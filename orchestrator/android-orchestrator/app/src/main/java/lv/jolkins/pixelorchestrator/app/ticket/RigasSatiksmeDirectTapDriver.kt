package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.delay
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode

internal interface RigasSatiksmeDirectTapGateway {
  suspend fun launchApp(): Boolean
  suspend fun waitForForeground(): Boolean
  suspend fun snapshot(reason: String): List<PhoneAutomationVisibleNode>
  suspend fun resetApp(reason: String): Boolean
  suspend fun tapNodeCenter(node: PhoneAutomationVisibleNode, reason: String): Boolean = false
  suspend fun tapRatio(x: Double, y: Double, reason: String): Boolean
  suspend fun enterManualCode(cleanDigits: String, fieldXRatio: Double, fieldYRatio: Double): Boolean
  suspend fun pressBack(reason: String): Boolean
  fun recordPhase(name: String, details: String) = Unit
}

internal class RigasSatiksmeDirectTapDriver(
  private val gateway: RigasSatiksmeDirectTapGateway
) {
  suspend fun run(cleanDigits: String): RigasSatiksmeMonthlyTicketFlowResult {
    if (!gateway.launchApp()) {
      return failure("rs_app_launch_failed")
    }
    if (!gateway.waitForForeground()) {
      return failure("rs_app_foreground_failed")
    }

    val observedStates = mutableListOf<String>()
    var initial = observe("initial", cleanDigits, observedStates)
    if (initial.state == RigasSatiksmeSemanticState.UNKNOWN) {
      gateway.recordPhase("rs_direct_unknown_recovery_started", "states=${observedStates.joinToString(",")}")
      if (!gateway.resetApp("rs_direct_initial_unknown_reset")) {
        gateway.recordPhase("rs_direct_attention_required", "reason=initial_unknown_reset_failed states=${observedStates.joinToString(",")}")
        return failure(
          "rs_app_attention_required",
          initial.snapshot,
          "direct_initial_unknown_reset_failed states=${observedStates.joinToString(",")}"
        )
      }
      delay(RESET_SETTLE_MILLIS)
      initial = observe("initial_unknown_reset", cleanDigits, observedStates)
      gateway.recordPhase(
        "rs_direct_unknown_recovery_finished",
        "state=${initial.state.name.lowercase()} states=${observedStates.joinToString(",")}"
      )
      if (initial.state == RigasSatiksmeSemanticState.UNKNOWN) {
        gateway.recordPhase("rs_direct_attention_required", "reason=initial_unknown_after_reset states=${observedStates.joinToString(",")}")
        return failure(
          "rs_app_attention_required",
          initial.snapshot,
          "direct_initial_unknown_after_reset states=${observedStates.joinToString(",")}"
        )
      }
    }
    gateway.recordPhase(
      "rs_direct_known_state_ready",
      "state=${initial.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    var current = initial
    when (current.state) {
      RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING,
      RigasSatiksmeSemanticState.TICKET_CONTROL_STALE -> {
        current = clearInitialControlTicket(current, cleanDigits, observedStates)
        if (!isSafeAfterInitialControl(current.state)) {
          return attentionFailure(
            current,
            observedStates,
            "initial_control_not_cleared"
          )
        }
      }
      RigasSatiksmeSemanticState.TRIP_REGISTERED -> {
        tapPreferred(
          current.snapshot,
          OK_LABELS,
          TRIP_REGISTERED_OK_X,
          TRIP_REGISTERED_OK_Y,
          "rs_direct_initial_trip_ok"
        )
        delay(RESET_SETTLE_MILLIS)
        gateway.pressBack("rs_direct_initial_trip_back")
        delay(RESET_SETTLE_MILLIS)
        current = observe("initial_trip_registered_clear", cleanDigits, observedStates)
        if (current.state == RigasSatiksmeSemanticState.TRIP_REGISTERED ||
          current.state == RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING ||
          current.state == RigasSatiksmeSemanticState.TICKET_CONTROL_STALE ||
          current.state == RigasSatiksmeSemanticState.UNKNOWN
        ) {
          return attentionFailure(
            current,
            observedStates,
            "initial_trip_registered_not_cleared"
          )
        }
      }
      RigasSatiksmeSemanticState.WRONG_CODE -> {
        gateway.pressBack("rs_direct_initial_wrong_code_back")
        delay(RESET_SETTLE_MILLIS)
        current = observe("initial_wrong_code_back", cleanDigits, observedStates)
        if (current.state == RigasSatiksmeSemanticState.WRONG_CODE ||
          current.state == RigasSatiksmeSemanticState.UNKNOWN
        ) {
          return attentionFailure(
            current,
            observedStates,
            "initial_wrong_code_not_cleared"
          )
        }
      }
      RigasSatiksmeSemanticState.NO_MONTHLY_TICKET -> return failure("rs_monthly_ticket_missing", initial.snapshot)
      RigasSatiksmeSemanticState.AUTH_BLOCKED -> return failure("rs_auth_blocked", initial.snapshot)
      else -> Unit
    }

    current = ensureManualEntryReady(current, cleanDigits, observedStates)
      ?: return attentionFailure(
        current,
        observedStates,
        "manual_entry_not_ready"
      )
    gateway.recordPhase(
      "rs_direct_manual_popup_ready",
      "state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    val codeEntry = enterAndVerifyManualCode(current, cleanDigits, observedStates)
    if (codeEntry.failure != null) {
      return codeEntry.failure
    }
    val entered = codeEntry.entered ?: return failure(
      "rs_manual_code_entry_unverified",
      current.snapshot,
      "direct_digits_unverified states=${observedStates.joinToString(",")}"
    )
    gateway.recordPhase(
      "rs_direct_digits_verified",
      "state=${entered.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    tapPreferred(
      entered.snapshot,
      CONFIRM_LABELS,
      CONFIRM_CODE_X,
      CONFIRM_CODE_Y,
      "rs_direct_confirm_code"
    )
    gateway.recordPhase(
      "rs_direct_ok_tapped",
      "states=${observedStates.joinToString(",")}"
    )
    delay(POST_SUBMIT_SETTLE_MILLIS)

    var proof = observe("proof_1", cleanDigits, observedStates)
    gateway.recordPhase(
      "rs_direct_final_proof",
      "state=${proof.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    if (proof.state == RigasSatiksmeSemanticState.TRIP_REGISTERED) {
      tapPreferred(
        proof.snapshot,
        OK_LABELS,
        TRIP_REGISTERED_OK_X,
        TRIP_REGISTERED_OK_Y,
        "rs_direct_trip_registered_ok"
      )
      delay(MODAL_TO_CONTROL_SETTLE_MILLIS)
      proof = observe("proof_trip_registered_control", cleanDigits, observedStates)
      gateway.recordPhase(
        "rs_direct_final_proof",
        "state=${proof.state.name.lowercase()} states=${observedStates.joinToString(",")}"
      )
    }

    when (proof.state) {
      RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING -> {
        return RigasSatiksmeMonthlyTicketFlowResult(
          ok = true,
          reason = "generated",
          hierarchy = proof.snapshot.toSemanticHierarchy(),
          details = "direct_tap states=${observedStates.joinToString(",")}"
        )
      }
      RigasSatiksmeSemanticState.TICKET_CONTROL_STALE -> {
        return failure(
          "rs_monthly_ticket_stale_code",
          proof.snapshot,
          "direct_final_ticket_mismatch_after_verified_submit states=${observedStates.joinToString(",")}"
        )
      }
      RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY,
      RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY,
      RigasSatiksmeSemanticState.REGISTER_TRIP_READY,
      RigasSatiksmeSemanticState.HOME_READY,
      RigasSatiksmeSemanticState.TICKET_LIST_READY,
      RigasSatiksmeSemanticState.WRONG_CODE -> {
        return failure(
          "code_rejected_by_rs",
          proof.snapshot,
          "direct_post_submit_entry_or_wrong_code states=${observedStates.joinToString(",")}"
        )
      }
      RigasSatiksmeSemanticState.NO_MONTHLY_TICKET -> return failure("rs_monthly_ticket_missing", proof.snapshot)
      RigasSatiksmeSemanticState.AUTH_BLOCKED -> return failure("rs_auth_blocked", proof.snapshot)
      else -> {
        delay(UNKNOWN_RECHECK_MILLIS)
        val recheck = observe("proof_unknown_recheck", cleanDigits, observedStates)
        return when (recheck.state) {
          RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING -> RigasSatiksmeMonthlyTicketFlowResult(
            ok = true,
            reason = "generated",
            hierarchy = recheck.snapshot.toSemanticHierarchy(),
            details = "direct_tap states=${observedStates.joinToString(",")}"
          )
          RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY,
          RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY,
          RigasSatiksmeSemanticState.REGISTER_TRIP_READY,
          RigasSatiksmeSemanticState.HOME_READY,
          RigasSatiksmeSemanticState.TICKET_LIST_READY,
          RigasSatiksmeSemanticState.WRONG_CODE -> failure(
            "code_rejected_by_rs",
            recheck.snapshot,
            "direct_unknown_recheck_entry states=${observedStates.joinToString(",")}"
          )
          else -> failure(
            "rs_app_attention_required",
            recheck.snapshot,
            "direct_attention_required states=${observedStates.joinToString(",")}"
          ).also {
            gateway.recordPhase("rs_direct_attention_required", "reason=post_submit_unknown states=${observedStates.joinToString(",")}")
          }
        }
      }
    }
  }

  private suspend fun observe(
    reason: String,
    cleanDigits: String,
    observedStates: MutableList<String>
  ): Observation {
    val snapshot = gateway.snapshot("rs_direct_$reason")
    val state = RigasSatiksmeSemanticDriver.classify(snapshot, cleanDigits)
    observedStates += state.name.lowercase()
    return Observation(state, snapshot)
  }

  private suspend fun clearInitialControlTicket(
    start: Observation,
    cleanDigits: String,
    observedStates: MutableList<String>
  ): Observation {
    var current = start
    gateway.recordPhase(
      "rs_direct_stale_screen_clear_started",
      "state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )

    current = tryInitialControlExit(current, cleanDigits, observedStates, "initial_control_exit")
    if (isSafeAfterInitialControl(current.state)) {
      gateway.recordPhase(
        "rs_direct_stale_screen_clear_finished",
        "method=visible_exit state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
      )
      return current
    }

    gateway.pressBack("rs_direct_initial_control_back")
    delay(RESET_SETTLE_MILLIS)
    current = observe("initial_control_back", cleanDigits, observedStates)
    gateway.recordPhase(
      "rs_direct_stale_screen_clear_rechecked",
      "method=back state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    if (isSafeAfterInitialControl(current.state)) {
      gateway.recordPhase(
        "rs_direct_stale_screen_clear_finished",
        "method=back state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
      )
      return current
    }

    gateway.recordPhase(
      "rs_direct_stale_screen_reset_started",
      "state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    if (!gateway.resetApp("rs_direct_initial_control_reset")) {
      gateway.recordPhase(
        "rs_direct_stale_screen_reset_finished",
        "ok=false state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
      )
      return current
    }
    delay(RESET_SETTLE_MILLIS)
    current = observe("initial_control_reset", cleanDigits, observedStates)
    gateway.recordPhase(
      "rs_direct_stale_screen_reset_finished",
      "ok=true state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    if (isSafeAfterInitialControl(current.state)) {
      return current
    }

    current = tryInitialControlExit(current, cleanDigits, observedStates, "initial_control_exit_after_reset")
    gateway.recordPhase(
      "rs_direct_stale_screen_clear_finished",
      "method=post_reset_visible_exit state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    return current
  }

  private suspend fun tryInitialControlExit(
    current: Observation,
    cleanDigits: String,
    observedStates: MutableList<String>,
    step: String
  ): Observation {
    if (!isInitialControlTicket(current.state)) {
      return current
    }
    val exitNode = findInitialControlExitNode(current.snapshot)
    if (exitNode == null) {
      gateway.recordPhase(
        "rs_direct_stale_screen_exit_skipped",
        "step=$step reason=no_visible_exit state=${current.state.name.lowercase()}"
      )
      return current
    }
    val tapped = gateway.tapNodeCenter(exitNode, "rs_direct_initial_control_exit")
    gateway.recordPhase(
      "rs_direct_stale_screen_exit_tapped",
      "step=$step ok=$tapped source=node bounds=${exitNode.bounds} label=${exitNode.searchText().take(40)}"
    )
    if (!tapped) {
      return current
    }
    delay(RESET_SETTLE_MILLIS)
    return observe("${step}_tapped", cleanDigits, observedStates)
  }

  private suspend fun ensureManualEntryReady(
    start: Observation,
    cleanDigits: String,
    observedStates: MutableList<String>
  ): Observation? {
    var current = start
    when (current.state) {
      RigasSatiksmeSemanticState.TICKET_LIST_READY,
      RigasSatiksmeSemanticState.HOME_READY,
      RigasSatiksmeSemanticState.REGISTER_TRIP_READY -> {
        gateway.recordPhase(
          "rs_direct_safe_entry_ready",
          "state=${current.state.name.lowercase()} states=${observedStates.joinToString(",")}"
        )
        tapPreferred(
          current.snapshot,
          REGISTER_LABELS,
          REGISTER_TRIP_X,
          REGISTER_TRIP_Y,
          "rs_direct_register_trip"
        )
        delay(NAVIGATION_SETTLE_MILLIS)
        current = observe("register_trip_opened", cleanDigits, observedStates)
      }
      else -> Unit
    }

    if (current.state == RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY) {
      tapPreferred(
        current.snapshot,
        MANUAL_CODE_LABELS,
        MANUAL_CODE_CHOICE_X,
        MANUAL_CODE_CHOICE_Y,
        "rs_direct_manual_code_choice"
      )
      delay(NAVIGATION_SETTLE_MILLIS)
      current = observe("manual_code_choice_opened", cleanDigits, observedStates)
    }

    return if (current.state == RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY) {
      current
    } else {
      null
    }
  }

  private suspend fun enterAndVerifyManualCode(
    start: Observation,
    cleanDigits: String,
    observedStates: MutableList<String>
  ): ManualCodeEntryResult {
    var latest = start
    for (attempt in 1..MANUAL_CODE_ENTRY_MAX_ATTEMPTS) {
      if (!gateway.enterManualCode(cleanDigits, MANUAL_CODE_FIELD_X, MANUAL_CODE_FIELD_Y)) {
        return ManualCodeEntryResult(
          failure = failure(
            "rs_manual_code_field_missing",
            latest.snapshot,
            "direct_text_entry_failed attempt=$attempt states=${observedStates.joinToString(",")}"
          )
        )
      }
      gateway.recordPhase(
        "rs_direct_manual_code_entry_attempt",
        "attempt=$attempt states=${observedStates.joinToString(",")}"
      )
      delay(TEXT_ENTRY_SETTLE_MILLIS)
      latest = observe(
        if (attempt == 1) "manual_code_entered" else "manual_code_entered_retry_$attempt",
        cleanDigits,
        observedStates
      )
      if (latest.state == RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY &&
        latest.snapshot.containsDigits(cleanDigits)
      ) {
        return ManualCodeEntryResult(entered = latest)
      }
      if (latest.state != RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY) {
        return ManualCodeEntryResult(
          failure = attentionFailure(
            latest,
            observedStates,
            "manual_entry_lost_before_digit_proof"
          )
        )
      }
      if (attempt < MANUAL_CODE_ENTRY_MAX_ATTEMPTS) {
        gateway.recordPhase(
          "rs_direct_manual_code_entry_retry",
          "attempt=$attempt state=${latest.state.name.lowercase()} states=${observedStates.joinToString(",")}"
        )
      }
    }
    return ManualCodeEntryResult(
      failure = failure(
        "rs_manual_code_entry_unverified",
        latest.snapshot,
        "direct_digits_unverified states=${observedStates.joinToString(",")}"
      )
    )
  }

  private suspend fun tapPreferred(
    snapshot: List<PhoneAutomationVisibleNode>,
    labels: List<String>,
    fallbackX: Double,
    fallbackY: Double,
    reason: String
  ): Boolean {
    val node = snapshot.firstOrNull { node ->
      node.bounds.isNotBlank() && labels.any { label ->
        node.searchText().contains(label, ignoreCase = true)
      }
    }
    if (node != null && gateway.tapNodeCenter(node, reason)) {
      return true
    }
    return gateway.tapRatio(fallbackX, fallbackY, reason)
  }

  private fun findInitialControlExitNode(snapshot: List<PhoneAutomationVisibleNode>): PhoneAutomationVisibleNode? {
    val labeled = snapshot.firstOrNull { node ->
      node.bounds.isNotBlank() && CONTROL_EXIT_LABELS.any { label ->
        node.searchText().contains(label, ignoreCase = true)
      }
    }
    if (labeled != null) return labeled

    val parsed = snapshot.mapNotNull { node -> node.boundsOrNull()?.let { bounds -> node to bounds } }
    val maxRight = parsed.maxOfOrNull { it.second.right } ?: return null
    val maxBottom = parsed.maxOfOrNull { it.second.bottom } ?: return null
    return parsed
      .filter { (node, bounds) ->
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        val label = node.searchText()
        (node.clickable || node.enabled) &&
          width in CONTROL_EXIT_MIN_SIZE..CONTROL_EXIT_MAX_SIZE &&
          height in CONTROL_EXIT_MIN_SIZE..CONTROL_EXIT_MAX_SIZE &&
          bounds.top <= (maxBottom * CONTROL_EXIT_TOP_FRACTION).toInt() &&
          (
            bounds.right >= (maxRight * CONTROL_EXIT_RIGHT_FRACTION).toInt() ||
              bounds.left <= (maxRight * CONTROL_EXIT_LEFT_FRACTION).toInt()
            ) &&
          CONTROL_EXIT_FORBIDDEN_LABELS.none { forbidden -> label.contains(forbidden, ignoreCase = true) }
      }
      .maxWithOrNull(
        compareBy<Pair<PhoneAutomationVisibleNode, NodeBounds>> { it.second.right }
          .thenByDescending { it.second.left }
      )
      ?.first
  }

  private fun List<PhoneAutomationVisibleNode>.containsDigits(cleanDigits: String): Boolean {
    return any { node -> node.searchText().contains(cleanDigits) }
  }

  private fun PhoneAutomationVisibleNode.searchText(): String {
    return listOf(text, contentDescription, hint, resourceId)
      .filter { it.isNotBlank() }
      .joinToString(" ")
  }

  private fun PhoneAutomationVisibleNode.boundsOrNull(): NodeBounds? {
    val match = BOUNDS_REGEX.find(bounds) ?: return null
    val left = match.groupValues[1].toIntOrNull() ?: return null
    val top = match.groupValues[2].toIntOrNull() ?: return null
    val right = match.groupValues[3].toIntOrNull() ?: return null
    val bottom = match.groupValues[4].toIntOrNull() ?: return null
    if (right <= left || bottom <= top) return null
    return NodeBounds(left, top, right, bottom)
  }

  private fun isInitialControlTicket(state: RigasSatiksmeSemanticState): Boolean {
    return state == RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING ||
      state == RigasSatiksmeSemanticState.TICKET_CONTROL_STALE
  }

  private fun isSafeAfterInitialControl(state: RigasSatiksmeSemanticState): Boolean {
    return state == RigasSatiksmeSemanticState.HOME_READY ||
      state == RigasSatiksmeSemanticState.TICKET_LIST_READY ||
      state == RigasSatiksmeSemanticState.REGISTER_TRIP_READY ||
      state == RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY ||
      state == RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY
  }

  private fun attentionFailure(
    observation: Observation,
    observedStates: List<String>,
    detailReason: String
  ): RigasSatiksmeMonthlyTicketFlowResult {
    gateway.recordPhase(
      "rs_direct_attention_required",
      "reason=$detailReason state=${observation.state.name.lowercase()} states=${observedStates.joinToString(",")}"
    )
    return failure(
      "rs_app_attention_required",
      observation.snapshot,
      "direct_$detailReason states=${observedStates.joinToString(",")}"
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

  private data class Observation(
    val state: RigasSatiksmeSemanticState,
    val snapshot: List<PhoneAutomationVisibleNode>
  )

  private data class ManualCodeEntryResult(
    val entered: Observation? = null,
    val failure: RigasSatiksmeMonthlyTicketFlowResult? = null
  )

  private data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
  )

  private companion object {
    private const val RESET_SETTLE_MILLIS = 650L
    private const val NAVIGATION_SETTLE_MILLIS = 350L
    private const val TEXT_ENTRY_SETTLE_MILLIS = 120L
    private const val POST_SUBMIT_SETTLE_MILLIS = 620L
    private const val MODAL_TO_CONTROL_SETTLE_MILLIS = 700L
    private const val UNKNOWN_RECHECK_MILLIS = 260L
    private const val MANUAL_CODE_ENTRY_MAX_ATTEMPTS = 2

    private const val REGISTER_TRIP_X = 0.500
    private const val REGISTER_TRIP_Y = 555.0 / 2424.0
    private const val MANUAL_CODE_CHOICE_X = 0.500
    private const val MANUAL_CODE_CHOICE_Y = 0.915
    private const val MANUAL_CODE_FIELD_X = 0.500
    private const val MANUAL_CODE_FIELD_Y = 0.495
    private const val CONFIRM_CODE_X = 0.500
    private const val CONFIRM_CODE_Y = 1541.0 / 2424.0
    private const val TRIP_REGISTERED_OK_X = 0.500
    private const val TRIP_REGISTERED_OK_Y = 1347.0 / 2424.0
    private const val CONTROL_EXIT_MIN_SIZE = 36
    private const val CONTROL_EXIT_MAX_SIZE = 180
    private const val CONTROL_EXIT_TOP_FRACTION = 0.18
    private const val CONTROL_EXIT_RIGHT_FRACTION = 0.72
    private const val CONTROL_EXIT_LEFT_FRACTION = 0.24

    private val REGISTER_LABELS = listOf(
      "REGISTER A TRIP",
      "Register a trip",
      "Reģistrēt braucienu",
      "Registret braucienu"
    )
    private val MANUAL_CODE_LABELS = listOf(
      "ENTER THE CODE MANUALLY",
      "ENTER CODE MANUALLY",
      "Enter code manually",
      "Ievadīt kodu manuāli",
      "Ievadit kodu manuali",
      "Ievadīt kodu"
    )
    private val CONFIRM_LABELS = listOf(
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
      "Turpināt",
      "OK"
    )
    private val OK_LABELS = listOf("OK", "Labi")
    private val CONTROL_EXIT_LABELS = listOf(
      "Close",
      "Aizvērt",
      "Aizvert",
      "Back",
      "Atpakaļ",
      "Navigate up"
    )
    private val CONTROL_EXIT_FORBIDDEN_LABELS = listOf(
      "KONTROLES KODS",
      "Kontroles kods",
      "TICKET FOR CONTROL",
      "30 dienu",
      "month",
      "qr",
      "code",
      "Reģistrēt",
      "Register"
    )
    private val BOUNDS_REGEX = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
  }
}
