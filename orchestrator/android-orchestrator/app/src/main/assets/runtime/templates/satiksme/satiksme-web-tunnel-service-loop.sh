#!/system/bin/sh
set -eu

SATIKSME_BOT_ROOT="${SATIKSME_BOT_ROOT:-/data/local/pixel-stack/apps/satiksme-bot}"
ENV_FILE="${SATIKSME_BOT_ROOT}/env/satiksme-bot.env"
BIN_DIR="${SATIKSME_BOT_ROOT}/bin"
RUN_DIR="${SATIKSME_BOT_ROOT}/run"
LOG_DIR="${SATIKSME_BOT_ROOT}/logs"
STATE_DIR="${SATIKSME_BOT_ROOT}/state/satiksme-web-tunnel"
SERVICE_LOG="${LOG_DIR}/satiksme-web-tunnel-service-loop.log"
LOOP_NAME="satiksme-web-tunnel-service-loop"
LOCK_DIR="${RUN_DIR}/${LOOP_NAME}.lock"
LOOP_PID_FILE="${RUN_DIR}/${LOOP_NAME}.pid"
CLOUDFLARED_BIN="${BIN_DIR}/cloudflared"
CLOUDFLARED_PID_FILE="${RUN_DIR}/satiksme-bot-cloudflared.pid"
CLOUDFLARED_LOG_FILE="${LOG_DIR}/satiksme-bot-cloudflared.log"
CLOUDFLARED_CONFIG_FILE="${STATE_DIR}/satiksme-bot-cloudflared.yml"
CLOUDFLARED_VERSION="${CLOUDFLARED_VERSION:-2026.2.0}"
CLOUDFLARED_SHA256="${CLOUDFLARED_SHA256:-03c5d58e283f521d752dc4436014eb341092edf076eb1095953ab82debe54a8e}"
CLOUDFLARED_DOWNLOAD_URL="${CLOUDFLARED_DOWNLOAD_URL:-https://github.com/cloudflare/cloudflared/releases/download/${CLOUDFLARED_VERSION}/cloudflared-linux-arm64}"
CLOUDFLARED_METRICS_ADDR="${CLOUDFLARED_METRICS_ADDR:-127.0.0.1:20257}"
CURL_FALLBACK_BIN="/data/local/pixel-stack/bin/curl"
LEGACY_CLOUDFLARED_BIN="${LEGACY_CLOUDFLARED_BIN:-/usr/local/bin/cloudflared}"
ROOTFS_CURL_ROOT="${ROOTFS_CURL_ROOT:-/data/local/pixel-stack/chroots/adguardhome}"

mkdir -p "${RUN_DIR}" "${LOG_DIR}" "${STATE_DIR}"

load_env_file() {
  env_path="$1"
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      ''|'#'*) continue ;;
      *=*) ;;
      *) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "${key}" in
      [A-Za-z_][A-Za-z0-9_]*) export "${key}=${value}" ;;
      *) continue ;;
    esac
  done < "${env_path}"
}

if [ -r "${ENV_FILE}" ]; then
  load_env_file "${ENV_FILE}"
fi

: "${SATIKSME_WEB_TUNNEL_LOOP_POLL_SEC:=15}"
: "${SATIKSME_WEB_TUNNEL_PUBLIC_FAIL_LIMIT:=3}"
: "${SATIKSME_WEB_TUNNEL_PUBLIC_PROBE_TIMEOUT_SEC:=8}"
: "${SATIKSME_WEB_TUNNEL_START_GRACE_SEC:=2}"
: "${SATIKSME_WEB_PORT:=9327}"
: "${SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE:=/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json}"

ts() { date '+%Y-%m-%dT%H:%M:%S%z'; }
log() { printf '[%s] %s\n' "$(ts)" "$*" >> "${SERVICE_LOG}"; }

is_true() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

pid_cmdline() {
  pid="$1"
  if [ -r "/proc/${pid}/cmdline" ]; then
    tr '\000' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true
    return 0
  fi
  if command -v ps >/dev/null 2>&1; then
    ps -p "${pid}" -o command= 2>/dev/null || true
    return 0
  fi
  return 1
}

pid_matches_target() {
  pid="$1"
  target="$2"
  if [ -z "${pid}" ] || ! kill -0 "${pid}" >/dev/null 2>&1; then
    return 1
  fi
  cmdline="$(pid_cmdline "${pid}" || true)"
  target_base="$(basename "${target}")"
  case "${cmdline}" in
    *"${target}"*|*" ${target_base} "*|"${target_base}"|*" ${target_base}") return 0 ;;
    *) return 1 ;;
  esac
}

pid_matches_loop() {
  pid_matches_target "${1:-}" "${SATIKSME_BOT_ROOT}/bin/${LOOP_NAME}"
}

read_pid_file() {
  pid_file="$1"
  if [ -r "${pid_file}" ]; then
    sed -n '1p' "${pid_file}" 2>/dev/null | tr -d '\r'
  fi
}

list_matching_pids() {
  target="$1"
  ps -A -o PID,ARGS 2>/dev/null | awk -v target="${target}" '
    index($0, target) > 0 { print $1 }
  '
}

kill_pid_and_wait() {
  pid="$1"
  [ -n "${pid}" ] || return 0
  kill "${pid}" >/dev/null 2>&1 || true

  attempts=0
  while [ "${attempts}" -lt 10 ]; do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      return 0
    fi
    attempts=$((attempts + 1))
    sleep 1
  done

  kill -9 "${pid}" >/dev/null 2>&1 || true
  sleep 1
}

acquire_lock() {
  if mkdir "${LOCK_DIR}" >/dev/null 2>&1; then
    return 0
  fi

  pid="$(read_pid_file "${LOOP_PID_FILE}" || true)"
  if pid_matches_loop "${pid}"; then
    log "another ${LOOP_NAME} instance is already running (pid=${pid})"
    return 1
  fi

  log "stale ${LOOP_NAME} lock detected; resetting lock state"
  rm -f "${LOOP_PID_FILE}" >/dev/null 2>&1 || true
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
  mkdir "${LOCK_DIR}" >/dev/null 2>&1 || return 1
  return 0
}

release_lock() {
  rm -f "${LOOP_PID_FILE}" >/dev/null 2>&1 || true
  rmdir "${LOCK_DIR}" >/dev/null 2>&1 || true
}

cloudflared_file_sha256() {
  file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${file}" | awk '{print $1}'
    return 0
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "${file}" | awk '{print $1}'
    return 0
  fi
  return 1
}

download_cloudflared_binary() {
  output_path="$1"
  url="$2"

  if command -v curl >/dev/null 2>&1 && curl -V >/dev/null 2>&1; then
    curl -fL --retry 2 --connect-timeout 10 --max-time 180 \
      -o "${output_path}" "${url}" >> "${CLOUDFLARED_LOG_FILE}" 2>&1
    return $?
  fi

  if command -v wget >/dev/null 2>&1; then
    wget -O "${output_path}" "${url}" >> "${CLOUDFLARED_LOG_FILE}" 2>&1
    return $?
  fi

  if command -v toybox >/dev/null 2>&1 && toybox wget --help >/dev/null 2>&1; then
    toybox wget -O "${output_path}" "${url}" >> "${CLOUDFLARED_LOG_FILE}" 2>&1
    return $?
  fi

  if [ -x "${CURL_FALLBACK_BIN}" ] && "${CURL_FALLBACK_BIN}" -V >/dev/null 2>&1; then
    "${CURL_FALLBACK_BIN}" -fL --retry 2 --connect-timeout 10 --max-time 180 \
      -o "${output_path}" "${url}" >> "${CLOUDFLARED_LOG_FILE}" 2>&1
    return $?
  fi

  if [ -n "${ROOTFS_CURL_ROOT}" ] \
    && [ -x "${ROOTFS_CURL_ROOT}/usr/bin/curl" ] \
    && [ -x "${ROOTFS_CURL_ROOT}/usr/bin/env" ] \
    && chroot "${ROOTFS_CURL_ROOT}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
    rootfs_tmp_rel="/tmp/satiksme-bot-cloudflared-download.$$"
    rootfs_tmp_host="${ROOTFS_CURL_ROOT}${rootfs_tmp_rel}"
    rm -f "${rootfs_tmp_host}" >/dev/null 2>&1 || true
    if chroot "${ROOTFS_CURL_ROOT}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      /usr/bin/curl -fL --retry 2 --connect-timeout 10 --max-time 180 \
      -o "${rootfs_tmp_rel}" "${url}" >> "${CLOUDFLARED_LOG_FILE}" 2>&1; then
      cp "${rootfs_tmp_host}" "${output_path}"
      cp_rc=$?
      rm -f "${rootfs_tmp_host}" >/dev/null 2>&1 || true
      return "${cp_rc}"
    fi
    rm -f "${rootfs_tmp_host}" >/dev/null 2>&1 || true
  fi

  return 127
}

install_cloudflared_binary() {
  if [ -x "${CLOUDFLARED_BIN}" ]; then
    return 0
  fi

  if [ -x "${LEGACY_CLOUDFLARED_BIN}" ] && \
    ( "${LEGACY_CLOUDFLARED_BIN}" --version >/dev/null 2>&1 || "${LEGACY_CLOUDFLARED_BIN}" -v >/dev/null 2>&1 ); then
    mkdir -p "${BIN_DIR}"
    cp "${LEGACY_CLOUDFLARED_BIN}" "${CLOUDFLARED_BIN}"
    chmod 0755 "${CLOUDFLARED_BIN}"
    log "seeded cloudflared from legacy binary ${LEGACY_CLOUDFLARED_BIN}"
    return 0
  fi

  tmp_dir="$(mktemp -d)"
  downloaded_bin="${tmp_dir}/cloudflared-linux-arm64"
  set +e
  download_cloudflared_binary "${downloaded_bin}" "${CLOUDFLARED_DOWNLOAD_URL}"
  download_rc=$?
  set -e
  if [ "${download_rc}" -ne 0 ]; then
    log "failed to download cloudflared from ${CLOUDFLARED_DOWNLOAD_URL} (rc=${download_rc})"
    rm -rf "${tmp_dir}"
    return 1
  fi

  actual_sha="$(cloudflared_file_sha256 "${downloaded_bin}" 2>/dev/null || true)"
  if [ -z "${actual_sha}" ] || [ "${actual_sha}" != "${CLOUDFLARED_SHA256}" ]; then
    log "cloudflared checksum mismatch (expected=${CLOUDFLARED_SHA256} got=${actual_sha:-unknown})"
    rm -rf "${tmp_dir}"
    return 1
  fi

  mkdir -p "${BIN_DIR}"
  cp "${downloaded_bin}" "${CLOUDFLARED_BIN}"
  chmod 0755 "${CLOUDFLARED_BIN}"
  rm -rf "${tmp_dir}"
  return 0
}

render_cloudflared_config() {
  public_base_url="${SATIKSME_WEB_PUBLIC_BASE_URL:-}"
  credentials_file="${SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE:-}"
  if [ -z "${public_base_url}" ] || [ -z "${credentials_file}" ]; then
    log "missing SATIKSME_WEB_PUBLIC_BASE_URL or SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE"
    return 1
  fi
  if [ ! -r "${credentials_file}" ]; then
    log "cloudflared tunnel credentials file missing: ${credentials_file}"
    return 1
  fi

  tunnel_id="$(tr -d '\n\r' < "${credentials_file}" 2>/dev/null | sed -n 's/.*"TunnelID"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  if [ -z "${tunnel_id}" ]; then
    log "cloudflared tunnel credentials file did not contain TunnelID: ${credentials_file}"
    return 1
  fi

  hostname="$(printf '%s' "${public_base_url}" | sed -E 's#^https?://([^/]+)/?.*$#\1#')"
  cat > "${CLOUDFLARED_CONFIG_FILE}" <<EOF_SATIKSME_CLOUDFLARED
tunnel: ${tunnel_id}
credentials-file: ${credentials_file}
metrics: ${CLOUDFLARED_METRICS_ADDR}
ingress:
  - hostname: ${hostname}
    service: http://127.0.0.1:${SATIKSME_WEB_PORT}
  - service: http_status:404
EOF_SATIKSME_CLOUDFLARED
}

start_cloudflared() {
  if ! install_cloudflared_binary; then
    return 1
  fi
  if ! render_cloudflared_config; then
    return 1
  fi

  log "starting cloudflared for satiksme bot hostname=${SATIKSME_WEB_PUBLIC_BASE_URL:-} config=${CLOUDFLARED_CONFIG_FILE} credentials=${SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE:-}"
  nohup "${CLOUDFLARED_BIN}" tunnel --config "${CLOUDFLARED_CONFIG_FILE}" run >> "${CLOUDFLARED_LOG_FILE}" 2>&1 &
  cloudflared_pid="$!"
  if [ -n "${cloudflared_pid}" ] && kill -0 "${cloudflared_pid}" >/dev/null 2>&1; then
    echo "${cloudflared_pid}" > "${CLOUDFLARED_PID_FILE}"
    sleep "${SATIKSME_WEB_TUNNEL_START_GRACE_SEC}"
    return 0
  fi
  log "cloudflared exited immediately for satiksme bot config=${CLOUDFLARED_CONFIG_FILE} credentials=${SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE:-} log=${CLOUDFLARED_LOG_FILE}"
  rm -f "${CLOUDFLARED_PID_FILE}" >/dev/null 2>&1 || true
  return 1
}

stop_cloudflared() {
  pid="$(read_pid_file "${CLOUDFLARED_PID_FILE}" || true)"
  if [ -n "${pid}" ]; then
    kill_pid_and_wait "${pid}"
  fi
  rm -f "${CLOUDFLARED_PID_FILE}" >/dev/null 2>&1 || true
  for pid in $(list_matching_pids "${CLOUDFLARED_CONFIG_FILE}"); do
    kill_pid_and_wait "${pid}"
  done
}

resolve_probe_curl() {
  if command -v curl >/dev/null 2>&1; then
    printf 'native:%s\n' "$(command -v curl 2>/dev/null || true)"
    return 0
  fi
  if [ -n "${ROOTFS_CURL_ROOT}" ] && [ -x "${ROOTFS_CURL_ROOT}/usr/bin/curl" ] && [ -x "${ROOTFS_CURL_ROOT}/usr/bin/env" ] && chroot "${ROOTFS_CURL_ROOT}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
    printf 'chroot:%s\n' "${ROOTFS_CURL_ROOT}"
    return 0
  fi
  return 1
}

probe_http_code() {
  probe_spec="$1"
  probe_url="$2"
  probe_timeout="$3"
  case "${probe_spec}" in
    native:*)
      probe_bin="${probe_spec#native:}"
      "${probe_bin}" -ksS -o /dev/null -w '%{http_code}' --max-time "${probe_timeout}" "${probe_url}" 2>/dev/null || true
      ;;
    chroot:*)
      probe_rootfs="${probe_spec#chroot:}"
      chroot "${probe_rootfs}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -ksS -o /dev/null -w '%{http_code}' --max-time "${probe_timeout}" "${probe_url}" 2>/dev/null || true
      ;;
    *)
      printf '000'
      ;;
  esac
}

if ! acquire_lock; then
  exit 0
fi

echo "$$" > "${LOOP_PID_FILE}"
trap 'stop_cloudflared; release_lock' EXIT HUP INT TERM

if ! is_true "${SATIKSME_WEB_ENABLED:-0}" || ! is_true "${SATIKSME_WEB_TUNNEL_ENABLED:-0}"; then
  log "satiksme web tunnel disabled; exiting"
  exit 0
fi

public_failures=0
curl_spec="$(resolve_probe_curl 2>/dev/null || true)"
log "${LOOP_NAME} started"

while true; do
  pid="$(read_pid_file "${CLOUDFLARED_PID_FILE}" || true)"
  if ! pid_matches_target "${pid}" "${CLOUDFLARED_BIN}"; then
    stop_cloudflared
    if ! start_cloudflared; then
      log "failed to start cloudflared"
      sleep "${SATIKSME_WEB_TUNNEL_LOOP_POLL_SEC}"
      continue
    fi
  fi

  public_base_url="${SATIKSME_WEB_PUBLIC_BASE_URL:-}"
  if [ -n "${public_base_url}" ] && [ -n "${curl_spec}" ]; then
    public_root_url="$(printf '%s' "${public_base_url}" | sed 's#/*$##')/"
    public_app_url="$(printf '%s' "${public_base_url}" | sed 's#/*$##')/app"
    root_code="$(probe_http_code "${curl_spec}" "${public_root_url}" "${SATIKSME_WEB_TUNNEL_PUBLIC_PROBE_TIMEOUT_SEC}")"
    app_code="$(probe_http_code "${curl_spec}" "${public_app_url}" "${SATIKSME_WEB_TUNNEL_PUBLIC_PROBE_TIMEOUT_SEC}")"
    if [ "${root_code}" = "200" ] && [ "${app_code}" = "200" ]; then
      public_failures=0
    else
      public_failures=$((public_failures + 1))
      log "public probe failed root=${root_code} app=${app_code} count=${public_failures}/${SATIKSME_WEB_TUNNEL_PUBLIC_FAIL_LIMIT}"
      if [ "${public_failures}" -ge "${SATIKSME_WEB_TUNNEL_PUBLIC_FAIL_LIMIT}" ]; then
        stop_cloudflared
        public_failures=0
      fi
    fi
  fi

  sleep "${SATIKSME_WEB_TUNNEL_LOOP_POLL_SEC}"
done
