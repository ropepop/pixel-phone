package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAutomationPrerequisitesTest {
  @Test
  fun liveAccessibilityConnectionIsRequiredEvenWhenPermissionLooksEnabled() {
    val issue = PhoneAutomationPrerequisiteSnapshot(
      accessibilityGloballyEnabled = true,
      accessibilityServiceEnabled = true,
      notificationListenerEnabled = true,
      accessibilityConnected = false,
      notificationConnected = true
    ).issueFor(
      PhoneAutomationSettingsSnapshot(
        enabled = true,
        touchBrightnessEnabled = true
      )
    )

    requireNotNull(issue)
    assertEquals("accessibility_connection", issue.reasonKey)
    assertEquals(true, issue.recoverAutomation)
    assertEquals(false, issue.recoverTouchBrightness)
  }

  @Test
  fun touchBrightnessOnlyDoesNotRequireAccessibility() {
    val issue = PhoneAutomationPrerequisiteSnapshot(
      accessibilityGloballyEnabled = false,
      accessibilityServiceEnabled = false,
      notificationListenerEnabled = false,
      accessibilityConnected = false,
      notificationConnected = false
    ).issueFor(
      PhoneAutomationSettingsSnapshot(
        enabled = false,
        touchBrightnessEnabled = true
      )
    )

    assertNull(issue)
  }

  @Test
  fun healthyPrerequisitesProduceNoRecoveryIssue() {
    val issue = PhoneAutomationPrerequisiteSnapshot(
      accessibilityGloballyEnabled = true,
      accessibilityServiceEnabled = true,
      notificationListenerEnabled = true,
      accessibilityConnected = true,
      notificationConnected = true
    ).issueFor(
      PhoneAutomationSettingsSnapshot(
        enabled = true,
        touchBrightnessEnabled = true
      )
    )

    assertNull(issue)
  }
}
