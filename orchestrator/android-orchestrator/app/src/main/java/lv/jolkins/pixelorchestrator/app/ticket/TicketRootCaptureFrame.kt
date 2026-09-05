package lv.jolkins.pixelorchestrator.app.ticket

data class TicketRootCaptureFrame(
  internal val captureGeneration: Long,
  val keyFrame: Boolean,
  val captureAttemptId: Long,
  val codecGeneration: Long,
  val captureStartUs: Long,
  val captureCompleteUs: Long,
  val codecInputUs: Long,
  val codecOutputUs: Long,
  val recordEmissionUs: Long,
  val payload: ByteArray,
  val width: Int,
  val height: Int
) {
  internal fun hasCurrentCaptureGeneration(currentGeneration: Long): Boolean {
    return captureGeneration > 0L && captureGeneration == currentGeneration
  }
}
