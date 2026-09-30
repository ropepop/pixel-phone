package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Pure checkpoint decisions; Android still owns every durable effect and readback. */
internal object NativeTicketCheckpoint {
  private val json = Json { encodeDefaults = true }
  init { System.loadLibrary("pixel_health") }

  @JvmStatic private external fun decide(operation: String, payload: String): String

  fun call(
    operation: String,
    checkpoint: TicketActivationCheckpoint?,
    args: JsonObject = buildJsonObject {}
  ): JsonElement = json.parseToJsonElement(decide(operation, buildJsonObject {
    put("checkpoint", json.encodeToJsonElement(checkpoint))
    put("args", args)
  }.toString()))

  fun transition(
    operation: String,
    checkpoint: TicketActivationCheckpoint?,
    args: JsonObject = buildJsonObject {}
  ): TicketCheckpointUpdate = json.decodeFromJsonElement(call(operation, checkpoint, args))
}

@Serializable
internal data class TicketCheckpointUpdate(val checkpoint: TicketActivationCheckpoint, val write: Boolean)
