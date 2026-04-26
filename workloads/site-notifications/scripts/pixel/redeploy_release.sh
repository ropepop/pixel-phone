#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=./common.sh
source "$SCRIPT_DIR/common.sh"

DEFAULT_ORCHESTRATOR_REPO="$(cd "$REPO_ROOT/../../orchestrator" 2>/dev/null && pwd || true)"
ORCHESTRATOR_REPO="${ORCHESTRATOR_REPO:-$DEFAULT_ORCHESTRATOR_REPO}"
ORCHESTRATOR_DEPLOY_SCRIPT="${ORCHESTRATOR_REPO}/scripts/android/deploy_orchestrator_apk.sh"
COMPONENT_PACKAGER="${ORCHESTRATOR_REPO}/scripts/android/package_component_release.sh"
ORCHESTRATOR_CONFIG_FILE="${ORCHESTRATOR_CONFIG_FILE:-${ORCHESTRATOR_REPO}/configs/orchestrator-config-v1.production.json}"
COLLECT_SCRIPT="${REPO_ROOT}/scripts/collect_pixel_deploy_data.sh"

SKIP_BUILD=0
BOOTSTRAP_ONLY=0
PACKAGE_ONLY=0
VALIDATE_ONLY=0
ROLLOUT_LOG_PATH=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options]

Options:
  --device SERIAL      adb serial to target
  --transport MODE     transport to use (adb|ssh|auto)
  --ssh-host IP        Tailscale or SSH host/IP
  --ssh-port PORT      SSH port (default: 2222)
  --skip-build         skip orchestrator APK build
  --bootstrap-only     run orchestrator bootstrap only
  --package-only       build/package the release only and print the release dir
  --validate-only      run site-notifier validation only
  -h, --help           show help
USAGE
}

orchestrator_args() {
  local args=()
  pixel_transport_append_cli_args args
  if (( SKIP_BUILD == 1 )); then
    args+=(--skip-build)
  fi
  if [[ -f "${ORCHESTRATOR_CONFIG_FILE}" ]]; then
    args+=(--config-file "${ORCHESTRATOR_CONFIG_FILE}")
  fi
  printf '%s\n' "${args[@]}"
}

run_orchestrator() {
  local -a cmd=("${ORCHESTRATOR_DEPLOY_SCRIPT}")
  local line
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(orchestrator_args)
  cmd+=("$@")
  "${cmd[@]}"
}

run_release_check() {
  local -a cmd=("${SCRIPT_DIR}/release_check.sh")
  local line=""
  while IFS= read -r line; do
    [[ -n "${line}" ]] && cmd+=("${line}")
  done < <(transport_args)
  "${cmd[@]}"
}

collect_failure_diagnostics() {
  local reason="${1:-deploy_failure}"
  local bundle_dir="${REPO_ROOT}/output/pixel/site-notifier-rollout-failures/${PIXEL_RUN_ID}-${reason}"
  mkdir -p "${bundle_dir}"
  printf '%s\n' "${reason}" > "${bundle_dir}/reason.txt"
  if [[ -n "${ROLLOUT_LOG_PATH}" && -f "${ROLLOUT_LOG_PATH}" ]]; then
    cp "${ROLLOUT_LOG_PATH}" "${bundle_dir}/redeploy.log"
  fi
  [[ -x "${COLLECT_SCRIPT}" ]] || return 0
  log "Collecting Site Notifier diagnostics (${reason})"
  local output=""
  output="$(
    PIXEL_TRANSPORT="${PIXEL_TRANSPORT}" \
    ADB_SERIAL="${ADB_SERIAL:-}" \
    PIXEL_SSH_HOST="${PIXEL_SSH_HOST:-}" \
    PIXEL_SSH_PORT="${PIXEL_SSH_PORT:-}" \
    PIXEL_RUN_ID="${PIXEL_RUN_ID}" \
    REPORT_DIR="${bundle_dir}" \
      "${COLLECT_SCRIPT}" 2>&1 || true
  )"
  if [[ -n "${output}" ]]; then
    printf '%s\n' "${output}" >&2
  fi
  log "Saved failure diagnostics to ${bundle_dir}"
}

resolve_local_seed_bundle() {
  find "${REPO_ROOT}/.artifacts" \
    \( \
      -path "${REPO_ROOT}/.artifacts/site-notifier/site-notifier-bundle-*.tar" \
      -o -path "${REPO_ROOT}/.artifacts/component-releases/site_notifier-*/artifacts/site-notifier-bundle-*.tar" \
    \) \
    -type f \
    | sort | tail -n 1
}

build_site_notifier_bundle() {
  local timestamp_utc="$(date -u +%Y%m%dT%H%M%SZ)"
  local release_id="site-notifier-${timestamp_utc}"
  local source_tar="${REPO_ROOT}/.artifacts/site-notifier/source-${release_id}.tar"
  local bundle_path="${REPO_ROOT}/.artifacts/site-notifier/site-notifier-bundle-${release_id}.tar"
  local release_dir="${REPO_ROOT}/.artifacts/component-releases/site_notifier-${release_id}"
  local remote_source_tar="/data/local/tmp/site-notifier-source-${release_id}.tar"
  local remote_seed_tar="/data/local/tmp/site-notifier-seed-${release_id}.tar"
  local remote_bundle_tar="/data/local/tmp/site-notifier-bundle-${release_id}.tar"
  local remote_build_root="/data/local/tmp/site-notifier-build-${release_id}"
  local remote_seed_extract_root="/data/local/tmp/site-notifier-seed-${release_id}"
  local device_runtime_seed_root="/data/local/pixel-stack/apps/site-notifications/current/.runtime/usr"
  local local_seed_bundle=""
  local device_seed_available="0"

  mkdir -p "$(dirname "${source_tar}")"
  COPYFILE_DISABLE=1 COPY_EXTENDED_ATTRIBUTES_DISABLE=1 tar \
    --exclude='.git' \
    --exclude='.venv' \
    --exclude='__pycache__' \
    --exclude='.pytest_cache' \
    --exclude='state' \
    --exclude='output' \
    -C "${REPO_ROOT}" \
    -cf "${source_tar}" \
    app.py config.py env_store.py gribu_auth.py gribu_client.py process_lock.py requirements.txt scheduler.py state_store.py telegram_control.py unread_parser.py

  device_seed_available="$(adb_shell_root "if [ -x '${device_runtime_seed_root}/bin/python3' ]; then printf '1'; else printf '0'; fi" | tr -d '\r')"
  local_seed_bundle="$(resolve_local_seed_bundle)"
  if [[ "${device_seed_available}" != "1" && -z "${local_seed_bundle}" ]]; then
    echo "missing notifier runtime seed: no active device runtime and no local staged site-notifier bundle under ${REPO_ROOT}/.artifacts" >&2
    return 1
  fi

  adb_shell_root "rm -rf '${remote_build_root}' '${remote_seed_extract_root}' '${remote_source_tar}' '${remote_seed_tar}' '${remote_bundle_tar}'" >/dev/null 2>&1 || true
  adb_cmd push "${source_tar}" "${remote_source_tar}" >/dev/null
  if [[ -n "${local_seed_bundle}" ]]; then
    adb_cmd push "${local_seed_bundle}" "${remote_seed_tar}" >/dev/null
  fi
  adb_shell_root_stdin >&2 <<CMDS
set -euo pipefail
BUILD_ROOT="${remote_build_root}"
SOURCE_TAR="${remote_source_tar}"
SEED_BUNDLE_TAR="${remote_seed_tar}"
SEED_EXTRACT_ROOT="${remote_seed_extract_root}"
BUNDLE_TAR="${remote_bundle_tar}"
ACTIVE_RUNTIME_ROOT="${device_runtime_seed_root}"
RUNTIME_ROOT="\$BUILD_ROOT/.runtime/usr"
RUNTIME_LIB="\$RUNTIME_ROOT/lib"
RUNTIME_PYTHON_LIB="\$RUNTIME_LIB/python3.12"

rm -rf "\$BUILD_ROOT" "\$SEED_EXTRACT_ROOT" "\$BUNDLE_TAR"
mkdir -p "\$BUILD_ROOT"
tar -xf "\$SOURCE_TAR" -C "\$BUILD_ROOT"

if [ -x "\$ACTIVE_RUNTIME_ROOT/bin/python3" ]; then
  mkdir -p "\$BUILD_ROOT/.runtime"
  cp -a "\$ACTIVE_RUNTIME_ROOT" "\$BUILD_ROOT/.runtime/usr"
elif [ -s "\$SEED_BUNDLE_TAR" ]; then
  mkdir -p "\$SEED_EXTRACT_ROOT"
  tar -xf "\$SEED_BUNDLE_TAR" -C "\$SEED_EXTRACT_ROOT"
  if [ ! -x "\$SEED_EXTRACT_ROOT/.runtime/usr/bin/python3" ]; then
    echo "missing notifier runtime seed in staged bundle" >&2
    exit 31
  fi
  mkdir -p "\$BUILD_ROOT/.runtime"
  cp -a "\$SEED_EXTRACT_ROOT/.runtime/usr" "\$BUILD_ROOT/.runtime/usr"
else
  echo "missing notifier runtime seed" >&2
  exit 30
fi

mkdir -p "\$RUNTIME_ROOT/bin" "\$RUNTIME_PYTHON_LIB/site-packages" "\$BUILD_ROOT/.venv/bin"
chmod 0755 "\$RUNTIME_ROOT/bin/python3" "\$RUNTIME_ROOT/bin/python3.12" 2>/dev/null || true
cat > "\$BUILD_ROOT/.venv/bin/python" <<'EOF_WRAPPER'
#!/system/bin/sh
set -eu
SCRIPT_DIR="\$(CDPATH= cd -- "\$(dirname -- "\$0")" && pwd)"
APP_ROOT="\$(CDPATH= cd -- "\${SCRIPT_DIR}/../.." && pwd)"
PYTHON_HOME="\${APP_ROOT}/.runtime/usr"
export PYTHONHOME="\${PYTHON_HOME}"
export LD_LIBRARY_PATH="\${PYTHON_HOME}/lib\${LD_LIBRARY_PATH:+:\${LD_LIBRARY_PATH}}"
unset PYTHONPATH
exec "\${PYTHON_HOME}/bin/python3" "\$@"
EOF_WRAPPER
chmod 0755 "\$BUILD_ROOT/.venv/bin/python"
ln -sfn python "\$BUILD_ROOT/.venv/bin/python3"
ln -sfn python "\$BUILD_ROOT/.venv/bin/python3.12"
"\$BUILD_ROOT/.venv/bin/python" -m pip install --upgrade -r "\$BUILD_ROOT/requirements.txt" --target "\$RUNTIME_PYTHON_LIB/site-packages"
"\$BUILD_ROOT/.venv/bin/python" -V >/dev/null
"\$BUILD_ROOT/.venv/bin/python" -c 'import bs4, dotenv, requests, ssl, sqlite3'
tar -C "\$BUILD_ROOT" -cf "\$BUNDLE_TAR" .
test -s "\$BUNDLE_TAR"
CMDS

  adb_cmd pull "${remote_bundle_tar}" "${bundle_path}" >/dev/null
  adb_shell_root "rm -rf '${remote_build_root}' '${remote_seed_extract_root}' '${remote_source_tar}' '${remote_seed_tar}' '${remote_bundle_tar}'" >/dev/null 2>&1 || true
  if [[ ! -s "${bundle_path}" ]]; then
    echo "Artifact file not found: ${bundle_path}" >&2
    return 1
  fi

  "${COMPONENT_PACKAGER}" \
    --component site_notifier \
    --artifact "${bundle_path}" \
    --file-name "site-notifier-bundle-${release_id}.tar" \
    --release-id "${release_id}" \
    --out-dir "${release_dir}" >/dev/null
  printf '%s\n' "${release_dir}"
}

while (( $# > 0 )); do
  if pixel_transport_parse_arg "$1" "${2:-}"; then
    shift "${PIXEL_TRANSPORT_PARSE_CONSUMED}"
    continue
  fi

  case "$1" in
    --skip-build)
      SKIP_BUILD=1
      ;;
    --bootstrap-only)
      BOOTSTRAP_ONLY=1
      ;;
    --package-only)
      PACKAGE_ONLY=1
      ;;
    --validate-only)
      VALIDATE_ONLY=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if (( BOOTSTRAP_ONLY + PACKAGE_ONLY + VALIDATE_ONLY > 1 )); then
  echo "--bootstrap-only, --package-only, and --validate-only are mutually exclusive" >&2
  exit 2
fi

ensure_device
ensure_root
ensure_output_dirs
ensure_local_env

ROLLOUT_LOG_PATH="${REPO_ROOT}/output/pixel/site-notifier-redeploy-${PIXEL_RUN_ID}.log"
mkdir -p "$(dirname "${ROLLOUT_LOG_PATH}")"
exec > >(tee "${ROLLOUT_LOG_PATH}") 2>&1

if [[ -z "${ORCHESTRATOR_REPO}" || ! -d "${ORCHESTRATOR_REPO}" ]]; then
  echo "Cannot resolve orchestrator repo. Set ORCHESTRATOR_REPO explicitly." >&2
  exit 1
fi
if [[ ! -x "${ORCHESTRATOR_DEPLOY_SCRIPT}" ]]; then
  echo "Missing orchestrator deploy script: ${ORCHESTRATOR_DEPLOY_SCRIPT}" >&2
  exit 1
fi
if [[ ! -x "${COMPONENT_PACKAGER}" ]]; then
  echo "Missing component release packager: ${COMPONENT_PACKAGER}" >&2
  exit 1
fi

if (( BOOTSTRAP_ONLY == 1 )); then
  run_orchestrator --action bootstrap --site-notifier-env-file "${REPO_ROOT}/.env"
  exit 0
fi

if (( VALIDATE_ONLY == 1 )); then
  if ! run_release_check; then
    collect_failure_diagnostics "release_check"
    exit 1
  fi
  echo "Site Notifier validation complete"
  exit 0
fi

release_dir=""
if ! release_dir="$(build_site_notifier_bundle)"; then
  collect_failure_diagnostics "build_bundle"
  exit 1
fi
if [[ -z "${release_dir}" || ! -d "${release_dir}" ]]; then
  collect_failure_diagnostics "release_staging"
  echo "Site Notifier release staging failed; release directory missing" >&2
  exit 1
fi
log "Staged Site Notifier component release: ${release_dir}"
if (( PACKAGE_ONLY == 1 )); then
  printf 'SITE_NOTIFIER_RELEASE_DIR=%s\n' "${release_dir}"
  exit 0
fi
if ! run_orchestrator \
  --component-release-dir "${release_dir}" \
  --action redeploy_component \
  --component site_notifier \
  --site-notifier-env-file "${REPO_ROOT}/.env"; then
  collect_failure_diagnostics "redeploy_component"
  exit 1
fi
if ! run_release_check; then
  collect_failure_diagnostics "release_check"
  exit 1
fi

echo "Site Notifier redeploy complete: release staged at ${release_dir}"
