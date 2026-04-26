#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
HEALTH_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-notifier-health.sh"
WORKLOAD_MANIFEST="${REPO_ROOT}/../workloads/site-notifications/module.yaml"
MODULE_MANIFEST="${REPO_ROOT}/module.yaml"
MODULE_REGISTRY="${REPO_ROOT}/modules/registry/modules.yaml"
COMPONENT_REGISTRY="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/component-registry.json"

if ! rg -Fq 'exec "${PYTHON_BIN}" "${ENTRY_SCRIPT}" healthcheck' "${HEALTH_SCRIPT}"; then
  echo "FAIL: site notifier health script no longer runs the bundled app healthcheck" >&2
  exit 1
fi

if ! rg -Fq 'echo "unhealthy: missing_env_file"' "${HEALTH_SCRIPT}"; then
  echo "FAIL: site notifier health script no longer reports missing env files clearly" >&2
  exit 1
fi

if ! rg -Fq 'healthcheck: sh /data/local/pixel-stack/bin/pixel-notifier-health.sh' "${WORKLOAD_MANIFEST}"; then
  echo "FAIL: workload manifest no longer uses notifier health helper" >&2
  exit 1
fi

if ! rg -Fq 'health: sh /data/local/pixel-stack/bin/pixel-notifier-health.sh' "${WORKLOAD_MANIFEST}"; then
  echo "FAIL: workload component manifest no longer uses notifier health helper" >&2
  exit 1
fi

if ! rg -Fq 'health: sh /data/local/pixel-stack/bin/pixel-notifier-health.sh' "${MODULE_MANIFEST}"; then
  echo "FAIL: orchestrator module manifest no longer uses notifier health helper" >&2
  exit 1
fi

if ! rg -Fq 'health_command: sh /data/local/pixel-stack/bin/pixel-notifier-health.sh' "${MODULE_REGISTRY}"; then
  echo "FAIL: module registry no longer uses notifier health helper" >&2
  exit 1
fi

if ! rg -Fq '"healthCommand": "sh /data/local/pixel-stack/bin/pixel-notifier-health.sh"' "${COMPONENT_REGISTRY}"; then
  echo "FAIL: component registry no longer uses notifier health helper" >&2
  exit 1
fi

echo "PASS: site notifier health helper contract is present across manifests and runtime metadata"
