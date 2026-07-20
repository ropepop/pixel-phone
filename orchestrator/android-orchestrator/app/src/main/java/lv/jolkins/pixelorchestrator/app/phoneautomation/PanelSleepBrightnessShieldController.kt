package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

internal interface PanelSleepBrightnessShieldController {
  suspend fun prepare(): PhoneAutomationActionResult
  suspend fun show(): PhoneAutomationActionResult
  suspend fun hide(): PhoneAutomationActionResult
  fun isVisible(): Boolean
}

internal class AndroidPanelSleepBrightnessShieldController(
  context: Context,
  private val rootExecutor: RootExecutor
) : PanelSleepBrightnessShieldController {
  private val appContext = context.applicationContext
  private val windowManager = appContext.getSystemService(WindowManager::class.java)

  override suspend fun prepare(): PhoneAutomationActionResult {
    val packageName = appContext.packageName
    val result = rootExecutor.runScript(
      """
        if ! appops set $packageName SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; then
          exit 1
        fi
        appops get $packageName SYSTEM_ALERT_WINDOW 2>/dev/null | grep -q 'SYSTEM_ALERT_WINDOW: allow'
      """.trimIndent()
    )
    if (!result.ok) {
      return PhoneAutomationActionResult(
        success = false,
        detail = result.stderr.ifBlank { result.stdout.ifBlank { "Could not allow the panel-sleep brightness shield" } }
      )
    }
    return if (Settings.canDrawOverlays(appContext)) {
      PhoneAutomationActionResult(true, "Panel-sleep brightness shield is ready")
    } else {
      PhoneAutomationActionResult(false, "Panel-sleep brightness shield permission did not become ready")
    }
  }

  override suspend fun show(): PhoneAutomationActionResult {
    if (!Settings.canDrawOverlays(appContext)) {
      val preparation = prepare()
      if (!preparation.success) return preparation
    }
    return withContext(Dispatchers.Main.immediate) {
      if (sharedShieldView != null) {
        return@withContext PhoneAutomationActionResult(true, "Panel-sleep brightness shield already shown")
      }
      val view = View(appContext).apply {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      }
      val params = WindowManager.LayoutParams(
        SHIELD_SIZE_PX,
        SHIELD_SIZE_PX,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
          WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
      ).apply {
        gravity = Gravity.TOP or Gravity.START
        alpha = 0f
        screenBrightness = 0f
        title = SHIELD_WINDOW_TITLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
      }
      runCatching {
        windowManager.addView(view, params)
        sharedShieldView = view
        PhoneAutomationActionResult(true, "Panel-sleep brightness shield shown")
      }.getOrElse { error ->
        sharedShieldView = null
        PhoneAutomationActionResult(false, "Could not show panel-sleep brightness shield: ${error.message ?: error::class.java.simpleName}")
      }
    }
  }

  override suspend fun hide(): PhoneAutomationActionResult = withContext(Dispatchers.Main.immediate) {
    val view = sharedShieldView
      ?: return@withContext PhoneAutomationActionResult(true, "Panel-sleep brightness shield already hidden")
    runCatching {
      windowManager.removeViewImmediate(view)
      sharedShieldView = null
      PhoneAutomationActionResult(true, "Panel-sleep brightness shield hidden")
    }.getOrElse { error ->
      PhoneAutomationActionResult(false, "Could not hide panel-sleep brightness shield: ${error.message ?: error::class.java.simpleName}")
    }
  }

  override fun isVisible(): Boolean = sharedShieldView != null

  private companion object {
    @Volatile
    private var sharedShieldView: View? = null
    private const val SHIELD_SIZE_PX = 1
    private const val SHIELD_WINDOW_TITLE = "PixelPanelSleepBrightnessShield"
  }
}
