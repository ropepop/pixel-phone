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
PROTECTED_LIST="${TEST_ROOT}/protected.txt"
DRY_RUN_OUTPUT="${TEST_ROOT}/dry-run.txt"
LIVE_OUTPUT="${TEST_ROOT}/live-run.txt"

mkdir -p \
  "${STACK_BASE}/conf/runtime/artifacts" \
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts" \
  "${STACK_BASE}/apps/train-bot/releases/train-current" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous" \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/run/orchestrator-action-results" \
  "${STACK_BASE}/logs/events" \
  "${STACK_BASE}/logs" \
  "${CACHE_ROOT}/runtime-artifacts" \
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
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar" \
  "${STACK_BASE}/apps/train-bot/releases/train-current/app" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous/app" \
  "${STACK_BASE}/apps/train-bot/releases/train-old/app" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/events/cleanup-old.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${STACK_BASE}/logs/adguardhome-runtime.log" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${CACHE_ROOT}/asset-stage-old" \
  "${CACHE_ROOT}/old.tmp" \
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

touch -t 202603010101 \
  "${STACK_BASE}/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar" \
  "${STACK_BASE}/conf/runtime/artifacts/old-bundle.tar" \
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar" \
  "${STACK_BASE}/apps/train-bot/releases/train-current" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous" \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/events/cleanup-old.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${STACK_BASE}/logs/adguardhome-runtime.log" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${CACHE_ROOT}/asset-stage-old" \
  "${CACHE_ROOT}/old.tmp" \
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
  --termux-home "${TERMUX_HOME}" > "${DRY_RUN_OUTPUT}"

if ! grep -Fq $'SKIP\trelease_dir\t' "${DRY_RUN_OUTPUT}"; then
  echo "FAIL: expected protected release dir skip in dry-run output" >&2
  exit 1
fi

for expected in \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/conf/runtime/artifacts/old-bundle.tar" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260225T124938Z.tar"; do
  if ! grep -Fq "${expected}" "${DRY_RUN_OUTPUT}"; then
    echo "FAIL: expected dry-run candidate missing for ${expected}" >&2
    exit 1
  fi
done

if grep -Fq "${STACK_BASE}/logs/adguardhome-runtime.log" "${DRY_RUN_OUTPUT}"; then
  echo "FAIL: fixed live log should not be targeted by cleanup" >&2
  exit 1
fi

sh "${SCRIPT_PATH}" \
  --protected-list "${PROTECTED_LIST}" \
  --stack-base "${STACK_BASE}" \
  --orchestrator-cache "${CACHE_ROOT}" \
  --termux-home "${TERMUX_HOME}" > "${LIVE_OUTPUT}"

for removed in \
  "${STACK_BASE}/apps/train-bot/releases/train-old" \
  "${STACK_BASE}/conf/runtime/artifacts/old-bundle.tar" \
  "${STACK_BASE}/run/orchestrator-action-results/old-action.json" \
  "${STACK_BASE}/logs/manual-dns-start.log" \
  "${STACK_BASE}/logs/events/cleanup-old.json" \
  "${CACHE_ROOT}/runtime-artifacts/site-notifier-bundle-old.tar" \
  "${CACHE_ROOT}/asset-stage-old" \
  "${CACHE_ROOT}/old.tmp" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/site-notifier-bundle-site-notifier-20260225T124938Z.tar" \
  "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-notifier-20260225T124938Z" \
  "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local/local-20260225Tdns-hardening" \
  "${TERMUX_HOME}/site-notifications-build-older"; do
  if [[ -e "${removed}" ]]; then
    echo "FAIL: expected cleanup to remove ${removed}" >&2
    exit 1
  fi
done

for kept in \
  "${STACK_BASE}/conf/runtime/artifacts/adguardhome-rootfs-arm64.tar" \
  "${STACK_BASE}/conf/runtime/components/site_notifier/artifacts/site-notifier-bundle.tar" \
  "${STACK_BASE}/apps/train-bot/releases/train-current" \
  "${STACK_BASE}/apps/train-bot/releases/train-previous" \
  "${STACK_BASE}/logs/adguardhome-runtime.log" \
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

echo "PASS: pixel runtime cleanup respects protected paths and removes old garbage"
