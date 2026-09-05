package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketClockProbeSourceTest {
  private val service by lazy { source("TicketStreamService.kt") }
  private val socket by lazy { source("TicketWebSocket.kt") }
  private val protocol by lazy { source("TicketClockProbeProtocol.kt") }

  @Test
  fun videoClockProbeCapturesReceiveAtEntryAndSendInsideTheWriteLock() {
    val handler = service.substringAfter("private fun handleVideoClientCommand(")
      .substringBefore("internal suspend fun handleTicketSpacetimeCommand")
    val receive = handler.indexOf("val phoneReceiveUptimeMicros = SystemClock.elapsedRealtimeNanos() / 1_000L")
    val parse = handler.indexOf("json.parseToJsonElement(message)")
    val probe = handler.indexOf("\"clock_probe\" ->")
    val atWrite = handler.indexOf("client.sendTextAtWrite {")
    val send = handler.indexOf("val phoneSendUptimeMicros = SystemClock.elapsedRealtimeNanos() / 1_000L")

    assertTrue(receive >= 0)
    assertTrue(receive < parse)
    assertTrue(parse < probe)
    assertTrue(probe < atWrite)
    assertTrue(atWrite < send)
    assertTrue(handler.contains("TicketClockProbeProtocol.parseRequest(element) ?: return"))
    assertTrue(handler.contains("TicketClockProbeProtocol.encodeResult("))
    assertFalse(handler.substring(probe).contains("requestKeyFrame("))
    assertFalse(handler.substring(probe).contains("startTicketSession("))
    assertFalse(handler.substring(probe).contains("markViewerInput("))

    val lock = socket.indexOf("return synchronized(writeLock)")
    val payload = socket.indexOf("val currentPayload = payloadAtWrite?.invoke() ?: payload")
    val write = socket.indexOf("output.write(0x80 or opcode)")
    assertTrue(lock >= 0)
    assertTrue(lock < payload)
    assertTrue(payload < write)
  }

  @Test
  fun protocolIsBoundedAndContainsOnlyTheNtpSampleFields() {
    assertTrue(protocol.contains("const val MAX_PROBE_ID_BYTES = 64"))
    assertTrue(protocol.contains("[A-Za-z0-9._:-]{1,\$MAX_PROBE_ID_BYTES}"))
    listOf(
      "\"type\", \"clock_probe_result\"",
      "\"probeId\", request.probeId",
      "\"serverSendUnixMicros\", request.serverSendUnixMicros",
      "\"phoneReceiveUptimeMicros\", phoneReceiveUptimeMicros",
      "\"phoneSendUptimeMicros\", phoneSendUptimeMicros"
    ).forEach { assertTrue(protocol.contains(it)) }
    assertFalse(protocol.contains("requestKeyFrame"))
    assertFalse(protocol.contains("capture"))
    assertFalse(protocol.contains("ticketAction"))
  }

  private fun source(relative: String): String {
    val path = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/ticket/$relative")
    ).firstOrNull(Files::exists) ?: error("Missing source file: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
