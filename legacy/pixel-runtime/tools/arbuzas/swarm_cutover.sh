#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./swarm_common.sh
source "${SCRIPT_DIR}/swarm_common.sh"

legacy_services=(
  arbuzas-train.service
  arbuzas-satiksme.service
  arbuzas-subscription.service
  arbuzas-notifications.service
  arbuzas-cloudflared-train.service
  arbuzas-cloudflared-satiksme.service
  arbuzas-cloudflared-subscription.service
  arbuzas-dns-adguardhome.service
  arbuzas-dns-identity-web.service
  arbuzas-dns-frontend.service
)

legacy_stopped=0
skip_build=0
skip_ship=0

usage() {
  cat <<'EOF'
Usage: swarm_cutover.sh [--release-id VALUE] [--ssh-host HOST] [--ssh-user USER] [--ssh-port PORT] [--skip-build] [--skip-ship]
EOF
}

join_by_space() {
  local first=1
  local item
  for item in "$@"; do
    if (( first )); then
      printf '%s' "${item}"
      first=0
    else
      printf ' %s' "${item}"
    fi
  done
}

restart_legacy_services() {
  local joined
  joined="$(join_by_space "${legacy_services[@]}")"
  remote_shell "systemctl start ${joined} >/dev/null 2>&1 || true"
}

stop_legacy_services() {
  local joined
  joined="$(join_by_space "${legacy_services[@]}")"
  remote_shell "systemctl stop ${joined} >/dev/null 2>&1 || true"
}

disable_legacy_services() {
  local joined
  joined="$(join_by_space "${legacy_services[@]}")"
  remote_shell "systemctl disable ${joined} >/dev/null 2>&1 || true"
}

backup_and_sync_runtime_state() {
  remote_shell "
    backup_dir='${REMOTE_DATA_ROOT}/backups/swarm-cutover-${ARBUZAS_RELEASE_ID}'
    mkdir -p \"\${backup_dir}\"
    tar -C / -czf \"\${backup_dir}/legacy-runtime.tgz\" \
      var/lib/arbuzas \
      etc/arbuzas \
      opt/arbuzas \
      opt/adguardhome \
      var/log/arbuzas 2>/dev/null || true

    mkdir -p \
      '${REMOTE_DATA_ROOT}/train-bot' \
      '${REMOTE_DATA_ROOT}/satiksme-bot' \
      '${REMOTE_DATA_ROOT}/subscription-bot' \
      '${REMOTE_DATA_ROOT}/site-notifications/state' \
      '${REMOTE_DATA_ROOT}/dns/state' \
      '${REMOTE_DATA_ROOT}/dns/logs' \
      '${REMOTE_DATA_ROOT}/dns/runtime' \
      '${REMOTE_DATA_ROOT}/dns/adguardhome/conf' \
      '${REMOTE_DATA_ROOT}/dns/adguardhome/work'

    [[ -d /var/lib/arbuzas/train-bot ]] && cp -a /var/lib/arbuzas/train-bot/. '${REMOTE_DATA_ROOT}/train-bot/'
    [[ -d /var/lib/arbuzas/satiksme-bot ]] && cp -a /var/lib/arbuzas/satiksme-bot/. '${REMOTE_DATA_ROOT}/satiksme-bot/'
    [[ -d /var/lib/arbuzas/subscription-bot ]] && cp -a /var/lib/arbuzas/subscription-bot/. '${REMOTE_DATA_ROOT}/subscription-bot/'
    [[ -d /opt/arbuzas/site-notifications/state ]] && cp -a /opt/arbuzas/site-notifications/state/. '${REMOTE_DATA_ROOT}/site-notifications/state/'
    if [[ -f /opt/arbuzas/site-notifications/.env && ! -s '${REMOTE_ENV_ROOT}/site-notifications.env' ]]; then
      cp -a /opt/arbuzas/site-notifications/.env '${REMOTE_ENV_ROOT}/site-notifications.env'
    fi

    [[ -d /var/log/arbuzas/dns ]] && cp -a /var/log/arbuzas/dns/. '${REMOTE_DATA_ROOT}/dns/logs/'
    [[ -d /opt/adguardhome/work ]] && cp -a /opt/adguardhome/work/. '${REMOTE_DATA_ROOT}/dns/adguardhome/work/'
    [[ -f /etc/arbuzas/dns/doh-identities.json ]] && cp -a /etc/arbuzas/dns/doh-identities.json '${REMOTE_DATA_ROOT}/dns/state/doh-identities.json'
    [[ -d /etc/arbuzas/dns/state ]] && cp -a /etc/arbuzas/dns/state/. '${REMOTE_DATA_ROOT}/dns/state/'

    for service_name in train-bot satiksme-bot subscription-bot; do
      config_file='/etc/arbuzas/cloudflared/'\"\${service_name}\"'.yml'
      stable_credentials='${REMOTE_CREDENTIALS_ROOT}/'\"\${service_name}\"'.json'
      if [[ -f \"\${stable_credentials}\" ]]; then
        continue
      fi
      if [[ -f \"\${config_file}\" ]]; then
        credentials_file=\$(awk -F: '/credentials-file:/ {gsub(/^[[:space:]]+/, \"\", \$2); gsub(/[[:space:]]+$/, \"\", \$2); print \$2; exit}' \"\${config_file}\")
        if [[ -n \"\${credentials_file}\" && -f \"\${credentials_file}\" ]]; then
          cp -a \"\${credentials_file}\" \"\${stable_credentials}\"
        fi
      fi
    done
  "
}

rollback_on_error() {
  local exit_code="$1"
  if (( exit_code != 0 && legacy_stopped == 1 )); then
    log "Cutover failed after legacy services were stopped; starting legacy services again"
    remote_shell "docker stack rm platform apps dns >/dev/null 2>&1 || true"
    restart_legacy_services
  fi
  exit "${exit_code}"
}

trap 'rollback_on_error $?' EXIT

while (( $# > 0 )); do
  case "$1" in
    --release-id) shift; ARBUZAS_RELEASE_ID="${1:-}"; ARBUZAS_RELEASE_DIR="${SWARM_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}" ;;
    --ssh-host) shift; ARBUZAS_HOST="${1:-}" ;;
    --ssh-user) shift; ARBUZAS_USER="${1:-}" ;;
    --ssh-port) shift; ARBUZAS_SSH_PORT="${1:-}" ;;
    --skip-build) skip_build=1 ;;
    --skip-ship) skip_ship=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

log "Preparing Arbuzas host and building release ${ARBUZAS_RELEASE_ID}"
"${SCRIPT_DIR}/swarm_host_prepare.sh" --ssh-host "${ARBUZAS_HOST}" --ssh-user "${ARBUZAS_USER}" --ssh-port "${ARBUZAS_SSH_PORT}"
if (( skip_build == 0 )); then
  "${SCRIPT_DIR}/swarm_release.sh" build --release-id "${ARBUZAS_RELEASE_ID}" --ssh-host "${ARBUZAS_HOST}" --ssh-user "${ARBUZAS_USER}" --ssh-port "${ARBUZAS_SSH_PORT}"
fi
if (( skip_ship == 0 )); then
  "${SCRIPT_DIR}/swarm_release.sh" ship --release-id "${ARBUZAS_RELEASE_ID}" --ssh-host "${ARBUZAS_HOST}" --ssh-user "${ARBUZAS_USER}" --ssh-port "${ARBUZAS_SSH_PORT}"
fi

log "Backing up current Arbuzas runtime and syncing state into the Swarm layout"
backup_and_sync_runtime_state

log "Stopping legacy Arbuzas services"
stop_legacy_services
legacy_stopped=1

log "Deploying Swarm stacks"
"${SCRIPT_DIR}/swarm_release.sh" deploy --release-id "${ARBUZAS_RELEASE_ID}" --ssh-host "${ARBUZAS_HOST}" --ssh-user "${ARBUZAS_USER}" --ssh-port "${ARBUZAS_SSH_PORT}"

log "Validating the cutover"
"${SCRIPT_DIR}/swarm_release.sh" validate --release-id "${ARBUZAS_RELEASE_ID}" --ssh-host "${ARBUZAS_HOST}" --ssh-user "${ARBUZAS_USER}" --ssh-port "${ARBUZAS_SSH_PORT}"

log "Validation passed; disabling legacy systemd auto-start"
disable_legacy_services
legacy_stopped=0

trap - EXIT
log "Arbuzas cutover to Swarm completed successfully"
