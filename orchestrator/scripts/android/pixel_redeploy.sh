#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORCHESTRATOR_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${ORCHESTRATOR_ROOT}/.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${WORKSPACE_ROOT}/tools/pixel/transport.sh"

BUILD_SCRIPT="${ORCHESTRATOR_ROOT}/scripts/android/build_orchestrator_apk.sh"
DEPLOY_SCRIPT="${ORCHESTRATOR_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
PACKAGE_DNS_COMPONENT_RELEASE_SCRIPT="${ORCHESTRATOR_ROOT}/scripts/android/package_dns_component_release.sh"
PACKAGE_COMPONENT_RELEASE_SCRIPT="${ORCHESTRATOR_ROOT}/scripts/android/package_component_release.sh"
PACKAGE_RUNTIME_SCRIPT="${ORCHESTRATOR_ROOT}/scripts/android/package_runtime_bundle.sh"
RUNTIME_FRESHNESS_SCRIPT="${ORCHESTRATOR_ROOT}/scripts/android/runtime_asset_freshness.sh"
HOST_MIRROR_SCRIPT="${WORKSPACE_ROOT}/tools/pixel/host_mirror.py"
HOST_MIRROR_ROOT="${PIXEL_HOST_MIRROR_ROOT:-${WORKSPACE_ROOT}/host-mirror}"

TRAIN_DEPLOY_SCRIPT="${WORKSPACE_ROOT}/workloads/train-bot/scripts/pixel/redeploy_release.sh"
TRAIN_RELEASE_CHECK_SCRIPT="${WORKSPACE_ROOT}/workloads/train-bot/scripts/pixel/release_check.sh"
SATIKSME_DEPLOY_SCRIPT="${WORKSPACE_ROOT}/workloads/satiksme-bot/scripts/pixel/redeploy_release.sh"
SATIKSME_PREPARE_RELEASE_SCRIPT="${WORKSPACE_ROOT}/workloads/satiksme-bot/scripts/pixel/prepare_native_release.sh"
SATIKSME_RELEASE_CHECK_SCRIPT="${WORKSPACE_ROOT}/workloads/satiksme-bot/scripts/pixel/release_check.sh"
SATIKSME_VALIDATE_SCRIPT="${WORKSPACE_ROOT}/workloads/satiksme-bot/scripts/pixel/validate_prod_readiness.sh"
SATIKSME_SPACETIME_PUBLISH_SCRIPT="${WORKSPACE_ROOT}/workloads/satiksme-bot/scripts/pixel/publish_spacetime_schema.sh"
SITE_DEPLOY_SCRIPT="${WORKSPACE_ROOT}/workloads/site-notifications/scripts/pixel/redeploy_release.sh"
SITE_RELEASE_CHECK_SCRIPT="${WORKSPACE_ROOT}/workloads/site-notifications/scripts/pixel/release_check.sh"
SUBSCRIPTION_DEPLOY_SCRIPT="${WORKSPACE_ROOT}/workloads/subscription-bot/scripts/pixel/redeploy_release.sh"
SUBSCRIPTION_RELEASE_CHECK_SCRIPT="${WORKSPACE_ROOT}/workloads/subscription-bot/scripts/pixel/release_check.sh"

DEFAULT_CONFIG_FILE="${ORCHESTRATOR_ROOT}/configs/orchestrator-config-v1.production.json"
DEFAULT_TRAIN_BOT_ENV_FILE="${WORKSPACE_ROOT}/workloads/train-bot/.env"
DEFAULT_SATIKSME_BOT_ENV_FILE="${WORKSPACE_ROOT}/workloads/satiksme-bot/.env"
DEFAULT_SITE_NOTIFIER_ENV_FILE="${WORKSPACE_ROOT}/workloads/site-notifications/.env"
DEFAULT_SUBSCRIPTION_BOT_ENV_FILE="${WORKSPACE_ROOT}/workloads/subscription-bot/.env"
DEFAULT_DDNS_TOKEN_FILE="${WORKSPACE_ROOT}/infra/pihole/secrets/cloudflare-token"
SATIKSME_COMPONENT_RELEASE_ARGS=(--component satiksme_bot)

ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${DEFAULT_CONFIG_FILE}}"
TRAIN_BOT_ENV_FILE="${TRAIN_BOT_ENV_FILE:-${DEFAULT_TRAIN_BOT_ENV_FILE}}"
SATIKSME_BOT_ENV_FILE="${SATIKSME_BOT_ENV_FILE:-${DEFAULT_SATIKSME_BOT_ENV_FILE}}"
SITE_NOTIFIER_ENV_FILE="${SITE_NOTIFIER_ENV_FILE:-${DEFAULT_SITE_NOTIFIER_ENV_FILE}}"
SUBSCRIPTION_BOT_ENV_FILE="${SUBSCRIPTION_BOT_ENV_FILE:-${DEFAULT_SUBSCRIPTION_BOT_ENV_FILE}}"
DDNS_TOKEN_FILE="${DDNS_TOKEN_FILE:-${DEFAULT_DDNS_TOKEN_FILE}}"
SSH_PUBLIC_KEY_FILE="${SSH_PUBLIC_KEY_FILE:-}"
SSH_PASSWORD_HASH_FILE="${SSH_PASSWORD_HASH_FILE:-}"
ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:-}"
ACME_TOKEN_FILE="${ACME_TOKEN_FILE:-}"
VPN_AUTH_KEY_FILE="${VPN_AUTH_KEY_FILE:-}"
IPINFO_LITE_TOKEN_FILE="${IPINFO_LITE_TOKEN_FILE:-}"
ROOTFS_TARBALL="${PIXEL_RUNTIME_ROOTFS_TARBALL:-}"

ADB_SERIAL=""
SCOPE="full"
MODE="auto"
SKIP_BUILD=0
DESTRUCTIVE_E2E=0
MIRROR_ACTION=""

PIXEL_RUN_ID="${PIXEL_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM}"
export PIXEL_TRANSPORT ADB_SERIAL PIXEL_SSH_HOST PIXEL_SSH_PORT PIXEL_RUN_ID
REPORT_DIR="${WORKSPACE_ROOT}/output/pixel/redeploy/${PIXEL_RUN_ID}"
REPORT_LOG="${REPORT_DIR}/redeploy.log"
SUMMARY_JSON="${REPORT_DIR}/summary.json"
PLAN_TEXT="${REPORT_DIR}/plan.txt"
SATIKSME_SCHEMA_PUBLISH_LOG="${REPORT_DIR}/satiksme-schema-publish.log"
ROOTED_FRESHNESS_PRE_REPORT="${REPORT_DIR}/rooted-freshness.pre.txt"
ROOTED_FRESHNESS_POST_REPORT="${REPORT_DIR}/rooted-freshness.post.txt"

RUNTIME_BUNDLE_DIR=""
RUNTIME_MANIFEST_PATH=""
DNS_RELEASE_DIR=""
TRAIN_BOT_RELEASE_DIR=""
SATIKSME_BOT_RELEASE_DIR=""
SITE_NOTIFIER_RELEASE_DIR=""
SUBSCRIPTION_BOT_RELEASE_DIR=""
TRAIN_BOT_BUNDLE_PATH=""
SATIKSME_BOT_BUNDLE_PATH=""
SITE_NOTIFIER_BUNDLE_PATH=""
SUBSCRIPTION_BOT_BUNDLE_PATH=""
TRAIN_BOT_DEPLOY_RESULT_SOURCE="none"
SATIKSME_BOT_DEPLOY_RESULT_SOURCE="none"
SITE_NOTIFIER_DEPLOY_RESULT_SOURCE="none"
SUBSCRIPTION_BOT_DEPLOY_RESULT_SOURCE="none"
TRAIN_BOT_LIVE_RELEASE_PATH=""
SATIKSME_BOT_LIVE_RELEASE_PATH=""
SITE_NOTIFIER_LIVE_RELEASE_PATH=""
SUBSCRIPTION_BOT_LIVE_RELEASE_PATH=""
TRAIN_BOT_RECOVERY_COMMAND=""
SATIKSME_BOT_RECOVERY_COMMAND=""
SITE_NOTIFIER_RECOVERY_COMMAND=""
SUBSCRIPTION_BOT_RECOVERY_COMMAND=""
LAST_DEPLOY_RESULT_SOURCE="none"

BOOTSTRAP_NEEDED=0
ROOTED_STALE=0
CONFIG_CHANGED=0
DDNS_TOKEN_CHANGED=0
PLATFORM_ARTIFACTS_CHANGED=0
DNS_ARTIFACTS_CHANGED=0
DNS_RELEASE_NEEDED=0
REPAIR_ACTION="none"
ROOTFS_SOURCE="${ROOTFS_TARBALL}"
REMOTE_RECOVERY_TRIGGERED=0
RUN_STATUS="failed"
PREFLIGHT_ROOTED_FRESHNESS="unknown"
FINAL_ROOTED_FRESHNESS="unknown"
FINAL_ROOTED_FRESHNESS_REPORT=""
LIVE_DNS_RUNTIME_CONVERGED=0
PLATFORM_MUTATION_PERFORMED=0
ROOTED_CONVERGENCE_REQUIRED=0
APK_INSTALL_PENDING=0

declare -a ACTIONS_EXECUTED=()
declare -a VALIDATION_RESULTS=()

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --device SERIAL             adb serial to target
  --transport MODE            transport to use (adb|ssh|auto)
  --ssh-host IP               Tailscale or SSH host/IP
  --ssh-port PORT             SSH port (default: 2222)
  --scope full|platform|dns|train_bot|satiksme_bot|site_notifier|subscription_bot
                              deployment scope (default: full)
  --mode auto|force-bootstrap|force-refresh|validate-only
                              deployment mode (default: auto)
  --rootfs-tarball FILE       explicit AdGuardHome rootfs tarball to package for dns/platform scopes
  --skip-build                skip orchestrator APK build
  --destructive-e2e           run destructive restart/kill-recovery checks after standard validation
  mirror-pull                 pull Pixel deployment variables and secrets into the local plaintext mirror
  mirror-audit                compare the local Pixel mirror with the device and report drift
  mirror-push                 push local Pixel mirror changes when the device has not drifted
  deploy-config               push local mirror changes and restart/resync only affected components
  -h, --help                  show help
USAGE
}

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

record_action() {
  ACTIONS_EXECUTED+=("$1")
}

record_validation() {
  VALIDATION_RESULTS+=("$1=$2")
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

stat_mode_octal() {
  local path="$1"
  if stat -f '%Lp' "${path}" >/dev/null 2>&1; then
    stat -f '%Lp' "${path}"
  else
    stat -c '%a' "${path}"
  fi
}

pixel_mirror_pull_remote_tree() {
  local destination="$1"
  local remote_tar="/data/local/tmp/pixel-host-mirror-${PIXEL_RUN_ID}.tar"
  local local_tar=""
  local_tar="$(mktemp "${TMPDIR:-/tmp}/pixel-host-mirror.XXXXXX")"
  mkdir -p "${destination}"
  pixel_transport_root_shell "
    set -e
    list_file='/data/local/tmp/pixel-host-mirror-${PIXEL_RUN_ID}.list'
    rm -f '${remote_tar}'
    rm -f \"\${list_file}\"
    if [ -d /data/local/pixel-stack/conf ]; then
      cd /
      find data/local/pixel-stack/conf \\
        \\( -path data/local/pixel-stack/conf/runtime/artifacts -o -path 'data/local/pixel-stack/conf/runtime/components/*/artifacts' \\) -prune \\
        -o -type f -print > \"\${list_file}\"
      tar -cf '${remote_tar}' -T \"\${list_file}\"
    else
      tmp_empty=\$(mktemp -d /data/local/tmp/pixel-host-mirror-empty.XXXXXX)
      tar -cf '${remote_tar}' -C \"\${tmp_empty}\" .
      rm -rf \"\${tmp_empty}\"
    fi
    chmod 0644 '${remote_tar}'
    rm -f \"\${list_file}\"
  " >/dev/null
  pixel_transport_pull "${remote_tar}" "${local_tar}" >/dev/null
  pixel_transport_root_exec rm -f "${remote_tar}" >/dev/null 2>&1 || true
  tar -C "${destination}" -xf "${local_tar}"
  rm -f "${local_tar}"
  rm -rf "${destination}/data/local/pixel-stack/conf/runtime/artifacts"
  find "${destination}/data/local/pixel-stack/conf/runtime/components" -path '*/artifacts' -type d -prune -exec rm -rf {} + 2>/dev/null || true
}

pixel_mirror_run_helper() {
  local mirror_action="$1"
  local changed_paths_file="${2:-}"
  local tmpdir=""
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "${tmpdir}"' RETURN
  pixel_mirror_pull_remote_tree "${tmpdir}/remote"
  case "${mirror_action}" in
    pull|audit)
      python3 "${HOST_MIRROR_SCRIPT}" "${mirror_action}" --profile pixel --remote-root "${tmpdir}/remote" --mirror-root "${HOST_MIRROR_ROOT}"
      ;;
    push)
      python3 "${HOST_MIRROR_SCRIPT}" push --profile pixel --remote-root "${tmpdir}/remote" --mirror-root "${HOST_MIRROR_ROOT}" --changed-paths-file "${changed_paths_file}"
      ;;
  esac
}

pixel_mirror_apply_changed_paths() {
  local changed_paths_file="$1"
  local rel=""
  local local_path=""
  local remote_path=""
  local mode=""
  while IFS= read -r rel; do
    [[ -n "${rel}" ]] || continue
    local_path="${HOST_MIRROR_ROOT}/${rel}"
    remote_path="/${rel}"
    if [[ -f "${local_path}" ]]; then
      pixel_transport_push "${local_path}" "${remote_path}" >/dev/null
      mode="$(stat_mode_octal "${local_path}")"
      pixel_transport_root_exec chmod "${mode}" "${remote_path}" >/dev/null 2>&1 || true
    else
      pixel_transport_root_exec rm -f "${remote_path}" >/dev/null 2>&1 || true
    fi
  done < "${changed_paths_file}"
}

pixel_mirror_affected_actions() {
  local changed_paths_file="$1"
  local rel=""
  local -a actions=()
  local seen_train=0 seen_satiksme=0 seen_site=0 seen_subscription=0 seen_dns=0 seen_ssh=0 seen_vpn=0 seen_ddns=0
  while IFS= read -r rel; do
    case "${rel}" in
      data/local/pixel-stack/conf/apps/train-bot.env|data/local/pixel-stack/conf/apps/train-bot-cloudflared.json) seen_train=1 ;;
      data/local/pixel-stack/conf/apps/satiksme-bot.env) seen_satiksme=1 ;;
      data/local/pixel-stack/conf/apps/site-notifications.env) seen_site=1 ;;
      data/local/pixel-stack/conf/apps/subscription-bot.env) seen_subscription=1 ;;
      data/local/pixel-stack/conf/ssh/*) seen_ssh=1 ;;
      data/local/pixel-stack/conf/vpn/*) seen_vpn=1 ;;
      data/local/pixel-stack/conf/ddns/*) seen_ddns=1 ;;
      data/local/pixel-stack/conf/adguardhome/*|data/local/pixel-stack/conf/runtime/runtime-manifest.json|data/local/pixel-stack/conf/runtime/components/dns/*) seen_dns=1 ;;
    esac
  done < "${changed_paths_file}"
  (( seen_train == 1 )) && actions+=("restart_component train_bot")
  (( seen_satiksme == 1 )) && actions+=("restart_component satiksme_bot")
  (( seen_site == 1 )) && actions+=("restart_component site_notifier")
  (( seen_subscription == 1 )) && actions+=("restart_component subscription_bot")
  (( seen_ssh == 1 )) && actions+=("restart_component ssh")
  (( seen_vpn == 1 )) && actions+=("restart_component vpn")
  (( seen_ddns == 1 )) && actions+=("sync_ddns")
  (( seen_dns == 1 )) && actions+=("restart_component dns")
  printf '%s\n' "${actions[@]}"
}

pixel_mirror_deploy_config() {
  local changed_paths_file=""
  local action_line=""
  local action_name=""
  local component_name=""
  local -a deploy_args=()
  changed_paths_file="$(mktemp "${TMPDIR:-/tmp}/pixel-host-mirror-changed.XXXXXX")"
  trap 'rm -f "${changed_paths_file}"' RETURN
  pixel_mirror_run_helper push "${changed_paths_file}"
  pixel_mirror_apply_changed_paths "${changed_paths_file}"
  if [[ ! -s "${changed_paths_file}" ]]; then
    log "Deploy config: mirror is already in sync; no components need restart"
    return 0
  fi
  while IFS= read -r action_line; do
    [[ -n "${action_line}" ]] || continue
    action_name="${action_line%% *}"
    component_name="${action_line#* }"
    if [[ "${action_name}" == "sync_ddns" ]]; then
      deploy_args=("${DEPLOY_SCRIPT}" --skip-build --action sync_ddns)
    else
      deploy_args=("${DEPLOY_SCRIPT}" --skip-build --action "${action_name}" --component "${component_name}")
    fi
    pixel_transport_append_cli_args deploy_args
    "${deploy_args[@]}"
  done < <(pixel_mirror_affected_actions "${changed_paths_file}")
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    mirror-pull|mirror-audit|mirror-push|deploy-config)
      if [[ -n "${MIRROR_ACTION}" ]]; then
        echo "Only one mirror action is allowed" >&2
        exit 2
      fi
      MIRROR_ACTION="$1"
      ;;
    --scope)
      shift
      SCOPE="${1:-}"
      ;;
    --mode)
      shift
      MODE="${1:-}"
      ;;
    --rootfs-tarball)
      shift
      ROOTFS_TARBALL="${1:-}"
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --destructive-e2e)
      DESTRUCTIVE_E2E=1
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

if [[ -n "${MIRROR_ACTION}" ]]; then
  require_cmd python3
  require_cmd tar
  pixel_transport_require_device >/dev/null
  case "${MIRROR_ACTION}" in
    mirror-pull)
      pixel_mirror_run_helper pull
      ;;
    mirror-audit)
      pixel_mirror_run_helper audit
      ;;
    mirror-push)
      changed_paths_file="$(mktemp "${TMPDIR:-/tmp}/pixel-host-mirror-changed.XXXXXX")"
      trap 'rm -f "${changed_paths_file}"' EXIT
      pixel_mirror_run_helper push "${changed_paths_file}"
      pixel_mirror_apply_changed_paths "${changed_paths_file}"
      ;;
    deploy-config)
      pixel_mirror_deploy_config
      ;;
  esac
  exit 0
fi

case "${SCOPE}" in
  full|platform|dns|train_bot|satiksme_bot|site_notifier|subscription_bot) ;;
  *)
    echo "Unsupported --scope: ${SCOPE}" >&2
    exit 2
    ;;
esac

case "${MODE}" in
  auto|force-bootstrap|force-refresh|validate-only) ;;
  *)
    echo "Unsupported --mode: ${MODE}" >&2
    exit 2
    ;;
esac

bash "${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh"
mkdir -p "${REPORT_DIR}"
exec > >(tee "${REPORT_LOG}") 2>&1

require_cmd python3
require_cmd curl
require_cmd bash

pixel_transport_require_device >/dev/null
adb_cmd=(pixel_transport_adb_compat)
"${adb_cmd[@]}" get-state >/dev/null

remote_sha256_file() {
  local remote_path="$1"
  pixel_transport_remote_sha256_file "${remote_path}"
}

remote_file_exists() {
  local remote_path="$1"
  pixel_transport_remote_file_exists "${remote_path}"
}

load_remote_json_file() {
  local remote_path="$1"
  if ! remote_file_exists "${remote_path}"; then
    return 1
  fi
  pixel_transport_remote_cat "${remote_path}"
}

pull_remote_file() {
  local remote_path="$1"
  local local_path="$2"
  pixel_transport_pull "${remote_path}" "${local_path}"
}

component_runtime_name() {
  case "${1}" in
    train_bot) printf 'train-bot\n' ;;
    satiksme_bot) printf 'satiksme-bot\n' ;;
    site_notifier) printf 'site-notifications\n' ;;
    subscription_bot) printf 'subscription-bot\n' ;;
    *) printf '%s\n' "${1}" ;;
  esac
}

component_expected_release_id() {
  local manifest_path="$1/release-manifest.json"
  python3 - "${manifest_path}" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as fh:
    payload = json.load(fh)
print((payload.get("releaseId") or "").strip())
PY
}

component_live_release_path() {
  local runtime_name=""
  runtime_name="$(component_runtime_name "${1}")"
  "${adb_cmd[@]}" shell "su -c 'readlink /data/local/pixel-stack/apps/${runtime_name}/current 2>/dev/null || readlink /data/local/pixel-stack/apps/${runtime_name}.current 2>/dev/null || true'" | tr -d '\r'
}

action_result_remote_path() {
  local action="$1"
  local component="$2"
  local component_key="${component:-all}"
  printf '/data/local/pixel-stack/run/orchestrator-action-results/%s--%s--%s.json\n' "${PIXEL_RUN_ID}" "${action}" "${component_key}"
}

json_field() {
  local payload="$1"
  local field_name="$2"
  JSON_PAYLOAD="${payload}" python3 - "${field_name}" <<'PY'
import json
import os
import sys
field = sys.argv[1]
payload = json.loads(os.environ["JSON_PAYLOAD"])
value = payload.get(field, "")
if isinstance(value, bool):
    print("true" if value else "false")
elif value is None:
    print("")
else:
    print(value)
PY
}

json_payload_valid() {
  local payload="$1"
  JSON_PAYLOAD="${payload}" python3 - <<'PY' >/dev/null 2>&1
import json
import os

payload = os.environ.get("JSON_PAYLOAD", "").strip()
if not payload:
    raise SystemExit(1)
json.loads(payload)
PY
}

scope_includes_platform() {
  [[ "${SCOPE}" == "full" || "${SCOPE}" == "platform" || "${SCOPE}" == "dns" ]]
}

scope_is_dns_only() {
  [[ "${SCOPE}" == "dns" ]]
}

scope_requires_runtime_bundle() {
  [[ "${SCOPE}" == "full" || "${SCOPE}" == "platform" ]]
}

if scope_includes_platform && [[ -z "${ROOTFS_TARBALL}" ]]; then
  echo "Missing rootfs tarball for ${SCOPE} scope. Pass --rootfs-tarball or set PIXEL_RUNTIME_ROOTFS_TARBALL." >&2
  exit 2
fi

scope_includes_train() {
  [[ "${SCOPE}" == "full" || "${SCOPE}" == "train_bot" ]]
}

scope_includes_satiksme() {
  [[ "${SCOPE}" == "full" || "${SCOPE}" == "satiksme_bot" ]]
}

scope_includes_site() {
  [[ "${SCOPE}" == "full" || "${SCOPE}" == "site_notifier" ]]
}

scope_includes_subscription() {
  [[ "${SCOPE}" == "full" || "${SCOPE}" == "subscription_bot" ]]
}

preflight_satiksme_schema_publish() {
  if ! scope_includes_satiksme || [[ "${MODE}" == "validate-only" ]]; then
    return 0
  fi
  if [[ ! -x "${SATIKSME_SPACETIME_PUBLISH_SCRIPT}" ]]; then
    echo "Satiksme schema publish preflight failed: missing ${SATIKSME_SPACETIME_PUBLISH_SCRIPT}" >&2
    exit 1
  fi
  if ! command -v spacetime >/dev/null 2>&1; then
    echo "Satiksme schema publish preflight failed: local spacetime CLI is required" >&2
    exit 1
  fi
}

add_optional_arg() {
  local ref_name="$1"
  local flag="$2"
  local path="$3"
  [[ -n "${path}" && -f "${path}" ]] || return 0
  # macOS still ships bash 3.2, so build the target array without namerefs.
  eval "${ref_name}+=(\"\${flag}\" \"\${path}\")"
}

deploy_base_args() {
  local -a args=()
  pixel_transport_append_cli_args args
  if (( SKIP_BUILD == 1 || APK_INSTALL_PENDING == 0 )); then
    args+=(--skip-build)
  fi
  printf '%s\n' "${args[@]}"
}

runtime_freshness_args() {
  local args=()
  pixel_transport_append_cli_args args
  printf '%s\n' "${args[@]}"
}

transport_cli_args_string() {
  local args=()
  pixel_transport_append_cli_args args
  if (( ${#args[@]} == 0 )); then
    return 0
  fi
  printf '%q ' "${args[@]}"
}

selected_target_label() {
  if [[ "$(pixel_transport_selected)" == "adb" ]]; then
    printf '%s\n' "${ADB_SERIAL}"
  else
    printf '%s:%s\n' "${PIXEL_SSH_HOST}" "${PIXEL_SSH_PORT}"
  fi
}

run_deploy() {
  local -a cmd=("${DEPLOY_SCRIPT}")
  local line=""
  local capture_file=""
  local rc=0
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(deploy_base_args)
  cmd+=("$@")
  log "Running orchestrator deploy: ${cmd[*]}"
  capture_file="$(mktemp "${REPORT_DIR}/run-deploy.XXXXXX")"
  set +e
  "${cmd[@]}" 2>&1 | tee "${capture_file}"
  rc=${PIPESTATUS[0]}
  set -e
  if (( rc == 0 && SKIP_BUILD == 0 && APK_INSTALL_PENDING == 1 )); then
    APK_INSTALL_PENDING=0
  fi
  LAST_DEPLOY_RESULT_SOURCE="$(sed -n 's/^Action result source: //p' "${capture_file}" | tail -n1)"
  if [[ -z "${LAST_DEPLOY_RESULT_SOURCE}" ]]; then
    LAST_DEPLOY_RESULT_SOURCE="none"
  fi
  return "${rc}"
}

build_orchestrator_if_needed() {
  if (( SKIP_BUILD == 1 )); then
    log "Skipping orchestrator APK build"
    return 0
  fi
  record_action "build_orchestrator_apk"
  "${BUILD_SCRIPT}"
  APK_INSTALL_PENDING=1
}

package_train_release() {
  local output=""
  local -a cmd=("${TRAIN_DEPLOY_SCRIPT}" --skip-build --package-only)
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(deploy_base_args)
  output="$("${cmd[@]}" 2>&1)"
  printf '%s\n' "${output}"
  TRAIN_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | grep -Eo '/[^[:space:]]+/\.artifacts/component-releases/train_bot-[^[:space:]]+' | tail -n 1)"
  if [[ -z "${TRAIN_BOT_RELEASE_DIR}" ]]; then
    TRAIN_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | awk -F= '/^TRAIN_BOT_RELEASE_DIR=/{print $2}' | tail -n 1)"
  fi
  [[ -n "${TRAIN_BOT_RELEASE_DIR}" && -d "${TRAIN_BOT_RELEASE_DIR}" ]] || {
    echo "Failed to resolve Train Bot release dir from package-only output" >&2
    exit 1
  }
  TRAIN_BOT_BUNDLE_PATH="$(find "${TRAIN_BOT_RELEASE_DIR}/artifacts" -maxdepth 1 -type f -name 'train-bot-bundle*.tar' | sort | tail -n 1)"
  [[ -n "${TRAIN_BOT_BUNDLE_PATH}" && -f "${TRAIN_BOT_BUNDLE_PATH}" ]] || {
    echo "Train Bot release dir is missing a train-bot bundle: ${TRAIN_BOT_RELEASE_DIR}" >&2
    exit 1
  }
}

package_satiksme_release() {
  local output=""
  output="$(ORCHESTRATOR_REPO="${ORCHESTRATOR_ROOT}" "${SATIKSME_PREPARE_RELEASE_SCRIPT}" 2>&1)"
  printf '%s\n' "${output}"
  SATIKSME_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | grep -Eo '/[^[:space:]]+/\.artifacts/component-releases/satiksme_bot-[^[:space:]]+' | tail -n 1)"
  if [[ -z "${SATIKSME_BOT_RELEASE_DIR}" ]]; then
    SATIKSME_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | awk -F= '/^SATIKSME_BOT_RELEASE_DIR=/{print $2}' | tail -n 1)"
  fi
  if [[ -z "${SATIKSME_BOT_RELEASE_DIR}" ]]; then
    SATIKSME_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | tail -n 1 | tr -d '\r')"
  fi
  [[ -n "${SATIKSME_BOT_RELEASE_DIR}" && -d "${SATIKSME_BOT_RELEASE_DIR}" ]] || {
    echo "Failed to resolve Satiksme Bot release dir from package-only output" >&2
    exit 1
  }
  SATIKSME_BOT_BUNDLE_PATH="$(find "${SATIKSME_BOT_RELEASE_DIR}/artifacts" -maxdepth 1 -type f -name 'satiksme-bot-bundle*.tar' | sort | tail -n 1)"
  [[ -n "${SATIKSME_BOT_BUNDLE_PATH}" && -f "${SATIKSME_BOT_BUNDLE_PATH}" ]] || {
    echo "Satiksme Bot release dir is missing a satiksme-bot bundle: ${SATIKSME_BOT_RELEASE_DIR}" >&2
    exit 1
  }
}

package_site_release() {
  local output=""
  local -a cmd=("${SITE_DEPLOY_SCRIPT}" --skip-build --package-only)
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(deploy_base_args)
  output="$("${cmd[@]}" 2>&1)"
  printf '%s\n' "${output}"
  SITE_NOTIFIER_RELEASE_DIR="$(printf '%s\n' "${output}" | grep -Eo '/[^[:space:]]+/\.artifacts/component-releases/site_notifier-[^[:space:]]+' | tail -n 1)"
  if [[ -z "${SITE_NOTIFIER_RELEASE_DIR}" ]]; then
    SITE_NOTIFIER_RELEASE_DIR="$(printf '%s\n' "${output}" | awk -F= '/^SITE_NOTIFIER_RELEASE_DIR=/{print $2}' | tail -n 1)"
  fi
  if [[ -z "${SITE_NOTIFIER_RELEASE_DIR}" ]]; then
    SITE_NOTIFIER_RELEASE_DIR="$(printf '%s\n' "${output}" | tail -n 1 | tr -d '\r')"
  fi
  [[ -n "${SITE_NOTIFIER_RELEASE_DIR}" && -d "${SITE_NOTIFIER_RELEASE_DIR}" ]] || {
    echo "Failed to resolve Site Notifier release dir from package-only output" >&2
    exit 1
  }
  SITE_NOTIFIER_BUNDLE_PATH="$(find "${SITE_NOTIFIER_RELEASE_DIR}/artifacts" -maxdepth 1 -type f -name 'site-notifier-bundle*.tar' | sort | tail -n 1)"
  [[ -n "${SITE_NOTIFIER_BUNDLE_PATH}" && -f "${SITE_NOTIFIER_BUNDLE_PATH}" ]] || {
    echo "Site Notifier release dir is missing a site-notifier bundle: ${SITE_NOTIFIER_RELEASE_DIR}" >&2
    exit 1
  }
}

package_subscription_release() {
  local output=""
  local -a cmd=("${SUBSCRIPTION_DEPLOY_SCRIPT}" --skip-build --package-only)
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(deploy_base_args)
  output="$("${cmd[@]}" 2>&1)"
  printf '%s\n' "${output}"
  SUBSCRIPTION_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | grep -Eo '/[^[:space:]]+/\.artifacts/component-releases/subscription_bot-[^[:space:]]+' | tail -n 1)"
  if [[ -z "${SUBSCRIPTION_BOT_RELEASE_DIR}" ]]; then
    SUBSCRIPTION_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | awk -F= '/^SUBSCRIPTION_BOT_RELEASE_DIR=/{print $2}' | tail -n 1)"
  fi
  if [[ -z "${SUBSCRIPTION_BOT_RELEASE_DIR}" ]]; then
    SUBSCRIPTION_BOT_RELEASE_DIR="$(printf '%s\n' "${output}" | tail -n 1 | tr -d '\r')"
  fi
  [[ -n "${SUBSCRIPTION_BOT_RELEASE_DIR}" && -d "${SUBSCRIPTION_BOT_RELEASE_DIR}" ]] || {
    echo "Failed to resolve Subscription Bot release dir from package-only output" >&2
    exit 1
  }
  SUBSCRIPTION_BOT_BUNDLE_PATH="$(find "${SUBSCRIPTION_BOT_RELEASE_DIR}/artifacts" -maxdepth 1 -type f -name 'subscription-bot-bundle*.tar' | sort | tail -n 1)"
  [[ -n "${SUBSCRIPTION_BOT_BUNDLE_PATH}" && -f "${SUBSCRIPTION_BOT_BUNDLE_PATH}" ]] || {
    echo "Subscription Bot release dir is missing a subscription-bot bundle: ${SUBSCRIPTION_BOT_RELEASE_DIR}" >&2
    exit 1
  }
}

publish_satiksme_schema() {
  local rc=0

  record_action "publish_satiksme_schema"
  log "Publishing Satiksme Spacetime schema"
  set +e
  "${SATIKSME_SPACETIME_PUBLISH_SCRIPT}" >"${SATIKSME_SCHEMA_PUBLISH_LOG}" 2>&1
  rc=$?
  set -e
  if (( rc == 0 )); then
    record_validation "satiksme_schema_publish" "pass"
    log "Satiksme Spacetime schema publish complete"
    return 0
  fi

  record_validation "satiksme_schema_publish" "fail"
  echo "Satiksme schema publish failed; see ${SATIKSME_SCHEMA_PUBLISH_LOG}" >&2
  exit "${rc}"
}

package_runtime_bundle() {
  local manifest_version="pixel-redeploy-${PIXEL_RUN_ID}"
  local artifact_dir="${WORKSPACE_ROOT}/.artifacts/runtime-local/${manifest_version}"
  local -a cmd=("${PACKAGE_RUNTIME_SCRIPT}" --rootfs-tarball "${ROOTFS_TARBALL}" --manifest-version "${manifest_version}" --out-dir "${artifact_dir}")
  if scope_includes_train; then
    [[ -n "${TRAIN_BOT_BUNDLE_PATH}" ]] && cmd+=(--train-bot-bundle "${TRAIN_BOT_BUNDLE_PATH}")
  fi
  if scope_includes_satiksme; then
    [[ -n "${SATIKSME_BOT_BUNDLE_PATH}" ]] && cmd+=(--satiksme-bot-bundle "${SATIKSME_BOT_BUNDLE_PATH}")
  fi
  if scope_includes_site; then
    [[ -n "${SITE_NOTIFIER_BUNDLE_PATH}" ]] && cmd+=(--site-notifier-bundle "${SITE_NOTIFIER_BUNDLE_PATH}")
  fi
  if scope_includes_subscription; then
    [[ -n "${SUBSCRIPTION_BOT_BUNDLE_PATH}" ]] && cmd+=(--subscription-bot-bundle "${SUBSCRIPTION_BOT_BUNDLE_PATH}")
  fi
  if ! scope_includes_train && ! scope_includes_satiksme && ! scope_includes_site && ! scope_includes_subscription; then
    cmd+=(--platform-only)
  fi
  record_action "package_runtime_bundle"
  "${cmd[@]}"
  RUNTIME_BUNDLE_DIR="${artifact_dir}"
  RUNTIME_MANIFEST_PATH="${artifact_dir}/runtime-manifest.json"
  ROOTFS_SOURCE="${ROOTFS_TARBALL}"
}

package_dns_release() {
  local release_id="dns-${PIXEL_RUN_ID}"
  local release_dir="${ORCHESTRATOR_ROOT}/.artifacts/component-releases/${release_id}"
  local -a cmd=("${PACKAGE_DNS_COMPONENT_RELEASE_SCRIPT}" --rootfs-tarball "${ROOTFS_TARBALL}" --release-id "${release_id}" --out-dir "${release_dir}")
  record_action "package_dns_release"
  "${cmd[@]}"
  DNS_RELEASE_DIR="${release_dir}"
  ROOTFS_SOURCE="${ROOTFS_TARBALL}"
}

compare_manifest_subset() {
  local local_manifest="$1"
  local remote_manifest="$2"
  local artifact_ids_csv="$3"
  python3 - "${local_manifest}" "${remote_manifest}" "${artifact_ids_csv}" <<'PY'
import json
import sys

ids = {item.strip() for item in sys.argv[3].split(",") if item.strip()}
with open(sys.argv[1], "r", encoding="utf-8") as fh:
    local_manifest = json.load(fh)
with open(sys.argv[2], "r", encoding="utf-8") as fh:
    remote_manifest = json.load(fh)

def subset(payload):
    result = {}
    for artifact in payload.get("artifacts") or []:
        artifact_id = (artifact.get("id") or "").strip()
        if artifact_id in ids:
            result[artifact_id] = artifact.get("sha256")
    return result

print("match" if subset(local_manifest) == subset(remote_manifest) else "mismatch")
PY
}

run_rooted_freshness_check() {
  local output_path="$1"
  local freshness_output=""
  local freshness_rc=0
  local -a cmd=("${RUNTIME_FRESHNESS_SCRIPT}")
  local line=""

  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(runtime_freshness_args)
  cmd+=(--scope rooted)
  freshness_output="$("${cmd[@]}" 2>&1)" || freshness_rc=$?
  printf '%s\n' "${freshness_output}" > "${output_path}"
  case "${freshness_rc}" in
    0)
      return 0
      ;;
    3)
      return 3
      ;;
    *)
      echo "runtime_asset_freshness.sh failed while checking rooted assets:" >&2
      printf '%s\n' "${freshness_output}" >&2
      exit 1
      ;;
  esac
}

preflight_rooted_freshness() {
  local rc=0
  run_rooted_freshness_check "${ROOTED_FRESHNESS_PRE_REPORT}" || rc=$?
  case "${rc}" in
    0)
      PREFLIGHT_ROOTED_FRESHNESS="fresh"
      ROOTED_STALE=0
      ;;
    3)
      PREFLIGHT_ROOTED_FRESHNESS="stale"
      ROOTED_STALE=1
      DNS_RELEASE_NEEDED=1
      ROOTED_CONVERGENCE_REQUIRED=1
      ;;
  esac
}

post_deploy_rooted_freshness() {
  local rc=0
  run_rooted_freshness_check "${ROOTED_FRESHNESS_POST_REPORT}" || rc=$?
  FINAL_ROOTED_FRESHNESS_REPORT="${ROOTED_FRESHNESS_POST_REPORT}"
  LIVE_DNS_RUNTIME_CONVERGED=0
  if scope_includes_platform && verify_live_dns_runtime; then
    LIVE_DNS_RUNTIME_CONVERGED=1
  fi
  case "${rc}" in
    0)
      FINAL_ROOTED_FRESHNESS="fresh"
      ;;
    3)
      FINAL_ROOTED_FRESHNESS="stale"
      echo "Rooted runtime freshness is still stale after platform mutation; see ${ROOTED_FRESHNESS_POST_REPORT}" >&2
      exit 1
      ;;
  esac
}

compute_platform_bootstrap_need() {
  local remote_manifest_tmp=""
  local remote_dns_manifest_tmp=""
  local compare_result=""

  BOOTSTRAP_NEEDED=0
  ROOTED_STALE=0
  CONFIG_CHANGED=0
  DDNS_TOKEN_CHANGED=0
  PLATFORM_ARTIFACTS_CHANGED=0
  DNS_ARTIFACTS_CHANGED=0
  DNS_RELEASE_NEEDED=0
  ROOTED_CONVERGENCE_REQUIRED=0
  REPAIR_ACTION="none"

  if ! remote_file_exists "/data/local/pixel-stack/conf/runtime/runtime-manifest.json"; then
    BOOTSTRAP_NEEDED=1
  fi

  if [[ -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    local_sha="$(sha256_file "${ORCHESTRATOR_CONFIG_FILE}")"
    remote_sha="$(remote_sha256_file "/data/local/pixel-stack/conf/orchestrator-config-v1.json")"
    if [[ "${remote_sha}" != "${local_sha}" ]]; then
      CONFIG_CHANGED=1
      BOOTSTRAP_NEEDED=1
    fi
  fi

  if [[ -f "${DDNS_TOKEN_FILE}" ]]; then
    local_sha="$(sha256_file "${DDNS_TOKEN_FILE}")"
    remote_sha="$(remote_sha256_file "/data/local/pixel-stack/conf/ddns/cloudflare-token")"
    if [[ "${remote_sha}" != "${local_sha}" ]]; then
      DDNS_TOKEN_CHANGED=1
      BOOTSTRAP_NEEDED=1
    fi
  fi

  preflight_rooted_freshness

  if ! scope_is_dns_only && remote_file_exists "/data/local/pixel-stack/conf/runtime/runtime-manifest.json"; then
    remote_manifest_tmp="$(mktemp)"
    pull_remote_file "/data/local/pixel-stack/conf/runtime/runtime-manifest.json" "${remote_manifest_tmp}"
    compare_result="$(compare_manifest_subset "${RUNTIME_MANIFEST_PATH}" "${remote_manifest_tmp}" "dropbear-bundle,tailscale-bundle")"
    rm -f "${remote_manifest_tmp}"
    if [[ "${compare_result}" != "match" ]]; then
      PLATFORM_ARTIFACTS_CHANGED=1
      BOOTSTRAP_NEEDED=1
    fi
  fi

  if [[ -n "${DNS_RELEASE_DIR}" ]] && remote_file_exists "/data/local/pixel-stack/conf/runtime/components/dns/release-manifest.json"; then
    remote_dns_manifest_tmp="$(mktemp)"
    pull_remote_file "/data/local/pixel-stack/conf/runtime/components/dns/release-manifest.json" "${remote_dns_manifest_tmp}"
    compare_result="$(compare_manifest_subset "${DNS_RELEASE_DIR}/release-manifest.json" "${remote_dns_manifest_tmp}" "adguardhome-rootfs,dns-runtime-assets")"
    rm -f "${remote_dns_manifest_tmp}"
    if [[ "${compare_result}" != "match" ]]; then
      DNS_ARTIFACTS_CHANGED=1
      DNS_RELEASE_NEEDED=1
    fi
  elif remote_file_exists "/data/local/pixel-stack/conf/runtime/runtime-manifest.json"; then
    remote_manifest_tmp="$(mktemp)"
    pull_remote_file "/data/local/pixel-stack/conf/runtime/runtime-manifest.json" "${remote_manifest_tmp}"
    compare_result="$(compare_manifest_subset "${RUNTIME_MANIFEST_PATH}" "${remote_manifest_tmp}" "adguardhome-rootfs,dns-runtime-assets")"
    rm -f "${remote_manifest_tmp}"
    if [[ "${compare_result}" != "match" ]]; then
      DNS_ARTIFACTS_CHANGED=1
      DNS_RELEASE_NEEDED=1
    fi
  elif scope_includes_platform; then
    DNS_RELEASE_NEEDED=1
  fi

  if scope_includes_platform && ! verify_live_dns_runtime; then
    DNS_RELEASE_NEEDED=1
    ROOTED_CONVERGENCE_REQUIRED=1
  fi

  if (( BOOTSTRAP_NEEDED == 1 )); then
    REPAIR_ACTION="bootstrap"
  elif (( DNS_RELEASE_NEEDED == 1 )); then
    REPAIR_ACTION="dns_component_redeploy"
  else
    REPAIR_ACTION="none"
  fi
}

emit_plan() {
  {
    printf 'transport=%s\n' "$(pixel_transport_selected)"
    printf 'target=%s\n' "$(selected_target_label)"
    printf 'scope=%s\n' "${SCOPE}"
    printf 'mode=%s\n' "${MODE}"
    printf 'runtime_bundle_dir=%s\n' "${RUNTIME_BUNDLE_DIR:-none}"
    printf 'train_release_dir=%s\n' "${TRAIN_BOT_RELEASE_DIR:-none}"
    printf 'satiksme_release_dir=%s\n' "${SATIKSME_BOT_RELEASE_DIR:-none}"
    printf 'site_release_dir=%s\n' "${SITE_NOTIFIER_RELEASE_DIR:-none}"
    printf 'subscription_release_dir=%s\n' "${SUBSCRIPTION_BOT_RELEASE_DIR:-none}"
    printf 'dns_release_dir=%s\n' "${DNS_RELEASE_DIR:-none}"
    printf 'rootfs_source=%s\n' "${ROOTFS_SOURCE:-none}"
    printf 'bootstrap_needed=%s\n' "${BOOTSTRAP_NEEDED}"
    printf 'dns_release_needed=%s\n' "${DNS_RELEASE_NEEDED}"
    printf 'rooted_stale=%s\n' "${ROOTED_STALE}"
    printf 'config_changed=%s\n' "${CONFIG_CHANGED}"
    printf 'ddns_token_changed=%s\n' "${DDNS_TOKEN_CHANGED}"
    printf 'platform_artifacts_changed=%s\n' "${PLATFORM_ARTIFACTS_CHANGED}"
    printf 'dns_artifacts_changed=%s\n' "${DNS_ARTIFACTS_CHANGED}"
    printf 'repair_action=%s\n' "${REPAIR_ACTION}"
  } > "${PLAN_TEXT}"

  log "Deploy plan"
  sed 's/^/  /' "${PLAN_TEXT}"
}

verify_live_dns_runtime() {
  local expected_hash=""
  local live_hash=""
  expected_hash="$(sha256_file "${ORCHESTRATOR_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start")"
  live_hash="$(remote_sha256_file "/data/local/pixel-stack/chroots/adguardhome/usr/local/bin/adguardhome-start")"
  [[ "${live_hash}" == "${expected_hash}" ]] || return 1
  "${adb_cmd[@]}" shell "su -c 'ss -ltn 2>/dev/null | grep -Eq \"[.:]53[[:space:]]\" && ss -ltn 2>/dev/null | grep -Eq \"127\\.0\\.0\\.1:8080[[:space:]]\" && chroot /data/local/pixel-stack/chroots/adguardhome /usr/local/bin/adguardhome-start --remote-healthcheck >/dev/null 2>&1'" >/dev/null 2>&1
}

run_platform_bootstrap() {
  local -a cmd=(--runtime-bundle-dir "${RUNTIME_BUNDLE_DIR}" --action bootstrap)
  add_optional_arg cmd --component-release-dir "${DNS_RELEASE_DIR}"
  add_optional_arg cmd --config-file "${ORCHESTRATOR_CONFIG_FILE}"
  add_optional_arg cmd --train-bot-env-file "${TRAIN_BOT_ENV_FILE}"
  add_optional_arg cmd --satiksme-bot-env-file "${SATIKSME_BOT_ENV_FILE}"
  add_optional_arg cmd --site-notifier-env-file "${SITE_NOTIFIER_ENV_FILE}"
  add_optional_arg cmd --subscription-bot-env-file "${SUBSCRIPTION_BOT_ENV_FILE}"
  add_optional_arg cmd --ddns-token-file "${DDNS_TOKEN_FILE}"
  add_optional_arg cmd --ssh-public-key "${SSH_PUBLIC_KEY_FILE}"
  add_optional_arg cmd --ssh-password-hash-file "${SSH_PASSWORD_HASH_FILE}"
  add_optional_arg cmd --admin-password-file "${ADMIN_PASSWORD_FILE}"
  add_optional_arg cmd --acme-token-file "${ACME_TOKEN_FILE}"
  add_optional_arg cmd --vpn-auth-key-file "${VPN_AUTH_KEY_FILE}"
  add_optional_arg cmd --ipinfo-lite-token-file "${IPINFO_LITE_TOKEN_FILE}"
  record_action "bootstrap"
  run_deploy "${cmd[@]}"
}

run_component_redeploy() {
  local component="$1"
  local release_dir="$2"
  local env_flag="$3"
  local env_file="${4:-}"
  local -a cmd=(--component-release-dir "${release_dir}" --action redeploy_component --component "${component}")
  local expected_release_id=""
  local live_release_path=""
  local recovery_command=""
  local action_result_json=""
  local action_result_path=""
  local rc=0
  add_optional_arg cmd "${env_flag}" "${env_file}"
  record_action "redeploy_component_${component}"
  expected_release_id="$(component_expected_release_id "${release_dir}")"
  recovery_command="${WORKSPACE_ROOT}/tools/pixel/redeploy.sh $(transport_cli_args_string)--scope ${component} --mode auto"
  if run_deploy "${cmd[@]}"; then
    rc=0
  else
    rc=$?
  fi

  action_result_path="$(action_result_remote_path "redeploy_component" "${component}")"
  if action_result_json="$(load_remote_json_file "${action_result_path}")" && json_payload_valid "${action_result_json}"; then
    LAST_DEPLOY_RESULT_SOURCE="artifact"
    if [[ "$(json_field "${action_result_json}" "success")" != "true" ]]; then
      rc=1
    fi
  elif [[ "${LAST_DEPLOY_RESULT_SOURCE}" == "none" ]]; then
    live_release_path="$(component_live_release_path "${component}")"
    if [[ -n "${expected_release_id}" && "${live_release_path}" == *"${expected_release_id}"* ]]; then
      LAST_DEPLOY_RESULT_SOURCE="verification-fallback"
    else
      LAST_DEPLOY_RESULT_SOURCE="log"
    fi
  fi

  live_release_path="${live_release_path:-$(component_live_release_path "${component}")}"
  case "${component}" in
    train_bot)
      TRAIN_BOT_DEPLOY_RESULT_SOURCE="${LAST_DEPLOY_RESULT_SOURCE}"
      TRAIN_BOT_LIVE_RELEASE_PATH="${live_release_path}"
      TRAIN_BOT_RECOVERY_COMMAND="${recovery_command}"
      ;;
    satiksme_bot)
      SATIKSME_BOT_DEPLOY_RESULT_SOURCE="${LAST_DEPLOY_RESULT_SOURCE}"
      SATIKSME_BOT_LIVE_RELEASE_PATH="${live_release_path}"
      SATIKSME_BOT_RECOVERY_COMMAND="${recovery_command}"
      ;;
    site_notifier)
      SITE_NOTIFIER_DEPLOY_RESULT_SOURCE="${LAST_DEPLOY_RESULT_SOURCE}"
      SITE_NOTIFIER_LIVE_RELEASE_PATH="${live_release_path}"
      SITE_NOTIFIER_RECOVERY_COMMAND="${recovery_command}"
      ;;
    subscription_bot)
      SUBSCRIPTION_BOT_DEPLOY_RESULT_SOURCE="${LAST_DEPLOY_RESULT_SOURCE}"
      SUBSCRIPTION_BOT_LIVE_RELEASE_PATH="${live_release_path}"
      SUBSCRIPTION_BOT_RECOVERY_COMMAND="${recovery_command}"
      ;;
  esac

  {
    printf 'action=redeploy_component\n'
    printf 'component=%s\n' "${component}"
    printf 'releaseDir=%s\n' "${release_dir}"
    printf 'releaseId=%s\n' "${expected_release_id}"
    printf 'resultSource=%s\n' "${LAST_DEPLOY_RESULT_SOURCE}"
    printf 'liveReleasePath=%s\n' "${live_release_path}"
    printf 'recoveryCommand=%s\n' "${recovery_command}"
  } > "${REPORT_DIR}/${component}-redeploy-summary.txt"

  if (( rc != 0 )); then
    echo "${component} redeploy failed; see ${REPORT_DIR}/${component}-redeploy-summary.txt" >&2
    exit "${rc}"
  fi
}

run_deploy_health_check() {
  local name="$1"
  shift
  if "$@"; then
    record_validation "${name}" "pass"
    return 0
  fi
  record_validation "${name}" "fail"
  return 1
}

http_code() {
  local url="$1"
  curl -ksS -o /dev/null -w '%{http_code}' --max-time 15 "${url}" 2>/dev/null || true
}

http_code_reachable_non_route_miss() {
  local code="$1"
  case "${code}" in
    2??|3??|400|401|403|405) return 0 ;;
    *) return 1 ;;
  esac
}

dns_contract_config_value() {
  local field_path="$1"
  local default_value="${2:-}"
  python3 - "${ORCHESTRATOR_CONFIG_FILE}" "${field_path}" "${default_value}" <<'PY'
import json
import sys

config_path = sys.argv[1]
field_path = sys.argv[2]
default_value = sys.argv[3]

with open(config_path, "r", encoding="utf-8") as fh:
    payload = json.load(fh)

value = payload
for part in field_path.split("."):
    if not isinstance(value, dict):
        value = None
        break
    value = value.get(part)

value = "" if value is None else str(value).strip()
print(value or default_value)
PY
}

dns_contract_enabled() {
  local dns_enabled=""
  local remote_enabled=""

  dns_enabled="$(dns_contract_config_value "modules.dns.enabled" "true")"
  remote_enabled="$(dns_contract_config_value "modules.remote.enabled" "true")"

  case "${dns_enabled},${remote_enabled}" in
    true,true|1,1|true,1|1,true) return 0 ;;
    *) return 1 ;;
  esac
}

dns_contract_probe_available() {
  local code=""
  for code in "$@"; do
    case "${code}" in
      ""|000|skipped|disabled) ;;
      *) return 0 ;;
    esac
  done
  return 1
}

dns_contract_codes_valid() {
  local admin_code="$1"
  local bare_code="$2"
  local tokenized_code="$3"
  local remote_token="$4"

  [[ "${bare_code}" == "404" ]] || return 1
  case "${admin_code}" in
    200|302|401) ;;
    *) return 1 ;;
  esac

  if [[ -z "${remote_token}" ]]; then
    return 0
  fi

  http_code_reachable_non_route_miss "${tokenized_code}"
}

dns_contract_output_field() {
  local output="$1"
  local field_name="$2"
  awk -F= -v key="${field_name}" '$1 == key {sub(/^[^=]*=/, "", $0); print; exit}' <<<"${output}"
}

dns_contract_local_probe_ready() {
  local admin_code="$1"
  local bare_code="$2"
  local tokenized_code="$3"
  local remote_token="$4"
  local overall="$5"

  dns_contract_codes_valid "${admin_code}" "${bare_code}" "${tokenized_code}" "${remote_token}" || return 1
  case "${overall:-}" in
    ""|ok|unknown) ;;
    *) return 1 ;;
  esac
}

probe_local_dns_contract() {
  local debug_output=""
  local local_admin_code=""
  local local_bare_code=""
  local local_tokenized_code=""
  local local_overall=""

  debug_output="$("${adb_cmd[@]}" shell "su -c 'chroot /data/local/pixel-stack/chroots/adguardhome /usr/local/bin/adguardhome-start --remote-healthcheck-debug || true'" 2>/dev/null | tr -d '\r' || true)"
  [[ -n "${debug_output}" ]] || return 1

  local_admin_code="$(dns_contract_output_field "${debug_output}" "remote_admin_code")"
  local_bare_code="$(dns_contract_output_field "${debug_output}" "doh_bare_code")"
  local_tokenized_code="$(dns_contract_output_field "${debug_output}" "doh_tokenized_code")"
  local_overall="$(dns_contract_output_field "${debug_output}" "remote_healthcheck")"

  [[ -n "${local_admin_code}" || -n "${local_bare_code}" || -n "${local_tokenized_code}" || -n "${local_overall}" ]] || return 1

  {
    printf 'admin=%s\n' "${local_admin_code:-unknown}"
    printf 'bare=%s\n' "${local_bare_code:-unknown}"
    printf 'tokenized=%s\n' "${local_tokenized_code:-unknown}"
    printf 'overall=%s\n' "${local_overall:-unknown}"
  }
}

probe_local_dns_contract_with_retry() {
  local remote_token="$1"
  local max_attempts="${DNS_CONTRACT_LOCAL_MAX_ATTEMPTS:-6}"
  local retry_sleep_seconds="${DNS_CONTRACT_LOCAL_RETRY_SLEEP_SECONDS:-5}"
  local attempt=1
  local local_contract_output=""
  local local_admin_code=""
  local local_bare_code=""
  local local_tokenized_code=""
  local local_overall=""

  while (( attempt <= max_attempts )); do
    local_contract_output="$(probe_local_dns_contract || true)"
    if [[ -n "${local_contract_output}" ]]; then
      local_admin_code="$(dns_contract_output_field "${local_contract_output}" "admin")"
      local_bare_code="$(dns_contract_output_field "${local_contract_output}" "bare")"
      local_tokenized_code="$(dns_contract_output_field "${local_contract_output}" "tokenized")"
      local_overall="$(dns_contract_output_field "${local_contract_output}" "overall")"
      if dns_contract_local_probe_ready "${local_admin_code}" "${local_bare_code}" "${local_tokenized_code}" "${remote_token}" "${local_overall}"; then
        printf '%s\n' "${local_contract_output}"
        return 0
      fi
    fi

    if (( attempt >= max_attempts )); then
      [[ -n "${local_contract_output}" ]] || return 1
      printf '%s\n' "${local_contract_output}"
      return 0
    fi

    log "Device-local DNS contract probe attempt ${attempt}/${max_attempts} not ready yet; retrying in ${retry_sleep_seconds}s"
    sleep "${retry_sleep_seconds}"
    attempt=$((attempt + 1))
  done
}

validate_dns_contract() {
  local remote_host=""
  local remote_https_port=""
  local remote_token=""
  local public_admin_code=""
  local public_bare_code=""
  local public_tokenized_code="skipped"
  local effective_admin_code=""
  local effective_bare_code=""
  local effective_tokenized_code="skipped"
  local local_admin_code=""
  local local_bare_code=""
  local local_tokenized_code=""
  local local_overall=""
  local local_contract_output=""
  local probe_mode="public"
  local base_url=""

  if ! dns_contract_enabled; then
    {
      printf 'probe_mode=disabled\n'
      printf 'admin=disabled\n'
      printf 'bare=disabled\n'
      printf 'tokenized=disabled\n'
    } > "${REPORT_DIR}/dns-contract.txt"
    return 0
  fi

  remote_host="$(dns_contract_config_value "remote.hostname" "dns.jolkins.id.lv")"
  remote_https_port="$(dns_contract_config_value "remote.httpsPort" "443")"
  remote_token="$(dns_contract_config_value "remote.dohPathToken" "")"
  base_url="https://${remote_host}"
  if [[ "${remote_https_port}" != "443" ]]; then
    base_url="${base_url}:${remote_https_port}"
  fi

  public_admin_code="$(http_code "${base_url}/admin/")"
  public_bare_code="$(http_code "${base_url}/dns-query")"
  if [[ -n "${remote_token}" ]]; then
    public_tokenized_code="$(http_code "${base_url}/${remote_token}/dns-query")"
  fi

  effective_admin_code="${public_admin_code}"
  effective_bare_code="${public_bare_code}"
  effective_tokenized_code="${public_tokenized_code}"

  if ! dns_contract_probe_available "${public_admin_code}" "${public_bare_code}" "${public_tokenized_code}"; then
    if local_contract_output="$(probe_local_dns_contract_with_retry "${remote_token}")"; then
      probe_mode="local_fallback"
      log "Public DNS contract probe unavailable from the deploy host; falling back to device-local contract checks"
      local_admin_code="$(dns_contract_output_field "${local_contract_output}" "admin")"
      local_bare_code="$(dns_contract_output_field "${local_contract_output}" "bare")"
      local_tokenized_code="$(dns_contract_output_field "${local_contract_output}" "tokenized")"
      local_overall="$(dns_contract_output_field "${local_contract_output}" "overall")"
      effective_admin_code="${local_admin_code:-unknown}"
      effective_bare_code="${local_bare_code:-unknown}"
      effective_tokenized_code="${local_tokenized_code:-unknown}"
    else
      probe_mode="public_unavailable"
    fi
  fi

  {
    printf 'probe_mode=%s\n' "${probe_mode}"
    printf 'admin=%s\n' "${effective_admin_code}"
    printf 'bare=%s\n' "${effective_bare_code}"
    printf 'tokenized=%s\n' "${effective_tokenized_code}"
    printf 'public_admin=%s\n' "${public_admin_code}"
    printf 'public_bare=%s\n' "${public_bare_code}"
    printf 'public_tokenized=%s\n' "${public_tokenized_code}"
    if [[ -n "${local_admin_code}" || -n "${local_bare_code}" || -n "${local_tokenized_code}" || -n "${local_overall}" ]]; then
      printf 'local_admin=%s\n' "${local_admin_code:-unknown}"
      printf 'local_bare=%s\n' "${local_bare_code:-unknown}"
      printf 'local_tokenized=%s\n' "${local_tokenized_code:-unknown}"
      printf 'local_overall=%s\n' "${local_overall:-unknown}"
    fi
  } > "${REPORT_DIR}/dns-contract.txt"

  dns_contract_codes_valid "${effective_admin_code}" "${effective_bare_code}" "${effective_tokenized_code}" "${remote_token}"
}

maybe_recover_remote_frontend() {
  local dns_ok=0

  if run_deploy_health_check "dns_health" run_deploy --action health_component --component dns; then
    dns_ok=1
  else
    echo "DNS health validation failed" >&2
    exit 1
  fi

  if (( dns_ok == 1 )) &&
    ! run_deploy_health_check "remote_health" run_deploy --action health_component --component remote; then
    log "DNS is healthy but remote is not; retrying remote frontend recovery once"
    record_action "restart_component_remote"
    REMOTE_RECOVERY_TRIGGERED=1
    run_deploy --action restart_component --component remote
    if ! run_deploy_health_check "remote_health_after_recovery" run_deploy --action health_component --component remote; then
      echo "Remote frontend recovery did not restore component health" >&2
      exit 1
    fi
  fi
}

validate_scope_health() {
  if scope_includes_platform; then
    if scope_is_dns_only; then
      if ! run_deploy_health_check "dns_health" run_deploy --action health_component --component dns; then
        echo "DNS health validation failed" >&2
        exit 1
      fi
      return 0
    fi
  fi

  if ! run_deploy_health_check "orchestrator_health" run_deploy --action health; then
    echo "Orchestrator health validation failed" >&2
    exit 1
  fi
}

write_summary_json() {
  local actions_joined=""
  local validations_joined=""
  actions_joined="$(printf '%s\x1f' "${ACTIONS_EXECUTED[@]:-}")"
  validations_joined="$(printf '%s\x1f' "${VALIDATION_RESULTS[@]:-}")"

  export SUMMARY_DEVICE="$(selected_target_label)"
  export SUMMARY_SCOPE="${SCOPE}"
  export SUMMARY_MODE="${MODE}"
  export SUMMARY_STATUS="${RUN_STATUS}"
  export SUMMARY_REPORT_DIR="${REPORT_DIR}"
  export SUMMARY_RUNTIME_BUNDLE_DIR="${RUNTIME_BUNDLE_DIR}"
  export SUMMARY_RUNTIME_MANIFEST_PATH="${RUNTIME_MANIFEST_PATH}"
  export SUMMARY_DNS_RELEASE_DIR="${DNS_RELEASE_DIR}"
  export SUMMARY_TRAIN_RELEASE_DIR="${TRAIN_BOT_RELEASE_DIR}"
  export SUMMARY_SATIKSME_RELEASE_DIR="${SATIKSME_BOT_RELEASE_DIR}"
  export SUMMARY_SITE_RELEASE_DIR="${SITE_NOTIFIER_RELEASE_DIR}"
  export SUMMARY_SUBSCRIPTION_RELEASE_DIR="${SUBSCRIPTION_BOT_RELEASE_DIR}"
  export SUMMARY_BOOTSTRAP_NEEDED="${BOOTSTRAP_NEEDED}"
  export SUMMARY_ROOTED_STALE="${ROOTED_STALE}"
  export SUMMARY_PREFLIGHT_ROOTED_FRESHNESS="${PREFLIGHT_ROOTED_FRESHNESS}"
  export SUMMARY_CONFIG_CHANGED="${CONFIG_CHANGED}"
  export SUMMARY_DDNS_TOKEN_CHANGED="${DDNS_TOKEN_CHANGED}"
  export SUMMARY_PLATFORM_ARTIFACTS_CHANGED="${PLATFORM_ARTIFACTS_CHANGED}"
  export SUMMARY_DNS_ARTIFACTS_CHANGED="${DNS_ARTIFACTS_CHANGED}"
  export SUMMARY_DNS_RELEASE_NEEDED="${DNS_RELEASE_NEEDED}"
  export SUMMARY_REPAIR_ACTION="${REPAIR_ACTION}"
  export SUMMARY_ROOTFS_SOURCE="${ROOTFS_SOURCE}"
  export SUMMARY_REMOTE_RECOVERY_TRIGGERED="${REMOTE_RECOVERY_TRIGGERED}"
  export SUMMARY_FINAL_ROOTED_FRESHNESS="${FINAL_ROOTED_FRESHNESS}"
  export SUMMARY_FINAL_ROOTED_FRESHNESS_REPORT="${FINAL_ROOTED_FRESHNESS_REPORT}"
  export SUMMARY_LIVE_DNS_RUNTIME_CONVERGED="${LIVE_DNS_RUNTIME_CONVERGED}"
  export SUMMARY_ACTIONS="${actions_joined}"
  export SUMMARY_VALIDATIONS="${validations_joined}"
  export SUMMARY_TRAIN_BOT_RESULT_SOURCE="${TRAIN_BOT_DEPLOY_RESULT_SOURCE}"
  export SUMMARY_SATIKSME_BOT_RESULT_SOURCE="${SATIKSME_BOT_DEPLOY_RESULT_SOURCE}"
  export SUMMARY_SITE_NOTIFIER_RESULT_SOURCE="${SITE_NOTIFIER_DEPLOY_RESULT_SOURCE}"
  export SUMMARY_SUBSCRIPTION_BOT_RESULT_SOURCE="${SUBSCRIPTION_BOT_DEPLOY_RESULT_SOURCE}"
  export SUMMARY_TRAIN_BOT_LIVE_RELEASE_PATH="${TRAIN_BOT_LIVE_RELEASE_PATH}"
  export SUMMARY_SATIKSME_BOT_LIVE_RELEASE_PATH="${SATIKSME_BOT_LIVE_RELEASE_PATH}"
  export SUMMARY_SITE_NOTIFIER_LIVE_RELEASE_PATH="${SITE_NOTIFIER_LIVE_RELEASE_PATH}"
  export SUMMARY_SUBSCRIPTION_BOT_LIVE_RELEASE_PATH="${SUBSCRIPTION_BOT_LIVE_RELEASE_PATH}"
  export SUMMARY_TRAIN_BOT_RECOVERY_COMMAND="${TRAIN_BOT_RECOVERY_COMMAND}"
  export SUMMARY_SATIKSME_BOT_RECOVERY_COMMAND="${SATIKSME_BOT_RECOVERY_COMMAND}"
  export SUMMARY_SITE_NOTIFIER_RECOVERY_COMMAND="${SITE_NOTIFIER_RECOVERY_COMMAND}"
  export SUMMARY_SUBSCRIPTION_BOT_RECOVERY_COMMAND="${SUBSCRIPTION_BOT_RECOVERY_COMMAND}"
  export SUMMARY_JSON_PATH="${SUMMARY_JSON}"

  python3 - <<'PY'
import json
import os
from pathlib import Path

def split_field(name):
    raw = os.environ.get(name, "")
    if not raw:
        return []
    return [item for item in raw.split("\x1f") if item]

validations = {}
for item in split_field("SUMMARY_VALIDATIONS"):
    if "=" in item:
        key, value = item.split("=", 1)
        validations[key] = value

payload = {
    "device": os.environ["SUMMARY_DEVICE"],
    "scope": os.environ["SUMMARY_SCOPE"],
    "mode": os.environ["SUMMARY_MODE"],
    "status": os.environ["SUMMARY_STATUS"],
    "reportDir": os.environ["SUMMARY_REPORT_DIR"],
    "runtimeBundleDir": os.environ.get("SUMMARY_RUNTIME_BUNDLE_DIR") or None,
    "runtimeManifestPath": os.environ.get("SUMMARY_RUNTIME_MANIFEST_PATH") or None,
    "dnsReleaseDir": os.environ.get("SUMMARY_DNS_RELEASE_DIR") or None,
    "trainBotReleaseDir": os.environ.get("SUMMARY_TRAIN_RELEASE_DIR") or None,
    "satiksmeBotReleaseDir": os.environ.get("SUMMARY_SATIKSME_RELEASE_DIR") or None,
    "siteNotifierReleaseDir": os.environ.get("SUMMARY_SITE_RELEASE_DIR") or None,
    "subscriptionBotReleaseDir": os.environ.get("SUMMARY_SUBSCRIPTION_RELEASE_DIR") or None,
    "preflight": {
        "rootedFreshness": os.environ.get("SUMMARY_PREFLIGHT_ROOTED_FRESHNESS") or "unknown",
        "rootfsSource": os.environ.get("SUMMARY_ROOTFS_SOURCE") or None,
    },
    "decisions": {
        "bootstrapNeeded": os.environ["SUMMARY_BOOTSTRAP_NEEDED"] == "1",
        "dnsReleaseNeeded": os.environ["SUMMARY_DNS_RELEASE_NEEDED"] == "1",
        "rootedStale": os.environ["SUMMARY_ROOTED_STALE"] == "1",
        "configChanged": os.environ["SUMMARY_CONFIG_CHANGED"] == "1",
        "ddnsTokenChanged": os.environ["SUMMARY_DDNS_TOKEN_CHANGED"] == "1",
        "platformArtifactsChanged": os.environ["SUMMARY_PLATFORM_ARTIFACTS_CHANGED"] == "1",
        "dnsArtifactsChanged": os.environ["SUMMARY_DNS_ARTIFACTS_CHANGED"] == "1",
        "repairAction": os.environ.get("SUMMARY_REPAIR_ACTION") or "none",
        "remoteRecoveryTriggered": os.environ["SUMMARY_REMOTE_RECOVERY_TRIGGERED"] == "1",
    },
    "finalState": {
        "rootedFreshness": os.environ.get("SUMMARY_FINAL_ROOTED_FRESHNESS") or "unknown",
        "liveDnsRuntimeConverged": os.environ.get("SUMMARY_LIVE_DNS_RUNTIME_CONVERGED") == "1",
        "rootedFreshnessReport": os.environ.get("SUMMARY_FINAL_ROOTED_FRESHNESS_REPORT") or None,
    },
    "actionsExecuted": split_field("SUMMARY_ACTIONS"),
    "validations": validations,
    "componentResults": {
        "trainBot": {
            "action": "redeploy_component",
            "releaseDir": os.environ.get("SUMMARY_TRAIN_RELEASE_DIR") or None,
            "resultSource": os.environ.get("SUMMARY_TRAIN_BOT_RESULT_SOURCE") or "none",
            "liveReleasePath": os.environ.get("SUMMARY_TRAIN_BOT_LIVE_RELEASE_PATH") or None,
            "recoveryCommand": os.environ.get("SUMMARY_TRAIN_BOT_RECOVERY_COMMAND") or None,
        },
        "satiksmeBot": {
            "action": "redeploy_component",
            "releaseDir": os.environ.get("SUMMARY_SATIKSME_RELEASE_DIR") or None,
            "resultSource": os.environ.get("SUMMARY_SATIKSME_BOT_RESULT_SOURCE") or "none",
            "liveReleasePath": os.environ.get("SUMMARY_SATIKSME_BOT_LIVE_RELEASE_PATH") or None,
            "recoveryCommand": os.environ.get("SUMMARY_SATIKSME_BOT_RECOVERY_COMMAND") or None,
        },
        "siteNotifier": {
            "action": "redeploy_component",
            "releaseDir": os.environ.get("SUMMARY_SITE_RELEASE_DIR") or None,
            "resultSource": os.environ.get("SUMMARY_SITE_NOTIFIER_RESULT_SOURCE") or "none",
            "liveReleasePath": os.environ.get("SUMMARY_SITE_NOTIFIER_LIVE_RELEASE_PATH") or None,
            "recoveryCommand": os.environ.get("SUMMARY_SITE_NOTIFIER_RECOVERY_COMMAND") or None,
        },
        "subscriptionBot": {
            "action": "redeploy_component",
            "releaseDir": os.environ.get("SUMMARY_SUBSCRIPTION_RELEASE_DIR") or None,
            "resultSource": os.environ.get("SUMMARY_SUBSCRIPTION_BOT_RESULT_SOURCE") or "none",
            "liveReleasePath": os.environ.get("SUMMARY_SUBSCRIPTION_BOT_LIVE_RELEASE_PATH") or None,
            "recoveryCommand": os.environ.get("SUMMARY_SUBSCRIPTION_BOT_RECOVERY_COMMAND") or None,
        },
    },
}

Path(os.environ["SUMMARY_JSON_PATH"]).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

finalize() {
  local rc=$?
  if (( rc != 0 )) && [[ "${RUN_STATUS}" != "success" ]]; then
    RUN_STATUS="failed"
  fi
  write_summary_json || true
  if (( rc == 0 )); then
    log "Summary: ${SUMMARY_JSON}"
  fi
  return "${rc}"
}

trap finalize EXIT

main() {
  log "Using transport: $(pixel_transport_selected) ($(selected_target_label))"
  log "PIXEL_RUN_ID=${PIXEL_RUN_ID}"
  preflight_satiksme_schema_publish

  if scope_includes_platform; then
    if scope_requires_runtime_bundle; then
      package_runtime_bundle
    fi
    package_dns_release
    compute_platform_bootstrap_need
  fi

  if [[ "${MODE}" != "validate-only" ]]; then
    build_orchestrator_if_needed

    if scope_includes_train; then
      record_action "package_train_bot_release"
      package_train_release
    fi

    if scope_includes_satiksme; then
      record_action "package_satiksme_bot_release"
      package_satiksme_release
    fi

    if scope_includes_site; then
      record_action "package_site_notifier_release"
      package_site_release
    fi

    if scope_includes_subscription; then
      record_action "package_subscription_bot_release"
      package_subscription_release
    fi

    case "${MODE}" in
      force-bootstrap)
        BOOTSTRAP_NEEDED=1
        DNS_RELEASE_NEEDED=0
        REPAIR_ACTION="bootstrap"
        ;;
      force-refresh)
        if scope_includes_platform && (( PLATFORM_ARTIFACTS_CHANGED == 1 || CONFIG_CHANGED == 1 || DDNS_TOKEN_CHANGED == 1 )); then
          BOOTSTRAP_NEEDED=1
          ROOTED_CONVERGENCE_REQUIRED=1
        else
          BOOTSTRAP_NEEDED=0
        fi
        if scope_includes_platform && (( BOOTSTRAP_NEEDED == 0 )); then
          DNS_RELEASE_NEEDED=1
        fi
        if (( BOOTSTRAP_NEEDED == 1 )); then
          REPAIR_ACTION="bootstrap"
        else
          REPAIR_ACTION="dns_component_redeploy"
        fi
        ;;
    esac
  else
    if scope_includes_platform; then
      FINAL_ROOTED_FRESHNESS="${PREFLIGHT_ROOTED_FRESHNESS}"
      FINAL_ROOTED_FRESHNESS_REPORT="${ROOTED_FRESHNESS_PRE_REPORT}"
      if verify_live_dns_runtime; then
        LIVE_DNS_RUNTIME_CONVERGED=1
      fi
    fi
  fi

  emit_plan

  if [[ "${MODE}" != "validate-only" ]]; then
    if scope_includes_platform && (( BOOTSTRAP_NEEDED == 1 )); then
      if [[ -z "${RUNTIME_BUNDLE_DIR}" ]]; then
        package_runtime_bundle
      fi
      run_platform_bootstrap
      DNS_RELEASE_NEEDED=0
      REPAIR_ACTION="bootstrap"
      PLATFORM_MUTATION_PERFORMED=1
      ROOTED_CONVERGENCE_REQUIRED=1
    fi

    if scope_includes_platform && (( BOOTSTRAP_NEEDED == 0 && DNS_RELEASE_NEEDED == 1 )); then
      run_component_redeploy "dns" "${DNS_RELEASE_DIR}" ""
      REPAIR_ACTION="dns_component_redeploy"
      PLATFORM_MUTATION_PERFORMED=1
      ROOTED_CONVERGENCE_REQUIRED=1
    fi

    if scope_includes_train; then
      run_component_redeploy "train_bot" "${TRAIN_BOT_RELEASE_DIR}" --train-bot-env-file "${TRAIN_BOT_ENV_FILE}"
    fi

    if scope_includes_satiksme; then
      publish_satiksme_schema
      run_component_redeploy "satiksme_bot" "${SATIKSME_BOT_RELEASE_DIR}" --satiksme-bot-env-file "${SATIKSME_BOT_ENV_FILE}"
    fi

    if scope_includes_site; then
      run_component_redeploy "site_notifier" "${SITE_NOTIFIER_RELEASE_DIR}" --site-notifier-env-file "${SITE_NOTIFIER_ENV_FILE}"
    fi

    if scope_includes_subscription; then
      run_component_redeploy "subscription_bot" "${SUBSCRIPTION_BOT_RELEASE_DIR}" --subscription-bot-env-file "${SUBSCRIPTION_BOT_ENV_FILE}"
    fi
  fi

  validate_scope_health

  if scope_includes_platform && [[ "${MODE}" != "validate-only" ]]; then
    maybe_recover_remote_frontend
    if (( REMOTE_RECOVERY_TRIGGERED == 1 )); then
      PLATFORM_MUTATION_PERFORMED=1
      ROOTED_CONVERGENCE_REQUIRED=1
    fi
  fi

  if scope_includes_platform; then
    if validate_dns_contract; then
      record_validation "dns_contract" "pass"
    else
      record_validation "dns_contract" "fail"
      echo "DNS contract validation failed; see ${REPORT_DIR}/dns-contract.txt" >&2
      exit 1
    fi
  fi

  if scope_includes_platform; then
    if (( ROOTED_CONVERGENCE_REQUIRED == 1 )); then
      post_deploy_rooted_freshness
    elif [[ "${MODE}" == "validate-only" ]]; then
      :
    elif [[ "${FINAL_ROOTED_FRESHNESS}" == "unknown" ]]; then
      FINAL_ROOTED_FRESHNESS="${PREFLIGHT_ROOTED_FRESHNESS}"
      FINAL_ROOTED_FRESHNESS_REPORT="${ROOTED_FRESHNESS_PRE_REPORT}"
      if verify_live_dns_runtime; then
        LIVE_DNS_RUNTIME_CONVERGED=1
      else
        LIVE_DNS_RUNTIME_CONVERGED=0
      fi
    fi
  fi

  if scope_includes_train; then
    record_action "validate_train_bot"
    train_validate_cmd=("${TRAIN_DEPLOY_SCRIPT}" --skip-build --validate-only)
    while IFS= read -r line; do
      [[ -n "${line}" ]] && train_validate_cmd+=("${line}")
    done < <(deploy_base_args)
    "${train_validate_cmd[@]}"
    record_validation "train_bot_validation" "pass"
  fi

  if scope_includes_satiksme; then
    record_action "validate_satiksme_bot"
    satiksme_validate_cmd=("${SATIKSME_VALIDATE_SCRIPT}")
    while IFS= read -r line; do
      [[ -n "${line}" ]] && satiksme_validate_cmd+=("${line}")
    done < <(runtime_freshness_args)
    ORCHESTRATOR_REPO="${ORCHESTRATOR_ROOT}" "${satiksme_validate_cmd[@]}"
    record_validation "satiksme_bot_validation" "pass"
  fi

  if scope_includes_site; then
    record_action "validate_site_notifier"
    site_validate_cmd=("${SITE_DEPLOY_SCRIPT}" --skip-build --validate-only)
    while IFS= read -r line; do
      [[ -n "${line}" ]] && site_validate_cmd+=("${line}")
    done < <(deploy_base_args)
    "${site_validate_cmd[@]}"
    record_validation "site_notifier_validation" "pass"
  fi

  if scope_includes_subscription; then
    record_action "validate_subscription_bot"
    subscription_validate_cmd=("${SUBSCRIPTION_DEPLOY_SCRIPT}" --skip-build --validate-only)
    while IFS= read -r line; do
      [[ -n "${line}" ]] && subscription_validate_cmd+=("${line}")
    done < <(deploy_base_args)
    "${subscription_validate_cmd[@]}"
    record_validation "subscription_bot_validation" "pass"
  fi

  if (( DESTRUCTIVE_E2E == 1 )) && [[ "${MODE}" != "validate-only" ]]; then
    if scope_includes_platform || scope_includes_train || scope_includes_satiksme; then
      record_action "destructive_restart_isolation"
      "${WORKSPACE_ROOT}/workloads/train-bot/scripts/pixel/restart_isolation_acceptance.sh"
      record_validation "destructive_restart_isolation" "pass"
    fi
  fi

  RUN_STATUS="success"
}

main "$@"
