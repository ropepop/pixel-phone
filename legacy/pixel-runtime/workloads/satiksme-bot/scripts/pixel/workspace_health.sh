#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

ensure_output_dirs

for cmd in go node npm python3 bash mktemp spacetime; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    log "Missing required command: $cmd"
    exit 1
  fi
done

timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
report_file="${WORKSPACE_HEALTH_REPORT_FILE:-$REPO_ROOT/output/pixel/satiksme-bot-workspace-health-${timestamp_utc}.json}"
log_file="${WORKSPACE_HEALTH_LOG_FILE:-$REPO_ROOT/output/pixel/satiksme-bot-workspace-health-${timestamp_utc}.log}"
tmp_dir="$(mktemp -d)"

cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

status_go_test="SKIP"
status_js_test="SKIP"
status_live_client_build="SKIP"
status_temp_build="SKIP"
status_spacetime_build="SKIP"
success=0

declare -a failures

run_step() {
  local name="$1"
  shift

  log "Running workspace step: ${name}" | tee -a "$log_file" >/dev/null
  {
    printf '=== %s (%s) ===\n' "$name" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    "$@"
    printf '\n'
  } >>"$log_file" 2>&1
}

run_go_test() {
  (
    cd "$REPO_ROOT"
    go test ./...
  )
}

run_js_test() {
  (
    cd "$REPO_ROOT"
    node --test ./internal/web/static/*.test.js
  )
}

run_live_client_build() {
  (
    cd "$REPO_ROOT/web-client"
    npm run build
  )
}

run_temp_build() {
  local tmp_bin="$tmp_dir/satiksme-bot"
  (
    cd "$REPO_ROOT"
    go build -ldflags "$(bash ./scripts/ldflags.sh)" -o "$tmp_bin" ./cmd/bot
  )
  test -x "$tmp_bin"
}

run_spacetime_build() {
  (
    cd "$REPO_ROOT/spacetimedb"
    npm run build
  )
}

: >"$log_file"

if run_step "go-test" run_go_test; then
  status_go_test="PASS"
else
  status_go_test="FAIL"
  failures+=("go test ./... failed")
fi

if [[ "$status_go_test" == "PASS" ]]; then
  if run_step "js-test" run_js_test; then
    status_js_test="PASS"
  else
    status_js_test="FAIL"
    failures+=("node --test ./internal/web/static/*.test.js failed")
  fi
fi

if [[ "$status_js_test" == "PASS" ]]; then
  if run_step "live-client-build" run_live_client_build; then
    status_live_client_build="PASS"
  else
    status_live_client_build="FAIL"
    failures+=("web-client npm run build failed")
  fi
fi

if [[ "$status_live_client_build" == "PASS" ]]; then
  if run_step "temp-build" run_temp_build; then
    status_temp_build="PASS"
  else
    status_temp_build="FAIL"
    failures+=("temporary go build failed")
  fi
fi

if [[ "$status_temp_build" == "PASS" ]]; then
  if run_step "spacetime-build" run_spacetime_build; then
    status_spacetime_build="PASS"
  else
    status_spacetime_build="FAIL"
    failures+=("spacetimedb build failed")
  fi
fi

if [[ "$status_spacetime_build" == "PASS" ]]; then
  success=1
fi

export WORKSPACE_HEALTH_GENERATED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export WORKSPACE_HEALTH_GO_TEST="$status_go_test"
export WORKSPACE_HEALTH_JS_TEST="$status_js_test"
export WORKSPACE_HEALTH_LIVE_CLIENT_BUILD="$status_live_client_build"
export WORKSPACE_HEALTH_TEMP_BUILD="$status_temp_build"
export WORKSPACE_HEALTH_SPACETIME_BUILD="$status_spacetime_build"
export WORKSPACE_HEALTH_LOG_FILE_VALUE="$log_file"
export WORKSPACE_HEALTH_SUCCESS="$success"
export WORKSPACE_HEALTH_FAILURES="$(printf '%s\n' "${failures[@]-}")"

python3 - "$report_file" <<'PY'
import json
import os
import sys

failures = [line for line in os.environ.get("WORKSPACE_HEALTH_FAILURES", "").splitlines() if line]
payload = {
    "generatedAtUtc": os.environ["WORKSPACE_HEALTH_GENERATED_AT_UTC"],
    "success": os.environ["WORKSPACE_HEALTH_SUCCESS"] == "1",
    "steps": {
        "goTest": os.environ["WORKSPACE_HEALTH_GO_TEST"],
        "jsTest": os.environ["WORKSPACE_HEALTH_JS_TEST"],
        "liveClientBuild": os.environ["WORKSPACE_HEALTH_LIVE_CLIENT_BUILD"],
        "tempBuild": os.environ["WORKSPACE_HEALTH_TEMP_BUILD"],
        "spacetimeBuild": os.environ["WORKSPACE_HEALTH_SPACETIME_BUILD"],
    },
    "failures": failures,
    "logFile": os.environ["WORKSPACE_HEALTH_LOG_FILE_VALUE"],
}

with open(sys.argv[1], "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2)
    fh.write("\n")
PY

log "Workspace health log: $log_file"
log "Workspace health report: $report_file"

if (( success != 1 )); then
  exit 1
fi
