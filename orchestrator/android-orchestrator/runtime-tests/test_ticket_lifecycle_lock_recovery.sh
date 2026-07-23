#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="${ROOT}/app/src/main/assets/runtime/entrypoints/pixel-ticket-lifecycle-lock.sh"
TMP="$(mktemp -d)"
PIDS=()
cleanup() {
  for pid in "${PIDS[@]}"; do kill "$pid" >/dev/null 2>&1 || true; done
  for pid in "${PIDS[@]}"; do wait "$pid" >/dev/null 2>&1 || true; done
  rm -rf "${TMP}"
}
restore_cleanup_traps() {
  trap cleanup EXIT
  trap 'cleanup; exit 143' HUP INT TERM
}
restore_cleanup_traps

cat > "${TMP}/timeout-pass" <<'EOF'
#!/usr/bin/env bash
shift
exec "$@"
EOF
cat > "${TMP}/timeout-fail" <<'EOF'
#!/usr/bin/env bash
exit 124
EOF
cat > "${TMP}/timeout-real" <<'EOF'
#!/usr/bin/env python3
import os
import signal
import subprocess
import sys

process = subprocess.Popen(sys.argv[2:], start_new_session=True)
try:
  raise SystemExit(process.wait(timeout=float(sys.argv[1])))
except subprocess.TimeoutExpired:
  os.killpg(process.pid, signal.SIGKILL)
  process.wait()
  raise SystemExit(124)
EOF
chmod +x "${TMP}/timeout-pass" "${TMP}/timeout-fail" "${TMP}/timeout-real"

# shellcheck source=/dev/null
. "${HELPER}"
LOCK="${TMP}/ticket.lock"
mkdir "${LOCK}"
printf '99999999\n' > "${LOCK}/owner.pid"

ticket_lock_acquire "${LOCK}" 1
restore_cleanup_traps
[[ "$(<"${LOCK}/owner.pid")" == "$$" ]]
ticket_lock_release
[[ ! -e "${LOCK}" ]]

cat > "${TMP}/pixel-ticket-start.sh" <<'EOF'
#!/usr/bin/env bash
sleep 30
EOF
chmod +x "${TMP}/pixel-ticket-start.sh"
"${TMP}/pixel-ticket-start.sh" &
active_owner=$!
PIDS+=("${active_owner}")
sleep 0.1

TICKET_LOCK_TIMEOUT_BIN="${TMP}/timeout-pass"
TICKET_LOCK_PROC_ROOT="${TMP}/proc"
mkdir -p "${TICKET_LOCK_PROC_ROOT}/${active_owner}"
printf 'bash\000%s/pixel-ticket-start.sh\000' "${TMP}" > "${TICKET_LOCK_PROC_ROOT}/${active_owner}/cmdline"
mkdir "${LOCK}"
printf '%s\n' "${active_owner}" > "${LOCK}/owner.pid"
if ticket_lock_acquire "${LOCK}" 1; then
  echo "FAIL: a verified live Ticket lifecycle owner was replaced" >&2
  exit 1
fi
[[ "$(<"${LOCK}/owner.pid")" == "${active_owner}" ]]

rm -f "${TICKET_LOCK_PROC_ROOT}/${active_owner}/cmdline"
mkfifo "${TICKET_LOCK_PROC_ROOT}/${active_owner}/cmdline"
( exec 3> "${TICKET_LOCK_PROC_ROOT}/${active_owner}/cmdline"; sleep 10 ) &
blocked_writer=$!
PIDS+=("${blocked_writer}")
TICKET_LOCK_TIMEOUT_BIN="${TMP}/timeout-real"
SECONDS=0
ticket_lock_owner_active "${active_owner}"
[[ "${SECONDS}" -le 2 ]]
kill "${blocked_writer}" >/dev/null 2>&1 || true
rm -f "${TICKET_LOCK_PROC_ROOT}/${active_owner}/cmdline"
printf 'bash\000%s/pixel-ticket-start.sh\000' "${TMP}" > "${TICKET_LOCK_PROC_ROOT}/${active_owner}/cmdline"

TICKET_LOCK_TIMEOUT_BIN="${TMP}/timeout-fail"
if ticket_lock_acquire "${LOCK}" 1; then
  echo "FAIL: an unreadable live lifecycle owner was replaced" >&2
  exit 1
fi
[[ "$(<"${LOCK}/owner.pid")" == "${active_owner}" ]]

TICKET_LOCK_TIMEOUT_BIN="${TMP}/timeout-pass"
mkdir -p "${TICKET_LOCK_PROC_ROOT}/$$"
printf 'bash\000unrelated-script.sh\000' > "${TICKET_LOCK_PROC_ROOT}/$$/cmdline"
printf '%s\n' "$$" > "${LOCK}/owner.pid"
ticket_lock_acquire "${LOCK}" 1
restore_cleanup_traps
[[ "$(<"${LOCK}/owner.pid")" == "$$" ]]
ticket_lock_release
[[ ! -e "${LOCK}" ]]

if rg -Fq 'rm -rf' "${HELPER}"; then
  echo "FAIL: lifecycle lock helper recursively deletes contested state" >&2
  exit 1
fi
cleanup
trap - EXIT HUP INT TERM
echo "PASS: Ticket lifecycle owner reads are bounded, live owners are protected, and stale locks recover narrowly"
