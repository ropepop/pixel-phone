package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketEncoderOutputLivenessTest {
  @Test
  fun secondSpacedScheduledStaticMissRequestsExactlyOneRecoveryUntilOutputResumes() {
    val liveness = TicketEncoderOutputLiveness()

    assertFalse(note(liveness, atMillis = 1_000L))
    assertTrue(note(liveness, atMillis = 2_000L))
    assertFalse(note(liveness, atMillis = 3_000L))
    assertFalse(note(liveness, atMillis = 4_000L))

    assertFalse(note(liveness, outputs = 1, atMillis = 4_100L))
    assertFalse(note(liveness, atMillis = 5_000L))
    assertTrue(note(liveness, atMillis = 6_000L))
  }

  @Test
  fun startupAndHigherCadenceMissesNeverRequestStaticRecovery() {
    val liveness = TicketEncoderOutputLiveness()

    repeat(3) { index ->
      val atMillis = index * 1_000L
      assertFalse(note(liveness, steadyState = false, atMillis = atMillis))
      assertFalse(note(liveness, targetFps = TicketCaptureCadenceScheduler.MODERATE_FPS, atMillis = atMillis + 100L))
      assertFalse(note(liveness, targetFps = TicketCaptureCadenceScheduler.ACTIVE_FPS, atMillis = atMillis + 200L))
    }

    assertFalse(
      note(
        liveness,
        targetFps = TicketCaptureCadenceScheduler.ACTIVE_FPS,
        outputs = 1,
        atMillis = 3_000L
      )
    )
    assertFalse(note(liveness, atMillis = 4_000L))
    assertTrue(note(liveness, atMillis = 5_000L))
  }

  @Test
  fun armedRecoverySurvivesCadenceAndFragmentProgressUntilCompleteMediaOutput() {
    val liveness = TicketEncoderOutputLiveness()

    assertFalse(note(liveness, atMillis = 1_000L))
    assertTrue(note(liveness, atMillis = 2_000L))
    assertFalse(note(liveness, madeCodecProgress = true, scheduledCadenceDrain = false, atMillis = 2_100L))
    assertFalse(note(liveness, targetFps = TicketCaptureCadenceScheduler.MODERATE_FPS, atMillis = 2_200L))
    assertFalse(note(liveness, targetFps = TicketCaptureCadenceScheduler.ACTIVE_FPS, atMillis = 2_300L))
    assertFalse(note(liveness, steadyState = false, atMillis = 2_400L))
    assertFalse(note(liveness, atMillis = 3_000L))

    assertFalse(
      note(
        liveness,
        targetFps = TicketCaptureCadenceScheduler.MODERATE_FPS,
        outputs = 1,
        atMillis = 3_100L
      )
    )
    assertFalse(note(liveness, atMillis = 4_000L))
    assertTrue(note(liveness, atMillis = 5_000L))
  }

  @Test
  fun immediateTransitionAndCloselySpacedDrainsCannotCreateDroughtEvidence() {
    val liveness = TicketEncoderOutputLiveness()

    repeat(4) { index ->
      assertFalse(
        note(
          liveness,
          scheduledCadenceDrain = false,
          atMillis = 100L + index * 20L
        )
      )
    }
    assertFalse(note(liveness, atMillis = 1_000L))
    repeat(4) { index ->
      assertFalse(
        note(
          liveness,
          scheduledCadenceDrain = false,
          atMillis = 1_010L + index * 20L
        )
      )
    }
    assertFalse(note(liveness, atMillis = 1_100L))
    assertTrue(note(liveness, atMillis = 2_000L))

    assertFalse(
      note(
        liveness,
        scheduledCadenceDrain = false,
        outputs = 1,
        atMillis = 2_100L
      )
    )
    assertFalse(note(liveness, atMillis = 3_000L))
    assertFalse(note(liveness, atMillis = 3_100L))
    assertTrue(note(liveness, atMillis = 4_000L))
  }

  @Test
  fun fragmentAndCodecConfigurationProgressCannotMaskAnOverdueCompleteFrame() {
    val liveness = TicketEncoderOutputLiveness()

    assertFalse(note(liveness, outputs = 1, atMillis = 1_000L))
    assertFalse(note(liveness, atMillis = 1_500L))
    assertFalse(
      note(
        liveness,
        scheduledCadenceDrain = false,
        madeCodecProgress = true,
        atMillis = 1_600L
      )
    )
    assertFalse(note(liveness, atMillis = 2_000L))
    assertFalse(note(liveness, madeCodecProgress = true, atMillis = 2_400L))
    assertTrue(note(liveness, madeCodecProgress = true, atMillis = 2_800L))

    assertFalse(note(liveness, madeCodecProgress = true, atMillis = 2_900L))
    assertFalse(note(liveness, atMillis = 3_000L))
    assertFalse(note(liveness, outputs = 1, atMillis = 3_100L))
    assertFalse(note(liveness, atMillis = 4_000L))
    assertTrue(note(liveness, atMillis = 5_000L))
  }

  private fun note(
    liveness: TicketEncoderOutputLiveness,
    targetFps: Int = TicketCaptureCadenceScheduler.STATIC_FPS,
    steadyState: Boolean = true,
    scheduledCadenceDrain: Boolean = true,
    madeCodecProgress: Boolean = false,
    outputs: Int = 0,
    atMillis: Long
  ): Boolean = liveness.noteDrain(
    targetFps,
    steadyState,
    scheduledCadenceDrain,
    madeCodecProgress,
    outputs,
    atMillis
  )
}
