package lv.jolkins.pixelorchestrator.app.ticket

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketActionPanelDarkLeaseTest {
  @Test
  fun existingVisibilityPermitsWorkButANewTouchCannotResumeIt() = runTest {
    var touch = readyNoTouch(1_000L)
    var window = TicketPhysicalVisibleWindow(90_000L, 1L)
    val helper = SuccessfulLaunchRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "visible-interruption", scope = backgroundScope,
      clampRootExecutor = helper, verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { touch }, physicalVisibleWindow = { window },
      onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 1_000L }
    )
    assertTrue(lease.acquire())
    assertFalse(helper.started.isCompleted)
    lease.markMutationMayHaveDispatched()
    touch = touch.copy(active = true, touchBeginCount = 1L)
    window = TicketPhysicalVisibleWindow(91_000L, 2L)
    assertFalse(lease.beforeMutationAllowed())
    touch = touch.copy(active = false)
    assertFalse(lease.beforeMutationAllowed())
    assertFalse(lease.releaseAfterFinalConvergence("complete").safe)
    assertTrue(lease.snapshot().physicalTouchPreempted)
  }

  @Test
  fun visibleGrantExpiryRequiresDarkProofBeforeNextMutation() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ZeroRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "visible-expiry", scope = backgroundScope,
      clampRootExecutor = helper, verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 1_000L) },
      physicalVisibleWindow = { TicketPhysicalVisibleWindow(1_100L, 1L) },
      onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 1_000L }
    )
    assertTrue(lease.acquire())
    assertFalse(helper.started.isCompleted)
    advanceTimeBy(101L)
    assertTrue(lease.beforeMutationAllowed())
    assertTrue(helper.started.isCompleted)
    assertTrue(verifier.verificationCalls > 0)
    assertEquals("dark", lease.snapshot().protectionMode)
    assertTrue(lease.releaseAfterFinalConvergence("complete").safe)
    assertEquals(1, verifier.stopCalls)
  }

  @Test
  fun newVisibilityGrantCannotRaceHelperRegistration() = runTest {
    var window = TicketPhysicalVisibleWindow()
    val helper = SuccessfulLaunchRootExecutor()
    var writerStopped = false
    val lease = TicketActionPanelDarkLease(
      actionId = "grant-during-registration", scope = backgroundScope,
      clampRootExecutor = helper, verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { readyNoTouch(1_000L) }, physicalVisibleWindow = { window },
      onDarkWriterStarting = { window = TicketPhysicalVisibleWindow(90_000L, 1L) },
      onDarkWriterStopped = { writerStopped = true },
      onSnapshotChanged = {}, ownerProcessId = 4321, uptimeClock = { 1_000L }
    )
    assertFalse(lease.acquire())
    assertFalse(helper.started.isCompleted)
    assertTrue(writerStopped)
    assertTrue(lease.snapshot().physicalTouchPreempted)
    lease.release("interrupted")
  }

  @Test
  fun batchedAcquisitionKeepsTheBudgetForBothObservations() = runTest {
    val verifier = DelayedBudgetedVerifier(1_200L)
    val lease = TicketActionPanelDarkLease(
      actionId = "batched-verifier-budget", scope = backgroundScope,
      clampRootExecutor = SuccessfulLaunchRootExecutor(), verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 1_000L) },
      onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 1_000L }
    )
    assertTrue(lease.acquire())
    assertEquals(listOf(2_000L), verifier.timeouts)
    assertTrue(testScheduler.currentTime < TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS)
    lease.release("test_complete")
  }

  @Test
  fun lateBatchedProofCannotExtendTheAcquisitionDeadline() = runTest {
    val verifier = DelayedBudgetedVerifier(3_100L, honorTimeout = false)
    val lease = TicketActionPanelDarkLease(
      actionId = "batched-verifier-deadline", scope = backgroundScope,
      clampRootExecutor = SuccessfulLaunchRootExecutor(), verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 1_000L) },
      onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 1_000L }
    )
    assertFalse(lease.acquire())
    assertFalse(lease.snapshot().active)
    lease.release("test_complete")
  }

  private class DelayedBudgetedVerifier(
    private val delayMillis: Long,
    private val honorTimeout: Boolean = true
  ) : RootExecutor {
    val timeouts = mutableListOf<Long>()
    override suspend fun isRootAvailable() = true
    override suspend fun run(command: String, timeout: Duration): RootResult = error("unexpected run")
    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) return RootResult(
        exitCode = 0, stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
        stderr = "", command = "script", durationMs = 1L
      )
      timeouts += timeout.inWholeMilliseconds
      return if (honorTimeout) {
        withTimeoutOrNull(timeout.inWholeMilliseconds) { delay(delayMillis); provenZeroResult() }
          ?: unavailableResult()
      } else {
        delay(delayMillis)
        provenZeroResult()
      }
    }
  }

  @Test
  fun touchCancelsStalledAcquisitionVerificationBeforeExactStop() = runTest {
    var touch = readyNoTouch(1_000L)
    val verifier = SuspendedAcquisitionVerifyRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "touch-during-stalled-acquire", scope = backgroundScope,
      clampRootExecutor = SuccessfulLaunchRootExecutor(), verifyRootExecutor = verifier,
      physicalTouchState = { touch }, onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 1_000L }
    )
    val acquisition = async { lease.acquire() }
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_POLL_MILLIS)
    runCurrent()
    assertTrue(verifier.verificationStarted.isCompleted)
    touch = touch.copy(active = false, touchBeginCount = 1L)
    advanceTimeBy(TicketActionPanelDarkLease.PHYSICAL_TOUCH_POLL_MILLIS)
    runCurrent()
    assertTrue(verifier.verificationCancelled)
    assertTrue(verifier.exactStopStarted.isCompleted)
    assertFalse(verifier.verificationResult.isCompleted)
    assertFalse(acquisition.await())
    assertFalse(lease.snapshot().active)
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_during_acquire", lease.snapshot().releaseReason)
    lease.release("test_complete")
  }

  @Test
  fun completedTouchBetweenSamplesBlocksMutationAndFinalSuccess() = runTest {
    var touch = readyNoTouch(1_000L).copy(touchBeginCount = 3L)
    val verifier = ZeroRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "remembered-touch", scope = backgroundScope,
      clampRootExecutor = SuccessfulLaunchRootExecutor(), verifyRootExecutor = verifier,
      physicalTouchState = { touch }, onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 1_000L }
    )
    assertTrue(lease.acquire())
    runCurrent()
    // Both down and up have reached the bridge before either consumer gets another turn.
    touch = touch.copy(active = false, touchBeginCount = 4L)
    assertFalse(lease.beforeMutationAllowed())
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertFalse(lease.releaseAfterFinalConvergence("terminal").safe)
    assertEquals(1, verifier.stopCalls)
  }




  @Test
  fun leaseHoldsAcrossAnActionAndExactlyStopsDetachedHelperOnRelease() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    val verifier = ZeroRootExecutor()
    val snapshots = mutableListOf<TicketActionPanelDarkLeaseSnapshot>()
    var now = 1_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-1",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(available = true, active = false, observedAtUptimeMillis = now)
      },
      onSnapshotChanged = snapshots::add,
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    assertTrue(helper.started.isCompleted)
    assertTrue(lease.beforeMutationAllowed())
    lease.markMutationMayHaveDispatched()

    lease.release("terminal_complete")
    runCurrent()

    assertTrue(helper.completed)
    assertEquals(1, verifier.stopCalls)
    assertFalse(lease.snapshot().active)
    assertTrue(lease.snapshot().mutationMayHaveDispatched)
    assertEquals("terminal_complete", lease.snapshot().releaseReason)
    assertTrue(snapshots.any { it.active && it.lastZeroConfirmedAtUptimeMillis == 1_000L })
  }

  @Test
  fun physicalRootTouchPreemptsTheHelperAndBlocksTheNextMutationBoundary() = runTest {
    val helper = SuccessfulLaunchRootExecutor()
    var now = 2_000L
    var touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-touch",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = now + 1
    )

    // The synchronous mutation-boundary read wins even before the 20 ms monitor wakes.
    assertFalse(lease.beforeMutationAllowed())
    runCurrent()
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_at_mutation_boundary", lease.snapshot().releaseReason)
    assertTrue(helper.completed)

    lease.release("ignored_after_preemption")
    assertEquals("physical_touch_at_mutation_boundary", lease.snapshot().releaseReason)
  }


  @Test
  fun activeOrUnreadyPhysicalTouchSourceFailsClosedBeforeStartingAHelper() = runTest {
    suspend fun acquireFor(
      state: PhoneAutomationRootPhysicalTouchState
    ): Pair<Boolean, SuccessfulLaunchRootExecutor> {
      val helper = SuccessfulLaunchRootExecutor()
      val lease = TicketActionPanelDarkLease(
        actionId = "action-blocked",
        scope = backgroundScope,
        clampRootExecutor = helper,
        verifyRootExecutor = ZeroRootExecutor(),
        physicalTouchState = { state },
        onSnapshotChanged = {},
        ownerProcessId = 4321,
        uptimeClock = { 4_000L }
      )
      return lease.acquire() to helper
    }

    val active = acquireFor(PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = 4_000L
    ))
    assertFalse(active.first)
    assertFalse(active.second.started.isCompleted)

    val unready = acquireFor(PhoneAutomationRootPhysicalTouchState())
    assertFalse(unready.first)
    assertFalse(unready.second.started.isCompleted)
  }



































  private class SuccessfulLaunchRootExecutor : RootExecutor {
    val started = CompletableDeferred<Unit>()
    var completed = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      started.complete(Unit)
      completed = true
      return RootResult(
        exitCode = 0,
        stdout = "helper_launched=1\n",
        stderr = "",
        command = "script",
        durationMs = 1L
      )
    }
  }

  private class CompletedLaunchRootExecutor : RootExecutor {
    var completed = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      completed = true
      return RootResult(
        exitCode = 0,
        stdout = "helper_launched=1\n",
        stderr = "",
        command = "script",
        durationMs = 1L
      )
    }
  }

  private class ImmediateResultLaunchRootExecutor(
    private val result: RootResult
  ) : RootExecutor {
    var timeoutMillis = 0L

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      timeoutMillis = timeout.inWholeMilliseconds
      return result
    }
  }

  private class DeferredLaunchRootExecutor : RootExecutor {
    private val result = CompletableDeferred<RootResult>()

    fun complete(value: RootResult) {
      result.complete(value)
    }

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult = result.await()
  }

  private class SuspendedAcquisitionVerifyRootExecutor : RootExecutor {
    private val mutex = Mutex()
    val verificationStarted = CompletableDeferred<Unit>()
    val verificationResult = CompletableDeferred<RootResult>()
    val exactStopStarted = CompletableDeferred<Unit>()
    var verificationCancelled = false
    override suspend fun isRootAvailable(): Boolean = true
    override suspend fun run(command: String, timeout: Duration): RootResult = error("unexpected")
    override suspend fun runScript(script: String, timeout: Duration): RootResult = mutex.withLock {
      if (script.contains("echo helper_stopped=1")) {
        assertTrue(verificationCancelled)
        assertTrue(script.contains("remove_launch=1"))
        exactStopStarted.complete(Unit)
        RootResult(0, "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n", "", "script", 0L)
      } else {
        verificationStarted.complete(Unit)
        try {
          verificationResult.await()
        } finally {
          verificationCancelled = true
        }
      }
    }
  }

  private class ZeroRootExecutor(private val onVerification: (Int) -> Unit = {}) : RootExecutor {
    var stopCalls = 0
    var verificationCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) {
        stopCalls += 1
      } else {
        verificationCalls += 1
        onVerification(verificationCalls)
      }
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\npanel_confirmations=2\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class ToggleZeroRootExecutor : RootExecutor {
    var panelDark = true
    var stopCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) stopCalls += 1
      return when {
        isStop -> RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
        panelDark -> provenZeroResult()
        else -> nonzeroPanelResult()
      }
    }
  }

  private class TouchOnStopVerifyRootExecutor(
    private val onStop: () -> Unit
  ) : RootExecutor {
    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) onStop()
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\npanel_confirmations=2\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class HelperAbsentAfterSlowStopRootExecutor(
    private val onFirstStop: () -> Unit
  ) : RootExecutor {
    private var stopped = false
    var verificationsAfterStop = 0
      private set

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) {
        if (!stopped) {
          stopped = true
          onFirstStop()
        }
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      if (stopped) {
        verificationsAfterStop += 1
        return helperUnreadyResult()
      }
      return provenZeroResult()
    }
  }

  private class SequencedVerifyRootExecutor(
    private val outputs: List<String>
  ) : RootExecutor {
    var calls: Int = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      val output = outputs.getOrElse(calls) { outputs.last() }
      calls += 1
      return RootResult(
        exitCode = 0,
        stdout = output,
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class DelayedCancellationIgnoringLaunchRootExecutor(
    private val delayMillis: Long
  ) : RootExecutor {
    val started = CompletableDeferred<Unit>()
    var lateSpawned = false
    var completed = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      started.complete(Unit)
      return try {
        withContext(NonCancellable) {
          delay(delayMillis)
        }
        lateSpawned = true
        RootResult(
          exitCode = 0,
          stdout = "helper_launched=1\n",
          stderr = "",
          command = "script",
          durationMs = delayMillis
        )
      } finally {
        completed = true
      }
    }
  }

  private class LateSpawnRecordingVerifyRootExecutor(
    private val launcher: DelayedCancellationIgnoringLaunchRootExecutor
  ) : RootExecutor {
    var stopCalls = 0
    var retainedMarkerStopCalls = 0
    var finalMarkerStopCalls = 0
    var verificationCalls = 0
    var finalStopObservedLateSpawn = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) {
        stopCalls += 1
        if (script.contains("remove_launch=0")) retainedMarkerStopCalls += 1
        if (script.contains("remove_launch=1")) {
          finalMarkerStopCalls += 1
          finalStopObservedLateSpawn = launcher.lateSpawned
        }
      } else {
        verificationCalls += 1
      }
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\npanel_confirmations=2\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class FailingStopVerifyRootExecutor : RootExecutor {
    var stopCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) stopCalls += 1
      return RootResult(
        exitCode = if (isStop) 74 else 0,
        stdout = if (isStop) {
          "helper_stopped=0\nreadiness_removed=0\nlaunch_marker_removed=0\n"
        } else {
          "helper_ready=1\npanel_dark=1\npanel_confirmations=2\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class ResultSequencedVerifyRootExecutor(
    private val results: List<RootResult>
  ) : RootExecutor {
    var verificationCalls = 0
      private set

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      return results.getOrElse(verificationCalls++) { results.last() }
    }
  }

  private class BlockingBoundaryVerifyRootExecutor : RootExecutor {
    var verificationCalls = 0
      private set
    val boundaryVerificationStarted = CompletableDeferred<Unit>()
    val boundaryVerificationResult = CompletableDeferred<RootResult>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      verificationCalls += 1
      if (verificationCalls <= 1) return provenZeroResult()
      boundaryVerificationStarted.complete(Unit)
      return boundaryVerificationResult.await()
    }
  }

  private companion object {
    fun readyNoTouch(now: Long) = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )

    fun provenZeroResult() = RootResult(
      exitCode = 0,
      stdout = "helper_ready=1\npanel_dark=1\npanel_confirmations=2\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )

    fun unavailableResult() = RootResult(
      exitCode = 124,
      stdout = "",
      stderr = "root command timed out",
      command = "script",
      durationMs = TicketActionPanelDarkLease.PANEL_DARK_VERIFY_TIMEOUT_MILLIS
    )

    fun helperUnreadyResult() = RootResult(
      exitCode = 74,
      stdout = "helper_ready=0\npanel_dark=1\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )

    fun nonzeroPanelResult() = RootResult(
      exitCode = 1,
      stdout = "helper_ready=1\npanel_dark=0\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )
  }

}
