#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
TRANSPORT_SH="${REPO_ROOT}/tools/pixel/transport.sh"
LEGACY_TRANSPORT_SH="${REPO_ROOT}/legacy/pixel-runtime/tools/pixel/transport.sh"
if [[ -r "${TRANSPORT_SH}" ]]; then
  # shellcheck source=../../../tools/pixel/transport.sh
  source "${TRANSPORT_SH}"
elif [[ -r "${LEGACY_TRANSPORT_SH}" ]]; then
  # shellcheck source=../../../legacy/pixel-runtime/tools/pixel/transport.sh
  source "${LEGACY_TRANSPORT_SH}"
else
  pixel_transport_require_adb_serial() {
    local adb_bin="${ADB_BIN:-adb}"
    local serials=()
    local line=""

    command -v "${adb_bin}" >/dev/null 2>&1 || return 1

    while IFS= read -r line; do
      [[ -n "${line}" ]] || continue
      serials+=("${line}")
    done < <("${adb_bin}" devices | awk 'NR>1 && $2=="device" {print $1}')

    if (( ${#serials[@]} == 1 )); then
      printf '%s\n' "${serials[0]}"
      return 0
    fi
    if (( ${#serials[@]} > 1 )); then
      echo "Multiple adb devices found; pass --adb-serial." >&2
    fi
    return 1
  }
fi

HOST="${SERVICE_REPORT_HOST:-dns.jolkins.id.lv}"
FQDN="${SERVICE_REPORT_FQDN:-dns.jolkins.id.lv}"
HTTPS_PORT="${SERVICE_REPORT_HTTPS_PORT:-443}"
DOT_PORT="${SERVICE_REPORT_DOT_PORT:-853}"
SSH_PORT="${SERVICE_REPORT_SSH_PORT:-2222}"
ADB_SERIAL=""
ADB_CONNECT=""
JSON_OUT=""
CONFIG_FILE=""
TIMEOUT_SEC=2
DNS_TEST_DOMAIN="example.com"
RUN_ROOT_CHECKS=0
REQUIRE_REMOTE=0
DOH_URL=""
DOH_TOKEN="${SERVICE_REPORT_DOH_TOKEN:-}"
DOH_ENDPOINT_MODE="${SERVICE_REPORT_DOH_ENDPOINT_MODE:-native}"
DEFAULT_DOH_PAYLOAD="EjQBAAABAAAAAAAAB2V4YW1wbGUDY29tAAABAAE"
BENCHMARK_REQUESTS=0
EXPECT_LAN_CLIENT_IP=""
LAN_GATEWAY_IP=""
EXPECT_ROUTER_PUBLIC_IP=""
EXPECT_ROUTER_LAN_IP=""
QUERYLOG_LIMIT=5000
QUERYLOG_JSON_FILE=""
MAX_LAN_GATEWAY_SHARE_PCT=""
MAX_ROUTER_LAN_DOH_COUNT=""
REQUIRE_LAN_VISIBLE=0
INCLUDE_INTERNAL_QUERYLOG=0
INTERNAL_QUERYLOG_CLIENTS_CSV="127.0.0.1,::1"
INTERNAL_QUERYLOG_CLIENTS_PROVIDED=0
INTERNAL_PROBE_DOMAINS_CSV=""
INTERNAL_PROBE_DOMAINS_PROVIDED=0
INTERNAL_QUERYLOG_CLIENTS=""
INTERNAL_PROBE_DOMAINS=""

usage() {
  cat <<EOH
Usage: service-availability-report.sh [options]

Options:
  --host HOST              Target host/IP for probes (default: ${HOST})
  --fqdn NAME              Public DNS FQDN for HTTPS probes (default: ${FQDN})
  --https-port PORT        Public HTTPS/DoH port (default: ${HTTPS_PORT})
  --dot-port PORT          Public DoT port (default: ${DOT_PORT})
  --ssh-port PORT          Informational SSH port probe target (default: ${SSH_PORT})
  --adb-serial SERIAL      Use this adb serial for optional rooted checks
  --adb-connect HOST:PORT  Attempt adb connect before device checks
  --timeout SEC            TCP probe timeout in seconds (default: ${TIMEOUT_SEC})
  --dns-domain DOMAIN      Domain to query for DNS probes (default: ${DNS_TEST_DOMAIN})
  --config-file PATH       Read remote.dohEndpointMode and remote.dohPathToken from config JSON
  --doh-endpoint-mode MODE DoH mode: native | tokenized | dual
  --doh-token TOKEN        Token used for tokenized/dual mode endpoint checks
  --doh-url URL            Override primary DoH URL probe target
  --benchmark-requests N   Run N requests against primary DoH URL and report latency percentiles
  --expect-lan-client-ip IP
                           Expected LAN client IP to appear in AdGuard query log
  --lan-gateway-ip IP      Gateway/router IP used for querylog share checks (default: derived from --host, fallback 192.168.31.1)
  --expect-router-public-ip IP
                           Expected public router IP to appear for relayed DoH requests
  --expect-router-lan-ip IP
                           Router LAN IP to track for relayed DoH requests (default: --lan-gateway-ip)
  --querylog-limit N       Querylog row limit for AdGuard API fetch (default: ${QUERYLOG_LIMIT})
  --querylog-json-file PATH
                           Read querylog JSON from local file instead of live AdGuard API
  --include-internal-querylog
                           Include internal querylog rows in user-facing metrics (default: hidden)
  --internal-querylog-clients CSV
                           Comma-separated client IPs to classify as internal (default: 127.0.0.1,::1)
  --internal-probe-domains CSV
                           Comma-separated probe domains for internal loopback tagging (default: example.com + --dns-domain)
  --max-lan-gateway-share-pct N
                           Optional threshold for gateway DoH share percentage (0-100)
  --max-router-lan-doh-count N
                           Optional threshold for DoH rows still attributed to router LAN IP
  --require-lan-visible    Exit non-zero unless LAN visibility contract passes
  --require-remote         Exit non-zero unless remote listener contracts pass
  --rooted-pixel-checks    Include Pixel adb/rooted checks
  --skip-root-checks       Skip adb + rooted runtime checks
  --json-out PATH          Write JSON summary to PATH
  -h, --help               Show this help text
EOH
}

normalize_csv_unique() {
  local input="${1:-}" raw item out=""
  IFS=',' read -r -a _parts <<< "${input}"
  for raw in "${_parts[@]}"; do
    item="$(printf '%s' "${raw}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -n "${item}" ]] || continue
    case ",${out}," in
      *,"${item}",*) ;;
      *) out="${out}${out:+,}${item}" ;;
    esac
  done
  printf '%s' "${out}"
}

normalize_domain_csv_unique() {
  local input="${1:-}" raw item out=""
  IFS=',' read -r -a _parts <<< "${input}"
  for raw in "${_parts[@]}"; do
    item="$(printf '%s' "${raw}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | tr '[:upper:]' '[:lower:]')"
    [[ -n "${item}" ]] || continue
    case ",${out}," in
      *,"${item}",*) ;;
      *) out="${out}${out:+,}${item}" ;;
    esac
  done
  printf '%s' "${out}"
}

while (( $# > 0 )); do
  case "$1" in
    --host) shift; HOST="${1:-}" ;;
    --fqdn) shift; FQDN="${1:-}" ;;
    --https-port) shift; HTTPS_PORT="${1:-}" ;;
    --dot-port) shift; DOT_PORT="${1:-}" ;;
    --ssh-port) shift; SSH_PORT="${1:-}" ;;
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --adb-connect) shift; ADB_CONNECT="${1:-}" ;;
    --timeout) shift; TIMEOUT_SEC="${1:-}" ;;
    --dns-domain) shift; DNS_TEST_DOMAIN="${1:-}" ;;
    --config-file) shift; CONFIG_FILE="${1:-}" ;;
    --doh-endpoint-mode) shift; DOH_ENDPOINT_MODE="${1:-}" ;;
    --doh-token) shift; DOH_TOKEN="${1:-}" ;;
    --doh-url) shift; DOH_URL="${1:-}" ;;
    --benchmark-requests) shift; BENCHMARK_REQUESTS="${1:-}" ;;
    --expect-lan-client-ip) shift; EXPECT_LAN_CLIENT_IP="${1:-}" ;;
    --lan-gateway-ip) shift; LAN_GATEWAY_IP="${1:-}" ;;
    --expect-router-public-ip) shift; EXPECT_ROUTER_PUBLIC_IP="${1:-}" ;;
    --expect-router-lan-ip) shift; EXPECT_ROUTER_LAN_IP="${1:-}" ;;
    --querylog-limit) shift; QUERYLOG_LIMIT="${1:-}" ;;
    --querylog-json-file) shift; QUERYLOG_JSON_FILE="${1:-}" ;;
    --include-internal-querylog) INCLUDE_INTERNAL_QUERYLOG=1 ;;
    --internal-querylog-clients) shift; INTERNAL_QUERYLOG_CLIENTS_CSV="${1:-}"; INTERNAL_QUERYLOG_CLIENTS_PROVIDED=1 ;;
    --internal-probe-domains) shift; INTERNAL_PROBE_DOMAINS_CSV="${1:-}"; INTERNAL_PROBE_DOMAINS_PROVIDED=1 ;;
    --max-lan-gateway-share-pct) shift; MAX_LAN_GATEWAY_SHARE_PCT="${1:-}" ;;
    --max-router-lan-doh-count) shift; MAX_ROUTER_LAN_DOH_COUNT="${1:-}" ;;
    --require-lan-visible) REQUIRE_LAN_VISIBLE=1 ;;
    --require-remote) REQUIRE_REMOTE=1 ;;
    --rooted-pixel-checks) RUN_ROOT_CHECKS=1 ;;
    --skip-root-checks) RUN_ROOT_CHECKS=0 ;;
    --json-out) shift; JSON_OUT="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

INTERNAL_QUERYLOG_CLIENTS="$(normalize_csv_unique "${INTERNAL_QUERYLOG_CLIENTS_CSV}")"
if [[ -z "${INTERNAL_QUERYLOG_CLIENTS}" ]]; then
  echo "Invalid --internal-querylog-clients value (resolved empty set)" >&2
  exit 2
fi
if (( INTERNAL_PROBE_DOMAINS_PROVIDED == 1 )); then
  INTERNAL_PROBE_DOMAINS="$(normalize_domain_csv_unique "${INTERNAL_PROBE_DOMAINS_CSV}")"
else
  INTERNAL_PROBE_DOMAINS="$(normalize_domain_csv_unique "example.com,${DNS_TEST_DOMAIN}")"
fi
if [[ -z "${INTERNAL_PROBE_DOMAINS}" ]]; then
  echo "Invalid --internal-probe-domains value (resolved empty set)" >&2
  exit 2
fi

if [[ -z "${HOST}" || -z "${FQDN}" || -z "${TIMEOUT_SEC}" ]] || [[ ! "${TIMEOUT_SEC}" =~ ^[0-9]+$ ]] || (( TIMEOUT_SEC < 1 )); then
  echo "Invalid required argument values" >&2
  exit 2
fi
for port_value in "${HTTPS_PORT}" "${DOT_PORT}" "${SSH_PORT}"; do
  if [[ ! "${port_value}" =~ ^[0-9]+$ ]] || (( port_value < 1 || port_value > 65535 )); then
    echo "Invalid port value: ${port_value}" >&2
    exit 2
  fi
done
if [[ ! "${BENCHMARK_REQUESTS}" =~ ^[0-9]+$ ]]; then
  echo "Invalid --benchmark-requests value" >&2
  exit 2
fi
if [[ ! "${QUERYLOG_LIMIT}" =~ ^[0-9]+$ ]] || (( QUERYLOG_LIMIT < 1 )); then
  echo "Invalid --querylog-limit value" >&2
  exit 2
fi
if [[ -n "${MAX_LAN_GATEWAY_SHARE_PCT}" ]] && [[ ! "${MAX_LAN_GATEWAY_SHARE_PCT}" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "Invalid --max-lan-gateway-share-pct value" >&2
  exit 2
fi
if [[ -n "${MAX_ROUTER_LAN_DOH_COUNT}" ]] && [[ ! "${MAX_ROUTER_LAN_DOH_COUNT}" =~ ^[0-9]+$ ]]; then
  echo "Invalid --max-router-lan-doh-count value" >&2
  exit 2
fi
if [[ -n "${QUERYLOG_JSON_FILE}" && ! -r "${QUERYLOG_JSON_FILE}" ]]; then
  echo "Querylog JSON file not readable: ${QUERYLOG_JSON_FILE}" >&2
  exit 2
fi

normalize_mode() {
  local raw="${1:-native}"
  raw="$(printf '%s' "${raw}" | tr '[:upper:]' '[:lower:]')"
  case "${raw}" in
    native|tokenized|dual) printf '%s' "${raw}" ;;
    *) return 1 ;;
  esac
}

if [[ -n "${CONFIG_FILE}" ]]; then
  if [[ ! -r "${CONFIG_FILE}" ]]; then
    echo "Config file not readable: ${CONFIG_FILE}" >&2
    exit 2
  fi
  if command -v jq >/dev/null 2>&1; then
    cfg_mode="$(jq -r '.remote.dohEndpointMode // empty' "${CONFIG_FILE}" 2>/dev/null || true)"
    cfg_token="$(jq -r '.remote.dohPathToken // empty' "${CONFIG_FILE}" 2>/dev/null || true)"
    [[ -n "${cfg_mode}" && "${cfg_mode}" != "null" ]] && DOH_ENDPOINT_MODE="${cfg_mode}"
    if [[ -z "${DOH_TOKEN}" && -n "${cfg_token}" && "${cfg_token}" != "null" ]]; then
      DOH_TOKEN="${cfg_token}"
    fi
  fi
fi

if ! DOH_ENDPOINT_MODE="$(normalize_mode "${DOH_ENDPOINT_MODE}")"; then
  echo "Invalid doh endpoint mode: ${DOH_ENDPOINT_MODE}. Expected native|tokenized|dual" >&2
  exit 2
fi

timestamp() { date '+%Y-%m-%dT%H:%M:%S%z'; }

default_gateway_from_host() {
  local ip="${1:-}"
  if [[ "${ip}" =~ ^([0-9]{1,3})[.]([0-9]{1,3})[.]([0-9]{1,3})[.][0-9]{1,3}$ ]]; then
    printf '%s.%s.%s.1' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
  else
    printf '192.168.31.1'
  fi
}

decimal_leq() {
  local lhs="${1:-0}" rhs="${2:-0}"
  awk -v l="${lhs}" -v r="${rhs}" 'BEGIN { if (l + 0 <= r + 0) exit 0; exit 1 }'
}

if [[ -z "${LAN_GATEWAY_IP}" ]]; then
  LAN_GATEWAY_IP="$(default_gateway_from_host "${HOST}")"
fi
if [[ -n "${ADB_SERIAL}" || -n "${ADB_CONNECT}" ]]; then
  RUN_ROOT_CHECKS=1
fi
ROUTER_PUBLIC_ATTRIBUTION_CHECK_ENABLED=0
if [[ -n "${EXPECT_ROUTER_PUBLIC_IP}" || -n "${EXPECT_ROUTER_LAN_IP}" || -n "${MAX_ROUTER_LAN_DOH_COUNT}" ]]; then
  ROUTER_PUBLIC_ATTRIBUTION_CHECK_ENABLED=1
fi
if (( ROUTER_PUBLIC_ATTRIBUTION_CHECK_ENABLED == 1 )) && [[ -z "${EXPECT_ROUTER_LAN_IP}" ]]; then
  EXPECT_ROUTER_LAN_IP="${LAN_GATEWAY_IP}"
fi

json_escape() {
  local s="${1:-}"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\t'/\\t}"
  printf '%s' "${s}"
}

tcp_probe() {
  local host="$1" port="$2"
  if nc -z -w "${TIMEOUT_SEC}" "${host}" "${port}" >/dev/null 2>&1; then
    printf 'open'
  else
    printf 'closed'
  fi
}

http_code() {
  local url="$1" code
  code="$(curl -m "$((TIMEOUT_SEC + 3))" -sS -o /dev/null -w '%{http_code}' "${url}" 2>/dev/null || true)"
  [[ "${code}" =~ ^[0-9]{3}$ ]] && printf '%s' "${code}" || printf '000'
}

https_url() {
  local path="$1"
  local base="https://${FQDN}"
  if [[ "${HTTPS_PORT}" != "443" ]]; then
    base="${base}:${HTTPS_PORT}"
  fi
  printf '%s%s\n' "${base}" "${path}"
}

dns_probe() {
  local host="$1" port="$2" out rc
  set +e
  out="$(dig @"${host}" -p "${port}" "${DNS_TEST_DOMAIN}" +time="${TIMEOUT_SEC}" +tries=1 +short 2>/dev/null)"
  rc=$?
  set -e
  if (( rc == 0 )) && [[ -n "${out// /}" ]]; then
    printf 'ok|%s' "${out//$'\n'/,}"
  else
    printf 'failed|%s' "${out//$'\n'/,}"
  fi
}

TOKENIZED_DOH_URL=""
BARE_DOH_URL="$(https_url "/dns-query?dns=${DEFAULT_DOH_PAYLOAD}")"
if [[ -n "${DOH_TOKEN}" ]]; then
  TOKENIZED_DOH_URL="$(https_url "/${DOH_TOKEN}/dns-query?dns=${DEFAULT_DOH_PAYLOAD}")"
fi

if [[ -z "${DOH_URL}" ]]; then
  case "${DOH_ENDPOINT_MODE}" in
    native)
      DOH_URL="${BARE_DOH_URL}"
      ;;
    tokenized|dual)
      if [[ -z "${TOKENIZED_DOH_URL}" ]]; then
        echo "Tokenized DoH mode requires --doh-token or --config-file with remote.dohPathToken" >&2
        exit 2
      fi
      DOH_URL="${TOKENIZED_DOH_URL}"
      ;;
  esac
fi

PING_STATE="skipped"
if ping -c 1 "${HOST}" >/dev/null 2>&1; then
  PING_STATE="ok"
else
  PING_STATE="failed"
fi

PORT_53="$(tcp_probe "${HOST}" 53)"
PORT_HTTPS="$(tcp_probe "${HOST}" "${HTTPS_PORT}")"
PORT_DOT="$(tcp_probe "${HOST}" "${DOT_PORT}")"
PORT_SSH="$(tcp_probe "${HOST}" "${SSH_PORT}")"
PORT_8080="$(tcp_probe "${HOST}" 8080)"

DNS53_RAW="$(dns_probe "${HOST}" 53)"
DNS53_STATE="${DNS53_RAW%%|*}"
DNS53_DATA="${DNS53_RAW#*|}"

HTTP_HTTPS_ROOT="$(http_code "$(https_url "/")")"
HTTP_HTTPS_ADMIN="$(http_code "$(https_url "/admin/")")"
HTTP_DOH_PRIMARY="$(http_code "${DOH_URL}")"
HTTP_DOH_TOKENIZED="skipped"
HTTP_DOH_BARE="skipped"
if [[ -n "${TOKENIZED_DOH_URL}" ]]; then
  HTTP_DOH_TOKENIZED="$(http_code "${TOKENIZED_DOH_URL}")"
fi
if [[ -n "${BARE_DOH_URL}" ]]; then
  HTTP_DOH_BARE="$(http_code "${BARE_DOH_URL}")"
fi

HTTPS_ROOT_CONTRACT="fail"
case "${HTTP_HTTPS_ROOT}" in
  200|301|302|303|307|308|401) HTTPS_ROOT_CONTRACT="pass" ;;
esac

HTTPS_ADMIN_UI_CONTRACT="fail"
case "${HTTP_HTTPS_ADMIN}" in
  200|301|302|303|307|308|401) HTTPS_ADMIN_UI_CONTRACT="pass" ;;
esac

DOH_CONTRACT="fail"
case "${DOH_ENDPOINT_MODE}" in
  native)
    [[ "${HTTP_DOH_BARE}" == "200" ]] && DOH_CONTRACT="pass"
    ;;
  tokenized)
    [[ "${HTTP_DOH_TOKENIZED}" == "200" && "${HTTP_DOH_BARE}" != "200" ]] && DOH_CONTRACT="pass"
    ;;
  dual)
    [[ "${HTTP_DOH_TOKENIZED}" == "200" && "${HTTP_DOH_BARE}" == "200" ]] && DOH_CONTRACT="pass"
    ;;
esac

REMOTE_CONTRACT="pass"
if [[ "${PORT_HTTPS}" != "open" || "${PORT_DOT}" != "open" || "${HTTPS_ROOT_CONTRACT}" != "pass" || "${DOH_CONTRACT}" != "pass" ]]; then
  REMOTE_CONTRACT="fail"
fi

BENCHMARK_ENABLED=0
BENCHMARK_SAMPLE_COUNT=0
BENCHMARK_SUCCESS_RATE="0.00"
BENCHMARK_P50_MS="0.000"
BENCHMARK_P90_MS="0.000"
BENCHMARK_P95_MS="0.000"
BENCHMARK_P99_MS="0.000"
BENCHMARK_STATUS_DIST=""

if (( BENCHMARK_REQUESTS > 0 )) && [[ -n "${DOH_URL}" ]]; then
  BENCHMARK_ENABLED=1
  tmp_results="$(mktemp)"
  tmp_sorted="$(mktemp)"
  tmp_codes="$(mktemp)"
  success_count=0

  for _ in $(seq 1 "${BENCHMARK_REQUESTS}"); do
    line="$(curl -ksS -o /dev/null --max-time "$((TIMEOUT_SEC + 3))" -w '%{http_code} %{time_total}' "${DOH_URL}" 2>/dev/null || true)"
    code="${line%% *}"
    sec="${line##* }"
    [[ "${code}" =~ ^[0-9]{3}$ ]] || code="000"
    if [[ "${sec}" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
      ms="$(awk -v s="${sec}" 'BEGIN { printf "%.3f", s * 1000 }')"
    else
      ms="0.000"
    fi
    printf '%s\n' "${ms}" >> "${tmp_results}"
    printf '%s\n' "${code}" >> "${tmp_codes}"
    if [[ "${code}" == "200" ]]; then
      success_count=$((success_count + 1))
    fi
  done

  BENCHMARK_SAMPLE_COUNT="$(wc -l < "${tmp_results}" | tr -d ' ')"
  if (( BENCHMARK_SAMPLE_COUNT > 0 )); then
    sort -n "${tmp_results}" > "${tmp_sorted}"

    percentile_ms() {
      local pct="$1" n rank
      n="${BENCHMARK_SAMPLE_COUNT}"
      rank=$(( (pct * n + 99) / 100 ))
      (( rank < 1 )) && rank=1
      (( rank > n )) && rank=n
      sed -n "${rank}p" "${tmp_sorted}"
    }

    BENCHMARK_P50_MS="$(percentile_ms 50)"
    BENCHMARK_P90_MS="$(percentile_ms 90)"
    BENCHMARK_P95_MS="$(percentile_ms 95)"
    BENCHMARK_P99_MS="$(percentile_ms 99)"
    BENCHMARK_SUCCESS_RATE="$(awk -v ok="${success_count}" -v total="${BENCHMARK_SAMPLE_COUNT}" 'BEGIN { if (total == 0) { print "0.00" } else { printf "%.2f", (ok * 100.0) / total } }')"
    BENCHMARK_STATUS_DIST="$(
      sort "${tmp_codes}" | uniq -c | awk '
        {
          if (NR > 1) {
            printf ";"
          }
          printf "%s:%s", $2, $1
        }
      '
    )"
  fi

  rm -f "${tmp_results}" "${tmp_sorted}" "${tmp_codes}"
fi

ADB_AVAILABLE=0
ADB_TARGET=""
ADB_DEVICE_STATE="unknown"
ADB_CONNECT_RESULT=""
ADGUARDHOME_LOOP_STATE="unknown"
SSH_LISTEN_STATE="unknown"
DNS_LISTEN_STATE="unknown"
LAN_QUERYLOG_SOURCE="none"
LAN_QUERYLOG_STATUS="skipped"
LAN_QUERYLOG_PATH=""
LAN_EXPECTED_CLIENT_SEEN="skipped"
LAN_GATEWAY_DOH_COUNT=0
LAN_TOTAL_DOH_COUNT=0
LAN_GATEWAY_SHARE_PCT="0.00"
LAN_ROUTER_PUBLIC_IP_DOH_COUNT=0
LAN_ROUTER_LAN_IP_DOH_COUNT=0
LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT="skipped"
LAN_TOP_CLIENTS="none"
LAN_TOP_CLIENTS_INTERNAL="none"
LAN_TOP_CLIENTS_USER="none"
LAN_QUERYLOG_VIEW_MODE="user_only"
LAN_INTERNAL_TOTAL_COUNT=0
LAN_INTERNAL_DOH_COUNT=0
LAN_USER_TOTAL_COUNT=0
LAN_USER_DOH_COUNT=0
LAN_INTERNAL_PROBE_DOMAIN_COUNTS="none"
LAN_CONTRACT="skipped"

if (( RUN_ROOT_CHECKS == 1 )) && command -v adb >/dev/null 2>&1; then
  if [[ -n "${ADB_CONNECT}" ]]; then
    ADB_CONNECT_RESULT="$(adb connect "${ADB_CONNECT}" 2>&1 || true)"
  fi

  if [[ -n "${ADB_SERIAL}" ]]; then
    ADB_TARGET="${ADB_SERIAL}"
  else
    ADB_TARGET="$(pixel_transport_require_adb_serial 2>/dev/null || true)"
  fi

  if [[ -n "${ADB_TARGET}" ]]; then
    ADB_AVAILABLE=1
    if adb -s "${ADB_TARGET}" get-state >/dev/null 2>&1; then
      ADB_DEVICE_STATE="device"
      if adb -s "${ADB_TARGET}" shell "su -c 'pgrep -af adguardhome-service-loop >/dev/null 2>&1'" >/dev/null 2>&1; then
        ADGUARDHOME_LOOP_STATE="running"
      else
        ADGUARDHOME_LOOP_STATE="stopped"
      fi
      if adb -s "${ADB_TARGET}" shell "su -c 'ss -ltn 2>/dev/null | grep -E \"[:.]2222[[:space:]]\" >/dev/null 2>&1'" >/dev/null 2>&1; then
        SSH_LISTEN_STATE="open"
      else
        SSH_LISTEN_STATE="closed"
      fi
      if adb -s "${ADB_TARGET}" shell "su -c 'ss -ltn 2>/dev/null | grep -E \"[:.]53[[:space:]]\" >/dev/null 2>&1'" >/dev/null 2>&1; then
        DNS_LISTEN_STATE="open"
      else
        DNS_LISTEN_STATE="closed"
      fi
    else
      ADB_DEVICE_STATE="unreachable"
    fi
  fi
fi

NEED_LAN_QUERYLOG=0
if [[ -n "${QUERYLOG_JSON_FILE}" || -n "${EXPECT_LAN_CLIENT_IP}" || -n "${MAX_LAN_GATEWAY_SHARE_PCT}" || -n "${EXPECT_ROUTER_PUBLIC_IP}" || -n "${EXPECT_ROUTER_LAN_IP}" || -n "${MAX_ROUTER_LAN_DOH_COUNT}" ]] || (( REQUIRE_LAN_VISIBLE == 1 )); then
  NEED_LAN_QUERYLOG=1
fi
if (( INCLUDE_INTERNAL_QUERYLOG == 1 || INTERNAL_QUERYLOG_CLIENTS_PROVIDED == 1 || INTERNAL_PROBE_DOMAINS_PROVIDED == 1 )); then
  NEED_LAN_QUERYLOG=1
fi

if (( NEED_LAN_QUERYLOG == 1 )); then
  if [[ -n "${QUERYLOG_JSON_FILE}" ]]; then
    LAN_QUERYLOG_SOURCE="file"
    LAN_QUERYLOG_PATH="${QUERYLOG_JSON_FILE}"
    LAN_QUERYLOG_STATUS="ok"
  elif (( ADB_AVAILABLE == 1 )) && [[ "${ADB_DEVICE_STATE}" == "device" ]]; then
    querylog_tmp="$(mktemp)"
    querylog_raw="$(
      adb -s "${ADB_TARGET}" shell "su -c 'set -eu
PW_FILE=/data/local/pixel-stack/conf/adguardhome/remote-admin-password
ROOTFS=/data/local/pixel-stack/chroots/adguardhome
if [ -r \"\${PW_FILE}\" ]; then
  PW=\$(cat \"\${PW_FILE}\" 2>/dev/null)
  if command -v curl >/dev/null 2>&1; then
    CURL_BIN=\$(command -v curl 2>/dev/null || true)
    \"\${CURL_BIN}\" -sS -u pihole:\"\${PW}\" \"http://127.0.0.1:8080/control/querylog?limit=${QUERYLOG_LIMIT}\"
  elif [ -x \"\${ROOTFS}/usr/bin/curl\" ] && [ -x \"\${ROOTFS}/usr/bin/env\" ] && chroot \"\${ROOTFS}\" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
    chroot \"\${ROOTFS}\" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -sS -u pihole:\"\${PW}\" \"http://127.0.0.1:8080/control/querylog?limit=${QUERYLOG_LIMIT}\"
  fi
fi'" 2>/dev/null || true
    )"
    if [[ -n "${querylog_raw}" ]]; then
      printf '%s' "${querylog_raw}" > "${querylog_tmp}"
      LAN_QUERYLOG_SOURCE="adb"
      LAN_QUERYLOG_PATH="${querylog_tmp}"
      LAN_QUERYLOG_STATUS="ok"
    else
      rm -f "${querylog_tmp}"
      LAN_QUERYLOG_STATUS="fetch_failed"
    fi
  else
    if (( ADB_AVAILABLE == 0 )); then
      LAN_QUERYLOG_STATUS="adb_unavailable"
    else
      LAN_QUERYLOG_STATUS="adb_unreachable"
    fi
  fi

  if [[ "${LAN_QUERYLOG_STATUS}" == "ok" ]]; then
    if ! command -v jq >/dev/null 2>&1; then
      LAN_QUERYLOG_STATUS="jq_missing"
    elif ! jq -e '.data | type == "array"' "${LAN_QUERYLOG_PATH}" >/dev/null 2>&1; then
      LAN_QUERYLOG_STATUS="invalid_json"
    else
      lan_metrics_json="$(
        jq -c \
          --arg gw "${LAN_GATEWAY_IP}" \
          --arg expect_client "${EXPECT_LAN_CLIENT_IP}" \
          --arg router_lan "${EXPECT_ROUTER_LAN_IP}" \
          --arg router_public "${EXPECT_ROUTER_PUBLIC_IP}" \
          --arg include_internal "${INCLUDE_INTERNAL_QUERYLOG}" \
          --arg internal_clients_csv "${INTERNAL_QUERYLOG_CLIENTS}" \
          --arg internal_probe_domains_csv "${INTERNAL_PROBE_DOMAINS}" \
          '
          def internal_clients: ($internal_clients_csv | split(",") | map(gsub("^\\s+|\\s+$";"")) | map(select(length > 0)));
          def internal_probe_domains: ($internal_probe_domains_csv | split(",") | map(ascii_downcase) | map(gsub("^\\s+|\\s+$";"")) | map(select(length > 0)));
          def qname: (.question.name // "" | ascii_downcase);
          def is_loopback_internal: (.client // "") as $c | (internal_clients | index($c)) != null;
          def is_internal: is_loopback_internal;
          def is_internal_probe: ((qname) as $qn | is_loopback_internal and ((internal_probe_domains | index($qn)) != null));
          def doh_count($rows): ($rows | map(select((.client_proto // "") == "doh")) | length);
          def count_client_doh($rows; $ip): ($rows | map(select((.client // "") == $ip and (.client_proto // "") == "doh")) | length);
          def top_clients($rows):
            ($rows
              | map({client: (.client // "unknown"), proto: ((.client_proto // "") | if . == "" then "plain" else . end)})
              | group_by([.client, .proto])
              | map({client: .[0].client, proto: .[0].proto, count: length})
              | sort_by(-.count, .client, .proto)
              | .[:10]
              | if length == 0 then "none" else map("\(.client):\(.proto):\(.count)") | join(";") end);
          def expected_seen($rows; $ip):
            (if ($ip | length) == 0 then "skipped" else (if any($rows[]?; (.client // "") == $ip) then "true" else "false" end) end);
          def probe_counts($rows):
            ($rows
              | map(select(is_internal_probe) | qname)
              | group_by(.)
              | map({domain: .[0], count: length})
              | sort_by(-.count, .domain)
              | if length == 0 then "none" else map("\(.domain):\(.count)") | join(";") end);

          (.data // []) as $all
          | ($all | map(select(is_internal))) as $internal
          | ($all | map(select(is_internal | not))) as $user
          | (if $include_internal == "1" then $all else $user end) as $effective
          | {
              querylog_view_mode: (if $include_internal == "1" then "all" else "user_only" end),
              internal_total_count: ($internal | length),
              internal_doh_count: doh_count($internal),
              user_total_count: ($user | length),
              user_doh_count: doh_count($user),
              total_doh_count: doh_count($effective),
              gateway_doh_count: count_client_doh($effective; $gw),
              router_lan_ip_doh_count: (if ($router_lan | length) == 0 then 0 else count_client_doh($effective; $router_lan) end),
              router_public_ip_doh_count: (if ($router_public | length) == 0 then 0 else count_client_doh($effective; $router_public) end),
              expected_client_seen: expected_seen($effective; $expect_client),
              top_clients: top_clients($effective),
              top_clients_internal: top_clients($internal),
              top_clients_user: top_clients($user),
              internal_probe_domain_counts: probe_counts($all)
            }
          ' "${LAN_QUERYLOG_PATH}" 2>/dev/null || true
      )"
      if [[ -z "${lan_metrics_json}" ]]; then
        LAN_QUERYLOG_STATUS="invalid_json"
      else
        LAN_QUERYLOG_VIEW_MODE="$(jq -r '.querylog_view_mode' <<< "${lan_metrics_json}")"
        LAN_INTERNAL_TOTAL_COUNT="$(jq -r '.internal_total_count' <<< "${lan_metrics_json}")"
        LAN_INTERNAL_DOH_COUNT="$(jq -r '.internal_doh_count' <<< "${lan_metrics_json}")"
        LAN_USER_TOTAL_COUNT="$(jq -r '.user_total_count' <<< "${lan_metrics_json}")"
        LAN_USER_DOH_COUNT="$(jq -r '.user_doh_count' <<< "${lan_metrics_json}")"
        LAN_TOTAL_DOH_COUNT="$(jq -r '.total_doh_count' <<< "${lan_metrics_json}")"
        LAN_GATEWAY_DOH_COUNT="$(jq -r '.gateway_doh_count' <<< "${lan_metrics_json}")"
        LAN_ROUTER_LAN_IP_DOH_COUNT="$(jq -r '.router_lan_ip_doh_count' <<< "${lan_metrics_json}")"
        LAN_ROUTER_PUBLIC_IP_DOH_COUNT="$(jq -r '.router_public_ip_doh_count' <<< "${lan_metrics_json}")"
        LAN_EXPECTED_CLIENT_SEEN="$(jq -r '.expected_client_seen' <<< "${lan_metrics_json}")"
        LAN_TOP_CLIENTS="$(jq -r '.top_clients' <<< "${lan_metrics_json}")"
        LAN_TOP_CLIENTS_INTERNAL="$(jq -r '.top_clients_internal' <<< "${lan_metrics_json}")"
        LAN_TOP_CLIENTS_USER="$(jq -r '.top_clients_user' <<< "${lan_metrics_json}")"
        LAN_INTERNAL_PROBE_DOMAIN_COUNTS="$(jq -r '.internal_probe_domain_counts' <<< "${lan_metrics_json}")"
        LAN_GATEWAY_SHARE_PCT="$(awk -v gw="${LAN_GATEWAY_DOH_COUNT}" -v total="${LAN_TOTAL_DOH_COUNT}" 'BEGIN { if (total == 0) print "0.00"; else printf "%.2f", (gw * 100.0) / total }')"
      fi
    fi
  fi

  if (( ROUTER_PUBLIC_ATTRIBUTION_CHECK_ENABLED == 1 )); then
    LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT="pass"
    if [[ "${LAN_QUERYLOG_STATUS}" != "ok" ]]; then
      LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT="fail"
    fi
    if [[ "${LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT}" == "pass" && -n "${EXPECT_ROUTER_PUBLIC_IP}" && "${LAN_ROUTER_PUBLIC_IP_DOH_COUNT}" -le 0 ]]; then
      LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT="fail"
    fi
    if [[ "${LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT}" == "pass" && -n "${MAX_ROUTER_LAN_DOH_COUNT}" && "${LAN_ROUTER_LAN_IP_DOH_COUNT}" -gt "${MAX_ROUTER_LAN_DOH_COUNT}" ]]; then
      LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT="fail"
    fi
  fi

  if [[ -n "${EXPECT_LAN_CLIENT_IP}" || -n "${MAX_LAN_GATEWAY_SHARE_PCT}" ]] || (( REQUIRE_LAN_VISIBLE == 1 )); then
    LAN_CONTRACT="pass"
    if [[ "${LAN_QUERYLOG_STATUS}" != "ok" ]]; then
      LAN_CONTRACT="fail"
    fi
    if [[ "${LAN_CONTRACT}" == "pass" && -n "${EXPECT_LAN_CLIENT_IP}" && "${LAN_EXPECTED_CLIENT_SEEN}" != "true" ]]; then
      LAN_CONTRACT="fail"
    fi
    if [[ "${LAN_CONTRACT}" == "pass" && -n "${MAX_LAN_GATEWAY_SHARE_PCT}" ]]; then
      if ! decimal_leq "${LAN_GATEWAY_SHARE_PCT}" "${MAX_LAN_GATEWAY_SHARE_PCT}"; then
        LAN_CONTRACT="fail"
      fi
    fi
  fi
fi

cat <<EOH
Service Availability Report
Timestamp: $(timestamp)
Host: ${HOST}
FQDN: ${FQDN}

Network Reachability:
- ping: ${PING_STATE}

TCP Ports:
- 53: ${PORT_53}
- ${HTTPS_PORT}: ${PORT_HTTPS}
- ${DOT_PORT}: ${PORT_DOT}
- ${SSH_PORT}: ${PORT_SSH}
- 8080: ${PORT_8080}

DNS Probe (${DNS_TEST_DOMAIN}):
- @${HOST}:53 -> ${DNS53_STATE}${DNS53_DATA:+ (${DNS53_DATA})}

HTTPS/DoH Probes:
- $(https_url "/") -> ${HTTP_HTTPS_ROOT}
- $(https_url "/admin/") -> ${HTTP_HTTPS_ADMIN}
- doh_mode: ${DOH_ENDPOINT_MODE}
- doh_primary_url: ${DOH_URL}
- doh_primary_code: ${HTTP_DOH_PRIMARY}
- doh_tokenized_url: ${TOKENIZED_DOH_URL:-none}
- doh_tokenized_code: ${HTTP_DOH_TOKENIZED}
- doh_bare_url: ${BARE_DOH_URL}
- doh_bare_code: ${HTTP_DOH_BARE}
- contract_root_ui_reachable (2xx/3xx/401 expected): ${HTTPS_ROOT_CONTRACT}
- contract_admin_ui_reachable (informational 2xx/3xx/401): ${HTTPS_ADMIN_UI_CONTRACT}
- contract_doh_mode: ${DOH_CONTRACT}
- contract_remote_required: ${REMOTE_CONTRACT}
EOH

if (( BENCHMARK_ENABLED == 1 )); then
  cat <<EOH

DoH Benchmark (${BENCHMARK_REQUESTS} requests):
- success_rate_200_pct: ${BENCHMARK_SUCCESS_RATE}
- p50_ms: ${BENCHMARK_P50_MS}
- p90_ms: ${BENCHMARK_P90_MS}
- p95_ms: ${BENCHMARK_P95_MS}
- p99_ms: ${BENCHMARK_P99_MS}
- status_distribution: ${BENCHMARK_STATUS_DIST:-none}
EOH
fi

cat <<EOH

ADB/Rooted Checks:
- adb_available: ${ADB_AVAILABLE}
- adb_target: ${ADB_TARGET:-none}
- adb_device_state: ${ADB_DEVICE_STATE}
- adguardhome_service_loop: ${ADGUARDHOME_LOOP_STATE}
- rooted_listen_53: ${DNS_LISTEN_STATE}
- rooted_listen_2222: ${SSH_LISTEN_STATE}
EOH

if (( NEED_LAN_QUERYLOG == 1 )); then
  cat <<EOH

LAN Client Visibility:
- querylog_source: ${LAN_QUERYLOG_SOURCE}
- querylog_status: ${LAN_QUERYLOG_STATUS}
- querylog_view_mode: ${LAN_QUERYLOG_VIEW_MODE}
- querylog_limit: ${QUERYLOG_LIMIT}
- include_internal_querylog: ${INCLUDE_INTERNAL_QUERYLOG}
- internal_querylog_clients: ${INTERNAL_QUERYLOG_CLIENTS}
- internal_probe_domains: ${INTERNAL_PROBE_DOMAINS}
- expected_client_ip: ${EXPECT_LAN_CLIENT_IP:-none}
- expected_client_seen: ${LAN_EXPECTED_CLIENT_SEEN}
- gateway_ip: ${LAN_GATEWAY_IP}
- gateway_doh_count: ${LAN_GATEWAY_DOH_COUNT}
- total_doh_count: ${LAN_TOTAL_DOH_COUNT}
- gateway_share_pct: ${LAN_GATEWAY_SHARE_PCT}
- user_total_count: ${LAN_USER_TOTAL_COUNT}
- user_doh_count: ${LAN_USER_DOH_COUNT}
- internal_total_count: ${LAN_INTERNAL_TOTAL_COUNT}
- internal_doh_count: ${LAN_INTERNAL_DOH_COUNT}
- router_lan_ip: ${EXPECT_ROUTER_LAN_IP:-none}
- router_public_ip: ${EXPECT_ROUTER_PUBLIC_IP:-none}
- router_lan_ip_doh_count: ${LAN_ROUTER_LAN_IP_DOH_COUNT}
- router_public_ip_doh_count: ${LAN_ROUTER_PUBLIC_IP_DOH_COUNT}
- contract_router_public_attribution: ${LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT}
- top_clients: ${LAN_TOP_CLIENTS}
- top_clients_user: ${LAN_TOP_CLIENTS_USER}
- top_clients_internal: ${LAN_TOP_CLIENTS_INTERNAL}
- internal_probe_domain_counts: ${LAN_INTERNAL_PROBE_DOMAIN_COUNTS}
- contract_lan_visibility: ${LAN_CONTRACT}
EOH
fi

if [[ -n "${JSON_OUT}" ]]; then
  mkdir -p "$(dirname "${JSON_OUT}")"
  cat > "${JSON_OUT}" <<EOF_JSON
{
  "timestamp": "$(json_escape "$(timestamp)")",
  "host": "$(json_escape "${HOST}")",
  "fqdn": "$(json_escape "${FQDN}")",
  "reachability": {"ping": "$(json_escape "${PING_STATE}")"},
  "ports": {
    "53": "$(json_escape "${PORT_53}")",
    "${HTTPS_PORT}": "$(json_escape "${PORT_HTTPS}")",
    "${DOT_PORT}": "$(json_escape "${PORT_DOT}")",
    "${SSH_PORT}": "$(json_escape "${PORT_SSH}")",
    "8080": "$(json_escape "${PORT_8080}")"
  },
  "dns": {
    "host_53": {"state": "$(json_escape "${DNS53_STATE}")", "data": "$(json_escape "${DNS53_DATA}")"}
  },
  "https": {
    "dns_root": "$(json_escape "${HTTP_HTTPS_ROOT}")",
    "dns_admin": "$(json_escape "${HTTP_HTTPS_ADMIN}")",
    "doh_mode": "$(json_escape "${DOH_ENDPOINT_MODE}")",
    "doh_primary_url": "$(json_escape "${DOH_URL}")",
    "doh_primary_code": "$(json_escape "${HTTP_DOH_PRIMARY}")",
    "doh_tokenized_url": "$(json_escape "${TOKENIZED_DOH_URL}")",
    "doh_tokenized_code": "$(json_escape "${HTTP_DOH_TOKENIZED}")",
    "doh_bare_url": "$(json_escape "${BARE_DOH_URL}")",
    "doh_bare_code": "$(json_escape "${HTTP_DOH_BARE}")",
    "contract_root_ui_reachable": "$(json_escape "${HTTPS_ROOT_CONTRACT}")",
    "contract_root_redirect": "$(json_escape "${HTTPS_ROOT_CONTRACT}")",
    "contract_admin_ui_reachable": "$(json_escape "${HTTPS_ADMIN_UI_CONTRACT}")",
    "contract_doh_mode": "$(json_escape "${DOH_CONTRACT}")",
    "contract_doh_200": "$(json_escape "${DOH_CONTRACT}")",
    "contract_remote_required": "$(json_escape "${REMOTE_CONTRACT}")"
  },
  "benchmark": {
    "enabled": ${BENCHMARK_ENABLED},
    "requests": ${BENCHMARK_REQUESTS},
    "samples": ${BENCHMARK_SAMPLE_COUNT},
    "success_rate_200_pct": "$(json_escape "${BENCHMARK_SUCCESS_RATE}")",
    "p50_ms": "$(json_escape "${BENCHMARK_P50_MS}")",
    "p90_ms": "$(json_escape "${BENCHMARK_P90_MS}")",
    "p95_ms": "$(json_escape "${BENCHMARK_P95_MS}")",
    "p99_ms": "$(json_escape "${BENCHMARK_P99_MS}")",
    "status_distribution": "$(json_escape "${BENCHMARK_STATUS_DIST}")"
  },
  "lan": {
    "querylog_source": "$(json_escape "${LAN_QUERYLOG_SOURCE}")",
    "querylog_status": "$(json_escape "${LAN_QUERYLOG_STATUS}")",
    "querylog_view_mode": "$(json_escape "${LAN_QUERYLOG_VIEW_MODE}")",
    "querylog_limit": ${QUERYLOG_LIMIT},
    "include_internal_querylog": ${INCLUDE_INTERNAL_QUERYLOG},
    "internal_querylog_clients": "$(json_escape "${INTERNAL_QUERYLOG_CLIENTS}")",
    "internal_probe_domains": "$(json_escape "${INTERNAL_PROBE_DOMAINS}")",
    "expected_client_ip": "$(json_escape "${EXPECT_LAN_CLIENT_IP}")",
    "expected_client_seen": "$(json_escape "${LAN_EXPECTED_CLIENT_SEEN}")",
    "gateway_ip": "$(json_escape "${LAN_GATEWAY_IP}")",
    "gateway_doh_count": ${LAN_GATEWAY_DOH_COUNT},
    "total_doh_count": ${LAN_TOTAL_DOH_COUNT},
    "gateway_share_pct": "$(json_escape "${LAN_GATEWAY_SHARE_PCT}")",
    "user_total_count": ${LAN_USER_TOTAL_COUNT},
    "user_doh_count": ${LAN_USER_DOH_COUNT},
    "internal_total_count": ${LAN_INTERNAL_TOTAL_COUNT},
    "internal_doh_count": ${LAN_INTERNAL_DOH_COUNT},
    "router_lan_ip": "$(json_escape "${EXPECT_ROUTER_LAN_IP}")",
    "router_public_ip": "$(json_escape "${EXPECT_ROUTER_PUBLIC_IP}")",
    "router_lan_ip_doh_count": ${LAN_ROUTER_LAN_IP_DOH_COUNT},
    "router_public_ip_doh_count": ${LAN_ROUTER_PUBLIC_IP_DOH_COUNT},
    "router_public_attribution_contract": "$(json_escape "${LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT}")",
    "top_clients": "$(json_escape "${LAN_TOP_CLIENTS}")",
    "top_clients_user": "$(json_escape "${LAN_TOP_CLIENTS_USER}")",
    "top_clients_internal": "$(json_escape "${LAN_TOP_CLIENTS_INTERNAL}")",
    "internal_probe_domain_counts": "$(json_escape "${LAN_INTERNAL_PROBE_DOMAIN_COUNTS}")",
    "contract_lan_visibility": "$(json_escape "${LAN_CONTRACT}")"
  },
  "adb": {
    "available": ${ADB_AVAILABLE},
    "connect_result": "$(json_escape "${ADB_CONNECT_RESULT}")",
    "target": "$(json_escape "${ADB_TARGET}")",
    "device_state": "$(json_escape "${ADB_DEVICE_STATE}")"
  },
  "rooted": {
    "adguardhome_service_loop": "$(json_escape "${ADGUARDHOME_LOOP_STATE}")",
    "listen_53": "$(json_escape "${DNS_LISTEN_STATE}")",
    "listen_2222": "$(json_escape "${SSH_LISTEN_STATE}")"
  }
}
EOF_JSON
  echo "JSON report written: ${JSON_OUT}"
fi

if [[ "${LAN_QUERYLOG_SOURCE}" == "adb" && -n "${LAN_QUERYLOG_PATH}" ]]; then
  rm -f "${LAN_QUERYLOG_PATH}" >/dev/null 2>&1 || true
fi

if (( REQUIRE_REMOTE == 1 )) && [[ "${REMOTE_CONTRACT}" != "pass" ]]; then
  echo "Remote contract required but failed for ${FQDN} (mode=${DOH_ENDPOINT_MODE})" >&2
  exit 1
fi
if (( REQUIRE_LAN_VISIBLE == 1 )) && [[ "${LAN_CONTRACT}" != "pass" ]]; then
  echo "LAN visibility contract required but failed (expected=${EXPECT_LAN_CLIENT_IP:-none}, gateway=${LAN_GATEWAY_IP}, share=${LAN_GATEWAY_SHARE_PCT}%)" >&2
  exit 1
fi
if (( REQUIRE_LAN_VISIBLE == 1 )) && (( ROUTER_PUBLIC_ATTRIBUTION_CHECK_ENABLED == 1 )) && [[ "${LAN_ROUTER_PUBLIC_ATTRIBUTION_CONTRACT}" != "pass" ]]; then
  echo "Router public attribution contract required but failed (router_lan=${EXPECT_ROUTER_LAN_IP:-none}, router_public=${EXPECT_ROUTER_PUBLIC_IP:-none}, router_lan_doh=${LAN_ROUTER_LAN_IP_DOH_COUNT}, router_public_doh=${LAN_ROUTER_PUBLIC_IP_DOH_COUNT})" >&2
  exit 1
fi
