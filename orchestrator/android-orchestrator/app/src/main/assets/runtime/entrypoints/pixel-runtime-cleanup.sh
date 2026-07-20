#!/system/bin/sh
set -eu

STACK_BASE="/data/local/pixel-stack"
ORCHESTRATOR_CACHE="/data/user/0/lv.jolkins.pixelorchestrator/cache"
TERMUX_HOME="/data/user/0/com.termux/files/home"
LOCAL_TMP="/data/local/tmp"
SUPERUSER_LOG_DB="/data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db"
SUPERUSER_PACKAGE="giilmkonhuutj.ih.wb"
ROOT_RECHECK_COMMAND="su -c id -u"
PROTECTED_LIST=""
DRY_RUN=0
TERMUX_RETENTION_LIST=""
ARTIFACT_AGE_DAYS=30
LOG_AGE_DAYS=30
ACTION_RESULT_AGE_MINUTES=1440
SUPPORT_BUNDLE_AGE_MINUTES=1440
SUPERUSER_LOG_MAX_BYTES=33554432
KNOWN_LOG_MAX_BYTES=1048576
STACK_LOG_MAX_BYTES=33554432
RETIRED_DNS=0
SUPERUSER_ONLY=0
SUPERUSER_ROTATION_ACTIVE=0
SUPERUSER_ROTATION_BACKUP=""

rollback_superuser_log_db() {
  [ "${SUPERUSER_ROTATION_ACTIVE}" -eq 1 ] || return 0
  rollback_failed=0
  for rollback_suffix in "" "-wal" "-shm"; do
    rollback_backup="${SUPERUSER_ROTATION_BACKUP}${rollback_suffix}"
    rollback_target="${SUPERUSER_LOG_DB}${rollback_suffix}"
    if [ -e "${rollback_backup}" ]; then
      rm -f "${rollback_target}" 2>/dev/null || rollback_failed=1
      if ! mv "${rollback_backup}" "${rollback_target}" 2>/dev/null; then
        rollback_failed=1
      fi
    fi
  done
  SUPERUSER_ROTATION_ACTIVE=0
  [ "${rollback_failed}" -eq 0 ]
}

cleanup_tmp_files() {
  rollback_superuser_log_db >/dev/null 2>&1 || true
  [ -n "${TERMUX_RETENTION_LIST}" ] && rm -f "${TERMUX_RETENTION_LIST}" 2>/dev/null || true
}

trap cleanup_tmp_files EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

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
    --superuser-log-max-bytes)
      SUPERUSER_LOG_MAX_BYTES="${2:-}"
      shift 2
      ;;
    --known-log-max-bytes)
      KNOWN_LOG_MAX_BYTES="${2:-}"
      shift 2
      ;;
    --stack-log-max-bytes)
      STACK_LOG_MAX_BYTES="${2:-}"
      shift 2
      ;;
    --retired-dns)
      RETIRED_DNS=1
      shift
      ;;
    --superuser-only)
      SUPERUSER_ONLY=1
      shift
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
case "${SUPERUSER_LOG_MAX_BYTES}" in
  ''|*[!0-9]*) echo "superuser log max bytes must be a whole number" >&2; exit 2 ;;
esac
case "${KNOWN_LOG_MAX_BYTES}" in
  ''|*[!0-9]*) echo "known log max bytes must be a whole number" >&2; exit 2 ;;
esac
case "${STACK_LOG_MAX_BYTES}" in
  ''|*[!0-9]*) echo "stack log max bytes must be a whole number" >&2; exit 2 ;;
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

rotate_known_log_if_oversize() {
  rotate_path="$1"
  rotate_detail="$2"
  rotate_max_bytes="${3:-${KNOWN_LOG_MAX_BYTES}}"

  [ -f "${rotate_path}" ] || return 0
  rotate_bytes="$(bytes_of_path "${rotate_path}")"
  if [ "${rotate_bytes}" -le "${rotate_max_bytes}" ]; then
    return 0
  fi
  if is_protected "${rotate_path}"; then
    record "SKIP" "runtime_log" "${rotate_bytes}" "${rotate_path}" "protected"
    return 0
  fi
  if [ "${DRY_RUN}" -eq 1 ]; then
    record "CANDIDATE" "runtime_log" "${rotate_bytes}" "${rotate_path}" "oversize:${rotate_detail}"
    return 0
  fi

  rotate_tmp="${rotate_path}.pixel-cleanup-tmp"
  if tail -c "${rotate_max_bytes}" "${rotate_path}" > "${rotate_tmp}" 2>/dev/null &&
    mv -f "${rotate_tmp}" "${rotate_path}.1" 2>/dev/null &&
    : > "${rotate_path}" 2>/dev/null; then
    record "DELETE" "runtime_log" "$((rotate_bytes - rotate_max_bytes))" "${rotate_path}" "rotated:${rotate_detail}"
  else
    rm -f "${rotate_tmp}" 2>/dev/null || true
    record "FAIL" "runtime_log" "${rotate_bytes}" "${rotate_path}" "rotate_failed:${rotate_detail}"
  fi
}

remove_extra_known_log_rotations() {
  rotation_path="$1"
  shift
  for rotation_suffix in "$@"; do
    delete_or_candidate "runtime_log_rotation" "${rotation_path}${rotation_suffix}" "extra_rotation"
  done
}

bound_known_log_rotation() {
  rotation_path="$1"
  [ -f "${rotation_path}" ] || return 0
  rotation_bytes="$(bytes_of_path "${rotation_path}")"
  [ "${rotation_bytes}" -gt "${KNOWN_LOG_MAX_BYTES}" ] || return 0
  if is_protected "${rotation_path}"; then
    record "SKIP" "runtime_log_rotation" "${rotation_bytes}" "${rotation_path}" "protected"
    return 0
  fi
  if [ "${DRY_RUN}" -eq 1 ]; then
    record "CANDIDATE" "runtime_log_rotation" "${rotation_bytes}" "${rotation_path}" "oversize_rotation"
    return 0
  fi
  rotation_tmp="${rotation_path}.pixel-cleanup-tmp"
  if tail -c "${KNOWN_LOG_MAX_BYTES}" "${rotation_path}" > "${rotation_tmp}" 2>/dev/null &&
    mv -f "${rotation_tmp}" "${rotation_path}" 2>/dev/null; then
    record "DELETE" "runtime_log_rotation" "$((rotation_bytes - KNOWN_LOG_MAX_BYTES))" "${rotation_path}" "bounded_rotation"
  else
    rm -f "${rotation_tmp}" 2>/dev/null || true
    record "FAIL" "runtime_log_rotation" "${rotation_bytes}" "${rotation_path}" "rotation_bound_failed"
  fi
}

known_runtime_log_paths() {
  printf '%s\n' \
    "${STACK_BASE}/logs/adguardhome-service-loop.log" \
    "${STACK_BASE}/vpn/logs/pixel-vpn-service-loop.log" \
    "${STACK_BASE}/vpn/logs/tailscaled.log" \
    "${STACK_BASE}/ssh/logs/pixel-ssh-service-loop.log" \
    "${STACK_BASE}/ssh/logs/dropbear.log" \
    "${STACK_BASE}/logs/ddns-runner.log" \
    "${STACK_BASE}/apps/site-notifications/logs/daemon.log" \
    "${STACK_BASE}/apps/site-notifications/logs/service-loop.log" \
    "${STACK_BASE}/apps/subscription-bot/logs/subscription-bot.log" \
    "${STACK_BASE}/apps/subscription-bot/logs/subscription-bot-cloudflared.log" \
    "${STACK_BASE}/apps/subscription-bot/logs/subscription-web-tunnel-service-loop.log" \
    "${STACK_BASE}/apps/subscription-bot/logs/service-loop.log" \
    "${STACK_BASE}/apps/train-bot/logs/train-bot.log" \
    "${STACK_BASE}/apps/train-bot/logs/train-bot-cloudflared.log" \
    "${STACK_BASE}/apps/train-bot/logs/train-web-tunnel-service-loop.log" \
    "${STACK_BASE}/apps/train-bot/logs/service-loop.log" \
    "${STACK_BASE}/apps/satiksme-bot/logs/satiksme-bot.log" \
    "${STACK_BASE}/apps/satiksme-bot/logs/satiksme-bot-cloudflared.log" \
    "${STACK_BASE}/apps/satiksme-bot/logs/satiksme-web-tunnel-service-loop.log" \
    "${STACK_BASE}/apps/satiksme-bot/logs/service-loop.log" \
    "${STACK_BASE}/apps/ticket-screen/logs/ticket-screen-cloudflared.log" \
    "${STACK_BASE}/apps/ticket-screen/logs/ticket-web-tunnel-service-loop.log"
}

allowlisted_log_total_bytes() {
  allowlisted_total=0
  for allowlisted_path in $(known_runtime_log_paths); do
    allowlisted_total=$((allowlisted_total + $(bytes_of_path "${allowlisted_path}")))
    allowlisted_total=$((allowlisted_total + $(bytes_of_path "${allowlisted_path}.1")))
  done
  echo "${allowlisted_total}"
}

enforce_allowlisted_log_total() {
  total_bytes="$(allowlisted_log_total_bytes)"
  [ "${total_bytes}" -gt "${STACK_LOG_MAX_BYTES}" ] || return 0
  remaining_bytes="${total_bytes}"

  for allowlisted_path in $(known_runtime_log_paths); do
    [ "${remaining_bytes}" -gt "${STACK_LOG_MAX_BYTES}" ] || break
    rotation_path="${allowlisted_path}.1"
    rotation_bytes="$(bytes_of_path "${rotation_path}")"
    [ "${rotation_bytes}" -gt 0 ] || continue
    protected_rotation=0
    is_protected "${rotation_path}" && protected_rotation=1
    delete_or_candidate "runtime_log_total" "${rotation_path}" "over_total_limit_rotation"
    if [ "${protected_rotation}" -eq 0 ] && { [ "${DRY_RUN}" -eq 1 ] || [ ! -e "${rotation_path}" ]; }; then
      remaining_bytes=$((remaining_bytes - rotation_bytes))
    fi
  done
  for allowlisted_path in $(known_runtime_log_paths); do
    [ "${remaining_bytes}" -gt "${STACK_LOG_MAX_BYTES}" ] || break
    active_bytes="$(bytes_of_path "${allowlisted_path}")"
    [ "${active_bytes}" -gt 0 ] || continue
    protected_active=0
    is_protected "${allowlisted_path}" && protected_active=1
    truncate_or_candidate "runtime_log_total" "${allowlisted_path}" "over_total_limit_active"
    if [ "${protected_active}" -eq 0 ] && { [ "${DRY_RUN}" -eq 1 ] || [ "$(bytes_of_path "${allowlisted_path}")" -eq 0 ]; }; then
      remaining_bytes=$((remaining_bytes - active_bytes))
    fi
  done
}

root_recheck_ok() {
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

recover_interrupted_superuser_rotation() {
  interrupted_backup="${SUPERUSER_LOG_DB}.pixel-cleanup-backup"
  if [ ! -e "${interrupted_backup}" ] && [ ! -e "${interrupted_backup}-wal" ] && [ ! -e "${interrupted_backup}-shm" ]; then
    return 0
  fi
  SUPERUSER_ROTATION_BACKUP="${interrupted_backup}"
  SUPERUSER_ROTATION_ACTIVE=1
  if [ -e "${SUPERUSER_LOG_DB}" ] && root_recheck_ok; then
    SUPERUSER_ROTATION_ACTIVE=0
    if rm -f "${interrupted_backup}" "${interrupted_backup}-wal" "${interrupted_backup}-shm" 2>/dev/null; then
      record "DELETE" "superuser_log_db" "0" "${SUPERUSER_LOG_DB}" "reconciled_interrupted_success"
      return 0
    fi
    record "FAIL" "superuser_log_db" "0" "${SUPERUSER_LOG_DB}" "interrupted_backup_delete_failed"
    return 1
  fi
  if rollback_superuser_log_db; then
    record "SKIP" "superuser_log_db" "0" "${SUPERUSER_LOG_DB}" "recovered_interrupted_rotation"
    return 0
  fi
  record "FAIL" "superuser_log_db" "0" "${SUPERUSER_LOG_DB}" "interrupted_rotation_recovery_failed"
  return 1
}

cleanup_superuser_log_db() {
  recover_interrupted_superuser_rotation || return 0
  if [ ! -e "${SUPERUSER_LOG_DB}" ] && [ ! -e "${SUPERUSER_LOG_DB}-wal" ] && [ ! -e "${SUPERUSER_LOG_DB}-shm" ]; then
    return 0
  fi

  superuser_bytes="$(superuser_log_bytes)"
  if is_protected "${SUPERUSER_LOG_DB}"; then
    record "SKIP" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "protected"
    return 0
  fi
  if [ "${superuser_bytes}" -le "${SUPERUSER_LOG_MAX_BYTES}" ]; then
    record "SKIP" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "within_size_limit"
    return 0
  fi

  if [ "${DRY_RUN}" -eq 1 ]; then
    record "CANDIDATE" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "over_size_limit"
    return 0
  fi

  if ! root_recheck_ok; then
    record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "root_recheck_failed_before_rotation"
    return 0
  fi

  superuser_backup="${SUPERUSER_LOG_DB}.pixel-cleanup-backup"
  SUPERUSER_ROTATION_BACKUP="${superuser_backup}"
  am force-stop "${SUPERUSER_PACKAGE}" >/dev/null 2>&1 || true
  SUPERUSER_ROTATION_ACTIVE=1
  rotation_prepare_failed=0
  for rotation_suffix in "" "-wal" "-shm"; do
    rotation_source="${SUPERUSER_LOG_DB}${rotation_suffix}"
    rotation_backup="${superuser_backup}${rotation_suffix}"
    if [ -e "${rotation_source}" ] && ! mv "${rotation_source}" "${rotation_backup}" 2>/dev/null; then
      rotation_prepare_failed=1
      break
    fi
  done
  if [ "${rotation_prepare_failed}" -ne 0 ]; then
    if rollback_superuser_log_db; then
      record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "rotation_prepare_failed_rolled_back"
    else
      record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "rotation_prepare_and_rollback_failed"
    fi
    return 0
  fi
  if ! root_recheck_ok; then
    if rollback_superuser_log_db; then
      record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "root_recheck_failed_rolled_back"
    else
      record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "root_recheck_and_rollback_failed"
    fi
    return 0
  fi
  SUPERUSER_ROTATION_ACTIVE=0
  rm -f "${superuser_backup}" "${superuser_backup}-wal" "${superuser_backup}-shm" 2>/dev/null || true
  if [ -e "${superuser_backup}" ] || [ -e "${superuser_backup}-wal" ] || [ -e "${superuser_backup}-shm" ]; then
    record "FAIL" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "backup_delete_failed"
    return 0
  fi

  record "DELETE" "superuser_log_db" "${superuser_bytes}" "${SUPERUSER_LOG_DB}" "rotated_over_size_limit"
}

cleanup_retired_dns_logs() {
  [ "${RETIRED_DNS}" -eq 1 ] || return 0
  dns_root="${STACK_BASE}/chroots/adguardhome"
  dns_log_root="${dns_root}/var/log/adguardhome"
  dns_query_log="${dns_root}/opt/adguardhome/work/data/querylog.json"
  dns_state_query_log="${STACK_BASE}/state/adguardhome/work/data/querylog.json"
  pihole_root="${STACK_BASE}/chroots/pihole"
  pihole_log_root="${pihole_root}/var/log/pihole"
  pihole_rooted_log_root="${pihole_root}/var/log/pihole-rooted"

  if pidof AdGuardHome pihole-FTL dnsmasq >/dev/null 2>&1 ||
    pgrep -f '[A]dGuardHome|[p]ihole-FTL|[p]ihole-rooted' >/dev/null 2>&1; then
    record "SKIP" "retired_dns_log" "$(bytes_of_path "${dns_log_root}")" "${dns_log_root}" "runtime_still_active"
    return 0
  fi

  delete_or_candidate "retired_dns_log" "${dns_query_log}" "retired_query_history"
  delete_or_candidate "retired_dns_log" "${dns_query_log}.1" "retired_query_history_rotation"
  delete_or_candidate "retired_dns_log" "${dns_state_query_log}" "retired_query_history"
  delete_or_candidate "retired_dns_log" "${dns_state_query_log}.1" "retired_query_history_rotation"
  delete_or_candidate "retired_dns_log" "${STACK_BASE}/logs/adguardhome-runtime.log" "retired_service_runtime_log"
  delete_or_candidate "retired_dns_log" "${STACK_BASE}/logs/adguardhome-runtime.log.1" "retired_service_runtime_log_rotation"
  delete_or_candidate "retired_dns_log" "${STACK_BASE}/logs/adguardhome-service-loop.log" "retired_service_loop_log"
  delete_or_candidate "retired_dns_log" "${STACK_BASE}/logs/adguardhome-service-loop.log.1" "retired_service_loop_log_rotation"
  for dns_log_name in \
    adguardhome-runtime.log \
    adguardhome.log \
    doh-server.log \
    nginx-access.log \
    nginx-error.log \
    nginx-doh-access.log \
    remote-watchdog.log \
    remote-nginx-access.log \
    remote-nginx-error.log \
    remote-nginx-doh-access.log \
    remote-nginx-dot-access.log \
    doh-identity-web.log \
    train-bot-cloudflared.log; do
    delete_or_candidate "retired_dns_log" "${dns_log_root}/${dns_log_name}" "retired_disabled_service_log"
    delete_or_candidate "retired_dns_log" "${dns_log_root}/${dns_log_name}.1" "retired_disabled_service_log_rotation"
  done
  scan_find "retired_dns_log" "retired_start_attempt_log" "${dns_log_root}" -mindepth 1 -maxdepth 1 -type f -name 'adguardhome-start-attempt-*.log'
  for pihole_log_path in \
    "${pihole_log_root}/pihole.log" \
    "${pihole_log_root}/pihole.log.1" \
    "${pihole_log_root}/FTL.log" \
    "${pihole_log_root}/FTL.log.1" \
    "${pihole_rooted_log_root}/doh-server.log" \
    "${pihole_rooted_log_root}/doh-server.log.1" \
    "${pihole_rooted_log_root}/nginx-access.log" \
    "${pihole_rooted_log_root}/nginx-access.log.1" \
    "${pihole_rooted_log_root}/nginx-doh-access.log" \
    "${pihole_rooted_log_root}/nginx-doh-access.log.1" \
    "${pihole_rooted_log_root}/nginx-error.log" \
    "${pihole_rooted_log_root}/nginx-error.log.1"; do
    delete_or_candidate "retired_dns_log" "${pihole_log_path}" "retired_disabled_service_log"
  done
}

if [ "${SUPERUSER_ONLY}" -eq 1 ]; then
  cleanup_superuser_log_db
  exit 0
fi

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
scan_find "support_bundle" "stale_private_support_archive" "${ORCHESTRATOR_CACHE}/support-bundles" -mindepth 1 -maxdepth 1 -type f \( -name 'pixel-stack-support-*.zip' -o -name '*.zip.tmp' \) -mmin +${SUPPORT_BUNDLE_AGE_MINUTES}

scan_find "runtime_artifact" "runtime_manifest_artifacts" "${STACK_BASE}/conf/runtime/artifacts" -mindepth 1 -maxdepth 1 -type f ${ARTIFACT_AGE_ARGS}
scan_find "runtime_artifact" "unreferenced_canonical_artifact" "${STACK_BASE}/conf/runtime/artifacts/sha256" -mindepth 1 -maxdepth 1 -type f ${ARTIFACT_AGE_ARGS}
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

scan_find "action_result" "unconsumed_action_result" "${STACK_BASE}/run/orchestrator-action-results" -mindepth 1 -maxdepth 1 -type f -name '*.json' -mmin +${ACTION_RESULT_AGE_MINUTES}
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

for runtime_log_path in $(known_runtime_log_paths); do
  rotate_known_log_if_oversize "${runtime_log_path}" "known_runtime_log"
  bound_known_log_rotation "${runtime_log_path}.1"
  remove_extra_known_log_rotations "${runtime_log_path}" ".2" ".3" ".old"
  scan_find "runtime_log_rotation" "extra_backup_rotation" "$(dirname "${runtime_log_path}")" \
    -mindepth 1 -maxdepth 1 -type f -name "$(basename "${runtime_log_path}").bak*"
done
enforce_allowlisted_log_total

cleanup_superuser_log_db
cleanup_retired_dns_logs
