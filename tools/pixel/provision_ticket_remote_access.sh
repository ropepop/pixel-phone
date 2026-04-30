#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./transport.sh
source "${SCRIPT_DIR}/transport.sh"

TUNNEL_NAME="${TUNNEL_NAME:-ticket-screen}"
TUNNEL_HOSTNAME="${TUNNEL_HOSTNAME:-ticket.jolkins.id.lv}"
ACCESS_EMAIL="${ACCESS_EMAIL:-ticket@jolkins.id.lv}"
ACCESS_SESSION_DURATION="${ACCESS_SESSION_DURATION:-720h}"
PIXEL_CREDENTIALS_FILE="${PIXEL_CREDENTIALS_FILE:-/data/local/pixel-stack/conf/apps/ticket-screen-cloudflared.json}"

usage() {
  cat <<'USAGE'
Usage: provision_ticket_remote_access.sh [options]

Creates/routes the Cloudflare Tunnel for ticket.jolkins.id.lv, pushes tunnel
credentials to the Pixel, and configures Cloudflare Access when CF_ACCOUNT_ID
and CF_API_TOKEN are present.

Environment:
  CF_ACCOUNT_ID              Cloudflare account id for Access API setup
  CF_API_TOKEN               Cloudflare API token with Access app/policy rights
  TUNNEL_NAME                default: ticket-screen
  TUNNEL_HOSTNAME            default: ticket.jolkins.id.lv
  ACCESS_EMAIL               default: ticket@jolkins.id.lv
  ACCESS_SESSION_DURATION    default: 720h (30 days)

Options:
  --transport MODE           transport to use (adb|ssh|auto)
  --device SERIAL            adb serial to target
  --ssh-host IP              Tailscale or SSH host/IP
  --ssh-port PORT            SSH port (default: 2222)
  -h, --help                 show help
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

for cmd in cloudflared python3; do
  command -v "${cmd}" >/dev/null 2>&1 || {
    echo "Missing required command: ${cmd}" >&2
    exit 1
  }
done

pixel_transport_require_device
pixel_transport_require_root

LOCAL_CLOUDFLARED_DIR="${HOME}/.cloudflared"
LOCAL_CERT_FILE="${LOCAL_CLOUDFLARED_DIR}/cert.pem"
if [[ ! -s "${LOCAL_CERT_FILE}" ]]; then
  echo "Cloudflare tunnel auth is missing at ${LOCAL_CERT_FILE}" >&2
  echo "Run: cloudflared tunnel login" >&2
  exit 1
fi

extract_tunnel_id() {
  python3 -c '
import json
import sys

target = sys.argv[1]
payload = json.load(sys.stdin)
for item in payload:
    if item.get("name") == target:
        print(item.get("id", ""))
        break
' "$TUNNEL_NAME"
}

tunnel_id="$(cloudflared tunnel list -o json | extract_tunnel_id)"
if [[ -z "${tunnel_id}" ]]; then
  cloudflared tunnel create "${TUNNEL_NAME}"
  tunnel_id="$(cloudflared tunnel list -o json | extract_tunnel_id)"
fi
if [[ -z "${tunnel_id}" ]]; then
  echo "Could not create or find Cloudflare tunnel ${TUNNEL_NAME}" >&2
  exit 1
fi

set +e
route_output="$(cloudflared tunnel route dns "${TUNNEL_NAME}" "${TUNNEL_HOSTNAME}" 2>&1)"
route_rc=$?
set -e
if [[ "${route_rc}" -ne 0 ]] && ! grep -qi 'already exists' <<<"${route_output}"; then
  printf '%s\n' "${route_output}" >&2
  exit "${route_rc}"
fi

local_credentials="${LOCAL_CLOUDFLARED_DIR}/${tunnel_id}.json"
if [[ ! -s "${local_credentials}" ]]; then
  echo "Missing local tunnel credentials file: ${local_credentials}" >&2
  exit 1
fi

remote_tmp="/data/local/tmp/ticket-screen-cloudflared.json"
remote_dir="$(dirname "${PIXEL_CREDENTIALS_FILE}")"
pixel_transport_push "${local_credentials}" "${remote_tmp}" >/dev/null
pixel_transport_root_exec mkdir -p "${remote_dir}" >/dev/null
pixel_transport_root_exec cp "${remote_tmp}" "${PIXEL_CREDENTIALS_FILE}" >/dev/null
pixel_transport_root_exec chmod 600 "${PIXEL_CREDENTIALS_FILE}" >/dev/null
pixel_transport_root_exec rm -f "${remote_tmp}" >/dev/null

if [[ -n "${CF_ACCOUNT_ID:-}" && -n "${CF_API_TOKEN:-}" ]]; then
  python3 - "$CF_ACCOUNT_ID" "$CF_API_TOKEN" "$TUNNEL_HOSTNAME" "$ACCESS_EMAIL" "$ACCESS_SESSION_DURATION" <<'PY'
import json
import sys
import urllib.error
import urllib.request

account_id, token, hostname, email, duration = sys.argv[1:]
base = f"https://api.cloudflare.com/client/v4/accounts/{account_id}/access"
headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/json",
}

def request(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"Cloudflare API failed {method} {path}: {exc.code} {body}") from exc
    parsed = json.loads(body)
    if not parsed.get("success"):
        raise SystemExit(f"Cloudflare API returned failure for {method} {path}: {body}")
    return parsed.get("result")

apps = request("GET", f"/apps?search={hostname}") or []
app = next((item for item in apps if item.get("domain") == hostname), None)
app_payload = {
    "name": hostname,
    "domain": hostname,
    "type": "self_hosted",
    "session_duration": duration,
}
if app:
    app = request("PUT", f"/apps/{app['id']}", app | app_payload)
else:
    app = request("POST", "/apps", app_payload)

policy_payload = {
    "name": "ticket-email-only",
    "decision": "allow",
    "include": [{"email": {"email": email}}],
    "session_duration": duration,
    "precedence": 1,
}
policies = request("GET", f"/apps/{app['id']}/policies") or []
policy = next((item for item in policies if item.get("name") == "ticket-email-only"), None)
if policy:
    request("PUT", f"/apps/{app['id']}/policies/{policy['id']}", policy | policy_payload)
else:
    request("POST", f"/apps/{app['id']}/policies", policy_payload)

print(f"cloudflare_access=configured host={hostname} email={email} duration={duration}")
PY
else
  echo "cloudflare_access=skipped"
  echo "Set CF_ACCOUNT_ID and CF_API_TOKEN to configure Cloudflare Access automatically."
fi

cat <<EOF_STATUS
tunnel_name=${TUNNEL_NAME}
tunnel_id=${tunnel_id}
tunnel_hostname=${TUNNEL_HOSTNAME}
pixel_credentials=${PIXEL_CREDENTIALS_FILE}
access_email=${ACCESS_EMAIL}
access_session_duration=${ACCESS_SESSION_DURATION}
EOF_STATUS
