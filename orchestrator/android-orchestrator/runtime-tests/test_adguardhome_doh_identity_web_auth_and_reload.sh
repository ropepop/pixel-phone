#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IDENTITY_HELPER="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identities.py"
WEB_HELPER="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-doh-identity-web.py"

for cmd in python3 jq curl; do
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "FAIL: ${cmd} is required" >&2
    exit 1
  fi
done

if [[ ! -f "${IDENTITY_HELPER}" ]]; then
  echo "FAIL: identity helper script missing: ${IDENTITY_HELPER}" >&2
  exit 1
fi
if [[ ! -f "${WEB_HELPER}" ]]; then
  echo "FAIL: web helper script missing: ${WEB_HELPER}" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
sidecar_pid=""
cleanup() {
  if [[ -n "${sidecar_pid}" ]] && kill -0 "${sidecar_pid}" >/dev/null 2>&1; then
    kill "${sidecar_pid}" >/dev/null 2>&1 || true
    wait "${sidecar_pid}" 2>/dev/null || true
  fi
  rm -rf "${tmpdir}"
}
trap cleanup EXIT

pick_port() {
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

start_sidecar() {
  local port="$1"
  shift
  python3 "${WEB_HELPER}" \
    --host 127.0.0.1 \
    --port "${port}" \
    --identityctl "${identityctl_wrapper}" \
    "$@" \
    >"${tmpdir}/sidecar-${port}.log" 2>&1 &
  sidecar_pid="$!"
  for _ in $(seq 1 40); do
    if curl -fsS "http://127.0.0.1:${port}/pixel-stack/identity/inject.js" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  echo "FAIL: sidecar did not start on port ${port}" >&2
  return 1
}

stop_sidecar() {
  if [[ -n "${sidecar_pid}" ]] && kill -0 "${sidecar_pid}" >/dev/null 2>&1; then
    kill "${sidecar_pid}" >/dev/null 2>&1 || true
    wait "${sidecar_pid}" 2>/dev/null || true
  fi
  sidecar_pid=""
}

identityctl_wrapper="${tmpdir}/identityctl"
cat > "${identityctl_wrapper}" <<EOF_WRAPPER
#!/usr/bin/env bash
set -euo pipefail
exec python3 "${IDENTITY_HELPER}" "\$@"
EOF_WRAPPER
chmod 0755 "${identityctl_wrapper}"

export ADGUARDHOME_DOH_IDENTITIES_FILE="${tmpdir}/doh-identities.json"
export ADGUARDHOME_DOH_USAGE_EVENTS_FILE="${tmpdir}/state/doh-usage-events.jsonl"
export ADGUARDHOME_DOH_USAGE_CURSOR_FILE="${tmpdir}/state/doh-usage-cursor.json"
export ADGUARDHOME_DOH_OBSERVABILITY_DB_FILE="${tmpdir}/state/identity-observability.sqlite"
export ADGUARDHOME_DOH_ACCESS_LOG_FILE="${tmpdir}/remote-nginx-doh-access.log"
export ADGUARDHOME_DOH_USAGE_RETENTION_DAYS=30
export ADGUARDHOME_DOH_IDENTITYCTL_APPLY=0
export ADGUARDHOME_DOH_IDENTITY_WEB_QUERYLOG_JSON_FILE="${tmpdir}/querylog-fixture.json"
export PIHOLE_WEB_PORT=8080

cat > "${ADGUARDHOME_DOH_IDENTITY_WEB_QUERYLOG_JSON_FILE}" <<'EOF_QUERYLOG'
{"data":[]}
EOF_QUERYLOG

auth_port="$(pick_port)"
start_sidecar "${auth_port}" --adguard-web-port 65535

auth_page_headers="${tmpdir}/auth-page-headers.txt"
curl -sS -D "${auth_page_headers}" -o /dev/null "http://127.0.0.1:${auth_port}/pixel-stack/identity"
if ! head -n 1 "${auth_page_headers}" | rg -Fq "302"; then
  echo "FAIL: unauthenticated identity page should redirect with 302" >&2
  exit 1
fi
if ! rg -Fq "Location: /login.html" "${auth_page_headers}"; then
  echo "FAIL: unauthenticated identity page should redirect to /login.html" >&2
  exit 1
fi

unauth_delete_body="${tmpdir}/unauth-delete-body.json"
unauth_delete_code="$(curl -sS -o "${unauth_delete_body}" -w '%{http_code}' \
  -X DELETE \
  "http://127.0.0.1:${auth_port}/pixel-stack/identity/api/v1/identities/alpha")"
if [[ "${unauth_delete_code}" != "401" ]]; then
  echo "FAIL: unauthenticated identity revoke should return 401" >&2
  exit 1
fi
if [[ "$(jq -r '.error' "${unauth_delete_body}")" != "Unauthorized" ]]; then
  echo "FAIL: unauthenticated revoke error payload mismatch" >&2
  exit 1
fi

unauth_rename_body="${tmpdir}/unauth-rename-body.json"
unauth_rename_code="$(curl -sS -o "${unauth_rename_body}" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -X POST \
  -d '{"newId":"beta"}' \
  "http://127.0.0.1:${auth_port}/pixel-stack/identity/api/v1/identities/alpha/rename")"
if [[ "${unauth_rename_code}" != "401" ]]; then
  echo "FAIL: unauthenticated identity rename should return 401" >&2
  exit 1
fi
if [[ "$(jq -r '.error' "${unauth_rename_body}")" != "Unauthorized" ]]; then
  echo "FAIL: unauthenticated rename error payload mismatch" >&2
  exit 1
fi

stop_sidecar

export ADGUARDHOME_DOH_IDENTITY_WEB_RESTART_ENTRY="${tmpdir}/missing-restart-entry.sh"
open_port="$(pick_port)"
start_sidecar "${open_port}" --adguard-web-port 8080 --skip-session-check

bad_origin_body="${tmpdir}/bad-origin-body.json"
bad_origin_code="$(curl -sS -o "${bad_origin_body}" -w '%{http_code}' \
  -H 'Origin: http://evil.example' \
  -H 'X-Forwarded-Proto: http' \
  -H 'Content-Type: application/json' \
  -X POST \
  -d '{"id":"alpha"}' \
  "http://127.0.0.1:${open_port}/pixel-stack/identity/api/v1/identities")"
if [[ "${bad_origin_code}" != "403" ]]; then
  echo "FAIL: mismatched origin should return 403" >&2
  exit 1
fi
if ! jq -r '.error' "${bad_origin_body}" | rg -Fq 'Origin validation failed'; then
  echo "FAIL: mismatched origin should return origin validation error" >&2
  exit 1
fi

bad_origin_rename_body="${tmpdir}/bad-origin-rename-body.json"
bad_origin_rename_code="$(curl -sS -o "${bad_origin_rename_body}" -w '%{http_code}' \
  -H 'Origin: http://evil.example' \
  -H 'X-Forwarded-Proto: http' \
  -H 'Content-Type: application/json' \
  -X POST \
  -d '{"newId":"beta"}' \
  "http://127.0.0.1:${open_port}/pixel-stack/identity/api/v1/identities/alpha/rename")"
if [[ "${bad_origin_rename_code}" != "403" ]]; then
  echo "FAIL: mismatched origin on rename should return 403" >&2
  exit 1
fi
if ! jq -r '.error' "${bad_origin_rename_body}" | rg -Fq 'Origin validation failed'; then
  echo "FAIL: mismatched origin on rename should return origin validation error" >&2
  exit 1
fi

create_alpha_json="${tmpdir}/create-alpha.json"
curl -fsS \
  -H "Origin: http://127.0.0.1:${open_port}" \
  -H "X-Forwarded-Proto: http" \
  -H 'Content-Type: application/json' \
  -X POST \
  -d '{"id":"alpha"}' \
  "http://127.0.0.1:${open_port}/pixel-stack/identity/api/v1/identities" > "${create_alpha_json}"
if [[ "$(jq -r '.created' "${create_alpha_json}")" != "alpha" ]]; then
  echo "FAIL: create(alpha) response mismatch" >&2
  exit 1
fi
if [[ "$(jq -r '.applied' "${create_alpha_json}")" != "false" ]]; then
  echo "FAIL: create(alpha) should report applied=false when reload entrypoint is unavailable" >&2
  exit 1
fi

create_beta_json="${tmpdir}/create-beta.json"
curl -fsS \
  -H "Origin: http://127.0.0.1:${open_port}" \
  -H "X-Forwarded-Proto: http" \
  -H 'Content-Type: application/json' \
  -X POST \
  -d '{"id":"beta"}' \
  "http://127.0.0.1:${open_port}/pixel-stack/identity/api/v1/identities" > "${create_beta_json}"
if [[ "$(jq -r '.applied' "${create_beta_json}")" != "false" ]]; then
  echo "FAIL: create(beta) should report applied=false when reload entrypoint is unavailable" >&2
  exit 1
fi

rename_beta_json="${tmpdir}/rename-beta.json"
curl -fsS \
  -H "Origin: http://127.0.0.1:${open_port}" \
  -H "X-Forwarded-Proto: http" \
  -H 'Content-Type: application/json' \
  -X POST \
  -d '{"newId":"beta-main"}' \
  "http://127.0.0.1:${open_port}/pixel-stack/identity/api/v1/identities/beta/rename" > "${rename_beta_json}"
if [[ "$(jq -r '.renamed' "${rename_beta_json}")" != "beta-main" ]]; then
  echo "FAIL: rename(beta) response should report the new identity id" >&2
  exit 1
fi
if [[ "$(jq -r '.previousId' "${rename_beta_json}")" != "beta" ]]; then
  echo "FAIL: rename(beta) response should report the previous identity id" >&2
  exit 1
fi
if [[ "$(jq -r '.applied' "${rename_beta_json}")" != "false" ]]; then
  echo "FAIL: rename(beta) should report applied=false when reload entrypoint is unavailable" >&2
  exit 1
fi

revoke_alpha_json="${tmpdir}/revoke-alpha.json"
curl -fsS \
  -H "Origin: http://127.0.0.1:${open_port}" \
  -H "X-Forwarded-Proto: http" \
  -X DELETE \
  "http://127.0.0.1:${open_port}/pixel-stack/identity/api/v1/identities/alpha" > "${revoke_alpha_json}"
if [[ "$(jq -r '.revoked' "${revoke_alpha_json}")" != "alpha" ]]; then
  echo "FAIL: revoke(alpha) response mismatch" >&2
  exit 1
fi
if [[ "$(jq -r '.applied' "${revoke_alpha_json}")" != "false" ]]; then
  echo "FAIL: revoke(alpha) should report applied=false when reload entrypoint is unavailable" >&2
  exit 1
fi

echo "PASS: DoH identity web auth/origin/create/rename/reload contracts are correct"
