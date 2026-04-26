#!/system/bin/sh
set -eu

TRAIN_BOT_ROOT="${TRAIN_BOT_ROOT:-/data/local/pixel-stack/apps/train-bot}"
ENV_FILE="${TRAIN_BOT_ROOT}/env/train-bot.env"
LAUNCH_BIN="${TRAIN_BOT_ROOT}/bin/train-bot-launch"
RUN_DIR="${TRAIN_BOT_ROOT}/run"
LOG_DIR="${TRAIN_BOT_ROOT}/logs"
SERVICE_LOG="${LOG_DIR}/service-loop.log"
LOOP_NAME="train-bot-service-loop"
LOCK_DIR="${RUN_DIR}/${LOOP_NAME}.lock"
LOOP_PID_FILE="${RUN_DIR}/${LOOP_NAME}.pid"
BOT_PID_FILE="${RUN_DIR}/train-bot.pid"
SELF_PATH="${TRAIN_BOT_ROOT}/bin/${LOOP_NAME}"
PRIMARY_BOT_BIN="${TRAIN_BOT_ROOT}/bin/train-bot.current"
FALLBACK_BOT_BIN="${TRAIN_BOT_ROOT}/bin/train-bot"

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

read_pid_file() {
  pid_file="$1"
  if [ -r "${pid_file}" ]; then
    sed -n '1p' "${pid_file}" 2>/dev/null | tr -d '\r'
  fi
}

pid_matches_target() {
  pid="$1"
  target="$2"
  if [ -z "${pid}" ] || ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 1
  fi
  cmdline="$(pid_cmdline "${pid}" || true)"
  target_base="$(basename "${target}")"
  case "${cmdline}" in
    *"${target}"*|*" ${target_base} "*|"${target_base}"|*" ${target_base}") return 0 ;;
    *) return 1 ;;
  esac
}

pid_matches_loop() {
  pid_matches_target "${1:-}" "${SELF_PATH}"
}

find_existing_pid() {
  target="$1"
  target_base="$(basename "${target}")"
  ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v target="${target}" -v target_base="${target_base}" -v self_pid="$$" '
    function starts_with(value, prefix) { return index(value, prefix) == 1 }
    function next_is_boundary(value, prefix_len) {
      c = substr(value, prefix_len + 1, 1)
      return c == "" || c == " "
    }
    {
      pid = $1
      name = $2
      if (pid == self_pid) {
        next
      }
      args = ""
      if (NF >= 3) {
        args = substr($0, index($0, $3))
      }
      if (name == target_base ||
        args == target ||
        (starts_with(args, target) && next_is_boundary(args, length(target))) ||
        (starts_with(args, "sh " target) && next_is_boundary(args, length("sh " target))) ||
        (starts_with(args, target_base) && next_is_boundary(args, length(target_base)))) {
        print pid
        exit
      }
    }
  '
}

find_running_bot_pid() {
  running_pid="$(read_pid_file "${BOT_PID_FILE}" || true)"
  if pid_matches_target "${running_pid}" "${PRIMARY_BOT_BIN}" || pid_matches_target "${running_pid}" "${FALLBACK_BOT_BIN}"; then
    printf '%s\n' "${running_pid}"
    return 0
  fi

  running_pid="$(find_existing_pid "${PRIMARY_BOT_BIN}" || true)"
  if pid_matches_target "${running_pid}" "${PRIMARY_BOT_BIN}"; then
    printf '%s\n' "${running_pid}"
    return 0
  fi

  running_pid="$(find_existing_pid "${FALLBACK_BOT_BIN}" || true)"
  if pid_matches_target "${running_pid}" "${FALLBACK_BOT_BIN}"; then
    printf '%s\n' "${running_pid}"
    return 0
  fi

  return 1
}

wait_for_pid_exit() {
  pid="$1"
  while kill -0 "${pid}" >/dev/null 2>&1; do
    sleep 5
  done
}

kill_pid_and_wait() {
  pid="$1"
  [ -n "${pid}" ] || return 0
  kill "${pid}" >/dev/null 2>&1 || true

  attempts=0
  while [ "${attempts}" -lt 10 ]; do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 1
  done

  kill -9 "${pid}" >/dev/null 2>&1 || true
  sleep 1
}

sync_bot_pid_file() {
  running_pid="$(find_running_bot_pid || true)"
  if [ -n "${running_pid}" ]; then
    echo "${running_pid}" > "${BOT_PID_FILE}"
    return 0
  fi
  rm -f "${BOT_PID_FILE}" >/dev/null 2>&1 || true
  return 1
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
  rm -f "${BOT_PID_FILE}" >/dev/null 2>&1 || true
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
}

current_launch_pid=""
current_bot_pid=""
cleanup_done=0

ensure_loop_pid_file() {
  echo "$$" > "${LOOP_PID_FILE}"
}

terminate_managed_processes() {
  if [ -n "${current_launch_pid}" ] && kill -0 "${current_launch_pid}" >/dev/null 2>&1; then
    kill_pid_and_wait "${current_launch_pid}"
  fi
  if [ -n "${current_bot_pid}" ] && kill -0 "${current_bot_pid}" >/dev/null 2>&1; then
    kill_pid_and_wait "${current_bot_pid}"
  fi
}

cleanup_runtime() {
  if [ "${cleanup_done}" -eq 1 ]; then
    return 0
  fi
  cleanup_done=1
  release_lock
}

handle_shutdown() {
  terminate_managed_processes
  cleanup_runtime
  exit 0
}

if ! acquire_lock; then
  exit 0
fi

ensure_loop_pid_file
trap cleanup_runtime EXIT
trap handle_shutdown HUP INT TERM

window_start="$(date +%s)"
rapid_count=0
backoff="${SERVICE_BACKOFF_INITIAL_SEC}"

log "${LOOP_NAME} started"

while true; do
  ensure_loop_pid_file

  if sync_bot_pid_file; then
    current_bot_pid="$(read_pid_file "${BOT_PID_FILE}" || true)"
    current_launch_pid="$(find_existing_pid "${LAUNCH_BIN}" || true)"
    log "adopting existing train bot pid=${current_bot_pid}"
    wait_for_pid_exit "${current_bot_pid}"
    current_launch_pid=""
    current_bot_pid=""
    rm -f "${BOT_PID_FILE}" >/dev/null 2>&1 || true
    log "adopted train bot exited"
  fi

  log "starting train bot"
  rm -f "${BOT_PID_FILE}" >/dev/null 2>&1 || true
  env TRAIN_BOT_ROOT="${TRAIN_BOT_ROOT}" TRAIN_BOT_PID_FILE="${BOT_PID_FILE}" "${LAUNCH_BIN}" >> "${LOG_DIR}/train-bot.log" 2>&1 &
  child_pid="$!"
  current_launch_pid="${child_pid}"
  current_bot_pid=""

  set +e
  wait "${child_pid}"
  child_rc=$?
  set -e
  current_launch_pid=""
  current_bot_pid=""
  rm -f "${BOT_PID_FILE}" >/dev/null 2>&1 || true
  log "train bot exited rc=${child_rc}"

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
