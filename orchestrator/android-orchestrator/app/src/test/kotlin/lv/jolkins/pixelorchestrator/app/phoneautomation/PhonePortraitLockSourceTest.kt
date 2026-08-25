package lv.jolkins.pixelorchestrator.app.phoneautomation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePortraitLockSourceTest {
  @Test
  fun portraitVerificationRequiresLiveWindowManagerLockState() {
    val source = readFirstExisting(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhonePortraitLock.kt"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PhonePortraitLock.kt")
    )
    val force = source.substringBetween("suspend fun force", "suspend fun verify")
    val verify = source.substringBetween("suspend fun verify", "\n}")

    assertTrue(force.contains("cmd window user-rotation lock 0"))
    assertTrue(force.contains("cmd window fixed-to-user-rotation enabled"))
    assertTrue(force.contains("settings put system accelerometer_rotation 0"))
    assertTrue(force.contains("settings put system user_rotation 0"))
    assertTrue(verify.contains("mCurrentRotation=ROTATION_0"))
    assertTrue(verify.contains("mUserRotationMode=USER_ROTATION_LOCKED"))
    assertTrue(verify.contains("mFixedToUserRotation=true"))
    assertTrue(verify.contains("get-ignore-orientation-request"))
    assertEquals(1, verify.windowManagerDumpCount())
    assertFalse(verify.contains("USER_ROTATION_FREE"))
    assertFalse(source.contains("USER_ROTATION_FREE"))
    assertFalse(source.contains("accelerometer_rotation 1"))
    assertFalse(source.contains("set-ignore-orientation-request false"))
  }

  @Test
  fun alreadyVerifiedPortraitSkipsMutationAndUsesOneRootScript() = runTest {
    val executor = ScriptedRootExecutor(
      ArrayDeque(listOf(rootResult(exitCode = 0, stdout = "ok\n")))
    )

    assertTrue(PhonePortraitLock.ensureVerified(executor))
    assertEquals(1, executor.scripts.size)
    assertEquals(1, executor.scripts.single().windowManagerDumpCount())
    assertFalse(executor.scripts.single().contains("settings put system"))
  }

  @Test
  fun failedInitialVerificationForcesAndRequiresSecondLiveVerification() = runTest {
    val executor = ScriptedRootExecutor(
      ArrayDeque(
        listOf(
          rootResult(exitCode = 0, stdout = ""),
          rootResult(exitCode = 1, stdout = ""),
          rootResult(exitCode = 0, stdout = "ok\n")
        )
      )
    )

    assertTrue(PhonePortraitLock.ensureVerified(executor))
    assertEquals(3, executor.scripts.size)
    assertEquals(executor.scripts.first(), executor.scripts.last())
    assertTrue(executor.scripts[1].contains("cmd window user-rotation lock 0"))
    assertTrue(executor.scripts[1].contains("settings put system accelerometer_rotation 0"))
  }

  @Test
  fun failedRepairVerificationFailsClosed() = runTest {
    val executor = ScriptedRootExecutor(
      ArrayDeque(
        listOf(
          rootResult(exitCode = 0, stdout = ""),
          rootResult(exitCode = 0, stdout = ""),
          rootResult(exitCode = 0, stdout = "")
        )
      )
    )

    assertFalse(PhonePortraitLock.ensureVerified(executor))
    assertEquals(3, executor.scripts.size)
  }

  private fun String.windowManagerDumpCount(): Int =
    windowed("dumpsys window displays".length, 1).count { it == "dumpsys window displays" }

  private class ScriptedRootExecutor(
    private val results: ArrayDeque<RootResult>
  ) : RootExecutor {
    val scripts = mutableListOf<String>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult {
      error("run should not be called")
    }

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      scripts += script
      return results.removeFirstOrNull() ?: error("No scripted result left for root script")
    }
  }

  private fun rootResult(exitCode: Int, stdout: String): RootResult = RootResult(
    exitCode = exitCode,
    stdout = stdout,
    stderr = "",
    command = "script",
    durationMs = 0L
  )

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }

  private fun readFirstExisting(vararg paths: Path): String {
    for (path in paths) {
      if (Files.exists(path)) {
        return String(Files.readAllBytes(path))
      }
    }
    error("none of the source paths exist: ${paths.joinToString()}")
  }
}
