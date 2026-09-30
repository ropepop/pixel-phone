package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TicketMonitoringTest {
  @Test fun problemRechecksAreBoundedAndKeepTheFiveMinuteReportDeadline() {
    for (problem in listOf(
      ticketMonitoringObservation(null, true, 1_000L),
      ticketMonitoringObservation(null, false, 1_000L),
      ticketMonitoringObservation(TicketVisualPhoneState.BLOCKED, false, 1_000L)
    )) {
      val schedule = TicketMonitoringSchedule()
      schedule.configure(TicketMonitoringConfig(true, "enabled"), 1_000L)
      schedule.reported(problem, 1_000L)
      for (check in 1..12) {
        val now = 1_000L + check * 15_000L
        assertFalse(schedule.checkDue(now - 1L))
        assertTrue("Missing recheck $check for ${problem.reason}", schedule.checkDue(now))
        assertTrue(schedule.checkDue(now, problem.copy(capturedAtMillis = now - 1L)))
        schedule.checked(now)
        assertFalse(schedule.shouldReport(problem.copy(capturedAtMillis = now), now))
      }
      assertFalse(schedule.checkDue(196_000L))
      assertFalse(schedule.checkDue(300_999L))
      assertTrue(schedule.checkDue(301_000L))
      val persistent = problem.copy(capturedAtMillis = 301_000L)
      assertTrue(schedule.shouldReport(persistent, 301_000L))
      schedule.reported(persistent, 301_000L)
      assertTrue(schedule.checkDue(316_000L))
    }
  }

  @Test fun rechecksRequireNewEvidenceAndRecoveryOrConfigurationCancelsThem() {
    val schedule = TicketMonitoringSchedule()
    schedule.configure(TicketMonitoringConfig(true, "enabled"), 1_000L)
    schedule.reported(ticketMonitoringObservation(null, true, 1_000L), 1_000L)
    schedule.checked(16_000L)
    val ready = ticketMonitoringObservation(TicketVisualPhoneState.ACTIVATED_DETAIL, false, 15_999L)
    assertFalse(schedule.shouldReport(ready, 16_000L))
    assertNull(ticketMonitoringProbeDispatch(1L, 16_001L))
    assertFalse(schedule.checkDue(17_000L))
    val fresh = ready.copy(capturedAtMillis = 17_000L)
    assertTrue(schedule.shouldReport(fresh, 17_000L))
    schedule.reported(fresh, 17_000L)
    assertFalse(schedule.checkDue(31_000L))
    assertFalse(schedule.checkDue(316_999L))
    assertTrue(schedule.checkDue(317_000L))
    schedule.reported(ticketMonitoringObservation(null, true, 318_000L), 318_000L)
    assertFalse(schedule.configure(TicketMonitoringConfig(false, "disabled"), 319_000L))
    assertTrue(schedule.configure(TicketMonitoringConfig(true, "new"), 320_000L))
    schedule.reported(ready.copy(capturedAtMillis = 320_000L), 320_000L)
    assertFalse(schedule.checkDue(333_000L))
    assertTrue(schedule.checkDue(620_000L))
  }

  @Test fun delayedRechecksDoNotCatchUpOrExtendTheirWindowWhenTheProblemChanges() {
    val schedule = TicketMonitoringSchedule()
    schedule.configure(TicketMonitoringConfig(true, "enabled"), 1_000L)
    schedule.reported(ticketMonitoringObservation(null, true, 1_000L), 1_000L)
    assertTrue(schedule.checkDue(61_000L))
    schedule.checked(61_000L)
    assertFalse(schedule.checkDue(61_001L))
    assertFalse(schedule.checkDue(75_999L))
    assertTrue(schedule.checkDue(76_000L))
    schedule.checked(176_000L)
    val changed = ticketMonitoringObservation(null, false, 176_000L)
    assertTrue(schedule.shouldReport(changed, 176_000L))
    schedule.reported(changed, 176_000L)
    assertFalse(schedule.checkDue(191_000L))
    assertFalse(schedule.checkDue(475_999L))
    assertTrue(schedule.checkDue(476_000L))
  }

  @Test fun warmIdleProbeStaysPendingUntilRealObservationAcrossFourIntervals() {
    val schedule = TicketMonitoringSchedule()
    schedule.configure(TicketMonitoringConfig(true, "enabled"), 100L)
    var now = 100L
    schedule.reported(ticketMonitoringObservation(TicketVisualPhoneState.ACTIVATED_DETAIL, false, now), now)
    repeat(4) { interval ->
      now += TICKET_MONITOR_INTERVAL_MILLIS
      assertTrue(schedule.checkDue(now))
      schedule.checked(now)
      // The helper can capture before dispatch returns; a fabricated unavailable result
      // here used to reject this real observation as older and keep a false incident open.
      val capturedAt = now + 10L
      now += 20L
      assertNull(ticketMonitoringProbeDispatch(interval + 1L, now))
      assertFalse(schedule.checkDue(now + 1L))
      val ready = ticketMonitoringObservation(TicketVisualPhoneState.ACTIVATED_DETAIL, false, capturedAt)
      assertTrue(schedule.shouldReport(ready, now + 1_000L))
      now += 1_000L
      schedule.reported(ready, now)
    }
    now += TICKET_MONITOR_INTERVAL_MILLIS
    val failedDispatch = ticketMonitoringProbeDispatch(null, now)!!
    assertEquals("unavailable", failedDispatch.status)
    assertTrue(schedule.shouldReport(failedDispatch, now))
    schedule.reported(failedDispatch, now)
    assertFalse(schedule.shouldReport(
      ticketMonitoringObservation(TicketVisualPhoneState.ACTIVATED_DETAIL, false, now - 1L), now))
  }

  @Test fun healthClassifiesBothDetailsAndNeverConfusesMissingCaptureWithUnknownScreen() {
    for (state in TicketVisualPhoneState.entries) {
      val result = ticketMonitoringObservation(state, false, 100L)
      assertEquals(if (state in setOf(TicketVisualPhoneState.ACTIVATED_DETAIL,
        TicketVisualPhoneState.UNACTIVATED_DETAIL)) "ready" else "not_ready", result.status)
      assertEquals("not_ready", ticketMonitoringObservation(state, true, 100L).status)
    }
    assertEquals("unavailable", ticketMonitoringObservation(null, false, 100L).status)
    assertEquals("unknown", ticketMonitoringObservation(TicketVisualPhoneState.UNKNOWN, false, 100L).reason)
  }

  @Test fun fiveMinuteChecksAndTransitionsRequireFreshEvidenceAndReenableStartsFresh() {
    val schedule = TicketMonitoringSchedule()
    assertFalse(schedule.configure(null, 0L))
    assertTrue(schedule.configure(TicketMonitoringConfig(true, "first"), 100L))
    assertTrue(schedule.checkDue(100L))
    val ready = ticketMonitoringObservation(TicketVisualPhoneState.ACTIVATED_DETAIL, false, 100L)
    assertTrue(schedule.shouldReport(ready, 100L))
    schedule.reported(ready, 100L)
    assertFalse(schedule.shouldReport(ready, 101L))
    assertFalse(schedule.checkDue(300_099L))
    assertTrue(schedule.checkDue(300_100L))
    assertFalse(schedule.shouldReport(ready, 300_100L))
    assertTrue(schedule.shouldReport(ready.copy(capturedAtMillis = 300_100L), 300_100L))
    val blocked = ticketMonitoringObservation(TicketVisualPhoneState.BLOCKED, false, 200L)
    assertTrue(schedule.shouldReport(blocked, 200L))
    schedule.reported(blocked, 200L)
    assertFalse(schedule.shouldReport(ready, 200L))
    assertTrue(schedule.shouldReport(ready.copy(capturedAtMillis = 201L), 201L))
    assertFalse(ready.fresh(30_100L))
    assertFalse(ready.fresh(99L))
    assertFalse(schedule.configure(TicketMonitoringConfig(false, "disabled"), 400L))
    assertTrue(schedule.configure(TicketMonitoringConfig(true, "new"), 500L))
    assertEquals(500L, schedule.acceptCapturedAfter)
    assertTrue(schedule.checkDue(500L))
  }

  @Test fun monitoringConfigUsesExistingSubscriptionAndClearsOnDisconnect() {
    fun row(ticket: String, enabled: Boolean, epoch: String) = JsonPrimitive(
      """["$ticket:pixel","$ticket","pixel",$enabled,"$epoch"]""").toString()
    fun update(enabled: Boolean, epoch: String) = parseTicketSpacetimeCommandSubscriptionMessage(
      """{"TransactionUpdateLight":{"update":{"tables":[{"table_name":"ticketremote_monitoring_config",
        "updates":[{"Uncompressed":{"deletes":[${row("ticket", true, "old")}],
        "inserts":[${row("other", true, "unauthorized")},${row("ticket", enabled, epoch)}]}}]}]}}}""",
      "ticket", "pixel")
    val inbox = TicketCommandInbox()
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED))
    assertNull(inbox.controlSnapshot()!!.monitoring)
    inbox.apply(update(true, "new"))
    assertEquals(TicketMonitoringConfig(true, "new"), inbox.controlSnapshot()!!.monitoring)
    inbox.apply(update(false, "disabled"))
    assertEquals(TicketMonitoringConfig(false, "disabled"), inbox.controlSnapshot()!!.monitoring)
    inbox.disconnected()
    assertNull(inbox.controlSnapshot())
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED))
    assertNull(inbox.controlSnapshot()!!.monitoring)
  }

  @Test fun monitoringTimeUsesConservativeDatabaseAnchorAndCannotOutliveIt() {
    val clock = TicketPhoneControlClock(serverMillis = 1_800_000L, receivedMonotonicMillis = 5_000L)
    assertEquals(1_799_499L, clock.observedAtMillis(4_500_000L, 6_000L))
    assertNull(clock.observedAtMillis(6_001_000L, 6_000L))
    assertNull(clock.observedAtMillis(34_000_000L, 35_000L))
    assertNull(clock.observedAtMillis(0L, 6_000L))
    assertNull(TicketPhoneControlPublisher(TicketPhoneControlState(), { 6_000L }).observedAtMillis(5_000L))
  }

  @Test fun monitoringNeverQueuesBehindAUserPhoneActionAndReleasesOwnershipOnFailure() = runBlocking {
    val lane = ControlCodePhoneMutationLane()
    val acquired = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val action = async { lane.withOwnership { acquired.complete(Unit); release.await() } }
    withTimeout(1_000L) { acquired.await() }
    try { assertNull(withTimeout(1_000L) { lane.tryWithOwnership { fail("monitor interfered"); true } }) }
    finally { release.complete(Unit); action.await() }
    try { lane.tryWithOwnership<Boolean> { error("capture failure") }; fail("expected failure") }
    catch (_: IllegalStateException) { }
    assertEquals(true, lane.tryWithOwnership { true })
  }
}
