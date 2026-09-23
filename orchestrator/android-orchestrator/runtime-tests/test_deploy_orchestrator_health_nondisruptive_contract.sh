#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="${ROOT}/scripts/android/deploy_orchestrator_apk.sh"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT

sed -n '/^process_prepare_started_ms=/,/^record_phase_timing process_prepare /p' "${SOURCE}" > "${TEST_DIR}/process-prepare.sh"
[[ -s "${TEST_DIR}/process-prepare.sh" ]]

run_case() (
  PROFILE="$1"
  ACTION="$2"
  PKG=lv.jolkins.pixelorchestrator
  : > "${TEST_DIR}/commands"
  now_ms() { printf '0\n'; }
  record_phase_timing() { :; }
  pixel_transport_shell() { printf '%s\n' "$1" >> "${TEST_DIR}/commands"; }
  source "${TEST_DIR}/process-prepare.sh" >/dev/null
)

for action in health health_component; do
  run_case standard "${action}"
  if [[ -s "${TEST_DIR}/commands" ]]; then
    echo "FAIL: standard ${action} interrupted the orchestrator or reset its logs" >&2
    exit 1
  fi
done

run_case standard redeploy_component
[[ "$(wc -l < "${TEST_DIR}/commands" | tr -d ' ')" == "2" ]]
rg -Fq 'am force-stop lv.jolkins.pixelorchestrator' "${TEST_DIR}/commands"
rg -Fq 'logcat -c' "${TEST_DIR}/commands"

run_case fast redeploy_component
[[ ! -s "${TEST_DIR}/commands" ]]

echo 'PASS: health checks preserve the orchestrator process and logs; standard redeploy still resets them'
