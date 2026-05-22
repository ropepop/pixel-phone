package lv.jolkins.pixelorchestrator.app.ticket

class RigasSatiksmeMonthlyTicketOperation(
  private val sourceApp: String,
  private val ticketFlow: String,
  private val runFlow: suspend (
    cleanDigits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    reusePreviousRigasSatiksmeQr: Boolean
  ) -> RigasSatiksmeMonthlyTicketFlowResult,
  private val captureImage: suspend (
    cleanDigits: String,
    hierarchy: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ) -> ByteArray?,
  private val markPhase: (MutableMap<String, Long>, String, Long) -> Unit
) {
  suspend fun run(
    cleanDigits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    reusePreviousRigasSatiksmeQr: Boolean
  ): TicketRequestOutcome {
    val flow = runFlow(cleanDigits, phases, requestStartedAtMillis, reusePreviousRigasSatiksmeQr)
    if (!flow.ok) {
      return TicketRequestOutcome(
        ok = false,
        reason = flow.reason,
        failedPhase = "rs_monthly_ticket_proof",
        phases = phases.toMap(),
        cleanupRequired = true
      )
    }

    val imageBytes = captureImage(cleanDigits, flow.hierarchy, phases, requestStartedAtMillis)
    if (imageBytes == null || imageBytes.isEmpty()) {
      return TicketRequestOutcome(
        ok = false,
        reason = "rs_monthly_ticket_image_capture_failed",
        failedPhase = "rs_monthly_ticket_image_capture",
        phases = phases.toMap(),
        cleanupRequired = true
      )
    }

    markPhase(phases, "rs_monthly_ticket_image_captured", requestStartedAtMillis)
    return TicketRequestOutcome(
      ok = true,
      reason = "generated",
      failedPhase = null,
      phases = phases.toMap(),
      imageBytes = imageBytes,
      sourceApp = sourceApp,
      ticketFlow = ticketFlow,
      cleanupRequired = true
    )
  }
}
