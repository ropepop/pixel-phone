package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.content.SharedPreferences

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
  ACTIVATION_COMMITTED("activation_committed"),
  NEEDS_ATTENTION("needs_attention");

  companion object {
    fun fromWireName(value: String?): TicketActivationCheckpointStage? {
      return entries.firstOrNull { it.wireName == value?.trim() }
    }
  }
}

internal data class TicketActivationCheckpoint(
  val commandId: String,
  val interactionRevision: String,
  val activationAttemptId: String,
  val activationRevision: String = "",
  val stage: TicketActivationCheckpointStage
)

internal enum class TicketActivationRecoveryAction {
  NONE,
  ACKNOWLEDGE_ONLY,
  RESUME_ACTIVATION,
  COMMIT_PROVEN_RESULT,
  NEEDS_ATTENTION
}

internal enum class TicketActivationRecoveryScreen {
  ACTIVATED,
  UNUSED,
  AMBIGUOUS
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
      TicketActivationRecoveryScreen.UNUSED -> TicketActivationRecoveryAction.RESUME_ACTIVATION
      TicketActivationRecoveryScreen.AMBIGUOUS -> TicketActivationRecoveryAction.NEEDS_ATTENTION
    }
    TicketActivationCheckpointStage.ACTIVATION_DISPATCHING -> when (screen) {
      TicketActivationRecoveryScreen.ACTIVATED -> TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT
      TicketActivationRecoveryScreen.UNUSED,
      TicketActivationRecoveryScreen.AMBIGUOUS -> TicketActivationRecoveryAction.NEEDS_ATTENTION
    }
    // This stage is written only after Android reported a completed stroke and two fresh frames
    // proved the exact same unactivated detail. Spacetime may admit a distinct child command, but
    // a restarted Pixel must never turn this parent checkpoint into local replay authority.
    TicketActivationCheckpointStage.NO_TRANSITION_PROVEN ->
      TicketActivationRecoveryAction.NEEDS_ATTENTION
    TicketActivationCheckpointStage.ACTIVATION_PROVEN -> when (screen) {
      TicketActivationRecoveryScreen.ACTIVATED -> TicketActivationRecoveryAction.COMMIT_PROVEN_RESULT
      TicketActivationRecoveryScreen.UNUSED,
      TicketActivationRecoveryScreen.AMBIGUOUS -> TicketActivationRecoveryAction.NEEDS_ATTENTION
    }
    TicketActivationCheckpointStage.ACTIVATION_COMMITTED ->
      TicketActivationRecoveryAction.ACKNOWLEDGE_ONLY
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
    return save(
      TicketActivationCheckpoint(
        commandId = commandId.trim(),
        interactionRevision = interactionRevision.trim(),
        activationAttemptId = activationAttemptId.trim(),
        stage = TicketActivationCheckpointStage.FRESH_TICKET_PROVEN
      )
    )
  }

  fun recordActivationDispatching(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
    return save(checkpoint.copy(stage = TicketActivationCheckpointStage.ACTIVATION_DISPATCHING))
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

  fun recordActivationCommitted(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
    return save(checkpoint.copy(stage = TicketActivationCheckpointStage.ACTIVATION_COMMITTED))
  }

  fun recordNeedsAttention(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
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
      stage = stage
    )
  }

  override fun save(checkpoint: TicketActivationCheckpoint): Boolean {
    return preferences.edit()
      .putString(KEY_COMMAND_ID, checkpoint.commandId)
      .putString(KEY_INTERACTION_REVISION, checkpoint.interactionRevision)
      .putString(KEY_ATTEMPT_ID, checkpoint.activationAttemptId)
      .putString(KEY_ACTIVATION_REVISION, checkpoint.activationRevision)
      .putString(KEY_STAGE, checkpoint.stage.wireName)
      .commit()
  }

  override fun clear(): Boolean = preferences.edit().clear().commit()

  private companion object {
    const val KEY_COMMAND_ID = "command_id"
    const val KEY_INTERACTION_REVISION = "interaction_revision"
    const val KEY_ATTEMPT_ID = "activation_attempt_id"
    const val KEY_ACTIVATION_REVISION = "activation_revision"
    const val KEY_STAGE = "stage"
  }
}
