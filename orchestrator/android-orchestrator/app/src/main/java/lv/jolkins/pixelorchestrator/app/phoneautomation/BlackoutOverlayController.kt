package lv.jolkins.pixelorchestrator.app.phoneautomation

internal interface BlackoutOverlayController {
  suspend fun show(): PhoneAutomationActionResult
  suspend fun hide(): PhoneAutomationActionResult
  fun isAvailable(): Boolean
}

internal class BridgeBlackoutOverlayController(
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge
) : BlackoutOverlayController {
  override suspend fun show(): PhoneAutomationActionResult {
    if (!bridge.isBlackoutOverlayAvailable()) {
      bridge.awaitAccessibilityConnection(OVERLAY_CONNECTION_TIMEOUT_MILLIS)
    }
    if (!bridge.isBlackoutOverlayAvailable()) {
      return PhoneAutomationActionResult(
        success = false,
        detail = "Accessibility blackout overlay is unavailable"
      )
    }
    return if (bridge.setBlackoutOverlayVisible(true)) {
      PhoneAutomationActionResult(
        success = true,
        detail = "Blackout overlay shown"
      )
    } else {
      PhoneAutomationActionResult(
        success = false,
        detail = "Accessibility blackout overlay is unavailable"
      )
    }
  }

  override suspend fun hide(): PhoneAutomationActionResult {
    return if (bridge.setBlackoutOverlayVisible(false)) {
      PhoneAutomationActionResult(
        success = true,
        detail = "Blackout overlay hidden"
      )
    } else {
      PhoneAutomationActionResult(
        success = false,
        detail = "Accessibility blackout overlay could not be hidden"
      )
    }
  }

  override fun isAvailable(): Boolean = bridge.isBlackoutOverlayAvailable()

  private companion object {
    private const val OVERLAY_CONNECTION_TIMEOUT_MILLIS = 5_000L
  }
}
