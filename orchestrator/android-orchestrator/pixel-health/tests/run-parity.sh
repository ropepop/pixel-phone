#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
BASELINE_REF="${1:-2dc9085}"
GENERATED="${PROJECT_ROOT}/health/build/rust-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show "${BASELINE_REF}:orchestrator/android-orchestrator/health/src/main/kotlin/lv/jolkins/pixelorchestrator/health/RuntimeHealthChecker.kt" |
  sed -e 's/class RuntimeHealthChecker(/class LegacyRuntimeHealthChecker(/' \
      -e 's/private val commandRunner: CommandRunner/private val commandRunner: CommandRunner, private val legacyNowEpoch: Long = 1700000000L/' \
      -e 's/System.currentTimeMillis() \/ 1000/legacyNowEpoch/g' > "${GENERATED}/src/LegacyRuntimeHealthChecker.kt"
cp "${CRATE_ROOT}/tests/RustParityTest.kt" "${GENERATED}/src/RustParityTest.kt"
cp "${CRATE_ROOT}/tests/HealthParityMeasurements.kt" "${GENERATED}/src/HealthParityMeasurements.kt"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def health = gradle.rootProject.project(':health')
  health.kotlin.sourceSets.test.kotlin.srcDir(new File(health.buildDir, 'rust-parity/src'))
  health.tasks.named('test') {
    if (System.getenv('PIXEL_HEALTH_PARITY_SAMPLE')) systemProperty 'pixel.health.parity.sample', System.getenv('PIXEL_HEALTH_PARITY_SAMPLE')
  }
  health.tasks.register('healthParityMeasurements', JavaExec) {
    dependsOn health.tasks.named('testClasses'), gradle.rootProject.tasks.named('buildPixelHealthHost')
    classpath = health.sourceSets.test.runtimeClasspath
    mainClass = 'lv.jolkins.pixelorchestrator.health.HealthParityMeasurements'
    systemProperty 'java.library.path', new File(health.rootDir, 'build/pixelHealthHostRust/release').absolutePath
  }
  ['Kotlin', 'Rust'].each { engine ->
    health.tasks.register('healthCold' + engine, JavaExec) {
      dependsOn health.tasks.named('testClasses'), gradle.rootProject.tasks.named('buildPixelHealthHost')
      classpath = health.sourceSets.test.runtimeClasspath
      mainClass = 'lv.jolkins.pixelorchestrator.health.HealthParityMeasurements'
      args engine
      systemProperty 'java.library.path', new File(health.rootDir, 'build/pixelHealthHostRust/release').absolutePath
    }
  }
}
GRADLE
cd "${PROJECT_ROOT}"
tasks=(:health:test)
if [[ "${PIXEL_HEALTH_MEASURE:-0}" == 1 ]]; then tasks+=(:health:healthColdKotlin :health:healthColdRust :health:healthParityMeasurements); fi
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew "${tasks[@]}" --rerun-tasks --init-script "${GENERATED}/parity.gradle"
