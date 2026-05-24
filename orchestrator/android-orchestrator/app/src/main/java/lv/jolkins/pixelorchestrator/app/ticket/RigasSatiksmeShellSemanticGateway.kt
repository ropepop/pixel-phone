package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock
import kotlinx.coroutines.delay
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSelector
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

internal class RigasSatiksmeShellSemanticGateway(
  private val rootExecutor: RootExecutor,
  private val launchApp: suspend () -> Boolean,
  private val recordEvent: (String, String) -> Unit = { _, _ -> }
) : RigasSatiksmeSemanticGateway {
  private var cachedNodes: List<PhoneAutomationVisibleNode> = emptyList()
  private var cachedNodesAtMillis: Long = 0L

  override suspend fun prepareAutomation(): Boolean {
    val result = rootExecutor.runScript(
      "command -v uiautomator >/dev/null 2>&1 && command -v input >/dev/null 2>&1",
      800.milliseconds
    )
    val ready = result.ok
    recordEvent(
      "rs_monthly_ticket_shell_automation_ready",
      "ready=$ready duration_ms=${result.durationMs}"
    )
    return ready
  }

  override suspend fun launchApp(): Boolean = launchApp.invoke()

  override suspend fun waitForForeground(): Boolean {
    val startedAt = SystemClock.elapsedRealtime()
    repeat(8) {
      val focused = rootExecutor.runScript(
        "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep -q '${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}'",
        350.milliseconds
      )
      if (focused.ok) {
        recordEvent(
          "rs_monthly_ticket_shell_foreground_ready",
          "source=dumpsys duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
        )
        return true
      }
      delay(80L)
    }
    val fallbackSnapshot = snapshot()
    val ready = fallbackSnapshot.isNotEmpty()
    recordEvent(
      "rs_monthly_ticket_shell_foreground_ready",
      "source=uiautomator ready=$ready duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
    )
    return ready
  }

  override suspend fun snapshot(): List<PhoneAutomationVisibleNode> {
    val startedAt = SystemClock.elapsedRealtime()
    val result = rootExecutor.runScript(
      """
      tmp="/data/local/tmp/rs-window-${'$'}${'$'}.xml"
      uiautomator dump "${'$'}tmp" >/dev/null 2>/dev/null &&
      cat "${'$'}tmp"
      rm -f "${'$'}tmp" >/dev/null 2>/dev/null || true
      """.trimIndent(),
      1_800.milliseconds
    )
    if (!result.ok) {
      recordEvent(
        "rs_monthly_ticket_shell_snapshot_failed",
        "duration_ms=${result.durationMs} output=${result.stdout.takeLast(120).replace('\n', ' ').replace('\r', ' ')}"
      )
      return emptyList()
    }
    val nodes = runCatching { parseUiAutomatorNodes(result.stdout) }
      .getOrElse { error ->
        recordEvent(
          "rs_monthly_ticket_shell_snapshot_failed",
          "parse_error=${error.message?.take(120)?.replace('\n', ' ')?.replace('\r', ' ')}"
        )
        emptyList()
      }
      .filter { it.resourceId.isNotBlank() || it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.className.isNotBlank() }
      .filter { it.className.isNotBlank() || it.contentDescription.isNotBlank() || it.text.isNotBlank() }
    recordEvent(
      "rs_monthly_ticket_shell_snapshot_ready",
      "nodes=${nodes.size} duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
    )
    cachedNodes = nodes
    cachedNodesAtMillis = SystemClock.elapsedRealtime()
    return nodes
  }

  override suspend fun click(
    selectors: List<PhoneAutomationSelector>,
    timeoutMillis: Long
  ): Boolean {
    val nodes = actionSnapshot()
    val target = nodes.firstOrNull { node ->
      node.enabled && selectors.any { it.matches(node.text, node.resourceId, node.contentDescription) }
    } ?: nodes.firstOrNull { node ->
      selectors.any { it.matches(node.text, node.resourceId, node.contentDescription) }
    } ?: return false
    val (x, y) = target.bounds.centerOrNull() ?: return false
    return inputTap(x, y, "rs_shell_semantic_click")
  }

  override suspend fun openFirstEditableInput(timeoutMillis: Long): Boolean {
    val nodes = actionSnapshot()
    val target = nodes.firstOrNull { it.editable && it.enabled }
      ?: nodes.firstOrNull {
        it.enabled && (
          it.className.contains("EditText", ignoreCase = true) ||
            it.text.contains("Control code", ignoreCase = true) ||
            it.contentDescription.contains("Control code", ignoreCase = true)
          )
      }
      ?: nodes.firstOrNull {
        it.enabled && (
          it.text.contains("Kods", ignoreCase = true) ||
            it.contentDescription.contains("Kods", ignoreCase = true)
          )
      }
      ?: return false
    val (x, y) = target.bounds.centerOrNull() ?: return false
    return inputTap(x, y, "rs_shell_open_editable")
  }

  override suspend fun setTextInFirstEditableInput(text: String, timeoutMillis: Long): Boolean {
    if (!text.all { it.isDigit() }) return false
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:rs_shell_set_text")
    return try {
      val result = rootExecutor.runScript(
        """
        input keyevent KEYCODE_MOVE_END KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL
        input text $text
        """.trimIndent(),
        timeoutMillis.coerceAtLeast(1L).milliseconds
      )
      recordEvent(
        "rs_monthly_ticket_shell_set_text",
        "ok=${result.ok} duration_ms=${result.durationMs}"
      )
      cachedNodes = emptyList()
      cachedNodesAtMillis = 0L
      result.ok
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:rs_shell_set_text:complete")
    }
  }

  override suspend fun performBack(): Boolean {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:rs_shell_back")
    return try {
      val result = rootExecutor.runScript("input keyevent KEYCODE_BACK", 600.milliseconds)
      recordEvent("rs_monthly_ticket_shell_back", "ok=${result.ok} duration_ms=${result.durationMs}")
      cachedNodes = emptyList()
      cachedNodesAtMillis = 0L
      result.ok
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:rs_shell_back:complete")
    }
  }

  private suspend fun inputTap(x: Int, y: Int, reason: String): Boolean {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      val result = rootExecutor.runScript("input tap $x $y", 700.milliseconds)
      recordEvent(
        "rs_monthly_ticket_shell_tap",
        "reason=$reason x=$x y=$y ok=${result.ok} duration_ms=${result.durationMs}"
      )
      if (result.ok) {
        cachedNodes = emptyList()
        cachedNodesAtMillis = 0L
        delay(80L)
      }
      result.ok
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  companion object {
    private const val ACTION_SNAPSHOT_CACHE_MILLIS = 650L

    fun parseUiAutomatorNodes(xml: String): List<PhoneAutomationVisibleNode> {
      if (xml.isBlank()) return emptyList()
      val document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))
      val parsedNodes = document.getElementsByTagName("node")
      val nodes = mutableListOf<PhoneAutomationVisibleNode>()
      for (index in 0 until parsedNodes.length) {
        val element = parsedNodes.item(index) as? Element ?: continue
        nodes += PhoneAutomationVisibleNode(
          text = element.attr("text"),
          resourceId = element.attr("resource-id"),
          contentDescription = element.attr("content-desc"),
          className = element.attr("class"),
          bounds = element.attr("bounds"),
          clickable = element.attr("clickable").equals("true", ignoreCase = true),
          enabled = element.attr("enabled").equals("true", ignoreCase = true),
          focused = element.attr("focused").equals("true", ignoreCase = true),
          editable = element.attr("class").contains("EditText", ignoreCase = true) ||
            element.attr("focusable").equals("true", ignoreCase = true) &&
            (
              element.attr("text").contains("Control code", ignoreCase = true) ||
                element.attr("content-desc").contains("Control code", ignoreCase = true)
              ),
          focusable = element.attr("focusable").equals("true", ignoreCase = true),
          hint = element.attr("hint")
        )
      }
      return nodes
    }

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    internal fun String.centerOrNull(): Pair<Int, Int>? {
      val match = BOUNDS_REGEX.matchEntire(trim()) ?: return null
      val left = match.groupValues[1].toDoubleOrNull() ?: return null
      val top = match.groupValues[2].toDoubleOrNull() ?: return null
      val right = match.groupValues[3].toDoubleOrNull() ?: return null
      val bottom = match.groupValues[4].toDoubleOrNull() ?: return null
      if (right <= left || bottom <= top) return null
      return Pair(((left + right) / 2.0).roundToInt(), ((top + bottom) / 2.0).roundToInt())
    }

    private val BOUNDS_REGEX = Regex("""\[(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)\]\[(\d+(?:\.\d+)?),(\d+(?:\.\d+)?)\]""")
  }

  private suspend fun actionSnapshot(): List<PhoneAutomationVisibleNode> {
    val ageMillis = SystemClock.elapsedRealtime() - cachedNodesAtMillis
    if (cachedNodes.isNotEmpty() && ageMillis in 0..ACTION_SNAPSHOT_CACHE_MILLIS) {
      recordEvent("rs_monthly_ticket_shell_snapshot_reused", "age_ms=$ageMillis nodes=${cachedNodes.size}")
      return cachedNodes
    }
    return snapshot()
  }
}
