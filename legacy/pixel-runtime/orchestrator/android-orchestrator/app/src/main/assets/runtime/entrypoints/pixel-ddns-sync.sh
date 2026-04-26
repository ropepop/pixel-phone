#!/system/bin/sh
set -eu

BASE="/data/local/pixel-stack"
CONF_FILE="${BASE}/conf/ddns.env"
RUN_DIR="${BASE}/run"
LOG_FILE="${BASE}/logs/ddns-runner.log"
LAST_IPV4_FILE="${RUN_DIR}/ddns-last-ipv4"
LAST_RECORD_NAMES_FILE="${RUN_DIR}/ddns-last-record-names"
CHROOT_CURL_ROOT="${PIXEL_STACK_CURL_ROOTFS:-/data/local/pixel-stack/chroots/adguardhome}"

mkdir -p "${RUN_DIR}" "${BASE}/logs"

log() {
  printf '[%s] [pixel-ddns-sync] %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >>"${LOG_FILE}"
}

trim() {
  echo "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

resolve_curl_spec() {
  if command -v curl >/dev/null 2>&1; then
    printf 'native:%s\n' "$(command -v curl 2>/dev/null || true)"
    return 0
  fi
  if [ -x "${CHROOT_CURL_ROOT}/usr/bin/curl" ] && [ -x "${CHROOT_CURL_ROOT}/usr/bin/env" ] && chroot "${CHROOT_CURL_ROOT}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
    printf 'chroot:%s\n' "${CHROOT_CURL_ROOT}"
    return 0
  fi
  return 1
}

run_curl() {
  spec="$1"
  shift
  case "${spec}" in
    native:*)
      curl_bin="${spec#native:}"
      "${curl_bin}" "$@"
      ;;
    chroot:*)
      curl_root="${spec#chroot:}"
      chroot "${curl_root}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl "$@"
      ;;
    *)
      return 127
      ;;
  esac
}

if [ ! -r "${CONF_FILE}" ]; then
  log "missing ddns config: ${CONF_FILE}"
  exit 1
fi

# shellcheck disable=SC1090
set -a
. "${CONF_FILE}"
set +a

: "${DDNS_ENABLED:=0}"
: "${DDNS_PROVIDER:=cloudflare}"
: "${DDNS_ZONE_NAME:=}"
: "${DDNS_RECORD_NAME:=}"
: "${DDNS_RECORD_NAMES:=}"
: "${DDNS_CF_API_TOKEN_FILE:=}"
: "${DDNS_UPDATE_TTL:=120}"
: "${DDNS_REQUIRE_STABLE_READS:=2}"
: "${DDNS_SYNC_ON_NETWORK_CHANGE:=1}"
: "${DDNS_MOVE_POLICY:=stick_new_ip}"
: "${DDNS_UPDATE_IPV4:=1}"
: "${PUBLIC_IP_DISCOVERY_V4_URLS:=https://api.ipify.org?format=json,https://checkip.amazonaws.com,https://ipv4.icanhazip.com}"

if [ "${DDNS_ENABLED}" != "1" ]; then
  log "ddns disabled"
  date +%s >"${RUN_DIR}/ddns-last-sync-epoch"
  exit 0
fi

CURL_SPEC="$(resolve_curl_spec 2>/dev/null || true)"
if [ -z "${CURL_SPEC}" ]; then
  log "curl missing"
  exit 1
fi
if [ ! -f "${DDNS_CF_API_TOKEN_FILE}" ]; then
  log "token file missing: ${DDNS_CF_API_TOKEN_FILE}"
  exit 1
fi

TOKEN="$(tr -d '\r\n' <"${DDNS_CF_API_TOKEN_FILE}")"
if [ -z "${TOKEN}" ]; then
  log "token is empty"
  exit 1
fi

stable_discover_ipv4() {
  required="${DDNS_REQUIRE_STABLE_READS}"
  [ -n "${required}" ] || required=1
  case "${required}" in
    ''|*[!0-9]*) required=1 ;;
  esac

  max_attempts=$((required + 3))
  last=""
  streak=0
  attempt=1

  while [ "${attempt}" -le "${max_attempts}" ]; do
    IFS=','
    for raw in ${PUBLIC_IP_DISCOVERY_V4_URLS}; do
      url="$(trim "${raw}")"
      [ -n "${url}" ] || continue
      body="$(run_curl "${CURL_SPEC}" -fsS --connect-timeout 3 --max-time 8 "${url}" 2>/dev/null || true)"
      [ -n "${body}" ] || continue
      ip="$(echo "${body}" | tr -d '\r\n' | sed -n 's/.*"ip"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
      [ -n "${ip}" ] || ip="$(echo "${body}" | tr -d '\r' | sed -n '1p' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
      if echo "${ip}" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
        if [ "${ip}" = "${last}" ]; then
          streak=$((streak + 1))
        else
          last="${ip}"
          streak=1
        fi

        if [ "${streak}" -ge "${required}" ]; then
          echo "${ip}"
          return 0
        fi
      fi
    done
    attempt=$((attempt + 1))
  done

  return 1
}

normalized_record_names() {
  raw_names="${DDNS_RECORD_NAMES}"
  if [ -z "${raw_names}" ]; then
    raw_names="${DDNS_RECORD_NAME}"
  fi
  [ -n "${raw_names}" ] || return 0
  printf '%s\n' "${raw_names}" \
    | tr ',' '\n' \
    | while IFS= read -r raw; do
        name="$(trim "${raw}")"
        [ -n "${name}" ] || continue
        printf '%s\n' "${name}"
      done \
    | awk '!seen[$0]++'
}

cloudflare_record_lookup() {
  record_name="$1"
  run_curl "${CURL_SPEC}" -fsS --connect-timeout 5 --max-time 30 \
    -G \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    --data-urlencode "type=A" \
    --data-urlencode "name=${record_name}" \
    "https://api.cloudflare.com/client/v4/zones/${ZONE_ID}/dns_records"
}

sync_cloudflare_record() {
  record_name="$1"
  record_resp="$(cloudflare_record_lookup "${record_name}")"
  record_compact="$(echo "${record_resp}" | tr -d '\n\r')"
  record_result="$(echo "${record_compact}" | awk -F'"result":[[][{]' '{print $2}')"
  record_id="$(
    echo "${record_result}" \
      | awk -F'"id":"' '{print $2}' \
      | awk -F'"' '{print $1}'
  )"
  current_content="$(
    echo "${record_result}" \
      | awk -F'"content":"' '{print $2}' \
      | awk -F'"' '{print $1}'
  )"

  payload="{\"type\":\"A\",\"name\":\"${record_name}\",\"content\":\"${IPV4}\",\"ttl\":${DDNS_UPDATE_TTL},\"proxied\":false}"

  if [ -z "${record_id}" ]; then
    run_curl "${CURL_SPEC}" -fsS --connect-timeout 5 --max-time 30 \
      -X POST \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      --data "${payload}" \
      "https://api.cloudflare.com/client/v4/zones/${ZONE_ID}/dns_records" >/dev/null
    log "created A record ${record_name} -> ${IPV4}"
    return 0
  fi

  if [ "${current_content}" != "${IPV4}" ]; then
    run_curl "${CURL_SPEC}" -fsS --connect-timeout 5 --max-time 30 \
      -X PUT \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      --data "${payload}" \
      "https://api.cloudflare.com/client/v4/zones/${ZONE_ID}/dns_records/${record_id}" >/dev/null
    log "updated A record ${record_name} -> ${IPV4}"
    return 0
  fi

  log "record unchanged ${record_name}=${IPV4}"
  return 0
}

if [ "${DDNS_UPDATE_IPV4}" != "1" ]; then
  log "ipv4 update disabled"
  date +%s >"${RUN_DIR}/ddns-last-sync-epoch"
  exit 0
fi

cached_ipv4=""
if [ -f "${LAST_IPV4_FILE}" ]; then
  cached_ipv4="$(tr -d '\r\n' <"${LAST_IPV4_FILE}" 2>/dev/null || true)"
fi

cached_record_names=""
if [ -f "${LAST_RECORD_NAMES_FILE}" ]; then
  cached_record_names="$(tr -d '\r' <"${LAST_RECORD_NAMES_FILE}" 2>/dev/null || true)"
fi

record_names_file="$(mktemp "${RUN_DIR}/ddns-record-names.XXXXXX")"
normalized_record_names >"${record_names_file}"
if [ ! -s "${record_names_file}" ]; then
  rm -f "${record_names_file}"
  log "record name missing: set DDNS_RECORD_NAME or DDNS_RECORD_NAMES"
  exit 1
fi
current_record_names="$(paste -sd, "${record_names_file}")"

IPV4="$(stable_discover_ipv4 || true)"
if [ -z "${IPV4}" ]; then
  if [ -n "${cached_ipv4}" ]; then
    rm -f "${record_names_file}"
    if [ "${DDNS_SYNC_ON_NETWORK_CHANGE}" = "1" ] && [ "${DDNS_MOVE_POLICY}" = "stick_new_ip" ]; then
      log "unable to discover stable ipv4 during network-change sync; keeping cached ${cached_ipv4} and retrying later"
    else
      log "unable to discover stable ipv4; using cached ${cached_ipv4}"
    fi
    date +%s >"${RUN_DIR}/ddns-last-sync-epoch"
    exit 0
  fi
  rm -f "${record_names_file}"
  log "unable to discover stable ipv4"
  exit 1
fi

if [ -n "${cached_ipv4}" ] && [ "${cached_ipv4}" = "${IPV4}" ]; then
  if [ -n "${cached_record_names}" ] && [ "${cached_record_names}" = "${current_record_names}" ]; then
    rm -f "${record_names_file}"
    log "ipv4 unchanged from cache (${IPV4}) and record names unchanged; skipping provider sync"
    date +%s >"${RUN_DIR}/ddns-last-sync-epoch"
    exit 0
  fi
  log "ipv4 unchanged from cache (${IPV4}) but record names changed; syncing provider"
fi

if [ -n "${cached_ipv4}" ] && [ "${cached_ipv4}" != "${IPV4}" ] && [ "${DDNS_SYNC_ON_NETWORK_CHANGE}" = "1" ]; then
  log "public ipv4 changed from cache (${cached_ipv4} -> ${IPV4}); syncing provider with move policy ${DDNS_MOVE_POLICY}"
fi

zone_resp="$(run_curl "${CURL_SPEC}" -fsS --connect-timeout 5 --max-time 30 \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  "https://api.cloudflare.com/client/v4/zones?name=${DDNS_ZONE_NAME}&status=active")"

zone_compact="$(echo "${zone_resp}" | tr -d '\n\r')"
ZONE_ID="$(
  echo "${zone_compact}" \
    | awk -F'"result":[[][{]' '{print $2}' \
    | awk -F'"id":"' '{print $2}' \
    | awk -F'"' '{print $1}'
)"
if [ -z "${ZONE_ID}" ]; then
  rm -f "${record_names_file}"
  log "zone lookup failed"
  exit 1
fi

while IFS= read -r record_name; do
  [ -n "${record_name}" ] || continue
  if ! sync_cloudflare_record "${record_name}"; then
    rm -f "${record_names_file}"
    exit 1
  fi
done < "${record_names_file}"
rm -f "${record_names_file}"

echo "${IPV4}" >"${LAST_IPV4_FILE}"
printf '%s\n' "${current_record_names}" >"${LAST_RECORD_NAMES_FILE}"
date +%s >"${RUN_DIR}/ddns-last-sync-epoch"
log "sync complete"

exit 0
