# Site Notifications Module Runbook

- Canonical orchestrator operations: [ROOT_OPERATIONS](./ROOT_OPERATIONS.md)
- Module path: `workloads/site-notifications`
- Runtime owner in orchestrator: `site_notifier`
- Release root: `/data/local/pixel-stack/apps/site-notifications/releases`
- Active release pointer: `/data/local/pixel-stack/apps/site-notifications/current`
- Default runtime architecture: dedicated bundled runtime under the app root, not the shared AdGuard chroot

## Quick Actions

Runtime control only:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --device <adb-serial> --action restart_component --component site_notifier --skip-build
```

Single-service redeploy from a staged release:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --device <adb-serial> --component-release-dir <component-release-dir> --action redeploy_component --component site_notifier
```

Workload-owned release flow:

```bash
cd workloads/site-notifications
../../tools/pixel/redeploy.sh --scope site_notifier
```

```bash
cd workloads/site-notifications
make pixel-release-check
```

```bash
cd workloads/site-notifications
make pixel-restart
```

`../../tools/pixel/redeploy.sh --scope site_notifier` is the canonical Site Notifier release path. The target architecture is: build a dedicated notifier runtime bundle, package a Site Notifier component release, and ask orchestrator to `redeploy_component site_notifier`.

`make pixel-restart` is day-2 process control only. It does not publish a new release.

`make pixel-release-check` is the preflight gate for the staged notifier release and should validate the bundled interpreter/runtime closure before cutover.

Verification:

```bash
adb -s <adb-serial> shell su -c 'readlink /data/local/pixel-stack/apps/site-notifications/current'
adb -s <adb-serial> shell su -c 'tail -n 120 /data/local/pixel-stack/apps/site-notifications/logs/site-notifier.log'
```

## Evidence

- Archived evidence root: `ops/evidence/site-notifications`
- Legacy imported diagnostics: `workloads/site-notifications/state/pixel-diagnostics`
