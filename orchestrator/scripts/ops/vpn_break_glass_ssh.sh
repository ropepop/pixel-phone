#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

ADB_SERIAL="${ADB_SERIAL:-}"
DURATION_SEC="${BREAK_GLASS_DURATION_SEC:-600}"
SSH_PORT="${BREAK_GLASS_SSH_PORT:-2222}"

usage() {
  cat <<EOF_USAGE
Usage: $(basename "$0") [options]

Temporarily open public SSH access by inserting a short-lived ACCEPT rule in the
VPN SSH guard chains. Rule is automatically removed after the duration expires.

Options:
  --adb-serial SERIAL       adb serial/device (default: first "device")
  --duration-sec SEC        break-glass window in seconds (default: ${DURATION_SEC})
  --ssh-port PORT           SSH port guarded by VPN chains (default: ${SSH_PORT})
  -h, --help                Show this help text
EOF_USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial)
      shift
      ADB_SERIAL="${1:-}"
      ;;
    --duration-sec)
      shift
      DURATION_SEC="${1:-}"
      ;;
    --ssh-port)
      shift
      SSH_PORT="${1:-}"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

[[ "${DURATION_SEC}" =~ ^[0-9]+$ ]] || { echo "--duration-sec must be numeric" >&2; exit 2; }
[[ "${SSH_PORT}" =~ ^[0-9]+$ ]] || { echo "--ssh-port must be numeric" >&2; exit 2; }
(( DURATION_SEC > 0 )) || { echo "--duration-sec must be > 0" >&2; exit 2; }
(( SSH_PORT >= 1 && SSH_PORT <= 65535 )) || { echo "--ssh-port must be in range 1..65535" >&2; exit 2; }

command -v adb >/dev/null 2>&1 || {
  echo "adb is required" >&2
  exit 1
}

if [[ -z "${ADB_SERIAL}" ]]; then
  ADB_SERIAL="$(pixel_transport_require_adb_serial)"
fi
[[ -n "${ADB_SERIAL}" ]] || {
  echo "No adb device in 'device' state found. Use --adb-serial." >&2
  exit 1
}

echo "Using adb serial: ${ADB_SERIAL}"

adb_su() {
  local cmd="$1"
  adb -s "${ADB_SERIAL}" shell su -c "${cmd}"
}

LOG_DIR="/data/local/tmp"
LOG_FILE="${LOG_DIR}/vpn-break-glass.log"
CHAIN_V4="PIXEL_SSH_GUARD"
CHAIN_V6="PIXEL_SSH_GUARD6"
IPTABLES_BIN="/system/bin/iptables"
IP6TABLES_BIN="/system/bin/ip6tables"
BREAK_GLASS_COMMENT="PIXEL_BREAK_GLASS"

adb_su "mkdir -p '${LOG_DIR}'"
adb_su "[ -x '${IPTABLES_BIN}' ] && '${IPTABLES_BIN}' -N '${CHAIN_V4}' >/dev/null 2>&1 || true"
adb_su "[ -x '${IP6TABLES_BIN}' ] && '${IP6TABLES_BIN}' -N '${CHAIN_V6}' >/dev/null 2>&1 || true"
adb_su "'${IPTABLES_BIN}' -C INPUT -p tcp --dport '${SSH_PORT}' -j '${CHAIN_V4}' >/dev/null 2>&1 || '${IPTABLES_BIN}' -I INPUT 1 -p tcp --dport '${SSH_PORT}' -j '${CHAIN_V4}' >/dev/null 2>&1"
adb_su "'${IP6TABLES_BIN}' -C INPUT -p tcp --dport '${SSH_PORT}' -j '${CHAIN_V6}' >/dev/null 2>&1 || '${IP6TABLES_BIN}' -I INPUT 1 -p tcp --dport '${SSH_PORT}' -j '${CHAIN_V6}' >/dev/null 2>&1"
adb_su "'${IPTABLES_BIN}' -C '${CHAIN_V4}' -p tcp --dport '${SSH_PORT}' -j DROP >/dev/null 2>&1 || '${IPTABLES_BIN}' -A '${CHAIN_V4}' -p tcp --dport '${SSH_PORT}' -j DROP >/dev/null 2>&1"
adb_su "'${IP6TABLES_BIN}' -C '${CHAIN_V6}' -p tcp --dport '${SSH_PORT}' -j DROP >/dev/null 2>&1 || '${IP6TABLES_BIN}' -A '${CHAIN_V6}' -p tcp --dport '${SSH_PORT}' -j DROP >/dev/null 2>&1"
adb_su "[ -x '${IPTABLES_BIN}' ] && '${IPTABLES_BIN}' -D '${CHAIN_V4}' -p tcp --dport '${SSH_PORT}' -m comment --comment '${BREAK_GLASS_COMMENT}' -j ACCEPT >/dev/null 2>&1 || true"
adb_su "[ -x '${IP6TABLES_BIN}' ] && '${IP6TABLES_BIN}' -D '${CHAIN_V6}' -p tcp --dport '${SSH_PORT}' -m comment --comment '${BREAK_GLASS_COMMENT}' -j ACCEPT >/dev/null 2>&1 || true"
adb_su "'${IPTABLES_BIN}' -I '${CHAIN_V4}' 1 -p tcp --dport '${SSH_PORT}' -m comment --comment '${BREAK_GLASS_COMMENT}' -j ACCEPT"
adb_su "'${IP6TABLES_BIN}' -I '${CHAIN_V6}' 1 -p tcp --dport '${SSH_PORT}' -m comment --comment '${BREAK_GLASS_COMMENT}' -j ACCEPT"
adb_su "printf '[%s] %s\\n' \"\$(date '+%Y-%m-%dT%H:%M:%S%z')\" 'break-glass opened ssh port ${SSH_PORT} for ${DURATION_SEC}s' >> '${LOG_FILE}' 2>/dev/null || true"

cleanup_cmd="sleep ${DURATION_SEC}; ${IPTABLES_BIN} -D ${CHAIN_V4} -p tcp --dport ${SSH_PORT} -m comment --comment ${BREAK_GLASS_COMMENT} -j ACCEPT >/dev/null 2>&1 || true; ${IP6TABLES_BIN} -D ${CHAIN_V6} -p tcp --dport ${SSH_PORT} -m comment --comment ${BREAK_GLASS_COMMENT} -j ACCEPT >/dev/null 2>&1 || true; printf '[%s] %s\\n' \"\$(date '+%Y-%m-%dT%H:%M:%S%z')\" 'break-glass expired for ssh port ${SSH_PORT}' >> '${LOG_FILE}' 2>/dev/null || true"
adb_su "nohup sh -c \"${cleanup_cmd}\" >/dev/null 2>&1 &"

echo "break-glass active for ${DURATION_SEC}s on port ${SSH_PORT}"

echo "Break-glass rule inserted. It will auto-remove after ${DURATION_SEC}s."
