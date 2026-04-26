#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
ORCHESTRATOR_BUILD_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/build_orchestrator_apk.sh"
ORCHESTRATOR_APK_PATH="${ORCHESTRATOR_REPO}/android-orchestrator/app/build/outputs/apk/debug/app-debug.apk"

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
if [[ ! -x "${ORCHESTRATOR_BUILD_SCRIPT}" ]]; then
  log "Missing orchestrator build script: ${ORCHESTRATOR_BUILD_SCRIPT}"
  exit 1
fi

log "Building current orchestrator APK so refreshed runtime assets match the local repo."
"${ORCHESTRATOR_BUILD_SCRIPT}"

if [[ ! -f "${ORCHESTRATOR_APK_PATH}" ]]; then
  log "Missing orchestrator APK after build: ${ORCHESTRATOR_APK_PATH}"
  exit 1
fi

log "Installing current orchestrator APK before component-scoped runtime refresh."
pixel_transport_install_apk "${ORCHESTRATOR_APK_PATH}" >/dev/null

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

run_orchestrator_action restart_component dns
run_orchestrator_action restart_component train_bot
run_orchestrator_action health
run_orchestrator_action health_component dns
run_orchestrator_action health_component train_bot

log "Runtime asset refresh completed: rooted DNS assets and Train Bot assets are current, and health checks passed."
