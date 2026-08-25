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

ticket_service_active() {
  service_dump=$(dumpsys activity services "${APP}/.app.ticket.TicketStreamService" 2>/dev/null) || return 0
  printf '%s\n' "$service_dump" | grep -F "TicketStreamService" >/dev/null 2>&1
}

restore_secure_capture_state() {
  state_file="${BASE}/state/ro-debuggable-before-ticket"
  if [ ! -e "$state_file" ]; then
    current_debuggable=$(getprop ro.debuggable 2>/dev/null | tr -d '\r' || true)
    current_disable_secure_windows=$(settings get secure disable_secure_windows 2>/dev/null | tr -d '\r' || true)
    case "$current_debuggable" in 0|1) ;; *) return 1 ;; esac
    case "$current_disable_secure_windows" in 0|1|null) ;; *) return 1 ;; esac
    case "$current_disable_secure_windows" in
      0|null) return 0 ;;
    esac
    # No original record exists, so an active or partial bypass cannot be restored historically.
    # Normalize it to the conservative inactive baseline and prove both values independently.
    settings put secure disable_secure_windows 0 >/dev/null 2>&1 || true
    resetprop ro.debuggable 0 >/dev/null 2>&1 || true
    current_debuggable=$(getprop ro.debuggable 2>/dev/null | tr -d '\r' || true)
    current_disable_secure_windows=$(settings get secure disable_secure_windows 2>/dev/null | tr -d '\r' || true)
    [ "$current_debuggable" = 0 ] || return 1
    [ "$current_disable_secure_windows" = 0 ] || return 1
    return 0
  fi
  [ -r "$state_file" ] || return 1

  saved_debuggable=$(sed -n '1p' "$state_file" 2>/dev/null | tr -d '\r')
  saved_disable_secure_windows=$(sed -n '2p' "$state_file" 2>/dev/null | tr -d '\r')
  case "$saved_debuggable" in 0|1) ;; *) return 1 ;; esac
  if [ -z "$saved_disable_secure_windows" ]; then
    # v311 stored only ro.debuggable and always restored the secure setting to zero. Migrate the
    # durable record before touching either live setting so an interrupted stop remains retryable.
    saved_disable_secure_windows=0
    temporary_state="${state_file}.tmp.$$"
    if ! printf '%s\n%s\n' "$saved_debuggable" "$saved_disable_secure_windows" > "$temporary_state"; then
      return 1
    fi
    chmod 600 "$temporary_state" >/dev/null 2>&1 || true
    if ! mv "$temporary_state" "$state_file"; then
      rm -f "$temporary_state" >/dev/null 2>&1 || true
      return 1
    fi
  else
    case "$saved_disable_secure_windows" in 0|1|null) ;; *) return 1 ;; esac
  fi

  # Always attempt both restores. A failure in one command must not suppress the other setting.
  if [ "$saved_disable_secure_windows" = null ]; then
    settings delete secure disable_secure_windows >/dev/null 2>&1 || true
  else
    settings put secure disable_secure_windows "$saved_disable_secure_windows" >/dev/null 2>&1 || true
  fi
  resetprop ro.debuggable "$saved_debuggable" >/dev/null 2>&1 || true

  current_debuggable=$(getprop ro.debuggable 2>/dev/null | tr -d '\r' || true)
  current_disable_secure_windows=$(settings get secure disable_secure_windows 2>/dev/null | tr -d '\r' || true)
  [ "$current_debuggable" = "$saved_debuggable" ] || return 1
  [ "$current_disable_secure_windows" = "$saved_disable_secure_windows" ] || return 1
  rm -f "$state_file" || return 1
  [ ! -e "$state_file" ] || return 1
}

mkdir -p "${BASE}/run"
ticket_lock_acquire "$LOCK" "${TICKET_SCREEN_STOP_LOCK_WAIT_SECONDS:-10}" || { echo "ticket start/stop lock remained active" >&2; exit 1; }

am start-foreground-service -n "${APP}/.app.SupervisorService" \
  -a "lv.jolkins.pixelorchestrator.action.TICKET_STOP_SERVER" \
  --es orchestrator_action ticket_stop_server >/dev/null 2>&1 || true

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
service_attempts="${TICKET_SCREEN_STOP_SERVICE_WAIT_ATTEMPTS:-100}"
while [ "$service_attempts" -gt 0 ] && ticket_service_active; do
  service_attempts=$((service_attempts - 1))
  sleep 0.2
done
if ticket_service_active; then
  echo "Ticket service did not quiesce; secure capture saved state was not touched" >&2
  exit 1
fi
SECURE_CAPTURE_RESTORE_OK=1
restore_secure_capture_state || SECURE_CAPTURE_RESTORE_OK=0
if [ "$SECURE_CAPTURE_RESTORE_OK" != 1 ]; then
  echo "Ticket secure capture settings were not exactly restored; saved state was retained" >&2
  exit 1
fi
exit 0
