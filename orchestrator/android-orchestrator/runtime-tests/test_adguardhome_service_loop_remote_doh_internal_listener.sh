#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOOP_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-service-loop"

if rg -Fq 'PIHOLE_REMOTE_DOH_INTERNAL_PORT' "${LOOP_TEMPLATE}"; then
  echo "FAIL: service-loop still carries removed PIHOLE_REMOTE_DOH_INTERNAL_PORT path" >&2
  exit 1
fi

if rg -Fq 'remote_doh_internal_port' "${LOOP_TEMPLATE}"; then
  echo "FAIL: service-loop still defines removed remote_doh_internal_port check" >&2
  exit 1
fi

if rg -Fq 'port_tcp_listening "${remote_doh_internal_port}" || return 1' "${LOOP_TEMPLATE}"; then
  echo "FAIL: service-loop still enforces removed internal DoH gateway listener" >&2
  exit 1
fi

echo "PASS: service-loop removed internal DoH gateway listener checks (no :8053 dependency)"
