#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-telemetry-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/telemetry/OrchestratorTelemetry.kt' > "${GENERATED}/original.kt"
python3 - "${GENERATED}" <<'PY'
import pathlib,sys,re
p=pathlib.Path(sys.argv[1]);s=(p/'original.kt').read_text()
s=re.sub(r'\b(?:Secure)?OrchestratorTelemetry\w*\b',lambda m:'Legacy'+m.group(),s)
(p/'src/LegacyTelemetry.kt').write_text(s)
PY
cp "${CRATE_ROOT}/tests/TelemetryParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-telemetry-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*TelemetryParityTest' --tests '*OrchestratorTelemetry*' --init-script "${GENERATED}/parity.gradle" --console=plain
