package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
