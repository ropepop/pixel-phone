package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketControlCodeSubmitVisualProofTest {
  @Test
  fun requiresTwoDistinctConsecutiveStaticReadySamples() {
    val proof = TicketControlCodeSubmitVisualProof()

    assertFalse(proof.observe(10L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
    assertFalse(proof.observe(10L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
    assertTrue(proof.observe(11L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
  }

  @Test
  fun inconclusiveOrOrdinaryPopupSampleResetsReadiness() {
    val proof = TicketControlCodeSubmitVisualProof()

    assertFalse(proof.observe(20L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
    assertFalse(proof.observe(21L, TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY))
    assertFalse(proof.observe(22L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
    assertTrue(proof.observe(23L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
  }

  @Test
  fun staleProbeCannotCompleteProofAfterReset() {
    val proof = TicketControlCodeSubmitVisualProof()

    assertFalse(proof.observe(30L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
    assertFalse(proof.observe(31L, TicketControlCodeVisualClassifier.UNKNOWN))
    assertFalse(proof.observe(30L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
    assertFalse(proof.observe(32L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
    assertTrue(proof.observe(33L, TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY))
  }
}
