#!/usr/bin/env bash
set -euo pipefail

export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${PATH:-}"

RUNTIME_ENV_FILE="${ARBUZAS_DNS_RUNTIME_ENV_FILE:-/etc/arbuzas/dns/runtime.env}"
if [[ ! -r "${RUNTIME_ENV_FILE}" && -r /etc/pixel-stack/remote-dns/runtime.env ]]; then
  RUNTIME_ENV_FILE="/etc/pixel-stack/remote-dns/runtime.env"
fi

load_runtime_env_defaults() {
  local raw_line line key value
  [[ -r "${RUNTIME_ENV_FILE}" ]] || return 0
  while IFS= read -r raw_line || [[ -n "${raw_line}" ]]; do
    line="${raw_line#"${raw_line%%[![:space:]]*}"}"
    [[ -z "${line}" || "${line}" == \#* || "${line}" != *=* ]] && continue
    key="${line%%=*}"
    key="${key%"${key##*[![:space:]]}"}"
    [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ -n "${!key+x}" ]]; then
      continue
    fi
    value="${line#*=}"
    eval "export ${key}=${value}"
  done < "${RUNTIME_ENV_FILE}"
}

if [[ -r "${RUNTIME_ENV_FILE}" ]]; then
  load_runtime_env_defaults
fi

ARBUZAS_DNS_DIR="${ARBUZAS_DNS_DIR:-/etc/arbuzas/dns}"
TEMPLATE_FILE="${ARBUZAS_DNS_NGINX_TEMPLATE_FILE:-${ARBUZAS_DNS_DIR}/arbuzas-dns-nginx.conf.template}"
OUTPUT_FILE="${ARBUZAS_DNS_NGINX_CONF_FILE:-${ARBUZAS_DNS_DIR}/arbuzas-dns-nginx.conf}"
PID_FILE="${ARBUZAS_DNS_NGINX_PID_FILE:-/run/arbuzas-dns-frontend.pid}"
HOSTNAME="${PIHOLE_REMOTE_HOSTNAME:-dns.example.com}"
HTTPS_PORT="${PIHOLE_REMOTE_HTTPS_PORT:-443}"
DOT_PORT="${PIHOLE_REMOTE_DOT_PORT:-853}"
WEB_HOST="${PIHOLE_WEB_HOST:-127.0.0.1}"
WEB_PORT="${PIHOLE_WEB_PORT:-8080}"
IDENTITY_WEB_HOST="${ADGUARDHOME_DOH_IDENTITY_WEB_HOST:-127.0.0.1}"
IDENTITY_WEB_PORT="${ADGUARDHOME_DOH_IDENTITY_WEB_PORT:-8097}"
IDENTITYCTL="${ADGUARDHOME_DOH_IDENTITYCTL:-/usr/local/bin/adguardhome-doh-identityctl}"
NGINX_BIN="${ARBUZAS_DNS_NGINX_BIN:-$(command -v nginx || true)}"
NGINX_PREFIX="${ARBUZAS_DNS_NGINX_PREFIX:-/usr/share/nginx}"
SKIP_VALIDATE="${ARBUZAS_DNS_SKIP_NGINX_VALIDATE:-0}"
DOT_PROXY_TIMEOUT="${PIHOLE_REMOTE_DOT_PROXY_TIMEOUT_SECONDS:-15}"
DOT_ENABLED="${PIHOLE_REMOTE_DOT_ENABLED:-1}"
DOT_IDENTITY_ENABLED="${PIHOLE_REMOTE_DOT_IDENTITY_ENABLED:-1}"
DOT_BACKEND_HOST="${ADGUARDHOME_REMOTE_DOT_INTERNAL_HOST:-127.0.0.1}"
DOT_BACKEND_PORT="${ADGUARDHOME_REMOTE_DOT_INTERNAL_PORT:-8853}"
TLS_CERT_FILE="${PIHOLE_REMOTE_TLS_CERT_FILE:-${ARBUZAS_DNS_DIR}/tls/fullchain.pem}"
TLS_KEY_FILE="${PIHOLE_REMOTE_TLS_KEY_FILE:-${ARBUZAS_DNS_DIR}/tls/privkey.pem}"
ROUTER_ATTR_ENABLED="${PIHOLE_REMOTE_ROUTER_PUBLIC_IP_ATTRIBUTION_ENABLED:-0}"
ROUTER_LAN_IP="${PIHOLE_REMOTE_ROUTER_LAN_IP:-}"
DDNS_LAST_IPV4_FILE="${PIHOLE_DDNS_LAST_IPV4_FILE:-${ARBUZAS_DNS_DIR}/state/ddns-last-ipv4}"

IDENTITIES_FILE="${ADGUARDHOME_DOH_IDENTITIES_FILE:-${ARBUZAS_DNS_DIR}/doh-identities.json}"
USAGE_EVENTS_FILE="${ADGUARDHOME_DOH_USAGE_EVENTS_FILE:-${ARBUZAS_DNS_DIR}/state/doh-usage-events.jsonl}"
USAGE_CURSOR_FILE="${ADGUARDHOME_DOH_USAGE_CURSOR_FILE:-${ARBUZAS_DNS_DIR}/state/doh-usage-cursor.json}"
OBSERVABILITY_DB_FILE="${ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE:-${ARBUZAS_DNS_DIR}/state/identity-observability.sqlite}"
DOH_ACCESS_LOG_FILE="${ADGUARDHOME_DOH_ACCESS_LOG_FILE:-/var/log/arbuzas/dns/remote-nginx-doh-access.log}"
STATE_DIR="$(dirname "${OBSERVABILITY_DB_FILE}")"
DOH_INGEST_SOCKET_FILE="${ADGUARDHOME_DOH_INGEST_SOCKET_FILE:-${STATE_DIR}/doh-ingest.sock}"
DOT_INGEST_SOCKET_FILE="${ADGUARDHOME_DOT_INGEST_SOCKET_FILE:-${STATE_DIR}/dot-ingest.sock}"

usage() {
  cat <<'EOF'
Usage: arbuzas-dns-frontctl.sh [render|reload]
EOF
}

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

is_valid_ipv4() {
  [[ "${1:-}" =~ ^([0-9]{1,3}[.]){3}[0-9]{1,3}$ ]]
}

resolve_dot_backend_endpoint() {
  local host="${DOT_BACKEND_HOST:-127.0.0.1}"
  local port="${DOT_BACKEND_PORT:-8853}"
  local candidate=""

  if [[ -z "${host}" ]]; then
    echo "dot backend host is empty" >&2
    return 1
  fi

  if [[ "${host}" == "localhost" ]]; then
    host="127.0.0.1"
  fi

  if is_valid_ipv4 "${host}"; then
    printf '%s:%s\n' "${host}" "${port}"
    return 0
  fi

  if command -v getent >/dev/null 2>&1; then
    candidate="$(getent ahostsv4 "${host}" | awk 'NR == 1 { print $1 }')"
    if is_valid_ipv4 "${candidate}"; then
      printf '%s:%s\n' "${candidate}" "${port}"
      return 0
    fi
  fi

  echo "could not resolve DoT backend host to an IPv4 address: ${host}" >&2
  return 1
}

render_token_block() {
  [[ -x "${IDENTITYCTL}" ]] || {
    echo "missing identityctl: ${IDENTITYCTL}" >&2
    return 1
  }

  ADGUARDHOME_DOH_IDENTITIES_FILE="${IDENTITIES_FILE}" \
  ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${USAGE_EVENTS_FILE}" \
  ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${USAGE_CURSOR_FILE}" \
  ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${OBSERVABILITY_DB_FILE}" \
  ADGUARDHOME_DOH_ACCESS_LOG_FILE="${DOH_ACCESS_LOG_FILE}" \
  ADGUARDHOME_DOH_INGEST_SOCKET_FILE="${DOH_INGEST_SOCKET_FILE}" \
  PIHOLE_WEB_HOST="${WEB_HOST}" \
  PIHOLE_WEB_PORT="${WEB_PORT}" \
  "${IDENTITYCTL}" nginx-token-block
}

render_dot_sni_map() {
  local dot_backend_endpoint=""

  [[ -x "${IDENTITYCTL}" ]] || {
    echo "missing identityctl: ${IDENTITYCTL}" >&2
    return 1
  }

  dot_backend_endpoint="$(resolve_dot_backend_endpoint)"

  ADGUARDHOME_DOH_IDENTITIES_FILE="${IDENTITIES_FILE}" \
  PIHOLE_REMOTE_DOT_HOSTNAME="${PIHOLE_REMOTE_DOT_HOSTNAME:-${HOSTNAME}}" \
  ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED="${DOT_IDENTITY_ENABLED}" \
  "${IDENTITYCTL}" nginx-dot-sni-map --backend "${dot_backend_endpoint}"
}

render_doh_client_map_block() {
  local router_public_ip=""
  local block=""

  block="$(cat <<'EOF_MAP'
  map $remote_addr $doh_client_ip {
    default $remote_addr;
  }
EOF_MAP
)"

  if ! is_true "${ROUTER_ATTR_ENABLED}"; then
    printf '%s\n' "${block}"
    return 0
  fi
  if ! is_valid_ipv4 "${ROUTER_LAN_IP}"; then
    printf '%s\n' "${block}"
    return 0
  fi
  if [[ -r "${DDNS_LAST_IPV4_FILE}" ]]; then
    router_public_ip="$(tr -d '\r\n[:space:]' < "${DDNS_LAST_IPV4_FILE}" 2>/dev/null || true)"
  fi
  if ! is_valid_ipv4 "${router_public_ip}"; then
    printf '%s\n' "${block}"
    return 0
  fi

  cat <<EOF_MAP
  map \$remote_addr \$doh_client_ip {
    default \$remote_addr;
    ${ROUTER_LAN_IP} ${router_public_ip};
  }
EOF_MAP
}

validate_config() {
  local file="$1"
  if is_true "${SKIP_VALIDATE}"; then
    return 0
  fi
  [[ -n "${NGINX_BIN}" ]] || {
    echo "nginx is required but was not found in PATH" >&2
    return 1
  }
  "${NGINX_BIN}" -t -c "${file}" -p "${NGINX_PREFIX}" >/dev/null
}

render_config() {
  local bare_block token_block doh_client_ip_map_block tmpfile
  local stream_dot_gate_block="    # DoT identity gate disabled"
  local dot_sni_map_block=""
  local doh_access_log_target="syslog:server=unix:${DOH_INGEST_SOCKET_FILE},facility=local7,tag=arbuzas_doh,severity=info,nohostname"
  local dot_access_log_target="syslog:server=unix:${DOT_INGEST_SOCKET_FILE},facility=local7,tag=arbuzas_dot,severity=info,nohostname"

  [[ -r "${TEMPLATE_FILE}" ]] || {
    echo "missing nginx template: ${TEMPLATE_FILE}" >&2
    return 1
  }
  [[ -r "${TLS_CERT_FILE}" && -r "${TLS_KEY_FILE}" ]] || {
    echo "TLS material missing: ${TLS_CERT_FILE} / ${TLS_KEY_FILE}" >&2
    return 1
  }

  bare_block="$(cat <<EOF_BARE
    location = /dns-query {
      access_log ${doh_access_log_target} arbuzas_doh;
      proxy_pass http://${WEB_HOST}:${WEB_PORT}/dns-query;
      proxy_set_header Host \$host;
      proxy_set_header X-Forwarded-For \$doh_client_ip;
      proxy_set_header X-Real-IP \$doh_client_ip;
      proxy_set_header X-Forwarded-Proto https;
    }
EOF_BARE
)"
  token_block="$(render_token_block)"
  [[ -n "${token_block}" ]] || {
    echo "identity store did not produce any tokenized DoH routes" >&2
    return 1
  }
  doh_client_ip_map_block="$(render_doh_client_map_block)"

  if is_true "${DOT_ENABLED}" && is_true "${DOT_IDENTITY_ENABLED}"; then
    dot_sni_map_block="$(render_dot_sni_map)"
    [[ -n "${dot_sni_map_block}" ]] || {
      echo "identity store did not produce any DoT SNI routes" >&2
      return 1
    }
    stream_dot_gate_block="$(cat <<EOF_STREAM
stream {
  log_format arbuzas_dot '\$time_iso8601\t\$ssl_preread_server_name\t\$status\t\$session_time\t\$remote_addr\t\$msec';
  access_log ${dot_access_log_target} arbuzas_dot;

  map \$ssl_preread_server_name \$dot_backend {
${dot_sni_map_block}
  }

  server {
    listen ${DOT_PORT};
    listen [::]:${DOT_PORT};
    proxy_connect_timeout 1s;
    proxy_timeout ${DOT_PROXY_TIMEOUT}s;
    ssl_preread on;
    proxy_pass \$dot_backend;
  }
}
EOF_STREAM
)"
  fi

  tmpfile="$(mktemp)"
  HTTPS_PORT_RENDER="${HTTPS_PORT}" \
  HOSTNAME_RENDER="${HOSTNAME}" \
  WEB_HOST_RENDER="${WEB_HOST}" \
  WEB_PORT_RENDER="${WEB_PORT}" \
  IDENTITY_WEB_HOST_RENDER="${IDENTITY_WEB_HOST}" \
  IDENTITY_WEB_PORT_RENDER="${IDENTITY_WEB_PORT}" \
  TLS_CERT_FILE_RENDER="${TLS_CERT_FILE}" \
  TLS_KEY_FILE_RENDER="${TLS_KEY_FILE}" \
  STREAM_DOT_GATE_BLOCK_RENDER="${stream_dot_gate_block}" \
  DOH_CLIENT_IP_MAP_BLOCK_RENDER="${doh_client_ip_map_block}" \
  DOH_BARE_BLOCK_RENDER="${bare_block}" \
  DOH_TOKEN_BLOCK_RENDER="${token_block}" \
  python3 - "${TEMPLATE_FILE}" "${tmpfile}" <<'PY'
from pathlib import Path
import os
import sys

template_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
rendered = template_path.read_text(encoding="utf-8")

replacements = {
    "__HTTPS_PORT__": os.environ["HTTPS_PORT_RENDER"],
    "__HOSTNAME__": os.environ["HOSTNAME_RENDER"],
    "__WEB_HOST__": os.environ["WEB_HOST_RENDER"],
    "__WEB_PORT__": os.environ["WEB_PORT_RENDER"],
    "__IDENTITY_WEB_HOST__": os.environ["IDENTITY_WEB_HOST_RENDER"],
    "__IDENTITY_WEB_PORT__": os.environ["IDENTITY_WEB_PORT_RENDER"],
    "__TLS_CERT_FILE__": os.environ["TLS_CERT_FILE_RENDER"],
    "__TLS_KEY_FILE__": os.environ["TLS_KEY_FILE_RENDER"],
    "__STREAM_DOT_GATE_BLOCK__": os.environ["STREAM_DOT_GATE_BLOCK_RENDER"],
    "__DOH_CLIENT_IP_MAP_BLOCK__": os.environ["DOH_CLIENT_IP_MAP_BLOCK_RENDER"],
    "__DOH_BARE_BLOCK__": os.environ["DOH_BARE_BLOCK_RENDER"],
    "__DOH_TOKEN_BLOCK__": os.environ["DOH_TOKEN_BLOCK_RENDER"],
}

for placeholder, value in replacements.items():
    rendered = rendered.replace(placeholder, value)

output_path.write_text(rendered, encoding="utf-8")
PY

  validate_config "${tmpfile}"
  install -m 0644 "${tmpfile}" "${OUTPUT_FILE}"
  rm -f "${tmpfile}"
}

reload_frontend() {
  local pid=""
  render_config
  [[ -f "${PID_FILE}" ]] || {
    echo "frontend pid file missing: ${PID_FILE}" >&2
    return 1
  }
  pid="$(tr -d '\r\n[:space:]' < "${PID_FILE}" 2>/dev/null || true)"
  [[ "${pid}" =~ ^[0-9]+$ ]] || {
    echo "frontend pid file is invalid: ${PID_FILE}" >&2
    return 1
  }
  kill -0 "${pid}" >/dev/null 2>&1 || {
    echo "frontend is not running (pid ${pid})" >&2
    return 1
  }
  "${NGINX_BIN}" -c "${OUTPUT_FILE}" -p "${NGINX_PREFIX}" -s reload >/dev/null
}

mode="${1:-render}"
case "${mode}" in
  render|--render)
    render_config
    ;;
  reload|--reload)
    reload_frontend
    ;;
  -h|--help)
    usage
    ;;
  *)
    echo "unknown mode: ${mode}" >&2
    usage >&2
    exit 2
    ;;
esac
