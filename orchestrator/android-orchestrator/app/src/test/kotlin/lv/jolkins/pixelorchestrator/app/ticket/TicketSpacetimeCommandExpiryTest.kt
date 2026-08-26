package lv.jolkins.pixelorchestrator.app.ticket

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSpacetimeCommandExpiryTest {
  private val now = Instant.parse("2026-07-23T18:00:00Z")

  @Test
  fun futureCommandCanBeDispatched() {
    assertFalse(ticketSpacetimeCommandExpired("2026-07-23T18:00:00.001Z", now))
  }

  @Test
  fun exactAndPastDeadlinesAreExpired() {
    assertTrue(ticketSpacetimeCommandExpired("2026-07-23T18:00:00Z", now))
    assertTrue(ticketSpacetimeCommandExpired("2026-07-23T17:59:59.999Z", now))
  }

  @Test
  fun malformedOrMissingDeadlineFailsClosed() {
    assertTrue(ticketSpacetimeCommandExpired("", now))
    assertTrue(ticketSpacetimeCommandExpired("not-a-time", now))
  }

  @Test
  fun startDispatchRequiresFreshRemotePendingStateAndFreshLocalDeadline() {
    assertTrue(
      shouldDispatchRevalidatedStartCommand(
        commandType = "start",
        remotelyDispatchable = true,
        expiresAt = "2026-07-23T18:00:00.001Z",
        now = now
      )
    )
    assertFalse(
      shouldDispatchRevalidatedStartCommand(
        commandType = "start",
        remotelyDispatchable = false,
        expiresAt = "2026-07-23T18:00:00.001Z",
        now = now
      )
    )
    assertFalse(
      shouldDispatchRevalidatedStartCommand(
        commandType = "start",
        remotelyDispatchable = true,
        expiresAt = "2026-07-23T18:00:00Z",
        now = now
      )
    )
  }

  @Test
  fun nonStartCommandsKeepTheirExistingDispatchPath() {
    assertTrue(
      shouldDispatchRevalidatedStartCommand(
        commandType = "keyframe",
        remotelyDispatchable = false,
        expiresAt = "",
        now = now
      )
    )
  }

}
