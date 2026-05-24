package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

data class PhoneAutomationObservedNotification(
  val key: String,
  val packageName: String,
  val channelId: String?,
  val title: String,
  val text: String,
  val actionTitles: List<String>,
  val postedAtMillis: Long,
  val ongoing: Boolean
)

sealed interface PhoneAutomationNotificationEvent {
  val notification: PhoneAutomationObservedNotification
  val observedAtMillis: Long

  data class Posted(
    override val notification: PhoneAutomationObservedNotification,
    override val observedAtMillis: Long
  ) : PhoneAutomationNotificationEvent

  data class Removed(
    override val notification: PhoneAutomationObservedNotification,
    override val observedAtMillis: Long
  ) : PhoneAutomationNotificationEvent
}

sealed interface PhoneAutomationTouchEvent {
  val observedAtMillis: Long

  data class Started(
    override val observedAtMillis: Long
  ) : PhoneAutomationTouchEvent

  data class Ended(
    override val observedAtMillis: Long
  ) : PhoneAutomationTouchEvent
}

sealed interface PhoneAutomationBlackoutOverlayEvent {
  val observedAtUptimeMillis: Long

  data class WakeRequested(
    override val observedAtUptimeMillis: Long,
    val activePointerCount: Int = 1,
    val gestureEnded: Boolean = false
  ) : PhoneAutomationBlackoutOverlayEvent
}

data class PhoneAutomationNonTouchInputEvent(
  val reason: String,
  val observedAtUptimeMillis: Long,
  val suppressedUntilUptimeMillis: Long
)

data class PhoneAutomationVisibleNode(
  val text: String,
  val resourceId: String,
  val contentDescription: String,
  val className: String = "",
  val bounds: String = "",
  val clickable: Boolean = false,
  val enabled: Boolean = false,
  val focused: Boolean = false,
  val editable: Boolean = false,
  val focusable: Boolean = false,
  val hint: String = ""
)

internal interface PhoneAutomationAccessibilityHost {
  fun syncBlackoutOverlayVisibility(visible: Boolean): Boolean

  suspend fun setBlackoutOverlayVisible(visible: Boolean): Boolean

  suspend fun clickFirstMatching(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean

  suspend fun tapFirstMatchingCenter(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean

  suspend fun isAnySelectorPresent(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>
  ): Boolean

  suspend fun snapshotVisibleNodes(
    expectedPackageName: String
  ): List<PhoneAutomationVisibleNode>

  suspend fun setTextInFocusedInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean

  suspend fun openFirstEditableInput(
    expectedPackageName: String,
    timeoutMillis: Long
  ): Boolean

  suspend fun setTextInFirstEditableInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean

  suspend fun performBack(): Boolean
}

object PhoneAutomationServiceBridge {
  private const val ACCESSIBILITY_SNAPSHOT_TIMEOUT_MILLIS = 750L
  private const val ACCESSIBILITY_CALL_GRACE_TIMEOUT_MILLIS = 250L

  private val accessibilityService = MutableStateFlow<PhoneAutomationAccessibilityHost?>(null)
  private val notificationListenerConnected = MutableStateFlow(false)
  private val notificationSnapshotReady = MutableStateFlow(false)
  private val foregroundPackage = MutableStateFlow<String?>(null)
  private val touchInteractionActive = MutableStateFlow(false)
  private val lastBlackoutWakeAtMillis = MutableStateFlow(0L)
  private val blackoutOverlayActivePointerCount = MutableStateFlow(0)
  private val lastExpectedOrchestratorForegroundAtMillis = MutableStateFlow(0L)
  private val blackoutOverlayRequested = MutableStateFlow(false)
  private val blackoutOverlaySuppressed = MutableStateFlow(false)
  private val remoteScreenBrightnessState = MutableStateFlow<ScreenBrightnessState?>(null)
  private val nonTouchInputSuppressedUntilUptimeMillis = MutableStateFlow(0L)
  private val activeNotifications = MutableStateFlow<Map<String, PhoneAutomationObservedNotification>>(emptyMap())
  private val rawNotificationEvents =
    MutableSharedFlow<PhoneAutomationNotificationEvent>(extraBufferCapacity = 64)
  private val rawTouchEvents = MutableSharedFlow<PhoneAutomationTouchEvent>(extraBufferCapacity = 64)
  private val rawBlackoutOverlayEvents =
    MutableSharedFlow<PhoneAutomationBlackoutOverlayEvent>(extraBufferCapacity = 16)
  private val rawNonTouchInputEvents =
    MutableSharedFlow<PhoneAutomationNonTouchInputEvent>(extraBufferCapacity = 64)

  val notificationEvents: Flow<PhoneAutomationNotificationEvent> = rawNotificationEvents.asSharedFlow()
  val touchEvents: Flow<PhoneAutomationTouchEvent> = rawTouchEvents.asSharedFlow()
  val blackoutOverlayEvents: Flow<PhoneAutomationBlackoutOverlayEvent> = rawBlackoutOverlayEvents.asSharedFlow()
  val nonTouchInputEvents: Flow<PhoneAutomationNonTouchInputEvent> = rawNonTouchInputEvents.asSharedFlow()
  val accessibilityAvailability: Flow<Boolean> = accessibilityService
    .map { it != null }
    .distinctUntilChanged()
  val notificationListenerAvailability: Flow<Boolean> = notificationListenerConnected

  internal fun bindAccessibilityService(service: PhoneAutomationAccessibilityHost) {
    accessibilityService.value = service
    service.syncBlackoutOverlayVisibility(blackoutOverlayRequested.value && !blackoutOverlaySuppressed.value)
  }

  internal fun unbindAccessibilityService(service: PhoneAutomationAccessibilityHost) {
    if (accessibilityService.value === service) {
      accessibilityService.value = null
    }
    foregroundPackage.value = null
    touchInteractionActive.value = false
  }

  fun updateForegroundPackage(packageName: String?) {
    foregroundPackage.value = packageName
  }

  fun setNotificationListenerConnected(connected: Boolean) {
    notificationListenerConnected.value = connected
    if (!connected) {
      notificationSnapshotReady.value = false
      activeNotifications.value = emptyMap()
    }
  }

  fun replaceActiveNotifications(notifications: Collection<PhoneAutomationObservedNotification>) {
    activeNotifications.value = notifications.associateBy { it.key }
    notificationSnapshotReady.value = true
  }

  fun recordPosted(notification: PhoneAutomationObservedNotification) {
    activeNotifications.update { current -> current + (notification.key to notification) }
    rawNotificationEvents.tryEmit(
      PhoneAutomationNotificationEvent.Posted(
        notification = notification,
        observedAtMillis = System.currentTimeMillis()
      )
    )
  }

  fun recordRemoved(notification: PhoneAutomationObservedNotification) {
    activeNotifications.update { current -> current - notification.key }
    rawNotificationEvents.tryEmit(
      PhoneAutomationNotificationEvent.Removed(
        notification = notification,
        observedAtMillis = System.currentTimeMillis()
      )
    )
  }

  fun isNotificationPresent(matcher: PhoneAutomationNotificationMatcher): Boolean {
    return activeNotifications.value.values.any(matcher::matches)
  }

  fun matchingNotifications(
    matcher: PhoneAutomationNotificationMatcher
  ): List<PhoneAutomationObservedNotification> {
    return activeNotifications.value.values.filter(matcher::matches)
  }

  fun recordTouchInteractionStarted(observedAtMillis: Long = System.currentTimeMillis()) {
    touchInteractionActive.value = true
    rawTouchEvents.tryEmit(PhoneAutomationTouchEvent.Started(observedAtMillis))
  }

  fun recordTouchInteractionEnded(observedAtMillis: Long = System.currentTimeMillis()) {
    touchInteractionActive.value = false
    rawTouchEvents.tryEmit(PhoneAutomationTouchEvent.Ended(observedAtMillis))
  }

  fun clearTouchInteraction() {
    touchInteractionActive.value = false
  }

  fun isTouchInteractionActive(): Boolean = touchInteractionActive.value

  fun recordBlackoutOverlayWakeRequested(
    observedAtUptimeMillis: Long,
    activePointerCount: Int = 1,
    gestureEnded: Boolean = false
  ) {
    lastBlackoutWakeAtMillis.value = System.currentTimeMillis()
    blackoutOverlayActivePointerCount.value = activePointerCount.coerceAtLeast(0)
    rawBlackoutOverlayEvents.tryEmit(
      PhoneAutomationBlackoutOverlayEvent.WakeRequested(
        observedAtUptimeMillis = observedAtUptimeMillis,
        activePointerCount = activePointerCount.coerceAtLeast(0),
        gestureEnded = gestureEnded
      )
    )
  }

  fun lastBlackoutWakeAtMillis(): Long = lastBlackoutWakeAtMillis.value

  fun blackoutOverlayActivePointerCount(): Int = blackoutOverlayActivePointerCount.value

  fun recordExpectedOrchestratorForeground(observedAtMillis: Long = System.currentTimeMillis()) {
    lastExpectedOrchestratorForegroundAtMillis.value = observedAtMillis
  }

  fun lastExpectedOrchestratorForegroundAtMillis(): Long {
    return lastExpectedOrchestratorForegroundAtMillis.value
  }

  fun isBlackoutOverlayAvailable(): Boolean = accessibilityService.value != null

  fun isBlackoutOverlaySuppressed(): Boolean = blackoutOverlaySuppressed.value

  internal fun remoteScreenBrightnessState(): ScreenBrightnessState? = remoteScreenBrightnessState.value

  internal fun setRemoteScreenBrightnessState(state: ScreenBrightnessState?) {
    remoteScreenBrightnessState.value = state
  }

  fun markNonTouchInput(
    reason: String,
    durationMillis: Long = NON_TOUCH_INPUT_SUPPRESSION_MILLIS,
    observedAtUptimeMillis: Long = SystemClock.uptimeMillis()
  ) {
    val untilMillis = observedAtUptimeMillis + durationMillis.coerceAtLeast(0L)
    nonTouchInputSuppressedUntilUptimeMillis.update { current ->
      maxOf(current, untilMillis)
    }
    rawNonTouchInputEvents.tryEmit(
      PhoneAutomationNonTouchInputEvent(
        reason = reason,
        observedAtUptimeMillis = observedAtUptimeMillis,
        suppressedUntilUptimeMillis = untilMillis
      )
    )
  }

  fun isNonTouchInputSuppressed(
    observedAtUptimeMillis: Long = SystemClock.uptimeMillis()
  ): Boolean {
    val suppressedUntil = nonTouchInputSuppressedUntilUptimeMillis.value
    return suppressedUntil > 0L && observedAtUptimeMillis <= suppressedUntil
  }

  fun setBlackoutOverlaySuppressed(suppressed: Boolean) {
    blackoutOverlaySuppressed.value = suppressed
    val service = accessibilityService.value ?: return
    if (suppressed) {
      blackoutOverlayRequested.value = false
      service.syncBlackoutOverlayVisibility(false)
    } else {
      service.syncBlackoutOverlayVisibility(blackoutOverlayRequested.value)
    }
  }

  suspend fun setBlackoutOverlayVisible(visible: Boolean): Boolean {
    if (visible && blackoutOverlaySuppressed.value) {
      blackoutOverlayRequested.value = false
      blackoutOverlayActivePointerCount.value = 0
      accessibilityService.value?.setBlackoutOverlayVisible(false)
      return true
    }
    blackoutOverlayRequested.value = visible
    if (!visible) {
      blackoutOverlayActivePointerCount.value = 0
    }
    val service = accessibilityService.value ?: return true
    return service.setBlackoutOverlayVisible(visible)
  }

  suspend fun awaitAccessibilityConnection(timeoutMillis: Long): Boolean {
    if (accessibilityService.value != null) {
      return true
    }
    return withTimeoutOrNull(timeoutMillis) {
      accessibilityService.filter { it != null }.first()
      true
    } ?: false
  }

  suspend fun awaitNotificationListenerConnection(timeoutMillis: Long): Boolean {
    if (notificationListenerConnected.value) {
      return true
    }
    return withTimeoutOrNull(timeoutMillis) {
      notificationListenerConnected.filter { it }.first()
      true
    } ?: false
  }

  suspend fun awaitNotificationBootstrap(timeoutMillis: Long): Boolean {
    if (notificationListenerConnected.value && notificationSnapshotReady.value) {
      return true
    }
    return withTimeoutOrNull(timeoutMillis) {
      notificationListenerConnected
        .combine(notificationSnapshotReady) { connected, ready -> connected && ready }
        .filter { it }
        .first()
      true
    } ?: false
  }

  fun isNotificationBootstrapReady(): Boolean {
    return notificationListenerConnected.value && notificationSnapshotReady.value
  }

  fun isAccessibilityServiceConnected(): Boolean = accessibilityService.value != null

  fun isNotificationListenerConnected(): Boolean = notificationListenerConnected.value

  suspend fun waitForForegroundPackage(packageName: String, timeoutMillis: Long): Boolean {
    if (foregroundPackage.value == packageName) {
      return true
    }
    return withTimeoutOrNull(timeoutMillis) {
      foregroundPackage.filter { it == packageName }.first()
      true
    } ?: false
  }

  fun isForegroundPackage(packageName: String): Boolean {
    return foregroundPackage.value == packageName
  }

  fun currentForegroundPackage(): String? {
    return foregroundPackage.value
  }

  suspend fun clickSelectors(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean {
    val service = accessibilityService.value ?: return false
    return service.clickFirstMatching(
      expectedPackageName = expectedPackageName,
      selectors = selectors,
      timeoutMillis = timeoutMillis
    )
  }

  suspend fun tapSelectorCenter(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean {
    val service = accessibilityService.value ?: return false
    return service.tapFirstMatchingCenter(
      expectedPackageName = expectedPackageName,
      selectors = selectors,
      timeoutMillis = timeoutMillis
    )
  }

  suspend fun isSelectorPresent(
    expectedPackageName: String,
    selectors: List<PhoneAutomationSelector>
  ): Boolean {
    val service = accessibilityService.value ?: return false
    return service.isAnySelectorPresent(
      expectedPackageName = expectedPackageName,
      selectors = selectors
    )
  }

  suspend fun snapshotVisibleNodes(expectedPackageName: String): List<PhoneAutomationVisibleNode> {
    val service = accessibilityService.value ?: return emptyList()
    return withTimeoutOrNull(ACCESSIBILITY_SNAPSHOT_TIMEOUT_MILLIS) {
      service.snapshotVisibleNodes(expectedPackageName)
    }.orEmpty()
  }

  suspend fun setTextInFocusedInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean {
    val service = accessibilityService.value ?: return false
    return withTimeoutOrNull(timeoutMillis.accessibilityCallTimeoutMillis()) {
      service.setTextInFocusedInput(expectedPackageName, text, timeoutMillis)
    } ?: false
  }

  suspend fun openFirstEditableInput(
    expectedPackageName: String,
    timeoutMillis: Long
  ): Boolean {
    val service = accessibilityService.value ?: return false
    return withTimeoutOrNull(timeoutMillis.accessibilityCallTimeoutMillis()) {
      service.openFirstEditableInput(expectedPackageName, timeoutMillis)
    } ?: false
  }

  suspend fun setTextInFirstEditableInput(
    expectedPackageName: String,
    text: String,
    timeoutMillis: Long
  ): Boolean {
    val service = accessibilityService.value ?: return false
    return withTimeoutOrNull(timeoutMillis.accessibilityCallTimeoutMillis()) {
      service.setTextInFirstEditableInput(expectedPackageName, text, timeoutMillis)
    } ?: false
  }

  suspend fun performBack(): Boolean {
    val service = accessibilityService.value ?: return false
    return service.performBack()
  }

  private fun Long.accessibilityCallTimeoutMillis(): Long {
    return coerceAtLeast(1L) + ACCESSIBILITY_CALL_GRACE_TIMEOUT_MILLIS
  }

  suspend fun awaitNotificationPostedAfter(
    matcher: PhoneAutomationNotificationMatcher,
    observedAfterMillis: Long,
    timeoutMillis: Long
  ): PhoneAutomationObservedNotification? {
    return withTimeoutOrNull(timeoutMillis) {
      notificationEvents
        .filterIsInstance<PhoneAutomationNotificationEvent.Posted>()
        .filter { event ->
          event.observedAtMillis >= observedAfterMillis && matcher.matches(event.notification)
        }
        .map { it.notification }
        .first()
    }
  }

  fun isAccessibilityPermissionEnabled(context: Context): Boolean {
    if (isAccessibilityServiceConnected()) {
      return true
    }
    return isAccessibilityPermissionGranted(context)
  }

  fun isAccessibilityPermissionGranted(context: Context): Boolean {
    val accessibilityGloballyEnabled = runCatching {
      Settings.Secure.getInt(
        context.contentResolver,
        Settings.Secure.ACCESSIBILITY_ENABLED
      ) == 1
    }.getOrDefault(false)

    val componentName = ComponentName(context, PhoneAutomationAccessibilityService::class.java)
    val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    val enabledByManager = accessibilityManager
      ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      ?.any { serviceInfo -> isAccessibilityServiceEnabled(serviceInfo, componentName) }
      ?: false

    return hasEnabledAccessibilityPermission(
      accessibilityGloballyEnabled = accessibilityGloballyEnabled,
      componentEnabled = enabledByManager || PhoneAutomationServicePermissions.containsEnabledService(
        currentValue = Settings.Secure.getString(
          context.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ),
        componentName = componentName
      )
    )
  }

  fun isNotificationAccessPermissionEnabled(context: Context): Boolean {
    if (isNotificationListenerConnected()) {
      return true
    }
    return isNotificationAccessPermissionGranted(context)
  }

  fun isNotificationAccessPermissionGranted(context: Context): Boolean {
    val componentName = ComponentName(context, PhoneAutomationNotificationListenerService::class.java)
    val enabledPackages = runCatching {
      NotificationManagerCompat.getEnabledListenerPackages(context)
    }.getOrElse { emptySet() }

    return enabledPackages.contains(context.packageName) || PhoneAutomationServicePermissions.containsEnabledService(
      currentValue = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
      ),
      componentName = componentName
    )
  }

  private fun isAccessibilityServiceEnabled(
    serviceInfo: AccessibilityServiceInfo,
    componentName: ComponentName
  ): Boolean {
    val flattened = componentName.flattenToString()
    val flattenedShort = componentName.flattenToShortString()
    if (serviceInfo.id == flattened || serviceInfo.id == flattenedShort) {
      return true
    }
    val resolvedService = serviceInfo.resolveInfo?.serviceInfo ?: return false
    return resolvedService.packageName == componentName.packageName &&
      resolvedService.name == componentName.className
  }

  internal fun hasEnabledAccessibilityPermission(
    accessibilityGloballyEnabled: Boolean,
    componentEnabled: Boolean
  ): Boolean {
    return accessibilityGloballyEnabled && componentEnabled
  }

  internal fun resetForTests() {
    accessibilityService.value = null
    notificationListenerConnected.value = false
    notificationSnapshotReady.value = false
    foregroundPackage.value = null
    touchInteractionActive.value = false
    lastBlackoutWakeAtMillis.value = 0L
    lastExpectedOrchestratorForegroundAtMillis.value = 0L
    blackoutOverlayRequested.value = false
    blackoutOverlaySuppressed.value = false
    remoteScreenBrightnessState.value = null
    nonTouchInputSuppressedUntilUptimeMillis.value = 0L
    activeNotifications.value = emptyMap()
  }

  private const val NON_TOUCH_INPUT_SUPPRESSION_MILLIS = 4_000L
}
