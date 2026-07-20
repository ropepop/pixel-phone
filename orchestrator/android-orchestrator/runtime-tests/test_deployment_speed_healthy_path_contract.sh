#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ENTRY_DIR="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

line_of() {
  rg -Fn -- "$1" "$2" | cut -d: -f1 | head -n1
}

assert_before() {
  local first="$1" second="$2" file="$3"
  local first_line second_line
  first_line="$(line_of "${first}" "${file}" || true)"
  second_line="$(line_of "${second}" "${file}" || true)"
  [[ -n "${first_line}" && -n "${second_line}" && "${first_line}" -lt "${second_line}" ]] || \
    fail "expected ${first} before ${second} in ${file}"
}

SSH_START="${ENTRY_DIR}/pixel-ssh-start.sh"
SSH_STOP="${ENTRY_DIR}/pixel-ssh-stop.sh"
VPN_START="${ENTRY_DIR}/pixel-vpn-start.sh"
VPN_STOP="${ENTRY_DIR}/pixel-vpn-stop.sh"
MANAGEMENT_HEALTH="${ENTRY_DIR}/pixel-management-health.sh"
TICKET_START="${ENTRY_DIR}/pixel-ticket-start.sh"
TICKET_STOP="${ENTRY_DIR}/pixel-ticket-stop.sh"
TICKET_LOCK="${ENTRY_DIR}/pixel-ticket-lifecycle-lock.sh"

for file in "${SSH_START}" "${SSH_STOP}" "${VPN_START}" "${VPN_STOP}" "${MANAGEMENT_HEALTH}" "${TICKET_START}" "${TICKET_STOP}" "${TICKET_LOCK}"; do
  bash -n "${file}" || fail "shell syntax check failed for ${file}"
done

rg -Fq 'cmp -s "${TPL_LAUNCH}" "${LAUNCH_BIN}"' "${SSH_START}" || fail "SSH start rewrites unchanged launch input"
rg -Fq 'cmp -s "${TPL_LOOP}" "${LOOP_BIN}"' "${SSH_START}" || fail "SSH start rewrites unchanged loop input"
rg -Fq 'choose_canonical_loop_pid' "${SSH_START}" || fail "SSH start lacks canonical owner selection"
rg -Fq 'prune_duplicate_loops' "${SSH_START}" || fail "SSH start lacks duplicate-loop reconciliation"
rg -Fq 'pid_matches_target' "${SSH_START}" || fail "SSH start trusts unverified PID files"
rg -Fq 'while [ "${attempts}" -lt 20 ]; do' "${SSH_START}" || fail "SSH start lacks bounded process wait"
rg -Fq 'owned_pid' "${SSH_STOP}" || fail "SSH stop lacks process ownership checks"
rg -Fq 'while [ "${attempt}" -lt 20 ]; do' "${SSH_STOP}" || fail "SSH stop lacks one bounded shutdown poll"

assert_before 'runtime_inputs_current && [ -x "${HEALTH_BIN}" ]' 'if [ ! -f "${CONF_SRC}" ]; then' "${VPN_START}"
rg -Fq 'same_file "${CONF_SRC}" "${CONF_DIR}/tailscale.env"' "${VPN_START}" || fail "VPN start lacks config freshness"
rg -Fq 'same_file "${TPL_LAUNCH}" "${LAUNCH_BIN}"' "${VPN_START}" || fail "VPN start lacks launch freshness"
rg -Fq 'same_file "${TPL_LOOP}" "${LOOP_BIN}"' "${VPN_START}" || fail "VPN start lacks loop freshness"
rg -Fq 'while [ "${attempt}" -lt 40 ]; do' "${VPN_START}" || fail "VPN start lacks bounded readiness polling"
rg -Fq 'owned_pid' "${VPN_STOP}" || fail "VPN stop lacks process ownership checks"
rg -Fq 'while [ "${attempt}" -lt 20 ]; do' "${VPN_STOP}" || fail "VPN stop lacks one bounded shutdown poll"

assert_before 'public_ipv4_candidate="${ddns_published_ipv4}"' 'if [ "${DEEP_MODE}" = "1" ]; then' "${MANAGEMENT_HEALTH}"
rg -Fq 'management_health_mode' "${MANAGEMENT_HEALTH}" || fail "management health does not report local/deep mode"
rg -Fq -- '--deep|--full' "${MANAGEMENT_HEALTH}" || fail "management health lacks explicit deep mode"

if rg -Fq 'rm -rf' "${TICKET_START}" || rg -Fq 'rm -rf' "${TICKET_STOP}" || rg -Fq 'rm -rf' "${TICKET_LOCK}"; then
  fail "ticket lifecycle scripts recursively remove a contested lock"
fi
rg -Fq 'inputs_current()' "${TICKET_START}" || fail "Ticket start lacks runtime input freshness checks"
rg -Fq 'ready && inputs_current' "${TICKET_START}" || fail "Ticket fast path can bypass changed runtime inputs"
rg -Fq 'ticket_lock_acquire "$LOCK"' "${TICKET_START}" || fail "Ticket start lacks lifecycle lock acquisition"
rg -Fq 'ticket_lock_acquire "$LOCK"' "${TICKET_STOP}" || fail "Ticket stop lacks lifecycle lock acquisition"
rg -Fq 'TICKET_LOCK_OWNER="${TICKET_LOCK_DIR}/owner.pid"' "${TICKET_LOCK}" || fail "Ticket lock lacks owner PID"
rg -Fq 'ticket_lock_owner_active' "${TICKET_LOCK}" || fail "Ticket lock lacks stale-owner proof"
rg -Fq 'rmdir "$TICKET_LOCK_DIR"' "${TICKET_LOCK}" || fail "Ticket lock lacks narrow stale cleanup"
if rg -Fq 'cloudflared' "${TICKET_START}" || rg -Fq 'ticket-web-tunnel' "${TICKET_STOP}"; then
  fail "Ticket lifecycle still owns the retired Pixel tunnel"
fi

echo "PASS: active SSH, VPN, management, and Ticket paths retain freshness, bounded waits, ownership, and stale-safe locks"
