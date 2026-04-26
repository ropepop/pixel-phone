#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SSH_LAUNCH_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-launch.sh"

if ! rg -Fq ': "${SSH_ALLOW_KEY_AUTH:=1}"' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing SSH_ALLOW_KEY_AUTH setting in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'if [ "${SSH_ALLOW_KEY_AUTH}" = "1" ]; then' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing key auth gating logic in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'AUTH_KEYS_ARG="-D ${AUTH_KEYS_DST_DIR}"' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing dynamic authorized_keys argument in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'invalid SSH auth configuration: password and key auth are both disabled' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing invalid auth-mode guard in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'resolved_auth_mode="password_only"' "${SSH_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing password-only auth mode reporting in ${SSH_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

echo "PASS: pixel-ssh-launch supports password-only auth mode"
