# Satiksme Bot Module Runbook

- Canonical orchestrator operations: [ROOT_OPERATIONS](./ROOT_OPERATIONS.md)
- Module path: `workloads/satiksme-bot`
- Runtime owner in orchestrator: `satiksme_bot`
- Release root: `/data/local/pixel-stack/apps/satiksme-bot/releases`
- Active release pointer: `/data/local/pixel-stack/apps/satiksme-bot/current`
- Production web host: `https://satiksme-bot.jolkins.id.lv`
- Tunnel credentials source: `/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json`
- Tunnel runtime state: `/data/local/pixel-stack/apps/satiksme-bot/run/satiksme-bot-cloudflared.pid`, `/data/local/pixel-stack/apps/satiksme-bot/logs/satiksme-bot-cloudflared.log`, `/data/local/pixel-stack/apps/satiksme-bot/state/satiksme-web-tunnel/satiksme-bot-cloudflared.yml`
- Primary runtime data: `/data/local/pixel-stack/apps/satiksme-bot/satiksme_bot.db`, `/data/local/pixel-stack/apps/satiksme-bot/data/catalog/generated/catalog.json`

## Quick Actions

Runtime control only:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --device <adb-serial> --action restart_component --component satiksme_bot --skip-build
```

Single-service redeploy from a staged release:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --device <adb-serial> --component-release-dir <component-release-dir> --action redeploy_component --component satiksme_bot
```

Workload-owned release flow:

```bash
cd workloads/satiksme-bot
../../tools/pixel/redeploy.sh --scope satiksme_bot
```

```bash
cd workloads/satiksme-bot
make pixel-release-check
```

```bash
cd workloads/satiksme-bot
make pixel-native-validate
```

`../../tools/pixel/redeploy.sh --scope satiksme_bot` is the canonical Satiksme Bot release path. The target architecture is: build a native satiksme bundle, package a `satiksme_bot` component release, switch the active rooted release, and fail closed if origin/public health or release parity does not converge.

Use `restart_component satiksme_bot` for day-2 runtime recovery on an already-provisioned device. It is not a release command.

Use `make pixel-release-check` to confirm the staged release is healthy from both the rooted origin and the public tunnel before declaring the cutover good.

Use `make pixel-native-validate` as the production-readiness gate. It should cover native tests, build, redeploy, release parity, public smoke, and authenticated report submission.

## Verification

```bash
adb -s <adb-serial> shell su -c 'readlink /data/local/pixel-stack/apps/satiksme-bot/current'
adb -s <adb-serial> shell su -c 'tail -n 120 /data/local/pixel-stack/apps/satiksme-bot/logs/satiksme-bot.log'
adb -s <adb-serial> shell su -c 'tail -n 120 /data/local/pixel-stack/apps/satiksme-bot/logs/service-loop.log'
adb -s <adb-serial> shell su -c 'cat /data/local/pixel-stack/apps/satiksme-bot/run/heartbeat.epoch'
adb -s <adb-serial> shell su -c 'test -f /data/local/pixel-stack/apps/satiksme-bot/data/catalog/generated/catalog.json && echo catalog-present'
```

## Notes

- `cmd/catalogsync` mirrors `stops.txt`, `routes.txt`, and GTFS fallback data into the workload data directory.
- `cmd/bot` serves the public site and mini app, writes sightings to SQLite, and posts anonymized accepted reports to `REPORT_DUMP_CHAT`.
- Public endpoints stay read-only; report writes require a Telegram-authenticated web session.
- Live departures can be served via browser-direct requests to Riga Satiksme or via the bot web server proxy (`SATIKSME_WEB_DIRECT_PROXY_ENABLED=true`).

## Evidence

- Archived evidence root: `ops/evidence/satiksme-bot`
- Local release and smoke artifacts: `workloads/satiksme-bot/output/pixel`
