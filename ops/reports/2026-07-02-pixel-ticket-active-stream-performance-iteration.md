# Pixel Ticket Active Stream Performance Iteration - 2026-07-02

Evidence:

- Baseline reports supplied by the user:
  - `ops/reports/2026-07-01-deployment-resource-cut-analysis.md`
  - `ops/reports/2026-07-02-deployment-resource-cut-analysis.md`
- Fresh active-stream sample after the 2 FPS steady-stream change:
  - `ops/evidence/pixel-active-stream-cost-after-2fps-20260702T111317Z`
- Post page-guard cadence sample:
  - `ops/evidence/pixel-active-stream-cost-after-guard-cadence-20260702T112355Z`
- Foreground-check cadence sample:
  - `ops/evidence/pixel-active-stream-cost-after-foreground-cadence-20260702T112943Z`
- 1 FPS steady-stream sample:
  - `ops/evidence/pixel-active-stream-cost-after-1fps-20260702T113949Z`
- Already-dark panel reassertion cut sample:
  - `ops/evidence/pixel-active-stream-cost-after-panel-reassert-cut-20260702T115006Z`
- Final adaptive foreground-guard sample:
  - `ops/evidence/pixel-active-stream-cost-after-adaptive-guard-20260702T115754Z`
- Local idle-timer countdown cadence sample:
  - `ops/evidence/pixel-active-stream-cost-after-idle-timer-local-20260702T225435Z`
- Panel guard read-first sample:
  - `ops/evidence/pixel-active-stream-cost-after-panel-guard-read-first-20260702T230338Z`
- Panel guard 5-second passive check sample:
  - `ops/evidence/pixel-active-stream-cost-after-panel-guard-5s-20260702T230849Z`
- Warm steady sample after 5-second passive panel checks:
  - `ops/evidence/pixel-active-stream-cost-warm-steady-after-panel-guard-5s-20260702T231155Z`
- Final warm steady sample after 30-second passive panel checks:
  - `ops/evidence/pixel-active-stream-cost-warm-steady-after-panel-guard-30s-20260702T231619Z`
- Final public browser and database verification:
  - `ops/evidence/pixel-public-ticket-final-verification-20260702T232434Z`

## What Changed

1. Quiet steady streaming now runs at 1 FPS instead of the original 4 FPS.
2. Startup, reconnect, keyframe, and control-code moments still burst at 8 FPS.
3. Healthy live-stream ViVi page revalidation runs every 5 seconds instead of roughly every 2 seconds.
4. The foreground escape check stays fast during startup, recovery, control-code work, and real violations, but backs off to 5 seconds once the stream is live and ViVi ticket detail is confirmed.
5. Repeated panel-sleep reassertions no longer run the full 1.5-second panel write loop when the raw panel already reads dark. Initial panel sleep and visible restore still keep the stronger 1.5-second convergence hold.
6. The browser now renders the visible inactivity countdown locally between phone updates. The phone still owns timeout truth, broadcasts immediately when activity changes, sends steady countdown updates every 5 seconds, and returns to 1-second updates in the final minute.
7. Passive panel-dark verification now runs every 30 seconds instead of every 500 ms or 5 seconds. Event-triggered reassertions still run immediately, and failed guard writes still retry after 500 ms.

These changes target repeated work during the common static-ticket viewing state. They do not remove the fast path used when the stream opens, reconnects, or runs a control-code request.

## Measured Result

| Sample | Stream state | FPS mode | Pixel sampled CPU | Battery temp | Notes |
| --- | --- | --- | ---: | ---: | --- |
| July 1 active report | live, 1 video client | 4 FPS steady / 8 FPS burst | 46.6% avg | 37.8 C avg | Original comparable active-stream report. |
| After 2 FPS change | live, 1 video client | 2 FPS steady | 54.2% avg | 37.9 C avg | Detailed collector inflated CPU; proved FPS cut but exposed guard overhead. |
| After page-guard cadence | live, 1 video client | 2 FPS steady | 51.8% avg | 37.8 C avg | ViVi page proof cadence stretched to about 5 seconds. |
| Foreground-check cadence build | live, 1 video client | 2 FPS steady | 45.7% avg | 37.9 C avg | Includes 2 FPS, 5-second page proof, and 1.5-second foreground check. |
| 1 FPS build, steady-only | live, 1 local video client | 1 FPS steady | 33.7% avg | 35.7 C avg | Best observed CPU result; frame age max stayed under 1 second. |
| Panel reassert cut, steady-only | live, 1 local video client | 1 FPS steady | 36.1% avg | 34.8 C avg | Removed long 1.5-second repeated panel loops; only short 250 ms reassertions appeared. |
| Final adaptive guard, steady-only | live, 1 local video client | 1 FPS steady | 36.1% avg | 34.9 C avg | No long panel loops; fewer short panel reassertions; only two foreground window probes appeared in top samples. |
| Local idle-timer cadence, steady-only | live, 1 local video client | 1 FPS steady | 35.4% avg | 32.2 C avg | Control text messages dropped from 170 to 47 in a similar local watch; idle countdown rows were 36 instead of roughly one per second. |
| Panel guard 5-second sample, steady-only | live, 1 local video client | 1 FPS steady | 28.7% avg | 33.3 C avg | No sampled panel writes or brightness reads during the steady subset; frame age max 572 ms. |
| Final warm steady 30-second guard sample | live, 1 local video client | 1 FPS steady | 15.2% avg app process | 32.8 C avg | Warmed for 60 seconds first; one sampled brightness read, no sampled foreground/orientation guard rows, frame age max 961 ms. |

The absolute CPU samples include measurement overhead from `top`, `adb`, `su`, root-policy shell work, and Android media services. Treat them as directional, not lab-grade. The strongest proof from the final samples is that the repeated long panel-dark loop disappeared, passive root panel checks became rare, and the stream stayed fresh.

The exact final build reaches the half-cost target for the warm steady Pixel Orchestrator app process: 46.6% baseline to 15.2% average after warm-up. The broader phone still pays real capture cost through the rooted H.264 helper and Android media codec; the final warm sample measured about 55.5% combined app/root-capture/media in sampled `top` rows, with the collector's own `top` command averaging about 37.4%. That combined number is useful for understanding where the remaining work lives, but it is not directly comparable to the original app-process baseline.

The idle-timer change is a cleanup of needless control-plane work, not the main CPU breakthrough. The comparable local control socket went from 170 text messages over about 2 minutes 40 seconds to 47 text messages over about 2 minutes 45 seconds while the stream stayed live at 1 FPS. The visible browser timer remains smooth because the browser counts down locally between phone updates.

The warm-up boundary is now explicit: first-frame/startup, portrait lock, secure-capture setup, keyframe burst, and active recovery are allowed to be more expensive. After roughly 60 seconds of quiet viewing, the app should settle into the warm steady profile: 1 FPS, no routine foreground/orientation rows in the sampled window, rare passive panel proof, frame age under 1 second, and immediate cleanup when the viewer leaves.

## Verification

- Unit/build checks passed:
  - focused `testDebugUnitTest` for touch brightness, screen brightness, and ticket service source tests
  - `testReleaseUnitTest`
  - `assembleDebug`
- Pixel deployment passed through direct APK install for the final 30-second guard build. After reinstall, the supervisor brought the ticket server back to ready state.
- Final local warm-steady stream verification passed:
  - one local control socket and one local video socket attached,
  - Pixel health reported live stream with one video client,
  - all 12 final warm samples were steady 1 FPS with `currentIntervalMillis=1000`,
  - latest frame age max was 961 ms,
  - video socket received live H.264 frames,
  - after the sample, the local viewer stopped and delayed health reported no active clients, no active encoder, secure capture bypass inactive, and notification lockdown inactive.
- Final public Chrome verification passed on this exact final build:
  - public ticket page opened at `https://ticket.jolkins.id.lv/`,
  - spinner cleared,
  - live ticket canvas stayed visible,
  - browser submitted a 4-digit control code request,
  - Pixel acted on it,
  - browser received the generated control-code screen,
  - Pixel reported `status=succeeded`, `totalDurationMillis=5223`, `browserCaptureAckMillis=2309`, and `resultDeliveryMillis=2848`,
  - after the browser tabs were closed, delayed Pixel health returned to `sessionState=client_disconnected`, `streamActive=false`, `clients=0`, `streamVerdict=idle`, `captureMode=idle`, and `encoderRunning=false`.
- Final Spacetime verification passed:
  - recent logs still arrived as immediate individual rows,
  - the last-30-minute post-verification log sample contained 264 rows,
  - the sampled key events no longer showed the repeated dead-route recovery failures that previously dominated the table,
  - the metrics dashboard window showed bytes scanned per second in the hundreds of bytes and reducer compute in microseconds.

## Remaining Cost

The obvious waste removed in this pass:

1. Too many steady-state frames for a static ticket.
2. Full 1.5-second panel-dark reassertions even when the panel was already dark.
3. Startup/recovery-style foreground checking continuing during stable passive viewing.
4. Per-second phone-to-browser inactivity countdown broadcasts during a quiet 10-minute ticket watch.
5. Repeated passive root panel-brightness verification during stable viewing.

The remaining real cost is still the rooted H.264 path itself: Pixel Orchestrator, the rooted capture helper, Android media codec, and secure screen capture. Further cuts should be adaptive and visually verified, not blanket quality reductions.

Next safe candidates:

1. Measure a longer active stream without heavy collector overhead.
2. Consider an even lower static-ticket cadence only if the ticket remains visually readable and control-code/reconnect bursts stay fast.
3. Keep watching for root helper, media codec, or foreground guard work that appears while no real browser viewer is present.
