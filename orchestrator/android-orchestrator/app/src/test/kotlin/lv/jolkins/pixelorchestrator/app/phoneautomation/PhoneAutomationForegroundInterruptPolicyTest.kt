package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAutomationForegroundInterruptPolicyTest {
  @Test
  fun ordinaryProtectedHandoffForegroundStillInterrupts() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
      protectedHandoffStartedAtMillis = 10_000L
    )

    assertEquals(
      PhoneAutomationForegroundInterruptDecision.INTERRUPT,
      phoneAutomationForegroundInterruptDecision(
        snapshot = snapshot,
        lastBlackoutWakeAtMillis = 7_999L,
        lastExpectedOrchestratorForegroundAtMillis = 0L
      )
    )
  }

  @Test
  fun blackoutWakeNearProtectedHandoffSuppressesForegroundInterrupt() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
      protectedHandoffStartedAtMillis = 10_000L
    )

    assertEquals(
      PhoneAutomationForegroundInterruptDecision.SUPPRESS_BLACKOUT_WAKE,
      phoneAutomationForegroundInterruptDecision(
        snapshot = snapshot,
        lastBlackoutWakeAtMillis = 8_000L,
        lastExpectedOrchestratorForegroundAtMillis = 0L
      )
    )
  }

  @Test
  fun expectedForegroundNearProtectedHandoffSuppressesInterrupt() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
      protectedHandoffStartedAtMillis = 10_000L
    )

    assertEquals(
      PhoneAutomationForegroundInterruptDecision.SUPPRESS_SELF_FOREGROUND,
      phoneAutomationForegroundInterruptDecision(
        snapshot = snapshot,
        lastBlackoutWakeAtMillis = 0L,
        lastExpectedOrchestratorForegroundAtMillis = 9_500L
      )
    )
  }

  @Test
  fun nonProtectedForegroundDoesNotInterrupt() {
    val snapshot = PhoneAutomationSettingsSnapshot(
      enabled = true,
      runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_SPEEDTEST_COMPLETION
    )

    assertEquals(
      PhoneAutomationForegroundInterruptDecision.IGNORE_NOT_IN_FLIGHT,
      phoneAutomationForegroundInterruptDecision(
        snapshot = snapshot,
        lastBlackoutWakeAtMillis = 10_000L,
        lastExpectedOrchestratorForegroundAtMillis = 10_000L
      )
    )
  }
}
