#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_ROOT="${REPO_ROOT}/android-orchestrator"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${WORKSPACE_ROOT}/tools/pixel/transport.sh"
DEPLOY_TIMING_REPORTER="${DEPLOY_TIMING_REPORTER:-${WORKSPACE_ROOT}/../ops/workloads/operational-logging/scripts/report-deployment.sh}"
APK_PATH="${APP_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
PKG="lv.jolkins.pixelorchestrator"
SUPERVISOR="${PKG}/.app.SupervisorService"
RUNTIME_ASSET_FRESHNESS_SCRIPT="${REPO_ROOT}/scripts/android/runtime_asset_freshness.sh"
ACTION_RESULT_REMOTE_DIR="/data/local/pixel-stack/run/orchestrator-action-results"
CANONICAL_ARTIFACT_ROOT="/data/local/pixel-stack/conf/runtime/artifacts/sha256"

ADB_SERIAL=""
ACTION="bootstrap"
COMPONENT=""
PROFILE="${ORCHESTRATOR_DEPLOY_PROFILE:-}"
PROFILE_EXPLICIT=0
if [[ -n "${ORCHESTRATOR_DEPLOY_PROFILE:-}" ]]; then
  PROFILE_EXPLICIT=1
fi
SKIP_BUILD=0
INSTALL_APK=0
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
ENABLE_TICKET_SERVICE=0
PIXEL_RUN_ID="${PIXEL_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM}"
RUNTIME_ASSET_REPAIR_REQUIRED=0
RUNTIME_ASSET_REPAIR_ACTION="none"
RUNTIME_ASSET_SCOPE=""
ACTION_RESULT_SOURCE="none"
ACTION_RESULT_REMOTE_PATH=""
ACTION_RESULT_LOG_MARKER_SEEN=0
ACTION_RESULT_LOGS=""
ACTION_RESULT_SUMMARY=""
ACTION_RESULT_JSON=""
ACTION_RESULT_OUTPUT_PATH=""
APK_BUILD_PERFORMED=0
APK_INSTALLED_THIS_RUN=0
DEPLOY_STARTED_MS=""
TIMINGS_PRINTED=0
declare -a PHASE_TIMINGS=()

now_ms() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print(time.monotonic_ns() // 1000000)'
  elif [[ -n "${EPOCHREALTIME:-}" ]]; then
    awk -v value="${EPOCHREALTIME}" 'BEGIN { printf "%.0f\n", value * 1000 }'
  else
    printf '%s\n' "$(( $(date +%s) * 1000 ))"
  fi
}

deployment_timing_safe_token() {
  local value="$1"
  local max_len="$2"
  [[ -n "${value}" && ${#value} -le "${max_len}" && "${value}" =~ ^[A-Za-z0-9][-A-Za-z0-9._:/@=]*$ ]]
}

deployment_timing_target() {
  if [[ -n "${COMPONENT}" ]]; then
    printf '%s\n' "${COMPONENT}"
  else
    printf 'all\n'
  fi
}

deployment_timing_metadata_is_safe() {
  local target=""
  target="$(deployment_timing_target)"
  deployment_timing_safe_token "${PIXEL_RUN_ID}" 120 &&
    deployment_timing_safe_token "${ACTION}" 80 &&
    deployment_timing_safe_token "${PROFILE}" 48 &&
    deployment_timing_safe_token "${target}" 160
}

deployment_timing_total_ms() {
  local finished_ms="$1"
  local total_ms=0
  if [[ -n "${DEPLOY_STARTED_MS}" ]]; then
    total_ms=$((finished_ms - DEPLOY_STARTED_MS))
  fi
  if (( total_ms < 0 )); then
    total_ms=0
  fi
  printf '%s\n' "${total_ms}"
}

emit_deployment_timing() {
  local event="$1"
  shift
  local target=""
  local python_bin=""
  [[ -x "${DEPLOY_TIMING_REPORTER}" ]] || return 0
  deployment_timing_metadata_is_safe || return 0
  python_bin="${DEPLOY_TIMING_PYTHON_BIN:-/usr/bin/python3}"
  if [[ ! -x "${python_bin}" ]]; then
    python_bin="$(command -v python3 || true)"
  fi
  [[ -n "${python_bin}" ]] || return 0
  target="$(deployment_timing_target)"
  # The reporter process is detached from the deploy, but it performs its one Spacetime call in
  # the foreground. This avoids losing the final event to a second detach while keeping timing
  # telemetry off the deploy command's critical path and unable to alter its result.
  "${python_bin}" -c \
    'import subprocess, sys; subprocess.Popen(sys.argv[1:], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True, start_new_session=True)' \
    "${DEPLOY_TIMING_REPORTER}" "${event}" \
    --run-id "${PIXEL_RUN_ID}" \
    --source pixel \
    --action "${ACTION}" \
    --profile "${PROFILE}" \
    --target "${target}" \
    "$@" \
    --wait >/dev/null 2>&1 || true
  return 0
}

record_phase_timing() {
  local name="$1"
  local started_ms="$2"
  local status="${3:-ok}"
  local finished_ms=""
  local duration_ms=""
  local total_ms=""
  finished_ms="$(now_ms)"
  duration_ms=$((finished_ms - started_ms))
  if (( duration_ms < 0 )); then
    duration_ms=0
  fi
  total_ms="$(deployment_timing_total_ms "${finished_ms}")"
  PHASE_TIMINGS+=("${name}=${status}=${duration_ms}=${total_ms}")
  printf 'Phase timing: %s=%sms\n' "${name}" "${duration_ms}"
}

deployment_timing_phase_bundle() {
  if (( ${#PHASE_TIMINGS[@]} == 0 )); then
    printf '-'
    return 0
  fi
  local IFS='@'
  printf '%s' "${PHASE_TIMINGS[*]}"
}

run_phase() {
  local name="$1"
  local started_ms=""
  local rc=0
  shift
  started_ms="$(now_ms)"
  set +e
  "$@"
  rc=$?
  set -e
  if (( rc == 0 )); then
    record_phase_timing "${name}" "${started_ms}" ok
  else
    record_phase_timing "${name}" "${started_ms}" failed
  fi
  return "${rc}"
}

print_deploy_timings() {
  local rc="${1:-$?}"
  local finished_ms=""
  local total_ms=""
  if (( TIMINGS_PRINTED == 1 )) || [[ -z "${DEPLOY_STARTED_MS}" ]]; then
    return "${rc}"
  fi
  TIMINGS_PRINTED=1
  finished_ms="$(now_ms)"
  total_ms=$((finished_ms - DEPLOY_STARTED_MS))
  printf 'Deploy profile: %s\n' "${PROFILE}"
  printf 'Total timing: deploy_orchestrator=%sms\n' "${total_ms}"
  return "${rc}"
}

finish_deploy_timing() {
  local rc=$?
  local finished_ms=""
  local total_ms=""
  local status="ok"
  trap - EXIT INT TERM
  cleanup_remote_deploy_staging
  print_deploy_timings "${rc}" || true
  case "${rc}" in
    0) status="ok" ;;
    130|143) status="cancelled" ;;
    *) status="failed" ;;
  esac
  finished_ms="$(now_ms)"
  total_ms="$(deployment_timing_total_ms "${finished_ms}")"
  emit_deployment_timing run-complete \
    --status "${status}" \
    --total-duration-ms "${total_ms}" \
    --phase-bundle "$(deployment_timing_phase_bundle)"
  return "${rc}"
}

deployment_timing_on_signal() {
  local signal_name="$1"
  trap - INT TERM
  case "${signal_name}" in
    INT) exit 130 ;;
    TERM) exit 143 ;;
  esac
}

cleanup_remote_deploy_staging() {
  pixel_transport_root_exec rm -rf \
    "/data/local/tmp/pixel-orchestrator-runtime-${PIXEL_RUN_ID}" \
    "/data/local/tmp/pixel-orchestrator-component-release-${PIXEL_RUN_ID}" \
    >/dev/null 2>&1 || true
  pixel_transport_root_exec find "${CANONICAL_ARTIFACT_ROOT}" \
    -maxdepth 1 -type f -name ".*.${PIXEL_RUN_ID}.tmp" -delete \
    >/dev/null 2>&1 || true
  pixel_transport_root_exec find "/data/local/pixel-stack/conf/runtime" \
    -maxdepth 4 -type f \
    \( -name ".runtime-manifest.${PIXEL_RUN_ID}.tmp" \
       -o -name ".runtime-manifest.previous.${PIXEL_RUN_ID}.tmp" \
       -o -name ".release-manifest.${PIXEL_RUN_ID}.tmp" \
       -o -name ".release-manifest.previous.${PIXEL_RUN_ID}.tmp" \) -delete \
    >/dev/null 2>&1 || true
}

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
                              (dns|ssh|vpn|ddns|remote|train_bot|satiksme_bot|site_notifier|subscription_bot|ticket_screen)
  --profile fast|standard|full
                              fast reuses unchanged APK/runtime state and, for ticket_screen redeploys,
                              proves only the local Ticket endpoint; standard preserves normal checks;
                              full preserves the strict rebuild/install/diagnostic path
                              (default: fast for ticket_screen redeploy, standard otherwise)
  --runtime-bundle-dir PATH   local runtime bundle dir containing runtime-manifest.json and artifacts/
  --component-release-dir PATH
                              local component release dir containing release-manifest.json and artifacts/
                              (staged alongside bootstrap, or used directly with redeploy_component)
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
  --enable-ticket-service     persist Ticket service reliability through the Android app;
                              only valid with --action redeploy_component --component ticket_screen
  --skip-build                do not build APK before deploy
  --install-apk               install the existing APK even when --skip-build is used
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
    --profile)
      shift
      PROFILE="${1:-}"
      PROFILE_EXPLICIT=1
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
    --enable-ticket-service)
      ENABLE_TICKET_SERVICE=1
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --install-apk)
      INSTALL_APK=1
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

if (( PROFILE_EXPLICIT == 0 )); then
  if [[ "${ACTION}" == "redeploy_component" && "${COMPONENT}" == "ticket_screen" ]]; then
    PROFILE="fast"
  else
    PROFILE="standard"
  fi
fi

case "${PROFILE}" in
  fast|standard|full) ;;
  *)
    echo "Unsupported --profile: ${PROFILE}" >&2
    usage >&2
    exit 2
    ;;
esac

fast_ticket_redeploy_enabled() {
  [[ "${PROFILE}" == "fast" && "${ACTION}" == "redeploy_component" && "${COMPONENT}" == "ticket_screen" ]]
}

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
      dns|ssh|vpn|ddns|remote|train_bot|satiksme_bot|site_notifier|subscription_bot|ticket_screen) ;;
      *)
        echo "--component must be one of: dns|ssh|vpn|ddns|remote|train_bot|satiksme_bot|site_notifier|subscription_bot|ticket_screen" >&2
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

if (( ENABLE_TICKET_SERVICE == 1 )) &&
  [[ "${ACTION}" != "redeploy_component" || "${COMPONENT}" != "ticket_screen" ]]; then
  echo "--enable-ticket-service is only valid with --action redeploy_component --component ticket_screen" >&2
  exit 2
fi

DEPLOY_STARTED_MS="$(now_ms)"
trap finish_deploy_timing EXIT
trap 'deployment_timing_on_signal INT' INT
trap 'deployment_timing_on_signal TERM' TERM
emit_deployment_timing run-start --total-duration-ms 0

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

orchestrator_apk_needs_build() {
  [[ -f "${APK_PATH}" ]] || return 0
  find "${APP_ROOT}" \
    \( -type d \( -name build -o -name .gradle \) -prune \) -o \
    \( -type f \
      \( -path '*/src/*' -o -name '*.gradle' -o -name '*.gradle.kts' -o -name 'gradle.properties' -o -name 'gradle-wrapper.properties' \) \
      -newer "${APK_PATH}" -print -quit \
    \) | grep -q .
}

if (( SKIP_BUILD == 0 )); then
  if [[ "${PROFILE}" == "fast" ]] && ! orchestrator_apk_needs_build; then
    echo "Skipping orchestrator APK build (fast profile: existing APK is current)"
  else
    run_phase build_apk "${REPO_ROOT}/scripts/android/build_orchestrator_apk.sh" --profile "${PROFILE}"
    APK_BUILD_PERFORMED=1
  fi
fi

if [[ ! -f "${APK_PATH}" ]]; then
  echo "APK not found: ${APK_PATH}" >&2
  exit 1
fi

connect_started_ms="$(now_ms)"
pixel_transport_require_device >/dev/null
adb_cmd=(pixel_transport_adb_compat)

if [[ "$(pixel_transport_selected)" == "adb" ]]; then
  echo "Using transport: adb (${ADB_SERIAL})"
else
  echo "Using transport: ssh (${PIXEL_SSH_HOST}:${PIXEL_SSH_PORT})"
fi
echo "PIXEL_RUN_ID=${PIXEL_RUN_ID}"
"${adb_cmd[@]}" get-state >/dev/null
record_phase_timing connect_device "${connect_started_ms}"

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

installed_apk_path() {
  "${adb_cmd[@]}" shell "pm path ${PKG}" 2>/dev/null |
    tr -d '\r' |
    sed -n 's/^package://p' |
    sed -n '1p'
}

installed_apk_matches_local() {
  local remote_path="$1"
  local local_hash=""
  local remote_hash=""
  [[ -n "${remote_path}" ]] || return 1
  local_hash="$(sha256_file "${APK_PATH}")"
  remote_hash="$(pixel_transport_remote_sha256_file "${remote_path}" 2>/dev/null || true)"
  [[ -n "${remote_hash}" && "${remote_hash}" == "${local_hash}" ]]
}

install_started_ms="$(now_ms)"
device_apk_path="$(installed_apk_path || true)"
install_reason=""
if (( INSTALL_APK == 1 )); then
  install_reason="requested"
elif [[ -z "${device_apk_path}" ]]; then
  install_reason="package_missing"
elif [[ "${PROFILE}" == "fast" ]]; then
  if installed_apk_matches_local "${device_apk_path}"; then
    echo "Skipping APK install (fast profile: installed APK matches local artifact)"
  else
    install_reason="artifact_changed_or_unverifiable"
  fi
elif (( SKIP_BUILD == 0 )); then
  install_reason="build_completed"
else
  echo "Skipping APK install (--skip-build and package already present)"
fi

if [[ -n "${install_reason}" ]]; then
  echo "Installing orchestrator APK (reason=${install_reason})"
  "${adb_cmd[@]}" install -r "${APK_PATH}"
  APK_INSTALLED_THIS_RUN=1
fi
record_phase_timing install_apk "${install_started_ms}"

# Keep app alive under battery optimizations whitelist when possible.
whitelist_started_ms="$(now_ms)"
"${adb_cmd[@]}" shell "cmd deviceidle whitelist +${PKG}" >/dev/null 2>&1 || true
record_phase_timing battery_whitelist "${whitelist_started_ms}"

resolve_orchestrator_package_uid() {
  local uid=""

  uid="$(
    pixel_transport_shell "cmd package list packages -U ${PKG} 2>/dev/null | sed -n 's/.*uid://p' | sed -n '1p'" \
      2>/dev/null | tr -d '\r' | tr -d '[:space:]'
  )" || true
  if [[ "${uid}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${uid}"
    return 0
  fi

  uid="$(
    pixel_transport_shell "dumpsys package ${PKG} 2>/dev/null | sed -n 's/.*userId=\\([0-9][0-9]*\\).*/\\1/p' | sed -n '1p'" \
      2>/dev/null | tr -d '\r' | tr -d '[:space:]'
  )" || true
  if [[ "${uid}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${uid}"
    return 0
  fi

  return 1
}

suppress_superuser_grant_toasts() {
  local uid=""
  local sql=""
  local output=""

  uid="$(resolve_orchestrator_package_uid || true)"
  if [[ -z "${uid}" ]]; then
    echo "Warning: could not resolve ${PKG} uid for superuser toast suppression" >&2
    return 0
  fi

  if ! pixel_transport_root_shell "command -v magisk >/dev/null 2>&1" >/dev/null 2>&1; then
    echo "Magisk sqlite unavailable; skipping superuser toast suppression"
    return 0
  fi

  sql="UPDATE policies SET notification=0 WHERE uid=2000; INSERT OR IGNORE INTO policies(uid, policy, until, logging, notification) VALUES(${uid}, 2, 0, 1, 0); UPDATE policies SET notification=0 WHERE uid=${uid};"
  if output="$(pixel_transport_root_shell "magisk --sqlite $(pixel_transport_single_quote "${sql}")" 2>&1)"; then
    echo "Superuser toast notifications suppressed for ${PKG} uid=${uid}"
    return 0
  fi

  echo "Warning: failed to suppress superuser toast notifications for ${PKG} uid=${uid}" >&2
  if [[ -n "${output}" ]]; then
    echo "${output}" >&2
  fi
  return 0
}

if [[ "${PROFILE}" != "fast" ]] || (( APK_INSTALLED_THIS_RUN == 1 )); then
  suppress_started_ms="$(now_ms)"
suppress_superuser_grant_toasts || true
  record_phase_timing suppress_superuser_toasts "${suppress_started_ms}"
else
  echo "Skipping superuser toast policy refresh (fast profile: APK unchanged)"
fi

repair_phone_automation_permissions() {
  if [[ "$(pixel_transport_selected)" != "adb" ]]; then
    return 0
  fi

  local accessibility_component="lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService"
  local notification_component="lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationNotificationListenerService"
  local current=""
  local next=""
  local enabled_flag=""
  local attempt=""

  merge_enabled_service_component() {
    local service_list="$1"
    local component_name="$2"

    python3 - "${service_list}" "${component_name}" <<'PY'
import sys

service_list = sys.argv[1]
component_name = sys.argv[2]
parts = [
    entry.strip()
    for entry in service_list.split(":")
    if entry.strip() and entry.strip().lower() != "null"
]
if component_name not in parts:
    parts.append(component_name)
print(":".join(parts))
PY
  }

  for attempt in 1 2 3; do
    pixel_transport_root_shell "cmd appops set ${PKG} ACCESS_RESTRICTED_SETTINGS allow" >/dev/null || true

    current="$(pixel_transport_root_shell "settings get secure enabled_accessibility_services" 2>/dev/null | tr -d '\r' | sed -n '1p')"
    next="$(merge_enabled_service_component "${current}" "${accessibility_component}")"
    pixel_transport_root_shell "settings put secure enabled_accessibility_services $(pixel_transport_single_quote "${next}")" >/dev/null
    pixel_transport_root_shell "settings put secure accessibility_enabled 1" >/dev/null

    current="$(pixel_transport_root_shell "settings get secure enabled_notification_listeners" 2>/dev/null | tr -d '\r' | sed -n '1p')"
    next="$(merge_enabled_service_component "${current}" "${notification_component}")"
    pixel_transport_root_shell "settings put secure enabled_notification_listeners $(pixel_transport_single_quote "${next}")" >/dev/null

    sleep 1
    current="$(pixel_transport_root_shell "settings get secure enabled_accessibility_services" 2>/dev/null | tr -d '\r' | sed -n '1p')"
    enabled_flag="$(pixel_transport_root_shell "settings get secure accessibility_enabled" 2>/dev/null | tr -d '\r' | sed -n '1p')"
    if [[ ":${current}:" == *":${accessibility_component}:"* && "${enabled_flag}" == "1" ]]; then
      return 0
    fi
  done

  echo "Warning: accessibility permission repair did not stick" >&2
  return 1
}

should_repair_phone_automation_permissions() {
  if [[ "$(pixel_transport_selected)" != "adb" ]]; then
    return 1
  fi
  if [[ "${PROFILE}" != "fast" ]] || (( APK_INSTALLED_THIS_RUN == 1 )); then
    return 0
  fi
  ! phone_automation_permissions_ready
}

phone_automation_permissions_ready() {
  local accessibility_component="lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService"
  local notification_component="lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationNotificationListenerService"
  local state=""
  local accessibility_services=""
  local accessibility_enabled=""
  local notification_listeners=""

  state="$(pixel_transport_root_shell 'settings get secure enabled_accessibility_services; settings get secure accessibility_enabled; settings get secure enabled_notification_listeners' 2>/dev/null | tr -d '\r' || true)"
  accessibility_services="$(printf '%s\n' "${state}" | sed -n '1p')"
  accessibility_enabled="$(printf '%s\n' "${state}" | sed -n '2p')"
  notification_listeners="$(printf '%s\n' "${state}" | sed -n '3p')"

  [[ ":${accessibility_services}:" == *":${accessibility_component}:"* ]] || return 1
  [[ "${accessibility_enabled}" == "1" ]] || return 1
  [[ ":${notification_listeners}:" == *":${notification_component}:"* ]]
}

remote_sha256_file() {
  pixel_transport_remote_sha256_file "$1"
}

load_action_result_json() {
  local remote_path="$1"
  local payload=""
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
    dns) printf 'dns\n' ;;
    ssh) printf 'ssh\n' ;;
    vpn) printf 'vpn\n' ;;
    ticket_screen) printf 'ticket_screen\n' ;;
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

verify_live_dns_runtime_assets() {
  local local_hash="" host_hash="" chroot_hash=""
  local_hash="$(sha256_file "${APP_ROOT}/app/src/main/assets/runtime/templates/rooted/adguardhome-start")"
  host_hash="$(remote_sha256_file "/data/local/pixel-stack/templates/rooted/adguardhome-start")"
  chroot_hash="$(remote_sha256_file "/data/local/pixel-stack/chroots/adguardhome/usr/local/bin/adguardhome-start")"
  [[ "${host_hash}" == "${local_hash}" ]] || return 1
  [[ "${chroot_hash}" == "${local_hash}" ]] || return 1
}

verify_live_dns_runtime() {
  verify_live_dns_runtime_assets || return 1
  "${adb_cmd[@]}" shell "su -c 'ss -ltn 2>/dev/null | grep -Eq \"[.:]53[[:space:]]\" && ss -ltn 2>/dev/null | grep -Eq \"127\\.0\\.0\\.1:8080[[:space:]]\" && chroot /data/local/pixel-stack/chroots/adguardhome /usr/local/bin/adguardhome-start --remote-healthcheck >/dev/null 2>&1'" >/dev/null 2>&1
}

dns_runtime_enabled_on_device() {
  local config_json=""
  config_json="$(pixel_transport_remote_cat "/data/local/pixel-stack/conf/orchestrator-config-v1.json" 2>/dev/null || true)"
  PIXEL_ORCHESTRATOR_CONFIG_JSON="${config_json}" python3 - <<'PY'
import json
import os
import sys

try:
    config = json.loads(os.environ.get("PIXEL_ORCHESTRATOR_CONFIG_JSON") or "{}")
except (TypeError, ValueError):
    sys.exit(0)

modules = config.get("modules") or {}
dns = modules.get("dns") or {}
sys.exit(0 if dns.get("enabled", True) else 1)
PY
}

verify_dns_runtime_stopped() {
  pixel_transport_root_shell '
for pid_file in /data/local/pixel-stack/run/adguardhome-service-loop.pid /data/local/pixel-stack/run/adguardhome-host.pid; do
  [ -f "$pid_file" ] || continue
  pid=$(sed -n "1p" "$pid_file" 2>/dev/null | tr -d "\r" || true)
  [ -n "$pid" ] || continue
  if kill -0 "$pid" >/dev/null 2>&1; then
    exit 1
  fi
done
if ps -A -o NAME= 2>/dev/null | grep -Fx "AdGuardHome" >/dev/null 2>&1; then
  exit 1
fi
exit 0
' >/dev/null 2>&1
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
  if (( rc == 3 )) && runtime_action_repairs_assets; then
    local freshness_retry=0
    while (( freshness_retry < 5 && rc == 3 )); do
      sleep 0.2
      freshness_retry=$((freshness_retry + 1))
      rc=0
      output="$("${freshness_cmd[@]}" 2>&1)" || rc=$?
    done
  fi
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
      if dns_runtime_enabled_on_device; then
        if verify_live_dns_runtime; then
          echo "Live DNS runtime after action: converged"
        else
          echo "Live DNS runtime after action: stale" >&2
          identity_endpoint_status_summary >&2
          return 1
        fi
      elif verify_live_dns_runtime_assets && verify_dns_runtime_stopped; then
        echo "Live DNS runtime after action: disabled as configured"
      else
        echo "Disabled DNS runtime after action: state mismatch" >&2
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

run_phase runtime_precheck verify_runtime_assets_pre_action

provision_file() {
  local local_path="$1"
  local remote_target="$2"
  local _stage_name="$3"

  [[ -f "${local_path}" ]] || return 0
  pixel_transport_push "${local_path}" "${remote_target}" >/dev/null
  pixel_transport_root_exec chmod 600 "${remote_target}" >/dev/null
}

component_release_owner_component() {
  printf '%s\n' "${1}"
}

component_requires_release_manifest() {
  case "${1}" in
    ddns|ticket_screen) return 1 ;;
    *) return 0 ;;
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

component_release_manifest_artifacts() {
  local manifest_path="$1"
  python3 - "${manifest_path}" <<'PY'
import json
import os
import re
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)

for artifact in payload.get("artifacts") or []:
    file_name = str(artifact.get("fileName") or "").strip()
    sha256 = str(artifact.get("sha256") or "").strip().lower()
    url = str(artifact.get("url") or "").strip()
    if not file_name or file_name != os.path.basename(file_name):
        raise SystemExit(f"invalid component artifact fileName: {file_name!r}")
    if not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise SystemExit(f"invalid component artifact sha256 for {file_name}")
    expected_url = f"/data/local/pixel-stack/conf/runtime/artifacts/sha256/{sha256}"
    if url != expected_url:
        raise SystemExit(
            f"component artifact {file_name} must use canonical content-addressed url {expected_url}"
        )
    print(f"{file_name}\t{sha256}")
PY
}

stage_runtime_bundle() {
  local bundle_dir="$1"
  local manifest_path="${bundle_dir}/runtime-manifest.json"
  local artifacts_dir="${bundle_dir}/artifacts"
  local stage_root="/data/local/tmp/pixel-orchestrator-runtime-${PIXEL_RUN_ID}"
  local target_root="/data/local/pixel-stack/conf/runtime"
  local artifact_count=0
  local transferred_count=0
  local reused_count=0
  local artifact_name=""
  local expected_sha=""
  local local_artifact=""
  local remote_artifact=""
  local local_sha=""
  local remote_sha=""

  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null
  pixel_transport_root_exec mkdir -p "${stage_root}/artifacts" >/dev/null
  pixel_transport_push "${manifest_path}" "${stage_root}/runtime-manifest.json" >/dev/null

  while IFS=$'\t' read -r artifact_name expected_sha; do
    [[ -n "${artifact_name}" ]] || continue
    artifact_count=$((artifact_count + 1))
    local_artifact="${artifacts_dir}/${artifact_name}"
    [[ -f "${local_artifact}" ]] || {
      echo "Runtime artifact missing from bundle: ${local_artifact}" >&2
      exit 1
    }
    local_sha="$(sha256_file "${local_artifact}")"
    if [[ "${local_sha}" != "${expected_sha}" ]]; then
      echo "Runtime artifact checksum mismatch before staging: ${artifact_name}" >&2
      exit 1
    fi
    remote_artifact="${CANONICAL_ARTIFACT_ROOT}/${expected_sha}"
    remote_sha="$(remote_sha256_file "${remote_artifact}" 2>/dev/null || true)"
    if [[ "${remote_sha}" == "${expected_sha}" ]]; then
      reused_count=$((reused_count + 1))
      continue
    fi
    pixel_transport_push "${local_artifact}" "${stage_root}/artifacts/${artifact_name}" >/dev/null
    transferred_count=$((transferred_count + 1))
  done < <(component_release_manifest_artifacts "${manifest_path}")

  if (( artifact_count == 0 )); then
    echo "Runtime bundle artifacts/ is empty: ${artifacts_dir}" >&2
    exit 1
  fi

  pixel_transport_root_exec mkdir -p "${target_root}" "${CANONICAL_ARTIFACT_ROOT}" >/dev/null
  pixel_transport_root_exec cp \
    "${stage_root}/runtime-manifest.json" \
    "${target_root}/.runtime-manifest.${PIXEL_RUN_ID}.tmp"
  pixel_transport_root_exec chmod 600 \
    "${target_root}/.runtime-manifest.${PIXEL_RUN_ID}.tmp" >/dev/null
  while IFS=$'\t' read -r artifact_name expected_sha; do
    [[ -n "${artifact_name}" ]] || continue
    if pixel_transport_root_exec test -f "${stage_root}/artifacts/${artifact_name}" >/dev/null 2>&1; then
      pixel_transport_root_exec cp "${stage_root}/artifacts/${artifact_name}" "${CANONICAL_ARTIFACT_ROOT}/.${expected_sha}.${PIXEL_RUN_ID}.tmp"
      pixel_transport_root_exec chmod 644 "${CANONICAL_ARTIFACT_ROOT}/.${expected_sha}.${PIXEL_RUN_ID}.tmp" >/dev/null
      pixel_transport_root_exec mv "${CANONICAL_ARTIFACT_ROOT}/.${expected_sha}.${PIXEL_RUN_ID}.tmp" "${CANONICAL_ARTIFACT_ROOT}/${expected_sha}"
    fi
    remote_sha="$(remote_sha256_file "${CANONICAL_ARTIFACT_ROOT}/${expected_sha}" 2>/dev/null || true)"
    if [[ "${remote_sha}" != "${expected_sha}" ]]; then
      echo "Runtime artifact failed canonical checksum verification: ${artifact_name}" >&2
      exit 1
    fi
  done < <(component_release_manifest_artifacts "${manifest_path}")
  if pixel_transport_root_exec test -s "${target_root}/runtime-manifest.json" >/dev/null 2>&1; then
    pixel_transport_root_exec cp \
      "${target_root}/runtime-manifest.json" \
      "${target_root}/.runtime-manifest.previous.${PIXEL_RUN_ID}.tmp"
    pixel_transport_root_exec chmod 600 \
      "${target_root}/.runtime-manifest.previous.${PIXEL_RUN_ID}.tmp" >/dev/null
    pixel_transport_root_exec mv \
      "${target_root}/.runtime-manifest.previous.${PIXEL_RUN_ID}.tmp" \
      "${target_root}/runtime-manifest.previous.json"
  fi
  pixel_transport_root_exec mv \
    "${target_root}/.runtime-manifest.${PIXEL_RUN_ID}.tmp" \
    "${target_root}/runtime-manifest.json"
  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null 2>&1 || true
  echo "Runtime bundle staged: artifacts=${artifact_count} transferred=${transferred_count} reused=${reused_count}"
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
  local transferred_count=0
  local reused_count=0
  local artifact_name=""
  local expected_sha=""
  local local_artifact=""
  local remote_artifact=""
  local local_sha=""
  local remote_sha=""

  storage_component="$(component_release_owner_component "${requested_component}")"
  device_component_root="/data/local/pixel-stack/conf/runtime/components/${storage_component}"

  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null
  pixel_transport_root_exec mkdir -p "${stage_root}/artifacts" >/dev/null
  pixel_transport_push "${manifest_path}" "${stage_root}/release-manifest.json" >/dev/null

  while IFS=$'\t' read -r artifact_name expected_sha; do
    [[ -n "${artifact_name}" ]] || continue
    artifact_count=$((artifact_count + 1))
    local_artifact="${artifacts_dir}/${artifact_name}"
    remote_artifact="${CANONICAL_ARTIFACT_ROOT}/${expected_sha}"

    if [[ -f "${local_artifact}" ]]; then
      local_sha="$(sha256_file "${local_artifact}")"
      if [[ "${local_sha}" != "${expected_sha}" ]]; then
        echo "Component artifact checksum mismatch before staging: ${artifact_name}" >&2
        exit 1
      fi
    fi

    remote_sha="$(remote_sha256_file "${remote_artifact}" 2>/dev/null || true)"
    if [[ "${remote_sha}" == "${expected_sha}" ]]; then
      reused_count=$((reused_count + 1))
      echo "Reusing verified device artifact: ${artifact_name}"
      continue
    fi
    if [[ ! -f "${local_artifact}" ]]; then
      echo "Component artifact ${artifact_name} is absent locally and the device copy does not match ${expected_sha}." >&2
      exit 1
    fi
    pixel_transport_push "${local_artifact}" "${stage_root}/artifacts/${artifact_name}" >/dev/null
    transferred_count=$((transferred_count + 1))
  done < <(component_release_manifest_artifacts "${manifest_path}")

  if (( artifact_count == 0 )); then
    echo "Component release manifest has no artifacts: ${manifest_path}" >&2
    exit 1
  fi

  pixel_transport_root_exec mkdir -p "/data/local/pixel-stack/conf/runtime/components" >/dev/null
  pixel_transport_root_exec mkdir -p "${device_component_root}" "${CANONICAL_ARTIFACT_ROOT}" >/dev/null
  while IFS=$'\t' read -r artifact_name expected_sha; do
    [[ -n "${artifact_name}" ]] || continue
    if ! pixel_transport_root_exec test -f "${stage_root}/artifacts/${artifact_name}" >/dev/null 2>&1; then
      continue
    fi
    pixel_transport_root_exec cp "${stage_root}/artifacts/${artifact_name}" "${CANONICAL_ARTIFACT_ROOT}/.${expected_sha}.${PIXEL_RUN_ID}.tmp"
    pixel_transport_root_exec chmod 644 "${CANONICAL_ARTIFACT_ROOT}/.${expected_sha}.${PIXEL_RUN_ID}.tmp" >/dev/null
    pixel_transport_root_exec mv "${CANONICAL_ARTIFACT_ROOT}/.${expected_sha}.${PIXEL_RUN_ID}.tmp" "${CANONICAL_ARTIFACT_ROOT}/${expected_sha}"
  done < <(component_release_manifest_artifacts "${manifest_path}")

  while IFS=$'\t' read -r artifact_name expected_sha; do
    [[ -n "${artifact_name}" ]] || continue
    remote_sha="$(remote_sha256_file "${CANONICAL_ARTIFACT_ROOT}/${expected_sha}" 2>/dev/null || true)"
    if [[ "${remote_sha}" != "${expected_sha}" ]]; then
      echo "Component artifact failed device checksum verification: ${artifact_name}" >&2
      exit 1
    fi
  done < <(component_release_manifest_artifacts "${manifest_path}")

  pixel_transport_root_exec cp "${stage_root}/release-manifest.json" "${device_component_root}/.release-manifest.${PIXEL_RUN_ID}.tmp"
  pixel_transport_root_exec chmod 600 "${device_component_root}/.release-manifest.${PIXEL_RUN_ID}.tmp" >/dev/null
  if pixel_transport_root_exec test -s "${device_component_root}/release-manifest.json" >/dev/null 2>&1; then
    pixel_transport_root_exec cp \
      "${device_component_root}/release-manifest.json" \
      "${device_component_root}/.release-manifest.previous.${PIXEL_RUN_ID}.tmp"
    pixel_transport_root_exec chmod 600 \
      "${device_component_root}/.release-manifest.previous.${PIXEL_RUN_ID}.tmp" >/dev/null
    pixel_transport_root_exec mv \
      "${device_component_root}/.release-manifest.previous.${PIXEL_RUN_ID}.tmp" \
      "${device_component_root}/release-manifest.previous.json"
  fi
  pixel_transport_root_exec mv "${device_component_root}/.release-manifest.${PIXEL_RUN_ID}.tmp" "${device_component_root}/release-manifest.json"
  pixel_transport_root_exec rm -rf "${stage_root}" >/dev/null 2>&1 || true
  echo "Component release staged: artifacts=${artifact_count} transferred=${transferred_count} reused=${reused_count}"
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
  local timeout_ms=$((timeout_sec * 1000))
  local elapsed_ms=0
  local poll_ms=500
  local poll_sleep="0.5"
  local next_log_scan_ms=1000
  local log_scan_interval_ms=2000
  local log_rc=1
  local logs=""
  local action_logs=""
  local marker="command_accepted action=${ACTION} component=${COMPONENT} run_id=${PIXEL_RUN_ID}"
  local marker_line=""
  local scan_logs=""
  local action_result_json=""
  ACTION_RESULT_SOURCE="none"
  ACTION_RESULT_LOG_MARKER_SEEN=0
  ACTION_RESULT_LOGS=""
  ACTION_RESULT_SUMMARY=""
  ACTION_RESULT_JSON=""
  ACTION_RESULT_OUTPUT_PATH=""

  if [[ "${PROFILE}" == "fast" ]]; then
    poll_ms=200
    poll_sleep="0.2"
  fi

  inspect_current_action_logs() {
    logs="$(pixel_transport_shell "logcat -d -v time | grep -E 'OrchestratorActionReceiver|SupervisorService' | tail -n 200" || true)"
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
      return 2
    fi
    if grep -Fq "command_action=${ACTION} component=${COMPONENT} success=true" <<<"${scan_logs}"; then
      ACTION_RESULT_SOURCE="log"
      ACTION_RESULT_LOGS="${scan_logs}"
      echo "Action ${ACTION} reported SUCCESS"
      return 0
    fi
    return 1
  }

  while (( elapsed_ms < timeout_ms )); do
    if action_result_json="$(load_action_result_json "${ACTION_RESULT_REMOTE_PATH}")"; then
      ACTION_RESULT_LOGS="${ACTION_RESULT_LOGS:-${logs}}"
      ACTION_RESULT_JSON="${action_result_json}"
      ACTION_RESULT_OUTPUT_PATH="$(action_result_field "${action_result_json}" "outputPath")"
      if ! pixel_transport_root_exec rm -f "${ACTION_RESULT_REMOTE_PATH}" >/dev/null 2>&1; then
        echo "WARN: consumed action result could not be removed; the 24-hour cleanup fallback will remove it" >&2
      fi
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

    if (( elapsed_ms >= next_log_scan_ms )); then
      log_rc=0
      inspect_current_action_logs || log_rc=$?
      case "${log_rc}" in
        0) return 0 ;;
        2) return 1 ;;
      esac
      next_log_scan_ms=$((elapsed_ms + log_scan_interval_ms))
    fi
    sleep "${poll_sleep}"
    elapsed_ms=$((elapsed_ms + poll_ms))
  done

  log_rc=0
  inspect_current_action_logs || log_rc=$?
  case "${log_rc}" in
    0) return 0 ;;
    2) return 1 ;;
  esac
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
  local supervisor_action=""
  local rc=0

  case "${ACTION}" in
    bootstrap) supervisor_action="lv.jolkins.pixelorchestrator.action.BOOTSTRAP" ;;
    start_all) supervisor_action="lv.jolkins.pixelorchestrator.action.START_ALL" ;;
    stop_all) supervisor_action="lv.jolkins.pixelorchestrator.action.STOP_ALL" ;;
    health) supervisor_action="lv.jolkins.pixelorchestrator.action.HEALTH" ;;
    start_component) supervisor_action="lv.jolkins.pixelorchestrator.action.START_COMPONENT" ;;
    stop_component) supervisor_action="lv.jolkins.pixelorchestrator.action.STOP_COMPONENT" ;;
    restart_component) supervisor_action="lv.jolkins.pixelorchestrator.action.RESTART_COMPONENT" ;;
    redeploy_component) supervisor_action="lv.jolkins.pixelorchestrator.action.REDEPLOY_COMPONENT" ;;
    health_component) supervisor_action="lv.jolkins.pixelorchestrator.action.HEALTH_COMPONENT" ;;
    sync_ddns) supervisor_action="lv.jolkins.pixelorchestrator.action.SYNC_DDNS" ;;
    export_bundle) supervisor_action="lv.jolkins.pixelorchestrator.action.EXPORT_BUNDLE" ;;
    cleanup) supervisor_action="lv.jolkins.pixelorchestrator.action.CLEANUP" ;;
    *)
      echo "Unsupported action dispatch: ${ACTION}" >&2
      return 1
      ;;
  esac

  shell_cmd="am start-foreground-service -n ${SUPERVISOR} -a ${supervisor_action} --es orchestrator_action ${ACTION} --es pixel_run_id ${PIXEL_RUN_ID}"
  if [[ -n "${COMPONENT}" ]]; then
    shell_cmd="${shell_cmd} --es orchestrator_component ${COMPONENT}"
  fi
  if fast_ticket_redeploy_enabled; then
    shell_cmd="${shell_cmd} --ez orchestrator_fast_ticket_redeploy true"
  fi
  if (( ENABLE_TICKET_SERVICE == 1 )); then
    shell_cmd="${shell_cmd} --ez orchestrator_enable_ticket_service true"
  fi
  if (( DRY_RUN == 1 )); then
    shell_cmd="${shell_cmd} --ez orchestrator_dry_run true"
  fi

  set +e
  dispatch_output="$(pixel_transport_root_shell "${shell_cmd}" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "${dispatch_output}"

  if (( rc != 0 )) || ! grep -Eq 'Starting service:|Foreground service' <<<"${dispatch_output}"; then
    echo "Failed to dispatch action ${ACTION} via ${SUPERVISOR}" >&2
    return 1
  fi
}

staging_started_ms="$(now_ms)"
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
record_phase_timing provision_and_stage "${staging_started_ms}"

if [[ "${ACTION}" == "bootstrap" ]]; then
  ensure_runtime_manifest_staged
fi
if [[ "${ACTION}" == "redeploy_component" ]] && component_requires_release_manifest "${COMPONENT}"; then
  ensure_component_release_manifest_staged "${COMPONENT}"
fi

ACTION_RESULT_REMOTE_PATH="$(action_result_remote_path)"
pixel_transport_root_exec mkdir -p "${ACTION_RESULT_REMOTE_DIR}" >/dev/null 2>&1 || true
pixel_transport_root_exec rm -f "${ACTION_RESULT_REMOTE_PATH}" >/dev/null 2>&1 || true

process_prepare_started_ms="$(now_ms)"
if [[ "${PROFILE}" == "fast" ]]; then
  echo "Skipping app force-stop and log reset (fast profile)"
else
  pixel_transport_shell "am force-stop ${PKG}" >/dev/null 2>&1 || true
  pixel_transport_shell "logcat -c" >/dev/null 2>&1 || true
fi
record_phase_timing process_prepare "${process_prepare_started_ms}"

permissions_started_ms="$(now_ms)"
if should_repair_phone_automation_permissions; then
  repair_phone_automation_permissions || true
else
  echo "Skipping phone automation permission repair (already ready or not required)"
fi
record_phase_timing permission_readiness "${permissions_started_ms}"

dispatch_started_ms="$(now_ms)"
dispatch_orchestrator_action
record_phase_timing action_dispatch "${dispatch_started_ms}"

echo "Triggered action: ${ACTION}"
if fast_ticket_redeploy_enabled; then
  echo "Fast Ticket redeploy: verifying the restarted local Ticket endpoint; standard/full retain full cross-component validation"
fi
if [[ "${PROFILE}" == "full" ]]; then
  echo "Recent app logs:"
  run_phase initial_log_diagnostics pixel_transport_shell "logcat -d -v time | grep -E 'OrchestratorActionReceiver|SupervisorService|OrchestratorMain' | tail -n 120" || true
fi

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
if fast_ticket_redeploy_enabled && [[ -z "${ORCHESTRATOR_ACTION_TIMEOUT_SEC:-}" ]]; then
  wait_timeout_sec=40
fi
action_wait_rc=0
run_phase action_wait wait_for_action_result "${wait_timeout_sec}" || action_wait_rc=$?

if (( action_wait_rc != 0 )) &&
  [[ "${ACTION}" == "redeploy_component" && "${ACTION_RESULT_SOURCE}" == "none" ]] &&
  component_requires_release_manifest "${COMPONENT}"; then
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
if [[ "${ACTION_RESULT_SOURCE}" == "artifact" && -n "${ACTION_RESULT_OUTPUT_PATH}" ]]; then
  echo "Action output path: ${ACTION_RESULT_OUTPUT_PATH}"
fi

run_phase runtime_postcheck verify_runtime_assets_after_action

if [[ "${ACTION}" == "health" || "${ACTION}" == "start_all" || "${ACTION}" == "bootstrap" || "${ACTION}" == "start_component" ]]; then
  echo "Quick listener checks:"
  "${adb_cmd[@]}" shell "su -c 'ss -ltn 2>/dev/null | grep -E \":53 |:2222 |:2789 |:443 |:2790 \" || true'" || true
fi

if action_implies_remote_bringup; then
  identity_endpoint_status_summary
fi
