#!/system/bin/sh
set -eu

SUBSCRIPTION_BOT_ROOT="${SUBSCRIPTION_BOT_ROOT:-/data/local/pixel-stack/apps/subscription-bot}"
ENV_FILE="${SUBSCRIPTION_BOT_ROOT}/env/subscription-bot.env"
BIN_DIR="${SUBSCRIPTION_BOT_ROOT}/bin"
RUN_DIR="${SUBSCRIPTION_BOT_ROOT}/run"
LOG_DIR="${SUBSCRIPTION_BOT_ROOT}/logs"
STATE_DIR="${SUBSCRIPTION_BOT_ROOT}/state/subscription-web-tunnel"
SERVICE_LOG="${LOG_DIR}/subscription-web-tunnel-service-loop.log"
LOOP_NAME="subscription-web-tunnel-service-loop"
LOCK_DIR="${RUN_DIR}/${LOOP_NAME}.lock"
LOOP_PID_FILE="${RUN_DIR}/${LOOP_NAME}.pid"
CLOUDFLARED_BIN="${BIN_DIR}/cloudflared"
CLOUDFLARED_PID_FILE="${RUN_DIR}/subscription-bot-cloudflared.pid"
CLOUDFLARED_LOG_FILE="${LOG_DIR}/subscription-bot-cloudflared.log"
CLOUDFLARED_CONFIG_FILE="${STATE_DIR}/subscription-bot-cloudflared.yml"
CLOUDFLARED_VERSION="${CLOUDFLARED_VERSION:-2026.2.0}"
CLOUDFLARED_SHA256="${CLOUDFLARED_SHA256:-03c5d58e283f521d752dc4436014eb341092edf076eb1095953ab82debe54a8e}"
CLOUDFLARED_DOWNLOAD_URL="${CLOUDFLARED_DOWNLOAD_URL:-https://github.com/cloudflare/cloudflared/releases/download/${CLOUDFLARED_VERSION}/cloudflared-linux-arm64}"
CLOUDFLARED_METRICS_ADDR="${CLOUDFLARED_METRICS_ADDR:-127.0.0.1:20250}"
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

: "${SUBSCRIPTION_BOT_WEB_TUNNEL_LOOP_POLL_SEC:=15}"
: "${SUBSCRIPTION_BOT_WEB_TUNNEL_PUBLIC_FAIL_LIMIT:=3}"
: "${SUBSCRIPTION_BOT_WEB_TUNNEL_PUBLIC_PROBE_TIMEOUT_SEC:=8}"
: "${SUBSCRIPTION_BOT_WEB_TUNNEL_START_GRACE_SEC:=2}"
: "${SUBSCRIPTION_BOT_WEB_PORT:=9320}"
: "${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE:=/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json}"

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
  pid_matches_target "${1:-}" "${SUBSCRIPTION_BOT_ROOT}/bin/${LOOP_NAME}"
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

find_existing_pid() {
  target="$1"
  target_base="$(basename "${target}")"
  ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v target="${target}" -v target_base="${target_base}" -v self_pid="$$" '
    function starts_with(value, prefix) { return index(value, prefix) == 1 }
    function next_is_boundary(value, prefix_len) {
      c = substr(value, prefix_len + 1, 1)
      return c == "" || c == " "
    }
    {
      pid = $1
      name = $2
      if (pid == self_pid) {
        next
      }
      args = ""
      if (NF >= 3) {
        args = substr($0, index($0, $3))
      }
      if (name == target_base ||
        args == target ||
        (starts_with(args, target) && next_is_boundary(args, length(target))) ||
        (starts_with(args, "sh " target) && next_is_boundary(args, length("sh " target))) ||
        (starts_with(args, target_base) && next_is_boundary(args, length(target_base)))) {
        print pid
        exit
      }
    }
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

ensure_loop_pid_file() {
  echo "$$" > "${LOOP_PID_FILE}"
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
    rootfs_tmp_rel="/tmp/subscription-bot-cloudflared-download.$$"
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

  mkdir -p "${BIN_DIR}"
  tmp_bin="$(mktemp)"
  if ! download_cloudflared_binary "${tmp_bin}" "${CLOUDFLARED_DOWNLOAD_URL}"; then
    rm -f "${tmp_bin}" >/dev/null 2>&1 || true
    log "failed to download cloudflared binary from ${CLOUDFLARED_DOWNLOAD_URL}"
    return 1
  fi

  actual_sha="$(cloudflared_file_sha256 "${tmp_bin}" 2>/dev/null || true)"
  if [ -z "${actual_sha}" ] || [ "${actual_sha}" != "${CLOUDFLARED_SHA256}" ]; then
    rm -f "${tmp_bin}" >/dev/null 2>&1 || true
    log "cloudflared sha256 mismatch expected=${CLOUDFLARED_SHA256} actual=${actual_sha}"
    return 1
  fi

  mv "${tmp_bin}" "${CLOUDFLARED_BIN}"
  chmod 0755 "${CLOUDFLARED_BIN}"
  return 0
}

resolve_curl() {
  if command -v curl >/dev/null 2>&1; then
    printf '%s\n' "$(command -v curl)"
    return 0
  fi
  if [ -x "${CURL_FALLBACK_BIN}" ]; then
    printf '%s\n' "${CURL_FALLBACK_BIN}"
    return 0
  fi
  return 1
}

subscription_web_proxy_port() {
  case "${SUBSCRIPTION_BOT_WEB_PORT:-}" in
    ''|*[!0-9]*) printf '9320\n' ;;
    *)
      if [ "${SUBSCRIPTION_BOT_WEB_PORT}" -ge 1 ] && [ "${SUBSCRIPTION_BOT_WEB_PORT}" -le 65535 ]; then
        printf '%s\n' "${SUBSCRIPTION_BOT_WEB_PORT}"
      else
        printf '9320\n'
      fi
      ;;
  esac
}

subscription_web_public_base_url() {
  printf '%s\n' "${SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL:-}"
}

subscription_web_tunnel_hostname() {
  base_url="$(subscription_web_public_base_url)"
  [ -n "${base_url}" ] || return 1
  printf '%s\n' "${base_url}" | sed -n 's#^[A-Za-z][A-Za-z0-9+.-]*://\([^/:?#]*\).*$#\1#p'
}

subscription_web_tunnel_id() {
  credentials_file="${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE}"
  [ -r "${credentials_file}" ] || return 1
  raw="$(tr -d '\n\r' < "${credentials_file}" 2>/dev/null || true)"
  [ -n "${raw}" ] || return 1
  printf '%s\n' "${raw}" | sed -n 's/.*"TunnelID"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

render_tunnel_config() {
  tunnel_id="$(subscription_web_tunnel_id)"
  tunnel_host="$(subscription_web_tunnel_hostname)"
  subscription_web_port="$(subscription_web_proxy_port)"

  if [ -z "${tunnel_id}" ]; then
    log "cloudflared tunnel credentials file did not contain TunnelID: ${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE}"
    return 1
  fi
  if [ -z "${tunnel_host}" ]; then
    log "cloudflared tunnel enabled but SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL host is missing"
    return 1
  fi

  tmp_config="$(mktemp)"
  cat > "${tmp_config}" <<EOF_CLOUDFLARED
tunnel: ${tunnel_id}
credentials-file: ${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE}
ingress:
  - hostname: ${tunnel_host}
    service: http://127.0.0.1:${subscription_web_port}
  - service: http_status:404
EOF_CLOUDFLARED
  mv "${tmp_config}" "${CLOUDFLARED_CONFIG_FILE}"
  chmod 0600 "${CLOUDFLARED_CONFIG_FILE}" >/dev/null 2>&1 || true
}

read_tunnel_pid() {
  read_pid_file "${CLOUDFLARED_PID_FILE}"
}

sync_tunnel_pid_file() {
  pid="$(read_tunnel_pid || true)"
  if pid_matches_target "${pid}" "${CLOUDFLARED_BIN}"; then
    echo "${pid}" > "${CLOUDFLARED_PID_FILE}"
    return 0
  fi

  pid="$(find_existing_pid "${CLOUDFLARED_BIN}" || true)"
  if pid_matches_target "${pid}" "${CLOUDFLARED_BIN}"; then
    echo "${pid}" > "${CLOUDFLARED_PID_FILE}"
    return 0
  fi

  rm -f "${CLOUDFLARED_PID_FILE}" >/dev/null 2>&1 || true
  return 1
}

tunnel_running() {
  sync_tunnel_pid_file >/dev/null 2>&1
}

stop_tunnel() {
  pid="$(read_tunnel_pid || true)"
  if pid_matches_target "${pid}" "${CLOUDFLARED_BIN}"; then
    kill_pid_and_wait "${pid}"
  fi
  rm -f "${CLOUDFLARED_PID_FILE}" >/dev/null 2>&1 || true

  for stale_pid in $(list_matching_pids "${CLOUDFLARED_CONFIG_FILE}" 2>/dev/null || true); do
    [ -n "${stale_pid}" ] || continue
    kill_pid_and_wait "${stale_pid}"
  done
}

start_tunnel() {
  if ! is_true "${SUBSCRIPTION_BOT_WEB_ENABLED:-0}" || ! is_true "${SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED:-0}"; then
    stop_tunnel
    return 0
  fi
  if [ ! -r "${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE}" ]; then
    log "cloudflared tunnel credentials file missing: ${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE}"
    return 1
  fi
  install_cloudflared_binary || return 1
  render_tunnel_config || return 1

  if tunnel_running; then
    return 0
  fi

  stop_tunnel
  log "starting cloudflared for subscription bot hostname=$(subscription_web_tunnel_hostname) config=${CLOUDFLARED_CONFIG_FILE} credentials=${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE}"
  nohup "${CLOUDFLARED_BIN}" --config "${CLOUDFLARED_CONFIG_FILE}" --metrics "${CLOUDFLARED_METRICS_ADDR}" --no-autoupdate tunnel run >> "${CLOUDFLARED_LOG_FILE}" 2>&1 &
  pid="$!"
  if [ -n "${pid}" ]; then
    echo "${pid}" > "${CLOUDFLARED_PID_FILE}"
  fi

  sleep "${SUBSCRIPTION_BOT_WEB_TUNNEL_START_GRACE_SEC}"
  if tunnel_running; then
    return 0
  fi

  log "cloudflared exited immediately for subscription bot config=${CLOUDFLARED_CONFIG_FILE} credentials=${SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE} log=${CLOUDFLARED_LOG_FILE}"
  rm -f "${CLOUDFLARED_PID_FILE}" >/dev/null 2>&1 || true
  return 1
}

probe_http_code() {
  probe_bin="$1"
  probe_url="$2"
  probe_timeout="$3"
  if ! probe_code="$("${probe_bin}" -ksS -o /dev/null -w '%{http_code}' --max-time "${probe_timeout}" "${probe_url}" 2>/dev/null)"; then
    printf 'unavailable\n'
    return 0
  fi
  case "${probe_code}" in
    ""|"000000") probe_code="000" ;;
  esac
  printf '%s\n' "${probe_code}"
}

public_probe_ok() {
  public_base_url="$(subscription_web_public_base_url)"
  if [ -z "${public_base_url}" ]; then
    return 0
  fi

  probe_bin="$(resolve_curl 2>/dev/null || true)"
  if [ -z "${probe_bin}" ]; then
    return 0
  fi

  health_url="$(printf '%s' "${public_base_url}" | sed 's#/*$##')/api/v1/health"
  health_code="$(probe_http_code "${probe_bin}" "${health_url}" "${SUBSCRIPTION_BOT_WEB_TUNNEL_PUBLIC_PROBE_TIMEOUT_SEC}")"
  if [ "${health_code}" = "unavailable" ]; then
    log "public tunnel probe skipped because ${probe_bin} could not be executed on-device"
    return 0
  fi
  if [ "${health_code}" = "200" ]; then
    return 0
  fi

  log "public tunnel probe failed health_code=${health_code} base_url=${public_base_url}"
  return 1
}

if ! acquire_lock; then
  exit 0
fi

ensure_loop_pid_file
trap 'stop_tunnel >/dev/null 2>&1 || true; release_lock' EXIT HUP INT TERM

fail_count=0
log "${LOOP_NAME} started"

while true; do
  ensure_loop_pid_file

  if ! is_true "${SUBSCRIPTION_BOT_WEB_ENABLED:-0}" || ! is_true "${SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED:-0}"; then
    fail_count=0
    stop_tunnel
    sleep 30
    continue
  fi

  if ! tunnel_running; then
    fail_count=0
    log "cloudflared tunnel missing; starting"
    if ! start_tunnel; then
      log "cloudflared tunnel failed to start"
      sleep "${SUBSCRIPTION_BOT_WEB_TUNNEL_LOOP_POLL_SEC}"
      continue
    fi
    log "cloudflared tunnel running pid=$(read_tunnel_pid)"
    sleep "${SUBSCRIPTION_BOT_WEB_TUNNEL_LOOP_POLL_SEC}"
    continue
  fi

  if public_probe_ok; then
    fail_count=0
  else
    fail_count=$((fail_count + 1))
    if [ "${fail_count}" -ge "${SUBSCRIPTION_BOT_WEB_TUNNEL_PUBLIC_FAIL_LIMIT}" ]; then
      log "restarting cloudflared after ${fail_count} consecutive public probe failures"
      stop_tunnel
      sleep 2
      fail_count=0
      continue
    fi
  fi

  sleep "${SUBSCRIPTION_BOT_WEB_TUNNEL_LOOP_POLL_SEC}"
done
