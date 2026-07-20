#!/system/bin/sh
set +e

BASE_LOCAL="/data/local/pixel-stack/ssh"
BASE_LEGACY="/data/adb/pixel-stack/ssh"

owned_pid() {
  pid="$1"
  base="$2"
  case "${pid}" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "${pid}" >/dev/null 2>&1 || return 1
  if [ -r "/proc/${pid}/cmdline" ]; then
    cmdline="$(tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true)"
  else
    cmdline="$(ps -p "${pid}" -o command= 2>/dev/null || true)"
  fi
  case "${cmdline}" in *"${base}/"*) return 0 ;; *) return 1 ;; esac
}

pids=""
for base in "${BASE_LOCAL}" "${BASE_LEGACY}"; do
  pid_file="${base}/run/pixel-ssh-service-loop.pid"
  lock_dir="${base}/run/pixel-ssh-service-loop.lock"
  dropbear_pid_file="${base}/run/dropbear.pid"

  if [ -f "${pid_file}" ]; then
    pid="$(cat "${pid_file}" 2>/dev/null || true)"
    if owned_pid "${pid}" "${base}"; then pids="${pids} ${pid}"; fi
    rm -f "${pid_file}" >/dev/null 2>&1 || true
  fi

  if [ -f "${dropbear_pid_file}" ]; then
    dropbear_pid="$(cat "${dropbear_pid_file}" 2>/dev/null || true)"
    if owned_pid "${dropbear_pid}" "${base}"; then pids="${pids} ${dropbear_pid}"; fi
  fi
  rm -f "${dropbear_pid_file}" >/dev/null 2>&1 || true
  rm -rf "${lock_dir}" >/dev/null 2>&1 || true

done

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

pkill -f "${BASE_LOCAL}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 || true
pkill -f "${BASE_LEGACY}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 || true
pkill -f "${BASE_LOCAL}/bin/dropbear" >/dev/null 2>&1 || true
pkill -f "${BASE_LEGACY}/bin/dropbear" >/dev/null 2>&1 || true

exit 0
