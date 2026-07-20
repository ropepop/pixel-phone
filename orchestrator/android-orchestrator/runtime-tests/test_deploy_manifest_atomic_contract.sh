#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"

for required in \
  'cleanup_remote_deploy_staging' \
  '/data/local/tmp/pixel-orchestrator-runtime-${PIXEL_RUN_ID}' \
  '/data/local/tmp/pixel-orchestrator-component-release-${PIXEL_RUN_ID}' \
  '${target_root}/.runtime-manifest.${PIXEL_RUN_ID}.tmp' \
  '${target_root}/runtime-manifest.previous.json' \
  '${device_component_root}/release-manifest.previous.json' \
  '${target_root}/runtime-manifest.json'; do
  if ! rg -Fq "${required}" "${DEPLOY_SCRIPT}"; then
    echo "FAIL: deploy script missing atomic staging contract fragment: ${required}" >&2
    exit 1
  fi
done

copy_line="$(rg -n -F '"${target_root}/.runtime-manifest.${PIXEL_RUN_ID}.tmp"' "${DEPLOY_SCRIPT}" | head -n1 | cut -d: -f1)"
verify_line="$(rg -n -F 'Runtime artifact failed canonical checksum verification' "${DEPLOY_SCRIPT}" | head -n1 | cut -d: -f1)"
activate_line="$(rg -n -F '"${target_root}/runtime-manifest.json"' "${DEPLOY_SCRIPT}" | tail -n1 | cut -d: -f1)"

if (( copy_line >= verify_line || verify_line >= activate_line )); then
  echo "FAIL: runtime manifest must be staged, artifacts verified, then atomically activated" >&2
  exit 1
fi

if rg -Fq 'remote) printf '\''dns\n' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: retired remote component is still aliased to DNS release ownership" >&2
  exit 1
fi

echo "PASS: deployment stages manifests atomically and cleans interrupted staging"
