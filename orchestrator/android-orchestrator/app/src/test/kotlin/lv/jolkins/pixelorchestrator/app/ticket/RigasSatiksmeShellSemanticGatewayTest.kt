package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RigasSatiksmeShellSemanticGatewayTest {
  @Test
  fun parsesUiAutomatorControlTicketNodesFromRsApp() {
    val nodes = RigasSatiksmeShellSemanticGateway.parseUiAutomatorNodes(
      """
      <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
      <hierarchy rotation="0">
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="TICKET FOR CONTROL" clickable="false" enabled="true" focusable="true" focused="false" bounds="[322,218][758,286]" />
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="qr code" clickable="false" enabled="true" focusable="true" focused="false" bounds="[219,414][861,1056]" />
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="1 MONTH" clickable="false" enabled="true" focusable="true" focused="false" bounds="[520,1096][672,1148]" />
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="68803" clickable="false" enabled="true" focusable="true" focused="false" bounds="[439,1542][641,1655]" />
      </hierarchy>
      """.trimIndent()
    )

    assertEquals(
      RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING,
      RigasSatiksmeSemanticDriver.classify(nodes, "68803")
    )
    assertEquals("[322,218][758,286]", nodes.first().bounds)
  }

  @Test
  fun parsesTripRegisteredModalAndOkButton() {
    val nodes = RigasSatiksmeShellSemanticGateway.parseUiAutomatorNodes(
      """
      <hierarchy rotation="0">
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="Trip is registered" clickable="false" enabled="true" focusable="true" focused="false" bounds="[0,152][1080,2361]" />
        <node text="" resource-id="" class="android.widget.Button" package="com.flutter.rspassenger" content-desc="Ok" clickable="true" enabled="true" focusable="true" focused="false" bounds="[253,1284][827,1410]" />
      </hierarchy>
      """.trimIndent()
    )

    assertEquals(
      RigasSatiksmeSemanticState.TRIP_REGISTERED,
      RigasSatiksmeSemanticDriver.classify(nodes, "58011")
    )
    assertTrue(nodes.any { it.contentDescription == "Ok" && it.clickable })
  }

  @Test
  fun classifiesLatvianMonthlyControlTicketWithRequestedCode() {
    val nodes = RigasSatiksmeShellSemanticGateway.parseUiAutomatorNodes(
      """
      <hierarchy rotation="0">
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="KONTROLES KODS" clickable="false" enabled="true" focusable="true" focused="false" bounds="[54,250][454,338]" />
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="qr code" clickable="false" enabled="true" focusable="true" focused="false" bounds="[190,532][890,1232]" />
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="30 dienu biļete" clickable="false" enabled="true" focusable="true" focused="false" bounds="[347,1533][733,1601]" />
        <node text="" resource-id="" class="android.view.View" package="com.flutter.rspassenger" content-desc="68803" clickable="false" enabled="true" focusable="true" focused="false" bounds="[425,1400][655,1480]" />
      </hierarchy>
      """.trimIndent()
    )

    assertEquals(
      RigasSatiksmeSemanticState.TICKET_CONTROL_MATCHING,
      RigasSatiksmeSemanticDriver.classify(nodes, "68803")
    )
  }

  @Test
  fun classifiesLatvianRsEntryFlowStates() {
    assertEquals(
      RigasSatiksmeSemanticState.REGISTER_TRIP_READY,
      RigasSatiksmeSemanticDriver.classify(
        visibleNodes("30 dienu biļete", "Reģistrēt braucienu"),
        "58011"
      )
    )
    assertEquals(
      RigasSatiksmeSemanticState.MANUAL_CODE_BUTTON_READY,
      RigasSatiksmeSemanticDriver.classify(
        visibleNodes("Ievadīt kodu manuāli"),
        "58011"
      )
    )
    assertEquals(
      RigasSatiksmeSemanticState.MANUAL_CODE_ENTRY,
      RigasSatiksmeSemanticDriver.classify(
        visibleNodes("Ievadi kontroles kodu", "Atcelt", "OK"),
        "58011"
      )
    )
  }

  @Test
  fun classifiesLatvianGeneratedTicketWithoutMatchingCodeAsStale() {
    val nodes = visibleNodes("KONTROLES KODS", "qr code", "30 dienu biļete", "55555")

    assertEquals(
      RigasSatiksmeSemanticState.TICKET_CONTROL_STALE,
      RigasSatiksmeSemanticDriver.classify(nodes, "68803")
    )
  }

  private fun visibleNodes(vararg labels: String) =
    labels.map { label ->
      lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode(
        text = label,
        resourceId = "",
        contentDescription = label
      )
    }
}
