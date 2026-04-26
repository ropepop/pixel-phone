package lv.jolkins.pixelorchestrator.app.phoneautomation

internal enum class PhoneAutomationForegroundInterruptDecision {
  IGNORE_NOT_IN_FLIGHT,
  INTERRUPT,
  SUPPRESS_BLACKOUT_WAKE,
  SUPPRESS_SELF_FOREGROUND
}

internal fun phoneAutomationForegroundInterruptDecision(
  snapshot: PhoneAutomationSettingsSnapshot,
  lastBlackoutWakeAtMillis: Long,
  lastExpectedOrchestratorForegroundAtMillis: Long
): PhoneAutomationForegroundInterruptDecision {
  if (!snapshot.isProtectedSpeedtestHandoffInProgress()) {
    return PhoneAutomationForegroundInterruptDecision.IGNORE_NOT_IN_FLIGHT
  }
  return if (
    lastBlackoutWakeAtMillis > 0L &&
      lastBlackoutWakeAtMillis >=
      snapshot.protectedHandoffStartedAtMillis - BLACKOUT_WAKE_HANDOFF_ASSOCIATION_WINDOW_MILLIS
  ) {
    PhoneAutomationForegroundInterruptDecision.SUPPRESS_BLACKOUT_WAKE
  } else if (
    lastExpectedOrchestratorForegroundAtMillis > 0L &&
      lastExpectedOrchestratorForegroundAtMillis >=
      snapshot.protectedHandoffStartedAtMillis - SELF_FOREGROUND_HANDOFF_ASSOCIATION_WINDOW_MILLIS
  ) {
    PhoneAutomationForegroundInterruptDecision.SUPPRESS_SELF_FOREGROUND
  } else {
    PhoneAutomationForegroundInterruptDecision.INTERRUPT
  }
}

internal const val BLACKOUT_WAKE_HANDOFF_ASSOCIATION_WINDOW_MILLIS = 2_000L
internal const val SELF_FOREGROUND_HANDOFF_ASSOCIATION_WINDOW_MILLIS = 2_000L
