# Pixel Ops Monorepo

Unified operations, runtime, workload, and automation repository for Pixel-root orchestrated services.

## Purpose

This repository consolidates the production Pixel stack into a single operational workspace with:
- root orchestrator code and runtime scripts,
- workload modules (train bot and site notifications),
- automation workflows,
- pihole secret artifacts (intentionally tracked per current policy),
- evidence archives and observability contracts.

## Repository Map

- `orchestrator/`: Android orchestrator app, root scripts, templates, orchestrator configs, module registry.
- `orchestrator/vpn-access`: VPN access module manifest and integration overlays.
- `workloads/`: runtime applications managed by orchestrator (`train-bot`, `site-notifications`).
- `automation/`: external scheduler/cron automation (`task-executor`).
- `infra/`: infrastructure-specific files (`pihole/secrets`).
- `ops/`: archived evidence and reports.
- `docs/`: canonical runbooks, onboarding docs, architecture and references.
- `standards/`: shared schemas and templates.
- `tools/`: import, observability, and docs utility scripts.

## Operator Quickstart

1. Review canonical runbook: [ROOT_OPERATIONS](./docs/runbooks/ROOT_OPERATIONS.md).
2. Normal production redeploy path:
```bash
./tools/pixel/redeploy.sh
```
2. Validate module layout and contracts:
```bash
yq '.modules[]?.id' orchestrator/modules/registry/modules.yaml
```
3. Run observability/evidence checks:
```bash
./tools/observability/validate_evidence.sh
./tools/docs/check_links.sh
```

## Developer Quickstart

1. Refresh snapshot imports when needed:
```bash
./tools/import/import_snapshot.sh
```
2. Validate Android orchestrator project:
```bash
cd orchestrator/android-orchestrator
./gradlew test
```
3. Validate automation/workload suites (examples):
```bash
cd workloads/site-notifications && python -m venv .venv && . .venv/bin/activate && pip install -r requirements-dev.txt && PYTHONPATH=. pytest -q
cd workloads/train-bot && go test ./...
cd automation/task-executor && ./scripts/drain_runner_smoke_test.sh
```

## Observability

- Event schema: [observability-event.v1.schema.json](./standards/schemas/observability-event.v1.schema.json)
- Health schema: [observability-health.v1.schema.json](./standards/schemas/observability-health.v1.schema.json)
- Event emitter: `./tools/observability/emit_event.sh`
- Evidence archive root: `ops/evidence/`

## Runtime Notes

- `train_bot` treats missing same-day schedule data as degraded after `SCRAPER_DAILY_HOUR` in the runtime timezone (`Europe/Riga` by default).
- The Android supervisor can auto-restart `train_bot` when heartbeat is healthy but same-day schedule freshness is missing after the daily cutoff.
- Before the daily cutoff, schedule reads can still return `schedule unavailable` if the current day has not been loaded yet.

## Add New Module

Use the manifest-driven onboarding flow:
- [ADDING_A_MODULE](./docs/onboarding/ADDING_A_MODULE.md)

## Scope Notes

- Consolidated modules: `orchestrator`, `telegram train app`, `site-notifications`, `task-executor`, `pihole`, `vpn-access`.
- White-label notifier repository remains out of scope.
- History was intentionally reset for this monorepo.
