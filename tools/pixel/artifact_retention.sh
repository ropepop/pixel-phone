#!/usr/bin/env bash

if [[ -n "${PIXEL_ARTIFACT_RETENTION_SH_LOADED:-}" ]]; then
  return 0
fi
PIXEL_ARTIFACT_RETENTION_SH_LOADED=1
PIXEL_ARTIFACT_RETENTION_HOURS="${PIXEL_ARTIFACT_RETENTION_HOURS:-72}"

pixel_workspace_cleanup_native() {
  local repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  local crate="${repo_root}/orchestrator/android-orchestrator/pixel-health"
  command -v cargo >/dev/null 2>&1 || {
    echo 'workspace cleanup requires cargo' >&2
    return 2
  }
  cargo build --quiet --locked --release --manifest-path "${crate}/Cargo.toml" \
    --target-dir "${crate}/target" --features workspace-cleanup \
    --bin pixel-workspace-cleanup -j 1 || return $?
  "${crate}/target/release/pixel-workspace-cleanup" "$@"
}

pixel_artifact_retention_warn() {
  printf '[%s] artifact retention: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

pixel_artifact_retention_prune() {
  local retention_hours="${1:-${PIXEL_ARTIFACT_RETENTION_HOURS}}"
  shift || true
  (( $# > 0 )) || return 0
  if ! pixel_workspace_cleanup_native retention "${retention_hours}" "$@"; then
    pixel_artifact_retention_warn 'stale artifact cleanup hit an unexpected error; continuing'
  fi
  return 0
}
