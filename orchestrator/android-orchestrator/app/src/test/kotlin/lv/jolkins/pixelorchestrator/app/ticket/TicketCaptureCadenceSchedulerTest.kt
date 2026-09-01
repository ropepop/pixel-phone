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
}
