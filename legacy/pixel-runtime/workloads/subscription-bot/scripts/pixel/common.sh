#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/../.." && pwd)"
# shellcheck source=../../../../tools/pixel/transport.sh
source "${WORKSPACE_ROOT}/tools/pixel/transport.sh"

PIXEL_RUN_ID="${PIXEL_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM}"
export PIXEL_TRANSPORT ADB_SERIAL PIXEL_SSH_HOST PIXEL_SSH_PORT PIXEL_RUN_ID

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

transport_args() {
  local args=()
  pixel_transport_append_cli_args args
  printf '%s\n' "${args[@]}"
}

ensure_device() {
  if ! pixel_transport_require_device >/dev/null 2>&1; then
    log "Pixel transport is not ready (transport=${PIXEL_TRANSPORT})"
    exit 1
  fi
}

ensure_root() {
  ensure_device
  if ! pixel_transport_require_root >/dev/null 2>&1; then
    log "Root shell not available on target"
    exit 1
  fi
}

ensure_local_env() {
  if [[ ! -f "${REPO_ROOT}/.env" ]]; then
    if [[ -f "${REPO_ROOT}/.env.example" ]]; then
      cp "${REPO_ROOT}/.env.example" "${REPO_ROOT}/.env"
      log "Created .env from .env.example"
    else
      log "Missing .env and .env.example"
      exit 1
    fi
  fi
}

ensure_output_dirs() {
  bash "${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh"
  mkdir -p \
    "${REPO_ROOT}/output/pixel" \
    "${REPO_ROOT}/.artifacts/subscription-bot" \
    "${REPO_ROOT}/.artifacts/component-releases"
}
