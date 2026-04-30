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
    assertTrue(script.contains("/sys/class/backlight/*"))
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
  fun restoreScriptPrefersLiveDisplayPercentageWhenAvailable() {
    val script = ScreenBrightnessControl.buildRestoreScript(
      ScreenBrightnessState(mode = 0, value = 6, displayPercentage = 29.9f)
    )

    assertTrue(script.contains("cmd display set-brightness 30 --unit percentage"))
    assertTrue(script.contains("settings put system screen_brightness 6"))
  }

  @Test
  fun restoreScriptWritesPanelBacklightWhenCaptured() {
    val script = ScreenBrightnessControl.buildRestoreScript(
      ScreenBrightnessState(
        mode = 0,
        value = 6,
        panelPath = "/sys/class/backlight/panel0-backlight",
        panelBrightness = 830,
        panelMaxBrightness = 3939
      )
    )

    assertTrue(script.contains("panel0-backlight"))
    assertTrue(script.contains("echo 830"))
  }

  @Test
  fun panelPercentScriptCalculatesFromPanelMaximum() {
    assertEquals(0, ScreenBrightnessControl.panelValueFromPercent(0, 3939))
    assertEquals(1970, ScreenBrightnessControl.panelValueFromPercent(50, 3939))
    assertEquals(3939, ScreenBrightnessControl.panelValueFromPercent(100, 3939))
  }

  @Test
  fun heldPanelPercentScriptRepeatsWritesToBeatForegroundBrightnessPushes() {
    val script = ScreenBrightnessControl.buildSetPanelPercentScript(
      percent = 0,
      holdMillis = 1_500,
      holdIntervalMillis = 50
    )

    assertTrue(script.contains("panel_writes=${'$'}(( (1500 + 50 - 1) / 50 ))"))
    assertTrue(script.contains("if [ \"${'$'}panel_current\" = \"${'$'}panel_target\" ]; then"))
    assertTrue(script.contains("while [ \"${'$'}panel_write_index\" -lt \"${'$'}panel_writes\" ]"))
    assertTrue(script.contains("sleep 0.05"))
  }

  @Test
  fun parseStateReadsModeSystemValueAndDisplayPercentage() {
    val state = ScreenBrightnessControl.parseState(
      """
      mode=0
      value=255
      display_percentage=100.0
      panel_path=/sys/class/backlight/panel0-backlight
      panel_brightness=3939
      panel_actual_brightness=3938
      panel_max_brightness=3939
      """.trimIndent()
    )

    assertNotNull(state)
    assertEquals(0, state?.mode)
    assertEquals(255, state?.value)
    assertEquals(100.0f, state?.displayPercentage)
    assertEquals("/sys/class/backlight/panel0-backlight", state?.panelPath)
    assertEquals(3939, state?.panelBrightness)
    assertEquals(3938, state?.panelActualBrightness)
    assertEquals(3939, state?.panelMaxBrightness)
  }
}
