package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

internal enum class TicketVisualActionTarget(val wireName: String, val activatesTicket: Boolean = false) {
  OPEN_LATEST_UNACTIVATED("open_latest_unactivated"),
  OPEN_LATEST_AND_REGISTER("open_latest_and_register", activatesTicket = true),
  REGISTER_CURRENT("register_current", activatesTicket = true),
  SHOW_RECENT_ACTIVATED("show_recent_activated"),
  RETURN_TO_LATEST_UNACTIVATED("return_to_latest_unactivated"),
  REDETECT_LATEST("redetect_latest"),
  PROVE_CURRENT("prove_current");

  companion object {
    fun fromWireName(value: String): TicketVisualActionTarget? = entries.firstOrNull {
      it.wireName == value.trim().lowercase()
    }
  }
}

internal enum class TicketVisualActionView(val wireName: String) {
  LATEST_UNACTIVATED("latest_unactivated"),
  RECENT_ACTIVATED("recent_activated"),
  ACTIVATED_CURRENT("activated_current"),
  UNKNOWN("unknown")
}

internal data class TicketVisualActionRequest(
  val actionId: String,
  val target: TicketVisualActionTarget,
  val source: String,
  val reason: String,
  val attemptId: String,
  val expectedInteractionRevision: String,
  val scheduleId: String,
  /** Opaque Spacetime policy revision that admitted a context-switch command. */
  val policyRevision: String,
  /** Spacetime-owned business deadline. Pixel echoes it but never derives a local window. */
  val switchExpiresAt: String
) {
  val hasSpacetimeSwitchAuthority: Boolean
    get() = policyRevision.isNotBlank() && switchExpiresAt.isNotBlank()
}

internal data class TicketVisualActionSnapshot(
  val actionId: String = "",
  val target: String = "",
  val status: String = "idle",
  val phase: String = "idle",
  val currentView: TicketVisualActionView = TicketVisualActionView.UNKNOWN,
  val switchAvailable: Boolean = false,
  val switchExpiresAt: String = "",
  val streamEpoch: Long = 0L,
  val frameSequence: Long = 0L,
  val reason: String = "",
  val completedAt: String = "",
  val interactionRevision: String = "",
  val activationRevision: String = "",
  val activationAttemptId: String = "",
  val sliderRegion: TicketSliderRegionV3? = null,
  val terminal: Boolean = false,
  val ok: Boolean = false
)

internal data class TicketSliderRegionV3(
  val proofActionId: String,
  val streamEpoch: Long,
  val frameSequence: Long,
  val leftBasisPoints: Int,
  val topBasisPoints: Int,
  val rightBasisPoints: Int,
  val bottomBasisPoints: Int
)

internal data class TicketVisualSwitchAnchors(
  val recentActivatedAnchor: String = "",
  val latestUnactivatedAnchor: String = ""
) {
  /** Visual readiness only. Spacetime remains the sole switch policy and expiry authority. */
  val visuallyReady: Boolean
    get() = recentActivatedAnchor.isNotBlank() && latestUnactivatedAnchor.isNotBlank()
}

internal data class TicketVisualActionJournalState(
  val actionId: String = "",
  val target: String = "",
  val phase: String = "",
  val intendedAnchor: String = "",
  /** The typed transition surrounding the last physical navigation tap. */
  val navigationFromState: String = "",
  val navigationToState: String = "",
  /** Required only for list-to-detail taps; detail-to-list deliberately leaves this blank. */
  val navigationAnchor: String = "",
  /** Exact encoded-frame watermark attached to a successful terminal visual proof. */
  val streamEpoch: Long = 0L,
  val frameSequence: Long = 0L,
  val terminalStatus: String = "",
  val terminalReason: String = "",
  val terminalView: String = "",
  val interactionRevision: String = "",
  val activationRevision: String = "",
  val activationAttemptId: String = "",
  val completedAt: String = "",
  val terminalOk: Boolean = false,
  /** Privacy-safe encoded-frame geometry retained until both terminal reducers are acknowledged. */
  val sliderLeftBasisPoints: Int = -1,
  val sliderTopBasisPoints: Int = -1,
  val sliderRightBasisPoints: Int = -1,
  val sliderBottomBasisPoints: Int = -1
) {
  val navigationDispatchUncertain: Boolean
    get() = actionId.isNotBlank() && phase == "navigation_dispatched"

  val hasRetainedTerminal: Boolean
    get() = actionId.isNotBlank() && phase == "terminal" && terminalStatus.isNotBlank()
}

internal fun ticketVisualActionJournalWriteProved(
  value: TicketVisualActionJournalState,
  commit: () -> Boolean,
  readBack: () -> TicketVisualActionJournalState
): Boolean {
  if (!commit()) return false
  return readBack() == value
}

/**
 * A retained success is acknowledgement-only replay of an already proved terminal view. Keep
 * passive prove_current compatible with every sanitized view, but never let an older navigation
 * journal reintroduce list-only success after the target's terminal contract becomes stricter.
 */
internal fun ticketVisualTerminalViewCompatible(
  target: TicketVisualActionTarget,
  view: TicketVisualActionView
): Boolean = when (target) {
  TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
  TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED,
  TicketVisualActionTarget.REDETECT_LATEST -> view == TicketVisualActionView.LATEST_UNACTIVATED
  TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
  TicketVisualActionTarget.REGISTER_CURRENT -> view == TicketVisualActionView.ACTIVATED_CURRENT
  TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> view == TicketVisualActionView.RECENT_ACTIVATED
  TicketVisualActionTarget.PROVE_CURRENT -> true
}

internal fun retainedTicketVisualTerminalSnapshot(
  journal: TicketVisualActionJournalState,
  request: TicketVisualActionRequest,
  streamEpoch: Long,
  frameSequence: Long
): TicketVisualActionSnapshot? {
  if (!journal.hasRetainedTerminal || journal.actionId != request.actionId ||
    journal.target != request.target.wireName
  ) return null
  val successfulWatermarkValid = !journal.terminalOk ||
    journal.streamEpoch > 0L && journal.frameSequence > 0L
  val retainedView = TicketVisualActionView.entries.firstOrNull {
    it.wireName == journal.terminalView
  } ?: TicketVisualActionView.UNKNOWN
  // Failure journals are already terminal safety decisions and must be replayed unchanged. Only a
  // retained success needs the target/view compatibility gate.
  val successfulViewValid = !journal.terminalOk ||
    ticketVisualTerminalViewCompatible(request.target, retainedView)
  val retainedTerminalValid = successfulWatermarkValid && successfulViewValid
  return TicketVisualActionSnapshot(
    actionId = journal.actionId,
    target = journal.target,
    status = if (retainedTerminalValid) journal.terminalStatus else "needs_attention",
    phase = if (journal.terminalOk && retainedTerminalValid) {
      "complete"
    } else if (retainedTerminalValid) {
      journal.terminalStatus
    } else {
      "needs_attention"
    },
    currentView = retainedView,
    streamEpoch = journal.streamEpoch.takeIf { it > 0L } ?: streamEpoch,
    frameSequence = journal.frameSequence.takeIf { it > 0L } ?: frameSequence,
    switchAvailable = retainedTerminalValid && request.hasSpacetimeSwitchAuthority,
    switchExpiresAt = if (retainedTerminalValid && request.hasSpacetimeSwitchAuthority) {
      request.switchExpiresAt
    } else {
      ""
    },
    reason = when {
      !successfulWatermarkValid -> "ticket_action_frame_watermark_unproved"
      !successfulViewValid -> "ticket_action_terminal_view_unproved"
      else -> journal.terminalReason
    },
    completedAt = journal.completedAt,
    interactionRevision = journal.interactionRevision,
    activationRevision = journal.activationRevision,
    activationAttemptId = journal.activationAttemptId,
    sliderRegion = journal.retainedSliderRegionOrNull().takeIf { retainedTerminalValid },
    terminal = true,
    ok = journal.terminalOk && retainedTerminalValid
  )
}

private fun TicketVisualActionJournalState.retainedSliderRegionOrNull(): TicketSliderRegionV3? {
  if (!terminalOk || terminalView != TicketVisualActionView.LATEST_UNACTIVATED.wireName ||
    target !in setOf(
      TicketVisualActionTarget.PROVE_CURRENT.wireName,
      TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED.wireName,
      TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED.wireName,
      TicketVisualActionTarget.REDETECT_LATEST.wireName
    ) || streamEpoch <= 0L || frameSequence <= 0L ||
    sliderLeftBasisPoints !in 0..10_000 || sliderTopBasisPoints !in 0..10_000 ||
    sliderRightBasisPoints !in 0..10_000 || sliderBottomBasisPoints !in 0..10_000 ||
    sliderLeftBasisPoints >= sliderRightBasisPoints ||
    sliderTopBasisPoints >= sliderBottomBasisPoints
  ) return null
  return TicketSliderRegionV3(
    proofActionId = actionId,
    streamEpoch = streamEpoch,
    frameSequence = frameSequence,
    leftBasisPoints = sliderLeftBasisPoints,
    topBasisPoints = sliderTopBasisPoints,
    rightBasisPoints = sliderRightBasisPoints,
    bottomBasisPoints = sliderBottomBasisPoints
  )
}

internal fun ticketVisualJournalReconciled(
  journal: TicketVisualActionJournalState,
  request: TicketVisualActionRequest,
  observation: TicketVisualActionObservation
): Boolean {
  if (!journal.navigationDispatchUncertain || journal.actionId != request.actionId ||
    journal.target != request.target.wireName
  ) return false
  val recordedToState = TicketVisualPhoneState.fromWireName(journal.navigationToState)
  if (recordedToState != TicketVisualPhoneState.UNKNOWN) {
    if (recordedToState == TicketVisualPhoneState.TICKET_LIST &&
      journal.navigationFromState == TicketVisualPhoneState.VIVI_HOME.wireName &&
      observation.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY
    ) return true
    if (observation.state != recordedToState) return false
    return when (recordedToState) {
      TicketVisualPhoneState.TICKET_LIST -> true
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      TicketVisualPhoneState.ACTIVATED_DETAIL ->
        journal.navigationAnchor.isNotBlank() &&
          observation.currentAnchor.isNotBlank()
      else -> false
    }
  }

  // Backward compatibility for the pre-transition journal. That journal could only describe
  // list-to-detail taps and always required the intended card identity.
  if (journal.intendedAnchor.isBlank()) return false
  val expectedState = when (request.target) {
    TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> TicketVisualPhoneState.ACTIVATED_DETAIL
    TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
    TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
    TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED -> TicketVisualPhoneState.UNACTIVATED_DETAIL
    else -> return false
  }
  return observation.state == expectedState && observation.currentAnchor == journal.intendedAnchor
}

internal class TicketVisualCaptureRecoveryBudget {
  var consumed: Boolean = false
    private set

  fun consumeIfCaptureWasInterrupted(
    baselineStreamEpoch: Long,
    currentStreamEpoch: Long,
    baselineRestartCount: Long,
    currentRestartCount: Long,
    completedProbeSeen: Boolean
  ): Boolean {
    if (consumed) return false
    val interrupted = !completedProbeSeen ||
      currentRestartCount > baselineRestartCount ||
      baselineStreamEpoch > 0L && currentStreamEpoch > 0L &&
      currentStreamEpoch != baselineStreamEpoch
    if (!interrupted) return false
    consumed = true
    return true
  }
}

internal fun parseTicketVisualActionRequest(payload: JsonObject): TicketVisualActionRequest? {
  val version = payload["version"]?.jsonPrimitive?.intOrNull ?: return null
  if (version != 3) return null
  val actionId = payload.string("actionId").trim()
  val target = TicketVisualActionTarget.fromWireName(payload.string("target")) ?: return null
  val attemptId = payload.string("attemptId").trim()
  val expectedRevision = payload.string("expectedInteractionRevision").trim()
  val policyRevision = payload.string("policyRevision").trim()
  val switchExpiresAt = payload.string("switchExpiresAt").trim()
  if (actionId.isBlank() || actionId.length > 128) return null
  if (target.activatesTicket && attemptId != actionId) return null
  if (target == TicketVisualActionTarget.REGISTER_CURRENT && expectedRevision.isBlank()) return null
  val switchesView = target in setOf(
    TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
    TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED
  )
  if (switchesView && (policyRevision.isBlank() || switchExpiresAt.isBlank())) return null
  if (policyRevision.length > 128 || switchExpiresAt.length > 64) return null
  if (switchExpiresAt.isNotBlank() && runCatching { Instant.parse(switchExpiresAt) }.isFailure) return null
  return TicketVisualActionRequest(
    actionId = actionId,
    target = target,
    source = payload.string("source").take(64),
    reason = payload.string("reason").take(160),
    attemptId = attemptId,
    expectedInteractionRevision = expectedRevision.take(128),
    scheduleId = payload.string("scheduleId").take(128),
    policyRevision = policyRevision,
    switchExpiresAt = switchExpiresAt
  )
}

private fun JsonObject.string(key: String): String =
  this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal data class TicketVisualProbeBounds(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int
) {
  val width: Int get() = (right - left).coerceAtLeast(0)
  val height: Int get() = (bottom - top).coerceAtLeast(0)
  val centerX: Int get() = (left + right) / 2
  val centerY: Int get() = (top + bottom) / 2
}

internal data class TicketVisualCardAnchor(
  val anchor: String,
  val bounds: TicketVisualProbeBounds,
  val registrationBounds: TicketVisualProbeBounds? = null,
  /** Registered-status strip that opens the already-activated Aztec detail. */
  val activatedDetailBounds: TicketVisualProbeBounds? = null,
  val latest: Boolean = false
) {
  fun navigationBoundsFor(target: TicketVisualActionTarget): TicketVisualProbeBounds? = when (target) {
    TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
    TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
    TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED,
    TicketVisualActionTarget.REDETECT_LATEST -> registrationBounds
    TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> activatedDetailBounds
    TicketVisualActionTarget.REGISTER_CURRENT,
    TicketVisualActionTarget.PROVE_CURRENT -> null
  }
}

internal enum class TicketVisualPhoneState(val wireName: String) {
  ACTIVATED_DETAIL("activated_detail"),
  UNACTIVATED_DETAIL("unactivated_detail"),
  TICKET_LIST("ticket_list"),
  TICKETS_SINGLE_USE_EMPTY("tickets_single_use_empty"),
  VIVI_HOME("vivi_home"),
  LOGIN_REQUIRED("login_required"),
  BLOCKED("blocked"),
  UNKNOWN("unknown");

  companion object {
    fun fromWireName(value: String): TicketVisualPhoneState = entries.firstOrNull {
      it.wireName == value
    } ?: UNKNOWN
  }
}

internal data class TicketVisualActionObservation(
  val probeId: Long,
  val state: TicketVisualPhoneState,
  val currentAnchor: String = "",
  val sliderBounds: TicketVisualProbeBounds? = null,
  val controlCodeBounds: TicketVisualProbeBounds? = null,
  val backBounds: TicketVisualProbeBounds? = null,
  val ticketsTabBounds: TicketVisualProbeBounds? = null,
  val timeTicketsTabBounds: TicketVisualProbeBounds? = null,
  val cards: List<TicketVisualCardAnchor> = emptyList(),
  val atMillis: Long = 0L
) {
  fun timeTicketsNavigationBoundsFor(target: TicketVisualActionTarget): TicketVisualProbeBounds? =
    timeTicketsTabBounds.takeIf {
      state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY && target in setOf(
        TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
        TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
        TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
        TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED,
        TicketVisualActionTarget.REDETECT_LATEST
      )
    }

  fun cardFor(target: TicketVisualActionTarget, anchors: TicketVisualSwitchAnchors): TicketVisualCardAnchor? {
    val anchor = when (target) {
      TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> anchors.recentActivatedAnchor
      TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED -> anchors.latestUnactivatedAnchor
      else -> ""
    }
    return if (anchor.isNotBlank()) cards.singleOrNull { it.anchor == anchor } else null
  }

  fun latestCard(): TicketVisualCardAnchor? = cards.singleOrNull { it.latest }
  fun latestRegistrationCard(): TicketVisualCardAnchor? = cards.singleOrNull {
    it.latest && it.registrationBounds != null
  }

  fun uniqueActivatedDetailCard(): TicketVisualCardAnchor? = cards.singleOrNull {
    it.activatedDetailBounds != null
  }

  fun activatedCardForRecentDetail(
    anchors: TicketVisualSwitchAnchors
  ): TicketVisualCardAnchor? {
    cardFor(TicketVisualActionTarget.SHOW_RECENT_ACTIVATED, anchors)
      ?.takeIf { it.activatedDetailBounds != null }
      ?.let { return it }
    // register_current can begin from prove_current without first visiting the list. That safe
    // path retains a detail-only identity (d_...) rather than the card's date-derived identity.
    // Current ViVi exposes the latest registration on a separate compact status target. Use only
    // that one unique target, never the generic card body or the registration button, and let the
    // caller require a fresh activated-detail proof after the tap.
    if (!anchors.recentActivatedAnchor.startsWith("d_")) return null
    return uniqueActivatedDetailCard()
  }

  /**
   * Control-code recovery may encounter a ticket activated before Pixel began retaining visual
   * anchors. Only that compatibility case may use one unambiguous registered-status target.
   * A nonblank card anchor must still match exactly; a detail anchor retains the unique-target
   * selection and is checked again against the reopened detail signature by the caller.
   */
  fun activatedCardForControlCode(
    anchors: TicketVisualSwitchAnchors
  ): TicketVisualCardAnchor? {
    val rememberedAnchor = anchors.recentActivatedAnchor
    if (rememberedAnchor.isBlank()) return uniqueActivatedDetailCard()
    cardFor(TicketVisualActionTarget.SHOW_RECENT_ACTIVATED, anchors)
      ?.takeIf { it.activatedDetailBounds != null }
      ?.let { return it }
    if (!rememberedAnchor.startsWith("d_")) return null
    return uniqueActivatedDetailCard()
  }
}

internal fun ticketVisualObservationsAgree(
  first: TicketVisualActionObservation,
  second: TicketVisualActionObservation,
  geometryTolerance: Int = 3
): Boolean {
  if (first.state != second.state) return false
  val anchorsAgree = first.currentAnchor == second.currentAnchor
  if (!anchorsAgree) return false
  if (!boundsAgree(first.sliderBounds, second.sliderBounds, geometryTolerance) ||
    !boundsAgree(first.controlCodeBounds, second.controlCodeBounds, geometryTolerance) ||
    !boundsAgree(first.backBounds, second.backBounds, geometryTolerance) ||
    !boundsAgree(first.ticketsTabBounds, second.ticketsTabBounds, geometryTolerance) ||
    !boundsAgree(first.timeTicketsTabBounds, second.timeTicketsTabBounds, geometryTolerance)
  ) return false
  val firstCards = first.cards.sortedWith(compareBy<TicketVisualCardAnchor> { it.anchor }
    .thenBy { it.bounds.top })
  val secondCards = second.cards.sortedWith(compareBy<TicketVisualCardAnchor> { it.anchor }
    .thenBy { it.bounds.top })
  if (firstCards.size != secondCards.size) return false
  return firstCards.zip(secondCards).all { (left, right) ->
    left.anchor == right.anchor && left.latest == right.latest &&
      boundsAgree(left.bounds, right.bounds, geometryTolerance) &&
      boundsAgree(left.registrationBounds, right.registrationBounds, geometryTolerance) &&
      boundsAgree(left.activatedDetailBounds, right.activatedDetailBounds, geometryTolerance)
  }
}

/**
 * Retains the last usable visual candidate while a single observation window is extended.
 * UNKNOWN animation frames never erase a candidate unless the caller explicitly accepts UNKNOWN,
 * and distinct probe ids plus the existing tap-grade agreement remain mandatory.
 */
internal class TicketVisualObservationConsensus {
  private var previous: TicketVisualActionObservation? = null

  fun reset() {
    previous = null
  }

  fun offer(
    current: TicketVisualActionObservation,
    allowUnknown: Boolean = false
  ): TicketVisualActionObservation? {
    if (current.state == TicketVisualPhoneState.UNKNOWN && !allowUnknown) return null
    val prior = previous
    val agrees = prior != null && prior.probeId != current.probeId &&
      ticketVisualObservationsAgree(prior, current)
    if (!agrees) {
      previous = current
      return null
    }
    return if (current.currentAnchor.isBlank() && prior.currentAnchor.isNotBlank()) {
      current.copy(currentAnchor = prior.currentAnchor)
    } else {
      current
    }
  }
}

private fun boundsAgree(
  first: TicketVisualProbeBounds?,
  second: TicketVisualProbeBounds?,
  tolerance: Int
): Boolean {
  if (first == null || second == null) return first == second
  return kotlin.math.abs(first.left - second.left) <= tolerance &&
    kotlin.math.abs(first.top - second.top) <= tolerance &&
    kotlin.math.abs(first.right - second.right) <= tolerance &&
    kotlin.math.abs(first.bottom - second.bottom) <= tolerance
}

internal fun ticketVisualResultView(
  target: TicketVisualActionTarget,
  finalState: TicketVisualPhoneState
): TicketVisualActionView = when {
  finalState == TicketVisualPhoneState.UNACTIVATED_DETAIL -> TicketVisualActionView.LATEST_UNACTIVATED
  target == TicketVisualActionTarget.SHOW_RECENT_ACTIVATED &&
    finalState == TicketVisualPhoneState.ACTIVATED_DETAIL -> TicketVisualActionView.RECENT_ACTIVATED
  finalState == TicketVisualPhoneState.ACTIVATED_DETAIL -> TicketVisualActionView.ACTIVATED_CURRENT
  else -> TicketVisualActionView.UNKNOWN
}

internal fun ticketRegistrationProofMatchesVisualAnchor(
  proof: TicketRegistrationProof,
  visualAnchor: String
): Boolean = proof.ticketAnchor.isNotBlank() && proof.ticketAnchor == visualAnchor

internal fun ticketRegistrationProofMatchesVisualDetail(
  proof: TicketRegistrationProof,
  visualAnchor: String
): Boolean {
  return proof.ticketAnchor.isNotBlank() &&
    proof.detailAnchor.isNotBlank() &&
    visualAnchor.isNotBlank() &&
    proof.detailAnchor == visualAnchor
}

internal data class TicketRegistrationProofGateResult(
  val proof: TicketRegistrationProof?,
  val failureReason: String?
)

/**
 * A geometry refresh for the same exact visual-action revision must retain the already-proved
 * phone-local ticket identity. A geometry-only update must never erase the identity that
 * register_current is required to reconcile before dispatch.
 */
internal fun ticketRegistrationProofPreservingExactIdentity(
  prior: TicketRegistrationProof?,
  next: TicketRegistrationProof
): TicketRegistrationProof {
  val exactPrior = prior?.takeIf {
    it.interactionRevision.isNotBlank() &&
      it.interactionRevision == next.interactionRevision
  } ?: return next
  return next.copy(
    ticketAnchor = next.ticketAnchor.ifBlank { exactPrior.ticketAnchor },
    detailAnchor = next.detailAnchor.ifBlank { exactPrior.detailAnchor }
  )
}

/** Keeps the durable list-card identity separate from the fresh phone-local detail signature. */
internal fun ticketVisualProvenTicketAnchor(
  observation: TicketVisualActionObservation,
  exactRegistrationProof: TicketRegistrationProof?
): String = exactRegistrationProof?.ticketAnchor.orEmpty().ifBlank {
  observation.currentAnchor
}

/**
 * Binds register_current to the exact proof named by the browser command. The retained proof is
 * identity only: the gesture geometry and freshness come from the new agreeing detail
 * observation, and the executor binds a new positive frame watermark before any drag. The
 * durable reducer separately rejects an expired or replaced action revision.
 */
internal fun ticketRegistrationProofForCurrentVisualAction(
  proof: TicketRegistrationProof?,
  request: TicketVisualActionRequest,
  observation: TicketVisualActionObservation,
  currentStreamEpoch: Long,
  currentFrameSequence: Long
): TicketRegistrationProofGateResult {
  val genericFailure = TicketRegistrationProofGateResult(
    proof = null,
    failureReason = "ticket_action_interaction_revision_unproved"
  )
  if (request.target != TicketVisualActionTarget.REGISTER_CURRENT || proof == null) {
    return genericFailure
  }
  val candidate = proof.takeIf {
    observation.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
      observation.sliderBounds != null &&
      it.status == "unactivated_ready" &&
      it.interactionRevision == request.expectedInteractionRevision &&
      it.ticketAnchor.isNotBlank() &&
      it.streamEpoch > 0L &&
      it.frameSequence > 0L &&
      it.streamEpoch == currentStreamEpoch &&
      it.frameSequence <= currentFrameSequence
  } ?: return genericFailure
  if (!ticketRegistrationProofMatchesVisualDetail(candidate, observation.currentAnchor)) {
    return TicketRegistrationProofGateResult(
      proof = null,
      failureReason = "ticket_action_detail_identity_conflict"
    )
  }
  return TicketRegistrationProofGateResult(proof = candidate, failureReason = null)
}

/**
 * A stable ticket-list observation proves the exact card and its tap bounds. Once that single
 * tap produces the expected typed detail view, keep the card's opaque identity as the cross-view
 * identity while retaining the independently recognized detail identity in the registration proof.
 */
internal fun ticketVisualObservationAfterCardSelection(
  observation: TicketVisualActionObservation,
  selectedAnchor: String
): TicketVisualActionObservation = if (
  selectedAnchor.isNotBlank() && observation.state in setOf(
    TicketVisualPhoneState.UNACTIVATED_DETAIL,
    TicketVisualPhoneState.ACTIVATED_DETAIL
  )
) {
  observation.copy(currentAnchor = selectedAnchor)
} else {
  observation
}

/**
 * Activated-detail recognition intentionally reuses the exact pre-gesture identity because the
 * activated layout cannot independently expose that detail identity. Every other post-gesture
 * state keeps its detector-provided anchor so an unrelated or unproved detail is never mistaken
 * for the ticket that was swiped.
 */
internal fun ticketVisualActivationObservationAfterCompletedGesture(
  observation: TicketVisualActionObservation?,
  provenAnchor: String
): TicketVisualActionObservation? = if (
  observation?.state == TicketVisualPhoneState.ACTIVATED_DETAIL && provenAnchor.isNotBlank()
) {
  observation.copy(currentAnchor = provenAnchor)
} else {
  observation
}

internal fun ticketVisualPostGestureFailureReason(
  observation: TicketVisualActionObservation?,
  provenAnchor: String
): String = if (
  observation?.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
  provenAnchor.isNotBlank() &&
  observation.currentAnchor.isNotBlank() &&
  observation.currentAnchor == provenAnchor
) {
  "ticket_action_gesture_completed_no_transition"
} else {
  "ticket_action_post_gesture_visual_unproved"
}

/**
 * A registration started from prove_current has only the detail signature (d_...) as its recent
 * activated identity. In that case the unique registered-status target proves which list control
 * may be tapped, while the freshly reopened detail must retain and match that exact signature.
 * Date-derived card identities keep the normal cross-view rebinding behavior.
 */
internal fun ticketVisualObservationAfterRecentActivatedSelection(
  observation: TicketVisualActionObservation,
  selectedCardAnchor: String,
  recentActivatedAnchor: String
): TicketVisualActionObservation = if (recentActivatedAnchor.startsWith("d_")) {
  observation
} else {
  ticketVisualObservationAfterCardSelection(observation, selectedCardAnchor)
}

internal fun ticketVisualControlCodeActivatedDetailProved(
  observation: TicketVisualActionObservation,
  anchors: TicketVisualSwitchAnchors
): Boolean {
  if (observation.state != TicketVisualPhoneState.ACTIVATED_DETAIL ||
    observation.currentAnchor.isBlank()
  ) return false
  val rememberedAnchor = anchors.recentActivatedAnchor
  return !rememberedAnchor.startsWith("d_") || observation.currentAnchor == rememberedAnchor
}

/** A failed control-code lookup may return only to the exact detail it started from. */
internal fun ticketVisualControlCodeUnactivatedDetailRestored(
  initial: TicketVisualActionObservation,
  restored: TicketVisualActionObservation
): Boolean = initial.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
  restored.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
  initial.currentAnchor.isNotBlank() &&
  restored.currentAnchor == initial.currentAnchor

internal fun ticketVisualTerminalIntendedAnchor(
  prior: TicketVisualActionJournalState,
  observation: TicketVisualActionObservation?
): String = prior.intendedAnchor.ifBlank { observation?.currentAnchor.orEmpty() }

internal fun ticketVisualCheckpointMatchesActivatedAnchor(
  observation: TicketVisualActionObservation,
  anchors: TicketVisualSwitchAnchors
): Boolean = observation.state == TicketVisualPhoneState.ACTIVATED_DETAIL &&
  observation.currentAnchor.isNotBlank() &&
  observation.currentAnchor == anchors.recentActivatedAnchor
