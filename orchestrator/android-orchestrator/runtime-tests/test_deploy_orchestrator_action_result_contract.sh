#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_SCRIPT="${REPO_ROOT}/scripts/android/deploy_orchestrator_apk.sh"
SHELL_COMMANDS="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorShellCommand.kt"
ACTION_RECEIVER="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorActionReceiver.kt"
SUPERVISOR_SERVICE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/SupervisorService.kt"
FACADE="${REPO_ROOT}/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt"
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

if ! rg -Fq 'am start-foreground-service -n ${SUPERVISOR}' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh no longer dispatches through SupervisorService" >&2
  exit 1
fi

if rg -Fq 'am start -n ${ACTIVITY}' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh still dispatches via MainActivity" >&2
  exit 1
fi

if ! rg -Fq 'cmd deviceidle whitelist +${PKG}' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh no longer repairs the battery whitelist with cmd deviceidle" >&2
  exit 1
fi

if rg -Fq 'dumpsys deviceidle whitelist +${PKG}' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy_orchestrator_apk.sh still uses the ineffective dumpsys battery whitelist command" >&2
  exit 1
fi

if rg -Fq 'pixel_transport_remote_file_exists "${remote_path}"' "${SOURCE_SCRIPT}"; then
  echo "FAIL: action-result polling still performs a separate existence check before reading the artifact" >&2
  exit 1
fi

if ! rg -Fq 'poll_ms=200' "${SOURCE_SCRIPT}"; then
  echo "FAIL: fast action-result polling interval is missing" >&2
  exit 1
fi

if ! rg -Fq 'orchestrator_fast_ticket_redeploy true' "${SOURCE_SCRIPT}"; then
  echo "FAIL: fast ticket redeploy does not pass its scoped local-health flag" >&2
  exit 1
fi

if ! rg -Fq 'Fast Ticket redeploy: verifying the restarted local Ticket endpoint' "${SOURCE_SCRIPT}"; then
  echo "FAIL: fast ticket redeploy does not explain its local-only validation scope" >&2
  exit 1
fi

if ! rg -Fq 'PROFILE="fast"' "${SOURCE_SCRIPT}" ||
   ! rg -Fq 'PROFILE_EXPLICIT == 0' "${SOURCE_SCRIPT}"; then
  echo "FAIL: ticket_screen redeploy no longer defaults to the fast profile" >&2
  exit 1
fi

if ! rg -Fq 'wait_timeout_sec=40' "${SOURCE_SCRIPT}"; then
  echo "FAIL: fast ticket redeploy lacks a bounded action wait" >&2
  exit 1
fi

if ! rg -Fq 'FAST_TICKET_REDEPLOY_HEALTH_WAIT_MILLIS = 25_000L' "${FACADE}"; then
  echo "FAIL: fast ticket redeploy no longer has its 25-second local health bound" >&2
  exit 1
fi

if ! rg -Fq 'start_new_session=True' "${SOURCE_SCRIPT}" ||
   ! rg -Fq '"${DEPLOY_TIMING_REPORTER}" "${event}"' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deployment timing reporter is no longer detached from the deploy path" >&2
  exit 1
fi

if ! rg -Fq -- '--wait >/dev/null' "${SOURCE_SCRIPT}"; then
  echo "FAIL: detached deployment timing worker no longer waits for its Spacetime write" >&2
  exit 1
fi

if ! rg -Fq 'DEPLOY_TIMING_PYTHON_BIN:-/usr/bin/python3' "${SOURCE_SCRIPT}"; then
  echo "FAIL: detached timing worker no longer uses the local Python process handoff" >&2
  exit 1
fi

if ! rg -Fq 'ops/workloads/operational-logging/scripts/report-deployment.sh' "${SOURCE_SCRIPT}"; then
  echo "FAIL: deploy reporter no longer targets the canonical operational logging workload" >&2
  exit 1
fi

if ! rg -Fq 'time.monotonic_ns()' "${SOURCE_SCRIPT}" ||
   ! rg -Fq '130|143) status="cancelled"' "${SOURCE_SCRIPT}" ||
   ! rg -Fq "trap 'deployment_timing_on_signal INT' INT" "${SOURCE_SCRIPT}" ||
   ! rg -Fq "trap 'deployment_timing_on_signal TERM' TERM" "${SOURCE_SCRIPT}"; then
  echo "FAIL: Pixel deployment timing no longer uses a monotonic clock and explicit cancellation status" >&2
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
{"releaseId":"train-bot-20260309T105826Z-332599446cd3","artifacts":[{"fileName":"train-bot-bundle.tar","sha256":"1e6ed65d77d6364eeaed5a745ba5c4985ae2b700dd85d7cf7f027bdf294a33fc","url":"/data/local/pixel-stack/conf/runtime/artifacts/sha256/1e6ed65d77d6364eeaed5a745ba5c4985ae2b700dd85d7cf7f027bdf294a33fc"}]}
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
    if [[ "${shell_cmd}" == *"/system/bin/sh -s"* ]]; then
      shell_cmd="$(cat)"
    fi
    case "${shell_cmd}" in
      *"pm path lv.jolkins.pixelorchestrator"*)
        printf 'package:/data/app/base.apk\n'
        ;;
      *"cmd deviceidle whitelist"*)
        ;;
      *"settings get secure enabled_accessibility_services"*"settings get secure enabled_notification_listeners"*)
        printf '%s\n' 'lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService'
        printf '%s\n' '1'
        printf '%s\n' 'lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationNotificationListenerService'
        ;;
      *"hash_one '/data/app/base.apk'"*)
        printf '%s\n' 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
        ;;
      *"test -s /data/local/pixel-stack/conf/runtime/components/train_bot/release-manifest.json"*)
        ;;
      *"mkdir -p \"/data/local/pixel-stack/run/orchestrator-action-results\" && rm -f \"${result_path}\""*)
        rm -f "${state_dir}/action-result-consumed-${run_id}"
        ;;
      *"am force-stop "*|*"logcat -c"*)
        ;;
      *"am start-foreground-service -n "*)
        rm -f "${state_dir}/action-result-consumed-${run_id}"
        printf '%s\n' "Starting service: Intent { cmp=lv.jolkins.pixelorchestrator/.app.SupervisorService }"
        ;;
      *"readlink /data/local/pixel-stack/apps/train-bot/current"*)
        printf '%s\n' "${FAKE_LIVE_RELEASE_PATH:-}"
        ;;
      *"ps -A | grep -Eq \"train-bot.current|train-bot-service-loop\""*)
        [[ "${FAKE_PROCESS_HEALTHY:-0}" == "1" ]]
        ;;
      *"/conf/runtime/artifacts/sha256/1e6ed65d77d6364eeaed5a745ba5c4985ae2b700dd85d7cf7f027bdf294a33fc"*)
        printf '%s\n' '1e6ed65d77d6364eeaed5a745ba5c4985ae2b700dd85d7cf7f027bdf294a33fc'
        ;;
      *"rm -f ${result_path}"*|*"rm -f \"${result_path}\""*|*"\"rm\" \"-f\" \"${result_path}\""*)
        touch "${state_dir}/action-result-consumed-${run_id}"
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
            if [[ "${FAKE_ACTION_RESULT_MODE:-}" == "success" && ! -e "${state_dir}/action-result-consumed-${run_id}" ]]; then
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

TIMING_CAPTURE="${TMP_ROOT}/deployment-timing.log"
TIMING_REPORTER="${TMP_ROOT}/bin/fake-deploy-timing-reporter"
cat > "${TIMING_REPORTER}" <<'EOF_REPORTER'
#!/usr/bin/env bash
set -euo pipefail

sleep "${DEPLOY_TIMING_TEST_SLEEP:-0}"
printf '%s\n' "$*" >> "${DEPLOY_TIMING_CAPTURE:?}"
EOF_REPORTER
chmod +x "${TIMING_REPORTER}"

run_script() {
  PATH="${TMP_ROOT}/bin:${PATH}" \
  FAKE_STATE_DIR="${TMP_ROOT}/state" \
  FAKE_PIXEL_RUN_ID="${TEST_PIXEL_RUN_ID:-test-run-id}" \
  PIXEL_RUN_ID="${TEST_PIXEL_RUN_ID:-test-run-id}" \
  ORCHESTRATOR_ACTION_TIMEOUT_SEC=1 \
  DEPLOY_TIMING_REPORTER="${TIMING_REPORTER}" \
  DEPLOY_TIMING_CAPTURE="${TIMING_CAPTURE}" \
  DEPLOY_TIMING_TEST_SLEEP="${DEPLOY_TIMING_TEST_SLEEP:-0}" \
  "$@"
}

wait_for_timing_line() {
  local expected="$1"
  local attempt=0
  while (( attempt < 120 )); do
    if [[ -f "${TIMING_CAPTURE}" ]] && rg -Fq -- "${expected}" "${TIMING_CAPTURE}"; then
      return 0
    fi
    sleep 0.05
    attempt=$((attempt + 1))
  done
  echo "FAIL: deployment timing reporter did not emit: ${expected}" >&2
  [[ -f "${TIMING_CAPTURE}" ]] && cat "${TIMING_CAPTURE}" >&2
  return 1
}

mkdir -p "${TMP_ROOT}/state"
success_log="${TMP_ROOT}/success.log"
success_started_ms="$(python3 -c 'import time; print(time.monotonic_ns() // 1000000)')"
if ! DEPLOY_TIMING_TEST_SLEEP=0 TEST_PIXEL_RUN_ID=timing-baseline FAKE_ACTION_RESULT_MODE=success run_script "${TEST_ROOT}/scripts/android/deploy_orchestrator_apk.sh" \
  --device fake-device \
  --skip-build \
  --profile fast \
  --action redeploy_component \
  --component train_bot \
  --component-release-dir "${TEST_ROOT}/release" >"${success_log}" 2>&1; then
  echo "FAIL: deploy_orchestrator_apk.sh should succeed when artifact result exists without service success logs" >&2
  cat "${success_log}" >&2
  exit 1
fi
success_finished_ms="$(python3 -c 'import time; print(time.monotonic_ns() // 1000000)')"
baseline_elapsed_ms=$((success_finished_ms - success_started_ms))

if ! rg -Fq 'Starting service:' "${success_log}"; then
  echo "FAIL: deploy_orchestrator_apk.sh no longer surfaces foreground service dispatch output" >&2
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

if [[ ! -e "${TMP_ROOT}/state/action-result-consumed-timing-baseline" ]]; then
  echo "FAIL: host did not remove the consumed action-result artifact" >&2
  exit 1
fi

if ! rg -Fq 'Phase timing: action_wait=' "${success_log}"; then
  echo "FAIL: success path no longer reports action wait timing" >&2
  exit 1
fi

wait_for_timing_line 'run-start --run-id timing-baseline --source pixel --action redeploy_component --profile fast --target train_bot --total-duration-ms 0'
wait_for_timing_line 'run-complete --run-id timing-baseline --source pixel --action redeploy_component --profile fast --target train_bot --status ok'
while IFS= read -r phase_name; do
  [[ -z "${phase_name}" ]] && continue
  wait_for_timing_line "${phase_name}=ok="
done < <(sed -n 's/^Phase timing: \([^=]*\)=.*/\1/p' "${success_log}")

slow_timing_log="${TMP_ROOT}/slow-timing.log"
slow_started_ms="$(python3 -c 'import time; print(time.monotonic_ns() // 1000000)')"
if ! DEPLOY_TIMING_TEST_SLEEP=4 TEST_PIXEL_RUN_ID=timing-slow FAKE_ACTION_RESULT_MODE=success run_script "${TEST_ROOT}/scripts/android/deploy_orchestrator_apk.sh" \
  --device fake-device \
  --skip-build \
  --profile fast \
  --action redeploy_component \
  --component train_bot \
  --component-release-dir "${TEST_ROOT}/release" >"${slow_timing_log}" 2>&1; then
  echo "FAIL: deployment should not depend on the detached timing reporter" >&2
  cat "${slow_timing_log}" >&2
  exit 1
fi
slow_finished_ms="$(python3 -c 'import time; print(time.monotonic_ns() // 1000000)')"
slow_elapsed_ms=$((slow_finished_ms - slow_started_ms))
if (( slow_elapsed_ms > baseline_elapsed_ms + 1500 )); then
  echo "FAIL: detached timing reporter delayed deploy by $((slow_elapsed_ms - baseline_elapsed_ms))ms" >&2
  exit 1
fi
wait_for_timing_line 'run-complete --run-id timing-slow --source pixel --action redeploy_component --profile fast --target train_bot --status ok'

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

wait_for_timing_line 'run-complete --run-id test-run-id --source pixel --action redeploy_component --profile standard --target train_bot --status failed'
wait_for_timing_line 'action_wait=failed='

echo "PASS: deploy_orchestrator_apk.sh dispatches through the foreground service and prefers artifact-backed results"
