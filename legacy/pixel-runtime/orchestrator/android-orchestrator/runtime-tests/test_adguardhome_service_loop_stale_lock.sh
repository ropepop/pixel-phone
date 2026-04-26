#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LOOP_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/rooted/adguardhome-service-loop"

TMP_DIR="$(mktemp -d)"
BASE_DIR="${TMP_DIR}/rooted"
LOOP_BIN="${BASE_DIR}/bin/adguardhome-service-loop"

cleanup() {
  pkill -f "${LOOP_BIN}" >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${BASE_DIR}/bin" "${BASE_DIR}/conf" "${BASE_DIR}/run" "${BASE_DIR}/logs"
cp "${LOOP_TEMPLATE}" "${LOOP_BIN}"
chmod 0755 "${LOOP_BIN}"

cat > "${BASE_DIR}/conf/adguardhome.env" <<'EOF_ENV'
PIHOLE_SERVICE_HEALTH_POLL_SEC=1
PIHOLE_SERVICE_UNHEALTHY_FAILS=2
PIHOLE_SERVICE_MAX_RAPID_RESTARTS=5
PIHOLE_SERVICE_RAPID_WINDOW_SECONDS=30
PIHOLE_SERVICE_BACKOFF_SECONDS=1
PIHOLE_SERVICE_BACKOFF_MAX_SECONDS=2
EOF_ENV

mkdir -p "${BASE_DIR}/run/adguardhome-service-loop.lock"
printf '424242\n' > "${BASE_DIR}/run/adguardhome-service-loop.pid"

PIHOLE_BASE_DIR="${BASE_DIR}" sh "${LOOP_BIN}" >/dev/null 2>&1 &
loop_pid="$!"

log_file="${BASE_DIR}/logs/adguardhome-service-loop.log"
for _ in $(seq 1 20); do
  if grep -q 'stale adguardhome-service-loop lock detected; resetting lock state' "${log_file}" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

if ! grep -q 'stale adguardhome-service-loop lock detected; resetting lock state' "${log_file}" 2>/dev/null; then
  echo "FAIL: stale lock recovery message not found in service log" >&2
  sed -n '1,200p' "${log_file}" >&2 || true
  exit 1
fi

if [[ ! -f "${BASE_DIR}/run/adguardhome-service-loop.pid" ]]; then
  echo "FAIL: loop pid file missing after stale-lock takeover" >&2
  exit 1
fi

running_pid="$(sed -n '1p' "${BASE_DIR}/run/adguardhome-service-loop.pid" | tr -d '\r')"
if [[ ! "${running_pid}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: loop pid file did not contain numeric pid: ${running_pid}" >&2
  exit 1
fi

if ! kill -0 "${running_pid}" >/dev/null 2>&1; then
  echo "FAIL: recovered loop pid is not running: ${running_pid}" >&2
  exit 1
fi

kill "${running_pid}" >/dev/null 2>&1 || true
sleep 1
kill -9 "${running_pid}" >/dev/null 2>&1 || true
sleep 1

if [[ -d "${BASE_DIR}/run/adguardhome-service-loop.lock" ]]; then
  echo "FAIL: lock directory was not removed on exit" >&2
  exit 1
fi

if [[ -f "${BASE_DIR}/run/adguardhome-service-loop.pid" ]]; then
  echo "FAIL: service-loop pid file was not removed on exit" >&2
  exit 1
fi

if kill -0 "${loop_pid}" >/dev/null 2>&1; then
  kill -9 "${loop_pid}" >/dev/null 2>&1 || true
fi

echo "PASS: adguardhome-service-loop recovers stale lock and cleans lock state on exit"
