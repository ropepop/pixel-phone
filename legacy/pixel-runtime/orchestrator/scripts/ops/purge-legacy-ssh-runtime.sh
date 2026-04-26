#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

ADB_SERIAL=""
RESTART_LOCAL=1

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Delete legacy SSH runtime under /data/adb/pixel-stack/ssh and verify only /data/local runtime remains active.

Options:
  --adb-serial SERIAL   Target adb serial (default: first connected device)
  --no-restart-local    Do not restart /data/local SSH loop after purge
  -h, --help            Show this help text
USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --no-restart-local) RESTART_LOCAL=0 ;;
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
echo "Stopping SSH runtime (local+legacy cleanup paths)"
adb_su "sh /data/local/pixel-stack/bin/pixel-ssh-stop.sh || true"

echo "Killing any residual legacy SSH processes"
adb_su "pkill -f '^(/system/bin/)?sh /data/adb/pixel-stack/ssh/bin/pixel-ssh-service-loop$' >/dev/null 2>&1 || true"
adb_su "pkill -f '^/data/adb/pixel-stack/ssh/bin/dropbear' >/dev/null 2>&1 || true"

echo "Deleting /data/adb/pixel-stack/ssh"
adb_su "rm -rf /data/adb/pixel-stack/ssh"

if (( RESTART_LOCAL == 1 )); then
  echo "Restarting /data/local SSH runtime"
  adb_su "sh /data/local/pixel-stack/bin/pixel-ssh-start.sh"
fi

legacy_exists="$(adb_su "if [ -e /data/adb/pixel-stack/ssh ]; then echo yes; else echo no; fi" | tr -d '\r\n')"
legacy_dropbear_count="$(adb_su "pgrep -af '^/data/adb/pixel-stack/ssh/bin/dropbear' | wc -l" | tr -d '\r\n' | tr -d '[:space:]')"
legacy_loop_count="$(adb_su "pgrep -af '^(/system/bin/)?sh /data/adb/pixel-stack/ssh/bin/pixel-ssh-service-loop$' | wc -l" | tr -d '\r\n' | tr -d '[:space:]')"
local_dropbear_count="$(adb_su "pgrep -af '^/data/local/pixel-stack/ssh/bin/dropbear' | wc -l" | tr -d '\r\n' | tr -d '[:space:]')"
local_loop_count="$(adb_su "pgrep -af '^(/system/bin/)?sh /data/local/pixel-stack/ssh/bin/pixel-ssh-service-loop$' | wc -l" | tr -d '\r\n' | tr -d '[:space:]')"
listener_2222_count="$(adb_su "ss -ltn 2>/dev/null | grep -E '[:.]2222[[:space:]]' | wc -l" | tr -d '\r\n' | tr -d '[:space:]')"

cat <<REPORT
Legacy SSH Purge Report
- adb_target: ${adb_target}
- legacy_path_exists: ${legacy_exists}
- legacy_dropbear_count: ${legacy_dropbear_count}
- legacy_loop_count: ${legacy_loop_count}
- local_dropbear_count: ${local_dropbear_count}
- local_loop_count: ${local_loop_count}
- listener_2222_count: ${listener_2222_count}
REPORT

if [[ "${legacy_exists}" != "no" ]] || [[ "${legacy_dropbear_count}" != "0" ]] || [[ "${legacy_loop_count}" != "0" ]]; then
  echo "Legacy SSH runtime purge verification failed" >&2
  exit 1
fi

if (( RESTART_LOCAL == 1 )); then
  if [[ "${local_dropbear_count}" == "0" ]] || [[ "${listener_2222_count}" == "0" ]]; then
    echo "Local SSH runtime did not recover after purge" >&2
    exit 1
  fi
fi

echo "Legacy SSH runtime purge verification passed"
