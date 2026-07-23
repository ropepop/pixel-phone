package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketSpacetimeDesiredRefreshStateTest {
  @Test
  fun idleCommandArrivesThenLiveRefreshesOnceBeforeReportingDesiredActive() {
    val state = TicketSpacetimeDesiredRefreshState<Boolean>()
    state.onClientConnected()

    assertTrue(state.shouldRefresh(hotLaneActive = false, hasPendingCommands = false, nowMillis = 0L))
    state.markRefreshed(false, hotLaneActive = false, desiredActive = false, nowMillis = 0L)
    assertFalse(state.cachedDesired == true)

    assertFalse(state.shouldRefresh(hotLaneActive = false, hasPendingCommands = true, nowMillis = 100L))
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 100L))
    state.markRefreshed(true, hotLaneActive = true, desiredActive = true, nowMillis = 100L)

    assertEquals(true, state.cachedDesired)
    repeat(10) {
      assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 10_000L + it))
    }
  }

  @Test
  fun reconnectWhileLiveDefersForCommandsThenRefreshesOnceBeforeReportingDesiredActive() {
    val state = TicketSpacetimeDesiredRefreshState<Boolean>()
    state.onClientConnected()
    state.markRefreshed(false, hotLaneActive = false, desiredActive = false, nowMillis = 0L)

    state.onClientConnected()
    assertFalse(state.cacheLoaded)
    assertNull(state.cachedDesired)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = true, nowMillis = 100L))
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 100L))
    state.markRefreshed(true, hotLaneActive = true, desiredActive = true, nowMillis = 100L)

    assertTrue(state.cacheLoaded)
    assertEquals(true, state.cachedDesired)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 10_000L))
  }

  @Test
  fun leavingAndReenteringTheHotLaneSchedulesOneNewRefresh() {
    val state = TicketSpacetimeDesiredRefreshState<Boolean>()
    state.onClientConnected()
    state.markRefreshed(false, hotLaneActive = false, desiredActive = false, nowMillis = 0L)

    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 100L))
    state.markRefreshed(true, hotLaneActive = true, desiredActive = true, nowMillis = 100L)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 101L))

    assertFalse(state.shouldRefresh(hotLaneActive = false, hasPendingCommands = false, nowMillis = 200L))
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 300L))
    state.markRefreshed(true, hotLaneActive = true, desiredActive = true, nowMillis = 300L)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 301L))
  }

  @Test
  fun falseFirstHotReadRetriesAfterDelayAndThenPublishesTrueWithoutHotPolling() {
    val state = TicketSpacetimeDesiredRefreshState<Boolean>(
      hotNegativeRetryDelayMillis = 1_000L,
      maxHotNegativeFollowUpReads = 4
    )
    state.onClientConnected()

    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 0L))
    state.markRefreshed(false, hotLaneActive = true, desiredActive = false, nowMillis = 0L)
    assertEquals(false, state.cachedDesired)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 999L))
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 1_000L))

    state.markRefreshed(true, hotLaneActive = true, desiredActive = true, nowMillis = 1_000L)
    assertEquals(true, state.cachedDesired)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 10_000L))
  }

  @Test
  fun falseHotReadsStopAfterTheConfiguredFollowUpCap() {
    val state = TicketSpacetimeDesiredRefreshState<Boolean>(
      hotNegativeRetryDelayMillis = 100L,
      maxHotNegativeFollowUpReads = 2
    )
    state.onClientConnected()

    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 0L))
    state.markRefreshed(false, hotLaneActive = true, desiredActive = false, nowMillis = 0L)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 99L))
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 100L))
    state.markRefreshed(false, hotLaneActive = true, desiredActive = false, nowMillis = 100L)
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 200L))
    state.markRefreshed(false, hotLaneActive = true, desiredActive = false, nowMillis = 200L)

    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 10_000L))
  }

  @Test
  fun pendingCommandsDeferAnOverdueHotFollowUpWithoutConsumingIt() {
    val state = TicketSpacetimeDesiredRefreshState<Boolean>(
      hotNegativeRetryDelayMillis = 100L,
      maxHotNegativeFollowUpReads = 1
    )
    state.onClientConnected()

    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 0L))
    state.markRefreshed(false, hotLaneActive = true, desiredActive = false, nowMillis = 0L)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = true, nowMillis = 100L))
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = true, nowMillis = 500L))
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 500L))
    state.markRefreshed(true, hotLaneActive = true, desiredActive = true, nowMillis = 500L)
    assertFalse(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 10_000L))
  }

  @Test
  fun leavingTheHotLaneCancelsAScheduledNegativeFollowUp() {
    val state = TicketSpacetimeDesiredRefreshState<Boolean>(
      hotNegativeRetryDelayMillis = 100L,
      maxHotNegativeFollowUpReads = 1
    )
    state.onClientConnected()

    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 0L))
    state.markRefreshed(false, hotLaneActive = true, desiredActive = false, nowMillis = 0L)
    assertFalse(state.shouldRefresh(hotLaneActive = false, hasPendingCommands = false, nowMillis = 100L))
    assertFalse(state.shouldRefresh(hotLaneActive = false, hasPendingCommands = false, nowMillis = 10_000L))
    assertTrue(state.shouldRefresh(hotLaneActive = true, hasPendingCommands = false, nowMillis = 10_001L))
  }

  @Test
  fun workerScansCommandsFirstAndInvalidatesThePhoneReportAfterCanonicalRefresh() {
    val worker = source("ticket/TicketSpacetimeWorker.kt")
    val connection = body(worker, "private fun onSpacetimeClientConnected", "private suspend fun runCycle")
    val cycle = body(worker, "private suspend fun runCycle", "private fun commandCanBePreemptedByControlCode")
    val register = worker.indexOf("client.register()")
    val connected = worker.indexOf("onSpacetimeClientConnected()", register)
    val commandScan = cycle.indexOf("var commands = client.pendingCommands(config)")
    val refreshDecision = cycle.indexOf("desiredRefreshState.shouldRefresh(")
    val canonicalRead = cycle.indexOf("desired = client.desiredState(config)")
    val cacheUpdate = cycle.indexOf("desiredRefreshState.markRefreshed(", canonicalRead)
    val reportInvalidation = cycle.indexOf("lastPhoneReportWriteKey = \"\"", cacheUpdate)
    val phoneReport = cycle.indexOf("maybeUpdatePhoneReport(client, desired)", reportInvalidation)

    assertTrue(register >= 0 && connected > register)
    assertTrue(connection.contains("desiredRefreshState.onClientConnected()"))
    assertTrue(connection.contains("lastPhoneReportWriteKey = \"\""))
    assertTrue(commandScan >= 0 && refreshDecision > commandScan)
    assertTrue(canonicalRead > refreshDecision)
    assertTrue(cacheUpdate > canonicalRead)
    assertTrue(reportInvalidation > cacheUpdate)
    assertTrue(phoneReport > reportInvalidation)
    assertEquals(1, Regex("desiredRefreshState\\.shouldRefresh\\(").findAll(cycle).count())
  }

  private fun body(text: String, startNeedle: String, endNeedle: String): String {
    val start = text.indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = text.indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return text.substring(start, end)
  }

  private fun source(relative: String): String {
    val roots = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/$relative"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/$relative")
    )
    val path = roots.firstOrNull(Files::exists) ?: error("Missing source file: $relative")
    return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }
}
