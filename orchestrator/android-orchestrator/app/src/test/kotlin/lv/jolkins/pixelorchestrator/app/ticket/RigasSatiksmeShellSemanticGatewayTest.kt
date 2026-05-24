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
}
