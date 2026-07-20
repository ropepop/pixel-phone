package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
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
  private var viviControlCodePreviousKeyboardShowMode: Int? = null
  private var viviControlCodeKeyboardExpectedPackageName: String? = null

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
    if (Looper.myLooper() == Looper.getMainLooper()) {
      restoreViviControlCodeKeyboardModeOnMainThread(null)
    }
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
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
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

  override suspend fun tapFirstMatchingCenter(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
    while (System.currentTimeMillis() < deadline) {
      val tapped = withContext(Dispatchers.Main.immediate) {
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
        val node = selectors.asSequence().mapNotNull { selector ->
          findMatchingNode(root, selector)
        }.firstOrNull() ?: return@withContext false
        tapNodeOrParentCenter(node)
      }
      if (tapped) {
        return true
      }
      delay(120)
    }
    return false
  }

  override suspend fun isAnySelectorPresent(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>
  ): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      val root = rootForPackage(expectedPackageName) ?: return@withContext false
      selectors.any { selector -> findMatchingNode(root, selector) != null }
    }
  }

  override suspend fun snapshotVisibleNodes(
    expectedPackageName: String
  ): List<PhoneAutomationVisibleNode> {
    return withContext(Dispatchers.Main.immediate) {
      val root = rootForPackage(expectedPackageName) ?: return@withContext emptyList()
      val flattenedNodes = flattenNodes(root).toList()
      val visibleNodes = flattenedNodes.filter { node -> node.isVisibleToUser }
      visibleNodes.ifEmpty {
        // Some secure/Flutter windows can expose useful semantic nodes while
        // reporting them as not visible to the in-process AccessibilityService.
        // Keep the snapshot cheap by reusing the current node tree instead of
        // falling back to the ~2.4s uiautomator shell dump.
        flattenedNodes.filter { node ->
          node.textValue().isNotBlank() ||
            node.contentDescriptionValue().isNotBlank() ||
            node.resourceIdValue().isNotBlank()
        }
      }
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
            focused = node.isFocused,
            editable = node.isEditable,
            focusable = node.isFocusable,
            hint = node.hintText?.toString().orEmpty()
          )
        }
        .toList()
    }
  }

  override suspend fun setTextInFocusedInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
    while (System.currentTimeMillis() < deadline) {
      val updated = withContext(Dispatchers.Main.immediate) {
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
        val target = editableFocusedNode(root) ?: return@withContext false
        val args = Bundle().apply {
          putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_set_text")
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
      }
      if (updated) {
        return true
      }
      delay(80)
    }
    return false
  }

  override suspend fun setTextInFirstEditableInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
    while (System.currentTimeMillis() < deadline) {
      val updated = withContext(Dispatchers.Main.immediate) {
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
        val target = firstEditableNode(root) ?: return@withContext false
        val args = Bundle().apply {
          putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_set_first_editable_text")
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
      }
      if (updated) {
        return true
      }
      delay(80)
    }
    return false
  }

  override suspend fun setViviControlCodeTextWithoutKeyboard(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
    while (System.currentTimeMillis() < deadline) {
      val updated = withContext(Dispatchers.Main.immediate) {
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
        val target = viviControlCodeEditableNode(root, expectedPackageName) ?: return@withContext false
        if (!suppressViviControlCodeKeyboardOnMainThread(expectedPackageName)) {
          restoreViviControlCodeKeyboardModeOnMainThread(expectedPackageName)
          return@withContext false
        }
        val activated = target.isFocused ||
          target.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
          target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!activated) {
          restoreViviControlCodeKeyboardModeOnMainThread(expectedPackageName)
          return@withContext false
        }
        val args = Bundle().apply {
          putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_activate_and_set_control_code_without_keyboard")
        val set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!set) {
          restoreViviControlCodeKeyboardModeOnMainThread(expectedPackageName)
        }
        set
      }
      if (updated) {
        return true
      }
      delay(40)
    }
    return false
  }

  override suspend fun submitViviControlCodeWithoutKeyboard(
    expectedPackageName: String,
    expectedText: String,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
    while (System.currentTimeMillis() < deadline) {
      val submitted = withContext(Dispatchers.Main.immediate) {
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
        val target = viviControlCodeSubmitNode(
          root = root,
          expectedPackageName = expectedPackageName,
          expectedText = expectedText
        ) ?: return@withContext false
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_submit_control_code_without_keyboard")
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      }
      if (submitted) {
        return true
      }
      delay(40)
    }
    return false
  }

  override suspend fun restoreViviControlCodeKeyboardMode(
    expectedPackageName: String
  ): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      restoreViviControlCodeKeyboardModeOnMainThread(expectedPackageName)
    }
  }

  override suspend fun tapScreenRatio(
    expectedPackageName: String,
    xRatio: Double,
    yRatio: Double,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
    while (System.currentTimeMillis() < deadline) {
      val tapped = withContext(Dispatchers.Main.immediate) {
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val x = (width * xRatio.coerceIn(0.0, 1.0)).toFloat()
        val y = (height * yRatio.coerceIn(0.0, 1.0)).toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
          .build()
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_ratio_tap")
        dispatchGesture(gesture, null, null)
      }
      if (tapped) {
        return true
      }
      delay(80)
    }
    return false
  }

  override suspend fun openFirstEditableInput(
    expectedPackageName: String,
    timeoutMillis: Long
  ): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1L)
    while (System.currentTimeMillis() < deadline) {
      val opened = withContext(Dispatchers.Main.immediate) {
        val root = rootForPackage(expectedPackageName) ?: return@withContext false
        val target = firstEditableNode(root) ?: return@withContext false
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_open_first_editable")
        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val focused = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        clicked || focused
      }
      if (opened) {
        return true
      }
      delay(80)
    }
    return false
  }

  override suspend fun performBack(): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      PhoneAutomationServiceBridge.markNonTouchInput("accessibility_back")
      performGlobalAction(GLOBAL_ACTION_BACK)
    }
  }

  override fun setClipboardText(text: String): Boolean {
    return runCatching {
      val clipboard = getSystemService(ClipboardManager::class.java)
      clipboard.setPrimaryClip(ClipData.newPlainText("chatgpt-broker", text))
      PhoneAutomationServiceBridge.markNonTouchInput("accessibility_clipboard_set")
      true
    }.getOrElse { false }
  }

  private fun rootForPackage(expectedPackageName: String): AccessibilityNodeInfo? {
    rootInActiveWindow?.takeIf { root -> rootPackageMatchesExpected(root, expectedPackageName) }
      ?.let { return it }
    return windows.asSequence()
      .mapNotNull { window -> window.root }
      .firstOrNull { root -> rootPackageMatchesExpected(root, expectedPackageName) }
  }

  private fun rootPackageMatchesExpected(root: AccessibilityNodeInfo, expectedPackageName: String): Boolean {
    val rootPackage = root.packageName?.toString().orEmpty()
    if (rootPackage == expectedPackageName) {
      return true
    }
    return flattenNodes(root).any { node -> nodePackageMatchesExpected(node, expectedPackageName) }
  }

  private fun nodePackageMatchesExpected(node: AccessibilityNodeInfo, expectedPackageName: String): Boolean {
    return node.packageName?.toString().orEmpty() == expectedPackageName
  }

  private fun firstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    return editableNodes(root).firstOrNull { node -> node.isVisibleToUser }
      ?: editableNodes(root).firstOrNull()
  }

  private fun viviControlCodeEditableNode(
    root: AccessibilityNodeInfo,
    expectedPackageName: String
  ): AccessibilityNodeInfo? {
    val visibleNodes = flattenNodes(root)
      .filter { node ->
        nodePackageMatchesExpected(node, expectedPackageName) &&
          node.isVisibleToUser &&
          node.isEnabled
      }
      .toList()
    val promptPresent = visibleNodes.any { node ->
      val label = nodeAccessibilityLabel(node)
      label.contains("kontroles kod") ||
        label.contains("control code") ||
        label.contains("enter the code manually")
    }
    if (!promptPresent) {
      return null
    }
    val submitPresent = visibleNodes.any { node ->
      val label = nodeAccessibilityLabel(node)
      val submitLabel =
        label == "ok" ||
          label == "labi" ||
          label.contains("apstiprin") ||
          label.contains("izveidot kod") ||
          label.contains("create code")
      submitLabel && clickableEnabledNodeOrParent(node) != null
    }
    if (!submitPresent) {
      return null
    }
    val editables = visibleNodes.filter { node ->
      node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
    }
    val labeled = editables.filter { node ->
      val label = nodeAccessibilityLabel(node)
      val resourceId = node.resourceIdValue().lowercase()
      label.contains("kontroles kod") ||
        label.contains("control code") ||
        label.contains("koda cipari") ||
        resourceId.contains("code") ||
        resourceId.contains("kod")
    }
    return labeled.singleOrNull() ?: editables.singleOrNull()
  }

  private fun viviControlCodeSubmitNode(
    root: AccessibilityNodeInfo,
    expectedPackageName: String,
    expectedText: String
  ): AccessibilityNodeInfo? {
    val input = viviControlCodeEditableNode(root, expectedPackageName) ?: return null
    if (input.textValue().trim() != expectedText.trim() || expectedText.isBlank()) {
      return null
    }
    return flattenNodes(root)
      .filter { node ->
        nodePackageMatchesExpected(node, expectedPackageName) &&
          node.isVisibleToUser &&
          node.isEnabled &&
          node.isClickable &&
          isViviControlCodeSubmitLabel(nodeAccessibilityLabel(node))
      }
      .singleOrNull()
  }

  private fun isViviControlCodeSubmitLabel(label: String): Boolean {
    return label == "ok" ||
      label == "labi" ||
      label.contains("apstiprin") ||
      label.contains("izveidot kod") ||
      label.contains("create code")
  }

  private fun suppressViviControlCodeKeyboardOnMainThread(
    expectedPackageName: String
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return false
    }
    val controller = softKeyboardController
    if (viviControlCodePreviousKeyboardShowMode == null) {
      viviControlCodePreviousKeyboardShowMode = controller.showMode
    }
    viviControlCodeKeyboardExpectedPackageName = expectedPackageName
    return controller.setShowMode(SHOW_MODE_HIDDEN)
  }

  private fun restoreViviControlCodeKeyboardModeOnMainThread(
    expectedPackageName: String?
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return true
    }
    val packageToClear = expectedPackageName?.takeIf { it.isNotBlank() }
      ?: viviControlCodeKeyboardExpectedPackageName
    if (!packageToClear.isNullOrBlank()) {
      rootForPackage(packageToClear)?.let { root ->
        editableNodes(root)
          .filter { node -> node.isFocused }
          .forEach { node -> node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) }
      }
    }
    val previousMode = viviControlCodePreviousKeyboardShowMode ?: return true
    val restored = softKeyboardController.setShowMode(previousMode)
    if (restored) {
      viviControlCodePreviousKeyboardShowMode = null
      viviControlCodeKeyboardExpectedPackageName = null
    }
    return restored
  }

  private fun nodeAccessibilityLabel(node: AccessibilityNodeInfo): String {
    return listOf(
      node.textValue(),
      node.contentDescriptionValue(),
      node.hintText?.toString().orEmpty()
    ).joinToString(" ").trim().lowercase().replace(Regex("""\s+"""), " ")
  }

  private fun clickableEnabledNodeOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
      if (current.isClickable && current.isEnabled) {
        return current
      }
      current = current.parent
    }
    return null
  }

  private fun editableFocusedNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
      if (focused.isEnabled && (focused.isEditable || focused.className?.toString()?.contains("EditText", ignoreCase = true) == true)) {
        return focused
      }
    }
    return editableNodes(root).firstOrNull { node -> node.isFocused && node.isVisibleToUser }
      ?: editableNodes(root).firstOrNull { node -> node.isFocused }
  }

  private fun editableNodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> {
    return flattenNodes(root).filter { node ->
      node.isEnabled &&
        (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true)
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

  private fun tapNodeOrParentCenter(node: AccessibilityNodeInfo): Boolean {
    var current: AccessibilityNodeInfo? = node
    while (current != null) {
      val bounds = Rect()
      current.getBoundsInScreen(bounds)
      if (bounds.width() > 0 && bounds.height() > 0) {
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
          .build()
        PhoneAutomationServiceBridge.markNonTouchInput("accessibility_semantic_tap")
        return dispatchGesture(gesture, null, null)
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
