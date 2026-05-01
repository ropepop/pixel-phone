package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
  private val preferredSource = RootTouchDevice(
    path = "/dev/input/event2",
    name = "synaptics_tcm_touch",
    score = 109
  )

  @Test
  fun startCapturesRestoreStateAndEntersBlackout() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    assertEquals(listOf(TouchBrightnessRuntime.DIM_PERCENT), deviceController.setBrightnessPercentCalls)
    assertEquals(1, overlayController.showCalls)
    assertEquals(1, store.load().touchBrightnessRestoreMode)
    assertEquals(127, store.load().touchBrightnessRestoreValue)
    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("synaptics_tcm_touch"))
  }

  @Test
  fun wakeOnlyTapReturnsToBlackoutAfterTheNormalTimeout() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    eventSource.emit(
      TouchBrightnessEvent.BlackoutWakeRequested(
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("waiting for touch confirmation"))
    assertTrue(store.load().touchBrightnessDebugDetail.contains("overlay=1"))

    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS + 100L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)

    eventSource.emit(
      TouchBrightnessEvent.BlackoutWakeRequested(
        observedAtUptimeMillis = testScheduler.currentTime,
        activePointerCount = 0,
        gestureEnded = true
      )
    )
    runCurrent()
    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))

    advanceTimeBy(TouchBrightnessRuntime.WAKE_CONFIRM_WINDOW_MILLIS)
    runCurrent()
    assertTrue(store.load().touchBrightnessDetail.contains("returning to blackout"))

    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS - TouchBrightnessRuntime.WAKE_CONFIRM_WINDOW_MILLIS)
    runCurrent()

    assertEquals(
      listOf(
        TouchBrightnessRuntime.DIM_PERCENT,
        TouchBrightnessRuntime.DIM_PERCENT
      ),
      deviceController.setBrightnessPercentCalls
    )
    assertEquals(
      listOf(ScreenBrightnessState(mode = 1, value = 127)),
      deviceController.restoreBrightnessStateCalls
    )
    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
  }

  @Test
  fun wakeRequestTreatsAnAlreadyActiveTouchAsStillInProgress() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    eventSource.setActiveTouchCountWithoutEmitting(1)
    eventSource.emit(
      TouchBrightnessEvent.BlackoutWakeRequested(
        observedAtUptimeMillis = testScheduler.currentTime,
        activePointerCount = 0,
        gestureEnded = true
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("touch is active"))

    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS + 100L)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDebugDetail.contains("touches=1"))
  }

  @Test
  fun wakePlusRealTouchConfirmationStaysBrightUntilTheLastTouchIsLifted() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    eventSource.emit(TouchBrightnessEvent.BlackoutWakeRequested(observedAtUptimeMillis = testScheduler.currentTime))
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("waiting for touch confirmation"))

    eventSource.emit(
      TouchBrightnessEvent.BlackoutWakeRequested(
        observedAtUptimeMillis = testScheduler.currentTime,
        activePointerCount = 0,
        gestureEnded = true
      )
    )
    runCurrent()
    assertTrue(store.load().touchBrightnessDetail.contains("touch is active"))

    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS + 100L)
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)

    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
  }

  @Test
  fun manualSavedBrightnessLabelPrefersDisplayPercentageOverLegacyValue() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController().apply {
      currentBrightnessState = ScreenBrightnessState(
        mode = 0,
        value = 6,
        displayPercentage = 30.000002f
      )
    }
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("30%"))
  }

  @Test
  fun multitouchKeepsScreenVisibleUntilTheLastFingerIsLifted() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.BlackoutWakeRequested(observedAtUptimeMillis = testScheduler.currentTime))
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 2,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()
    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    eventSource.emit(
      TouchBrightnessEvent.BlackoutWakeRequested(
        observedAtUptimeMillis = testScheduler.currentTime,
        activePointerCount = 0,
        gestureEnded = true
      )
    )
    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS + 100L)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)

    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
  }

  @Test
  fun touchWhileScreenIsOffWakesAndHoldsUntilBlackoutAfterRelease() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = false,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val powerController = FakeTouchScreenPowerController()
    val runtime = buildRuntime(
      backgroundScope,
      store,
      deviceController,
      overlayController,
      eventSource,
      powerController = powerController
    ) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.SUSPENDED_SCREEN_OFF, store.load().touchBrightnessState)

    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime,
        snapshot = RootTouchSnapshot(
          activeTouchCount = 1,
          btnTouchActive = true,
          selectedDevice = preferredSource,
          lastEventUptimeMillis = testScheduler.currentTime
        )
      )
    )
    runCurrent()

    assertEquals(1, powerController.wakeCalls)
    assertTrue(powerController.wakeHoldActive)
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)

    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS)
    runCurrent()

    assertFalse(powerController.wakeHoldActive)
    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
  }

  @Test
  fun screenOnWithAlreadyHeldFingerStaysBright() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = false,
      activeTouchCount = 1,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) {
      testScheduler.currentTime
    }

    runtime.start()
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = true))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("touch is active"))
  }

  @Test
  fun overlayUnavailableWhileIdleSurfacesErrorAndRetries() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController(available = false)
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = false,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    assertEquals(emptyList<Int>(), deviceController.setBrightnessPercentCalls)
    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("retrying"))

    overlayController.setAvailable(true)
    eventSource.emit(TouchBrightnessEvent.OverlayAvailabilityChanged(available = true))
    advanceTimeBy(TouchBrightnessRuntime.SESSION_RETRY_INITIAL_DELAY_MILLIS)
    runCurrent()

    assertEquals(2, overlayController.showCalls)
    assertEquals(listOf(TouchBrightnessRuntime.DIM_PERCENT), deviceController.setBrightnessPercentCalls)
    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("Panel dim waiting for touch"))
  }

  @Test
  fun debugDetailReflectsTouchSourceAndPendingBlackoutTimer() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    assertTrue(store.load().touchBrightnessDebugDetail.contains("touches=0"))
    assertTrue(store.load().touchBrightnessDebugDetail.contains("source=synaptics_tcm_touch"))

    eventSource.emit(
      TouchBrightnessEvent.BlackoutWakeRequested(
        observedAtUptimeMillis = testScheduler.currentTime,
        activePointerCount = 0,
        gestureEnded = true
      )
    )
    runCurrent()

    assertTrue(store.load().touchBrightnessDebugDetail.contains("timer=pending("))
  }

  @Test
  fun overlayLossWhileTouchIsActiveDoesNotForceBlackoutMidTouch() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 1,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    overlayController.setAvailable(false)
    eventSource.emit(TouchBrightnessEvent.OverlayAvailabilityChanged(available = false))
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
    assertEquals(emptyList<Int>(), deviceController.setBrightnessPercentCalls)
    assertEquals(
      listOf(ScreenBrightnessState(mode = 1, value = 127)),
      deviceController.restoreBrightnessStateCalls
    )

    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 0,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    advanceTimeBy(TouchBrightnessRuntime.IDLE_BLACKOUT_DELAY_MILLIS)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("retrying"))
    assertEquals(emptyList<Int>(), deviceController.setBrightnessPercentCalls)
    assertEquals(
      listOf(ScreenBrightnessState(mode = 1, value = 127)),
      deviceController.restoreBrightnessStateCalls
    )
  }

  @Test
  fun screenOffSuspendsAndScreenOnWithoutTouchReturnsToBlackout() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.BlackoutWakeRequested(observedAtUptimeMillis = testScheduler.currentTime))
    runCurrent()

    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = false))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.SUSPENDED_SCREEN_OFF, store.load().touchBrightnessState)

    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = true))
    runCurrent()
    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
  }

  @Test
  fun panelDimGuardStopsWhileTouchIsActive() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(
      scope = backgroundScope,
      store = store,
      deviceController = deviceController,
      overlayController = overlayController,
      eventSource = eventSource,
      uptimeClock = { testScheduler.currentTime },
      dimGuardEnabled = true
    )

    runtime.start()
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS)
    runCurrent()

    assertEquals(
      listOf(
        TouchBrightnessRuntime.DIM_PERCENT,
        TouchBrightnessRuntime.DIM_PERCENT
      ),
      deviceController.setBrightnessPercentCalls
    )

    eventSource.emit(
      TouchBrightnessEvent.TouchCountChanged(
        activeTouchCount = 1,
        observedAtUptimeMillis = testScheduler.currentTime
      )
    )
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS * 2)
    runCurrent()

    assertEquals(
      listOf(
        TouchBrightnessRuntime.DIM_PERCENT,
        TouchBrightnessRuntime.DIM_PERCENT
      ),
      deviceController.setBrightnessPercentCalls
    )
    assertEquals(TouchBrightnessRuntimeState.BRIGHT, store.load().touchBrightnessState)
  }

  @Test
  fun panelDimGuardRetriesTransientForegroundBrightnessPushBeforeFailingSession() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController().apply {
      setBrightnessResults += PhoneAutomationActionResult(true, "initial dim")
      setBrightnessResults += PhoneAutomationActionResult(false, "foreground app pushed brightness")
      setBrightnessResults += PhoneAutomationActionResult(true, "guard recovered")
    }
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(
      scope = backgroundScope,
      store = store,
      deviceController = deviceController,
      overlayController = overlayController,
      eventSource = eventSource,
      uptimeClock = { testScheduler.currentTime },
      dimGuardEnabled = true
    )

    runtime.start()
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("retrying"))

    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
    assertEquals(
      listOf(
        TouchBrightnessRuntime.DIM_PERCENT,
        TouchBrightnessRuntime.DIM_PERCENT,
        TouchBrightnessRuntime.DIM_PERCENT
      ),
      deviceController.setBrightnessPercentCalls
    )
  }

  @Test
  fun panelDimGuardStopsWhenScreenTurnsOff() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(
      scope = backgroundScope,
      store = store,
      deviceController = deviceController,
      overlayController = overlayController,
      eventSource = eventSource,
      uptimeClock = { testScheduler.currentTime },
      dimGuardEnabled = true
    )

    runtime.start()
    runCurrent()
    eventSource.emit(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = false))
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS * 2)
    runCurrent()

    assertEquals(listOf(TouchBrightnessRuntime.DIM_PERCENT), deviceController.setBrightnessPercentCalls)
    assertEquals(TouchBrightnessRuntimeState.SUSPENDED_SCREEN_OFF, store.load().touchBrightnessState)
  }

  @Test
  fun panelDimGuardStopsWhenFeatureIsDisabled() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(
      scope = backgroundScope,
      store = store,
      deviceController = deviceController,
      overlayController = overlayController,
      eventSource = eventSource,
      uptimeClock = { testScheduler.currentTime },
      dimGuardEnabled = true
    )

    runtime.start()
    runCurrent()
    store.setTouchBrightnessEnabled(false)
    runtime.stop(reason = "disabled:test")
    runCurrent()
    advanceTimeBy(TouchBrightnessRuntime.PANEL_DIM_GUARD_INTERVAL_MILLIS * 2)
    runCurrent()

    assertEquals(listOf(TouchBrightnessRuntime.DIM_PERCENT), deviceController.setBrightnessPercentCalls)
    assertEquals(
      listOf(ScreenBrightnessState(mode = 1, value = 127)),
      deviceController.restoreBrightnessStateCalls
    )
    assertEquals(TouchBrightnessRuntimeState.DISABLED, store.load().touchBrightnessState)
  }

  @Test
  fun disablingRestoresSavedBrightnessStateAndHidesOverlay() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    store.setTouchBrightnessEnabled(false)

    runtime.stop(reason = "disabled:test")
    advanceUntilIdle()

    assertEquals(
      listOf(ScreenBrightnessState(mode = 1, value = 127)),
      deviceController.restoreBrightnessStateCalls
    )
    assertTrue(overlayController.hideCalls >= 1)
    assertEquals(null, store.load().touchBrightnessRestoreMode)
    assertEquals(null, store.load().touchBrightnessRestoreValue)
    assertEquals(TouchBrightnessRuntimeState.DISABLED, store.load().touchBrightnessState)
  }

  @Test
  fun quickDisableThenReEnableRestartsTouchBrightnessInsteadOfLeavingItDisabled() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController().apply {
      restoreDelayMillis = 1_000L
    }
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()

    store.setTouchBrightnessEnabled(false)
    runtime.stop(reason = "disabled:quick")
    store.setTouchBrightnessEnabled(true)
    runtime.start()
    advanceUntilIdle()

    assertTrue(store.load().touchBrightnessEnabled)
    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
  }

  @Test
  fun rootTouchFatalErrorKeepsTheToggleEnabledAndRetries() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController()
    val overlayController = FakeBlackoutOverlayController()
    val firstEventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val secondEventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val eventSources = ArrayDeque(
      listOf(firstEventSource, secondEventSource)
    )
    val runtime = buildRuntime(
      scope = backgroundScope,
      store = store,
      deviceController = deviceController,
      overlayController = overlayController,
      eventSource = firstEventSource,
      uptimeClock = { testScheduler.currentTime },
      eventSourceFactory = { eventSources.removeFirst() }
    )

    runtime.start()
    runCurrent()
    firstEventSource.emit(TouchBrightnessEvent.FatalError("All touchscreen input sources failed"))
    runCurrent()

    assertTrue(store.load().touchBrightnessEnabled)
    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("retrying"))

    advanceTimeBy(TouchBrightnessRuntime.SESSION_RETRY_INITIAL_DELAY_MILLIS)
    runCurrent()

    assertEquals(TouchBrightnessRuntimeState.BLACKOUT_IDLE, store.load().touchBrightnessState)
    assertEquals(2, overlayController.showCalls)
    assertEquals(
      listOf(
        TouchBrightnessRuntime.DIM_PERCENT,
        TouchBrightnessRuntime.DIM_PERCENT
      ),
      deviceController.setBrightnessPercentCalls
    )
  }

  @Test
  fun disablingWithRestoreFailureKeepsTheToggleOffAndSurfacesTheWarning() = runTest {
    val store = InMemoryTouchBrightnessSettingsStore(touchBrightnessEnabled = true)
    val deviceController = FakeTouchBrightnessDeviceController().apply {
      restoreBrightnessResult = PhoneAutomationActionResult(
        success = false,
        detail = "restore verification failed"
      )
    }
    val overlayController = FakeBlackoutOverlayController()
    val eventSource = FakeTouchBrightnessEventSource(
      interactive = true,
      activeTouchCount = 0,
      overlayAvailable = true,
      source = preferredSource
    )
    val runtime = buildRuntime(backgroundScope, store, deviceController, overlayController, eventSource) { testScheduler.currentTime }

    runtime.start()
    runCurrent()
    store.setTouchBrightnessEnabled(false)

    runtime.stop(reason = "disabled:test")
    advanceUntilIdle()

    assertFalse(store.load().touchBrightnessEnabled)
    assertEquals(TouchBrightnessRuntimeState.ERROR, store.load().touchBrightnessState)
    assertTrue(store.load().touchBrightnessDetail.contains("Brightness restore failed"))
  }

  private fun buildRuntime(
    scope: CoroutineScope,
    store: InMemoryTouchBrightnessSettingsStore,
    deviceController: FakeTouchBrightnessDeviceController,
    overlayController: FakeBlackoutOverlayController,
    eventSource: FakeTouchBrightnessEventSource,
    powerController: FakeTouchScreenPowerController = FakeTouchScreenPowerController(),
    eventSourceFactory: (() -> TouchBrightnessEventSource)? = null,
    dimGuardEnabled: Boolean = false,
    uptimeClock: () -> Long
  ): TouchBrightnessRuntime {
    return TouchBrightnessRuntime(
      context = ContextWrapper(null),
      scope = scope,
      settingsStore = store,
      rootExecutor = UnusedRootExecutor(),
      onSnapshotChanged = {},
      deviceController = deviceController,
      overlayController = overlayController,
      powerController = powerController,
      eventSourceFactory = eventSourceFactory ?: { eventSource },
      uptimeClock = uptimeClock,
      dimGuardEnabled = dimGuardEnabled
    )
  }
}

private class FakeTouchBrightnessEventSource(
  interactive: Boolean,
  activeTouchCount: Int,
  overlayAvailable: Boolean,
  source: RootTouchDevice? = null
) : TouchBrightnessEventSource {
  private val mutableEvents = MutableSharedFlow<TouchBrightnessEvent>(extraBufferCapacity = 16)

  override val events: Flow<TouchBrightnessEvent> = mutableEvents
  private var interactiveState = interactive
  private var activeTouchCountState = activeTouchCount
  private var overlayAvailableState = overlayAvailable
  private var sourceState = source
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

  fun setActiveTouchCountWithoutEmitting(value: Int) {
    activeTouchCountState = value
    btnTouchActiveState = value > 0
    activeSlotCountState = value
  }

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
      is TouchBrightnessEvent.BlackoutWakeRequested -> Unit
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
  var restoreDelayMillis = 0L
  val setBrightnessResults = ArrayDeque<PhoneAutomationActionResult>()
  val setBrightnessPercentCalls = mutableListOf<Int>()
  val restoreBrightnessStateCalls = mutableListOf<ScreenBrightnessState>()

  override suspend fun prepare(): PhoneAutomationPreparationResult = prepareResult

  override suspend fun readBrightnessState(): ScreenBrightnessState? = currentBrightnessState

  override suspend fun setBrightnessPercent(percent: Int): PhoneAutomationActionResult {
    setBrightnessPercentCalls += percent
    currentBrightnessState = ScreenBrightnessState(
      mode = 0,
      value = ScreenBrightnessControl.legacySystemValue(percent),
      displayPercentage = percent.toFloat()
    )
    return setBrightnessResults.removeFirstOrNull() ?: setBrightnessResult
  }

  override suspend fun restoreBrightnessState(state: ScreenBrightnessState): PhoneAutomationActionResult {
    if (restoreDelayMillis > 0L) {
      delay(restoreDelayMillis)
    }
    restoreBrightnessStateCalls += state
    currentBrightnessState = state
    return restoreBrightnessResult
  }
}

private class FakeBlackoutOverlayController(
  private var available: Boolean = true
) : BlackoutOverlayController {
  var showCalls = 0
  var hideCalls = 0
  var showSuccess = true
  var hideSuccess = true

  override suspend fun show(): PhoneAutomationActionResult {
    showCalls += 1
    return PhoneAutomationActionResult(
      success = available && showSuccess,
      detail = if (available && showSuccess) "shown" else "unavailable"
    )
  }

  override suspend fun hide(): PhoneAutomationActionResult {
    hideCalls += 1
    return PhoneAutomationActionResult(
      success = hideSuccess,
      detail = if (hideSuccess) "hidden" else "hide failed"
    )
  }

  override fun isAvailable(): Boolean = available

  fun setAvailable(value: Boolean) {
    available = value
  }
}

private class FakeTouchScreenPowerController : TouchScreenPowerController {
  override var wakeHoldActive = false
    private set
  var wakeCalls = 0
  val holdReasons = mutableListOf<String>()
  val releaseReasons = mutableListOf<String>()

  override suspend fun wakeScreen(reason: String): PhoneAutomationActionResult {
    wakeCalls += 1
    holdScreen("wake:$reason")
    return PhoneAutomationActionResult(true, "woke")
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
  touchBrightnessEnabled: Boolean
) : PhoneAutomationSettingsStore {
  private var snapshot = PhoneAutomationSettingsSnapshot(
    enabled = false,
    touchBrightnessEnabled = touchBrightnessEnabled
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
    snapshot = snapshot.copy(
      touchBrightnessEnabled = enabled,
      touchBrightnessDebugDetail = if (enabled) {
        "Waiting for live touch data"
      } else {
        ""
      },
      touchBrightnessRestoreMode = if (enabled) {
        null
      } else {
        snapshot.touchBrightnessRestoreMode
      },
      touchBrightnessRestoreValue = if (enabled) {
        null
      } else {
        snapshot.touchBrightnessRestoreValue
      }
    )
    return snapshot
  }

  override fun updateSetupState(
    state: PhoneAutomationSetupState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(setupState = state, setupDetail = detail)
    return snapshot
  }

  override fun updateRuntimeState(
    state: PhoneAutomationRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    snapshot = snapshot.copy(runtimeState = state, runtimeDetail = detail)
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
      pendingRecoveryToken = ""
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
