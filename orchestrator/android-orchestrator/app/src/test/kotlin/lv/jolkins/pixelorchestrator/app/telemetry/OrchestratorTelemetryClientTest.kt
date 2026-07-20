package lv.jolkins.pixelorchestrator.app.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorTelemetryClientTest {
  @Test
  fun reducerBodyMatchesPrivateScalarContractAndExactUtf8Size() {
    val payload = OrchestratorTelemetryPayload.create(
      draft = draft(
        priority = OrchestratorTelemetryPriority.HIGH,
        durationMillis = 125,
        count = 3,
        byteCount = 4096
      ),
      correlationId = "0123456789abcdef01234567",
      buildId = "20260713T120000Z-a1b2c3d",
      createdAtEpochMillis = 1234
    )

    val expected =
      "[\"0123456789abcdef01234567\",\"health_change\",\"stack\",\"none\",\"degraded\",\"none\",\"high\",\"20260713T120000Z-a1b2c3d\",125,3,4096]"
    assertEquals(expected, payload.reducerRequestBody())
    assertEquals(expected.toByteArray(Charsets.UTF_8).size, payload.reducerRequestBytes().size)
    assertFalse(expected.contains("message"))
    assertFalse(expected.contains("json"))
    assertFalse(expected.contains("device"))
  }

  @Test
  fun defaultLimitsAreExactlyFourMibTwentyFourHoursAndTwentyRecentEvents() {
    assertEquals(4 * 1024 * 1024, OrchestratorTelemetryClient.MAX_QUEUE_BYTES)
    assertEquals(24L * 60 * 60 * 1_000, OrchestratorTelemetryClient.MAX_EVENT_AGE_MILLIS)
    assertEquals(20, OrchestratorTelemetryClient.MAX_RECENT_EVENTS)
  }

  @Test
  fun cleanupCategoryIsFixedRequiredAndIncludedBeforeScalarMeasurements() {
    val payload = OrchestratorTelemetryPayload.create(
      draft = OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.CLEANUP_RESULT,
        component = OrchestratorTelemetryComponent.CLEANUP,
        cleanupCategory = OrchestratorTelemetryCleanupCategory.ROOT_COMMAND_HISTORY,
        status = OrchestratorTelemetryStatus.COMPLETED,
        result = OrchestratorTelemetryResult.OK,
        count = 4,
        byteCount = 1_234_567
      ),
      correlationId = "0123456789abcdef01234567",
      buildId = "20260713T120000Z-a1b2c3d",
      createdAtEpochMillis = 1234
    )

    assertEquals(OrchestratorTelemetryCleanupCategory.ROOT_COMMAND_HISTORY, payload.cleanupCategory)
    assertEquals(
      "[\"0123456789abcdef01234567\",\"cleanup_result\",\"cleanup\",\"root_command_history\",\"completed\",\"ok\",\"normal\",\"20260713T120000Z-a1b2c3d\",0,4,1234567]",
      payload.reducerRequestBody()
    )
    assertFails {
      OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.CLEANUP_RESULT,
        component = OrchestratorTelemetryComponent.CLEANUP,
        status = OrchestratorTelemetryStatus.COMPLETED
      )
    }
    assertFails {
      OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.HEALTH_CHANGE,
        component = OrchestratorTelemetryComponent.STACK,
        cleanupCategory = OrchestratorTelemetryCleanupCategory.STACK_LOGS,
        status = OrchestratorTelemetryStatus.DEGRADED
      )
    }
  }

  @Test
  fun byteCeilingEvictsOldestLowerPriorityEventUsingSerializedBodySize() {
    val ids = SequentialIds()
    val first = payloadSize(ids.peek(), OrchestratorTelemetryPriority.LOW)
    val second = payloadSize(ids.peek(offset = 1), OrchestratorTelemetryPriority.NORMAL)
    val client = client(
      ids = ids,
      maxQueueBytes = first + second - 1
    )

    assertTrue(client.enqueue(draft(priority = OrchestratorTelemetryPriority.LOW)) is
      OrchestratorTelemetryEnqueueResult.Accepted)
    val result = client.enqueue(draft(priority = OrchestratorTelemetryPriority.NORMAL))

    assertTrue(result is OrchestratorTelemetryEnqueueResult.Accepted)
    result as OrchestratorTelemetryEnqueueResult.Accepted
    assertEquals(1, result.evictedEventCount)
    assertEquals(second, client.queuedSerializedBytes())
    assertEquals(1, client.queuedEventCount())
    assertEquals(1, client.droppedEventCount())
    assertEquals(OrchestratorTelemetryDeliveryState.EVICTED, client.recentEvents()[1].deliveryState)
  }

  @Test
  fun lowerPriorityEventCannotDisplaceCriticalBacklog() {
    val ids = SequentialIds()
    val criticalSize = payloadSize(ids.peek(), OrchestratorTelemetryPriority.CRITICAL)
    val client = client(ids = ids, maxQueueBytes = criticalSize)
    client.enqueue(draft(priority = OrchestratorTelemetryPriority.CRITICAL))

    val result = client.enqueue(draft(priority = OrchestratorTelemetryPriority.LOW))

    assertEquals(
      OrchestratorTelemetryEnqueueResult.Dropped(
        OrchestratorTelemetryDropReason.HIGHER_PRIORITY_BACKLOG
      ),
      result
    )
    assertEquals(1, client.queuedEventCount())
    assertEquals(1, client.droppedEventCount())
  }

  @Test
  fun eventExpiresFromRamAtExactlyTwentyFourHours() {
    var now = 10_000L
    val client = client(now = { now })
    client.enqueue(draft())
    now += OrchestratorTelemetryClient.MAX_EVENT_AGE_MILLIS - 1
    assertEquals(1, client.queuedEventCount())

    now += 1

    assertEquals(0, client.queuedEventCount())
    assertEquals(OrchestratorTelemetryDeliveryState.EXPIRED, client.recentEvents().single().deliveryState)
    assertEquals(1, client.droppedEventCount())
  }

  @Test
  fun retryBackoffLeavesPayloadInRamAndRetriesWhenDue() = runTest {
    var now = 1_000L
    val transport = FakeTransport(
      ArrayDeque(
        listOf(
          OrchestratorTelemetrySendResult.Retryable(503),
          OrchestratorTelemetrySendResult.Success
        )
      )
    )
    val client = client(now = { now }, transport = transport)
    client.enqueue(draft(priority = OrchestratorTelemetryPriority.HIGH))

    val first = client.drainDue()
    assertEquals(1, first.retryScheduled)
    assertEquals(1, client.queuedEventCount())
    assertEquals(OrchestratorTelemetryDeliveryState.RETRYING, client.recentEvents().single().deliveryState)

    now += 999
    assertEquals(0, client.drainDue().sent)
    assertEquals(1, transport.payloads.size)

    now += 1
    assertEquals(1, client.drainDue().sent)
    assertEquals(0, client.queuedEventCount())
    assertEquals(OrchestratorTelemetryDeliveryState.SENT, client.recentEvents().single().deliveryState)
  }

  @Test
  fun recentViewKeepsTwentySafeEventsAfterSuccessfulDelivery() = runTest {
    val client = client(transport = FakeTransport())
    repeat(25) { client.enqueue(draft(count = it.toLong())) }

    client.drainDue(maxEvents = 25)

    val recent = client.recentEvents()
    assertEquals(20, recent.size)
    assertEquals(24, recent.first().count)
    assertEquals(5, recent.last().count)
    assertTrue(recent.all { it.deliveryState == OrchestratorTelemetryDeliveryState.SENT })
  }

  @Test
  fun droppedEventsProduceOneSafeSummaryBeforeNormalDrain() = runTest {
    val ids = SequentialIds()
    val criticalSize = payloadSize(ids.peek(), OrchestratorTelemetryPriority.CRITICAL)
    val transport = FakeTransport()
    val client = client(
      ids = ids,
      maxQueueBytes = criticalSize + 256,
      transport = transport
    )
    client.enqueue(draft(priority = OrchestratorTelemetryPriority.CRITICAL))
    repeat(3) { client.enqueue(draft(priority = OrchestratorTelemetryPriority.LOW)) }

    client.drainDue(maxEvents = 8)

    val summary = transport.payloads.firstOrNull {
      it.eventType == OrchestratorTelemetryEventType.DROPPED_EVENT_SUMMARY
    }
    assertTrue(summary != null)
    assertTrue(summary!!.count >= 1)
    assertEquals(OrchestratorTelemetryComponent.TELEMETRY, summary.component)
    assertEquals(OrchestratorTelemetryResult.DROPPED, summary.result)
  }

  @Test
  fun transportClassifiesRetryableAndPermanentFailuresWithoutResponseText() {
    assertEquals(
      OrchestratorTelemetrySendResult.Success,
      SpacetimeOrchestratorTelemetryTransport.classifyHttpStatus(204)
    )
    assertEquals(
      OrchestratorTelemetrySendResult.Retryable(429),
      SpacetimeOrchestratorTelemetryTransport.classifyHttpStatus(429)
    )
    assertEquals(
      OrchestratorTelemetrySendResult.Retryable(503),
      SpacetimeOrchestratorTelemetryTransport.classifyHttpStatus(503)
    )
    assertEquals(
      OrchestratorTelemetrySendResult.Rejected(401),
      SpacetimeOrchestratorTelemetryTransport.classifyHttpStatus(401)
    )
  }

  @Test
  fun configRedactsBearerTokenAndPayloadRejectsSensitiveTokenShapes() {
    val config = SpacetimeOrchestratorTelemetryConfig(
      host = "https://maincloud.spacetimedb.com",
      database = "pixel-orchestrator-observability-prod",
      bearerToken = "super-secret-token"
    )
    assertFalse(config.toString().contains("super-secret-token"))
    assertTrue(config.toString().contains("<redacted>"))
    assertFails { client(buildId = "/data/local/pixel-stack") }
    assertFails { client(buildId = "100.76.50.43") }
    assertFails {
      OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.CLEANUP_RESULT,
        component = OrchestratorTelemetryComponent.CLEANUP,
        cleanupCategory = OrchestratorTelemetryCleanupCategory.APP_CACHE,
        status = OrchestratorTelemetryStatus.COMPLETED,
        byteCount = -1
      )
    }
  }

  private fun draft(
    priority: OrchestratorTelemetryPriority = OrchestratorTelemetryPriority.NORMAL,
    durationMillis: Long = 0,
    count: Long = 0,
    byteCount: Long = 0
  ) = OrchestratorTelemetryDraft(
    eventType = OrchestratorTelemetryEventType.HEALTH_CHANGE,
    component = OrchestratorTelemetryComponent.STACK,
    status = OrchestratorTelemetryStatus.DEGRADED,
    priority = priority,
    durationMillis = durationMillis,
    count = count,
    byteCount = byteCount
  )

  private fun client(
    buildId: String = "20260713T120000Z-a1b2c3d",
    ids: SequentialIds = SequentialIds(),
    now: () -> Long = { 1_000L },
    transport: OrchestratorTelemetryTransport = FakeTransport(),
    maxQueueBytes: Int = OrchestratorTelemetryClient.MAX_QUEUE_BYTES
  ) = OrchestratorTelemetryClient(
    buildId = buildId,
    transport = transport,
    nowMillis = now,
    correlationIds = ids,
    maxQueueBytes = maxQueueBytes
  )

  private fun payloadSize(
    id: String,
    priority: OrchestratorTelemetryPriority
  ): Int = OrchestratorTelemetryPayload.create(
    draft = draft(priority = priority),
    correlationId = id,
    buildId = "20260713T120000Z-a1b2c3d",
    createdAtEpochMillis = 1_000
  ).reducerRequestBytes().size

  private fun assertFails(block: () -> Unit) {
    assertTrue(runCatching(block).isFailure)
  }

  private class SequentialIds : OrchestratorTelemetryCorrelationIdFactory {
    private var next = 0L

    override fun nextId(): String = (next++).toString(16).padStart(24, '0')

    fun peek(offset: Long = 0): String = (next + offset).toString(16).padStart(24, '0')
  }

  private class FakeTransport(
    private val results: ArrayDeque<OrchestratorTelemetrySendResult> = ArrayDeque()
  ) : OrchestratorTelemetryTransport {
    val payloads = mutableListOf<OrchestratorTelemetryPayload>()

    override suspend fun send(
      payload: OrchestratorTelemetryPayload
    ): OrchestratorTelemetrySendResult {
      payloads += payload
      return if (results.isEmpty()) {
        OrchestratorTelemetrySendResult.Success
      } else {
        results.removeFirst()
      }
    }
  }
}
