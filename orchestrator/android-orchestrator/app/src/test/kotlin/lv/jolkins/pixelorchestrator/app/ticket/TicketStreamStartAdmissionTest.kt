package lv.jolkins.pixelorchestrator.app.ticket

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketStreamStartAdmissionTest {
  @Test
  fun preparingRootStreamCoalescesBeforeColdPreflightOnlyUntilVerified() {
    assertTrue(shouldCoalescePreparingTicketStreamBeforePreflight(true, true, false))
    assertFalse(shouldCoalescePreparingTicketStreamBeforePreflight(true, true, true))
    assertFalse(shouldCoalescePreparingTicketStreamBeforePreflight(true, false, false))
    assertFalse(shouldCoalescePreparingTicketStreamBeforePreflight(false, true, false))
  }

  @Test
  fun everyControlOwnershipSurfaceBlocksBackgroundStart() {
    assertTrue(ticketControlOwnershipActive("queued", false, false, "live"))
    assertTrue(ticketControlOwnershipActive("running", false, false, "live"))
    assertTrue(ticketControlOwnershipActive("succeeded", true, false, "live"))
    assertTrue(ticketControlOwnershipActive("succeeded", false, true, "live"))
    assertTrue(ticketControlOwnershipActive("succeeded", false, false, "control_active"))
    assertTrue(ticketControlOwnershipActive("succeeded", false, false, "control_transition"))
    assertTrue(ticketControlOwnershipActive("succeeded", false, false, "control_exit"))
    assertFalse(ticketControlOwnershipActive("succeeded", false, false, "live"))
  }

  @Test
  fun claimDuringColdPreflightBlocksTheFinalBackgroundCaptureAdmission() {
    val admission = TicketStreamStartAdmission()
    admission.claim()
    var captureStarted = false

    val result = admission.admit(
      controlCodeOwnsStart = false,
      additionalControlOwnershipActive = { false },
      blocked = { "blocked" },
      admitted = {
        captureStarted = true
        "started"
      }
    )

    assertEquals("blocked", result)
    assertFalse(captureStarted)
    assertEquals(0L, admission.release())
  }

  @Test
  fun admittedCaptureFinishesBeforeAConcurrentClaimCanComplete() {
    val admission = TicketStreamStartAdmission()
    val captureAdmissionEntered = CountDownLatch(1)
    val releaseCaptureAdmission = CountDownLatch(1)
    val claimAttempted = CountDownLatch(1)
    val claimCompleted = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val start = executor.submit<String> {
        admission.admit(
          controlCodeOwnsStart = false,
          additionalControlOwnershipActive = { false },
          blocked = { "blocked" },
          admitted = {
            captureAdmissionEntered.countDown()
            check(releaseCaptureAdmission.await(2, TimeUnit.SECONDS))
            "started"
          }
        )
      }
      assertTrue(captureAdmissionEntered.await(2, TimeUnit.SECONDS))
      val claim = executor.submit<Long> {
        claimAttempted.countDown()
        admission.claim().also { claimCompleted.countDown() }
      }

      assertTrue(claimAttempted.await(2, TimeUnit.SECONDS))
      assertFalse(claimCompleted.await(100, TimeUnit.MILLISECONDS))
      releaseCaptureAdmission.countDown()
      assertEquals("started", start.get(2, TimeUnit.SECONDS))
      assertEquals(1L, claim.get(2, TimeUnit.SECONDS))
      assertTrue(claimCompleted.await(2, TimeUnit.SECONDS))
    } finally {
      releaseCaptureAdmission.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun onlyFinalClaimReleaseWithNoClientsAndOpenSessionReschedulesDisconnect() {
    assertFalse(shouldScheduleDisconnectAfterFinalControlClaim(1L, 0, true))
    assertFalse(shouldScheduleDisconnectAfterFinalControlClaim(0L, 1, true))
    assertFalse(shouldScheduleDisconnectAfterFinalControlClaim(0L, 0, false))
    assertTrue(shouldScheduleDisconnectAfterFinalControlClaim(0L, 0, true))
  }

  @Test
  fun nestedClaimsDoNotExposeBackgroundStartAfterTheFirstRelease() {
    val admission = TicketStreamStartAdmission()
    assertEquals(1L, admission.claim())
    assertEquals(2L, admission.claim())
    assertEquals(1L, admission.release())

    val result = admission.admit(
      controlCodeOwnsStart = false,
      additionalControlOwnershipActive = { false },
      blocked = { "blocked" },
      admitted = { "started" }
    )

    assertEquals("blocked", result)
    assertEquals(0L, admission.release())
  }

  @Test
  fun controlOwnedColdStartRemainsAdmittedWhileClaimIsHeld() {
    val admission = TicketStreamStartAdmission()
    admission.claim()

    val result = admission.admit(
      controlCodeOwnsStart = true,
      additionalControlOwnershipActive = { true },
      blocked = { "blocked" },
      admitted = { "started" }
    )

    assertEquals("started", result)
  }
}
