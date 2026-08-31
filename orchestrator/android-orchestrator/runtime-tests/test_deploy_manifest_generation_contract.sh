#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

FAIL_ACTIVATION_SOURCE=""
FAIL_ACTIVATION_ONCE=0

pixel_transport_root_exec() {
  if [[ "${FAIL_ACTIVATION_ONCE}" == "1" && "${1:-}" == "mv" && "${2:-}" == "${FAIL_ACTIVATION_SOURCE}" ]]; then
    FAIL_ACTIVATION_ONCE=0
    return 1
  fi
  "$@"
}

helper_source="$(awk '
  /^activate_manifest_with_previous\(\)/ { capture = 1 }
  capture { print }
  capture && /^}/ { exit }
' "${DEPLOY_SCRIPT}")"
if [[ -z "${helper_source}" ]]; then
  echo "FAIL: previous-manifest helper is missing" >&2
  exit 1
fi
eval "${helper_source}"

active="${TEST_ROOT}/manifest.json"
staged="${TEST_ROOT}/manifest.tmp"
previous="${TEST_ROOT}/manifest.previous.json"
previous_tmp="${TEST_ROOT}/manifest.previous.tmp"

printf 'generation-one\n' > "${staged}"
activate_manifest_with_previous "${active}" "${staged}" "${previous}" "${previous_tmp}"
if [[ -e "${previous}" ]]; then
  echo "FAIL: first activation created a false rollback generation" >&2
  exit 1
fi

printf 'generation-one\n' > "${staged}"
activate_manifest_with_previous "${active}" "${staged}" "${previous}" "${previous_tmp}"
if [[ -e "${previous}" ]]; then
  echo "FAIL: identical activation created a false rollback generation" >&2
  exit 1
fi

printf 'generation-two\n' > "${staged}"
activate_manifest_with_previous "${active}" "${staged}" "${previous}" "${previous_tmp}"
if [[ "$(cat "${active}")" != "generation-two" || "$(cat "${previous}")" != "generation-one" ]]; then
  echo "FAIL: distinct activation did not retain exactly the superseded generation" >&2
  exit 1
fi

printf 'generation-two\n' > "${staged}"
activate_manifest_with_previous "${active}" "${staged}" "${previous}" "${previous_tmp}"
if [[ "$(cat "${active}")" != "generation-two" || "$(cat "${previous}")" != "generation-one" ]]; then
  echo "FAIL: identical restaging replaced the valid rollback generation" >&2
  exit 1
fi

printf 'generation-three\n' > "${staged}"
FAIL_ACTIVATION_SOURCE="${staged}"
FAIL_ACTIVATION_ONCE=1
if activate_manifest_with_previous "${active}" "${staged}" "${previous}" "${previous_tmp}"; then
  echo "FAIL: simulated activation failure unexpectedly succeeded" >&2
  exit 1
fi
if [[ "$(cat "${active}")" != "generation-two" || "$(cat "${previous}")" != "generation-one" ]]; then
  echo "FAIL: failed activation changed active or rollback manifests" >&2
  exit 1
fi
if [[ -e "${previous_tmp}" || -e "${staged}" ]]; then
  echo "FAIL: manifest activation left temporary state" >&2
  exit 1
fi

echo "PASS: manifest activation preserves rollback metadata and restores state on failure"
