package lv.jolkins.pixelorchestrator.app.ticket

internal class TicketControlCodeCleanupVisualProof(
  private val requiredRawTicketSamples: Int
) {
  init {
    require(requiredRawTicketSamples > 0)
  }

  var consecutiveRawTicketSamples: Int = 0
    private set

  fun observe(result: String): Boolean {
    consecutiveRawTicketSamples = if (result == TicketControlCodeVisualClassifier.RAW_TICKET) {
      consecutiveRawTicketSamples + 1
    } else {
      0
    }
    return consecutiveRawTicketSamples >= requiredRawTicketSamples
  }
}
