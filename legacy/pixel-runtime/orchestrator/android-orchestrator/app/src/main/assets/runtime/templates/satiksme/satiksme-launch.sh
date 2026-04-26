#!/system/bin/sh
set -eu

SATIKSME_BOT_ROOT="${SATIKSME_BOT_ROOT:-/data/local/pixel-stack/apps/satiksme-bot}"
ENV_FILE="${SATIKSME_BOT_ROOT}/env/satiksme-bot.env"
PRIMARY_BIN="${SATIKSME_BOT_ROOT}/bin/satiksme-bot.current"
FALLBACK_BIN="${SATIKSME_BOT_ROOT}/bin/satiksme-bot"
RUN_DIR="${SATIKSME_BOT_ROOT}/run"
LOG_DIR="${SATIKSME_BOT_ROOT}/logs"
LOG_FILE="${LOG_DIR}/satiksme-bot.log"
HEARTBEAT_FILE="${RUN_DIR}/heartbeat.epoch"
BOT_PID_FILE="${RUN_DIR}/satiksme-bot.pid"

mkdir -p "${LOG_DIR}" "${RUN_DIR}" "${SATIKSME_BOT_ROOT}/data/catalog" "${SATIKSME_BOT_ROOT}/state"

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

export DB_PATH="${DB_PATH:-${SATIKSME_BOT_ROOT}/satiksme_bot.db}"
export SATIKSME_CATALOG_MIRROR_DIR="${SATIKSME_CATALOG_MIRROR_DIR:-${SATIKSME_BOT_ROOT}/data/catalog/source}"
export SATIKSME_CATALOG_OUTPUT_PATH="${SATIKSME_CATALOG_OUTPUT_PATH:-${SATIKSME_BOT_ROOT}/data/catalog/generated/catalog.json}"
export SATIKSME_RUNTIME_SNAPSHOT_GC_ENABLED="${SATIKSME_RUNTIME_SNAPSHOT_GC_ENABLED:-true}"

cd "${SATIKSME_BOT_ROOT}"

heartbeat_loop() {
  while true; do
    date +%s > "${HEARTBEAT_FILE}" 2>/dev/null || true
    sleep 15
  done
}

forward_signal() {
  if [ -n "${child_pid:-}" ] && kill -0 "${child_pid}" >/dev/null 2>&1; then
    kill "${child_pid}" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  rm -f "${BOT_PID_FILE}" >/dev/null 2>&1 || true
  if [ -n "${heartbeat_pid:-}" ] && kill -0 "${heartbeat_pid}" >/dev/null 2>&1; then
    kill "${heartbeat_pid}" >/dev/null 2>&1 || true
  fi
  if [ -n "${heartbeat_pid:-}" ]; then
    wait "${heartbeat_pid}" >/dev/null 2>&1 || true
  fi
}

trap cleanup EXIT
trap forward_signal HUP INT TERM

heartbeat_loop &
heartbeat_pid="$!"

set +e
"${BIN}" >> "${LOG_FILE}" 2>&1 &
child_pid="$!"
echo "${child_pid}" > "${BOT_PID_FILE}"
while true; do
  wait "${child_pid}"
  child_rc=$?
  if kill -0 "${child_pid}" >/dev/null 2>&1; then
    continue
  fi
  break
done
set -e

exit "${child_rc}"
