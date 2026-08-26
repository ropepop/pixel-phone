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
  suspend fun forceWakeScreen(reason: String): PhoneAutomationActionResult
  fun holdScreen(reason: String)
  fun releaseHold(reason: String)
}

internal fun touchScreenWakeHoldNeedsRefresh(
  isHeld: Boolean,
  nowUptimeMillis: Long,
  expiresAtUptimeMillis: Long,
  refreshMarginMillis: Long
): Boolean = !isHeld || expiresAtUptimeMillis <= 0L ||
  nowUptimeMillis >= expiresAtUptimeMillis - refreshMarginMillis.coerceAtLeast(0L)

internal class AndroidTouchScreenPowerController(
  context: Context,
  private val rootExecutor: RootExecutor,
  private val uptimeClock: () -> Long = SystemClock::uptimeMillis
) : TouchScreenPowerController {
  private val appContext = context.applicationContext
  private val powerManager = appContext.getSystemService(PowerManager::class.java)
  private var wakeLock: PowerManager.WakeLock? = null
  private var wakeHoldExpiresAtUptimeMillis: Long = 0L

  override val wakeHoldActive: Boolean
    get() = wakeLock?.isHeld == true

  @Suppress("DEPRECATION")
  override suspend fun wakeScreen(reason: String): PhoneAutomationActionResult {
    holdScreen("wake:$reason")
    val interactive = powerManager?.isInteractive == true
    if (interactive) {
      return PhoneAutomationActionResult(true, "Screen already interactive")
    }
    return requestWakeKey(reason)
  }

  override suspend fun forceWakeScreen(reason: String): PhoneAutomationActionResult {
    holdScreen("force_wake:$reason")
    return requestWakeKey(reason)
  }

  private suspend fun requestWakeKey(reason: String): PhoneAutomationActionResult {
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
  @Synchronized
  override fun holdScreen(reason: String) {
    val manager = powerManager ?: return
    val existing = wakeLock
    val now = uptimeClock()
    if (!touchScreenWakeHoldNeedsRefresh(
        isHeld = existing?.isHeld == true,
        nowUptimeMillis = now,
        expiresAtUptimeMillis = wakeHoldExpiresAtUptimeMillis,
        refreshMarginMillis = WAKE_HOLD_REFRESH_MARGIN_MILLIS
      )
    ) {
      return
    }
    val lock = existing ?: manager.newWakeLock(
        PowerManager.SCREEN_DIM_WAKE_LOCK,
        "${appContext.packageName}:touch-brightness"
      ).also { it.setReferenceCounted(false) }
    runCatching {
      lock.acquire(WAKE_HOLD_REFRESH_MILLIS)
      wakeLock = lock
      wakeHoldExpiresAtUptimeMillis = now + WAKE_HOLD_REFRESH_MILLIS
      Log.d(TAG, "touch_screen_hold_acquired reason=$reason uptime=$now")
    }.onFailure {
      Log.w(TAG, "touch_screen_hold_failed reason=$reason", it)
    }
  }

  @Synchronized
  override fun releaseHold(reason: String) {
    val lock = wakeLock ?: return
    wakeLock = null
    wakeHoldExpiresAtUptimeMillis = 0L
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
    private const val WAKE_HOLD_REFRESH_MARGIN_MILLIS = 60 * 1000L
    private const val TAG = "TouchScreenPower"
  }
}
