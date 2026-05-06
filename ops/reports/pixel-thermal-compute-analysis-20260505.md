# Pixel Thermal And Compute Analysis - 2026-05-05

## Summary

This was an analysis-only sweep of the Pixel while it appeared idle. No phone processes were killed, no settings were changed, no services were restarted, and nothing was deployed.

The phone was reachable throughout the pass. The strongest finding is a runaway root `tr '\000' ' '` process that stayed at the top of CPU use for every one of the 60 idle samples. Ticket streaming itself was not active: the local ticket health reported `streamActive=false` and `clients=0`, while the durable ticket service and tunnel were ready.

Evidence root: `ops/evidence/pixel-thermal-compute-20260505/`

## Measurement Window

| Item | Result |
| --- | --- |
| Poll samples | 60 |
| Poll window | `20260505T141743Z` to `20260505T145019Z` |
| Battery temperature | 37.7-37.9 C, average 37.79 C |
| Virtual skin temperature | 39.13 C in the sampled summaries |
| Thermal status | `1` for all 60 samples: mild thermal pressure |
| Top process in poll | `tr \000` for all 60 samples |
| Top process CPU | 87.5-143%, average 117.46% |
| Charging state | AC powered, battery held around 80% |
| Ticket stream state | Inactive, 0 clients |

Raw summaries:

- `ops/evidence/pixel-thermal-compute-20260505/poll/idle-poll-summary.csv`
- `ops/evidence/pixel-thermal-compute-20260505/poll/poll-stats.json`
- `ops/evidence/pixel-thermal-compute-20260505/baseline/`
- `ops/evidence/pixel-thermal-compute-20260505/final/`

## Ranked Findings

| Rank | Source | Observed impact | Confidence | User value | Next action |
| --- | --- | --- | --- | --- | --- |
| 1 | Orphaned root `tr '\000' ' '` process | Used about one full CPU core for the whole poll. It had been running for more than a day and was still active at the final snapshot. | High | None once orphaned | Add hard timeouts and process-group cleanup around root health/readiness scripts; clean existing orphan during the implementation pass. |
| 2 | Ticket/Orchestrator root health probe churn | Repeated `su -c ... pixel-management-health` / `resolve_probe_curl` shells appeared in 46 of 60 samples. Some snapshots showed the Orchestrator app and root shell work spiking while the ticket stream was inactive. | Medium-high | Medium: readiness visibility | Cache/space health probes, avoid nested root shell probes on every periodic check, and make ticket tunnel readiness checks cheap when state has not changed. |
| 3 | Screen/display held awake during part of idle window | Baseline power state showed `SCREEN_BRIGHT_WAKE_LOCK 'lv.jolkins.pixelorchestrator:TicketStream'` while ticket health reported no clients and inactive stream. It was later released, but the device remained awake and charging. | Medium | Low while no viewer is active | Release ticket display wake locks immediately when capture stops or clients reach zero; keep brightness dimming, but let the screen sleep when no physical/user-facing session needs it. |

## Details

### 1. Runaway `tr '\000'` Process

The hot process was PID `32430`, parented by stale root shell PIDs `32421` and `32404`. It appeared as:

```text
32404     1 root S sh -s
32421 32404 root S sh -s
32430 32421 root R tr \000
```

It consumed roughly one core for the entire poll:

```text
samples=60
top_args_counts={"tr \\000": 60}
top_cpu_avg=117.46%
top_cpu_max=143.0%
```

The repo-side match is the ticket readiness path that reads `/proc/$pid/cmdline` through `tr '\000' ' '`. The copied evidence includes the current `SupervisorService` ticket readiness snippet and ticket health script snippets:

- `ops/evidence/pixel-thermal-compute-20260505/repo/supervisor-ticket-probe-excerpt.kt`
- `ops/evidence/pixel-thermal-compute-20260505/repo/pixel-ticket-health-pid-cmdline-excerpt.sh`
- `ops/evidence/pixel-thermal-compute-20260505/repo/ticket-web-tunnel-pid-cmdline-excerpt.sh`

The likely failure mode is: a root readiness/health shell got orphaned, and its `tr` child kept spinning after the caller was gone. The script path needs both local command timeouts and parent-side process-group cleanup so child commands cannot survive a timeout/cancel.

### 2. Root Health Probe Churn

Project process samples showed these recurring components across the 60 poll samples:

```text
runaway_tr: 60/60
ticket_tunnel_loop: 60/60
ticket_cloudflared: 60/60
tailscaled: 60/60
dropbear: 60/60
vivi: 60/60
orchestrator_app: 60/60
orchestrator_root_health_shell: 46/60
pixel_management_health: 22/60
```

Some health probes are expected. The waste is the repeated nested root shell work when the device is otherwise idle and ticket streaming is inactive. Final health still showed the ticket service was ready, so this is not user-visible streaming work:

```text
ticket server: ready
streamActive: false
clients: 0
rootCapture.active: false
brightnessGuard.active: true
```

Recommended implementation pass:

- Add a cheap cached readiness state for ticket tunnel and local server checks.
- Avoid running the full management health script on every supervisor health pass when inputs have not changed.
- Put a strict timeout around every `/proc/$pid/cmdline` read, including the Kotlin inline script path.
- Ensure root executor cancellation kills the whole child process group, not only the parent shell.

### 3. Display/Wake State

At baseline, power state showed a ticket screen wake lock while the ticket stream was inactive:

```text
Wake Locks: size=1
SCREEN_BRIGHT_WAKE_LOCK 'lv.jolkins.pixelorchestrator:TicketStream'
```

The final snapshot showed the wake lock had released:

```text
Wake Locks: size=0
mWakefulness=Awake
mHoldingDisplaySuspendBlocker=true
```

This suggests the wake lock was not the constant 30-minute heat source, but it did keep the panel awake during part of the idle period. Because the ticket stream health already had `clients=0`, that wake lock should be released sooner.

Recommended implementation pass:

- Treat `streamActive=false && clients=0` as a hard condition to release ticket wake locks.
- Keep dim brightness protection while ticket service is enabled, but allow screen sleep when no viewer or physical session is active.
- Add health fields for current wake-lock ownership and last release reason so this is visible next time.

## Component Cost Notes

| Component | Observed state | Idle cost impression |
| --- | --- | --- |
| Ticket stream capture | Inactive, 0 clients, no active root capture | Not the current heat source |
| Ticket tunnel on Pixel | `cloudflared` active, low CPU in `dumpsys cpuinfo` around 0.8% | Acceptable always-ready cost |
| Tailscale | Active, usually low single-digit CPU | Recovery-critical; do not disable casually |
| SSH/dropbear | Active listener, no meaningful CPU | Recovery-critical; do not disable casually |
| ViVi | Running, low CPU | Not a major heat source in this window |
| Arbuzas ticket remote/bridge | Remote CPU small; bridge around 1% on server | Not phone heat directly |
| DNS/AdGuard and other project bots | No phone-side high CPU seen in this pass | Not current top source |

## Do Not Touch Without Explicit Approval

- Tailscale/VPN and Dropbear/SSH recovery access.
- Durable ticket service readiness, unless replacing it with an equally reliable lower-cost check.
- Ticket input safety, brightness guard, protected tap rules, and control cleanup rules.

## Recommended Next Pass

1. Fix ticket readiness/root health process hygiene:
   - Add timeouts around every `/proc/$pid/cmdline` `tr` call.
   - Make root shell execution kill full process groups on timeout/cancel.
   - Add orphan detection for stale health/readiness shells.

2. Quiet supervisor readiness polling:
   - Cache stable ticket readiness and management health.
   - Run expensive probes only on state changes, failures, or a slower keepalive interval.

3. Tighten idle display cleanup:
   - Release ticket wake locks immediately when no clients remain.
   - Let the screen sleep while preserving dim brightness safety.

After those fixes, repeat this same poll. The success target should be: no long-running orphaned root helpers, no persistent one-core idle CPU process, no ticket wake lock while `clients=0`, and mild/no thermal pressure when the phone is idle and charging.

## Fix Implementation And Verification

Implemented on 2026-05-05 and deployed to the Pixel through the orchestrator `ticket_screen` redeploy path.

Changes made:

- Hardened the shared root executor so root commands drain output safely, enforce timeouts, and clean up timed-out shell child processes.
- Bounded the ticket tunnel pid/cmdline probes with `timeout 1`, including the inline SupervisorService readiness probe and `pixel-ticket-health.sh`.
- Throttled stable ticket service readiness checks: once local server and tunnel readiness are already proven, periodic checks are skipped for a two-minute stable window.
- Released the ticket screen wake lock immediately on viewer detach and shortened the active ticket screen wake hold to 30 seconds. Safe dim brightness enforcement remains active after stop.
- Updated `docs/architecture/TICKET_STREAMING_ARCHITECTURE.md` and `docs/architecture/PIXEL_STACK_ARCHITECTURE.md` with the new root-probe and wake-lock rules.

Checks run:

- `./gradlew :root-exec:test`
- `./gradlew :app:testDebugUnitTest --tests 'lv.jolkins.pixelorchestrator.app.ticket.*' --tests 'lv.jolkins.pixelorchestrator.app.SupervisorServiceDecisionTest'`
- `./gradlew :app:testDebugUnitTest --tests 'lv.jolkins.pixelorchestrator.app.phoneautomation.*Brightness*'`
- `sh -n orchestrator/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ticket-health.sh`
- `sh -n orchestrator/android-orchestrator/app/src/main/assets/runtime/templates/ticket/ticket-web-tunnel-service-loop.sh`

Live verification:

- Deployed successfully via `deploy_orchestrator_apk.sh --action redeploy_component --component ticket_screen`.
- Removed only the exact stale orphan chain confirmed from this report: `32430 tr \000`, parent `32421 sh -s`, grandparent `32404 sh -s`.
- Verified the authenticated Brave page at `ticket.jolkins.id.lv` opened from the user profile and showed the live ticket, not the waiting screen. Evidence: `ops/evidence/pixel-thermal-compute-20260505/fix-verification/brave-ticket-front-after-deploy.png`.
- Parked the stream after verification. Final Pixel health reported `sessionState=stopped`, `streamActive=false`, `clients=0`, and ticket service readiness still `ready`.
- Confirmed no current `tr` process, no active `lv.jolkins.pixelorchestrator:TicketStream` wake lock, battery temperature down to `34.5 C`, and thermal status `0`.
- Confirmed the readiness throttle by waiting past one monitor interval: `lastEnsureAgeMillis` continued aging to `63327` ms instead of resetting at 30 seconds.

Follow-up note:

- The short idle watch still observed transient root work from normal health probes and from the watch itself, but the persistent one-core `tr` leak did not return. A longer future watch can confirm whether any remaining management health probes deserve a separate broader optimization pass.

## Post-Fix Analytical Pass

Repeated the same style of idle thermal/compute sweep on 2026-05-05 after the root-executor, ticket-readiness, and wake-lock fixes.

Evidence root: `ops/evidence/pixel-thermal-compute-20260505/post-fix-recheck-20260505T152821Z/`

Raw summaries:

- `ops/evidence/pixel-thermal-compute-20260505/post-fix-recheck-20260505T152821Z/poll/idle-poll-summary.csv`
- `ops/evidence/pixel-thermal-compute-20260505/post-fix-recheck-20260505T152821Z/poll/corrected-summary.txt`
- `ops/evidence/pixel-thermal-compute-20260505/post-fix-recheck-20260505T152821Z/poll/poll-stats-corrected.json`
- `ops/evidence/pixel-thermal-compute-20260505/post-fix-recheck-20260505T152821Z/baseline/`
- `ops/evidence/pixel-thermal-compute-20260505/post-fix-recheck-20260505T152821Z/final/`

### Result

The original heat bug did not return.

| Item | Previous pass | Post-fix recheck |
| --- | --- | --- |
| Poll samples | 60 | 60 |
| Poll window | `20260505T141743Z` to `20260505T145019Z` | `20260505T152832Z` to `20260505T155934Z` |
| Battery temperature | 37.7-37.9 C, average 37.79 C | 28.6-32.9 C, average 29.91 C |
| Thermal status | `1` for all 60 samples | `0` for all 60 samples |
| Persistent hot `tr '\000'` | 60/60 samples, average top CPU 117.46% | Not observed |
| Any `tr` process | Persistent `tr \000` in every sample | 8/60 samples, 10 total transient `tr -d` helpers |
| Ticket wake lock active | Active at baseline while `clients=0` | 0 active samples after corrected active-wake-lock parsing |
| Ticket stream | Inactive, 0 clients | Inactive, 0 clients |
| Ticket readiness | Ready, but expensive probes were noisy | Ready; sampled `lastEnsureAgeMillis` usually aged up to 115s, with periodic keepalive reset |

The quick CSV has a rough `ticket_wakelock_active` field that can match old acquire/release history in `dumpsys power`. The corrected parser only counts the active `Wake Locks:` section. By that corrected reading, `lv.jolkins.pixelorchestrator:TicketStream` was never active during the post-fix poll.

Final verification:

- Phone reachable before and after the pass.
- No ADB forwards were created.
- No process kills, service stops, restarts, deploys, settings writes, or browser actions were run.
- Final power state reported `Wake Locks: size=0`.
- Final ticket health reported `sessionState=client_disconnected`, `streamActive=false`, `clients=0`, `serviceReadiness.state=ready`, `rootCapture.active=false`, and `ffmpeg.active=false`.
- Final brightness remained dim-safe: `brightnessGuard.active=true`, target 1%, panel brightness 39/3939.
- Final process snapshot showed no `tr`, no `ffmpeg`, no `screenrecord`, and no `screencap`.

### Remaining Ranked Findings

| Rank | Source | Observed impact | Confidence | User value | Safest next action |
| --- | --- | --- | --- | --- | --- |
| 1 | Speedtest background reporting jobs | Held an active partial wake lock in 8 of 12 power samples, from about 10 minutes old to about 23 minutes old. Released before final snapshot. One top sample showed the Speedtest app spiking high while the phone otherwise cooled. | High | Low while no speed test is being run | Add a Speedtest-idle policy pass: when Speedtest automation is not actively testing, prevent or defer Ookla background reporting work if that does not break the intended automation. |
| 2 | Root health/probe shell bursts | The old runaway is gone, but root health/probe work was still the most common top non-measurement process category: 27/60 samples. Related Magisk/root shell children were top in 13/60 samples. Transient `tr -d` helpers appeared in 8/60 samples. | High | Medium: readiness and diagnostics | Further quiet stable health checks: cache management health, avoid full root shell probes while state is stable, and replace remaining `tr -d` helper paths where cheap shell parsing is enough. |
| 3 | Awake display/system UI stack | The phone stayed awake with dim brightness. Display-related services appeared as top non-measurement work in a few samples, and transient WindowManager screen wake locks appeared in 3 power samples. Temperature still fell, so this is efficiency polish, not the main heat source. | Medium | Medium: burn-in and idle battery safety | Add an idle-screen policy pass: with no viewer, no physical handoff, and no active automation step, let the screen sleep while preserving dim brightness behavior when the ticket is intentionally visible again. |

### Component Notes

| Component | Post-fix state | Idle cost impression |
| --- | --- | --- |
| Ticket stream capture | Inactive, 0 clients, no active FFmpeg/root capture | Not a current heat source |
| Ticket service readiness | Ready; keepalive resets still happen, but stable checks are no longer every poll | Much improved, still a future quieting target |
| Ticket tunnel/cloudflared | Active and low in top-process samples | Acceptable for always-ready public ticket access |
| Tailscale and SSH/dropbear | Active, low in this pass | Recovery-critical; do not disable casually |
| Speedtest | Background reporting job held wake lock for much of the pass | Best remaining non-ticket heat/wake target |
| Display/brightness | Awake but dim-safe; no active TicketStream wake lock | Safe but still not the quietest possible idle state |

### Recommended Next Pass

1. Speedtest idle policy:
   - Verify whether Ookla background reporting is needed outside active automation.
   - If not needed, gate or restrict it while idle without breaking the user-triggered speedtest workflow.

2. Root probe quieting:
   - Reduce full `pixel-management-health.sh` and ticket health shell probes during stable ready states.
   - Keep recovery-critical checks, but make them slower or cheaper when nothing is changing.

3. Idle screen handoff:
   - Preserve dim brightness protection for ticket visibility.
   - Allow the screen to sleep when no viewer, no physical user, and no active automation step needs it awake.

Success target for the next recheck: no long wake locks while idle, fewer root health shell bursts, screen allowed to sleep when unused, and continued thermal status `0` while charging.
