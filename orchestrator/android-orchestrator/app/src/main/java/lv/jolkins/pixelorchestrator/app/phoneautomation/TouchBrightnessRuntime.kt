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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val suppressedUntilUptimeMillis: Long,
    val touchBeginCount: Long? = null
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
  suspend fun clampPanelSleepImmediately(): PhoneAutomationActionResult = setBrightnessPercent(0)
  suspend fun clampPanelSleepForWake(): PhoneAutomationActionResult = setBrightnessPercent(0)
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
            bridge.recordRootPhysicalTouchState(
              active = event.activeTouchCount > 0 && event.snapshot?.isRawTouchActive() == true,
              observedAtUptimeMillis = event.observedAtUptimeMillis
            )
            trySend(
              TouchBrightnessEvent.TouchCountChanged(
                activeTouchCount = event.activeTouchCount,
                observedAtUptimeMillis = event.observedAtUptimeMillis,
                snapshot = event.snapshot
              )
            )
          }

          is RootTouchEvent.SourceSelected -> {
            bridge.recordRootPhysicalTouchState(
              active = false,
              observedAtUptimeMillis = SystemClock.uptimeMillis(),
              available = true
            )
            trySend(TouchBrightnessEvent.TouchSourceSelected(event.device))
          }

          is RootTouchEvent.FatalError -> {
            bridge.recordRootPhysicalTouchState(
              active = false,
              observedAtUptimeMillis = SystemClock.uptimeMillis(),
              available = false
            )
            trySend(TouchBrightnessEvent.FatalError(event.detail))
          }
        }
      }
    }

    val powerKeyJob = launch {
      rootPowerKeyMonitor.events.collect { event ->
        when (event) {
          is RootPowerKeyEvent.PowerButtonPressed -> {
            bridge.revokePhysicalVisibility(event.observedAtUptimeMillis)
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
            suppressedUntilUptimeMillis = event.suppressedUntilUptimeMillis,
            touchBeginCount = event.touchBeginCount
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
      bridge.recordRootPhysicalTouchState(active = false, available = false)
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
    if (targetPercent == PANEL_SLEEP_TARGET_PERCENT) return clampPanelSleepImmediately()
    val before = readBrightnessStateForVerification(panelOnly = false)
    if (before != null && before.panelBacklightPower == 0 &&
      before.matchesTargetLenient(targetPercent, panelOnly = false)) {
      return PhoneAutomationActionResult(true, "Brightness already at $targetPercent%")
    }
    return runBrightnessCommandUntilVerified(
      scriptFactory = {
        ScreenBrightnessControl.buildSetPercentScript(
          percent = targetPercent,
          panelHoldMillis = VISIBLE_HOLD_MILLIS,
          panelHoldIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
        ) + "\n" + ScreenBrightnessControl.buildPanelBlankScript(blank = false)
      },
      panelOnly = false,
      attempts = VISIBLE_RESTORE_ATTEMPTS,
      commandFailureDetail = "brightness command failed",
      verificationFailurePrefix = "Brightness verification failed",
      successDetail = "Brightness set to $targetPercent%",
      matches = { it.panelBacklightPower == 0 && it.matchesTargetLenient(targetPercent, panelOnly = false) }
    )
  }

  override suspend fun clampPanelSleepImmediately(): PhoneAutomationActionResult {
    val result = rootExecutor.runScript(
      """
        ${ScreenBrightnessControl.buildPanelBlankScript(blank = true)}
        echo 0 > "${'$'}panel_dir/brightness" || exit 1
        [ "${'$'}(cat "${'$'}panel_dir/brightness")" = 0 ] || exit 1
        [ "${'$'}(cat "${'$'}panel_dir/bl_power")" = 4 ] || exit 1
      """.trimIndent(),
      timeout = 2.seconds
    )
    return PhoneAutomationActionResult(
      result.ok,
      if (result.ok) "Panel blank and requested zero confirmed" else "Panel blank was not confirmed"
    )
  }

  override suspend fun clampPanelSleepForWake(): PhoneAutomationActionResult = clampPanelSleepImmediately()

  override suspend fun restoreBrightnessState(state: ScreenBrightnessState): PhoneAutomationActionResult {
    val panelOnly = false
    val restoreState = state.withRemotePanelFallback()
    val before = readBrightnessStateForVerification(panelOnly)
    if (before != null && before.panelBacklightPower == 0 && before.matchesRestoredStateLenient(restoreState, panelOnly = panelOnly)) {
      return PhoneAutomationActionResult(true, "Brightness already restored")
    }
    // Blanking does not change Android brightness. When those settings still match,
    // restore only the raw request and open the gate; there is no framework ramp to wait for.
    val androidStateUnchanged = before?.mode == restoreState.mode &&
      before?.value == restoreState.value && (before?.displayPercentage ?: 0f) > DISPLAY_PERCENT_TOLERANCE
    val holdMillis = if (androidStateUnchanged) 0L else VISIBLE_HOLD_MILLIS
    val panelScript = if (restoreState.hasVisiblePanelBrightnessData()) {
      ScreenBrightnessControl.buildRestorePanelScript(restoreState, holdMillis, DIM_HOLD_INTERVAL_MILLIS)
    } else {
      ScreenBrightnessControl.buildSetPanelPercentScript(
        percent = restoreState.visiblePanelFallbackPercent(),
        holdMillis = holdMillis,
        holdIntervalMillis = DIM_HOLD_INTERVAL_MILLIS
      )
    }
    return runBrightnessCommandUntilVerified(
      scriptFactory = {
        val prepare = if (androidStateUnchanged) panelScript else {
          // One panel write sequence, including when the saved state has no raw panel data.
          ScreenBrightnessControl.buildRestoreScript(restoreState) + "\n" + panelScript
        }
        prepare + "\n" + ScreenBrightnessControl.buildPanelBlankScript(blank = false)
      },
      panelOnly = panelOnly,
      attempts = VISIBLE_RESTORE_ATTEMPTS,
      commandFailureDetail = "brightness restore failed",
      verificationFailurePrefix = "Brightness restore verification failed",
      successDetail = "Brightness restored",
      matches = { it.panelBacklightPower == 0 && it.matchesRestoredStateLenient(restoreState, panelOnly = panelOnly) }
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
      if (after != null && matches(after)) {
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
      val result = rootExecutor.runScript(
        RootInputDeviceCapabilities.PER_NODE_DISCOVERY_COMMAND,
        timeout = 5.seconds
      )
      if (result.ok) {
        RootTouchDeviceDiscovery.parseTouchDevices(result.stdout)
      } else {
        emptyList()
      }
    }.getOrDefault(emptyList())
  }

  private suspend fun readPowerKeyDevices(): List<RootPowerKeyDevice> {
    return runCatching {
      val result = rootExecutor.runScript(
        RootInputDeviceCapabilities.PER_NODE_DISCOVERY_COMMAND,
        timeout = 5.seconds
      )
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
          if [ -w "${'$'}candidate/brightness" ] && [ -r "${'$'}candidate/max_brightness" ] && [ -w "${'$'}candidate/bl_power" ]; then
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
  private val powerButtonPolicy: TouchPowerButtonPolicyController =
    AndroidTouchPowerButtonPolicyController(context.applicationContext, rootExecutor),
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
    PhoneAutomationServiceBridge.configurePhysicalVisibility(enabled = true)
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
    val stoppingSession = sessionJob
    stoppingSession?.cancel()
    sessionJob = null
    stopJob?.cancel()

    val snapshot = settingsStore.load()
    if (!snapshot.touchBrightnessEnabled) PhoneAutomationServiceBridge.configurePhysicalVisibility(enabled = false)
    val shouldRestoreBrightness = !snapshot.touchBrightnessEnabled
    val restoreState = snapshot.touchBrightnessRestoreState()
    val newStopJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
      stoppingSession?.join()
      if (!snapshot.touchBrightnessEnabled && !powerButtonPolicy.restore()) {
        settingsStore.updateTouchBrightnessState(
          TouchBrightnessRuntimeState.ERROR, "Could not restore normal side-button behavior"
        )
        onSnapshotChanged(settingsStore.load())
        return@launch
      }
      powerController.releaseHold("runtime_stop:$reason")
      overlayController.hide()

      if (shouldRestoreBrightness) {
        val restore = if (restoreState != null) deviceController.restoreBrightnessState(restoreState)
          else deviceController.setBrightnessPercent(SAFE_VISIBLE_FALLBACK_PERCENT)
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
    check(powerButtonPolicy.acquire()) { "Could not reserve short side-button presses for panel sleep" }
    var interactive = false
    var activeTouchCount = 0
    var currentSource: RootTouchDevice? = null
    var currentPowerSource: RootPowerKeyDevice? = null
    var currentTouchSnapshot = RootTouchSnapshot()
    var visibleBrightnessState: ScreenBrightnessState? = null
    var internalMode = InternalTouchBrightnessMode.STARTING
    var idleDeadlineMillis: Long? = null
    var pendingNonTouchPanelWakeClamp = false
    var overlayPointerCount = 0

    var idleJob: Job? = null
    var panelSleepGuardJob: Job? = null
    var nonTouchPanelReassertJob: Job? = null
    var lastPhysicalTouchAtUptimeMillis: Long? = null
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

    fun physicalVisibilityActive(): Boolean =
      PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis > uptimeClock()

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
        append(" visible_remaining_ms=")
        append((PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis - uptimeClock()).coerceAtLeast(0L))
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
          TouchBrightnessRuntimeState.PANEL_SLEEP to "Panel brightness is zero; Android is held awake waiting for physical touch${sourceSuffix()}"
        }

        InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF -> {
          TouchBrightnessRuntimeState.SUSPENDED_SCREEN_OFF to "Suspended while the screen is off${sourceSuffix()}"
        }
      }
      settingsStore.updateTouchBrightnessState(state, detail)
      publishDebugState()
    }

    suspend fun cancelIdleJob() {
      val hadPendingTimer = idleDeadlineMillis != null
      idleJob?.cancelAndJoin()
      idleJob = null
      idleDeadlineMillis = null
      if (hadPendingTimer) {
        logTouchState("panel_sleep_timer_cancelled")
      }
    }

    suspend fun cancelPanelSleepGuardJob() {
      panelSleepGuardJob?.cancelAndJoin()
      panelSleepGuardJob = null
    }

    suspend fun cancelNonTouchPanelReassertJob() {
      nonTouchPanelReassertJob?.cancelAndJoin()
      nonTouchPanelReassertJob = null
    }

    suspend fun panelSleepBrightnessStillDark(): Boolean {
      val state = deviceController.readBrightnessState() ?: return false
      return state.panelBacklightPower == 4 && state.isPanelSleepBrightnessState()
    }

    fun ensurePanelSleepGuardJob() {
      if (!dimGuardEnabled || panelSleepGuardJob?.isActive == true) {
        return
      }
      panelSleepGuardJob = launch {
        while (true) {
          val guardDelayMillis = if (panelSleepGuardFailureCount > 0) {
            PANEL_DIM_GUARD_RETRY_INTERVAL_MILLIS
          } else {
            PANEL_DIM_GUARD_INTERVAL_MILLIS
          }
          delay(guardDelayMillis)
          if (
            internalMode != InternalTouchBrightnessMode.PANEL_SLEEP ||
            !interactive ||
            activeTouchCount > 0 ||
            overlayPointerCount > 0 || physicalVisibilityActive()
          ) {
            panelSleepGuardJob = null
            return@launch
          }
          powerController.holdScreen("panel_sleep_guard")
          if (panelSleepBrightnessStillDark()) {
            panelSleepGuardFailureCount = 0
            publishDebugState()
            logTouchState("panel_sleep_guard_verified")
            continue
          }
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

    fun shouldReassertPanelSleepForNonTouch(): Boolean =
      !physicalVisibilityActive() && internalMode in setOf(
        InternalTouchBrightnessMode.PANEL_SLEEP,
        InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF,
        InternalTouchBrightnessMode.BRIGHT_IDLE
      )

    suspend fun reassertPanelSleepBrightness(reason: String) {
      cancelIdleJob()
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

    suspend fun scheduleNonTouchPanelReassert(
      reason: String,
      touchBeginCount: Long,
      afterImmediateClamp: Boolean = false
    ) {
      cancelNonTouchPanelReassertJob()
      // Keep the event collector available for a complete down/up while root brightness work
      // is pending. Physical restore joins this job before making any visible brightness write.
      nonTouchPanelReassertJob = launch {
        if (physicalVisibilityActive() ||
          PhoneAutomationServiceBridge.currentRootPhysicalTouchState().touchBeginCount != touchBeginCount ||
          (!afterImmediateClamp &&
            (activeTouchCount > 0 || eventSource.currentTouchSnapshot()?.isRawTouchActive() == true))
        ) return@launch
        reassertPanelSleepBrightness(reason)
      }
    }

    suspend fun schedulePanelSleepFrom(observedAtUptimeMillis: Long) {
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
        // Retain ownership through the dark write. A physical touch must be able to cancel and
        // join an expired timer's work, not just its initial delay. Next scheduling replaces it.
        idleDeadlineMillis = null
        if (!interactive || activeTouchCount > 0 || physicalVisibilityActive()) {
          publishDebugState()
          return@launch
        }
        powerController.holdScreen("panel_sleep_timer_fired")
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

    suspend fun enterPanelSleepImmediately(reason: String) {
      val immediate = deviceController.clampPanelSleepImmediately()
      if (!immediate.success) {
        logTouchState("${reason}_immediate_clamp_unproved")
      }
      if (immediate.success) {
        powerController.holdScreen(reason)
        internalMode = InternalTouchBrightnessMode.PANEL_SLEEP
        publishCurrentState()
      }
      // Complete state bookkeeping in the owned writer. Physical restore joins it
      // before unblanking, including when a side press ended a held contact.
      scheduleNonTouchPanelReassert(
        reason,
        PhoneAutomationServiceBridge.currentRootPhysicalTouchState().touchBeginCount,
        afterImmediateClamp = true
      )
    }

    suspend fun restoreVisibleWhileAllowed(): Boolean = coroutineScope {
      val restore = async { applyVisibleBrightnessState(visibleBrightnessState) }
      while (true) {
        if (!physicalVisibilityActive()) {
          // Await exact root-writer cleanup before zeroing; an old visible hold must not rebound.
          restore.cancelAndJoin()
          enterPanelSleepImmediately("visibility_revoked_during_restore")
          return@coroutineScope false
        }
        val result = withTimeoutOrNull(20L) { restore.await() } ?: continue
        if (!physicalVisibilityActive()) {
          restore.join()
          enterPanelSleepImmediately("visibility_revoked_after_restore")
          return@coroutineScope false
        }
        check(result.success) { "Could not set bright mode: ${result.detail}" }
        return@coroutineScope true
      }
      @Suppress("UNREACHABLE_CODE")
      false
    }

    suspend fun enterBrightIdle(
      timerStartUptimeMillis: Long,
      reason: String
    ) {
      cancelNonTouchPanelReassertJob()
      cancelIdleJob()
      cancelPanelSleepGuardJob()
      powerController.holdScreen(reason)
      check(PhoneAutomationServiceBridge.awaitPhysicalVisibilityUnblocked()) {
        "Could not stop Ticket panel-dark writers before $reason"
      }
      if (!physicalVisibilityActive()) {
        enterPanelSleepImmediately("visibility_revoked_during_restore")
        return
      }
      val overlayHidden = overlayController.hide()
      if (!overlayHidden.success) {
        throw IllegalStateException(overlayHidden.detail)
      }
      overlayPointerCount = 0
      if (!restoreVisibleWhileAllowed()) return
      internalMode = InternalTouchBrightnessMode.BRIGHT_IDLE
      schedulePanelSleepFrom(timerStartUptimeMillis)
      publishCurrentState()
      logTouchState(reason)
    }

    suspend fun enterBrightTouchActive() {
      cancelNonTouchPanelReassertJob()
      cancelIdleJob()
      cancelPanelSleepGuardJob()
      powerController.holdScreen("touch_active")
      check(PhoneAutomationServiceBridge.awaitPhysicalVisibilityUnblocked()) {
        "Could not stop Ticket panel-dark writers before physical touch"
      }
      if (!physicalVisibilityActive()) {
        enterPanelSleepImmediately("visibility_revoked_during_restore")
        return
      }
      if (internalMode != InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE) {
        panelSleepGuardFailureCount = 0
        val overlayHidden = overlayController.hide()
        if (!overlayHidden.success) {
          throw IllegalStateException(overlayHidden.detail)
        }
        overlayPointerCount = 0
        if (!restoreVisibleWhileAllowed()) return
      }
      internalMode = InternalTouchBrightnessMode.BRIGHT_TOUCH_ACTIVE
      publishCurrentState()
      logTouchState("touch_active")
    }

    suspend fun enterSuspendedScreenOff() {
      cancelNonTouchPanelReassertJob()
      cancelIdleJob()
      cancelPanelSleepGuardJob()
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

    val startupRestoreState = startupSnapshot.touchBrightnessRestoreState()
    if (
      startupSnapshot.touchBrightnessEnabled &&
      !physicalVisibilityActive() &&
      startupRestoreState != null &&
      !startupRestoreState.isPanelSleepBrightnessState()
    ) {
      val restartClamp = deviceController.setBrightnessPercent(PANEL_SLEEP_PERCENT)
      if (!restartClamp.success) {
        val detail = "Panel sleep restart clamp will retry after readiness preparation: ${restartClamp.detail}"
        runCatching { Log.w(TAG, detail) }
        settingsStore.updateTouchBrightnessState(TouchBrightnessRuntimeState.STARTING, detail)
        onSnapshotChanged(settingsStore.load())
      }
    }
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
    PhoneAutomationServiceBridge.recordRootPhysicalTouchState(
      active = activeTouchCount > 0 && currentTouchSnapshot.isRawTouchActive(),
      observedAtUptimeMillis = currentTouchSnapshot.lastEventUptimeMillis.takeIf { it > 0L }
        ?: uptimeClock(),
      available = currentTouchSnapshot.selectedDevice != null
    )

    if (!interactive) {
      enterSuspendedScreenOff()
    } else if (activeTouchCount > 0 && physicalVisibilityActive()) {
      enterBrightTouchActive()
    } else if (physicalVisibilityActive()) {
      enterBrightIdle(
        PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis - IDLE_PANEL_SLEEP_DELAY_MILLIS,
        "restart_preserves_physical_visible_window"
      )
    } else {
      activeTouchCount = 0
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
            if (physicalTouchStarted || physicalTouchReleased) {
              PhoneAutomationServiceBridge.recordPhysicalVisibilityTouch(
                observedAtUptimeMillis = event.observedAtUptimeMillis,
                active = physicalTouchStarted
              )
            }
            // The input collector can deliver a DOWN buffered before a side-button revocation.
            // The bridge owns admission; a rejected old contact must not restore brightness here.
            if (physicalTouchStarted && !physicalVisibilityActive()) {
              activeTouchCount = 0
              overlayPointerCount = 0
              publishDebugState()
              logTouchState("revoked_physical_touch_ignored")
              return@collect
            }
            if (physicalTouchStarted) {
              pendingNonTouchPanelWakeClamp = false
              lastPhysicalTouchAtUptimeMillis = event.observedAtUptimeMillis
            }
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
                scheduleNonTouchPanelReassert(
                  "non_touch_input_panel_sleep_reasserted",
                  PhoneAutomationServiceBridge.currentRootPhysicalTouchState().touchBeginCount
                )
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
                if (physicalVisibilityActive()) {
                  enterBrightIdle(event.observedAtUptimeMillis, "touch_released_visible_idle")
                } else {
                  reassertPanelSleepBrightness("side_button_touch_release_stays_dark")
                }
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
              if (physicalVisibilityActive()) {
                pendingNonTouchPanelWakeClamp = false
                val wake = powerController.forceWakeScreen("physical_visible_window_screen_off")
                if (!wake.success) throw IllegalStateException("Could not keep physical observation visible: ${wake.detail}")
                interactive = true
                enterBrightIdle(
                  PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis - IDLE_PANEL_SLEEP_DELAY_MILLIS,
                  "physical_visible_window_screen_off"
                )
              } else if (internalMode == InternalTouchBrightnessMode.PANEL_SLEEP) {
                val clampBeforeWake = deviceController.clampPanelSleepForWake()
                if (!clampBeforeWake.success) {
                  throw IllegalStateException("Could not clamp panel sleep before non-touch wake: ${clampBeforeWake.detail}")
                }
                pendingNonTouchPanelWakeClamp = true
                val wake = powerController.forceWakeScreen("panel_sleep_non_touch_screen_off")
                if (!wake.success) {
                  pendingNonTouchPanelWakeClamp = false
                  throw IllegalStateException("Could not recover non-touch screen off: ${wake.detail}")
                }
                interactive = true
                val reassertReason = "screen_off_non_touch_panel_sleep_reasserted"
                reassertPanelSleepBrightness(reassertReason)
              } else {
                pendingNonTouchPanelWakeClamp = false
                enterSuspendedScreenOff()
              }
            } else {
              val nonTouchPanelWakePending = pendingNonTouchPanelWakeClamp
              pendingNonTouchPanelWakeClamp = false
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
                activeTouchCount == 0 && !physicalVisibilityActive() &&
                (nonTouchInputSuppressed || nonTouchPanelWakePending) &&
                (
                  internalMode == InternalTouchBrightnessMode.PANEL_SLEEP ||
                    internalMode == InternalTouchBrightnessMode.SUSPENDED_SCREEN_OFF
                  )
              ) {
                val reassertReason = "screen_on_non_touch_panel_sleep_reasserted"
                reassertPanelSleepBrightness(reassertReason)
              } else if (activeTouchCount > 0) {
                enterBrightTouchActive()
              } else if (physicalVisibilityActive()) {
                enterBrightIdle(
                  PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis - IDLE_PANEL_SLEEP_DELAY_MILLIS,
                  "screen_on_visible_window"
                )
              } else {
                reassertPanelSleepBrightness("screen_on_without_physical_touch_stays_dark")
              }
            }
          }

          is TouchBrightnessEvent.NonTouchInput -> {
            if (physicalVisibilityActive()) {
              publishDebugState()
              logTouchState("non_touch_input_preserves_physical_visible_window")
              return@collect
            }
            val physicalTouch = PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
            if (event.touchBeginCount?.let { it != physicalTouch.touchBeginCount } == true ||
              lastPhysicalTouchAtUptimeMillis?.let { event.observedAtUptimeMillis < it } == true
            ) {
              logTouchState("non_touch_input_stale_after_physical_touch:${event.reason}")
              return@collect
            }
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
            if (interactive && shouldReassertPanelSleepForNonTouch()) {
              scheduleNonTouchPanelReassert(
                "non_touch_input_panel_sleep_reasserted",
                event.touchBeginCount ?: physicalTouch.touchBeginCount
              )
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
            PhoneAutomationServiceBridge.revokePhysicalVisibility(event.observedAtUptimeMillis)
            pendingNonTouchPanelWakeClamp = false
            currentPowerSource = event.device ?: currentPowerSource
            cancelNonTouchPanelReassertJob()
            cancelIdleJob()
            cancelPanelSleepGuardJob()
            activeTouchCount = 0
            overlayPointerCount = 0
            currentTouchSnapshot = currentTouchSnapshot.copy(
              activeTouchCount = 0, btnTouchActive = false, toolFingerActive = false, activeSlotCount = 0
            )
            if (!interactive) {
              val clamp = deviceController.clampPanelSleepForWake()
              check(clamp.success) { "Could not darken before side-button wake: ${clamp.detail}" }
              val wake = powerController.forceWakeScreen("side_button_panel_sleep")
              check(wake.success) { "Could not keep Android awake for panel sleep: ${wake.detail}" }
              interactive = true
            }
            enterPanelSleepImmediately("physical_side_button_panel_sleep")
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
      withContext(NonCancellable) {
        cancelNonTouchPanelReassertJob()
        cancelIdleJob()
        cancelPanelSleepGuardJob()
        powerController.releaseHold("session_finished")
        eventJob.cancelAndJoin()
      }
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
    if (panelBacklightPower == 4) return true
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
    internal const val IDLE_PANEL_SLEEP_DELAY_MILLIS = 90_000L
    internal const val PANEL_DIM_GUARD_INTERVAL_MILLIS = 30_000L
    internal const val PANEL_DIM_GUARD_RETRY_INTERVAL_MILLIS = 500L
    internal const val PANEL_DIM_GUARD_FAILURE_LIMIT = 5
    internal const val SESSION_RETRY_INITIAL_DELAY_MILLIS = 1_000L
    internal const val SESSION_RETRY_MAX_DELAY_MILLIS = 15_000L
    private const val AUTOMATIC_BRIGHTNESS_MODE_VALUE = 1
    private const val PANEL_SLEEP_DISPLAY_PERCENT_TOLERANCE = 0.5f
    private const val PANEL_SLEEP_PANEL_VALUE_TOLERANCE = 2
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
