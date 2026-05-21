# Architecture Index

Read architecture in this order:

1. [Pixel Stack Architecture](./PIXEL_STACK_ARCHITECTURE.md)
2. [Touch Brightness Architecture](./TOUCH_BRIGHTNESS_ARCHITECTURE.md)
3. [Ticket Streaming Architecture](./TICKET_STREAMING_ARCHITECTURE.md)
4. [RS/ViVi Incident Analytics Architecture](./RS_VIVI_INCIDENT_ANALYTICS_ARCHITECTURE.md)
5. [ROOT_OPERATIONS](../runbooks/ROOT_OPERATIONS.md)
6. Module runbook overlays in `docs/runbooks/`
7. Dated evidence in `ops/reports/` when a doc links to a measurement

Architecture docs are canonical source material. Reports and evidence explain how a conclusion was measured, but they should not be the only place stable architecture is recorded.

## Control Domains

- Orchestrator control plane: `orchestrator/android-orchestrator`
- Runtime shell/control scripts: `orchestrator/scripts`
- Runtime templates/configs: canonical templates in `orchestrator/templates` and Android runtime assets, plus `orchestrator/configs`
- Managed workloads: `workloads/train-bot`, `workloads/satiksme-bot`, `workloads/site-notifications`, `workloads/subscription-bot`, `workloads/ticket-screen`
- External automation driver: `automation/task-executor`
- Observability/evidence: `ops/evidence`, `standards/schemas`

## Key Contracts

- Module registry: `orchestrator/modules/registry/modules.yaml`
- Module manifest schema: `orchestrator/modules/schemas/module-manifest.v1.schema.json`
- Component redeploy metadata lives in module manifests and the registry; every managed component declares whether it is an `artifact_release`, `asset_refresh`, `job`, or `derived` surface
- Observability event schema: `standards/schemas/observability-event.v1.schema.json`
- Observability health schema: `standards/schemas/observability-health.v1.schema.json`
- RS/ViVi user incident trace schema: `standards/schemas/rs-vivi-incident-trace.v1.schema.json`
- RS/ViVi broker analytics schema: `standards/schemas/rs-vivi-qr-analytics.v1.schema.json`

## Canonical Operations

- [ROOT_OPERATIONS](../runbooks/ROOT_OPERATIONS.md)
- `bootstrap` is for clean-room provisioning and shared-platform changes
- `redeploy_component` is the default single-service release path
- `restart_component` is lifecycle control only and does not publish a new release

## Agent Update Rule

When implementation changes component boundaries, runtime paths, deployment behavior, health fields, public/private endpoints, stream/input flow, tunnel behavior, or safety policy, update the relevant architecture doc in the same work. If the change is temporary or measurement-only, link the dated report but keep stable behavior in `docs/architecture/`.
