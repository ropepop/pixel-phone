#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

ensure_device
ensure_root
ensure_output_dirs

ROOT_RUNTIME_BIN_DIR="/data/local/pixel-stack/apps/train-bot/bin"
ROLLBACK_TARGET=""
MODE="last"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [--last | --sha SHA]

Options:
  --device SERIAL  adb serial to target
  --transport MODE transport to use (adb|ssh|auto)
  --ssh-host IP    Tailscale or SSH host/IP
  --ssh-port PORT  SSH port (default: 2222)
  --last       rollback to the previous retained immutable release
  --sha SHA    rollback to a specific immutable release hash
  -h, --help   show help
USAGE
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    --last)
      MODE="last"
      ;;
    --sha)
      shift
      ROLLBACK_TARGET="${1:-}"
      MODE="sha"
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

current_target="$(
  adb_cmd shell su -c "readlink -f '${ROOT_RUNTIME_BIN_DIR}/train-bot.current' 2>/dev/null || true" | tr -d '\r' | tr -d '[:space:]'
)"

if [[ "${MODE}" == "last" ]]; then
  rollback_path="$(
    adb_cmd shell su -c "ls -1t ${ROOT_RUNTIME_BIN_DIR}/train-bot.[0-9a-f]* 2>/dev/null | while read -r path; do [ \"\$path\" = \"${current_target}\" ] && continue; printf '%s\n' \"\$path\"; break; done" | tr -d '\r'
  )"
else
  if [[ -z "${ROLLBACK_TARGET}" ]]; then
    echo "Missing SHA for --sha" >&2
    exit 2
  fi
  rollback_path="${ROOT_RUNTIME_BIN_DIR}/train-bot.${ROLLBACK_TARGET}"
fi

rollback_path="$(printf '%s' "${rollback_path}" | tr -d '[:space:]')"
if [[ -z "${rollback_path}" ]]; then
  echo "No rollback candidate is available under ${ROOT_RUNTIME_BIN_DIR}" >&2
  exit 1
fi

if ! adb_cmd shell su -c "test -x '${rollback_path}'"; then
  echo "Rollback target is missing or not executable: ${rollback_path}" >&2
  exit 1
fi

log "Repointing train-bot.current to ${rollback_path}"
adb_cmd shell su -c "ln -sfn '${rollback_path}' '${ROOT_RUNTIME_BIN_DIR}/train-bot.current' && touch '${rollback_path}'"

log "Restarting train runtime on the selected rollback release"
adb_cmd shell su -c "sh /data/local/pixel-stack/bin/pixel-train-stop.sh"
adb_cmd shell su -c "sh /data/local/pixel-stack/bin/pixel-train-start.sh"

"${SCRIPT_DIR}/release_check.sh"

log "Rollback completed: ${rollback_path}"
