package lv.jolkins.pixelorchestrator.app.ticket

internal object TicketLatestTicketReselectRecoveryPolicy {
  private val ticketCardSelectionActions = setOf(
    "open_fresh_time_ticket_card",
    "open_upcoming_time_ticket_card",
    "open_ticket_card"
  )
  private val finalActionCategories = setOf(
    "none",
    "ticket_card_selection",
    "other_recovery"
  )
  private val finalActionOutcomes = setOf(
    "not_attempted",
    "succeeded",
    "failed"
  )

  fun actionCategory(actionReason: String): String {
    return when {
      actionReason.isBlank() -> "none"
      actionReason in ticketCardSelectionActions -> "ticket_card_selection"
      else -> "other_recovery"
    }
  }

  fun actionOutcome(attempted: Boolean, succeeded: Boolean): String {
    return when {
      !attempted -> "not_attempted"
      succeeded -> "succeeded"
      else -> "failed"
    }
  }

  fun finalTelemetryEvent(
    state: TicketViviRecoveryState,
    actionCategory: String,
    actionOutcome: String
  ): String {
    val cleanActionCategory =
      actionCategory.takeIf(finalActionCategories::contains) ?: "unknown_action"
    val cleanActionOutcome =
      actionOutcome.takeIf(finalActionOutcomes::contains) ?: "unknown"
    return "latest_ticket_reselect_final_${state.name.lowercase()}_${cleanActionCategory}_${cleanActionOutcome}"
  }

  fun ticketCardSelectionGraceDeadlineMillis(
    currentDeadlineMillis: Long,
    actionReason: String,
    actionSucceeded: Boolean,
    actionCompletedAtMillis: Long,
    graceMillis: Long
  ): Long {
    if (
      !actionSucceeded ||
      actionCategory(actionReason) != "ticket_card_selection" ||
      actionCompletedAtMillis <= 0L ||
      graceMillis <= 0L
    ) {
      return currentDeadlineMillis
    }
    return maxOf(currentDeadlineMillis, actionCompletedAtMillis + graceMillis)
  }

  fun remainingMillis(
    wakeStartedAtMillis: Long,
    launchBudgetMillis: Long,
    ticketCardSelectionGraceDeadlineMillis: Long,
    nowMillis: Long
  ): Long {
    val launchRemainingMillis =
      (wakeStartedAtMillis + launchBudgetMillis - nowMillis).coerceAtLeast(0L)
    val actionGraceRemainingMillis =
      (ticketCardSelectionGraceDeadlineMillis - nowMillis).coerceAtLeast(0L)
    return maxOf(launchRemainingMillis, actionGraceRemainingMillis)
  }
}
