package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAutomationBridgeTest {
  @Test
  fun physicalRevealWaitsForEveryExactHelperOwner() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.registerPhysicalVisibilityBlocker("first")
    PhoneAutomationServiceBridge.registerPhysicalVisibilityBlocker("second")
    val reveal = async { PhoneAutomationServiceBridge.awaitPhysicalVisibilityUnblocked() }
    runCurrent()
    assertFalse(reveal.isCompleted)

    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("first")
    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("first")
    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("unrelated")
    runCurrent()
    assertEquals(setOf("second"), PhoneAutomationServiceBridge.currentPhysicalVisibilityBlockers())
    assertFalse(reveal.isCompleted)

    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("second")
    runCurrent()
    assertTrue(reveal.await())
  }

  @Test
  fun helperStopTimeoutCannotAuthorizeRevealOrForgetOwnership() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.registerPhysicalVisibilityBlocker("unproved")

    assertFalse(PhoneAutomationServiceBridge.awaitPhysicalVisibilityUnblocked(timeoutMillis = 100L))
    assertEquals(setOf("unproved"), PhoneAutomationServiceBridge.currentPhysicalVisibilityBlockers())
  }

  @Test
  fun visibilitySettingsAndCancelledWaiterDoNotClearLiveHelperOwnership() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.registerPhysicalVisibilityBlocker("owned")
    val reveal = async { PhoneAutomationServiceBridge.awaitPhysicalVisibilityUnblocked() }
    runCurrent()
    reveal.cancel()
    runCurrent()
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(1_000L)
    PhoneAutomationServiceBridge.revokePhysicalVisibility(1_100L)
    PhoneAutomationServiceBridge.configurePhysicalVisibility(false)

    assertEquals(setOf("owned"), PhoneAutomationServiceBridge.currentPhysicalVisibilityBlockers())
    assertFalse(PhoneAutomationServiceBridge.awaitPhysicalVisibilityUnblocked(timeoutMillis = 0L))
    PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker("owned")
    assertTrue(PhoneAutomationServiceBridge.awaitPhysicalVisibilityUnblocked(timeoutMillis = 0L))
  }

  @Test
  fun physicalDownAndUpEachKeepThePhoneVisibleForNinetySeconds() {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_000L)
    val down = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    assertEquals(91_000L, down.deadlineUptimeMillis)

    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_075L)
    val up = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    assertEquals(91_075L, up.deadlineUptimeMillis)
    assertEquals(down.generation + 1L, up.generation)
    assertEquals(1L, PhoneAutomationServiceBridge.currentRootPhysicalTouchState().touchBeginCount)
  }

  @Test
  fun duplicateRuntimeDeliveryAndOlderTouchesDoNotMoveTheWindow() {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_000L)
    val down = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(1_000L, active = true)
    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(900L, active = false)
    assertEquals(down, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())

    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_075L)
    val up = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(1_075L, active = false)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_100L)
    assertEquals(up, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())
  }

  @Test
  fun syntheticInputAndAccessibilityEventsCannotGrantOrRenewVisibility() {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    val dark = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:action", 4_000L, 1_000L)
    PhoneAutomationServiceBridge.recordTouchInteractionStarted(1_000L)
    PhoneAutomationServiceBridge.recordTouchInteractionEnded(1_100L)
    assertEquals(dark, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())

    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(2_000L)
    val visible = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:next", 4_000L, 3_000L)
    PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(3_100L)
    assertEquals(visible, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())
  }

  @Test
  fun disablingClearsVisibilityAndReenablingNeedsAFreshContact() {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 500L)
    assertEquals(0L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 600L)
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_000L)
    val visible = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    assertEquals(visible, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())

    PhoneAutomationServiceBridge.configurePhysicalVisibility(false)
    assertEquals(0L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_100L)
    assertEquals(0L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_200L)
    assertEquals(91_200L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
  }

  @Test
  fun sideButtonRevokesAndTheHeldContactCannotReopenVisibility() {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_000L)
    PhoneAutomationServiceBridge.revokePhysicalVisibility(1_100L)
    val revoked = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    assertEquals(0L, revoked.deadlineUptimeMillis)

    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(1_050L, active = true)
    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(1_100L, active = true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_150L)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_200L)
    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(1_200L, active = false)
    PhoneAutomationServiceBridge.revokePhysicalVisibility(1_100L)
    PhoneAutomationServiceBridge.revokePhysicalVisibility(1_050L)
    assertEquals(revoked, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())

    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_300L)
    assertEquals(91_300L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
  }

  @Test
  fun sourceLossDoesNotRenewVisibilityOrUndoPowerRevocation() {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.configurePhysicalVisibility(true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_000L)
    val visible = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_050L, available = false)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_060L)
    assertEquals(visible, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())

    PhoneAutomationServiceBridge.revokePhysicalVisibility(1_100L)
    val revoked = PhoneAutomationServiceBridge.currentPhysicalVisibleWindow()
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_200L, available = false)
    PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(1_300L, active = false)
    assertEquals(revoked, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow())
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_400L)
    assertEquals(91_400L, PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis)
  }

  @Test
  fun rootTouchOccurrenceSurvivesReleaseAndSourceRestart() {
    PhoneAutomationServiceBridge.resetForTests()
    fun record(active: Boolean, available: Boolean = true) =
      PhoneAutomationServiceBridge.recordRootPhysicalTouchState(active, 100L, available)
    record(false)
    record(true)
    record(true)
    record(false)
    assertEquals(1L, PhoneAutomationServiceBridge.currentRootPhysicalTouchState().touchBeginCount)
    record(false, available = false)
    record(false)
    assertEquals(1L, PhoneAutomationServiceBridge.currentRootPhysicalTouchState().touchBeginCount)
    record(true)
    record(false)
    val final = PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
    assertFalse(final.active)
    assertEquals(2L, final.touchBeginCount)
  }

  @Test
  fun accessibilityPermissionRequiresGlobalAccessibilityToggle() {
    assertFalse(
      PhoneAutomationServiceBridge.hasEnabledAccessibilityPermission(
        accessibilityGloballyEnabled = false,
        componentEnabled = true
      )
    )
    assertFalse(
      PhoneAutomationServiceBridge.hasEnabledAccessibilityPermission(
        accessibilityGloballyEnabled = true,
        componentEnabled = false
      )
    )
    assertTrue(
      PhoneAutomationServiceBridge.hasEnabledAccessibilityPermission(
        accessibilityGloballyEnabled = true,
        componentEnabled = true
      )
    )
  }

  @Test
  fun accessibilityConnectionWaitsForATransientServiceRebind() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost()
    val pending = async {
      PhoneAutomationServiceBridge.awaitAccessibilityConnection(timeoutMillis = 5_000L)
    }

    runCurrent()
    assertFalse(pending.isCompleted)

    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    runCurrent()

    assertTrue(pending.await())
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
  }

  @Test
  fun ticketSliderFullStrokeDistinguishesCompletedRejectedAndUnknownResults() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    assertEquals(
      TicketSliderGestureDispatchResult.REJECTED,
      PhoneAutomationServiceBridge.performTicketSliderFullStroke(
        "com.pv.vivi",
        10, 20, 90, 20, 800L, 10L
      )
    )

    val host = FakeAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    host.ticketSliderFullStrokeResult = TicketSliderGestureDispatchResult.COMPLETED
    assertEquals(
      TicketSliderGestureDispatchResult.COMPLETED,
      PhoneAutomationServiceBridge.performTicketSliderFullStroke(
        "com.pv.vivi",
        10, 20, 90, 20, 800L, 10L
      )
    )
    host.ticketSliderFullStrokeResult = TicketSliderGestureDispatchResult.REJECTED
    assertEquals(
      TicketSliderGestureDispatchResult.REJECTED,
      PhoneAutomationServiceBridge.performTicketSliderFullStroke(
        "com.pv.vivi",
        10, 20, 90, 20, 800L, 10L
      )
    )
    host.ticketSliderFullStrokeNeverReturns = true
    assertEquals(
      TicketSliderGestureDispatchResult.UNKNOWN,
      PhoneAutomationServiceBridge.performTicketSliderFullStroke(
        "com.pv.vivi",
        10, 20, 90, 20, 800L, 10L
      )
    )
    assertEquals(3, host.ticketSliderFullStrokeCalls)
    assertEquals(
      List(3) {
        RecordedTicketSliderFullStroke(
          expectedPackageName = "com.pv.vivi",
          startX = 10,
          startY = 20,
          endX = 90,
          endY = 20,
          durationMillis = 800L,
          timeoutMillis = 10L
        )
      },
      host.ticketSliderFullStrokeRequests
    )
    assertTrue(host.ticketSliderFullStrokeRequests.all { request ->
      request.endX > request.startX &&
        request.endY == request.startY &&
        request.durationMillis == 800L
    })
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
  }

  @Test
  fun stableTicketInputFenceIsInvalidatedBeforeTheHostCanReceiveAStroke() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      focusedInputWindow = PhoneAutomationFocusedInputWindow("com.pv.vivi", 17)
      ticketSliderFullStrokeResult = TicketSliderGestureDispatchResult.COMPLETED
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    val fence = PhoneAutomationServiceBridge.awaitStableTicketInputFence(
      expectedPackageName = "com.pv.vivi",
      timeoutMillis = 100L,
      stableMillis = 1L,
      elapsedRealtimeMillis = { testScheduler.currentTime }
    )
    assertNotNull(fence)
    assertTrue(PhoneAutomationServiceBridge.ticketInputFenceIsCurrent(requireNotNull(fence)))

    PhoneAutomationServiceBridge.recordTouchInteractionStarted(observedAtMillis = 10L)
    PhoneAutomationServiceBridge.recordTouchInteractionEnded(observedAtMillis = 11L)

    assertFalse(PhoneAutomationServiceBridge.ticketInputFenceIsCurrent(fence))
    assertEquals(
      TicketSliderGestureDispatchResult.REJECTED,
      PhoneAutomationServiceBridge.performTicketSliderFullStroke(
        "com.pv.vivi",
        10, 20, 90, 20, 800L, 10L,
        expectedInputFence = fence
      )
    )
    assertEquals(0, host.ticketSliderFullStrokeCalls)
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
  }

  @Test
  fun touchEventsArePublishedAndUpdateSharedState() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val observedEvents = mutableListOf<PhoneAutomationTouchEvent>()
    val job = backgroundScope.launch {
      PhoneAutomationServiceBridge.touchEvents.take(2).toList(observedEvents)
    }
    runCurrent()

    PhoneAutomationServiceBridge.recordTouchInteractionStarted(observedAtMillis = 10L)
    PhoneAutomationServiceBridge.recordTouchInteractionEnded(observedAtMillis = 20L)
    runCurrent()

    assertEquals(
      listOf(
        PhoneAutomationTouchEvent.Started(observedAtMillis = 10L),
        PhoneAutomationTouchEvent.Ended(observedAtMillis = 20L)
      ),
      observedEvents
    )
    assertFalse(PhoneAutomationServiceBridge.isTouchInteractionActive())
    job.cancel()
  }

  @Test
  fun rootConfirmedPhysicalTouchProjectionIsPublishedAndReset() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val observed = mutableListOf<PhoneAutomationRootPhysicalTouchState>()
    val job = backgroundScope.launch {
      PhoneAutomationServiceBridge.rootPhysicalTouchStates.take(2).toList(observed)
    }
    runCurrent()

    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(
      active = true,
      observedAtUptimeMillis = 55L
    )
    runCurrent()

    assertEquals(
      PhoneAutomationRootPhysicalTouchState(
        available = true,
        active = true,
        observedAtUptimeMillis = 55L,
        touchBeginCount = 1L
      ),
      PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
    )
    assertEquals(
      listOf(
        PhoneAutomationRootPhysicalTouchState(),
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = true,
          observedAtUptimeMillis = 55L,
          touchBeginCount = 1L
        )
      ),
      observed
    )

    PhoneAutomationServiceBridge.resetForTests()
    assertEquals(
      PhoneAutomationRootPhysicalTouchState(),
      PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
    )
    job.cancel()
  }

  @Test
  fun blackoutWakeEventsArePublished() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val observedEvents = mutableListOf<PhoneAutomationBlackoutOverlayEvent>()
    val job = backgroundScope.launch {
      PhoneAutomationServiceBridge.blackoutOverlayEvents.take(1).toList(observedEvents)
    }
    runCurrent()

    PhoneAutomationServiceBridge.recordBlackoutOverlayWakeRequested(observedAtUptimeMillis = 42L)
    runCurrent()

    assertEquals(
      listOf(
        PhoneAutomationBlackoutOverlayEvent.WakeRequested(observedAtUptimeMillis = 42L)
      ),
      observedEvents
    )
    job.cancel()
  }

  @Test
  fun nonTouchInputEventsArePublishedWithSuppressionDeadline() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val observedEvents = mutableListOf<PhoneAutomationNonTouchInputEvent>()
    val job = backgroundScope.launch {
      PhoneAutomationServiceBridge.nonTouchInputEvents.take(1).toList(observedEvents)
    }
    runCurrent()

    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "ticket:tap",
      durationMillis = 250L,
      observedAtUptimeMillis = 1_000L
    )
    runCurrent()

    assertEquals(
      listOf(
        PhoneAutomationNonTouchInputEvent(
          reason = "ticket:tap",
          observedAtUptimeMillis = 1_000L,
          suppressedUntilUptimeMillis = 1_250L
        )
      ),
      observedEvents
    )
    assertTrue(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_250L))
    assertFalse(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_251L))
    job.cancel()
  }

  @Test
  fun browserCriticalNoTailExplicitlyClearsAnOlderSuppressionDeadline() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "older_operation",
      durationMillis = 4_000L,
      observedAtUptimeMillis = 1_000L
    )
    assertTrue(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_100L))

    PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(
      observedAtUptimeMillis = 1_100L
    )

    assertFalse(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_101L))
  }

  @Test
  fun completedPhysicalTouchThenCleanupDoesNotPublishAnotherDarkeningIntent() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val events = mutableListOf<PhoneAutomationNonTouchInputEvent>()
    backgroundScope.launch { PhoneAutomationServiceBridge.nonTouchInputEvents.toList(events) }
    runCurrent()
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 900L, true)
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:action", 4_000L, 1_000L)
    runCurrent()
    assertEquals(1, events.size)
    assertEquals(0L, events.single().touchBeginCount)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(true, 1_050L, true)
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(false, 1_075L, true)
    PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(1_100L)
    runCurrent()
    assertEquals("cleanup must not undo the completed physical tap", 1, events.size)
    assertFalse(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_101L))
    assertEquals(1L, PhoneAutomationServiceBridge.currentRootPhysicalTouchState().touchBeginCount)
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:next_action", 250L, 1_200L)
    runCurrent()
    assertEquals(2, events.size)
    assertEquals(1L, events.last().touchBeginCount)
    assertEquals(0L, events.first().touchBeginCount)
  }

  @Test
  fun blackoutWakeStoresWallClockTimestamp() {
    PhoneAutomationServiceBridge.resetForTests()
    val before = System.currentTimeMillis()

    PhoneAutomationServiceBridge.recordBlackoutOverlayWakeRequested(observedAtUptimeMillis = 42L)

    val recordedAt = PhoneAutomationServiceBridge.lastBlackoutWakeAtMillis()
    val after = System.currentTimeMillis()
    assertTrue(recordedAt in before..after)
  }

  @Test
  fun blackoutVisibilityRequestSurvivesAccessibilityReconnect() = runTest {
    PhoneAutomationServiceBridge.resetForTests()

    assertTrue(PhoneAutomationServiceBridge.setBlackoutOverlayVisible(true))

    val firstHost = FakeAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(firstHost)
    assertEquals(listOf(true), firstHost.syncedVisibility)

    PhoneAutomationServiceBridge.unbindAccessibilityService(firstHost)

    val secondHost = FakeAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(secondHost)
    assertEquals(listOf(true), secondHost.syncedVisibility)
  }


  @Test
  fun blackoutSuppressionHidesOverlayAndIgnoresShowRequests() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(true)
    assertTrue(PhoneAutomationServiceBridge.isBlackoutOverlaySuppressed())
    assertTrue(PhoneAutomationServiceBridge.setBlackoutOverlayVisible(true))

    assertEquals(listOf(false), host.requestedVisibility)
    assertEquals(listOf(false), host.syncedVisibility.drop(1))

    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(false)
    assertFalse(PhoneAutomationServiceBridge.isBlackoutOverlaySuppressed())
  }

  @Test
  fun remoteScreenBrightnessStateIsSharedAndReset() {
    PhoneAutomationServiceBridge.resetForTests()
    val state = ScreenBrightnessState(
      mode = 0,
      value = 6,
      panelPath = "/sys/class/backlight/panel0-backlight",
      panelBrightness = 830,
      panelMaxBrightness = 3939
    )

    PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(state)

    assertEquals(state, PhoneAutomationServiceBridge.remoteScreenBrightnessState())

    PhoneAutomationServiceBridge.resetForTests()

    assertEquals(null, PhoneAutomationServiceBridge.remoteScreenBrightnessState())
  }

  @Test
  fun nonTouchInputSuppressionIsTimeBoundAndResettable() {
    PhoneAutomationServiceBridge.resetForTests()

    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "test",
      durationMillis = 100L,
      observedAtUptimeMillis = 1_000L
    )

    assertTrue(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_050L))
    assertFalse(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_101L))

    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "test_later",
      durationMillis = 500L,
      observedAtUptimeMillis = 2_000L
    )
    PhoneAutomationServiceBridge.resetForTests()

    assertFalse(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(2_100L))
  }

  @Test
  fun notificationBootstrapWaitsForListenerAndSnapshot() = runTest {
    PhoneAutomationServiceBridge.resetForTests()

    val waiting = backgroundScope.async {
      PhoneAutomationServiceBridge.awaitNotificationBootstrap(timeoutMillis = 1_000L)
    }
    runCurrent()

    PhoneAutomationServiceBridge.setNotificationListenerConnected(true)
    runCurrent()
    assertFalse(waiting.isCompleted)

    PhoneAutomationServiceBridge.replaceActiveNotifications(emptyList())
    runCurrent()

    assertTrue(waiting.await())
    assertTrue(PhoneAutomationServiceBridge.isNotificationBootstrapReady())
  }

  @Test
  fun notificationBootstrapResetsWhenListenerDisconnects() = runTest {
    PhoneAutomationServiceBridge.resetForTests()

    PhoneAutomationServiceBridge.setNotificationListenerConnected(true)
    PhoneAutomationServiceBridge.replaceActiveNotifications(
      listOf(
        PhoneAutomationObservedNotification(
          key = "speedtest",
          packageName = "org.zwanoo.android.speedtest",
          channelId = "SpeedtestRunningChannel",
          title = "Running",
          text = "Speedtest in progress",
          actionTitles = emptyList(),
          postedAtMillis = 10L,
          ongoing = true
        )
      )
    )

    assertTrue(PhoneAutomationServiceBridge.isNotificationBootstrapReady())
    assertTrue(
      PhoneAutomationServiceBridge.isNotificationPresent(
        PhoneAutomationProfiles
          .profile(PhoneAutomationApp.SPEEDTEST)
          .notificationMatchers
          .getValue(PhoneAutomationNotificationKind.SPEEDTEST_RUNNING)
      )
    )

    PhoneAutomationServiceBridge.setNotificationListenerConnected(false)

    assertFalse(PhoneAutomationServiceBridge.isNotificationBootstrapReady())
    assertFalse(
      PhoneAutomationServiceBridge.isNotificationPresent(
        PhoneAutomationProfiles
          .profile(PhoneAutomationApp.SPEEDTEST)
          .notificationMatchers
          .getValue(PhoneAutomationNotificationKind.SPEEDTEST_RUNNING)
      )
    )
  }

  @Test
  fun awaitNotificationPostedAfterIgnoresBootstrapNotificationsFromOlderAttempts() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.setNotificationListenerConnected(true)
    PhoneAutomationServiceBridge.replaceActiveNotifications(
      listOf(
        PhoneAutomationObservedNotification(
          key = "speedtest-old",
          packageName = "org.zwanoo.android.speedtest",
          channelId = "SpeedtestRunningChannel",
          title = "Running",
          text = "Speedtest in progress",
          actionTitles = emptyList(),
          postedAtMillis = 10L,
          ongoing = true
        )
      )
    )
    val matcher = PhoneAutomationProfiles
      .profile(PhoneAutomationApp.SPEEDTEST)
      .notificationMatchers
      .getValue(PhoneAutomationNotificationKind.SPEEDTEST_RUNNING)

    val waiting = backgroundScope.async {
      PhoneAutomationServiceBridge.awaitNotificationPostedAfter(
        matcher = matcher,
        observedAfterMillis = 100L,
        timeoutMillis = 1_000L
      )
    }
    runCurrent()
    assertFalse(waiting.isCompleted)

    PhoneAutomationServiceBridge.recordPosted(
      PhoneAutomationObservedNotification(
        key = "speedtest-new",
        packageName = "org.zwanoo.android.speedtest",
        channelId = "SpeedtestRunningChannel",
        title = "Running",
        text = "Speedtest in progress",
        actionTitles = emptyList(),
        postedAtMillis = 200L,
        ongoing = true
      )
    )
    runCurrent()

    assertEquals(200L, waiting.await()?.postedAtMillis)
  }

  @Test
  fun selectorPresenceDelegatesToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      selectorPresence = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    val present = PhoneAutomationServiceBridge.isSelectorPresent(
      expectedPackageName = "org.zwanoo.android.speedtest",
      selectors = PhoneAutomationProfiles
        .profile(PhoneAutomationApp.SPEEDTEST)
        .selectors
        .getValue(PhoneAutomationSelectorKind.SPEEDTEST_CONNECTING)
    )

    assertTrue(present)
    assertEquals(listOf("org.zwanoo.android.speedtest"), host.selectorPresencePackages)
  }

  @Test
  fun performBackDelegatesToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      backResult = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    assertTrue(PhoneAutomationServiceBridge.performBack())
    assertEquals(1, host.backCalls)
  }

  @Test
  fun setTextInFirstEditableInputDelegatesToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      firstEditableTextResult = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    assertTrue(PhoneAutomationServiceBridge.setTextInFirstEditableInput("com.pv.vivi", "12345", 750L))
    assertEquals(listOf("com.pv.vivi" to "12345"), host.firstEditableTextRequests)
  }

  @Test
  fun setViviControlCodeTextWithoutKeyboardDelegatesToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      firstEditableTextWithoutKeyboardResult = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    assertTrue(PhoneAutomationServiceBridge.setViviControlCodeTextWithoutKeyboard("com.pv.vivi", "5555", 650L))
    assertEquals(listOf("com.pv.vivi" to "5555"), host.firstEditableTextWithoutKeyboardRequests)
  }

  @Test
  fun submitViviControlCodeWithoutKeyboardDelegatesToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      controlCodeSubmitWithoutKeyboardResult = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    assertTrue(PhoneAutomationServiceBridge.submitViviControlCodeWithoutKeyboard("com.pv.vivi", "5555", 500L))
    assertEquals(listOf("com.pv.vivi" to "5555"), host.controlCodeSubmitWithoutKeyboardRequests)
  }

  @Test
  fun restoreViviControlCodeKeyboardModeDelegatesToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      restoreControlCodeKeyboardModeResult = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    assertTrue(PhoneAutomationServiceBridge.restoreViviControlCodeKeyboardMode("com.pv.vivi"))
    assertEquals(listOf("com.pv.vivi"), host.restoreControlCodeKeyboardModeRequests)
  }

  @Test
  fun controlCodeKeyboardModeAcquireAndValidationDelegateToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      suppressControlCodeKeyboardModeResult = true
      controlCodeKeyboardModeSuppressed = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    assertTrue(PhoneAutomationServiceBridge.suppressViviControlCodeKeyboardMode("com.pv.vivi"))
    assertTrue(PhoneAutomationServiceBridge.isViviControlCodeKeyboardModeSuppressed("com.pv.vivi"))
    assertEquals(listOf("com.pv.vivi"), host.suppressControlCodeKeyboardModeRequests)
    assertEquals(listOf("com.pv.vivi"), host.validateControlCodeKeyboardModeRequests)
  }

  @Test
  fun controlCodeKeyboardModeRestoreFailsClosedWithoutAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()

    assertFalse(PhoneAutomationServiceBridge.restoreViviControlCodeKeyboardMode("com.pv.vivi"))
  }

  @Test
  fun keyboardFreeTextActionSuppressesImeBeforeActivatingAndSettingText() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val method = source.substringAfter("override suspend fun setViviControlCodeTextWithoutKeyboard(")
      .substringBefore("override suspend fun submitViviControlCodeWithoutKeyboard(")
    val validator = source.substringAfter("private fun viviControlCodeEditableNode(")
      .substringBefore("private fun editableFocusedNode(")

    assertTrue(method.contains("viviControlCodeEditableNode(root, expectedPackageName)"))
    assertTrue(method.contains("AccessibilityNodeInfo.ACTION_SET_TEXT"))
    assertTrue(method.contains("suppressViviControlCodeKeyboardOnMainThread(expectedPackageName)"))
    assertTrue(method.contains("AccessibilityNodeInfo.ACTION_CLICK"))
    assertTrue(method.contains("AccessibilityNodeInfo.ACTION_FOCUS"))
    assertTrue(method.indexOf("suppressViviControlCodeKeyboardOnMainThread(expectedPackageName)") < method.indexOf("AccessibilityNodeInfo.ACTION_CLICK"))
    assertTrue(validator.contains("nodePackageMatchesExpected(node, expectedPackageName)"))
    assertTrue(validator.contains("label.contains(\"kontroles kod\")"))
    assertTrue(validator.contains("label.contains(\"control code\")"))
    assertTrue(validator.contains("submitPresent"))
    assertTrue(validator.contains("clickableEnabledNodeOrParent(node)"))
    assertTrue(validator.contains("editables.singleOrNull()"))
  }

  @Test
  fun keyboardFreeSubmitClicksOnlyTheExactEnabledViviSubmitNode() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val method = source.substringAfter("override suspend fun submitViviControlCodeWithoutKeyboard(")
      .substringBefore("override suspend fun tapScreenRatio(")
    val validator = source.substringAfter("private fun viviControlCodeSubmitNode(")
      .substringBefore("private fun nodeAccessibilityLabel(")

    assertTrue(method.contains("viviControlCodeSubmitNode("))
    assertTrue(method.contains("AccessibilityNodeInfo.ACTION_CLICK"))
    assertFalse(method.contains("AccessibilityNodeInfo.ACTION_FOCUS"))
    assertFalse(method.contains("AccessibilityNodeInfo.ACTION_SET_TEXT"))
    assertTrue(validator.contains("input.textValue().trim() != expectedText.trim()"))
    assertTrue(validator.contains("nodePackageMatchesExpected(node, expectedPackageName)"))
    assertTrue(validator.contains("node.isVisibleToUser"))
    assertTrue(validator.contains("node.isEnabled"))
    assertTrue(validator.contains("node.isClickable"))
    assertFalse(validator.contains("clickableEnabledNodeOrParent"))
    assertTrue(validator.contains("singleOrNull()"))
  }

  @Test
  fun keyboardSuppressionLeaseAlwaysAttemptsModeRestoreAndKeepsRetryStateOnFailure() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val restore = source.substringAfter("private fun restoreViviControlCodeKeyboardModeOnMainThread(")
      .substringBefore("private fun nodeAccessibilityLabel(")
    val restoredBranch = restore.substringAfter("if (markerCleared) {")
      .substringBefore("return markerCleared")
    val focusCleanup = source.substringAfter("private fun clearFocusedViviControlCodeInputOnMainThread(")
      .substringBefore("private fun recoverOwnedViviControlCodeKeyboardModeOnMainThread(")

    assertTrue(source.contains("viviControlCodeKeyboardExpectedPackageName = expectedPackageName"))
    assertTrue(restore.contains("?: viviControlCodeKeyboardExpectedPackageName"))
    assertTrue(restore.contains("clearFocusedViviControlCodeInputOnMainThread(packageToClear)"))
    assertTrue(focusCleanup.contains("AccessibilityNodeInfo.ACTION_CLEAR_FOCUS"))
    assertTrue(focusCleanup.contains("node.refresh()"))
    assertTrue(focusCleanup.contains("!node.isFocused"))
    assertTrue(focusCleanup.contains("?: return false"))
    assertFalse(restore.contains("rootForPackage(packageToClear) ?: return false"))
    assertFalse(restore.contains("if (!focusCleared)"))
    assertTrue(restore.contains("if (controller.showMode != SHOW_MODE_HIDDEN)"))
    assertTrue(restore.contains("clearOwnedViviControlCodeKeyboardModeMarker()"))
    assertTrue(restore.contains("val restored = controller.setShowMode(previousMode)"))
    assertTrue(restore.indexOf("clearFocusedViviControlCodeInputOnMainThread(packageToClear)") < restore.indexOf("controller.setShowMode(previousMode)"))
    assertTrue(restoredBranch.contains("viviControlCodePreviousKeyboardShowMode = null"))
    assertTrue(restoredBranch.contains("viviControlCodeKeyboardExpectedPackageName = null"))
  }

  @Test
  fun keyboardSuppressionBlocksNewRequestsUntilCrashRecoveryIsResolved() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val connected = source.substringAfter("override fun onServiceConnected()")
      .substringBefore("override fun onAccessibilityEvent")
    val unbind = source.substringAfter("override fun onUnbind(")
      .substringBefore("override suspend fun setBlackoutOverlayVisible")
    val suppress = source.substringAfter("private fun suppressViviControlCodeKeyboardOnMainThread(")
      .substringBefore("private fun clearOwnedViviControlCodeKeyboardModeMarker")
    val recover = source.substringAfter("private fun recoverOwnedViviControlCodeKeyboardModeOnMainThread()")
      .substringBefore("private fun restoreViviControlCodeKeyboardModeOnMainThread(")

    assertTrue(connected.indexOf("bindAccessibilityService(this)") < connected.indexOf("recoverOwnedViviControlCodeKeyboardModeOnMainThread()"))
    assertTrue(suppress.contains("!viviControlCodeKeyboardRecoveryReady"))
    assertTrue(suppress.contains("VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY"))
    assertTrue(suppress.indexOf("VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY") < suppress.indexOf(".putBoolean("))
    assertTrue(recover.contains("clearFocusedViviControlCodeInputOnMainThread(VIVI_CONTROL_CODE_PACKAGE)"))
    assertTrue(recover.indexOf("clearFocusedViviControlCodeInputOnMainThread") < recover.indexOf("controller.setShowMode(previousMode)"))
    assertTrue(recover.contains("return false"))
  }

  @Test
  fun ticketSliderFullStrokeIsOneIndependentCompletedGesture() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val fullStroke = source.substringAfter("override suspend fun performTicketSliderFullStroke(")
      .substringBefore("/** Waits for Android to finish the one full stroke")

    assertTrue(fullStroke.contains("if (ticketSliderStroke != null)"))
    assertTrue(fullStroke.contains("TicketSliderGestureDispatchResult.REJECTED"))
    assertTrue(fullStroke.contains("focusedInputWindow(expectedPackageName)"))
    assertTrue(fullStroke.contains("ticketInputFenceGenerationsAreCurrent("))
    assertTrue(fullStroke.contains("focusedWindow.windowId != expectedWindowId"))
    val focusedWindow = source.substringAfter(
      "private fun focusedInputWindow(expectedPackageName: String)"
    ).substringBefore("private fun fastRootForPackage")
    assertTrue(focusedWindow.contains("window.isFocused"))
    assertTrue(focusedWindow.contains("focused.singleOrNull()"))
    assertFalse(focusedWindow.contains("rootInActiveWindow"))
    assertEquals(1, Regex("GestureDescription\\.StrokeDescription\\(").findAll(fullStroke).count())
    assertTrue(fullStroke.contains("durationMillis.coerceIn(700L, 1_100L)"))
    assertTrue(fullStroke.contains("lineTo(end.first.toFloat(), end.second.toFloat())"))
    assertTrue(fullStroke.contains("reason = \"ticket_slider_full_stroke\""))
    assertEquals(1, Regex("dispatchTerminalTicketSliderStroke\\(").findAll(fullStroke).count())
    assertTrue(fullStroke.contains("} finally {"))
    assertTrue(fullStroke.contains("ticketSliderStroke = null"))
    assertFalse(fullStroke.contains("continueStroke("))
    assertFalse(fullStroke.contains("retry"))
    assertTrue(fullStroke.contains("TicketSliderTerminalDispatchResult.COMPLETED ->"))
    assertTrue(fullStroke.contains("TicketSliderTerminalDispatchResult.CANCELLED,"))
    assertTrue(fullStroke.contains("TicketSliderTerminalDispatchResult.TIMED_OUT ->"))
    assertTrue(fullStroke.contains("TicketSliderGestureDispatchResult.UNKNOWN"))
  }

  @Test
  fun ticketSliderBridgeBudgetAllowsExactlyOneTerminalAttempt() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationBridge.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationBridge.kt")
    )
    val bridge = source.substringAfter("object PhoneAutomationServiceBridge")
    val fullStroke = bridge.substringAfter("suspend fun performTicketSliderFullStroke(")
      .substringBefore("suspend fun performBack(")

    assertTrue(fullStroke.contains("timeoutMillis.accessibilityCallTimeoutMillis()"))
    assertTrue(fullStroke.contains("TicketSliderGestureDispatchResult.UNKNOWN"))
    assertEquals(1, Regex("service\\.performTicketSliderFullStrokeFenced\\(").findAll(fullStroke).count())
    assertTrue(fullStroke.contains("expectedInputFence?.accessibilityGeneration"))
    assertTrue(fullStroke.contains("expectedInputFence?.touchGeneration"))
    assertFalse(bridge.contains("terminalGestureCallTimeoutMillis"))
    assertFalse(fullStroke.contains("* 2L"))
    assertTrue(source.contains("private const val TICKET_SLIDER_DIAGNOSTIC_TAG = \"PixelTicketSlider\""))
    assertTrue(source.contains("Log.i(TICKET_SLIDER_DIAGNOSTIC_TAG, message.take(800))"))
  }

  @Test
  fun accessibilityServiceDeclaresGestureCapability() {
    val xml = readFirstExisting(
      Path.of("app/src/main/res/xml/phone_automation_accessibility_service.xml"),
      Path.of("src/main/res/xml/phone_automation_accessibility_service.xml")
    )

    assertTrue(xml.contains("android:canPerformGestures=\"true\""))
  }

  @Test
  fun openFirstEditableInputDelegatesToAccessibilityHost() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val host = FakeAccessibilityHost().apply {
      firstEditableOpenResult = true
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(host)

    assertTrue(PhoneAutomationServiceBridge.openFirstEditableInput("com.pv.vivi", 750L))
    assertEquals(listOf("com.pv.vivi"), host.firstEditableOpenRequests)
  }

  @Test
  fun accessibilityServiceSearchesExpectedPackageWindowWhenKeyboardOwnsActiveRoot() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )

    assertTrue(source.contains("private fun rootForPackage(expectedPackageName: String): AccessibilityNodeInfo?"))
    assertTrue(source.contains("rootInActiveWindow?.takeIf"))
    assertTrue(source.contains("windows.asSequence()"))
    assertTrue(source.contains("rootForPackage(expectedPackageName) ?: return@withContext"))
    assertTrue("Flutter/secure windows can expose the expected package only on descendant nodes", source.contains("nodePackageMatchesExpected"))
    assertTrue("Snapshot should retain semantic input nodes when accessibility marks them not-visible", source.contains("node.isEditable"))
    assertTrue("Snapshot should retain EditText nodes when accessibility marks them not-visible", source.contains("contains(\"EditText\", ignoreCase = true)"))
    assertTrue("RS Flutter code fields may be exposed as enabled EditText nodes even when visible=false", source.contains("private fun editableNodes(root: AccessibilityNodeInfo)"))
    assertTrue("Editable field lookup should prefer visible nodes but fall back to enabled semantic EditText nodes", source.contains("editableNodes(root).firstOrNull { node -> node.isVisibleToUser }\n      ?: editableNodes(root).firstOrNull()"))
  }

  private fun readFirstExisting(vararg paths: Path): String {
    val path = paths.firstOrNull { Files.exists(it) } ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }
}

private data class RecordedTicketSliderFullStroke(
  val expectedPackageName: String,
  val startX: Int,
  val startY: Int,
  val endX: Int,
  val endY: Int,
  val durationMillis: Long,
  val timeoutMillis: Long
)

private class FakeAccessibilityHost : PhoneAutomationAccessibilityHost {
  val syncedVisibility = mutableListOf<Boolean>()
  val requestedVisibility = mutableListOf<Boolean>()
  val selectorPresencePackages = mutableListOf<String>()
  var selectorPresence = false
  var visibleNodes: List<PhoneAutomationVisibleNode> = emptyList()
  val firstEditableOpenRequests = mutableListOf<String>()
  var firstEditableOpenResult = false
  val firstEditableTextRequests = mutableListOf<Pair<String, String>>()
  var firstEditableTextResult = false
  val firstEditableTextWithoutKeyboardRequests = mutableListOf<Pair<String, String>>()
  var firstEditableTextWithoutKeyboardResult = false
  val controlCodeSubmitWithoutKeyboardRequests = mutableListOf<Pair<String, String>>()
  var controlCodeSubmitWithoutKeyboardResult = false
  val suppressControlCodeKeyboardModeRequests = mutableListOf<String>()
  var suppressControlCodeKeyboardModeResult = false
  val validateControlCodeKeyboardModeRequests = mutableListOf<String>()
  var controlCodeKeyboardModeSuppressed = false
  val restoreControlCodeKeyboardModeRequests = mutableListOf<String>()
  var restoreControlCodeKeyboardModeResult = true
  var backResult = false
  var backCalls = 0
  var ticketSliderFullStrokeResult = TicketSliderGestureDispatchResult.REJECTED
  var ticketSliderFullStrokeNeverReturns = false
  var ticketSliderFullStrokeCalls = 0
  val ticketSliderFullStrokeRequests = mutableListOf<RecordedTicketSliderFullStroke>()
  var focusedInputWindow: PhoneAutomationFocusedInputWindow? = null

  override fun syncBlackoutOverlayVisibility(visible: Boolean): Boolean {
    syncedVisibility += visible
    return true
  }

  override suspend fun setBlackoutOverlayVisible(visible: Boolean): Boolean {
    requestedVisibility += visible
    return true
  }

  override suspend fun clickFirstMatching(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean = false

  override suspend fun tapFirstMatchingCenter(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean = false

  override suspend fun isAnySelectorPresent(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>
  ): Boolean {
    selectorPresencePackages += expectedPackageName
    return selectorPresence
  }

  override suspend fun snapshotVisibleNodes(expectedPackageName: String): List<PhoneAutomationVisibleNode> {
    return visibleNodes
  }

  override suspend fun snapshotFocusedInputWindow(
    expectedPackageName: String
  ): PhoneAutomationFocusedInputWindow? = focusedInputWindow

  override suspend fun setTextInFocusedInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean = false

  override suspend fun setTextInFirstEditableInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean {
    firstEditableTextRequests += expectedPackageName to text
    return firstEditableTextResult
  }

  override suspend fun setViviControlCodeTextWithoutKeyboard(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean {
    firstEditableTextWithoutKeyboardRequests += expectedPackageName to text
    return firstEditableTextWithoutKeyboardResult
  }

  override suspend fun submitViviControlCodeWithoutKeyboard(
    expectedPackageName: String,
    expectedText: String,
    timeoutMillis: Long
  ): Boolean {
    controlCodeSubmitWithoutKeyboardRequests += expectedPackageName to expectedText
    return controlCodeSubmitWithoutKeyboardResult
  }

  override suspend fun suppressViviControlCodeKeyboardMode(expectedPackageName: String): Boolean {
    suppressControlCodeKeyboardModeRequests += expectedPackageName
    return suppressControlCodeKeyboardModeResult
  }

  override suspend fun isViviControlCodeKeyboardModeSuppressed(expectedPackageName: String): Boolean {
    validateControlCodeKeyboardModeRequests += expectedPackageName
    return controlCodeKeyboardModeSuppressed
  }

  override suspend fun restoreViviControlCodeKeyboardMode(expectedPackageName: String): Boolean {
    restoreControlCodeKeyboardModeRequests += expectedPackageName
    return restoreControlCodeKeyboardModeResult
  }

  override suspend fun openFirstEditableInput(
    expectedPackageName: String,
    timeoutMillis: Long
  ): Boolean {
    firstEditableOpenRequests += expectedPackageName
    return firstEditableOpenResult
  }

  override suspend fun tapScreenRatio(
    expectedPackageName: String,
    xRatio: Double,
    yRatio: Double,
    timeoutMillis: Long
  ): Boolean = false

  override suspend fun performTicketSliderFullStroke(
    expectedPackageName: String,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMillis: Long,
    timeoutMillis: Long
  ): TicketSliderGestureDispatchResult {
    ticketSliderFullStrokeCalls += 1
    ticketSliderFullStrokeRequests += RecordedTicketSliderFullStroke(
      expectedPackageName = expectedPackageName,
      startX = startX,
      startY = startY,
      endX = endX,
      endY = endY,
      durationMillis = durationMillis,
      timeoutMillis = timeoutMillis
    )
    if (ticketSliderFullStrokeNeverReturns) awaitCancellation()
    return ticketSliderFullStrokeResult
  }

  override suspend fun performBack(): Boolean {
    backCalls += 1
    return backResult
  }
}
