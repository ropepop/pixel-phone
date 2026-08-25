package lv.jolkins.pixelorchestrator.app.ticket

internal data class TicketFastOpenVisualReadinessDecision(
  val success: Boolean,
  val recoveryState: TicketViviRecoveryState,
  val step: String
)

/**
 * A fresh, stable, typed ViVi frame is sufficient to start streaming so the browser can show and
 * explain the current view. This policy never authorizes registration; that remains restricted to
 * a separate fresh unactivated-detail proof.
 */
internal object TicketFastOpenVisualReadinessPolicy {
  fun decide(state: TicketVisualPhoneState): TicketFastOpenVisualReadinessDecision = when (state) {
    TicketVisualPhoneState.ACTIVATED_DETAIL,
    TicketVisualPhoneState.UNACTIVATED_DETAIL -> TicketFastOpenVisualReadinessDecision(
      success = true,
      recoveryState = TicketViviRecoveryState.TICKET_DETAIL,
      step = "fast_open_current_visual_detail_ready"
    )
    TicketVisualPhoneState.TICKET_LIST -> TicketFastOpenVisualReadinessDecision(
      success = true,
      recoveryState = TicketViviRecoveryState.TICKET_LIST_WITH_CARD,
      step = "fast_open_current_visual_ticket_list_ready"
    )
    TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY -> TicketFastOpenVisualReadinessDecision(
      success = true,
      recoveryState = TicketViviRecoveryState.TICKET_LIST_EMPTY,
      step = "fast_open_current_visual_tickets_single_use_empty_ready"
    )
    TicketVisualPhoneState.VIVI_HOME -> TicketFastOpenVisualReadinessDecision(
      success = true,
      recoveryState = TicketViviRecoveryState.OTHER_VIVI_TAB,
      step = "fast_open_current_visual_vivi_home_ready"
    )
    TicketVisualPhoneState.LOGIN_REQUIRED -> TicketFastOpenVisualReadinessDecision(
      success = true,
      recoveryState = TicketViviRecoveryState.LOGIN_REQUIRED,
      step = "fast_open_current_visual_login_required_ready"
    )
    TicketVisualPhoneState.BLOCKED -> TicketFastOpenVisualReadinessDecision(
      success = true,
      recoveryState = TicketViviRecoveryState.DISMISSIBLE_BLOCKER,
      step = "fast_open_current_visual_blocked_ready"
    )
    TicketVisualPhoneState.UNKNOWN -> TicketFastOpenVisualReadinessDecision(
      success = false,
      recoveryState = TicketViviRecoveryState.UNKNOWN_VIVI,
      step = "fast_open_current_visual_unknown"
    )
  }

  fun isKnownRecoveryState(state: TicketViviRecoveryState): Boolean =
    state != TicketViviRecoveryState.BLANK &&
      state != TicketViviRecoveryState.OUTSIDE_VIVI &&
      state != TicketViviRecoveryState.UNKNOWN_VIVI
}
