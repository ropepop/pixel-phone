#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TRAIN_BOT_ROOT="${WORKSPACE_ROOT}/workloads/train-bot"
REPO_ROOT="${TRAIN_BOT_ROOT}"

log() {
  printf '[%s] telegram_browser_session: %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

# shellcheck source=../../workloads/train-bot/scripts/pixel/browser_use.sh
source "${TRAIN_BOT_ROOT}/scripts/pixel/browser_use.sh"

SESSION_NAME="${BROWSER_USE_SESSION:-pixel-telegram}"
TELEGRAM_URL="${BROWSER_USE_CHAT_URL:-https://web.telegram.org/a/}"
PROFILE_SPEC="${BROWSER_USE_PROFILE:-}"
TARGET_SESSION_DIR="${BROWSER_USE_TELEGRAM_SESSION_DIR:-$(browser_use_saved_telegram_session_dir)}"

usage() {
  cat <<USAGE
Usage: $(basename "$0") <command> [options]

Manage the stable browser-use Telegram session stored under ops.

Commands:
  status                 show whether the saved bundle exists and whether the active browser session is logged in
  open                   open Telegram using the saved bundle when available, otherwise fall back to --profile
  bootstrap              open Telegram with a real Chrome profile, bypassing the saved bundle so you can log in manually
  save                   export cookies + storage from the active session into ${TARGET_SESSION_DIR}
  close                  close the active browser-use session
  env                    print export lines for reuse in shell scripts

Options:
  --session NAME         browser-use session name (default: ${SESSION_NAME})
  --profile NAME         browser-use Chrome profile to use for bootstrap/open fallback
  --url URL              Telegram Web URL (default: ${TELEGRAM_URL})
  --timeout SEC          browser-use timeout in seconds (default: 20)
  -h, --help             show this help

Examples:
  $(basename "$0") open
  $(basename "$0") bootstrap --profile "Your Chrome"
  $(basename "$0") save
USAGE
}

print_env() {
  printf 'export BROWSER_USE_SESSION=%q\n' "${SESSION_NAME}"
  printf 'export BROWSER_USE_TELEGRAM_SESSION_DIR=%q\n' "${TARGET_SESSION_DIR}"
  printf 'export BROWSER_USE_CHAT_URL=%q\n' "${TELEGRAM_URL}"
}

session_snapshot() {
  local timeout_s="$1"
  browser_use_run_timed "${SESSION_NAME}" "${timeout_s}" eval "(() => { const body = document.body ? (document.body.innerText || '') : ''; const loginPrompt = /Log in to Telegram by QR Code/i.test(body) ? 1 : 0; return 'title=' + document.title + ';loginPrompt=' + loginPrompt + ';url=' + window.location.href; })()"
}

show_status() {
  local timeout_s="$1"
  local saved_exists="no"
  if browser_use_has_saved_telegram_session; then
    saved_exists="yes"
  fi

  printf 'session=%s\n' "${SESSION_NAME}"
  printf 'saved_bundle_dir=%s\n' "${TARGET_SESSION_DIR}"
  printf 'saved_bundle_exists=%s\n' "${saved_exists}"
  printf 'telegram_url=%s\n' "${TELEGRAM_URL}"

  if browser_use_session_exists "${SESSION_NAME}"; then
    printf 'active_session=yes\n'
    session_snapshot "${timeout_s}"
  else
    printf 'active_session=no\n'
  fi
}

open_saved_or_profile() {
  local timeout_s="$1"
  browser_use_open_telegram_authenticated "${SESSION_NAME}" "${PROFILE_SPEC}" "${TELEGRAM_URL}" "${timeout_s}" >/dev/null
  show_status "${timeout_s}"
}

bootstrap_with_profile() {
  local timeout_s="$1"
  if [[ -z "${PROFILE_SPEC}" ]]; then
    log "bootstrap requires --profile with a browser-use Chrome profile name"
    exit 2
  fi

  browser_use_prepare_profile "${PROFILE_SPEC}"
  browser_use_run_timed "${SESSION_NAME}" "${timeout_s}" close >/dev/null 2>&1 || true
  browser_use_run_with_profile_timed "${SESSION_NAME}" "${timeout_s}" "${PROFILE_SPEC}" open "${TELEGRAM_URL}" >/dev/null
  show_status "${timeout_s}"
}

save_session() {
  local timeout_s="$1"
  if ! browser_use_session_exists "${SESSION_NAME}"; then
    log "No active browser-use session named ${SESSION_NAME}. Run 'open' or 'bootstrap' first."
    exit 1
  fi

  local snapshot=""
  snapshot="$(session_snapshot "${timeout_s}")" || true
  if printf '%s\n' "${snapshot}" | grep -q 'loginPrompt=1'; then
    log "Telegram is still showing the login screen for ${SESSION_NAME}; finish logging in before saving."
    exit 1
  fi

  local saved_dir=""
  saved_dir="$(browser_use_save_telegram_session "${SESSION_NAME}" "${TELEGRAM_URL}" "${timeout_s}")"
  printf 'saved_bundle_dir=%s\n' "${saved_dir}"
  printf 'session=%s\n' "${SESSION_NAME}"
  printf 'telegram_url=%s\n' "${TELEGRAM_URL}"
}

close_session() {
  browser_use_run_timed "${SESSION_NAME}" 15 close >/dev/null 2>&1 || true
  printf 'session=%s\n' "${SESSION_NAME}"
  printf 'active_session=no\n'
}

command="${1:-status}"
if (( $# > 0 )); then
  shift
fi

TIMEOUT_SEC="${BROWSER_USE_TIMEOUT_SEC:-20}"

while (( $# > 0 )); do
  case "$1" in
    --session)
      SESSION_NAME="${2:?missing value for --session}"
      shift 2
      ;;
    --profile)
      PROFILE_SPEC="${2:?missing value for --profile}"
      shift 2
      ;;
    --url)
      TELEGRAM_URL="${2:?missing value for --url}"
      shift 2
      ;;
    --timeout)
      TIMEOUT_SEC="${2:?missing value for --timeout}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unsupported argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

TARGET_SESSION_DIR="${BROWSER_USE_TELEGRAM_SESSION_DIR:-$(browser_use_saved_telegram_session_dir)}"

case "${command}" in
  status)
    show_status "${TIMEOUT_SEC}"
    ;;
  open)
    open_saved_or_profile "${TIMEOUT_SEC}"
    ;;
  bootstrap)
    bootstrap_with_profile "${TIMEOUT_SEC}"
    ;;
  save)
    save_session "${TIMEOUT_SEC}"
    ;;
  close)
    close_session
    ;;
  env)
    print_env
    ;;
  *)
    echo "Unsupported command: ${command}" >&2
    usage >&2
    exit 2
    ;;
esac
