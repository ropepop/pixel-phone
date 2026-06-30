package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGPTPhoneRunnerTest {
  @Test
  fun classifyScreenDetectsLogin() {
    val state = ChatGPTPhoneRunner.classifyScreen(
      nodes = listOf(node(text = "Log in"), node(text = "Sign up")),
      expectedProject = "Pixel Broker"
    )

    assertEquals(ChatGPTPhoneScreenState.LOGIN, state)
  }

  @Test
  fun classifyScreenDetectsProject() {
    val state = ChatGPTPhoneRunner.classifyScreen(
      nodes = listOf(node(text = "Pixel Broker")),
      expectedProject = "Pixel Broker"
    )

    assertEquals(ChatGPTPhoneScreenState.PROJECT_VERIFIED, state)
  }

  @Test
  fun classifyScreenDetectsProjectComposer() {
    val state = ChatGPTPhoneRunner.classifyScreen(
      nodes = listOf(
        node(text = "Pixel Broker"),
        node(hint = "Message ChatGPT", editable = true)
      ),
      expectedProject = "Pixel Broker"
    )

    assertEquals(ChatGPTPhoneScreenState.COMPOSER_READY, state)
  }

  @Test
  fun classifyScreenFallsBackToHomeOrProject() {
    val state = ChatGPTPhoneRunner.classifyScreen(
      nodes = listOf(node(text = "Today"), node(text = "New chat")),
      expectedProject = "Pixel Broker"
    )

    assertEquals(ChatGPTPhoneScreenState.HOME_OR_PROJECT, state)
  }

  private fun node(
    text: String = "",
    contentDescription: String = "",
    hint: String = "",
    editable: Boolean = false
  ): PhoneAutomationVisibleNode {
    return PhoneAutomationVisibleNode(
      text = text,
      resourceId = "",
      contentDescription = contentDescription,
      editable = editable,
      hint = hint
    )
  }
}
