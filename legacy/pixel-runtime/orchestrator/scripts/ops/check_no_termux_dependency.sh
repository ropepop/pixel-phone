#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

# Keep this check focused on active repository sources. Archived evidence and
# one-time legacy cleanup tooling are excluded separately.
PATTERN='termux-related|/data/data/com\.termux|com\.termux|TERMUX_REPO|termux_repo|termux_only|Termux app context|Termux:Boot|termux-home|~/pixel-stack/bin|scripts/deploy_to_termux_home\.sh|scripts/ops/setup_root_dropbear_ssh\.sh|rollback_last'

matches="$(rg -n --color never -S -e "${PATTERN}" \
  --glob '!ops/evidence/**' \
  --glob '!ops/reports/**' \
  --glob '!workloads/site-notifications/output/**' \
  --glob '!output/**' \
  --glob '!.git/**' \
  --glob '!android-orchestrator/build/**' \
  --glob '!orchestrator/scripts/ops/check_no_termux_dependency.sh' \
  --glob '!orchestrator/scripts/docs/check_stale_references.sh' \
  --glob '!orchestrator/scripts/ops/hard-cutover-orchestrator-owners.sh' \
  --glob '!workloads/site-notifications/scripts/pixel/termux_run.sh' \
  --glob '!workloads/site-notifications/scripts/start_daemon.sh' \
  --glob '!workloads/site-notifications/scripts/install_termux_autostart.sh' \
  --glob '!workloads/train-bot/scripts/start_termux.sh' \
  --glob '!workloads/train-bot/scripts/pixel/termux_run.sh' \
  --glob '!workloads/train-bot/scripts/pixel/termux_bootstrap_go.sh' \
  --glob '!**/*.png' \
  --glob '!**/*.jpg' \
  --glob '!**/*.jpeg' \
  --glob '!**/*.gif' || true)"

if [[ -n "${matches}" ]]; then
  echo "Forbidden stale deployment references detected:" >&2
  printf '%s\n' "${matches}" >&2
  exit 1
fi

echo "OK: no forbidden stale deployment references detected in active repository sources."
