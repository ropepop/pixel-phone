# Ticket Health Monitor

`tools/observability/ticket_health_monitor.py` is the repository-owned, read-only Ticket health contract. It checks the public protection boundary, kitty-gration containers and local endpoints, bounded host memory/disk/Docker usage, narrow public Spacetime state, Pixel-local health, Pixel battery/thermal/RAM/disk usage, rooted capture readiness, stream freshness, and portrait lock. The legacy `hardwareH264` field name does not establish the deployed codec.

The classifier distinguishes `healthy_live`, `healthy_warm`, and `healthy_idle`. Active browser viewing requires live transport, capture and ticket state plus pictures within the three-second product boundary. Intentional page warmth requires zero browser video clients, retained stream demand, a fresh relay report with a bounded non-expired `pageOpenWarm` count/expiry, connected phone transport and healthy capture readiness. It does not require continuing browser frame delivery and grants no fresh-picture or action proof. Missing/expired warmth, stale relay reports, disconnected transport, stopped capture and failed recovery remain failures. Active viewers never borrow the warm-state freshness exemption. Settled idle requires stopped capture and settled phone/ticket state; an older unchanged idle report alone is not a fault.

Frame thresholds have separate `warning` and `failure` values. The Pixel warning is 1,500 ms and relay warning 1,000 ms; both failure boundaries are exactly 3,000 ms, with 3,001 ms rejected. Warnings do not change a healthy verdict. Configuration validation rejects a weakened product boundary.

Spacetime collection selects absolute `lastFrameAt` and phone/relay `updatedAt`; the retired relative-age column is not read. The CLI's tagged optional frame timestamp is decoded strictly. Ages are calculated at each query's receipt time, including query delay, and those observation times are retained separately from report creation time. Relay reports under active or warm demand must be at most five seconds old, matching Ticket's background-report contract. More than 250 ms of future clock disagreement fails collection. A warm deadline cannot exceed the report time by more than the product's 30-minute hold. Phone rows are change-driven, so their update age is recorded but is not treated as a heartbeat; direct Pixel health supplies the current device observation. Old snapshots do not become fresh evidence when reevaluated offline.

The standalone monitor has no V3 visual-action, authenticated browser, physical gesture, HDR brightness or failover acceptance. A healthy result covers only the named observed surfaces. Keep reported faults, demonstrated classifier mismatches and unavailable coverage distinct before routing any repair.

Pixel frame age comes from `streamPipeline.lastFrameSentAgoMillis`, and encoder
activity comes from `hardwareH264.active`. The monitor does not depend on the
removed duplicate `visibleFrame` or `encoderRunning` projections. Recovery uses
the current stream owner; while V1 is still live, any optional legacy desired-
recovery failure is also retained. Remove that legacy interpretation after the
coordinated V2 cutover.

Spacetime reads require the current CLI login to match the configured public operator identity before any SQL runs; the configuration contains no token and the monitor never falls back to anonymous SQL. The entire configuration is an exact schema: missing or extra sections and fields, wrong container or endpoint shapes, duplicate names, unsafe URLs or commands, wrong types, and non-finite numbers all stop before a probe runs. Every query must return its exact expected columns and strictly typed booleans, integers, statuses, and JSON objects. Pixel live and idle reports also have explicit required fields and types; incomplete reports fail the Pixel surface before stream classification. Healthy idle additionally requires a settled idle/stopped phone and ticket state, inactive-but-available hardware capture, and no failed or blocked recovery. If Spacetime or Pixel truth is unavailable, that surface remains unknown and the report records its one primary failure instead of guessing live, idle, portrait, frame, capture, pipeline, or ticket state. Each persisted state field, including Docker and ADB state, has its own approved enum and fixed fallback, so a merely token-shaped value is not accepted automatically.

The checked-in thresholds define separate warning and failure levels for host memory, root disk, configured-container CPU/memory, and Pixel battery level, battery temperature, Android thermal status, memory, and data disk. Warning crossings remain healthy but visible; failure crossings degrade the report with a specific finding. Missing, mistyped, reversed, extra, non-finite, or out-of-range threshold settings make configuration validation fail. Resource evidence remains numeric and bounded. Subprocess output is drained to completion, decoded safely, and remains at most 256 KiB per output stream even when a command emits invalid UTF-8. Raw command output, full phone health, ticket data, private identifiers, secrets, and unrelated container state are not copied into the report.

The checked-in configuration is enabled and keeps repair mode disabled. The monitor runs from this local checkout; edits to its Python/config files do not require an APK or service restart. Existing recurring callers use this canonical command; this runbook does not create a schedule. Validate it without touching production:

```bash
python3 tools/observability/ticket_health_monitor.py \
  --config tools/observability/ticket_health_monitor.config.json \
  --check-config
```

One read-only run is:

```bash
python3 tools/observability/ticket_health_monitor.py \
  --config tools/observability/ticket_health_monitor.config.json
```

Full-run exits are `0` healthy, `1` degraded, `2` invalid configuration, and `3` paused. Configuration checking and exits 2/3 do not replace `latest.json`. Confirm the completed run's embedded timestamp before citing the report. Offline regression checks are:

```bash
python3 -B -m unittest discover -s tools/observability/tests -p 'test_ticket_health_monitor.py'
```

Roll out the Ticket relay's warm-report projection before relying on `healthy_warm`. An older relay missing that projection is explicitly uncovered when there is demand but no browser video client; the monitor must not infer warmth from the absence of clients.

The monitor atomically updates `state/ticket-health-monitor/latest.json`. It saves a timestamped compact summary under `ops/evidence/ticket-health-monitor/` only for degraded or blocked runs. `reporting.max_degraded_evidence_reports` is 72, so the monitor keeps at most the newest 72 compact monitor-only summaries; it deliberately preserves legacy evidence and any directory containing extra operator notes or artifacts. Generated directories use mode `0755` and reports use `0644`, keeping them readable without making them writable by everyone. It never reads env files, tokens, cookies, databases, broad logs, or browser profiles, and it contains no deployment, container restart, ADB reconnect, or stream-recovery action. A repair workflow must remain separate, explicitly approved, narrowly scoped to a confirmed failing layer, and followed by a second read-only run.

`standby_devices` is currently empty. That is a deliberate warning: the monitor can verify the primary Pixel, but failover cannot be proven until a second physical device exists and has its own reviewed configuration.
