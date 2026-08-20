package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketMotionCadenceControllerTest {
  @Test
  fun thresholdsPromoteImmediatelyAndDemoteWithHysteresis() {
    val policy = TicketMotionCadenceController()
    val total = 64 * 64

    assertEquals(10, policy.update(200, total, -1, false, 10))
    assertEquals(10, policy.update(0, total, 0, false, 10))
    assertEquals(10, policy.update(0, total, 1_999, false, 10))
    assertEquals(5, policy.update(0, total, 2_000, false, 10))
    assertEquals(5, policy.update(0, total, 4_999, false, 10))
    assertEquals(1, policy.update(0, total, 5_000, false, 10))
  }

  @Test
  fun moderateMotionAlsoDemotesActiveCadenceAfterHighMotionHysteresis() {
    val policy = TicketMotionCadenceController()
    val total = 64 * 64
    assertEquals(10, policy.update(200, total, 0, false, 10))
    assertEquals(10, policy.update(20, total, 1, false, 10))
    assertEquals(10, policy.update(20, total, 1_999, false, 10))
    assertEquals(5, policy.update(20, total, 2_001, false, 10))
    assertEquals(5, policy.update(20, total, 4_999, false, 10))
  }

  @Test
  fun moderateMotionPromotesToFiveAndPriorityUsesCeiling() {
    val policy = TicketMotionCadenceController()
    val total = 64 * 64
    assertEquals(5, policy.update(20, total, 0, false, 10))
    assertEquals(5, policy.update(20, total, 1_000, false, 10))
    assertEquals(5, policy.update(20, total, 2_000, true, 5))
    assertEquals(1, policy.update(20, total, 3_000, false, 1))
  }
  
  @Test
  fun tinyNoiseIsStaticAndCeilingNeverExceedsRequestedDemand() {
    val policy = TicketMotionCadenceController()
    val total = 64 * 64
    assertEquals(1, policy.update(1, total, 0, false, 10))
    assertEquals(5, policy.update(200, total, 1, false, 5))
    assertEquals(5, policy.update(200, total, 2, true, 5))
  }
}
