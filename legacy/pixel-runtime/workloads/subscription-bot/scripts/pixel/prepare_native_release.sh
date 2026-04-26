#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "${SCRIPT_DIR}/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "${REPO_ROOT}/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-${DEFAULT_ORCHESTRATOR_REPO}}"
COMPONENT_PACKAGER="${ORCHESTRATOR_REPO}/scripts/android/package_component_release.sh"
RELEASE_ID="${RELEASE_ID:-subscription-bot-$(date -u +%Y%m%dT%H%M%SZ)}"

ensure_output_dirs

LDFLAGS="$(bash "${REPO_ROOT}/scripts/ldflags.sh")"
BUILD_ROOT="$(mktemp -d "${REPO_ROOT}/output/pixel/subscription-bot-build.${RELEASE_ID}.XXXXXX")"
BUNDLE_ROOT="${BUILD_ROOT}/bundle"
mkdir -p "${BUNDLE_ROOT}/bin"

(
  cd "${REPO_ROOT}"
  GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
    go build -ldflags "${LDFLAGS}" -o "${BUNDLE_ROOT}/bin/subscription-bot" ./cmd/bot
)

BUNDLE_PATH="${REPO_ROOT}/.artifacts/subscription-bot/subscription-bot-bundle-${RELEASE_ID}.tar"
tar -C "${BUNDLE_ROOT}" -cf "${BUNDLE_PATH}" .

RELEASE_DIR="${REPO_ROOT}/.artifacts/component-releases/subscription_bot-${RELEASE_ID}"
"${COMPONENT_PACKAGER}" \
  --component subscription_bot \
  --artifact "${BUNDLE_PATH}" \
  --file-name "subscription-bot-bundle-${RELEASE_ID}.tar" \
  --release-id "${RELEASE_ID}" \
  --out-dir "${RELEASE_DIR}" >/dev/null

printf 'SUBSCRIPTION_BOT_RELEASE_DIR=%s\n' "${RELEASE_DIR}"
printf '%s\n' "${RELEASE_DIR}"
