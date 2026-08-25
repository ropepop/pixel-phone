package lv.jolkins.pixelorchestrator.app.ticket

/**
 * Serializes background stream admission against control/RS automation claims.
 *
 * A cold stream start performs slow root/helper preflight before it schedules capture. A control
 * request can arrive during that wait, so the final admission decision and the claim transition
 * share one lock. Capture is therefore either admitted before the claim or rejected after it.
 */
internal class TicketStreamStartAdmission {
  private val lock = Any()
  private var automationClaims: Long = 0L

  fun claim(): Long = synchronized(lock) {
    automationClaims += 1L
    automationClaims
  }

  fun release(): Long = synchronized(lock) {
    automationClaims = (automationClaims - 1L).coerceAtLeast(0L)
    automationClaims
  }

  fun claimCount(): Long = synchronized(lock) { automationClaims }

  fun <T> admit(
    controlCodeOwnsStart: Boolean,
    additionalControlOwnershipActive: () -> Boolean,
    blocked: () -> T,
    admitted: () -> T
  ): T = synchronized(lock) {
    if (!controlCodeOwnsStart && (automationClaims > 0L || additionalControlOwnershipActive())) {
      blocked()
    } else {
      admitted()
    }
  }
}

internal fun ticketControlOwnershipActive(
  requestStatus: String,
  pendingBrowserCapture: Boolean,
  controlModeActive: Boolean,
  sessionState: String
): Boolean {
  return requestStatus == "queued" ||
    requestStatus == "running" ||
    pendingBrowserCapture ||
    controlModeActive ||
    sessionState in setOf("control_active", "control_transition", "control_exit")
}

internal fun shouldCoalescePreparingTicketStreamBeforePreflight(
  streamActive: Boolean,
  rootHardwareCaptureMode: Boolean,
  hardwareCaptureVerified: Boolean
): Boolean {
  return streamActive && rootHardwareCaptureMode && !hardwareCaptureVerified
}

internal fun shouldScheduleDisconnectAfterFinalControlClaim(
  remainingClaims: Long,
  clientCount: Int,
  sessionOpen: Boolean
): Boolean {
  return remainingClaims == 0L && clientCount == 0 && sessionOpen
}
