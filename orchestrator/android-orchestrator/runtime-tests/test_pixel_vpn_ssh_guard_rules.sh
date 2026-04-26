#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
VPN_LAUNCH_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/vpn/pixel-vpn-launch.sh"

if ! rg -Fq 'PIXEL_SSH_GUARD' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing IPv4 guard chain name in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'PIXEL_SSH_GUARD6' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing IPv6 guard chain name in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq '"${ipt}" -A "${chain}" -i "${VPN_INTERFACE_NAME}" -p tcp --dport "${SSH_PORT}" -j ACCEPT' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing VPN interface allow rule in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq '"${ipt}" -A "${chain}" -p tcp --dport "${SSH_PORT}" -j DROP' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing non-VPN drop rule in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'ensure_global_setting adb_enabled 1' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing adb enablement in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'ensure_global_setting adb_wifi_enabled 1' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing wireless debug enablement in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'ensure_system_property persist.adb.tls_server.enable 1' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing persistent TLS wireless debug enablement in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'read_system_property service.adb.tls.port' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing live TLS wireless debug port discovery in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'WIRELESS_DEBUG_TLS_PORT_FILE="${RUN_DIR}/wireless-debug-tls-port"' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing wireless debug TLS port runtime file in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'discover_wireless_debug_tls_port' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: missing wireless debug TLS port discovery helper in ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if rg -Fq 'service.adb.tcp.port' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: legacy fixed adb TCP port contract leaked into ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

if rg -Fq 'persist.adb.tcp.port' "${VPN_LAUNCH_TEMPLATE}"; then
  echo "FAIL: persisted adb TCP port contract leaked into ${VPN_LAUNCH_TEMPLATE}" >&2
  exit 1
fi

echo "PASS: pixel-vpn-launch enforces VPN-only SSH guard chains and wireless debug recovery hooks"
