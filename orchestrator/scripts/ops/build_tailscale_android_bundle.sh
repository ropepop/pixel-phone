#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

DEFAULT_VERSION="v1.80.3"
DEFAULT_OUT_DIR="${REPO_ROOT}/.artifacts/tailscale/android-arm64/${DEFAULT_VERSION}"

VERSION="${DEFAULT_VERSION}"
OUT_DIR="${DEFAULT_OUT_DIR}"
WORK_DIR=""
KEEP_WORK_DIR=0

usage() {
  cat <<EOF_USAGE
Usage: $(basename "$0") [options]

Build Android arm64 tailscale binaries and package a runtime bundle tar.

Options:
  --version TAG        Tailscale git tag/branch (default: ${DEFAULT_VERSION})
  --out-dir DIR        Output directory (default: ${DEFAULT_OUT_DIR})
  --work-dir DIR       Work directory (default: temporary dir)
  --keep-work-dir      Keep temporary work directory on success
  -h, --help           Show this help text
EOF_USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --version)
      shift
      [[ $# -gt 0 ]] || { echo "Missing value for --version" >&2; exit 2; }
      VERSION="$1"
      ;;
    --out-dir)
      shift
      [[ $# -gt 0 ]] || { echo "Missing value for --out-dir" >&2; exit 2; }
      OUT_DIR="$1"
      ;;
    --work-dir)
      shift
      [[ $# -gt 0 ]] || { echo "Missing value for --work-dir" >&2; exit 2; }
      WORK_DIR="$1"
      ;;
    --keep-work-dir)
      KEEP_WORK_DIR=1
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

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }
}

require_cmd git
require_cmd go
require_cmd tar

if [[ -z "${WORK_DIR}" ]]; then
  WORK_DIR="$(mktemp -d)"
  WORK_DIR_AUTO=1
else
  mkdir -p "${WORK_DIR}"
  WORK_DIR_AUTO=0
fi

cleanup() {
  if (( KEEP_WORK_DIR == 0 )) && (( WORK_DIR_AUTO == 1 )); then
    rm -rf "${WORK_DIR}"
  fi
}
trap cleanup EXIT

SRC_DIR="${WORK_DIR}/tailscale"
BUNDLE_STAGE="${WORK_DIR}/bundle-stage"
mkdir -p "${OUT_DIR}" "${BUNDLE_STAGE}/bin" "${BUNDLE_STAGE}/conf" "${BUNDLE_STAGE}/logs" "${BUNDLE_STAGE}/run" "${BUNDLE_STAGE}/state"

echo "Cloning tailscale ${VERSION}"
git clone --depth 1 --branch "${VERSION}" https://github.com/tailscale/tailscale.git "${SRC_DIR}"

echo "Building tailscale binaries for android/arm64"
(
  cd "${SRC_DIR}"
  export CGO_ENABLED=0
  export GOOS=android
  export GOARCH=arm64
  go build -trimpath -ldflags="-s -w" -o "${BUNDLE_STAGE}/bin/tailscale" ./cmd/tailscale
  go build -trimpath -ldflags="-s -w" -o "${BUNDLE_STAGE}/bin/tailscaled" ./cmd/tailscaled
)

chmod 0755 "${BUNDLE_STAGE}/bin/tailscale" "${BUNDLE_STAGE}/bin/tailscaled"

BUNDLE_PATH="${OUT_DIR}/tailscale-bundle.tar"
tar -C "${BUNDLE_STAGE}" -cf "${BUNDLE_PATH}" .

echo "Built tailscale bundle:"
echo "  ${BUNDLE_PATH}"
