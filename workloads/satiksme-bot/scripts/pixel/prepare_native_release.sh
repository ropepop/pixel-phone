#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
COMPONENT_PACKAGER="${ORCHESTRATOR_REPO}/scripts/android/package_component_release.sh"

ensure_output_dirs
ensure_local_env

release_id=""
if (( $# > 0 )) && [[ "${1:-}" != -* ]]; then
  release_id="${1:-}"
  shift
fi

while (( $# > 0 )); do
  case "$1" in
    --release-id)
      shift
      release_id="${1:-}"
      ;;
    -h|--help)
      echo "Usage: $(basename "$0") [release-id] [--release-id VALUE]" >&2
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

if [[ -z "${ORCHESTRATOR_REPO}" || ! -d "${ORCHESTRATOR_REPO}" ]]; then
  echo "Cannot resolve orchestrator repo. Set ORCHESTRATOR_REPO explicitly." >&2
  exit 1
fi
if [[ ! -x "${COMPONENT_PACKAGER}" ]]; then
  echo "Missing component release packager: ${COMPONENT_PACKAGER}" >&2
  exit 1
fi

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
release_root="${REPO_ROOT}/.artifacts/satiksme-bot/native-${timestamp_utc}"
bundle_root="${release_root}/bundle-root"
bundle_path=""
component_release_dir=""
log_file="${REPO_ROOT}/output/pixel/satiksme-bot-native-build-${timestamp_utc}.log"

mkdir -p "${bundle_root}/bin" "${bundle_root}/data/catalog/generated" "${bundle_root}/data/catalog/source"

{
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) build android binary ==="
  (
    cd "${REPO_ROOT}"
    GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
      go build -ldflags "$(bash ./scripts/ldflags.sh)" \
      -o "${bundle_root}/bin/satiksme-bot" \
      ./cmd/bot
  )

  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) mirror catalog ==="
  (
    set -a
    . "${REPO_ROOT}/.env"
    set +a
    cd "${REPO_ROOT}"
    go run ./cmd/catalogsync \
      --mirror-dir "${bundle_root}/data/catalog/source" \
      --out "${bundle_root}/data/catalog/generated/catalog.json" \
      --force
    )
} 2>&1 | tee -a "${log_file}" >&2

if [[ ! -x "${bundle_root}/bin/satiksme-bot" ]]; then
  echo "Missing packaged binary after host build: ${bundle_root}/bin/satiksme-bot" >&2
  exit 1
fi
if [[ ! -s "${bundle_root}/data/catalog/generated/catalog.json" ]]; then
  echo "Missing generated catalog JSON after mirror step: ${bundle_root}/data/catalog/generated/catalog.json" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  binary_sha="$(sha256sum "${bundle_root}/bin/satiksme-bot" | awk '{print $1}')"
else
  binary_sha="$(shasum -a 256 "${bundle_root}/bin/satiksme-bot" | awk '{print $1}')"
fi

if [[ -z "${release_id}" ]]; then
  release_id="satiksme-bot-${timestamp_utc}-${binary_sha:0:12}"
fi

bundle_path="${REPO_ROOT}/.artifacts/satiksme-bot/satiksme-bot-bundle-${release_id}.tar"
component_release_dir="${REPO_ROOT}/.artifacts/component-releases/satiksme_bot-${release_id}"
mkdir -p "$(dirname "${bundle_path}")"

COPYFILE_DISABLE=1 COPY_EXTENDED_ATTRIBUTES_DISABLE=1 \
  tar -C "${bundle_root}" -cf "${bundle_path}" .

packager_output="$("${COMPONENT_PACKAGER}" \
  --component satiksme_bot \
  --artifact "${bundle_path}" \
  --file-name "satiksme-bot-bundle-${release_id}.tar" \
  --release-id "${release_id}" \
  --out-dir "${component_release_dir}" 2>&1)" || {
  printf '%s\n' "${packager_output}" >&2
  exit 1
}

if [[ ! -d "${component_release_dir}" || ! -f "${component_release_dir}/release-manifest.json" ]]; then
  echo "Prepared release dir is invalid: ${component_release_dir}" >&2
  exit 1
fi

printf '%s\n' "${component_release_dir}"
