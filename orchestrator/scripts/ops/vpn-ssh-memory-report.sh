#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

ADB_SERIAL="${VPN_SSH_MEMORY_ADB_SERIAL:-}"
OUTPUT_DIR="${VPN_SSH_MEMORY_OUTPUT_DIR:-ops/evidence/vpn-access}"
TAILSCALED_MAX_KB="${VPN_SSH_MEMORY_TAILSCALED_MAX_KB:-25000}"
TOTAL_MAX_KB="${VPN_SSH_MEMORY_TOTAL_MAX_KB:-35000}"
ENFORCE_THRESHOLDS=0

usage() {
  cat <<EOF_USAGE
Usage: $(basename "$0") [options]

Collect tailscaled + SSH runtime memory footprint and emit JSON/Markdown reports.

Options:
  --adb-serial SERIAL       adb serial/device (default: first "device")
  --output-dir DIR          output directory (default: ${OUTPUT_DIR})
  --tailscaled-max-kb N     tailscaled RSS threshold (default: ${TAILSCALED_MAX_KB})
  --total-max-kb N          combined VPN+SSH RSS threshold (default: ${TOTAL_MAX_KB})
  --enforce-thresholds      exit non-zero when thresholds are exceeded
  -h, --help                Show this help text
EOF_USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial)
      shift
      ADB_SERIAL="${1:-}"
      ;;
    --output-dir)
      shift
      OUTPUT_DIR="${1:-}"
      ;;
    --tailscaled-max-kb)
      shift
      TAILSCALED_MAX_KB="${1:-}"
      ;;
    --total-max-kb)
      shift
      TOTAL_MAX_KB="${1:-}"
      ;;
    --enforce-thresholds)
      ENFORCE_THRESHOLDS=1
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

for val in "${TAILSCALED_MAX_KB}" "${TOTAL_MAX_KB}"; do
  [[ "${val}" =~ ^[0-9]+$ ]] || { echo "Thresholds must be numeric" >&2; exit 2; }
done

command -v adb >/dev/null 2>&1 || { echo "adb is required" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

if [[ -z "${ADB_SERIAL}" ]]; then
  ADB_SERIAL="$(pixel_transport_require_adb_serial)"
fi
[[ -n "${ADB_SERIAL}" ]] || { echo "No adb device in 'device' state found. Use --adb-serial." >&2; exit 1; }
adb -s "${ADB_SERIAL}" get-state >/dev/null 2>&1 || { echo "ADB target unreachable: ${ADB_SERIAL}" >&2; exit 1; }

timestamp_iso() { date '+%Y-%m-%dT%H:%M:%S%z'; }

read -r -d '' remote_cmd <<'EOF_REMOTE' || true
for p in $(pgrep -f "tailscaled|dropbear|pixel-ssh-service-loop" 2>/dev/null); do
  rss=$(awk 'BEGIN{v=0} /^VmRSS:/ {v=$2} END{print v}' /proc/$p/status 2>/dev/null)
  cmd=$(tr '\000' ' ' < /proc/$p/cmdline 2>/dev/null)
  printf '%s\t%s\t%s\n' "$p" "${rss:-0}" "$cmd"
done | sort -n
EOF_REMOTE

raw="$(printf '%s\n' "${remote_cmd}" | adb -s "${ADB_SERIAL}" shell su -c "sh -s" 2>/dev/null || true)"

processes_json="$(printf '%s\n' "${raw}" | jq -Rcs '
  split("\n")
  | map(select(length > 0) | split("\t"))
  | map({
      pid: (.[0] | tonumber),
      rss_kb: (.[1] | tonumber),
      cmd: (.[2] // ""),
      component: (
        if (.[2] // "" | test("(^|[[:space:]/])tailscaled([[:space:]]|$)")) then "tailscaled"
        elif (.[2] // "" | test("(^|[[:space:]/])dropbear([[:space:]]|$)|/ssh/bin/dropbear")) then "ssh_dropbear"
        elif (.[2] // "" | test("pixel-ssh-service-loop")) then "ssh_loop"
        else "other"
        end
      )
    })
')"

tailscaled_rss_kb="$(printf '%s\n' "${processes_json}" | jq '[.[] | select(.component=="tailscaled") | .rss_kb] | add // 0')"
ssh_dropbear_rss_kb="$(printf '%s\n' "${processes_json}" | jq '[.[] | select(.component=="ssh_dropbear") | .rss_kb] | add // 0')"
ssh_loop_rss_kb="$(printf '%s\n' "${processes_json}" | jq '[.[] | select(.component=="ssh_loop") | .rss_kb] | add // 0')"
vpn_plus_ssh_total_rss_kb="$((tailscaled_rss_kb + ssh_dropbear_rss_kb + ssh_loop_rss_kb))"

tailscaled_within_limit=true
total_within_limit=true
if (( tailscaled_rss_kb > TAILSCALED_MAX_KB )); then
  tailscaled_within_limit=false
fi
if (( vpn_plus_ssh_total_rss_kb > TOTAL_MAX_KB )); then
  total_within_limit=false
fi
overall_pass=true
if [[ "${tailscaled_within_limit}" != "true" || "${total_within_limit}" != "true" ]]; then
  overall_pass=false
fi

mkdir -p "${OUTPUT_DIR}"
stamp="$(date '+%Y%m%d-%H%M%S')"
json_out="${OUTPUT_DIR}/vpn-ssh-memory-${stamp}.json"
md_out="${OUTPUT_DIR}/vpn-ssh-memory-${stamp}.md"

jq -n \
  --arg timestamp "$(timestamp_iso)" \
  --arg adb_serial "${ADB_SERIAL}" \
  --argjson processes "${processes_json}" \
  --argjson tailscaled_rss_kb "${tailscaled_rss_kb}" \
  --argjson ssh_dropbear_rss_kb "${ssh_dropbear_rss_kb}" \
  --argjson ssh_loop_rss_kb "${ssh_loop_rss_kb}" \
  --argjson vpn_plus_ssh_total_rss_kb "${vpn_plus_ssh_total_rss_kb}" \
  --argjson tailscaled_max_kb "${TAILSCALED_MAX_KB}" \
  --argjson total_max_kb "${TOTAL_MAX_KB}" \
  --argjson tailscaled_within_limit "${tailscaled_within_limit}" \
  --argjson total_within_limit "${total_within_limit}" \
  --argjson overall_pass "${overall_pass}" \
  '
  {
    timestamp: $timestamp,
    target: {
      adb_serial: $adb_serial
    },
    thresholds: {
      tailscaled_max_kb: $tailscaled_max_kb,
      vpn_plus_ssh_total_max_kb: $total_max_kb
    },
    metrics: {
      tailscaled_rss_kb: $tailscaled_rss_kb,
      ssh_dropbear_rss_kb: $ssh_dropbear_rss_kb,
      ssh_loop_rss_kb: $ssh_loop_rss_kb,
      vpn_plus_ssh_total_rss_kb: $vpn_plus_ssh_total_rss_kb
    },
    checks: {
      tailscaled_within_limit: $tailscaled_within_limit,
      vpn_plus_ssh_total_within_limit: $total_within_limit,
      overall_pass: $overall_pass
    },
    processes: $processes
  }
  ' > "${json_out}"

{
  echo "# VPN + SSH Memory Report"
  echo
  echo "- Generated: $(jq -r '.timestamp' "${json_out}")"
  echo "- ADB serial: $(jq -r '.target.adb_serial' "${json_out}")"
  echo
  echo "## RSS Metrics (kB)"
  echo
  echo "| Metric | Value | Threshold | Status |"
  echo "| --- | ---: | ---: | --- |"
  echo "| tailscaled_rss_kb | ${tailscaled_rss_kb} | ${TAILSCALED_MAX_KB} | ${tailscaled_within_limit} |"
  echo "| vpn_plus_ssh_total_rss_kb | ${vpn_plus_ssh_total_rss_kb} | ${TOTAL_MAX_KB} | ${total_within_limit} |"
  echo
  echo "## Process Rows"
  echo
  jq -r '.processes[]? | "- pid=\(.pid) component=\(.component) rss_kb=\(.rss_kb) cmd=\(.cmd)"' "${json_out}"
  echo
  echo "## Overall"
  echo
  echo "- overall_pass: ${overall_pass}"
} > "${md_out}"

echo "JSON report written: ${json_out}"
echo "Markdown report written: ${md_out}"

if (( ENFORCE_THRESHOLDS == 1 )) && [[ "${overall_pass}" != "true" ]]; then
  echo "Memory thresholds exceeded" >&2
  exit 1
fi
