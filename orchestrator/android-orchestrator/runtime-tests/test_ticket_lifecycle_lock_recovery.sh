#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELPER="${ROOT}/app/src/main/assets/runtime/entrypoints/pixel-ticket-lifecycle-lock.sh"
TMP="$(mktemp -d)"
PIDS=()
cleanup() {
  for pid in "${PIDS[@]}"; do kill "$pid" >/dev/null 2>&1 || true; done
  rm -rf "${TMP}"
}
trap cleanup EXIT

cat > "${TMP}/timeout-pass" <<'EOF'
#!/usr/bin/env bash
shift
exec "$@"
EOF
cat > "${TMP}/timeout-fail" <<'EOF'
#!/usr/bin/env bash
exit 124
EOF
chmod +x "${TMP}/timeout-pass" "${TMP}/timeout-fail"

# shellcheck source=/dev/null
. "${HELPER}"
LOCK="${TMP}/ticket.lock"
mkdir "${LOCK}"
printf '99999999\n' > "${LOCK}/owner.pid"

ticket_lock_acquire "${LOCK}" 1
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
mkdir "${LOCK}"
printf '%s\n' "${active_owner}" > "${LOCK}/owner.pid"
if ticket_lock_acquire "${LOCK}" 1; then
  echo "FAIL: a verified live Ticket lifecycle owner was replaced" >&2
  exit 1
fi
[[ "$(<"${LOCK}/owner.pid")" == "${active_owner}" ]]

TICKET_LOCK_TIMEOUT_BIN="${TMP}/timeout-fail"
if ticket_lock_acquire "${LOCK}" 1; then
  echo "FAIL: an unreadable live lifecycle owner was replaced" >&2
  exit 1
fi
[[ "$(<"${LOCK}/owner.pid")" == "${active_owner}" ]]

TICKET_LOCK_TIMEOUT_BIN="${TMP}/timeout-pass"
printf '%s\n' "$$" > "${LOCK}/owner.pid"
ticket_lock_acquire "${LOCK}" 1
[[ "$(<"${LOCK}/owner.pid")" == "$$" ]]
ticket_lock_release
[[ ! -e "${LOCK}" ]]

if rg -Fq 'rm -rf' "${HELPER}"; then
  echo "FAIL: lifecycle lock helper recursively deletes contested state" >&2
  exit 1
fi
rm -rf "${TMP}"
trap - EXIT
echo "PASS: Ticket lifecycle owner reads are bounded, live owners are protected, and stale locks recover narrowly"
