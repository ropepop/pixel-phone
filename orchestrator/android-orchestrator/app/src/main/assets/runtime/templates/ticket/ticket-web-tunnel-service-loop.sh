#!/system/bin/sh
set -eu

BASE="${TICKET_SCREEN_ROOT:-/data/local/pixel-stack/apps/ticket-screen}"
ENV_FILE="${BASE}/env/ticket-screen.env"
BIN_DIR="${BASE}/bin"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
STATE_DIR="${BASE}/state/ticket-web-tunnel"
LOOP_PID_FILE="${RUN_DIR}/ticket-web-tunnel-service-loop.pid"
CLOUDFLARED_PID_FILE="${RUN_DIR}/ticket-screen-cloudflared.pid"
CLOUDFLARED_LOG_FILE="${LOG_DIR}/ticket-screen-cloudflared.log"
CLOUDFLARED_CONFIG_FILE="${STATE_DIR}/ticket-screen-cloudflared.yml"
CLOUDFLARED_CHROOT_DIR="${BASE}/chroot"
CLOUDFLARED_CHROOT_CONFIG_FILE="/state/ticket-screen-cloudflared.yml"
CLOUDFLARED_CHROOT_CREDENTIALS_FILE="/conf/ticket-screen-cloudflared.json"

mkdir -p "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${STATE_DIR}"

if [ -r "${ENV_FILE}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${ENV_FILE}"
  set +a
fi

: "${TICKET_SCREEN_PUBLIC_BASE_URL:=https://ticket.jolkins.id.lv}"
: "${TICKET_SCREEN_PORT:=9388}"
: "${TICKET_SCREEN_TUNNEL_ENABLED:=1}"
: "${TICKET_SCREEN_TUNNEL_CREDENTIALS_FILE:=/data/local/pixel-stack/conf/apps/ticket-screen-cloudflared.json}"
: "${TICKET_SCREEN_TUNNEL_METRICS_ADDR:=127.0.0.1:20388}"
: "${TICKET_SCREEN_TUNNEL_DNS_RESOLVER_ADDRS:=1.1.1.1:53,1.0.0.1:53}"
: "${TICKET_SCREEN_TUNNEL_CHROOT_ENABLED:=1}"
: "${TICKET_SCREEN_TUNNEL_POLL_SEC:=15}"

ts() { date '+%Y-%m-%dT%H:%M:%S%z'; }
log() { printf '[%s] %s\n' "$(ts)" "$*" >> "${LOG_DIR}/ticket-web-tunnel-service-loop.log"; }

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

hostname_from_url() {
  printf '%s\n' "${TICKET_SCREEN_PUBLIC_BASE_URL}" | sed -n 's#^[A-Za-z][A-Za-z0-9+.-]*://\([^/:?#]*\).*$#\1#p'
}

tunnel_id() {
  [ -r "${TICKET_SCREEN_TUNNEL_CREDENTIALS_FILE}" ] || return 1
  tr -d '\n\r' < "${TICKET_SCREEN_TUNNEL_CREDENTIALS_FILE}" 2>/dev/null |
    sed -n 's/.*"TunnelID"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

resolve_cloudflared() {
  for candidate in \
    "${BIN_DIR}/cloudflared" \
    "/data/local/pixel-stack/apps/subscription-bot/bin/cloudflared" \
    "/data/local/pixel-stack/apps/satiksme-bot/bin/cloudflared" \
    "/data/local/pixel-stack/apps/train-bot/bin/cloudflared" \
    "/data/local/pixel-stack/bin/cloudflared" \
    "/usr/local/bin/cloudflared"; do
    if [ -x "${candidate}" ]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  if command -v cloudflared >/dev/null 2>&1; then
    command -v cloudflared
    return 0
  fi
  return 1
}

render_config() {
  host="$(hostname_from_url)"
  id="$(tunnel_id || true)"
  credentials_path="${TICKET_SCREEN_TUNNEL_CREDENTIALS_FILE}"
  config_path="${CLOUDFLARED_CONFIG_FILE}"
  if is_true "${TICKET_SCREEN_TUNNEL_CHROOT_ENABLED}"; then
    mkdir -p "${CLOUDFLARED_CHROOT_DIR}/state"
    credentials_path="${CLOUDFLARED_CHROOT_CREDENTIALS_FILE}"
    config_path="${CLOUDFLARED_CHROOT_DIR}${CLOUDFLARED_CHROOT_CONFIG_FILE}"
  fi
  if [ -z "${host}" ]; then
    log "missing host in TICKET_SCREEN_PUBLIC_BASE_URL=${TICKET_SCREEN_PUBLIC_BASE_URL}"
    return 1
  fi
  if [ -z "${id}" ]; then
    log "missing TunnelID in ${TICKET_SCREEN_TUNNEL_CREDENTIALS_FILE}"
    return 1
  fi
  tmp="$(mktemp)"
  cat > "${tmp}" <<EOF_CLOUDFLARED
tunnel: ${id}
credentials-file: ${credentials_path}
ingress:
  - hostname: ${host}
    service: http://127.0.0.1:${TICKET_SCREEN_PORT}
  - service: http_status:404
EOF_CLOUDFLARED
  mv "${tmp}" "${config_path}"
  chmod 600 "${config_path}" >/dev/null 2>&1 || true
}

read_pid() {
  if [ -r "${1}" ]; then
    sed -n '1p' "${1}" 2>/dev/null | tr -d '\r'
  fi
}

pid_matches() {
  pid="$1"
  needle="$2"
  [ -n "${pid}" ] || return 1
  kill -0 "${pid}" >/dev/null 2>&1 || return 1
  timeout 1 tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null | grep -F "${needle}" >/dev/null
}

cloudflared_cmd_needle() {
  if is_true "${TICKET_SCREEN_TUNNEL_CHROOT_ENABLED}"; then
    printf '%s\n' "${CLOUDFLARED_CHROOT_CONFIG_FILE}"
  else
    printf '%s\n' "${CLOUDFLARED_CONFIG_FILE}"
  fi
}

stop_tunnel() {
  pid="$(read_pid "${CLOUDFLARED_PID_FILE}" || true)"
  if pid_matches "${pid}" "$(cloudflared_cmd_needle)"; then
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  fi
  rm -f "${CLOUDFLARED_PID_FILE}" >/dev/null 2>&1 || true
}

tunnel_running() {
  pid="$(read_pid "${CLOUDFLARED_PID_FILE}" || true)"
  pid_matches "${pid}" "$(cloudflared_cmd_needle)"
}

prepare_chroot() {
  cloudflared_bin="$1"
  chroot_cloudflared="${CLOUDFLARED_CHROOT_DIR}/bin/cloudflared"
  mkdir -p \
    "${CLOUDFLARED_CHROOT_DIR}/bin" \
    "${CLOUDFLARED_CHROOT_DIR}/conf" \
    "${CLOUDFLARED_CHROOT_DIR}/etc" \
    "${CLOUDFLARED_CHROOT_DIR}/state" \
    "${CLOUDFLARED_CHROOT_DIR}/tmp"
  if [ ! -x "${chroot_cloudflared}" ] || ! cmp -s "${cloudflared_bin}" "${chroot_cloudflared}" 2>/dev/null; then
    tmp_cloudflared="${chroot_cloudflared}.$$"
    cp "${cloudflared_bin}" "${tmp_cloudflared}"
    chmod 0755 "${tmp_cloudflared}" >/dev/null 2>&1 || true
    mv "${tmp_cloudflared}" "${chroot_cloudflared}"
  fi
  cp "${TICKET_SCREEN_TUNNEL_CREDENTIALS_FILE}" "${CLOUDFLARED_CHROOT_DIR}${CLOUDFLARED_CHROOT_CREDENTIALS_FILE}"
  chmod 0755 "${chroot_cloudflared}" >/dev/null 2>&1 || true
  chmod 0600 "${CLOUDFLARED_CHROOT_DIR}${CLOUDFLARED_CHROOT_CREDENTIALS_FILE}" >/dev/null 2>&1 || true

  resolv_tmp="$(mktemp)"
  old_ifs="${IFS}"
  IFS=", "
  for resolver_addr in ${TICKET_SCREEN_TUNNEL_DNS_RESOLVER_ADDRS}; do
    resolver_host="${resolver_addr%:*}"
    if [ -n "${resolver_host}" ]; then
      printf 'nameserver %s\n' "${resolver_host}" >> "${resolv_tmp}"
    fi
  done
  IFS="${old_ifs}"
  if [ ! -s "${resolv_tmp}" ]; then
    printf 'nameserver 1.1.1.1\n' > "${resolv_tmp}"
  fi
  mv "${resolv_tmp}" "${CLOUDFLARED_CHROOT_DIR}/etc/resolv.conf"
  chmod 0644 "${CLOUDFLARED_CHROOT_DIR}/etc/resolv.conf" >/dev/null 2>&1 || true
}

start_tunnel() {
  cloudflared_bin="$(resolve_cloudflared)" || {
    log "cloudflared binary is not installed on the Pixel"
    return 1
  }
  if is_true "${TICKET_SCREEN_TUNNEL_CHROOT_ENABLED}"; then
    prepare_chroot "${cloudflared_bin}"
  fi
  render_config || return 1
  if tunnel_running; then
    return 0
  fi
  stop_tunnel
  log "starting cloudflared for ticket host=$(hostname_from_url)"
  if is_true "${TICKET_SCREEN_TUNNEL_CHROOT_ENABLED}"; then
    set -- chroot "${CLOUDFLARED_CHROOT_DIR}" /bin/cloudflared tunnel \
      --config "${CLOUDFLARED_CHROOT_CONFIG_FILE}" \
      --metrics "${TICKET_SCREEN_TUNNEL_METRICS_ADDR}" \
      --no-autoupdate \
      run
  else
    set -- "${cloudflared_bin}" tunnel \
      --config "${CLOUDFLARED_CONFIG_FILE}" \
      --metrics "${TICKET_SCREEN_TUNNEL_METRICS_ADDR}" \
      --no-autoupdate \
      run
  fi
  old_ifs="${IFS}"
  IFS=", "
  for resolver_addr in ${TICKET_SCREEN_TUNNEL_DNS_RESOLVER_ADDRS}; do
    if [ -n "${resolver_addr}" ]; then
      set -- "$@" --dns-resolver-addrs "${resolver_addr}"
    fi
  done
  IFS="${old_ifs}"
  nohup "$@" >> "${CLOUDFLARED_LOG_FILE}" 2>&1 &
  echo "$!" > "${CLOUDFLARED_PID_FILE}"
  sleep 2
  tunnel_running
}

echo "$$" > "${LOOP_PID_FILE}"
trap 'stop_tunnel >/dev/null 2>&1 || true; rm -f "${LOOP_PID_FILE}" >/dev/null 2>&1 || true' EXIT HUP INT TERM

while true; do
  if ! is_true "${TICKET_SCREEN_TUNNEL_ENABLED}"; then
    stop_tunnel
    sleep "${TICKET_SCREEN_TUNNEL_POLL_SEC}"
    continue
  fi
  if ! tunnel_running; then
    start_tunnel || true
  fi
  sleep "${TICKET_SCREEN_TUNNEL_POLL_SEC}"
done
