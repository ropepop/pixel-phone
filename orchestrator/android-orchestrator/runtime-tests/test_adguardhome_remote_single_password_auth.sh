#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
STOP_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-stop"
ENTRY_START="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-dns-start.sh"
ENTRY_STOP="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-dns-stop.sh"
SERVICE_REPORT="${REPO_ROOT}/scripts/ops/service-availability-report.sh"

if ! rg -Fq 'ADGUARDHOME_ADMIN_USERNAME=' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing ADGUARDHOME_ADMIN_USERNAME runtime wiring" >&2
  exit 1
fi
if ! rg -Fq 'ADGUARDHOME_ADMIN_PASSWORD_FILE=' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing ADGUARDHOME_ADMIN_PASSWORD_FILE runtime wiring" >&2
  exit 1
fi
if rg -Fq 'PIHOLE_ADMIN_BASIC_AUTH_HASH' "${START_FILE}"; then
  echo "FAIL: adguardhome-start still references legacy PIHOLE_ADMIN_BASIC_AUTH_HASH" >&2
  exit 1
fi
if ! rg -Fq "pkill -f 'adguardhome-doh-gateway'" "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing legacy DoH gateway cleanup for hard cutover" >&2
  exit 1
fi
if ! rg -Fq "stop_remote_nginx()" "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing nginx lifecycle cleanup helper" >&2
  exit 1
fi

if rg -Fq 'adguardhome-doh-gateway.py' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start still stages removed DoH gateway" >&2
  exit 1
fi
if ! rg -Fq 'adguardhome-remote-nginx.conf.template' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start must stage tokenized remote nginx template" >&2
  exit 1
fi
if ! rg -Fq 'adguardhome-migrate.py' "${ENTRY_START}"; then
  echo "FAIL: pixel-dns-start does not stage migration helper" >&2
  exit 1
fi
if rg -Fq 'adguardhome-doh-gateway' "${ENTRY_STOP}"; then
  echo "FAIL: pixel-dns-stop still cleans up removed DoH gateway" >&2
  exit 1
fi
if ! rg -Fq 'pixel-stack-adguardhome-remote-nginx.conf' "${ENTRY_STOP}"; then
  echo "FAIL: pixel-dns-stop missing nginx cleanup guard" >&2
  exit 1
fi
if ! rg -Fq "pkill -f 'adguardhome-doh-gateway'" "${STOP_FILE}"; then
  echo "FAIL: adguardhome-stop missing legacy DoH gateway cleanup for cutover safety" >&2
  exit 1
fi
if ! rg -Fq "pkill -f 'pixel-stack-adguardhome-remote-nginx.conf'" "${STOP_FILE}"; then
  echo "FAIL: adguardhome-stop missing nginx cleanup for cutover safety" >&2
  exit 1
fi

if ! rg -Fq 'contract_root_ui_reachable' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing direct root UI contract" >&2
  exit 1
fi
if ! rg -Fq -- '--doh-endpoint-mode' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing endpoint mode option" >&2
  exit 1
fi
if ! rg -Fq 'contract_doh_mode' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing mode-aware DoH contract output" >&2
  exit 1
fi

echo "PASS: tokenized remote auth/runtime wiring preserves AdGuard credentials and mode-aware service contracts"
