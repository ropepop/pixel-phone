package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.*
import org.junit.Test

class TicketCodePublicationTest {
  private fun result(sequence: Long) = TicketCodePublication(
    "request", TicketCodePublicationKind.GENERATED, status = "succeeded", reason = "generated",
    cleanupPending = true, epoch = 7, sequence = sequence, proof = "generated_visual",
    proofAt = "2026-09-07T00:00:00Z"
  )

  @Test fun delayedSendCannotRemoveReplacementAndResultPrecedesProgress() {
    val outbox = TicketSpacetimePhoneOutbox(300_000) { 100L }
    val progress = TicketCodePublication("request", TicketCodePublicationKind.PROGRESS, "running")
    outbox.enqueue(progress)
    val first = result(10)
    outbox.enqueue(first)
    assertEquals(first, outbox.peek())
    val replacement = result(11)
    outbox.enqueue(replacement)
    outbox.acknowledge(first)
    assertEquals(replacement, outbox.peek())
    outbox.acknowledge(replacement)
    assertEquals(progress, outbox.peek())
    outbox.acknowledge(progress)
    assertNull(outbox.peek())
  }

  @Test fun publicationRetainsTheExactTupleAndCleanupHasNoPictureAuthority() {
    assertEquals(listOf("ticket", "request", "succeeded", "generated", "",
      "7", "11", "11", "7", "11", "generated_visual", "2026-09-07T00:00:00Z", true, "now"),
      result(11).reducerArguments("ticket", "pixel", "now"))
    val ready = TicketCodePublication("request", TicketCodePublicationKind.READY, cleanupRevision = "pc-clean")
    assertEquals(listOf("ticket", "pixel", "request", "pc-clean", "", "", "now"),
      ready.reducerArguments("ticket", "pixel", "now"))
    assertThrows(IllegalArgumentException::class.java) {
      result(0).reducerArguments("ticket", "pixel", "now")
    }
    assertThrows(IllegalArgumentException::class.java) {
      ready.copy(cleanupRevision = "old").reducerArguments("ticket", "pixel", "now")
    }
  }

  @Test fun expiryIsBoundedAndReplacementRenewsOnlyItsOwnDelivery() {
    var now = 10L
    val outbox = TicketSpacetimePhoneOutbox(100) { now }
    val first = result(10)
    outbox.enqueue(first)
    now = 110
    assertEquals(first, outbox.peek())
    outbox.enqueue(result(11))
    now = 111
    assertEquals(result(11), outbox.peek())
    now = 211
    assertNull(outbox.peek())
  }
}
