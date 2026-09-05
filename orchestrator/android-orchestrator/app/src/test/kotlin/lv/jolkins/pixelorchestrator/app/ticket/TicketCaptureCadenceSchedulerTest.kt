package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCaptureCadenceSchedulerTest {
  @Test
  fun fixedCadenceIsExactlyOneFramePerSecond() {
    val scheduler = TicketCaptureCadenceScheduler(0L)

    assertEquals(1, TicketCaptureCadenceScheduler.FIXED_FPS)
    assertEquals(1_000L, TicketCaptureCadenceScheduler.INTERVAL_MILLIS)
    assertEquals(1_000L, scheduler.intervalMillis())
  }

  @Test
  fun onlyOneCaptureIsGrantedAndExpiredTicksAreSkipped() {
    val scheduler = TicketCaptureCadenceScheduler(1_000L)

    assertEquals(0L, scheduler.waitMillis(1_000L))
    scheduler.beginCapture(1_000L)

    // Work finished 1.25 seconds later. The 2,000 deadline expired, so the
    // scheduler grants one capture and advances to 3,000 ms without catch-up.
    val decision = scheduler.beginCapture(2_250L)
    assertEquals(250L, decision.latenessMillis)
    assertEquals(1L, decision.skippedTicks)
    assertEquals(1L, scheduler.skippedTicks())
    assertEquals(1L, scheduler.deadlineMisses())
    assertEquals(3_000L, 2_250L + scheduler.waitMillis(2_250L))
  }

  @Test
  fun requestAfterPeriodicFrameIsImmediateThenStartsANewOneSecondPeriod() {
    val scheduler = TicketCaptureCadenceScheduler(10_000L)
    assertFalse(scheduler.beginCapture(10_000L).immediate)
    assertEquals(1_000L, scheduler.waitMillis(10_000L))

    assertTrue(scheduler.requestImmediateCapture(10_100L))
    assertFalse(scheduler.requestImmediateCapture(10_101L))
    assertTrue(scheduler.hasImmediateCapturePending())
    assertEquals(0L, scheduler.waitMillis(10_101L))

    // The first request does not wait for the old 11,000 ms periodic deadline.
    assertTrue(scheduler.beginCapture(10_101L).immediate)
    assertFalse(scheduler.hasImmediateCapturePending())
    // Its presentation starts a new period ending at 11,101 ms.
    assertEquals(1_000L, scheduler.waitMillis(10_101L))

    // Further requests in that period coalesce onto the refresh and add no frame.
    assertFalse(scheduler.requestImmediateCapture(10_150L))
    assertFalse(scheduler.hasImmediateCapturePending())
    assertEquals(951L, scheduler.waitMillis(10_150L))
    assertTrue(scheduler.requestImmediateCapture(11_101L))
    assertTrue(scheduler.beginCapture(11_101L).immediate)
    assertEquals(1_000L, scheduler.waitMillis(11_101L))
  }

  @Test
  fun periodicCaptureAfterImmediateStartsAtTheNewOneSecondDeadline() {
    val scheduler = TicketCaptureCadenceScheduler(20_000L)
    assertFalse(scheduler.beginCapture(20_000L).immediate)
    assertTrue(scheduler.requestImmediateCapture(20_050L))
    assertEquals(0L, scheduler.waitMillis(20_050L))
    assertTrue(scheduler.beginCapture(20_050L).immediate)
    assertEquals(1_000L, scheduler.waitMillis(20_050L))
    assertFalse(scheduler.beginCapture(21_050L).immediate)
  }

  @Test
  fun steadyPeriodRestartsFromFirstExposedPrimerPicture() {
    val scheduler = TicketCaptureCadenceScheduler(30_000L)
    scheduler.beginCapture(30_000L)

    scheduler.restartPeriodFrom(30_275L)

    assertEquals(1_000L, scheduler.waitMillis(30_275L))
    assertFalse(scheduler.requestImmediateCapture(30_300L))
    assertFalse(scheduler.hasImmediateCapturePending())
    assertEquals(975L, scheduler.waitMillis(30_300L))
    assertTrue(scheduler.requestImmediateCapture(31_275L))
    assertTrue(scheduler.beginCapture(31_275L).immediate)
    assertEquals(1_000L, scheduler.waitMillis(31_275L))
  }

  @Test
  fun legacyRelayLeavesOrdinaryCaptureContinuouslyScheduled() {
    val scheduler = TicketCaptureCadenceScheduler(1_000L)

    assertFalse(scheduler.ordinaryCaptureDemandGated())
    assertFalse(scheduler.beginCapture(1_000L).ordinaryDemandOpportunityConsumed)
    assertEquals(0L, scheduler.waitMillis(2_000L))
    assertFalse(scheduler.beginCapture(2_000L).ordinaryDemandOpportunityConsumed)
  }

  @Test
  fun firstDemandEnablesGateAndGrantsExactlyOneOrdinaryOpportunity() {
    val scheduler = TicketCaptureCadenceScheduler(1_000L)
    scheduler.beginCapture(1_000L)

    assertTrue(scheduler.enableDemandGateAndLatchOrdinaryCapture(3_600L, 1_100L))
    assertTrue(scheduler.ordinaryCaptureDemandGated())
    assertTrue(scheduler.ordinaryCaptureOpportunityPending(1_100L))
    assertEquals(900L, scheduler.waitMillis(1_100L, false))

    val demanded = scheduler.beginCapture(2_000L, false)
    assertTrue(demanded.ordinaryDemandOpportunityConsumed)
    assertFalse(scheduler.ordinaryCaptureOpportunityPending(2_000L))
    assertEquals(
      TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS,
      scheduler.waitMillis(3_000L, false)
    )
  }

  @Test
  fun repeatedDemandAggregatesIntoOneLatchedOpportunity() {
    val scheduler = TicketCaptureCadenceScheduler(1_000L)
    scheduler.beginCapture(1_000L)

    assertTrue(scheduler.enableDemandGateAndLatchOrdinaryCapture(3_600L, 1_100L))
    assertFalse(scheduler.enableDemandGateAndLatchOrdinaryCapture(3_700L, 1_200L))
    assertTrue(scheduler.beginCapture(2_000L, false).ordinaryDemandOpportunityConsumed)
    assertEquals(
      TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS,
      scheduler.waitMillis(3_000L, false)
    )
  }

  @Test
  fun expiredOpportunityParksWhileTheSameSchedulerAndCodecCanStayWarm() {
    val scheduler = TicketCaptureCadenceScheduler(1_000L)
    scheduler.beginCapture(1_000L)
    scheduler.enableDemandGateAndLatchOrdinaryCapture(1_500L, 1_100L)

    assertFalse(scheduler.ordinaryCaptureOpportunityPending(1_501L))
    assertTrue(scheduler.ordinaryCaptureDemandGated())
    assertEquals(
      TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS,
      scheduler.waitMillis(2_000L, false)
    )
    assertTrue(scheduler.enableDemandGateAndLatchOrdinaryCapture(4_500L, 2_000L))
    assertTrue(scheduler.beginCapture(2_000L, false).ordinaryDemandOpportunityConsumed)
  }

  @Test
  fun proofBypassNeedsNoDemandAndCoalescesAnyAlreadyLatchedOpportunity() {
    val scheduler = TicketCaptureCadenceScheduler(10_000L)
    scheduler.beginCapture(10_000L)
    scheduler.enableDemandGateAndLatchOrdinaryCapture(13_500L, 10_100L)

    assertEquals(900L, scheduler.waitMillis(10_100L, true))
    val proof = scheduler.beginCapture(11_000L, true)
    assertTrue(proof.ordinaryDemandOpportunityConsumed)
    assertFalse(scheduler.ordinaryCaptureOpportunityPending(11_000L))
    assertEquals(
      TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS,
      scheduler.waitMillis(12_000L, false)
    )

    // A later proof still runs at cadence without viewer credit.
    assertEquals(0L, scheduler.waitMillis(12_000L, true))
    assertFalse(scheduler.beginCapture(12_000L, true).ordinaryDemandOpportunityConsumed)
  }

  @Test
  fun demandArrivingDuringProofEncodingIsCoalescedWhenThatPictureIsEmitted() {
    val scheduler = TicketCaptureCadenceScheduler(10_000L)
    scheduler.beginCapture(10_000L, true)

    // The relay demand can arrive after capture admission while the picture is still encoding.
    assertTrue(scheduler.enableDemandGateAndLatchOrdinaryCapture(12_500L, 10_050L))
    assertTrue(scheduler.ordinaryCaptureOpportunityPending(10_050L))

    assertTrue(scheduler.notePictureEmitted(10_100L))
    assertFalse(scheduler.ordinaryCaptureOpportunityPending(10_100L))
    assertEquals(
      TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS,
      scheduler.waitMillis(11_000L, false)
    )
  }

  @Test
  fun coalescedProofInsideBlockedPeriodCanWakeParkedGateForNextDeadline() {
    val scheduler = TicketCaptureCadenceScheduler(10_000L)
    scheduler.beginCapture(10_000L)
    scheduler.enableDemandGateAndLatchOrdinaryCapture(12_500L, 10_010L)
    assertTrue(scheduler.requestImmediateCapture(10_020L))
    assertTrue(scheduler.beginCapture(10_020L, true).ordinaryDemandOpportunityConsumed)
    assertEquals(
      TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS,
      scheduler.waitMillis(10_100L, false)
    )

    assertFalse(scheduler.requestImmediateCapture(10_100L))
    assertEquals(920L, scheduler.waitMillis(10_100L, true))
    assertFalse(scheduler.beginCapture(11_020L, true).immediate)
  }
}
