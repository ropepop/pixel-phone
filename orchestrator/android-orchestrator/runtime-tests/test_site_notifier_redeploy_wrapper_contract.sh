#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MAKEFILE="${REPO_ROOT}/../workloads/site-notifications/Makefile"
DEPLOY_SCRIPT="${REPO_ROOT}/../workloads/site-notifications/scripts/pixel/redeploy_release.sh"
RESTART_SCRIPT="${REPO_ROOT}/../workloads/site-notifications/scripts/pixel/restart_service.sh"
CHECK_SCRIPT="${REPO_ROOT}/../workloads/site-notifications/scripts/pixel/release_check.sh"
TOOL_WRAPPER="${REPO_ROOT}/../tools/pixel/redeploy.sh"
TERMUX_PATH_PREFIX='/data/data/com'
TERMUX_PATH="${TERMUX_PATH_PREFIX}.termux"

if ! rg -U -Fq $'pixel-deploy:\n\t../../tools/pixel/redeploy.sh --scope site_notifier' "${MAKEFILE}"; then
  echo "FAIL: site-notifications Makefile pixel-deploy target missing canonical orchestrator wrapper" >&2
  exit 1
fi

if ! rg -Fq '/orchestrator/scripts/android/pixel_redeploy.sh' "${TOOL_WRAPPER}"; then
  echo "FAIL: site-notifier canonical wrapper no longer delegates through tools/pixel/redeploy.sh" >&2
  exit 1
fi

if ! rg -U -Fq $'pixel-restart:\n\t./scripts/pixel/restart_service.sh' "${MAKEFILE}"; then
  echo "FAIL: site-notifications Makefile pixel-restart target missing restart_service.sh" >&2
  exit 1
fi

if ! rg -U -Fq $'pixel-release-check:\n\t./scripts/pixel/release_check.sh' "${MAKEFILE}"; then
  echo "FAIL: site-notifications Makefile pixel-release-check target missing release_check.sh" >&2
  exit 1
fi

if ! rg -Fq 'package_component_release.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer packages a component release" >&2
  exit 1
fi

if ! rg -Fq -- '--action redeploy_component' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer calls redeploy_component" >&2
  exit 1
fi

if rg -Fq -- '--action stop_component' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper should rely on orchestrator quiescence handling, not manual stop_component" >&2
  exit 1
fi

if rg -Fq -- '--action start_component' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper should not manually start_component on failure" >&2
  exit 1
fi

if ! rg -Fq -- '--component site_notifier' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer targets component site_notifier" >&2
  exit 1
fi

if ! rg -Fq -- '--site-notifier-env-file "${REPO_ROOT}/.env"' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer stages the site-notifier env file" >&2
  exit 1
fi

if ! rg -Fq -- 'run_release_check' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer runs release_check after redeploy" >&2
  exit 1
fi

if ! rg -Fq -- '--package-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper missing --package-only mode" >&2
  exit 1
fi

if ! rg -Fq -- '--validate-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper missing --validate-only mode" >&2
  exit 1
fi

if ! rg -Fq -- 'if (( PACKAGE_ONLY == 1 )); then' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper missing package-only branch" >&2
  exit 1
fi

if ! rg -Fq -- 'if (( VALIDATE_ONLY == 1 )); then' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper missing validate-only branch" >&2
  exit 1
fi

if ! rg -Fq -- 'SITE_NOTIFIER_RELEASE_DIR=' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier package-only mode no longer emits a machine-readable release dir" >&2
  exit 1
fi

if ! rg -Fq -- 'collect_failure_diagnostics' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer collects failure diagnostics" >&2
  exit 1
fi

if rg -Fq 'termux_run.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper still references the retired termux_run.sh helper" >&2
  exit 1
fi

if rg -Fq "${TERMUX_PATH}" "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper still references Termux-owned paths" >&2
  exit 1
fi

if ! rg -Fq '/data/local/tmp/site-notifier-build-' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer builds under a root-owned /data/local/tmp staging path" >&2
  exit 1
fi

if ! rg -Fq 'missing notifier runtime seed' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: site-notifier redeploy wrapper no longer fails closed when no runtime seed is available" >&2
  exit 1
fi

if ! rg -Fq -- '--action restart_component --component site_notifier' "${RESTART_SCRIPT}"; then
  echo "FAIL: site-notifier restart wrapper no longer calls restart_component" >&2
  exit 1
fi

if ! rg -Fq -- '--action health_component --component site_notifier' "${CHECK_SCRIPT}"; then
  echo "FAIL: site-notifier release check no longer calls health_component" >&2
  exit 1
fi

echo "PASS: site-notifier redeploy wrapper contract is present"
