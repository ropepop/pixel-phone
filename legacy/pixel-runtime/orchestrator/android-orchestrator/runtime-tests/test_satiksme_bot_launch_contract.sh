#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LAUNCH_SCRIPT="${REPO_ROOT}/android-orchestrator/app/src/main/assets/runtime/templates/satiksme/satiksme-launch.sh"

if ! rg -Fq 'BOT_PID_FILE="${RUN_DIR}/satiksme-bot.pid"' "${LAUNCH_SCRIPT}"; then
  echo "FAIL: satiksme launch script does not define the bot pid file" >&2
  exit 1
fi

if ! rg -Fq 'trap forward_signal HUP INT TERM' "${LAUNCH_SCRIPT}"; then
  echo "FAIL: satiksme launch script no longer forwards termination signals to the bot process" >&2
  exit 1
fi

if ! rg -Fq 'echo "${child_pid}" > "${BOT_PID_FILE}"' "${LAUNCH_SCRIPT}"; then
  echo "FAIL: satiksme launch script no longer records the real bot pid" >&2
  exit 1
fi

echo "PASS: satiksme launch contract is present"
