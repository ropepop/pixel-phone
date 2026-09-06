package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketRegistrationFrameWatermarkTest {
  private val observation = TicketVisualActionObservation(
    probeId = 2L,
    state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
    atMillis = 10_000L,
    captureStartUs = 10_500_123L
  )

  private fun matches(
    now: Long,
    capturedAtUs: Long = observation.captureStartUs,
    proof: TicketVisualActionObservation = observation,
    expectedEpoch: Long = 7L,
    frameEpoch: Long = 7L
  ) = ticketVisualFrameMatchesRegistrationObservation(
    proof, expectedEpoch, frameEpoch, capturedAtUs, now, 3_000L
  )

  @Test
  fun alreadyEncodedProbeDoesNotWaitForTheNextOneSecondCapture() {
    // Probe requested at 10,000; the 1 Hz source captures at 10,500, encodes at
    // 11,100, and its classifier reply is consumed at 11,120. The picture's
    // deadline is 13,500 regardless of the wait before capture.
    val cachedSequence = 42L
    val oldWatermarkStartingSequence = cachedSequence
    assertFalse(cachedSequence > oldWatermarkStartingSequence)
    assertTrue(matches(now = 11_120L))
    assertFalse(matches(now = 11_251L, capturedAtUs = 11_500_123L))
  }

  @Test
  fun classifierReplyBeforeEncoderOutputWaitsOnlyForThatSameCapture() {
    assertFalse(matches(now = 11_000L, capturedAtUs = 9_500_123L))
    assertTrue(matches(now = 11_100L))
  }

  @Test
  fun captureAfterRequestButBeforeTheObservedCaptureIsNotTheProof() {
    assertFalse(matches(now = 11_100L, capturedAtUs = 10_100_123L))
    assertFalse(matches(now = 11_100L, capturedAtUs = observation.captureStartUs - 1L))
  }

  @Test
  fun laterSameEpochPictureMayBindButCannotRenewTheOriginalProofDeadline() {
    assertTrue(matches(now = 11_200L, capturedAtUs = 10_900_123L))
    assertTrue(matches(now = 13_500L))
    assertFalse(matches(now = 13_501L))
    assertFalse(matches(now = 13_501L, capturedAtUs = 13_400_123L))
  }

  @Test
  fun missingFutureOrReorderedTimestampsFailClosed() {
    assertFalse(matches(now = 11_100L, proof = observation.copy(captureStartUs = 0L)))
    assertFalse(matches(now = 11_100L, proof = observation.copy(atMillis = 0L)))
    assertFalse(matches(now = 11_100L, proof = observation.copy(probeId = 0L)))
    assertFalse(matches(now = 11_100L, proof = observation.copy(captureStartUs = -1L)))
    assertFalse(matches(now = 11_100L, proof = observation.copy(captureStartUs = Long.MAX_VALUE)))
    assertFalse(matches(now = 9_999L))
    assertFalse(matches(now = 11_100L, proof = observation.copy(captureStartUs = 9_999_999L)))
    assertFalse(matches(now = 11_100L, capturedAtUs = 11_101_000L))
  }

  @Test
  fun matchingCaptureTimeCannotCrossTheStreamEpochFence() {
    assertFalse(matches(now = 11_100L, frameEpoch = 8L))
    assertFalse(matches(now = 11_100L, expectedEpoch = 0L, frameEpoch = 0L))
    assertTrue(matches(now = 11_100L))
  }

  @Test
  fun cadenceWaitBeforeCaptureDoesNotConsumeThePicturesFreshnessBudget() {
    val delayedCapture = observation.copy(captureStartUs = 10_950_000L)
    // The probe waited 950 ms for the legal 1 Hz opportunity; its exact encoded
    // bitmap is now only 600 ms old. The old request-based 1250 ms gate rejected it.
    assertTrue(11_550L - delayedCapture.atMillis > 1_250L)
    assertTrue(matches(now = 11_550L, capturedAtUs = delayedCapture.captureStartUs,
      proof = delayedCapture))
    assertTrue(matches(now = 13_950L, capturedAtUs = delayedCapture.captureStartUs,
      proof = delayedCapture))
    assertFalse(matches(now = 13_951L, capturedAtUs = delayedCapture.captureStartUs,
      proof = delayedCapture))
    // A later output cannot make the classified picture younger before dispatch.
    assertFalse(matches(now = 13_951L, capturedAtUs = 12_100_000L, proof = delayedCapture))
  }
}
