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
class TicketVideoClientConfigWriterPumpTest {
  @Test
  fun blockedConfigurationHasAHardDeadlineAndClosesOnlyItsGeneration() = runTest {
    val gate = CompletableDeferred<Unit>()
    var current = true
    var ready = false
    var configuredCalls = 0
    val expirations = mutableListOf<Long>()
    val pump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { current },
      sendConfig = { canSend ->
        gate.await()
        canSend()
      },
      markReady = { ready = true; true },
      onConfigured = { configuredCalls += 1 },
      onExpired = { blockedMillis ->
        expirations += blockedMillis
        current = false
        gate.complete(Unit)
      },
      onRejectedCurrent = {}
    )

    pump.start()
    runCurrent()
    advanceTimeBy(250L)
    runCurrent()
    advanceUntilIdle()

    assertEquals(listOf(250L), expirations)
    assertFalse(ready)
    assertEquals(0, configuredCalls)
  }

  @Test
  fun elapsedDeadlineStillClosesWhenTimerCoroutineDoesNotRunFirst() = runTest {
    var nowMillis = 1L
    var current = true
    val expirations = mutableListOf<Long>()
    var configured = false
    val pump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { nowMillis },
      isCurrent = { current },
      sendConfig = { canSend ->
        assertTrue(canSend())
        nowMillis = 301L
        true
      },
      markReady = { true },
      onConfigured = { configured = true },
      onExpired = { blockedMillis ->
        expirations += blockedMillis
        current = false
      },
      onRejectedCurrent = {}
    )

    pump.start()
    advanceUntilIdle()

    assertEquals(listOf(300L), expirations)
    assertFalse(configured)
  }

  @Test
  fun elapsedDeadlineStillClosesWhenGenerationBecameObsoleteDuringWrite() = runTest {
    var nowMillis = 1L
    var current = true
    val expirations = mutableListOf<Long>()
    val pump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { nowMillis },
      isCurrent = { current },
      sendConfig = { canSend ->
        assertTrue(canSend())
        nowMillis = 301L
        current = false
        true
      },
      markReady = { true },
      onConfigured = {},
      onExpired = { expirations += it },
      onRejectedCurrent = {}
    )

    pump.start()
    advanceUntilIdle()

    assertEquals(listOf(300L), expirations)
  }

  @Test
  fun replacementConfigurationRejectsLateOldCompletion() = runTest {
    val oldGate = CompletableDeferred<Unit>()
    val oldGeneration = Any()
    val replacementGeneration = Any()
    var currentGeneration = oldGeneration
    val writes = mutableListOf<String>()
    var oldReady = false
    var replacementReady = false
    val oldPump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { currentGeneration === oldGeneration },
      sendConfig = { canSend ->
        oldGate.await()
        if (!canSend()) false else {
          writes += "old"
          true
        }
      },
      markReady = { oldReady = true; true },
      onConfigured = {},
      onExpired = {},
      onRejectedCurrent = {}
    )
    oldPump.start()
    runCurrent()

    currentGeneration = replacementGeneration
    val replacementPump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { currentGeneration === replacementGeneration },
      sendConfig = { canSend ->
        if (!canSend()) false else {
          writes += "replacement"
          true
        }
      },
      markReady = { replacementReady = true; true },
      onConfigured = {},
      onExpired = {},
      onRejectedCurrent = {}
    )
    replacementPump.start()
    runCurrent()
    oldGate.complete(Unit)
    advanceUntilIdle()

    assertEquals(listOf("replacement"), writes)
    assertFalse(oldReady)
    assertTrue(replacementReady)
  }

  @Test
  fun keyFrameProducedWhileConfigIsBlockedIsReplayedAfterSuccessfulFlush() = runTest {
    val state = state()
    val gate = CompletableDeferred<Unit>()
    val cachedKeyFrame = frame(sequence = 1L, keyFrame = true)
    var replay: TicketVideoDeliveryDecision? = null
    val pump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { true },
      sendConfig = { canSend -> gate.await(); canSend() },
      markReady = state::markConfigReady,
      onConfigured = {
        replay = state.offer(cachedKeyFrame, nowMillis = testScheduler.currentTime + 2L)
      },
      onExpired = {},
      onRejectedCurrent = {}
    )

    pump.start()
    runCurrent()
    val blocked = state.offer(cachedKeyFrame, nowMillis = 1L)
    assertEquals(TicketVideoClientDeliveryState.DROP_CONFIG_NOT_READY, blocked.dropReason)

    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(1L, replay?.frameToWrite?.sequence)
    assertTrue(replay?.frameToWrite?.keyFrame == true)
  }

  @Test
  fun advancedSourceTailWhileConfigIsBlockedRequestsOneFreshKeyFrameAfterFlush() = runTest {
    val state = state()
    val gate = CompletableDeferred<Unit>()
    val cachedKeyFrame = frame(sequence = 1L, keyFrame = true)
    var sourceTail = 0L
    var freshKeyFrameRequests = 0
    val pump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { true },
      sendConfig = { canSend -> gate.await(); canSend() },
      markReady = state::markConfigReady,
      onConfigured = {
        if (cachedKeyFrame.sequence == sourceTail) {
          state.offer(cachedKeyFrame, nowMillis = testScheduler.currentTime + 2L)
        } else {
          freshKeyFrameRequests += 1
        }
      },
      onExpired = {},
      onRejectedCurrent = {}
    )

    pump.start()
    runCurrent()
    sourceTail = 1L
    state.offer(cachedKeyFrame, nowMillis = 1L)
    sourceTail = 2L
    state.offer(frame(sequence = 2L), nowMillis = 2L)

    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(1, freshKeyFrameRequests)
    assertFalse(state.snapshot().writeInFlight)
    assertTrue(state.snapshot().waitingForKeyFrame)
  }

  @Test
  fun invalidatedConfigGenerationStillExpiresBlockedSocketAtOriginalDeadline() = runTest {
    val gate = CompletableDeferred<Unit>()
    var current = true
    val expirations = mutableListOf<Long>()
    val pump = TicketVideoClientConfigWriterPump(
      scope = this,
      slowCloseMillis = 250L,
      nowMillis = { testScheduler.currentTime + 1L },
      isCurrent = { current },
      sendConfig = { canSend -> gate.await(); canSend() },
      markReady = { true },
      onConfigured = {},
      onExpired = { blockedMillis ->
        expirations += blockedMillis
        gate.complete(Unit)
      },
      onRejectedCurrent = {}
    )

    pump.start()
    runCurrent()
    current = false
    advanceTimeBy(250L)
    runCurrent()
    advanceUntilIdle()

    assertEquals(listOf(250L), expirations)
  }

  private fun state(): TicketVideoClientDeliveryState {
    return TicketVideoClientDeliveryState(
      expectedEpoch = 7L,
      maxQueuedFrames = 12,
      maxQueuedBytes = 1024,
      pendingMaxAgeMillis = 150L,
      slowCloseMillis = 250L
    )
  }

  private fun frame(sequence: Long, keyFrame: Boolean = false): TicketVideoDeliveryFrame {
    return TicketVideoDeliveryFrame(
      bytes = byteArrayOf(sequence.toByte()),
      keyFrame = keyFrame,
      epoch = 7L,
      sequence = sequence
    )
  }
}
