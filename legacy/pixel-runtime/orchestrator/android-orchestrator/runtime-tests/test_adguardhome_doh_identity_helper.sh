#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
HELPER="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identities.py"

if [[ ! -f "${HELPER}" ]]; then
  echo "FAIL: helper script missing: ${HELPER}" >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "FAIL: python3 is required" >&2
  exit 1
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT

export ADGUARDHOME_DOH_IDENTITIES_FILE="${tmpdir}/doh-identities.json"
export ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${tmpdir}/state/doh-usage-events.jsonl"
export ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${tmpdir}/state/doh-usage-cursor.json"
export ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${tmpdir}/state/identity-observability.sqlite"
export ADGUARDHOME_DOH_ACCESS_LOG_FILE="${tmpdir}/remote-nginx-doh-access.log"
export ADGUARDHOME_DOH_USAGE_RETENTION_DAYS=30
export ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED=1
export ADGUARDHOME_REMOTE_DOT_IDENTITY_LABEL_LENGTH=20
export PIHOLE_REMOTE_DOT_HOSTNAME="dns.jolkins.id.lv"
export PIHOLE_WEB_PORT=8080

run_helper() {
  python3 "${HELPER}" "$@"
}

create_one_json="${tmpdir}/create-one.json"
run_helper create --id iphone --json > "${create_one_json}"
token_one="$(jq -r '.token' "${create_one_json}")"
dot_label_one="$(jq -r '.dotLabel' "${create_one_json}")"
dot_hostname_one="$(jq -r '.dotHostname' "${create_one_json}")"
if [[ ! "${token_one}" =~ ^[A-Za-z0-9._~-]{16,128}$ ]]; then
  echo "FAIL: create auto-generated token is invalid" >&2
  exit 1
fi
if [[ ! "${dot_label_one}" =~ ^[a-z0-9]{20}$ ]]; then
  echo "FAIL: create should assign a 20-char lower-case DoT label" >&2
  exit 1
fi
if [[ "${dot_hostname_one}" != "${dot_label_one}.dns.jolkins.id.lv" ]]; then
  echo "FAIL: create should derive dotHostname from dotLabel and PIHOLE_REMOTE_DOT_HOSTNAME" >&2
  exit 1
fi
dot_sni_map="$(run_helper nginx-dot-sni-map --backend 127.0.0.1:8853)"
if [[ "${dot_sni_map}" != *"default 127.0.0.1:1;"* ]]; then
  echo "FAIL: nginx-dot-sni-map should reject unknown DoT SNI values by default" >&2
  exit 1
fi
if [[ "${dot_sni_map}" != *"${dot_hostname_one} 127.0.0.1:8853;"* ]]; then
  echo "FAIL: nginx-dot-sni-map should allow the managed DoT hostname" >&2
  exit 1
fi
if [[ "$(jq -r '.expiresEpochSeconds' "${create_one_json}")" != "null" ]]; then
  echo "FAIL: default create should set expiresEpochSeconds to null (no expiry)" >&2
  exit 1
fi

set +e
run_helper create --id iphone >/dev/null 2>&1
rc=$?
set -e
if (( rc == 0 )); then
  echo "FAIL: duplicate identity id create should fail" >&2
  exit 1
fi

set +e
run_helper create --id ipad --token "${token_one}" >/dev/null 2>&1
rc=$?
set -e
if (( rc == 0 )); then
  echo "FAIL: duplicate token create should fail" >&2
  exit 1
fi

rm -f "${ADGUARDHOME_DOH_IDENTITIES_FILE}"
legacy_token="ABCDEFGHIJKLMNOPQRSTUVWX1234567890abcdef"
run_helper ensure-legacy --legacy-token "${legacy_token}" --id default
if [[ "$(run_helper primary-token)" != "${legacy_token}" ]]; then
  echo "FAIL: legacy token import did not set primary token" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[0].id' "${ADGUARDHOME_DOH_IDENTITIES_FILE}")" != "default" ]]; then
  echo "FAIL: legacy token import did not create default identity" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[0].dotLabel' "${ADGUARDHOME_DOH_IDENTITIES_FILE}")" == "null" ]]; then
  echo "FAIL: legacy token import should assign a DoT label when DoT identities are enabled" >&2
  exit 1
fi

create_two_json="${tmpdir}/create-two.json"
run_helper create --id ipad --json > "${create_two_json}"
ipad_token="$(jq -r '.token' "${create_two_json}")"
ipad_dot_label="$(jq -r '.dotLabel' "${create_two_json}")"
legacy_iso_now="$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
legacy_epoch_ms="$(python3 - <<'PY'
from datetime import datetime, timezone
print(int(datetime.now(timezone.utc).timestamp() * 1000))
PY
)"
cat > "${ADGUARDHOME_DOH_ACCESS_LOG_FILE}" <<EOF_LEGACY_LOG
${legacy_iso_now}	/${legacy_token}/dns-query?dns=phone	200	0.010	212.3.197.32	${legacy_epoch_ms}
${legacy_iso_now}	/${ipad_token}/dns-query?dns=tablet	200	0.015	62.205.193.194	${legacy_epoch_ms}
${legacy_iso_now}	/dns-query?dns=bare	404	0.020	192.168.31.25	${legacy_epoch_ms}
EOF_LEGACY_LOG

default_usage_json="${tmpdir}/default-usage.json"
run_helper usage --identity default --window 7d --json > "${default_usage_json}"
if [[ "$(jq -r '.totalRequests' "${default_usage_json}")" != "1" ]]; then
  echo "FAIL: default identity usage should only count its own tokenized requests" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[] | select(.id == "default") | .requests' "${default_usage_json}")" != "1" ]]; then
  echo "FAIL: default identity usage row mismatch" >&2
  exit 1
fi
if jq -e '.identities[] | select(.id == "ipad")' "${default_usage_json}" >/dev/null 2>&1; then
  echo "FAIL: default identity usage should exclude non-default token traffic" >&2
  exit 1
fi
if jq -e '.identities[] | select(.id == "__bare__")' "${default_usage_json}" >/dev/null 2>&1; then
  echo "FAIL: default identity usage should exclude bare-path service traffic" >&2
  exit 1
fi

run_helper revoke --id default >/dev/null
if jq -e '.identities[] | select(.id == "default")' "${ADGUARDHOME_DOH_IDENTITIES_FILE}" >/dev/null 2>&1; then
  echo "FAIL: revoke did not remove default identity" >&2
  exit 1
fi

set +e
run_helper revoke --id ipad >/dev/null 2>&1
rc=$?
set -e
if (( rc == 0 )); then
  echo "FAIL: revoke should reject removing last identity without --allow-empty" >&2
  exit 1
fi
run_helper revoke --id ipad --allow-empty >/dev/null
if [[ "$(jq -r '.identities | length' "${ADGUARDHOME_DOH_IDENTITIES_FILE}")" != "0" ]]; then
  echo "FAIL: allow-empty revoke did not clear identity store" >&2
  exit 1
fi

rename_source_json="${tmpdir}/rename-source.json"
run_helper create --id laptop --json > "${rename_source_json}"
laptop_token="$(jq -r '.token' "${rename_source_json}")"
laptop_dot_label="$(jq -r '.dotLabel' "${rename_source_json}")"
rename_iso_now="$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
rename_epoch_ms="$(python3 - <<'PY'
from datetime import datetime, timezone
print(int(datetime.now(timezone.utc).timestamp() * 1000))
PY
)"
cat > "${ADGUARDHOME_DOH_ACCESS_LOG_FILE}" <<EOF_RENAME_LOG
${rename_iso_now}	/${laptop_token}/dns-query?dns=laptop	200	0.012	212.3.197.32	${rename_epoch_ms}
EOF_RENAME_LOG

rename_usage_before_json="${tmpdir}/rename-usage-before.json"
run_helper usage --identity laptop --window 7d --json > "${rename_usage_before_json}"
if [[ "$(jq -r '.identities[] | select(.id == "laptop") | .requests' "${rename_usage_before_json}")" != "1" ]]; then
  echo "FAIL: rename setup should record the pre-rename identity usage" >&2
  exit 1
fi

rename_result_json="${tmpdir}/rename-result.json"
run_helper rename --id laptop --new-id travel-laptop --json > "${rename_result_json}"
if [[ "$(jq -r '.renamed' "${rename_result_json}")" != "travel-laptop" ]]; then
  echo "FAIL: rename should report the new identity id" >&2
  exit 1
fi
if [[ "$(jq -r '.previousId' "${rename_result_json}")" != "laptop" ]]; then
  echo "FAIL: rename should report the previous identity id" >&2
  exit 1
fi
if [[ "$(jq -r '.token' "${rename_result_json}")" != "${laptop_token}" ]]; then
  echo "FAIL: rename should preserve the existing DoH token" >&2
  exit 1
fi
if [[ "$(jq -r '.dotLabel' "${rename_result_json}")" != "${laptop_dot_label}" ]]; then
  echo "FAIL: rename should preserve the existing DoT label" >&2
  exit 1
fi
if [[ "$(jq -r '.primaryIdentityId' "${rename_result_json}")" != "travel-laptop" ]]; then
  echo "FAIL: rename should keep the renamed identity as primary when it was primary before" >&2
  exit 1
fi

rename_list_json="${tmpdir}/rename-list.json"
run_helper list --json > "${rename_list_json}"
if jq -e '.identities[] | select(.id == "laptop")' "${rename_list_json}" >/dev/null 2>&1; then
  echo "FAIL: rename should remove the old identity id from the store listing" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[] | select(.id == "travel-laptop") | .token' "${rename_list_json}")" != "${laptop_token}" ]]; then
  echo "FAIL: rename list should keep the original token under the new identity id" >&2
  exit 1
fi

rename_usage_after_json="${tmpdir}/rename-usage-after.json"
run_helper usage --identity travel-laptop --window 7d --json > "${rename_usage_after_json}"
if [[ "$(jq -r '.identities[] | select(.id == "travel-laptop") | .requests' "${rename_usage_after_json}")" != "1" ]]; then
  echo "FAIL: rename should move historical usage onto the new identity id" >&2
  exit 1
fi
if jq -e '.identities[] | select(.id == "laptop")' "${rename_usage_after_json}" >/dev/null 2>&1; then
  echo "FAIL: rename should not leave historical usage behind on the old identity id" >&2
  exit 1
fi

rename_events_after_json="${tmpdir}/rename-events-after.json"
run_helper events --identity travel-laptop --window 7d --json > "${rename_events_after_json}"
if [[ "$(jq -r '.events[0].identityId' "${rename_events_after_json}")" != "travel-laptop" ]]; then
  echo "FAIL: rename should rewrite persisted observability events to the new identity id" >&2
  exit 1
fi

run_helper revoke --id travel-laptop --allow-empty >/dev/null
rm -f "${ADGUARDHOME_DOH_ACCESS_LOG_FILE}" "${ADGUARDHOME_DOH_USAGE_EVENTS_FILE}" "${ADGUARDHOME_DOH_USAGE_CURSOR_FILE}" "${ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE}"

create_alpha_json="${tmpdir}/create-alpha.json"
create_beta_json="${tmpdir}/create-beta.json"
create_gamma_json="${tmpdir}/create-gamma.json"
run_helper create --id alpha --json > "${create_alpha_json}"
run_helper create --id beta --json > "${create_beta_json}"
run_helper create --id gamma --expires-in 7d --json > "${create_gamma_json}"
alpha_token="$(jq -r '.token' "${create_alpha_json}")"
beta_token="$(jq -r '.token' "${create_beta_json}")"
alpha_dot_label="$(jq -r '.dotLabel' "${create_alpha_json}")"
beta_dot_label="$(jq -r '.dotLabel' "${create_beta_json}")"
gamma_expiry="$(jq -r '.expiresEpochSeconds' "${create_gamma_json}")"
if [[ ! "${gamma_expiry}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: create --expires-in should produce integer expiresEpochSeconds" >&2
  exit 1
fi
if (( gamma_expiry <= $(date +%s) )); then
  echo "FAIL: create --expires-in produced non-future expiresEpochSeconds" >&2
  exit 1
fi

set +e
run_helper create --id stale --expires-epoch "$(( $(date +%s) - 60 ))" >/dev/null 2>&1
rc=$?
set -e
if (( rc == 0 )); then
  echo "FAIL: create should reject past --expires-epoch values" >&2
  exit 1
fi

iso_now="$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
epoch_ms_now="$(python3 - <<'PY'
from datetime import datetime, timezone
print(int(datetime.now(timezone.utc).timestamp() * 1000))
PY
)"
alpha_dns_query="$(python3 - <<'PY'
import base64
import struct

name = "alpha.example.net"
labels = b"".join(bytes((len(part),)) + part.encode("ascii") for part in name.split(".")) + b"\x00"
payload = b"\x00\x00\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels + struct.pack("!HH", 1, 1)
print(base64.urlsafe_b64encode(payload).rstrip(b"=").decode("ascii"))
PY
)"
alpha_blocked_dns_query="$(python3 - <<'PY'
import base64
import struct

name = "blocked.alpha.example.net"
labels = b"".join(bytes((len(part),)) + part.encode("ascii") for part in name.split(".")) + b"\x00"
payload = b"\x00\x00\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels + struct.pack("!HH", 28, 1)
print(base64.urlsafe_b64encode(payload).rstrip(b"=").decode("ascii"))
PY
)"
beta_dns_query="$(python3 - <<'PY'
import base64
import struct

name = "beta.example.net"
labels = b"".join(bytes((len(part),)) + part.encode("ascii") for part in name.split(".")) + b"\x00"
payload = b"\x00\x00\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00" + labels + struct.pack("!HH", 1, 1)
print(base64.urlsafe_b64encode(payload).rstrip(b"=").decode("ascii"))
PY
)"
cat > "${ADGUARDHOME_DOH_ACCESS_LOG_FILE}" <<EOF_LOG
${iso_now}	/${alpha_token}/dns-query?dns=${alpha_dns_query}	200	0.010	212.3.197.32	${epoch_ms_now}
${iso_now}	/${alpha_token}/dns-query?dns=${alpha_blocked_dns_query}	404	0.060	212.3.197.32	${epoch_ms_now}
${iso_now}	/${beta_token}/dns-query?dns=${beta_dns_query}	200	0.015	62.205.193.194	${epoch_ms_now}
${iso_now}	/dns-query?dns=bare	404	0.020	192.168.31.25	${epoch_ms_now}
${iso_now}	/unknown-token/dns-query?dns=foo	404	0.025	80.89.77.222	${epoch_ms_now}
EOF_LOG

usage_json="${tmpdir}/usage.json"
run_helper usage --json > "${usage_json}"
if [[ "$(jq -r '.windowSeconds' "${usage_json}")" != "604800" ]]; then
  echo "FAIL: default usage window should be 7 days" >&2
  exit 1
fi
if [[ "$(jq -r '.totalRequests' "${usage_json}")" != "5" ]]; then
  echo "FAIL: usage total request count mismatch" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[] | select(.id == "alpha") | .requests' "${usage_json}")" != "2" ]]; then
  echo "FAIL: alpha request count mismatch" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[] | select(.id == "alpha") | .statusCounts["4xx"]' "${usage_json}")" != "1" ]]; then
  echo "FAIL: alpha 4xx count mismatch" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[] | select(.id == "beta") | .requests' "${usage_json}")" != "1" ]]; then
  echo "FAIL: beta request count mismatch" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[] | select(.id == "__bare__") | .requests' "${usage_json}")" != "1" ]]; then
  echo "FAIL: bare /dns-query usage row missing" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[] | select(.id == "__unknown__") | .requests' "${usage_json}")" != "1" ]]; then
  echo "FAIL: unknown token usage row missing" >&2
  exit 1
fi

events_json="${tmpdir}/events.json"
run_helper events --window 7d --json > "${events_json}"
if [[ "$(jq -r '.events[] | select(.identityId == "alpha") | .clientIp' "${events_json}" | head -n1)" != "212.3.197.32" ]]; then
  echo "FAIL: events output missing forwarded clientIp for enriched access log format" >&2
  exit 1
fi
if [[ "$(jq -r '.events[] | select(.identityId == "alpha") | .tsMs' "${events_json}" | head -n1)" == "0" ]]; then
  echo "FAIL: events output missing tsMs for enriched access log format" >&2
  exit 1
fi
if [[ "$(jq -r '.events[] | select(.identityId == "alpha") | .queryName' "${events_json}" | head -n1)" != "alpha.example.net" ]]; then
  echo "FAIL: events output should decode queryName from tokenized DoH access log requests" >&2
  exit 1
fi
if [[ "$(jq -r '.events[] | select(.identityId == "alpha") | .queryType' "${events_json}" | sort -u | tr '\n' ',' )" != *"AAAA"* ]]; then
  echo "FAIL: events output should decode queryType from tokenized DoH access log requests" >&2
  exit 1
fi
if [[ "$(jq -r '.events[] | select(.identityId == "alpha") | .protocol' "${events_json}" | head -n1)" != "doh" ]]; then
  echo "FAIL: events output should preserve the DoH protocol marker for stored observability rows" >&2
  exit 1
fi

store_tmp="${tmpdir}/store-expiry.json"
now_epoch="$(date +%s)"
jq --argjson now "${now_epoch}" '
  .primaryIdentityId = "alpha"
  | (.identities[] | select(.id == "alpha") | .expiresEpochSeconds) = ($now - 10)
  | (.identities[] | select(.id == "beta") | .expiresEpochSeconds) = ($now + 3600)
  | (.identities[] | select(.id == "gamma") | .expiresEpochSeconds) = null
' "${ADGUARDHOME_DOH_IDENTITIES_FILE}" > "${store_tmp}"
mv "${store_tmp}" "${ADGUARDHOME_DOH_IDENTITIES_FILE}"

if [[ "$(run_helper primary-token)" != "${beta_token}" ]]; then
  echo "FAIL: primary-token should auto-promote from expired primary to next active identity" >&2
  exit 1
fi
if ! run_helper validate-active >/dev/null; then
  echo "FAIL: validate-active should pass when at least one identity is still active" >&2
  exit 1
fi
nginx_block="${tmpdir}/nginx-block.txt"
run_helper nginx-token-block > "${nginx_block}"
if rg -Fq "/${alpha_token}/dns-query" "${nginx_block}"; then
  echo "FAIL: nginx-token-block should exclude expired identity tokens" >&2
  exit 1
fi
if ! rg -Fq "/${beta_token}/dns-query" "${nginx_block}"; then
  echo "FAIL: nginx-token-block should include non-expired identity tokens" >&2
  exit 1
fi
adguard_block="${tmpdir}/adguard-clients.yaml"
run_helper adguard-client-block > "${adguard_block}"
if rg -Fq "${alpha_dot_label}" "${adguard_block}"; then
  echo "FAIL: adguard-client-block should exclude expired DoT identity labels" >&2
  exit 1
fi
if ! rg -Fq "${beta_dot_label}" "${adguard_block}"; then
  echo "FAIL: adguard-client-block should include active DoT identity labels" >&2
  exit 1
fi

jq --argjson now "${now_epoch}" '
  (.identities[] | select(.id == "beta") | .expiresEpochSeconds) = ($now - 10)
  | (.identities[] | select(.id == "gamma") | .expiresEpochSeconds) = ($now - 10)
' "${ADGUARDHOME_DOH_IDENTITIES_FILE}" > "${store_tmp}"
mv "${store_tmp}" "${ADGUARDHOME_DOH_IDENTITIES_FILE}"
set +e
run_helper validate-active >/dev/null 2>&1
rc=$?
set -e
if (( rc == 0 )); then
  echo "FAIL: validate-active should fail when all identities are expired" >&2
  exit 1
fi

old_epoch="$(( $(date +%s) - 45 * 86400 ))"
mkdir -p "$(dirname "${ADGUARDHOME_DOH_USAGE_EVENTS_FILE}")"
printf '{"ts":%s,"identityId":"old","status":200,"requestTimeMs":1}\n' "${old_epoch}" >> "${ADGUARDHOME_DOH_USAGE_EVENTS_FILE}"
run_helper usage --window 7d --json > "${tmpdir}/usage-after-legacy-append.json"
if ! rg -Fq '"identityId":"old"' "${ADGUARDHOME_DOH_USAGE_EVENTS_FILE}"; then
  echo "FAIL: legacy migration JSONL should stay untouched once the live observability DB exists" >&2
  exit 1
fi
if [[ "$(jq -r '.totalRequests' "${tmpdir}/usage-after-legacy-append.json")" != "5" ]]; then
  echo "FAIL: appending old rows to the migration JSONL should not change live usage totals after SQLite is active" >&2
  exit 1
fi

printf '%s\t/dns-query?dns=legacy\t200\t0.040\n' "${iso_now}" >> "${ADGUARDHOME_DOH_ACCESS_LOG_FILE}"
legacy_events_json="${tmpdir}/legacy-events.json"
run_helper events --window 7d --json > "${legacy_events_json}"
if [[ "$(jq -r '.events | map(select(.identityId == "__bare__" and .clientIp == "")) | length' "${legacy_events_json}")" == "0" ]]; then
  echo "FAIL: events command should remain backward compatible with legacy 4-column access log lines" >&2
  exit 1
fi

echo "PASS: DoH identity helper create/rename/revoke/legacy import/usage/prune behavior is correct"
