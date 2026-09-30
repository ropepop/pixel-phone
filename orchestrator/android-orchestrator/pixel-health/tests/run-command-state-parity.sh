#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-command-state-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeCommandSubscription.kt' > "${GENERATED}/subscription.kt"
git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeWorker.kt' > "${GENERATED}/worker.kt"
python3 - "${GENERATED}" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);s=(p/'subscription.kt').read_text();w=(p/'worker.kt').read_text()
body=s[s.index('internal class TicketCommandInbox {'):s.index('/** Terminal results remain')].replace('TicketCommandInbox','LegacyCommandInbox').replace('ticketSpacetimeCommandExpired','legacyCommandExpired')
expiry=w[w.index('internal fun ticketSpacetimeCommandExpired('):w.index('private val ticketOperationalSensitiveFieldFragments')].replace('ticketSpacetimeCommandExpired','legacyCommandExpired').replace('shouldDispatchRevalidatedStartCommand','legacyStartDispatch')
priority=w[w.index('internal fun prioritizePendingCommandsForStreamState('):w.index('private data class TicketSpacetimeConfig')].replace('prioritizePendingCommandsForStreamState','legacyPrioritize')
ack=w[w.index('      if (!result.terminal) continue'):w.index('      if (!result.ok) client.safeLog')]
ack=ack.replace('continue','return null').replace('client.ack(command.id,','return TicketCommandAcknowledgement(')
ack='internal fun legacyAck(command: TicketSpacetimeCommand,result: TicketSpacetimeCommandResult): TicketCommandAcknowledgement? {\n'+ack+'}\n'
(p/'src/LegacyCommandState.kt').write_text('package lv.jolkins.pixelorchestrator.app.ticket\nimport java.time.Instant\nimport kotlinx.coroutines.channels.Channel\n'+body+expiry+priority+ack)
PY
cp "${CRATE_ROOT}/tests/CommandStateParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-command-state-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*CommandStateParityTest' --tests '*TicketCommandSubscriptionSocketTest' --tests '*TicketSpacetimeCommandExpiryTest' --init-script "${GENERATED}/parity.gradle" --console=plain
