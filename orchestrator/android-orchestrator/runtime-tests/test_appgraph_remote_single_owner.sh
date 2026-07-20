#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPONENT_REGISTRY_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/component-registry.json"

if [[ ! -f "${COMPONENT_REGISTRY_FILE}" ]]; then
  echo "FAIL: missing component registry file ${COMPONENT_REGISTRY_FILE}" >&2
  exit 1
fi

if rg -q '"id"[[:space:]]*:[[:space:]]*"(dns|remote)"|pixel-dns-(start|stop)\.sh' "${COMPONENT_REGISTRY_FILE}"; then
  echo "FAIL: retired DNS or remote still has a runtime owner in ${COMPONENT_REGISTRY_FILE}" >&2
  exit 1
fi

echo "PASS: retired DNS and remote have no active component owner"
