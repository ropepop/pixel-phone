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
  val logoutInApp: Boolean = false
)

private const val VIVI_REAUTH_FULL_RESET_REQUEST_PREFIX = "vivi-full-reset-"
private const val VIVI_REAUTH_LOGOUT_LOGIN_REQUEST_PREFIX = "vivi-logout-login-"
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
      logoutInApp -> VIVI_REAUTH_LOGOUT_LOGIN_REQUEST_PREFIX
      else -> VIVI_REAUTH_LEGACY_REQUEST_PREFIX
    }
    if (!ticketViviReauthRequestIdInNamespace(requestId, requiredPrefix)) {
      return@runCatching null
    }
    TicketViviReauthRequest(requestId, credentialRevision, resetAppData, logoutInApp)
  }.getOrNull()
}

internal data class TicketViviReauthSnapshot(
  val requestId: String = "",
  val credentialRevision: String = "",
  val resetAppData: Boolean = false,
  val logoutInApp: Boolean = false,
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
): Boolean {
  if (observation == null) return false
  // A proved detail remains a detail even if stale lower-navigation pixels are visible behind it.
  // It must take the separately proved close route. Every other accepted start requires the exact
  // four-glyph side channel and rejects explicit login/blocker authority while allowing an
  // otherwise unrecognized dynamic page body.
  if (observation.state in setOf(
    TicketVisualPhoneState.ACTIVATED_DETAIL,
    TicketVisualPhoneState.UNACTIVATED_DETAIL
  )) return observation.backBounds != null
  return ticketViviLogoutBottomRouteObservation(observation)
}

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

internal fun ticketViviReauthPostSubmitSignedInState(
  state: TicketVisualPhoneState?
): Boolean = state in TICKET_VIVI_REAUTH_SIGNED_IN_STATES

internal fun ticketViviReauthPostSubmitSignedInObservation(
  observation: TicketVisualActionObservation?
): Boolean = observation != null && (
  ticketViviReauthPostSubmitSignedInState(observation.state) ||
    ticketViviLogoutBottomRouteObservation(observation)
  )

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
    "verifying_signed_in"
  )
}

internal fun retainedTicketViviReauthSnapshot(
  journal: TicketViviReauthJournal,
  request: TicketViviReauthRequest
): TicketViviReauthSnapshot? {
  if (!journal.terminal || journal.requestId != request.requestId ||
    journal.credentialRevision != request.credentialRevision ||
    journal.resetAppData != request.resetAppData ||
    journal.logoutInApp != request.logoutInApp
  ) return null
  return TicketViviReauthSnapshot(
    requestId = journal.requestId,
    credentialRevision = journal.credentialRevision,
    resetAppData = journal.resetAppData,
    logoutInApp = journal.logoutInApp,
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
