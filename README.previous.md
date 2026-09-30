# Pixel Phone Runtime

Canonical source repository for Ticket on the rooted Pixel and its ADB/SSH access.

## Purpose

The phone runs Ticket, its required device-side support and housekeeping, and
ADB/SSH access. Existing Tailscale connectivity provides the private access path.
Application servers, bots, notifications and other server workloads run on the
VPS from the separate canonical `ops` repository.

Historical workload directories and configuration fields remain for retained
state and reference. Their presence is not an instruction to reinstall or
restart them. The active registry is
`orchestrator/modules/registry/modules.yaml`; see
[PIXEL_STACK_ARCHITECTURE](./docs/architecture/PIXEL_STACK_ARCHITECTURE.md).

## Repository Map

- `orchestrator/`: Android orchestrator app, root scripts, templates, orchestrator configs, module registry.
- `orchestrator/vpn-access`: VPN access module manifest and integration overlays.
- `workloads/ticket-screen/`: active Ticket device runtime.
- Other `workloads/`, `automation/` and `infra/` directories: historical material;
  excluded from phone deployment and migration.
- `ops/`: archived evidence and reports.
- `docs/`: canonical runbooks, onboarding docs, architecture and references.
- `docs/CONTEXT.md`: small-context reading guide for agents and documentation placement.
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

1. Validate the Android orchestrator project:
```bash
cd orchestrator/android-orchestrator
./gradlew test
```
2. Follow the Ticket guidance in the sibling ops checkout's
`workloads/ticket-remote/CURRENT.md` for affected browser-to-phone acceptance.

## Observability

- Private operational history is consolidated in the single `operationallog_event` data table in `operational-logging-prod`; Ticket application state remains in `ticket-remote-prod-v3`.
- General orchestrator telemetry reads `/data/local/pixel-stack/conf/apps/operational-logging.env` and its separately protected token file. The dashboard's newest-event list remains process-memory-only.
- Event schema: [observability-event.v1.schema.json](./standards/schemas/observability-event.v1.schema.json)
- Health schema: [observability-health.v1.schema.json](./standards/schemas/observability-health.v1.schema.json)
- One-off evidence emitter: `./tools/observability/emit_event.sh` (stdout JSON; it is not the cloud writer)
- Evidence archive root: `ops/evidence/`

## Scope Notes

- Phone scope is Ticket plus ADB/SSH access and the support required for them.
- Do not restore the old phone-hosted Train, Satiksme, notification, subscription,
  DNS/DDNS or task-runner arrangement from historical imports or examples.
- Keep the current working networking setup; language migration does not justify
  changing the phone/VPS boundary or introducing another resident service.
