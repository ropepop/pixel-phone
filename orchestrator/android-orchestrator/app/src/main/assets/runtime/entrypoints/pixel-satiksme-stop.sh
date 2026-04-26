#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack/apps/satiksme-bot"
RUN_DIR="${BASE}/run"
LOOP_PID_FILE="${RUN_DIR}/satiksme-bot-service-loop.pid"
BOT_PID_FILE="${RUN_DIR}/satiksme-bot.pid"
HEARTBEAT_FILE="${RUN_DIR}/heartbeat.epoch"
LOCK_DIR="${RUN_DIR}/satiksme-bot-service-loop.lock"
TUNNEL_LOOP_PID_FILE="${RUN_DIR}/satiksme-web-tunnel-service-loop.pid"
TUNNEL_LOCK_DIR="${RUN_DIR}/satiksme-web-tunnel-service-loop.lock"
CLOUDFLARED_PID_FILE="${RUN_DIR}/satiksme-bot-cloudflared.pid"
SELF_PID="$$"

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  ps -p "${pid}" -o ARGS= 2>/dev/null || true
}

list_matching_pids() {
  target="$1"
  target_base="$(basename "${target}")"
  ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v self_pid="${SELF_PID}" -v target="${target}" -v target_base="${target_base}" '
    {
      pid=$1
      name=$2
      if (pid == self_pid) {
        next
      }
      if (index($0, target) > 0 || name == target_base) {
        print pid
      }
    }
  '
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

stop_pid_file() {
  pid_file="$1"
  target="$2"
  pid="$(cat "${pid_file}" 2>/dev/null || true)"
  if pid_matches_target "${pid}" "${target}"; then
    kill_pid_and_wait "${pid}"
  fi
  rm -f "${pid_file}" >/dev/null 2>&1 || true
}

kill_matching_processes() {
  target="$1"
  for pid in $(list_matching_pids "${target}"); do
    kill_pid_and_wait "${pid}"
  done
}

stop_pid_file "${BOT_PID_FILE}" 'satiksme-bot'
stop_pid_file "${LOOP_PID_FILE}" '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot-service-loop'
stop_pid_file "${TUNNEL_LOOP_PID_FILE}" '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-web-tunnel-service-loop'
stop_pid_file "${CLOUDFLARED_PID_FILE}" '/data/local/pixel-stack/apps/satiksme-bot/bin/cloudflared'

kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot-service-loop'
kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-web-tunnel-service-loop'
kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot-launch'
kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/cloudflared'
kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/releases/'
kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/current'
kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/state/satiksme-web-tunnel/satiksme-bot-cloudflared.yml'
kill_matching_processes 'satiksme-bot.current'
kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot'

rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
rm -rf "${TUNNEL_LOCK_DIR}" >/dev/null 2>&1 || true
rm -f "${HEARTBEAT_FILE}" >/dev/null 2>&1 || true
exit 0
