package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class TicketClockProbeRequest(
  val probeId: String,
  val serverSendUnixMicros: Long
)

/** Side-effect-free clock sample exchanged on the existing video WebSocket. */
internal object TicketClockProbeProtocol {
  const val MAX_PROBE_ID_BYTES = 64

  private val probeIdPattern = Regex("[A-Za-z0-9._:-]{1,$MAX_PROBE_ID_BYTES}")

  fun parseRequest(element: JsonObject): TicketClockProbeRequest? {
    val type = runCatching { element["type"]?.jsonPrimitive }.getOrNull() ?: return null
    val probeIdValue = runCatching { element["probeId"]?.jsonPrimitive }.getOrNull() ?: return null
    val serverSendValue = runCatching {
      element["serverSendUnixMicros"]?.jsonPrimitive
    }.getOrNull() ?: return null
    if (!type.isString || type.contentOrNull != "clock_probe" || !probeIdValue.isString || serverSendValue.isString) {
      return null
    }
    val probeId = probeIdValue.contentOrNull.orEmpty()
    val serverSendUnixMicros = serverSendValue.longOrNull ?: return null
    if (!probeIdPattern.matches(probeId) || serverSendUnixMicros <= 0L) return null
    return TicketClockProbeRequest(probeId, serverSendUnixMicros)
  }

  fun encodeResult(
    request: TicketClockProbeRequest,
    phoneReceiveUptimeMicros: Long,
    phoneSendUptimeMicros: Long
  ): String {
    require(probeIdPattern.matches(request.probeId))
    require(request.serverSendUnixMicros > 0L)
    require(phoneReceiveUptimeMicros > 0L)
    require(phoneSendUptimeMicros >= phoneReceiveUptimeMicros)
    return buildJsonObject {
      put("type", "clock_probe_result")
      put("probeId", request.probeId)
      put("serverSendUnixMicros", request.serverSendUnixMicros)
      put("phoneReceiveUptimeMicros", phoneReceiveUptimeMicros)
      put("phoneSendUptimeMicros", phoneSendUptimeMicros)
    }.toString()
  }
}
