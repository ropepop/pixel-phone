# Train Bot Module Runbook

- Canonical orchestrator operations: [ROOT_OPERATIONS](./ROOT_OPERATIONS.md)
- Module path: `workloads/train-bot`
- Runtime owner in orchestrator: `train_bot`
- Release root: `/data/local/pixel-stack/apps/train-bot/releases`
- Active release pointer: `/data/local/pixel-stack/apps/train-bot/current`
- Production web host: `https://train-bot.jolkins.id.lv`
- Tunnel credentials source: `/data/local/pixel-stack/conf/apps/train-bot-cloudflared.json`
- Tunnel runtime state: `/data/local/pixel-stack/apps/train-bot/run/train-bot-cloudflared.pid`, `/data/local/pixel-stack/apps/train-bot/logs/train-bot-cloudflared.log`, `/data/local/pixel-stack/apps/train-bot/state/train-web-tunnel/train-bot-cloudflared.yml`

## Quick Actions

Runtime control only:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --device <adb-serial> --action restart_component --component train_bot --skip-build
```

Single-service redeploy from a staged release:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --device <adb-serial> --component-release-dir <component-release-dir> --action redeploy_component --component train_bot
```

Workload-owned release flow:

```bash
cd workloads/train-bot
../../tools/pixel/redeploy.sh --scope train_bot
```

```bash
cd workloads/train-bot
make pixel-release-check
```

```bash
cd workloads/train-bot
make pixel-restart-train
```

```bash
cd workloads/train-bot
make pixel-refresh-runtime
```

`../../tools/pixel/redeploy.sh --scope train_bot` is the canonical Train Bot release path. The target architecture is: prepare workload-specific inputs, package a Train Bot component release, then ask orchestrator to `redeploy_component train_bot`. It must fail closed if redeploy does not succeed. It is strict on same-day schedule readiness:
- prepares `TZ=Europe/Riga` snapshot in Termux (`~/telegram-train-app/data/schedules/YYYY-MM-DD.json`)
- copies snapshot into root runtime (`/data/local/pixel-stack/apps/train-bot/data/schedules/YYYY-MM-DD.json`)
- fails if runtime DB has zero `train_instances` for today
- must not continue after failed env/config provisioning

Use `make pixel-restart-train` for day-2 Train Bot restarts on an already-provisioned device. This is not a release command.
Use `make pixel-release-check` to validate the staged Train Bot release before asking orchestrator to switch the active release.
Use `make pixel-refresh-runtime` to rebuild/install the current orchestrator APK and refresh bundled runtime assets. This is compatibility tooling, not the canonical Train Bot release path.

## Schedule Recovery (If Bot Says "schedule unavailable")

```bash
cd workloads/train-bot
make pixel-restart-train
```

If the same-day schedule snapshot must be regenerated or recopied from Termux into the root runtime, use:

```bash
cd workloads/train-bot
../../tools/pixel/redeploy.sh --scope train_bot
```

Verify runtime state:

```bash
adb -s <adb-serial> shell su -c 'd=$(TZ=Europe/Riga date +%F); ls -la "/data/local/pixel-stack/apps/train-bot/data/schedules/$d.json"'
adb -s <adb-serial> shell su -c 'd=$(TZ=Europe/Riga date +%F); sqlite_bin=$(command -v sqlite3 2>/dev/null || true); if [ -n "$sqlite_bin" ]; then "$sqlite_bin" /data/local/pixel-stack/apps/train-bot/train_bot.db "select count(*) from train_instances where service_date='\''$d'\'';"; else echo unknown; fi'
adb -s <adb-serial> shell su -c 'tail -n 120 /data/local/pixel-stack/apps/train-bot/logs/train-bot.log'
adb -s <adb-serial> shell su -c 'readlink /data/local/pixel-stack/apps/train-bot/current'
```

## Evidence

- Archived evidence root: `ops/evidence/train-bot`
- Legacy imported evidence: `workloads/train-bot/output`
