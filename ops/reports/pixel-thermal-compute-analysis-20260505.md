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

