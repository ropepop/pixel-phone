#!/system/bin/sh

PIXEL_SSH_ROOT="${PIXEL_SSH_ROOT:-/data/local/pixel-stack/ssh}"
SERVICE_LOOP="${PIXEL_SSH_ROOT}/bin/pixel-ssh-service-loop"
RUN_DIR="${PIXEL_SSH_ROOT}/run"
PID_FILE="${RUN_DIR}/pixel-ssh-service-loop.pid"
LOCK_DIR="${RUN_DIR}/pixel-ssh-service-loop.lock"

if [ ! -x "${SERVICE_LOOP}" ]; then
  exit 0
fi

mkdir -p "${RUN_DIR}"

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  if command -v ps >/dev/null 2>&1; then
    ps -p "${pid}" -o command= 2>/dev/null || true
    return 0
  fi
  return 1
}

loop_running=0
if [ -r "${PID_FILE}" ]; then
  pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    cmdline="$(pid_cmdline "${pid}" || true)"
    case " ${cmdline} " in
      *" ${SERVICE_LOOP} "*|*" pixel-ssh-service-loop "*) loop_running=1 ;;
      *) rm -f "${PID_FILE}" >/dev/null 2>&1 || true ;;
    esac
  else
    rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  fi
fi

if [ "${loop_running}" = "1" ]; then
  exit 0
fi

# Drop stale lock state before attempting a fresh launch.
if [ -d "${LOCK_DIR}" ]; then
  rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
fi

nohup "${SERVICE_LOOP}" >/dev/null 2>&1 &
pid="$!"
if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
  echo "${pid}" > "${PID_FILE}"
else
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
fi

exit 0
