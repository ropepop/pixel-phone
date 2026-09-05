package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketVideoClientWriterPumpTest {
  @Test
  fun actualPumpDrainsBlockedFrameThenOnlyTheNewestPendingFrame() = runTest {
    val state = state()
    state.markConfigReady()
    val first = state.offer(frame(10, keyFrame = true), nowMillis = 1L)
    val releaseFirst = CompletableDeferred<Unit>()
    val writes = mutableListOf<Long>()
    var calls = 0
    val pump = pump(
      state = state,
      sendBinary = { frame, canSend ->
        if (calls++ == 0) releaseFirst.await()
        if (!canSend()) false else {
          writes += frame.sequence
          true
        }
      }
    )

    pump.start(first.frameToWrite!!, first.writeToken)
    runCurrent()
    assertEquals(0, writes.size)
    state.offer(frame(11), nowMillis = 10L)
    state.offer(frame(12), nowMillis = 20L)

    releaseFirst.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf(10L, 12L), writes)
    assertEquals(0, state.snapshot().pendingFrames)
    assertFalse(state.snapshot().writeInFlight)
  }

  @Test
  fun timeoutClosesBlockedPumpAndDoesNotLeakQueuedFrames() = runTest {
    val state = state()
    state.markConfigReady()
    val first = state.offer(frame(1, keyFrame = true), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)
    val releaseWrite = CompletableDeferred<Unit>()
    val closeDecisions = mutableListOf<TicketVideoDeliveryDecision>()
    var current = true
    var rejectedCurrent = 0
    val pump = pump(
      state = state,
      nowMillis = { testScheduler.currentTime + 1L },
      sendBinary = { _, canSend ->
        releaseWrite.await()
        canSend()
      },
      onDecision = { decision ->
        if (decision.closeSlowClient) {
          closeDecisions += decision
          current = false
          releaseWrite.complete(Unit)
        }
      },
      isCurrent = { current },
      onRejectedCurrent = { rejectedCurrent += 1 }
    )

    pump.start(first.frameToWrite!!, first.writeToken)
    runCurrent()
    advanceTimeBy(250L)
    runCurrent()
    advanceUntilIdle()

    assertEquals(1, closeDecisions.size)
    assertEquals(250L, closeDecisions.single().blockedMillis)
    assertEquals(2, closeDecisions.single().droppedFrames)
    assertTrue(state.snapshot().closed)
    assertEquals(0, state.snapshot().pendingFrames)
    assertEquals(0, rejectedCurrent)
  }

  @Test
  fun falseSendClosesOnlyCurrentDeliveryGeneration() = runTest {
    val state = state()
    state.markConfigReady()
    val first = state.offer(frame(1, keyFrame = true), nowMillis = 1L)
    var rejectedCurrent = 0
    val decisions = mutableListOf<TicketVideoDeliveryDecision>()
    val pump = pump(
      state = state,
      sendBinary = { _, _ -> false },
      onDecision = { decisions += it },
      onRejectedCurrent = { rejectedCurrent += 1 }
    )

    pump.start(first.frameToWrite!!, first.writeToken)
    advanceUntilIdle()

    assertEquals(1, rejectedCurrent)
    assertTrue(state.snapshot().closed)
    assertTrue(decisions.any { it.dropReason == TicketVideoClientDeliveryState.DROP_WRITE_FAILED })
  }

  @Test
  fun configReplacementRejectsLateOldWriteWhileNewPumpContinues() = runTest {
    val oldState = state()
    oldState.markConfigReady()
    val oldFirst = oldState.offer(frame(1, keyFrame = true, epoch = 7L), nowMillis = 1L)
    val oldGate = CompletableDeferred<Unit>()
    var currentState = oldState
    val writes = mutableListOf<Pair<Long, Long>>()
    var oldRejectedCurrent = 0
    val oldPump = pump(
      state = oldState,
      sendBinary = { frame, canSend ->
        oldGate.await()
        if (!canSend()) false else {
          writes += frame.epoch to frame.sequence
          true
        }
      },
      isCurrent = { currentState === oldState },
      onRejectedCurrent = { oldRejectedCurrent += 1 }
    )
    oldPump.start(oldFirst.frameToWrite!!, oldFirst.writeToken)
    runCurrent()

    val replacement = state(expectedEpoch = 8L)
    replacement.markConfigReady()
    oldState.close()
    currentState = replacement
    val replacementFirst = replacement.offer(
      frame(1, keyFrame = true, epoch = 8L),
      nowMillis = 2L
    )
    val replacementPump = pump(
      state = replacement,
      sendBinary = { frame, canSend ->
        if (!canSend()) false else {
          writes += frame.epoch to frame.sequence
          true
        }
      },
      isCurrent = { currentState === replacement }
    )
    replacementPump.start(replacementFirst.frameToWrite!!, replacementFirst.writeToken)
    runCurrent()

    oldGate.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf(8L to 1L), writes)
    assertEquals(0, oldRejectedCurrent)
    assertTrue(oldState.snapshot().closed)
    assertFalse(replacement.snapshot().closed)
  }

  @Test
  fun successfulWritePastDeadlineStillProducesSlowCloseDecision() = runTest {
    val state = state()
    state.markConfigReady()
    val first = state.offer(frame(1, keyFrame = true), nowMillis = 1L)
    state.offer(frame(2), nowMillis = 2L)
    var nowMillis = 1L
    val decisions = mutableListOf<TicketVideoDeliveryDecision>()
    val pump = pump(
      state = state,
      nowMillis = { nowMillis },
      sendBinary = { _, canSend ->
        assertTrue(canSend())
        nowMillis = 251L
        true
      },
      onDecision = { decisions += it }
    )

    pump.start(first.frameToWrite!!, first.writeToken)
    advanceUntilIdle()

    val close = decisions.single { it.closeSlowClient }
    assertEquals(250L, close.blockedMillis)
    assertEquals(1, close.droppedFrames)
    assertTrue(state.snapshot().closed)
  }

  @Test
  fun blockedPumpCannotDelayOrReorderIndependentFastPump() = runTest {
    val slowState = state()
    val fastState = state()
    slowState.markConfigReady()
    fastState.markConfigReady()
    val slowFirst = slowState.offer(frame(1, keyFrame = true), 1L)
    val fastFirst = fastState.offer(frame(1, keyFrame = true), 1L)
    val slowGate = CompletableDeferred<Unit>()
    val slowWrites = mutableListOf<Long>()
    val fastWrites = mutableListOf<Long>()
    pump(
      state = slowState,
      sendBinary = { frame, canSend ->
        slowGate.await()
        if (!canSend()) false else {
          slowWrites += frame.sequence
          true
        }
      }
    ).start(slowFirst.frameToWrite!!, slowFirst.writeToken)
    val fastPump = pump(
      state = fastState,
      sendBinary = { frame, canSend ->
        if (!canSend()) false else {
          fastWrites += frame.sequence
          true
        }
      }
    )
    fastPump.start(fastFirst.frameToWrite!!, fastFirst.writeToken)
    runCurrent()

    for (sequence in 2L..4L) {
      slowState.offer(frame(sequence), sequence * 10L)
      val fastOffer = fastState.offer(frame(sequence), sequence * 10L)
      fastOffer.frameToWrite?.let { fastPump.start(it, fastOffer.writeToken) }
      runCurrent()
    }

    assertEquals(listOf(1L, 2L, 3L, 4L), fastWrites)
    assertEquals(1, slowState.snapshot().pendingFrames)
    assertEquals(4L, slowState.snapshot().pendingSequence)
    slowGate.complete(Unit)
    advanceUntilIdle()
    assertEquals(listOf(1L, 4L), slowWrites)
  }

  @Test
  fun invalidatedGenerationStillClosesBlockedSocketAtOriginalDeadline() = runTest {
    val state = state()
    state.markConfigReady()
    val first = state.offer(frame(1, keyFrame = true), 1L)
    val gate = CompletableDeferred<Unit>()
    var current = true
    val expirations = mutableListOf<Long>()
    val pump = pump(
      state = state,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { current },
      sendBinary = { _, canSend -> gate.await(); canSend() },
      onWriteExpired = { _, blockedMillis ->
        expirations += blockedMillis
        gate.complete(Unit)
      }
    )

    pump.start(first.frameToWrite!!, first.writeToken)
    runCurrent()
    state.close()
    current = false
    advanceTimeBy(250L)
    runCurrent()
    advanceUntilIdle()

    assertEquals(listOf(250L), expirations)
  }

  @Test
  fun productionBoundedWriteWindowDoesNotCloseAFeasibleWriteAtTheOld250Millis() = runTest {
    val usefulnessMillis = 1_250L
    val state = state(slowCloseMillis = usefulnessMillis)
    state.markConfigReady()
    val first = state.offer(frame(1, keyFrame = true), 1L)
    val gate = CompletableDeferred<Unit>()
    val closeDecisions = mutableListOf<TicketVideoDeliveryDecision>()
    var current = true
    val pump = pump(
      state = state,
      slowCloseMillis = usefulnessMillis,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { current },
      sendBinary = { _, canSend -> gate.await(); canSend() },
      onDecision = { decision ->
        if (decision.closeSlowClient) {
          closeDecisions += decision
          current = false
          gate.complete(Unit)
        }
      }
    )

    pump.start(first.frameToWrite!!, first.writeToken)
    runCurrent()
    advanceTimeBy(250L)
    runCurrent()
    assertTrue(closeDecisions.isEmpty())
    assertFalse(state.snapshot().closed)

    advanceTimeBy(1_000L)
    runCurrent()
    advanceUntilIdle()
    assertEquals(1_250L, closeDecisions.single().blockedMillis)
    assertTrue(state.snapshot().closed)
  }

  private suspend fun pump(
    state: TicketVideoClientDeliveryState,
    slowCloseMillis: Long = 250L,
    nowMillis: () -> Long = { 1L },
    sendBinary: suspend (TicketVideoDeliveryFrame, () -> Boolean) -> Boolean,
    onDecision: (TicketVideoDeliveryDecision) -> Unit = {},
    isCurrent: () -> Boolean = { true },
    onWriteExpired: (TicketVideoDeliveryFrame, Long) -> Unit = { _, _ -> },
    onRejectedCurrent: () -> Unit = {}
  ): TicketVideoClientWriterPump {
    return TicketVideoClientWriterPump(
      scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()),
      state = state,
      slowWriteMillis = 100L,
      slowCloseMillis = slowCloseMillis,
      nowMillis = nowMillis,
      isCurrent = isCurrent,
      sendBinary = sendBinary,
      onDecision = onDecision,
      onSlowWrite = { _, _ -> },
      onWriteExpired = onWriteExpired,
      onRejectedCurrent = onRejectedCurrent
    )
  }

  private fun state(
    expectedEpoch: Long = 7L,
    slowCloseMillis: Long = 250L
  ): TicketVideoClientDeliveryState {
    return TicketVideoClientDeliveryState(
      expectedEpoch = expectedEpoch,
      maxFrameBytes = 1024,
      slowCloseMillis = slowCloseMillis
    )
  }

  private fun frame(
    sequence: Long,
    keyFrame: Boolean = true,
    epoch: Long = 7L
  ): TicketVideoDeliveryFrame {
    return TicketVideoDeliveryFrame(
      bytes = byteArrayOf(sequence.toByte()),
      keyFrame = keyFrame,
      epoch = epoch,
      sequence = sequence
    )
  }
}
