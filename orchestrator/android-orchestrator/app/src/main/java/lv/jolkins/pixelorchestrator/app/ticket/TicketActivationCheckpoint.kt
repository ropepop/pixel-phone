package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState

/**
 * The only durable phone state for an admitted ticket activation.  It contains opaque command
 * and revision identifiers plus a stage; it deliberately never contains ticket pixels, UI text,
 * coordinates, identity data, or other private content.
 */
@Serializable
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

@Serializable
internal data class TicketActivationCheckpoint(
  val commandId: String,
  val interactionRevision: String,
  val activationAttemptId: String,
  val activationRevision: String = "",
  /** Last ordinal durably admitted for physical dispatch. Zero means no stroke was admitted. */
  val dispatchOrdinal: Int = 0,
  val stage: TicketActivationCheckpointStage
)

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
): String = NativeTicketCheckpoint.call("no_transition_phase", checkpoint).jsonPrimitive.content

internal fun ticketActivationNoTransitionTerminalReason(
  checkpoint: TicketActivationCheckpoint
): String = NativeTicketCheckpoint.call("no_transition_reason", checkpoint).jsonPrimitive.content

/** Navigation taps never establish that the separate registration stroke was dispatched. */
internal fun ticketActivationFailureTerminalPhase(
  checkpoint: TicketActivationCheckpoint?,
  provisionalPhase: String = ""
): String = NativeTicketCheckpoint.call("failure_phase", checkpoint, buildJsonObject {
  put("provisionalPhase", provisionalPhase)
}).jsonPrimitive.content

/**
 * A server-finalized terminal may retire only a checkpoint whose local stage proves the same
 * conclusive outcome. Dispatch uncertainty and generic attention remain as durable replay fences.
 */
internal fun ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
  checkpoint: TicketActivationCheckpoint,
  commandId: String,
  action: TicketVisualActionSnapshot
): Boolean = NativeTicketCheckpoint.call("safe_to_clear", checkpoint, buildJsonObject {
  put("commandId", commandId)
  put("action", buildJsonObject {
    put("actionId", action.actionId)
    put("activationAttemptId", action.activationAttemptId)
    put("terminal", action.terminal)
    put("completedAt", action.completedAt)
    put("ok", action.ok)
    put("status", action.status)
    put("phase", action.phase)
    put("reason", action.reason)
    put("currentView", action.currentView.wireName)
    put("interactionRevision", action.interactionRevision)
    put("activationRevision", action.activationRevision)
  })
}).jsonPrimitive.boolean


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
    return checkpoint.takeIf { NativeTicketCheckpoint.call("matches", it, buildJsonObject {
      put("commandId", commandId)
      put("interactionRevision", interactionRevision)
      put("activationAttemptId", activationAttemptId)
    }).jsonPrimitive.boolean }
  }

  fun recordFreshTicketProven(
    commandId: String,
    interactionRevision: String,
    activationAttemptId: String
  ): TicketActivationCheckpoint? {
    // A different unresolved activation owns this single durable slot. Never overwrite its
    // at-most-once history merely because the server normally serializes phone commands.
    if (backend.load() != null) return null
    return transition("fresh", null, buildJsonObject {
      put("commandId", commandId)
      put("interactionRevision", interactionRevision)
      put("activationAttemptId", activationAttemptId)
    })
  }

  fun recordActivationDispatching(
    checkpoint: TicketActivationCheckpoint,
    ordinal: Int
  ): TicketActivationCheckpoint? {
    return transition("dispatching", checkpoint, buildJsonObject { put("ordinal", ordinal) })
  }

  fun recordActivationProven(
    checkpoint: TicketActivationCheckpoint,
    activationRevision: String
  ): TicketActivationCheckpoint? {
    return transition("proven", checkpoint, buildJsonObject { put("activationRevision", activationRevision) })
  }

  fun recordNoTransitionProven(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
    return transition("no_transition", checkpoint)
  }

  fun recordNeedsAttention(checkpoint: TicketActivationCheckpoint): TicketActivationCheckpoint? {
    return transition("attention", checkpoint)
  }

  fun clearIfMatches(commandId: String, activationAttemptId: String): Boolean {
    val current = backend.load() ?: return false
    if (!NativeTicketCheckpoint.call("matches", current, buildJsonObject {
      put("commandId", commandId)
      put("activationAttemptId", activationAttemptId)
    }).jsonPrimitive.boolean) {
      return false
    }
    return backend.clear() && backend.load() == null
  }

  private fun transition(
    operation: String,
    checkpoint: TicketActivationCheckpoint?,
    args: JsonObject = buildJsonObject {}
  ): TicketActivationCheckpoint? {
    val update = NativeTicketCheckpoint.transition(operation, checkpoint, args)
    if (update.write && !backend.save(update.checkpoint)) return null
    return update.checkpoint.takeIf { backend.load() == it }
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
