#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/apps/satiksme-bot"
CONF_ENV="/data/local/pixel-stack/conf/apps/satiksme-bot.env"
RUNTIME_ENV="${BASE}/env/satiksme-bot.env"
TPL_DIR="/data/local/pixel-stack/templates/satiksme"
BIN_DIR="${BASE}/bin"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
PID_FILE="${RUN_DIR}/satiksme-bot-service-loop.pid"
LOOP_BIN="${BIN_DIR}/satiksme-bot-service-loop"
TUNNEL_LOOP_PID_FILE="${RUN_DIR}/satiksme-web-tunnel-service-loop.pid"
TUNNEL_LOOP_BIN="${BIN_DIR}/satiksme-web-tunnel-service-loop"
LAUNCH_BIN="${BIN_DIR}/satiksme-bot-launch"
TPL_LAUNCH="${TPL_DIR}/satiksme-launch.sh"
TPL_LOOP="${TPL_DIR}/satiksme-service-loop.sh"
TPL_TUNNEL_LOOP="${TPL_DIR}/satiksme-web-tunnel-service-loop.sh"

mkdir -p "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${BASE}/env" "${BASE}/data/catalog" "${BASE}/state"

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

ensure_web_session_secret() {
  if ! is_true "${SATIKSME_WEB_ENABLED:-0}"; then
    return 0
  fi

  secret_file="${SATIKSME_WEB_SESSION_SECRET_FILE:-/data/local/pixel-stack/conf/apps/satiksme-bot-web-session-secret}"
  secret_dir="$(dirname "${secret_file}")"
  mkdir -p "${secret_dir}"

  if [ -s "${secret_file}" ]; then
    chmod 600 "${secret_file}" >/dev/null 2>&1 || true
    return 0
  fi

  if [ -r /dev/urandom ]; then
    tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 64 > "${secret_file}"
  else
    date +%s | tr -d '\n' > "${secret_file}"
  fi
  chmod 600 "${secret_file}" >/dev/null 2>&1 || true
}

if [ ! -f "${RUNTIME_ENV}" ] && [ -f "${CONF_ENV}" ]; then
  cp "${CONF_ENV}" "${RUNTIME_ENV}"
  chmod 600 "${RUNTIME_ENV}" >/dev/null 2>&1 || true
fi

if [ -f "${RUNTIME_ENV}" ]; then
  set -a
  # shellcheck disable=SC1090
  . "${RUNTIME_ENV}"
  set +a
  ensure_web_session_secret
fi

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

pid_matches_target() {
  pid="$1"
  target="$2"
  [ -n "${pid}" ] || return 1
  kill -0 "${pid}" >/dev/null 2>&1 || return 1
  cmdline="$(pid_cmdline "${pid}")"
  target_base="$(basename "${target}")"
  case "${cmdline}" in
    *"${target}"*|*" ${target_base} "*|"${target_base}"|*" ${target_base}") return 0 ;;
    *) return 1 ;;
  esac
}

find_existing_pid() {
  target="$1"
  target_base="$(basename "${target}")"
  ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v target="${target}" -v target_base="${target_base}" -v self_pid="$$" '
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

sync_pid_file_with_target() {
  pid_file="$1"
  target="$2"

  pid="$(read_pid_file "${pid_file}" || true)"
  if pid_matches_target "${pid}" "${target}"; then
    echo "${pid}" > "${pid_file}"
    return 0
  fi

  existing_pid="$(find_existing_pid "${target}" || true)"
  if pid_matches_target "${existing_pid}" "${target}"; then
    echo "${existing_pid}" > "${pid_file}"
    return 0
  fi

  rm -f "${pid_file}" >/dev/null 2>&1 || true
  return 1
}

start_loop_if_needed() {
  pid_file="$1"
  loop_bin="$2"
  log_file="$3"

  if sync_pid_file_with_target "${pid_file}" "${loop_bin}"; then
    return 0
  fi

  nohup env SATIKSME_BOT_ROOT="${BASE}" "${loop_bin}" >>"${log_file}" 2>&1 &
  pid="$!"
  sleep 1
  if pid_matches_target "${pid}" "${loop_bin}"; then
    echo "${pid}" > "${pid_file}"
    return 0
  fi
  if sync_pid_file_with_target "${pid_file}" "${loop_bin}"; then
    return 0
  fi
  rm -f "${pid_file}" >/dev/null 2>&1 || true
  return 1
}

stop_loop_if_running() {
  pid_file="$1"
  loop_bin="$2"

  pid="$(cat "${pid_file}" 2>/dev/null || true)"
  if pid_matches_target "${pid}" "${loop_bin}"; then
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  fi
  rm -f "${pid_file}" >/dev/null 2>&1 || true
}

ensure_satiksme_web_tunnel_loop_state() {
  if ! is_true "${SATIKSME_WEB_ENABLED:-0}" || ! is_true "${SATIKSME_WEB_TUNNEL_ENABLED:-0}"; then
    stop_loop_if_running "${TUNNEL_LOOP_PID_FILE}" "${TUNNEL_LOOP_BIN}"
    return 0
  fi
  if [ ! -x "${TUNNEL_LOOP_BIN}" ]; then
    echo "missing satiksme web tunnel loop: ${TUNNEL_LOOP_BIN}" >&2
    return 1
  fi
  start_loop_if_needed "${TUNNEL_LOOP_PID_FILE}" "${TUNNEL_LOOP_BIN}" "${LOG_DIR}/satiksme-web-tunnel-service-loop.log"
}

if [ ! -f "${TPL_LAUNCH}" ]; then
  echo "missing satiksme launch template: ${TPL_LAUNCH}" >&2
  exit 1
fi
if [ ! -f "${TPL_LOOP}" ]; then
  echo "missing satiksme loop template: ${TPL_LOOP}" >&2
  exit 1
fi
if [ ! -f "${TPL_TUNNEL_LOOP}" ]; then
  echo "missing satiksme tunnel loop template: ${TPL_TUNNEL_LOOP}" >&2
  exit 1
fi

cp "${TPL_LAUNCH}" "${LAUNCH_BIN}"
chmod 0755 "${LAUNCH_BIN}"
cp "${TPL_LOOP}" "${LOOP_BIN}"
chmod 0755 "${LOOP_BIN}"
cp "${TPL_TUNNEL_LOOP}" "${TUNNEL_LOOP_BIN}"
chmod 0755 "${TUNNEL_LOOP_BIN}"

if [ ! -x "${LOOP_BIN}" ]; then
  echo "missing loop binary: ${LOOP_BIN}" >&2
  exit 1
fi

if [ ! -x "${BASE}/bin/satiksme-bot.current" ] && [ ! -x "${BASE}/bin/satiksme-bot" ]; then
  echo "missing satiksme bot executable under ${BASE}/bin" >&2
  exit 1
fi

if sync_pid_file_with_target "${PID_FILE}" "${LOOP_BIN}"; then
  ensure_satiksme_web_tunnel_loop_state
  exit 0
fi

nohup env SATIKSME_BOT_ROOT="${BASE}" "${LOOP_BIN}" >>"${LOG_DIR}/service-loop.log" 2>&1 &
pid="$!"
sleep 1
if pid_matches_target "${pid}" "${LOOP_BIN}"; then
  echo "${pid}" > "${PID_FILE}"
  ensure_satiksme_web_tunnel_loop_state || exit 1
elif sync_pid_file_with_target "${PID_FILE}" "${LOOP_BIN}"; then
  ensure_satiksme_web_tunnel_loop_state || exit 1
else
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  exit 1
fi

exit 0
