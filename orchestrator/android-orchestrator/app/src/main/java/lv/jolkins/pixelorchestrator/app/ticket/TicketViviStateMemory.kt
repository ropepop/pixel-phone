package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock

internal data class TicketViviStateMemorySnapshot(
  val state: TicketViviRecoveryState = TicketViviRecoveryState.UNKNOWN_VIVI,
  val ticketId: String? = null,
  val observedAtMillis: Long = 0L,
  val source: String = "none",
  val reason: String = "none"
) {
  /** Compile-only bridge for legacy callers. Ticket hierarchy bytes are never retained. */
  val hierarchy: String? get() = null
}

internal class TicketViviStateMemory(
  private val clockMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {
  @Volatile private var snapshot = TicketViviStateMemorySnapshot()
  @Volatile private var lastTicketDetailSnapshot = TicketViviStateMemorySnapshot()

  fun record(
    state: TicketViviRecoveryState,
    ticketId: String?,
    source: String,
    reason: String,
    @Suppress("UNUSED_PARAMETER")
    hierarchy: String? = null
  ): TicketViviStateMemorySnapshot {
    val next = TicketViviStateMemorySnapshot(
      state = state,
      ticketId = ticketId,
      observedAtMillis = clockMillis(),
      source = source,
      reason = reason
    )
    snapshot = next
    if (state == TicketViviRecoveryState.TICKET_DETAIL) {
      lastTicketDetailSnapshot = next
    }
    return next
  }

  fun clear(source: String, reason: String): TicketViviStateMemorySnapshot {
    val next = record(TicketViviRecoveryState.UNKNOWN_VIVI, null, source, reason)
    lastTicketDetailSnapshot = TicketViviStateMemorySnapshot()
    return next
  }

  fun current(): TicketViviStateMemorySnapshot = snapshot

  fun recentTicketDetailWithin(maxAgeMillis: Long): TicketViviStateMemorySnapshot? {
    val current = lastTicketDetailSnapshot
    if (current.state != TicketViviRecoveryState.TICKET_DETAIL || current.observedAtMillis <= 0L) {
      return null
    }
    val ageMillis = clockMillis() - current.observedAtMillis
    return current.takeIf { ageMillis in 0..maxAgeMillis }
  }

  /** Retained temporarily for legacy callers; hierarchy-backed Ticket proof is retired. */
  fun recentTicketDetailHierarchyWithin(
    @Suppress("UNUSED_PARAMETER") maxAgeMillis: Long
  ): TicketViviStateMemorySnapshot? {
    return null
  }

  fun health(nowMillis: Long): TicketViviStateHealth {
    val current = snapshot
    return TicketViviStateHealth(
      state = current.state.name,
      ticketId = current.ticketId,
      observedAgoMillis = current.observedAtMillis.takeIf { it > 0L }
        ?.let { (nowMillis - it).coerceAtLeast(0L) },
      source = current.source,
      reason = current.reason
    )
  }
}
