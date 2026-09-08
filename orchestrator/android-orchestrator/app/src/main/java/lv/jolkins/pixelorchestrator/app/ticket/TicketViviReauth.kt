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
      else -> VIVI_REAUTH_LOGOUT_LOGIN_REQUEST_PREFIX
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

/** A lost acknowledgement cannot justify replaying an input; prove its successor instead. */
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

// All post-login navigation shares one bounded executor. The mode changes only
// the allowed destination and whether a proved absence may lead to latest-ticket search.
internal enum class TicketViviNavigationMode { EXACT, ORIGINAL, LATEST }

internal enum class TicketViviNavigationKind(val restorePhase: String, val latestPhase: String) {
  CLOSE_DETAIL("", "ticket_redetect_detail_close_dispatching"),
  TICKETS_TAB("ticket_restore_tickets_tab_dispatching", "ticket_redetect_tickets_tab_dispatching"),
  SINGLE_USE_TAB("ticket_restore_single_use_tab_dispatching", "ticket_redetect_single_use_tab_dispatching"),
  TIME_TAB("ticket_restore_time_tab_dispatching", "ticket_redetect_time_tab_dispatching"),
  UNUSED_DETAIL("ticket_restore_detail_dispatching", "ticket_redetect_latest_detail_dispatching"),
  ACTIVATED_DETAIL("ticket_restore_detail_dispatching", "")
}

internal data class TicketViviNavigationTarget(
  val kind: TicketViviNavigationKind,
  val bounds: TicketVisualProbeBounds,
  val selectedAnchor: String = ""
)

internal fun ticketViviNavigationTarget(
  mode: TicketViviNavigationMode,
  original: TicketViviReauthReturnTarget?,
  observation: TicketVisualActionObservation
): TicketViviNavigationTarget? {
  fun target(kind: TicketViviNavigationKind, bounds: TicketVisualProbeBounds?) =
    bounds?.let { TicketViviNavigationTarget(kind, it) }
  return when (observation.state) {
    TicketVisualPhoneState.ACTIVATED_DETAIL,
    TicketVisualPhoneState.UNACTIVATED_DETAIL ->
      if (mode == TicketViviNavigationMode.LATEST) {
        target(TicketViviNavigationKind.CLOSE_DETAIL, observation.backBounds)
      } else null
    TicketVisualPhoneState.VIVI_HOME ->
      if (mode != TicketViviNavigationMode.LATEST || ticketViviLogoutBottomRouteObservation(observation)) {
        target(TicketViviNavigationKind.TICKETS_TAB, observation.ticketsTabBounds)
      } else null
    TicketVisualPhoneState.VIVI_PROFILE,
    TicketVisualPhoneState.VIVI_OTHER_TAB,
    TicketVisualPhoneState.UNKNOWN ->
      if (mode != TicketViviNavigationMode.EXACT && ticketViviLogoutBottomRouteObservation(observation)) {
        target(TicketViviNavigationKind.TICKETS_TAB, observation.ticketsTabBounds)
      } else null
    TicketVisualPhoneState.TICKETS_TIME_EMPTY ->
      if (mode != TicketViviNavigationMode.EXACT) {
        target(TicketViviNavigationKind.SINGLE_USE_TAB,
          observation.singleUseTicketsNavigationBoundsFor(TicketVisualActionTarget.REDETECT_LATEST))
      } else null
    TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY ->
      target(TicketViviNavigationKind.TIME_TAB,
        observation.timeTicketsNavigationBoundsFor(TicketVisualActionTarget.REDETECT_LATEST))
    TicketVisualPhoneState.TICKET_LIST -> {
      val activated = mode != TicketViviNavigationMode.LATEST &&
        original?.state == TicketVisualPhoneState.ACTIVATED_DETAIL
      if (!activated && mode != TicketViviNavigationMode.LATEST &&
        original?.state != TicketVisualPhoneState.UNACTIVATED_DETAIL) return null
      val card = if (activated) observation.uniqueActivatedDetailCard()
        else observation.latestRegistrationCard()
      if (card == null || mode == TicketViviNavigationMode.LATEST && card.anchor.isBlank()) return null
      val bounds = if (activated) card.activatedDetailBounds else card.registrationBounds
      bounds?.let { TicketViviNavigationTarget(
        if (activated) TicketViviNavigationKind.ACTIVATED_DETAIL else TicketViviNavigationKind.UNUSED_DETAIL,
        it, card.anchor
      ) }
    }
    else -> null
  }
}

internal fun ticketViviReauthOriginalTargetVisuallyAbsent(
  target: TicketViviReauthReturnTarget,
  observation: TicketVisualActionObservation
): Boolean = observation.state == TicketVisualPhoneState.TICKET_LIST && when (target.state) {
  TicketVisualPhoneState.UNACTIVATED_DETAIL ->
    observation.cards.none { it.latest && it.registrationBounds != null }
  TicketVisualPhoneState.ACTIVATED_DETAIL -> observation.cards.none { it.activatedDetailBounds != null }
  else -> false
}

internal enum class TicketViviReauthRestoreTransitionStatus { WAITING, PROVED, REJECTED }

/** A dispatched target accepts only its fresh successor; waiting never authorizes another tap. */
internal fun ticketViviNavigationTransition(
  mode: TicketViviNavigationMode,
  original: TicketViviReauthReturnTarget?,
  target: TicketViviNavigationTarget,
  before: TicketVisualActionObservation,
  after: TicketVisualActionObservation?
): TicketViviReauthRestoreTransitionStatus {
  if (after == null || after.probeId <= before.probeId) {
    return TicketViviReauthRestoreTransitionStatus.WAITING
  }
  if (mode == TicketViviNavigationMode.EXACT) {
    val expectedKind = when (original?.state) {
      TicketVisualPhoneState.UNACTIVATED_DETAIL -> TicketViviNavigationKind.UNUSED_DETAIL
      TicketVisualPhoneState.ACTIVATED_DETAIL -> TicketViviNavigationKind.ACTIVATED_DETAIL
      else -> return TicketViviReauthRestoreTransitionStatus.REJECTED
    }
    val nextKind = ticketViviNavigationTarget(mode, original, after)?.kind
    val proved = when (target.kind) {
      TicketViviNavigationKind.TICKETS_TAB -> before.state == TicketVisualPhoneState.VIVI_HOME &&
        nextKind in setOf(TicketViviNavigationKind.TIME_TAB, expectedKind)
      TicketViviNavigationKind.TIME_TAB -> before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY &&
        nextKind == expectedKind
      TicketViviNavigationKind.UNUSED_DETAIL,
      TicketViviNavigationKind.ACTIVATED_DETAIL -> before.state == TicketVisualPhoneState.TICKET_LIST &&
        target.kind == expectedKind && ticketViviReauthRestoredTarget(original, after)
      else -> false
    }
    return when {
      proved -> TicketViviReauthRestoreTransitionStatus.PROVED
      nextKind == target.kind || after.state == TicketVisualPhoneState.UNKNOWN ->
        TicketViviReauthRestoreTransitionStatus.WAITING
      else -> TicketViviReauthRestoreTransitionStatus.REJECTED
    }
  }
  if (after.state == TicketVisualPhoneState.UNKNOWN ||
    after.state == before.state && after.currentAnchor == before.currentAnchor
  ) return TicketViviReauthRestoreTransitionStatus.WAITING
  if (mode == TicketViviNavigationMode.ORIGINAL && original != null &&
    ticketViviReauthRestoredTarget(original, after)
  ) return TicketViviReauthRestoreTransitionStatus.PROVED
  val proved = when (target.kind) {
    TicketViviNavigationKind.CLOSE_DETAIL -> before.state in setOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL, TicketVisualPhoneState.UNACTIVATED_DETAIL
    ) && ticketViviLogoutBottomRouteObservation(after)
    TicketViviNavigationKind.TICKETS_TAB -> ticketViviLogoutBottomRouteObservation(before) &&
      after.state in setOf(TicketVisualPhoneState.TICKET_LIST,
        TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY, TicketVisualPhoneState.TICKETS_TIME_EMPTY)
    TicketViviNavigationKind.SINGLE_USE_TAB -> before.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY &&
      after.state in setOf(TicketVisualPhoneState.TICKET_LIST, TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY)
    TicketViviNavigationKind.TIME_TAB -> before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY &&
      after.state in setOf(TicketVisualPhoneState.TICKET_LIST, TicketVisualPhoneState.TICKETS_TIME_EMPTY)
    TicketViviNavigationKind.UNUSED_DETAIL,
    TicketViviNavigationKind.ACTIVATED_DETAIL -> before.state == TicketVisualPhoneState.TICKET_LIST &&
      ticketViviReauthTerminalReadyObservation(after) &&
      (mode == TicketViviNavigationMode.ORIGINAL ||
        after.state == TicketVisualPhoneState.UNACTIVATED_DETAIL && target.selectedAnchor.isNotBlank() &&
        before.latestRegistrationCard()?.anchor == target.selectedAnchor &&
        ticketVisualObservationAfterCardSelection(after, target.selectedAnchor).currentAnchor == target.selectedAnchor)
  }
  return if (proved) TicketViviReauthRestoreTransitionStatus.PROVED
    else TicketViviReauthRestoreTransitionStatus.REJECTED
}

/**
 * A stale device-link activity can outlive an operator-side link reset. Only the safe request may
 * refresh that exact initial surface, and the refresh preserves all ViVi app data. Full reset has
 * its own destructive one-shot contract and must never inherit this extra lifecycle mutation.
 */
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
