#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack"
BIN_DIR="${BASE}/bin"
TPL_DIR="${BASE}/templates/rooted"
CONF_FILE="${BASE}/conf/adguardhome.env"
STATE_DIR="${BASE}/state/adguardhome"
PERSISTENT_CONF_DIR="${STATE_DIR}/conf"
PERSISTENT_WORK_DIR="${STATE_DIR}/work"
RUN_DIR="${BASE}/run"
LOG_DIR="${BASE}/logs"
PID_FILE="${RUN_DIR}/adguardhome-service-loop.pid"
LOCK_DIR="${RUN_DIR}/adguardhome-service-loop.lock"
START_LOCK_DIR="${RUN_DIR}/adguardhome-start.lock"
LOOP_BIN="${BIN_DIR}/adguardhome-service-loop"

ADGUARDHOME_ROOTFS_PATH="/data/local/pixel-stack/chroots/adguardhome"
if [ -r "${CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${CONF_FILE}"
  set +a
fi

mkdir -p "${BIN_DIR}" "${RUN_DIR}" "${LOG_DIR}" "${STATE_DIR}" \
  "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin" \
  "${ADGUARDHOME_ROOTFS_PATH}/etc/pixel-stack/remote-dns" \
  "${ADGUARDHOME_ROOTFS_PATH}/etc/pixel-stack/remote-dns/state" \
  "${ADGUARDHOME_ROOTFS_PATH}/opt/adguardhome/conf" \
  "${ADGUARDHOME_ROOTFS_PATH}/opt/adguardhome/work"

hash_file() {
  file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file}" | awk '{print $1}'
  elif command -v toybox >/dev/null 2>&1; then
    toybox sha256sum "${file}" | awk '{print $1}'
  else
    cksum "${file}" | awk '{print $1 ":" $2}'
  fi
}

stage_template() {
  local src="$1"
  local dst="$2"
  local mode="$3"
  local tmp src_hash dst_hash
  if [ -f "${TPL_DIR}/${src}" ]; then
    tmp="${dst}.tmp.$$"
    cp "${TPL_DIR}/${src}" "${tmp}"
    chmod "${mode}" "${tmp}"
    src_hash="$(hash_file "${TPL_DIR}/${src}")"
    dst_hash="$(hash_file "${tmp}")"
    if [ "${src_hash}" != "${dst_hash}" ]; then
      rm -f "${tmp}" >/dev/null 2>&1 || true
      echo "staged asset hash mismatch: ${TPL_DIR}/${src} -> ${dst}" >&2
      exit 1
    fi
    mv -f "${tmp}" "${dst}"
  fi
}

stage_template "adguardhome-start" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-start" 0755
stage_template "adguardhome-render-config" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-render-config" 0755
stage_template "adguardhome-launch-core" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-launch-core" 0755
stage_template "adguardhome-launch-frontend" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-launch-frontend" 0755
stage_template "adguardhome-stop" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-stop" 0755
stage_template "adguardhome-remote-acme.sh" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-remote-acme" 0755
stage_template "adguardhome-remote-watchdog" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-remote-watchdog" 0755
stage_template "adguardhome-migrate.py" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-migrate.py" 0755
stage_template "adguardhome-doh-identities.py" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-doh-identities.py" 0755
stage_template "adguardhome-doh-identityctl" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-doh-identityctl" 0755
stage_template "adguardhome-doh-identity-web.py" "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-doh-identity-web.py" 0755
stage_template "pixel-dns-identityctl" "${BIN_DIR}/pixel-dns-identityctl" 0755
stage_template "adguardhome-remote-nginx.conf.template" "${ADGUARDHOME_ROOTFS_PATH}/etc/pixel-stack/remote-dns/adguardhome-remote-nginx.conf.template" 0644
stage_template "adguardhome-service-loop" "${LOOP_BIN}" 0755

chroot_bash_path() {
  if [ -x "${ADGUARDHOME_ROOTFS_PATH}/bin/bash" ]; then
    printf '/bin/bash\n'
    return 0
  fi
  if [ -x "${ADGUARDHOME_ROOTFS_PATH}/usr/bin/bash" ]; then
    printf '/usr/bin/bash\n'
    return 0
  fi
  return 1
}

mount_is_present() {
  local target="$1"
  grep -F " ${target} " /proc/mounts >/dev/null 2>&1
}

mount_source_for_target() {
  local target="$1"
  awk -v target="${target}" '$2 == target { print $1; exit }' /proc/mounts 2>/dev/null
}

directory_has_entries() {
  local dir="$1"
  [ -d "${dir}" ] || return 1
  find "${dir}" -mindepth 1 -maxdepth 1 | read -r _
}

seed_persistent_dir_from_chroot() {
  local persistent_dir="$1"
  local chroot_dir="$2"

  mkdir -p "${persistent_dir}" "${chroot_dir}"
  if mount_is_present "${chroot_dir}"; then
    return 0
  fi
  if directory_has_entries "${persistent_dir}"; then
    return 0
  fi
  if ! directory_has_entries "${chroot_dir}"; then
    return 0
  fi

  cp -a "${chroot_dir}/." "${persistent_dir}/"
}

ensure_bind_mount() {
  local source_dir="$1"
  local target_dir="$2"
  local mounted_source=""

  mkdir -p "${source_dir}" "${target_dir}"
  mounted_source="$(mount_source_for_target "${target_dir}" || true)"
  if [ "${mounted_source}" = "${source_dir}" ]; then
    return 0
  fi
  if mount_is_present "${target_dir}"; then
    umount "${target_dir}" >/dev/null 2>&1 || umount -l "${target_dir}" >/dev/null 2>&1 || true
  fi
  mount -o bind "${source_dir}" "${target_dir}"
}

mount_persistent_adguardhome_state() {
  local chroot_conf_dir="${ADGUARDHOME_ROOTFS_PATH}/opt/adguardhome/conf"
  local chroot_work_dir="${ADGUARDHOME_ROOTFS_PATH}/opt/adguardhome/work"

  seed_persistent_dir_from_chroot "${PERSISTENT_CONF_DIR}" "${chroot_conf_dir}"
  seed_persistent_dir_from_chroot "${PERSISTENT_WORK_DIR}" "${chroot_work_dir}"
  ensure_bind_mount "${PERSISTENT_CONF_DIR}" "${chroot_conf_dir}"
  ensure_bind_mount "${PERSISTENT_WORK_DIR}" "${chroot_work_dir}"
}

validate_host_bash_script() {
  local file="$1"
  local bash_path
  bash_path="$(chroot_bash_path)"
  chroot "${ADGUARDHOME_ROOTFS_PATH}" "${bash_path}" -n < "${file}"
}

validate_chroot_bash_script() {
  local chroot_file="$1"
  local bash_path
  bash_path="$(chroot_bash_path)"
  chroot "${ADGUARDHOME_ROOTFS_PATH}" "${bash_path}" -n "${chroot_file}"
}

preflight_runtime_assets() {
  validate_host_bash_script "${LOOP_BIN}"
  validate_host_bash_script "${BIN_DIR}/pixel-dns-identityctl"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-start"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-render-config"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-launch-core"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-launch-frontend"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-stop"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-remote-acme"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-remote-watchdog"
  validate_chroot_bash_script "/usr/local/bin/adguardhome-doh-identityctl"
  chroot "${ADGUARDHOME_ROOTFS_PATH}" /usr/local/bin/adguardhome-render-config >> "${LOG_DIR}/adguardhome-runtime.log" 2>&1
}

sync_ddns_last_ipv4_file() {
  local src="${RUN_DIR}/ddns-last-ipv4"
  local dst="${ADGUARDHOME_ROOTFS_PATH}/etc/pixel-stack/remote-dns/state/ddns-last-ipv4"
  if [ -s "${src}" ]; then
    cp "${src}" "${dst}"
    chmod 600 "${dst}" >/dev/null 2>&1 || true
  else
    rm -f "${dst}" >/dev/null 2>&1 || true
  fi
}

kill_matching() {
  local pattern="$1"
  local pids=""

  if command -v pkill >/dev/null 2>&1; then
    pkill -f "${pattern}" >/dev/null 2>&1 || true
  fi

  if command -v pgrep >/dev/null 2>&1; then
    pids="$(pgrep -f "${pattern}" 2>/dev/null || true)"
  elif command -v ps >/dev/null 2>&1; then
    pids="$(ps -A -o PID,ARGS 2>/dev/null | grep -F "${pattern}" | grep -v -F "grep -F ${pattern}" | awk '{print $1}')"
  fi

  for pid in ${pids}; do
    [ -n "${pid}" ] || continue
    kill "${pid}" >/dev/null 2>&1 || true
    sleep 1
    kill -9 "${pid}" >/dev/null 2>&1 || true
  done
}

listener_pids_for_port() {
  port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -H -ltnp 2>/dev/null | awk -v p="${port}" '
      {
        addr=$4
        if (addr ~ "\\]:" p "$" || addr ~ ":" p "$") {
          print $0
        }
      }
    ' | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | sort -u
  fi
}

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
  fi
}

cleanup_stack_listener_port() {
  port="$1"
  had=0
  for pid in $(listener_pids_for_port "${port}" 2>/dev/null || true); do
    [ -n "${pid}" ] || continue
    had=1
    cmd="$(pid_cmdline "${pid}")"
    case "${cmd}" in
      *AdGuardHome*|*adguardhome*|*pixel-stack*|*nginx*)
        kill "${pid}" >/dev/null 2>&1 || true
        sleep 1
        kill -9 "${pid}" >/dev/null 2>&1 || true
        ;;
      *)
        # Leave non-stack services untouched.
        ;;
    esac
  done
  if [ "${had}" -eq 1 ]; then
    sleep 1
  fi
}

cleanup_legacy_pihole_runtime() {
  local legacy_rootfs="${BASE}/chroots/pihole"
  local legacy_pid_file="${RUN_DIR}/pihole-service-loop.pid"
  local legacy_lock_dir="${RUN_DIR}/pihole-rooted-service-loop.lock"

  if [ -f "${legacy_pid_file}" ]; then
    old_pid="$(cat "${legacy_pid_file}" 2>/dev/null || true)"
    if [ -n "${old_pid}" ] && kill -0 "${old_pid}" >/dev/null 2>&1; then
      kill "${old_pid}" >/dev/null 2>&1 || true
      sleep 1
      kill -9 "${old_pid}" >/dev/null 2>&1 || true
    fi
    rm -f "${legacy_pid_file}" >/dev/null 2>&1 || true
  fi

  if [ -x "${legacy_rootfs}/usr/local/bin/pihole-stop" ]; then
    chroot "${legacy_rootfs}" /usr/local/bin/pihole-stop >/dev/null 2>&1 || true
  fi

  rm -f "${RUN_DIR}/pihole-rooted-host.pid" >/dev/null 2>&1 || true
  rmdir "${legacy_lock_dir}" >/dev/null 2>&1 || rm -rf "${legacy_lock_dir}" >/dev/null 2>&1 || true
  rmdir "${RUN_DIR}/pihole-service-loop.lock" >/dev/null 2>&1 || rm -rf "${RUN_DIR}/pihole-service-loop.lock" >/dev/null 2>&1 || true

  kill_matching 'pihole-service-loop'
  kill_matching 'pihole-FTL'
  kill_matching 'dnscrypt-proxy'
  kill_matching 'pihole-doh-gateway'
  kill_matching 'pihole-remote-auth-gateway'
  kill_matching 'pihole-remote-watchdog'
  kill_matching 'nginx.*pixel-stack-pihole-remote'
}

cleanup_legacy_pihole_runtime
# Clear stale stack-owned remote HTTPS listener collisions before starting
# the DNS runtime in chroot.
cleanup_stack_listener_port "${PIHOLE_REMOTE_HTTPS_PORT:-443}"
sync_ddns_last_ipv4_file

# Serialize startup to avoid concurrent callers racing into parallel
# adguardhome-service-loop spawns.
if ! mkdir "${START_LOCK_DIR}" >/dev/null 2>&1; then
  exit 0
fi
cleanup_start_lock() {
  rmdir "${START_LOCK_DIR}" >/dev/null 2>&1 || rm -rf "${START_LOCK_DIR}" >/dev/null 2>&1 || true
}
trap cleanup_start_lock EXIT INT TERM

if [ -f "${PID_FILE}" ]; then
  old_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [ -n "${old_pid}" ] && kill -0 "${old_pid}" >/dev/null 2>&1; then
    exit 0
  fi
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
fi

if [ -d "${LOCK_DIR}" ]; then
  stale_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if command -v pgrep >/dev/null 2>&1; then
    if pgrep -f "${LOOP_BIN}" >/dev/null 2>&1; then
      exit 0
    fi
  elif command -v ps >/dev/null 2>&1; then
    if ps -A -o ARGS 2>/dev/null | grep -F "${LOOP_BIN}" | grep -v -F "grep -F ${LOOP_BIN}" >/dev/null; then
      exit 0
    fi
  fi
  if [ -z "${stale_pid}" ] || ! kill -0 "${stale_pid}" >/dev/null 2>&1; then
    rm -f "${PID_FILE}" >/dev/null 2>&1 || true
    rmdir "${LOCK_DIR}" >/dev/null 2>&1 || rm -rf "${LOCK_DIR}" >/dev/null 2>&1 || true
  fi
fi

# Extra idempotency guard: if a service-loop process is already active,
# skip spawning a parallel loop even if pid/lock files drifted.
if command -v pgrep >/dev/null 2>&1; then
  if pgrep -f "${LOOP_BIN}" >/dev/null 2>&1; then
    exit 0
  fi
elif command -v ps >/dev/null 2>&1; then
  if ps -A -o ARGS 2>/dev/null | grep -F "${LOOP_BIN}" | grep -v -F "grep -F ${LOOP_BIN}" >/dev/null; then
    exit 0
  fi
fi

if [ ! -x "${LOOP_BIN}" ]; then
  echo "missing loop binary: ${LOOP_BIN}" >&2
  exit 1
fi

if [ ! -x "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-start" ] || \
   [ ! -x "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-render-config" ] || \
   [ ! -x "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-launch-core" ] || \
   [ ! -x "${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin/adguardhome-launch-frontend" ]; then
  echo "missing chroot runtime helper(s) under ${ADGUARDHOME_ROOTFS_PATH}/usr/local/bin" >&2
  exit 1
fi

if [ ! -x "${ADGUARDHOME_ROOTFS_PATH}/bin/bash" ] && [ ! -x "${ADGUARDHOME_ROOTFS_PATH}/usr/bin/bash" ]; then
  echo "invalid rooted AdGuardHome rootfs (missing bash in /bin or /usr/bin); rerun bootstrap/rootfs install" >&2
  exit 1
fi

if [ ! -e "${ADGUARDHOME_ROOTFS_PATH}/bin" ] && [ -d "${ADGUARDHOME_ROOTFS_PATH}/usr/bin" ]; then
  ln -s usr/bin "${ADGUARDHOME_ROOTFS_PATH}/bin" >/dev/null 2>&1 || true
fi
if [ ! -e "${ADGUARDHOME_ROOTFS_PATH}/lib" ] && [ -d "${ADGUARDHOME_ROOTFS_PATH}/usr/lib" ]; then
  ln -s usr/lib "${ADGUARDHOME_ROOTFS_PATH}/lib" >/dev/null 2>&1 || true
fi

mount_persistent_adguardhome_state
preflight_runtime_assets

nohup "${LOOP_BIN}" >>"${LOG_DIR}/adguardhome-service-loop.log" 2>&1 &
pid="$!"
sleep 2
if [ -z "${pid}" ] || ! kill -0 "${pid}" >/dev/null 2>&1; then
  rm -f "${PID_FILE}" >/dev/null 2>&1 || true
  exit 1
fi

exit 0
