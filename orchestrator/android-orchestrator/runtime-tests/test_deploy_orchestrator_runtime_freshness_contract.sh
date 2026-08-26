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

if ! rg -Fq 'freshness_retry < 5 && rc == 3' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh lacks a bounded post-repair freshness recheck" >&2
  exit 1
fi

if ! rg -Fq 'runtime_scope_requires_current_apk "${scope}" && (( SKIP_BUILD == 1 && APK_INSTALLED_THIS_RUN == 0 ))' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: stale APK-backed repair must refuse --skip-build unless this run installed the APK" >&2
  exit 1
fi

if rg -Fq 'runtime_scope_requires_current_apk "${scope}" && (( SKIP_BUILD == 1 )); then' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: a freshly installed APK must remain eligible to repair its bundled runtime assets" >&2
  exit 1
fi

if ! rg -Fq 'APK_INSTALLED_THIS_RUN=1' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy script no longer records a successful APK install for runtime repair admission" >&2
  exit 1
fi

if rg -Fq 'advisory; continuing' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh still treats stale runtime assets as advisory" >&2
  exit 1
fi

if ! rg -Fq 'site_notifier' "${DEPLOY_SCRIPT}" || ! rg -Fq 'subscription_bot' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh missing exact component runtime freshness scopes" >&2
  exit 1
fi

echo "PASS: deploy orchestrator runtime freshness contract is fail-closed and component-scoped"
