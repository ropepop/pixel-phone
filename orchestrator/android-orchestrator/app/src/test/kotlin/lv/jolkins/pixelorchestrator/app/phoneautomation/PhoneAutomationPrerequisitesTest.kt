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
    assertEquals(true, issue.recoverTouchBrightness)
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
