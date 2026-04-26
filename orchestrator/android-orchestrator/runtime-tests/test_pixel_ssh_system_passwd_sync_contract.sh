#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SSH_LAUNCH_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-launch.sh"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"
RUNTIME_INSTALLER_FILE="${REPO_ROOT}/android-orchestrator/runtime-installer/src/main/kotlin/lv/jolkins/pixelorchestrator/runtimeinstaller/RuntimeInstaller.kt"

if ! rg -Fq 'sync_system_passwd() {' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing system passwd sync helper in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'cat "${PASSWD_FILE}" > /system/etc/passwd' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing direct system passwd repair fallback in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'system passwd hash mismatch after synchronization' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing verification failure message in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'expected_hash=' "${FACADE_FILE}" || ! rg -Fq 'chmod 0644 /system/etc/passwd' "${FACADE_FILE}"; then
  echo "FAIL: missing system passwd verification-and-repair logic in ${FACADE_FILE}" >&2
  exit 1
fi

if ! rg -Fq 'expected_hash=' "${RUNTIME_INSTALLER_FILE}" || ! rg -Fq 'chmod 0644 /system/etc/passwd' "${RUNTIME_INSTALLER_FILE}"; then
  echo "FAIL: missing system passwd verification-and-repair logic in ${RUNTIME_INSTALLER_FILE}" >&2
  exit 1
fi

echo "PASS: SSH runtime install and launch paths repair /system/etc/passwd drift"
