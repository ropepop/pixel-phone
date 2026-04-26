#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

CANON_ROOT="${REPO_ROOT}/orchestrator/templates"
ASSET_ROOT="${REPO_ROOT}/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates"

sync_group() {
  local name="$1"
  local src="${CANON_ROOT}/${name}"
  local dst="${ASSET_ROOT}/${name}"
  if [[ ! -d "${src}" ]]; then
    echo "missing canonical template group: ${src}" >&2
    exit 1
  fi
  mkdir -p "${dst}"
  rsync -a --delete "${src}/" "${dst}/"
}

sync_group "ssh"
sync_group "vpn"

echo "runtime template sync complete (groups: ssh, vpn)"
