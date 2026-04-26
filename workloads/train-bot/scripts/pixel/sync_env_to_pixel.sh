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

merge_root_env() {
  local target="$1"
  adb_cmd shell su -c "TARGET='$target' TMP_ENV='$TMP_ENV' sh -s" <<'EOF'
set -eu

managed_re='^(TRAIN_WEB_ENABLED|TRAIN_WEB_BIND_ADDR|TRAIN_WEB_PORT|TRAIN_WEB_PUBLIC_BASE_URL|TRAIN_WEB_DIRECT_PROXY_ENABLED|TRAIN_WEB_TUNNEL_ENABLED|TRAIN_WEB_TUNNEL_CREDENTIALS_FILE|TRAIN_WEB_SESSION_SECRET_FILE|TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC)='
preserve_tmp="${TMP_ENV}.preserve"
base_tmp="${TMP_ENV}.base"
merged_tmp="${TMP_ENV}.merged"

cp "${TMP_ENV}" "${merged_tmp}"
if [ -f "${TARGET}" ]; then
  grep -E "${managed_re}" "${TARGET}" > "${preserve_tmp}" 2>/dev/null || true
  if [ -s "${preserve_tmp}" ]; then
    grep -Ev "${managed_re}" "${merged_tmp}" > "${base_tmp}" 2>/dev/null || true
    cat "${base_tmp}" "${preserve_tmp}" > "${merged_tmp}"
  fi
fi

cp "${merged_tmp}" "${TARGET}"
chmod 600 "${TARGET}" >/dev/null 2>&1 || true
rm -f "${preserve_tmp}" "${base_tmp}" "${merged_tmp}" >/dev/null 2>&1 || true
EOF
}

log "Syncing .env to Pixel"
adb_cmd push "$REPO_ROOT/.env" "$TMP_ENV" >/dev/null

adb_shell_root "mkdir -p /data/local/pixel-stack/conf/apps /data/local/pixel-stack/apps/train-bot/env"
merge_root_env "$ROOT_CONF_ENV"
merge_root_env "$ROOT_RUNTIME_ENV"
adb_cmd shell rm -f "$TMP_ENV"

log "Synced env files: $ROOT_CONF_ENV, $ROOT_RUNTIME_ENV"
