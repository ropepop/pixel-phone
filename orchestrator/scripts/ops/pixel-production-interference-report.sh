#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

HOST="${PIXEL_PROD_REPORT_HOST:-192.168.31.25}"
ADB_SERIAL="${PIXEL_PROD_REPORT_ADB_SERIAL:-}"
OUTPUT_DIR="${PIXEL_PROD_REPORT_OUTPUT_DIR:-output}"
BASELINE_JSON=""
TIMEOUT_SEC="${PIXEL_PROD_REPORT_TIMEOUT_SEC:-2}"
HTTPS_PORT="${PIXEL_PROD_REPORT_HTTPS_PORT:-443}"
DOT_PORT="${PIXEL_PROD_REPORT_DOT_PORT:-853}"

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Generate production interference/resource diagnostics for Pixel rooted runtime.

Options:
  --host HOST             Host/IP for basic network probes (default: ${HOST})
  --adb-serial SERIAL     Target adb serial (default: first connected device)
  --output-dir DIR        Output directory for JSON/Markdown (default: ${OUTPUT_DIR})
  --baseline-json PATH    Optional prior JSON report for delta summary
  --timeout SEC           TCP probe timeout in seconds (default: ${TIMEOUT_SEC})
  --https-port PORT       DNS HTTPS/DoH port expected on Pixel before retirement checks (default: ${HTTPS_PORT})
  --dot-port PORT         DNS DoT port expected on Pixel before retirement checks (default: ${DOT_PORT})
  -h, --help              Show this help text
USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --host) shift; HOST="${1:-}" ;;
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --output-dir) shift; OUTPUT_DIR="${1:-}" ;;
    --baseline-json) shift; BASELINE_JSON="${1:-}" ;;
    --timeout) shift; TIMEOUT_SEC="${1:-}" ;;
    --https-port) shift; HTTPS_PORT="${1:-}" ;;
    --dot-port) shift; DOT_PORT="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ ! "${TIMEOUT_SEC}" =~ ^[0-9]+$ ]] || (( TIMEOUT_SEC < 1 )); then
  echo "--timeout must be a positive integer" >&2
  exit 2
fi

if [[ -n "${BASELINE_JSON}" && ! -f "${BASELINE_JSON}" ]]; then
  echo "Baseline JSON not found: ${BASELINE_JSON}" >&2
  exit 2
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd adb
require_cmd jq
require_cmd nc

resolve_adb_target() {
  if [[ -n "${ADB_SERIAL}" ]]; then
    printf '%s' "${ADB_SERIAL}"
    return 0
  fi

  pixel_transport_require_adb_serial
}

adb_target="$(resolve_adb_target)"
if [[ -z "${adb_target}" ]]; then
  echo "No adb target available. Pass --adb-serial or connect a device." >&2
  exit 1
fi

if ! adb -s "${adb_target}" get-state >/dev/null 2>&1; then
  echo "ADB target unreachable: ${adb_target}" >&2
  exit 1
fi

adb_su() {
  local cmd="$1"
  local encoded
  encoded="$(printf '%s\n' "${cmd}" | base64 | tr -d '\n')"
  adb -s "${adb_target}" shell "echo '${encoded}' | base64 -d | su -c sh" 2>/dev/null || true
}

json_lines() {
  printf '%s\n' "$1" | jq -Rcs 'split("\n") | map(select(length > 0))'
}

as_int() {
  local raw="$1"
  raw="$(printf '%s' "${raw}" | tr -d '\r' | tail -n 1)"
  if [[ "${raw}" =~ ^[0-9]+$ ]]; then
    printf '%s' "${raw}"
  else
    printf '0'
  fi
}

tcp_probe() {
  local host="$1"
  local port="$2"
  if nc -z -w "${TIMEOUT_SEC}" "${host}" "${port}" >/dev/null 2>&1; then
    printf 'open'
  else
    printf 'closed'
  fi
}

timestamp_iso() {
  date '+%Y-%m-%dT%H:%M:%S%z'
}

host_ping_state="failed"
if ping -c 1 "${HOST}" >/dev/null 2>&1; then
  host_ping_state="ok"
fi

host_port_53="$(tcp_probe "${HOST}" 53)"
host_port_https="$(tcp_probe "${HOST}" "${HTTPS_PORT}")"
host_port_dot="$(tcp_probe "${HOST}" "${DOT_PORT}")"
host_port_2222="$(tcp_probe "${HOST}" 2222)"
host_port_8080="$(tcp_probe "${HOST}" 8080)"

listeners_raw="$(adb_su "ss -ltnup 2>/dev/null | grep -E '(:53 |:${HTTPS_PORT} |:${DOT_PORT} |:2222 |:8080 )' || true")"
processes_raw="$(adb_su "ps -A -o PID,PPID,USER,NAME,ARGS 2>/dev/null | grep -E '(AdGuardHome|adguardhome|dropbear|adguardhome-remote-watchdog|pixel-ssh-service-loop|adguardhome-service-loop|cron)' || true")"

read -r -d '' resource_cmd <<'EOF_RESOURCE' || true
for p in $(pgrep -f "/data/local/pixel-stack/ssh/bin/dropbear|AdGuardHome|sh /data/local/pixel-stack/bin/adguardhome-service-loop|sh /data/local/pixel-stack/ssh/bin/pixel-ssh-service-loop|bash /usr/local/bin/adguardhome-remote-watchdog" 2>/dev/null); do
  rss=$(awk 'BEGIN{v=0} /^VmRSS:/ {v=$2} END{print v}' /proc/$p/status 2>/dev/null)
  fds=$(ls /proc/$p/fd 2>/dev/null | wc -l)
  cmd=$(tr '\000' ' ' < /proc/$p/cmdline 2>/dev/null)
  printf '%s\t%s\t%s\t%s\n' "$p" "${rss:-0}" "${fds:-0}" "$cmd"
done | sort -n
EOF_RESOURCE
resources_raw="$(adb_su "${resource_cmd}")"

legacy_path_exists="$(adb_su "if [ -e /data/adb/pixel-stack/ssh ]; then echo true; else echo false; fi" | tr -d '\r\n')"
legacy_dropbear_raw="$(adb_su "pgrep -af '^/data/adb/pixel-stack/ssh/bin/dropbear' || true")"
legacy_loop_raw="$(adb_su "pgrep -af '^(/system/bin/)?sh /data/adb/pixel-stack/ssh/bin/pixel-ssh-service-loop$' || true")"
local_dropbear_raw="$(adb_su "pgrep -af '^/data/local/pixel-stack/ssh/bin/dropbear' || true")"

dns_listener_failed="$(as_int "$(adb_su "grep -c 'listener health failed' /data/local/pixel-stack/logs/adguardhome-service-loop.log 2>/dev/null || true")")"
dns_forcing_restart="$(as_int "$(adb_su "grep -c 'forcing restart' /data/local/pixel-stack/logs/adguardhome-service-loop.log 2>/dev/null || true")")"
dns_rapid_restarts="$(as_int "$(adb_su "grep -c 'too many rapid restarts' /data/local/pixel-stack/logs/adguardhome-service-loop.log 2>/dev/null || true")")"
ssh_dropbear_rc1="$(as_int "$(adb_su "grep -c 'dropbear exited rc=1' /data/local/pixel-stack/ssh/logs/pixel-ssh-service-loop.log 2>/dev/null || true")")"
ssh_dropbear_rc143="$(as_int "$(adb_su "grep -c 'dropbear exited rc=143' /data/local/pixel-stack/ssh/logs/pixel-ssh-service-loop.log 2>/dev/null || true")")"
ssh_duplicate_loop="$(as_int "$(adb_su "grep -c 'another pixel-ssh-service-loop' /data/local/pixel-stack/ssh/logs/pixel-ssh-service-loop.log 2>/dev/null || true")")"
dropbear_addr_in_use="$(as_int "$(adb_su "grep -c 'Address already in use' /data/local/pixel-stack/ssh/logs/dropbear.log 2>/dev/null || true")")"
dropbear_invalid_option="$(as_int "$(adb_su "grep -c 'Invalid option' /data/local/pixel-stack/ssh/logs/dropbear.log 2>/dev/null || true")")"

critical_violations=()
while IFS= read -r line; do
  [[ -n "${line}" ]] || continue
  port="$(printf '%s\n' "${line}" | sed -nE 's/.*:([0-9]+)[[:space:]]+[^[:space:]]+[[:space:]]+users:.*/\1/p')"
  if [[ -z "${port}" ]]; then
    port="$(printf '%s\n' "${line}" | sed -nE 's/.*:([0-9]+)[[:space:]]+users:.*/\1/p')"
  fi
  [[ -n "${port}" ]] || continue

  owner="$(printf '%s\n' "${line}" | sed -nE 's/.*users:\(\("([^"]+)".*/\1/p' | head -n 1)"
  expected=""
  case "${port}" in
    53|8080) expected="AdGuardHome|adguardhome" ;;
    ${HTTPS_PORT}|${DOT_PORT}) expected="AdGuardHome|adguardhome|nginx" ;;
    2222) expected="dropbear" ;;
    *) expected="" ;;
  esac

  if [[ -n "${expected}" ]]; then
    if ! [[ "${owner}" =~ ^(${expected})$ ]]; then
      critical_violations+=("port=${port} owner=${owner:-unknown} expected=${expected} line=${line}")
    fi
  fi
done <<< "${listeners_raw}"

listeners_json="$(json_lines "${listeners_raw}")"
processes_json="$(json_lines "${processes_raw}")"
legacy_dropbear_json="$(json_lines "${legacy_dropbear_raw}")"
legacy_loop_json="$(json_lines "${legacy_loop_raw}")"
local_dropbear_json="$(json_lines "${local_dropbear_raw}")"
critical_violations_json="$(printf '%s\n' "${critical_violations[@]:-}" | jq -Rcs 'split("\n") | map(select(length > 0))')"

resources_json="$(printf '%s\n' "${resources_raw}" | jq -Rcs '
  split("\n")
  | map(select(length > 0) | split("\t"))
  | map({
      pid: (.[0] | tonumber),
      rss_kb: (.[1] | tonumber),
      fds: (.[2] | tonumber),
      cmd: (.[3] // "")
    })
')"

resources_totals_json="$(printf '%s\n' "${resources_raw}" | awk -F'\t' '
  BEGIN {rss=0; fds=0; n=0}
  NF >= 3 {
    rss += ($2 + 0)
    fds += ($3 + 0)
    n += 1
  }
  END {
    printf("{\"process_count\":%d,\"rss_kb\":%d,\"fds\":%d}\n", n, rss, fds)
  }
')"

mkdir -p "${OUTPUT_DIR}"
stamp="$(date '+%Y%m%d-%H%M%S')"
json_out="${OUTPUT_DIR}/pixel-production-interference-${stamp}.json"
md_out="${OUTPUT_DIR}/pixel-production-interference-${stamp}.md"

jq -n \
  --arg timestamp "$(timestamp_iso)" \
  --arg host "${HOST}" \
  --arg adb_target "${adb_target}" \
  --arg baseline_json "${BASELINE_JSON}" \
  --arg host_ping_state "${host_ping_state}" \
  --arg host_port_53 "${host_port_53}" \
  --arg https_port "${HTTPS_PORT}" \
  --arg dot_port "${DOT_PORT}" \
  --arg host_port_https "${host_port_https}" \
  --arg host_port_dot "${host_port_dot}" \
  --arg host_port_2222 "${host_port_2222}" \
  --arg host_port_8080 "${host_port_8080}" \
  --arg legacy_path_exists "${legacy_path_exists}" \
  --argjson listeners "${listeners_json}" \
  --argjson processes "${processes_json}" \
  --argjson resources "${resources_json}" \
  --argjson resources_totals "${resources_totals_json}" \
  --argjson legacy_dropbear_processes "${legacy_dropbear_json}" \
  --argjson legacy_loop_processes "${legacy_loop_json}" \
  --argjson local_dropbear_processes "${local_dropbear_json}" \
  --argjson critical_port_owner_violations "${critical_violations_json}" \
  --argjson dns_listener_failed "${dns_listener_failed}" \
  --argjson dns_forcing_restart "${dns_forcing_restart}" \
  --argjson dns_rapid_restarts "${dns_rapid_restarts}" \
  --argjson ssh_dropbear_rc1 "${ssh_dropbear_rc1}" \
  --argjson ssh_dropbear_rc143 "${ssh_dropbear_rc143}" \
  --argjson ssh_duplicate_loop "${ssh_duplicate_loop}" \
  --argjson dropbear_addr_in_use "${dropbear_addr_in_use}" \
  --argjson dropbear_invalid_option "${dropbear_invalid_option}" \
  '
  {
    timestamp: $timestamp,
    target: {
      host: $host,
      adb_target: $adb_target
    },
    settings: {
      baseline_json: (if $baseline_json == "" then null else $baseline_json end)
    },
    host_probes: {
      ping: $host_ping_state,
      ports: {
        "53": $host_port_53,
        ($https_port): $host_port_https,
        ($dot_port): $host_port_dot,
        "2222": $host_port_2222,
        "8080": $host_port_8080
      }
    },
    runtime: {
      listeners: $listeners,
      processes: $processes,
      resources: $resources,
      resources_totals: $resources_totals,
      local_dropbear_processes: $local_dropbear_processes,
      legacy_path_exists: ($legacy_path_exists == "true"),
      legacy_dropbear_processes: $legacy_dropbear_processes,
      legacy_loop_processes: $legacy_loop_processes
    },
    counters: {
      dns: {
        listener_failed: $dns_listener_failed,
        forcing_restart: $dns_forcing_restart,
        rapid_restarts: $dns_rapid_restarts
      },
      ssh: {
        dropbear_exit_rc1: $ssh_dropbear_rc1,
        dropbear_exit_rc143: $ssh_dropbear_rc143,
        duplicate_loop_guard_hits: $ssh_duplicate_loop,
        dropbear_address_in_use: $dropbear_addr_in_use,
        dropbear_invalid_option: $dropbear_invalid_option
      }
    },
    interference: {
      critical_port_owner_violations: $critical_port_owner_violations,
      has_critical_port_owner_violations: (($critical_port_owner_violations | length) > 0)
    }
  }
  ' > "${json_out}"

{
  echo "# Pixel Production Interference Report"
  echo
  echo "- Generated: $(jq -r '.timestamp' "${json_out}")"
  echo "- Host: $(jq -r '.target.host' "${json_out}")"
  echo "- ADB target: $(jq -r '.target.adb_target' "${json_out}")"
  echo
  echo "## Host Probes"
  echo
  echo "| Probe | State |"
  echo "| --- | --- |"
  echo "| ping | $(jq -r '.host_probes.ping' "${json_out}") |"
  echo "| tcp/53 | $(jq -r '.host_probes.ports["53"]' "${json_out}") |"
  echo "| tcp/${HTTPS_PORT} | $(jq -r --arg port "${HTTPS_PORT}" '.host_probes.ports[$port]' "${json_out}") |"
  echo "| tcp/${DOT_PORT} | $(jq -r --arg port "${DOT_PORT}" '.host_probes.ports[$port]' "${json_out}") |"
  echo "| tcp/2222 | $(jq -r '.host_probes.ports["2222"]' "${json_out}") |"
  echo "| tcp/8080 | $(jq -r '.host_probes.ports["8080"]' "${json_out}") |"
  echo
  echo "## Runtime Footprint"
  echo
  echo "- process_count: $(jq -r '.runtime.resources_totals.process_count' "${json_out}")"
  echo "- total_rss_kb: $(jq -r '.runtime.resources_totals.rss_kb' "${json_out}")"
  echo "- total_fds: $(jq -r '.runtime.resources_totals.fds' "${json_out}")"
  echo "- legacy_path_exists: $(jq -r '.runtime.legacy_path_exists' "${json_out}")"
  echo "- local_dropbear_processes: $(jq -r '.runtime.local_dropbear_processes | length' "${json_out}")"
  echo "- legacy_dropbear_processes: $(jq -r '.runtime.legacy_dropbear_processes | length' "${json_out}")"
  echo
  echo "## Restart/Error Counters"
  echo
  echo "- dns.listener_failed: $(jq -r '.counters.dns.listener_failed' "${json_out}")"
  echo "- dns.forcing_restart: $(jq -r '.counters.dns.forcing_restart' "${json_out}")"
  echo "- dns.rapid_restarts: $(jq -r '.counters.dns.rapid_restarts' "${json_out}")"
  echo "- ssh.dropbear_exit_rc1: $(jq -r '.counters.ssh.dropbear_exit_rc1' "${json_out}")"
  echo "- ssh.dropbear_exit_rc143: $(jq -r '.counters.ssh.dropbear_exit_rc143' "${json_out}")"
  echo "- ssh.duplicate_loop_guard_hits: $(jq -r '.counters.ssh.duplicate_loop_guard_hits' "${json_out}")"
  echo "- ssh.dropbear_address_in_use: $(jq -r '.counters.ssh.dropbear_address_in_use' "${json_out}")"
  echo "- ssh.dropbear_invalid_option: $(jq -r '.counters.ssh.dropbear_invalid_option' "${json_out}")"
  echo
  echo "## Critical Port Owner Violations"
  echo
  if [[ "$(jq -r '.interference.has_critical_port_owner_violations' "${json_out}")" == "true" ]]; then
    echo '```text'
    jq -r '.interference.critical_port_owner_violations[]' "${json_out}"
    echo '```'
  else
    echo "(none)"
  fi

  if [[ -n "${BASELINE_JSON}" ]] && jq empty "${BASELINE_JSON}" >/dev/null 2>&1; then
    current_rss="$(jq -r '.runtime.resources_totals.rss_kb // 0' "${json_out}")"
    baseline_rss="$(jq -r '.runtime.resources_totals.rss_kb // 0' "${BASELINE_JSON}")"
    current_fds="$(jq -r '.runtime.resources_totals.fds // 0' "${json_out}")"
    baseline_fds="$(jq -r '.runtime.resources_totals.fds // 0' "${BASELINE_JSON}")"

    echo
    echo "## Baseline Delta"
    echo
    printf '| Metric | Baseline | Current | Delta |\n'
    printf '| --- | ---: | ---: | ---: |\n'
    printf '| total_rss_kb | %s | %s | %+d |\n' "${baseline_rss}" "${current_rss}" "$((current_rss - baseline_rss))"
    printf '| total_fds | %s | %s | %+d |\n' "${baseline_fds}" "${current_fds}" "$((current_fds - baseline_fds))"
  fi
} > "${md_out}"

echo "JSON report: ${json_out}"
echo "Markdown report: ${md_out}"
