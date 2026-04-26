#!/system/bin/sh
set -eu

PIXEL_VPN_ROOT="${PIXEL_VPN_ROOT:-/data/local/pixel-stack/vpn}"
CONF_FILE="${PIXEL_VPN_ROOT}/conf/tailscale.env"
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
: "${VPN_RUNTIME_ROOT:=${PIXEL_VPN_ROOT}}"
: "${VPN_INTERFACE_NAME:=tailscale0}"
: "${VPN_AUTH_KEY_FILE:=/data/local/pixel-stack/conf/vpn/tailscale-authkey}"
: "${VPN_HOSTNAME:=}"
: "${VPN_ADVERTISE_TAGS:=}"
: "${VPN_ACCEPT_ROUTES:=0}"
: "${VPN_ACCEPT_DNS:=0}"

if [ "${VPN_ENABLED}" != "1" ]; then
  exit 0
fi

TAILSCALED_BIN="${VPN_RUNTIME_ROOT}/bin/tailscaled"
TAILSCALE_BIN="${VPN_RUNTIME_ROOT}/bin/tailscale"
RUN_DIR="${VPN_RUNTIME_ROOT}/run"
LOG_DIR="${VPN_RUNTIME_ROOT}/logs"
STATE_DIR="${VPN_RUNTIME_ROOT}/state"
TAILSCALED_PID_FILE="${RUN_DIR}/tailscaled.pid"
TAILSCALED_SOCK="${RUN_DIR}/tailscaled.sock"
TAILSCALED_STATE="${STATE_DIR}/tailscaled.state"
TAILNET_IPV4_FILE="${RUN_DIR}/tailnet-ipv4"
SSH_CONF_FILE="/data/local/pixel-stack/ssh/conf/dropbear.env"
SSH_PORT=2222

mkdir -p "${RUN_DIR}" "${LOG_DIR}" "${STATE_DIR}"

[ -x "${TAILSCALED_BIN}" ] || {
  echo "missing tailscaled binary: ${TAILSCALED_BIN}" >&2
  exit 1
}
[ -x "${TAILSCALE_BIN}" ] || {
  echo "missing tailscale binary: ${TAILSCALE_BIN}" >&2
  exit 1
}
[ -f "${VPN_AUTH_KEY_FILE}" ] || {
  echo "missing tailscale auth key file: ${VPN_AUTH_KEY_FILE}" >&2
  exit 1
}

if [ -r "${SSH_CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${SSH_CONF_FILE}"
  set +a
fi
: "${SSH_PORT:=2222}"

bool_flag() {
  value="$1"
  if [ "${value}" = "1" ] || [ "${value}" = "true" ]; then
    echo "true"
  else
    echo "false"
  fi
}

apply_ssh_guard_chain() {
  ipt="$1"
  chain="$2"
  if ! command -v "${ipt}" >/dev/null 2>&1; then
    return 0
  fi

  "${ipt}" -N "${chain}" >/dev/null 2>&1 || true
  "${ipt}" -F "${chain}" >/dev/null 2>&1 || true
  "${ipt}" -A "${chain}" -i "${VPN_INTERFACE_NAME}" -p tcp --dport "${SSH_PORT}" -j ACCEPT >/dev/null 2>&1 || true
  "${ipt}" -A "${chain}" -p tcp --dport "${SSH_PORT}" -j DROP >/dev/null 2>&1 || true
  "${ipt}" -C INPUT -p tcp --dport "${SSH_PORT}" -j "${chain}" >/dev/null 2>&1 ||
    "${ipt}" -I INPUT 1 -p tcp --dport "${SSH_PORT}" -j "${chain}" >/dev/null 2>&1 || true
}

rm -f "${TAILSCALED_SOCK}" >/dev/null 2>&1 || true

"${TAILSCALED_BIN}" --state="${TAILSCALED_STATE}" --socket="${TAILSCALED_SOCK}" --tun="${VPN_INTERFACE_NAME}" >> "${LOG_DIR}/tailscaled.log" 2>&1 &
child_pid="$!"
echo "${child_pid}" > "${TAILSCALED_PID_FILE}"

ready=0
for _ in $(seq 1 20); do
  if [ -S "${TAILSCALED_SOCK}" ]; then
    ready=1
    break
  fi
  if ! kill -0 "${child_pid}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if [ "${ready}" != "1" ]; then
  kill "${child_pid}" >/dev/null 2>&1 || true
  wait "${child_pid}" >/dev/null 2>&1 || true
  rm -f "${TAILSCALED_PID_FILE}" >/dev/null 2>&1 || true
  echo "tailscaled socket did not become ready" >&2
  exit 1
fi

set -- "${TAILSCALE_BIN}" \
  --socket "${TAILSCALED_SOCK}" \
  up \
  --ssh=false \
  "--auth-key=file:${VPN_AUTH_KEY_FILE}" \
  "--accept-routes=$(bool_flag "${VPN_ACCEPT_ROUTES}")" \
  "--accept-dns=$(bool_flag "${VPN_ACCEPT_DNS}")"

if [ -n "${VPN_HOSTNAME}" ]; then
  set -- "$@" "--hostname=${VPN_HOSTNAME}"
fi
if [ -n "${VPN_ADVERTISE_TAGS}" ]; then
  set -- "$@" "--advertise-tags=${VPN_ADVERTISE_TAGS}"
fi

if ! "$@" >> "${LOG_DIR}/tailscaled.log" 2>&1; then
  kill "${child_pid}" >/dev/null 2>&1 || true
  wait "${child_pid}" >/dev/null 2>&1 || true
  rm -f "${TAILSCALED_PID_FILE}" >/dev/null 2>&1 || true
  exit 1
fi

tailnet_ipv4="$("${TAILSCALE_BIN}" --socket "${TAILSCALED_SOCK}" ip -4 2>/dev/null | sed -n '1p' || true)"
if [ -z "${tailnet_ipv4}" ]; then
  tailnet_ipv4="$(ip -4 addr show dev "${VPN_INTERFACE_NAME}" 2>/dev/null | awk '/inet / {sub(/\/.*/, "", $2); print $2; exit}')"
fi
printf '%s\n' "${tailnet_ipv4}" > "${TAILNET_IPV4_FILE}"

apply_ssh_guard_chain iptables PIXEL_SSH_GUARD
apply_ssh_guard_chain ip6tables PIXEL_SSH_GUARD6

set +e
wait "${child_pid}"
child_rc=$?
set -e
rm -f "${TAILNET_IPV4_FILE}" >/dev/null 2>&1 || true
rm -f "${TAILSCALED_PID_FILE}" >/dev/null 2>&1 || true
exit "${child_rc}"
