package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketInactivityPolicyTest {
  @Test
  fun timeoutIsTenMinutes() {
    assertEquals(600_000L, TicketInactivityPolicy.TIMEOUT_MILLIS)
  }

  @Test
  fun remainingTimeCountsDownFromLastViewerInput() {
    assertEquals(
      600_000L,
      TicketInactivityPolicy.remainingMillis(
        lastInputAtMillis = 1_000L,
        nowMillis = 1_000L
      )
    )
    assertEquals(
      540_000L,
      TicketInactivityPolicy.remainingMillis(
        lastInputAtMillis = 1_000L,
        nowMillis = 61_000L
      )
    )
  }

  @Test
  fun timeoutStartsAtTheTenMinuteBoundary() {
    assertFalse(
      TicketInactivityPolicy.timedOut(
        lastInputAtMillis = 1_000L,
        nowMillis = 600_999L
      )
    )
    assertTrue(
      TicketInactivityPolicy.timedOut(
        lastInputAtMillis = 1_000L,
        nowMillis = 601_000L
      )
    )
    assertEquals(
      0L,
      TicketInactivityPolicy.remainingMillis(
        lastInputAtMillis = 1_000L,
        nowMillis = 700_000L
      )
    )
  }

  @Test
  fun inactivityTimeoutStopsStreamWithoutResetAndBlocksBrowserAutoStart() {
    assertFalse(
      TicketSessionStopPolicy.shouldResetViviToTicket(
        TicketSessionStopPolicy.VIEWER_INACTIVITY_TIMEOUT
      )
    )
    assertFalse(
      TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(
        TicketSessionStopPolicy.VIEWER_INACTIVITY_TIMEOUT
      )
    )
  }

  @Test
  fun explicitBrowserStopDoesNotResetViviAndBlocksAutoStart() {
    assertFalse(TicketSessionStopPolicy.shouldResetViviToTicket(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP))
    assertFalse(TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP))
    assertTrue(TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(null))
  }
}
