package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.*
import org.junit.Test

class TicketControlCodeSubmitVisualProofTest {
  private fun sample(id: Long, value: Boolean = true, left: Int = 12) = TicketControlCodeVisualProbe(
    probeId = id,
    result = if (value) TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY
      else TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
    reason = "test", atMillis = id * 100,
    inputBounds = TicketControlCodeVisualBounds(left, 30, 80, 45),
    submitBounds = TicketControlCodeVisualBounds(90, 30, 110, 45)
  )

  @Test fun valueAndBothTargetsMustAgreeInTwoSuccessivePictures() {
    val proof = TicketControlCodeSubmitVisualProof()
    assertFalse(proof.observe(sample(1), true))
    assertFalse(proof.observe(sample(2, left = 15), true))
    assertTrue(proof.observe(sample(3, left = 15), true))
    assertFalse(proof.observe(sample(4).copy(submitBounds = null), true))
    assertFalse(proof.observe(sample(5), true))
    assertTrue(proof.observe(sample(6), true))
  }

  @Test fun missingAndRepeatedObservationsBreakThePair() {
    val proof = TicketControlCodeSubmitVisualProof()
    assertFalse(proof.observe(sample(1), true))
    assertFalse(proof.observe(null, true))
    assertFalse(proof.observe(sample(2), true))
    assertFalse(proof.observe(sample(2), true))
    assertFalse(proof.observe(sample(3), true))
    assertTrue(proof.observe(sample(4), true))
    assertFalse(proof.observe(sample(3), true))
    assertFalse(proof.observe(sample(5), true))
  }

  @Test fun aChangedValueCannotInheritBlankRetryAuthority() {
    val proof = TicketControlCodeSubmitVisualProof()
    assertFalse(proof.observe(sample(1, value = false), true))
    assertFalse(proof.observe(sample(2), true))
    assertFalse(proof.observe(sample(3, value = false), true))
    assertTrue(proof.observe(sample(4, value = false), true))
    assertFalse(proof.observe(sample(5).copy(result = TicketControlCodeVisualClassifier.UNKNOWN), true))
    assertFalse(proof.observe(sample(6, value = false), true))
  }

  @Test fun cleanupMayRecognizeAPopupWithoutAuthorizingAnInputTarget() {
    val proof = TicketControlCodeSubmitVisualProof()
    assertFalse(proof.observe(sample(1).copy(inputBounds = null, submitBounds = null), false))
    assertTrue(proof.observe(sample(2).copy(inputBounds = null, submitBounds = null), false))
    assertFalse(proof.observe(sample(3).copy(inputBounds = null, submitBounds = null), true))
  }
}
