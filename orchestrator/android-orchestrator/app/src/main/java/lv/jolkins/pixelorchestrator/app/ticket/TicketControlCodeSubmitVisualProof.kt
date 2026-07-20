package lv.jolkins.pixelorchestrator.app.ticket

internal class TicketControlCodeSubmitVisualProof(
  private val requiredSamples: Int = 2,
  private val expectedResult: String = TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY
) {
  private var lastProbeId: Long = 0L
  private var consecutiveReadySamples: Int = 0

  fun observe(probeId: Long, result: String): Boolean {
    if (probeId <= lastProbeId) {
      return false
    }
    lastProbeId = probeId
    consecutiveReadySamples = if (
      result == expectedResult
    ) {
      consecutiveReadySamples + 1
    } else {
      0
    }
    return consecutiveReadySamples >= requiredSamples
  }
}
