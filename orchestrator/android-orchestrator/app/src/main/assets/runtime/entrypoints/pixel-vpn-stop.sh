#!/system/bin/sh
set +e

BASE="/data/local/pixel-stack/vpn"
RUN_DIR="${BASE}/run"
PID_FILE="${RUN_DIR}/pixel-vpn-service-loop.pid"
LOCK_DIR="${RUN_DIR}/pixel-vpn-service-loop.lock"
TAILSCALED_PID_FILE="${RUN_DIR}/tailscaled.pid"
TAILSCALED_SOCK="${RUN_DIR}/tailscaled.sock"
TAILNET_IPV4_FILE="${RUN_DIR}/tailnet-ipv4"

owned_pid() {
  pid="$1"
  expected="$2"
  case "${pid}" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "${pid}" >/dev/null 2>&1 || return 1
  if [ -r "/proc/${pid}/cmdline" ]; then
    cmdline="$(tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true)"
  else
    cmdline="$(ps -p "${pid}" -o command= 2>/dev/null || true)"
  fi
  case "${cmdline}" in *"${expected}"*) return 0 ;; *) return 1 ;; esac
}

loop_pid="$(sed -n '1p' "${PID_FILE}" 2>/dev/null | tr -d '\r' || true)"
tailscaled_pid="$(sed -n '1p' "${TAILSCALED_PID_FILE}" 2>/dev/null | tr -d '\r' || true)"
pids=""
if owned_pid "${loop_pid}" "${BASE}/bin/pixel-vpn-service-loop"; then pids="${pids} ${loop_pid}"; fi
if owned_pid "${tailscaled_pid}" "${BASE}/bin/tailscaled"; then pids="${pids} ${tailscaled_pid}"; fi

for pid in ${pids}; do kill "${pid}" >/dev/null 2>&1 || true; done
attempt=0
while [ "${attempt}" -lt 20 ]; do
  alive=0
  for pid in ${pids}; do kill -0 "${pid}" >/dev/null 2>&1 && alive=1; done
  [ "${alive}" = "0" ] && break
  attempt=$((attempt + 1))
  sleep 0.1
done
for pid in ${pids}; do kill -0 "${pid}" >/dev/null 2>&1 && kill -9 "${pid}" >/dev/null 2>&1 || true; done

rm -f "${PID_FILE}" "${TAILSCALED_PID_FILE}" >/dev/null 2>&1 || true

rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
rm -f "${TAILSCALED_SOCK}" >/dev/null 2>&1 || true
rm -f "${TAILNET_IPV4_FILE}" >/dev/null 2>&1 || true

pkill -f "${BASE}/bin/pixel-vpn-service-loop" >/dev/null 2>&1 || true
pkill -f "${BASE}/bin/tailscaled" >/dev/null 2>&1 || true

exit 0
