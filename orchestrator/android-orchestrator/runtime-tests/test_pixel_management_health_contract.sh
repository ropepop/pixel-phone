#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MANAGEMENT_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-management-health.sh"
NATIVE="${PIXEL_MANAGEMENT_HEALTH_EXECUTABLE:-${REPO_ROOT}/android-orchestrator/pixel-health/target/release/pixel-runtime-cleanup}"

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT
legacy_script="${tmpdir}/legacy-management-health.sh"
git -C "${REPO_ROOT}/.." show b4a66c24979136be2b95f8e923568e64a80bb945:orchestrator/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-management-health.sh > "${legacy_script}"
[[ "$(shasum -a 256 "${legacy_script}" | awk '{print $1}')" == c721a6daa577bd105ff4b6ef902e9a03ff68c78bd7511263f8ce2d7bcf852df8 ]] || { echo 'FAIL: management oracle changed' >&2; exit 1; }
if [[ -z "${PIXEL_MANAGEMENT_HEALTH_EXECUTABLE:-}" ]]; then
  cargo build --locked --release --manifest-path "${REPO_ROOT}/android-orchestrator/pixel-health/Cargo.toml" --bin pixel-runtime-cleanup
fi
[[ -x "${NATIVE}" ]] || { echo 'FAIL: native management executable missing' >&2; exit 1; }
mkdir "${tmpdir}/native-bundle"
cp "${NATIVE}" "${tmpdir}/native-bundle/pixel-runtime-cleanup"
cp "${MANAGEMENT_SCRIPT}" "${tmpdir}/native-bundle/pixel-management-health.sh"
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
nc_output_file="${tmpdir}/nc-output.txt"

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
CONTRACT_COUNT=0

invoke_contract() {
  local owner="$1"
  shift
  local -a invocation=()
  if [[ "${owner}" == native ]]; then
    invocation=(sh "${tmpdir}/native-bundle/pixel-management-health.sh")
  else
    invocation=(bash "${legacy_script}")
  fi
    PATH="${CONTRACT_PATH:-${fake_bin_dir}:$PATH}" \
      PIXEL_STACK_BIN_DIR="${stack_bin_dir}" \
      PIXEL_SSH_ROOT="${ssh_root}" \
      PIXEL_SSH_LEGACY_ROOT="${ssh_legacy_root}" \
      PIXEL_VPN_ROOT="${tmpdir}/vpn" \
      PIXEL_DDNS_CONF_FILE="${tmpdir}/conf/ddns.env" \
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
      FAKE_NC_OUTPUT_FILE="${nc_output_file}" \
      FAKE_NETWORK_EFFECTS_FILE="${tmpdir}/network-effects" \
      FAKE_ID_UID="${CONTRACT_UID:-0}" \
      "${invocation[@]}" "$@"
}

run_contract() {
  local output="" old_output="" old_rc=0
  if [[ "$#" == 0 && "${CONTRACT_SILENT:-0}" != 1 ]]; then
    set -- --report
  fi
  set +e
  invoke_contract native "$@" >"${tmpdir}/native-output" 2>"${tmpdir}/native-error"
  RUN_CONTRACT_RC=$?
  invoke_contract legacy "$@" >"${tmpdir}/legacy-output" 2>"${tmpdir}/legacy-error"
  old_rc=$?
  set -e
  output="$(cat "${tmpdir}/native-output")"
  old_output="$(cat "${tmpdir}/legacy-output")"
  [[ "${RUN_CONTRACT_RC}" == "${old_rc}" ]] || fail "old/native exit mismatch: ${old_rc}/${RUN_CONTRACT_RC}"
  cmp -s "${tmpdir}/native-output" "${tmpdir}/legacy-output" || { diff -u "${tmpdir}/legacy-output" "${tmpdir}/native-output" >&2; fail 'old/native report mismatch'; }
  cmp -s "${tmpdir}/native-error" "${tmpdir}/legacy-error" || { diff -u "${tmpdir}/legacy-error" "${tmpdir}/native-error" >&2; fail 'old/native error mismatch'; }
  [[ "${output}" != *'$6$'* && "${output}" != *AAAAC3Nza* ]] || fail 'private authentication material entered report'
  ! grep -Eq '\$6\$|AAAAC3Nza' "${tmpdir}/native-error" || fail 'private authentication material entered error'
  RUN_CONTRACT_OUTPUT="${output}"
  CONTRACT_COUNT=$((CONTRACT_COUNT + 1))
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
printf 'curl %s\n' "$*" >> "${FAKE_NETWORK_EFFECTS_FILE}"
cat "${FAKE_CURL_OUTPUT_FILE}" 2>/dev/null || true
EOF
chmod +x "${fake_bin_dir}/curl"

cat > "${fake_bin_dir}/nc" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'nc %s\n' "$*" >> "${FAKE_NETWORK_EFFECTS_FILE}"
cat "${FAKE_NC_OUTPUT_FILE}"
EOF
chmod +x "${fake_bin_dir}/nc"
printf 'SSH-2.0-dropbear_fixture\r\n' > "${nc_output_file}"
: > "${tmpdir}/network-effects"

for command_name in pm am logcat; do
  cat > "${fake_bin_dir}/${command_name}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
  chmod +x "${fake_bin_dir}/${command_name}"
done

healthy_fixture() {
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
CONTRACT_UID=0
}
healthy_fixture
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

# Failure reasons retain their original precedence when several facts fail together.
healthy_fixture
CONTRACT_UID=1000
write_vpn_report 0 ''
write_ss_output ''
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason root_unavailable
rm "${fake_bin_dir}/logcat"
CONTRACT_UID=0
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason android_command_missing
printf '#!/bin/sh\nexit 0\n' > "${fake_bin_dir}/logcat"
chmod +x "${fake_bin_dir}/logcat"
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason vpn_unhealthy
write_vpn_report 1 ''
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason tailnet_ip_missing
write_vpn_report 1 '100.64.0.10'
write_vpn_config 1 1
write_getprop_values 0 0
write_settings_global 0 0
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason adb_disabled
write_settings_global 1 0
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason wireless_debug_disabled
write_settings_global 1 1
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason listener_missing
printf 'vpn_enabled=0\n' > "${vpn_report_file}"
CONTRACT_UID=1000
run_contract
[[ "${RUN_CONTRACT_RC}" == 0 ]] || fail 'disabled management must stay neutral'
assert_value "${RUN_CONTRACT_OUTPUT}" management_auth_consistent 1
assert_value "${RUN_CONTRACT_OUTPUT}" management_auth_warning_reason disabled
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason disabled

# Only a Dropbear-owned non-loopback listener qualifies; live ADB ports are stable and unique.
healthy_fixture
write_ss_output $'LISTEN 0 128 [::1]:2222 [::]:* users:(("dropbear",pid=2,fd=7))\nLISTEN 0 128 127.0.0.1:2222 *:* users:(("dropbear",pid=2,fd=7))\nLISTEN 0 128 [::]:2222 [::]:* users:(("other",pid=3,fd=7))'
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason ssh_listener_missing
write_getprop_values 0 0
write_ss_output $'LISTEN 0 128 [::]:2222 [::]:* users:(("dropbear",pid=2,fd=7))\nLISTEN 0 50 *:5555 *:* users:(("adbd",pid=5,fd=1))\nLISTEN 0 50 [::]:40001 *:* users:(("adbd",pid=5,fd=2))\nLISTEN 0 50 *:5555 *:* users:(("adbd",pid=5,fd=3))'
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_listener 1
assert_value "${RUN_CONTRACT_OUTPUT}" wireless_debug_live_ports '5555,40001'
assert_value "${RUN_CONTRACT_OUTPUT}" wireless_debug_tls_port 40001

# Missing material blocks its exclusive mode; runtime drift remains a warning and recovery clears it.
healthy_fixture
write_password_env 0 0
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason ssh_auth_unconfigured
write_password_env 1 1
write_password_hash_source x
write_authorized_keys ''
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason ssh_auth_not_ready
healthy_fixture
write_runtime_authorized_keys 'ssh-ed25519 different_fixture'
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason ok
assert_value "${RUN_CONTRACT_OUTPUT}" management_auth_warning_reason key_auth_runtime_mismatch
write_passwd '$6$different_fixture'
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_auth_warning_reason password_auth_runtime_mismatch,key_auth_runtime_mismatch
healthy_fixture
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_auth_consistent 1
assert_value "${RUN_CONTRACT_OUTPUT}" management_auth_warning_reason ok
write_system_passwd '$6$different_fixture'
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_password_runtime_system_ready 0
assert_value "${RUN_CONTRACT_OUTPUT}" management_auth_warning_reason password_auth_runtime_mismatch
healthy_fixture
rm "${ssh_legacy_root}/etc/passwd"
write_password_hash_source $'  \n root:$6$healthyhash:0:0:root:/root:/system/bin/sh\n'
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_password_runtime_legacy_present 0
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_password_auth_ready 1

# The fallback without cmp ignores final newlines only; it must retain other key-byte drift.
healthy_fixture
printf '\n' >> "${runtime_authorized_keys_file}"
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_key_runtime_mismatch 1
mkdir "${tmpdir}/without-cmp"
for name in bash sh env awk sed tr grep cat rm paste dirname; do
  ln -s "$(command -v "${name}")" "${tmpdir}/without-cmp/${name}"
done
for name in ss id settings getprop ip curl nc pm am logcat; do
  ln -s "${fake_bin_dir}/${name}" "${tmpdir}/without-cmp/${name}"
done
CONTRACT_PATH="${tmpdir}/without-cmp" run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_key_runtime_mismatch 0
printf ' \n' >> "${runtime_authorized_keys_file}"
CONTRACT_PATH="${tmpdir}/without-cmp" run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_key_runtime_mismatch 1

# A VPN adapter's shell read protocol retains CR and ignores an unterminated last row.
healthy_fixture
printf 'vpn_enabled=1\r\ntailnet_ipv4=100.64.0.10\n' > "${vpn_report_file}"
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason disabled
printf 'vpn_enabled=0\nvpn_enabled=1' > "${vpn_report_file}"
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason disabled
healthy_fixture
CONTRACT_SILENT=1 run_contract
[[ -z "${RUN_CONTRACT_OUTPUT}" && "${RUN_CONTRACT_RC}" == 0 ]] || fail 'non-report healthy result changed'

# Transport classification and fingerprints come from the real command boundary.
healthy_fixture
for iface in rmnet_data0 tailscale0 eth0 ''; do
  write_ip_route_output "1.1.1.1 ${iface:+dev ${iface}}"
  write_ip_addr_output $'wlan0=192.168.1.50\nrmnet_data0=10.0.0.2'
  run_contract
  case "${iface}" in
    rmnet_data0) expected_transport=cellular ;;
    tailscale0) expected_transport=vpn ;;
    eth0) expected_transport=other ;;
    '') expected_transport=unknown ;;
  esac
  assert_value "${RUN_CONTRACT_OUTPUT}" active_transport "${expected_transport}"
  assert_value "${RUN_CONTRACT_OUTPUT}" mobile_iface rmnet_data0
  assert_value "${RUN_CONTRACT_OUTPUT}" mobile_ipv4 10.0.0.2
done

# Local probes use the cache and never invoke network transports; explicit deep probes do.
healthy_fixture
: > "${tmpdir}/network-effects"
write_curl_output '{"ip":"198.51.100.9"}'
run_contract
[[ ! -s "${tmpdir}/network-effects" ]] || fail 'local health initiated a network handshake'
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 62.205.193.194
run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 198.51.100.9
assert_value "${RUN_CONTRACT_OUTPUT}" management_health_mode deep
grep -q '^curl -fsS --connect-timeout 2 --max-time 4 ' "${tmpdir}/network-effects" || fail 'deep discovery lacked original limits'
grep -q '^nc -w 1 -W 1 ' "${tmpdir}/network-effects" || fail 'deep SSH handshake missing'
printf 'not SSH\n' > "${nc_output_file}"
run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" management_reason ssh_listener_missing
printf 'SSH-2.0-dropbear_fixture\r\n' > "${nc_output_file}"
write_curl_output invalid_candidate
: > "${tmpdir}/network-effects"
run_contract --report --full
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 62.205.193.194
[[ "$(grep -c '^curl ' "${tmpdir}/network-effects")" == 6 ]] || fail 'deep fallback skipped original discovery sequence'
write_curl_output $' 203.0.113.7\r\nignored second line'
run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 203.0.113.7
write_curl_output '{"ip":"198.51.100.1","ip":"999.999.999.999"}'
run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 999.999.999.999
write_curl_output '{"ip":"198.51.100.1","ip":null}'
run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 198.51.100.1
write_curl_output '{"ip":"198.51.100.1'
run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 62.205.193.194
write_curl_output '{"ip":"198.51.100.1","ip":""}'
run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 62.205.193.194
mkdir "${tmpdir}/without-curl"
for command_path in "${tmpdir}/without-cmp/"*; do
  [[ "${command_path##*/}" == curl ]] || ln -s "${command_path}" "${tmpdir}/without-curl/${command_path##*/}"
done
CONTRACT_PATH="${tmpdir}/without-curl" PIXEL_STACK_CURL_ROOTFS="${tmpdir}/missing-rootfs" run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 62.205.193.194
mkdir -p "${tmpdir}/curl-rootfs/usr/bin"
cp "${fake_bin_dir}/curl" "${tmpdir}/curl-rootfs/usr/bin/curl"
cp "$(command -v env)" "${tmpdir}/curl-rootfs/usr/bin/env"
cat > "${tmpdir}/without-curl/chroot" <<'EOF'
#!/usr/bin/env bash
set -eu
root="$1"
shift
[[ "$1 $2 $3 $4" == '/usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl' ]]
shift 4
exec "${root}/usr/bin/curl" "$@"
EOF
chmod +x "${tmpdir}/without-curl/chroot"
write_curl_output '{"ip":"203.0.113.19"}'
CONTRACT_PATH="${tmpdir}/without-curl" PIXEL_STACK_CURL_ROOTFS="${tmpdir}/curl-rootfs" run_contract --report --deep
assert_value "${RUN_CONTRACT_OUTPUT}" public_ipv4_candidate 203.0.113.19

# Existing env syntax/order, stdout, empty defaults and public argument failures remain exact.
healthy_fixture
printf 'SSH_PORT=9999\nprintf "fixture-config-output\\n"\n' >> "${ssh_root}/conf/dropbear.env"
printf 'SSH_PORT=3333\n' >> "${vpn_conf_file}"
printf 'SSH_PORT=\n' > "${tmpdir}/conf/ddns.env"
run_contract
assert_value "${RUN_CONTRACT_OUTPUT}" ssh_port 2222
[[ "${RUN_CONTRACT_OUTPUT}" == fixture-config-output$'\n'* ]] || fail 'config stdout changed'
rm "${tmpdir}/conf/ddns.env"
healthy_fixture
PIXEL_MANAGEMENT_HEALTH_REPORT=1 PIXEL_MANAGEMENT_HEALTH_DEEP=1 run_contract --full
assert_value "${RUN_CONTRACT_OUTPUT}" management_health_mode deep
run_contract --unsupported
[[ "${RUN_CONTRACT_RC}" == 2 && -z "${RUN_CONTRACT_OUTPUT}" ]] || fail 'unknown argument contract changed'
grep -qx 'unsupported management health argument: --unsupported' "${tmpdir}/native-error" || fail 'unknown argument error changed'

echo "PASS: ${CONTRACT_COUNT} frozen shell/native management contracts; exact reports, errors, exit precedence and private-fact protection"
