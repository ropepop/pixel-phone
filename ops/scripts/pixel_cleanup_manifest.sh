#!/usr/bin/env bash
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-100.76.50.43:5555}"
OUTPUT_DIR=""

usage() {
  echo "Usage: $(basename "$0") --output-dir DIR [--device ADB_SERIAL]" >&2
}

while (( $# > 0 )); do
  case "$1" in
    --device) shift; ADB_SERIAL="${1:-}" ;;
    --output-dir) shift; OUTPUT_DIR="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

[[ -n "${OUTPUT_DIR}" ]] || { usage; exit 2; }
mkdir -p "${OUTPUT_DIR}"

adb_cmd=(adb -s "${ADB_SERIAL}")
"${adb_cmd[@]}" get-state >/dev/null

root_shell() {
  local encoded
  encoded="$(printf '%s' "$1" | base64 | tr -d '\n')"
  "${adb_cmd[@]}" shell "echo '${encoded}' | base64 -d | su -c sh" </dev/null | tr -d '\r'
}

root_shell 'df -k /data; du -sk /data/local/pixel-stack /data/user/0/lv.jolkins.pixelorchestrator/cache 2>/dev/null || true' \
  > "${OUTPUT_DIR}/disk-before.txt"
root_shell 'ps -A -o PID,PPID,USER,NAME,ARGS 2>/dev/null || ps -A' \
  > "${OUTPUT_DIR}/processes-before.txt"
root_shell 'ss -ltnup 2>/dev/null || true' > "${OUTPUT_DIR}/listeners-before.txt"

manifest_file="${OUTPUT_DIR}/cleanup-manifest.tsv"
remote_inventory=""
IFS= read -r -d '' remote_inventory <<'REMOTE_INVENTORY' || true
printf 'path\tbytes\tsha256\towner\tmode\tmtime_epoch\tclassification\tmanifest_refs\topen_pids\n'
{
  find /data/user/0/lv.jolkins.pixelorchestrator/cache/runtime-artifacts -mindepth 1 -maxdepth 1 -type f -print 2>/dev/null || true
  find /data/user/0/lv.jolkins.pixelorchestrator/cache/support-bundles -mindepth 1 -maxdepth 1 -type f -print 2>/dev/null || true
  find /data/local/pixel-stack/conf/runtime/artifacts -mindepth 1 -maxdepth 2 -type f -print 2>/dev/null || true
  find /data/local/pixel-stack/conf/runtime/components -mindepth 3 -maxdepth 3 -type f -path '*/artifacts/*' -print 2>/dev/null || true
  find /data/local/pixel-stack/run/orchestrator-action-results -mindepth 1 -maxdepth 1 -type f -name '*.json' -print 2>/dev/null || true
  for path in \
    /data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db \
    /data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db-wal \
    /data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db-shm \
    /sdcard/pixel-ticket-window.xml \
    /data/local/tmp/pixel-vivi-fast-return-window.xml \
    /data/local/tmp/rs-direct-window.xml
  do
    [ ! -e "$path" ] || echo "$path"
  done
  find \
    /data/local/pixel-stack/chroots/adguardhome/var/log/adguardhome \
    /data/local/pixel-stack/chroots/adguardhome/opt/adguardhome/work/data \
    /data/local/pixel-stack/state/adguardhome/work/data \
    -maxdepth 1 -type f \( -name '*.log' -o -name '*.log.1' -o -name 'querylog.json' -o -name 'querylog.json.1' \) \
    -print 2>/dev/null || true
  find \
    /data/local/pixel-stack/chroots/pihole/var/log/pihole \
    /data/local/pixel-stack/chroots/pihole/var/log/pihole-rooted \
    -maxdepth 1 -type f \( -name '*.log' -o -name '*.log.1' \) -print 2>/dev/null || true
  find /data/local/pixel-stack/apps -maxdepth 4 -type f -path '*/logs/*' -print 2>/dev/null || true
  find \
    /data/local/pixel-stack/logs \
    /data/local/pixel-stack/vpn/logs \
    /data/local/pixel-stack/ssh/logs \
    -maxdepth 1 -type f \( -name '*.log' -o -name '*.log.1' -o -name '*.log.2' -o -name '*.log.3' -o -name '*.log.old' -o -name '*.log.bak*' \) \
    -print 2>/dev/null || true
} | sed '/^$/d' | sort -u | while IFS= read -r path; do
  metadata=$(stat -c '%s %u:%g %a %Y' "$path" 2>/dev/null || true)
  [ -n "$metadata" ] || continue
  set -- $metadata
  bytes=$1
  owner=$2
  mode=$3
  mtime=$4
  sha256=$(sha256sum "$path" 2>/dev/null | sed -n '1s/[[:space:]].*//p')
  manifest_refs=""
  case "$path" in
    /data/local/pixel-stack/conf/runtime/*)
      manifest_refs=$(find /data/local/pixel-stack/conf/runtime -maxdepth 5 -type f -name '*.json' -exec grep -lF -- "$path" {} \; 2>/dev/null | paste -sd, -)
      ;;
  esac
  open_pids=""
  classification=unknown
  case "$path" in
    /data/user_de/*/sulogs.db*) classification=root_command_history ;;
    /sdcard/pixel-ticket-window.xml|/data/local/tmp/pixel-vivi-fast-return-window.xml|/data/local/tmp/rs-direct-window.xml) classification=transient_ticket_hierarchy ;;
    /data/user/0/lv.jolkins.pixelorchestrator/cache/runtime-artifacts/*) classification=app_cache_staging ;;
    /data/user/0/lv.jolkins.pixelorchestrator/cache/support-bundles/*) classification=temporary_support_bundle ;;
    /data/local/pixel-stack/run/orchestrator-action-results/*) classification=deployment_action_result ;;
    /data/local/pixel-stack/chroots/adguardhome/*|/data/local/pixel-stack/state/adguardhome/*|/data/local/pixel-stack/chroots/pihole/*) classification=retired_dns_history ;;
    /data/local/pixel-stack/apps/*/logs/*|/data/local/pixel-stack/vpn/logs/*|/data/local/pixel-stack/ssh/logs/*|/data/local/pixel-stack/logs/*) classification=managed_stack_log ;;
    /data/local/pixel-stack/conf/runtime/*)
      if echo "$manifest_refs" | grep -q previous; then
        classification=rollback_artifact
      elif [ -n "$manifest_refs" ]; then
        classification=active_manifest_artifact
      else
        classification=unreferenced_deployment_artifact
      fi
      ;;
  esac
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$path" "$bytes" "$sha256" "$owner" "$mode" "$mtime" \
    "$classification" "$manifest_refs" "$open_pids"
done
REMOTE_INVENTORY
root_shell "${remote_inventory}" > "${manifest_file}"
root_shell 'lsof 2>/dev/null || true' > "${OUTPUT_DIR}/open-files-before.txt"
awk '
  NR == FNR {
    if (FNR > 1 && $2 ~ /^[0-9]+$/) {
      if (open_pid[$NF] == "") open_pid[$NF] = $2
      else if (open_pid[$NF] !~ "(^|,)" $2 "(,|$)") open_pid[$NF] = open_pid[$NF] "," $2
    }
    next
  }
  FNR == 1 { print; next }
  { print $0 open_pid[$1] }
' "${OUTPUT_DIR}/open-files-before.txt" "${manifest_file}" > "${manifest_file}.tmp"
mv "${manifest_file}.tmp" "${manifest_file}"

echo "Wrote read-only cleanup manifest to ${manifest_file}"
