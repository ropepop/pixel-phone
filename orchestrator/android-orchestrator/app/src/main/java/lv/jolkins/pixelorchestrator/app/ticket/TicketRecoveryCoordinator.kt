package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import lv.jolkins.pixelorchestrator.rootexec.RootResult

internal enum class TicketRecoveryMode {
  ACTIVE_SOFT,
  FRESH_RESET
}

internal class TicketRecoveryCoordinator(
  private val context: Context,
  private val scope: CoroutineScope,
  private val rootExecutor: TicketRootCommandWorker,
  private val runInput: suspend (String, String) -> RootResult,
  private val collapseSystemUi: suspend (String) -> Unit,
  private val scheduleBrightnessGuard: (String) -> Unit,
  private val stateMemory: TicketViviStateMemory,
  private val returnToOrchestrator: suspend () -> Unit,
  private val onHardReset: (String) -> Unit = {},
  private val onRecoveryResult: (String, TicketRecoveryMode, Boolean) -> Unit = { _, _, _ -> }
) {
  private val lock = Any()
  private var job: Job? = null
  @Volatile private var state: String = STATE_IDLE
  @Volatile private var currentReason: String? = null
  @Volatile private var currentMode: TicketRecoveryMode? = null
  @Volatile private var pendingReason: String? = null
  @Volatile private var pendingMode: TicketRecoveryMode? = null
  @Volatile private var lastResult: String = "none"
  @Volatile private var lastStep: String = "idle"
  @Volatile private var startedAtMillis: Long = 0L
  @Volatile private var completedAtMillis: Long = 0L
  private val autopilot = TicketAutopilot(
    context = context,
    rootExecutor = rootExecutor,
    runInput = runInput,
    collapseSystemUi = collapseSystemUi,
    scheduleBrightnessGuard = scheduleBrightnessGuard,
    stateMemory = stateMemory,
    onHardReset = onHardReset
  )

  fun schedule(reason: String, mode: TicketRecoveryMode = TicketRecoveryMode.ACTIVE_SOFT) {
    synchronized(lock) {
      val active = job
      if (active?.isActive == true) {
        pendingReason = reason
        pendingMode = mode
        Log.i(TAG, "ticket_recovery_coalesced reason=$reason mode=$mode current=$currentReason")
        return
      }
      currentReason = reason
      currentMode = mode
      pendingReason = null
      pendingMode = null
      state = STATE_RUNNING
      lastResult = "running"
      lastStep = "scheduled"
      startedAtMillis = SystemClock.elapsedRealtime()
      completedAtMillis = 0L
      job = scope.launch {
        var nextReason = reason
        var nextMode = mode
        while (true) {
          val result = run(nextReason, nextMode)
          onRecoveryResult(nextReason, nextMode, result == RESULT_SUCCEEDED)
          val shouldContinue = synchronized(lock) {
            lastResult = result
            state = if (result == RESULT_SUCCEEDED) STATE_SUCCEEDED else STATE_FAILED
            completedAtMillis = SystemClock.elapsedRealtime()
            val queuedReason = pendingReason
            val queuedMode = pendingMode ?: TicketRecoveryMode.ACTIVE_SOFT
            if (queuedReason == null) {
              job = null
              false
            } else {
              currentReason = queuedReason
              currentMode = queuedMode
              pendingReason = null
              pendingMode = null
              state = STATE_RUNNING
              lastResult = "running"
              lastStep = "scheduled"
              startedAtMillis = SystemClock.elapsedRealtime()
              completedAtMillis = 0L
              nextReason = queuedReason
              nextMode = queuedMode
              true
            }
          }
          if (!shouldContinue) {
            break
          }
        }
      }
    }
  }

  fun cancel() {
    synchronized(lock) {
      job?.cancel()
      job = null
      state = STATE_IDLE
      currentReason = null
      currentMode = null
      pendingReason = null
      pendingMode = null
      lastStep = "cancelled"
    }
  }

  fun snapshot(nowMillis: Long): TicketRecoveryHealth {
    return TicketRecoveryHealth(
      state = state,
      currentReason = currentReason,
      currentMode = currentMode?.name?.lowercase(),
      pendingReason = pendingReason,
      pendingMode = pendingMode?.name?.lowercase(),
      lastResult = lastResult,
      lastStep = lastStep,
      startedAgoMillis = startedAtMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) },
      completedAgoMillis = completedAtMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) }
    )
  }

  private suspend fun run(reason: String, mode: TicketRecoveryMode): String {
    Log.i(TAG, "ticket_recovery_start reason=$reason mode=$mode")
    val fresh = mode == TicketRecoveryMode.FRESH_RESET
    val result = autopilot.driveToTicketDetail(
      reason = "recovery:$reason",
      forceFreshLaunch = fresh,
      allowControlCodePopup = !fresh,
      restartPolicy = if (fresh) {
        TicketAutopilotRestartPolicy.FRESH_RECOVERY
      } else {
        TicketAutopilotRestartPolicy.SOFT_RECOVERY
      },
      onStep = { step -> lastStep = step }
    )
    return if (result.success) {
      Log.i(TAG, "ticket_recovery_succeeded reason=$reason mode=$mode step=${result.step}")
      RESULT_SUCCEEDED
    } else {
      lastStep = result.step
      Log.w(TAG, "ticket_recovery_failed reason=$reason mode=$mode state=${result.state} step=${result.step}")
      if (fresh) {
        returnToOrchestrator()
      }
      return RESULT_FAILED
    }
  }

  private companion object {
    private const val TAG = "TicketRecoveryCoordinator"
    private const val STATE_IDLE = "idle"
    private const val STATE_RUNNING = "running"
    private const val STATE_SUCCEEDED = "succeeded"
    private const val STATE_FAILED = "failed"
    private const val RESULT_SUCCEEDED = "succeeded"
    private const val RESULT_FAILED = "failed"
  }
}
