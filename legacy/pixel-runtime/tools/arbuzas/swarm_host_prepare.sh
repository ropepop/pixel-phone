#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./swarm_common.sh
source "${SCRIPT_DIR}/swarm_common.sh"

usage() {
  cat <<'EOF'
Usage: swarm_host_prepare.sh [--ssh-host HOST] [--ssh-user USER] [--ssh-port PORT] [--swarm-advertise-addr ADDR]
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --ssh-host) shift; ARBUZAS_HOST="${1:-}" ;;
    --ssh-user) shift; ARBUZAS_USER="${1:-}" ;;
    --ssh-port) shift; ARBUZAS_SSH_PORT="${1:-}" ;;
    --swarm-advertise-addr) shift; ARBUZAS_SWARM_ADVERTISE_ADDR="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

require_cmd ssh

log "Preparing remote Arbuzas host layout on ${ARBUZAS_HOST}"
prepare_remote_host_layout
log "Remote Arbuzas host is ready for Swarm releases"

