#!/usr/bin/env bash
set -euo pipefail

ADB_SERIAL="${ADB_SERIAL:-100.76.50.43:5555}"
MANIFEST=""
CATEGORY=""
EVIDENCE_DIR=""

usage() {
  echo "Usage: $(basename "$0") --manifest FILE --category NAME --evidence-dir DIR [--device ADB_SERIAL]" >&2
}

while (( $# > 0 )); do
  case "$1" in
    --device) shift; ADB_SERIAL="${1:-}" ;;
    --manifest) shift; MANIFEST="${1:-}" ;;
    --category) shift; CATEGORY="${1:-}" ;;
    --evidence-dir) shift; EVIDENCE_DIR="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
  shift
done

[[ -f "${MANIFEST}" && -n "${CATEGORY}" && -n "${EVIDENCE_DIR}" ]] || { usage; exit 2; }
case "${CATEGORY}" in
  transient_ticket_hierarchy|deployment_action_result|retired_dns_history|unreferenced_deployment_artifact|app_cache_staging) ;;
  *) echo "Unsupported cleanup category: ${CATEGORY}" >&2; exit 2 ;;
esac

mkdir -p "${EVIDENCE_DIR}"
adb_cmd=(adb -s "${ADB_SERIAL}")
"${adb_cmd[@]}" get-state >/dev/null

entries="$(awk -F '\t' -v category="${CATEGORY}" '
  NR > 1 && $7 == category { print $1 "\t" $2 "\t" $3 }
' "${MANIFEST}")"
if [[ -z "${entries}" ]]; then
  echo "No ${CATEGORY} entries in ${MANIFEST}"
  exit 0
fi

entries_b64="$(printf '%s\n' "${entries}" | base64 | tr -d '\n')"
remote_script=""
IFS= read -r -d '' remote_script <<'REMOTE_SCRIPT' || true
set -eu
category='__CATEGORY__'
entries_b64='__ENTRIES_B64__'
tab=$(printf '\t')

allowed_path() {
  candidate=$1
  case "$category:$candidate" in
    transient_ticket_hierarchy:/sdcard/pixel-ticket-window.xml|transient_ticket_hierarchy:/data/local/tmp/pixel-vivi-fast-return-window.xml|transient_ticket_hierarchy:/data/local/tmp/rs-direct-window.xml) return 0 ;;
    deployment_action_result:/data/local/pixel-stack/run/orchestrator-action-results/*.json) return 0 ;;
    retired_dns_history:/data/local/pixel-stack/chroots/adguardhome/*|retired_dns_history:/data/local/pixel-stack/state/adguardhome/*|retired_dns_history:/data/local/pixel-stack/chroots/pihole/*) return 0 ;;
    unreferenced_deployment_artifact:/data/local/pixel-stack/conf/runtime/artifacts/*|unreferenced_deployment_artifact:/data/local/pixel-stack/conf/runtime/components/*/artifacts/*) return 0 ;;
    app_cache_staging:/data/user/0/lv.jolkins.pixelorchestrator/cache/runtime-artifacts/*) return 0 ;;
    *) return 1 ;;
  esac
}

if [ "$category" = retired_dns_history ]; then
  if pidof AdGuardHome pihole-FTL dnsmasq >/dev/null 2>&1 ||
    pgrep -f '[A]dGuardHome|[p]ihole-FTL|[p]ihole-rooted' >/dev/null 2>&1; then
    echo 'BLOCKED retired DNS process is active' >&2
    exit 3
  fi
fi

echo "$entries_b64" | base64 -d | while IFS="$tab" read -r path expected_bytes expected_hash; do
  [ -n "$path" ] || continue
  allowed_path "$path" || { echo "BLOCKED path outside category allowlist: $path" >&2; exit 4; }
  [ -e "$path" ] || continue
  [ -f "$path" ] && [ ! -L "$path" ] || { echo "BLOCKED non-regular path: $path" >&2; exit 5; }
  actual_bytes=$(stat -c '%s' "$path")
  [ "$actual_bytes" = "$expected_bytes" ] || { echo "BLOCKED size changed: $path" >&2; exit 6; }
  actual_hash=$(sha256sum "$path" | awk '{print $1}')
  [ "$actual_hash" = "$expected_hash" ] || { echo "BLOCKED hash changed: $path" >&2; exit 7; }
  if lsof "$path" 2>/dev/null | sed '1d' | grep -q .; then
    echo "BLOCKED file is open: $path" >&2
    exit 8
  fi
  if find /data/local/pixel-stack/conf/runtime -maxdepth 5 -type f -name '*.json' -exec grep -lF -- "$path" {} \; 2>/dev/null | grep -q .; then
    echo "BLOCKED manifest reference found: $path" >&2
    exit 9
  fi
  printf 'VERIFIED\t%s\t%s\n' "$actual_bytes" "$path"
done

echo "$entries_b64" | base64 -d | while IFS="$tab" read -r path expected_bytes expected_hash; do
  [ -n "$path" ] || continue
  [ -e "$path" ] || { printf 'ALREADY_GONE\t0\t%s\n' "$path"; continue; }
  rm -f -- "$path"
  [ ! -e "$path" ] || { echo "BLOCKED deletion failed: $path" >&2; exit 10; }
  printf 'DELETED\t%s\t%s\n' "$expected_bytes" "$path"
done
REMOTE_SCRIPT
remote_script="${remote_script/__CATEGORY__/${CATEGORY}}"
remote_script="${remote_script/__ENTRIES_B64__/${entries_b64}}"

evidence_file="${EVIDENCE_DIR}/${CATEGORY}.txt"
printf '%s\n' "${remote_script}" | "${adb_cmd[@]}" shell "su -c sh" | tr -d '\r' | tee "${evidence_file}"

deleted_count="$(awk -F '\t' '$1 == "DELETED" { count += 1 } END { print count + 0 }' "${evidence_file}")"
deleted_bytes="$(awk -F '\t' '$1 == "DELETED" { bytes += $2 } END { printf "%.0f\n", bytes + 0 }' "${evidence_file}")"
echo "Deleted ${deleted_count} ${CATEGORY} files (${deleted_bytes} bytes)"
