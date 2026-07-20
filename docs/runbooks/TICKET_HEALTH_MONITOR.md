# Ticket Health Monitor

`tools/observability/ticket_health_monitor.py` is the repository-owned, read-only Ticket health contract. It checks the public protection boundary, kitty-gration containers and local endpoints, bounded host memory/disk/Docker usage, narrow public Spacetime state, Pixel-local health, Pixel battery/thermal/RAM/disk usage, rooted H.264 readiness, stream freshness, and portrait lock. It distinguishes healthy idle from healthy live state instead of treating an intentionally stopped no-viewer stream as an outage.

Spacetime reads require the current CLI login to match the configured public operator identity before any SQL runs; the configuration contains no token and the monitor never falls back to anonymous SQL. The entire configuration is an exact schema: missing or extra sections and fields, wrong container or endpoint shapes, duplicate names, unsafe URLs or commands, wrong types, and non-finite numbers all stop before a probe runs. Every query must return its exact expected columns and strictly typed booleans, integers, statuses, and JSON objects. Pixel live and idle reports also have explicit required fields and types; incomplete reports fail the Pixel surface before stream classification. Healthy idle additionally requires a settled idle/stopped phone and ticket state, inactive-but-available hardware capture, and no failed or blocked recovery. If Spacetime or Pixel truth is unavailable, that surface remains unknown and the report records its one primary failure instead of guessing live, idle, portrait, frame, capture, pipeline, or ticket state. Each persisted state field, including Docker and ADB state, has its own approved enum and fixed fallback, so a merely token-shaped value is not accepted automatically.

The checked-in thresholds define separate warning and failure levels for host memory, root disk, configured-container CPU/memory, and Pixel battery level, battery temperature, Android thermal status, memory, and data disk. Warning crossings remain healthy but visible; failure crossings degrade the report with a specific finding. Missing, mistyped, reversed, extra, non-finite, or out-of-range threshold settings make configuration validation fail. Resource evidence remains numeric and bounded. Subprocess output is drained to completion, decoded safely, and remains at most 256 KiB per output stream even when a command emits invalid UTF-8. Raw command output, full phone health, ticket data, private identifiers, secrets, and unrelated container state are not copied into the report.

The checked-in configuration is enabled for the approved recurring read-only health check and keeps repair mode disabled. Validate it without touching production:

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

The monitor atomically updates `state/ticket-health-monitor/latest.json`. It saves a timestamped compact summary under `ops/evidence/ticket-health-monitor/` only for degraded or blocked runs. `reporting.max_degraded_evidence_reports` is 72, so the monitor keeps at most the newest 72 compact monitor-only summaries; it deliberately preserves legacy evidence and any directory containing extra operator notes or artifacts. Generated directories use mode `0755` and reports use `0644`, keeping them readable without making them writable by everyone. It never reads env files, tokens, cookies, databases, broad logs, or browser profiles, and it contains no deployment, container restart, ADB reconnect, or stream-recovery action. A repair workflow must remain separate, explicitly approved, narrowly scoped to a confirmed failing layer, and followed by a second read-only run.

`standby_devices` is currently empty. That is a deliberate warning: the monitor can verify the primary Pixel, but failover cannot be proven until a second physical device exists and has its own reviewed configuration.
