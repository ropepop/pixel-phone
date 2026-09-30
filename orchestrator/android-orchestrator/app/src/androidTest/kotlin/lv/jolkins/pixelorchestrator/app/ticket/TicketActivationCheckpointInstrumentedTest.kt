package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Production preferences adapter and installed JNI, using only a named disposable store. */
@RunWith(AndroidJUnit4::class)
class TicketActivationCheckpointInstrumentedTest {
  @Test fun installedCheckpointKeepsUncertainDispatchAcrossProcesses() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val fixtureName = "ticket_checkpoint_native_acceptance"
    val phase = InstrumentationRegistry.getArguments().getString("checkpoint_phase") ?: "roundtrip"
    require(phase in setOf("write", "read", "roundtrip", "cleanup"))
    if (phase == "cleanup") {
      assertTrue(context.deleteSharedPreferences(fixtureName))
      return
    }
    val fixture = context.getSharedPreferences(fixtureName, Context.MODE_PRIVATE)
    val fixtureContext = object : ContextWrapper(context) {
      override fun getApplicationContext(): Context = this
      override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        check(name == "ticket_activation_checkpoint")
        return fixture
      }
    }
    val store = TicketActivationCheckpointStore(fixtureContext)
    val process = android.os.Process.myPid()
    var owned = phase == "read"
    try {
      if (phase != "read") {
        assertTrue("Acceptance fixture must not already contain state", fixture.all.isEmpty())
        owned = true
        val fresh = requireNotNull(store.recordFreshTicketProven("synthetic-command", "pc-synthetic:1", "synthetic-attempt"))
        assertNotNull(store.recordActivationDispatching(fresh, 1))
        assertTrue(fixture.edit().putInt("fixture_process_id", process).commit())
      } else {
        assertNotEquals("Read phase must run in a separate Android process", fixture.getInt("fixture_process_id", -1), process)
      }
      val retained = requireNotNull(store.loadFor("synthetic-command", "pc-synthetic:1", "synthetic-attempt"))
      assertEquals(TicketActivationCheckpointStage.ACTIVATION_DISPATCHING, retained.stage)
      assertEquals(1, retained.dispatchOrdinal)
      assertEquals("activation_dispatching", fixture.getString("stage", null))
      assertEquals("synthetic-command", fixture.getString("command_id", null))
      assertNull(store.recordFreshTicketProven("replacement", "pc-synthetic:2", "replacement"))
      assertThrows(IllegalArgumentException::class.java) { store.recordActivationDispatching(retained, 1) }
      assertThrows(IllegalArgumentException::class.java) { store.recordActivationDispatching(retained, 2) }
      val terminal = TicketVisualActionSnapshot(actionId = "synthetic-attempt", activationAttemptId = "synthetic-attempt",
        terminal = true, completedAt = "2026-09-29T00:00:00Z", status = "needs_attention", phase = "outcome_unknown")
      assertFalse(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(retained, "synthetic-command", terminal))
      assertEquals(retained, store.load())
      if (phase != "write") {
        val proven = requireNotNull(store.recordActivationProven(retained, "synthetic-activation"))
        assertTrue(ticketActivationCheckpointSafeToClearAfterTerminalFinalization(proven, "synthetic-command",
          terminal.copy(status = "succeeded", phase = "activation_proven", ok = true,
            reason = "ticket_action_registered", currentView = TicketVisualActionView.ACTIVATED_CURRENT,
            interactionRevision = "pc-synthetic:1", activationRevision = "synthetic-activation")))
        assertTrue(store.clearIfMatches("synthetic-command", "synthetic-attempt"))
        assertNull(store.load())
      }
      instrumentation.sendStatus(0, Bundle().apply {
        putString("checkpoint_phase", phase)
        putString("checkpoint_process_id", process.toString())
      })
    } finally {
      if (owned && phase != "write") assertTrue(context.deleteSharedPreferences(fixtureName))
    }
  }
}
