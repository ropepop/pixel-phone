package lv.jolkins.pixelorchestrator.app.phoneautomation

internal data class PhoneAutomationPrerequisiteSnapshot(
  val accessibilityGloballyEnabled: Boolean,
  val accessibilityServiceEnabled: Boolean,
  val notificationListenerEnabled: Boolean,
  val accessibilityConnected: Boolean,
  val notificationConnected: Boolean
) {
  val accessibilityPermissionEnabled: Boolean
    get() = PhoneAutomationServiceBridge.hasEnabledAccessibilityPermission(
      accessibilityGloballyEnabled = accessibilityGloballyEnabled,
      componentEnabled = accessibilityServiceEnabled
    )

  val notificationPermissionEnabled: Boolean
    get() = notificationListenerEnabled
}

internal data class PhoneAutomationPrerequisiteIssue(
  val reasonKey: String,
  val detail: String,
  val recoverAutomation: Boolean,
  val recoverTouchBrightness: Boolean,
  val connectionOnly: Boolean
)

internal fun PhoneAutomationPrerequisiteSnapshot.issueFor(
  settings: PhoneAutomationSettingsSnapshot
): PhoneAutomationPrerequisiteIssue? {
  val recoverAutomation = settings.enabled
  val recoverTouchBrightness = settings.touchBrightnessEnabled
  if (!recoverAutomation && !recoverTouchBrightness) {
    return null
  }

  val accessibilityRequired = recoverAutomation

  if (accessibilityRequired && !accessibilityPermissionEnabled) {
    return PhoneAutomationPrerequisiteIssue(
      reasonKey = "accessibility_permission",
      detail = "Accessibility access is not enabled",
      recoverAutomation = recoverAutomation,
      recoverTouchBrightness = false,
      connectionOnly = false
    )
  }

  if (accessibilityRequired && !accessibilityConnected) {
    return PhoneAutomationPrerequisiteIssue(
      reasonKey = "accessibility_connection",
      detail = "Accessibility service is not connected",
      recoverAutomation = recoverAutomation,
      recoverTouchBrightness = false,
      connectionOnly = true
    )
  }

  if (recoverAutomation && !notificationPermissionEnabled) {
    return PhoneAutomationPrerequisiteIssue(
      reasonKey = "notification_permission",
      detail = "Notification access is not enabled",
      recoverAutomation = true,
      recoverTouchBrightness = false,
      connectionOnly = false
    )
  }

  if (recoverAutomation && !notificationConnected) {
    return PhoneAutomationPrerequisiteIssue(
      reasonKey = "notification_connection",
      detail = "Notification listener is not connected",
      recoverAutomation = true,
      recoverTouchBrightness = false,
      connectionOnly = true
    )
  }

  return null
}
