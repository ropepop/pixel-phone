#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_LOOP_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/train/train-service-loop.sh"
SOURCE_LAUNCH_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/train/train-launch.sh"

TMP_DIR="$(mktemp -d)"
FAKE_BIN_DIR="${TMP_DIR}/fake-bin"
BASE_DIR="${TMP_DIR}/train-bot"
TEST_LOOP="${BASE_DIR}/bin/train-bot-service-loop"
TEST_LAUNCH="${BASE_DIR}/bin/train-bot-launch"
ENV_FILE="${BASE_DIR}/env/train-bot.env"

cleanup() {
  pkill -f "${BASE_DIR}/bin/train-bot-service-loop" >/dev/null 2>&1 || true
  pkill -f "${BASE_DIR}/bin/train-bot-launch" >/dev/null 2>&1 || true
  pkill -f "${BASE_DIR}/bin/train-bot.current" >/dev/null 2>&1 || true
  if [[ -n "${orphan_bot_pid:-}" ]]; then
    wait "${orphan_bot_pid}" >/dev/null 2>&1 || true
  fi
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${FAKE_BIN_DIR}" "${BASE_DIR}/bin" "${BASE_DIR}/run" "${BASE_DIR}/logs" "${BASE_DIR}/env" "${BASE_DIR}/data/schedules" "${BASE_DIR}/state"
cp "${SOURCE_LOOP_SCRIPT}" "${TEST_LOOP}"
cp "${SOURCE_LAUNCH_SCRIPT}" "${TEST_LAUNCH}"

python3 - "${TEST_LOOP}" "${TEST_LAUNCH}" "${BASE_DIR}" <<'PY'
from pathlib import Path
import sys

loop_path = Path(sys.argv[1])
launch_path = Path(sys.argv[2])
base_dir = sys.argv[3]

for path in (loop_path, launch_path):
    content = path.read_text(encoding="utf-8")
    content = content.replace('/data/local/pixel-stack/apps/train-bot', base_dir)
    path.write_text(content, encoding="utf-8")
PY
chmod 0755 "${TEST_LOOP}" "${TEST_LAUNCH}"

cat > "${FAKE_BIN_DIR}/ps" <<'EOF_PS'
#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

if [[ "${1:-}" == "-p" ]]; then
  pid="${2:-}"
  shift 2
  if [[ "${1:-}" == "-o" ]]; then
    /bin/ps -p "${pid}" -o command= | awk '
      {
        cmd = $0
        split(cmd, parts, " ")
        if ((parts[1] == "sh" || parts[1] == "bash" || parts[1] == "dash" || parts[1] == "zsh") && length(parts[2]) > 0) {
          sub(/^[^ ]+ /, "", cmd)
        }
        print cmd
      }
    '
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
      if ((name == "sh" || name == "bash" || name == "dash" || name == "zsh") && NF >= 3) {
        args = substr($0, index($0, $3))
        name = $3
      }
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

cat > "${BASE_DIR}/bin/train-bot.current" <<'EOF_BOT'
#!/usr/bin/env bash
trap 'exit 0' HUP INT TERM
while true; do
  sleep 1
done
EOF_BOT
chmod 0755 "${BASE_DIR}/bin/train-bot.current"

cat > "${ENV_FILE}" <<'EOF_ENV'
BOT_TOKEN=test-token
EOF_ENV

/bin/sh -c 'nohup "$1" >/dev/null 2>&1 & echo $! > "$2"' _ "${BASE_DIR}/bin/train-bot.current" "${TMP_DIR}/orphan-bot.pid"
orphan_bot_pid="$(sed -n '1p' "${TMP_DIR}/orphan-bot.pid" | tr -d '\r')"

PATH="${FAKE_BIN_DIR}:${PATH}" /bin/sh "${TEST_LOOP}" >/dev/null 2>&1 &

loop_pid=""
for _ in $(seq 1 20); do
  loop_pid="$(sed -n '1p' "${BASE_DIR}/run/train-bot-service-loop.pid" 2>/dev/null | tr -d '\r' || true)"
  if [[ "${loop_pid}" =~ ^[0-9]+$ ]] && kill -0 "${loop_pid}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if [[ ! "${loop_pid}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: service loop pid file was not created" >&2
  exit 1
fi

adopted_pid="$(sed -n '1p' "${BASE_DIR}/run/train-bot.pid" 2>/dev/null | tr -d '\r' || true)"
if [[ "${adopted_pid}" != "${orphan_bot_pid}" ]]; then
  echo "FAIL: service loop did not adopt the existing train bot pid (expected ${orphan_bot_pid}, got ${adopted_pid:-<empty>})" >&2
  exit 1
fi

if pgrep -f "${BASE_DIR}/bin/train-bot-launch" >/dev/null 2>&1; then
  echo "FAIL: service loop spawned train-bot-launch while an existing train bot was already running" >&2
  pgrep -fal "${BASE_DIR}/bin/train-bot-launch" >&2 || true
  exit 1
fi

kill "${loop_pid}" >/dev/null 2>&1 || true

for _ in $(seq 1 20); do
  if ! kill -0 "${orphan_bot_pid}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if kill -0 "${orphan_bot_pid}" >/dev/null 2>&1; then
  echo "FAIL: stopping the service loop left the adopted train-bot.current orphaned" >&2
  exit 1
fi
wait "${orphan_bot_pid}" >/dev/null 2>&1 || true

echo "PASS: train service loop adopts existing train-bot.current and shuts it down on supervisor termination"
