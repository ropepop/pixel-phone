#!/system/bin/sh
set +e

BASE="/data/local/pixel-stack"
CONF_FILE="${BASE}/conf/adguardhome.env"
RUN_DIR="${BASE}/run"
PID_FILE="${RUN_DIR}/adguardhome-service-loop.pid"
ROOTFS="/data/local/pixel-stack/chroots/adguardhome"
LEGACY_ROOTFS="/data/local/pixel-stack/chroots/pihole"

if [ -r "${CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${CONF_FILE}"
  set +a
fi

kill_matching() {
  pattern="$1"
  pids=""

  if command -v pkill >/dev/null 2>&1; then
    pkill -f "${pattern}" >/dev/null 2>&1 || true
  fi

  if command -v pgrep >/dev/null 2>&1; then
    pids="$(pgrep -f "${pattern}" 2>/dev/null || true)"
  elif command -v ps >/dev/null 2>&1; then
    pids="$(ps -A -o PID,ARGS 2>/dev/null | grep -F "${pattern}" | grep -v -F "grep -F ${pattern}" | awk '{print $1}')"
  fi

  for pid in ${pids}; do
    [ -n "${pid}" ] || continue
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  done
}

if [ -f "${PID_FILE}" ]; then
  pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  fi
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
fi

if [ -x "${ROOTFS}/usr/local/bin/adguardhome-stop" ]; then
  chroot "${ROOTFS}" /usr/local/bin/adguardhome-stop >/dev/null 2>&1 || true
fi
if [ -x "${LEGACY_ROOTFS}/usr/local/bin/pihole-stop" ]; then
  chroot "${LEGACY_ROOTFS}" /usr/local/bin/pihole-stop >/dev/null 2>&1 || true
fi

kill_matching '/data/local/pixel-stack/bin/adguardhome-service-loop'
kill_matching 'adguardhome-service-loop'
kill_matching 'AdGuardHome'
kill_matching 'adguardhome-remote-watchdog'
kill_matching 'pixel-stack-adguardhome-remote-nginx.conf'
kill_matching 'pihole-service-loop'
kill_matching 'pihole-FTL'
kill_matching 'dnscrypt-proxy'
kill_matching 'pihole-doh-gateway'
kill_matching 'pihole-remote-auth-gateway'
kill_matching 'pihole-remote-watchdog'
kill_matching 'nginx.*pixel-stack-pihole-remote'

rm -f "${RUN_DIR}/pihole-service-loop.pid" "${RUN_DIR}/pihole-rooted-host.pid" >/dev/null 2>&1 || true
rmdir "${RUN_DIR}/pihole-rooted-service-loop.lock" >/dev/null 2>&1 || rm -rf "${RUN_DIR}/pihole-rooted-service-loop.lock" >/dev/null 2>&1 || true
rmdir "${RUN_DIR}/pihole-service-loop.lock" >/dev/null 2>&1 || rm -rf "${RUN_DIR}/pihole-service-loop.lock" >/dev/null 2>&1 || true

exit 0
