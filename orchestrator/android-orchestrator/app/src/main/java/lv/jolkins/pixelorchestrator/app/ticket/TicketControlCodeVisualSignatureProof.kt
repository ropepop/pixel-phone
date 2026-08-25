package lv.jolkins.pixelorchestrator.app.ticket

internal data class TicketControlCodeVisualSignatureAnchor(
  val signature: String,
  val epoch: String
)

internal enum class TicketControlCodeVisualResultMode {
  NONE,
  REDESIGNED_DETAIL_ALREADY_CLEAN,
  LEGACY_GENERATED_WITH_CLOSE
}

internal fun ticketControlCodeDetailAnchorIsValid(anchor: String): Boolean =
  anchor.matches(Regex("d_[0-9a-f]{28}"))

/** Compact activated-detail compatibility may resemble GENERATED without a result badge. */
internal fun ticketControlCodeRedesignedDetailCandidate(
  probe: TicketControlCodeVisualProbe?
): Boolean = probe != null &&
  probe.closeBounds == null &&
  probe.result in setOf(
    TicketControlCodeVisualClassifier.RAW_TICKET,
    TicketControlCodeVisualClassifier.UNKNOWN,
    TicketControlCodeVisualClassifier.GENERATED
  )

/**
 * The durable pending bit survives a process restart, while the private visual result identity
 * intentionally does not. Without that volatile identity, generic cleanup must not inspect or
 * mutate the phone; only the explicit list-to-detail visual reopen may clear the checkpoint.
 */
internal fun ticketControlCodeCleanupRequiresVisualReopen(
  cleanupRequired: Boolean,
  resultMode: TicketControlCodeVisualResultMode,
  rawDetailAnchor: String
): Boolean = cleanupRequired &&
  resultMode == TicketControlCodeVisualResultMode.NONE &&
  !ticketControlCodeDetailAnchorIsValid(rawDetailAnchor)

/**
 * The redesigned result and baseline are both ticket-detail surfaces, but the older cleanup
 * classifier can label either one unknown. Exact same-epoch signature equality may bridge only
 * that narrow RAW/UNKNOWN layout gap. A legacy GENERATED strip must keep its detected-X path,
 * while popup, list, and other contradictory states remain ineligible.
 */
internal fun ticketControlCodeExpectedDetailSignatureMatches(
  probe: TicketControlCodeVisualProbe?,
  expectedSignature: String,
  expectedEpoch: String
): Boolean = probe != null &&
  probe.result in setOf(
    TicketControlCodeVisualClassifier.RAW_TICKET,
    TicketControlCodeVisualClassifier.UNKNOWN
  ) &&
  expectedSignature.matches(Regex("[0-9a-f]{24}")) &&
  expectedEpoch.matches(Regex("[0-9a-f]{12}")) &&
  probe.visualSignature == expectedSignature &&
  probe.visualSignatureEpoch == expectedEpoch

/**
 * The current ViVi design closes its code-entry sheet back onto the same ticket detail instead
 * of opening a separate generated-result surface. The large QR/Aztec region keeps rotating, so
 * full-frame signature equality cannot authorize or reject cleanup. Two fresh action probes have
 * already agreed before this predicate is called; bind the no-input cleanup only to the exact
 * phone-local static detail identity and state observed before the sheet was opened.
 */
internal fun ticketControlCodeReturnedToSameDetail(
  observation: TicketVisualActionObservation?,
  expectedAnchor: String,
  expectedState: TicketVisualPhoneState,
  resultMode: TicketControlCodeVisualResultMode
): Boolean = observation != null &&
  resultMode == TicketControlCodeVisualResultMode.REDESIGNED_DETAIL_ALREADY_CLEAN &&
  ticketControlCodeDetailAnchorIsValid(expectedAnchor) &&
  expectedState == TicketVisualPhoneState.ACTIVATED_DETAIL &&
  observation.state == expectedState &&
  observation.currentAnchor == expectedAnchor &&
  observation.controlCodeBounds != null

/** Requires independent, consecutive visual probes to agree on one same-epoch signature. */
internal class TicketControlCodeVisualSignatureProof(
  private val rejectedSignature: String = "",
  private val expectedSignature: String = "",
  private val expectedEpoch: String = "",
  private val requiredSamples: Int = 2
) {
  init {
    require(requiredSamples > 0)
  }

  private var lastProbeId: Long = 0L
  private var candidateSignature: String = ""
  private var candidateEpoch: String = ""
  private var consecutiveSamples: Int = 0

  fun observe(
    probeId: Long,
    result: String,
    visualSignature: String,
    visualSignatureEpoch: String
  ): TicketControlCodeVisualSignatureAnchor? {
    if (probeId <= lastProbeId) return null
    lastProbeId = probeId
    val acceptable = result == TicketControlCodeVisualClassifier.RAW_TICKET &&
      visualSignature.matches(Regex("[0-9a-f]{24}")) &&
      visualSignatureEpoch.matches(Regex("[0-9a-f]{12}")) &&
      visualSignature != rejectedSignature &&
      (expectedSignature.isBlank() || visualSignature == expectedSignature) &&
      (expectedEpoch.isBlank() || visualSignatureEpoch == expectedEpoch)
    if (!acceptable) {
      candidateSignature = ""
      candidateEpoch = ""
      consecutiveSamples = 0
      return null
    }
    if (candidateSignature == visualSignature && candidateEpoch == visualSignatureEpoch) {
      consecutiveSamples += 1
    } else {
      candidateSignature = visualSignature
      candidateEpoch = visualSignatureEpoch
      consecutiveSamples = 1
    }
    return if (consecutiveSamples >= requiredSamples) {
      TicketControlCodeVisualSignatureAnchor(candidateSignature, candidateEpoch)
    } else {
      null
    }
  }
}
