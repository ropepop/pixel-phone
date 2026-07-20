#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./transport.sh
source "${SCRIPT_DIR}/transport.sh"

usage() {
  echo "Usage: ticket_first_setup.sh [--transport MODE] [--device SERIAL] [--ssh-host HOST] [--ssh-port PORT]"
}

while (( $# )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
  elif [[ "$1" == "-h" || "$1" == "--help" ]]; then
    usage
    exit 0
  else
    echo "Unknown argument: $1" >&2
    usage >&2
    exit 2
  fi
done

pixel_transport_require_device
pixel_transport_require_root

vivi="com.pv.vivi"
stores=(app.accrescent.client app.grapheneos.apps com.aurora.store org.fdroid.fdroid dev.imranr.obtainium)
store=""
for package in "${stores[@]}"; do
  if pixel_transport_package_installed "${package}"; then
    store="${package}"
    break
  fi
done
if ! pixel_transport_package_installed "${vivi}"; then
  if [[ -z "${store}" ]]; then
    echo "blocked=no_local_app_store"
    exit 20
  fi
  [[ "${store}" == "app.accrescent.client" ]] && step="install_vivi_from_accrescent" || step="install_vivi_from_local_store"
  echo "next_step=${step}"
  pixel_transport_root_exec monkey -p "${store}" 1 >/dev/null 2>&1 || true
  exit 21
fi

runtime=/data/local/pixel-stack/bin
if ! pixel_transport_root_exec test -x "${runtime}/pixel-ticket-start.sh" >/dev/null 2>&1; then
  echo "blocked=ticket_runtime_not_deployed"
  exit 22
fi
pixel_transport_root_exec sh "${runtime}/pixel-ticket-start.sh" >/dev/null
sleep 2
service=started_but_health_pending
pixel_transport_root_exec sh "${runtime}/pixel-ticket-health.sh" >/dev/null 2>&1 && service=running
echo "ticket_service=${service}"
echo "next_step=open_ticket_site"
