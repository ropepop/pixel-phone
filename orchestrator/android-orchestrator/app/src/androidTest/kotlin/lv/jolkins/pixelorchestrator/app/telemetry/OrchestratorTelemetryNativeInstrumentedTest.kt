package lv.jolkins.pixelorchestrator.app.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android JNI and the actual RAM queue; synthetic events never leave this process. */
@RunWith(AndroidJUnit4::class)
class OrchestratorTelemetryNativeInstrumentedTest {
  @Test fun nativeQueueProtectsRetryPriorityExpiryAndDeliveryWithoutNetwork() = runBlocking {
    var now=1_000L; var id=0; var attempt=0
    val delivered=mutableListOf<String>()
    val client=OrchestratorTelemetryClient("synthetic",OrchestratorTelemetryTransport {
      delivered += it.eventType.name; attempt++
      if (attempt==1) OrchestratorTelemetrySendResult.Retryable(503) else OrchestratorTelemetrySendResult.Success
    },{now},OrchestratorTelemetryCorrelationIdFactory { "%024x".format(++id) },maxEventAgeMillis=5_000L)
    val draft=OrchestratorTelemetryDraft(OrchestratorTelemetryEventType.MANUAL_ACTION,OrchestratorTelemetryComponent.STACK,status=OrchestratorTelemetryStatus.HEALTHY)
    assertTrue(client.enqueue(draft) is OrchestratorTelemetryEnqueueResult.Accepted)
    assertEquals(OrchestratorTelemetryDrainResult(0,1,0,1),client.drainDue())
    assertEquals(OrchestratorTelemetryDrainResult(0,0,0,1),client.drainDue())
    now=2_000L
    assertEquals(OrchestratorTelemetryDrainResult(1,0,0,0),client.drainDue())
    assertEquals(listOf("MANUAL_ACTION","MANUAL_ACTION"),delivered)
    client.enqueue(draft); now=7_000L
    assertEquals(0,client.queuedEventCount()); assertEquals(1L,client.droppedEventCount())
    assertEquals(OrchestratorTelemetryDeliveryState.EXPIRED,client.recentEvents().first().deliveryState)
    assertEquals(0,client.queuedSerializedBytes())
  }
}
