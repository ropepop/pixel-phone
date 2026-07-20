#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
HELPER_SCRIPT="${REPO_ROOT}/orchestrator/scripts/android/runtime_asset_freshness.sh"

if [[ ! -f "${HELPER_SCRIPT}" ]]; then
  echo "FAIL: missing runtime asset freshness helper ${HELPER_SCRIPT}" >&2
  exit 1
fi

specs="$("${HELPER_SCRIPT}" --scope readiness --print-specs)"

if [[ -z "${specs}" ]]; then
  echo "FAIL: runtime asset freshness helper returned no readiness specs" >&2
  exit 1
fi

if ! printf '%s\n' "${specs}" | rg -Fq '/data/local/pixel-stack/templates/ssh/pixel-ssh-service-loop.sh'; then
  echo "FAIL: readiness scope is missing SSH runtime asset checks" >&2
  exit 1
fi

if ! printf '%s\n' "${specs}" | rg -Fq '/data/local/pixel-stack/templates/vpn/pixel-vpn-service-loop.sh'; then
  echo "FAIL: readiness scope is missing VPN runtime asset checks" >&2
  exit 1
fi

if printf '%s\n' "${specs}" | rg -Fq 'ticket-web-tunnel-service-loop'; then
  echo "FAIL: retired Pixel-owned Ticket tunnel is still in runtime asset checks" >&2
  exit 1
fi

if ! printf '%s\n' "${specs}" | rg -Fq '/data/local/pixel-stack/bin/pixel-ticket-root-keyboard'; then
  echo "FAIL: readiness scope is missing the Ticket native root keyboard" >&2
  exit 1
fi

if ! printf '%s\n' "${specs}" | rg -Fq '/data/local/pixel-stack/bin/pixel-runtime-cleanup.sh'; then
  echo "FAIL: readiness scope is missing runtime cleanup" >&2
  exit 1
fi

ssh_specs="$("${HELPER_SCRIPT}" --scope ssh --print-specs)"
if ! printf '%s\n' "${ssh_specs}" | rg -Fq '/data/local/pixel-stack/templates/ssh/pixel-ssh-service-loop.sh'; then
  echo "FAIL: ssh scope is missing ssh template checks" >&2
  exit 1
fi
if ! printf '%s\n' "${ssh_specs}" | rg -Fq '/data/local/pixel-stack/bin/pixel-management-health.sh'; then
  echo "FAIL: ssh scope is missing management health entrypoint checks" >&2
  exit 1
fi

ticket_specs="$("${HELPER_SCRIPT}" --scope ticket_screen --print-specs)"
if ! printf '%s\n' "${ticket_specs}" | rg -Fq '/data/local/pixel-stack/bin/pixel-ticket-root-keyboard'; then
  echo "FAIL: ticket_screen scope is missing the native root keyboard helper" >&2
  exit 1
fi
if ! printf '%s\n' "${ticket_specs}" | rg -Fq '/data/local/pixel-stack/bin/pixel-ticket-health.sh'; then
  echo "FAIL: ticket_screen scope is missing ticket health entrypoint checks" >&2
  exit 1
fi
if ! printf '%s\n' "${ticket_specs}" | rg -Fq '/data/local/pixel-stack/bin/pixel-ticket-lifecycle-lock.sh'; then
  echo "FAIL: ticket_screen scope is missing lifecycle lock helper checks" >&2
  exit 1
fi

if printf '%s\n' "${specs}" | rg -Fq '__pycache__'; then
  echo "FAIL: runtime asset freshness helper should ignore __pycache__ artifacts" >&2
  exit 1
fi

if printf '%s\n' "${specs}" | rg -Fq '.pyc'; then
  echo "FAIL: runtime asset freshness helper should ignore Python bytecode artifacts" >&2
  exit 1
fi

echo "PASS: runtime asset freshness helper covers active SSH, VPN, Ticket, and cleanup assets"
