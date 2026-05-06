package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock

internal data class TicketViviStateMemorySnapshot(
  val state: TicketViviRecoveryState = TicketViviRecoveryState.UNKNOWN_VIVI,
  val ticketId: String? = null,
  val observedAtMillis: Long = 0L,
  val source: String = "none",
  val reason: String = "none"
)

internal class TicketViviStateMemory {
  @Volatile private var snapshot = TicketViviStateMemorySnapshot()
  @Volatile private var lastTicketDetailSnapshot = TicketViviStateMemorySnapshot()

  fun record(
    state: TicketViviRecoveryState,
    ticketId: String?,
    source: String,
    reason: String
  ): TicketViviStateMemorySnapshot {
    val next = TicketViviStateMemorySnapshot(
      state = state,
      ticketId = ticketId,
      observedAtMillis = SystemClock.elapsedRealtime(),
      source = source,
      reason = reason
    )
    snapshot = next
    if (state == TicketViviRecoveryState.TICKET_DETAIL) {
      lastTicketDetailSnapshot = next
    }
    return next
  }

  fun current(): TicketViviStateMemorySnapshot = snapshot

  fun recentTicketDetailWithin(maxAgeMillis: Long): TicketViviStateMemorySnapshot? {
    val current = lastTicketDetailSnapshot
    if (current.state != TicketViviRecoveryState.TICKET_DETAIL || current.observedAtMillis <= 0L) {
      return null
    }
    val ageMillis = SystemClock.elapsedRealtime() - current.observedAtMillis
    return current.takeIf { ageMillis in 0..maxAgeMillis }
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
