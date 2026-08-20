package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneAutomationBridgeTest {
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
      reason = "ticket_slider",
      observedAtUptimeMillis = 1_100L
    )

    assertFalse(PhoneAutomationServiceBridge.isNonTouchInputSuppressed(1_101L))
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

    assertTrue(source.contains("viviControlCodeKeyboardExpectedPackageName = expectedPackageName"))
    assertTrue(restore.contains("?: viviControlCodeKeyboardExpectedPackageName"))
    assertTrue(restore.contains("AccessibilityNodeInfo.ACTION_CLEAR_FOCUS"))
    assertFalse(restore.contains("rootForPackage(packageToClear) ?: return false"))
    assertFalse(restore.contains("if (!focusCleared)"))
    assertTrue(restore.contains("val restored = softKeyboardController.setShowMode(previousMode)"))
    assertTrue(restore.indexOf("ACTION_CLEAR_FOCUS") < restore.indexOf("softKeyboardController.setShowMode(previousMode)"))
    assertTrue(restore.indexOf("if (restored)") < restore.indexOf("viviControlCodePreviousKeyboardShowMode = null"))
    assertTrue(restore.indexOf("if (restored)") < restore.indexOf("viviControlCodeKeyboardExpectedPackageName = null"))
  }

  @Test
  fun ticketSliderUsesDispatchAcceptanceForContinuingSegments() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val dispatch = source.substringAfter("private fun dispatchTicketSliderStroke(")
      .substringBefore("override suspend fun openFirstEditableInput(")

    assertTrue(dispatch.contains("dispatchGesture("))
    assertTrue(dispatch.contains("return dispatched"))
    assertTrue(source.contains("StrokeDescription(path, 0L, 120L, true)"))
    assertTrue(source.contains("lineTo(heldX.toFloat(), y.toFloat())"))
    assertTrue(source.contains("ticketSliderNextDispatchAtMillis"))
    assertTrue(source.contains("delay(waitMillis)"))
    assertTrue(source.contains("willContinue segment"))
    assertTrue(source.contains("rooted H.264/state proof remains the authoritative completion check"))
    assertFalse(dispatch.contains("suspendCancellableCoroutine"))
    assertFalse(dispatch.contains("continuation.resume(true)"))
  }

  @Test
  fun ticketSliderStartInvalidatesAnOrphanedContinuationBeforeReplacement() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val start = source.substringAfter("override suspend fun startTicketSliderGesture(")
      .substringBefore("override suspend fun continueTicketSliderGesture(")

    assertTrue(start.contains("if (ticketSliderStroke != null)"))
    assertTrue(start.contains("val staleGeneration = ticketSliderDispatchGeneration"))
    assertTrue(start.contains("ticketSliderDispatchGeneration += 1L"))
    assertTrue(start.contains("ticketSliderStroke = null"))
    assertTrue(start.contains("ticketSliderNextDispatchAtMillis = 0L"))
    assertTrue(start.contains("start_recover_stale previous_generation="))
    assertTrue(start.indexOf("ticketSliderDispatchGeneration += 1L") < start.indexOf("val generation = ++ticketSliderDispatchGeneration"))
    assertFalse(start.contains("if (ticketSliderStroke != null) return@withContext false"))
  }

  @Test
  fun ticketSliderTerminalSegmentWaitsForAndroidCompletion() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val end = source.substringAfter("override suspend fun endTicketSliderGesture(")
      .substringBefore("private suspend fun dispatchTerminalTicketSliderStroke(")
    val terminal = source.substringAfter("private suspend fun dispatchTerminalTicketSliderStroke(")
      .substringBefore("private suspend fun awaitTerminalTicketSliderStroke(")
    val callbackWait = source.substringAfter("private suspend fun awaitTerminalTicketSliderStroke(")
      .substringBefore("private fun dispatchTicketSliderStroke(")
    val awaiter = source.substringAfter("private class TicketSliderTerminalGestureAwaiter(")
      .substringBefore("private lateinit var windowManager")

    assertTrue(end.contains("dispatchTerminalTicketSliderStroke("))
    assertTrue(terminal.contains("awaitTerminalTicketSliderStroke(gesture, reason, generation, timeoutMillis)"))
    assertFalse(terminal.contains("withTimeoutOrNull"))
    assertFalse(terminal.contains("suspendCancellableCoroutine"))
    assertFalse(terminal.contains("object : GestureResultCallback()"))
    assertTrue(callbackWait.contains("suspendCancellableCoroutine"))
    assertTrue(callbackWait.contains("TicketSliderTerminalGestureAwaiter(this, reason, generation, continuation)"))
    assertTrue(callbackWait.contains("continuation.invokeOnCancellation(awaiter)"))
    assertTrue(callbackWait.contains("awaiter.scheduleTimeout(timeoutMillis)"))
    assertTrue(callbackWait.contains("dispatchGesture(gesture, awaiter, null)"))
    assertTrue(callbackWait.contains("TicketSliderTerminalDispatchResult.REJECTED"))
    assertTrue(awaiter.contains("override fun onCompleted"))
    assertTrue(awaiter.contains("TicketSliderTerminalDispatchResult.COMPLETED"))
    assertTrue(awaiter.contains("override fun onCancelled"))
    assertTrue(awaiter.contains("TicketSliderTerminalDispatchResult.CANCELLED"))
    assertTrue(awaiter.contains("override fun run()"))
    assertTrue(awaiter.contains("TicketSliderTerminalDispatchResult.TIMED_OUT"))
    assertTrue(awaiter.contains("handler.postDelayed(this, timeoutMillis.coerceAtLeast(1L))"))
    assertTrue(source.contains("terminal_dispatch reason=\$reason generation=\$generation accepted=\$dispatched"))
    assertTrue(source.contains("terminal_callback reason=\$reason generation=\$generation result="))
  }

  @Test
  fun ticketSliderTerminalSegmentRetriesWithFreshStrokeAfterContinuationFailure() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val end = source.substringAfter("override suspend fun endTicketSliderGesture(")
      .substringBefore("private suspend fun dispatchTerminalTicketSliderStroke(")

    assertTrue(end.contains("dispatchResult == TicketSliderTerminalDispatchResult.REJECTED"))
    assertTrue(end.contains("TICKET_SLIDER_TERMINAL_RETRY_DELAY_MILLIS"))
    assertTrue(end.contains("ticketSliderStroke = null"))
    assertTrue(end.contains("val retryPath = Path().apply"))
    assertTrue(end.contains("moveTo(ticketSliderStartX.toFloat(), ticketSliderStartY.toFloat())"))
    assertTrue(end.contains("reason = \"ticket_slider_end_retry\""))
    assertTrue(end.contains("generation = generation"))
    assertFalse(end.contains("dispatchResult == TicketSliderTerminalDispatchResult.CANCELLED ||"))
    assertFalse(end.contains("dispatchResult == TicketSliderTerminalDispatchResult.TIMED_OUT ||"))
    assertTrue(source.contains("TICKET_SLIDER_CONTINUATION_HANDOFF_GRACE_MILLIS"))
  }

  @Test
  fun ticketSliderFreshUnactivatedRetryIsOneIndependentCompletedStroke() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationAccessibilityService.kt")
    )
    val retry = source.substringAfter("override suspend fun retryTicketSliderFullStroke(")
      .substringBefore("/** Waits for Android to finish the terminal segment")

    assertTrue(retry.contains("if (ticketSliderStroke != null) return@withContext false"))
    assertTrue(retry.contains("GestureDescription.StrokeDescription("))
    assertTrue(retry.contains("false"))
    assertTrue(retry.contains("reason = \"ticket_slider_fresh_unactivated_retry\""))
    assertTrue(retry.contains("dispatchTerminalTicketSliderStroke("))
    assertTrue(retry.contains("result == TicketSliderTerminalDispatchResult.COMPLETED"))
  }

  @Test
  fun ticketSliderBridgeBudgetAllowsTwoBoundedTerminalAttempts() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationBridge.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhoneAutomationBridge.kt")
    )
    val bridge = source.substringAfter("object PhoneAutomationServiceBridge")
    val end = bridge.substringAfter("suspend fun endTicketSliderGesture(")
      .substringBefore("suspend fun performBack()")
    val budget = bridge.substringAfter("private fun Long.terminalGestureCallTimeoutMillis()")
      .substringBefore("private fun Int.isInInclusiveRange")

    assertTrue(end.contains("timeoutMillis.terminalGestureCallTimeoutMillis()"))
    assertTrue(budget.contains("coerceAtLeast(1L) * 2L"))
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
  val restoreControlCodeKeyboardModeRequests = mutableListOf<String>()
  var restoreControlCodeKeyboardModeResult = true
  var backResult = false
  var backCalls = 0

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

  override suspend fun performBack(): Boolean {
    backCalls += 1
    return backResult
  }
}
