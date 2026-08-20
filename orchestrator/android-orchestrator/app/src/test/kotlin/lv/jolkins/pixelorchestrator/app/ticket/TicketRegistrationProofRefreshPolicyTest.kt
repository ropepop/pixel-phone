package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketRegistrationProofRefreshPolicyTest {
  @Test
  fun oldStreamEpochRequiresARefresh() {
    assertTrue(
      requiresRefresh(
        proofEpoch = 10L,
        proofFrameSequence = 42L,
        currentEpoch = 11L,
        currentFrameSequence = 1L
      )
    )
  }

  @Test
  fun currentEpochWithAnAlreadyRenderedFrameRemainsUsable() {
    assertFalse(
      requiresRefresh(
        proofEpoch = 11L,
        proofFrameSequence = 42L,
        currentEpoch = 11L,
        currentFrameSequence = 43L
      )
    )
  }

  @Test
  fun aProofFromTheFutureCannotBePublishedAfterAnEpochReset() {
    assertTrue(
      requiresRefresh(
        proofEpoch = 11L,
        proofFrameSequence = 43L,
        currentEpoch = 11L,
        currentFrameSequence = 2L
      )
    )
  }

  @Test
  fun missingGeometryOrRevisionFailsClosed() {
    assertTrue(
      ticketRegistrationProofRequiresRefresh(
        status = "unactivated_ready",
        interactionRevision = "revision",
        proofRevision = "other_revision",
        proofEpoch = 11L,
        proofFrameSequence = 42L,
        currentEpoch = 11L,
        currentFrameSequence = 43L,
        hasSliderBounds = false
      )
    )
  }

  @Test
  fun liveStatusesAreNotReprovedByTheUnactivatedGate() {
    assertFalse(
      ticketRegistrationProofRequiresRefresh(
        status = "control_active",
        interactionRevision = "revision",
        proofRevision = "revision",
        proofEpoch = 10L,
        proofFrameSequence = 42L,
        currentEpoch = 11L,
        currentFrameSequence = 1L,
        hasSliderBounds = true
      )
    )
  }

  private fun requiresRefresh(
    proofEpoch: Long,
    proofFrameSequence: Long,
    currentEpoch: Long,
    currentFrameSequence: Long
  ): Boolean = ticketRegistrationProofRequiresRefresh(
    status = "unactivated_ready",
    interactionRevision = "revision",
    proofRevision = "revision",
    proofEpoch = proofEpoch,
    proofFrameSequence = proofFrameSequence,
    currentEpoch = currentEpoch,
    currentFrameSequence = currentFrameSequence,
    hasSliderBounds = true
  )
}
