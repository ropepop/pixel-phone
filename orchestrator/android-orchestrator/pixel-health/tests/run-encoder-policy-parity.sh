#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-encoder-policy-parity"
mkdir -p "${GENERATED}/src"
for name in TicketEncoderStartupPrimer TicketCodecInputLedger TicketCaptureVisibilityClassifier; do
 git -C "${REPO_ROOT}" show "dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/${name}.java" | sed "s/${name}/Legacy${name}/g" > "${GENERATED}/src/Legacy${name}.java"
done
git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/TicketStreamStartupRecoveryPolicy.kt' | sed 's/TicketStreamStartupRecoveryPolicy/LegacyTicketStreamStartupRecoveryPolicy/g' > "${GENERATED}/src/LegacyStartupRecovery.kt"
cp "${CRATE_ROOT}/tests/EncoderPolicyParityTest.kt" "${CRATE_ROOT}/tests/CaptureRecoveryParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app = gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir, 'rust-encoder-policy-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*EncoderPolicyParityTest' --tests '*CaptureRecoveryParityTest' --tests '*TicketStreamStartupRecoveryPolicyTest' --tests '*TicketEncoderStartupPrimerTest' --tests '*TicketCodecInputLedgerTest' --tests '*TicketEncoderTeardownTest' --init-script "${GENERATED}/parity.gradle" --console=plain
