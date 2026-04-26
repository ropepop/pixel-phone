#!/system/bin/sh
set -eu

SITE_NOTIFIER_ROOT="${SITE_NOTIFIER_ROOT:-/data/local/pixel-stack/apps/site-notifications}"
ENV_FILE="${SITE_NOTIFIER_ROOT}/env/site-notifications.env"
DEFAULT_PYTHON="${SITE_NOTIFIER_ROOT}/current/.venv/bin/python"

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

if [ ! -f "${ENV_FILE}" ]; then
  echo "unhealthy: missing_env_file"
  exit 1
fi

load_env_file "${ENV_FILE}"

PYTHON_BIN="${NOTIFIER_PYTHON_PATH:-${DEFAULT_PYTHON}}"
ENTRY_SCRIPT="${NOTIFIER_ENTRY_SCRIPT:-${SITE_NOTIFIER_ROOT}/current/app.py}"

if ! "${PYTHON_BIN}" -V >/dev/null 2>&1; then
  echo "unhealthy: missing_python"
  exit 1
fi

if [ ! -f "${ENTRY_SCRIPT}" ]; then
  echo "unhealthy: missing_entry_script"
  exit 1
fi

export RUNTIME_CONTEXT_POLICY="orchestrator_root"

# Keep relative paths from .env (for example ./state/state.json) under runtime root.
cd "${SITE_NOTIFIER_ROOT}"
exec "${PYTHON_BIN}" "${ENTRY_SCRIPT}" healthcheck
