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
  PORTRAIT_LOCK_FAILED,
  BLOCKING_OVERLAY,
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
  val resultText: String = "",
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
    startNewChat: Boolean = false,
    responseWaitMillis: Long = ROOT_RESPONSE_SETTLE_MILLIS,
    shouldAbort: suspend () -> Boolean = { false }
  ): ChatGPTPhoneRootRunResult {
    suspend fun abortResult(stage: String): ChatGPTPhoneRootRunResult? {
      if (!shouldAbort()) {
        return null
      }
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = stage,
        failureCode = ChatGPTPhoneFailureCode.CANCELLED,
        detail = "Phone automation was interrupted before touching ChatGPT again"
      )
    }

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
    abortResult("preflight")?.let { return it }
    if (!rootInput.forcePortraitLock() || !rootInput.verifyPortraitLocked()) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "force_portrait",
        failureCode = ChatGPTPhoneFailureCode.PORTRAIT_LOCK_FAILED,
        detail = "Root portrait lock could not be verified"
      )
    }
    abortResult("force_portrait")?.let { return it }
    if (!rootInput.launchPackage(packageName)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "launch",
        failureCode = ChatGPTPhoneFailureCode.LAUNCH_FAILED,
        detail = "Root launch command did not start ChatGPT"
      )
    }
    delay(ROOT_LAUNCH_SETTLE_MILLIS)
    abortResult("launch")?.let { return it }
    rootInput.dismissKnownBlockingOverlays()
    rootInput.forcePortraitLock()
    if (!rootInput.verifyPortraitLocked()) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "verify_portrait",
        failureCode = ChatGPTPhoneFailureCode.PORTRAIT_LOCK_FAILED,
        detail = "ChatGPT launch left the display outside portrait lock"
      )
    }
    abortResult("verify_portrait")?.let { return it }
    if (!rootInput.enterProject(expectedProject)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "enter_project",
        failureCode = ChatGPTPhoneFailureCode.PROJECT_NOT_VERIFIED,
        detail = "Root navigation could not enter the expected ChatGPT Project"
      )
    }
    abortResult("enter_project")?.let { return it }
    if (startNewChat) {
      if (!rootInput.tapNewChat()) {
        return ChatGPTPhoneRootRunResult(
          ok = false,
          stage = "open_new_chat",
          failureCode = ChatGPTPhoneFailureCode.SEND_NOT_FOUND,
          detail = "Root tap for new chat did not complete"
        )
      }
      delay(ROOT_AFTER_NEW_CHAT_SETTLE_MILLIS)
      abortResult("open_new_chat")?.let { return it }
    }
    val hierarchy = rootInput.visibleHierarchyXml()
    if (looksLikeLoginScreen(hierarchy)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "verify_session",
        failureCode = ChatGPTPhoneFailureCode.NOT_LOGGED_IN,
        detail = "ChatGPT appears to be showing a login screen"
      )
    }
    abortResult("verify_session")?.let { return it }
    if (expectedProject.isNotBlank() && !rootInput.verifyProjectSurface(expectedProject)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "verify_project",
        failureCode = ChatGPTPhoneFailureCode.PROJECT_NOT_VERIFIED,
        detail = "Expected ChatGPT Project could not be re-opened from the rooted UI hierarchy"
      )
    }
    abortResult("verify_project")?.let { return it }
    if (!rootInput.enterPrompt(cleanPrompt)) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "set_prompt",
        failureCode = ChatGPTPhoneFailureCode.INPUT_NOT_FOUND,
        detail = "Root prompt entry command failed"
      )
    }
    delay(ROOT_AFTER_PROMPT_SETTLE_MILLIS)
    abortResult("set_prompt")?.let { return it }
    val beforeResponseHierarchy = rootInput.visibleHierarchyXml()
    if (!rootInput.tapSend()) {
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "send_prompt",
        failureCode = ChatGPTPhoneFailureCode.SEND_NOT_FOUND,
        detail = "Root send tap command failed"
      )
    }
    abortResult("send_prompt")?.let { return it }
    val answer = rootInput.waitForLatestAnswerText(
      prompt = cleanPrompt,
      beforeHierarchyXml = beforeResponseHierarchy,
      timeoutMillis = responseWaitMillis.coerceIn(ROOT_MIN_RESPONSE_SETTLE_MILLIS, ROOT_MAX_RESPONSE_SETTLE_MILLIS),
      shouldAbort = shouldAbort
    )
    if (answer.isBlank()) {
      abortResult("capture_result")?.let { return it }
      return ChatGPTPhoneRootRunResult(
        ok = false,
        stage = "capture_result",
        failureCode = ChatGPTPhoneFailureCode.RESULT_EXTRACTION_UNVERIFIED,
        detail = "Root UI extraction returned no assistant answer text"
      )
    }
    return ChatGPTPhoneRootRunResult(
      ok = true,
      stage = "answer_ready",
      detail = "Root ChatGPT job completed through UI text extraction",
      resultText = answer
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
  suspend fun forcePortraitLock(): Boolean = true
  suspend fun verifyPortraitLocked(): Boolean = true
  suspend fun dismissKnownBlockingOverlays(): Boolean = true
  suspend fun launchPackage(packageName: String): Boolean = false
  suspend fun enterProject(expectedProject: String): Boolean = true
  suspend fun verifyProjectSurface(expectedProject: String): Boolean = true
  suspend fun tapNewChat(): Boolean
  suspend fun visibleHierarchyXml(): String = ""
  suspend fun enterPrompt(prompt: String): Boolean
  suspend fun tapSend(): Boolean
  suspend fun waitForLatestAnswerText(
    prompt: String,
    beforeHierarchyXml: String,
    timeoutMillis: Long,
    shouldAbort: suspend () -> Boolean = { false }
  ): String = ""
  suspend fun screenshotPngBase64(): String = ""
}

class ChatGPTRootShellInput(
  private val rootExecutor: RootExecutor,
  private val bridge: PhoneAutomationServiceBridge = PhoneAutomationServiceBridge,
  private val clipboardTextSetter: ((String) -> Boolean)? = null
) : ChatGPTShellInput {
  private var projectOverviewReadyAtMillis: Long = 0L
  private var projectOverviewProject: String = ""

  override suspend fun forcePortraitLock(): Boolean {
    return PhonePortraitLock.force(rootExecutor)
  }

  override suspend fun verifyPortraitLocked(): Boolean {
    return PhonePortraitLock.verify(rootExecutor)
  }

  override suspend fun launchPackage(packageName: String): Boolean {
    resetProjectOverviewReadiness()
    val result = rootExecutor.runScript(
      "monkey -p ${shellQuote(packageName)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1",
      5.seconds
    )
    return result.ok
  }

  override suspend fun dismissKnownBlockingOverlays(): Boolean {
    rootExecutor.runScript("cmd statusbar collapse >/dev/null 2>&1 || true", 3.seconds)
    val hierarchy = visibleHierarchyXml()
    if (looksLikeAndroidShareSheet(hierarchy)) {
      rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
      delay(500L)
      return true
    }
    if (looksLikeExternalShareSurface(hierarchy)) {
      repeat(2) {
        rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
        delay(350L)
      }
      return true
    }
    if (hierarchy.contains("Add files", ignoreCase = true) ||
      hierarchy.contains("Upload new file", ignoreCase = true) ||
      hierarchy.contains("Attach ", ignoreCase = true) && hierarchy.contains("items", ignoreCase = true)
    ) {
      rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
      delay(500L)
      return true
    }
    if (!hierarchy.contains("com.android.vending") && !hierarchy.contains("Reviews are public")) {
      return true
    }
    val notNowBounds = findNodeBounds(hierarchy) { node ->
      node.enabled && node.text.equals("Not now", ignoreCase = true)
    }
    if (notNowBounds != null) {
      tap(notNowBounds)
      delay(500L)
      return true
    }
    rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
    delay(500L)
    return true
  }

  override suspend fun enterProject(expectedProject: String): Boolean {
    val project = expectedProject.trim()
    if (project.isBlank()) {
      return true
    }
    resetProjectOverviewReadiness()
    repeat(3) {
      rootExecutor.runScript("cmd statusbar collapse >/dev/null 2>&1 || true", 3.seconds)
      if (!openProjectsList()) {
        delay(350L)
        if (verifyProjectSurface(project)) {
          return true
        }
        return@repeat
      }
      if (tapProjectListRow(project)) {
        delay(1_000L)
      }
      val hierarchy = visibleHierarchyXml()
      if (looksLikeProjectOverviewAfterProjectListTap(hierarchy)) {
        markProjectOverviewReady(project)
        return true
      }
      if (verifyProjectSurface(project) || looksLikeNewProjectComposer(hierarchy)) {
        return true
      }
    }
    return false
  }

  override suspend fun verifyProjectSurface(expectedProject: String): Boolean {
    val project = expectedProject.trim()
    if (project.isBlank()) {
      return true
    }
    val hierarchy = visibleHierarchyXml()
    return looksLikeProjectComposer(hierarchy, project) ||
      (recentProjectOverviewReady(project) && looksLikeProjectOverviewAfterProjectListTap(hierarchy))
  }

  override suspend fun tapNewChat(): Boolean {
    repeat(3) {
      val hierarchy = visibleHierarchyXml()
      val scene = classifyRootScene(hierarchy, projectOverviewProject)
      if (scene == ChatGPTRootScene.PROJECT_NEW_CHAT_COMPOSER && findComposerBounds(hierarchy) != null) {
        return true
      }
      val bounds = findNewChatActionBounds(hierarchy)
      if (bounds != null && tap(bounds)) {
        delay(900L)
        val afterTap = visibleHierarchyXml()
        if (looksLikeNewProjectComposer(afterTap) || looksLikeProjectComposer(afterTap, projectOverviewProject)) {
          return true
        }
      }
      delay(350L)
    }
    return false
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
    val displayText = promptForRootInput(prompt)
    if (displayText.isBlank() || !setClipboardText(displayText)) {
      return false
    }
    val hierarchy = visibleHierarchyXml()
    val bounds = findComposerBounds(hierarchy)
    val (x, y) = bounds?.center() ?: inferredComposerFocus(hierarchy) ?: return false
    val command = """
      input tap $x $y
      sleep 0.150
      input keyevent KEYCODE_CLEAR
      sleep 0.100
      input keycombination KEYCODE_CTRL_LEFT KEYCODE_A
      sleep 0.100
      input keyevent KEYCODE_DEL
      sleep 0.100
      input keyevent KEYCODE_PASTE
    """.trimIndent()
    if (!rootExecutor.runScript(command, promptInputTimeout(displayText)).ok) {
      return false
    }
    delay(300L)
    val afterPaste = visibleHierarchyXml()
    return promptEvidenceMatches(composerText(afterPaste), displayText) ||
      hierarchyContainsPrompt(afterPaste, displayText)
  }

  override suspend fun tapSend(): Boolean {
    val hierarchy = visibleHierarchyXml()
    val bounds = findNodeBounds(hierarchy) { node ->
      node.enabled &&
        node.contentDescription.contains("send", ignoreCase = true) &&
        !node.contentDescription.contains("voice", ignoreCase = true)
    }
    val (_, inferredY) = inferredComposerFocus(hierarchy) ?: (PROJECT_SEND_FALLBACK_X to (bounds?.center()?.second ?: PROJECT_SEND_KEYBOARD_FALLBACK_Y))
    val candidates = buildList {
      if (bounds != null) {
        add(bounds.center())
        add(PROJECT_SEND_FALLBACK_X to bounds.center().second)
      }
      add(PROJECT_SEND_FALLBACK_X to inferredY)
      add(PROJECT_SEND_FALLBACK_X to PROJECT_SEND_KEYBOARD_FALLBACK_Y)
    }.distinct()
    var tapped = false
    candidates.forEach { (x, y) ->
      if (tapCoordinate(x, y)) {
        tapped = true
      }
      delay(500L)
      if (composerText(visibleHierarchyXml()).isBlank()) {
        return true
      }
    }
    return tapped && composerText(visibleHierarchyXml()).isBlank()
  }

  override suspend fun waitForLatestAnswerText(
    prompt: String,
    beforeHierarchyXml: String,
    timeoutMillis: Long,
    shouldAbort: suspend () -> Boolean
  ): String {
    val beforeTexts = parseNodes(beforeHierarchyXml).mapNotNull { node ->
      normalizedNodeText(node).takeIf { it.isNotBlank() }
    }.toSet()
    val deadline = System.currentTimeMillis() + timeoutMillis
    var stableCandidate = ""
    var stableCount = 0
    var sawGeneration = false
    var idleAfterGenerationCount = 0
    while (System.currentTimeMillis() < deadline) {
      if (shouldAbort()) {
        return ""
      }
      val hierarchy = visibleHierarchyXml()
      if (dismissResultBlockingSheet(hierarchy)) {
        delay(800L)
        continue
      }
      val candidate = extractAnswerCandidate(hierarchy, prompt, beforeTexts)
      val generating = isGenerating(hierarchy)
      if (generating) {
        sawGeneration = true
        idleAfterGenerationCount = 0
      } else if (sawGeneration) {
        idleAfterGenerationCount += 1
      }
      if (candidate.isNotBlank()) {
        if (candidate == stableCandidate) {
          stableCount += 1
        } else {
          stableCandidate = candidate
          stableCount = 1
        }
      }
      val waitedLongEnoughForFastResponse =
        System.currentTimeMillis() > deadline - timeoutMillis + COPY_FAST_RESPONSE_WAIT_MILLIS
      if (!generating && ((sawGeneration && idleAfterGenerationCount >= 2) || (!sawGeneration && waitedLongEnoughForFastResponse))) {
        if (stableCandidate.isNotBlank() && stableCount >= 2) {
          return stableCandidate
        }
        val copiedAnswer = copyLatestAnswerThroughComposer(prompt, beforeTexts, hierarchy)
        if (copiedAnswer.isNotBlank()) {
          return copiedAnswer
        }
      }
      delay(1_500L)
    }
    return ""
  }

  override suspend fun screenshotPngBase64(): String {
    val command = """
      screencap -p 2>/dev/null | base64 2>/dev/null | tr -d '\n' || true
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

  private suspend fun tapText(text: String): Boolean {
    val bounds = findNodeBounds(visibleHierarchyXml()) { node ->
      node.enabled && node.text.equals(text, ignoreCase = true)
    } ?: return false
    return tap(bounds)
  }

  private fun findProjectListRowBounds(hierarchyXml: String, project: String): UiBounds? {
    val expected = project.trim()
    if (expected.isBlank()) {
      return null
    }
    return parseNodes(hierarchyXml)
      .asSequence()
      .filter { node -> node.enabled && node.bounds != null }
      .filter { node ->
        val text = normalizedNodeText(node)
        text.equals(expected, ignoreCase = true) ||
          node.contentDescription.equals(expected, ignoreCase = true)
      }
      .sortedWith(compareByDescending<UiNode> { it.clickable }.thenBy { it.bounds?.top ?: Int.MAX_VALUE })
      .mapNotNull { it.bounds }
      .firstOrNull()
  }

  private fun findNewChatActionBounds(hierarchyXml: String): UiBounds? {
    return parseNodes(hierarchyXml)
      .asSequence()
      .filter { node -> node.enabled && node.bounds != null }
      .filter { node ->
        val text = normalizedNodeText(node)
        text.equals("New chat", ignoreCase = true) ||
          text.contains("New chat in", ignoreCase = true) ||
          node.contentDescription.equals("New chat", ignoreCase = true) ||
          node.contentDescription.contains("New chat in", ignoreCase = true) ||
          node.hint.equals("New chat", ignoreCase = true)
      }
      .sortedWith(
        compareByDescending<UiNode> { it.clickable }
          .thenBy { it.bounds?.top ?: Int.MAX_VALUE }
          .thenBy { it.bounds?.left ?: Int.MAX_VALUE }
      )
      .mapNotNull { it.bounds }
      .firstOrNull()
  }

  private suspend fun tapDrawerProjectsRoute(): Boolean {
    if (!looksLikeDrawer(visibleHierarchyXml())) {
      hideKeyboardIfVisible()
      if (!tapMenu()) {
        return false
      }
      delay(650L)
    }
    if (tapText("Projects")) {
      delay(900L)
      return looksLikeProjectsList(visibleHierarchyXml())
    }
    return false
  }

  private suspend fun openProjectsList(): Boolean {
    if (looksLikeProjectsList(visibleHierarchyXml())) {
      return true
    }
    if (looksLikeProjectActionSurface(visibleHierarchyXml()) && returnToProjectsListFromProjectSurface()) {
      return true
    }
    return tapDrawerProjectsRoute()
  }

  private suspend fun returnToProjectsListFromProjectSurface(): Boolean {
    hideKeyboardIfVisible()
    repeat(3) {
      rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
      delay(650L)
      if (looksLikeProjectsList(visibleHierarchyXml())) {
        return true
      }
    }
    return false
  }

  private suspend fun tapProjectListRow(project: String): Boolean {
    repeat(4) {
      val hierarchy = visibleHierarchyXml()
      if (!looksLikeProjectsList(hierarchy)) {
        return false
      }
      val bounds = findProjectListRowBounds(hierarchy, project)
      if (bounds != null && tap(bounds)) {
        return true
      }
      rootExecutor.runScript("input swipe 500 1750 500 850 250", 4.seconds)
      delay(450L)
    }
    return false
  }

  private suspend fun tapMenu(): Boolean {
    val hierarchy = visibleHierarchyXml()
    val menuBounds = findNodeBounds(hierarchy) { node ->
      node.enabled && (
        node.contentDescription.equals("Menu", ignoreCase = true) ||
          node.contentDescription.equals("Open sidebar", ignoreCase = true) ||
          node.contentDescription.equals("Navigation menu", ignoreCase = true)
        )
    }
    if (menuBounds != null) {
      return tap(menuBounds)
    }
    val result = rootExecutor.runScript("input swipe 5 1200 850 1200 350", 4.seconds)
    delay(650L)
    return result.ok && looksLikeDrawer(visibleHierarchyXml())
  }

  private suspend fun tapCoordinate(x: Int, y: Int): Boolean {
    return rootExecutor.runScript("input tap $x $y", 3.seconds).ok
  }

  private suspend fun dismissResultBlockingSheet(hierarchyXml: String): Boolean {
    if (!hierarchyXml.contains("Get updates on your tasks", ignoreCase = true) &&
      !hierarchyXml.contains("Turn on notification", ignoreCase = true)
    ) {
      return false
    }
    val notNowBounds = findNodeBounds(hierarchyXml) { node ->
      node.enabled && node.text.equals("Not now", ignoreCase = true)
    }
    if (notNowBounds != null) {
      return tap(notNowBounds)
    }
    return false
  }

  private suspend fun copyLatestAnswerThroughComposer(
    prompt: String,
    beforeTexts: Set<String>,
    answerHierarchyXml: String
  ): String {
    val clipboardBaseline = primeClipboardSentinel()
    if (clipboardBaseline.isBlank()) {
      return ""
    }
    clearFocusedComposerText(hideKeyboard = true)
    hideKeyboardIfVisible()
    val copyBounds = (copyActionBounds(answerHierarchyXml) + copyActionBounds(visibleHierarchyXml()))
      .distinct()
    copyBounds.forEach { bounds ->
      tap(bounds)
      delay(250L)
      val afterTapHierarchy = visibleHierarchyXml()
      if (looksLikeExternalShareSurface(afterTapHierarchy)) {
        dismissKnownBlockingOverlays()
        return@forEach
      }
      val sharedAnswer = copyAnswerFromShareSheet(
        prompt = prompt,
        beforeTexts = beforeTexts,
        clipboardBaseline = clipboardBaseline,
        answerHierarchyXml = answerHierarchyXml,
        shareSheetHierarchyXml = afterTapHierarchy
      )
      if (sharedAnswer.isNotBlank()) {
        return sharedAnswer
      }
      val pasted = pasteClipboardIntoComposerAndRead()
      clearFocusedComposerText(hideKeyboard = true)
      if (isCopiedAnswerCandidate(pasted, prompt, beforeTexts, clipboardBaseline, answerHierarchyXml)) {
        return pasted
      }
    }
    return ""
  }

  private fun copyActionBounds(hierarchyXml: String): List<UiBounds> {
    return parseNodes(hierarchyXml)
      .asSequence()
      .filter { node -> node.enabled && node.bounds != null }
      .filter { node ->
        val text = normalizedNodeText(node)
        val description = node.contentDescription
        (text.equals("Copy", ignoreCase = true) ||
          text.equals("Copy text", ignoreCase = true) ||
          description.equals("Copy", ignoreCase = true) ||
          description.equals("Copy text", ignoreCase = true) ||
          description.contains("Copy response", ignoreCase = true)) &&
          !text.contains("Copy link", ignoreCase = true) &&
          !description.contains("Copy link", ignoreCase = true)
      }
      .sortedWith(
        compareByDescending<UiNode> { it.bounds?.top ?: 0 }
          .thenByDescending { it.clickable }
          .thenBy { it.bounds?.left ?: 0 }
      )
      .mapNotNull { it.bounds }
      .toList()
  }

  private suspend fun primeClipboardSentinel(): String {
    val sentinel = "CHATGPTBROKERSENTINEL${System.currentTimeMillis()}"
    return if (setClipboardText(sentinel)) sentinel else ""
  }

  private fun setClipboardText(text: String): Boolean {
    val setDirectly = clipboardTextSetter?.let { setter ->
      runCatching { setter(text) }.getOrDefault(false)
    } ?: false
    return setDirectly || bridge.setClipboardText(text)
  }

  private suspend fun copyAnswerFromShareSheet(
    prompt: String,
    beforeTexts: Set<String>,
    clipboardBaseline: String,
    answerHierarchyXml: String,
    shareSheetHierarchyXml: String
  ): String {
    if (!looksLikeAndroidShareSheet(shareSheetHierarchyXml)) {
      return ""
    }
    val preview = extractShareSheetTextCandidate(
      hierarchyXml = shareSheetHierarchyXml,
      prompt = prompt,
      beforeTexts = beforeTexts,
      clipboardBaseline = clipboardBaseline
    )
    val copyTextBounds = findNodeBounds(shareSheetHierarchyXml) { node ->
      node.enabled &&
        (node.contentDescription.equals("Copy text", ignoreCase = true) ||
          node.text.equals("Copy text", ignoreCase = true))
    }
    if (copyTextBounds != null) {
      tap(copyTextBounds)
      delay(500L)
      if (looksLikeAndroidShareSheet(visibleHierarchyXml())) {
        rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
        delay(300L)
      }
      val pasted = pasteClipboardIntoComposerAndRead()
      clearFocusedComposerText(hideKeyboard = true)
      if (isCopiedAnswerCandidate(pasted, prompt, beforeTexts, clipboardBaseline, answerHierarchyXml)) {
        return pasted
      }
    }
    if (isCopiedAnswerCandidate(preview, prompt, beforeTexts, clipboardBaseline, answerHierarchyXml)) {
      rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
      delay(300L)
      return preview
    }
    rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
    delay(300L)
    return ""
  }

  private suspend fun pasteClipboardIntoComposerAndRead(): String {
    val hierarchy = visibleHierarchyXml()
    val (x, y) = findComposerBounds(hierarchy)?.center() ?: inferredComposerFocus(hierarchy) ?: return ""
    if (!rootExecutor.runScript("input tap $x $y", 3.seconds).ok) {
      return ""
    }
    delay(150L)
    clearFocusedComposerText(hideKeyboard = false)
    val command = """
      input tap $x $y
      sleep 0.150
      input keyevent KEYCODE_PASTE
    """.trimIndent()
    if (!rootExecutor.runScript(command, 5.seconds).ok) {
      return ""
    }
    delay(500L)
    return focusedComposerText(visibleHierarchyXml())
  }

  private suspend fun clearFocusedComposerText(hideKeyboard: Boolean) {
    var beforeClear = visibleHierarchyXml()
    repeat(3) {
      rootExecutor.runScript(
        """
          input keyevent KEYCODE_CLEAR
          sleep 0.100
          input keycombination KEYCODE_CTRL_LEFT KEYCODE_A
          sleep 0.100
          input keyevent KEYCODE_DEL
        """.trimIndent(),
        5.seconds
      )
      delay(150L)
      val remaining = focusedComposerText(visibleHierarchyXml())
      if (remaining.isBlank()) {
        return@repeat
      }
      val deleteCount = (remaining.length + 8).coerceIn(MIN_COMPOSER_DELETE_KEYS, MAX_COMPOSER_DELETE_KEYS)
      rootExecutor.runScript(
        """
          input keyevent KEYCODE_MOVE_END
          i=0
          while [ "${'$'}i" -lt $deleteCount ]; do
            input keyevent KEYCODE_DEL
            i=${'$'}((i + 1))
          done
        """.trimIndent(),
        12.seconds
      )
      delay(150L)
      if (focusedComposerText(visibleHierarchyXml()).isBlank()) {
        return@repeat
      }
      beforeClear = visibleHierarchyXml()
    }
    if (hideKeyboard) {
      if (keyboardAppearsVisible(beforeClear)) {
        rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
        delay(300L)
      }
      hideKeyboardIfVisible()
    }
  }

  private suspend fun hideKeyboardIfVisible() {
    repeat(3) {
      if (!keyboardAppearsVisible(visibleHierarchyXml())) {
        return
      }
      rootExecutor.runScript("input keyevent KEYCODE_BACK", 3.seconds)
      delay(400L)
    }
  }

  private fun focusedComposerText(hierarchyXml: String): String {
    return composerText(hierarchyXml)
  }

  private fun composerText(hierarchyXml: String): String {
    return parseNodes(hierarchyXml)
      .asSequence()
      .filter { node -> node.className == "android.widget.EditText" }
      .sortedWith(compareByDescending<UiNode> { it.focused }.thenByDescending { it.bounds?.area() ?: 0 })
      .map { node -> node.text.trim() }
      .firstOrNull { it.isNotBlank() }
      .orEmpty()
  }

  private fun isCopiedAnswerCandidate(
    text: String,
    prompt: String,
    beforeTexts: Set<String>,
    clipboardBaseline: String,
    answerHierarchyXml: String
  ): Boolean {
    val candidate = text.trim()
    if (candidate.isBlank() || candidate == clipboardBaseline.trim() || candidate == prompt.trim() || candidate in beforeTexts) {
      return false
    }
    if (candidate.startsWith("Reply exactly:", ignoreCase = true) || isChromeText(candidate)) {
      return false
    }
    return copiedAnswerVisibleInHierarchy(candidate, answerHierarchyXml)
  }

  private fun copiedAnswerVisibleInHierarchy(candidate: String, hierarchyXml: String): Boolean {
    val normalizedCandidate = candidate.trim()
    if (normalizedCandidate.isBlank()) {
      return false
    }
    val visibleTexts = parseNodes(hierarchyXml)
      .map { node -> normalizedNodeText(node) }
      .filter { text -> text.isNotBlank() && !isChromeText(text) }
    if (visibleTexts.any { text -> text == normalizedCandidate }) {
      return true
    }
    val candidateFragments = normalizedCandidate
      .lineSequence()
      .map { line -> line.trim() }
      .filter { line -> line.length >= COPIED_ANSWER_VISIBLE_FRAGMENT_MIN_CHARS }
      .map { line -> line.take(COPIED_ANSWER_VISIBLE_FRAGMENT_CHARS) }
      .toList()
    return candidateFragments.any { fragment ->
      visibleTexts.any { text -> text.contains(fragment, ignoreCase = false) || fragment.contains(text, ignoreCase = false) }
    }
  }

  private fun looksLikeAndroidShareSheet(hierarchyXml: String): Boolean {
    val texts = parseNodes(hierarchyXml)
      .map { node -> normalizedNodeText(node) }
      .filter { it.isNotBlank() }
    return texts.any { it.equals("Sharing text", ignoreCase = true) } &&
      (texts.any { it.equals("No recommended people to share with", ignoreCase = true) } ||
        texts.any { it.equals("Copy text", ignoreCase = true) } ||
        hierarchyXml.contains("content-desc=\"Copy text\"", ignoreCase = true))
  }

  private fun extractShareSheetTextCandidate(
    hierarchyXml: String,
    prompt: String,
    beforeTexts: Set<String>,
    clipboardBaseline: String
  ): String {
    val promptClean = prompt.trim()
    return parseNodes(hierarchyXml)
      .asSequence()
      .filter { node -> node.bounds != null }
      .map { node -> node to normalizedNodeText(node) }
      .filter { (_, text) -> text.isNotBlank() }
      .filterNot { (_, text) -> text == promptClean || text == clipboardBaseline.trim() || text in beforeTexts }
      .filterNot { (_, text) -> text.startsWith("Reply exactly:", ignoreCase = true) }
      .filterNot { (_, text) -> isChromeText(text) || isAndroidShareSheetChromeText(text) }
      .sortedWith(
        compareByDescending<Pair<UiNode, String>> { (node, text) ->
          val bounds = node.bounds
          val inPayloadBand = bounds != null && bounds.top in SHARE_SHEET_PAYLOAD_TOP_RANGE
          when {
            inPayloadBand -> 2_000 + text.length
            else -> text.length
          }
        }
      )
      .map { (_, text) -> text.trim() }
      .firstOrNull()
      .orEmpty()
  }

  private fun isAndroidShareSheetChromeText(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return normalized in setOf(
      "sharing text",
      "copy text",
      "no recommended people to share with",
      "google play services",
      "quick share",
      "aurora store",
      "vanadium",
      "f-droid",
      "add repository"
    )
  }

  private fun looksLikeExternalShareSurface(hierarchyXml: String): Boolean {
    val lower = hierarchyXml.lowercase()
    return lower.contains("share link") ||
      lower.contains("copy link") ||
      lower.contains("share via") ||
      lower.contains("quick share") ||
      lower.contains("send to nearby devices") ||
      lower.contains("selected files") ||
      lower.contains("ask the other person to swipe")
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

  private fun inferredComposerFocus(hierarchyXml: String): Pair<Int, Int>? {
    val chromeBounds = projectComposerChromeBounds(hierarchyXml)
    if (chromeBounds != null) {
      return PROJECT_COMPOSER_FALLBACK_X to chromeBounds.center().second
    }
    if (looksLikeProjectActionSurface(hierarchyXml)) {
      return PROJECT_COMPOSER_FALLBACK_X to PROJECT_COMPOSER_HIDDEN_KEYBOARD_Y
    }
    return null
  }

  private fun projectComposerChromeBounds(hierarchyXml: String): UiBounds? {
    val nodes = parseNodes(hierarchyXml)
    val dictation = nodes.firstOrNull { node ->
      node.contentDescription.equals("Dictation", ignoreCase = true) && node.bounds != null
    }?.bounds
    val voice = nodes.firstOrNull { node ->
      node.contentDescription.equals("Start a voice conversation", ignoreCase = true) && node.bounds != null
    }?.bounds
    return when {
      dictation != null && voice != null -> dictation.union(voice)
      dictation != null -> dictation
      voice != null -> voice
      else -> null
    }
  }

  private fun keyboardAppearsVisible(hierarchyXml: String): Boolean {
    val chromeY = projectComposerChromeBounds(hierarchyXml)?.center()?.second ?: Int.MAX_VALUE
    if (chromeY < KEYBOARD_VISIBLE_COMPOSER_MAX_Y) {
      return true
    }
    return parseNodes(hierarchyXml).any { node ->
      node.className == "android.widget.EditText" &&
        node.focused &&
        (node.bounds?.bottom ?: Int.MAX_VALUE) < KEYBOARD_VISIBLE_COMPOSER_MAX_Y
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
          clickable = attrs["clickable"] == "true",
          focused = attrs["focused"] == "true",
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
    val evidence = promptEvidence(prompt).take(PROMPT_VERIFY_CHARS)
    if (evidence.isBlank()) {
      return false
    }
    val escaped = xmlEscape(evidence)
    return promptEvidence(hierarchyXml).contains(evidence) || hierarchyXml.contains(escaped)
  }

  private fun promptEvidenceMatches(actual: String, expected: String): Boolean {
    val expectedEvidence = promptEvidence(expected).take(PROMPT_VERIFY_CHARS)
    if (expectedEvidence.isBlank()) {
      return false
    }
    return promptEvidence(actual).contains(expectedEvidence)
  }

  private fun promptEvidence(value: String): String {
    return value
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replace(Regex("[ \\t]+"), " ")
      .trim()
  }

  private fun looksLikeProjectComposer(hierarchyXml: String, project: String): Boolean {
    if (looksLikeNewProjectComposer(hierarchyXml)) {
      return true
    }
    val lowerProject = project.lowercase()
    val nodes = parseNodes(hierarchyXml)
    val hasProjectComposer = nodes.any { node ->
      node.contentDescription.contains("New chat in", ignoreCase = true) &&
        node.contentDescription.contains(project, ignoreCase = true)
    }
    if (hasProjectComposer) {
      return true
    }
    val hasProjectTitle = nodes.any { node ->
      (node.text.lowercase() == lowerProject || node.contentDescription.lowercase() == lowerProject) &&
        (node.bounds?.top ?: Int.MAX_VALUE) < PROJECT_TITLE_MAX_TOP
    }
    val hasComposer = findComposerBounds(hierarchyXml) != null
    return hasProjectTitle && hasComposer
  }

  private fun looksLikeNewProjectComposer(hierarchyXml: String): Boolean {
    val nodes = parseNodes(hierarchyXml)
    val hasNewChatLabel = nodes.any { node ->
      node.enabled &&
        (node.text.equals("New chat", ignoreCase = true) ||
          node.contentDescription.equals("New chat", ignoreCase = true) ||
          node.contentDescription.contains("New chat in", ignoreCase = true) ||
          node.hint.equals("New chat", ignoreCase = true))
    }
    return hasNewChatLabel && (findComposerBounds(hierarchyXml) != null || projectComposerChromeBounds(hierarchyXml) != null)
  }

  private fun classifyRootScene(hierarchyXml: String, project: String): ChatGPTRootScene {
    return when {
      hierarchyLooksLikeLoginScreen(hierarchyXml) -> ChatGPTRootScene.LOGIN
      looksLikeAndroidShareSheet(hierarchyXml) ||
        looksLikeExternalShareSurface(hierarchyXml) ||
        hierarchyXml.contains("Get updates on your tasks", ignoreCase = true) ||
        hierarchyXml.contains("Turn on notification", ignoreCase = true) -> ChatGPTRootScene.BLOCKING_OVERLAY
      isGenerating(hierarchyXml) -> ChatGPTRootScene.ACTIVE_GENERATION
      looksLikeDrawer(hierarchyXml) -> ChatGPTRootScene.DRAWER
      looksLikeProjectsList(hierarchyXml) -> ChatGPTRootScene.PROJECTS_LIST
      looksLikeNewProjectComposer(hierarchyXml) -> ChatGPTRootScene.PROJECT_NEW_CHAT_COMPOSER
      project.isNotBlank() && looksLikeProjectComposer(hierarchyXml, project) -> ChatGPTRootScene.SELECTED_PROJECT_SURFACE
      extractAnswerCandidate(hierarchyXml, "", emptySet()).isNotBlank() -> ChatGPTRootScene.COMPLETED_ANSWER
      else -> ChatGPTRootScene.UNKNOWN
    }
  }

  private fun looksLikeProjectActionSurface(hierarchyXml: String): Boolean {
    val nodes = parseNodes(hierarchyXml)
    val hasEditMenu = nodes.any { node ->
      node.enabled && node.contentDescription.equals("Edit Menu", ignoreCase = true)
    }
    return hasEditMenu && projectComposerChromeBounds(hierarchyXml) != null
  }

  private fun looksLikeProjectOverviewAfterProjectListTap(hierarchyXml: String): Boolean {
    if (!looksLikeProjectActionSurface(hierarchyXml)) {
      return false
    }
    if (looksLikeDrawer(hierarchyXml) || looksLikeProjectsList(hierarchyXml) || hierarchyLooksLikeLoginScreen(hierarchyXml)) {
      return false
    }
    return true
  }

  private fun hierarchyLooksLikeLoginScreen(hierarchyXml: String): Boolean {
    val lower = hierarchyXml.lowercase()
    return lower.contains("log in") ||
      lower.contains("sign up") ||
      lower.contains("continue with google") ||
      lower.contains("continue with apple")
  }

  private fun markProjectOverviewReady(project: String) {
    projectOverviewProject = project
    projectOverviewReadyAtMillis = System.currentTimeMillis()
  }

  private fun resetProjectOverviewReadiness() {
    projectOverviewProject = ""
    projectOverviewReadyAtMillis = 0L
  }

  private fun recentProjectOverviewReady(project: String): Boolean {
    val expectedProject = project.trim()
    if (expectedProject.isBlank() || !projectOverviewProject.equals(expectedProject, ignoreCase = true)) {
      return false
    }
    return System.currentTimeMillis() - projectOverviewReadyAtMillis <= PROJECT_OVERVIEW_READINESS_MILLIS
  }

  private fun looksLikeDrawer(hierarchyXml: String): Boolean {
    val texts = parseNodes(hierarchyXml).map { node -> normalizedNodeText(node).lowercase() }.toSet()
    return "library" in texts && "projects" in texts && "scheduled" in texts
  }

  private fun looksLikeProjectsList(hierarchyXml: String): Boolean {
    val texts = parseNodes(hierarchyXml).map { node -> normalizedNodeText(node).lowercase() }.toSet()
    return "projects" in texts && ("all" in texts || "created by you" in texts || "shared with you" in texts)
  }

  private fun extractAnswerCandidate(
    hierarchyXml: String,
    prompt: String,
    beforeTexts: Set<String>
  ): String {
    val promptClean = prompt.trim()
    return parseNodes(hierarchyXml)
      .asSequence()
      .map { node -> normalizedNodeText(node) }
      .filter { text -> text.isNotBlank() }
      .filterNot { text -> text == promptClean || text == "$promptClean " }
      .filterNot { text -> text in beforeTexts }
      .filterNot { text -> isChromeText(text) }
      .filterNot { text -> text.startsWith("Reply exactly:", ignoreCase = true) }
      .distinct()
      .joinToString("\n")
      .trim()
  }

  private fun normalizedNodeText(node: UiNode): String {
    return node.text.trim().ifBlank { node.contentDescription.trim() }
  }

  private fun isGenerating(hierarchyXml: String): Boolean {
    val lower = hierarchyXml.lowercase()
    return lower.contains("stop generating") ||
      lower.contains("stop response") ||
      lower.contains("cancel generation") ||
      lower.contains("streaming") ||
      parseNodes(hierarchyXml).any { node -> normalizedNodeText(node).equals("Stop", ignoreCase = true) }
  }

  private fun isChromeText(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return normalized in setOf(
      "chatgpt",
      "ask chatgpt",
      "reply to chatgpt",
      "new chat",
      "temporary chat",
      "send message",
      "sending",
      "stop",
      "dictation",
      "attachment",
      "start a voice conversation",
      "menu",
      "edit menu",
      "close",
      "search",
      "library",
      "projects",
      "scheduled",
      "apps",
      "remote",
      "more",
      "pinned",
      "recents",
      "chat",
      "account settings",
      "follow the world cup",
      "create an image",
      "look something up",
      "close sheet",
      "drag handle",
      "get updates on your tasks",
      "turn on notification",
      "not now"
    )
  }

  private fun promptForRootInput(prompt: String): String {
    return prompt
      .replace("\r\n", "\n")
      .replace('\r', '\n')
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
    fun union(other: UiBounds): UiBounds = UiBounds(
      left = minOf(left, other.left),
      top = minOf(top, other.top),
      right = maxOf(right, other.right),
      bottom = maxOf(bottom, other.bottom)
    )
  }

  private data class UiNode(
    val className: String,
    val text: String,
    val contentDescription: String,
    val hint: String,
    val enabled: Boolean,
    val clickable: Boolean,
    val focused: Boolean,
    val bounds: UiBounds?
  )

  private enum class ChatGPTRootScene {
    LOGIN,
    DRAWER,
    PROJECTS_LIST,
    SELECTED_PROJECT_SURFACE,
    PROJECT_NEW_CHAT_COMPOSER,
    ACTIVE_GENERATION,
    COMPLETED_ANSWER,
    BLOCKING_OVERLAY,
    UNKNOWN
  }

  private companion object {
    const val PROMPT_VERIFY_CHARS = 80
    const val PROJECT_TITLE_MAX_TOP = 420
    const val PROJECT_COMPOSER_FALLBACK_X = 420
    const val PROJECT_COMPOSER_HIDDEN_KEYBOARD_Y = 2_180
    const val PROJECT_SEND_FALLBACK_X = 990
    const val PROJECT_SEND_KEYBOARD_FALLBACK_Y = 1_430
    const val KEYBOARD_VISIBLE_COMPOSER_MAX_Y = 1_700
    const val COPY_FAST_RESPONSE_WAIT_MILLIS = 10_000L
    const val PROJECT_OVERVIEW_READINESS_MILLIS = 30_000L
    const val COPIED_ANSWER_VISIBLE_FRAGMENT_MIN_CHARS = 16
    const val COPIED_ANSWER_VISIBLE_FRAGMENT_CHARS = 80
    const val MIN_COMPOSER_DELETE_KEYS = 32
    const val MAX_COMPOSER_DELETE_KEYS = 280
    val SHARE_SHEET_PAYLOAD_TOP_RANGE = 1_200..1_950
    val NODE_REGEX = Regex("""<node\b[^>]*>""")
    val ATTR_REGEX = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
  }
}
