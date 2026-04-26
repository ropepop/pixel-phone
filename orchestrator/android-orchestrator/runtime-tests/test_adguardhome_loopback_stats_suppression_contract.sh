#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

render_block="$(sed -n '/^render_adguardhome_config()/,/^}/p' "${START_FILE}")"
validate_block="$(sed -n '/^validate_rendered_config()/,/^}/p' "${START_FILE}")"

if [[ -z "${render_block}" ]]; then
  echo "FAIL: missing render_adguardhome_config() helper in ${START_FILE}" >&2
  exit 1
fi

if [[ -z "${validate_block}" ]]; then
  echo "FAIL: missing validate_rendered_config() helper in ${START_FILE}" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq 'name: "Loopback internal"'; then
  echo "FAIL: rendered AdGuard config missing loopback client suppression entry" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq '        - 127.0.0.1'; then
  echo "FAIL: rendered AdGuard config missing IPv4 loopback client id" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq '        - ::1'; then
  echo "FAIL: rendered AdGuard config missing IPv6 loopback client id" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq '      ignore_querylog: true'; then
  echo "FAIL: rendered AdGuard config missing ignore_querylog for loopback client" >&2
  exit 1
fi

if ! printf '%s\n' "${render_block}" | rg -Fq '      ignore_statistics: true'; then
  echo "FAIL: rendered AdGuard config missing ignore_statistics for loopback client" >&2
  exit 1
fi

if ! printf '%s\n' "${validate_block}" | rg -Fq 'Rendered AdGuardHome config missing IPv4 loopback suppression entry'; then
  echo "FAIL: validation helper missing IPv4 loopback suppression guard" >&2
  exit 1
fi

if ! printf '%s\n' "${validate_block}" | rg -Fq 'Rendered AdGuardHome config missing IPv6 loopback suppression entry'; then
  echo "FAIL: validation helper missing IPv6 loopback suppression guard" >&2
  exit 1
fi

if ! printf '%s\n' "${validate_block}" | rg -Fq 'Rendered AdGuardHome config missing loopback statistics suppression flag'; then
  echo "FAIL: validation helper missing statistics suppression guard" >&2
  exit 1
fi

echo "PASS: AdGuard runtime config suppresses loopback querylog and statistics entries"
