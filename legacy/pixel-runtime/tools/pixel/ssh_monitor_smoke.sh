#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./transport.sh
source "${SCRIPT_DIR}/transport.sh"

PIXEL_TRANSPORT="${PIXEL_TRANSPORT:-ssh}"

usage() {
  cat <<'USAGE'
Usage: ssh_monitor_smoke.sh [options]

Options:
  --transport MODE    transport to use (default: ssh)
  --device SERIAL     adb serial to target
  --ssh-host IP       Tailscale or SSH host/IP
  --ssh-port PORT     SSH port (default: 2222)
  -h, --help          show help
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
  shift
done

pixel_transport_require_device >/dev/null
if [[ "${PIXEL_TRANSPORT_RESOLVED}" != "ssh" ]]; then
  echo "ssh_monitor_smoke.sh requires SSH transport; resolved ${PIXEL_TRANSPORT_RESOLVED}" >&2
  exit 1
fi

management_report="$(
  pixel_transport_root_shell \
    "PIXEL_MANAGEMENT_HEALTH_REPORT=1 /system/bin/sh /data/local/pixel-stack/bin/pixel-management-health.sh --report"
)"

listeners="$(
  pixel_transport_root_shell \
    'ss -ltn 2>/dev/null | while IFS= read -r line; do
       case "$line" in
         *:53" "*|*:443" "*|*:853" "*|*:2222" "*) printf "%s\n" "$line" ;;
       esac
     done'
)"

process_summary="$(
  pixel_transport_root_shell '
    set -eu
    summarize() {
      name="$1"
      pattern="$2"
      match="$(pgrep -af "$pattern" 2>/dev/null | head -n 1 || true)"
      if [ -n "$match" ]; then
        printf "%s=running %s\n" "$name" "$match"
      else
        printf "%s=missing\n" "$name"
      fi
    }
    summarize ssh "/data/local/pixel-stack/ssh/bin/dropbear"
    summarize vpn "/tailscaled|/data/local/pixel-stack/vpn/bin/tailscaled"
    summarize dns "/data/local/pixel-stack/bin/adguardhome-start|/data/local/pixel-stack/bin/pixel-dns-start.sh|AdGuardHome"
    summarize train_bot "/data/local/pixel-stack/apps/train-bot"
    summarize satiksme_bot "/data/local/pixel-stack/apps/satiksme-bot"
    summarize site_notifier "/data/local/pixel-stack/apps/site-notifications|/data/local/pixel-stack/apps/site-notifier"
  '
)"

state_json="$(
  pixel_transport_root_shell \
    "run-as lv.jolkins.pixelorchestrator cat files/stack-store/orchestrator-state-v1.json"
)"

printf 'management_report:\n%s\n' "${management_report}"
printf '\nlisteners:\n%s\n' "${listeners}"
printf '\nprocesses:\n%s\n' "${process_summary}"
printf '\nservices:\n'
printf '%s\n' "${state_json}" | python3 - <<'PY'
import json
import sys

payload = json.load(sys.stdin)
services = payload.get("services") or {}
for key in (
    "dns",
    "ssh",
    "vpn",
    "management",
    "remote",
    "train_bot",
    "satiksme_bot",
    "site_notifier",
):
    entry = services.get(key) or {}
    status = entry.get("status", "unknown")
    pid = entry.get("pid")
    detail = f" pid={pid}" if pid not in (None, "", 0) else ""
    print(f"{key}={status}{detail}")
PY
