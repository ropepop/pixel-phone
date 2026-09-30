#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
BASELINE_REF="${1:-d953ff9}"
GENERATED="${PROJECT_ROOT}/core-config/build/rust-parity"
mkdir -p "${GENERATED}/src"
for source in StackStore LegacyConfigCompat SecretRedactor; do
  git -C "${REPO_ROOT}" show "${BASELINE_REF}:orchestrator/android-orchestrator/core-config/src/main/kotlin/lv/jolkins/pixelorchestrator/coreconfig/${source}.kt" |
    sed -e 's/StackStore/LegacyStackStore/g' -e 's/LegacyConfigCompat/OriginalConfigCompat/g' -e 's/SecretRedactor/OriginalSecretRedactor/g' > "${GENERATED}/src/Original${source}.kt"
done
cp "${CRATE_ROOT}/tests/StoreParityTest.kt" "${GENERATED}/src/StoreParityTest.kt"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def config = gradle.rootProject.project(':core-config')
  config.kotlin.sourceSets.test.kotlin.srcDir(new File(config.buildDir, 'rust-parity/src'))
  ['Legacy', 'Rust', 'Warm'].each { engine ->
    config.tasks.register('storeMeasure' + engine, JavaExec) {
      dependsOn config.tasks.named('testClasses'), gradle.rootProject.tasks.named('buildPixelHealthHost')
      classpath = config.sourceSets.test.runtimeClasspath
      mainClass = 'lv.jolkins.pixelorchestrator.coreconfig.StoreMeasurements'
      args engine
      systemProperty 'java.library.path', new File(config.rootDir, 'build/pixelHealthHostRust/release').absolutePath
    }
  }
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
tasks=(:core-config:test)
if [[ "${PIXEL_STORE_MEASURE:-0}" == 1 ]]; then tasks+=(:core-config:storeMeasureLegacy :core-config:storeMeasureRust :core-config:storeMeasureWarm); fi
./gradlew "${tasks[@]}" --rerun-tasks --init-script "${GENERATED}/parity.gradle" --console=plain
