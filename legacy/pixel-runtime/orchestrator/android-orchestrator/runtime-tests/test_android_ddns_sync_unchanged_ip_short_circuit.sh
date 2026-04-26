#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ANDROID_DDNS_SYNC="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ddns-sync.sh"

cache_line="$(rg -Fn 'record names unchanged; skipping provider sync' "${ANDROID_DDNS_SYNC}" | cut -d: -f1 | head -n1 || true)"
record_names_line="$(rg -Fn 'LAST_RECORD_NAMES_FILE=' "${ANDROID_DDNS_SYNC}" | cut -d: -f1 | head -n1 || true)"
sync_when_names_change_line="$(rg -Fn 'record names changed; syncing provider' "${ANDROID_DDNS_SYNC}" | cut -d: -f1 | head -n1 || true)"
normalized_names_printf_line="$(grep -nF "printf '%s\\n' \"\${raw_names}\"" "${ANDROID_DDNS_SYNC}" | cut -d: -f1 | head -n1 || true)"
zone_line="$(rg -Fn 'zones?name=${DDNS_ZONE_NAME}' "${ANDROID_DDNS_SYNC}" | cut -d: -f1 | head -n1 || true)"

if [[ -z "${cache_line}" || -z "${record_names_line}" || -z "${sync_when_names_change_line}" || -z "${normalized_names_printf_line}" || -z "${zone_line}" ]]; then
  echo "FAIL: missing record-name-aware short-circuit guard or Cloudflare zone lookup in ${ANDROID_DDNS_SYNC}" >&2
  exit 1
fi

if (( cache_line >= zone_line )); then
  echo "FAIL: unchanged-cache short-circuit appears after provider lookup in ${ANDROID_DDNS_SYNC}" >&2
  exit 1
fi

date_line="$(rg -Fn 'date +%s >"${RUN_DIR}/ddns-last-sync-epoch"' "${ANDROID_DDNS_SYNC}" | cut -d: -f1 | awk -v min="${cache_line}" '$1 > min { print; exit }')"
exit_line="$(rg -Fn 'exit 0' "${ANDROID_DDNS_SYNC}" | cut -d: -f1 | awk -v min="${cache_line}" '$1 > min { print; exit }')"

if [[ -z "${date_line}" || -z "${exit_line}" || "${date_line}" -ge "${exit_line}" ]]; then
  echo "FAIL: unchanged-cache path does not update ddns-last-sync-epoch before exit in ${ANDROID_DDNS_SYNC}" >&2
  exit 1
fi

if (( record_names_line >= cache_line || cache_line >= sync_when_names_change_line )); then
  echo "FAIL: record-name state tracking is not wired into unchanged-cache sync logic in ${ANDROID_DDNS_SYNC}" >&2
  exit 1
fi

if (( normalized_names_printf_line >= zone_line )); then
  echo "FAIL: record-name normalization does not clearly terminate the raw record list before provider sync logic in ${ANDROID_DDNS_SYNC}" >&2
  exit 1
fi

echo "PASS: android ddns sync only short-circuits unchanged IPv4 when record names are unchanged"
