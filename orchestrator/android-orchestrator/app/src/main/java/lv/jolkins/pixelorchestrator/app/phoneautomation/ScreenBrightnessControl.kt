package lv.jolkins.pixelorchestrator.app.phoneautomation

import kotlin.math.roundToInt

internal data class ScreenBrightnessState(
  val mode: Int?,
  val value: Int?,
  val displayPercentage: Float? = null,
  val panelPath: String? = null,
  val panelBrightness: Int? = null,
  val panelActualBrightness: Int? = null,
  val panelMaxBrightness: Int? = null
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

  fun panelValueFromPercent(percent: Int, maxBrightness: Int): Int {
    val targetPercent = percent.coerceIn(0, 100)
    val max = maxBrightness.coerceAtLeast(1)
    val target = ((max * targetPercent) / 100.0).roundToInt().coerceIn(0, max)
    return if (targetPercent > 0 && target == 0) {
      1
    } else {
      target
    }
  }

  fun buildSetPercentScript(percent: Int): String {
    val targetPercent = percent.coerceIn(0, 100)
    val targetSystemValue = legacySystemValue(targetPercent)
    return """
      settings put system screen_brightness_mode $MANUAL_BRIGHTNESS_MODE
      if ! cmd display set-brightness $targetPercent --unit percentage >/dev/null 2>&1; then
        settings put system screen_brightness $targetSystemValue
      fi
      ${buildSetPanelPercentScript(targetPercent)}
    """.trimIndent()
  }

  fun buildSetPanelPercentScript(
    percent: Int,
    holdMillis: Long = 0L,
    holdIntervalMillis: Long = 50L
  ): String {
    val targetPercent = percent.coerceIn(0, 100)
    val holdMs = holdMillis.coerceAtLeast(0L)
    val intervalMs = holdIntervalMillis.coerceAtLeast(1L)
    val sleepSeconds = intervalMs / 1000.0
    return """
      ${panelDiscoveryScript()}
      if [ -n "${'$'}panel_dir" ] && [ -w "${'$'}panel_dir/brightness" ] && [ -r "${'$'}panel_dir/max_brightness" ]; then
        panel_max="$(cat "${'$'}panel_dir/max_brightness" 2>/dev/null || true)"
        case "${'$'}panel_max" in
          ''|*[!0-9]*) ;;
          *)
            panel_target="${'$'}(( (panel_max * $targetPercent + 50) / 100 ))"
            if [ "$targetPercent" -gt 0 ] && [ "${'$'}panel_target" -lt 1 ]; then
              panel_target=1
            fi
            panel_current="$(cat "${'$'}panel_dir/brightness" 2>/dev/null || true)"
            panel_writes=1
            if [ $holdMs -gt 0 ]; then
              if [ "${'$'}panel_current" = "${'$'}panel_target" ]; then
                panel_writes=1
              else
                panel_writes=$(( ($holdMs + $intervalMs - 1) / $intervalMs ))
                if [ "${'$'}panel_writes" -lt 1 ]; then
                  panel_writes=1
                fi
              fi
            fi
            panel_write_index=0
            while [ "${'$'}panel_write_index" -lt "${'$'}panel_writes" ]; do
              echo "${'$'}panel_target" > "${'$'}panel_dir/brightness"
              panel_write_index="${'$'}((panel_write_index + 1))"
              if [ "${'$'}panel_write_index" -lt "${'$'}panel_writes" ]; then
                sleep $sleepSeconds
              fi
            done
            ;;
        esac
      fi
    """.trimIndent()
  }

  fun buildRestoreScript(state: ScreenBrightnessState): String {
    val restoreMode = state.mode ?: MANUAL_BRIGHTNESS_MODE
    val restoreValue = (state.value ?: 0).coerceIn(0, 255)
    val restorePercent = state.displayPercentage
      ?.roundToInt()
      ?.coerceIn(0, 100)
      ?: percentFromSystemValue(restoreValue)
    val systemRestore = if (restoreMode == AUTOMATIC_BRIGHTNESS_MODE) {
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
    return """
      $systemRestore
      ${buildRestorePanelScript(state)}
    """.trimIndent()
  }

  fun buildRestorePanelScript(state: ScreenBrightnessState): String {
    val panelBrightness = state.panelBrightness ?: state.panelActualBrightness
    return if (state.panelPath.isNullOrBlank() || panelBrightness == null) {
      ""
    } else {
      """
        panel_dir=${state.panelPath.shellQuote()}
        if [ -w "${'$'}panel_dir/brightness" ]; then
          echo ${panelBrightness.coerceAtLeast(0)} > "${'$'}panel_dir/brightness"
        fi
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
      ${panelDiscoveryScript()}
      if [ -n "${'$'}panel_dir" ]; then
        panel_brightness=$(cat "${'$'}panel_dir/brightness" 2>/dev/null || true)
        panel_actual=$(cat "${'$'}panel_dir/actual_brightness" 2>/dev/null || true)
        panel_max=$(cat "${'$'}panel_dir/max_brightness" 2>/dev/null || true)
        printf 'panel_path=%s\n' "${'$'}panel_dir"
        printf 'panel_brightness=%s\n' "${'$'}panel_brightness"
        printf 'panel_actual_brightness=%s\n' "${'$'}panel_actual"
        printf 'panel_max_brightness=%s\n' "${'$'}panel_max"
      fi
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
    val panelPath = values["panel_path"]?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    val panelBrightness = values["panel_brightness"].parseIntValue()
    val panelActualBrightness = values["panel_actual_brightness"].parseIntValue()
    val panelMaxBrightness = values["panel_max_brightness"].parseIntValue()
    if (
      mode == null &&
      systemValue == null &&
      displayPercentage == null &&
      panelPath == null &&
      panelBrightness == null &&
      panelActualBrightness == null &&
      panelMaxBrightness == null
    ) {
      return null
    }
    return ScreenBrightnessState(
      mode = mode,
      value = systemValue,
      displayPercentage = displayPercentage,
      panelPath = panelPath,
      panelBrightness = panelBrightness,
      panelActualBrightness = panelActualBrightness,
      panelMaxBrightness = panelMaxBrightness
    )
  }

  private fun panelDiscoveryScript(): String {
    return """
      panel_dir=""
      for candidate in /sys/class/backlight/*; do
        if [ -e "${'$'}candidate/brightness" ] && [ -e "${'$'}candidate/max_brightness" ]; then
          panel_dir="${'$'}candidate"
          break
        fi
      done
    """.trimIndent()
  }

  private fun String.shellQuote(): String {
    return "'" + replace("'", "'\"'\"'") + "'"
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
