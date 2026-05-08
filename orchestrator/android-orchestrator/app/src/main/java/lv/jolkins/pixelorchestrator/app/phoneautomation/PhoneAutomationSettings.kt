package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable

enum class PhoneAutomationMode(
  val wireName: String,
  val displayName: String
) {
  SPEEDTEST_ONLY("speedtest_only", "Speedtest only"),
  SPEEDTEST_AND_CELLMAPPER("speedtest_and_cellmapper", "Speedtest + CellMapper");

  val maintainsCellMapper: Boolean
    get() = this == SPEEDTEST_AND_CELLMAPPER
}

@Serializable
enum class PhoneAutomationDispatchInterval(
  val wireName: String,
  val sliderLabel: String,
  val summaryLabel: String,
  val intervalMillis: Long,
  val sliderIndex: Int
) {
  IMMEDIATE("immediate", "On completion", "immediately on completion", 0L, 0),
  EVERY_60_SECONDS("60_seconds", "1 min", "every 1 min", 60_000L, 1),
  EVERY_90_SECONDS("90_seconds", "90 sec", "every 90 sec", 90_000L, 2),
  EVERY_2_MINUTES("2_minutes", "2 min", "every 2 min", 120_000L, 3),
  EVERY_3_MINUTES("3_minutes", "3 min", "every 3 min", 180_000L, 4),
  EVERY_5_MINUTES("5_minutes", "5 min", "every 5 min", 300_000L, 5),
  EVERY_10_MINUTES("10_minutes", "10 min", "every 10 min", 600_000L, 6),
  EVERY_15_MINUTES("15_minutes", "15 min", "every 15 min", 900_000L, 7);

  companion object {
    val default: PhoneAutomationDispatchInterval = EVERY_60_SECONDS

    fun fromWireName(value: String?): PhoneAutomationDispatchInterval {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: default
    }

    fun fromSliderIndex(value: Int): PhoneAutomationDispatchInterval {
      return entries.firstOrNull { it.sliderIndex == value } ?: default
    }
  }
}

@Serializable
enum class SpeedtestActivityState(
  val wireName: String
) {
  UNKNOWN("unknown"),
  RUNNING("running"),
  NOT_RUNNING("not_running");

  companion object {
    fun fromWireName(value: String?): SpeedtestActivityState {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: UNKNOWN
    }
  }
}

@Serializable
enum class CellMapperRecordingState(
  val wireName: String
) {
  UNKNOWN("unknown"),
  ACTIVE("active"),
  INACTIVE("inactive");

  companion object {
    fun fromWireName(value: String?): CellMapperRecordingState {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: UNKNOWN
    }
  }
}

@Serializable
enum class PhoneAutomationPendingRecoveryAction(
  val wireName: String
) {
  NONE("none"),
  START("start"),
  RESTART("restart");

  companion object {
    fun fromWireName(value: String?): PhoneAutomationPendingRecoveryAction {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: NONE
    }
  }
}

@Serializable
enum class PhoneAutomationPendingRecoveryPhase(
  val wireName: String
) {
  NONE("none"),
  HANDOFF_RESUME("handoff_resume"),
  QUEUED_RETRY("queued_retry");

  companion object {
    fun fromWireName(value: String?): PhoneAutomationPendingRecoveryPhase {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: NONE
    }
  }
}

@Serializable
enum class SpeedtestRunLaunchMode(
  val wireName: String
) {
  NONE("none"),
  WARM_IN_APP("warm_in_app"),
  COLD_FRESH_LAUNCH("cold_fresh_launch");

  companion object {
    fun fromWireName(value: String?): SpeedtestRunLaunchMode {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: NONE
    }
  }
}

@Serializable
data class PhoneAutomationSettingsSnapshot(
  val enabled: Boolean = false,
  val maintainCellMapper: Boolean = true,
  val returnToOrchestratorAfterForegroundWork: Boolean = true,
  val dispatchInterval: PhoneAutomationDispatchInterval = PhoneAutomationDispatchInterval.default,
  val touchBrightnessEnabled: Boolean = false,
  val touchBrightnessState: TouchBrightnessRuntimeState = TouchBrightnessRuntimeState.DISABLED,
  val touchBrightnessDetail: String = TouchBrightnessRuntimeState.DISABLED.defaultDetail,
  val touchBrightnessDebugDetail: String = "",
  val touchBrightnessRestoreMode: Int? = null,
  val touchBrightnessRestoreValue: Int? = null,
  val setupState: PhoneAutomationSetupState = PhoneAutomationSetupState.UNKNOWN,
  val setupDetail: String = PhoneAutomationSetupState.UNKNOWN.defaultDetail,
  val runtimeState: PhoneAutomationRuntimeState = PhoneAutomationRuntimeState.DISABLED,
  val runtimeDetail: String = PhoneAutomationRuntimeState.DISABLED.defaultDetail,
  val lastRunStartedAtMillis: Long = 0L,
  val lastCompletionNotificationAtMillis: Long = 0L,
  val lastResultReadyAtMillis: Long = 0L,
  val lastHandledCompletionAtMillis: Long = 0L,
  val currentRunLaunchMode: SpeedtestRunLaunchMode = SpeedtestRunLaunchMode.NONE,
  val lastAcceptedResultFingerprint: String = "",
  val currentAttemptId: String = "",
  val currentAttemptStartProofAtMillis: Long = 0L,
  val currentAttemptResultScreenClearedAtMillis: Long = 0L,
  val speedtestState: SpeedtestActivityState = SpeedtestActivityState.UNKNOWN,
  val cellMapperState: CellMapperRecordingState = CellMapperRecordingState.UNKNOWN,
  val protectedHandoffStartedAtMillis: Long = 0L,
  val pendingRecoveryAction: PhoneAutomationPendingRecoveryAction = PhoneAutomationPendingRecoveryAction.NONE,
  val pendingRecoveryPhase: PhoneAutomationPendingRecoveryPhase = PhoneAutomationPendingRecoveryPhase.NONE,
  val pendingRecoveryReason: String = "",
  val pendingRecoveryNotBeforeAtMillis: Long = 0L,
  val pendingRecoveryToken: String = "",
  val lastReadyAtMillis: Long = 0L,
  val transientFailureCount: Int = 0,
  val lastTransientFailureAtMillis: Long = 0L,
  val runtimeErrorRetryAtMillis: Long = 0L,
  val updatedAtMillis: Long = 0L
) {
  fun mode(): PhoneAutomationMode {
    return if (maintainCellMapper) {
      PhoneAutomationMode.SPEEDTEST_AND_CELLMAPPER
    } else {
      PhoneAutomationMode.SPEEDTEST_ONLY
    }
  }

  fun cadenceSummary(): String = dispatchInterval.summaryLabel

  private fun automationSummaryPrefix(): String = "${mode().displayName} ${cadenceSummary()}"

  fun notificationSummary(): String {
    return when {
      !enabled -> "off"
      else -> "${automationSummaryPrefix()}: ${runtimeSummary()}"
    }
  }

  fun touchBrightnessNotificationSummary(): String {
    return when {
      !touchBrightnessEnabled -> "off"
      else -> touchBrightnessRuntimeSummary()
    }
  }

  fun setupSummary(): String {
    val detail = setupDetail.ifBlank { setupState.defaultDetail }
    return if (enabled) {
      "${automationSummaryPrefix()}: $detail"
    } else {
      detail
    }
  }

  fun runtimeSummary(): String = runtimeDetail.ifBlank { runtimeState.defaultDetail }

  fun touchBrightnessRuntimeSummary(): String {
    return touchBrightnessDetail.ifBlank { touchBrightnessState.defaultDetail }
  }
}

internal fun PhoneAutomationSettingsSnapshot.isProtectedSpeedtestHandoffInProgress(): Boolean {
  return enabled &&
    protectedHandoffStartedAtMillis > 0L &&
    (runtimeState == PhoneAutomationRuntimeState.STARTING ||
      runtimeState == PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST)
}

internal fun PhoneAutomationSettingsSnapshot.hasProtectedSpeedtestHandoffTimedOut(
  nowMillis: Long,
  timeoutMillis: Long
): Boolean {
  return isProtectedSpeedtestHandoffInProgress() &&
    protectedHandoffStartedAtMillis > 0L &&
    nowMillis - protectedHandoffStartedAtMillis >= timeoutMillis
}

internal fun PhoneAutomationSettingsSnapshot.hasReachedReadyState(): Boolean {
  return lastReadyAtMillis > 0L
}

internal fun PhoneAutomationSettingsSnapshot.hasQueuedPendingRecovery(): Boolean {
  return pendingRecoveryAction != PhoneAutomationPendingRecoveryAction.NONE &&
    pendingRecoveryPhase == PhoneAutomationPendingRecoveryPhase.QUEUED_RETRY &&
    pendingRecoveryNotBeforeAtMillis > 0L
}

internal fun PhoneAutomationSettingsSnapshot.hasPendingRecoveryWork(): Boolean {
  return pendingRecoveryAction != PhoneAutomationPendingRecoveryAction.NONE &&
    pendingRecoveryPhase != PhoneAutomationPendingRecoveryPhase.NONE
}

@Serializable
enum class PhoneAutomationSetupState(
  val wireName: String,
  val defaultDetail: String
) {
  UNKNOWN("unknown", "Not checked yet"),
  READY("ready", "Ready"),
  SETUP_BLOCKED("setup_blocked", "Setup blocked");

  companion object {
    fun fromWireName(value: String?): PhoneAutomationSetupState {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: UNKNOWN
    }
  }
}

@Serializable
enum class PhoneAutomationRuntimeState(
  val wireName: String,
  val defaultDetail: String
) {
  DISABLED("disabled", "Feature is off"),
  STARTING("starting", "Starting automation"),
  WAITING_FOR_SPEEDTEST_COMPLETION("waiting_for_speedtest_completion", "Waiting for Speedtest completion"),
  WAITING_FOR_SPEEDTEST_RESULT("waiting_for_speedtest_result", "Waiting for Speedtest result"),
  WAITING_FOR_RECOVERY_RETRY("waiting_for_recovery_retry", "Recovering from Speedtest failure"),
  WAITING_FOR_NEXT_DISPATCH("waiting_for_next_dispatch", "Waiting for next dispatch"),
  RESTARTING_SPEEDTEST("restarting_speedtest", "Restarting Speedtest"),
  RECOVERING_CELLMAPPER("recovering_cellmapper", "Recovering CellMapper"),
  SETUP_BLOCKED("setup_blocked", "Setup blocked"),
  STOPPED("stopped", "Stopped"),
  ERROR("error", "Automation error");

  companion object {
    fun fromWireName(value: String?): PhoneAutomationRuntimeState {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: DISABLED
    }
  }
}

@Serializable
enum class TouchBrightnessRuntimeState(
  val wireName: String,
  val defaultDetail: String
) {
  DISABLED("disabled", "Feature is off"),
  STARTING("starting", "Starting touch brightness"),
  BRIGHT("bright", "Saved brightness is active"),
  PANEL_SLEEP("panel_sleep", "Panel brightness is zero while Android stays awake"),
  @Deprecated("Use PANEL_SLEEP; kept only so older source references still compile.")
  BLACKOUT_IDLE("blackout_idle", "Panel brightness is zero while Android stays awake"),
  SUSPENDED_SCREEN_OFF("suspended_screen_off", "Suspended while the screen is off"),
  STOPPED("stopped", "Stopped"),
  ERROR("error", "Touch brightness error");

  companion object {
    fun fromWireName(value: String?): TouchBrightnessRuntimeState {
      val normalized = value?.trim()?.lowercase()
      return when (normalized) {
        "dimmed", "blackout_idle" -> PANEL_SLEEP
        else -> entries.firstOrNull { it.wireName == normalized } ?: DISABLED
      }
    }
  }
}

interface PhoneAutomationSettingsStore {
  fun load(): PhoneAutomationSettingsSnapshot
  fun setEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot
  fun setMaintainCellMapper(maintainCellMapper: Boolean): PhoneAutomationSettingsSnapshot
  fun setReturnToOrchestratorAfterForegroundWork(
    returnToOrchestratorAfterForegroundWork: Boolean
  ): PhoneAutomationSettingsSnapshot
  fun setDispatchInterval(dispatchInterval: PhoneAutomationDispatchInterval): PhoneAutomationSettingsSnapshot
  fun setTouchBrightnessEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot
  fun updateSetupState(
    state: PhoneAutomationSetupState,
    detail: String = state.defaultDetail
  ): PhoneAutomationSettingsSnapshot
  fun updateRuntimeState(
    state: PhoneAutomationRuntimeState,
    detail: String = state.defaultDetail
  ): PhoneAutomationSettingsSnapshot
  fun updateTouchBrightnessState(
    state: TouchBrightnessRuntimeState,
    detail: String = state.defaultDetail
  ): PhoneAutomationSettingsSnapshot
  fun updateTouchBrightnessDebugDetail(detail: String): PhoneAutomationSettingsSnapshot
  fun saveTouchBrightnessRestoreState(
    mode: Int?,
    value: Int?
  ): PhoneAutomationSettingsSnapshot
  fun clearTouchBrightnessRestoreState(): PhoneAutomationSettingsSnapshot
  fun updateCycleState(
    lastRunStartedAtMillis: Long,
    lastCompletionNotificationAtMillis: Long,
    lastResultReadyAtMillis: Long,
    lastHandledCompletionAtMillis: Long,
    currentRunLaunchMode: SpeedtestRunLaunchMode,
    lastAcceptedResultFingerprint: String,
    speedtestState: SpeedtestActivityState,
    cellMapperState: CellMapperRecordingState,
    pendingRecoveryReason: String,
    currentAttemptId: String = "",
    currentAttemptStartProofAtMillis: Long = 0L,
    currentAttemptResultScreenClearedAtMillis: Long = 0L
  ): PhoneAutomationSettingsSnapshot
  fun updatePendingRecovery(
    action: PhoneAutomationPendingRecoveryAction,
    reason: String,
    phase: PhoneAutomationPendingRecoveryPhase = if (
      action == PhoneAutomationPendingRecoveryAction.NONE
    ) {
      PhoneAutomationPendingRecoveryPhase.NONE
    } else {
      PhoneAutomationPendingRecoveryPhase.QUEUED_RETRY
    },
    notBeforeAtMillis: Long = 0L,
    token: String = ""
  ): PhoneAutomationSettingsSnapshot
  fun clearCycleState(): PhoneAutomationSettingsSnapshot
  fun recordTransientFailure(
    reason: String,
    observedAtMillis: Long = System.currentTimeMillis()
  ): PhoneAutomationSettingsSnapshot
  fun clearTransientFailureTracking(): PhoneAutomationSettingsSnapshot
}

class NotifyingPhoneAutomationSettingsStore(
  private val delegate: PhoneAutomationSettingsStore,
  private val onChanged: (PhoneAutomationSettingsSnapshot) -> Unit
) : PhoneAutomationSettingsStore {
  override fun load(): PhoneAutomationSettingsSnapshot = delegate.load()

  override fun setEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    return delegate.setEnabled(enabled).also(onChanged)
  }

  override fun setMaintainCellMapper(maintainCellMapper: Boolean): PhoneAutomationSettingsSnapshot {
    return delegate.setMaintainCellMapper(maintainCellMapper).also(onChanged)
  }

  override fun setReturnToOrchestratorAfterForegroundWork(
    returnToOrchestratorAfterForegroundWork: Boolean
  ): PhoneAutomationSettingsSnapshot {
    return delegate
      .setReturnToOrchestratorAfterForegroundWork(returnToOrchestratorAfterForegroundWork)
      .also(onChanged)
  }

  override fun setDispatchInterval(dispatchInterval: PhoneAutomationDispatchInterval): PhoneAutomationSettingsSnapshot {
    return delegate.setDispatchInterval(dispatchInterval).also(onChanged)
  }

  override fun setTouchBrightnessEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    return delegate.setTouchBrightnessEnabled(enabled).also(onChanged)
  }

  override fun updateSetupState(
    state: PhoneAutomationSetupState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    return delegate.updateSetupState(state, detail).also(onChanged)
  }

  override fun updateRuntimeState(
    state: PhoneAutomationRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    return delegate.updateRuntimeState(state, detail).also(onChanged)
  }

  override fun updateTouchBrightnessState(
    state: TouchBrightnessRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    return delegate.updateTouchBrightnessState(state, detail).also(onChanged)
  }

  override fun updateTouchBrightnessDebugDetail(detail: String): PhoneAutomationSettingsSnapshot {
    return delegate.updateTouchBrightnessDebugDetail(detail).also(onChanged)
  }

  override fun saveTouchBrightnessRestoreState(
    mode: Int?,
    value: Int?
  ): PhoneAutomationSettingsSnapshot {
    return delegate.saveTouchBrightnessRestoreState(mode, value).also(onChanged)
  }

  override fun clearTouchBrightnessRestoreState(): PhoneAutomationSettingsSnapshot {
    return delegate.clearTouchBrightnessRestoreState().also(onChanged)
  }

  override fun updateCycleState(
    lastRunStartedAtMillis: Long,
    lastCompletionNotificationAtMillis: Long,
    lastResultReadyAtMillis: Long,
    lastHandledCompletionAtMillis: Long,
    currentRunLaunchMode: SpeedtestRunLaunchMode,
    lastAcceptedResultFingerprint: String,
    speedtestState: SpeedtestActivityState,
    cellMapperState: CellMapperRecordingState,
    pendingRecoveryReason: String,
    currentAttemptId: String,
    currentAttemptStartProofAtMillis: Long,
    currentAttemptResultScreenClearedAtMillis: Long
  ): PhoneAutomationSettingsSnapshot {
    return delegate.updateCycleState(
      lastRunStartedAtMillis = lastRunStartedAtMillis,
      lastCompletionNotificationAtMillis = lastCompletionNotificationAtMillis,
      lastResultReadyAtMillis = lastResultReadyAtMillis,
      lastHandledCompletionAtMillis = lastHandledCompletionAtMillis,
      currentRunLaunchMode = currentRunLaunchMode,
      lastAcceptedResultFingerprint = lastAcceptedResultFingerprint,
      speedtestState = speedtestState,
      cellMapperState = cellMapperState,
      pendingRecoveryReason = pendingRecoveryReason,
      currentAttemptId = currentAttemptId,
      currentAttemptStartProofAtMillis = currentAttemptStartProofAtMillis,
      currentAttemptResultScreenClearedAtMillis = currentAttemptResultScreenClearedAtMillis
    ).also(onChanged)
  }

  override fun updatePendingRecovery(
    action: PhoneAutomationPendingRecoveryAction,
    reason: String,
    phase: PhoneAutomationPendingRecoveryPhase,
    notBeforeAtMillis: Long,
    token: String
  ): PhoneAutomationSettingsSnapshot {
    return delegate.updatePendingRecovery(action, reason, phase, notBeforeAtMillis, token).also(onChanged)
  }

  override fun clearCycleState(): PhoneAutomationSettingsSnapshot {
    return delegate.clearCycleState().also(onChanged)
  }

  override fun recordTransientFailure(
    reason: String,
    observedAtMillis: Long
  ): PhoneAutomationSettingsSnapshot {
    return delegate.recordTransientFailure(reason, observedAtMillis).also(onChanged)
  }

  override fun clearTransientFailureTracking(): PhoneAutomationSettingsSnapshot {
    return delegate.clearTransientFailureTracking().also(onChanged)
  }
}

internal interface PhoneAutomationPreferencesBackend {
  fun contains(key: String): Boolean
  fun getBoolean(key: String, defaultValue: Boolean): Boolean
  fun getString(key: String, defaultValue: String): String
  fun getLong(key: String, defaultValue: Long): Long
  fun applyMutations(mutations: Map<String, Any>)
}

internal class SharedPreferencesPhoneAutomationBackend(
  private val sharedPreferences: SharedPreferences
) : PhoneAutomationPreferencesBackend {
  override fun contains(key: String): Boolean {
    return sharedPreferences.contains(key)
  }

  override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
    return sharedPreferences.getBoolean(key, defaultValue)
  }

  override fun getString(key: String, defaultValue: String): String {
    return sharedPreferences.getString(key, defaultValue).orEmpty().ifBlank { defaultValue }
  }

  override fun getLong(key: String, defaultValue: Long): Long {
    return sharedPreferences.getLong(key, defaultValue)
  }

  override fun applyMutations(mutations: Map<String, Any>) {
    sharedPreferences.edit(commit = true) {
      mutations.forEach { (key, value) ->
        when (value) {
          is Boolean -> putBoolean(key, value)
          is String -> putString(key, value)
          is Long -> putLong(key, value)
          else -> error("Unsupported preferences value for $key: ${value::class.java.name}")
        }
      }
    }
  }
}

class PhoneAutomationPreferencesStore internal constructor(
  private val backend: PhoneAutomationPreferencesBackend
) : PhoneAutomationSettingsStore {

  constructor(context: Context) : this(
    SharedPreferencesPhoneAutomationBackend(
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )
  )

  override fun load(): PhoneAutomationSettingsSnapshot {
    val maintainCellMapper = if (backend.contains(KEY_MAINTAIN_CELLMAPPER)) {
      backend.getBoolean(KEY_MAINTAIN_CELLMAPPER, true)
    } else {
      true
    }
    val returnToOrchestratorAfterForegroundWork =
      if (backend.contains(KEY_RETURN_TO_ORCHESTRATOR_AFTER_FOREGROUND_WORK)) {
        backend.getBoolean(KEY_RETURN_TO_ORCHESTRATOR_AFTER_FOREGROUND_WORK, true)
      } else {
        true
      }
    val pendingRecoveryAction = PhoneAutomationPendingRecoveryAction.fromWireName(
      backend.getString(KEY_PENDING_RECOVERY_ACTION, PhoneAutomationPendingRecoveryAction.NONE.wireName)
    )
    val pendingRecoveryNotBeforeAtMillis = backend.getLong(KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS, 0L)
    val inferredPendingRecoveryPhase = when {
      pendingRecoveryAction == PhoneAutomationPendingRecoveryAction.NONE -> {
        PhoneAutomationPendingRecoveryPhase.NONE
      }

      pendingRecoveryNotBeforeAtMillis > 0L -> {
        PhoneAutomationPendingRecoveryPhase.QUEUED_RETRY
      }

      else -> {
        PhoneAutomationPendingRecoveryPhase.HANDOFF_RESUME
      }
    }
    return PhoneAutomationSettingsSnapshot(
      enabled = backend.getBoolean(KEY_ENABLED, false),
      maintainCellMapper = maintainCellMapper,
      returnToOrchestratorAfterForegroundWork = returnToOrchestratorAfterForegroundWork,
      dispatchInterval = PhoneAutomationDispatchInterval.fromWireName(
        backend.getString(KEY_DISPATCH_INTERVAL, PhoneAutomationDispatchInterval.default.wireName)
      ),
      touchBrightnessEnabled = backend.getBoolean(KEY_TOUCH_BRIGHTNESS_ENABLED, false),
      touchBrightnessState = TouchBrightnessRuntimeState.fromWireName(
        backend.getString(KEY_TOUCH_BRIGHTNESS_STATE, TouchBrightnessRuntimeState.DISABLED.wireName)
      ),
      touchBrightnessDetail = backend.getString(
        KEY_TOUCH_BRIGHTNESS_DETAIL,
        TouchBrightnessRuntimeState.DISABLED.defaultDetail
      ),
      touchBrightnessDebugDetail = backend.getString(KEY_TOUCH_BRIGHTNESS_DEBUG_DETAIL, ""),
      touchBrightnessRestoreMode = backend.getLong(KEY_TOUCH_BRIGHTNESS_RESTORE_MODE, CLEAR_SENTINEL)
        .takeIf { it != CLEAR_SENTINEL }
        ?.toInt(),
      touchBrightnessRestoreValue = backend.getLong(KEY_TOUCH_BRIGHTNESS_RESTORE_VALUE, CLEAR_SENTINEL)
        .takeIf { it != CLEAR_SENTINEL }
        ?.toInt(),
      setupState = PhoneAutomationSetupState.fromWireName(
        backend.getString(KEY_SETUP_STATE, PhoneAutomationSetupState.UNKNOWN.wireName)
      ),
      setupDetail = backend.getString(KEY_SETUP_DETAIL, PhoneAutomationSetupState.UNKNOWN.defaultDetail),
      runtimeState = PhoneAutomationRuntimeState.fromWireName(
        backend.getString(KEY_RUNTIME_STATE, PhoneAutomationRuntimeState.DISABLED.wireName)
      ),
      runtimeDetail = backend.getString(KEY_RUNTIME_DETAIL, PhoneAutomationRuntimeState.DISABLED.defaultDetail),
      lastRunStartedAtMillis = backend.getLong(KEY_LAST_RUN_STARTED_AT_MILLIS, 0L),
      lastCompletionNotificationAtMillis = backend.getLong(KEY_LAST_COMPLETION_NOTIFICATION_AT_MILLIS, 0L),
      lastResultReadyAtMillis = backend.getLong(KEY_LAST_RESULT_READY_AT_MILLIS, 0L),
      lastHandledCompletionAtMillis = backend.getLong(KEY_LAST_HANDLED_COMPLETION_AT_MILLIS, 0L),
      currentRunLaunchMode = SpeedtestRunLaunchMode.fromWireName(
        backend.getString(KEY_CURRENT_RUN_LAUNCH_MODE, SpeedtestRunLaunchMode.NONE.wireName)
      ),
      lastAcceptedResultFingerprint = backend.getString(KEY_LAST_ACCEPTED_RESULT_FINGERPRINT, ""),
      currentAttemptId = backend.getString(KEY_CURRENT_ATTEMPT_ID, ""),
      currentAttemptStartProofAtMillis = backend.getLong(KEY_CURRENT_ATTEMPT_START_PROOF_AT_MILLIS, 0L),
      currentAttemptResultScreenClearedAtMillis = backend.getLong(
        KEY_CURRENT_ATTEMPT_RESULT_SCREEN_CLEARED_AT_MILLIS,
        0L
      ),
      speedtestState = SpeedtestActivityState.fromWireName(
        backend.getString(KEY_SPEEDTEST_STATE, SpeedtestActivityState.UNKNOWN.wireName)
      ),
      cellMapperState = CellMapperRecordingState.fromWireName(
        backend.getString(KEY_CELLMAPPER_STATE, CellMapperRecordingState.UNKNOWN.wireName)
      ),
      protectedHandoffStartedAtMillis = backend.getLong(KEY_PROTECTED_HANDOFF_STARTED_AT_MILLIS, 0L),
      pendingRecoveryAction = pendingRecoveryAction,
      pendingRecoveryPhase = if (backend.contains(KEY_PENDING_RECOVERY_PHASE)) {
        PhoneAutomationPendingRecoveryPhase.fromWireName(
          backend.getString(KEY_PENDING_RECOVERY_PHASE, PhoneAutomationPendingRecoveryPhase.NONE.wireName)
        )
      } else {
        inferredPendingRecoveryPhase
      },
      pendingRecoveryReason = backend.getString(KEY_PENDING_RECOVERY_REASON, ""),
      pendingRecoveryNotBeforeAtMillis = pendingRecoveryNotBeforeAtMillis,
      pendingRecoveryToken = backend.getString(KEY_PENDING_RECOVERY_TOKEN, ""),
      lastReadyAtMillis = backend.getLong(KEY_LAST_READY_AT_MILLIS, 0L),
      transientFailureCount = backend.getLong(KEY_TRANSIENT_FAILURE_COUNT, 0L).toInt(),
      lastTransientFailureAtMillis = backend.getLong(KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS, 0L),
      runtimeErrorRetryAtMillis = backend.getLong(KEY_RUNTIME_ERROR_RETRY_AT_MILLIS, 0L),
      updatedAtMillis = backend.getLong(KEY_UPDATED_AT_MILLIS, 0L)
    )
  }

  override fun setEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    val runtimeState = if (enabled) {
      PhoneAutomationRuntimeState.STARTING
    } else {
      PhoneAutomationRuntimeState.DISABLED
    }
    val runtimeDetail = if (enabled) {
      "Waiting for the supervision service"
    } else {
      PhoneAutomationRuntimeState.DISABLED.defaultDetail
    }
    return mutate(
      KEY_ENABLED to enabled,
      KEY_RUNTIME_STATE to runtimeState.wireName,
      KEY_RUNTIME_DETAIL to runtimeDetail,
      KEY_LAST_RUN_STARTED_AT_MILLIS to 0L,
      KEY_LAST_COMPLETION_NOTIFICATION_AT_MILLIS to 0L,
      KEY_LAST_RESULT_READY_AT_MILLIS to 0L,
      KEY_LAST_HANDLED_COMPLETION_AT_MILLIS to 0L,
      KEY_CURRENT_RUN_LAUNCH_MODE to SpeedtestRunLaunchMode.NONE.wireName,
      KEY_CURRENT_ATTEMPT_ID to "",
      KEY_CURRENT_ATTEMPT_START_PROOF_AT_MILLIS to 0L,
      KEY_CURRENT_ATTEMPT_RESULT_SCREEN_CLEARED_AT_MILLIS to 0L,
      KEY_SPEEDTEST_STATE to SpeedtestActivityState.UNKNOWN.wireName,
      KEY_CELLMAPPER_STATE to CellMapperRecordingState.UNKNOWN.wireName,
      KEY_PROTECTED_HANDOFF_STARTED_AT_MILLIS to 0L,
      KEY_PENDING_RECOVERY_ACTION to PhoneAutomationPendingRecoveryAction.NONE.wireName,
      KEY_PENDING_RECOVERY_PHASE to PhoneAutomationPendingRecoveryPhase.NONE.wireName,
      KEY_PENDING_RECOVERY_REASON to "",
      KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS to 0L,
      KEY_PENDING_RECOVERY_TOKEN to "",
      KEY_TRANSIENT_FAILURE_COUNT to 0L,
      KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS to 0L,
      KEY_RUNTIME_ERROR_RETRY_AT_MILLIS to 0L
    )
  }

  override fun setMaintainCellMapper(maintainCellMapper: Boolean): PhoneAutomationSettingsSnapshot {
    val snapshot = load()
    val updates = mutableListOf<Pair<String, Any>>(
      KEY_MAINTAIN_CELLMAPPER to maintainCellMapper
    )
    if (snapshot.enabled) {
      updates += KEY_RUNTIME_STATE to PhoneAutomationRuntimeState.STARTING.wireName
      updates += KEY_RUNTIME_DETAIL to "Waiting for the supervision service"
    }
    updates += KEY_PENDING_RECOVERY_ACTION to PhoneAutomationPendingRecoveryAction.NONE.wireName
    updates += KEY_PENDING_RECOVERY_PHASE to PhoneAutomationPendingRecoveryPhase.NONE.wireName
    updates += KEY_PENDING_RECOVERY_REASON to ""
    updates += KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS to 0L
    updates += KEY_PENDING_RECOVERY_TOKEN to ""
    updates += KEY_TRANSIENT_FAILURE_COUNT to 0L
    updates += KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS to 0L
    return mutate(*updates.toTypedArray())
  }

  override fun setReturnToOrchestratorAfterForegroundWork(
    returnToOrchestratorAfterForegroundWork: Boolean
  ): PhoneAutomationSettingsSnapshot {
    val snapshot = load()
    val updates = mutableListOf<Pair<String, Any>>(
      KEY_RETURN_TO_ORCHESTRATOR_AFTER_FOREGROUND_WORK to returnToOrchestratorAfterForegroundWork
    )
    if (snapshot.enabled) {
      updates += KEY_RUNTIME_STATE to PhoneAutomationRuntimeState.STARTING.wireName
      updates += KEY_RUNTIME_DETAIL to "Waiting for the supervision service"
    }
    updates += KEY_PENDING_RECOVERY_ACTION to PhoneAutomationPendingRecoveryAction.NONE.wireName
    updates += KEY_PENDING_RECOVERY_PHASE to PhoneAutomationPendingRecoveryPhase.NONE.wireName
    updates += KEY_PENDING_RECOVERY_REASON to ""
    updates += KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS to 0L
    updates += KEY_PENDING_RECOVERY_TOKEN to ""
    updates += KEY_TRANSIENT_FAILURE_COUNT to 0L
    updates += KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS to 0L
    return mutate(*updates.toTypedArray())
  }

  override fun setDispatchInterval(dispatchInterval: PhoneAutomationDispatchInterval): PhoneAutomationSettingsSnapshot {
    val snapshot = load()
    val updates = mutableListOf<Pair<String, Any>>(
      KEY_DISPATCH_INTERVAL to dispatchInterval.wireName
    )
    if (snapshot.enabled) {
      updates += KEY_RUNTIME_STATE to PhoneAutomationRuntimeState.STARTING.wireName
      updates += KEY_RUNTIME_DETAIL to "Waiting for the supervision service"
    }
    updates += KEY_PENDING_RECOVERY_ACTION to PhoneAutomationPendingRecoveryAction.NONE.wireName
    updates += KEY_PENDING_RECOVERY_PHASE to PhoneAutomationPendingRecoveryPhase.NONE.wireName
    updates += KEY_PENDING_RECOVERY_REASON to ""
    updates += KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS to 0L
    updates += KEY_PENDING_RECOVERY_TOKEN to ""
    updates += KEY_TRANSIENT_FAILURE_COUNT to 0L
    updates += KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS to 0L
    return mutate(*updates.toTypedArray())
  }

  override fun setTouchBrightnessEnabled(enabled: Boolean): PhoneAutomationSettingsSnapshot {
    val state = if (enabled) {
      TouchBrightnessRuntimeState.STARTING
    } else {
      TouchBrightnessRuntimeState.DISABLED
    }
    val detail = if (enabled) {
      "Waiting for the supervision service"
    } else {
      TouchBrightnessRuntimeState.DISABLED.defaultDetail
    }
    val updates = mutableListOf<Pair<String, Any>>(
      KEY_TOUCH_BRIGHTNESS_ENABLED to enabled,
      KEY_TOUCH_BRIGHTNESS_STATE to state.wireName,
      KEY_TOUCH_BRIGHTNESS_DETAIL to detail,
      KEY_TOUCH_BRIGHTNESS_DEBUG_DETAIL to if (enabled) {
        "Waiting for live touch data"
      } else {
        ""
      }
    )
    if (enabled) {
      updates += KEY_TOUCH_BRIGHTNESS_RESTORE_MODE to CLEAR_SENTINEL
      updates += KEY_TOUCH_BRIGHTNESS_RESTORE_VALUE to CLEAR_SENTINEL
    }
    return mutate(*updates.toTypedArray())
  }

  override fun updateSetupState(
    state: PhoneAutomationSetupState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    val lastReadyAtMillis = if (state == PhoneAutomationSetupState.READY) {
      System.currentTimeMillis()
    } else {
      load().lastReadyAtMillis
    }
    return mutate(
      KEY_SETUP_STATE to state.wireName,
      KEY_SETUP_DETAIL to detail.ifBlank { state.defaultDetail },
      KEY_LAST_READY_AT_MILLIS to lastReadyAtMillis
    )
  }

  override fun updateRuntimeState(
    state: PhoneAutomationRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    val snapshot = load()
    val protectedHandoffStartedAtMillis = when {
      !snapshot.enabled -> 0L
      state == snapshot.runtimeState && snapshot.isProtectedSpeedtestHandoffInProgress() ->
        snapshot.protectedHandoffStartedAtMillis
      state == PhoneAutomationRuntimeState.STARTING ||
        state == PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST -> System.currentTimeMillis()
      else -> 0L
    }
    val runtimeErrorRetryAtMillis = when {
      state == snapshot.runtimeState &&
        state == PhoneAutomationRuntimeState.ERROR &&
        snapshot.runtimeErrorRetryAtMillis > 0L -> snapshot.runtimeErrorRetryAtMillis
      state == PhoneAutomationRuntimeState.ERROR -> {
        System.currentTimeMillis() + snapshot.dispatchInterval.intervalMillis
      }
      else -> 0L
    }
    return mutate(
      KEY_RUNTIME_STATE to state.wireName,
      KEY_RUNTIME_DETAIL to detail.ifBlank { state.defaultDetail },
      KEY_PROTECTED_HANDOFF_STARTED_AT_MILLIS to protectedHandoffStartedAtMillis,
      KEY_RUNTIME_ERROR_RETRY_AT_MILLIS to runtimeErrorRetryAtMillis
    )
  }

  override fun updateTouchBrightnessState(
    state: TouchBrightnessRuntimeState,
    detail: String
  ): PhoneAutomationSettingsSnapshot {
    return mutate(
      KEY_TOUCH_BRIGHTNESS_STATE to state.wireName,
      KEY_TOUCH_BRIGHTNESS_DETAIL to detail.ifBlank { state.defaultDetail }
    )
  }

  override fun updateTouchBrightnessDebugDetail(detail: String): PhoneAutomationSettingsSnapshot {
    return mutate(
      KEY_TOUCH_BRIGHTNESS_DEBUG_DETAIL to detail
    )
  }

  override fun saveTouchBrightnessRestoreState(
    mode: Int?,
    value: Int?
  ): PhoneAutomationSettingsSnapshot {
    return mutate(
      KEY_TOUCH_BRIGHTNESS_RESTORE_MODE to (mode?.toLong() ?: CLEAR_SENTINEL),
      KEY_TOUCH_BRIGHTNESS_RESTORE_VALUE to (value?.toLong() ?: CLEAR_SENTINEL)
    )
  }

  override fun clearTouchBrightnessRestoreState(): PhoneAutomationSettingsSnapshot {
    return mutate(
      KEY_TOUCH_BRIGHTNESS_RESTORE_MODE to CLEAR_SENTINEL,
      KEY_TOUCH_BRIGHTNESS_RESTORE_VALUE to CLEAR_SENTINEL
    )
  }

  override fun updateCycleState(
    lastRunStartedAtMillis: Long,
    lastCompletionNotificationAtMillis: Long,
    lastResultReadyAtMillis: Long,
    lastHandledCompletionAtMillis: Long,
    currentRunLaunchMode: SpeedtestRunLaunchMode,
    lastAcceptedResultFingerprint: String,
    speedtestState: SpeedtestActivityState,
    cellMapperState: CellMapperRecordingState,
    pendingRecoveryReason: String,
    currentAttemptId: String,
    currentAttemptStartProofAtMillis: Long,
    currentAttemptResultScreenClearedAtMillis: Long
  ): PhoneAutomationSettingsSnapshot {
    return mutate(
      KEY_LAST_RUN_STARTED_AT_MILLIS to lastRunStartedAtMillis,
      KEY_LAST_COMPLETION_NOTIFICATION_AT_MILLIS to lastCompletionNotificationAtMillis,
      KEY_LAST_RESULT_READY_AT_MILLIS to lastResultReadyAtMillis,
      KEY_LAST_HANDLED_COMPLETION_AT_MILLIS to lastHandledCompletionAtMillis,
      KEY_CURRENT_RUN_LAUNCH_MODE to currentRunLaunchMode.wireName,
      KEY_LAST_ACCEPTED_RESULT_FINGERPRINT to lastAcceptedResultFingerprint,
      KEY_CURRENT_ATTEMPT_ID to currentAttemptId,
      KEY_CURRENT_ATTEMPT_START_PROOF_AT_MILLIS to currentAttemptStartProofAtMillis,
      KEY_CURRENT_ATTEMPT_RESULT_SCREEN_CLEARED_AT_MILLIS to currentAttemptResultScreenClearedAtMillis,
      KEY_SPEEDTEST_STATE to speedtestState.wireName,
      KEY_CELLMAPPER_STATE to cellMapperState.wireName,
      KEY_PENDING_RECOVERY_REASON to pendingRecoveryReason
    )
  }

  override fun updatePendingRecovery(
    action: PhoneAutomationPendingRecoveryAction,
    reason: String,
    phase: PhoneAutomationPendingRecoveryPhase,
    notBeforeAtMillis: Long,
    token: String
  ): PhoneAutomationSettingsSnapshot {
    val resolvedPhase = if (action == PhoneAutomationPendingRecoveryAction.NONE) {
      PhoneAutomationPendingRecoveryPhase.NONE
    } else {
      phase
    }
    val resolvedNotBeforeAtMillis = if (
      action == PhoneAutomationPendingRecoveryAction.NONE ||
      resolvedPhase != PhoneAutomationPendingRecoveryPhase.QUEUED_RETRY
    ) {
      0L
    } else if (notBeforeAtMillis > 0L) {
      notBeforeAtMillis
    } else {
      0L
    }
    val resolvedToken = if (action == PhoneAutomationPendingRecoveryAction.NONE) {
      ""
    } else {
      token
    }
    return mutate(
      KEY_PENDING_RECOVERY_ACTION to action.wireName,
      KEY_PENDING_RECOVERY_PHASE to resolvedPhase.wireName,
      KEY_PENDING_RECOVERY_REASON to reason,
      KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS to resolvedNotBeforeAtMillis,
      KEY_PENDING_RECOVERY_TOKEN to resolvedToken
    )
  }

  override fun clearCycleState(): PhoneAutomationSettingsSnapshot {
    return mutate(
      KEY_LAST_RUN_STARTED_AT_MILLIS to 0L,
      KEY_LAST_COMPLETION_NOTIFICATION_AT_MILLIS to 0L,
      KEY_LAST_RESULT_READY_AT_MILLIS to 0L,
      KEY_LAST_HANDLED_COMPLETION_AT_MILLIS to 0L,
      KEY_CURRENT_RUN_LAUNCH_MODE to SpeedtestRunLaunchMode.NONE.wireName,
      KEY_CURRENT_ATTEMPT_ID to "",
      KEY_CURRENT_ATTEMPT_START_PROOF_AT_MILLIS to 0L,
      KEY_CURRENT_ATTEMPT_RESULT_SCREEN_CLEARED_AT_MILLIS to 0L,
      KEY_SPEEDTEST_STATE to SpeedtestActivityState.UNKNOWN.wireName,
      KEY_CELLMAPPER_STATE to CellMapperRecordingState.UNKNOWN.wireName,
      KEY_PROTECTED_HANDOFF_STARTED_AT_MILLIS to 0L,
      KEY_PENDING_RECOVERY_PHASE to PhoneAutomationPendingRecoveryPhase.NONE.wireName,
      KEY_PENDING_RECOVERY_REASON to "",
      KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS to 0L,
      KEY_PENDING_RECOVERY_TOKEN to ""
    )
  }

  override fun recordTransientFailure(
    reason: String,
    observedAtMillis: Long
  ): PhoneAutomationSettingsSnapshot {
    val snapshot = load()
    return mutate(
      KEY_PENDING_RECOVERY_REASON to reason,
      KEY_TRANSIENT_FAILURE_COUNT to (snapshot.transientFailureCount + 1).toLong(),
      KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS to observedAtMillis
    )
  }

  override fun clearTransientFailureTracking(): PhoneAutomationSettingsSnapshot {
    val snapshot = load()
    return mutate(
      KEY_TRANSIENT_FAILURE_COUNT to 0L,
      KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS to 0L,
      KEY_PENDING_RECOVERY_REASON to if (
        snapshot.pendingRecoveryPhase != PhoneAutomationPendingRecoveryPhase.NONE
      ) {
        snapshot.pendingRecoveryReason
      } else {
        ""
      }
    )
  }

  private fun mutate(vararg updates: Pair<String, Any>): PhoneAutomationSettingsSnapshot {
    val mutations = linkedMapOf<String, Any>()
    updates.forEach { (key, value) ->
      mutations[key] = value
    }
    mutations[KEY_UPDATED_AT_MILLIS] = System.currentTimeMillis()
    backend.applyMutations(mutations)
    return load()
  }

  companion object {
    private const val CLEAR_SENTINEL = -1L
    private const val PREFS_NAME = "phone_automation_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MAINTAIN_CELLMAPPER = "maintain_cellmapper"
    private const val KEY_RETURN_TO_ORCHESTRATOR_AFTER_FOREGROUND_WORK =
      "return_to_orchestrator_after_foreground_work"
    private const val KEY_DISPATCH_INTERVAL = "dispatch_interval"
    private const val KEY_TOUCH_BRIGHTNESS_ENABLED = "touch_brightness_enabled"
    private const val KEY_TOUCH_BRIGHTNESS_STATE = "touch_brightness_state"
    private const val KEY_TOUCH_BRIGHTNESS_DETAIL = "touch_brightness_detail"
    private const val KEY_TOUCH_BRIGHTNESS_DEBUG_DETAIL = "touch_brightness_debug_detail"
    private const val KEY_TOUCH_BRIGHTNESS_RESTORE_MODE = "touch_brightness_restore_mode"
    private const val KEY_TOUCH_BRIGHTNESS_RESTORE_VALUE = "touch_brightness_restore_value"
    private const val KEY_SETUP_STATE = "setup_state"
    private const val KEY_SETUP_DETAIL = "setup_detail"
    private const val KEY_RUNTIME_STATE = "runtime_state"
    private const val KEY_RUNTIME_DETAIL = "runtime_detail"
    private const val KEY_LAST_RUN_STARTED_AT_MILLIS = "last_run_started_at_millis"
    private const val KEY_LAST_COMPLETION_NOTIFICATION_AT_MILLIS = "last_completion_notification_at_millis"
    private const val KEY_LAST_RESULT_READY_AT_MILLIS = "last_result_ready_at_millis"
    private const val KEY_LAST_HANDLED_COMPLETION_AT_MILLIS = "last_handled_completion_at_millis"
    private const val KEY_CURRENT_RUN_LAUNCH_MODE = "current_run_launch_mode"
    private const val KEY_LAST_ACCEPTED_RESULT_FINGERPRINT = "last_accepted_result_fingerprint"
    private const val KEY_CURRENT_ATTEMPT_ID = "current_attempt_id"
    private const val KEY_CURRENT_ATTEMPT_START_PROOF_AT_MILLIS = "current_attempt_start_proof_at_millis"
    private const val KEY_CURRENT_ATTEMPT_RESULT_SCREEN_CLEARED_AT_MILLIS =
      "current_attempt_result_screen_cleared_at_millis"
    private const val KEY_SPEEDTEST_STATE = "speedtest_state"
    private const val KEY_CELLMAPPER_STATE = "cellmapper_state"
    private const val KEY_PROTECTED_HANDOFF_STARTED_AT_MILLIS = "protected_handoff_started_at_millis"
    private const val KEY_PENDING_RECOVERY_ACTION = "pending_recovery_action"
    private const val KEY_PENDING_RECOVERY_PHASE = "pending_recovery_phase"
    private const val KEY_PENDING_RECOVERY_REASON = "pending_recovery_reason"
    private const val KEY_PENDING_RECOVERY_NOT_BEFORE_AT_MILLIS = "pending_recovery_not_before_at_millis"
    private const val KEY_PENDING_RECOVERY_TOKEN = "pending_recovery_token"
    private const val KEY_LAST_READY_AT_MILLIS = "last_ready_at_millis"
    private const val KEY_TRANSIENT_FAILURE_COUNT = "transient_failure_count"
    private const val KEY_LAST_TRANSIENT_FAILURE_AT_MILLIS = "last_transient_failure_at_millis"
    private const val KEY_RUNTIME_ERROR_RETRY_AT_MILLIS = "runtime_error_retry_at_millis"
    private const val KEY_UPDATED_AT_MILLIS = "updated_at_millis"
  }
}
