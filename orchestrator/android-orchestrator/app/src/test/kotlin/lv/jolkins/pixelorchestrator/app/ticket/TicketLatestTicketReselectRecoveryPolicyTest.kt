package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketLatestTicketReselectRecoveryPolicyTest {
  @Test
  fun routinePreparationDoesNotYieldAnActiveReselect() {
    assertFalse(TicketLatestTicketReselectPreemptionPolicy.shouldYieldFor("prepare_control_code"))
    assertTrue(TicketLatestTicketReselectPreemptionPolicy.shouldYieldFor("generate_control_code"))
    assertTrue(TicketLatestTicketReselectPreemptionPolicy.shouldYieldFor("control_code_browser_capture"))
    assertTrue(TicketLatestTicketReselectPreemptionPolicy.shouldYieldFor("close_control_code"))
  }

  @Test
  fun successfulLateTicketCardSelectionGetsActionRelativeGrace() {
    val wakeStartedAtMillis = 1_000L
    val actionCompletedAtMillis = wakeStartedAtMillis + 119_000L
    val graceDeadlineMillis =
      TicketLatestTicketReselectRecoveryPolicy.ticketCardSelectionGraceDeadlineMillis(
        currentDeadlineMillis = 0L,
        actionReason = "open_fresh_time_ticket_card",
        actionSucceeded = true,
        actionCompletedAtMillis = actionCompletedAtMillis,
        graceMillis = 60_000L
      )

    assertEquals(actionCompletedAtMillis + 60_000L, graceDeadlineMillis)
    assertEquals(
      60_000L,
      TicketLatestTicketReselectRecoveryPolicy.remainingMillis(
        wakeStartedAtMillis = wakeStartedAtMillis,
        launchBudgetMillis = 120_000L,
        ticketCardSelectionGraceDeadlineMillis = graceDeadlineMillis,
        nowMillis = actionCompletedAtMillis
      )
    )
    assertEquals(
      1_000L,
      TicketLatestTicketReselectRecoveryPolicy.remainingMillis(
        wakeStartedAtMillis = wakeStartedAtMillis,
        launchBudgetMillis = 120_000L,
        ticketCardSelectionGraceDeadlineMillis = 0L,
        nowMillis = actionCompletedAtMillis
      )
    )
  }

  @Test
  fun failedOrUnrelatedRecoveryActionDoesNotExtendTheLaunchBudget() {
    listOf(
      "open_fresh_time_ticket_card" to false,
      "open_tickets_tab" to true
    ).forEach { (actionReason, actionSucceeded) ->
      assertEquals(
        0L,
        TicketLatestTicketReselectRecoveryPolicy.ticketCardSelectionGraceDeadlineMillis(
          currentDeadlineMillis = 0L,
          actionReason = actionReason,
          actionSucceeded = actionSucceeded,
          actionCompletedAtMillis = 120_000L,
          graceMillis = 60_000L
        )
      )
    }
  }

  @Test
  fun telemetryCategoriesAreFixedAndDoNotRetainActionText() {
    assertEquals(
      "ticket_card_selection",
      TicketLatestTicketReselectRecoveryPolicy.actionCategory("open_upcoming_time_ticket_card")
    )
    assertEquals(
      "ticket_card_selection",
      TicketLatestTicketReselectRecoveryPolicy.actionCategory("open_upcoming_time_ticket_detail_card")
    )
    assertEquals(
      "ticket_card_selection",
      TicketLatestTicketReselectRecoveryPolicy.actionCategory("open_fresh_time_ticket_registration_button")
    )
    assertEquals(
      "other_recovery",
      TicketLatestTicketReselectRecoveryPolicy.actionCategory("untrusted action text")
    )
    assertEquals("none", TicketLatestTicketReselectRecoveryPolicy.actionCategory(""))
    assertEquals(
      "not_attempted",
      TicketLatestTicketReselectRecoveryPolicy.actionOutcome(attempted = false, succeeded = false)
    )
    assertEquals(
      "succeeded",
      TicketLatestTicketReselectRecoveryPolicy.actionOutcome(attempted = true, succeeded = true)
    )
    assertEquals(
      "failed",
      TicketLatestTicketReselectRecoveryPolicy.actionOutcome(attempted = true, succeeded = false)
    )
    assertEquals(
      "latest_ticket_reselect_final_ticket_detail_ticket_card_selection_succeeded",
      TicketLatestTicketReselectRecoveryPolicy.finalTelemetryEvent(
        state = TicketViviRecoveryState.TICKET_DETAIL,
        actionCategory = "ticket_card_selection",
        actionOutcome = "succeeded"
      )
    )
    assertEquals(
      "latest_ticket_reselect_final_ticket_detail_unknown_action_unknown",
      TicketLatestTicketReselectRecoveryPolicy.finalTelemetryEvent(
        state = TicketViviRecoveryState.TICKET_DETAIL,
        actionCategory = "private action text",
        actionOutcome = "private outcome text"
      )
    )
  }

  @Test
  fun semanticTicketDetailProofRequiresTheMatchingLatestCardSelection() {
    fun accepted(
      action: String,
      unactivated: Boolean = false,
      upcomingPreValidity: Boolean = false,
      aztec: Boolean = false,
      requireLatest: Boolean = true,
      requireFreshAztec: Boolean = true
    ): Boolean = TicketLatestTicketReselectRecoveryPolicy.ticketDetailProofAccepted(
      requireFreshAztecVisualProof = requireFreshAztec,
      requireLatestTicketSelection = requireLatest,
      latestTicketSelectionAction = action,
      unactivatedRegistrationDetail = unactivated,
      upcomingPreValidityTicketDetail = upcomingPreValidity,
      freshAztecVisualProofed = aztec
    )

    assertFalse(
      accepted(action = "", unactivated = true)
    )
    assertTrue(
      accepted(action = "open_fresh_time_ticket_detail_card", unactivated = true)
    )
    assertTrue(
      accepted(
        action = "open_upcoming_time_ticket_detail_card",
        upcomingPreValidity = true
      )
    )
    assertFalse(accepted(action = "open_fresh_time_ticket_detail_card", upcomingPreValidity = true))
    assertFalse(accepted(action = "open_fresh_time_ticket_registration_button", unactivated = true))
    assertFalse(accepted(action = "open_upcoming_time_ticket_card", upcomingPreValidity = true))
    assertFalse(accepted(action = "open_fresh_time_ticket_detail_card"))
    assertTrue(
      accepted(action = "open_fresh_time_ticket_detail_card", aztec = true)
    )
    assertFalse(accepted(action = "open_fresh_time_ticket_detail_card", unactivated = true, requireLatest = false))
    assertFalse(accepted(action = "open_upcoming_time_ticket_detail_card", upcomingPreValidity = true, requireLatest = false))
    assertFalse(accepted(action = "open_ticket_card", requireFreshAztec = false))
    assertTrue(accepted(action = "", requireLatest = false, requireFreshAztec = false))
  }

  @Test
  fun healedControlCodeDetailCannotBypassSpecializedProof() {
    fun accepted(
      requireNew: Boolean = false,
      requireList: Boolean = false,
      requireAztec: Boolean = false,
      requireUnactivated: Boolean = false,
      requireLatest: Boolean = false
    ): Boolean = TicketLatestTicketReselectRecoveryPolicy.canAcceptHealedTicketDetail(
      requireNewTicketRegistration = requireNew,
      requireTicketListWithRegistrationButton = requireList,
      requireFreshAztecVisualProof = requireAztec,
      requireUnactivatedRegistration = requireUnactivated,
      requireLatestTicketSelection = requireLatest
    )

    assertTrue(accepted())
    assertFalse(accepted(requireNew = true))
    assertFalse(accepted(requireList = true))
    assertFalse(accepted(requireAztec = true))
    assertFalse(accepted(requireUnactivated = true))
    assertFalse(accepted(requireLatest = true))
  }

  @Test
  fun onlyTicketCardBodyActionsCountAsLatestTicketSelection() {
    assertTrue(
      TicketLatestTicketReselectRecoveryPolicy.isTicketDetailSelectionAction(
        "open_fresh_time_ticket_detail_card"
      )
    )
    assertTrue(
      TicketLatestTicketReselectRecoveryPolicy.isTicketDetailSelectionAction(
        "open_upcoming_time_ticket_detail_card"
      )
    )
    assertFalse(
      TicketLatestTicketReselectRecoveryPolicy.isTicketDetailSelectionAction(
        "open_upcoming_time_ticket_registration_button"
      )
    )
  }

  @Test
  fun everyFinalTelemetryEventFitsTheDurableEventVocabularyLimit() {
    TicketViviRecoveryState.entries.forEach { state ->
      listOf("none", "ticket_card_selection", "other_recovery").forEach { actionCategory ->
        listOf("not_attempted", "succeeded", "failed").forEach { actionOutcome ->
          val event = TicketLatestTicketReselectRecoveryPolicy.finalTelemetryEvent(
            state = state,
            actionCategory = actionCategory,
            actionOutcome = actionOutcome
          )
          assertTrue(event.matches(Regex("[a-z][a-z0-9_]{0,95}")))
        }
      }
    }
  }
}
