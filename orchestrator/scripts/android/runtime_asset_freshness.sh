#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APP_ROOT="${REPO_ROOT}/android-orchestrator"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${WORKSPACE_ROOT}/tools/pixel/transport.sh"

usage() {
  cat <<'USAGE'
Usage: runtime_asset_freshness.sh [options]

Options:
  --device SERIAL      adb serial (optional if only one device connected)
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
  --scope NAME         asset scope to verify (dns|remote|rooted|ssh|vpn|ticket_screen|train_bot|satiksme_bot|site_notifier|subscription_bot|readiness)
  --print-specs        print the local/remote asset mapping for the scope and exit
  --timings-file FILE  append JSONL phase timings to FILE
  -h, --help           show help
USAGE
}

ADB_SERIAL=""
SCOPE="readiness"
PRINT_SPECS=0
TIMINGS_FILE="${PIXEL_PHASE_TIMINGS_FILE:-}"
TIMING_TOTAL_START_MS=""
TIMING_PHASE_START_MS=""

timing_now_ms() {
  python3 -c 'import time; print(time.monotonic_ns() // 1_000_000)'
}

timing_start() {
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  mkdir -p "$(dirname "${TIMINGS_FILE}")"
  TIMING_TOTAL_START_MS="$(timing_now_ms)"
  TIMING_PHASE_START_MS="${TIMING_TOTAL_START_MS}"
}

timing_mark() {
  local phase="$1"
  local now_ms=""
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  now_ms="$(timing_now_ms)"
  printf '{"script":"runtime_asset_freshness","phase":"%s","durationMs":%d}\n' \
    "${phase}" "$((now_ms - TIMING_PHASE_START_MS))" >> "${TIMINGS_FILE}"
  TIMING_PHASE_START_MS="${now_ms}"
}

timing_finish() {
  local now_ms=""
  [[ -n "${TIMINGS_FILE}" ]] || return 0
  now_ms="$(timing_now_ms)"
  printf '{"script":"runtime_asset_freshness","phase":"total","durationMs":%d}\n' \
    "$((now_ms - TIMING_TOTAL_START_MS))" >> "${TIMINGS_FILE}"
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    --scope)
      shift
      SCOPE="${1:-}"
      ;;
    --print-specs)
      PRINT_SPECS=1
      ;;
    --timings-file)
      shift
      TIMINGS_FILE="${1:-}"
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

case "${SCOPE}" in
  dns|remote|rooted|ssh|vpn|ticket_screen|train_bot|satiksme_bot|site_notifier|subscription_bot|readiness) ;;
  *)
    echo "Unsupported --scope: ${SCOPE}" >&2
    exit 2
    ;;
esac

append_template_group_specs() {
  local local_root="$1"
  local remote_root="$2"
  local label_prefix="$3"
  local local_path rel

  while IFS= read -r local_path; do
    [[ -n "${local_path}" ]] || continue
    rel="${local_path#${local_root}/}"
    printf '%s|%s|%s\n' "${label_prefix}:${rel}" "${local_path}" "${remote_root}/${rel}"
  done < <(
    find "${local_root}" -type f \
      ! -path '*/__pycache__/*' \
      ! -name '*.pyc' \
      ! -name '.DS_Store' \
      | sort
  )
}

append_entrypoint_specs() {
  local name
  for name in "$@"; do
    printf 'entrypoint:%s|%s|%s\n' \
      "${name}" \
      "${APP_ROOT}/app/src/main/assets/runtime/entrypoints/${name}" \
      "/data/local/pixel-stack/bin/${name}"
  done
}

emit_specs() {
  case "${SCOPE}" in
    dns|remote|rooted)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/rooted" \
        "/data/local/pixel-stack/templates/rooted" \
        "rooted"
      append_entrypoint_specs "pixel-dns-start.sh" "pixel-dns-stop.sh"
      ;;
    ssh)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/ssh" \
        "/data/local/pixel-stack/templates/ssh" \
        "ssh"
      append_entrypoint_specs "pixel-ssh-start.sh" "pixel-ssh-stop.sh" "pixel-management-health.sh"
      ;;
    vpn)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/vpn" \
        "/data/local/pixel-stack/templates/vpn" \
        "vpn"
      append_entrypoint_specs "pixel-vpn-start.sh" "pixel-vpn-stop.sh" "pixel-vpn-health.sh" "pixel-management-health.sh"
      ;;
    ticket_screen)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/ticket" \
        "/data/local/pixel-stack/templates/ticket" \
        "ticket"
      append_entrypoint_specs "pixel-ticket-start.sh" "pixel-ticket-stop.sh" "pixel-ticket-health.sh" "pixel-ticket-lifecycle-lock.sh"
      printf 'ticket-root-keyboard|%s|%s\n' \
        "${APP_ROOT}/app/build/generated/ticketRootKeyboardAssets/ticket-root-keyboard" \
        "/data/local/pixel-stack/bin/pixel-ticket-root-keyboard"
      ;;
    train_bot)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/train" \
        "/data/local/pixel-stack/templates/train" \
        "train"
      append_entrypoint_specs "pixel-train-start.sh" "pixel-train-stop.sh"
      ;;
    satiksme_bot)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/satiksme" \
        "/data/local/pixel-stack/templates/satiksme" \
        "satiksme"
      append_entrypoint_specs "pixel-satiksme-start.sh" "pixel-satiksme-stop.sh" "pixel-satiksme-health.sh"
      ;;
    site_notifier)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/notifier" \
        "/data/local/pixel-stack/templates/notifier" \
        "notifier"
      append_entrypoint_specs "pixel-notifier-start.sh" "pixel-notifier-stop.sh"
      ;;
    subscription_bot)
      append_template_group_specs \
        "${APP_ROOT}/app/src/main/assets/runtime/templates/subscription" \
        "/data/local/pixel-stack/templates/subscription" \
        "subscription"
      append_entrypoint_specs "pixel-subscription-start.sh" "pixel-subscription-stop.sh" "pixel-subscription-health.sh"
      ;;
    readiness)
      SCOPE="ssh" emit_specs
      SCOPE="vpn" emit_specs
      SCOPE="ticket_screen" emit_specs
      append_entrypoint_specs "pixel-runtime-cleanup.sh"
      SCOPE="readiness"
      ;;
  esac
}

if (( PRINT_SPECS == 1 )); then
  emit_specs
  exit 0
fi

timing_start
pixel_transport_require_device >/dev/null
pixel_transport_require_root
timing_mark "resolve_transport"

labels=()
local_paths=()
remote_paths=()
while IFS='|' read -r label local_path remote_path; do
  [[ -n "${label}" ]] || continue
  labels+=("${label}")
  local_paths+=("${local_path}")
  remote_paths+=("${remote_path}")
done < <(emit_specs)

checked="${#labels[@]}"
mismatches=0
local_hashes=()
remote_hashes=()
local_hash_file="$(mktemp)"
remote_hash_file="$(mktemp)"
trap 'rm -f "${local_hash_file}" "${remote_hash_file}"' EXIT

python3 - "${local_paths[@]}" > "${local_hash_file}" <<'PY'
import hashlib
import sys
from pathlib import Path

for raw_path in sys.argv[1:]:
    path = Path(raw_path)
    if not path.is_file():
        print("MISSING")
        continue
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    print(digest.hexdigest())
PY
while IFS= read -r local_hash; do
  local_hashes+=("${local_hash}")
done < "${local_hash_file}"
timing_mark "local_hashes"

if (( checked > 0 )); then
  if ! pixel_transport_remote_sha256_files "${remote_paths[@]}" > "${remote_hash_file}"; then
    echo "Failed to read remote runtime asset hashes" >&2
    exit 1
  fi
fi
while IFS= read -r remote_hash; do
  remote_hashes+=("${remote_hash}")
done < "${remote_hash_file}"
timing_mark "remote_hashes"

if (( ${#local_hashes[@]} != checked || ${#remote_hashes[@]} != checked )); then
  echo "Runtime asset hash batch returned an incomplete result" >&2
  exit 1
fi

for ((index = 0; index < checked; index++)); do
  label="${labels[index]}"
  local_hash="${local_hashes[index]}"
  remote_hash="${remote_hashes[index]}"
  remote_path="${remote_paths[index]}"

  if [[ "${local_hash}" == "MISSING" ]]; then
    mismatches=$((mismatches + 1))
    printf 'MISMATCH %s local=missing remote=unknown path=%s\n' "${label}" "${remote_path}"
    continue
  fi

  if [[ -z "${remote_hash}" || "${remote_hash}" == "UNKNOWN" || "${remote_hash}" == "MISSING" || "${remote_hash}" != "${local_hash}" ]]; then
    mismatches=$((mismatches + 1))
    printf 'MISMATCH %s local=%s remote=%s path=%s\n' "${label}" "${local_hash}" "${remote_hash:-UNKNOWN}" "${remote_path}"
  fi
done
timing_mark "compare"

if (( mismatches > 0 )); then
  printf 'STALE scope=%s checked=%d mismatches=%d transport=%s\n' "${SCOPE}" "${checked}" "${mismatches}" "$(pixel_transport_selected)"
  timing_finish
  exit 3
fi

printf 'FRESH scope=%s checked=%d transport=%s\n' "${SCOPE}" "${checked}" "$(pixel_transport_selected)"
timing_finish
