package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketLegacyRegistrationInteractionPolicyTest {
  @Test
  fun ordinaryV3NavigationAndSwitchActionsNeverEnterTheLegacyInteractionLane() {
    listOf(
      "open_latest_unactivated",
      "show_recent_activated",
      "return_to_latest_unactivated",
      "redetect_latest"
    ).forEach { target ->
      assertFalse(
        target,
        ticketCommandUsesLegacyRegistrationInteraction(
          commandType = "ticket_action_v3",
          target = target
        )
      )
    }
  }

  @Test
  fun registerCurrentUsesItsDedicatedActivationCommitInsteadOfTheLegacyResetBridge() {
    assertFalse(
      ticketCommandUsesLegacyRegistrationInteraction(
        commandType = "ticket_action_v3",
        target = "register_current"
      )
    )
  }

  @Test
  fun openAndRegisterRetainsTheLegacyActivationCommitLane() {
    assertTrue(
      ticketCommandUsesLegacyRegistrationInteraction(
        commandType = "ticket_action_v3",
        target = "open_latest_and_register"
      )
    )
  }

  @Test
  fun scheduledV3ExpiryRefreshRetainsTheLegacyFreshProofLane() {
    assertTrue(
      ticketCommandUsesLegacyRegistrationInteraction(
        commandType = "ticket_action_v3",
        target = "open_latest_unactivated",
        flow = "activation_expiry_reset"
      )
    )
    assertFalse(
      ticketCommandUsesLegacyRegistrationInteraction(
        commandType = "ticket_action_v3",
        target = "redetect_latest",
        flow = "activation_expiry_reset"
      )
    )
  }

  @Test
  fun legacyResetAndExpiryReselectCommandsKeepTheirExistingProofLane() {
    assertTrue(ticketCommandUsesLegacyRegistrationInteraction("reset_ticket_registration"))
    assertTrue(
      ticketCommandUsesLegacyRegistrationInteraction(
        commandType = "force_ticket_reselect",
        flow = "activation_expiry_reset"
      )
    )
    assertFalse(ticketCommandUsesLegacyRegistrationInteraction("force_ticket_reselect"))
  }
}
