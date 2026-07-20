package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class ControlCodeAutomationCoordinatorTest {
  @Test
  fun requestWaitsForBackgroundPreparationToFinishCancellationBeforeInputStarts() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher
    )
    val preparationStarted = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()

    assertEquals(
      ControlCodePrepareScheduleResult.STARTED,
      coordinator.schedulePrepare {
        try {
          events += "prepare_started"
          preparationStarted.complete(Unit)
          awaitCancellation()
        } finally {
          events += "prepare_stopped"
        }
      }
    )
    runCurrent()
    preparationStarted.await()

    val request = launch(dispatcher) {
      coordinator.claimRequest()
      events += "request_input_started"
    }
    runCurrent()

    assertTrue(request.isCompleted)
    assertEquals(
      listOf("prepare_started", "prepare_stopped", "request_input_started"),
      events
    )
    coordinator.releaseRequest()
    assertFalse(coordinator.requestClaimed())
  }

  @Test
  fun claimedRequestQueuesPreparationUntilReleased() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher
    )

    var prepared = false
    coordinator.claimRequest()
    assertTrue(coordinator.requestClaimed())
    assertEquals(
      ControlCodePrepareScheduleResult.REQUEST_OWNED,
      coordinator.schedulePrepare { prepared = true }
    )
    runCurrent()
    assertFalse(prepared)

    coordinator.releaseRequest()
    runCurrent()
    assertTrue(prepared)
  }

  @Test
  fun duplicatePreparationIsDeduplicated() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher
    )
    val hold = CompletableDeferred<Unit>()

    assertEquals(
      ControlCodePrepareScheduleResult.STARTED,
      coordinator.schedulePrepare { hold.await() }
    )
    assertEquals(
      ControlCodePrepareScheduleResult.DEDUPLICATED,
      coordinator.schedulePrepare { error("duplicate must not start") }
    )
    coordinator.cancelPreparation()
    runCurrent()
  }

  @Test
  fun queuedPreparationRunsExactlyOnceAfterTheLastOfTwoRequestsReleases() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher
    )
    var preparations = 0

    coordinator.claimRequest()
    coordinator.claimRequest()
    assertEquals(
      ControlCodePrepareScheduleResult.REQUEST_OWNED,
      coordinator.schedulePrepare { preparations += 1 }
    )
    assertEquals(
      ControlCodePrepareScheduleResult.REQUEST_OWNED,
      coordinator.schedulePrepare { preparations += 100 }
    )

    coordinator.releaseRequest()
    runCurrent()
    assertEquals(0, preparations)
    assertTrue(coordinator.requestClaimed())

    coordinator.releaseRequest()
    runCurrent()
    assertEquals(1, preparations)
    assertFalse(coordinator.requestClaimed())
  }

  @Test
  fun cancelAndJoinWaitsForPreparationFinallyBlockBeforeSessionStopContinues() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher
    )
    val events = mutableListOf<String>()

    coordinator.schedulePrepare {
      try {
        events += "prepare_started"
        awaitCancellation()
      } finally {
        events += "prepare_finished"
      }
    }
    runCurrent()
    val stop = launch(dispatcher) {
      coordinator.cancelPreparationAndJoin()
      events += "session_stopped"
    }
    runCurrent()

    assertTrue(stop.isCompleted)
    assertEquals(listOf("prepare_started", "prepare_finished", "session_stopped"), events)
  }

  @Test
  fun newClaimDuringQueuedPreparationStartPreservesRecoveryForItsRelease() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher
    )
    var preparations = 0

    coordinator.claimRequest()
    coordinator.schedulePrepare { preparations += 1 }
    coordinator.releaseRequest()
    coordinator.claimRequest()
    runCurrent()
    assertEquals(0, preparations)

    coordinator.releaseRequest()
    runCurrent()
    assertEquals(1, preparations)
  }

  @Test
  fun shutdownJoinsPreparationAlreadyBeingRetiredByARequestClaim() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher
    )
    val finallyStarted = CompletableDeferred<Unit>()
    val allowFinallyToFinish = CompletableDeferred<Unit>()

    coordinator.schedulePrepare {
      try {
        awaitCancellation()
      } finally {
        withContext(NonCancellable) {
          finallyStarted.complete(Unit)
          allowFinallyToFinish.await()
        }
      }
    }
    runCurrent()
    val claim = launch(dispatcher) { coordinator.claimRequest() }
    runCurrent()
    finallyStarted.await()

    val shutdown = launch(dispatcher) { coordinator.cancelPreparationAndJoin() }
    runCurrent()
    assertFalse(claim.isCompleted)
    assertFalse(shutdown.isCompleted)

    allowFinallyToFinish.complete(Unit)
    runCurrent()
    assertTrue(claim.isCompleted)
    assertTrue(shutdown.isCompleted)
    coordinator.releaseRequest()
  }

  @Test
  fun concurrentSchedulingCannotReplaceAnUnstartedPublishedPreparation() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    val firstPublish = CountDownLatch(1)
    val allowFirstStart = CountDownLatch(1)
    val firstHook = AtomicBoolean(true)
    val firstResult = AtomicReference<ControlCodePrepareScheduleResult>()
    val events = mutableListOf<String>()
    val coordinator = ControlCodeAutomationCoordinator(
      scope = CoroutineScope(SupervisorJob() + dispatcher),
      dispatcher = dispatcher,
      beforePrepareStart = {
        if (firstHook.compareAndSet(true, false)) {
          firstPublish.countDown()
          check(allowFirstStart.await(5, TimeUnit.SECONDS))
        }
      }
    )

    val first = thread(name = "control-code-prepare-publisher") {
      firstResult.set(
        coordinator.schedulePrepare {
          try {
            events += "prepare_started"
            awaitCancellation()
          } finally {
            events += "prepare_finished"
          }
        }
      )
    }
    assertTrue(firstPublish.await(5, TimeUnit.SECONDS))
    assertEquals(
      ControlCodePrepareScheduleResult.DEDUPLICATED,
      coordinator.schedulePrepare { events += "replacement_started" }
    )
    allowFirstStart.countDown()
    first.join(5_000)
    assertFalse(first.isAlive)
    assertEquals(ControlCodePrepareScheduleResult.STARTED, firstResult.get())
    runCurrent()
    assertEquals(listOf("prepare_started"), events)

    val shutdown = launch(dispatcher) {
      coordinator.cancelPreparationAndJoin()
      events += "shutdown_finished"
    }
    runCurrent()
    assertTrue(shutdown.isCompleted)
    assertEquals(listOf("prepare_started", "prepare_finished", "shutdown_finished"), events)
  }
}
