#!/system/bin/sh
set -eu

PIXEL_VPN_ROOT="${PIXEL_VPN_ROOT:-/data/local/pixel-stack/vpn}"
CONF_FILE="${PIXEL_VPN_ROOT}/conf/tailscale.env"
if [ ! -r "${CONF_FILE}" ]; then
  CONF_FILE="/data/local/pixel-stack/conf/vpn/tailscale.env"
fi
LAUNCH_BIN="${PIXEL_VPN_ROOT}/bin/pixel-vpn-launch"
RUN_DIR="${PIXEL_VPN_ROOT}/run"
LOG_DIR="${PIXEL_VPN_ROOT}/logs"
SERVICE_LOG="${LOG_DIR}/pixel-vpn-service-loop.log"
LOOP_NAME="pixel-vpn-service-loop"
LOCK_DIR="${RUN_DIR}/${LOOP_NAME}.lock"
PID_FILE="${RUN_DIR}/${LOOP_NAME}.pid"
SELF_PATH="${PIXEL_VPN_ROOT}/bin/${LOOP_NAME}"
DUPLICATE_MARK_FILE="${RUN_DIR}/${LOOP_NAME}.duplicate"

mkdir -p "${RUN_DIR}" "${LOG_DIR}"

if [ -r "${CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${CONF_FILE}"
  set +a
fi

: "${SERVICE_MAX_RAPID_RESTARTS:=5}"
: "${SERVICE_RAPID_WINDOW_SEC:=300}"
: "${SERVICE_BACKOFF_INITIAL_SEC:=5}"
: "${SERVICE_BACKOFF_MAX_SEC:=60}"

ts() { date '+%Y-%m-%dT%H:%M:%S%z'; }
log() { printf '[%s] %s\n' "$(ts)" "$*" >> "${SERVICE_LOG}"; }

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  if command -v ps >/dev/null 2>&1; then
    ps -p "${pid}" -o command= 2>/dev/null || true
    return 0
  fi
  return 1
}

pid_matches_loop() {
  pid="$1"
  if [ -z "${pid}" ] || ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 1
  fi
  cmdline="$(pid_cmdline "${pid}" || true)"
  case " ${cmdline} " in
    *" ${SELF_PATH} "*|*" ${LOOP_NAME} "*) return 0 ;;
    *) return 1 ;;
  esac
}

acquire_lock() {
  if mkdir "${LOCK_DIR}" >/dev/null 2>&1; then
    return 0
  fi

  pid="$(sed -n '1p' "${PID_FILE}" 2>/dev/null | tr -d '\r' || true)"
  if pid_matches_loop "${pid}"; then
    if [ ! -f "${DUPLICATE_MARK_FILE}" ]; then
      log "another ${LOOP_NAME} instance is already running (pid=${pid})"
      : > "${DUPLICATE_MARK_FILE}"
    fi
    return 1
  fi
  log "stale ${LOOP_NAME} lock detected; resetting lock state"
  rm -f "${DUPLICATE_MARK_FILE}" >/dev/null 2>&1 || true
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
  mkdir "${LOCK_DIR}" >/dev/null 2>&1 || return 1
  return 0
}

release_lock() {
  rm -f "${DUPLICATE_MARK_FILE}" >/dev/null 2>&1 || true
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
}

cleanup() {
  release_lock
}

if ! acquire_lock; then
  exit 0
fi

echo "$$" > "${PID_FILE}"
trap cleanup EXIT HUP INT TERM

window_start="$(date +%s)"
rapid_count=0
backoff="${SERVICE_BACKOFF_INITIAL_SEC}"

log "${LOOP_NAME} started"

while true; do
  log "starting tailscaled"
  "${LAUNCH_BIN}" >> "${LOG_DIR}/tailscaled.log" 2>&1 &
  child_pid="$!"

  set +e
  wait "${child_pid}"
  child_rc=$?
  set -e
  log "tailscaled exited rc=${child_rc}"

  now="$(date +%s)"
  if [ $((now - window_start)) -gt "${SERVICE_RAPID_WINDOW_SEC}" ]; then
    window_start="${now}"
    rapid_count=0
    backoff="${SERVICE_BACKOFF_INITIAL_SEC}"
  fi

  rapid_count=$((rapid_count + 1))
  if [ "${rapid_count}" -gt "${SERVICE_MAX_RAPID_RESTARTS}" ]; then
    log "too many rapid restarts (${rapid_count}/${SERVICE_MAX_RAPID_RESTARTS}), sleeping 120s"
    sleep 120
    window_start="$(date +%s)"
    rapid_count=0
    backoff="${SERVICE_BACKOFF_INITIAL_SEC}"
    continue
  fi

  sleep "${backoff}"
  if [ "${backoff}" -lt "${SERVICE_BACKOFF_MAX_SEC}" ]; then
    backoff=$((backoff * 2))
    if [ "${backoff}" -gt "${SERVICE_BACKOFF_MAX_SEC}" ]; then
      backoff="${SERVICE_BACKOFF_MAX_SEC}"
    fi
  fi
done
