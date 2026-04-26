#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<'USAGE'
Usage: provision_cloudflared_tunnel.sh [options]

Options:
  --device SERIAL      adb serial to target
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
  -h, --help           show help
USAGE
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
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
done

ensure_device
ensure_root

TUNNEL_NAME="${TUNNEL_NAME:-subscription-bot}"
TUNNEL_HOSTNAME="${TUNNEL_HOSTNAME:-farel-subscription-bot.jolkins.id.lv}"
PIXEL_CREDENTIALS_FILE="${PIXEL_CREDENTIALS_FILE:-/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json}"
LOCAL_CLOUDFLARED_DIR="${HOME}/.cloudflared"
LOCAL_CERT_FILE="${LOCAL_CLOUDFLARED_DIR}/cert.pem"

for cmd in cloudflared python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done

if [[ ! -s "${LOCAL_CERT_FILE}" ]]; then
  log "Cloudflare tunnel auth is missing at ${LOCAL_CERT_FILE}"
  log "Run: cloudflared tunnel login"
  exit 1
fi

list_tunnels_json() {
  cloudflared tunnel list -o json
}

extract_tunnel_id() {
  local target_name="$1"
  python3 -c '
import json
import sys

target = sys.argv[1]
payload = json.load(sys.stdin)
for item in payload:
    if item.get("name") == target:
        print(item.get("id", ""))
        break
' "$target_name"
}

ensure_tunnel_exists() {
  local tunnel_id
  tunnel_id="$(list_tunnels_json | extract_tunnel_id "${TUNNEL_NAME}")"
  if [[ -n "${tunnel_id}" ]]; then
    printf '%s\n' "${tunnel_id}"
    return 0
  fi

  log "Creating Cloudflare tunnel ${TUNNEL_NAME}" >&2
  cloudflared tunnel create "${TUNNEL_NAME}"
  tunnel_id="$(list_tunnels_json | extract_tunnel_id "${TUNNEL_NAME}")"
  if [[ -z "${tunnel_id}" ]]; then
    log "Tunnel ${TUNNEL_NAME} was not visible after creation"
    exit 1
  fi
  printf '%s\n' "${tunnel_id}"
}

route_dns() {
  local output rc
  set +e
  output="$(cloudflared tunnel route dns "${TUNNEL_NAME}" "${TUNNEL_HOSTNAME}" 2>&1)"
  rc=$?
  set -e
  if [[ "${rc}" -eq 0 ]]; then
    printf '%s\n' "${output}"
    return 0
  fi
  if printf '%s' "${output}" | grep -qi 'already exists'; then
    printf '%s\n' "${output}"
    return 0
  fi
  printf '%s\n' "${output}" >&2
  return "${rc}"
}

push_credentials() {
  local tunnel_id="$1"
  local local_credentials
  local_credentials="${LOCAL_CLOUDFLARED_DIR}/${tunnel_id}.json"
  if [[ ! -s "${local_credentials}" ]]; then
    log "Missing local tunnel credentials file: ${local_credentials}"
    exit 1
  fi

  log "Pushing tunnel credentials to Pixel: ${PIXEL_CREDENTIALS_FILE}"
  pixel_transport_push "${local_credentials}" "${PIXEL_CREDENTIALS_FILE}" >/dev/null
  pixel_transport_root_exec chown root:root "${PIXEL_CREDENTIALS_FILE}" >/dev/null 2>&1 || true
  pixel_transport_root_exec chmod 600 "${PIXEL_CREDENTIALS_FILE}" >/dev/null
}

tunnel_id="$(ensure_tunnel_exists)"
log "Using Cloudflare tunnel ${TUNNEL_NAME} (${tunnel_id})"
route_dns
push_credentials "${tunnel_id}"
log "Cloudflare tunnel DNS is routed for https://${TUNNEL_HOSTNAME}"
log "Tunnel credentials installed at ${PIXEL_CREDENTIALS_FILE}"
