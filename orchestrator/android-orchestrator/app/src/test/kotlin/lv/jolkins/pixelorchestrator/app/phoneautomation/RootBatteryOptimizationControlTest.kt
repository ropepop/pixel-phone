package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootBatteryOptimizationControlTest {
  @Test
  fun recognizesPackageInDeviceIdleWhitelistOutput() {
    val output = """
      system,com.android.providers.downloads,10012
      user,lv.jolkins.pixelorchestrator,10203
      user,com.termux,10182
    """.trimIndent()

    assertTrue(
      RootBatteryOptimizationControl.isWhitelisted(
        whitelistOutput = output,
        packageName = "lv.jolkins.pixelorchestrator"
      )
    )
  }

  @Test
  fun doesNotMatchPackageByPrefix() {
    val output = "user,lv.jolkins.pixelorchestrator.debug,10203"

    assertFalse(
      RootBatteryOptimizationControl.isWhitelisted(
        whitelistOutput = output,
        packageName = "lv.jolkins.pixelorchestrator"
      )
    )
  }

  @Test
  fun treatsVerifiedRootWhitelistAsUnrestrictedWhenAndroidPublicStatusIsRestricted() {
    assertTrue(
      RootBatteryOptimizationControl.batteryUnrestricted(
        androidBatteryUnrestricted = false,
        rootWhitelistConfirmed = true
      )
    )
  }

  @Test
  fun confirmsWhitelistAfterRootAddCommand() = runTest {
    val rootExecutor = ScriptedRootExecutor(
      responses = ArrayDeque(
        listOf(
          okResult(command = "cmd deviceidle whitelist '+lv.jolkins.pixelorchestrator'"),
          okResult(
            command = "cmd deviceidle whitelist",
            stdout = "user,lv.jolkins.pixelorchestrator,10203"
          )
        )
      )
    )

    val result = RootBatteryOptimizationControl.ensureWhitelisted(
      packageName = "lv.jolkins.pixelorchestrator",
      rootExecutor = rootExecutor
    )

    assertTrue(result.attempted)
    assertTrue(result.confirmed)
    assertEquals("Root battery whitelist active", result.detail)
    assertEquals(
      listOf(
        "cmd deviceidle whitelist '+lv.jolkins.pixelorchestrator'",
        "cmd deviceidle whitelist"
      ),
      rootExecutor.commands
    )
  }

  @Test
  fun reportsFailedAddCommand() = runTest {
    val rootExecutor = ScriptedRootExecutor(
      responses = ArrayDeque(
        listOf(
          failedResult(
            command = "cmd deviceidle whitelist '+lv.jolkins.pixelorchestrator'",
            stderr = "permission denied"
          ),
          okResult(command = "cmd deviceidle whitelist", stdout = "")
        )
      )
    )

    val result = RootBatteryOptimizationControl.ensureWhitelisted(
      packageName = "lv.jolkins.pixelorchestrator",
      rootExecutor = rootExecutor
    )

    assertTrue(result.attempted)
    assertFalse(result.confirmed)
    assertEquals("permission denied", result.detail)
  }

  @Test
  fun reportsMissingReadbackAfterSuccessfulCommand() = runTest {
    val rootExecutor = ScriptedRootExecutor(
      responses = ArrayDeque(
        listOf(
          okResult(command = "cmd deviceidle whitelist '+lv.jolkins.pixelorchestrator'"),
          okResult(command = "cmd deviceidle whitelist", stdout = "user,com.termux,10182")
        )
      )
    )

    val result = RootBatteryOptimizationControl.ensureWhitelisted(
      packageName = "lv.jolkins.pixelorchestrator",
      rootExecutor = rootExecutor
    )

    assertTrue(result.attempted)
    assertFalse(result.confirmed)
    assertEquals(
      "Battery whitelist verification did not include lv.jolkins.pixelorchestrator",
      result.detail
    )
  }

  private class ScriptedRootExecutor(
    private val responses: ArrayDeque<RootResult>
  ) : RootExecutor {
    val commands = mutableListOf<String>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult {
      commands += command
      return responses.removeFirstOrNull()
        ?: error("No scripted root result left for: $command")
    }

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      error("runScript should not be called")
    }
  }

  companion object {
    private fun okResult(command: String, stdout: String = ""): RootResult {
      return RootResult(
        exitCode = 0,
        stdout = stdout,
        stderr = "",
        command = command,
        durationMs = 0L
      )
    }

    private fun failedResult(command: String, stderr: String): RootResult {
      return RootResult(
        exitCode = 1,
        stdout = "",
        stderr = stderr,
        command = command,
        durationMs = 0L
      )
    }
  }
}
