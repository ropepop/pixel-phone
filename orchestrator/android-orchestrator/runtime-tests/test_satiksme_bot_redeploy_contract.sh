#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PIXEL_REDEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/pixel_redeploy.sh"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
PACKAGE_COMPONENT_SCRIPT="${REPO_ROOT}/scripts/android/package_component_release.sh"
PACKAGE_RUNTIME_SCRIPT="${REPO_ROOT}/scripts/android/package_runtime_bundle.sh"

if ! rg -Fq 'full|platform|dns|train_bot|satiksme_bot|site_notifier' "${PIXEL_REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing satiksme_bot scope support" >&2
  exit 1
fi

if ! rg -Fq 'workloads/satiksme-bot/scripts/pixel/prepare_native_release.sh' "${PIXEL_REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing satiksme native release packaging hook" >&2
  exit 1
fi

if ! rg -Fq 'workloads/satiksme-bot/scripts/pixel/validate_prod_readiness.sh' "${PIXEL_REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing satiksme validation hook" >&2
  exit 1
fi

if ! rg -Fq 'package_component_release.sh' "${PIXEL_REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing orchestrator component release packager usage" >&2
  exit 1
fi

if ! rg -Fq -- '--component satiksme_bot' "${PIXEL_REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing satiksme component release target" >&2
  exit 1
fi

if ! rg -Fq -- '--satiksme-bot-env-file' "${PIXEL_REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing satiksme env handoff" >&2
  exit 1
fi

if ! rg -Fq 'satiksme_bot' "${PACKAGE_COMPONENT_SCRIPT}"; then
  echo "FAIL: package_component_release.sh missing satiksme_bot support" >&2
  exit 1
fi

if ! rg -Fq -- '--satiksme-bot-bundle' "${PACKAGE_RUNTIME_SCRIPT}"; then
  echo "FAIL: package_runtime_bundle.sh missing satiksme bundle support" >&2
  exit 1
fi

if ! rg -Fq 'satiksme_bot' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing satiksme component support" >&2
  exit 1
fi

echo "PASS: satiksme bot redeploy contract is present across orchestrator wrappers"
