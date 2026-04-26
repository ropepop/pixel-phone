#!/system/bin/sh
set -eu

REPORT_MODE=0
if [ "${1:-}" = "--report" ] || [ "${PIXEL_MANAGEMENT_HEALTH_REPORT:-0}" = "1" ]; then
  REPORT_MODE=1
fi

STACK_BIN_DIR="${PIXEL_STACK_BIN_DIR:-/data/local/pixel-stack/bin}"
SSH_ROOT="${PIXEL_SSH_ROOT:-/data/local/pixel-stack/ssh}"
SSH_LEGACY_ROOT="${PIXEL_SSH_LEGACY_ROOT:-/data/adb/pixel-stack/ssh}"
VPN_ROOT="${PIXEL_VPN_ROOT:-/data/local/pixel-stack/vpn}"
DDNS_CONF_FILE="${PIXEL_DDNS_CONF_FILE:-/data/local/pixel-stack/conf/ddns.env}"
VPN_HEALTH_BIN="${STACK_BIN_DIR}/pixel-vpn-health.sh"
VPN_CONF_FILE="${VPN_ROOT}/conf/tailscale.env"
SSH_CONF_FILE="${SSH_ROOT}/conf/dropbear.env"
PASSWD_FILE="${SSH_ROOT}/etc/passwd"
LEGACY_PASSWD_FILE="${SSH_LEGACY_ROOT}/etc/passwd"
SYSTEM_PASSWD_FILE="${PIXEL_SSH_SYSTEM_PASSWD_FILE:-/system/etc/passwd}"
PASSWORD_HASH_SOURCE_FILE="${PIXEL_SSH_PASSWORD_HASH_SOURCE_FILE:-/data/local/pixel-stack/conf/ssh/root_password.hash}"
AUTHORIZED_KEYS_FILE="${SSH_ROOT}/home/root/.ssh/authorized_keys"
RUNTIME_AUTHORIZED_KEYS_FILE="${PIXEL_SSH_RUNTIME_AUTHORIZED_KEYS_FILE:-/debug_ramdisk/pixel-ssh-auth/authorized_keys}"
WIRELESS_DEBUG_TLS_PORT_FILE="${VPN_ROOT}/run/wireless-debug-tls-port"
DDNS_LAST_IPV4_FILE="${PIXEL_DDNS_LAST_IPV4_FILE:-/data/local/pixel-stack/run/ddns-last-ipv4}"
PIXEL_STACK_CURL_ROOTFS="${PIXEL_STACK_CURL_ROOTFS:-/data/local/pixel-stack/chroots/adguardhome}"

if [ ! -r "${VPN_CONF_FILE}" ]; then
  VPN_CONF_FILE="/data/local/pixel-stack/conf/vpn/tailscale.env"
fi

if [ -r "${SSH_CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${SSH_CONF_FILE}"
  set +a
fi

if [ -r "${VPN_CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${VPN_CONF_FILE}"
  set +a
fi

if [ -r "${DDNS_CONF_FILE}" ]; then
  # shellcheck disable=SC1090
  set -a
  . "${DDNS_CONF_FILE}"
  set +a
fi

: "${SSH_PORT:=2222}"
: "${SSH_PASSWORD_AUTH:=1}"
: "${SSH_ALLOW_KEY_AUTH:=1}"
: "${VPN_NATIVE_WIRELESS_DEBUG_ENABLED:=0}"
: "${MANAGEMENT_REQUIRE_WIRELESS_DEBUG:=0}"
: "${PUBLIC_IP_DISCOVERY_V4_URLS:=https://api.ipify.org?format=json,https://checkip.amazonaws.com,https://ipv4.icanhazip.com}"

emit() {
  if [ "${REPORT_MODE}" = "1" ]; then
    printf '%s=%s\n' "$1" "$2"
  fi
}

listeners_have_port() {
  local port="$1"
  ss -ltn 2>/dev/null | grep -E "[:.]${port}[[:space:]]" >/dev/null 2>&1
}

ssh_listener_ready() {
  if ! ssh_listener_present; then
    return 1
  fi
  if ssh_banner_ready; then
    return 0
  fi
  return 1
}

ssh_listener_present() {
  ss -ltnp 2>/dev/null | awk -v port="${SSH_PORT}" '
    $1 == "LISTEN" {
      addr = $4
      gsub(/\[|\]/, "", addr)
      if (index($0, "dropbear") == 0) {
        next
      }
      if (!(addr ~ ":" port "$" || addr ~ "\\." port "$")) {
        next
      }
      if (addr ~ /^127\.0\.0\.1:/ || addr ~ /^::1:/) {
        next
      }
      print "1"
      exit
    }
  ' | grep -q '^1$'
}

ssh_banner_ready() {
  if ! command -v nc >/dev/null 2>&1; then
    return 0
  fi

  for host in "${tailnet_ipv4:-}" "${wifi_ipv4:-}" "${mobile_ipv4:-}" 127.0.0.1; do
    [ -n "${host}" ] || continue
    banner="$(
      if command -v timeout >/dev/null 2>&1; then
        timeout 3 sh -c "nc -w 1 -W 1 ${host} ${SSH_PORT} </dev/null 2>/dev/null | tr -d '\r' | sed -n '1p'" 2>/dev/null
      else
        sh -c "nc -w 1 -W 1 ${host} ${SSH_PORT} </dev/null 2>/dev/null | tr -d '\r' | sed -n '1p'" 2>/dev/null
      fi
    )"
    case "${banner}" in
      SSH-2.0-*) return 0 ;;
    esac
  done
  return 1
}

read_first_non_empty_line() {
  local file="$1"
  if [ ! -r "${file}" ]; then
    return 0
  fi
  sed -n '/[^[:space:]]/ { s/^[[:space:]]*//; s/[[:space:]]*$//; p; q; }' "${file}" 2>/dev/null || true
}

read_global_setting() {
  settings get global "$1" 2>/dev/null | tr -d '\r' | sed -n '1p' || true
}

read_system_property() {
  getprop "$1" 2>/dev/null | tr -d '\r' | sed -n '1p' || true
}

resolve_curl_spec() {
  if command -v curl >/dev/null 2>&1; then
    printf 'native:%s\n' "$(command -v curl 2>/dev/null || true)"
    return 0
  fi
  if [ -x "${PIXEL_STACK_CURL_ROOTFS}/usr/bin/curl" ] && [ -x "${PIXEL_STACK_CURL_ROOTFS}/usr/bin/env" ] &&
    chroot "${PIXEL_STACK_CURL_ROOTFS}" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
    printf 'chroot:%s\n' "${PIXEL_STACK_CURL_ROOTFS}"
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

discover_default_route_interface() {
  ip route get 1.1.1.1 2>/dev/null | awk '
    {
      for (i = 1; i <= NF; i++) {
        if ($i == "dev" && (i + 1) <= NF) {
          print $(i + 1)
          exit
        }
      }
    }
  ' | tr -d '\r'
}

discover_ipv4_for_interface() {
  local iface="$1"
  if [ -z "${iface}" ]; then
    return 0
  fi
  ip -4 addr show dev "${iface}" 2>/dev/null | awk '/inet / { sub(/\/.*/, "", $2); print $2; exit }' | tr -d '\r'
}

discover_first_interface_by_prefix() {
  local prefixes="$1"
  ip -o -4 addr show 2>/dev/null | awk -v prefixes="${prefixes}" '
    BEGIN {
      split(prefixes, list, ",")
    }
    {
      iface = $2
      for (i in list) {
        prefix = list[i]
        if (prefix != "" && index(iface, prefix) == 1) {
          print iface
          exit
        }
      }
    }
  ' | tr -d '\r'
}

classify_transport() {
  local iface="$1"
  case "${iface}" in
    wlan*|wifi*) printf 'wifi\n' ;;
    rmnet*|ccmni*|pdp*|wwan*) printf 'cellular\n' ;;
    tailscale*|tun*) printf 'vpn\n' ;;
    "") printf 'unknown\n' ;;
    *) printf 'other\n' ;;
  esac
}

discover_public_ipv4_candidate() {
  local curl_spec=""
  local cached_ipv4=""
  local raw=""
  local url=""
  local body=""
  local ip=""
  local old_ifs="${IFS}"

  cached_ipv4="$(read_first_non_empty_line "${DDNS_LAST_IPV4_FILE}")"
  curl_spec="$(resolve_curl_spec 2>/dev/null || true)"
  if [ -z "${curl_spec}" ]; then
    printf '%s\n' "${cached_ipv4}"
    return 0
  fi

  IFS=','
  for raw in ${PUBLIC_IP_DISCOVERY_V4_URLS}; do
    url="$(printf '%s' "${raw}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [ -n "${url}" ] || continue
    body="$(run_curl "${curl_spec}" -fsS --connect-timeout 2 --max-time 4 "${url}" 2>/dev/null || true)"
    [ -n "${body}" ] || continue
    ip="$(echo "${body}" | tr -d '\r\n' | sed -n 's/.*"ip"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
    [ -n "${ip}" ] || ip="$(echo "${body}" | tr -d '\r' | sed -n '1p' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if echo "${ip}" | grep -Eq '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
      IFS="${old_ifs}"
      printf '%s\n' "${ip}"
      return 0
    fi
  done

  IFS="${old_ifs}"
  printf '%s\n' "${cached_ipv4}"
}

discover_adbd_ports() {
  ss -ltnp 2>/dev/null | awk '
    /adbd/ {
      addr=$4
      gsub(/\[|\]/, "", addr)
      if (addr ~ /:[0-9]+$/) {
        sub(/^.*:/, "", addr)
        print addr
        next
      }
      if (addr ~ /\.[0-9]+$/) {
        sub(/^.*\./, "", addr)
        print addr
      }
    }
  ' | awk 'NF && !seen[$0]++ { print $0 }'
}

discover_wireless_debug_tls_port() {
  discover_adbd_ports | awk '$1 != "5555" { print; exit }'
}

extract_root_hash() {
  local file="$1"
  if [ ! -r "${file}" ]; then
    return 0
  fi
  awk -F: '$1=="root"{print $2; exit}' "${file}" 2>/dev/null || true
}

normalize_password_hash() {
  local file="$1"
  local line=""
  line="$(read_first_non_empty_line "${file}")"
  case "${line}" in
    root:*)
      printf '%s\n' "${line}" | awk -F: '$1=="root"{print $2; exit}' 2>/dev/null || true
      ;;
    '$6$'*)
      printf '%s\n' "${line}"
      ;;
    *)
      ;;
  esac
}

valid_password_hash() {
  case "${1:-}" in
    ""|"x"|"*"|"!"|"!!")
      return 1
      ;;
    '$6$'*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

files_match() {
  local first="$1"
  local second="$2"

  [ -r "${first}" ] || return 1
  [ -r "${second}" ] || return 1

  if command -v cmp >/dev/null 2>&1; then
    cmp -s "${first}" "${second}" >/dev/null 2>&1
    return $?
  fi

  [ "$(cat "${first}" 2>/dev/null || true)" = "$(cat "${second}" 2>/dev/null || true)" ]
}

remote_uid="$(id -u 2>/dev/null || true)"
pm_path="$(command -v pm 2>/dev/null || true)"
am_path="$(command -v am 2>/dev/null || true)"
logcat_path="$(command -v logcat 2>/dev/null || true)"
adb_enabled_global="$(read_global_setting adb_enabled)"
adb_wifi_enabled_global="$(read_global_setting adb_wifi_enabled)"
wifi_enabled_global="$(read_global_setting wifi_on)"
adb_tls_enabled_prop="$(read_system_property persist.adb.tls_server.enable)"
adb_tls_port_prop="$(read_system_property service.adb.tls.port)"
if [ "${adb_tls_port_prop}" = "0" ]; then
  adb_tls_port_prop=""
fi

vpn_enabled="0"
vpn_health="0"
tailscaled_live="0"
tailscaled_sock="0"
tailnet_ipv4=""
guard_chain_ipv4="0"
guard_chain_ipv6="0"

if [ -x "${VPN_HEALTH_BIN}" ]; then
  if PIXEL_VPN_HEALTH_REPORT=1 "${VPN_HEALTH_BIN}" --report >/tmp/pixel-management-health.$$ 2>/dev/null; then
    vpn_health="1"
  fi
  if [ -f /tmp/pixel-management-health.$$ ]; then
    while IFS='=' read -r key value; do
      case "${key}" in
        vpn_enabled) vpn_enabled="${value}" ;;
        tailscaled_live) tailscaled_live="${value}" ;;
        tailscaled_sock) tailscaled_sock="${value}" ;;
        tailnet_ipv4) tailnet_ipv4="${value}" ;;
        guard_chain_ipv4) guard_chain_ipv4="${value}" ;;
        guard_chain_ipv6) guard_chain_ipv6="${value}" ;;
      esac
    done < /tmp/pixel-management-health.$$
    rm -f /tmp/pixel-management-health.$$ >/dev/null 2>&1 || true
  fi
fi

default_route_iface="$(discover_default_route_interface)"
active_transport="$(classify_transport "${default_route_iface}")"
wifi_iface="${default_route_iface}"
case "${wifi_iface}" in
  wlan*|wifi*) ;;
  *)
    wifi_iface="$(discover_first_interface_by_prefix "wlan,wifi")"
    ;;
esac
mobile_iface="${default_route_iface}"
case "${mobile_iface}" in
  rmnet*|ccmni*|pdp*|wwan*) ;;
  *)
    mobile_iface="$(discover_first_interface_by_prefix "rmnet,ccmni,pdp,wwan")"
    ;;
esac
wifi_ipv4="$(discover_ipv4_for_interface "${wifi_iface}")"
mobile_ipv4="$(discover_ipv4_for_interface "${mobile_iface}")"
wifi_enabled="0"
if [ "${wifi_enabled_global}" = "1" ] || [ -n "${wifi_iface}" ]; then
  wifi_enabled="1"
fi
wifi_connected="0"
if [ -n "${wifi_ipv4}" ] || [ "${active_transport}" = "wifi" ]; then
  wifi_connected="1"
fi

wireless_debug_live_ports="$(discover_adbd_ports | paste -sd, -)"
wireless_debug_live="0"
if [ -n "${wireless_debug_live_ports}" ]; then
  wireless_debug_live="1"
fi

wireless_debug_tls_port="${adb_tls_port_prop}"
if [ -z "${wireless_debug_tls_port}" ]; then
  wireless_debug_tls_port="$(discover_wireless_debug_tls_port)"
fi
if [ -z "${wireless_debug_tls_port}" ] && [ -n "${wireless_debug_live_ports}" ]; then
  wireless_debug_tls_port="$(printf '%s\n' "${wireless_debug_live_ports}" | awk -F, '{print $1}')"
fi

ddns_published_ipv4="$(read_first_non_empty_line "${DDNS_LAST_IPV4_FILE}")"
public_ipv4_candidate="$(discover_public_ipv4_candidate)"
network_fingerprint="transport=${active_transport};wifi_enabled=${wifi_enabled};wifi_connected=${wifi_connected};wifi_ipv4=${wifi_ipv4:-none};mobile_iface=${mobile_iface:-none};mobile_ipv4=${mobile_ipv4:-none};adbd_ports=${wireless_debug_live_ports:-none};public_ipv4=${public_ipv4_candidate:-none}"

wireless_debug_enabled="0"
if [ "${adb_enabled_global}" = "1" ] && {
  [ "${adb_wifi_enabled_global}" = "1" ] ||
  [ "${adb_tls_enabled_prop}" = "1" ] ||
  [ "${wireless_debug_live}" = "1" ];
}; then
  wireless_debug_enabled="1"
fi

wireless_debug_healthy="${wireless_debug_live}"
wireless_debug_reason="ok"
if [ "${wireless_debug_live}" != "1" ]; then
  wireless_debug_healthy="0"
  if [ "${wireless_debug_enabled}" != "1" ]; then
    if [ "${adb_enabled_global}" != "1" ]; then
      wireless_debug_reason="adb_disabled"
    else
      wireless_debug_reason="wireless_debug_disabled"
    fi
  else
    wireless_debug_reason="listener_missing"
  fi
fi

ssh_listener="0"
if ssh_listener_ready; then
  ssh_listener="1"
fi

ssh_password_auth_requested="0"
ssh_key_auth_requested="0"
if [ "${SSH_PASSWORD_AUTH}" = "1" ]; then
  ssh_password_auth_requested="1"
fi
if [ "${SSH_ALLOW_KEY_AUTH}" = "1" ]; then
  ssh_key_auth_requested="1"
fi

ssh_auth_mode="key_password"
if [ "${ssh_password_auth_requested}" = "1" ] && [ "${ssh_key_auth_requested}" != "1" ]; then
  ssh_auth_mode="password_only"
elif [ "${ssh_password_auth_requested}" != "1" ] && [ "${ssh_key_auth_requested}" = "1" ]; then
  ssh_auth_mode="key_only"
elif [ "${ssh_password_auth_requested}" != "1" ] && [ "${ssh_key_auth_requested}" != "1" ]; then
  ssh_auth_mode="disabled"
fi

password_hash_source_ready="0"
password_runtime_local_ready="0"
password_runtime_legacy_present="0"
password_runtime_legacy_ready="1"
password_runtime_system_ready="1"
password_runtime_mismatch="0"
source_password_hash="$(normalize_password_hash "${PASSWORD_HASH_SOURCE_FILE}")"
local_root_hash="$(extract_root_hash "${PASSWD_FILE}")"
legacy_root_hash=""
system_root_hash=""

if valid_password_hash "${source_password_hash}"; then
  password_hash_source_ready="1"
fi
if [ "${password_hash_source_ready}" = "1" ] && [ "${local_root_hash}" = "${source_password_hash}" ]; then
  password_runtime_local_ready="1"
fi
if [ -e "${LEGACY_PASSWD_FILE}" ]; then
  password_runtime_legacy_present="1"
  password_runtime_legacy_ready="0"
  legacy_root_hash="$(extract_root_hash "${LEGACY_PASSWD_FILE}")"
  if [ "${password_hash_source_ready}" = "1" ] && [ "${legacy_root_hash}" = "${source_password_hash}" ]; then
    password_runtime_legacy_ready="1"
  fi
fi
if [ "${ssh_listener}" = "1" ]; then
  password_runtime_system_ready="0"
  system_root_hash="$(extract_root_hash "${SYSTEM_PASSWD_FILE}")"
  if [ "${password_hash_source_ready}" = "1" ] && [ "${system_root_hash}" = "${source_password_hash}" ]; then
    password_runtime_system_ready="1"
  fi
fi
if [ "${password_hash_source_ready}" = "1" ] && {
  [ "${password_runtime_local_ready}" != "1" ] ||
  [ "${password_runtime_legacy_ready}" != "1" ] ||
  [ "${password_runtime_system_ready}" != "1" ];
}; then
  password_runtime_mismatch="1"
fi

ssh_password_auth_ready="0"
if [ "${password_hash_source_ready}" = "1" ] &&
   [ "${password_runtime_local_ready}" = "1" ] &&
   [ "${password_runtime_legacy_ready}" = "1" ] &&
   [ "${password_runtime_system_ready}" = "1" ]; then
  ssh_password_auth_ready="1"
fi

key_source_ready="0"
key_runtime_ready="1"
key_runtime_mismatch="0"
ssh_key_auth_ready="0"
if [ -s "${AUTHORIZED_KEYS_FILE}" ]; then
  key_source_ready="1"
fi
if [ "${ssh_listener}" = "1" ]; then
  key_runtime_ready="0"
  if [ "${key_source_ready}" = "1" ] && files_match "${AUTHORIZED_KEYS_FILE}" "${RUNTIME_AUTHORIZED_KEYS_FILE}"; then
    key_runtime_ready="1"
  fi
fi
if [ "${key_source_ready}" = "1" ] && [ "${key_runtime_ready}" != "1" ]; then
  key_runtime_mismatch="1"
fi
if [ "${key_source_ready}" = "1" ] && [ "${key_runtime_ready}" = "1" ]; then
  ssh_key_auth_ready="1"
fi

management_auth_consistent="1"
management_auth_warning_reason="ok"
if [ "${ssh_password_auth_requested}" = "1" ] && [ "${password_runtime_mismatch}" = "1" ]; then
  management_auth_consistent="0"
  management_auth_warning_reason="password_auth_runtime_mismatch"
fi
if [ "${ssh_key_auth_requested}" = "1" ] && [ "${key_runtime_mismatch}" = "1" ]; then
  management_auth_consistent="0"
  if [ "${management_auth_warning_reason}" = "ok" ]; then
    management_auth_warning_reason="key_auth_runtime_mismatch"
  else
    management_auth_warning_reason="${management_auth_warning_reason},key_auth_runtime_mismatch"
  fi
fi

management_enabled="0"
management_healthy="1"
management_reason="disabled"

if [ "${vpn_enabled}" = "1" ]; then
  management_enabled="1"
  management_healthy="0"
  management_reason="vpn_unhealthy"

  if [ "${remote_uid}" != "0" ]; then
    management_reason="root_unavailable"
  elif [ -z "${pm_path}" ] || [ -z "${am_path}" ] || [ -z "${logcat_path}" ]; then
    management_reason="android_command_missing"
  elif [ "${vpn_health}" != "1" ]; then
    management_reason="vpn_unhealthy"
  elif [ -z "${tailnet_ipv4}" ]; then
    management_reason="tailnet_ip_missing"
  elif [ "${MANAGEMENT_REQUIRE_WIRELESS_DEBUG}" = "1" ] && [ "${wireless_debug_healthy}" != "1" ]; then
    management_reason="${wireless_debug_reason}"
  elif [ "${ssh_listener}" != "1" ]; then
    management_reason="ssh_listener_missing"
  elif [ "${ssh_auth_mode}" = "disabled" ]; then
    management_reason="ssh_auth_unconfigured"
  elif [ "${ssh_password_auth_requested}" = "1" ] && [ "${ssh_key_auth_requested}" != "1" ] &&
       [ "${ssh_password_auth_ready}" != "1" ] && [ "${password_runtime_mismatch}" != "1" ]; then
    management_reason="password_auth_not_ready"
  elif [ "${ssh_password_auth_requested}" != "1" ] && [ "${ssh_key_auth_requested}" = "1" ] &&
       [ "${ssh_key_auth_ready}" != "1" ] && [ "${key_runtime_mismatch}" != "1" ]; then
    management_reason="key_auth_not_ready"
  elif [ "${ssh_password_auth_ready}" != "1" ] && [ "${ssh_key_auth_ready}" != "1" ] &&
       [ "${password_runtime_mismatch}" != "1" ] && [ "${key_runtime_mismatch}" != "1" ]; then
    management_reason="ssh_auth_not_ready"
  else
    management_healthy="1"
    management_reason="ok"
  fi
fi

if [ "${management_enabled}" != "1" ]; then
  management_auth_consistent="1"
  management_auth_warning_reason="disabled"
fi

emit "remote_uid" "${remote_uid}"
emit "pm_path" "${pm_path}"
emit "am_path" "${am_path}"
emit "logcat_path" "${logcat_path}"
emit "vpn_enabled" "${vpn_enabled}"
emit "vpn_health" "${vpn_health}"
emit "tailscaled_live" "${tailscaled_live}"
emit "tailscaled_sock" "${tailscaled_sock}"
emit "tailnet_ipv4" "${tailnet_ipv4}"
emit "guard_chain_ipv4" "${guard_chain_ipv4}"
emit "guard_chain_ipv6" "${guard_chain_ipv6}"
emit "wireless_debug_enabled" "${wireless_debug_enabled}"
emit "wireless_debug_tls_enabled_prop" "${adb_tls_enabled_prop}"
emit "wireless_debug_tls_port_prop" "${adb_tls_port_prop}"
emit "wireless_debug_tls_port" "${wireless_debug_tls_port}"
emit "wireless_debug_live" "${wireless_debug_live}"
emit "wireless_debug_live_ports" "${wireless_debug_live_ports}"
emit "wireless_debug_healthy" "${wireless_debug_healthy}"
emit "wireless_debug_reason" "${wireless_debug_reason}"
emit "wifi_enabled" "${wifi_enabled}"
emit "wifi_connected" "${wifi_connected}"
emit "wifi_ipv4" "${wifi_ipv4}"
emit "mobile_iface" "${mobile_iface}"
emit "mobile_ipv4" "${mobile_ipv4}"
emit "active_transport" "${active_transport}"
emit "public_ipv4_candidate" "${public_ipv4_candidate}"
emit "ddns_published_ipv4" "${ddns_published_ipv4}"
emit "network_fingerprint" "${network_fingerprint}"
emit "ssh_port" "${SSH_PORT}"
emit "ssh_listener" "${ssh_listener}"
emit "ssh_auth_mode" "${ssh_auth_mode}"
emit "ssh_password_auth_requested" "${ssh_password_auth_requested}"
emit "ssh_password_auth_ready" "${ssh_password_auth_ready}"
emit "ssh_password_hash_source_ready" "${password_hash_source_ready}"
emit "ssh_password_runtime_local_ready" "${password_runtime_local_ready}"
emit "ssh_password_runtime_legacy_present" "${password_runtime_legacy_present}"
emit "ssh_password_runtime_legacy_ready" "${password_runtime_legacy_ready}"
emit "ssh_password_runtime_system_ready" "${password_runtime_system_ready}"
emit "ssh_password_runtime_mismatch" "${password_runtime_mismatch}"
emit "ssh_key_auth_requested" "${ssh_key_auth_requested}"
emit "ssh_key_auth_ready" "${ssh_key_auth_ready}"
emit "ssh_key_source_ready" "${key_source_ready}"
emit "ssh_key_runtime_ready" "${key_runtime_ready}"
emit "ssh_key_runtime_mismatch" "${key_runtime_mismatch}"
emit "management_auth_consistent" "${management_auth_consistent}"
emit "management_auth_warning_reason" "${management_auth_warning_reason}"
emit "management_require_wireless_debug" "${MANAGEMENT_REQUIRE_WIRELESS_DEBUG}"
emit "management_enabled" "${management_enabled}"
emit "management_healthy" "${management_healthy}"
emit "management_reason" "${management_reason}"

if [ "${management_enabled}" != "1" ]; then
  exit 0
fi

[ "${management_healthy}" = "1" ]
