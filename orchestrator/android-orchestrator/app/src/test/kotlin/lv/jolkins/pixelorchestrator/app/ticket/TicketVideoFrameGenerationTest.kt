package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TicketVideoFrameGenerationTest {
  @Test
  fun callbackBlockedBehindRestartCannotTakeTheReplacementEpoch() {
    val encoderLock = Any()
    val currentCapture = AtomicLong(7L)
    val engineReadCompleted = CountDownLatch(1)
    val callbackFailure = AtomicReference<Throwable?>(null)
    var epoch = 70L
    var encodedFrames = 0L
    var frameSequence = 0L
    var recoverySucceeded = false
    val published = mutableListOf<Pair<Long, Long>>()

    fun admit(frame: TicketRootCaptureFrame) = synchronized(encoderLock) {
      if (!frame.hasCurrentCaptureGeneration(currentCapture.get())) return@synchronized
      encodedFrames += 1L
      frameSequence += 1L
      published += epoch to frameSequence
      recoverySucceeded = true
    }

    val oldFrame = sourceFrame(7L)
    val callback: Thread
    synchronized(encoderLock) {
      callback = thread {
        try {
          // The old record passed the engine's original read-side generation check.
          check(oldFrame.hasCurrentCaptureGeneration(currentCapture.get()))
          engineReadCompleted.countDown()
          admit(oldFrame)
        } catch (error: Throwable) {
          callbackFailure.set(error)
          engineReadCompleted.countDown()
        }
      }
      assertTrue(engineReadCompleted.await(5, TimeUnit.SECONDS))
      // Recovery keeps the same dimensions, invalidates the source, and resets the epoch
      // while the completed old callback is still waiting for service admission.
      currentCapture.incrementAndGet()
      epoch = 80L
    }
    callback.join(5_000L)
    assertFalse(callback.isAlive)
    callbackFailure.get()?.let { throw AssertionError("callback failed", it) }
    assertEquals(0L, encodedFrames)
    assertEquals(0L, frameSequence)
    assertFalse(recoverySucceeded)
    assertTrue(published.isEmpty())

    admit(sourceFrame(8L))
    assertEquals(1L, encodedFrames)
    assertEquals(1L, frameSequence)
    assertTrue(recoverySucceeded)
    assertEquals(listOf(80L to 1L), published)
  }

  @Test
  fun uninitializedAndObsoleteCaptureGenerationsAreRejected() {
    assertFalse(sourceFrame(0L).hasCurrentCaptureGeneration(0L))
    assertFalse(sourceFrame(-1L).hasCurrentCaptureGeneration(-1L))
    assertFalse(sourceFrame(7L).hasCurrentCaptureGeneration(8L))
    assertTrue(sourceFrame(8L).hasCurrentCaptureGeneration(8L))
  }

  private fun sourceFrame(generation: Long) = TicketRootCaptureFrame(
    captureGeneration = generation,
    keyFrame = true,
    captureAttemptId = 1L,
    codecGeneration = 1L,
    captureStartUs = 1_000L,
    captureCompleteUs = 1_100L,
    codecInputUs = 1_200L,
    codecOutputUs = 1_300L,
    recordEmissionUs = 1_400L,
    payload = byteArrayOf(1),
    width = 720,
    height = 1482
  )

  @Test
  fun callbackAcceptedInOldEpochCannotBeRelabeledIntoNewEpoch() {
    val accepted = TicketVideoFrameGeneration(epoch = 7L, width = 720, height = 1482)

    assertTrue(accepted.matches(currentEpoch = 7L, currentWidth = 720, currentHeight = 1482))
    assertFalse(accepted.matches(currentEpoch = 8L, currentWidth = 720, currentHeight = 1482))
  }

  @Test
  fun callbackFromOldCaptureDimensionsCannotEnterCurrentGeneration() {
    val accepted = TicketVideoFrameGeneration(epoch = 8L, width = 720, height = 1482)

    assertFalse(accepted.matches(currentEpoch = 8L, currentWidth = 720, currentHeight = 1440))
    assertFalse(accepted.matches(currentEpoch = 8L, currentWidth = null, currentHeight = null))
  }
}
