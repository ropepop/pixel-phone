#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-phone-control-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketPhoneControlState.kt' > "${GENERATED}/original.kt"
python3 - "${GENERATED}" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);s=(p/'original.kt').read_text()
for name in ['TicketPhoneControlState','TicketPhoneControlClock','TicketRegistrationVisualEvidence','ticketRegistrationObservationIsFresh','ticketPhoneControlRegistrationIdentity','ticketPhoneControlBounds','TICKET_CONTROL_OBSERVATION_TTL_MILLIS']:
 s=s.replace(name,'Legacy'+name)
a=s.index('/** Single coalescing publication owner'); s=s[:a]
a=s.index('/** Private capture identity'); b=s.index('internal data class LegacyTicketRegistrationVisualEvidence');s=s[:a]+s[b:]
(p/'src/LegacyPhoneControl.kt').write_text(s)
PY
cp "${CRATE_ROOT}/tests/PhoneControlParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-phone-control-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*PhoneControlParityTest' --tests '*TicketPhoneControlStateTest' --init-script "${GENERATED}/parity.gradle" --console=plain
