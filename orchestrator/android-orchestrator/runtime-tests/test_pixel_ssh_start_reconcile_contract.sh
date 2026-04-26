#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_START_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ssh-start.sh"
SOURCE_LOOP_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-service-loop.sh"
SOURCE_LAUNCH_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-launch.sh"

TMP_DIR="$(mktemp -d)"
FAKE_BIN_DIR="${TMP_DIR}/fake-bin"
BASE_DIR="${TMP_DIR}/ssh"
TEMPLATE_DIR="${TMP_DIR}/templates/ssh"
START_SCRIPT="${TMP_DIR}/pixel-ssh-start.sh"
SS_OUTPUT_FILE="${TMP_DIR}/ss-output.txt"
CANONICAL_DROPBEAR_PID_FILE="${TMP_DIR}/canonical-dropbear.pid"

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
cp "${SOURCE_LAUNCH_TEMPLATE}" "${TEMPLATE_DIR}/pixel-ssh-launch.sh"

python3 - "${START_SCRIPT}" "${TEMPLATE_DIR}/pixel-ssh-service-loop.sh" "${TEMPLATE_DIR}/pixel-ssh-launch.sh" "${BASE_DIR}" "${TEMPLATE_DIR}" <<'PY'
from pathlib import Path
import sys

start_path = Path(sys.argv[1])
loop_path = Path(sys.argv[2])
launch_path = Path(sys.argv[3])
base_dir = sys.argv[4]
template_dir = sys.argv[5]

start_content = start_path.read_text(encoding="utf-8")
start_content = start_content.replace('/data/local/pixel-stack/templates/ssh', template_dir)
start_content = start_content.replace('/data/local/pixel-stack/ssh', base_dir)
start_path.write_text(start_content, encoding="utf-8")

for path in (loop_path, launch_path):
    content = path.read_text(encoding="utf-8")
    content = content.replace('/data/local/pixel-stack/ssh', base_dir)
    path.write_text(content, encoding="utf-8")
PY
chmod 0755 "${START_SCRIPT}" "${TEMPLATE_DIR}/pixel-ssh-service-loop.sh" "${TEMPLATE_DIR}/pixel-ssh-launch.sh"

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

cat > "${BASE_DIR}/bin/pixel-ssh-service-loop" <<'EOF_LOOP'
#!/usr/bin/env bash
set -euo pipefail

child=""
if [[ "${1:-}" == "--with-dropbear" ]]; then
  shift
  dropbear_bin="${1:-}"
  dropbear_pid_file="${2:-}"
  "${dropbear_bin}" &
  child="$!"
  if [[ -n "${dropbear_pid_file}" ]]; then
    printf '%s\n' "${child}" > "${dropbear_pid_file}"
  fi
fi

cleanup() {
  if [[ -n "${child}" ]] && kill -0 "${child}" >/dev/null 2>&1; then
    kill "${child}" >/dev/null 2>&1 || true
    wait "${child}" >/dev/null 2>&1 || true
  fi
  exit 0
}
trap cleanup TERM INT HUP EXIT

while true; do
  sleep 1
done
EOF_LOOP
chmod 0755 "${BASE_DIR}/bin/pixel-ssh-service-loop"

/bin/sh "${BASE_DIR}/bin/pixel-ssh-service-loop" --with-dropbear "${BASE_DIR}/bin/dropbear" "${CANONICAL_DROPBEAR_PID_FILE}" >/dev/null 2>&1 &
canonical_loop_pid="$!"
/bin/sh "${BASE_DIR}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 &
duplicate_loop_pid_1="$!"
/bin/sh "${BASE_DIR}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 &
duplicate_loop_pid_2="$!"

canonical_dropbear_pid=""
for _ in $(seq 1 20); do
  canonical_dropbear_pid="$(sed -n '1p' "${CANONICAL_DROPBEAR_PID_FILE}" 2>/dev/null | tr -d '\r' || true)"
  if [[ "${canonical_dropbear_pid}" =~ ^[0-9]+$ ]] && kill -0 "${canonical_dropbear_pid}" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

if [[ ! "${canonical_dropbear_pid}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: canonical dropbear did not start" >&2
  exit 1
fi

cat > "${SS_OUTPUT_FILE}" <<EOF_LISTENER
LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=${canonical_dropbear_pid},fd=3))
EOF_LISTENER

rm -f "${BASE_DIR}/run/pixel-ssh-service-loop.pid"
rm -rf "${BASE_DIR}/run/pixel-ssh-service-loop.lock"

PATH="${FAKE_BIN_DIR}:${PATH}" /bin/sh "${START_SCRIPT}"

repaired_loop_pid="$(sed -n '1p' "${BASE_DIR}/run/pixel-ssh-service-loop.pid" 2>/dev/null | tr -d '\r' || true)"
if [[ "${repaired_loop_pid}" != "${canonical_loop_pid}" ]]; then
  echo "FAIL: expected canonical loop pid ${canonical_loop_pid}, got ${repaired_loop_pid:-<empty>}" >&2
  exit 1
fi

if ! kill -0 "${canonical_loop_pid}" >/dev/null 2>&1; then
  echo "FAIL: canonical loop was pruned instead of preserved" >&2
  exit 1
fi

if ! kill -0 "${canonical_dropbear_pid}" >/dev/null 2>&1; then
  echo "FAIL: canonical dropbear listener was interrupted" >&2
  exit 1
fi

if kill -0 "${duplicate_loop_pid_1}" >/dev/null 2>&1; then
  echo "FAIL: first duplicate loop survived reconcile" >&2
  exit 1
fi

if kill -0 "${duplicate_loop_pid_2}" >/dev/null 2>&1; then
  echo "FAIL: second duplicate loop survived reconcile" >&2
  exit 1
fi

loop_count="$(pgrep -f "${BASE_DIR}/bin/pixel-ssh-service-loop" | wc -l | tr -d ' ')"
if [[ "${loop_count}" != "1" ]]; then
  echo "FAIL: expected exactly one surviving loop, found ${loop_count}" >&2
  pgrep -fal "${BASE_DIR}/bin/pixel-ssh-service-loop" >&2 || true
  exit 1
fi

echo "PASS: pixel-ssh-start preserves the live SSH runtime and prunes duplicate watcher loops"
