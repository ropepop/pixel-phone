#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/ops/hard-cutover-orchestrator-owners.sh"

if [[ ! -f "${SOURCE_SCRIPT}" ]]; then
  echo "FAIL: missing hard-cutover-orchestrator-owners.sh" >&2
  exit 1
fi

if ! rg -Fq 'RECEIVER="${PKG}/.app.OrchestratorActionReceiver"' "${SOURCE_SCRIPT}"; then
  echo "FAIL: hard-cutover script missing OrchestratorActionReceiver dispatch target" >&2
  exit 1
fi

if ! rg -Fq 'am broadcast -n "${RECEIVER}" --es orchestrator_action "${ACTION}"' "${SOURCE_SCRIPT}"; then
  echo "FAIL: hard-cutover script no longer broadcasts orchestrator actions" >&2
  exit 1
fi

if rg -Fq 'ACTIVITY="${PKG}/.app.MainActivity"' "${SOURCE_SCRIPT}"; then
  echo "FAIL: hard-cutover script still targets MainActivity" >&2
  exit 1
fi

echo "PASS: hard-cutover orchestrator owner cleanup uses the receiver-based command path"
