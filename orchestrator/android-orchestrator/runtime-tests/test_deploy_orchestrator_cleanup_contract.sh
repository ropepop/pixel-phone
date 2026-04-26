#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
SHELL_COMMANDS="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorShellCommand.kt"
SERVICE_FILE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"

if ! rg -Fq 'const val ACTION_CLEANUP = "cleanup"' "${SHELL_COMMANDS}"; then
  echo "FAIL: OrchestratorShellCommand missing cleanup action constant" >&2
  exit 1
fi

if ! rg -Fq 'const val EXTRA_DRY_RUN = "orchestrator_dry_run"' "${SHELL_COMMANDS}"; then
  echo "FAIL: OrchestratorShellCommand missing cleanup dry-run extra" >&2
  exit 1
fi

if ! rg -Fq 'const val ACTION_CLEANUP = "lv.jolkins.pixelorchestrator.action.CLEANUP"' "${SERVICE_FILE}"; then
  echo "FAIL: SupervisorService missing cleanup action constant" >&2
  exit 1
fi

if ! rg -Fq '(bootstrap|start_all|stop_all|health|sync_ddns|export_bundle|cleanup|' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy wrapper usage text missing cleanup action" >&2
  exit 1
fi

if ! rg -Fq -- '--dry-run                   only valid with --action cleanup' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy wrapper usage text missing cleanup dry-run flag" >&2
  exit 1
fi

if ! rg -Fq 'cleanup|redeploy_component|start_component|stop_component|restart_component|health_component' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy wrapper validation does not allow cleanup action" >&2
  exit 1
fi

if ! rg -Fq 'shell_cmd="${shell_cmd} --ez orchestrator_dry_run true"' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy wrapper does not forward cleanup dry-run broadcast extra" >&2
  exit 1
fi

echo "PASS: deploy orchestrator cleanup wiring is present"
