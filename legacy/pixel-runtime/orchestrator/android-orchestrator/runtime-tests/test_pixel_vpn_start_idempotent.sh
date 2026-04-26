#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VPN_START_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-vpn-start.sh"

if ! rg -Fq 'if [ -f "${PID_FILE}" ]; then' "${VPN_START_SCRIPT}"; then
  echo "FAIL: missing PID guard in ${VPN_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'if [ -n "${old_pid}" ] && kill -0 "${old_pid}" >/dev/null 2>&1; then' "${VPN_START_SCRIPT}"; then
  echo "FAIL: missing running PID check in ${VPN_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'nohup env PIXEL_VPN_ROOT="${BASE}" "${LOOP_BIN}"' "${VPN_START_SCRIPT}"; then
  echo "FAIL: missing explicit PIXEL_VPN_ROOT launch env in ${VPN_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'rm -f "${TAILSCALED_PID_FILE}"' "${VPN_START_SCRIPT}"; then
  echo "FAIL: missing stale tailscaled PID cleanup in ${VPN_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'rm -f "${TAILSCALED_SOCK}"' "${VPN_START_SCRIPT}"; then
  echo "FAIL: missing stale tailscaled socket cleanup in ${VPN_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'rm -rf "${LOCK_DIR}"' "${VPN_START_SCRIPT}"; then
  echo "FAIL: missing stale lock cleanup in ${VPN_START_SCRIPT}" >&2
  exit 1
fi

if ! rg -Fq 'missing vpn config source' "${VPN_START_SCRIPT}"; then
  echo "FAIL: missing config source enforcement in ${VPN_START_SCRIPT}" >&2
  exit 1
fi

echo "PASS: pixel-vpn-start includes PID-based idempotent launch guard and stale-state cleanup"
