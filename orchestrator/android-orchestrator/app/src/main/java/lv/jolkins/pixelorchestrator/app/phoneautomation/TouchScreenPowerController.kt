package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

internal interface TouchScreenPowerController {
  val wakeHoldActive: Boolean

  suspend fun wakeScreen(reason: String): PhoneAutomationActionResult
  fun holdScreen(reason: String)
  fun releaseHold(reason: String)
}

internal class AndroidTouchScreenPowerController(
  context: Context,
  private val rootExecutor: RootExecutor
) : TouchScreenPowerController {
  private val appContext = context.applicationContext
  private val powerManager = appContext.getSystemService(PowerManager::class.java)
  private var wakeLock: PowerManager.WakeLock? = null

  override val wakeHoldActive: Boolean
    get() = wakeLock?.isHeld == true

  @Suppress("DEPRECATION")
  override suspend fun wakeScreen(reason: String): PhoneAutomationActionResult {
    holdScreen("wake:$reason")
    val interactive = powerManager?.isInteractive == true
    if (interactive) {
      return PhoneAutomationActionResult(true, "Screen already interactive")
    }
    val result = withContext(Dispatchers.IO) {
      rootExecutor.run("input keyevent KEYCODE_WAKEUP", timeout = 3.seconds)
    }
    return if (result.ok) {
      PhoneAutomationActionResult(true, "Screen wake requested")
    } else {
      PhoneAutomationActionResult(
        success = false,
        detail = result.stderr.ifBlank { result.stdout.ifBlank { "Screen wake command failed" } }
      )
    }
  }

  @Suppress("DEPRECATION")
  override fun holdScreen(reason: String) {
    val manager = powerManager ?: return
    val existing = wakeLock
    if (existing?.isHeld == true) {
      existing.acquire(WAKE_HOLD_REFRESH_MILLIS)
      return
    }
    val lock = manager.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "${appContext.packageName}:touch-brightness"
    )
    lock.setReferenceCounted(false)
    runCatching {
      lock.acquire(WAKE_HOLD_REFRESH_MILLIS)
      wakeLock = lock
      Log.d(TAG, "touch_screen_hold_acquired reason=$reason uptime=${SystemClock.uptimeMillis()}")
    }.onFailure {
      Log.w(TAG, "touch_screen_hold_failed reason=$reason", it)
    }
  }

  override fun releaseHold(reason: String) {
    val lock = wakeLock ?: return
    wakeLock = null
    runCatching {
      if (lock.isHeld) {
        lock.release()
      }
      Log.d(TAG, "touch_screen_hold_released reason=$reason uptime=${SystemClock.uptimeMillis()}")
    }.onFailure {
      Log.w(TAG, "touch_screen_hold_release_failed reason=$reason", it)
    }
  }

  private companion object {
    private const val WAKE_HOLD_REFRESH_MILLIS = 10 * 60 * 1000L
    private const val TAG = "TouchScreenPower"
  }
}
