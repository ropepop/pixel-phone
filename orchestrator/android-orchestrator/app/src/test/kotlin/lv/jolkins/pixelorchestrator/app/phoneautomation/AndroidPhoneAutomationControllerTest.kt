package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ContextWrapper
import android.content.Intent
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPhoneAutomationControllerTest {
  @Test
  fun startSpeedtestReturnsSuccessOnlyAfterRunningProof() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button") {
        backgroundScope.launch {
          delay(1L)
          PhoneAutomationServiceBridge.recordPosted(
            speedtestRunningNotification(postedAtMillis = testScheduler.currentTime + 10L)
          )
        }
        true
      } else {
        false
      }
    }

    val result = controller.startSpeedtest("initial_start")

    assertTrue(result.success)
    assertEquals("Started Speedtest from a fresh launch with the start button after initial_start", result.detail)
    assertEquals(listOf("org.zwanoo.android.speedtest"), context.startedPackages)
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun startSpeedtestAllowsSlowForegroundWithinExtendedPatienceWindow() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext().apply {
      onStartActivity = { launchedPackage ->
        backgroundScope.launch {
          delay(19_000L)
          PhoneAutomationServiceBridge.updateForegroundPackage(launchedPackage)
        }
      }
    }
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button") {
        backgroundScope.launch {
          delay(1L)
          PhoneAutomationServiceBridge.recordPosted(
            speedtestRunningNotification(postedAtMillis = testScheduler.currentTime + 10L)
          )
        }
        true
      } else {
        false
      }
    }

    val result = controller.startSpeedtest("initial_start")

    assertTrue(result.success)
    assertEquals(listOf("org.zwanoo.android.speedtest"), context.startedPackages)
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun startSpeedtestWaitsForLateRunningProofInsideExtendedWindow() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button") {
        backgroundScope.launch {
          delay(7_500L)
          PhoneAutomationServiceBridge.recordPosted(
            speedtestRunningNotification(postedAtMillis = testScheduler.currentTime + 10L)
          )
        }
        true
      } else {
        false
      }
    }

    val result = controller.startSpeedtest("initial_start")

    assertTrue(result.success)
    assertEquals("Started Speedtest from a fresh launch with the start button after initial_start", result.detail)
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun restartSpeedtestPrefersRetryButtonAndWaitsForRunningProof() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId ==
        "org.zwanoo.android.speedtest:id/suite_completed_feedback_assembly_test_again"
      ) {
        backgroundScope.launch {
          delay(1L)
          PhoneAutomationServiceBridge.recordPosted(
            speedtestRunningNotification(postedAtMillis = testScheduler.currentTime + 10L)
          )
        }
        true
      } else {
        false
      }
    }

    val result = controller.restartSpeedtest("resume_waiting_dispatch")

    assertTrue(result.success)
    assertEquals(PhoneAutomationRestartPath.RETRY_BUTTON, result.path)
    assertEquals(
      "Restarted Speedtest with the visible retry button after resume_waiting_dispatch",
      result.detail
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun orchestratorReturningToForegroundGetsOneRecoveryAttemptBeforeLaunchFails() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button") {
        PhoneAutomationServiceBridge.updateForegroundPackage(context.packageName)
        true
      } else {
        false
      }
    }

    val result = controller.startSpeedtest("initial_start")

    assertFalse(result.success)
    assertEquals(
      "Speedtest launch did not stick",
      result.detail
    )
    assertEquals(PhoneAutomationFailureMode.TRANSIENT, result.failureMode)
    assertEquals(PhoneAutomationFailureCategory.GENERIC, result.failureCategory)
    assertEquals(
      PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
      result.failureCleanupDisposition
    )
    assertEquals(
      PhoneAutomationRetryExhaustionDisposition.RUNTIME_ERROR,
      result.retryExhaustionDisposition
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun orchestratorForegroundStealIsRecoveredOnceDuringRunningProof() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button") {
        PhoneAutomationServiceBridge.updateForegroundPackage(context.packageName)
        backgroundScope.launch {
          delay(500L)
          PhoneAutomationServiceBridge.recordPosted(
            speedtestRunningNotification(postedAtMillis = 1_100L + testScheduler.currentTime)
          )
        }
        true
      } else {
        false
      }
    }

    val result = controller.startSpeedtest("initial_start")

    assertTrue(result.success)
    assertEquals(
      "Started Speedtest from a fresh launch with the start button after initial_start",
      result.detail
    )
    assertEquals(
      listOf("org.zwanoo.android.speedtest", "org.zwanoo.android.speedtest"),
      context.startedPackages
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun stableSpeedtestScreenWithoutMatchingSelectorsBecomesRecoverableAfterRelaunch() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val rootExecutor = ScriptedRootExecutor()
    val controller = createController(context, rootExecutor, clockMillis = { testScheduler.currentTime })

    host.onClick = { _, _ -> false }

    val result = controller.startSpeedtest("initial_start")

    assertFalse(result.success)
    assertEquals("Speedtest start button was unavailable after fresh launch", result.detail)
    assertEquals(PhoneAutomationFailureMode.TRANSIENT, result.failureMode)
    assertEquals(
      PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
      result.failureCleanupDisposition
    )
    assertTrue(rootExecutor.commands.any { it == "am force-stop org.zwanoo.android.speedtest" })
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun startSpeedtestCancellationReturnsDedicatedFailure() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button") {
        throw CancellationException("cancelled")
      }
      false
    }

    val result = controller.startSpeedtest("initial_start")

    assertFalse(result.success)
    assertEquals("Speedtest launch was cancelled before proof completed", result.detail)
    assertEquals(PhoneAutomationFailureCategory.CANCELLATION, result.failureCategory)
    assertEquals(
      PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
      result.failureCleanupDisposition
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun restartSpeedtestCancellationAfterForegroundRequestsCleanup() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId ==
        "org.zwanoo.android.speedtest:id/suite_completed_feedback_assembly_test_again"
      ) {
        throw CancellationException("cancelled")
      }
      false
    }

    val result = controller.restartSpeedtest("completion_notification")

    assertFalse(result.success)
    assertEquals(PhoneAutomationRestartPath.FAILED, result.path)
    assertEquals("Speedtest launch was cancelled before proof completed", result.detail)
    assertEquals(PhoneAutomationFailureCategory.CANCELLATION, result.failureCategory)
    assertEquals(
      PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
      result.failureCleanupDisposition
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun staleRunningNotificationDoesNotCountAsFreshStartProof() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    PhoneAutomationServiceBridge.replaceActiveNotifications(
      listOf(speedtestRunningNotification(key = "speedtest-stale", postedAtMillis = 10L))
    )
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button"
    }

    val result = controller.startSpeedtest("initial_start")

    assertFalse(result.success)
    assertEquals("Speedtest launch did not stick", result.detail)
    assertEquals(PhoneAutomationFailureMode.TRANSIENT, result.failureMode)
    assertEquals(
      PhoneAutomationFailureCleanupDisposition.CLEANUP_PARTIAL_SPEEDTEST,
      result.failureCleanupDisposition
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun connectingScreenMustClearBeforeForegroundFallbackCountsAsSuccess() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val rootExecutor = ScriptedRootExecutor()
    val controller = createController(context, rootExecutor, clockMillis = { testScheduler.currentTime })
    var connectingChecks = 0

    host.onClick = { _, selectors ->
      selectors.firstOrNull()?.resourceId ==
        "org.zwanoo.android.speedtest:id/suite_completed_feedback_assembly_test_again"
    }
    host.onSelectorPresent = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/connecting_button") {
        connectingChecks += 1
        connectingChecks <= 3
      } else {
        false
      }
    }

    val result = controller.restartSpeedtest("completion_notification")

    assertTrue(result.success)
    assertEquals(PhoneAutomationRestartPath.RETRY_BUTTON, result.path)
    assertTrue(rootExecutor.commands.none { it == "am force-stop org.zwanoo.android.speedtest" })
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun connectingScreenCanStayVisibleForLongerBeforeFallbackProofStillSucceeds() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val rootExecutor = ScriptedRootExecutor()
    val controller = createController(context, rootExecutor, clockMillis = { testScheduler.currentTime })

    host.onClick = { _, selectors ->
      selectors.firstOrNull()?.resourceId ==
        "org.zwanoo.android.speedtest:id/suite_completed_feedback_assembly_test_again"
    }
    host.onSelectorPresent = { _, selectors ->
      selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/connecting_button" &&
        testScheduler.currentTime < 22_000L
    }

    val result = controller.restartSpeedtest("completion_notification")

    assertTrue(result.success)
    assertEquals(PhoneAutomationRestartPath.RETRY_BUTTON, result.path)
    assertTrue(rootExecutor.commands.none { it == "am force-stop org.zwanoo.android.speedtest" })
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun stuckConnectingTriggersSingleRelaunchThenReturnsRuntimeFailure() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val rootExecutor = ScriptedRootExecutor()
    val controller = createController(context, rootExecutor, clockMillis = { testScheduler.currentTime })

    host.onClick = { _, selectors ->
      val resourceId = selectors.firstOrNull()?.resourceId
      resourceId == "org.zwanoo.android.speedtest:id/suite_completed_feedback_assembly_test_again" ||
        resourceId == "org.zwanoo.android.speedtest:id/go_button"
    }
    host.onSelectorPresent = { _, selectors ->
      selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/connecting_button"
    }

    val result = controller.restartSpeedtest("dead_run_timeout")

    assertFalse(result.success)
    assertEquals(PhoneAutomationRestartPath.FAILED, result.path)
    assertEquals("Speedtest stayed on Connecting...", result.detail)
    assertEquals(PhoneAutomationFailureMode.TRANSIENT, result.failureMode)
    assertEquals(PhoneAutomationFailureCategory.GENERIC, result.failureCategory)
    assertEquals(
      PhoneAutomationFailureCleanupDisposition.NONE,
      result.failureCleanupDisposition
    )
    assertEquals(
      0,
      rootExecutor.commands.count { it == "am force-stop org.zwanoo.android.speedtest" }
    )
    assertEquals(
      listOf("org.zwanoo.android.speedtest"),
      context.startedPackages
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun bringCellMapperToForegroundUsesFocusedPackageWhenAccessibilityForegroundIsStale() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext().apply {
      fallbackStartedPackage = "cellmapper.net.cellmapper"
      onStartActivity = { launchedPackage ->
        if (launchedPackage == "cellmapper.net.cellmapper") {
          PhoneAutomationServiceBridge.updateForegroundPackage(packageName)
        } else {
          PhoneAutomationServiceBridge.updateForegroundPackage(launchedPackage)
        }
      }
    }
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val rootExecutor = ScriptedRootExecutor().apply {
      stdoutByCommand["dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 1"] =
        "mCurrentFocus=Window{1 u0 cellmapper.net.cellmapper/.MainActivity}"
    }
    val controller = createController(context, rootExecutor, clockMillis = { testScheduler.currentTime })

    val result = controller.bringCellMapperToForeground("speedtest_started:dead_run_timeout")

    assertTrue(result.success)
    assertEquals(
      "Returned CellMapper to the foreground after speedtest_started:dead_run_timeout",
      result.detail
    )
    assertEquals(listOf("cellmapper.net.cellmapper"), context.startedPackages)
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun bringOrchestratorToForegroundUsesMainActivityIntent() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext().apply {
      intentPackageResolver = { packageName }
    }
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { testScheduler.currentTime })

    val result = controller.bringOrchestratorToForeground("speedtest_started:initial_start")

    assertTrue(result.success)
    assertEquals(
      "Returned the orchestrator to the foreground after speedtest_started:initial_start",
      result.detail
    )
    assertEquals(listOf(context.packageName), context.startedPackages)
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  @Test
  fun startSpeedtestUsesExtendedSelectorTimeout() = runTest {
    PhoneAutomationServiceBridge.resetForTests()
    val context = FakePhoneAutomationContext()
    val host = ScriptedAccessibilityHost()
    PhoneAutomationServiceBridge.bindAccessibilityService(host)
    val controller = createController(context, clockMillis = { 1_000L + testScheduler.currentTime })

    host.onClick = { _, selectors ->
      if (selectors.firstOrNull()?.resourceId == "org.zwanoo.android.speedtest:id/go_button") {
        backgroundScope.launch {
          delay(1L)
          PhoneAutomationServiceBridge.recordPosted(
            speedtestRunningNotification(postedAtMillis = testScheduler.currentTime + 10L)
          )
        }
        true
      } else {
        false
      }
    }

    val result = controller.startSpeedtest("initial_start")

    assertTrue(result.success)
    assertEquals(
      listOf(PhoneAutomationSpeedtestTiming.UI_SELECTOR_TIMEOUT_MILLIS),
      host.clickTimeouts
    )
    PhoneAutomationServiceBridge.unbindAccessibilityService(host)
    PhoneAutomationServiceBridge.resetForTests()
  }

  private fun createController(
    context: FakePhoneAutomationContext,
    rootExecutor: ScriptedRootExecutor = ScriptedRootExecutor(),
    clockMillis: () -> Long = { System.currentTimeMillis() }
  ): AndroidPhoneAutomationController {
    val controller = AndroidPhoneAutomationController(
      context = context,
      rootExecutor = rootExecutor,
      clockMillis = clockMillis
    )
    AndroidPhoneAutomationController::class.java.getDeclaredField("resolvedTargets").apply {
      isAccessible = true
      set(
        controller,
        PhoneAutomationResolvedTargets(
          speedtest = fakeTarget(PhoneAutomationApp.SPEEDTEST),
          cellMapper = fakeTarget(PhoneAutomationApp.CELLMAPPER)
        )
      )
    }
    return controller
  }

  private fun fakeTarget(app: PhoneAutomationApp): PhoneAutomationResolvedTarget {
    val profile = PhoneAutomationProfiles.profile(app)
    val packageName = profile.packageCandidates.first()
    return PhoneAutomationResolvedTarget(
      profile = profile,
      packageName = packageName,
      launcherLabel = profile.displayName,
      versionName = "1.0",
      launchIntent = Intent(Intent.ACTION_MAIN)
    )
  }

  private fun speedtestRunningNotification(
    key: String = "speedtest-running",
    postedAtMillis: Long = 10L
  ): PhoneAutomationObservedNotification {
    return PhoneAutomationObservedNotification(
      key = key,
      packageName = "org.zwanoo.android.speedtest",
      channelId = "SpeedtestRunningChannel",
      title = "Running",
      text = "Speedtest in progress",
      actionTitles = emptyList(),
      postedAtMillis = postedAtMillis,
      ongoing = true
    )
  }
}

private class FakePhoneAutomationContext : ContextWrapper(null) {
  val startedPackages = mutableListOf<String>()
  val startedIntents = mutableListOf<Intent>()
  var fallbackStartedPackage: String = "org.zwanoo.android.speedtest"
  var intentPackageResolver: ((Intent) -> String?)? = null
  var onStartActivity: ((String) -> Unit)? = null

  override fun getPackageName(): String = "lv.jolkins.pixelorchestrator"

  override fun startActivity(intent: Intent) {
    startedIntents += intent
    val launchedPackage = intentPackageResolver?.invoke(intent) ?: fallbackStartedPackage
    require(!launchedPackage.isNullOrBlank()) { "Intent must include a target package" }
    startedPackages += launchedPackage
    onStartActivity?.invoke(launchedPackage) ?: PhoneAutomationServiceBridge.updateForegroundPackage(launchedPackage)
  }
}

private class ScriptedAccessibilityHost : PhoneAutomationAccessibilityHost {
  var onClick: suspend (String, List<PhoneAutomationSelector>) -> Boolean = { _, _ -> false }
  var onSelectorPresent: suspend (String, List<PhoneAutomationSelector>) -> Boolean = { _, _ -> false }
  var visibleNodes: List<PhoneAutomationVisibleNode> = emptyList()
  val clickTimeouts = mutableListOf<Long>()

  override fun syncBlackoutOverlayVisibility(visible: Boolean): Boolean = true

  override suspend fun setBlackoutOverlayVisible(visible: Boolean): Boolean = true

  override suspend fun clickFirstMatching(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean {
    clickTimeouts += timeoutMillis
    return onClick(expectedPackageName, selectors)
  }

  override suspend fun isAnySelectorPresent(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>
  ): Boolean {
    return onSelectorPresent(expectedPackageName, selectors)
  }

  override suspend fun snapshotVisibleNodes(expectedPackageName: String): List<PhoneAutomationVisibleNode> {
    return visibleNodes
  }
}

private class ScriptedRootExecutor : RootExecutor {
  val commands = mutableListOf<String>()
  val stdoutByCommand = mutableMapOf<String, String>()

  override suspend fun isRootAvailable(): Boolean = true

  override suspend fun run(command: String, timeout: Duration): RootResult {
    commands += command
    return RootResult(
      exitCode = 0,
      stdout = stdoutByCommand[command].orEmpty(),
      stderr = "",
      command = command,
      durationMs = 0L
    )
  }

  override suspend fun runScript(script: String, timeout: Duration): RootResult {
    return RootResult(
      exitCode = 0,
      stdout = "",
      stderr = "",
      command = script,
      durationMs = 0L
    )
  }
}
