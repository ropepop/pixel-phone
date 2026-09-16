package lv.jolkins.pixelorchestrator.app.ticket

internal fun ticketIdleRefreshCard(
  original: TicketVisualActionObservation,
  list: TicketVisualActionObservation
): TicketVisualCardAnchor? {
  if (list.state != TicketVisualPhoneState.TICKET_LIST || original.detailCardAnchor.isBlank()) return null
  val eligible = list.cards.filter {
    it.anchor.isNotBlank() && when (original.state) {
      TicketVisualPhoneState.UNACTIVATED_DETAIL -> it.registrationBounds != null
      TicketVisualPhoneState.ACTIVATED_DETAIL -> it.activatedDetailBounds != null
      else -> false
    }
  }
  // A lone remaining card may be a replacement for an expired ticket. Never infer identity.
  return eligible.singleOrNull { it.anchor == original.detailCardAnchor }
}

internal data class TicketIdleRefreshResult(
  val reason: String,
  val observation: TicketVisualActionObservation? = null,
  val ok: Boolean = false
)

/** One app restart, then the existing bounded exact-ticket route. Never resume retained input. */
internal suspend fun refreshCurrentTicket(
  initial: TicketVisualActionObservation,
  retainedDispatch: Boolean,
  begin: suspend () -> Boolean,
  restart: suspend () -> Boolean,
  dispatch: suspend (TicketVisualActionObservation, TicketVisualProbeBounds, String) -> Boolean,
  observe: suspend () -> TicketVisualActionObservation?,
  prepare: suspend (TicketVisualActionObservation, TicketVisualProbeBounds, String) -> Boolean = { _, _, _ -> true }
): TicketIdleRefreshResult {
  fun failed(reason: String) = TicketIdleRefreshResult(reason)
  if (retainedDispatch) return failed("ticket_action_navigation_dispatch_uncertain")
  val original = ticketViviReauthReturnTarget(initial)
    ?: return failed("ticket_action_detail_identity_unproved")
  if (initial.detailCardAnchor.isBlank()) {
    return failed("ticket_action_detail_identity_unproved")
  }
  if (!prepare(initial, requireNotNull(initial.backBounds), "")) {
    return failed("ticket_action_idle_refresh_unavailable")
  }
  if (!begin()) return failed("ticket_action_idle_refresh_cancelled")
  if (!restart()) {
    return failed("ticket_action_navigation_dispatch_uncertain")
  }
  var before = initial
  val dispatched = mutableSetOf<TicketViviNavigationKind>()
  // Home -> Tickets -> Time tickets -> exact detail: at most three navigation taps.
  repeat(4) { step ->
    val observation = observe()?.takeIf { it.probeId > before.probeId &&
      it.captureGeneration == initial.captureGeneration }
      ?: return failed("ticket_action_visual_transition_unproved")
    if (ticketViviReauthRestoredTarget(original, observation) &&
      observation.detailCardAnchor == initial.detailCardAnchor
    ) return TicketIdleRefreshResult("ticket_action_current_ticket_refreshed", observation, true)
    if (observation.state in setOf(TicketVisualPhoneState.ACTIVATED_DETAIL,
        TicketVisualPhoneState.UNACTIVATED_DETAIL)) {
      return failed("ticket_action_detail_identity_conflict")
    }
    val target = if (observation.state == TicketVisualPhoneState.TICKET_LIST) {
      val card = ticketIdleRefreshCard(initial, observation)
        ?: return failed("ticket_action_visual_target_ambiguous")
      if (original.state == TicketVisualPhoneState.ACTIVATED_DETAIL) {
        TicketViviNavigationTarget(TicketViviNavigationKind.ACTIVATED_DETAIL,
          requireNotNull(card.activatedDetailBounds), card.anchor)
      } else TicketViviNavigationTarget(TicketViviNavigationKind.UNUSED_DETAIL,
        requireNotNull(card.registrationBounds), card.anchor)
    } else ticketViviNavigationTarget(TicketViviNavigationMode.EXACT, original, observation)
      ?: return failed("ticket_action_visual_target_ambiguous")
    if (step == 3 || !dispatched.add(target.kind)) {
      return failed("ticket_action_visual_transition_unproved")
    }
    // Admission owns restoration after a viewer returns; every new input still rechecks touch.
    if (!prepare(observation, target.bounds, target.selectedAnchor) ||
      !dispatch(observation, target.bounds, target.selectedAnchor)
    ) return failed("ticket_action_navigation_dispatch_uncertain")
    before = observation
  }
  return failed("ticket_action_visual_transition_unproved")
}
