#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
SHELL_COMMANDS="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorShellCommand.kt"
ACTION_RECEIVER="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorActionReceiver.kt"
SUPERVISOR_SERVICE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"
STACK_PATHS="${REPO_ROOT}/android-orchestrator/core-config/src/main/kotlin/lv/jolkins/pixelorchestrator/coreconfig/StackPaths.kt"
MANIFEST="${REPO_ROOT}/android-orchestrator/app/src/main/AndroidManifest.xml"
TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

if ! rg -Fq 'const val EXTRA_ACTION = "orchestrator_action"' "${SHELL_COMMANDS}"; then
  echo "FAIL: OrchestratorShellCommand missing orchestrator_action extra" >&2
  exit 1
fi

if ! rg -Fq 'fun toSupervisorAction(action: String): String?' "${SHELL_COMMANDS}"; then
  echo "FAIL: OrchestratorShellCommand missing logical-to-service action mapping" >&2
  exit 1
fi

if ! rg -Fq 'command_accepted action=' "${ACTION_RECEIVER}"; then
  echo "FAIL: OrchestratorActionReceiver missing stable command acceptance log marker" >&2
  exit 1
fi

if ! rg -Fq 'SupervisorService.start(' "${ACTION_RECEIVER}"; then
  echo "FAIL: OrchestratorActionReceiver no longer dispatches through SupervisorService" >&2
  exit 1
fi

if ! rg -Fq 'android:name=".app.OrchestratorActionReceiver"' "${MANIFEST}"; then
  echo "FAIL: AndroidManifest missing OrchestratorActionReceiver registration" >&2
  exit 1
fi

if ! rg -Uq 'android:name="\.app\.OrchestratorActionReceiver"[\s\S]*android:exported="true"' "${MANIFEST}"; then
  echo "FAIL: AndroidManifest no longer exports OrchestratorActionReceiver" >&2
  exit 1
fi

if ! rg -Fq 'const val ACTION_EXPORT_BUNDLE = "lv.jolkins.pixelorchestrator.action.EXPORT_BUNDLE"' "${SUPERVISOR_SERVICE}"; then
  echo "FAIL: SupervisorService missing export bundle action support" >&2
  exit 1
fi

if ! rg -Fq 'command_action=$resultAction' "${SUPERVISOR_SERVICE}"; then
  echo "FAIL: SupervisorService missing logical command action logging" >&2
  exit 1
fi

if ! rg -Fq 'facade.writeActionResult(pixelRunId, resultAction, component, result)' "${SUPERVISOR_SERVICE}"; then
  echo "FAIL: SupervisorService no longer persists action results using logical command names" >&2
  exit 1
fi

if ! rg -Fq 'const val ACTION_RESULTS = "$RUN/orchestrator-action-results"' "${STACK_PATHS}"; then
  echo "FAIL: StackPaths missing orchestrator action result directory" >&2
  exit 1
fi

if ! rg -Fq 'am broadcast -n ${RECEIVER}' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh no longer dispatches via OrchestratorActionReceiver" >&2
  exit 1
fi

if rg -Fq 'am start -n ${ACTIVITY}' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh still dispatches via MainActivity" >&2
  exit 1
fi

TEST_ROOT="${TMP_ROOT}/repo"
mkdir -p "${TEST_ROOT}/scripts/android" \
  "${TEST_ROOT}/android-orchestrator/app/build/outputs/apk/debug" \
  "${TEST_ROOT}/release/artifacts" \
  "${TMP_ROOT}/tools/pixel" \
  "${TMP_ROOT}/bin"
cp "${SOURCE_SCRIPT}" "${TEST_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
chmod +x "${TEST_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
cp "${REPO_ROOT}/../tools/pixel/transport.sh" "${TMP_ROOT}/tools/pixel/transport.sh"
touch "${TEST_ROOT}/android-orchestrator/app/build/outputs/apk/debug/app-debug.apk"
cat > "${TEST_ROOT}/release/release-manifest.json" <<'EOF_MANIFEST'
{"releaseId":"train-bot-20260309T105826Z-332599446cd3"}
EOF_MANIFEST
printf 'bundle' > "${TEST_ROOT}/release/artifacts/train-bot-bundle.tar"

cat > "${TMP_ROOT}/bin/adb" <<'EOF_ADB'
#!/usr/bin/env bash
set -euo pipefail

state_dir="${FAKE_STATE_DIR:?}"
run_id="${FAKE_PIXEL_RUN_ID:?}"
result_path="/data/local/pixel-stack/run/orchestrator-action-results/${run_id}--redeploy_component--train_bot.json"

if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi

cmd="${1:-}"
shift || true

case "${cmd}" in
  get-state)
    printf 'device\n'
    ;;
  install)
    ;;
  push)
    ;;
  shell)
    shell_cmd="$*"
    case "${shell_cmd}" in
      *"pm path lv.jolkins.pixelorchestrator"*)
        printf 'package:/data/app/base.apk\n'
        ;;
      *"dumpsys deviceidle whitelist"*)
        ;;
      *"test -s /data/local/pixel-stack/conf/runtime/components/train_bot/release-manifest.json"*)
        ;;
      *"mkdir -p \"/data/local/pixel-stack/run/orchestrator-action-results\" && rm -f \"${result_path}\""*)
        ;;
      *"am force-stop "*|*"logcat -c"*)
        ;;
      *"am broadcast -n "*)
        printf '%s\n' "Broadcast completed: result=0"
        ;;
      *"readlink /data/local/pixel-stack/apps/train-bot/current"*)
        printf '%s\n' "${FAKE_LIVE_RELEASE_PATH:-}"
        ;;
      *"ps -A | grep -Eq \"train-bot.current|train-bot-service-loop\""*)
        [[ "${FAKE_PROCESS_HEALTHY:-0}" == "1" ]]
        ;;
      *"logcat -d -v time | grep -E 'OrchestratorActionReceiver|SupervisorService|OrchestratorMain' | tail -n 120"*)
        printf '%s\n' "03-09 12:58:52.867 I/OrchestratorActionReceiver( 5326): command_accepted action=redeploy_component component=train_bot run_id=test-run-id"
        ;;
      *"logcat -d -v time | grep -E 'OrchestratorActionReceiver|SupervisorService' | tail -n 200"*)
        printf '%s\n' "03-09 12:58:52.867 I/OrchestratorActionReceiver( 5326): command_accepted action=redeploy_component component=train_bot run_id=test-run-id"
        ;;
      *)
        if [[ "${shell_cmd}" == *"/orchestrator-action-results/"* ]]; then
          if [[ "${shell_cmd}" == *"test -f "* ]]; then
            [[ "${FAKE_ACTION_RESULT_MODE:-}" == "success" ]]
          elif [[ "${shell_cmd}" == *"cat "* || "${shell_cmd}" == *'"cat"'* ]]; then
            if [[ "${FAKE_ACTION_RESULT_MODE:-}" == "success" ]]; then
              printf '%s\n' '{"pixelRunId":"'"${run_id}"'","action":"redeploy_component","component":"train_bot","success":true,"message":"artifact success"}'
            else
              exit 1
            fi
          fi
        fi
        ;;
    esac
    ;;
  *)
    echo "unsupported adb invocation: ${cmd} $*" >&2
    exit 1
    ;;
esac
EOF_ADB
chmod +x "${TMP_ROOT}/bin/adb"

run_script() {
  PATH="${TMP_ROOT}/bin:${PATH}" \
  FAKE_STATE_DIR="${TMP_ROOT}/state" \
  FAKE_PIXEL_RUN_ID="test-run-id" \
  PIXEL_RUN_ID="test-run-id" \
  ORCHESTRATOR_ACTION_TIMEOUT_SEC=1 \
  "$@"
}

mkdir -p "${TMP_ROOT}/state"
success_log="${TMP_ROOT}/success.log"
if ! FAKE_ACTION_RESULT_MODE=success run_script "${TEST_ROOT}/scripts/android/deploy_orchestrator_apk.sh" \
  --device fake-device \
  --skip-build \
  --action redeploy_component \
  --component train_bot \
  --component-release-dir "${TEST_ROOT}/release" >"${success_log}" 2>&1; then
  echo "FAIL: deploy_orchestrator_apk.sh should succeed when artifact result exists without service success logs" >&2
  cat "${success_log}" >&2
  exit 1
fi

if ! rg -Fq 'Broadcast completed: result=0' "${success_log}"; then
  echo "FAIL: deploy_orchestrator_apk.sh no longer surfaces receiver dispatch output" >&2
  exit 1
fi

if ! rg -Fq 'Action redeploy_component reported SUCCESS via artifact' "${success_log}"; then
  echo "FAIL: success path no longer prefers artifact-backed completion" >&2
  exit 1
fi

if ! rg -Fq 'Action result source: artifact' "${success_log}"; then
  echo "FAIL: success path no longer reports artifact result source" >&2
  exit 1
fi

timeout_log="${TMP_ROOT}/timeout.log"
set +e
run_script "${TEST_ROOT}/scripts/android/deploy_orchestrator_apk.sh" \
  --device fake-device \
  --skip-build \
  --action redeploy_component \
  --component train_bot \
  --component-release-dir "${TEST_ROOT}/release" >"${timeout_log}" 2>&1
timeout_rc=$?
set -e

if [[ "${timeout_rc}" == "0" ]]; then
  echo "FAIL: deploy_orchestrator_apk.sh succeeded despite missing artifact and no live release switch" >&2
  cat "${timeout_log}" >&2
  exit 1
fi

if ! rg -Fq "command_accepted action=redeploy_component component=train_bot run_id=test-run-id" "${timeout_log}"; then
  echo "FAIL: timeout path no longer reports receiver command marker" >&2
  exit 1
fi

if ! rg -Fq 'Timed out waiting for action redeploy_component result after 1s' "${timeout_log}"; then
  echo "FAIL: timeout path no longer reports action wait timeout" >&2
  exit 1
fi

if ! rg -Fq 'Redeploy recovery summary:' "${timeout_log}"; then
  echo "FAIL: timeout path no longer emits recovery summary" >&2
  exit 1
fi

if ! rg -Fq 'resume_command=' "${timeout_log}"; then
  echo "FAIL: timeout recovery summary missing resume command" >&2
  exit 1
fi

echo "PASS: deploy_orchestrator_apk.sh dispatches through the receiver and prefers artifact-backed results"
