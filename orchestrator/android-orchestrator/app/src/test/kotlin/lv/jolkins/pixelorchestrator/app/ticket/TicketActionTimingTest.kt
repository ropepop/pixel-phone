package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketActionTimingTest {
  @Test fun onlyFirstInputIsMeasuredAndMissingInputIsNotReportedAsZero() {
    var now = 100L
    val timing = TicketActionTiming({ now })
    now = 120L
    timing.mark(TicketActionTiming.Phase.ADMITTED)
    assertEquals("admitted_ms=20", timing.detail())
    now = 700L
    timing.mark(TicketActionTiming.Phase.INPUT_REQUESTED)
    now = 1700L
    timing.mark(TicketActionTiming.Phase.INPUT_REQUESTED)
    assertEquals("admitted_ms=20 input_requested_ms=600", timing.detail())
  }
}
