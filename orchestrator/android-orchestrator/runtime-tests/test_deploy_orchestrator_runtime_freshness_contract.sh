#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"

for required in \
  'verify_runtime_assets_pre_action' \
  'Runtime asset precheck stale' \
  'refusing to run lifecycle-only action' \
  'pre_action_runtime_freshness_scope' \
  'runtime_freshness_scope_for_component' \
  'Live DNS runtime after action: converged' \
  '--component-release-dir is only valid with --action redeploy_component or bootstrap' \
  'component_release_manifest_component'; do
  if ! rg -Fq -- "${required}" "${DEPLOY_SCRIPT}"; then
    echo "FAIL: deploy_orchestrator_apk.sh missing runtime freshness contract fragment ${required}" >&2
    exit 1
  fi
done

if rg -Fq 'advisory; continuing' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh still treats stale runtime assets as advisory" >&2
  exit 1
fi

if ! rg -Fq 'site_notifier' "${DEPLOY_SCRIPT}" || ! rg -Fq 'subscription_bot' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing exact component runtime freshness scopes" >&2
  exit 1
fi

echo "PASS: deploy orchestrator runtime freshness contract is fail-closed and component-scoped"
