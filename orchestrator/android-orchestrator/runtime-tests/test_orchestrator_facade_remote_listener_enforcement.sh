#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"

if rg -Fq 'PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS=0' "${FACADE_FILE}"; then
  echo "FAIL: hardcoded PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS=0 still present" >&2
  exit 1
fi

if ! rg -Fq 'PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS=${if (config.supervision.enforceRemoteListeners) 1 else 0}' "${FACADE_FILE}"; then
  echo "FAIL: supervision.enforceRemoteListeners is not wired into adguardhome env output" >&2
  exit 1
fi

if rg -Fq 'PIHOLE_REMOTE_NGINX_WORKERS=${config.remote.nginxWorkerProcesses}' "${FACADE_FILE}"; then
  echo "FAIL: removed PIHOLE_REMOTE_NGINX_WORKERS env output still present" >&2
  exit 1
fi

if rg -Fq 'PIHOLE_REMOTE_DOH_INTERNAL_PORT=${config.remote.dohInternalPort}' "${FACADE_FILE}"; then
  echo "FAIL: removed PIHOLE_REMOTE_DOH_INTERNAL_PORT env output still present" >&2
  exit 1
fi

if ! rg -Fq 'ADGUARDHOME_REMOTE_DOH_ENDPOINT_MODE=${dohEndpointMode}' "${FACADE_FILE}"; then
  echo "FAIL: missing ADGUARDHOME_REMOTE_DOH_ENDPOINT_MODE env output" >&2
  exit 1
fi

if ! rg -Fq 'ADGUARDHOME_REMOTE_DOH_PATH_TOKEN=${config.remote.dohPathToken}' "${FACADE_FILE}"; then
  echo "FAIL: missing ADGUARDHOME_REMOTE_DOH_PATH_TOKEN env output for tokenized/dual modes" >&2
  exit 1
fi
if ! rg -Fq 'ADGUARDHOME_REMOTE_ROUTER_PUBLIC_IP_ATTRIBUTION_ENABLED=${if (config.remote.routerPublicIpAttributionEnabled) 1 else 0}' "${FACADE_FILE}"; then
  echo "FAIL: missing router public IP attribution enabled env output" >&2
  exit 1
fi
if ! rg -Fq 'ADGUARDHOME_REMOTE_ROUTER_LAN_IP=${config.remote.routerLanIp.trim()}' "${FACADE_FILE}"; then
  echo "FAIL: missing router LAN IP env output" >&2
  exit 1
fi

if rg -Fq 'PIHOLE_REMOTE_DOH_RATE_LIMIT_RPS=${config.remote.dohRateLimitRps}' "${FACADE_FILE}"; then
  echo "FAIL: removed PIHOLE_REMOTE_DOH_RATE_LIMIT_RPS env output still present" >&2
  exit 1
fi

if ! rg -Fq 'deprecated remote fields are no-op and ignored' "${FACADE_FILE}"; then
  echo "FAIL: deprecation warning for ignored remote fields missing/renamed" >&2
  exit 1
fi
if rg -Fq 'remote.nginxWorkerProcesses' "${FACADE_FILE}"; then
  echo "FAIL: removed remote.nginxWorkerProcesses deprecation warning path still present" >&2
  exit 1
fi

if ! rg -Fq 'PIHOLE_REMOTE_WATCHDOG_ESCALATE_RUNTIME=${if (config.remote.watchdogEscalateRuntimeRestart) 1 else 0}' "${FACADE_FILE}"; then
  echo "FAIL: PIHOLE_REMOTE_WATCHDOG_ESCALATE_RUNTIME env output missing" >&2
  exit 1
fi

echo "PASS: OrchestratorFacade writes mode-aware remote env and warns only on remaining deprecated no-op fields"
