package lv.jolkins.pixelorchestrator.app.phoneautomation

internal data class PhoneAutomationUiPolicy(
  val protectedHandoff: Boolean,
  val phoneAutomationToggleEnabled: Boolean,
  val cellMapperToggleEnabled: Boolean,
  val returnToOrchestratorToggleEnabled: Boolean,
  val dispatchIntervalEnabled: Boolean,
  val touchBrightnessToggleEnabled: Boolean,
  val shouldInterruptOnForeground: Boolean
)

internal fun phoneAutomationUiPolicy(snapshot: PhoneAutomationSettingsSnapshot): PhoneAutomationUiPolicy {
  val protectedHandoff = snapshot.isProtectedSpeedtestHandoffInProgress()
  return PhoneAutomationUiPolicy(
    protectedHandoff = protectedHandoff,
    phoneAutomationToggleEnabled = true,
    cellMapperToggleEnabled = snapshot.enabled && !protectedHandoff,
    returnToOrchestratorToggleEnabled = snapshot.enabled && !protectedHandoff,
    dispatchIntervalEnabled = snapshot.enabled && !protectedHandoff,
    touchBrightnessToggleEnabled = !protectedHandoff,
    shouldInterruptOnForeground = protectedHandoff
  )
}
