#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MAKEFILE="${REPO_ROOT}/../workloads/satiksme-bot/Makefile"
DEPLOY_SCRIPT="${REPO_ROOT}/../workloads/satiksme-bot/scripts/pixel/redeploy_release.sh"
CHECK_SCRIPT="${REPO_ROOT}/../workloads/satiksme-bot/scripts/pixel/release_check.sh"
TOOL_WRAPPER="${REPO_ROOT}/../tools/pixel/redeploy.sh"

if ! rg -U -Fq $'pixel-native-deploy:\n\t../../tools/pixel/redeploy.sh --scope satiksme_bot' "${MAKEFILE}"; then
  echo "FAIL: satiksme-bot Makefile pixel-native-deploy target missing canonical orchestrator wrapper" >&2
  exit 1
fi

if ! rg -U -Fq $'pixel-deploy:\n\t@echo "pixel-deploy retired: use ../../tools/pixel/redeploy.sh --scope satiksme_bot."' "${MAKEFILE}"; then
  echo "FAIL: satiksme-bot Makefile pixel-deploy target no longer points at the canonical wrapper" >&2
  exit 1
fi

if ! rg -Fq '/orchestrator/scripts/android/pixel_redeploy.sh' "${TOOL_WRAPPER}"; then
  echo "FAIL: satiksme-bot canonical wrapper no longer delegates through tools/pixel/redeploy.sh" >&2
  exit 1
fi

if ! rg -Fq 'prepare_native_release.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer packages a native release" >&2
  exit 1
fi

if ! rg -Fq 'publish_spacetime_schema.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer references the spacetime publish helper" >&2
  exit 1
fi

if ! rg -Fq -- '--action redeploy_component' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer calls redeploy_component" >&2
  exit 1
fi

if ! rg -Fq -- '--component satiksme_bot' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer targets component satiksme_bot" >&2
  exit 1
fi

if ! rg -Fq -- '--satiksme-bot-env-file "${REPO_ROOT}/.env"' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer stages the satiksme env file" >&2
  exit 1
fi

if ! rg -Fq -- '--package-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper missing --package-only mode" >&2
  exit 1
fi

if ! rg -Fq -- '--validate-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper missing --validate-only mode" >&2
  exit 1
fi

if ! rg -Fq 'prepare_release_dir' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper missing reusable release-dir resolver" >&2
  exit 1
fi

if ! rg -Fq 'SATIKSME_BOT_RELEASE_DIR=' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot package-only mode no longer emits a machine-readable release dir" >&2
  exit 1
fi

if ! rg -Fq 'validate_prod_readiness.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer runs the satiksme validation suite" >&2
  exit 1
fi

if ! rg -Fq 'publish_spacetime_schema' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer invokes the spacetime publish step" >&2
  exit 1
fi

if ! rg -Fq 'provision_cloudflared_tunnel.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: satiksme-bot redeploy wrapper no longer provisions the satiksme tunnel preflight" >&2
  exit 1
fi

if [[ ! -x "${REPO_ROOT}/../workloads/satiksme-bot/scripts/pixel/provision_cloudflared_tunnel.sh" ]]; then
  echo "FAIL: satiksme-bot tunnel provisioner script is missing or not executable" >&2
  exit 1
fi

if [[ ! -x "${REPO_ROOT}/../workloads/satiksme-bot/scripts/pixel/publish_spacetime_schema.sh" ]]; then
  echo "FAIL: satiksme-bot spacetime publish helper is missing or not executable" >&2
  exit 1
fi

if ! rg -Fq -- '--action health_component --component satiksme_bot' "${CHECK_SCRIPT}"; then
  echo "FAIL: satiksme-bot release check no longer calls health_component" >&2
  exit 1
fi

echo "PASS: satiksme-bot redeploy wrapper contract is present"
