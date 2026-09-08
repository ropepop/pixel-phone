package lv.jolkins.pixelorchestrator.app.ticket

internal enum class TicketControlCodeVisualResultMode {
  NONE,
  GENERATED_WITH_CLOSE
}

internal fun ticketControlCodeDetailAnchorIsValid(anchor: String): Boolean =
  anchor.matches(Regex("d_[0-9a-f]{28}"))

/**
 * Proves that a fresh current-view observation is an ordinary ViVi Aztec detail, rather than a
 * generated control-code result, popup, list, or transition frame. This deliberately consumes
 * only the rooted pixel classifier's typed state and geometry.
 */
internal fun ticketControlCodeFreshRawDetailMismatch(
  observation: TicketVisualActionObservation?
): String? {
  if (observation == null) return "observation_missing"
  if (observation.state !in setOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL
    )
  ) return "detail_state_unproved"
  if (!ticketControlCodeDetailAnchorIsValid(observation.currentAnchor)) {
    return "static_anchor_invalid"
  }
  if (observation.controlCodeBounds == null) return "vivi_control_missing"
  if (observation.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
    observation.sliderBounds == null
  ) return "slider_missing"
  if (observation.state == TicketVisualPhoneState.ACTIVATED_DETAIL &&
    observation.sliderBounds != null
  ) return "slider_unexpected"
  return null
}

internal fun ticketControlCodeRestoredOriginalDetailMismatch(
  observation: TicketVisualActionObservation?,
  expectedAnchor: String,
  expectedState: TicketVisualPhoneState
): String? {
  if (observation == null) return "observation_missing"
  if (!ticketControlCodeDetailAnchorIsValid(expectedAnchor)) return "expected_anchor_invalid"
  if (expectedState !in setOf(
    TicketVisualPhoneState.ACTIVATED_DETAIL,
    TicketVisualPhoneState.UNACTIVATED_DETAIL
  )) return "expected_state_invalid"
  if (observation.state != expectedState) return "state_mismatch"
  // The proven strip X only closes an inline result; it cannot navigate to another ticket.
  // The ordinary Aztec rotates independently, so cleanup uses continuity of that one tap plus
  // a valid, two-frame-stable static anchor rather than equality with the pre-submit hash.
  return ticketControlCodeFreshRawDetailMismatch(observation)
}


/**
 * Two distinct generated-specific frames must agree on both the close-badge geometry and the
 * phone-local identity of the non-refreshing Aztec surface. The dark strip alone is not enough:
 * an activated-status row can occupy similar geometry, while a changing ordinary ticket code is
 * not the requested generated result.
 */
internal class TicketGeneratedWithCloseProof(
  private val requiredSamples: Int = 2
) {
  init {
    require(requiredSamples > 0)
  }

  private var lastProbeId = 0L
  private var candidateBounds: TicketControlCodeVisualBounds? = null
  private var candidateSignature = ""
  private var candidateEpoch = ""
  private var consecutiveSamples = 0

  fun observe(probe: TicketControlCodeVisualProbe?): Boolean {
    if (probe == null || probe.probeId <= lastProbeId) return false
    lastProbeId = probe.probeId
    val bounds = probe.closeBounds
    val signature = probe.visualSignature
    val epoch = probe.visualSignatureEpoch
    if (probe.result != TicketControlCodeVisualClassifier.GENERATED || bounds == null ||
      !signature.matches(Regex("[0-9a-f]{24}")) ||
      !epoch.matches(Regex("[0-9a-f]{12}"))
    ) {
      candidateBounds = null
      candidateSignature = ""
      candidateEpoch = ""
      consecutiveSamples = 0
      return false
    }
    if (candidateBounds == bounds && candidateSignature == signature && candidateEpoch == epoch) {
      consecutiveSamples += 1
    } else {
      candidateBounds = bounds
      candidateSignature = signature
      candidateEpoch = epoch
      consecutiveSamples = 1
    }
    return consecutiveSamples >= requiredSamples
  }
}
