#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOOP_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-service-loop"

if ! rg -Fq 'port_tcp_listening "${active_dns_port}" || return 1' "${LOOP_TEMPLATE}"; then
  echo "FAIL: service-loop does not enforce DNS listener health" >&2
  exit 1
fi

if ! rg -Fq 'port_tcp_listening "${web_port}" || return 1' "${LOOP_TEMPLATE}"; then
  echo "FAIL: service-loop does not enforce AdGuard web listener health" >&2
  exit 1
fi

if rg -Fq 'PIHOLE_SERVICE_ENFORCE_DOH_BACKEND' "${LOOP_TEMPLATE}"; then
  echo "FAIL: service-loop still contains legacy DoH backend supervision toggle" >&2
  exit 1
fi

if rg -Fq 'dnscrypt-proxy|dnscrypt_proxy|dnscrypt' "${LOOP_TEMPLATE}"; then
  echo "FAIL: service-loop still contains legacy dnscrypt backend checks" >&2
  exit 1
fi

if ! rg -Fq 'ss -ltn 2>/dev/null | awk -v port="${port}"' "${LOOP_TEMPLATE}"; then
  echo "FAIL: tcp listener check is not using awk-based ss parsing in ${LOOP_TEMPLATE}" >&2
  exit 1
fi

echo "PASS: service-loop enforces native AdGuard listeners without dnscrypt sidecar supervision"
