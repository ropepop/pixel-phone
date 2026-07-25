package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSpacetimePollingPolicyTest {
  @Test
  fun activeWorkKeepsTheExistingHotCadence() {
    assertEquals(75L, TicketSpacetimePollingPolicy.nextPollMillis(250L, fastWorkActive = true))
    assertEquals(75L, TicketSpacetimePollingPolicy.nextPollMillis(75L, fastWorkActive = true))
  }

  @Test
  fun healthyIdleUsesTheConfiguredCadenceWithA250MillisecondFloor() {
    assertEquals(250L, TicketSpacetimePollingPolicy.nextPollMillis(100L, fastWorkActive = false))
    assertEquals(250L, TicketSpacetimePollingPolicy.nextPollMillis(250L, fastWorkActive = false))
    assertEquals(1_000L, TicketSpacetimePollingPolicy.nextPollMillis(1_000L, fastWorkActive = false))
  }

  @Test
  fun onlyAPositiveSignalRequiresTheFullCommandView() {
    assertFalse(TicketSpacetimePollingPolicy.shouldReadPendingCommands(0))
    assertTrue(TicketSpacetimePollingPolicy.shouldReadPendingCommands(1))
    assertTrue(TicketSpacetimePollingPolicy.shouldReadPendingCommands(3))
  }

  @Test
  fun idleDesiredStateRefreshesOnBootstrapChangeAndOneSecondReconciliation() {
    assertTrue(TicketSpacetimePollingPolicy.shouldRefreshIdleDesired(
      cacheLoaded = false,
      signalChanged = false,
      lastRefreshAtMillis = 0L,
      nowMillis = 10L
    ))
    assertTrue(TicketSpacetimePollingPolicy.shouldRefreshIdleDesired(
      cacheLoaded = true,
      signalChanged = true,
      lastRefreshAtMillis = 100L,
      nowMillis = 200L
    ))
    assertFalse(TicketSpacetimePollingPolicy.shouldRefreshIdleDesired(
      cacheLoaded = true,
      signalChanged = false,
      lastRefreshAtMillis = 100L,
      nowMillis = 1_099L
    ))
    assertTrue(TicketSpacetimePollingPolicy.shouldRefreshIdleDesired(
      cacheLoaded = true,
      signalChanged = false,
      lastRefreshAtMillis = 100L,
      nowMillis = 1_100L
    ))
  }
}
