package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRootPhysicalTouchState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

internal data class TicketActionPanelDarkLeaseSnapshot(
  val active: Boolean = false,
  val ownerActionId: String = "",
  val acquiredAtUptimeMillis: Long = 0L,
  val lastZeroConfirmedAtUptimeMillis: Long = 0L,
  val failure: String = "",
  val physicalTouchPreempted: Boolean = false,
  val releaseReason: String = "idle",
  val mutationMayHaveDispatched: Boolean = false
)

private enum class TicketPanelDarkVerification {
  PROVEN,
  HELPER_UNREADY,
  PANEL_NOT_DARK,
  VERIFIER_UNAVAILABLE
}

/**
 * Holds the physical backlight at zero for one Ticket action. TouchBrightnessRuntime remains the
 * physical-input authority: its root-confirmed touch projection cancels this helper immediately,
 * after which the action must reconcile any already-dispatched phone mutation and never replay it.
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
  private val helperToken: String = "ticket-panel-${UUID.randomUUID()}"
) {
  private val released = AtomicBoolean(false)
  private val emergencyHelperStopScheduled = AtomicBoolean(false)
  private val helperStopMutex = Mutex()
  private val stateLock = Any()
  @Volatile private var helperJob: Job? = null
  @Volatile private var touchMonitorJob: Job? = null
  @Volatile private var verifyJob: Job? = null
  @Volatile private var helperStopProven = false
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

    helperJob = scope.launch {
      val result = clampRootExecutor.runScript(
        panelDarkHoldScript(ownerProcessId, helperToken),
        PANEL_DARK_MAX_HOLD_MILLIS.milliseconds
      )
      currentCoroutineContext().ensureActive()
      if (!released.get() && !snapshot().physicalTouchPreempted) {
        updateState {
          it.copy(
            active = false,
            failure = if (result.ok) "panel_dark_helper_exited" else "panel_dark_helper_failed",
            releaseReason = "panel_dark_helper_ended"
          )
        }
      }
    }

    val readyDeadline = uptimeClock() + PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS
    var consecutiveReadyZeroConfirmations = 0
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
      if (currentTouch.active) {
        preemptForPhysicalTouch("physical_touch_during_acquire")
        return false
      }
      val verification = if (helperJob?.isActive == true) {
        verifyHelperReadyAndZero()
      } else {
        TicketPanelDarkVerification.HELPER_UNREADY
      }
      lastVerification = verification
      if (verification == TicketPanelDarkVerification.PROVEN) {
        consecutiveReadyZeroConfirmations += 1
      } else {
        consecutiveReadyZeroConfirmations = 0
      }
      if (consecutiveReadyZeroConfirmations >= PANEL_DARK_REQUIRED_ACQUIRE_CONFIRMATIONS) {
        val now = uptimeClock()
        updateState {
          it.copy(
            active = true,
            acquiredAtUptimeMillis = now,
            lastZeroConfirmedAtUptimeMillis = now,
            failure = "",
            releaseReason = "active"
          )
        }
        startMonitors()
        return true
      }
      delay(PANEL_DARK_ACQUIRE_POLL_MILLIS)
    }
    updateState {
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
    stopHelperAndJoinBounded()
    return false
  }

  fun beforeMutationAllowed(): Boolean {
    // Read the physical source synchronously at the mutation boundary as well as via the 20 ms
    // monitor. This closes the scheduler window between a real finger-down event and the monitor
    // coroutine's next turn.
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
      requestEmergencyHelperStop()
      return false
    }
    if (currentTouch.active) {
      updateState {
        it.copy(
          active = false,
          physicalTouchPreempted = true,
          releaseReason = "physical_touch_at_mutation_boundary"
        )
      }
      verifyJob?.cancel()
      requestEmergencyHelperStop()
      return false
    }
    val current = snapshot()
    val zeroProofAge = if (current.lastZeroConfirmedAtUptimeMillis > 0L) {
      (uptimeClock() - current.lastZeroConfirmedAtUptimeMillis).coerceAtLeast(0L)
    } else {
      Long.MAX_VALUE
    }
    if (current.active && zeroProofAge > PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS) {
      updateState {
        it.copy(
          active = false,
          failure = "panel_dark_verifier_unavailable",
          releaseReason = "panel_dark_zero_proof_stale_at_mutation_boundary"
        )
      }
      verifyJob?.cancel()
      requestEmergencyHelperStop()
      return false
    }
    return current.active && !current.physicalTouchPreempted && current.failure.isBlank()
  }

  fun markMutationMayHaveDispatched() {
    updateState { it.copy(mutationMayHaveDispatched = true) }
  }

  suspend fun release(reason: String) {
    if (!released.compareAndSet(false, true)) return
    withContext(NonCancellable) {
      touchMonitorJob?.cancelAndJoin()
      verifyJob?.cancelAndJoin()
      stopHelperAndJoinBounded()
      updateState {
        it.copy(
          active = false,
          releaseReason = if (it.physicalTouchPreempted || it.failure.isNotBlank()) {
            it.releaseReason
          } else {
            reason
          }
        )
      }
    }
  }

  private fun startMonitors() {
    touchMonitorJob = scope.launch {
      while (true) {
        val currentTouch = physicalTouchState()
        if (!currentTouch.available) {
          failForLostPhysicalTouchSource("physical_touch_source_lost")
          return@launch
        }
        if (currentTouch.active) {
          preemptForPhysicalTouch("physical_touch_preempted")
          return@launch
        }
        delay(PHYSICAL_TOUCH_POLL_MILLIS)
      }
    }
    verifyJob = scope.launch {
      while (true) {
        delay(PANEL_DARK_VERIFY_INTERVAL_MILLIS)
        if (helperJob?.isActive != true) {
          failForPanelVerification(
            failure = "panel_dark_helper_exited",
            releaseReason = "panel_dark_helper_ended"
          )
          return@launch
        }
        when (verifyHelperReadyAndZero()) {
          TicketPanelDarkVerification.PROVEN -> {
            updateState { it.copy(lastZeroConfirmedAtUptimeMillis = uptimeClock()) }
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
    updateState {
      it.copy(
        active = false,
        failure = failure,
        releaseReason = releaseReason
      )
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
    val job = helperJob ?: run {
      helperStopProven = true
      return@withLock true
    }
    val initiallyCompleted = job.isCompleted
    // Mark the coroutine cancelled before terminating the exact root helper. Once the independent
    // stop command makes the blocking executor return, ensureActive() must not publish a spurious
    // helper-exited failure over the action's real terminal/preemption state.
    job.cancel()
    var stopProven = stopHelperProcess(allowMissingReadiness = initiallyCompleted)
    val joined = withTimeoutOrNull(PANEL_DARK_HELPER_JOIN_TIMEOUT_MILLIS) {
      job.join()
      true
    } ?: false
    if (!stopProven && joined) {
      // A helper that exited between the strict identity read and cancellation can already have
      // removed its own file. Re-run only in the now-completed state so missing readiness is safe.
      stopProven = stopHelperProcess(allowMissingReadiness = true)
    }
    val stopped = stopProven && joined
    if (stopped) {
      helperStopProven = true
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

  private suspend fun stopHelperProcess(allowMissingReadiness: Boolean): Boolean {
    val result = verifyRootExecutor.runScript(
      panelDarkStopScript(helperToken, allowMissingReadiness),
      PANEL_DARK_STOP_TIMEOUT_MILLIS.milliseconds
    )
    if (!result.ok) return false
    val outputLines = result.stdout.lineSequence().map(String::trim).toSet()
    return "helper_stopped=1" in outputLines && "readiness_removed=1" in outputLines
  }

  private suspend fun verifyHelperReadyAndZero(): TicketPanelDarkVerification {
    val result = verifyRootExecutor.runScript(
      panelDarkVerifyScript(helperToken),
      PANEL_DARK_VERIFY_TIMEOUT_MILLIS.milliseconds
    )
    val outputLines = result.stdout.lineSequence().map(String::trim).toSet()
    return when {
      result.ok && "helper_ready=1" in outputLines && "panel_dark=1" in outputLines ->
        TicketPanelDarkVerification.PROVEN
      "helper_ready=0" in outputLines ->
        TicketPanelDarkVerification.HELPER_UNREADY
      result.exitCode == 1 && "helper_ready=1" in outputLines && "panel_dark=0" in outputLines ->
        TicketPanelDarkVerification.PANEL_NOT_DARK
      else -> TicketPanelDarkVerification.VERIFIER_UNAVAILABLE
    }
  }

  private fun updateState(transform: (TicketActionPanelDarkLeaseSnapshot) -> TicketActionPanelDarkLeaseSnapshot) {
    val updated = synchronized(stateLock) {
      transform(state).also { state = it }
    }
    onSnapshotChanged(updated)
  }

  companion object {
    internal const val PANEL_DARK_WRITE_INTERVAL_MILLIS = 25L
    internal const val PHYSICAL_TOUCH_POLL_MILLIS = 20L
    internal const val PANEL_DARK_VERIFY_INTERVAL_MILLIS = 250L
    internal const val PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS = 3_000L
    internal const val PANEL_DARK_ACQUIRE_POLL_MILLIS = 25L
    internal const val PANEL_DARK_REQUIRED_ACQUIRE_CONFIRMATIONS = 2
    internal const val PANEL_DARK_ACQUIRE_MAX_ATTEMPTS =
      (PANEL_DARK_ACQUIRE_TIMEOUT_MILLIS / PANEL_DARK_ACQUIRE_POLL_MILLIS).toInt() + 1
    internal const val PANEL_DARK_VERIFY_TIMEOUT_MILLIS = 1_000L
    internal const val PANEL_DARK_MAX_ZERO_CONFIRMATION_AGE_MILLIS = 2_000L
    internal const val PANEL_DARK_MAX_HOLD_MILLIS = 90_000L
    internal const val PANEL_DARK_STOP_TIMEOUT_MILLIS = 2_000L
    internal const val PANEL_DARK_HELPER_JOIN_TIMEOUT_MILLIS = 1_000L
    internal const val PANEL_DARK_STOP_POLL_MILLIS = 25L
    internal const val PANEL_DARK_STOP_WAIT_ATTEMPTS = 20
    internal const val PANEL_DARK_READINESS_WAIT_ATTEMPTS = 20
    internal const val MAX_HELPER_READINESS_FILES = 16

    internal const val HELPER_PROCESS_MARKER = "pixel_ticket_action_panel_dark"
    internal const val HELPER_READINESS_DIRECTORY =
      "/data/local/pixel-stack/run/ticket-action-panel-dark"

    internal fun panelDarkHoldScript(ownerProcessId: Int, helperToken: String): String {
      require(helperToken.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe helper token" }
      return """
      owner_pid=${ownerProcessId.coerceAtLeast(1)}
      helper_token='$helperToken'
      readiness_dir='$HELPER_READINESS_DIRECTORY'
      readiness_file="${'$'}readiness_dir/${'$'}helper_token.ready"
      owner_start="${'$'}(
        line="${'$'}(cat "/proc/${'$'}owner_pid/stat" 2>/dev/null)" || exit 77
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        printf '%s' "${'$'}{1:-}"
      )"
      [ -n "${'$'}owner_start" ] || exit 77
      exec sh -c '
        owner_pid="${'$'}1"
        owner_start="${'$'}2"
        helper_token="${'$'}3"
        readiness_dir="${'$'}4"
        readiness_file="${'$'}readiness_dir/${'$'}helper_token.ready"
        helper_pid="${'$'}${'$'}"
        helper_start="${'$'}(
          line="${'$'}(cat "/proc/${'$'}helper_pid/stat" 2>/dev/null)" || exit 77
          rest="${'$'}{line#*) }"
          set -- ${'$'}rest
          shift 19
          printf "%s" "${'$'}{1:-}"
        )"
        [ -n "${'$'}helper_start" ] || exit 77
        mkdir -p "${'$'}readiness_dir" 2>/dev/null || exit 78
        chmod 700 "${'$'}readiness_dir" 2>/dev/null || exit 78
        umask 077
        cleanup_readiness() { rm -f "${'$'}readiness_file" 2>/dev/null || true; }
        trap "cleanup_readiness; exit 0" HUP INT TERM
        trap cleanup_readiness EXIT
        panel=""
        for candidate in /sys/class/backlight/panel0-backlight /sys/class/backlight/*; do
          if [ -f "${'$'}candidate/brightness" ]; then panel="${'$'}candidate"; break; fi
        done
        [ -n "${'$'}panel" ] || exit 75
        readiness_published=0
        while true; do
          line="${'$'}(cat "/proc/${'$'}owner_pid/stat" 2>/dev/null)" || exit 0
          rest="${'$'}{line#*) }"
          set -- ${'$'}rest
          shift 19
          [ "${'$'}{1:-}" = "${'$'}owner_start" ] || exit 0
          echo 0 > "${'$'}panel/brightness" 2>/dev/null || exit 76
          if [ "${'$'}readiness_published" != "1" ]; then
            printf "helper_pid=%s\nhelper_start=%s\nowner_pid=%s\nowner_start=%s\n" \
              "${'$'}helper_pid" "${'$'}helper_start" "${'$'}owner_pid" "${'$'}owner_start" \
              > "${'$'}readiness_file" 2>/dev/null || exit 78
            readiness_published=1
          fi
          usleep ${PANEL_DARK_WRITE_INTERVAL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025
        done
      ' $HELPER_PROCESS_MARKER "${'$'}owner_pid" "${'$'}owner_start" \
        "${'$'}helper_token" "${'$'}readiness_dir"
      """.trimIndent()
    }

    internal val CLEANUP_STALE_HELPERS_SCRIPT = """
      readiness_dir='$HELPER_READINESS_DIRECTORY'
      cleaned=0
      stale_removed=0
      cleanup_failed=0
      candidate_count=0
      for readiness_file in "${'$'}readiness_dir"/*.ready; do
        [ -f "${'$'}readiness_file" ] || continue
        candidate_count=${'$'}((candidate_count + 1))
        if [ "${'$'}candidate_count" -gt $MAX_HELPER_READINESS_FILES ]; then
          echo candidate_limit_exceeded=${'$'}candidate_count
          exit 78
        fi
      done

      read_proc_start() {
        line="${'$'}(cat "/proc/${'$'}1/stat" 2>/dev/null)" || return 1
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        [ -n "${'$'}{1:-}" ] || return 1
        printf '%s' "${'$'}1"
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
      exact_helper_matches() {
        cmdline="/proc/${'$'}helper_pid/cmdline"
        [ -r "${'$'}cmdline" ] || return 1
        grep -Fzqx '$HELPER_PROCESS_MARKER' "${'$'}cmdline" 2>/dev/null || return 1
        grep -Fzqx "${'$'}helper_token" "${'$'}cmdline" 2>/dev/null || return 1
        grep -Fzqx "${'$'}owner_pid" "${'$'}cmdline" 2>/dev/null || return 1
        grep -Fzqx "${'$'}owner_start" "${'$'}cmdline" 2>/dev/null || return 1
        [ "${'$'}(read_proc_start "${'$'}helper_pid")" = "${'$'}helper_start" ] || return 1
        [ "${'$'}(read_proc_start "${'$'}owner_pid")" = "${'$'}owner_start" ] || return 1
      }

      for readiness_file in "${'$'}readiness_dir"/*.ready; do
        [ -f "${'$'}readiness_file" ] || continue
        filename="${'$'}{readiness_file##*/}"
        helper_token="${'$'}{filename%.ready}"
        case "${'$'}helper_token" in ''|*[!A-Za-z0-9._-]*)
          rm -f "${'$'}readiness_file" 2>/dev/null || cleanup_failed=1
          stale_removed=${'$'}((stale_removed + 1))
          continue
          ;;
        esac
        if ! load_readiness ||
           [ "${'$'}helper_pid" = "${'$'}${'$'}" ] ||
           [ "${'$'}helper_pid" = "${'$'}PPID" ] ||
           ! exact_helper_matches; then
          rm -f "${'$'}readiness_file" 2>/dev/null || cleanup_failed=1
          stale_removed=${'$'}((stale_removed + 1))
          continue
        fi
        if kill -TERM "${'$'}helper_pid" >/dev/null 2>&1; then
          cleaned=${'$'}((cleaned + 1))
          rm -f "${'$'}readiness_file" 2>/dev/null || cleanup_failed=1
        elif [ ! -d "/proc/${'$'}helper_pid" ]; then
          stale_removed=${'$'}((stale_removed + 1))
          rm -f "${'$'}readiness_file" 2>/dev/null || cleanup_failed=1
        else
          cleanup_failed=1
        fi
      done
      echo cleaned=${'$'}cleaned
      echo stale_removed=${'$'}stale_removed
      [ "${'$'}cleanup_failed" = "0" ] || exit 76
    """.trimIndent()

    internal fun panelDarkStopScript(
      helperToken: String,
      allowMissingReadiness: Boolean = false
    ): String {
      require(helperToken.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe helper token" }
      return """
      helper_token='$helperToken'
      readiness_file='$HELPER_READINESS_DIRECTORY/$helperToken.ready'
      allow_missing=${if (allowMissingReadiness) 1 else 0}
      stop_failed() {
        echo helper_stopped=0
        echo readiness_removed=0
        exit 74
      }
      read_proc_start() {
        line="${'$'}(cat "/proc/${'$'}1/stat" 2>/dev/null)" || return 1
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        [ -n "${'$'}{1:-}" ] || return 1
        printf '%s' "${'$'}1"
      }
      readiness_attempt=0
      while [ ! -f "${'$'}readiness_file" ] &&
            [ "${'$'}readiness_attempt" -lt $PANEL_DARK_READINESS_WAIT_ATTEMPTS ]; do
        readiness_attempt=${'$'}((readiness_attempt + 1))
        usleep ${PANEL_DARK_STOP_POLL_MILLIS * 1_000L} 2>/dev/null || sleep 0.025
      done
      if [ ! -f "${'$'}readiness_file" ]; then
        if [ "${'$'}allow_missing" = "1" ]; then
          echo helper_stopped=1
          echo readiness_removed=1
          exit 0
        fi
        stop_failed
      fi
      helper_pid=""; helper_start=""; owner_pid=""; owner_start=""
      while IFS='=' read -r key value; do
        case "${'$'}key" in
          helper_pid) [ -z "${'$'}helper_pid" ] || stop_failed; helper_pid="${'$'}value" ;;
          helper_start) [ -z "${'$'}helper_start" ] || stop_failed; helper_start="${'$'}value" ;;
          owner_pid) [ -z "${'$'}owner_pid" ] || stop_failed; owner_pid="${'$'}value" ;;
          owner_start) [ -z "${'$'}owner_start" ] || stop_failed; owner_start="${'$'}value" ;;
          *) stop_failed ;;
        esac
      done < "${'$'}readiness_file"
      for numeric in "${'$'}helper_pid" "${'$'}helper_start" "${'$'}owner_pid" "${'$'}owner_start"; do
        case "${'$'}numeric" in ''|*[!0-9]*) stop_failed ;; esac
      done
      [ "${'$'}helper_pid" != "${'$'}${'$'}" ] || stop_failed
      [ "${'$'}helper_pid" != "${'$'}PPID" ] || stop_failed
      original_helper_alive() {
        [ "${'$'}(read_proc_start "${'$'}helper_pid")" = "${'$'}helper_start" ]
      }
      exact_helper_matches() {
        cmdline="/proc/${'$'}helper_pid/cmdline"
        [ -r "${'$'}cmdline" ] || return 1
        grep -Fzqx '$HELPER_PROCESS_MARKER' "${'$'}cmdline" 2>/dev/null || return 1
        grep -Fzqx "${'$'}helper_token" "${'$'}cmdline" 2>/dev/null || return 1
        grep -Fzqx "${'$'}owner_pid" "${'$'}cmdline" 2>/dev/null || return 1
        grep -Fzqx "${'$'}owner_start" "${'$'}cmdline" 2>/dev/null || return 1
        original_helper_alive
      }
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
      rm -f "${'$'}readiness_file" 2>/dev/null || stop_failed
      [ ! -e "${'$'}readiness_file" ] || stop_failed
      echo helper_stopped=1
      echo readiness_removed=1
      """.trimIndent()
    }

    internal fun panelDarkVerifyScript(helperToken: String): String {
      require(helperToken.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe helper token" }
      return """
      helper_token='$helperToken'
      readiness_file='$HELPER_READINESS_DIRECTORY/$helperToken.ready'
      fail_readiness() {
        echo helper_ready=0
        echo panel_dark=0
        exit 74
      }
      read_proc_start() {
        line="${'$'}(cat "/proc/${'$'}1/stat" 2>/dev/null)" || return 1
        rest="${'$'}{line#*) }"
        set -- ${'$'}rest
        shift 19
        [ -n "${'$'}{1:-}" ] || return 1
        printf '%s' "${'$'}1"
      }
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
      [ -r "${'$'}cmdline" ] || fail_readiness
      grep -Fzqx '$HELPER_PROCESS_MARKER' "${'$'}cmdline" 2>/dev/null || fail_readiness
      grep -Fzqx "${'$'}helper_token" "${'$'}cmdline" 2>/dev/null || fail_readiness
      grep -Fzqx "${'$'}owner_pid" "${'$'}cmdline" 2>/dev/null || fail_readiness
      grep -Fzqx "${'$'}owner_start" "${'$'}cmdline" 2>/dev/null || fail_readiness
      [ "${'$'}(read_proc_start "${'$'}helper_pid")" = "${'$'}helper_start" ] || fail_readiness
      [ "${'$'}(read_proc_start "${'$'}owner_pid")" = "${'$'}owner_start" ] || fail_readiness
      echo helper_ready=1
      panel=""
      for candidate in /sys/class/backlight/panel0-backlight /sys/class/backlight/*; do
        if [ -f "${'$'}candidate/brightness" ]; then panel="${'$'}candidate"; break; fi
      done
      if [ -z "${'$'}panel" ]; then
        echo panel_dark=0
        exit 75
      fi
      current="${'$'}(cat "${'$'}panel/brightness" 2>/dev/null)"
      actual="${'$'}(cat "${'$'}panel/actual_brightness" 2>/dev/null)"
      if [ "${'$'}current" = "0" ] && { [ -z "${'$'}actual" ] || [ "${'$'}actual" = "0" ]; }; then
        echo panel_dark=1
        exit 0
      fi
      echo panel_dark=0
      exit 1
      """.trimIndent()
    }
  }
}
