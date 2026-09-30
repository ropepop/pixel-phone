#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-capture-parity"
mkdir -p "${GENERATED}/src"
for name in TicketCaptureCadenceScheduler.java TicketCaptureDemandProtocol.kt TicketProofStreamCleanupPolicy.kt; do
  git -C "${REPO_ROOT}" show "dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/${name}" > "${GENERATED}/${name}"
done
python3 - "${GENERATED}" <<'GEN'
import pathlib,sys
p=pathlib.Path(sys.argv[1])
s=(p/'TicketCaptureCadenceScheduler.java').read_text().replace('TicketCaptureCadenceScheduler','LegacyCaptureCadence')
(p/'src/LegacyCaptureCadence.java').write_text(s)
s=(p/'TicketCaptureDemandProtocol.kt').read_text().replace('TicketCaptureDemandProtocol','LegacyCaptureDemandProtocol').replace('TicketCaptureDemandSession','LegacyCaptureDemandSession')
a=s.index('internal data class TicketCaptureDemandRequest'); b=s.index('/** Strict additive')
s=s[:a]+s[b:]
(p/'src/LegacyCaptureDemand.kt').write_text(s)
s=(p/'TicketProofStreamCleanupPolicy.kt').read_text().replace('ticketProofStreamCleanupDecision','legacyCaptureCleanup')
a=s.index('internal enum'); b=s.index('/**')
s=s[:a]+s[b:]
(p/'src/LegacyCaptureCleanup.kt').write_text(s)
GEN
cp "${CRATE_ROOT}/tests/CaptureParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-capture-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*CaptureParityTest' --tests '*TicketCaptureCadenceSchedulerTest' --init-script "${GENERATED}/parity.gradle" --console=plain
