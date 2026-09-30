#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-cleanup-policy-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show '9d1aa21:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/NightlyCleanupSupport.kt' |
  sed 's/internal class NightlyCleanupSupport(/internal class LegacyNightlyCleanupSupport(/' > "${GENERATED}/src/LegacyNightlyCleanupSupport.kt"
cp "${CRATE_ROOT}/tests/CleanupPolicyParityTest.kt" "${GENERATED}/src/"
git -C "${REPO_ROOT}" show '9d1aa21:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/RuntimeCleanupComponentController.kt' |
  sed -e 's/class RuntimeCleanupComponentController(/class LegacyRuntimeCleanupComponentController(/' -e 's/Instant.now()/Instant.parse("2026-09-30T00:00:00Z")/' > "${GENERATED}/src/LegacyRuntimeCleanupComponentController.kt"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app=gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir,'rust-cleanup-policy-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*CleanupPolicyParityTest' --tests '*OrchestratorFacadeCleanupTest' --init-script "${GENERATED}/parity.gradle" --console=plain
