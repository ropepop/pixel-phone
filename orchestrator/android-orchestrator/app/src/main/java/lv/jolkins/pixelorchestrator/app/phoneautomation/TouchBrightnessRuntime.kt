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
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
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
    val observedAtUptimeMillis: Long,
    val activePointerCount: Int = 1,
    val gestureEnded: Boolean = false
  ) : TouchBrightnessEvent

  data class NonTouchInput(
    val reason: String,
    val observedAtUptimeMillis: Long,
    val suppressedUntilUptimeMillis: Long
  ) : TouchBrightnessEvent

  data class PowerButtonPressed(
    val observedAtUptimeMillis: Long,
    val device: RootPowerKeyDevice? = null
  ) : TouchBrightnessEvent

  data class OverlayAvailabilityChanged(
    val available: Boolean
  ) : TouchBrightnessEvent

  data class TouchSourceSelected(
    val device: RootTouchDevice
  ) : TouchBrightnessEvent

  data class PowerSourceSelected(
    val device: RootPowerKeyDevice
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
  fun selectedPowerSource(): RootPowerKeyDevice?
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
  private val rootTouchMonitor: RootTouchMonitor = AndroidRootTouchMonitor(),
  private val rootPowerKeyMonitor: RootPowerKeyMonitor = AndroidRootPowerKeyMonitor()
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

    val powerKeyJob = launch {
      rootPowerKeyMonitor.events.collect { event ->
        when (event) {
          is RootPowerKeyEvent.PowerButtonPressed -> {
            trySend(
              TouchBrightnessEvent.PowerButtonPressed(
                observedAtUptimeMillis = event.observedAtUptimeMillis,
                device = event.device
              )
            )
          }

          is RootPowerKeyEvent.SourceSelected -> {
            trySend(TouchBrightnessEvent.PowerSourceSelected(event.device))
          }

          is RootPowerKeyEvent.FatalError -> {
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
                observedAtUptimeMillis = event.observedAtUptimeMillis,
                activePointerCount = event.activePointerCount,
                gestureEnded = event.gestureEnded
              )
            )
          }
        }
      }
    }

    val nonTouchInputJob = launch {
      bridge.nonTouchInputEvents.collect { event ->
        trySend(
          TouchBrightnessEvent.NonTouchInput(
            reason = event.reason,
            observedAtUptimeMillis = event.observedAtUptimeMillis,
            suppressedUntilUptimeMillis = event.suppressedUntilUptimeMillis
          )
        )
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
      powerKeyJob.cancel()
      overlayWakeJob.cancel()
      nonTouchInputJob.cancel()
      accessibilityJob.cancel()
    }
  }

  override fun isInteractive(): Boolean = powerManager?.isInteractive == true

  override fun activeTouchCount(): Int = rootTouchMonitor.activeTouchCount()

  override fun currentTouchSnapshot(): RootTouchSnapshot = rootTouchMonitor.currentSnapshot()

  override fun isOverlayAvailable(): Boolean = bridge.isBlackoutOverlayAvailable()

  override fun selectedTouchSource(): RootTouchDevice? = rootTouchMonitor.selectedDevice()

  override fun selectedPowerSource(): RootPowerKeyDevice? = rootPowerKeyMonitor.selectedDevice()
}

internal class AndroidTouchBrightnessDeviceController(
  private val context: Context,
  private val rootExecutor: RootExecutor,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge
) : TouchBrightnessDeviceController {
  override suspend fun prepare(): PhoneAutomationPreparationResult {
    val rootAvailable = rootExecutor.isRootAvailable()
    val rootBatteryWhitelist = if (rootAvailable) {
      RootBatteryOptimizationControl.ensureWhitelisted(context.packageName, rootExecutor)
    } else {
      RootBatteryWhitelistResult(
        attempted = false,
        confirmed = false,
        detail = "Root access is unavailable"
      )
    }
    val reliability = PhoneAutomationBackgroundReliabilitySupport.read(context)
    val touchDevices = if (rootAvailable) {
      readTouchDevices()
    } else {
      emptyList()
    }
    val powerKeyDevices = if (rootAvailable) {
      readPowerKeyDevices()
    } else {
      emptyList()
    }
    val panelAvailable = rootAvailable && readPanelAvailable()
    return TouchBrightnessReadiness.evaluate(
      TouchBrightnessReadinessSnapshot(
        rootAvailable = rootAvailable,
        batteryUnrestricted = RootBatteryOptimizationControl.batteryUnrestricted(
          androidBatteryUnrestricted = reliability.batteryUnrestricted,
          rootWhitelistConfirmed = rootBatteryWhitelist.confirmed
        ),
        touchDevices = touchDevices,
        powerKeyDevices = powerKeyDevices,
        panelAvailable = panelAvailable
      )
    )
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
    val panelOnly = targetPercent == PANEL_SLEEP_TARGET_PERCENT
    val before = readBrightnessStateForVerification(panelOnly)
    if (
      targetPercent != PANEL_SLEEP_TARGET_PERCENT &&
      before != null &&
      before.matchesTargetLenient(targetPercent, panelOnly = panelOnly)
    ) {
      return PhoneAutomationActionResult(true, "Brightness already at $targetPercent%")
    }
    val panelAlreadySleeping = panelOnly && before?.matchesTargetLenient(targetPercent, panelOnly = true) == true
    val script = if (targetPercent == PANEL_SLEEP_TARGET_PERCENT && panelAlreadySleeping) {
      ScreenBrightnessControl.buildSetPanelPercentScript(
        percent = targetPercent,
        holdMillis = PANEL_SLEEP_HOLD_MILLIS,
        holdIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
      )
    } else if (targetPercent == PANEL_SLEEP_TARGET_PERCENT) {
      """
        ${ScreenBrightnessControl.buildSetPanelPercentScript(
          percent = targetPercent,
          holdMillis = 0L,
          holdIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
        )}
        settings put system screen_brightness_mode 0
        ${ScreenBrightnessControl.buildSetPanelPercentScript(
          percent = targetPercent,
          holdMillis = 0L,
          holdIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
        )}
        if ! cmd display set-brightness 0 --unit percentage >/dev/null 2>&1; then
          settings put system screen_brightness 0
        fi
        settings put system screen_brightness 0
        ${ScreenBrightnessControl.buildSetPanelPercentScript(
          percent = targetPercent,
          holdMillis = PANEL_SLEEP_HOLD_MILLIS,
          holdIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
        )}
      """.trimIndent()
    } else {
      ScreenBrightnessControl.buildSetPercentScript(
        percent = targetPercent,
        panelHoldMillis = VISIBLE_HOLD_MILLIS,
        panelHoldIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
      )
    }
    return runBrightnessCommandUntilVerified(
      scriptFactory = { script },
      panelOnly = panelOnly,
      attempts = if (panelOnly) 1 else VISIBLE_RESTORE_ATTEMPTS,
      commandFailureDetail = "brightness command failed",
      verificationFailurePrefix = "Brightness verification failed",
      successDetail = "Brightness set to $targetPercent%",
      matches = { it.matchesTargetLenient(targetPercent, panelOnly = panelOnly) }
    )
  }

  override suspend fun restoreBrightnessState(state: ScreenBrightnessState): PhoneAutomationActionResult {
    val panelOnly = false
    val restoreState = state.withRemotePanelFallback()
    val before = readBrightnessStateForVerification(panelOnly)
    if (before != null && before.matchesRestoredStateLenient(restoreState, panelOnly = panelOnly)) {
      return PhoneAutomationActionResult(true, "Brightness already restored")
    }
    val fallbackPanelScript = if (restoreState.hasVisiblePanelBrightnessData()) {
      ""
    } else {
      ScreenBrightnessControl.buildSetPanelPercentScript(
        percent = restoreState.visiblePanelFallbackPercent(),
        holdMillis = VISIBLE_HOLD_MILLIS,
        holdIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
      )
    }
    return runBrightnessCommandUntilVerified(
      scriptFactory = {
        """
          ${ScreenBrightnessControl.buildRestoreScript(
            state = restoreState,
            panelHoldMillis = VISIBLE_HOLD_MILLIS,
            panelHoldIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
          )}
          $fallbackPanelScript
        """.trimIndent()
      },
      panelOnly = panelOnly,
      attempts = VISIBLE_RESTORE_ATTEMPTS,
      commandFailureDetail = "brightness restore failed",
      verificationFailurePrefix = "Brightness restore verification failed",
      successDetail = "Brightness restored",
      matches = { it.matchesRestoredStateLenient(restoreState, panelOnly = panelOnly) }
    )
  }

  private suspend fun runBrightnessCommandUntilVerified(
    scriptFactory: () -> String,
    panelOnly: Boolean,
    attempts: Int,
    commandFailureDetail: String,
    verificationFailurePrefix: String,
    successDetail: String,
    matches: (ScreenBrightnessState) -> Boolean
  ): PhoneAutomationActionResult {
    val totalAttempts = attempts.coerceAtLeast(1)
    var lastState: ScreenBrightnessState? = null
    repeat(totalAttempts) { attemptIndex ->
      val result = rootExecutor.runScript(scriptFactory())
      if (!result.ok) {
        return PhoneAutomationActionResult(
          success = false,
          detail = result.stderr.ifBlank { result.stdout.ifBlank { commandFailureDetail } }
        )
      }

      delay(BRIGHTNESS_SETTLE_DELAY_MILLIS)
      val after = readBrightnessStateForVerification(panelOnly)
      if (after == null || matches(after)) {
        return PhoneAutomationActionResult(true, successDetail)
      }
      lastState = after
      if (attemptIndex < totalAttempts - 1) {
        delay(VISIBLE_RESTORE_RETRY_DELAY_MILLIS)
      }
    }

    return PhoneAutomationActionResult(
      success = false,
      detail = lastState?.verificationFailureDetail("$verificationFailurePrefix after $totalAttempts attempts")
        ?: "$verificationFailurePrefix after $totalAttempts attempts"
    )
  }

  private suspend fun readBrightnessStateForVerification(panelOnly: Boolean): ScreenBrightnessState? {
    var state = readBrightnessState()
    if (panelOnly && (state == null || !state.hasPanelBrightnessData())) {
      delay(BRIGHTNESS_SETTLE_DELAY_MILLIS)
      state = readBrightnessState()
    }
    return state
  }

  private suspend fun readTouchDevices(): List<RootTouchDevice> {
    return runCatching {
      val result = rootExecutor.run("getevent -lp", timeout = 5.seconds)
      if (result.ok) {
        RootTouchDeviceDiscovery.parseTouchDevices(result.stdout)
      } else {
        emptyList()
      }
    }.getOrDefault(emptyList())
  }

  private suspend fun readPowerKeyDevices(): List<RootPowerKeyDevice> {
    return runCatching {
      val result = rootExecutor.run("getevent -lp", timeout = 5.seconds)
      if (result.ok) {
        RootPowerKeyDeviceDiscovery.parsePowerKeyDevices(result.stdout)
      } else {
        emptyList()
      }
    }.getOrDefault(emptyList())
  }

  private suspend fun readPanelAvailable(): Boolean {
    return runCatching {
      val result = rootExecutor.runScript(
        """
        for candidate in /sys/class/backlight/*; do
          if [ -w "${'$'}candidate/brightness" ] && [ -r "${'$'}candidate/max_brightness" ]; then
            printf 'ready\n'
            exit 0
          fi
        done
        printf 'missing\n'
        exit 1
        """.trimIndent(),
        timeout = 5.seconds
      )
      result.ok && result.stdout.contains("ready")
    }.getOrDefault(false)
  }

  private fun ScreenBrightnessState.withRemotePanelFallback(): ScreenBrightnessState {
    val remoteState = bridge.remoteScreenBrightnessState() ?: return this
    val remoteHasVisiblePanel = remoteState.hasVisiblePanelBrightnessData()
    return copy(
      displayPercentage = displayPercentage ?: remoteState.displayPercentage,
      panelPath = panelPath ?: remoteState.panelPath.takeIf { remoteHasVisiblePanel },
      panelBrightness = panelBrightness ?: remoteState.panelBrightness.takeIf { remoteHasVisiblePanel },
      panelActualBrightness = panelActualBrightness ?: remoteState.panelActualBrightness.takeIf { remoteHasVisiblePanel },
      panelMaxBrightness = panelMaxBrightness ?: remoteState.panelMaxBrightness.takeIf { remoteHasVisiblePanel }
    )
  }

  private fun ScreenBrightnessState.visiblePanelFallbackPercent(): Int {
    val display = displayPercentage?.roundToInt()
    val system = value?.let(ScreenBrightnessControl::percentFromSystemValue)
    return (display ?: system ?: VISIBLE_PANEL_FALLBACK_PERCENT)
      .coerceAtLeast(VISIBLE_PANEL_FALLBACK_PERCENT)
      .coerceAtMost(100)
  }

  private fun ScreenBrightnessState.matchesTargetLenient(targetPercent: Int, panelOnly: Boolean): Boolean {
    if (panelOnly) {
      return panelPercentMatches(targetPercent)
    }
    if (targetPercent > PANEL_SLEEP_TARGET_PERCENT && hasPanelBrightnessData() && !panelPercentMatches(targetPercent)) {
      return false
    }
    if (mode != null && mode != MANUAL_BRIGHTNESS_MODE) {
      return false
    }
    if (targetPercent > PANEL_SLEEP_TARGET_PERCENT && displayPercentage != null && displayPercentage <= DISPLAY_PERCENT_TOLERANCE) {
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

  private fun ScreenBrightnessState.matchesRestoredStateLenient(
    expected: ScreenBrightnessState,
    panelOnly: Boolean
  ): Boolean {
    if (expected.panelBrightness != null || expected.panelActualBrightness != null) {
      val expectedPanel = expected.panelBrightness ?: expected.panelActualBrightness
      val actualPanel = panelActualBrightness ?: panelBrightness
      if (expectedPanel != null && actualPanel != null && kotlin.math.abs(actualPanel - expectedPanel) <= PANEL_VALUE_TOLERANCE) {
        if (panelOnly) {
          return true
        }
      } else if (panelOnly) {
        return false
      } else if (expectedPanel != null && actualPanel != null) {
        return false
      }
    } else if (panelOnly) {
      return !hasPanelBrightnessData() || hasVisiblePanelBrightnessData()
    }
    if (!panelOnly && expected.mode != AUTOMATIC_BRIGHTNESS_MODE && hasPanelBrightnessData() && !hasVisiblePanelBrightnessData()) {
      return false
    }
    val expectsVisibleAndroidBrightness =
      (expected.displayPercentage != null && expected.displayPercentage > DISPLAY_PERCENT_TOLERANCE) ||
        ((expected.value ?: 0) > 0)
    if (!panelOnly && expectsVisibleAndroidBrightness && displayPercentage != null && displayPercentage <= DISPLAY_PERCENT_TOLERANCE) {
      return false
    }
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

  private fun ScreenBrightnessState.panelPercentMatches(targetPercent: Int): Boolean {
    val max = panelMaxBrightness ?: return false
    if (max <= 0) {
      return false
    }
    val current = panelActualBrightness ?: panelBrightness ?: return false
    if (targetPercent == PANEL_SLEEP_TARGET_PERCENT) {
      return current == 0
    }
    val targetValue = ScreenBrightnessControl.panelValueFromPercent(targetPercent, max)
    if (kotlin.math.abs(current - targetValue) <= PANEL_VALUE_TOLERANCE) {
      return true
    }
    val currentPercent = (current.toFloat() / max.toFloat()) * 100.0f
    return abs(currentPercent - targetPercent.toFloat()) <= PANEL_PERCENT_TOLERANCE
  }

  private fun ScreenBrightnessState.verificationFailureDetail(prefix: String): String {
    return "$prefix: mode=$mode value=$value display=$displayPercentage panel=$panelActualBrightness/$panelMaxBrightness path=$panelPath"
  }

  private fun ScreenBrightnessState.hasPanelBrightnessData(): Boolean {
    return panelMaxBrightness != null && (panelActualBrightness != null || panelBrightness != null)
  }

  private fun ScreenBrightnessState.hasVisiblePanelBrightnessData(): Boolean {
    val panel = panelActualBrightness ?: panelBrightness ?: return false
    return panel > PANEL_VALUE_TOLERANCE
  }

  companion object {
    private const val BRIGHTNESS_SETTLE_DELAY_MILLIS = 250L
    private const val PANEL_SLEEP_TARGET_PERCENT = 0
    private const val PANEL_SLEEP_HOLD_MILLIS = 1_500L
    private const val DIM_HOLD_INTERVAL_MILLIS = 50L
    private const val VISIBLE_HOLD_MILLIS = 1_500L
    private const val VISIBLE_RESTORE_RETRY_DELAY_MILLIS = 250L
    private const val VISIBLE_RESTORE_ATTEMPTS = 4
    private const val VISIBLE_PANEL_FALLBACK_PERCENT = 20
    private const val DISPLAY_PERCENT_TOLERANCE = 0.5f
    private const val PANEL_PERCENT_TOLERANCE = 1.0f
    private const val PANEL_VALUE_TOLERANCE = 2
    private const val MANUAL_BRIGHTNESS_MODE = 0
    private const val AUTOMATIC_BRIGHTNESS_MODE = 1
    private const val TAG = "TouchBrightnessDevice"
  }
}

private enum class InternalTouchBrightnessMode {
  STARTING,
  BRIGHT_IDLE,
  BRIGHT_TOUCH_ACTIVE,
  PANEL_SLEEP,
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
  private val powerController: TouchScreenPowerController = AndroidTouchScreenPowerController(
    context = context.applicationContext,
    rootExecutor = rootExecutor
  ),
  private val eventSourceFactory: () -> TouchBrightnessEventSource = {
    AndroidTouchBrightnessEventSource(context.applicationContext)
  },
  private val uptimeClock: () -> Long = SystemClock::uptimeMillis,
  private val dimGuardEnabled: Boolean = true
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
      powerController.releaseHold("runtime_stop:$reason")
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
    var currentSource: RootTouchDevice? = null
    var currentPowerSource: RootPowerKeyDevice? = null
    var currentTouchSnapshot = RootTouchSnapshot()
    var visibleBrightnessState: ScreenBrightnessState? = null
    var internalMode = InternalTouchBrightnessMode.STARTING
    var idleDeadlineMillis: Long? = null
    var powerReboundDeadlineMillis: Long? = null
    var powerButtonVisibleIdleActive = false
    var overlayPointerCount = 0

    var idleJob: Job? = null
    var powerReboundJob: Job? = null
    var panelSleepGuardJob: Job? = null
    var panelSleepReassertBurstJob: Job? = null
    var panelSleepGuardFailureCount = 0
    val eventSource = eventSourceFactory()

    fun sourceSuffix(): String {
      val source = currentSource ?: return ""
      return " (source: ${source.displayLabel()})"
    }

    fun visibleBrightnessLabel(): String {
      val state = visibleBrightnessState ?: return "saved brightness"
      if (state.mode == AUTOMATIC_BRIGHTNESS_MODE_VALUE) {
        return "saved automatic brightness"
      }
      state.displayPercentage?.let { display ->
        return "${display.roundToInt().coerceIn(0, 100)}%"
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

    fun panelSleepTimerSummary(): String {
      val deadline = idleDeadlineMillis ?: return "none"
      val remainingMillis = (deadline - uptimeClock()).coerceAtLeast(0L)
      return "pending(${remainingMillis}ms)"
    }

    fun powerReboundSummary(): String {
      val deadline = powerReboundDeadlineMillis ?: return "none"
      val remainingMillis = (deadline - uptimeClock()).coerceAtLeast(0L)
      return "pending(${remainingMillis}ms)"
    }

    fun buildDebugDetail(): String {
      return buildString {
        append("touches=")
        append(activeTouchCount)
        append(" source=")
        append(currentTouchSourceLabel())
        append(" power_source=")
        append(currentPowerSource?.displayLabel() ?: "unknown")
        append(" last_raw=")
        append(lastTouchAgeSummary())
        append(" timer=")
        append(panelSleepTimerSummary())
        append(" panel_target=")
        append(PANEL_SLEEP_PERCENT)
        append(" power_rebound=")
        append(powerReboundSummary())
        append(" overlay=")
        append(overlayPointerCount)
        append(" raw(btn=")
        append(currentTouchSnapshot.btnTouchActive)
        append(",slots=")
        append(currentTouchSnapshot.activeSlotCount)
        append(",tool=")
        append(currentTouchSnapshot.toolFingerActive)
        append(")")
        append(" hold=")
        append(powerController.wakeHoldActive)
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

        InternalTouchBrightnessMode.BRIGHT_IDLE -> {
          TouchBrightnessRuntimeState.BRIGHT to "Screen is visible at ${visibleBrightnessLabel()}; panel sleep timer ${panelSleepTimerSummary()}${sourceSuffix()}"
        }

        InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE -> {
          TouchBrightnessRuntimeState.BRIGHT to "Screen is visible at ${visibleBrightnessLabel()} because touch is active${sourceSuffix()}"
        }

        InternalTouchBrightnessMode.PANEL_SLEEP -> {
          TouchBrightnessRuntimeState.PANEL_SLEEP to "Panel brightness is zero; Android is held awake waiting for physical touch or power button${sourceSuffix()}"
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
        logTouchState("panel_sleep_timer_cancelled")
      }
    }

    fun cancelPowerReboundJob() {
      powerReboundJob?.cancel()
      powerReboundJob = null
      powerReboundDeadlineMillis = null
    }

    fun cancelPanelSleepGuardJob() {
      panelSleepGuardJob?.cancel()
      panelSleepGuardJob = null
    }

    fun cancelPanelSleepReassertBurstJob() {
      panelSleepReassertBurstJob?.cancel()
      panelSleepReassertBurstJob = null
    }

    fun ensurePanelSleepGuardJob() {
      if (!dimGuardEnabled || panelSleepGuardJob?.isActive == true) {
        return
      }
      panelSleepGuardJob = launch {
        while (true) {
          delay(PANEL_DIM_GUARD_INTERVAL_MILLIS)
          if (
            internalMode != InternalTouchBrightnessMode.PANEL_SLEEP ||
            !interactive ||
            activeTouchCount > 0 ||
            overlayPointerCount > 0
          ) {
            panelSleepGuardJob = null
            return@launch
          }
          powerController.holdScreen("panel_sleep_guard")
          val brightness = deviceController.setBrightnessPercent(PANEL_SLEEP_PERCENT)
          if (!brightness.success) {
            panelSleepGuardFailureCount += 1
            settingsStore.updateTouchBrightnessState(
              TouchBrightnessRuntimeState.PANEL_SLEEP,
              "Panel sleep retrying after brightness push${sourceSuffix()}: ${brightness.detail}"
            )
            publishDebugState()
            if (panelSleepGuardFailureCount >= PANEL_DIM_GUARD_FAILURE_LIMIT) {
              throw IllegalStateException("Could not hold panel sleep brightness: ${brightness.detail}")
            }
            continue
          }
          panelSleepGuardFailureCount = 0
          publishDebugState()
          logTouchState("panel_sleep_guard")
        }
      }
    }

    fun shouldReassertPanelSleepForNonTouch(reason: String): Boolean {
      return internalMode == InternalTouchBrightnessMode.PANEL_SLEEP ||
        internalMode == InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF ||
        (
          internalMode == InternalTouchBrightnessMode.BRIGHT_IDLE &&
            !powerButtonVisibleIdleActive
          )
    }

    suspend fun reassertPanelSleepBrightness(reason: String) {
      cancelIdleJob()
      powerButtonVisibleIdleActive = false
      powerController.holdScreen("panel_sleep_reassert:$reason")
      overlayPointerCount = 0
      val overlayHidden = overlayController.hide()
      if (!overlayHidden.success) {
        throw IllegalStateException(overlayHidden.detail)
      }
      val brightness = deviceController.setBrightnessPercent(PANEL_SLEEP_PERCENT)
      if (!brightness.success) {
        panelSleepGuardFailureCount += 1
        internalMode = InternalTouchBrightnessMode.PANEL_SLEEP
        settingsStore.updateTouchBrightnessState(
          TouchBrightnessRuntimeState.PANEL_SLEEP,
          "Panel sleep retrying after brightness reassert${sourceSuffix()}: ${brightness.detail}"
        )
        publishDebugState()
        ensurePanelSleepGuardJob()
        logTouchState("${reason}_retrying")
        return
      }
      panelSleepGuardFailureCount = 0
      internalMode = InternalTouchBrightnessMode.PANEL_SLEEP
      publishCurrentState()
      ensurePanelSleepGuardJob()
      logTouchState(reason)
    }

    fun schedulePanelSleepReassertBurst(reason: String) {
      if (!dimGuardEnabled) {
        return
      }
      cancelPanelSleepReassertBurstJob()
      panelSleepReassertBurstJob = launch {
        var previousDelayMillis = 0L
        for (delayMillis in NON_TOUCH_PANEL_REASSERT_DELAYS_MILLIS) {
          delay(delayMillis - previousDelayMillis)
          previousDelayMillis = delayMillis
          if (
            internalMode != InternalTouchBrightnessMode.PANEL_SLEEP ||
            !interactive ||
            activeTouchCount > 0 ||
            overlayPointerCount > 0
          ) {
            panelSleepReassertBurstJob = null
            return@launch
          }
          val brightness = deviceController.setBrightnessPercent(PANEL_SLEEP_PERCENT)
          if (!brightness.success) {
            panelSleepGuardFailureCount += 1
            settingsStore.updateTouchBrightnessState(
              TouchBrightnessRuntimeState.PANEL_SLEEP,
              "Panel sleep burst retry failed after non-touch input${sourceSuffix()}: ${brightness.detail}"
            )
            publishDebugState()
            if (panelSleepGuardFailureCount >= PANEL_DIM_GUARD_FAILURE_LIMIT) {
              throw IllegalStateException("Could not reassert panel sleep brightness after non-touch input: ${brightness.detail}")
            }
            continue
          }
          panelSleepGuardFailureCount = 0
          publishDebugState()
          logTouchState("${reason}_burst_${delayMillis}ms")
        }
        panelSleepReassertBurstJob = null
      }
    }

    fun schedulePanelSleepFrom(observedAtUptimeMillis: Long) {
      if (activeTouchCount > 0) {
        publishDebugState()
        logTouchState("panel_sleep_timer_blocked_by_touch")
        return
      }
      cancelIdleJob()
      val deadline = observedAtUptimeMillis + IDLE_PANEL_SLEEP_DELAY_MILLIS
      idleDeadlineMillis = deadline
      logTouchState("panel_sleep_timer_started")
      publishDebugState()
      idleJob = launch {
        delay(maxOf(0L, deadline - uptimeClock()))
        idleJob = null
        idleDeadlineMillis = null
        if (!interactive || activeTouchCount > 0) {
          publishDebugState()
          return@launch
        }
        powerController.holdScreen("panel_sleep_timer_fired")
        powerButtonVisibleIdleActive = false
        panelSleepGuardFailureCount = 0
        val overlayHidden = overlayController.hide()
        if (!overlayHidden.success) {
          throw IllegalStateException(overlayHidden.detail)
        }
        overlayPointerCount = 0
        val brightness = deviceController.setBrightnessPercent(PANEL_SLEEP_PERCENT)
        if (!brightness.success) {
          throw IllegalStateException("Could not set panel sleep brightness: ${brightness.detail}")
        }
        internalMode = InternalTouchBrightnessMode.PANEL_SLEEP
        publishCurrentState()
        ensurePanelSleepGuardJob()
        logTouchState("panel_sleep_timer_fired")
      }
    }

    fun schedulePowerButtonRebound(startedAtUptimeMillis: Long) {
      powerReboundJob?.cancel()
      val deadline = startedAtUptimeMillis + POWER_BUTTON_REBOUND_WINDOW_MILLIS
      powerReboundDeadlineMillis = deadline
      powerReboundJob = launch {
        while (uptimeClock() < deadline) {
          delay(POWER_BUTTON_REBOUND_POLL_MILLIS)
          if (!eventSource.isInteractive()) {
            val wake = powerController.forceWakeScreen("panel_sleep_power_rebound")
            if (!wake.success) {
              Log.w(TAG, "touch_screen_force_wake_failed detail=${wake.detail}")
            }
            interactive = true
            val brightness = applyVisibleBrightnessState(visibleBrightnessState)
            if (!brightness.success) {
              Log.w(TAG, "panel_sleep_rebound_brightness_failed detail=${brightness.detail}")
            }
            publishCurrentState()
          }
        }
        if (powerReboundDeadlineMillis == deadline) {
          powerReboundDeadlineMillis = null
          powerReboundJob = null
          publishDebugState()
        }
      }
    }

    suspend fun enterBrightIdle(
      timerStartUptimeMillis: Long,
      reason: String,
      powerButtonVisibleIdle: Boolean = false
    ) {
      cancelIdleJob()
      cancelPanelSleepGuardJob()
      cancelPanelSleepReassertBurstJob()
      powerButtonVisibleIdleActive = powerButtonVisibleIdle
      powerController.holdScreen(reason)
      val overlayHidden = overlayController.hide()
      if (!overlayHidden.success) {
        throw IllegalStateException(overlayHidden.detail)
      }
      overlayPointerCount = 0
      val brightness = applyVisibleBrightnessState(visibleBrightnessState)
      if (!brightness.success) {
        throw IllegalStateException("Could not set bright mode: ${brightness.detail}")
      }
      internalMode = InternalTouchBrightnessMode.BRIGHT_IDLE
      schedulePanelSleepFrom(timerStartUptimeMillis)
      publishCurrentState()
      logTouchState(reason)
    }

    suspend fun enterBrightTouchActive() {
      cancelIdleJob()
      cancelPanelSleepGuardJob()
      cancelPanelSleepReassertBurstJob()
      powerButtonVisibleIdleActive = false
      powerController.holdScreen("touch_active")
      if (internalMode != InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE) {
        panelSleepGuardFailureCount = 0
        val overlayHidden = overlayController.hide()
        if (!overlayHidden.success) {
          throw IllegalStateException(overlayHidden.detail)
        }
        overlayPointerCount = 0
        val brightness = applyVisibleBrightnessState(visibleBrightnessState)
        if (!brightness.success) {
          throw IllegalStateException("Could not set bright mode: ${brightness.detail}")
        }
      }
      internalMode = InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE
      publishCurrentState()
      logTouchState("touch_active")
    }

    suspend fun enterSuspendedScreenOff() {
      cancelIdleJob()
      cancelPanelSleepGuardJob()
      cancelPanelSleepReassertBurstJob()
      cancelPowerReboundJob()
      powerButtonVisibleIdleActive = false
      powerController.releaseHold("screen_off")
      overlayPointerCount = 0
      overlayController.hide()
      panelSleepGuardFailureCount = 0
      internalMode = InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF
      publishCurrentState()
      logTouchState("screen_off_suspended")
    }

    val startupSnapshot = settingsStore.load()

    internalMode = InternalTouchBrightnessMode.STARTING
    publishCurrentState()

    val preparation = deviceController.prepare()
    if (!preparation.ready) {
      throw IllegalStateException(preparation.detail)
    }
    val capturedBrightnessState = captureRestoreStateIfNeeded()
    val startupBrightnessWasPanelSleep = capturedBrightnessState?.isPanelSleepBrightnessState() == true
    visibleBrightnessState = if (startupBrightnessWasPanelSleep) {
      settingsStore.clearTouchBrightnessRestoreState()
      null
    } else {
      capturedBrightnessState
    }
    if (visibleBrightnessState == null && !startupBrightnessWasPanelSleep) {
      throw IllegalStateException("Could not read the current brightness state")
    }

    interactive = eventSource.isInteractive()
    activeTouchCount = eventSource.activeTouchCount()
    currentSource = eventSource.selectedTouchSource()
    currentPowerSource = eventSource.selectedPowerSource()
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
    } else {
      reassertPanelSleepBrightness("start_panel_sleep_reasserted")
    }

    val eventJob = launch {
      eventSource.events.collect { event ->
        when (event) {
          is TouchBrightnessEvent.TouchCountChanged -> {
            val previousTouchCount = activeTouchCount
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
            val physicalTouchStarted = event.activeTouchCount > 0 && currentTouchSnapshot.isRawTouchActive()
            val physicalTouchReleased = previousTouchCount > 0 && event.activeTouchCount == 0
            if (
              PhoneAutomationServiceBridge.isNonTouchInputSuppressed(event.observedAtUptimeMillis) &&
              !physicalTouchStarted &&
              !physicalTouchReleased
            ) {
              activeTouchCount = 0
              overlayPointerCount = 0
              currentTouchSnapshot = currentTouchSnapshot.copy(
                activeTouchCount = 0,
                btnTouchActive = false,
                toolFingerActive = false,
                activeSlotCount = 0
              )
              if (interactive && internalMode == InternalTouchBrightnessMode.PANEL_SLEEP) {
                reassertPanelSleepBrightness("non_touch_input_panel_sleep_reasserted")
                schedulePanelSleepReassertBurst("non_touch_input_panel_sleep_reasserted")
              }
              publishDebugState()
              logTouchState("non_touch_input_ignored")
              return@collect
            }
            activeTouchCount = event.activeTouchCount
            logTouchState("touch_count_changed")
            when {
              !interactive && activeTouchCount > 0 -> {
                val wake = powerController.wakeScreen("raw_touch_while_screen_off")
                if (!wake.success) {
                  Log.w(TAG, "touch_screen_wake_failed detail=${wake.detail}")
                }
                interactive = true
                enterBrightTouchActive()
              }

              !interactive -> Unit
              activeTouchCount > 0 -> {
                if (previousTouchCount == 0) {
                  logTouchState("wake_promoted_to_active_touch")
                }
                enterBrightTouchActive()
              }

              previousTouchCount > 0 && activeTouchCount == 0 -> {
                enterBrightIdle(event.observedAtUptimeMillis, "touch_released_visible_idle")
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
              val now = uptimeClock()
              val shouldReboundDuringPowerButtonWindow = powerReboundDeadlineMillis?.let { now <= it } == true
              val shouldReboundPowerButtonVisibleIdle =
                internalMode == InternalTouchBrightnessMode.BRIGHT_IDLE && powerButtonVisibleIdleActive
              val shouldTreatPanelSleepScreenOffAsPowerWake = internalMode == InternalTouchBrightnessMode.PANEL_SLEEP
              if (
                shouldReboundDuringPowerButtonWindow ||
                shouldReboundPowerButtonVisibleIdle ||
                shouldTreatPanelSleepScreenOffAsPowerWake
              ) {
                val wake = powerController.forceWakeScreen("panel_sleep_power_rebound_screen_off")
                if (!wake.success) {
                  Log.w(TAG, "touch_screen_force_wake_failed detail=${wake.detail}")
                }
                interactive = true
                val timerStartUptimeMillis = if (shouldReboundPowerButtonVisibleIdle) {
                  idleDeadlineMillis?.minus(IDLE_PANEL_SLEEP_DELAY_MILLIS) ?: now
                } else {
                  now
                }
                if (shouldTreatPanelSleepScreenOffAsPowerWake) {
                  schedulePowerButtonRebound(timerStartUptimeMillis)
                }
                enterBrightIdle(
                  timerStartUptimeMillis,
                  "panel_sleep_power_rebound_screen_off",
                  powerButtonVisibleIdle = true
                )
              } else {
                enterSuspendedScreenOff()
              }
            } else {
              val liveTouchSnapshot = eventSource.currentTouchSnapshot() ?: currentTouchSnapshot
              val nonTouchInputSuppressed = PhoneAutomationServiceBridge.isNonTouchInputSuppressed(uptimeClock())
              val liveTouchCount = if (nonTouchInputSuppressed && !liveTouchSnapshot.isRawTouchActive()) {
                0
              } else {
                maxOf(
                  activeTouchCount,
                  eventSource.activeTouchCount(),
                  if (liveTouchSnapshot.isRawTouchActive()) {
                    maxOf(1, liveTouchSnapshot.activeTouchCount)
                  } else {
                    liveTouchSnapshot.activeTouchCount
                  }
                )
              }
              activeTouchCount = liveTouchCount
              currentTouchSnapshot = liveTouchSnapshot.copy(
                activeTouchCount = liveTouchCount,
                btnTouchActive = liveTouchCount > 0 && liveTouchSnapshot.btnTouchActive,
                toolFingerActive = liveTouchCount > 0 && liveTouchSnapshot.toolFingerActive,
                activeSlotCount = if (liveTouchCount > 0) liveTouchSnapshot.activeSlotCount else 0,
                selectedDevice = liveTouchSnapshot.selectedDevice ?: currentSource
              )
              currentTouchSnapshot.selectedDevice?.let {
                currentSource = it
              }
              if (
                activeTouchCount == 0 &&
                nonTouchInputSuppressed &&
                (
                  internalMode == InternalTouchBrightnessMode.PANEL_SLEEP ||
                    internalMode == InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF
                  )
              ) {
                val reassertReason = "screen_on_non_touch_panel_sleep_reasserted"
                reassertPanelSleepBrightness(reassertReason)
                schedulePanelSleepReassertBurst(reassertReason)
              } else if (activeTouchCount > 0) {
                enterBrightTouchActive()
              } else {
                enterBrightIdle(
                  uptimeClock(),
                  "screen_on_visible_idle",
                  powerButtonVisibleIdle = internalMode == InternalTouchBrightnessMode.PANEL_SLEEP
                )
              }
            }
          }

          is TouchBrightnessEvent.NonTouchInput -> {
            if (activeTouchCount > 0 || currentTouchSnapshot.isRawTouchActive()) {
              publishDebugState()
              logTouchState("non_touch_input_deferred_physical_touch_active:${event.reason}")
              return@collect
            }
            activeTouchCount = 0
            overlayPointerCount = 0
            currentTouchSnapshot = currentTouchSnapshot.copy(
              activeTouchCount = 0,
              btnTouchActive = false,
              toolFingerActive = false,
              activeSlotCount = 0,
              lastEventUptimeMillis = event.observedAtUptimeMillis
            )
            if (interactive && shouldReassertPanelSleepForNonTouch(event.reason)) {
              reassertPanelSleepBrightness("non_touch_input_panel_sleep_reasserted")
              schedulePanelSleepReassertBurst("non_touch_input_panel_sleep_reasserted")
            } else {
              publishDebugState()
            }
            logTouchState("non_touch_input_event:${event.reason}")
          }

          is TouchBrightnessEvent.BlackoutWakeRequested -> {
            overlayPointerCount = event.activePointerCount
            if (!interactive) {
              val wake = powerController.wakeScreen("blackout_overlay")
              if (!wake.success) {
                Log.w(TAG, "touch_screen_wake_failed detail=${wake.detail}")
              }
              interactive = true
            }
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
                enterBrightIdle(event.observedAtUptimeMillis, "legacy_overlay_wake_visible_idle")
              }
            }
          }

          is TouchBrightnessEvent.PowerButtonPressed -> {
            currentPowerSource = event.device ?: currentPowerSource
            if (internalMode == InternalTouchBrightnessMode.PANEL_SLEEP) {
              val wake = powerController.forceWakeScreen("panel_sleep_power_button")
              if (!wake.success) {
                Log.w(TAG, "touch_screen_force_wake_failed detail=${wake.detail}")
              }
              interactive = true
              activeTouchCount = 0
              overlayPointerCount = 0
              schedulePowerButtonRebound(event.observedAtUptimeMillis)
              enterBrightIdle(
                event.observedAtUptimeMillis,
                "panel_sleep_power_button",
                powerButtonVisibleIdle = true
              )
            } else {
              publishDebugState()
              logTouchState("power_button_ignored_outside_panel_sleep")
            }
          }

          is TouchBrightnessEvent.OverlayAvailabilityChanged -> {
            publishDebugState()
          }

          is TouchBrightnessEvent.TouchSourceSelected -> {
            currentSource = event.device
            currentTouchSnapshot = currentTouchSnapshot.copy(selectedDevice = event.device)
            logTouchState("touch_source_selected")
            publishCurrentState()
          }

          is TouchBrightnessEvent.PowerSourceSelected -> {
            currentPowerSource = event.device
            logTouchState("power_source_selected")
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
      cancelPanelSleepGuardJob()
      cancelPanelSleepReassertBurstJob()
      cancelPowerReboundJob()
      powerController.releaseHold("session_finished")
      eventJob.cancelAndJoin()
    }
  }

  private suspend fun captureRestoreStateIfNeeded(): ScreenBrightnessState? {
    val snapshot = settingsStore.load()
    val savedState = snapshot.touchBrightnessRestoreState()
    if (savedState != null) {
      return savedState
    }

    val currentState = deviceController.readBrightnessState()
    if (currentState == null || currentState.mode == null || currentState.value == null) {
      return null
    }

    settingsStore.saveTouchBrightnessRestoreState(
      mode = currentState.mode,
      value = currentState.value
    )
    return currentState
  }

  private suspend fun applyVisibleBrightnessState(state: ScreenBrightnessState?): PhoneAutomationActionResult {
    val targetState = state ?: settingsStore.load().touchBrightnessRestoreState()
    if (targetState != null) {
      val restore = deviceController.restoreBrightnessState(targetState)
      if (restore.success) {
        return restore
      }
      runCatching {
        Log.w(TAG, "saved brightness restore failed during wake; forcing safe visible brightness: ${restore.detail}")
      }
      return deviceController.setBrightnessPercent(SAFE_VISIBLE_FALLBACK_PERCENT)
    } else {
      return deviceController.setBrightnessPercent(SAFE_VISIBLE_FALLBACK_PERCENT)
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

  private fun ScreenBrightnessState.isPanelSleepBrightnessState(): Boolean {
    val systemBrightness = value
    if (systemBrightness != null && systemBrightness <= 0) {
      return true
    }
    val display = displayPercentage
    if (display != null && display <= PANEL_SLEEP_DISPLAY_PERCENT_TOLERANCE) {
      return true
    }
    val panel = panelActualBrightness ?: panelBrightness
    return panel != null && panel <= PANEL_SLEEP_PANEL_VALUE_TOLERANCE
  }

  companion object {
    internal const val BRIGHT_PERCENT = 100
    internal const val SAFE_VISIBLE_FALLBACK_PERCENT = 20
    internal const val PANEL_SLEEP_PERCENT = 0
    internal const val IDLE_PANEL_SLEEP_DELAY_MILLIS = 120_000L
    internal const val POWER_BUTTON_REBOUND_WINDOW_MILLIS = 2_000L
    internal const val POWER_BUTTON_REBOUND_POLL_MILLIS = 150L
    internal const val PANEL_DIM_GUARD_INTERVAL_MILLIS = 500L
    private val NON_TOUCH_PANEL_REASSERT_DELAYS_MILLIS = longArrayOf(100L, 250L, 500L, 1_000L, 1_500L)
    internal const val PANEL_DIM_GUARD_FAILURE_LIMIT = 5
    internal const val SESSION_RETRY_INITIAL_DELAY_MILLIS = 1_000L
    internal const val SESSION_RETRY_MAX_DELAY_MILLIS = 15_000L
    private const val AUTOMATIC_BRIGHTNESS_MODE_VALUE = 1
    private const val PANEL_SLEEP_DISPLAY_PERCENT_TOLERANCE = 0.5f
    private const val PANEL_SLEEP_PANEL_VALUE_TOLERANCE = 2
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
