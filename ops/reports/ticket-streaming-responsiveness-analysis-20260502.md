# Ticket Streaming Responsiveness Analysis - 2026-05-02

## Summary

This pass analyzed both directions of the ticket stream:

- Pixel to browser: screen capture, encoding, WebSocket transport, browser decoding, and recovery.
- Browser to Pixel: taps, keys, safety gates, and root input execution.

The first analysis pass did not change product behavior, public interface, runtime setting, or source code path. This report was later updated with the 2026-05-02 and 2026-05-03 stabilization passes and live after-measurements. The original baseline was a local Android service on `127.0.0.1:9388`, exposed through Cloudflare Tunnel, with separate control and video WebSockets and root `screenrecord` H.264 decoded by browser WebCodecs. As of the 2026-05-03 reboot recovery pass, the normal public path is root-only lossless PNG frames through the same freshness envelope; H.264, AV1, and Android MediaProjection are no longer the default public stream path.

The existing ticket unit tests passed before this report was written:

```text
./gradlew :app:testDebugUnitTest --tests 'lv.jolkins.pixelorchestrator.app.ticket.*'
BUILD SUCCESSFUL
```

Live read-only verification was also possible against the connected Pixel at `100.76.50.43:5555`:

- `pixel-ticket-health.sh` exited successfully.
- The local ticket service was listening on `127.0.0.1:9388`.
- `cloudflared` metrics were listening on `127.0.0.1:20388`.
- Local `/api/v1/health` returned service health.
- Public `/api/v1/health` returned a Cloudflare Access redirect, so unauthenticated public health cannot be used as a direct tunnel timing probe.

A controlled browser verification was then run through a temporary local ADB forward. The first browser start did not render a frame within 30 seconds and the page returned to `Session ended`, but phone health showed the session was actually active and frames were being sent. A second browser start/reconnect against that active session reached a visible non-black frame in about 2.3 seconds. The session was then explicitly stopped, and health confirmed `streamActive=false`, notification lockdown inactive, and secure-window bypass inactive.

## Current Flow

### Startup to First Visible Frame

1. `pixel-ticket-start.sh` starts `SupervisorService`, opens `MainActivity`, and starts the ticket tunnel loop if enabled.
2. `SupervisorService` starts `TicketStreamService`.
3. The browser opens `/`, creates a control WebSocket to `/api/v1/session`, then sends `{"type":"start"}`.
4. `TicketStreamService.startTicketSession()` checks ViVi installation, probes root `screenrecord`, applies session guards, launches or confirms ViVi, starts the foreground guard, and marks the session active.
5. The browser opens a separate video WebSocket to `/api/v1/stream`.
6. When a video client exists, the service sends a stream config, starts root capture, parses H.264 frames, and sends binary frames.
7. The browser configures `VideoDecoder`, waits for a key frame, draws the first decoded frame to canvas, then marks the stream ready.

Important code paths:

- Local HTTP/WebSocket server: `TicketStreamService.kt`
- Root H.264 capture: `TicketRootCaptureEngine.kt`
- Browser stream client: `browserPage()` inside `TicketStreamService.kt`
- Tunnel loop: `ticket-web-tunnel-service-loop.sh`

### Frame Path

Root capture starts `screenrecord --output-format=h264 --size 540x... --bit-rate 1200000 --time-limit 180 -`. The service parses NAL units, prepends SPS/PPS to IDR frames, wraps each frame in a 9-byte envelope, and writes one WebSocket binary frame per video frame.

The browser waits for a key frame before decoding deltas. It requests key frames on video socket open and after decoder setup. For root H.264, a key-frame request can restart root capture when the last key frame is missing or older than 2 seconds.

### Input Path

The browser only forwards taps and a narrow set of keyboard inputs. The server maps browser canvas coordinates back to device coordinates, applies input gates, then sends Android `input tap`, `input text`, or `input keyevent` through a persistent root shell.

Most normal taps use the fast path. Higher-risk taps, keyboard entry, popup closing, and post-tap checks can trigger `dumpsys window`, `uiautomator dump`, accessibility snapshots, or fixed waits.

### Tunnel Path

The tunnel loop renders a Cloudflare config for `ticket.jolkins.id.lv`, runs `cloudflared tunnel --config ... run`, and checks the process every 15 seconds. The current public endpoint is protected by Cloudflare Access, which is good for control but means unauthenticated health probes cannot measure public tunnel health directly.

## Ranked Opportunities

| Rank | Opportunity | Expected gain | Risk | Confidence |
| --- | --- | --- | --- | --- |
| 1 | Add end-to-end timing before changing behavior | High | Low | High |
| 2 | Reduce root capture restarts and key-frame restart pressure | High | Medium | High |
| 3 | Decouple frame production from blocking WebSocket writes | High | Medium | High |
| 4 | Make tunnel startup/restart idempotent and faster to detect failure | High | Medium | High |
| 5 | Fix browser/session state divergence during startup | Medium to High | Medium | Medium |
| 6 | Shorten browser first-frame and stale-frame recovery windows | Medium to High | Medium | Medium |
| 7 | Keep tap input on the fast path and narrow slow safety checks | Medium to High | Medium to High | Medium |
| 8 | Batch or cache control-code keyboard checks | Medium | Medium | Medium |
| 9 | Revisit stream quality knobs only after latency is measured | Medium | Low to Medium | Medium |

### 1. Add End-to-End Timing Before Changing Behavior

The health model exposes useful counters and "age" values, and the browser logs first-frame sampling events. It does not yet provide one clean timeline for:

- page loaded
- control WebSocket opened
- start command sent
- session marked active
- video WebSocket opened
- config received
- first key frame received
- first frame decoded and painted
- tap sent
- tap accepted by the server
- root input command completed
- next visible frame after the input

This should be the first implementation step after this report. It will prevent guessing and will make later changes easy to rank.

Recommended measurement additions:

- Browser client logs with `performance.now()` deltas for startup, config, first key frame, first decoded frame, stale-frame recovery, tap send, and key send.
- Server logs for WebSocket accept, start handling, root capture start, first frame, first key frame, per-command root input duration, and slow frame writes.
- Health fields or recent events for first-frame latency, last input command duration, frame byte size, and slow-client drops if added later.

### 2. Reduce Root Capture Restarts and Key-Frame Restart Pressure

Root capture uses `screenrecord`, and root H.264 cannot request an IDR frame directly. The current fallback is to restart capture when a key frame is missing or older than 2 seconds. That is simple, but it is also expensive: it kills and restarts the capture process, then waits for a new encoder stream.

Live runtime state supports this being a hot path:

- `frames`: 9412
- `keyFrames`: 48
- `keyFrameRequests`: 183
- `rootCaptureRestarts`: 235
- configured max FPS: 10

At 10 FPS, 9412 frames is roughly 15.7 minutes of captured video. A 180-second `screenrecord` time limit would explain only a few restarts, not hundreds. The likely high-gain question is how often key-frame requests or recovery paths restart capture unnecessarily.

Recommended next changes:

- Instrument restart reason counts separately from total restarts.
- Debounce root key-frame restarts more aggressively, especially while a fresh capture has just started.
- On video reconnect, prefer waiting briefly for an incoming IDR before restarting root capture.
- Consider keeping root capture warm while a session is active, even across brief video socket reconnects, if battery/thermal impact is acceptable.

### 3. Decouple Frame Production From Blocking WebSocket Writes

`broadcastFrame()` allocates a new frame buffer, then calls `sendBinary()` on each video client. `TicketWebSocket.sendFrame()` writes to the socket and flushes synchronously. A slow or half-dead video client can therefore block frame delivery and can also slow the root capture reader, because frame parsing and send calls sit in the same path.

This matters most through Cloudflare Tunnel, where writes can stall during reconnects or network hiccups.

Recommended next changes:

- Give each video client a small outbound queue.
- Drop stale delta frames when a client falls behind.
- Keep at most one pending key frame and the newest delta frame per slow client.
- Close clients that remain blocked past a short threshold.
- Log slow writes and dropped frames so the browser's perceived stalls can be tied back to transport behavior.

### 4. Make Tunnel Startup and Restart More Idempotent

`pixel-ticket-start.sh` calls `cleanup_extra_tunnel_loops` before checking whether the existing tunnel loop is valid. That can kill a healthy tunnel during repeated starts or redeploy-adjacent flows. The loop itself checks `cloudflared` every 15 seconds and sleeps 2 seconds after start.

Live logs show many `starting cloudflared for ticket host=ticket.jolkins.id.lv` entries over May 1-2, and recent `cloudflared` logs show repeated tunnel starts. Some restarts may be expected during development, but this is a strong candidate for public-stream responsiveness because it affects the browser's connection path before the app code sees traffic.

Recommended next changes:

- Check the existing loop and `cloudflared` process before cleanup.
- Only kill processes proven stale or duplicated.
- Reduce failure detection time, or replace fixed polling with a child-process wait loop.
- Expose tunnel readiness and last restart reason in health or logs.

### 5. Fix Browser/Session State Divergence During Startup

The controlled browser check exposed a concrete startup issue: the phone session became active and root capture was sending frames, but the browser page still fell back to `Session ended` and closed both sockets. Health then showed `streamActive=true`, `rootCapture.active=true`, `videoClients=1`, and recent client telemetry with abnormal socket closes. Retrying the browser connection succeeded quickly.

This is a user-visible responsiveness issue because the phone can already be doing the expensive work while the browser believes the session ended.

Recommended next changes:

- Log the exact reason `endSession()` or `suspendAutoStart()` transitions the page to ended during startup.
- When the browser sees an ended local state but server health says `streamActive=true`, prefer reconnecting to video before showing `Session ended`.
- Clear stale `autoStartBlockedReason` effects after an explicit manual Start more aggressively on the browser side.
- Add a regression test around "manual Start after inactivity timeout" so the page does not strand itself while the phone is active.

### 6. Shorten Browser Recovery Windows Carefully

The browser currently waits up to:

- 10 seconds for control WebSocket open timeout.
- 10 seconds for video WebSocket open timeout.
- 7 seconds after config before treating missing first frame as a recovery case.
- 8 seconds after last frame before treating the stream as stale.
- 3 seconds between self-heal checks.

These values favor stability, but they are long enough that a viewer can feel the stream is frozen. Once timing logs exist, the no-first-frame and stale-frame windows are likely good candidates to lower or make adaptive.

Recommended next changes:

- Request a key frame earlier after config if no key frame arrives.
- Trigger soft video reconnect before full stream restart.
- Use shorter stale-frame thresholds when the page is foregrounded and the network is local or recently healthy.
- Preserve conservative thresholds when the page is backgrounded or Cloudflare/network errors are present.

### 7. Keep Normal Taps Fast; Narrow Slow Safety Checks

The normal tap path is already designed well: it uses a cached foreground decision and a dedicated root shell. The slow paths are safety checks:

- Suspicious coordinates trigger `uiautomator dump`.
- Keyboard entry requires control-code popup verification unless cached.
- Post-tap checks run after a 350 ms delay and may inspect foreground state and hierarchy.
- Active session guard can run ViVi enforcement every 2 seconds.

These checks protect the phone from dangerous remote control, so they should not be removed broadly. The gain is in measuring and narrowing when they run.

Recommended next changes:

- Log root input duration separately from safety-check duration.
- Prefer accessibility snapshots over `uiautomator dump` when available and fresh.
- Cache safe ViVi regions for a short time after a successful page classification.
- Avoid running hierarchy checks immediately after low-risk taps unless the tap lands near protected regions.

### 8. Cache and Batch Control-Code Keyboard Work

Keyboard input is intentionally restricted to the control-code popup. That is a good safety boundary, but each key can still depend on popup detection, input focus, and root input. The current cache lasts 2 seconds, and focusing the input includes a 120 ms settle delay.

Recommended next changes:

- Measure per-key latency from browser keydown to root command completion.
- Extend the popup-ready cache while key events are flowing and reset it on popup close or foreground violation.
- Batch fast alphanumeric key sequences into fewer root `input text` commands when the popup is confirmed and focused.
- Keep Escape and close actions strict and separately checked.

### 9. Treat Quality Knobs as Secondary

The current root path uses 540-pixel width, 1.2 Mbps, and 10 FPS. This keeps bandwidth and decode cost low, but 10 FPS means visual feedback updates in 100 ms steps before network and decode are included.

Do not tune FPS, width, or bitrate first. The higher-confidence wins are first-frame timing, root restarts, blocked writes, and input gate timing. After those are measured, a small FPS increase can be evaluated against heat, bandwidth, and decode stability.

## Validation Notes

Completed:

- Static mapping of ticket startup, capture, WebSocket, browser decode, input, policy, and tunnel paths.
- Ticket-focused unit test run: passed.
- Read-only live checks against the Pixel:
  - Ticket health script passed.
  - Local service and tunnel metrics ports were listening.
  - Local health endpoint returned current service state.
  - Public endpoint was reachable but Cloudflare Access protected.
- Controlled local-browser check through ADB forwarding:
  - First manual start did not render within 30 seconds and returned the page to `Session ended`.
  - Phone health showed the stream was active and frames were being sent during that browser-ended state.
  - A second browser start/reconnect reached a visible non-black frame in about 2.3 seconds.
  - The session was explicitly stopped afterward, and health confirmed the protective session settings were restored.

Not completed:

- Tap-to-visible-feedback timing.
- Forced tunnel restart timing.
- Control-code entry timing.

Those remaining live behavioral checks require intentionally tapping or entering keys in the ticket app. This report avoided those input actions because they can affect the ticket state.

## Current Measured Losses

Measured on 2026-05-02 against the connected Pixel 9a at `100.76.50.43:5555`, using a temporary ADB forward to the local ticket service and the in-app browser.

### Ranked live losses

| Rank | Current loss | Observed impact | Confidence |
| --- | --- | --- | --- |
| 1 | Root capture is restarting far too often and leaves old `screenrecord` processes behind | During a 126.9 second live polling window, root capture restarts rose by 22 while only 814 frames were sent. That is about 10.4 restarts per minute and about 6.4 delivered FPS against a 10 FPS target. After the run, 10 actual `screenrecord` binaries were still present; after final stop/start cleanup, 14 remained. | High |
| 2 | Browser state can be visibly wrong while the stream is working | The browser screenshot showed a non-black live phone frame, but the page still displayed `Waiting for ticket stream...`. Health simultaneously showed duplicate clients: 2 control sockets and 2 video sockets. | High |
| 3 | Reconnect-to-visible-frame is several seconds, not instant | On browser reload, the first new visible-frame sample arrived about 4.8 seconds after reload started. A second visible-frame sample arrived about 14.0 seconds after reload, likely from the duplicate client path. | Medium |
| 4 | Stop/cleanup is not reliable while clients are present | Closing the measurement tab and sending an explicit stop still led to the session becoming active again briefly, with notification lockdown and secure-window bypass active. A full ticket stop/start was needed to return the service to an inactive stream state. | High |
| 5 | First open is polluted by auto-start and duplicate-client behavior | After a clean service start, browser load auto-started the session without a visible Start button. Health showed first-frame samples, but the browser DOM stayed in `Waiting` for the full 20 second wait. Exact first-frame timing is therefore mixed with state divergence. | Medium |
| 6 | Tap gate decisions are not the main current loss | A center tap was allowed and a protected edge tap was blocked within the health polling interval, roughly under 0.3 seconds. The current health model does not expose root command completion time, so tap-to-command duration still needs direct instrumentation. | Medium |
| 7 | Tunnel startup is measurable but not the biggest observed loss | Full ticket start returned local health in about 1.2 seconds and tunnel metrics in about 2.6 seconds. The public health URL returned Cloudflare Access `302` in about 87 ms, so unauthenticated public probes still cannot measure end-to-end authenticated stream startup. | High |

### Raw timing notes

- Full ticket stop script: about 1.3 seconds.
- Full ticket start script: about 1.1 seconds.
- Local health ready after start: about 1.2 seconds.
- Cloudflared metrics ready after start: about 2.6 seconds.
- Public `/api/v1/health`: Cloudflare Access `302`, about 86.6 ms total from this machine.
- Browser cold open:
  - Page loaded in about 0.6 seconds.
  - Auto-start ran immediately; there was no Start button by the time the DOM was inspected.
  - The browser remained textually stuck on `Waiting for ticket stream...` after about 20 seconds, even though screenshots and client telemetry showed visible frames.
- Browser reload/reconnect:
  - Reload loaded in about 0.3 seconds.
  - First derived visible-frame sample: about 4.8 seconds after reload start.
  - Second derived visible-frame sample: about 14.0 seconds after reload start.
  - Page text still remained `Waiting for ticket stream...`.
- Live frame/capture window:
  - Duration: about 126.9 seconds.
  - Frames sent: +814.
  - Key frames: +6.
  - Root capture restarts: +22.
  - Runtime snapshot after input: 1,146 frames, 9 key frames, 41 root capture restarts, 50 key-frame requests.
- Input:
  - Center tap: allowed with reason `vivi_tap_allowed`; decision appeared within the next health sample.
  - Edge tap: blocked with reason `remote_protected_tap_blocked`; decision appeared within the next health sample.
  - Control-code key entry was skipped because `controlCodeMode.active` was false and no confirmed control-code popup was present.
- Cleanup:
  - A full final stop/start reset left `streamActive=false`, notification lockdown inactive, and secure-window bypass inactive.
  - That final health check still reported 2 connected clients and 14 leftover `screenrecord` binaries, so cleanup is not fully clean even when the stream is inactive.

## Post-Fix Measured Results

Measured on the same Pixel 9a after the ticket-screen redeploy on 2026-05-02. The run used a temporary local ADB forward and a persistent local browser, then removed the forwards after cleanup.

### Before/after summary

| Area | Before | After | Result |
| --- | --- | --- | --- |
| Reload to visible frame | About 4.8 seconds | About 0.7 seconds | Better than 2x target |
| Start to visible frame | Cold/manual timing was polluted by state divergence | About 0.9-1.2 seconds through the browser start path | Better than 2.4 second target |
| Root capture restarts | About 10.4/min during the measured window | 0 restarts during a 129.4 second stable window | Better than 2/min target |
| Duplicate browser clients | 2 control and 2 video sockets after reload | 1 control and 1 video while active; 0 after Stop | Fixed |
| Explicit Stop cleanup | Stream could become active again; protections could remain on; old captures remained | Stream inactive, clients 0, secure-window bypass off, input gate inactive, no active app-owned `screenrecord` left | Fixed |
| Stable delivered FPS | About 6.4 FPS during the previous noisy run | About 4.3 FPS on a mostly static ticket screen | Not proven fixed; no slow writes or restart churn remained |

### Raw after timings

- Direct browser start path to visible frame: about 0.9 seconds. A prior deliberate start run measured about 1.2 seconds.
- Browser reload to visible frame: about 0.7 seconds.
- Stable stream window:
  - Duration: about 129.4 seconds.
  - Frames sent: +559.
  - Root capture restarts: +0.
  - Key frames: +1.
  - Dropped stale frames: +13.
  - Slow socket writes: +0.
  - Closed slow clients: +0.
- Input:
  - Safe tap: accepted in about 0.5 seconds wall time; root tap command duration was 345 ms.
  - Protected top-edge tap: blocked in about 0.24 seconds; no new root tap was sent.
  - Control-code key entry: skipped because the control-code popup was not visible.
- Tunnel:
  - Cloudflared metrics stayed reachable on `127.0.0.1:20388`.
- Cleanup:
  - Final health: `streamActive=false`, control clients 0, video clients 0, secure-window bypass inactive, input gate inactive, control-code mode inactive.
  - Temporary ADB forwards were removed.

### Remaining caveat

The FPS target is not proven met. After the restart cleanup fix, the stream no longer showed slow writes, slow-client closures, duplicate clients, or restart churn, but the root recorder emitted about 4.3 FPS while the ticket screen was mostly static. That may be Android `screenrecord` producing fewer frames when the screen changes little; it needs a motion-heavy check before treating this as a remaining transport loss.

### What this means

The original biggest user-visible losses were not raw tap handling or Cloudflare startup. They were video pipeline churn and state divergence:

- Root capture restart churn is now removed during stable streaming.
- Browser visible-frame state now reaches streaming and reload recovers in under a second.
- Duplicate same-viewer sockets are replaced or closed.
- Explicit Stop now leaves the stream inactive and cleanup complete.

The remaining uncertainty is stable FPS on a static screen. The next measurement should use visible motion so frame-production limits can be separated from normal static-screen behavior.

## Broad Loading And Recovery MVP Update

Measured after redeploying `ticket-stream-2026-05-02-v20` on 2026-05-02.

### What changed in the final MVP pass

- Browser loading now records the broad phases separately: health, control socket, video socket, session active, config, and first live frame.
- Repeated start commands are now idempotent while a stream is already active, so reload/self-heal does not relaunch the phone-side flow.
- The service now keeps one current control socket and one current video socket, replacing older sockets of the same kind, including legacy/no-identity sockets.
- Static or quiet frames no longer immediately force the browser into `Connecting`; the watchdog first asks for a keyframe while keeping the live stream state.
- Cached keyframes are kept long enough for static ticket-screen periods, and root keyframe restarts are delayed to avoid churn.
- Root capture restart cleanup now kills child `screenrecord` processes during restarts, not only on explicit stop.

### v20 measured results

| Check | Result |
| --- | --- |
| Clean deploy state | `streamActive=false`, clients `0`, protections off, no `screenrecord` processes. |
| Local initial open to first live frame | Browser telemetry reported `2.756s`. This is much better than the original reload loss, but still above the ideal 2 second initial-load target. |
| Local reload to first live frame | `230ms`, with one control socket and one video socket. |
| Stable stream window | About `145s` from start to Stop with `0` root restarts, `0` slow writes, and no active duplicate clients. |
| Control-code area tap | Accepted, root tap command `303ms`, popup reached `CONTROL_CODE_POPUP`, stream stayed active, no loading-over-budget event. |
| Control-code key path | Backspace sent only after popup was confirmed active; root key command `171ms`, stream stayed active. |
| Explicit Stop cleanup | `streamActive=false`, clients `0`, notification lockdown inactive, secure-window bypass inactive, input gate inactive, control-code mode inactive, and no app-owned `screenrecord` process left. |

### Public Brave verification

The current Brave tab and cookies were preserved. Public verification is still inconclusive: navigating Brave to `https://ticket.jolkins.id.lv/?mvp=20260502v20-public-check` did not reach the Pixel origin during the measurement window. Local service health stayed stopped with `clients=0`, and Cloudflare tunnel metrics still showed `cloudflared_tunnel_total_requests 0` and `cloudflared_tunnel_request_errors 0`. An unauthenticated command-line probe still returns a Cloudflare Access `302`, which is expected and does not measure the authenticated browser path.

The Pixel-side stream path is verified locally; the remaining public check is specifically Brave/Cloudflare Access/browser reachability, not a measured stream pipeline failure.

## Fully Authenticated Public Loading Pass

Measured in the existing authenticated Brave Work profile after deploying public `ticket-remote-2026-05-02-public-sub2s-v7` and Pixel `ticket-stream-2026-05-02-v22`.

### What was fixed

- The public site was confirmed to be a `ticket_remote` shell in front of the Pixel stream, not the Pixel HTML served directly. The public shell now has the same stale-cache protections as the Pixel page: no-store root/API responses, versioned bootstrap proof, and a legacy cache cleanup path that preserves Cloudflare Access cookies and browser storage.
- Public browser sockets now carry viewer, page, and page-version identity. A newer page in the same authenticated session replaces older same-session control/video sockets, so reloads do not leave duplicate public clients behind.
- The relay now passes page identity through to the Pixel using the query names the Pixel expects, so Pixel health can prove the current public page version.
- Public video delivery now keeps a cached keyframe for fast joins, sends keyframe requests on the phone video socket first, and uses a non-blocking per-browser video queue so slow clients do not stall the stream.
- The browser no longer treats a quiet/static ticket screen as an immediate outage. It asks for keyframes while staying live, and only shows recovery loading after a longer real no-frame gap.
- Public key forwarding was added for the existing private-control path. The Pixel still enforces the final safety rule: keys only run when the control-code popup is confirmed visible.

### Public timing results

| Check | Result |
| --- | --- |
| Latest public page proof | Brave loaded `ticket-remote-2026-05-02-public-sub2s-v7`; bootstrap reported Pixel `ticket-stream-2026-05-02-v22`; root/bootstrap responses were no-store with Cloudflare `DYNAMIC`. |
| Fresh authenticated open | First measured v7 open: 2.118s external sample, with app telemetry first frame at 1.316s. Second steady-state open: 1.735s external sample. |
| Authenticated reload | 1.458s external sample; app telemetry first frame at 0.995s. |
| Control-code area tap | No loading state observed; stream stayed live; page moved to private control. |
| Control-code key path | Backspace was sent only while the popup was active; Pixel command duration was 192ms; gate reason `control_code_popup_key`. |
| Protected edge tap | Blocked with `remote_protected_tap_blocked`; stream stayed live and connected. |
| 8-second stability watch | Stayed live; one control socket and one video socket; no browser decode error; no slow-client buildup. |
| Explicit cleanup | After closing Brave and the leftover `browser-use` `ticket-public` session, final Pixel health reported `streamActive=false`, clients `0`, protections off, input gate inactive, and no active `screenrecord`. Public relay health reported clients `[]`, viewers `0`, and phone stream idle. Temporary ADB forwards were removed. |

### Raw evidence

- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-version-proof.json`
- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-initial-measure.json`
- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-initial-second-measure.json`
- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-reload-measure.json`
- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-stability-watch.json`
- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-input-checks.json`
- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-final-pixel-cleanup.json`
- `ops/evidence/ticket-streaming-20260502/public-authed-sub2s/public-v7-final-remote-cleanup.json`

### Remaining caveat

The first v7 public open had one external sample at 2.118s, while the page's own first-frame telemetry for that same navigation was 1.316s and the next fresh open measured 1.735s externally. The remaining variability appears to be public navigation and tunnel/auth timing rather than Pixel stream startup; authenticated reload and steady-state open are under the two-second target.

## Fresh-Keyframe Balanced Public Pass

Measured in the existing authenticated Brave Work profile after deploying Pixel `ticket-stream-2026-05-02-v25` and public `ticket-remote-2026-05-02-fresh-keyframes-balanced-v9`.

### What changed

- Old video is now rejected end to end. Frames carry a stream epoch, frame sequence, timestamp, and keyframe flag. The public relay and browser drop old epochs, duplicate or lower frame sequences, and legacy frames after the current envelope is negotiated.
- Cached keyframes are now only allowed when they are from the current stream epoch and younger than about 1.5 seconds. If the cache is older, the browser asks for a fresh keyframe instead of drawing stale video.
- The balanced quality profile is active: 720 pixel stream width, 1616 pixel height on this Pixel, 3 Mbps bitrate, and 10 FPS target.
- Public video stays visible to all authenticated viewers. Private-control mode now gates input only; it does not black out or pause non-controller video.
- Decoder recovery is browser-local first. A decoder problem reconnects that browser path instead of restarting the shared phone stream unless Pixel health shows the stream itself is unhealthy.

### Public Brave timing results

| Check | Result |
| --- | --- |
| Latest version proof | Public page `ticket-remote-2026-05-02-fresh-keyframes-balanced-v9`; Pixel config `ticket-stream-2026-05-02-v25`; frame envelope `tsf2`; quality `balanced`. |
| Fresh authenticated open | App telemetry first live frame at `3.236s`; external wait sample `3.417s`; wall sample `4.302s`. This is stable but still above the ideal 2 second target. |
| Authenticated reload | App telemetry first live frame at `2.069s`; external wait sample `2.514s`; wall sample `2.754s`. This is close to the target and no stale frame was drawn. |
| Control-code area tap | Stream did not interrupt. The page was already live again in the next sample: `4ms` wait, `7ms` wall. |
| Two authenticated viewers | Brave main session plus an isolated authenticated viewer both received live video. Health showed two control clients, two video clients, `activeVideoClients=2`, and `phoneViewers=2`. |
| Second viewer reload while first watched | Reloading the isolated viewer reached live video again in `2.859s`; the first viewer stayed live throughout; health still showed two active video clients. |
| Cleanup | After closing temporary pages and sending explicit Stop, Pixel health reported `streamActive=false`, clients `0`, notification lockdown inactive, secure-window bypass inactive, input gate inactive, and no active `screenrecord`. Public relay health reported `activeVideoClients=0`, `phoneConnected=false`, `phoneDesired=false`, and `phoneViewers=0`. Temporary ADB forwards were removed. |

### Quality and freshness evidence

- Native decoded canvas PNGs:
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/brave-initial-canvas.png`
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/brave-reload-canvas.png`
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/brave-control-tap-canvas.png`
- Raw timing and health:
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/brave-public-pass.json`
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/brave-two-viewer-pass.json`
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/final-pixel-cleanup.json`
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/final-remote-cleanup.json`
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/final-screenrecord-check.txt`
  - `ops/evidence/ticket-streaming-20260502/fresh-keyframe-balanced/final-adb-forward-list.txt`
- Health samples during the pass showed `width=720`, `height=1616`, `bitrate=3000000`, `fps=10`, `qualityProfile=balanced`, `frameEnvelope=tsf2`, and `freshKeyFrameCacheMaxAgeMillis=1500`.
- Example frame sizes were about `22-25 KB` for keyframes and about `3-20 KB` for recent delta frames, depending on screen change.
- `staleFramesDropped` increased during verification, which confirms old or no-longer-current frames were being rejected rather than drawn.

### Remaining caveat

The stale-keyframe defect is fixed, and the Aztec/ticket stream is now delivered at the balanced 720px/3 Mbps profile. The remaining timing blocker is first fresh keyframe availability from Android `screenrecord`. Because root `screenrecord` cannot request an IDR frame directly, the system sometimes has to restart capture and wait for the encoder to emit a fresh keyframe. That is why fresh open still measured above two seconds in this run, while control-code interaction stayed effectively instant and reload was close to target.

## Durable Ticket Service Toggle And Restart Recovery Pass

Measured on 2026-05-03 after deploying the Pixel orchestrator/ticket service and public `ticket_remote` to production.

### What changed

- The Android orchestrator now has a persisted Ticket Service toggle. The default is off. When on, it keeps the local ticket server and tunnel ready after service start, package replace, and phone boot.
- The ready path does not open ViVi or start stream capture. ViVi and capture start only after an authenticated public viewer connects.
- The public relay reconnect path was fixed so a new viewer restarts the phone reconnect loop even if a stale desired state survived a phone reboot or bridge failure.
- ViVi recovery now uses a root launch fallback for `com.pv.vivi/.MainActivity`, which fixed the post-reboot case where the phone stayed in Messages and the recovery loop tapped the wrong app.
- Every remote tap now requires a fresh ViVi foreground check. If ViVi cannot be proven foreground, the tap is blocked before Android input is sent.
- Private-control keyframe pulses run once per second for the active controller only. In this run, control mode produced pulse counters and phone keyframe requests without adding extra viewers or affecting other video clients.

### Live deployment and reboot results

| Check | Result |
| --- | --- |
| Pixel deploy | APK installed on `100.76.50.43:5555`; `ticket_screen` redeploy reported success. |
| Public relay deploy | `ticket_remote` and `ticket_phone_bridge` redeployed on Arbuzas; health reported `ticket-remote-2026-05-03-durable-control-keyframes-v10`. |
| Toggle ON before reboot | UI switch turned on; readiness became `ready`; local server and tunnel ready; stream idle; clients `0`; capture off. |
| Phone reboot | ADB returned after about `18.6s`. Immediately after boot, health showed toggle enabled, readiness `ready`, local server ready, tunnel ready, stream idle, clients `0`, and capture off. |
| Authenticated Brave public open after reboot | Public page reached the Pixel through the relay and drew the first live frame. Page telemetry reported first live frame at about `5.555s`; the delay is still dominated by cold root capture/keyframe startup, not service/tunnel readiness. |
| ViVi recovery after reboot | Recovery brought ViVi to ticket detail `PV-ELB-20260423-0RJB2M`; foreground became `com.pv.vivi/.MainActivity`. |
| Public relay reconnect after reboot | Fixed. After redeploy, `ticket_remote` connected to the phone bridge and reported `phoneConnected=true`, `phoneViewers=1`, `phoneStreamState=streaming`. |
| Protected edge tap | Blocked with `remote_protected_tap_blocked`. |
| Non-ViVi foreground tap | After forcing Messages foreground, a controller tap was blocked with `vivi_not_foreground_for_tap:left_vivi_app`; no Android tap was sent to Messages. |
| Controller keyframe cadence | Active-control health showed `intervalMillis=1000`, `pulses=54`, and controller-only phone keyframe requests. |
| Explicit Stop cleanup | Pixel reported `state=stopped`, `streamActive=false`, clients `0`, root capture inactive, notification lockdown inactive, secure-window bypass inactive, and no `screenrecord` process. Public relay reported clients `[]`, `phoneConnected=false`, `phoneDesired=false`, and `phoneViewers=0`. |
| Toggle OFF | UI switch off stopped the local ticket listener and tunnel; local health became unreachable and only the orchestrator process remained. |
| Toggle ON final state | UI switch on restored readiness to `ready`; local server and tunnel ready; stream idle; clients `0`; capture off. This is the final left-running state. |

### Remaining caveat

The durable service goal is met: after reboot the ticket server and tunnel come back without manual action, and no stream capture starts until a viewer opens the public page. The remaining sub-2s cold-open gap is root capture/keyframe startup. Warm control-mode reload improved but still measured around `2.5s` by app telemetry in this pass, because the current root `screenrecord` path cannot directly force an IDR keyframe every second without restarts. The next true latency step is still a capture path with direct keyframe control.

## Remaining Follow-Up Order

1. If sub-2s fresh open is mandatory, replace or augment root `screenrecord` with a capture path that can request a fresh keyframe directly.
2. Run one motion-heavy stream check to decide whether the remaining low FPS on static screens is real loss or normal static-screen behavior.
3. If motion still stays well below 10 FPS, inspect the root frame source before changing bitrate or FPS defaults again.
4. Keep watching public Safari/iOS behavior. A leftover `browser-use` mobile-profile session showed Safari can still hit decoder recovery after long static periods; authenticated Brave v9 stayed stable in the measured watch.

## Control-Mode Stability And Sub-500ms Tap Pass

Measured on 2026-05-03 using the existing authenticated Brave Work session and the 10-minute poll evidence in `ops/evidence/ticket-streaming-20260503/ten-minute-poll-20260502T233624Z`.

### What changed

- Normal private-control release and expiry no longer ask the Pixel for a fresh ticket reset. They send a soft control exit instead.
- The Pixel no longer uses `FRESH_RESET`, `am force-stop`, or a return-to-Orchestrator path for normal control release, control expiry, foreground guard activity, or control-code popup close.
- Browser taps and keys now carry an `inputId`. The Pixel replies with `input_result`, including accepted/blocked status, reason, total phone-side time, and phase timings. The browser now treats this as the real outcome of input.
- The browser queues one latest control-code tap during claim/reconnect/startup and drops stale queued taps.
- The control-code button zone now gets transition grace immediately, so foreground recovery cannot race the user’s control-code tap.
- Stale post-tap checks can no longer re-enter control mode after control release.
- The soft exit path can close the control-code popup with one bounded safe close attempt, and successful close immediately returns ticket health to live.

### Poll findings used as baseline

| Finding | Evidence | Effect |
| --- | --- | --- |
| Control expiry used hard reset | Poll showed `recovery_scheduled detail=control_expired mode=FRESH_RESET`, then ViVi was force-stopped. | ViVi could close and the phone could land in Orchestrator after a normal session end. |
| Recovery stayed active while stream was usable | Poll had `recovering` in most samples even while stream, clients, and ticket detail were present. | The browser could stay confused and slow despite live video. |
| Root taps were already fast | Accepted root taps in the poll were usually about `68-208ms`. | The main loss was surrounding checks/recovery, not Android input itself. |

### Public Brave timing results

| Check | Result |
| --- | --- |
| Deployed Pixel build | Final live Pixel version `ticket-stream-2026-05-03-control-stability-v35`; public relay stayed on `ticket-remote-2026-05-03-control-stability-v14`. |
| Queued control-code tap | Accepted by the phone in `462ms` on v27; browser result was `769ms` because the claim/reconnect path had to finish first. |
| Active-control tap | Accepted by the phone in `314ms`; browser result was `419ms`. |
| Final v34 control-code tap | Accepted by the phone in `486ms`; phases showed `remote_ticket_tap` completed and returned an `input_result`. |
| Control release | Returned to `live`, left ViVi on ticket detail, kept `controlCodeMode.active=false`, and kept `hardResetCount=0`. |
| Popup close evidence | v33/v34 screenshots confirmed the control-code popup closes and the ticket view remains visible. |
| 10-minute watch | 120 samples over about `688s`. The stream was live in 113 samples and the public relay was streaming in 119 samples. The watch exposed one remaining destructive path: `viewer_inactivity_timeout` still caused a ViVi hard reset once in v34. |
| v35 inactivity fix | Viewer inactivity now stops the stream and blocks browser auto-start without scheduling fresh recovery, force-stopping ViVi, or returning to Orchestrator. This was unit-tested, redeployed, and final cleanup health showed v35 with `hardResetCount=0`. |
| Explicit cleanup | Final Pixel cleanup reported `streamActive=false`, clients `0`, root capture inactive, notification lockdown inactive, secure-window bypass inactive, service readiness `ready`, and `hardResetCount=0`. Public relay cleanup settled to clients `[]`, `phoneDesired=false`, `phoneConnected=false`, and `phoneViewers=0`. |
| Remaining over-budget state | Control exit still recorded an over-budget state around `3.7s` when the popup was not visible or the phone UI dump was slow. This no longer causes reset or Orchestrator fallback; it is now diagnostic. |

### Evidence

- `ops/evidence/ticket-streaming-20260503/control-stability-v12/brave-control-code-tap-v27.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/brave-active-control-tap-v27.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/brave-control-code-center-release-v33.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/brave-canvas-after-release-v33.png`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/brave-control-code-center-release-v34.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/final-pixel-cleanup-v34.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/final-remote-cleanup-v34.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/final-screenrecord-check-v34.txt`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/ten-minute-watch-v34.jsonl`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/ten-minute-watch-v34-summary.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/final-pixel-cleanup-v35.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/final-remote-cleanup-v35.json`
- `ops/evidence/ticket-streaming-20260503/control-stability-v12/final-screenrecord-check-v35.txt`

### Remaining caveat

The accepted tap target is now met on the phone side in the final run (`486ms`). The release path is safe and no longer resets ViVi, and the additional 10-minute watch finding around viewer inactivity was fixed in v35. The final v35 change was verified by unit/source tests and deploy/cleanup health, but a second full 10-minute watch was not repeated after v35 because the last code change was limited to the inactivity reset policy.

The diagnostic state timer can still exceed one second because Android UI hierarchy reads can take about `3.4s` on this device. That delay no longer blocks the stream or forces recovery; it is the next measurement target if the release-state timer itself must also be sub-second.

## AV1 Clarity And Burn-In-Safe Brightness Pass

Measured on 2026-05-03 using the existing authenticated Brave Work profile. Cloudflare Access was already valid, so no mailbox code was needed. Final deployed Pixel version was `ticket-stream-2026-05-03-av1-clarity-v41`; public relay stayed on `ticket-remote-2026-05-03-av1-clarity-v16`.

### What changed

- Public ticket streaming now uses MediaProjection hardware AV1 as the normal path. Root H.264 remains emergency-only and is not silently used when AV1 setup fails.
- The AV1 clarity profile is full phone width, `1080x2424` on this Pixel, about `8 Mbps`, `10 FPS`, 1-second keyframe interval, and a 1-second static-frame repeat cadence so quiet ticket screens still emit fresh current-epoch frames.
- The browser proves AV1 WebCodecs support before starting. Brave reported AV1 support in the authenticated session.
- MediaProjection approvals are treated as single-use after an encoder virtual display is created. Stop/viewer-idle cleanup now stops the encoder and consumed projection, then root-assists a fresh permission pre-arm while the durable ticket service remains enabled.
- Browser Stop and public relay idle park capture instead of leaving an AV1 encoder running without viewers.
- Brightness guard now keeps the panel at the safe dim target after Stop and cleanup. It does not restore maximum brightness while the ticket service remains enabled.

### Public Brave timing results

| Check | Result |
| --- | --- |
| First open after APK install | `11.471s` external wait; app telemetry first live frame at `9.620s`. This includes one-time Android capture-permission setup after app reinstall. |
| Authenticated reload | `2.344s` external wait; app telemetry first live frame at `1.341s`. The native decoded canvas was clean. |
| Post-Stop reopen after pre-arm | `7.125s` external wait; app telemetry first live frame at `5.521s`. This is functional but still above target. The remaining delay is before stream config arrives, likely root/phone session startup work rather than browser decode. |
| Post-Stop reload | `1.980s` external wait; app telemetry first live frame at `1.304s`. |
| Final cleanup | Pixel health reported stream inactive, clients `0`, encoder stopped, capture mode idle, notification lockdown inactive, secure-window bypass inactive, projection pre-armed, and brightness guard active. Brave had no open `ticket.jolkins.id.lv` tabs. |

### Clarity evidence

- Native Brave canvas: `ops/evidence/ticket-streaming-20260503/av1-clarity-brightness-v36/brave-av1-v41-first-reload-canvas.png`
- Pixel screenshot: `ops/evidence/ticket-streaming-20260503/av1-clarity-brightness-v36/pixel-v41-current.png`
- Brave Aztec crop metrics: `midGrayContaminationPercent=2.987`, `contrastP95MinusP05=254`, `blackPixelPercent=45.219`, `whitePixelPercent=51.793`.
- The v41 canvas shows crisp black/white Aztec blocks with no visible previous-frame gray residue.
- Earlier v39/v40 attempts were rejected during the pass: v39's 100ms static repeat could preserve startup artifacts, and v40 could repeat a black startup frame. The final v41 cadence is 1 second.

### Brightness and cleanup evidence

- After explicit Stop, health showed `streamActive=false`, clients `0`, `encoderRunning=false`, `captureMode=idle`, `projectionReady=true`, and protections off.
- Brightness guard remained active with `targetPercent=1`; direct Android settings showed `screen_brightness=1`.
- `screenrecord` was not running; process checks only matched the shell command doing the check.
- Evidence files:
  - `ops/evidence/ticket-streaming-20260503/av1-clarity-brightness-v36/pixel-v41-final-health.json`
  - `ops/evidence/ticket-streaming-20260503/av1-clarity-brightness-v36/pixel-v41-final-brightness-process.txt`
  - `ops/evidence/ticket-streaming-20260503/av1-clarity-brightness-v36/brave-av1-v41-first-summary.json`
  - `ops/evidence/ticket-streaming-20260503/av1-clarity-brightness-v36/brave-av1-v41-post-stop-summary.json`

### Remaining blocker

Reload is at or near the 2-second goal and the image quality issue is fixed in the measured Brave evidence. Fresh open after Stop is still too slow at `7.125s`. Since decode after stream config is fast, the next target is the Pixel/relay session-start path before config: reduce serial root setup, avoid unnecessary ViVi recovery work when ticket detail is already visible, and make the public relay request phone start immediately after the first authenticated viewer arrives.

## Reboot Recovery And Root-Only Clear Stream Pass

Measured on 2026-05-03 after a real phone reboot, using the authenticated Brave Work profile and public `ticket.jolkins.id.lv` random paths. Final deployed versions were Pixel `ticket-stream-2026-05-03-root-png-v45` and public relay `ticket-remote-2026-05-03-root-png-v20`.

This pass supersedes the AV1 public path above. The public ticket stream now uses root `screencap` PNG frames as the normal path. Android MediaProjection permission, AV1 WebCodecs setup, and root `screenrecord` H.264 are not used silently for public ticket sessions.

### What changed

- Public sessions use `captureMode=root_screencap_png`, `transport=root-screencap-png`, `codec=png`, `qualityProfile=root_lossless_png`, and full display dimensions (`1080x2424` on this Pixel).
- Every PNG frame is treated as a fresh keyframe in the current stream epoch, so decoder smear and old keyframe replay cannot create gray Aztec block carryover.
- The browser draws PNG frames directly to canvas and does not initialize WebCodecs for the normal public path.
- After phone bridge disconnect, `ticket_remote` clears cached stream config and cached frame state. New browser sockets wait for the phone instead of receiving pre-reboot config.
- The public page now serves the latest app shell for arbitrary non-reserved paths, including `/reboot-root-png-v45-random-path` and `/reload-root-png-v45-random-path`; `/api/...`, `/static/...`, and `/admin...` remain reserved.
- The bridge loop now health-checks the Pixel before exposing traffic and can recreate stale ADB forwards. In this run it recovered through the ADB shell proxy path after EOF-style forward failures.
- Viewer demand after reboot launches ViVi and then starts root PNG capture. The durable service still keeps only the local server and tunnel ready until a viewer asks.

### Reboot and public Brave results

| Check | Result |
| --- | --- |
| After phone reboot | Pixel health returned with service readiness `ready`, local server reachable, tunnel ready, stream idle, clients `0`, and capture off. |
| Bridge recovery | Bridge logs showed `ticket phone bridge ready via adb shell proxy` after health probe failures. Public relay health settled with `phone.connected=false`, `phone.desired=false`, and no stale clients after cleanup. |
| Latest public page on arbitrary path | Brave loaded the v20 app shell from `https://ticket.jolkins.id.lv/reboot-root-png-v45-random-path` and later `https://ticket.jolkins.id.lv/reload-root-png-v45-random-path`; both returned HTML 200 and current static assets. |
| Cold public open after reboot | First stream config at `6.355s`, first PNG frame at `7.880s`, first visible live frame at `8.104s`. This no longer looped on stale config, but it is still above the 2-second target. |
| Active public reload/open | First stream config at `2.975s`, first PNG frame at `3.487s`, first visible live frame at `4.037s`. This used one video socket and no missing-config restart loop. |
| Stream mode proof | Pixel health and browser telemetry reported PNG root capture, full display dimensions, `tsf2` envelope, all frames keyframes, root capture restarts `0`, no `screenrecord`, and no MediaProjection permission UI. |
| ViVi recovery proof | After the cold open settled, Pixel health reported `sessionState=live`, `viviState.state=TICKET_DETAIL`, and ticket id `PV-ELB-20260423-0RJB2M`. |
| Clear ticket evidence | The final phone-side ticket image shows a crisp Aztec/ticket detail with no visible H.264/AV1 gray block carryover. |
| Startup reinitialization cap | The v45 cold and active checks each created one video socket and recorded no startup restart loop from missing config. |
| Final cleanup | Explicit Stop left `sessionState=stopped`, `streamActive=false`, clients `0`, notification lockdown inactive, secure-window bypass inactive, input gate inactive, root PNG capture inactive, and no `screenrecord` process. Public relay health showed clients `[]`, `phone.desired=false`, `phone.connected=false`, and `phone.viewers=0`. |
| Brightness after cleanup | Brightness guard stayed active at the safe dim target (`targetPercent=1`, panel value `39/3939`; Android `screen_brightness=1`). A delayed health check still showed the guard active and recently enforced after cleanup. |

### Evidence

- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/pixel-health-before-reboot.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/pixel-health-after-reboot-ready.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/relay-health-after-reboot-ready.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/final-bridge-logs-tail-v20.txt`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/public-open-v45/brave-cdp-check.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/public-open-v45/brave-canvas-root-png.png`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/public-reload-v45-active/brave-cdp-check.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/public-reload-v45-active/brave-canvas-root-png.png`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/phone-v45-after-settle-ticket-detail.png`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/final-pixel-health-v45.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/final-pixel-health-v45-delayed.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/final-remote-health-v20.json`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/final-screenrecord-check-v45.txt`
- `ops/evidence/ticket-streaming-20260503/root-png-reboot-v43/final-brightness-check-v45.txt`

### Remaining blocker

The reboot initialization loop and stale pre-reboot config problem are fixed, and the image path is now clear/root-only. The strict 2-second public target is not met yet: cold open after reboot measured `8.104s`, and an active public reload measured `4.037s`.

The remaining delay is no longer old config replay, WebCodecs setup, MediaProjection approval, or H.264 keyframe wait. In this run the main wait was before and around first root PNG config/frame delivery: public page navigation took about `1.5-2.1s`, and cold phone startup still had to launch/settle ViVi and begin root PNG capture. The next speed pass should reduce session-start serialization, keep the bridge warm for authenticated viewers without starting capture early, and start the first root PNG burst even closer to viewer demand while ViVi recovery continues in the background.

## Control Input Reliability And Clean Control Exit Pass

Measured on 2026-05-03 using the authenticated Brave Work profile and the deployed public site. Final deployed versions were Pixel `ticket-stream-2026-05-03-control-input-v46` and public relay `ticket-remote-2026-05-03-control-input-v21`.

This pass kept the stable root-PNG stream path unchanged and focused on making control-mode input reliable. Browser taps and keys are now queued in order, sent one at a time, and kept until the Pixel returns `input_result`. Retries reuse the same `inputId`, and the Pixel caches recent `input_result` messages so a reconnect retry cannot execute the same tap twice.

### What changed

- The first Kontroles kods tap is preserved while private control is being claimed, then delivered through the same ordered input path once the controller owns the session.
- Rapid controller taps and keys are no longer overwritten by later input. Later inputs wait their turn behind earlier acknowledged inputs.
- The browser logs `input_queued`, `input_dispatched`, `input_acknowledged`, `input_retry`, and `input_expired`, plus tap/key result timings.
- The Pixel records and resends cached duplicate `input_result` messages for repeated `inputId` values without running a second Android tap/key command.
- Control release/expiry uses a bounded soft exit. It closes the Kontroles kods popup/result when visible, requests a fresh root-PNG frame, and returns to normal ticket detail. It does not fresh-reset ViVi, force-stop ViVi, or return to Orchestrator for normal control exit.

### Live authenticated Brave results

| Check | Result |
| --- | --- |
| Public page version | Brave loaded `ticket-remote-2026-05-03-control-input-v21` from `https://ticket.jolkins.id.lv/control-input-v21-random-path`. |
| Pixel version | Pixel health reported `ticket-stream-2026-05-03-control-input-v46`. |
| Stream ready | The page reached first ready/live state in `7.333s` for this fresh test open. This pass did not target stream startup speed. |
| First Kontroles kods tap | The first tap at source `(250,330)` claimed control and landed on the phone as `tap:1`; Pixel accepted it in `441ms` and entered control-code mode. |
| Five rapid follow-up taps | Five safe focus taps were accepted in order as `tap:2` through `tap:6`; none were dropped or overwritten. |
| Phone-side accepted tap timings | `tap:2=389ms`, `tap:3=422ms`, `tap:4=254ms`, `tap:5=440ms`, `tap:6=316ms`. |
| Browser-side burst timing | Later taps took longer wall-clock time because they intentionally waited for earlier acknowledgements; this is expected for ordered delivery. |
| Duplicate execution | Pixel recent events showed `duplicateEvents=0` during the run. Duplicate-input replay is covered by the unit test path. |
| Control exit | Public release returned `200`; Pixel recorded `control_exit_popup_cancel duration_ms=85 ok=true`, then `control_exit_popup_closed` and `control_code_soft_check_ok popup_closed`. |
| Post-exit state | After settle, Pixel health showed `viviState.state=TICKET_DETAIL` with ticket id `PV-ELB-20260423-0RJB2M`; the window hierarchy showed the normal ticket detail and no control-code input popup. |
| Hard resets | `hardResetCount=0`; no force-stop or Orchestrator fallback happened. |
| Final cleanup | Explicit Stop left `sessionState=stopped`, `streamActive=false`, clients `0`, control-code mode inactive, root capture inactive, notification lockdown inactive, and no `screenrecord`. Brightness guard stayed active at the safe dim target. Public relay health showed no clients and no desired phone connection. |

### Evidence

- `ops/evidence/ticket-streaming-20260503/control-input-v21/brave-control-input-check.json`
- `ops/evidence/ticket-streaming-20260503/control-input-v21/remote-input-client-logs.txt`
- `ops/evidence/ticket-streaming-20260503/control-input-v21/pixel-health-after-release-settle.json`
- `ops/evidence/ticket-streaming-20260503/control-input-v21/window-after-release.xml`
- `ops/evidence/ticket-streaming-20260503/control-input-v21/final-pixel-health-v46.json`
- `ops/evidence/ticket-streaming-20260503/control-input-v21/final-remote-health-v21.json`
- `ops/evidence/ticket-streaming-20260503/control-input-v21/final-screenrecord-check-v46.txt`

### Remaining notes

The accepted phone-side tap target of about `500ms` was met for the first control-code tap and all five rapid safe taps. Later items in a burst can exceed `500ms` from browser click to final acknowledgement because reliability now deliberately serializes input instead of sending overlapping taps to ViVi.

The live test verified closing the control-code popup after release and returning to ticket detail. The special valid-code result close path is covered by source/unit tests, but a real current control code was not entered during this live pass.

## Clean Control Exit Result-State Pass

Measured and implemented on 2026-05-03 after the user supplied an example screenshot of the generated Kontroles kods Aztec/code result. Final deployed Pixel version for this pass was `ticket-stream-2026-05-03-clean-control-exit-v56`; the public relay remained on `ticket-remote-2026-05-03-clean-control-exit-v23`.

### What changed

- The generated control-code Aztec/code result is now modeled separately from normal ticket detail and the keyboard/input popup.
- Result detection no longer requires the `Kontroles kods` title. It detects the result from ViVi foreground, no editable input, a close affordance, and a short standalone entered-code value like the supplied screenshot.
- Normal release, expiry, accepted Escape/close, and controller departure all route through `control_exit` cleanup.
- Cleanup now uses fresh state reads for control-exit surfaces. It prefers accessibility-visible ViVi hierarchy and falls back to root hierarchy, avoiding stale pre-tap snapshots.
- If direct hierarchy reads are temporarily unavailable while ViVi is settling, cleanup can use a non-destructive Back-close attempt and then the existing ViVi-detail autopilot as a final soft cleanup fallback. It still must not use fresh reset, force-stop ViVi, or Orchestrator fallback.
- Health now exposes `controlExitCleanup` with reason, detected state, close action, duration, verification result, success, and fresh-frame request status.

### Verification

| Check | Result |
| --- | --- |
| Unit/source coverage | Passed. Generated result is classified as `CONTROL_CODE_RESULT` without the title; normal ticket detail is not misclassified; result/popup close paths verify ticket detail and request a fresh root-PNG frame. |
| Public relay/browser checks | Passed in `go test ./...` and `node --check`; relay sends `control_exit` for release, expiry, and controller disconnect, and accepted popup/result close input releases public control. |
| Live deployment | Pixel health reported `ticket-stream-2026-05-03-clean-control-exit-v56`, service ready, local server reachable, and tunnel ready after redeploy. |
| Live popup cleanup | Control release from an open Kontroles kods popup closed via `back_close`, verified `TICKET_DETAIL`, requested a fresh root-PNG frame, and did not hard-reset ViVi. The cleanup health duration was `3672ms`; most of that was ViVi settling and verification after the close. |
| Live cleanup final state | Final cleanup left `streamActive=false`, clients `0`, ViVi on `TICKET_DETAIL`, brightness guard active at the safe dim target, no app-owned `screenrecord`, and public relay viewers `0`. |
| Live special-result entry | Skipped. I did not enter a real current control code, so the generated result screen was not safely produced during this pass. |

### Evidence

- `ops/evidence/ticket-streaming-20260503/clean-control-exit-v47-v23/brave-clean-control-exit-v48-live-final.json`
- `ops/evidence/ticket-streaming-20260503/clean-control-exit-v47-v23/public-release-cleanup-v51-manual-popup.json`
- `ops/evidence/ticket-streaming-20260503/clean-control-exit-v47-v23/local-start-control-exit-v50-popup-active.json`
- `ops/evidence/ticket-streaming-20260503/clean-control-exit-v47-v23/local-start-control-exit-v56-popup-active.json`
- Final health from the live cleanup showed the stream stopped, zero clients, ViVi ticket detail visible, and brightness dim-safe.

### Follow-up Note

During live verification, a synthetic browser tap reached the phone in about `397-416ms`, but ViVi did not consistently open the control-code popup from that automated tap in every run. Direct ADB taps could open the popup in some states, and startup/foreground guard could also detect the popup. Because this pass was scoped to deterministic cleanup after control ends, the remaining tap-to-open inconsistency should be treated as a separate control-entry reliability investigation if it appears in normal Brave use.

## FFmpeg H.264 Quality Hard-Cutover Pass

Implemented and deployed on 2026-05-04. Final deployed Pixel version for this pass was `ticket-stream-2026-05-04-ffmpeg-h264-v62`; final deployed public relay version was `ticket-remote-2026-05-04-ffmpeg-h264-v25`.

This pass hard-cut the normal public stream path from root PNG to root-fed FFmpeg H.264. The goal was lower bandwidth and better practical motion while keeping the safety and freshness rules built during the PNG stabilization work.

### What changed

- Public streaming now reports `captureMode=root_ffmpeg_h264`, `transport=ffmpeg-h264-annexb`, `codec=avc1.42E01E`, and `qualityProfile=ffmpeg_h264_clarity`.
- The phone uses root `screencap` as the capture source and feeds full-resolution raw frames into FFmpeg in the Pixel chroot at `/data/local/pixel-stack/chroots/pihole/usr/bin/ffmpeg`.
- The first profile is conservative for ticket clarity: full `1080x2424`, `8 FPS`, about `8 Mbps`, no B-frames, repeated headers, and all frames marked as keyframes.
- The browser and public relay accept the FFmpeg H.264 path without falling back to PNG, AV1, MediaProjection, or `screenrecord`.
- Canvas drawing disables image smoothing so the decoded ticket image is not softened by browser scaling.
- FFmpeg cleanup was tightened after live verification found orphaned root `screencap` feeder loops. Final v62 closes the feeder pipe, makes the feeder shell exit on broken pipe, and runs root cleanup in a non-cancellable cleanup path.
- The public relay container now installs FFmpeg for validation/evidence only; live frames are still forwarded directly from the phone without relay re-encoding.

### Verification

| Check | Result |
| --- | --- |
| Pixel deployment | Pixel health reported `ticket-stream-2026-05-04-ffmpeg-h264-v62`, ticket service ready, local server reachable, tunnel ready, stream idle, and FFmpeg available. |
| Relay deployment | Arbuzas public relay reported `ticket-remote-2026-05-04-ffmpeg-h264-v25`, FFmpeg available in the container, no viewers, and no stale phone connection after cleanup. |
| Local stream config | Probe received FFmpeg config with `root_ffmpeg_h264`, `ffmpeg-h264-annexb`, `avc1.42E01E`, `1080x2424`, `8 FPS`, `8 Mbps`, and `tsf2` frame envelope. |
| Freshness envelope | Final sample frame had `TSF2`, current epoch `39976598`, sequence `18`, keyframe flag `1`, and H.264 payload bytes `19703`. |
| Local first frame | First frame arrived at `5.370s` in the final v62 local probe. This is reliable but still above the 2-second target. |
| Live frame timings | Final FFmpeg health showed root read/write/frame timing around `440ms + 15ms = 455ms` for the last measured frame, `20` encoded frames, `20` keyframes, dropped frames `0`, and restart count `0`. |
| Cleanup | Final health after Stop showed `streamActive=false`, clients `0`, FFmpeg inactive, feeder inactive, capture idle, secure-window bypass inactive, and no active `ffmpeg`, `screenrecord`, or root `screencap` feeder processes in `ps`. |
| Brightness | Brightness guard stayed active at safe dim brightness after cleanup: target `1%`, panel `39/3939`, last enforced within a few seconds. |
| Quality evidence | A live TSF2/H.264 sample was saved and decoded to PNG on the Mac; a Pixel-side screenshot was also saved for source comparison. Authenticated Brave CDP was not available on port `9222` in this run, so native Brave canvas evidence was not captured during this pass. |

### Evidence

- `ops/evidence/ticket-streaming-20260504/ffmpeg-h264-v62/local-ffmpeg-stream-probe.json`
- `ops/evidence/ticket-streaming-20260504/ffmpeg-h264-v60/sample-frame.tsf2`
- `ops/evidence/ticket-streaming-20260504/ffmpeg-h264-v60/sample-frame.h264`
- `ops/evidence/ticket-streaming-20260504/ffmpeg-h264-v60/sample-frame-decoded.png`
- `ops/evidence/ticket-streaming-20260504/ffmpeg-h264-v60/sample-frame.json`
- `ops/evidence/ticket-streaming-20260504/ffmpeg-h264-v60/pixel-screenshot-after-ffmpeg-probe.png`

### Remaining notes

The hard cutover is complete and deployed, but the first FFmpeg profile is not yet a speed win. The remaining delay is mostly in the root capture/FFmpeg startup path and full-screen `screencap` read cost, not stale config replay or browser decoder setup. The last measured full-resolution root frame read took about `440ms`, which is too slow for a smooth 8 FPS stream even though the stream stayed stable.

The next optimization should keep the same public contract but reduce capture cost: crop to the ticket card/Aztec region when full-screen is unnecessary, evaluate lower source dimensions only with clarity metrics, or replace `screencap` with a faster persistent root framebuffer/minicap-style source if available. Bitrate can then be lowered only after the decoded Aztec evidence remains clean.

## Native Capture Speed Pass

Implemented and deployed on 2026-05-04. Final Pixel version for this pass was `ticket-stream-2026-05-04-native-capture-v65`; the public relay remained on `ticket-remote-2026-05-04-ffmpeg-h264-v25`.

This pass kept the public browser contract unchanged (`root_ffmpeg_h264`, `ffmpeg-h264-annexb`, `avc1.42E01E`) and replaced the slow root `screencap` feeder with a persistent root `app_process` screen-capture helper feeding FFmpeg.

### What changed

- Added a root native screen-capture helper using Android's current `ScreenCapture` API from `app_process`.
- Fixed the helper command handoff so width, height, and FPS are passed into the real streaming process instead of only the standalone probe.
- Fixed an expected pipe-close crash during Stop so stream cleanup no longer kills the ticket service.
- Moved the active profile from full `1080x2424` to measured near-full `900x2020`. Full resolution worked in isolation but was too heavy beside FFmpeg; the 900px profile stayed live and avoided helper kills.
- Kept the same all-intra FFmpeg H.264 clarity profile, no MediaProjection, no `screenrecord`, no public PNG fallback, and the same `tsf2` freshness envelope.

### Verification

| Check | Result |
| --- | --- |
| Pixel deployment | Pixel health reported `ticket-stream-2026-05-04-native-capture-v65`, ticket service enabled, local server reachable, tunnel ready, and FFmpeg available. |
| Stream config | Local probe received `captureMode=root_ffmpeg_h264`, `captureSource=root_surface_capture`, `captureMethod=app_process_screen_capture`, `width=900`, `height=2020`, `8 FPS`, `8 Mbps`, and `tsf2`. |
| Frame delivery | After the argument handoff fix, the local socket probe received current H.264 frames. A warm local reconnect while the stream was active reached first frame in about `135-169ms`. |
| Capture timing | Final live health showed the native helper plus FFmpeg path around `131ms read + 14ms write = 145ms` on the last sampled frame. A prior sample was `159ms + 23ms = 182ms`. This is about 2-3x better than the v62 full-screen `screencap` read/write path around `455ms`. |
| Live relay state | Pixel health showed the public relay connected with page version `ticket-remote-2026-05-04-ffmpeg-h264-v25`. After explicit local Stop, relay sockets reconnected but capture stayed idle, as expected. |
| Restarts | FFmpeg restart count stayed `0`; stderr was empty after the command handoff fix. |
| Cleanup | Explicit Stop left capture mode `idle`, encoder inactive, FFmpeg inactive, native helper inactive, and no active `ffmpeg`, `TicketRootSurfaceCaptureMain`, `screenrecord`, or stale `screencap` feeder processes. |
| Final live state | After cleanup, the public relay reconnected and requested the stream again. Final health showed the product live on v65 with relay control/video sockets, `900x2020`, estimated send bitrate about `5.7 Mbps`, last frame timing about `190ms + 15ms = 205ms`, restart count `0`, and brightness still dim-safe. |
| Brightness | Brightness guard remained active at the safe dim target: `1%`, panel `39/3939`. |
| Tests | Android ticket unit tests passed. `go test ./...` passed in `ticket-remote`. `node --check internal/web/static/app.js` passed. |

### Remaining notes

The speed pass is a real improvement, but it is not a perfect 8 FPS solution yet. The active profile's final frame timing around `145-182ms` means the phone is closer to roughly `4-6 FPS` in practice, depending on load. That is a useful improvement over the old full-screen `screencap` path, and the live product remains stable, but the next major speed jump likely needs a lower-copy native path or region/crop capture rather than more tuning around bitmap copies.

The final cleanup state intentionally left the durable ticket service ready and the public relay sockets connected, but no capture running. The next authenticated viewer can start the stream without the phone being left hot.

### Evidence

- `ops/evidence/ticket-streaming-20260504/native-capture-v65/local-warm-stream-probe.json`
- `ops/evidence/ticket-streaming-20260504/native-capture-v65/final-pixel-health-v65.json`
- `ops/evidence/ticket-streaming-20260504/native-capture-v65/final-pixel-health-v65-cleanup.json`
- `ops/evidence/ticket-streaming-20260504/native-capture-v65/final-pixel-health-v65-live.json`
- `ops/evidence/ticket-streaming-20260504/native-capture-v65/final-process-check-v65.txt`

## Public Deployment Recovery v70

Implemented and verified on 2026-05-04 after the authenticated Brave page was stuck in a waiting state.

### What happened

- Pixel v65/v66/v67/v68 could start the FFmpeg H.264 path, but authenticated Brave continued to reject the public stream with H.264 WebCodecs decode errors.
- The public relay and browser recovery code were improved so decoder failures reconnect the affected browser path instead of restarting the phone stream.
- Because the promoted FFmpeg H.264 path still did not produce a visible user-facing stream in Brave, the live product was restored to the known-good root lossless PNG path in Pixel v70.
- The public relay page version was bumped to `ticket-remote-2026-05-04-root-png-recovery-v28` so Brave can prove it is running the latest page code.
- The Pixel active guard was tightened so a later successful ticket-detail check clears stale `needs_attention` state from an earlier failed recovery.

### Verification

| Check | Result |
| --- | --- |
| Pixel deployment | Pixel health reported `ticket-stream-2026-05-04-root-png-recovery-v70`, service ready, tunnel ready, stream active, root PNG capture active, full `1080x2424`, `codec=png`, and `transport=root-screencap-png`. |
| Phone state | A Pixel-side screenshot showed ViVi on the normal ticket detail screen with the live/self-updating Aztec ticket visible. |
| Public relay | Relay health showed the phone connected, one authenticated viewer, `root-screencap-png`, fresh frame sequence `195`, browser decode error empty, and recent `png_frame_received` plus `png_frame_drawn` client events. |
| User-side Brave proof | A macOS screenshot of the existing authenticated Brave window showed `ticket.jolkins.id.lv` rendering the live ticket image instead of `Gaida tālruni...` or `Gaida biļetes straumi...`. |
| Loading | Relay client telemetry showed PNG frames drawn and loading finished at `first_live_frame_drawn`; one observed loading completion during the recovery pass was `1683ms`. |

### Remaining notes

The live product is working again on the root PNG path. This is intentionally the reliability rollback: it costs more bandwidth and smoothness than the FFmpeg path, but it preserves ticket clarity and avoids the H.264 decode failure seen in authenticated Brave. FFmpeg H.264 should not be promoted again until direct authenticated Brave evidence shows the page opens, decodes, and stays live after deployment.

### Evidence

- `ops/evidence/ticket-streaming-20260504/public-recovery-v70/brave-final-live-ticket.png`
- `ops/evidence/ticket-streaming-20260504/public-recovery-v70/pixel-health-v70.json`
- `ops/evidence/ticket-streaming-20260504/public-recovery-v70/relay-health-v28.json`

## Scroll Status Stability And Compute Quieting Pass

Implemented and deployed on 2026-05-05 as public relay `ticket-remote-2026-05-05-status-compute-v31`.

### What changed

- The scroll-menu status line is now a stable presentation state. Repeated equivalent states are debounced, lower-priority phone/health messages do not overwrite a current live/control state, and the control countdown updates locally instead of forcing status rewrites.
- The status line has a fixed two-line-height area so wording changes do not make the menu jump.
- Noisy browser events such as `png_frame_received`, `png_frame_drawn`, repeated loading phases, and stale-frame retry waits are summarized instead of posted for every occurrence.
- Healthy live pages slow health polling to `45s`; startup/recovery still uses faster polling.
- Cached state broadcasts are throttled to a `5s` interval for quiet live viewing; control/admin mutations still broadcast immediately.
- `/api/v1/health.telemetry` now exposes client-log, suppression, aggregate, and state-broadcast counters.
- Ticket docs now record the authenticated Brave verification rule: preserve the Brave Work profile, and if Cloudflare Access asks again, use the newest code from macOS notification or Apple Mail for `ticket@jolkins.id.lv`.

### Top compute cuts

| Source | Before | After v31 | Result |
| --- | --- | --- | --- |
| Per-frame browser telemetry | Recent relay telemetry was filled with raw `png_frame_received`/`png_frame_drawn` events for each frame. | Recent telemetry shows `*_summary` events about every 10s; one sample summarized 9-10 frame logs with 8-9 suppressed. | Fewer browser POSTs and server log lines while preserving frame evidence. |
| Repeated status rendering | Main status was rewritten from state/health paths, including the one-second control timer path. | Live watch reported `updates=1`, `debounced=1079`, stable key `watching`. | The scroll menu stayed visually stable over the watch. |
| Cached state broadcasts | Cached state was sent every second while clients existed. | Health showed `stateBroadcasts=72` and `stateBroadcastSuppressed=217` after the watch. | Countdown noise is kept local to the browser; server still sends periodic state and immediate mutation updates. |

### Verification

| Check | Result |
| --- | --- |
| Tests | `go test ./...` passed in `ticket-remote`; `node --check internal/web/static/app.js` passed. |
| Deploy | Arbuzas deploy and built-in validation passed. Containers ran `ticket-remote-2026-05-05-status-compute-v31`. |
| Public Brave live page | Existing authenticated Brave Work page force-reloaded to v31 and showed the live ticket. Relay telemetry recorded first live frame after the v31 page boot in `378ms`. |
| Scroll-menu stability | After scrolling to the menu, the status stayed on `Vispārīga skatīšanās` over the watch; the second screenshot after about two minutes showed the same stable status and layout. |
| Stream state | Relay health after the watch showed `activeVideoClients=1`, phone connected, root PNG stream active, and latest frame age `83ms`. |
| Compute counters | `/api/v1/health.telemetry` after the watch showed `clientLogsReceived=100`, `clientLogsWritten=82`, `clientLogsSuppressed=18`, `clientLogAggregates=63`, `stateBroadcasts=72`, and `stateBroadcastSuppressed=217`. |
| Spacetime cache | Health after the watch showed member-cache hits, presence throttling, and no stale fallback: `memberCacheHits=104`, `memberCacheMisses=0`, `presenceWrites=9`, `presenceThrottled=13`, `staleFallbacks=0`. |

### Evidence

- `ops/evidence/ticket-streaming-20260505/status-compute-v31/pre-deploy-relay-health.json`
- `ops/evidence/ticket-streaming-20260505/status-compute-v31/post-deploy-relay-health.json`
- `ops/evidence/ticket-streaming-20260505/status-compute-v31/post-watch-relay-health.json`
- `ops/evidence/ticket-streaming-20260505/status-compute-v31/final-relay-health.json`
- `ops/evidence/ticket-streaming-20260505/status-compute-v31/brave-v31-live-screen.png`
- `ops/evidence/ticket-streaming-20260505/status-compute-v31/brave-v31-status-menu.png`
- `ops/evidence/ticket-streaming-20260505/status-compute-v31/brave-v31-status-menu-after-2m.png`
- `ops/evidence/ticket-streaming-20260505/status-compute-v31/brave-v31-final-live-screen.png`
