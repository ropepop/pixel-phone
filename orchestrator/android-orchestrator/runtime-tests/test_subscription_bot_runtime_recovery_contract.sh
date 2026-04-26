#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
E2E_SCRIPT="${REPO_ROOT}/../workloads/subscription-bot/scripts/pixel/e2e_live.sh"
READINESS_SCRIPT="${REPO_ROOT}/../workloads/subscription-bot/scripts/pixel/validate_prod_readiness.sh"

if ! rg -Fq 'ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"' "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e promote flow no longer defaults to the checked-in production config" >&2
  exit 1
fi

if ! rg -Fq 'LOCAL_BASE_PATH="${SUBSCRIPTION_BOT_LOCAL_BASE_PATH:-/pixel-stack/subscription}"' "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e flow no longer keeps the forwarded local base path aligned with the mounted app path" >&2
  exit 1
fi

if ! rg -Fq 'BASE_URL="http://127.0.0.1:${LOCAL_PORT}${LOCAL_BASE_PATH}"' "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e preflight no longer uses the routed local base path" >&2
  exit 1
fi

if ! rg -Fq '.subscriptionBot.webShellEnabled = true' "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e config no longer forces the mini app shell on during the temporary test deploy" >&2
  exit 1
fi

if ! rg -Fq 'provision_cloudflared_tunnel.sh' "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e flow no longer provisions the Cloudflare tunnel preflight" >&2
  exit 1
fi

if ! rg -Fq '.subscriptionBot = (($production[0].subscriptionBot // .subscriptionBot) + {e2eMode: false})' "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e promote flow no longer restores the production subscriptionBot settings" >&2
  exit 1
fi

if ! rg -Fq "^SUBSCRIPTION_BOT_WEB_ENABLED=true$" "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e promote flow no longer requires web to stay enabled" >&2
  exit 1
fi

if ! rg -Fq "^SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=true$" "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e promote flow no longer requires the native Mini App shell to stay enabled" >&2
  exit 1
fi

if ! rg -Fq "^SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED=true$" "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e promote flow no longer requires the web tunnel to stay enabled" >&2
  exit 1
fi

if rg -Fq "^SUBSCRIPTION_BOT_WEB_ENABLED=false$" "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e promote flow still expects web to be disabled" >&2
  exit 1
fi

if rg -Fq "^SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=false$" "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e promote flow still expects the Mini App shell to be disabled" >&2
  exit 1
fi

if ! rg -Fq 'pixel_transport_pull "/data/local/pixel-stack/conf/apps/subscription-bot.env" "${source_env_log}"' "${READINESS_SCRIPT}"; then
  echo "FAIL: production readiness no longer snapshots the subscription bot source env" >&2
  exit 1
fi

if ! rg -Fq 'pixel_transport_pull "/data/local/pixel-stack/apps/subscription-bot/env/subscription-bot.env" "${runtime_env_log}"' "${READINESS_SCRIPT}"; then
  echo "FAIL: production readiness no longer snapshots the live subscription bot runtime env" >&2
  exit 1
fi

if ! rg -Fq 'provision_cloudflared_tunnel.sh' "${READINESS_SCRIPT}"; then
  echo "FAIL: production readiness no longer provisions the Cloudflare tunnel preflight" >&2
  exit 1
fi

if ! rg -Fq 'https://farel-subscription-bot.jolkins.id.lv/pixel-stack/subscription' "${READINESS_SCRIPT}"; then
  echo "FAIL: production readiness no longer expects the farel subscription public hostname" >&2
  exit 1
fi

if ! rg -Fq 'has_main_web_app' "${READINESS_SCRIPT}"; then
  echo "FAIL: production readiness no longer verifies the Telegram Main Mini App configuration" >&2
  exit 1
fi

if ! rg -Fq 'has_main_web_app' "${E2E_SCRIPT}"; then
  echo "FAIL: subscription bot e2e preflight no longer verifies the Telegram Main Mini App configuration" >&2
  exit 1
fi

if ! rg -Fq 'subscription bot runtime env drift detected' "${READINESS_SCRIPT}"; then
  echo "FAIL: production readiness no longer fails clearly on runtime env drift" >&2
  exit 1
fi

echo "PASS: subscription bot runtime recovery contract is present in the e2e promote flow and production readiness checks"
