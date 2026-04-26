#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

ensure_output_dirs

if ! command -v go >/dev/null 2>&1; then
  log "Missing required command: go"
  exit 1
fi

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
log_file="${REPO_ROOT}/output/pixel/train-bot-native-test-${timestamp_utc}.log"

log "Running host-side train-bot tests"
if ! (
  cd "${REPO_ROOT}"
  CGO_ENABLED=0 go test ./... -count=1
) 2>&1 | tee "${log_file}"; then
  log "Host-side tests failed; log saved to ${log_file}"
  exit 1
fi

log "Host-side tests passed; log saved to ${log_file}"
