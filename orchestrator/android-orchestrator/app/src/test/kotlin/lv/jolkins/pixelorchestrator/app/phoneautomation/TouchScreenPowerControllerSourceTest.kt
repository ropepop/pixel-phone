package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TouchScreenPowerControllerSourceTest {
  @Test
  fun heldWakeLockRefreshesOnlyNearItsDeadline() {
    assertTrue(touchScreenWakeHoldNeedsRefresh(false, 1_000L, 0L, 60_000L))
    assertFalse(touchScreenWakeHoldNeedsRefresh(true, 1_000L, 600_000L, 60_000L))
    assertFalse(touchScreenWakeHoldNeedsRefresh(true, 539_999L, 600_000L, 60_000L))
    assertTrue(touchScreenWakeHoldNeedsRefresh(true, 540_000L, 600_000L, 60_000L))
    assertTrue(touchScreenWakeHoldNeedsRefresh(true, 600_001L, 600_000L, 60_000L))
  }

  @Test
  fun touchBrightnessHoldUsesDimNonWakingWakeLockToAvoidPanelBrightnessSpikes() {
    val source = touchScreenPowerControllerSource()
    val holdBody = source.substringBetween(
      "  override fun holdScreen(reason: String) {",
      "  override fun releaseHold(reason: String)"
    )

    assertTrue(
      "touch-brightness hold should keep Android interactive with the least-bright display wake lock",
      holdBody.contains("PowerManager.SCREEN_DIM_WAKE_LOCK")
    )
    assertFalse(
      "panel-sleep hold must not request a bright screen wake lock; sysfs owns visible brightness",
      holdBody.contains("PowerManager.SCREEN_BRIGHT_WAKE_LOCK")
    )
    assertFalse(
      "panel-sleep hold refresh must not ACQUIRE_CAUSES_WAKEUP because refreshes re-trigger display brightness ramps",
      holdBody.contains("PowerManager.ACQUIRE_CAUSES_WAKEUP")
    )
    assertTrue(
      "routine non-touch events must not reacquire an already-held wake lock",
      holdBody.contains("touchScreenWakeHoldNeedsRefresh")
    )
  }

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }

  private fun touchScreenPowerControllerSource(): String = readFirstExisting(
    Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/TouchScreenPowerController.kt"),
    Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/TouchScreenPowerController.kt")
  )

  private fun readFirstExisting(vararg paths: Path): String {
    val path = paths.firstOrNull { Files.exists(it) } ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }
}
