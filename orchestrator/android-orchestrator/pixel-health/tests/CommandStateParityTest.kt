package lv.jolkins.pixelorchestrator.app.ticket

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CommandStateParityTest {
  private fun command(id: String, kind: String = "start", expiry: String = "2099-01-01T00:00:00Z") =
    TicketSpacetimeCommand(id,"ticket","pixel",kind,"pending","revision","reason","{}","date","date",expiry)
  @Test fun commandDeadlinePriorityAndTerminalAcknowledgementsMatchPriorOwner() {
    var comparisons = 0
    val expiries = listOf("", " ", "invalid", "2026-09-29T23:45:00Z", "2026-09-29T23:45:00.123456789Z",
      "2026-09-29T24:00:00Z", "2026-09-29T23:59:60Z", "2026-09-29T23:45:00+18:00", "2026-09-29T23:45:00+18:00:01",
      "2026-09-29t23:45:00z", "\u20072026-09-29T23:45:00Z\u2007", "-1000000000-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z")
    val times = listOf(Instant.MIN, Instant.MAX, Instant.EPOCH, Instant.parse("2026-09-29T23:45:00Z"), Instant.parse("2026-09-29T23:45:00.123456788Z"),Instant.parse("2026-09-29T23:45:00.123456789Z"))
    for (expiry in expiries) for (now in times) {
      assertEquals("$expiry $now",legacyCommandExpired(expiry,now),ticketSpacetimeCommandExpired(expiry,now)); comparisons++
      for (type in listOf("start","cold_stop","ticket_action_v3")) for (remote in listOf(false,true)) {
        assertEquals(legacyStartDispatch(type,remote,expiry,now),shouldDispatchRevalidatedStartCommand(type,remote,expiry,now)); comparisons++
      }
    }
    for (kind in listOf("start","cold_stop","ticket_action_v3","vivi_reauth","generate_control_code","control_code_browser_capture")) {
      for (terminal in listOf(false,true)) for (ok in listOf(false,true)) for (action in listOf(false,true)) for (reauth in listOf(false,true)) for (reason in listOf(""," \t\u2007","safe_reason")) {
        val result = TicketSpacetimeCommandResult(ok,reason,"streaming",terminal,
          if(action) TicketVisualActionSnapshot() else null, if(reauth) TicketViviReauthSnapshot() else null)
        assertEquals(legacyAck(command("a",kind),result),ticketCommandAcknowledgement(kind,result)); comparisons++
      }
    }
    val commands = listOf(command("b","ticket_action_v3"),command("a"),command("d","cold_stop"),command("c"))
    for (active in listOf(false,true)) { assertEquals(legacyPrioritize(commands,active),prioritizePendingCommandsForStreamState(commands,active)); comparisons++ }
    println("COMMAND_POLICY_PARITY_OK comparisons=$comparisons")
  }
  @Test fun inboxAuthorityOrderCapacityAndDisconnectMatchPriorOwner() {
    val old = LegacyCommandInbox(); val next = TicketCommandInbox()
    var comparisons = 0
    fun compare() {
      assertEquals(old.snapshot(),next.snapshot()); assertEquals(old.controlSnapshot(),next.controlSnapshot())
      for (id in listOf("a","b","c","0","127","128")) {
        assertEquals(old.contains(command(id)),next.contains(command(id)))
        assertEquals(old.contains(command(id).copy(revision="changed")),next.contains(command(id).copy(revision="changed")))
      }
      comparisons += 14
    }
    fun apply(message: TicketSpacetimeCommandSubscriptionMessage) {
      val previous = runCatching { old.apply(message) }; val result = runCatching { next.apply(message) }
      assertEquals(previous.exceptionOrNull()?.message,result.exceptionOrNull()?.message); compare()
    }
    compare()
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.UPDATE,listOf(command("a"))))
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED,listOf(command("a"),command("b"),command("c")),monitoring=TicketMonitoringConfig(true,"epoch")))
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.UPDATE,listOf(command("a").copy(reason="replacement")),deleted=listOf("b")))
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.UPDATE,listOf(command("b")),monitoringDeleted=true))
    old.disconnected(); next.disconnected(); compare()
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED,(0..127).map { command(it.toString()) }))
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.UPDATE,listOf(command("128"))))
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.UPDATE,deleted=listOf("0")))
    apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED,listOf(command("expired",expiry="2000-01-01T00:00:00Z"),command("invalid",expiry="bad"))))
    println("COMMAND_INBOX_PARITY_OK comparisons=$comparisons")
  }
}
