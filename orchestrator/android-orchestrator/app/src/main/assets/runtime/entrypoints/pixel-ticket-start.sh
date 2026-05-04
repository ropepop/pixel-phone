#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/apps/ticket-screen"
CONF_ENV="/data/local/pixel-stack/conf/apps/ticket-screen.env"
RUNTIME_ENV="${BASE}/env/ticket-screen.env"
TPL_DIR="/data/local/pixel-stack/templates/ticket"
BIN_DIR="${BASE}/bin"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
STATE_DIR="${BASE}/state"
TUNNEL_LOOP_BIN="${BIN_DIR}/ticket-web-tunnel-service-loop"
TUNNEL_LOOP_PID_FILE="${RUN_DIR}/ticket-web-tunnel-service-loop.pid"
TUNNEL_LOOP_LOG_FILE="${LOG_DIR}/ticket-web-tunnel-service-loop.log"
CLOUDFLARED_PID_FILE="${RUN_DIR}/ticket-screen-cloudflared.pid"
TPL_TUNNEL_LOOP="${TPL_DIR}/ticket-web-tunnel-service-loop.sh"
LOCK_DIR="${RUN_DIR}/ticket-screen-start-stop.lock"
APP_PACKAGE="lv.jolkins.pixelorchestrator"
SUPERVISOR="${APP_PACKAGE}/.app.SupervisorService"
ACTION_TICKET_START="lv.jolkins.pixelorchestrator.action.TICKET_START_SERVER"

mkdir -p "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${STATE_DIR}" "${BASE}/env"
chcon u:object_r:shell_data_file:s0 "${BASE}" "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${STATE_DIR}" "${BASE}/env" 2>/dev/null || true

acquire_lock() {
  if ! mkdir "${LOCK_DIR}" >/dev/null 2>&1; then
    rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
    mkdir "${LOCK_DIR}" >/dev/null 2>&1 || return 0
  fi
  trap 'rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true' EXIT HUP INT TERM
}

acquire_lock

if [ ! -f "${RUNTIME_ENV}" ] && [ -f "${CONF_ENV}" ]; then
  cp "${CONF_ENV}" "${RUNTIME_ENV}"
  chmod 600 "${RUNTIME_ENV}" >/dev/null 2>&1 || true
  chcon u:object_r:shell_data_file:s0 "${RUNTIME_ENV}" 2>/dev/null || true
fi

if [ -f "${RUNTIME_ENV}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${RUNTIME_ENV}"
  set +a
fi

am start-foreground-service \
  -n "${SUPERVISOR}" \
  -a "${ACTION_TICKET_START}" \
  --es orchestrator_action ticket_start_server >/dev/null

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

if is_true "${TICKET_SCREEN_OPEN_ORCHESTRATOR_ON_START:-0}"; then
  am start -n "${APP_PACKAGE}/.app.MainActivity" >/dev/null 2>&1 || true
fi

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    timeout 1 tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  ps -p "${pid}" -o ARGS= 2>/dev/null || true
}

pid_matches_target() {
  pid="$1"
  target="$2"
  [ -n "${pid}" ] || return 1
  kill -0 "${pid}" >/dev/null 2>&1 || return 1
  cmdline="$(pid_cmdline "${pid}")"
  case "${cmdline}" in
    *"${target}"*) return 0 ;;
    *) return 1 ;;
  esac
}

read_pid_file() {
  if [ -r "${1}" ]; then
    sed -n '1p' "${1}" 2>/dev/null | tr -d '\r'
  fi
}

cleanup_extra_tunnel_loops() {
  keep_loop_pid="${1:-}"
  keep_cloudflared_pid="${2:-}"
  kill_matching_with_signal "-9" "${TUNNEL_LOOP_BIN}" "${keep_loop_pid}"
  kill_matching_with_signal "-9" "/state/ticket-screen-cloudflared.yml" "${keep_cloudflared_pid}"
  if [ -z "${keep_loop_pid}" ]; then
    rm -f "${TUNNEL_LOOP_PID_FILE}" "${CLOUDFLARED_PID_FILE}" >/dev/null 2>&1 || true
  fi
}

kill_one() {
  signal="$1"
  pid="$2"
  [ -n "${pid}" ] || return 0
  if [ -n "${signal}" ]; then
    kill "${signal}" "${pid}" >/dev/null 2>&1 || su -M -c "kill ${signal} ${pid}" >/dev/null 2>&1 || true
  else
    kill "${pid}" >/dev/null 2>&1 || su -M -c "kill ${pid}" >/dev/null 2>&1 || true
  fi
}

kill_matching_with_signal() {
  signal="$1"
  needle="$2"
  keep_pid="${3:-}"
  ps -A -o PID,ARGS 2>/dev/null | while IFS= read -r line; do
    case "${line}" in
      *"${needle}"*)
        set -- ${line}
        pid="${1:-}"
        case "${pid}" in
          ''|*[!0-9]*) ;;
          "$$") ;;
          "$keep_pid") ;;
          *) kill_one "${signal}" "${pid}" ;;
        esac
        ;;
    esac
  done
}

start_tunnel_loop_if_needed() {
  if ! is_true "${TICKET_SCREEN_TUNNEL_ENABLED:-1}"; then
    return 0
  fi
  if [ ! -f "${TPL_TUNNEL_LOOP}" ]; then
    echo "missing ticket tunnel loop template: ${TPL_TUNNEL_LOOP}" >&2
    return 1
  fi
  pid="$(read_pid_file "${TUNNEL_LOOP_PID_FILE}" || true)"
  cloudflared_pid="$(read_pid_file "${CLOUDFLARED_PID_FILE}" || true)"
  if pid_matches_target "${pid}" "${TUNNEL_LOOP_BIN}"; then
    cleanup_extra_tunnel_loops "${pid}" "${cloudflared_pid}"
    return 0
  fi
  cleanup_extra_tunnel_loops "" ""
  cp "${TPL_TUNNEL_LOOP}" "${TUNNEL_LOOP_BIN}"
  chmod 0755 "${TUNNEL_LOOP_BIN}"
  chcon u:object_r:shell_data_file:s0 "${TUNNEL_LOOP_BIN}" 2>/dev/null || true
  nohup "${TUNNEL_LOOP_BIN}" >> "${TUNNEL_LOOP_LOG_FILE}" 2>&1 &
  echo "$!" > "${TUNNEL_LOOP_PID_FILE}"
}

start_tunnel_loop_if_needed
