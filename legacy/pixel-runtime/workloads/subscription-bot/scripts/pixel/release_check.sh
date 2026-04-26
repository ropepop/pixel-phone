#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "${REPO_ROOT}/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-${DEFAULT_ORCHESTRATOR_REPO}}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"

run_orchestrator() {
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  cmd+=(--skip-build)
  if [[ -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    cmd+=(--config-file "${ORCHESTRATOR_CONFIG_FILE}")
  fi
  cmd+=("$@")
  "${cmd[@]}"
}

ensure_device
ensure_root
ensure_local_env

run_orchestrator \
  --subscription-bot-env-file "${REPO_ROOT}/.env" \
  --action health_component \
  --component subscription_bot
