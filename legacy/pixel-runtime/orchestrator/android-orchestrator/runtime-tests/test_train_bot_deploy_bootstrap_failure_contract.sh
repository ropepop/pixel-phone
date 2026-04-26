#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SOURCE_DEPLOY_SCRIPT="${REPO_ROOT}/workloads/train-bot/scripts/pixel/redeploy_release.sh"
SOURCE_COMMON_SCRIPT="${REPO_ROOT}/workloads/train-bot/scripts/pixel/common.sh"
SOURCE_TRANSPORT_SCRIPT="${REPO_ROOT}/tools/pixel/transport.sh"
SOURCE_RETENTION_HELPER="${REPO_ROOT}/tools/pixel/artifact_retention.sh"
SOURCE_CLEANUP_SCRIPT="${REPO_ROOT}/tools/pixel/cleanup_workspace.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

TEST_WORKLOAD_ROOT="${TMP_ROOT}/workloads/train-bot"
TEST_SCRIPT="${TEST_WORKLOAD_ROOT}/scripts/pixel/redeploy_release.sh"
TEST_COMMON="${TEST_WORKLOAD_ROOT}/scripts/pixel/common.sh"
FAKE_SYNC_SCRIPT="${TEST_WORKLOAD_ROOT}/scripts/pixel/sync_env_to_pixel.sh"
FAKE_PREPARE_SCRIPT="${TEST_WORKLOAD_ROOT}/scripts/pixel/prepare_native_release.sh"
FAKE_RELEASE_CHECK_SCRIPT="${TEST_WORKLOAD_ROOT}/scripts/pixel/release_check.sh"
FAKE_TUNNEL_SCRIPT="${TEST_WORKLOAD_ROOT}/scripts/pixel/provision_cloudflared_tunnel.sh"
FAKE_ORCHESTRATOR_ROOT="${TMP_ROOT}/orchestrator"
FAKE_DEPLOY_SCRIPT="${FAKE_ORCHESTRATOR_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
FAKE_CONFIG_FILE="${FAKE_ORCHESTRATOR_ROOT}/configs/orchestrator-config-v1.production.json"
FAKE_MARKER_FILE="${TMP_ROOT}/bootstrap-invocations.log"

mkdir -p "${TEST_WORKLOAD_ROOT}/scripts/pixel" "${FAKE_ORCHESTRATOR_ROOT}/scripts/android" "${FAKE_ORCHESTRATOR_ROOT}/configs" "${TMP_ROOT}/tools/pixel"
git -C "${TMP_ROOT}" init -q
cp "${SOURCE_DEPLOY_SCRIPT}" "${TEST_SCRIPT}"
cp "${SOURCE_COMMON_SCRIPT}" "${TEST_COMMON}"
cp "${SOURCE_TRANSPORT_SCRIPT}" "${TMP_ROOT}/tools/pixel/transport.sh"
cp "${SOURCE_RETENTION_HELPER}" "${TMP_ROOT}/tools/pixel/artifact_retention.sh"
cp "${SOURCE_CLEANUP_SCRIPT}" "${TMP_ROOT}/tools/pixel/cleanup_workspace.sh"

default_timeout="$(sed -n 's/^ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC="${ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC:-\([0-9][0-9]*\)}"$/\1/p' "${TEST_SCRIPT}")"
if [[ -z "${default_timeout}" ]]; then
  echo "FAIL: unable to parse default bootstrap timeout from ${TEST_SCRIPT}" >&2
  exit 1
fi
if (( default_timeout < 240 )); then
  echo "FAIL: default bootstrap timeout regressed below 240s (got ${default_timeout})" >&2
  exit 1
fi

cat > "${TEST_WORKLOAD_ROOT}/.env" <<'EOF_ENV'
BOT_TOKEN=test-token
EOF_ENV

cat > "${FAKE_CONFIG_FILE}" <<'EOF_CFG'
{
  "trainBot": {
    "ingressMode": "direct",
    "publicBaseUrl": "https://train-bot.jolkins.id.lv"
  }
}
EOF_CFG

cat > "${FAKE_DEPLOY_SCRIPT}" <<'EOF_FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_MARKER_FILE}"
if [[ -n "${FAKE_SLEEP_SEC:-}" ]]; then
  sleep "${FAKE_SLEEP_SEC}"
fi
exit "${FAKE_RC:-0}"
EOF_FAKE
chmod +x "${FAKE_DEPLOY_SCRIPT}"

for helper in "${FAKE_SYNC_SCRIPT}" "${FAKE_RELEASE_CHECK_SCRIPT}" "${FAKE_TUNNEL_SCRIPT}"; do
  cat > "${helper}" <<'EOF_HELPER'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF_HELPER
  chmod +x "${helper}"
done

cat > "${FAKE_PREPARE_SCRIPT}" <<'EOF_PREPARE'
#!/usr/bin/env bash
set -euo pipefail
echo "/tmp/fake-release"
EOF_PREPARE
chmod +x "${FAKE_PREPARE_SCRIPT}"

run_test_script() {
  ORCHESTRATOR_REPO="${FAKE_ORCHESTRATOR_ROOT}" \
  FAKE_MARKER_FILE="${FAKE_MARKER_FILE}" \
  "$@"
}

nonzero_log="${TMP_ROOT}/bootstrap-nonzero.log"
set +e
FAKE_RC=17 run_test_script "${TEST_SCRIPT}" --bootstrap-only >"${nonzero_log}" 2>&1
nonzero_rc=$?
set -e
if [[ "${nonzero_rc}" == "0" ]]; then
  echo "FAIL: redeploy_release.sh succeeded despite bootstrap returning non-zero" >&2
  exit 1
fi
if [[ "${nonzero_rc}" != "17" ]]; then
  echo "FAIL: expected bootstrap non-zero exit 17, got ${nonzero_rc}" >&2
  exit 1
fi
if ! rg -Fq 'aborting train-bot deploy before runtime enforcement' "${nonzero_log}"; then
  echo "FAIL: bootstrap non-zero path no longer aborts before runtime enforcement" >&2
  exit 1
fi

timeout_log="${TMP_ROOT}/bootstrap-timeout.log"
set +e
ORCHESTRATOR_BOOTSTRAP_TIMEOUT_SEC=1 FAKE_SLEEP_SEC=3 run_test_script "${TEST_SCRIPT}" --bootstrap-only >"${timeout_log}" 2>&1
timeout_rc=$?
set -e
if [[ "${timeout_rc}" == "0" ]]; then
  echo "FAIL: redeploy_release.sh succeeded despite bootstrap timeout" >&2
  exit 1
fi
if [[ "${timeout_rc}" != "124" ]]; then
  echo "FAIL: expected bootstrap timeout exit 124, got ${timeout_rc}" >&2
  exit 1
fi
if ! rg -Fq 'timed out after 1s; aborting train-bot deploy before runtime enforcement' "${timeout_log}"; then
  echo "FAIL: bootstrap timeout path no longer aborts before runtime enforcement" >&2
  exit 1
fi

rm -f "${FAKE_MARKER_FILE}"
start_only_log="${TMP_ROOT}/start-only.log"
if ! run_test_script "${TEST_SCRIPT}" --start-only >"${start_only_log}" 2>&1; then
  echo "FAIL: --start-only path is no longer available" >&2
  exit 1
fi
if ! rg -Fq -- '--action start_component --component train_bot' "${FAKE_MARKER_FILE}"; then
  echo "FAIL: --start-only no longer invokes start_component for train_bot" >&2
  exit 1
fi

echo "PASS: train-bot redeploy wrapper fails closed on bootstrap errors and preserves explicit start-only mode"
