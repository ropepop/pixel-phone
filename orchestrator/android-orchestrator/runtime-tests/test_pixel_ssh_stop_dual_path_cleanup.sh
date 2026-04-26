#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_STOP_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/entrypoints/pixel-ssh-stop.sh"

TMP_DIR="$(mktemp -d)"
LOCAL_BASE="${TMP_DIR}/ssh-local"
LEGACY_BASE="${TMP_DIR}/ssh-legacy"
STOP_SCRIPT="${TMP_DIR}/pixel-ssh-stop.sh"

cleanup() {
  pkill -f "${LOCAL_BASE}/run/dropbear-listener" >/dev/null 2>&1 || true
  pkill -f "${LEGACY_BASE}/run/dropbear-listener" >/dev/null 2>&1 || true
  pkill -f "${LOCAL_BASE}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 || true
  pkill -f "${LEGACY_BASE}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${LOCAL_BASE}/bin" "${LOCAL_BASE}/run" "${LEGACY_BASE}/bin" "${LEGACY_BASE}/run"
cp "${SOURCE_STOP_SCRIPT}" "${STOP_SCRIPT}"

python3 - "${STOP_SCRIPT}" "${LOCAL_BASE}" "${LEGACY_BASE}" <<'PY'
from pathlib import Path
import sys

stop_path = Path(sys.argv[1])
local_base = sys.argv[2]
legacy_base = sys.argv[3]

content = stop_path.read_text(encoding="utf-8")
content = content.replace('/data/local/pixel-stack/ssh', local_base)
content = content.replace('/data/adb/pixel-stack/ssh', legacy_base)
stop_path.write_text(content, encoding="utf-8")
PY
chmod 0755 "${STOP_SCRIPT}"

cat > "${LOCAL_BASE}/bin/pixel-ssh-service-loop" <<'EOF_LOOP'
#!/usr/bin/env bash
trap 'exit 0' TERM INT HUP
while true; do
  sleep 1
done
EOF_LOOP
cp "${LOCAL_BASE}/bin/pixel-ssh-service-loop" "${LEGACY_BASE}/bin/pixel-ssh-service-loop"
chmod 0755 "${LOCAL_BASE}/bin/pixel-ssh-service-loop" "${LEGACY_BASE}/bin/pixel-ssh-service-loop"

mkdir -p "${LOCAL_BASE}/run" "${LEGACY_BASE}/run" "${LOCAL_BASE}/run/pixel-ssh-service-loop.lock" "${LEGACY_BASE}/run/pixel-ssh-service-loop.lock"

/bin/sh "${LOCAL_BASE}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 &
local_loop_pid="$!"
/bin/sh "${LEGACY_BASE}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 &
legacy_loop_pid="$!"

cat > "${LOCAL_BASE}/run/dropbear-listener" <<'EOF_DROPBEAR'
#!/usr/bin/env bash
trap 'exit 0' TERM INT HUP
while true; do
  sleep 1
done
EOF_DROPBEAR
cp "${LOCAL_BASE}/run/dropbear-listener" "${LEGACY_BASE}/run/dropbear-listener"
chmod 0755 "${LOCAL_BASE}/run/dropbear-listener" "${LEGACY_BASE}/run/dropbear-listener"

/bin/sh "${LOCAL_BASE}/run/dropbear-listener" >/dev/null 2>&1 &
local_dropbear_pid="$!"
/bin/sh "${LEGACY_BASE}/run/dropbear-listener" >/dev/null 2>&1 &
legacy_dropbear_pid="$!"

printf '%s\n' "${local_loop_pid}" > "${LOCAL_BASE}/run/pixel-ssh-service-loop.pid"
printf '%s\n' "${legacy_loop_pid}" > "${LEGACY_BASE}/run/pixel-ssh-service-loop.pid"
printf '%s\n' "${local_dropbear_pid}" > "${LOCAL_BASE}/run/dropbear.pid"
printf '%s\n' "${legacy_dropbear_pid}" > "${LEGACY_BASE}/run/dropbear.pid"

/bin/sh "${STOP_SCRIPT}"

for pid in "${local_loop_pid}" "${legacy_loop_pid}" "${local_dropbear_pid}" "${legacy_dropbear_pid}"; do
  if kill -0 "${pid}" >/dev/null 2>&1; then
    echo "FAIL: stop script left process ${pid} running" >&2
    exit 1
  fi
done

for path in \
  "${LOCAL_BASE}/run/pixel-ssh-service-loop.pid" \
  "${LEGACY_BASE}/run/pixel-ssh-service-loop.pid" \
  "${LOCAL_BASE}/run/dropbear.pid" \
  "${LEGACY_BASE}/run/dropbear.pid"; do
  if [[ -e "${path}" ]]; then
    echo "FAIL: stop script did not remove ${path}" >&2
    exit 1
  fi
done

for path in \
  "${LOCAL_BASE}/run/pixel-ssh-service-loop.lock" \
  "${LEGACY_BASE}/run/pixel-ssh-service-loop.lock"; do
  if [[ -e "${path}" ]]; then
    echo "FAIL: stop script did not remove ${path}" >&2
    exit 1
  fi
done

echo "PASS: pixel-ssh-stop removes loop and dropbear state across both runtime paths"
