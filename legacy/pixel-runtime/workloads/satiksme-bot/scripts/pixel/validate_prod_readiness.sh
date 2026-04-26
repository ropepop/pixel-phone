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

for cmd in make git curl python3 rg spacetime; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
evidence_dir="$REPO_ROOT/ops/evidence/satiksme-bot/${timestamp_utc}"
report_file="$REPO_ROOT/output/pixel/satiksme-bot-prod-readiness-${timestamp_utc}.md"
workspace_health_log="$REPO_ROOT/output/pixel/satiksme-bot-workspace-health-${timestamp_utc}.log"
workspace_health_report="$REPO_ROOT/output/pixel/satiksme-bot-workspace-health-${timestamp_utc}.json"
build_log="$REPO_ROOT/output/pixel/satiksme-bot-native-build-${timestamp_utc}.log"
baseline_health_log="$REPO_ROOT/output/pixel/satiksme-bot-baseline-health-${timestamp_utc}.log"
post_redeploy_health_log="$REPO_ROOT/output/pixel/satiksme-bot-post-redeploy-health-${timestamp_utc}.log"
release_check_log="$REPO_ROOT/output/pixel/satiksme-bot-release-check-${timestamp_utc}.log"
release_check_report="$REPO_ROOT/output/pixel/satiksme-bot-release-check-${timestamp_utc}.json"
public_smoke_log="$REPO_ROOT/output/pixel/satiksme-bot-public-smoke-${timestamp_utc}.log"
miniapp_smoke_log="$REPO_ROOT/output/pixel/satiksme-bot-miniapp-smoke-${timestamp_utc}.log"
browser_sanity_log="$REPO_ROOT/output/pixel/satiksme-bot-browser-sanity-${timestamp_utc}.log"
browser_sanity_report="$REPO_ROOT/output/pixel/satiksme-bot-browser-sanity-${timestamp_utc}.json"
browser_sanity_dir="$REPO_ROOT/output/browser-use/pixel-browser-sanity-${timestamp_utc}"
spacetime_schema_gate_log="$REPO_ROOT/output/pixel/satiksme-bot-spacetime-schema-gate-${timestamp_utc}.log"
spacetime_log_gate_log="$REPO_ROOT/output/pixel/satiksme-bot-spacetime-log-gate-${timestamp_utc}.log"
origin_health_file="$REPO_ROOT/output/pixel/satiksme-bot-origin-health-${timestamp_utc}.json"
public_health_file="$REPO_ROOT/output/pixel/satiksme-bot-public-health-${timestamp_utc}.json"
mkdir -p "$evidence_dir"

resolve_spacetime_target() {
  local env_file="${REPO_ROOT}/.env"
  local runtime_enabled="" runtime_host="" runtime_database=""
  local web_enabled="" web_host="" web_database=""

  runtime_enabled="$(env_file_value "${env_file}" "SATIKSME_RUNTIME_SPACETIME_ENABLED")"
  runtime_host="$(env_file_value "${env_file}" "SATIKSME_RUNTIME_SPACETIME_HOST")"
  runtime_database="$(env_file_value "${env_file}" "SATIKSME_RUNTIME_SPACETIME_DATABASE")"
  web_enabled="$(env_file_value "${env_file}" "SATIKSME_WEB_SPACETIME_ENABLED")"
  web_host="$(env_file_value "${env_file}" "SATIKSME_WEB_SPACETIME_HOST")"
  web_database="$(env_file_value "${env_file}" "SATIKSME_WEB_SPACETIME_DATABASE")"

  if value_is_truthy "${runtime_enabled}" && value_is_truthy "${web_enabled}" && [[ -n "${runtime_host}" && -n "${runtime_database}" && -n "${web_host}" && -n "${web_database}" ]]; then
    if [[ "${runtime_host%/}" != "${web_host%/}" || "${runtime_database}" != "${web_database}" ]]; then
      echo "Runtime and web Spacetime targets differ in .env; readiness validation requires a single shared target" >&2
      return 1
    fi
  fi

  if value_is_truthy "${runtime_enabled}" && [[ -n "${runtime_host}" && -n "${runtime_database}" ]]; then
    printf '%s\n%s\n' "${runtime_host%/}" "${runtime_database}"
    return 0
  fi
  if value_is_truthy "${web_enabled}" && [[ -n "${web_host}" && -n "${web_database}" ]]; then
    printf '%s\n%s\n' "${web_host%/}" "${web_database}"
    return 0
  fi
  return 1
}

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
  local origin_port="${SATIKSME_WEB_ORIGIN_PORT:-9327}"
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
  local rc=0
  log "Running gate: $name"
  set +e
  "$@"
  rc=$?
  set -e
  if (( rc == 0 )); then
    log "Gate passed: $name"
    return 0
  fi
  log "Gate failed: $name (exit $rc)"
  return "$rc"
}

run_baseline_health_gate() {
  transport_exec "${ORCHESTRATOR_DEPLOY_SCRIPT}" --action health_component --component satiksme_bot --skip-build >"${baseline_health_log}" 2>&1
}

run_workspace_health_gate() {
  (
    cd "${REPO_ROOT}"
    export WORKSPACE_HEALTH_LOG_FILE="${workspace_health_log}"
    export WORKSPACE_HEALTH_REPORT_FILE="${workspace_health_report}"
    ./scripts/pixel/workspace_health.sh
  )
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

run_browser_sanity_gate() {
  (
    cd "${REPO_ROOT}"
    export BROWSER_SANITY_LOG_FILE="${browser_sanity_log}"
    export BROWSER_SANITY_REPORT_FILE="${browser_sanity_report}"
    export BROWSER_SANITY_OUT_DIR="${browser_sanity_dir}"
    ./scripts/pixel/browser_sanity.sh
  )
}

run_spacetime_schema_gate() {
  local target_host="" target_database=""
  local resolved_target=""

  if ! resolved_target="$(resolve_spacetime_target)"; then
    echo "Could not resolve Spacetime host/database from .env" >&2
    return 1
  fi
  target_host="$(printf '%s\n' "${resolved_target}" | sed -n '1p')"
  target_database="$(printf '%s\n' "${resolved_target}" | sed -n '2p')"
  if [[ -z "${target_host}" || -z "${target_database}" ]]; then
    echo "Resolved empty Spacetime host/database" >&2
    return 1
  fi

  verify_spacetime_schema_version "${target_host}" "${target_database}" >"${spacetime_schema_gate_log}"
}

run_spacetime_log_gate() {
  local target_host="" target_database=""
  local resolved_target=""
  local logs_stderr_file=""
  local pipeline_rc=0
  if [[ -z "${spacetime_log_window_start_us:-}" ]]; then
    echo "Spacetime log window start is not set" >&2
    return 1
  fi
  if ! resolved_target="$(resolve_spacetime_target)"; then
    echo "Could not resolve Spacetime host/database from .env" >&2
    return 1
  fi
  target_host="$(printf '%s\n' "${resolved_target}" | sed -n '1p')"
  target_database="$(printf '%s\n' "${resolved_target}" | sed -n '2p')"
  if [[ -z "${target_host}" || -z "${target_database}" ]]; then
    echo "Resolved empty Spacetime host/database" >&2
    return 1
  fi

  logs_stderr_file="$(mktemp "${TMPDIR:-/tmp}/satiksme-log-gate-stderr.XXXXXX")"
  set +e
  spacetime logs \
    --no-config \
    --server "${target_host}" \
    --format json \
    --level error \
    --yes \
    "${target_database}" 2>"${logs_stderr_file}" | python3 - "${spacetime_log_window_start_us}" <<'PY' >"${spacetime_log_gate_log}"
import json
import sys

cutoff = int(sys.argv[1])

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    item = json.loads(line)
    if int(item.get("ts") or 0) >= cutoff:
        print(json.dumps(item, ensure_ascii=False))
PY
  pipeline_rc=$?
  set -e

  if (( pipeline_rc != 0 )); then
    if awk 'NF && $0 != "Error: Broken pipe (os error 32)" { found=1 } END { exit found ? 0 : 1 }' "${logs_stderr_file}"; then
      cat "${logs_stderr_file}" >&2
      rm -f "${logs_stderr_file}"
      return 1
    fi
    if [[ ! -s "${logs_stderr_file}" ]]; then
      rm -f "${logs_stderr_file}"
      return 1
    fi
  fi

  if awk 'NF && $0 != "Error: Broken pipe (os error 32)" { found=1 } END { exit found ? 0 : 1 }' "${logs_stderr_file}"; then
    cat "${logs_stderr_file}" >&2
    rm -f "${logs_stderr_file}"
    return 1
  fi
  rm -f "${logs_stderr_file}"

  if rg -n '"function":"(satiksmebot_[^"]+|list_public_[^"]+|service_[^"]+|heartbeat_live_viewer|set_live_viewer_state|get_public_incident_detail)"' "${spacetime_log_gate_log}" >/dev/null 2>&1; then
    echo "Found Spacetime procedure or reducer calls still hitting error logs" >&2
    return 1
  fi
}

status_baseline_health="SKIP"
status_workspace_health="SKIP"
status_build="SKIP"
status_health_convergence="SKIP"
status_release_check="SKIP"
status_spacetime_schema_gate="SKIP"
status_public_smoke="SKIP"
status_miniapp_smoke="SKIP"
status_browser_sanity="SKIP"
status_spacetime_log_gate="SKIP"
status_evidence="SKIP"
spacetime_log_window_start_us=""

declare -a failures

if run_gate "baseline-health" run_baseline_health_gate; then
  status_baseline_health="PASS"
else
  status_baseline_health="FAIL"
  failures+=("baseline satiksme health failed")
fi

if [[ "${status_baseline_health}" == "PASS" ]]; then
  if run_gate "workspace-health" run_workspace_health_gate; then
    status_workspace_health="PASS"
  else
    status_workspace_health="FAIL"
    failures+=("workspace health failed")
  fi
fi

if [[ "${status_workspace_health}" == "PASS" ]]; then
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
  if run_gate "spacetime-schema-gate" run_spacetime_schema_gate; then
    status_spacetime_schema_gate="PASS"
  else
    status_spacetime_schema_gate="FAIL"
    failures+=("Spacetime schema version check failed")
  fi
fi

if [[ "${status_spacetime_schema_gate}" == "PASS" ]]; then
  spacetime_log_window_start_us="$(
    python3 - <<'PY'
import time

print(int(time.time() * 1_000_000))
PY
  )"
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
  if run_gate "browser-sanity" run_browser_sanity_gate; then
    status_browser_sanity="PASS"
  else
    status_browser_sanity="FAIL"
    failures+=("browser sanity failed")
  fi
fi

if [[ "${status_browser_sanity}" == "PASS" ]]; then
  if run_gate "spacetime-log-gate" run_spacetime_log_gate; then
    status_spacetime_log_gate="PASS"
  else
    status_spacetime_log_gate="FAIL"
    failures+=("Spacetime error logs still show procedure or reducer noise")
  fi
fi

if [[ "${status_spacetime_log_gate}" == "PASS" ]]; then
  if capture_health_payloads "${SATIKSME_WEB_PUBLIC_BASE_URL:-https://satiksme-bot.jolkins.id.lv}" "${public_health_file}" "${origin_health_file}"; then
    cp "${baseline_health_log}" "${evidence_dir}/baseline-health.log"
    cp "${workspace_health_log}" "${evidence_dir}/workspace-health.log"
    cp "${workspace_health_report}" "${evidence_dir}/workspace-health.json"
    cp "${post_redeploy_health_log}" "${evidence_dir}/post-redeploy-health.log"
    cp "${release_check_log}" "${evidence_dir}/release-check.log"
    cp "${release_check_report}" "${evidence_dir}/release-check.json"
    cp "${spacetime_schema_gate_log}" "${evidence_dir}/spacetime-schema-gate.log"
    cp "${public_smoke_log}" "${evidence_dir}/public-smoke.log"
    cp "${miniapp_smoke_log}" "${evidence_dir}/miniapp-smoke.log"
    cp "${browser_sanity_log}" "${evidence_dir}/browser-sanity.log"
    cp "${browser_sanity_report}" "${evidence_dir}/browser-sanity.json"
    cp "${spacetime_log_gate_log}" "${evidence_dir}/spacetime-log-gate.log"
    mkdir -p "${evidence_dir}/browser-sanity"
    cp -R "${browser_sanity_dir}/." "${evidence_dir}/browser-sanity/"
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
  echo "## Matrix"
  echo
  echo "- Workspace health: ${status_workspace_health}"
  echo "- Public web: ${status_public_smoke}"
  echo "- Mini app reporting: ${status_miniapp_smoke}"
  echo "- Browser sanity: ${status_browser_sanity}"
  echo "- Spacetime schema gate: ${status_spacetime_schema_gate}"
  echo "- Spacetime log gate: ${status_spacetime_log_gate}"
  echo "- Pixel origin/public parity: ${status_release_check}"
  echo
  echo "## Supporting Gates"
  echo
  echo "- Baseline health: ${status_baseline_health}"
  echo "- Native build: ${status_build}"
  echo "- Health convergence: ${status_health_convergence}"
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
