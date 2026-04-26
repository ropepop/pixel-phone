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

runtime_env_file="${tmpdir}/arbuzas-runtime.env"
cat > "${runtime_env_file}" <<'EOF_RUNTIME'
ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED=1
ADGUARDHOME_REMOTE_DOT_IDENTITY_LABEL_LENGTH=20
PIHOLE_REMOTE_DOT_HOSTNAME=dns.jolkins.id.lv
EOF_RUNTIME

fallback_store="${tmpdir}/fallback-doh-identities.json"
fallback_create_json="${tmpdir}/fallback-create.json"
env \
  -u ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED \
  -u ADGUARDHOME_REMOTE_DOT_IDENTITY_LABEL_LENGTH \
  -u PIHOLE_REMOTE_DOT_HOSTNAME \
  ADGUARDHOME_DOH_IDENTITIES_FILE="${fallback_store}" \
  ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${tmpdir}/fallback-state/doh-usage-events.jsonl" \
  ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${tmpdir}/fallback-state/doh-usage-cursor.json" \
  ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${tmpdir}/fallback-state/identity-observability.sqlite" \
  ADGUARDHOME_DOH_ACCESS_LOG_FILE="${tmpdir}/fallback-remote-nginx-doh-access.log" \
  ARBUZAS_DNS_RUNTIME_ENV_FILE="${runtime_env_file}" \
  python3 "${HELPER}" create --id runtime-fallback --json > "${fallback_create_json}"
if [[ "$(jq -r '.dotHostname' "${fallback_create_json}")" != "$(jq -r '.dotLabel' "${fallback_create_json}").dns.jolkins.id.lv" ]]; then
  echo "FAIL: helper should load DoT hostname settings from ARBUZAS_DNS_RUNTIME_ENV_FILE" >&2
  exit 1
fi
if [[ -e "${fallback_store}" ]]; then
  echo "FAIL: helper should not recreate the legacy identity JSON file after moving runtime state to SQLite" >&2
  exit 1
fi
if [[ "$(env \
  -u ADGUARDHOME_REMOTE_DOT_IDENTITY_ENABLED \
  -u ADGUARDHOME_REMOTE_DOT_IDENTITY_LABEL_LENGTH \
  -u PIHOLE_REMOTE_DOT_HOSTNAME \
  ADGUARDHOME_DOH_IDENTITIES_FILE="${fallback_store}" \
  ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${tmpdir}/fallback-state/doh-usage-events.jsonl" \
  ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${tmpdir}/fallback-state/doh-usage-cursor.json" \
  ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${tmpdir}/fallback-state/identity-observability.sqlite" \
  ADGUARDHOME_DOH_ACCESS_LOG_FILE="${tmpdir}/fallback-remote-nginx-doh-access.log" \
  ARBUZAS_DNS_RUNTIME_ENV_FILE="${runtime_env_file}" \
  python3 "${HELPER}" list --json | jq -r '.dotIdentityEnabled')" != "true" ]]; then
  echo "FAIL: helper list should expose DoT mode from ARBUZAS_DNS_RUNTIME_ENV_FILE" >&2
  exit 1
fi

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
if [[ -e "${ADGUARDHOME_DOH_IDENTITIES_FILE}" ]]; then
  echo "FAIL: create should persist identities in SQLite without recreating the legacy JSON file" >&2
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

rm -f "${ADGUARDHOME_DOH_IDENTITIES_FILE}" "${ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE}"
legacy_token="ABCDEFGHIJKLMNOPQRSTUVWX1234567890abcdef"
run_helper ensure-legacy --legacy-token "${legacy_token}" --id default
if [[ "$(run_helper primary-token)" != "${legacy_token}" ]]; then
  echo "FAIL: legacy token import did not set primary token" >&2
  exit 1
fi
legacy_list_json="${tmpdir}/legacy-list.json"
run_helper list --json > "${legacy_list_json}"
if [[ "$(jq -r '.identities[0].id' "${legacy_list_json}")" != "default" ]]; then
  echo "FAIL: legacy token import did not create default identity" >&2
  exit 1
fi
if [[ "$(jq -r '.identities[0].dotLabel' "${legacy_list_json}")" == "null" ]]; then
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
post_revoke_default_json="${tmpdir}/post-revoke-default.json"
run_helper list --json > "${post_revoke_default_json}"
if jq -e '.identities[] | select(.id == "default")' "${post_revoke_default_json}" >/dev/null 2>&1; then
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
post_revoke_all_json="${tmpdir}/post-revoke-all.json"
run_helper list --json > "${post_revoke_all_json}"
if [[ "$(jq -r '.identities | length' "${post_revoke_all_json}")" != "0" ]]; then
  echo "FAIL: allow-empty revoke did not clear identity store" >&2
  exit 1
fi
rm -f "${ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE}" "${ADGUARDHOME_DOH_ACCESS_LOG_FILE}"

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

now_epoch="$(date +%s)"
python3 - <<'PY' "${ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE}" "${now_epoch}"
import sqlite3
import sys

db_file = sys.argv[1]
now = int(sys.argv[2])
conn = sqlite3.connect(db_file)
conn.execute("UPDATE state_settings SET value = ? WHERE key = 'primaryIdentityId'", ("alpha",))
conn.execute("UPDATE state_identities SET expires_epoch_seconds = ? WHERE identity_id = 'alpha'", (now - 10,))
conn.execute("UPDATE state_identities SET expires_epoch_seconds = ? WHERE identity_id = 'beta'", (now + 3600,))
conn.execute("UPDATE state_identities SET expires_epoch_seconds = NULL WHERE identity_id = 'gamma'")
conn.commit()
conn.close()
PY

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

python3 - <<'PY' "${ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE}" "${now_epoch}"
import sqlite3
import sys

db_file = sys.argv[1]
now = int(sys.argv[2])
conn = sqlite3.connect(db_file)
conn.execute("UPDATE state_identities SET expires_epoch_seconds = ? WHERE identity_id = 'beta'", (now - 10,))
conn.execute("UPDATE state_identities SET expires_epoch_seconds = ? WHERE identity_id = 'gamma'", (now - 10,))
conn.commit()
conn.close()
PY
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

legacy_log_tmp="${tmpdir}/legacy-log-import"
mkdir -p "${legacy_log_tmp}/state"
printf '%s\t/dns-query?dns=legacy\t200\t0.040\n' "${iso_now}" > "${legacy_log_tmp}/remote-nginx-doh-access.log"
legacy_events_json="${tmpdir}/legacy-events.json"
env \
  ADGUARDHOME_DOH_IDENTITIES_FILE="${legacy_log_tmp}/doh-identities.json" \
  ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${legacy_log_tmp}/state/doh-usage-events.jsonl" \
  ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${legacy_log_tmp}/state/doh-usage-cursor.json" \
  ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${legacy_log_tmp}/state/identity-observability.sqlite" \
  ADGUARDHOME_DOH_ACCESS_LOG_FILE="${legacy_log_tmp}/remote-nginx-doh-access.log" \
  ADGUARDHOME_DOH_IDENTITYCTL_APPLY=0 \
  python3 "${HELPER}" events --window 7d --json > "${legacy_events_json}"
if [[ "$(jq -r '.events | map(select(.identityId == "__bare__" and .clientIp == "")) | length' "${legacy_events_json}")" == "0" ]]; then
  echo "FAIL: events command should remain backward compatible when importing legacy 4-column access log lines into SQLite" >&2
  exit 1
fi

compaction_tmp="${tmpdir}/compaction"
mkdir -p "${compaction_tmp}/state"
compaction_db="${compaction_tmp}/state/identity-observability.sqlite"
python3 - <<'PY' "${compaction_db}"
import os
import sqlite3
import sys

db_path = sys.argv[1]
payload = "q" * 4096
conn = sqlite3.connect(db_path)
conn.execute("PRAGMA journal_mode=WAL")
conn.execute(
    """
    CREATE TABLE doh_events (
      ts_ms INTEGER NOT NULL,
      identity_id TEXT NOT NULL,
      client_ip TEXT NOT NULL,
      status INTEGER NOT NULL,
      request_time_ms INTEGER NOT NULL,
      query_name TEXT NOT NULL DEFAULT '',
      query_type TEXT NOT NULL DEFAULT '',
      protocol TEXT NOT NULL DEFAULT ''
    )
    """
)
rows = [
    (index, "alpha", "192.168.31.40", 200, 10, payload, "A", "doh")
    for index in range(4000)
]
conn.executemany(
    """
    INSERT INTO doh_events (
      ts_ms,
      identity_id,
      client_ip,
      status,
      request_time_ms,
      query_name,
      query_type,
      protocol
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """,
    rows,
)
conn.commit()
conn.execute("DELETE FROM doh_events WHERE ts_ms % 2 = 0")
conn.commit()
conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
conn.close()
os.chmod(db_path, 0o640)
PY
env \
  ADGUARDHOME_DOH_IDENTITIES_FILE="${compaction_tmp}/doh-identities.json" \
  ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${compaction_tmp}/state/doh-usage-events.jsonl" \
  ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${compaction_tmp}/state/doh-usage-cursor.json" \
  ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${compaction_db}" \
  ADGUARDHOME_DOH_ACCESS_LOG_FILE="${compaction_tmp}/remote-nginx-doh-access.log" \
  ADGUARDHOME_DOH_IDENTITYCTL_APPLY=0 \
  python3 "${HELPER}" compact-observability-db --json > "${compaction_tmp}/compact.json"
if [[ "$(jq -r '.status' "${compaction_tmp}/compact.json")" != "compacted" ]]; then
  echo "FAIL: compact-observability-db should report status=compacted for a normal run" >&2
  exit 1
fi
before_bytes="$(jq -r '.beforeBytes' "${compaction_tmp}/compact.json")"
after_bytes="$(jq -r '.afterBytes' "${compaction_tmp}/compact.json")"
if [[ "${after_bytes}" -ge "${before_bytes}" ]]; then
  echo "FAIL: compact-observability-db should reduce on-disk size when the DB has reclaimable pages" >&2
  exit 1
fi
if [[ "$(python3 -c "import sqlite3, sys; conn = sqlite3.connect(sys.argv[1]); print(conn.execute('select count(*) from doh_events').fetchone()[0]); conn.close()" "${compaction_db}")" != "2000" ]]; then
  echo "FAIL: compact-observability-db should preserve the live row count" >&2
  exit 1
fi
compaction_mode="$(python3 -c "import os, stat, sys; print(oct(stat.S_IMODE(os.stat(sys.argv[1]).st_mode)))" "${compaction_db}")"
if [[ "${compaction_mode}" != "0o640" ]]; then
  echo "FAIL: compact-observability-db should preserve DB file permissions" >&2
  exit 1
fi
python3 - <<'PY' "${compaction_db}"
import sqlite3
import sys

conn = sqlite3.connect(sys.argv[1])
conn.execute(
    """
    INSERT INTO doh_events (
      ts_ms,
      identity_id,
      client_ip,
      status,
      request_time_ms,
      query_name,
      query_type,
      protocol
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """,
    (9001, "alpha", "192.168.31.41", 200, 12, "post-compact.example", "A", "doh"),
)
conn.commit()
conn.close()
PY

already_running_pid=""
python3 - <<'PY' "${HELPER}" "${compaction_db}" &
import importlib.util
import os
import sys
import time

helper_path = sys.argv[1]
db_path = sys.argv[2]
os.environ["ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE"] = db_path
spec = importlib.util.spec_from_file_location("identity_helper_compaction_lock", helper_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
handle = module._acquire_lock_handle(module.observability_compaction_lock_file(), exclusive=True)
try:
    time.sleep(2.5)
finally:
    module._release_lock_handle(handle)
PY
already_running_pid=$!
sleep 0.3
env \
  ADGUARDHOME_DOH_IDENTITIES_FILE="${compaction_tmp}/doh-identities.json" \
  ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${compaction_tmp}/state/doh-usage-events.jsonl" \
  ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${compaction_tmp}/state/doh-usage-cursor.json" \
  ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${compaction_db}" \
  ADGUARDHOME_DOH_ACCESS_LOG_FILE="${compaction_tmp}/remote-nginx-doh-access.log" \
  ADGUARDHOME_DOH_IDENTITYCTL_APPLY=0 \
  python3 "${HELPER}" compact-observability-db --json > "${compaction_tmp}/already-running.json"
wait "${already_running_pid}"
if [[ "$(jq -r '.status' "${compaction_tmp}/already-running.json")" != "already_running" ]]; then
  echo "FAIL: overlapping compaction attempts should return already_running" >&2
  exit 1
fi

write_block_tmp="${tmpdir}/write-block"
mkdir -p "${write_block_tmp}/state"
write_block_db="${write_block_tmp}/state/identity-observability.sqlite"
python3 - <<'PY' "${write_block_db}"
import sqlite3
import sys

conn = sqlite3.connect(sys.argv[1])
conn.execute("PRAGMA journal_mode=WAL")
conn.execute(
    """
    CREATE TABLE doh_events (
      ts_ms INTEGER NOT NULL,
      identity_id TEXT NOT NULL,
      client_ip TEXT NOT NULL,
      status INTEGER NOT NULL,
      request_time_ms INTEGER NOT NULL,
      query_name TEXT NOT NULL DEFAULT '',
      query_type TEXT NOT NULL DEFAULT '',
      protocol TEXT NOT NULL DEFAULT ''
    )
    """
)
conn.execute(
    """
    INSERT INTO doh_events (
      ts_ms,
      identity_id,
      client_ip,
      status,
      request_time_ms,
      query_name,
      query_type,
      protocol
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    """,
    (1, "alpha", "192.168.31.42", 200, 10, "seed.example", "A", "doh"),
)
conn.commit()
conn.close()
PY
python3 - <<'PY' "${HELPER}" "${write_block_db}" &
import importlib.util
import os
import sys
import time

helper_path = sys.argv[1]
db_path = sys.argv[2]
os.environ["ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE"] = db_path
spec = importlib.util.spec_from_file_location("identity_helper_block_writer", helper_path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
conn = module.connect_observability_db(readonly=False)
try:
    conn.execute(
        """
        INSERT INTO doh_events (
          ts_ms,
          identity_id,
          client_ip,
          status,
          request_time_ms,
          query_name,
          query_type,
          protocol
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (2, "alpha", "192.168.31.43", 200, 11, "held.example", "A", "doh"),
    )
    time.sleep(2.5)
    conn.commit()
finally:
    conn.close()
PY
writer_pid=$!
sleep 0.3
start_epoch="$(date +%s)"
env \
  ADGUARDHOME_DOH_IDENTITIES_FILE="${write_block_tmp}/doh-identities.json" \
  ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${write_block_tmp}/state/doh-usage-events.jsonl" \
  ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${write_block_tmp}/state/doh-usage-cursor.json" \
  ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${write_block_db}" \
  ADGUARDHOME_DOH_ACCESS_LOG_FILE="${write_block_tmp}/remote-nginx-doh-access.log" \
  ADGUARDHOME_DOH_IDENTITYCTL_APPLY=0 \
  python3 "${HELPER}" compact-observability-db --json > "${write_block_tmp}/compact.json"
elapsed_seconds="$(( $(date +%s) - start_epoch ))"
wait "${writer_pid}"
if (( elapsed_seconds < 2 )); then
  echo "FAIL: compaction should wait for active writers holding the shared lock" >&2
  exit 1
fi
if [[ "$(jq -r '.status' "${write_block_tmp}/compact.json")" != "compacted" ]]; then
  echo "FAIL: compaction should finish successfully once active writers release the shared lock" >&2
  exit 1
fi
if [[ "$(python3 -c "import sqlite3, sys; conn = sqlite3.connect(sys.argv[1]); print(conn.execute('select count(*) from doh_events').fetchone()[0]); conn.close()" "${write_block_db}")" != "2" ]]; then
  echo "FAIL: writes should still be present after compaction waits for and resumes behind the shared lock" >&2
  exit 1
fi

echo "PASS: DoH identity helper create/rename/revoke/legacy import/usage/prune behavior is correct"
