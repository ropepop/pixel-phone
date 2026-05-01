package lv.jolkins.pixelorchestrator.app.phoneautomation

internal data class TouchBrightnessReadinessSnapshot(
  val rootAvailable: Boolean,
  val batteryUnrestricted: Boolean,
  val touchDevices: List<RootTouchDevice>,
  val panelAvailable: Boolean
)

internal object TouchBrightnessReadiness {
  fun evaluate(snapshot: TouchBrightnessReadinessSnapshot): PhoneAutomationPreparationResult {
    val missing = mutableListOf<String>()
    if (!snapshot.rootAvailable) {
      missing += "Root access is unavailable"
    }
    if (!snapshot.batteryUnrestricted) {
      missing += "Unrestricted battery access is not enabled"
    }
    if (snapshot.touchDevices.isEmpty()) {
      missing += "No touchscreen input device could be identified"
    }
    if (!snapshot.panelAvailable) {
      missing += "Panel brightness path is unavailable"
    }
    if (missing.isNotEmpty()) {
      return PhoneAutomationPreparationResult(
        ready = false,
        detail = missing.joinToString(separator = "; ")
      )
    }

    val source = snapshot.touchDevices.first().displayLabel()
    return PhoneAutomationPreparationResult(
      ready = true,
      detail = "Ready for physical touch brightness via $source"
    )
  }
}
