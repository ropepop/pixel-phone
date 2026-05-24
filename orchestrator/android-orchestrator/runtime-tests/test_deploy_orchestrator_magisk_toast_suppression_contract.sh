#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"

require_source() {
  local needle="$1"
  local message="$2"
  if ! rg -Fq "${needle}" "${SOURCE_SCRIPT}"; then
    echo "FAIL: ${message}" >&2
    exit 1
  fi
}

require_source 'resolve_orchestrator_package_uid()' 'deploy script must resolve the installed orchestrator uid dynamically'
require_source 'cmd package list packages -U ${PKG}' 'deploy script must use package manager uid lookup first'
require_source 'dumpsys package ${PKG}' 'deploy script must keep a dumpsys uid fallback'
require_source 'suppress_superuser_grant_toasts()' 'deploy script must suppress root-manager grant toasts'
require_source 'magisk --sqlite' 'deploy script must update Magisk policy through its sqlite interface'
require_source 'INSERT OR IGNORE INTO policies(uid, policy, until, logging, notification)' 'deploy script must create an allow policy when the uid changed after reinstall'
require_source 'UPDATE policies SET notification=0 WHERE uid=${uid}' 'deploy script must disable superuser grant toasts for the orchestrator app'
require_source 'UPDATE policies SET notification=0 WHERE uid=2000' 'deploy script must disable deployment-shell grant toasts when that policy already exists'
require_source 'repair_phone_automation_permissions || true' 'deploy script must repair phone automation permissions before dispatch'
if rg -Fq 'Skipping phone automation accessibility repair for ticket_screen' "${SOURCE_SCRIPT}"; then
  echo "FAIL: ticket_screen deploy must not skip phone automation accessibility repair" >&2
  exit 1
fi

call_line="$(rg -n '^suppress_superuser_grant_toasts \|\| true$' "${SOURCE_SCRIPT}" | cut -d: -f1 | head -n1)"
repair_line="$(rg -n '^if should_repair_phone_automation_permissions; then$' "${SOURCE_SCRIPT}" | cut -d: -f1 | head -n1)"
dispatch_line="$(rg -n '^dispatch_orchestrator_action$' "${SOURCE_SCRIPT}" | cut -d: -f1 | head -n1)"

if [[ -z "${call_line}" || -z "${repair_line}" || -z "${dispatch_line}" ]]; then
  echo "FAIL: could not locate suppression, permission repair, and dispatch ordering anchors" >&2
  exit 1
fi

if (( call_line >= repair_line )); then
  echo "FAIL: superuser toast suppression must run before permission repair root commands" >&2
  exit 1
fi

if (( call_line >= dispatch_line )); then
  echo "FAIL: superuser toast suppression must run before orchestrator action dispatch" >&2
  exit 1
fi

echo "PASS: deploy_orchestrator_apk.sh suppresses Magisk superuser grant toasts before root app dispatch"
