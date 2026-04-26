#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

ADB_SERIAL="${ADB_SERIAL:-}"
OUTPUT_DIR="${OUTPUT_DIR:-ops/evidence/adguardhome-cutover}"
DOH_URL="${DOH_URL:-}"

usage() {
  cat <<EOH
Usage: adguardhome_migration_snapshot.sh [options]

Creates a pre-cutover snapshot and migration payload for Pi-hole -> AdGuard Home.

Options:
  --adb-serial SERIAL   Target adb serial (default: first connected device)
  --output-dir PATH     Output directory (default: ops/evidence/adguardhome-cutover)
  --doh-url URL         Optional DoH URL to probe and record before cutover
  -h, --help            Show this help text
EOH
}

while (( $# > 0 )); do
  case "$1" in
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --output-dir) shift; OUTPUT_DIR="${1:-}" ;;
    --doh-url) shift; DOH_URL="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

command -v adb >/dev/null 2>&1 || { echo "adb is required" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }
command -v sqlite3 >/dev/null 2>&1 || { echo "sqlite3 is required" >&2; exit 1; }

if [[ -z "${ADB_SERIAL}" ]]; then
  ADB_SERIAL="$(pixel_transport_require_adb_serial)"
fi
[[ -n "${ADB_SERIAL}" ]] || { echo "No adb device found in state 'device'" >&2; exit 1; }

stamp="$(date +%Y%m%d-%H%M%S)"
root="${OUTPUT_DIR}/${stamp}"
mkdir -p "${root}" "${root}/snapshot" "${root}/evidence"

echo "Using adb serial: ${ADB_SERIAL}"
echo "Writing snapshot: ${root}"

adb_su_cat() {
  local remote_path="$1"
  adb -s "${ADB_SERIAL}" exec-out su -c "cat '${remote_path}'" 2>/dev/null
}

adb_su_cmd() {
  local cmd="$1"
  adb -s "${ADB_SERIAL}" shell "su -c \"${cmd}\"" 2>/dev/null
}

copy_remote_file() {
  local remote_path="$1"
  local local_path="$2"
  if adb_su_cmd "test -f '${remote_path}'" >/dev/null 2>&1; then
    adb_su_cat "${remote_path}" > "${local_path}" || true
  else
    printf '' > "${local_path}.missing"
  fi
}

copy_remote_file "/data/local/pixel-stack/chroots/pihole/etc/pihole/gravity.db" "${root}/snapshot/gravity.db"
copy_remote_file "/data/local/pixel-stack/chroots/pihole/etc/pihole/pihole.toml" "${root}/snapshot/pihole.toml"
copy_remote_file "/data/local/pixel-stack/chroots/pihole/etc/nginx/pixel-stack-pihole-remote-nginx.conf" "${root}/snapshot/pixel-stack-pihole-remote-nginx.conf"
copy_remote_file "/data/local/pixel-stack/chroots/pihole/etc/pixel-stack/remote-dns/runtime.env" "${root}/snapshot/runtime.env"
copy_remote_file "/data/local/pixel-stack/conf/orchestrator-config-v1.json" "${root}/snapshot/orchestrator-config-v1.json"

adlists_json_file="${root}/snapshot/adlists.json"
domainlist_json_file="${root}/snapshot/domainlist.json"
payload_file="${root}/snapshot/pihole-migration-payload.json"

if [[ -s "${root}/snapshot/gravity.db" ]]; then
  sqlite3 -json "${root}/snapshot/gravity.db" "SELECT address AS url, enabled FROM adlist ORDER BY id;" > "${adlists_json_file}" || echo '[]' > "${adlists_json_file}"
  sqlite3 -json "${root}/snapshot/gravity.db" "SELECT domain, type, enabled FROM domainlist ORDER BY id;" > "${domainlist_json_file}" || echo '[]' > "${domainlist_json_file}"
else
  echo '[]' > "${adlists_json_file}"
  echo '[]' > "${domainlist_json_file}"
fi

jq -n \
  --arg generated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg source_gravity "/data/local/pixel-stack/chroots/pihole/etc/pihole/gravity.db" \
  --slurpfile adlists "${adlists_json_file}" \
  --slurpfile domains "${domainlist_json_file}" \
  '{
    generated_at: $generated_at,
    source: {
      gravity_db: $source_gravity
    },
    adlists: (($adlists[0] // []) | map(select((.enabled // 1 | tonumber) == 1) | {url: .url, enabled: (.enabled // 1)})),
    domain_rules: {
      allow_exact: (($domains[0] // []) | map(select((.enabled // 1 | tonumber) == 1 and (.type | tonumber) == 0) | .domain)),
      block_exact: (($domains[0] // []) | map(select((.enabled // 1 | tonumber) == 1 and (.type | tonumber) == 1) | .domain)),
      allow_regex: (($domains[0] // []) | map(select((.enabled // 1 | tonumber) == 1 and (.type | tonumber) == 2) | .domain)),
      block_regex: (($domains[0] // []) | map(select((.enabled // 1 | tonumber) == 1 and (.type | tonumber) == 3) | .domain))
    },
    upstreams: ["https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"]
  }' > "${payload_file}"

adb_su_cmd "mkdir -p /data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state" || true
adb -s "${ADB_SERIAL}" push "${payload_file}" "/sdcard/pihole-migration-payload.json" >/dev/null
adb_su_cmd "cp /sdcard/pihole-migration-payload.json /data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/pihole-migration-payload.json && chmod 600 /data/local/pixel-stack/chroots/adguardhome/etc/pixel-stack/remote-dns/state/pihole-migration-payload.json" || true
adb_su_cmd "rm -f /sdcard/pihole-migration-payload.json" || true

adb_su_cmd "date -u +%Y-%m-%dT%H:%M:%SZ" > "${root}/evidence/timestamp.txt" || true
adb_su_cmd "ss -ltnup 2>/dev/null || true" > "${root}/evidence/listeners.txt" || true
adb_su_cmd "ps -A -o PID,PPID,USER,NAME,ARGS 2>/dev/null | grep -E '(pihole|AdGuardHome|dnscrypt|nginx|doh|service-loop)' || true" > "${root}/evidence/processes.txt" || true
adb_su_cmd "chroot /data/local/pixel-stack/chroots/pihole sh -lc 'command -v curl >/dev/null && curl -ksS -o /dev/null -w \"%{http_code}\" http://127.0.0.1:8080/admin/ || true'" > "${root}/evidence/local-admin-http-code.txt" || true

if [[ -n "${DOH_URL}" ]]; then
  if command -v curl >/dev/null 2>&1; then
    curl -ksS -o /dev/null -w '%{http_code}\n' "${DOH_URL}" > "${root}/evidence/public-doh-http-code.txt" || true
  fi
fi

jq -n \
  --arg serial "${ADB_SERIAL}" \
  --arg output "${root}" \
  --arg payload "${payload_file}" \
  --slurpfile adlists "${adlists_json_file}" \
  --slurpfile domains "${domainlist_json_file}" \
  '{
    adb_serial: $serial,
    output_root: $output,
    migration_payload: $payload,
    adlists_enabled: (($adlists[0] // []) | map(select((.enabled // 1 | tonumber) == 1)) | length),
    domainlist_rows: (($domains[0] // []) | length),
    generated_at: (now | todate)
  }' > "${root}/snapshot/summary.json"

echo "Snapshot complete: ${root}"
echo "Migration payload: ${payload_file}"
