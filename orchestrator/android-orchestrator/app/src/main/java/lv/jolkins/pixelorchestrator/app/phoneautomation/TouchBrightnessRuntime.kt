package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

internal sealed interface TouchBrightnessEvent {
  data class TouchCountChanged(
    val activeTouchCount: Int,
    val observedAtUptimeMillis: Long,
    val snapshot: RootTouchSnapshot? = null
  ) : TouchBrightnessEvent

  data class ScreenInteractiveChanged(
    val interactive: Boolean
  ) : TouchBrightnessEvent

  data class BlackoutWakeRequested(
    val observedAtUptimeMillis: Long
  ) : TouchBrightnessEvent

  data class OverlayAvailabilityChanged(
    val available: Boolean
  ) : TouchBrightnessEvent

  data class TouchSourceSelected(
    val device: RootTouchDevice
  ) : TouchBrightnessEvent

  data class FatalError(
    val detail: String
  ) : TouchBrightnessEvent
}

internal interface TouchBrightnessEventSource {
  val events: Flow<TouchBrightnessEvent>

  fun isInteractive(): Boolean
  fun activeTouchCount(): Int
  fun currentTouchSnapshot(): RootTouchSnapshot?
  fun isOverlayAvailable(): Boolean
  fun selectedTouchSource(): RootTouchDevice?
}

internal interface TouchBrightnessDeviceController {
  suspend fun prepare(): PhoneAutomationPreparationResult
  suspend fun readBrightnessState(): ScreenBrightnessState?
  suspend fun setBrightnessPercent(percent: Int): PhoneAutomationActionResult
  suspend fun restoreBrightnessState(state: ScreenBrightnessState): PhoneAutomationActionResult
}

internal class AndroidTouchBrightnessEventSource(
  private val context: Context,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge,
  private val rootTouchMonitor: RootTouchMonitor = AndroidRootTouchMonitor()
) : TouchBrightnessEventSource {
  private val powerManager = context.getSystemService(PowerManager::class.java)

  override val events: Flow<TouchBrightnessEvent> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          Intent.ACTION_SCREEN_OFF -> {
            rootTouchMonitor.resetActiveTouchCount()
            trySend(
              TouchBrightnessEvent.TouchCountChanged(
                activeTouchCount = 0,
                observedAtUptimeMillis = SystemClock.uptimeMillis(),
                snapshot = rootTouchMonitor.currentSnapshot()
              )
            )
            trySend(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = false))
          }

          Intent.ACTION_SCREEN_ON -> {
            trySend(TouchBrightnessEvent.ScreenInteractiveChanged(interactive = true))
          }
        }
      }
    }

    ContextCompat.registerReceiver(
      context,
      receiver,
      IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
      },
      ContextCompat.RECEIVER_NOT_EXPORTED
    )

    val touchJob = launch {
      rootTouchMonitor.events.collect { event ->
        when (event) {
          is RootTouchEvent.TouchCountChanged -> {
            trySend(
              TouchBrightnessEvent.TouchCountChanged(
                activeTouchCount = event.activeTouchCount,
                observedAtUptimeMillis = event.observedAtUptimeMillis,
                snapshot = event.snapshot
              )
            )
          }

          is RootTouchEvent.SourceSelected -> {
            trySend(TouchBrightnessEvent.TouchSourceSelected(event.device))
          }

          is RootTouchEvent.FatalError -> {
            trySend(TouchBrightnessEvent.FatalError(event.detail))
          }
        }
      }
    }

    val overlayWakeJob = launch {
      bridge.blackoutOverlayEvents.collect { event ->
        when (event) {
          is PhoneAutomationBlackoutOverlayEvent.WakeRequested -> {
            trySend(
              TouchBrightnessEvent.BlackoutWakeRequested(
                observedAtUptimeMillis = event.observedAtUptimeMillis
              )
            )
          }
        }
      }
    }

    val accessibilityJob = launch {
      bridge.accessibilityAvailability.collect { available ->
        trySend(TouchBrightnessEvent.OverlayAvailabilityChanged(available))
      }
    }

    awaitClose {
      context.unregisterReceiver(receiver)
      touchJob.cancel()
      overlayWakeJob.cancel()
      accessibilityJob.cancel()
    }
  }

  override fun isInteractive(): Boolean = powerManager?.isInteractive == true

  override fun activeTouchCount(): Int = rootTouchMonitor.activeTouchCount()

  override fun currentTouchSnapshot(): RootTouchSnapshot = rootTouchMonitor.currentSnapshot()

  override fun isOverlayAvailable(): Boolean = bridge.isBlackoutOverlayAvailable()

  override fun selectedTouchSource(): RootTouchDevice? = rootTouchMonitor.selectedDevice()
}

internal class AndroidTouchBrightnessDeviceController(
  private val context: Context,
  private val rootExecutor: RootExecutor,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge
) : TouchBrightnessDeviceController {
  private val accessibilityRecovery: PhoneAutomationAccessibilityRecovery by lazy {
    PhoneAutomationAccessibilityRecovery(
      environment = AndroidPhoneAutomationAccessibilityRecoveryEnvironment(
        context = context,
        rootExecutor = rootExecutor,
        bridge = bridge
      )
    )
  }

  override suspend fun prepare(): PhoneAutomationPreparationResult {
    attemptAccessibilityPermissionRepairIfNeeded()

    val reliability = PhoneAutomationBackgroundReliabilitySupport.read(context)
    val missingRequirements = mutableListOf<String>()
    if (!rootExecutor.isRootAvailable()) {
      missingRequirements += "Root access is unavailable"
    }
    if (!bridge.isAccessibilityPermissionEnabled(context)) {
      missingRequirements += "Accessibility access is not enabled"
    }
    missingRequirements += PhoneAutomationBackgroundReliabilitySupport.missingTouchBrightnessRequirements(reliability)
    if (missingRequirements.isNotEmpty()) {
      return PhoneAutomationPreparationResult(
        ready = false,
        detail = missingRequirements.joinToString(separator = "; ")
      )
    }

    var accessibilityConnected = bridge.awaitAccessibilityConnection(SERVICE_CONNECTION_TIMEOUT_MILLIS)
    if (!accessibilityConnected && rootExecutor.isRootAvailable()) {
      val recovery = attemptAccessibilityRebindIfNeeded()
      if (recovery.recovered) {
        accessibilityConnected = true
      }
    }
    return if (!accessibilityConnected) {
      PhoneAutomationPreparationResult(
        ready = false,
        detail = "Accessibility service is not connected"
      )
    } else {
      PhoneAutomationPreparationResult(
        ready = true,
        detail = "Ready for blackout touch brightness"
      )
    }
  }

  private suspend fun attemptAccessibilityPermissionRepairIfNeeded() {
    if (bridge.isAccessibilityPermissionGranted(context)) {
      return
    }
    if (!repairAccessibilityPermission()) {
      return
    }
    delay(PERMISSION_REPAIR_SETTLE_DELAY_MILLIS)
    Log.i(TAG, "touch_brightness_permission_auto_repair repaired=accessibility")
  }

  private suspend fun repairAccessibilityPermission(): Boolean {
    val recovery = accessibilityRecovery.repairPermissionIfNeeded()
    if (recovery.recovered) {
      return true
    }
    Log.w(
      TAG,
      "touch_brightness_permission_auto_repair_failed permission=accessibility stage=${recovery.stage.name.lowercase()}"
    )
    return false
  }

  private suspend fun attemptAccessibilityRebindIfNeeded(): PhoneAutomationAccessibilityRecoveryResult {
    val recovery = accessibilityRecovery.recoverDisconnectedService(
      timeoutMillis = SERVICE_CONNECTION_TIMEOUT_MILLIS
    )
    if (recovery.recovered) {
      Log.i(
        TAG,
        "touch_brightness_permission_auto_repair repaired=accessibility_rebind stage=${recovery.stage.name.lowercase()}"
      )
    }
    return recovery
  }

  override suspend fun readBrightnessState(): ScreenBrightnessState? {
    val rootState = runCatching {
      val result = rootExecutor.runScript(ScreenBrightnessControl.buildReadStateScript())
      if (result.ok) {
        ScreenBrightnessControl.parseState(result.stdout)
      } else {
        null
      }
    }.getOrNull()
    if (rootState != null) {
      return rootState
    }

    return runCatching {
      ScreenBrightnessState(
        mode = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE),
        value = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
      )
    }.getOrNull()
  }

  override suspend fun setBrightnessPercent(percent: Int): PhoneAutomationActionResult {
    val targetPercent = percent.coerceIn(0, 100)
    val result = rootExecutor.runScript(ScreenBrightnessControl.buildSetPercentScript(targetPercent))
    if (!result.ok) {
      return PhoneAutomationActionResult(
        success = false,
        detail = result.stderr.ifBlank { result.stdout.ifBlank { "brightness command failed" } }
      )
    }

    delay(BRIGHTNESS_SETTLE_DELAY_MILLIS)
    val after = readBrightnessState()
    return when {
      after == null -> PhoneAutomationActionResult(true, "Brightness set to $targetPercent%")
      !after.matchesTargetLenient(targetPercent) -> PhoneAutomationActionResult(
        success = false,
        detail = "Brightness verification failed: mode=${after.mode} value=${after.value} display=${after.displayPercentage}"
      )
      else -> PhoneAutomationActionResult(true, "Brightness set to $targetPercent%")
    }
  }

  override suspend fun restoreBrightnessState(state: ScreenBrightnessState): PhoneAutomationActionResult {
    val result = rootExecutor.runScript(ScreenBrightnessControl.buildRestoreScript(state))
    if (!result.ok) {
      return PhoneAutomationActionResult(
        success = false,
        detail = result.stderr.ifBlank { result.stdout.ifBlank { "brightness restore failed" } }
      )
    }

    delay(BRIGHTNESS_SETTLE_DELAY_MILLIS)
    val after = readBrightnessState()
    return when {
      after == null -> PhoneAutomationActionResult(true, "Brightness restored")
      !after.matchesRestoredStateLenient(state) -> PhoneAutomationActionResult(
        success = false,
        detail = "Brightness restore verification failed: mode=${after.mode} value=${after.value} display=${after.displayPercentage}"
      )
      else -> PhoneAutomationActionResult(true, "Brightness restored")
    }
  }

  private fun ScreenBrightnessState.matchesTargetLenient(targetPercent: Int): Boolean {
    if (mode != null && mode != MANUAL_BRIGHTNESS_MODE) {
      return false
    }
    val targetSystemValue = ScreenBrightnessControl.legacySystemValue(targetPercent)
    if (value != null && value == targetSystemValue) {
      return true
    }
    if (displayPercentage != null) {
      return abs(displayPercentage - targetPercent.toFloat()) <= DISPLAY_PERCENT_TOLERANCE
    }
    return value == null
  }

  private fun ScreenBrightnessState.matchesRestoredStateLenient(expected: ScreenBrightnessState): Boolean {
    if (expected.mode != null && mode != null && mode != expected.mode) {
      return false
    }
    return when (expected.mode) {
      AUTOMATIC_BRIGHTNESS_MODE -> true
      MANUAL_BRIGHTNESS_MODE, null -> {
        val expectedValue = expected.value ?: return true
        when {
          value != null && value == expectedValue -> true
          displayPercentage != null -> {
            abs(displayPercentage - ScreenBrightnessControl.percentFromSystemValue(expectedValue).toFloat()) <= DISPLAY_PERCENT_TOLERANCE
          }
          else -> true
        }
      }

      else -> true
    }
  }

  companion object {
    private const val SERVICE_CONNECTION_TIMEOUT_MILLIS = 15_000L
    private const val BRIGHTNESS_SETTLE_DELAY_MILLIS = 250L
    private const val PERMISSION_REPAIR_SETTLE_DELAY_MILLIS = 1_000L
    private const val DISPLAY_PERCENT_TOLERANCE = 0.5f
    private const val MANUAL_BRIGHTNESS_MODE = 0
    private const val AUTOMATIC_BRIGHTNESS_MODE = 1
    private const val TAG = "TouchBrightnessDevice"
  }
}

private enum class InternalTouchBrightnessMode {
  STARTING,
  BRIGHT_AWAITING_TOUCH_CONFIRMATION,
  BRIGHT_TOUCH_ACTIVE,
  BLACKOUT_IDLE,
  WAITING_FOR_BLACKOUT_OVERLAY,
  SUSPENDED_SCREEN_OFF
}

internal class TouchBrightnessRuntime(
  context: Context,
  private val scope: CoroutineScope,
  private val settingsStore: PhoneAutomationSettingsStore,
  rootExecutor: RootExecutor,
  private val onSnapshotChanged: (PhoneAutomationSettingsSnapshot) -> Unit,
  private val deviceController: TouchBrightnessDeviceController = AndroidTouchBrightnessDeviceController(
    context = context.applicationContext,
    rootExecutor = rootExecutor
  ),
  private val overlayController: BlackoutOverlayController = BridgeBlackoutOverlayController(),
  private val eventSourceFactory: () -> TouchBrightnessEventSource = {
    AndroidTouchBrightnessEventSource(context.applicationContext)
  },
  private val uptimeClock: () -> Long = SystemClock::uptimeMillis
) : PhoneAutomationRuntimeController {
  private var sessionJob: Job? = null
  private var stopJob: Job? = null

  override fun start() {
    stopJob?.cancel()
    stopJob = null
    if (sessionJob?.isActive == true) {
      return
    }
    val newSessionJob = scope.launch {
      try {
        runSupervisorLoop()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } finally {
        onSnapshotChanged(settingsStore.load())
      }
    }
    sessionJob = newSessionJob
    newSessionJob.invokeOnCompletion {
      if (sessionJob === newSessionJob) {
        sessionJob = null
      }
    }
  }

  override fun stop(reason: String) {
    sessionJob?.cancel()
    sessionJob = null
    stopJob?.cancel()

    val snapshot = settingsStore.load()
    val shouldRestoreBrightness = !snapshot.touchBrightnessEnabled || reason == SERVICE_DESTROYED_REASON
    val restoreState = snapshot.touchBrightnessRestoreState()
    val newStopJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
      overlayController.hide()

      if (shouldRestoreBrightness && restoreState != null) {
        val restore = deviceController.restoreBrightnessState(restoreState)
        if (restore.success) {
          settingsStore.clearTouchBrightnessRestoreState()
        } else {
          if (!snapshot.touchBrightnessEnabled) {
            settingsStore.updateTouchBrightnessDebugDetail("")
          }
          settingsStore.updateTouchBrightnessState(
            TouchBrightnessRuntimeState.ERROR,
            "Brightness restore failed: ${restore.detail}"
          )
          onSnapshotChanged(settingsStore.load())
          return@launch
        }
      } else if (shouldRestoreBrightness) {
        settingsStore.clearTouchBrightnessRestoreState()
      }
      if (!snapshot.touchBrightnessEnabled) {
        settingsStore.updateTouchBrightnessDebugDetail("")
      }

      val nextState = when {
        !snapshot.touchBrightnessEnabled -> TouchBrightnessRuntimeState.DISABLED
        else -> TouchBrightnessRuntimeState.STOPPED
      }
      val nextDetail = when (nextState) {
        TouchBrightnessRuntimeState.STOPPED -> "Stopped: $reason"
        else -> nextState.defaultDetail
      }
      settingsStore.updateTouchBrightnessState(nextState, nextDetail)
      onSnapshotChanged(settingsStore.load())
    }
    stopJob = newStopJob
    newStopJob.invokeOnCompletion {
      if (stopJob === newStopJob) {
        stopJob = null
      }
    }
  }

  private suspend fun runSupervisorLoop() = coroutineScope {
    var retryDelayMillis = SESSION_RETRY_INITIAL_DELAY_MILLIS

    while (true) {
      try {
        runSession()
        return@coroutineScope
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        runCatching {
          Log.e(TAG, "touch brightness session failed", error)
        }

        if (!settingsStore.load().touchBrightnessEnabled) {
          return@coroutineScope
        }

        val failureDetail = error.message ?: error::class.java.simpleName
        settingsStore.updateTouchBrightnessState(
          TouchBrightnessRuntimeState.ERROR,
          buildRetryDetail(failureDetail, retryDelayMillis)
        )
        onSnapshotChanged(settingsStore.load())

        delay(retryDelayMillis)
        retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(SESSION_RETRY_MAX_DELAY_MILLIS)
      }
    }
  }

  private suspend fun runSession(): Unit = coroutineScope {
    var interactive = false
    var activeTouchCount = 0
    var overlayAvailable = false
    var currentSource: RootTouchDevice? = null
    var currentTouchSnapshot = RootTouchSnapshot()
    var visibleBrightnessState: ScreenBrightnessState? = null
    var internalMode = InternalTouchBrightnessMode.STARTING
    var lastWakeRequestedAtUptimeMillis: Long? = null
    var wakeConfirmDeadlineMillis: Long? = null
    var idleDeadlineMillis: Long? = null

    var idleJob: Job? = null
    var overlayRetryJob: Job? = null
    var wakeConfirmJob: Job? = null

    fun sourceSuffix(): String {
      val source = currentSource ?: return ""
      return " (source: ${source.displayLabel()})"
    }

    fun visibleBrightnessLabel(): String {
      val state = visibleBrightnessState ?: return "saved brightness"
      if (state.mode == AUTOMATIC_BRIGHTNESS_MODE_VALUE) {
        return "saved automatic brightness"
      }
      val value = state.value ?: return "saved brightness"
      return "${ScreenBrightnessControl.percentFromSystemValue(value)}%"
    }

    fun currentTouchSourceLabel(): String {
      return (currentTouchSnapshot.selectedDevice ?: currentSource)?.displayLabel() ?: "unknown"
    }

    fun lastTouchAgeSummary(): String {
      val lastEventAt = currentTouchSnapshot.lastEventUptimeMillis
      if (lastEventAt <= 0L) {
        return "n/a"
      }
      val ageMillis = (uptimeClock() - lastEventAt).coerceAtLeast(0L)
      return "${ageMillis}ms"
    }

    fun blackoutTimerSummary(): String {
      val deadline = idleDeadlineMillis ?: return "none"
      val remainingMillis = (deadline - uptimeClock()).coerceAtLeast(0L)
      return "pending(${remainingMillis}ms)"
    }

    fun buildDebugDetail(): String {
      return buildString {
        append("touches=")
        append(activeTouchCount)
        append(" source=")
        append(currentTouchSourceLabel())
        append(" last_raw=")
        append(lastTouchAgeSummary())
        append(" timer=")
        append(blackoutTimerSummary())
        append(" raw(btn=")
        append(currentTouchSnapshot.btnTouchActive)
        append(",slots=")
        append(currentTouchSnapshot.activeSlotCount)
        append(")")
      }
    }

    fun publishDebugState() {
      settingsStore.updateTouchBrightnessDebugDetail(buildDebugDetail())
    }

    fun logTouchState(reason: String) {
      runCatching {
        Log.d(TAG, "touch_brightness_debug reason=$reason ${buildDebugDetail()}")
      }
    }

    suspend fun publishCurrentState() {
      val (state, detail) = when (internalMode) {
        InternalTouchBrightnessMode.STARTING -> {
          TouchBrightnessRuntimeState.STARTING to "Checking touch brightness setup${sourceSuffix()}"
        }

        InternalTouchBrightnessMode.BRIGHT_AWAITING_TOUCH_CONFIRMATION -> {
          val detail = if (wakeConfirmDeadlineMillis != null) {
            "Screen is visible at ${visibleBrightnessLabel()} while waiting for touch confirmation${sourceSuffix()}"
          } else {
            "Screen is visible at ${visibleBrightnessLabel()} after wake; returning to blackout if untouched${sourceSuffix()}"
          }
          TouchBrightnessRuntimeState.BRIGHT to detail
        }

        InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE -> {
          TouchBrightnessRuntimeState.BRIGHT to "Screen is visible at ${visibleBrightnessLabel()} because touch is active${sourceSuffix()}"
        }

        InternalTouchBrightnessMode.BLACKOUT_IDLE -> {
          TouchBrightnessRuntimeState.BLACKOUT_IDLE to "Blackout waiting for touch${sourceSuffix()}"
        }

        InternalTouchBrightnessMode.WAITING_FOR_BLACKOUT_OVERLAY -> {
          TouchBrightnessRuntimeState.BLACKOUT_IDLE to "Blackout fallback active while waiting for the blackout overlay${sourceSuffix()}"
        }

        InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF -> {
          TouchBrightnessRuntimeState.SUSPENDED_SCREEN_OFF to "Suspended while the screen is off${sourceSuffix()}"
        }
      }
      settingsStore.updateTouchBrightnessState(state, detail)
      publishDebugState()
    }

    fun cancelIdleJob() {
      val hadPendingTimer = idleDeadlineMillis != null
      idleJob?.cancel()
      idleJob = null
      idleDeadlineMillis = null
      if (hadPendingTimer) {
        logTouchState("blackout_timer_cancelled")
      }
    }

    fun cancelOverlayRetryJob() {
      overlayRetryJob?.cancel()
      overlayRetryJob = null
    }

    fun cancelWakeConfirmJob() {
      wakeConfirmJob?.cancel()
      wakeConfirmJob = null
    }

    fun scheduleWakeConfirmationExpiry(wakeRequestedAtUptimeMillis: Long) {
      cancelWakeConfirmJob()
      val deadline = wakeRequestedAtUptimeMillis + WAKE_CONFIRM_WINDOW_MILLIS
      wakeConfirmDeadlineMillis = deadline
      wakeConfirmJob = launch {
        delay(maxOf(0L, deadline - uptimeClock()))
        if (
          internalMode == InternalTouchBrightnessMode.BRIGHT_AWAITING_TOUCH_CONFIRMATION &&
          wakeConfirmDeadlineMillis == deadline
        ) {
          wakeConfirmDeadlineMillis = null
          publishCurrentState()
          logTouchState("wake_confirmation_expired")
        }
      }
    }

    fun ensureOverlayRetryJob() {
      if (overlayRetryJob?.isActive == true) {
        return
      }
      overlayRetryJob = launch {
        while (true) {
          delay(OVERLAY_RETRY_INTERVAL_MILLIS)
          if (!interactive || activeTouchCount > 0) {
            overlayRetryJob = null
            return@launch
          }
          if (!overlayAvailable || !overlayController.isAvailable()) {
            continue
          }
          overlayRetryJob = null
          val overlayShown = overlayController.show()
          if (!overlayShown.success) {
            throw IllegalStateException(overlayShown.detail)
          }
          internalMode = InternalTouchBrightnessMode.BLACKOUT_IDLE
          publishCurrentState()
          return@launch
        }
      }
    }

    fun scheduleBlackoutFrom(observedAtUptimeMillis: Long) {
      cancelIdleJob()
      val deadline = observedAtUptimeMillis + IDLE_BLACKOUT_DELAY_MILLIS
      idleDeadlineMillis = deadline
      logTouchState("blackout_timer_started")
      publishDebugState()
      idleJob = launch {
        delay(maxOf(0L, deadline - uptimeClock()))
        idleJob = null
        idleDeadlineMillis = null
        if (!interactive || activeTouchCount > 0) {
          publishDebugState()
          return@launch
        }
        if (!overlayAvailable || !overlayController.isAvailable()) {
          internalMode = InternalTouchBrightnessMode.WAITING_FOR_BLACKOUT_OVERLAY
          val brightness = deviceController.setBrightnessPercent(DIM_PERCENT)
          if (!brightness.success) {
            throw IllegalStateException("Could not dim the screen while waiting for the blackout overlay: ${brightness.detail}")
          }
          publishCurrentState()
          ensureOverlayRetryJob()
        } else {
          val overlayShown = overlayController.show()
          if (!overlayShown.success) {
            throw IllegalStateException(overlayShown.detail)
          }
          val brightness = deviceController.setBrightnessPercent(DIM_PERCENT)
          if (!brightness.success) {
            throw IllegalStateException("Could not dim the screen: ${brightness.detail}")
          }
          internalMode = InternalTouchBrightnessMode.BLACKOUT_IDLE
          publishCurrentState()
        }
        logTouchState("blackout_timer_fired")
      }
    }

    suspend fun enterBrightAwaitingTouchConfirmation(wakeRequestedAtUptimeMillis: Long) {
      cancelIdleJob()
      cancelOverlayRetryJob()
      lastWakeRequestedAtUptimeMillis = wakeRequestedAtUptimeMillis
      val overlayHidden = overlayController.hide()
      if (!overlayHidden.success) {
        throw IllegalStateException(overlayHidden.detail)
      }
      val brightness = applyVisibleBrightnessState(visibleBrightnessState)
      if (!brightness.success) {
        throw IllegalStateException("Could not set bright mode: ${brightness.detail}")
      }
      internalMode = InternalTouchBrightnessMode.BRIGHT_AWAITING_TOUCH_CONFIRMATION
      scheduleWakeConfirmationExpiry(wakeRequestedAtUptimeMillis)
      scheduleBlackoutFrom(wakeRequestedAtUptimeMillis)
      publishCurrentState()
      logTouchState("wake_awaiting_touch_confirmation")
    }

    suspend fun enterBrightTouchActive() {
      cancelIdleJob()
      cancelOverlayRetryJob()
      cancelWakeConfirmJob()
      wakeConfirmDeadlineMillis = null
      lastWakeRequestedAtUptimeMillis = null
      if (internalMode != InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE) {
        val overlayHidden = overlayController.hide()
        if (!overlayHidden.success) {
          throw IllegalStateException(overlayHidden.detail)
        }
        val brightness = applyVisibleBrightnessState(visibleBrightnessState)
        if (!brightness.success) {
          throw IllegalStateException("Could not set bright mode: ${brightness.detail}")
        }
      }
      internalMode = InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE
      publishCurrentState()
      logTouchState("touch_active")
    }

    suspend fun enterBlackoutIdle(forceHardwareDim: Boolean = true) {
      cancelOverlayRetryJob()
      cancelWakeConfirmJob()
      wakeConfirmDeadlineMillis = null
      lastWakeRequestedAtUptimeMillis = null
      if (!overlayAvailable || !overlayController.isAvailable()) {
        if (forceHardwareDim) {
          val brightness = deviceController.setBrightnessPercent(DIM_PERCENT)
          if (!brightness.success) {
            throw IllegalStateException("Could not dim the screen while waiting for the blackout overlay: ${brightness.detail}")
          }
        }
        overlayController.hide()
        internalMode = InternalTouchBrightnessMode.WAITING_FOR_BLACKOUT_OVERLAY
        publishCurrentState()
        ensureOverlayRetryJob()
        return
      }
      cancelIdleJob()
      if (internalMode != InternalTouchBrightnessMode.BLACKOUT_IDLE) {
        val overlayShown = overlayController.show()
        if (!overlayShown.success) {
          throw IllegalStateException(overlayShown.detail)
        }
        if (forceHardwareDim) {
          val brightness = deviceController.setBrightnessPercent(DIM_PERCENT)
          if (!brightness.success) {
            throw IllegalStateException("Could not dim the screen: ${brightness.detail}")
          }
        }
      }
      internalMode = InternalTouchBrightnessMode.BLACKOUT_IDLE
      publishCurrentState()
      logTouchState("blackout_idle")
    }

    suspend fun enterWaitingForOverlay() {
      cancelIdleJob()
      cancelWakeConfirmJob()
      wakeConfirmDeadlineMillis = null
      lastWakeRequestedAtUptimeMillis = null
      if (internalMode != InternalTouchBrightnessMode.WAITING_FOR_BLACKOUT_OVERLAY) {
        overlayController.hide()
        val brightness = deviceController.setBrightnessPercent(DIM_PERCENT)
        if (!brightness.success) {
          throw IllegalStateException("Could not dim the screen while waiting for the blackout overlay: ${brightness.detail}")
        }
      }
      internalMode = InternalTouchBrightnessMode.WAITING_FOR_BLACKOUT_OVERLAY
      publishCurrentState()
      logTouchState("waiting_for_overlay")
      ensureOverlayRetryJob()
    }

    suspend fun enterSuspendedScreenOff() {
      cancelIdleJob()
      cancelOverlayRetryJob()
      cancelWakeConfirmJob()
      wakeConfirmDeadlineMillis = null
      lastWakeRequestedAtUptimeMillis = null
      overlayController.hide()
      internalMode = InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF
      publishCurrentState()
      logTouchState("screen_off_suspended")
    }

    internalMode = InternalTouchBrightnessMode.STARTING
    publishCurrentState()

    val preparation = deviceController.prepare()
    if (!preparation.ready) {
      throw IllegalStateException(preparation.detail)
    }
    if (!captureRestoreStateIfNeeded()) {
      throw IllegalStateException("Could not read the current brightness state")
    }
    visibleBrightnessState = settingsStore.load().touchBrightnessRestoreState()

    val eventSource = eventSourceFactory()
    interactive = eventSource.isInteractive()
    activeTouchCount = eventSource.activeTouchCount()
    overlayAvailable = eventSource.isOverlayAvailable()
    currentSource = eventSource.selectedTouchSource()
    currentTouchSnapshot = eventSource.currentTouchSnapshot()
      ?.copy(selectedDevice = eventSource.currentTouchSnapshot()?.selectedDevice ?: currentSource)
      ?: RootTouchSnapshot(
        activeTouchCount = activeTouchCount,
        selectedDevice = currentSource
      )

    if (!interactive) {
      enterSuspendedScreenOff()
    } else if (activeTouchCount > 0) {
      enterBrightTouchActive()
    } else if (!overlayAvailable || !overlayController.isAvailable()) {
      enterWaitingForOverlay()
    } else {
      enterBlackoutIdle()
    }

    val eventJob = launch {
      eventSource.events.collect { event ->
        when (event) {
          is TouchBrightnessEvent.TouchCountChanged -> {
            val previousTouchCount = activeTouchCount
            val wakeRequestedAt = lastWakeRequestedAtUptimeMillis
            currentTouchSnapshot = event.snapshot ?: currentTouchSnapshot.copy(
              activeTouchCount = event.activeTouchCount,
              selectedDevice = currentSource,
              lastEventUptimeMillis = event.observedAtUptimeMillis
            )
            currentTouchSnapshot = currentTouchSnapshot.copy(
              activeTouchCount = event.activeTouchCount,
              selectedDevice = currentTouchSnapshot.selectedDevice ?: currentSource,
              lastEventUptimeMillis = event.observedAtUptimeMillis
            )
            currentTouchSnapshot.selectedDevice?.let {
              currentSource = it
            }
            if (
              wakeRequestedAt != null &&
              internalMode == InternalTouchBrightnessMode.BRIGHT_AWAITING_TOUCH_CONFIRMATION &&
              event.observedAtUptimeMillis < wakeRequestedAt
            ) {
              publishDebugState()
              return@collect
            }
            activeTouchCount = event.activeTouchCount
            logTouchState("touch_count_changed")
            when {
              !interactive -> Unit
              activeTouchCount > 0 -> {
                if (previousTouchCount == 0) {
                  logTouchState("wake_promoted_to_active_touch")
                }
                enterBrightTouchActive()
              }

              previousTouchCount > 0 && activeTouchCount == 0 -> {
                scheduleBlackoutFrom(event.observedAtUptimeMillis)
              }

              else -> {
                publishDebugState()
              }
            }
          }

          is TouchBrightnessEvent.ScreenInteractiveChanged -> {
            interactive = event.interactive
            if (!interactive) {
              activeTouchCount = 0
              currentTouchSnapshot = currentTouchSnapshot.copy(activeTouchCount = 0)
              enterSuspendedScreenOff()
            } else if (activeTouchCount > 0) {
              enterBrightTouchActive()
            } else if (!overlayAvailable || !overlayController.isAvailable()) {
              enterWaitingForOverlay()
            } else {
              enterBlackoutIdle()
            }
          }

          is TouchBrightnessEvent.BlackoutWakeRequested -> {
            if (interactive) {
              val liveTouchSnapshot = eventSource.currentTouchSnapshot() ?: currentTouchSnapshot
              currentTouchSnapshot = liveTouchSnapshot.copy(
                selectedDevice = liveTouchSnapshot.selectedDevice ?: currentSource
              )
              currentTouchSnapshot.selectedDevice?.let {
                currentSource = it
              }
              val liveTouchCount = maxOf(
                activeTouchCount,
                eventSource.activeTouchCount(),
                if (currentTouchSnapshot.isRawTouchActive()) {
                  maxOf(1, currentTouchSnapshot.activeTouchCount)
                } else {
                  currentTouchSnapshot.activeTouchCount
                }
              )
              activeTouchCount = liveTouchCount
              currentTouchSnapshot = currentTouchSnapshot.copy(activeTouchCount = liveTouchCount)
              if (liveTouchCount > 0) {
                enterBrightTouchActive()
              } else {
                enterBrightAwaitingTouchConfirmation(event.observedAtUptimeMillis)
              }
            }
          }

          is TouchBrightnessEvent.OverlayAvailabilityChanged -> {
            overlayAvailable = event.available
            when {
              !interactive -> Unit
              activeTouchCount > 0 -> {
                enterBrightTouchActive()
              }

              overlayAvailable -> {
                enterBlackoutIdle(forceHardwareDim = false)
              }

              else -> {
                enterWaitingForOverlay()
              }
            }
          }

          is TouchBrightnessEvent.TouchSourceSelected -> {
            currentSource = event.device
            currentTouchSnapshot = currentTouchSnapshot.copy(selectedDevice = event.device)
            logTouchState("touch_source_selected")
            publishCurrentState()
          }

          is TouchBrightnessEvent.FatalError -> {
            throw IllegalStateException(event.detail)
          }
        }
      }
    }

    try {
      awaitCancellation()
    } finally {
      cancelIdleJob()
      cancelOverlayRetryJob()
      cancelWakeConfirmJob()
      eventJob.cancelAndJoin()
    }
  }

  private suspend fun captureRestoreStateIfNeeded(): Boolean {
    val snapshot = settingsStore.load()
    if (snapshot.touchBrightnessRestoreState() != null) {
      return true
    }

    val currentState = deviceController.readBrightnessState()
    if (currentState == null || currentState.mode == null || currentState.value == null) {
      return false
    }

    settingsStore.saveTouchBrightnessRestoreState(
      mode = currentState.mode,
      value = currentState.value
    )
    return true
  }

  private suspend fun applyVisibleBrightnessState(state: ScreenBrightnessState?): PhoneAutomationActionResult {
    val targetState = state ?: settingsStore.load().touchBrightnessRestoreState()
    return if (targetState != null) {
      deviceController.restoreBrightnessState(targetState)
    } else {
      deviceController.setBrightnessPercent(BRIGHT_PERCENT)
    }
  }

  private fun PhoneAutomationSettingsSnapshot.touchBrightnessRestoreState(): ScreenBrightnessState? {
    if (touchBrightnessRestoreMode == null || touchBrightnessRestoreValue == null) {
      return null
    }
    return ScreenBrightnessState(
      mode = touchBrightnessRestoreMode,
      value = touchBrightnessRestoreValue
    )
  }

  companion object {
    internal const val BRIGHT_PERCENT = 100
    internal const val DIM_PERCENT = 0
    internal const val IDLE_BLACKOUT_DELAY_MILLIS = 3_000L
    internal const val WAKE_CONFIRM_WINDOW_MILLIS = 500L
    internal const val OVERLAY_RETRY_INTERVAL_MILLIS = 1_000L
    internal const val SESSION_RETRY_INITIAL_DELAY_MILLIS = 1_000L
    internal const val SESSION_RETRY_MAX_DELAY_MILLIS = 15_000L
    private const val AUTOMATIC_BRIGHTNESS_MODE_VALUE = 1
    private const val SERVICE_DESTROYED_REASON = "service_destroyed"
    private const val TAG = "TouchBrightness"
  }

  private fun buildRetryDetail(detail: String, retryDelayMillis: Long): String {
    val normalizedDetail = detail.ifBlank { "Unknown touch brightness failure" }
    val retrySummary = "retrying in ${retryDelayMillis / 1_000}s"
    return when {
      normalizedDetail.contains("touchscreen", ignoreCase = true) ||
        normalizedDetail.contains("touch monitor", ignoreCase = true) ||
        normalizedDetail.contains("touch source", ignoreCase = true) -> {
        "Touch source unavailable; $retrySummary: $normalizedDetail"
      }

      normalizedDetail.contains("accessibility", ignoreCase = true) ||
        normalizedDetail.contains("root access", ignoreCase = true) -> {
        "$normalizedDetail; $retrySummary"
      }

      else -> {
        "Touch brightness error; $retrySummary: $normalizedDetail"
      }
    }
  }
}
