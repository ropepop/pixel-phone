#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "${REPO_ROOT}/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-${DEFAULT_ORCHESTRATOR_REPO}}"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
EXPECTED_BOT_USERNAME=""
TUNNEL_PROVISION_SCRIPT="${SCRIPT_DIR}/provision_cloudflared_tunnel.sh"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --device SERIAL      adb serial to target
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
  -h, --help           show help
USAGE
}

run_orchestrator() {
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  cmd+=(--skip-build)
  if [[ -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    cmd+=(--config-file "${ORCHESTRATOR_CONFIG_FILE}")
  fi
  cmd+=("$@")
  "${cmd[@]}"
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
elif field == "publicBaseUrl":
    print((subscription_bot.get("publicBaseUrl") or "https://farel-subscription-bot.jolkins.id.lv/pixel-stack/subscription").strip())
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
    echo "Subscription bot readiness preflight failed: missing orchestrator config ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  ingress_mode="$(orchestrator_subscription_bot_field "ingressMode" | tr -d '\r' | tr -d '[:space:]')"
  if [[ "${ingress_mode}" != "cloudflare_tunnel" ]]; then
    return 0
  fi

  if [[ ! -x "${TUNNEL_PROVISION_SCRIPT}" ]]; then
    echo "Subscription bot readiness preflight failed: missing ${TUNNEL_PROVISION_SCRIPT}" >&2
    exit 1
  fi
  if ! command -v cloudflared >/dev/null 2>&1; then
    echo "Subscription bot readiness preflight failed: local cloudflared CLI is required when ingressMode=cloudflare_tunnel" >&2
    exit 1
  fi

  tunnel_name="$(orchestrator_subscription_bot_field "tunnelName" | tr -d '\r')"
  tunnel_hostname="$(orchestrator_subscription_bot_field "publicHostname" | tr -d '\r')"
  if [[ -z "${tunnel_hostname}" ]]; then
    echo "Subscription bot readiness preflight failed: subscriptionBot.publicBaseUrl hostname is empty in ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  log "Ensuring Cloudflare tunnel route/credentials for ${tunnel_name} (${tunnel_hostname})"
  TUNNEL_NAME="${tunnel_name}" \
  TUNNEL_HOSTNAME="${tunnel_hostname}" \
  PIXEL_CREDENTIALS_FILE="/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json" \
    "${TUNNEL_PROVISION_SCRIPT}"
}

public_dns_query() {
  local hostname="$1"
  local resolver="$2"
  local ip=""

  if command -v dig >/dev/null 2>&1; then
    ip="$(dig +short "${hostname}" "@${resolver}" | awk 'NF { print; exit }')"
  elif command -v nslookup >/dev/null 2>&1; then
    ip="$(nslookup "${hostname}" "${resolver}" 2>/dev/null | awk '/^Address: / { print $2 }' | tail -n +2 | head -n 1)"
  else
    return 1
  fi

  [[ -n "${ip}" ]] || return 1
  printf '%s\n' "${ip}"
}

resolve_public_hostname_override() {
  local hostname="$1"
  local ip=""
  local resolver=""

  for resolver in 1.1.1.1 8.8.8.8; do
    if ip="$(public_dns_query "${hostname}" "${resolver}")"; then
      printf '%s\n' "${ip}"
      return 0
    fi
  done

  return 1
}

local_dns_resolves_hostname() {
  local hostname="$1"
  python3 - "${hostname}" <<'PY'
import socket
import sys

hostname = sys.argv[1]
try:
    socket.getaddrinfo(hostname, None, type=socket.SOCK_STREAM)
except OSError:
    raise SystemExit(1)
PY
}

curl_public_url_to_file() {
  local path="$1"
  local output_file="$2"
  local url="${PUBLIC_BASE_URL}${path}"

  if local_dns_resolves_hostname "${PUBLIC_HOSTNAME}"; then
    curl -fsS "${url}" >"${output_file}"
    return 0
  fi

  public_dns_ip="$(resolve_public_hostname_override "${PUBLIC_HOSTNAME}")" || {
    echo "Could not resolve ${PUBLIC_HOSTNAME} locally or via public DNS (1.1.1.1/8.8.8.8)" >&2
    exit 1
  }
  log "Local DNS has not picked up ${PUBLIC_HOSTNAME} yet; using public DNS ${public_dns_ip} for ${url}"
  curl -fsS --resolve "${PUBLIC_HOSTNAME}:${PUBLIC_PORT}:${public_dns_ip}" "${url}" >"${output_file}"
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
ensure_output_dirs
ensure_local_env

for cmd in curl python3; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    log "Missing required command: ${cmd}"
    exit 1
  fi
done

set -a
# shellcheck source=/dev/null
. "${REPO_ROOT}/.env"
set +a

: "${BOT_TOKEN:?BOT_TOKEN must be set in workloads/subscription-bot/.env}"
: "${SUBSCRIPTION_BOT_OPERATOR_IDS:?SUBSCRIPTION_BOT_OPERATOR_IDS must be set in workloads/subscription-bot/.env}"
EXPECTED_BOT_USERNAME="${SUBSCRIPTION_BOT_TELEGRAM_BOT_USERNAME:-${SUBSCRIPTION_BOT_BOT_USERNAME:-${SUBSCRIPTION_BOT_EXPECTED_USERNAME:-}}}"
: "${EXPECTED_BOT_USERNAME:?SUBSCRIPTION_BOT_TELEGRAM_BOT_USERNAME must be set in workloads/subscription-bot/.env}"
PAYMENT_PROVIDER="${SUBSCRIPTION_BOT_PAYMENT_PROVIDER:-nowpayments}"
PUBLIC_BASE_URL="${SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL:-https://farel-subscription-bot.jolkins.id.lv/pixel-stack/subscription}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL%/}"
CONFIG_PUBLIC_BASE_URL="$(orchestrator_subscription_bot_field "publicBaseUrl" | tr -d '\r')"
CONFIG_PUBLIC_BASE_URL="${CONFIG_PUBLIC_BASE_URL%/}"
PUBLIC_URL_PARTS="$(
python3 - "${PUBLIC_BASE_URL}" <<'PY'
from urllib.parse import urlparse
import sys

parsed = urlparse(sys.argv[1])
default_port = 443 if parsed.scheme == "https" else 80
print(parsed.hostname or "")
print(parsed.port or default_port)
PY
)"
PUBLIC_HOSTNAME="$(printf '%s\n' "${PUBLIC_URL_PARTS}" | sed -n '1p')"
PUBLIC_PORT="$(printf '%s\n' "${PUBLIC_URL_PARTS}" | sed -n '2p')"
PUBLIC_PORT="${PUBLIC_PORT:-443}"
if [[ "${CONFIG_PUBLIC_BASE_URL}" != "${PUBLIC_BASE_URL}" ]]; then
  echo "SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL in workloads/subscription-bot/.env does not match subscriptionBot.publicBaseUrl in ${ORCHESTRATOR_CONFIG_FILE}" >&2
  echo "env=${PUBLIC_BASE_URL}" >&2
  echo "config=${CONFIG_PUBLIC_BASE_URL}" >&2
  exit 1
fi
if [[ "${PAYMENT_PROVIDER}" != "nowpayments" ]]; then
  echo "SUBSCRIPTION_BOT_PAYMENT_PROVIDER must be nowpayments for production readiness checks (got ${PAYMENT_PROVIDER})" >&2
  exit 1
fi
if [[ "${SUBSCRIPTION_BOT_WEB_ENABLED:-true}" != "true" ]]; then
  echo "SUBSCRIPTION_BOT_WEB_ENABLED must stay true for the native Mini App production surface" >&2
  exit 1
fi
if [[ "${SUBSCRIPTION_BOT_WEB_SHELL_ENABLED:-true}" != "true" ]]; then
  echo "SUBSCRIPTION_BOT_WEB_SHELL_ENABLED must stay true for the native Mini App production surface" >&2
  exit 1
fi
: "${SUBSCRIPTION_BOT_NOWPAYMENTS_API_KEY:?SUBSCRIPTION_BOT_NOWPAYMENTS_API_KEY must be set for nowpayments}"
: "${SUBSCRIPTION_BOT_NOWPAYMENTS_IPN_SECRET:?SUBSCRIPTION_BOT_NOWPAYMENTS_IPN_SECRET must be set for nowpayments}"

ensure_subscription_bot_web_tunnel_provisioned

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
report_dir="${REPO_ROOT}/output/pixel"
health_log="${report_dir}/subscription-bot-health-${timestamp_utc}.log"
source_env_log="${report_dir}/subscription-bot-source-env-${timestamp_utc}.env"
runtime_env_log="${report_dir}/subscription-bot-runtime-env-${timestamp_utc}.env"
env_drift_log="${report_dir}/subscription-bot-env-drift-${timestamp_utc}.json"
public_health_log="${report_dir}/subscription-bot-public-health-${timestamp_utc}.json"
public_root_shell_log="${report_dir}/subscription-bot-public-root-shell-${timestamp_utc}.html"
public_app_shell_log="${report_dir}/subscription-bot-public-app-shell-${timestamp_utc}.html"
public_admin_shell_log="${report_dir}/subscription-bot-public-admin-shell-${timestamp_utc}.html"
getme_log="${report_dir}/subscription-bot-telegram-getme-${timestamp_utc}.json"
main_app_probe_log="${report_dir}/subscription-bot-telegram-main-app-${timestamp_utc}.html"
commands_log="${report_dir}/subscription-bot-telegram-commands-${timestamp_utc}.json"
menu_log="${report_dir}/subscription-bot-telegram-menu-${timestamp_utc}.json"

log "Checking on-device orchestrator health for subscription_bot"
run_orchestrator \
  --subscription-bot-env-file "${REPO_ROOT}/.env" \
  --action health_component \
  --component subscription_bot >"${health_log}" 2>&1

log "Checking subscription bot runtime env matches the saved source env for managed web settings"
pixel_transport_pull "/data/local/pixel-stack/conf/apps/subscription-bot.env" "${source_env_log}"
pixel_transport_pull "/data/local/pixel-stack/apps/subscription-bot/env/subscription-bot.env" "${runtime_env_log}"
python3 - "${source_env_log}" "${runtime_env_log}" "${env_drift_log}" <<'PY'
import json
import pathlib
import sys

source_path = pathlib.Path(sys.argv[1])
runtime_path = pathlib.Path(sys.argv[2])
report_path = pathlib.Path(sys.argv[3])
managed_keys = [
    "SUBSCRIPTION_BOT_E2E_MODE",
    "SUBSCRIPTION_BOT_WEB_ENABLED",
    "SUBSCRIPTION_BOT_WEB_SHELL_ENABLED",
    "SUBSCRIPTION_BOT_WEB_BIND_ADDR",
    "SUBSCRIPTION_BOT_WEB_PORT",
    "SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL",
    "SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED",
    "SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE",
]


def read_env(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
      line = raw_line.strip()
      if not line or line.startswith("#") or "=" not in raw_line:
          continue
      key, value = raw_line.split("=", 1)
      key = key.strip()
      if not key:
          continue
      values[key] = value.strip()
    return values


source_values = read_env(source_path)
runtime_values = read_env(runtime_path)
mismatches = []
for key in managed_keys:
    source_value = source_values.get(key)
    runtime_value = runtime_values.get(key)
    if source_value != runtime_value:
        mismatches.append(
            {
                "key": key,
                "source": source_value,
                "runtime": runtime_value,
            }
        )

report = {
    "source_env": str(source_path),
    "runtime_env": str(runtime_path),
    "managed_keys": managed_keys,
    "mismatches": mismatches,
}
report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

if mismatches:
    details = "; ".join(
        f"{item['key']}: source={item['source']!r} runtime={item['runtime']!r}"
        for item in mismatches
    )
    raise SystemExit(f"subscription bot runtime env drift detected: {details}")
PY

log "Checking public callback health URL"
curl_public_url_to_file "/api/v1/health" "${public_health_log}"
python3 - "${public_health_log}" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
if not payload.get("ok"):
    raise SystemExit("public health check did not return ok=true")
if payload.get("service") != "subscription_bot":
    raise SystemExit(f"unexpected service marker: {payload!r}")
PY

log "Checking public Mini App shell routes"
curl_public_url_to_file "" "${public_root_shell_log}"
curl_public_url_to_file "/app" "${public_app_shell_log}"
curl_public_url_to_file "/admin" "${public_admin_shell_log}"
python3 - "${public_root_shell_log}" "${public_app_shell_log}" "${public_admin_shell_log}" <<'PY'
import pathlib
import sys

checks = [
    (pathlib.Path(sys.argv[1]), 'data-mode="launcher"'),
    (pathlib.Path(sys.argv[2]), 'data-mode="app"'),
    (pathlib.Path(sys.argv[3]), 'data-mode="admin"'),
]

for path, marker in checks:
    html = path.read_text(encoding="utf-8")
    if marker not in html:
        raise SystemExit(f"{path} did not render expected shell marker {marker!r}")
    if "/pixel-stack/subscription/assets/app.js?v=" not in html:
        raise SystemExit(f"{path} did not reference the versioned app bundle")
PY

telegram_base="https://api.telegram.org/bot${BOT_TOKEN}"

log "Checking Telegram bot identity and Main Mini App"
curl -fsS "${telegram_base}/getMe" >"${getme_log}"
curl -fsS "https://t.me/${EXPECTED_BOT_USERNAME}?startapp" >"${main_app_probe_log}"
python3 - "${getme_log}" "${EXPECTED_BOT_USERNAME}" "${main_app_probe_log}" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
if not payload.get("ok"):
    raise SystemExit(f"getMe failed: {payload}")
result = payload.get("result", {})
username = result.get("username", "")
expected = sys.argv[2]
if expected and username != expected:
    raise SystemExit(f"unexpected bot username: {username!r} != {expected!r}")
if not result.get("has_main_web_app"):
    launch_html = open(sys.argv[3], "r", encoding="utf-8").read()
    expected_launch = f"tg://resolve?domain={expected}&amp;startapp"
    if expected_launch not in launch_html and f"Telegram: Launch @{expected}" not in launch_html:
        raise SystemExit("Telegram Main Mini App is not configured (getMe.has_main_web_app=false and the public startapp launch page is missing)")
PY

log "Checking Telegram command list"
curl -fsS "${telegram_base}/getMyCommands" >"${commands_log}"
python3 - "${commands_log}" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
if not payload.get("ok"):
    raise SystemExit(f"getMyCommands failed: {payload}")
commands = {item.get("command") for item in payload.get("result", [])}
required = {
    "start",
    "help",
    "create_plan",
    "my_plans",
    "join",
    "pay",
    "invoice",
    "renew",
    "members",
    "ledger",
    "support",
    "settings",
    "admin",
    "cancel",
}
missing = sorted(required - commands)
if missing:
    raise SystemExit(f"missing Telegram commands: {', '.join(missing)}")
PY

log "Checking Telegram menu button is no longer a web app shortcut"
curl -fsS "${telegram_base}/getChatMenuButton" >"${menu_log}"
python3 - "${menu_log}" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
if not payload.get("ok"):
    raise SystemExit(f"getChatMenuButton failed: {payload}")
menu_type = (payload.get("result") or {}).get("type", "")
if menu_type != "commands":
    raise SystemExit(f"Telegram menu button must remain commands, got {menu_type!r}")
PY

cat <<SUMMARY
Subscription bot production-readiness checks passed.
- Device health: ${health_log}
- Source env snapshot: ${source_env_log}
- Runtime env snapshot: ${runtime_env_log}
- Env drift report: ${env_drift_log}
- Public health: ${public_health_log}
- Public root shell: ${public_root_shell_log}
- Public app shell: ${public_app_shell_log}
- Public admin shell: ${public_admin_shell_log}
- Telegram getMe: ${getme_log}
- Telegram main app launch page: ${main_app_probe_log}
- Telegram commands: ${commands_log}
- Telegram menu button: ${menu_log}
SUMMARY
