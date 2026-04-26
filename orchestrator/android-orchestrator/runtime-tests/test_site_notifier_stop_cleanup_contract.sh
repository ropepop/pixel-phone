#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
STOP_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-notifier-stop.sh"

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/site-notifications/bin/site-notifier-service-loop'" "${STOP_SCRIPT}"; then
  echo "FAIL: notifier stop script no longer kills the service loop" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/site-notifications/bin/site-notifier-launch'" "${STOP_SCRIPT}"; then
  echo "FAIL: notifier stop script no longer kills the launch wrapper" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/site-notifications/bin/site-notifier-python.current'" "${STOP_SCRIPT}"; then
  echo "FAIL: notifier stop script no longer kills the bundled python wrapper" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/site-notifications/bin/site-notifier-python3.current'" "${STOP_SCRIPT}"; then
  echo "FAIL: notifier stop script no longer kills the bundled python binary" >&2
  exit 1
fi

if ! rg -Fq "kill_matching_processes '/data/local/pixel-stack/apps/site-notifications/releases/'" "${STOP_SCRIPT}"; then
  echo "FAIL: notifier stop script no longer sweeps stale release-root notifier processes" >&2
  exit 1
fi

if ! rg -Fq 'rm -f "${BASE}/state/daemon.lock"' "${STOP_SCRIPT}"; then
  echo "FAIL: notifier stop script no longer clears daemon.lock" >&2
  exit 1
fi

if ! rg -Fq 'rm -f "${RUN_DIR}/heartbeat.epoch"' "${STOP_SCRIPT}"; then
  echo "FAIL: notifier stop script no longer clears heartbeat state" >&2
  exit 1
fi

echo "PASS: site-notifier stop cleanup contract is present"
