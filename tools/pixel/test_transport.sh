#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TRANSPORT_SH="${SCRIPT_DIR}/transport.sh"

TMP_DIR="$(mktemp -d)"
ADB_LOG="${TMP_DIR}/adb.log"
EXPECT_LOG="${TMP_DIR}/expect.log"
SSH_LOG="${TMP_DIR}/ssh.log"
FAKE_ADB="${TMP_DIR}/adb"
FAKE_SSH="${TMP_DIR}/ssh"
FAKE_TAILSCALE="${TMP_DIR}/tailscale-app"

cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_contains() {
  local file="$1"
  local needle="$2"
  if ! rg -Fq -- "${needle}" "${file}"; then
    echo "Expected to find: ${needle}" >&2
    echo "In file: ${file}" >&2
    cat "${file}" >&2 || true
    fail "missing expected content"
  fi
}

cat > "${FAKE_ADB}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${PIXEL_TEST_ADB_LOG}"

if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\nSER123\tdevice\n'
  exit 0
fi

if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi

case "${1:-}" in
  get-state)
    printf 'device\n'
    ;;
  shell)
    shell_stdin="$(cat || true)"
    if [[ -n "${shell_stdin}" ]]; then
      printf 'stdin=%s\n' "${shell_stdin}" >> "${PIXEL_TEST_ADB_LOG}"
    fi
    if [[ "${PIXEL_TEST_DRAIN_STDIN:-0}" == "1" ]]; then
      :
    fi
    if [[ "${2:-}" == *"id -u"* ]]; then
      printf '0\n'
    fi
    ;;
  pull)
    : > "${3}"
    ;;
  push)
    if [[ "${PIXEL_TEST_DRAIN_STDIN:-0}" == "1" ]]; then
      cat >/dev/null || true
    fi
    ;;
esac
EOF
chmod +x "${FAKE_ADB}"

cat > "${FAKE_SSH}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${PIXEL_TEST_SSH_LOG}"
exit 0
EOF
chmod +x "${FAKE_SSH}"

cat > "${FAKE_TAILSCALE}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "status" && "${2:-}" == "--json" ]]; then
  cat <<'JSON'
{"BackendState":"Running","Self":{"Online":true,"DNSName":"pixel.tailnet.ts.net.","TailscaleIPs":["100.64.0.10"]}}
JSON
  exit 0
fi
exit 1
EOF
chmod +x "${FAKE_TAILSCALE}"

export PATH="${TMP_DIR}:${PATH}"
export PIXEL_TEST_ADB_LOG="${ADB_LOG}"
export PIXEL_TEST_SSH_LOG="${SSH_LOG}"
export ADB_BIN="${FAKE_ADB}"
export PIXEL_TRANSPORT_FORWARD_DIR="${TMP_DIR}/forward"
export PIXEL_SSH_KNOWN_HOSTS_FILE="${TMP_DIR}/known_hosts"
export PIXEL_DEVICE_SSH_PASSWORD="test-password"

unset PIXEL_TRANSPORT_SH_LOADED
# shellcheck source=./transport.sh
source "${TRANSPORT_SH}"

pixel_transport_expect_run() {
  local program="$1"
  shift
  printf 'program=%s args=%s\n' "${program}" "$*" >> "${EXPECT_LOG}"

  if [[ "${program}" == "ssh" ]]; then
    local previous=""
    local arg=""
    for arg in "$@"; do
      if [[ "${previous}" == "-S" ]]; then
        : > "${arg}"
      fi
      previous="${arg}"
    done
  fi

  if [[ "${program}" == "scp" ]]; then
    local last_arg=""
    for last_arg in "$@"; do
      :
    done
    if [[ -n "${last_arg}" && "${last_arg}" != *:* ]]; then
      : > "${last_arg}"
    fi
  fi
}

test_adb_transport_contract() {
  : > "${ADB_LOG}"
  local local_file="${TMP_DIR}/artifact.bin"
  local pulled_file="${TMP_DIR}/pulled.bin"
  printf 'artifact\n' > "${local_file}"

  PIXEL_TRANSPORT="adb"
  PIXEL_TRANSPORT_RESOLVED=""
  ADB_SERIAL="SER123"

  pixel_transport_root_shell "echo hi" >/dev/null
  pixel_transport_push "${local_file}" "/data/local/tmp/artifact.bin" >/dev/null
  pixel_transport_pull "/data/local/tmp/artifact.bin" "${pulled_file}" >/dev/null
  pixel_transport_install_apk "${local_file}" >/dev/null

  assert_contains "${ADB_LOG}" "-s SER123 shell su -c '/system/bin/sh -s'"
  assert_contains "${ADB_LOG}" "stdin=echo hi"
  assert_contains "${ADB_LOG}" "-s SER123 push ${local_file} /data/local/tmp/pixel-transport-push-"
  assert_contains "${ADB_LOG}" "shell su -c '/system/bin/sh -s'"
  assert_contains "${ADB_LOG}" "mv -f"
  assert_contains "${ADB_LOG}" "pixel-transport-push-"
  assert_contains "${ADB_LOG}" "-s SER123 pull /data/local/tmp/pixel-transport-pull-"
  assert_contains "${ADB_LOG}" "-s SER123 install -r ${local_file}"
}

test_ssh_transport_contract() {
  : > "${EXPECT_LOG}"
  : > "${SSH_LOG}"
  local local_file="${TMP_DIR}/artifact.apk"
  local pulled_file="${TMP_DIR}/ssh-pulled.apk"
  printf 'artifact\n' > "${local_file}"

  PIXEL_TRANSPORT="ssh"
  PIXEL_TRANSPORT_RESOLVED=""
  PIXEL_SSH_HOST="100.64.0.10"
  PIXEL_SSH_PORT="2222"

  pixel_transport_root_shell "echo ssh" >/dev/null
  pixel_transport_push "${local_file}" "/data/local/tmp/artifact.apk" >/dev/null
  pixel_transport_pull "/data/local/tmp/artifact.apk" "${pulled_file}" >/dev/null
  pixel_transport_install_apk "${local_file}" >/dev/null
  pixel_transport_forward_start 18080 8080 >/dev/null
  pixel_transport_forward_stop 18080 >/dev/null

  assert_contains "${EXPECT_LOG}" "program=ssh args=-o LogLevel=ERROR"
  assert_contains "${EXPECT_LOG}" "100.64.0.10"
  assert_contains "${EXPECT_LOG}" "program=scp args=-o LogLevel=ERROR"
  assert_contains "${EXPECT_LOG}" "artifact.apk"
  assert_contains "${EXPECT_LOG}" "install"
  assert_contains "${EXPECT_LOG}" "-L 18080:127.0.0.1:8080"
}

test_auto_transport_selection() {
  local auto_log="${TMP_DIR}/auto.log"
  : > "${auto_log}"

  PIXEL_TRANSPORT="auto"
  PIXEL_TRANSPORT_RESOLVED=""
  ADB_SERIAL=""
  PIXEL_SSH_HOST="100.64.0.10"

  pixel_transport_host_ssh_ready() { return 0; }
  pixel_transport_resolve_adb() {
    echo "adb-fallback" >> "${auto_log}"
    ADB_SERIAL="SER123"
    return 0
  }
  pixel_transport_require_device >/dev/null
  [[ "${PIXEL_TRANSPORT_RESOLVED}" == "ssh" ]] || fail "auto mode should prefer ssh when ready"
  [[ ! -s "${auto_log}" ]] || fail "adb should not be used when ssh is ready"

  PIXEL_TRANSPORT_RESOLVED=""
  pixel_transport_host_ssh_ready() { return 1; }
  pixel_transport_resolve_adb() {
    echo "adb-fallback" >> "${auto_log}"
    ADB_SERIAL="SER123"
    return 0
  }
  pixel_transport_require_device >/dev/null
  [[ "${PIXEL_TRANSPORT_RESOLVED}" == "adb" ]] || fail "auto mode should fall back to adb"
  assert_contains "${auto_log}" "adb-fallback"

  PIXEL_TRANSPORT_RESOLVED=""
  : > "${auto_log}"
  pixel_transport_host_ssh_ready() { return 1; }
  pixel_transport_resolve_adb() { return 1; }
  if pixel_transport_require_device >/dev/null 2>&1; then
    fail "auto mode should fail when neither ssh nor adb is ready"
  fi

  unset -f pixel_transport_host_ssh_ready
  unset -f pixel_transport_resolve_adb
  unset PIXEL_TRANSPORT_SH_LOADED
  # shellcheck source=./transport.sh
  source "${TRANSPORT_SH}"
}

test_management_probe_validation() {
  local healthy_probe='remote_uid=0
management_enabled=1
management_healthy=1
management_reason=ok
vpn_enabled=1
vpn_health=1
tailscaled_sock=1
tailnet_ipv4=100.64.0.10
guard_chain_ipv4=1
guard_chain_ipv6=1
pm_path=/system/bin/pm
am_path=/system/bin/am
logcat_path=/system/bin/logcat'
  local unhealthy_probe='remote_uid=0
management_enabled=1
management_healthy=0
management_reason=ssh_listener_missing
vpn_enabled=1
vpn_health=1
tailscaled_sock=1
tailnet_ipv4=100.64.0.10
guard_chain_ipv4=1
guard_chain_ipv6=1
pm_path=/system/bin/pm
am_path=/system/bin/am
logcat_path=/system/bin/logcat'

  printf '%s\n' "${healthy_probe}" | pixel_transport_validate_management_probe >/dev/null
  if printf '%s\n' "${unhealthy_probe}" | pixel_transport_validate_management_probe >/dev/null 2>&1; then
    fail "management probe validator should reject unhealthy readiness reports"
  fi
}

test_adb_push_does_not_consume_loop_input() {
  local loop_dir="${TMP_DIR}/loop-input"
  local local_file=""
  local count=0

  mkdir -p "${loop_dir}"
  printf 'one\n' > "${loop_dir}/one.bin"
  printf 'two\n' > "${loop_dir}/two.bin"

  PIXEL_TRANSPORT="adb"
  PIXEL_TRANSPORT_RESOLVED=""
  ADB_SERIAL="SER123"
  export PIXEL_TEST_DRAIN_STDIN=1

  while IFS= read -r local_file; do
    [[ -n "${local_file}" ]] || continue
    pixel_transport_push "${local_file}" "/data/local/tmp/$(basename "${local_file}")" >/dev/null
    count=$((count + 1))
  done < <(find "${loop_dir}" -maxdepth 1 -type f | sort)

  unset PIXEL_TEST_DRAIN_STDIN
  [[ "${count}" == "2" ]] || fail "adb push should not consume loop input"
}

test_tailscale_bin_resolution() {
  PIXEL_TAILSCALE_BIN="${FAKE_TAILSCALE}"
  [[ "$(pixel_transport_resolve_tailscale_bin)" == "${FAKE_TAILSCALE}" ]] || fail "expected tailscale resolver to honor PIXEL_TAILSCALE_BIN"
  [[ "$(pixel_transport_tailscale_status_json | python3 -c "import json, sys; print(json.load(sys.stdin)['BackendState'])")" == "Running" ]] || fail "expected tailscale status helper to return bundled CLI output"
  assert_contains "${TRANSPORT_SH}" 'expect -f /dev/stdin -- "$@"'
}

test_adb_transport_contract
test_ssh_transport_contract
test_auto_transport_selection
test_management_probe_validation
test_adb_push_does_not_consume_loop_input
test_tailscale_bin_resolution

echo "PASS: pixel transport supports adb, ssh, and auto selection contracts"
