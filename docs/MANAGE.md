# Manage

Use the monorepo contracts to keep ownership boundaries clear.

## Day-2 Commands

- Full redeploy: `./tools/pixel/redeploy.sh --scope full`
- Platform-only refresh: `./tools/pixel/redeploy.sh --scope platform`
- Train bot redeploy: `./tools/pixel/redeploy.sh --scope train_bot`
- Satiksme bot redeploy: `./tools/pixel/redeploy.sh --scope satiksme_bot`
- Site notifier redeploy: `./tools/pixel/redeploy.sh --scope site_notifier`
- Validate only: `./tools/pixel/redeploy.sh --mode validate-only`

## Ownership Model

- `orchestrator/modules/registry/modules.yaml` is the module index.
- Each module keeps its own `module.yaml` manifest.
- App-style modules should own a dedicated runtime root and immutable releases.
- Derived components must declare which primary component owns their updates.

## Evidence And Reports

Public starter rule:

- `ops/evidence/` and `ops/reports/` are output directories, not source material.
- generate them locally or in your own private ops repo
- do not commit generated data back into this starter

## Adding Modules

Start with [onboarding/ADDING_A_MODULE.md](./onboarding/ADDING_A_MODULE.md), then update:

- the module registry
- module manifests
- workload or infra docs
- validation coverage
