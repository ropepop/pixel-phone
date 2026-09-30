#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-redeploy-parity"
mkdir -p "${GENERATED}/src"
python3 - "${REPO_ROOT}" "${GENERATED}/src" <<'PY'
import hashlib, pathlib, subprocess, sys
repo, output = sys.argv[1:]
ref = 'b4a66c24979136be2b95f8e923568e64a80bb945'
path = 'orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt'
source = subprocess.check_output(['git', '-C', repo, 'show', f'{ref}:{path}']).decode()
def function(name):
    import re
    start = re.search(r'  private (?:suspend )?fun '+name+r'\(', source).start()
    end = re.search(r'\n  (?:private |(?:suspend )?fun |/\*\*)', source[start+3:]).start()+start+3
    return source[start:end].replace('private ', '', 1)
names = ['componentEnabledInConfig','componentHealthy','componentReadyForRedeploy','waitForRedeployHealth','healthyNeighborsOutsideTarget','resolveRedeployPolicy','detectNeighborRegressions','resolveRedeploySpec','rollbackFailureCode','buildPostDeployIssues','buildRedeployMessage']
functions = '\n'.join(function(name) for name in names)
functions = functions.replace('System.currentTimeMillis()', 'clock()').replace('supervisor.runHealthCheck(HealthScope.FULL)', 'probe()')
start = source.index('  private data class RedeploySpec(')
end = source.index('  private data class QuiescenceProbe(', start)
types = source[start:end].replace('private ', '')
header = '''package lv.jolkins.pixelorchestrator.app
import lv.jolkins.pixelorchestrator.coreconfig.*
// Policy bodies and return DTOs are extracted unchanged from frozen b4a66c24.
// Only Android clock/probe/delay/log effects are replaced with owned deterministic adapters.
internal class LegacyRedeployPolicy {
 var clock: () -> Long = { 0 }
 var probe: () -> HealthSnapshot = { HealthSnapshot() }
 var waits = 0
 private suspend fun delay(millis: Long) { waits++ }
 private fun logStep(message: String) {}
 private val SUPPORTED_COMPONENTS = linkedSetOf("ssh","vpn","cpu_frequency","management","ticket_screen","runtime_cleanup")
 private fun trainBotQuiescenceProbeScript() = "probe:train_bot"
 private fun satiksmeBotQuiescenceProbeScript() = "probe:satiksme_bot"
 private fun siteNotifierQuiescenceProbeScript() = "probe:site_notifier"
 private fun subscriptionBotQuiescenceProbeScript() = "probe:subscription_bot"
'''
pathlib.Path(output,'LegacyRedeployPolicy.kt').write_text(header+functions+types+'}\n')
print(f'REDEPLOY_ORACLE ref={ref} source_sha256={hashlib.sha256(source.encode()).hexdigest()}')
PY
cp "${CRATE_ROOT}/tests/RedeployParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app=gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir,'rust-redeploy-parity/src'))
  app.tasks.withType(Test).configureEach { testLogging.showStandardStreams = true }
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*RedeployParityTest' --tests '*OrchestratorFacadeRedeployPolicyTest' --init-script "${GENERATED}/parity.gradle" --console=plain
