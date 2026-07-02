package lv.jolkins.pixelorchestrator.app.phoneautomation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

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

  @Test
  fun rootScreenshotCaptureDoesNotPersistLocalResultPng() {
    val source = readSource("ChatGPTPhoneRunner.kt")

    assertFalse(source.contains("chatgpt-broker-result.png"))
    assertFalse(source.contains("screencap -p \"${'$'}tmp\""))
    assertTrue(source.contains("screencap -p 2>/dev/null | base64"))
  }

  @Test
  fun rootExtractionHandlesAndroidShareSheetWithoutOcr() {
    val source = readSource("ChatGPTPhoneRunner.kt")

    assertTrue(source.contains("copyAnswerFromShareSheet"))
    assertTrue(source.contains("Sharing text"))
    assertTrue(source.contains("Copy text"))
    assertTrue(source.contains("clipboardTextSetter"))
    assertTrue(source.contains("looksLikeExternalShareSurface"))
    assertFalse(source.contains("1_880"))
    assertFalse(source.contains("OCR"))
  }

  @Test
  fun rootRunnerUsesHierarchyInsteadOfHardcodedProjectAndCopyCoordinates() {
    val source = readSource("ChatGPTPhoneRunner.kt")

    assertTrue(source.contains("findProjectListRowBounds"))
    assertTrue(source.contains("copyActionBounds"))
    assertFalse(source.contains("tapKnownProjectListRow"))
    assertFalse(source.contains("245, 552"))
    assertFalse(source.contains("245, 520"))
    assertFalse(source.contains("COPY_BUTTON_X"))
    assertFalse(source.contains("COPY_BUTTON_Y_CANDIDATES"))
  }

  @Test
  fun rootRunnerPastesPromptAndFailsClosedOnUnverifiedAnswer() {
    val source = readSource("ChatGPTPhoneRunner.kt")
    val waitBlock = source.substringBetween(
      "override suspend fun waitForLatestAnswerText",
      "override suspend fun screenshotPngBase64"
    )

    assertTrue(source.contains("setClipboardText(displayText)"))
    assertTrue(source.contains("input keyevent KEYCODE_PASTE"))
    assertTrue(source.contains("shouldAbort"))
    assertTrue(waitBlock.trimEnd().endsWith("return \"\"\n  }"))
    assertFalse(source.contains("input text"))
  }

  @Test
  fun rootRunnerModelsRealChatgptScenes() {
    val source = readSource("ChatGPTPhoneRunner.kt")

    assertTrue(source.contains("private enum class ChatGPTRootScene"))
    assertTrue(source.contains("LOGIN"))
    assertTrue(source.contains("PROJECTS_LIST"))
    assertTrue(source.contains("SELECTED_PROJECT_SURFACE"))
    assertTrue(source.contains("PROJECT_NEW_CHAT_COMPOSER"))
    assertTrue(source.contains("ACTIVE_GENERATION"))
    assertTrue(source.contains("COMPLETED_ANSWER"))
    assertTrue(source.contains("BLOCKING_OVERLAY"))
  }

  @Test
  fun spacetimeWorkerUsesSpacetimeEventsInsteadOfAndroidLogs() {
    val source = readSource("ChatGPTSpacetimeWorker.kt")

    assertFalse(source.contains("android.util.Log"))
    assertFalse(source.contains("Log."))
    assertTrue(source.contains("chatgptbroker_record_event"))
  }

  @Test
  fun spacetimeWorkerAlwaysStartsNewProjectChatAndChecksPriorityDuringRun() {
    val source = readSource("ChatGPTSpacetimeWorker.kt")

    assertTrue(source.contains("startNewChat = true"))
    assertTrue(source.contains("shouldAbort = { shouldAbortPhoneWork() }"))
    assertTrue(source.contains("ticketPriorityActive()"))
    assertTrue(source.contains("cancelRequested(work)"))
    assertTrue(source.contains("\"stage\" to \"root_ui\""))
    assertTrue(source.contains("\"project\" to projectName"))
    assertTrue(source.contains("\"portraitLock\" to \"required\""))
    assertTrue(source.contains("\"ticketPriority\" to \"idle\""))
  }

  @Test
  fun spacetimeWorkerDoesNotCarryTelegramThreadRouting() {
    val source = readSource("ChatGPTSpacetimeWorker.kt")

    assertFalse(source.contains("threadName"))
    assertFalse(source.contains("values[\"thread\"]"))
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

  private fun readSource(fileName: String): String {
    val paths = listOf(
      Path.of("app/src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/$fileName"),
      Path.of("src/main/java/lv/jolkins/pixelorchestrator/app/phoneautomation/$fileName")
    )
    val path = paths.firstOrNull { Files.exists(it) } ?: error("missing source file: ${paths.joinToString()}")
    return String(Files.readAllBytes(path), Charsets.UTF_8)
  }

  private fun String.substringBetween(startNeedle: String, endNeedle: String): String {
    val start = indexOf(startNeedle)
    assertTrue("missing start needle: $startNeedle", start >= 0)
    val end = indexOf(endNeedle, start + startNeedle.length)
    assertTrue("missing end needle: $endNeedle", end >= 0)
    return substring(start, end)
  }
}
