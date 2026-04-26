#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
INSTALLER_FILE="${REPO_ROOT}/android-orchestrator/runtime-installer/src/main/kotlin/lv/jolkins/pixelorchestrator/runtimeinstaller/RuntimeInstaller.kt"

for symbol in \
  'unmount_if_mounted()' \
  'rootfs/opt/adguardhome/conf"' \
  'rootfs/opt/adguardhome/work"' \
  'target_rootfs/opt/adguardhome/conf"' \
  'target_rootfs/opt/adguardhome/work"' \
  'rootfs/dev/pts"' \
  'rootfs/dev"' \
  'target_rootfs/dev/pts"' \
  'target_rootfs/dev"'; do
  if ! rg -Fq "${symbol}" "${INSTALLER_FILE}"; then
    echo "FAIL: RuntimeInstaller missing persistent-state-safe rootfs extraction symbol ${symbol}" >&2
    exit 1
  fi
done

echo "PASS: RuntimeInstaller unmounts AdGuardHome persistent bind mounts before rootfs reseed/extract"
