#!/system/bin/sh
set -eu

TRAIN_BOT_ROOT="${TRAIN_BOT_ROOT:-/data/local/pixel-stack/apps/train-bot}"
ENV_FILE="${TRAIN_BOT_ROOT}/env/train-bot.env"
PRIMARY_BIN="${TRAIN_BOT_ROOT}/bin/train-bot.current"
FALLBACK_BIN="${TRAIN_BOT_ROOT}/bin/train-bot"
LOG_DIR="${TRAIN_BOT_ROOT}/logs"
LOG_FILE="${LOG_DIR}/train-bot.log"
HEARTBEAT_FILE="${TRAIN_BOT_ROOT}/run/heartbeat.epoch"
BOT_PID_FILE="${TRAIN_BOT_PID_FILE:-${TRAIN_BOT_ROOT}/run/train-bot.pid}"

mkdir -p "${LOG_DIR}" "${TRAIN_BOT_ROOT}/run" "${TRAIN_BOT_ROOT}/data/schedules" "${TRAIN_BOT_ROOT}/state"

if [ ! -f "${ENV_FILE}" ]; then
  echo "[$(date -Iseconds)] missing env file: ${ENV_FILE}" >> "${LOG_FILE}"
  exit 1
fi

set -a
# shellcheck disable=SC1090
. "${ENV_FILE}"
set +a

BIN="${PRIMARY_BIN}"
if [ ! -x "${BIN}" ]; then
  BIN="${FALLBACK_BIN}"
fi
if [ ! -x "${BIN}" ]; then
  echo "[$(date -Iseconds)] missing executable: ${PRIMARY_BIN} (${FALLBACK_BIN} fallback)" >> "${LOG_FILE}"
  exit 1
fi

if [ -z "${BOT_TOKEN:-}" ]; then
  echo "[$(date -Iseconds)] BOT_TOKEN is empty in ${ENV_FILE}" >> "${LOG_FILE}"
  exit 1
fi

export DB_PATH="${DB_PATH:-${TRAIN_BOT_ROOT}/train_bot.db}"
export SCHEDULE_DIR="${SCHEDULE_DIR:-${TRAIN_BOT_ROOT}/data/schedules}"
export TRAIN_RUNTIME_SNAPSHOT_GC_ENABLED="${TRAIN_RUNTIME_SNAPSHOT_GC_ENABLED:-true}"

# Keep relative paths from environment (for example ./train_bot.db) under runtime root.
cd "${TRAIN_BOT_ROOT}"

heartbeat_loop() {
  while true; do
    date +%s > "${HEARTBEAT_FILE}" 2>/dev/null || true
    sleep 15
  done
}

pid_alive() {
  pid="$1"
  [ -n "${pid}" ] || return 1
  kill -0 "${pid}" >/dev/null 2>&1
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

clear_bot_pid_file() {
  tracked_pid="$(sed -n '1p' "${BOT_PID_FILE}" 2>/dev/null | tr -d '\r' || true)"
  if [ -z "${tracked_pid}" ] || [ "${tracked_pid}" = "${child_pid:-}" ]; then
    rm -f "${BOT_PID_FILE}" >/dev/null 2>&1 || true
  fi
}

terminate_children() {
  if pid_alive "${child_pid:-}"; then
    kill_pid_and_wait "${child_pid}"
  fi
  if pid_alive "${heartbeat_pid:-}"; then
    kill "${heartbeat_pid}" >/dev/null 2>&1 || true
    wait "${heartbeat_pid}" >/dev/null 2>&1 || true
  fi
}

child_pid=""
heartbeat_pid=""
trap 'terminate_children' HUP INT TERM

heartbeat_loop &
heartbeat_pid="$!"

set +e
"${BIN}" >> "${LOG_FILE}" 2>&1 &
child_pid="$!"
echo "${child_pid}" > "${BOT_PID_FILE}"
wait "${child_pid}"
child_rc=$?
set -e

kill "${heartbeat_pid}" >/dev/null 2>&1 || true
wait "${heartbeat_pid}" >/dev/null 2>&1 || true
clear_bot_pid_file

exit "${child_rc}"
