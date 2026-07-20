#!/system/bin/sh
set -eu

FORCE=0
DEEP=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    --deep|--full) FORCE=1; DEEP=1 ;;
    *) echo "unsupported ticket start argument: $arg" >&2; exit 2 ;;
  esac
done

STACK_ROOT="${PIXEL_STACK_ROOT:-/data/local/pixel-stack}"
BASE="${STACK_ROOT}/apps/ticket-screen"
CONF_ENV="${STACK_ROOT}/conf/apps/ticket-screen.env"
RUNTIME_ENV="${BASE}/env/ticket-screen.env"
HEALTH="${PIXEL_TICKET_HEALTH_BIN:-${STACK_ROOT}/bin/pixel-ticket-health.sh}"
LOCK="${BASE}/run/ticket-screen-start-stop.lock"
APP="lv.jolkins.pixelorchestrator"
. "${PIXEL_TICKET_LOCK_HELPER:-${STACK_ROOT}/bin/pixel-ticket-lifecycle-lock.sh}"

for env_file in "$CONF_ENV" "$RUNTIME_ENV"; do
  if [ -r "$env_file" ]; then
    set -a
    . "$env_file"
    set +a
  fi
done

ready() {
  [ -r "$HEALTH" ] || return 1
  if [ "$DEEP" = 1 ]; then sh "$HEALTH" --deep >/dev/null 2>&1
  else sh "$HEALTH" >/dev/null 2>&1
  fi
}

inputs_current() {
  [ ! -f "$CONF_ENV" ] || { [ -f "$RUNTIME_ENV" ] && cmp -s "$CONF_ENV" "$RUNTIME_ENV"; }
}

open_ui() {
  case "${TICKET_SCREEN_OPEN_ORCHESTRATOR_ON_START:-0}" in
    1|true|TRUE|yes|YES|on|ON) am start -n "${APP}/.app.MainActivity" >/dev/null 2>&1 || true ;;
  esac
}

wait_ready() {
  attempts=$((${1:-15} * 5))
  while [ "$attempts" -gt 0 ]; do
    ready && return 0
    attempts=$((attempts - 1))
    sleep 0.2
  done
  return 1
}

if [ "$FORCE" = 0 ] && ready && inputs_current; then open_ui; exit 0; fi
mkdir -p "${BASE}/run" "${BASE}/logs" "${BASE}/state" "${BASE}/env"
chcon u:object_r:shell_data_file:s0 "$BASE" "${BASE}/run" "${BASE}/logs" "${BASE}/state" "${BASE}/env" 2>/dev/null || true
if ! ticket_lock_acquire "$LOCK" "${TICKET_SCREEN_START_LOCK_WAIT_SECONDS:-10}"; then
  [ "$FORCE" = 0 ] && wait_ready 1 && exit 0
  echo "ticket start/stop is active but Ticket did not become ready" >&2
  exit 1
fi
if [ "$FORCE" = 0 ] && ready && inputs_current; then open_ui; exit 0; fi
if [ ! -f "$RUNTIME_ENV" ] && [ -f "$CONF_ENV" ]; then
  cp "$CONF_ENV" "$RUNTIME_ENV"
  chmod 600 "$RUNTIME_ENV"
  chcon u:object_r:shell_data_file:s0 "$RUNTIME_ENV" 2>/dev/null || true
fi
am start-foreground-service -n "${APP}/.app.SupervisorService" \
  -a "lv.jolkins.pixelorchestrator.action.TICKET_START_SERVER" \
  --es orchestrator_action ticket_start_server >/dev/null
open_ui
wait_ready "${TICKET_SCREEN_START_TIMEOUT_SECONDS:-15}" || {
  echo "Ticket did not become ready" >&2
  exit 1
}
