package lv.jolkins.pixelorchestrator.app.ticket

import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
class TicketActionPanelVisibleWindowTest {
  @Test
  fun visibleActionPreservesTouchAuthorityWithoutClaimingZeroOrStartingRootWork() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    assertTrue(lease.beforeMutationAllowed())
    assertTrue(lease.ongoingCommandAllowed())
    lease.markMutationMayHaveDispatched()
    val completed = lease.releaseAfterFinalConvergence("done")
    assertTrue(completed.safe)
    assertFalse(completed.freshDarkProven)
    assertEquals("visible_window", completed.snapshot.protectionMode)
    assertEquals(0L, completed.snapshot.lastZeroConfirmedAtUptimeMillis)
    assertEquals(0, fixture.root.calls)
    advanceTimeBy(100_000L)
    runCurrent()
    assertEquals("a released visible action cannot start a late helper", 0, fixture.root.calls)
  }

  @Test
  fun visibleWindowNeverAuthorizesAHeldFingerOrMissingTouchSource() = runTest {
    for (touch in listOf(
      PhoneAutomationRootPhysicalTouchState(true, true, 1_000L),
      PhoneAutomationRootPhysicalTouchState(false, false, 1_000L)
    )) {
      val fixture = Fixture(this)
      fixture.touch = touch
      val lease = fixture.lease()
      assertFalse(lease.acquire())
      assertFalse(lease.releaseAfterFinalConvergence("failed").safe)
      assertEquals(0, fixture.root.calls)
    }
  }

  @Test
  fun completedTapContinuesSameActionInRenewedWindow() = runTest {
    val fixture = Fixture(this)
    val original = fixture.lease()
    assertTrue(original.acquire())
    original.markMutationMayHaveDispatched()
    fixture.touch = fixture.touch.copy(touchBeginCount = 1L)
    fixture.window = fixture.window.copy(generation = 2L)
    assertTrue(original.ongoingCommandAllowed())
    assertTrue(original.beforeMutationAllowed())
    val result = original.releaseAfterFinalConvergence("done")
    assertTrue(result.safe)
    assertTrue(result.snapshot.mutationMayHaveDispatched)
    assertEquals("visible_test", result.snapshot.ownerActionId)
    assertFalse(result.snapshot.physicalTouchPreempted)
    assertEquals(0, fixture.root.calls)
  }

  @Test
  fun darkActionWaitsForExactStopAndFingerUpThenContinuesSameLease() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow()
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    assertEquals(1, fixture.blockers)
    fixture.root.stopGate = CompletableDeferred()
    fixture.touch = fixture.touch.copy(active = true, touchBeginCount = 1L)
    fixture.window = TicketPhysicalVisibleWindow(91_000L, 2L)
    assertTrue(lease.ongoingCommandAllowed())
    val boundary = async { lease.beforeMutationAllowed() }
    runCurrent()
    assertEquals("revealing", lease.snapshot().protectionMode)
    assertFalse(boundary.isCompleted)
    assertEquals(1, fixture.blockers)
    fixture.root.stopGate!!.complete(Unit)
    runCurrent()
    assertEquals("visible_window", lease.snapshot().protectionMode)
    assertEquals(0, fixture.blockers)
    assertFalse(boundary.isCompleted)
    fixture.touch = fixture.touch.copy(active = false)
    fixture.window = fixture.window.copy(generation = 3L)
    advanceTimeBy(20L)
    runCurrent()
    assertTrue(boundary.await())
    assertFalse(lease.snapshot().physicalTouchPreempted)
    fixture.window = TicketPhysicalVisibleWindow(0L, 4L)
    assertTrue(lease.beforeMutationAllowed())
    assertEquals("dark", lease.snapshot().protectionMode)
    assertEquals(2, fixture.root.launches)
    assertTrue(lease.releaseAfterFinalConvergence("done").safe)
    assertEquals(0, fixture.blockers)
  }

  @Test
  fun heldTouchIsBoundedWithoutAuthorizingAnotherMutation() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.touch = fixture.touch.copy(active = true, touchBeginCount = 1L)
    fixture.window = fixture.window.copy(generation = 2L)
    assertTrue(lease.ongoingCommandAllowed())
    assertFalse(lease.beforeMutationAllowed())
    assertEquals("physical_touch_release_timeout", lease.snapshot().releaseReason)
    assertFalse(lease.releaseAfterFinalConvergence("failed").safe)
  }

  @Test
  fun sourceLossDuringHeldTouchFailsClosed() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.touch = fixture.touch.copy(active = true, touchBeginCount = 1L)
    assertTrue(lease.ongoingCommandAllowed())
    fixture.touch = fixture.touch.copy(available = false)
    assertFalse(lease.ongoingCommandAllowed())
    assertFalse(lease.releaseAfterFinalConvergence("failed").safe)
  }

  @Test
  fun grantArrivingDuringWriterRegistrationPreventsLaunch() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow()
    fixture.onStarting = { fixture.window = TicketPhysicalVisibleWindow(91_000L, 2L) }
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    assertEquals(0, fixture.root.launches)
    assertEquals(0, fixture.blockers)
    assertTrue(lease.releaseAfterFinalConvergence("done").safe)
  }

  @Test
  fun expiryStartsOneHelperAndWaitsForTwoZeroProofsBeforeNextMutation() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.window = fixture.window.copy(deadlineUptimeMillis = 0L)
    assertTrue(lease.ongoingCommandAllowed())
    assertEquals("darkening", lease.snapshot().protectionMode)
    assertTrue(lease.beforeMutationAllowed())
    assertEquals(1, fixture.root.launches)
    assertEquals(1, fixture.root.verifications)
    assertEquals("dark", lease.snapshot().protectionMode)
    assertTrue(lease.beforeMutationAllowed())
    assertEquals(1, fixture.root.launches)
    lease.markMutationMayHaveDispatched()
    val completed = lease.releaseAfterFinalConvergence("done")
    assertTrue(completed.safe)
    assertTrue(completed.freshDarkProven)
    assertEquals(1, fixture.root.stops)
  }

  @Test
  fun monitorDarkensOnDeadlineEvenWhileActionOnlyWaitsForVisualProof() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow(1_100L, 1L)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    advanceTimeBy(200L)
    runCurrent()
    assertEquals("dark", lease.snapshot().protectionMode)
    assertEquals(1, fixture.root.launches)
    assertTrue(lease.releaseAfterFinalConvergence("done").safe)
  }

  @Test
  fun refreshedPhysicalGrantKeepsVisibleProtection() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.window = fixture.window.copy(generation = 2L)
    assertTrue(lease.beforeMutationAllowed())
    assertEquals("visible_window", lease.snapshot().protectionMode)
    assertEquals(0, fixture.root.launches)
    assertTrue(lease.releaseAfterFinalConvergence("done").safe)
  }

  @Test
  fun unprovedDarkTransitionHasBoundedInflightAuthorityAndCannotAuthorizeMutation() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.root.zeroProven = false
    fixture.window = fixture.window.copy(deadlineUptimeMillis = 0L)
    assertTrue(lease.ongoingCommandAllowed())
    advanceTimeBy(TicketActionPanelDarkLease.PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS + 1L)
    runCurrent()
    assertFalse(lease.ongoingCommandAllowed())
    assertFalse(lease.beforeMutationAllowed())
    assertFalse(lease.releaseAfterFinalConvergence("failed").safe)
    assertEquals(1, fixture.root.launches)
  }

  @Test
  fun touchWhileExpiryHelperStartsCannotResetTheOriginalTouchBaseline() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.root.launchGate = CompletableDeferred()
    fixture.window = fixture.window.copy(deadlineUptimeMillis = 0L)
    val allowed = async { lease.beforeMutationAllowed() }
    fixture.root.launchEntered.await()
    fixture.touch = fixture.touch.copy(touchBeginCount = 1L)
    assertFalse(lease.ongoingCommandAllowed())
    assertFalse(allowed.await())
    assertFalse(lease.releaseAfterFinalConvergence("preempted").safe)
    assertTrue(lease.snapshot().physicalTouchPreempted)
    assertEquals(1, fixture.root.launches)
  }

  @Test
  fun expiryImmediatelyBeforeFinalizationRequiresNormalDarkConvergence() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    lease.markMutationMayHaveDispatched()
    fixture.window = fixture.window.copy(deadlineUptimeMillis = 0L)
    val completed = lease.releaseAfterFinalConvergence("done")
    assertTrue(completed.safe)
    assertTrue(completed.freshDarkProven)
    assertEquals("dark", completed.snapshot.protectionMode)
    assertEquals(1, fixture.root.launches)
    assertEquals(1, fixture.root.stops)
  }

  @Test
  fun cancellationDuringDarkeningStopsItsHelperAndCannotRestartIt() = runTest {
    val fixture = Fixture(this)
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.root.launchGate = CompletableDeferred()
    fixture.window = fixture.window.copy(deadlineUptimeMillis = 0L)
    assertTrue(lease.ongoingCommandAllowed())
    fixture.root.launchEntered.await()
    assertFalse(lease.release("cancelled").safe)
    advanceTimeBy(5_000L)
    runCurrent()
    assertFalse(lease.snapshot().active)
    assertEquals(1, fixture.root.launches)
    assertTrue(fixture.root.stops > 0)
  }

  @Test
  fun revealDuringStaleVerifierCannotAuthorizeMutationUntilFingerUp() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow()
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.clockOffset = 3_000L
    fixture.root.verifyGate = CompletableDeferred()
    val boundary = async { lease.beforeMutationAllowed() }
    runCurrent()
    assertFalse(boundary.isCompleted)
    fixture.touch = fixture.touch.copy(active = true, touchBeginCount = 1L)
    fixture.window = TicketPhysicalVisibleWindow(91_000L, 2L)
    assertTrue(lease.ongoingCommandAllowed())
    runCurrent()
    fixture.root.verifyGate!!.complete(Unit)
    runCurrent()
    assertFalse(boundary.isCompleted)
    fixture.touch = fixture.touch.copy(active = false)
    advanceTimeBy(20L)
    runCurrent()
    assertTrue(boundary.await())
    assertEquals("visible_window", lease.snapshot().protectionMode)
    assertTrue(lease.releaseAfterFinalConvergence("done").safe)
  }

  @Test
  fun touchDuringFinalHelperStopWaitsForLiftAndFinishesVisible() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow()
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    lease.markMutationMayHaveDispatched()
    fixture.root.stopGate = CompletableDeferred()
    val completed = async { lease.releaseAfterFinalConvergence("done") }
    fixture.root.stopEntered.await()
    fixture.touch = fixture.touch.copy(active = true, touchBeginCount = 1L)
    fixture.window = TicketPhysicalVisibleWindow(91_000L, 2L)
    fixture.root.stopGate!!.complete(Unit)
    runCurrent()
    assertFalse(completed.isCompleted)
    assertEquals(0, fixture.blockers)
    fixture.touch = fixture.touch.copy(active = false)
    advanceTimeBy(20L)
    runCurrent()
    val result = completed.await()
    assertTrue(result.safe)
    assertFalse(result.freshDarkProven)
    assertEquals("visible_window", result.snapshot.protectionMode)
    assertFalse(result.snapshot.physicalTouchPreempted)
  }

  @Test
  fun tapThenImmediateSideRevocationContinuesSameActionInDarkness() = runTest {
    val fixture = Fixture(this)
    fixture.continuationEnabled = true
    fixture.window = TicketPhysicalVisibleWindow()
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.touch = fixture.touch.copy(touchBeginCount = 1L)
    fixture.window = TicketPhysicalVisibleWindow(0L, 3L)
    assertTrue(lease.ongoingCommandAllowed())
    assertTrue(lease.beforeMutationAllowed())
    assertEquals("dark", lease.snapshot().protectionMode)
    assertEquals(1, fixture.root.launches)
    assertEquals(0, fixture.root.stops)
    assertTrue(lease.releaseAfterFinalConvergence("done").safe)
  }

  @Test
  fun physicalGrantDuringInitialLauncherContinuesAfterLiftWithoutSecondLaunch() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow()
    fixture.root.launchGate = CompletableDeferred()
    val lease = fixture.lease()
    val acquired = async { lease.acquire() }
    fixture.root.launchEntered.await()
    fixture.touch = fixture.touch.copy(active = true, touchBeginCount = 1L)
    fixture.window = TicketPhysicalVisibleWindow(91_000L, 2L)
    assertTrue(lease.ongoingCommandAllowed())
    runCurrent()
    assertFalse(acquired.isCompleted)
    fixture.touch = fixture.touch.copy(active = false)
    advanceTimeBy(20L)
    runCurrent()
    assertTrue(acquired.await())
    assertEquals(1, fixture.root.launches)
    assertTrue(lease.releaseAfterFinalConvergence("done").safe)
    assertEquals(0, fixture.blockers)
  }

  @Test
  fun unprovedRevealStopRejectsBoundaryAndKeepsWriterBlocker() = runTest {
    val fixture = Fixture(this)
    fixture.window = TicketPhysicalVisibleWindow()
    val lease = fixture.lease()
    assertTrue(lease.acquire())
    fixture.root.stopProven = false
    fixture.touch = fixture.touch.copy(touchBeginCount = 1L)
    fixture.window = TicketPhysicalVisibleWindow(91_000L, 2L)
    assertFalse(lease.beforeMutationAllowed())
    assertEquals("panel_dark_helper_stop_unproved", lease.snapshot().failure)
    assertEquals(1, fixture.blockers)
    assertFalse(lease.releaseAfterFinalConvergence("failed").safe)
  }

  private class Fixture(private val scope: TestScope) {
    var window = TicketPhysicalVisibleWindow(91_000L, 1L)
    var touch = PhoneAutomationRootPhysicalTouchState(true, false, 1_000L)
    val root = FakeRoot()
    var blockers = 0
    var clockOffset = 0L
    var continuationEnabled = false
    var onStarting: () -> Unit = {}
    fun lease() = TicketActionPanelDarkLease(
      actionId = "visible_test", scope = scope.backgroundScope,
      clampRootExecutor = root, verifyRootExecutor = root,
      physicalTouchState = { touch }, onSnapshotChanged = {}, ownerProcessId = 4321,
      uptimeClock = { scope.testScheduler.currentTime + 1_000L + clockOffset }, physicalVisibleWindow = { window },
      physicalTouchContinuationEnabled = continuationEnabled,
      onDarkWriterStarting = { blockers++; onStarting() },
      onDarkWriterStopped = { blockers-- }
    )
  }

  private class FakeRoot : RootExecutor {
    var calls = 0
    var launches = 0
    var verifications = 0
    var stops = 0
    var zeroProven = true
    var stopProven = true
    val stopEntered = CompletableDeferred<Unit>()
    var verifyGate: CompletableDeferred<Unit>? = null
    var stopGate: CompletableDeferred<Unit>? = null
    var launchGate: CompletableDeferred<Unit>? = null
    val launchEntered = CompletableDeferred<Unit>()
    override suspend fun isRootAvailable() = true
    override suspend fun run(command: String, timeout: Duration): RootResult = error("unexpected run")
    override suspend fun runScript(script: String, timeout: Duration): RootResult {
      calls++
      val output = when {
        script.contains("echo helper_launched=1") -> {
          launches++
          launchEntered.complete(Unit)
          launchGate?.await()
          "helper_launched=1\n"
        }
        script.contains("echo helper_stopped=1") -> {
          stops++
          stopEntered.complete(Unit)
          stopGate?.await()
          if (stopProven) "helper_stopped=1\nreadiness_removed=1\ntelemetry_removed=1\nwait_fifo_removed=1\nlaunch_marker_removed=1\n" else "helper_stopped=0\n"
        }
        else -> {
          verifications++
          verifyGate?.await()
          if (zeroProven) "helper_ready=1\npanel_dark=1\npanel_confirmations=2\n" else "helper_ready=0\npanel_dark=0\n"
        }
      }
      return RootResult(0, output, "", "test", 0L)
    }
  }
}
