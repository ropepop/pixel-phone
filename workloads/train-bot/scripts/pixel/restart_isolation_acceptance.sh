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

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
out_dir="${RESTART_ISOLATION_OUT_DIR:-$REPO_ROOT/output/pixel/component-isolation-${timestamp_utc}}"
mkdir -p "${out_dir}"

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
  local log_name="$3"
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")

  pixel_transport_append_cli_args cmd
  cmd+=(--skip-build --action "${action}")

  if [[ -n "${component}" ]]; then
    cmd+=(--component "${component}")
  fi

  log "Running orchestrator action: ${action}${component:+ component=${component}}"
  "${cmd[@]}" | tee "${out_dir}/${log_name}"
}

snapshot_field() {
  local file="$1"
  local key="$2"
  awk -F'=' -v target="${key}" '$1 == target {print $2; exit}' "${file}"
}

capture_adguard_snapshot() {
  local target="$1"
  {
    printf 'adguardhome_host_pid=%s\n' "$(adb_cmd shell su -c 'cat /data/local/pixel-stack/run/adguardhome-host.pid 2>/dev/null || true' | tr -d '\r' | tr -d '[:space:]')"
    printf 'adguardhome_chroot_pid=%s\n' "$(adb_cmd shell su -c 'cat /data/local/pixel-stack/chroots/adguardhome/run/pixel-stack-adguardhome.pid 2>/dev/null || true' | tr -d '\r' | tr -d '[:space:]')"
    printf 'listeners=%s\n' "$(adb_cmd shell su -c 'ss -ltn 2>/dev/null | grep -E ":53 |:443 |:853 " || true' | tr -d '\r' | sort | tr '\n' ';')"
  } > "${target}"
}

capture_train_snapshot() {
  local target="$1"
  {
    printf 'train_bot_pid=%s\n' "$(adb_cmd shell su -c 'ps -A | awk "(\$NF==\"train-bot\" || index(\$NF,\"train-bot.\")==1) {print \$2; exit}"' | tr -d '\r' | tr -d '[:space:]')"
    printf 'train_cloudflared_pid=%s\n' "$(adb_cmd shell su -c 'cat /data/local/pixel-stack/apps/train-bot/run/train-bot-cloudflared.pid 2>/dev/null || true' | tr -d '\r' | tr -d '[:space:]')"
    printf 'train_tunnel_supervisor_pid=%s\n' "$(adb_cmd shell su -c 'cat /data/local/pixel-stack/apps/train-bot/run/train-web-tunnel-service-loop.pid 2>/dev/null || true' | tr -d '\r' | tr -d '[:space:]')"
    printf 'train_current=%s\n' "$(adb_cmd shell su -c 'readlink -f /data/local/pixel-stack/apps/train-bot/bin/train-bot.current 2>/dev/null || true' | tr -d '\r' | tr -d '[:space:]')"
  } > "${target}"
}

require_nonempty_field() {
  local file="$1"
  local key="$2"
  local value
  value="$(snapshot_field "${file}" "${key}")"
  if [[ -z "${value}" ]]; then
    log "Missing required field ${key} in ${file}"
    exit 1
  fi
}

require_train_public_contract() {
  local target="$1"
  local root_code app_code departures_code stations_code legacy_code

  root_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "${train_public_base_url%/}/" || true)"
  app_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "${train_public_base_url%/}/app" || true)"
  departures_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "${train_public_base_url%/}/departures" || true)"
  stations_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "${train_public_base_url%/}/stations" || true)"
  legacy_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 https://dns.jolkins.id.lv/pixel-stack/train/app || true)"

  {
    printf 'root=%s\n' "${root_code}"
    printf 'app=%s\n' "${app_code}"
    printf 'departures=%s\n' "${departures_code}"
    printf 'stations=%s\n' "${stations_code}"
    printf 'legacy=%s\n' "${legacy_code}"
  } > "${target}"

  if [[ "${root_code}" != "200" || "${app_code}" != "200" || "${departures_code}" != "200" || "${stations_code}" != "200" ]]; then
    log "Train public contract check failed; see ${target}"
    exit 1
  fi
  case "${legacy_code}" in
    404|410|500|502|503|504|000) ;;
    *)
      log "Legacy Train path unexpectedly served traffic; see ${target}"
      exit 1
      ;;
  esac
}

compare_snapshots() {
  local before="$1"
  local after="$2"
  local description="$3"
  if ! cmp -s "${before}" "${after}"; then
    log "${description} changed unexpectedly"
    diff -u "${before}" "${after}" || true
    exit 1
  fi
}

run_orchestrator_action health "" "baseline-health.log"

train_restart_dir="${out_dir}/train-restart"
dns_restart_dir="${out_dir}/dns-restart"
mkdir -p "${train_restart_dir}" "${dns_restart_dir}"

capture_adguard_snapshot "${train_restart_dir}/adguard-before.txt"
require_nonempty_field "${train_restart_dir}/adguard-before.txt" "adguardhome_host_pid"
require_nonempty_field "${train_restart_dir}/adguard-before.txt" "adguardhome_chroot_pid"
require_nonempty_field "${train_restart_dir}/adguard-before.txt" "listeners"
run_orchestrator_action restart_component train_bot "train-restart.log"
capture_adguard_snapshot "${train_restart_dir}/adguard-after.txt"
compare_snapshots "${train_restart_dir}/adguard-before.txt" "${train_restart_dir}/adguard-after.txt" "AdGuard snapshot after train_bot restart"
capture_train_snapshot "${train_restart_dir}/train-after.txt"
require_nonempty_field "${train_restart_dir}/train-after.txt" "train_bot_pid"
require_nonempty_field "${train_restart_dir}/train-after.txt" "train_cloudflared_pid"
require_nonempty_field "${train_restart_dir}/train-after.txt" "train_tunnel_supervisor_pid"
require_nonempty_field "${train_restart_dir}/train-after.txt" "train_current"
require_train_public_contract "${train_restart_dir}/public-contract.txt"
run_orchestrator_action health "" "train-health.log"
run_orchestrator_action health_component train_bot "train-health-component.log"
run_orchestrator_action health_component dns "train-dns-health-component.log"

capture_train_snapshot "${dns_restart_dir}/train-before.txt"
require_nonempty_field "${dns_restart_dir}/train-before.txt" "train_bot_pid"
require_nonempty_field "${dns_restart_dir}/train-before.txt" "train_cloudflared_pid"
require_nonempty_field "${dns_restart_dir}/train-before.txt" "train_tunnel_supervisor_pid"
require_nonempty_field "${dns_restart_dir}/train-before.txt" "train_current"
run_orchestrator_action restart_component dns "dns-restart.log"
capture_train_snapshot "${dns_restart_dir}/train-after.txt"
compare_snapshots "${dns_restart_dir}/train-before.txt" "${dns_restart_dir}/train-after.txt" "Train Bot snapshot after dns restart"
require_train_public_contract "${dns_restart_dir}/public-contract.txt"
run_orchestrator_action health "" "dns-health.log"
run_orchestrator_action health_component train_bot "dns-train-health-component.log"
run_orchestrator_action health_component dns "dns-health-component.log"

log "Component restart isolation checks passed. Artifacts: ${out_dir}"
