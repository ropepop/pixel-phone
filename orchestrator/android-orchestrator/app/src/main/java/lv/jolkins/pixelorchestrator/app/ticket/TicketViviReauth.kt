package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviAuthSurface

internal data class TicketSpacetimeViviCredentials(
  val email: String,
  val password: String,
  val revision: String
) {
  val valid: Boolean get() = email.isNotBlank() && password.isNotBlank() && revision.isNotBlank()
}

internal data class TicketViviReauthRequest(
  val requestId: String,
  val credentialRevision: String,
  val resetAppData: Boolean = false,
  val logoutInApp: Boolean = false,
  val redetectAfterLogin: Boolean = false
)

private const val VIVI_REAUTH_FULL_RESET_REQUEST_PREFIX = "vivi-full-reset-"
private const val VIVI_REAUTH_LOGOUT_LOGIN_REQUEST_PREFIX = "vivi-logout-login-"
private const val VIVI_REAUTH_LOGOUT_REDETECT_LOGIN_REQUEST_PREFIX =
  "vivi-logout-redetect-login-"
private const val VIVI_REAUTH_LEGACY_REQUEST_PREFIX = "vivi-reauth-"

private fun ticketViviReauthRequestIdInNamespace(value: String, prefix: String): Boolean =
  value.startsWith(prefix) && value.length > prefix.length

internal fun ticketViviReauthRequest(command: TicketSpacetimeCommand): TicketViviReauthRequest? {
  if (command.commandType != "vivi_reauth") return null
  return runCatching {
    val payload = Json.parseToJsonElement(command.payloadJson).jsonObject
    val version = payload["version"]?.jsonPrimitive ?: return@runCatching null
    if (version.isString) return@runCatching null
    var logoutInApp = false
    var redetectAfterLogin = false
    val resetAppData = when (version.intOrNull) {
      1 -> {
        if (payload.keys != setOf("version", "requestId", "credentialRevision")) {
          return@runCatching null
        }
        false
      }
      2 -> {
        if (payload.keys != setOf("version", "requestId", "credentialRevision", "resetAppData")) {
          return@runCatching null
        }
        val reset = payload["resetAppData"]?.jsonPrimitive ?: return@runCatching null
        if (reset.isString || reset.booleanOrNull != true) return@runCatching null
        true
      }
      3 -> {
        if (payload.keys != setOf("version", "requestId", "credentialRevision", "logoutInApp")) {
          return@runCatching null
        }
        val logout = payload["logoutInApp"]?.jsonPrimitive ?: return@runCatching null
        if (logout.isString || logout.booleanOrNull != true) return@runCatching null
        logoutInApp = true
        false
      }
      4 -> {
        if (payload.keys != setOf(
            "version",
            "requestId",
            "credentialRevision",
            "logoutInApp",
            "redetectAfterLogin"
          )
        ) {
          return@runCatching null
        }
        val logout = payload["logoutInApp"]?.jsonPrimitive ?: return@runCatching null
        val redetect = payload["redetectAfterLogin"]?.jsonPrimitive ?: return@runCatching null
        if (logout.isString || logout.booleanOrNull != true ||
          redetect.isString || redetect.booleanOrNull != true
        ) return@runCatching null
        logoutInApp = true
        redetectAfterLogin = true
        false
      }
      else -> return@runCatching null
    }
    val requestIdValue = payload["requestId"]?.jsonPrimitive ?: return@runCatching null
    val credentialRevisionValue = payload["credentialRevision"]?.jsonPrimitive ?: return@runCatching null
    if (!requestIdValue.isString || !credentialRevisionValue.isString) return@runCatching null
    val requestId = requestIdValue.content.trim()
    val credentialRevision = credentialRevisionValue.content.trim()
    if (requestId.isBlank() || credentialRevision.isBlank() || requestId.length > 160 ||
      credentialRevision.length > 160
    ) return@runCatching null
    val requiredPrefix = when {
      resetAppData -> VIVI_REAUTH_FULL_RESET_REQUEST_PREFIX
      redetectAfterLogin -> VIVI_REAUTH_LOGOUT_REDETECT_LOGIN_REQUEST_PREFIX
      logoutInApp -> VIVI_REAUTH_LOGOUT_LOGIN_REQUEST_PREFIX
      else -> VIVI_REAUTH_LEGACY_REQUEST_PREFIX
    }
    if (!ticketViviReauthRequestIdInNamespace(requestId, requiredPrefix)) {
      return@runCatching null
    }
    TicketViviReauthRequest(
      requestId,
      credentialRevision,
      resetAppData,
      logoutInApp,
      redetectAfterLogin
    )
  }.getOrNull()
}

internal data class TicketViviReauthSnapshot(
  val requestId: String = "",
  val credentialRevision: String = "",
  val resetAppData: Boolean = false,
  val logoutInApp: Boolean = false,
  val redetectAfterLogin: Boolean = false,
  val status: String = "idle",
  val phase: String = "idle",
  val reason: String = "",
  val proofSource: String = "",
  val streamEpoch: Long = 0L,
  val frameSequence: Long = 0L,
  val terminal: Boolean = false,
  val ok: Boolean = false
)

private val TICKET_VIVI_REAUTH_SIGNED_IN_STATES = setOf(
  TicketVisualPhoneState.ACTIVATED_DETAIL,
  TicketVisualPhoneState.UNACTIVATED_DETAIL,
  TicketVisualPhoneState.TICKET_LIST,
  TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
  TicketVisualPhoneState.TICKETS_TIME_EMPTY,
  TicketVisualPhoneState.VIVI_HOME,
  TicketVisualPhoneState.VIVI_PROFILE,
  TicketVisualPhoneState.VIVI_OTHER_TAB
)

internal fun ticketViviReauthPreflightAuthenticatedState(
  state: TicketVisualPhoneState?
): Boolean = state in TICKET_VIVI_REAUTH_SIGNED_IN_STATES

internal fun ticketViviReauthPreflightAuthenticatedObservation(
  observation: TicketVisualActionObservation?
): Boolean = observation != null && (
  ticketViviReauthPreflightAuthenticatedState(observation.state) ||
    ticketViviLogoutBottomRouteObservation(observation)
  )

internal fun ticketViviLogoutLoginStartObservation(
  observation: TicketVisualActionObservation?
): Boolean = ticketViviReauthTerminalReadyObservation(observation)

internal fun ticketViviLogoutBottomRouteObservation(
  observation: TicketVisualActionObservation?,
  expectedTab: TicketViviBottomTab? = null
): Boolean {
  if (observation == null || observation.bottomTab == TicketViviBottomTab.NONE ||
    observation.state in setOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      TicketVisualPhoneState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED
    ) || (expectedTab != null && observation.bottomTab != expectedTab)
  ) return false
  return true
}

/**
 * Dispatches one route mutation exactly once and trusts only its proved successor.
 *
 * Accessibility and shell actions can report `false` even after ViVi has accepted them. Replaying
 * such an action would be unsafe, so the immediate acknowledgement is deliberately ignored. The
 * caller must supply a bounded, exact successor observation and stops if that proof is absent.
 */
internal suspend fun <T : Any> ticketViviReauthDispatchOnceThenProve(
  dispatch: suspend () -> Boolean,
  observeSuccessor: suspend () -> T?,
  successorProven: (T) -> Boolean
): T? {
  dispatch()
  val successor = observeSuccessor() ?: return null
  return successor.takeIf(successorProven)
}

internal fun ticketViviReauthPostSubmitSignedInState(
  state: TicketVisualPhoneState?
): Boolean = state in TICKET_VIVI_REAUTH_SIGNED_IN_STATES

internal fun ticketViviReauthPostSubmitSignedInObservation(
  observation: TicketVisualActionObservation?
): Boolean = observation != null && (
  ticketViviReauthPostSubmitSignedInState(observation.state) ||
    ticketViviLogoutBottomRouteObservation(observation)
  )

internal fun ticketViviReauthTerminalReadyObservation(
  observation: TicketVisualActionObservation?
): Boolean = observation != null &&
  observation.state in setOf(
    TicketVisualPhoneState.ACTIVATED_DETAIL,
    TicketVisualPhoneState.UNACTIVATED_DETAIL
  ) &&
  ticketControlCodeDetailAnchorIsValid(observation.currentAnchor) &&
  observation.backBounds != null &&
  when (observation.state) {
    TicketVisualPhoneState.UNACTIVATED_DETAIL -> observation.sliderBounds != null
    TicketVisualPhoneState.ACTIVATED_DETAIL -> observation.sliderBounds == null
    else -> false
  }

internal data class TicketViviReauthReturnTarget(
  val state: TicketVisualPhoneState,
  val detailAnchor: String
)

internal fun ticketViviReauthReturnTarget(
  observation: TicketVisualActionObservation?
): TicketViviReauthReturnTarget? = observation
  ?.takeIf(::ticketViviReauthTerminalReadyObservation)
  ?.let { TicketViviReauthReturnTarget(it.state, it.currentAnchor) }

internal fun ticketViviReauthRestoredTarget(
  target: TicketViviReauthReturnTarget,
  observation: TicketVisualActionObservation?
): Boolean = ticketViviReauthTerminalReadyObservation(observation) &&
  observation?.state == target.state &&
  observation.currentAnchor == target.detailAnchor

internal enum class TicketViviReauthRedetectTargetKind(val journalPhase: String) {
  CLOSE_WRONG_DETAIL("ticket_redetect_detail_close_dispatching"),
  TICKETS_TAB("ticket_redetect_tickets_tab_dispatching"),
  SINGLE_USE_TICKETS_TAB("ticket_redetect_single_use_tab_dispatching"),
  TIME_TICKETS_TAB("ticket_redetect_time_tab_dispatching"),
  LATEST_UNACTIVATED_DETAIL("ticket_redetect_latest_detail_dispatching")
}

internal data class TicketViviReauthRedetectTarget(
  val kind: TicketViviReauthRedetectTargetKind,
  val bounds: TicketVisualProbeBounds,
  val selectedAnchor: String = ""
)

/**
 * Selects only controls already proved by the visual Ticket classifier. The latest-card branch
 * requires one unique latest card with its non-activating registration control; the slider is
 * never returned by this function.
 */
internal fun ticketViviReauthRedetectTarget(
  observation: TicketVisualActionObservation
): TicketViviReauthRedetectTarget? = when (observation.state) {
  TicketVisualPhoneState.ACTIVATED_DETAIL,
  TicketVisualPhoneState.UNACTIVATED_DETAIL -> observation.backBounds?.let {
    TicketViviReauthRedetectTarget(TicketViviReauthRedetectTargetKind.CLOSE_WRONG_DETAIL, it)
  }
  TicketVisualPhoneState.VIVI_HOME,
  TicketVisualPhoneState.VIVI_PROFILE,
  TicketVisualPhoneState.VIVI_OTHER_TAB,
  TicketVisualPhoneState.UNKNOWN -> observation.ticketsTabBounds
    ?.takeIf { ticketViviLogoutBottomRouteObservation(observation) }
    ?.let {
      TicketViviReauthRedetectTarget(TicketViviReauthRedetectTargetKind.TICKETS_TAB, it)
    }
  TicketVisualPhoneState.TICKETS_TIME_EMPTY ->
    observation.singleUseTicketsNavigationBoundsFor(TicketVisualActionTarget.REDETECT_LATEST)?.let {
      TicketViviReauthRedetectTarget(
        TicketViviReauthRedetectTargetKind.SINGLE_USE_TICKETS_TAB,
        it
      )
    }
  TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY ->
    observation.timeTicketsNavigationBoundsFor(TicketVisualActionTarget.REDETECT_LATEST)?.let {
      TicketViviReauthRedetectTarget(TicketViviReauthRedetectTargetKind.TIME_TICKETS_TAB, it)
    }
  TicketVisualPhoneState.TICKET_LIST -> observation.latestRegistrationCard()
    ?.takeIf { it.anchor.isNotBlank() }
    ?.let { card ->
      card.registrationBounds?.let {
        TicketViviReauthRedetectTarget(
          TicketViviReauthRedetectTargetKind.LATEST_UNACTIVATED_DETAIL,
          it,
          card.anchor
        )
      }
    }
  else -> null
}

internal fun ticketViviReauthV4OriginalRestoreTarget(
  returnTarget: TicketViviReauthReturnTarget,
  observation: TicketVisualActionObservation
): TicketViviReauthRestoreTarget? {
  ticketViviReauthRestoreTarget(returnTarget, observation)?.let { return it }
  if (observation.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY) {
    return observation.singleUseTicketsNavigationBoundsFor(TicketVisualActionTarget.REDETECT_LATEST)
      ?.let {
        TicketViviReauthRestoreTarget(
          TicketViviReauthRestoreTargetKind.SINGLE_USE_TICKETS_TAB,
          it
        )
      }
  }
  return observation.ticketsTabBounds
    ?.takeIf {
      observation.state in setOf(
        TicketVisualPhoneState.VIVI_HOME,
        TicketVisualPhoneState.VIVI_PROFILE,
        TicketVisualPhoneState.VIVI_OTHER_TAB,
        TicketVisualPhoneState.UNKNOWN
      ) && ticketViviLogoutBottomRouteObservation(observation)
    }
    ?.let { TicketViviReauthRestoreTarget(TicketViviReauthRestoreTargetKind.TICKETS_TAB, it) }
}

internal fun ticketViviReauthRedetectedLatest(
  before: TicketVisualActionObservation,
  selectedAnchor: String,
  observation: TicketVisualActionObservation?
): Boolean {
  val selectedCard = before.latestRegistrationCard()
  if (before.state != TicketVisualPhoneState.TICKET_LIST ||
    selectedCard?.anchor != selectedAnchor || selectedCard?.registrationBounds == null ||
    selectedAnchor.isBlank() || observation == null || observation.probeId <= before.probeId ||
    !ticketViviReauthTerminalReadyObservation(observation) ||
    observation?.state != TicketVisualPhoneState.UNACTIVATED_DETAIL
  ) return false
  return ticketVisualObservationAfterCardSelection(observation, selectedAnchor).currentAnchor ==
    selectedAnchor
}

internal fun ticketViviReauthOriginalTargetVisuallyAbsent(
  target: TicketViviReauthReturnTarget,
  observation: TicketVisualActionObservation
): Boolean {
  if (observation.state != TicketVisualPhoneState.TICKET_LIST) return false
  return when (target.state) {
    TicketVisualPhoneState.UNACTIVATED_DETAIL ->
      observation.cards.none { it.latest && it.registrationBounds != null }
    TicketVisualPhoneState.ACTIVATED_DETAIL ->
      observation.cards.none { it.activatedDetailBounds != null }
    else -> false
  }
}

internal fun ticketViviReauthNoTicketProven(
  navigationFromState: TicketVisualPhoneState,
  observation: TicketVisualActionObservation,
  streamEpoch: Long,
  frameSequence: Long
): Boolean = ticketVisualRedetectLatestNotDetectedProof(
  TicketVisualActionTarget.REDETECT_LATEST,
  navigationFromState,
  observation,
  streamEpoch,
  frameSequence
)

internal enum class TicketViviReauthRestoreTargetKind(val journalPhase: String) {
  TICKETS_TAB("ticket_restore_tickets_tab_dispatching"),
  SINGLE_USE_TICKETS_TAB("ticket_restore_single_use_tab_dispatching"),
  TIME_TICKETS_TAB("ticket_restore_time_tab_dispatching"),
  LATEST_UNACTIVATED_DETAIL("ticket_restore_detail_dispatching"),
  UNIQUE_ACTIVATED_DETAIL("ticket_restore_detail_dispatching")
}

internal data class TicketViviReauthRestoreTarget(
  val kind: TicketViviReauthRestoreTargetKind,
  val bounds: TicketVisualProbeBounds
)

/**
 * Returns only visually proved, non-activating navigation targets. Opening the orange control on
 * the latest unused card reveals its detail; ticket activation remains a separate slider gesture.
 */
internal fun ticketViviReauthRestoreTarget(
  returnTarget: TicketViviReauthReturnTarget,
  observation: TicketVisualActionObservation
): TicketViviReauthRestoreTarget? = when (observation.state) {
  TicketVisualPhoneState.VIVI_HOME -> observation.ticketsTabBounds?.let {
    TicketViviReauthRestoreTarget(TicketViviReauthRestoreTargetKind.TICKETS_TAB, it)
  }
  TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY ->
    observation.timeTicketsNavigationBoundsFor(TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED)?.let {
      TicketViviReauthRestoreTarget(TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB, it)
    }
  TicketVisualPhoneState.TICKET_LIST ->
    when (returnTarget.state) {
      TicketVisualPhoneState.UNACTIVATED_DETAIL ->
        observation.latestRegistrationCard()?.registrationBounds?.let {
          TicketViviReauthRestoreTarget(
            TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL,
            it
          )
        }
      TicketVisualPhoneState.ACTIVATED_DETAIL ->
        observation.uniqueActivatedDetailCard()?.activatedDetailBounds?.let {
          TicketViviReauthRestoreTarget(
            TicketViviReauthRestoreTargetKind.UNIQUE_ACTIVATED_DETAIL,
            it
          )
        }
      else -> null
    }
  else -> null
}

internal enum class TicketViviReauthRestoreTransitionStatus {
  WAITING,
  PROVED,
  REJECTED
}

/**
 * Reconciles one already-dispatched navigation tap without ever authorizing a replay. An
 * unchanged or still-unclassified frame may be observed again, but only the exact successor for
 * that one target kind can advance the flow.
 */
internal fun ticketViviReauthRestoreTransitionStatus(
  returnTarget: TicketViviReauthReturnTarget,
  dispatchedKind: TicketViviReauthRestoreTargetKind,
  before: TicketVisualActionObservation,
  after: TicketVisualActionObservation?
): TicketViviReauthRestoreTransitionStatus {
  if (after == null || after.probeId <= before.probeId) {
    return TicketViviReauthRestoreTransitionStatus.WAITING
  }
  val expectedDetailKind = when (returnTarget.state) {
    TicketVisualPhoneState.UNACTIVATED_DETAIL ->
      TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL
    TicketVisualPhoneState.ACTIVATED_DETAIL ->
      TicketViviReauthRestoreTargetKind.UNIQUE_ACTIVATED_DETAIL
    else -> return TicketViviReauthRestoreTransitionStatus.REJECTED
  }
  val nextKind = ticketViviReauthRestoreTarget(returnTarget, after)?.kind
  val proved = when (dispatchedKind) {
    TicketViviReauthRestoreTargetKind.TICKETS_TAB ->
      before.state == TicketVisualPhoneState.VIVI_HOME &&
        nextKind in setOf(TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB, expectedDetailKind)
    TicketViviReauthRestoreTargetKind.SINGLE_USE_TICKETS_TAB -> false
    TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB ->
      before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY &&
        nextKind == expectedDetailKind
    TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL ->
      before.state == TicketVisualPhoneState.TICKET_LIST &&
        dispatchedKind == expectedDetailKind &&
        ticketViviReauthRestoredTarget(returnTarget, after)
    TicketViviReauthRestoreTargetKind.UNIQUE_ACTIVATED_DETAIL ->
      before.state == TicketVisualPhoneState.TICKET_LIST &&
        dispatchedKind == expectedDetailKind &&
        ticketViviReauthRestoredTarget(returnTarget, after)
  }
  if (proved) return TicketViviReauthRestoreTransitionStatus.PROVED

  val unchangedTarget = ticketViviReauthRestoreTarget(returnTarget, after)?.kind == dispatchedKind
  if (unchangedTarget || after.state == TicketVisualPhoneState.UNKNOWN) {
    return TicketViviReauthRestoreTransitionStatus.WAITING
  }
  return TicketViviReauthRestoreTransitionStatus.REJECTED
}

/**
 * A stale device-link activity can outlive an operator-side link reset. Only the safe request may
 * refresh that exact initial surface, and the refresh preserves all ViVi app data. Full reset has
 * its own destructive one-shot contract and must never inherit this extra lifecycle mutation.
 */
internal fun ticketViviSafeReauthShouldRefreshInitialDeviceLink(
  resetAppData: Boolean,
  surface: PhoneAutomationViviAuthSurface
): Boolean = !resetAppData && surface == PhoneAutomationViviAuthSurface.DEVICE_LINK

internal data class TicketViviReauthJournal(
  val requestId: String = "",
  val credentialRevision: String = "",
  val resetAppData: Boolean = false,
  val logoutInApp: Boolean = false,
  val redetectAfterLogin: Boolean = false,
  val phase: String = "",
  val terminalStatus: String = "",
  val terminalReason: String = "",
  val proofSource: String = "",
  val streamEpoch: Long = 0L,
  val frameSequence: Long = 0L,
  val terminalOk: Boolean = false
) {
  val terminal: Boolean get() = terminalStatus.isNotBlank()
  // Retain this phase for both a current full reset and any unfinished pre-upgrade clear, so
  // journal reconciliation never downgrades a possibly destructive dispatch to a safe retry.
  val mutationMayHaveDispatched: Boolean get() = phase in setOf(
    "clear_dispatching",
    "opening_vivi",
    "device_link_force_stop_dispatching",
    "device_link_relaunch_dispatching",
    "detail_close_dispatching",
    "profile_tab_dispatching",
    "account_controls_dispatching",
    "account_scroll_dispatching",
    "logout_dispatching",
    "verifying_signed_out",
    "email_dispatching",
    "password_dispatching",
    "submit_dispatching",
    "verifying_signed_in",
    "ticket_restore_tickets_tab_dispatching",
    "ticket_restore_single_use_tab_dispatching",
    "ticket_restore_time_tab_dispatching",
    "ticket_restore_detail_dispatching",
    "ticket_redetect_detail_close_dispatching",
    "ticket_redetect_tickets_tab_dispatching",
    "ticket_redetect_single_use_tab_dispatching",
    "ticket_redetect_time_tab_dispatching",
    "ticket_redetect_latest_detail_dispatching"
  )
}

internal fun ticketViviReauthJournalMatchesRequest(
  journal: TicketViviReauthJournal,
  request: TicketViviReauthRequest
): Boolean = journal.requestId == request.requestId &&
  journal.credentialRevision == request.credentialRevision &&
  journal.resetAppData == request.resetAppData &&
  journal.logoutInApp == request.logoutInApp &&
  journal.redetectAfterLogin == request.redetectAfterLogin

internal fun ticketViviReauthRequestIdHasSemanticMismatch(
  journal: TicketViviReauthJournal,
  request: TicketViviReauthRequest
): Boolean = journal.requestId.isNotBlank() && journal.requestId == request.requestId &&
  !ticketViviReauthJournalMatchesRequest(journal, request)

internal fun retainedTicketViviReauthSnapshot(
  journal: TicketViviReauthJournal,
  request: TicketViviReauthRequest
): TicketViviReauthSnapshot? {
  if (!journal.terminal || !ticketViviReauthJournalMatchesRequest(journal, request)) return null
  return TicketViviReauthSnapshot(
    requestId = journal.requestId,
    credentialRevision = journal.credentialRevision,
    resetAppData = journal.resetAppData,
    logoutInApp = journal.logoutInApp,
    redetectAfterLogin = journal.redetectAfterLogin,
    status = journal.terminalStatus,
    phase = "complete",
    reason = journal.terminalReason,
    proofSource = journal.proofSource,
    streamEpoch = journal.streamEpoch,
    frameSequence = journal.frameSequence,
    terminal = true,
    ok = journal.terminalOk
  )
}

internal fun ticketViviReauthJournalWriteProved(
  value: TicketViviReauthJournal,
  commit: () -> Boolean,
  readBack: () -> TicketViviReauthJournal
): Boolean = commit() && readBack() == value

@Serializable
data class TicketViviReauthHealth(
  val supported: Boolean = true,
  val commandType: String = "vivi_reauth",
  val status: String = "idle",
  val phase: String = "idle",
  val reason: String = "",
  val credentialRevisionObserved: Boolean = false,
  val completedAgoMillis: Long? = null
)
