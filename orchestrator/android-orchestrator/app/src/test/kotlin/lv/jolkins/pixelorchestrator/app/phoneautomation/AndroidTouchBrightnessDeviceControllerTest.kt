package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.ContextWrapper
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
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
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=3939
              panel_actual_brightness=3939
              panel_max_brightness=3939
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
  fun setVisibleBrightnessWritesDisplayAndPanelBrightness() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          ),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=51
              display_percentage=20.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=788
              panel_actual_brightness=788
              panel_max_brightness=3939
            """.trimIndent()
          )
        )
      )
    )
    val controller = AndroidTouchBrightnessDeviceController(
      context = ContextWrapper(null),
      rootExecutor = rootExecutor
    )

    val result = controller.setBrightnessPercent(20)

    assertTrue(result.success)
    assertTrue(rootExecutor.scripts[1].contains("cmd display set-brightness 20 --unit percentage"))
    assertTrue(rootExecutor.scripts[1].contains("settings put system screen_brightness 51"))
    assertTrue(rootExecutor.scripts[1].contains("panel_target"))
  }

  @Test
  fun setBrightnessPercentAllowsRealPanelZero() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(stdout = ""),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=127
              display_percentage=50.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          )
        )
      )
    )
    val controller = AndroidTouchBrightnessDeviceController(
      context = ContextWrapper(null),
      rootExecutor = rootExecutor
    )

    val result = controller.setBrightnessPercent(0)

    assertTrue(result.success)
    assertTrue(rootExecutor.scripts.any { it.contains("panel_max * 0 + 50") })
  }

  @Test
  fun setBrightnessPercentDoesNotTreatPanelOneAsPanelZero() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(
            stdout = """
              mode=0
              value=1
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=1
              panel_actual_brightness=1
              panel_max_brightness=3939
            """.trimIndent()
          ),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          )
        )
      )
    )
    val controller = AndroidTouchBrightnessDeviceController(
      context = ContextWrapper(null),
      rootExecutor = rootExecutor
    )

    val result = controller.setBrightnessPercent(0)

    assertTrue(result.success)
    assertEquals(3, rootExecutor.scripts.size)
    assertTrue(rootExecutor.scripts[1].contains("screen_brightness 0"))
  }

  @Test
  fun setBrightnessPercentZeroStillRunsPanelHoldWhenPanelAlreadyReadsZero() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          ),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          )
        )
      )
    )
    val controller = AndroidTouchBrightnessDeviceController(
      context = ContextWrapper(null),
      rootExecutor = rootExecutor
    )

    val result = controller.setBrightnessPercent(0)

    assertTrue(result.success)
    assertEquals(3, rootExecutor.scripts.size)
    assertTrue(rootExecutor.scripts[1].contains("screen_brightness 0"))
    assertTrue(rootExecutor.scripts[1].contains("panel_writes=$(( (1500 + 50 - 1) / 50 ))"))
    assertFalse(rootExecutor.scripts[1].contains("if [ \"${'$'}panel_current\" = \"${'$'}panel_target\" ]"))
  }

  @Test
  fun restoreWithoutCapturedPanelDataDoesNotTreatPanelZeroAsAlreadyRestored() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(
            stdout = """
              mode=0
              value=127
              display_percentage=50.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          ),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=127
              display_percentage=50.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=1969
              panel_actual_brightness=1969
              panel_max_brightness=3939
            """.trimIndent()
          )
        )
      )
    )
    val controller = AndroidTouchBrightnessDeviceController(
      context = ContextWrapper(null),
      rootExecutor = rootExecutor
    )

    val result = controller.restoreBrightnessState(ScreenBrightnessState(mode = 0, value = 127))

    assertTrue(result.success)
    assertTrue(rootExecutor.scripts.any { it.contains("settings put system screen_brightness") })
    assertTrue(rootExecutor.scripts.any { it.contains("panel_target") })
  }

  @Test
  fun restoreWithCapturedPanelDataRestoresDisplayAndPanelBrightness() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          ),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=127
              display_percentage=50.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=1969
              panel_actual_brightness=1969
              panel_max_brightness=3939
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
      ScreenBrightnessState(
        mode = 0,
        value = 127,
        displayPercentage = 50.0f,
        panelPath = "/sys/class/backlight/panel0-backlight",
        panelBrightness = 1969,
        panelActualBrightness = 1969,
        panelMaxBrightness = 3939
      )
    )

    assertTrue(result.success)
    assertTrue(rootExecutor.scripts[1].contains("cmd display set-brightness 50 --unit percentage"))
    assertTrue(rootExecutor.scripts[1].contains("settings put system screen_brightness 127"))
    assertTrue(rootExecutor.scripts[1].contains("echo 1969 >"))
  }

  @Test
  fun restoreFailsWhenDisplayBrightnessStaysZeroEvenIfPanelMatches() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          ),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=1969
              panel_actual_brightness=1969
              panel_max_brightness=3939
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
      ScreenBrightnessState(
        mode = 0,
        value = 127,
        displayPercentage = 50.0f,
        panelPath = "/sys/class/backlight/panel0-backlight",
        panelBrightness = 1969,
        panelActualBrightness = 1969,
        panelMaxBrightness = 3939
      )
    )

    assertFalse(result.success)
  }

  @Test
  fun restoreFailsWhenTinySavedValueLeavesDisplayBrightnessAtZero() = runTest {
    val rootExecutor = QueuedRootExecutor(
      scriptResults = ArrayDeque(
        listOf(
          okResult(
            stdout = """
              mode=0
              value=0
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=0
              panel_actual_brightness=0
              panel_max_brightness=3939
            """.trimIndent()
          ),
          okResult(stdout = ""),
          okResult(
            stdout = """
              mode=0
              value=1
              display_percentage=0.0
              panel_path=/sys/class/backlight/panel0-backlight
              panel_brightness=788
              panel_actual_brightness=788
              panel_max_brightness=3939
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
      ScreenBrightnessState(mode = 0, value = 1)
    )

    assertFalse(result.success)
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
    assertEquals(2, environment.restrictedSettingsAllowCalls)
    assertEquals(
      "com.example/.ReaderService:lv.jolkins.pixelorchestrator/lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityService",
      environment.enabledServices
    )
  }

  @Test
  fun accessibilityPermissionRepairFailsWhenRestrictedSettingsCannotBeAllowed() = runTest {
    val environment = FakeAccessibilityRecoveryEnvironment(
      permissionGranted = false,
      connected = false,
      accessibilityGloballyEnabled = false,
      enabledServices = "",
      restrictedSettingsAllowed = false
    )

    val result = PhoneAutomationAccessibilityRecovery(environment).repairPermissionIfNeeded()

    assertEquals(PhoneAutomationAccessibilityRecoveryStage.FAILED, result.stage)
    assertEquals(false, result.recovered)
    assertEquals("", environment.enabledServices)
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
    val scripts = mutableListOf<String>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult {
      return okResult(stdout = "")
    }

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      scripts += script
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
    var restrictedSettingsAllowed = true
    var restrictedSettingsAllowCalls = 0

    constructor(
      permissionGranted: Boolean,
      connected: Boolean,
      accessibilityGloballyEnabled: Boolean,
      enabledServices: String,
      restrictedSettingsAllowed: Boolean
    ) : this(
      permissionGranted = permissionGranted,
      connected = connected,
      accessibilityGloballyEnabled = accessibilityGloballyEnabled,
      enabledServices = enabledServices
    ) {
      this.restrictedSettingsAllowed = restrictedSettingsAllowed
    }

    override fun isPermissionGranted(): Boolean = permissionGranted

    override fun isConnected(): Boolean = connected

    override suspend fun awaitConnection(timeoutMillis: Long): Boolean {
      val next = connectionResponses.removeFirstOrNull() ?: connected
      connected = next
      return next
    }

    override suspend fun ensureWriteSecureSettingsPermission(): Boolean = true

    override suspend fun allowRestrictedSettings(): Boolean {
      restrictedSettingsAllowCalls += 1
      return restrictedSettingsAllowed
    }

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
