#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "${REPO_ROOT}/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-${DEFAULT_ORCHESTRATOR_REPO}}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"
PREPARE_RELEASE_SCRIPT="${SCRIPT_DIR}/prepare_native_release.sh"
VALIDATE_SCRIPT="${SCRIPT_DIR}/validate_prod_readiness.sh"
TUNNEL_PROVISION_SCRIPT="${SCRIPT_DIR}/provision_cloudflared_tunnel.sh"

SKIP_BUILD=0
BOOTSTRAP_ONLY=0
PACKAGE_ONLY=0
VALIDATE_ONLY=0

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --device SERIAL      adb serial to target
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
  --skip-build         skip orchestrator APK build
  --bootstrap-only     run orchestrator bootstrap only
  --package-only       build/package the release only and print its release dir
  --validate-only      run subscription-bot validation only
  -h, --help           show help
USAGE
}

orchestrator_subscription_bot_field() {
  local field="$1"
  python3 - "${ORCHESTRATOR_CONFIG_FILE}" "${field}" <<'PY'
import json
import sys
from urllib.parse import urlparse

config_path, field = sys.argv[1], sys.argv[2]
with open(config_path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
subscription_bot = payload.get("subscriptionBot") or {}

if field == "ingressMode":
    print((subscription_bot.get("ingressMode") or "cloudflare_tunnel").strip())
elif field == "tunnelName":
    print((subscription_bot.get("tunnelName") or "subscription-bot").strip())
elif field == "publicHostname":
    parsed = urlparse((subscription_bot.get("publicBaseUrl") or "https://farel-subscription-bot.jolkins.id.lv/pixel-stack/subscription").strip())
    print(parsed.hostname or "")
else:
    raise SystemExit(f"unsupported field: {field}")
PY
}

ensure_subscription_bot_web_tunnel_provisioned() {
  local ingress_mode="" tunnel_name="" tunnel_hostname=""

  if [[ ! -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    echo "Subscription bot web tunnel preflight failed: missing orchestrator config ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  ingress_mode="$(orchestrator_subscription_bot_field "ingressMode" | tr -d '\r' | tr -d '[:space:]')"
  if [[ "${ingress_mode}" != "cloudflare_tunnel" ]]; then
    return 0
  fi

  if [[ ! -x "${TUNNEL_PROVISION_SCRIPT}" ]]; then
    echo "Subscription bot web tunnel preflight failed: missing ${TUNNEL_PROVISION_SCRIPT}" >&2
    exit 1
  fi
  if ! command -v cloudflared >/dev/null 2>&1; then
    echo "Subscription bot web tunnel preflight failed: local cloudflared CLI is required when ingressMode=cloudflare_tunnel" >&2
    exit 1
  fi

  tunnel_name="$(orchestrator_subscription_bot_field "tunnelName" | tr -d '\r')"
  tunnel_hostname="$(orchestrator_subscription_bot_field "publicHostname" | tr -d '\r')"
  if [[ -z "${tunnel_hostname}" ]]; then
    echo "Subscription bot web tunnel preflight failed: subscriptionBot.publicBaseUrl hostname is empty in ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  log "Ensuring Cloudflare tunnel route/credentials for ${tunnel_name} (${tunnel_hostname})" >&2
  TUNNEL_NAME="${tunnel_name}" \
  TUNNEL_HOSTNAME="${tunnel_hostname}" \
  PIXEL_CREDENTIALS_FILE="/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json" \
    "${TUNNEL_PROVISION_SCRIPT}"
}

orchestrator_args() {
  local args=()
  pixel_transport_append_cli_args args
  if (( SKIP_BUILD == 1 )); then
    args+=(--skip-build)
  fi
  if [[ -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    args+=(--config-file "${ORCHESTRATOR_CONFIG_FILE}")
  fi
  printf '%s\n' "${args[@]}"
}

run_orchestrator() {
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(orchestrator_args)
  cmd+=("$@")
  "${cmd[@]}"
}

run_validation() {
  ensure_subscription_bot_web_tunnel_provisioned
  local -a cmd=("${VALIDATE_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  "${cmd[@]}"
}

prepare_release_dir() {
  local release_output="" release_dir=""
  release_output="$(ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO}" "${PREPARE_RELEASE_SCRIPT}")"
  printf '%s\n' "${release_output}" >&2
  release_dir="$(printf '%s\n' "${release_output}" | awk -F= '/^SUBSCRIPTION_BOT_RELEASE_DIR=/{print $2}' | tail -n 1)"
  if [[ -z "${release_dir}" ]]; then
    release_dir="$(printf '%s\n' "${release_output}" | tail -n 1 | tr -d '\r')"
  fi
  if [[ ! -d "${release_dir}" || ! -f "${release_dir}/release-manifest.json" ]]; then
    echo "Prepared release dir is invalid: ${release_dir}" >&2
    return 1
  fi
  printf '%s\n' "${release_dir}"
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    --skip-build)
      SKIP_BUILD=1
      ;;
    --bootstrap-only)
      BOOTSTRAP_ONLY=1
      ;;
    --package-only)
      PACKAGE_ONLY=1
      ;;
    --validate-only)
      VALIDATE_ONLY=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if (( BOOTSTRAP_ONLY + PACKAGE_ONLY + VALIDATE_ONLY > 1 )); then
  echo "--bootstrap-only, --package-only, and --validate-only are mutually exclusive" >&2
  exit 2
fi

ensure_device
ensure_root
ensure_output_dirs
ensure_local_env

if [[ -z "${ORCHESTRATOR_REPO}" || ! -d "${ORCHESTRATOR_REPO}" ]]; then
  echo "Cannot resolve orchestrator repo. Set ORCHESTRATOR_REPO explicitly." >&2
  exit 1
fi
if [[ ! -x "${ORCHESTRATOR_DEPLOY_SCRIPT}" ]]; then
  echo "Missing orchestrator deploy script: ${ORCHESTRATOR_DEPLOY_SCRIPT}" >&2
  exit 1
fi
if [[ ! -x "${PREPARE_RELEASE_SCRIPT}" ]]; then
  echo "Missing subscription-bot release builder: ${PREPARE_RELEASE_SCRIPT}" >&2
  exit 1
fi

ensure_subscription_bot_web_tunnel_provisioned

if (( PACKAGE_ONLY == 1 )); then
  release_dir="$(prepare_release_dir)"
  printf 'SUBSCRIPTION_BOT_RELEASE_DIR=%s\n' "${release_dir}"
  exit 0
fi

if (( VALIDATE_ONLY == 1 )); then
  run_validation
  exit 0
fi

if (( BOOTSTRAP_ONLY == 1 )); then
  run_orchestrator \
    --subscription-bot-env-file "${REPO_ROOT}/.env" \
    --action bootstrap
  run_validation
  exit 0
fi

RELEASE_DIR="$(prepare_release_dir)"

run_orchestrator \
  --subscription-bot-env-file "${REPO_ROOT}/.env" \
  --component-release-dir "${RELEASE_DIR}" \
  --action redeploy_component \
  --component subscription_bot

run_validation
echo "Subscription Bot redeploy complete"
