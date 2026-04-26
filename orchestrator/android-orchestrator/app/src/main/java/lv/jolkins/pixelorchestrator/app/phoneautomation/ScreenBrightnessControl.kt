package lv.jolkins.pixelorchestrator.app.phoneautomation

internal data class ScreenBrightnessState(
  val mode: Int?,
  val value: Int?,
  val displayPercentage: Float? = null
)

internal object ScreenBrightnessControl {
  private const val MANUAL_BRIGHTNESS_MODE = 0
  private const val AUTOMATIC_BRIGHTNESS_MODE = 1

  fun legacySystemValue(percent: Int): Int {
    return ((percent.coerceIn(0, 100) / 100.0) * 255.0).toInt().coerceIn(0, 255)
  }

  fun percentFromSystemValue(value: Int): Int {
    return ((value.coerceIn(0, 255) / 255.0) * 100.0).toInt().coerceIn(0, 100)
  }

  fun buildSetPercentScript(percent: Int): String {
    val targetPercent = percent.coerceIn(0, 100)
    val targetSystemValue = legacySystemValue(targetPercent)
    return """
      settings put system screen_brightness_mode $MANUAL_BRIGHTNESS_MODE
      if ! cmd display set-brightness $targetPercent --unit percentage >/dev/null 2>&1; then
        settings put system screen_brightness $targetSystemValue
      fi
    """.trimIndent()
  }

  fun buildRestoreScript(state: ScreenBrightnessState): String {
    val restoreMode = state.mode ?: MANUAL_BRIGHTNESS_MODE
    val restoreValue = (state.value ?: 0).coerceIn(0, 255)
    val restorePercent = percentFromSystemValue(restoreValue)
    return if (restoreMode == AUTOMATIC_BRIGHTNESS_MODE) {
      """
        settings put system screen_brightness $restoreValue
        settings put system screen_brightness_mode $AUTOMATIC_BRIGHTNESS_MODE
      """.trimIndent()
    } else {
      """
        settings put system screen_brightness_mode $MANUAL_BRIGHTNESS_MODE
        if ! cmd display set-brightness $restorePercent --unit percentage >/dev/null 2>&1; then
          settings put system screen_brightness $restoreValue
        fi
        settings put system screen_brightness $restoreValue
      """.trimIndent()
    }
  }

  fun buildReadStateScript(): String {
    return """
      mode=$(settings get system screen_brightness_mode 2>/dev/null || true)
      value=$(settings get system screen_brightness 2>/dev/null || true)
      display=$(cmd display get-brightness --unit percentage 2>/dev/null || true)
      printf 'mode=%s\n' "${'$'}mode"
      printf 'value=%s\n' "${'$'}value"
      printf 'display_percentage=%s\n' "${'$'}display"
    """.trimIndent()
  }

  fun parseState(output: String): ScreenBrightnessState? {
    if (output.isBlank()) {
      return null
    }
    val values = output.lineSequence()
      .mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) {
          null
        } else {
          line.substring(0, separator) to line.substring(separator + 1)
        }
      }
      .toMap()
    val mode = values["mode"].parseIntValue()
    val systemValue = values["value"].parseIntValue()
    val displayPercentage = values["display_percentage"].parseFloatValue()
    if (mode == null && systemValue == null && displayPercentage == null) {
      return null
    }
    return ScreenBrightnessState(
      mode = mode,
      value = systemValue,
      displayPercentage = displayPercentage
    )
  }

  private fun String?.parseIntValue(): Int? {
    return this
      ?.trim()
      ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
      ?.toIntOrNull()
  }

  private fun String?.parseFloatValue(): Float? {
    return this
      ?.trim()
      ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
      ?.toFloatOrNull()
  }
}
