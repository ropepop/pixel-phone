#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-maintenance-parity"
BASELINE_REF=9d1aa21
APP_SOURCE=orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show "${BASELINE_REF}:orchestrator/android-orchestrator/supervisor/src/main/kotlin/lv/jolkins/pixelorchestrator/supervisor/BackoffPolicy.kt" |
  sed -e 's/class BackoffPolicy(/class LegacyBackoffPolicy(/' -e '/^data class BackoffDecision(/,$d' > "${GENERATED}/src/LegacyBackoffPolicy.kt"
git -C "${REPO_ROOT}" show "${BASELINE_REF}:${APP_SOURCE}/WeeklyCleanupSchedulePolicy.kt" |
  sed 's/WeeklyCleanupSchedulePolicy/LegacyWeeklyCleanupSchedulePolicy/g' > "${GENERATED}/src/LegacyWeeklyCleanupSchedulePolicy.kt"
{
  printf '%s\n' 'package lv.jolkins.pixelorchestrator.app' 'import android.os.Build'
  git -C "${REPO_ROOT}" show "${BASELINE_REF}:${APP_SOURCE}/WeeklyCleanupScheduler.kt" |
    sed -n '/^internal object WeeklyCleanupAlarmPolicy/,$p' |
    sed 's/WeeklyCleanupAlarmPolicy/LegacyWeeklyCleanupAlarmPolicy/g'
} > "${GENERATED}/src/LegacyWeeklyCleanupAlarmPolicy.kt"
cp "${CRATE_ROOT}/tests/MaintenanceParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-maintenance-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :supervisor:test --tests '*BackoffPolicyTest' :app:testDebugUnitTest --tests '*MaintenanceParityTest' --tests '*WeeklyCleanup*' --init-script "${GENERATED}/parity.gradle" --console=plain
