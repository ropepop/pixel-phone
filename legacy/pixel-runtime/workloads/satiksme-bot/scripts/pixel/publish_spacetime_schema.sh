#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<'USAGE'
Usage: publish_spacetime_schema.sh

Publishes the satiksme-bot Spacetime module to the live database configured in .env.
USAGE
}

if (( $# > 0 )); then
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
fi

ensure_local_env

if ! command -v spacetime >/dev/null 2>&1; then
  log "Missing required command: spacetime"
  exit 1
fi

env_file="${REPO_ROOT}/.env"

runtime_enabled="$(env_file_value "${env_file}" "SATIKSME_RUNTIME_SPACETIME_ENABLED")"
runtime_host="$(env_file_value "${env_file}" "SATIKSME_RUNTIME_SPACETIME_HOST")"
runtime_database="$(env_file_value "${env_file}" "SATIKSME_RUNTIME_SPACETIME_DATABASE")"
web_enabled="$(env_file_value "${env_file}" "SATIKSME_WEB_SPACETIME_ENABLED")"
web_host="$(env_file_value "${env_file}" "SATIKSME_WEB_SPACETIME_HOST")"
web_database="$(env_file_value "${env_file}" "SATIKSME_WEB_SPACETIME_DATABASE")"

if ! value_is_truthy "${runtime_enabled}" && ! value_is_truthy "${web_enabled}"; then
  log "Spacetime is disabled in .env; skipping schema publish"
  exit 0
fi

if value_is_truthy "${runtime_enabled}" && [[ -z "${runtime_host}" || -z "${runtime_database}" ]]; then
  log "SATIKSME_RUNTIME_SPACETIME_* is enabled but host/database is incomplete"
  exit 1
fi

if value_is_truthy "${web_enabled}" && [[ -z "${web_host}" || -z "${web_database}" ]]; then
  log "SATIKSME_WEB_SPACETIME_* is enabled but host/database is incomplete"
  exit 1
fi

if value_is_truthy "${runtime_enabled}" && value_is_truthy "${web_enabled}"; then
  if [[ "${runtime_host%/}" != "${web_host%/}" ]]; then
    log "Runtime and web Spacetime hosts differ; refusing to guess which one to publish"
    exit 1
  fi
  if [[ "${runtime_database}" != "${web_database}" ]]; then
    log "Runtime and web Spacetime databases differ; refusing to guess which one to publish"
    exit 1
  fi
fi

publish_host="${runtime_host}"
publish_database="${runtime_database}"

if [[ -z "${publish_host}" || -z "${publish_database}" ]]; then
  publish_host="${web_host}"
  publish_database="${web_database}"
fi

if [[ -z "${publish_host}" || -z "${publish_database}" ]]; then
  log "Could not determine a live Spacetime host/database from .env"
  exit 1
fi

publish_host="${publish_host%/}"

spacetime_call() {
  local procedure="$1"
  local args_json="$2"
  local body_file="$3"
  local url="${publish_host}/v1/database/${publish_database}/call/${procedure}"

  curl -sS --max-time 20 \
    -X POST \
    -H "Content-Type: application/json" \
    --data "${args_json}" \
    -o "${body_file}" \
    -w '%{http_code}' \
    "${url}"
}

spacetime_body_has_missing_procedure() {
  local body_file="$1"
  python3 - "${body_file}" <<'PY'
import json
import re
import sys

raw = open(sys.argv[1], "r", encoding="utf-8").read()
message = raw
try:
    payload = json.loads(raw)
    if isinstance(payload, dict):
        message = payload.get("error") or payload.get("message") or raw
    elif isinstance(payload, str):
        message = payload
except Exception:
    message = raw

message = str(message or "")
pattern = re.compile(r"(nonexistent|unknown)\s+(reducer|procedure)", re.IGNORECASE)
raise SystemExit(0 if pattern.search(message) else 1)
PY
}

verify_public_procedure() {
  local procedure="$1"
  local args_json="$2"
  local body_file=""
  local status=""

  body_file="$(mktemp "${TMPDIR:-/tmp}/satiksme-publish-public.XXXXXX")"
  status="$(spacetime_call "${procedure}" "${args_json}" "${body_file}")" || {
    rm -f "${body_file}"
    echo "Spacetime publish verification failed for ${procedure}: curl request error" >&2
    return 1
  }
  if [[ ! "${status}" =~ ^[0-9]+$ ]] || (( status < 200 || status >= 300 )); then
    echo "Spacetime publish verification failed for ${procedure}: expected HTTP 2xx, got ${status}" >&2
    cat "${body_file}" >&2
    rm -f "${body_file}"
    return 1
  fi
  rm -f "${body_file}"
}

verify_service_procedure_presence() {
  local procedure="$1"
  local args_json="$2"
  local body_file=""
  local status=""
  local body=""

  body_file="$(mktemp "${TMPDIR:-/tmp}/satiksme-publish-service.XXXXXX")"
  status="$(spacetime_call "${procedure}" "${args_json}" "${body_file}")" || {
    rm -f "${body_file}"
    echo "Spacetime publish verification failed for ${procedure}: curl request error" >&2
    return 1
  }
  body="$(cat "${body_file}")"
  if spacetime_body_has_missing_procedure "${body_file}"; then
    echo "Spacetime publish verification failed for ${procedure}: procedure is still missing" >&2
    cat "${body_file}" >&2
    rm -f "${body_file}"
    return 1
  fi
  if (( status >= 200 && status < 300 )); then
    rm -f "${body_file}"
    return 0
  fi
  if [[ "${status}" == "401" || "${status}" == "403" ]] || grep -Eiq 'service role required|permission|forbidden|unauthorized' <<<"${body}"; then
    rm -f "${body_file}"
    return 0
  fi
  echo "Spacetime publish verification failed for ${procedure}: unexpected HTTP ${status}" >&2
  cat "${body_file}" >&2
  rm -f "${body_file}"
  return 1
}

verify_representative_procedures() {
  local now_iso=""

  now_iso="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  verify_spacetime_schema_version "${publish_host}" "${publish_database}"
  verify_public_procedure "satiksmebot_list_public_incidents" '[0]'
  verify_public_procedure "satiksmebot_list_public_sightings" '["",1]'
  verify_service_procedure_presence "satiksmebot_service_next_report_dump" "[\"${now_iso}\"]"
}

log "Publishing satiksme-bot Spacetime schema to ${publish_database} on ${publish_host}"
(
  cd "${REPO_ROOT}"
  spacetime publish \
    --no-config \
    --module-path "./spacetimedb" \
    --server "${publish_host}" \
    --yes \
    "${publish_database}"
)

verify_attempts="${SATIKSME_SPACETIME_PUBLISH_VERIFY_ATTEMPTS:-12}"
verify_sleep_seconds="${SATIKSME_SPACETIME_PUBLISH_VERIFY_SLEEP_SEC:-5}"
verify_log_file="$(mktemp "${TMPDIR:-/tmp}/satiksme-publish-verify.XXXXXX")"
verify_ok=0

for attempt in $(seq 1 "${verify_attempts}"); do
  log "Verifying satiksme-bot Spacetime schema and representative procedures on ${publish_database} (attempt ${attempt}/${verify_attempts})"
  if verify_representative_procedures >"${verify_log_file}" 2>&1; then
    cat "${verify_log_file}"
    verify_ok=1
    break
  fi
  cat "${verify_log_file}" >&2
  if (( attempt < verify_attempts )); then
    sleep "${verify_sleep_seconds}"
  fi
done

rm -f "${verify_log_file}"

if (( verify_ok != 1 )); then
  log "Satiksme-bot Spacetime publish verification did not stabilize"
  exit 1
fi
