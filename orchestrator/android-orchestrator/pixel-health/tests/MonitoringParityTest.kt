package lv.jolkins.pixelorchestrator.app.ticket

import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/** Native ABI compared with the prior working owner across clock, epoch and evidence boundaries. */
class MonitoringParityTest {
  @Test fun observationsAndStatefulCadenceMatchPreviousOwner() {
    val clocks = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 30_000, -1L, 0L, 1L, 999L,
      30_000L, 300_000L, Long.MAX_VALUE - 30_000, Long.MAX_VALUE)
    var comparisons = 0
    for (state in TicketVisualPhoneState.entries + listOf(null)) for (busy in listOf(false, true)) {
      for (captured in clocks) {
        val old = LegacyticketMonitoringObservation(state, busy, captured)
        val next = ticketMonitoringObservation(state, busy, captured)
        assertEquals(old.status, next.status); assertEquals(old.reason, next.reason)
        assertEquals(old.capturedAtMillis, next.capturedAtMillis)
        for (now in clocks) { assertEquals(old.fresh(now), next.fresh(now)); comparisons++ }
      }
    }
    val random = Random(3971)
    repeat(100) {
      val old = LegacyTicketMonitoringSchedule()
      val next = TicketMonitoringSchedule()
      var now = clocks[it % clocks.size]
      repeat(40) { step ->
        now += listOf(0L, 1L, 14_999L, 15_000L, 29_999L, 30_000L, 180_000L, 300_000L, -1L)[random.nextInt(9)]
        val config = when (random.nextInt(4)) {
          0 -> null
          1 -> TicketMonitoringConfig(false, "off")
          else -> TicketMonitoringConfig(true, "epoch${step / 10}")
        }
        val state = TicketVisualPhoneState.entries[random.nextInt(TicketVisualPhoneState.entries.size)]
        val busy = random.nextBoolean()
        val captured = now - listOf(0L, 1L, 29_999L, 30_000L, -1L)[random.nextInt(5)]
        val observation = ticketMonitoringObservation(state, busy, captured)
        val prior = LegacyticketMonitoringObservation(state, busy, captured)
        when (random.nextInt(3)) {
          0 -> assertEquals(old.configure(config?.let { LegacyTicketMonitoringConfig(it.enabled,it.epoch) }, now), next.configure(config,now))
          1 -> { old.checked(now); next.checked(now) }
          2 -> { old.reported(prior,now); next.reported(observation,now) }
        }
        assertEquals(old.acceptCapturedAfter,next.acceptCapturedAfter)
        assertEquals(old.checkDue(now),next.checkDue(now))
        assertEquals(old.checkDue(now,prior),next.checkDue(now,observation))
        assertEquals(old.shouldReport(prior,now),next.shouldReport(observation,now))
        comparisons += 4
      }
    }
    println("MONITORING_PARITY_OK comparisons=$comparisons")
  }
}
