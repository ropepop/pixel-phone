#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
REDEPLOY_SCRIPT="${REPO_ROOT}/scripts/android/pixel_redeploy.sh"

if ! rg -Fq 'chroot /data/local/pixel-stack/chroots/adguardhome /usr/local/bin/adguardhome-start --remote-healthcheck >/dev/null 2>&1' "${REDEPLOY_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh does not verify rooted remote health in verify_live_dns_runtime()" >&2
  exit 1
fi

echo "PASS: pixel redeploy verifies rooted remote health before declaring DNS runtime converged"
