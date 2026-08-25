package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketVideoClientDeliveryStateTest {
  @Test
  fun blockedWriterDrainsNormalBurstInContiguousSequenceOrder() {
    val state = state()
    assertTrue(state.markConfigReady())

    val first = state.offer(frame(10, keyFrame = true), nowMillis = 1L)
    val second = state.offer(frame(11), nowMillis = 10L)
    val third = state.offer(frame(12), nowMillis = 20L)

    assertEquals(10L, first.frameToWrite?.sequence)
    assertNull(second.frameToWrite)
    assertNull(third.frameToWrite)
    assertEquals(0, second.droppedFrames + third.droppedFrames)

    val afterFirst = complete(state, nowMillis = 30L)
    val afterSecond = complete(state, nowMillis = 40L)
    val afterThird = complete(state, nowMillis = 50L)

    assertEquals(listOf(10L, 11L, 12L), listOfNotNull(
      first.frameToWrite?.sequence,
      afterFirst.frameToWrite?.sequence,
      afterSecond.frameToWrite?.sequence
    ))
    assertNull(afterThird.frameToWrite)
    assertEquals(0, afterFirst.droppedFrames + afterSecond.droppedFrames + afterThird.droppedFrames)
  }

  @Test
  fun realOverflowDropsDependentGopAndResumesOnlyFromKeyFrame() {
    val state = state(maxQueuedFrames = 2)
    state.markConfigReady()
    assertEquals(10L, state.offer(frame(10, keyFrame = true), 1L).frameToWrite?.sequence)
    state.offer(frame(11), 10L)
    state.offer(frame(12), 20L)

    val overflow = state.offer(frame(13), 30L)
    assertEquals(3, overflow.droppedFrames)
    assertTrue(overflow.requestKeyFrame)
    assertEquals(TicketVideoClientDeliveryState.DROP_QUEUE_OVERFLOW, overflow.dropReason)
    assertTrue(state.snapshot().waitingForKeyFrame)

    val repeatedDelta = state.offer(frame(14), 40L)
    assertEquals(1, repeatedDelta.droppedFrames)
    assertFalse(repeatedDelta.requestKeyFrame)

    val recoveryKeyFrame = state.offer(frame(20, keyFrame = true), 50L)
    val recoveryDelta = state.offer(frame(21), 60L)
    assertEquals(0, recoveryKeyFrame.droppedFrames)
    assertEquals(0, recoveryDelta.droppedFrames)

    val afterBlocked = complete(state, nowMillis = 70L)
    val afterKeyFrame = complete(state, nowMillis = 80L)
    assertEquals(20L, afterBlocked.frameToWrite?.sequence)
    assertTrue(afterBlocked.frameToWrite?.keyFrame == true)
    assertEquals(21L, afterKeyFrame.frameToWrite?.sequence)
  }

  @Test
  fun nonKeySequenceGapIsNeverTransmittedAndRequestsOncePerBrokenGop() {
    val state = state()
    state.markConfigReady()
    assertEquals(40L, state.offer(frame(40, keyFrame = true), 1L).frameToWrite?.sequence)

    val gap = state.offer(frame(42), 10L)
    val repeatedGap = state.offer(frame(43), 20L)
    assertNull(gap.frameToWrite)
    assertEquals(TicketVideoClientDeliveryState.DROP_SEQUENCE_GAP, gap.dropReason)
    assertTrue(gap.requestKeyFrame)
    assertFalse(repeatedGap.requestKeyFrame)

    state.offer(frame(50, keyFrame = true), 30L)
    state.offer(frame(51), 40L)
    val afterOldFrame = complete(state, nowMillis = 50L)
    val afterRecoveryKeyFrame = complete(state, nowMillis = 60L)
    assertEquals(50L, afterOldFrame.frameToWrite?.sequence)
    assertTrue(afterOldFrame.frameToWrite?.keyFrame == true)
    assertEquals(51L, afterRecoveryKeyFrame.frameToWrite?.sequence)
  }

  @Test
  fun staleQueueDropsToFreshKeyFrameInsteadOfSendingAnOldDeltaGap() {
    val state = state(pendingMaxAgeMillis = 150L, slowCloseMillis = 250L)
    state.markConfigReady()
    state.offer(frame(10, keyFrame = true), 1L)
    state.offer(frame(11), 10L)
    state.offer(frame(20, keyFrame = true), 100L)
    state.offer(frame(21), 110L)

    val completion = complete(state, nowMillis = 200L)
    assertEquals(1, completion.droppedFrames)
    assertEquals(TicketVideoClientDeliveryState.DROP_QUEUE_STALE, completion.dropReason)
    assertFalse(completion.requestKeyFrame)
    assertEquals(20L, completion.frameToWrite?.sequence)
    assertTrue(completion.frameToWrite?.keyFrame == true)
    assertEquals(21L, complete(state, nowMillis = 210L).frameToWrite?.sequence)
  }

  @Test
  fun slowClientOverflowCannotChangeFastClientContinuity() {
    val fast = state(maxQueuedFrames = 2)
    val slow = state(maxQueuedFrames = 2)
    fast.markConfigReady()
    slow.markConfigReady()

    val fastWritten = mutableListOf<Long>()
    val slowWritten = mutableListOf<Long>()
    fastWritten += fast.offer(frame(1, keyFrame = true), 1L).frameToWrite!!.sequence
    slowWritten += slow.offer(frame(1, keyFrame = true), 1L).frameToWrite!!.sequence

    for (sequence in 2L..4L) {
      val fastOffer = fast.offer(frame(sequence), sequence * 10L)
      fastOffer.frameToWrite?.let { fastWritten += it.sequence }
      val fastCompletion = complete(fast, nowMillis = sequence * 10L + 1L)
      fastCompletion.frameToWrite?.let { fastWritten += it.sequence }
      slow.offer(frame(sequence), sequence * 10L)
    }

    assertEquals(listOf(1L, 2L, 3L, 4L), fastWritten)
    assertTrue(slow.snapshot().waitingForKeyFrame)
    assertEquals(listOf(1L), slowWritten)
  }

  @Test
  fun writeDeadlineClosesOnlyTheBlockedClient() {
    val fast = state()
    val blocked = state()
    fast.markConfigReady()
    blocked.markConfigReady()
    fast.offer(frame(1, keyFrame = true), 1L)
    blocked.offer(frame(1, keyFrame = true), 1L)
    blocked.offer(frame(2), 10L)
    complete(fast, nowMillis = 2L)

    val timeout = blocked.timeoutWrite(
      writeToken = blocked.snapshot().inFlightWriteToken,
      nowMillis = 251L
    )
    assertTrue(timeout.closeSlowClient)
    assertEquals(2, timeout.droppedFrames)
    assertEquals(TicketVideoClientDeliveryState.DROP_SLOW_CLIENT, timeout.dropReason)
    assertTrue(blocked.snapshot().closed)
    assertFalse(fast.snapshot().closed)
    assertEquals(2L, fast.offer(frame(2), 252L).frameToWrite?.sequence)
  }

  @Test
  fun laterOfferReportsTheActualBlockedDurationWhenClosingSlowClient() {
    val state = state()
    state.markConfigReady()
    state.offer(frame(1, keyFrame = true), 1L)
    state.offer(frame(2), 10L)

    val slow = state.offer(frame(3), 252L)
    assertTrue(slow.closeSlowClient)
    assertEquals(251L, slow.blockedMillis)
    assertEquals(3, slow.droppedFrames)
    assertTrue(state.snapshot().closed)
  }

  @Test
  fun failedWriteClearsPendingMediaAndClosesThatDeliveryGeneration() {
    val state = state()
    state.markConfigReady()
    state.offer(frame(1, keyFrame = true), 1L)
    state.offer(frame(2), 2L)

    val failure = complete(state, nowMillis = 3L, succeeded = false)
    assertEquals(2, failure.droppedFrames)
    assertEquals(TicketVideoClientDeliveryState.DROP_WRITE_FAILED, failure.dropReason)
    assertTrue(state.snapshot().closed)
    assertEquals(0, state.snapshot().queuedFrames)
  }

  @Test
  fun lateOldTokenCannotCloseOrCompleteAReusedSequenceInANewEpoch() {
    val state = state(expectedEpoch = 7L)
    state.markConfigReady()
    val old = state.offer(frame(sequence = 1L, keyFrame = true, epoch = 7L), 1L)
    complete(state, nowMillis = 2L)
    val replacement = state.offer(frame(sequence = 2L, keyFrame = true, epoch = 7L), 3L)

    val staleTimeout = state.timeoutWrite(writeToken = old.writeToken, nowMillis = 400L)
    val staleCompletion = state.completeWrite(
      writeToken = old.writeToken,
      nowMillis = 401L,
      succeeded = true
    )

    assertFalse(staleTimeout.closeSlowClient)
    assertNull(staleCompletion.frameToWrite)
    assertTrue(state.canWrite(replacement.writeToken))
    assertEquals(replacement.writeToken, state.snapshot().inFlightWriteToken)
  }

  @Test
  fun configuredEpochRejectsLateOldFrameAfterReset() {
    val state = state(expectedEpoch = 8L)
    state.markConfigReady()

    val stale = state.offer(frame(sequence = 90L, keyFrame = true, epoch = 7L), 1L)
    val current = state.offer(frame(sequence = 1L, keyFrame = true, epoch = 8L), 2L)

    assertNull(stale.frameToWrite)
    assertEquals(TicketVideoClientDeliveryState.DROP_EPOCH_MISMATCH, stale.dropReason)
    assertTrue(stale.requestKeyFrame)
    assertEquals(1L, current.frameToWrite?.sequence)
    assertEquals(8L, current.frameToWrite?.epoch)
  }

  @Test
  fun repeatedOversizedKeyFramesRequestOnlyOneReplacementKeyFrame() {
    val state = state(maxQueuedBytes = 1)
    state.markConfigReady()
    state.offer(frame(1, keyFrame = true), 1L)

    val firstOversized = state.offer(frame(2, keyFrame = true, bytes = 2), 2L)
    val repeatedOversized = state.offer(frame(3, keyFrame = true, bytes = 2), 3L)

    assertTrue(firstOversized.requestKeyFrame)
    assertFalse(repeatedOversized.requestKeyFrame)
    assertTrue(state.snapshot().waitingForKeyFrame)
  }

  @Test
  fun successfulWritePastHardDeadlineClosesWithoutCountingWrittenFrameAsDropped() {
    val state = state()
    state.markConfigReady()
    state.offer(frame(1, keyFrame = true), 1L)
    state.offer(frame(2), 2L)

    val completion = complete(state, nowMillis = 251L, succeeded = true)

    assertTrue(completion.closeSlowClient)
    assertEquals(1, completion.droppedFrames)
    assertEquals(250L, completion.blockedMillis)
    assertTrue(state.snapshot().closed)
  }

  @Test
  fun staleCachedKeyFrameCannotRegressAnEstablishedEpoch() {
    val state = state()
    state.markConfigReady()
    val first = state.offer(frame(sequence = 10L, keyFrame = true), 1L)
    val delta = state.offer(frame(sequence = 11L), 2L)
    val staleKeyFrame = state.offer(frame(sequence = 10L, keyFrame = true), 3L)

    assertEquals(10L, first.frameToWrite?.sequence)
    assertNull(delta.frameToWrite)
    assertEquals(TicketVideoClientDeliveryState.DROP_STALE_KEYFRAME, staleKeyFrame.dropReason)
    assertTrue(staleKeyFrame.requestKeyFrame)
    assertEquals(11L, state.snapshot().lastAdmittedSequence)
  }

  @Test
  fun closeAndReplacementRejectLateOldCompletionAndRequireNewKeyFrame() {
    val old = state()
    old.markConfigReady()
    old.offer(frame(1, keyFrame = true), 1L)
    old.offer(frame(2), 2L)
    val oldWriteToken = old.snapshot().inFlightWriteToken
    old.close()

    assertNull(old.completeWrite(oldWriteToken, 3L, succeeded = true).frameToWrite)
    assertTrue(old.snapshot().closed)
    assertEquals(0, old.snapshot().queuedFrames)

    val replacement = state()
    val beforeConfig = replacement.offer(frame(10, keyFrame = true), 4L)
    assertEquals(TicketVideoClientDeliveryState.DROP_CONFIG_NOT_READY, beforeConfig.dropReason)
    replacement.markConfigReady()
    val deltaBeforeKeyFrame = replacement.offer(frame(10), 5L)
    assertTrue(deltaBeforeKeyFrame.requestKeyFrame)
    val keyFrame = replacement.offer(frame(10, keyFrame = true), 6L)
    assertEquals(10L, keyFrame.frameToWrite?.sequence)
  }

  @Test
  fun queueBoundsBothFrameCountAndBytes() {
    val state = state(maxQueuedFrames = 10, maxQueuedBytes = 2)
    state.markConfigReady()
    state.offer(frame(1, keyFrame = true), 1L)
    state.offer(frame(2, bytes = 2), 2L)

    val overflow = state.offer(frame(3, bytes = 1), 3L)
    assertEquals(2, overflow.droppedFrames)
    assertTrue(overflow.requestKeyFrame)
    assertEquals(0, state.snapshot().queuedBytes)
  }

  private fun state(
    expectedEpoch: Long = 7L,
    maxQueuedFrames: Int = 12,
    maxQueuedBytes: Int = 1024,
    pendingMaxAgeMillis: Long = 150L,
    slowCloseMillis: Long = 250L
  ): TicketVideoClientDeliveryState {
    return TicketVideoClientDeliveryState(
      expectedEpoch = expectedEpoch,
      maxQueuedFrames = maxQueuedFrames,
      maxQueuedBytes = maxQueuedBytes,
      pendingMaxAgeMillis = pendingMaxAgeMillis,
      slowCloseMillis = slowCloseMillis
    )
  }

  private fun frame(
    sequence: Long,
    keyFrame: Boolean = false,
    epoch: Long = 7L,
    bytes: Int = 1
  ): TicketVideoDeliveryFrame {
    return TicketVideoDeliveryFrame(
      bytes = ByteArray(bytes) { sequence.toByte() },
      keyFrame = keyFrame,
      epoch = epoch,
      sequence = sequence
    )
  }

  private fun complete(
    state: TicketVideoClientDeliveryState,
    nowMillis: Long,
    succeeded: Boolean = true
  ): TicketVideoDeliveryDecision {
    return state.completeWrite(
      writeToken = state.snapshot().inFlightWriteToken,
      nowMillis = nowMillis,
      succeeded = succeeded
    )
  }
}
