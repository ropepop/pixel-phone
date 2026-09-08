package lv.jolkins.pixelorchestrator.app.ticket

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
        commandType = "ticket_action_v3",
        remotelyDispatchable = false,
        expiresAt = "",
        now = now
      )
    )
  }


  private fun pendingCommand(id: String = "command") = TicketSpacetimeCommand(
    id, "ticket", "pixel", "start", "pending", "revision", "browser", "{}",
    "2026-09-07T00:00:00Z", "2026-09-07T00:00:00Z", "2099-01-01T00:00:00Z"
  )

  @Test
  fun cancellationRemovesAuthorityAndReconnectReplacesTheWholeSnapshot() {
    val inbox = TicketCommandInbox()
    val first = pendingCommand()
    assertNull(inbox.snapshot())
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(
      TicketSpacetimeCommandSubscriptionMessageKind.APPLIED, listOf(first)))
    assertTrue(inbox.contains(first))
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(
      TicketSpacetimeCommandSubscriptionMessageKind.UPDATE, deleted = listOf(first.id)))
    assertFalse(inbox.contains(first))
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(
      TicketSpacetimeCommandSubscriptionMessageKind.UPDATE, listOf(first)))
    inbox.disconnected()
    assertNull(inbox.snapshot())
    assertFalse(inbox.contains(first))
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(
      TicketSpacetimeCommandSubscriptionMessageKind.APPLIED, listOf(pendingCommand("replacement"))))
    assertFalse(inbox.contains(first))
    assertEquals(1, inbox.snapshot()?.size)
  }

  @Test
  fun transactionDeletionIsAppliedBeforeReplacementAndForeignRowsCannotEnterInbox() {
    fun row(ticket: String, revision: String, kind: String = "start"): String = JsonPrimitive(JsonArray(listOf(
      "command", ticket, "pixel", kind, "pending", revision, "browser", "{}",
      "2026-09-07T00:00:00Z", "2026-09-07T00:00:00Z", "2099-01-01T00:00:00Z"
    ).map(::JsonPrimitive)).toString()).toString()
    val message = parseTicketSpacetimeCommandSubscriptionMessage(
      """{"TransactionUpdateLight":{"update":{"tables":[{"table_name":"ticketremote_service_stream_command",
        "updates":[{"Uncompressed":{"deletes":[${row("ticket", "old")}],
        "inserts":[${row("foreign", "unauthorized")},${row("ticket", "new")},
        ${row("ticket", "retired", "recover_stream")},${row("ticket", "retired", "keyframe")},
        ${row("ticket", "retired", "activity")}]}}]}]}}}""",
      "ticket", "pixel")
    val inbox = TicketCommandInbox()
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(
      TicketSpacetimeCommandSubscriptionMessageKind.APPLIED, listOf(pendingCommand())))
    inbox.apply(message)
    assertEquals(listOf("new"), inbox.snapshot()?.map { it.revision })
  }

  @Test
  fun desiredStateUpdatesShareTheCommandSnapshotAndClearOnDisconnect() {
    fun row(phase: String) = JsonPrimitive("""["ticket:pixel","ticket","pixel",false,0,"cold_restart","operation","owner","2026-09-08T00:00:00Z",[0,"operation"],[0,"$phase"],[0,"2026-09-08T00:00:00Z"],[1,[]]]""").toString()
    fun update(phase: String) = parseTicketSpacetimeCommandSubscriptionMessage(
      """{"TransactionUpdateLight":{"update":{"tables":[{"table_name":"ticketremote_stream_desired_state",
      "updates":[{"Uncompressed":{"deletes":[${row("stopping")}],"inserts":[${row(phase)}]}}]}]}}}""", "ticket", "pixel")
    val inbox = TicketCommandInbox()
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED))
    inbox.apply(update("stopping"))
    assertTrue(inbox.controlSnapshot()!!.desired!!.coldRestartBlocked)
    assertEquals("operation", inbox.controlSnapshot()!!.desired!!.coldRestartId)
    inbox.apply(update("reloading"))
    assertFalse(inbox.controlSnapshot()!!.desired!!.coldRestartBlocked)
    inbox.disconnected()
    assertNull(inbox.controlSnapshot())
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED))
    assertNull(inbox.controlSnapshot()!!.desired)
  }

  @Test
  fun coldStopPassesTheLiveSubscriptionAllowlist() {
    val row = JsonPrimitive(JsonArray(listOf(
      "cold-stop:operation", "ticket", "pixel", "cold_stop", "pending", "operation", "owner_cold_restart", "{}",
      "2026-09-07T00:00:00Z", "2026-09-07T00:00:00Z", "2099-01-01T00:00:00Z"
    ).map(::JsonPrimitive)).toString()).toString()
    val message = parseTicketSpacetimeCommandSubscriptionMessage(
      """{"TransactionUpdateLight":{"update":{"tables":[{"table_name":"ticketremote_service_stream_command",
        "updates":[{"Uncompressed":{"deletes":[],"inserts":[$row]}}]}]}}}""", "ticket", "pixel")
    val inbox = TicketCommandInbox()
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED, emptyList()))
    inbox.apply(message)
    assertEquals("cold_stop", inbox.snapshot()?.single()?.commandType)
    assertEquals("operation", inbox.snapshot()?.single()?.revision)
  }

  @Test
  fun recordedTerminalOutcomeSurvivesAnAcknowledgementRetry() {
    val results = TicketCommandResults()
    val outcome = TicketSpacetimeCommandResult(true, "applied", "streaming")
    var executions = 0
    repeat(2) {
      val result = results.peek("command") ?: outcome.also { executions++; results.settle("command", it) }
      assertEquals(outcome, result)
    }
    assertEquals(1, executions)
    results.retain(emptySet())
    assertNull(results.peek("command"))
  }
}
