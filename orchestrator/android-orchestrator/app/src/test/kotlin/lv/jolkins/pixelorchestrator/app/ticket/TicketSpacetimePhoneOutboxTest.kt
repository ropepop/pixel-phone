package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSpacetimePhoneOutboxTest {
  @Test
  fun failedAndUnattemptedMessagesRemainUntilEachSuccessfulPublishIsAcknowledged() {
    var now = 1_000L
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 4,
      criticalTtlMillis = 5_000L,
      criticalKey = { message -> message.substringBefore(':').takeIf { message.startsWith("critical") } },
      nowMillis = { now }
    )
    outbox.enqueue("critical-a:first")
    outbox.enqueue("trace:first")
    outbox.enqueue("critical-b:second")

    val firstAttempt = outbox.peek()
    assertEquals(listOf("critical-a:first", "critical-b:second", "trace:first"), firstAttempt)
    outbox.acknowledge(firstAttempt[0])

    val retry = outbox.peek()
    assertEquals(listOf("critical-b:second", "trace:first"), retry)
    assertFalse(retry.contains("critical-a:first"))
  }

  @Test
  fun tracePressureCannotEvictCriticalMessagesAndNewestCriticalValueWins() {
    var now = 2_000L
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 3,
      criticalTtlMillis = 5_000L,
      criticalKey = { message -> "request-1".takeIf { message.startsWith("critical") } },
      nowMillis = { now }
    )
    outbox.enqueue("critical:queued")
    repeat(10) { index -> outbox.enqueue("trace:$index") }
    outbox.enqueue("critical:terminal")

    val pending = outbox.peek()
    assertTrue(pending.contains("critical:terminal"))
    assertFalse(pending.contains("critical:queued"))
    assertEquals(4, pending.size)

    now += 5_001L
    assertFalse(outbox.peek().any { it.startsWith("critical") })
  }

  @Test
  fun criticalResultOvertakesTraceBacklogOnTheNextBoundedPeek() {
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 80,
      criticalTtlMillis = 5_000L,
      criticalKey = { message -> "request-1".takeIf { message.startsWith("critical") } },
      nowMillis = { 3_000L }
    )
    repeat(80) { index -> outbox.enqueue("trace:$index") }

    assertEquals(listOf("trace:0"), outbox.peek(maxMessages = 1))
    outbox.enqueue("critical:result")

    assertEquals(listOf("critical:result"), outbox.peek(maxMessages = 1))
  }

  @Test
  fun finalReselectTraceIsCriticalUntilAcknowledgedWhileOrdinaryTracesStayLossy() {
    val json = Json
    val finalTrace = buildJsonObject {
      put("type", "ticket_trace_event")
      put("event", "latest_ticket_reselect_final_ticket_detail_ticket_card_selection_succeeded")
      put("eventAtPhoneUptimeMillis", "123456")
    }.toString()
    val ordinaryTrace = buildJsonObject {
      put("type", "ticket_trace_event")
      put("event", "latest_ticket_reselect_recovery_result")
      put("eventAtPhoneUptimeMillis", "123457")
    }
    assertNull(TicketSpacetimeCriticalMessagePolicy.key(ordinaryTrace))

    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        TicketSpacetimeCriticalMessagePolicy.key(
          json.parseToJsonElement(message).jsonObject
        )
      },
      nowMillis = { 4_000L }
    )
    repeat(10) { index ->
      outbox.enqueue(
        buildJsonObject {
          put("type", "ticket_trace_event")
          put("event", "latest_ticket_reselect_recovery_result")
          put("eventAtPhoneUptimeMillis", index.toString())
        }.toString()
      )
    }
    outbox.enqueue(finalTrace)

    assertEquals(listOf(finalTrace), outbox.peek(maxMessages = 1))
    assertTrue(outbox.peek().contains(finalTrace))
    outbox.acknowledge(finalTrace)
    assertFalse(outbox.peek().contains(finalTrace))
  }

  @Test
  fun finalReselectTraceStaysInPhoneOutboxUntilAwaitedLogSendSucceeds() = runTest {
    val json = Json
    val finalTrace = buildJsonObject {
      put("type", "ticket_trace_event")
      put("event", "latest_ticket_reselect_final_ticket_detail_ticket_card_selection_succeeded")
      put("eventAtPhoneUptimeMillis", "123456")
    }.toString()
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        TicketSpacetimeCriticalMessagePolicy.key(
          json.parseToJsonElement(message).jsonObject
        )
      },
      nowMillis = { 4_000L }
    )
    var attempts = 0
    val queue = TicketOperationalLogQueue(
      scope = this,
      sender = {
        attempts += 1
        if (attempts == 1) error("network unavailable")
      }
    )
    val event = TicketOperationalLogEvent(
      id = "log_retained_test",
      level = "info",
      event = "pixel_ticket_latest_ticket_reselect_final_ticket_detail_ticket_card_selection_succeeded",
      correlationId = "",
      detailJson = "{}"
    )
    outbox.enqueue(finalTrace)

    if (queue.sendRetained(event)) {
      outbox.acknowledge(finalTrace)
    }
    assertTrue(outbox.peek().contains(finalTrace))

    if (queue.sendRetained(event)) {
      outbox.acknowledge(finalTrace)
    }
    assertFalse(outbox.peek().contains(finalTrace))
    assertEquals(2, attempts)
    queue.stop()
  }
}
