package lv.jolkins.pixelorchestrator.app.ticket

internal const val TICKET_MONITOR_INTERVAL_MILLIS = 5 * 60_000L
internal const val TICKET_MONITOR_FRESH_MILLIS = 30_000L

internal data class TicketMonitoringConfig(val enabled: Boolean, val epoch: String)
internal data class TicketMonitoringObservation(
  val status: String,
  val reason: String,
  val capturedAtMillis: Long
) {
  fun fresh(now: Long) = capturedAtMillis > 0L && now - capturedAtMillis in 0 until TICKET_MONITOR_FRESH_MILLIS
}

internal fun ticketMonitoringObservation(
  state: TicketVisualPhoneState?, busy: Boolean, capturedAtMillis: Long
): TicketMonitoringObservation {
  val reason = when {
    busy -> "busy"
    state == null -> "capture_unavailable"
    state == TicketVisualPhoneState.ACTIVATED_DETAIL -> "ticket_detail_activated"
    state == TicketVisualPhoneState.UNACTIVATED_DETAIL -> "ticket_detail_unused"
    state == TicketVisualPhoneState.LOGIN_REQUIRED -> "login"
    state == TicketVisualPhoneState.BLOCKED -> "blocked"
    state in setOf(TicketVisualPhoneState.TICKET_LIST, TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      TicketVisualPhoneState.TICKETS_TIME_EMPTY) -> "ticket_list"
    else -> "unknown"
  }
  val status = when (reason) {
    "ticket_detail_activated", "ticket_detail_unused" -> "ready"
    "capture_unavailable" -> "unavailable"
    else -> "not_ready"
  }
  return TicketMonitoringObservation(status, reason, capturedAtMillis)
}

/** A dispatched asynchronous probe is pending, not evidence that capture failed. */
internal fun ticketMonitoringProbeDispatch(probeId: Long?, now: Long): TicketMonitoringObservation? =
  if (probeId == null) ticketMonitoringObservation(null, false, now) else null

/** Health cadence only; this state cannot authorize an input or renew a control observation. */
internal class TicketMonitoringSchedule {
  private var config: TicketMonitoringConfig? = null
  private var lastReported: TicketMonitoringObservation? = null
  private var lastReportedAt = 0L
  private var lastCheckedAt: Long? = null
  var acceptCapturedAfter = 0L
    private set

  fun configure(next: TicketMonitoringConfig?, now: Long): Boolean {
    if (config != next) {
      config = next
      lastReported = null
      lastReportedAt = 0L
      lastCheckedAt = null
      acceptCapturedAfter = now
    }
    return next?.enabled == true
  }

  fun checkDue(now: Long) = lastCheckedAt?.let { now - it >= TICKET_MONITOR_INTERVAL_MILLIS } ?: true
  fun checked(now: Long) { lastCheckedAt = now }
  fun shouldReport(observation: TicketMonitoringObservation, now: Long): Boolean =
    observation.fresh(now) && observation.capturedAtMillis >= (lastReported?.capturedAtMillis ?: 0L) &&
      (lastReported?.status != observation.status ||
      lastReported?.reason != observation.reason || now - lastReportedAt >= TICKET_MONITOR_INTERVAL_MILLIS)

  fun reported(observation: TicketMonitoringObservation, now: Long) {
    lastReported = observation
    lastReportedAt = now
    lastCheckedAt = now
  }
}
