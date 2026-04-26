#!/usr/bin/env bash

if [[ -n "${PIXEL_TRANSPORT_SH_LOADED:-}" ]]; then
  return 0
fi
PIXEL_TRANSPORT_SH_LOADED=1

PIXEL_TRANSPORT_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PIXEL_TRANSPORT_REPO_ROOT="$(cd "${PIXEL_TRANSPORT_LIB_DIR}/../.." && pwd)"
PIXEL_TRANSPORT="${PIXEL_TRANSPORT:-auto}"
PIXEL_TRANSPORT_RESOLVED="${PIXEL_TRANSPORT_RESOLVED:-}"
PIXEL_TRANSPORT_PARSE_CONSUMED=0
PIXEL_TRANSPORT_FORWARD_DIR="${TMPDIR:-/tmp}/pixel-transport-forward"
PIXEL_SSH_HOST="${PIXEL_SSH_HOST:-}"
PIXEL_SSH_PORT="${PIXEL_SSH_PORT:-2222}"
PIXEL_SSH_USER="${PIXEL_SSH_USER:-root}"
PIXEL_SSH_CONNECT_TIMEOUT_SEC="${PIXEL_SSH_CONNECT_TIMEOUT_SEC:-10}"
PIXEL_SSH_KNOWN_HOSTS_FILE="${PIXEL_SSH_KNOWN_HOSTS_FILE:-${HOME}/.ssh/known_hosts}"
PIXEL_TAILSCALE_BIN="${PIXEL_TAILSCALE_BIN:-}"
ADB_BIN="${ADB_BIN:-adb}"

mkdir -p "${PIXEL_TRANSPORT_FORWARD_DIR}" "$(dirname "${PIXEL_SSH_KNOWN_HOSTS_FILE}")"

pixel_transport_usage() {
  cat <<'USAGE'
  --transport MODE          transport to use (adb|ssh|auto)
  --device SERIAL           adb serial to target
  --ssh-host IP             Tailscale or SSH host/IP
  --ssh-port PORT           SSH port (default: 2222)
USAGE
}

pixel_transport_parse_arg() {
  PIXEL_TRANSPORT_PARSE_CONSUMED=0

  case "${1:-}" in
    --transport)
      PIXEL_TRANSPORT="${2:-}"
      PIXEL_TRANSPORT_PARSE_CONSUMED=2
      return 0
      ;;
    --device)
      ADB_SERIAL="${2:-}"
      PIXEL_TRANSPORT_PARSE_CONSUMED=2
      return 0
      ;;
    --ssh-host)
      PIXEL_SSH_HOST="${2:-}"
      PIXEL_TRANSPORT_PARSE_CONSUMED=2
      return 0
      ;;
    --ssh-port)
      PIXEL_SSH_PORT="${2:-}"
      PIXEL_TRANSPORT_PARSE_CONSUMED=2
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

pixel_transport_append_cli_args() {
  local ref_name="$1"

  if [[ -n "${PIXEL_TRANSPORT:-}" ]]; then
    eval "${ref_name}+=(--transport \"\${PIXEL_TRANSPORT}\")"
  fi
  if [[ -n "${ADB_SERIAL:-}" ]]; then
    eval "${ref_name}+=(--device \"\${ADB_SERIAL}\")"
  fi
  if [[ -n "${PIXEL_SSH_HOST:-}" ]]; then
    eval "${ref_name}+=(--ssh-host \"\${PIXEL_SSH_HOST}\")"
  fi
  if [[ -n "${PIXEL_SSH_PORT:-}" ]]; then
    eval "${ref_name}+=(--ssh-port \"\${PIXEL_SSH_PORT}\")"
  fi
}

pixel_transport_selected() {
  pixel_transport_require_device >/dev/null
  printf '%s\n' "${PIXEL_TRANSPORT_RESOLVED}"
}

pixel_transport_require_cmd() {
  local cmd="$1"
  command -v "${cmd}" >/dev/null 2>&1 || {
    echo "Missing required command: ${cmd}" >&2
    return 1
  }
}

pixel_transport_single_quote() {
  printf "'%s'" "${1//\'/\'\"\'\"\'}"
}

pixel_transport_shell_join() {
  local joined=""
  local part=""
  for part in "$@"; do
    joined="${joined} $(pixel_transport_single_quote "${part}")"
  done
  printf '%s\n' "${joined# }"
}

pixel_transport_double_quote() {
  local value="${1//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//\$/\\$}"
  value="${value//\`/\\\`}"
  printf '"%s"' "${value}"
}

pixel_transport_exec_join() {
  local joined=""
  local part=""
  for part in "$@"; do
    joined="${joined} $(pixel_transport_double_quote "${part}")"
  done
  printf '%s\n' "${joined# }"
}

pixel_transport_resolve_adb() {
  pixel_transport_require_cmd "${ADB_BIN}" || return 1

  if [[ -n "${ADB_SERIAL:-}" ]]; then
    "${ADB_BIN}" -s "${ADB_SERIAL}" get-state >/dev/null 2>&1 || {
      echo "Device ${ADB_SERIAL} is not reachable via adb" >&2
      return 1
    }
    return 0
  fi

  local devices=()
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && devices+=("${line}")
  done < <("${ADB_BIN}" devices | awk 'NR>1 && $2=="device" {print $1}')

  if (( ${#devices[@]} == 1 )); then
    ADB_SERIAL="${devices[0]}"
    return 0
  fi

  if (( ${#devices[@]} == 0 )); then
    echo "No adb devices available" >&2
  else
    echo "Multiple adb devices found; pass --device" >&2
  fi
  "${ADB_BIN}" devices -l >&2 || true
  return 1
}

pixel_transport_resolve_tailscale_bin() {
  if [[ -n "${PIXEL_TAILSCALE_BIN:-}" && -x "${PIXEL_TAILSCALE_BIN}" ]]; then
    printf '%s\n' "${PIXEL_TAILSCALE_BIN}"
    return 0
  fi

  local candidate=""
  for candidate in \
    "$(command -v tailscale 2>/dev/null || true)" \
    "/Applications/Tailscale.app/Contents/MacOS/Tailscale" \
    "${HOME}/Applications/Tailscale.app/Contents/MacOS/Tailscale"
  do
    [[ -n "${candidate}" && -x "${candidate}" ]] || continue
    PIXEL_TAILSCALE_BIN="${candidate}"
    printf '%s\n' "${PIXEL_TAILSCALE_BIN}"
    return 0
  done

  echo "tailscale CLI is not available in PATH or the macOS app bundle" >&2
  return 1
}

pixel_transport_tailscale_status_json() {
  local tailscale_bin=""
  tailscale_bin="$(pixel_transport_resolve_tailscale_bin)" || return 1
  "${tailscale_bin}" status --json
}

pixel_transport_check_tailscale() {
  local status_json=""
  status_json="$(pixel_transport_tailscale_status_json 2>/dev/null)" || {
    echo "tailscale CLI is installed but not ready" >&2
    return 1
  }

  printf '%s\n' "${status_json}" | python3 -c '
import json
import sys

payload = json.load(sys.stdin)
backend = (payload.get("BackendState") or "").strip()
self_node = payload.get("Self") or {}
online = self_node.get("Online")
ips = self_node.get("TailscaleIPs") or []

if backend != "Running":
    raise SystemExit(1)
if online is False:
    raise SystemExit(1)
if not ips:
    raise SystemExit(1)
' || return 1
}

pixel_transport_require_ssh_client() {
  pixel_transport_require_cmd expect || return 1
  pixel_transport_require_cmd ssh || return 1
  pixel_transport_require_cmd scp || return 1
  [[ -n "${PIXEL_SSH_HOST:-}" ]] || {
    echo "PIXEL_SSH_HOST is required for SSH transport" >&2
    return 1
  }
  [[ -n "${PIXEL_DEVICE_SSH_PASSWORD:-}" ]] || {
    echo "PIXEL_DEVICE_SSH_PASSWORD is required for SSH transport" >&2
    return 1
  }
}

pixel_transport_tcp_probe() {
  local host="$1"
  local port="$2"

  python3 - "${host}" "${port}" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.settimeout(5)
try:
    sock.connect((host, port))
except OSError:
    raise SystemExit(1)
finally:
    sock.close()
PY
}

pixel_transport_build_ssh_args() {
  local ref_name="$1"
  eval "${ref_name}=()"
  eval "${ref_name}+=(-o LogLevel=ERROR)"
  eval "${ref_name}+=(-o StrictHostKeyChecking=accept-new)"
  eval "${ref_name}+=(-o UserKnownHostsFile=\"\${PIXEL_SSH_KNOWN_HOSTS_FILE}\")"
  eval "${ref_name}+=(-o PreferredAuthentications=password,keyboard-interactive)"
  eval "${ref_name}+=(-o PubkeyAuthentication=no)"
  eval "${ref_name}+=(-o KbdInteractiveAuthentication=yes)"
  eval "${ref_name}+=(-o NumberOfPasswordPrompts=1)"
  eval "${ref_name}+=(-o ConnectTimeout=\"\${PIXEL_SSH_CONNECT_TIMEOUT_SEC}\")"
}

pixel_transport_expect_run() {
  local program="$1"
  shift

EXPECT_PROGRAM="${program}" PIXEL_DEVICE_SSH_PASSWORD="${PIXEL_DEVICE_SSH_PASSWORD:-}" expect -f /dev/stdin -- "$@" <<'EOF'
set timeout -1
if {![info exists env(PIXEL_DEVICE_SSH_PASSWORD)] || $env(PIXEL_DEVICE_SSH_PASSWORD) eq ""} {
  puts stderr "PIXEL_DEVICE_SSH_PASSWORD is not set"
  exit 96
}
set password $env(PIXEL_DEVICE_SSH_PASSWORD)
set program $env(EXPECT_PROGRAM)
log_user 0
spawn -noecho $program {*}$argv
set output ""
expect {
  -re "(?i)are you sure you want to continue connecting.*" {
    send -- "yes\r"
    exp_continue
  }
  -re "(?i)(password|passphrase).*:" {
    send -- "$password\r"
    exp_continue
  }
  -re ".+" {
    append output $expect_out(buffer)
    exp_continue
  }
  eof {
    append output $expect_out(buffer)
    puts -nonewline $output
    catch wait result
    set rc [lindex $result 3]
    exit $rc
  }
}
EOF
}

pixel_transport_ssh_remote_shell() {
  pixel_transport_require_ssh_client || return 1

  local command="$1"
  local -a ssh_args=()
  local remote_command=""
  pixel_transport_build_ssh_args ssh_args
  remote_command="$(pixel_transport_shell_join /system/bin/sh -c "${command}")"
  pixel_transport_expect_run ssh "${ssh_args[@]}" -p "${PIXEL_SSH_PORT}" "${PIXEL_SSH_USER}@${PIXEL_SSH_HOST}" "${remote_command}"
}

pixel_transport_ssh_remote_probe() {
  pixel_transport_require_ssh_client || return 1

  local probe_script='
set -eu
PIXEL_MANAGEMENT_HEALTH_REPORT=1 /system/bin/sh /data/local/pixel-stack/bin/pixel-management-health.sh --report
'
  pixel_transport_ssh_remote_shell "${probe_script}"
}

pixel_transport_validate_management_probe() {
  python3 -c '
import sys

data = {}
for line in sys.stdin:
    line = line.strip()
    if not line or "=" not in line:
        continue
    key, value = line.split("=", 1)
    data[key] = value

required = {
    "remote_uid": "0",
    "management_enabled": "1",
    "management_healthy": "1",
    "vpn_enabled": "1",
    "vpn_health": "1",
    "tailscaled_sock": "1",
    "guard_chain_ipv4": "1",
    "guard_chain_ipv6": "1",
}
for key, value in required.items():
    if data.get(key) != value:
        raise SystemExit(1)

required_non_empty = (
    "tailnet_ipv4",
    "pm_path",
    "am_path",
    "logcat_path",
)
for key in required_non_empty:
    if not data.get(key):
        raise SystemExit(1)
'
}

pixel_transport_host_ssh_ready() {
  pixel_transport_check_tailscale || return 1
  pixel_transport_require_ssh_client || return 1
  pixel_transport_tcp_probe "${PIXEL_SSH_HOST}" "${PIXEL_SSH_PORT}" || return 1

  local probe_output=""
  probe_output="$(pixel_transport_ssh_remote_probe 2>/dev/null)" || return 1
  printf '%s\n' "${probe_output}" | pixel_transport_validate_management_probe >/dev/null || return 1
}

pixel_transport_require_device() {
  case "${PIXEL_TRANSPORT}" in
    adb|ssh)
      if [[ -z "${PIXEL_TRANSPORT_RESOLVED}" ]]; then
        PIXEL_TRANSPORT_RESOLVED="${PIXEL_TRANSPORT}"
      fi
      ;;
    auto)
      if [[ -z "${PIXEL_TRANSPORT_RESOLVED}" ]]; then
        if pixel_transport_host_ssh_ready >/dev/null 2>&1; then
          PIXEL_TRANSPORT_RESOLVED="ssh"
        elif pixel_transport_resolve_adb >/dev/null 2>&1; then
          PIXEL_TRANSPORT_RESOLVED="adb"
        else
          echo "Neither SSH/Tailscale nor adb transport is ready. Set PIXEL_SSH_HOST and PIXEL_DEVICE_SSH_PASSWORD, or connect the device over adb." >&2
          return 1
        fi
      fi
      ;;
    *)
      echo "Unsupported PIXEL_TRANSPORT: ${PIXEL_TRANSPORT}" >&2
      return 1
      ;;
  esac

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      pixel_transport_resolve_adb
      ;;
    ssh)
      pixel_transport_require_ssh_client
      ;;
    *)
      echo "Unsupported resolved transport: ${PIXEL_TRANSPORT_RESOLVED}" >&2
      return 1
      ;;
  esac
}

pixel_transport_require_root() {
  local uid=""
  uid="$(pixel_transport_root_shell "id -u" | tr -d '\r' | tr -d '[:space:]')" || return 1
  [[ "${uid}" == "0" ]] || {
    echo "Root shell not available on target" >&2
    return 1
  }
}

pixel_transport_shell() {
  local command="$1"
  pixel_transport_require_device || return 1

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      printf '%s\n' "${command}" | "${ADB_BIN}" -s "${ADB_SERIAL}" shell "/system/bin/sh -s"
      ;;
    ssh)
      pixel_transport_ssh_remote_shell "${command}"
      ;;
  esac
}

pixel_transport_root_shell() {
  local command="$1"
  pixel_transport_require_device || return 1

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      printf '%s\n' "${command}" | "${ADB_BIN}" -s "${ADB_SERIAL}" shell "su -c '/system/bin/sh -s'"
      ;;
    ssh)
      pixel_transport_ssh_remote_shell "${command}"
      ;;
  esac
}

pixel_transport_root_exec() {
  local command=""
  command="$(pixel_transport_exec_join "$@")"
  pixel_transport_root_shell "${command}"
}

pixel_transport_root_shell_stdin() {
  pixel_transport_require_device || return 1

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      "${ADB_BIN}" -s "${ADB_SERIAL}" shell "su -c '/system/bin/sh -s'" < /dev/stdin
      ;;
    ssh)
      local local_script="" remote_script="" rc=0
      local_script="$(mktemp "${TMPDIR:-/tmp}/pixel-transport-script.XXXXXX")"
      remote_script="/data/local/tmp/pixel-transport-script-$$-${RANDOM}.sh"
      cat > "${local_script}"
      pixel_transport_push "${local_script}" "${remote_script}" >/dev/null
      set +e
      pixel_transport_root_exec chmod 700 "${remote_script}" >/dev/null 2>&1
      pixel_transport_root_exec /system/bin/sh "${remote_script}"
      rc=$?
      pixel_transport_root_exec rm -f "${remote_script}" >/dev/null 2>&1 || true
      rm -f "${local_script}"
      set -e
      return "${rc}"
      ;;
  esac
}

pixel_transport_push() {
  local local_path="$1"
  local remote_path="$2"

  [[ -e "${local_path}" ]] || {
    echo "Local path not found: ${local_path}" >&2
    return 1
  }

  pixel_transport_require_device || return 1

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      local stage_path=""
      local remote_parent=""
      local stage_path_quoted=""
      local remote_path_quoted=""
      stage_path="/data/local/tmp/pixel-transport-push-$$-${RANDOM}-$(basename "${remote_path}")"
      remote_parent="$(dirname "${remote_path}")"
      stage_path_quoted="$(pixel_transport_single_quote "${stage_path}")"
      remote_path_quoted="$(pixel_transport_single_quote "${remote_path}")"
      "${ADB_BIN}" -s "${ADB_SERIAL}" shell "mkdir -p /data/local/tmp" < /dev/null >/dev/null
      "${ADB_BIN}" -s "${ADB_SERIAL}" push "${local_path}" "${stage_path}" < /dev/null >/dev/null
      pixel_transport_root_exec mkdir -p "${remote_parent}" >/dev/null
      pixel_transport_root_shell "
        set -e
        stage_path=${stage_path_quoted}
        remote_path=${remote_path_quoted}
        if mv -f \"\${stage_path}\" \"\${remote_path}\" 2>/dev/null; then
          exit 0
        fi
        cp -a \"\${stage_path}\" \"\${remote_path}\"
        rm -rf \"\${stage_path}\"
      "
      "${ADB_BIN}" -s "${ADB_SERIAL}" shell "rm -rf $(pixel_transport_single_quote "${stage_path}")" < /dev/null >/dev/null 2>&1 || true
      ;;
    ssh)
      local -a scp_args=()
      local remote_parent=""
      pixel_transport_build_ssh_args scp_args
      remote_parent="$(dirname "${remote_path}")"
      pixel_transport_root_exec mkdir -p "${remote_parent}" >/dev/null
      pixel_transport_expect_run scp "${scp_args[@]}" -P "${PIXEL_SSH_PORT}" "${local_path}" "${PIXEL_SSH_USER}@${PIXEL_SSH_HOST}:${remote_path}"
      ;;
  esac
}

pixel_transport_pull() {
  local remote_path="$1"
  local local_path="$2"

  pixel_transport_require_device || return 1

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      local stage_path=""
      stage_path="/data/local/tmp/pixel-transport-pull-$$-${RANDOM}-$(basename "${remote_path}")"
      pixel_transport_root_exec cp -a "${remote_path}" "${stage_path}"
      pixel_transport_root_exec chmod 0644 "${stage_path}" >/dev/null 2>&1 || true
      "${ADB_BIN}" -s "${ADB_SERIAL}" pull "${stage_path}" "${local_path}" < /dev/null >/dev/null
      pixel_transport_root_exec rm -rf "${stage_path}" >/dev/null 2>&1 || true
      ;;
    ssh)
      local -a scp_args=()
      pixel_transport_build_ssh_args scp_args
      pixel_transport_expect_run scp "${scp_args[@]}" -P "${PIXEL_SSH_PORT}" "${PIXEL_SSH_USER}@${PIXEL_SSH_HOST}:${remote_path}" "${local_path}"
      ;;
  esac
}

pixel_transport_install_apk() {
  local apk_path="$1"
  pixel_transport_require_device || return 1

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      "${ADB_BIN}" -s "${ADB_SERIAL}" install -r "${apk_path}" < /dev/null
      ;;
    ssh)
      local remote_apk="/data/local/tmp/$(basename "${apk_path}")"
      pixel_transport_push "${apk_path}" "${remote_apk}" >/dev/null
      pixel_transport_root_exec pm install -r "${remote_apk}"
      pixel_transport_root_exec rm -f "${remote_apk}" >/dev/null 2>&1 || true
      ;;
  esac
}

pixel_transport_remote_file_exists() {
  local remote_path="$1"
  pixel_transport_root_exec test -f "${remote_path}" >/dev/null 2>&1
}

pixel_transport_remote_sha256_file() {
  local remote_path="$1"
  local raw=""
  local quoted_path=""
  quoted_path="$(pixel_transport_single_quote "${remote_path}")"
  raw="$(
    pixel_transport_root_shell "
      f=${quoted_path}
      if [ ! -f \${f} ]; then
        printf 'MISSING\n'
        exit 0
      fi
      if command -v sha256sum >/dev/null 2>&1; then
        sha256sum \${f}
      elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 \${f}
      elif command -v toybox >/dev/null 2>&1; then
        toybox sha256sum \${f}
      else
        printf 'UNKNOWN\n'
      fi
    " 2>/dev/null | tr -d '\r' | sed -n '1p'
  )"
  printf '%s\n' "${raw%% *}"
}

pixel_transport_remote_cat() {
  local remote_path="$1"
  pixel_transport_root_exec cat "${remote_path}" | tr -d '\r'
}

pixel_transport_package_installed() {
  local package_name="$1"
  local output=""
  output="$(pixel_transport_root_exec pm path "${package_name}" 2>/dev/null | tr -d '\r')" || return 1
  grep -q '^package:' <<<"${output}"
}

pixel_transport_forward_start() {
  local local_port="$1"
  local remote_port="$2"
  local remote_host="${3:-127.0.0.1}"

  pixel_transport_require_device || return 1

  case "${PIXEL_TRANSPORT_RESOLVED}" in
    adb)
      "${ADB_BIN}" -s "${ADB_SERIAL}" forward --remove "tcp:${local_port}" >/dev/null 2>&1 || true
      "${ADB_BIN}" -s "${ADB_SERIAL}" forward "tcp:${local_port}" "tcp:${remote_port}" >/dev/null
      ;;
    ssh)
      local socket_file="${PIXEL_TRANSPORT_FORWARD_DIR}/ssh-forward-${local_port}.sock"
      local -a ssh_args=()
      pixel_transport_forward_stop "${local_port}" >/dev/null 2>&1 || true
      pixel_transport_build_ssh_args ssh_args
      rm -f "${socket_file}"
      pixel_transport_expect_run ssh "${ssh_args[@]}" -p "${PIXEL_SSH_PORT}" -M -S "${socket_file}" -o ControlPersist=yes -f -N -L "${local_port}:${remote_host}:${remote_port}" "${PIXEL_SSH_USER}@${PIXEL_SSH_HOST}"
      ;;
  esac
}

pixel_transport_forward_stop() {
  local local_port="$1"

  case "${PIXEL_TRANSPORT_RESOLVED:-${PIXEL_TRANSPORT}}" in
    adb|auto)
      if [[ -n "${ADB_SERIAL:-}" ]] && command -v "${ADB_BIN}" >/dev/null 2>&1; then
        "${ADB_BIN}" -s "${ADB_SERIAL}" forward --remove "tcp:${local_port}" >/dev/null 2>&1 || true
      fi
      ;;
    ssh)
      local socket_file="${PIXEL_TRANSPORT_FORWARD_DIR}/ssh-forward-${local_port}.sock"
      if [[ -S "${socket_file}" ]]; then
        ssh -S "${socket_file}" -O exit -p "${PIXEL_SSH_PORT}" "${PIXEL_SSH_USER}@${PIXEL_SSH_HOST}" >/dev/null 2>&1 || true
        rm -f "${socket_file}" >/dev/null 2>&1 || true
      fi
      ;;
  esac
}

pixel_transport_forward_cleanup() {
  local socket_file=""
  while IFS= read -r socket_file; do
    [[ -n "${socket_file}" ]] || continue
    ssh -S "${socket_file}" -O exit -p "${PIXEL_SSH_PORT}" "${PIXEL_SSH_USER}@${PIXEL_SSH_HOST}" >/dev/null 2>&1 || true
    rm -f "${socket_file}" >/dev/null 2>&1 || true
  done < <(find "${PIXEL_TRANSPORT_FORWARD_DIR}" -maxdepth 1 -type s -name 'ssh-forward-*.sock' 2>/dev/null | sort)
}

pixel_transport_adb_compat() {
  local subcommand="${1:-}"
  shift || true

  case "${subcommand}" in
    devices)
      local -a cmd=("${ADB_BIN}" devices)
      if [[ "${1:-}" == "-l" ]]; then
        cmd+=(-l)
      fi
      "${cmd[@]}"
      ;;
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
          echo "Unsupported adb forward invocation: ${subcommand} $*" >&2
          return 1
          ;;
      esac
      ;;
    shell)
      if (( $# == 1 )); then
        local shell_command="$1"
        if [[ "${shell_command}" == su\ -c\ * ]]; then
          shell_command="${shell_command#su -c }"
          if [[ "${shell_command}" == \'*\' ]]; then
            shell_command="${shell_command:1:${#shell_command}-2}"
          elif [[ "${shell_command}" == \"*\" ]]; then
            shell_command="${shell_command:1:${#shell_command}-2}"
          fi
        fi
        pixel_transport_root_shell "${shell_command}"
        return 0
      fi
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
