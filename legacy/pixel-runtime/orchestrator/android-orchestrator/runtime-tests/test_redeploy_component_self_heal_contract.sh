#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"
INSTALLER_FILE="${REPO_ROOT}/android-orchestrator/runtime-installer/src/main/kotlin/lv/jolkins/pixelorchestrator/runtimeinstaller/RuntimeInstaller.kt"

if ! rg -Fq 'requiresQuiescentInstall' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing quiescent install contract" >&2
  exit 1
fi

if ! rg -Fq 'staleCleanupCommand' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing stale cleanup hook contract" >&2
  exit 1
fi

if ! rg -Fq 'RollbackStrategy.PREVIOUS_CURRENT_RELEASE' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing previous-current-release rollback strategy" >&2
  exit 1
fi

if ! rg -Fq 'stopAndQuiesceComponent' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing stop/quiesce redeploy helper" >&2
  exit 1
fi

if ! rg -Fq 'ps -A -o PID=,NAME=,ARGS=' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade quiescence probe no longer inspects process name alongside args" >&2
  exit 1
fi

if ! rg -Fq 'rollbackFailedRedeploy' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing rollback-on-health-failure path" >&2
  exit 1
fi

if ! rg -Fq 'rollbackComponentRelease(' "${INSTALLER_FILE}"; then
  echo "FAIL: RuntimeInstaller missing rollbackComponentRelease entrypoint" >&2
  exit 1
fi

if ! rg -Fq 'pruneComponentReleases(' "${INSTALLER_FILE}"; then
  echo "FAIL: RuntimeInstaller missing deferred component release pruning entrypoint" >&2
  exit 1
fi

if ! rg -Fq 'ReleaseRollbackMetadata' "${INSTALLER_FILE}"; then
  echo "FAIL: RuntimeInstaller missing rollback metadata contract" >&2
  exit 1
fi

echo "PASS: redeploy self-heal contract is present"
