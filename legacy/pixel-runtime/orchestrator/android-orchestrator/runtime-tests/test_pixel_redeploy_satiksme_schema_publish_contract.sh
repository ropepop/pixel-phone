#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/android/pixel_redeploy.sh"
CLEANUP_SCRIPT="${REPO_ROOT}/../tools/pixel/cleanup_workspace.sh"
RETENTION_HELPER="${REPO_ROOT}/../tools/pixel/artifact_retention.sh"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

WORKSPACE_FIXTURE="${TMP_ROOT}/workspace"
ORCHESTRATOR_FIXTURE="${WORKSPACE_FIXTURE}/orchestrator"
STATE_DIR="${TMP_ROOT}/state"
REMOTE_ROOT="${STATE_DIR}/remote"
BIN_DIR="${TMP_ROOT}/bin"
ROOTFS_TARBALL="${STATE_DIR}/adguardhome-rootfs-arm64.tar"
EXPECTED_ADGUARDHOME_START="${ORCHESTRATOR_FIXTURE}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start"

TRAIN_RELEASE_DIR="${WORKSPACE_FIXTURE}/workloads/train-bot/.artifacts/component-releases/train_bot-train-fixture"
SATIKSME_RELEASE_DIR="${WORKSPACE_FIXTURE}/workloads/satiksme-bot/.artifacts/component-releases/satiksme_bot-satiksme-fixture"
SITE_RELEASE_DIR="${WORKSPACE_FIXTURE}/workloads/site-notifications/.artifacts/component-releases/site_notifier-site-fixture"
SUBSCRIPTION_RELEASE_DIR="${WORKSPACE_FIXTURE}/workloads/subscription-bot/.artifacts/component-releases/subscription_bot-subscription-fixture"

mkdir -p \
  "${ORCHESTRATOR_FIXTURE}/scripts/android" \
  "${ORCHESTRATOR_FIXTURE}/android-orchestrator/app/src/main/assets/runtime/templates/rooted" \
  "${ORCHESTRATOR_FIXTURE}/configs" \
  "${WORKSPACE_FIXTURE}/tools/pixel" \
  "${WORKSPACE_FIXTURE}/workloads/train-bot/scripts/pixel" \
  "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel" \
  "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel" \
  "${WORKSPACE_FIXTURE}/workloads/subscription-bot/scripts/pixel" \
  "${WORKSPACE_FIXTURE}/output/pixel" \
  "${BIN_DIR}" \
  "${REMOTE_ROOT}/data/local/pixel-stack/conf/runtime/components/dns" \
  "${REMOTE_ROOT}/data/local/pixel-stack/conf/runtime" \
  "${REMOTE_ROOT}/data/local/pixel-stack/conf"

git -C "${WORKSPACE_FIXTURE}" init -q

cp "${SOURCE_SCRIPT}" "${ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh"
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh"
cp "${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-start" "${EXPECTED_ADGUARDHOME_START}"
cp "${CLEANUP_SCRIPT}" "${WORKSPACE_FIXTURE}/tools/pixel/cleanup_workspace.sh"
cp "${RETENTION_HELPER}" "${WORKSPACE_FIXTURE}/tools/pixel/artifact_retention.sh"

mkdir -p \
  "${TRAIN_RELEASE_DIR}/artifacts" \
  "${SATIKSME_RELEASE_DIR}/artifacts" \
  "${SITE_RELEASE_DIR}/artifacts" \
  "${SUBSCRIPTION_RELEASE_DIR}/artifacts"

cat > "${TRAIN_RELEASE_DIR}/release-manifest.json" <<'EOF_TRAIN_MANIFEST'
{"releaseId":"train_bot-train-fixture"}
EOF_TRAIN_MANIFEST
printf 'bundle' > "${TRAIN_RELEASE_DIR}/artifacts/train-bot-bundle.tar"

cat > "${SATIKSME_RELEASE_DIR}/release-manifest.json" <<'EOF_SATIKSME_MANIFEST'
{"releaseId":"satiksme_bot-satiksme-fixture"}
EOF_SATIKSME_MANIFEST
printf 'bundle' > "${SATIKSME_RELEASE_DIR}/artifacts/satiksme-bot-bundle.tar"

cat > "${SITE_RELEASE_DIR}/release-manifest.json" <<'EOF_SITE_MANIFEST'
{"releaseId":"site_notifier-site-fixture"}
EOF_SITE_MANIFEST
printf 'bundle' > "${SITE_RELEASE_DIR}/artifacts/site-notifier-bundle.tar"

cat > "${SUBSCRIPTION_RELEASE_DIR}/release-manifest.json" <<'EOF_SUBSCRIPTION_MANIFEST'
{"releaseId":"subscription_bot-subscription-fixture"}
EOF_SUBSCRIPTION_MANIFEST
printf 'bundle' > "${SUBSCRIPTION_RELEASE_DIR}/artifacts/subscription-bot-bundle.tar"

cat > "${WORKSPACE_FIXTURE}/tools/pixel/transport.sh" <<'EOF_TRANSPORT'
#!/usr/bin/env bash
set -euo pipefail

FAKE_REMOTE_ROOT="${FAKE_REMOTE_ROOT:?}"
FAKE_EXPECTED_ADGUARDHOME_START="${FAKE_EXPECTED_ADGUARDHOME_START:?}"
FAKE_STATE_DIR="${FAKE_STATE_DIR:?}"
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
      ;;
    shell)
      local shell_cmd="$*"
      local component=""
      case "${shell_cmd}" in
        *"readlink /data/local/pixel-stack/apps/train-bot/current"*)
          component="train_bot"
          ;;
        *"readlink /data/local/pixel-stack/apps/satiksme-bot/current"*)
          component="satiksme_bot"
          ;;
        *"readlink /data/local/pixel-stack/apps/site-notifications/current"*)
          component="site_notifier"
          ;;
        *"readlink /data/local/pixel-stack/apps/subscription-bot/current"*)
          component="subscription_bot"
          ;;
      esac
      if [[ -n "${component}" ]]; then
        local live_release_path_file="${FAKE_STATE_DIR}/${PIXEL_RUN_ID}-${component}-live-release-path"
        [[ -f "${live_release_path_file}" ]] && cat "${live_release_path_file}"
        return 0
      fi
      if [[ "${shell_cmd}" == *"ss -ltn"* && "${shell_cmd}" == *"--remote-healthcheck"* ]]; then
        return 0
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
    return 0
  fi
  printf 'MISSING\n'
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
EOF_TRANSPORT
chmod +x "${WORKSPACE_FIXTURE}/tools/pixel/transport.sh"

cat > "${ORCHESTRATOR_FIXTURE}/scripts/android/build_orchestrator_apk.sh" <<'EOF_BUILD'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF_BUILD
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/build_orchestrator_apk.sh"

cat > "${ORCHESTRATOR_FIXTURE}/scripts/android/package_runtime_bundle.sh" <<'EOF_RUNTIME'
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
EOF_RUNTIME
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/package_runtime_bundle.sh"

cat > "${ORCHESTRATOR_FIXTURE}/scripts/android/package_dns_component_release.sh" <<'EOF_DNS_RELEASE'
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
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/package_dns_component_release.sh"

cat > "${ORCHESTRATOR_FIXTURE}/scripts/android/runtime_asset_freshness.sh" <<'EOF_FRESH'
#!/usr/bin/env bash
set -euo pipefail
printf 'FRESH scope=rooted checked=16 transport=adb\n'
exit 0
EOF_FRESH
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/runtime_asset_freshness.sh"

cat > "${ORCHESTRATOR_FIXTURE}/scripts/android/deploy_orchestrator_apk.sh" <<'EOF_DEPLOY'
#!/usr/bin/env bash
set -euo pipefail

run_id="${PIXEL_RUN_ID:?}"
event_log="${FAKE_STATE_DIR}/${run_id}-events.log"
invocation_log="${FAKE_STATE_DIR}/${run_id}-deploy-invocations.log"
remote_root="${FAKE_REMOTE_ROOT:?}"

printf '%s\n' "$*" >> "${invocation_log}"

action=""
component=""
release_dir=""
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
    --component-release-dir)
      shift
      release_dir="${1:-}"
      ;;
  esac
  shift || true
done

if [[ "${action}" == "redeploy_component" && -n "${component}" ]]; then
  printf 'redeploy_component:%s\n' "${component}" >> "${event_log}"
  result_path="${remote_root}/data/local/pixel-stack/run/orchestrator-action-results/${run_id}--${action}--${component}.json"
  mkdir -p "$(dirname "${result_path}")"
  cat > "${result_path}" <<EOF_JSON
{"pixelRunId":"${run_id}","action":"${action}","component":"${component}","success":true}
EOF_JSON

  if [[ -n "${release_dir}" && -f "${release_dir}/release-manifest.json" ]]; then
    release_id="$(
      python3 - "${release_dir}/release-manifest.json" <<'PY'
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as fh:
    payload = json.load(fh)
print((payload.get("releaseId") or "").strip())
PY
    )"
    case "${component}" in
      train_bot) runtime_name="train-bot" ;;
      satiksme_bot) runtime_name="satiksme-bot" ;;
      site_notifier) runtime_name="site-notifications" ;;
      subscription_bot) runtime_name="subscription-bot" ;;
      *) runtime_name="${component}" ;;
    esac
    printf '/data/local/pixel-stack/apps/%s/releases/%s\n' "${runtime_name}" "${release_id}" > "${FAKE_STATE_DIR}/${run_id}-${component}-live-release-path"
  fi
fi

printf 'Action result source: artifact\n'
exit 0
EOF_DEPLOY
chmod +x "${ORCHESTRATOR_FIXTURE}/scripts/android/deploy_orchestrator_apk.sh"

cat > "${ORCHESTRATOR_FIXTURE}/configs/orchestrator-config-v1.production.json" <<'EOF_CONFIG'
{
  "remote": {
    "hostname": "dns.jolkins.id.lv",
    "dohPathToken": "tok"
  }
}
EOF_CONFIG
cp "${ORCHESTRATOR_FIXTURE}/configs/orchestrator-config-v1.production.json" "${REMOTE_ROOT}/data/local/pixel-stack/conf/orchestrator-config-v1.json"

cat > "${REMOTE_ROOT}/data/local/pixel-stack/conf/runtime/runtime-manifest.json" <<'EOF_REMOTE_RUNTIME'
{
  "artifacts": [
    {"id": "dropbear-bundle", "sha256": "dropbear-match"},
    {"id": "tailscale-bundle", "sha256": "tailscale-match"},
    {"id": "adguardhome-rootfs", "sha256": "rootfs-match"},
    {"id": "dns-runtime-assets", "sha256": "dns-assets-match"}
  ]
}
EOF_REMOTE_RUNTIME

cat > "${REMOTE_ROOT}/data/local/pixel-stack/conf/runtime/components/dns/release-manifest.json" <<'EOF_REMOTE_DNS'
{
  "artifacts": [
    {"id": "adguardhome-rootfs", "sha256": "rootfs-match"},
    {"id": "dns-runtime-assets", "sha256": "dns-assets-match"}
  ]
}
EOF_REMOTE_DNS

printf 'rootfs' > "${ROOTFS_TARBALL}"

cat > "${BIN_DIR}/curl" <<'EOF_CURL'
#!/usr/bin/env bash
set -euo pipefail

url="${*: -1}"
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
EOF_CURL
chmod +x "${BIN_DIR}/curl"

cat > "${BIN_DIR}/spacetime" <<'EOF_SPACETIME'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF_SPACETIME
chmod +x "${BIN_DIR}/spacetime"

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
    printf 'TRAIN_BOT_RELEASE_DIR=%s\n' "${TRAIN_RELEASE_DIR}"
    ;;
  validate)
    printf 'validate_train_bot\n' >> "${STATE_DIR}/\${PIXEL_RUN_ID}-events.log"
    ;;
esac
EOF_TRAIN
chmod +x "${WORKSPACE_FIXTURE}/workloads/train-bot/scripts/pixel/redeploy_release.sh"

cat > "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel/prepare_native_release.sh" <<EOF_SATIKSME_PREPARE
#!/usr/bin/env bash
set -euo pipefail
printf 'SATIKSME_BOT_RELEASE_DIR=%s\n' "${SATIKSME_RELEASE_DIR}"
EOF_SATIKSME_PREPARE
chmod +x "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel/prepare_native_release.sh"

cat > "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel/validate_prod_readiness.sh" <<EOF_SATIKSME_VALIDATE
#!/usr/bin/env bash
set -euo pipefail
printf 'validate_satiksme_bot\n' >> "${STATE_DIR}/\${PIXEL_RUN_ID}-events.log"
EOF_SATIKSME_VALIDATE
chmod +x "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel/validate_prod_readiness.sh"

cat > "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel/publish_spacetime_schema.sh" <<EOF_SATIKSME_PUBLISH
#!/usr/bin/env bash
set -euo pipefail
printf 'publish_satiksme_schema\n' >> "${STATE_DIR}/\${PIXEL_RUN_ID}-events.log"
printf 'published fixture schema\n'
EOF_SATIKSME_PUBLISH
chmod +x "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel/publish_spacetime_schema.sh"

cat > "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel/redeploy_release.sh" <<EOF_SITE
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
    printf 'SITE_NOTIFIER_RELEASE_DIR=%s\n' "${SITE_RELEASE_DIR}"
    ;;
  validate)
    printf 'validate_site_notifier\n' >> "${STATE_DIR}/\${PIXEL_RUN_ID}-events.log"
    ;;
esac
EOF_SITE
chmod +x "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel/redeploy_release.sh"

cat > "${WORKSPACE_FIXTURE}/workloads/subscription-bot/scripts/pixel/redeploy_release.sh" <<EOF_SUBSCRIPTION
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
    printf 'SUBSCRIPTION_BOT_RELEASE_DIR=%s\n' "${SUBSCRIPTION_RELEASE_DIR}"
    ;;
  validate)
    printf 'validate_subscription_bot\n' >> "${STATE_DIR}/\${PIXEL_RUN_ID}-events.log"
    ;;
esac
EOF_SUBSCRIPTION
chmod +x "${WORKSPACE_FIXTURE}/workloads/subscription-bot/scripts/pixel/redeploy_release.sh"

touch \
  "${WORKSPACE_FIXTURE}/workloads/train-bot/scripts/pixel/release_check.sh" \
  "${WORKSPACE_FIXTURE}/workloads/satiksme-bot/scripts/pixel/release_check.sh" \
  "${WORKSPACE_FIXTURE}/workloads/site-notifications/scripts/pixel/release_check.sh" \
  "${WORKSPACE_FIXTURE}/workloads/subscription-bot/scripts/pixel/release_check.sh"

run_wrapper() {
  local run_id="$1"
  local scope="$2"
  shift 2
  PATH="${BIN_DIR}:${PATH}" \
  FAKE_STATE_DIR="${STATE_DIR}" \
  FAKE_REMOTE_ROOT="${REMOTE_ROOT}" \
  FAKE_EXPECTED_ADGUARDHOME_START="${EXPECTED_ADGUARDHOME_START}" \
  PIXEL_RUN_ID="${run_id}" \
  "${ORCHESTRATOR_FIXTURE}/scripts/android/pixel_redeploy.sh" \
    --device fake-device \
    --scope "${scope}" \
    --mode auto \
    --skip-build \
    "$@"
}

event_line() {
  local event="$1"
  local path="$2"
  grep -n -F -m 1 "${event}" "${path}" | cut -d: -f1
}

assert_publish_sequence() {
  local run_id="$1"
  local event_log="${STATE_DIR}/${run_id}-events.log"
  local publish_line=""
  local redeploy_line=""
  local validate_line=""

  if [[ "$(grep -Fxc 'publish_satiksme_schema' "${event_log}")" != "1" ]]; then
    echo "FAIL: expected exactly one satiksme schema publish event for ${run_id}" >&2
    cat "${event_log}" >&2
    exit 1
  fi

  publish_line="$(event_line 'publish_satiksme_schema' "${event_log}")"
  redeploy_line="$(event_line 'redeploy_component:satiksme_bot' "${event_log}")"
  validate_line="$(event_line 'validate_satiksme_bot' "${event_log}")"

  if [[ -z "${publish_line}" || -z "${redeploy_line}" || -z "${validate_line}" ]]; then
    echo "FAIL: missing publish/redeploy/validate events for ${run_id}" >&2
    cat "${event_log}" >&2
    exit 1
  fi

  if (( publish_line >= redeploy_line )); then
    echo "FAIL: satiksme schema publish should happen before satiksme redeploy for ${run_id}" >&2
    cat "${event_log}" >&2
    exit 1
  fi

  if (( redeploy_line >= validate_line )); then
    echo "FAIL: satiksme validation should happen after satiksme redeploy for ${run_id}" >&2
    cat "${event_log}" >&2
    exit 1
  fi
}

assert_publish_summary() {
  local run_id="$1"
  local summary_path="${WORKSPACE_FIXTURE}/output/pixel/redeploy/${run_id}/summary.json"

  if ! jq -e '.status == "success"' "${summary_path}" >/dev/null 2>&1; then
    echo "FAIL: expected successful summary for ${run_id}" >&2
    cat "${summary_path}" >&2
    exit 1
  fi

  if ! jq -e '.actionsExecuted | index("publish_satiksme_schema") != null' "${summary_path}" >/dev/null 2>&1; then
    echo "FAIL: summary is missing publish_satiksme_schema action for ${run_id}" >&2
    cat "${summary_path}" >&2
    exit 1
  fi

  if ! jq -e '.validations.satiksme_schema_publish == "pass"' "${summary_path}" >/dev/null 2>&1; then
    echo "FAIL: summary is missing satiksme_schema_publish=pass for ${run_id}" >&2
    cat "${summary_path}" >&2
    exit 1
  fi
}

satiksme_only_log="${TMP_ROOT}/satiksme-only.log"
if ! run_wrapper satiksme-schema-only satiksme_bot >"${satiksme_only_log}" 2>&1; then
  echo "FAIL: satiksme_bot wrapper fixture should succeed" >&2
  cat "${satiksme_only_log}" >&2
  exit 1
fi

assert_publish_sequence "satiksme-schema-only"
assert_publish_summary "satiksme-schema-only"

if ! grep -Fq 'published fixture schema' "${WORKSPACE_FIXTURE}/output/pixel/redeploy/satiksme-schema-only/satiksme-schema-publish.log"; then
  echo "FAIL: satiksme-only publish log is missing the publish helper output" >&2
  cat "${WORKSPACE_FIXTURE}/output/pixel/redeploy/satiksme-schema-only/satiksme-schema-publish.log" >&2
  exit 1
fi

echo "PASS: satiksme-only redeploy publishes the schema before redeploying and validates afterward"

full_scope_log="${TMP_ROOT}/full-scope.log"
if ! run_wrapper satiksme-schema-full full --rootfs-tarball "${ROOTFS_TARBALL}" >"${full_scope_log}" 2>&1; then
  echo "FAIL: full wrapper fixture should succeed" >&2
  cat "${full_scope_log}" >&2
  exit 1
fi

assert_publish_sequence "satiksme-schema-full"
assert_publish_summary "satiksme-schema-full"

if ! grep -Fq 'redeploy_component:train_bot' "${STATE_DIR}/satiksme-schema-full-events.log"; then
  echo "FAIL: full scope fixture should still redeploy the other bundled workloads" >&2
  cat "${STATE_DIR}/satiksme-schema-full-events.log" >&2
  exit 1
fi

echo "PASS: full redeploy publishes the schema once and preserves the satiksme deploy ordering"
