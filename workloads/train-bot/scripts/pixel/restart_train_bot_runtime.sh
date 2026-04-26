#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
DEFAULT_ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-$DEFAULT_ORCHESTRATOR_CONFIG_FILE}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"

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

while (($# > 0)); do
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
      log "Unknown argument: $1"
      usage >&2
      exit 1
      ;;
  esac
done

ensure_device
ensure_root
ensure_output_dirs

if [[ -z "${ORCHESTRATOR_REPO}" || ! -d "${ORCHESTRATOR_REPO}" ]]; then
  log "Cannot resolve orchestrator repo. Set ORCHESTRATOR_REPO explicitly."
  exit 1
fi
if [[ ! -x "${ORCHESTRATOR_DEPLOY_SCRIPT}" ]]; then
  log "Missing orchestrator deploy script: ${ORCHESTRATOR_DEPLOY_SCRIPT}"
  exit 1
fi

train_public_base_url="$(
  python3 - "${ORCHESTRATOR_CONFIG_FILE}" <<'PY'
import json
import sys
from pathlib import Path

config_path = Path(sys.argv[1])
default_url = "https://train-bot.jolkins.id.lv"
if not config_path.is_file():
    print(default_url)
    raise SystemExit(0)

payload = json.loads(config_path.read_text(encoding="utf-8"))
print((payload.get("trainBot", {}).get("publicBaseUrl") or default_url).strip())
PY
)"

run_orchestrator_action() {
  local action="$1"
  local component="${2:-}"
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")

  pixel_transport_append_cli_args cmd
  cmd+=(--skip-build --action "${action}")

  if [[ -n "${component}" ]]; then
    cmd+=(--component "${component}")
  fi

  log "Running orchestrator action: ${action}${component:+ component=${component}}"
  "${cmd[@]}"
}

require_public_code() {
  local url="$1"
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "${url}" || true)"
  if [[ "${code}" != "200" ]]; then
    log "Public train host check failed: ${url} returned ${code:-unknown}"
    exit 1
  fi
}

run_orchestrator_action restart_component train_bot
run_orchestrator_action health_component train_bot

require_public_code "${train_public_base_url%/}/"
require_public_code "${train_public_base_url%/}/app"
require_public_code "${train_public_base_url%/}/departures"
require_public_code "${train_public_base_url%/}/stations"

log "Train Bot runtime restart completed and public host checks passed."
