#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_ROOT="${REPO_ROOT}/android-orchestrator"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${WORKSPACE_ROOT}/tools/pixel/transport.sh"
APK_PATH="${APP_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
PKG="lv.jolkins.pixelorchestrator"
RECEIVER="${PKG}/.app.OrchestratorActionReceiver"
RUNTIME_ASSET_FRESHNESS_SCRIPT="${REPO_ROOT}/scripts/android/runtime_asset_freshness.sh"
ACTION_RESULT_REMOTE_DIR="/data/local/pixel-stack/run/orchestrator-action-results"

ADB_SERIAL=""
ACTION="bootstrap"
COMPONENT=""
SKIP_BUILD=0
RUNTIME_BUNDLE_DIR=""
COMPONENT_RELEASE_DIR=""
CONFIG_FILE=""
SSH_PUBLIC_KEY_FILE=""
SSH_PASSWORD_HASH_FILE=""
DDNS_TOKEN_FILE=""
ADMIN_PASSWORD_FILE=""
ACME_TOKEN_FILE=""
TRAIN_BOT_ENV_FILE=""
SATIKSME_BOT_ENV_FILE=""
SITE_NOTIFIER_ENV_FILE=""
SUBSCRIPTION_BOT_ENV_FILE=""
VPN_AUTH_KEY_FILE=""
IPINFO_LITE_TOKEN_FILE=""
DRY_RUN=0
PIXEL_RUN_ID="${PIXEL_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM}"
RUNTIME_ASSET_REPAIR_REQUIRED=0
RUNTIME_ASSET_REPAIR_ACTION="none"
RUNTIME_ASSET_SCOPE=""
ACTION_RESULT_SOURCE="none"
ACTION_RESULT_REMOTE_PATH=""
ACTION_RESULT_LOG_MARKER_SEEN=0
ACTION_RESULT_LOGS=""
ACTION_RESULT_SUMMARY=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --device SERIAL             adb serial (optional if only one device connected)
  --transport MODE            transport to use (adb|ssh|auto)
  --ssh-host IP               Tailscale or SSH host/IP
  --ssh-port PORT             SSH port (default: 2222)
  --action NAME               orchestrator action to run after launch
                              (bootstrap|start_all|stop_all|health|sync_ddns|export_bundle|cleanup|
                               redeploy_component|start_component|stop_component|
                               restart_component|health_component)
  --component NAME            required when action is component-scoped
                              (dns|ssh|vpn|ddns|remote|train_bot|satiksme_bot|site_notifier|subscription_bot)
  --runtime-bundle-dir PATH   local runtime bundle dir containing runtime-manifest.json and artifacts/
  --component-release-dir PATH
                              local component release dir containing release-manifest.json and artifacts/
                              (staged alongside bootstrap for DNS/remote, or used directly with redeploy_component)
  --config-file PATH          orchestrator config JSON to copy to /data/local/pixel-stack/conf/orchestrator-config-v1.json
  --ssh-public-key PATH       SSH authorized_keys source file to copy to /data/local/pixel-stack/conf/ssh/authorized_keys
  --ssh-password-hash-file PATH
                              SSH password hash file to copy to /data/local/pixel-stack/conf/ssh/root_password.hash
  --ddns-token-file PATH      Cloudflare token file to copy to /data/local/pixel-stack/conf/ddns/cloudflare-token
  --admin-password-file PATH  AdGuard admin password file to copy to /data/local/pixel-stack/conf/adguardhome/remote-admin-password
  --ipinfo-lite-token-file PATH
                              IPinfo Lite token file to copy to /data/local/pixel-stack/conf/adguardhome/ipinfo-lite-token
  --acme-token-file PATH      ACME Cloudflare token file (must match ddns token when both provided)
  --train-bot-env-file PATH   train bot env file to copy to /data/local/pixel-stack/conf/apps/train-bot.env
  --satiksme-bot-env-file PATH
                              satiksme bot env file to copy to /data/local/pixel-stack/conf/apps/satiksme-bot.env
  --site-notifier-env-file PATH
                              site notifier env file to copy to /data/local/pixel-stack/conf/apps/site-notifications.env
  --subscription-bot-env-file PATH
                              subscription bot env file to copy to /data/local/pixel-stack/conf/apps/subscription-bot.env
  --vpn-auth-key-file PATH    Tailscale auth key file to copy to /data/local/pixel-stack/conf/vpn/tailscale-authkey
  --dry-run                   only valid with --action cleanup; inventory without deleting
  --skip-build                do not build APK before deploy
  -h, --help                  show help
USAGE
}

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

generate_bcrypt_hash() {
  local password="$1"
  htpasswd -nbBC 10 "" "${password}" 2>/dev/null | tr -d ':\n' | sed 's/^\$2y/\$2a/'
}

action_result_component_key() {
  if [[ -n "${COMPONENT}" ]]; then
    printf '%s\n' "${COMPONENT}"
  else
    printf 'all\n'
  fi
}

action_result_remote_path() {
  local component_key=""
  component_key="$(action_result_component_key)"
  printf '%s/%s--%s--%s.json\n' "${ACTION_RESULT_REMOTE_DIR}" "${PIXEL_RUN_ID}" "${ACTION}" "${component_key}"
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    --action)
      shift
      ACTION="${1:-}"
      ;;
    --component)
      shift
      COMPONENT="${1:-}"
      ;;
    --runtime-bundle-dir)
      shift
      RUNTIME_BUNDLE_DIR="${1:-}"
      ;;
    --component-release-dir)
      shift
      COMPONENT_RELEASE_DIR="${1:-}"
      ;;
    --config-file)
      shift
      CONFIG_FILE="${1:-}"
      ;;
    --ssh-public-key)
      shift
      SSH_PUBLIC_KEY_FILE="${1:-}"
      ;;
    --ssh-password-hash-file)
      shift
      SSH_PASSWORD_HASH_FILE="${1:-}"
      ;;
    --ddns-token-file)
      shift
      DDNS_TOKEN_FILE="${1:-}"
      ;;
    --admin-password-file)
      shift
      ADMIN_PASSWORD_FILE="${1:-}"
      ;;
    --ipinfo-lite-token-file)
      shift
      IPINFO_LITE_TOKEN_FILE="${1:-}"
      ;;
    --acme-token-file)
      shift
      ACME_TOKEN_FILE="${1:-}"
      ;;
    --train-bot-env-file)
      shift
      TRAIN_BOT_ENV_FILE="${1:-}"
      ;;
    --satiksme-bot-env-file)
      shift
      SATIKSME_BOT_ENV_FILE="${1:-}"
      ;;
    --site-notifier-env-file)
      shift
      SITE_NOTIFIER_ENV_FILE="${1:-}"
      ;;
    --subscription-bot-env-file)
      shift
      SUBSCRIPTION_BOT_ENV_FILE="${1:-}"
      ;;
    --vpn-auth-key-file)
      shift
      VPN_AUTH_KEY_FILE="${1:-}"
      ;;
    --dry-run)
      DRY_RUN=1
      ;;
    --skip-build)
      SKIP_BUILD=1
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

case "${ACTION}" in
  bootstrap|start_all|stop_all|health|sync_ddns|export_bundle|cleanup|redeploy_component|start_component|stop_component|restart_component|health_component) ;;
  *)
    echo "Unsupported --action: ${ACTION}" >&2
    usage >&2
    exit 2
    ;;
esac

if (( DRY_RUN == 1 )) && [[ "${ACTION}" != "cleanup" ]]; then
  echo "--dry-run is only supported with --action cleanup" >&2
  exit 2
fi

case "${ACTION}" in
  start_component|stop_component|restart_component|health_component|redeploy_component)
    case "${COMPONENT}" in
      dns|ssh|vpn|ddns|remote|train_bot|satiksme_bot|site_notifier|subscription_bot) ;;
      *)
        echo "--component must be one of: dns|ssh|vpn|ddns|remote|train_bot|satiksme_bot|site_notifier|subscription_bot" >&2
        exit 2
        ;;
    esac
    ;;
  *)
    if [[ -n "${COMPONENT}" ]]; then
      echo "--component is only valid with component-scoped actions" >&2
      exit 2
    fi
    ;;
esac

if [[ -n "${RUNTIME_BUNDLE_DIR}" && "${ACTION}" != "bootstrap" ]]; then
  echo "--runtime-bundle-dir is bootstrap-only; use --component-release-dir with redeploy_component" >&2
  exit 2
fi

if [[ -n "${COMPONENT_RELEASE_DIR}" && "${ACTION}" != "redeploy_component" && "${ACTION}" != "bootstrap" ]]; then
  echo "--component-release-dir is only valid with --action redeploy_component or bootstrap" >&2
  exit 2
fi

for file in \
  "${CONFIG_FILE}" \
  "${SSH_PUBLIC_KEY_FILE}" \
  "${SSH_PASSWORD_HASH_FILE}" \
  "${DDNS_TOKEN_FILE}" \
  "${ADMIN_PASSWORD_FILE}" \
  "${IPINFO_LITE_TOKEN_FILE}" \
  "${ACME_TOKEN_FILE}" \
  "${TRAIN_BOT_ENV_FILE}" \
  "${SATIKSME_BOT_ENV_FILE}" \
  "${SITE_NOTIFIER_ENV_FILE}" \
  "${SUBSCRIPTION_BOT_ENV_FILE}" \
  "${VPN_AUTH_KEY_FILE}"; do
  [[ -z "${file}" ]] && continue
  [[ -f "${file}" ]] || { echo "Provisioning file not found: ${file}" >&2; exit 1; }
done

if [[ -n "${RUNTIME_BUNDLE_DIR}" ]]; then
  [[ -d "${RUNTIME_BUNDLE_DIR}" ]] || { echo "Runtime bundle dir not found: ${RUNTIME_BUNDLE_DIR}" >&2; exit 1; }
  [[ -f "${RUNTIME_BUNDLE_DIR}/runtime-manifest.json" ]] || {
    echo "Runtime bundle missing runtime-manifest.json: ${RUNTIME_BUNDLE_DIR}" >&2
    exit 1
  }
  [[ -d "${RUNTIME_BUNDLE_DIR}/artifacts" ]] || {
    echo "Runtime bundle missing artifacts/ directory: ${RUNTIME_BUNDLE_DIR}" >&2
    exit 1
  }
fi

if [[ -n "${COMPONENT_RELEASE_DIR}" ]]; then
  [[ -d "${COMPONENT_RELEASE_DIR}" ]] || { echo "Component release dir not found: ${COMPONENT_RELEASE_DIR}" >&2; exit 1; }
  [[ -f "${COMPONENT_RELEASE_DIR}/release-manifest.json" ]] || {
    echo "Component release dir missing release-manifest.json: ${COMPONENT_RELEASE_DIR}" >&2
    exit 1
  }
  [[ -d "${COMPONENT_RELEASE_DIR}/artifacts" ]] || {
    echo "Component release dir missing artifacts/ directory: ${COMPONENT_RELEASE_DIR}" >&2
    exit 1
  }
fi

if [[ -n "${DDNS_TOKEN_FILE}" && -n "${ACME_TOKEN_FILE}" ]]; then
  ddns_hash="$(sha256_file "${DDNS_TOKEN_FILE}")"
  acme_hash="$(sha256_file "${ACME_TOKEN_FILE}")"
  if [[ "${ddns_hash}" != "${acme_hash}" ]]; then
    echo "--ddns-token-file and --acme-token-file must contain the same token content" >&2
    exit 2
  fi
fi

if (( SKIP_BUILD == 0 )); then
  "${REPO_ROOT}/scripts/android/build_orchestrator_apk.sh"
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "APK not found: ${APK_PATH}" >&2
  exit 1
fi

pixel_transport_require_device >/dev/null
adb_cmd=(pixel_transport_adb_compat)

if [[ "$(pixel_transport_selected)" == "adb" ]]; then
  echo "Using transport: adb (${ADB_SERIAL})"
else
  echo "Using transport: ssh (${PIXEL_SSH_HOST}:${PIXEL_SSH_PORT})"
fi
echo "PIXEL_RUN_ID=${PIXEL_RUN_ID}"
"${adb_cmd[@]}" get-state >/dev/null

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

if (( SKIP_BUILD == 1 )); then
  if "${adb_cmd[@]}" shell "pm path ${PKG}" | grep -q "^package:"; then
    echo "Skipping APK install (--skip-build and package already present)"
  else
    echo "Package not present on device; performing install despite --skip-build"
    "${adb_cmd[@]}" install -r "${APK_PATH}"
  fi
else
  "${adb_cmd[@]}" install -r "${APK_PATH}"
fi

# Keep app alive under battery optimizations whitelist when possible.
"${adb_cmd[@]}" shell "dumpsys deviceidle whitelist +${PKG}" >/dev/null 2>&1 || true

remote_sha256_file() {
  pixel_transport_remote_sha256_file "$1"
}

load_action_result_json() {
  local remote_path="$1"
  local payload=""
  if ! pixel_transport_remote_file_exists "${remote_path}"; then
    return 1
  fi
  payload="$(pixel_transport_remote_cat "${remote_path}" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "${payload}" ]] || ! JSON_PAYLOAD="${payload}" python3 - <<'PY' >/dev/null 2>&1
import json
import os

json.loads(os.environ["JSON_PAYLOAD"])
PY
  then
    return 1
  fi
  printf '%s\n' "${payload}"
}

action_result_field() {
  local json_payload="$1"
  local field_name="$2"
  JSON_PAYLOAD="${json_payload}" python3 - "${field_name}" <<'PY'
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

action_result_health_field() {
  local json_payload="$1"
  local field_name="$2"
  JSON_PAYLOAD="${json_payload}" python3 - "${field_name}" <<'PY'
import json
import os
import sys

field = sys.argv[1]
payload = json.loads(os.environ["JSON_PAYLOAD"])
health = payload.get("healthSnapshot") or {}
value = health.get(field, "")
if isinstance(value, bool):
    print("true" if value else "false")
elif value is None:
    print("")
else:
    print(value)
PY
}

component_runtime_name() {
  case "${1}" in
    train_bot) printf 'train-bot\n' ;;
    satiksme_bot) printf 'satiksme-bot\n' ;;
    site_notifier) printf 'site-notifications\n' ;;
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

collect_component_redeploy_timeout_summary() {
  local expected_release_id="$1"
  local live_release_path=""
  local manifest_staged="false"
  local process_status="unknown"

  if [[ -n "${COMPONENT}" ]]; then
    if ensure_component_release_manifest_staged "${COMPONENT}" >/dev/null 2>&1; then
      manifest_staged="true"
    fi
  fi

  live_release_path="$(component_live_release_path "${COMPONENT}")"
  case "${COMPONENT}" in
    train_bot)
      if "${adb_cmd[@]}" shell "su -c 'ps -A | grep -Eq \"train-bot.current|train-bot-service-loop\"'" >/dev/null 2>&1; then
        process_status="healthy"
      else
        process_status="missing"
      fi
      ;;
    satiksme_bot)
      if "${adb_cmd[@]}" shell "su -c 'ps -A | grep -Eq \"satiksme-bot.current|satiksme-bot-service-loop\"'" >/dev/null 2>&1; then
        process_status="healthy"
      else
        process_status="missing"
      fi
      ;;
    site_notifier)
      if "${adb_cmd[@]}" shell "su -c 'ps -A | grep -Eq \"site-notifications.current|site-notifier-service-loop\"'" >/dev/null 2>&1; then
        process_status="healthy"
      else
        process_status="missing"
      fi
      ;;
  esac

  cat <<EOF
intent_marker_seen=${ACTION_RESULT_LOG_MARKER_SEEN}
artifact_staged=${manifest_staged}
expected_release_id=${expected_release_id}
live_release_path=${live_release_path:-unknown}
process_status=${process_status}
resume_command=${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh $(transport_cli_args_string)--skip-build --action redeploy_component --component ${COMPONENT} --component-release-dir ${COMPONENT_RELEASE_DIR}
EOF
}

verify_redeploy_fallback() {
  local expected_release_id="$1"
  local live_release_path=""
  local process_pattern=""

  case "${COMPONENT}" in
    train_bot) process_pattern='train-bot.current|train-bot-service-loop' ;;
    satiksme_bot) process_pattern='satiksme-bot.current|satiksme-bot-service-loop' ;;
    *)
      return 1
      ;;
  esac

  ensure_component_release_manifest_staged "${COMPONENT}" >/dev/null 2>&1 || return 1
  live_release_path="$(component_live_release_path "${COMPONENT}")"
  [[ -n "${live_release_path}" && "${live_release_path}" == *"${expected_release_id}"* ]] || return 1
  "${adb_cmd[@]}" shell "su -c 'ps -A | grep -Eq \"${process_pattern}\"'" >/dev/null 2>&1 || return 1
  return 0
}

runtime_freshness_scope_for_component() {
  case "${1}" in
    dns|remote) printf 'dns\n' ;;
    ssh) printf 'ssh\n' ;;
    vpn) printf 'vpn\n' ;;
    train_bot) printf 'train_bot\n' ;;
    satiksme_bot) printf 'satiksme_bot\n' ;;
    site_notifier) printf 'site_notifier\n' ;;
    subscription_bot) printf 'subscription_bot\n' ;;
  esac
}

pre_action_runtime_freshness_scope() {
  case "${ACTION}" in
    bootstrap|start_all)
      printf 'readiness\n'
      ;;
    redeploy_component|start_component|restart_component)
      runtime_freshness_scope_for_component "${COMPONENT}"
      ;;
  esac
}

post_action_runtime_freshness_scope() {
  pre_action_runtime_freshness_scope
}

runtime_action_repairs_assets() {
  case "${ACTION}" in
    bootstrap|redeploy_component) return 0 ;;
    *) return 1 ;;
  esac
}

runtime_scope_requires_current_apk() {
  case "$1" in
    dns|remote|rooted) return 1 ;;
    *) return 0 ;;
  esac
}

verify_live_dns_runtime() {
  local local_hash="" host_hash="" chroot_hash=""
  local_hash="$(sha256_file "${APP_ROOT}/app/src/main/assets/runtime/templates/rooted/adguardhome-start")"
  host_hash="$(remote_sha256_file "/data/local/pixel-stack/templates/rooted/adguardhome-start")"
  chroot_hash="$(remote_sha256_file "/data/local/pixel-stack/chroots/adguardhome/usr/local/bin/adguardhome-start")"
  [[ "${host_hash}" == "${local_hash}" ]] || return 1
  [[ "${chroot_hash}" == "${local_hash}" ]] || return 1
  "${adb_cmd[@]}" shell "su -c 'ss -ltn 2>/dev/null | grep -Eq \"[.:]53[[:space:]]\" && ss -ltn 2>/dev/null | grep -Eq \"127\\.0\\.0\\.1:8080[[:space:]]\" && chroot /data/local/pixel-stack/chroots/adguardhome /usr/local/bin/adguardhome-start --remote-healthcheck >/dev/null 2>&1'" >/dev/null 2>&1
}

verify_runtime_assets_pre_action() {
  local scope="" output="" rc=0
  [[ -x "${RUNTIME_ASSET_FRESHNESS_SCRIPT}" ]] || return 0
  scope="$(pre_action_runtime_freshness_scope)"
  [[ -n "${scope}" ]] || return 0

  local -a freshness_cmd=("${RUNTIME_ASSET_FRESHNESS_SCRIPT}")
  while IFS= read -r line; do
    [[ -n "${line}" ]] && freshness_cmd+=("${line}")
  done < <(runtime_freshness_args)
  freshness_cmd+=(--scope "${scope}")
  output="$("${freshness_cmd[@]}" 2>&1)" || rc=$?
  case "${rc}" in
    0)
      return 0
      ;;
    3)
      RUNTIME_ASSET_REPAIR_REQUIRED=1
      RUNTIME_ASSET_SCOPE="${scope}"
      if ! runtime_action_repairs_assets; then
        echo "Runtime asset precheck stale (scope=${scope}); refusing to run lifecycle-only action ${ACTION}." >&2
        printf '%s\n' "${output}" >&2
        return 1
      fi
      if runtime_scope_requires_current_apk "${scope}" && (( SKIP_BUILD == 1 )); then
        echo "Runtime asset precheck stale (scope=${scope}) and --skip-build prevents APK-backed repair." >&2
        printf '%s\n' "${output}" >&2
        return 1
      fi
      if runtime_scope_requires_current_apk "${scope}"; then
        RUNTIME_ASSET_REPAIR_ACTION="apk_refresh"
      else
        RUNTIME_ASSET_REPAIR_ACTION="component_release"
      fi
      echo "Runtime asset precheck stale (scope=${scope}); continuing because ${ACTION} is expected to repair it via ${RUNTIME_ASSET_REPAIR_ACTION}."
      while IFS= read -r line; do
        [[ -n "${line}" ]] && echo "INFO: ${line}"
      done <<<"${output}"
      return 0
      ;;
    *)
      echo "Unable to verify runtime asset freshness before action (scope=${scope})" >&2
      printf '%s\n' "${output}" >&2
      return 1
      ;;
  esac
}

verify_runtime_assets_after_action() {
  local scope="" output="" rc=0
  [[ -x "${RUNTIME_ASSET_FRESHNESS_SCRIPT}" ]] || return 0
  scope="$(post_action_runtime_freshness_scope)"
  [[ -n "${scope}" ]] || return 0

  local -a freshness_cmd=("${RUNTIME_ASSET_FRESHNESS_SCRIPT}")
  while IFS= read -r line; do
    [[ -n "${line}" ]] && freshness_cmd+=("${line}")
  done < <(runtime_freshness_args)
  freshness_cmd+=(--scope "${scope}")
  output="$("${freshness_cmd[@]}" 2>&1)" || rc=$?
  case "${rc}" in
    0)
      echo "Runtime asset freshness after action: ${output}"
      ;;
    3)
      echo "Runtime asset freshness after action: STALE"
      printf '%s\n' "${output}" >&2
      return 1
      ;;
    *)
      echo "Unable to verify runtime asset freshness after action" >&2
      printf '%s\n' "${output}" >&2
      return 1
      ;;
  esac

  case "${scope}" in
    dns|readiness)
      if verify_live_dns_runtime; then
        echo "Live DNS runtime after action: converged"
      else
        echo "Live DNS runtime after action: stale" >&2
        identity_endpoint_status_summary >&2
        return 1
      fi
      ;;
  esac
}

action_implies_remote_bringup() {
  case "${ACTION}" in
    bootstrap|start_all|health)
      return 0
      ;;
    start_component|restart_component|redeploy_component)
      [[ "${COMPONENT}" == "dns" || "${COMPONENT}" == "remote" ]]
      return $?
      ;;
    *)
      return 1
      ;;
  esac
}

identity_endpoint_status_summary() {
  local status_line=""
  status_line="$("${adb_cmd[@]}" shell "su -c 'set +e; rootfs=\"/data/local/pixel-stack/chroots/adguardhome\"; helper=\"/usr/local/bin/adguardhome-start\"; if [ ! -x \"\${rootfs}\${helper}\" ]; then echo \"mode=unknown inject_code=unavailable remote_healthcheck=helper_missing\"; exit 0; fi; output=\$(chroot \"\${rootfs}\" \"\${helper}\" --remote-healthcheck-debug 2>/dev/null || true); mode=\$(printf \"%s\n\" \"\${output}\" | sed -n \"s/^doh_mode=//p\" | head -n1); inject_code=\$(printf \"%s\n\" \"\${output}\" | sed -n \"s/^identity_inject_code=//p\" | head -n1); remote_healthcheck=\$(printf \"%s\n\" \"\${output}\" | sed -n \"s/^remote_healthcheck=//p\" | head -n1); [ -n \"\${mode}\" ] || mode=unknown; [ -n \"\${inject_code}\" ] || inject_code=unavailable; [ -n \"\${remote_healthcheck}\" ] || remote_healthcheck=unknown; echo \"mode=\${mode} inject_code=\${inject_code} remote_healthcheck=\${remote_healthcheck}\"'" | tr -d '\r' | sed -n '1p')"
  echo "Identity endpoint check: ${status_line:-unavailable}"
}

verify_runtime_assets_pre_action

provision_file() {
  local local_path="$1"
  local remote_target="$2"
  local _stage_name="$3"

  [[ -f "${local_path}" ]] || return 0
  pixel_transport_push "${local_path}" "${remote_target}" >/dev/null
  pixel_transport_root_exec chmod 600 "${remote_target}" >/dev/null
}

component_release_owner_component() {
  case "${1}" in
    remote) printf 'dns\n' ;;
    *) printf '%s\n' "${1}" ;;
  esac
}

component_release_manifest_component() {
  local release_dir="$1"
  python3 - "${release_dir}/release-manifest.json" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
component_id = (payload.get("componentId") or "").strip()
if not component_id:
    raise SystemExit("component release manifest missing componentId")
print(component_id)
PY
}

stage_runtime_bundle() {
  local bundle_dir="$1"
  local manifest_path="${bundle_dir}/runtime-manifest.json"
  local artifacts_dir="${bundle_dir}/artifacts"
  local stage_root="/data/local/tmp/pixel-orchestrator-runtime-${PIXEL_RUN_ID}"
  local target_root="/data/local/pixel-stack/conf/runtime"
  local artifact_count=0

  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null
  pixel_transport_root_exec mkdir -p "${stage_root}/artifacts" >/dev/null
  pixel_transport_push "${manifest_path}" "${stage_root}/runtime-manifest.json" >/dev/null

  while IFS= read -r artifact_file; do
    [[ -f "${artifact_file}" ]] || continue
    artifact_count=$((artifact_count + 1))
    pixel_transport_push "${artifact_file}" "${stage_root}/artifacts/$(basename "${artifact_file}")" >/dev/null
  done < <(find "${artifacts_dir}" -maxdepth 1 -type f | sort)

  if (( artifact_count == 0 )); then
    echo "Runtime bundle artifacts/ is empty: ${artifacts_dir}" >&2
    exit 1
  fi

  pixel_transport_root_exec mkdir -p "${target_root}/artifacts" >/dev/null
  pixel_transport_root_exec cp "${stage_root}/runtime-manifest.json" "${target_root}/runtime-manifest.json"
  pixel_transport_root_exec find "${target_root}/artifacts" -mindepth 1 -maxdepth 1 -exec rm -rf '{}' '+'
  pixel_transport_root_exec cp -a "${stage_root}/artifacts/." "${target_root}/artifacts/"
  pixel_transport_root_exec chmod 600 "${target_root}/runtime-manifest.json" >/dev/null
  pixel_transport_root_exec find "${target_root}/artifacts" -maxdepth 1 -type f -exec chmod 644 '{}' '+'
  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null 2>&1 || true
}

stage_component_release() {
  local release_dir="$1"
  local requested_component="$2"
  local storage_component=""
  local manifest_path="${release_dir}/release-manifest.json"
  local artifacts_dir="${release_dir}/artifacts"
  local stage_root="/data/local/tmp/pixel-orchestrator-component-release-${PIXEL_RUN_ID}"
  local device_component_root=""
  local artifact_count=0

  storage_component="$(component_release_owner_component "${requested_component}")"
  device_component_root="/data/local/pixel-stack/conf/runtime/components/${storage_component}"

  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null
  pixel_transport_root_exec mkdir -p "${stage_root}/artifacts" >/dev/null
  pixel_transport_push "${manifest_path}" "${stage_root}/release-manifest.json" >/dev/null

  while IFS= read -r artifact_file; do
    [[ -f "${artifact_file}" ]] || continue
    artifact_count=$((artifact_count + 1))
    pixel_transport_push "${artifact_file}" "${stage_root}/artifacts/$(basename "${artifact_file}")" >/dev/null
  done < <(find "${artifacts_dir}" -maxdepth 1 -type f | sort)

  if (( artifact_count == 0 )); then
    echo "Component release artifacts/ is empty: ${artifacts_dir}" >&2
    exit 1
  fi

  pixel_transport_root_exec mkdir -p "/data/local/pixel-stack/conf/runtime/components" >/dev/null
  pixel_transport_root_exec rm -rf "${device_component_root}"
  pixel_transport_root_exec mkdir -p "${device_component_root}/artifacts" >/dev/null
  pixel_transport_root_exec cp "${stage_root}/release-manifest.json" "${device_component_root}/release-manifest.json"
  pixel_transport_root_exec cp -a "${stage_root}/artifacts/." "${device_component_root}/artifacts/"
  pixel_transport_root_exec chmod 600 "${device_component_root}/release-manifest.json" >/dev/null
  pixel_transport_root_exec find "${device_component_root}/artifacts" -maxdepth 1 -type f -exec chmod 644 '{}' '+'
  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null 2>&1 || true
}

ensure_runtime_manifest_staged() {
  if ! pixel_transport_root_exec test -s "/data/local/pixel-stack/conf/runtime/runtime-manifest.json" >/dev/null 2>&1; then
    echo "Missing staged runtime manifest on device: /data/local/pixel-stack/conf/runtime/runtime-manifest.json" >&2
    echo "Use --runtime-bundle-dir to stage a local runtime bundle before bootstrap." >&2
    exit 1
  fi
}

ensure_component_release_manifest_staged() {
  local requested_component="$1"
  local storage_component=""
  local manifest_path=""

  storage_component="$(component_release_owner_component "${requested_component}")"
  manifest_path="/data/local/pixel-stack/conf/runtime/components/${storage_component}/release-manifest.json"
  if ! pixel_transport_root_exec test -s "${manifest_path}" >/dev/null 2>&1; then
    echo "Missing staged component release manifest on device: ${manifest_path}" >&2
    echo "Use --component-release-dir to stage a single-service release before redeploy_component." >&2
    exit 1
  fi
}

wait_for_action_result() {
  local timeout_sec="$1"
  local elapsed=0
  local poll_sec=2
  local logs=""
  local action_logs=""
  local marker=""
  local marker_line=""
  local scan_logs=""
  local action_result_json=""
  ACTION_RESULT_SOURCE="none"
  ACTION_RESULT_LOG_MARKER_SEEN=0
  ACTION_RESULT_LOGS=""
  ACTION_RESULT_SUMMARY=""

  while (( elapsed < timeout_sec )); do
    if action_result_json="$(load_action_result_json "${ACTION_RESULT_REMOTE_PATH}")"; then
      ACTION_RESULT_LOGS="${ACTION_RESULT_LOGS:-${logs}}"
      if [[ "$(action_result_field "${action_result_json}" "success")" == "true" ]]; then
        ACTION_RESULT_SOURCE="artifact"
        ACTION_RESULT_SUMMARY="$(action_result_field "${action_result_json}" "message")"
        echo "Action ${ACTION} reported SUCCESS via artifact ${ACTION_RESULT_REMOTE_PATH}"
        return 0
      fi
      ACTION_RESULT_SOURCE="artifact"
      ACTION_RESULT_SUMMARY="$(action_result_field "${action_result_json}" "message")"
      echo "Action ${ACTION} reported FAILURE via artifact ${ACTION_RESULT_REMOTE_PATH}:"
      printf '%s\n' "${action_result_json}"
      return 1
    fi

    logs="$(pixel_transport_shell "logcat -d -v time | grep -E 'OrchestratorActionReceiver|SupervisorService' | tail -n 200" || true)"
    marker="command_accepted action=${ACTION} component=${COMPONENT} run_id=${PIXEL_RUN_ID}"
    marker_line="$(printf '%s\n' "${logs}" | grep -n -F "${marker}" | tail -n1 | cut -d: -f1 || true)"
    action_logs=""
    if [[ -n "${marker_line}" ]]; then
      ACTION_RESULT_LOG_MARKER_SEEN=1
      action_logs="$(printf '%s\n' "${logs}" | tail -n +"${marker_line}")"
    fi
    scan_logs="${action_logs:-${logs}}"
    if grep -Fq "command_action=${ACTION} component=${COMPONENT} success=false" <<<"${scan_logs}"; then
      ACTION_RESULT_SOURCE="log"
      ACTION_RESULT_LOGS="${scan_logs}"
      echo "Action ${ACTION} reported FAILURE:"
      echo "${scan_logs}"
      return 1
    fi
    if grep -Fq "command_action=${ACTION} component=${COMPONENT} success=true" <<<"${scan_logs}"; then
      ACTION_RESULT_SOURCE="log"
      ACTION_RESULT_LOGS="${scan_logs}"
      echo "Action ${ACTION} reported SUCCESS"
      return 0
    fi
    sleep "${poll_sec}"
    elapsed=$((elapsed + poll_sec))
  done

  ACTION_RESULT_LOGS="${logs}"
  echo "Timed out waiting for action ${ACTION} result after ${timeout_sec}s"
  if (( ACTION_RESULT_LOG_MARKER_SEEN == 0 )); then
    echo "WARN: did not observe marker '${marker}' in OrchestratorActionReceiver logs; used fallback SupervisorService scan"
  fi
  echo "${logs}"
  return 1
}

dispatch_orchestrator_action() {
  local shell_cmd=""
  local dispatch_output=""
  local rc=0

  shell_cmd="am broadcast -n ${RECEIVER} --es orchestrator_action ${ACTION} --es pixel_run_id ${PIXEL_RUN_ID}"
  if [[ -n "${COMPONENT}" ]]; then
    shell_cmd="${shell_cmd} --es orchestrator_component ${COMPONENT}"
  fi
  if (( DRY_RUN == 1 )); then
    shell_cmd="${shell_cmd} --ez orchestrator_dry_run true"
  fi

  set +e
  dispatch_output="$(pixel_transport_shell "${shell_cmd}" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "${dispatch_output}"

  if (( rc != 0 )) || ! grep -Fq 'Broadcast completed:' <<<"${dispatch_output}"; then
    echo "Failed to dispatch action ${ACTION} via ${RECEIVER}" >&2
    return 1
  fi
}

if [[ -n "${CONFIG_FILE}" || -n "${SSH_PUBLIC_KEY_FILE}" || -n "${SSH_PASSWORD_HASH_FILE}" || -n "${DDNS_TOKEN_FILE}" || -n "${ADMIN_PASSWORD_FILE}" || -n "${IPINFO_LITE_TOKEN_FILE}" || -n "${ACME_TOKEN_FILE}" || -n "${TRAIN_BOT_ENV_FILE}" || -n "${SATIKSME_BOT_ENV_FILE}" || -n "${SITE_NOTIFIER_ENV_FILE}" || -n "${SUBSCRIPTION_BOT_ENV_FILE}" || -n "${VPN_AUTH_KEY_FILE}" ]]; then
  echo "Provisioning runtime config/secrets"
  provision_file "${CONFIG_FILE}" "/data/local/pixel-stack/conf/orchestrator-config-v1.json" "orchestrator-config-v1.json"
  provision_file "${SSH_PUBLIC_KEY_FILE}" "/data/local/pixel-stack/conf/ssh/authorized_keys" "authorized_keys"
  provision_file "${SSH_PASSWORD_HASH_FILE}" "/data/local/pixel-stack/conf/ssh/root_password.hash" "root_password.hash"
  provision_file "${DDNS_TOKEN_FILE}" "/data/local/pixel-stack/conf/ddns/cloudflare-token" "cloudflare-token"
  provision_file "${ADMIN_PASSWORD_FILE}" "/data/local/pixel-stack/conf/adguardhome/remote-admin-password" "remote-admin-password"
  provision_file "${ADMIN_PASSWORD_FILE}" "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/secrets/admin-password" "remote-admin-password-chroot-secret"
  provision_file "${IPINFO_LITE_TOKEN_FILE}" "/data/local/pixel-stack/conf/adguardhome/ipinfo-lite-token" "ipinfo-lite-token"
  provision_file "${IPINFO_LITE_TOKEN_FILE}" "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/secrets/ipinfo-lite-token" "ipinfo-lite-token-chroot-secret"
  if [[ -n "${ADMIN_PASSWORD_FILE}" && -x "$(command -v htpasswd 2>/dev/null || true)" ]]; then
    admin_password_value="$(tr -d '\r' < "${ADMIN_PASSWORD_FILE}" | head -n1)"
    if [[ -n "${admin_password_value}" ]]; then
      admin_hash_tmp="$(mktemp)"
      generate_bcrypt_hash "${admin_password_value}" > "${admin_hash_tmp}"
      provision_file "${admin_hash_tmp}" "/data/local/pixel-stack/conf/adguardhome/remote-admin-password.hash" "remote-admin-password.hash"
      provision_file "${admin_hash_tmp}" "/data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/secrets/admin-password.hash" "remote-admin-password.hash-chroot-secret"
      rm -f "${admin_hash_tmp}"
    fi
  fi
  # One-release compatibility path for devices still reading the old location during transition.
  provision_file "${ADMIN_PASSWORD_FILE}" "/data/local/pixel-stack/conf/pihole-rooted/remote-admin-password" "remote-admin-password-legacy"
  provision_file "${ACME_TOKEN_FILE}" "/data/local/pixel-stack/conf/ddns/cloudflare-token" "cloudflare-token-acme"
  provision_file "${TRAIN_BOT_ENV_FILE}" "/data/local/pixel-stack/conf/apps/train-bot.env" "train-bot.env"
  provision_file "${SATIKSME_BOT_ENV_FILE}" "/data/local/pixel-stack/conf/apps/satiksme-bot.env" "satiksme-bot.env"
  provision_file "${SITE_NOTIFIER_ENV_FILE}" "/data/local/pixel-stack/conf/apps/site-notifications.env" "site-notifications.env"
  provision_file "${SUBSCRIPTION_BOT_ENV_FILE}" "/data/local/pixel-stack/conf/apps/subscription-bot.env" "subscription-bot.env"
  provision_file "${VPN_AUTH_KEY_FILE}" "/data/local/pixel-stack/conf/vpn/tailscale-authkey" "tailscale-authkey"
fi

if [[ -n "${RUNTIME_BUNDLE_DIR}" ]]; then
  echo "Staging runtime bundle from ${RUNTIME_BUNDLE_DIR}"
  stage_runtime_bundle "${RUNTIME_BUNDLE_DIR}"
fi

if [[ -n "${COMPONENT_RELEASE_DIR}" ]]; then
  staged_component="${COMPONENT}"
  if [[ -z "${staged_component}" && "${ACTION}" == "bootstrap" ]]; then
    staged_component="$(component_release_manifest_component "${COMPONENT_RELEASE_DIR}")"
  fi
  echo "Staging component release from ${COMPONENT_RELEASE_DIR} for ${staged_component}"
  stage_component_release "${COMPONENT_RELEASE_DIR}" "${staged_component}"
fi

if [[ "${ACTION}" == "bootstrap" ]]; then
  ensure_runtime_manifest_staged
fi
if [[ "${ACTION}" == "redeploy_component" ]]; then
  ensure_component_release_manifest_staged "${COMPONENT}"
fi

ACTION_RESULT_REMOTE_PATH="$(action_result_remote_path)"
pixel_transport_root_exec mkdir -p "${ACTION_RESULT_REMOTE_DIR}" >/dev/null 2>&1 || true
pixel_transport_root_exec rm -f "${ACTION_RESULT_REMOTE_PATH}" >/dev/null 2>&1 || true

pixel_transport_shell "am force-stop ${PKG}" >/dev/null 2>&1 || true
pixel_transport_shell "logcat -c" >/dev/null 2>&1 || true
dispatch_orchestrator_action

sleep 4

echo "Triggered action: ${ACTION}"
echo "Recent app logs:"
pixel_transport_shell "logcat -d -v time | grep -E 'OrchestratorActionReceiver|SupervisorService|OrchestratorMain' | tail -n 120" || true

wait_timeout_sec=120
case "${ACTION}" in
  bootstrap|start_all|stop_all|start_component|stop_component|restart_component|redeploy_component)
    wait_timeout_sec=300
    ;;
  cleanup)
    wait_timeout_sec=600
    ;;
  health|health_component|sync_ddns|export_bundle)
    wait_timeout_sec=120
    ;;
esac
if [[ -n "${ORCHESTRATOR_ACTION_TIMEOUT_SEC:-}" ]]; then
  wait_timeout_sec="${ORCHESTRATOR_ACTION_TIMEOUT_SEC}"
fi
action_wait_rc=0
wait_for_action_result "${wait_timeout_sec}" || action_wait_rc=$?

if (( action_wait_rc != 0 )) && [[ "${ACTION}" == "redeploy_component" && "${ACTION_RESULT_SOURCE}" == "none" ]]; then
  expected_release_id="$(component_expected_release_id "${COMPONENT_RELEASE_DIR}")"
  ACTION_RESULT_SUMMARY="$(collect_component_redeploy_timeout_summary "${expected_release_id}")"
  echo "Redeploy recovery summary:"
  printf '%s\n' "${ACTION_RESULT_SUMMARY}"
  if verify_redeploy_fallback "${expected_release_id}"; then
    ACTION_RESULT_SOURCE="verification-fallback"
    echo "WARN: terminal action marker missing; runtime verification passed for ${COMPONENT} redeploy"
  else
    exit 1
  fi
elif (( action_wait_rc != 0 )); then
  exit "${action_wait_rc}"
fi

echo "Action result source: ${ACTION_RESULT_SOURCE}"
if [[ "${ACTION_RESULT_SOURCE}" == "artifact" ]]; then
  action_result_json="$(load_action_result_json "${ACTION_RESULT_REMOTE_PATH}" || true)"
  if [[ -n "${action_result_json}" ]]; then
    action_output_path="$(action_result_field "${action_result_json}" "outputPath")"
    if [[ -n "${action_output_path}" ]]; then
      echo "Action output path: ${action_output_path}"
    fi
  fi
fi

verify_runtime_assets_after_action

if [[ "${ACTION}" == "health" || "${ACTION}" == "start_all" || "${ACTION}" == "bootstrap" || "${ACTION}" == "start_component" ]]; then
  echo "Quick listener checks:"
  "${adb_cmd[@]}" shell "su -c 'ss -ltn 2>/dev/null | grep -E \":53 |:2222 |:2789 |:443 |:2790 \" || true'" || true
fi

if action_implies_remote_bringup; then
  identity_endpoint_status_summary
fi
