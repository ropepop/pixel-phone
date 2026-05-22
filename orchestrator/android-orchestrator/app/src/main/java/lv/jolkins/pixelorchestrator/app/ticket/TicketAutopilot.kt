package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

internal data class TicketAutopilotResult(
  val success: Boolean,
  val state: TicketViviRecoveryState,
  val step: String,
  val firstActionState: TicketViviRecoveryState? = null
)

internal enum class TicketAutopilotRestartPolicy {
  NEVER,
  SOFT_RECOVERY,
  FRESH_RECOVERY
}

internal class TicketAutopilot(
  private val context: Context,
  private val rootExecutor: TicketRootCommandWorker,
  private val runInput: suspend (String, String) -> RootResult,
  private val collapseSystemUi: suspend (String) -> Unit,
  private val scheduleBrightnessGuard: (String) -> Unit,
  private val stateMemory: TicketViviStateMemory,
  private val onHardReset: (String) -> Unit = {}
) {
  suspend fun observeFastState(reason: String): TicketViviStateMemorySnapshot? {
    val observation = observeState(reason)
    return stateMemory.record(
      state = observation.state,
      ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(observation.xml),
      source = observation.source,
      reason = reason
    )
  }

  suspend fun driveToTicketDetail(
    reason: String,
    forceFreshLaunch: Boolean,
    allowControlCodePopup: Boolean = false,
    restartPolicy: TicketAutopilotRestartPolicy = TicketAutopilotRestartPolicy.FRESH_RECOVERY,
    maxSteps: Int = MAX_STEPS,
    timeoutMillis: Long = OVERALL_TIMEOUT_MILLIS,
    onStep: (String) -> Unit = {}
  ): TicketAutopilotResult {
    Log.i(TAG, "ticket_autopilot_start reason=$reason force_fresh=$forceFreshLaunch restart_policy=$restartPolicy")
    onStep("start")
    if (!forceFreshLaunch) {
      recentSafeTicketDetail()?.let { recent ->
        Log.i(TAG, "ticket_autopilot_memory_fast_succeeded reason=$reason source=${recent.source}")
        onStep("memory_fast_ticket_detail")
        return TicketAutopilotResult(true, recent.state, "memory_fast_ticket_detail")
      }
      observeFastState("fast_start_$reason")?.let { fast ->
        if (fast.state == TicketViviRecoveryState.TICKET_DETAIL) {
          Log.i(TAG, "ticket_autopilot_fast_succeeded reason=$reason state=${fast.state}")
          onStep("fast_ticket_detail")
          return TicketAutopilotResult(true, fast.state, "fast_ticket_detail")
        }
      }
    }
    collapseSystemUi("ticket_autopilot:$reason")
    if (forceFreshLaunch && restartPolicy == TicketAutopilotRestartPolicy.FRESH_RECOVERY) {
      forceStopVivi(reason, onStep)
    }
    if (
      restartPolicy == TicketAutopilotRestartPolicy.FRESH_RECOVERY &&
      (forceFreshLaunch || stateMemory.current().state != TicketViviRecoveryState.TICKET_DETAIL)
    ) {
      launchVivi(reason, onStep)
    }

    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    var usedBackFallback = false
    var usedCardFallback = false
    var usedSoftRelaunch = false
    var stuckActions = 0
    var lastState = TicketViviRecoveryState.BLANK
    var lastStep = "start"
    var firstActionState: TicketViviRecoveryState? = null

    for (step in 1..maxSteps) {
      if (SystemClock.elapsedRealtime() >= deadline) {
        break
      }
      delay(STEP_SETTLE_MILLIS)
      val observation = observeState(reason)
      val state = observation.state
      val repeatedState = step > 1 && state == lastState
      lastState = state
      lastStep = "step_${step}_${state.name.lowercase()}"
      onStep(lastStep)
      Log.i(TAG, "ticket_autopilot_step reason=$reason step=$step state=$state")

      when (state) {
        TicketViviRecoveryState.TICKET_DETAIL -> {
          Log.i(TAG, "ticket_autopilot_succeeded reason=$reason steps=$step")
          return TicketAutopilotResult(true, state, lastStep, firstActionState)
        }

        TicketViviRecoveryState.CONTROL_CODE_POPUP -> {
          if (allowControlCodePopup) {
            Log.i(TAG, "ticket_autopilot_holding_control_code reason=$reason steps=$step")
            return TicketAutopilotResult(true, state, lastStep, firstActionState)
          }
          firstActionState = firstActionState ?: state
          stuckActions += if (closePopup(observation.xml, "control_code_popup")) 0 else 1
        }

        TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (closePopup(observation.xml, "control_code_result")) 0 else 1
        }

        TicketViviRecoveryState.DISMISSIBLE_BLOCKER -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (closePopup(observation.xml, "dismissible_blocker")) 0 else 1
        }

        TicketViviRecoveryState.CART_OR_CHECKOUT -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (leaveCartOrCheckout(observation.xml)) 0 else 1
        }

        TicketViviRecoveryState.TICKET_LIST_WITH_CARD -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (openTicketCard(observation.xml)) {
            usedCardFallback = true
            0
          } else {
            1
          }
        }

        TicketViviRecoveryState.TICKET_LIST_EMPTY -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (openTimeTicketTab(observation.xml)) 0 else 1
        }

        TicketViviRecoveryState.OTHER_VIVI_TAB -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (openTicketsTab(observation.xml)) 0 else 1
        }

        TicketViviRecoveryState.SETTINGS_OR_PROFILE,
        TicketViviRecoveryState.UNKNOWN_VIVI -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (openTicketsTab(observation.xml)) {
            0
          } else if (!usedBackFallback) {
            usedBackFallback = true
            if (goBack("recover_from_${state.name.lowercase()}")) 0 else 1
          } else {
            1
          }
        }

        TicketViviRecoveryState.OUTSIDE_VIVI -> {
          firstActionState = firstActionState ?: state
          collapseSystemUi("ticket_autopilot_outside_vivi")
          stuckActions += if (restartPolicy == TicketAutopilotRestartPolicy.NEVER) {
            1
          } else {
            launchVivi("outside_vivi", onStep)
            delay(SOFT_LAUNCH_TICKETS_TAB_SETTLE_MILLIS)
            if (openTicketsTab(observation.xml)) {
              delay(SOFT_LAUNCH_TICKET_STEP_SETTLE_MILLIS)
              openTimeTicketTab("")
              delay(SOFT_LAUNCH_TICKET_STEP_SETTLE_MILLIS)
              tapFirstTicketCardFallback()
              0
            } else {
              1
            }
          }
        }

        TicketViviRecoveryState.BLANK -> {
          firstActionState = firstActionState ?: state
          stuckActions += if (
            restartPolicy != TicketAutopilotRestartPolicy.NEVER &&
            repeatedState &&
            !usedSoftRelaunch
          ) {
            usedSoftRelaunch = true
            launchVivi("blank_$reason", onStep)
            0
          } else if (!usedCardFallback && step > 2) {
            usedCardFallback = true
            if (tapFirstTicketCardFallback()) 0 else 1
          } else if (openTicketsTab(observation.xml)) {
            0
          } else {
            1
          }
        }
      }

      if (repeatedState) {
        stuckActions += 1
      }

      if (stuckActions >= STUCK_ACTION_LIMIT) {
        if (restartPolicy == TicketAutopilotRestartPolicy.NEVER) {
          onStep("uncertain_hold")
          Log.w(TAG, "ticket_autopilot_uncertain_hold reason=$reason state=$state")
          return TicketAutopilotResult(false, state, "uncertain_hold")
        }
        if (restartPolicy == TicketAutopilotRestartPolicy.SOFT_RECOVERY && !usedSoftRelaunch) {
          usedSoftRelaunch = true
          onStep("stuck_soft_relaunch")
          Log.w(TAG, "ticket_autopilot_stuck_soft_relaunch reason=$reason state=$state")
        } else if (restartPolicy == TicketAutopilotRestartPolicy.SOFT_RECOVERY) {
          onStep("soft_recovery_needs_attention")
          Log.w(TAG, "ticket_autopilot_soft_recovery_needs_attention reason=$reason state=$state")
          return TicketAutopilotResult(false, state, "soft_recovery_needs_attention")
        } else {
          onStep("stuck_hard_relaunch")
          Log.w(TAG, "ticket_autopilot_stuck_hard_relaunch reason=$reason state=$state")
          forceStopVivi("stuck_$reason", onStep)
        }
        launchVivi("stuck_$reason", onStep)
        stuckActions = 0
        usedBackFallback = false
      }
    }

    val finalObservation = observeState(reason)
    if (finalObservation.state == TicketViviRecoveryState.TICKET_DETAIL) {
      Log.i(TAG, "ticket_autopilot_succeeded reason=$reason final=true")
      return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, "final_ticket_detail", firstActionState)
    }
    Log.w(TAG, "ticket_autopilot_failed reason=$reason state=$lastState step=$lastStep")
    return TicketAutopilotResult(false, lastState, lastStep)
  }

  private fun recentSafeTicketDetail(): TicketViviStateMemorySnapshot? {
    val current = stateMemory.current()
    val nowMillis = SystemClock.elapsedRealtime()
    val currentAgeMillis = nowMillis - current.observedAtMillis
    if (
      current.state == TicketViviRecoveryState.TICKET_DETAIL &&
      current.observedAtMillis > 0L &&
      currentAgeMillis in 0..FAST_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
    ) {
      return current
    }
    if (
      current.observedAtMillis > 0L &&
      currentAgeMillis in 0..FAST_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS &&
      current.state != TicketViviRecoveryState.TICKET_DETAIL
    ) {
      return null
    }
    return stateMemory.recentTicketDetailWithin(FAST_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS)
  }

  private suspend fun forceStopVivi(reason: String, onStep: (String) -> Unit) {
    onStep("force_stop_vivi")
    onHardReset(reason)
    val forceStop = rootExecutor.run("am force-stop ${TicketScreenConfig.VIVI_PACKAGE}", 1_500.milliseconds)
    if (!forceStop.ok) {
      Log.w(TAG, "ticket_autopilot_force_stop_failed reason=$reason stderr=${forceStop.stderr}")
    }
    delay(FRESH_RELAUNCH_DELAY_MILLIS)
  }

  private suspend fun launchVivi(reason: String, onStep: (String) -> Unit) {
    onStep("launch_vivi")
    val launchIntent = context.packageManager.getLaunchIntentForPackage(TicketScreenConfig.VIVI_PACKAGE)
    if (launchIntent == null) {
      Log.w(TAG, "ticket_autopilot_launch_intent_missing reason=$reason")
      return
    }
    runCatching {
      context.startActivity(
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      )
      scheduleBrightnessGuard("ticket_autopilot")
    }.onFailure { error ->
      Log.w(TAG, "ticket_autopilot_launch_failed reason=$reason", error)
    }
    val rootLaunch = rootExecutor.run(
      "am start -n ${TicketScreenConfig.VIVI_LAUNCH_ACTIVITY} " +
        "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
      1_500.milliseconds
    )
    if (!rootLaunch.ok) {
      Log.w(TAG, "ticket_autopilot_root_launch_failed reason=$reason stderr=${rootLaunch.stderr}")
    }
  }

  private suspend fun dumpHierarchy(): RootResult {
    return TicketScreenObserver.dumpViviHierarchy(
      rootExecutor = rootExecutor,
      reason = "autopilot",
      timeoutMillis = AUTOPILOT_ROOT_DUMP_TIMEOUT_MILLIS
    )
  }

  private suspend fun observeState(reason: String): TicketAutopilotObservation {
    val dump = dumpHierarchy()
    val xml = if (dump.ok) dump.stdout else ""
    val state = TicketViviPageEnforcer.classifyForRecovery(xml)
    stateMemory.record(
      state = state,
      ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(xml),
      source = "root",
      reason = reason
    )
    return TicketAutopilotObservation(state, xml, "root")
  }

  private suspend fun closePopup(xml: String, reason: String): Boolean {
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml) ?: return false
    return tap(action.x, action.y, reason)
  }

  private suspend fun openTicketCard(xml: String): Boolean {
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)
    if (action != null && action.x >= 0 && action.y >= 0) {
      return tap(action.x, action.y, action.reason)
    }
    return tapFirstTicketCardFallback()
  }

  private suspend fun openTicketsTab(xml: String): Boolean {
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)
    if (action != null && action.x >= 0 && action.y >= 0) {
      return tap(action.x, action.y, action.reason)
    }
    val size = displaySize()
    return tap(
      (size.width * TICKETS_TAB_X_FRACTION).roundToInt(),
      (size.height * BOTTOM_NAV_Y_FRACTION).roundToInt(),
      "fallback_open_tickets_tab"
    )
  }

  private suspend fun openTimeTicketTab(xml: String): Boolean {
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)
    if (action != null && action.x >= 0 && action.y >= 0) {
      return tap(action.x, action.y, action.reason)
    }
    val size = displaySize()
    return tap(
      (size.width * TIME_TICKET_TAB_X_FRACTION).roundToInt(),
      (size.height * TICKET_TYPE_TAB_Y_FRACTION).roundToInt(),
      "fallback_open_time_ticket_tab"
    )
  }

  private suspend fun leaveCartOrCheckout(xml: String): Boolean {
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)
    if (action != null && action.x >= 0 && action.y >= 0) {
      return tap(action.x, action.y, action.reason)
    }
    return goBack("cart_or_checkout")
  }

  private suspend fun tapFirstTicketCardFallback(): Boolean {
    val size = displaySize()
    return tap(
      (size.width * FIRST_TICKET_CARD_X_FRACTION).roundToInt(),
      (size.height * FIRST_TICKET_CARD_Y_FRACTION).roundToInt(),
      "fallback_open_ticket_card"
    )
  }

  private suspend fun goBack(reason: String): Boolean {
    val result = runInput("input keyevent KEYCODE_BACK", "ticket_autopilot:$reason")
    if (!result.ok) {
      Log.w(TAG, "ticket_autopilot_back_failed reason=$reason stderr=${result.stderr}")
    }
    return result.ok
  }

  private suspend fun tap(x: Int, y: Int, reason: String): Boolean {
    if (x < 0 || y < 0) {
      return goBack(reason)
    }
    val result = runInput("input tap $x $y", "ticket_autopilot:$reason")
    if (!result.ok) {
      Log.w(TAG, "ticket_autopilot_tap_failed reason=$reason stderr=${result.stderr}")
    }
    return result.ok
  }

  private suspend fun displaySize(): DisplaySize {
    val result = rootExecutor.runScript("wm size 2>/dev/null | head -n 1", 800.milliseconds)
    val match = Regex("""(\d+)x(\d+)""").find(result.stdout)
    if (match != null) {
      return DisplaySize(
        width = match.groupValues[1].toInt(),
        height = match.groupValues[2].toInt()
      )
    }
    val metrics = context.resources.displayMetrics
    return DisplaySize(metrics.widthPixels, metrics.heightPixels)
  }

  private data class DisplaySize(
    val width: Int,
    val height: Int
  )

  private data class TicketAutopilotObservation(
    val state: TicketViviRecoveryState,
    val xml: String,
    val source: String
  )

  private companion object {
    private const val TAG = "TicketAutopilot"
    private const val FRESH_RELAUNCH_DELAY_MILLIS = 300L
    private const val SOFT_LAUNCH_TICKETS_TAB_SETTLE_MILLIS = 1_200L
    private const val SOFT_LAUNCH_TICKET_STEP_SETTLE_MILLIS = 650L
    private const val STEP_SETTLE_MILLIS = 240L
    private const val OVERALL_TIMEOUT_MILLIS = 7_500L
    private const val MAX_STEPS = 10
    private const val STUCK_ACTION_LIMIT = 3
    private const val AUTOPILOT_ROOT_DUMP_TIMEOUT_MILLIS = 4_500L
    private const val FAST_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = 10_000L
    private const val TICKETS_TAB_X_FRACTION = 0.375f
    private const val BOTTOM_NAV_Y_FRACTION = 0.962f
    private const val TIME_TICKET_TAB_X_FRACTION = 0.727f
    private const val TICKET_TYPE_TAB_Y_FRACTION = 0.170f
    private const val FIRST_TICKET_CARD_X_FRACTION = 0.50f
    private const val FIRST_TICKET_CARD_Y_FRACTION = 0.32f
  }
}
