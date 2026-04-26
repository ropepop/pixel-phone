#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BUILD_SCRIPT="${REPO_ROOT}/scripts/android/build_orchestrator_apk.sh"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"
INSTALLER_FILE="${REPO_ROOT}/android-orchestrator/runtime-installer/src/main/kotlin/lv/jolkins/pixelorchestrator/runtimeinstaller/RuntimeInstaller.kt"

if rg -Fq -- '--runtime-release-base-url' "${BUILD_SCRIPT}"; then
  echo "FAIL: build_orchestrator_apk.sh still exposes --runtime-release-base-url" >&2
  exit 1
fi

if rg -Fq -- '--runtime-release-base-url' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh still exposes --runtime-release-base-url" >&2
  exit 1
fi

if ! rg -Fq -- '--runtime-bundle-dir' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing --runtime-bundle-dir" >&2
  exit 1
fi

if ! rg -Fq -- '--component-release-dir' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing --component-release-dir" >&2
  exit 1
fi

if ! rg -Fq '/data/local/pixel-stack/conf/runtime/runtime-manifest.json' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing on-device runtime manifest path" >&2
  exit 1
fi

if rg -Fq 'BuildConfig.RUNTIME_RELEASE_BASE_URL' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade still references BuildConfig.RUNTIME_RELEASE_BASE_URL" >&2
  exit 1
fi

if ! rg -Fq 'ensure_runtime_manifest_staged' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing staged manifest bootstrap guard" >&2
  exit 1
fi

if ! rg -Fq 'ensure_component_release_manifest_staged' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing staged component release guard" >&2
  exit 1
fi

if ! rg -Fq 'suspend fun syncBundledRuntimeAssets' "${INSTALLER_FILE}"; then
  echo "FAIL: RuntimeInstaller missing reusable bundled runtime asset sync method" >&2
  exit 1
fi

if ! rg -Fq 'suspend fun redeployComponent(component: String)' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing redeployComponent action" >&2
  exit 1
fi

if ! rg -Fq 'installComponentRelease(' "${INSTALLER_FILE}"; then
  echo "FAIL: RuntimeInstaller missing installComponentRelease entrypoint" >&2
  exit 1
fi

if ! rg -Fq 'ACTION_REDEPLOY_COMPONENT' "${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/MainActivity.kt"; then
  echo "FAIL: MainActivity missing redeploy action constant wiring" >&2
  exit 1
fi

if ! rg -Fq 'ACTION_REDEPLOY_COMPONENT' "${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"; then
  echo "FAIL: SupervisorService missing redeploy action constant wiring" >&2
  exit 1
fi

if ! rg -Fq -- '--runtime-bundle-dir is bootstrap-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing bootstrap-only guard for runtime bundles" >&2
  exit 1
fi

if rg -Fq 'syncRuntimeAssetsForAction(action = "start_component"' "${FACADE_FILE}"; then
  echo "FAIL: start_component should no longer perform update-style runtime asset sync" >&2
  exit 1
fi

if rg -Fq 'syncRuntimeAssetsForAction(action = "restart_component"' "${FACADE_FILE}"; then
  echo "FAIL: restart_component should no longer perform update-style runtime asset sync" >&2
  exit 1
fi

echo "PASS: runtime local bundle contract is present"
