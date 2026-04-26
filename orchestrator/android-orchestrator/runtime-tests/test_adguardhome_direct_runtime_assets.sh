#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

GATEWAY_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-gateway.py"
NGINX_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-remote-nginx.conf.template"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
ENTRY_START="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-dns-start.sh"

if [[ -e "${GATEWAY_TEMPLATE}" ]]; then
  echo "FAIL: removed DoH gateway template still exists: ${GATEWAY_TEMPLATE}" >&2
  exit 1
fi

if [[ ! -e "${NGINX_TEMPLATE}" ]]; then
  echo "FAIL: tokenized front-door nginx template missing: ${NGINX_TEMPLATE}" >&2
  exit 1
fi

if ! rg -Fq 'render_remote_nginx_config()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing nginx render helper" >&2
  exit 1
fi

if ! rg -Fq 'start_remote_nginx()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing nginx start helper" >&2
  exit 1
fi

if ! rg -Fq 'trusted_proxies:' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing trusted_proxies config for forwarded client IP" >&2
  exit 1
fi

if ! rg -Fq 'allow_unencrypted_doh: true' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing local unencrypted DoH setting for tokenized proxy mode" >&2
  exit 1
fi
if ! rg -Fq 'map $remote_addr $doh_client_ip' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing DoH client IP map for router public IP attribution fallback" >&2
  exit 1
fi
if ! rg -Fq 'access_log /var/log/adguardhome/remote-nginx-dot-access.log pixel_dot;' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing DoT stream access log for client IP recovery" >&2
  exit 1
fi
if ! rg -Fq 'PIHOLE_DDNS_LAST_IPV4_FILE="${PIHOLE_DDNS_LAST_IPV4_FILE:-/etc/pixel-stack/remote-dns/state/ddns-last-ipv4}"' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing chroot-accessible DDNS IPv4 source path" >&2
  exit 1
fi
if ! rg -Fq 'PIHOLE_DDNS_LAST_IPV4_FALLBACK_FILE="/data/local/pixel-stack/run/ddns-last-ipv4"' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing host-path DDNS IPv4 fallback source" >&2
  exit 1
fi
if ! rg -Fq 'sync_ddns_last_ipv4_file()' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start missing DDNS IPv4 sync helper for chroot runtime state" >&2
  exit 1
fi
if ! rg -Fq 'sync_ddns_last_ipv4_file' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start missing DDNS IPv4 sync invocation" >&2
  exit 1
fi
if ! rg -Fq 'ddns-last-ipv4' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start missing host DDNS IPv4 source file reference" >&2
  exit 1
fi
if ! rg -Fq '__DOH_CLIENT_IP_MAP_BLOCK__' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing DoH client IP map placeholder" >&2
  exit 1
fi

echo "PASS: tokenized mode runtime assets keep nginx front door, remove gateway, and preserve proxy IP settings"
