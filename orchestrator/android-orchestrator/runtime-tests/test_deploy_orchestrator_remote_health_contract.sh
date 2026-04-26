#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"

if ! rg -Fq 'chroot /data/local/pixel-stack/chroots/adguardhome /usr/local/bin/adguardhome-start --remote-healthcheck >/dev/null 2>&1' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh does not wait for rooted remote health when refreshing DNS runtime" >&2
  exit 1
fi

if ! rg -Fq -- '--remote-healthcheck-debug' "${DEPLOY_SCRIPT}" || ! rg -Fq 'output=\$(chroot ' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh identity endpoint summary is not sourced from remote-healthcheck-debug" >&2
  exit 1
fi

if rg -Fq 'reason=curl_missing' "${DEPLOY_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh still reports identity status via host curl_missing fallback" >&2
  exit 1
fi

echo "PASS: deploy orchestrator script uses rooted remote health checks for DNS convergence and identity status"
