#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
BASELINE_REF="${1:-d6a6bbb}"
GENERATED="${PROJECT_ROOT}/app/build/rust-checkpoint-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show "${BASELINE_REF}:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketActivationCheckpoint.kt" |
  sed -e 's/TicketActivation/LegacyTicketActivation/g' -e 's/ticketActivation/legacyTicketActivation/g' > "${GENERATED}/src/LegacyTicketActivationCheckpoint.kt"
cp "${CRATE_ROOT}/tests/CheckpointParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-checkpoint-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*CheckpointParityTest' --tests '*TicketActivationCheckpointTest' --init-script "${GENERATED}/parity.gradle" --console=plain
