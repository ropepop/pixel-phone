#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
START_FILE="${ROOT}/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

if ! rg -Fq 'load_remote_runtime_env()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing load_remote_runtime_env helper" >&2
  exit 1
fi

if ! rg -Fq '. "${PIHOLE_REMOTE_RUNTIME_ENV_FILE}"' "${START_FILE}"; then
  echo "FAIL: adguardhome-start does not source persisted remote runtime env" >&2
  exit 1
fi

if ! rg -Fq 'remote-healthcheck|remote-healthcheck-debug|core-healthcheck|remote-restart|runtime-restart-services|runtime-restart-core|remote-reload-frontend|runtime-status' "${START_FILE}"; then
  echo "FAIL: adguardhome-start does not hydrate remote runtime env for maintenance modes" >&2
  exit 1
fi

echo "PASS: AdGuard remote maintenance modes reload persisted runtime env"
