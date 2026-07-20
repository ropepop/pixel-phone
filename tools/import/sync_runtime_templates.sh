#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

CANON_ROOT="${REPO_ROOT}/orchestrator/templates"
ASSET_ROOT="${REPO_ROOT}/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates"

SOURCE_ROOT="${CANON_ROOT}"
DESTINATION_ROOT="${ASSET_ROOT}"
direction="canonical to embedded assets"
if [[ "${1:-}" == "--from-assets" ]]; then
  SOURCE_ROOT="${ASSET_ROOT}"
  DESTINATION_ROOT="${CANON_ROOT}"
  direction="embedded assets to canonical"
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--from-assets]" >&2
  exit 2
fi

sync_group() {
  local name="$1"
  local src="${SOURCE_ROOT}/${name}"
  local dst="${DESTINATION_ROOT}/${name}"
  if [[ ! -d "${src}" ]]; then
    echo "missing canonical template group: ${src}" >&2
    exit 1
  fi
  mkdir -p "${dst}"
  rsync -a --delete "${src}/" "${dst}/"
}

sync_group "ssh"
sync_group "vpn"

echo "runtime template sync complete (${direction}; groups: ssh, vpn)"
