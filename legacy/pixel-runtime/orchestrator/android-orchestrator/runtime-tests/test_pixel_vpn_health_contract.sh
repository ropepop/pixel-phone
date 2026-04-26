#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VPN_HEALTH_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-vpn-health.sh"

if ! rg -Fq 'emit "vpn_enabled" "${VPN_ENABLED}"' "${VPN_HEALTH_SCRIPT}"; then
  echo "FAIL: missing vpn_enabled report in ${VPN_HEALTH_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'emit "tailscaled_live" "${tailscaled_live}"' "${VPN_HEALTH_SCRIPT}"; then
  echo "FAIL: missing tailscaled_live report in ${VPN_HEALTH_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'emit "tailnet_ipv4" "${tailnet_ipv4}"' "${VPN_HEALTH_SCRIPT}"; then
  echo "FAIL: missing tailnet IPv4 report in ${VPN_HEALTH_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq '[ "${tailscaled_live}" = "1" ]' "${VPN_HEALTH_SCRIPT}"; then
  echo "FAIL: missing live tailscaled health check in ${VPN_HEALTH_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq '[ "${guard_chain_ipv4}" = "1" ]' "${VPN_HEALTH_SCRIPT}"; then
  echo "FAIL: missing IPv4 SSH guard health check in ${VPN_HEALTH_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq '[ "${guard_chain_ipv6}" = "1" ]' "${VPN_HEALTH_SCRIPT}"; then
  echo "FAIL: missing IPv6 SSH guard health check in ${VPN_HEALTH_SCRIPT}" >&2
  exit 1
fi

echo "PASS: pixel-vpn-health exposes VPN evidence and enforces tailscale + ssh guard health contract"
