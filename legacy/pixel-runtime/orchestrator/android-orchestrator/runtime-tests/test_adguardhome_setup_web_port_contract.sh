#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADGUARD_START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

setup_mode_block="$(sed -n '/^adguard_setup_mode_active()/,/^}/p' "${ADGUARD_START_FILE}")"

if [[ -z "${setup_mode_block}" ]]; then
  echo "FAIL: missing adguard_setup_mode_active() helper in ${ADGUARD_START_FILE}" >&2
  exit 1
fi

if printf '%s\n' "${setup_mode_block}" | rg -Fq 'tcp_listener_local 3000'; then
  echo "FAIL: setup-mode detection still hardcodes port 3000" >&2
  exit 1
fi

if ! printf '%s\n' "${setup_mode_block}" | rg -Fq 'tcp_listener_local "${ADGUARDHOME_SETUP_WEB_PORT}"'; then
  echo "FAIL: setup-mode detection does not use ADGUARDHOME_SETUP_WEB_PORT" >&2
  exit 1
fi

echo "PASS: AdGuardHome setup-mode detection respects ADGUARDHOME_SETUP_WEB_PORT"
