#!/system/bin/sh

TICKET_LOCK_HELD=0

ticket_lock_run_bounded() {
  if [ -n "${TICKET_LOCK_TIMEOUT_BIN:-}" ]; then
    "$TICKET_LOCK_TIMEOUT_BIN" 1 "$@"
  elif command -v timeout >/dev/null 2>&1; then
    timeout 1 "$@"
  elif [ -x /system/bin/timeout ]; then
    /system/bin/timeout 1 "$@"
  else
    return 125
  fi
}

ticket_lock_owner_active() {
  owner="$1"
  proc_root="${TICKET_LOCK_PROC_ROOT:-/proc}"
  case "$owner" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "$owner" >/dev/null 2>&1 || return 1
  [ -r "$proc_root/$owner/cmdline" ] || return 0
  if owner_command="$(ticket_lock_run_bounded tr '\000' ' ' < "$proc_root/$owner/cmdline" 2>/dev/null)"; then
    case "$owner_command" in
      *pixel-ticket-start.sh*|*pixel-ticket-stop.sh*) return 0 ;;
      *) return 1 ;;
    esac
  fi
  kill -0 "$owner" >/dev/null 2>&1 || return 1
  return 0
}

ticket_lock_release() {
  [ "$TICKET_LOCK_HELD" = 1 ] || return 0
  [ "$(sed -n '1p' "$TICKET_LOCK_OWNER" 2>/dev/null || true)" = "$$" ] || return 0
  rm -f "$TICKET_LOCK_OWNER" && rmdir "$TICKET_LOCK_DIR" 2>/dev/null || true
  TICKET_LOCK_HELD=0
}

ticket_lock_try() {
  if ! mkdir "$TICKET_LOCK_DIR" 2>/dev/null; then
    sleep 0.1
    before="$(sed -n '1p' "$TICKET_LOCK_OWNER" 2>/dev/null || true)"
    ticket_lock_owner_active "$before" && return 1
    [ "$before" = "$(sed -n '1p' "$TICKET_LOCK_OWNER" 2>/dev/null || true)" ] || return 1
    rm -f "$TICKET_LOCK_OWNER" 2>/dev/null || return 1
    rmdir "$TICKET_LOCK_DIR" 2>/dev/null || return 1
    mkdir "$TICKET_LOCK_DIR" 2>/dev/null || return 1
  fi
  printf '%s\n' "$$" > "$TICKET_LOCK_OWNER" || { rmdir "$TICKET_LOCK_DIR" 2>/dev/null; return 1; }
  TICKET_LOCK_HELD=1
  trap ticket_lock_release EXIT
  trap 'ticket_lock_release; exit 143' HUP INT TERM
}

ticket_lock_acquire() {
  TICKET_LOCK_DIR="$1"
  TICKET_LOCK_OWNER="${TICKET_LOCK_DIR}/owner.pid"
  seconds="$2"; case "$seconds" in ''|*[!0-9]*) seconds=10 ;; esac
  attempts=$((seconds * 5)); [ "$attempts" -gt 0 ] || attempts=1
  while [ "$attempts" -gt 0 ]; do
    ticket_lock_try && return 0
    attempts=$((attempts - 1)); sleep 0.2
  done
  return 1
}
