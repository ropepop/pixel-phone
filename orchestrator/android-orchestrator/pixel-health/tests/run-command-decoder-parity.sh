#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-command-decoder-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show 'c66dc93:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketSpacetimeCommandSubscription.kt' > "${GENERATED}/original.kt"
python3 - "${GENERATED}" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
source = (root / 'original.kt').read_text()
imports = source[:source.index('internal enum')]
parser = source[source.index('internal fun parseTicketSpacetimeCommandSubscriptionMessage('):source.index('/** Reconnecting transport')]
selectors = source[source.index('private fun JsonElement.toMonitoringConfigOrNull'):source.index('internal fun ticketSpacetimeKeyframeReconnectDelayMillis')]
constants = source[source.index('private val LIVE_TICKET_COMMAND_TYPES'):]
constants += '\nprivate const val COMMAND_SUBSCRIPTION_REQUEST_ID = 0\n' if 'private const val COMMAND_SUBSCRIPTION_REQUEST_ID' not in constants else ''
(root / 'src/LegacyCommandDecoder.kt').write_text(imports + parser.replace('parseTicketSpacetimeCommandSubscriptionMessage', 'legacyParseCommandMessage') + selectors + constants)
PY
cp "${CRATE_ROOT}/tests/CommandDecoderParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-command-decoder-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*CommandDecoderParityTest' --tests '*TicketCommandSubscriptionSocketTest' --tests '*TicketSpacetimeCommandExpiryTest' --init-script "${GENERATED}/parity.gradle" --console=plain
