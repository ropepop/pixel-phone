#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/vpn"
CONF_SRC="/data/local/pixel-stack/conf/vpn/tailscale.env"
TPL_DIR="/data/local/pixel-stack/templates/vpn"
BIN_DIR="${BASE}/bin"
CONF_DIR="${BASE}/conf"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
PID_FILE="${RUN_DIR}/pixel-vpn-service-loop.pid"
LOCK_DIR="${RUN_DIR}/pixel-vpn-service-loop.lock"
TAILSCALED_PID_FILE="${RUN_DIR}/tailscaled.pid"
TAILSCALED_SOCK="${RUN_DIR}/tailscaled.sock"
LOOP_BIN="${BIN_DIR}/pixel-vpn-service-loop"
LAUNCH_BIN="${BIN_DIR}/pixel-vpn-launch"
TPL_LAUNCH="${TPL_DIR}/pixel-vpn-launch.sh"
TPL_LOOP="${TPL_DIR}/pixel-vpn-service-loop.sh"
HEALTH_BIN="/data/local/pixel-stack/bin/pixel-vpn-health.sh"
STOP_BIN="/data/local/pixel-stack/bin/pixel-vpn-stop.sh"

mkdir -p "${BIN_DIR}" "${CONF_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${BASE}/state"

if [ ! -f "${CONF_SRC}" ]; then
  echo "missing vpn config source: ${CONF_SRC}" >&2
  exit 1
fi
cp "${CONF_SRC}" "${CONF_DIR}/tailscale.env"
chmod 0600 "${CONF_DIR}/tailscale.env" >/dev/null 2>&1 || true

if [ ! -f "${TPL_LAUNCH}" ]; then
  echo "missing vpn launch template: ${TPL_LAUNCH}" >&2
  exit 1
fi
if [ ! -f "${TPL_LOOP}" ]; then
  echo "missing vpn loop template: ${TPL_LOOP}" >&2
  exit 1
fi

cp "${TPL_LAUNCH}" "${LAUNCH_BIN}"
chmod 0755 "${LAUNCH_BIN}"
cp "${TPL_LOOP}" "${LOOP_BIN}"
chmod 0755 "${LOOP_BIN}"

if [ ! -x "${BASE}/bin/tailscaled" ]; then
  echo "missing tailscaled binary: ${BASE}/bin/tailscaled" >&2
  exit 1
fi
if [ ! -x "${BASE}/bin/tailscale" ]; then
  echo "missing tailscale binary: ${BASE}/bin/tailscale" >&2
  exit 1
fi

clear_stale_state() {
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  rm -f "${TAILSCALED_PID_FILE}" >/dev/null 2>&1 || true
  rm -f "${TAILSCALED_SOCK}" >/dev/null 2>&1 || true
  rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
}

if [ -f "${PID_FILE}" ]; then
  old_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [ -n "${old_pid}" ] && kill -0 "${old_pid}" >/dev/null 2>&1; then
    if sh "${HEALTH_BIN}" >/dev/null 2>&1; then
      exit 0
    fi
    sh "${STOP_BIN}" >/dev/null 2>&1 || true
  fi
fi
clear_stale_state

nohup env PIXEL_VPN_ROOT="${BASE}" "${LOOP_BIN}" >>"${LOG_DIR}/pixel-vpn-service-loop.log" 2>&1 &
pid="$!"
if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
  echo "${pid}" > "${PID_FILE}"
else
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  exit 1
fi

exit 0
