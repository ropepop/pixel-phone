package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.delay
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSelector
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode

internal interface RigasSatiksmeSemanticGateway {
  suspend fun prepareAutomation(): Boolean
  suspend fun launchApp(): Boolean
  suspend fun waitForForeground(): Boolean
  suspend fun snapshot(): List<PhoneAutomationVisibleNode>
  suspend fun click(selectors: List<PhoneAutomationSelector>, timeoutMillis: Long): Boolean
  suspend fun openFirstEditableInput(timeoutMillis: Long): Boolean
  suspend fun setTextInFirstEditableInput(text: String, timeoutMillis: Long): Boolean
  suspend fun performBack(): Boolean
}

internal class PhoneAutomationRigasSatiksmeSemanticGateway(
  private val prepareAutomation: suspend () -> Boolean,
  private val launchApp: suspend () -> Boolean,
  private val typeDigitsWithInput: (suspend (String) -> Boolean)? = null
) : RigasSatiksmeSemanticGateway {
  override suspend fun prepareAutomation(): Boolean = prepareAutomation.invoke()

  override suspend fun launchApp(): Boolean = launchApp.invoke()

  override suspend fun waitForForeground(): Boolean {
    if (PhoneAutomationServiceBridge.waitForForegroundPackage(
      TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
      timeoutMillis = 2_500L
    )) {
      return true
    }
    return PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.RIGAS_SATIKSME_PACKAGE).isNotEmpty()
  }

  override suspend fun snapshot(): List<PhoneAutomationVisibleNode> {
    return PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.RIGAS_SATIKSME_PACKAGE)
  }

  override suspend fun click(
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean {
    val clickTimeoutMillis = timeoutMillis.coerceAtMost(260L).coerceAtLeast(1L)
    val clicked = PhoneAutomationServiceBridge.clickSelectors(
      expectedPackageName = TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
      selectors = selectors,
      timeoutMillis = clickTimeoutMillis
    )
    if (clicked) {
      delay(80L)
      return true
    }
    val gestureTimeoutMillis = (timeoutMillis - clickTimeoutMillis).coerceAtLeast(180L).coerceAtMost(520L)
    val tapped = PhoneAutomationServiceBridge.tapSelectorCenter(
      expectedPackageName = TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
      selectors = selectors,
      timeoutMillis = gestureTimeoutMillis
    )
    if (tapped) {
      delay(120L)
    }
    return tapped
  }

  override suspend fun openFirstEditableInput(timeoutMillis: Long): Boolean {
    return PhoneAutomationServiceBridge.openFirstEditableInput(
      expectedPackageName = TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
      timeoutMillis = timeoutMillis
    )
  }

  override suspend fun setTextInFirstEditableInput(text: String, timeoutMillis: Long): Boolean {
    val accessibilityUpdated = PhoneAutomationServiceBridge.setTextInFirstEditableInput(
      expectedPackageName = TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
      text = text,
      timeoutMillis = timeoutMillis
    )
    if (accessibilityUpdated) {
      delay(80L)
      return true
    }
    return typeDigitsWithInput?.invoke(text) ?: false
  }

  override suspend fun performBack(): Boolean = PhoneAutomationServiceBridge.performBack()
}

internal class RigasSatiksmeSemanticDriver(
  private val gateway: RigasSatiksmeSemanticGateway,
  private val maxStateAttempts: Int = DEFAULT_MAX_STATE_ATTEMPTS,
  private val stateSettleMillis: Long = DEFAULT_STATE_SETTLE_MILLIS
) {
  suspend fun run(cleanDigits: String): RigasSatiksmeMonthlyTicketFlowResult {
    if (!gateway.prepareAutomation()) {
      return failure("rs_phone_automation_unavailable", details = "accessibility_unavailable")
    }
    if (!gateway.launchApp()) {
      return failure("rs_app_launch_failed")
    }
    if (!gateway.waitForForeground()) {
      return failure("rs_app_foreground_failed")
    }

    var lastState = RigasSatiksmeSemanticState.UNKNOWN
    var lastSnapshot = emptyList<PhoneAutomationVisibleNode>()
    var unknownResets = 0
    var consecutiveUnknownStates = 0
    var registerTripAttempts = 0
    var manualCodeButtonAttempts = 0
    var manualEntryAttempts = 0
    var tripRegisteredAttempts = 0
    var postSubmitManualCodeButtonObservations = 0
    var postSubmitStaleControlObservations = 0
    var staleControlResetAttempts = 0
    var emptySnapshotRelaunches = 0
    var codeSubmitted = false
    var tripRegisteredSeen = false
    val observedStates = mutableListOf<String>()
    repeat(maxStateAttempts.coerceAtLeast(1)) {
      val snapshot = gateway.snapshot()
      lastSnapshot = snapshot
      val state = classify(snapshot, cleanDigits)
      lastState = state
      observedStates += state.name.lowercase()
      if (state == RigasSatiksmeSemanticState.UNKNOWN) {
        consecutiveUnknownStates += 1
      } else {
        consecutiveUnknownStates = 0
      }
      when (state) {
        RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING -> {
          if (!codeSubmitted && !tripRegisteredSeen) {
            staleControlResetAttempts += 1
            if (staleControlResetAttempts <= MAX_STALE_CONTROL_RESET_ATTEMPTS) {
              gateway.performBack()
              delay(POST_STALE_CONTROL_BACK_SETTLE_MILLIS)
              return@repeat
            }
            return failure(
              "rs_stale_control_reset_failed",
              snapshot,
              "initial_matching_control_reset_failed states=${observedStates.joinToString(",")}"
            )
          }
          return RigasSatiksmeMonthlyTicketFlowResult(
            ok = true,
            reason = "generated",
            hierarchy = snapshot.toSemanticHierarchy(),
            details = "semantic_control_ticket states=${observedStates.joinToString(",")}"
          )
        }
        RigasSatiksmeSemanticState.TICKET_CONTROL_STALE -> {
          if (codeSubmitted || tripRegisteredSeen) {
            postSubmitStaleControlObservations += 1
            if (postSubmitStaleControlObservations <= MAX_POST_SUBMIT_STALE_CONTROL_RECHECKS) {
              delay(POST_SUBMIT_STALE_CONTROL_RECHECK_MILLIS)
              return@repeat
            }
            return failure(
              "rs_monthly_ticket_stale_code",
              snapshot,
              "final_ticket_proof_failed states=${observedStates.joinToString(",")}"
            )
          }
          staleControlResetAttempts += 1
          if (staleControlResetAttempts == 1) {
            gateway.performBack()
            delay(POST_STALE_CONTROL_BACK_SETTLE_MILLIS)
          } else if (staleControlResetAttempts <= MAX_STALE_CONTROL_RESET_ATTEMPTS) {
            delay(POST_STALE_CONTROL_RECHECK_MILLIS)
          } else {
            return failure(
              "rs_stale_control_reset_failed",
              snapshot,
              "stale_control_reset_failed states=${observedStates.joinToString(",")}"
            )
          }
        }
        RigasSatiksmeSemanticState.TICKET_LIST_READY -> {
          if (!gateway.click(ticketListSelectors, CLICK_TIMEOUT_MILLIS)) {
            delay(POST_NAVIGATION_SETTLE_MILLIS)
            return@repeat
          }
        }
        RigasSatiksmeSemanticState.HOME_READY,
        RigasSatiksmeSemanticState.REGISTER_TRIP_READY -> {
          registerTripAttempts += 1
          if (registerTripAttempts > 4) {
            return failure("rs_register_trip_missing", snapshot, observedStates.joinToString(","))
          }
          if (!gateway.click(registerTripSelectors, CLICK_TIMEOUT_MILLIS)) {
            delay(POST_NAVIGATION_SETTLE_MILLIS)
            return@repeat
          }
          delay(POST_NAVIGATION_SETTLE_MILLIS)
        }
        RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY -> {
          if (codeSubmitted) {
            postSubmitManualCodeButtonObservations += 1
            return failure(
              "code_rejected_by_rs",
              snapshot,
              "post_submit_returned_manual_code observations=$postSubmitManualCodeButtonObservations states=${observedStates.joinToString(",")}"
            )
          }
          manualCodeButtonAttempts += 1
          if (manualCodeButtonAttempts > 3) {
            return failure("rs_manual_code_button_missing", snapshot, observedStates.joinToString(","))
          }
          if (!gateway.click(manualCodeSelectors, CLICK_TIMEOUT_MILLIS)) {
            delay(POST_NAVIGATION_SETTLE_MILLIS)
            return@repeat
          }
          delay(POST_NAVIGATION_SETTLE_MILLIS)
        }
        RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY -> {
          manualEntryAttempts += 1
          if (manualEntryAttempts > 3) {
            return failure("rs_manual_code_submit_stalled", snapshot, observedStates.joinToString(","))
          }
          if (!gateway.openFirstEditableInput(INPUT_TIMEOUT_MILLIS)) {
            return failure("rs_manual_code_field_missing", snapshot, observedStates.joinToString(","))
          }
          if (!gateway.setTextInFirstEditableInput(cleanDigits, INPUT_TIMEOUT_MILLIS)) {
            return failure("rs_manual_code_field_missing", snapshot, observedStates.joinToString(","))
          }
          if (!gateway.click(confirmSelectors, CLICK_TIMEOUT_MILLIS)) {
            return failure("rs_confirm_button_missing", snapshot, observedStates.joinToString(","))
          }
          codeSubmitted = true
          delay(POST_SUBMIT_SETTLE_MILLIS)
        }
        RigasSatiksmeSemanticState.TRIP_REGISTERED -> {
          tripRegisteredSeen = true
          tripRegisteredAttempts += 1
          if (tripRegisteredAttempts > 4) {
            return failure("rs_trip_registered_modal_stuck", snapshot, observedStates.joinToString(","))
          }
          gateway.click(okSelectors, CLICK_TIMEOUT_MILLIS)
          if (tripRegisteredAttempts >= 2) {
            gateway.performBack()
          }
          gateway.click(ticketForControlSelectors, CLICK_TIMEOUT_MILLIS)
        }
        RigasSatiksmeSemanticState.WRONG_CODE -> {
          return failure("code_rejected_by_rs", snapshot)
        }
        RigasSatiksmeSemanticState.NO_MONTHLY_TICKET -> {
          return failure("rs_monthly_ticket_missing", snapshot)
        }
        RigasSatiksmeSemanticState.AUTH_BLOCKED -> {
          return failure("rs_auth_blocked", snapshot)
        }
        RigasSatiksmeSemanticState.UNKNOWN -> {
          if (snapshot.isEmpty()) {
            if (codeSubmitted || tripRegisteredSeen) {
              delay(POST_NAVIGATION_SETTLE_MILLIS)
              return@repeat
            }
            emptySnapshotRelaunches += 1
            if (emptySnapshotRelaunches > MAX_EMPTY_SNAPSHOT_RELAUNCHES) {
              return failure(
                "rs_app_foreground_failed",
                snapshot,
                "empty_rs_hierarchy states=${observedStates.joinToString(",")}"
              )
            }
            gateway.launchApp()
            gateway.waitForForeground()
            delay(POST_NAVIGATION_SETTLE_MILLIS)
            return@repeat
          }
          if (unknownResets == 0 && consecutiveUnknownStates >= 2) {
            unknownResets += 1
            gateway.performBack()
          } else if (unknownResets > 0 && consecutiveUnknownStates >= 5) {
            return failure(
              "rs_monthly_ticket_unknown_state",
              snapshot,
              "semantic_reset_failed states=${observedStates.joinToString(",")}"
            )
          }
        }
      }
      delay(stateSettleMillis)
    }
    return failure(
      reason = if (lastState == RigasSatiksmeSemanticState.UNKNOWN) {
        "rs_monthly_ticket_unknown_state"
      } else {
        "rs_monthly_ticket_state_timeout"
      },
      snapshot = lastSnapshot,
      details = "last=${lastState.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
  }

  private fun failure(
    reason: String,
    snapshot: List<PhoneAutomationVisibleNode> = emptyList(),
    details: String = ""
  ): RigasSatiksmeMonthlyTicketFlowResult {
    return RigasSatiksmeMonthlyTicketFlowResult(
      ok = false,
      reason = reason,
      hierarchy = snapshot.toSemanticHierarchy(),
      details = details
    )
  }

  companion object {
    private const val DEFAULT_MAX_STATE_ATTEMPTS = 28
    private const val DEFAULT_STATE_SETTLE_MILLIS = 120L
    private const val CLICK_TIMEOUT_MILLIS = 550L
    private const val INPUT_TIMEOUT_MILLIS = 650L
    private const val POST_BACK_SETTLE_MILLIS = 520L
    private const val POST_STALE_CONTROL_BACK_SETTLE_MILLIS = 450L
    private const val POST_STALE_CONTROL_RECHECK_MILLIS = 520L
    private const val POST_SUBMIT_STALE_CONTROL_RECHECK_MILLIS = 150L
    private const val POST_NAVIGATION_SETTLE_MILLIS = 120L
    private const val POST_SUBMIT_SETTLE_MILLIS = 120L
    private const val MAX_STALE_CONTROL_RESET_ATTEMPTS = 5
    private const val MAX_POST_SUBMIT_STALE_CONTROL_RECHECKS = 2
    private const val MAX_EMPTY_SNAPSHOT_RELAUNCHES = 2

    private val registerTripSelectors = semanticSelectors(
      "REGISTER A TRIP",
      "Register a trip",
      "Reģistrēt braucienu"
    )
    private val ticketListSelectors = semanticSelectors(
      "Tickets",
      "My tickets",
      "Biļetes",
      "Manas biļetes"
    )
    private val manualCodeSelectors = semanticSelectors(
      "ENTER THE CODE MANUALLY",
      "ENTER CODE MANUALLY",
      "Enter code manually",
      "Ievadīt kodu manuāli"
    )
    private val confirmSelectors = semanticSelectors(
      "CONFIRM",
      "Confirm",
      "Apstiprināt",
      "APSTIPRINĀT",
      "REGISTER",
      "Register",
      "SUBMIT",
      "Submit",
      "CONTINUE",
      "Continue",
      "Turpināt"
    )
    private val okSelectors = listOf(
      PhoneAutomationSelector(text = "OK"),
      PhoneAutomationSelector(textContains = "OK"),
      PhoneAutomationSelector(contentDescription = "OK"),
      PhoneAutomationSelector(contentDescriptionContains = "OK"),
      PhoneAutomationSelector(textContains = "Labi")
    )
    private val ticketForControlSelectors = semanticSelectors(
      "TICKET FOR CONTROL",
      "Ticket for control",
      "Present a ticket for control",
      "Kontrolei"
    )

    private fun semanticSelectors(vararg labels: String): List<PhoneAutomationSelector> {
      return labels.flatMap { label ->
        listOf(
          PhoneAutomationSelector(textContains = label),
          PhoneAutomationSelector(contentDescriptionContains = label)
        )
      }
    }

    fun classify(
      nodes: List<PhoneAutomationVisibleNode>,
      cleanDigits: String
    ): RigasSatiksmeSemanticState {
      val text = nodes.joinToString("\n") { node ->
        listOf(node.text, node.contentDescription, node.hint, node.resourceId)
          .filter { it.isNotBlank() }
          .joinToString(" ")
      }
      fun has(value: String): Boolean = text.contains(value, ignoreCase = true)
      fun hasAny(vararg values: String): Boolean = values.any { has(it) }
      val hasEditable = nodes.any { it.editable || it.className.contains("EditText", ignoreCase = true) }
      val hasMonthlyMarker = hasAny(
        "1 month",
        "1 mēnes",
        "monthly",
        "month ticket",
        "30 dienu biļete",
        "30 dienu bilete",
        "30 day ticket",
        "30-day ticket"
      )
      val hasQr = hasAny("qr code", "qr", "KONTROLES KODS", "kontroles kods")
      val hasControl = hasAny(
        "TICKET FOR CONTROL",
        "Ticket for control",
        "Present a ticket for control",
        "KONTROLES KODS",
        "Kontroles kods",
        "Kontrolei"
      )
      val hasConfirm = hasAny("CONFIRM", "OK", "Labi")
      val hasCancel = hasAny("Cancel", "Atcelt")

      return when {
        hasControl && hasQr && has(cleanDigits) && hasMonthlyMarker -> RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING
        hasControl && hasQr && hasMonthlyMarker -> RigasSatiksmeSemanticState.TICKET_CONTROL_STALE
        hasAny("wrong code", "incorrect code", "invalid code", "nepareizs kods", "nederīgs kods", "nederigs kods") -> {
          RigasSatiksmeSemanticState.WRONG_CODE
        }
        hasAny("no active tickets", "no tickets", "empty ticket list", "nav aktīvu biļešu", "nav aktivu bilesu", "nav biļešu") -> {
          RigasSatiksmeSemanticState.NO_MONTHLY_TICKET
        }
        hasAny("sign in", "log in", "authentication", "session expired", "pieslēgties", "pierakstīties", "autentifik", "sesija beigusies") -> {
          RigasSatiksmeSemanticState.AUTH_BLOCKED
        }
        has("trip is registered") || (has("registered") && hasConfirm) || (has("brauciens reģistrēts") && hasConfirm) -> {
          RigasSatiksmeSemanticState.TRIP_REGISTERED
        }
        hasEditable || hasAny("Control code", "Ievadi kontroles kodu") || (hasAny("Kods", "kontroles kods") && (hasConfirm || hasCancel)) -> {
          RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY
        }
        hasAny(
          "ENTER THE CODE MANUALLY",
          "ENTER CODE MANUALLY",
          "Enter code manually",
          "Ievadīt kodu manuāli",
          "Ievadit kodu manuali",
          "Ievadīt kodu"
        ) -> {
          RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY
        }
        hasAny("REGISTER A TRIP", "Register a trip", "Reģistrēt braucienu", "Registret braucienu") -> {
          RigasSatiksmeSemanticState.REGISTER_TRIP_READY
        }
        hasAny("Public transport", "Sabiedriskais transports") && hasMonthlyMarker -> RigasSatiksmeSemanticState.HOME_READY
        hasAny("Tickets", "Biļetes", "Biletes", "Manas biļetes") && hasMonthlyMarker -> RigasSatiksmeSemanticState.TICKET_LIST_READY
        else -> RigasSatiksmeSemanticState.UNKNOWN
      }
    }
  }
}

internal enum class RigasSatiksmeSemanticState {
  HOME_READY,
  TICKET_LIST_READY,
  REGISTER_TRIP_READY,
  MANUAL_CODE_BUTTON_READY,
  MANUAL_CODE_ENTRY,
  TRIP_REGISTERED,
  TICKET_CONTROL_MATCHING,
  TICKET_CONTROL_STALE,
  WRONG_CODE,
  NO_MONTHLY_TICKET,
  AUTH_BLOCKED,
  UNKNOWN
}

internal fun List<PhoneAutomationVisibleNode>.toSemanticHierarchy(): String {
  return joinToString(separator = "\n") { node ->
    buildString {
      append("<node")
      if (node.text.isNotBlank()) append(" text=\"").append(node.text.escapeHierarchyAttribute()).append("\"")
      if (node.contentDescription.isNotBlank()) {
        append(" content-desc=\"").append(node.contentDescription.escapeHierarchyAttribute()).append("\"")
      }
      if (node.resourceId.isNotBlank()) append(" resource-id=\"").append(node.resourceId.escapeHierarchyAttribute()).append("\"")
      if (node.hint.isNotBlank()) append(" hint=\"").append(node.hint.escapeHierarchyAttribute()).append("\"")
      if (node.editable) append(" editable=\"true\"")
      append(" />")
    }
  }
}

private fun String.escapeHierarchyAttribute(): String {
  return replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
}
