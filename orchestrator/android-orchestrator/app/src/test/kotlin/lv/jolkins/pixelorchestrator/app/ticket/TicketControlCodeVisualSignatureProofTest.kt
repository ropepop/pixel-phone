package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketControlCodeVisualSignatureProofTest {
  @Test
  fun generatedProofRequiresTwoFreshMatchingGeneratedBadgeFrames() {
    val bounds = TicketControlCodeVisualBounds(38, 30, 44, 35)
    fun probe(
      id: Long,
      result: String = TicketControlCodeVisualClassifier.GENERATED,
      closeBounds: TicketControlCodeVisualBounds? = bounds,
      signature: String = "222222222222222222222222",
      epoch: String = "aaaaaaaaaaaa"
    ) = TicketControlCodeVisualProbe(
      probeId = id,
      result = result,
      reason = "test",
      atMillis = id,
      closeBounds = closeBounds,
      visualSignature = signature,
      visualSignatureEpoch = epoch
    )

    val proof = TicketGeneratedWithCloseProof()
    assertFalse(proof.observe(probe(1)))
    assertFalse(proof.observe(probe(1)))
    assertFalse(proof.observe(probe(2, TicketControlCodeVisualClassifier.RAW_TICKET)))
    assertFalse(proof.observe(probe(3)))
    assertFalse(proof.observe(probe(4, closeBounds = TicketControlCodeVisualBounds(37, 30, 43, 35))))
    assertTrue(proof.observe(probe(5, closeBounds = TicketControlCodeVisualBounds(37, 30, 43, 35))))
    assertFalse(proof.observe(probe(6, signature = "333333333333333333333333")))
    assertFalse(proof.observe(probe(7, epoch = "bbbbbbbbbbbb")))
    assertFalse(proof.observe(probe(8, signature = "")))
  }

  @Test
  fun generatedProofKeepsOneValidSampleAcrossAProbeTransportTimeout() {
    val bounds = TicketControlCodeVisualBounds(38, 30, 44, 35)
    fun probe(id: Long) = TicketControlCodeVisualProbe(
      probeId = id,
      result = TicketControlCodeVisualClassifier.GENERATED,
      reason = "test",
      atMillis = id,
      closeBounds = bounds,
      visualSignature = "222222222222222222222222",
      visualSignatureEpoch = "aaaaaaaaaaaa"
    )

    val proof = TicketGeneratedWithCloseProof()
    assertFalse(proof.observe(probe(1)))
    assertFalse(proof.observe(null))
    assertTrue(proof.observe(probe(3)))
  }

  @Test
  fun generatedProofRejectsOrdinaryDetailAndGeneratedLabelWithoutBadge() {
    fun probe(id: Long, result: String, close: TicketControlCodeVisualBounds? = null) =
      TicketControlCodeVisualProbe(
        id,
        result,
        "test",
        id,
        closeBounds = close,
        visualSignature = "222222222222222222222222",
        visualSignatureEpoch = "aaaaaaaaaaaa"
      )
    val proof = TicketGeneratedWithCloseProof()
    assertFalse(proof.observe(probe(1, TicketControlCodeVisualClassifier.RAW_TICKET)))
    assertFalse(proof.observe(probe(2, TicketControlCodeVisualClassifier.GENERATED)))
    assertFalse(proof.observe(probe(3, TicketControlCodeVisualClassifier.GENERATED)))
    assertFalse(proof.observe(probe(4, TicketControlCodeVisualClassifier.UNKNOWN)))
  }

  @Test
  fun cleanupMustReturnToTheExactOriginalActivatedOrUnactivatedDetail() {
    val anchor = "d_1111111111111111aaaaaaaaaaaa"
    val control = TicketVisualProbeBounds(4, 5, 12, 13)
    fun observation(
      state: TicketVisualPhoneState,
      currentAnchor: String = anchor,
      controlBounds: TicketVisualProbeBounds? = control,
      sliderBounds: TicketVisualProbeBounds? = if (state == TicketVisualPhoneState.UNACTIVATED_DETAIL) {
        TicketVisualProbeBounds(3, 15, 16, 18)
      } else {
        null
      }
    ) = TicketVisualActionObservation(
      probeId = 2,
      state = state,
      currentAnchor = currentAnchor,
      sliderBounds = sliderBounds,
      controlCodeBounds = controlBounds,
      atMillis = 2
    )

    assertTrue(ticketControlCodeRestoredOriginalDetail(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertTrue(ticketControlCodeRestoredOriginalDetail(
      observation(TicketVisualPhoneState.UNACTIVATED_DETAIL),
      anchor,
      TicketVisualPhoneState.UNACTIVATED_DETAIL
    ))
    assertFalse(ticketControlCodeRestoredOriginalDetail(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL),
      anchor,
      TicketVisualPhoneState.UNACTIVATED_DETAIL
    ))
    assertTrue(ticketControlCodeRestoredOriginalDetail(
      observation(
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        currentAnchor = "d_2222222222222222aaaaaaaaaaaa"
      ),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertFalse(ticketControlCodeRestoredOriginalDetail(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL, controlBounds = null),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertEquals("observation_missing", ticketControlCodeRestoredOriginalDetailMismatch(
      null, anchor, TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertEquals("expected_anchor_invalid", ticketControlCodeRestoredOriginalDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL), "", TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertEquals("state_mismatch", ticketControlCodeRestoredOriginalDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL), anchor, TicketVisualPhoneState.UNACTIVATED_DETAIL
    ))
    assertNull(ticketControlCodeRestoredOriginalDetailMismatch(
      observation(
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        currentAnchor = "d_2222222222222222aaaaaaaaaaaa"
      ),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertEquals("static_anchor_invalid", ticketControlCodeRestoredOriginalDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL, currentAnchor = ""),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertEquals("vivi_control_missing", ticketControlCodeRestoredOriginalDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL, controlBounds = null),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertEquals("slider_missing", ticketControlCodeRestoredOriginalDetailMismatch(
      observation(TicketVisualPhoneState.UNACTIVATED_DETAIL, sliderBounds = null),
      anchor,
      TicketVisualPhoneState.UNACTIVATED_DETAIL
    ))
    assertEquals("slider_unexpected", ticketControlCodeRestoredOriginalDetailMismatch(
      observation(
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        sliderBounds = TicketVisualProbeBounds(3, 15, 16, 18)
      ),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
    assertNull(ticketControlCodeRestoredOriginalDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL
    ))
  }

  @Test
  fun staleCheckpointRecoveryRequiresACompletePixelOnlyRawDetail() {
    val anchor = "d_1111111111111111aaaaaaaaaaaa"
    val control = TicketVisualProbeBounds(4, 5, 12, 13)
    val slider = TicketVisualProbeBounds(3, 15, 16, 18)
    fun observation(
      state: TicketVisualPhoneState,
      currentAnchor: String = anchor,
      controlBounds: TicketVisualProbeBounds? = control,
      sliderBounds: TicketVisualProbeBounds? = when (state) {
        TicketVisualPhoneState.UNACTIVATED_DETAIL -> slider
        else -> null
      }
    ) = TicketVisualActionObservation(
      probeId = 9L,
      state = state,
      currentAnchor = currentAnchor,
      sliderBounds = sliderBounds,
      controlCodeBounds = controlBounds,
      atMillis = 9L
    )

    assertNull(ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL)
    ))
    assertNull(ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.UNACTIVATED_DETAIL)
    ))
    assertEquals("observation_missing", ticketControlCodeFreshRawDetailMismatch(null))
    assertEquals("detail_state_unproved", ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.TICKET_LIST)
    ))
    assertEquals("detail_state_unproved", ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.UNKNOWN)
    ))
    assertEquals("static_anchor_invalid", ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL, currentAnchor = "")
    ))
    assertEquals("vivi_control_missing", ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL, controlBounds = null)
    ))
    assertEquals("slider_missing", ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.UNACTIVATED_DETAIL, sliderBounds = null)
    ))
    assertEquals("slider_unexpected", ticketControlCodeFreshRawDetailMismatch(
      observation(TicketVisualPhoneState.ACTIVATED_DETAIL, sliderBounds = slider)
    ))
  }

  @Test
  fun restoredCleanupCheckpointCannotUseGenericCleanupWithoutVolatileIdentity() {
    assertTrue(ticketControlCodeCleanupRequiresVisualReopen(
      cleanupRequired = true,
      resultMode = TicketControlCodeVisualResultMode.NONE,
      rawDetailAnchor = ""
    ))
    assertTrue(ticketControlCodeCleanupRequiresVisualReopen(
      cleanupRequired = true,
      resultMode = TicketControlCodeVisualResultMode.NONE,
      rawDetailAnchor = "d_invalid"
    ))
    assertFalse(ticketControlCodeCleanupRequiresVisualReopen(
      cleanupRequired = true,
      resultMode = TicketControlCodeVisualResultMode.NONE,
      rawDetailAnchor = "d_1111111111111111aaaaaaaaaaaa"
    ))
    assertFalse(ticketControlCodeCleanupRequiresVisualReopen(
      cleanupRequired = true,
      resultMode = TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE,
      rawDetailAnchor = ""
    ))
    assertFalse(ticketControlCodeCleanupRequiresVisualReopen(
      cleanupRequired = false,
      resultMode = TicketControlCodeVisualResultMode.NONE,
      rawDetailAnchor = ""
    ))
  }

}
