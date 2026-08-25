package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
  fun requiredStartupPhasesSurvivePressureOnlyForTheNewestTraceGeneration() {
    var now = 5_000L
    val json = Json
    fun startup(event: String, traceId: String, uptime: Long): String = buildJsonObject {
      put("type", "ticket_trace_event")
      put("event", event)
      put("traceId", traceId)
      put("eventAtPhoneUptimeMillis", uptime.toString())
      put("eventAtEpochMillis", (1_000_000L + uptime).toString())
    }.toString()
    fun payload(message: String) = json.parseToJsonElement(message).jsonObject
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        runCatching { TicketSpacetimeCriticalMessagePolicy.key(payload(message)) }.getOrNull()
      },
      criticalReplacement = { message ->
        runCatching { TicketSpacetimeCriticalMessagePolicy.replacement(payload(message)) }.getOrNull()
      },
      nowMillis = { now }
    )
    val oldTrace = "startup_aaaaaaaa"
    val newTrace = "startup_bbbbbbbb"
    val oldOpened = startup("stream_client_opened", oldTrace, 100L)
    val oldRequested = startup("startup_phase_session_start_requested", oldTrace, 110L)
    outbox.enqueue(oldOpened)
    outbox.enqueue(oldRequested)
    repeat(10) { index -> outbox.enqueue("lossy:$index") }

    assertTrue(outbox.peek().contains(oldOpened))
    assertTrue(outbox.peek().contains(oldRequested))
    assertEquals(4, outbox.peek().size)

    val newOpened = startup("stream_client_opened", newTrace, 200L)
    val newImmediate = startup("stream_client_immediate_start", newTrace, 201L)
    outbox.enqueue(newOpened)
    outbox.enqueue(newImmediate)
    assertFalse(outbox.peek().any { it.contains(oldTrace) })
    assertTrue(outbox.peek().contains(newOpened))
    assertTrue(outbox.peek().contains(newImmediate))

    val lateOldKeyframe = startup("startup_phase_first_keyframe_encoded", oldTrace, 150L)
    outbox.enqueue(lateOldKeyframe)
    assertFalse(outbox.peek().contains(lateOldKeyframe))
    assertTrue(outbox.peek().contains(newOpened))

    outbox.acknowledge(newOpened)
    assertFalse(outbox.peek().contains(newOpened))
    assertTrue(outbox.peek().contains(newImmediate))

    now += 5_001L
    assertFalse(outbox.peek().contains(newImmediate))
  }

  @Test
  fun lateSupersededSocketCompletionCannotReplaceNewSocketInPhoneOutbox() {
    val json = Json
    fun socketEvent(event: String, traceId: String, socketGeneration: Long, uptime: Long): String =
      buildJsonObject {
        put("type", "ticket_trace_event")
        put("event", event)
        put("traceId", traceId)
        put("detail_generation", socketGeneration.toString())
        put("eventAtPhoneUptimeMillis", uptime.toString())
      }.toString()
    fun payload(message: String) = json.parseToJsonElement(message).jsonObject
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        runCatching { TicketSpacetimeCriticalMessagePolicy.key(payload(message)) }.getOrNull()
      },
      criticalReplacement = { message ->
        runCatching { TicketSpacetimeCriticalMessagePolicy.replacement(payload(message)) }.getOrNull()
      },
      nowMillis = { 1_000L }
    )
    val socketB = "startup_bbbbbbbb"
    val socketC = "startup_cccccccc"
    val openedB = socketEvent("stream_client_opened", socketB, socketGeneration = 2L, uptime = 100L)
    val openedC = socketEvent("stream_client_opened", socketC, socketGeneration = 3L, uptime = 200L)
    val lateResultB = socketEvent(
      "stream_client_immediate_start_result",
      socketB,
      socketGeneration = 2L,
      uptime = 300L
    )

    outbox.enqueue(openedB)
    outbox.enqueue(openedC)
    outbox.enqueue(lateResultB)

    val pending = outbox.peek()
    assertEquals(listOf(openedC), pending)
    assertFalse(pending.any { it.contains(socketB) })
    assertEquals(2L, TicketSpacetimeCriticalMessagePolicy.replacement(payload(lateResultB))?.socketGeneration)
  }

  @Test
  fun currentStartupEvidenceStaysBoundedAndKeepsFirstPhaseOccurrence() {
    val json = Json
    fun trace(event: String, traceId: String, uptime: Long, detail: String = ""): String = buildJsonObject {
      put("type", "ticket_trace_event")
      put("event", event)
      put("traceId", traceId)
      put("eventAtPhoneUptimeMillis", uptime.toString())
      if (detail.isNotBlank()) put("detail", detail)
    }.toString()
    fun parsed(message: String) = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull()
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      maxCriticalMessages = 11,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::key)
      },
      criticalReplacement = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::replacement)
      },
      nowMillis = { 1_000L }
    )
    val traceId = "startup_aaaaaaaa"
    val final = trace(
      "latest_ticket_reselect_final_ticket_detail_ticket_card_selection_succeeded",
      "",
      50L
    )
    val requiredEvents = listOf(
      "stream_client_opened",
      "stream_client_immediate_start",
      "stream_client_immediate_start_result",
      "stream_client_immediate_start_coalesced",
      "startup_phase_pixel_start_command_received",
      "startup_phase_session_start_requested",
      "startup_phase_root_capture_start_requested",
      "startup_phase_capture_helper_active",
      "startup_phase_vivi_foreground_confirmed",
      "startup_phase_first_keyframe_encoded"
    )
    val required = requiredEvents.mapIndexed { index, event ->
      trace(event, traceId, 100L + index, detail = "first")
    }
    outbox.enqueue(final)
    required.forEach(outbox::enqueue)
    val duplicateOpened = trace(requiredEvents.first(), traceId, 999L, detail = "duplicate")
    outbox.enqueue(duplicateOpened)

    val pending = outbox.peek()
    assertEquals(11, pending.size)
    assertTrue(pending.contains(final))
    required.forEach { message -> assertTrue(pending.contains(message)) }
    assertFalse(pending.contains(duplicateOpened))
  }

  @Test
  fun functionalCriticalMessagesOutrankStartupDiagnosticsUnderSaturation() {
    val json = Json
    fun startup(event: String, uptime: Long): String = buildJsonObject {
      put("type", "ticket_trace_event")
      put("event", event)
      put("traceId", "startup_aaaaaaaa")
      put("eventAtPhoneUptimeMillis", uptime.toString())
    }.toString()
    fun functional(type: String, requestId: String, ticketState: String = ""): String = buildJsonObject {
      put("type", type)
      put("requestId", requestId)
      if (ticketState.isNotBlank()) put("ticketState", ticketState)
    }.toString()
    fun parsed(message: String) = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull()
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      maxCriticalMessages = 6,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::key)
      },
      criticalReplacement = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::replacement)
      },
      nowMillis = { 1_000L }
    )
    val startupEvents = listOf(
      "stream_client_opened",
      "stream_client_immediate_start",
      "stream_client_immediate_start_result",
      "stream_client_immediate_start_coalesced",
      "startup_phase_pixel_start_command_received",
      "startup_phase_session_start_requested",
      "startup_phase_root_capture_start_requested",
      "startup_phase_capture_helper_active",
      "startup_phase_vivi_foreground_confirmed",
      "startup_phase_first_keyframe_encoded"
    )
    startupEvents.take(6).mapIndexed { index, event ->
      startup(event, 100L + index)
    }.forEach(outbox::enqueue)

    val functionalMessages = listOf(
      functional("ticket_state_event", "state-request", "unactivated_ready"),
      functional("control_code_progress", "control-request"),
      functional("control_code_result", "control-request"),
      functional("control_code_cleanup_complete", "control-request")
    )
    functionalMessages.forEach(outbox::enqueue)

    var pending = outbox.peek()
    assertEquals(10, pending.size)
    functionalMessages.forEach { message -> assertTrue(pending.contains(message)) }
    assertEquals(functionalMessages, outbox.peek(maxMessages = functionalMessages.size))
    assertEquals(6, pending.count { message ->
      parsed(message)?.let { payload ->
        TicketSpacetimeCriticalMessagePolicy.isRequiredStartupTraceEvent(
          payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
      } == true
    })

    startupEvents.drop(6).mapIndexed { index, event ->
      startup(event, 200L + index)
    }.forEach(outbox::enqueue)

    pending = outbox.peek()
    assertEquals(10, pending.size)
    functionalMessages.forEach { message -> assertTrue(pending.contains(message)) }
    assertEquals(functionalMessages, outbox.peek(maxMessages = functionalMessages.size))
    assertEquals(6, pending.count { message ->
      parsed(message)?.let { payload ->
        TicketSpacetimeCriticalMessagePolicy.isRequiredStartupTraceEvent(
          payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
      } == true
    })

    val functionalOnlyOutbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      maxCriticalMessages = functionalMessages.size,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::key)
      },
      criticalReplacement = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::replacement)
      },
      nowMillis = { 1_000L }
    )
    functionalMessages.forEach(functionalOnlyOutbox::enqueue)
    startupEvents.mapIndexed { index, event ->
      startup(event, 300L + index)
    }.forEach(functionalOnlyOutbox::enqueue)

    assertEquals(8, functionalOnlyOutbox.peek().size)
    assertEquals(functionalMessages, functionalOnlyOutbox.peek(maxMessages = functionalMessages.size))
  }

  @Test
  fun functionalRetryBacklogIsIndependentFromTheStartupDiagnosticLimit() {
    val json = Json
    fun parsed(message: String) = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull()
    val outbox = TicketSpacetimePhoneOutbox(
      maxLossyMessages = 2,
      maxCriticalMessages = 3,
      criticalTtlMillis = 5_000L,
      criticalKey = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::key)
      },
      criticalReplacement = { message ->
        parsed(message)?.let(TicketSpacetimeCriticalMessagePolicy::replacement)
      },
      nowMillis = { 1_000L }
    )
    val functionalMessages = buildList {
      repeat(90) { index ->
        add(buildJsonObject {
          put("type", "ticket_state_event")
          put("requestId", "ticket-$index")
          put("ticketState", "state-$index")
        }.toString())
      }
      repeat(15) { index ->
        add(buildJsonObject {
          put("type", "rigassatiksme_qr_result")
          put("requestId", "rs-$index")
        }.toString())
      }
    }
    functionalMessages.forEach(outbox::enqueue)

    assertEquals(105, outbox.peek().size)
    assertEquals(functionalMessages, outbox.peek())

    val startupEvents = listOf(
      "stream_client_opened",
      "stream_client_immediate_start",
      "stream_client_immediate_start_result",
      "stream_client_immediate_start_coalesced",
      "startup_phase_pixel_start_command_received",
      "startup_phase_session_start_requested",
      "startup_phase_root_capture_start_requested",
      "startup_phase_capture_helper_active",
      "startup_phase_vivi_foreground_confirmed",
      "startup_phase_first_keyframe_encoded"
    )
    startupEvents.mapIndexed { index, event ->
      buildJsonObject {
        put("type", "ticket_trace_event")
        put("event", event)
        put("traceId", "startup_aaaaaaaa")
        put("eventAtPhoneUptimeMillis", (500L + index).toString())
      }.toString()
    }.forEach(outbox::enqueue)

    val pending = outbox.peek()
    assertEquals(108, pending.size)
    functionalMessages.forEach { message -> assertTrue(pending.contains(message)) }
    assertEquals(functionalMessages, outbox.peek(maxMessages = functionalMessages.size))
    assertEquals(3, pending.count { message ->
      parsed(message)?.let { payload ->
        TicketSpacetimeCriticalMessagePolicy.isRequiredStartupTraceEvent(
          payload["event"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
      } == true
    })
  }

  @Test
  fun requiredStartupRetentionRejectsUnboundedOrUncorrelatedTraceIds() {
    val payload = buildJsonObject {
      put("type", "ticket_trace_event")
      put("event", "startup_phase_first_keyframe_encoded")
      put("traceId", "private-browser-session")
      put("eventAtPhoneUptimeMillis", "300")
    }
    assertNull(TicketSpacetimeCriticalMessagePolicy.key(payload))
    assertNull(TicketSpacetimeCriticalMessagePolicy.replacement(payload))
  }

  @Test
  fun finalReselectTraceTransfersToTheAsyncQueueUntilTheRetrySucceeds() = runTest {
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
      },
      retainedRetryMillis = 10L,
      dispatcher = StandardTestDispatcher(testScheduler)
    )
    val event = TicketOperationalLogEvent(
      id = "log_retained_test",
      level = "info",
      event = "pixel_ticket_latest_ticket_reselect_final_ticket_detail_ticket_card_selection_succeeded",
      correlationId = "",
      detailJson = "{}"
    )
    outbox.enqueue(finalTrace)

    assertTrue(queue.enqueueRetained(
      event = event,
      priority = 10,
      onUndelivered = { outbox.enqueue(finalTrace) }
    ))
    outbox.acknowledge(finalTrace)
    runCurrent()
    assertTrue(outbox.peek().isEmpty())
    assertEquals(1, attempts)

    advanceTimeBy(10L)
    runCurrent()
    assertFalse(outbox.peek().contains(finalTrace))
    assertEquals(2, attempts)
    queue.stop()
  }
}
