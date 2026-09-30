#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
BASELINE_REF="${1:-85fa84f}"
GENERATED="${PROJECT_ROOT}/app/build/rust-visual-parity"
mkdir -p "${GENERATED}/src"
for source in TicketVisualDateGlyphRecognizer TicketVisualActionClassifier TicketControlCodeVisualClassifier; do
  git -C "${REPO_ROOT}" show "${BASELINE_REF}:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/${source}.java" |
    sed -e 's/TicketVisualDateGlyphRecognizer/LegacyTicketVisualDateGlyphRecognizer/g' \
        -e 's/TicketVisualActionClassifier/LegacyTicketVisualActionClassifier/g' \
        -e 's/TicketControlCodeVisualClassifier/LegacyTicketControlCodeVisualClassifier/g' > "${GENERATED}/src/Legacy${source}.java"
done
cp "${CRATE_ROOT}/tests/VisualParityTest.java" "${GENERATED}/src/VisualParityTest.java"
cp "${CRATE_ROOT}/tests/ControlParityTest.java" "${GENERATED}/src/ControlParityTest.java"
cp "${CRATE_ROOT}/tests/VisualMeasurements.java" "${GENERATED}/src/VisualMeasurements.java"
cp "${CRATE_ROOT}/tests/VisualIdentityProbe.java" "${GENERATED}/src/VisualIdentityProbe.java"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-visual-parity/src'))
  def identities = [:]
  ['First', 'Second'].each { run ->
    app.tasks.register('visualIdentity' + run, JavaExec) {
      dependsOn app.tasks.named('testDebugUnitTest')
      classpath = app.tasks.named('testDebugUnitTest').get().classpath
      mainClass = 'lv.jolkins.pixelorchestrator.app.ticket.VisualIdentityProbe'
      def output = new ByteArrayOutputStream()
      standardOutput = output
      doLast {
        def value = output.toString('UTF-8').trim()
        println value
        assert value ==~ /visual_process_identity=d_[0-9a-f]{28}/
        identities[run] = value
      }
      systemProperty 'java.library.path', new File(app.rootDir, 'build/pixelHealthHostRust/release').absolutePath
    }
  }
  app.tasks.register('visualVerifyRestartIdentity') {
    dependsOn 'visualIdentityFirst', 'visualIdentitySecond'
    doLast { assert identities['First'] != identities['Second'] }
  }
  ['Legacy', 'Rust', 'LegacyAfter'].each { engine ->
    app.tasks.register('visualMeasure' + engine, JavaExec) {
      dependsOn app.tasks.named('testDebugUnitTest')
      classpath = app.tasks.named('testDebugUnitTest').get().classpath
      mainClass = 'lv.jolkins.pixelorchestrator.app.ticket.VisualMeasurements'
      args engine
      systemProperty 'java.library.path', new File(app.rootDir, 'build/pixelHealthHostRust/release').absolutePath
    }
  }
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
tasks=(:app:testDebugUnitTest --tests '*VisualParityTest' --tests '*ControlParityTest' --tests '*TicketIdleRefreshTest' :app:visualVerifyRestartIdentity)
if [[ "${PIXEL_VISUAL_MEASURE:-0}" == 1 ]]; then tasks+=(:app:visualMeasureLegacy :app:visualMeasureRust :app:visualMeasureLegacyAfter); fi
./gradlew "${tasks[@]}" --init-script "${GENERATED}/parity.gradle" --console=plain
