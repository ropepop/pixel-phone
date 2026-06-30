#!/system/bin/sh
set -eu

STACK_BASE="/data/local/pixel-stack"
ORCHESTRATOR_CACHE="/data/user/0/lv.jolkins.pixelorchestrator/cache"
TERMUX_HOME="/data/user/0/com.termux/files/home"
LOCAL_TMP="/data/local/tmp"
SUPERUSER_LOG_DB="/data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db"
SUPERUSER_PACKAGE="giilmkonhuutj.ih.wb"
ROOT_RECHECK_COMMAND="id -u"
PROTECTED_LIST=""
DRY_RUN=0
TERMUX_RETENTION_LIST=""
ARTIFACT_AGE_DAYS=30
LOG_AGE_DAYS=30

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
    --local-tmp)
      LOCAL_TMP="${2:-}"
      shift 2
      ;;
    --superuser-log-db)
      SUPERUSER_LOG_DB="${2:-}"
      shift 2
      ;;
    --superuser-package)
      SUPERUSER_PACKAGE="${2:-}"
      shift 2
      ;;
    --root-recheck-command)
      ROOT_RECHECK_COMMAND="${2:-}"
      shift 2
      ;;
    --artifact-age-days)
      ARTIFACT_AGE_DAYS="${2:-}"
      shift 2
      ;;
    --log-age-days)
      LOG_AGE_DAYS="${2:-}"
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

case "${ARTIFACT_AGE_DAYS}" in
  ''|*[!0-9]*) echo "artifact age must be a whole number of days" >&2; exit 2 ;;
esac
case "${LOG_AGE_DAYS}" in
  ''|*[!0-9]*) echo "log age must be a whole number of days" >&2; exit 2 ;;
esac

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

truncate_or_candidate() {
  truncate_category="$1"
  truncate_path="$2"
  truncate_detail="${3:-}"

  if [ ! -f "${truncate_path}" ]; then
    return 0
  fi

  truncate_bytes="$(bytes_of_path "${truncate_path}")"
  if is_protected "${truncate_path}"; then
    record "SKIP" "${truncate_category}" "${truncate_bytes}" "${truncate_path}" "protected"
    return 0
  fi

  if [ "${DRY_RUN}" -eq 1 ]; then
    record "CANDIDATE" "${truncate_category}" "${truncate_bytes}" "${truncate_path}" "${truncate_detail}"
    return 0
  fi

  if : > "${truncate_path}" 2>/dev/null; then
    record "DELETE" "${truncate_category}" "${truncate_bytes}" "${truncate_path}" "truncated:${truncate_detail}"
    return 0
  fi

  record "FAIL" "${truncate_category}" "${truncate_bytes}" "${truncate_path}" "truncate_failed:${truncate_detail}"
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

truncate_if_old() {
  truncate_path="$1"
  truncate_detail="$2"
  find "${truncate_path}" -maxdepth 0 -type f -mtime "+${LOG_AGE_DAYS}" 2>/dev/null | while IFS= read -r old_log_path; do
    [ -n "${old_log_path}" ] || continue
    truncate_or_candidate "runtime_log" "${old_log_path}" "${truncate_detail}"
  done
}

root_recheck_ok() {
  if [ "${ROOT_RECHECK_COMMAND}" = "id -u" ]; then
    [ "$(id -u 2>/dev/null || echo 1)" = "0" ]
    return $?
  fi
  sh -c "${ROOT_RECHECK_COMMAND}" >/dev/null 2>&1
}

superuser_log_bytes() {
  total=0
  for superuser_log_path in "${SUPERUSER_LOG_DB}" "${SUPERUSER_LOG_DB}-wal" "${SUPERUSER_LOG_DB}-shm"; do
    if [ -e "${superuser_log_path}" ]; then
      path_bytes="$(bytes_of_path "${superuser_log_path}")"
      total=$((total + path_bytes))
    fi
  done
  echo "${total}"
}

cleanup_superuser_log_db() {
  if [ ! -e "${SUPERUSER_LOG_DB}" ] && [ ! -e "${SUPERUSER_LOG_DB}-wal" ] && [ ! -e "${SUPERUSER_LOG_DB}-shm" ]; then
    return 0
  fi

  superuser_bytes="$(superuser_log_bytes)"
  if is_protected "${SUPERUSER_LOG_DB}"; then
    record "SKIP" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "protected"
    return 0
  fi
  if ! find "${SUPERUSER_LOG_DB}" -maxdepth 0 -type f -mtime "+${LOG_AGE_DAYS}" 2>/dev/null | grep -q .; then
    record "SKIP" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "younger_than_retention"
    return 0
  fi

  if [ "${DRY_RUN}" -eq 1 ]; then
    record "CANDIDATE" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "sulogs_db"
    return 0
  fi

  am force-stop "${SUPERUSER_PACKAGE}" >/dev/null 2>&1 || true
  rm -f "${SUPERUSER_LOG_DB}" "${SUPERUSER_LOG_DB}-wal" "${SUPERUSER_LOG_DB}-shm" 2>/dev/null || true
  if ! root_recheck_ok; then
    record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "root_recheck_failed"
    return 0
  fi
  if [ -e "${SUPERUSER_LOG_DB}" ] || [ -e "${SUPERUSER_LOG_DB}-wal" ] || [ -e "${SUPERUSER_LOG_DB}-shm" ]; then
    record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "delete_failed"
    return 0
  fi

  record "DELETE" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "sulogs_db"
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

ARTIFACT_AGE_ARGS="-mtime +${ARTIFACT_AGE_DAYS}"
LOG_AGE_ARGS="-mtime +${LOG_AGE_DAYS}"

scan_find "tmp_artifact" "pixel_orchestrator_runtime_dir" "${LOCAL_TMP}" -mindepth 1 -maxdepth 1 -type d -name 'pixel-orchestrator-runtime-*' ${ARTIFACT_AGE_ARGS}
scan_find "tmp_artifact" "orchestrator_runtime_dir" "${LOCAL_TMP}" -mindepth 1 -maxdepth 1 -type d -name 'orchestrator-runtime-*' ${ARTIFACT_AGE_ARGS}
scan_find "tmp_artifact" "site_notifier_build_dir" "${LOCAL_TMP}" -mindepth 1 -maxdepth 1 -type d -name 'site-notifications-build*' ${ARTIFACT_AGE_ARGS}
scan_find "tmp_artifact" "ticket_capture_dir" "${LOCAL_TMP}" -mindepth 1 -maxdepth 1 -type d \( -name 'ticket-poll-*' -o -name 'ticket-capture-*' \) ${ARTIFACT_AGE_ARGS}
scan_find "tmp_artifact" "runtime_bundle" "${LOCAL_TMP}" -mindepth 1 -maxdepth 1 -type f \( \
  -name 'adguardhome-rootfs*.tar' -o \
  -name '*-rootfs-*.tar' -o \
  -name 'dropbear-bundle*.tar' -o \
  -name 'tailscale-bundle*.tar' -o \
  -name 'site-notifier-bundle*.tar' -o \
  -name 'source-site-notifier-*.tar' -o \
  -name 'train-bot-bundle*.tar' -o \
  -name 'satiksme-bot-bundle*.tar' -o \
  -name 'subscription-bot-bundle*.tar' -o \
  -name 'pixel-orchestrator-runtime-*.tar' \
\) ${ARTIFACT_AGE_ARGS}
scan_find "tmp_artifact" "debug_apk" "${LOCAL_TMP}" -mindepth 1 -maxdepth 1 -type f \( -name '*-debug.apk' -o -name 'app-debug.apk' -o -name 'pixel-orchestrator*.apk' \) ${ARTIFACT_AGE_ARGS}
scan_find "tmp_artifact" "ticket_capture_file" "${LOCAL_TMP}" -mindepth 1 -maxdepth 1 -type f \( -name 'ticket-poll-*' -o -name 'ticket-capture-*' -o -name 'pixel-ticket-*capture*' \) ${ARTIFACT_AGE_ARGS}

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

for runtime_log_path in \
  "${STACK_BASE}/vpn/logs/tailscaled.log" \
  "${STACK_BASE}/ssh/logs/dropbear.log" \
  "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log" \
  "${STACK_BASE}/apps/ticket-screen/logs/ticket-web-tunnel-service-loop.log"; do
  truncate_if_old "${runtime_log_path}" "known_runtime_log"
done

cleanup_superuser_log_db
