package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class PhoneAutomationAccessibilityService : AccessibilityService(), PhoneAutomationAccessibilityHost {
  private enum class TicketSliderTerminalDispatchResult {
    COMPLETED,
    CANCELLED,
    REJECTED,
    TIMED_OUT
  }

  private class TicketSliderTerminalGestureAwaiter(
    private val owner: PhoneAutomationAccessibilityService,
    private val reason: String,
    private val generation: Long,
    private val continuation: CancellableContinuation<TicketSliderTerminalDispatchResult>
  ) : GestureResultCallback(), Runnable, (Throwable?) -> Unit {
    private val handler = Handler(Looper.getMainLooper())

    fun scheduleTimeout(timeoutMillis: Long) {
      handler.postDelayed(this, timeoutMillis.coerceAtLeast(1L))
    }

    fun finish(result: TicketSliderTerminalDispatchResult) {
      handler.removeCallbacks(this)
      Log.i(
        TICKET_SLIDER_DIAGNOSTIC_TAG,
        "terminal_callback reason=$reason generation=$generation result=${result.name.lowercase()}"
      )
      if (continuation.isActive) continuation.resume(result)
    }

    override fun onCompleted(gestureDescription: GestureDescription?) {
      finish(TicketSliderTerminalDispatchResult.COMPLETED)
    }

    override fun onCancelled(gestureDescription: GestureDescription?) {
      if (owner.ticketSliderDispatchGeneration == generation) {
        owner.ticketSliderStroke = null
      }
      finish(TicketSliderTerminalDispatchResult.CANCELLED)
    }

    override fun run() {
      finish(TicketSliderTerminalDispatchResult.TIMED_OUT)
    }

    override fun invoke(cause: Throwable?) {
      handler.removeCallbacks(this)
      Log.i(
        TICKET_SLIDER_DIAGNOSTIC_TAG,
        "terminal_callback reason=$reason generation=$generation result=coroutine_cancelled"
      )
    }
  }

  private lateinit var windowManager: WindowManager
  private var blackoutOverlayView: View? = null
  private var panelSleepBrightnessShieldView: View? = null
  private var blackoutOverlayActivePointerCount = 0
  private var viviControlCodePreviousKeyboardShowMode: Int? = null
  private var viviControlCodeKeyboardExpectedPackageName: String? = null
  private var viviControlCodeKeyboardRecoveryReady: Boolean = false
  private var ticketSliderStroke: GestureDescription.StrokeDescription? = null
  private var ticketSliderDispatchGeneration: Long = 0L
  /** Main-thread gate that defers concurrent panel-sleep reassertions until a stroke is terminal. */
  private var ticketSliderBrightnessShieldSuspended: Boolean = false

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
    // Bind first so the already-requested panel-sleep brightness shield is synchronized before a
    // crash marker can restore a focused ViVi field to a keyboard-visible mode. Suppression itself
    // remains blocked on recoveryReady until the marker has been resolved.
    PhoneAutomationServiceBridge.bindAccessibilityService(this)
    viviControlCodeKeyboardRecoveryReady = recoverOwnedViviControlCodeKeyboardModeOnMainThread()
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
    ticketSliderDispatchGeneration += 1L
    ticketSliderStroke = null
    if (Looper.myLooper() == Looper.getMainLooper()) {
      restoreViviControlCodeKeyboardModeOnMainThread(null)
    }
    // Restore while the transparent panel-sleep brightness authority is still attached. The
    // window must be removed afterward because Android is unbinding this Accessibility service.
    syncBlackoutOverlayVisibility(false)
    syncPanelSleepBrightnessShieldVisibility(false)
    viviControlCodeKeyboardRecoveryReady = false
    PhoneAutomationServiceBridge.unbindAccessibilityService(this)
    return super.onUnbind(intent)
  }

  override suspend fun setBlackoutOverlayVisible(visible: Boolean): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      setBlackoutOverlayVisibleOnMainThread(visible)
    }
  }

  override suspend fun setPanelSleepBrightnessShieldVisible(visible: Boolean): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      setPanelSleepBrightnessShieldVisibleOnMainThread(visible)
    }
  }

  override fun syncPanelSleepBrightnessShieldVisibility(visible: Boolean): Boolean {
    return if (Looper.myLooper() == Looper.getMainLooper()) {
      setPanelSleepBrightnessShieldVisibleOnMainThread(visible)
    } else {
      false
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
      // Flutter can briefly expose a non-package active root while the ViVi
      // window is already foreground. Keep the cheap exact-root lookup as the
      // normal path, but fall back to the existing bounded package-root
      // resolver before declaring the registration proof unavailable. The
      // caller still requires the same deterministic markers and rooted frame
      // proof; this only prevents a transient accessibility-root mismatch from
      // turning a visible registration screen into a false failure.
      val root = fastRootForPackage(expectedPackageName)
        ?: rootForPackage(expectedPackageName)
        ?: return@withContext emptyList()
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

  override suspend fun suppressViviControlCodeKeyboardMode(
    expectedPackageName: String
  ): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      suppressViviControlCodeKeyboardOnMainThread(expectedPackageName)
    }
  }

  override suspend fun isViviControlCodeKeyboardModeSuppressed(
    expectedPackageName: String
  ): Boolean {
    return withContext(Dispatchers.Main.immediate) {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        viviControlCodeKeyboardRecoveryReady &&
        viviControlCodePreviousKeyboardShowMode != null &&
        viviControlCodeKeyboardExpectedPackageName == expectedPackageName &&
        softKeyboardController.showMode == SHOW_MODE_HIDDEN
    }
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

  override suspend fun performTicketSliderFullStroke(
    expectedPackageName: String,
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMillis: Long,
    timeoutMillis: Long
  ): TicketSliderGestureDispatchResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return TicketSliderGestureDispatchResult.REJECTED
    }
    return withContext(Dispatchers.Main.immediate) {
      if (ticketSliderStroke != null) {
        return@withContext TicketSliderGestureDispatchResult.REJECTED
      }
      // A TouchBrightness reassertion can remove/re-add the full-display brightness authority while
      // MotionEventInjector owns this stroke. Android may report the gesture completed even though
      // Flutter discarded that mid-gesture window/display-policy transition. Keep the logical
      // request intact but defer every actual accessibility-shield show until the stroke is
      // terminal. The action root helper and separate one-pixel application shield remain at zero.
      ticketSliderBrightnessShieldSuspended = true
      val shieldWasVisible = panelSleepBrightnessShieldView != null
      if (shieldWasVisible && !hidePanelSleepBrightnessShield()) {
        ticketSliderBrightnessShieldSuspended = false
        Log.i(
          TICKET_SLIDER_DIAGNOSTIC_TAG,
          "full_stroke_input_target result=brightness_shield_hide_failed"
        )
        return@withContext TicketSliderGestureDispatchResult.REJECTED
      }
      var generation = 0L
      var brightnessShieldRestored = true
      val result = try {
        if (shieldWasVisible) {
          // removeViewImmediate() detaches the view synchronously; one main-loop turn lets the input
          // window snapshot converge before MotionEventInjector chooses the gesture target.
          delay(TICKET_SLIDER_INPUT_WINDOW_SETTLE_MILLIS)
        }
        if (!isExpectedPackageFocusedForTicketSlider(expectedPackageName)) {
          Log.i(
            TICKET_SLIDER_DIAGNOSTIC_TAG,
            "full_stroke_input_target result=expected_package_not_focused"
          )
          return@withContext TicketSliderGestureDispatchResult.REJECTED
        }
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val start = startX.coerceIn(0, width - 1) to startY.coerceIn(0, height - 1)
        val end = endX.coerceIn(0, width - 1) to endY.coerceIn(0, height - 1)
        val fullStrokeDurationMillis = durationMillis.coerceIn(700L, 1_100L)
        val path = Path().apply {
          moveTo(start.first.toFloat(), start.second.toFloat())
          lineTo(end.first.toFloat(), end.second.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
          path,
          0L,
          fullStrokeDurationMillis,
          false
        )
        generation = ++ticketSliderDispatchGeneration
        ticketSliderStroke = stroke
        Log.i(
          TICKET_SLIDER_DIAGNOSTIC_TAG,
          "full_stroke generation=$generation display=${width}x$height requested_start=$startX,$startY actual_start=${start.first},${start.second} requested_end=$endX,$endY actual_end=${end.first},${end.second} duration_ms=$fullStrokeDurationMillis"
        )
        dispatchTerminalTicketSliderStroke(
          stroke = stroke,
          reason = "ticket_slider_full_stroke",
          generation = generation,
          timeoutMillis = timeoutMillis
        )
      } finally {
        if (generation > 0L && ticketSliderDispatchGeneration == generation) {
          ticketSliderStroke = null
        }
        ticketSliderBrightnessShieldSuspended = false
        val shieldRequested = PhoneAutomationServiceBridge.isPanelSleepBrightnessShieldRequested()
        brightnessShieldRestored = if (shieldRequested) {
          showPanelSleepBrightnessShield()
        } else {
          hidePanelSleepBrightnessShield()
        }
        if (shieldWasVisible || shieldRequested) {
          Log.i(
            TICKET_SLIDER_DIAGNOSTIC_TAG,
            "full_stroke_input_target result=${if (brightnessShieldRestored) "brightness_shield_converged" else "brightness_shield_convergence_failed"} requested=$shieldRequested"
          )
        }
      }
      Log.i(
        TICKET_SLIDER_DIAGNOSTIC_TAG,
        "full_stroke_result generation=$generation result=${result.name.lowercase()}"
      )
      if (!brightnessShieldRestored) {
        return@withContext TicketSliderGestureDispatchResult.UNKNOWN
      }
      when (result) {
        TicketSliderTerminalDispatchResult.COMPLETED ->
          TicketSliderGestureDispatchResult.COMPLETED
        TicketSliderTerminalDispatchResult.REJECTED ->
          TicketSliderGestureDispatchResult.REJECTED
        TicketSliderTerminalDispatchResult.CANCELLED,
        TicketSliderTerminalDispatchResult.TIMED_OUT ->
          TicketSliderGestureDispatchResult.UNKNOWN
      }
    }
  }

  /** Waits for Android to finish the one full stroke before ViVi state proof begins. */
  private suspend fun dispatchTerminalTicketSliderStroke(
    stroke: GestureDescription.StrokeDescription,
    reason: String,
    generation: Long,
    timeoutMillis: Long
  ): TicketSliderTerminalDispatchResult {
    PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(reason)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    return awaitTerminalTicketSliderStroke(gesture, reason, generation, timeoutMillis)
  }

  /**
   * Keep the Android callback bridge in its own suspend function.  The ticket
   * service is loaded by root app_process as well as by the normal Android
   * runtime; keeping this callback out of the timeout lambda avoids a nested
   * continuation class that some of the split dex loaders failed to resolve.
   */
  private suspend fun awaitTerminalTicketSliderStroke(
    gesture: GestureDescription,
    reason: String,
    generation: Long,
    timeoutMillis: Long
  ): TicketSliderTerminalDispatchResult = suspendCancellableCoroutine { continuation ->
    val awaiter = TicketSliderTerminalGestureAwaiter(this, reason, generation, continuation)
    continuation.invokeOnCancellation(awaiter)
    awaiter.scheduleTimeout(timeoutMillis)
    val dispatched = runCatching {
      dispatchGesture(gesture, awaiter, null)
    }.getOrDefault(false)
    Log.i(
      TICKET_SLIDER_DIAGNOSTIC_TAG,
      "terminal_dispatch reason=$reason generation=$generation accepted=$dispatched timeout_ms=${timeoutMillis.coerceAtLeast(1L)}"
    )
    if (!dispatched) awaiter.finish(TicketSliderTerminalDispatchResult.REJECTED)
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

  /** Final dispatch authority: the expected app must own Android's focused accessibility window. */
  private fun isExpectedPackageFocusedForTicketSlider(expectedPackageName: String): Boolean {
    if (expectedPackageName.isBlank()) return false
    val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
    if (activePackage == expectedPackageName) return true
    return windows.asSequence().any { window ->
      window.isFocused && window.root?.packageName?.toString().orEmpty() == expectedPackageName
    }
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
    if (!viviControlCodeKeyboardRecoveryReady) {
      // A later Ticket request reaches this method only after acquiring the panel-dark lane. That
      // gives a stale crash marker one safe retry once ViVi's rooted window is available again.
      viviControlCodeKeyboardRecoveryReady = recoverOwnedViviControlCodeKeyboardModeOnMainThread()
    }
    if (!viviControlCodeKeyboardRecoveryReady ||
      viviControlCodePreviousKeyboardShowMode != null
    ) return false
    val preferences = getSharedPreferences(
      VIVI_CONTROL_CODE_KEYBOARD_MODE_PREFERENCES,
      MODE_PRIVATE
    )
    if (preferences.getBoolean(VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY, false)) {
      viviControlCodeKeyboardRecoveryReady = false
      return false
    }
    val controller = softKeyboardController
    val previousMode = controller.showMode
    val markerSaved = preferences.edit()
      .putBoolean(VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY, true)
      .putInt(VIVI_CONTROL_CODE_KEYBOARD_MODE_PREVIOUS_KEY, previousMode)
      .commit()
    if (!markerSaved) {
      return false
    }
    viviControlCodePreviousKeyboardShowMode = previousMode
    viviControlCodeKeyboardExpectedPackageName = expectedPackageName
    val applied = controller.setShowMode(SHOW_MODE_HIDDEN) &&
      controller.showMode == SHOW_MODE_HIDDEN
    if (!applied) {
      restoreViviControlCodeKeyboardModeOnMainThread(expectedPackageName)
    }
    return applied
  }

  private fun clearOwnedViviControlCodeKeyboardModeMarker(): Boolean {
    return getSharedPreferences(VIVI_CONTROL_CODE_KEYBOARD_MODE_PREFERENCES, MODE_PRIVATE)
      .edit()
      .remove(VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY)
      .remove(VIVI_CONTROL_CODE_KEYBOARD_MODE_PREVIOUS_KEY)
      .commit()
  }

  private fun clearFocusedViviControlCodeInputOnMainThread(expectedPackageName: String): Boolean {
    val root = rootForPackage(expectedPackageName) ?: return false
    val focused = editableNodes(root).filter { node -> node.isFocused }.toList()
    if (focused.isEmpty()) {
      return true
    }
    return focused.all { node ->
      node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS) &&
        runCatching {
          node.refresh()
          !node.isFocused
        }.getOrDefault(false)
    }
  }

  private fun recoverOwnedViviControlCodeKeyboardModeOnMainThread(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return true
    }
    val preferences = getSharedPreferences(
      VIVI_CONTROL_CODE_KEYBOARD_MODE_PREFERENCES,
      MODE_PRIVATE
    )
    if (!preferences.getBoolean(VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY, false)) {
      return true
    }
    val previousMode = preferences.getInt(
      VIVI_CONTROL_CODE_KEYBOARD_MODE_PREVIOUS_KEY,
      SHOW_MODE_AUTO
    )
    val controller = softKeyboardController
    if (controller.showMode != SHOW_MODE_HIDDEN) {
      return clearOwnedViviControlCodeKeyboardModeMarker()
    }
    if (!clearFocusedViviControlCodeInputOnMainThread(VIVI_CONTROL_CODE_PACKAGE)) {
      return false
    }
    if (!controller.setShowMode(previousMode) || controller.showMode != previousMode) {
      return false
    }
    return clearOwnedViviControlCodeKeyboardModeMarker()
  }

  private fun restoreViviControlCodeKeyboardModeOnMainThread(
    expectedPackageName: String?
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return true
    }
    val previousMode = viviControlCodePreviousKeyboardShowMode ?: return !getSharedPreferences(
      VIVI_CONTROL_CODE_KEYBOARD_MODE_PREFERENCES,
      MODE_PRIVATE
    ).getBoolean(VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY, false)
    val controller = softKeyboardController
    if (controller.showMode != SHOW_MODE_HIDDEN) {
      // Another accessibility service or the user took ownership after our last verified gate.
      // Do not overwrite that newer choice with this request's stale prior mode.
      val markerCleared = clearOwnedViviControlCodeKeyboardModeMarker()
      if (markerCleared) {
        viviControlCodePreviousKeyboardShowMode = null
        viviControlCodeKeyboardExpectedPackageName = null
        viviControlCodeKeyboardRecoveryReady = true
      }
      return markerCleared
    }
    val packageToClear = expectedPackageName?.takeIf { it.isNotBlank() }
      ?: viviControlCodeKeyboardExpectedPackageName
    if (packageToClear.isNullOrBlank() ||
      !clearFocusedViviControlCodeInputOnMainThread(packageToClear)
    ) {
      return false
    }
    val restored = controller.setShowMode(previousMode) && controller.showMode == previousMode
    val markerCleared = restored && clearOwnedViviControlCodeKeyboardModeMarker()
    if (markerCleared) {
      viviControlCodePreviousKeyboardShowMode = null
      viviControlCodeKeyboardExpectedPackageName = null
      viviControlCodeKeyboardRecoveryReady = true
    }
    return markerCleared
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

  private fun setPanelSleepBrightnessShieldVisibleOnMainThread(visible: Boolean): Boolean {
    if (visible && ticketSliderBrightnessShieldSuspended) {
      return true
    }
    return if (visible) {
      showPanelSleepBrightnessShield()
    } else {
      hidePanelSleepBrightnessShield()
    }
  }

  private fun showPanelSleepBrightnessShield(): Boolean {
    if (panelSleepBrightnessShieldView != null) return true
    val shield = View(this).apply {
      setBackgroundColor(Color.TRANSPARENT)
      isClickable = false
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val layoutParams = WindowManager.LayoutParams(
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.MATCH_PARENT,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      // The surface itself is fully transparent, so this non-zero window alpha changes no pixels.
      // It does keep WindowManager from transiently dropping the zero-brightness override while a
      // short-lived hardware keyboard changes the display configuration under ViVi.
      alpha = PANEL_SLEEP_BRIGHTNESS_SHIELD_WINDOW_ALPHA
      screenBrightness = 0f
      title = PANEL_SLEEP_BRIGHTNESS_SHIELD_WINDOW_TITLE
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
      }
    }
    return runCatching {
      windowManager.addView(shield, layoutParams)
      panelSleepBrightnessShieldView = shield
      true
    }.getOrElse { false }
  }

  private fun hidePanelSleepBrightnessShield(): Boolean {
    val shield = panelSleepBrightnessShieldView ?: return true
    return runCatching {
      windowManager.removeViewImmediate(shield)
      panelSleepBrightnessShieldView = null
      true
    }.getOrElse { false }
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
    private const val TICKET_SLIDER_DIAGNOSTIC_TAG = "PixelTicketSlider"
    private const val OVERLAY_WAKE_REFRESH_MILLIS = 250L
    private const val TICKET_SLIDER_CONTINUATION_HANDOFF_GRACE_MILLIS = 80L
    // Live WindowManager/DisplayPower tracing measured the zero-to-zero authority handoff at
    // 48 ms after removeViewImmediate(). Keep it outside the stroke with bounded headroom.
    private const val TICKET_SLIDER_INPUT_WINDOW_SETTLE_MILLIS = 120L
    private const val PANEL_SLEEP_BRIGHTNESS_SHIELD_WINDOW_ALPHA = 0.001f
    private const val PANEL_SLEEP_BRIGHTNESS_SHIELD_WINDOW_TITLE =
      "PixelPanelSleepAccessibilityBrightnessShield"
    private const val VIVI_CONTROL_CODE_KEYBOARD_MODE_PREFERENCES =
      "vivi_control_code_keyboard_mode"
    private const val VIVI_CONTROL_CODE_KEYBOARD_MODE_OWNED_KEY = "owned"
    private const val VIVI_CONTROL_CODE_KEYBOARD_MODE_PREVIOUS_KEY = "previous"
    private const val VIVI_CONTROL_CODE_PACKAGE = "com.pv.vivi"
  }
}
