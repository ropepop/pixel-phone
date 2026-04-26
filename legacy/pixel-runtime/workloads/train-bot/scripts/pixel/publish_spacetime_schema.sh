#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

usage() {
  cat <<'USAGE'
Usage: publish_spacetime_schema.sh

Publishes the train-bot Spacetime module to the live database configured in .env.
USAGE
}

env_file_value() {
  local file="$1"
  local key="$2"
  local value=""

  if [[ ! -f "${file}" ]]; then
    return 0
  fi

  value="$(grep -E "^${key}=" "${file}" | tail -n 1 | cut -d= -f2- || true)"
  value="$(printf '%s' "${value}" | tr -d '\r' | sed -e "s/^['\"]//" -e "s/['\"]$//")"
  printf '%s' "${value}"
}

value_is_truthy() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
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
runtime_host="$(env_file_value "${env_file}" "TRAIN_RUNTIME_SPACETIME_HOST")"
runtime_database="$(env_file_value "${env_file}" "TRAIN_RUNTIME_SPACETIME_DATABASE")"
web_enabled="$(env_file_value "${env_file}" "TRAIN_WEB_ENABLED")"
web_host="$(env_file_value "${env_file}" "TRAIN_WEB_SPACETIME_HOST")"
web_database="$(env_file_value "${env_file}" "TRAIN_WEB_SPACETIME_DATABASE")"

if [[ -z "${runtime_host}" || -z "${runtime_database}" ]]; then
  log "TRAIN_RUNTIME_SPACETIME_HOST and TRAIN_RUNTIME_SPACETIME_DATABASE are required to publish the schema"
  exit 1
fi

if value_is_truthy "${web_enabled}"; then
  if [[ -z "${web_host}" || -z "${web_database}" ]]; then
    log "TRAIN_WEB_ENABLED is true but TRAIN_WEB_SPACETIME_HOST/TRAIN_WEB_SPACETIME_DATABASE are incomplete"
    exit 1
  fi
  if [[ "${runtime_host%/}" != "${web_host%/}" ]]; then
    log "Runtime and web Spacetime hosts differ; refusing to guess which one to publish"
    exit 1
  fi
  if [[ "${runtime_database}" != "${web_database}" ]]; then
    log "Runtime and web Spacetime databases differ; refusing to guess which one to publish"
    exit 1
  fi
fi

publish_host="${runtime_host%/}"
publish_database="${runtime_database}"

publish_log_file="$(mktemp "${TMPDIR:-/tmp}/train-bot-spacetime-publish.XXXXXX")"

cleanup_publish_log() {
  rm -f "${publish_log_file}"
}

trap cleanup_publish_log EXIT

publish_once() {
  local -a extra_args=("$@")
  local -a cmd=(
    spacetime publish
    --no-config
    --module-path "./spacetimedb"
    --server "${publish_host}"
    --yes
  )

  if (( ${#extra_args[@]} > 0 )); then
    cmd+=("${extra_args[@]}")
  fi
  cmd+=("${publish_database}")

  (
    cd "${REPO_ROOT}"
    "${cmd[@]}"
  ) >"${publish_log_file}" 2>&1
}

publish_requires_break_clients() {
  local file="$1"
  python3 - "${file}" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="ignore")
patterns = [
    r"--break-clients",
    r"break[- ]clients",
    r"breaking\s+(change|schema)",
    r"incompatible\s+schema",
    r"remove(?:d|ing)?\s+table",
    r"drop(?:ped|ping)?\s+table",
]

for pattern in patterns:
    if re.search(pattern, text, re.IGNORECASE):
        raise SystemExit(0)

raise SystemExit(1)
PY
}

log "Publishing train-bot Spacetime schema to ${publish_database} on ${publish_host}"
if publish_once; then
  cat "${publish_log_file}"
  exit 0
fi

if publish_requires_break_clients "${publish_log_file}"; then
  cat "${publish_log_file}" >&2
  log "Schema publish was rejected as a breaking change; retrying once with --break-clients"
  if publish_once --break-clients; then
    cat "${publish_log_file}"
    exit 0
  fi
fi

cat "${publish_log_file}" >&2
exit 1
