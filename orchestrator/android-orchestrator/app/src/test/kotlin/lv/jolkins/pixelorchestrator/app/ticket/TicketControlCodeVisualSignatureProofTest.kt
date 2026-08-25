package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketControlCodeVisualSignatureProofTest {
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
      resultMode = TicketControlCodeVisualResultMode.REDESIGNED_DETAIL_ALREADY_CLEAN,
      rawDetailAnchor = ""
    ))
    assertFalse(ticketControlCodeCleanupRequiresVisualReopen(
      cleanupRequired = true,
      resultMode = TicketControlCodeVisualResultMode.LEGACY_GENERATED_WITH_CLOSE,
      rawDetailAnchor = ""
    ))
    assertFalse(ticketControlCodeCleanupRequiresVisualReopen(
      cleanupRequired = false,
      resultMode = TicketControlCodeVisualResultMode.NONE,
      rawDetailAnchor = ""
    ))
  }

  @Test
  fun compactGeneratedWithoutACloseBadgeMayEnterOnlyTheSameDetailProof() {
    fun probe(result: String, close: TicketControlCodeVisualBounds? = null) =
      TicketControlCodeVisualProbe(
        probeId = 1,
        result = result,
        reason = "test",
        atMillis = 1,
        closeBounds = close
      )

    assertTrue(ticketControlCodeRedesignedDetailCandidate(
      probe(TicketControlCodeVisualClassifier.GENERATED)
    ))
    assertTrue(ticketControlCodeRedesignedDetailCandidate(
      probe(TicketControlCodeVisualClassifier.RAW_TICKET)
    ))
    assertTrue(ticketControlCodeRedesignedDetailCandidate(
      probe(TicketControlCodeVisualClassifier.UNKNOWN)
    ))
    assertFalse(ticketControlCodeRedesignedDetailCandidate(
      probe(
        TicketControlCodeVisualClassifier.GENERATED,
        TicketControlCodeVisualBounds(38, 30, 44, 35)
      )
    ))
    assertFalse(ticketControlCodeRedesignedDetailCandidate(
      probe(TicketControlCodeVisualClassifier.CONTROL_POPUP)
    ))
    assertFalse(ticketControlCodeRedesignedDetailCandidate(null))
  }

  @Test
  fun redesignedResultMayCleanUpOnlyOnTheExactSameStableDetail() {
    val anchor = "d_1111111111111111aaaaaaaaaaaa"
    val control = TicketVisualProbeBounds(4, 5, 12, 13)
    val mode = TicketControlCodeVisualResultMode.REDESIGNED_DETAIL_ALREADY_CLEAN
    fun observation(
      state: TicketVisualPhoneState = TicketVisualPhoneState.ACTIVATED_DETAIL,
      currentAnchor: String = anchor,
      controlBounds: TicketVisualProbeBounds? = control
    ) = TicketVisualActionObservation(
      probeId = 2,
      state = state,
      currentAnchor = currentAnchor,
      controlCodeBounds = controlBounds,
      atMillis = 2
    )

    assertTrue(ticketControlCodeReturnedToSameDetail(
      observation(), anchor, TicketVisualPhoneState.ACTIVATED_DETAIL, mode
    ))
    assertFalse(ticketControlCodeReturnedToSameDetail(
      observation(currentAnchor = "d_2222222222222222aaaaaaaaaaaa"),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      mode
    ))
    assertFalse(ticketControlCodeReturnedToSameDetail(
      observation(state = TicketVisualPhoneState.UNACTIVATED_DETAIL),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      mode
    ))
    assertFalse(ticketControlCodeReturnedToSameDetail(
      observation(controlBounds = null), anchor, TicketVisualPhoneState.ACTIVATED_DETAIL, mode
    ))
    assertFalse(ticketControlCodeReturnedToSameDetail(
      observation(), "", TicketVisualPhoneState.ACTIVATED_DETAIL, mode
    ))
    assertFalse(ticketControlCodeReturnedToSameDetail(
      observation(), anchor, TicketVisualPhoneState.UNKNOWN, mode
    ))
    assertFalse(ticketControlCodeReturnedToSameDetail(
      observation(),
      anchor,
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketControlCodeVisualResultMode.NONE
    ))
  }

  @Test
  fun exactCleanupSignatureBridgesOnlyRawAndUnknownDetailStates() {
    val signature = "222222222222222222222222"
    val epoch = "aaaaaaaaaaaa"
    fun probe(
      result: String,
      visualSignature: String = signature,
      visualEpoch: String = epoch,
      closeBounds: TicketControlCodeVisualBounds? = null
    ) = TicketControlCodeVisualProbe(
      probeId = 1,
      result = result,
      reason = "test",
      atMillis = 1,
      closeBounds = closeBounds,
      visualSignature = visualSignature,
      visualSignatureEpoch = visualEpoch
    )

    assertTrue(ticketControlCodeExpectedDetailSignatureMatches(
      probe(TicketControlCodeVisualClassifier.RAW_TICKET), signature, epoch
    ))
    assertTrue(ticketControlCodeExpectedDetailSignatureMatches(
      probe(TicketControlCodeVisualClassifier.UNKNOWN), signature, epoch
    ))
    assertFalse(ticketControlCodeExpectedDetailSignatureMatches(
      probe(
        TicketControlCodeVisualClassifier.GENERATED,
        closeBounds = TicketControlCodeVisualBounds(38, 30, 44, 35)
      ),
      signature,
      epoch
    ))
    assertFalse(ticketControlCodeExpectedDetailSignatureMatches(
      probe(TicketControlCodeVisualClassifier.CONTROL_POPUP), signature, epoch
    ))
    assertFalse(ticketControlCodeExpectedDetailSignatureMatches(
      probe(TicketControlCodeVisualClassifier.TICKET_LIST_WITH_REGISTRATION_BUTTON),
      signature,
      epoch
    ))
    assertFalse(ticketControlCodeExpectedDetailSignatureMatches(
      probe(TicketControlCodeVisualClassifier.UNKNOWN, ""), signature, epoch
    ))
    assertFalse(ticketControlCodeExpectedDetailSignatureMatches(
      probe(TicketControlCodeVisualClassifier.UNKNOWN, "333333333333333333333333"),
      signature,
      epoch
    ))
    assertFalse(ticketControlCodeExpectedDetailSignatureMatches(
      probe(TicketControlCodeVisualClassifier.UNKNOWN, visualEpoch = "bbbbbbbbbbbb"),
      signature,
      epoch
    ))
  }

  @Test
  fun requiresTwoFreshMatchingRawTicketSignatures() {
    val before = "111111111111111111111111"
    val after = "222222222222222222222222"
    val epoch = "aaaaaaaaaaaa"
    val proof = TicketControlCodeVisualSignatureProof(
      rejectedSignature = before,
      expectedEpoch = epoch
    )

    assertNull(proof.observe(1L, TicketControlCodeVisualClassifier.RAW_TICKET, after, epoch))
    assertNull(proof.observe(1L, TicketControlCodeVisualClassifier.RAW_TICKET, after, epoch))
    assertEquals(
      TicketControlCodeVisualSignatureAnchor(after, epoch),
      proof.observe(2L, TicketControlCodeVisualClassifier.RAW_TICKET, after, epoch)
    )
  }

  @Test
  fun baselineUnknownConflictingAndRestartedSamplesFailClosed() {
    val before = "111111111111111111111111"
    val afterA = "222222222222222222222222"
    val afterB = "333333333333333333333333"
    val epoch = "aaaaaaaaaaaa"
    val proof = TicketControlCodeVisualSignatureProof(
      rejectedSignature = before,
      expectedEpoch = epoch
    )

    assertNull(proof.observe(1L, TicketControlCodeVisualClassifier.RAW_TICKET, before, epoch))
    assertNull(proof.observe(2L, TicketControlCodeVisualClassifier.UNKNOWN, afterA, epoch))
    assertNull(proof.observe(3L, TicketControlCodeVisualClassifier.RAW_TICKET, afterA, "bbbbbbbbbbbb"))
    assertNull(proof.observe(4L, TicketControlCodeVisualClassifier.RAW_TICKET, afterA, epoch))
    assertNull(proof.observe(5L, TicketControlCodeVisualClassifier.RAW_TICKET, afterB, epoch))
    assertNull(proof.observe(6L, TicketControlCodeVisualClassifier.RAW_TICKET, afterA, epoch))
    assertEquals(
      TicketControlCodeVisualSignatureAnchor(afterA, epoch),
      proof.observe(7L, TicketControlCodeVisualClassifier.RAW_TICKET, afterA, epoch)
    )
  }

  @Test
  fun exactBaselineModeRejectsEveryThirdSignature() {
    val baseline = "111111111111111111111111"
    val unrelated = "333333333333333333333333"
    val epoch = "aaaaaaaaaaaa"
    val proof = TicketControlCodeVisualSignatureProof(
      expectedSignature = baseline,
      expectedEpoch = epoch
    )

    assertNull(proof.observe(1L, TicketControlCodeVisualClassifier.RAW_TICKET, unrelated, epoch))
    assertNull(proof.observe(2L, TicketControlCodeVisualClassifier.RAW_TICKET, unrelated, epoch))
    assertNull(proof.observe(3L, TicketControlCodeVisualClassifier.RAW_TICKET, baseline, epoch))
    assertEquals(
      TicketControlCodeVisualSignatureAnchor(baseline, epoch),
      proof.observe(4L, TicketControlCodeVisualClassifier.RAW_TICKET, baseline, epoch)
    )
  }
}
