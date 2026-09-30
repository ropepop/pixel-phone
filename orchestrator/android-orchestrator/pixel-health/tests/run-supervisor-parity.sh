#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
BASELINE_REF="${1:-ea2e6df}"
GENERATED="${PROJECT_ROOT}/supervisor/build/rust-parity"
SOURCE="orchestrator/android-orchestrator/supervisor/src/main/kotlin/lv/jolkins/pixelorchestrator/supervisor/SupervisorEngine.kt"
mkdir -p "${GENERATED}/src"
# Both real owners receive the same deterministic clock only in generated tests.
git -C "${REPO_ROOT}" show "${BASELINE_REF}:${SOURCE}" |
  sed -e 's/class SupervisorEngine(/class LegacySupervisorEngine(/' -e 's/System.currentTimeMillis() \/ 1000/SupervisorParityClock.now/g' > "${GENERATED}/src/LegacySupervisorEngine.kt"
sed -e 's/class SupervisorEngine(/class RustSupervisorEngine(/' -e 's/System.currentTimeMillis() \/ 1000/SupervisorParityClock.now/g' "${REPO_ROOT}/${SOURCE}" > "${GENERATED}/src/RustSupervisorEngine.kt"
cp "${CRATE_ROOT}/tests/SupervisorParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def supervisor = gradle.rootProject.project(':supervisor')
  supervisor.kotlin.sourceSets.test.kotlin.srcDir(new File(supervisor.buildDir, 'rust-parity/src'))
  ['Legacy', 'Rust', 'Warm'].each { engine ->
    supervisor.tasks.register('supervisorMeasure' + engine, JavaExec) {
      dependsOn supervisor.tasks.named('testClasses'), gradle.rootProject.tasks.named('buildPixelHealthHost')
      classpath = supervisor.sourceSets.test.runtimeClasspath
      mainClass = 'lv.jolkins.pixelorchestrator.supervisor.SupervisorMeasurements'
      args engine
      systemProperty 'java.library.path', new File(supervisor.rootDir, 'build/pixelHealthHostRust/release').absolutePath
    }
  }
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
tasks=(:supervisor:test)
if [[ "${PIXEL_SUPERVISOR_MEASURE:-0}" == 1 ]]; then tasks+=(:supervisor:supervisorMeasureLegacy :supervisor:supervisorMeasureRust :supervisor:supervisorMeasureWarm); fi
./gradlew "${tasks[@]}" --rerun-tasks --init-script "${GENERATED}/parity.gradle" --console=plain
