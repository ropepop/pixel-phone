package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import kotlin.time.Duration
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult

@OptIn(ExperimentalCoroutinesApi::class)
class TouchBrightnessRuntimeTest {
  @Before fun resetBridge() { PhoneAutomationServiceBridge.resetForTests() }

  @Test
  fun powerRevocationCancelsAndJoinsSlowVisibleWriterBeforeImmediateZero() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val device = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(), events) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    device.operationCalls.clear()
    val restoreBlocked = CompletableDeferred<Unit>()
    val cleanupBlocked = CompletableDeferred<Unit>()
    device.beforeRestore = {
      try { restoreBlocked.await() } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
          device.operationCalls += "restore:cleanup_started"
          cleanupBlocked.await()
          device.operationCalls += "restore:cleanup_done"
        }
      }
    }
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    runCurrent()
    advanceTimeBy(1L)
    PhoneAutomationServiceBridge.revokePhysicalVisibility(testScheduler.currentTime)
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    advanceTimeBy(20L)
    runCurrent()
    assertTrue(device.operationCalls.contains("restore:cleanup_started"))
    assertFalse(device.operationCalls.contains("raw:zero"))
    cleanupBlocked.complete(Unit)
    runCurrent()
    assertTrue(device.operationCalls.indexOf("restore:cleanup_done") < device.operationCalls.indexOf("raw:zero"))
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertFalse(restoreBlocked.isCompleted)
  }

  @Test
  fun newTouchCancelsOwnedSideButtonConvergenceBeforeVisibleRestore() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val device = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(), events) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    val tail = CompletableDeferred<Unit>()
    device.beforeSetBrightness = {
      try { tail.await() } finally { device.operationCalls += "side_tail:cleanup_done" }
    }
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    runCurrent()
    assertTrue(device.operationCalls.indexOf("side_tail:cleanup_done") < device.operationCalls.indexOf("restore"))
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertFalse(tail.isCompleted)
  }

  @Test
  fun failedImmediateZeroStillConvergesWithoutRestoringVisibleBrightness() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val device = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(), events) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    runCurrent()
    val restores = device.restoreBrightnessStateCalls.size
    device.immediateClampResult = PhoneAutomationActionResult(false, "unproved")
    device.operationCalls.clear()
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    assertEquals(listOf("raw:zero", "set:0"), device.operationCalls)
    assertEquals(restores, device.restoreBrightnessStateCalls.size)
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(0L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
  }


  @Test
  fun immediatePanelControllerUsesOnlyRawWriteAndReadback() = runTest {
    val scripts = mutableListOf<String>()
    var exitCode = 0
    val root = object : RootExecutor {
      override suspend fun isRootAvailable() = true
      override suspend fun run(command: String, timeout: Duration): RootResult = error("unexpected")
      override suspend fun runScript(script: String, timeout: Duration): RootResult {
        scripts += script
        return RootResult(exitCode, "", "", script, 1L)
      }
    }
    val controller = AndroidTouchBrightnessDeviceController(ContextWrapper(null), root)
    assertTrue(controller.clampPanelSleepImmediately().success)
    assertEquals(1, scripts.size)
    assertFalse(scripts.single().contains("settings "))
    assertFalse(scripts.single().contains("cmd display"))
    assertTrue(scripts.single().contains("bl_power"))
    assertTrue(scripts.single().contains("|| exit 1"))
    exitCode = 1
    assertFalse(controller.clampPanelSleepImmediately().success)
  }

  @Test
  fun physicalTapWaitsForExactDarkHelperStopBeforeRestoringVisibility() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val device = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(), events) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    PhoneAutomationServiceBridge.registerPhysicalVisibilityBlocker("ticket-helper")
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    events.emit(physicalTouchEvent(0, testScheduler.currentTime))
    runCurrent()
    assertTrue(device.restoreBrightnessStateCalls.isEmpty())
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)

    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("different-helper")
    advanceTimeBy(100L)
    runCurrent()
    assertTrue(device.restoreBrightnessStateCalls.isEmpty())
    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("ticket-helper")
    runCurrent()

    assertTrue(device.restoreBrightnessStateCalls.isNotEmpty())
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
  }

  @Test
  fun sideButtonDuringHelperStopWaitPreventsEvenATemporaryVisibleRestore() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val device = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(), events) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    PhoneAutomationServiceBridge.registerPhysicalVisibilityBlocker("ticket-helper")
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    events.emit(physicalTouchEvent(0, testScheduler.currentTime))
    runCurrent()
    assertTrue(device.restoreBrightnessStateCalls.isEmpty())
    advanceTimeBy(1L)
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    assertEquals(0L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)

    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("ticket-helper")
    runCurrent()

    assertTrue(device.restoreBrightnessStateCalls.isEmpty())
    assertTrue(device.setBrightnessPercentCalls.all { it == 0 })
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
  }

  @Test
  fun enabledRestartKeepsPhysicalWindowVisibleWithoutRestartClampOrDeadlineExtension() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val device = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(), events) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    events.emit(physicalTouchEvent(0, testScheduler.currentTime))
    runCurrent()
    val deadline = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis
    advanceTimeBy(30_000L)
    runtime.stop("supervisor_handoff")
    runCurrent()
    device.setBrightnessPercentCalls.clear()
    runtime.start()
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(device.setBrightnessPercentCalls.isEmpty())
    assertEquals(deadline, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
    advanceTimeBy(deadline - testScheduler.currentTime - 1L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    advanceTimeBy(1L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
  }

  @Test
  fun bufferedPhysicalDownBeforePowerRevocationCannotRestoreVisibility() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val device = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(), events) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    advanceTimeBy(10L)
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    val restoreCount = device.restoreBrightnessStateCalls.size
    events.emit(physicalTouchEvent(1, 5L))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(restoreCount, device.restoreBrightnessStateCalls.size)
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(0, testScheduler.currentTime))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
  }

  @Test
  fun unprovedDarkCommandCleanupCancelsSessionBeforeAnyVisibleRestore() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val controller = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, controller, FakeBlackoutOverlayController(), events,
      dimGuardEnabled = true) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    val darkStarted = CompletableDeferred<Unit>()
    val darkCompletion = CompletableDeferred<Unit>()
    controller.beforeSetBrightness = { percent ->
      if (percent == 0) {
        darkStarted.complete(Unit)
        try { darkCompletion.await() } finally {
          throw IllegalStateException("root command cleanup unproved")
        }
      }
    }
    events.emit(TouchBrightnessEvent.NonTouchInput("ticket:tap", 0L, 4_000L, touchBeginCount = 0L))
    runCurrent()
    assertTrue(darkStarted.isCompleted)

    events.emit(physicalTouchEvent(1, 1L))
    events.emit(physicalTouchEvent(0, 2L))
    runCurrent()

    assertFalse(darkCompletion.isCompleted)
    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
    assertTrue(controller.restoreBrightnessStateCalls.isEmpty())
    assertTrue(controller.setBrightnessPercentCalls.all { it == 0 })
  }

  @Test
  fun physicalTapCancelsExpiredTimerDarkWriteBeforeVisibleRestore() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val controller = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, controller, FakeBlackoutOverlayController(), events,
      dimGuardEnabled = true) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    events.emit(physicalTouchEvent(1, 0L))
    events.emit(physicalTouchEvent(0, 0L))
    runCurrent()
    controller.operationCalls.clear()
    val timerWriteStarted = CompletableDeferred<Unit>()
    val timerWriteCompletion = CompletableDeferred<Unit>()
    controller.beforeSetBrightness = { percent ->
      if (percent == 0) {
        timerWriteStarted.complete(Unit)
        try { timerWriteCompletion.await() } finally { controller.operationCalls += "timer_dark_cancelled" }
      }
    }
    advanceTimeBy(TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS)
    runCurrent()
    assertTrue(timerWriteStarted.isCompleted)
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    events.emit(physicalTouchEvent(0, testScheduler.currentTime))
    runCurrent()
    assertFalse(timerWriteCompletion.isCompleted)
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(controller.operationCalls.indexOf("timer_dark_cancelled") < controller.operationCalls.indexOf("restore"))
  }

  @Test
  fun completedTapCancelsPendingNonTouchDarkWriteBeforeRestoreAndRejectsStaleEvent() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val controller = FakeTouchBrightnessDeviceController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, controller, FakeBlackoutOverlayController(), events,
      dimGuardEnabled = true) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    val darkStarted = CompletableDeferred<Unit>()
    val darkCompletion = CompletableDeferred<Unit>()
    var darkCancelled = false
    controller.beforeSetBrightness = { percent ->
      if (percent == 0) {
        darkStarted.complete(Unit)
        try { darkCompletion.await() } finally {
          darkCancelled = true
          controller.operationCalls += "dark_cancelled"
        }
      }
    }
    events.emit(TouchBrightnessEvent.NonTouchInput("ticket:tap", 0L, 4_000L, touchBeginCount = 0L))
    runCurrent()
    assertTrue(darkStarted.isCompleted)

    events.emit(physicalTouchEvent(1, 1L))
    events.emit(physicalTouchEvent(0, 2L))
    runCurrent()
    assertTrue(darkCancelled)
    assertFalse(darkCompletion.isCompleted)
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(controller.operationCalls.indexOf("dark_cancelled") < controller.operationCalls.indexOf("restore"))
    val darkWritesAtRestore = controller.setBrightnessPercentCalls.size

    // Even an equal-timestamp stale event cannot undo the completed tap: occurrence wins.
    events.emit(TouchBrightnessEvent.NonTouchInput("ticket:queued", 2L, 4_000L, touchBeginCount = 0L))
    advanceTimeBy(2_000L)
    runCurrent()
    assertEquals(darkWritesAtRestore, controller.setBrightnessPercentCalls.size)
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
  }


  private val touchSource = RootTouchDevice(
    path = "/dev/input/event2",
    name = "synaptics_tcm_touch",
    score = 109
  )
  private val powerSource = RootPowerKeyDevice(
    path = "/dev/input/event0",
    name = "gpio_keys",
    score = 116
  )

  @Test
  fun panelSleepUsesRealZeroAfterNinetySeconds() {
    assertEquals(0, TouchBrightnessRuntime.PANEL_SLEEP_PERCENT)
    assertEquals(90_000L, TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS)
  }

  @Test
  fun startWithoutPhysicalTouchEntersPanelSleepImmediately() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val powerController = FakeTouchScreenPowerController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource, powerController) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertEquals(0, overlayController.showCalls)
    assertTrue(powerController.wakeHoldActive)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=none"))
  }





  @Test
  fun transientSessionFailureDoesNotRestoreBrightnessDuringRetryBackoff() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.FatalError("transient monitor failure"))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
  }

  @Test
  fun physicalTouchResetsPanelSleepTimerAndWakesFromPanelSleep() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS - 1_000L)
    runCurrent()

    eventSource.emit(physicalTouchEvent(activeTouchCount = 1, observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)

    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS - 1L)
    runCurrent()
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    advanceTimeBy(1L)
    runCurrent()
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)

    eventSource.emit(physicalTouchEvent(activeTouchCount = 1, observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(ScreenBrightnessState(mode = 1, value = 127), deviceController.restoreBrightnessStateCalls.last())
  }

  @Test
  fun nonTouchInputDoesNotResetThePanelSleepTimer() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS - 1_000L)
    runCurrent()
    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "test_remote_input",
      durationMillis = 2_000L,
      observedAtUptimeMillis = testScheduler.currentTime
    )
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime,
        snapshot = RootTouchSnapshot(
          activeTouchCount = 1,
          btnTouchActive = false,
          toolFingerActive = false,
          activeSlotCount = 0,
          selectedDevice = touchSource,
          lastEventUptimeMillis = testScheduler.currentTime
        )
      )
    )
    runCurrent()

    advanceTimeBy(1_000L)
    runCurrent()

    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
  }

  @Test
  fun powerButtonDuringPanelSleepKeepsPanelDarkAndAndroidAwake() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val powerController = FakeTouchScreenPowerController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), events, powerController) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertTrue(powerController.wakeHoldActive)
    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
    advanceTimeBy(90_000L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
  }

  @Test
  fun immediateScreenOffAfterPowerPressRecoversDarkWithoutVisibleRebound() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val powerController = FakeTouchScreenPowerController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), events, powerController) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    advanceTimeBy(0L)
    events.emit(TouchBrightnessEvent.ScreenInteractiveChanged(false))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(1, powerController.forceWakeCalls)
    assertTrue(powerController.wakeHoldActive)
    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
  }

  @Test
  fun delayedScreenOffAfterPowerPressRecoversDarkWithoutVisibleRebound() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val powerController = FakeTouchScreenPowerController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), events, powerController) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    advanceTimeBy(3000L)
    events.emit(TouchBrightnessEvent.ScreenInteractiveChanged(false))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(1, powerController.forceWakeCalls)
    assertTrue(powerController.wakeHoldActive)
    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
  }

  @Test
  fun uncorrelatedScreenOffFromPanelSleepWakesDarkAndDoesNotRestoreVisibleBrightness() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val powerController = FakeTouchScreenPowerController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource, powerController) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)

    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = false))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(1, powerController.forceWakeCalls)
    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
    assertEquals(listOf(0, 0, 0), deviceController.setBrightnessPercentCalls)
    assertFalse(store.load().touchBrightnessDebugDetail.contains("timer=pending("))

    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = true))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
    assertEquals(listOf(0, 0, 0, 0), deviceController.setBrightnessPercentCalls)
  }

  @Test
  fun restoreFailureUsesSafeVisibleFallbackInsteadOfFullBrightness() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController().apply {
      restoreBrightnessResult = PhoneAutomationActionResult(false, "restore verification failed")
    }
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 1,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()

    assertEquals(listOf(TouchBrightnessRuntime.SAFE_VISIBLE_FALLBACK_PERCENT), deviceController.setBrightnessPercentCalls)
    assertFalse(deviceController.setBrightnessPercentCalls.contains(TouchBrightnessRuntime.BRIGHT_PERCENT))
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
  }

  @Test
  fun physicalTapWindowIgnoresNewNonTouchInputWithoutExtendingDeadline() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val powerController = FakeTouchScreenPowerController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), events, powerController) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    runCurrent()
    advanceTimeBy(5_000L)
    events.emit(physicalTouchEvent(0, testScheduler.currentTime))
    runCurrent()
    advanceTimeBy(45_000L)
    events.emit(TouchBrightnessEvent.NonTouchInput("ticket:new_action", testScheduler.currentTime, testScheduler.currentTime + 2_000L))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    advanceTimeBy(44_999L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    advanceTimeBy(1L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
  }

  @Test
  fun sideButtonRevokesHeldTouchAndReleaseDoesNotGrantVisibilityAgain() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val powerController = FakeTouchScreenPowerController()
    val events = FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), events, powerController) { testScheduler.currentTime }
    runtime.start()
    runCurrent()
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    events.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertTrue(powerController.wakeHoldActive)
    val restores = deviceController.restoreBrightnessStateCalls.size
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(0, testScheduler.currentTime))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(restores, deviceController.restoreBrightnessStateCalls.size)
    advanceTimeBy(1L)
    events.emit(physicalTouchEvent(1, testScheduler.currentTime))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
  }

  @Test
  fun disableRestoresPowerPolicyEvenWithoutStartedSession() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = false)
    val policy = FakeTouchPowerButtonPolicyController()
    val runtime = buildRuntime(backgroundScope, store, FakeTouchBrightnessDeviceController(), FakeBlackoutOverlayController(),
      FakeTouchBrightnessEventSource(true, 0, source = touchSource), powerButtonPolicy = policy) { testScheduler.currentTime }
    runtime.stop("disabled")
    runCurrent()
    assertEquals(1, policy.restoreCalls)
    assertEquals(0, policy.acquireCalls)
  }

  @Test
  fun physicalTouchDuringNonTouchSuppressionWakesFromPanelSleep() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "ticket:rs_monthly_ticket_fast_return:active",
      durationMillis = 4_000L,
      observedAtUptimeMillis = testScheduler.currentTime
    )

    eventSource.emit(physicalTouchEvent(activeTouchCount = 1, observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(ScreenBrightnessState(mode = 1, value = 127), deviceController.restoreBrightnessStateCalls.last())
    assertTrue(store.load().touchBrightnessDetail.contains("touch is active"))
  }

  @Test
  fun nonTouchInputDoesNotClearActivePhysicalTouch() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    eventSource.emit(physicalTouchEvent(activeTouchCount = 1, observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()

    eventSource.emit(
      TouchBrightnessEvent.NonTouchInput(
        reason = "ticket:rs_monthly_ticket_fast_return:active",
        observedAtUptimeMillis = testScheduler.currentTime,
        suppressedUntilUptimeMillis = testScheduler.currentTime + 4_000L
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("touch is active"))
    assertTrue(store.load().touchBrightnessDebugDetail.contains("touches=1"))
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
  }

  @Test
  fun screenOffDuringPhysicalWindowRecoversVisibleUntilDeadline() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, FakeTouchBrightnessDeviceController(), FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    eventSource.emit(physicalTouchEvent(activeTouchCount = 1, observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = false))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
  }

  @Test
  fun disableWithoutSavedBrightnessStillReopensPanelWithSafeVisibleFallback() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = false)
    val device = FakeTouchBrightnessDeviceController().apply {
      currentBrightnessState = ScreenBrightnessState(mode = 0, value = 0, panelBacklightPower = 4)
    }
    val runtime = buildRuntime(backgroundScope, store, device, FakeBlackoutOverlayController(),
      FakeTouchBrightnessEventSource(true, 0, source = touchSource, powerSource = powerSource)) {
      testScheduler.currentTime
    }
    runtime.stop("disabled:missing_checkpoint")
    runCurrent()
    assertEquals(listOf(TouchBrightnessRuntime.SAFE_VISIBLE_FALLBACK_PERCENT), device.setBrightnessPercentCalls)
    assertEquals(0, device.currentBrightnessState.panelBacklightPower)
    assertEquals(TouchBrightnessRuntimeState.DISABLED, store.load().touchBrightnessState)
  }

  @Test
  fun disableRestoresVisibleBrightnessFromPanelSleep() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    store.setTouchBrightnessEnabled(false)

    runtime.stop(reason = "disabled:test")
    runCurrent()
    advanceUntilIdle()

    assertEquals(TouchBrightnessRuntimeState.DISABLED, store.load().touchBrightnessState)
    assertEquals(ScreenBrightnessState(mode = 1, value = 127), deviceController.restoreBrightnessStateCalls.last())
    assertEquals(null, store.load().touchBrightnessRestoreMode)
    assertEquals(null, store.load().touchBrightnessRestoreValue)
  }

  @Test
  fun enabledStopKeepsPanelBlankAndSavedRestoreStateForSupervisorHandoff() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val policy = FakeTouchPowerButtonPolicyController()
    val deviceController = FakeTouchBrightnessDeviceController()
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      FakeTouchBrightnessEventSource(
        interactive = true,
        activeTouchCount = 0,
        source = touchSource,
        powerSource = powerSource
      ),
      powerButtonPolicy = policy
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    runtime.stop(reason = "service_destroyed")
    runCurrent()
    advanceUntilIdle()

    assertEquals(0, policy.restoreCalls)
    assertEquals(1, policy.acquireCalls)
    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
    assertEquals(1, store.load().touchBrightnessRestoreMode)
    assertEquals(127, store.load().touchBrightnessRestoreValue)
    assertEquals(TouchBrightnessRuntimeState.STOPPED, store.load().touchBrightnessState)
  }

  @Test
  fun serviceDestroyedWhileEnabledPreservesPanelSleepAndSavedRestoreState() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(
      touchBrightnessEnabled = true,
      initialTouchBrightnessState = TouchBrightnessRuntimeState.PANEL_SLEEP,
      initialRestoreMode = 1,
      initialRestoreValue = 127
    )
    val deviceController = FakeTouchBrightnessDeviceController().apply {
      currentBrightnessState = ScreenBrightnessState(mode = 0, value = 0, displayPercentage = 0f)
    }
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      FakeTouchBrightnessEventSource(
        interactive = true,
        activeTouchCount = 0,
        source = touchSource,
        powerSource = powerSource
      )
    ) { testScheduler.currentTime }

    runtime.stop(reason = "service_destroyed")
    runCurrent()
    advanceUntilIdle()

    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
    assertTrue(deviceController.setBrightnessPercentCalls.isEmpty())
    assertEquals(1, store.load().touchBrightnessRestoreMode)
    assertEquals(127, store.load().touchBrightnessRestoreValue)
    assertEquals(TouchBrightnessRuntimeState.STOPPED, store.load().touchBrightnessState)
  }

  @Test
  fun restartClampRunsBeforeFailedPrepareAndPreservesSavedRestoreState() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(
      touchBrightnessEnabled = true,
      initialTouchBrightnessState = TouchBrightnessRuntimeState.STOPPED,
      initialRestoreMode = 1,
      initialRestoreValue = 127
    )
    val deviceController = FakeTouchBrightnessDeviceController().apply {
      prepareResult = PhoneAutomationPreparationResult(ready = false, detail = "root unavailable")
    }
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      FakeTouchBrightnessEventSource(
        interactive = true,
        activeTouchCount = 0,
        source = touchSource,
        powerSource = powerSource
      )
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    assertEquals(listOf("set:0", "prepare"), deviceController.operationCalls.take(2))
    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
    assertEquals(1, store.load().touchBrightnessRestoreMode)
    assertEquals(127, store.load().touchBrightnessRestoreValue)
  }

  @Test
  fun nonTouchScreenOnDuringPanelSleepStaysDim() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    val restoreCallsAfterPanelSleep = deviceController.restoreBrightnessStateCalls.size
    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "test_programmatic_wake",
      durationMillis = 2_000L,
      observedAtUptimeMillis = testScheduler.currentTime
    )

    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = true))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(restoreCallsAfterPanelSleep, deviceController.restoreBrightnessStateCalls.size)
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
  }

  @Test
  fun nonTouchInputEventReassertsOnceWithoutDelayedBrightnessBursts() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
      dimGuardEnabled = true
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    eventSource.emit(
      TouchBrightnessEvent.NonTouchInput(
        reason = "ticket:tap",
        observedAtUptimeMillis = testScheduler.currentTime,
        suppressedUntilUptimeMillis = testScheduler.currentTime + 2_000L
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)

    advanceTimeBy(2_000L)
    runCurrent()
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)

  }

  @Test
  fun ticketNonTouchInputPreservesPhysicalIdleWindow() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
      dimGuardEnabled = true
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    eventSource.emit(
      physicalTouchEvent(1, testScheduler.currentTime)
    )
    runCurrent()
    eventSource.emit(
      physicalTouchEvent(0, testScheduler.currentTime)
    )
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))

    eventSource.emit(
      TouchBrightnessEvent.NonTouchInput(
        reason = "ticket:rs_monthly_ticket_flow:active",
        observedAtUptimeMillis = testScheduler.currentTime,
        suppressedUntilUptimeMillis = testScheduler.currentTime + 2_000L
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))
  }

  @Test
  fun genericNonTouchInputPreservesPhysicalIdleWindow() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
      dimGuardEnabled = true
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    eventSource.emit(
      physicalTouchEvent(1, testScheduler.currentTime)
    )
    runCurrent()
    eventSource.emit(
      physicalTouchEvent(0, testScheduler.currentTime)
    )
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))

    eventSource.emit(
      TouchBrightnessEvent.NonTouchInput(
        reason = "accessibility_click",
        observedAtUptimeMillis = testScheduler.currentTime,
        suppressedUntilUptimeMillis = testScheduler.currentTime + 2_000L
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))
  }

  @Test
  fun nonTouchScreenOnWhileSuspendedEntersPanelSleep() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = false,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.SUSPENDED_SCREEN_OFF, store.load().touchBrightnessState)
    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "ticket:wake",
      durationMillis = 2_000L,
      observedAtUptimeMillis = testScheduler.currentTime
    )

    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = true))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertEquals(emptyList<ScreenBrightnessState>(), deviceController.restoreBrightnessStateCalls)
  }

  @Test
  fun screenOnWithoutPhysicalTouchStaysDark() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    val restoreCallsAfterPanelSleep = deviceController.restoreBrightnessStateCalls.size

    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = true))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(restoreCallsAfterPanelSleep, deviceController.restoreBrightnessStateCalls.size)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=none"))
  }

  @Test
  fun panelSleepGuardSkipsWriteWhenPanelIsAlreadyDark() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
      dimGuardEnabled = true
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    val startupReads = deviceController.readBrightnessStateCalls

    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS - 1L)
    runCurrent()
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertEquals(startupReads, deviceController.readBrightnessStateCalls)

    advanceTimeBy(1L)
    runCurrent()
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertEquals(startupReads + 1, deviceController.readBrightnessStateCalls)
  }

  @Test
  fun panelSleepGuardReassertsDimWhenPanelDriftsBright() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
      dimGuardEnabled = true
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    deviceController.currentBrightnessState = ScreenBrightnessState(mode = 0, value = 127, displayPercentage = 50f)
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS)
    runCurrent()

    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
  }

  @Test
  fun panelSleepGuardRefreshesWakeHoldBeforeReassertingDim() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val powerController = FakeTouchScreenPowerController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
      powerController,
      dimGuardEnabled = true
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertTrue(powerController.wakeHoldActive)
    powerController.releaseHold("test_expired")
    assertFalse(powerController.wakeHoldActive)

    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS)
    runCurrent()

    assertTrue(powerController.wakeHoldActive)
    assertEquals("panel_sleep_guard", powerController.holdReasons.last())
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
  }

  @Test
  fun nonTouchPanelSleepReassertFailureStaysInPanelSleepForGuardRetry() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      FakeBlackoutOverlayController(),
      eventSource,
      dimGuardEnabled = true
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    deviceController.setBrightnessResult = PhoneAutomationActionResult(false, "panel still bright")
    deviceController.currentBrightnessState = ScreenBrightnessState(mode = 0, value = 127, displayPercentage = 50f)
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS)
    runCurrent()

    val callsAfterFailedGuard = deviceController.setBrightnessPercentCalls.size
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("Panel sleep retrying"))

    deviceController.setBrightnessResult = PhoneAutomationActionResult(true, "set")
    deviceController.currentBrightnessState = ScreenBrightnessState(mode = 0, value = 127, displayPercentage = 50f)
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_RETRY_INTERVAL_MILLIS - 1L)
    runCurrent()
    assertEquals(callsAfterFailedGuard, deviceController.setBrightnessPercentCalls.size)

    advanceTimeBy(1L)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertFalse(store.load().touchBrightnessDetail.contains("Touch brightness error"))
    assertTrue(deviceController.setBrightnessPercentCalls.size > callsAfterFailedGuard)
  }

  @Test
  fun restartWhilePersistedPanelSleepReassertsDimImmediately() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(
      touchBrightnessEnabled = true,
      initialTouchBrightnessState = TouchBrightnessRuntimeState.PANEL_SLEEP,
      initialRestoreMode = 1,
      initialRestoreValue = 127
    )
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
    assertEquals(emptyList<ScreenBrightnessState>(), deviceController.restoreBrightnessStateCalls)
  }

  @Test
  fun darkSavedRestoreStateIsTreatedAsPanelSleepOnStartup() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(
      touchBrightnessEnabled = true,
      initialTouchBrightnessState = TouchBrightnessRuntimeState.ERROR,
      initialRestoreMode = 0,
      initialRestoreValue = 0
    )
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertEquals(emptyList<ScreenBrightnessState>(), deviceController.restoreBrightnessStateCalls)
    assertEquals(null, store.load().touchBrightnessRestoreMode)
    assertEquals(null, store.load().touchBrightnessRestoreValue)
  }

  @Test
  fun physicalTouchWithDarkSavedRestoreStateStillGetsVisibleFallback() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(
      touchBrightnessEnabled = true,
      initialTouchBrightnessState = TouchBrightnessRuntimeState.ERROR,
      initialRestoreMode = 0,
      initialRestoreValue = 0
    )
    val deviceController = FakeTouchBrightnessDeviceController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 1,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, FakeBlackoutOverlayController(), eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(listOf(TouchBrightnessRuntime.SAFE_VISIBLE_FALLBACK_PERCENT), deviceController.setBrightnessPercentCalls)
    assertEquals(emptyList<ScreenBrightnessState>(), deviceController.restoreBrightnessStateCalls)
    assertEquals(null, store.load().touchBrightnessRestoreMode)
    assertEquals(null, store.load().touchBrightnessRestoreValue)
  }

  private fun buildRuntime(
    scope: CoroutineScope,
    store: InMemoryTouchBrightnessSettingsStore,
    deviceController: FakeTouchBrightnessDeviceController,
    overlayController: FakeBlackoutOverlayController,
    eventSource: FakeTouchBrightnessEventSource,
    powerController: FakeTouchScreenPowerController = FakeTouchScreenPowerController(),
    dimGuardEnabled: Boolean = false,
    powerButtonPolicy: FakeTouchPowerButtonPolicyController = FakeTouchPowerButtonPolicyController(),
    uptimeClock: () -> Long
  ): TouchBrightnessRuntime {
    PhoneAutomationServiceBridge.resetForTests()
    return TouchBrightnessRuntime(
      context = ContextWrapper(null),
      scope = scope,
      settingsStore = store,
      rootExecutor = UnusedRootExecutor(),
      onSnapshotChanged = {},
      deviceController = deviceController,
      overlayController = overlayController,
      powerController = powerController,
      powerButtonPolicy = powerButtonPolicy,
      eventSourceFactory = { eventSource },
      uptimeClock = uptimeClock,
      dimGuardEnabled = dimGuardEnabled
    )
  }

  private fun physicalTouchEvent(
    activeTouchCount: Int,
    observedAtUptimeMillis: Long
  ): TouchBrightnessEvent.TouchCountChanged {
    return TouchBrightnessEvent.TouchCountChanged(
      activeTouchCount = activeTouchCount,
      observedAtUptimeMillis = observedAtUptimeMillis,
      snapshot = RootTouchSnapshot(
        activeTouchCount = activeTouchCount,
        btnTouchActive = activeTouchCount > 0,
        toolFingerActive = activeTouchCount > 0,
        activeSlotCount = activeTouchCount,
        selectedDevice = touchSource,
        lastEventUptimeMillis = observedAtUptimeMillis
      )
    )
  }
}

private class FakeTouchBrightnessEventSource(
  interactive: Boolean,
  activeTouchCount: Int,
  private var overlayAvailableState: Boolean = true,
  source: RootTouchDevice? = null,
  powerSource: RootPowerKeyDevice? = null
) : TouchBrightnessEventSource {
  private val mutableEvents = MutableSharedFlow<TouchBrightnessEvent>(extraBufferCapacity = 16)
  override val events: Flow<TouchBrightnessEvent> = mutableEvents
  private var interactiveState = interactive
  private var activeTouchCountState = activeTouchCount
  private var sourceState = source
  private var powerSourceState = powerSource
  private var btnTouchActiveState = activeTouchCount > 0
  private var activeSlotCountState = activeTouchCount
  private var lastEventUptimeMillisState = 0L

  override fun isInteractive(): Boolean = interactiveState

  override fun activeTouchCount(): Int = activeTouchCountState

  override fun currentTouchSnapshot(): RootTouchSnapshot {
    return RootTouchSnapshot(
      activeTouchCount = activeTouchCountState,
      btnTouchActive = btnTouchActiveState,
      toolFingerActive = btnTouchActiveState,
      activeSlotCount = activeSlotCountState,
      selectedDevice = sourceState,
      lastEventUptimeMillis = lastEventUptimeMillisState
    )
  }

  override fun isOverlayAvailable(): Boolean = overlayAvailableState

  override fun selectedTouchSource(): RootTouchDevice? = sourceState

  override fun selectedPowerSource(): RootPowerKeyDevice? = powerSourceState

  fun emit(event: TouchBrightnessEvent) {
    when (event) {
      is TouchBrightnessEvent.TouchCountChanged -> {
        PhoneAutomationServiceBridge.recordRootPhysicalTouchState(
          active = event.activeTouchCount > 0 && event.snapshot?.isRawTouchActive() == true,
          observedAtUptimeMillis = event.observedAtUptimeMillis
        )
        activeTouchCountState = event.activeTouchCount
        lastEventUptimeMillisState = event.observedAtUptimeMillis
        val snapshot = event.snapshot
        if (snapshot != null) {
          btnTouchActiveState = snapshot.btnTouchActive || snapshot.toolFingerActive
          activeSlotCountState = snapshot.activeSlotCount
          sourceState = snapshot.selectedDevice ?: sourceState
          lastEventUptimeMillisState = snapshot.lastEventUptimeMillis
        } else {
          btnTouchActiveState = event.activeTouchCount > 0
          activeSlotCountState = event.activeTouchCount
        }
      }

      is TouchBrightnessEvent.ScreenInteractiveChanged -> {
        interactiveState = event.interactive
        if (!event.interactive) {
          activeTouchCountState = 0
          btnTouchActiveState = false
          activeSlotCountState = 0
        }
      }

      is TouchBrightnessEvent.OverlayAvailabilityChanged -> overlayAvailableState = event.available
      is TouchBrightnessEvent.TouchSourceSelected -> sourceState = event.device
      is TouchBrightnessEvent.PowerSourceSelected -> powerSourceState = event.device
      is TouchBrightnessEvent.PowerButtonPressed -> {
        PhoneAutomationServiceBridge.revokePhysicalVisibility(event.observedAtUptimeMillis)
        powerSourceState = event.device ?: powerSourceState
      }
      is TouchBrightnessEvent.BlackoutWakeRequested -> Unit
      is TouchBrightnessEvent.NonTouchInput -> Unit
      is TouchBrightnessEvent.FatalError -> Unit
    }
    mutableEvents.tryEmit(event)
  }
}

private class FakeTouchBrightnessDeviceController : TouchBrightnessDeviceController {
  var beforeSetBrightness: suspend (Int) -> Unit = {}
  var beforeRestore: suspend () -> Unit = {}
  var immediateClampResult = PhoneAutomationActionResult(true, "raw zero")
  var prepareResult = PhoneAutomationPreparationResult(ready = true, detail = "ready")
  var currentBrightnessState = ScreenBrightnessState(mode = 1, value = 127)
  var setBrightnessResult = PhoneAutomationActionResult(true, "set")
  var restoreBrightnessResult = PhoneAutomationActionResult(true, "restored")
  var readBrightnessStateCalls = 0
  val setBrightnessPercentCalls = mutableListOf<Int>()
  val restoreBrightnessStateCalls = mutableListOf<ScreenBrightnessState>()
  val operationCalls = mutableListOf<String>()

  override suspend fun clampPanelSleepImmediately(): PhoneAutomationActionResult {
    operationCalls += "raw:zero"
    return immediateClampResult
  }

  override suspend fun prepare(): PhoneAutomationPreparationResult {
    operationCalls += "prepare"
    return prepareResult
  }

  override suspend fun readBrightnessState(): ScreenBrightnessState? {
    readBrightnessStateCalls += 1
    return currentBrightnessState
  }

  override suspend fun setBrightnessPercent(percent: Int): PhoneAutomationActionResult {
    operationCalls += "set:$percent"
    setBrightnessPercentCalls += percent
    beforeSetBrightness(percent)
    currentBrightnessState = ScreenBrightnessState(
      mode = 0,
      value = ScreenBrightnessControl.legacySystemValue(percent),
      displayPercentage = percent.toFloat(),
      panelBacklightPower = if (percent == 0) 4 else 0
    )
    return setBrightnessResult
  }

  override suspend fun restoreBrightnessState(state: ScreenBrightnessState): PhoneAutomationActionResult {
    operationCalls += "restore"
    restoreBrightnessStateCalls += state
    beforeRestore()
    currentBrightnessState = state
    return restoreBrightnessResult
  }
}

private class FakeBlackoutOverlayController : BlackoutOverlayController {
  var showCalls = 0
  var hideCalls = 0

  override suspend fun show(): PhoneAutomationActionResult {
    showCalls += 1
    return PhoneAutomationActionResult(success = true, detail = "shown")
  }

  override suspend fun hide(): PhoneAutomationActionResult {
    hideCalls += 1
    return PhoneAutomationActionResult(success = true, detail = "hidden")
  }

  override fun isAvailable(): Boolean = true
}

private class FakeTouchScreenPowerController : TouchScreenPowerController {
  override var wakeHoldActive = false
    private set
  var wakeCalls = 0
  var forceWakeCalls = 0
  val holdReasons = mutableListOf<String>()
  val releaseReasons = mutableListOf<String>()

  override suspend fun wakeScreen(reason: String): PhoneAutomationActionResult {
    wakeCalls += 1
    holdScreen("wake:$reason")
    return PhoneAutomationActionResult(true, "woke")
  }

  override suspend fun forceWakeScreen(reason: String): PhoneAutomationActionResult {
    forceWakeCalls += 1
    holdScreen("force_wake:$reason")
    return PhoneAutomationActionResult(true, "force woke")
  }

  override fun holdScreen(reason: String) {
    wakeHoldActive = true
    holdReasons += reason
  }

  override fun releaseHold(reason: String) {
    wakeHoldActive = false
    releaseReasons += reason
  }
}

private class InMemoryTouchBrightnessSettingsStore(
  touchBrightnessEnabled: Boolean,
  initialTouchBrightnessState: TouchBrightnessRuntimeState = TouchBrightnessRuntimeState.DISABLED,
  initialRestoreMode: Int? = null,
  initialRestoreValue: Int? = null
) : PhoneAutomationSettingsStore {
  private var snapshot = PhoneAutomationSettingsSnapshot(
    enabled = false,
    touchBrightnessEnabled = touchBrightnessEnabled,
    touchBrightnessState = initialTouchBrightnessState,
    touchBrightnessRestoreMode = initialRestoreMode,
    touchBrightnessRestoreValue = initialRestoreValue
  )

  override fun load(): PhoneAutomationSettingsSnapshot = snapshot

  override fun setEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(enabled = enabled)
    return snapshot
  }

  override fun setMaintainCellMapper(maintainCellMapper: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(maintainCellMapper = maintainCellMapper)
    return snapshot
  }

  override fun setReturnToOrchestratorAfterForegroundWork(returnToOrchestratorAfterForegroundWork: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(returnToOrchestratorAfterForegroundWork = returnToOrchestratorAfterForegroundWork)
    return snapshot
  }

  override fun setDispatchInterval(dispatchInterval: PhoneAutomationDispatchInterval): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(dispatchInterval = dispatchInterval)
    return snapshot
  }

  override fun setTouchBrightnessEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(
      touchBrightnessEnabled = enabled,
      touchBrightnessRestoreMode = if (enabled) null else snapshot.touchBrightnessRestoreMode,
      touchBrightnessRestoreValue = if (enabled) null else snapshot.touchBrightnessRestoreValue
    )
    return snapshot
  }

  override fun updateSetupState(state: PhoneAutomationSetupState, detail: String): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(setupState = state, setupDetail = detail)
    return snapshot
  }

  override fun updateRuntimeState(state: PhoneAutomationRuntimeState, detail: String): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(runtimeState = state, runtimeDetail = detail)
    return snapshot
  }

  override fun updateTouchBrightnessState(state: TouchBrightnessRuntimeState, detail: String): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(touchBrightnessState = state, touchBrightnessDetail = detail)
    return snapshot
  }

  override fun updateTouchBrightnessDebugDetail(detail: String): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(touchBrightnessDebugDetail = detail)
    return snapshot
  }

  override fun saveTouchBrightnessRestoreState(mode: Int?, value: Int?): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(touchBrightnessRestoreMode = mode, touchBrightnessRestoreValue = value)
    return snapshot
  }

  override fun clearTouchBrightnessRestoreState(): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(touchBrightnessRestoreMode = null, touchBrightnessRestoreValue = null)
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
      pendingRecoveryToken = ""
    )
    return snapshot
  }

  override fun recordTransientFailure(reason: String, observedAtMillis: Long): PhoneAutomationSettingsSnapshot {
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

private class UnusedRootExecutor : RootExecutor {
  override suspend fun isRootAvailable(): Boolean = false

  override suspend fun run(command: String, timeout: Duration): RootResult {
    return RootResult(
      exitCode = 1,
      stdout = "",
      stderr = "unused",
      command = command,
      durationMs = 0L
    )
  }

  override suspend fun runScript(script: String, timeout: Duration): RootResult {
    return RootResult(
      exitCode = 1,
      stdout = "",
      stderr = "unused",
      command = script,
      durationMs = 0L
    )
  }
}

private class FakeTouchPowerButtonPolicyController : TouchPowerButtonPolicyController {
  var acquireCalls = 0
  var restoreCalls = 0
  override suspend fun acquire(): Boolean { acquireCalls += 1; return true }
  override suspend fun restore(): Boolean { restoreCalls += 1; return true }
}
