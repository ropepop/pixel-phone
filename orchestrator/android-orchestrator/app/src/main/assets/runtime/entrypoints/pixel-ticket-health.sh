#!/system/bin/sh
set -eu

PORT="${TICKET_SCREEN_PORT:-9388}"
BASE="/data/local/pixel-stack/apps/ticket-screen"
CONF_ENV="/data/local/pixel-stack/conf/apps/ticket-screen.env"
RUNTIME_ENV="${BASE}/env/ticket-screen.env"
RUN_DIR="${BASE}/run"

if [ -r "${CONF_ENV}" ]; then
  # shellcheck disable=SC1090
  . "${CONF_ENV}"
fi
if [ -r "${RUNTIME_ENV}" ]; then
  # shellcheck disable=SC1090
  . "${RUNTIME_ENV}"
fi

local_ready=0
if command -v ss >/dev/null 2>&1; then
  ss -ltn 2>/dev/null | grep -E "127[.]0[.]0[.]1:${PORT}[[:space:]]" >/dev/null && local_ready=1
  ss -ltn 2>/dev/null | grep -E "[:.]${PORT}[[:space:]]" >/dev/null && local_ready=1
fi

if [ "${local_ready}" != "1" ] && command -v curl >/dev/null 2>&1; then
  curl -fsS --max-time 3 "http://127.0.0.1:${PORT}/api/v1/health" >/dev/null && local_ready=1
fi

if [ "${local_ready}" != "1" ]; then
  exit 1
fi

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

if ! is_true "${TICKET_SCREEN_TUNNEL_ENABLED:-1}"; then
  exit 0
fi

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  ps -p "${pid}" -o ARGS= 2>/dev/null || true
}

pid_matches() {
  pid="$1"
  needle="$2"
  [ -n "${pid}" ] || return 1
  kill -0 "${pid}" >/dev/null 2>&1 || return 1
  case "$(pid_cmdline "${pid}")" in
    *"${needle}"*) return 0 ;;
    *) return 1 ;;
  esac
}

read_pid() {
  [ -r "${1}" ] && sed -n '1p' "${1}" 2>/dev/null | tr -d '\r'
}

loop_pid="$(read_pid "${RUN_DIR}/ticket-web-tunnel-service-loop.pid" || true)"
cloudflared_pid="$(read_pid "${RUN_DIR}/ticket-screen-cloudflared.pid" || true)"

pid_matches "${loop_pid}" "${BASE}/bin/ticket-web-tunnel-service-loop" || exit 1
pid_matches "${cloudflared_pid}" "/state/ticket-screen-cloudflared.yml" || exit 1
exit 0
