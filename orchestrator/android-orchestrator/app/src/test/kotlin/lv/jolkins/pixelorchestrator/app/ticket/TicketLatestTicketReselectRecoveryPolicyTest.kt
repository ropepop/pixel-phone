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
