package lv.jolkins.pixelorchestrator.app.ticket

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TicketNewestFrameHandoffTest {
  @Test fun warmWithoutViewerParksButCommandsUseTheSameCaptureCadence() {
    val cadence = TicketCaptureCadenceScheduler(100)
    cadence.parkOrdinaryCapture()
    assertEquals(TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS, cadence.waitMillis(100, false))
    assertEquals(0L, cadence.waitMillis(100, true))
    cadence.beginCapture(100, true)
    assertEquals(900L, cadence.waitMillis(200, true))
    assertTrue(cadence.enableDemandGateAndLatchOrdinaryCapture(2_000, 200))
    assertEquals(900L, cadence.waitMillis(200, false))
    cadence.beginCapture(1_100, false)
    assertEquals(TicketCaptureCadenceScheduler.WAIT_UNTIL_SIGNAL_MILLIS, cadence.waitMillis(2_100, false))
  }

  private class Picture : AutoCloseable {
    var closed = 0
    override fun close() { closed++ }
  }

  @Test fun stalledEncoderKeepsItsPictureWhileCaptureReplacesOnlyPending() {
    val handoff = TicketNewestFrameHandoff<Picture>()
    val inFlight = Picture()
    handoff.offer(inFlight)
    assertSame(inFlight, handoff.take())
    val superseded = Picture()
    handoff.offer(superseded)
    val newest = Picture()
    handoff.offer(newest)
    assertEquals(0, inFlight.closed)
    assertEquals(1, superseded.closed)
    assertSame(newest, handoff.take())
    handoff.close()
    assertEquals(0, newest.closed)
    inFlight.close()
    newest.close()
    assertEquals(1, inFlight.closed)
    assertEquals(1, newest.closed)
  }

  @Test fun stopReleasesPendingAndRejectsLateCaptureWithoutTouchingInflight() {
    val handoff = TicketNewestFrameHandoff<Picture>()
    val pending = Picture()
    handoff.offer(pending)
    handoff.close()
    assertEquals(1, pending.closed)
    val late = Picture()
    handoff.offer(late)
    assertEquals(1, late.closed)
    assertNull(handoff.take())
    handoff.close()
    assertEquals(1, pending.closed)
  }

  @Test fun finiteCaptureDrainsItsLastPictureBeforeEndOfInput() {
    val handoff = TicketNewestFrameHandoff<Picture>()
    val last = Picture()
    handoff.offer(last)
    handoff.finish()
    assertSame(last, handoff.take())
    assertNull(handoff.take())
    handoff.close()
    assertEquals(0, last.closed)
    last.close()
  }
  @Test fun recognitionAndEncoderReleaseTheirSharedCaptureInEitherOrder() {
    repeat(2) { firstReader ->
      var releases = 0
      val lifetime = TicketSharedFrameLifetime { releases++ }
      lifetime.retain()
      val worker = Executors.newSingleThreadExecutor()
      val firstDone = CountDownLatch(1)
      val finish = CountDownLatch(1)
      try {
        val work = worker.submit {
          if (firstReader == 0) lifetime.close()
          firstDone.countDown()
          check(finish.await(2, TimeUnit.SECONDS))
          if (firstReader == 1) lifetime.close()
        }
        assertTrue(firstDone.await(2, TimeUnit.SECONDS))
        assertEquals(0, releases)
        lifetime.close()
        assertEquals(if (firstReader == 0) 1 else 0, releases)
        finish.countDown()
        work.get(2, TimeUnit.SECONDS)
        assertEquals(1, releases)
        assertThrows(IllegalStateException::class.java) { lifetime.retain() }
      } finally {
        finish.countDown()
        worker.shutdownNow()
      }
    }
  }
}
