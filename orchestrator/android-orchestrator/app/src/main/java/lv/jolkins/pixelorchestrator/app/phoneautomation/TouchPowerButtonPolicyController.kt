package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.Context
import android.content.SharedPreferences
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

/** Owns only Android's short power press; long press, emergency and volume policy are untouched. */
internal interface TouchPowerButtonPolicyController {
  suspend fun acquire(): Boolean
  suspend fun restore(): Boolean
}

internal data class TouchPowerButtonOriginalSetting(val value: String?)

internal interface TouchPowerButtonPolicyCheckpoint {
  fun load(): TouchPowerButtonOriginalSetting?
  fun save(setting: TouchPowerButtonOriginalSetting): Boolean
  fun clear(): Boolean
}

internal class AndroidTouchPowerButtonPolicyController internal constructor(
  private val rootExecutor: RootExecutor,
  private val checkpoint: TouchPowerButtonPolicyCheckpoint
) : TouchPowerButtonPolicyController {
  constructor(context: Context, rootExecutor: RootExecutor) : this(
    rootExecutor,
    SharedPreferencesTouchPowerButtonPolicyCheckpoint(
      context.applicationContext.getSharedPreferences("touch_power_button_policy", Context.MODE_PRIVATE)
    )
  )

  private val mutex = Mutex()

  override suspend fun acquire(): Boolean = mutex.withLock {
    if (checkpoint.load() == null) {
      val original = readSetting() ?: return@withLock false
      // Synchronous durable write must precede mutation, including when the original was absent.
      if (!checkpoint.save(original) || checkpoint.load() != original) return@withLock false
    }
    val result = rootExecutor.runScript(
      """
        settings put global power_button_short_press 0 || exit 1
        attempt=0
        while [ "${'$'}attempt" -lt 10 ]; do
          if dumpsys window policy | grep -q 'mShortPressOnPowerBehavior=SHORT_PRESS_POWER_NOTHING'; then
            [ "${'$'}(settings get global power_button_short_press)" = 0 ] && exit 0
          fi
          attempt=${'$'}((attempt + 1))
          sleep 0.1
        done
        exit 1
      """.trimIndent(),
      timeout = 5.seconds
    )
    result.ok
  }

  override suspend fun restore(): Boolean = mutex.withLock {
    val original = checkpoint.load() ?: return@withLock true
    val command = original.value?.let {
      "settings put global power_button_short_press '${it.replace("'", "'\\''")}'"
    } ?: "settings delete global power_button_short_press"
    if (!rootExecutor.runScript(command, timeout = 3.seconds).ok) return@withLock false
    if (readSetting() != original) return@withLock false
    checkpoint.clear() && checkpoint.load() == null
  }

  private suspend fun readSetting(): TouchPowerButtonOriginalSetting? {
    // Preserve absence independently of the textual value, unlike `settings get`'s null sentinel.
    val result = rootExecutor.runScript(
      "settings_snapshot=${'$'}(settings list global) || exit 1\nprintf '%s\\n' \"${'$'}settings_snapshot\" | sed -n '/^power_button_short_press=/p'",
      timeout = 3.seconds
    )
    if (!result.ok) return null
    val line = result.stdout.trimEnd('\n', '\r')
    if (line.isEmpty()) return TouchPowerButtonOriginalSetting(null)
    if (!line.startsWith("power_button_short_press=") || line.contains('\n')) return null
    return TouchPowerButtonOriginalSetting(line.substringAfter('='))
  }
}

private class SharedPreferencesTouchPowerButtonPolicyCheckpoint(
  private val preferences: SharedPreferences
) : TouchPowerButtonPolicyCheckpoint {
  override fun load(): TouchPowerButtonOriginalSetting? {
    if (!preferences.getBoolean("captured", false)) return null
    return TouchPowerButtonOriginalSetting(preferences.getString("original_value", null))
  }

  override fun save(setting: TouchPowerButtonOriginalSetting): Boolean = preferences.edit()
    .putString("original_value", setting.value)
    .putBoolean("captured", true)
    .commit()

  override fun clear(): Boolean = preferences.edit().clear().commit()
}
