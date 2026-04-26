#!/system/bin/sh
set -eu

SUBSCRIPTION_BOT_ROOT="${SUBSCRIPTION_BOT_ROOT:-/data/local/pixel-stack/apps/subscription-bot}"
ENV_FILE="${SUBSCRIPTION_BOT_ROOT}/env/subscription-bot.env"
PRIMARY_BIN="${SUBSCRIPTION_BOT_ROOT}/bin/subscription-bot.current"
FALLBACK_BIN="${SUBSCRIPTION_BOT_ROOT}/bin/subscription-bot"
RUN_DIR="${SUBSCRIPTION_BOT_ROOT}/run"
LOG_DIR="${SUBSCRIPTION_BOT_ROOT}/logs"
STATE_DIR="${SUBSCRIPTION_BOT_ROOT}/state"
LOG_FILE="${LOG_DIR}/subscription-bot.log"
HEARTBEAT_FILE="${RUN_DIR}/heartbeat.epoch"
BOT_PID_FILE="${RUN_DIR}/subscription-bot.pid"

mkdir -p "${LOG_DIR}" "${RUN_DIR}" "${STATE_DIR}"

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

export SUBSCRIPTION_BOT_DB_PATH="${SUBSCRIPTION_BOT_DB_PATH:-${STATE_DIR}/subscription_bot.db}"
export SUBSCRIPTION_BOT_SESSION_SECRET_FILE="${SUBSCRIPTION_BOT_SESSION_SECRET_FILE:-${STATE_DIR}/subscription-bot.session.secret}"
export SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH="${SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH:-${RUN_DIR}/subscription-bot.instance.lock}"

managed_db_path="${STATE_DIR}/subscription_bot.db"
managed_session_secret_file="${STATE_DIR}/subscription-bot.session.secret"
managed_lock_path="${RUN_DIR}/subscription-bot.instance.lock"
export SUBSCRIPTION_BOT_DB_PATH="${managed_db_path}"
export SUBSCRIPTION_BOT_SESSION_SECRET_FILE="${managed_session_secret_file}"
export SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH="${managed_lock_path}"

validate_managed_path() {
  label="$1"
  value="$2"
  allowed_prefix="$3"
  case "${value}" in
    "${allowed_prefix}"|${allowed_prefix}/*)
      ;;
    *)
      echo "[$(date -Iseconds)] invalid ${label}: ${value} (expected under ${allowed_prefix})" >> "${LOG_FILE}"
      exit 1
      ;;
  esac
}

validate_managed_path "SUBSCRIPTION_BOT_DB_PATH" "${SUBSCRIPTION_BOT_DB_PATH}" "${STATE_DIR}"
validate_managed_path "SUBSCRIPTION_BOT_SESSION_SECRET_FILE" "${SUBSCRIPTION_BOT_SESSION_SECRET_FILE}" "${STATE_DIR}"
validate_managed_path "SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH" "${SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH}" "${RUN_DIR}"

session_secret_file="${SUBSCRIPTION_BOT_SESSION_SECRET_FILE}"
session_secret_dir="$(dirname "${session_secret_file}")"
mkdir -p "${session_secret_dir}"
if [ ! -f "${session_secret_file}" ]; then
  if [ -r /dev/urandom ]; then
    od -An -N 24 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n' > "${session_secret_file}"
  else
    date +%s%N > "${session_secret_file}"
  fi
  chmod 600 "${session_secret_file}" >/dev/null 2>&1 || true
fi

cd "${SUBSCRIPTION_BOT_ROOT}"

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
