package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCaptureCadenceSchedulerTest {
  @Test fun proofConsumesDemandWithoutCatchUpOrAnExtraCapture() {
    val cadence = TicketCaptureCadenceScheduler(1000)
    cadence.parkOrdinaryCapture()
    assertEquals(-1L, cadence.waitMillis(1000, false))
    assertEquals(0L, cadence.waitMillis(1000, true))
    assertTrue(cadence.enableDemandGateAndLatchOrdinaryCapture(6000, 1000))
    cadence.beginCapture(1000, true)
    assertEquals(-1L, cadence.waitMillis(1001, false))
    assertEquals(999L, cadence.waitMillis(1001, true))
    assertTrue(cadence.enableDemandGateAndLatchOrdinaryCapture(6000, 1001))
    assertTrue(cadence.notePictureEmitted(1002))
    assertEquals(-1L, cadence.waitMillis(2000, false))
    cadence.beginCapture(5000, true)
    assertEquals(1000L, cadence.waitMillis(5000, true))
    assertTrue(cadence.enableDemandGateAndLatchOrdinaryCapture(6000, 5999))
    assertEquals(0L, cadence.waitMillis(6000, false))
    assertEquals(-1L, cadence.waitMillis(6001, false))
  }
}
