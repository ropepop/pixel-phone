package lv.jolkins.pixelorchestrator.app

import kotlinx.serialization.Serializable
import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot

@Serializable
data class OrchestratorActionResult(
  val pixelRunId: String,
  val action: String,
  val component: String,
  val success: Boolean,
  val message: String,
  val outputPath: String = "",
  val healthSnapshot: HealthSnapshot? = null,
  val recordedAt: String,
  val resultSource: String = "artifact"
)
