package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.delay
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode

internal interface RigasSatiksmeDirectTapGateway {
  suspend fun launchApp(): Boolean
  suspend fun waitForForeground(): Boolean
  suspend fun snapshot(reason: String): List<PhoneAutomationVisibleNode>
  suspend fun tapRatio(x: Double, y: Double, reason: String): Boolean
  suspend fun enterManualCode(cleanDigits: String, fieldXRatio: Double, fieldYRatio: Double): Boolean
  suspend fun pressBack(reason: String): Boolean
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
    val initial = observe("initial", cleanDigits, observedStates)
    when (initial.state) {
      RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING,
      RigasSatiksmeSemanticState.TICKET_CONTROL_STALE -> {
        gateway.pressBack("rs_direct_initial_control_back")
        delay(RESET_SETTLE_MILLIS)
      }
      RigasSatiksmeSemanticState.TRIP_REGISTERED -> {
        gateway.tapRatio(TRIP_REGISTERED_OK_X, TRIP_REGISTERED_OK_Y, "rs_direct_initial_trip_ok")
        delay(RESET_SETTLE_MILLIS)
        gateway.pressBack("rs_direct_initial_trip_back")
        delay(RESET_SETTLE_MILLIS)
      }
      RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY,
      RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY -> {
        gateway.pressBack("rs_direct_initial_entry_back")
        delay(RESET_SETTLE_MILLIS)
      }
      RigasSatiksmeSemanticState.NO_MONTHLY_TICKET -> return failure("rs_monthly_ticket_missing", initial.snapshot)
      RigasSatiksmeSemanticState.AUTH_BLOCKED -> return failure("rs_auth_blocked", initial.snapshot)
      else -> Unit
    }

    gateway.tapRatio(REGISTER_TRIP_X, REGISTER_TRIP_Y, "rs_direct_register_trip")
    delay(NAVIGATION_SETTLE_MILLIS)
    gateway.tapRatio(MANUAL_CODE_CHOICE_X, MANUAL_CODE_CHOICE_Y, "rs_direct_manual_code_choice")
    delay(NAVIGATION_SETTLE_MILLIS)
    if (!gateway.enterManualCode(cleanDigits, MANUAL_CODE_FIELD_X, MANUAL_CODE_FIELD_Y)) {
      return failure("rs_manual_code_field_missing", details = "direct_text_entry_failed states=${observedStates.joinToString(",")}")
    }
    delay(TEXT_ENTRY_SETTLE_MILLIS)
    gateway.tapRatio(CONFIRM_CODE_X, CONFIRM_CODE_Y, "rs_direct_confirm_code")
    delay(POST_SUBMIT_SETTLE_MILLIS)

    var proof = observe("proof_1", cleanDigits, observedStates)
    if (proof.state == RigasSatiksmeSemanticState.TRIP_REGISTERED) {
      gateway.tapRatio(TRIP_REGISTERED_OK_X, TRIP_REGISTERED_OK_Y, "rs_direct_trip_registered_ok")
      delay(MODAL_TO_CONTROL_SETTLE_MILLIS)
      return RigasSatiksmeMonthlyTicketFlowResult(
        ok = true,
        reason = "generated",
        hierarchy = proof.snapshot.toSemanticHierarchy(),
        details = "direct_tap trip_registered_accepted states=${observedStates.joinToString(",")}"
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
        delay(STALE_RECHECK_MILLIS)
        val recheck = observe("proof_stale_recheck", cleanDigits, observedStates)
        if (recheck.state == RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING) {
          return RigasSatiksmeMonthlyTicketFlowResult(
            ok = true,
            reason = "generated",
            hierarchy = recheck.snapshot.toSemanticHierarchy(),
            details = "direct_tap states=${observedStates.joinToString(",")}"
          )
        }
        return failure(
          "rs_monthly_ticket_stale_code",
          recheck.snapshot,
          "direct_final_ticket_mismatch states=${observedStates.joinToString(",")}"
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
            "rs_monthly_ticket_unknown_state",
            recheck.snapshot,
            "direct_unknown_state states=${observedStates.joinToString(",")}"
          )
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

  private companion object {
    private const val RESET_SETTLE_MILLIS = 650L
    private const val NAVIGATION_SETTLE_MILLIS = 350L
    private const val TEXT_ENTRY_SETTLE_MILLIS = 120L
    private const val POST_SUBMIT_SETTLE_MILLIS = 620L
    private const val MODAL_TO_CONTROL_SETTLE_MILLIS = 700L
    private const val STALE_RECHECK_MILLIS = 260L
    private const val UNKNOWN_RECHECK_MILLIS = 260L

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
  }
}
