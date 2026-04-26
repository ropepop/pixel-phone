#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOOP_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-service-loop"

if ! rg -Fq 'PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS:=1' "${LOOP_TEMPLATE}"; then
  echo "FAIL: missing PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS default in ${LOOP_TEMPLATE}" >&2
  exit 1
fi

runtime_health_block="$(sed -n '/^runtime_ports_healthy()/,/^}/p' "${LOOP_TEMPLATE}")"
remote_health_block="$(sed -n '/^runtime_remote_healthy()/,/^}/p' "${LOOP_TEMPLATE}")"
remote_recovery_block="$(sed -n '/^recover_remote_frontend()/,/^}/p' "${LOOP_TEMPLATE}")"
start_runtime_block="$(sed -n '/^start_runtime()/,/^}/p' "${LOOP_TEMPLATE}")"
monitor_block="$(sed -n '/^monitor_runtime_health()/,/^}/p' "${LOOP_TEMPLATE}")"
main_loop_block="$(sed -n '/while true; do/,/^done/p' "${LOOP_TEMPLATE}")"

if [[ -z "${runtime_health_block}" || -z "${remote_health_block}" || -z "${remote_recovery_block}" || -z "${start_runtime_block}" || -z "${monitor_block}" || -z "${main_loop_block}" ]]; then
  echo "FAIL: missing service-loop health helpers in ${LOOP_TEMPLATE}" >&2
  exit 1
fi

if ! printf '%s\n' "${runtime_health_block}" | rg -Fq 'port_tcp_listening "${active_dns_port}" || return 1'; then
  echo "FAIL: service-loop core health no longer requires the DNS listener" >&2
  exit 1
fi

if ! printf '%s\n' "${runtime_health_block}" | rg -Fq 'port_tcp_listening "${web_port}" || return 1'; then
  echo "FAIL: service-loop core health no longer requires the local web listener" >&2
  exit 1
fi

if printf '%s\n' "${runtime_health_block}" | rg -Fq 'remote_https_port'; then
  echo "FAIL: service-loop core health still references remote HTTPS listener state" >&2
  exit 1
fi

if printf '%s\n' "${runtime_health_block}" | rg -Fq 'remote_dot_port'; then
  echo "FAIL: service-loop core health still references remote DoT listener state" >&2
  exit 1
fi

if printf '%s\n' "${runtime_health_block}" | rg -Fq 'PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS'; then
  echo "FAIL: service-loop core health still gates restart decisions on remote listener policy" >&2
  exit 1
fi

if ! printf '%s\n' "${remote_health_block}" | rg -Fq 'PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS'; then
  echo "FAIL: service-loop remote health helper does not honor PIHOLE_SERVICE_ENFORCE_REMOTE_LISTENERS" >&2
  exit 1
fi

if ! printf '%s\n' "${remote_health_block}" | rg -Fq 'run_chroot_helper "${START_BIN}" --remote-healthcheck'; then
  echo "FAIL: service-loop remote health helper does not delegate to adguardhome-start --remote-healthcheck" >&2
  exit 1
fi

if ! printf '%s\n' "${remote_recovery_block}" | rg -Fq 'run_chroot_helper "${START_BIN}" --remote-restart'; then
  echo "FAIL: service-loop remote recovery helper does not delegate to adguardhome-start --remote-restart" >&2
  exit 1
fi

if ! printf '%s\n' "${start_runtime_block}" | rg -Fq 'frontend launch failed after core startup while remote listener enforcement is enabled'; then
  echo "FAIL: start_runtime does not fail fast when remote listener enforcement is enabled" >&2
  exit 1
fi

if ! printf '%s\n' "${start_runtime_block}" | rg -Fq 'if recover_remote_frontend; then'; then
  echo "FAIL: start_runtime does not attempt remote frontend recovery before failing" >&2
  exit 1
fi

if ! printf '%s\n' "${monitor_block}" | rg -Fq 'if runtime_ports_healthy; then'; then
  echo "FAIL: service-loop monitor no longer checks core listener health explicitly" >&2
  exit 1
fi

if ! printf '%s\n' "${monitor_block}" | rg -Fq 'if runtime_remote_healthy; then'; then
  echo "FAIL: service-loop monitor no longer checks remote listener health explicitly" >&2
  exit 1
fi

if ! printf '%s\n' "${main_loop_block}" | rg -Fq 'if start_runtime; then'; then
  echo "FAIL: service-loop main loop no longer captures start_runtime rc explicitly" >&2
  exit 1
fi

if ! printf '%s\n' "${main_loop_block}" | rg -Fq '14|15)'; then
  echo "FAIL: service-loop main loop does not special-case remote frontend startup failures" >&2
  exit 1
fi

if ! printf '%s\n' "${main_loop_block}" | rg -Fq 'stopping partially started runtime after remote frontend failure'; then
  echo "FAIL: service-loop main loop does not stop partially started runtime after remote frontend failure" >&2
  exit 1
fi

if ! printf '%s\n' "${monitor_block}" | rg -Fq 'attempting frontend recovery'; then
  echo "FAIL: service-loop monitor does not attempt remote frontend recovery before forcing a restart" >&2
  exit 1
fi

echo "PASS: service-loop keeps core health local-only and layers remote listener enforcement with targeted frontend recovery"
