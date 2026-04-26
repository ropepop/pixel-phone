#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_LOOP_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-service-loop.sh"

TMP_DIR="$(mktemp -d)"
BASE_DIR="${TMP_DIR}/ssh"
LOOP_BIN="${BASE_DIR}/bin/pixel-ssh-service-loop"
ALT_OWNER_BIN="${TMP_DIR}/alternate-owner"

cleanup() {
  pkill -f "${BASE_DIR}/bin/pixel-ssh-service-loop" >/dev/null 2>&1 || true
  pkill -f "${ALT_OWNER_BIN}" >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${BASE_DIR}/bin" "${BASE_DIR}/conf" "${BASE_DIR}/run" "${BASE_DIR}/logs"
cp "${SOURCE_LOOP_SCRIPT}" "${LOOP_BIN}"

python3 - "${LOOP_BIN}" "${BASE_DIR}" <<'PY'
from pathlib import Path
import sys

loop_path = Path(sys.argv[1])
base_dir = sys.argv[2]

content = loop_path.read_text(encoding="utf-8")
content = content.replace('/data/local/pixel-stack/ssh', base_dir)
loop_path.write_text(content, encoding="utf-8")
PY
chmod 0755 "${LOOP_BIN}"

cat > "${BASE_DIR}/bin/pixel-ssh-launch" <<'EOF_LAUNCH'
#!/usr/bin/env bash
exit 1
EOF_LAUNCH
chmod 0755 "${BASE_DIR}/bin/pixel-ssh-launch"

cat > "${ALT_OWNER_BIN}" <<'EOF_ALT'
#!/usr/bin/env bash
trap 'exit 0' TERM INT HUP
while true; do
  sleep 1
done
EOF_ALT
chmod 0755 "${ALT_OWNER_BIN}"

cat > "${BASE_DIR}/conf/dropbear.env" <<'EOF_ENV'
SERVICE_MAX_RAPID_RESTARTS=5
SERVICE_RAPID_WINDOW_SEC=300
SERVICE_BACKOFF_INITIAL_SEC=5
SERVICE_BACKOFF_MAX_SEC=60
EOF_ENV

PIXEL_SSH_ROOT="${BASE_DIR}" /bin/sh "${LOOP_BIN}" >/dev/null 2>&1 &

loop_pid=""
for _ in $(seq 1 20); do
  loop_pid="$(sed -n '1p' "${BASE_DIR}/run/pixel-ssh-service-loop.pid" 2>/dev/null | tr -d '\r' || true)"
  if [[ "${loop_pid}" =~ ^[0-9]+$ ]] && kill -0 "${loop_pid}" >/dev/null 2>&1; then
    break
  fi
  sleep 0.5
done

if [[ ! "${loop_pid}" =~ ^[0-9]+$ ]]; then
  echo "FAIL: ssh loop did not create its pid file" >&2
  exit 1
fi

if [[ ! -d "${BASE_DIR}/run/pixel-ssh-service-loop.lock" ]]; then
  echo "FAIL: ssh loop did not create its lock directory" >&2
  exit 1
fi

/bin/sh "${ALT_OWNER_BIN}" >/dev/null 2>&1 &
alt_owner_pid="$!"

printf '%s\n' "${alt_owner_pid}" > "${BASE_DIR}/run/pixel-ssh-service-loop.pid"

kill "${loop_pid}" >/dev/null 2>&1 || true
sleep 1

preserved_pid="$(sed -n '1p' "${BASE_DIR}/run/pixel-ssh-service-loop.pid" 2>/dev/null | tr -d '\r' || true)"
if [[ "${preserved_pid}" != "${alt_owner_pid}" ]]; then
  echo "FAIL: loop cleanup removed ownership held by another live pid" >&2
  exit 1
fi

if [[ ! -d "${BASE_DIR}/run/pixel-ssh-service-loop.lock" ]]; then
  echo "FAIL: loop cleanup removed the shared lock despite losing ownership" >&2
  exit 1
fi

echo "PASS: pixel-ssh-service-loop only clears lock state when it still owns the pid file"
