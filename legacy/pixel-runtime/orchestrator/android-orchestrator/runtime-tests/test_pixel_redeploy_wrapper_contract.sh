#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
WORKSPACE_ROOT="$(cd "${REPO_ROOT}/.." && pwd)"
TOOL_WRAPPER="${WORKSPACE_ROOT}/tools/pixel/redeploy.sh"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/android/pixel_redeploy.sh"
RETENTION_HELPER="${WORKSPACE_ROOT}/tools/pixel/artifact_retention.sh"
CLEANUP_SCRIPT="${WORKSPACE_ROOT}/tools/pixel/cleanup_workspace.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

if [[ ! -f "${TOOL_WRAPPER}" ]]; then
  echo "FAIL: missing tools/pixel/redeploy.sh" >&2
  exit 1
fi

if [[ ! -f "${SOURCE_SCRIPT}" ]]; then
  echo "FAIL: missing orchestrator/scripts/android/pixel_redeploy.sh" >&2
  exit 1
fi

if ! rg -Fq '/orchestrator/scripts/android/pixel_redeploy.sh' "${TOOL_WRAPPER}"; then
  echo "FAIL: tools/pixel/redeploy.sh no longer delegates to pixel_redeploy.sh" >&2
  exit 1
fi

for required in '--scope' '--mode' '--rootfs-tarball' '--skip-build' '--destructive-e2e'; do
  if ! rg -Fq -- "${required}" "${SOURCE_SCRIPT}"; then
    echo "FAIL: pixel_redeploy.sh missing ${required} flag" >&2
    exit 1
  fi
done

if ! rg -Fq 'package_runtime_bundle.sh' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh no longer packages runtime bundles" >&2
  exit 1
fi

if ! rg -Fq -- '--platform-only' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing platform-only runtime bundle packaging path" >&2
  exit 1
fi

if ! rg -Fq -- '--package-only' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh no longer reuses workload package-only entrypoints" >&2
  exit 1
fi

if ! rg -Fq -- '--validate-only' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh no longer reuses workload validate-only entrypoints" >&2
  exit 1
fi

if ! rg -Fq 'output/pixel/redeploy/' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing canonical report directory" >&2
  exit 1
fi

if ! rg -Fq 'summary.json' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing machine-readable summary output" >&2
  exit 1
fi

if ! rg -Fq 'runtime_freshness_args' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing dedicated runtime freshness argument builder" >&2
  exit 1
fi

if ! rg -Fq 'add_optional_arg cmd --component-release-dir "${DNS_RELEASE_DIR}"' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing bootstrap-time dns release staging contract" >&2
  exit 1
fi

for required in 'rooted-freshness.pre.txt' 'rooted-freshness.post.txt' 'preflight' 'finalState' 'liveDnsRuntimeConverged' 'rootedFreshnessReport'; do
  if ! rg -Fq -- "${required}" "${SOURCE_SCRIPT}"; then
    echo "FAIL: pixel_redeploy.sh missing ${required} reporting contract" >&2
    exit 1
  fi
done

if ! rg -Fq 'validate_dns_contract' "${SOURCE_SCRIPT}"; then
  echo "FAIL: pixel_redeploy.sh missing DNS contract validation" >&2
  exit 1
fi

WORKSPACE_FIXTURE="${TMP_ROOT}/workspace"
ORCHESTRATOR_FIXTURE="${WORKSPACE_FIXTURE}/orchestrator"
STATE_DIR="${TMP_ROOT}/state"
BIN_DIR="${TMP_ROOT}/bin"
RELEASE_DIR="${WORKSPACE_FIXTURE}/workloads/train-bot/.artifacts/component-releases/train_bot-20260309T105826Z-332599446cd3"

mkdir -p \
  "${ORCHESTRATOR_FIXTURE}/scripts/android" \
  "${WORKSPACE_FIXTURE}/tools/pixel" \
  "${WORKSPACE_FIXTURE}/workloads/train-bot/scripts/pixel" \
  "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel" \
  "${WORKSPACE_FIXTURE}/output/pixel" \
  "${RELEASE_DIR}/artifacts" \
  "${BIN_DIR}" \
  "${STATE_DIR}"

git -C "${WORKSPACE_FIXTURE}" init -q

cp "${SOURCE_SCRIPT}" "${ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh"
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh"
cp "${WORKSPACE_ROOT}/tools/pixel/transport.sh" "${WORKSPACE_FIXTURE}/tools/pixel/transport.sh"
cp "${RETENTION_HELPER}" "${WORKSPACE_FIXTURE}/tools/pixel/artifact_retention.sh"
cp "${CLEANUP_SCRIPT}" "${WORKSPACE_FIXTURE}/tools/pixel/cleanup_workspace.sh"

cat > "${ORCHESTRATOR_FIXTURE}/scripts/android/build_orchestrator_apk.sh" <<'EOF_BUILD'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$(dirname "${FAKE_STATE_DIR}/apk-builds.log")"
printf 'build\n' >> "${FAKE_STATE_DIR}/apk-builds.log"
EOF_BUILD
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/build_orchestrator_apk.sh"

cat > "${ORCHESTRATOR_FIXTURE}/scripts/android/deploy_orchestrator_apk.sh" <<'EOF_DEPLOY'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${FAKE_STATE_DIR}/${FAKE_LOG_PREFIX}-deploy-invocations.log"

action=""
component=""
while (( $# > 0 )); do
  case "$1" in
    --action)
      shift
      action="${1:-}"
      ;;
    --component)
      shift
      component="${1:-}"
      ;;
  esac
  shift || true
done

if [[ "${action}" == "redeploy_component" && "${component}" == "train_bot" ]]; then
  cat > "${FAKE_STATE_DIR}/remote-action-result.json" <<'EOF_JSON'
{"pixelRunId":"test-run-id","action":"redeploy_component","component":"train_bot","success":true,"message":"artifact success"}
EOF_JSON
fi

printf 'Action result source: artifact\n'
EOF_DEPLOY
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/deploy_orchestrator_apk.sh"

cat > "${WORKSPACE_FIXTURE}/workloads/train-bot/scripts/pixel/redeploy_release.sh" <<EOF_TRAIN
#!/usr/bin/env bash
set -euo pipefail

mode=""
while (( \$# > 0 )); do
  case "\$1" in
    --package-only)
      mode="package"
      ;;
    --validate-only)
      mode="validate"
      ;;
  esac
  shift || true
done

case "\${mode}" in
  package)
    printf 'TRAIN_BOT_RELEASE_DIR=%s\n' "${RELEASE_DIR}"
    ;;
  validate)
    exit 0
    ;;
  *)
    exit 0
    ;;
esac
EOF_TRAIN
chmod +x "${WORKSPACE_FIXTURE}/workloads/train-bot/scripts/pixel/redeploy_release.sh"

cat > "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel/redeploy_release.sh" <<'EOF_SITE'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF_SITE
chmod +x "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel/redeploy_release.sh"

touch "${WORKSPACE_FIXTURE}/workloads/train-bot/scripts/pixel/release_check.sh"
touch "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel/release_check.sh"

cat > "${RELEASE_DIR}/release-manifest.json" <<'EOF_MANIFEST'
{"releaseId":"train_bot-20260309T105826Z-332599446cd3"}
EOF_MANIFEST
printf 'bundle' > "${RELEASE_DIR}/artifacts/train-bot-bundle.tar"

cat > "${BIN_DIR}/adb" <<'EOF_ADB'
#!/usr/bin/env bash
set -euo pipefail

state_dir="${FAKE_STATE_DIR:?}"

if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi

cmd="${1:-}"
shift || true

case "${cmd}" in
  devices)
    printf 'List of devices attached\nfake-device\tdevice\n'
    ;;
  get-state)
    printf 'device\n'
    ;;
  shell)
    shell_cmd="$*"
    if [[ "${shell_cmd}" == *"/orchestrator-action-results/"* ]]; then
      if [[ "${shell_cmd}" == *"test -f "* ]]; then
        [[ -f "${state_dir}/remote-action-result.json" ]]
      elif [[ "${shell_cmd}" == *"cat "* || "${shell_cmd}" == *'"cat"'* ]]; then
        cat "${state_dir}/remote-action-result.json"
      fi
    elif [[ "${shell_cmd}" == *"readlink /data/local/pixel-stack/apps/train-bot/current"* ]]; then
        printf '/data/local/pixel-stack/apps/train-bot/releases/train_bot-20260309T105826Z-332599446cd3\n'
    fi
    ;;
  *)
    ;;
esac
EOF_ADB
chmod +x "${BIN_DIR}/adb"

run_wrapper() {
  local log_prefix="$1"
  shift
  PATH="${BIN_DIR}:${PATH}" \
  FAKE_STATE_DIR="${STATE_DIR}" \
  FAKE_LOG_PREFIX="${log_prefix}" \
  PIXEL_RUN_ID="test-run-id" \
  "${ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh" \
  --device fake-device \
  --scope train_bot \
  --mode auto \
  "$@"
}

read_lines_into_array() {
  local path="$1"
  local target_name="$2"
  local line=""
  eval "${target_name}=()"
  while IFS= read -r line; do
    eval "${target_name}+=(\"\${line}\")"
  done < "${path}"
}

auto_log="${TMP_ROOT}/auto.log"
if ! run_wrapper auto >"${auto_log}" 2>&1; then
  echo "FAIL: pixel_redeploy.sh should succeed in the auto build/install fixture" >&2
  cat "${auto_log}" >&2
  exit 1
fi

read_lines_into_array "${STATE_DIR}/auto-deploy-invocations.log" auto_invocations
if [[ "${#auto_invocations[@]}" -lt 2 ]]; then
  echo "FAIL: expected multiple orchestrator deploy invocations in auto mode" >&2
  cat "${auto_log}" >&2
  exit 1
fi

if [[ "${auto_invocations[0]}" == *"--skip-build"* ]]; then
  echo "FAIL: first deploy invocation should install the freshly built APK before action dispatch" >&2
  printf '%s\n' "${auto_invocations[@]}" >&2
  exit 1
fi

if [[ "${auto_invocations[1]}" != *"--skip-build"* ]]; then
  echo "FAIL: subsequent deploy invocation should reuse the freshly installed APK" >&2
  printf '%s\n' "${auto_invocations[@]}" >&2
  exit 1
fi

if [[ "$(grep -c '^build$' "${STATE_DIR}/apk-builds.log")" != "1" ]]; then
  echo "FAIL: auto mode should build the orchestrator APK exactly once" >&2
  cat "${STATE_DIR}/apk-builds.log" >&2
  exit 1
fi

rm -f "${STATE_DIR}/remote-action-result.json"

skip_log="${TMP_ROOT}/skip.log"
if ! run_wrapper skip --skip-build >"${skip_log}" 2>&1; then
  echo "FAIL: pixel_redeploy.sh should succeed in skip-build mode" >&2
  cat "${skip_log}" >&2
  exit 1
fi

read_lines_into_array "${STATE_DIR}/skip-deploy-invocations.log" skip_invocations
if [[ "${#skip_invocations[@]}" -lt 2 ]]; then
  echo "FAIL: expected multiple orchestrator deploy invocations in skip-build mode" >&2
  cat "${skip_log}" >&2
  exit 1
fi

for invocation in "${skip_invocations[@]}"; do
  if [[ "${invocation}" != *"--skip-build"* ]]; then
    echo "FAIL: skip-build mode should preserve --skip-build on every deploy invocation" >&2
    printf '%s\n' "${skip_invocations[@]}" >&2
    exit 1
  fi
done

echo "PASS: pixel redeploy wrapper installs a fresh APK once, then reuses it for later deploy actions"

DNS_WORKSPACE_FIXTURE="${TMP_ROOT}/dns-workspace"
DNS_ORCHESTRATOR_FIXTURE="${DNS_WORKSPACE_FIXTURE}/orchestrator"
DNS_STATE_DIR="${TMP_ROOT}/dns-state"
DNS_BIN_DIR="${TMP_ROOT}/dns-bin"
DNS_REMOTE_ROOT="${DNS_STATE_DIR}/remote"
DNS_ROOTFS_TARBALL="${DNS_STATE_DIR}/adguardhome-rootfs-arm64.tar"
DNS_RUNTIME_BUNDLE_DIR="${DNS_WORKSPACE_FIXTURE}/.artifacts/runtime-local/pixel-redeploy-dns-test"
DNS_RELEASE_DIR="${DNS_ORCHESTRATOR_FIXTURE}/.artifacts/component-releases/dns-dns-test"
DNS_EXPECTED_START="${DNS_ORCHESTRATOR_FIXTURE}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

mkdir -p \
  "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android" \
  "${DNS_ORCHESTRATOR_FIXTURE}/android-orchestrator/app/src/main/assets/runtime/templates/rooted" \
  "${DNS_ORCHESTRATOR_FIXTURE}/configs" \
  "${DNS_WORKSPACE_FIXTURE}/tools/pixel" \
  "${DNS_BIN_DIR}" \
  "${DNS_REMOTE_ROOT}/data/local/pixel-stack/conf/runtime/components/dns" \
  "${DNS_REMOTE_ROOT}/data/local/pixel-stack/conf/runtime" \
  "${DNS_REMOTE_ROOT}/data/local/pixel-stack/conf"

git -C "${DNS_WORKSPACE_FIXTURE}" init -q

cp "${SOURCE_SCRIPT}" "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh"
chmod +x "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh"
cp "${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start" "${DNS_EXPECTED_START}"
cp "${RETENTION_HELPER}" "${DNS_WORKSPACE_FIXTURE}/tools/pixel/artifact_retention.sh"
cp "${CLEANUP_SCRIPT}" "${DNS_WORKSPACE_FIXTURE}/tools/pixel/cleanup_workspace.sh"

cat > "${DNS_WORKSPACE_FIXTURE}/tools/pixel/transport.sh" <<'EOF_DNS_TRANSPORT'
#!/usr/bin/env bash
set -euo pipefail

FAKE_REMOTE_ROOT="${FAKE_REMOTE_ROOT:?}"
FAKE_EXPECTED_ADGUARDHOME_START="${FAKE_EXPECTED_ADGUARDHOME_START:?}"
ADB_BIN="${ADB_BIN:-adb}"
PIXEL_TRANSPORT_PARSE_CONSUMED=0

pixel_transport_parse_arg() {
  case "${1:-}" in
    --transport)
      PIXEL_TRANSPORT="adb"
      PIXEL_TRANSPORT_PARSE_CONSUMED=2
      return 0
      ;;
    --device)
      ADB_SERIAL="${2:-}"
      PIXEL_TRANSPORT_PARSE_CONSUMED=2
      return 0
      ;;
    *)
      PIXEL_TRANSPORT_PARSE_CONSUMED=0
      return 1
      ;;
  esac
}

pixel_transport_require_device() {
  return 0
}

pixel_transport_selected() {
  printf 'adb\n'
}

pixel_transport_append_cli_args() {
  local ref_name="$1"
  eval "${ref_name}+=(\"--transport\" \"adb\" \"--device\" \"fake-device\")"
}

pixel_transport_adb_compat() {
  local cmd="${1:-}"
  shift || true
  case "${cmd}" in
    get-state)
      printf 'device\n'
      return 0
      ;;
    shell)
      local shell_cmd="$*"
      if [[ "${shell_cmd}" == *"--remote-healthcheck-debug"* ]]; then
        local debug_output="${FAKE_REMOTE_HEALTHCHECK_DEBUG_OUTPUT:-}"
        if [[ -n "${FAKE_REMOTE_HEALTHCHECK_DEBUG_FAIL_COUNT:-}" ]]; then
          local count_file="${FAKE_STATE_DIR}/dns-remote-healthcheck-debug-count"
          local count=0
          if [[ -f "${count_file}" ]]; then
            count="$(cat "${count_file}")"
          fi
          count=$((count + 1))
          printf '%s' "${count}" > "${count_file}"
          if (( count <= FAKE_REMOTE_HEALTHCHECK_DEBUG_FAIL_COUNT )); then
            debug_output="${FAKE_REMOTE_HEALTHCHECK_DEBUG_FAIL_OUTPUT:-${debug_output}}"
          fi
        fi
        if [[ -n "${debug_output}" ]]; then
          printf '%b' "${debug_output}"
          [[ "${debug_output}" == *$'\n' ]] || printf '\n'
        fi
        return "${FAKE_REMOTE_HEALTHCHECK_DEBUG_RC:-0}"
      fi
      if [[ "${shell_cmd}" == *"ss -ltn"* && "${shell_cmd}" == *"--remote-healthcheck"* ]]; then
        return "${FAKE_REMOTE_HEALTHCHECK_RC:-0}"
      fi
      return 0
      ;;
    *)
      return 0
      ;;
  esac
}

pixel_transport_remote_file_exists() {
  local remote_path="$1"
  [[ -f "${FAKE_REMOTE_ROOT}${remote_path}" ]]
}

pixel_transport_remote_sha256_file() {
  local remote_path="$1"
  if [[ "${remote_path}" == "/data/local/pixel-stack/chroots/adguardhome/usr/local/bin/adguardhome-start" ]]; then
    sha256sum "${FAKE_EXPECTED_ADGUARDHOME_START}" | awk '{print $1}'
    return 0
  fi
  if [[ -f "${FAKE_REMOTE_ROOT}${remote_path}" ]]; then
    sha256sum "${FAKE_REMOTE_ROOT}${remote_path}" | awk '{print $1}'
  else
    printf 'MISSING\n'
  fi
}

pixel_transport_remote_cat() {
  local remote_path="$1"
  cat "${FAKE_REMOTE_ROOT}${remote_path}"
}

pixel_transport_pull() {
  local remote_path="$1"
  local local_path="$2"
  cp "${FAKE_REMOTE_ROOT}${remote_path}" "${local_path}"
}
EOF_DNS_TRANSPORT
chmod +x "${DNS_WORKSPACE_FIXTURE}/tools/pixel/transport.sh"

cat > "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/build_orchestrator_apk.sh" <<'EOF_DNS_BUILD'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF_DNS_BUILD
chmod +x "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/build_orchestrator_apk.sh"

cat > "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/package_runtime_bundle.sh" <<'EOF_DNS_RUNTIME'
#!/usr/bin/env bash
set -euo pipefail

out_dir=""
while (( $# > 0 )); do
  case "$1" in
    --out-dir)
      shift
      out_dir="${1:-}"
      ;;
  esac
  shift || true
done

mkdir -p "${out_dir}"
cat > "${out_dir}/runtime-manifest.json" <<'EOF_JSON'
{
  "artifacts": [
    {"id": "dropbear-bundle", "sha256": "dropbear-match"},
    {"id": "tailscale-bundle", "sha256": "tailscale-match"},
    {"id": "adguardhome-rootfs", "sha256": "rootfs-match"},
    {"id": "dns-runtime-assets", "sha256": "dns-assets-match"}
  ]
}
EOF_JSON
EOF_DNS_RUNTIME
chmod +x "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/package_runtime_bundle.sh"

cat > "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/package_dns_component_release.sh" <<'EOF_DNS_RELEASE'
#!/usr/bin/env bash
set -euo pipefail

out_dir=""
while (( $# > 0 )); do
  case "$1" in
    --out-dir)
      shift
      out_dir="${1:-}"
      ;;
  esac
  shift || true
done

mkdir -p "${out_dir}"
cat > "${out_dir}/release-manifest.json" <<'EOF_JSON'
{
  "artifacts": [
    {"id": "adguardhome-rootfs", "sha256": "rootfs-match"},
    {"id": "dns-runtime-assets", "sha256": "dns-assets-match"}
  ]
}
EOF_JSON
EOF_DNS_RELEASE
chmod +x "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/package_dns_component_release.sh"

cat > "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/runtime_asset_freshness.sh" <<'EOF_DNS_FRESH'
#!/usr/bin/env bash
set -euo pipefail
printf 'FRESH scope=rooted checked=16 transport=adb\n'
exit 0
EOF_DNS_FRESH
chmod +x "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/runtime_asset_freshness.sh"

cat > "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/deploy_orchestrator_apk.sh" <<'EOF_DNS_DEPLOY'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${FAKE_STATE_DIR}/dns-deploy-invocations.log"

action=""
component=""
while (( $# > 0 )); do
  case "$1" in
    --action)
      shift
      action="${1:-}"
      ;;
    --component)
      shift
      component="${1:-}"
      ;;
  esac
  shift || true
done

printf 'Action result source: artifact\n'

if [[ "${action}" == "health" ]]; then
  exit 1
fi

if [[ "${action}" == "health_component" && "${component}" == "dns" ]]; then
  exit 0
fi

if [[ "${action}" == "health_component" && "${component}" == "remote" ]]; then
  if [[ "${FAKE_REMOTE_HEALTH_FAIL_ONCE:-0}" == "1" ]]; then
    remote_health_count_file="${FAKE_STATE_DIR}/dns-remote-health-count"
    remote_health_count=0
    if [[ -f "${remote_health_count_file}" ]]; then
      remote_health_count="$(cat "${remote_health_count_file}")"
    fi
    remote_health_count=$((remote_health_count + 1))
    printf '%s\n' "${remote_health_count}" > "${remote_health_count_file}"
    if (( remote_health_count == 1 )); then
      exit 1
    fi
  fi
  exit 0
fi

if [[ "${action}" == "restart_component" && "${component}" == "remote" ]]; then
  printf 'Action restart_component reported SUCCESS\n'
  exit 0
fi

exit 0
EOF_DNS_DEPLOY
chmod +x "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/deploy_orchestrator_apk.sh"

cat > "${DNS_ORCHESTRATOR_FIXTURE}/configs/orchestrator-config-v1.production.json" <<'EOF_DNS_CONFIG'
{
  "remote": {
    "hostname": "dns.jolkins.id.lv",
    "dohPathToken": "tok"
  }
}
EOF_DNS_CONFIG

cp "${DNS_ORCHESTRATOR_FIXTURE}/configs/orchestrator-config-v1.production.json" "${DNS_REMOTE_ROOT}/data/local/pixel-stack/conf/orchestrator-config-v1.json"

cat > "${DNS_REMOTE_ROOT}/data/local/pixel-stack/conf/runtime/runtime-manifest.json" <<'EOF_DNS_REMOTE_RUNTIME'
{
  "artifacts": [
    {"id": "dropbear-bundle", "sha256": "dropbear-match"},
    {"id": "tailscale-bundle", "sha256": "tailscale-match"},
    {"id": "adguardhome-rootfs", "sha256": "rootfs-match"},
    {"id": "dns-runtime-assets", "sha256": "dns-assets-match"}
  ]
}
EOF_DNS_REMOTE_RUNTIME

cat > "${DNS_REMOTE_ROOT}/data/local/pixel-stack/conf/runtime/components/dns/release-manifest.json" <<'EOF_DNS_REMOTE_RELEASE'
{
  "artifacts": [
    {"id": "adguardhome-rootfs", "sha256": "rootfs-match"},
    {"id": "dns-runtime-assets", "sha256": "dns-assets-match"}
  ]
}
EOF_DNS_REMOTE_RELEASE

printf 'rootfs' > "${DNS_ROOTFS_TARBALL}"

cat > "${DNS_BIN_DIR}/curl" <<'EOF_DNS_CURL'
#!/usr/bin/env bash
set -euo pipefail

mode="${FAKE_CURL_MODE:-dns-ok}"
url="${*: -1}"
case "${mode}" in
  all-000)
    printf '000'
    ;;
  dns-ok)
    case "${url}" in
      *"/admin/"*)
        printf '401'
        ;;
      *"/tok/dns-query"*)
        printf '400'
        ;;
      *"/dns-query"*)
        printf '404'
        ;;
      *)
        printf '200'
        ;;
    esac
    ;;
  *)
    echo "unsupported FAKE_CURL_MODE=${mode}" >&2
    exit 1
    ;;
esac
EOF_DNS_CURL
chmod +x "${DNS_BIN_DIR}/curl"

dns_log="${TMP_ROOT}/dns-validate.log"
if ! PATH="${DNS_BIN_DIR}:${PATH}" \
  FAKE_STATE_DIR="${DNS_STATE_DIR}" \
  FAKE_REMOTE_ROOT="${DNS_REMOTE_ROOT}" \
  FAKE_EXPECTED_ADGUARDHOME_START="${DNS_EXPECTED_START}" \
  PIXEL_RUN_ID="dns-test-run" \
  "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh" \
  --device fake-device \
  --scope dns \
  --mode validate-only \
  --rootfs-tarball "${DNS_ROOTFS_TARBALL}" >"${dns_log}" 2>&1; then
  echo "FAIL: dns validate-only scope should succeed when DNS health is good even if global health is bad" >&2
  cat "${dns_log}" >&2
  exit 1
fi

DNS_REPORT_DIR="${DNS_WORKSPACE_FIXTURE}/output/pixel/redeploy/dns-test-run"
if [[ ! -f "${DNS_REPORT_DIR}/summary.json" ]]; then
  echo "FAIL: dns validate-only fixture should emit a summary.json report" >&2
  cat "${dns_log}" >&2
  exit 1
fi

if ! jq -e '.status == "success"' "${DNS_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
  echo "FAIL: dns validate-only summary should report success" >&2
  cat "${DNS_REPORT_DIR}/summary.json" >&2
  exit 1
fi

if ! jq -e '.validations.dns_health == "pass"' "${DNS_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
  echo "FAIL: dns validate-only summary should record dns_health=pass" >&2
  cat "${DNS_REPORT_DIR}/summary.json" >&2
  exit 1
fi

if ! jq -e '.validations.dns_contract == "pass"' "${DNS_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
  echo "FAIL: dns validate-only summary should record dns_contract=pass" >&2
  cat "${DNS_REPORT_DIR}/summary.json" >&2
  exit 1
fi

for jq_assert in \
  '.runtimeBundleDir == null' \
  '.decisions.dnsReleaseNeeded == false' \
  '.decisions.rootedStale == false' \
  '.decisions.repairAction == "none"' \
  '.finalState.rootedFreshness == "fresh"' \
  '.finalState.liveDnsRuntimeConverged == true'
do
  if ! jq -e "${jq_assert}" "${DNS_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
    echo "FAIL: dns validate-only summary missing expected success state (${jq_assert})" >&2
    cat "${DNS_REPORT_DIR}/summary.json" >&2
    exit 1
  fi
done

if ! jq -e '.actionsExecuted == ["package_dns_release"]' "${DNS_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
  echo "FAIL: dns validate-only clean state should only package the dns release surface" >&2
  cat "${DNS_REPORT_DIR}/summary.json" >&2
  exit 1
fi

if ! grep -Fq -- '--action health_component --component dns' "${DNS_STATE_DIR}/dns-deploy-invocations.log"; then
  echo "FAIL: dns validate-only fixture should call component-scoped dns health" >&2
  cat "${DNS_STATE_DIR}/dns-deploy-invocations.log" >&2
  exit 1
fi

if rg -q -- '(^| )--action health($| )' "${DNS_STATE_DIR}/dns-deploy-invocations.log"; then
  echo "FAIL: dns validate-only fixture should not call the broad orchestrator health action" >&2
  cat "${DNS_STATE_DIR}/dns-deploy-invocations.log" >&2
  exit 1
fi

echo "PASS: dns validate-only scope succeeds on DNS-owned health even when global health is unrelatedly bad"

dns_fallback_log="${TMP_ROOT}/dns-fallback-validate.log"
if ! PATH="${DNS_BIN_DIR}:${PATH}" \
  FAKE_STATE_DIR="${DNS_STATE_DIR}" \
  FAKE_REMOTE_ROOT="${DNS_REMOTE_ROOT}" \
  FAKE_EXPECTED_ADGUARDHOME_START="${DNS_EXPECTED_START}" \
  FAKE_CURL_MODE="all-000" \
  FAKE_REMOTE_HEALTHCHECK_DEBUG_OUTPUT=$'remote_admin_code=302\ndoh_bare_code=404\ndoh_tokenized_code=400\nremote_healthcheck=ok\n' \
  PIXEL_RUN_ID="dns-fallback-run" \
  "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh" \
  --device fake-device \
  --scope dns \
  --mode validate-only \
  --rootfs-tarball "${DNS_ROOTFS_TARBALL}" >"${dns_fallback_log}" 2>&1; then
  echo "FAIL: dns validate-only should fall back to device-local contract checks when caller-side public probes are unavailable" >&2
  cat "${dns_fallback_log}" >&2
  exit 1
fi

DNS_FALLBACK_REPORT_DIR="${DNS_WORKSPACE_FIXTURE}/output/pixel/redeploy/dns-fallback-run"
if ! jq -e '.status == "success" and .validations.dns_contract == "pass"' "${DNS_FALLBACK_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
  echo "FAIL: dns fallback validate-only summary should record a successful dns_contract validation" >&2
  cat "${DNS_FALLBACK_REPORT_DIR}/summary.json" >&2
  exit 1
fi

for required_line in \
  'probe_mode=local_fallback' \
  'admin=302' \
  'bare=404' \
  'tokenized=400' \
  'public_admin=000' \
  'public_bare=000' \
  'public_tokenized=000' \
  'local_overall=ok'
do
  if ! grep -Fq "${required_line}" "${DNS_FALLBACK_REPORT_DIR}/dns-contract.txt"; then
    echo "FAIL: dns fallback contract report missing ${required_line}" >&2
    cat "${DNS_FALLBACK_REPORT_DIR}/dns-contract.txt" >&2
    exit 1
  fi
done

echo "PASS: dns validate-only falls back to device-local contract checks when public probes are unavailable"

rm -f "${DNS_STATE_DIR}/dns-remote-healthcheck-debug-count"
dns_retry_log="${TMP_ROOT}/dns-fallback-retry.log"
if ! PATH="${DNS_BIN_DIR}:${PATH}" \
  FAKE_STATE_DIR="${DNS_STATE_DIR}" \
  FAKE_REMOTE_ROOT="${DNS_REMOTE_ROOT}" \
  FAKE_EXPECTED_ADGUARDHOME_START="${DNS_EXPECTED_START}" \
  FAKE_CURL_MODE="all-000" \
  FAKE_REMOTE_HEALTHCHECK_DEBUG_FAIL_COUNT="2" \
  FAKE_REMOTE_HEALTHCHECK_DEBUG_FAIL_OUTPUT=$'remote_admin_code=000\ndoh_bare_code=000\ndoh_tokenized_code=000\nremote_healthcheck=fail\n' \
  FAKE_REMOTE_HEALTHCHECK_DEBUG_OUTPUT=$'remote_admin_code=302\ndoh_bare_code=404\ndoh_tokenized_code=400\nremote_healthcheck=ok\n' \
  DNS_CONTRACT_LOCAL_MAX_ATTEMPTS="3" \
  DNS_CONTRACT_LOCAL_RETRY_SLEEP_SECONDS="0" \
  PIXEL_RUN_ID="dns-fallback-retry-run" \
  "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh" \
  --device fake-device \
  --scope dns \
  --mode validate-only \
  --rootfs-tarball "${DNS_ROOTFS_TARBALL}" >"${dns_retry_log}" 2>&1; then
  echo "FAIL: dns validate-only should retry device-local contract checks through transient frontend outages" >&2
  cat "${dns_retry_log}" >&2
  exit 1
fi

DNS_FALLBACK_RETRY_REPORT_DIR="${DNS_WORKSPACE_FIXTURE}/output/pixel/redeploy/dns-fallback-retry-run"
if ! jq -e '.status == "success" and .validations.dns_contract == "pass"' "${DNS_FALLBACK_RETRY_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
  echo "FAIL: dns retry validate-only summary should record a successful dns_contract validation after transient local failures" >&2
  cat "${DNS_FALLBACK_RETRY_REPORT_DIR}/summary.json" >&2
  exit 1
fi

if [[ "$(cat "${DNS_STATE_DIR}/dns-remote-healthcheck-debug-count")" != "3" ]]; then
  echo "FAIL: dns retry validate-only should probe until the transient local failure clears" >&2
  cat "${dns_retry_log}" >&2
  exit 1
fi

echo "PASS: dns validate-only retries transient device-local contract failures before giving up"

rm -f "${DNS_STATE_DIR}/dns-deploy-invocations.log" "${DNS_STATE_DIR}/dns-remote-health-count"
dns_remote_recovery_log="${TMP_ROOT}/dns-remote-recovery.log"
if ! PATH="${DNS_BIN_DIR}:${PATH}" \
  FAKE_STATE_DIR="${DNS_STATE_DIR}" \
  FAKE_REMOTE_ROOT="${DNS_REMOTE_ROOT}" \
  FAKE_EXPECTED_ADGUARDHOME_START="${DNS_EXPECTED_START}" \
  FAKE_CURL_MODE="dns-ok" \
  FAKE_REMOTE_HEALTH_FAIL_ONCE="1" \
  PIXEL_RUN_ID="dns-remote-recovery-run" \
  "${DNS_ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh" \
  --device fake-device \
  --scope dns \
  --mode auto \
  --rootfs-tarball "${DNS_ROOTFS_TARBALL}" \
  --skip-build >"${dns_remote_recovery_log}" 2>&1; then
  echo "FAIL: dns auto redeploy should recover the remote frontend when the first remote health check fails" >&2
  cat "${dns_remote_recovery_log}" >&2
  exit 1
fi

DNS_REMOTE_RECOVERY_REPORT_DIR="${DNS_WORKSPACE_FIXTURE}/output/pixel/redeploy/dns-remote-recovery-run"
if ! jq -e '.status == "success" and .decisions.remoteRecoveryTriggered == true and .validations.remote_health == "fail" and .validations.remote_health_after_recovery == "pass"' "${DNS_REMOTE_RECOVERY_REPORT_DIR}/summary.json" >/dev/null 2>&1; then
  echo "FAIL: dns auto redeploy summary should record one remote recovery cycle" >&2
  cat "${DNS_REMOTE_RECOVERY_REPORT_DIR}/summary.json" >&2
  exit 1
fi

if ! grep -Fq -- '--action restart_component --component remote' "${DNS_STATE_DIR}/dns-deploy-invocations.log"; then
  echo "FAIL: dns auto redeploy should restart the remote component after a failed remote health probe" >&2
  cat "${DNS_STATE_DIR}/dns-deploy-invocations.log" >&2
  exit 1
fi

echo "PASS: dns auto redeploy retries the remote frontend when DNS-owned changes leave it unhealthy"
