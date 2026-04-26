#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./swarm_common.sh
source "${SCRIPT_DIR}/swarm_common.sh"

action=""

usage() {
  cat <<'EOF'
Usage: swarm_release.sh ACTION [options]

Actions:
  build      Build local images and write a release bundle
  ship       Copy a prepared release bundle to Arbuzas and load images there
  deploy     Render remote configs and deploy the release bundle
  validate   Check Swarm services plus public health endpoints
  rollback   Redeploy a previously shipped release
  full       Build, ship, deploy, and validate in one run

Options:
  --release-id VALUE
  --ssh-host HOST
  --ssh-user USER
  --ssh-port PORT
  --swarm-env-file PATH
EOF
}

save_image_tar() {
  local image="$1"
  local file_name="$2"
  docker save -o "${ARBUZAS_RELEASE_DIR}/images/${file_name}" "${image}"
}

save_image_tar_or_mark_remote_pull() {
  local image="$1"
  local file_name="$2"
  local pull_list_file="${ARBUZAS_RELEASE_DIR}/remote-pull-images.txt"
  if docker save -o "${ARBUZAS_RELEASE_DIR}/images/${file_name}" "${image}"; then
    return 0
  fi

  rm -f "${ARBUZAS_RELEASE_DIR}/images/${file_name}"
  log "Falling back to remote pull for ${image}; local export is not available on this host"
  printf '%s\n' "${image}" >> "${pull_list_file}"
}

build_release() {
  require_cmd docker
  require_cmd python3
  ensure_local_release_layout
  copy_stack_assets_into_release
  write_release_env
  : > "${ARBUZAS_RELEASE_DIR}/remote-pull-images.txt"
  local build_cmd=(docker buildx build --load --provenance=false --platform "${ARBUZAS_DOCKER_PLATFORM}")

  log "Building Arbuzas custom images for release ${ARBUZAS_RELEASE_ID} on ${ARBUZAS_DOCKER_PLATFORM}"
  "${build_cmd[@]}" -f "${SWARM_ROOT}/images/train-bot.Dockerfile" -t "${ARBUZAS_TRAIN_BOT_IMAGE}" "${REPO_ROOT}"
  "${build_cmd[@]}" -f "${SWARM_ROOT}/images/satiksme-bot.Dockerfile" -t "${ARBUZAS_SATIKSME_BOT_IMAGE}" "${REPO_ROOT}"
  "${build_cmd[@]}" -f "${SWARM_ROOT}/images/subscription-bot.Dockerfile" -t "${ARBUZAS_SUBSCRIPTION_BOT_IMAGE}" "${REPO_ROOT}"
  "${build_cmd[@]}" -f "${SWARM_ROOT}/images/site-notifications.Dockerfile" -t "${ARBUZAS_SITE_NOTIFICATIONS_IMAGE}" "${REPO_ROOT}"
  "${build_cmd[@]}" -f "${SWARM_ROOT}/images/dns-adguardhome.Dockerfile" -t "${ARBUZAS_DNS_ADGUARDHOME_IMAGE}" "${REPO_ROOT}"
  "${build_cmd[@]}" -f "${SWARM_ROOT}/images/dns-identity-web.Dockerfile" -t "${ARBUZAS_DNS_IDENTITY_WEB_IMAGE}" "${REPO_ROOT}"
  "${build_cmd[@]}" -f "${SWARM_ROOT}/images/dns-frontend.Dockerfile" -t "${ARBUZAS_DNS_FRONTEND_IMAGE}" "${REPO_ROOT}"

  log "Pulling third-party runtime images"
  docker pull --platform "${ARBUZAS_DOCKER_PLATFORM}" "${ARBUZAS_PORTAINER_SERVER_IMAGE}"
  docker pull --platform "${ARBUZAS_DOCKER_PLATFORM}" "${ARBUZAS_PORTAINER_AGENT_IMAGE}"
  docker pull --platform "${ARBUZAS_DOCKER_PLATFORM}" "${ARBUZAS_CLOUDFLARED_IMAGE}"

  log "Saving image tarballs into ${ARBUZAS_RELEASE_DIR}/images"
  save_image_tar "${ARBUZAS_TRAIN_BOT_IMAGE}" train-bot.tar
  save_image_tar "${ARBUZAS_SATIKSME_BOT_IMAGE}" satiksme-bot.tar
  save_image_tar "${ARBUZAS_SUBSCRIPTION_BOT_IMAGE}" subscription-bot.tar
  save_image_tar "${ARBUZAS_SITE_NOTIFICATIONS_IMAGE}" site-notifications.tar
  save_image_tar "${ARBUZAS_DNS_ADGUARDHOME_IMAGE}" dns-adguardhome.tar
  save_image_tar "${ARBUZAS_DNS_IDENTITY_WEB_IMAGE}" dns-identity-web.tar
  save_image_tar "${ARBUZAS_DNS_FRONTEND_IMAGE}" dns-frontend.tar
  save_image_tar_or_mark_remote_pull "${ARBUZAS_PORTAINER_SERVER_IMAGE}" portainer-server.tar
  save_image_tar_or_mark_remote_pull "${ARBUZAS_PORTAINER_AGENT_IMAGE}" portainer-agent.tar
  save_image_tar_or_mark_remote_pull "${ARBUZAS_CLOUDFLARED_IMAGE}" cloudflared.tar

  log "Release bundle prepared at ${ARBUZAS_RELEASE_DIR}"
}

ship_release() {
  require_cmd ssh
  require_cmd scp

  if [[ ! -f "${ARBUZAS_RELEASE_DIR}/release.env" ]]; then
    echo "Release bundle is missing: ${ARBUZAS_RELEASE_DIR}" >&2
    exit 1
  fi

  prepare_remote_host_layout
  prepare_remote_release_root

  local remote_tmp="/tmp/arbuzas-swarm-release-${ARBUZAS_RELEASE_ID}"
  log "Copying ${ARBUZAS_RELEASE_DIR} to ${ARBUZAS_HOST}:${remote_tmp}"
  remote_shell "
    rm -rf '${remote_tmp}'
    install -d -m 0755 -o '${ARBUZAS_USER}' -g '${ARBUZAS_USER}' '${remote_tmp}'
  "
  copy_to_remote "${ARBUZAS_RELEASE_DIR}/." "${remote_tmp}/"

  log "Loading images on ${ARBUZAS_HOST}"
  remote_shell "
    mkdir -p '${REMOTE_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}'
    shopt -s nullglob
    for image_tar in '${remote_tmp}'/images/*.tar; do
      docker image load -i \"\${image_tar}\" >/dev/null
    done
    if [[ -s '${remote_tmp}/remote-pull-images.txt' ]]; then
      while IFS= read -r image_name; do
        [[ -n \"\${image_name}\" ]] || continue
        docker pull --platform '${ARBUZAS_DOCKER_PLATFORM}' \"\${image_name}\" >/dev/null
      done < '${remote_tmp}/remote-pull-images.txt'
    fi
    cp -R '${remote_tmp}/.' '${REMOTE_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}/'
    rm -rf '${remote_tmp}'
  "
}

deploy_release() {
  render_remote_cloudflared_configs
  remote_deploy_release
  log "Release ${ARBUZAS_RELEASE_ID} deployed to ${ARBUZAS_HOST}"
}

validate_release() {
  require_cmd curl
  local service_check_script
  service_check_script="$(cat <<'EOF'
expected_services=(
  platform_portainer
  platform_agent
  apps_train_bot
  apps_satiksme_bot
  apps_subscription_bot
  apps_site_notifications
  apps_train_tunnel
  apps_satiksme_tunnel
  apps_subscription_tunnel
  dns_adguardhome
  dns_identity_web
  dns_frontend
)

deadline=$((SECONDS + 180))
while (( SECONDS < deadline )); do
  pending=0
  for service_name in "${expected_services[@]}"; do
    replicas="$(docker service ls --format '{{.Name}} {{.Replicas}}' | awk -v name="${service_name}" '$1 == name { print $2; exit }')"
    if [[ -z "${replicas}" ]]; then
      pending=1
      continue
    fi
    IFS='/' read -r running desired <<< "${replicas}"
    if [[ "${running}" != "${desired}" ]]; then
      pending=1
    fi
  done
  if (( pending == 0 )); then
    exit 0
  fi
  sleep 5
done

docker service ls >&2
echo "timed out waiting for Swarm services to become healthy" >&2
exit 1
EOF
)"

  log "Checking Swarm service replica state on ${ARBUZAS_HOST}"
  remote_shell "${service_check_script}"

  log "Checking Portainer HTTPS endpoint on ${ARBUZAS_HOST}"
  remote_shell "curl -skf https://127.0.0.1:9443 >/dev/null"

  log "Checking public Train health"
  curl -fsS "https://${ARBUZAS_TRAIN_BOT_HOSTNAME}/api/v1/health" >/dev/null

  log "Checking public Satiksme health"
  curl -fsS "https://${ARBUZAS_SATIKSME_BOT_HOSTNAME}/api/v1/health" >/dev/null

  log "Checking public Subscription health"
  curl -fsS "https://${ARBUZAS_SUBSCRIPTION_BOT_HOSTNAME}/pixel-stack/subscription/api/v1/health" >/dev/null

  log "Checking Site Notifications daemon health from inside the task"
  remote_shell "
    container_id=\$(docker ps --filter 'label=com.docker.swarm.service.name=apps_site_notifications' --format '{{.ID}}' | head -n 1)
    [[ -n \"\${container_id}\" ]] || { echo 'site notifications container not found' >&2; exit 1; }
    docker exec \"\${container_id}\" python /opt/site-notifications/app.py healthcheck >/dev/null
  "

  log "Checking remote DNS contract"
  "${REPO_ROOT}/orchestrator/scripts/ops/service-availability-report.sh" \
    --host "${ARBUZAS_HOST}" \
    --fqdn "${ARBUZAS_DNS_HOSTNAME}" \
    --https-port "${ARBUZAS_DNS_HTTPS_PORT}" \
    --dot-port "${ARBUZAS_DNS_DOT_PORT}" \
    --require-remote \
    --skip-root-checks >/dev/null

  log "Release ${ARBUZAS_RELEASE_ID} passed validation"
}

rollback_release() {
  if [[ -z "${ARBUZAS_RELEASE_ID}" ]]; then
    echo "--release-id is required for rollback" >&2
    exit 2
  fi
  render_remote_cloudflared_configs
  remote_deploy_release
  log "Rolled back Arbuzas to release ${ARBUZAS_RELEASE_ID}"
}

while (( $# > 0 )); do
  case "$1" in
    build|ship|deploy|validate|rollback|full)
      if [[ -n "${action}" ]]; then
        echo "Only one action is allowed" >&2
        exit 2
      fi
      action="$1"
      ;;
    --release-id)
      shift
      ARBUZAS_RELEASE_ID="${1:-}"
      ARBUZAS_RELEASE_DIR="${SWARM_RELEASES_ROOT}/${ARBUZAS_RELEASE_ID}"
      ARBUZAS_TRAIN_BOT_IMAGE="arbuzas/train-bot:${ARBUZAS_RELEASE_ID}"
      ARBUZAS_SATIKSME_BOT_IMAGE="arbuzas/satiksme-bot:${ARBUZAS_RELEASE_ID}"
      ARBUZAS_SUBSCRIPTION_BOT_IMAGE="arbuzas/subscription-bot:${ARBUZAS_RELEASE_ID}"
      ARBUZAS_SITE_NOTIFICATIONS_IMAGE="arbuzas/site-notifications:${ARBUZAS_RELEASE_ID}"
      ARBUZAS_DNS_ADGUARDHOME_IMAGE="arbuzas/dns-adguardhome:${ARBUZAS_RELEASE_ID}"
      ARBUZAS_DNS_IDENTITY_WEB_IMAGE="arbuzas/dns-identity-web:${ARBUZAS_RELEASE_ID}"
      ARBUZAS_DNS_FRONTEND_IMAGE="arbuzas/dns-frontend:${ARBUZAS_RELEASE_ID}"
      ;;
    --ssh-host) shift; ARBUZAS_HOST="${1:-}" ;;
    --ssh-user) shift; ARBUZAS_USER="${1:-}" ;;
    --ssh-port) shift; ARBUZAS_SSH_PORT="${1:-}" ;;
    --swarm-env-file) shift; if [[ -f "${1:-}" ]]; then set -a; . "${1}"; set +a; fi ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ -z "${action}" ]]; then
  usage >&2
  exit 2
fi

case "${action}" in
  build)
    build_release
    ;;
  ship)
    ship_release
    ;;
  deploy)
    deploy_release
    ;;
  validate)
    validate_release
    ;;
  rollback)
    rollback_release
    ;;
  full)
    build_release
    ship_release
    deploy_release
    validate_release
    ;;
esac
