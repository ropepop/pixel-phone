package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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

/** Strict additive relay-to-Pixel demand message carried by the existing video WebSocket. */
internal object TicketCaptureDemandProtocol {
  const val VERSION = 1L
  const val TTL_MILLIS = 2_500L

  private val fields = setOf("type", "version", "streamEpoch", "generation", "ttlMillis")

  fun parseRequest(element: JsonObject): TicketCaptureDemandRequest? {
    if (element.keys != fields) return null
    val type = runCatching { element["type"]?.jsonPrimitive }.getOrNull() ?: return null
    val version = exactPositiveLong(element, "version") ?: return null
    val streamEpoch = exactPositiveLong(element, "streamEpoch") ?: return null
    val generation = exactPositiveLong(element, "generation") ?: return null
    val ttlMillis = exactPositiveLong(element, "ttlMillis") ?: return null
    if (
      !type.isString ||
      type.contentOrNull != "capture_demand" ||
      version != VERSION ||
      ttlMillis != TTL_MILLIS
    ) {
      return null
    }
    return TicketCaptureDemandRequest(streamEpoch, generation, ttlMillis)
  }

  private fun exactPositiveLong(element: JsonObject, name: String): Long? {
    val primitive = runCatching { element[name]?.jsonPrimitive }.getOrNull() ?: return null
    if (primitive.isString) return null
    val value = primitive.longOrNull ?: return null
    return value.takeIf { it in 1L..TicketTsf3FrameEnvelope.MAX_SAFE_INTEGER }
  }
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
    if (currentStreamEpoch <= 0L || request.streamEpoch != currentStreamEpoch) {
      return TicketCaptureDemandAdmissionResult.WRONG_EPOCH
    }
    if (request.generation <= lastAcceptedGeneration) {
      return TicketCaptureDemandAdmissionResult.NON_MONOTONIC_GENERATION
    }
    val validUntilUptimeMillis = receivedAtUptimeMillis + request.ttlMillis
    if (
      receivedAtUptimeMillis <= 0L ||
      validUntilUptimeMillis < receivedAtUptimeMillis ||
      nowUptimeMillis > validUntilUptimeMillis
    ) {
      return TicketCaptureDemandAdmissionResult.EXPIRED
    }
    if (!deliverToHelper(validUntilUptimeMillis)) {
      return TicketCaptureDemandAdmissionResult.HELPER_UNAVAILABLE
    }
    lastAcceptedGeneration = request.generation
    return TicketCaptureDemandAdmissionResult.ACCEPTED
  }

  @Synchronized
  internal fun lastAcceptedGeneration(): Long = lastAcceptedGeneration
}
