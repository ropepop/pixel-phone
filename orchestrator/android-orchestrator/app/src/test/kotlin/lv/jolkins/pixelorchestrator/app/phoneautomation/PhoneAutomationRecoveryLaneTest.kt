package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationRecoveryLaneTest {
  @Test
  fun waitingForRecoveryRetryOwnsTheRecoveryLane() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_RECOVERY_RETRY
    )

    assertTrue(snapshot.isSpeedtestRecoveryOwningLane())
  }

  @Test
  fun prerequisiteRecoveryDefersTouchBrightnessWhileAutomationOwnsLane() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      touchBrightnessEnabled = true,
      runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
      protectedHandoffStartedAtMillis = 123L,
      touchBrightnessState = TouchBrightnessRuntimeState.BLACKOUT_IDLE
    )
    val issue = PhoneAutomationPrerequisiteIssue(
      reasonKey = "accessibility_connection",
      detail = "Accessibility service is not connected",
      recoverAutomation = true,
      recoverTouchBrightness = true,
      connectionOnly = true
    )

    assertTrue(shouldDeferPhoneAutomationPrerequisiteRecovery(snapshot, issue))
  }

  @Test
  fun steadyWaitingStateDoesNotOwnTheRecoveryLane() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_COMPLETION
    )

    assertFalse(snapshot.isSpeedtestRecoveryOwningLane())
  }
}
