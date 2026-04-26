#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_START_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ssh-start.sh"
SOURCE_LOOP_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-service-loop.sh"

TMP_DIR="$(mktemp -d)"
FAKE_BIN_DIR="${TMP_DIR}/fake-bin"
BASE_DIR="${TMP_DIR}/ssh"
TEMPLATE_DIR="${TMP_DIR}/templates/ssh"
START_SCRIPT="${TMP_DIR}/pixel-ssh-start.sh"
SS_OUTPUT_FILE="${TMP_DIR}/ss-output.txt"

cleanup() {
  pkill -f "${BASE_DIR}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 || true
  pkill -f "${BASE_DIR}/bin/dropbear" >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${FAKE_BIN_DIR}" "${BASE_DIR}/bin" "${BASE_DIR}/conf" "${BASE_DIR}/run" "${BASE_DIR}/logs" "${BASE_DIR}/etc/dropbear" "${BASE_DIR}/home/root/.ssh" "${TEMPLATE_DIR}"

cat > "${BASE_DIR}/conf/dropbear.env" <<'EOF_ENV'
SSH_PORT=2222
SSH_BIND_ADDRESS=0.0.0.0
EOF_ENV

cp "${SOURCE_START_SCRIPT}" "${START_SCRIPT}"
cp "${SOURCE_LOOP_TEMPLATE}" "${TEMPLATE_DIR}/pixel-ssh-service-loop.sh"

python3 - "${START_SCRIPT}" "${TEMPLATE_DIR}/pixel-ssh-service-loop.sh" "${BASE_DIR}" "${TEMPLATE_DIR}" <<'PY'
from pathlib import Path
import sys

start_path = Path(sys.argv[1])
loop_path = Path(sys.argv[2])
base_dir = sys.argv[3]
template_dir = sys.argv[4]

start_content = start_path.read_text(encoding="utf-8")
start_content = start_content.replace('/data/local/pixel-stack/templates/ssh', template_dir)
start_content = start_content.replace('/data/local/pixel-stack/ssh', base_dir)
start_path.write_text(start_content, encoding="utf-8")

loop_content = loop_path.read_text(encoding="utf-8")
loop_content = loop_content.replace('/data/local/pixel-stack/ssh', base_dir)
if loop_content.startswith('#!/system/bin/sh\n'):
    loop_content = '#!/usr/bin/env bash\n' + loop_content.split('\n', 1)[1]
loop_path.write_text(loop_content, encoding="utf-8")
PY
chmod 0755 "${START_SCRIPT}" "${TEMPLATE_DIR}/pixel-ssh-service-loop.sh"

cat > "${TEMPLATE_DIR}/pixel-ssh-launch.sh" <<'EOF_LAUNCH'
#!/usr/bin/env bash
set -euo pipefail
trap 'exit 0' TERM INT HUP
while true; do
  sleep 1
done
EOF_LAUNCH
chmod 0755 "${TEMPLATE_DIR}/pixel-ssh-launch.sh"

cat > "${FAKE_BIN_DIR}/ps" <<'EOF_PS'
#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

if [[ "${1:-}" == "-p" ]]; then
  pid="${2:-}"
  shift 2
  if [[ "${1:-}" == "-o" ]]; then
    format="${2:-}"
    if [[ "${format}" == "ppid=" ]]; then
      /bin/ps -p "${pid}" -o ppid= | tr -d ' '
      exit 0
    fi
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
      sub(/^.*\//, "", name)
      if (format == "PID=,NAME=,ARGS=") {
        print pid " " name " " args
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

cat > "${FAKE_BIN_DIR}/ss" <<EOF_SS
#!/usr/bin/env bash
set -euo pipefail
cat "${SS_OUTPUT_FILE}" 2>/dev/null || true
EOF_SS
chmod 0755 "${FAKE_BIN_DIR}/ss"

cat > "${BASE_DIR}/bin/dropbear" <<'EOF_DROPBEAR'
#!/usr/bin/env bash
set -euo pipefail
trap 'exit 0' TERM INT HUP
while true; do
  sleep 1
done
EOF_DROPBEAR
chmod 0755 "${BASE_DIR}/bin/dropbear"

/bin/sh "${BASE_DIR}/bin/dropbear" >/dev/null 2>&1 &
orphan_dropbear_pid="$!"

cat > "${SS_OUTPUT_FILE}" <<EOF_LISTENER
LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=${orphan_dropbear_pid},fd=3))
EOF_LISTENER

PATH="${FAKE_BIN_DIR}:${PATH}" /bin/sh "${START_SCRIPT}"

for _ in $(seq 1 20); do
  loop_pid="$(sed -n '1p' "${BASE_DIR}/run/pixel-ssh-service-loop.pid" 2>/dev/null | tr -d '\r' || true)"
  if [[ "${loop_pid}" =~ ^[0-9]+$ ]] && kill -0 "${loop_pid}" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

if [[ ! "${loop_pid:-}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: orphan recovery did not launch a new service loop" >&2
  exit 1
fi

if kill -0 "${orphan_dropbear_pid}" >/dev/null 2>&1; then
  echo "FAIL: orphaned dropbear listener survived reconcile restart" >&2
  exit 1
fi

for _ in $(seq 1 20); do
  if [[ -d "${BASE_DIR}/run/pixel-ssh-service-loop.lock" ]]; then
    break
  fi
  sleep 0.5
done

if [[ ! -d "${BASE_DIR}/run/pixel-ssh-service-loop.lock" ]]; then
  echo "FAIL: new loop did not recreate lock state" >&2
  exit 1
fi

launch_pid_count="$(pgrep -f "${BASE_DIR}/bin/pixel-ssh-launch" | wc -l | tr -d ' ')"
if [[ "${launch_pid_count}" -lt "1" ]]; then
  echo "FAIL: new loop did not invoke pixel-ssh-launch after orphan recovery" >&2
  exit 1
fi

echo "PASS: pixel-ssh-start restarts orphaned live dropbear listeners under a fresh service loop"
