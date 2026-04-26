#!/system/bin/sh
set +e

BASE_LOCAL="/data/local/pixel-stack/ssh"
BASE_LEGACY="/data/adb/pixel-stack/ssh"

for base in "${BASE_LOCAL}" "${BASE_LEGACY}"; do
  pid_file="${base}/run/pixel-ssh-service-loop.pid"
  lock_dir="${base}/run/pixel-ssh-service-loop.lock"
  dropbear_pid_file="${base}/run/dropbear.pid"

  if [ -f "${pid_file}" ]; then
    pid="$(cat "${pid_file}" 2>/dev/null || true)"
    if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
      kill "${pid}" >/dev/null 2>&1 || true
      sleep 1
      kill -9 "${pid}" >/dev/null 2>&1 || true
    fi
    rm -f "${pid_file}" >/dev/null 2>&1 || true
  fi

  rm -f "${dropbear_pid_file}" >/dev/null 2>&1 || true
  rm -rf "${lock_dir}" >/dev/null 2>&1 || true
done

pkill -f 'pixel-ssh-service-loop' >/dev/null 2>&1 || true
pkill -f '/data/local/pixel-stack/ssh/bin/dropbear' >/dev/null 2>&1 || true
pkill -f '/data/adb/pixel-stack/ssh/bin/dropbear' >/dev/null 2>&1 || true

exit 0
