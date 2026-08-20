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

  @Test(expected = IllegalArgumentException::class)
  fun constructorRejectsUnsupportedCadence() {
    TicketCaptureCadenceScheduler(2, 0L)
  }
}
