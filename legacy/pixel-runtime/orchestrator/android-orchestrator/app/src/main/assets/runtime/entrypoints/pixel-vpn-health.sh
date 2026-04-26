#!/system/bin/sh
set -eu

REPORT_MODE=0
if [ "${1:-}" = "--report" ] || [ "${PIXEL_VPN_HEALTH_REPORT:-0}" = "1" ]; then
  REPORT_MODE=1
fi

BASE="${PIXEL_VPN_ROOT:-/data/local/pixel-stack/vpn}"
CONF_FILE="${BASE}/conf/tailscale.env"
if [ ! -r "${CONF_FILE}" ]; then
  CONF_FILE="/data/local/pixel-stack/conf/vpn/tailscale.env"
fi

if [ -r "${CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${CONF_FILE}"
  set +a
fi

: "${VPN_ENABLED:=0}"
: "${VPN_RUNTIME_ROOT:=${BASE}}"
: "${VPN_INTERFACE_NAME:=tailscale0}"

TAILSCALED_BIN="${VPN_RUNTIME_ROOT}/bin/tailscaled"
TAILSCALE_BIN="${VPN_RUNTIME_ROOT}/bin/tailscale"
RUN_DIR="${VPN_RUNTIME_ROOT}/run"
TAILSCALED_PID_FILE="${RUN_DIR}/tailscaled.pid"
TAILSCALED_SOCK="${RUN_DIR}/tailscaled.sock"
TAILNET_IPV4_FILE="${RUN_DIR}/tailnet-ipv4"
SSH_CONF_FILE="/data/local/pixel-stack/ssh/conf/dropbear.env"
SSH_PORT=2222
IPTABLES_BIN="/system/bin/iptables"
IP6TABLES_BIN="/system/bin/ip6tables"

if [ -r "${SSH_CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${SSH_CONF_FILE}"
  set +a
fi
: "${SSH_PORT:=2222}"

emit() {
  if [ "${REPORT_MODE}" = "1" ]; then
    printf '%s=%s\n' "$1" "$2"
  fi
}

verify_ssh_guard_chain() {
  ipt="$1"
  chain="$2"
  [ -x "${ipt}" ]
  "${ipt}" -C INPUT -p tcp --dport "${SSH_PORT}" -j "${chain}" >/dev/null 2>&1
  "${ipt}" -C "${chain}" -i "${VPN_INTERFACE_NAME}" -p tcp --dport "${SSH_PORT}" -j ACCEPT >/dev/null 2>&1
  "${ipt}" -C "${chain}" -p tcp --dport "${SSH_PORT}" -j DROP >/dev/null 2>&1
}

tailscaled_live="0"
tailscaled_sock="0"
tailnet_ipv4=""
guard_chain_ipv4="0"
guard_chain_ipv6="0"

if [ -f "${TAILSCALED_PID_FILE}" ]; then
  pid="$(cat "${TAILSCALED_PID_FILE}" 2>/dev/null || true)"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    tailscaled_live="1"
  fi
fi

if [ -S "${TAILSCALED_SOCK}" ]; then
  tailscaled_sock="1"
fi

if [ -f "${TAILNET_IPV4_FILE}" ]; then
  tailnet_ipv4="$(sed -n '1p' "${TAILNET_IPV4_FILE}" 2>/dev/null | tr -d '\r' || true)"
fi
if [ -z "${tailnet_ipv4}" ] && [ -x "${TAILSCALE_BIN}" ] && [ -S "${TAILSCALED_SOCK}" ]; then
  tailnet_ipv4="$("${TAILSCALE_BIN}" --socket "${TAILSCALED_SOCK}" ip -4 2>/dev/null | sed -n '1p' || true)"
fi
if [ -z "${tailnet_ipv4}" ]; then
  tailnet_ipv4="$(ip -4 addr show dev "${VPN_INTERFACE_NAME}" 2>/dev/null | awk '/inet / {sub(/\/.*/, "", $2); print $2; exit}')"
fi

if verify_ssh_guard_chain "${IPTABLES_BIN}" PIXEL_SSH_GUARD; then
  guard_chain_ipv4="1"
fi
if verify_ssh_guard_chain "${IP6TABLES_BIN}" PIXEL_SSH_GUARD6; then
  guard_chain_ipv6="1"
fi

emit "vpn_enabled" "${VPN_ENABLED}"
emit "tailscaled_live" "${tailscaled_live}"
emit "tailscaled_sock" "${tailscaled_sock}"
emit "tailnet_ipv4" "${tailnet_ipv4}"
emit "guard_chain_ipv4" "${guard_chain_ipv4}"
emit "guard_chain_ipv6" "${guard_chain_ipv6}"

if [ "${VPN_ENABLED}" != "1" ]; then
  exit 0
fi

[ -x "${TAILSCALED_BIN}" ]
[ -x "${TAILSCALE_BIN}" ]
[ "${tailscaled_live}" = "1" ]
[ "${tailscaled_sock}" = "1" ]
[ -n "${tailnet_ipv4}" ]
[ "${guard_chain_ipv4}" = "1" ]
[ "${guard_chain_ipv6}" = "1" ]

exit 0
