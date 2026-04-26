package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAutomationCoordinatorTest {
  @Test
  fun completionNotificationWaitsUntilSelectedIntervalBeforeNextRun() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = true)
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1))
    runCurrent()

    assertEquals(listOf("initial_start"), controller.startSpeedtestReasons)
    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH,
      store.load().runtimeState
    )
    advanceTimeBy(PhoneAutomationDispatchInterval.EVERY_60_SECONDS.intervalMillis + 1L)
    runCurrent()

    assertEquals(listOf("accepted_result"), controller.restartSpeedtestReasons)
    job.cancel()
  }

  @Test
  fun completionNotificationWithImmediateDispatchRestartsRightAway() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = false,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    controller.speedtestCompletionEvidence = SpeedtestCompletionEvidence(
      resultReadyAtMillis = testScheduler.currentTime + 2L
    )
    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1))
    repeat(6) { runCurrent() }
    advanceTimeBy(1_003L)
    runCurrent()

    assertEquals(listOf("accepted_result"), controller.restartSpeedtestReasons)
    assertTrue(controller.bringCellMapperForegroundReasons.contains("speedtest_started:accepted_result"))
    job.cancel()
  }

  @Test
  fun noNewResultWithinTimeoutQueuesRecoveryAfterFreshLaunch() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = false
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    advanceTimeBy(PhoneAutomationSpeedtestTiming.COMPLETION_RESULT_TIMEOUT_MILLIS + 1)
    runCurrent()

    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY, store.load().runtimeState)
    assertEquals(PhoneAutomationPendingRecoveryAction.RESTART, store.load().pendingRecoveryAction)
    job.cancel()
  }

  @Test
  fun cellMapperRecordingStopTriggersRecoveryAndForegroundRestore() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = false
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.cellMapperRecordingActive = false
    controller.cellMapperProbeState = CellMapperRecordingState.INACTIVE

    controller.emit(PhoneAutomationEvent.CellMapperRecordingStopped(observedAtMillis = testScheduler.currentTime + 1))
    runCurrent()

    assertEquals(listOf("recording_stopped"), controller.recoverCellMapperReasons)
    assertTrue(controller.bringCellMapperForegroundReasons.contains("post_recovery:recording_stopped"))
    job.cancel()
  }

  @Test
  fun speedtestAndCellMapperReturnsToOrchestratorAfterSuccessfulStartWhenEnabled() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = true
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(listOf("initial_start"), controller.startSpeedtestReasons)
    assertEquals(listOf("speedtest_started:initial_start"), controller.bringOrchestratorForegroundReasons)
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    job.cancel()
  }

  @Test
  fun immediateRestartReturnsToOrchestratorWhenEnabled() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = true,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    controller.speedtestCompletionEvidence = SpeedtestCompletionEvidence(
      resultReadyAtMillis = testScheduler.currentTime + 2L
    )
    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1))
    repeat(3) { runCurrent() }
    advanceTimeBy(1_003L)
    runCurrent()

    assertEquals(listOf("accepted_result"), controller.restartSpeedtestReasons)
    assertEquals(
      listOf(
        "speedtest_started:initial_start",
        "accepted_result",
        "speedtest_restarted:accepted_result"
      ),
      controller.bringOrchestratorForegroundReasons
    )
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    job.cancel()
  }

  @Test
  fun cellMapperRecoveryReturnsToOrchestratorWhenEnabled() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = true
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.cellMapperRecordingActive = false
    controller.cellMapperProbeState = CellMapperRecordingState.INACTIVE

    controller.emit(PhoneAutomationEvent.CellMapperRecordingStopped(observedAtMillis = testScheduler.currentTime + 1))
    runCurrent()

    assertEquals(listOf("recording_stopped"), controller.recoverCellMapperReasons)
    assertTrue(controller.bringCellMapperForegroundReasons.contains("post_recovery:recording_stopped"))
    assertTrue(
      controller.bringOrchestratorForegroundReasons.contains("cellmapper_recovered:recording_stopped")
    )
    job.cancel()
  }

  @Test
  fun preStartCellMapperRecoveryWaitsToReturnToOrchestratorUntilAfterSpeedtestLaunchFinishes() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = true
    )
    val controller = FakePhoneAutomationController().apply {
      cellMapperRecordingActive = false
      cellMapperProbeState = CellMapperRecordingState.INACTIVE
      startHandler = {
        assertTrue(bringOrchestratorForegroundReasons.isEmpty())
        PhoneAutomationActionResult(true, "started")
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(listOf("initial_start"), controller.startSpeedtestReasons)
    assertTrue(controller.bringCellMapperForegroundReasons.contains("post_recovery:initial_start"))
    assertEquals(listOf("speedtest_started:initial_start"), controller.bringOrchestratorForegroundReasons)
    job.cancel()
  }

  @Test
  fun returnToOrchestratorFailureAfterStartStaysInHealthyWaitingState() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = false,
      returnToOrchestratorAfterForegroundWork = true
    )
    val controller = FakePhoneAutomationController().apply {
      bringOrchestratorForegroundResult = PhoneAutomationActionResult(
        success = false,
        detail = "Orchestrator did not reach the foreground",
        failureMode = PhoneAutomationFailureMode.TRANSIENT
      )
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertTrue(job.isActive)
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    assertEquals(
      "Waiting for a new Speedtest result after initial_start. Return to the orchestrator failed: Orchestrator did not reach the foreground",
      store.load().runtimeDetail
    )
    assertEquals(listOf("speedtest_started:initial_start"), controller.bringOrchestratorForegroundReasons)
    job.cancel()
  }

  @Test
  fun serviceRestartDuringActiveSpeedtestRunResumesWaitingWithoutDuplicateLaunch() = runTest {
    advanceTimeBy(30_000L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 10_000L,
      speedtestState = SpeedtestActivityState.RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertTrue(controller.startSpeedtestReasons.isEmpty())
    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    job.cancel()
  }

  @Test
  fun serviceRestartDuringIdleWaitResumesWaitingWithoutDuplicateLaunch() = runTest {
    advanceTimeBy(30_000L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 20_000L,
      lastHandledCompletionAtMillis = 21_000L,
      lastAcceptedResultFingerprint = "result-1",
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertTrue(controller.startSpeedtestReasons.isEmpty())
    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH,
      store.load().runtimeState
    )
    advanceTimeBy(PhoneAutomationDispatchInterval.EVERY_60_SECONDS.intervalMillis + 1L)
    runCurrent()

    assertEquals(listOf("resume_waiting_dispatch"), controller.restartSpeedtestReasons)
    job.cancel()
  }

  @Test
  fun serviceRestartDuringIdleWaitWithImmediateDispatchRestartsWithoutWaiting() = runTest {
    advanceTimeBy(30_000L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
      lastRunStartedAtMillis = 20_000L,
      lastCompletionNotificationAtMillis = 21_000L,
      lastResultReadyAtMillis = 21_500L,
      lastHandledCompletionAtMillis = 21_000L,
      lastAcceptedResultFingerprint = "result-1",
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    repeat(6) { runCurrent() }

    assertEquals(listOf("resume_due_dispatch"), controller.restartSpeedtestReasons)
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    job.cancel()
  }

  @Test
  fun serviceRestartAfterTimedOutRunImmediatelyChoosesRestartPath() = runTest {
    advanceTimeBy(PhoneAutomationCoordinator.DEFAULT_DEAD_RUN_TIMEOUT_MILLIS + 1L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 1L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.WARM_IN_APP,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(listOf("completion_result_timeout"), controller.startSpeedtestReasons)
    job.cancel()
  }

  @Test
  fun listenerDisconnectWhileCellMapperActiveStaysUnknownUntilRetryWindow() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = true)
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.cellMapperProbeState = CellMapperRecordingState.UNKNOWN
    controller.emit(PhoneAutomationEvent.CellMapperRecordingStopped(observedAtMillis = testScheduler.currentTime + 1))
    runCurrent()

    assertTrue(controller.recoverCellMapperReasons.isEmpty())
    assertEquals(PhoneAutomationRuntimeState.RECOVERING_CELLMAPPER, store.load().runtimeState)
    assertEquals(CellMapperRecordingState.UNKNOWN, store.load().cellMapperState)
    job.cancel()
  }

  @Test
  fun listenerReconnectWithinRetryWindowReturnsToNormalWaiting() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = true)
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.cellMapperProbeState = CellMapperRecordingState.UNKNOWN
    controller.emit(PhoneAutomationEvent.CellMapperRecordingStopped(observedAtMillis = testScheduler.currentTime + 1))
    runCurrent()

    controller.cellMapperProbeState = CellMapperRecordingState.ACTIVE
    advanceTimeBy(2_000L)
    runCurrent()

    assertTrue(controller.recoverCellMapperReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    job.cancel()
  }

  @Test
  fun transientFailuresStopAfterThirdRetryAndLeaveBlockedState() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = true)
    val controller = FakePhoneAutomationController().apply {
      notificationBootstrapReady = false
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    advanceTimeBy(2_000L)
    runCurrent()
    advanceTimeBy(5_000L)
    runCurrent()
    advanceTimeBy(15_000L)
    runCurrent()

    assertEquals(PhoneAutomationSetupState.SETUP_BLOCKED, store.load().setupState)
    assertEquals(PhoneAutomationRuntimeState.SETUP_BLOCKED, store.load().runtimeState)
    assertTrue(store.load().pendingRecoveryReason.contains("Temporary issue did not recover"))
    assertFalse(job.isActive)
  }

  @Test
  fun failedSpeedtestLaunchDoesNotMarkRunActiveOrReturnToCellMapper() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = true)
    val controller = FakePhoneAutomationController().apply {
      startResult = PhoneAutomationActionResult(
        success = false,
        detail = "Speedtest launch did not stick",
        failureMode = PhoneAutomationFailureMode.TRANSIENT,
        retryExhaustionDisposition = PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR
      )
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(0L, store.load().lastRunStartedAtMillis)
    assertEquals(SpeedtestActivityState.NOT_RUNNING, store.load().speedtestState)
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    assertEquals(
      "Speedtest launch did not stick Retrying in 2s",
      store.load().runtimeDetail
    )
    job.cancel()
  }

  @Test
  fun repeatedFreshLaunchFailuresBlockSetupDuringStartupRecovery() = runTest {
    advanceTimeBy(PhoneAutomationCoordinator.DEFAULT_DEAD_RUN_TIMEOUT_MILLIS + 1L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 1L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.WARM_IN_APP,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      startHandler = {
        PhoneAutomationActionResult(
          success = false,
          detail = "Speedtest was interrupted when the orchestrator screen returned",
          failureMode = PhoneAutomationFailureMode.TRANSIENT,
          failureCategory = PhoneAutomationFailureCategory.GENERIC,
          retryExhaustionDisposition = PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR
        )
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    advanceTimeBy(2_001L)
    runCurrent()
    advanceTimeBy(5_001L)
    runCurrent()
    advanceTimeBy(15_001L)
    runCurrent()

    assertEquals(PhoneAutomationSetupState.SETUP_BLOCKED, store.load().setupState)
    assertEquals(PhoneAutomationRuntimeState.SETUP_BLOCKED, store.load().runtimeState)
    assertEquals(
      "Temporary issue did not recover: Timed-out Speedtest run could not be recovered",
      store.load().runtimeDetail
    )
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    assertEquals(
      listOf(
        "completion_result_timeout",
        "completion_result_timeout",
        "completion_result_timeout",
        "completion_result_timeout"
      ),
      controller.startSpeedtestReasons
    )
    job.cancel()
  }

  @Test
  fun cancelledRestartEndsInRuntimeErrorAndWaitsForNextDispatch() = runTest {
    advanceTimeBy(PhoneAutomationCoordinator.DEFAULT_DEAD_RUN_TIMEOUT_MILLIS + 1L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 1L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.WARM_IN_APP,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      startHandler = {
        PhoneAutomationActionResult(
          success = false,
          detail = "Speedtest launch was cancelled before proof completed",
          failureMode = PhoneAutomationFailureMode.TRANSIENT,
          failureCategory = PhoneAutomationFailureCategory.CANCELLATION,
          failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
          retryExhaustionDisposition = PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR
        )
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY, store.load().runtimeState)
    assertEquals(
      "Recovering from Speedtest failure: Speedtest launch was cancelled before proof completed",
      store.load().runtimeDetail
    )
    assertEquals(0L, store.load().lastRunStartedAtMillis)
    assertEquals(SpeedtestActivityState.NOT_RUNNING, store.load().speedtestState)
    assertEquals(listOf("completion_result_timeout"), controller.cleanupFailedSpeedtestLaunchReasons)
    assertEquals(listOf(true), controller.cleanupRestoreCellMapperRequests)
    assertEquals(
      listOf("failed_speedtest_launch:completion_result_timeout"),
      controller.bringCellMapperForegroundReasons
    )

    advanceTimeBy(5_001L)
    runCurrent()
    assertEquals(2, controller.startSpeedtestReasons.size)
    job.cancel()
  }

  @Test
  fun stuckConnectingRestartEndsInRuntimeErrorAndWaitsForNextDispatch() = runTest {
    advanceTimeBy(PhoneAutomationCoordinator.DEFAULT_DEAD_RUN_TIMEOUT_MILLIS + 1L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 1L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.WARM_IN_APP,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      startHandler = {
        PhoneAutomationActionResult(
          success = false,
          detail = "Speedtest relaunch also stayed on Connecting...",
          failureMode = PhoneAutomationFailureMode.TRANSIENT,
          failureCategory = PhoneAutomationFailureCategory.STUCK_CONNECTING,
          failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
          retryExhaustionDisposition = PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR
        )
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(PhoneAutomationSetupState.READY, store.load().setupState)
    assertEquals(PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY, store.load().runtimeState)
    assertEquals(
      "Recovering from Speedtest failure: Speedtest relaunch also stayed on Connecting...",
      store.load().runtimeDetail
    )
    assertEquals(0L, store.load().lastRunStartedAtMillis)
    assertEquals(SpeedtestActivityState.NOT_RUNNING, store.load().speedtestState)
    assertEquals(listOf("completion_result_timeout"), controller.cleanupFailedSpeedtestLaunchReasons)
    assertEquals(listOf(true), controller.cleanupRestoreCellMapperRequests)
    assertTrue(
      controller.bringCellMapperForegroundReasons.contains(
        "failed_speedtest_launch:completion_result_timeout"
      )
    )

    advanceTimeBy(5_001L)
    runCurrent()
    assertEquals(2, controller.startSpeedtestReasons.size)
    job.cancel()
  }

  @Test
  fun runtimeErrorWithImmediateDispatchRetriesRightAway() = runTest {
    advanceTimeBy(PhoneAutomationSpeedtestTiming.COMPLETION_RESULT_TIMEOUT_MILLIS + 1L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
      lastRunStartedAtMillis = 1L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.WARM_IN_APP,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    var startAttempts = 0
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      startHandler = {
        startAttempts += 1
        if (startAttempts == 1) {
          PhoneAutomationActionResult(
            success = false,
            detail = "Speedtest relaunch also stayed on Connecting...",
            failureMode = PhoneAutomationFailureMode.TRANSIENT,
            failureCategory = PhoneAutomationFailureCategory.STUCK_CONNECTING,
            failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
            retryExhaustionDisposition = PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR
          )
        } else {
          PhoneAutomationActionResult(true, "started")
        }
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    repeat(5) { runCurrent() }

    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
      store.load().runtimeState
    )
    assertEquals(
      "Recovering from Speedtest failure: Speedtest relaunch also stayed on Connecting...",
      store.load().runtimeDetail
    )
    assertEquals(
      PhoneAutomationPendingRecoveryAction.RESTART,
      store.load().pendingRecoveryAction
    )
    assertEquals("runtime_error_recovery", store.load().pendingRecoveryReason)

    advanceTimeBy(5_001L)
    repeat(5) { runCurrent() }

    assertEquals(
      listOf("completion_result_timeout", "runtime_error_recovery"),
      controller.startSpeedtestReasons
    )
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    assertEquals(
      PhoneAutomationPendingRecoveryAction.NONE,
      store.load().pendingRecoveryAction
    )
    job.cancel()
  }

  @Test
  fun interruptProtectedHandoffCancelsRestartAndKeepsAppReadyForNextDispatch() = runTest {
    advanceTimeBy(PhoneAutomationCoordinator.DEFAULT_DEAD_RUN_TIMEOUT_MILLIS + 1L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 1L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.WARM_IN_APP,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE,
      clockMillis = { testScheduler.currentTime }
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      startHandler = {
        awaitCancellation()
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST, store.load().runtimeState)
    assertTrue(
      coordinator.interruptProtectedHandoff(
        "Speedtest restart was interrupted because the app was opened"
      )
    )
    runCurrent()

    assertTrue(job.isActive)
    assertEquals(PhoneAutomationSetupState.READY, store.load().setupState)
    assertEquals(PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY, store.load().runtimeState)
    assertEquals(
      "Recovering from Speedtest failure: Speedtest restart was interrupted because the app was opened",
      store.load().runtimeDetail
    )
    assertEquals(0L, store.load().lastRunStartedAtMillis)
    assertEquals(SpeedtestActivityState.NOT_RUNNING, store.load().speedtestState)
    assertEquals(listOf("protected_handoff_interrupted"), controller.cleanupFailedSpeedtestLaunchReasons)
    assertEquals(listOf(false), controller.cleanupRestoreCellMapperRequests)
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())

    advanceTimeBy(5_001L)
    runCurrent()
    assertEquals(2, controller.startSpeedtestReasons.size)
    job.cancel()
  }

  @Test
  fun protectedHandoffTimeoutCancelsRestartAndLeavesRuntimeError() = runTest {
    advanceTimeBy(PhoneAutomationCoordinator.DEFAULT_DEAD_RUN_TIMEOUT_MILLIS + 1L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 1L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.WARM_IN_APP,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE,
      clockMillis = { testScheduler.currentTime }
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      startHandler = {
        awaitCancellation()
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST, store.load().runtimeState)
    advanceTimeBy(PhoneAutomationCoordinator.PROTECTED_HANDOFF_TIMEOUT_MILLIS)
    runCurrent()

    assertTrue(job.isActive)
    assertEquals(PhoneAutomationSetupState.READY, store.load().setupState)
    assertEquals(PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY, store.load().runtimeState)
    assertEquals(
      "Recovering from Speedtest failure: Speedtest restart took too long and was stopped",
      store.load().runtimeDetail
    )
    assertEquals(SpeedtestActivityState.NOT_RUNNING, store.load().speedtestState)
    assertEquals(listOf("protected_handoff_timeout"), controller.cleanupFailedSpeedtestLaunchReasons)
    assertEquals(listOf(true), controller.cleanupRestoreCellMapperRequests)
    assertTrue(
      controller.bringCellMapperForegroundReasons.contains(
        "failed_speedtest_launch:protected_handoff_timeout"
      )
    )

    advanceTimeBy(4_999L)
    runCurrent()
    assertEquals(1, controller.startSpeedtestReasons.size)
    advanceTimeBy(2L)
    runCurrent()
    assertEquals(2, controller.startSpeedtestReasons.size)
    job.cancel()
  }

  @Test
  fun retryButtonPathIsPreferredWhenAvailable() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = true)
    val controller = FakePhoneAutomationController().apply {
      restartResult = PhoneAutomationRestartResult(
        success = true,
        path = PhoneAutomationRestartPath.RETRY_BUTTON,
        detail = "retry"
      )
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1))
    advanceTimeBy(PhoneAutomationDispatchInterval.EVERY_60_SECONDS.intervalMillis + 1L)
    runCurrent()

    assertEquals(PhoneAutomationRestartPath.RETRY_BUTTON, controller.lastRestartPath)
    job.cancel()
  }

  @Test
  fun relaunchFallbackIsUsedWhenRetryIsUnavailable() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = true)
    val controller = FakePhoneAutomationController().apply {
      restartResult = PhoneAutomationRestartResult(
        success = true,
        path = PhoneAutomationRestartPath.RELAUNCH_FALLBACK,
        detail = "fallback"
      )
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1))
    advanceTimeBy(PhoneAutomationDispatchInterval.EVERY_60_SECONDS.intervalMillis + 1L)
    runCurrent()

    assertEquals(PhoneAutomationRestartPath.RELAUNCH_FALLBACK, controller.lastRestartPath)
    job.cancel()
  }

  @Test
  fun speedtestOnlyModeStartsWithoutCellMapperRecovery() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = false)
    val controller = FakePhoneAutomationController().apply {
      cellMapperRecordingActive = false
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(listOf("initial_start"), controller.startSpeedtestReasons)
    assertTrue(controller.recoverCellMapperReasons.isEmpty())
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    job.cancel()
  }

  @Test
  fun speedtestOnlyModeReturnsToOrchestratorWhenEnabled() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = false,
      returnToOrchestratorAfterForegroundWork = true
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(listOf("speedtest_started:initial_start"), controller.bringOrchestratorForegroundReasons)
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    job.cancel()
  }

  @Test
  fun speedtestOnlyCompletionWaitsThenRestartsWithoutCellMapperForegroundRestore() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = false)
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1))
    advanceTimeBy(PhoneAutomationDispatchInterval.EVERY_60_SECONDS.intervalMillis + 1L)
    runCurrent()

    assertEquals(listOf("accepted_result"), controller.restartSpeedtestReasons)
    assertTrue(controller.recoverCellMapperReasons.isEmpty())
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    job.cancel()
  }

  @Test
  fun speedtestOnlyCompletionWithImmediateDispatchRestartsRightAwayWithoutCellMapperForegroundRestore() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = false,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE
    )
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.speedtestCompletionEvidence = SpeedtestCompletionEvidence(
      resultReadyAtMillis = testScheduler.currentTime + 2L
    )
    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1))
    repeat(3) { runCurrent() }
    advanceTimeBy(1_003L)
    runCurrent()

    assertEquals(listOf("accepted_result"), controller.restartSpeedtestReasons)
    assertTrue(controller.recoverCellMapperReasons.isEmpty())
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    job.cancel()
  }

  @Test
  fun immediateCompletionNotificationRestartsAfterGraceWithoutWaitingForResultProof() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      returnToOrchestratorAfterForegroundWork = false,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE
    )
    val controller = FakePhoneAutomationController().apply {
      autoCompleteWithFreshResult = false
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    val completionObservedAt = testScheduler.currentTime + 1L
    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = completionObservedAt))
    runCurrent()

    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH,
      store.load().runtimeState
    )
    assertEquals(
      listOf("completion_notification"),
      controller.bringSpeedtestForegroundReasons
    )

    advanceTimeBy(1_001L)
    runCurrent()

    assertEquals(listOf("initial_start", "accepted_result"), controller.startSpeedtestReasons)
    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    job.cancel()
  }

  @Test
  fun startupResumeWithStaleResultProofDoesNotTreatItAsFreshResult() = runTest {
    advanceTimeBy(30_000L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
      lastRunStartedAtMillis = 10_000L,
      lastAcceptedResultFingerprint = "result-1",
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      speedtestCompletionEvidence = SpeedtestCompletionEvidence(
        resultReadyAtMillis = 25_000L,
        resultFingerprint = "result-1"
      )
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertTrue(controller.startSpeedtestReasons.isEmpty())
    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
      store.load().runtimeState
    )
    assertEquals(0L, store.load().lastCompletionNotificationAtMillis)
    assertEquals(0L, store.load().lastResultReadyAtMillis)
    assertEquals(
      PhoneAutomationPendingRecoveryAction.RESTART,
      store.load().pendingRecoveryAction
    )
    job.cancel()
  }

  @Test
  fun immediateRestartedFreshLaunchTimeoutQueuesRecoveryWhenNoNewResultArrives() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = false,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE
    )
    val controller = FakePhoneAutomationController().apply {
      autoCompleteWithFreshResult = false
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1L))
    runCurrent()

    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH,
      store.load().runtimeState
    )
    assertTrue(controller.restartSpeedtestReasons.isEmpty())

    advanceTimeBy(1_001L)
    runCurrent()

    assertEquals(listOf("accepted_result"), controller.startSpeedtestReasons.drop(1))
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )

    val remainingUntilTimeout = (
      store.load().lastRunStartedAtMillis +
        PhoneAutomationSpeedtestTiming.COMPLETION_RESULT_TIMEOUT_MILLIS -
        testScheduler.currentTime
      ).coerceAtLeast(1L)
    advanceTimeBy(remainingUntilTimeout)
    runCurrent()

    assertEquals(listOf("initial_start", "accepted_result"), controller.startSpeedtestReasons)
    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
      store.load().runtimeState
    )
    assertEquals(
      PhoneAutomationPendingRecoveryAction.RESTART,
      store.load().pendingRecoveryAction
    )
    job.cancel()
  }

  @Test
  fun serviceRestartDuringCompletionSettlingAcceptsNotificationAndResumesImmediately() = runTest {
    advanceTimeBy(45_000L)
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
      lastRunStartedAtMillis = 1L,
      lastCompletionNotificationAtMillis = 20_000L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.COLD_FRESH_LAUNCH,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
      speedtestCompletionEvidence = SpeedtestCompletionEvidence(
        completionNotificationAtMillis = 20_000L
      )
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )

    assertEquals(listOf("accepted_result"), controller.startSpeedtestReasons)
    assertEquals(PhoneAutomationPendingRecoveryAction.NONE, store.load().pendingRecoveryAction)
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    job.cancel()
  }

  @Test
  fun timeoutFollowedByTransientRestartFailureQueuesRecoveryAndAutoRestarts() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = false,
      dispatchInterval = PhoneAutomationDispatchInterval.IMMEDIATE,
      clockMillis = { testScheduler.currentTime }
    )
    var startAttempts = 0
    val controller = FakePhoneAutomationController().apply {
      autoCompleteWithFreshResult = false
      startHandler = {
        if (it == "initial_start") {
          PhoneAutomationActionResult(true, "started")
        } else {
          startAttempts += 1
          if (startAttempts == 1) {
            PhoneAutomationActionResult(
              success = false,
              detail = "Speedtest relaunch also stayed on Connecting...",
              failureMode = PhoneAutomationFailureMode.TRANSIENT,
              failureCategory = PhoneAutomationFailureCategory.STUCK_CONNECTING,
              failureCleanupDisposition = PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
              retryExhaustionDisposition = PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR
            )
          } else {
            PhoneAutomationActionResult(true, "started")
          }
        }
      }
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    controller.emit(PhoneAutomationEvent.SpeedtestCompleted(observedAtMillis = testScheduler.currentTime + 1L))
    runCurrent()

    advanceTimeBy(1_001L)
    runCurrent()

    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
      store.load().runtimeState
    )
    assertEquals(
      "Recovering from Speedtest failure: Speedtest relaunch also stayed on Connecting...",
      store.load().runtimeDetail
    )
    assertEquals(
      PhoneAutomationPendingRecoveryAction.RESTART,
      store.load().pendingRecoveryAction
    )
    assertEquals("runtime_error_recovery", store.load().pendingRecoveryReason)

    advanceTimeBy(5_001L)
    repeat(4) { runCurrent() }

    assertEquals(
      listOf("initial_start", "accepted_result", "runtime_error_recovery"),
      controller.startSpeedtestReasons
    )
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    assertEquals(PhoneAutomationPendingRecoveryAction.NONE, store.load().pendingRecoveryAction)
    job.cancel()
  }

  @Test
  fun startupPendingRecoveryResumesRestartAutomatically() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(
      enabled = true,
      maintainCellMapper = true,
      lastRunStartedAtMillis = 1L,
      speedtestState = SpeedtestActivityState.NOT_RUNNING,
      cellMapperState = CellMapperRecordingState.ACTIVE,
      pendingRecoveryAction = PhoneAutomationPendingRecoveryAction.RESTART,
      pendingRecoveryReason = "runtime_error_recovery",
      clockMillis = { testScheduler.currentTime }
    )
    val controller = FakePhoneAutomationController().apply {
      speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()

    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
      store.load().runtimeState
    )
    advanceTimeBy(PhoneAutomationCoordinator.PENDING_RECOVERY_DELAY_MILLIS + 1L)
    runCurrent()

    assertEquals(listOf("runtime_error_recovery"), controller.startSpeedtestReasons)
    assertEquals(
      PhoneAutomationPendingRecoveryAction.NONE,
      store.load().pendingRecoveryAction
    )
    assertEquals(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT,
      store.load().runtimeState
    )
    job.cancel()
  }

  @Test
  fun speedtestOnlyFreshLaunchTimeoutQueuesRecoveryWithoutCellMapperForegroundRestore() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = false)
    val controller = FakePhoneAutomationController()
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    advanceTimeBy(PhoneAutomationSpeedtestTiming.COMPLETION_RESULT_TIMEOUT_MILLIS + 1)
    runCurrent()

    assertTrue(controller.restartSpeedtestReasons.isEmpty())
    assertEquals(PhoneAutomationPendingRecoveryAction.RESTART, store.load().pendingRecoveryAction)
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    job.cancel()
  }

  @Test
  fun speedtestOnlyModeIgnoresCellMapperRecordingStoppedEvents() = runTest {
    val store = InMemoryPhoneAutomationSettingsStore(enabled = true, maintainCellMapper = false)
    val controller = FakePhoneAutomationController().apply {
      cellMapperRecordingActive = false
    }
    val coordinator = PhoneAutomationCoordinator(
      settingsStore = store,
      controller = controller,
      mode = PhoneAutomationMode.SPEEDTEST_ONLY,
      clockMillis = { testScheduler.currentTime }
    )

    val job = backgroundScope.launch { coordinator.run() }
    runCurrent()
    controller.emit(PhoneAutomationEvent.CellMapperRecordingStopped(observedAtMillis = testScheduler.currentTime + 1))
    runCurrent()

    assertTrue(controller.recoverCellMapperReasons.isEmpty())
    assertTrue(controller.bringCellMapperForegroundReasons.isEmpty())
    job.cancel()
  }
}

private class FakePhoneAutomationController : PhoneAutomationController {
  private val mutableEvents = MutableSharedFlow<PhoneAutomationEvent>(extraBufferCapacity = 16)
  private var fingerprintCounter: Int = 0
  private var synthesizedBlankEvidenceKey: Triple<Long, Long, Boolean>? = null
  private var synthesizedBlankEvidenceFingerprint: String = ""

  override val events: Flow<PhoneAutomationEvent> = mutableEvents

  var prepareResult = PhoneAutomationPreparationResult(ready = true, detail = "ready")
  var recoverResult = PhoneAutomationActionResult(true, "recovered")
  var startResult = PhoneAutomationActionResult(true, "started")
  var restartResult = PhoneAutomationRestartResult(true, PhoneAutomationRestartPath.RETRY_BUTTON, "retry")
  var bringCellMapperForegroundResult = PhoneAutomationActionResult(true, "foreground")
  var bringOrchestratorForegroundResult = PhoneAutomationActionResult(true, "foreground")
  var startHandler: (suspend (String) -> PhoneAutomationActionResult)? = null
  var restartHandler: (suspend (String) -> PhoneAutomationRestartResult)? = null
  var completionEvidenceHandler: (suspend (Long) -> SpeedtestCompletionEvidence)? = null
  var notificationBootstrapReady = true
  var speedtestProbeState = SpeedtestActivityState.NOT_RUNNING
  var cellMapperProbeState = CellMapperRecordingState.ACTIVE
  var cellMapperRecordingActive = true
  var speedtestCompletionEvidence = SpeedtestCompletionEvidence()
  var autoCompleteWithFreshResult = true
  var lastRestartPath = PhoneAutomationRestartPath.FAILED
  val startSpeedtestReasons = mutableListOf<String>()
  val restartSpeedtestReasons = mutableListOf<String>()
  val recoverCellMapperReasons = mutableListOf<String>()
  val cleanupFailedSpeedtestLaunchReasons = mutableListOf<String>()
  val cleanupRestoreCellMapperRequests = mutableListOf<Boolean>()
  val bringSpeedtestForegroundReasons = mutableListOf<String>()
  val bringCellMapperForegroundReasons = mutableListOf<String>()
  val bringOrchestratorForegroundReasons = mutableListOf<String>()
  val preparedModes = mutableListOf<PhoneAutomationMode>()

  override suspend fun prepare(mode: PhoneAutomationMode): PhoneAutomationPreparationResult {
    preparedModes += mode
    return prepareResult
  }

  override suspend fun speedtestState(): SpeedtestActivityState = speedtestProbeState

  override suspend fun speedtestCompletionEvidence(runStartedAtMillis: Long): SpeedtestCompletionEvidence {
    completionEvidenceHandler?.let { return it(runStartedAtMillis) }
    return normalizedCompletionEvidence(speedtestCompletionEvidence)
  }

  override suspend fun cellMapperState(): CellMapperRecordingState = cellMapperProbeState

  override suspend fun awaitNotificationBootstrap(timeoutMillis: Long): Boolean = notificationBootstrapReady

  override fun isCellMapperRecordingActive(): Boolean = cellMapperRecordingActive

  override suspend fun recoverCellMapper(reason: String): PhoneAutomationActionResult {
    recoverCellMapperReasons += reason
    if (recoverResult.success) {
      cellMapperRecordingActive = true
      cellMapperProbeState = CellMapperRecordingState.ACTIVE
    }
    return recoverResult
  }

  override suspend fun startSpeedtest(reason: String): PhoneAutomationActionResult {
    startSpeedtestReasons += reason
    startHandler?.let { return it(reason) }
    if (startResult.success) {
      speedtestProbeState = SpeedtestActivityState.RUNNING
      speedtestCompletionEvidence = SpeedtestCompletionEvidence()
      synthesizedBlankEvidenceKey = null
      synthesizedBlankEvidenceFingerprint = ""
    }
    return startResult
  }

  override suspend fun restartSpeedtest(reason: String): PhoneAutomationRestartResult {
    restartSpeedtestReasons += reason
    restartHandler?.let { return it(reason) }
    lastRestartPath = restartResult.path
    if (restartResult.success) {
      speedtestProbeState = SpeedtestActivityState.RUNNING
      speedtestCompletionEvidence = SpeedtestCompletionEvidence()
      synthesizedBlankEvidenceKey = null
      synthesizedBlankEvidenceFingerprint = ""
    }
    return restartResult
  }

  override suspend fun cleanupFailedSpeedtestLaunch(
    reason: String,
    restoreCellMapper: Boolean
  ): PhoneAutomationActionResult {
    cleanupFailedSpeedtestLaunchReasons += reason
    cleanupRestoreCellMapperRequests += restoreCellMapper
    return if (restoreCellMapper) {
      bringCellMapperToForeground("failed_speedtest_launch:$reason")
    } else {
      PhoneAutomationActionResult(true, "cleanup")
    }
  }

  override suspend fun bringCellMapperToForeground(reason: String): PhoneAutomationActionResult {
    bringCellMapperForegroundReasons += reason
    return bringCellMapperForegroundResult
  }

  override suspend fun bringSpeedtestToForeground(reason: String): PhoneAutomationActionResult {
    bringSpeedtestForegroundReasons += reason
    return PhoneAutomationActionResult(true, "foreground")
  }

  override suspend fun bringOrchestratorToForeground(reason: String): PhoneAutomationActionResult {
    bringOrchestratorForegroundReasons += reason
    return bringOrchestratorForegroundResult
  }

  fun emit(event: PhoneAutomationEvent) {
    if (
      autoCompleteWithFreshResult &&
      event is PhoneAutomationEvent.SpeedtestCompleted &&
      completionEvidenceHandler == null &&
      speedtestCompletionEvidence == SpeedtestCompletionEvidence()
    ) {
      speedtestCompletionEvidence = SpeedtestCompletionEvidence(
        completionNotificationAtMillis = event.observedAtMillis,
        resultReadyAtMillis = event.observedAtMillis + 1L,
        resultFingerprint = nextFingerprint()
      )
    }
    mutableEvents.tryEmit(event)
  }

  private fun normalizedCompletionEvidence(evidence: SpeedtestCompletionEvidence): SpeedtestCompletionEvidence {
    if (evidence.resultReadyAtMillis <= 0L || evidence.resultFingerprint.isNotBlank()) {
      if (evidence.resultReadyAtMillis <= 0L) {
        synthesizedBlankEvidenceKey = null
        synthesizedBlankEvidenceFingerprint = ""
      }
      return evidence
    }
    val key = Triple(
      evidence.completionNotificationAtMillis,
      evidence.resultReadyAtMillis,
      evidence.resultScreenVisible
    )
    if (synthesizedBlankEvidenceKey != key) {
      synthesizedBlankEvidenceKey = key
      synthesizedBlankEvidenceFingerprint = nextFingerprint()
    }
    return evidence.copy(resultFingerprint = synthesizedBlankEvidenceFingerprint)
  }

  private fun nextFingerprint(): String {
    fingerprintCounter += 1
    return "result-$fingerprintCounter"
  }
}

private class InMemoryPhoneAutomationSettingsStore(
  enabled: Boolean,
  maintainCellMapper: Boolean = true,
  returnToOrchestratorAfterForegroundWork: Boolean = true,
  dispatchInterval: PhoneAutomationDispatchInterval = PhoneAutomationDispatchInterval.default,
  touchBrightnessEnabled: Boolean = false,
  lastRunStartedAtMillis: Long = 0L,
  lastCompletionNotificationAtMillis: Long = 0L,
  lastResultReadyAtMillis: Long = 0L,
  lastHandledCompletionAtMillis: Long = 0L,
  currentRunLaunchMode: SpeedtestRunLaunchMode = SpeedtestRunLaunchMode.NONE,
  lastAcceptedResultFingerprint: String = "",
  speedtestState: SpeedtestActivityState = SpeedtestActivityState.UNKNOWN,
  cellMapperState: CellMapperRecordingState = CellMapperRecordingState.UNKNOWN,
  pendingRecoveryAction: PhoneAutomationPendingRecoveryAction = PhoneAutomationPendingRecoveryAction.NONE,
  pendingRecoveryReason: String = "",
  pendingRecoveryNotBeforeAtMillis: Long = 0L,
  pendingRecoveryPhase: PhoneAutomationPendingRecoveryPhase = when {
    pendingRecoveryAction == PhoneAutomationPendingRecoveryAction.NONE -> {
      PhoneAutomationPendingRecoveryPhase.NONE
    }

    pendingRecoveryNotBeforeAtMillis > 0L -> {
      PhoneAutomationPendingRecoveryPhase.QUEUED_RETRY
    }

    else -> {
      PhoneAutomationPendingRecoveryPhase.HANDOFF_RESUME
    }
  },
  pendingRecoveryToken: String = "",
  private val clockMillis: () -> Long = { 0L }
) : PhoneAutomationSettingsStore {
  private var snapshot = PhoneAutomationSettingsSnapshot(
    enabled = enabled,
    maintainCellMapper = maintainCellMapper,
    returnToOrchestratorAfterForegroundWork = returnToOrchestratorAfterForegroundWork,
    dispatchInterval = dispatchInterval,
    touchBrightnessEnabled = touchBrightnessEnabled,
    lastRunStartedAtMillis = lastRunStartedAtMillis,
    lastCompletionNotificationAtMillis = lastCompletionNotificationAtMillis,
    lastResultReadyAtMillis = lastResultReadyAtMillis,
    lastHandledCompletionAtMillis = lastHandledCompletionAtMillis,
    currentRunLaunchMode = currentRunLaunchMode,
    lastAcceptedResultFingerprint = lastAcceptedResultFingerprint,
    currentAttemptId = if (lastRunStartedAtMillis > 0L) {
      "${lastRunStartedAtMillis}_${currentRunLaunchMode.wireName}"
    } else {
      ""
    },
    currentAttemptStartProofAtMillis = lastRunStartedAtMillis,
    currentAttemptResultScreenClearedAtMillis = lastRunStartedAtMillis,
    speedtestState = speedtestState,
    cellMapperState = cellMapperState,
    pendingRecoveryAction = pendingRecoveryAction,
    pendingRecoveryPhase = pendingRecoveryPhase,
    pendingRecoveryReason = pendingRecoveryReason,
    pendingRecoveryNotBeforeAtMillis = pendingRecoveryNotBeforeAtMillis,
    pendingRecoveryToken = pendingRecoveryToken
  )

  override fun load(): PhoneAutomationSettingsSnapshot = snapshot

  override fun setEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      enabled = enabled,
      protectedHandoffStartedAtMillis = 0L,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun setMaintainCellMapper(maintainCellMapper: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      maintainCellMapper = maintainCellMapper,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun setReturnToOrchestratorAfterForegroundWork(
    returnToOrchestratorAfterForegroundWork: Boolean
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      returnToOrchestratorAfterForegroundWork = returnToOrchestratorAfterForegroundWork,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun setDispatchInterval(dispatchInterval: PhoneAutomationDispatchInterval): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      dispatchInterval = dispatchInterval,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun setTouchBrightnessEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessEnabled = enabled,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun updateSetupState(
    state: PhoneAutomationSetupState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      setupState = state,
      setupDetail = detail,
      lastReadyAtMillis = if (state == PhoneAutomationSetupState.READY) {
        clockMillis()
      } else {
        snapshot.lastReadyAtMillis
      },
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun updateRuntimeState(
    state: PhoneAutomationRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    val protectedHandoffStartedAtMillis = when {
      !snapshot.enabled -> 0L
      state == PhoneAutomationRuntimeState.STARTING ||
        state == PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST -> {
        if (
          snapshot.runtimeState == state &&
          snapshot.protectedHandoffStartedAtMillis > 0L
        ) {
          snapshot.protectedHandoffStartedAtMillis
        } else {
          clockMillis()
        }
      }

      else -> 0L
    }
    snapshot = snapshot.copy(
      runtimeState = state,
      runtimeDetail = detail,
      protectedHandoffStartedAtMillis = protectedHandoffStartedAtMillis,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun updateTouchBrightnessState(
    state: TouchBrightnessRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessState = state,
      touchBrightnessDetail = detail,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun updateTouchBrightnessDebugDetail(detail: String): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessDebugDetail = detail,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun saveTouchBrightnessRestoreState(
    mode: Int?,
    value: Int?
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessRestoreMode = mode,
      touchBrightnessRestoreValue = value,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun clearTouchBrightnessRestoreState(): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessRestoreMode = null,
      touchBrightnessRestoreValue = null,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun updateCycleState(
    lastRunStartedAtMillis: Long,
    lastCompletionNotificationAtMillis: Long,
    lastResultReadyAtMillis: Long,
    lastHandledCompletionAtMillis: Long,
    currentRunLaunchMode: SpeedtestRunLaunchMode,
    lastAcceptedResultFingerprint: String,
    speedtestState: SpeedtestActivityState,
    cellMapperState: CellMapperRecordingState,
    pendingRecoveryReason: String,
    currentAttemptId: String,
    currentAttemptStartProofAtMillis: Long,
    currentAttemptResultScreenClearedAtMillis: Long
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      lastRunStartedAtMillis = lastRunStartedAtMillis,
      lastCompletionNotificationAtMillis = lastCompletionNotificationAtMillis,
      lastResultReadyAtMillis = lastResultReadyAtMillis,
      lastHandledCompletionAtMillis = lastHandledCompletionAtMillis,
      currentRunLaunchMode = currentRunLaunchMode,
      lastAcceptedResultFingerprint = lastAcceptedResultFingerprint,
      currentAttemptId = currentAttemptId,
      currentAttemptStartProofAtMillis = currentAttemptStartProofAtMillis,
      currentAttemptResultScreenClearedAtMillis = currentAttemptResultScreenClearedAtMillis,
      speedtestState = speedtestState,
      cellMapperState = cellMapperState,
      pendingRecoveryReason = pendingRecoveryReason,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun updatePendingRecovery(
    action: PhoneAutomationPendingRecoveryAction,
    reason: String,
    phase: PhoneAutomationPendingRecoveryPhase,
    notBeforeAtMillis: Long,
    token: String
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      pendingRecoveryAction = action,
      pendingRecoveryPhase = phase,
      pendingRecoveryReason = reason,
      pendingRecoveryNotBeforeAtMillis = notBeforeAtMillis,
      pendingRecoveryToken = token,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun clearCycleState(): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      lastRunStartedAtMillis = 0L,
      lastCompletionNotificationAtMillis = 0L,
      lastResultReadyAtMillis = 0L,
      lastHandledCompletionAtMillis = 0L,
      currentRunLaunchMode = SpeedtestRunLaunchMode.NONE,
      currentAttemptId = "",
      currentAttemptStartProofAtMillis = 0L,
      currentAttemptResultScreenClearedAtMillis = 0L,
      speedtestState = SpeedtestActivityState.UNKNOWN,
      cellMapperState = CellMapperRecordingState.UNKNOWN,
      pendingRecoveryPhase = PhoneAutomationPendingRecoveryPhase.NONE,
      pendingRecoveryReason = "",
      pendingRecoveryNotBeforeAtMillis = 0L,
      pendingRecoveryToken = "",
      protectedHandoffStartedAtMillis = 0L,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun recordTransientFailure(
    reason: String,
    observedAtMillis: Long
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      pendingRecoveryReason = reason,
      transientFailureCount = snapshot.transientFailureCount + 1,
      lastTransientFailureAtMillis = observedAtMillis,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }

  override fun clearTransientFailureTracking(): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      pendingRecoveryReason = if (
        snapshot.pendingRecoveryAction != PhoneAutomationPendingRecoveryAction.NONE
      ) {
        snapshot.pendingRecoveryReason
      } else {
        ""
      },
      transientFailureCount = 0,
      lastTransientFailureAtMillis = 0L,
      updatedAtMillis = maxOf(clockMillis(), 1L)
    )
    return snapshot
  }
}
