package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketViviPageEnforcerTest {
  @Test
  fun leavesTicketDetailAlone() {
    val xml = ticketDetailXml()

    assertNull(TicketViviPageEnforcer.actionForHierarchy(xml))
    assertEquals(true, TicketViviPageEnforcer.isTicketDetail(xml))
  }

  @Test
  fun rejectsNonViviTicketDetailMarkers() {
    val xml = """
      <hierarchy>
        <node package="com.example" content-desc="KONTROLES KODS" bounds="[53,264][450,390]" />
        <node package="com.example" content-desc="ZONAS PV-ELB-20260423-0RJB2M" bounds="[68,434][190,486]" />
      </hierarchy>
    """.trimIndent()

    assertEquals(false, TicketViviPageEnforcer.isTicketDetail(xml))
  }

  @Test
  fun opensVisibleTicketCardFromTicketsList() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;23.04.2026 - 22.05.2026&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.actionForHierarchy(xml)

    assertEquals("open_ticket_card", action?.reason)
    assertEquals(540, action?.x)
    assertEquals(773, action?.y)
  }

  @Test
  fun opensTicketsTabWhenOnAnotherViviTab() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Maršruta plānošana" bounds="[200,300][900,390]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.actionForHierarchy(xml)

    assertEquals("open_tickets_tab", action?.reason)
    assertEquals(405, action?.x)
    assertEquals(2285, action?.y)
  }

  @Test
  fun opensTicketsTabFromLiveHomeTabShape() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Maršruta plānošana" bounds="[228,955][852,1052]" />
        <node package="com.pv.vivi" content-desc="Rādīt vairāk" clickable="true" bounds="[0,577][1080,703]" />
        <node package="com.pv.vivi" content-desc="home&#10;1. cilne no 4" clickable="true" selected="true" bounds="[0,2209][270,2361]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="false" bounds="[270,2209][540,2361]" />
        <node package="com.pv.vivi" content-desc="user&#10;3. cilne no 4" clickable="true" selected="false" bounds="[540,2209][810,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.actionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.OTHER_VIVI_TAB, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("open_tickets_tab", action?.reason)
    assertEquals(405, action?.x)
    assertEquals(2285, action?.y)
  }

  @Test
  fun detectsControlCodePopupAndItsInput() {
    val xml = controlCodePopupXml(inputFocused = true)

    assertTrue(TicketViviPageEnforcer.isControlCodePopup(xml))
    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertTrue(TicketViviPageEnforcer.isControlCodeInputFocused(xml))

    val input = TicketViviPageEnforcer.controlCodeInputActionForHierarchy(xml)

    assertEquals("focus_control_code_input", input?.reason)
    assertEquals(540, input?.x)
    assertEquals(805, input?.y)
  }

  @Test
  fun detectsControlCodeCloseButtonAndRejectsOutsideTaps() {
    val xml = controlCodePopupXml(inputFocused = false)

    val close = TicketViviPageEnforcer.controlCodeCloseActionForHierarchy(xml)

    assertEquals("close_control_code_popup", close?.reason)
    assertEquals(984, close?.x)
    assertEquals(468, close?.y)
    assertTrue(TicketViviPageEnforcer.isControlCodeCloseTap(xml, 984, 468))
    assertFalse(TicketViviPageEnforcer.isControlCodeCloseTap(xml, 540, 805))
  }

  @Test
  fun detectsControlCodeResultCloseWithoutTreatingItAsTicketDetail() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" text="Kontroles kods" bounds="[124,548][700,604]" />
        <node package="com.pv.vivi" text="253986" bounds="[360,1180][720,1260]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[936,420][1032,516]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertEquals(984, close?.x)
    assertEquals(468, close?.y)
  }

  @Test
  fun detectsControlCodeResultWithoutVisibleTitle() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" text="25698" bounds="[318,1130][760,1240]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[910,1086][1036,1212]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertEquals(973, close?.x)
    assertEquals(1149, close?.y)
  }

  @Test
  fun normalTicketDetailWithDatesAndPriceIsNotControlCodeResult() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="23.04.2026 - 22.05.2026" bounds="[120,1580][700,1650]" />
        <node package="com.pv.vivi" text="46,00€" bounds="[730,1580][960,1650]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[936,420][1032,516]" />
      </hierarchy>
    """.trimIndent()

    assertTrue(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.TICKET_DETAIL, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertNull(TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml))
  }

  @Test
  fun rejectsControlCodeActionsOutsidePopup() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
      </hierarchy>
    """.trimIndent()

    assertFalse(TicketViviPageEnforcer.isControlCodePopup(xml))
    assertNull(TicketViviPageEnforcer.controlCodeInputActionForHierarchy(xml))
    assertNull(TicketViviPageEnforcer.controlCodeCloseActionForHierarchy(xml))
    assertNull(TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml))
    assertFalse(TicketViviPageEnforcer.isControlCodeCloseTap(xml, 984, 468))
  }

  @Test
  fun focusableControlCodeButtonIsStillTicketDetail() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="KONTROLES KODS" clickable="true" focusable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" focusable="true" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" focusable="true" bounds="[119,1329][961,1423]" />
      </hierarchy>
    """.trimIndent()

    assertTrue(TicketViviPageEnforcer.isTicketDetail(xml))
    assertFalse(TicketViviPageEnforcer.isControlCodePopup(xml))
    assertEquals(TicketViviRecoveryState.TICKET_DETAIL, TicketViviPageEnforcer.classifyForRecovery(xml))
  }

  @Test
  fun classifiesAccessibilityNodesAsTicketDetail() {
    val xml = TicketViviPageEnforcer.hierarchyForVisibleNodes(
      listOf(
        PhoneAutomationVisibleNode(
          text = "",
          resourceId = "",
          contentDescription = "KONTROLES KODS",
          className = "android.widget.Button",
          bounds = "[53,264][450,390]",
          clickable = true,
          enabled = true,
          focused = false
        ),
        PhoneAutomationVisibleNode(
          text = "",
          resourceId = "",
          contentDescription = "ZONAS",
          bounds = "[68,434][190,486]",
          enabled = true
        ),
        PhoneAutomationVisibleNode(
          text = "",
          resourceId = "",
          contentDescription = "PV-ELB-20260423-0RJB2M",
          bounds = "[119,1329][961,1423]",
          enabled = true
        )
      )
    )

    assertEquals(TicketViviRecoveryState.TICKET_DETAIL, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("PV-ELB-20260423-0RJB2M", TicketViviPageEnforcer.ticketIdForHierarchy(xml))
  }

  @Test
  fun resetFlowClosesPopupBeforeOpeningTicketCard() {
    val popupAction = TicketViviPageEnforcer.resetActionForHierarchy(controlCodePopupXml(inputFocused = false))
    assertEquals("close_control_code_popup", popupAction?.reason)

    val listAction = TicketViviPageEnforcer.resetActionForHierarchy(
      """
        <hierarchy>
          <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
          <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;23.04.2026 - 22.05.2026&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
          <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
        </hierarchy>
      """.trimIndent()
    )
    assertEquals("open_ticket_card", listAction?.reason)

    val detailAction = TicketViviPageEnforcer.resetActionForHierarchy(
      """
        <hierarchy>
          <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
          <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
          <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        </hierarchy>
      """.trimIndent()
    )
    assertNull(detailAction)
  }

  @Test
  fun classifiesRecoveryStatesAndActions() {
    assertEquals(TicketViviRecoveryState.TICKET_DETAIL, TicketViviPageEnforcer.classifyForRecovery(ticketDetailXml()))
    assertEquals(
      TicketViviRecoveryState.CONTROL_CODE_POPUP,
      TicketViviPageEnforcer.classifyForRecovery(controlCodePopupXml(inputFocused = false))
    )
    assertEquals(
      "close_control_code_popup",
      TicketViviPageEnforcer.recoveryActionForHierarchy(controlCodePopupXml(inputFocused = false))?.reason
    )
    val resultXml = """
      <hierarchy>
        <node package="com.pv.vivi" text="25698" bounds="[318,1130][760,1240]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[910,1086][1036,1212]" />
      </hierarchy>
    """.trimIndent()
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(resultXml))
    assertEquals("close_control_code_result", TicketViviPageEnforcer.recoveryActionForHierarchy(resultXml)?.reason)
    val listXml = ticketsListXml()
    assertEquals(TicketViviRecoveryState.TICKET_LIST_WITH_CARD, TicketViviPageEnforcer.classifyForRecovery(listXml))
    assertEquals("open_ticket_card", TicketViviPageEnforcer.recoveryActionForHierarchy(listXml)?.reason)
    val otherTabXml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Maršruta plānošana" bounds="[200,300][900,390]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()
    assertEquals(TicketViviRecoveryState.OTHER_VIVI_TAB, TicketViviPageEnforcer.classifyForRecovery(otherTabXml))
    assertEquals("open_tickets_tab", TicketViviPageEnforcer.recoveryActionForHierarchy(otherTabXml)?.reason)
    assertEquals(TicketViviRecoveryState.OUTSIDE_VIVI, TicketViviPageEnforcer.classifyForRecovery("<hierarchy />"))
    assertEquals(TicketViviRecoveryState.BLANK, TicketViviPageEnforcer.classifyForRecovery(""))
  }

  @Test
  fun classifiesEmptyCartAsBackRecoveryState() {
    val cartXml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Atpakaļ" clickable="true" bounds="[36,88][132,184]" />
        <node package="com.pv.vivi" content-desc="0" clickable="true" bounds="[909,166][1080,285]" />
        <node package="com.pv.vivi" content-desc="Grozs" bounds="[450,112][630,170]" />
        <node package="com.pv.vivi" content-desc="Grozs ir tukšs" bounds="[120,880][960,960]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(cartXml)

    assertEquals(TicketViviRecoveryState.CART_OR_CHECKOUT, TicketViviPageEnforcer.classifyForRecovery(cartXml))
    assertEquals("leave_cart_or_checkout", action?.reason)
    assertEquals(84, action?.x)
    assertEquals(136, action?.y)
  }

  @Test
  fun switchesToTimeTicketTabWhenTicketListIsEmpty() {
    val emptyTicketsXml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="VIENREIZĒJĀS BIĻETES" clickable="true" bounds="[63,349][540,475]" />
        <node package="com.pv.vivi" content-desc="LAIKA BIĻETES" clickable="true" bounds="[540,349][1017,475]" />
        <node package="com.pv.vivi" content-desc="Biļetes nav atrastas" bounds="[185,1289][895,1402]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(emptyTicketsXml)

    assertEquals(TicketViviRecoveryState.TICKET_LIST_EMPTY, TicketViviPageEnforcer.classifyForRecovery(emptyTicketsXml))
    assertEquals("open_time_ticket_tab", action?.reason)
    assertEquals(778, action?.x)
    assertEquals(412, action?.y)
  }

  @Test
  fun classifiesBlockersAndSettingsForRecovery() {
    val blockerXml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="Pasažieri ar Goda ģimenes apliecību varēs braukt bez maksas." bounds="[190,1090][890,1320]" />
        <node package="com.pv.vivi" text="x" clickable="true" bounds="[900,965][980,1045]" />
      </hierarchy>
    """.trimIndent()
    assertEquals(TicketViviRecoveryState.DISMISSIBLE_BLOCKER, TicketViviPageEnforcer.classifyForRecovery(blockerXml))
    assertEquals("dismiss_blocking_popup", TicketViviPageEnforcer.recoveryActionForHierarchy(blockerXml)?.reason)

    val settingsXml = """
      <hierarchy>
        <node package="com.pv.vivi" text="Iestatījumi" bounds="[120,260][640,340]" />
        <node package="com.pv.vivi" text="Profils" clickable="true" bounds="[120,520][640,610]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()
    assertEquals(TicketViviRecoveryState.SETTINGS_OR_PROFILE, TicketViviPageEnforcer.classifyForRecovery(settingsXml))
    assertNull(TicketViviPageEnforcer.recoveryActionForHierarchy(settingsXml))
  }

  @Test
  fun allowsKontrolesKodsButtonOnTicketDetail() {
    val xml = ticketDetailXml()

    assertTrue(TicketViviPageEnforcer.isTicketDetail(xml))
    assertTrue(TicketViviPageEnforcer.isControlCodeButtonTap(xml, 55, 265))
    assertTrue(TicketViviPageEnforcer.isControlCodeButtonTap(xml, 250, 327))
    assertTrue(TicketViviPageEnforcer.isControlCodeButtonTap(xml, 448, 388))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 250, 327))
  }

  @Test
  fun blocksViviSettingsAndProfileButNotTicketContent() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Iestatījumi" clickable="true" bounds="[55,276][150,372]" />
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,420][450,546]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,620][190,672]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" content-desc="Cart&#10;0" clickable="true" bounds="[890,276][1030,390]" />
        <node package="com.pv.vivi" content-desc="Home&#10;1. cilne no 4" clickable="true" bounds="[0,2209][270,2361]" />
        <node package="com.pv.vivi" content-desc="Profile&#10;3. cilne no 4" clickable="true" bounds="[540,2209][810,2361]" />
      </hierarchy>
    """.trimIndent()

    assertTrue(TicketViviPageEnforcer.isForbiddenViviTap(xml, 100, 320))
    assertTrue(TicketViviPageEnforcer.isForbiddenViviTap(xml, 675, 2285))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 960, 330))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 135, 2285))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 250, 483))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 540, 1380))
  }

  @Test
  fun blocksUnlabeledSettingsAndProfileFallbackAreas() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" clickable="true" bounds="[55,276][150,372]" />
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,420][450,546]" />
        <node package="com.pv.vivi" clickable="true" bounds="[540,2209][810,2361]" />
        <node package="com.pv.vivi" clickable="true" bounds="[810,2209][1080,2361]" />
      </hierarchy>
    """.trimIndent()

    assertTrue(TicketViviPageEnforcer.isForbiddenViviTap(xml, 100, 320))
    assertTrue(TicketViviPageEnforcer.isForbiddenViviTap(xml, 675, 2285))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 250, 483))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 945, 2285))
  }

  @Test
  fun allowsControlCodeButtonWhenUnlabeledSettingsFallbackOverlapsIt() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" clickable="true" bounds="[55,276][150,372]" />
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
      </hierarchy>
    """.trimIndent()

    assertTrue(TicketViviPageEnforcer.isControlCodeButtonTap(xml, 100, 320))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 100, 320))
  }

  @Test
  fun allowsPromoPopupDuringActiveViviUse() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="Pasažieri ar “Goda ģimenes” apliecību 4. maijā varēs braukt bez maksas, izmantojot viena brauciena biļetes ar 100% atlaidi." bounds="[190,1090][890,1320]" />
        <node package="com.pv.vivi" text="Vairāk →" clickable="true" bounds="[410,1320][670,1390]" />
        <node package="com.pv.vivi" text="x" clickable="true" bounds="[900,965][980,1045]" />
      </hierarchy>
    """.trimIndent()

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 940, 1005))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 540, 1355))
  }

  @Test
  fun topBannerMoreTextAloneDoesNotMakeTicketADismissiblePopup() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="Rādīt vairāk" clickable="true" bounds="[600,620][900,710]" />
        <node package="com.pv.vivi" content-desc="Cart&#10;0" clickable="true" bounds="[890,276][1030,390]" />
      </hierarchy>
    """.trimIndent()

    assertTrue(TicketViviPageEnforcer.isTicketDetail(xml))
    assertNull(TicketViviPageEnforcer.dismissibleBlockerActionForHierarchy(xml))
  }

  private fun controlCodePopupXml(inputFocused: Boolean): String {
    return """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="Kontroles kods" bounds="[124,548][700,604]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="" editable="true" focusable="true" focused="$inputFocused" bounds="[120,760][960,850]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[936,420][1032,516]" />
      </hierarchy>
    """.trimIndent()
  }

  private fun ticketsListXml(): String {
    return """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;23.04.2026 - 22.05.2026&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()
  }

  private fun ticketDetailXml(): String {
    return """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
      </hierarchy>
    """.trimIndent()
  }
}
