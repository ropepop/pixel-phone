#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_START_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-train-start.sh"

TMP_DIR="$(mktemp -d)"
FAKE_BIN_DIR="${TMP_DIR}/fake-bin"
BASE_DIR="${TMP_DIR}/train-bot"
TPL_DIR="${TMP_DIR}/templates/train"
TEST_SCRIPT="${TMP_DIR}/pixel-train-start.sh"
CONF_ENV="${TMP_DIR}/train-bot.env"

cleanup() {
  if [[ -n "${tunnel_pid:-}" ]]; then
    kill "${tunnel_pid}" >/dev/null 2>&1 || true
    wait "${tunnel_pid}" >/dev/null 2>&1 || true
  fi
  pkill -f "${BASE_DIR}/bin/train-bot-service-loop" >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${FAKE_BIN_DIR}" "${BASE_DIR}/bin" "${BASE_DIR}/run" "${BASE_DIR}/logs" "${BASE_DIR}/env" "${BASE_DIR}/data/schedules" "${BASE_DIR}/state" "${TPL_DIR}"
cp "${SOURCE_START_SCRIPT}" "${TEST_SCRIPT}"

python3 - "${TEST_SCRIPT}" "${BASE_DIR}" "${CONF_ENV}" "${TPL_DIR}" <<'PY'
from pathlib import Path
import sys

script_path = Path(sys.argv[1])
base_dir = sys.argv[2]
conf_env = sys.argv[3]
tpl_dir = sys.argv[4]
content = script_path.read_text(encoding="utf-8")
content = content.replace('BASE="/data/local/pixel-stack/apps/train-bot"', f'BASE="{base_dir}"')
content = content.replace('CONF_ENV="/data/local/pixel-stack/conf/apps/train-bot.env"', f'CONF_ENV="{conf_env}"')
content = content.replace('TPL_DIR="/data/local/pixel-stack/templates/train"', f'TPL_DIR="{tpl_dir}"')
script_path.write_text(content, encoding="utf-8")
PY
chmod 0755 "${TEST_SCRIPT}"

cat > "${FAKE_BIN_DIR}/ps" <<'EOF_PS'
#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

if [[ "${1:-}" == "-p" ]]; then
  pid="${2:-}"
  shift 2
  if [[ "${1:-}" == "-o" ]]; then
    /bin/ps -p "${pid}" -o command=
    exit 0
  fi
fi

if [[ "${1:-}" == "-A" && "${2:-}" == "-o" ]]; then
  format="${3:-}"
  /bin/ps -ax -o pid=,command= | awk -v format="${format}" '
    {
      pid = $1
      args = substr($0, index($0, $2))
      name = $2
      sub(/^.*\//, "", name)
      if (format == "PID=,NAME=,ARGS=") {
        print pid " " name " " args
      } else if (format == "PID,ARGS") {
        print pid " " args
      } else {
        print pid " " args
      }
    }
  '
  exit 0
fi

exec /bin/ps "$@"
EOF_PS
chmod 0755 "${FAKE_BIN_DIR}/ps"

cat > "${TPL_DIR}/train-launch.sh" <<'EOF_LAUNCH'
#!/usr/bin/env bash
sleep 60
EOF_LAUNCH
cat > "${TPL_DIR}/train-service-loop.sh" <<'EOF_LOOP'
#!/usr/bin/env bash
sleep 60
EOF_LOOP
cat > "${TPL_DIR}/train-web-tunnel-service-loop.sh" <<'EOF_TUNNEL'
#!/usr/bin/env bash
sleep 60
EOF_TUNNEL
chmod 0755 "${TPL_DIR}/train-launch.sh" "${TPL_DIR}/train-service-loop.sh" "${TPL_DIR}/train-web-tunnel-service-loop.sh"

cat > "${BASE_DIR}/bin/train-bot.current" <<'EOF_BIN'
#!/usr/bin/env bash
exit 0
EOF_BIN
chmod 0755 "${BASE_DIR}/bin/train-bot.current"

cat > "${BASE_DIR}/env/train-bot.env" <<EOF_ENV
BOT_TOKEN=test-token
TRAIN_WEB_ENABLED=true
TRAIN_WEB_TUNNEL_ENABLED=true
TRAIN_WEB_SESSION_SECRET_FILE=${TMP_DIR}/train-bot-web-session-secret
EOF_ENV
cp "${BASE_DIR}/env/train-bot.env" "${CONF_ENV}"

cp "${TPL_DIR}/train-web-tunnel-service-loop.sh" "${BASE_DIR}/bin/train-web-tunnel-service-loop"
chmod 0755 "${BASE_DIR}/bin/train-web-tunnel-service-loop"
"${BASE_DIR}/bin/train-web-tunnel-service-loop" >/dev/null 2>&1 &
tunnel_pid="$!"
printf '%s\n' "${tunnel_pid}" > "${BASE_DIR}/run/train-web-tunnel-service-loop.pid"
printf '%s\n' "${tunnel_pid}" > "${BASE_DIR}/run/train-bot-service-loop.pid"

PATH="${FAKE_BIN_DIR}:${PATH}" /bin/sh "${TEST_SCRIPT}"

train_loop_pid="$(sed -n '1p' "${BASE_DIR}/run/train-bot-service-loop.pid" | tr -d '\r')"
tunnel_loop_pid="$(sed -n '1p' "${BASE_DIR}/run/train-web-tunnel-service-loop.pid" | tr -d '\r')"

if [[ ! "${train_loop_pid}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: repaired train loop pid is not numeric: ${train_loop_pid}" >&2
  exit 1
fi
if [[ ! "${tunnel_loop_pid}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: tunnel loop pid is not numeric: ${tunnel_loop_pid}" >&2
  exit 1
fi
if [[ "${train_loop_pid}" == "${tunnel_loop_pid}" ]]; then
  echo "FAIL: train loop pid still points at the tunnel loop pid ${train_loop_pid}" >&2
  exit 1
fi

train_cmd="$(/bin/ps -p "${train_loop_pid}" -o command=)"
tunnel_cmd="$(/bin/ps -p "${tunnel_loop_pid}" -o command=)"
if [[ "${train_cmd}" != *"${BASE_DIR}/bin/train-bot-service-loop"* ]]; then
  echo "FAIL: repaired train loop pid does not point at train-bot-service-loop: ${train_cmd}" >&2
  exit 1
fi
if [[ "${tunnel_cmd}" != *"${BASE_DIR}/bin/train-web-tunnel-service-loop"* ]]; then
  echo "FAIL: tunnel loop pid does not point at train-web-tunnel-service-loop: ${tunnel_cmd}" >&2
  exit 1
fi

if [[ "$(pgrep -f "${BASE_DIR}/bin/train-web-tunnel-service-loop" | wc -l | tr -d '[:space:]')" != "1" ]]; then
  echo "FAIL: train start spawned duplicate tunnel loops" >&2
  pgrep -fal "${BASE_DIR}/bin/train-web-tunnel-service-loop" >&2 || true
  exit 1
fi

if [[ "$(pgrep -f "${BASE_DIR}/bin/train-bot-service-loop" | wc -l | tr -d '[:space:]')" != "1" ]]; then
  echo "FAIL: train start did not leave exactly one train loop running" >&2
  pgrep -fal "${BASE_DIR}/bin/train-bot-service-loop" >&2 || true
  exit 1
fi

echo "PASS: pixel-train-start repairs mismatched live loop pid files without duplicating tunnel loops"
