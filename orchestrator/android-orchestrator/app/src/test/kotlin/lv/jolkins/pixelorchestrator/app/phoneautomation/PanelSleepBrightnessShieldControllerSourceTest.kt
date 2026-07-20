package lv.jolkins.pixelorchestrator.app.phoneautomation

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSleepBrightnessShieldControllerSourceTest {
  @Test
  fun shieldIsPixelNeutralNonInteractiveAndRootSelfHealed() {
    val source = controllerSource()

    assertTrue(source.contains("SHIELD_SIZE_PX = 1"))
    assertTrue(source.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"))
    assertTrue(source.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"))
    assertTrue(source.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"))
    assertTrue(source.contains("PixelFormat.TRANSLUCENT"))
    assertTrue(source.contains("alpha = 0f"))
    assertTrue(source.contains("screenBrightness = 0f"))
    assertTrue(source.contains("appops set \$packageName SYSTEM_ALERT_WINDOW allow"))
    assertTrue(source.contains("Settings.canDrawOverlays(appContext)"))
    assertFalse(source.contains("TYPE_ACCESSIBILITY_OVERLAY"))
  }

  @Test
  fun failedRemovalKeepsShieldTrackedForRetry() {
    val source = controllerSource()
    val hideBody = source.substringBetween(
      "override suspend fun hide(): PhoneAutomationActionResult",
      "override fun isVisible(): Boolean"
    )
    val failedRemovalBody = hideBody.substringAfter("}.getOrElse { error ->")

    assertTrue(hideBody.contains("windowManager.removeViewImmediate(view)"))
    assertFalse(failedRemovalBody.contains("sharedShieldView = null"))
  }

  @Test
  fun recreatedControllerAdoptsProcessSharedShieldView() {
    val source = controllerSource()

    assertTrue(source.contains("@Volatile\n    private var sharedShieldView: View? = null"))
    assertTrue(source.contains("if (sharedShieldView != null)"))
    assertTrue(source.contains("val view = sharedShieldView"))
    assertTrue(source.contains("override fun isVisible(): Boolean = sharedShieldView != null"))
    assertFalse(source.contains("private var shieldView: View?"))
  }

  private fun controllerSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PanelSleepBrightnessShieldController.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/PanelSleepBrightnessShieldController.kt")
  )

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }

  private fun readFirstExisting(vararg paths: Path): String {
    val path = paths.firstOrNull { Files.exists(it) } ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }
}
