package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenBrightnessControlTest {
  @Test
  fun setPercentScriptUsesLiveDisplayBrightnessWithLegacyFallback() {
    val script = ScreenBrightnessControl.buildSetPercentScript(100)

    assertTrue(script.contains("settings put system screen_brightness_mode 0"))
    assertTrue(script.contains("cmd display set-brightness 100 --unit percentage"))
    assertTrue(script.contains("settings put system screen_brightness 255"))
  }

  @Test
  fun restoreScriptReturnsAutomaticModeByBestEffort() {
    val script = ScreenBrightnessControl.buildRestoreScript(
      ScreenBrightnessState(mode = 1, value = 127)
    )

    assertTrue(script.contains("settings put system screen_brightness 127"))
    assertTrue(script.contains("settings put system screen_brightness_mode 1"))
  }

  @Test
  fun parseStateReadsModeSystemValueAndDisplayPercentage() {
    val state = ScreenBrightnessControl.parseState(
      """
      mode=0
      value=255
      display_percentage=100.0
      """.trimIndent()
    )

    assertNotNull(state)
    assertEquals(0, state?.mode)
    assertEquals(255, state?.value)
    assertEquals(100.0f, state?.displayPercentage)
  }
}
