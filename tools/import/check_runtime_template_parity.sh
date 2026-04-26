#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

CANON_ROOT="${REPO_ROOT}/orchestrator/templates"
ASSET_ROOT="${REPO_ROOT}/orchestrator/android-orchestrator/app/src/main/assets/runtime/templates"

check_group() {
  local name="$1"
  local src="${CANON_ROOT}/${name}"
  local dst="${ASSET_ROOT}/${name}"
  if [[ ! -d "${src}" || ! -d "${dst}" ]]; then
    echo "missing template group for parity check: ${name}" >&2
    exit 1
  fi

  if ! diff -ru --strip-trailing-cr "${src}" "${dst}" >/tmp/template-parity-${name}.diff; then
    echo "template parity failed for group '${name}'. Run ./tools/import/sync_runtime_templates.sh" >&2
    cat /tmp/template-parity-${name}.diff >&2
    exit 1
  fi
}

check_group "ssh"
check_group "vpn"

echo "runtime template parity check passed (groups: ssh, vpn)"
