#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
STOP_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-satiksme-stop.sh"

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot-service-loop'" "${STOP_SCRIPT}"; then
  echo "FAIL: satiksme stop script no longer kills the service loop" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-web-tunnel-service-loop'" "${STOP_SCRIPT}"; then
  echo "FAIL: satiksme stop script no longer kills the tunnel service loop" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot-launch'" "${STOP_SCRIPT}"; then
  echo "FAIL: satiksme stop script no longer kills the launch wrapper" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/bin/cloudflared'" "${STOP_SCRIPT}"; then
  echo "FAIL: satiksme stop script no longer kills cloudflared" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/satiksme-bot/releases/'" "${STOP_SCRIPT}"; then
  echo "FAIL: satiksme stop script no longer kills release-path bot binaries" >&2
  exit 1
fi

if ! rg -Fq 'rm -f "${HEARTBEAT_FILE}"' "${STOP_SCRIPT}"; then
  echo "FAIL: satiksme stop script no longer clears heartbeat state" >&2
  exit 1
fi

echo "PASS: satiksme stop cleanup contract is present"
