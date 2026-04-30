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
}

private class FakeAccessibilityHost : PhoneAutomationAccessibilityHost {
  val syncedVisibility = mutableListOf<Boolean>()
  val requestedVisibility = mutableListOf<Boolean>()
  val selectorPresencePackages = mutableListOf<String>()
  var selectorPresence = false
  var visibleNodes: List<PhoneAutomationVisibleNode> = emptyList()

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
}
