package lv.jolkins.pixelorchestrator.app.phoneautomation

internal object PhoneAutomationSpeedtestTiming {
  const val APP_FOREGROUND_TIMEOUT_MILLIS = 20_000L
  const val UI_SELECTOR_TIMEOUT_MILLIS = 16_000L
  const val RUNNING_PROOF_TIMEOUT_MILLIS = 8_000L
  const val CONNECTING_TIMEOUT_MILLIS = 30_000L
  const val STARTUP_RECONCILIATION_GRACE_WINDOW_MILLIS = 30_000L
  const val PROTECTED_HANDOFF_TIMEOUT_MILLIS = 40_000L
  const val COMPLETION_RESULT_TIMEOUT_MILLIS = 60_000L
}
