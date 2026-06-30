package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import kotlin.time.Duration.Companion.seconds

@Serializable
enum class ChatGPTPhoneFailureCode {
  NOT_INSTALLED,
  LAUNCH_FAILED,
  ACCESSIBILITY_UNAVAILABLE,
  NOT_LOGGED_IN,
  PROJECT_NOT_VERIFIED,
  INPUT_NOT_FOUND,
  SEND_NOT_FOUND,
  RESULT_EXTRACTION_UNVERIFIED,
  CANCELLED,
  UNKNOWN
}

@Serializable
enum class ChatGPTPhoneScreenState {
  NOT_INSTALLED,
  UNKNOWN,
  LOGIN,
  HOME_OR_PROJECT,
  PROJECT_VERIFIED,
  COMPOSER_READY,
  RESPONSE_VISIBLE
}

@Serializable
data class ChatGPTPhoneHealth(
  val ok: Boolean,
  val packageName: String = ChatGPTPhoneRunner.DEFAULT_PACKAGE,
  val installed: Boolean = false,
  val versionName: String = "",
  val installerPackageName: String = "",
  val launchable: Boolean = false,
  val foreground: Boolean = false,
  val accessibilityReady: Boolean = false,
  val screenState: ChatGPTPhoneScreenState = ChatGPTPhoneScreenState.UNKNOWN,
  val failureCode: ChatGPTPhoneFailureCode? = null,
  val detail: String = ""
)

@Serializable
data class ChatGPTDiscoverySnapshot(
  val packageName: String,
  val expectedProject: String,
  val screenState: ChatGPTPhoneScreenState,
  val visibleNodes: List<PhoneAutomationVisibleNode>
)

@Serializable
data class ChatGPTPhoneActionResult(
  val ok: Boolean,
  val stage: String,
  val failureCode: ChatGPTPhoneFailureCode? = null,
  val detail: String = "",
  val resultText: String = ""
)

@Serializable
data class ChatGPTPhoneRootRunResult(
  val ok: Boolean,
  val stage: String,
  val failureCode: ChatGPTPhoneFailureCode? = null,
  val detail: String = "",
  val screenshotPngBase64: String = ""
)

class ChatGPTPhoneRunner(
  private val context: Context,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge,
  private val shellInput: ChatGPTShellInput? = null,
  private val packageName: String = DEFAULT_PACKAGE
) {
  fun health(expectedProject: String = ""): ChatGPTPhoneHealth {
    val installed = isPackageInstalled(packageName)
    val versionName = packageVersion(packageName)
    val installer = installerPackage(packageName)
    val launchable = launchIntent() != null
    val foreground = bridge.isForegroundPackage(packageName)
    val accessibilityReady = bridge.isAccessibilityServiceConnected()
    val screenState = if (!installed) {
      ChatGPTPhoneScreenState.NOT_INSTALLED
    } else {
      classifyScreen(emptyList(), expectedProject)
    }
    val ok = installed && launchable && accessibilityReady
    return ChatGPTPhoneHealth(
      ok = ok,
      packageName = packageName,
      installed = installed,
      versionName = versionName,
      installerPackageName = installer,
      launchable = launchable,
      foreground = foreground,
      accessibilityReady = accessibilityReady,
      screenState = screenState,
      failureCode = when {
        !installed -> ChatGPTPhoneFailureCode.NOT_INSTALLED
        !launchable -> ChatGPTPhoneFailureCode.LAUNCH_FAILED
        !accessibilityReady -> ChatGPTPhoneFailureCode.ACCESSIBILITY_UNAVAILABLE
        else -> null
      },
      detail = when {
        !installed -> "ChatGPT is not installed"
        !launchable -> "ChatGPT has no launcher activity"
        !accessibilityReady -> "Accessibility service is not connected"
        else -> "ChatGPT runner prerequisites are ready"
      }
    )
  }

  suspend fun launch(): ChatGPTPhoneActionResult {
    val intent = launchIntent() ?: return ChatGPTPhoneActionResult(
      ok = false,
      stage = "launch",
      failureCode = ChatGPTPhoneFailureCode.NOT_INSTALLED,
      detail = "ChatGPT is not installed or has no launcher activity"
    )
    return try {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      val foreground = bridge.waitForForegroundPackage(packageName, APP_LAUNCH_TIMEOUT_MILLIS) ||
        bridge.snapshotVisibleNodes(packageName).isNotEmpty()
      ChatGPTPhoneActionResult(
        ok = foreground,
        stage = "launch",
        failureCode = if (foreground) null else ChatGPTPhoneFailureCode.LAUNCH_FAILED,
        detail = if (foreground) "ChatGPT reached foreground" else "ChatGPT did not reach foreground"
      )
    } catch (error: Throwable) {
      ChatGPTPhoneActionResult(
        ok = false,
        stage = "launch",
        failureCode = ChatGPTPhoneFailureCode.LAUNCH_FAILED,
        detail = error.message ?: error::class.java.simpleName
      )
    }
  }

  suspend fun discoverySnapshot(expectedProject: String): ChatGPTDiscoverySnapshot {
    val nodes = bridge.snapshotVisibleNodes(packageName)
    return ChatGPTDiscoverySnapshot(
      packageName = packageName,
      expectedProject = expectedProject,
      screenState = classifyScreen(nodes, expectedProject),
      visibleNodes = sanitizeNodes(nodes)
    )
  }

  suspend fun verifyProject(expectedProject: String): ChatGPTPhoneActionResult {
    if (!bridge.isAccessibilityServiceConnected()) {
      return ChatGPTPhoneActionResult(
        ok = false,
        stage = "verify_project",
        failureCode = ChatGPTPhoneFailureCode.ACCESSIBILITY_UNAVAILABLE,
        detail = "Accessibility service is not connected"
      )
    }
    val deadline = System.currentTimeMillis() + PROJECT_VERIFY_TIMEOUT_MILLIS
    while (System.currentTimeMillis() < deadline) {
      val nodes = bridge.snapshotVisibleNodes(packageName)
      val state = classifyScreen(nodes, expectedProject)
      if (state == ChatGPTPhoneScreenState.PROJECT_VERIFIED ||
        state == ChatGPTPhoneScreenState.COMPOSER_READY ||
        (expectedProject.isBlank() && state == ChatGPTPhoneScreenState.HOME_OR_PROJECT)
      ) {
        return ChatGPTPhoneActionResult(ok = true, stage = "verify_project", detail = "Project verified")
      }
      if (state == ChatGPTPhoneScreenState.LOGIN) {
        return ChatGPTPhoneActionResult(
          ok = false,
          stage = "verify_project",
          failureCode = ChatGPTPhoneFailureCode.NOT_LOGGED_IN,
          detail = "ChatGPT is showing a login screen"
        )
      }
      delay(250L)
    }
    return ChatGPTPhoneActionResult(
      ok = false,
      stage = "verify_project",
      failureCode = ChatGPTPhoneFailureCode.PROJECT_NOT_VERIFIED,
      detail = "Expected ChatGPT Project was not visible"
    )
  }

  suspend fun runTextJob(expectedProject: String, prompt: String): ChatGPTPhoneActionResult {
    val cleanPrompt = prompt.trim()
    if (cleanPrompt.isEmpty()) {
      return ChatGPTPhoneActionResult(
        ok = false,
        stage = "preflight",
        failureCode = ChatGPTPhoneFailureCode.INPUT_NOT_FOUND,
        detail = "Prompt is empty"
      )
    }
    val launched = launch()
    if (!launched.ok) {
      return launched
    }
    tapNewChatIfVisible()
    val project = verifyProject(expectedProject)
    if (!project.ok) {
      return project
    }
    val beforeTexts = visibleTextSet()
    val textSet = shellInput?.enterPrompt(cleanPrompt) == true ||
      run {
        bridge.tapScreenRatio(packageName, COMPOSER_X_RATIO, COMPOSER_Y_RATIO, COMPOSER_FOCUS_TIMEOUT_MILLIS)
        delay(250L)
        bridge.setTextInFocusedInput(packageName, cleanPrompt, TEXT_INPUT_TIMEOUT_MILLIS) ||
          bridge.setTextInFirstEditableInput(packageName, cleanPrompt, TEXT_INPUT_TIMEOUT_MILLIS)
      }
    if (!textSet) {
      return ChatGPTPhoneActionResult(
        ok = false,
        stage = "set_prompt",
        failureCode = ChatGPTPhoneFailureCode.INPUT_NOT_FOUND,
        detail = "Could not set text in a ChatGPT input"
      )
    }
    val sent = shellInput?.tapSend() == true ||
      bridge.tapSelectorCenter(
        expectedPackageName = packageName,
        selectors = listOf(PhoneAutomationSelector(contentDescription = "Send Message")),
        timeoutMillis = SEND_TIMEOUT_MILLIS
      )
    if (!sent) {
      return ChatGPTPhoneActionResult(
        ok = false,
        stage = "send_prompt",
        failureCode = ChatGPTPhoneFailureCode.SEND_NOT_FOUND,
        detail = "Could not tap ChatGPT send button"
      )
    }
    return waitForResult(cleanPrompt, beforeTexts)
  }

  suspend fun runTextJobRootOnly(
    expectedProject: String,
    prompt: String,
    responseWaitMillis: Long = ROOT_RESPONSE_SETTLE_MILLIS
  ): ChatGPTPhoneRootRunResult {
    val rootInput = shellInput ?: return ChatGPTPhoneRootRunResult(
      ok = false,
      stage = "preflight",
      failureCode = ChatGPTPhoneFailureCode.LAUNCH_FAILED,
      detail = "Root shell input is not configured"
    )
    val cleanPrompt = prompt.trim()
    if (cleanPrompt.isEmpty()) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "preflight",
        failureCode = ChatGPTPhoneFailureCode.INPUT_NOT_FOUND,
        detail = "Prompt is empty"
      )
    }
    if (!isPackageInstalled(packageName)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "preflight",
        failureCode = ChatGPTPhoneFailureCode.NOT_INSTALLED,
        detail = "ChatGPT is not installed"
      )
    }
    if (!rootInput.launchPackage(packageName)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "launch",
        failureCode = ChatGPTPhoneFailureCode.LAUNCH_FAILED,
        detail = "Root launch command did not start ChatGPT"
      )
    }
    delay(ROOT_LAUNCH_SETTLE_MILLIS)
    if (!rootInput.tapNewChat()) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "open_new_chat",
        failureCode = ChatGPTPhoneFailureCode.SEND_NOT_FOUND,
        detail = "Root tap for new chat did not complete"
      )
    }
    delay(ROOT_AFTER_NEW_CHAT_SETTLE_MILLIS)
    val hierarchy = rootInput.visibleHierarchyXml()
    if (looksLikeLoginScreen(hierarchy)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "verify_session",
        failureCode = ChatGPTPhoneFailureCode.NOT_LOGGED_IN,
        detail = "ChatGPT appears to be showing a login screen"
      )
    }
    if (expectedProject.isNotBlank() && !hierarchy.contains(expectedProject, ignoreCase = true)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "verify_project",
        failureCode = ChatGPTPhoneFailureCode.PROJECT_NOT_VERIFIED,
        detail = "Expected ChatGPT Project was not visible in the rooted UI hierarchy"
      )
    }
    if (!rootInput.enterPrompt(cleanPrompt)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "set_prompt",
        failureCode = ChatGPTPhoneFailureCode.INPUT_NOT_FOUND,
        detail = "Root prompt entry command failed"
      )
    }
    delay(ROOT_AFTER_PROMPT_SETTLE_MILLIS)
    if (!rootInput.tapSend()) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "send_prompt",
        failureCode = ChatGPTPhoneFailureCode.SEND_NOT_FOUND,
        detail = "Root send tap command failed"
      )
    }
    delay(responseWaitMillis.coerceIn(ROOT_MIN_RESPONSE_SETTLE_MILLIS, ROOT_MAX_RESPONSE_SETTLE_MILLIS))
    val screenshot = rootInput.screenshotPngBase64()
    if (screenshot.isBlank()) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "capture_result",
        failureCode = ChatGPTPhoneFailureCode.RESULT_EXTRACTION_UNVERIFIED,
        detail = "Root screenshot capture returned no image data"
      )
    }
    return ChatGPTPhoneRootRunResult(
      ok = true,
      stage = "screenshot_ready",
      detail = "Root ChatGPT job completed through screenshot handoff",
      screenshotPngBase64 = screenshot
    )
  }

  suspend fun abortCurrentJob(): ChatGPTPhoneActionResult {
    val backedOut = bridge.performBack()
    return ChatGPTPhoneActionResult(
      ok = backedOut,
      stage = "abort",
      failureCode = if (backedOut) null else ChatGPTPhoneFailureCode.CANCELLED,
      detail = if (backedOut) "Back action sent to ChatGPT" else "Could not send back action"
    )
  }

  suspend fun resetSafeIdle(): ChatGPTPhoneActionResult {
    repeat(2) {
      bridge.performBack()
      delay(150L)
    }
    return ChatGPTPhoneActionResult(ok = true, stage = "reset_safe_idle", detail = "Safe-idle back sequence sent")
  }

  private fun launchIntent(): Intent? {
    return context.packageManager.getLaunchIntentForPackage(packageName)
  }

  private suspend fun tapNewChatIfVisible() {
    if (shellInput?.tapNewChat() != true) {
      bridge.tapSelectorCenter(
        expectedPackageName = packageName,
        selectors = listOf(PhoneAutomationSelector(contentDescription = "New chat")),
        timeoutMillis = 500L
      )
    }
    delay(300L)
  }

  private suspend fun waitForResult(
    prompt: String,
    beforeTexts: Set<String>
  ): ChatGPTPhoneActionResult {
    val deadline = System.currentTimeMillis() + RESPONSE_TIMEOUT_MILLIS
    var lastCandidate = ""
    while (System.currentTimeMillis() < deadline) {
      val nodes = bridge.snapshotVisibleNodes(packageName)
      val candidate = extractResultCandidate(nodes, prompt, beforeTexts)
      if (candidate.isNotBlank()) {
        lastCandidate = candidate
      }
      delay(RESPONSE_POLL_MILLIS)
    }
    return ChatGPTPhoneActionResult(
      ok = false,
      stage = "extract_result",
      failureCode = ChatGPTPhoneFailureCode.RESULT_EXTRACTION_UNVERIFIED,
      detail = "ChatGPT completed or timed out without exposing assistant text to accessibility",
      resultText = lastCandidate
    )
  }

  private suspend fun visibleTextSet(): Set<String> {
    return bridge.snapshotVisibleNodes(packageName)
      .mapNotNull { node -> node.text.trim().takeIf { it.isNotBlank() } }
      .toSet()
  }

  private fun extractResultCandidate(
    nodes: List<PhoneAutomationVisibleNode>,
    prompt: String,
    beforeTexts: Set<String>
  ): String {
    val promptClean = prompt.trim()
    return nodes
      .asSequence()
      .map { node -> node.text.trim() }
      .filter { text -> text.isNotBlank() }
      .filterNot { text -> text == promptClean || text == "$promptClean " }
      .filterNot { text -> text.startsWith("Reply exactly:", ignoreCase = true) }
      .filterNot { text -> text in beforeTexts }
      .filterNot { text -> isChatGPTChromeText(text) }
      .distinct()
      .joinToString("\n")
      .trim()
  }

  private fun isChatGPTChromeText(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return normalized in setOf(
      "chatgpt",
      "ask chatgpt",
      "reply to chatgpt",
      "new chat",
      "temporary chat",
      "send message",
      "sending",
      "start a voice conversation"
    )
  }

  private fun looksLikeLoginScreen(hierarchyXml: String): Boolean {
    val normalized = hierarchyXml.lowercase()
    return normalized.contains("log in") ||
      normalized.contains("sign up") ||
      normalized.contains("continue with google") ||
      normalized.contains("continue with apple")
  }

  private fun isPackageInstalled(packageName: String): Boolean {
    return packageInfo(packageName) != null
  }

  private fun packageVersion(packageName: String): String {
    return packageInfo(packageName)?.versionName.orEmpty()
  }

  private fun packageInfo(packageName: String): android.content.pm.PackageInfo? {
    return runCatching {
      @Suppress("DEPRECATION")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
      } else {
        context.packageManager.getPackageInfo(packageName, 0)
      }
    }.getOrNull()
  }

  private fun installerPackage(packageName: String): String {
    return runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(packageName).installingPackageName.orEmpty()
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(packageName).orEmpty()
      }
    }.getOrDefault("")
  }

  companion object {
    const val DEFAULT_PACKAGE = "com.openai.chatgpt"
    private const val APP_LAUNCH_TIMEOUT_MILLIS = 8_000L
    private const val PROJECT_VERIFY_TIMEOUT_MILLIS = 5_000L
    private const val TEXT_INPUT_TIMEOUT_MILLIS = 2_000L
    private const val SEND_TIMEOUT_MILLIS = 3_000L
    private const val RESPONSE_TIMEOUT_MILLIS = 90_000L
    private const val RESPONSE_POLL_MILLIS = 750L
    private const val COMPOSER_FOCUS_TIMEOUT_MILLIS = 1_000L
    private const val COMPOSER_X_RATIO = 0.35
    private const val COMPOSER_Y_RATIO = 0.94
    private const val ROOT_LAUNCH_SETTLE_MILLIS = 1_500L
    private const val ROOT_AFTER_NEW_CHAT_SETTLE_MILLIS = 800L
    private const val ROOT_AFTER_PROMPT_SETTLE_MILLIS = 400L
    private const val ROOT_MIN_RESPONSE_SETTLE_MILLIS = 10_000L
    private const val ROOT_RESPONSE_SETTLE_MILLIS = 90_000L
    private const val ROOT_MAX_RESPONSE_SETTLE_MILLIS = 300_000L

    fun classifyScreen(
      nodes: List<PhoneAutomationVisibleNode>,
      expectedProject: String
    ): ChatGPTPhoneScreenState {
      if (nodes.isEmpty()) {
        return ChatGPTPhoneScreenState.UNKNOWN
      }
      val haystack = nodes.flatMap { node ->
        listOf(node.text, node.contentDescription, node.hint)
      }.filter { it.isNotBlank() }

      if (haystack.any { it.contains("log in", ignoreCase = true) || it.contains("sign up", ignoreCase = true) }) {
        return ChatGPTPhoneScreenState.LOGIN
      }
      val projectVisible = expectedProject.isNotBlank() &&
        haystack.any { it.contains(expectedProject, ignoreCase = true) }
      val composerVisible = nodes.any { node ->
        node.editable ||
          node.hint.contains("message", ignoreCase = true) ||
          node.contentDescription.contains("Ask ChatGPT", ignoreCase = true) ||
          node.contentDescription.contains("Reply to ChatGPT", ignoreCase = true) ||
          node.text.contains("message", ignoreCase = true) ||
          node.contentDescription.contains("message", ignoreCase = true)
      }
      val responseVisible = haystack.any { it.contains("copy", ignoreCase = true) } && composerVisible
      return when {
        projectVisible && composerVisible -> ChatGPTPhoneScreenState.COMPOSER_READY
        projectVisible -> ChatGPTPhoneScreenState.PROJECT_VERIFIED
        responseVisible -> ChatGPTPhoneScreenState.RESPONSE_VISIBLE
        else -> ChatGPTPhoneScreenState.HOME_OR_PROJECT
      }
    }

    private fun sanitizeNodes(nodes: List<PhoneAutomationVisibleNode>): List<PhoneAutomationVisibleNode> {
      return nodes.take(200).map { node ->
        node.copy(
          text = node.text.take(160),
          contentDescription = node.contentDescription.take(160),
          hint = node.hint.take(160)
        )
      }
    }
  }
}

interface ChatGPTShellInput {
  suspend fun launchPackage(packageName: String): Boolean = false
  suspend fun tapNewChat(): Boolean
  suspend fun visibleHierarchyXml(): String = ""
  suspend fun enterPrompt(prompt: String): Boolean
  suspend fun tapSend(): Boolean
  suspend fun screenshotPngBase64(): String = ""
}

class ChatGPTRootShellInput(
  private val rootExecutor: RootExecutor
) : ChatGPTShellInput {
  override suspend fun launchPackage(packageName: String): Boolean {
    val result = rootExecutor.runScript(
      "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1",
      5.seconds
    )
    return result.ok
  }

  override suspend fun tapNewChat(): Boolean {
    val bounds = findNodeBounds(visibleHierarchyXml()) { node ->
      node.enabled && node.contentDescription.equals("New chat", ignoreCase = true)
    } ?: return false
    return tap(bounds)
  }

  override suspend fun visibleHierarchyXml(): String {
    val command = """
      tmp=/data/local/tmp/chatgpt-broker-hierarchy.xml
      rm -f "${'$'}tmp"
      uiautomator dump --compressed "${'$'}tmp" >/dev/null 2>&1
      cat "${'$'}tmp" 2>/dev/null || true
      rm -f "${'$'}tmp"
    """.trimIndent()
    val result = rootExecutor.runScript(command, 8.seconds)
    return if (result.ok) result.stdout else ""
  }

  override suspend fun enterPrompt(prompt: String): Boolean {
    val inputText = promptForRootInput(prompt)
    val bounds = findComposerBounds(visibleHierarchyXml()) ?: return false
    val (x, y) = bounds.center()
    val command = """
      input tap $x $y
      sleep 0.150
      input keyevent KEYCODE_MOVE_END
      for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
        input keyevent KEYCODE_DEL
      done
      input text ${shellQuote(inputText)}
    """.trimIndent()
    if (!rootExecutor.runScript(command, promptInputTimeout(inputText)).ok) {
      return false
    }
    delay(300L)
    return hierarchyContainsPrompt(visibleHierarchyXml(), inputText)
  }

  override suspend fun tapSend(): Boolean {
    val bounds = findNodeBounds(visibleHierarchyXml()) { node ->
      node.enabled && node.contentDescription.contains("send", ignoreCase = true)
    } ?: return false
    return tap(bounds)
  }

  override suspend fun screenshotPngBase64(): String {
    val command = """
      tmp=/data/local/tmp/chatgpt-broker-result.png
      rm -f "${'$'}tmp"
      screencap -p "${'$'}tmp"
      base64 "${'$'}tmp" 2>/dev/null | tr -d '\n' || true
      rm -f "${'$'}tmp"
    """.trimIndent()
    val result = rootExecutor.runScript(command, 12.seconds)
    return if (result.ok) result.stdout.trim() else ""
  }

  private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
  }

  private suspend fun tap(bounds: UiBounds): Boolean {
    val (x, y) = bounds.center()
    return rootExecutor.runScript("input tap $x $y", 3.seconds).ok
  }

  private fun findComposerBounds(hierarchyXml: String): UiBounds? {
    val nodes = parseNodes(hierarchyXml)
    return nodes
      .filter { node ->
        node.enabled &&
          node.className == "android.widget.EditText" &&
          node.bounds != null
      }
      .maxWithOrNull(compareBy<UiNode> { it.bounds?.area() ?: 0 }.thenBy { it.bounds?.bottom ?: 0 })
      ?.bounds
      ?: findNodeBounds(hierarchyXml) { node ->
        node.enabled &&
          (node.contentDescription.contains("Reply to ChatGPT", ignoreCase = true) ||
            node.contentDescription.contains("Ask ChatGPT", ignoreCase = true))
      }
  }

  private fun findNodeBounds(hierarchyXml: String, predicate: (UiNode) -> Boolean): UiBounds? {
    return parseNodes(hierarchyXml)
      .asSequence()
      .filter(predicate)
      .mapNotNull { it.bounds }
      .maxWithOrNull(compareBy<UiBounds> { it.area() }.thenBy { it.right }.thenBy { it.bottom })
  }

  private fun parseNodes(hierarchyXml: String): List<UiNode> {
    return NODE_REGEX.findAll(hierarchyXml)
      .map { match ->
        val tag = match.value
        val attrs = ATTR_REGEX.findAll(tag).associate { attr ->
          attr.groupValues[1] to xmlUnescape(attr.groupValues[2])
        }
        UiNode(
          className = attrs["class"].orEmpty(),
          text = attrs["text"].orEmpty(),
          contentDescription = attrs["content-desc"].orEmpty(),
          hint = attrs["hint"].orEmpty(),
          enabled = attrs["enabled"] == "true",
          bounds = parseBounds(attrs["bounds"].orEmpty())
        )
      }
      .toList()
  }

  private fun parseBounds(raw: String): UiBounds? {
    val match = BOUNDS_REGEX.matchEntire(raw) ?: return null
    return UiBounds(
      left = match.groupValues[1].toIntOrNull() ?: return null,
      top = match.groupValues[2].toIntOrNull() ?: return null,
      right = match.groupValues[3].toIntOrNull() ?: return null,
      bottom = match.groupValues[4].toIntOrNull() ?: return null
    ).takeIf { it.right > it.left && it.bottom > it.top }
  }

  private fun hierarchyContainsPrompt(hierarchyXml: String, prompt: String): Boolean {
    val evidence = prompt.trim().take(PROMPT_VERIFY_CHARS)
    if (evidence.isBlank()) {
      return false
    }
    val escaped = xmlEscape(evidence)
    return hierarchyXml.contains(evidence) || hierarchyXml.contains(escaped)
  }

  private fun promptForRootInput(prompt: String): String {
    return prompt
      .replace("\r\n", " ")
      .replace('\n', ' ')
      .replace('\r', ' ')
      .replace('\t', ' ')
      .replace(Regex(" {2,}"), " ")
      .trim()
  }

  private fun promptInputTimeout(prompt: String) = ((prompt.length / 35) + 18).coerceIn(18, 120).seconds

  private fun xmlEscape(value: String): String {
    return value
      .replace("&", "&amp;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
  }

  private fun xmlUnescape(value: String): String {
    return value
      .replace("&quot;", "\"")
      .replace("&apos;", "'")
      .replace("&lt;", "<")
      .replace("&gt;", ">")
      .replace("&amp;", "&")
  }

  private data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
  ) {
    fun area(): Int = (right - left) * (bottom - top)
    fun center(): Pair<Int, Int> = ((left + right) / 2) to ((top + bottom) / 2)
  }

  private data class UiNode(
    val className: String,
    val text: String,
    val contentDescription: String,
    val hint: String,
    val enabled: Boolean,
    val bounds: UiBounds?
  )

  private companion object {
    const val PROMPT_VERIFY_CHARS = 80
    val NODE_REGEX = Regex("""<node\b[^>]*>""")
    val ATTR_REGEX = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
  }
}
