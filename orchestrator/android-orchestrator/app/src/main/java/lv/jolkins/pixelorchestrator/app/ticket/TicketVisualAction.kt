package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal enum class TicketVisualActionTarget(val wireName: String, val activatesTicket: Boolean = false) {
  OPEN_LATEST_UNACTIVATED("open_latest_unactivated"),
  OPEN_LATEST_AND_REGISTER("open_latest_and_register", activatesTicket = true),
  REGISTER_CURRENT("register_current", activatesTicket = true),
  SHOW_RECENT_ACTIVATED("show_recent_activated"),
  RETURN_TO_LATEST_UNACTIVATED("return_to_latest_unactivated"),
  REDETECT_LATEST("redetect_latest"),
  REFRESH_CURRENT_TICKET("refresh_current_ticket");

  companion object {
    fun fromWireName(value: String): TicketVisualActionTarget? = decision("target", "value" to value.nativeJson())
  }
}

internal enum class TicketVisualActionView(val wireName: String) {
  LATEST_UNACTIVATED("latest_unactivated"),
  RECENT_ACTIVATED("recent_activated"),
  ACTIVATED_CURRENT("activated_current"),
  UNKNOWN("unknown")
}

@Serializable
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
  /** Spacetime-owned business deadline validated when decoding an admitted command. */
  val switchExpiresAt: String,
  val flow: String = "",
  val refreshActivationAttemptId: String = "",
  val refreshActivationRevision: String = "",
  val commandId: String = "",
  val commandRevision: String = ""
)

@Serializable
internal data class TicketVisualActionSnapshot(
  val actionId: String = "",
  val target: String = "",
  val status: String = "idle",
  val phase: String = "idle",
  val currentView: TicketVisualActionView = TicketVisualActionView.UNKNOWN,
  val streamEpoch: Long = 0L,
  val frameSequence: Long = 0L,
  val reason: String = "",
  val completedAt: String = "",
  val interactionRevision: String = "",
  val activationRevision: String = "",
  val activationAttemptId: String = "",
  /** Typed unencoded observation was verified on the phone; video is presentation only. */
  val semanticProof: Boolean = false,
  val terminal: Boolean = false,
  val ok: Boolean = false,
  /** In-memory private proof carried to the action owner; never part of the wire result. */
  val proofObservation: TicketVisualActionObservation? = null
)

/** Only an old terminal journal may echo geometry into its original settlement fingerprint. */
@Serializable
internal data class RetainedTicketSliderGeometry(
  val leftBasisPoints: Int,
  val topBasisPoints: Int,
  val rightBasisPoints: Int,
  val bottomBasisPoints: Int
)

/** Durable phone-local handoff for one idempotent server settlement. */
@Serializable
internal data class TicketActionFinalizationEnvelope(
  val commandId: String,
  val commandRevision: String,
  val flow: String,
  val refreshActivationAttemptId: String,
  val refreshActivationRevision: String,
  val action: TicketVisualActionSnapshot,
  val retainedGeometry: RetainedTicketSliderGeometry? = null
)

@Serializable
internal data class TicketVisualSwitchAnchors(
  val recentActivatedAnchor: String = "",
  val latestUnactivatedAnchor: String = ""
)

@Serializable
internal data class TicketVisualActionJournalState(
  val commandId: String = "",
  val commandRevision: String = "",
  val flow: String = "",
  val refreshActivationAttemptId: String = "",
  val refreshActivationRevision: String = "",
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
  val terminalPhase: String = "",
  val terminalReason: String = "",
  val terminalView: String = "",
  val interactionRevision: String = "",
  val activationRevision: String = "",
  val activationAttemptId: String = "",
  val completedAt: String = "",
  val terminalOk: Boolean = false,
  val semanticProof: Boolean = false,
  /** Storage compatibility only: preserve an old terminal's exact settlement fingerprint. */
  val sliderLeftBasisPoints: Int = -1,
  val sliderTopBasisPoints: Int = -1,
  val sliderRightBasisPoints: Int = -1,
  val sliderBottomBasisPoints: Int = -1
) {
  val navigationDispatchUncertain: Boolean
    get() = decision("uncertain", "journal" to nativeJson())

  val hasRetainedTerminal: Boolean
    get() = decision("retained", "journal" to nativeJson())
}

internal object NativeTicketAction {
  val json = Json { encodeDefaults = true }
  init { System.loadLibrary("pixel_health") }
  external fun decide(operation: String, payload: String): String
}

private inline fun <reified T> T.nativeJson(): JsonElement = NativeTicketAction.json.encodeToJsonElement(this)
private inline fun <reified T> decision(operation: String, vararg args: Pair<String, JsonElement>): T =
  NativeTicketAction.json.decodeFromString(NativeTicketAction.decide(operation, JsonObject(args.toMap()).toString()))

/** Storage effects and exact read-back remain in their existing Android owner. */
internal fun ticketVisualActionJournalWriteProved(value: TicketVisualActionJournalState, commit: () -> Boolean,
  readBack: () -> TicketVisualActionJournalState): Boolean = commit() && readBack() == value

internal fun ticketVisualTerminalViewCompatible(target: TicketVisualActionTarget, view: TicketVisualActionView): Boolean =
  decision("terminal_view", "target" to target.nativeJson(), "view" to view.nativeJson())

internal fun retainedTicketVisualTerminalSnapshot(journal: TicketVisualActionJournalState, request: TicketVisualActionRequest,
  streamEpoch: Long, frameSequence: Long): TicketVisualActionSnapshot? = decision("terminal", "journal" to journal.nativeJson(),
    "request" to request.nativeJson(), "epoch" to streamEpoch.nativeJson(), "sequence" to frameSequence.nativeJson())

internal fun ticketVisualLatestNotDetectedJournalHasBoundProof(journal: TicketVisualActionJournalState): Boolean =
  decision("negative_journal", "journal" to journal.nativeJson())

internal fun ticketActionFinalizationEnvelope(journal: TicketVisualActionJournalState): TicketActionFinalizationEnvelope? =
  decision("finalize", "journal" to journal.nativeJson())

internal fun ticketVisualJournalReconciled(journal: TicketVisualActionJournalState, request: TicketVisualActionRequest,
  observation: TicketVisualActionObservation): Boolean = decision("reconciled", "journal" to journal.nativeJson(),
    "request" to request.nativeJson(), "observation" to observation.nativeJson())

internal class TicketVisualCaptureRecoveryBudget {
  var consumed: Boolean = false
    private set
  fun consumeIfCaptureWasInterrupted(baselineStreamEpoch: Long, currentStreamEpoch: Long, baselineRestartCount: Long,
    currentRestartCount: Long, completedProbeSeen: Boolean): Boolean {
    val admitted: Boolean = decision("recovery", "consumed" to consumed.nativeJson(), "baselineEpoch" to baselineStreamEpoch.nativeJson(),
      "currentEpoch" to currentStreamEpoch.nativeJson(), "baselineRestart" to baselineRestartCount.nativeJson(),
      "currentRestart" to currentRestartCount.nativeJson(), "completed" to completedProbeSeen.nativeJson())
    if (admitted) consumed = true
    return admitted
  }
}

internal fun parseTicketVisualActionRequest(payload: JsonObject): TicketVisualActionRequest? =
  NativeTicketAction.json.decodeFromString<TicketVisualActionRequest?>(NativeTicketAction.decide("parse", payload.toString()))?.let {
    // Java string slicing preserves the former UTF-16 wire representation, including split pairs.
    it.copy(source = it.source.take(64), reason = it.reason.take(160),
      expectedInteractionRevision = it.expectedInteractionRevision.take(128), scheduleId = it.scheduleId.take(128))
  }

@Serializable
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

@Serializable
internal data class TicketVisualCardAnchor(
  val anchor: String,
  val bounds: TicketVisualProbeBounds,
  val registrationBounds: TicketVisualProbeBounds? = null,
  /** Registered-status strip that opens the already-activated Aztec detail. */
  val activatedDetailBounds: TicketVisualProbeBounds? = null,
  val latest: Boolean = false
) {
  fun navigationBoundsFor(target: TicketVisualActionTarget): TicketVisualProbeBounds? =
    decision("navigation_bounds", "card" to nativeJson(), "target" to target.nativeJson())
}

internal enum class TicketVisualPhoneState(val wireName: String) {
  ACTIVATED_DETAIL("activated_detail"),
  UNACTIVATED_DETAIL("unactivated_detail"),
  TICKET_LIST("ticket_list"),
  TICKETS_SINGLE_USE_EMPTY("tickets_single_use_empty"),
  TICKETS_TIME_EMPTY("tickets_time_empty"),
  VIVI_HOME("vivi_home"),
  VIVI_PROFILE("vivi_profile"),
  VIVI_OTHER_TAB("vivi_other_tab"),
  LOGIN_REQUIRED("login_required"),
  BLOCKED("blocked"),
  UNKNOWN("unknown");

  companion object {
    fun fromWireName(value: String): TicketVisualPhoneState = entries.firstOrNull {
      it.wireName == value
    } ?: UNKNOWN
  }
}

internal enum class TicketViviBottomTab(val wireName: String) {
  HOME("home"),
  TICKETS("tickets"),
  PROFILE("profile"),
  MENU("menu"),
  NONE("");

  companion object {
    fun fromWireName(value: String): TicketViviBottomTab = entries.firstOrNull {
      it.wireName == value
    } ?: NONE
  }
}

@Serializable
internal data class TicketVisualActionObservation(
  val probeId: Long,
  val state: TicketVisualPhoneState,
  val currentAnchor: String = "",
  val sliderBounds: TicketVisualProbeBounds? = null,
  val controlCodeBounds: TicketVisualProbeBounds? = null,
  val backBounds: TicketVisualProbeBounds? = null,
  val ticketsTabBounds: TicketVisualProbeBounds? = null,
  val timeTicketsTabBounds: TicketVisualProbeBounds? = null,
  val bottomTab: TicketViviBottomTab = TicketViviBottomTab.NONE,
  val cards: List<TicketVisualCardAnchor> = emptyList(),
  val atMillis: Long = 0L,
  val captureStartUs: Long = 0L,
  /** Local helper generation; never supplied by a browser or persisted in a result. */
  val captureGeneration: Long = 0L,
  /** Exact validity-pair card identity from a full action probe, never an input freshness proof. */
  val detailCardAnchor: String = ""
) {
  fun timeTicketsNavigationBoundsFor(target: TicketVisualActionTarget): TicketVisualProbeBounds? =
    decision("time_bounds", "observation" to nativeJson(), "target" to target.nativeJson())
  fun singleUseTicketsNavigationBoundsFor(target: TicketVisualActionTarget): TicketVisualProbeBounds? =
    decision("single_bounds", "observation" to nativeJson(), "target" to target.nativeJson())
  fun cardFor(target: TicketVisualActionTarget, anchors: TicketVisualSwitchAnchors): TicketVisualCardAnchor? =
    decision("card", "observation" to nativeJson(), "target" to target.nativeJson(), "anchors" to anchors.nativeJson())
  fun latestRegistrationCard(): TicketVisualCardAnchor? = decision("latest_card", "observation" to nativeJson())
  fun uniqueActivatedDetailCard(): TicketVisualCardAnchor? = decision("activated_card", "observation" to nativeJson())
  fun activatedCardForRecentDetail(anchors: TicketVisualSwitchAnchors): TicketVisualCardAnchor? =
    decision("recent_card", "observation" to nativeJson(), "anchors" to anchors.nativeJson())
}

internal const val TICKET_ACTION_LIST_TO_SINGLE_USE = "ticket_list_to_single_use"
internal const val TICKET_ACTION_LIST_TO_TIME = "ticket_list_to_time"

@Serializable
private data class RedetectTabDecision(val bounds: TicketVisualProbeBounds, val transition: String)

internal fun ticketVisualRedetectListTabTarget(observation: TicketVisualActionObservation, returnedToTime: Boolean): Pair<TicketVisualProbeBounds, String>? =
  decision<RedetectTabDecision?>("redetect_tab", "observation" to observation.nativeJson(), "returned" to returnedToTime.nativeJson())?.let {it.bounds to it.transition}

internal fun ticketVisualObservationsAgree(first: TicketVisualActionObservation, second: TicketVisualActionObservation, geometryTolerance: Int = 3): Boolean =
  decision("agree", "first" to first.nativeJson(), "second" to second.nativeJson(), "tolerance" to geometryTolerance.nativeJson())

@Serializable
private data class ConsensusDecision(val prior: TicketVisualActionObservation?, val answer: TicketVisualActionObservation?)

internal class TicketVisualObservationConsensus {
  private var previous: TicketVisualActionObservation? = null
  fun reset() {previous = null}
  fun offer(current: TicketVisualActionObservation, allowUnknown: Boolean = false): TicketVisualActionObservation? {
    val result: ConsensusDecision = decision("consensus", "prior" to previous.nativeJson(), "observation" to current.nativeJson(), "allowUnknown" to allowUnknown.nativeJson())
    previous = result.prior
    return result.answer
  }
}

internal fun ticketVisualObservationIsFreshForDispatch(observation: TicketVisualActionObservation, nowMillis: Long, maxAgeMillis: Long): Boolean =
  decision("dispatch_fresh", "observation" to observation.nativeJson(), "now" to nowMillis.nativeJson(), "maxAge" to maxAgeMillis.nativeJson())

internal fun ticketVisualResultView(target: TicketVisualActionTarget, finalState: TicketVisualPhoneState): TicketVisualActionView =
  decision("result_view", "target" to target.nativeJson(), "state" to finalState.nativeJson())

internal fun ticketVisualRedetectLatestNotDetectedObservation(target: TicketVisualActionTarget, navigationFromState: TicketVisualPhoneState,
  observation: TicketVisualActionObservation): Boolean = decision("negative_observation", "target" to target.nativeJson(),
    "from" to navigationFromState.nativeJson(), "observation" to observation.nativeJson())

internal fun ticketVisualLatestNotDetectedTerminalHasBoundProof(snapshot: TicketVisualActionSnapshot): Boolean = decision("negative_terminal", "snapshot" to snapshot.nativeJson())

internal fun ticketRegistrationProofMatchesVisualDetail(proof: TicketRegistrationProof, visualAnchor: String): Boolean =
  decision("proof_matches", "proof" to proof.nativeJson(), "anchor" to visualAnchor.nativeJson())

@Serializable
internal data class TicketRegistrationProofGateResult(val proof: TicketRegistrationProof?, val failureReason: String?)

internal fun ticketRegistrationProofRevisionForRegisterCurrent(proofRevision: String, expectedRevision: String): String? =
  decision("proof_revision", "proof" to proofRevision.nativeJson(), "expected" to expectedRevision.nativeJson())

internal fun ticketVisualProvenTicketAnchor(observation: TicketVisualActionObservation, exactRegistrationProof: TicketRegistrationProof?): String =
  decision("proven_anchor", "observation" to observation.nativeJson(), "proof" to exactRegistrationProof.nativeJson())

internal fun ticketRegistrationProofForCurrentVisualAction(proof: TicketRegistrationProof?, request: TicketVisualActionRequest,
  observation: TicketVisualActionObservation): TicketRegistrationProofGateResult = decision("proof_gate", "proof" to proof.nativeJson(),
    "request" to request.nativeJson(), "observation" to observation.nativeJson())

internal fun ticketVisualObservationAfterCardSelection(observation: TicketVisualActionObservation, selectedAnchor: String): TicketVisualActionObservation =
  decision("after_card", "observation" to observation.nativeJson(), "anchor" to selectedAnchor.nativeJson())

internal fun ticketVisualActivationObservationAfterCompletedGesture(observation: TicketVisualActionObservation?, provenAnchor: String): TicketVisualActionObservation? =
  decision("after_activation", "observation" to observation.nativeJson(), "anchor" to provenAnchor.nativeJson())

internal fun ticketVisualObservationAfterRecentActivatedSelection(observation: TicketVisualActionObservation, selectedCardAnchor: String,
  recentActivatedAnchor: String): TicketVisualActionObservation = decision("after_recent", "observation" to observation.nativeJson(),
    "anchor" to selectedCardAnchor.nativeJson(), "recent" to recentActivatedAnchor.nativeJson())

internal fun ticketVisualTerminalIntendedAnchor(prior: TicketVisualActionJournalState, observation: TicketVisualActionObservation?): String =
  decision("intended_anchor", "journal" to prior.nativeJson(), "observation" to observation.nativeJson())

internal fun ticketVisualCheckpointMatchesActivatedAnchor(observation: TicketVisualActionObservation, anchors: TicketVisualSwitchAnchors): Boolean =
  decision("checkpoint_matches", "observation" to observation.nativeJson(), "anchors" to anchors.nativeJson())
