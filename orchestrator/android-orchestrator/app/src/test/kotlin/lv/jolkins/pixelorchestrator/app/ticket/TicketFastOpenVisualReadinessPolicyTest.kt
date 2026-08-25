package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketFastOpenVisualReadinessPolicyTest {
  @Test
  fun everyStableTypedViviViewIsSafeForReadOnlyStreamStartup() {
    val expected = mapOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL to TicketViviRecoveryState.TICKET_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL to TicketViviRecoveryState.TICKET_DETAIL,
      TicketVisualPhoneState.TICKET_LIST to TicketViviRecoveryState.TICKET_LIST_WITH_CARD,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY to TicketViviRecoveryState.TICKET_LIST_EMPTY,
      TicketVisualPhoneState.VIVI_HOME to TicketViviRecoveryState.OTHER_VIVI_TAB,
      TicketVisualPhoneState.LOGIN_REQUIRED to TicketViviRecoveryState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED to TicketViviRecoveryState.DISMISSIBLE_BLOCKER
    )

    expected.forEach { (visualState, recoveryState) ->
      val decision = TicketFastOpenVisualReadinessPolicy.decide(visualState)
      assertTrue(visualState.wireName, decision.success)
      assertEquals(recoveryState, decision.recoveryState)
      assertTrue(decision.step.endsWith("_ready"))
    }
  }

  @Test
  fun unknownTransitionFrameNeverStartsTheStream() {
    val decision = TicketFastOpenVisualReadinessPolicy.decide(TicketVisualPhoneState.UNKNOWN)

    assertFalse(decision.success)
    assertEquals(TicketViviRecoveryState.UNKNOWN_VIVI, decision.recoveryState)
    assertFalse(TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(decision.recoveryState))
  }

  @Test
  fun outsideBlankAndUnknownRecoveryStatesRemainUnsafe() {
    assertFalse(TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(TicketViviRecoveryState.OUTSIDE_VIVI))
    assertFalse(TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(TicketViviRecoveryState.BLANK))
    assertFalse(TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(TicketViviRecoveryState.UNKNOWN_VIVI))
    assertTrue(TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(TicketViviRecoveryState.OTHER_VIVI_TAB))
  }
}
