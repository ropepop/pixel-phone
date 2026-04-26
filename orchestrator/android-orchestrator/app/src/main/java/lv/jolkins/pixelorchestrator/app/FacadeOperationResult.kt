package lv.jolkins.pixelorchestrator.app

import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot

data class FacadeOperationResult(
  val success: Boolean,
  val message: String,
  val healthSnapshot: HealthSnapshot? = null,
  val outputPath: String = ""
)
