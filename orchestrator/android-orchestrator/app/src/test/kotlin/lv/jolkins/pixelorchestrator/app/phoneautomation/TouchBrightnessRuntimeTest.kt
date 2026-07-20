package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.Duration
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult

@OptIn(ExperimentalCoroutinesApi::class)
class TouchBrightnessRuntimeTest {
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
  fun panelSleepUsesRealZeroAfterTwoMinutes() {
    assertEquals(0, TouchBrightnessRuntime.PANEL_SLEEP_PERCENT)
    assertEquals(120_000L, TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS)
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
  fun panelSleepPreparesAndShowsBrightnessShield() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val shieldController = FakePanelSleepBrightnessShieldController(deviceController.operationCalls)
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
      shieldController = shieldController
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    assertEquals(1, shieldController.prepareCalls)
    assertEquals(1, shieldController.showCalls)
    assertTrue(shieldController.isVisible())
    assertTrue(
      deviceController.operationCalls.indexOf("shield:show") <
        deviceController.operationCalls.indexOf("set:0")
    )
  }

  @Test
  fun shieldShowFailureFallsBackToRawPanelClampAndPanelSleep() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val shieldController = FakePanelSleepBrightnessShieldController().apply {
      showResult = PhoneAutomationActionResult(false, "overlay unavailable")
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
      ),
      shieldController = shieldController
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    assertFalse(shieldController.isVisible())
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
  }

  @Test
  fun physicalTouchRemovesBrightnessShieldBeforeVisibleRestore() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val shieldController = FakePanelSleepBrightnessShieldController(deviceController.operationCalls)
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
      shieldController = shieldController
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    deviceController.operationCalls.clear()

    eventSource.emit(physicalTouchEvent(activeTouchCount = 1, observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()

    assertFalse(shieldController.isVisible())
    assertTrue(
      deviceController.operationCalls.indexOf("shield:hide") <
        deviceController.operationCalls.indexOf("restore")
    )
  }

  @Test
  fun shieldRemovalFailureBlocksVisibleRestoreAndRemainsRetryable() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val shieldController = FakePanelSleepBrightnessShieldController(deviceController.operationCalls)
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
      shieldController = shieldController
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    shieldController.hideResult = PhoneAutomationActionResult(false, "remove failed")

    eventSource.emit(physicalTouchEvent(activeTouchCount = 1, observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()

    assertTrue(shieldController.isVisible())
    assertTrue(deviceController.restoreBrightnessStateCalls.isEmpty())
    assertTrue(shieldController.hideCalls >= 1)
  }

  @Test
  fun transientSessionFailureKeepsPanelSleepShieldAttachedDuringRetryBackoff() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val shieldController = FakePanelSleepBrightnessShieldController(deviceController.operationCalls)
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
      shieldController = shieldController
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.FatalError("transient monitor failure"))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
    assertTrue(shieldController.isVisible())
    assertEquals(0, shieldController.hideCalls)
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
  fun powerButtonDuringPanelSleepRestoresPanelAndStartsTimerAgain() = runTest {
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

    eventSource.emit(
      TouchBrightnessEvent.PowerButtonPressed(
        observedAtUptimeMillis = testScheduler.currentTime,
        device = powerSource
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(1, powerController.forceWakeCalls)
    assertEquals(ScreenBrightnessState(mode = 1, value = 127), deviceController.restoreBrightnessStateCalls.last())

    advanceTimeBy(TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS)
    runCurrent()
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
  }

  @Test
  fun screenOffImmediatelyAfterPanelSleepPowerButtonIsReboundedAwake() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val powerController = FakeTouchScreenPowerController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, FakeTouchBrightnessDeviceController(), FakeBlackoutOverlayController(), eventSource, powerController) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = false))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(2, powerController.forceWakeCalls)
    assertFalse(store.load().touchBrightnessDetail.contains("Suspended"))
  }

  @Test
  fun delayedScreenOffAfterPanelSleepPowerButtonKeepsOriginalTwoMinuteTimer() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val powerController = FakeTouchScreenPowerController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      source = touchSource,
      powerSource = powerSource
    )
    val runtime = buildRuntime(backgroundScope, store, FakeTouchBrightnessDeviceController(), FakeBlackoutOverlayController(), eventSource, powerController) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    val powerPressedAtMillis = testScheduler.currentTime
    eventSource.emit(TouchBrightnessEvent.PowerButtonPressed(powerPressedAtMillis, powerSource))
    runCurrent()

    advanceTimeBy(TouchBrightnessRuntime.POWER_BUTTON_REBOUND_WINDOW_MILLIS + 1L)
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = false))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(2, powerController.forceWakeCalls)
    assertFalse(store.load().touchBrightnessDetail.contains("Suspended"))

    val remainingMillis = powerPressedAtMillis +
      TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS -
      testScheduler.currentTime
    advanceTimeBy(remainingMillis - 1L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)

    advanceTimeBy(1L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
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
  fun powerButtonVisibleIdleIgnoresTicketNonTouchUntilTwoMinuteTimerExpires() = runTest {
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
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    eventSource.emit(TouchBrightnessEvent.PowerButtonPressed(testScheduler.currentTime, powerSource))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))

    eventSource.emit(
      TouchBrightnessEvent.NonTouchInput(
        reason = "ticket:wake_start:manual_power_check",
        observedAtUptimeMillis = testScheduler.currentTime,
        suppressedUntilUptimeMillis = testScheduler.currentTime + 2_000L
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))

    advanceTimeBy(TouchBrightnessRuntime.IDLE_PANEL_SLEEP_DELAY_MILLIS - 1L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(listOf(0), deviceController.setBrightnessPercentCalls)

    advanceTimeBy(1L)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
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
  fun ordinaryScreenOffOutsidePowerReboundSuspendsRuntime() = runTest {
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

    assertEquals(TouchBrightnessRuntimeState.SUSPENDED_SCREEN_OFF, store.load().touchBrightnessState)
  }

  @Test
  fun disableRestoresVisibleBrightnessFromPanelSleep() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val shieldController = FakePanelSleepBrightnessShieldController(deviceController.operationCalls)
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
      shieldController = shieldController
    ) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    store.setTouchBrightnessEnabled(false)

    runtime.stop(reason = "disabled:test")
    advanceUntilIdle()

    assertEquals(TouchBrightnessRuntimeState.DISABLED, store.load().touchBrightnessState)
    assertFalse(shieldController.isVisible())
    assertTrue(
      deviceController.operationCalls.lastIndexOf("shield:hide") <
        deviceController.operationCalls.lastIndexOf("restore")
    )
    assertEquals(ScreenBrightnessState(mode = 1, value = 127), deviceController.restoreBrightnessStateCalls.last())
    assertEquals(null, store.load().touchBrightnessRestoreMode)
    assertEquals(null, store.load().touchBrightnessRestoreValue)
  }

  @Test
  fun enabledStopKeepsAttachedShieldAndSavedRestoreStateForSupervisorHandoff() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val shieldController = FakePanelSleepBrightnessShieldController()
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
      shieldController = shieldController
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    assertTrue(shieldController.isVisible())

    runtime.stop(reason = "service_destroyed")
    advanceUntilIdle()

    assertTrue(shieldController.isVisible())
    assertEquals(0, shieldController.hideCalls)
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
  fun nonTouchInputEventDuringPanelSleepReassertsImmediatelyAndBursts() = runTest {
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

    advanceTimeBy(99L)
    runCurrent()
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)

    advanceTimeBy(1L)
    runCurrent()
    assertEquals(listOf(0, 0, 0), deviceController.setBrightnessPercentCalls)

    advanceTimeBy(149L)
    runCurrent()
    assertEquals(listOf(0, 0, 0), deviceController.setBrightnessPercentCalls)

    advanceTimeBy(1L)
    runCurrent()
    assertEquals(listOf(0, 0, 0, 0), deviceController.setBrightnessPercentCalls)
  }

  @Test
  fun ticketNonTouchInputCorrectsBrightIdleLeftByMisclassifiedSoftwareTouch() = runTest {
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
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
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

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=none"))
  }

  @Test
  fun genericNonTouchInputCorrectsBrightIdleLeftByMisclassifiedSoftwareAction() = runTest {
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
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
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

    assertEquals(TouchBrightnessRuntimeState.PANEL_SLEEP, store.load().touchBrightnessState)
    assertEquals(listOf(0, 0), deviceController.setBrightnessPercentCalls)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=none"))
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
  fun physicalScreenOnDuringPanelSleepRestoresVisibleBrightness() = runTest {
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

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(restoreCallsAfterPanelSleep + 1, deviceController.restoreBrightnessStateCalls.size)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))
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
    shieldController: FakePanelSleepBrightnessShieldController = FakePanelSleepBrightnessShieldController(),
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
      panelSleepShieldController = shieldController,
      powerController = powerController,
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
      is TouchBrightnessEvent.PowerButtonPressed -> powerSourceState = event.device ?: powerSourceState
      is TouchBrightnessEvent.BlackoutWakeRequested -> Unit
      is TouchBrightnessEvent.NonTouchInput -> Unit
      is TouchBrightnessEvent.FatalError -> Unit
    }
    mutableEvents.tryEmit(event)
  }
}

private class FakeTouchBrightnessDeviceController : TouchBrightnessDeviceController {
  var prepareResult = PhoneAutomationPreparationResult(ready = true, detail = "ready")
  var currentBrightnessState = ScreenBrightnessState(mode = 1, value = 127)
  var setBrightnessResult = PhoneAutomationActionResult(true, "set")
  var restoreBrightnessResult = PhoneAutomationActionResult(true, "restored")
  var readBrightnessStateCalls = 0
  val setBrightnessPercentCalls = mutableListOf<Int>()
  val restoreBrightnessStateCalls = mutableListOf<ScreenBrightnessState>()
  val operationCalls = mutableListOf<String>()

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
    currentBrightnessState = ScreenBrightnessState(
      mode = 0,
      value = ScreenBrightnessControl.legacySystemValue(percent),
      displayPercentage = percent.toFloat()
    )
    return setBrightnessResult
  }

  override suspend fun restoreBrightnessState(state: ScreenBrightnessState): PhoneAutomationActionResult {
    operationCalls += "restore"
    restoreBrightnessStateCalls += state
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

private class FakePanelSleepBrightnessShieldController(
  private val operationCalls: MutableList<String>? = null
) : PanelSleepBrightnessShieldController {
  var prepareResult = PhoneAutomationActionResult(true, "ready")
  var showResult = PhoneAutomationActionResult(true, "shown")
  var hideResult = PhoneAutomationActionResult(true, "hidden")
  var prepareCalls = 0
  var showCalls = 0
  var hideCalls = 0
  private var visible = false

  override suspend fun prepare(): PhoneAutomationActionResult {
    prepareCalls += 1
    operationCalls?.add("shield:prepare")
    return prepareResult
  }

  override suspend fun show(): PhoneAutomationActionResult {
    showCalls += 1
    operationCalls?.add("shield:show")
    if (showResult.success) {
      visible = true
    }
    return showResult
  }

  override suspend fun hide(): PhoneAutomationActionResult {
    hideCalls += 1
    operationCalls?.add("shield:hide")
    if (hideResult.success) {
      visible = false
    }
    return hideResult
  }

  override fun isVisible(): Boolean = visible
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
