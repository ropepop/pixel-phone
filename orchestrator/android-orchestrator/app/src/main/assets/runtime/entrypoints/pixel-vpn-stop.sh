#!/system/bin/sh
set +e

BASE="/data/local/pixel-stack/vpn"
RUN_DIR="${BASE}/run"
PID_FILE="${RUN_DIR}/pixel-vpn-service-loop.pid"
LOCK_DIR="${RUN_DIR}/pixel-vpn-service-loop.lock"
TAILSCALED_PID_FILE="${RUN_DIR}/tailscaled.pid"
TAILSCALED_SOCK="${RUN_DIR}/tailscaled.sock"
TAILNET_IPV4_FILE="${RUN_DIR}/tailnet-ipv4"

if [ -f "${PID_FILE}" ]; then
  pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  fi
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
fi

if [ -f "${TAILSCALED_PID_FILE}" ]; then
  pid="$(cat "${TAILSCALED_PID_FILE}" 2>/dev/null || true)"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  fi
  rm -f "${TAILSCALED_PID_FILE}" >/dev/null 2>&1 || true
fi

rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
rm -f "${TAILSCALED_SOCK}" >/dev/null 2>&1 || true
rm -f "${TAILNET_IPV4_FILE}" >/dev/null 2>&1 || true

pkill -f 'pixel-vpn-service-loop' >/dev/null 2>&1 || true
pkill -f '/data/local/pixel-stack/vpn/bin/tailscaled' >/dev/null 2>&1 || true

exit 0
