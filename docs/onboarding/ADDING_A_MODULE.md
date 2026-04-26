# Adding A Module

This monorepo uses a manifest-driven onboarding contract.

## Required Outputs

- module directory in a domain (`workloads/`, `automation/`, `infra/`, or `orchestrator/`)
- module manifest (`module.yaml`) conforming to schema, including redeploy ownership metadata for every managed component
- module runbook overlay under `docs/runbooks/`
- module evidence archive directory under `ops/evidence/`
- module entry in `orchestrator/modules/registry/modules.yaml` with matching redeploy metadata

## Redeploy Contract

Every managed component must declare a `redeploy` block in its manifest metadata:

```yaml
redeploy:
  mode: artifact_release | asset_refresh | job | derived
  artifact_id: <artifact-id-when-applicable>
  derived_from: <owner-component-when-derived>
  release_root: <immutable-release-root-when-applicable>
```

Rules:
- `bootstrap` is reserved for clean-room provisioning and shared-platform changes.
- `redeploy_component` is the default release/update path for one service or job.
- `restart_component` is runtime control only and is not a release mechanism.
- New apps must own a dedicated mutable runtime root and should default to immutable releases with `releases/<releaseId>/` plus `current`.
- Derived components must declare their owner and cannot pretend to be independently isolated.
- Shared mutable runtime dependencies between sibling apps are not acceptable for new module onboarding.

## Steps

1. Scaffold:
```bash
./tools/import/new_module_scaffold.sh <module_id> <domain_dir> <component_a,component_b>
```
2. Choose a redeploy mode for each managed component and define its runtime ownership boundary.
3. Add/update module in registry file with matching redeploy metadata.
4. Validate manifest against schema.
5. Add module-specific health command(s).
6. Add observability emission integration with `PIXEL_RUN_ID` propagation.
7. Add tests and compatibility checks.
8. Update root README module map.
9. Update the module runbook so `restart_component` and `redeploy_component` are documented separately.

## Acceptance Gates

- registry entry exists and IDs are unique
- manifest validates against `orchestrator/modules/schemas/module-manifest.v1.schema.json`
- every managed component declares redeploy ownership metadata
- any derived component declares `derived_from`
- any app-style service has a dedicated runtime root; new apps default to immutable releases with `current`
- module runbook distinguishes `restart_component` from `redeploy_component`
- docs link check passes (`./tools/docs/check_links.sh`)
- evidence validation passes (`./tools/observability/validate_evidence.sh`)
- compatibility API unchanged for existing components/actions
