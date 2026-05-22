package lv.jolkins.pixelorchestrator.app.ticket

data class TicketRequestOutcome(
  val ok: Boolean,
  val reason: String,
  val failedPhase: String? = null,
  val phases: Map<String, Long> = emptyMap(),
  val imageBytes: ByteArray? = null,
  val sourceApp: String = "",
  val ticketFlow: String = "",
  val cleanupRequired: Boolean = false
)

data class RigasSatiksmeMonthlyTicketFlowResult(
  val ok: Boolean,
  val reason: String,
  val hierarchy: String = "",
  val details: String = ""
)
