package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ContextWrapper
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult

class AndroidTouchBrightnessDeviceControllerTest {
  @Test
  fun restoreBrightnessSucceedsWhenTheSystemValueMatchesEvenIfDisplayPercentageUsesOemScaling() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=3
              display_percentage=18.776789
            """.trimIndent()
          )
        )
      )
    )
    val controller = AndroidTouchBrightnessDeviceController(
      context = ContextWrapper(null),
      rootExecutor = rootExecutor
    )

    val result = controller.restoreBrightnessState(
      ScreenBrightnessState(mode = 0, value = 3)
    )

    assertTrue(result.success)
  }

  @Test
  fun setBrightnessSucceedsWhenTheSystemValueMatchesEvenIfDisplayPercentageLooksDifferent() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=255
              display_percentage=67.0
            """.trimIndent()
          )
        )
      )
    )
    val controller = AndroidTouchBrightnessDeviceController(
      context = ContextWrapper(null),
      rootExecutor = rootExecutor
    )

    val result = controller.setBrightnessPercent(100)

    assertTrue(result.success)
  }

  @Test
  fun accessibilityPermissionRepairAddsTheServiceAndTurnsAccessibilityOn() = runTest {
    val environment = FakeAccessibilityRecoveryEnvironment(
      permissionGranted = false,
      connected = false,
      accessibilityGloballyEnabled = false,
      enabledServices = "com.example/.ReaderService"
    )

    val result = PhoneAutomationAccessibilityRecovery(environment).repairPermissionIfNeeded()

    assertTrue(result.recovered)
    assertEquals(PhoneAutomationAccessibilityRecoveryStage.PERMISSION_REPAIRED, result.stage)
    assertTrue(environment.accessibilityGloballyEnabled)
    assertEquals(
      "com.example/.ReaderService:lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService",
      environment.enabledServices
    )
  }

  @Test
  fun disconnectedAccessibilityServiceUsesStagedRebindBeforeGivingUp() = runTest {
    val environment = FakeAccessibilityRecoveryEnvironment(
      permissionGranted = true,
      connected = false,
      accessibilityGloballyEnabled = true,
      enabledServices = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService",
      connectionResponses = ArrayDeque(listOf(false, true))
    )

    val result = PhoneAutomationAccessibilityRecovery(environment).recoverDisconnectedService(timeoutMillis = 50L)

    assertTrue(result.recovered)
    assertEquals(PhoneAutomationAccessibilityRecoveryStage.SERVICE_REBOUND, result.stage)
    assertEquals(
      listOf(
        "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService",
        "",
        "lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService"
      ),
      environment.writtenEnabledServices
    )
  }

  @Test
  fun disconnectedAccessibilityServiceFailureLeavesRecoveryInFailedState() = runTest {
    val environment = FakeAccessibilityRecoveryEnvironment(
      permissionGranted = true,
      connected = false,
      accessibilityGloballyEnabled = true,
      enabledServices = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService",
      connectionResponses = ArrayDeque(listOf(false, false))
    )

    val result = PhoneAutomationAccessibilityRecovery(environment).recoverDisconnectedService(timeoutMillis = 50L)

    assertEquals(PhoneAutomationAccessibilityRecoveryStage.FAILED, result.stage)
    assertEquals(false, result.recovered)
  }

  private class QueuedRootExecutor(
    private val scriptResults: ArrayDeque<RootResult>
  ) : RootExecutor {
    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult {
      return okResult(stdout = "")
    }

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      return scriptResults.removeFirstOrNull()
        ?: error("No scripted root result left for: $script")
    }
  }

  companion object {
    private fun okResult(stdout: String): RootResult {
      return RootResult(
        exitCode = 0,
        stdout = stdout,
        stderr = "",
        command = "",
        durationMs = 0L
      )
    }
  }

  private class FakeAccessibilityRecoveryEnvironment(
    permissionGranted: Boolean,
    connected: Boolean,
    var accessibilityGloballyEnabled: Boolean,
    enabledServices: String,
    val connectionResponses: ArrayDeque<Boolean> = ArrayDeque()
  ) : PhoneAutomationAccessibilityRecoveryEnvironment {
    override val componentName: PhoneAutomationServiceComponent = PhoneAutomationServiceComponent(
      fullName = "lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService",
      shortName = "lv.jolkins.pixelorchestrator/.PhoneAutomationAccessibilityService"
    )

    var permissionGranted = permissionGranted
    var connected = connected
    var enabledServices = enabledServices
    val writtenEnabledServices = mutableListOf<String>()

    override fun isPermissionGranted(): Boolean = permissionGranted

    override fun isConnected(): Boolean = connected

    override suspend fun awaitConnection(timeoutMillis: Long): Boolean {
      val next = connectionResponses.removeFirstOrNull() ?: connected
      connected = next
      return next
    }

    override suspend fun ensureWriteSecureSettingsPermission(): Boolean = true

    override fun isAccessibilityGloballyEnabled(): Boolean = accessibilityGloballyEnabled

    override fun readEnabledAccessibilityServices(): String = enabledServices

    override fun writeEnabledAccessibilityServices(value: String): Boolean {
      enabledServices = value
      writtenEnabledServices += value
      permissionGranted = accessibilityGloballyEnabled &&
        PhoneAutomationServicePermissions.containsEnabledService(
          currentValue = value,
          componentName = componentName
        )
      return true
    }

    override fun setAccessibilityGloballyEnabled(enabled: Boolean): Boolean {
      accessibilityGloballyEnabled = enabled
      permissionGranted = enabled &&
        PhoneAutomationServicePermissions.containsEnabledService(
          currentValue = enabledServices,
          componentName = componentName
        )
      return true
    }
  }
}
