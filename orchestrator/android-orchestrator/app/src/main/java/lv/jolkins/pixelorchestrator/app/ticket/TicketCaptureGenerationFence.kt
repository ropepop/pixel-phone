package lv.jolkins.pixelorchestrator.app.ticket

/**
 * Owns the handoff between one capture generation and its replacement.
 *
 * A replacement may be requested while the current generation is being stopped, but it cannot
 * publish a helper until the old Job has been joined and its process sweep has completed. Stale
 * finally blocks therefore cannot reap a newer helper.
 */
internal class TicketCaptureGenerationFence {
  private var activeGeneration = 0L
  private var cancellingGeneration = 0L
  private var pendingReplacementGeneration = 0L

  @Synchronized
  fun begin(generation: Long): Boolean {
    require(generation > 0L) { "capture generation must be positive" }
    if (cancellingGeneration != 0L) {
      pendingReplacementGeneration = generation
      return false
    }
    activeGeneration = generation
    return true
  }

  @Synchronized
  fun beginCancellation(generation: Long): Boolean {
    if (generation <= 0L || activeGeneration != generation) return false
    cancellingGeneration = generation
    return true
  }

  @Synchronized
  fun isCancellationInProgress(): Boolean = cancellingGeneration != 0L

  @Synchronized
  fun isCancelling(generation: Long): Boolean = cancellingGeneration == generation

  @Synchronized
  fun mayPublish(generation: Long): Boolean =
    activeGeneration == generation && cancellingGeneration != generation

  @Synchronized
  fun mayReap(generation: Long): Boolean = activeGeneration == generation

  /**
   * Opens the fence only after the caller has killed, joined, and reaped [generation].
   *
   * Returns the queued replacement generation, which becomes current atomically with opening the
   * fence, or zero when no replacement was requested.
   */
  @Synchronized
  fun completeCancellation(generation: Long): Long {
    if (cancellingGeneration != generation || activeGeneration != generation) return 0L
    val replacement = pendingReplacementGeneration
    pendingReplacementGeneration = 0L
    cancellingGeneration = 0L
    activeGeneration = replacement
    return replacement
  }

  @Synchronized
  fun completeNormally(generation: Long) {
    if (activeGeneration == generation && cancellingGeneration == 0L) {
      activeGeneration = 0L
    }
  }
}
