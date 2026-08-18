package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Build
import android.os.Looper
import android.os.SystemClock
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
  private var ticketSliderStroke: GestureDescription.StrokeDescription? = null
  private var ticketSliderLastX: Int = 0
  private var ticketSliderLastY: Int = 0
  private var ticketSliderDispatchGeneration: Long = 0L
  private var ticketSliderNextDispatchAtMillis: Long = 0L

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
    ticketSliderDispatchGeneration += 1L
    ticketSliderStroke = null
    ticketSliderNextDispatchAtMillis = 0L
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
      // Some secure/Flutter windows expose the popup's editable field and
      // prompt semantics while reporting those nodes as not visible to the
      // in-process service. Keep those important semantic nodes in the same
      // cheap tree; otherwise the fast path sees the OK button but cannot
      // prove the input surface. This still avoids the multi-second shell
      // uiautomator dump and does not expose arbitrary hidden nodes.
      flattenedNodes.filter { node ->
        node.isVisibleToUser ||
          node.isEditable ||
          node.className?.toString()?.contains("EditText", ignoreCase = true) == true ||
          node.textValue().isNotBlank() ||
          node.contentDescriptionValue().isNotBlank() ||
          node.resourceIdValue().isNotBlank()
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

  override suspend fun snapshotTicketRegistrationNodes(
    expectedPackageName: String
  ): List<PhoneAutomationVisibleNode> {
    return withContext(Dispatchers.Main.immediate) {
      // rootForPackage() falls back to a recursive tree walk when Android
      // exposes an overlay root. That walk is useful for broad recovery but
      // can block the phone lane for seconds on ViVi's Flutter tree. The
      // registration fast path only needs the active ViVi root and a handful
      // of semantic anchors, so keep this lookup direct and bounded.
      val root = fastRootForPackage(expectedPackageName) ?: return@withContext emptyList()
      val semanticTerms = listOf(
        "reģistrēt biļeti",
        "reģistrēt bileti",
        "pavelc",
        "apstiprin",
        "biļete reģistrēta",
        "bilete registreta",
        "pasažieru vilciens",
        "pasazieru vilciens",
        "derīga",
        "deriga"
      )
      val matchedNodes = LinkedHashMap<String, AccessibilityNodeInfo>()
      var visitedNodes = 0
      val walkDeadline = SystemClock.uptimeMillis() + 180L

      // ViVi exposes the useful labels as content descriptions on generic
      // android.view.View nodes, so findAccessibilityNodeInfosByText() does
      // not see them. Walk only this bounded active root instead of using the
      // unbounded snapshotVisibleNodes() traversal.
      fun collect(node: AccessibilityNodeInfo, depth: Int) {
        if (
          visitedNodes >= 192 ||
          depth > 24 ||
          SystemClock.uptimeMillis() >= walkDeadline
        ) {
          return
        }
        visitedNodes += 1
        val label = listOf(
          node.textValue(),
          node.contentDescriptionValue(),
          node.hintText?.toString().orEmpty()
        ).joinToString(" ").lowercase().replace(Regex("\\s+"), " ")
        if (semanticTerms.any(label::contains)) {
          val bounds = Rect()
          node.getBoundsInScreen(bounds)
          val key = buildString {
            append(node.textValue()).append('|')
            append(node.contentDescriptionValue()).append('|')
            append(node.resourceIdValue()).append('|')
            append(bounds.left).append(',').append(bounds.top).append(',')
              .append(bounds.right).append(',').append(bounds.bottom)
          }
          matchedNodes.putIfAbsent(key, node)
        }
        for (index in 0 until node.childCount.coerceAtMost(48)) {
          if (SystemClock.uptimeMillis() >= walkDeadline) return
          node.getChild(index)?.let { child -> collect(child, depth + 1) }
        }
      }
      collect(root, 0)
      if (matchedNodes.isEmpty()) {
        return@withContext emptyList()
      }

      // Include a short parent chain because the large clickable ticket
      // surface normally carries the slider bounds while its child carries
      // the instruction text. No arbitrary descendants are traversed.
      val nodes = LinkedHashMap<String, AccessibilityNodeInfo>()
      matchedNodes.values.forEach { matched ->
        var current: AccessibilityNodeInfo? = matched
        repeat(5) {
          val node = current ?: return@repeat
          val key = buildString {
            append(node.textValue()).append('|')
            append(node.contentDescriptionValue()).append('|')
            append(node.resourceIdValue()).append('|')
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            append(bounds.left).append(',').append(bounds.top).append(',')
              .append(bounds.right).append(',').append(bounds.bottom)
          }
          nodes.putIfAbsent(key, node)
          current = node.parent
        }
      }
      nodes.values
        .filter { node -> nodePackageMatchesExpected(node, expectedPackageName) }
        .map { node -> visibleNodeSnapshot(node) }
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

  override suspend fun startTicketSliderGesture(
    startX: Int,
    startY: Int,
    timeoutMillis: Long
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return withContext(Dispatchers.Main.immediate) {
      if (ticketSliderStroke != null) return@withContext false
      val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
      val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
      val x = startX.coerceIn(0, width - 1)
      val y = startY.coerceIn(0, height - 1)
      // A zero-length continued stroke is treated as a tap by some Android
      // accessibility implementations. Give the pointer a one-pixel hold
      // segment so ViVi receives a real down/held gesture before the next
      // continuation, while keeping the visual starting position unchanged.
      val heldX = (x + 1).coerceAtMost(width - 1)
      val path = Path().apply {
        moveTo(x.toFloat(), y.toFloat())
        lineTo(heldX.toFloat(), y.toFloat())
      }
      val stroke = GestureDescription.StrokeDescription(path, 0L, 120L, true)
      val generation = ++ticketSliderDispatchGeneration
      ticketSliderStroke = stroke
      val ok = dispatchTicketSliderStroke(stroke, "ticket_slider_start", generation)
      if (ok) {
        ticketSliderLastX = heldX
        ticketSliderLastY = y
        ticketSliderNextDispatchAtMillis = SystemClock.uptimeMillis() + 120L
      } else if (ticketSliderDispatchGeneration == generation) {
        ticketSliderStroke = null
        ticketSliderNextDispatchAtMillis = 0L
      }
      ok
    }
  }

  override suspend fun continueTicketSliderGesture(
    endX: Int,
    endY: Int,
    durationMillis: Long,
    timeoutMillis: Long
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val waitMillis = withContext(Dispatchers.Main.immediate) {
      (ticketSliderNextDispatchAtMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L)
    }
    if (waitMillis > 0L) delay(waitMillis)
    return withContext(Dispatchers.Main.immediate) {
      val current = ticketSliderStroke ?: return@withContext false
      val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
      val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
      val x = endX.coerceIn(0, width - 1)
      val y = endY.coerceIn(0, height - 1)
      val path = Path().apply {
        moveTo(ticketSliderLastX.toFloat(), ticketSliderLastY.toFloat())
        lineTo(x.toFloat(), y.toFloat())
      }
      val next = current.continueStroke(path, 0L, durationMillis.coerceIn(32L, 100L), true)
      val generation = ++ticketSliderDispatchGeneration
      ticketSliderStroke = next
      val ok = dispatchTicketSliderStroke(next, "ticket_slider_continue", generation)
      if (ok) {
        ticketSliderLastX = x
        ticketSliderLastY = y
        ticketSliderNextDispatchAtMillis = SystemClock.uptimeMillis() + durationMillis.coerceIn(32L, 100L)
      } else if (ticketSliderDispatchGeneration == generation) {
        ticketSliderStroke = null
        ticketSliderNextDispatchAtMillis = 0L
      }
      ok
    }
  }

  override suspend fun endTicketSliderGesture(
    endX: Int,
    endY: Int,
    durationMillis: Long,
    timeoutMillis: Long
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val waitMillis = withContext(Dispatchers.Main.immediate) {
      (ticketSliderNextDispatchAtMillis - SystemClock.uptimeMillis()).coerceAtLeast(0L)
    }
    if (waitMillis > 0L) delay(waitMillis)
    return withContext(Dispatchers.Main.immediate) {
      val current = ticketSliderStroke ?: return@withContext false
      val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
      val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
      val x = endX.coerceIn(0, width - 1)
      val y = endY.coerceIn(0, height - 1)
      val path = Path().apply {
        moveTo(ticketSliderLastX.toFloat(), ticketSliderLastY.toFloat())
        lineTo(x.toFloat(), y.toFloat())
      }
      val next = current.continueStroke(path, 0L, durationMillis.coerceIn(32L, 180L), false)
      val generation = ++ticketSliderDispatchGeneration
      ticketSliderStroke = next
      val ok = dispatchTicketSliderStroke(next, "ticket_slider_end", generation)
      if (ok) {
        ticketSliderStroke = null
        ticketSliderNextDispatchAtMillis = 0L
        ticketSliderLastX = x
        ticketSliderLastY = y
      } else if (ticketSliderDispatchGeneration == generation) {
        ticketSliderStroke = null
        ticketSliderNextDispatchAtMillis = 0L
      }
      ok
    }
  }

  /**
   * Accessibility dispatch acknowledges that a segment was accepted, not that a
   * continued stroke has reached its final endpoint. Waiting for onCompleted on
   * a willContinue segment blocks the Spacetime command lane indefinitely on
   * Android builds that defer that callback until the stroke is ended. The
   * rooted H.264/state proof remains the authoritative completion check.
   */
  private fun dispatchTicketSliderStroke(
    stroke: GestureDescription.StrokeDescription,
    reason: String,
    generation: Long
  ): Boolean {
    PhoneAutomationServiceBridge.markNonTouchInput(reason)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    val dispatched = runCatching {
      dispatchGesture(
        gesture,
        object : GestureResultCallback() {
          override fun onCompleted(gestureDescription: GestureDescription?) = Unit

          override fun onCancelled(gestureDescription: GestureDescription?) {
            if (ticketSliderDispatchGeneration == generation) {
              ticketSliderStroke = null
              ticketSliderNextDispatchAtMillis = 0L
            }
          }
        },
        null
      )
    }.getOrDefault(false)
    if (!dispatched && ticketSliderDispatchGeneration == generation) {
      ticketSliderStroke = null
      ticketSliderNextDispatchAtMillis = 0L
    }
    return dispatched
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

  private fun rootForPackage(expectedPackageName: String): AccessibilityNodeInfo? {
    rootInActiveWindow?.takeIf { root -> rootPackageMatchesExpected(root, expectedPackageName) }
      ?.let { return it }
    return windows.asSequence()
      .mapNotNull { window -> window.root }
      .firstOrNull { root -> rootPackageMatchesExpected(root, expectedPackageName) }
  }

  private fun fastRootForPackage(expectedPackageName: String): AccessibilityNodeInfo? {
    rootInActiveWindow
      ?.takeIf { root -> root.packageName?.toString().orEmpty() == expectedPackageName }
      ?.let { return it }
    return windows.asSequence()
      .mapNotNull { window -> window.root }
      .firstOrNull { root -> root.packageName?.toString().orEmpty() == expectedPackageName }
  }

  private fun visibleNodeSnapshot(node: AccessibilityNodeInfo): PhoneAutomationVisibleNode {
    val bounds = Rect()
    node.getBoundsInScreen(bounds)
    return PhoneAutomationVisibleNode(
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
