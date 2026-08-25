package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCaptureCadenceSchedulerTest {
  @Test
  fun onlyOneCaptureIsGrantedAndExpiredTicksAreSkipped() {
    val scheduler = TicketCaptureCadenceScheduler(
      TicketCaptureCadenceScheduler.ACTIVE_FPS,
      1_000L
    )

    assertEquals(0L, scheduler.waitMillis(1_000L))
    scheduler.beginCapture(1_000L)

    // Work finished 250 ms later. The 1,100 and 1,200 deadlines expired, so
    // the scheduler grants this one capture and advances to 1,300 ms.
    val decision = scheduler.beginCapture(1_250L)
    assertEquals(150L, decision.latenessMillis)
    assertEquals(2L, decision.skippedTicks)
    assertEquals(2L, scheduler.skippedTicks())
    assertEquals(1L, scheduler.deadlineMisses())
    assertEquals(1_300L, 1_250L + scheduler.waitMillis(1_250L))
  }

  @Test
  fun cadenceChangeIsImmediateAndResetsTheAbsoluteDeadline() {
    val scheduler = TicketCaptureCadenceScheduler(
      TicketCaptureCadenceScheduler.STATIC_FPS,
      5_000L
    )
    scheduler.beginCapture(5_000L)
    assertEquals(1_000L, scheduler.waitMillis(5_000L))

    assertTrue(scheduler.setTargetFps(TicketCaptureCadenceScheduler.MODERATE_FPS, 5_050L))
    assertEquals(5, scheduler.targetFps())
    assertEquals(0L, scheduler.waitMillis(5_050L))
    assertEquals(1L, scheduler.cadenceChanges())
    assertFalse(scheduler.setTargetFps(2, 5_050L))
    assertEquals(5, scheduler.targetFps())
  }

  @Test
  fun immediateCaptureRequestCoalescesAndResumesTheSelectedCadence() {
    val scheduler = TicketCaptureCadenceScheduler(
      TicketCaptureCadenceScheduler.STATIC_FPS,
      10_000L
    )
    assertFalse(scheduler.beginCapture(10_000L).immediate)
    assertEquals(1_000L, scheduler.waitMillis(10_000L))

    assertTrue(scheduler.requestImmediateCapture(10_100L))
    assertFalse(scheduler.requestImmediateCapture(10_101L))
    assertTrue(scheduler.hasImmediateCapturePending())
    assertEquals(0L, scheduler.waitMillis(10_101L))

    assertTrue(scheduler.beginCapture(10_101L).immediate)
    assertFalse(scheduler.hasImmediateCapturePending())
    assertEquals(TicketCaptureCadenceScheduler.STATIC_FPS, scheduler.targetFps())
    assertEquals(999L, scheduler.waitMillis(10_101L))

    assertTrue(scheduler.requestImmediateCapture(10_150L))
    assertEquals(0L, scheduler.waitMillis(10_150L))
    assertTrue(scheduler.beginCapture(10_150L).immediate)
    assertFalse(scheduler.beginCapture(11_150L).immediate)
  }

  @Test
  fun cadenceTransitionIsDueNowButIsNotAnExplicitImmediateCapture() {
    val scheduler = TicketCaptureCadenceScheduler(
      TicketCaptureCadenceScheduler.MODERATE_FPS,
      20_000L
    )
    assertFalse(scheduler.beginCapture(20_000L).immediate)

    assertTrue(scheduler.setTargetFps(TicketCaptureCadenceScheduler.STATIC_FPS, 20_050L))
    assertEquals(0L, scheduler.waitMillis(20_050L))
    assertFalse(scheduler.beginCapture(20_050L).immediate)
  }

  @Test(expected = IllegalArgumentException::class)
  fun constructorRejectsUnsupportedCadence() {
    TicketCaptureCadenceScheduler(2, 0L)
  }
}
