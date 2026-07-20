#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="${REPO_ROOT}/app/src/main/assets/runtime/entrypoints/pixel-runtime-cleanup.sh"

if [[ ! -f "${SCRIPT_PATH}" ]]; then
  echo "FAIL: cleanup script missing at ${SCRIPT_PATH}" >&2
  exit 1
fi

TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

STACK_BASE="${TEST_ROOT}/stack"
CACHE_ROOT="${TEST_ROOT}/cache"
TERMUX_HOME="${TEST_ROOT}/termux-home"
LOCAL_TMP="${TEST_ROOT}/local-tmp"
SUPERUSER_DB="${TEST_ROOT}/superuser/databases/sulogs.db"
PROTECTED_LIST="${TEST_ROOT}/protected.txt"
DRY_RUN_OUTPUT="${TEST_ROOT}/dry-run.txt"
LIVE_OUTPUT="${TEST_ROOT}/live-run.txt"

mkdir -p \
  "${STACK_BASE}/conf/runtime/artifacts" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256" \
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts" \
  "${STACK_BASE}/apps/train-bot/releases/train-current" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous" \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/run/orchestrator-action-results" \
  "${STACK_BASE}/logs/events" \
  "${STACK_BASE}/logs" \
  "${STACK_BASE}/vpn/logs" \
  "${STACK_BASE}/ssh/logs" \
  "${STACK_BASE}/apps/site-notifications/logs" \
  "${STACK_BASE}/apps/ticket-screen/logs" \
  "${STACK_BASE}/chroots/adguardhome/opt/adguardhome/work/data" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome" \
  "${STACK_BASE}/state/adguardhome/work/data" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole-rooted" \
  "${CACHE_ROOT}/support-bundles" \
  "${CACHE_ROOT}/runtime-artifacts" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-old" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-young" \
  "${LOCAL_TMP}/ticket-capture-old" \
  "$(dirname "${SUPERUSER_DB}")" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260301T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260225T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260301Tdns-hardening" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260225Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build" \
  "${TERMUX_HOME}/site-notifications-build-site-notifier-20260309T135009Z" \
  "${TERMUX_HOME}/site-notifications-build-older"

touch \
  "${STACK_BASE}/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/old-bundle.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar" \
  "${STACK_BASE}/apps/train-bot/releases/train-current/app" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous/app" \
  "${STACK_BASE}/apps/train-bot/releases/train-old/app" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/events/cleanup-old.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${STACK_BASE}/logs/adguardhome-runtime.log" \
  "${STACK_BASE}/logs/adguardhome-service-loop.log" \
  "${STACK_BASE}/vpn/logs/tailscaled.log" \
  "${STACK_BASE}/ssh/logs/dropbear.log" \
  "${STACK_BASE}/apps/site-notifications/logs/daemon.log" \
  "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log" \
  "${STACK_BASE}/chroots/adguardhome/opt/adguardhome/work/data/querylog.json" \
  "${STACK_BASE}/state/adguardhome/work/data/querylog.json.1" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome/remote-nginx-doh-access.log" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome/adguardhome-start-attempt-20260301T010101-1.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole/pihole.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole-rooted/nginx-doh-access.log" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${CACHE_ROOT}/asset-stage-old" \
  "${CACHE_ROOT}/old.tmp" \
  "${CACHE_ROOT}/support-bundles/pixel-stack-support-1.zip" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-old/drop" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-young/keep" \
  "${LOCAL_TMP}/ticket-capture-old/drop" \
  "${LOCAL_TMP}/adguardhome-rootfs-old.tar" \
  "${LOCAL_TMP}/pixel-orchestrator-debug.apk" \
  "${LOCAL_TMP}/ticket-poll-old.h264" \
  "${LOCAL_TMP}/unknown-old.bin" \
  "${SUPERUSER_DB}" \
  "${SUPERUSER_DB}-wal" \
  "${SUPERUSER_DB}-shm" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260308T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260301T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260225T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124938Z/keep" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260301T124938Z/drop" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260225T124938Z/drop" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening/keep" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260301Tdns-hardening/drop" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260225Tdns-hardening/drop" \
  "${TERMUX_HOME}/site-notifications-build/keep" \
  "${TERMUX_HOME}/site-notifications-build-site-notifier-20260309T135009Z/keep" \
  "${TERMUX_HOME}/site-notifications-build-older/drop"

printf 'old tailscaled log\n' > "${STACK_BASE}/vpn/logs/tailscaled.log"
printf 'old dropbear log\n' > "${STACK_BASE}/ssh/logs/dropbear.log"
printf 'old notifier log\n' > "${STACK_BASE}/apps/site-notifications/logs/daemon.log"
printf 'old ticket tunnel log\n' > "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log"
printf 'old superuser db\n' > "${SUPERUSER_DB}"
printf 'old superuser wal\n' > "${SUPERUSER_DB}-wal"
printf 'old superuser shm\n' > "${SUPERUSER_DB}-shm"

touch -t 202603010101 \
  "${STACK_BASE}/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/old-bundle.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar" \
  "${STACK_BASE}/apps/train-bot/releases/train-current" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous" \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/events/cleanup-old.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${STACK_BASE}/logs/adguardhome-runtime.log" \
  "${STACK_BASE}/logs/adguardhome-service-loop.log" \
  "${STACK_BASE}/vpn/logs/tailscaled.log" \
  "${STACK_BASE}/ssh/logs/dropbear.log" \
  "${STACK_BASE}/apps/site-notifications/logs/daemon.log" \
  "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log" \
  "${STACK_BASE}/chroots/adguardhome/opt/adguardhome/work/data/querylog.json" \
  "${STACK_BASE}/state/adguardhome/work/data/querylog.json.1" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome/remote-nginx-doh-access.log" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome/adguardhome-start-attempt-20260301T010101-1.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole/pihole.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole-rooted/nginx-doh-access.log" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${CACHE_ROOT}/asset-stage-old" \
  "${CACHE_ROOT}/old.tmp" \
  "${CACHE_ROOT}/support-bundles/pixel-stack-support-1.zip" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-old" \
  "${LOCAL_TMP}/ticket-capture-old" \
  "${LOCAL_TMP}/adguardhome-rootfs-old.tar" \
  "${LOCAL_TMP}/pixel-orchestrator-debug.apk" \
  "${LOCAL_TMP}/ticket-poll-old.h264" \
  "${LOCAL_TMP}/unknown-old.bin" \
  "${SUPERUSER_DB}" \
  "${SUPERUSER_DB}-wal" \
  "${SUPERUSER_DB}-shm" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260308T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260301T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260225T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260301T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260225T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260301Tdns-hardening" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260225Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build" \
  "${TERMUX_HOME}/site-notifications-build-site-notifier-20260309T135009Z" \
  "${TERMUX_HOME}/site-notifications-build-older"

touch -t 202603081249 \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260308T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build-site-notifier-20260309T135009Z"

touch -t 202603011249 \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260301T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260301T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260301Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build"

touch -t 202602251249 \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260225T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260225T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260225Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build-older"

cat > "${PROTECTED_LIST}" <<EOF_PROTECTED
${STACK_BASE}/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar
${STACK_BASE}/conf/runtime/artifacts/sha256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
${STACK_BASE}/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar
${STACK_BASE}/apps/train-bot/releases/train-current
${STACK_BASE}/apps/train-bot/releases/train-previous
${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260308T124938Z.tar
${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124938Z
${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening
${TERMUX_HOME}/site-notifications-build
EOF_PROTECTED

sh "${SCRIPT_PATH}" \
  --dry-run \
  --protected-list "${PROTECTED_LIST}" \
  --stack-base "${STACK_BASE}" \
  --orchestrator-cache "${CACHE_ROOT}" \
  --termux-home "${TERMUX_HOME}" \
  --local-tmp "${LOCAL_TMP}" \
  --superuser-log-db "${SUPERUSER_DB}" \
  --root-recheck-command true \
  --superuser-log-max-bytes 8 \
  --known-log-max-bytes 8 \
  --stack-log-max-bytes 16 \
  --retired-dns \
  --artifact-age-days 30 \
  --log-age-days 30 > "${DRY_RUN_OUTPUT}"

if ! grep -Fq $'SKIP\trelease_dir\t' "${DRY_RUN_OUTPUT}"; then
  echo "FAIL: expected protected release dir skip in dry-run output" >&2
  exit 1
fi

for expected in \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/conf/runtime/artifacts/old-bundle.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${STACK_BASE}/vpn/logs/tailscaled.log" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${CACHE_ROOT}/support-bundles/pixel-stack-support-1.zip" \
  "${STACK_BASE}/chroots/adguardhome/opt/adguardhome/work/data/querylog.json" \
  "${STACK_BASE}/state/adguardhome/work/data/querylog.json.1" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome/remote-nginx-doh-access.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole/pihole.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole-rooted/nginx-doh-access.log" \
  "${STACK_BASE}/logs/adguardhome-runtime.log" \
  "${STACK_BASE}/logs/adguardhome-service-loop.log" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-old" \
  "${LOCAL_TMP}/adguardhome-rootfs-old.tar" \
  "${LOCAL_TMP}/pixel-orchestrator-debug.apk" \
  "${LOCAL_TMP}/ticket-poll-old.h264" \
  "${SUPERUSER_DB}" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260225T124938Z.tar"; do
  if ! grep -Fq "${expected}" "${DRY_RUN_OUTPUT}"; then
    echo "FAIL: expected dry-run candidate missing for ${expected}" >&2
    exit 1
  fi
done

if grep -Fq "${LOCAL_TMP}/unknown-old.bin" "${DRY_RUN_OUTPUT}"; then
  echo "FAIL: unknown old tmp file should not be targeted by cleanup" >&2
  exit 1
fi
if grep -Fq "${LOCAL_TMP}/pixel-orchestrator-runtime-young" "${DRY_RUN_OUTPUT}"; then
  echo "FAIL: young tmp artifact should not be targeted by cleanup" >&2
  exit 1
fi

sh "${SCRIPT_PATH}" \
  --protected-list "${PROTECTED_LIST}" \
  --stack-base "${STACK_BASE}" \
  --orchestrator-cache "${CACHE_ROOT}" \
  --termux-home "${TERMUX_HOME}" \
  --local-tmp "${LOCAL_TMP}" \
  --superuser-log-db "${SUPERUSER_DB}" \
  --root-recheck-command true \
  --superuser-log-max-bytes 8 \
  --known-log-max-bytes 8 \
  --stack-log-max-bytes 16 \
  --retired-dns \
  --artifact-age-days 30 \
  --log-age-days 30 > "${LIVE_OUTPUT}"

for removed in \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/conf/runtime/artifacts/old-bundle.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${STACK_BASE}/logs/adguardhome-runtime.log" \
  "${STACK_BASE}/logs/adguardhome-service-loop.log" \
  "${STACK_BASE}/logs/events/cleanup-old.json" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${CACHE_ROOT}/asset-stage-old" \
  "${CACHE_ROOT}/old.tmp" \
  "${CACHE_ROOT}/support-bundles/pixel-stack-support-1.zip" \
  "${STACK_BASE}/chroots/adguardhome/opt/adguardhome/work/data/querylog.json" \
  "${STACK_BASE}/state/adguardhome/work/data/querylog.json.1" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome/remote-nginx-doh-access.log" \
  "${STACK_BASE}/chroots/adguardhome/var/log/adguardhome/adguardhome-start-attempt-20260301T010101-1.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole/pihole.log" \
  "${STACK_BASE}/chroots/pihole/var/log/pihole-rooted/nginx-doh-access.log" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-old" \
  "${LOCAL_TMP}/ticket-capture-old" \
  "${LOCAL_TMP}/adguardhome-rootfs-old.tar" \
  "${LOCAL_TMP}/pixel-orchestrator-debug.apk" \
  "${LOCAL_TMP}/ticket-poll-old.h264" \
  "${SUPERUSER_DB}" \
  "${SUPERUSER_DB}-wal" \
  "${SUPERUSER_DB}-shm" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260225T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260225T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260225Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build-older"; do
  if [[ -e "${removed}" ]]; then
    echo "FAIL: expected cleanup to remove ${removed}" >&2
    exit 1
  fi
done

allowlisted_log_total=0
for bounded_log in \
  "${STACK_BASE}/vpn/logs/tailscaled.log" \
  "${STACK_BASE}/ssh/logs/dropbear.log" \
  "${STACK_BASE}/apps/site-notifications/logs/daemon.log" \
  "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log"; do
  for bounded_path in "${bounded_log}" "${bounded_log}.1"; do
    [[ -e "${bounded_path}" ]] || continue
    bounded_bytes="$(wc -c < "${bounded_path}" | tr -d '[:space:]')"
    if (( bounded_bytes > 8 )); then
      echo "FAIL: allowlisted log or rotation exceeds its per-file ceiling: ${bounded_path}" >&2
      exit 1
    fi
    allowlisted_log_total=$((allowlisted_log_total + bounded_bytes))
  done
done
if (( allowlisted_log_total > 16 )); then
  echo "FAIL: allowlisted log total exceeds the configured ceiling" >&2
  exit 1
fi

for kept in \
  "${STACK_BASE}/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/sha256/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar" \
  "${STACK_BASE}/apps/train-bot/releases/train-current" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous" \
  "${STACK_BASE}/vpn/logs/tailscaled.log" \
  "${STACK_BASE}/ssh/logs/dropbear.log" \
  "${STACK_BASE}/apps/site-notifications/logs/daemon.log" \
  "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log" \
  "${LOCAL_TMP}/unknown-old.bin" \
  "${LOCAL_TMP}/pixel-orchestrator-runtime-young" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260308T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260301T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260308T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260301T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260308Tdns-hardening" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260301Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build" \
  "${TERMUX_HOME}/site-notifications-build-site-notifier-20260309T135009Z"; do
  if [[ ! -e "${kept}" ]]; then
    echo "FAIL: expected cleanup to preserve ${kept}" >&2
    exit 1
  fi
done

for truncated in \
  "${STACK_BASE}/vpn/logs/tailscaled.log" \
  "${STACK_BASE}/ssh/logs/dropbear.log" \
  "${STACK_BASE}/apps/site-notifications/logs/daemon.log" \
  "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log"; do
  if [[ "$(wc -c < "${truncated}" | tr -d '[:space:]')" != "0" ]]; then
    echo "FAIL: expected runtime log to be truncated: ${truncated}" >&2
    exit 1
  fi
done

ROOT_RECHECK_COUNTER="${TEST_ROOT}/root-recheck-count"
ROOT_RECHECK_SCRIPT="${TEST_ROOT}/root-recheck.sh"
printf '0\n' > "${ROOT_RECHECK_COUNTER}"
cat > "${ROOT_RECHECK_SCRIPT}" <<EOF_ROOT_RECHECK
#!/bin/sh
count=\$(cat "${ROOT_RECHECK_COUNTER}")
count=\$((count + 1))
printf '%s\n' "\${count}" > "${ROOT_RECHECK_COUNTER}"
[ "\${count}" -eq 1 ]
EOF_ROOT_RECHECK
chmod +x "${ROOT_RECHECK_SCRIPT}"
printf 'oversize superuser database\n' > "${SUPERUSER_DB}"
printf 'oversize wal\n' > "${SUPERUSER_DB}-wal"

ROLLBACK_OUTPUT="${TEST_ROOT}/root-rollback.txt"
sh "${SCRIPT_PATH}" \
  --protected-list "${PROTECTED_LIST}" \
  --stack-base "${STACK_BASE}" \
  --orchestrator-cache "${CACHE_ROOT}" \
  --termux-home "${TERMUX_HOME}" \
  --local-tmp "${LOCAL_TMP}" \
  --superuser-log-db "${SUPERUSER_DB}" \
  --root-recheck-command "${ROOT_RECHECK_SCRIPT}" \
  --superuser-log-max-bytes 8 \
  --known-log-max-bytes 8 \
  --artifact-age-days 30 \
  --log-age-days 30 > "${ROLLBACK_OUTPUT}"

if ! grep -Fq 'root_recheck_failed_rolled_back' "${ROLLBACK_OUTPUT}"; then
  echo "FAIL: expected failed root recheck to report a rollback" >&2
  exit 1
fi
if [[ ! -f "${SUPERUSER_DB}" || ! -f "${SUPERUSER_DB}-wal" ]]; then
  echo "FAIL: failed root recheck did not restore the superuser database" >&2
  exit 1
fi
if compgen -G "${SUPERUSER_DB}.pixel-cleanup-backup*" >/dev/null; then
  echo "FAIL: superuser rollback left temporary backup files" >&2
  exit 1
fi

FAKE_BIN="${TEST_ROOT}/fake-bin"
MV_COUNTER="${TEST_ROOT}/mv-count"
mkdir -p "${FAKE_BIN}"
printf '0\n' > "${MV_COUNTER}"
cat > "${FAKE_BIN}/mv" <<EOF_FAKE_MV
#!/bin/sh
count=\$(cat "${MV_COUNTER}")
count=\$((count + 1))
printf '%s\n' "\${count}" > "${MV_COUNTER}"
if [ "\${count}" -eq 2 ]; then
  exit 1
fi
exec /bin/mv "\$@"
EOF_FAKE_MV
chmod +x "${FAKE_BIN}/mv"
printf 'oversize interruption db\n' > "${SUPERUSER_DB}"
printf 'oversize interruption wal\n' > "${SUPERUSER_DB}-wal"
printf 'oversize interruption shm\n' > "${SUPERUSER_DB}-shm"

INTERRUPTION_OUTPUT="${TEST_ROOT}/root-interruption.txt"
PATH="${FAKE_BIN}:${PATH}" sh "${SCRIPT_PATH}" \
  --superuser-only \
  --protected-list "${PROTECTED_LIST}" \
  --superuser-log-db "${SUPERUSER_DB}" \
  --root-recheck-command true \
  --superuser-log-max-bytes 8 > "${INTERRUPTION_OUTPUT}"

if ! grep -Fq 'rotation_prepare_failed_rolled_back' "${INTERRUPTION_OUTPUT}"; then
  echo "FAIL: expected interrupted superuser rotation to report rollback" >&2
  exit 1
fi
for restored in "${SUPERUSER_DB}" "${SUPERUSER_DB}-wal" "${SUPERUSER_DB}-shm"; do
  if [[ ! -f "${restored}" ]]; then
    echo "FAIL: interrupted rotation did not restore ${restored}" >&2
    exit 1
  fi
done
if compgen -G "${SUPERUSER_DB}.pixel-cleanup-backup*" >/dev/null; then
  echo "FAIL: interrupted rotation left backup files" >&2
  exit 1
fi

echo "PASS: pixel runtime cleanup respects protected paths and removes old garbage"
