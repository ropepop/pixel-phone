#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${WORKSPACE_ROOT}/tools/pixel/transport.sh"

OUT_DIR="${OUT_DIR:-./state/pixel-diagnostics}"
APP_DIR="${APP_DIR:-/data/local/pixel-stack/apps/site-notifications}"
TS="$(date +%Y%m%d-%H%M%S)"
RUN_ID="${PIXEL_RUN_ID:-${TS}-$RANDOM}"
REPORT_DIR="${REPORT_DIR:-${OUT_DIR}/${TS}-${RUN_ID}}"
mkdir -p "$REPORT_DIR"

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi
  case "$1" in
    -h|--help)
      cat <<'USAGE'
Usage: collect_pixel_deploy_data.sh [options]

Options:
  --device SERIAL      adb serial to target
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
  -h, --help           show help
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

pixel_transport_require_device >/dev/null
pixel_transport_require_root >/dev/null

run_local() {
  local name="$1"
  shift
  {
    echo "$ $*"
    "$@"
  } >"${REPORT_DIR}/${name}.txt" 2>&1 || true
}

run_device() {
  local name="$1"
  shift
  {
    echo "$ pixel shell $*"
    pixel_transport_root_shell "$*"
  } >"${REPORT_DIR}/${name}.txt" 2>&1 || true
}

run_device_root() {
  run_device "$@"
}

run_device_root_notifier_shell() {
  local name="$1"
  local body="$2"
  {
    echo "$ pixel shell /system/bin/sh -s <<'EOF_RUNTIME'"
    pixel_transport_root_shell_stdin <<EOF_RUNTIME
set -eu
APP_DIR='${APP_DIR}'
load_env_file() {
  env_path="\$1"
  while IFS= read -r line || [ -n "\$line" ]; do
    case "\$line" in
      ''|'#'*) continue ;;
      *=*) ;;
      *) continue ;;
    esac
    key="\${line%%=*}"
    value="\${line#*=}"
    case "\$key" in
      [A-Za-z_][A-Za-z0-9_]*) export "\${key}=\${value}" ;;
      *) continue ;;
    esac
  done < "\$env_path"
}
load_env_file "\${APP_DIR}/env/site-notifications.env"
export RUNTIME_CONTEXT_POLICY=orchestrator_root
${body}
EOF_RUNTIME
  } >"${REPORT_DIR}/${name}.txt" 2>&1 || true
}

run_local "transport_target" printf 'transport=%s\ntarget=%s\n' "$(pixel_transport_selected)" "${ADB_SERIAL:-${PIXEL_SSH_HOST:-unknown}}"
run_device "device_model" "getprop ro.product.model"
run_device "device_date" "date"
run_device "pwd" "pwd"
run_device "app_dir_ls" "ls -la '${APP_DIR}'"
run_device "run_dir_ls" "ls -la '${APP_DIR}/run'"
run_device "logs_dir_ls" "ls -la '${APP_DIR}/logs'"
run_device "current_release" "readlink -f '${APP_DIR}/current'"
run_device "env_probe" "env | grep -E 'RUNTIME_CONTEXT_POLICY|STATE_FILE|DAEMON_LOCK_FILE|WATCHDOG|CHECK_INTERVAL|HTTP_TIMEOUT|TELEGRAM'"
run_device "state_json" "cat '${APP_DIR}/state/state.json'"
run_device "healthcheck" "cd '${APP_DIR}' && python app.py healthcheck; echo EXIT:\$?"
run_device "status_local" "cd '${APP_DIR}' && python app.py status-local"
run_device "diag_telegram" "cd '${APP_DIR}' && python app.py diag-telegram"
run_device "processes" "ps -ef | grep -E 'python.*app.py daemon|site-notifications|site_notifier' | grep -v grep"
run_device "service_loop_log_tail" "tail -n 200 '${APP_DIR}/logs/service-loop.log' 2>/dev/null || true"
run_device "daemon_log_tail" "tail -n 200 '${APP_DIR}/logs/daemon.log' 2>/dev/null || true"
run_device "telegram_getme" "cd '${APP_DIR}' && . ./.env >/dev/null 2>&1; curl -sS \"https://api.telegram.org/bot\$TELEGRAM_BOT_TOKEN/getMe\""
run_device "telegram_webhook" "cd '${APP_DIR}' && . ./.env >/dev/null 2>&1; curl -sS \"https://api.telegram.org/bot\$TELEGRAM_BOT_TOKEN/getWebhookInfo\""
run_device "telegram_getupdates" "cd '${APP_DIR}' && . ./.env >/dev/null 2>&1; curl -sS -X POST \"https://api.telegram.org/bot\$TELEGRAM_BOT_TOKEN/getUpdates\" -H 'Content-Type: application/json' -d '{\"timeout\":0,\"allowed_updates\":[\"message\"]}'"

run_device_root "app_dir_ls_root" "ls -la '${APP_DIR}'"
run_device_root "run_dir_ls_root" "ls -la '${APP_DIR}/run'"
run_device_root "state_json_root" "cat '${APP_DIR}/state/state.json'"
run_device_root "current_release_root" "readlink -f '${APP_DIR}/current'"
run_device_root_notifier_shell "healthcheck_root" "\"\${APP_DIR}/bin/site-notifier-python.current\" \"\${APP_DIR}/current/app.py\" healthcheck; echo EXIT:\$?"
run_device_root_notifier_shell "status_local_root" "\"\${APP_DIR}/bin/site-notifier-python.current\" \"\${APP_DIR}/current/app.py\" status-local"
run_device_root_notifier_shell "diag_telegram_root" "\"\${APP_DIR}/bin/site-notifier-python.current\" \"\${APP_DIR}/current/app.py\" diag-telegram"
run_device_root "service_loop_log_tail_root" "tail -n 200 '${APP_DIR}/logs/service-loop.log' 2>/dev/null || true"
run_device_root "daemon_log_tail_root" "tail -n 200 '${APP_DIR}/logs/daemon.log' 2>/dev/null || true"
run_device_root_notifier_shell "telegram_getme_root" "curl -sS \"https://api.telegram.org/bot\${TELEGRAM_BOT_TOKEN}/getMe\""
run_device_root_notifier_shell "telegram_webhook_root" "curl -sS \"https://api.telegram.org/bot\${TELEGRAM_BOT_TOKEN}/getWebhookInfo\""
run_device_root_notifier_shell "telegram_getupdates_root" "curl -sS -X POST \"https://api.telegram.org/bot\${TELEGRAM_BOT_TOKEN}/getUpdates\" -H 'Content-Type: application/json' -d '{\"timeout\":0,\"allowed_updates\":[\"message\"]}'"

echo "Saved diagnostics to ${REPORT_DIR}"
