# AdGuard Home Module Runbook

- Canonical orchestrator operations: [ROOT_OPERATIONS](./ROOT_OPERATIONS.md)
- Infra secret path: `infra/adguardhome/secrets`
- Runtime root path on device: `/data/local/pixel-stack/chroots/adguardhome`

## Quick Validation

```bash
./tools/pixel/redeploy.sh --scope dns --mode validate-only --transport ssh --ssh-host "${PIXEL_SSH_HOST}"
adb shell su -c 'chroot /data/local/pixel-stack/chroots/adguardhome /usr/local/bin/adguardhome-start --remote-healthcheck-debug'
```

Querylog reporting default:
- LAN/querylog metrics in `service-availability-report.sh` default to user-facing rows (`querylog_view_mode=user_only`).
- Internal rows (watchdog/admin/maintenance loopback clients) remain measurable via dedicated `lan.internal_*` fields.
- Use `--include-internal-querylog` when you want combined legacy-style totals.
- The web sidecar at `/pixel-stack/identity` now includes a **Querylog Visibility** panel with the same default `user_only` behavior and an **Include internal querylog** toggle for `all` view.
- Web summary classification defaults align with reporting semantics: internal clients `127.0.0.1,::1` plus loopback+probe-domain tagging (`example.com` by default), configurable via sidecar env overrides.
- Remote DoH contract health probes are path-only (`/dns-query`) and no longer send `?dns=` payload requests, preventing new loopback probe rows in native AdGuard querylog surfaces.
- Existing querylog rows are preserved and age out naturally via AdGuard retention; no querylog wipe is performed by rollout.
- Runtime hardening: `start_all`, `start_component`, and `restart_component` now perform bundled runtime asset sync before action execution.
- Tokenized/dual remote mode is fail-closed for identity sidecar startup: if the sidecar cannot start, remote health/action fails instead of logging warn-only.
- Tokenized identity expiry is enforced: expired identities remain visible in `/pixel-stack/identity` but are excluded from active tokenized DoH routes.
- `deploy_orchestrator_apk.sh` prints advisory stale-runtime hash warnings and a post-action identity endpoint status summary when remote bring-up is expected.

Remote endpoint contract:
- `https://dns.jolkins.id.lv/` returns a redirect to the native AdGuard UI (`302` in the current rollout).
- `https://dns.jolkins.id.lv/pixel-stack/identity/inject.js` returns `200`.
- `https://dns.jolkins.id.lv/<token>/dns-query` is the only public DoH route. Empty or invalid requests are expected to return a reachable non-route-miss response (`400` in the current rollout).
- Bare `/dns-query` returns `404` when tokenized DoH mode is enabled.
- Public DoT stays on `853`, but in managed identity mode nginx only forwards randomized managed labels under `*.dns.jolkins.id.lv`. The current production label length is `20`, and the base hostname `dns.jolkins.id.lv` is rejected on `853`.
- AdGuardHome’s own DoT listener stays internal on `8853` when nginx fronts public `853`.
- Train Bot ingress is out of scope for this module. `dns.jolkins.id.lv` must not proxy `/pixel-stack/train`, and Train Bot public web/tunnel ownership lives under `https://train-bot.jolkins.id.lv`.

## Evidence

- Archived evidence root: `ops/evidence/adguardhome`
- Orchestrator health/e2e artifacts: `ops/evidence/orchestrator`
