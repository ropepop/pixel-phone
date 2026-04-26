#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../../tools/pixel/transport.sh
source "${REPO_ROOT}/tools/pixel/transport.sh"

HOST="${SSH_PERF_HOST:-192.168.31.25}"
PORT="${SSH_PERF_PORT:-2222}"
USER="${SSH_PERF_USER:-root}"
ADB_SERIAL="${SSH_PERF_ADB_SERIAL:-}"
LOCAL_HOST_FOR_PHONE="${SSH_PERF_LOCAL_HOST:-}"
SAMPLES="${SSH_PERF_SAMPLES:-12}"
PING_COUNT="${SSH_PERF_PING_COUNT:-20}"
TIMEOUT_SEC="${SSH_PERF_TIMEOUT_SEC:-5}"
OUTPUT_DIR="${SSH_PERF_OUTPUT_DIR:-output}"
PASSWORD_ENV="${SSH_PERF_PASSWORD_ENV:-TERMUX_SSH_PASSWORD}"
BASELINE_JSON=""

usage() {
  cat <<EOF_USAGE
Usage: $(basename "$0") [options]

Generate SSH performance diagnostics as markdown and JSON.

Options:
  --host HOST               SSH target host/IP (default: ${HOST})
  --port PORT               SSH target port (default: ${PORT})
  --user USER               SSH user for probes (default: ${USER})
  --adb-serial SERIAL       ADB serial for rooted on-device probes
  --local-host HOST         Host IP for phone->host ping probes (auto-detected if omitted)
  --samples N               Number of timing samples (default: ${SAMPLES})
  --ping-count N            Number of ICMP samples (default: ${PING_COUNT})
  --timeout SEC             Connect timeout for probes (default: ${TIMEOUT_SEC})
  --output-dir DIR          Report output directory (default: ${OUTPUT_DIR})
  --password-env VAR        Env var name that stores SSH password (default: ${PASSWORD_ENV})
  --baseline-json PATH      Prior JSON report for before/after delta table
  -h, --help                Show this help text
EOF_USAGE
}

while (( $# > 0 )); do
  case "$1" in
    --host) shift; HOST="${1:-}" ;;
    --port) shift; PORT="${1:-}" ;;
    --user) shift; USER="${1:-}" ;;
    --adb-serial) shift; ADB_SERIAL="${1:-}" ;;
    --local-host) shift; LOCAL_HOST_FOR_PHONE="${1:-}" ;;
    --samples) shift; SAMPLES="${1:-}" ;;
    --ping-count) shift; PING_COUNT="${1:-}" ;;
    --timeout) shift; TIMEOUT_SEC="${1:-}" ;;
    --output-dir) shift; OUTPUT_DIR="${1:-}" ;;
    --password-env) shift; PASSWORD_ENV="${1:-}" ;;
    --baseline-json) shift; BASELINE_JSON="${1:-}" ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

if [[ -z "${HOST}" || -z "${USER}" || -z "${PASSWORD_ENV}" ]]; then
  echo "Host, user, and password env variable name must be non-empty" >&2
  exit 2
fi

for val in "${PORT}" "${SAMPLES}" "${PING_COUNT}" "${TIMEOUT_SEC}"; do
  if [[ ! "${val}" =~ ^[0-9]+$ ]]; then
    echo "Numeric options must be integers" >&2
    exit 2
  fi
done

if (( PORT < 1 || PORT > 65535 || SAMPLES < 1 || PING_COUNT < 1 || TIMEOUT_SEC < 1 )); then
  echo "One or more numeric options are out of range" >&2
  exit 2
fi

if [[ -n "${BASELINE_JSON}" && ! -f "${BASELINE_JSON}" ]]; then
  echo "Baseline JSON not found: ${BASELINE_JSON}" >&2
  exit 2
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd ping
require_cmd nc
require_cmd ssh
require_cmd awk
require_cmd sed
require_cmd sort
require_cmd jq

timestamp_iso() {
  date '+%Y-%m-%dT%H:%M:%S%z'
}

json_num_array_from_file() {
  local file="$1"
  jq -Rcs 'split("\n") | map(select(length > 0) | tonumber)' "${file}"
}

json_int_array_from_file() {
  local file="$1"
  jq -Rcs 'split("\n") | map(select(length > 0) | tonumber)' "${file}"
}

json_lines_from_stdin() {
  jq -Rcs 'split("\n") | map(select(length > 0))'
}

collect_ping_samples() {
  local host="$1"
  local count="$2"
  local out_file="$3"
  set +e
  ping -c "${count}" "${host}" 2>/dev/null \
    | awk -F'time=' '/time=/{split($2,a," "); print a[1]}' > "${out_file}"
  set -e
}

collect_timed_command_samples() {
  local command="$1"
  local samples="$2"
  local values_file="$3"
  local status_file="$4"

  : > "${values_file}"
  : > "${status_file}"

  local i output rc real
  for i in $(seq 1 "${samples}"); do
    set +e
    output="$({ /usr/bin/time -p sh -c "${command}"; } 2>&1)"
    rc=$?
    set -e
    real="$(printf '%s\n' "${output}" | awk '/^real /{print $2}' | tail -n 1)"
    if [[ -n "${real}" ]]; then
      printf '%s\n' "${real}" >> "${values_file}"
    fi
    printf '%s\n' "${rc}" >> "${status_file}"
  done
}

adb_available=0
adb_target=""
if command -v adb >/dev/null 2>&1; then
  if [[ -n "${ADB_SERIAL}" ]]; then
    adb_target="${ADB_SERIAL}"
  else
    adb_target="$(pixel_transport_require_adb_serial 2>/dev/null || true)"
  fi

  if [[ -n "${adb_target}" ]] && adb -s "${adb_target}" get-state >/dev/null 2>&1; then
    adb_available=1
  fi
fi

if [[ -z "${LOCAL_HOST_FOR_PHONE}" ]]; then
  iface="$(route -n get "${HOST}" 2>/dev/null | awk '/interface:/{print $2; exit}')"
  if [[ -n "${iface}" ]]; then
    LOCAL_HOST_FOR_PHONE="$(ipconfig getifaddr "${iface}" 2>/dev/null || true)"
  fi
fi
if [[ -z "${LOCAL_HOST_FOR_PHONE}" ]]; then
  LOCAL_HOST_FOR_PHONE="$(ipconfig getifaddr en0 2>/dev/null || true)"
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

host_ping_file="${tmp_dir}/host_ping_ms.txt"
phone_ping_file="${tmp_dir}/phone_ping_ms.txt"
tcp_values_file="${tmp_dir}/tcp_values.txt"
tcp_status_file="${tmp_dir}/tcp_status.txt"
preauth_values_file="${tmp_dir}/preauth_values.txt"
preauth_status_file="${tmp_dir}/preauth_status.txt"
password_values_file="${tmp_dir}/password_values.txt"
password_status_file="${tmp_dir}/password_status.txt"

collect_ping_samples "${HOST}" "${PING_COUNT}" "${host_ping_file}"

phone_ping_available=0
phone_ping_reason=""
: > "${phone_ping_file}"
if (( adb_available == 1 )) && [[ -n "${LOCAL_HOST_FOR_PHONE}" ]]; then
  set +e
  phone_ping_raw="$(adb -s "${adb_target}" shell su -c "ping -c ${PING_COUNT} ${LOCAL_HOST_FOR_PHONE}" 2>/dev/null)"
  phone_ping_rc=$?
  set -e
  if (( phone_ping_rc == 0 )) || [[ -n "${phone_ping_raw}" ]]; then
    printf '%s\n' "${phone_ping_raw}" \
      | awk -F'time=' '/time=/{split($2,a," "); print a[1]}' > "${phone_ping_file}"
    if [[ -s "${phone_ping_file}" ]]; then
      phone_ping_available=1
    else
      phone_ping_reason="No numeric ping timings parsed from Android output"
    fi
  else
    phone_ping_reason="Phone->host ping command failed"
  fi
elif (( adb_available != 1 )); then
  phone_ping_reason="ADB target unavailable"
else
  phone_ping_reason="Local host IP for phone ping could not be resolved"
fi

tcp_command="nc -z -w ${TIMEOUT_SEC} ${HOST} ${PORT}"
collect_timed_command_samples "${tcp_command}" "${SAMPLES}" "${tcp_values_file}" "${tcp_status_file}"

preauth_command="ssh -o BatchMode=yes -o NumberOfPasswordPrompts=0 -o PreferredAuthentications=none -o PubkeyAuthentication=no -o PasswordAuthentication=no -o ConnectTimeout=${TIMEOUT_SEC} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -p ${PORT} ${USER}@${HOST} exit"
collect_timed_command_samples "${preauth_command}" "${SAMPLES}" "${preauth_values_file}" "${preauth_status_file}"

password_available=0
password_reason=""
: > "${password_values_file}"
: > "${password_status_file}"

password_value="${!PASSWORD_ENV-}"
if [[ -z "${password_value}" ]]; then
  password_reason="Password env var '${PASSWORD_ENV}' is empty or unset"
elif ! command -v expect >/dev/null 2>&1; then
  password_reason="'expect' is not installed"
else
  password_available=1
  expect_probe="${tmp_dir}/ssh_password_probe.expect"
  cat > "${expect_probe}" <<'EOF_EXPECT'
#!/usr/bin/expect -f
set timeout [expr {$env(SSH_PROBE_TIMEOUT) + 5}]
log_user 0
set sent 0
spawn ssh \
  -o BatchMode=no \
  -o PreferredAuthentications=password \
  -o PubkeyAuthentication=no \
  -o KbdInteractiveAuthentication=no \
  -o NumberOfPasswordPrompts=1 \
  -o ConnectTimeout=$env(SSH_PROBE_TIMEOUT) \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  -o LogLevel=ERROR \
  -p $env(SSH_PROBE_PORT) \
  "$env(SSH_PROBE_USER)@$env(SSH_PROBE_HOST)" \
  exit
expect {
  -re "(?i)password:" {
    if {$sent == 0} {
      send -- "$env(SSH_PROBE_PASSWORD)\r"
      set sent 1
      exp_continue
    } else {
      exit 5
    }
  }
  timeout { exit 124 }
  eof {}
}
catch wait result
set rc [lindex $result 3]
if {$rc eq ""} {
  set rc 0
}
exit $rc
EOF_EXPECT
  chmod 0700 "${expect_probe}"

  for _ in $(seq 1 "${SAMPLES}"); do
    set +e
    output="$({ /usr/bin/time -p env \
      SSH_PROBE_HOST="${HOST}" \
      SSH_PROBE_PORT="${PORT}" \
      SSH_PROBE_USER="${USER}" \
      SSH_PROBE_TIMEOUT="${TIMEOUT_SEC}" \
      SSH_PROBE_PASSWORD="${password_value}" \
      "${expect_probe}"; } 2>&1)"
    rc=$?
    set -e
    real="$(printf '%s\n' "${output}" | awk '/^real /{print $2}' | tail -n 1)"
    if [[ -n "${real}" ]]; then
      printf '%s\n' "${real}" >> "${password_values_file}"
    fi
    printf '%s\n' "${rc}" >> "${password_status_file}"
  done
fi

ssh_loop_raw=""
dropbear_local_raw=""
dropbear_legacy_raw=""
listeners_2222_raw=""
dropbear_log_raw=""
service_loop_log_raw=""
wifi_indicator_raw=""

if (( adb_available == 1 )); then
  ssh_loop_raw="$(adb -s "${adb_target}" shell su -c "pgrep -af '^(/system/bin/)?sh /data/local/pixel-stack/ssh/bin/pixel-ssh-service-loop$' || true" 2>/dev/null || true)"
  dropbear_local_raw="$(adb -s "${adb_target}" shell su -c "pgrep -af '^/data/local/pixel-stack/ssh/bin/dropbear' || true" 2>/dev/null || true)"
  dropbear_legacy_raw="$(adb -s "${adb_target}" shell su -c "pgrep -af '^/data/adb/pixel-stack/ssh/bin/dropbear' || true" 2>/dev/null || true)"
  listeners_2222_raw="$(adb -s "${adb_target}" shell su -c "ss -ltnp | grep 2222 || true" 2>/dev/null || true)"
  dropbear_log_raw="$(adb -s "${adb_target}" shell su -c "tail -n 120 /data/local/pixel-stack/ssh/logs/dropbear.log || true" 2>/dev/null || true)"
  service_loop_log_raw="$(adb -s "${adb_target}" shell su -c "tail -n 120 /data/local/pixel-stack/ssh/logs/pixel-ssh-service-loop.log || true" 2>/dev/null || true)"
  wifi_indicator_raw="$(adb -s "${adb_target}" shell su -c "dumpsys wifi | grep -i -E 'low latency|hi-perf|suspend|mSuspendOptimizationsEnabled|Locks acquired|Locks released' | head -n 120 || true" 2>/dev/null || true)"
fi

address_in_use_count="$(printf '%s\n' "${dropbear_log_raw}" | awk '/Address already in use/{c++} END{print c+0}')"
invalid_option_count="$(printf '%s\n' "${dropbear_log_raw}" | awk '/Invalid option/{c++} END{print c+0}')"

host_ping_samples_json="$(json_num_array_from_file "${host_ping_file}")"
phone_ping_samples_json="$(json_num_array_from_file "${phone_ping_file}")"
tcp_samples_json="$(json_num_array_from_file "${tcp_values_file}")"
tcp_exit_json="$(json_int_array_from_file "${tcp_status_file}")"
preauth_samples_json="$(json_num_array_from_file "${preauth_values_file}")"
preauth_exit_json="$(json_int_array_from_file "${preauth_status_file}")"
password_samples_json="$(json_num_array_from_file "${password_values_file}")"
password_exit_json="$(json_int_array_from_file "${password_status_file}")"

ssh_loop_lines_json="$(printf '%s\n' "${ssh_loop_raw}" | json_lines_from_stdin)"
dropbear_local_lines_json="$(printf '%s\n' "${dropbear_local_raw}" | json_lines_from_stdin)"
dropbear_legacy_lines_json="$(printf '%s\n' "${dropbear_legacy_raw}" | json_lines_from_stdin)"
listeners_2222_lines_json="$(printf '%s\n' "${listeners_2222_raw}" | json_lines_from_stdin)"
dropbear_log_lines_json="$(printf '%s\n' "${dropbear_log_raw}" | json_lines_from_stdin)"
service_loop_log_lines_json="$(printf '%s\n' "${service_loop_log_raw}" | json_lines_from_stdin)"
wifi_indicator_lines_json="$(printf '%s\n' "${wifi_indicator_raw}" | json_lines_from_stdin)"

mkdir -p "${OUTPUT_DIR}"
report_stamp="$(date '+%Y%m%d-%H%M%S')"
json_out="${OUTPUT_DIR}/ssh-performance-${report_stamp}.json"
md_out="${OUTPUT_DIR}/ssh-performance-${report_stamp}.md"

jq -n \
  --arg timestamp "$(timestamp_iso)" \
  --arg host "${HOST}" \
  --arg port "${PORT}" \
  --arg user "${USER}" \
  --arg adb_serial "${ADB_SERIAL}" \
  --arg adb_target "${adb_target}" \
  --arg local_host_for_phone "${LOCAL_HOST_FOR_PHONE}" \
  --arg samples "${SAMPLES}" \
  --arg ping_count "${PING_COUNT}" \
  --arg timeout_sec "${TIMEOUT_SEC}" \
  --arg password_env "${PASSWORD_ENV}" \
  --arg baseline_json "${BASELINE_JSON}" \
  --arg phone_ping_reason "${phone_ping_reason}" \
  --arg password_reason "${password_reason}" \
  --argjson adb_available "$(if (( adb_available == 1 )); then echo true; else echo false; fi)" \
  --argjson phone_ping_available "$(if (( phone_ping_available == 1 )); then echo true; else echo false; fi)" \
  --argjson password_available "$(if (( password_available == 1 )); then echo true; else echo false; fi)" \
  --argjson host_ping_samples "${host_ping_samples_json}" \
  --argjson phone_ping_samples "${phone_ping_samples_json}" \
  --argjson tcp_samples "${tcp_samples_json}" \
  --argjson tcp_exit_codes "${tcp_exit_json}" \
  --argjson preauth_samples "${preauth_samples_json}" \
  --argjson preauth_exit_codes "${preauth_exit_json}" \
  --argjson password_samples "${password_samples_json}" \
  --argjson password_exit_codes "${password_exit_json}" \
  --argjson ssh_loop_lines "${ssh_loop_lines_json}" \
  --argjson dropbear_local_lines "${dropbear_local_lines_json}" \
  --argjson dropbear_legacy_lines "${dropbear_legacy_lines_json}" \
  --argjson listeners_2222_lines "${listeners_2222_lines_json}" \
  --argjson dropbear_log_lines "${dropbear_log_lines_json}" \
  --argjson service_loop_log_lines "${service_loop_log_lines_json}" \
  --argjson wifi_indicator_lines "${wifi_indicator_lines_json}" \
  --argjson address_in_use_count "${address_in_use_count}" \
  --argjson invalid_option_count "${invalid_option_count}" \
  '
  def stats(arr):
    if (arr | length) == 0 then
      {count:0, min:null, avg:null, max:null, stddev:null, p95:null}
    else
      (arr | sort) as $sorted
      | (arr | length) as $n
      | (arr | add / $n) as $avg
      | {
          count: $n,
          min: $sorted[0],
          avg: $avg,
          max: $sorted[-1],
          stddev: ((arr | map((. - $avg) * (. - $avg)) | add) / $n | sqrt),
          p95: $sorted[((($n * 0.95) | ceil) - 1)]
        }
    end;

  def exit_summary(arr):
    {
      total: (arr | length),
      success: (arr | map(select(. == 0)) | length),
      failure: (arr | map(select(. != 0)) | length)
    };

  {
    timestamp: $timestamp,
    target: {
      host: $host,
      port: ($port | tonumber),
      user: $user,
      adb_serial: (if $adb_serial == "" then null else $adb_serial end),
      adb_target: (if $adb_target == "" then null else $adb_target end),
      local_host_for_phone: (if $local_host_for_phone == "" then null else $local_host_for_phone end)
    },
    settings: {
      samples: ($samples | tonumber),
      ping_count: ($ping_count | tonumber),
      timeout_sec: ($timeout_sec | tonumber),
      password_env: $password_env,
      baseline_json: (if $baseline_json == "" then null else $baseline_json end)
    },
    probes: {
      host_to_phone_ping: {
        samples: $host_ping_samples,
        stats: stats($host_ping_samples)
      },
      phone_to_host_ping: {
        available: $phone_ping_available,
        skipped_reason: (if $phone_ping_available then null else $phone_ping_reason end),
        samples: $phone_ping_samples,
        stats: stats($phone_ping_samples)
      },
      tcp_connect: {
        samples: $tcp_samples,
        exit_codes: $tcp_exit_codes,
        exit_summary: exit_summary($tcp_exit_codes),
        stats: stats($tcp_samples)
      },
      ssh_preauth: {
        expected_failure: true,
        samples: $preauth_samples,
        exit_codes: $preauth_exit_codes,
        exit_summary: exit_summary($preauth_exit_codes),
        stats: stats($preauth_samples)
      },
      ssh_password_auth: {
        available: $password_available,
        skipped_reason: (if $password_available then null else $password_reason end),
        samples: $password_samples,
        exit_codes: $password_exit_codes,
        exit_summary: exit_summary($password_exit_codes),
        stats: stats($password_samples)
      }
    },
    runtime: {
      adb_available: $adb_available,
      ssh_loop_processes: $ssh_loop_lines,
      dropbear_local_processes: $dropbear_local_lines,
      dropbear_legacy_processes: $dropbear_legacy_lines,
      listeners_2222: $listeners_2222_lines,
      dropbear_log_tail: $dropbear_log_lines,
      service_loop_log_tail: $service_loop_log_lines,
      error_counts: {
        address_already_in_use: $address_in_use_count,
        invalid_option: $invalid_option_count
      }
    },
    wifi: {
      indicator_lines: $wifi_indicator_lines
    },
    assessment: {
      worst_bottleneck: (
        if (stats($host_ping_samples).stddev // 0) >= 15 then
          "network_jitter"
        elif ($password_available and (stats($password_samples).avg // 0) >= 0.6) then
          "password_auth_overhead"
        elif (stats($preauth_samples).avg // 0) >= 0.3 then
          "ssh_handshake_overhead"
        else
          "no_clear_outlier"
        end
      )
    }
  }
  ' > "${json_out}"

fmt_num() {
  local value="$1"
  if [[ -z "${value}" || "${value}" == "null" ]]; then
    printf 'n/a'
  else
    awk -v v="${value}" 'BEGIN{printf "%.3f", v}'
  fi
}

metric_row() {
  local label="$1"
  local base="$2"
  local count avg p95 stddev min max
  count="$(jq -r "${base}.count" "${json_out}")"
  avg="$(fmt_num "$(jq -r "${base}.avg" "${json_out}")")"
  p95="$(fmt_num "$(jq -r "${base}.p95" "${json_out}")")"
  stddev="$(fmt_num "$(jq -r "${base}.stddev" "${json_out}")")"
  min="$(fmt_num "$(jq -r "${base}.min" "${json_out}")")"
  max="$(fmt_num "$(jq -r "${base}.max" "${json_out}")")"
  if [[ "${count}" == "null" ]]; then
    count="n/a"
  fi
  printf '| %s | %s | %s | %s | %s | %s | %s |\n' "${label}" "${count}" "${avg}" "${p95}" "${stddev}" "${min}" "${max}"
}

emit_lines_block() {
  local jq_expr="$1"
  local content
  content="$(jq -r "${jq_expr}[]?" "${json_out}")"
  if [[ -z "${content}" ]]; then
    echo "(none)"
  else
    printf '%s\n' "${content}"
  fi
}

{
  echo "# SSH Performance Report"
  echo
  echo "- Generated: $(jq -r '.timestamp' "${json_out}")"
  echo "- Target: $(jq -r '.target.user + "@" + .target.host + ":" + (.target.port|tostring)' "${json_out}")"
  echo "- ADB target: $(jq -r '.target.adb_target // "none"' "${json_out}")"
  echo "- Worst bottleneck: $(jq -r '.assessment.worst_bottleneck' "${json_out}")"
  echo
  echo "## Probe Statistics (ms)"
  echo
  echo "| Metric | Count | Avg | P95 | StdDev | Min | Max |"
  echo "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"
  metric_row "Host -> Phone ping" '.probes.host_to_phone_ping.stats'
  metric_row "Phone -> Host ping" '.probes.phone_to_host_ping.stats'
  metric_row "TCP connect (:${PORT})" '.probes.tcp_connect.stats'
  metric_row "SSH pre-auth handshake" '.probes.ssh_preauth.stats'
  metric_row "SSH password auth" '.probes.ssh_password_auth.stats'
  echo
  if [[ "$(jq -r '.probes.phone_to_host_ping.available' "${json_out}")" != "true" ]]; then
    echo "- Phone->host ping skipped: $(jq -r '.probes.phone_to_host_ping.skipped_reason' "${json_out}")"
  fi
  if [[ "$(jq -r '.probes.ssh_password_auth.available' "${json_out}")" != "true" ]]; then
    echo "- SSH password probe skipped: $(jq -r '.probes.ssh_password_auth.skipped_reason' "${json_out}")"
  fi
  echo "- TCP connect success/failure: $(jq -r '.probes.tcp_connect.exit_summary.success' "${json_out}")/$(jq -r '.probes.tcp_connect.exit_summary.failure' "${json_out}")"
  echo "- SSH pre-auth success/failure: $(jq -r '.probes.ssh_preauth.exit_summary.success' "${json_out}")/$(jq -r '.probes.ssh_preauth.exit_summary.failure' "${json_out}")"
  echo "- SSH password success/failure: $(jq -r '.probes.ssh_password_auth.exit_summary.success' "${json_out}")/$(jq -r '.probes.ssh_password_auth.exit_summary.failure' "${json_out}")"
  echo
  echo "## Runtime Checks"
  echo
  echo "- local_dropbear_running: $(jq -r '.runtime.dropbear_local_processes | length > 0' "${json_out}")"
  echo "- legacy_dropbear_running: $(jq -r '.runtime.dropbear_legacy_processes | length > 0' "${json_out}")"
  echo "- listener_2222_present: $(jq -r '.runtime.listeners_2222 | length > 0' "${json_out}")"
  echo "- dropbear_log 'Address already in use' count: $(jq -r '.runtime.error_counts.address_already_in_use' "${json_out}")"
  echo "- dropbear_log 'Invalid option' count: $(jq -r '.runtime.error_counts.invalid_option' "${json_out}")"
  echo
  echo "### Local Dropbear Processes"
  echo
  echo '```text'
  emit_lines_block '.runtime.dropbear_local_processes'
  echo '```'
  echo
  echo "### Legacy Dropbear Processes (/data/adb)"
  echo
  echo '```text'
  emit_lines_block '.runtime.dropbear_legacy_processes'
  echo '```'
  echo
  echo "### Wi-Fi Indicators"
  echo
  echo '```text'
  emit_lines_block '.wifi.indicator_lines'
  echo '```'
} > "${md_out}"

if [[ -n "${BASELINE_JSON}" ]]; then
  if jq empty "${BASELINE_JSON}" >/dev/null 2>&1; then
    {
      echo
      echo "## Baseline Delta"
      echo
      echo "| Metric | Baseline | Current | Delta |"
      echo "| --- | ---: | ---: | ---: |"
    } >> "${md_out}"

    delta_row() {
      local label="$1"
      local path="$2"
      local base cur delta
      base="$(jq -r "${path}" "${BASELINE_JSON}")"
      cur="$(jq -r "${path}" "${json_out}")"
      if [[ "${base}" == "null" || -z "${base}" || "${cur}" == "null" || -z "${cur}" ]]; then
        printf '| %s | n/a | n/a | n/a |\n' "${label}" >> "${md_out}"
        return
      fi
      delta="$(awk -v b="${base}" -v c="${cur}" 'BEGIN{printf "%+.3f", c-b}')"
      printf '| %s | %.3f | %.3f | %s |\n' "${label}" "${base}" "${cur}" "${delta}" >> "${md_out}"
    }

    delta_row "Host->Phone ping avg (ms)" '.probes.host_to_phone_ping.stats.avg'
    delta_row "Host->Phone ping stddev (ms)" '.probes.host_to_phone_ping.stats.stddev'
    delta_row "TCP connect avg (ms)" '.probes.tcp_connect.stats.avg'
    delta_row "TCP connect stddev (ms)" '.probes.tcp_connect.stats.stddev'
    delta_row "SSH pre-auth avg (ms)" '.probes.ssh_preauth.stats.avg'
    delta_row "SSH pre-auth stddev (ms)" '.probes.ssh_preauth.stats.stddev'
    delta_row "SSH password avg (ms)" '.probes.ssh_password_auth.stats.avg'
    delta_row "SSH password stddev (ms)" '.probes.ssh_password_auth.stats.stddev'

    baseline_stddev="$(jq -r '.probes.host_to_phone_ping.stats.stddev // null' "${BASELINE_JSON}")"
    current_stddev="$(jq -r '.probes.host_to_phone_ping.stats.stddev // null' "${json_out}")"
    if [[ "${baseline_stddev}" != "null" && "${current_stddev}" != "null" ]]; then
      improvement="$(awk -v b="${baseline_stddev}" -v c="${current_stddev}" 'BEGIN{if (b<=0) {print "n/a"} else {printf "%.1f", ((b-c)/b)*100}}')"
      if [[ "${improvement}" == "n/a" ]]; then
        target_status="n/a"
      else
        target_status="$(awk -v i="${improvement}" 'BEGIN{if (i>=30) print "pass"; else print "fail"}')"
      fi
      {
        echo
        echo "- Host->phone ping stddev improvement: ${improvement}% (target >= 30%): ${target_status}"
      } >> "${md_out}"
    fi
  else
    {
      echo
      echo "## Baseline Delta"
      echo
      echo "- Baseline JSON could not be parsed: ${BASELINE_JSON}"
    } >> "${md_out}"
  fi
fi

echo "JSON report: ${json_out}"
echo "Markdown report: ${md_out}"
