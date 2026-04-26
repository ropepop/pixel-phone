#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WATCHDOG_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-remote-watchdog"

if ! rg -Fq 'PIHOLE_REMOTE_WATCHDOG_ESCALATE_RUNTIME="${PIHOLE_REMOTE_WATCHDOG_ESCALATE_RUNTIME:-0}"' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog escalation runtime toggle default is missing" >&2
  exit 1
fi

if ! rg -Fq 'if ! is_true "${PIHOLE_REMOTE_WATCHDOG_ESCALATE_RUNTIME}"; then' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog escalation gate condition is missing" >&2
  exit 1
fi

if ! rg -Fq '"${PIHOLE_START_BIN}" --remote-reload-frontend >/dev/null 2>&1 || true' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog no longer uses frontend-only reload for targeted recovery" >&2
  exit 1
fi

if rg -Fq '"${PIHOLE_START_BIN}" --remote-restart >/dev/null 2>&1 || true' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog still uses full remote restart as the targeted recovery step" >&2
  exit 1
fi

if ! rg -Fq '"${PIHOLE_START_BIN}" --runtime-restart-services >/dev/null 2>&1 || true' "${WATCHDOG_FILE}"; then
  echo "FAIL: runtime escalation command missing (gate cannot be validated)" >&2
  exit 1
fi

if ! rg -Fq 'frontend reload recovered remote listeners' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog does not log successful frontend-only recovery" >&2
  exit 1
fi

if ! rg -Fq 'frontend reload failed; runtime escalation disabled' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog does not log disabled escalation after frontend reload failure" >&2
  exit 1
fi

if ! rg -Fq 'frontend reload failed; escalating to sidecar runtime restart' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog does not log escalation after frontend reload failure" >&2
  exit 1
fi

if ! rg -Fq 'runtime escalation disabled' "${WATCHDOG_FILE}"; then
  echo "FAIL: watchdog does not log disabled escalation state" >&2
  exit 1
fi

echo "PASS: watchdog uses frontend-only recovery first and escalates to runtime restart only after failure"
