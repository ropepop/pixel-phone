#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
LOOP_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-service-loop"

for symbol in 'prepare_runtime()' 'launch_adguardhome_core()' 'launch_remote_frontend()' '--prepare-runtime' '--launch-core' '--launch-frontend'; do
  if ! rg -Fq -- "${symbol}" "${START_FILE}"; then
    echo "FAIL: missing split runtime symbol ${symbol} in ${START_FILE}" >&2
    exit 1
  fi
done

for symbol in 'PREPARE_BIN="/usr/local/bin/adguardhome-render-config"' \
              'LAUNCH_CORE_BIN="/usr/local/bin/adguardhome-launch-core"' \
              'LAUNCH_FRONTEND_BIN="/usr/local/bin/adguardhome-launch-frontend"' \
              'record_runtime_restart "core-healthcheck-failed"' \
              'record_runtime_restart "core-process-exited"'; do
  if ! rg -Fq -- "${symbol}" "${LOOP_FILE}"; then
    echo "FAIL: missing service-loop split supervision symbol ${symbol} in ${LOOP_FILE}" >&2
    exit 1
  fi
done

echo "PASS: runtime split contract keeps config/core/frontend roles explicit and resolver supervision separate"
