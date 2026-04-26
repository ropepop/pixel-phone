#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
COMPONENT_PACKAGER="${ORCHESTRATOR_REPO}/scripts/android/package_component_release.sh"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"
PREPARE_RELEASE_SCRIPT="${SCRIPT_DIR}/prepare_native_release.sh"
SYNC_ENV_SCRIPT="${SCRIPT_DIR}/sync_env_to_pixel.sh"
TUNNEL_PROVISION_SCRIPT="${SCRIPT_DIR}/provision_cloudflared_tunnel.sh"
SPACETIME_PUBLISH_SCRIPT="${SCRIPT_DIR}/publish_spacetime_schema.sh"
ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC="${ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC:-600}"
TODAY_RIGA="$(TZ=Europe/Riga date +%F)"
ROOT_RUNTIME_ROOT="/data/local/pixel-stack/apps/train-bot"
ROOT_RUNTIME_ENV_FILE="${ROOT_RUNTIME_ROOT}/env/train-bot.env"
ROOT_CONFIG_ENV_FILE="/data/local/pixel-stack/conf/apps/train-bot.env"

SKIP_BUILD=0
BOOTSTRAP_ONLY=0
START_ONLY=0
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
  --start-only         start train_bot only
  --package-only       package a native train_bot component release and print its release dir
  --validate-only      run train-specific validation without packaging or redeploying
  -h, --help           show help
USAGE
}

orchestrator_train_bot_field() {
  local field="$1"
  python3 - "${ORCHESTRATOR_CONFIG_FILE}" "${field}" <<'PY'
import json
import sys
from urllib.parse import urlparse

config_path, field = sys.argv[1], sys.argv[2]
with open(config_path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)
train_bot = payload.get("trainBot") or {}

if field == "ingressMode":
    print((train_bot.get("ingressMode") or "cloudflare_tunnel").strip())
elif field == "tunnelName":
    print((train_bot.get("tunnelName") or "train-bot").strip())
elif field == "publicHostname":
    parsed = urlparse((train_bot.get("publicBaseUrl") or "https://train-bot.jolkins.id.lv").strip())
    print(parsed.hostname or "")
else:
    raise SystemExit(f"unsupported field: {field}")
PY
}

ensure_train_web_tunnel_provisioned() {
  local ingress_mode="" tunnel_name="" tunnel_hostname=""

  if [[ ! -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    echo "Train web tunnel preflight failed: missing orchestrator config ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  ingress_mode="$(orchestrator_train_bot_field "ingressMode" | tr -d '\r' | tr -d '[:space:]')"
  if [[ "${ingress_mode}" != "cloudflare_tunnel" ]]; then
    return 0
  fi

  if [[ ! -x "${TUNNEL_PROVISION_SCRIPT}" ]]; then
    echo "Train web tunnel preflight failed: missing ${TUNNEL_PROVISION_SCRIPT}" >&2
    exit 1
  fi
  if ! command -v cloudflared >/dev/null 2>&1; then
    echo "Train web tunnel preflight failed: local cloudflared CLI is required when ingressMode=cloudflare_tunnel" >&2
    exit 1
  fi

  tunnel_name="$(orchestrator_train_bot_field "tunnelName" | tr -d '\r')"
  tunnel_hostname="$(orchestrator_train_bot_field "publicHostname" | tr -d '\r')"
  if [[ -z "${tunnel_hostname}" ]]; then
    echo "Train web tunnel preflight failed: trainBot.publicBaseUrl hostname is empty in ${ORCHESTRATOR_CONFIG_FILE}" >&2
    exit 1
  fi

  log "Ensuring Cloudflare tunnel route/credentials for ${tunnel_name} (${tunnel_hostname})" >&2
  TUNNEL_NAME="${tunnel_name}" \
  TUNNEL_HOSTNAME="${tunnel_hostname}" \
  PIXEL_CREDENTIALS_FILE="/data/local/pixel-stack/conf/apps/train-bot-cloudflared.json" \
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
  local line
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(orchestrator_args)
  cmd+=("$@")
  "${cmd[@]}"
}

run_with_timeout() {
  local timeout_sec="$1"
  shift
  local pid elapsed=0

  "$@" &
  pid=$!
  while kill -0 "${pid}" >/dev/null 2>&1; do
    if (( elapsed >= timeout_sec )); then
      kill "${pid}" >/dev/null 2>&1 || true
      wait "${pid}" >/dev/null 2>&1 || true
      return 124
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  wait "${pid}"
}

run_orchestrator_bootstrap() {
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")
  local line
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(orchestrator_args)
  cmd+=(--action bootstrap --train-bot-env-file "${REPO_ROOT}/.env")
  run_with_timeout "${ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC}" "${cmd[@]}"
}

sync_root_env_files() {
  local -a cmd=("${SYNC_ENV_SCRIPT}")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  "${cmd[@]}"
}

runtime_uses_spacetime() {
  local host database
  host="$(adb_shell_root "value=''; for env_file in \"${ROOT_RUNTIME_ENV_FILE}\" \"${ROOT_CONFIG_ENV_FILE}\"; do if [ -f \"\${env_file}\" ]; then value=\$(grep -E '^TRAIN_RUNTIME_SPACETIME_HOST=' \"\${env_file}\" | tail -n 1 | cut -d= -f2-); [ -n \"\$value\" ] && break; fi; done; printf '%s' \"\$value\"" | tr -d '\r' | sed -e "s/^['\"]//" -e "s/['\"]$//")"
  database="$(adb_shell_root "value=''; for env_file in \"${ROOT_RUNTIME_ENV_FILE}\" \"${ROOT_CONFIG_ENV_FILE}\"; do if [ -f \"\${env_file}\" ]; then value=\$(grep -E '^TRAIN_RUNTIME_SPACETIME_DATABASE=' \"\${env_file}\" | tail -n 1 | cut -d= -f2-); [ -n \"\$value\" ] && break; fi; done; printf '%s' \"\$value\"" | tr -d '\r' | sed -e "s/^['\"]//" -e "s/['\"]$//")"
  [[ -n "${host}" && -n "${database}" ]]
}

runtime_today_train_count() {
  local service_date="$1"

  if runtime_uses_spacetime; then
    printf '%s\n' "spacetime_runtime"
    return 0
  fi

  : "${service_date}"
  printf '%s\n' "missing_spacetime_config"
}

runtime_web_port() {
  adb_shell_root "value=''; for env_file in \"${ROOT_RUNTIME_ENV_FILE}\" \"${ROOT_CONFIG_ENV_FILE}\"; do if [ -f \"\${env_file}\" ]; then value=\$(grep -E '^TRAIN_WEB_PORT=' \"\${env_file}\" | tail -n 1 | cut -d= -f2-); [ -n \"\$value\" ] && break; fi; done; if [ -z \"\$value\" ]; then value=9317; fi; printf '%s' \"\$value\"" | tr -d '\r' | tr -d '[:space:]'
}

runtime_public_base_path() {
  local public_base_url=""
  public_base_url="$(adb_shell_root "value=''; for env_file in \"${ROOT_RUNTIME_ENV_FILE}\" \"${ROOT_CONFIG_ENV_FILE}\"; do if [ -f \"\${env_file}\" ]; then value=\$(grep -E '^TRAIN_WEB_PUBLIC_BASE_URL=' \"\${env_file}\" | tail -n 1 | cut -d= -f2-); [ -n \"\$value\" ] && break; fi; done; printf '%s' \"\$value\"" | tr -d '\r' | sed -e "s/^['\"]//" -e "s/['\"]$//")"
  python3 - "${public_base_url}" <<'PY'
import sys
from urllib.parse import urlparse

raw = (sys.argv[1] or "").strip()
if not raw:
    print("")
else:
    print((urlparse(raw).path or "").rstrip("/"))
PY
}

schedule_gate() {
  local service_date="$1"
  local attempts=30
  local attempt count runtime_port base_path forward_port health_url health_url_fallback health_file effective_date fallback_active available

  runtime_port="$(runtime_web_port)"
  if [[ -z "${runtime_port}" || ! "${runtime_port}" =~ ^[0-9]+$ ]]; then
    echo "Schedule gate failed: runtime train web port is missing or invalid (${runtime_port:-unset})" >&2
    return 1
  fi
  forward_port="${TRAIN_WEB_ORIGIN_FORWARD_PORT:-19317}"
  base_path="$(runtime_public_base_path)"
  if [[ -z "${base_path}" ]]; then
    health_url="http://127.0.0.1:${forward_port}/api/v1/health"
  else
    health_url="http://127.0.0.1:${forward_port}${base_path}/api/v1/health"
  fi
  health_url_fallback="http://127.0.0.1:${forward_port}/api/v1/health"
  health_file="$(mktemp)"
  adb_cmd forward --remove "tcp:${forward_port}" >/dev/null 2>&1 || true
  adb_cmd forward "tcp:${forward_port}" "tcp:${runtime_port}" >/dev/null
  cleanup_schedule_gate() {
    rm -f "${health_file}"
    adb_cmd forward --remove "tcp:${forward_port}" >/dev/null 2>&1 || true
  }

  for attempt in $(seq 1 "${attempts}"); do
    count="$(runtime_today_train_count "${service_date}")"
    case "${count}" in
      spacetime_runtime)
        if curl -fsS --max-time 15 "${health_url}" -o "${health_file}" || curl -fsS --max-time 15 "${health_url_fallback}" -o "${health_file}"; then
          available="$(python3 - "${health_file}" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
print("true" if payload.get("scheduleAvailable") else "false")
PY
)"
          effective_date="$(python3 - "${health_file}" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
schedule = payload.get("schedule") or {}
print((schedule.get("effectiveServiceDate") or "").strip())
PY
)"
          fallback_active="$(python3 - "${health_file}" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
print("true" if payload.get("scheduleFallbackActive") else "false")
PY
)"
          if [[ "${available}" == "true" && ( "${effective_date}" == "${service_date}" || "${fallback_active}" == "true" ) ]]; then
            cleanup_schedule_gate
            return 0
          fi
        fi
        ;;
      missing_spacetime_config)
        cleanup_schedule_gate
        echo "Schedule gate failed: runtime Spacetime host/database are missing" >&2
        return 1
        ;;
    esac
    sleep 2
  done
  cleanup_schedule_gate
  echo "Schedule gate failed: runtime health never reported an active projection for ${service_date}" >&2
  return 1
}

run_release_check() {
  if [[ "${SKIP_RELEASE_CHECK:-0}" == "1" ]]; then
    return 0
  fi
  local -a cmd=("${SCRIPT_DIR}/release_check.sh")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  "${cmd[@]}"
}

prepare_release_dir() {
  local output=""
  local release_dir=""

  if [[ -n "${ADB_SERIAL:-}" ]]; then
    output="$(ADB_SERIAL="${ADB_SERIAL}" ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO}" "${PREPARE_RELEASE_SCRIPT}" 2>&1)"
  else
    output="$(ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO}" "${PREPARE_RELEASE_SCRIPT}" 2>&1)"
  fi
  printf '%s\n' "${output}" >&2

  release_dir="$(printf '%s\n' "${output}" | grep -Eo '/[^[:space:]]+/\.artifacts/component-releases/train_bot-[^[:space:]]+' | tail -n 1)"
  if [[ -z "${release_dir}" ]]; then
    release_dir="$(printf '%s\n' "${output}" | tail -n 1 | tr -d '\r')"
  fi
  if [[ ! -d "${release_dir}" || ! -f "${release_dir}/release-manifest.json" ]]; then
    echo "Prepared release dir is invalid: ${release_dir}" >&2
    return 1
  fi
  printf '%s\n' "${release_dir}"
}

run_package_only() {
  prepare_release_dir
}

run_validate_only() {
  ensure_train_web_tunnel_provisioned
  schedule_gate "${TODAY_RIGA}"
  run_release_check
  echo "Train Bot validation complete for ${TODAY_RIGA}"
}

publish_spacetime_schema() {
  if [[ ! -x "${SPACETIME_PUBLISH_SCRIPT}" ]]; then
    echo "Missing train-bot Spacetime publish script: ${SPACETIME_PUBLISH_SCRIPT}" >&2
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
    --start-only)
      START_ONLY=1
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

selected_modes=0
for mode_flag in "${BOOTSTRAP_ONLY}" "${START_ONLY}" "${PACKAGE_ONLY}" "${VALIDATE_ONLY}"; do
  if (( mode_flag == 1 )); then
    selected_modes=$((selected_modes + 1))
  fi
done
if (( selected_modes > 1 )); then
  echo "Choose only one of: --bootstrap-only, --start-only, --package-only, --validate-only" >&2
  exit 2
fi

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
if (( PACKAGE_ONLY == 1 )) || (( BOOTSTRAP_ONLY == 0 && START_ONLY == 0 && VALIDATE_ONLY == 0 )); then
  if [[ ! -x "${COMPONENT_PACKAGER}" ]]; then
    echo "Missing component release packager: ${COMPONENT_PACKAGER}" >&2
    exit 1
  fi
  if [[ ! -x "${PREPARE_RELEASE_SCRIPT}" ]]; then
    echo "Missing native release prep script: ${PREPARE_RELEASE_SCRIPT}" >&2
    exit 1
  fi
fi
if (( START_ONLY == 0 && PACKAGE_ONLY == 0 && VALIDATE_ONLY == 0 )) && [[ ! -x "${SYNC_ENV_SCRIPT}" ]]; then
  echo "Missing env sync script: ${SYNC_ENV_SCRIPT}" >&2
  exit 1
fi
if (( BOOTSTRAP_ONLY == 0 && START_ONLY == 0 && PACKAGE_ONLY == 0 && VALIDATE_ONLY == 0 )) && [[ ! -x "${SPACETIME_PUBLISH_SCRIPT}" ]]; then
  echo "Missing train-bot Spacetime publish script: ${SPACETIME_PUBLISH_SCRIPT}" >&2
  exit 1
fi

if (( PACKAGE_ONLY == 1 )); then
  run_package_only
  exit 0
fi

if (( START_ONLY == 1 )); then
  run_orchestrator --action start_component --component train_bot
  exit 0
fi

if (( VALIDATE_ONLY == 1 )); then
  run_validate_only
  exit 0
fi

sync_root_env_files
ensure_train_web_tunnel_provisioned

if (( BOOTSTRAP_ONLY == 1 )); then
  bootstrap_rc=0
  run_orchestrator_bootstrap || bootstrap_rc=$?
  if (( bootstrap_rc != 0 )); then
    if (( bootstrap_rc == 124 )); then
      echo "Error: orchestrator bootstrap timed out after ${ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC}s; aborting train-bot deploy before runtime enforcement." >&2
    else
      echo "Error: orchestrator bootstrap returned non-zero (${bootstrap_rc}); aborting train-bot deploy before runtime enforcement." >&2
    fi
  fi
  exit "${bootstrap_rc}"
fi

bootstrap_rc=0
run_orchestrator_bootstrap || bootstrap_rc=$?
if (( bootstrap_rc != 0 )); then
  if (( bootstrap_rc == 124 )); then
    echo "Error: orchestrator bootstrap timed out after ${ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC}s; aborting train-bot deploy before runtime enforcement." >&2
  else
    echo "Error: orchestrator bootstrap returned non-zero (${bootstrap_rc}); aborting train-bot deploy before runtime enforcement." >&2
  fi
  exit "${bootstrap_rc}"
fi

release_dir="$(prepare_release_dir)"
if [[ ! -d "${release_dir}" || ! -f "${release_dir}/release-manifest.json" ]]; then
  echo "Component release dir not found after staging: ${release_dir}" >&2
  exit 1
fi
log "Staged Train Bot component release: ${release_dir}" >&2

publish_spacetime_schema

run_orchestrator \
  --component-release-dir "${release_dir}" \
  --action redeploy_component \
  --component train_bot \
  --train-bot-env-file "${REPO_ROOT}/.env"
schedule_gate "${TODAY_RIGA}"
run_release_check

echo "Train Bot redeploy complete for ${TODAY_RIGA}: release staged at ${release_dir}"
