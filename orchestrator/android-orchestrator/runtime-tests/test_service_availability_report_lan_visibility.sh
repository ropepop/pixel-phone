#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SERVICE_REPORT="${REPO_ROOT}/scripts/ops/service-availability-report.sh"

if [[ ! -x "${SERVICE_REPORT}" ]]; then
  echo "FAIL: service report script not executable: ${SERVICE_REPORT}" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required for lan visibility report test" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

cat > "${tmpdir}/querylog.json" <<'EOF_JSON'
{
  "data": [
    {"client": "192.168.31.1", "client_proto": "doh", "question": {"name": "router.example.net"}},
    {"client": "192.168.31.1", "client_proto": "doh", "question": {"name": "router.example.net"}},
    {"client": "192.168.31.1", "client_proto": "doh", "question": {"name": "router.example.net"}},
    {"client": "192.168.31.1", "client_proto": "doh", "question": {"name": "router.example.net"}},
    {"client": "192.168.31.1", "client_proto": "doh", "question": {"name": "router.example.net"}},
    {"client": "192.168.31.46", "client_proto": "doh", "question": {"name": "example.com"}},
    {"client": "192.168.31.46", "client_proto": ""},
    {"client": "127.0.0.1", "client_proto": "doh", "question": {"name": "example.com"}},
    {"client": "62.205.193.194", "client_proto": "doh"},
    {"client": "62.205.193.194", "client_proto": "doh"}
  ]
}
EOF_JSON

pass_json="${tmpdir}/pass.json"
bash "${SERVICE_REPORT}" \
  --host 127.0.0.1 \
  --fqdn localhost \
  --timeout 1 \
  --skip-root-checks \
  --querylog-json-file "${tmpdir}/querylog.json" \
  --expect-lan-client-ip 192.168.31.46 \
  --lan-gateway-ip 192.168.31.1 \
  --expect-router-public-ip 62.205.193.194 \
  --expect-router-lan-ip 192.168.31.1 \
  --max-router-lan-doh-count 5 \
  --max-lan-gateway-share-pct 90 \
  --require-lan-visible \
  --json-out "${pass_json}" >/dev/null

if [[ "$(jq -r '.lan.contract_lan_visibility' "${pass_json}")" != "pass" ]]; then
  echo "FAIL: expected lan visibility contract to pass" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.querylog_view_mode' "${pass_json}")" != "user_only" ]]; then
  echo "FAIL: expected default querylog view mode to be user_only" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.expected_client_seen' "${pass_json}")" != "true" ]]; then
  echo "FAIL: expected lan client to be detected in querylog" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.gateway_doh_count' "${pass_json}")" != "5" ]]; then
  echo "FAIL: expected gateway DoH count to be 5" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.total_doh_count' "${pass_json}")" != "8" ]]; then
  echo "FAIL: expected total DoH count to be 8 when internal rows are hidden" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.gateway_share_pct' "${pass_json}")" != "62.50" ]]; then
  echo "FAIL: expected gateway share to be 62.50 with internal rows hidden" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.top_clients' "${pass_json}")" != *"192.168.31.1:doh:5"* ]]; then
  echo "FAIL: expected top client distribution to include gateway DoH count" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.user_total_count' "${pass_json}")" != "9" ]]; then
  echo "FAIL: expected user total count to be 9" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.user_doh_count' "${pass_json}")" != "8" ]]; then
  echo "FAIL: expected user DoH count to be 8" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.internal_total_count' "${pass_json}")" != "1" ]]; then
  echo "FAIL: expected internal total count to be 1" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.internal_doh_count' "${pass_json}")" != "1" ]]; then
  echo "FAIL: expected internal DoH count to be 1" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.top_clients_internal' "${pass_json}")" != "127.0.0.1:doh:1" ]]; then
  echo "FAIL: expected internal top clients summary to include loopback DoH entry" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.top_clients_user' "${pass_json}")" == *"127.0.0.1:doh:1"* ]]; then
  echo "FAIL: expected user top clients summary to exclude loopback internal entry by default" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.internal_probe_domain_counts' "${pass_json}")" != "example.com:1" ]]; then
  echo "FAIL: expected internal probe domain counts to include example.com:1" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.router_public_ip_doh_count' "${pass_json}")" != "2" ]]; then
  echo "FAIL: expected router public DoH count to be 2" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.router_lan_ip_doh_count' "${pass_json}")" != "5" ]]; then
  echo "FAIL: expected router LAN DoH count to be 5" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.router_public_attribution_contract' "${pass_json}")" != "pass" ]]; then
  echo "FAIL: expected router public attribution contract to pass" >&2
  exit 1
fi

include_json="${tmpdir}/include.json"
bash "${SERVICE_REPORT}" \
  --host 127.0.0.1 \
  --fqdn localhost \
  --timeout 1 \
  --skip-root-checks \
  --querylog-json-file "${tmpdir}/querylog.json" \
  --include-internal-querylog \
  --expect-lan-client-ip 192.168.31.46 \
  --lan-gateway-ip 192.168.31.1 \
  --expect-router-public-ip 62.205.193.194 \
  --expect-router-lan-ip 192.168.31.1 \
  --max-router-lan-doh-count 5 \
  --max-lan-gateway-share-pct 90 \
  --require-lan-visible \
  --json-out "${include_json}" >/dev/null

if [[ "$(jq -r '.lan.querylog_view_mode' "${include_json}")" != "all" ]]; then
  echo "FAIL: expected querylog view mode to be all when include-internal-querylog is set" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.total_doh_count' "${include_json}")" != "9" ]]; then
  echo "FAIL: expected inclusive total DoH count to be 9" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.gateway_share_pct' "${include_json}")" != "55.56" ]]; then
  echo "FAIL: expected inclusive gateway share to be 55.56" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.top_clients' "${include_json}")" != *"127.0.0.1:doh:1"* ]]; then
  echo "FAIL: expected inclusive top clients summary to include internal loopback entry" >&2
  exit 1
fi

fail_json="${tmpdir}/fail.json"
set +e
bash "${SERVICE_REPORT}" \
  --host 127.0.0.1 \
  --fqdn localhost \
  --timeout 1 \
  --skip-root-checks \
  --querylog-json-file "${tmpdir}/querylog.json" \
  --expect-lan-client-ip 192.168.31.46 \
  --lan-gateway-ip 192.168.31.1 \
  --expect-router-public-ip 62.205.193.194 \
  --expect-router-lan-ip 192.168.31.1 \
  --max-router-lan-doh-count 2 \
  --max-lan-gateway-share-pct 80 \
  --require-lan-visible \
  --json-out "${fail_json}" >/dev/null
rc=$?
set -e
if (( rc == 0 )); then
  echo "FAIL: expected --require-lan-visible run to fail when router attribution contract fails" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.contract_lan_visibility' "${fail_json}")" != "pass" ]]; then
  echo "FAIL: expected LAN visibility contract to remain pass in router-attribution-only failure case" >&2
  exit 1
fi
if [[ "$(jq -r '.lan.router_public_attribution_contract' "${fail_json}")" != "fail" ]]; then
  echo "FAIL: expected router public attribution contract to fail when threshold exceeded" >&2
  exit 1
fi

echo "PASS: service availability report enforces LAN visibility and router public attribution querylog contracts"
