package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.*
import org.junit.Test

/** Actual JNI compared with the former owner, including the surrounding durable effect order. */
class CheckpointParityTest {
  private fun LegacyTicketActivationCheckpoint.current() = TicketActivationCheckpoint(
    commandId, interactionRevision, activationAttemptId, activationRevision, dispatchOrdinal,
    TicketActivationCheckpointStage.valueOf(stage.name)
  )
  private fun checkpoint(stage: LegacyTicketActivationCheckpointStage, ordinal: Int) =
    LegacyTicketActivationCheckpoint("command-🌿", "pc-Ω:1", "attempt-𐐀", "activation", ordinal, stage)

  private fun outcome(block: () -> Any?): Any? = try { block() } catch (e: IllegalArgumentException) {
    listOf(e.javaClass.name, e.message)
  }

  @Test fun transitionAndRejectionParityIncludesCommitLossReadbackAndIdentity() {
    for (stage in LegacyTicketActivationCheckpointStage.entries) for (ordinal in listOf(-1, 0, 1, 2, 3)) {
      val prior = checkpoint(stage, ordinal)
      for (mode in listOf("normal", "commit_failed", "ack_lost", "readback_mismatch", "absent")) {
        for (operation in listOf("first", "second", "zero", "third", "proven", "no_transition", "attention", "clear", "wrong_clear", "load", "wrong_load")) {
          val oldBackend = LegacyBackend(prior.takeUnless { mode == "absent" }, mode)
          val backend = Backend(prior.current().takeUnless { mode == "absent" }, mode)
          val old = LegacyTicketActivationCheckpointStore(oldBackend)
          val now = TicketActivationCheckpointStore(backend)
          val expected = outcome { when (operation) {
            "first" -> old.recordActivationDispatching(prior, 1)?.current()
            "second" -> old.recordActivationDispatching(prior, 2)?.current()
            "zero" -> old.recordActivationDispatching(prior, 0)?.current()
            "third" -> old.recordActivationDispatching(prior, 3)?.current()
            "proven" -> old.recordActivationProven(prior, "\u001c activation Ω \u00a0")?.current()
            "no_transition" -> old.recordNoTransitionProven(prior)?.current()
            "attention" -> old.recordNeedsAttention(prior)?.current()
            "clear" -> old.clearIfMatches(" command-🌿\n", "attempt-𐐀 ")
            "wrong_clear" -> old.clearIfMatches("command-🌿", "other")
            "load" -> old.loadFor(" command-🌿\n", "pc-Ω:1\u00a0", "attempt-𐐀 ")?.current()
            else -> old.loadFor("command-🌿", "other", "attempt-𐐀")?.current()
          } }
          val actual = outcome { when (operation) {
            "first" -> now.recordActivationDispatching(prior.current(), 1)
            "second" -> now.recordActivationDispatching(prior.current(), 2)
            "zero" -> now.recordActivationDispatching(prior.current(), 0)
            "third" -> now.recordActivationDispatching(prior.current(), 3)
            "proven" -> now.recordActivationProven(prior.current(), "\u001c activation Ω \u00a0")
            "no_transition" -> now.recordNoTransitionProven(prior.current())
            "attention" -> now.recordNeedsAttention(prior.current())
            "clear" -> now.clearIfMatches(" command-🌿\n", "attempt-𐐀 ")
            "wrong_clear" -> now.clearIfMatches("command-🌿", "other")
            "load" -> now.loadFor(" command-🌿\n", "pc-Ω:1\u00a0", "attempt-𐐀 ")
            else -> now.loadFor("command-🌿", "other", "attempt-𐐀")
          } }
          val label = "$stage/$ordinal/$mode/$operation"
          assertEquals(label, expected, actual)
          assertEquals("$label effects", oldBackend.calls, backend.calls)
          assertEquals("$label retained state", oldBackend.value?.current(), backend.value)
        }
      }
    }
    for (id in listOf("command", " 🌿Ω𐐀 ", "", "\u001f\u00a0")) {
      val old = LegacyTicketActivationCheckpointStore(LegacyBackend(null, "normal"))
      val now = TicketActivationCheckpointStore(Backend(null, "normal"))
      assertEquals(outcome { old.recordFreshTicketProven(id, id, id)?.current() },
        outcome { now.recordFreshTicketProven(id, id, id) })
    }
  }

  @Test fun terminalRetirementAndFailurePhaseParityProtectsEveryFence() {
    for (stage in LegacyTicketActivationCheckpointStage.entries) for (ordinal in listOf(-1, 0, 1, 2, 3)) {
      val prior = checkpoint(stage, ordinal)
      val success = TicketVisualActionSnapshot(actionId = prior.activationAttemptId,
        status = "succeeded", phase = "activation_proven", currentView = TicketVisualActionView.ACTIVATED_CURRENT,
        reason = "ticket_action_registered", completedAt = "2026-09-29T00:00:00Z",
        interactionRevision = prior.interactionRevision, activationRevision = "activation",
        activationAttemptId = prior.activationAttemptId, terminal = true, ok = true)
      val failure = success.copy(ok = false, status = "needs_attention", phase = "not_dispatched", activationRevision = "")
      val noTransition = failure.copy(phase = if (ordinal >= 2) "no_transition" else "retry_not_dispatched",
        reason = if (ordinal >= 2) "ticket_action_gesture_completed_no_transition" else "ticket_action_retry_not_dispatched",
        currentView = TicketVisualActionView.LATEST_UNACTIVATED)
      for (candidate in listOf(success, failure, noTransition)) {
        val variants = listOf(candidate, candidate.copy(actionId = "other"), candidate.copy(activationAttemptId = "other"),
          candidate.copy(terminal = false), candidate.copy(completedAt = "\u001f\u00a0"),
          candidate.copy(ok = !candidate.ok), candidate.copy(status = "failed"), candidate.copy(phase = "outcome_unknown"),
          candidate.copy(reason = "other"), candidate.copy(currentView = TicketVisualActionView.UNKNOWN),
          candidate.copy(interactionRevision = "other"), candidate.copy(activationRevision = "other"))
        for (action in variants) for (command in listOf(prior.commandId, "other")) {
          assertEquals(legacyTicketActivationCheckpointSafeToClearAfterTerminalFinalization(prior, command, action),
            ticketActivationCheckpointSafeToClearAfterTerminalFinalization(prior.current(), command, action))
        }
      }
      for (phase in listOf("", "outcome_unknown", "navigation_dispatched")) {
        assertEquals(legacyTicketActivationFailureTerminalPhase(prior, phase),
          ticketActivationFailureTerminalPhase(prior.current(), phase))
      }
      assertEquals(outcome { legacyTicketActivationNoTransitionTerminalPhase(prior) },
        outcome { ticketActivationNoTransitionTerminalPhase(prior.current()) })
      assertEquals(outcome { legacyTicketActivationNoTransitionTerminalReason(prior) },
        outcome { ticketActivationNoTransitionTerminalReason(prior.current()) })
    }
    assertEquals("not_dispatched", ticketActivationFailureTerminalPhase(null))
    assertEquals("outcome_unknown", ticketActivationFailureTerminalPhase(null, "outcome_unknown"))
  }

  @Test fun lostDispatchAcknowledgementRemainsDurableAndCannotAdmitAnotherStroke() {
    val backend = Backend(null, "normal")
    val store = TicketActivationCheckpointStore(backend)
    val fresh = requireNotNull(store.recordFreshTicketProven("command", "revision", "attempt"))
    backend.mode = "ack_lost"
    assertNull(store.recordActivationDispatching(fresh, 1))
    val restarted = TicketActivationCheckpointStore(backend)
    val retained = requireNotNull(restarted.loadFor("command", "revision", "attempt"))
    assertEquals(TicketActivationCheckpointStage.ACTIVATION_DISPATCHING, retained.stage)
    assertThrows(IllegalArgumentException::class.java) { restarted.recordActivationDispatching(retained, 1) }
    assertThrows(IllegalArgumentException::class.java) { restarted.recordActivationDispatching(retained, 2) }
    assertNull(restarted.recordFreshTicketProven("replacement", "revision", "replacement"))
    assertEquals("outcome_unknown", ticketActivationFailureTerminalPhase(retained))
    assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(retained, "command",
      TicketVisualActionSnapshot(actionId = "attempt", activationAttemptId = "attempt", terminal = true,
        completedAt = "2026-09-29T00:00:00Z", status = "needs_attention", phase = "outcome_unknown")))
  }

  private class Backend(var value: TicketActivationCheckpoint?, var mode: String) : TicketActivationCheckpointBackend {
    val calls = mutableListOf<String>()
    override fun load(): TicketActivationCheckpoint? { calls += "load"; return value }
    override fun save(checkpoint: TicketActivationCheckpoint): Boolean {
      calls += "save"
      if (mode != "commit_failed" && mode != "readback_mismatch") value = checkpoint
      return mode != "commit_failed" && mode != "ack_lost"
    }
    override fun clear(): Boolean { calls += "clear"; if (mode != "commit_failed") value = null; return mode != "commit_failed" }
  }
  private class LegacyBackend(var value: LegacyTicketActivationCheckpoint?, val mode: String) : LegacyTicketActivationCheckpointBackend {
    val calls = mutableListOf<String>()
    override fun load(): LegacyTicketActivationCheckpoint? { calls += "load"; return value }
    override fun save(checkpoint: LegacyTicketActivationCheckpoint): Boolean {
      calls += "save"
      if (mode != "commit_failed" && mode != "readback_mismatch") value = checkpoint
      return mode != "commit_failed" && mode != "ack_lost"
    }
    override fun clear(): Boolean { calls += "clear"; if (mode != "commit_failed") value = null; return mode != "commit_failed" }
  }
}
