#!/system/bin/sh
set -eu

DEEP=0
for arg in "$@"; do
  case "$arg" in
    --deep|--full) DEEP=1 ;;
    *) echo "unsupported ticket stop argument: $arg" >&2; exit 2 ;;
  esac
done

STACK_ROOT="${PIXEL_STACK_ROOT:-/data/local/pixel-stack}"
BASE="${STACK_ROOT}/apps/ticket-screen"
CONF_ENV="${STACK_ROOT}/conf/apps/ticket-screen.env"
RUNTIME_ENV="${BASE}/env/ticket-screen.env"
LOCK="${BASE}/run/ticket-screen-start-stop.lock"
APP="lv.jolkins.pixelorchestrator"
. "${PIXEL_TICKET_LOCK_HELPER:-${STACK_ROOT}/bin/pixel-ticket-lifecycle-lock.sh}"
for env_file in "$CONF_ENV" "$RUNTIME_ENV"; do [ ! -r "$env_file" ] || . "$env_file"; done
: "${TICKET_SCREEN_PORT:=9388}"

listening() {
  ss -ltn 2>/dev/null | grep -E "[:.]${TICKET_SCREEN_PORT}[[:space:]]" >/dev/null 2>&1
}

mkdir -p "${BASE}/run"
ticket_lock_acquire "$LOCK" "${TICKET_SCREEN_STOP_LOCK_WAIT_SECONDS:-10}" || { echo "ticket start/stop lock remained active" >&2; exit 1; }

am start-foreground-service -n "${APP}/.app.SupervisorService" \
  -a "lv.jolkins.pixelorchestrator.action.TICKET_STOP_SERVER" \
  --es orchestrator_action ticket_stop_server >/dev/null 2>&1 || true
settings put secure disable_secure_windows 0 >/dev/null 2>&1 || true
state_file="${BASE}/state/ro-debuggable-before-ticket"
if [ -r "$state_file" ]; then
  original=$(sed -n '1p' "$state_file" | tr -d '\r')
  resetprop ro.debuggable "${original:-0}" >/dev/null 2>&1 || true
  rm -f "$state_file"
fi

attempts=25
while [ "$attempts" -gt 0 ] && listening; do attempts=$((attempts - 1)); sleep 0.2; done
if listening && [ "$DEEP" = 1 ]; then
  am force-stop "$APP" >/dev/null 2>&1 || true
  sleep 0.2
fi
if listening; then
  echo "Ticket runtime did not stop cleanly" >&2
  exit 1
fi
exit 0
