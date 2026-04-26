#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
CONFIG_MODEL_FILE="${REPO_ROOT}/android-orchestrator/core-config/src/main/kotlin/lv/jolkins/pixelorchestrator/coreconfig/StackConfigV1.kt"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"

if ! rg -Fq 'tls:' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing TLS config rendering block" >&2
  exit 1
fi
if ! rg -Fq 'port_https: ${PIHOLE_REMOTE_HTTPS_PORT}' "${START_FILE}" && ! rg -Fq 'port_https: 0' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing mode-aware tls.port_https wiring" >&2
  exit 1
fi
if ! rg -Fq 'allow_unencrypted_doh: true' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing tokenized local DoH allowance" >&2
  exit 1
fi
if ! rg -Fq 'certificate_path: "${PIHOLE_REMOTE_TLS_CERT_FILE}"' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing TLS certificate path wiring" >&2
  exit 1
fi
if ! rg -Fq 'private_key_path: "${PIHOLE_REMOTE_TLS_KEY_FILE}"' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing TLS private key path wiring" >&2
  exit 1
fi
if ! rg -Fq 'anonymize_client_ip: false' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing querylog anonymize_client_ip=false" >&2
  exit 1
fi

if rg -Fq 'PIHOLE_REMOTE_NGINX_WORKERS=' "${START_FILE}"; then
  echo "FAIL: adguardhome-start still references removed nginx worker setting" >&2
  exit 1
fi
if rg -Fq 'PIHOLE_REMOTE_NGINX_WORKERS=' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade still emits removed PIHOLE_REMOTE_NGINX_WORKERS env" >&2
  exit 1
fi
if rg -Fq 'PIHOLE_REMOTE_DOH_INTERNAL_PORT=' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade still emits removed PIHOLE_REMOTE_DOH_INTERNAL_PORT env" >&2
  exit 1
fi
if ! rg -Fq 'ADGUARDHOME_REMOTE_DOH_ENDPOINT_MODE=${dohEndpointMode}' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing ADGUARDHOME_REMOTE_DOH_ENDPOINT_MODE env output" >&2
  exit 1
fi
if ! rg -Fq 'ADGUARDHOME_REMOTE_DOH_PATH_TOKEN=${config.remote.dohPathToken}' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing ADGUARDHOME_REMOTE_DOH_PATH_TOKEN env output for tokenized/dual modes" >&2
  exit 1
fi
if ! rg -Fq 'ADGUARDHOME_REMOTE_ROUTER_PUBLIC_IP_ATTRIBUTION_ENABLED=${if (config.remote.routerPublicIpAttributionEnabled) 1 else 0}' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing ADGUARDHOME_REMOTE_ROUTER_PUBLIC_IP_ATTRIBUTION_ENABLED env output" >&2
  exit 1
fi
if ! rg -Fq 'ADGUARDHOME_REMOTE_ROUTER_LAN_IP=${config.remote.routerLanIp.trim()}' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade missing ADGUARDHOME_REMOTE_ROUTER_LAN_IP env output" >&2
  exit 1
fi
if rg -Fq 'PIHOLE_REMOTE_DOH_RATE_LIMIT_RPS=' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade still emits removed PIHOLE_REMOTE_DOH_RATE_LIMIT_RPS env" >&2
  exit 1
fi

if ! rg -Fq 'val dohEndpointMode: String = "native"' "${CONFIG_MODEL_FILE}"; then
  echo "FAIL: StackConfigV1 missing dohEndpointMode field" >&2
  exit 1
fi
if ! rg -Fq 'Used when dohEndpointMode is tokenized/dual' "${CONFIG_MODEL_FILE}"; then
  echo "FAIL: StackConfigV1 missing dohPathToken operational annotation" >&2
  exit 1
fi
if ! rg -Fq 'routerPublicIpAttributionEnabled: Boolean = false' "${CONFIG_MODEL_FILE}"; then
  echo "FAIL: StackConfigV1 missing routerPublicIpAttributionEnabled field" >&2
  exit 1
fi
if ! rg -Fq 'routerLanIp: String = ""' "${CONFIG_MODEL_FILE}"; then
  echo "FAIL: StackConfigV1 missing routerLanIp field" >&2
  exit 1
fi
if rg -Fq 'nginxWorkerProcesses' "${CONFIG_MODEL_FILE}"; then
  echo "FAIL: StackConfigV1 still exposes removed nginxWorkerProcesses field" >&2
  exit 1
fi

echo "PASS: tokenized mode TLS/DoH config wiring keeps nginxWorkerProcesses removed across config and env surfaces"
