#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/../workloads/subscription-bot/scripts/pixel/redeploy_release.sh"
CHECK_SCRIPT="${REPO_ROOT}/../workloads/subscription-bot/scripts/pixel/release_check.sh"

if ! rg -Fq 'prepare_native_release.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper no longer packages a native release" >&2
  exit 1
fi

if ! rg -Fq -- '--action redeploy_component' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper no longer calls redeploy_component" >&2
  exit 1
fi

if ! rg -Fq -- '--component subscription_bot' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper no longer targets component subscription_bot" >&2
  exit 1
fi

if ! rg -Fq -- '--subscription-bot-env-file "${REPO_ROOT}/.env"' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper no longer stages the subscription env file" >&2
  exit 1
fi

if ! rg -Fq -- '--package-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper missing --package-only mode" >&2
  exit 1
fi

if ! rg -Fq -- '--validate-only' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper missing --validate-only mode" >&2
  exit 1
fi

if ! rg -Fq 'prepare_release_dir' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper missing reusable release-dir resolver" >&2
  exit 1
fi

if ! rg -Fq 'SUBSCRIPTION_BOT_RELEASE_DIR=' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot package-only mode no longer emits a machine-readable release dir" >&2
  exit 1
fi

if ! rg -Fq 'validate_prod_readiness.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper no longer runs the validation suite" >&2
  exit 1
fi

if ! rg -Fq 'provision_cloudflared_tunnel.sh' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: subscription-bot redeploy wrapper no longer provisions the tunnel preflight" >&2
  exit 1
fi

if [[ ! -x "${REPO_ROOT}/../workloads/subscription-bot/scripts/pixel/provision_cloudflared_tunnel.sh" ]]; then
  echo "FAIL: subscription-bot tunnel provisioner script is missing or not executable" >&2
  exit 1
fi

if ! rg -Fq -- '--action health_component' "${CHECK_SCRIPT}" || ! rg -Fq -- '--component subscription_bot' "${CHECK_SCRIPT}"; then
  echo "FAIL: subscription-bot release check no longer calls health_component for subscription_bot" >&2
  exit 1
fi

echo "PASS: subscription-bot redeploy wrapper contract is present"
