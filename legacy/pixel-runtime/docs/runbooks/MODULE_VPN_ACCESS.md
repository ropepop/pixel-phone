# VPN Access Module Runbook

- Canonical orchestrator operations: [ROOT_OPERATIONS](./ROOT_OPERATIONS.md)
- Module path: `orchestrator/vpn-access`
- Runtime owner in orchestrator: `vpn`

## Quick Actions

```bash
export PIXEL_TRANSPORT=ssh
export PIXEL_SSH_HOST="<tailnet-ip>"
export PIXEL_DEVICE_SSH_PASSWORD="<root-ssh-password>"

bash tools/pixel/check_ssh_ready.sh --ssh-host "${PIXEL_SSH_HOST}"

bash orchestrator/scripts/android/deploy_orchestrator_apk.sh \
  --transport ssh \
  --ssh-host "${PIXEL_SSH_HOST}" \
  --action restart_component \
  --component vpn \
  --skip-build
```

ADB fallback:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh \
  --transport adb \
  --device <adb-serial> \
  --action restart_component \
  --component vpn \
  --skip-build
```

## Evidence

- Archived evidence root: `ops/evidence/vpn-access`
