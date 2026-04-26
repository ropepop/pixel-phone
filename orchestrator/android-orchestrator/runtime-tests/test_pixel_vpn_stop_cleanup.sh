#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VPN_STOP_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-vpn-stop.sh"

if ! rg -Fq 'pixel-vpn-service-loop' "${VPN_STOP_SCRIPT}"; then
  echo "FAIL: missing service loop cleanup pattern in ${VPN_STOP_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'tailscaled.pid' "${VPN_STOP_SCRIPT}"; then
  echo "FAIL: missing tailscaled pid cleanup in ${VPN_STOP_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq "pkill -f '/data/local/pixel-stack/vpn/bin/tailscaled'" "${VPN_STOP_SCRIPT}"; then
  echo "FAIL: missing tailscaled process kill fallback in ${VPN_STOP_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'rm -rf "${LOCK_DIR}"' "${VPN_STOP_SCRIPT}"; then
  echo "FAIL: missing lock cleanup in ${VPN_STOP_SCRIPT}" >&2
  exit 1
fi

echo "PASS: pixel-vpn-stop cleans vpn loop and tailscaled state"
