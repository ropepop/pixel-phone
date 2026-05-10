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

  override suspend fun openFirstEditableInput(
    expectedPackageName: String,
    timeoutMillis: Long
  ): Boolean {
    firstEditableOpenRequests += expectedPackageName
    return firstEditableOpenResult
  }

  override suspend fun performBack(): Boolean {
    backCalls += 1
    return backResult
  }
}
