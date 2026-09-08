package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketStreamStartupRecoveryPolicyTest {
  private fun canContinue(expected: Boolean, demandAge: Long?, active: Boolean = true): Boolean =
    TicketStreamStartupRecoveryPolicy.canContinueCurrentEncoder(
      encoderActive = active,
      encoderState = if (active) "active" else "idle",
      ordinaryCaptureDemandGated = true,
      captureFrameExpected = expected,
      captureFrameExpectedAgoMillis = demandAge,
      firstUsefulFramePending = false,
      frameAgeMillis = 1_400_000,
      encoderStartAgeMillis = 1_500_000,
      liveFrameMaxAgeMillis = 3000,
      startupWaitMillis = 5000
    )

  @Test fun parkedEncoderSurvivesAnExpiredPicture() {
    assertTrue(canContinue(false, null))
  }

  @Test fun returningViewerGetsAFullDemandWindowBeforeRecovery() {
    assertTrue(canContinue(true, 0))
    assertTrue(canContinue(true, 4999))
    assertFalse(canContinue(true, 5000))
  }

  @Test fun DeadEncoderCannotBorrowTheDemandGrace() {
    assertFalse(canContinue(true, 0, active = false))
  }
}
