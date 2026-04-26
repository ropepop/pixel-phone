#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SSH_STOP_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ssh-stop.sh"

if ! rg -Fq '/data/local/pixel-stack/ssh/bin/dropbear' "${SSH_STOP_SCRIPT}"; then
  echo "FAIL: missing /data/local dropbear cleanup pattern in ${SSH_STOP_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq '/data/adb/pixel-stack/ssh/bin/dropbear' "${SSH_STOP_SCRIPT}"; then
  echo "FAIL: missing /data/adb dropbear cleanup pattern in ${SSH_STOP_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'for base in "${BASE_LOCAL}" "${BASE_LEGACY}"; do' "${SSH_STOP_SCRIPT}"; then
  echo "FAIL: missing dual-base cleanup loop in ${SSH_STOP_SCRIPT}" >&2
  exit 1
fi

echo "PASS: pixel-ssh-stop cleans both /data/local and /data/adb ssh runtime paths"
