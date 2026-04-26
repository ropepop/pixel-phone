#!/system/bin/sh
set -eu

SATIKSME_BOT_ROOT="${SATIKSME_BOT_ROOT:-/data/local/pixel-stack/apps/satiksme-bot}"
ENV_FILE="${SATIKSME_BOT_ROOT}/env/satiksme-bot.env"
RUN_DIR="${SATIKSME_BOT_ROOT}/run"
BOT_PID_FILE="${RUN_DIR}/satiksme-bot.pid"
HEARTBEAT_FILE="${RUN_DIR}/heartbeat.epoch"
TUNNEL_SUPERVISOR_PID_FILE="${RUN_DIR}/satiksme-web-tunnel-service-loop.pid"
TUNNEL_PID_FILE="${RUN_DIR}/satiksme-bot-cloudflared.pid"
ROOTFS_CURL_ROOT="${ROOTFS_CURL_ROOT:-/data/local/pixel-stack/chroots/adguardhome}"
HEARTBEAT_MAX_AGE_SEC="${SATIKSME_HEARTBEAT_MAX_AGE_SEC:-120}"
PROBE_TIMEOUT_SEC="${SATIKSME_TUNNEL_PROBE_TIMEOUT_SEC:-8}"

SATIKSME_WEB_ENABLED="${SATIKSME_WEB_ENABLED:-0}"
SATIKSME_WEB_TUNNEL_ENABLED="${SATIKSME_WEB_TUNNEL_ENABLED:-0}"
SATIKSME_WEB_PUBLIC_BASE_URL="${SATIKSME_WEB_PUBLIC_BASE_URL:-}"

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

resolve_probe_curl() {
  if command -v curl >/dev/null 2>&1; then
    printf 'native:%s\n' "$(command -v curl 2>/dev/null || true)"
    return 0
  fi
  if [ -n "${ROOTFS_CURL_ROOT}" ] && [ -x "${ROOTFS_CURL_ROOT}/usr/bin/curl" ] && [ -x "${ROOTFS_CURL_ROOT}/usr/bin/env" ] && \
    chroot "${ROOTFS_CURL_ROOT}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
    printf 'chroot:%s\n' "${ROOTFS_CURL_ROOT}"
    return 0
  fi
  return 1
}

probe_http_code() {
  probe_spec="$1"
  probe_url="$2"
  probe_timeout="$3"
  case "${probe_spec}" in
    native:*)
      probe_bin="${probe_spec#native:}"
      probe_code="$("${probe_bin}" -ksS -o /dev/null -w '%{http_code}' --max-time "${probe_timeout}" "${probe_url}" 2>/dev/null || true)"
      ;;
    chroot:*)
      probe_rootfs="${probe_spec#chroot:}"
      probe_code="$(chroot "${probe_rootfs}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -ksS -o /dev/null -w '%{http_code}' --max-time "${probe_timeout}" "${probe_url}" 2>/dev/null || true)"
      ;;
    *)
      probe_code="000"
      ;;
  esac
  case "${probe_code}" in
    ""|"000000") probe_code="000" ;;
  esac
  printf '%s\n' "${probe_code}"
}

load_env_file "${ENV_FILE}"

satiksme_pid="$(read_pid_file "${BOT_PID_FILE}" || true)"
heartbeat_age="unknown"
satiksme_tunnel_enabled="0"
tunnel_supervisor_pid=""
tunnel_pid=""
public_root_code="000"
public_app_code="000"
tunnel_probe_available="0"
failure_reason="ok"
healthy=1

if is_true "${SATIKSME_WEB_ENABLED}"; then
  if is_true "${SATIKSME_WEB_TUNNEL_ENABLED}"; then
    satiksme_tunnel_enabled="1"
  fi
fi

if ! pid_alive "${satiksme_pid}"; then
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

if [ "${healthy}" = "1" ] && [ "${satiksme_tunnel_enabled}" = "1" ]; then
  tunnel_supervisor_pid="$(read_pid_file "${TUNNEL_SUPERVISOR_PID_FILE}" || true)"
  if ! pid_alive "${tunnel_supervisor_pid}"; then
    healthy=0
    failure_reason="tunnel_supervisor_missing"
  fi
fi

if [ "${healthy}" = "1" ] && [ "${satiksme_tunnel_enabled}" = "1" ]; then
  tunnel_pid="$(read_pid_file "${TUNNEL_PID_FILE}" || true)"
  if ! pid_alive "${tunnel_pid}"; then
    healthy=0
    failure_reason="tunnel_pid_missing"
  fi
fi

if [ "${healthy}" = "1" ] && [ "${satiksme_tunnel_enabled}" = "1" ]; then
  probe_spec="$(resolve_probe_curl 2>/dev/null || true)"
  if [ -z "${SATIKSME_WEB_PUBLIC_BASE_URL}" ] || [ -z "${probe_spec}" ]; then
    healthy=0
    failure_reason="tunnel_probe_unavailable"
  else
    tunnel_probe_available="1"
    public_root_url="$(printf '%s' "${SATIKSME_WEB_PUBLIC_BASE_URL}" | sed 's#/*$##')"
    public_root_code="$(probe_http_code "${probe_spec}" "${public_root_url}/" "${PROBE_TIMEOUT_SEC}")"
    public_app_code="$(probe_http_code "${probe_spec}" "${public_root_url}/app" "${PROBE_TIMEOUT_SEC}")"
    if [ "${public_root_code}" != "200" ]; then
      healthy=0
      failure_reason="public_root_failed"
    elif [ "${public_app_code}" != "200" ]; then
      healthy=0
      failure_reason="public_app_failed"
    fi
  fi
fi

emit "satiksme_bot_pid" "${satiksme_pid}"
emit "heartbeat_age_sec" "${heartbeat_age}"
emit "tunnel_enabled" "${satiksme_tunnel_enabled}"
emit "tunnel_supervisor_pid" "${tunnel_supervisor_pid}"
emit "tunnel_pid" "${tunnel_pid}"
emit "public_base_url" "${SATIKSME_WEB_PUBLIC_BASE_URL}"
emit "public_root_code" "${public_root_code}"
emit "public_app_code" "${public_app_code}"
emit "tunnel_probe_available" "${tunnel_probe_available}"
emit "failure_reason" "${failure_reason}"
emit "healthy" "${healthy}"

if [ "${healthy}" = "1" ]; then
  exit 0
fi
exit 1
