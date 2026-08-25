package lv.jolkins.pixelorchestrator.app.ticket

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketActionPanelDarkLeaseTest {
  @Test
  fun leaseHoldsAcrossAnActionAndAlwaysCancelsItsRootHelperOnRelease() = runTest {
    val helper = HoldingRootExecutor()
    val verifier = ZeroRootExecutor()
    val snapshots = mutableListOf<TicketActionPanelDarkLeaseSnapshot>()
    var now = 1_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-1",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(available = true, active = false, observedAtUptimeMillis = now)
      },
      onSnapshotChanged = snapshots::add,
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    assertTrue(helper.started.isCompleted)
    assertTrue(lease.beforeMutationAllowed())
    lease.markMutationMayHaveDispatched()

    lease.release("terminal_complete")
    runCurrent()

    assertTrue(helper.cancelled)
    assertFalse(helper.active)
    assertEquals(1, verifier.stopCalls)
    assertFalse(lease.snapshot().active)
    assertTrue(lease.snapshot().mutationMayHaveDispatched)
    assertEquals("terminal_complete", lease.snapshot().releaseReason)
    assertTrue(snapshots.any { it.active && it.lastZeroConfirmedAtUptimeMillis == 1_000L })
  }

  @Test
  fun physicalRootTouchPreemptsTheHelperAndBlocksTheNextMutationBoundary() = runTest {
    val helper = HoldingRootExecutor()
    var now = 2_000L
    var touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-touch",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = now + 1
    )

    // The synchronous mutation-boundary read wins even before the 20 ms monitor wakes.
    assertFalse(lease.beforeMutationAllowed())
    runCurrent()
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_at_mutation_boundary", lease.snapshot().releaseReason)
    assertTrue(helper.cancelled)
    assertFalse(helper.active)

    lease.release("ignored_after_preemption")
    assertEquals("physical_touch_at_mutation_boundary", lease.snapshot().releaseReason)
  }

  @Test
  fun monitorPreemptsARealTouchDuringVisualWaitingWithoutWaitingForMutation() = runTest {
    val helper = HoldingRootExecutor()
    var now = 3_000L
    var touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-wait",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { touch },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now }
    )

    assertTrue(lease.acquire())
    runCurrent()
    touch = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = now + 1
    )
    now += TicketActionPanelDarkLease.PHYSICAL_TOUCH_POLL_MILLIS
    advanceTimeBy(TicketActionPanelDarkLease.PHYSICAL_TOUCH_POLL_MILLIS)
    runCurrent()

    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals("physical_touch_preempted", lease.snapshot().releaseReason)
    assertTrue(helper.cancelled)
    assertFalse(helper.active)
    lease.release("test_complete")
  }

  @Test
  fun activeOrUnreadyPhysicalTouchSourceFailsClosedBeforeStartingAHelper() = runTest {
    suspend fun acquireFor(state: PhoneAutomationRootPhysicalTouchState): Pair<Boolean, HoldingRootExecutor> {
      val helper = HoldingRootExecutor()
      val lease = TicketActionPanelDarkLease(
        actionId = "action-blocked",
        scope = backgroundScope,
        clampRootExecutor = helper,
        verifyRootExecutor = ZeroRootExecutor(),
        physicalTouchState = { state },
        onSnapshotChanged = {},
        ownerProcessId = 4321,
        uptimeClock = { 4_000L }
      )
      return lease.acquire() to helper
    }

    val active = acquireFor(PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = true,
      observedAtUptimeMillis = 4_000L
    ))
    assertFalse(active.first)
    assertFalse(active.second.started.isCompleted)

    val unready = acquireFor(PhoneAutomationRootPhysicalTouchState())
    assertFalse(unready.first)
    assertFalse(unready.second.started.isCompleted)
  }

  @Test
  fun acquisitionWaitsForHelperReadinessAndTwoDistinctZeroConfirmations() = runTest {
    val helper = HoldingRootExecutor()
    val verifier = SequencedVerifyRootExecutor(listOf(
      "helper_ready=0\npanel_dark=1\n",
      "helper_ready=1\npanel_dark=1\n",
      "helper_ready=1\npanel_dark=1\n"
    ))
    val lease = TicketActionPanelDarkLease(
      actionId = "action-readiness",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = false,
          observedAtUptimeMillis = 5_000L
        )
      },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { 5_000L },
      helperToken = "test-readiness-token"
    )

    assertTrue(lease.acquire())
    assertEquals(3, verifier.calls)
    assertTrue(helper.started.isCompleted)
    assertTrue(lease.snapshot().active)

    lease.release("test_complete")
  }

  @Test
  fun acquisitionFailsClosedWhenBrightnessIsZeroButHelperNeverBecomesReady() = runTest {
    val helper = HoldingRootExecutor()
    val verifier = SequencedVerifyRootExecutor(listOf("helper_ready=0\npanel_dark=1\n"))
    var now = 6_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-no-helper",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = false,
          observedAtUptimeMillis = now
        )
      },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now.also { now += TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_POLL_MILLIS } },
      helperToken = "test-never-ready-token"
    )

    assertFalse(lease.acquire())
    runCurrent()

    assertTrue(verifier.calls > 1)
    assertTrue(helper.cancelled)
    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_helper_unready_or_zero_unproved", lease.snapshot().failure)
    assertEquals("acquire_failed", lease.snapshot().releaseReason)
  }

  @Test
  fun rootHelperIsOwnerBoundAndStartupCleanupTargetsOnlyItsExactMarker() {
    val helperToken = "test-helper-token"
    val helper = TicketActionPanelDarkLease.panelDarkHoldScript(4321, helperToken)
    val readiness = TicketActionPanelDarkLease.panelDarkVerifyScript(helperToken)
    val cleanup = TicketActionPanelDarkLease.CLEANUP_STALE_HELPERS_SCRIPT

    assertTrue(helper.contains("owner_pid=4321"))
    assertTrue(helper.contains("/proc/\$owner_pid/stat"))
    assertTrue(helper.contains("owner_start"))
    assertTrue(helper.contains("shift 19"))
    assertTrue(helper.contains(TicketActionPanelDarkLease.HELPER_PROCESS_MARKER))
    assertTrue(helper.contains(helperToken))
    assertTrue(helper.contains(TicketActionPanelDarkLease.HELPER_READINESS_DIRECTORY))
    assertTrue(helper.contains("trap \"cleanup_readiness; exit 0\" HUP INT TERM"))
    assertTrue(helper.indexOf("echo 0 >") < helper.indexOf("printf \"helper_pid="))
    assertFalse(helper.contains("/data/local/tmp"))

    assertTrue(readiness.contains("$helperToken.ready"))
    assertTrue(readiness.contains("grep -Fzqx '${TicketActionPanelDarkLease.HELPER_PROCESS_MARKER}'"))
    assertTrue(readiness.contains("grep -Fzqx \"\$helper_token\""))
    assertTrue(readiness.contains("read_proc_start \"\$helper_pid\""))
    assertTrue(readiness.contains("read_proc_start \"\$owner_pid\""))
    assertTrue(readiness.contains("echo helper_ready="))
    assertTrue(readiness.contains("echo panel_dark="))

    assertTrue(cleanup.contains("/*.ready"))
    assertTrue(cleanup.contains("candidate_count="))
    assertTrue(cleanup.contains("-gt ${TicketActionPanelDarkLease.MAX_HELPER_READINESS_FILES}"))
    assertTrue(cleanup.indexOf("candidate_limit_exceeded=") < cleanup.indexOf("kill -TERM"))
    assertTrue(cleanup.contains("grep -Fzqx '${TicketActionPanelDarkLease.HELPER_PROCESS_MARKER}'"))
    assertTrue(cleanup.contains("kill -TERM"))
    assertTrue(cleanup.contains("cleaned="))
    assertFalse(readiness.contains("/proc/[0-9]*/cmdline"))
    assertFalse(cleanup.contains("/proc/[0-9]*/cmdline"))
    assertFalse(readiness.contains("pidof sh"))
    assertFalse(cleanup.contains("pidof sh"))
  }

  @Test
  fun releaseStopsExactHelperBeforeJoiningACancellationIgnoringRootCommand() = runTest {
    val stopSignal = CompletableDeferred<Unit>()
    val helper = NonCancellableHoldingRootExecutor(stopSignal)
    val verifier = StopSignallingVerifyRootExecutor(stopSignal)
    val lease = TicketActionPanelDarkLease(
      actionId = "action-noncancellable-helper",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = false,
          observedAtUptimeMillis = 7_000L
        )
      },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { 7_000L },
      helperToken = "exact-release-token"
    )

    assertTrue(lease.acquire())
    runCurrent()
    assertTrue(helper.active)

    lease.release("terminal_complete")
    runCurrent()

    assertEquals(1, verifier.stopCalls)
    assertTrue(verifier.helperWasActiveWhenStopRequested)
    assertTrue(helper.completed.isCompleted)
    assertFalse(helper.active)
    assertFalse(lease.snapshot().active)
    assertEquals("terminal_complete", lease.snapshot().releaseReason)
    assertEquals("", lease.snapshot().failure)
  }

  @Test
  fun exactStopScriptValidatesIdentityThenRemovesOnlyItsReadinessFile() {
    val helperToken = "exact-stop-token"
    val stop = TicketActionPanelDarkLease.panelDarkStopScript(helperToken)

    assertTrue(stop.contains("$helperToken.ready"))
    assertTrue(stop.contains("read_proc_start \"\$helper_pid\""))
    assertTrue(stop.contains("grep -Fzqx '${TicketActionPanelDarkLease.HELPER_PROCESS_MARKER}'"))
    assertTrue(stop.contains("grep -Fzqx \"\$helper_token\""))
    assertTrue(stop.contains("grep -Fzqx \"\$owner_pid\""))
    assertTrue(stop.contains("grep -Fzqx \"\$owner_start\""))
    assertTrue(stop.indexOf("exact_helper_matches || stop_failed") < stop.indexOf("kill -TERM"))
    assertTrue(stop.contains("kill -KILL"))
    assertTrue(stop.contains("rm -f \"\$readiness_file\""))
    assertTrue(stop.contains("[ ! -e \"\$readiness_file\" ]"))
    assertTrue(stop.contains("echo helper_stopped=1"))
    assertTrue(stop.contains("echo readiness_removed=1"))
    assertFalse(stop.contains("/proc/[0-9]*/cmdline"))
    assertFalse(stop.contains("pidof"))
  }

  @Test
  fun unprovedExactStopFailsClosedEvenWhenCoroutineCancellationCompletes() = runTest {
    val helper = HoldingRootExecutor()
    val verifier = FailingStopVerifyRootExecutor()
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stop-unproved",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = {
        PhoneAutomationRootPhysicalTouchState(
          available = true,
          active = false,
          observedAtUptimeMillis = 8_000L
        )
      },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { 8_000L },
      helperToken = "unproved-stop-token"
    )

    assertTrue(lease.acquire())
    lease.release("terminal_complete")
    runCurrent()

    assertTrue(helper.cancelled)
    assertTrue(verifier.stopCalls >= 1)
    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_helper_stop_unproved", lease.snapshot().failure)
    assertEquals("panel_dark_helper_stop_unproved", lease.snapshot().releaseReason)
  }

  @Test
  fun oneUnavailableVerificationKeepsARecentlyProvenLeaseActiveAndCanRecover() = runTest {
    val helper = HoldingRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(
        provenZeroResult(),
        provenZeroResult(),
        unavailableResult(),
        provenZeroResult()
      )
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-transient-verifier",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 10_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 10_000L },
      helperToken = "transient-verifier-token"
    )

    assertTrue(lease.acquire())
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS)
    runCurrent()
    assertTrue(lease.snapshot().active)
    assertEquals("", lease.snapshot().failure)

    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS)
    runCurrent()
    assertTrue(lease.snapshot().active)
    assertEquals(testScheduler.currentTime + 10_000L, lease.snapshot().lastZeroConfirmedAtUptimeMillis)

    lease.release("test_complete")
  }

  @Test
  fun verifierUnavailabilityFailsClosedOnceTheLastZeroProofIsStale() = runTest {
    val helper = HoldingRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(provenZeroResult(), provenZeroResult(), unavailableResult())
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-verifier",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 20_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 20_000L },
      helperToken = "stale-verifier-token"
    )

    assertTrue(lease.acquire())
    advanceTimeBy(
      TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS +
        TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS + 1L
    )
    runCurrent()

    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_verifier_unavailable", lease.snapshot().failure)
    assertEquals("panel_dark_zero_proof_stale", lease.snapshot().releaseReason)
    assertTrue(helper.cancelled)
    lease.release("test_complete")
  }

  @Test
  fun aDefinitiveNonzeroPanelReadingStillFailsImmediately() = runTest {
    val helper = HoldingRootExecutor()
    val verifier = ResultSequencedVerifyRootExecutor(
      listOf(provenZeroResult(), provenZeroResult(), nonzeroPanelResult())
    )
    val lease = TicketActionPanelDarkLease(
      actionId = "action-panel-nonzero",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = verifier,
      physicalTouchState = { readyNoTouch(testScheduler.currentTime + 30_000L) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { testScheduler.currentTime + 30_000L },
      helperToken = "panel-nonzero-token"
    )

    assertTrue(lease.acquire())
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_VERIFY_INTERVAL_MILLIS)
    runCurrent()

    assertFalse(lease.snapshot().active)
    assertEquals("panel_dark_zero_lost", lease.snapshot().failure)
    assertEquals("panel_dark_zero_lost", lease.snapshot().releaseReason)
    assertTrue(helper.cancelled)
    lease.release("test_complete")
  }

  @Test
  fun aStaleZeroProofBlocksTheNextMutationEvenBeforeTheMonitorRuns() = runTest {
    val helper = HoldingRootExecutor()
    var now = 40_000L
    val lease = TicketActionPanelDarkLease(
      actionId = "action-stale-mutation",
      scope = backgroundScope,
      clampRootExecutor = helper,
      verifyRootExecutor = ZeroRootExecutor(),
      physicalTouchState = { readyNoTouch(now) },
      onSnapshotChanged = {},
      ownerProcessId = 4321,
      uptimeClock = { now },
      helperToken = "stale-mutation-token"
    )

    assertTrue(lease.acquire())
    now += TicketActionPanelDarkLease.PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS + 1L
    assertFalse(lease.beforeMutationAllowed())
    runCurrent()

    assertEquals("panel_dark_verifier_unavailable", lease.snapshot().failure)
    assertEquals("panel_dark_zero_proof_stale_at_mutation_boundary", lease.snapshot().releaseReason)
    assertTrue(helper.cancelled)
    lease.release("test_complete")
  }

  private class HoldingRootExecutor : RootExecutor {
    val started = CompletableDeferred<Unit>()
    var active = false
    var cancelled = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      active = true
      started.complete(Unit)
      try {
        awaitCancellation()
      } finally {
        active = false
        cancelled = true
      }
    }
  }

  private class ZeroRootExecutor : RootExecutor {
    var stopCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) stopCalls += 1
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class SequencedVerifyRootExecutor(
    private val outputs: List<String>
  ) : RootExecutor {
    var calls: Int = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      val output = outputs.getOrElse(calls) { outputs.last() }
      calls += 1
      return RootResult(
        exitCode = 0,
        stdout = output,
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class NonCancellableHoldingRootExecutor(
    private val stopSignal: CompletableDeferred<Unit>
  ) : RootExecutor {
    val started = CompletableDeferred<Unit>()
    val completed = CompletableDeferred<Unit>()
    var active = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      active = true
      started.complete(Unit)
      return try {
        withContext(NonCancellable) {
          stopSignal.await()
        }
        RootResult(
          exitCode = 0,
          stdout = "",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      } finally {
        active = false
        completed.complete(Unit)
      }
    }
  }

  private class StopSignallingVerifyRootExecutor(
    private val stopSignal: CompletableDeferred<Unit>
  ) : RootExecutor {
    var stopCalls = 0
    var helperWasActiveWhenStopRequested = false

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) {
        stopCalls += 1
        helperWasActiveWhenStopRequested = !stopSignal.isCompleted
        stopSignal.complete(Unit)
      }
      return RootResult(
        exitCode = 0,
        stdout = if (isStop) {
          "helper_stopped=1\nreadiness_removed=1\n"
        } else {
          "helper_ready=1\npanel_dark=1\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class FailingStopVerifyRootExecutor : RootExecutor {
    var stopCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      val isStop = script.contains("echo helper_stopped=1")
      if (isStop) stopCalls += 1
      return RootResult(
        exitCode = if (isStop) 74 else 0,
        stdout = if (isStop) {
          "helper_stopped=0\nreadiness_removed=0\n"
        } else {
          "helper_ready=1\npanel_dark=1\n"
        },
        stderr = "",
        command = "script",
        durationMs = 0L
      )
    }
  }

  private class ResultSequencedVerifyRootExecutor(
    private val results: List<RootResult>
  ) : RootExecutor {
    private var verificationCalls = 0

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: Duration): RootResult =
      error("run should not be called")

    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      if (script.contains("echo helper_stopped=1")) {
        return RootResult(
          exitCode = 0,
          stdout = "helper_stopped=1\nreadiness_removed=1\n",
          stderr = "",
          command = "script",
          durationMs = 0L
        )
      }
      return results.getOrElse(verificationCalls++) { results.last() }
    }
  }

  private companion object {
    fun readyNoTouch(now: Long) = PhoneAutomationRootPhysicalTouchState(
      available = true,
      active = false,
      observedAtUptimeMillis = now
    )

    fun provenZeroResult() = RootResult(
      exitCode = 0,
      stdout = "helper_ready=1\npanel_dark=1\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )

    fun unavailableResult() = RootResult(
      exitCode = 124,
      stdout = "",
      stderr = "root command timed out",
      command = "script",
      durationMs = TicketActionPanelDarkLease.PANEL_DARK_VERIFY_TIMEOUT_MILLIS
    )

    fun nonzeroPanelResult() = RootResult(
      exitCode = 1,
      stdout = "helper_ready=1\npanel_dark=0\n",
      stderr = "",
      command = "script",
      durationMs = 1L
    )
  }

}
