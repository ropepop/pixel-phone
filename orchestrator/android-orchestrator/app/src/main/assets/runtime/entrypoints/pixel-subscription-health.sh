#!/system/bin/sh
set -eu

SUBSCRIPTION_BOT_ROOT="${SUBSCRIPTION_BOT_ROOT:-/data/local/pixel-stack/apps/subscription-bot}"
ENV_FILE="${SUBSCRIPTION_BOT_ROOT}/env/subscription-bot.env"
RUN_DIR="${SUBSCRIPTION_BOT_ROOT}/run"
BOT_PID_FILE="${RUN_DIR}/subscription-bot.pid"
TUNNEL_PID_FILE="${RUN_DIR}/subscription-bot-cloudflared.pid"
HEARTBEAT_FILE="${RUN_DIR}/heartbeat.epoch"
HEARTBEAT_MAX_AGE_SEC="${SUBSCRIPTION_HEARTBEAT_MAX_AGE_SEC:-120}"

SUBSCRIPTION_BOT_WEB_ENABLED="${SUBSCRIPTION_BOT_WEB_ENABLED:-0}"
SUBSCRIPTION_BOT_WEB_SHELL_ENABLED="${SUBSCRIPTION_BOT_WEB_SHELL_ENABLED:-0}"
SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED="${SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED:-0}"
SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL="${SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL:-}"

emit() {
  key="$1"
  value="$2"
  printf '%s=%s\n' "${key}" "${value}"
}

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

load_env_file() {
  env_path="$1"
  [ -r "${env_path}" ] || return 0

  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      ''|'#'*) continue ;;
      *=*) ;;
      *) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "${value}" in
      \"*\") value="${value#\"}"; value="${value%\"}" ;;
      \'*\') value="${value#\'}"; value="${value%\'}" ;;
    esac
    case "${key}" in
      [A-Za-z_][A-Za-z0-9_]*) export "${key}=${value}" ;;
      *) continue ;;
    esac
  done < "${env_path}"
}

read_pid_file() {
  pid_file="$1"
  if [ -r "${pid_file}" ]; then
    sed -n '1p' "${pid_file}" 2>/dev/null | tr -d '\r'
  fi
}

pid_alive() {
  pid="$1"
  [ -n "${pid}" ] || return 1
  kill -0 "${pid}" >/dev/null 2>&1
}

heartbeat_age_sec() {
  if [ ! -r "${HEARTBEAT_FILE}" ]; then
    return 1
  fi

  heartbeat_epoch="$(sed -n '1p' "${HEARTBEAT_FILE}" 2>/dev/null | tr -d '\r' | tr -d '[:space:]')"
  case "${heartbeat_epoch}" in
    ''|*[!0-9]*) return 1 ;;
  esac

  now_epoch="$(date +%s)"
  if [ "${heartbeat_epoch}" -gt "${now_epoch}" ]; then
    printf '0\n'
    return 0
  fi
  printf '%s\n' "$((now_epoch - heartbeat_epoch))"
  return 0
}

load_env_file "${ENV_FILE}"

subscription_pid="$(read_pid_file "${BOT_PID_FILE}" || true)"
tunnel_pid="$(read_pid_file "${TUNNEL_PID_FILE}" || true)"
heartbeat_age="unknown"
web_enabled="0"
web_shell_enabled="0"
tunnel_enabled="0"
failure_reason="ok"
healthy=1

if is_true "${SUBSCRIPTION_BOT_WEB_ENABLED}"; then
  web_enabled="1"
fi
if is_true "${SUBSCRIPTION_BOT_WEB_SHELL_ENABLED}"; then
  web_shell_enabled="1"
fi
if is_true "${SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED}"; then
  tunnel_enabled="1"
fi

if ! pid_alive "${subscription_pid}"; then
  healthy=0
  failure_reason="pid_missing"
else
  if heartbeat_age_value="$(heartbeat_age_sec)"; then
    heartbeat_age="${heartbeat_age_value}"
    if [ "${heartbeat_age_value}" -gt "${HEARTBEAT_MAX_AGE_SEC}" ]; then
      healthy=0
      failure_reason="heartbeat_stale"
    fi
  else
    healthy=0
    failure_reason="heartbeat_missing"
  fi
fi

if [ "${healthy}" = "1" ] && [ "${tunnel_enabled}" = "1" ] && ! pid_alive "${tunnel_pid}"; then
  healthy=0
  failure_reason="tunnel_missing"
fi

emit "subscription_bot_pid" "${subscription_pid}"
emit "subscription_tunnel_pid" "${tunnel_pid}"
emit "heartbeat_age_sec" "${heartbeat_age}"
emit "web_enabled" "${web_enabled}"
emit "web_shell_enabled" "${web_shell_enabled}"
emit "web_tunnel_enabled" "${tunnel_enabled}"
emit "public_base_url" "${SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL}"
emit "failure_reason" "${failure_reason}"
emit "healthy" "${healthy}"

if [ "${healthy}" = "1" ]; then
  exit 0
fi
exit 1
