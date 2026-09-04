package lv.jolkins.pixelorchestrator.app.ticket

internal enum class TicketProofStreamCleanupDecision {
  STOP,
  RETRY,
  DROP
}

/**
 * Authorizes cleanup only for the exact no-viewer stream session started by a proof command.
 * A newer session, any viewer, or an already-closed stream permanently invalidates the lease.
 * Temporary phone-control ownership defers the same fenced cleanup for another grace period.
 */
internal fun ticketProofStreamCleanupDecision(
  expectedSessionGeneration: Long?,
  currentSessionGeneration: Long,
  streamActive: Boolean,
  clientCount: Int,
  startOwnershipActive: Boolean,
  controlOwnershipActive: Boolean,
  actionOwnershipActive: Boolean,
  reauthOwnershipActive: Boolean
): TicketProofStreamCleanupDecision {
  if (!streamActive || clientCount != 0) return TicketProofStreamCleanupDecision.DROP
  if (expectedSessionGeneration != null &&
    (expectedSessionGeneration <= 0L || expectedSessionGeneration != currentSessionGeneration)
  ) {
    return TicketProofStreamCleanupDecision.DROP
  }
  if (startOwnershipActive ||
    controlOwnershipActive ||
    actionOwnershipActive ||
    reauthOwnershipActive
  ) {
    return TicketProofStreamCleanupDecision.RETRY
  }
  return TicketProofStreamCleanupDecision.STOP
}
