package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class PhoneAutomationAccessibilityService : AccessibilityService(), PhoneAutomationAccessibilityHost {
  private lateinit var windowManager: WindowManager
  private var blackoutOverlayView: View? = null
  private var blackoutOverlayActivePointerCount = 0

  override fun onServiceConnected() {
    super.onServiceConnected()
    windowManager = getSystemService(WindowManager::class.java)
    serviceInfo = serviceInfo.apply {
      eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
      feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
      flags = flags or
        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
      notificationTimeout = 100
    }
    PhoneAutomationServiceBridge.bindAccessibilityService(this)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    when (event?.eventType) {
      AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
        PhoneAutomationServiceBridge.recordTouchInteractionStarted()
      }

      AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
        PhoneAutomationServiceBridge.recordTouchInteractionEnded()
      }
    }
    val packageName = event?.packageName?.toString()
    if (!packageName.isNullOrBlank()) {
      PhoneAutomationServiceBridge.updateForegroundPackage(packageName)
    }
  }

  override fun onInterrupt() = Unit

  override fun onUnbind(intent: android.content.Intent?): Boolean {
    syncBlackoutOverlayVisibility(false)
    PhoneAutomationServiceBridge.unbindAccessibilityService(this)
    return super.onUnbind(intent)
  }

  override suspend fun setBlackoutOverlayVisible(visible: Boolean): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      setBlackoutOverlayVisibleOnMainThread(visible)
    }
  }

  override fun syncBlackoutOverlayVisibility(visible: Boolean): Boolean {
    return if (Looper.myLooper() == Looper.getMainLooper()) {
      setBlackoutOverlayVisibleOnMainThread(visible)
    } else {
      false
    }
  }

  override suspend fun clickFirstMatching(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      val clicked = withContext(Dispatchers.Main.immediate) {
        val root = rootInActiveWindow ?: return@withContext false
        val rootPackage = root.packageName?.toString().orEmpty()
        if (rootPackage != expectedPackageName) {
          return@withContext false
        }
        val node = selectors.asSequence().mapNotNull { selector ->
          findMatchingNode(root, selector)
        }.firstOrNull() ?: return@withContext false
        clickNodeOrClickableParent(node)
      }
      if (clicked) {
        return true
      }
      delay(250)
    }
    return false
  }

  override suspend fun isAnySelectorPresent(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>
  ): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      val root = rootInActiveWindow ?: return@withContext false
      val rootPackage = root.packageName?.toString().orEmpty()
      if (rootPackage != expectedPackageName) {
        return@withContext false
      }
      selectors.any { selector -> findMatchingNode(root, selector) != null }
    }
  }

  override suspend fun snapshotVisibleNodes(
    expectedPackageName: String
  ): List<PhoneAutomationVisibleNode> {
    return withContext(Dispatchers.Main.immediate) {
      val root = rootInActiveWindow ?: return@withContext emptyList()
      val rootPackage = root.packageName?.toString().orEmpty()
      if (rootPackage != expectedPackageName) {
        return@withContext emptyList()
      }
      flattenNodes(root)
        .filter { node -> node.isVisibleToUser }
        .map { node ->
          val bounds = Rect()
          node.getBoundsInScreen(bounds)
          PhoneAutomationVisibleNode(
            text = node.textValue(),
            resourceId = node.resourceIdValue(),
            contentDescription = node.contentDescriptionValue(),
            className = node.className?.toString().orEmpty(),
            bounds = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
            clickable = node.isClickable,
            enabled = node.isEnabled,
            focused = node.isFocused
          )
        }
        .toList()
    }
  }

  override suspend fun performBack(): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      PhoneAutomationServiceBridge.markNonTouchInput("accessibility_back")
      performGlobalAction(GLOBAL_ACTION_BACK)
    }
  }

  private fun findMatchingNode(
    root: AccessibilityNodeInfo,
    selector: PhoneAutomationSelector
  ): AccessibilityNodeInfo? {
    selector.resourceId?.let { resourceId ->
      root.findAccessibilityNodeInfosByViewId(resourceId)
        ?.firstOrNull { node -> selector.matches(node.textValue(), node.resourceIdValue(), node.contentDescriptionValue()) }
        ?.let { return it }
    }

    selector.text?.let { text ->
      root.findAccessibilityNodeInfosByText(text)
        ?.firstOrNull { node -> selector.matches(node.textValue(), node.resourceIdValue(), node.contentDescriptionValue()) }
        ?.let { return it }
    }

    return flattenNodes(root).firstOrNull { node ->
      selector.matches(node.textValue(), node.resourceIdValue(), node.contentDescriptionValue())
    }
  }

  private fun flattenNodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
    yield(root)
    for (index in 0 until root.childCount) {
      val child = root.getChild(index) ?: continue
      yieldAll(flattenNodes(child))
    }
  }

  private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
      if (current.isClickable && current.isEnabled) {
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_click")
        return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      }
      current = current.parent
    }
    return false
  }

  private fun AccessibilityNodeInfo.textValue(): String = text?.toString().orEmpty()

  private fun AccessibilityNodeInfo.resourceIdValue(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
      viewIdResourceName.orEmpty()
    } else {
      ""
    }
  }

  private fun AccessibilityNodeInfo.contentDescriptionValue(): String = contentDescription?.toString().orEmpty()

  private fun setBlackoutOverlayVisibleOnMainThread(visible: Boolean): Boolean {
    return if (visible) {
      showBlackoutOverlay()
    } else {
      hideBlackoutOverlay()
    }
  }

  private fun showBlackoutOverlay(): Boolean {
    if (blackoutOverlayView != null) {
      return true
    }
    blackoutOverlayActivePointerCount = 0
    val overlay = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      isClickable = true
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      var lastWakeEventTime = 0L
      setOnTouchListener { _, event ->
        val previousCount = blackoutOverlayActivePointerCount
        val gestureEnded = when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            blackoutOverlayActivePointerCount = 1
            false
          }
          MotionEvent.ACTION_POINTER_DOWN -> {
            blackoutOverlayActivePointerCount = event.pointerCount.coerceAtLeast(previousCount + 1)
            false
          }
          MotionEvent.ACTION_POINTER_UP -> {
            blackoutOverlayActivePointerCount = (event.pointerCount - 1).coerceAtLeast(0)
            false
          }
          MotionEvent.ACTION_UP,
          MotionEvent.ACTION_CANCEL -> {
            blackoutOverlayActivePointerCount = 0
            true
          }
          else -> {
            blackoutOverlayActivePointerCount = event.pointerCount.coerceAtLeast(previousCount)
            false
          }
        }
        val shouldReport = when (event.actionMasked) {
          MotionEvent.ACTION_DOWN,
          MotionEvent.ACTION_POINTER_DOWN,
          MotionEvent.ACTION_POINTER_UP,
          MotionEvent.ACTION_UP,
          MotionEvent.ACTION_CANCEL -> true
          MotionEvent.ACTION_MOVE -> event.eventTime - lastWakeEventTime >= OVERLAY_WAKE_REFRESH_MILLIS
          else -> false
        }
        if (shouldReport) {
          lastWakeEventTime = event.eventTime
          PhoneAutomationServiceBridge.recordBlackoutOverlayWakeRequested(
            observedAtUptimeMillis = event.eventTime,
            activePointerCount = blackoutOverlayActivePointerCount,
            gestureEnded = gestureEnded
          )
        }
        true
      }
    }
    val layoutParams = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      PixelFormat.OPAQUE
    ).apply {
      gravity = Gravity.TOP or Gravity.START
    }
    return runCatching {
      windowManager.addView(overlay, layoutParams)
      blackoutOverlayView = overlay
      true
    }.getOrElse { false }
  }

  private fun hideBlackoutOverlay(): Boolean {
    val overlay = blackoutOverlayView ?: return true
    blackoutOverlayActivePointerCount = 0
    return runCatching {
      windowManager.removeViewImmediate(overlay)
      blackoutOverlayView = null
      true
    }.getOrElse {
      blackoutOverlayView = null
      false
    }
  }

  private companion object {
    private const val OVERLAY_WAKE_REFRESH_MILLIS = 250L
  }
}
