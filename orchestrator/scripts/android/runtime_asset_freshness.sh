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
  --scope NAME         asset scope to verify (dns|remote|rooted|ssh|vpn|train_bot|satiksme_bot|site_notifier|subscription_bot|readiness)
  --print-specs        print the local/remote asset mapping for the scope and exit
  -h, --help           show help
USAGE
}

ADB_SERIAL=""
SCOPE="readiness"
PRINT_SPECS=0

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
  dns|remote|rooted|ssh|vpn|train_bot|satiksme_bot|site_notifier|subscription_bot|readiness) ;;
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
      SCOPE="dns" emit_specs
      SCOPE="ssh" emit_specs
      SCOPE="vpn" emit_specs
      SCOPE="train_bot" emit_specs
      SCOPE="satiksme_bot" emit_specs
      SCOPE="site_notifier" emit_specs
      SCOPE="subscription_bot" emit_specs
      SCOPE="readiness"
      ;;
  esac
}

if (( PRINT_SPECS == 1 )); then
  emit_specs
  exit 0
fi

pixel_transport_require_device >/dev/null
root_uid="$(pixel_transport_root_shell "id -u" </dev/null 2>/dev/null | tr -d '\r' | tr -d '[:space:]')"
if [[ "${root_uid}" != "0" ]]; then
  echo "Root shell not available on target" >&2
  exit 1
fi

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${path}" | awk '{print $1}'
  else
    shasum -a 256 "${path}" | awk '{print $1}'
  fi
}

remote_sha256_file() {
  local remote_path="$1"
  pixel_transport_remote_sha256_file "${remote_path}"
}

checked=0
mismatches=0

while IFS='|' read -r label local_path remote_path; do
  [[ -n "${label}" ]] || continue
  checked=$((checked + 1))

  if [[ ! -f "${local_path}" ]]; then
    mismatches=$((mismatches + 1))
    printf 'MISMATCH %s local=missing remote=unknown path=%s\n' "${label}" "${remote_path}"
    continue
  fi

  local_hash="$(sha256_file "${local_path}")"
  remote_hash="$(remote_sha256_file "${remote_path}")"
  if [[ -z "${remote_hash}" || "${remote_hash}" == "UNKNOWN" || "${remote_hash}" == "MISSING" || "${remote_hash}" != "${local_hash}" ]]; then
    mismatches=$((mismatches + 1))
    printf 'MISMATCH %s local=%s remote=%s path=%s\n' "${label}" "${local_hash}" "${remote_hash:-UNKNOWN}" "${remote_path}"
  fi
done < <(emit_specs)

if (( mismatches > 0 )); then
  printf 'STALE scope=%s checked=%d mismatches=%d transport=%s\n' "${SCOPE}" "${checked}" "${mismatches}" "$(pixel_transport_selected)"
  exit 3
fi

printf 'FRESH scope=%s checked=%d transport=%s\n' "${SCOPE}" "${checked}" "$(pixel_transport_selected)"
