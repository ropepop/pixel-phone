#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
NGINX_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-remote-nginx.conf.template"
SERVICE_REPORT="${REPO_ROOT}/scripts/ops/service-availability-report.sh"

if ! rg -Fq 'ADGUARDHOME_REMOTE_DOH_ENDPOINT_MODE=' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing doh endpoint mode runtime variable" >&2
  exit 1
fi
if ! rg -Fq 'validate_doh_mode_and_token()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing fail-closed mode/token validation helper" >&2
  exit 1
fi
if ! rg -Fq 'remote_doh_contract_healthy()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing mode-aware DoH contract health check" >&2
  exit 1
fi
if ! rg -Fq 'http_code_reachable_non_route_miss()' "${START_FILE}"; then
  echo "FAIL: adguardhome-start missing no-query HTTP reachability helper for DoH contract checks" >&2
  exit 1
fi
if rg -Fq 'dns-query?dns=' "${START_FILE}"; then
  echo "FAIL: adguardhome-start still uses query-generating DoH health probes" >&2
  exit 1
fi
if rg -Fq 'PIHOLE_REMOTE_DOH_PAYLOAD=' "${START_FILE}"; then
  echo "FAIL: adguardhome-start still defines legacy DoH payload probe constant" >&2
  exit 1
fi
if ! rg -Fq '__DOH_BARE_BLOCK__' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing bare DoH route placeholder" >&2
  exit 1
fi
if ! rg -Fq 'location ~ ^/[A-Za-z0-9._~-]+/dns-query/?$' "${NGINX_TEMPLATE}"; then
  echo "FAIL: nginx template missing unknown-token 404 route" >&2
  exit 1
fi
if ! rg -Fq -- '--benchmark-requests' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing benchmark option" >&2
  exit 1
fi
if ! rg -Fq -- '--expect-lan-client-ip' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing expected LAN client option" >&2
  exit 1
fi
if ! rg -Fq -- '--include-internal-querylog' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing include-internal-querylog option" >&2
  exit 1
fi
if ! rg -Fq -- '--internal-querylog-clients' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing internal-querylog-clients option" >&2
  exit 1
fi
if ! rg -Fq -- '--internal-probe-domains' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing internal-probe-domains option" >&2
  exit 1
fi
if ! rg -Fq -- '--max-lan-gateway-share-pct' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing LAN gateway share threshold option" >&2
  exit 1
fi
if ! rg -Fq -- '--expect-router-public-ip' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing router public IP attribution option" >&2
  exit 1
fi
if ! rg -Fq -- '--max-router-lan-doh-count' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing router LAN DoH count threshold option" >&2
  exit 1
fi
if ! rg -Fq -- '--require-lan-visible' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing LAN visibility enforcement option" >&2
  exit 1
fi
if ! rg -Fq 'contract_doh_mode' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing mode-aware DoH contract result" >&2
  exit 1
fi
if ! rg -Fq 'contract_lan_visibility' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing LAN visibility contract result" >&2
  exit 1
fi
if ! rg -Fq 'querylog_view_mode' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing querylog view mode result" >&2
  exit 1
fi
if ! rg -Fq 'internal_total_count' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing internal total count result" >&2
  exit 1
fi
if ! rg -Fq 'internal_doh_count' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing internal DoH count result" >&2
  exit 1
fi
if ! rg -Fq 'user_total_count' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing user total count result" >&2
  exit 1
fi
if ! rg -Fq 'user_doh_count' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing user DoH count result" >&2
  exit 1
fi
if ! rg -Fq 'top_clients_internal' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing internal top clients result" >&2
  exit 1
fi
if ! rg -Fq 'top_clients_user' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing user top clients result" >&2
  exit 1
fi
if ! rg -Fq 'internal_probe_domain_counts' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing internal probe domain counts result" >&2
  exit 1
fi
if ! rg -Fq 'router_public_attribution_contract' "${SERVICE_REPORT}"; then
  echo "FAIL: service availability report missing router public attribution contract result" >&2
  exit 1
fi

echo "PASS: tokenized DoH runtime contract assets and reporting hooks are present"
