package lv.jolkins.pixelorchestrator.app.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationRuntimeState
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsSnapshot
import org.junit.Rule
import org.junit.Test

class DashboardScreenTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun dashboardShowsOperationalHierarchyAndProtectedHandoff() {
    composeRule.setContent {
      PixelOrchestratorTheme {
        DashboardScreen(
          state = DashboardUiState(
            buildIdentity = "test • abc123 • 0.1.0",
            phoneAutomation = PhoneAutomationSettingsSnapshot(
              enabled = true,
              runtimeState = PhoneAutomationRuntimeState.RESTARTING_SPEEDTEST,
              protectedHandoffStartedAtMillis = 1L
            )
          ),
          onAction = {}
        )
      }
    }

    composeRule.onNodeWithText("Pixel Orchestrator").assertIsDisplayed()
    composeRule.onNodeWithText("Stack status").assertIsDisplayed()
    composeRule.onNodeWithText("Protected handoff in progress").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun bootstrapRequiresAnExplicitConfirmation() {
    composeRule.setContent {
      PixelOrchestratorTheme {
        DashboardScreen(
          state = DashboardUiState(buildIdentity = "test"),
          onAction = {}
        )
      }
    }

    composeRule.onNodeWithText("Bootstrap and cut over").performScrollTo().performClick()
    composeRule.onNodeWithText("Bootstrap the stack?").assertIsDisplayed()
    composeRule.onNodeWithText("Cancel").assertIsDisplayed()
  }
}
