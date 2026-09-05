package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class TicketStreamStartupRecoveryPolicyTest {
  @Test
  fun durableRecoveryJoinsWatchdogWhileTheOldEncoderIsStillStopping() {
    assertOverlappingRecoveryCoalesces(RecoveryObservation(false, "restarting", null, 0L))
  }

  @Test
  fun lateRecoveryJoinsAReplacementThatHasAlreadyBecomeFullyHealthy() {
    // Outside both startup windows: only current live evidence can preserve this encoder.
    assertOverlappingRecoveryCoalesces(RecoveryObservation(true, "active", 150L, 10_000L))
  }

  private data class RecoveryObservation(
    val active: Boolean = true,
    val state: String = "active",
    val frameAgeMillis: Long? = 3_760L,
    val startAgeMillis: Long? = 855_053L
  ) {
    fun canContinue() = TicketStreamStartupRecoveryPolicy.canContinueCurrentEncoder(
      encoderActive = active,
      encoderState = state,
      ordinaryCaptureDemandGated = false,
      captureFrameExpected = false,
      firstUsefulFramePending = false,
      frameAgeMillis = frameAgeMillis,
      encoderStartAgeMillis = startAgeMillis,
      liveFrameMaxAgeMillis = 2_000L,
      startupWaitMillis = 3_000L
    )
  }

  private fun assertOverlappingRecoveryCoalesces(replacement: RecoveryObservation) {
    val encoderLock = Any()
    val observation = AtomicReference(RecoveryObservation())
    val stops = AtomicInteger(0)
    val watchdogStopping = CountDownLatch(1)
    val finishStop = CountDownLatch(1)
    val durableArrived = CountDownLatch(1)
    val durableRechecked = CountDownLatch(1)
    val watchdog = thread {
      TicketStreamStartupRecoveryPolicy.restartIfNeeded(
        lock = encoderLock,
        shouldRestart = { !observation.get().canContinue() },
        restart = {
          stops.incrementAndGet()
          watchdogStopping.countDown()
          check(finishStop.await(5, TimeUnit.SECONDS))
          // Production records the replacement start only after the slow old-process stop.
          observation.set(replacement)
        }
      )
    }
    assertTrue(watchdogStopping.await(5, TimeUnit.SECONDS))
    val durable = thread {
      durableArrived.countDown()
      TicketStreamStartupRecoveryPolicy.restartIfNeeded(
        lock = encoderLock,
        shouldRestart = { durableRechecked.countDown(); !observation.get().canContinue() },
        restart = { stops.incrementAndGet() }
      )
    }
    try {
      assertTrue(durableArrived.await(5, TimeUnit.SECONDS))
      assertFalse(durableRechecked.await(100, TimeUnit.MILLISECONDS))
    } finally {
      finishStop.countDown()
      watchdog.join(5_000)
      durable.join(5_000)
    }
    assertFalse(watchdog.isAlive)
    assertFalse(durable.isAlive)
    assertTrue(durableRechecked.await(1, TimeUnit.SECONDS))
    assertEquals(1, stops.get())
  }

  @Test
  fun anExitedEncoderCannotReuseAnOldFreshPicture() {
    assertFalse(RecoveryObservation(active = false, state = "idle", frameAgeMillis = 100L).canContinue())
    assertFalse(RecoveryObservation(active = false, state = "failed", frameAgeMillis = 100L).canContinue())
    assertTrue(RecoveryObservation(frameAgeMillis = 2_000L).canContinue())
    assertFalse(RecoveryObservation(frameAgeMillis = 2_001L).canContinue())
    assertTrue(RecoveryObservation(frameAgeMillis = null, startAgeMillis = 2_999L).canContinue())
    assertFalse(RecoveryObservation(frameAgeMillis = null, startAgeMillis = 3_000L).canContinue())
  }

  @Test
  fun aLaterConfirmedStallStillRestartsAndBothCallbacksShareTheEncoderOwner() {
    val encoderLock = Any()
    var stale = false
    var restarts = 0
    fun attempt() = TicketStreamStartupRecoveryPolicy.restartIfNeeded(
      lock = encoderLock,
      shouldRestart = { assertTrue(Thread.holdsLock(encoderLock)); stale },
      restart = { assertTrue(Thread.holdsLock(encoderLock)); restarts += 1; stale = false }
    )
    assertFalse(attempt())
    stale = true
    assertTrue(attempt())
    assertFalse(attempt())
    stale = true
    assertTrue(attempt())
    assertEquals(2, restarts)
  }

  private fun waiting(
    active: Boolean = true,
    state: String = "active",
    startAgeMillis: Long? = 3_200L,
    frameAgeMillis: Long? = 100L,
    sourceToServiceMillis: Long? = 3_100L
  ): Boolean = TicketStreamStartupRecoveryPolicy.waitingForFirstUsefulFrame(
    encoderActive = active,
    encoderState = state,
    encoderStartAgeMillis = startAgeMillis,
    lastFrameAgeMillis = frameAgeMillis,
    lastFrameSourceToServiceMillis = sourceToServiceMillis,
    graceMillis = 6_000L,
    sourceUsefulnessMillis = 1_250L
  )

  @Test
  fun staleColdPrimerKeepsCurrentEncoderAliveForFollowingFrame() {
    assertTrue(waiting())
    assertTrue(waiting(active = false, state = "restarting"))
    assertTrue(waiting(frameAgeMillis = null, sourceToServiceMillis = null))
  }

  @Test
  fun usefulCurrentGenerationFrameRestoresNormalStaleRecoveryImmediately() {
    assertFalse(waiting(sourceToServiceMillis = 1_250L))
    assertTrue(
      waiting(
        startAgeMillis = 3_200L,
        frameAgeMillis = 3_201L,
        sourceToServiceMillis = 100L
      )
    )
  }

  @Test
  fun graceIsBoundedAndDoesNotHideADeadEncoder() {
    assertTrue(waiting(startAgeMillis = 5_999L))
    assertFalse(waiting(startAgeMillis = 6_000L))
    assertFalse(waiting(active = false, state = "idle"))
    assertFalse(waiting(startAgeMillis = null))
  }
}
