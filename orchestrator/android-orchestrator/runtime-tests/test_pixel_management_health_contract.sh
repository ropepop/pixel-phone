#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MANAGEMENT_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-management-health.sh"

tmpdir="$(mktemp -d)"
fake_bin_dir="${tmpdir}/fake-bin"
stack_bin_dir="${tmpdir}/stack-bin"
ssh_root="${tmpdir}/ssh"
ssh_legacy_root="${tmpdir}/ssh-legacy"
vpn_report_file="${tmpdir}/vpn-report.env"
vpn_conf_file="${tmpdir}/vpn/conf/tailscale.env"
wireless_debug_tls_port_file="${tmpdir}/vpn/run/wireless-debug-tls-port"
ddns_last_ipv4_file="${tmpdir}/run/ddns-last-ipv4"
ss_output_file="${tmpdir}/ss-output.txt"
settings_global_file="${tmpdir}/settings-global.env"
password_hash_source_file="${tmpdir}/conf/root_password.hash"
runtime_authorized_keys_file="${tmpdir}/runtime-auth/authorized_keys"
system_passwd_file="${tmpdir}/system/etc/passwd"
getprop_values_file="${tmpdir}/getprop-values.env"
ip_route_output_file="${tmpdir}/ip-route.txt"
ip_addr_output_file="${tmpdir}/ip-addr.env"
curl_output_file="${tmpdir}/curl-output.txt"

cleanup() {
  rm -rf "${tmpdir}"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

get_value() {
  local payload="$1"
  local key="$2"
  printf '%s\n' "${payload}" | awk -F= -v key="${key}" '$1 == key { print substr($0, index($0, "=") + 1); exit }'
}

assert_value() {
  local payload="$1"
  local key="$2"
  local expected="$3"
  local actual=""
  actual="$(get_value "${payload}" "${key}")"
  if [[ "${actual}" != "${expected}" ]]; then
    fail "expected ${key}=${expected}, got ${actual:-<empty>}"
  fi
}

assert_non_empty() {
  local payload="$1"
  local key="$2"
  local actual=""
  actual="$(get_value "${payload}" "${key}")"
  if [[ -z "${actual}" ]]; then
    fail "expected non-empty ${key}"
  fi
}

RUN_CONTRACT_RC=0
RUN_CONTRACT_OUTPUT=""

run_contract() {
  local output=""
  set +e
  output="$(
    PATH="${fake_bin_dir}:$PATH" \
      PIXEL_STACK_BIN_DIR="${stack_bin_dir}" \
      PIXEL_SSH_ROOT="${ssh_root}" \
      PIXEL_SSH_LEGACY_ROOT="${ssh_legacy_root}" \
      PIXEL_VPN_ROOT="${tmpdir}/vpn" \
      PIXEL_DDNS_LAST_IPV4_FILE="${ddns_last_ipv4_file}" \
      PIXEL_SSH_PASSWORD_HASH_SOURCE_FILE="${password_hash_source_file}" \
      PIXEL_SSH_RUNTIME_AUTHORIZED_KEYS_FILE="${runtime_authorized_keys_file}" \
      PIXEL_SSH_SYSTEM_PASSWD_FILE="${system_passwd_file}" \
      FAKE_VPN_REPORT_FILE="${vpn_report_file}" \
      FAKE_SS_OUTPUT_FILE="${ss_output_file}" \
      FAKE_SETTINGS_GLOBAL_FILE="${settings_global_file}" \
      FAKE_GETPROP_VALUES_FILE="${getprop_values_file}" \
      FAKE_IP_ROUTE_OUTPUT_FILE="${ip_route_output_file}" \
      FAKE_IP_ADDR_OUTPUT_FILE="${ip_addr_output_file}" \
      FAKE_CURL_OUTPUT_FILE="${curl_output_file}" \
      FAKE_ID_UID="0" \
      bash "${MANAGEMENT_SCRIPT}" --report
  )"
  RUN_CONTRACT_RC=$?
  set -e
  RUN_CONTRACT_OUTPUT="${output}"
}

write_vpn_report() {
  cat > "${vpn_report_file}" <<EOF
vpn_enabled=1
vpn_health=$1
tailscaled_live=1
tailscaled_sock=1
tailnet_ipv4=$2
guard_chain_ipv4=1
guard_chain_ipv6=1
EOF
}

write_vpn_config() {
  mkdir -p "$(dirname "${vpn_conf_file}")"
  cat > "${vpn_conf_file}" <<EOF
VPN_ENABLED=1
VPN_NATIVE_WIRELESS_DEBUG_ENABLED=$1
MANAGEMENT_REQUIRE_WIRELESS_DEBUG=${2:-0}
EOF
}

write_ss_output() {
  cat > "${ss_output_file}" <<EOF
$1
EOF
}

write_settings_global() {
  cat > "${settings_global_file}" <<EOF
adb_enabled=$1
adb_wifi_enabled=$2
EOF
}

write_wireless_debug_tls_port() {
  mkdir -p "$(dirname "${wireless_debug_tls_port_file}")"
  printf '%s\n' "$1" > "${wireless_debug_tls_port_file}"
}

write_ddns_last_ipv4() {
  mkdir -p "$(dirname "${ddns_last_ipv4_file}")"
  printf '%s\n' "$1" > "${ddns_last_ipv4_file}"
}

write_getprop_values() {
  cat > "${getprop_values_file}" <<EOF
persist.adb.tls_server.enable=$1
service.adb.tls.port=$2
EOF
}

write_ip_route_output() {
  cat > "${ip_route_output_file}" <<EOF
$1
EOF
}

write_ip_addr_output() {
  cat > "${ip_addr_output_file}" <<EOF
$1
EOF
}

write_curl_output() {
  cat > "${curl_output_file}" <<EOF
$1
EOF
}

write_password_env() {
  cat > "${ssh_root}/conf/dropbear.env" <<EOF
SSH_PORT=2222
SSH_PASSWORD_AUTH=$1
SSH_ALLOW_KEY_AUTH=$2
EOF
}

write_passwd() {
  cat > "${ssh_root}/etc/passwd" <<EOF
root:$1:0:0:root:/root:/system/bin/sh
EOF
}

write_legacy_passwd() {
  cat > "${ssh_legacy_root}/etc/passwd" <<EOF
root:$1:0:0:root:/root:/system/bin/sh
EOF
}

write_password_hash_source() {
  printf '%s\n' "${1}" > "${password_hash_source_file}"
}

write_system_passwd() {
  mkdir -p "$(dirname "${system_passwd_file}")"
  cat > "${system_passwd_file}" <<EOF
root:$1:0:0:root:/root:/system/bin/sh
EOF
}

write_authorized_keys() {
  mkdir -p "${ssh_root}/home/root/.ssh"
  if [[ -n "${1}" ]]; then
    printf '%s\n' "${1}" > "${ssh_root}/home/root/.ssh/authorized_keys"
  else
    : > "${ssh_root}/home/root/.ssh/authorized_keys"
  fi
}

write_runtime_authorized_keys() {
  mkdir -p "$(dirname "${runtime_authorized_keys_file}")"
  if [[ -n "${1}" ]]; then
    printf '%s\n' "${1}" > "${runtime_authorized_keys_file}"
  else
    : > "${runtime_authorized_keys_file}"
  fi
}

mkdir -p "${fake_bin_dir}" "${stack_bin_dir}" "${ssh_root}/conf" "${ssh_root}/etc" "${ssh_root}/home/root/.ssh" "${ssh_legacy_root}/etc" "$(dirname "${password_hash_source_file}")" "$(dirname "${runtime_authorized_keys_file}")" "$(dirname "${settings_global_file}")" "$(dirname "${wireless_debug_tls_port_file}")" "$(dirname "${ddns_last_ipv4_file}")"

cat > "${stack_bin_dir}/pixel-vpn-health.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cat "${FAKE_VPN_REPORT_FILE}"
if grep -q '^vpn_health=1$' "${FAKE_VPN_REPORT_FILE}"; then
  exit 0
fi
exit 1
EOF
chmod +x "${stack_bin_dir}/pixel-vpn-health.sh"

cat > "${fake_bin_dir}/ss" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cat "${FAKE_SS_OUTPUT_FILE}" 2>/dev/null || true
EOF
chmod +x "${fake_bin_dir}/ss"

cat > "${fake_bin_dir}/id" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-u" ]]; then
  printf '%s\n' "${FAKE_ID_UID:-0}"
  exit 0
fi
exit 1
EOF
chmod +x "${fake_bin_dir}/id"

cat > "${fake_bin_dir}/settings" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "get" && "${2:-}" == "global" ]]; then
  awk -F= -v key="${3:-}" '$1 == key { print $2; found=1; exit } END { if (!found) print "null" }' "${FAKE_SETTINGS_GLOBAL_FILE}" 2>/dev/null || printf 'null\n'
  exit 0
fi
exit 1
EOF
chmod +x "${fake_bin_dir}/settings"

cat > "${fake_bin_dir}/getprop" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
awk -F= -v key="${1:-}" '$1 == key { print $2; found=1; exit } END { if (!found) print "" }' "${FAKE_GETPROP_VALUES_FILE}" 2>/dev/null || true
EOF
chmod +x "${fake_bin_dir}/getprop"

cat > "${fake_bin_dir}/ip" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "route" && "${2:-}" == "get" ]]; then
  cat "${FAKE_IP_ROUTE_OUTPUT_FILE}" 2>/dev/null || true
  exit 0
fi
if [[ "${1:-}" == "-4" && "${2:-}" == "addr" && "${3:-}" == "show" && "${4:-}" == "dev" ]]; then
  awk -F= -v iface="${5:-}" '$1 == iface && $2 != "" { print "    inet " $2 "/24 brd 0.0.0.0 scope global " iface; exit }' "${FAKE_IP_ADDR_OUTPUT_FILE}" 2>/dev/null || true
  exit 0
fi
if [[ "${1:-}" == "-o" && "${2:-}" == "-4" && "${3:-}" == "addr" && "${4:-}" == "show" ]]; then
  awk -F= '$2 != "" { print "1: " $1 "    inet " $2 "/24 brd 0.0.0.0 scope global " $1 }' "${FAKE_IP_ADDR_OUTPUT_FILE}" 2>/dev/null || true
  exit 0
fi
exit 0
EOF
chmod +x "${fake_bin_dir}/ip"

cat > "${fake_bin_dir}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-V" ]]; then
  printf 'curl 8.0.0\n'
  exit 0
fi
cat "${FAKE_CURL_OUTPUT_FILE}" 2>/dev/null || true
EOF
chmod +x "${fake_bin_dir}/curl"

for command_name in pm am logcat; do
  cat > "${fake_bin_dir}/${command_name}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
  chmod +x "${fake_bin_dir}/${command_name}"
done

write_password_env 1 1
write_passwd '$6$healthyhash'
write_legacy_passwd '$6$healthyhash'
write_system_passwd '$6$healthyhash'
write_password_hash_source '$6$healthyhash'
write_authorized_keys 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeKey pixel@test'
write_runtime_authorized_keys 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeKey pixel@test'
write_vpn_config 1 0
write_settings_global 1 1
write_getprop_values 1 43463
write_wireless_debug_tls_port 43463
write_ddns_last_ipv4 '62.205.193.194'
write_ip_route_output '1.1.1.1 dev wlan0 src 192.168.1.50 uid 0'
write_ip_addr_output $'wlan0=192.168.1.50\nrmnet_data0='
write_curl_output '{"ip":"62.205.193.194"}'
write_vpn_report 1 '100.64.0.10'
write_ss_output $'LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=2048,fd=7))\nLISTEN 0 50 *:43463 *:* users:(("adbd",pid=1061,fd=13))'
run_contract
healthy_rc="${RUN_CONTRACT_RC}"
healthy_output="${RUN_CONTRACT_OUTPUT}"
[[ "${healthy_rc}" == "0" ]] || fail "healthy contract should exit 0"
assert_value "${healthy_output}" "management_enabled" "1"
assert_value "${healthy_output}" "management_healthy" "1"
assert_value "${healthy_output}" "management_reason" "ok"
assert_value "${healthy_output}" "wireless_debug_enabled" "1"
assert_value "${healthy_output}" "wireless_debug_tls_port" "43463"
assert_value "${healthy_output}" "wireless_debug_healthy" "1"
assert_value "${healthy_output}" "wireless_debug_reason" "ok"
assert_value "${healthy_output}" "ssh_auth_mode" "key_password"
assert_value "${healthy_output}" "ssh_password_runtime_mismatch" "0"
assert_value "${healthy_output}" "management_auth_consistent" "1"
assert_value "${healthy_output}" "management_auth_warning_reason" "ok"
assert_non_empty "${healthy_output}" "pm_path"
assert_non_empty "${healthy_output}" "am_path"
assert_non_empty "${healthy_output}" "logcat_path"

write_vpn_report 1 ''
run_contract
missing_tailnet_rc="${RUN_CONTRACT_RC}"
missing_tailnet_output="${RUN_CONTRACT_OUTPUT}"
[[ "${missing_tailnet_rc}" != "0" ]] || fail "missing tailnet IP should fail"
assert_value "${missing_tailnet_output}" "management_healthy" "0"
assert_value "${missing_tailnet_output}" "management_reason" "tailnet_ip_missing"

write_vpn_report 1 '100.64.0.10'
write_ss_output ''
run_contract
missing_listener_rc="${RUN_CONTRACT_RC}"
missing_listener_output="${RUN_CONTRACT_OUTPUT}"
[[ "${missing_listener_rc}" != "0" ]] || fail "missing ssh listener should fail"
assert_value "${missing_listener_output}" "management_reason" "ssh_listener_missing"

write_ss_output 'LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=2048,fd=7))'
write_settings_global 1 0
write_getprop_values 0 0
write_wireless_debug_tls_port '43463'
run_contract
wireless_disabled_rc="${RUN_CONTRACT_RC}"
wireless_disabled_output="${RUN_CONTRACT_OUTPUT}"
[[ "${wireless_disabled_rc}" == "0" ]] || fail "wireless debugging should be observational when management does not require it"
assert_value "${wireless_disabled_output}" "wireless_debug_enabled" "0"
assert_value "${wireless_disabled_output}" "wireless_debug_healthy" "0"
assert_value "${wireless_disabled_output}" "wireless_debug_reason" "wireless_debug_disabled"
assert_value "${wireless_disabled_output}" "wireless_debug_tls_port" ""
assert_value "${wireless_disabled_output}" "management_reason" "ok"

write_vpn_config 1 1
run_contract
wireless_required_rc="${RUN_CONTRACT_RC}"
wireless_required_output="${RUN_CONTRACT_OUTPUT}"
[[ "${wireless_required_rc}" != "0" ]] || fail "management should fail when wireless debug is explicitly required"
assert_value "${wireless_required_output}" "management_require_wireless_debug" "1"
assert_value "${wireless_required_output}" "management_reason" "wireless_debug_disabled"

write_vpn_config 1 0

write_getprop_values 1 43463
write_ss_output $'LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=2048,fd=7))\nLISTEN 0 50 *:43463 *:* users:(("adbd",pid=1061,fd=13))'
run_contract
tls_prop_healthy_rc="${RUN_CONTRACT_RC}"
tls_prop_healthy_output="${RUN_CONTRACT_OUTPUT}"
[[ "${tls_prop_healthy_rc}" == "0" ]] || fail "tls property-backed wireless debugging should be healthy even when adb_wifi_enabled stays 0"
assert_value "${tls_prop_healthy_output}" "wireless_debug_enabled" "1"
assert_value "${tls_prop_healthy_output}" "wireless_debug_tls_enabled_prop" "1"
assert_value "${tls_prop_healthy_output}" "wireless_debug_tls_port_prop" "43463"
assert_value "${tls_prop_healthy_output}" "wireless_debug_tls_port" "43463"
assert_value "${tls_prop_healthy_output}" "wireless_debug_live" "1"
assert_value "${tls_prop_healthy_output}" "wireless_debug_live_ports" "43463"
assert_value "${tls_prop_healthy_output}" "wireless_debug_healthy" "1"
assert_value "${tls_prop_healthy_output}" "management_reason" "ok"

write_settings_global 1 1
write_getprop_values 0 0
write_ss_output 'LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=2048,fd=7))'
run_contract
stale_port_rc="${RUN_CONTRACT_RC}"
stale_port_output="${RUN_CONTRACT_OUTPUT}"
[[ "${stale_port_rc}" == "0" ]] || fail "a stale saved wireless debug port should not break management health on its own"
assert_value "${stale_port_output}" "wireless_debug_enabled" "1"
assert_value "${stale_port_output}" "wireless_debug_live" "0"
assert_value "${stale_port_output}" "wireless_debug_tls_port" ""
assert_value "${stale_port_output}" "wireless_debug_healthy" "0"
assert_value "${stale_port_output}" "wireless_debug_reason" "listener_missing"
assert_value "${stale_port_output}" "management_reason" "ok"

write_ss_output $'LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=2048,fd=7))\nLISTEN 0 50 *:5555 *:* users:(("adbd",pid=1061,fd=13))'
run_contract
live_5555_rc="${RUN_CONTRACT_RC}"
live_5555_output="${RUN_CONTRACT_OUTPUT}"
[[ "${live_5555_rc}" == "0" ]] || fail "a live adbd listener on 5555 should be treated as observationally available"
assert_value "${live_5555_output}" "wireless_debug_enabled" "1"
assert_value "${live_5555_output}" "wireless_debug_tls_port" "5555"
assert_value "${live_5555_output}" "wireless_debug_live" "1"
assert_value "${live_5555_output}" "wireless_debug_live_ports" "5555"
assert_value "${live_5555_output}" "wireless_debug_healthy" "1"
assert_value "${live_5555_output}" "management_reason" "ok"

write_wireless_debug_tls_port 43463
write_password_env 1 0
write_passwd '*'
write_legacy_passwd '*'
write_system_passwd '*'
write_password_hash_source '$6$healthyhash'
write_authorized_keys ''
write_runtime_authorized_keys ''
run_contract
password_unready_rc="${RUN_CONTRACT_RC}"
password_unready_output="${RUN_CONTRACT_OUTPUT}"
[[ "${password_unready_rc}" == "0" ]] || fail "password auth drift should stay operational when VPN and SSH are healthy"
assert_value "${password_unready_output}" "ssh_auth_mode" "password_only"
assert_value "${password_unready_output}" "management_healthy" "1"
assert_value "${password_unready_output}" "management_reason" "ok"
assert_value "${password_unready_output}" "management_auth_consistent" "0"
assert_value "${password_unready_output}" "management_auth_warning_reason" "password_auth_runtime_mismatch"

write_password_env 0 1
write_passwd '$6$healthyhash'
write_legacy_passwd '$6$healthyhash'
write_system_passwd '$6$healthyhash'
write_password_hash_source '$6$healthyhash'
write_authorized_keys ''
write_runtime_authorized_keys ''
run_contract
key_unready_rc="${RUN_CONTRACT_RC}"
key_unready_output="${RUN_CONTRACT_OUTPUT}"
[[ "${key_unready_rc}" != "0" ]] || fail "key-only auth without authorized_keys should fail"
assert_value "${key_unready_output}" "ssh_auth_mode" "key_only"
assert_value "${key_unready_output}" "management_reason" "key_auth_not_ready"

write_password_env 1 0
write_passwd '$6$healthyhash'
write_legacy_passwd '$6$stalelegacy'
write_system_passwd '$6$healthyhash'
write_password_hash_source '$6$healthyhash'
write_authorized_keys ''
write_runtime_authorized_keys ''
run_contract
password_mismatch_rc="${RUN_CONTRACT_RC}"
password_mismatch_output="${RUN_CONTRACT_OUTPUT}"
[[ "${password_mismatch_rc}" == "0" ]] || fail "legacy runtime mismatch should stay operational when SSH is still up"
assert_value "${password_mismatch_output}" "ssh_password_runtime_legacy_present" "1"
assert_value "${password_mismatch_output}" "ssh_password_runtime_mismatch" "1"
assert_value "${password_mismatch_output}" "management_reason" "ok"
assert_value "${password_mismatch_output}" "management_auth_consistent" "0"
assert_value "${password_mismatch_output}" "management_auth_warning_reason" "password_auth_runtime_mismatch"

write_password_env 1 0
write_passwd '*'
write_legacy_passwd '*'
write_system_passwd '*'
write_password_hash_source ''
run_contract
password_missing_rc="${RUN_CONTRACT_RC}"
password_missing_output="${RUN_CONTRACT_OUTPUT}"
[[ "${password_missing_rc}" != "0" ]] || fail "missing required password material should fail"
assert_value "${password_missing_output}" "management_healthy" "0"
assert_value "${password_missing_output}" "management_reason" "password_auth_not_ready"
assert_value "${password_missing_output}" "management_auth_consistent" "1"
assert_value "${password_missing_output}" "management_auth_warning_reason" "ok"

echo "PASS: pixel-management-health keeps management on VPN+SSH while reporting truthful wireless debug state"
