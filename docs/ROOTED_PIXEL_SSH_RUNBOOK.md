# Rooted Pixel SSH Runbook

Compatibility pointer:
- [ROOT_OPERATIONS](./runbooks/ROOT_OPERATIONS.md)

## SSH-First Operator Path

Use Tailscale-backed SSH as the primary transport for rooted Pixel operations.
ADB remains supported as a fallback and recovery path.

Required local environment:

```bash
export PIXEL_TRANSPORT=ssh
export PIXEL_SSH_HOST="<tailnet-ip>"
export PIXEL_SSH_PORT=2222
export PIXEL_DEVICE_SSH_PASSWORD="<root-ssh-password>"
```

Readiness gate:

```bash
bash tools/pixel/check_ssh_ready.sh --ssh-host "${PIXEL_SSH_HOST}"
```

Normal redeploy:

```bash
./tools/pixel/redeploy.sh --scope train_bot --transport ssh --ssh-host "${PIXEL_SSH_HOST}"
```

Manual orchestrator action:

```bash
bash orchestrator/scripts/android/deploy_orchestrator_apk.sh \
  --transport ssh \
  --ssh-host "${PIXEL_SSH_HOST}" \
  --action health \
  --skip-build
```

ADB fallback:

```bash
export PIXEL_TRANSPORT=adb
export ADB_SERIAL="<adb-serial>"

./tools/pixel/redeploy.sh --scope train_bot --transport adb --device "${ADB_SERIAL}"
```

Notes:
- The host-side readiness script verifies local Tailscale status, TCP reachability, password auth, remote root access, Android command availability, and VPN guard-chain evidence.
- `auto` mode prefers SSH only when the readiness gate passes; otherwise it falls back to ADB.
- The repo does not store SSH credentials. Supply the password through `PIXEL_DEVICE_SSH_PASSWORD`.
