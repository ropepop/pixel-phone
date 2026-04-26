package lv.jolkins.pixelorchestrator.runtimeinstaller

import kotlinx.serialization.Serializable

@Serializable
data class ArtifactManifest(
  val schema: Int = 1,
  val manifestVersion: String = "",
  val signatureSchema: String = "",
  val artifacts: List<ArtifactEntry> = emptyList()
)

@Serializable
data class ComponentReleaseManifest(
  val schema: Int = 1,
  val componentId: String = "",
  val releaseId: String = "",
  val signatureSchema: String = "",
  val artifacts: List<ArtifactEntry> = emptyList()
)

@Serializable
data class ArtifactEntry(
  val id: String,
  val url: String,
  val sha256: String,
  val fileName: String,
  val sizeBytes: Long = 0,
  val required: Boolean = true
)
