#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
COMPONENT=""
ARTIFACT=""
ARTIFACT_ID=""
RELEASE_ID=""
OUT_DIR=""
FILE_NAME=""
FULL_MODE=0
TIMINGS_FILE="${PIXEL_PHASE_TIMINGS_FILE:-}"
TIMING_TOTAL_START_MS=""
TIMING_PHASE_START_MS=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") --component NAME --artifact FILE [--artifact-id ID] [--file-name NAME] [--release-id VALUE] [--out-dir DIR] [--full]

Builds a single-component release bundle for on-device staging via deploy_orchestrator_apk.sh --component-release-dir.

Options:
  --component NAME     component owner (dns|ssh|vpn|train_bot|satiksme_bot|site_notifier|subscription_bot)
  --artifact FILE      release artifact file to publish for the component
  --artifact-id ID     override manifest artifact id
  --file-name NAME     override staged artifact file name
  --release-id VALUE   release id string (default: local-<UTC timestamp>)
  --out-dir DIR        output dir (default: .artifacts/component-releases/<component>-<release-id>)
  --fast               skip workspace-wide cleanup (default)
  --full, --strict     run workspace cleanup before packaging
  --timings-file FILE  append JSONL phase timings to FILE
  -h, --help           show help
USAGE
}

timing_now_ms() {
  python3 -c 'import time; print(time.monotonic_ns() // 1_000_000)'
}

timing_start() {
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  mkdir -p "$(dirname "${TIMINGS_FILE}")"
  TIMING_TOTAL_START_MS="$(timing_now_ms)"
  TIMING_PHASE_START_MS="${TIMING_TOTAL_START_MS}"
}

timing_mark() {
  local phase="$1"
  local now_ms=""
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  now_ms="$(timing_now_ms)"
  printf '{"script":"package_component_release","phase":"%s","durationMs":%d}\n' \
    "${phase}" "$((now_ms - TIMING_PHASE_START_MS))" >> "${TIMINGS_FILE}"
  TIMING_PHASE_START_MS="${now_ms}"
}

timing_finish() {
  local now_ms=""
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  now_ms="$(timing_now_ms)"
  printf '{"script":"package_component_release","phase":"total","durationMs":%d}\n' \
    "$((now_ms - TIMING_TOTAL_START_MS))" >> "${TIMINGS_FILE}"
}

while (( $# > 0 )); do
  case "$1" in
    --component)
      shift
      COMPONENT="${1:-}"
      ;;
    --artifact)
      shift
      ARTIFACT="${1:-}"
      ;;
    --artifact-id)
      shift
      ARTIFACT_ID="${1:-}"
      ;;
    --file-name)
      shift
      FILE_NAME="${1:-}"
      ;;
    --release-id)
      shift
      RELEASE_ID="${1:-}"
      ;;
    --out-dir)
      shift
      OUT_DIR="${1:-}"
      ;;
    --fast)
      FULL_MODE=0
      ;;
    --full|--strict)
      FULL_MODE=1
      ;;
    --timings-file)
      shift
      TIMINGS_FILE="${1:-}"
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

[[ -n "${COMPONENT}" ]] || { echo "--component is required" >&2; exit 2; }
[[ -n "${ARTIFACT}" ]] || { echo "--artifact is required" >&2; exit 2; }
[[ -f "${ARTIFACT}" ]] || { echo "Artifact file not found: ${ARTIFACT}" >&2; exit 1; }

case "${COMPONENT}" in
  dns)
    DEFAULT_ARTIFACT_ID="adguardhome-rootfs"
    ;;
  ssh)
    DEFAULT_ARTIFACT_ID="dropbear-bundle"
    ;;
  vpn)
    DEFAULT_ARTIFACT_ID="tailscale-bundle"
    ;;
  train_bot)
    DEFAULT_ARTIFACT_ID="train-bot-bundle"
    ;;
  satiksme_bot)
    DEFAULT_ARTIFACT_ID="satiksme-bot-bundle"
    ;;
  site_notifier)
    DEFAULT_ARTIFACT_ID="site-notifier-bundle"
    ;;
  subscription_bot)
    DEFAULT_ARTIFACT_ID="subscription-bot-bundle"
    ;;
  *)
    echo "--component must be one of: dns|ssh|vpn|train_bot|satiksme_bot|site_notifier|subscription_bot" >&2
    exit 2
    ;;
esac

if [[ -z "${ARTIFACT_ID}" ]]; then
  ARTIFACT_ID="${DEFAULT_ARTIFACT_ID}"
fi

if [[ -z "${RELEASE_ID}" ]]; then
  RELEASE_ID="local-$(date -u +%Y%m%dT%H%M%SZ)"
fi

if [[ -z "${FILE_NAME}" ]]; then
  FILE_NAME="$(basename "${ARTIFACT}")"
fi

if [[ -z "${FILE_NAME}" || "${FILE_NAME}" == "." || "${FILE_NAME}" == ".." || "${FILE_NAME}" != "$(basename "${FILE_NAME}")" ]]; then
  echo "--file-name must be a single safe file name" >&2
  exit 2
fi

if [[ -z "${OUT_DIR}" ]]; then
  OUT_DIR="${REPO_ROOT}/.artifacts/component-releases/${COMPONENT}-${RELEASE_ID}"
fi

timing_start
if (( FULL_MODE == 1 )); then
  bash "${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh"
fi
timing_mark "cleanup"
mkdir -p "${OUT_DIR}/artifacts"
OUT_DIR="$(cd "${OUT_DIR}" && pwd)"

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

size_bytes() {
  local path="$1"
  if stat -f "%z" "${path}" >/dev/null 2>&1; then
    stat -f "%z" "${path}"
  else
    stat -c "%s" "${path}"
  fi
}

copy_artifact() {
  local source_path="$1"
  local destination_path="$2"
  rm -f "${destination_path}"
  if cp -c "${source_path}" "${destination_path}" 2>/dev/null; then
    return 0
  fi
  if cp --reflink=auto "${source_path}" "${destination_path}" 2>/dev/null; then
    return 0
  fi
  cp "${source_path}" "${destination_path}"
}

ARTIFACT_OUT="${OUT_DIR}/artifacts/${FILE_NAME}"
copy_artifact "${ARTIFACT}" "${ARTIFACT_OUT}"
timing_mark "stage"

ARTIFACT_SHA="$(sha256_file "${ARTIFACT_OUT}")"
ARTIFACT_SIZE="$(size_bytes "${ARTIFACT_OUT}")"
timing_mark "hash"
MANIFEST_PATH="${OUT_DIR}/release-manifest.json"

export MANIFEST_PATH COMPONENT RELEASE_ID ARTIFACT_ID FILE_NAME ARTIFACT_SHA ARTIFACT_SIZE
python3 - <<'PY'
import json
import os
from pathlib import Path

manifest_path = Path(os.environ["MANIFEST_PATH"])
payload = {
    "schema": 1,
    "componentId": os.environ["COMPONENT"],
    "releaseId": os.environ["RELEASE_ID"],
    "signatureSchema": "none",
    "artifacts": [
        {
            "id": os.environ["ARTIFACT_ID"],
            "url": (
                "/data/local/pixel-stack/conf/runtime/artifacts/sha256/"
                f"{os.environ['ARTIFACT_SHA']}"
            ),
            "sha256": os.environ["ARTIFACT_SHA"],
            "fileName": os.environ["FILE_NAME"],
            "sizeBytes": int(os.environ["ARTIFACT_SIZE"]),
            "required": True,
        }
    ],
}
temporary_path = manifest_path.with_name(f".{manifest_path.name}.tmp-{os.getpid()}")
temporary_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
temporary_path.replace(manifest_path)
PY
timing_mark "manifest"
timing_finish

cat <<EOF_SUMMARY
Component release ready:
  ${OUT_DIR}

Stage on device with:
  bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --component-release-dir "${OUT_DIR}" --action redeploy_component --component ${COMPONENT}
EOF_SUMMARY
