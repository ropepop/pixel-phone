#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TRAIN_START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-train-start.sh"
TRAIN_STOP_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-train-stop.sh"
TRAIN_LOOP_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/train/train-web-tunnel-service-loop.sh"
DNS_STOP_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-dns-stop.sh"
ADGUARD_START_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"
FACADE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"
HEALTH_FILE="${REPO_ROOT}/android-orchestrator/health/src/main/kotlin/lv/jolkins/pixelorchestrator/health/RuntimeHealthChecker.kt"
REMOTE_NGINX_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-remote-nginx.conf.template"

if rg -Fq 'adguardhome-start --train-web-tunnel-' "${TRAIN_START_FILE}"; then
  echo "FAIL: pixel-train-start still delegates tunnel lifecycle to adguardhome-start" >&2
  exit 1
fi

if rg -Fq 'adguardhome-start --train-web-tunnel-' "${TRAIN_STOP_FILE}"; then
  echo "FAIL: pixel-train-stop still delegates tunnel lifecycle to adguardhome-start" >&2
  exit 1
fi

if rg -Fq 'adguardhome-start --train-web-tunnel-' "${TRAIN_LOOP_FILE}"; then
  echo "FAIL: train-web-tunnel-service-loop still delegates tunnel lifecycle to adguardhome-start" >&2
  exit 1
fi

if ! rg -Fq 'TRAIN_BOT_ROOT:-/data/local/pixel-stack/apps/train-bot' "${TRAIN_LOOP_FILE}"; then
  echo "FAIL: train-web-tunnel-service-loop is not rooted at the train-bot runtime path" >&2
  exit 1
fi

if ! rg -Fq 'CLOUDFLARED_PID_FILE="${RUN_DIR}/train-bot-cloudflared.pid"' "${TRAIN_LOOP_FILE}"; then
  echo "FAIL: train-web-tunnel-service-loop does not store tunnel pid under the train-bot runtime" >&2
  exit 1
fi

if ! rg -Fq 'CLOUDFLARED_LOG_FILE="${LOG_DIR}/train-bot-cloudflared.log"' "${TRAIN_LOOP_FILE}"; then
  echo "FAIL: train-web-tunnel-service-loop does not store tunnel logs under the train-bot runtime" >&2
  exit 1
fi

if ! rg -Fq 'STATE_DIR="${TRAIN_BOT_ROOT}/state/train-web-tunnel"' "${TRAIN_LOOP_FILE}" || ! rg -Fq 'CLOUDFLARED_CONFIG_FILE="${STATE_DIR}/train-bot-cloudflared.yml"' "${TRAIN_LOOP_FILE}"; then
  echo "FAIL: train-web-tunnel-service-loop does not render tunnel config under the train-bot runtime" >&2
  exit 1
fi

if ! rg -Fq 'LEGACY_CLOUDFLARED_BIN="${LEGACY_CLOUDFLARED_BIN:-/usr/local/bin/cloudflared}"' "${TRAIN_LOOP_FILE}"; then
  echo "FAIL: train-web-tunnel-service-loop no longer supports seeding cloudflared from the legacy installed binary during cutover recovery" >&2
  exit 1
fi

if ! rg -Fq 'ROOTFS_CURL_ROOT="${ROOTFS_CURL_ROOT:-/data/local/pixel-stack/chroots/adguardhome}"' "${TRAIN_LOOP_FILE}"; then
  echo "FAIL: train-web-tunnel-service-loop no longer supports chroot curl fallback for cloudflared bootstrap" >&2
  exit 1
fi

if rg -Fq 'train-web-tunnel-start' "${ADGUARD_START_FILE}" || rg -Fq 'train-web-tunnel-stop' "${ADGUARD_START_FILE}"; then
  echo "FAIL: adguardhome-start still exposes train web tunnel control modes" >&2
  exit 1
fi

if rg -Fq '/pixel-stack/train' "${ADGUARD_START_FILE}"; then
  echo "FAIL: adguardhome-start still owns the legacy /pixel-stack/train proxy block" >&2
  exit 1
fi

if ! rg -Fq 'location = /pixel-stack/train {' "${REMOTE_NGINX_TEMPLATE}" || ! rg -Fq 'location ^~ /pixel-stack/train/' "${REMOTE_NGINX_TEMPLATE}"; then
  echo "FAIL: remote nginx template does not explicitly return 404 for the legacy /pixel-stack/train path" >&2
  exit 1
fi

if rg -Fq 'train-bot-cloudflared' "${ADGUARD_START_FILE}" || rg -Fq 'cloudflared' "${ADGUARD_START_FILE}"; then
  echo "FAIL: adguardhome-start still contains train tunnel cloudflared lifecycle logic" >&2
  exit 1
fi

if rg -Fq 'cloudflared.*train-bot.yml' "${DNS_STOP_FILE}"; then
  echo "FAIL: pixel-dns-stop still kills the train-bot tunnel process" >&2
  exit 1
fi

if ! rg -Fq "/opt/adguardhome/work/pixel-stack/cloudflared/train-bot.yml" "${TRAIN_STOP_FILE}"; then
  echo "FAIL: pixel-train-stop does not clean up the legacy AdGuard-managed train tunnel process during cutover recovery" >&2
  exit 1
fi

adguard_env_block="$(sed -n "/cat > \\/data\\/local\\/pixel-stack\\/conf\\/adguardhome\\.env <<'EOF_PIHOLE'/,/EOF_PIHOLE/p" "${FACADE_FILE}")"
if printf '%s\n' "${adguard_env_block}" | rg -Fq 'TRAIN_WEB_'; then
  echo "FAIL: OrchestratorFacade still writes TRAIN_WEB_* values into adguardhome.env" >&2
  exit 1
fi

if rg -Fq 'trainBotChroot' "${FACADE_FILE}" || rg -Fq 'cloudflared/train-bot-credentials.json' "${FACADE_FILE}"; then
  echo "FAIL: OrchestratorFacade still stages train-bot env or credentials into the AdGuard chroot" >&2
  exit 1
fi

if ! rg -Fq 'run/train-bot-cloudflared.pid' "${HEALTH_FILE}"; then
  echo "FAIL: RuntimeHealthChecker still probes the old AdGuard-rootfs tunnel pid path" >&2
  exit 1
fi

echo "PASS: train-bot tunnel ownership is decoupled from AdGuard runtime assets and health contracts"
