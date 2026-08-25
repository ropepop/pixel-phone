package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketPendingKeyFrameRequestTest {
  @Test
  fun repeatedOffersCoalesceToTheLatestRequestAndConsumeOnce() {
    val pending = TicketPendingKeyFrameRequest()

    pending.offer("viewer_sequence_gap_while_source_healthy")
    pending.offer("decoder_reset_while_source_healthy")

    assertEquals("decoder_reset_while_source_healthy", pending.take()?.reason)
    assertNull(pending.take())
  }

  @Test
  fun failedFlushRestoreNeverOverwritesANewerRequest() {
    val pending = TicketPendingKeyFrameRequest()
    pending.offer("flushing")
    val flushing = pending.take()!!

    pending.offer("newer")
    val newer = pending.take()!!

    assertFalse(pending.restoreIfEmpty(flushing))
    assertTrue(pending.restoreIfEmpty(newer))
    assertEquals("newer", pending.take()?.reason)
  }
}
