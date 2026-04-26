#!/system/bin/sh
set +e

BASE_LOCAL="/data/local/pixel-stack/ssh"
BASE_LEGACY="/data/adb/pixel-stack/ssh"

kill_pid_if_alive() {
  pid="$1"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  fi
}

for base in "${BASE_LOCAL}" "${BASE_LEGACY}"; do
  pid_file="${base}/run/pixel-ssh-service-loop.pid"
  lock_dir="${base}/run/pixel-ssh-service-loop.lock"
  dropbear_pid_file="${base}/run/dropbear.pid"

  if [ -f "${pid_file}" ]; then
    pid="$(cat "${pid_file}" 2>/dev/null || true)"
    kill_pid_if_alive "${pid}"
    rm -f "${pid_file}" >/dev/null 2>&1 || true
  fi

  if [ -f "${dropbear_pid_file}" ]; then
    dropbear_pid="$(cat "${dropbear_pid_file}" 2>/dev/null || true)"
    kill_pid_if_alive "${dropbear_pid}"
  fi
  rm -f "${dropbear_pid_file}" >/dev/null 2>&1 || true
  rm -rf "${lock_dir}" >/dev/null 2>&1 || true

  pkill -f "${base}/etc/dropbear/" >/dev/null 2>&1 || true
done

pkill -f 'pixel-ssh-service-loop' >/dev/null 2>&1 || true

exit 0
