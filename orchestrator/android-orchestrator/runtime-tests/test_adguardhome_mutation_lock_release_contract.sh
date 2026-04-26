#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
START_FILE="${ROOT}/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

if ! rg -Fq 'release_mutation_lock' "${START_FILE}"; then
  echo "FAIL: adguardhome-start never releases the mutation lock" >&2
  exit 1
fi

if rg -Fq 'Monitoring AdGuardHome liveness via pid polling' "${START_FILE}"; then
  echo "FAIL: adguardhome-start still contains long-lived pid polling" >&2
  exit 1
fi

if rg -Fq 'while pid_is_running "${adguard_pid}"' "${START_FILE}"; then
  echo "FAIL: adguardhome-start still loops on the AdGuardHome pid instead of exiting after launch" >&2
  exit 1
fi

if ! rg -Fq 'prepare-runtime' "${START_FILE}" || ! rg -Fq 'launch-core' "${START_FILE}" || ! rg -Fq 'launch-frontend' "${START_FILE}"; then
  echo "FAIL: adguardhome-start is missing the split runtime modes" >&2
  exit 1
fi

echo "PASS: AdGuardHome startup is one-shot and keeps mutation locking out of the runtime lifetime"
