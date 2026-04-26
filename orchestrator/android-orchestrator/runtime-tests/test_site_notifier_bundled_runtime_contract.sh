#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LAUNCH_SCRIPT="${ROOT}/app/src/main/assets/runtime/templates/notifier/notifier-launch.sh"
TERMUX_PATH_PREFIX='/data/data/com'
TERMUX_PATH="${TERMUX_PATH_PREFIX}.termux"

if ! rg -Fq 'PYTHON_BIN="${NOTIFIER_PYTHON_PATH:-${DEFAULT_PYTHON}}"' "${LAUNCH_SCRIPT}"; then
  echo "FAIL: site-notifier launch template no longer resolves the bundled notifier python path" >&2
  exit 1
fi

if ! rg -Fq 'export RUNTIME_CONTEXT_POLICY="orchestrator_root"' "${LAUNCH_SCRIPT}"; then
  echo "FAIL: site-notifier launch template no longer exports orchestrator_root runtime policy" >&2
  exit 1
fi

if ! rg -Fq '"${PYTHON_BIN}" "${ENTRY_SCRIPT}" daemon' "${LAUNCH_SCRIPT}"; then
  echo "FAIL: site-notifier launch template no longer starts the daemon through the bundled interpreter" >&2
  exit 1
fi

for forbidden in 'NOTIFIER_RUNTIME_MODE' 'NOTIFIER_RUN_UID' 'NOTIFIER_CHROOT_ROOT' 'NOTIFIER_CHROOT_PYTHON' "${TERMUX_PATH}" 'run_as_uid' 'probe_termux_python' 'uid_has_network_egress'; do
  if rg -Fq "${forbidden}" "${LAUNCH_SCRIPT}"; then
    echo "FAIL: site-notifier launch template still contains retired runtime branch: ${forbidden}" >&2
    exit 1
  fi
done

echo "PASS: site-notifier launch template is bundled-runtime-only"
