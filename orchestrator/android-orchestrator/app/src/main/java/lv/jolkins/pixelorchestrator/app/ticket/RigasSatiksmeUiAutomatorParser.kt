package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** The only retained part of the retired shell-semantic gateway: parsing bounded root proofs. */
internal object RigasSatiksmeUiAutomatorParser {
  fun parse(xml: String): List<PhoneAutomationVisibleNode> {
    if (xml.isBlank()) return emptyList()
    require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) {
      "UI Automator XML must not contain declarations or entities"
    }
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
}
