#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SSH_START_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ssh-start.sh"

if ! rg -Fq 'if [ -f "${PID_FILE}" ]; then' "${SSH_START_SCRIPT}"; then
  echo "FAIL: missing PID guard in ${SSH_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'if [ -n "${old_pid}" ] && kill -0 "${old_pid}" >/dev/null 2>&1; then' "${SSH_START_SCRIPT}"; then
  echo "FAIL: missing running PID check in ${SSH_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'exit 0' "${SSH_START_SCRIPT}"; then
  echo "FAIL: missing idempotent fast-exit in ${SSH_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'nohup env PIXEL_SSH_ROOT="${BASE}" "${LOOP_BIN}"' "${SSH_START_SCRIPT}"; then
  echo "FAIL: missing explicit PIXEL_SSH_ROOT launch env in ${SSH_START_SCRIPT}" >&2
  exit 1
fi

echo "PASS: pixel-ssh-start includes PID-based idempotent launch guard"
