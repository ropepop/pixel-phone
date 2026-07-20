#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RUNTIME_INSTALLER="${REPO_ROOT}/android-orchestrator/runtime-installer/src/main/kotlin/lv/jolkins/pixelorchestrator/runtimeinstaller/RuntimeInstaller.kt"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"

for required in \
  'listOfNotNull(rootfsArtifactId, DROPBEAR_ARTIFACT_ID, TAILSCALE_ARTIFACT_ID)' \
  'Required artifact must set required=true:'; do
  if ! rg -Fq "${required}" "${RUNTIME_INSTALLER}"; then
    echo "FAIL: RuntimeInstaller missing platform-only bootstrap manifest contract fragment ${required}" >&2
    exit 1
  fi
done

for required in \
  'REQUIRED_BOOTSTRAP_ARTIFACT_IDS = listOf("dropbear-bundle", "tailscale-bundle")' \
  'OPTIONAL_BOOTSTRAP_ARTIFACT_IDS = emptyList<String>()' \
  'rootfsArtifactId = null' \
  'Bootstrap artifact must set required=true when present:'; do
  if ! rg -Fq "${required}" "${FACADE_FILE}"; then
    echo "FAIL: OrchestratorFacade missing platform-only bootstrap manifest contract fragment ${required}" >&2
    exit 1
  fi
done

echo "PASS: platform-only bootstrap excludes retired DNS and requires only active platform artifacts"
