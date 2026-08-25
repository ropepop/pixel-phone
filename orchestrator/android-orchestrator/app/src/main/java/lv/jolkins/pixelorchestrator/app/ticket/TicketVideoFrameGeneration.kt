package lv.jolkins.pixelorchestrator.app.ticket

/** Immutable capture-generation identity carried from callback admission to frame numbering. */
internal data class TicketVideoFrameGeneration(
  val epoch: Long,
  val width: Int,
  val height: Int
) {
  fun matches(currentEpoch: Long, currentWidth: Int?, currentHeight: Int?): Boolean {
    return epoch > 0L &&
      currentEpoch == epoch &&
      currentWidth == width &&
      currentHeight == height
  }
}
