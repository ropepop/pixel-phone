#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"
PREPARE_RELEASE_SCRIPT="${SCRIPT_DIR}/prepare_native_release.sh"
VALIDATE_SCRIPT="${SCRIPT_DIR}/validate_prod_readiness.sh"
TUNNEL_PROVISION_SCRIPT="${SCRIPT_DIR}/provision_cloudflared_tunnel.sh"
SPACETIME_PUBLISH_SCRIPT="${SCRIPT_DIR}/publish_spacetime_schema.sh"

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
  --package-only       package a native satiksme_bot component release and print its release dir
  --validate-only      run satiksme-specific validation without packaging or redeploying
  -h, --help           show help
USAGE
}

orchestrator_satiksme_bot_field() {
  local field="$1"
  python3 - "${ORCHESTRATOR_CONFIG_FILE}" "${field}" <<'PY'
import json
import sys
from urllib.parse import urlparse

config_path, field = sys.argv[1], sys.argv[2]
with open(config_path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
satiksme_bot = payload.get("satiksmeBot") or {}

if field == "ingressMode":
    print((satiksme_bot.get("ingressMode") or "cloudflare_tunnel").strip())
elif field == "tunnelName":
    print((satiksme_bot.get("tunnelName") or "satiksme-bot").strip())
elif field == "publicHostname":
    parsed = urlparse((satiksme_bot.get("publicBaseUrl") or "https://satiksme-bot.jolkins.id.lv").strip())
    print(parsed.hostname or "")
else:
    raise SystemExit(f"unsupported field: {field}")
PY
}

ensure_satiksme_web_tunnel_provisioned() {
  local ingress_mode="" tunnel_name="" tunnel_hostname=""

  if [[ ! -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    echo "Satiksme web tunnel preflight failed: missing orchestrator config ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  ingress_mode="$(orchestrator_satiksme_bot_field "ingressMode" | tr -d '\r' | tr -d '[:space:]')"
  if [[ "${ingress_mode}" != "cloudflare_tunnel" ]]; then
    return 0
  fi

  if [[ ! -x "${TUNNEL_PROVISION_SCRIPT}" ]]; then
    echo "Satiksme web tunnel preflight failed: missing ${TUNNEL_PROVISION_SCRIPT}" >&2
    exit 1
  fi
  if ! command -v cloudflared >/dev/null 2>&1; then
    echo "Satiksme web tunnel preflight failed: local cloudflared CLI is required when ingressMode=cloudflare_tunnel" >&2
    exit 1
  fi

  tunnel_name="$(orchestrator_satiksme_bot_field "tunnelName" | tr -d '\r')"
  tunnel_hostname="$(orchestrator_satiksme_bot_field "publicHostname" | tr -d '\r')"
  if [[ -z "${tunnel_hostname}" ]]; then
    echo "Satiksme web tunnel preflight failed: satiksmeBot.publicBaseUrl hostname is empty in ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  log "Ensuring Cloudflare tunnel route/credentials for ${tunnel_name} (${tunnel_hostname})" >&2
  TUNNEL_NAME="${tunnel_name}" \
  TUNNEL_HOSTNAME="${tunnel_hostname}" \
  PIXEL_CREDENTIALS_FILE="/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json" \
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

prepare_release_dir() {
  local release_dir=""
  release_dir="$(ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO}" "${PREPARE_RELEASE_SCRIPT}")"
  if [[ ! -d "${release_dir}" || ! -f "${release_dir}/release-manifest.json" ]]; then
    echo "Prepared release dir is invalid: ${release_dir}" >&2
    return 1
  fi
  printf '%s\n' "${release_dir}"
}

run_validate_only() {
  ensure_satiksme_web_tunnel_provisioned
  local -a cmd=("${VALIDATE_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  "${cmd[@]}"
}

publish_spacetime_schema() {
  if [[ ! -x "${SPACETIME_PUBLISH_SCRIPT}" ]]; then
    echo "Missing satiksme Spacetime publish script: ${SPACETIME_PUBLISH_SCRIPT}" >&2
    return 1
  fi
  "${SPACETIME_PUBLISH_SCRIPT}"
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
  echo "Missing satiksme release builder: ${PREPARE_RELEASE_SCRIPT}" >&2
  exit 1
fi
if [[ ! -x "${SPACETIME_PUBLISH_SCRIPT}" ]]; then
  echo "Missing satiksme Spacetime publish script: ${SPACETIME_PUBLISH_SCRIPT}" >&2
  exit 1
fi

ensure_satiksme_web_tunnel_provisioned

if (( PACKAGE_ONLY == 1 )); then
  release_dir="$(prepare_release_dir)"
  printf 'SATIKSME_BOT_RELEASE_DIR=%s\n' "${release_dir}"
  exit 0
fi

if (( VALIDATE_ONLY == 1 )); then
  run_validate_only
  echo "Satiksme Bot validation complete"
  exit 0
fi

if (( BOOTSTRAP_ONLY == 1 )); then
  run_orchestrator --action bootstrap --satiksme-bot-env-file "${REPO_ROOT}/.env"
  echo "Satiksme Bot bootstrap complete"
  exit 0
fi

release_dir="$(prepare_release_dir)"
publish_spacetime_schema
run_orchestrator \
  --action redeploy_component \
  --component satiksme_bot \
  --component-release-dir "${release_dir}" \
  --satiksme-bot-env-file "${REPO_ROOT}/.env"
run_validate_only
echo "Satiksme Bot redeploy complete"
