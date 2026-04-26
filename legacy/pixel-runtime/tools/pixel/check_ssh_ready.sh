#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./transport.sh
source "${SCRIPT_DIR}/transport.sh"

PIXEL_TRANSPORT="ssh"
QUIET=0

usage() {
  cat <<'USAGE'
Usage: check_ssh_ready.sh [options]

Options:
  --ssh-host IP      Tailscale or SSH host/IP
  --ssh-port PORT    SSH port (default: 2222)
  --quiet            only use the exit code
  -h, --help         show help
USAGE
}

emit() {
  if (( QUIET == 0 )); then
    printf '%s\n' "$1"
  fi
}

status_json=""
backend_state=""
tailnet_name=""
self_online="false"
self_ips=""
tailscale_ok="false"
host_set="false"
password_set="false"
tcp_reachable="false"
remote_probe_ok="false"
ready="false"
remote_probe_output=""

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    --quiet)
      QUIET=1
      ;;
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
  shift
done

status_json="$(pixel_transport_tailscale_status_json 2>/dev/null || true)"
if [[ -n "${status_json}" ]]; then
  tailscale_fields=()
  while IFS= read -r line; do
    tailscale_fields+=("${line}")
  done < <(
    printf '%s\n' "${status_json}" | python3 -c '
import json
import sys

payload = json.load(sys.stdin)
self_node = payload.get("Self") or {}
ips = self_node.get("TailscaleIPs") or []
print((payload.get("BackendState") or "").strip())
print((self_node.get("DNSName") or "").strip())
print("true" if self_node.get("Online") else "false")
print(",".join(ips))
'
  )
  backend_state="${tailscale_fields[0]:-}"
  tailnet_name="${tailscale_fields[1]:-}"
  self_online="${tailscale_fields[2]:-false}"
  self_ips="${tailscale_fields[3]:-}"
  if [[ "${backend_state}" == "Running" && "${self_online}" == "true" && -n "${self_ips}" ]]; then
    tailscale_ok="true"
  fi
fi

if [[ -n "${PIXEL_SSH_HOST:-}" ]]; then
  host_set="true"
fi
if [[ -n "${PIXEL_DEVICE_SSH_PASSWORD:-}" ]]; then
  password_set="true"
fi

if [[ "${host_set}" == "true" ]] && pixel_transport_tcp_probe "${PIXEL_SSH_HOST}" "${PIXEL_SSH_PORT}" >/dev/null 2>&1; then
  tcp_reachable="true"
fi

if [[ "${host_set}" == "true" && "${password_set}" == "true" && "${tcp_reachable}" == "true" ]]; then
  if remote_probe_output="$(pixel_transport_ssh_remote_probe 2>/dev/null)"; then
    remote_probe_ok="true"
  fi
fi

if [[ "${tailscale_ok}" == "true" && "${remote_probe_ok}" == "true" ]]; then
  if printf '%s\n' "${remote_probe_output}" | pixel_transport_validate_management_probe >/dev/null 2>&1; then
    ready="true"
  fi
fi

emit "local_tailscale_ok=${tailscale_ok}"
emit "local_tailscale_backend_state=${backend_state:-unknown}"
emit "local_tailscale_tailnet=${tailnet_name:-unknown}"
emit "local_tailscale_online=${self_online}"
emit "local_tailscale_ips=${self_ips:-unknown}"
emit "ssh_host_set=${host_set}"
emit "ssh_host=${PIXEL_SSH_HOST:-unset}"
emit "ssh_port=${PIXEL_SSH_PORT}"
emit "ssh_password_set=${password_set}"
emit "ssh_tcp_reachable=${tcp_reachable}"

if [[ -n "${remote_probe_output}" ]]; then
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    emit "${line}"
  done <<<"${remote_probe_output}"
else
  emit "remote_probe_ok=${remote_probe_ok}"
fi

emit "ready=${ready}"

if [[ "${ready}" == "true" ]]; then
  exit 0
fi

exit 1
