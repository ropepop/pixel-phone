#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

ADB_SERIAL=""
REPLACE_UNMANAGED_SSH_TRIGGER=1

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Back up and disable legacy train/notifier boot owners, then normalize legacy SSH boot owner handling.

Options:
  --adb-serial SERIAL              Target adb serial (default: first connected device)
  --remove-unmanaged-ssh-script    Remove unmanaged /data/adb/service.d/30-pixel-ssh.sh instead of replacing it
  -h, --help                       Show this help text
USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --remove-unmanaged-ssh-script) REPLACE_UNMANAGED_SSH_TRIGGER=0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

command -v adb >/dev/null 2>&1 || {
  echo "adb not found" >&2
  exit 1
}

resolve_adb_target() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    printf '%s' "${ADB_SERIAL}"
    return 0
  fi
  pixel_transport_require_adb_serial
}

adb_target="$(resolve_adb_target)"
if [[ -z "${adb_target}" ]]; then
  echo "No adb target available. Pass --adb-serial or connect a device." >&2
  exit 1
fi

if ! adb -s "${adb_target}" get-state >/dev/null 2>&1; then
  echo "ADB target unreachable: ${adb_target}" >&2
  exit 1
fi

adb_su() {
  local cmd="$1"
  local encoded
  encoded="$(printf '%s\n' "${cmd}" | base64 | tr -d '\n')"
  adb -s "${adb_target}" shell "echo '${encoded}' | base64 -d | su -c sh"
}

echo "Using adb target: ${adb_target}"
echo "Running hard-cutover cleanup for legacy service owners"

remote_script="$(cat <<'REMOTE_SCRIPT'
set -eu

replace_unmanaged_ssh="__REPLACE_UNMANAGED_SSH__"
timestamp="$(date +%Y%m%d-%H%M%S)"
backup_dir="/data/local/pixel-stack/backups/cutover-${timestamp}"
legacy_backup_dir="${backup_dir}/legacy-owners"
snapshot_dir="${backup_dir}/snapshots"

mkdir -p "${legacy_backup_dir}" "${snapshot_dir}"

snapshot_phase() {
  phase="$1"
  if ps -A -o USER,PID,PPID,NAME,ARGS >/dev/null 2>&1; then
    ps -A -o USER,PID,PPID,NAME,ARGS > "${snapshot_dir}/${phase}-processes.txt" 2>/dev/null || true
  elif ps -A >/dev/null 2>&1; then
    ps -A > "${snapshot_dir}/${phase}-processes.txt" 2>/dev/null || true
  else
    ps > "${snapshot_dir}/${phase}-processes.txt" 2>/dev/null || true
  fi

  if ss -ltnp >/dev/null 2>&1; then
    ss -ltnp > "${snapshot_dir}/${phase}-listeners.txt" 2>/dev/null || true
  else
    ss -ltn > "${snapshot_dir}/${phase}-listeners.txt" 2>/dev/null || true
  fi
}

backup_and_remove() {
  src="$1"
  backup_name="$2"
  if [ -f "${src}" ]; then
    cp "${src}" "${legacy_backup_dir}/${backup_name}"
    chmod 0600 "${legacy_backup_dir}/${backup_name}" 2>/dev/null || true
    rm -f "${src}"
    echo "removed=${src}"
  else
    echo "absent=${src}"
  fi
}

kill_legacy_owner_processes() {
  for pattern in \
    '/data/adb/service.d/40-telegram-train-bot.sh' \
    '/data/data/com.termux/files/home/.termux/boot/start-gribu-notifier.sh' \
    '/data/data/com.termux/files/home/.termux/boot/start-site-notifications.sh' \
    '/data/data/com.termux/files/home/site-notifications/scripts/start_daemon.sh'
  do
    pkill -f "${pattern}" >/dev/null 2>&1 || true
  done
}

snapshot_phase pre

backup_and_remove "/data/adb/service.d/40-telegram-train-bot.sh" "40-telegram-train-bot.sh"
backup_and_remove "/data/data/com.termux/files/home/.termux/boot/start-gribu-notifier.sh" "start-gribu-notifier.sh"
backup_and_remove "/data/data/com.termux/files/home/.termux/boot/start-site-notifications.sh" "start-site-notifications.sh"

ssh_boot_script="/data/adb/service.d/30-pixel-ssh.sh"
ssh_boot_script_mode="absent"
if [ -f "${ssh_boot_script}" ]; then
  if grep -Fq "managed-by: pixel-orchestrator" "${ssh_boot_script}" || \
    grep -Fq "/data/local/pixel-stack/bin/pixel-ssh-start.sh" "${ssh_boot_script}"; then
    ssh_boot_script_mode="kept"
  else
    cp "${ssh_boot_script}" "${legacy_backup_dir}/30-pixel-ssh.sh"
    chmod 0600 "${legacy_backup_dir}/30-pixel-ssh.sh" 2>/dev/null || true
    if [ "${replace_unmanaged_ssh}" = "1" ]; then
      cat > "${ssh_boot_script}" <<'EOF_SSH_TRIGGER'
#!/system/bin/sh
# managed-by: pixel-orchestrator
set -eu

PKG="lv.jolkins.pixelorchestrator"
RECEIVER="${PKG}/.app.OrchestratorActionReceiver"
ACTION="start_all"

attempt=0
while [ "${attempt}" -lt 6 ]; do
  if am broadcast -n "${RECEIVER}" --es orchestrator_action "${ACTION}" >/dev/null 2>&1; then
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 10
done

exit 0
EOF_SSH_TRIGGER
      chmod 0755 "${ssh_boot_script}"
      chown 0:0 "${ssh_boot_script}" 2>/dev/null || true
      ssh_boot_script_mode="replaced"
    else
      rm -f "${ssh_boot_script}"
      ssh_boot_script_mode="removed"
    fi
  fi
fi

kill_legacy_owner_processes
snapshot_phase post

echo "backup_dir=${backup_dir}"
echo "ssh_boot_script_mode=${ssh_boot_script_mode}"
REMOTE_SCRIPT
)"

remote_script="${remote_script/__REPLACE_UNMANAGED_SSH__/${REPLACE_UNMANAGED_SSH_TRIGGER}}"
cutover_output="$(adb_su "${remote_script}" | tr -d '\r')"

backup_dir="$(printf '%s\n' "${cutover_output}" | awk -F= '/^backup_dir=/{print $2; exit}')"
ssh_mode="$(printf '%s\n' "${cutover_output}" | awk -F= '/^ssh_boot_script_mode=/{print $2; exit}')"

echo "${cutover_output}"
cat <<REPORT
Hard Cutover Legacy Ownership Cleanup
- adb_target: ${adb_target}
- backup_dir: ${backup_dir:-unknown}
- ssh_boot_script_mode: ${ssh_mode:-unknown}
REPORT
