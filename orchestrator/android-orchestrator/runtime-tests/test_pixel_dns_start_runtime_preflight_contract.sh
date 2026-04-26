#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENTRY_START="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-dns-start.sh"

for symbol in 'hash_file()' \
              'STATE_DIR="${BASE}/state/adguardhome"' \
              'PERSISTENT_CONF_DIR="${STATE_DIR}/conf"' \
              'PERSISTENT_WORK_DIR="${STATE_DIR}/work"' \
              'seed_persistent_dir_from_chroot()' \
              'ensure_bind_mount()' \
              'mount_persistent_adguardhome_state()' \
              'mount -o bind "${source_dir}" "${target_dir}"' \
              'stage_template "adguardhome-render-config"' \
              'stage_template "adguardhome-launch-core"' \
              'stage_template "adguardhome-launch-frontend"' \
              'preflight_runtime_assets()' \
              'validate_chroot_bash_script "/usr/local/bin/adguardhome-start"' \
              'validate_chroot_bash_script "/usr/local/bin/adguardhome-render-config"' \
              'validate_chroot_bash_script "/usr/local/bin/adguardhome-launch-core"' \
              'validate_chroot_bash_script "/usr/local/bin/adguardhome-launch-frontend"' \
              'chroot "${ADGUARDHOME_ROOTFS_PATH}" /usr/local/bin/adguardhome-render-config' \
              'staged asset hash mismatch'; do
  if ! rg -Fq -- "${symbol}" "${ENTRY_START}"; then
    echo "FAIL: missing deploy preflight symbol ${symbol} in ${ENTRY_START}" >&2
    exit 1
  fi
done

if ! awk '
  /^mount_persistent_adguardhome_state$/ { mount_line=NR }
  /^preflight_runtime_assets$/ { preflight_line=NR }
  END { exit(!(mount_line > 0 && preflight_line > 0 && mount_line < preflight_line)) }
' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start must mount persistent AdGuardHome state before preflight render" >&2
  exit 1
fi

echo "PASS: pixel-dns-start stages helper assets, verifies hashes, syntax-checks shells, and preflights runtime render"
