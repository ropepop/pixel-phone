#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
HELPER_SCRIPT="${WORKSPACE_ROOT}/tools/pixel/artifact_retention.sh"
CLEANUP_SCRIPT="${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh"
PACKAGE_COMPONENT_SCRIPT="${REPO_ROOT}/scripts/android/package_component_release.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

if [[ ! -f "${HELPER_SCRIPT}" ]]; then
  echo "FAIL: missing artifact retention helper ${HELPER_SCRIPT}" >&2
  exit 1
fi

if [[ ! -f "${CLEANUP_SCRIPT}" ]]; then
  echo "FAIL: missing workspace cleanup script ${CLEANUP_SCRIPT}" >&2
  exit 1
fi

for script_path in \
  "${WORKSPACE_ROOT}/workloads/train-bot/scripts/pixel/common.sh" \
  "${WORKSPACE_ROOT}/workloads/satiksme-bot/scripts/pixel/common.sh" \
  "${WORKSPACE_ROOT}/workloads/site-notifications/scripts/pixel/common.sh" \
  "${WORKSPACE_ROOT}/workloads/subscription-bot/scripts/pixel/common.sh" \
  "${REPO_ROOT}/scripts/android/pixel_redeploy.sh" \
  "${REPO_ROOT}/scripts/android/package_component_release.sh" \
  "${REPO_ROOT}/scripts/android/package_dns_component_release.sh" \
  "${REPO_ROOT}/scripts/android/package_runtime_bundle.sh"; do
  if ! rg -Fq 'cleanup_workspace.sh' "${script_path}"; then
    echo "FAIL: ${script_path} is missing the centralized workspace cleanup hook" >&2
    exit 1
  fi
done

set_path_mtime() {
  local epoch="$1"
  shift
  python3 - "${epoch}" "$@" <<'PY'
import os
import sys

timestamp = float(sys.argv[1])
for path in sys.argv[2:]:
    os.utime(path, (timestamp, timestamp), follow_symlinks=False)
PY
}

old_epoch="$(python3 - <<'PY'
import time
print(time.time() - ((72 * 3600) + 300))
PY
)"
recent_epoch="$(python3 - <<'PY'
import time
print(time.time() - 60)
PY
)"

mkdir -p \
  "${TMP_ROOT}/tools/pixel" \
  "${TMP_ROOT}/orchestrator/scripts/android" \
  "${TMP_ROOT}/.codex-tmp/stale-session/logs" \
  "${TMP_ROOT}/.codex-tmp/recent-session" \
  "${TMP_ROOT}/output/pixel/stale-run/artifacts" \
  "${TMP_ROOT}/output/browser-use" \
  "${TMP_ROOT}/output/agent-browser/session-debug" \
  "${TMP_ROOT}/.artifacts/component-releases/recent-parent" \
  "${TMP_ROOT}/.artifacts/component-releases/mixed-parent" \
  "${TMP_ROOT}/workloads/train-bot/output/playwright/debug-live" \
  "${TMP_ROOT}/workloads/train-bot/output/agent-browser/pixel-miniapp-smoke" \
  "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old/bundle/bin" \
  "${TMP_ROOT}/workloads/site-notifications/output/pixel" \
  "${TMP_ROOT}/ops/evidence/orchestrator" \
  "${TMP_ROOT}/state/browser-use/tgweb-session"

printf 'stale bundle\n' > "${TMP_ROOT}/output/pixel/stale-run/artifacts/old.tar"
printf 'recent screenshot\n' > "${TMP_ROOT}/output/browser-use/recent.png"
printf 'stale agent browser\n' > "${TMP_ROOT}/output/agent-browser/session-debug/old.png"
printf 'stale codex scratch\n' > "${TMP_ROOT}/.codex-tmp/stale-session/logs/old.txt"
printf 'recent codex scratch\n' > "${TMP_ROOT}/.codex-tmp/recent-session/keep.txt"
printf 'stale child\n' > "${TMP_ROOT}/.artifacts/component-releases/recent-parent/stale.txt"
printf 'recent child\n' > "${TMP_ROOT}/.artifacts/component-releases/mixed-parent/recent.txt"
printf 'stale playwright\n' > "${TMP_ROOT}/workloads/train-bot/output/playwright/debug-live/old.txt"
printf 'stale agent-browser\n' > "${TMP_ROOT}/workloads/train-bot/output/agent-browser/pixel-miniapp-smoke/old.txt"
printf 'stale subscription build\n' > "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old/bundle/bin/subscription-bot"
printf '{"cookie":"keep"}\n' > "${TMP_ROOT}/state/browser-use/tgweb-session/cookies.json"
printf '{"localStorage":{}}\n' > "${TMP_ROOT}/state/browser-use/tgweb-session/storage.json"
printf 'keep evidence\n' > "${TMP_ROOT}/ops/evidence/orchestrator/keep.txt"

cp "${HELPER_SCRIPT}" "${TMP_ROOT}/tools/pixel/artifact_retention.sh"
cp "${CLEANUP_SCRIPT}" "${TMP_ROOT}/tools/pixel/cleanup_workspace.sh"
cp "${PACKAGE_COMPONENT_SCRIPT}" "${TMP_ROOT}/orchestrator/scripts/android/package_component_release.sh"
chmod +x \
  "${TMP_ROOT}/tools/pixel/cleanup_workspace.sh" \
  "${TMP_ROOT}/orchestrator/scripts/android/package_component_release.sh"

git -C "${TMP_ROOT}" init -q
git -C "${TMP_ROOT}" config user.email cleanup-test@example.com
git -C "${TMP_ROOT}" config user.name cleanup-test
printf 'tracked violation\n' > "${TMP_ROOT}/output/pixel/tracked-violation.txt"
git -C "${TMP_ROOT}" add output/pixel/tracked-violation.txt

set_path_mtime "${old_epoch}" \
  "${TMP_ROOT}/.codex-tmp/stale-session/logs/old.txt" \
  "${TMP_ROOT}/.codex-tmp/stale-session/logs" \
  "${TMP_ROOT}/.codex-tmp/stale-session" \
  "${TMP_ROOT}/output/pixel/stale-run/artifacts/old.tar" \
  "${TMP_ROOT}/output/pixel/stale-run/artifacts" \
  "${TMP_ROOT}/output/pixel/stale-run" \
  "${TMP_ROOT}/output/agent-browser/session-debug/old.png" \
  "${TMP_ROOT}/output/agent-browser/session-debug" \
  "${TMP_ROOT}/.artifacts/component-releases/recent-parent/stale.txt" \
  "${TMP_ROOT}/workloads/train-bot/output/playwright/debug-live/old.txt" \
  "${TMP_ROOT}/workloads/train-bot/output/playwright/debug-live" \
  "${TMP_ROOT}/workloads/train-bot/output/agent-browser/pixel-miniapp-smoke/old.txt" \
  "${TMP_ROOT}/workloads/train-bot/output/agent-browser/pixel-miniapp-smoke" \
  "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old/bundle/bin/subscription-bot" \
  "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old/bundle/bin" \
  "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old/bundle" \
  "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old" \
  "${TMP_ROOT}/state/browser-use/tgweb-session/cookies.json" \
  "${TMP_ROOT}/state/browser-use/tgweb-session/storage.json" \
  "${TMP_ROOT}/state/browser-use/tgweb-session" \
  "${TMP_ROOT}/ops/evidence/orchestrator/keep.txt"
set_path_mtime "${recent_epoch}" \
  "${TMP_ROOT}/.codex-tmp/recent-session/keep.txt" \
  "${TMP_ROOT}/.codex-tmp/recent-session" \
  "${TMP_ROOT}/output/browser-use/recent.png" \
  "${TMP_ROOT}/.artifacts/component-releases/recent-parent" \
  "${TMP_ROOT}/.artifacts/component-releases/mixed-parent/recent.txt"
set_path_mtime "${old_epoch}" "${TMP_ROOT}/.artifacts/component-releases/mixed-parent"

set +e
bash "${TMP_ROOT}/tools/pixel/cleanup_workspace.sh" --check > "${TMP_ROOT}/check-output.txt" 2>&1
check_rc=$?
set -e

if [[ "${check_rc}" != "1" ]]; then
  echo "FAIL: cleanup --check should fail when tracked violations or stale output exist" >&2
  exit 1
fi

if ! grep -Fq "${TMP_ROOT}/output/pixel/tracked-violation.txt" "${TMP_ROOT}/check-output.txt"; then
  echo "FAIL: cleanup --check did not report the tracked violation" >&2
  exit 1
fi

if ! grep -Fq "${TMP_ROOT}/workloads/train-bot/output/playwright/debug-live/old.txt" "${TMP_ROOT}/check-output.txt"; then
  echo "FAIL: cleanup --check did not report the train-bot playwright garbage root" >&2
  exit 1
fi

if ! grep -Fq "${TMP_ROOT}/output/agent-browser/session-debug/old.png" "${TMP_ROOT}/check-output.txt"; then
  echo "FAIL: cleanup --check did not report the root agent-browser garbage root" >&2
  exit 1
fi

if ! grep -Fq "${TMP_ROOT}/.codex-tmp/stale-session/logs/old.txt" "${TMP_ROOT}/check-output.txt"; then
  echo "FAIL: cleanup --check did not report the Codex scratch garbage root" >&2
  exit 1
fi

if grep -Fq "${TMP_ROOT}/state/browser-use/tgweb-session/storage.json" "${TMP_ROOT}/check-output.txt"; then
  echo "FAIL: cleanup --check should not target protected browser session storage" >&2
  exit 1
fi

if grep -Fq "${TMP_ROOT}/ops/evidence/orchestrator/keep.txt" "${TMP_ROOT}/check-output.txt"; then
  echo "FAIL: cleanup --check should not target protected evidence storage" >&2
  exit 1
fi

git -C "${TMP_ROOT}" rm --cached -q output/pixel/tracked-violation.txt

bash "${TMP_ROOT}/tools/pixel/cleanup_workspace.sh" --dry-run > "${TMP_ROOT}/dry-run-output.txt"

for expected in \
  "${TMP_ROOT}/.codex-tmp/stale-session/logs/old.txt" \
  "${TMP_ROOT}/output/pixel/stale-run/artifacts/old.tar" \
  "${TMP_ROOT}/output/agent-browser/session-debug/old.png" \
  "${TMP_ROOT}/workloads/train-bot/output/playwright/debug-live/old.txt" \
  "${TMP_ROOT}/workloads/train-bot/output/agent-browser/pixel-miniapp-smoke/old.txt" \
  "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old/bundle/bin/subscription-bot"; do
  if ! grep -Fq "${expected}" "${TMP_ROOT}/dry-run-output.txt"; then
    echo "FAIL: expected dry-run candidate missing for ${expected}" >&2
    exit 1
  fi
done

bash "${TMP_ROOT}/tools/pixel/cleanup_workspace.sh" > "${TMP_ROOT}/live-cleanup-output.txt"

for removed in \
  "${TMP_ROOT}/.codex-tmp/stale-session" \
  "${TMP_ROOT}/output/pixel/stale-run" \
  "${TMP_ROOT}/output/agent-browser/session-debug" \
  "${TMP_ROOT}/.artifacts/component-releases/recent-parent/stale.txt" \
  "${TMP_ROOT}/workloads/train-bot/output/playwright/debug-live/old.txt" \
  "${TMP_ROOT}/workloads/train-bot/output/agent-browser/pixel-miniapp-smoke/old.txt" \
  "${TMP_ROOT}/workloads/subscription-bot/output/pixel/subscription-bot-build-old"; do
  if [[ -e "${removed}" ]]; then
    echo "FAIL: cleanup did not remove ${removed}" >&2
    exit 1
  fi
done

for kept in \
  "${TMP_ROOT}/.codex-tmp/recent-session/keep.txt" \
  "${TMP_ROOT}/output/browser-use/recent.png" \
  "${TMP_ROOT}/.artifacts/component-releases/recent-parent" \
  "${TMP_ROOT}/.artifacts/component-releases/mixed-parent/recent.txt" \
  "${TMP_ROOT}/state/browser-use/tgweb-session/storage.json" \
  "${TMP_ROOT}/state/browser-use/tgweb-session/cookies.json" \
  "${TMP_ROOT}/ops/evidence/orchestrator/keep.txt"; do
  if [[ ! -e "${kept}" ]]; then
    echo "FAIL: cleanup should have preserved ${kept}" >&2
    exit 1
  fi
done

bash "${TMP_ROOT}/tools/pixel/cleanup_workspace.sh" --check >/dev/null 2>&1

package_root="${TMP_ROOT}/orchestrator/.artifacts/component-releases"
old_release="${package_root}/train_bot-old-release"
recent_release="${package_root}/train_bot-recent-release"
new_release="${package_root}/train_bot-test-release"
mkdir -p "${old_release}/artifacts" "${recent_release}/artifacts"
printf 'old artifact\n' > "${old_release}/artifacts/old.tar"
printf 'recent artifact\n' > "${recent_release}/artifacts/recent.tar"
set_path_mtime "${old_epoch}" "${old_release}/artifacts/old.tar" "${old_release}/artifacts" "${old_release}"
set_path_mtime "${recent_epoch}" "${recent_release}/artifacts/recent.tar" "${recent_release}/artifacts" "${recent_release}"
printf 'input artifact\n' > "${TMP_ROOT}/input-artifact.tar"

bash "${TMP_ROOT}/orchestrator/scripts/android/package_component_release.sh" \
  --component train_bot \
  --artifact "${TMP_ROOT}/input-artifact.tar" \
  --release-id test-release \
  --out-dir "${new_release}" >/dev/null

if [[ -e "${old_release}" ]]; then
  echo "FAIL: package_component_release.sh did not prune stale sibling releases through cleanup_workspace.sh" >&2
  exit 1
fi

if [[ ! -e "${recent_release}" ]]; then
  echo "FAIL: package_component_release.sh removed a recent sibling release" >&2
  exit 1
fi

if [[ ! -f "${new_release}/release-manifest.json" ]]; then
  echo "FAIL: package_component_release.sh did not create the new release manifest" >&2
  exit 1
fi

if [[ ! -f "${new_release}/artifacts/input-artifact.tar" ]]; then
  echo "FAIL: package_component_release.sh did not stage the new artifact" >&2
  exit 1
fi

echo "PASS: workspace cleanup centralizes garbage disposal and preserves protected paths"
