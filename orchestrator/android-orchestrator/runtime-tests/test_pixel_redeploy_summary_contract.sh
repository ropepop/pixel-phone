#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
IMPL_SCRIPT="${REPO_ROOT}/scripts/android/pixel_redeploy.sh"

if ! rg -Fq 'SUMMARY_PREFLIGHT_ROOTED_FRESHNESS' "${IMPL_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing preflight rooted freshness summary export" >&2
  exit 1
fi

if ! rg -Fq 'SUMMARY_FINAL_ROOTED_FRESHNESS' "${IMPL_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing final rooted freshness summary export" >&2
  exit 1
fi

if ! rg -Fq '"preflight": {' "${IMPL_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing preflight summary block" >&2
  exit 1
fi

if ! rg -Fq '"finalState": {' "${IMPL_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing finalState summary block" >&2
  exit 1
fi

if ! rg -Fq '"rootedFreshness": os.environ.get("SUMMARY_FINAL_ROOTED_FRESHNESS")' "${IMPL_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing final rooted freshness summary field" >&2
  exit 1
fi

if ! rg -Fq 'SUMMARY_STATUS' "${IMPL_SCRIPT}" || ! rg -Fq '"status": os.environ["SUMMARY_STATUS"]' "${IMPL_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing overall status summary contract" >&2
  exit 1
fi

for required in '"componentResults": {' 'SUMMARY_TRAIN_BOT_RESULT_SOURCE' 'SUMMARY_SITE_NOTIFIER_RESULT_SOURCE' '"liveReleasePath":' '"recoveryCommand":'; do
  if ! rg -Fq -- "${required}" "${IMPL_SCRIPT}"; then
    echo "FAIL: pixel_redeploy.sh missing ${required} summary contract" >&2
    exit 1
  fi
done

for required in 'SUMMARY_ROOTFS_SOURCE' 'SUMMARY_REPAIR_ACTION' 'SUMMARY_DNS_RELEASE_NEEDED' '"rootfsSource":' '"repairAction":' '"dnsReleaseNeeded":'; do
  if ! rg -Fq -- "${required}" "${IMPL_SCRIPT}"; then
    echo "FAIL: pixel_redeploy.sh missing ${required} summary contract" >&2
    exit 1
  fi
done

echo "PASS: pixel redeploy summary contract is present"
