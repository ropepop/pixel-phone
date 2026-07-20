#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"

help_output="$(bash "${DEPLOY_SCRIPT}" --help)"
grep -Fq -- '--enable-ticket-service' <<<"${help_output}" || {
  echo "FAIL: deploy help is missing --enable-ticket-service" >&2
  exit 1
}

assert_rejected() {
  local output=""
  local rc=0
  set +e
  output="$(bash "${DEPLOY_SCRIPT}" "$@" 2>&1)"
  rc=$?
  set -e
  if (( rc != 2 )); then
    echo "FAIL: invalid Ticket enable invocation returned ${rc}, expected 2" >&2
    printf '%s\n' "${output}" >&2
    exit 1
  fi
  grep -Fq -- '--enable-ticket-service is only valid with --action redeploy_component --component ticket_screen' <<<"${output}" || {
    echo "FAIL: invalid Ticket enable invocation did not fail closed" >&2
    printf '%s\n' "${output}" >&2
    exit 1
  }
}

assert_rejected --action health --enable-ticket-service
assert_rejected --action redeploy_component --component vpn --enable-ticket-service

grep -Fq 'shell_cmd="${shell_cmd} --ez orchestrator_enable_ticket_service true"' "${DEPLOY_SCRIPT}" || {
  echo "FAIL: valid Ticket redeploy does not pass the Android-owned enable request" >&2
  exit 1
}

grep -Fq 'shell_cmd="am start-foreground-service -n ${SUPERVISOR}' "${DEPLOY_SCRIPT}" || {
  echo "FAIL: Ticket enable must use the trusted direct SupervisorService dispatch" >&2
  exit 1
}

if rg -n 'am broadcast -n .*OrchestratorActionReceiver|am broadcast -n \$\{RECEIVER\}' "${DEPLOY_SCRIPT}" >/dev/null; then
  echo "FAIL: Ticket enable must not use the sender-identity-sensitive broadcast path" >&2
  exit 1
fi

if rg -n 'shared_prefs|ticket_service_settings\.xml|ticket_service_enabled.*sed|ticket_service_enabled.*echo' "${DEPLOY_SCRIPT}" >/dev/null; then
  echo "FAIL: deploy script must not edit Android SharedPreferences from shell" >&2
  exit 1
fi

echo "PASS: Ticket service enable flag is explicit, fail-closed, and app-owned"
