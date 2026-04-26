#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_ROOT="${REPO_ROOT}/android-orchestrator"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  -h, --help                       Show this help
USAGE
}

while (( $# > 0 )); do
  case "$1" in
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

if [[ ! -d "${APP_ROOT}" ]]; then
  echo "Android project not found: ${APP_ROOT}" >&2
  exit 1
fi

cd "${APP_ROOT}"
./gradlew test
./gradlew :app:assembleDebug

echo "APK: ${APP_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
