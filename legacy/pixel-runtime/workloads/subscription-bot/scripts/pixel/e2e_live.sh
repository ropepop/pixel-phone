#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"
# shellcheck source=./browser_use.sh
source "${SCRIPT_DIR}/browser_use.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "${REPO_ROOT}/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-${DEFAULT_ORCHESTRATOR_REPO}}"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
PREPARE_RELEASE_SCRIPT="${SCRIPT_DIR}/prepare_native_release.sh"
TUNNEL_PROVISION_SCRIPT="${SCRIPT_DIR}/provision_cloudflared_tunnel.sh"

BOT_USERNAME="${SUBSCRIPTION_BOT_TELEGRAM_BOT_USERNAME:-${SUBSCRIPTION_BOT_BOT_USERNAME:-farel_subscription_bot}}"
BOT_HANDLE="@${BOT_USERNAME#@}"
CHAT_URL="${BROWSER_USE_CHAT_URL:-https://web.telegram.org/a/}"
SESSION_NAME="${BROWSER_USE_SESSION:-subscription-bot-e2e-${PIXEL_RUN_ID}}"
MANAGE_BROWSER_SESSION=1
if [[ -n "${BROWSER_USE_SESSION:-}" ]]; then
  MANAGE_BROWSER_SESSION=0
fi
PROFILE_SPEC="${BROWSER_USE_PROFILE:-}"
TELEGRAM_MESSAGE_SELECTOR='#editable-message-text[aria-label="Message"], .form-control.allow-selection[aria-label="Message"]'
LOCAL_PORT="${SUBSCRIPTION_BOT_E2E_LOCAL_PORT:-19320}"
REMOTE_PORT="${SUBSCRIPTION_BOT_WEB_REMOTE_PORT:-9320}"
LOCAL_BASE_PATH="${SUBSCRIPTION_BOT_LOCAL_BASE_PATH:-/pixel-stack/subscription}"
OPERATOR_ID="${SUBSCRIPTION_BOT_E2E_OPERATOR_ID:-9900001001}"
ORCHESTRATOR_ACTION_TIMEOUT_SEC="${ORCHESTRATOR_ACTION_TIMEOUT_SEC:-300}"
ORCHESTRATOR_COMMAND_TIMEOUT_SEC="${SUBSCRIPTION_BOT_E2E_ORCHESTRATOR_TIMEOUT_SEC:-150}"
export ORCHESTRATOR_ACTION_TIMEOUT_SEC

SKIP_BUILD=0
PRECHECK_ONLY=0
KEEP_E2E_CONFIG=0

TMP_ROOT=""
ARTIFACT_DIR=""
STEPS_DIR=""
WARNINGS_FILE=""
REPORT_JSON=""
PREPARE_LOG=""
DEPLOY_LOG=""
RESTORE_LOG=""
DEVICE_HEALTH_FILE=""
PREFLIGHT_DETAILS=""
OWNER_DETAILS=""
OWNER_RENEW_DETAILS=""
SYNTHETIC_DETAILS=""
SYNTHETIC_TRACE_DIR=""
SYNTHETIC_STDOUT_FILE=""
SYNTHETIC_ERROR_FILE=""
ORIGINAL_CONFIG_FILE=""
ORIGINAL_ENV_FILE=""
E2E_CONFIG_FILE=""
E2E_ENV_FILE=""
RESTORE_CONFIG_FILE=""
PROMOTE_CONFIG_FILE=""

CURRENT_STEP=""
CURRENT_ACTOR="system"
CURRENT_STEP_FILE=""
CURRENT_STEP_DETAILS=""
CURRENT_STEP_STARTED=""
RUN_STATUS="failed"
DEPLOYED_E2E=0
FORWARD_STARTED=0
ORIGINAL_ENABLED="true"
BASE_URL=""
BOT_TOKEN=""
NOWPAYMENTS_IPN_SECRET=""
REQUIRED_CONFIRMATIONS=""
BOT_TELEGRAM_ID=""
RELEASE_DIR=""
OWNER_PLAN_ID=""
OWNER_INVITE_CODE=""
OWNER_RENEWAL_DATE=""
OWNER_FLOW_STARTED_AT=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Runs the live subscription bot E2E harness against the connected Pixel.

Options:
  --skip-build            skip rebuilding the orchestrator APK during deploy
  --preflight-only        stop after deploy + live preflight checks
  --keep-e2e-config       leave the device on the temporary e2e config after the run
  --bot-username NAME     Telegram bot username (default: ${BOT_USERNAME})
  --chat-url URL          Telegram Web URL to open (default: ${CHAT_URL})
  --profile NAME          named browser-use Chrome profile to use
  --local-port PORT       local forwarded port for the web app (default: ${LOCAL_PORT})
  --operator-id ID        fixed synthetic operator Telegram id (default: ${OPERATOR_ID})
  --device SERIAL         adb serial to target
  --transport MODE        transport to use (adb|ssh|auto)
  --ssh-host IP           Tailscale or SSH host/IP
  --ssh-port PORT         SSH port (default: 2222)
  -h, --help              show help
USAGE
}

append_transport_args() {
  local ref_name="$1"
  pixel_transport_append_cli_args "${ref_name}"
}

run_orchestrator() {
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  if (( SKIP_BUILD == 1 )); then
    cmd+=(--skip-build)
  fi
  cmd+=("$@")
  "${cmd[@]}"
}

run_orchestrator_timed() {
  local timeout_sec="$1"
  shift

  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  if (( SKIP_BUILD == 1 )); then
    cmd+=(--skip-build)
  fi
  cmd+=("$@")

  python3 - "${timeout_sec}" "${cmd[@]}" <<'PY'
import os
import signal
import subprocess
import sys

timeout_sec = int(sys.argv[1])
cmd = sys.argv[2:]

proc = subprocess.Popen(
    cmd,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    text=True,
    start_new_session=True,
)

try:
    output, _ = proc.communicate(timeout=timeout_sec)
    sys.stdout.write(output or "")
    raise SystemExit(proc.returncode)
except subprocess.TimeoutExpired:
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        output, _ = proc.communicate(timeout=10)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        output, _ = proc.communicate()
    sys.stdout.write(output or "")
    raise SystemExit(124)
PY
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
    echo "Subscription bot e2e preflight failed: missing orchestrator config ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  ingress_mode="$(orchestrator_subscription_bot_field "ingressMode" | tr -d '\r' | tr -d '[:space:]')"
  if [[ "${ingress_mode}" != "cloudflare_tunnel" ]]; then
    return 0
  fi

  if [[ ! -x "${TUNNEL_PROVISION_SCRIPT}" ]]; then
    echo "Subscription bot e2e preflight failed: missing ${TUNNEL_PROVISION_SCRIPT}" >&2
    exit 1
  fi
  if ! command -v cloudflared >/dev/null 2>&1; then
    echo "Subscription bot e2e preflight failed: local cloudflared CLI is required when ingressMode=cloudflare_tunnel" >&2
    exit 1
  fi

  tunnel_name="$(orchestrator_subscription_bot_field "tunnelName" | tr -d '\r')"
  tunnel_hostname="$(orchestrator_subscription_bot_field "publicHostname" | tr -d '\r')"
  if [[ -z "${tunnel_hostname}" ]]; then
    echo "Subscription bot e2e preflight failed: subscriptionBot.publicBaseUrl hostname is empty in ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  log "Ensuring Cloudflare tunnel route/credentials for ${tunnel_name} (${tunnel_hostname})"
  TUNNEL_NAME="${tunnel_name}" \
  TUNNEL_HOSTNAME="${tunnel_hostname}" \
  PIXEL_CREDENTIALS_FILE="/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json" \
    "${TUNNEL_PROVISION_SCRIPT}"
}

write_step_file() {
  local file="$1"
  local name="$2"
  local status="$3"
  local actor="$4"
  local summary="$5"
  local details_file="$6"
  local started_at="$7"
  local completed_at="$8"
  python3 - "$file" "$name" "$status" "$actor" "$summary" "$details_file" "$started_at" "$completed_at" <<'PY'
import json
import sys

file_path, name, status, actor, summary, details_file, started_at, completed_at = sys.argv[1:9]
payload = {
    "name": name,
    "status": status,
    "actor": actor,
    "summary": summary,
    "started_at": started_at,
    "completed_at": completed_at,
}
if details_file:
    payload["details_file"] = details_file
with open(file_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
PY
}

begin_step() {
  local name="$1"
  local actor="$2"
  CURRENT_STEP="${name}"
  CURRENT_ACTOR="${actor}"
  CURRENT_STEP_STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  CURRENT_STEP_FILE="${STEPS_DIR}/$(printf '%02d' "$(find "${STEPS_DIR}" -maxdepth 1 -name '*.json' | wc -l | tr -d '[:space:]')")-${name}.json"
  CURRENT_STEP_DETAILS=""
}

finish_step() {
  local status="$1"
  local summary="$2"
  local details_file="${3:-${CURRENT_STEP_DETAILS}}"
  write_step_file \
    "${CURRENT_STEP_FILE}" \
    "${CURRENT_STEP}" \
    "${status}" \
    "${CURRENT_ACTOR}" \
    "${summary}" \
    "${details_file}" \
    "${CURRENT_STEP_STARTED}" \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  CURRENT_STEP=""
  CURRENT_ACTOR="system"
  CURRENT_STEP_FILE=""
  CURRENT_STEP_DETAILS=""
  CURRENT_STEP_STARTED=""
}

complete_step() {
  finish_step "ok" "$1" "${2:-}"
}

refresh_local_forward() {
  pixel_transport_forward_start "${LOCAL_PORT}" "${REMOTE_PORT}"
  FORWARD_STARTED=1
}

open_owner_browser_session() {
  browser_use_prepare_profile "${PROFILE_SPEC}"

  if (( MANAGE_BROWSER_SESSION == 0 )) && browser_use_session_exists "${SESSION_NAME}"; then
    browser_use_run "${SESSION_NAME}" open "${CHAT_URL}" >/dev/null
  else
    if (( MANAGE_BROWSER_SESSION == 1 )); then
      browser_use_run "${SESSION_NAME}" close >/dev/null 2>&1 || true
    fi
    browser_use_run_with_profile "${SESSION_NAME}" "${PROFILE_SPEC}" open "${CHAT_URL}" >/dev/null
  fi

  browser_use_run "${SESSION_NAME}" wait 4000 >/dev/null
}

browser_use_click_matching() {
  local session="$1"
  local pattern="$2"
  browser_use_run "${session}" eval "$(browser_use__click_selector_script "${pattern}")" >/dev/null
  browser_use_output_has 'clicked=1'
}

browser_use_click_visible_ref() {
  local session="$1"
  local pattern="$2"
  local ref=""
  local attempt=""
  local state_output=""

  for attempt in 1 2 3 4 5; do
    state_output="$(browser_use_run_timed "${session}" 15 state || true)"
    ref="$(
      STATE_OUTPUT="${state_output}" PATTERN="${pattern}" python3 - <<'PY'
import os
import re
import sys

state_output = os.environ.get("STATE_OUTPUT", "")
pattern = os.environ.get("PATTERN", "")
lines = state_output.splitlines()
needle = re.compile(pattern)
ref_re = re.compile(r"\[(\d+)\]")
clickable_re = re.compile(r"\[(\d+)\]<(?:a|button)\b|\[(\d+)\]<div\s+role=button\b")

candidates = []
for index, line in enumerate(lines):
    if not needle.search(line):
        continue
    ref = ""
    for radius in range(0, 9):
        positions = [index] if radius == 0 else [index - radius, index + radius]
        for pos in positions:
            if pos < 0 or pos >= len(lines):
                continue
            match = clickable_re.search(lines[pos])
            if match:
                ref = match.group(1) or match.group(2) or ""
                break
        if ref:
            break
    if not ref:
        match = ref_re.search(line)
        if match:
            ref = match.group(1)
    if ref:
        candidates.append(ref)

if candidates:
    print(candidates[-1])
else:
    raise SystemExit(1)
PY
    )"
    if [[ -n "${ref}" ]]; then
      browser_use_run "${session}" click "${ref}" >/dev/null
      return 0
    fi
    browser_use_run "${session}" wait 1000 >/dev/null
  done

  browser_use_run "${session}" eval "$(browser_use__click_selector_script "${pattern}")" >/dev/null
  browser_use_output_has 'clicked=1'
}

telegram_owner_open_chat() {
  local attempt=""
  local open_chat_js=""

  if [[ -n "${BOT_TELEGRAM_ID}" ]]; then
    if browser_use_open_telegram_chat "${SESSION_NAME}" "${BOT_TELEGRAM_ID}" "${BOT_HANDLE}" 'Farel Subscription Bot' 4; then
      browser_use_run "${SESSION_NAME}" wait 2000 >/dev/null
      browser_use_run "${SESSION_NAME}" eval '(() => document.querySelector(".form-control.allow-selection[aria-label=\"Message\"]") ? "messageReady=1" : "messageReady=0")()' >/dev/null
      if browser_use_output_has 'messageReady=1'; then
        return 0
      fi
    fi
  fi

  open_chat_js="$(
    python3 - "${BOT_USERNAME#@}" <<'PY'
import json
import sys

bot_name = json.dumps("Farel Subscription Bot")
bot_handle = json.dumps("@" + sys.argv[1].lstrip("@"))
print(
    f"""(() => {{
  const visible = (node) => Boolean(
    node &&
    node.isConnected &&
    node.getClientRects &&
    node.getClientRects().length > 0 &&
    window.getComputedStyle(node).visibility !== 'hidden' &&
    window.getComputedStyle(node).display !== 'none'
  );
  const text = (node) => String((node && (node.textContent || node.innerText)) || '').trim();
  const matchesBot = (node) => {{
    const value = text(node);
    return value.includes({bot_name}) || value.includes({bot_handle});
  }};
  const clickNode = (node) => {{
    if (!visible(node)) {{
      return false;
    }}
    if (typeof node.click === 'function') {{
      node.click();
      return true;
    }}
    node.dispatchEvent(new MouseEvent('mouseover', {{ bubbles: true, cancelable: true }}));
    node.dispatchEvent(new MouseEvent('mousedown', {{ bubbles: true, cancelable: true }}));
    node.dispatchEvent(new MouseEvent('mouseup', {{ bubbles: true, cancelable: true }}));
    node.dispatchEvent(new MouseEvent('click', {{ bubbles: true, cancelable: true }}));
    return true;
  }};
  const messageReady = () => document.querySelector('#editable-message-text[aria-label="Message"], .form-control.allow-selection[aria-label="Message"]') ? 1 : 0;
  if (messageReady()) {{
    return 'messageReady=1;chatReady=1';
  }}

  const directChat = Array.from(document.querySelectorAll('a, button, [role="button"], span'))
    .find((node) => visible(node) && matchesBot(node));
  if (clickNode(directChat)) {{
    return 'messageReady=' + messageReady() + ';chatReady=' + messageReady() + ';clicked=1';
  }}

  const searchInput = document.querySelector('input[type="text"], input[placeholder*="Search"], [contenteditable="true"][data-placeholder*="Search"]');
  if (!visible(searchInput)) {{
    return 'messageReady=' + messageReady() + ';chatReady=0;searchReady=0';
  }}

  searchInput.focus();
  if ('value' in searchInput) {{
    searchInput.value = {bot_handle};
    searchInput.dispatchEvent(new Event('input', {{ bubbles: true }}));
    searchInput.dispatchEvent(new Event('change', {{ bubbles: true }}));
  }} else {{
    searchInput.textContent = {bot_handle};
    try {{
      searchInput.dispatchEvent(new InputEvent('input', {{ bubbles: true, data: {bot_handle}, inputType: 'insertText' }}));
    }} catch (_) {{
      searchInput.dispatchEvent(new Event('input', {{ bubbles: true }}));
    }}
  }}

  const botEntry = Array.from(document.querySelectorAll('a, button, [role="button"], span'))
    .find((node) => visible(node) && matchesBot(node));
  if (clickNode(botEntry)) {{
    return 'messageReady=' + messageReady() + ';chatReady=' + messageReady() + ';searchReady=1;clicked=1';
  }}

  return 'messageReady=' + messageReady() + ';chatReady=0;searchReady=1;clicked=0';
}})()"""
)
PY
  )"

  for attempt in 1 2 3; do
    browser_use_run "${SESSION_NAME}" open "${CHAT_URL}" >/dev/null
    browser_use_run "${SESSION_NAME}" wait 3000 >/dev/null
    browser_use_run "${SESSION_NAME}" eval "${open_chat_js}" >/dev/null
    if browser_use_output_has 'messageReady=1'; then
      return 0
    fi
    if browser_use_output_has 'clicked=1'; then
      browser_use_run "${SESSION_NAME}" wait 2000 >/dev/null
      browser_use_run "${SESSION_NAME}" eval '(() => document.querySelector(".form-control.allow-selection[aria-label=\"Message\"]") ? "messageReady=1" : "messageReady=0")()' >/dev/null
      if browser_use_output_has 'messageReady=1'; then
        return 0
      fi
    fi
    browser_use_run "${SESSION_NAME}" press Enter >/dev/null 2>&1 || true
    browser_use_run "${SESSION_NAME}" wait 1500 >/dev/null
    browser_use_run "${SESSION_NAME}" eval '(() => document.querySelector(".form-control.allow-selection[aria-label=\"Message\"]") ? "messageReady=1" : "messageReady=0")()' >/dev/null
    if browser_use_output_has 'messageReady=1'; then
      return 0
    fi
  done

  return 1
}

telegram_latest_visible_message_text() {
  local session="$1"
  local latest_message_js=""
  local encoded=""

  latest_message_js="$(cat <<'JS'
(() => {
  const visible = (node) => Boolean(
    node &&
    node.isConnected &&
    node.getClientRects &&
    node.getClientRects().length > 0 &&
    window.getComputedStyle(node).visibility !== 'hidden' &&
    window.getComputedStyle(node).display !== 'none'
  );
  const normalize = (value) => String(value || '').replace(/\s+/g, ' ').trim();
  const composer = document.querySelector('#editable-message-text[aria-label="Message"], .form-control.allow-selection[aria-label="Message"]');
  const composerTop = composer ? composer.getBoundingClientRect().top : window.innerHeight + 1;
  const selectors = [
    '[data-mid]',
    '.message',
    '[class*="message"]',
    '[class*="Message"]',
    '[class*="bubble"]',
    '[class*="Bubble"]',
  ];

  let bestText = '';
  let bestMid = '';
  let bestTop = -Infinity;
  let bestBottom = -Infinity;

  for (const node of document.querySelectorAll(selectors.join(','))) {
    if (!visible(node)) {
      continue;
    }
    const rect = node.getBoundingClientRect();
    if (rect.bottom <= 0 || rect.top >= composerTop) {
      continue;
    }
    const text = normalize(node.innerText || node.textContent);
    const mid = normalize(node.getAttribute('data-mid') || node.getAttribute('data-message-id') || (node.dataset ? (node.dataset.mid || node.dataset.messageId || '') : ''));
    if (!text || text.length > 600) {
      continue;
    }
    if (rect.bottom > bestBottom + 1 || (Math.abs(rect.bottom - bestBottom) <= 1 && rect.top >= bestTop)) {
      bestText = text;
      bestMid = mid;
      bestTop = rect.top;
      bestBottom = rect.bottom;
    }
  }

  return 'message=' + encodeURIComponent(bestText) + ';mid=' + encodeURIComponent(bestMid) + ';bottom=' + Math.round(bestBottom);
})()
JS
)"

  browser_use_run "${session}" eval "${latest_message_js}" >/dev/null
  encoded="$(browser_use_output_value 'message')"
  python3 - "${encoded}" <<'PY'
import sys
import urllib.parse

print(urllib.parse.unquote(sys.argv[1] if len(sys.argv) > 1 else ""))
PY
}

telegram_chat_preview_text() {
  local session="$1"
  local preview_js=""
  local encoded=""

  preview_js="$(cat <<'JS'
(() => {
  const visible = (node) => Boolean(
    node &&
    node.isConnected &&
    node.getClientRects &&
    node.getClientRects().length > 0 &&
    window.getComputedStyle(node).visibility !== 'hidden' &&
    window.getComputedStyle(node).display !== 'none'
  );
  const normalize = (value) => String(value || '').replace(/\s+/g, ' ').trim();
  const matchesBot = (value) => /farel subscription bot|farel_subscription_bot/i.test(String(value || ''));
  const isTimestamp = (value) => /^(?:\d{1,2}:\d{2}|today|yesterday|mon|tue|wed|thu|fri|sat|sun)$/i.test(String(value || '').trim());

  let preview = '';
  for (const node of document.querySelectorAll('a')) {
    if (!visible(node)) {
      continue;
    }
    const rawLines = String(node.innerText || node.textContent || '').split(/\n+/).map((line) => line.trim()).filter(Boolean);
    if (!rawLines.some(matchesBot)) {
      continue;
    }
    const filtered = rawLines
      .map(normalize)
      .filter((line) => line && !matchesBot(line) && !isTimestamp(line) && line.toLowerCase() !== 'bot' && !/^[A-Z]{1,4}$/.test(line));
    if (filtered.length > 0) {
      preview = filtered[filtered.length - 1];
      break;
    }
  }

  return 'preview=' + encodeURIComponent(preview);
})()
JS
)"

  browser_use_run "${session}" eval "${preview_js}" >/dev/null
  encoded="$(browser_use_output_value 'preview')"
  python3 - "${encoded}" <<'PY'
import sys
import urllib.parse

print(urllib.parse.unquote(sys.argv[1] if len(sys.argv) > 1 else ""))
PY
}

lookup_owner_plan() {
  local output_path="$1"
  python3 - "${BASE_URL}" "${BOT_TOKEN}" "${OPERATOR_ID}" "${OWNER_FLOW_STARTED_AT}" "${OWNER_RENEWAL_DATE}" "${output_path}" <<'PY'
import hashlib
import hmac
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.cookies import SimpleCookie
from zoneinfo import ZoneInfo

base_url, bot_token, operator_id_raw, created_after, renewal_date, output_path_raw = sys.argv[1:7]
operator_id = int(operator_id_raw)
output_path = pathlib.Path(output_path_raw)
tz = ZoneInfo("Europe/Riga")
user_agent = "subscription-bot-e2e/1.0"


def decode_body(raw: str):
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw}


def api_request(path, method="GET", payload=None, cookie=None, allow_statuses=()):
    url = base_url.rstrip("/") + path
    headers = {"User-Agent": user_agent}
    data = None
    if cookie:
        headers["Cookie"] = f"subscription_app_session={cookie}"
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return response.status, decode_body(raw), dict(response.headers.items())
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        payload = decode_body(raw)
        if exc.code not in allow_statuses:
            raise SystemExit(f"{request.method} {url} returned {exc.code}: {payload}")
        return exc.code, payload, dict(exc.headers.items())


def telegram_init_data(user_id: int, username: str, language: str):
    auth_date = int(time.time())
    user_payload = json.dumps(
        {
            "id": user_id,
            "first_name": username,
            "username": username,
            "language_code": language,
        },
        separators=(",", ":"),
    )
    pairs = [
        ("auth_date", str(auth_date)),
        ("query_id", f"e2e-{user_id}"),
        ("user", user_payload),
    ]
    data_check = "\n".join(f"{key}={value}" for key, value in pairs)
    secret = hmac.new(b"WebAppData", bot_token.encode("utf-8"), hashlib.sha256).digest()
    signature = hmac.new(secret, data_check.encode("utf-8"), hashlib.sha256).hexdigest()
    return urllib.parse.urlencode(pairs + [("hash", signature)])


def auth_session():
    init_data = telegram_init_data(operator_id, "e2e_operator", "en")
    status, payload, headers = api_request(
        "/api/v1/auth/telegram",
        method="POST",
        payload={"initData": init_data},
    )
    if status != 200 or not payload.get("ok"):
        raise SystemExit(f"operator auth failed: {status} {payload}")
    cookie_jar = SimpleCookie()
    cookie_jar.load(headers.get("Set-Cookie", ""))
    morsel = cookie_jar.get("subscription_app_session")
    if morsel is None:
        raise SystemExit("operator auth did not return a session cookie")
    cookie_value = morsel.value
    session_status, session_payload, _ = api_request("/api/v1/session", cookie=cookie_value)
    if session_status != 200:
        raise SystemExit(f"operator session check failed: {session_status} {session_payload}")
    return cookie_value, session_payload


lookup_request = {
    "created_after": created_after,
    "service_code": "spotify_family",
    "total_price_minor": 1800,
    "seat_limit": 2,
    "renewal_date": renewal_date,
    "access_mode": "invite_seat",
}
operator_cookie, operator_session = auth_session()

for attempt in range(1, 31):
    status, payload, _ = api_request(
        "/api/v1/test/plan-lookup",
        method="POST",
        payload=lookup_request,
        cookie=operator_cookie,
        allow_statuses=(404,),
    )
    if status == 404:
        time.sleep(1)
        continue
    if status != 200 or not payload.get("ok"):
        raise SystemExit(f"plan lookup failed: {status} {payload}")
    plan = payload.get("plan") or {}
    invite = payload.get("invite") or {}
    if not plan.get("ID") or not invite.get("InviteCode"):
        raise SystemExit(f"plan lookup returned incomplete payload: {payload}")
    output_path.write_text(
        json.dumps(
            {
                "lookup_attempts": attempt,
                "request": lookup_request,
                "operator_session": operator_session,
                "plan": plan,
                "invite": invite,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    raise SystemExit(0)

raise SystemExit("timed out waiting for the owner plan to appear in the E2E lookup")
PY
}

record_warning() {
  printf '%s\n' "$1" >>"${WARNINGS_FILE}"
}

finalize_report() {
  python3 - "${ARTIFACT_DIR}" "${REPORT_JSON}" "${RUN_STATUS}" "${PIXEL_RUN_ID}" <<'PY'
import json
import os
import pathlib
import sys

artifact_dir = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
status = sys.argv[3]
run_id = sys.argv[4]
steps_dir = artifact_dir / "steps"
warnings_file = artifact_dir / "warnings.txt"

steps = []
for path in sorted(steps_dir.glob("*.json")):
    with path.open("r", encoding="utf-8") as handle:
        steps.append(json.load(handle))

warnings = []
if warnings_file.exists():
    warnings = [line.strip() for line in warnings_file.read_text(encoding="utf-8").splitlines() if line.strip()]

payload = {
    "run_id": run_id,
    "status": status,
    "artifact_root": str(artifact_dir),
    "warnings": warnings,
    "steps": steps,
}
with report_path.open("w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
PY
}

restore_original_runtime() {
  if (( DEPLOYED_E2E == 0 )) || (( KEEP_E2E_CONFIG == 1 )); then
    return 0
  fi
  local action="redeploy_component"
  if [[ "${ORIGINAL_ENABLED}" != "true" ]]; then
    action="stop_component"
  fi
  local -a args=(
    --skip-build
    --config-file "${RESTORE_CONFIG_FILE}"
    --subscription-bot-env-file "${ORIGINAL_ENV_FILE}"
    --action "${action}"
    --component subscription_bot
  )
  if [[ "${action}" == "redeploy_component" && -n "${RELEASE_DIR}" && -d "${RELEASE_DIR}" ]]; then
    args+=(--component-release-dir "${RELEASE_DIR}")
  fi
  local output=""
  set +e
  output="$(run_orchestrator "${args[@]}" 2>&1)"
  local rc=$?
  set -e
  printf '%s\n' "${output}" >"${RESTORE_LOG}"
  return "${rc}"
}

subscription_runtime_env_lines() {
  pixel_transport_root_exec /system/bin/sh -c \
    "grep -E '^(SUBSCRIPTION_BOT_E2E_MODE|SUBSCRIPTION_BOT_WEB_ENABLED|SUBSCRIPTION_BOT_WEB_SHELL_ENABLED|SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED)=' /data/local/pixel-stack/apps/subscription-bot/env/subscription-bot.env 2>/dev/null || true" | tr -d '\r'
}

subscription_runtime_health_output() {
  pixel_transport_root_exec /system/bin/sh /data/local/pixel-stack/bin/pixel-subscription-health.sh | tr -d '\r'
}

subscription_runtime_matches_expectations() {
  local expected_e2e="$1"
  local expected_web_shell="$2"
  local expected_tunnel="$3"
  local env_lines="" health_output=""

  env_lines="$(subscription_runtime_env_lines)"
  health_output="$(subscription_runtime_health_output)"

  grep -q '^healthy=1$' <<<"${health_output}" || return 1
  grep -q "^SUBSCRIPTION_BOT_E2E_MODE=${expected_e2e}$" <<<"${env_lines}" || return 1
  grep -q '^SUBSCRIPTION_BOT_WEB_ENABLED=true$' <<<"${env_lines}" || return 1
  grep -q "^SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=${expected_web_shell}$" <<<"${env_lines}" || return 1
  grep -q "^SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED=${expected_tunnel}$" <<<"${env_lines}" || return 1
}

recover_subscription_redeploy_wrapper_failure() {
  local output="$1"
  local stage_name="$2"
  local expected_e2e="$3"
  local expected_web_shell="$4"
  local expected_tunnel="$5"
  local action_result_path="" action_result_json="" action_result_success="false"

  action_result_path="/data/local/pixel-stack/run/orchestrator-action-results/${PIXEL_RUN_ID}--redeploy_component--subscription_bot.json"
  action_result_json="$(pixel_transport_root_exec cat "${action_result_path}" 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "${action_result_json}" ]]; then
    action_result_success="$(
      ACTION_RESULT_JSON="${action_result_json}" python3 - <<'PY'
import json
import os

raw = os.environ.get("ACTION_RESULT_JSON", "").strip()
if not raw:
    print("false")
    raise SystemExit(0)

try:
    payload = json.loads(raw)
except json.JSONDecodeError:
    print("false")
    raise SystemExit(0)

print("true" if payload.get("success") is True else "false")
PY
    )"
  fi

  if ! grep -Fq 'Action redeploy_component reported SUCCESS' <<<"${output}" && [[ "${action_result_success}" != "true" ]]; then
    return 1
  fi
  if ! subscription_runtime_matches_expectations "${expected_e2e}" "${expected_web_shell}" "${expected_tunnel}"; then
    return 1
  fi

  if grep -Fq 'Action redeploy_component reported SUCCESS' <<<"${output}"; then
    record_warning "${stage_name}: deploy wrapper exited non-zero after a success marker, but the phone verified the subscription bot runtime had already converged; continuing."
  else
    record_warning "${stage_name}: deploy wrapper stalled or exited non-zero, but the on-device action result and the subscription bot runtime both verified the redeploy had already converged; continuing."
  fi
  return 0
}

cleanup() {
  local rc=$?
  trap - ERR
  set +e

  if (( FORWARD_STARTED == 1 )); then
    pixel_transport_forward_stop "${LOCAL_PORT}" >/dev/null 2>&1 || true
  fi
  if (( MANAGE_BROWSER_SESSION == 1 )); then
    browser_use_run "${SESSION_NAME}" close >/dev/null 2>&1 || true
  fi
  restore_original_runtime >/dev/null 2>&1 || true

  if (( rc == 0 )); then
    RUN_STATUS="ok"
  fi
  finalize_report
  rm -rf "${TMP_ROOT}" >/dev/null 2>&1 || true
}

on_error() {
  local rc=$?
  local failed_cmd="${BASH_COMMAND:-unknown}"
  trap - ERR
  if [[ -n "${CURRENT_STEP}" && -n "${CURRENT_STEP_FILE}" && ! -f "${CURRENT_STEP_FILE}" ]]; then
    finish_step "failed" "Step failed while running: ${failed_cmd}"
  fi
  return "${rc}"
}

trap on_error ERR

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    --skip-build)
      SKIP_BUILD=1
      ;;
    --preflight-only)
      PRECHECK_ONLY=1
      ;;
    --keep-e2e-config)
      KEEP_E2E_CONFIG=1
      ;;
    --bot-username)
      BOT_USERNAME="$2"
      BOT_HANDLE="@${BOT_USERNAME#@}"
      CHAT_URL="https://web.telegram.org/a/#${BOT_HANDLE}"
      shift
      ;;
    --chat-url)
      CHAT_URL="$2"
      shift
      ;;
    --profile)
      PROFILE_SPEC="$2"
      shift
      ;;
    --local-port)
      LOCAL_PORT="$2"
      shift
      ;;
    --operator-id)
      OPERATOR_ID="$2"
      shift
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

TMP_ROOT="$(mktemp -d "${REPO_ROOT}/output/pixel/subscription-bot-e2e.${PIXEL_RUN_ID}.XXXXXX")"
ARTIFACT_DIR="${REPO_ROOT}/.artifacts/subscription-bot/e2e/${PIXEL_RUN_ID}"
STEPS_DIR="${ARTIFACT_DIR}/steps"
WARNINGS_FILE="${ARTIFACT_DIR}/warnings.txt"
REPORT_JSON="${ARTIFACT_DIR}/report.json"
PREPARE_LOG="${ARTIFACT_DIR}/prepare-release.log"
DEPLOY_LOG="${ARTIFACT_DIR}/deploy.log"
RESTORE_LOG="${ARTIFACT_DIR}/restore.log"
DEVICE_HEALTH_FILE="${ARTIFACT_DIR}/device-health.txt"
PREFLIGHT_DETAILS="${ARTIFACT_DIR}/preflight-details.json"
OWNER_DETAILS="${ARTIFACT_DIR}/owner-details.json"
OWNER_RENEW_DETAILS="${ARTIFACT_DIR}/owner-renew-details.json"
SYNTHETIC_DETAILS="${ARTIFACT_DIR}/synthetic-details.json"
SYNTHETIC_TRACE_DIR="${ARTIFACT_DIR}/synthetic-flow"
SYNTHETIC_STDOUT_FILE="${ARTIFACT_DIR}/synthetic-flow.stdout.txt"
SYNTHETIC_ERROR_FILE="${ARTIFACT_DIR}/synthetic-flow.stderr.txt"
ORIGINAL_CONFIG_FILE="${TMP_ROOT}/original-config.json"
ORIGINAL_ENV_FILE="${TMP_ROOT}/original-subscription-bot.env"
E2E_CONFIG_FILE="${TMP_ROOT}/e2e-config.json"
E2E_ENV_FILE="${TMP_ROOT}/e2e-subscription-bot.env"
RESTORE_CONFIG_FILE="${TMP_ROOT}/restore-config.json"
PROMOTE_CONFIG_FILE="${TMP_ROOT}/promote-config.json"

mkdir -p "${ARTIFACT_DIR}" "${STEPS_DIR}" "${SYNTHETIC_TRACE_DIR}"
: >"${WARNINGS_FILE}"

trap cleanup EXIT

ensure_output_dirs
ensure_device
ensure_root
browser_use_require_cli
command -v jq >/dev/null 2>&1
command -v python3 >/dev/null 2>&1
command -v curl >/dev/null 2>&1
if [[ -z "${ORCHESTRATOR_REPO}" || ! -d "${ORCHESTRATOR_REPO}" ]]; then
  echo "Cannot resolve orchestrator repo. Set ORCHESTRATOR_REPO explicitly." >&2
  exit 1
fi
[[ -x "${ORCHESTRATOR_DEPLOY_SCRIPT}" ]] || {
  echo "Missing orchestrator deploy script: ${ORCHESTRATOR_DEPLOY_SCRIPT}" >&2
  exit 1
}
[[ -x "${PREPARE_RELEASE_SCRIPT}" ]] || {
  echo "Missing release prepare script: ${PREPARE_RELEASE_SCRIPT}" >&2
  exit 1
}
ensure_subscription_bot_web_tunnel_provisioned

begin_step "deploy_e2e_runtime" "system"
pixel_transport_pull "/data/local/pixel-stack/conf/orchestrator-config-v1.json" "${ORIGINAL_CONFIG_FILE}"
pixel_transport_pull "/data/local/pixel-stack/conf/apps/subscription-bot.env" "${ORIGINAL_ENV_FILE}"
ORIGINAL_ENABLED="$(jq -r '.modules.subscription_bot.enabled // false' "${ORIGINAL_CONFIG_FILE}")"

jq '
  .subscriptionBot.e2eMode = true
  | .subscriptionBot.webShellEnabled = true
  | .subscriptionBot.ingressMode = "direct"
  | .modules = (.modules // {})
  | .modules.subscription_bot = ((.modules.subscription_bot // {}) + {enabled: true})
' "${ORIGINAL_CONFIG_FILE}" >"${E2E_CONFIG_FILE}"

jq '
  .subscriptionBot.e2eMode = false
' "${ORIGINAL_CONFIG_FILE}" >"${RESTORE_CONFIG_FILE}"

if [[ -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
  jq --slurpfile production "${ORCHESTRATOR_CONFIG_FILE}" '
    .subscriptionBot = (($production[0].subscriptionBot // .subscriptionBot) + {e2eMode: false})
    | .modules = (.modules // {})
    | .modules.subscription_bot = ((.modules.subscription_bot // {}) + {enabled: true})
  ' "${ORIGINAL_CONFIG_FILE}" >"${PROMOTE_CONFIG_FILE}"
else
  jq '
    .subscriptionBot.e2eMode = false
    | .modules = (.modules // {})
    | .modules.subscription_bot = ((.modules.subscription_bot // {}) + {enabled: true})
  ' "${ORIGINAL_CONFIG_FILE}" >"${PROMOTE_CONFIG_FILE}"
fi

python3 - "${ORIGINAL_ENV_FILE}" "${E2E_ENV_FILE}" "${OPERATOR_ID}" <<'PY'
import pathlib
import sys

source_path = pathlib.Path(sys.argv[1])
target_path = pathlib.Path(sys.argv[2])
operator_id = sys.argv[3].strip()

lines = source_path.read_text(encoding="utf-8").splitlines() if source_path.exists() else []
order = []
values = {}
for line in lines:
    if "=" not in line or line.lstrip().startswith("#"):
        continue
    key, value = line.split("=", 1)
    key = key.strip()
    if not key:
        continue
    order.append(key)
    values[key] = value

bot_token = (values.get("BOT_TOKEN") or "").strip()
if not bot_token:
    raise SystemExit("subscription bot env on device does not contain BOT_TOKEN")

existing_ids = [item.strip() for item in (values.get("SUBSCRIPTION_BOT_OPERATOR_IDS") or "").split(",") if item.strip()]
if operator_id not in existing_ids:
    existing_ids.append(operator_id)
values["SUBSCRIPTION_BOT_OPERATOR_IDS"] = ",".join(existing_ids)
values["SUBSCRIPTION_BOT_E2E_MODE"] = "true"
values["SUBSCRIPTION_BOT_WEB_ENABLED"] = "true"
values["SUBSCRIPTION_BOT_WEB_SHELL_ENABLED"] = "true"

seen = set()
output_lines = []
for key in order:
    if key in seen:
      continue
    if key in values:
      output_lines.append(f"{key}={values[key]}")
      seen.add(key)
for key in ("BOT_TOKEN", "SUBSCRIPTION_BOT_OPERATOR_IDS"):
    if key not in seen and key in values:
        output_lines.append(f"{key}={values[key]}")
        seen.add(key)
for key, value in values.items():
    if key not in seen:
        output_lines.append(f"{key}={value}")

target_path.write_text("\n".join(output_lines) + "\n", encoding="utf-8")
PY

BOT_TOKEN="$(python3 - "${E2E_ENV_FILE}" <<'PY'
import pathlib
import sys

env_path = pathlib.Path(sys.argv[1])
for line in env_path.read_text(encoding="utf-8").splitlines():
    if line.startswith("BOT_TOKEN="):
        print(line.split("=", 1)[1].strip())
        break
PY
)"
[[ -n "${BOT_TOKEN}" ]] || {
  echo "BOT_TOKEN is missing in ${E2E_ENV_FILE}" >&2
  exit 1
}

NOWPAYMENTS_IPN_SECRET="$(python3 - "${E2E_ENV_FILE}" <<'PY'
import pathlib
import sys

env_path = pathlib.Path(sys.argv[1])
for line in env_path.read_text(encoding="utf-8").splitlines():
    if line.startswith("SUBSCRIPTION_BOT_NOWPAYMENTS_IPN_SECRET="):
        print(line.split("=", 1)[1].strip())
        break
PY
)"
[[ -n "${NOWPAYMENTS_IPN_SECRET}" ]] || {
  echo "SUBSCRIPTION_BOT_NOWPAYMENTS_IPN_SECRET is missing in ${E2E_ENV_FILE}" >&2
  exit 1
}

REQUIRED_CONFIRMATIONS="$(python3 - "${E2E_ENV_FILE}" <<'PY'
import pathlib
import sys

env_path = pathlib.Path(sys.argv[1])
for line in env_path.read_text(encoding="utf-8").splitlines():
    if line.startswith("SUBSCRIPTION_BOT_REQUIRED_CONFIRMATIONS="):
        print(line.split("=", 1)[1].strip())
        break
PY
)"
[[ -n "${REQUIRED_CONFIRMATIONS}" ]] || {
  echo "SUBSCRIPTION_BOT_REQUIRED_CONFIRMATIONS is missing in ${E2E_ENV_FILE}" >&2
  exit 1
}

prepare_output="$("${PREPARE_RELEASE_SCRIPT}" 2>&1)"
printf '%s\n' "${prepare_output}" >"${PREPARE_LOG}"
RELEASE_DIR="$(printf '%s\n' "${prepare_output}" | awk -F= '/^SUBSCRIPTION_BOT_RELEASE_DIR=/{print $2}' | tail -n 1)"
if [[ -z "${RELEASE_DIR}" ]]; then
  RELEASE_DIR="$(printf '%s\n' "${prepare_output}" | tail -n 1 | tr -d '\r')"
fi
[[ -n "${RELEASE_DIR}" && -d "${RELEASE_DIR}" ]] || {
  echo "Failed to resolve subscription bot release dir" >&2
  exit 1
}

DEPLOYED_E2E=1
set +e
deploy_output="$(
  run_orchestrator_timed "${ORCHESTRATOR_COMMAND_TIMEOUT_SEC}" \
    --config-file "${E2E_CONFIG_FILE}" \
    --subscription-bot-env-file "${E2E_ENV_FILE}" \
    --component-release-dir "${RELEASE_DIR}" \
    --action redeploy_component \
    --component subscription_bot 2>&1
)"
deploy_rc=$?
set -e
printf '%s\n' "${deploy_output}" >"${DEPLOY_LOG}"
if (( deploy_rc != 0 )); then
  if ! recover_subscription_redeploy_wrapper_failure "${deploy_output}" "deploy_e2e_runtime" "true" "true" "false"; then
    exit "${deploy_rc}"
  fi
fi
complete_step "Built the current subscription bot release, enabled e2e mode, and redeployed it to the Pixel." "${DEPLOY_LOG}"

begin_step "preflight" "system"
refresh_local_forward
BASE_URL="http://127.0.0.1:${LOCAL_PORT}${LOCAL_BASE_PATH}"

device_health_output="$(pixel_transport_root_exec /system/bin/sh /data/local/pixel-stack/bin/pixel-subscription-health.sh | tr -d '\r')"
printf '%s\n' "${device_health_output}" >"${DEVICE_HEALTH_FILE}"
grep -q '^healthy=1$' <<<"${device_health_output}"

runtime_env_lines="$(
  pixel_transport_root_exec /system/bin/sh -c \
    "grep -E '^(SUBSCRIPTION_BOT_E2E_MODE|SUBSCRIPTION_BOT_OPERATOR_IDS|SUBSCRIPTION_BOT_WEB_ENABLED|SUBSCRIPTION_BOT_WEB_SHELL_ENABLED)=' /data/local/pixel-stack/apps/subscription-bot/env/subscription-bot.env 2>/dev/null || true" | tr -d '\r'
)"
conf_env_lines="$(
  pixel_transport_root_exec /system/bin/sh -c \
    "grep -E '^(SUBSCRIPTION_BOT_OPERATOR_IDS|SUBSCRIPTION_BOT_WEB_ENABLED|SUBSCRIPTION_BOT_WEB_SHELL_ENABLED)=' /data/local/pixel-stack/conf/apps/subscription-bot.env 2>/dev/null || true" | tr -d '\r'
)"

SUBSCRIPTION_BOT_RUNTIME_ENV_LINES="${runtime_env_lines}" \
SUBSCRIPTION_BOT_CONF_ENV_LINES="${conf_env_lines}" \
python3 - "${BASE_URL}" "${BOT_TOKEN}" "${BOT_USERNAME}" "${PREFLIGHT_DETAILS}" "${DEVICE_HEALTH_FILE}" "${OPERATOR_ID}" <<'PY'
import json
import os
import sys
import urllib.error
import urllib.request

base_url, bot_token, bot_username, output_path, health_path, operator_id = sys.argv[1:7]
expected_commands = {
    "admin",
    "cancel",
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
}

user_agent = "subscription-bot-e2e/1.0"

def decode_body(raw: str):
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw}

def fetch_json(url: str, payload=None, allow_statuses=(200,)):
    headers = {"User-Agent": user_agent}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if payload is not None else "GET")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, decode_body(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        payload = decode_body(exc.read().decode("utf-8"))
        if exc.code not in allow_statuses:
            raise SystemExit(f"{request.method} {url} returned {exc.code}: {payload}")
        return exc.code, payload

def fetch_text(url: str, allow_statuses=(200,)):
    request = urllib.request.Request(url, headers={"User-Agent": user_agent}, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8")
        if exc.code not in allow_statuses:
            raise SystemExit(f"{request.method} {url} returned {exc.code}: {body[:400]}")
        return exc.code, body

def parse_env_lines(raw: str):
    values = {}
    for line in raw.splitlines():
        line = line.strip()
        if not line or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values

health_status, health_payload = fetch_json(base_url.rstrip("/") + "/api/v1/health")
if health_status != 200 or not health_payload.get("ok"):
    raise SystemExit("web health endpoint did not return ok")

runtime_values = parse_env_lines(os.environ.get("SUBSCRIPTION_BOT_RUNTIME_ENV_LINES", ""))
runtime_operator_ids = [item.strip() for item in runtime_values.get("SUBSCRIPTION_BOT_OPERATOR_IDS", "").split(",") if item.strip()]
runtime_e2e_mode = runtime_values.get("SUBSCRIPTION_BOT_E2E_MODE", "").lower() == "true"
runtime_web_enabled = runtime_values.get("SUBSCRIPTION_BOT_WEB_ENABLED", "").lower() == "true"
runtime_web_shell_enabled = runtime_values.get("SUBSCRIPTION_BOT_WEB_SHELL_ENABLED", "").lower() == "true"
if not runtime_e2e_mode:
    raise SystemExit("runtime env did not enable SUBSCRIPTION_BOT_E2E_MODE")
if operator_id not in runtime_operator_ids:
    raise SystemExit(f"runtime env is missing operator id {operator_id}")
if not runtime_web_enabled:
    raise SystemExit("runtime env did not enable SUBSCRIPTION_BOT_WEB_ENABLED during e2e mode")
if not runtime_web_shell_enabled:
    raise SystemExit("runtime env did not enable SUBSCRIPTION_BOT_WEB_SHELL_ENABLED during e2e mode")

conf_values = parse_env_lines(os.environ.get("SUBSCRIPTION_BOT_CONF_ENV_LINES", ""))
conf_operator_ids = [item.strip() for item in conf_values.get("SUBSCRIPTION_BOT_OPERATOR_IDS", "").split(",") if item.strip()]
if operator_id not in conf_operator_ids:
    raise SystemExit(f"persisted env is missing operator id {operator_id}")
if conf_values.get("SUBSCRIPTION_BOT_WEB_SHELL_ENABLED", "").lower() != "true":
    raise SystemExit("persisted env did not keep SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=true during e2e mode")

test_status, test_payload = fetch_json(
    base_url.rstrip("/") + "/api/v1/test/process-cycle",
    payload={"at": "2000-01-01T00:00:00Z"},
    allow_statuses=(401,),
)
if test_status != 401:
    raise SystemExit(f"test cycle endpoint did not return 401 while e2e mode was enabled: {test_status} {test_payload}")

telegram_base = f"https://api.telegram.org/bot{bot_token}"
get_me_status, get_me = fetch_json(telegram_base + "/getMe")
if get_me_status != 200 or not get_me.get("ok"):
    raise SystemExit("Telegram getMe failed")
result = get_me.get("result") or {}
username = (result.get("username") or "").strip()
if username != bot_username:
    raise SystemExit(f"Telegram bot username mismatch: expected {bot_username}, got {username or '<empty>'}")
main_app_launch_page_status, main_app_launch_page = fetch_text(f"https://t.me/{bot_username}?startapp")
if main_app_launch_page_status != 200:
    raise SystemExit(f"Telegram startapp launch page probe failed: {main_app_launch_page_status}")
if not result.get("has_main_web_app"):
    expected_launch = f"tg://resolve?domain={bot_username}&amp;startapp"
    if expected_launch not in main_app_launch_page and f"Telegram: Launch @{bot_username}" not in main_app_launch_page:
        raise SystemExit("Telegram Main Mini App is not configured (getMe.has_main_web_app=false and the public startapp launch page is missing)")

commands_status, commands_payload = fetch_json(telegram_base + "/getMyCommands")
if commands_status != 200 or not commands_payload.get("ok"):
    raise SystemExit("Telegram getMyCommands failed")
actual_commands = {item.get("command") for item in commands_payload.get("result") or []}
missing = sorted(expected_commands - actual_commands)
if missing:
    raise SystemExit("Telegram command registration is missing: " + ", ".join(missing))

menu_status, menu_payload = fetch_json(telegram_base + "/getChatMenuButton")
if menu_status != 200 or not menu_payload.get("ok"):
    raise SystemExit("Telegram getChatMenuButton failed")
menu_type = ((menu_payload.get("result") or {}).get("type") or "").strip()

warnings = []
if not result.get("has_main_web_app"):
    warnings.append("Telegram getMe still reports has_main_web_app=false, but the public t.me startapp launch page is live.")
if menu_type != "commands":
    raise SystemExit(f"Telegram menu button must remain commands for the chat-first bot flow, got {menu_type or '<empty>'}")

output = {
    "base_url": base_url,
    "web_health": health_payload,
    "runtime": {
        "e2e_mode": runtime_e2e_mode,
        "web_enabled": runtime_web_enabled,
        "web_shell_enabled": runtime_web_shell_enabled,
        "operator_ids": runtime_operator_ids,
        "persisted_operator_ids": conf_operator_ids,
        "test_endpoint_status": test_status,
        "test_endpoint_payload": test_payload,
    },
    "telegram": {
        "id": result.get("id"),
        "username": username,
        "first_name": result.get("first_name"),
        "has_main_web_app": bool(result.get("has_main_web_app")),
        "main_app_launch_page_status": main_app_launch_page_status,
        "commands": sorted(actual_commands),
        "menu_button": menu_payload.get("result"),
    },
    "device_health_file": health_path,
    "warnings": warnings,
}
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(output, handle, indent=2)
    handle.write("\n")
PY

preflight_warning="$(python3 - "${PREFLIGHT_DETAILS}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
for item in payload.get("warnings") or []:
    print(item)
PY
)"
BOT_TELEGRAM_ID="$(python3 - "${PREFLIGHT_DETAILS}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
value = ((payload.get("telegram") or {}).get("id"))
print("" if value in (None, "") else str(value))
PY
)"
if [[ -n "${preflight_warning}" ]]; then
  while IFS= read -r line; do
    [[ -n "${line}" ]] && record_warning "${line}"
  done <<<"${preflight_warning}"
fi
complete_step "Verified the live service health, Telegram identity, command registration, the live test endpoint wiring, and the operator id loaded into the running runtime." "${PREFLIGHT_DETAILS}"

if (( PRECHECK_ONLY == 1 )); then
  exit 0
fi

OWNER_RENEWAL_DATE="$(python3 - <<'PY'
from datetime import datetime
from zoneinfo import ZoneInfo

now = datetime.now(ZoneInfo("Europe/Riga"))
year = now.year
month = now.month + 2
while month > 12:
    year += 1
    month -= 12
print(f"{year:04d}-{month:02d}-01")
PY
)"

begin_step "owner_flow" "owner"
CURRENT_STEP_DETAILS="${OWNER_DETAILS}"
owner_lookup_file="${ARTIFACT_DIR}/owner-plan-lookup.json"
open_owner_browser_session

if ! telegram_owner_open_chat; then
  echo "browser-use owner flow could not re-open the live bot chat" >&2
  exit 1
fi

browser_use_run "${SESSION_NAME}" eval '(() => document.querySelector(".form-control.allow-selection[aria-label=\"Message\"]") ? "messageReady=1" : "messageReady=0")()' >/dev/null
browser_use_output_has 'messageReady=1'

browser_use_run "${SESSION_NAME}" type "${TELEGRAM_MESSAGE_SELECTOR}" '/start' >/dev/null
browser_use_run "${SESSION_NAME}" press Enter >/dev/null
browser_use_run "${SESSION_NAME}" wait 2500 >/dev/null

OWNER_FLOW_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
browser_use_run "${SESSION_NAME}" type "${TELEGRAM_MESSAGE_SELECTOR}" "/create_plan spotify_family 18.00 2 ${OWNER_RENEWAL_DATE} invite_seat" >/dev/null
browser_use_run "${SESSION_NAME}" press Enter >/dev/null
browser_use_run "${SESSION_NAME}" wait 2500 >/dev/null

refresh_local_forward
lookup_owner_plan "${owner_lookup_file}"
OWNER_PLAN_ID="$(python3 - "${owner_lookup_file}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
print((payload.get("plan") or {}).get("ID", ""))
PY
)"
OWNER_INVITE_CODE="$(python3 - "${owner_lookup_file}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
print((payload.get("invite") or {}).get("InviteCode", ""))
PY
)"
[[ -n "${OWNER_PLAN_ID}" && -n "${OWNER_INVITE_CODE}" ]]

browser_use_write_output "${SESSION_NAME}" "${ARTIFACT_DIR}/owner-flow.snapshot.md" snapshot -c
browser_use_write_output "${SESSION_NAME}" "${ARTIFACT_DIR}/owner-flow.console.log" console
browser_use_write_output "${SESSION_NAME}" "${ARTIFACT_DIR}/owner-flow.network.log" network requests
browser_use_run "${SESSION_NAME}" screenshot "${ARTIFACT_DIR}/owner-flow.png" >/dev/null

python3 - "${OWNER_DETAILS}" "${BOT_USERNAME}" "${CHAT_URL}" "${OWNER_FLOW_STARTED_AT}" "${OWNER_PLAN_ID}" "${OWNER_INVITE_CODE}" "${OWNER_RENEWAL_DATE}" "${owner_lookup_file}" "${ARTIFACT_DIR}/owner-flow.png" "${ARTIFACT_DIR}/owner-flow.snapshot.md" <<'PY'
import json
import sys

(
    output_path,
    bot_username,
    chat_url,
    started_at,
    plan_id,
    invite_code,
    renewal_date,
    lookup_path,
    screenshot_path,
    snapshot_path,
) = sys.argv[1:11]
with open(lookup_path, "r", encoding="utf-8") as handle:
    lookup_payload = json.load(handle)
payload = {
    "bot_username": bot_username,
    "chat_url": chat_url,
    "started_at": started_at,
    "create_command": f"/create_plan spotify_family 18.00 2 {renewal_date} invite_seat",
    "renewal_date": renewal_date,
    "plan_id": plan_id,
    "invite_code": invite_code,
    "plan": lookup_payload.get("plan"),
    "invite": lookup_payload.get("invite"),
    "lookup_attempts": lookup_payload.get("lookup_attempts"),
    "artifacts": {
        "lookup": lookup_path,
        "screenshot": screenshot_path,
        "snapshot": snapshot_path,
    },
}
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
PY

complete_step "Used Telegram Web through browser-use to open the live bot, send /start, create a compliant Spotify Family plan, and extract the invite code." "${OWNER_DETAILS}"

begin_step "synthetic_flow" "member/operator"
CURRENT_STEP_DETAILS="${SYNTHETIC_DETAILS}"
refresh_local_forward
set +e
python3 - "${BASE_URL}" "${BOT_TOKEN}" "${BOT_USERNAME}" "${NOWPAYMENTS_IPN_SECRET}" "${REQUIRED_CONFIRMATIONS}" "${OWNER_PLAN_ID}" "${OWNER_INVITE_CODE}" "${OPERATOR_ID}" "${PIXEL_RUN_ID}" "${SYNTHETIC_DETAILS}" "${SYNTHETIC_TRACE_DIR}" >"${SYNTHETIC_STDOUT_FILE}" 2>"${SYNTHETIC_ERROR_FILE}" <<'PY'
import hashlib
import hmac
import json
import pathlib
import re
import sys
import traceback
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta
from http.cookies import SimpleCookie
from itertools import count
from zoneinfo import ZoneInfo

base_url, bot_token, bot_username, nowpayments_ipn_secret, required_confirmations_raw, plan_id, invite_code, operator_id_raw, run_id, output_path, trace_dir_raw = sys.argv[1:12]
required_confirmations = max(int(required_confirmations_raw), 0)
operator_id = int(operator_id_raw)
output_path = pathlib.Path(output_path)
trace_dir = pathlib.Path(trace_dir_raw)
trace_dir.mkdir(parents=True, exist_ok=True)
trace_counter = count()
update_counter = count(1000)
message_counter = count(2000)
callback_counter = count(1)
trace_files = {}
steps = []
member_cookie = None
operator_cookie = None
member_session = {}
operator_session = {}
tz = ZoneInfo("Europe/Riga")
now = datetime.now(tz)
user_agent = "subscription-bot-e2e/1.0"

def write_trace(label: str, payload):
    path = trace_dir / f"{next(trace_counter):02d}-{label}.json"
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")
    trace_files[label] = str(path)
    return str(path)

def decode_body(raw: str):
    if not raw:
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"raw": raw}

def api_request(path, method="GET", payload=None, cookie=None, extra_headers=None, raw_body=None):
    url = base_url.rstrip("/") + path
    headers = {"User-Agent": user_agent}
    data = None
    if cookie:
        headers["Cookie"] = f"subscription_app_session={cookie}"
    if extra_headers:
        headers.update(extra_headers)
    if raw_body is not None:
        headers.setdefault("Content-Type", "application/json")
        data = raw_body
    elif payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return response.status, decode_body(raw), dict(response.headers.items())
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        return exc.code, decode_body(raw), dict(exc.headers.items())

def expect_status(label: str, status: int, allowed, payload):
    if status not in allowed:
        raise AssertionError(f"{label} returned {status}: {payload}")

def persist_failure(reason: str):
    payload = {
        "status": "failed",
        "reason": reason,
        "plan_id": plan_id,
        "invite_code": invite_code,
        "member_session": member_session,
        "operator_session": operator_session,
        "steps": steps,
        "artifacts": trace_files,
    }
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

def telegram_init_data(bot_token: str, user_id: int, username: str, language: str):
    auth_date = int(datetime.now(tz).timestamp())
    user_payload = json.dumps(
        {
            "id": user_id,
            "first_name": username,
            "username": username,
            "language_code": language,
        },
        separators=(",", ":"),
    )
    pairs = [
        ("auth_date", str(auth_date)),
        ("query_id", f"e2e-{user_id}"),
        ("user", user_payload),
    ]
    data_check = "\n".join(f"{key}={value}" for key, value in pairs)
    secret = hmac.new(b"WebAppData", bot_token.encode("utf-8"), hashlib.sha256).digest()
    signature = hmac.new(secret, data_check.encode("utf-8"), hashlib.sha256).hexdigest()
    return urllib.parse.urlencode(pairs + [("hash", signature)])

def auth_session(user_id: int, username: str, language: str, trace_prefix: str):
    init_data = telegram_init_data(bot_token, user_id, username, language)
    status, payload, headers = api_request(
        "/api/v1/auth/telegram",
        method="POST",
        payload={"initData": init_data},
    )
    write_trace(
        f"{trace_prefix}-auth",
        {
            "status": status,
            "payload": payload,
            "has_session_cookie": bool(headers.get("Set-Cookie")),
        },
    )
    expect_status(f"{trace_prefix} auth", status, (200,), payload)
    if not payload.get("ok"):
        raise AssertionError(f"{trace_prefix} auth did not return ok: {payload}")
    cookie_jar = SimpleCookie()
    cookie_jar.load(headers.get("Set-Cookie", ""))
    morsel = cookie_jar.get("subscription_app_session")
    if morsel is None:
        raise AssertionError(f"session cookie missing for {trace_prefix}")
    cookie_value = morsel.value
    session_status, session_payload, _ = api_request("/api/v1/session", cookie=cookie_value)
    write_trace(
        f"{trace_prefix}-session",
        {
            "status": session_status,
            "payload": session_payload,
        },
    )
    expect_status(f"{trace_prefix} session", session_status, (200,), session_payload)
    return cookie_value, session_payload

def bot_user(user_id: int, username: str):
    return {
        "id": user_id,
        "first_name": username,
        "username": username,
        "language_code": "en",
    }

def build_message_update(user_id: int, username: str, text: str):
    message_id = next(message_counter)
    return {
        "update_id": next(update_counter),
        "message": {
            "message_id": message_id,
            "date": int(datetime.now(tz).timestamp()),
            "chat": {
                "id": user_id,
                "type": "private",
            },
            "from": bot_user(user_id, username),
            "text": text,
        },
    }

def build_callback_update(user_id: int, username: str, data: str):
    message_id = next(message_counter)
    return {
        "update_id": next(update_counter),
        "callback_query": {
            "id": f"cb-{next(callback_counter)}",
            "from": bot_user(user_id, username),
            "message": {
                "message_id": message_id,
                "date": int(datetime.now(tz).timestamp()),
                "chat": {
                    "id": user_id,
                    "type": "private",
                },
            },
            "data": data,
        },
    }

def inject_update(label: str, update):
    status, payload, _ = api_request(
        "/api/v1/test/telegram-update",
        method="POST",
        payload=update,
    )
    write_trace(label, {"request": update, "status": status, "response": payload})
    expect_status(label, status, (200,), payload)
    if not payload.get("ok") or not payload.get("handled"):
        raise AssertionError(f"{label} was not handled successfully: {payload}")
    return payload

def bot_texts(payload):
    texts = []
    for collection in ("edited_messages", "sent_messages"):
        for item in payload.get(collection) or []:
            text = (item.get("text") or "").strip()
            if text:
                texts.append(text)
    return texts

def require_text_contains(label: str, payload, needle: str):
    for text in bot_texts(payload):
        if needle in text:
            return text
    raise AssertionError(f"{label} did not contain {needle!r}: {payload}")

def find_button(payload, *, button_text=None, callback_prefix=None, callback_exact=None):
    containers = list(payload.get("edited_messages") or []) + list(payload.get("sent_messages") or [])
    for item in reversed(containers):
        reply_markup = item.get("reply_markup") or {}
        for row in reply_markup.get("inline_keyboard") or []:
            for button in row:
                text = (button.get("text") or "").strip()
                callback_data = (button.get("callback_data") or "").strip()
                if button_text is not None and text != button_text:
                    continue
                if callback_exact is not None and callback_data != callback_exact:
                    continue
                if callback_prefix is not None and not callback_data.startswith(callback_prefix):
                    continue
                if callback_data:
                    return callback_data
    raise AssertionError(
        f"could not find button text={button_text!r} callback_exact={callback_exact!r} callback_prefix={callback_prefix!r} in {payload}"
    )

def find_button_or_none(payload, *, button_text=None, callback_prefix=None, callback_exact=None):
    try:
        return find_button(
            payload,
            button_text=button_text,
            callback_prefix=callback_prefix,
            callback_exact=callback_exact,
        )
    except AssertionError:
        return None

def find_web_app_url(payload, *, button_text=None, url_contains=None):
    containers = list(payload.get("edited_messages") or []) + list(payload.get("sent_messages") or [])
    for item in reversed(containers):
        reply_markup = item.get("reply_markup") or {}
        for row in reply_markup.get("inline_keyboard") or []:
            for button in row:
                text = (button.get("text") or "").strip()
                web_app = button.get("web_app") or {}
                url = str(web_app.get("url") or "").strip()
                if not url:
                    continue
                if button_text is not None and text != button_text:
                    continue
                if url_contains is not None and url_contains not in url:
                    continue
                return url
    raise AssertionError(f"could not find web_app button text={button_text!r} url_contains={url_contains!r} in {payload}")

def extract_match(label: str, text: str, pattern: str):
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        raise AssertionError(f"{label} did not match {pattern!r}: {text}")
    return match.group(1)

def next_month_anchor(base: datetime):
    year = base.year
    month = base.month + 2
    while month > 12:
        year += 1
        month -= 12
    return datetime(year, month, 1, 0, 0, 0, tzinfo=tz)

def amount_minus(raw: str, delta: int) -> str:
    return str(int(raw) - delta)

def load_latest_invoice(cookie, label: str, expected_invoice_id: str):
    status, payload, _ = api_request("/api/v1/invoices/latest", cookie=cookie)
    write_trace(label, {"status": status, "payload": payload})
    expect_status(label, status, (200,), payload)
    invoice = payload.get("invoice") or {}
    invoice_id = str(invoice.get("ID") or "").strip()
    if not invoice_id:
        raise AssertionError(f"{label} did not return an invoice: {payload}")
    if invoice_id != expected_invoice_id:
        raise AssertionError(f"{label} expected invoice {expected_invoice_id}, got {invoice_id}: {payload}")
    return invoice

def nowpayments_currency_code(asset: str, network: str) -> str:
    key = f"{str(asset or '').strip().upper()}:{str(network or '').strip().lower()}"
    mapping = {
        "USDC:solana": "usdcsol",
        "USDC:base": "usdcbase",
        "USDT:tron": "usdttrc20",
        "USDT:solana": "usdtsol",
        "ETH:base": "ethbase",
        "BTC:bitcoin": "btc",
        "SOL:solana": "sol",
    }
    return mapping.get(key, str(asset or "").strip().lower())

def atomic_decimals_for_currency(pay_currency: str) -> int:
    mapping = {
        "usdcsol": 6,
        "usdcbase": 6,
        "usdttrc20": 6,
        "usdtsol": 6,
        "ethbase": 18,
        "btc": 8,
        "sol": 9,
    }
    return mapping.get(str(pay_currency or "").strip().lower(), 8)

def atomic_to_decimal_text(amount_atomic: str, decimals: int) -> str:
    raw = str(amount_atomic or "").strip()
    if not raw:
        raise AssertionError("atomic amount is required")
    negative = raw.startswith("-")
    if negative:
        raw = raw[1:]
    if not raw.isdigit():
        raise AssertionError(f"invalid atomic amount: {amount_atomic!r}")
    raw = raw.zfill(decimals + 1)
    if decimals == 0:
        value = raw
    else:
        value = raw[:-decimals] + "." + raw[-decimals:]
    if negative and value != "0":
        value = "-" + value
    return value

def post_nowpayments_webhook(label: str, provider_invoice_id: str, amount_atomic: str, pay_currency: str, tx_hash: str, payment_status: str):
    amount_text = atomic_to_decimal_text(amount_atomic, atomic_decimals_for_currency(pay_currency))
    body = {
        "payment_id": provider_invoice_id,
        "payment_status": payment_status,
        "pay_amount": amount_text,
        "actually_paid": amount_text,
        "pay_currency": pay_currency,
        "payin_hash": tx_hash,
        "confirmations": required_confirmations,
    }
    raw_body = json.dumps(body, separators=(",", ":")).encode("utf-8")
    signature = hmac.new(nowpayments_ipn_secret.encode("utf-8"), raw_body, hashlib.sha512).hexdigest()
    status, payload, _ = api_request(
        "/api/v1/payments/webhook/nowpayments",
        method="POST",
        raw_body=raw_body,
        extra_headers={"x-nowpayments-sig": signature},
    )
    write_trace(label, {"status": status, "request": body, "response": payload})
    expect_status(label, status, (200,), payload)
    if not payload.get("ok") or not payload.get("invoice_found"):
        raise AssertionError(f"{label} did not settle a known invoice: {payload}")
    return payload

def ledger_events(payload):
    items = payload.get("Events")
    if items is None:
        items = payload.get("events")
    return items or []

def admin_support_total(payload):
    if "SupportOpenTotal" in payload:
        return payload.get("SupportOpenTotal", 0)
    return payload.get("supportOpenTotal", 0)

def id_of(item):
    obj = item or {}
    direct = str(obj.get("ID") or obj.get("id") or "").strip()
    if direct:
        return direct
    for nested_key in ("Plan", "plan", "Ticket", "ticket", "Invoice", "invoice", "Membership", "membership"):
        nested = obj.get(nested_key)
        if isinstance(nested, dict):
            nested_id = id_of(nested)
            if nested_id:
                return nested_id
    return ""

def denylist_value_of(item):
    obj = item or {}
    return str(
        obj.get("EntryValue")
        or obj.get("entryValue")
        or obj.get("entry_value")
        or obj.get("Value")
        or obj.get("value")
        or ""
    ).strip()

def ensure_html_shell(label: str, path: str, mode: str):
    status, payload, headers = api_request(path)
    write_trace(label, {"status": status, "payload": payload, "headers": headers})
    expect_status(label, status, (200,), payload)
    html = str(payload.get("raw") or "")
    if "window.SUBSCRIPTION_APP_CONFIG" not in html:
        raise AssertionError(f"{label} did not include the shell config block")
    if f'data-mode="{mode}"' not in html:
        raise AssertionError(f"{label} did not render mode {mode}: {html[:400]}")
    if "/assets/app.js?v=" not in html or "/assets/app.css?v=" not in html:
        raise AssertionError(f"{label} did not include versioned Mini App assets: {html[:400]}")
    return headers

def run():
    global member_cookie, operator_cookie, member_session, operator_session

    member_seed = int(hashlib.sha256(run_id.encode("utf-8")).hexdigest()[:10], 16)
    member_id = 8_800_000_000 + (member_seed % 99_999_999)
    member_name = f"member_{run_id[-6:].lower()}"
    operator_name = "e2e_operator"
    owner_seed = int(hashlib.sha256((run_id + "-owner").encode("utf-8")).hexdigest()[:10], 16)
    owner_id = 7_700_000_000 + (owner_seed % 99_999_999)
    owner_name = f"owner_{run_id[-6:].lower()}"
    mini_member_id = member_id + 100_000_000
    mini_member_name = f"mini_{member_name}"
    blocked_user_id = mini_member_id + 100_000_000

    ensure_html_shell("miniapp-home-shell", "", "launcher")
    ensure_html_shell("miniapp-app-shell", "/app", "app")
    admin_shell_headers = ensure_html_shell("miniapp-admin-shell", "/admin", "admin")
    if "X-Subscription-Bot-App-Js" not in admin_shell_headers:
        raise AssertionError(f"miniapp admin shell did not expose the app hash header: {admin_shell_headers}")
    steps.append({"name": "miniapp_shell_routes", "status": 200})

    member_cookie, member_session = auth_session(member_id, member_name, "en", "member")
    steps.append({"name": "member_auth", "status": 200, "payload": member_session})

    operator_cookie, operator_session = auth_session(operator_id, operator_name, "en", "operator")
    steps.append({"name": "operator_auth", "status": 200, "payload": operator_session})

    owner_cookie, owner_session = auth_session(owner_id, owner_name, "en", "owner")
    steps.append({"name": "owner_auth", "status": 200, "payload": owner_session})

    renewal_date = next_month_anchor(now)
    renewal_lead_at = renewal_date - timedelta(days=7) + timedelta(hours=8)
    grace_at = renewal_date + timedelta(hours=12)
    suspend_at = renewal_date + timedelta(days=4, hours=12)
    top_up_at = renewal_date + timedelta(days=4, hours=12, minutes=5)
    recover_at = renewal_date + timedelta(days=4, hours=12, minutes=10)

    member_home_payload = inject_update("member-home-miniapp", build_message_update(member_id, member_name, "/start"))
    home_launch_url = find_web_app_url(member_home_payload, button_text="Open app", url_contains="/app")
    steps.append({"name": "home_mini_app_button", "status": 200, "url": home_launch_url})

    owner_bootstrap_status, owner_bootstrap, _ = api_request("/api/v1/bootstrap", cookie=owner_cookie)
    write_trace("owner-bootstrap", {"status": owner_bootstrap_status, "payload": owner_bootstrap})
    expect_status("owner bootstrap", owner_bootstrap_status, (200,), owner_bootstrap)
    if not owner_bootstrap.get("session") or not owner_bootstrap.get("catalog") or not owner_bootstrap.get("payments"):
        raise AssertionError(f"owner bootstrap was missing Mini App data: {owner_bootstrap}")
    steps.append({"name": "owner_bootstrap", "status": owner_bootstrap_status})

    mini_owner_create_status, mini_owner_create_payload, _ = api_request(
        "/api/v1/plans",
        method="POST",
        payload={
            "service_code": "spotify_family",
            "total_price_minor": 1800,
            "seat_limit": 2,
            "renewal_date": renewal_date.date().isoformat(),
            "access_mode": "invite_seat",
            "sharing_policy_ack": True,
        },
        cookie=owner_cookie,
    )
    write_trace("miniapp-owner-create-plan", {"status": mini_owner_create_status, "payload": mini_owner_create_payload})
    expect_status("miniapp owner create plan", mini_owner_create_status, (201,), mini_owner_create_payload)
    mini_owner_plan = mini_owner_create_payload.get("plan") or {}
    mini_owner_invite = mini_owner_create_payload.get("invite") or {}
    mini_owner_launch = mini_owner_create_payload.get("launch") or {}
    mini_owner_plan_id = id_of(mini_owner_plan)
    mini_owner_invite_code = str(mini_owner_invite.get("InviteCode") or mini_owner_invite.get("inviteCode") or "").strip()
    if not mini_owner_plan_id or not mini_owner_invite_code:
        raise AssertionError(f"miniapp owner create plan did not return a plan and invite: {mini_owner_create_payload}")
    expected_join_startapp = f"join-{mini_owner_invite_code}"
    if str(mini_owner_launch.get("startApp") or "").strip() != expected_join_startapp:
        raise AssertionError(f"miniapp owner create plan did not return the expected startapp token: {mini_owner_create_payload}")
    if f"startapp={urllib.parse.quote(expected_join_startapp)}" not in str(mini_owner_launch.get("appUrl") or ""):
        raise AssertionError(f"miniapp owner create plan did not return an app launch url with startapp={expected_join_startapp}: {mini_owner_create_payload}")
    if str(mini_owner_launch.get("telegramDeepLink") or "").strip() != f"https://t.me/{bot_username}?startapp={expected_join_startapp}":
        raise AssertionError(f"miniapp owner create plan did not return the Telegram deep link for the join flow: {mini_owner_create_payload}")
    steps.append({"name": "owner_create_plan_web", "status": mini_owner_create_status, "plan_id": mini_owner_plan_id})

    owner_plans_status, owner_plans_payload, _ = api_request("/api/v1/plans", cookie=owner_cookie)
    write_trace("miniapp-owner-plans", {"status": owner_plans_status, "payload": owner_plans_payload})
    expect_status("miniapp owner plans", owner_plans_status, (200,), owner_plans_payload)
    owner_plans = owner_plans_payload.get("plans") or []
    if not any(id_of(item) == mini_owner_plan_id for item in owner_plans):
        raise AssertionError(f"miniapp owner plans did not include the new plan {mini_owner_plan_id}: {owner_plans_payload}")
    steps.append({"name": "owner_view_plans_web", "status": owner_plans_status, "plan_id": mini_owner_plan_id})

    owner_invite_status, owner_invite_payload, _ = api_request(
        f"/api/v1/plans/{urllib.parse.quote(mini_owner_plan_id)}/invite",
        method="POST",
        cookie=owner_cookie,
    )
    write_trace("miniapp-owner-regenerate-invite", {"status": owner_invite_status, "payload": owner_invite_payload})
    expect_status("miniapp owner regenerate invite", owner_invite_status, (201,), owner_invite_payload)
    mini_owner_regenerated_invite = owner_invite_payload.get("invite") or {}
    mini_owner_regenerated_code = str(mini_owner_regenerated_invite.get("InviteCode") or mini_owner_regenerated_invite.get("inviteCode") or "").strip()
    if not mini_owner_regenerated_code:
        raise AssertionError(f"miniapp owner regenerate invite did not return an invite code: {owner_invite_payload}")
    steps.append({"name": "owner_regenerate_invite_web", "status": owner_invite_status, "invite_code": mini_owner_regenerated_code})

    owner_members_status, owner_members_payload, _ = api_request(
        f"/api/v1/plans/{urllib.parse.quote(mini_owner_plan_id)}/members",
        cookie=owner_cookie,
    )
    write_trace("miniapp-owner-members", {"status": owner_members_status, "payload": owner_members_payload})
    expect_status("miniapp owner members", owner_members_status, (200,), owner_members_payload)
    if "members" not in owner_members_payload:
        raise AssertionError(f"miniapp owner members payload was missing members: {owner_members_payload}")
    steps.append({"name": "owner_members_web", "status": owner_members_status, "plan_id": mini_owner_plan_id})

    owner_ledger_status, owner_ledger_payload, _ = api_request(
        f"/api/v1/ledger?plan_id={urllib.parse.quote(mini_owner_plan_id)}",
        cookie=owner_cookie,
    )
    write_trace("miniapp-owner-ledger", {"status": owner_ledger_status, "payload": owner_ledger_payload})
    expect_status("miniapp owner ledger", owner_ledger_status, (200,), owner_ledger_payload)
    if not ledger_events(owner_ledger_payload):
        raise AssertionError(f"miniapp owner ledger was empty for the newly created plan: {owner_ledger_payload}")
    steps.append({"name": "owner_ledger_web", "status": owner_ledger_status, "plan_id": mini_owner_plan_id})

    mini_member_cookie, mini_member_session = auth_session(mini_member_id, mini_member_name, "en", "mini-member")
    steps.append({"name": "mini_member_auth", "status": 200, "payload": mini_member_session})

    startapp_bootstrap_status, startapp_bootstrap_payload, _ = api_request(
        f"/api/v1/bootstrap?startapp={urllib.parse.quote(expected_join_startapp)}",
        cookie=mini_member_cookie,
    )
    write_trace("miniapp-startapp-join-bootstrap", {"status": startapp_bootstrap_status, "payload": startapp_bootstrap_payload})
    expect_status("miniapp startapp join bootstrap", startapp_bootstrap_status, (200,), startapp_bootstrap_payload)
    startapp_launch = startapp_bootstrap_payload.get("launch") or {}
    if (
        str(startapp_launch.get("section") or "").strip() != "join"
        or str(startapp_launch.get("inviteCode") or "").strip() != mini_owner_invite_code
        or str(startapp_launch.get("startApp") or "").strip() != expected_join_startapp
    ):
        raise AssertionError(f"miniapp bootstrap did not normalize the join startapp launch context: {startapp_bootstrap_payload}")
    steps.append({"name": "miniapp_startapp_join_launch", "status": startapp_bootstrap_status, "invite_code": mini_owner_invite_code})

    mini_member_join_status, mini_member_join_payload, _ = api_request(
        "/api/v1/plans/join",
        method="POST",
        payload={"invite_code": mini_owner_regenerated_code},
        cookie=mini_member_cookie,
    )
    write_trace("miniapp-member-join", {"status": mini_member_join_status, "payload": mini_member_join_payload})
    expect_status("miniapp member join", mini_member_join_status, (200,), mini_member_join_payload)
    mini_member_invoice = mini_member_join_payload.get("invoice") or {}
    mini_member_invoice_id = id_of(mini_member_invoice)
    if not mini_member_invoice_id:
        raise AssertionError(f"miniapp member join did not return an invoice: {mini_member_join_payload}")
    steps.append({"name": "member_join_web", "status": mini_member_join_status, "invoice_id": mini_member_invoice_id})

    mini_member_bootstrap_status, mini_member_bootstrap, _ = api_request("/api/v1/bootstrap", cookie=mini_member_cookie)
    write_trace("miniapp-member-bootstrap", {"status": mini_member_bootstrap_status, "payload": mini_member_bootstrap})
    expect_status("miniapp member bootstrap", mini_member_bootstrap_status, (200,), mini_member_bootstrap)
    if id_of(mini_member_bootstrap.get("latestInvoice") or {}) != mini_member_invoice_id:
        raise AssertionError(f"miniapp member bootstrap did not surface the latest invoice {mini_member_invoice_id}: {mini_member_bootstrap}")
    steps.append({"name": "member_bootstrap_web", "status": mini_member_bootstrap_status, "invoice_id": mini_member_invoice_id})

    mini_member_invoice_status, mini_member_invoice_payload, _ = api_request("/api/v1/invoices/latest", cookie=mini_member_cookie)
    write_trace("miniapp-member-latest-invoice", {"status": mini_member_invoice_status, "payload": mini_member_invoice_payload})
    expect_status("miniapp member latest invoice", mini_member_invoice_status, (200,), mini_member_invoice_payload)
    if id_of(mini_member_invoice_payload.get("invoice") or {}) != mini_member_invoice_id:
        raise AssertionError(f"miniapp member latest invoice did not match {mini_member_invoice_id}: {mini_member_invoice_payload}")
    steps.append({"name": "member_latest_invoice_web", "status": mini_member_invoice_status, "invoice_id": mini_member_invoice_id})

    mini_member_quote_status, mini_member_quote_payload, _ = api_request(
        "/api/v1/invoices/latest/quote",
        method="POST",
        payload={
            "pay_asset": mini_member_session.get("defaultPayAsset") or "USDC",
            "network": mini_member_session.get("defaultPayNetwork") or "solana",
        },
        cookie=mini_member_cookie,
    )
    write_trace("miniapp-member-quote", {"status": mini_member_quote_status, "payload": mini_member_quote_payload})
    expect_status("miniapp member quote", mini_member_quote_status, (200,), mini_member_quote_payload)
    quoted_invoice = mini_member_quote_payload.get("invoice") or {}
    if id_of(quoted_invoice) != mini_member_invoice_id:
        raise AssertionError(f"miniapp member quote did not reference the latest invoice {mini_member_invoice_id}: {mini_member_quote_payload}")
    quoted_provider_invoice_id = str(quoted_invoice.get("ProviderInvoiceID") or quoted_invoice.get("providerInvoiceID") or "").strip()
    if not quoted_provider_invoice_id:
        raise AssertionError(f"miniapp member quote did not return a provider payment reference: {mini_member_quote_payload}")
    steps.append({"name": "member_quote_web", "status": mini_member_quote_status, "invoice_id": mini_member_invoice_id})

    mini_member_ledger_status, mini_member_ledger_payload, _ = api_request(
        f"/api/v1/ledger?plan_id={urllib.parse.quote(mini_owner_plan_id)}",
        cookie=mini_member_cookie,
    )
    write_trace("miniapp-member-ledger", {"status": mini_member_ledger_status, "payload": mini_member_ledger_payload})
    expect_status("miniapp member ledger", mini_member_ledger_status, (200,), mini_member_ledger_payload)
    if not ledger_events(mini_member_ledger_payload):
        raise AssertionError(f"miniapp member ledger did not return plan activity: {mini_member_ledger_payload}")
    steps.append({"name": "member_ledger_web", "status": mini_member_ledger_status, "plan_id": mini_owner_plan_id})

    mini_member_support_message = f"mini app support check {run_id}"
    mini_member_support_status, mini_member_support_payload, _ = api_request(
        "/api/v1/support",
        method="POST",
        payload={"plan_id": mini_owner_plan_id, "message": mini_member_support_message},
        cookie=mini_member_cookie,
    )
    write_trace("miniapp-member-support", {"status": mini_member_support_status, "payload": mini_member_support_payload})
    expect_status("miniapp member support", mini_member_support_status, (201,), mini_member_support_payload)
    mini_member_ticket = mini_member_support_payload.get("ticket") or {}
    mini_member_ticket_id = id_of(mini_member_ticket)
    if not mini_member_ticket_id:
        raise AssertionError(f"miniapp member support did not return a ticket id: {mini_member_support_payload}")
    steps.append({"name": "member_support_web", "status": mini_member_support_status, "ticket_id": mini_member_ticket_id})

    operator_support_api_status, operator_support_api_payload, _ = api_request("/api/v1/admin/support", cookie=operator_cookie)
    write_trace("miniapp-operator-support-open", {"status": operator_support_api_status, "payload": operator_support_api_payload})
    expect_status("miniapp operator support", operator_support_api_status, (200,), operator_support_api_payload)
    operator_tickets = operator_support_api_payload.get("tickets") or []
    matching_ticket = next((item for item in operator_tickets if id_of(item) == mini_member_ticket_id), None)
    if matching_ticket is None:
        raise AssertionError(f"miniapp operator support queue did not include ticket {mini_member_ticket_id}: {operator_support_api_payload}")
    steps.append({"name": "operator_support_queue_web", "status": operator_support_api_status, "ticket_id": mini_member_ticket_id})

    resolve_status, resolve_payload, _ = api_request(
        f"/api/v1/admin/support/{urllib.parse.quote(mini_member_ticket_id)}/resolve",
        method="POST",
        payload={"note": "resolved by live mini app e2e"},
        cookie=operator_cookie,
    )
    write_trace("miniapp-operator-support-resolve", {"status": resolve_status, "payload": resolve_payload})
    expect_status("miniapp operator resolve support", resolve_status, (200,), resolve_payload)
    resolved_ticket = resolve_payload.get("ticket") or {}
    resolved_status = str(resolved_ticket.get("Status") or resolved_ticket.get("status") or "").strip().lower()
    if resolved_status != "resolved":
        raise AssertionError(f"miniapp operator resolve support did not mark the ticket resolved: {resolve_payload}")
    steps.append({"name": "operator_resolve_support_web", "status": resolve_status, "ticket_id": mini_member_ticket_id})

    operator_support_after_status, operator_support_after_payload, _ = api_request("/api/v1/admin/support", cookie=operator_cookie)
    write_trace("miniapp-operator-support-after-resolve", {"status": operator_support_after_status, "payload": operator_support_after_payload})
    expect_status("miniapp operator support after resolve", operator_support_after_status, (200,), operator_support_after_payload)
    if any(id_of(item) == mini_member_ticket_id for item in operator_support_after_payload.get("tickets") or []):
        raise AssertionError(f"miniapp operator support queue still contained resolved ticket {mini_member_ticket_id}: {operator_support_after_payload}")
    steps.append({"name": "operator_support_queue_after_resolve_web", "status": operator_support_after_status, "ticket_id": mini_member_ticket_id})

    operator_issues_api_status, operator_issues_api_payload, _ = api_request("/api/v1/admin/issues", cookie=operator_cookie)
    write_trace("miniapp-operator-issues", {"status": operator_issues_api_status, "payload": operator_issues_api_payload})
    expect_status("miniapp operator issues", operator_issues_api_status, (200,), operator_issues_api_payload)
    if "issues" not in operator_issues_api_payload:
        raise AssertionError(f"miniapp operator issues payload was missing issues: {operator_issues_api_payload}")
    steps.append({"name": "operator_issues_web", "status": operator_issues_api_status})

    operator_recent_status, operator_recent_payload, _ = api_request("/api/v1/admin/recent-plans", cookie=operator_cookie)
    write_trace("miniapp-operator-recent-plans", {"status": operator_recent_status, "payload": operator_recent_payload})
    expect_status("miniapp operator recent plans", operator_recent_status, (200,), operator_recent_payload)
    if not any(id_of(item) == mini_owner_plan_id for item in operator_recent_payload.get("plans") or []):
        raise AssertionError(f"miniapp operator recent plans did not include the web-created plan {mini_owner_plan_id}: {operator_recent_payload}")
    steps.append({"name": "operator_recent_plans_web", "status": operator_recent_status, "plan_id": mini_owner_plan_id})

    operator_reimbursements_status, operator_reimbursements_payload, _ = api_request("/api/v1/admin/reimbursements", cookie=operator_cookie)
    write_trace("miniapp-operator-reimbursements", {"status": operator_reimbursements_status, "payload": operator_reimbursements_payload})
    expect_status("miniapp operator reimbursements", operator_reimbursements_status, (200,), operator_reimbursements_payload)
    if "reimbursements" not in operator_reimbursements_payload:
        raise AssertionError(f"miniapp operator reimbursements payload was missing reimbursements: {operator_reimbursements_payload}")
    steps.append({"name": "operator_reimbursements_web", "status": operator_reimbursements_status})

    operator_alerts_status, operator_alerts_payload, _ = api_request("/api/v1/admin/payment-alerts", cookie=operator_cookie)
    write_trace("miniapp-operator-payment-alerts", {"status": operator_alerts_status, "payload": operator_alerts_payload})
    expect_status("miniapp operator payment alerts", operator_alerts_status, (200,), operator_alerts_payload)
    if "alerts" not in operator_alerts_payload:
        raise AssertionError(f"miniapp operator payment alerts payload was missing alerts: {operator_alerts_payload}")
    steps.append({"name": "operator_payment_alerts_web", "status": operator_alerts_status})

    operator_denylist_status, operator_denylist_payload, _ = api_request("/api/v1/admin/denylist", cookie=operator_cookie)
    write_trace("miniapp-operator-denylist-before-block", {"status": operator_denylist_status, "payload": operator_denylist_payload})
    expect_status("miniapp operator denylist", operator_denylist_status, (200,), operator_denylist_payload)
    if "entries" not in operator_denylist_payload:
        raise AssertionError(f"miniapp operator denylist payload was missing entries: {operator_denylist_payload}")
    steps.append({"name": "operator_denylist_before_block_web", "status": operator_denylist_status})

    operator_block_status, operator_block_payload, _ = api_request(
        "/api/v1/admin/denylist/block-user",
        method="POST",
        payload={"telegram_id": blocked_user_id, "reason": f"mini app e2e block {run_id}"},
        cookie=operator_cookie,
    )
    write_trace("miniapp-operator-block-user", {"status": operator_block_status, "payload": operator_block_payload})
    expect_status("miniapp operator block user", operator_block_status, (201,), operator_block_payload)
    blocked_entry = operator_block_payload.get("entry") or {}
    if denylist_value_of(blocked_entry) != str(blocked_user_id):
        raise AssertionError(f"miniapp operator block user did not return the expected denylist value: {operator_block_payload}")
    steps.append({"name": "operator_block_user_web", "status": operator_block_status, "telegram_id": blocked_user_id})

    operator_denylist_after_status, operator_denylist_after_payload, _ = api_request("/api/v1/admin/denylist", cookie=operator_cookie)
    write_trace("miniapp-operator-denylist-after-block", {"status": operator_denylist_after_status, "payload": operator_denylist_after_payload})
    expect_status("miniapp operator denylist after block", operator_denylist_after_status, (200,), operator_denylist_after_payload)
    if not any(denylist_value_of(item) == str(blocked_user_id) for item in operator_denylist_after_payload.get("entries") or []):
        raise AssertionError(f"miniapp operator denylist did not include blocked user {blocked_user_id}: {operator_denylist_after_payload}")
    steps.append({"name": "operator_denylist_after_block_web", "status": operator_denylist_after_status, "telegram_id": blocked_user_id})

    baseline_status, baseline_overview, _ = api_request("/api/v1/admin/overview", cookie=operator_cookie)
    write_trace("operator-admin-overview-before", {"status": baseline_status, "payload": baseline_overview})
    expect_status("operator admin overview", baseline_status, (200,), baseline_overview)
    steps.append({"name": "operator_overview_before", "status": baseline_status, "payload": baseline_overview})

    member_admin_payload = inject_update("member-admin-rejected", build_message_update(member_id, member_name, "/admin"))
    require_text_contains("member admin rejection", member_admin_payload, "unauthorized")
    steps.append({"name": "member_admin_rejected", "status": 200})

    join_prompt = inject_update("member-join-start", build_message_update(member_id, member_name, "/join"))
    require_text_contains("member join prompt", join_prompt, "Paste the invite code")
    steps.append({"name": "member_join_prompt", "status": 200})

    join_payload = inject_update("member-join-finish", build_message_update(member_id, member_name, invite_code))
    join_text = require_text_contains("member join result", join_payload, "Joined the plan.")
    invoice_id = extract_match("joined invoice id", join_text, r"First invoice:\s*(invoice-[a-z0-9]+)")
    if "Base:" not in join_text or "Fee:" not in join_text:
        raise AssertionError(f"joined plan response did not show separate base/fee amounts: {join_text}")
    steps.append({"name": "join_plan", "status": 200, "invoice_id": invoice_id})

    pay_assets_payload = inject_update(
        "member-pay-assets",
        build_callback_update(member_id, member_name, find_button(join_payload, button_text="Pay", callback_prefix=f"invoice:{invoice_id}:pay")),
    )
    require_text_contains("member pay assets", pay_assets_payload, "Choose what the member will pay with")
    steps.append({"name": "member_pay_assets", "status": 200, "invoice_id": invoice_id})

    pay_networks_payload = inject_update(
        "member-pay-networks",
        build_callback_update(member_id, member_name, find_button(pay_assets_payload, button_text="USDC", callback_prefix=f"pay:asset:{invoice_id}:")),
    )
    require_text_contains("member pay networks", pay_networks_payload, "Choose the network for USDC")
    steps.append({"name": "member_pay_networks", "status": 200, "invoice_id": invoice_id})

    initial_quote_payload = inject_update(
        "member-quote-initial",
        build_callback_update(member_id, member_name, find_button(pay_networks_payload, button_text="solana", callback_prefix=f"pay:quote:{invoice_id}:USDC:")),
    )
    initial_quote_text = require_text_contains("member initial quote", initial_quote_payload, f"Invoice {invoice_id}")
    require_text_contains("member initial quote", initial_quote_payload, "Platform fee:")
    require_text_contains("member initial quote", initial_quote_payload, "Quoted amount:")
    initial_invoice = load_latest_invoice(member_cookie, "member-invoice-after-quote", invoice_id)
    initial_quote_amount = str(initial_invoice.get("QuotedPayAmount") or extract_match("initial quoted amount", initial_quote_text, r"Quoted amount:\s*([0-9]+)")).strip()
    initial_provider_invoice_id = str(initial_invoice.get("ProviderInvoiceID") or "").strip()
    if not initial_provider_invoice_id:
        raise AssertionError(f"initial invoice is missing provider invoice id: {initial_invoice}")
    initial_pay_currency = nowpayments_currency_code(initial_invoice.get("PayAsset") or "USDC", initial_invoice.get("Network") or "solana")
    steps.append({"name": "quote_initial_invoice", "status": 200, "invoice_id": invoice_id, "quoted_pay_amount": initial_quote_amount, "provider_invoice_id": initial_provider_invoice_id})

    simulate_button = find_button_or_none(initial_quote_payload, button_text="Simulate payment", callback_prefix=f"pay:simulate:{invoice_id}")
    if simulate_button:
        simulate_initial_payload = inject_update(
            "member-simulate-initial",
            build_callback_update(member_id, member_name, simulate_button),
        )
        require_text_contains("simulate initial payment", simulate_initial_payload, "Sandbox payment submitted and processed.")
        steps.append({"name": "simulate_initial_payment", "status": 200, "invoice_id": invoice_id, "mode": "button"})
    else:
        post_nowpayments_webhook(
            "member-simulate-initial-webhook",
            initial_provider_invoice_id,
            initial_quote_amount,
            initial_pay_currency,
            f"e2e-init-{run_id}",
            "finished",
        )
        steps.append({"name": "simulate_initial_payment", "status": 200, "invoice_id": invoice_id, "mode": "webhook"})

    active_plans_payload = inject_update("member-plans-after-activation", build_message_update(member_id, member_name, "/my_plans"))
    require_text_contains("member plans after activation", active_plans_payload, "seat=active")
    steps.append({"name": "member_plan_after_activation", "status": 200})

    renewal_status, renewal_payload, _ = api_request(
        "/api/v1/test/process-cycle",
        method="POST",
        payload={"at": renewal_lead_at.isoformat()},
        cookie=operator_cookie,
    )
    write_trace("generate-renewal-invoice", {"status": renewal_status, "payload": renewal_payload})
    expect_status("generate renewal invoice", renewal_status, (200,), renewal_payload)
    steps.append({"name": "generate_renewal_invoice", "status": renewal_status, "notifications": renewal_payload.get("notifications")})

    renewal_invoice_payload = inject_update("member-invoice-renewal", build_message_update(member_id, member_name, "/invoice"))
    renewal_invoice_text = require_text_contains("member renewal invoice", renewal_invoice_payload, "Invoice ")
    renewal_invoice_id = extract_match("renewal invoice id", renewal_invoice_text, r"Invoice\s+(invoice-[a-z0-9]+)")
    if renewal_invoice_id == invoice_id:
        raise AssertionError(f"renewal invoice was not replaced: {renewal_invoice_text}")
    renewal_launch_url = find_web_app_url(renewal_invoice_payload, button_text="Pay in app", url_contains="/app")
    if f"plan_id={urllib.parse.quote(plan_id)}" not in renewal_launch_url:
        raise AssertionError(f"renewal invoice Mini App button did not include plan_id={plan_id}: {renewal_launch_url}")
    if f"startapp={urllib.parse.quote(f'invoice-{renewal_invoice_id}')}" not in renewal_launch_url:
        raise AssertionError(f"renewal invoice Mini App button did not include the invoice startapp token: {renewal_launch_url}")
    steps.append({"name": "latest_renewal_invoice", "status": 200, "invoice_id": renewal_invoice_id})

    renewal_pay_assets_payload = inject_update(
        "member-renewal-pay-assets",
        build_callback_update(member_id, member_name, find_button(renewal_invoice_payload, button_text="Pay", callback_prefix=f"invoice:{renewal_invoice_id}:pay")),
    )
    renewal_pay_networks_payload = inject_update(
        "member-renewal-pay-networks",
        build_callback_update(member_id, member_name, find_button(renewal_pay_assets_payload, button_text="USDC", callback_prefix=f"pay:asset:{renewal_invoice_id}:")),
    )
    renewal_quote_payload = inject_update(
        "member-quote-renewal",
        build_callback_update(member_id, member_name, find_button(renewal_pay_networks_payload, button_text="solana", callback_prefix=f"pay:quote:{renewal_invoice_id}:USDC:")),
    )
    renewal_quote_text = require_text_contains("member renewal quote", renewal_quote_payload, f"Invoice {renewal_invoice_id}")
    renewal_invoice = load_latest_invoice(member_cookie, "member-renewal-invoice-after-quote", renewal_invoice_id)
    renewal_quote_amount = str(renewal_invoice.get("QuotedPayAmount") or extract_match("renewal quoted amount", renewal_quote_text, r"Quoted amount:\s*([0-9]+)")).strip()
    renewal_provider_invoice_id = str(renewal_invoice.get("ProviderInvoiceID") or "").strip()
    if not renewal_provider_invoice_id:
        raise AssertionError(f"renewal invoice is missing provider invoice id: {renewal_invoice}")
    renewal_pay_currency = nowpayments_currency_code(renewal_invoice.get("PayAsset") or "USDC", renewal_invoice.get("Network") or "solana")
    steps.append({"name": "quote_renewal_invoice", "status": 200, "invoice_id": renewal_invoice_id, "quoted_pay_amount": renewal_quote_amount, "provider_invoice_id": renewal_provider_invoice_id})

    underpaid_amount = amount_minus(renewal_quote_amount, 1_000_000)
    post_nowpayments_webhook(
        "simulate-underpayment",
        renewal_provider_invoice_id,
        underpaid_amount,
        renewal_pay_currency,
        f"e2e-underpay-{run_id}",
        "partially_paid",
    )
    steps.append({"name": "simulate_underpayment", "status": 200, "amount_atomic": underpaid_amount})

    underpaid_cycle_status, underpaid_cycle_payload, _ = api_request(
        "/api/v1/test/process-cycle",
        method="POST",
        payload={"at": (renewal_lead_at + timedelta(minutes=5)).isoformat()},
        cookie=operator_cookie,
    )
    write_trace("process-underpayment", {"status": underpaid_cycle_status, "payload": underpaid_cycle_payload})
    expect_status("process underpayment", underpaid_cycle_status, (200,), underpaid_cycle_payload)
    steps.append({"name": "process_underpayment", "status": underpaid_cycle_status, "notifications": underpaid_cycle_payload.get("notifications")})

    underpaid_invoice_payload = inject_update("member-invoice-underpaid", build_message_update(member_id, member_name, "/invoice"))
    underpaid_invoice_text = require_text_contains("verify underpaid invoice", underpaid_invoice_payload, f"Invoice {renewal_invoice_id}")
    if "Status: underpaid" not in underpaid_invoice_text:
        raise AssertionError(f"renewal invoice did not become underpaid: {underpaid_invoice_text}")
    steps.append({"name": "verify_underpaid_invoice", "status": 200, "invoice_id": renewal_invoice_id})

    grace_cycle_status, grace_cycle_payload, _ = api_request(
        "/api/v1/test/process-cycle",
        method="POST",
        payload={"at": grace_at.isoformat()},
        cookie=operator_cookie,
    )
    write_trace("start-grace", {"status": grace_cycle_status, "payload": grace_cycle_payload})
    expect_status("start grace", grace_cycle_status, (200,), grace_cycle_payload)
    steps.append({"name": "start_grace", "status": grace_cycle_status, "notifications": grace_cycle_payload.get("notifications")})

    grace_plans_payload = inject_update("member-plans-grace", build_message_update(member_id, member_name, "/my_plans"))
    require_text_contains("verify grace membership", grace_plans_payload, "seat=grace")
    steps.append({"name": "verify_grace_membership", "status": 200})

    operator_admin_payload = inject_update("operator-admin-home", build_message_update(operator_id, operator_name, "/admin"))
    require_text_contains("operator admin home", operator_admin_payload, "Operator dashboard")
    admin_launch_url = find_web_app_url(operator_admin_payload, button_text="Open operator app", url_contains="/admin")
    if "startapp=admin-overview" not in admin_launch_url:
        raise AssertionError(f"operator admin Mini App button did not include the admin startapp token: {admin_launch_url}")
    steps.append({"name": "admin_mini_app_button", "status": 200, "url": admin_launch_url})
    operator_issues_payload = inject_update(
        "operator-admin-issues-before-suspend",
        build_callback_update(operator_id, operator_name, find_button(operator_admin_payload, button_text="Renewal issues", callback_exact="admin:issues")),
    )
    issues_text = require_text_contains("operator renewal issues", operator_issues_payload, "Renewal issues")
    if "grace" not in issues_text and "underpaid" not in issues_text:
        raise AssertionError(f"operator renewal issues did not show grace or underpaid entries: {issues_text}")
    steps.append({"name": "operator_renewal_issues_before_suspend", "status": 200})

    suspend_cycle_status, suspend_cycle_payload, _ = api_request(
        "/api/v1/test/process-cycle",
        method="POST",
        payload={"at": suspend_at.isoformat()},
        cookie=operator_cookie,
    )
    write_trace("suspend-member", {"status": suspend_cycle_status, "payload": suspend_cycle_payload})
    expect_status("suspend member", suspend_cycle_status, (200,), suspend_cycle_payload)
    steps.append({"name": "suspend_member", "status": suspend_cycle_status, "notifications": suspend_cycle_payload.get("notifications")})

    suspended_plans_payload = inject_update("member-plans-suspended", build_message_update(member_id, member_name, "/my_plans"))
    require_text_contains("verify suspended membership", suspended_plans_payload, "seat=suspended")
    steps.append({"name": "verify_suspended_membership", "status": 200})

    operator_issues_payload = inject_update(
        "operator-admin-issues-suspended",
        build_callback_update(operator_id, operator_name, "admin:issues"),
    )
    issues_text = require_text_contains("operator suspended issues", operator_issues_payload, "Renewal issues")
    if "suspended" not in issues_text:
        raise AssertionError(f"operator renewal issues did not show a suspended entry: {issues_text}")
    steps.append({"name": "operator_renewal_issues_suspended", "status": 200})

    post_nowpayments_webhook(
        "simulate-top-up",
        renewal_provider_invoice_id,
        "1000000",
        renewal_pay_currency,
        f"e2e-topup-{run_id}",
        "finished",
    )
    steps.append({"name": "simulate_top_up", "status": 200, "at": top_up_at.isoformat()})

    recover_cycle_status, recover_cycle_payload, _ = api_request(
        "/api/v1/test/process-cycle",
        method="POST",
        payload={"at": recover_at.isoformat()},
        cookie=operator_cookie,
    )
    write_trace("recover-membership", {"status": recover_cycle_status, "payload": recover_cycle_payload})
    expect_status("recover membership", recover_cycle_status, (200,), recover_cycle_payload)
    steps.append({"name": "recover_membership", "status": recover_cycle_status, "notifications": recover_cycle_payload.get("notifications")})

    recovered_plans_payload = inject_update("member-plans-recovered", build_message_update(member_id, member_name, "/my_plans"))
    require_text_contains("verify recovered membership", recovered_plans_payload, "seat=active")
    steps.append({"name": "verify_recovered_membership", "status": 200})

    operator_issues_cleared_payload = inject_update(
        "operator-admin-issues-cleared",
        build_callback_update(operator_id, operator_name, "admin:issues"),
    )
    issues_cleared_text = "\n".join(bot_texts(operator_issues_cleared_payload))
    if not issues_cleared_text:
        raise AssertionError(f"operator issues cleared did not return any bot text: {operator_issues_cleared_payload}")
    if member_name in issues_cleared_text or renewal_invoice_id in issues_cleared_text:
        raise AssertionError(f"operator issues still contained the recovered member or invoice: {issues_cleared_text}")
    steps.append({"name": "operator_renewal_issues_cleared", "status": 200})

    support_message = f"subscription-bot e2e support check {run_id}"
    support_prompt_payload = inject_update(
        "member-support-start",
        build_callback_update(member_id, member_name, f"plan:{plan_id}:support"),
    )
    require_text_contains("support prompt", support_prompt_payload, f"Send the support message for plan {plan_id}.")
    support_payload = inject_update("member-support-finish", build_message_update(member_id, member_name, support_message))
    require_text_contains("open support ticket", support_payload, "Support request opened:")
    steps.append({"name": "open_support_ticket", "status": 200})

    operator_support_payload = inject_update(
        "operator-admin-support",
        build_callback_update(operator_id, operator_name, "admin:support"),
    )
    support_queue_text = require_text_contains("operator support queue", operator_support_payload, "Open support queue")
    if support_message not in support_queue_text:
        raise AssertionError(f"operator support queue did not show the new ticket: {support_queue_text}")
    steps.append({"name": "operator_support_queue", "status": 200})

    final_admin_status, final_admin_payload, _ = api_request("/api/v1/admin/overview", cookie=operator_cookie)
    write_trace("operator-overview-after-support", {"status": final_admin_status, "payload": final_admin_payload})
    expect_status("operator overview after support", final_admin_status, (200,), final_admin_payload)
    if admin_support_total(final_admin_payload) < admin_support_total(baseline_overview) + 1:
        raise AssertionError(f"support ticket count did not increase in admin overview: before={baseline_overview} after={final_admin_payload}")
    steps.append({"name": "operator_overview_after_support", "status": final_admin_status, "payload": final_admin_payload})

    payload = {
        "status": "ok",
        "member_cookie": bool(member_cookie),
        "operator_cookie": bool(operator_cookie),
        "member_session": member_session,
        "operator_session": operator_session,
        "owner_session": owner_session,
        "plan_id": plan_id,
        "invite_code": invite_code,
        "mini_owner_plan_id": mini_owner_plan_id,
        "mini_owner_invite_code": mini_owner_regenerated_code,
        "mini_member_invoice_id": mini_member_invoice_id,
        "blocked_user_id": blocked_user_id,
        "renewal_date": renewal_date.date().isoformat(),
        "timeline": {
            "renewal_lead_at": renewal_lead_at.isoformat(),
            "grace_at": grace_at.isoformat(),
            "suspend_at": suspend_at.isoformat(),
            "top_up_at": top_up_at.isoformat(),
            "recover_at": recover_at.isoformat(),
        },
        "steps": steps,
        "artifacts": trace_files,
    }
    output_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

try:
    run()
except Exception as exc:
    persist_failure(str(exc))
    traceback.print_exc()
    raise SystemExit(1)
PY
synthetic_rc=$?
set -e
if (( synthetic_rc != 0 )); then
  synthetic_summary="$(
    python3 - "${SYNTHETIC_DETAILS}" "${SYNTHETIC_ERROR_FILE}" <<'PY'
import json
import pathlib
import sys

details_path = pathlib.Path(sys.argv[1])
stderr_path = pathlib.Path(sys.argv[2])

reason = ""
if details_path.exists():
    try:
        payload = json.loads(details_path.read_text(encoding="utf-8"))
        reason = (payload.get("reason") or "").strip()
    except Exception:
        reason = ""

if not reason and stderr_path.exists():
    lines = [line.strip() for line in stderr_path.read_text(encoding="utf-8", errors="replace").splitlines() if line.strip()]
    if lines:
        reason = lines[-1]

if not reason:
    reason = "Synthetic flow failed before it produced a readable reason."

print(f"Synthetic flow failed: {reason}")
PY
  )"
  synthetic_details_ref="${SYNTHETIC_DETAILS}"
  if [[ ! -s "${synthetic_details_ref}" ]]; then
    synthetic_details_ref="${SYNTHETIC_ERROR_FILE}"
  fi
  finish_step "failed" "${synthetic_summary}" "${synthetic_details_ref}"
  exit 1
fi

complete_step "Authenticated synthetic owner, member, and operator sessions; verified the Mini App shell routes and Telegram launch buttons; exercised owner/member/operator Mini App APIs including plan creation, invite regeneration, join, quote, ledger, support resolve, and block-user actions; then completed the live renewal, payment, suspension, recovery, and admin checks." "${SYNTHETIC_DETAILS}"

begin_step "owner_renew_checkpoint" "owner"
if ! telegram_owner_open_chat; then
  echo "browser-use owner renew checkpoint could not re-open the live bot chat" >&2
  exit 1
fi

renew_before_text="$(telegram_latest_visible_message_text "${SESSION_NAME}")"
renew_before_mid="$(browser_use_output_value 'mid')"
renew_before_preview="$(telegram_chat_preview_text "${SESSION_NAME}")"

browser_use_run "${SESSION_NAME}" type "${TELEGRAM_MESSAGE_SELECTOR}" '/renew' >/dev/null
browser_use_run "${SESSION_NAME}" press Enter >/dev/null
renew_after_text=""
renew_after_mid=""
renew_after_preview=""
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  browser_use_run "${SESSION_NAME}" wait 1000 >/dev/null
  renew_after_preview="$(telegram_chat_preview_text "${SESSION_NAME}")"
  if [[ -n "${renew_after_preview}" && "${renew_after_preview}" == Billing\ checks\ finished.* ]]; then
    if [[ "${renew_after_preview}" != "${renew_before_preview}" || "${attempt}" -ge 3 ]]; then
      if [[ "${renew_after_preview}" == "${renew_before_preview}" && "${attempt}" -ge 3 ]]; then
        record_warning "owner_renew_checkpoint: Telegram reused the same preview text for the /renew confirmation, so the checkpoint accepted the repeated billing-finished text after waiting for a fresh reply."
      fi
      renew_after_text="${renew_after_preview}"
      break
    fi
  fi
  renew_after_text="$(telegram_latest_visible_message_text "${SESSION_NAME}")"
  renew_after_mid="$(browser_use_output_value 'mid')"
  if [[ -n "${renew_after_text}" && "${renew_after_text}" == Billing\ checks\ finished.* ]]; then
    if [[ -n "${renew_after_mid}" && -n "${renew_before_mid}" && "${renew_after_mid}" != "${renew_before_mid}" ]]; then
      break
    fi
    if [[ "${renew_after_text}" != "${renew_before_text}" ]]; then
      break
    fi
    if [[ "${attempt}" -ge 3 ]]; then
      record_warning "owner_renew_checkpoint: Telegram reused the same visible billing-finished text for /renew, so the checkpoint accepted it after waiting for a fresh confirmation."
      break
    fi
  fi
done
if [[ -z "${renew_after_text}" || "${renew_after_text}" != Billing\ checks\ finished.* ]]; then
  echo "browser-use owner renew checkpoint did not observe the billing checks confirmation message" >&2
  exit 1
fi

browser_use_run "${SESSION_NAME}" screenshot "${ARTIFACT_DIR}/owner-renew.png" >/dev/null
browser_use_write_output "${SESSION_NAME}" "${ARTIFACT_DIR}/owner-renew.snapshot.md" snapshot || true
python3 - "${OWNER_RENEW_DETAILS}" "${ARTIFACT_DIR}/owner-renew.png" "${ARTIFACT_DIR}/owner-renew.snapshot.md" "${OWNER_PLAN_ID}" "${renew_before_text}" "${renew_before_preview}" "${renew_after_text}" "${renew_after_preview}" <<'PY'
import json
import sys

output_path, screenshot_path, snapshot_path, plan_id, before_text, before_preview, after_text, after_preview = sys.argv[1:9]
payload = {
    "plan_id": plan_id,
    "reply_before": before_text,
    "preview_before": before_preview,
    "reply_after": after_text,
    "preview_after": after_preview,
    "artifacts": {
        "screenshot": screenshot_path,
        "snapshot": snapshot_path,
    },
}
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2)
    handle.write("\n")
PY
complete_step "Sent /renew from the live Telegram Web owner chat and observed the bot confirm that billing checks finished." "${OWNER_RENEW_DETAILS}"

begin_step "promote_runtime" "system"
set +e
promote_output="$(
  run_orchestrator_timed "${ORCHESTRATOR_COMMAND_TIMEOUT_SEC}" \
    --skip-build \
    --config-file "${PROMOTE_CONFIG_FILE}" \
    --subscription-bot-env-file "${ORIGINAL_ENV_FILE}" \
    --component-release-dir "${RELEASE_DIR}" \
    --action redeploy_component \
    --component subscription_bot 2>&1
)"
promote_rc=$?
set -e
printf '%s\n' "${promote_output}" >"${ARTIFACT_DIR}/promote.log"
if (( promote_rc != 0 )); then
  if ! recover_subscription_redeploy_wrapper_failure "${promote_output}" "promote_runtime" "false" "true" "true"; then
    exit "${promote_rc}"
  fi
fi

promoted_runtime_env_lines="$(
  pixel_transport_root_exec /system/bin/sh -c \
    "grep -E '^(SUBSCRIPTION_BOT_E2E_MODE|SUBSCRIPTION_BOT_WEB_ENABLED|SUBSCRIPTION_BOT_WEB_SHELL_ENABLED|SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED)=' /data/local/pixel-stack/apps/subscription-bot/env/subscription-bot.env 2>/dev/null || true" | tr -d '\r'
)"
promoted_device_health_output="$(pixel_transport_root_exec /system/bin/sh /data/local/pixel-stack/bin/pixel-subscription-health.sh | tr -d '\r')"
printf '%s\n' "${promoted_device_health_output}" >"${ARTIFACT_DIR}/promoted-device-health.txt"
grep -q '^healthy=1$' <<<"${promoted_device_health_output}"
grep -q '^SUBSCRIPTION_BOT_E2E_MODE=false$' <<<"${promoted_runtime_env_lines}"
grep -q '^SUBSCRIPTION_BOT_WEB_ENABLED=true$' <<<"${promoted_runtime_env_lines}"
grep -q '^SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=true$' <<<"${promoted_runtime_env_lines}"
grep -q '^SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED=true$' <<<"${promoted_runtime_env_lines}"

DEPLOYED_E2E=0
complete_step "Kept the new subscription bot build live, restored the normal production runtime with e2e mode off, the native Mini App shell still enabled, public web enabled, and the tunnel re-enabled, and verified the device stayed healthy." "${ARTIFACT_DIR}/promote.log"

log "Subscription bot E2E completed. Report: ${REPORT_JSON}"
