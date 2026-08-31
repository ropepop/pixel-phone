package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketVideoClientDeliveryStateTest {
  @Test
  fun configurationMustFinishBeforeMediaCanStart() {
    val state = state()

    val blocked = state.offer(frame(1), nowMillis = 1L)

    assertEquals(TicketVideoClientDeliveryState.DROP_CONFIG_NOT_READY, blocked.dropReason)
    assertEquals(1, blocked.droppedFrames)
    assertFalse(blocked.requestImmediateRefresh)
    assertTrue(state.markConfigReady())
    assertFalse(state.canAcceptConfig())
    assertEquals(1L, state.offer(frame(1), nowMillis = 2L).frameToWrite?.sequence)
  }

  @Test
  fun blockedWriterKeepsOnlyTheNewestPendingIndependentFrame() {
    val state = readyState()
    val first = state.offer(frame(10), nowMillis = 1L)

    val pending = state.offer(frame(11), nowMillis = 10L)
    val replacement = state.offer(frame(12), nowMillis = 20L)

    assertEquals(10L, first.frameToWrite?.sequence)
    assertEquals(0, pending.droppedFrames)
    assertEquals(1, replacement.droppedFrames)
    assertEquals(TicketVideoClientDeliveryState.DROP_PENDING_REPLACED, replacement.dropReason)
    val blocked = state.snapshot()
    assertTrue(blocked.writeInFlight)
    assertEquals(1, blocked.pendingFrames)
    assertEquals(12L, blocked.pendingSequence)
    assertEquals(12L, blocked.latestAcceptedSequence)

    val next = state.completeWrite(first.writeToken, nowMillis = 100L, succeeded = true)
    assertEquals(12L, next.frameToWrite?.sequence)
    assertEquals(0, state.snapshot().pendingFrames)
    state.completeWrite(next.writeToken, nowMillis = 101L, succeeded = true)
    assertFalse(state.snapshot().writeInFlight)
  }

  @Test
  fun sequenceGapsAreAcceptedBecauseEveryFrameIsIndependent() {
    val state = readyState()
    state.offer(frame(10), nowMillis = 1L)

    val gap = state.offer(frame(20), nowMillis = 2L)

    assertNull(gap.dropReason)
    assertFalse(gap.requestImmediateRefresh)
    assertEquals(20L, state.snapshot().pendingSequence)
  }

  @Test
  fun unexpectedDeltaIsRejectedAndRequestsOneCoalescedRefresh() {
    val state = readyState()
    state.offer(frame(10), nowMillis = 1L)

    val delta = state.offer(frame(11, keyFrame = false), nowMillis = 2L)

    assertEquals(TicketVideoClientDeliveryState.DROP_UNEXPECTED_DELTA, delta.dropReason)
    assertEquals(1, delta.droppedFrames)
    assertTrue(delta.requestImmediateRefresh)
    assertEquals(0, state.snapshot().pendingFrames)
    assertEquals(10L, state.snapshot().latestAcceptedSequence)

    val recovered = state.offer(frame(11), nowMillis = 3L)
    assertNull(recovered.dropReason)
    assertEquals(11L, state.snapshot().pendingSequence)
  }

  @Test
  fun staleOrDuplicateIndependentFramesCannotReplaceTheNewestPendingFrame() {
    val state = readyState()
    state.offer(frame(10), nowMillis = 1L)
    state.offer(frame(12), nowMillis = 2L)

    val duplicate = state.offer(frame(12), nowMillis = 3L)
    val stale = state.offer(frame(11), nowMillis = 4L)

    assertEquals(TicketVideoClientDeliveryState.DROP_STALE_FRAME, duplicate.dropReason)
    assertEquals(TicketVideoClientDeliveryState.DROP_STALE_FRAME, stale.dropReason)
    assertFalse(duplicate.requestImmediateRefresh)
    assertFalse(stale.requestImmediateRefresh)
    assertEquals(12L, state.snapshot().pendingSequence)
  }

  @Test
  fun wrongEpochIsRejectedWithoutChangingCurrentGeneration() {
    val state = readyState(expectedEpoch = 7L)

    val wrongEpoch = state.offer(frame(1, epoch = 8L), nowMillis = 1L)

    assertEquals(TicketVideoClientDeliveryState.DROP_EPOCH_MISMATCH, wrongEpoch.dropReason)
    assertEquals(1, wrongEpoch.droppedFrames)
    assertFalse(wrongEpoch.requestImmediateRefresh)
    assertFalse(state.snapshot().writeInFlight)
  }

  @Test
  fun oversizedIndependentFrameIsRejectedAndRequestsRefresh() {
    val state = readyState(maxFrameBytes = 1)

    val oversized = state.offer(frame(1, bytes = byteArrayOf(1, 2)), nowMillis = 1L)

    assertEquals(TicketVideoClientDeliveryState.DROP_FRAME_TOO_LARGE, oversized.dropReason)
    assertEquals(1, oversized.droppedFrames)
    assertTrue(oversized.requestImmediateRefresh)
    assertFalse(state.snapshot().writeInFlight)
    assertEquals(0L, state.snapshot().latestAcceptedSequence)
  }

  @Test
  fun aLaterOfferClosesOnlyTheClientWhoseWriteCrossedTheHardDeadline() {
    val state = readyState(slowCloseMillis = 250L)
    state.offer(frame(1), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)

    val close = state.offer(frame(3), nowMillis = 251L)

    assertTrue(close.closeSlowClient)
    assertEquals(TicketVideoClientDeliveryState.DROP_SLOW_CLIENT, close.dropReason)
    assertEquals(250L, close.blockedMillis)
    assertEquals(3, close.droppedFrames)
    assertTrue(state.snapshot().closed)
    assertEquals(0, state.snapshot().pendingFrames)
  }

  @Test
  fun successfulWritePastDeadlineDropsOnlyThePendingFrame() {
    val state = readyState(slowCloseMillis = 250L)
    val first = state.offer(frame(1), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)

    val close = state.completeWrite(first.writeToken, nowMillis = 251L, succeeded = true)

    assertTrue(close.closeSlowClient)
    assertEquals(1, close.droppedFrames)
    assertEquals(250L, close.blockedMillis)
    assertTrue(state.snapshot().closed)
  }

  @Test
  fun failedWriteClosesAndDropsTheInFlightAndPendingFrames() {
    val state = readyState()
    val first = state.offer(frame(1), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)

    val failed = state.completeWrite(first.writeToken, nowMillis = 100L, succeeded = false)

    assertEquals(TicketVideoClientDeliveryState.DROP_WRITE_FAILED, failed.dropReason)
    assertEquals(2, failed.droppedFrames)
    assertTrue(state.snapshot().closed)
  }

  @Test
  fun timeoutClosesAndDropsTheInFlightAndPendingFrames() {
    val state = readyState(slowCloseMillis = 250L)
    val first = state.offer(frame(1), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)

    val early = state.timeoutWrite(first.writeToken, nowMillis = 250L)
    val expired = state.timeoutWrite(first.writeToken, nowMillis = 251L)

    assertFalse(early.closeSlowClient)
    assertTrue(expired.closeSlowClient)
    assertEquals(2, expired.droppedFrames)
    assertEquals(250L, expired.blockedMillis)
  }

  @Test
  fun staleWriteTokenCannotCompleteTheNewerPendingWrite() {
    val state = readyState()
    val first = state.offer(frame(1), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)
    val second = state.completeWrite(first.writeToken, nowMillis = 10L, succeeded = true)

    val staleCompletion = state.completeWrite(first.writeToken, nowMillis = 11L, succeeded = true)

    assertNull(staleCompletion.frameToWrite)
    assertEquals(2L, state.snapshot().inFlightSequence)
    assertEquals(second.writeToken, state.snapshot().inFlightWriteToken)
  }

  @Test
  fun closeInvalidatesTheWriteTokenAndClearsThePendingFrame() {
    val state = readyState()
    val first = state.offer(frame(1), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)

    state.close()

    assertFalse(state.canWrite(first.writeToken))
    assertTrue(state.snapshot().closed)
    assertEquals(0, state.snapshot().pendingFrames)
    assertEquals(0L, state.snapshot().latestAcceptedSequence)
  }

  private fun readyState(
    expectedEpoch: Long = 7L,
    maxFrameBytes: Int = 1024,
    slowCloseMillis: Long = 250L
  ): TicketVideoClientDeliveryState {
    return state(expectedEpoch, maxFrameBytes, slowCloseMillis).also {
      assertTrue(it.markConfigReady())
    }
  }

  private fun state(
    expectedEpoch: Long = 7L,
    maxFrameBytes: Int = 1024,
    slowCloseMillis: Long = 250L
  ): TicketVideoClientDeliveryState {
    return TicketVideoClientDeliveryState(
      expectedEpoch = expectedEpoch,
      maxFrameBytes = maxFrameBytes,
      slowCloseMillis = slowCloseMillis
    )
  }

  private fun frame(
    sequence: Long,
    keyFrame: Boolean = true,
    epoch: Long = 7L,
    bytes: ByteArray = byteArrayOf(sequence.toByte())
  ): TicketVideoDeliveryFrame {
    return TicketVideoDeliveryFrame(
      bytes = bytes,
      keyFrame = keyFrame,
      epoch = epoch,
      sequence = sequence
    )
  }
}
