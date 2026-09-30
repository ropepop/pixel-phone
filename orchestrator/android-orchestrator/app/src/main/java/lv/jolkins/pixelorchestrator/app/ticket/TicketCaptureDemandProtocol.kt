package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class TicketCaptureDemandRequest(
  val streamEpoch: Long,
  val generation: Long,
  val ttlMillis: Long
)

internal enum class TicketCaptureDemandAdmissionResult {
  ACCEPTED,
  WRONG_EPOCH,
  NON_MONOTONIC_GENERATION,
  EXPIRED,
  HELPER_UNAVAILABLE
}

/** JSON tree transport only; strict request admission belongs to Rust. */
internal object TicketCaptureDemandProtocol {
  const val VERSION = 1L
  const val TTL_MILLIS = 2_500L
  fun parseRequest(element: JsonObject): TicketCaptureDemandRequest? =
    NativeTicketCapture.json.decodeFromJsonElement<TicketCaptureDemandRequest?>(
      NativeTicketCapture.call("parse_demand", element)
    )
}

internal object NativeTicketCapture {
  val json = Json { encodeDefaults = true }
  init { System.loadLibrary("pixel_health") }
  private external fun decide(operation: String, payload: String): String
  fun call(operation: String, input: JsonObject): JsonElement = json.parseToJsonElement(decide(operation, input.toString()))
}

/** Monotonic generation scope owned by one exact TicketWebSocket instance. */
internal class TicketCaptureDemandSession {
  private var lastAcceptedGeneration = 0L

  @Synchronized
  fun admit(
    request: TicketCaptureDemandRequest,
    currentStreamEpoch: Long,
    receivedAtUptimeMillis: Long,
    nowUptimeMillis: Long,
    deliverToHelper: (validUntilUptimeMillis: Long) -> Boolean
  ): TicketCaptureDemandAdmissionResult {
    val decision = NativeTicketCapture.call("admit_demand", buildJsonObject {
      put("epoch", request.streamEpoch)
      put("generation", request.generation)
      put("ttlMillis", request.ttlMillis)
      put("currentEpoch", currentStreamEpoch)
      put("lastGeneration", lastAcceptedGeneration)
      put("received", receivedAtUptimeMillis)
      put("now", nowUptimeMillis)
    }).jsonObject
    val answer = TicketCaptureDemandAdmissionResult.valueOf(decision.getValue("answer").jsonPrimitive.content)
    if (answer != TicketCaptureDemandAdmissionResult.ACCEPTED) return answer
    val validUntilUptimeMillis = decision.getValue("validUntil").jsonPrimitive.long
    if (!deliverToHelper(validUntilUptimeMillis)) {
      return TicketCaptureDemandAdmissionResult.HELPER_UNAVAILABLE
    }
    lastAcceptedGeneration = request.generation
    return TicketCaptureDemandAdmissionResult.ACCEPTED
  }

  @Synchronized
  internal fun lastAcceptedGeneration(): Long = lastAcceptedGeneration
}
