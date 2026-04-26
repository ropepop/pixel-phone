#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
HEALTH_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-satiksme-health.sh"
MODULE_MANIFEST="${REPO_ROOT}/module.yaml"
MODULE_REGISTRY="${REPO_ROOT}/modules/registry/modules.yaml"
COMPONENT_REGISTRY="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/component-registry.json"

if ! rg -Fq 'emit "satiksme_bot_pid" "${satiksme_pid}"' "${HEALTH_SCRIPT}"; then
  echo "FAIL: satiksme health script no longer reports satiksme_bot_pid" >&2
  exit 1
fi

if ! rg -Fq 'emit "heartbeat_age_sec" "${heartbeat_age}"' "${HEALTH_SCRIPT}"; then
  echo "FAIL: satiksme health script no longer reports heartbeat age" >&2
  exit 1
fi

if ! rg -Fq 'emit "failure_reason" "${failure_reason}"' "${HEALTH_SCRIPT}"; then
  echo "FAIL: satiksme health script no longer reports failure_reason" >&2
  exit 1
fi

if ! rg -Fq 'failure_reason="tunnel_supervisor_missing"' "${HEALTH_SCRIPT}"; then
  echo "FAIL: satiksme health script no longer detects missing tunnel supervisor pid" >&2
  exit 1
fi

if ! rg -Fq 'failure_reason="public_root_failed"' "${HEALTH_SCRIPT}"; then
  echo "FAIL: satiksme health script no longer detects public root failures" >&2
  exit 1
fi

if ! rg -Fq 'failure_reason="public_app_failed"' "${HEALTH_SCRIPT}"; then
  echo "FAIL: satiksme health script no longer detects public app failures" >&2
  exit 1
fi

if ! rg -Fq 'health: sh /data/local/pixel-stack/bin/pixel-satiksme-health.sh' "${MODULE_MANIFEST}"; then
  echo "FAIL: orchestrator module manifest no longer uses satiksme rooted health script" >&2
  exit 1
fi

if ! rg -Fq 'health_command: sh /data/local/pixel-stack/bin/pixel-satiksme-health.sh' "${MODULE_REGISTRY}"; then
  echo "FAIL: module registry no longer uses satiksme rooted health script" >&2
  exit 1
fi

if ! rg -Fq '"healthCommand": "sh /data/local/pixel-stack/bin/pixel-satiksme-health.sh"' "${COMPONENT_REGISTRY}"; then
  echo "FAIL: component registry no longer uses satiksme rooted health script" >&2
  exit 1
fi

echo "PASS: satiksme rooted health contract is present across runtime assets and metadata"
