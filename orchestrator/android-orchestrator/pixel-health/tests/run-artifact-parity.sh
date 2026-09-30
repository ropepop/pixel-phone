#!/usr/bin/env bash
set -euo pipefail
CRATE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "${CRATE_ROOT}/.." && pwd)"
REPO_ROOT="$(cd "${PROJECT_ROOT}/../.." && pwd)"
GENERATED="${PROJECT_ROOT}/runtime-installer/build/rust-artifact-parity"
mkdir -p "${GENERATED}/src"
git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/runtime-installer/src/main/kotlin/lv/jolkins/pixelorchestrator/runtimeinstaller/ArtifactSyncer.kt' | sed 's/class ArtifactSyncer(/class LegacyArtifactSyncer(/' > "${GENERATED}/src/LegacyArtifactSyncer.kt"
{
  printf '%s\n' 'package lv.jolkins.pixelorchestrator.runtimeinstaller' 'object LegacyArtifactAdmission {' 'private const val REQUIRED_SIGNATURE_SCHEMA = "none"' 'private val REQUIRED_BOOTSTRAP_ARTIFACT_IDS = listOf("dropbear-bundle","tailscale-bundle")' 'private val OPTIONAL_BOOTSTRAP_ARTIFACT_IDS = listOf("optional-fixture")' 'private const val ROOTFS_ARTIFACT_ID = "adguardhome-rootfs"' 'private const val DNS_RUNTIME_ASSET_ID = "dns-runtime-assets"'
  git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/app/src/main/java/lv/jolkins/pixelorchestrator/app/OrchestratorFacade.kt' | sed -n '/  private fun validateManifest(/,/  private suspend fun writeRuntimeEnvFiles(/p' | sed '$d' | sed 's/  private fun /  fun /g'
  git -C "${REPO_ROOT}" show 'dd41627:orchestrator/android-orchestrator/runtime-installer/src/main/kotlin/lv/jolkins/pixelorchestrator/runtimeinstaller/RuntimeInstaller.kt' | sed -n '/  private fun ensureRequiredArtifactsPresent(/,/  private companion object {/p' | sed '$d' | sed 's/  private fun /  fun /g'
  printf '%s\n' 'private const val DROPBEAR_ARTIFACT_ID = "dropbear-bundle"' 'private const val TAILSCALE_ARTIFACT_ID = "tailscale-bundle"' '}'
} > "${GENERATED}/src/LegacyArtifactAdmission.kt"
cp "${CRATE_ROOT}/tests/ArtifactAdmissionParityTest.kt" "${GENERATED}/src/"
cp "${CRATE_ROOT}/tests/ArtifactParityTest.kt" "${GENERATED}/src/"
cat > "${GENERATED}/parity.gradle" <<'GRADLE'
gradle.projectsEvaluated {
  def project = gradle.rootProject.project(':runtime-installer')
  project.kotlin.sourceSets.getByName('test').kotlin.srcDir(new File(project.buildDir, 'rust-artifact-parity/src'))
  project.tasks.withType(Test).configureEach {
    dependsOn(gradle.rootProject.tasks.named('buildPixelHealthHost'))
    def name = System.getProperty('os.name').startsWith('Mac') ? 'release' : 'release'
    systemProperty('java.library.path', new File(gradle.rootProject.buildDir, 'pixelHealthHostRust/'+name).absolutePath)
  }
}
GRADLE
cd "${PROJECT_ROOT}"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == Darwin ]]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
./gradlew :runtime-installer:test --init-script "${GENERATED}/parity.gradle" --console=plain
