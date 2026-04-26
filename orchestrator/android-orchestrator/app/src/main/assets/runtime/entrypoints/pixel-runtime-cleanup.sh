#!/system/bin/sh
set -eu

STACK_BASE="/data/local/pixel-stack"
ORCHESTRATOR_CACHE="/data/user/0/lv.jolkins.pixelorchestrator/cache"
TERMUX_HOME="/data/user/0/com.termux/files/home"
PROTECTED_LIST=""
DRY_RUN=0
TERMUX_RETENTION_LIST=""

cleanup_tmp_files() {
  [ -n "${TERMUX_RETENTION_LIST}" ] && rm -f "${TERMUX_RETENTION_LIST}" 2>/dev/null || true
}

trap cleanup_tmp_files EXIT

while [ "$#" -gt 0 ]; do
  case "$1" in
    --protected-list)
      PROTECTED_LIST="${2:-}"
      shift 2
      ;;
    --stack-base)
      STACK_BASE="${2:-}"
      shift 2
      ;;
    --orchestrator-cache)
      ORCHESTRATOR_CACHE="${2:-}"
      shift 2
      ;;
    --termux-home)
      TERMUX_HOME="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    *)
      echo "Unsupported argument: $1" >&2
      exit 2
      ;;
  esac
done

if [ -z "${PROTECTED_LIST}" ] || [ ! -f "${PROTECTED_LIST}" ]; then
  echo "Protected path list is required" >&2
  exit 2
fi

bytes_of_path() {
  bytes_path="$1"
  if [ -d "${bytes_path}" ]; then
    du -sk "${bytes_path}" 2>/dev/null | awk '{print $1 * 1024}' || echo 0
    return 0
  fi
  if [ -e "${bytes_path}" ]; then
    wc -c < "${bytes_path}" 2>/dev/null | tr -d '[:space:]' || echo 0
    return 0
  fi
  echo 0
}

record() {
  record_kind="$1"
  record_category="$2"
  record_bytes="$3"
  record_path="$4"
  record_detail="${5:-}"
  printf '%s\t%s\t%s\t%s\t%s\n' "${record_kind}" "${record_category}" "${record_bytes}" "${record_path}" "${record_detail}"
}

is_protected() {
  protected_path="$1"
  grep -Fqx -- "${protected_path}" "${PROTECTED_LIST}" 2>/dev/null
}

is_termux_retained() {
  retained_path="$1"
  [ -n "${TERMUX_RETENTION_LIST}" ] || return 1
  grep -Fqx -- "${retained_path}" "${TERMUX_RETENTION_LIST}" 2>/dev/null
}

delete_or_candidate() {
  delete_category="$1"
  delete_path="$2"
  delete_detail="${3:-}"

  if [ ! -e "${delete_path}" ]; then
    return 0
  fi

  delete_bytes="$(bytes_of_path "${delete_path}")"
  if is_protected "${delete_path}"; then
    record "SKIP" "${delete_category}" "${delete_bytes}" "${delete_path}" "protected"
    return 0
  fi
  if is_termux_retained "${delete_path}"; then
    record "SKIP" "${delete_category}" "${delete_bytes}" "${delete_path}" "retained_generation"
    return 0
  fi

  if [ "${DRY_RUN}" -eq 1 ]; then
    record "CANDIDATE" "${delete_category}" "${delete_bytes}" "${delete_path}" "${delete_detail}"
    return 0
  fi

  if rm -rf "${delete_path}" 2>/dev/null; then
    record "DELETE" "${delete_category}" "${delete_bytes}" "${delete_path}" "${delete_detail}"
    return 0
  fi

  record "FAIL" "${delete_category}" "${delete_bytes}" "${delete_path}" "delete_failed:${delete_detail}"
}

scan_find() {
  scan_category="$1"
  scan_detail="$2"
  shift 2
  find "$@" 2>/dev/null | while IFS= read -r scan_path; do
    [ -n "${scan_path}" ] || continue
    delete_or_candidate "${scan_category}" "${scan_path}" "${scan_detail}"
  done
}

append_termux_retention() {
  retention_path="$1"
  [ -n "${TERMUX_RETENTION_LIST}" ] || return 0
  [ -n "${retention_path}" ] || return 0
  printf '%s\n' "${retention_path}" >> "${TERMUX_RETENTION_LIST}"
}

build_termux_retention_list() {
  TERMUX_RETENTION_LIST="$(mktemp)"

  notifier_count=0
  for cohort in $(ls -1dt "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier"/site-notifier-bundle-site-notifier-* 2>/dev/null || true); do
    cohort_stamp="$(basename "${cohort}" | sed -n 's/^site-notifier-bundle-\(site-notifier-[0-9]\{8\}T[0-9]\{6\}Z\)\.tar$/\1/p')"
    [ -n "${cohort_stamp}" ] || continue
    append_termux_retention "${cohort}"
    source_tar="${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/source-${cohort_stamp}.tar"
    [ -e "${source_tar}" ] && append_termux_retention "${source_tar}"
    component_release="${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/site_notifier-${cohort_stamp}"
    [ -e "${component_release}" ] && append_termux_retention "${component_release}"
    notifier_count=$((notifier_count + 1))
    [ "${notifier_count}" -ge 2 ] && break
  done

  runtime_local_count=0
  for runtime_local_path in $(ls -1dt "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local"/* 2>/dev/null || true); do
    append_termux_retention "${runtime_local_path}"
    runtime_local_count=$((runtime_local_count + 1))
    [ "${runtime_local_count}" -ge 2 ] && break
  done

  build_count=0
  for build_path in $(ls -1dt "${TERMUX_HOME}"/site-notifications-build* 2>/dev/null || true); do
    append_termux_retention "${build_path}"
    build_count=$((build_count + 1))
    [ "${build_count}" -ge 2 ] && break
  done
}

build_termux_retention_list

ARTIFACT_AGE_ARGS="-mtime +2"
LOG_AGE_ARGS="-mtime +6"

scan_find "app_cache" "runtime_artifact_cache" "${ORCHESTRATOR_CACHE}/runtime-artifacts" -mindepth 1 -maxdepth 1 \( -type f -o -type d \) ${ARTIFACT_AGE_ARGS}
scan_find "app_cache" "staged_asset_temp" "${ORCHESTRATOR_CACHE}" -mindepth 1 -maxdepth 1 -type f -name 'asset-stage-*' ${ARTIFACT_AGE_ARGS}
scan_find "app_cache" "cache_temp" "${ORCHESTRATOR_CACHE}" -mindepth 1 -maxdepth 1 -type f -name '*.tmp' ${ARTIFACT_AGE_ARGS}

scan_find "runtime_artifact" "runtime_manifest_artifacts" "${STACK_BASE}/conf/runtime/artifacts" -mindepth 1 -maxdepth 1 -type f ${ARTIFACT_AGE_ARGS}
scan_find "component_artifact" "component_manifest_artifacts" "${STACK_BASE}/conf/runtime/components" -mindepth 3 -maxdepth 3 -type f -path '*/artifacts/*' ${ARTIFACT_AGE_ARGS}

for runtime_root in \
  "${STACK_BASE}/apps/train-bot" \
  "${STACK_BASE}/apps/satiksme-bot" \
  "${STACK_BASE}/apps/site-notifications" \
  "${STACK_BASE}/apps/subscription-bot"; do
  scan_find "release_dir" "non_current_release" "${runtime_root}/releases" -mindepth 1 -maxdepth 1 -type d ${ARTIFACT_AGE_ARGS}
done

scan_find "termux_artifact" "site_notifier_artifact" "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier" -mindepth 1 -maxdepth 1 -type f ${ARTIFACT_AGE_ARGS}
scan_find "termux_artifact" "site_notifier_component_release" "${TERMUX_HOME}/telegram-train-app/workloads/site-notifications/.artifacts/component-releases" -mindepth 1 -maxdepth 1 -type d ${ARTIFACT_AGE_ARGS}
scan_find "termux_artifact" "orchestrator_runtime_local" "${TERMUX_HOME}/telegram-train-app/orchestrator/.artifacts/runtime-local" -mindepth 1 -maxdepth 1 -type d ${ARTIFACT_AGE_ARGS}
scan_find "termux_artifact" "site_notifier_build_dir" "${TERMUX_HOME}" -mindepth 1 -maxdepth 1 -type d -name 'site-notifications-build*' ${ARTIFACT_AGE_ARGS}

scan_find "action_result" "old_action_result" "${STACK_BASE}/run/orchestrator-action-results" -mindepth 1 -maxdepth 1 -type f -name '*.json' ${LOG_AGE_ARGS}
scan_find "cleanup_report" "old_cleanup_report" "${STACK_BASE}/logs/events" -mindepth 1 -maxdepth 1 -type f -name 'cleanup-*.json' ${LOG_AGE_ARGS}

for legacy_log in \
  dnscrypt-static-test.log \
  manual-dns-start.log \
  manual-dns-stop.log \
  pihole-rooted-boot.log \
  pihole-rooted-runtime.log \
  pihole-rooted-service-loop.log \
  pixel-dns-start-manual.log \
  vpn-break-glass.log; do
  scan_find "legacy_log" "legacy_debug_log" "${STACK_BASE}/logs" -mindepth 1 -maxdepth 1 -type f -name "${legacy_log}" ${LOG_AGE_ARGS}
done
