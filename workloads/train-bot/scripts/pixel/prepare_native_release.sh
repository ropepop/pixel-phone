#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
COMPONENT_PACKAGER="${ORCHESTRATOR_REPO}/scripts/android/package_component_release.sh"

service_date="${SERVICE_DATE:-$(TZ=Europe/Riga date +%F)}"
release_id=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --service-date YYYY-MM-DD  service date to package (default: today in Europe/Riga)
  --release-id VALUE         override generated release id
  -h, --help                 show help
USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --service-date)
      shift
      service_date="${1:-}"
      ;;
    --release-id)
      shift
      release_id="${1:-}"
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

ensure_output_dirs
ensure_local_env

for cmd in go python3; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    log "Missing required command: ${cmd}"
    exit 1
  fi
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
release_root="${REPO_ROOT}/.artifacts/train-bot/native-${timestamp_utc}"
bundle_stage="${release_root}/bundle"
bundle_dir="${bundle_stage}/bundle-root"
bundle_path=""
release_dir=""
log_file="${REPO_ROOT}/output/pixel/train-bot-native-build-${timestamp_utc}.log"

mkdir -p "${bundle_dir}/bin" "${bundle_dir}/data/schedules"
touch "${log_file}"

{
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) build android binary ==="
  (
    cd "${REPO_ROOT}"
    GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
      go build -ldflags "$(bash ./scripts/ldflags.sh)" \
      -o "${bundle_dir}/bin/train-bot" \
      ./cmd/bot
  )
  echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) scrape schedule ${service_date} ==="
  (
    set -a
    # shellcheck source=/dev/null
    . "${REPO_ROOT}/.env"
    set +a
    cd "${REPO_ROOT}"
    go run ./cmd/scrape --date "${service_date}" --out-dir "${bundle_dir}/data/schedules" --min-trains "${SCRAPER_MIN_TRAINS:-1}"
  )
} 2>&1 | tee -a "${log_file}" >&2

if [[ ! -x "${bundle_dir}/bin/train-bot" ]]; then
  echo "Missing packaged binary after host build: ${bundle_dir}/bin/train-bot" >&2
  exit 1
fi
if [[ ! -s "${bundle_dir}/data/schedules/${service_date}.json" ]]; then
  echo "Missing packaged schedule snapshot after scrape: ${bundle_dir}/data/schedules/${service_date}.json" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  binary_sha="$(sha256sum "${bundle_dir}/bin/train-bot" | awk '{print $1}')"
else
  binary_sha="$(shasum -a 256 "${bundle_dir}/bin/train-bot" | awk '{print $1}')"
fi

if [[ -z "${release_id}" ]]; then
  release_id="train-bot-${timestamp_utc}-${binary_sha:0:12}"
fi

bundle_path="${REPO_ROOT}/.artifacts/train-bot/train-bot-bundle-${release_id}.tar"
release_dir="${REPO_ROOT}/.artifacts/component-releases/train_bot-${release_id}"
mkdir -p "$(dirname "${bundle_path}")"

COPYFILE_DISABLE=1 COPY_EXTENDED_ATTRIBUTES_DISABLE=1 \
  tar -C "${bundle_dir}" -cf "${bundle_path}" .

packager_output="$("${COMPONENT_PACKAGER}" \
  --component train_bot \
  --artifact "${bundle_path}" \
  --file-name "train-bot-bundle-${release_id}.tar" \
  --release-id "${release_id}" \
  --out-dir "${release_dir}" 2>&1)" || {
  printf '%s\n' "${packager_output}" >&2
  exit 1
}

if [[ ! -d "${release_dir}" || ! -f "${release_dir}/release-manifest.json" ]]; then
  echo "Prepared release dir is invalid: ${release_dir}" >&2
  exit 1
fi

printf '%s\n' "${release_dir}"
