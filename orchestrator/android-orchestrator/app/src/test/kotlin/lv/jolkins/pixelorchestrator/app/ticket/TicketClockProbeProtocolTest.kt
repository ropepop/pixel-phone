package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketClockProbeProtocolTest {
  @Test
  fun parsesBoundedRequestAndEchoesTheExactFourTimestampFields() {
    val request = TicketClockProbeProtocol.parseRequest(
      Json.parseToJsonElement(
        """{"type":"clock_probe","probeId":"probe-7:attempt_2.ok","serverSendUnixMicros":1770000000123456}"""
      ).jsonObject
    )!!

    assertEquals("probe-7:attempt_2.ok", request.probeId)
    assertEquals(1_770_000_000_123_456L, request.serverSendUnixMicros)

    val result = Json.parseToJsonElement(
      TicketClockProbeProtocol.encodeResult(request, 9_001_000L, 9_001_125L)
    ).jsonObject
    assertEquals("clock_probe_result", result["type"]!!.jsonPrimitive.content)
    assertEquals(request.probeId, result["probeId"]!!.jsonPrimitive.content)
    assertEquals(request.serverSendUnixMicros, result["serverSendUnixMicros"]!!.jsonPrimitive.long)
    assertEquals(9_001_000L, result["phoneReceiveUptimeMicros"]!!.jsonPrimitive.long)
    assertEquals(9_001_125L, result["phoneSendUptimeMicros"]!!.jsonPrimitive.long)
  }

  @Test
  fun rejectsUnboundedWrongTypeOrNonPositiveRequestsWithoutThrowing() {
    val invalid = listOf(
      """{"type":"clock_probe","probeId":"","serverSendUnixMicros":1}""",
      """{"type":"clock_probe","probeId":"${"a".repeat(TicketClockProbeProtocol.MAX_PROBE_ID_BYTES + 1)}","serverSendUnixMicros":1}""",
      """{"type":"clock_probe","probeId":"spaces are rejected","serverSendUnixMicros":1}""",
      """{"type":"clock_probe","probeId":7,"serverSendUnixMicros":1}""",
      """{"type":"clock_probe","probeId":"valid","serverSendUnixMicros":"1"}""",
      """{"type":"clock_probe","probeId":"valid","serverSendUnixMicros":0}""",
      """{"type":"clock_probe","probeId":"valid","serverSendUnixMicros":-1}""",
      """{"type":"clock_probe","probeId":{},"serverSendUnixMicros":1}""",
      """{"type":"other","probeId":"valid","serverSendUnixMicros":1}"""
    )

    invalid.forEach { raw ->
      assertNull(TicketClockProbeProtocol.parseRequest(Json.parseToJsonElement(raw).jsonObject))
    }
  }

  @Test
  fun rejectsImpossiblePhoneStageValues() {
    val request = TicketClockProbeRequest("probe-1", 1L)
    listOf(
      0L to 1L,
      2L to 1L
    ).forEach { (receive, send) ->
      assertTrue(runCatching {
        TicketClockProbeProtocol.encodeResult(request, receive, send)
      }.exceptionOrNull() is IllegalArgumentException)
    }
  }
}
