#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_LAUNCH_TEMPLATE="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/ssh/pixel-ssh-launch.sh"

TMP_DIR="$(mktemp -d)"
FAKE_BIN_DIR="${TMP_DIR}/fake-bin"
SSH_ROOT="${TMP_DIR}/ssh"
LAUNCH_SCRIPT="${TMP_DIR}/pixel-ssh-launch.sh"
SS_OUTPUT_FILE="${TMP_DIR}/ss-output.txt"
DROPBEAR_LOG="${TMP_DIR}/dropbear.log"
FOREIGN_LOG="${TMP_DIR}/foreign.log"
AUTH_KEYS_DIR="${TMP_DIR}/pixel-ssh-auth"

cleanup() {
  pkill -f "${SSH_ROOT}/bin/dropbear" >/dev/null 2>&1 || true
  pkill -f "${TMP_DIR}/foreign-listener" >/dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${FAKE_BIN_DIR}" "${SSH_ROOT}/bin" "${SSH_ROOT}/conf" "${SSH_ROOT}/etc/dropbear" "${SSH_ROOT}/home/root/.ssh" "${SSH_ROOT}/run" "${SSH_ROOT}/logs"

cat > "${SSH_ROOT}/conf/dropbear.env" <<'EOF_ENV'
SSH_PASSWORD_AUTH=1
SSH_ALLOW_KEY_AUTH=0
SSH_PORT=2222
SSH_BIND_ADDRESS=0.0.0.0
EOF_ENV

cp "${SOURCE_LAUNCH_TEMPLATE}" "${LAUNCH_SCRIPT}"
python3 - "${LAUNCH_SCRIPT}" "${SSH_ROOT}" "${AUTH_KEYS_DIR}" <<'PY'
from pathlib import Path
import sys

launch_path = Path(sys.argv[1])
ssh_root = sys.argv[2]
auth_keys_dir = sys.argv[3]

content = launch_path.read_text(encoding="utf-8")
content = content.replace('/data/local/pixel-stack/ssh', ssh_root)
content = content.replace('/debug_ramdisk/pixel-ssh-auth', auth_keys_dir)
launch_path.write_text(content, encoding="utf-8")
PY
chmod 0755 "${LAUNCH_SCRIPT}"

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

exec /bin/ps "$@"
EOF_PS
chmod 0755 "${FAKE_BIN_DIR}/ps"

cat > "${FAKE_BIN_DIR}/ss" <<EOF_SS
#!/usr/bin/env bash
set -euo pipefail
cat "${SS_OUTPUT_FILE}" 2>/dev/null || true
EOF_SS
chmod 0755 "${FAKE_BIN_DIR}/ss"

cat > "${SSH_ROOT}/bin/dropbear" <<EOF_DROPBEAR
#!/usr/bin/env bash
set -euo pipefail
printf 'dropbear-live\n' >> "${DROPBEAR_LOG}"
trap 'exit 0' TERM INT HUP
while true; do
  sleep 1
done
EOF_DROPBEAR
chmod +x "${SSH_ROOT}/bin/dropbear"

cat > "${TMP_DIR}/foreign-listener" <<EOF_FOREIGN
#!/usr/bin/env bash
set -euo pipefail
printf 'foreign-live\n' >> "${FOREIGN_LOG}"
trap 'exit 0' TERM INT HUP
while true; do
  sleep 1
done
EOF_FOREIGN
chmod +x "${TMP_DIR}/foreign-listener"

/bin/sh "${SSH_ROOT}/bin/dropbear" >/dev/null 2>&1 &
expected_listener_pid="$!"

cat > "${SS_OUTPUT_FILE}" <<EOF_EXPECTED
LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("dropbear",pid=${expected_listener_pid},fd=3))
EOF_EXPECTED

PATH="${FAKE_BIN_DIR}:${PATH}" PIXEL_SSH_ROOT="${SSH_ROOT}" /bin/bash "${LAUNCH_SCRIPT}"

dropbear_count="$(pgrep -f "${SSH_ROOT}/bin/dropbear" | wc -l | tr -d ' ')"
if [[ "${dropbear_count}" != "1" ]]; then
  echo "FAIL: expected launch preflight to keep exactly one live dropbear process, found ${dropbear_count}" >&2
  pgrep -fal "${SSH_ROOT}/bin/dropbear" >&2 || true
  exit 1
fi

if [[ -e "${SSH_ROOT}/etc/dropbear/dropbear_ed25519_host_key" ]]; then
  echo "FAIL: expected launch preflight to skip host key generation when listener is already healthy" >&2
  exit 1
fi

kill "${expected_listener_pid}" >/dev/null 2>&1 || true
wait "${expected_listener_pid}" >/dev/null 2>&1 || true

/bin/sh "${TMP_DIR}/foreign-listener" >/dev/null 2>&1 &
foreign_listener_pid="$!"

cat > "${SS_OUTPUT_FILE}" <<EOF_FOREIGN_SS
LISTEN 0 128 0.0.0.0:2222 0.0.0.0:* users:(("python3",pid=${foreign_listener_pid},fd=3))
EOF_FOREIGN_SS

set +e
foreign_output="$(
  PATH="${FAKE_BIN_DIR}:${PATH}" PIXEL_SSH_ROOT="${SSH_ROOT}" /bin/bash "${LAUNCH_SCRIPT}" 2>&1
)"
foreign_rc=$?
set -e

if [[ "${foreign_rc}" -eq 0 ]]; then
  echo "FAIL: launch preflight should reject foreign listeners on the SSH port" >&2
  exit 1
fi

if [[ "${foreign_output}" != *"unexpected listener"* ]]; then
  echo "FAIL: expected foreign listener failure message, got: ${foreign_output}" >&2
  exit 1
fi

echo "PASS: pixel-ssh-launch no-ops for the expected live listener and rejects foreign listeners"
