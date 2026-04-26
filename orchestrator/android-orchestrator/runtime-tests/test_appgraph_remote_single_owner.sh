#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPONENT_REGISTRY_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/component-registry.json"

if [[ ! -f "${COMPONENT_REGISTRY_FILE}" ]]; then
  echo "FAIL: missing component registry file ${COMPONENT_REGISTRY_FILE}" >&2
  exit 1
fi

remote_block="$(awk '
  /"id":[[:space:]]*"remote"/ {capture=1}
  capture {print}
  capture && /"healthCommand":/ {exit}
' "${COMPONENT_REGISTRY_FILE}")"

if [[ -z "${remote_block}" ]]; then
  echo "FAIL: unable to locate remote component block in ${COMPONENT_REGISTRY_FILE}" >&2
  exit 1
fi

if [[ "${remote_block}" != *'"startCommand": "true"'* ]]; then
  echo "FAIL: remote startCommand is not noop=true in ${COMPONENT_REGISTRY_FILE}" >&2
  exit 1
fi

if [[ "${remote_block}" != *'"stopCommand": "true"'* ]]; then
  echo "FAIL: remote stopCommand is not noop=true in ${COMPONENT_REGISTRY_FILE}" >&2
  exit 1
fi

count_dns_start="$(rg -F '"startCommand": "sh /data/local/pixel-stack/bin/pixel-dns-start.sh"' "${COMPONENT_REGISTRY_FILE}" | wc -l | tr -d '[:space:]' || true)"
if [[ "${count_dns_start}" != "1" ]]; then
  echo "FAIL: expected exactly one pixel-dns-start owner after consolidation, found ${count_dns_start}" >&2
  exit 1
fi

echo "PASS: component registry keeps remote noop ownership and DNS start has a single owner"
