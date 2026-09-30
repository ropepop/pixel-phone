package lv.jolkins.pixelorchestrator.app.telemetry

import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual former queue owner compared at enqueue/drain/recent/byte/delivery boundaries. */
class TelemetryParityTest {
  @Test fun validationAndRetryArithmeticPreservePriorContract() {
    var comparisons = 0
    for (build in listOf("", "release", "a.b.c.d", "0.1.2.255", "256.1.2.3", "000.1.2.3", "a".repeat(96), "a".repeat(97), "unsafe/name", "é", "0000000000000000000000001.2.3.4")) {
      assertEquals(runCatching { LegacyOrchestratorTelemetryPayload.validateBuildId(build) }.exceptionOrNull()?.message,
        runCatching { OrchestratorTelemetryPayload.validateBuildId(build) }.exceptionOrNull()?.message); comparisons++
    }
    for (type in OrchestratorTelemetryEventType.entries) for (category in OrchestratorTelemetryCleanupCategory.entries) {
      for (n in listOf(-1L,0L,1L,604_800_000L,604_800_001L,1_000_000_000L,1_099_511_627_776L,Long.MAX_VALUE)) {
        assertEquals(runCatching { LegacyOrchestratorTelemetryDraft(LegacyOrchestratorTelemetryEventType.valueOf(type.name),LegacyOrchestratorTelemetryComponent.STACK,LegacyOrchestratorTelemetryCleanupCategory.valueOf(category.name),LegacyOrchestratorTelemetryStatus.UNKNOWN,durationMillis=n,count=n,byteCount=n) }.exceptionOrNull()?.message,
          runCatching { OrchestratorTelemetryDraft(type,OrchestratorTelemetryComponent.STACK,category,OrchestratorTelemetryStatus.UNKNOWN,durationMillis=n,count=n,byteCount=n) }.exceptionOrNull()?.message); comparisons++
      }
    }
    for (base in listOf(1L,1_000L,Long.MAX_VALUE / 2 + 1)) for (max in listOf(base,Long.MAX_VALUE)) for (failure in listOf(0,1,2,20,63,Int.MAX_VALUE)) {
      val old = runCatching { LegacyOrchestratorTelemetryBackoffPolicy(base,max).delayMillis(failure) }
      val next = runCatching { OrchestratorTelemetryBackoffPolicy(base,max).delayMillis(failure) }
      assertEquals(old.getOrNull(),next.getOrNull()); assertEquals(old.exceptionOrNull()?.message,next.exceptionOrNull()?.message); comparisons++
    }
    println("TELEMETRY_VALIDATION_PARITY_OK comparisons=$comparisons")
  }
  @Test fun actualQueueJourneysPreserveEffectsPriorityExpiryEvictionAndRecentState() = runBlocking {
    var comparisons = 0
    for (capacity in listOf(100,250,1_000,4*1024*1024)) for (age in listOf(10L,999L,86_400_000L)) {
      var now = 1_000L; var oldId=0; var nextId=0; var oldAttempt=0; var nextAttempt=0
      val sentOld=mutableListOf<String>(); val sentNext=mutableListOf<String>()
      val old=LegacyOrchestratorTelemetryClient("synthetic",LegacyOrchestratorTelemetryTransport {
        sentOld+=it.reducerRequestBody(); oldAttempt++
        when(oldAttempt%3) { 0->LegacyOrchestratorTelemetrySendResult.Success; 1->LegacyOrchestratorTelemetrySendResult.Retryable(503); else->LegacyOrchestratorTelemetrySendResult.Rejected(400) }
      },{now},LegacyOrchestratorTelemetryCorrelationIdFactory { "%024x".format(++oldId) },maxQueueBytes=capacity,maxEventAgeMillis=age,recentEventLimit=5)
      val next=OrchestratorTelemetryClient("synthetic",OrchestratorTelemetryTransport {
        sentNext+=it.reducerRequestBody(); nextAttempt++
        when(nextAttempt%3) { 0->OrchestratorTelemetrySendResult.Success; 1->OrchestratorTelemetrySendResult.Retryable(503); else->OrchestratorTelemetrySendResult.Rejected(400) }
      },{now},OrchestratorTelemetryCorrelationIdFactory { "%024x".format(++nextId) },maxQueueBytes=capacity,maxEventAgeMillis=age,recentEventLimit=5)
      val random=Random(184)
      repeat(80) { step ->
        now += listOf(0L,1L,10L,999L,1_000L,-1L)[random.nextInt(6)]
        if (step%3==0) {
          val drained=old.drainDue(4)
          assertEquals(OrchestratorTelemetryDrainResult(drained.sent,drained.retryScheduled,drained.rejected,drained.remaining),next.drainDue(4)); assertEquals(sentOld,sentNext)
        } else {
          val priority=OrchestratorTelemetryPriority.entries[random.nextInt(4)]
          assertEquals(old.enqueue(LegacyOrchestratorTelemetryDraft(LegacyOrchestratorTelemetryEventType.MANUAL_ACTION,LegacyOrchestratorTelemetryComponent.STACK,status=LegacyOrchestratorTelemetryStatus.HEALTHY,priority=LegacyOrchestratorTelemetryPriority.valueOf(priority.name))).toString(),
            next.enqueue(OrchestratorTelemetryDraft(OrchestratorTelemetryEventType.MANUAL_ACTION,OrchestratorTelemetryComponent.STACK,status=OrchestratorTelemetryStatus.HEALTHY,priority=priority)).toString())
        }
        assertEquals(old.queuedEventCount(),next.queuedEventCount())
        assertEquals(old.queuedSerializedBytes(),next.queuedSerializedBytes())
        assertEquals(old.droppedEventCount(),next.droppedEventCount())
        assertEquals(old.recentEvents().toString().replace("LegacyOrchestratorTelemetry","OrchestratorTelemetry"),next.recentEvents().toString())
        comparisons+=6
      }
    }
    println("TELEMETRY_QUEUE_PARITY_OK comparisons=$comparisons")
  }
}
