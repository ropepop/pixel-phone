#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/app/build/rust-brightness-parity"
SOURCE=orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation
mkdir -p "${GENERATED}/src"
{
  printf '%s\n' 'package lv.jolkins.pixelorchestrator.app.phoneautomation' 'import kotlin.math.abs' 'import kotlin.math.roundToInt' 'internal object LegacyBrightnessVerifier {'
  git -C "${REPO_ROOT}" show "9d1aa21:${SOURCE}/TouchBrightnessRuntime.kt" |
    sed -n '/  private fun ScreenBrightnessState.withRemotePanelFallback()/,/  companion object {/p' | sed '$d' |
    sed -e 's/  private fun /  fun /g' -e 's/withRemotePanelFallback()/withRemotePanelFallback(remoteStateArg: ScreenBrightnessState?)/' -e 's/bridge.remoteScreenBrightnessState()/remoteStateArg/' -e 's/ScreenBrightnessControl/LegacyScreenBrightnessControl/g'
  git -C "${REPO_ROOT}" show "9d1aa21:${SOURCE}/TouchBrightnessRuntime.kt" |
    sed -n '/  private fun ScreenBrightnessState.isPanelSleepBrightnessState()/,/  companion object {/p' | sed '$d' | sed 's/  private fun /  fun /g'
  printf '%s\n' 'private const val VISIBLE_PANEL_FALLBACK_PERCENT = 20' 'private const val PANEL_SLEEP_TARGET_PERCENT = 0' 'private const val MANUAL_BRIGHTNESS_MODE = 0' 'private const val AUTOMATIC_BRIGHTNESS_MODE = 1' 'private const val DISPLAY_PERCENT_TOLERANCE = 0.5f' 'private const val PANEL_PERCENT_TOLERANCE = 1f' 'private const val PANEL_VALUE_TOLERANCE = 2' 'private const val PANEL_SLEEP_DISPLAY_PERCENT_TOLERANCE = 0.5f' 'private const val PANEL_SLEEP_PANEL_VALUE_TOLERANCE = 2' '}'
} > "${GENERATED}/src/LegacyBrightnessVerifier.kt"
{
  printf '%s\n' 'package lv.jolkins.pixelorchestrator.app.phoneautomation' 'import kotlin.math.roundToInt'
  git -C "${REPO_ROOT}" show "9d1aa21:${SOURCE}/ScreenBrightnessControl.kt" | sed -n '/internal object ScreenBrightnessControl/,$p' | sed 's/ScreenBrightnessControl/LegacyScreenBrightnessControl/g'
} > "${GENERATED}/src/LegacyScreenBrightnessControl.kt"
cp "${CRATE_ROOT}/tests/BrightnessParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def app=gradle.rootProject.project(':app')
  app.android.sourceSets.getByName('test').java.srcDir(new File(app.buildDir,'rust-brightness-parity/src'))
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :app:testDebugUnitTest --tests '*BrightnessParityTest' --tests '*TouchBrightness*' --tests '*ScreenBrightnessControlTest' --init-script "${GENERATED}/parity.gradle" --console=plain
