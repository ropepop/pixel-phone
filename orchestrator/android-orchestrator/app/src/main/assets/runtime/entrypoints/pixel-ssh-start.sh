#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/ssh"
TPL_DIR="/data/local/pixel-stack/templates/ssh"
BIN_DIR="${BASE}/bin"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
CONF_FILE="${BASE}/conf/dropbear.env"
PID_FILE="${RUN_DIR}/pixel-ssh-service-loop.pid"
LOCK_DIR="${RUN_DIR}/pixel-ssh-service-loop.lock"
DROPBEAR_PID_FILE="${RUN_DIR}/dropbear.pid"
DUPLICATE_MARK_FILE="${RUN_DIR}/pixel-ssh-service-loop.duplicate"
LOOP_NAME="pixel-ssh-service-loop"
LOOP_BIN="${BIN_DIR}/pixel-ssh-service-loop"
LAUNCH_BIN="${BIN_DIR}/pixel-ssh-launch"
TPL_LAUNCH="${TPL_DIR}/pixel-ssh-launch.sh"
TPL_LOOP="${TPL_DIR}/pixel-ssh-service-loop.sh"
DROPBEAR_BIN="${BASE}/bin/dropbear"

mkdir -p "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${BASE}/conf" "${BASE}/etc/dropbear" "${BASE}/home/root/.ssh"

if [ -r "${CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${CONF_FILE}"
  set +a
fi

: "${SSH_PORT:=2222}"
: "${SSH_BIND_ADDRESS:=0.0.0.0}"

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

read_pid_file() {
  pid_file="$1"
  if [ -r "${pid_file}" ]; then
    sed -n '1p' "${pid_file}" 2>/dev/null | tr -d '\r'
  fi
}

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

pid_matches_target() {
  pid="$1"
  target="$2"
  if [ -z "${pid}" ] || ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 1
  fi
  cmdline="$(pid_cmdline "${pid}" || true)"
  target_base="$(basename "${target}")"
  case "${cmdline}" in
    *"${target}"*|*" ${target_base} "*|"${target_base}"|*" ${target_base}") return 0 ;;
    *) return 1 ;;
  esac
}

pid_matches_loop() {
  pid_matches_target "${1:-}" "${LOOP_BIN}"
}

pid_matches_dropbear() {
  pid_matches_target "${1:-}" "${DROPBEAR_BIN}"
}

pid_ppid() {
  pid="$1"
  if [ -r "/proc/${pid}/status" ]; then
    awk '/^PPid:/{print $2; exit}' "/proc/${pid}/status" 2>/dev/null || true
    return 0
  fi
  if command -v ps >/dev/null 2>&1; then
    ps -p "${pid}" -o ppid= 2>/dev/null | tr -d ' ' || true
    return 0
  fi
  return 1
}

listener_matches_bind() {
  local_addr="$1"
  case "${SSH_BIND_ADDRESS}" in
    ''|'0.0.0.0'|'::'|'*')
      case "${local_addr}" in
        *":${SSH_PORT}") return 0 ;;
      esac
      ;;
    *)
      case "${local_addr}" in
        "${SSH_BIND_ADDRESS}:${SSH_PORT}"|"[${SSH_BIND_ADDRESS}]:${SSH_PORT}") return 0 ;;
      esac
      ;;
  esac
  return 1
}

find_listener_pid() {
  if ! command -v ss >/dev/null 2>&1; then
    return 1
  fi

  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      *"pid="*)
        local_addr="$(printf '%s\n' "${line}" | awk '{print $4}')"
        if ! listener_matches_bind "${local_addr}"; then
          continue
        fi
        listener_pid="$(printf '%s\n' "${line}" | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sed -n '1p')"
        if [ -n "${listener_pid}" ]; then
          printf '%s\n' "${listener_pid}"
          return 0
        fi
        ;;
    esac
  done <<EOF_SS
$(ss -ltnp 2>/dev/null || true)
EOF_SS
  return 1
}

list_matching_pids() {
  target="$1"
  target_base="$(basename "${target}")"
  if ! command -v ps >/dev/null 2>&1; then
    return 1
  fi

  ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v target="${target}" -v target_base="${target_base}" '
    function starts_with(value, prefix) { return index(value, prefix) == 1 }
    function next_is_boundary(value, prefix_len) {
      c = substr(value, prefix_len + 1, 1)
      return c == "" || c == " "
    }
    {
      pid = $1
      name = $2
      args = $0
      sub(/^[[:space:]]*[0-9]+[[:space:]]+[^[:space:]]+[[:space:]]+/, "", args)
      if (name == target_base ||
        args == target ||
        (starts_with(args, target) && next_is_boundary(args, length(target))) ||
        (starts_with(args, "sh " target) && next_is_boundary(args, length("sh " target))) ||
        (starts_with(args, "/bin/sh " target) && next_is_boundary(args, length("/bin/sh " target))) ||
        (starts_with(args, "/system/bin/sh " target) && next_is_boundary(args, length("/system/bin/sh " target))) ||
        (starts_with(args, target_base) && next_is_boundary(args, length(target_base)))) {
        print pid
      }
    }
  ' | awk '!seen[$0]++'
}

list_loop_pids() {
  list_matching_pids "${LOOP_BIN}" | sort -n
}

kill_pid_and_wait() {
  pid="$1"
  [ -n "${pid}" ] || return 0
  kill "${pid}" >/dev/null 2>&1 || true

  attempts=0
  while [ "${attempts}" -lt 10 ]; do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 1
  done

  kill -9 "${pid}" >/dev/null 2>&1 || true
  sleep 1
}

repair_loop_state() {
  loop_pid="$1"
  if ! pid_matches_loop "${loop_pid}"; then
    return 1
  fi

  if [ ! -d "${LOCK_DIR}" ]; then
    rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
    mkdir "${LOCK_DIR}" >/dev/null 2>&1 || true
  fi
  echo "${loop_pid}" > "${PID_FILE}"
  rm -f "${DUPLICATE_MARK_FILE}" >/dev/null 2>&1 || true
}

clear_runtime_state() {
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  rm -f "${DROPBEAR_PID_FILE}" >/dev/null 2>&1 || true
  rm -f "${DUPLICATE_MARK_FILE}" >/dev/null 2>&1 || true
  rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
}

prune_duplicate_loops() {
  keep_pid="$1"
  killed_pids=""

  for loop_pid in $(list_loop_pids 2>/dev/null || true); do
    if [ -n "${keep_pid}" ] && [ "${loop_pid}" = "${keep_pid}" ]; then
      continue
    fi
    if ! pid_matches_loop "${loop_pid}"; then
      continue
    fi
    kill "${loop_pid}" >/dev/null 2>&1 || true
    killed_pids="${killed_pids} ${loop_pid}"
  done

  if [ -n "${killed_pids}" ]; then
    sleep 1
    for loop_pid in ${killed_pids}; do
      if pid_matches_loop "${loop_pid}"; then
        kill -9 "${loop_pid}" >/dev/null 2>&1 || true
      fi
    done
  fi
}

choose_canonical_loop_pid() {
  preferred_pid="$1"
  if pid_matches_loop "${preferred_pid}"; then
    printf '%s\n' "${preferred_pid}"
    return 0
  fi

  current_pid="$(read_pid_file "${PID_FILE}" || true)"
  if pid_matches_loop "${current_pid}"; then
    printf '%s\n' "${current_pid}"
    return 0
  fi

  for loop_pid in $(list_loop_pids 2>/dev/null || true); do
    if pid_matches_loop "${loop_pid}"; then
      printf '%s\n' "${loop_pid}"
      return 0
    fi
  done

  return 1
}

launch_loop() {
  nohup env PIXEL_SSH_ROOT="${BASE}" "${LOOP_BIN}" >>"${LOG_DIR}/pixel-ssh-service-loop.log" 2>&1 &
  pid="$!"
  if [ -n "${pid}" ] && kill -0 "${pid}" >/dev/null 2>&1; then
    echo "${pid}" > "${PID_FILE}"
    return 0
  fi
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  return 1
}

listener_pid="$(find_listener_pid || true)"
if pid_matches_dropbear "${listener_pid}"; then
  canonical_loop_pid="$(pid_ppid "${listener_pid}" | tr -d ' ' || true)"
  if pid_matches_loop "${canonical_loop_pid}"; then
    prune_duplicate_loops "${canonical_loop_pid}"
    repair_loop_state "${canonical_loop_pid}" || true
    printf '%s\n' "${listener_pid}" > "${DROPBEAR_PID_FILE}"
    exit 0
  fi

  prune_duplicate_loops ""
  kill_pid_and_wait "${listener_pid}"
  clear_runtime_state
  launch_loop || exit 1
  exit 0
fi

canonical_loop_pid="$(choose_canonical_loop_pid "" || true)"
if [ -n "${canonical_loop_pid}" ]; then
  prune_duplicate_loops "${canonical_loop_pid}"
  repair_loop_state "${canonical_loop_pid}" || true
  exit 0
fi

prune_duplicate_loops ""
clear_runtime_state
launch_loop || exit 1

exit 0
