package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhoneAutomationSupervisorControllerTest {
  @Test
  fun savedToggleOnAutoResumesAutomationOnSync() {
    val store = FakeSupervisorStore(enabled = true, maintainCellMapper = true)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.syncFromSettings(trigger = "boot")

    assertEquals(PhoneAutomationSupervisorDecision.STARTED, decision)
    assertEquals(1, runtime.startCalls)
    assertEquals(0, touchBrightnessRuntime.startCalls)
  }

  @Test
  fun speedtestOnlyModeAlsoResumesAutomationOnSync() {
    val store = FakeSupervisorStore(enabled = true, maintainCellMapper = false)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.syncFromSettings(trigger = "boot_speedtest_only")

    assertEquals(PhoneAutomationSupervisorDecision.STARTED, decision)
    assertEquals(1, runtime.startCalls)
    assertEquals(0, touchBrightnessRuntime.startCalls)
  }

  @Test
  fun touchBrightnessOnlyStartsIndependentRuntime() {
    val store = FakeSupervisorStore(enabled = false, touchBrightnessEnabled = true)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.syncFromSettings(trigger = "touch_only")

    assertEquals(PhoneAutomationSupervisorDecision.STARTED, decision)
    assertEquals(0, runtime.startCalls)
    assertEquals(1, touchBrightnessRuntime.startCalls)
  }

  @Test
  fun bothTogglesStartBothRuntimes() {
    val store = FakeSupervisorStore(enabled = true, touchBrightnessEnabled = true)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.syncFromSettings(trigger = "both")

    assertEquals(PhoneAutomationSupervisorDecision.STARTED, decision)
    assertEquals(1, runtime.startCalls)
    assertEquals(1, touchBrightnessRuntime.startCalls)
  }

  @Test
  fun toggleOffStopsFurtherAutomationActions() {
    val store = FakeSupervisorStore(enabled = false)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.syncFromSettings(trigger = "toggle_off")

    assertEquals(PhoneAutomationSupervisorDecision.STOPPED, decision)
    assertEquals(listOf("disabled:toggle_off"), runtime.stopReasons)
    assertEquals(PhoneAutomationRuntimeState.DISABLED, store.load().runtimeState)
    assertTrue(store.load().runtimeDetail.isNotBlank())
    assertEquals(listOf("disabled:toggle_off"), touchBrightnessRuntime.stopReasons)
    assertEquals(TouchBrightnessRuntimeState.DISABLED, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.isNotBlank())
  }

  @Test
  fun refreshSyncDuringProtectedHandoffDoesNotRestartAutomation() {
    val store = FakeSupervisorStore(enabled = true, clockMillis = { 123L }).apply {
      updateRuntimeState(
        PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
        "Restarting Speedtest after completion_notification"
      )
    }
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.syncFromSettings(
      trigger = "lv.jolkins.pixelorchestrator.action.REFRESH_PHONE_AUTOMATION",
      suppressAutomationRestart = true
    )

    assertEquals(PhoneAutomationSupervisorDecision.IGNORED_IN_FLIGHT, decision)
    assertEquals(0, runtime.startCalls)
    assertTrue(runtime.stopReasons.isEmpty())
  }

  @Test
  fun refreshSyncDuringPlaceholderStartingStateStartsAutomation() {
    val store = FakeSupervisorStore(enabled = true).apply {
      updateRuntimeState(
        PhoneAutomationRuntimeState.STARTING,
        "Waiting for the supervision service"
      )
    }
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.syncFromSettings(
      trigger = "lv.jolkins.pixelorchestrator.action.REFRESH_PHONE_AUTOMATION",
      suppressAutomationRestart = true
    )

    assertEquals(PhoneAutomationSupervisorDecision.STARTED, decision)
    assertEquals(1, runtime.startCalls)
    assertTrue(runtime.stopReasons.isEmpty())
  }

  @Test
  fun protectedHandoffHelperRequiresRealStartTimestamp() {
    val disabled = PhoneAutomationSettingsSnapshot(
      enabled = false,
      runtimeState = PhoneAutomationRuntimeState.STARTING,
      protectedHandoffStartedAtMillis = 123L
    )
    val placeholderStarting = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.STARTING
    )
    val starting = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.STARTING,
      protectedHandoffStartedAtMillis = 123L
    )
    val restarting = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
      protectedHandoffStartedAtMillis = 456L
    )
    val waiting = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH
    )

    assertFalse(placeholderStarting.isProtectedSpeedtestHandoffInProgress())
    assertTrue(starting.isProtectedSpeedtestHandoffInProgress())
    assertTrue(restarting.isProtectedSpeedtestHandoffInProgress())
    assertFalse(disabled.isProtectedSpeedtestHandoffInProgress())
    assertFalse(waiting.isProtectedSpeedtestHandoffInProgress())
  }

  @Test
  fun accessibilityDriftPrioritizesAutomationAndDefersTouchBrightnessRecovery() {
    val store = FakeSupervisorStore(enabled = true, touchBrightnessEnabled = true)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.recoverFromPrerequisiteDrift(
      PhoneAutomationRecoveryRequest(
        reasonKey = "accessibility_connection",
        detail = "Accessibility service is not connected",
        recoverAutomation = true,
        recoverTouchBrightness = true
      )
    )

    assertEquals(PhoneAutomationRecoveryDecision.RECOVERED, decision)
    assertEquals(listOf("recover:accessibility_connection"), runtime.restartReasons)
    assertTrue(touchBrightnessRuntime.restartReasons.isEmpty())
    assertEquals(PhoneAutomationRuntimeState.STARTING, store.load().runtimeState)
    assertFalse(controller.shouldResumeDeferredTouchBrightness(store.load()))
  }

  @Test
  fun jointRecoveryDefersTouchBrightnessRestartUntilAutomationLeavesRecoveryLane() {
    val store = FakeSupervisorStore(
      enabled = true,
      touchBrightnessEnabled = true,
      clockMillis = { 123L }
    ).apply {
      updateRuntimeState(
        PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_COMPLETION,
        "Waiting for Speedtest completion"
      )
      updateTouchBrightnessState(
        TouchBrightnessRuntimeState.PANEL_SLEEP,
        "Panel sleep waiting for touch"
      )
    }
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.recoverFromPrerequisiteDrift(
      PhoneAutomationRecoveryRequest(
        reasonKey = "accessibility_connection",
        detail = "Accessibility service is not connected",
        recoverAutomation = true,
        recoverTouchBrightness = true
      )
    )

    assertEquals(PhoneAutomationRecoveryDecision.RECOVERED, decision)
    assertEquals(listOf("recover:accessibility_connection"), runtime.restartReasons)
    assertTrue(touchBrightnessRuntime.restartReasons.isEmpty())
    assertFalse(controller.shouldResumeDeferredTouchBrightness(store.load()))

    store.updateRuntimeState(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_COMPLETION,
      "Waiting for Speedtest completion"
    )

    assertTrue(controller.shouldResumeDeferredTouchBrightness(store.load()))

    controller.syncFromSettings(trigger = "deferred_touch_brightness_resume")

    assertEquals(listOf("recover:accessibility_connection"), touchBrightnessRuntime.restartReasons)
    assertFalse(controller.shouldResumeDeferredTouchBrightness(store.load()))
  }

  @Test
  fun notificationDriftRestartsOnlyPhoneAutomation() {
    val store = FakeSupervisorStore(enabled = true, touchBrightnessEnabled = true)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.recoverFromPrerequisiteDrift(
      PhoneAutomationRecoveryRequest(
        reasonKey = "notification_connection",
        detail = "Notification listener is not connected",
        recoverAutomation = true,
        recoverTouchBrightness = false
      )
    )

    assertEquals(PhoneAutomationRecoveryDecision.RECOVERED, decision)
    assertEquals(listOf("recover:notification_connection"), runtime.restartReasons)
    assertTrue(touchBrightnessRuntime.restartReasons.isEmpty())
  }

  @Test
  fun syncDefersStartingTouchBrightnessWhileAutomationRecoveryOwnsTheLane() {
    val store = FakeSupervisorStore(enabled = true, touchBrightnessEnabled = true).apply {
      updateRuntimeState(
        PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
        "Recovering from Speedtest failure"
      )
      updateTouchBrightnessState(
        TouchBrightnessRuntimeState.STOPPED,
        "Stopped: recover:accessibility_connection"
      )
    }
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    controller.syncFromSettings(trigger = "activity_resume")

    assertEquals(1, runtime.startCalls)
    assertEquals(0, touchBrightnessRuntime.startCalls)
    assertFalse(controller.shouldResumeDeferredTouchBrightness(store.load()))

    store.updateRuntimeState(
      PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_COMPLETION,
      "Waiting for Speedtest completion"
    )

    assertTrue(controller.shouldResumeDeferredTouchBrightness(store.load()))

    controller.syncFromSettings(trigger = "deferred_touch_brightness_resume")

    assertEquals(1, touchBrightnessRuntime.startCalls)
    assertFalse(controller.shouldResumeDeferredTouchBrightness(store.load()))
  }

  @Test
  fun disabledFeaturesAreNotRestartedDuringPrerequisiteRecovery() {
    val store = FakeSupervisorStore(enabled = false, touchBrightnessEnabled = false)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(store, runtime, touchBrightnessRuntime)

    val decision = controller.recoverFromPrerequisiteDrift(
      PhoneAutomationRecoveryRequest(
        reasonKey = "accessibility_permission",
        detail = "Accessibility access is not enabled",
        recoverAutomation = true,
        recoverTouchBrightness = true
      )
    )

    assertEquals(PhoneAutomationRecoveryDecision.IGNORED_NO_TARGETS, decision)
    assertTrue(runtime.restartReasons.isEmpty())
    assertTrue(touchBrightnessRuntime.restartReasons.isEmpty())
  }

  @Test
  fun repeatedRecoveryReasonIsIgnoredDuringCooldown() {
    var now = 100L
    val store = FakeSupervisorStore(enabled = true, touchBrightnessEnabled = true)
    val runtime = FakeRuntimeController()
    val touchBrightnessRuntime = FakeRuntimeController()
    val controller = PhoneAutomationSupervisorController(
      settingsStore = store,
      runtimeController = runtime,
      touchBrightnessRuntimeController = touchBrightnessRuntime,
      clockMillis = { now }
    )

    val firstDecision = controller.recoverFromPrerequisiteDrift(
      PhoneAutomationRecoveryRequest(
        reasonKey = "accessibility_connection",
        detail = "Accessibility service is not connected",
        recoverAutomation = true,
        recoverTouchBrightness = true
      )
    )
    now += 5_000L
    val secondDecision = controller.recoverFromPrerequisiteDrift(
      PhoneAutomationRecoveryRequest(
        reasonKey = "accessibility_connection",
        detail = "Accessibility service is not connected",
        recoverAutomation = true,
        recoverTouchBrightness = true
      )
    )

    assertEquals(PhoneAutomationRecoveryDecision.RECOVERED, firstDecision)
    assertEquals(PhoneAutomationRecoveryDecision.IGNORED_COOLDOWN, secondDecision)
    assertEquals(1, runtime.restartReasons.size)
    assertEquals(0, touchBrightnessRuntime.restartReasons.size)
  }

  @Test
  fun interruptProtectedHandoffDelegatesToRuntime() = runTest {
    val store = FakeSupervisorStore(enabled = true, clockMillis = { 123L }).apply {
      updateRuntimeState(
        PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
        "Restarting Speedtest after completion_notification"
      )
    }
    val runtime = FakeRuntimeController().apply {
      interruptProtectedHandoffResult = true
    }
    val controller = PhoneAutomationSupervisorController(
      settingsStore = store,
      runtimeController = runtime,
      touchBrightnessRuntimeController = FakeRuntimeController()
    )

    val decision = controller.interruptProtectedHandoff(
      "Speedtest restart was interrupted because the app was opened"
    )

    assertEquals(PhoneAutomationHandoffInterruptionDecision.INTERRUPTED, decision)
    assertEquals(
      listOf("Speedtest restart was interrupted because the app was opened"),
      runtime.interruptProtectedHandoffReasons
    )
  }

  @Test
  fun interruptProtectedHandoffIsIgnoredWhenNotProtected() = runTest {
    val runtime = FakeRuntimeController().apply {
      interruptProtectedHandoffResult = true
    }
    val controller = PhoneAutomationSupervisorController(
      settingsStore = FakeSupervisorStore(enabled = true),
      runtimeController = runtime,
      touchBrightnessRuntimeController = FakeRuntimeController()
    )

    val decision = controller.interruptProtectedHandoff("ignored")

    assertEquals(PhoneAutomationHandoffInterruptionDecision.IGNORED_NOT_IN_FLIGHT, decision)
    assertTrue(runtime.interruptProtectedHandoffReasons.isEmpty())
  }
}

private class FakeRuntimeController : PhoneAutomationRuntimeController {
  var startCalls: Int = 0
  val stopReasons = mutableListOf<String>()
  val restartReasons = mutableListOf<String>()
  val interruptProtectedHandoffReasons = mutableListOf<String>()
  var interruptProtectedHandoffResult: Boolean = false

  override fun start() {
    startCalls += 1
  }

  override fun stop(reason: String) {
    stopReasons += reason
  }

  override fun restart(reason: String) {
    restartReasons += reason
    stop(reason)
    start()
  }

  override suspend fun interruptProtectedHandoff(reason: String): Boolean {
    interruptProtectedHandoffReasons += reason
    return interruptProtectedHandoffResult
  }
}

private class FakeSupervisorStore(
  enabled: Boolean,
  maintainCellMapper: Boolean = true,
  touchBrightnessEnabled: Boolean = false,
  private val clockMillis: () -> Long = { 0L }
) : PhoneAutomationSettingsStore {
  private var snapshot = PhoneAutomationSettingsSnapshot(
    enabled = enabled,
    maintainCellMapper = maintainCellMapper,
    touchBrightnessEnabled = touchBrightnessEnabled
  )

  override fun load(): PhoneAutomationSettingsSnapshot = snapshot

  override fun setEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(enabled = enabled, protectedHandoffStartedAtMillis = 0L)
    return snapshot
  }

  override fun setMaintainCellMapper(maintainCellMapper: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(maintainCellMapper = maintainCellMapper)
    return snapshot
  }

  override fun setReturnToOrchestratorAfterForegroundWork(
    returnToOrchestratorAfterForegroundWork: Boolean
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      returnToOrchestratorAfterForegroundWork = returnToOrchestratorAfterForegroundWork
    )
    return snapshot
  }

  override fun setDispatchInterval(dispatchInterval: PhoneAutomationDispatchInterval): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(dispatchInterval = dispatchInterval)
    return snapshot
  }

  override fun setTouchBrightnessEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(touchBrightnessEnabled = enabled)
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
      }
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
      protectedHandoffStartedAtMillis = protectedHandoffStartedAtMillis
    )
    return snapshot
  }

  override fun updateTouchBrightnessState(
    state: TouchBrightnessRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(touchBrightnessState = state, touchBrightnessDetail = detail)
    return snapshot
  }

  override fun updateTouchBrightnessDebugDetail(detail: String): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(touchBrightnessDebugDetail = detail)
    return snapshot
  }

  override fun saveTouchBrightnessRestoreState(
    mode: Int?,
    value: Int?
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessRestoreMode = mode,
      touchBrightnessRestoreValue = value
    )
    return snapshot
  }

  override fun clearTouchBrightnessRestoreState(): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessRestoreMode = null,
      touchBrightnessRestoreValue = null
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
      pendingRecoveryReason = pendingRecoveryReason
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
      pendingRecoveryToken = token
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
      protectedHandoffStartedAtMillis = 0L
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
      lastTransientFailureAtMillis = observedAtMillis
    )
    return snapshot
  }

  override fun clearTransientFailureTracking(): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      pendingRecoveryReason = "",
      transientFailureCount = 0,
      lastTransientFailureAtMillis = 0L
    )
    return snapshot
  }
}
