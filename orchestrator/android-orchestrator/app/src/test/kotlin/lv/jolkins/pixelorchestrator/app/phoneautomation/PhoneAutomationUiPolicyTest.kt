package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAutomationUiPolicyTest {
  @Test
  fun protectedHandoffLocksNonStopControlsButKeepsMainToggleAvailable() {
    val policy = phoneAutomationUiPolicy(
      PhoneAutomationSettingsSnapshot(
        enabled = true,
        touchBrightnessEnabled = true,
        runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
        protectedHandoffStartedAtMillis = 123L
      )
    )

    assertTrue(policy.protectedHandoff)
    assertTrue(policy.phoneAutomationToggleEnabled)
    assertFalse(policy.cellMapperToggleEnabled)
    assertFalse(policy.returnToOrchestratorToggleEnabled)
    assertFalse(policy.dispatchIntervalEnabled)
    assertFalse(policy.touchBrightnessToggleEnabled)
    assertTrue(policy.shouldInterruptOnForeground)
  }

  @Test
  fun placeholderStartingStateKeepsControlsAvailable() {
    val policy = phoneAutomationUiPolicy(
      PhoneAutomationSettingsSnapshot(
        enabled = true,
        touchBrightnessEnabled = true,
        runtimeState = PhoneAutomationRuntimeState.STARTING
      )
    )

    assertFalse(policy.protectedHandoff)
    assertTrue(policy.phoneAutomationToggleEnabled)
    assertTrue(policy.cellMapperToggleEnabled)
    assertTrue(policy.returnToOrchestratorToggleEnabled)
    assertTrue(policy.dispatchIntervalEnabled)
    assertTrue(policy.touchBrightnessToggleEnabled)
    assertFalse(policy.shouldInterruptOnForeground)
  }

  @Test
  fun nonProtectedStateKeepsControlsAvailable() {
    val policy = phoneAutomationUiPolicy(
      PhoneAutomationSettingsSnapshot(
        enabled = true,
        touchBrightnessEnabled = true,
        runtimeState = PhoneAutomationRuntimeState.WAITING_FOR_NEXT_DISPATCH
      )
    )

    assertFalse(policy.protectedHandoff)
    assertTrue(policy.phoneAutomationToggleEnabled)
    assertTrue(policy.cellMapperToggleEnabled)
    assertTrue(policy.returnToOrchestratorToggleEnabled)
    assertTrue(policy.dispatchIntervalEnabled)
    assertTrue(policy.touchBrightnessToggleEnabled)
    assertFalse(policy.shouldInterruptOnForeground)
  }
}
