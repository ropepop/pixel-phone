#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./transport.sh
source "${SCRIPT_DIR}/transport.sh"

usage() {
  cat <<'USAGE'
Usage: ticket_first_setup.sh [options]

Checks the Pixel for a local app store, confirms whether ViVi is already
installed, starts the ticket remote service when available, and prints the
next concrete setup step.

Options:
  --transport MODE   transport to use (adb|ssh|auto)
  --device SERIAL    adb serial to target
  --ssh-host IP      Tailscale or SSH host/IP
  --ssh-port PORT    SSH port (default: 2222)
  -h, --help         show help
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
done

pixel_transport_require_device
pixel_transport_require_root

VIVI_PACKAGE="com.pv.vivi"
ACCRESCENT_PACKAGE="app.accrescent.client"
STORE_PACKAGES=(
  "${ACCRESCENT_PACKAGE}"
  "app.grapheneos.apps"
  "com.aurora.store"
  "org.fdroid.fdroid"
  "dev.imranr.obtainium"
)

installed_stores=()
for package_name in "${STORE_PACKAGES[@]}"; do
  if pixel_transport_package_installed "${package_name}"; then
    installed_stores+=("${package_name}")
  fi
done

printf 'transport=%s\n' "$(pixel_transport_selected)"
printf 'vivi_installed=%s\n' "$(pixel_transport_package_installed "${VIVI_PACKAGE}" && printf true || printf false)"
printf 'local_store_packages=%s\n' "$(IFS=,; printf '%s' "${installed_stores[*]:-}")"

if ! pixel_transport_package_installed "${VIVI_PACKAGE}"; then
  if ((${#installed_stores[@]} == 0)); then
    echo "blocked=no_local_app_store"
    echo "No local Pixel app store package was found. I did not install ViVi from an APK mirror."
    exit 20
  fi

  if pixel_transport_package_installed "${ACCRESCENT_PACKAGE}"; then
    echo "next_step=install_vivi_from_accrescent"
    pixel_transport_root_exec monkey -p "${ACCRESCENT_PACKAGE}" 1 >/dev/null 2>&1 || true
    echo "Accrescent was opened on the Pixel. Search for ViVi / Vivi Latvija there and install it if listed."
    exit 21
  fi

  echo "next_step=install_vivi_from_local_store"
  first_store="${installed_stores[0]}"
  pixel_transport_root_exec monkey -p "${first_store}" 1 >/dev/null 2>&1 || true
  echo "A local store was opened on the Pixel (${first_store}). Search for ViVi / Vivi Latvija and install it if listed."
  exit 21
fi

if ! pixel_transport_root_exec test -x /data/local/pixel-stack/bin/pixel-ticket-start.sh >/dev/null 2>&1; then
  echo "blocked=ticket_runtime_not_deployed"
  echo "Deploy the updated orchestrator APK/runtime before starting ticket remote access."
  exit 22
fi

pixel_transport_root_exec sh /data/local/pixel-stack/bin/pixel-ticket-start.sh >/dev/null
sleep 2

if pixel_transport_root_exec sh /data/local/pixel-stack/bin/pixel-ticket-health.sh >/dev/null 2>&1; then
  echo "ticket_service=running"
else
  echo "ticket_service=started_but_health_pending"
fi

echo "next_step=open_ticket_site"
echo "Open https://ticket.jolkins.id.lv after the Cloudflare tunnel and Access policy are provisioned."
