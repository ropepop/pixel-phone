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

usage() {
  cat <<USAGE
Usage: $(basename "$0") --component NAME --artifact FILE [--artifact-id ID] [--file-name NAME] [--release-id VALUE] [--out-dir DIR]

Builds a single-component release bundle for on-device staging via deploy_orchestrator_apk.sh --component-release-dir.

Options:
  --component NAME     component owner (dns|ssh|vpn|train_bot|satiksme_bot|site_notifier|subscription_bot)
  --artifact FILE      release artifact file to publish for the component
  --artifact-id ID     override manifest artifact id
  --file-name NAME     override staged artifact file name
  --release-id VALUE   release id string (default: local-<UTC timestamp>)
  --out-dir DIR        output dir (default: .artifacts/component-releases/<component>-<release-id>)
  -h, --help           show help
USAGE
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

if [[ -z "${OUT_DIR}" ]]; then
  OUT_DIR="${REPO_ROOT}/.artifacts/component-releases/${COMPONENT}-${RELEASE_ID}"
fi

bash "${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh"
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

ARTIFACT_OUT="${OUT_DIR}/artifacts/${FILE_NAME}"
cp "${ARTIFACT}" "${ARTIFACT_OUT}"

ARTIFACT_SHA="$(sha256_file "${ARTIFACT_OUT}")"
ARTIFACT_SIZE="$(size_bytes "${ARTIFACT_OUT}")"
MANIFEST_PATH="${OUT_DIR}/release-manifest.json"

cat > "${MANIFEST_PATH}" <<EOF_MANIFEST
{
  "schema": 1,
  "componentId": "${COMPONENT}",
  "releaseId": "${RELEASE_ID}",
  "signatureSchema": "none",
  "artifacts": [
    {
      "id": "${ARTIFACT_ID}",
      "url": "/data/local/pixel-stack/conf/runtime/components/${COMPONENT}/artifacts/${FILE_NAME}",
      "sha256": "${ARTIFACT_SHA}",
      "fileName": "${FILE_NAME}",
      "sizeBytes": ${ARTIFACT_SIZE},
      "required": true
    }
  ]
}
EOF_MANIFEST

cat <<EOF_SUMMARY
Component release ready:
  ${OUT_DIR}

Stage on device with:
  bash orchestrator/scripts/android/deploy_orchestrator_apk.sh --component-release-dir "${OUT_DIR}" --action redeploy_component --component ${COMPONENT}
EOF_SUMMARY
