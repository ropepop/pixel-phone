#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
REDEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/pixel_redeploy.sh"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"

for required in \
  'component_release_owner_component' \
  "remote) printf 'dns" \
  'PACKAGE_DNS_COMPONENT_RELEASE_SCRIPT' \
  'run_component_redeploy "dns"' \
  '"remote" -> RedeploySpec(' \
  'releaseManifestComponent = "dns"' \
  'releaseInstallComponent = "dns"'; do
  target="${DEPLOY_SCRIPT}"
  case "${required}" in
    'PACKAGE_DNS_COMPONENT_RELEASE_SCRIPT'|'run_component_redeploy "dns"')
      target="${REDEPLOY_SCRIPT}"
      ;;
    '"remote" -> RedeploySpec('|'releaseManifestComponent = "dns"'|'releaseInstallComponent = "dns"')
      target="${FACADE_FILE}"
      ;;
  esac
  if ! rg -Fq -- "${required}" "${target}"; then
    echo "FAIL: missing DNS release surface contract fragment ${required} in ${target}" >&2
    exit 1
  fi
done

echo "PASS: remote component shares the DNS release surface across scripts and facade"
