#!/system/bin/sh
set -eu

SITE_NOTIFIER_ROOT="${SITE_NOTIFIER_ROOT:-/data/local/pixel-stack/apps/site-notifications}"
ENV_FILE="${SITE_NOTIFIER_ROOT}/env/site-notifications.env"
LOG_DIR="${SITE_NOTIFIER_ROOT}/logs"
LOG_FILE="${LOG_DIR}/daemon.log"
RUN_DIR="${SITE_NOTIFIER_ROOT}/run"
HEARTBEAT_FILE="${RUN_DIR}/heartbeat.epoch"
DEFAULT_PYTHON="${SITE_NOTIFIER_ROOT}/current/.venv/bin/python"

mkdir -p "${LOG_DIR}" "${RUN_DIR}" "${SITE_NOTIFIER_ROOT}/state"

if [ ! -f "${ENV_FILE}" ]; then
  echo "[$(date -Iseconds)] missing env file: ${ENV_FILE}" >> "${LOG_FILE}"
  exit 1
fi

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

load_env_file "${ENV_FILE}"

PYTHON_BIN="${NOTIFIER_PYTHON_PATH:-${DEFAULT_PYTHON}}"
ENTRY_SCRIPT="${NOTIFIER_ENTRY_SCRIPT:-${SITE_NOTIFIER_ROOT}/current/app.py}"
SITE_NOTIFIER_DNS_SERVER="${SITE_NOTIFIER_DNS_SERVER:-1.1.1.1}"
SITE_NOTIFIER_DNS_SERVER_ALT="${SITE_NOTIFIER_DNS_SERVER_ALT:-1.0.0.1}"

setprop net.dns1 "${SITE_NOTIFIER_DNS_SERVER}" >/dev/null 2>&1 || true
setprop net.dns2 "${SITE_NOTIFIER_DNS_SERVER_ALT}" >/dev/null 2>&1 || true

if ! "${PYTHON_BIN}" -V >/dev/null 2>&1; then
  echo "[$(date -Iseconds)] missing bundled python interpreter: ${PYTHON_BIN}" >> "${LOG_FILE}"
  exit 1
fi
if [ ! -f "${ENTRY_SCRIPT}" ]; then
  echo "[$(date -Iseconds)] missing notifier entry script: ${ENTRY_SCRIPT}" >> "${LOG_FILE}"
  exit 1
fi

export RUNTIME_CONTEXT_POLICY="orchestrator_root"

# Keep relative paths from .env (for example ./state/state.json) under runtime root.
cd "${SITE_NOTIFIER_ROOT}"

heartbeat_loop() {
  while true; do
    date +%s > "${HEARTBEAT_FILE}" 2>/dev/null || true
    sleep 15
  done
}

heartbeat_loop &
heartbeat_pid="$!"

set +e
"${PYTHON_BIN}" "${ENTRY_SCRIPT}" daemon >> "${LOG_FILE}" 2>&1 &
child_pid="$!"
wait "${child_pid}"
child_rc=$?
set -e

kill "${heartbeat_pid}" >/dev/null 2>&1 || true
wait "${heartbeat_pid}" >/dev/null 2>&1 || true

exit "${child_rc}"
