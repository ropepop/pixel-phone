#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_ROOT="${REPO_ROOT}/android-orchestrator"
PROFILE="${ORCHESTRATOR_BUILD_PROFILE:-standard}"
PRINT_PROVENANCE=false
BUILD_STARTED_MS=""
declare -a PHASE_TIMINGS=()

now_ms() {
  if [[ -n "${EPOCHREALTIME:-}" ]]; then
    awk -v value="${EPOCHREALTIME}" 'BEGIN { printf "%.0f\n", value * 1000 }'
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import time; print(time.monotonic_ns() // 1000000)'
  else
    printf '%s\n' "$(( $(date +%s) * 1000 ))"
  fi
}

run_phase() {
  local name="$1"
  local started_ms=""
  local finished_ms=""
  local duration_ms=""
  local rc=0
  shift

  started_ms="$(now_ms)"
  set +e
  "$@"
  rc=$?
  set -e
  finished_ms="$(now_ms)"
  duration_ms=$((finished_ms - started_ms))
  PHASE_TIMINGS+=("${name}=${duration_ms}")
  printf 'Phase timing: %s=%sms\n' "${name}" "${duration_ms}"
  return "${rc}"
}

print_total_timing() {
  local rc=$?
  local finished_ms=""
  local total_ms=""
  finished_ms="$(now_ms)"
  total_ms=$((finished_ms - BUILD_STARTED_MS))
  printf 'Build profile: %s\n' "${PROFILE}"
  printf 'Total timing: build_orchestrator=%sms\n' "${total_ms}"
  return "${rc}"
}

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --profile fast|standard|full     fast builds the APK only; standard runs tests and build together;
                                   full preserves the strict two-step test then build path
  --print-provenance               Print the release identity that would be embedded, then exit
  -h, --help                       Show this help
USAGE
}

prepare_release_provenance() {
  local build_stamp=""
  build_stamp="$(date -u +%Y%m%dT%H%M%SZ)"

  export ORCHESTRATOR_BUILD_TIME="${ORCHESTRATOR_BUILD_TIME:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
  export ORCHESTRATOR_RELEASE_ID="${ORCHESTRATOR_RELEASE_ID:-${PIXEL_RUN_ID:-local-${build_stamp}}}"

  if [[ -z "${ORCHESTRATOR_SOURCE_COMMIT:-}" ]]; then
    if git -C "${REPO_ROOT}" rev-parse HEAD >/dev/null 2>&1; then
      ORCHESTRATOR_SOURCE_COMMIT="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
    else
      ORCHESTRATOR_SOURCE_COMMIT="uncommitted"
    fi
    export ORCHESTRATOR_SOURCE_COMMIT
  fi

  if [[ -z "${ORCHESTRATOR_SOURCE_DIRTY:-}" ]]; then
    if [[ "${ORCHESTRATOR_SOURCE_COMMIT}" == "uncommitted" ]] ||
      [[ -n "$(git -C "${REPO_ROOT}" status -s -uall 2>/dev/null || true)" ]]
    then
      ORCHESTRATOR_SOURCE_DIRTY=true
    else
      ORCHESTRATOR_SOURCE_DIRTY=false
    fi
    export ORCHESTRATOR_SOURCE_DIRTY
  fi

  case "${ORCHESTRATOR_SOURCE_DIRTY}" in
    true|false) ;;
    *)
      echo "ORCHESTRATOR_SOURCE_DIRTY must be true or false" >&2
      return 2
      ;;
  esac
}

print_release_provenance() {
  printf 'ORCHESTRATOR_RELEASE_ID=%s\n' "${ORCHESTRATOR_RELEASE_ID}"
  printf 'ORCHESTRATOR_SOURCE_COMMIT=%s\n' "${ORCHESTRATOR_SOURCE_COMMIT}"
  printf 'ORCHESTRATOR_SOURCE_DIRTY=%s\n' "${ORCHESTRATOR_SOURCE_DIRTY}"
  printf 'ORCHESTRATOR_BUILD_TIME=%s\n' "${ORCHESTRATOR_BUILD_TIME}"
}

while (( $# > 0 )); do
  case "$1" in
    --profile)
      shift
      PROFILE="${1:-}"
      ;;
    --print-provenance)
      PRINT_PROVENANCE=true
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

case "${PROFILE}" in
  fast|standard|full) ;;
  *)
    echo "Unsupported --profile: ${PROFILE}" >&2
    usage >&2
    exit 2
    ;;
esac

if [[ ! -d "${APP_ROOT}" ]]; then
  echo "Android project not found: ${APP_ROOT}" >&2
  exit 1
fi

prepare_release_provenance
if [[ "${PRINT_PROVENANCE}" == "true" ]]; then
  print_release_provenance
  exit 0
fi

print_release_provenance

BUILD_STARTED_MS="$(now_ms)"
trap print_total_timing EXIT

cd "${APP_ROOT}"
case "${PROFILE}" in
  fast)
    run_phase assemble_debug ./gradlew :app:assembleDebug
    ;;
  standard)
    run_phase test_and_assemble ./gradlew test :app:assembleDebug
    ;;
  full)
    run_phase test ./gradlew test
    run_phase assemble_debug ./gradlew :app:assembleDebug
    ;;
esac

echo "APK: ${APP_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
