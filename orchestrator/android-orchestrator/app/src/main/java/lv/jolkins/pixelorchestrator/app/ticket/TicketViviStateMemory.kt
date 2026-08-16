package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock

internal data class TicketViviStateMemorySnapshot(
  val state: TicketViviRecoveryState = TicketViviRecoveryState.UNKNOWN_VIVI,
  val ticketId: String? = null,
  val observedAtMillis: Long = 0L,
  val source: String = "none",
  val reason: String = "none",
  val hierarchy: String? = null
)

internal class TicketViviStateMemory(
  private val clockMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {
  @Volatile private var snapshot = TicketViviStateMemorySnapshot()
  @Volatile private var lastTicketDetailSnapshot = TicketViviStateMemorySnapshot()
  @Volatile private var lastRootTicketDetailSnapshot = TicketViviStateMemorySnapshot()

  fun record(
    state: TicketViviRecoveryState,
    ticketId: String?,
    source: String,
    reason: String,
    hierarchy: String? = null
  ): TicketViviStateMemorySnapshot {
    val previousDetail = lastTicketDetailSnapshot
    val next = TicketViviStateMemorySnapshot(
      state = state,
      ticketId = ticketId,
      observedAtMillis = clockMillis(),
      source = source,
      reason = reason,
      hierarchy = hierarchy?.takeIf { it.isNotBlank() }
        ?: state.takeIf { it == TicketViviRecoveryState.TICKET_DETAIL }
          ?.let { previousDetail.hierarchy }
    )
    snapshot = next
    if (state == TicketViviRecoveryState.TICKET_DETAIL) {
      lastTicketDetailSnapshot = next
      if (source == "root" && !hierarchy.isNullOrBlank()) {
        lastRootTicketDetailSnapshot = next
      }
    }
    return next
  }

  fun clear(source: String, reason: String): TicketViviStateMemorySnapshot {
    val next = record(TicketViviRecoveryState.UNKNOWN_VIVI, null, source, reason)
    lastTicketDetailSnapshot = TicketViviStateMemorySnapshot()
    lastRootTicketDetailSnapshot = TicketViviStateMemorySnapshot()
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

  /**
   * Returns the last hierarchy-backed ticket-detail proof while it is still fresh.
   *
   * Visual observations may refresh the general detail memory, but they must not replace the
   * rooted hierarchy proof used by the fast-state handoff. This keeps the request fast without
   * accepting accessibility-only or stale detail memory as a button-target source.
   */
  fun recentTicketDetailHierarchyWithin(maxAgeMillis: Long): TicketViviStateMemorySnapshot? {
    val detail = recentTicketDetailWithin(maxAgeMillis) ?: return null
    val rooted = lastRootTicketDetailSnapshot
    if (
      rooted.state != TicketViviRecoveryState.TICKET_DETAIL ||
      rooted.hierarchy.isNullOrBlank() ||
      rooted.observedAtMillis <= 0L
    ) {
      return null
    }
    val ageMillis = clockMillis() - rooted.observedAtMillis
    if (ageMillis !in 0..maxAgeMillis) {
      return null
    }
    if (
      detail.ticketId != null && rooted.ticketId != null &&
      detail.ticketId != rooted.ticketId
    ) {
      return null
    }
    return rooted
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
