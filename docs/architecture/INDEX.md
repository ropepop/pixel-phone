# Architecture Index

## Control Domains

- Orchestrator control plane: `orchestrator/android-orchestrator`
- Runtime shell/control scripts: `orchestrator/scripts`
- Runtime templates/configs: canonical templates in `orchestrator/templates` (synced to Android runtime assets), `orchestrator/configs`
- Managed workloads: `workloads/train-bot`, `workloads/site-notifications`
- External automation driver: `automation/task-executor`
- Observability/evidence: `ops/evidence`, `standards/schemas`

## Key Contracts

- Module registry: `orchestrator/modules/registry/modules.yaml`
- Module manifest schema: `orchestrator/modules/schemas/module-manifest.v1.schema.json`
- Component redeploy metadata lives in module manifests and the registry; every managed component declares whether it is an `artifact_release`, `asset_refresh`, `job`, or `derived` surface
- Observability event schema: `standards/schemas/observability-event.v1.schema.json`
- Observability health schema: `standards/schemas/observability-health.v1.schema.json`

## Canonical Operations

- [ROOT_OPERATIONS](../runbooks/ROOT_OPERATIONS.md)
- `bootstrap` is for clean-room provisioning and shared-platform changes
- `redeploy_component` is the default single-service release path
- `restart_component` is lifecycle control only and does not publish a new release
