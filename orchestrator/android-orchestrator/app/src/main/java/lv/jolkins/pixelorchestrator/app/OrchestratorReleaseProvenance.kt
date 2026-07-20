package lv.jolkins.pixelorchestrator.app

import kotlinx.serialization.Serializable
import lv.jolkins.pixelorchestrator.BuildConfig

@Serializable
data class OrchestratorReleaseProvenance(
  val releaseId: String,
  val sourceCommit: String,
  val sourceDirty: Boolean,
  val builtAt: String
) {
  companion object {
    fun current(): OrchestratorReleaseProvenance {
      return OrchestratorReleaseProvenance(
        releaseId = BuildConfig.ORCHESTRATOR_RELEASE_ID,
        sourceCommit = BuildConfig.ORCHESTRATOR_SOURCE_COMMIT,
        sourceDirty = BuildConfig.ORCHESTRATOR_SOURCE_DIRTY,
        builtAt = BuildConfig.ORCHESTRATOR_BUILD_TIME
      )
    }
  }
}
