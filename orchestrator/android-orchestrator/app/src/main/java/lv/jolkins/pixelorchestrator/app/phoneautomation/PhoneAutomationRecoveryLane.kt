package lv.jolkins.pixelorchestrator.app.phoneautomation

private val SPEEDTEST_RECOVERY_LANE_STATES = setOf(
  PhoneAutomationRuntimeState.STARTING,
  PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
  PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY,
  PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_RESULT
)

internal fun PhoneAutomationSettingsSnapshot.isSpeedtestRecoveryOwningLane(): Boolean {
  if (!enabled) {
    return false
  }
  return isProtectedSpeedtestHandoffInProgress() ||
    pendingRecoveryPhase != PhoneAutomationPendingRecoveryPhase.NONE ||
    runtimeState in SPEEDTEST_RECOVERY_LANE_STATES
}

internal fun shouldDeferPhoneAutomationPrerequisiteRecovery(
  settingsSnapshot: PhoneAutomationSettingsSnapshot,
  issue: PhoneAutomationPrerequisiteIssue
): Boolean {
  val automationOwnsLane = settingsSnapshot.isSpeedtestRecoveryOwningLane()
  return (issue.recoverAutomation && automationOwnsLane) ||
    (
      issue.recoverTouchBrightness &&
        (
          settingsSnapshot.touchBrightnessState == TouchBrightnessRuntimeState.STARTING ||
            automationOwnsLane
          )
      )
}
