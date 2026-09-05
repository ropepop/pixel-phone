package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCodecInputLedgerTest {
  @Test
  fun pairsDelayedCodecOutputsWithInputsInPostOrder() {
    val ledger = TicketCodecInputLedger()
    ledger.add(stage(attempt = 1L, inputUs = 120L))
    ledger.add(stage(attempt = 1L, inputUs = 130L))
    ledger.add(stage(attempt = 2L, inputUs = 220L))

    assertEquals(1L, ledger.take().captureAttemptId)
    assertEquals(1L, ledger.take().captureAttemptId)
    assertEquals(2L, ledger.take().captureAttemptId)
    assertEquals(0, ledger.size())
  }

  @Test
  fun rejectsMissingOutputOwnerAndBoundedBacklogOverflow() {
    val empty = TicketCodecInputLedger()
    assertTrue(runCatching { empty.take() }.isFailure)

    val full = TicketCodecInputLedger()
    repeat(TicketCodecInputLedger.MAX_PENDING_INPUTS) { index ->
      full.add(stage(attempt = index + 1L, inputUs = 120L + index))
    }
    assertTrue(runCatching {
      full.add(stage(attempt = 99L, inputUs = 999L))
    }.isFailure)
  }

  @Test
  fun tracksTheExactInputUntilItsCodecOutputConsumesIt() {
    val ledger = TicketCodecInputLedger()
    val pending = stage(attempt = 7L, inputUs = 220L)
    ledger.add(pending)

    assertTrue(ledger.contains(pending))
    assertFalse(ledger.contains(stage(attempt = 7L, inputUs = 220L)))
    assertSame(pending, ledger.take())
    assertFalse(ledger.contains(pending))
  }

  private fun stage(attempt: Long, inputUs: Long) = TicketCodecInputLedger.InputStage(
    attempt,
    9L,
    inputUs - 20L,
    inputUs - 10L,
    inputUs
  )
}
