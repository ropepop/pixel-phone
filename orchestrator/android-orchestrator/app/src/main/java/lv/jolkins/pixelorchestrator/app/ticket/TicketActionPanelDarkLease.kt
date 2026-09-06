package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

internal data class TicketPhysicalVisibleWindow(
  val deadlineUptimeMillis: Long = 0L,
  val generation: Long = 0L
)

internal data class TicketActionPanelDarkLeaseSnapshot(
  val active: Boolean = false,
  val ownerActionId: String = "",
  val acquiredAtUptimeMillis: Long = 0L,
  val lastZeroConfirmedAtUptimeMillis: Long = 0L,
  val failure: String = "",
  val physicalTouchPreempted: Boolean = false,
  val releaseReason: String = "idle",
  val mutationMayHaveDispatched: Boolean = false,
  val launchExitCode: Int? = null,
  val launchDurationMillis: Long? = null,
  val acquisitionVerificationMillis: Long? = null,
  val lastVerifierClassification: String = "not_run",
  val lastVerifierExitCode: Int? = null,
  val lastVerifierDurationMillis: Long? = null,
  val helperStage: String = "not_observed",
  val helperExitCode: Int? = null,
  val protectionMode: String = "dark"
)

internal data class TicketActionPanelDarkLeaseFinalization(
  val safe: Boolean,
  val freshDarkProven: Boolean,
  val exactHelperStopProven: Boolean,
  val snapshot: TicketActionPanelDarkLeaseSnapshot
)

internal fun ticketControlCodeCleanupMayCommitAfterPanelFinalization(
  phoneSurfaceCleaned: Boolean,
  finalization: TicketActionPanelDarkLeaseFinalization?
): Boolean = phoneSurfaceCleaned && finalization?.safe == true

internal fun ticketScheduledControlCleanupFailureReason(
  leaseAcquired: Boolean,
  finalizationSafe: Boolean?,
  mutationMayHaveDispatched: Boolean
): String = when {
  mutationMayHaveDispatched -> "control_code_cleanup_attention_needed"
  !leaseAcquired || finalizationSafe != true -> "control_code_panel_dark_unavailable"
  else -> "control_code_cleanup_checkpoint_clear_unproved"
}

internal fun ticketVisualSuccessProofCurrentAfterPanelFinalization(
  proofActionId: String,
  expectedActionId: String,
  proofGeneration: Long,
  currentGeneration: Long,
  proofStreamEpoch: Long,
  proofFrameSequence: Long,
  currentStreamEpoch: Long,
  currentFrameSequence: Long,
  latestKeyFrameEpoch: Long,
  latestKeyFrameSequence: Long
): Boolean = proofActionId.isNotBlank() && proofActionId == expectedActionId &&
  proofGeneration == currentGeneration && proofStreamEpoch > 0L && proofFrameSequence > 0L &&
  currentStreamEpoch == proofStreamEpoch && currentFrameSequence >= proofFrameSequence &&
  latestKeyFrameEpoch == proofStreamEpoch && latestKeyFrameSequence >= proofFrameSequence

private enum class TicketPanelDarkVerification {
  PROVEN,
  HELPER_UNREADY,
  PANEL_NOT_DARK,
  VERIFIER_UNAVAILABLE;

  val healthToken: String
    get() = when (this) {
      PROVEN -> "proven"
      HELPER_UNREADY -> "helper_unready"
      PANEL_NOT_DARK -> "panel_not_dark"
      VERIFIER_UNAVAILABLE -> "verifier_unavailable"
    }
}

private enum class TicketPanelDarkLaunchState {
  PENDING,
  SUCCEEDED,
  FAILED
}

/**
 * Holds the physical backlight at zero for one Ticket action. TouchBrightnessRuntime remains the
 * physical-input authority: a trusted visibility grant reveals the same action after exact helper
 * shutdown. New mutations wait for finger-up; an already-dispatched mutation is never replayed.
 * Without configured continuation authority or a live grant, physical touch remains fail-closed.
 */
internal class TicketActionPanelDarkLease(
  private val actionId: String,
  private val scope: CoroutineScope,
  private val clampRootExecutor: RootExecutor,
  private val verifyRootExecutor: RootExecutor,
  private val physicalTouchState: () -> PhoneAutomationRootPhysicalTouchState,
  private val onSnapshotChanged: (TicketActionPanelDarkLeaseSnapshot) -> Unit,
  private val ownerProcessId: Int,
  private val uptimeClock: () -> Long = SystemClock::uptimeMillis,
  private val helperToken: String = "ticket-panel-${UUID.randomUUID()}",
  private val physicalVisibleWindow: () -> TicketPhysicalVisibleWindow = { TicketPhysicalVisibleWindow() },
  private val physicalTouchContinuationEnabled: Boolean = false,
  private val onDarkWriterStarting: () -> Unit = {},
  private val onDarkWriterStopped: () -> Unit = {}
) {
  private val released = AtomicBoolean(false)
  private val emergencyHelperStopScheduled = AtomicBoolean(false)
  private val helperStopMutex = Mutex()
  private val finalizationMutex = Mutex()
  private val verificationMutex = Mutex()
  private val stateLock = Any()
  @Volatile private var helperLaunchJob: Job? = null
  @Volatile private var helperLaunchState = TicketPanelDarkLaunchState.PENDING
  @Volatile private var touchMonitorJob: Job? = null
  @Volatile private var verifyJob: Job? = null
  @Volatile private var acquisitionVerificationJob: Job? = null
  @Volatile private var helperStopProven = false
  @Volatile private var finalization: TicketActionPanelDarkLeaseFinalization? = null
  @Volatile private var lastMutationMayHaveDispatchedAtUptimeMillis: Long? = null
  @Volatile private var acquiredTouchBeginCount: Long? = null
  @Volatile private var acquiredVisibleWindowGeneration: Long? = null
  @Volatile private var visibleTransitionJob: Job? = null
  @Volatile private var continuingPhysicalTouch = false
  @Volatile private var darkTransitionJob: Job? = null
  @Volatile private var darkTransitionStartedAtUptimeMillis = 0L
  @Volatile private var state = TicketActionPanelDarkLeaseSnapshot(ownerActionId = actionId)

  fun snapshot(): TicketActionPanelDarkLeaseSnapshot = state

  suspend fun acquire(): Boolean {
    val touch = physicalTouchState()
    if (touch.available && touch.active) {
      updateState {
        it.copy(
          failure = "physical_touch_active",
          physicalTouchPreempted = true,
          releaseReason = "physical_touch_preempted_before_acquire"
        )
      }
      return false
    }
    if (!touch.available || touch.observedAtUptimeMillis <= 0L) {
      updateState {
        it.copy(
          failure = "physical_touch_source_unready",
          releaseReason = "physical_touch_source_unready"
        )
      }
      return false
    }

    acquiredTouchBeginCount = touch.touchBeginCount
    val visibleWindow = physicalVisibleWindow()
    startTouchMonitor()
    if (visibleWindow.deadlineUptimeMillis > uptimeClock()) {
      acquiredVisibleWindowGeneration = visibleWindow.generation
      updateState {
        if (it.physicalTouchPreempted || it.failure.isNotBlank()) it else it.copy(active = true, acquiredAtUptimeMillis = uptimeClock(),
          releaseReason = "active", protectionMode = "visible_window")
      }
      return beforeMutationAllowed()
    }
    val acquired = acquireDarkHelper()
    return if (snapshot().protectionMode in setOf("revealing", "visible_window")) beforeMutationAllowed() else acquired
  }

  /** Reacquire darkness for the same lease after visibility expires or is revoked. */
  private suspend fun acquireDarkHelper(): Boolean {
    if (released.get() || !physicalTouchClearAtMutationBoundary() ||
      snapshot().physicalTouchPreempted || snapshot().failure.isNotBlank()
    ) return false
    val launchJob = synchronized(stateLock) {
      if (released.get() || state.physicalTouchPreempted || state.failure.isNotBlank()) return false
      if (state.protectionMode == "revealing") return false
      helperStopProven = false
      helperLaunchState = TicketPanelDarkLaunchState.PENDING
      emergencyHelperStopScheduled.set(false)
      onDarkWriterStarting()
      val grantAfterRegistration = physicalVisibleWindow()
      if (grantAfterRegistration.deadlineUptimeMillis > uptimeClock()) {
        acquiredVisibleWindowGeneration = grantAfterRegistration.generation
        helperStopProven = true
        state = state.copy(active = true, protectionMode = "visible_window",
          acquiredAtUptimeMillis = state.acquiredAtUptimeMillis.takeIf { it > 0L } ?: uptimeClock())
        onDarkWriterStopped()
        return@synchronized null
      }
      scope.launch(start = CoroutineStart.LAZY) {
      // The root command only starts the exact owner-bound helper. The helper itself is detached
      // from this su session, so an otherwise healthy root transport ending cannot terminate a
      // 5-16 second Ticket action. This short launcher acknowledges only that the detached spawn
      // received a numeric PID. Readiness, continued life, and shutdown are separately proved
      // through the helper's exact PID/start-time record rather than this coroutine's lifetime.
      val result = clampRootExecutor.runScript(
        panelDarkLaunchScript(ownerProcessId, helperToken),
        PANEL_DARK_LAUNCH_TIMEOUT_MILLIS.milliseconds
      )
      // Preserve only bounded, content-free launch diagnostics. Record them even when a physical
      // touch cancelled this coroutine while an uncooperative root transport was still returning;
      // the cancellation check below still prevents that late result from authorizing the lease.
      updateState {
        it.copy(
          launchExitCode = result.exitCode,
          launchDurationMillis = result.durationMs.coerceAtLeast(0L)
        )
      }
      currentCoroutineContext().ensureActive()
      val outputLines = result.stdout.lineSequence().map(String::trim).toSet()
      if (result.ok && "helper_launched=1" in outputLines) {
        helperLaunchState = TicketPanelDarkLaunchState.SUCCEEDED
      } else {
        helperLaunchState = TicketPanelDarkLaunchState.FAILED
        if (!released.get() && !snapshot().physicalTouchPreempted) {
          updateState {
            it.copy(
              active = false,
              failure = "panel_dark_helper_launch_failed",
              releaseReason = "panel_dark_helper_launch_failed"
            )
          }
          requestEmergencyHelperStop()
        }
      }
      }.also { helperLaunchJob = it }
    } ?: run {
      onSnapshotChanged(snapshot())
      return true
    }
    launchJob.start()

    val readyDeadline = uptimeClock() + PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS
    var acquireAttempts = 0
    var lastVerification = TicketPanelDarkVerification.VERIFIER_UNAVAILABLE
    while (
      uptimeClock() < readyDeadline &&
      acquireAttempts < PANEL_DARK_ACQUIRE_MAX_ATTEMPTS
    ) {
      acquireAttempts += 1
      val currentTouch = physicalTouchState()
      if (!currentTouch.available) {
        failForLostPhysicalTouchSource("physical_touch_source_lost_during_acquire")
        return false
      }
      if (!physicalTouchClearAtMutationBoundary()) {
        if (snapshot().physicalTouchPreempted) preemptForPhysicalTouch("physical_touch_during_acquire")
        return false
      }
      if (snapshot().protectionMode in setOf("revealing", "visible_window")) return false
      if (helperLaunchState == TicketPanelDarkLaunchState.FAILED) break
      // The short root launcher and the exact detached-helper verifier are both authorities at
      // acquisition. Never let a pre-existing zero panel or a stale readiness record authorize an
      // action while the launcher is pending or after it has failed.
      if (helperLaunchState != TicketPanelDarkLaunchState.SUCCEEDED) {
        delay(PANEL_DARK_ACQUIRE_POLL_MILLIS)
        continue
      }
      val verification = verifyDuringAcquisition(readyDeadline) ?: run {
        stopHelperAndJoinBounded()
        return false
      }
      val verifiedTouch = physicalTouchState()
      if (!verifiedTouch.available) {
        failForLostPhysicalTouchSource("physical_touch_source_lost_during_acquire")
        return false
      }
      if (!physicalTouchClearAtMutationBoundary()) {
        if (snapshot().physicalTouchPreempted) preemptForPhysicalTouch("physical_touch_during_acquire")
        return false
      }
      if (snapshot().protectionMode in setOf("revealing", "visible_window")) return false
      if (uptimeClock() >= readyDeadline) {
        lastVerification = if (verification == TicketPanelDarkVerification.PROVEN) {
          TicketPanelDarkVerification.VERIFIER_UNAVAILABLE
        } else verification
        break
      }
      lastVerification = verification
      if (verification == TicketPanelDarkVerification.PROVEN) {
        val now = uptimeClock()
        updateState {
          if (released.get() || it.protectionMode in setOf("revealing", "visible_window") || it.physicalTouchPreempted || it.failure.isNotBlank()) it else it.copy(
            active = true,
            acquiredAtUptimeMillis = it.acquiredAtUptimeMillis.takeIf { age -> age > 0L } ?: now,
            lastZeroConfirmedAtUptimeMillis = now,
            failure = "",
            releaseReason = "active",
            protectionMode = "dark"
          )
        }
        if (snapshot().protectionMode in setOf("revealing", "visible_window")) return false
        if (!snapshot().active) {
          stopHelperAndJoinBounded()
          return false
        }
        startPeriodicVerifier()
        return ongoingCommandAllowed()
      }
      delay(PANEL_DARK_ACQUIRE_POLL_MILLIS)
    }
    updateState {
      if (it.failure.isNotBlank() || it.physicalTouchPreempted) {
        it.copy(active = false)
      } else {
        it.copy(
          active = false,
          failure = when (lastVerification) {
            TicketPanelDarkVerification.PANEL_NOT_DARK -> "panel_dark_zero_unproved"
            TicketPanelDarkVerification.VERIFIER_UNAVAILABLE -> "panel_dark_verifier_unavailable"
            else -> "panel_dark_helper_unready_or_zero_unproved"
          },
          releaseReason = "acquire_failed"
        )
      }
    }
    stopHelperAndJoinBounded()
    return false
  }

  /** Revealing changes protection for this lease; it never creates or repeats an action. */
  private fun ensureVisibleTransitionStarted(window: TicketPhysicalVisibleWindow): Job? {
    val job = synchronized(stateLock) {
      if (released.get() || state.failure.isNotBlank() || state.physicalTouchPreempted) return@synchronized null
      acquiredVisibleWindowGeneration = window.generation
      if (state.protectionMode == "visible_window") return@synchronized null
      if (state.protectionMode == "revealing") return@synchronized visibleTransitionJob
      state = state.copy(active = true, protectionMode = "revealing",
        acquiredAtUptimeMillis = state.acquiredAtUptimeMillis.takeIf { it > 0L } ?: uptimeClock())
      // Cancel synchronously before the physical runtime is allowed to restore brightness.
      verifyJob?.cancel()
      darkTransitionJob?.cancel()
      acquisitionVerificationJob?.cancel()
      scope.launch(start = CoroutineStart.LAZY) {
        darkTransitionJob?.cancelAndJoin()
        verifyJob?.cancelAndJoin()
        acquisitionVerificationJob?.cancelAndJoin()
        val stopped = stopHelperAndJoinBounded()
        synchronized(stateLock) {
          if (stopped && !released.get() && state.failure.isBlank()) {
            helperLaunchJob = null
            state = state.copy(protectionMode = "visible_window", lastZeroConfirmedAtUptimeMillis = 0L)
          }
        }
        onSnapshotChanged(snapshot())
      }.also { visibleTransitionJob = it }
    }
    onSnapshotChanged(snapshot())
    job?.start()
    return job
  }

  private fun visibleWindowStillValid(): Boolean {
    val visibleWindow = physicalVisibleWindow()
    return visibleWindow.generation == acquiredVisibleWindowGeneration &&
      visibleWindow.deadlineUptimeMillis > uptimeClock()
  }

  private fun ensureDarkTransitionStarted(): Job? {
    var started = false
    val job = synchronized(stateLock) {
      if (released.get() || !state.active || state.physicalTouchPreempted || state.failure.isNotBlank()) {
        return@synchronized null
      }
      if (state.protectionMode != "visible_window" || visibleWindowStillValid() || physicalTouchState().active) {
        return@synchronized darkTransitionJob
      }
      darkTransitionStartedAtUptimeMillis = uptimeClock()
      state = state.copy(protectionMode = "darkening")
      started = true
      scope.launch(start = CoroutineStart.LAZY) {
        var acquired = false
        try {
          acquired = withTimeoutOrNull(PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS) { acquireDarkHelper() } == true
          if (!acquired && !released.get() && snapshot().protectionMode != "revealing" && snapshot().failure.isBlank() && !snapshot().physicalTouchPreempted) {
            failDarkTransition()
          }
        } finally {
          if (!acquired) withContext(NonCancellable) { stopHelperAndJoinBounded() }
        }
      }.also { darkTransitionJob = it }
    }
    if (started) onSnapshotChanged(snapshot())
    job?.start()
    return job
  }

  private fun failDarkTransition() {
    updateState {
      if (it.failure.isNotBlank() || it.physicalTouchPreempted) it else it.copy(
        active = false, failure = "panel_dark_transition_unproved", releaseReason = "panel_dark_transition_unproved"
      )
    }
    darkTransitionJob?.cancel()
    requestEmergencyHelperStop()
  }

  private suspend fun verifyDuringAcquisition(readyDeadline: Long): TicketPanelDarkVerification? = supervisorScope {
    val pending = async(start = CoroutineStart.LAZY) {
      verifyHelperReadyAndZero(
        requiredConfirmations = PANEL_DARK_REQUIRED_ACQUIRE_CONFIRMATIONS,
        deadlineUptimeMillis = readyDeadline
      )
    }
    acquisitionVerificationJob = pending
    try {
      val current = snapshot()
      if (current.physicalTouchPreempted || current.failure.isNotBlank()) {
        pending.cancel()
        return@supervisorScope null
      }
      pending.await()
    } catch (cancelled: CancellationException) {
      currentCoroutineContext().ensureActive()
      val current = snapshot()
      if (current.protectionMode == "revealing" || current.physicalTouchPreempted || current.failure.isNotBlank()) null else throw cancelled
    } finally {
      if (acquisitionVerificationJob === pending) acquisitionVerificationJob = null
    }
  }

  /** Check in-flight cancellation without waiting behind a root verifier. */
  fun ongoingCommandAllowed(): Boolean {
    if (!physicalTouchClearAtMutationBoundary()) return false
    ensureDarkTransitionStarted()
    val current = snapshot()
    if (current.protectionMode == "darkening" &&
      uptimeClock() - darkTransitionStartedAtUptimeMillis >= PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS
    ) {
      failDarkTransition()
      return false
    }
    return current.active && !current.physicalTouchPreempted && current.failure.isBlank()
  }

  suspend fun beforeMutationAllowed(): Boolean {
    while (true) {
      // Read the physical source synchronously at the mutation boundary as well as via the 20 ms
      // monitor. This closes the scheduler window between a real finger-down event and the monitor
      // coroutine's next turn.
      if (!physicalTouchClearAtMutationBoundary()) return false
      val lifted = withTimeoutOrNull(PHYSICAL_TOUCH_RELEASE_TIMEOUT_MILLIS) {
        while (physicalTouchState().active) {
          if (!physicalTouchClearAtMutationBoundary()) return@withTimeoutOrNull false
          delay(PHYSICAL_TOUCH_POLL_MILLIS)
        }
        physicalTouchClearAtMutationBoundary()
      } == true
      if (!lifted) {
        if (snapshot().failure.isBlank()) {
          updateState { it.copy(active = false, failure = "physical_touch_release_timeout", releaseReason = "physical_touch_release_timeout") }
          verifyJob?.cancel()
          darkTransitionJob?.cancel()
          visibleTransitionJob?.cancelAndJoin()
          stopHelperAndJoinBounded()
        }
        return false
      }
      visibleTransitionJob?.join()
      if (!physicalTouchClearAtMutationBoundary()) return false
      if (physicalTouchState().active || snapshot().protectionMode == "revealing") continue
      if (snapshot().protectionMode == "visible_window" && visibleWindowStillValid()) {
        return snapshot().let { it.active && !it.physicalTouchPreempted && it.failure.isBlank() }
      }
      ensureDarkTransitionStarted()?.join()
      if (!physicalTouchClearAtMutationBoundary()) return false
      if (snapshot().protectionMode in setOf("revealing", "visible_window") || physicalTouchState().active) continue
      if (snapshot().protectionMode != "dark") return false
      val current = snapshot()
      val zeroProofAge = if (current.lastZeroConfirmedAtUptimeMillis > 0L) {
        (uptimeClock() - current.lastZeroConfirmedAtUptimeMillis).coerceAtLeast(0L)
      } else {
        Long.MAX_VALUE
      }
      if (current.active && zeroProofAge > PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS) {
        // A delayed verifier coroutine must not turn a physically dark, healthy clamp into a false
        // terminal. Wait for any already-running exact verifier and, if it did not produce a fresh
        // proof, perform one bounded exact helper-identity/raw-zero refresh at this boundary. The
        // stale proof itself never authorizes mutation.
        val verification = refreshStaleZeroProofAtMutationBoundary()
        // Physical touch remains the highest authority throughout the bounded root read. Re-read it
        // before considering the new zero proof so a touch during verification always wins.
        if (!physicalTouchClearAtMutationBoundary()) return false
        if (snapshot().protectionMode != "dark" || physicalTouchState().active) continue
        val refreshed = snapshot()
        if (!refreshed.active || refreshed.physicalTouchPreempted || refreshed.failure.isNotBlank()) {
          return false
        }
        if (verification == TicketPanelDarkVerification.PROVEN) {
          return true
        }
        val failure = when (verification) {
          TicketPanelDarkVerification.HELPER_UNREADY -> "panel_dark_helper_identity_lost"
          TicketPanelDarkVerification.PANEL_NOT_DARK -> "panel_dark_zero_lost"
          TicketPanelDarkVerification.VERIFIER_UNAVAILABLE -> "panel_dark_verifier_unavailable"
          TicketPanelDarkVerification.PROVEN -> error("handled above")
        }
        updateState {
          if (it.physicalTouchPreempted || it.failure.isNotBlank()) {
            it.copy(active = false)
          } else {
            it.copy(
              active = false,
              failure = failure,
              releaseReason = when (verification) {
                TicketPanelDarkVerification.VERIFIER_UNAVAILABLE ->
                  "panel_dark_zero_proof_stale_at_mutation_boundary"
                else -> failure
              }
            )
          }
        }
        verifyJob?.cancel()
        darkTransitionJob?.cancel()
        requestEmergencyHelperStop()
        return false
      }
      return current.active && !current.physicalTouchPreempted && current.failure.isBlank()
    }
  }

  private fun physicalTouchClearAtMutationBoundary(): Boolean {
    if (snapshot().physicalTouchPreempted || snapshot().failure.isNotBlank()) return false
    val currentTouch = physicalTouchState()
    if (!currentTouch.available) {
      updateState {
        it.copy(
          active = false,
          failure = "physical_touch_source_lost",
          releaseReason = "physical_touch_source_lost_at_mutation_boundary"
        )
      }
      verifyJob?.cancel()
      darkTransitionJob?.cancel()
      requestEmergencyHelperStop()
      return false
    }
    if (physicalTouchOccurred(currentTouch)) {
      val window = physicalVisibleWindow()
      if (window.deadlineUptimeMillis > uptimeClock()) {
        acquiredTouchBeginCount = currentTouch.touchBeginCount
        continuingPhysicalTouch = currentTouch.active
        ensureVisibleTransitionStarted(window)
        return true
      }
      if (physicalTouchContinuationEnabled) {
        acquiredTouchBeginCount = currentTouch.touchBeginCount
        continuingPhysicalTouch = currentTouch.active
        return true
      }
      if (continuingPhysicalTouch && acquiredTouchBeginCount == currentTouch.touchBeginCount) return true
      updateState {
        it.copy(
          active = false,
          physicalTouchPreempted = true,
          releaseReason = "physical_touch_at_mutation_boundary"
        )
      }
      verifyJob?.cancel()
      darkTransitionJob?.cancel()
      requestEmergencyHelperStop()
      return false
    }
    continuingPhysicalTouch = false
    if (snapshot().protectionMode in setOf("visible_window", "revealing")) {
      val window = physicalVisibleWindow()
      if (window.deadlineUptimeMillis > uptimeClock()) ensureVisibleTransitionStarted(window)
    }
    return true
  }

  private fun physicalTouchOccurred(touch: PhoneAutomationRootPhysicalTouchState): Boolean =
    touch.active || acquiredTouchBeginCount?.let { it != touch.touchBeginCount } == true

  private suspend fun refreshStaleZeroProofAtMutationBoundary(): TicketPanelDarkVerification {
    return withTimeoutOrNull(PANEL_DARK_MUTATION_REFRESH_TIMEOUT_MILLIS) {
      verificationMutex.withLock {
        val current = snapshot()
        if (!current.active || current.physicalTouchPreempted || current.failure.isNotBlank()) {
          return@withLock TicketPanelDarkVerification.VERIFIER_UNAVAILABLE
        }
        // The periodic verifier may have completed while this boundary waited for its turn. Reuse
        // only that newly completed exact proof; otherwise run a fresh exact verification now.
        val proofAge = if (current.lastZeroConfirmedAtUptimeMillis > 0L) {
          (uptimeClock() - current.lastZeroConfirmedAtUptimeMillis).coerceAtLeast(0L)
        } else {
          Long.MAX_VALUE
        }
        when {
          current.lastVerifierClassification == TicketPanelDarkVerification.HELPER_UNREADY.healthToken ->
            TicketPanelDarkVerification.HELPER_UNREADY
          current.lastVerifierClassification == TicketPanelDarkVerification.PANEL_NOT_DARK.healthToken ->
            TicketPanelDarkVerification.PANEL_NOT_DARK
          proofAge <= PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS ->
            TicketPanelDarkVerification.PROVEN
          else -> verifyHelperReadyAndZeroLocked(recordFreshZero = true)
        }
      }
    } ?: TicketPanelDarkVerification.VERIFIER_UNAVAILABLE
  }

  fun markMutationMayHaveDispatched() {
    lastMutationMayHaveDispatchedAtUptimeMillis = uptimeClock()
    updateState { it.copy(mutationMayHaveDispatched = true) }
  }

  /**
   * Keeps the exact action helper alive while delayed Android/app brightness writes converge.
   * The physical-touch and zero-verification monitors remain authoritative throughout this tail;
   * either one can end it early and the normal exact-helper release still runs afterward.
   */
  suspend fun releaseAfterFinalConvergence(reason: String): TicketActionPanelDarkLeaseFinalization {
    return withContext(NonCancellable) {
      releaseInternal(reason, requireSuccessProof = true)
    }
  }

  private suspend fun awaitFinalConvergenceTail(): Boolean {
    val releaseStartedAtMillis = uptimeClock()
    // Visual convergence runs while the helper is still active, so count it toward the
    // safety window instead of starting a second full tail after proof has completed.
    val convergenceStartedAtMillis =
      lastMutationMayHaveDispatchedAtUptimeMillis ?: releaseStartedAtMillis
    val elapsedMillis = (releaseStartedAtMillis - convergenceStartedAtMillis).coerceAtLeast(0L)
    var remainingMillis = (PANEL_DARK_FINAL_CONVERGENCE_MILLIS - elapsedMillis)
      .coerceAtLeast(0L)
    while (remainingMillis > 0L) {
      if (!beforeMutationAllowed()) return false
      val stepMillis = minOf(PHYSICAL_TOUCH_POLL_MILLIS, remainingMillis)
      delay(stepMillis)
      remainingMillis -= stepMillis
    }
    if (!beforeMutationAllowed()) return false
    if (snapshot().protectionMode != "dark") return false
    val finalVerification = verifyHelperReadyAndZero(recordFreshZero = true)
    if (!physicalTouchClearAtMutationBoundary() || snapshot().protectionMode != "dark") return false
    return when (finalVerification) {
      TicketPanelDarkVerification.PROVEN -> {
        beforeMutationAllowed()
      }
      TicketPanelDarkVerification.HELPER_UNREADY -> {
        failForPanelVerification(
          failure = "panel_dark_helper_identity_lost",
          releaseReason = "panel_dark_final_zero_helper_unready"
        )
        false
      }
      TicketPanelDarkVerification.PANEL_NOT_DARK -> {
        failForPanelVerification(
          failure = "panel_dark_zero_lost",
          releaseReason = "panel_dark_final_zero_lost"
        )
        false
      }
      TicketPanelDarkVerification.VERIFIER_UNAVAILABLE -> {
        failForPanelVerification(
          failure = "panel_dark_verifier_unavailable",
          releaseReason = "panel_dark_final_zero_unproved"
        )
        false
      }
    }
  }

  suspend fun release(reason: String): TicketActionPanelDarkLeaseFinalization {
    return withContext(NonCancellable) {
      releaseInternal(reason, requireSuccessProof = false)
    }
  }

  private fun commitVisibleFinalization(reason: String): TicketActionPanelDarkLeaseFinalization? = synchronized(stateLock) {
        val touch = physicalTouchState()
        if (state.protectionMode == "visible_window" && visibleWindowStillValid() &&
          state.active && state.failure.isBlank() && !state.physicalTouchPreempted &&
          touch.available && !physicalTouchOccurred(touch) && helperLaunchJob == null
        ) {
          released.set(true)
          state = state.copy(active = false, releaseReason = reason)
          TicketActionPanelDarkLeaseFinalization(
            safe = true, freshDarkProven = false, exactHelperStopProven = true, snapshot = state
          ).also { finalization = it }
        } else null
      }

  private suspend fun releaseInternal(
    reason: String,
    requireSuccessProof: Boolean
  ): TicketActionPanelDarkLeaseFinalization {
    finalizationMutex.lock()
    return try {
      finalization?.let { return it }
      if (requireSuccessProof) beforeMutationAllowed()
      // The grant and helper-start decision share this lock: expiry either wins and obtains
      // normal dark finalization, or a visible terminal wins before any helper can be created.
      val visibleFinalization = if (requireSuccessProof) commitVisibleFinalization(reason) else null
      if (visibleFinalization != null) {
        touchMonitorJob?.cancelAndJoin()
        onSnapshotChanged(visibleFinalization.snapshot)
        return visibleFinalization
      }
      val freshDarkProven = requireSuccessProof && awaitFinalConvergenceTail()
      val revealedFinalization = if (requireSuccessProof) {
        visibleTransitionJob?.join()
        commitVisibleFinalization(reason)
      } else null
      if (revealedFinalization != null) {
        touchMonitorJob?.cancelAndJoin()
        onSnapshotChanged(revealedFinalization.snapshot)
        return revealedFinalization
      }
      synchronized(stateLock) { released.compareAndSet(false, true) }
      withContext(NonCancellable) {
        visibleTransitionJob?.cancelAndJoin()
        darkTransitionJob?.cancelAndJoin()
        // Freeze periodic verification, then require the complete helper-identity/raw-zero gate
        // while the helper and its root-only identity files still exist. Exact shutdown removes
        // those files by design, so only the independent physical-touch authority is sampled
        // afterward. A slow successful shutdown must not be mistaken for helper identity loss.
        // The touch monitor remains live throughout shutdown, and the synchronous post-stop read
        // closes the last scheduler window before it is cancelled.
        verifyJob?.cancelAndJoin()
        val preStopBoundaryClear = if (freshDarkProven) beforeMutationAllowed() else false
        val exactHelperStopProven = stopHelperAndJoinBounded()
        val postStopTouchClear = if (freshDarkProven && preStopBoundaryClear) {
          withTimeoutOrNull(PHYSICAL_TOUCH_RELEASE_TIMEOUT_MILLIS) {
            while (true) {
              if (!physicalTouchClearAtMutationBoundary()) return@withTimeoutOrNull false
              if (!physicalTouchState().active) return@withTimeoutOrNull true
              delay(PHYSICAL_TOUCH_POLL_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE") false
          } == true
        } else {
          false
        }
        touchMonitorJob?.cancelAndJoin()
        val visibleAfterStop = exactHelperStopProven && postStopTouchClear &&
          physicalVisibleWindow().deadlineUptimeMillis > uptimeClock()
        updateState {
          it.copy(
            protectionMode = if (visibleAfterStop) "visible_window" else it.protectionMode,
            active = false,
            releaseReason = if (it.physicalTouchPreempted || it.failure.isNotBlank()) {
              it.releaseReason
            } else {
              reason
            }
          )
        }
        val finalSnapshot = snapshot()
        TicketActionPanelDarkLeaseFinalization(
          safe = freshDarkProven && preStopBoundaryClear && exactHelperStopProven &&
            postStopTouchClear &&
            !finalSnapshot.active && !finalSnapshot.physicalTouchPreempted &&
            finalSnapshot.failure.isBlank(),
          freshDarkProven = freshDarkProven && !visibleAfterStop,
          exactHelperStopProven = exactHelperStopProven,
          snapshot = finalSnapshot
        ).also { finalization = it }
      }
    } finally {
      finalizationMutex.unlock()
    }
  }

  private fun startTouchMonitor() {
    touchMonitorJob = scope.launch {
      while (true) {
        val currentTouch = physicalTouchState()
        if (!currentTouch.available) {
          failForLostPhysicalTouchSource(
            if (snapshot().acquiredAtUptimeMillis == 0L) "physical_touch_source_lost_during_acquire"
            else "physical_touch_source_lost"
          )
          return@launch
        }
        if (!physicalTouchClearAtMutationBoundary()) {
          if (snapshot().physicalTouchPreempted) preemptForPhysicalTouch(
            if (snapshot().acquiredAtUptimeMillis == 0L) "physical_touch_during_acquire" else "physical_touch_preempted"
          )
          return@launch
        }
        ensureDarkTransitionStarted()
        delay(PHYSICAL_TOUCH_POLL_MILLIS)
      }
    }
  }

  private fun startPeriodicVerifier() {
    verifyJob = scope.launch {
      while (true) {
        delay(PANEL_DARK_VERIFY_INTERVAL_MILLIS)
        if (!physicalTouchClearAtMutationBoundary() || snapshot().protectionMode != "dark") return@launch
        val verification = verifyHelperReadyAndZero(recordFreshZero = true)
        if (!physicalTouchClearAtMutationBoundary() || snapshot().protectionMode != "dark") return@launch
        when (verification) {
          TicketPanelDarkVerification.PROVEN -> {
            // The exact proof timestamp is recorded before the serialized verifier is released.
          }
          TicketPanelDarkVerification.HELPER_UNREADY -> {
            failForPanelVerification(
              failure = "panel_dark_helper_identity_lost",
              releaseReason = "panel_dark_helper_identity_lost"
            )
            return@launch
          }
          TicketPanelDarkVerification.PANEL_NOT_DARK -> {
            failForPanelVerification(
              failure = "panel_dark_zero_lost",
              releaseReason = "panel_dark_zero_lost"
            )
            return@launch
          }
          TicketPanelDarkVerification.VERIFIER_UNAVAILABLE -> {
            val lastConfirmed = snapshot().lastZeroConfirmedAtUptimeMillis
            val proofAge = if (lastConfirmed > 0L) {
              (uptimeClock() - lastConfirmed).coerceAtLeast(0L)
            } else {
              Long.MAX_VALUE
            }
            if (proofAge > PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS) {
              failForPanelVerification(
                failure = "panel_dark_verifier_unavailable",
                releaseReason = "panel_dark_zero_proof_stale"
              )
              return@launch
            }
          }
        }
      }
    }
  }

  private suspend fun failForPanelVerification(failure: String, releaseReason: String) {
    if (!physicalTouchClearAtMutationBoundary() || snapshot().protectionMode != "dark") return
    updateState {
      if (it.failure.isNotBlank()) {
        it.copy(active = false)
      } else {
        it.copy(
          active = false,
          failure = failure,
          releaseReason = releaseReason
        )
      }
    }
    stopHelperAndJoinBounded()
  }

  private suspend fun preemptForPhysicalTouch(reason: String) {
    updateState {
      it.copy(
        active = false,
        physicalTouchPreempted = true,
        releaseReason = reason
      )
    }
    verifyJob?.cancel()
    darkTransitionJob?.cancel()
    acquisitionVerificationJob?.cancelAndJoin()
    stopHelperAndJoinBounded()
  }

  private suspend fun failForLostPhysicalTouchSource(reason: String) {
    updateState {
      it.copy(
        active = false,
        failure = "physical_touch_source_lost",
        releaseReason = reason
      )
    }
    verifyJob?.cancel()
    darkTransitionJob?.cancel()
    acquisitionVerificationJob?.cancelAndJoin()
    stopHelperAndJoinBounded()
  }

  private fun requestEmergencyHelperStop() {
    if (!emergencyHelperStopScheduled.compareAndSet(false, true)) return
    scope.launch {
      stopHelperAndJoinBounded()
    }
  }

  private suspend fun stopHelperAndJoinBounded(): Boolean = helperStopMutex.withLock {
    if (helperStopProven) return@withLock true
    val job = helperLaunchJob ?: run {
      helperStopProven = true
      return@withLock true
    }
    // A completed launcher cannot spawn a late child. Keep its readiness identity and prove
    // one exact shutdown instead of deleting readiness then scanning all processes a second time.
    val stopped = if (job.isCompleted) {
      stopHelperProcess(allowMissingReadiness = false, removeLaunchMarker = true)
    } else {
      // Retain the marker while a cancellation-ignoring launcher could still spawn a child.
      job.cancel()
      stopHelperProcess(allowMissingReadiness = false, removeLaunchMarker = false)
      val joined = withTimeoutOrNull(PANEL_DARK_HELPER_JOIN_TIMEOUT_MILLIS) {
        job.join()
        true
      } ?: false
      // Only after launch quiesces can the final exact scan exclude late children and remove
      // the marker. Missing readiness alone is never accepted as proof.
      joined && stopHelperProcess(allowMissingReadiness = false, removeLaunchMarker = true)
    }
    if (stopped) {
      helperStopProven = true
      onDarkWriterStopped()
    } else {
      updateState {
        it.copy(
          active = false,
          failure = it.failure.ifBlank { "panel_dark_helper_stop_unproved" },
          releaseReason = when {
            it.physicalTouchPreempted || it.failure.isNotBlank() -> it.releaseReason
            else -> "panel_dark_helper_stop_unproved"
          }
        )
      }
    }
    stopped
  }

  private suspend fun stopHelperProcess(
    allowMissingReadiness: Boolean,
    removeLaunchMarker: Boolean = true
  ): Boolean {
    val result = verifyRootExecutor.runScript(
      panelDarkStopScript(helperToken, allowMissingReadiness, removeLaunchMarker),
      PANEL_DARK_STOP_TIMEOUT_MILLIS.milliseconds
    )
    if (!result.ok) return false
    val outputLines = result.stdout.lineSequence().map(String::trim).toSet()
    return "helper_stopped=1" in outputLines && "readiness_removed=1" in outputLines &&
      "telemetry_removed=1" in outputLines &&
      "wait_fifo_removed=1" in outputLines &&
      (!removeLaunchMarker || "launch_marker_removed=1" in outputLines)
  }

  private suspend fun verifyHelperReadyAndZero(
    recordFreshZero: Boolean = false,
    requiredConfirmations: Int = 1,
    deadlineUptimeMillis: Long? = null
  ): TicketPanelDarkVerification = verificationMutex.withLock {
    verifyHelperReadyAndZeroLocked(recordFreshZero, requiredConfirmations, deadlineUptimeMillis)
  }

  private suspend fun verifyHelperReadyAndZeroLocked(
    recordFreshZero: Boolean,
    requiredConfirmations: Int = 1,
    deadlineUptimeMillis: Long? = null
  ): TicketPanelDarkVerification {
    // Batching preserves each observation's existing allowance. It may consume
    // only the remaining acquisition window and cannot renew that deadline.
    val remainingMillis = deadlineUptimeMillis?.minus(uptimeClock()) ?: Long.MAX_VALUE
    if (remainingMillis <= 0L) return TicketPanelDarkVerification.VERIFIER_UNAVAILABLE
    val timeoutMillis = (PANEL_DARK_VERIFY_TIMEOUT_MILLIS * requiredConfirmations)
      .coerceAtMost(remainingMillis)
    val result = verifyRootExecutor.runScript(
      panelDarkVerifyScript(helperToken, requiredConfirmations),
      timeoutMillis.milliseconds
    )
    val outputLines = result.stdout.lineSequence().map(String::trim).toSet()
    val verification = when {
      result.ok && "helper_ready=1" in outputLines && "panel_dark=1" in outputLines &&
        (requiredConfirmations == 1 || "panel_confirmations=$requiredConfirmations" in outputLines) ->
        TicketPanelDarkVerification.PROVEN
      "helper_ready=0" in outputLines ->
        TicketPanelDarkVerification.HELPER_UNREADY
      result.exitCode == 1 && "helper_ready=1" in outputLines && "panel_dark=0" in outputLines ->
        TicketPanelDarkVerification.PANEL_NOT_DARK
      else -> TicketPanelDarkVerification.VERIFIER_UNAVAILABLE
    }
    val helperStage = outputLines.mapNotNull { line ->
      line.takeIf { it.startsWith("helper_stage=") }?.substringAfter('=')
    }.singleOrNull()?.takeIf { it in HELPER_HEALTH_STAGES }
    val helperExitCode = outputLines.mapNotNull { line ->
      line.takeIf { it.startsWith("helper_exit_code=") }?.substringAfter('=')
    }.singleOrNull()?.toIntOrNull()?.takeIf { it in 0..255 }
    updateState {
      it.copy(
        lastZeroConfirmedAtUptimeMillis = if (
          recordFreshZero && verification == TicketPanelDarkVerification.PROVEN
        ) {
          uptimeClock()
        } else {
          it.lastZeroConfirmedAtUptimeMillis
        },
        lastVerifierClassification = verification.healthToken,
        lastVerifierExitCode = result.exitCode,
        lastVerifierDurationMillis = result.durationMs.coerceAtLeast(0L),
        acquisitionVerificationMillis = if (requiredConfirmations > 1) {
          (it.acquisitionVerificationMillis ?: 0L) + result.durationMs.coerceAtLeast(0L)
        } else it.acquisitionVerificationMillis,
        helperStage = helperStage ?: it.helperStage,
        helperExitCode = helperExitCode ?: it.helperExitCode
      )
    }
    return verification
  }

  private fun updateState(transform: (TicketActionPanelDarkLeaseSnapshot) -> TicketActionPanelDarkLeaseSnapshot) {
    val updated = synchronized(stateLock) {
      transform(state).also { state = it }
    }
    onSnapshotChanged(updated)
  }

  companion object {
    internal const val PANEL_DARK_WRITE_INTERVAL_MILLIS = 5L
    internal const val PANEL_DARK_FINAL_CONVERGENCE_MILLIS = 2_500L
    internal const val PHYSICAL_TOUCH_RELEASE_TIMEOUT_MILLIS = 10_000L
    internal const val PHYSICAL_TOUCH_POLL_MILLIS = 20L
    internal const val PANEL_DARK_VERIFY_INTERVAL_MILLIS = 250L
    internal const val PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS = 3_000L
    internal const val PANEL_DARK_ACQUIRE_POLL_MILLIS = 25L
    internal const val PANEL_DARK_REQUIRED_ACQUIRE_CONFIRMATIONS = 2
    internal const val PANEL_DARK_ACQUIRE_MAX_ATTEMPTS =
      (PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS / PANEL_DARK_ACQUIRE_POLL_MILLIS).toInt() + 1
    internal const val PANEL_DARK_VERIFY_TIMEOUT_MILLIS = 1_000L
    internal const val PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS = 2_000L
    internal const val PANEL_DARK_MUTATION_REFRESH_TIMEOUT_MILLIS =
      (PANEL_DARK_VERIFY_TIMEOUT_MILLIS * 2L) + PANEL_DARK_VERIFY_INTERVAL_MILLIS
    internal const val PANEL_DARK_MAX_HOLD_MILLIS = 90_000L
    internal const val PANEL_DARK_LAUNCH_TIMEOUT_MILLIS = 2_000L
    internal const val PANEL_DARK_STOP_TIMEOUT_MILLIS = 2_000L
    // A cancelled launch can ignore coroutine cancellation until the root executor's own hard
    // bound. Keep the phone-mutation lane until that bound plus scheduling margin has elapsed and
    // the second exact cleanup has proved that no late detached child escaped.
    internal const val PANEL_DARK_HELPER_JOIN_TIMEOUT_MILLIS =
      PANEL_DARK_LAUNCH_TIMEOUT_MILLIS + 1_000L
    internal const val PANEL_DARK_STOP_POLL_MILLIS = 25L
    internal const val PANEL_DARK_STOP_WAIT_ATTEMPTS = 20
    internal const val PANEL_DARK_READINESS_WAIT_ATTEMPTS = 20
    internal const val MAX_HELPER_READINESS_FILES = 16

    internal const val HELPER_PROCESS_MARKER = "pixel_ticket_action_panel_dark"
    internal const val HELPER_READINESS_DIRECTORY =
      "/data/local/pixel-stack/run/ticket-action-panel-dark"
    internal val HELPER_HEALTH_STAGES = setOf(
      "not_observed",
      "unknown",
      "invalid",
      "launch_prepared",
      "child_started",
      "identity_ready",
      "panel_ready",
      "zero_written",
      "ready"
    )

    internal val EXACT_HELPER_CMDLINE_MATCH_FUNCTION = """
      exact_helper_cmdline_args_match() {
        cmdline_file="${'$'}1"
        marker_arg_found=0
        token_arg_found=0
        owner_pid_arg_found=0
        owner_start_arg_found=0
        [ -r "${'$'}cmdline_file" ] || return 1
        while IFS= read -r -d '' cmdline_arg; do
          [ "${'$'}cmdline_arg" = '$HELPER_PROCESS_MARKER' ] && marker_arg_found=1
          [ "${'$'}cmdline_arg" = "${'$'}helper_token" ] && token_arg_found=1
          [ "${'$'}cmdline_arg" = "${'$'}owner_pid" ] && owner_pid_arg_found=1
          [ "${'$'}cmdline_arg" = "${'$'}owner_start" ] && owner_start_arg_found=1
          if [ "${'$'}marker_arg_found" = "1" ] && [ "${'$'}token_arg_found" = "1" ] &&
             [ "${'$'}owner_pid_arg_found" = "1" ] && [ "${'$'}owner_start_arg_found" = "1" ]; then
            return 0
          fi
        done < "${'$'}cmdline_file"
        return 1
      }
    """.trimIndent()

    internal fun panelDarkLaunchScript(ownerProcessId: Int, helperToken: String): String {
      require(helperToken.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe helper token" }
      return """
      owner_pid=${ownerProcessId.coerceAtLeast(1)}
      helper_token='$helperToken'
      readiness_dir='$HELPER_READINESS_DIRECTORY'
      readiness_file="${'$'}readiness_dir/${'$'}helper_token.ready"
      launch_file="${'$'}readiness_dir/${'$'}helper_token.launch"
      stage_file="${'$'}readiness_dir/${'$'}helper_token.stage"
      exit_file="${'$'}readiness_dir/${'$'}helper_token.exit"
      wait_file="${'$'}readiness_dir/${'$'}helper_token.wait"
      command -v nohup >/dev/null 2>&1 || exit 79
      command -v mkfifo >/dev/null 2>&1 || exit 79
      mkdir -p "${'$'}readiness_dir" 2>/dev/null || exit 78
      chmod 700 "${'$'}readiness_dir" 2>/dev/null || exit 78
      [ ! -e "${'$'}readiness_file" ] || exit 78
      [ ! -e "${'$'}launch_file" ] || exit 78
      [ ! -e "${'$'}stage_file" ] || exit 78
      [ ! -e "${'$'}exit_file" ] || exit 78
      [ ! -e "${'$'}wait_file" ] || exit 78
      owner_start="${'$'}(
        line=""
        IFS= read -r line < "/proc/${'$'}owner_pid/stat" 2>/dev/null || exit 77
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        printf '%s' "${'$'}{1:-}"
      )"
      [ -n "${'$'}owner_start" ] || exit 77
      umask 077
      printf "owner_pid=%s\nowner_start=%s\n" "${'$'}owner_pid" "${'$'}owner_start" \
        > "${'$'}launch_file" 2>/dev/null || exit 78
      printf '%s\n' launch_prepared > "${'$'}stage_file" 2>/dev/null || exit 78
      mkfifo "${'$'}wait_file" 2>/dev/null || exit 78
      [ -p "${'$'}wait_file" ] || exit 78
      # This child program is one single-quoted sh argument. Keep its body free of single quotes;
      # the generated-script test parses both layers and enforces that delimiter invariant.
      nohup sh -c '
        owner_pid="${'$'}1"
        owner_start="${'$'}2"
        helper_token="${'$'}3"
        readiness_dir="${'$'}4"
        readiness_file="${'$'}readiness_dir/${'$'}helper_token.ready"
        launch_file="${'$'}readiness_dir/${'$'}helper_token.launch"
        stage_file="${'$'}readiness_dir/${'$'}helper_token.stage"
        exit_file="${'$'}readiness_dir/${'$'}helper_token.exit"
        wait_file="${'$'}readiness_dir/${'$'}helper_token.wait"
        helper_pid="${'$'}${'$'}"
        umask 077
        cleanup_helper_runtime() {
          rm -f "${'$'}readiness_file" "${'$'}wait_file" 2>/dev/null || true
        }
        write_stage() {
          case "${'$'}1" in
            child_started|identity_ready|panel_ready|zero_written|ready) ;;
            *) exit 78 ;;
          esac
          printf "%s\n" "${'$'}1" > "${'$'}stage_file" 2>/dev/null || exit 78
        }
        record_helper_exit() {
          helper_exit_code="${'$'}?"
          case "${'$'}helper_exit_code" in ""|*[!0-9]*) helper_exit_code=255 ;; esac
          printf "%s\n" "${'$'}helper_exit_code" > "${'$'}exit_file" 2>/dev/null || true
          cleanup_helper_runtime
        }
        # Install lifecycle recording before the first child-side operation. A force kill can
        # intentionally leave the exit file absent; the last allowlisted stage still identifies
        # how far the child reached without publishing commands, paths, or ticket content.
        trap "" HUP
        trap "cleanup_helper_runtime; exit 0" INT TERM
        trap record_helper_exit EXIT
        write_stage child_started
        [ -p "${'$'}wait_file" ] || exit 78
        exec 9<> "${'$'}wait_file" || exit 78
        helper_start="${'$'}(
          line=""
          IFS= read -r line < "/proc/${'$'}helper_pid/stat" 2>/dev/null || exit 77
          rest="${'$'}{line#*) }"
          set -- ${'$'}rest
          shift 19
          printf "%s" "${'$'}{1:-}"
        )"
        [ -n "${'$'}helper_start" ] || exit 77
        write_stage identity_ready
        # nohup initially ignores HUP, so retain that disposition after the inner shell starts.
        # Re-enabling HUP here would unnecessarily couple the helper to transport signals.
        read_uptime_seconds() {
          IFS=" " read -r uptime_value uptime_rest < /proc/uptime || return 1
          uptime_seconds="${'$'}{uptime_value%%.*}"
          case "${'$'}uptime_seconds" in ""|*[!0-9]*) return 1 ;; esac
          printf "%s" "${'$'}uptime_seconds"
        }
        panel=""
        for candidate in /sys/class/backlight/panel0-backlight /sys/class/backlight/*; do
          if [ -f "${'$'}candidate/brightness" ]; then panel="${'$'}candidate"; break; fi
        done
        [ -n "${'$'}panel" ] || exit 75
        write_stage panel_ready
        readiness_published=0
        helper_started_uptime="${'$'}(read_uptime_seconds)" || exit 77
        helper_deadline_uptime=${'$'}((helper_started_uptime + ${PANEL_DARK_MAX_HOLD_MILLIS / 1_000L}))
        while true; do
          [ -f "${'$'}launch_file" ] || exit 0
          helper_current_uptime="${'$'}(read_uptime_seconds)" || exit 77
          [ "${'$'}helper_current_uptime" -lt "${'$'}helper_deadline_uptime" ] || exit 0
          line=""
          IFS= read -r line < "/proc/${'$'}owner_pid/stat" 2>/dev/null || exit 0
          rest="${'$'}{line#*) }"
          set -- ${'$'}rest
          shift 19
          [ "${'$'}{1:-}" = "${'$'}owner_start" ] || exit 0
          panel_power=""
          IFS= read -r panel_power < "${'$'}panel/bl_power" 2>/dev/null || panel_power=""
          if [ "${'$'}readiness_published" != "1" ] || [ "${'$'}panel_power" != "4" ]; then
            echo 0 > "${'$'}panel/brightness" 2>/dev/null || exit 76
          fi
          if [ "${'$'}readiness_published" != "1" ]; then
            write_stage zero_written
            printf "helper_pid=%s\nhelper_start=%s\nowner_pid=%s\nowner_start=%s\n" \
              "${'$'}helper_pid" "${'$'}helper_start" "${'$'}owner_pid" "${'$'}owner_start" \
              > "${'$'}readiness_file" 2>/dev/null || exit 78
            write_stage ready
            readiness_published=1
          fi
          helper_wait_tick=""
          IFS= read -r -t${PANEL_DARK_WRITE_INTERVAL_MILLIS.toDouble() / 1_000.0} -u9 helper_wait_tick || true
        done
      ' $HELPER_PROCESS_MARKER "${'$'}owner_pid" "${'$'}owner_start" \
        "${'$'}helper_token" "${'$'}readiness_dir" </dev/null >/dev/null 2>&1 &
      helper_launcher_pid=${'$'}!
      case "${'$'}helper_launcher_pid" in ''|*[!0-9]*) exit 79 ;; esac
      # Do not poll readiness here. Each Android toybox sleep is an external process and the old
      # 20-poll loop consumed most of the launch timeout under load. Lease acquisition separately
      # requires two exact helper-identity and raw-zero proofs before authorizing any mutation.
      echo helper_launched=1
      """.trimIndent()
    }

    internal val CLEANUP_STALE_HELPERS_SCRIPT = """
      readiness_dir='$HELPER_READINESS_DIRECTORY'
      cleaned=0
      stale_removed=0
      cleanup_failed=0
      candidate_count=0
      for launch_file in "${'$'}readiness_dir"/*.launch; do
        [ -f "${'$'}launch_file" ] || continue
        candidate_count=${'$'}((candidate_count + 1))
      done
      for readiness_file in "${'$'}readiness_dir"/*.ready; do
        [ -f "${'$'}readiness_file" ] || continue
        helper_token="${'$'}{readiness_file##*/}"; helper_token="${'$'}{helper_token%.ready}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        candidate_count=${'$'}((candidate_count + 1))
      done
      for stage_file in "${'$'}readiness_dir"/*.stage; do
        [ -f "${'$'}stage_file" ] || continue
        helper_token="${'$'}{stage_file##*/}"; helper_token="${'$'}{helper_token%.stage}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.ready" ] && continue
        candidate_count=${'$'}((candidate_count + 1))
      done
      for exit_file in "${'$'}readiness_dir"/*.exit; do
        [ -f "${'$'}exit_file" ] || continue
        helper_token="${'$'}{exit_file##*/}"; helper_token="${'$'}{helper_token%.exit}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.ready" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.stage" ] && continue
        candidate_count=${'$'}((candidate_count + 1))
      done
      for wait_file in "${'$'}readiness_dir"/*.wait; do
        [ -e "${'$'}wait_file" ] || continue
        helper_token="${'$'}{wait_file##*/}"; helper_token="${'$'}{helper_token%.wait}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.ready" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.stage" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.exit" ] && continue
        candidate_count=${'$'}((candidate_count + 1))
      done
      if [ "${'$'}candidate_count" -gt $MAX_HELPER_READINESS_FILES ]; then
        echo candidate_limit_exceeded=${'$'}candidate_count
        exit 78
      fi

      read_proc_start() {
        line=""
        IFS= read -r line < "/proc/${'$'}1/stat" 2>/dev/null || return 1
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        [ -n "${'$'}{1:-}" ] || return 1
        printf '%s' "${'$'}1"
      }
      ${EXACT_HELPER_CMDLINE_MATCH_FUNCTION.prependIndent("      ")}
      load_launch() {
        launch_owner_pid=""; launch_owner_start=""
        while IFS='=' read -r key value; do
          case "${'$'}key" in
            owner_pid) [ -z "${'$'}launch_owner_pid" ] || return 1; launch_owner_pid="${'$'}value" ;;
            owner_start) [ -z "${'$'}launch_owner_start" ] || return 1; launch_owner_start="${'$'}value" ;;
            *) return 1 ;;
          esac
        done < "${'$'}launch_file"
        for numeric in "${'$'}launch_owner_pid" "${'$'}launch_owner_start"; do
          case "${'$'}numeric" in ''|*[!0-9]*) return 1 ;; esac
        done
      }
      load_readiness() {
        helper_pid=""; helper_start=""; owner_pid=""; owner_start=""
        while IFS='=' read -r key value; do
          case "${'$'}key" in
            helper_pid) [ -z "${'$'}helper_pid" ] || return 1; helper_pid="${'$'}value" ;;
            helper_start) [ -z "${'$'}helper_start" ] || return 1; helper_start="${'$'}value" ;;
            owner_pid) [ -z "${'$'}owner_pid" ] || return 1; owner_pid="${'$'}value" ;;
            owner_start) [ -z "${'$'}owner_start" ] || return 1; owner_start="${'$'}value" ;;
            *) return 1 ;;
          esac
        done < "${'$'}readiness_file"
        for numeric in "${'$'}helper_pid" "${'$'}helper_start" "${'$'}owner_pid" "${'$'}owner_start"; do
          case "${'$'}numeric" in ''|*[!0-9]*) return 1 ;; esac
        done
      }
      original_helper_alive() {
        [ -n "${'$'}helper_pid" ] && [ "${'$'}(read_proc_start "${'$'}helper_pid")" = "${'$'}helper_start" ]
      }
      exact_helper_matches() {
        cmdline="/proc/${'$'}helper_pid/cmdline"
        exact_helper_cmdline_args_match "${'$'}cmdline" || return 1
        original_helper_alive
      }
      find_exact_helper() {
        helper_pid=""; helper_start=""; exact_count=0
        for cmdline in /proc/[0-9]*/cmdline; do
          exact_helper_cmdline_args_match "${'$'}cmdline" || continue
          candidate="${'$'}{cmdline#/proc/}"; candidate="${'$'}{candidate%/cmdline}"
          case "${'$'}candidate" in ''|*[!0-9]*) return 1 ;; esac
          [ "${'$'}candidate" != "${'$'}${'$'}" ] || continue
          [ "${'$'}candidate" != "${'$'}PPID" ] || continue
          candidate_start="${'$'}(read_proc_start "${'$'}candidate")" || return 1
          exact_count=${'$'}((exact_count + 1))
          [ "${'$'}exact_count" -le 1 ] || return 1
          helper_pid="${'$'}candidate"; helper_start="${'$'}candidate_start"
        done
      }
      stop_exact_helper() {
        if original_helper_alive; then
          exact_helper_matches || return 1
          kill -TERM "${'$'}helper_pid" >/dev/null 2>&1 || true
          stop_attempt=0
          while original_helper_alive && [ "${'$'}stop_attempt" -lt $PANEL_DARK_STOP_WAIT_ATTEMPTS ]; do
            stop_attempt=${'$'}((stop_attempt + 1))
            usleep ${PANEL_DARK_STOP_POLL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025
          done
        fi
        if original_helper_alive; then
          exact_helper_matches || return 1
          kill -KILL "${'$'}helper_pid" >/dev/null 2>&1 || return 1
          stop_attempt=0
          while original_helper_alive && [ "${'$'}stop_attempt" -lt $PANEL_DARK_STOP_WAIT_ATTEMPTS ]; do
            stop_attempt=${'$'}((stop_attempt + 1))
            usleep ${PANEL_DARK_STOP_POLL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025
          done
        fi
        ! original_helper_alive
      }
      cleanup_token() {
        readiness_file="${'$'}readiness_dir/${'$'}helper_token.ready"
        launch_file="${'$'}readiness_dir/${'$'}helper_token.launch"
        stage_file="${'$'}readiness_dir/${'$'}helper_token.stage"
        exit_file="${'$'}readiness_dir/${'$'}helper_token.exit"
        wait_file="${'$'}readiness_dir/${'$'}helper_token.wait"
        case "${'$'}helper_token" in ''|*[!A-Za-z0-9._-]*) cleanup_failed=1; return ;;
        esac
        owner_pid=""; owner_start=""; helper_pid=""; helper_start=""
        launch_owner_pid=""; launch_owner_start=""
        if [ -f "${'$'}launch_file" ]; then
          load_launch || { cleanup_failed=1; return; }
          owner_pid="${'$'}launch_owner_pid"; owner_start="${'$'}launch_owner_start"
        fi
        if [ -f "${'$'}readiness_file" ]; then
          load_readiness || { cleanup_failed=1; return; }
          if [ -n "${'$'}launch_owner_pid" ]; then
            [ "${'$'}owner_pid" = "${'$'}launch_owner_pid" ] || { cleanup_failed=1; return; }
            [ "${'$'}owner_start" = "${'$'}launch_owner_start" ] || { cleanup_failed=1; return; }
          fi
          if original_helper_alive && ! exact_helper_matches; then
            cleanup_failed=1; return
          fi
        else
          [ -n "${'$'}owner_pid" ] || { cleanup_failed=1; return; }
          find_exact_helper || { cleanup_failed=1; return; }
        fi
        if original_helper_alive; then
          stop_exact_helper || { cleanup_failed=1; return; }
          cleaned=${'$'}((cleaned + 1))
        else
          stale_removed=${'$'}((stale_removed + 1))
        fi
        rm -f "${'$'}readiness_file" "${'$'}launch_file" "${'$'}stage_file" \
          "${'$'}exit_file" "${'$'}wait_file" 2>/dev/null || cleanup_failed=1
        [ ! -e "${'$'}readiness_file" ] && [ ! -e "${'$'}launch_file" ] &&
          [ ! -e "${'$'}stage_file" ] && [ ! -e "${'$'}exit_file" ] &&
          [ ! -e "${'$'}wait_file" ] || cleanup_failed=1
      }
      cleanup_orphan_files() {
        stage_file="${'$'}readiness_dir/${'$'}helper_token.stage"
        exit_file="${'$'}readiness_dir/${'$'}helper_token.exit"
        wait_file="${'$'}readiness_dir/${'$'}helper_token.wait"
        case "${'$'}helper_token" in ''|*[!A-Za-z0-9._-]*) cleanup_failed=1; return ;;
        esac
        rm -f "${'$'}stage_file" "${'$'}exit_file" "${'$'}wait_file" 2>/dev/null ||
          cleanup_failed=1
        [ ! -e "${'$'}stage_file" ] && [ ! -e "${'$'}exit_file" ] &&
          [ ! -e "${'$'}wait_file" ] || cleanup_failed=1
      }

      for launch_file in "${'$'}readiness_dir"/*.launch; do
        [ -f "${'$'}launch_file" ] || continue
        helper_token="${'$'}{launch_file##*/}"; helper_token="${'$'}{helper_token%.launch}"
        cleanup_token
      done
      for readiness_file in "${'$'}readiness_dir"/*.ready; do
        [ -f "${'$'}readiness_file" ] || continue
        helper_token="${'$'}{readiness_file##*/}"; helper_token="${'$'}{helper_token%.ready}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        cleanup_token
      done
      for stage_file in "${'$'}readiness_dir"/*.stage; do
        [ -f "${'$'}stage_file" ] || continue
        helper_token="${'$'}{stage_file##*/}"; helper_token="${'$'}{helper_token%.stage}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.ready" ] && continue
        cleanup_orphan_files
      done
      for exit_file in "${'$'}readiness_dir"/*.exit; do
        [ -f "${'$'}exit_file" ] || continue
        helper_token="${'$'}{exit_file##*/}"; helper_token="${'$'}{helper_token%.exit}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.ready" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.stage" ] && continue
        cleanup_orphan_files
      done
      for wait_file in "${'$'}readiness_dir"/*.wait; do
        [ -e "${'$'}wait_file" ] || continue
        helper_token="${'$'}{wait_file##*/}"; helper_token="${'$'}{helper_token%.wait}"
        [ -f "${'$'}readiness_dir/${'$'}helper_token.launch" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.ready" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.stage" ] && continue
        [ -f "${'$'}readiness_dir/${'$'}helper_token.exit" ] && continue
        cleanup_orphan_files
      done
      echo cleaned=${'$'}cleaned
      echo stale_removed=${'$'}stale_removed
      [ "${'$'}cleanup_failed" = "0" ] || exit 76
    """.trimIndent()

    internal fun panelDarkStopScript(
      helperToken: String,
      allowMissingReadiness: Boolean = false,
      removeLaunchMarker: Boolean = true
    ): String {
      require(helperToken.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe helper token" }
      return """
      helper_token='$helperToken'
      readiness_file='$HELPER_READINESS_DIRECTORY/$helperToken.ready'
      launch_file='$HELPER_READINESS_DIRECTORY/$helperToken.launch'
      stage_file='$HELPER_READINESS_DIRECTORY/$helperToken.stage'
      exit_file='$HELPER_READINESS_DIRECTORY/$helperToken.exit'
      wait_file='$HELPER_READINESS_DIRECTORY/$helperToken.wait'
      allow_missing=${if (allowMissingReadiness) 1 else 0}
      remove_launch=${if (removeLaunchMarker) 1 else 0}
      stop_failed() {
        echo helper_stopped=0
        echo readiness_removed=0
        echo telemetry_removed=0
        echo wait_fifo_removed=0
        echo launch_marker_removed=0
        exit 74
      }
      read_proc_start() {
        line=""
        IFS= read -r line < "/proc/${'$'}1/stat" 2>/dev/null || return 1
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        [ -n "${'$'}{1:-}" ] || return 1
        printf '%s' "${'$'}1"
      }
      ${EXACT_HELPER_CMDLINE_MATCH_FUNCTION.prependIndent("      ")}
      load_launch() {
        launch_owner_pid=""; launch_owner_start=""
        while IFS='=' read -r key value; do
          case "${'$'}key" in
            owner_pid) [ -z "${'$'}launch_owner_pid" ] || return 1; launch_owner_pid="${'$'}value" ;;
            owner_start) [ -z "${'$'}launch_owner_start" ] || return 1; launch_owner_start="${'$'}value" ;;
            *) return 1 ;;
          esac
        done < "${'$'}launch_file"
        for numeric in "${'$'}launch_owner_pid" "${'$'}launch_owner_start"; do
          case "${'$'}numeric" in ''|*[!0-9]*) return 1 ;; esac
        done
      }
      load_readiness() {
        helper_pid=""; helper_start=""; owner_pid=""; owner_start=""
        while IFS='=' read -r key value; do
          case "${'$'}key" in
            helper_pid) [ -z "${'$'}helper_pid" ] || return 1; helper_pid="${'$'}value" ;;
            helper_start) [ -z "${'$'}helper_start" ] || return 1; helper_start="${'$'}value" ;;
            owner_pid) [ -z "${'$'}owner_pid" ] || return 1; owner_pid="${'$'}value" ;;
            owner_start) [ -z "${'$'}owner_start" ] || return 1; owner_start="${'$'}value" ;;
            *) return 1 ;;
          esac
        done < "${'$'}readiness_file"
        for numeric in "${'$'}helper_pid" "${'$'}helper_start" "${'$'}owner_pid" "${'$'}owner_start"; do
          case "${'$'}numeric" in ''|*[!0-9]*) return 1 ;; esac
        done
      }
      original_helper_alive() {
        [ -n "${'$'}helper_pid" ] && [ "${'$'}(read_proc_start "${'$'}helper_pid")" = "${'$'}helper_start" ]
      }
      exact_helper_matches() {
        cmdline="/proc/${'$'}helper_pid/cmdline"
        exact_helper_cmdline_args_match "${'$'}cmdline" || return 1
        original_helper_alive
      }
      find_exact_helper_from_launch() {
        helper_pid=""; helper_start=""
        owner_pid="${'$'}launch_owner_pid"; owner_start="${'$'}launch_owner_start"
        exact_count=0
        for cmdline in /proc/[0-9]*/cmdline; do
          exact_helper_cmdline_args_match "${'$'}cmdline" || continue
          candidate="${'$'}{cmdline#/proc/}"; candidate="${'$'}{candidate%/cmdline}"
          case "${'$'}candidate" in ''|*[!0-9]*) stop_failed ;; esac
          [ "${'$'}candidate" != "${'$'}${'$'}" ] || continue
          [ "${'$'}candidate" != "${'$'}PPID" ] || continue
          candidate_start="${'$'}(read_proc_start "${'$'}candidate")" || stop_failed
          exact_count=${'$'}((exact_count + 1))
          [ "${'$'}exact_count" -le 1 ] || stop_failed
          helper_pid="${'$'}candidate"; helper_start="${'$'}candidate_start"
        done
      }
      readiness_attempt=0
      while [ ! -f "${'$'}readiness_file" ] && [ ! -f "${'$'}launch_file" ] &&
            [ "${'$'}readiness_attempt" -lt $PANEL_DARK_READINESS_WAIT_ATTEMPTS ]; do
        readiness_attempt=${'$'}((readiness_attempt + 1))
        usleep ${PANEL_DARK_STOP_POLL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025
      done
      if [ ! -f "${'$'}readiness_file" ] && [ ! -f "${'$'}launch_file" ]; then
        if [ "${'$'}allow_missing" = "1" ]; then
          rm -f "${'$'}stage_file" "${'$'}exit_file" "${'$'}wait_file" 2>/dev/null || stop_failed
          [ ! -e "${'$'}stage_file" ] && [ ! -e "${'$'}exit_file" ] &&
            [ ! -e "${'$'}wait_file" ] || stop_failed
          echo helper_stopped=1
          echo readiness_removed=1
          echo telemetry_removed=1
          echo wait_fifo_removed=1
          echo launch_marker_removed=1
          exit 0
        fi
        stop_failed
      fi
      if [ -f "${'$'}launch_file" ]; then
        load_launch || stop_failed
      fi
      if [ -f "${'$'}readiness_file" ]; then
        load_readiness || stop_failed
        [ ! -f "${'$'}launch_file" ] || {
          [ "${'$'}owner_pid" = "${'$'}launch_owner_pid" ] || stop_failed
          [ "${'$'}owner_start" = "${'$'}launch_owner_start" ] || stop_failed
        }
      else
        [ -f "${'$'}launch_file" ] || stop_failed
        find_exact_helper_from_launch
      fi
      if original_helper_alive; then
        exact_helper_matches || stop_failed
        kill -TERM "${'$'}helper_pid" >/dev/null 2>&1 || true
        stop_attempt=0
        while original_helper_alive && [ "${'$'}stop_attempt" -lt $PANEL_DARK_STOP_WAIT_ATTEMPTS ]; do
          stop_attempt=${'$'}((stop_attempt + 1))
          usleep ${PANEL_DARK_STOP_POLL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025
        done
      fi
      if original_helper_alive; then
        exact_helper_matches || stop_failed
        kill -KILL "${'$'}helper_pid" >/dev/null 2>&1 || stop_failed
        stop_attempt=0
        while original_helper_alive && [ "${'$'}stop_attempt" -lt $PANEL_DARK_STOP_WAIT_ATTEMPTS ]; do
          stop_attempt=${'$'}((stop_attempt + 1))
          usleep ${PANEL_DARK_STOP_POLL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025
        done
      fi
      original_helper_alive && stop_failed
      rm -f "${'$'}readiness_file" "${'$'}stage_file" "${'$'}exit_file" \
        "${'$'}wait_file" 2>/dev/null || stop_failed
      [ ! -e "${'$'}readiness_file" ] && [ ! -e "${'$'}stage_file" ] &&
        [ ! -e "${'$'}exit_file" ] && [ ! -e "${'$'}wait_file" ] || stop_failed
      if [ "${'$'}remove_launch" = "1" ]; then
        rm -f "${'$'}launch_file" 2>/dev/null || stop_failed
        [ ! -e "${'$'}launch_file" ] || stop_failed
      fi
      echo helper_stopped=1
      echo readiness_removed=1
      echo telemetry_removed=1
      echo wait_fifo_removed=1
      echo launch_marker_removed=${if (removeLaunchMarker) 1 else 0}
      """.trimIndent()
    }

    internal fun panelDarkReadbackScript(): String = """
      panel=""
      for candidate in /sys/class/backlight/panel0-backlight /sys/class/backlight/*; do
        if [ -f "${'$'}candidate/brightness" ]; then panel="${'$'}candidate"; break; fi
      done
      if [ -z "${'$'}panel" ]; then
        echo panel_dark=0
        exit 75
      fi
      # This Pixel driver reports requested brightness even while its blank gate
      # forces emitted brightness to zero. Exact helper/owner identity is proved above.
      power=""
      IFS= read -r power < "${'$'}panel/bl_power" 2>/dev/null || power=""
      if [ "${'$'}power" = "4" ]; then
        echo panel_dark=1
        exit 0
      fi
      current=""
      IFS= read -r current < "${'$'}panel/brightness" 2>/dev/null || current=""
      actual=""
      IFS= read -r actual < "${'$'}panel/actual_brightness" 2>/dev/null || actual=""
      if [ "${'$'}current" = "0" ] && { [ -z "${'$'}actual" ] || [ "${'$'}actual" = "0" ]; }; then
        echo panel_dark=1
        exit 0
      fi
      echo panel_dark=0
      exit 1
    """.trimIndent()

    internal fun panelDarkVerifyScript(helperToken: String, requiredConfirmations: Int = 1): String {
      require(helperToken.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe helper token" }
      require(requiredConfirmations in 1..PANEL_DARK_REQUIRED_ACQUIRE_CONFIRMATIONS)
      val observation = """
      helper_token='$helperToken'
      readiness_file='$HELPER_READINESS_DIRECTORY/$helperToken.ready'
      stage_file='$HELPER_READINESS_DIRECTORY/$helperToken.stage'
      exit_file='$HELPER_READINESS_DIRECTORY/$helperToken.exit'
      emit_helper_telemetry() {
        helper_stage=unknown
        if [ -f "${'$'}stage_file" ]; then
          helper_stage_value=""
          IFS= read -r helper_stage_value < "${'$'}stage_file" 2>/dev/null || helper_stage_value=""
          case "${'$'}helper_stage_value" in
            launch_prepared|child_started|identity_ready|panel_ready|zero_written|ready)
              helper_stage="${'$'}helper_stage_value"
              ;;
            *) helper_stage=invalid ;;
          esac
        fi
        echo helper_stage="${'$'}helper_stage"
        if [ -f "${'$'}exit_file" ]; then
          helper_exit_value=""
          IFS= read -r helper_exit_value < "${'$'}exit_file" 2>/dev/null || helper_exit_value=""
          case "${'$'}helper_exit_value" in
            ''|*[!0-9]*) ;;
            *)
              if [ "${'$'}helper_exit_value" -ge 0 ] 2>/dev/null &&
                 [ "${'$'}helper_exit_value" -le 255 ] 2>/dev/null; then
                echo helper_exit_code="${'$'}helper_exit_value"
              fi
              ;;
          esac
        fi
      }
      fail_readiness() {
        emit_helper_telemetry
        echo helper_ready=0
        echo panel_dark=0
        exit 74
      }
      read_proc_start() {
        line=""
        IFS= read -r line < "/proc/${'$'}1/stat" 2>/dev/null || return 1
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        [ -n "${'$'}{1:-}" ] || return 1
        printf '%s' "${'$'}1"
      }
      ${EXACT_HELPER_CMDLINE_MATCH_FUNCTION.prependIndent("      ")}
      helper_pid=""; helper_start=""; owner_pid=""; owner_start=""
      [ -f "${'$'}readiness_file" ] || fail_readiness
      while IFS='=' read -r key value; do
        case "${'$'}key" in
          helper_pid) [ -z "${'$'}helper_pid" ] || fail_readiness; helper_pid="${'$'}value" ;;
          helper_start) [ -z "${'$'}helper_start" ] || fail_readiness; helper_start="${'$'}value" ;;
          owner_pid) [ -z "${'$'}owner_pid" ] || fail_readiness; owner_pid="${'$'}value" ;;
          owner_start) [ -z "${'$'}owner_start" ] || fail_readiness; owner_start="${'$'}value" ;;
          *) fail_readiness ;;
        esac
      done < "${'$'}readiness_file"
      for numeric in "${'$'}helper_pid" "${'$'}helper_start" "${'$'}owner_pid" "${'$'}owner_start"; do
        case "${'$'}numeric" in ''|*[!0-9]*) fail_readiness ;; esac
      done
      [ "${'$'}helper_pid" != "${'$'}${'$'}" ] || fail_readiness
      [ "${'$'}helper_pid" != "${'$'}PPID" ] || fail_readiness
      cmdline="/proc/${'$'}helper_pid/cmdline"
      exact_helper_cmdline_args_match "${'$'}cmdline" || fail_readiness
      [ "${'$'}(read_proc_start "${'$'}helper_pid")" = "${'$'}helper_start" ] || fail_readiness
      [ "${'$'}(read_proc_start "${'$'}owner_pid")" = "${'$'}owner_start" ] || fail_readiness
      emit_helper_telemetry
      echo helper_ready=1
      ${panelDarkReadbackScript()}
      """.trimIndent()
      if (requiredConfirmations == 1) return observation
      // Two distinct full identity/readback observations share one root transport. A subshell
      // contains each verifier's exit; only the final successful pair may authorize acquisition.
      return """
      verify_once() (
      $observation
      )
      confirmations=0
      while [ "${'$'}confirmations" -lt "$requiredConfirmations" ]; do
        observation="${'$'}(verify_once)"
        status=${'$'}?
        if [ "${'$'}status" -ne 0 ]; then
          printf '%s\n' "${'$'}observation"
          exit "${'$'}status"
        fi
        confirmations=${'$'}((confirmations + 1))
        if [ "${'$'}confirmations" -lt "$requiredConfirmations" ]; then
          usleep ${PANEL_DARK_ACQUIRE_POLL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025 || exit 76
        fi
      done
      printf '%s\n' "${'$'}observation"
      echo panel_confirmations=${'$'}confirmations
      """.trimIndent()
    }
  }
}
