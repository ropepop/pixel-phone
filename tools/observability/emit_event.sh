#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 6 ]; then
  echo "Usage: $0 <module_id> <component_id> <source> <event_type> <status> <metrics_json> [evidence_refs_json]" >&2
  exit 2
fi

module_id="$1"
component_id="$2"
source="$3"
event_type="$4"
status="$5"
metrics_json="$6"
evidence_refs_json="${7:-[]}"
run_id="${PIXEL_RUN_ID:-${GITHUB_RUN_ID:-$(date +%Y%m%dT%H%M%S)-$RANDOM}}"
timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --arg timestamp "$timestamp" \
  --arg run_id "$run_id" \
  --arg module_id "$module_id" \
  --arg component_id "$component_id" \
  --arg source "$source" \
  --arg event_type "$event_type" \
  --arg status "$status" \
  --argjson metrics "$metrics_json" \
  --argjson evidence_refs "$evidence_refs_json" \
  '{timestamp:$timestamp,run_id:$run_id,module_id:$module_id,component_id:$component_id,source:$source,event_type:$event_type,status:$status,metrics:$metrics,evidence_refs:$evidence_refs}'
