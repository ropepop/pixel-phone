#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADGUARD_START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

admin_block="$(sed -n '/^remote_admin_upstream_healthy()/,/^}/p' "${ADGUARD_START_FILE}")"
identity_block="$(sed -n '/^identity_frontend_healthy()/,/^}/p' "${ADGUARD_START_FILE}")"
doh_block="$(sed -n '/^remote_doh_contract_healthy()/,/^}/p' "${ADGUARD_START_FILE}")"

if [[ -z "${admin_block}" || -z "${identity_block}" || -z "${doh_block}" ]]; then
  echo "FAIL: missing remote healthcheck helpers in ${ADGUARD_START_FILE}" >&2
  exit 1
fi

if ! rg -Fq 'http_code_inconclusive()' "${ADGUARD_START_FILE}"; then
  echo "FAIL: missing http_code_inconclusive helper for remote probe tolerance" >&2
  exit 1
fi

if ! printf '%s\n' "${admin_block}" | rg -Fq '000) return 0 ;;'; then
  echo "FAIL: remote admin upstream healthcheck still treats 000 probe results as fatal" >&2
  exit 1
fi

if ! printf '%s\n' "${identity_block}" | rg -Fq 'if http_code_inconclusive "${inject_code}"; then'; then
  echo "FAIL: identity frontend healthcheck does not tolerate inconclusive HTTP probe results" >&2
  exit 1
fi

if ! printf '%s\n' "${doh_block}" | rg -Fq 'if ! command -v curl >/dev/null 2>&1; then'; then
  echo "FAIL: DoH contract helper missing curl availability guard" >&2
  exit 1
fi

if ! printf '%s\n' "${doh_block}" | rg -Fq 'return 0'; then
  echo "FAIL: DoH contract helper does not tolerate inconclusive probe results" >&2
  exit 1
fi

if ! printf '%s\n' "${doh_block}" | rg -Fq 'http_code_inconclusive "${tokenized_code}"'; then
  echo "FAIL: tokenized DoH healthcheck does not treat 000 tokenized probes as inconclusive" >&2
  exit 1
fi

if ! printf '%s\n' "${doh_block}" | rg -Fq 'http_code_inconclusive "${bare_code}"'; then
  echo "FAIL: DoH healthcheck does not treat 000 bare probes as inconclusive" >&2
  exit 1
fi

echo "PASS: AdGuard remote healthchecks tolerate inconclusive HTTP probe failures"
