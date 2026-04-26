#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<'USAGE'
Usage: sync_env_to_pixel.sh [options]

Options:
  --device SERIAL      adb serial to target
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
  -h, --help           show help
USAGE
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

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
done

ensure_device
ensure_root
ensure_local_env

ROOT_CONF_ENV="/data/local/pixel-stack/conf/apps/train-bot.env"
ROOT_RUNTIME_ENV="/data/local/pixel-stack/apps/train-bot/env/train-bot.env"
TMP_ENV="/data/local/tmp/telegram-train-bot.env.tmp"
MANAGED_RE='^(TRAIN_WEB_ENABLED|TRAIN_WEB_BIND_ADDR|TRAIN_WEB_PORT|TRAIN_WEB_PUBLIC_BASE_URL|TRAIN_WEB_DIRECT_PROXY_ENABLED|TRAIN_WEB_TUNNEL_ENABLED|TRAIN_WEB_TUNNEL_CREDENTIALS_FILE|TRAIN_WEB_SESSION_SECRET_FILE|TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC)='

build_merged_env() {
  local target="$1"
  local output="$2"
  local preserve_tmp=""
  local base_tmp=""

  preserve_tmp="$(mktemp "${TMPDIR:-/tmp}/train-bot-env-preserve.XXXXXX")"
  cp "$REPO_ROOT/.env" "$output"

  adb_cmd shell su -c "if [ -f '$target' ]; then grep -E '$MANAGED_RE' '$target' || true; fi" >"$preserve_tmp"
  if [[ -s "$preserve_tmp" ]]; then
    base_tmp="$(mktemp "${TMPDIR:-/tmp}/train-bot-env-base.XXXXXX")"
    grep -Ev "$MANAGED_RE" "$output" >"$base_tmp" || true
    cat "$base_tmp" "$preserve_tmp" >"$output"
    rm -f "$base_tmp"
  fi
  rm -f "$preserve_tmp"
}

push_merged_env() {
  local target="$1"
  local local_file="$2"

  adb_cmd push "$local_file" "$TMP_ENV" >/dev/null
  adb_cmd shell su -c "cp '$TMP_ENV' '$target' && chmod 600 '$target' >/dev/null 2>&1 || true; rm -f '$TMP_ENV'"
}

log "Syncing .env to Pixel"

adb_shell_root "mkdir -p /data/local/pixel-stack/conf/apps /data/local/pixel-stack/apps/train-bot/env"

conf_tmp="$(mktemp "${TMPDIR:-/tmp}/train-bot-conf-env.XXXXXX")"
runtime_tmp="$(mktemp "${TMPDIR:-/tmp}/train-bot-runtime-env.XXXXXX")"
trap 'rm -f "$conf_tmp" "$runtime_tmp"' EXIT

build_merged_env "$ROOT_CONF_ENV" "$conf_tmp"
build_merged_env "$ROOT_RUNTIME_ENV" "$runtime_tmp"

push_merged_env "$ROOT_CONF_ENV" "$conf_tmp"
push_merged_env "$ROOT_RUNTIME_ENV" "$runtime_tmp"

log "Synced env files: $ROOT_CONF_ENV, $ROOT_RUNTIME_ENV"
