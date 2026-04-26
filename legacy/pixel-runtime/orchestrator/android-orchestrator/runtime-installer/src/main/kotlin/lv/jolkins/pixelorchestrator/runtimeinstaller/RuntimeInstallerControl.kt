package lv.jolkins.pixelorchestrator.runtimeinstaller

import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1

interface RuntimeInstallerControl {
  suspend fun bootstrap(
    config: StackConfigV1,
    assets: AssetProvider,
    manifest: ArtifactManifest,
    rootfsArtifactId: String
  ): BootstrapResult

  suspend fun syncBundledRuntimeAssets(assets: AssetProvider, component: String? = null): SyncResult
  suspend fun installComponentRelease(
    config: StackConfigV1,
    component: String,
    manifest: ComponentReleaseManifest
  ): SyncResult

  suspend fun rollbackComponentRelease(
    config: StackConfigV1,
    component: String,
    rollbackMetadata: ReleaseRollbackMetadata
  ): SyncResult

  suspend fun pruneComponentReleases(config: StackConfigV1, component: String, keepReleases: Int = 3): SyncResult
}
