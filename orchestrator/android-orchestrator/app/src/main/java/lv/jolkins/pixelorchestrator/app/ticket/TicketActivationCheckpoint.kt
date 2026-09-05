package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.content.SharedPreferences
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState

/**
 * The only durable phone state for an admitted ticket activation.  It contains opaque command
 * and revision identifiers plus a stage; it deliberately never contains ticket pixels, UI text,
 * coordinates, identity data, or other private content.
 */
internal enum class TicketActivationCheckpointStage(val wireName: String) {
  FRESH_TICKET_PROVEN("fresh_ticket_proven"),
  ACTIVATION_DISPATCHING("activation_dispatching"),
  NO_TRANSITION_PROVEN("no_transition_proven"),
  ACTIVATION_PROVEN("activation_proven"),
  NEEDS_ATTENTION("needs_attention");

  companion object {
    fun fromWireName(value: String?): TicketActivationCheckpointStage? =
      entries.firstOrNull { it.wireName == value?.trim() }
  }
}

internal data class TicketActivationCheckpoint(
  val commandId: String,
  val interactionRevision: String,
  val activationAttemptId: String,
  val activationRevision: String = "",
  /** Last ordinal durably admitted for physical dispatch. Zero means no stroke was admitted. */
  val dispatchOrdinal: Int = 0,
  val stage: TicketActivationCheckpointStage
)

internal enum class TicketActivationRecoveryAction {
  NONE,
  COMMIT_PROVEN_RESULT,
  NEEDS_ATTENTION
}

internal enum class TicketActivationRecoveryScreen {
  ACTIVATED,
  UNUSED,
  AMBIGUOUS
}

/** A raw-input event watermark; a quick physical down/up changes it even when the final state is idle. */
internal data class TicketActivationPhysicalTouchFence(
  val observedAtUptimeMillis: Long
)

internal fun ticketActivationPhysicalTouchFence(
  state: PhoneAutomationRootPhysicalTouchState
): TicketActivationPhysicalTouchFence? = state.takeIf { it.available && !it.active }?.let {
  TicketActivationPhysicalTouchFence(it.observedAtUptimeMillis)
}

internal fun ticketActivationPhysicalTouchFenceIsCurrent(
  fence: TicketActivationPhysicalTouchFence,
  state: PhoneAutomationRootPhysicalTouchState
): Boolean = state.available && !state.active &&
  state.observedAtUptimeMillis == fence.observedAtUptimeMillis

internal fun ticketActivationNoTransitionTerminalPhase(
  checkpoint: TicketActivationCheckpoint
): String {
  require(checkpoint.stage == TicketActivationCheckpointStage.NO_TRANSITION_PROVEN)
  return if (checkpoint.dispatchOrdinal >= 2) "no_transition" else "retry_not_dispatched"
}

internal fun ticketActivationNoTransitionTerminalReason(
  checkpoint: TicketActivationCheckpoint
): String = if (ticketActivationNoTransitionTerminalPhase(checkpoint) == "no_transition") {
  "ticket_action_gesture_completed_no_transition"
} else {
  "ticket_action_retry_not_dispatched"
}

/** Navigation taps never establish that the separate registration stroke was dispatched. */
internal fun ticketActivationFailureTerminalPhase(
  checkpoint: TicketActivationCheckpoint?,
  provisionalPhase: String = ""
): String = when {
  checkpoint?.stage == TicketActivationCheckpointStage.NO_TRANSITION_PROVEN ->
    ticketActivationNoTransitionTerminalPhase(checkpoint)
  checkpoint?.dispatchOrdinal?.let { it > 0 } == true ||
    provisionalPhase == "outcome_unknown" -> "outcome_unknown"
  else -> "not_dispatched"
}

/**
 * A server-finalized terminal may retire only a checkpoint whose local stage proves the same
 * conclusive outcome. Dispatch uncertainty and generic attention remain as durable replay fences.
 */
internal fun ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
  checkpoint: TicketActivationCheckpoint,
  commandId: String,
  action: TicketVisualActionSnapshot
): Boolean {
  if (checkpoint.commandId != commandId ||
    checkpoint.activationAttemptId != action.activationAttemptId ||
    action.activationAttemptId != action.actionId ||
    !action.terminal || action.completedAt.isBlank()
  ) return false
  return when (checkpoint.stage) {
    TicketActivationCheckpointStage.FRESH_TICKET_PROVEN ->
      checkpoint.dispatchOrdinal == 0 && !action.ok &&
        action.status == "needs_attention" && action.phase == "not_dispatched" &&
        action.activationRevision.isBlank()
    TicketActivationCheckpointStage.NO_TRANSITION_PROVEN ->
      checkpoint.dispatchOrdinal in 1..2 && !action.ok && action.status == "needs_attention" &&
        action.phase == ticketActivationNoTransitionTerminalPhase(checkpoint) &&
        action.reason == ticketActivationNoTransitionTerminalReason(checkpoint) &&
        action.currentView == TicketVisualActionView.LATEST_UNACTIVATED &&
        action.activationRevision.isBlank()
    TicketActivationCheckpointStage.ACTIVATION_PROVEN ->
      action.ok && action.status == "succeeded" && action.phase == "activation_proven" &&
        action.reason == "ticket_action_registered" &&
        action.currentView == TicketVisualActionView.ACTIVATED_CURRENT &&
        action.interactionRevision == checkpoint.interactionRevision &&
        action.activationRevision.isNotBlank() &&
        action.activationRevision == checkpoint.activationRevision
    TicketActivationCheckpointStage.ACTIVATION_DISPATCHING,
    TicketActivationCheckpointStage.NEEDS_ATTENTION -> false
  }
}

/**
 * Decides what a restarted worker may do.  Once Android has accepted the physical gesture, an
 * unused or ambiguous screen is never treated as permission to send that gesture again.
 */
internal fun ticketActivationRecoveryAction(
  checkpoint: TicketActivationCheckpoint?,
  screen: TicketActivationRecoveryScreen
): TicketActivationRecoveryAction {
  checkpoint ?: return if (screen == TicketActivationRecoveryScreen.ACTIVATED) {
    TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT
  } else {
    TicketActivationRecoveryAction.NONE
  }
  return when (checkpoint.stage) {
    TicketActivationCheckpointStage.FRESH_TICKET_PROVEN -> when (screen) {
      TicketActivationRecoveryScreen.ACTIVATED -> TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT
      TicketActivationRecoveryScreen.UNUSED,
      TicketActivationRecoveryScreen.AMBIGUOUS -> TicketActivationRecoveryAction.NEEDS_ATTENTION
    }
    TicketActivationCheckpointStage.ACTIVATION_DISPATCHING -> when (screen) {
      TicketActivationRecoveryScreen.ACTIVATED -> TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT
      TicketActivationRecoveryScreen.UNUSED,
      TicketActivationRecoveryScreen.AMBIGUOUS -> TicketActivationRecoveryAction.NEEDS_ATTENTION
    }
    // This stage is written only after Android reported a completed stroke and two fresh frames
    // proved the exact same unactivated detail. The one allowed same-action retry must be prepared
    // in the same live run; a restarted Pixel never turns this checkpoint into replay authority.
    TicketActivationCheckpointStage.NO_TRANSITION_PROVEN ->
      TicketActivationRecoveryAction.NEEDS_ATTENTION
    TicketActivationCheckpointStage.ACTIVATION_PROVEN -> when (screen) {
      TicketActivationRecoveryScreen.ACTIVATED -> TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT
      TicketActivationRecoveryScreen.UNUSED,
      TicketActivationRecoveryScreen.AMBIGUOUS -> TicketActivationRecoveryAction.NEEDS_ATTENTION
    }
    TicketActivationCheckpointStage.NEEDS_ATTENTION ->
      TicketActivationRecoveryAction.NEEDS_ATTENTION
  }
}

internal interface TicketActivationCheckpointBackend {
  fun load(): TicketActivationCheckpoint?
  fun save(checkpoint: TicketActivationCheckpoint): Boolean
  fun clear(): Boolean
}

internal class TicketActivationCheckpointStore internal constructor(
  private val backend: TicketActivationCheckpointBackend
) {
  constructor(context: Context) : this(
    SharedPreferencesTicketActivationCheckpointBackend(
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )
  )

  fun load(): TicketActivationCheckpoint? = backend.load()

  fun loadFor(
    commandId: String,
    interactionRevision: String,
    activationAttemptId: String
  ): TicketActivationCheckpoint? {
    val checkpoint = backend.load() ?: return null
    return checkpoint.takeIf {
      it.commandId == commandId.trim() &&
        it.interactionRevision == interactionRevision.trim() &&
        it.activationAttemptId == activationAttemptId.trim()
    }
  }

  fun recordFreshTicketProven(
    commandId: String,
    interactionRevision: String,
    activationAttemptId: String
  ): TicketActivationCheckpoint? {
    // A different unresolved activation owns this single durable slot. Never overwrite its
    // at-most-once history merely because the server normally serializes phone commands.
    if (backend.load() != null) return null
    return save(
      TicketActivationCheckpoint(
        commandId = commandId.trim(),
        interactionRevision = interactionRevision.trim(),
        activationAttemptId = activationAttemptId.trim(),
        stage = TicketActivationCheckpointStage.FRESH_TICKET_PROVEN
      )
    )
  }

  fun recordActivationDispatching(
    checkpoint: TicketActivationCheckpoint,
    ordinal: Int
  ): TicketActivationCheckpoint? {
    require(ordinal in 1..2) { "activation dispatch ordinal must be one or two" }
    require(ordinal > checkpoint.dispatchOrdinal) { "activation dispatch ordinal must advance" }
    require(
      ordinal == 1 && checkpoint.stage == TicketActivationCheckpointStage.FRESH_TICKET_PROVEN ||
        ordinal == 2 && checkpoint.stage == TicketActivationCheckpointStage.NO_TRANSITION_PROVEN
    ) { "activation dispatch stage does not admit ordinal $ordinal" }
    return save(
      checkpoint.copy(
        dispatchOrdinal = ordinal,
        stage = TicketActivationCheckpointStage.ACTIVATION_DISPATCHING
      )
    )
  }

  fun recordActivationProven(
    checkpoint: TicketActivationCheckpoint,
    activationRevision: String
  ): TicketActivationCheckpoint? {
    return save(
      checkpoint.copy(
        activationRevision = activationRevision.trim(),
        stage = TicketActivationCheckpointStage.ACTIVATION_PROVEN
      )
    )
  }

  fun recordNoTransitionProven(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
    return save(checkpoint.copy(stage = TicketActivationCheckpointStage.NO_TRANSITION_PROVEN))
  }

  fun recordNeedsAttention(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
    if (checkpoint.stage == TicketActivationCheckpointStage.FRESH_TICKET_PROVEN ||
      checkpoint.stage == TicketActivationCheckpointStage.NO_TRANSITION_PROVEN
    ) {
      // These stages already prove a stronger, conclusive boundary: respectively no admitted
      // stroke, or a completed stroke with the exact ticket still unactivated. Do not replace
      // that certainty with the generic attention state.
      return checkpoint.takeIf { backend.load() == checkpoint }
    }
    return save(checkpoint.copy(stage = TicketActivationCheckpointStage.NEEDS_ATTENTION))
  }

  fun clearIfMatches(commandId: String, activationAttemptId: String): Boolean {
    val current = backend.load() ?: return false
    if (current.commandId != commandId.trim() || current.activationAttemptId != activationAttemptId.trim()) {
      return false
    }
    return backend.clear() && backend.load() == null
  }

  private fun save(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
    require(checkpoint.commandId.isNotBlank()) { "activation checkpoint command id is required" }
    require(checkpoint.interactionRevision.isNotBlank()) { "activation checkpoint interaction revision is required" }
    require(checkpoint.activationAttemptId.isNotBlank()) { "activation checkpoint attempt id is required" }
    if (!backend.save(checkpoint)) return null
    return checkpoint.takeIf { backend.load() == checkpoint }
  }

  private companion object {
    const val PREFS_NAME = "ticket_activation_checkpoint"
  }
}

private class SharedPreferencesTicketActivationCheckpointBackend(
  private val preferences: SharedPreferences
) : TicketActivationCheckpointBackend {
  override fun load(): TicketActivationCheckpoint? {
    val commandId = preferences.getString(KEY_COMMAND_ID, null)?.trim().orEmpty()
    val interactionRevision = preferences.getString(KEY_INTERACTION_REVISION, null)?.trim().orEmpty()
    val activationAttemptId = preferences.getString(KEY_ATTEMPT_ID, null)?.trim().orEmpty()
    val stage = TicketActivationCheckpointStage.fromWireName(
      preferences.getString(KEY_STAGE, null)
    )
    if (commandId.isBlank() || interactionRevision.isBlank() || activationAttemptId.isBlank() || stage == null) {
      return null
    }
    return TicketActivationCheckpoint(
      commandId = commandId,
      interactionRevision = interactionRevision,
      activationAttemptId = activationAttemptId,
      activationRevision = preferences.getString(KEY_ACTIVATION_REVISION, "").orEmpty().trim(),
      dispatchOrdinal = preferences.getInt(KEY_DISPATCH_ORDINAL, 0).coerceIn(0, 2),
      stage = stage
    )
  }

  override fun save(checkpoint: TicketActivationCheckpoint): Boolean {
    return preferences.edit()
      .putString(KEY_COMMAND_ID, checkpoint.commandId)
      .putString(KEY_INTERACTION_REVISION, checkpoint.interactionRevision)
      .putString(KEY_ATTEMPT_ID, checkpoint.activationAttemptId)
      .putString(KEY_ACTIVATION_REVISION, checkpoint.activationRevision)
      .putInt(KEY_DISPATCH_ORDINAL, checkpoint.dispatchOrdinal)
      .putString(KEY_STAGE, checkpoint.stage.wireName)
      .commit()
  }

  override fun clear(): Boolean = preferences.edit().clear().commit()

  private companion object {
    const val KEY_COMMAND_ID = "command_id"
    const val KEY_INTERACTION_REVISION = "interaction_revision"
    const val KEY_ATTEMPT_ID = "activation_attempt_id"
    const val KEY_ACTIVATION_REVISION = "activation_revision"
    const val KEY_DISPATCH_ORDINAL = "dispatch_ordinal"
    const val KEY_STAGE = "stage"
  }
}
