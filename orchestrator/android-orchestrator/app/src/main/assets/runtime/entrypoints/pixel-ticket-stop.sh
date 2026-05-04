#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/apps/ticket-screen"
RUN_DIR="${BASE}/run"
LOCK_DIR="${RUN_DIR}/ticket-screen-start-stop.lock"
APP_PACKAGE="lv.jolkins.pixelorchestrator"
SUPERVISOR="${APP_PACKAGE}/.app.SupervisorService"
ACTION_TICKET_STOP="lv.jolkins.pixelorchestrator.action.TICKET_STOP_SERVER"

mkdir -p "${RUN_DIR}"
chcon u:object_r:shell_data_file:s0 "${BASE}" "${RUN_DIR}" 2>/dev/null || true

acquire_lock() {
  if ! mkdir "${LOCK_DIR}" >/dev/null 2>&1; then
    rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
    mkdir "${LOCK_DIR}" >/dev/null 2>&1 || return 0
  fi
  trap 'rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true' EXIT HUP INT TERM
}

acquire_lock

reset_debuggable() {
  value="$1"
  if [ -x /debug_ramdisk/magisk ]; then
    su -M -c "/debug_ramdisk/magisk resetprop ro.debuggable ${value}"
    return "$?"
  fi
  if command -v resetprop >/dev/null 2>&1; then
    su -M -c "resetprop ro.debuggable ${value}"
    return "$?"
  fi
  su -M -c "/system_ext/bin/magisk resetprop ro.debuggable ${value}"
}

am start-foreground-service \
  -n "${SUPERVISOR}" \
  -a "${ACTION_TICKET_STOP}" \
  --es orchestrator_action ticket_stop_server >/dev/null 2>&1 || true

settings put secure disable_secure_windows 0 >/dev/null 2>&1 || true
state_file="${BASE}/state/ro-debuggable-before-ticket"
if [ -r "${state_file}" ]; then
  original="$(sed -n '1p' "${state_file}" 2>/dev/null | tr -d '\r' || true)"
  case "${original}" in
    1) reset_debuggable 1 >/dev/null 2>&1 || true ;;
    *) reset_debuggable 0 >/dev/null 2>&1 || true ;;
  esac
  rm -f "${state_file}" >/dev/null 2>&1 || true
fi

kill_pid_file() {
  pid_file="$1"
  [ -r "${pid_file}" ] || return 0
  pid="$(sed -n '1p' "${pid_file}" 2>/dev/null | tr -d '\r' || true)"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    kill_one "-9" "${pid}"
  fi
  rm -f "${pid_file}" >/dev/null 2>&1 || true
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
  ps -A -o PID,ARGS 2>/dev/null | while IFS= read -r line; do
    case "${line}" in
      *"${needle}"*)
        set -- ${line}
        pid="${1:-}"
        case "${pid}" in
          ''|*[!0-9]*) continue ;;
          "$$") continue ;;
        esac
        kill_one "${signal}" "${pid}"
        ;;
    esac
  done
}

kill_pid_file "${RUN_DIR}/ticket-web-tunnel-service-loop.pid"
kill_pid_file "${RUN_DIR}/ticket-screen-cloudflared.pid"
kill_matching_with_signal "-9" "${BASE}/bin/ticket-web-tunnel-service-loop"
kill_matching_with_signal "-9" "/state/ticket-screen-cloudflared.yml"
kill_matching_with_signal "-9" "/data/local/pixel-stack/chroots/pihole/usr/bin/ffmpeg"
kill_matching_with_signal "-9" "/usr/bin/ffmpeg -hide_banner -loglevel warning -nostats -f rawvideo"
kill_matching_with_signal "-9" "while :; do screencap; sleep"
