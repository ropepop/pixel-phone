#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BUILD_SCRIPT="${REPO_ROOT}/scripts/android/build_orchestrator_apk.sh"
ACTION_RESULT="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorActionResult.kt"
PROVENANCE_MODEL="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorReleaseProvenance.kt"

output="$(
  ORCHESTRATOR_RELEASE_ID="release-test-123" \
  ORCHESTRATOR_SOURCE_COMMIT="0123456789abcdef" \
  ORCHESTRATOR_SOURCE_DIRTY="false" \
  ORCHESTRATOR_BUILD_TIME="2026-07-10T10:00:00Z" \
    "${BUILD_SCRIPT}" --print-provenance
)"

for expected in \
  "ORCHESTRATOR_RELEASE_ID=release-test-123" \
  "ORCHESTRATOR_SOURCE_COMMIT=0123456789abcdef" \
  "ORCHESTRATOR_SOURCE_DIRTY=false" \
  "ORCHESTRATOR_BUILD_TIME=2026-07-10T10:00:00Z"
do
  if ! grep -Fxq "${expected}" <<<"${output}"; then
    echo "FAIL: missing provenance output: ${expected}" >&2
    exit 1
  fi
done

if ORCHESTRATOR_SOURCE_DIRTY="not-a-boolean" "${BUILD_SCRIPT}" --print-provenance >/dev/null 2>&1; then
  echo "FAIL: invalid dirty-state provenance was accepted" >&2
  exit 1
fi

if ! grep -Fq 'val releaseProvenance: OrchestratorReleaseProvenance' "${ACTION_RESULT}"; then
  echo "FAIL: action result does not carry release provenance" >&2
  exit 1
fi

for field in ORCHESTRATOR_RELEASE_ID ORCHESTRATOR_SOURCE_COMMIT ORCHESTRATOR_SOURCE_DIRTY ORCHESTRATOR_BUILD_TIME; do
  if ! grep -Fq "BuildConfig.${field}" "${PROVENANCE_MODEL}"; then
    echo "FAIL: release provenance model is missing ${field}" >&2
    exit 1
  fi
done

echo "PASS: orchestrator build provenance is generated and attached to action results"
