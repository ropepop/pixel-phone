#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SWARM_ROOT="${REPO_ROOT}/infra/arbuzas/swarm"
SWARM_DEFAULT_ENV_FILE="${SWARM_ROOT}/env/swarm.env"
SWARM_RELEASES_ROOT="${REPO_ROOT}/output/arbuzas/swarm/releases"
REMOTE_SWARM_ROOT="/etc/arbuzas/swarm"
REMOTE_RELEASES_ROOT="${REMOTE_SWARM_ROOT}/releases"
REMOTE_CURRENT_LINK="${REMOTE_SWARM_ROOT}/current"
REMOTE_CLOUDFLARED_ROOT="${REMOTE_SWARM_ROOT}/cloudflared"
REMOTE_DATA_ROOT="/srv/arbuzas"
REMOTE_ENV_ROOT="/etc/arbuzas/env"
REMOTE_CREDENTIALS_ROOT="/etc/arbuzas/cloudflared"

if [[ -f "${SWARM_DEFAULT_ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  . "${SWARM_DEFAULT_ENV_FILE}"
  set +a
fi

ARBUZAS_HOST="${ARBUZAS_HOST:-arbuzas}"
ARBUZAS_USER="${ARBUZAS_USER:-${USER}}"
ARBUZAS_SSH_PORT="${ARBUZAS_SSH_PORT:-22}"
ARBUZAS_TZ="${ARBUZAS_TZ:-Europe/Riga}"
ARBUZAS_RELEASE_ID="${ARBUZAS_RELEASE_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
ARBUZAS_RELEASE_DIR="${ARBUZAS_RELEASE_DIR:-${SWARM_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}}"
ARBUZAS_SWARM_ADVERTISE_ADDR="${ARBUZAS_SWARM_ADVERTISE_ADDR:-${ARBUZAS_HOST}}"

ARBUZAS_TRAIN_BOT_PORT="${ARBUZAS_TRAIN_BOT_PORT:-9317}"
ARBUZAS_SATIKSME_BOT_PORT="${ARBUZAS_SATIKSME_BOT_PORT:-9318}"
ARBUZAS_SUBSCRIPTION_BOT_PORT="${ARBUZAS_SUBSCRIPTION_BOT_PORT:-9320}"
ARBUZAS_DNS_HTTPS_PORT="${ARBUZAS_DNS_HTTPS_PORT:-2789}"
ARBUZAS_DNS_DOT_PORT="${ARBUZAS_DNS_DOT_PORT:-2790}"
ARBUZAS_DNS_HOSTNAME="${ARBUZAS_DNS_HOSTNAME:-dns.jolkins.id.lv}"

ARBUZAS_TRAIN_BOT_HOSTNAME="${ARBUZAS_TRAIN_BOT_HOSTNAME:-train-bot.jolkins.id.lv}"
ARBUZAS_SATIKSME_BOT_HOSTNAME="${ARBUZAS_SATIKSME_BOT_HOSTNAME:-satiksme-bot.jolkins.id.lv}"
ARBUZAS_SUBSCRIPTION_BOT_HOSTNAME="${ARBUZAS_SUBSCRIPTION_BOT_HOSTNAME:-farel-subscription-bot.jolkins.id.lv}"
ARBUZAS_DOCKER_PLATFORM="${ARBUZAS_DOCKER_PLATFORM:-linux/amd64}"

ARBUZAS_PORTAINER_SERVER_IMAGE="${ARBUZAS_PORTAINER_SERVER_IMAGE:-portainer/portainer-ce:lts}"
ARBUZAS_PORTAINER_AGENT_IMAGE="${ARBUZAS_PORTAINER_AGENT_IMAGE:-portainer/agent:lts}"
ARBUZAS_CLOUDFLARED_IMAGE="${ARBUZAS_CLOUDFLARED_IMAGE:-cloudflare/cloudflared:latest}"

refresh_release_context() {
  ARBUZAS_RELEASE_DIR="${ARBUZAS_RELEASE_DIR:-${SWARM_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}}"
  ARBUZAS_TRAIN_BOT_IMAGE="${ARBUZAS_TRAIN_BOT_IMAGE:-arbuzas/train-bot:${ARBUZAS_RELEASE_ID}}"
  ARBUZAS_SATIKSME_BOT_IMAGE="${ARBUZAS_SATIKSME_BOT_IMAGE:-arbuzas/satiksme-bot:${ARBUZAS_RELEASE_ID}}"
  ARBUZAS_SUBSCRIPTION_BOT_IMAGE="${ARBUZAS_SUBSCRIPTION_BOT_IMAGE:-arbuzas/subscription-bot:${ARBUZAS_RELEASE_ID}}"
  ARBUZAS_SITE_NOTIFICATIONS_IMAGE="${ARBUZAS_SITE_NOTIFICATIONS_IMAGE:-arbuzas/site-notifications:${ARBUZAS_RELEASE_ID}}"
  ARBUZAS_DNS_ADGUARDHOME_IMAGE="${ARBUZAS_DNS_ADGUARDHOME_IMAGE:-arbuzas/dns-adguardhome:${ARBUZAS_RELEASE_ID}}"
  ARBUZAS_DNS_IDENTITY_WEB_IMAGE="${ARBUZAS_DNS_IDENTITY_WEB_IMAGE:-arbuzas/dns-identity-web:${ARBUZAS_RELEASE_ID}}"
  ARBUZAS_DNS_FRONTEND_IMAGE="${ARBUZAS_DNS_FRONTEND_IMAGE:-arbuzas/dns-frontend:${ARBUZAS_RELEASE_ID}}"
}

refresh_release_context

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >&2
}

require_cmd() {
  local cmd="$1"
  if ! command -v "${cmd}" >/dev/null 2>&1; then
    echo "Missing required command: ${cmd}" >&2
    exit 1
  fi
}

remote_target() {
  printf '%s@%s' "${ARBUZAS_USER}" "${ARBUZAS_HOST}"
}

ssh_opts() {
  printf '%s\n' -p "${ARBUZAS_SSH_PORT}"
}

remote_shell() {
  local script="$1"
  if [[ -n "${ARBUZAS_SUDO_PASSWORD:-}" ]]; then
    {
      printf '%s\n' "${ARBUZAS_SUDO_PASSWORD}"
      printf '%s\n' 'set -euo pipefail'
      printf '%s\n' "${script}"
    } | ssh -p "${ARBUZAS_SSH_PORT}" "$(remote_target)" "bash -lc 'IFS= read -r sudo_pass; tmp_script=\$(mktemp); cat > \"\${tmp_script}\"; trap \"rm -f \\\"\\\${tmp_script}\\\"\" EXIT; printf \"%s\\n\" \"\${sudo_pass}\" | sudo -S -p \"\" bash \"\${tmp_script}\"'"
  else
    {
      printf '%s\n' 'set -euo pipefail'
      printf '%s\n' "${script}"
    } | ssh -p "${ARBUZAS_SSH_PORT}" "$(remote_target)" 'bash -s'
  fi
}

copy_to_remote() {
  local source_path="$1"
  local remote_path="$2"
  scp -P "${ARBUZAS_SSH_PORT}" -r "${source_path}" "$(remote_target):${remote_path}"
}

ensure_local_release_layout() {
  mkdir -p "${ARBUZAS_RELEASE_DIR}/images" "${ARBUZAS_RELEASE_DIR}/stacks" "${ARBUZAS_RELEASE_DIR}/tools"
}

write_release_env() {
  cat > "${ARBUZAS_RELEASE_DIR}/release.env" <<EOF
ARBUZAS_TZ=${ARBUZAS_TZ}
ARBUZAS_TRAIN_BOT_PORT=${ARBUZAS_TRAIN_BOT_PORT}
ARBUZAS_SATIKSME_BOT_PORT=${ARBUZAS_SATIKSME_BOT_PORT}
ARBUZAS_SUBSCRIPTION_BOT_PORT=${ARBUZAS_SUBSCRIPTION_BOT_PORT}
ARBUZAS_DNS_HTTPS_PORT=${ARBUZAS_DNS_HTTPS_PORT}
ARBUZAS_DNS_DOT_PORT=${ARBUZAS_DNS_DOT_PORT}
ARBUZAS_DNS_HOSTNAME=${ARBUZAS_DNS_HOSTNAME}
ARBUZAS_PORTAINER_SERVER_IMAGE=${ARBUZAS_PORTAINER_SERVER_IMAGE}
ARBUZAS_PORTAINER_AGENT_IMAGE=${ARBUZAS_PORTAINER_AGENT_IMAGE}
ARBUZAS_CLOUDFLARED_IMAGE=${ARBUZAS_CLOUDFLARED_IMAGE}
ARBUZAS_DOCKER_PLATFORM=${ARBUZAS_DOCKER_PLATFORM}
ARBUZAS_TRAIN_BOT_IMAGE=${ARBUZAS_TRAIN_BOT_IMAGE}
ARBUZAS_SATIKSME_BOT_IMAGE=${ARBUZAS_SATIKSME_BOT_IMAGE}
ARBUZAS_SUBSCRIPTION_BOT_IMAGE=${ARBUZAS_SUBSCRIPTION_BOT_IMAGE}
ARBUZAS_SITE_NOTIFICATIONS_IMAGE=${ARBUZAS_SITE_NOTIFICATIONS_IMAGE}
ARBUZAS_DNS_ADGUARDHOME_IMAGE=${ARBUZAS_DNS_ADGUARDHOME_IMAGE}
ARBUZAS_DNS_IDENTITY_WEB_IMAGE=${ARBUZAS_DNS_IDENTITY_WEB_IMAGE}
ARBUZAS_DNS_FRONTEND_IMAGE=${ARBUZAS_DNS_FRONTEND_IMAGE}
ARBUZAS_TRAIN_BOT_HOSTNAME=${ARBUZAS_TRAIN_BOT_HOSTNAME}
ARBUZAS_SATIKSME_BOT_HOSTNAME=${ARBUZAS_SATIKSME_BOT_HOSTNAME}
ARBUZAS_SUBSCRIPTION_BOT_HOSTNAME=${ARBUZAS_SUBSCRIPTION_BOT_HOSTNAME}
EOF
}

copy_stack_assets_into_release() {
  cp "${SWARM_ROOT}/stacks/"*.yml "${ARBUZAS_RELEASE_DIR}/stacks/"
  cp "${REPO_ROOT}/tools/arbuzas/render_cloudflared_swarm_config.py" "${ARBUZAS_RELEASE_DIR}/tools/"
}

prepare_remote_release_root() {
  remote_shell "mkdir -p '${REMOTE_RELEASES_ROOT}' '${REMOTE_CLOUDFLARED_ROOT}'"
}

prepare_remote_host_layout() {
  remote_shell "
    command -v docker >/dev/null 2>&1 || { echo 'docker is required on ${ARBUZAS_HOST}' >&2; exit 1; }
    command -v python3 >/dev/null 2>&1 || { echo 'python3 is required on ${ARBUZAS_HOST}' >&2; exit 1; }
    mkdir -p \
      '${REMOTE_DATA_ROOT}/portainer' \
      '${REMOTE_DATA_ROOT}/train-bot/run' \
      '${REMOTE_DATA_ROOT}/train-bot/state' \
      '${REMOTE_DATA_ROOT}/train-bot/data/schedules' \
      '${REMOTE_DATA_ROOT}/train-bot/data/public-bundles' \
      '${REMOTE_DATA_ROOT}/satiksme-bot/run' \
      '${REMOTE_DATA_ROOT}/satiksme-bot/state' \
      '${REMOTE_DATA_ROOT}/satiksme-bot/data/catalog/source' \
      '${REMOTE_DATA_ROOT}/satiksme-bot/data/catalog/generated' \
      '${REMOTE_DATA_ROOT}/satiksme-bot/data/public-bundles' \
      '${REMOTE_DATA_ROOT}/subscription-bot/run' \
      '${REMOTE_DATA_ROOT}/subscription-bot/state' \
      '${REMOTE_DATA_ROOT}/site-notifications/state' \
      '${REMOTE_DATA_ROOT}/dns/state' \
      '${REMOTE_DATA_ROOT}/dns/runtime' \
      '${REMOTE_DATA_ROOT}/dns/logs' \
      '${REMOTE_DATA_ROOT}/dns/adguardhome/conf' \
      '${REMOTE_DATA_ROOT}/dns/adguardhome/work' \
      '${REMOTE_ENV_ROOT}' \
      '/etc/arbuzas/dns/tls' \
      '/etc/arbuzas/dns/secrets' \
      '${REMOTE_CREDENTIALS_ROOT}' \
      '${REMOTE_CLOUDFLARED_ROOT}' \
      '${REMOTE_RELEASES_ROOT}'
    touch \
      '${REMOTE_ENV_ROOT}/train-bot.env' \
      '${REMOTE_ENV_ROOT}/satiksme-bot.env' \
      '${REMOTE_ENV_ROOT}/subscription-bot.env' \
      '${REMOTE_ENV_ROOT}/site-notifications.env'
    swarm_state=\$(docker info --format '{{.Swarm.LocalNodeState}}' 2>/dev/null || true)
    if [[ \"\${swarm_state}\" != active ]]; then
      docker swarm init --advertise-addr '${ARBUZAS_SWARM_ADVERTISE_ADDR}'
    fi
  "
}

render_remote_cloudflared_configs() {
  remote_shell "
    python3 '${REMOTE_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}/tools/render_cloudflared_swarm_config.py' \
      --credentials-file '${REMOTE_CREDENTIALS_ROOT}/train-bot.json' \
      --hostname '${ARBUZAS_TRAIN_BOT_HOSTNAME}' \
      --upstream 'http://apps_train_bot:${ARBUZAS_TRAIN_BOT_PORT}' \
      --out '${REMOTE_CLOUDFLARED_ROOT}/train-bot.yml'
    python3 '${REMOTE_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}/tools/render_cloudflared_swarm_config.py' \
      --credentials-file '${REMOTE_CREDENTIALS_ROOT}/satiksme-bot.json' \
      --hostname '${ARBUZAS_SATIKSME_BOT_HOSTNAME}' \
      --upstream 'http://apps_satiksme_bot:${ARBUZAS_SATIKSME_BOT_PORT}' \
      --out '${REMOTE_CLOUDFLARED_ROOT}/satiksme-bot.yml'
    python3 '${REMOTE_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}/tools/render_cloudflared_swarm_config.py' \
      --credentials-file '${REMOTE_CREDENTIALS_ROOT}/subscription-bot.json' \
      --hostname '${ARBUZAS_SUBSCRIPTION_BOT_HOSTNAME}' \
      --upstream 'http://apps_subscription_bot:${ARBUZAS_SUBSCRIPTION_BOT_PORT}' \
      --out '${REMOTE_CLOUDFLARED_ROOT}/subscription-bot.yml'
  "
}

remote_deploy_release() {
  remote_shell "
    ln -sfn '${REMOTE_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}' '${REMOTE_CURRENT_LINK}'
    set -a
    . '${REMOTE_CURRENT_LINK}/release.env'
    set +a
    docker stack deploy --prune --resolve-image never -c '${REMOTE_CURRENT_LINK}/stacks/platform.stack.yml' platform
    docker stack deploy --prune --resolve-image never -c '${REMOTE_CURRENT_LINK}/stacks/apps.stack.yml' apps
    docker stack deploy --prune --resolve-image never -c '${REMOTE_CURRENT_LINK}/stacks/dns.stack.yml' dns
  "
}
