package lv.jolkins.pixelorchestrator.app.phoneautomation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchBrightnessInputReadinessSourceTest {
  @Test
  fun startupReadinessUsesPerNodeCapabilitiesForTouchAndPower() {
    val source = runtimeSource()
    val inputReadiness = source.substringBetween(
      "  private suspend fun readTouchDevices()",
      "  private suspend fun readPanelAvailable()"
    )

    assertTrue(
      inputReadiness.countOccurrences("RootInputDeviceCapabilities.PER_NODE_DISCOVERY_COMMAND") == 2
    )
    assertTrue(inputReadiness.countOccurrences("rootExecutor.runScript(") == 2)
    assertFalse(inputReadiness.contains("rootExecutor.run(\"getevent -lp\""))
  }

  private fun runtimeSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/TouchBrightnessRuntime.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/TouchBrightnessRuntime.kt")
  )

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }

  private fun String.countOccurrences(needle: String): Int =
    windowed(needle.length).count { it == needle }

  private fun readFirstExisting(vararg paths: Path): String {
    val path = paths.firstOrNull { Files.exists(it) }
      ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }
}
