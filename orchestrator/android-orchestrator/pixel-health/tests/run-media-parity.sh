#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
BASELINE_REF="${1:-3ec2e93}"
GENERATED="${PROJECT_ROOT}/app/build/rust-media-parity"
mkdir -p "${GENERATED}/src"
for source in TicketH264EncoderOutputAssembler TicketH264FrameRecord TicketTsf3FrameEnvelope; do
  git -C "${REPO_ROOT}" show "${BASELINE_REF}:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/${source}.java" |
    sed -e 's/TicketH264EncoderOutputAssembler/LegacyTicketH264EncoderOutputAssembler/g' \
        -e 's/TicketH264FrameRecord/LegacyTicketH264FrameRecord/g' \
        -e 's/TicketTsf3FrameEnvelope/LegacyTicketTsf3FrameEnvelope/g' > "${GENERATED}/src/Legacy${source}.java"
done
cp "${CRATE_ROOT}/tests/MediaParityTest.kt" "${GENERATED}/src/MediaParityTest.kt"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-media-parity/src'))
  ['Legacy', 'Rust', 'Warm'].each { engine ->
    app.tasks.register('mediaMeasure' + engine, JavaExec) {
      dependsOn app.tasks.named('testDebugUnitTest')
      classpath = app.tasks.named('testDebugUnitTest').get().classpath
      mainClass = 'lv.jolkins.pixelorchestrator.app.ticket.MediaMeasurements'
      args engine
      systemProperty 'java.library.path', new File(app.rootDir, 'build/pixelHealthHostRust/release').absolutePath
    }
  }
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
tasks=(:app:testDebugUnitTest --tests '*MediaParityTest' --tests '*TicketH264EncoderOutputAssemblerTest')
if [[ "${PIXEL_MEDIA_MEASURE:-0}" == 1 ]]; then tasks+=(:app:mediaMeasureLegacy :app:mediaMeasureRust :app:mediaMeasureWarm); fi
./gradlew "${tasks[@]}" --init-script "${GENERATED}/parity.gradle" --console=plain
