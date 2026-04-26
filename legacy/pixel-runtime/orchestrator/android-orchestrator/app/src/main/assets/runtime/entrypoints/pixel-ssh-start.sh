#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/ssh"
TPL_DIR="/data/local/pixel-stack/templates/ssh"
BIN_DIR="${BASE}/bin"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
PID_FILE="${RUN_DIR}/pixel-ssh-service-loop.pid"
LOOP_BIN="${BIN_DIR}/pixel-ssh-service-loop"
LAUNCH_BIN="${BIN_DIR}/pixel-ssh-launch"
TPL_LAUNCH="${TPL_DIR}/pixel-ssh-launch.sh"
TPL_LOOP="${TPL_DIR}/pixel-ssh-service-loop.sh"

mkdir -p "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${BASE}/conf" "${BASE}/etc/dropbear" "${BASE}/home/root/.ssh"

if [ ! -f "${TPL_LAUNCH}" ]; then
  echo "missing ssh launch template: ${TPL_LAUNCH}" >&2
  exit 1
fi
if [ ! -f "${TPL_LOOP}" ]; then
  echo "missing ssh loop template: ${TPL_LOOP}" >&2
  exit 1
fi

cp "${TPL_LAUNCH}" "${LAUNCH_BIN}"
chmod 0755 "${LAUNCH_BIN}"
cp "${TPL_LOOP}" "${LOOP_BIN}"
chmod 0755 "${LOOP_BIN}"

if [ ! -x "${LOOP_BIN}" ]; then
  echo "missing loop binary: ${LOOP_BIN}" >&2
  exit 1
fi

if [ ! -x "${BASE}/bin/dropbear" ]; then
  echo "missing dropbear binary: ${BASE}/bin/dropbear" >&2
  exit 1
fi

if [ -f "${PID_FILE}" ]; then
  old_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [ -n "${old_pid}" ] && kill -0 "${old_pid}" >/dev/null 2>&1; then
    exit 0
  fi
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
fi

nohup env PIXEL_SSH_ROOT="${BASE}" "${LOOP_BIN}" >>"${LOG_DIR}/pixel-ssh-service-loop.log" 2>&1 &
pid="$!"
if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
  echo "${pid}" > "${PID_FILE}"
else
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  exit 1
fi

exit 0
