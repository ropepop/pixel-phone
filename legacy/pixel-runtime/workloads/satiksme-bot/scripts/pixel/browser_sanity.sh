#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

ensure_output_dirs

for cmd in node python3; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done

if ! node -e "require('playwright')" >/dev/null 2>&1; then
  log "Missing required Node module: playwright"
  exit 1
fi

if [[ -f "$REPO_ROOT/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  . "$REPO_ROOT/.env"
  set +a
fi

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
base_url="${BROWSER_SANITY_BASE_URL:-${SATIKSME_WEB_PUBLIC_BASE_URL:-https://satiksme-bot.jolkins.id.lv}}"
out_dir="${BROWSER_SANITY_OUT_DIR:-$REPO_ROOT/output/browser-use/pixel-browser-sanity}"
report_file="${BROWSER_SANITY_REPORT_FILE:-$REPO_ROOT/output/pixel/satiksme-bot-browser-sanity-${timestamp_utc}.json}"
log_file="${BROWSER_SANITY_LOG_FILE:-$REPO_ROOT/output/pixel/satiksme-bot-browser-sanity-${timestamp_utc}.log}"

mkdir -p "$out_dir"
rm -f \
  "$out_dir/home-desktop.png" \
  "$out_dir/incidents-desktop.png" \
  "$out_dir/app-mobile.png" \
  "$out_dir/incidents-mobile.png" \
  "$out_dir/console.json" \
  "$out_dir/network.json"

(
  cd "$REPO_ROOT"
  BROWSER_SANITY_BASE_URL="$base_url" \
  BROWSER_SANITY_OUT_DIR="$out_dir" \
  BROWSER_SANITY_REPORT_FILE="$report_file" \
    node ./scripts/pixel/browser_sanity.mjs
) 2>&1 | tee "$log_file"

log "Browser sanity log: $log_file"
log "Browser sanity report: $report_file"
log "Browser sanity artifacts: $out_dir"
