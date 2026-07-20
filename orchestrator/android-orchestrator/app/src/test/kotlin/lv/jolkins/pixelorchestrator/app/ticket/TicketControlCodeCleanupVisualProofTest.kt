package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketControlCodeCleanupVisualProofTest {
  @Test
  fun requiresTwoConsecutiveRawTicketSamples() {
    val proof = TicketControlCodeCleanupVisualProof(requiredRawTicketSamples = 2)

    assertFalse(proof.observe(TicketControlCodeVisualClassifier.RAW_TICKET))
    assertTrue(proof.observe(TicketControlCodeVisualClassifier.RAW_TICKET))
  }

  @Test
  fun nonRawStatesResetAPartialProof() {
    listOf(
      TicketControlCodeVisualClassifier.UNKNOWN,
      TicketControlCodeVisualClassifier.GENERATED,
      TicketControlCodeVisualClassifier.CONTROL_POPUP
    ).forEach { interruptingState ->
      val proof = TicketControlCodeCleanupVisualProof(requiredRawTicketSamples = 2)

      assertFalse(proof.observe(TicketControlCodeVisualClassifier.RAW_TICKET))
      assertFalse(proof.observe(interruptingState))
      assertFalse(proof.observe(TicketControlCodeVisualClassifier.RAW_TICKET))
      assertTrue(proof.observe(TicketControlCodeVisualClassifier.RAW_TICKET))
    }
  }
}
