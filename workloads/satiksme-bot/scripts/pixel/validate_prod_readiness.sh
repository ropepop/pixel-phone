#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"
TUNNEL_PROVISION_SCRIPT="${SCRIPT_DIR}/provision_cloudflared_tunnel.sh"

usage() {
  cat <<'USAGE'
Usage: validate_prod_readiness.sh [options]

Options:
  --device SERIAL      adb serial to target
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
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
    echo "Satiksme readiness preflight failed: missing orchestrator config ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  ingress_mode="$(orchestrator_satiksme_bot_field "ingressMode" | tr -d '\r' | tr -d '[:space:]')"
  if [[ "${ingress_mode}" != "cloudflare_tunnel" ]]; then
    return 0
  fi

  if [[ ! -x "${TUNNEL_PROVISION_SCRIPT}" ]]; then
    echo "Satiksme readiness preflight failed: missing ${TUNNEL_PROVISION_SCRIPT}" >&2
    exit 1
  fi
  if ! command -v cloudflared >/dev/null 2>&1; then
    echo "Satiksme readiness preflight failed: local cloudflared CLI is required when ingressMode=cloudflare_tunnel" >&2
    exit 1
  fi

  tunnel_name="$(orchestrator_satiksme_bot_field "tunnelName" | tr -d '\r')"
  tunnel_hostname="$(orchestrator_satiksme_bot_field "publicHostname" | tr -d '\r')"
  if [[ -z "${tunnel_hostname}" ]]; then
    echo "Satiksme readiness preflight failed: satiksmeBot.publicBaseUrl hostname is empty in ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  log "Ensuring Cloudflare tunnel route/credentials for ${tunnel_name} (${tunnel_hostname})" >&2
  TUNNEL_NAME="${tunnel_name}" \
  TUNNEL_HOSTNAME="${tunnel_hostname}" \
  PIXEL_CREDENTIALS_FILE="/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json" \
    "${TUNNEL_PROVISION_SCRIPT}"
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
ensure_satiksme_web_tunnel_provisioned

for cmd in make git curl python3 rg; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
evidence_dir="$REPO_ROOT/ops/evidence/satiksme-bot/${timestamp_utc}"
report_file="$REPO_ROOT/output/pixel/satiksme-bot-prod-readiness-${timestamp_utc}.md"
test_log="$REPO_ROOT/output/pixel/satiksme-bot-native-test-${timestamp_utc}.log"
build_log="$REPO_ROOT/output/pixel/satiksme-bot-native-build-${timestamp_utc}.log"
baseline_health_log="$REPO_ROOT/output/pixel/satiksme-bot-baseline-health-${timestamp_utc}.log"
post_redeploy_health_log="$REPO_ROOT/output/pixel/satiksme-bot-post-redeploy-health-${timestamp_utc}.log"
release_check_log="$REPO_ROOT/output/pixel/satiksme-bot-release-check-${timestamp_utc}.log"
release_check_report="$REPO_ROOT/output/pixel/satiksme-bot-release-check-${timestamp_utc}.json"
public_smoke_log="$REPO_ROOT/output/pixel/satiksme-bot-public-smoke-${timestamp_utc}.log"
miniapp_smoke_log="$REPO_ROOT/output/pixel/satiksme-bot-miniapp-smoke-${timestamp_utc}.log"
origin_health_file="$REPO_ROOT/output/pixel/satiksme-bot-origin-health-${timestamp_utc}.json"
public_health_file="$REPO_ROOT/output/pixel/satiksme-bot-public-health-${timestamp_utc}.json"
mkdir -p "$evidence_dir"

transport_exec() {
  local command_path="$1"
  shift
  local -a cmd=("${command_path}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  cmd+=("$@")
  "${cmd[@]}"
}

capture_health_payloads() {
  local base_url="$1"
  local public_out="$2"
  local origin_out="$3"
  local origin_port="${SATIKSME_WEB_ORIGIN_PORT:-${SATIKSME_WEB_PORT:-9327}}"
  local forward_port
  forward_port="$(reserve_local_port)"
  local base_path
  base_path="$(
    python3 - "${base_url}" <<'PY'
import sys
from urllib.parse import urlparse

parsed = urlparse(sys.argv[1].strip())
print((parsed.path or "").rstrip("/"))
PY
  )"
  adb_cmd forward --remove "tcp:${forward_port}" >/dev/null 2>&1 || true
  adb_cmd forward "tcp:${forward_port}" "tcp:${origin_port}" >/dev/null
  curl -fsS --max-time 20 "${base_url%/}/api/v1/health" -o "${public_out}"
  curl -fsS --max-time 20 "http://127.0.0.1:${forward_port}${base_path}/api/v1/health" -o "${origin_out}"
  adb_cmd forward --remove "tcp:${forward_port}" >/dev/null 2>&1 || true
}

poll_satiksme_health() {
  local out_file="$1"
  local attempts="${2:-10}"
  local sleep_seconds="${3:-5}"
  : >"${out_file}"
  local attempt=0
  while (( attempt < attempts )); do
    attempt=$((attempt + 1))
    {
      printf '=== attempt %d (%s) ===\n' "${attempt}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      if transport_exec "${ORCHESTRATOR_DEPLOY_SCRIPT}" --action health_component --component satiksme_bot --skip-build; then
        return 0
      fi
    } >>"${out_file}" 2>&1
    sleep "${sleep_seconds}"
  done
  return 1
}

run_gate() {
  local name="$1"
  shift
  log "Running gate: $name"
  if "$@"; then
    log "Gate passed: $name"
    return 0
  fi
  local rc=$?
  log "Gate failed: $name (exit $rc)"
  return "$rc"
}

run_baseline_health_gate() {
  transport_exec "${ORCHESTRATOR_DEPLOY_SCRIPT}" --action health_component --component satiksme_bot --skip-build >"${baseline_health_log}" 2>&1
}

run_native_tests_gate() {
  (
    cd "${REPO_ROOT}"
    make pixel-native-test
  ) >"${test_log}" 2>&1
}

run_native_build_gate() {
  (
    cd "${REPO_ROOT}"
    make pixel-native-build
  ) >"${build_log}" 2>&1
}

run_release_check_gate() {
  (
    cd "${REPO_ROOT}"
    transport_exec "${REPO_ROOT}/scripts/pixel/release_check.sh"
  ) >"${release_check_log}" 2>&1
}

run_public_smoke_gate() {
  (
    cd "${REPO_ROOT}"
    ./scripts/pixel/public_smoke.sh
  ) >"${public_smoke_log}" 2>&1
}

run_miniapp_smoke_gate() {
  (
    cd "${REPO_ROOT}"
    ./scripts/pixel/miniapp_smoke.sh
  ) >"${miniapp_smoke_log}" 2>&1
}

status_baseline_health="SKIP"
status_tests="SKIP"
status_build="SKIP"
status_health_convergence="SKIP"
status_release_check="SKIP"
status_public_smoke="SKIP"
status_miniapp_smoke="SKIP"
status_evidence="SKIP"

declare -a failures

if run_gate "baseline-health" run_baseline_health_gate; then
  status_baseline_health="PASS"
else
  status_baseline_health="FAIL"
  failures+=("baseline satiksme health failed")
fi

if [[ "${status_baseline_health}" == "PASS" ]]; then
  if run_gate "native-tests" run_native_tests_gate; then
    status_tests="PASS"
  else
    status_tests="FAIL"
    failures+=("native tests failed")
  fi
fi

if [[ "${status_tests}" == "PASS" ]]; then
  if run_gate "native-build" run_native_build_gate; then
    status_build="PASS"
  else
    status_build="FAIL"
    failures+=("native build failed")
  fi
fi

if [[ "${status_build}" == "PASS" ]]; then
  if run_gate "health-convergence" poll_satiksme_health "${post_redeploy_health_log}" 12 5; then
    status_health_convergence="PASS"
  else
    status_health_convergence="FAIL"
    failures+=("satiksme health did not converge during readiness validation")
  fi
fi

if [[ "${status_health_convergence}" == "PASS" ]]; then
  export RELEASE_REPORT_FILE="${release_check_report}"
  if run_gate "release-check" run_release_check_gate; then
    status_release_check="PASS"
  else
    status_release_check="FAIL"
    failures+=("release parity check failed")
  fi
  unset RELEASE_REPORT_FILE
fi

if [[ "${status_release_check}" == "PASS" ]]; then
  if run_gate "public-smoke" run_public_smoke_gate; then
    status_public_smoke="PASS"
  else
    status_public_smoke="FAIL"
    failures+=("public smoke failed")
  fi
fi

if [[ "${status_public_smoke}" == "PASS" ]]; then
  if run_gate "miniapp-smoke" run_miniapp_smoke_gate; then
    status_miniapp_smoke="PASS"
  else
    status_miniapp_smoke="FAIL"
    failures+=("mini app smoke failed")
  fi
fi

if [[ "${status_miniapp_smoke}" == "PASS" ]]; then
  if capture_health_payloads "${SATIKSME_WEB_PUBLIC_BASE_URL:-https://satiksme-bot.jolkins.id.lv}" "${public_health_file}" "${origin_health_file}"; then
    cp "${baseline_health_log}" "${evidence_dir}/baseline-health.log"
    cp "${post_redeploy_health_log}" "${evidence_dir}/post-redeploy-health.log"
    cp "${release_check_log}" "${evidence_dir}/release-check.log"
    cp "${release_check_report}" "${evidence_dir}/release-check.json"
    cp "${public_smoke_log}" "${evidence_dir}/public-smoke.log"
    cp "${miniapp_smoke_log}" "${evidence_dir}/miniapp-smoke.log"
    cp "${public_health_file}" "${evidence_dir}/public-health.json"
    cp "${origin_health_file}" "${evidence_dir}/origin-health.json"
    status_evidence="PASS"
  else
    status_evidence="FAIL"
    failures+=("failed to capture origin/public health payloads")
  fi
fi

git_sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
dirty_count="$(git -C "$REPO_ROOT" status --porcelain | wc -l | tr -d '[:space:]')"

{
  echo "# Satiksme Bot Production Readiness"
  echo
  echo "- Generated at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "- Git SHA: ${git_sha}"
  echo "- Dirty entries: ${dirty_count}"
  echo "- Transport: $(pixel_transport_selected)"
  echo "- Evidence dir: ${evidence_dir}"
  echo
  echo "## Gates"
  echo
  echo "- Baseline health: ${status_baseline_health}"
  echo "- Native tests: ${status_tests}"
  echo "- Native build: ${status_build}"
  echo "- Health convergence: ${status_health_convergence}"
  echo "- Release check: ${status_release_check}"
  echo "- Public smoke: ${status_public_smoke}"
  echo "- Mini app smoke: ${status_miniapp_smoke}"
  echo "- Evidence capture: ${status_evidence}"
  echo
  if (( ${#failures[@]} > 0 )); then
    echo "## Failures"
    echo
    for failure in "${failures[@]}"; do
      echo "- ${failure}"
    done
  else
    echo "## Result"
    echo
    echo "- All satiksme production readiness gates passed."
  fi
} >"${report_file}"

log "Readiness report: ${report_file}"

if (( ${#failures[@]} > 0 )); then
  exit 1
fi
