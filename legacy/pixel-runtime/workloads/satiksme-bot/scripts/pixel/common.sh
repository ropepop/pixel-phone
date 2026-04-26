#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/../.." && pwd)"
# shellcheck source=../../../../tools/pixel/transport.sh
source "${WORKSPACE_ROOT}/tools/pixel/transport.sh"

PIXEL_RUN_ID="${PIXEL_RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM}"
export PIXEL_TRANSPORT ADB_SERIAL PIXEL_SSH_HOST PIXEL_SSH_PORT PIXEL_RUN_ID

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"
}

adb_cmd() {
  local subcommand="${1:-}"
  shift || true

	case "${subcommand}" in
	  get-state)
	    pixel_transport_require_device >/dev/null
	    printf 'device\n'
	    ;;
	  install)
	    if [[ "${1:-}" == "-r" ]]; then
	      shift
	    fi
	    pixel_transport_install_apk "$1"
	    ;;
	  push)
	    pixel_transport_push "$1" "$2"
	    ;;
	  pull)
	    pixel_transport_pull "$1" "$2"
	    ;;
	  forward)
	    case "${1:-}" in
        --remove)
          pixel_transport_forward_stop "${2#tcp:}"
          ;;
        tcp:*)
          pixel_transport_forward_start "${1#tcp:}" "${2#tcp:}"
          ;;
        *)
          echo "Unsupported adb_cmd forward invocation: ${subcommand} $*" >&2
          return 1
          ;;
      esac
      ;;
    shell)
      if [[ "${1:-}" == "su" && "${2:-}" == "-c" ]]; then
        shift 2
      fi
      pixel_transport_root_shell "$(printf '%s' "$*")"
      ;;
    *)
      local -a cmd=("${ADB_BIN}")
      if [[ -n "${ADB_SERIAL:-}" ]]; then
        cmd+=(-s "${ADB_SERIAL}")
      fi
      cmd+=("${subcommand}" "$@")
      "${cmd[@]}"
      ;;
  esac
}

adb_shell_root() {
  pixel_transport_root_shell "$1"
}

adb_shell_root_stdin() {
  pixel_transport_root_shell_stdin
}

transport_args() {
  local args=()
  pixel_transport_append_cli_args args
  printf '%s\n' "${args[@]}"
}

ensure_output_dirs() {
  bash "${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh" >&2
  mkdir -p \
    "$REPO_ROOT/output/pixel" \
    "$REPO_ROOT/output/browser-use" \
    "$REPO_ROOT/.artifacts/satiksme-bot" \
    "$REPO_ROOT/.artifacts/component-releases"
}

ensure_root() {
  ensure_device
  if ! pixel_transport_require_root >/dev/null 2>&1; then
    log "Root shell not available on target"
    exit 1
  fi
}

ensure_local_env() {
  if [[ ! -f "$REPO_ROOT/.env" ]]; then
    if [[ -f "$REPO_ROOT/.env.example" ]]; then
      cp "$REPO_ROOT/.env.example" "$REPO_ROOT/.env"
      log "Created .env from .env.example"
    else
      log "Missing .env and .env.example"
      exit 1
    fi
  fi

	if ! grep -q '^BOT_TOKEN=' "$REPO_ROOT/.env"; then
	  log "BOT_TOKEN is missing in .env"
	  exit 1
	fi

	if grep -q '^BOT_TOKEN=your_telegram_bot_token$' "$REPO_ROOT/.env"; then
	  log "BOT_TOKEN placeholder still set in .env"
	  exit 1
	fi
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

spacetime_schema_constant() {
  local name="${1:-}"
  python3 - "${REPO_ROOT}/internal/spacetime/schema_version.go" "${name}" <<'PY'
import re
import sys

path, name = sys.argv[1], sys.argv[2]
with open(path, "r", encoding="utf-8") as fh:
    text = fh.read()
match = re.search(rf'{re.escape(name)}\s*=\s*"([^"]+)"', text)
if not match:
    raise SystemExit(f"missing schema constant: {name}")
print(match.group(1))
PY
}

spacetime_expected_schema_module() {
  spacetime_schema_constant "ExpectedSchemaModule"
}

spacetime_expected_schema_version() {
  spacetime_schema_constant "ExpectedSchemaVersion"
}

verify_spacetime_schema_version() {
  local host="${1:-}"
  local database="${2:-}"
  local expected_module="" expected_version=""
  local body_file="" status="" url=""

  host="${host%/}"
  database="$(printf '%s' "${database}" | tr -d '\r')"
  if [[ -z "${host}" || -z "${database}" ]]; then
    echo "Spacetime schema check requires host and database" >&2
    return 1
  fi

  expected_module="$(spacetime_expected_schema_module)"
  expected_version="$(spacetime_expected_schema_version)"
  body_file="$(mktemp "${TMPDIR:-/tmp}/satiksme-schema-check.XXXXXX")"
  url="${host}/v1/database/${database}/call/satiksmebot_schema_info"

  if ! status="$(curl -sS --max-time 20 -X POST -H "Content-Type: application/json" --data '[]' -o "${body_file}" -w '%{http_code}' "${url}")"; then
    rm -f "${body_file}"
    echo "Spacetime schema check failed for ${host} database ${database}: curl request error" >&2
    return 1
  fi
  if [[ ! "${status}" =~ ^[0-9]+$ ]]; then
    rm -f "${body_file}"
    echo "Spacetime schema check failed for ${host} database ${database}: invalid HTTP status ${status}" >&2
    return 1
  fi
  if (( status < 200 || status >= 300 )); then
    echo "Spacetime schema check failed for ${host} database ${database}: HTTP ${status}" >&2
    cat "${body_file}" >&2
    rm -f "${body_file}"
    return 1
  fi

  if ! python3 - "${body_file}" "${host}" "${database}" "${expected_module}" "${expected_version}" <<'PY'
import json
import sys

path, host, database, expected_module, expected_version = sys.argv[1:6]
raw = open(path, "r", encoding="utf-8").read().strip()
try:
    payload = json.loads(raw or "null")
    if isinstance(payload, str):
        payload = json.loads(payload)
except Exception as exc:  # noqa: BLE001
    print(f"Spacetime schema check failed for {host} database {database}: decode error: {exc}", file=sys.stderr)
    print(raw, file=sys.stderr)
    raise SystemExit(1)

if not isinstance(payload, dict):
    print(f"Spacetime schema check failed for {host} database {database}: expected object payload", file=sys.stderr)
    print(json.dumps(payload, ensure_ascii=False), file=sys.stderr)
    raise SystemExit(1)

module = str(payload.get("module") or "").strip()
version = str(payload.get("schemaVersion") or "").strip()
if module != expected_module or version != expected_version:
    print(
        f"Spacetime schema check failed for {host} database {database}: "
        f"expected module={expected_module!r} schemaVersion={expected_version!r}, "
        f"got module={module!r} schemaVersion={version!r}",
        file=sys.stderr,
    )
    print(json.dumps(payload, ensure_ascii=False), file=sys.stderr)
    raise SystemExit(1)

print(json.dumps({
    "host": host,
    "database": database,
    "module": module,
    "schemaVersion": version,
}, ensure_ascii=False))
PY
  then
    rm -f "${body_file}"
    return 1
  fi

  rm -f "${body_file}"
}

reserve_local_port() {
  python3 - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

ensure_device() {
  if ! pixel_transport_require_device >/dev/null 2>&1; then
    log "Pixel transport is not ready (transport=${PIXEL_TRANSPORT})"
    exit 1
  fi
}
