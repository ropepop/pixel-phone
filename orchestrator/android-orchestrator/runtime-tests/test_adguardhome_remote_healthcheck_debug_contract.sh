#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADGUARD_START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
WATCHDOG_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-remote-watchdog"

if ! rg -Fq 'remote_stack_healthcheck_debug()' "${ADGUARD_START_FILE}"; then
  echo "FAIL: missing remote_stack_healthcheck_debug helper in ${ADGUARD_START_FILE}" >&2
  exit 1
fi

if ! rg -Fq -- '--remote-healthcheck-debug)' "${ADGUARD_START_FILE}"; then
  echo "FAIL: missing --remote-healthcheck-debug run mode in ${ADGUARD_START_FILE}" >&2
  exit 1
fi

if ! rg -Fq 'remote_healthcheck=' "${ADGUARD_START_FILE}"; then
  echo "FAIL: remote healthcheck debug output missing final status field" >&2
  exit 1
fi

if ! rg -Fq '"${PIHOLE_START_BIN}" --remote-healthcheck-debug' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog does not emit remote healthcheck diagnostics on failure" >&2
  exit 1
fi

echo "PASS: AdGuard watchdog surfaces remote healthcheck diagnostics"
