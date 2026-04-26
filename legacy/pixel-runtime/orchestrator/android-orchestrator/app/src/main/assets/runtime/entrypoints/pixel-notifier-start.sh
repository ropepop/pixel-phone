#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/apps/site-notifications"
CONF_ENV="/data/local/pixel-stack/conf/apps/site-notifications.env"
RUNTIME_ENV="${BASE}/env/site-notifications.env"
TPL_DIR="/data/local/pixel-stack/templates/notifier"
BIN_DIR="${BASE}/bin"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
PID_FILE="${RUN_DIR}/site-notifier-service-loop.pid"
LOOP_BIN="${BIN_DIR}/site-notifier-service-loop"
LAUNCH_BIN="${BIN_DIR}/site-notifier-launch"
TPL_LAUNCH="${TPL_DIR}/notifier-launch.sh"
TPL_LOOP="${TPL_DIR}/notifier-service-loop.sh"

mkdir -p "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${BASE}/env" "${BASE}/state" "${BASE}/current"

if [ ! -f "${RUNTIME_ENV}" ] && [ -f "${CONF_ENV}" ]; then
  cp "${CONF_ENV}" "${RUNTIME_ENV}"
  chmod 600 "${RUNTIME_ENV}" >/dev/null 2>&1 || true
fi

if [ ! -f "${TPL_LAUNCH}" ]; then
  echo "missing notifier launch template: ${TPL_LAUNCH}" >&2
  exit 1
fi
if [ ! -f "${TPL_LOOP}" ]; then
  echo "missing notifier loop template: ${TPL_LOOP}" >&2
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

find_existing_loop_pid() {
  target_base="$(basename "${LOOP_BIN}")"
  ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v target="${LOOP_BIN}" -v target_base="${target_base}" -v self_pid="$$" '
    function starts_with(value, prefix) { return index(value, prefix) == 1 }
    function next_is_boundary(value, prefix_len) {
      c = substr(value, prefix_len + 1, 1)
      return c == "" || c == " "
    }
    {
      pid = $1
      name = $2
      if (pid == self_pid) {
        next
      }
      args = ""
      if (NF >= 3) {
        args = substr($0, index($0, $3))
      }
      if (name == target_base ||
        args == target ||
        (starts_with(args, target) && next_is_boundary(args, length(target))) ||
        (starts_with(args, "sh " target) && next_is_boundary(args, length("sh " target))) ||
        (starts_with(args, target_base) && next_is_boundary(args, length(target_base)))) {
        print pid
        exit
      }
    }
  '
}

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  ps -p "${pid}" -o ARGS= 2>/dev/null || true
}

read_pid_file() {
  pid_file="$1"
  if [ -r "${pid_file}" ]; then
    sed -n '1p' "${pid_file}" 2>/dev/null | tr -d '\r'
  fi
}

pid_matches_loop() {
  pid="$1"
  [ -n "${pid}" ] || return 1
  kill -0 "${pid}" >/dev/null 2>&1 || return 1
  cmdline="$(pid_cmdline "${pid}")"
  target_base="$(basename "${LOOP_BIN}")"
  case "${cmdline}" in
    *"${LOOP_BIN}"*|*" ${target_base} "*|"${target_base}"|*" ${target_base}") return 0 ;;
    *) return 1 ;;
  esac
}

sync_pid_file_with_loop() {
  pid="$(read_pid_file "${PID_FILE}" || true)"
  if pid_matches_loop "${pid}"; then
    echo "${pid}" > "${PID_FILE}"
    return 0
  fi

  existing_pid="$(find_existing_loop_pid || true)"
  if pid_matches_loop "${existing_pid}"; then
    echo "${existing_pid}" > "${PID_FILE}"
    return 0
  fi

  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  return 1
}

if sync_pid_file_with_loop; then
  exit 0
fi

nohup env SITE_NOTIFIER_ROOT="${BASE}" "${LOOP_BIN}" >>"${LOG_DIR}/service-loop.log" 2>&1 &
pid="$!"
sleep 1
if pid_matches_loop "${pid}"; then
  echo "${pid}" > "${PID_FILE}"
elif sync_pid_file_with_loop; then
  :
else
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  exit 1
fi

exit 0
