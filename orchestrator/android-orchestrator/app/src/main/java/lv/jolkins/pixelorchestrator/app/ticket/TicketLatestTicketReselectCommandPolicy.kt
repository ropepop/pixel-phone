package lv.jolkins.pixelorchestrator.app.ticket

internal enum class TicketLatestTicketReselectCommandDisposition {
  START,
  DEFER,
  SUCCEEDED,
  FAILED
}

internal data class TicketLatestTicketReselectCommandDecision(
  val disposition: TicketLatestTicketReselectCommandDisposition,
  val reason: String
)

internal fun mutateLatestTicketReselectIfCurrent(
  stateLock: Any,
  currentGeneration: () -> Long,
  currentCommandId: () -> String,
  expectedGeneration: Long,
  expectedCommandId: String,
  mutation: () -> Unit
): Boolean {
  return synchronized(stateLock) {
    if (
      currentGeneration() != expectedGeneration ||
      currentCommandId() != expectedCommandId
    ) {
      false
    } else {
      mutation()
      true
    }
  }
}

internal object TicketLatestTicketReselectCommandPolicy {
  fun decide(
    currentCommandId: String,
    currentStatus: String,
    currentPhase: String,
    incomingCommandId: String,
    controlSensitiveWindowActive: Boolean
  ): TicketLatestTicketReselectCommandDecision {
    val sameCommand = currentCommandId.isNotBlank() && currentCommandId == incomingCommandId
    if (sameCommand) {
      return when {
        currentStatus == "succeeded" && currentPhase == "ready" ->
          TicketLatestTicketReselectCommandDecision(
            disposition = TicketLatestTicketReselectCommandDisposition.SUCCEEDED,
            reason = "latest_ticket_reselect_succeeded"
          )
        currentStatus == "failed" ->
          TicketLatestTicketReselectCommandDecision(
            disposition = TicketLatestTicketReselectCommandDisposition.FAILED,
            reason = "latest_ticket_reselect_failed"
          )
        currentStatus == "yielded" && controlSensitiveWindowActive ->
          TicketLatestTicketReselectCommandDecision(
            disposition = TicketLatestTicketReselectCommandDisposition.DEFER,
            reason = "control_code_active"
          )
        currentStatus == "yielded" ->
          TicketLatestTicketReselectCommandDecision(
            disposition = TicketLatestTicketReselectCommandDisposition.START,
            reason = "latest_ticket_reselect_resume_after_control_code"
          )
        else ->
          TicketLatestTicketReselectCommandDecision(
            disposition = TicketLatestTicketReselectCommandDisposition.DEFER,
            reason = "latest_ticket_reselect_in_progress"
          )
      }
    }

    val currentCommandStillActive =
      currentCommandId.isNotBlank() &&
        (currentStatus == "pending" ||
          currentStatus == "yielded" ||
          (currentStatus == "succeeded" && currentPhase != "ready"))
    if (currentCommandStillActive) {
      return TicketLatestTicketReselectCommandDecision(
        disposition = TicketLatestTicketReselectCommandDisposition.DEFER,
        reason = "latest_ticket_reselect_busy"
      )
    }
    if (controlSensitiveWindowActive) {
      return TicketLatestTicketReselectCommandDecision(
        disposition = TicketLatestTicketReselectCommandDisposition.DEFER,
        reason = "control_code_active"
      )
    }
    return TicketLatestTicketReselectCommandDecision(
      disposition = TicketLatestTicketReselectCommandDisposition.START,
      reason = "latest_ticket_reselect_start"
    )
  }
}

internal object TicketLatestTicketReselectPreemptionPolicy {
  fun shouldYieldFor(commandType: String): Boolean {
    return commandType == "generate_control_code" ||
      commandType == "control_code_browser_capture" ||
      commandType == "close_control_code"
  }
}
