# Update

Updates in this stack should preserve the same release contract that production uses.

## When Runtime Templates Change

1. Update `orchestrator/templates/...`
2. Sync mirrored Android assets:

```bash
./tools/import/sync_runtime_templates.sh
./tools/import/check_runtime_template_parity.sh
```

3. Rebuild and redeploy the platform or affected scope.

## When Workload Code Changes

1. Run the workload tests locally.
2. Rebuild the workload bundle or binary.
3. Redeploy only the affected component:

```bash
./tools/pixel/redeploy.sh --scope train_bot
./tools/pixel/redeploy.sh --scope satiksme_bot
./tools/pixel/redeploy.sh --scope site_notifier
```

## When Config Changes

Keep private environment-specific config out of git.

- update your private copy of `orchestrator-config-v1.json`
- update local `.env` files as needed
- rerun `./tools/pixel/redeploy.sh --mode validate-only`
- perform a scoped or full redeploy depending on what changed

## Recommended Release Loop

```bash
./tools/import/validate_contracts.py
./tools/docs/check_links.sh
./tools/pixel/redeploy.sh --mode validate-only --transport ssh --ssh-host "${PIXEL_SSH_HOST}"
./tools/pixel/redeploy.sh --scope <affected-scope> --transport ssh --ssh-host "${PIXEL_SSH_HOST}"
```
