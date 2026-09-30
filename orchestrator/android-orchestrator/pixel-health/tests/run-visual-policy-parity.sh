#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-visual-policy-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketVisualAction.kt' > "${GENERATED}/original.kt"
python3 - "${GENERATED}" <<'PY'
import pathlib,re,sys
p=pathlib.Path(sys.argv[1]);s=(p/'original.kt').read_text()
names=re.findall(r'internal (?:data class|enum class|class|fun) (\w+)',s)+['TICKET_ACTION_LIST_TO_SINGLE_USE','TICKET_ACTION_LIST_TO_TIME']
for name in sorted(set(names),key=len,reverse=True): s=re.sub(r'\b'+re.escape(name)+r'\b','Legacy'+name,s)
s=s.replace('import kotlinx.serialization.json.JsonObject','import kotlinx.serialization.Serializable\nimport kotlinx.serialization.json.JsonObject')
s=s.replace('internal data class','@Serializable\ninternal data class')
(p/'src/LegacyVisualPolicy.kt').write_text(s)
PY
cp "${CRATE_ROOT}/tests/VisualPolicyParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-visual-policy-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*VisualPolicyParityTest' --tests '*TicketVisualTerminalOwnershipTest' --tests '*TicketIdleRefreshTest' --init-script "${GENERATED}/parity.gradle" --console=plain
