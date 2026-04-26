#!/system/bin/sh
set -eu

SITE_NOTIFIER_ROOT="${SITE_NOTIFIER_ROOT:-/data/local/pixel-stack/apps/site-notifications}"
ENV_FILE="${SITE_NOTIFIER_ROOT}/env/site-notifications.env"
LAUNCH_BIN="${SITE_NOTIFIER_ROOT}/bin/site-notifier-launch"
RUN_DIR="${SITE_NOTIFIER_ROOT}/run"
LOG_DIR="${SITE_NOTIFIER_ROOT}/logs"
SERVICE_LOG="${LOG_DIR}/service-loop.log"
LOOP_NAME="site-notifier-service-loop"
LOCK_DIR="${RUN_DIR}/${LOOP_NAME}.lock"
LOOP_PID_FILE="${RUN_DIR}/${LOOP_NAME}.pid"
NOTIFIER_PID_FILE="${RUN_DIR}/site-notifier.pid"
SELF_PATH="${SITE_NOTIFIER_ROOT}/bin/${LOOP_NAME}"

mkdir -p "${RUN_DIR}" "${LOG_DIR}"

load_env_file() {
  env_path="$1"
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      ''|'#'*) continue ;;
      *=*) ;;
      *) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "${key}" in
      [A-Za-z_][A-Za-z0-9_]*) export "${key}=${value}" ;;
      *) continue ;;
    esac
  done < "${env_path}"
}

if [ -r "${ENV_FILE}" ]; then
  load_env_file "${ENV_FILE}"
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

  pid="$(sed -n '1p' "${LOOP_PID_FILE}" 2>/dev/null | tr -d '\r' || true)"
  if pid_matches_loop "${pid}"; then
    log "another ${LOOP_NAME} instance is already running (pid=${pid})"
    return 1
  fi

  log "stale ${LOOP_NAME} lock detected; resetting lock state"
  rm -f "${LOOP_PID_FILE}" >/dev/null 2>&1 || true
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
  mkdir "${LOCK_DIR}" >/dev/null 2>&1 || return 1
  return 0
}

release_lock() {
  rm -f "${LOOP_PID_FILE}" >/dev/null 2>&1 || true
  rm -f "${NOTIFIER_PID_FILE}" >/dev/null 2>&1 || true
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
}

if ! acquire_lock; then
  exit 0
fi

echo "$$" > "${LOOP_PID_FILE}"
trap release_lock EXIT HUP INT TERM

window_start="$(date +%s)"
rapid_count=0
backoff="${SERVICE_BACKOFF_INITIAL_SEC}"

log "${LOOP_NAME} started"

while true; do
  log "starting site notifier"
  "${LAUNCH_BIN}" >> "${LOG_DIR}/daemon.log" 2>&1 &
  child_pid="$!"
  echo "${child_pid}" > "${NOTIFIER_PID_FILE}"

  set +e
  wait "${child_pid}"
  child_rc=$?
  set -e
  rm -f "${NOTIFIER_PID_FILE}" >/dev/null 2>&1 || true
  log "site notifier exited rc=${child_rc}"

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
