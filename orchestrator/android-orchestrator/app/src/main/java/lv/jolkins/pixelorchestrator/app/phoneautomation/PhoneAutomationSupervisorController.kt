package lv.jolkins.pixelorchestrator.app.phoneautomation

interface PhoneAutomationRuntimeController {
  fun start()
  fun stop(reason: String)
  suspend fun interruptProtectedHandoff(reason: String): Boolean = false

  fun restart(reason: String) {
    stop(reason)
    start()
  }
}

enum class PhoneAutomationSupervisorDecision {
  STARTED,
  IGNORED_IN_FLIGHT,
  STOPPED
}

internal data class PhoneAutomationRecoveryRequest(
  val reasonKey: String,
  val detail: String,
  val recoverAutomation: Boolean,
  val recoverTouchBrightness: Boolean
)

internal enum class PhoneAutomationRecoveryDecision {
  RECOVERED,
  IGNORED_COOLDOWN,
  IGNORED_NO_TARGETS
}

internal enum class PhoneAutomationHandoffInterruptionDecision {
  INTERRUPTED,
  IGNORED_NOT_IN_FLIGHT,
  IGNORED_NOT_ENABLED
}

class PhoneAutomationSupervisorController(
  private val settingsStore: PhoneAutomationSettingsStore,
  private val runtimeController: PhoneAutomationRuntimeController,
  private val touchBrightnessRuntimeController: PhoneAutomationRuntimeController,
  private val clockMillis: () -> Long = { System.currentTimeMillis() },
  private val recoveryCooldownMillis: Long = RECOVERY_COOLDOWN_MILLIS,
  private val logger: (String) -> Unit = {}
) {
  private var lastRecoveryReasonKey: String = ""
  private var lastRecoveryAtMillis: Long = 0L
  private var deferredTouchBrightnessRecovery: DeferredTouchBrightnessRecovery? = null

  fun syncFromSettings(
    trigger: String,
    suppressAutomationRestart: Boolean = false
  ): PhoneAutomationSupervisorDecision {
    val snapshot = settingsStore.load()
    val automationDecision = if (
      suppressAutomationRestart &&
      snapshot.enabled &&
      snapshot.isProtectedSpeedtestHandoffInProgress()
    ) {
      PhoneAutomationSupervisorDecision.IGNORED_IN_FLIGHT
    } else if (snapshot.enabled) {
      runtimeController.start()
      PhoneAutomationSupervisorDecision.STARTED
    } else {
      settingsStore.updateRuntimeState(
        PhoneAutomationRuntimeState.DISABLED,
        PhoneAutomationRuntimeState.DISABLED.defaultDetail
      )
      runtimeController.stop(reason = "disabled:$trigger")
      PhoneAutomationSupervisorDecision.STOPPED
    }

    if (snapshot.touchBrightnessEnabled) {
      val touchBrightnessNeedsStart = snapshot.touchBrightnessState == TouchBrightnessRuntimeState.STOPPED ||
        snapshot.touchBrightnessState == TouchBrightnessRuntimeState.ERROR
      val shouldDeferTouchBrightnessRecovery = snapshot.isSpeedtestRecoveryOwningLane() &&
        (deferredTouchBrightnessRecovery != null || touchBrightnessNeedsStart)
      if (shouldDeferTouchBrightnessRecovery) {
        deferTouchBrightnessRecovery(
          action = deferredTouchBrightnessRecovery?.action ?: DeferredTouchBrightnessRecoveryAction.START,
          reasonKey = deferredTouchBrightnessRecovery?.reasonKey ?: trigger,
          detail = deferredTouchBrightnessRecovery?.detail
            ?: "Touch brightness is waiting for Speedtest recovery to finish"
        )
      } else if (!resumeDeferredTouchBrightnessRecoveryIfNeeded()) {
        touchBrightnessRuntimeController.start()
      }
    } else {
      clearDeferredTouchBrightnessRecovery()
      settingsStore.updateTouchBrightnessState(
        TouchBrightnessRuntimeState.DISABLED,
        TouchBrightnessRuntimeState.DISABLED.defaultDetail
      )
      touchBrightnessRuntimeController.stop(reason = "disabled:$trigger")
    }

    return if (
      automationDecision == PhoneAutomationSupervisorDecision.IGNORED_IN_FLIGHT &&
      !snapshot.touchBrightnessEnabled
    ) {
      PhoneAutomationSupervisorDecision.IGNORED_IN_FLIGHT
    } else if (snapshot.enabled || snapshot.touchBrightnessEnabled) {
      if (automationDecision == PhoneAutomationSupervisorDecision.IGNORED_IN_FLIGHT) {
        PhoneAutomationSupervisorDecision.IGNORED_IN_FLIGHT
      } else {
        PhoneAutomationSupervisorDecision.STARTED
      }
    } else {
      PhoneAutomationSupervisorDecision.STOPPED
    }
  }

  internal fun recoverFromPrerequisiteDrift(request: PhoneAutomationRecoveryRequest): PhoneAutomationRecoveryDecision {
    val now = clockMillis()
    if (
      request.reasonKey == lastRecoveryReasonKey &&
      now - lastRecoveryAtMillis < recoveryCooldownMillis
    ) {
      return PhoneAutomationRecoveryDecision.IGNORED_COOLDOWN
    }

    val snapshot = settingsStore.load()
    var recoveredAny = false
    val automationWillOwnLane = (request.recoverAutomation && snapshot.enabled) || snapshot.isSpeedtestRecoveryOwningLane()

    if (request.recoverAutomation && snapshot.enabled) {
      runtimeController.restart(reason = "recover:${request.reasonKey}")
      settingsStore.updateRuntimeState(
        PhoneAutomationRuntimeState.STARTING,
        "Recovering automation after ${request.detail.lowercase()}"
      )
      recoveredAny = true
    }

    if (request.recoverTouchBrightness && snapshot.touchBrightnessEnabled) {
      if (automationWillOwnLane) {
        deferTouchBrightnessRecovery(
          action = DeferredTouchBrightnessRecoveryAction.RESTART,
          reasonKey = request.reasonKey,
          detail = request.detail
        )
      } else {
        touchBrightnessRuntimeController.restart(reason = "recover:${request.reasonKey}")
        settingsStore.updateTouchBrightnessState(
          TouchBrightnessRuntimeState.STARTING,
          "Recovering touch brightness after ${request.detail.lowercase()}"
        )
        recoveredAny = true
      }
    }

    if (!recoveredAny) {
      return PhoneAutomationRecoveryDecision.IGNORED_NO_TARGETS
    }

    lastRecoveryReasonKey = request.reasonKey
    lastRecoveryAtMillis = now
    return PhoneAutomationRecoveryDecision.RECOVERED
  }

  internal suspend fun interruptProtectedHandoff(detail: String): PhoneAutomationHandoffInterruptionDecision {
    val snapshot = settingsStore.load()
    if (!snapshot.enabled) {
      return PhoneAutomationHandoffInterruptionDecision.IGNORED_NOT_ENABLED
    }
    if (!snapshot.isProtectedSpeedtestHandoffInProgress()) {
      return PhoneAutomationHandoffInterruptionDecision.IGNORED_NOT_IN_FLIGHT
    }
    return if (runtimeController.interruptProtectedHandoff(detail)) {
      PhoneAutomationHandoffInterruptionDecision.INTERRUPTED
    } else {
      PhoneAutomationHandoffInterruptionDecision.IGNORED_NOT_IN_FLIGHT
    }
  }

  internal fun shouldResumeDeferredTouchBrightness(
    snapshot: PhoneAutomationSettingsSnapshot = settingsStore.load()
  ): Boolean {
    return deferredTouchBrightnessRecovery != null &&
      snapshot.touchBrightnessEnabled &&
      !snapshot.isSpeedtestRecoveryOwningLane()
  }

  private fun deferTouchBrightnessRecovery(
    action: DeferredTouchBrightnessRecoveryAction,
    reasonKey: String,
    detail: String
  ) {
    val previous = deferredTouchBrightnessRecovery
    val nextAction = when {
      previous?.action == DeferredTouchBrightnessRecoveryAction.RESTART ||
        action == DeferredTouchBrightnessRecoveryAction.RESTART -> {
        DeferredTouchBrightnessRecoveryAction.RESTART
      }

      else -> DeferredTouchBrightnessRecoveryAction.START
    }
    val next = DeferredTouchBrightnessRecovery(
      action = nextAction,
      reasonKey = if (previous?.reasonKey?.isNotBlank() == true && nextAction == previous.action) {
        previous.reasonKey
      } else {
        reasonKey
      },
      detail = if (previous?.detail?.isNotBlank() == true && nextAction == previous.action) {
        previous.detail
      } else {
        detail
      }
    )
    deferredTouchBrightnessRecovery = next
    if (previous != next) {
      logger(
        "touch_brightness_recovery_deferred action=${next.action.wireName} reason=${next.reasonKey} detail=${next.detail}"
      )
    }
  }

  private fun resumeDeferredTouchBrightnessRecoveryIfNeeded(): Boolean {
    val deferred = deferredTouchBrightnessRecovery ?: return false
    deferredTouchBrightnessRecovery = null
    logger(
      "touch_brightness_recovery_resumed action=${deferred.action.wireName} reason=${deferred.reasonKey} detail=${deferred.detail}"
    )
    return when (deferred.action) {
      DeferredTouchBrightnessRecoveryAction.START -> {
        touchBrightnessRuntimeController.start()
        true
      }

      DeferredTouchBrightnessRecoveryAction.RESTART -> {
        touchBrightnessRuntimeController.restart(reason = "recover:${deferred.reasonKey}")
        settingsStore.updateTouchBrightnessState(
          TouchBrightnessRuntimeState.STARTING,
          "Recovering touch brightness after ${deferred.detail.lowercase()}"
        )
        true
      }
    }
  }

  private fun clearDeferredTouchBrightnessRecovery() {
    deferredTouchBrightnessRecovery = null
  }

  companion object {
    internal const val RECOVERY_COOLDOWN_MILLIS = 30_000L
  }
}

private enum class DeferredTouchBrightnessRecoveryAction(
  val wireName: String
) {
  START("start"),
  RESTART("restart")
}

private data class DeferredTouchBrightnessRecovery(
  val action: DeferredTouchBrightnessRecoveryAction,
  val reasonKey: String,
  val detail: String
)
