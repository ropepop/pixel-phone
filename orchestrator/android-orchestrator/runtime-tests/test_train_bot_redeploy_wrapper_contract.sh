#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MAKEFILE="${REPO_ROOT}/../workloads/train-bot/Makefile"
DEPLOY_SCRIPT="${REPO_ROOT}/../workloads/train-bot/scripts/pixel/redeploy_release.sh"
TOOL_WRAPPER="${REPO_ROOT}/../tools/pixel/redeploy.sh"

if ! rg -U -Fq $'pixel-native-deploy:\n\t../../tools/pixel/redeploy.sh --scope train_bot' "${MAKEFILE}"; then
  echo "FAIL: train-bot Makefile pixel-native-deploy target missing canonical orchestrator wrapper" >&2
  exit 1
fi

if ! rg -U -Fq $'pixel-deploy:\n\t@echo "pixel-deploy retired: use ../../tools/pixel/redeploy.sh --scope train_bot."' "${MAKEFILE}"; then
  echo "FAIL: train-bot Makefile pixel-deploy target no longer points at the canonical wrapper" >&2
  exit 1
fi

if ! rg -Fq '/orchestrator/scripts/android/pixel_redeploy.sh' "${TOOL_WRAPPER}"; then
  echo "FAIL: train-bot canonical wrapper no longer delegates through tools/pixel/redeploy.sh" >&2
  exit 1
fi

if ! rg -Fq 'package_component_release.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper no longer packages a component release" >&2
  exit 1
fi

if ! rg -Fq -- '--action redeploy_component' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper no longer calls redeploy_component" >&2
  exit 1
fi

if ! rg -Fq -- '--component train_bot' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper no longer targets component train_bot" >&2
  exit 1
fi

if ! rg -Fq -- '--train-bot-env-file "${REPO_ROOT}/.env"' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper no longer stages the train-bot env file" >&2
  exit 1
fi

if ! rg -Fq -- '--package-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper missing --package-only mode" >&2
  exit 1
fi

if ! rg -Fq -- '--validate-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper missing --validate-only mode" >&2
  exit 1
fi

if ! rg -Fq 'run_package_only()' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper missing package-only entrypoint" >&2
  exit 1
fi

if ! rg -Fq 'prepare_release_dir' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot package-only path no longer resolves a staged release dir" >&2
  exit 1
fi

if ! rg -Fq 'run_validate_only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: train-bot redeploy wrapper missing reusable validation entrypoint" >&2
  exit 1
fi

echo "PASS: train-bot redeploy wrapper contract is present"
