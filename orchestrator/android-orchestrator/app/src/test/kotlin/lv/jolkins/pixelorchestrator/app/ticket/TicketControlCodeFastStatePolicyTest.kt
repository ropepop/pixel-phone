package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketControlCodeFastStatePolicyTest {
  @Test
  fun acceptsFreshMatchingLocalRevisionWithoutReparsingServerWatermark() {
    val decision = evaluate(
      revision = "phone:1000:12:40",
      snapshot = healthySnapshot(
        lastReadyRevision = "phone:1000:12:40",
        lastReadyAtMillis = NOW - 11_999L,
        lastFrameSentAtMillis = 0L
      )
    )

    assertTrue(decision.ready)
    assertEquals("local_revision_fresh", decision.reason)
    assertNull(decision.acceptedRevision)
  }

  @Test
  fun refreshesExpiredSameRevisionFromFreshLiveServerState() {
    val decision = evaluate(
      revision = "phone:1000:12:40",
      snapshot = healthySnapshot(
        lastReadyRevision = "phone:1000:12:40",
        lastReadyAtMillis = NOW - READY_TTL - 1L
      )
    )

    assertTrue(decision.ready)
    val accepted = requireNotNull(decision.acceptedRevision)
    assertEquals("phone:1000:12:40", accepted.revision)
    assertEquals(NOW, accepted.acceptedAtMillis)
    assertEquals("server_fast_revision_live_stream", accepted.reason)
    assertEquals(12L, accepted.readyStreamEpoch)
    assertEquals(40L, accepted.revisionFrameSequence)
    assertTrue(accepted.epochMatchesCurrentStream)
  }

  @Test
  fun rejectsMatchingEpochRevisionAheadOfThePhoneFrame() {
    val decision = evaluate(
      revision = "phone:1000:12:43",
      snapshot = healthySnapshot(frameSequence = 42L)
    )

    assertFalse(decision.ready)
    assertEquals("revision_ahead_of_live_frame", decision.reason)
    assertNull(decision.acceptedRevision)
  }

  @Test
  fun acceptsOldServerEpochAfterPhoneStreamRestart() {
    val decision = evaluate(
      revision = "phone:1000:5:900",
      snapshot = healthySnapshot(streamEpoch = 12L, frameSequence = 3L)
    )

    assertTrue(decision.ready)
    val accepted = requireNotNull(decision.acceptedRevision)
    assertFalse(accepted.epochMatchesCurrentStream)
    assertEquals("server_fast_revision_fresh_stream", accepted.reason)
    assertEquals(12L, accepted.readyStreamEpoch)
    assertEquals(5L, accepted.revisionStreamEpoch)
    assertEquals(900L, accepted.revisionFrameSequence)
  }

  @Test
  fun rejectsServerRevisionWhenLiveFrameIsStale() {
    val decision = evaluate(
      revision = "phone:1000:12:40",
      snapshot = healthySnapshot(lastFrameSentAtMillis = NOW - LIVE_FRAME_MAX_AGE - 1L)
    )

    assertFalse(decision.ready)
    assertEquals("live_frame_stale", decision.reason)
  }

  @Test
  fun rejectsServerRevisionOutsideHealthyLiveRootCapture() {
    val variants = listOf(
      healthySnapshot(streamActive = false),
      healthySnapshot(rootHardwareCaptureActive = false),
      healthySnapshot(hardwareCaptureVerified = false),
      healthySnapshot(sessionLive = false),
      healthySnapshot(controlCodeModeActive = true)
    )

    variants.forEach { snapshot ->
      assertFalse(evaluate("phone:1000:12:40", snapshot).ready)
    }
  }

  @Test
  fun rejectsMissingAndMalformedRevisions() {
    val malformed = listOf("", "phone:1000", "phone:1000:nope:40", "phone:1000:12:nope")

    malformed.forEach { revision ->
      assertFalse(evaluate(revision, healthySnapshot()).ready)
    }
  }

  private fun evaluate(
    revision: String,
    snapshot: TicketControlCodeFastStateSnapshot
  ): TicketControlCodeFastStateDecision {
    return TicketControlCodeFastStatePolicy.evaluate(
      expectedRevision = revision,
      nowMillis = NOW,
      readyTtlMillis = READY_TTL,
      liveFrameMaxAgeMillis = LIVE_FRAME_MAX_AGE,
      snapshot = snapshot
    )
  }

  private fun healthySnapshot(
    streamActive: Boolean = true,
    rootHardwareCaptureActive: Boolean = true,
    hardwareCaptureVerified: Boolean = true,
    sessionLive: Boolean = true,
    controlCodeModeActive: Boolean = false,
    lastFrameSentAtMillis: Long = NOW - 100L,
    streamEpoch: Long = 12L,
    frameSequence: Long = 42L,
    lastReadyRevision: String = "",
    lastReadyAtMillis: Long = 0L
  ): TicketControlCodeFastStateSnapshot {
    return TicketControlCodeFastStateSnapshot(
      streamActive = streamActive,
      rootHardwareCaptureActive = rootHardwareCaptureActive,
      hardwareCaptureVerified = hardwareCaptureVerified,
      sessionLive = sessionLive,
      controlCodeModeActive = controlCodeModeActive,
      lastFrameSentAtMillis = lastFrameSentAtMillis,
      streamEpoch = streamEpoch,
      frameSequence = frameSequence,
      lastReadyRevision = lastReadyRevision,
      lastReadyAtMillis = lastReadyAtMillis
    )
  }

  private companion object {
    const val NOW = 50_000L
    const val READY_TTL = 12_000L
    const val LIVE_FRAME_MAX_AGE = 2_000L
  }
}
