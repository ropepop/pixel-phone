#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

ensure_output_dirs
ensure_local_env

log_file="${REPO_ROOT}/output/pixel/satiksme-bot-native-test-$(date -u +%Y%m%dT%H%M%SZ).log"

(
  cd "$REPO_ROOT"
  make test
) 2>&1 | tee "$log_file"

log "Native test log: $log_file"
