#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"

usage() {
  cat <<'USAGE'
Usage: release_check.sh [options]

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
ensure_output_dirs
ensure_local_env

for cmd in curl python3 shasum; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  . "$REPO_ROOT/.env"
  set +a
fi

preflight_cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}" --action health_component --component satiksme_bot --skip-build)
pixel_transport_append_cli_args preflight_cmd
"${preflight_cmd[@]}"

public_base_url="${SATIKSME_WEB_PUBLIC_BASE_URL:-https://satiksme-bot.jolkins.id.lv}"
origin_port="${SATIKSME_WEB_ORIGIN_PORT:-${SATIKSME_WEB_PORT:-9327}}"
forward_port="${SATIKSME_WEB_ORIGIN_FORWARD_PORT:-$(reserve_local_port)}"
attempts="${SATIKSME_WEB_RELEASE_CHECK_ATTEMPTS:-30}"
sleep_seconds="${SATIKSME_WEB_RELEASE_CHECK_SLEEP_SEC:-2}"
timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
report_file="${RELEASE_REPORT_FILE:-$REPO_ROOT/output/pixel/satiksme-bot-release-check-${timestamp_utc}.json}"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
  adb_cmd forward --remove "tcp:${forward_port}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

base_path="$(
  python3 - "${public_base_url}" <<'PY'
import sys
from urllib.parse import urlparse

parsed = urlparse(sys.argv[1].strip())
path = (parsed.path or "").rstrip("/")
print(path)
PY
)"
origin_base_url="http://127.0.0.1:${forward_port}${base_path}"
origin_health_url="${origin_base_url}/api/v1/health"
public_health_url="${public_base_url%/}/api/v1/health"

adb_cmd forward --remove "tcp:${forward_port}" >/dev/null 2>&1 || true
adb_cmd forward "tcp:${forward_port}" "tcp:${origin_port}" >/dev/null

health_field() {
  local file="$1"
  local dotted="$2"
  python3 - "$file" "$dotted" <<'PY'
import json
import sys

path = sys.argv[2].split(".")
payload = json.loads(open(sys.argv[1], "r", encoding="utf-8").read())
cur = payload
for part in path:
    if isinstance(cur, dict):
        cur = cur.get(part)
    else:
        cur = None
        break
if cur is None:
    print("")
elif isinstance(cur, bool):
    print("true" if cur else "false")
else:
    print(cur)
PY
}

header_value() {
  local header_dump="$1"
  local header_name="$2"
  printf '%s\n' "$header_dump" | awk -F': ' -v target="$(printf '%s' "$header_name" | tr '[:upper:]' '[:lower:]')" '
    tolower($1) == target {
      gsub("\r", "", $2)
      print $2
      exit
    }
  '
}

asset_sha_from_body() {
  local url="$1"
  local target="$2"
  curl -fsS "$url" -o "$target"
  shasum -a 256 "$target" | awk '{print $1}'
}

origin_commit=""
public_commit=""
origin_instance=""
public_instance=""
origin_app_js=""
public_app_js=""
origin_app_css=""
public_app_css=""
origin_app_js_body_sha=""
public_app_js_body_sha=""
origin_app_css_body_sha=""
public_app_css_body_sha=""
parity_ok=0

for attempt in $(seq 1 "${attempts}"); do
  if curl -fsS --max-time 15 "$origin_health_url" -o "$tmp_dir/origin-health.json" &&
    curl -fsS --max-time 15 "$public_health_url" -o "$tmp_dir/public-health.json"; then
    origin_commit="$(health_field "$tmp_dir/origin-health.json" "version.commit")"
    public_commit="$(health_field "$tmp_dir/public-health.json" "version.commit")"
    origin_instance="$(health_field "$tmp_dir/origin-health.json" "runtime.instanceId")"
    public_instance="$(health_field "$tmp_dir/public-health.json" "runtime.instanceId")"
    origin_app_js="$(health_field "$tmp_dir/origin-health.json" "assets.appJsSha256")"
    public_app_js="$(health_field "$tmp_dir/public-health.json" "assets.appJsSha256")"
    origin_app_css="$(health_field "$tmp_dir/origin-health.json" "assets.appCssSha256")"
    public_app_css="$(health_field "$tmp_dir/public-health.json" "assets.appCssSha256")"
    if [[ -n "${origin_commit}" && "${origin_commit}" == "${public_commit}" &&
      -n "${origin_instance}" && "${origin_instance}" == "${public_instance}" &&
      -n "${origin_app_js}" && "${origin_app_js}" == "${public_app_js}" &&
      -n "${origin_app_css}" && "${origin_app_css}" == "${public_app_css}" ]]; then
      origin_app_js_body_sha="$(asset_sha_from_body "${origin_base_url}/assets/app.js?v=${origin_app_js}" "$tmp_dir/origin-app.js")"
      public_app_js_body_sha="$(asset_sha_from_body "${public_base_url%/}/assets/app.js?v=${public_app_js}" "$tmp_dir/public-app.js")"
      origin_app_css_body_sha="$(asset_sha_from_body "${origin_base_url}/assets/app.css?v=${origin_app_css}" "$tmp_dir/origin-app.css")"
      public_app_css_body_sha="$(asset_sha_from_body "${public_base_url%/}/assets/app.css?v=${public_app_css}" "$tmp_dir/public-app.css")"
      if [[ -n "${origin_app_js_body_sha}" && "${origin_app_js_body_sha}" == "${public_app_js_body_sha}" &&
        -n "${origin_app_css_body_sha}" && "${origin_app_css_body_sha}" == "${public_app_css_body_sha}" ]]; then
        parity_ok=1
        break
      fi
    fi
  fi
  sleep "${sleep_seconds}"
done

declare -a instance_samples=()
declare -a app_js_samples=()
declare -a app_css_samples=()
declare -a sample_codes=()

if (( parity_ok == 1 )); then
  for _ in $(seq 1 10); do
    headers="$(curl -sS -D - -o /dev/null --max-time 15 "$public_health_url")"
    sample_codes+=("$(printf '%s\n' "$headers" | awk 'NR==1 {print $2; exit}')")
    instance_samples+=("$(header_value "$headers" "X-Satiksme-Bot-Instance")")
    app_js_samples+=("$(header_value "$headers" "X-Satiksme-Bot-App-Js")")
    app_css_samples+=("$(header_value "$headers" "X-Satiksme-Bot-App-Css")")
    sleep 1
  done
fi

unique_instance_count="$(printf '%s\n' "${instance_samples[@]:-}" | awk 'NF' | sort -u | wc -l | tr -d '[:space:]')"
unique_app_js_count="$(printf '%s\n' "${app_js_samples[@]:-}" | awk 'NF' | sort -u | wc -l | tr -d '[:space:]')"
unique_app_css_count="$(printf '%s\n' "${app_css_samples[@]:-}" | awk 'NF' | sort -u | wc -l | tr -d '[:space:]')"
headers_stable=0
if (( parity_ok == 1 )) &&
  [[ "${unique_instance_count:-0}" == "1" ]] &&
  [[ "${unique_app_js_count:-0}" == "1" ]] &&
  [[ "${unique_app_css_count:-0}" == "1" ]] &&
  [[ "${instance_samples[0]:-}" == "${origin_instance}" ]] &&
  [[ "${app_js_samples[0]:-}" == "${origin_app_js}" ]] &&
  [[ "${app_css_samples[0]:-}" == "${origin_app_css}" ]]; then
  headers_stable=1
fi
for code in "${sample_codes[@]:-}"; do
  if [[ -n "${code}" && "${code}" != "200" ]]; then
    headers_stable=0
  fi
done

current_binary_sha="$(
  adb_cmd shell su -c "sha256sum /data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot.current 2>/dev/null | awk '{print \$1}'" | tr -d '\r' | tr -d '[:space:]'
)"
tunnel_id="$(
  adb_cmd shell su -c "path=''; if [ -f /data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json ]; then path=/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json; fi; if [ -n \"\$path\" ]; then value=\$(cat \"\$path\" 2>/dev/null | tr -d '\n\r' | sed -n 's/.*\"TunnelID\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p'); printf '%s' \"\$value\"; fi" | tr -d '\r' | tr -d '[:space:]'
)"
git_sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"

export REPORT_GENERATED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export REPORT_GIT_SHA="${git_sha}"
export REPORT_BINARY_SHA="${current_binary_sha}"
export REPORT_TUNNEL_ID="${tunnel_id}"
export REPORT_PUBLIC_BASE_URL="${public_base_url}"
export REPORT_ORIGIN_BASE_URL="${origin_base_url}"
export REPORT_ORIGIN_COMMIT="${origin_commit}"
export REPORT_PUBLIC_COMMIT="${public_commit}"
export REPORT_ORIGIN_INSTANCE="${origin_instance}"
export REPORT_PUBLIC_INSTANCE="${public_instance}"
export REPORT_ORIGIN_APP_JS="${origin_app_js}"
export REPORT_PUBLIC_APP_JS="${public_app_js}"
export REPORT_ORIGIN_APP_CSS="${origin_app_css}"
export REPORT_PUBLIC_APP_CSS="${public_app_css}"
export REPORT_ORIGIN_APP_JS_BODY_SHA="${origin_app_js_body_sha}"
export REPORT_PUBLIC_APP_JS_BODY_SHA="${public_app_js_body_sha}"
export REPORT_ORIGIN_APP_CSS_BODY_SHA="${origin_app_css_body_sha}"
export REPORT_PUBLIC_APP_CSS_BODY_SHA="${public_app_css_body_sha}"
export REPORT_SAMPLE_CODES="$(IFS=,; printf '%s' "${sample_codes[*]}")"
export REPORT_INSTANCE_SAMPLES="$(IFS=,; printf '%s' "${instance_samples[*]}")"
export REPORT_APP_JS_SAMPLES="$(IFS=,; printf '%s' "${app_js_samples[*]}")"
export REPORT_APP_CSS_SAMPLES="$(IFS=,; printf '%s' "${app_css_samples[*]}")"
export REPORT_PARITY_OK="${parity_ok}"
export REPORT_HEADERS_STABLE="${headers_stable}"

python3 - "$report_file" <<'PY'
import json
import os
import sys

report = {
    "generatedAtUtc": os.environ["REPORT_GENERATED_AT_UTC"],
    "gitSha": os.environ["REPORT_GIT_SHA"],
    "binarySha256": os.environ["REPORT_BINARY_SHA"],
    "tunnelId": os.environ["REPORT_TUNNEL_ID"],
    "publicBaseUrl": os.environ["REPORT_PUBLIC_BASE_URL"],
    "originBaseUrl": os.environ["REPORT_ORIGIN_BASE_URL"],
    "origin": {
        "commit": os.environ["REPORT_ORIGIN_COMMIT"],
        "instanceId": os.environ["REPORT_ORIGIN_INSTANCE"],
        "appJsSha256": os.environ["REPORT_ORIGIN_APP_JS"],
        "appCssSha256": os.environ["REPORT_ORIGIN_APP_CSS"],
        "appJsBodySha256": os.environ["REPORT_ORIGIN_APP_JS_BODY_SHA"],
        "appCssBodySha256": os.environ["REPORT_ORIGIN_APP_CSS_BODY_SHA"],
    },
    "public": {
        "commit": os.environ["REPORT_PUBLIC_COMMIT"],
        "instanceId": os.environ["REPORT_PUBLIC_INSTANCE"],
        "appJsSha256": os.environ["REPORT_PUBLIC_APP_JS"],
        "appCssSha256": os.environ["REPORT_PUBLIC_APP_CSS"],
        "appJsBodySha256": os.environ["REPORT_PUBLIC_APP_JS_BODY_SHA"],
        "appCssBodySha256": os.environ["REPORT_PUBLIC_APP_CSS_BODY_SHA"],
        "sampleStatusCodes": [item for item in os.environ["REPORT_SAMPLE_CODES"].split(",") if item],
        "instanceSamples": [item for item in os.environ["REPORT_INSTANCE_SAMPLES"].split(",") if item],
        "appJsSamples": [item for item in os.environ["REPORT_APP_JS_SAMPLES"].split(",") if item],
        "appCssSamples": [item for item in os.environ["REPORT_APP_CSS_SAMPLES"].split(",") if item],
    },
    "parity": {
        "healthMatched": os.environ["REPORT_PARITY_OK"] == "1",
        "headersStable": os.environ["REPORT_HEADERS_STABLE"] == "1",
    },
}

with open(sys.argv[1], "w", encoding="utf-8") as fh:
    json.dump(report, fh, indent=2, sort_keys=True)
    fh.write("\n")
PY

log "Release parity report: $report_file"

if (( parity_ok != 1 )); then
  log "Release check failed: origin/public health or asset parity did not converge"
  exit 1
fi
if (( headers_stable != 1 )); then
  log "Release check failed: public release headers were not stable across repeated samples"
  exit 1
fi

log "Release parity check passed for ${public_base_url}"
