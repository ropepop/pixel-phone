#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

launch_core_block="$(sed -n '/^launch_adguardhome_core()/,/^}/p' "${START_FILE}")"

if [[ -z "${launch_core_block}" ]]; then
  echo "FAIL: missing launch_adguardhome_core() in ${START_FILE}" >&2
  exit 1
fi

if ! printf '%s\n' "${launch_core_block}" | rg -Fq 'adguard_dot_port="$(effective_adguard_dot_port)"'; then
  echo "FAIL: launch_adguardhome_core does not derive the effective DoT port" >&2
  exit 1
fi

if ! printf '%s\n' "${launch_core_block}" | rg -Fq 'tcp_listener_local "${adguard_dot_port}"'; then
  echo "FAIL: launch_adguardhome_core does not wait for the effective AdGuard DoT port" >&2
  exit 1
fi

if ! printf '%s\n' "${launch_core_block}" | rg -Fq 'echo_log "AdGuardHome DoT listener failed to appear on :${adguard_dot_port}"'; then
  echo "FAIL: launch_adguardhome_core failure logging does not reference the effective AdGuard DoT port" >&2
  exit 1
fi

echo "PASS: launch_adguardhome_core validates DoT readiness against the effective AdGuard listener port"
