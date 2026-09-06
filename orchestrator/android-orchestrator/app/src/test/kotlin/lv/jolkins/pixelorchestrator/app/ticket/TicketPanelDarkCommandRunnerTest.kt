package lv.jolkins.pixelorchestrator.app.ticket

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketPanelDarkCommandRunnerTest {
  @Test
  fun physicalRevealKeepsDispatchedCommandAndWaitsForFingerUpWithoutReplay() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.borrowAcquired()
    var dispatches = 0
    var commandFinished = false
    val running = async {
      fixture.runner.run("reveal_running") {
        dispatches += 1
        delay(100L)
        fixture.window = TicketPhysicalVisibleWindow(testScheduler.currentTime + 91_000L, 1L)
        fixture.touch = fixture.touch.copy(active = true, touchBeginCount = 1L)
        delay(100L)
        commandFinished = true
        result(0)
      }
    }
    advanceTimeBy(500L)
    runCurrent()
    assertTrue(commandFinished)
    assertFalse(running.isCompleted)
    assertEquals(1, dispatches)
    assertFalse(lease.snapshot().physicalTouchPreempted)
    fixture.touch = fixture.touch.copy(active = false)
    runCurrent()
    assertTrue(running.await().ok)
    assertSame(lease, fixture.active)
    assertEquals("visible_window", lease.snapshot().protectionMode)
    assertEquals(1, dispatches)
    lease.release("test_done")
  }

  @Test
  fun visibleStandaloneCommandDoesNotCreateADarkHelper() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow(91_000L, 1L)
    assertTrue(fixture.runner.run("visible") { result(0) }.ok)
    assertEquals(0, fixture.root.launches)
    assertEquals(0, fixture.root.stops)
    assertEquals("visible_window", fixture.created.single().snapshot().protectionMode)
  }

  @Test
  fun longVisibleCommandKeepsRunningAsExpiryAcquiresItsSingleDarkHelper() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow(1_200L, 1L)
    var completed = false
    val result = fixture.runner.run("expires") {
      delay(600L)
      completed = true
      result(0)
    }
    assertTrue(result.ok)
    assertTrue(completed)
    assertEquals(1, fixture.root.launches)
    assertEquals(1, fixture.root.stops)
    assertEquals("dark", fixture.created.single().snapshot().protectionMode)
  }

  @Test
  fun failedExpiryTransitionCancelsTheAlreadyRunningVisibleCommand() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow(91_000L, 1L)
    var stopped = false
    val result = fixture.runner.run("expiry_failed") {
      fixture.root.helperPresent = false
      fixture.window = fixture.window.copy(deadlineUptimeMillis = 0L)
      try { awaitCancellation() } finally { stopped = true }
    }
    assertEquals(46, result.exitCode)
    assertTrue(stopped)
    assertEquals(1, fixture.root.launches)
  }

  @Test
  fun standaloneSuccessAcquiresOnceAndFinalizesBeforeReturning() = runTest {
    val fixture = Fixture(this)
    val result = fixture.runner.run("wake") { result(0, "command output") }
    assertEquals("command output", result.stdout)
    assertEquals(1, fixture.root.launches)
    assertTrue(fixture.root.stops > 0)
    assertNull(fixture.active)
    assertTrue(fixture.created.single().snapshot().mutationMayHaveDispatched)
  }

  @Test
  fun standaloneRootFailureIsPreservedAfterSafeFinalization() = runTest {
    val fixture = Fixture(this)
    assertEquals(124, fixture.runner.run("timeout") { result(124) }.exitCode)
    assertNull(fixture.active)
    assertTrue(fixture.root.stops > 0)
  }

  @Test
  fun longBulkCommandGetsFullConvergenceAfterItsLastPossibleEffect() = runTest {
    val fixture = Fixture(this)
    var commandFinishedAt = 0L
    assertTrue(fixture.runner.run("bulk") {
      delay(5_000L)
      commandFinishedAt = testScheduler.currentTime
      result(0)
    }.ok)
    assertTrue(testScheduler.currentTime - commandFinishedAt >=
      TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS)
  }

  @Test
  fun canceledBulkCommandGetsFullConvergenceAfterItQuiesces() = runTest {
    val fixture = Fixture(this)
    val entered = CompletableDeferred<Unit>()
    var quiescedAt = 0L
    val running = async {
      fixture.runner.run("bulk_cancel") {
        entered.complete(Unit)
        try { awaitCancellation() } finally { quiescedAt = testScheduler.currentTime }
      }
    }
    entered.await()
    delay(5_000L)
    running.cancelAndJoin()
    assertTrue(testScheduler.currentTime - quiescedAt >=
      TicketActionPanelDarkLease.PANEL_DARK_FINAL_CONVERGENCE_MILLIS)
    assertNull(fixture.active)
  }

  @Test
  fun failedAcquisitionDoesNotDispatch() = runTest {
    val fixture = Fixture(this)
    fixture.touch = fixture.touch.copy(active = true)
    assertEquals(46, fixture.runner.run("blocked") { error("must not dispatch") }.exitCode)
    assertEquals(0, fixture.root.launches)
    assertNull(fixture.active)
  }

  @Test
  fun borrowedLeaseRemainsActiveWithoutAnotherLaunchOrStop() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.borrowAcquired()
    assertTrue(fixture.runner.run("borrowed") { result(0) }.ok)
    assertSame(lease, fixture.active)
    assertTrue(lease.snapshot().active)
    assertEquals(1, fixture.root.launches)
    assertEquals(0, fixture.root.stops)
    lease.release("test_done")
  }

  @Test
  fun failedBorrowedGateDoesNotDispatchOrReacquire() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.borrowAcquired()
    fixture.touch = fixture.touch.copy(touchBeginCount = 1L)
    assertEquals(46, fixture.runner.run("blocked") { error("must not dispatch") }.exitCode)
    assertEquals(1, fixture.root.launches)
    assertSame(lease, fixture.active)
    lease.release("test_done")
  }

  @Test
  fun cancellationJoinsCommandAndFinalizesOwnedLease() = runTest {
    val fixture = Fixture(this)
    val entered = CompletableDeferred<Unit>()
    var commandStopped = false
    val running = async {
      fixture.runner.run("cancel") {
        entered.complete(Unit)
        try { awaitCancellation() } finally { commandStopped = true }
      }
    }
    entered.await()
    running.cancelAndJoin()
    assertTrue(running.isCancelled)
    assertTrue(commandStopped)
    assertNull(fixture.active)
    assertTrue(fixture.root.stops > 0)
  }

  @Test
  fun cancellationWhileAcquiringStillClearsAndStopsOwnedHelper() = runTest {
    val fixture = Fixture(this)
    fixture.root.pauseVerification = true
    val running = async {
      fixture.runner.run("cancel_acquire") { error("must not dispatch") }
    }
    fixture.root.verificationEntered.await()
    running.cancelAndJoin()
    assertTrue(running.isCancelled)
    assertNull(fixture.active)
    assertTrue(fixture.root.stops > 0)
  }

  @Test
  fun cancellationDoesNotReleaseBorrowedLease() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.borrowAcquired()
    val entered = CompletableDeferred<Unit>()
    val running = async {
      fixture.runner.run("cancel_borrowed") {
        entered.complete(Unit)
        awaitCancellation()
      }
    }
    entered.await()
    running.cancelAndJoin()
    assertSame(lease, fixture.active)
    assertEquals(0, fixture.root.stops)
    assertTrue(lease.snapshot().active)
    lease.release("test_done")
  }

  @Test
  fun completedPhysicalTouchCancelsInflightCommand() = runTest {
    val fixture = Fixture(this)
    var commandStopped = false
    val result = fixture.runner.run("brief_touch") {
      fixture.touch = fixture.touch.copy(active = false, touchBeginCount = 1L)
      try { awaitCancellation() } finally { commandStopped = true }
    }
    assertEquals(46, result.exitCode)
    assertTrue(commandStopped)
    assertTrue(fixture.created.single().snapshot().physicalTouchPreempted)
    assertNull(fixture.active)
  }

  @Test
  fun stalledVerifierCannotDelayPhysicalTouchCancellation() = runTest {
    val fixture = Fixture(this)
    val commandEntered = CompletableDeferred<Unit>()
    var commandStopped = false
    val running = async {
      fixture.runner.run("stalled_verifier") {
        fixture.root.verificationEntered = CompletableDeferred()
        fixture.root.pauseVerification = true
        commandEntered.complete(Unit)
        try { awaitCancellation() } finally { commandStopped = true }
      }
    }
    commandEntered.await()
    fixture.root.verificationEntered.await()
    // Cross the stale-proof threshold while the verifier still owns its root-read mutex.
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 5L)
    runCurrent()
    assertFalse(commandStopped)
    fixture.touch = fixture.touch.copy(active = false, touchBeginCount = 1L)
    advanceTimeBy(5L)
    runCurrent()
    assertTrue("touch cancellation must not wait for root verification", commandStopped)
    assertEquals(46, running.await().exitCode)
  }

  @Test
  fun touchDuringImmediatelyCompletedCommandStillRejectsSuccess() = runTest {
    val fixture = Fixture(this)
    val result = fixture.runner.run("brief_touch") {
      fixture.touch = fixture.touch.copy(active = false, touchBeginCount = 1L)
      result(0, "sensitive output")
    }
    assertEquals(46, result.exitCode)
    assertEquals("", result.stdout)
  }

  @Test
  fun lostHelperCancelsInflightCommand() = runTest {
    val fixture = Fixture(this)
    var commandStopped = false
    val result = fixture.runner.run("lost_helper") {
      fixture.root.helperPresent = false
      try { awaitCancellation() } finally { commandStopped = true }
    }
    assertEquals(46, result.exitCode)
    assertTrue(commandStopped)
    assertNull(fixture.active)
  }

  @Test
  fun unprovedShutdownOverridesSuccessfulCommand() = runTest {
    val fixture = Fixture(this)
    fixture.root.stopProven = false
    assertEquals(46, fixture.runner.run("stop_failed") { result(0) }.exitCode)
    assertNull(fixture.active)
  }

  private class Fixture(private val scope: TestScope) {
    var window = TicketPhysicalVisibleWindow()
    var touch = PhoneAutomationRootPhysicalTouchState(
      available = true, active = false, observedAtUptimeMillis = 1_000L
    )
    val root = FakeRoot()
    var active: TicketActionPanelDarkLease? = null
    val created = mutableListOf<TicketActionPanelDarkLease>()
    val runner = TicketPanelDarkCommandRunner(
      currentLease = { active }, createLease = ::create,
      onOwnedLeaseChanged = { active = it }
    )

    fun create(reason: String) = TicketActionPanelDarkLease(
      actionId = reason, scope = scope.backgroundScope,
      clampRootExecutor = root, verifyRootExecutor = root,
      physicalTouchState = { touch }, onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { scope.testScheduler.currentTime + 1_000L }, physicalVisibleWindow = { window }
    ).also { created += it }

    suspend fun borrowAcquired(): TicketActionPanelDarkLease = create("borrowed").also {
      assertTrue(it.acquire())
      active = it
    }
  }

  private class FakeRoot : RootExecutor {
    var launches = 0
    var stops = 0
    var helperPresent = true
    var stopProven = true
    var pauseVerification = false
    var verificationEntered = CompletableDeferred<Unit>()
    override suspend fun isRootAvailable() = true
    override suspend fun run(command: String, timeout: Duration): RootResult = error("unexpected run")
    override suspend fun runScript(script: String, timeout: Duration): RootResult = when {
      script.contains("echo helper_launched=1") -> {
        launches++
        result(0, "helper_launched=1\n")
      }
      script.contains("echo helper_stopped=1") -> {
        stops++
        if (stopProven) result(0, "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n")
        else result(1)
      }
      helperPresent -> {
        verificationEntered.complete(Unit)
        if (pauseVerification) awaitCancellation()
        result(0, "helper_ready=1\npanel_dark=1\npanel_confirmations=2\n")
      }
      else -> result(0, "helper_ready=0\npanel_dark=1\n")
    }
  }

  private companion object {
    fun result(code: Int, output: String = "") = RootResult(code, output, "", "test", 0L)
  }
}
