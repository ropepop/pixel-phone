package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TicketViviPageEnforcerTest {
  @Test
  fun leavesTicketDetailAlone() {
    val xml = ticketDetailXml()

    assertNull(TicketViviPageEnforcer.actionForHierarchy(xml))
    assertEquals(true, TicketViviPageEnforcer.isTicketDetail(xml))
  }

  @Test
  fun rawTicketWithAztecAndNumericDateIsStillTicketDetail() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" class="android.widget.Button" clickable="true" bounds="[880,260][1014,394]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" class="android.widget.ImageView" bounds="[212,646][868,1302]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" content-desc="23.04.2026 - 22.05.2026" bounds="[66,1846][592,1914]" />
      </hierarchy>
    """.trimIndent()

    assertEquals(TicketViviRecoveryState.TICKET_DETAIL, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertTrue(TicketViviPageEnforcer.isTicketDetail(xml))
    assertTrue(TicketViviPageEnforcer.hasTicketCodeGraphicForHierarchy(xml))
    assertNull(TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml))
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
    val currentRange = currentTicketDateRange()
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$currentRange&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.actionForHierarchy(xml)

    assertEquals("open_fresh_time_ticket_card", action?.reason)
    assertEquals(540, action?.x)
    assertEquals(773, action?.y)
  }

  @Test
  fun summarizesTicketCardSelectionForOperationalEvents() {
    val currentRange = currentTicketDateRange()
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$currentRange&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val summary = TicketViviPageEnforcer.ticketCardSelectionSummaryForHierarchy(xml)

    assertTrue(summary.contains("card_count=1"))
    assertTrue(summary.contains("priority=open_fresh_time_ticket_card"))
    assertTrue(summary.contains("label=Olaine"))
    assertFalse(summary.contains("&#10;"))
  }

  @Test
  fun detectsViviLoginScreenAndTargetsLoginControls() {
    val xml = loginScreenXml()

    val surface = TicketViviPageEnforcer.loginSurfaceForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.LOGIN_REQUIRED, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("user@example.test", surface?.visibleEmail)
    assertEquals("focus_vivi_login_email", surface?.email?.reason)
    assertEquals(612, surface?.email?.x)
    assertEquals(916, surface?.email?.y)
    assertEquals("focus_vivi_login_password", surface?.password?.reason)
    assertEquals(548, surface?.password?.x)
    assertEquals(1108, surface?.password?.y)
    assertEquals("submit_vivi_login", surface?.submit?.reason)
    assertEquals(540, surface?.submit?.x)
    assertEquals(1310, surface?.submit?.y)
  }

  @Test
  fun loginScreenDoesNotUseGenericRecoveryTap() {
    val xml = loginScreenXml()

    assertEquals(TicketViviRecoveryState.LOGIN_REQUIRED, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertNull(TicketViviPageEnforcer.actionForHierarchy(xml))
    assertNull(TicketViviPageEnforcer.recoveryActionForHierarchy(xml))
  }

  @Test
  fun detectsKeyboardShiftedLoginScreenAndTargetsShiftedSubmit() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.widget.EditText" text="user@example.test" clickable="true" editable="true" focusable="true" focused="false" bounds="[223,524][1001,603]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="" hint="Parole" clickable="true" editable="true" password="true" focusable="true" focused="true" bounds="[223,716][873,794]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="IEIET" clickable="false" enabled="false" bounds="[79,849][1001,1065]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="Aizmirsu paroli" clickable="true" bounds="[384,1065][696,1191]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="Reģistrēties" clickable="true" bounds="[410,1191][670,1317]" />
        <node package="com.android.inputmethod.latin" class="android.inputmethodservice.Keyboard" bounds="[0,1342][1080,2424]" />
      </hierarchy>
    """.trimIndent()

    val surface = TicketViviPageEnforcer.loginSurfaceForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.LOGIN_REQUIRED, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("submit_vivi_login", surface?.submit?.reason)
    assertEquals(540, surface?.submit?.x)
    assertEquals(957, surface?.submit?.y)
  }

  @Test
  fun postLoginProfileCompletionRequiresAttentionInsteadOfGenericRecovery() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="1" bounds="[205,625][229,676]" />
        <node package="com.pv.vivi" content-desc="2" bounds="[525,625][555,676]" />
        <node package="com.pv.vivi" content-desc="3" bounds="[845,625][875,676]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="" hint="E-pasts" clickable="true" enabled="true" focusable="true" bounds="[223,1040][1001,1119]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="+371" clickable="true" enabled="true" focusable="true" bounds="[223,1262][1001,1341]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="TURPINĀT" clickable="false" enabled="false" bounds="[0,2230][1080,2424]" />
      </hierarchy>
    """.trimIndent()

    assertEquals(TicketViviRecoveryState.AUTH_ATTENTION_REQUIRED, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertNull(TicketViviPageEnforcer.actionForHierarchy(xml))
    assertNull(TicketViviPageEnforcer.recoveryActionForHierarchy(xml))
  }

  @Test
  fun postLoginDeviceLinkedDialogRequiresAttentionInsteadOfGenericRecovery() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Tavs Vivi konts ir savienots ar citu ierīci/ lietotnes versiju" bounds="[168,1008][912,1113]" />
        <node package="com.pv.vivi" content-desc="Lietotnes ID nesakrīt ar iepriekšējo, kas bija piesaistīts jūsu Vivi kontam. Lūdzu, sazinies ar klientu apkalpošanas centru pa telefonu 8760 (pieejams 24/7) vai raksti uz vilciens@info.vivi.lv" bounds="[184,1140][896,1389]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" enabled="true" bounds="[781,1415][949,1541]" />
      </hierarchy>
    """.trimIndent()

    assertEquals(TicketViviRecoveryState.AUTH_ATTENTION_REQUIRED, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertNull(TicketViviPageEnforcer.actionForHierarchy(xml))
    assertNull(TicketViviPageEnforcer.recoveryActionForHierarchy(xml))
  }

  @Test
  fun choosesCurrentTimeTicketWithLatestEndDateInsteadOfFirstExpiredCard() {
    val today = LocalDate.now()
    val xml = ticketListWithExpiredAndCurrentCardsXml(today)

    val action = TicketViviPageEnforcer.bestTicketCardActionForHierarchy(
      xml,
      today = today
    )
    val recoveryAction = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.TICKET_LIST_WITH_CARD, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("open_fresh_time_ticket_card", action?.reason)
    assertEquals(540, action?.x)
    assertEquals(1466, action?.y)
    assertEquals("open_fresh_time_ticket_card", recoveryAction?.reason)
    assertEquals(540, recoveryAction?.x)
    assertEquals(1466, recoveryAction?.y)
  }

  @Test
  fun choosesSoonestUpcomingTimeTicketWhenNoCurrentTicketExists() {
    val today = LocalDate.now()
    val xml = ticketListWithOnlyFutureCardsXml(today)

    val action = TicketViviPageEnforcer.bestTicketCardActionForHierarchy(
      xml,
      today = today
    )

    assertEquals(TicketViviRecoveryState.TICKET_LIST_WITH_CARD, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("open_upcoming_time_ticket_card", action?.reason)
    assertEquals(540, action?.x)
    assertEquals(1092, action?.y)
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
  fun routeHomeAnnouncementDoesNotLookLikeDismissibleBlockerOrCart() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="0" clickable="true" bounds="[941,169][1064,292]" />
        <node package="com.pv.vivi" content-desc="Pasažieriem jāņem vērā aktuālie vilcienu kustības saraksti" bounds="[0,577][1080,703]" />
        <node package="com.pv.vivi" content-desc="Maršruta plānošana" bounds="[228,955][852,1052]" />
        <node package="com.pv.vivi" content-desc="MEKLĒT" clickable="true" bounds="[79,2040][1001,2197]" />
        <node package="com.pv.vivi" content-desc="home&#10;1. cilne no 4" clickable="true" selected="true" bounds="[0,2209][270,2361]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="false" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()

    val recoveryAction = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)
    val genericAction = TicketViviPageEnforcer.actionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.OTHER_VIVI_TAB, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("open_tickets_tab", recoveryAction?.reason)
    assertEquals(405, recoveryAction?.x)
    assertEquals(2285, recoveryAction?.y)
    assertEquals("open_tickets_tab", genericAction?.reason)
    assertEquals(405, genericAction?.x)
    assertEquals(2285, genericAction?.y)
  }

  @Test
  fun recoversRouteHomeEvenWhenTicketsTabHasNoAccessibleLabel() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Maršruta plānošana" bounds="[228,955][852,1052]" />
        <node package="com.pv.vivi" content-desc="MEKLĒT" clickable="true" bounds="[79,2040][1001,2197]" />
        <node package="com.pv.vivi" content-desc="home&#10;1. cilne no 4" clickable="true" selected="true" bounds="[0,2209][270,2361]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.UNKNOWN_VIVI, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("open_tickets_tab_route_fallback", action?.reason)
    assertEquals(405, action?.x)
    assertEquals(2285, action?.y)
  }

  @Test
  fun recognizesLiveLatvianSettingsScreenForBackRecovery() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="LIETOTNES VALODA" bounds="[79,530][429,592]" />
        <node package="com.pv.vivi" content-desc="Latviešu" bounds="[705,530][881,592]" />
        <node package="com.pv.vivi" content-desc="PAZIŅOJUMI" bounds="[79,761][306,823]" />
        <node package="com.pv.vivi" content-desc="LIETOTNES VERSIJA" bounds="[79,1221][477,1283]" />
        <node package="com.pv.vivi" content-desc="NOTEIKUMI" clickable="true" bounds="[79,1677][287,1739]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.SETTINGS_OR_PROFILE, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("leave_settings_or_profile", action?.reason)
    assertEquals(-1, action?.x)
    assertEquals(-1, action?.y)
  }

  @Test
  fun cartRecoveryUsesVisibleTopRightCloseFallbackWhenHierarchyBackButtonIsStale() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="Atpakaļ" clickable="true" bounds="[11,163][137,289]" />
        <node package="com.pv.vivi" content-desc="0" bounds="[575,204][610,250]" />
        <node package="com.pv.vivi" content-desc="Grozs ir tukšs" bounds="[336,1314][744,1409]" />
      </hierarchy>
    """.trimIndent()

    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.CART_OR_CHECKOUT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("leave_cart_or_checkout", action?.reason)
    assertEquals(1016, action?.x)
    assertEquals(226, action?.y)
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
  fun detectsLiveControlCodePopupWithCancelButton() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" bounds="[0,0][1080,2424]">
          <node package="com.pv.vivi" class="android.view.View" bounds="[173,1033][908,1480]">
            <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" focusable="true" focused="false" bounds="[236,1096][845,1149]" />
            <node package="com.pv.vivi" class="android.widget.EditText" clickable="true" focusable="true" focused="false" hint="kontroles kods" bounds="[251,1175][829,1301]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="Atcelt" clickable="true" focusable="true" focused="false" bounds="[464,1327][687,1453]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" focusable="true" focused="false" bounds="[713,1327][881,1453]" />
          </node>
        </node>
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertTrue(TicketViviPageEnforcer.isControlCodePopup(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_POPUP, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_popup", close?.reason)
    assertEquals(575, close?.x)
    assertEquals(1390, close?.y)
  }

  @Test
  fun popupIsReadyOnlyWhenInputAndSubmitAreBothPresent() {
    val missingSubmitXml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" bounds="[236,1096][845,1149]" />
        <node package="com.pv.vivi" class="android.widget.EditText" clickable="true" focusable="true" focused="false" hint="kontroles kods" bounds="[251,1175][829,1301]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="Atcelt" clickable="true" bounds="[464,1327][687,1453]" />
      </hierarchy>
    """.trimIndent()
    val missingInputXml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" bounds="[236,1096][845,1149]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" bounds="[713,1327][881,1453]" />
      </hierarchy>
    """.trimIndent()

    assertFalse(TicketViviPageEnforcer.isControlCodePopup(missingSubmitXml))
    assertNull(TicketViviPageEnforcer.controlCodeInputActionForHierarchy(missingSubmitXml))
    assertNull(TicketViviPageEnforcer.controlCodeSubmitActionForHierarchy(missingInputXml))
    assertFalse(TicketViviPageEnforcer.isControlCodePopup(missingInputXml))
  }

  @Test
  fun readsControlCodeInputValueWhenKeyboardHidesSubmit() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" bounds="[236,1096][845,1149]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="12" clickable="true" editable="true" focusable="true" focused="true" hint="kontroles kods" bounds="[251,1175][829,1301]" />
        <node package="com.android.inputmethod.latin" class="android.inputmethodservice.Keyboard" bounds="[0,1500][1080,2424]" />
      </hierarchy>
    """.trimIndent()

    assertFalse(TicketViviPageEnforcer.isControlCodePopup(xml))
    assertTrue(TicketViviPageEnforcer.hasControlCodeInputForHierarchy(xml))
    assertEquals("12", TicketViviPageEnforcer.controlCodeInputValueLooseForHierarchy(xml))
  }

  @Test
  fun detectsControlCodeInputFromHintAndPromptPosition() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" bounds="[236,1096][845,1149]" />
        <node package="com.pv.vivi" class="android.view.View" clickable="true" focusable="true" focused="false" hint="Kontroles kods" bounds="[251,1175][829,1301]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" bounds="[713,1327][881,1453]" />
      </hierarchy>
    """.trimIndent()

    val input = TicketViviPageEnforcer.controlCodeInputActionForHierarchy(xml)
    val submit = TicketViviPageEnforcer.controlCodeSubmitActionForHierarchy(xml)

    assertTrue(TicketViviPageEnforcer.isControlCodePopup(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_POPUP, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("focus_control_code_input", input?.reason)
    assertEquals(540, input?.x)
    assertEquals(1238, input?.y)
    assertEquals("submit_control_code_popup", submit?.reason)
  }

  @Test
  fun detectsLiveControlCodePopupSubmitButton() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" bounds="[0,0][1080,2424]">
          <node package="com.pv.vivi" class="android.view.View" bounds="[173,1033][908,1480]">
            <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" focusable="true" focused="false" bounds="[236,1096][845,1149]" />
            <node package="com.pv.vivi" class="android.widget.EditText" clickable="true" focusable="true" focused="true" hint="kontroles kods" bounds="[251,1175][829,1301]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="Atcelt" clickable="true" focusable="true" focused="false" bounds="[464,1327][687,1453]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" focusable="true" focused="false" bounds="[713,1327][881,1453]" />
          </node>
        </node>
      </hierarchy>
    """.trimIndent()

    val submit = TicketViviPageEnforcer.controlCodeSubmitActionForHierarchy(xml)

    assertEquals("submit_control_code_popup", submit?.reason)
    assertEquals(797, submit?.x)
    assertEquals(1390, submit?.y)
  }

  @Test
  fun prefersEditTextOverFocusablePromptForControlCodeInput() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" class="android.view.View" bounds="[0,0][1080,2424]">
          <node package="com.pv.vivi" class="android.view.View" bounds="[173,1033][908,1480]">
            <node package="com.pv.vivi" class="android.view.View" content-desc="Ievadi kontroles kodu" focusable="true" focused="false" bounds="[236,1096][845,1149]" />
            <node package="com.pv.vivi" class="android.widget.EditText" clickable="true" focusable="true" focused="true" hint="kontroles kods" bounds="[251,1175][829,1301]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="Atcelt" clickable="true" bounds="[464,1327][687,1453]" />
            <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" bounds="[713,1327][881,1453]" />
          </node>
        </node>
      </hierarchy>
    """.trimIndent()

    val input = TicketViviPageEnforcer.controlCodeInputActionForHierarchy(xml)

    assertEquals("focus_control_code_input", input?.reason)
    assertEquals(540, input?.x)
    assertEquals(1238, input?.y)
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
    assertEquals(908, close?.x)
    assertEquals(1220, close?.y)
    assertEquals("[868,1180][948,1260]", close?.bounds)
    assertEquals("253986", TicketViviPageEnforcer.controlCodeResultValueForHierarchy(xml))
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
  fun filledControlCodePopupWithKeyboardIsNotGeneratedResult() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,239][450,365]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,409][190,461]" />
        <node package="com.pv.vivi" content-desc="B > A" bounds="[68,475][250,545]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[205,1400][875,1470]" />
        <node package="com.pv.vivi" content-desc="Ievadi kontroles kodu" focusable="true" bounds="[330,910][750,970]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="13468" clickable="true" focusable="true" focused="true" hint="kontroles kods" bounds="[350,1000][730,1090]" />
        <node package="com.android.inputmethod.latin" text="1" clickable="true" bounds="[260,1660][410,1810]" />
        <node package="com.android.inputmethod.latin" text="2" clickable="true" bounds="[410,1660][560,1810]" />
        <node package="com.android.inputmethod.latin" text="3" clickable="true" bounds="[560,1660][710,1810]" />
      </hierarchy>
    """.trimIndent()

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertFalse(TicketViviPageEnforcer.controlCodeResultValueForHierarchy(xml)?.isNotBlank() == true)
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_POPUP, TicketViviPageEnforcer.classifyForRecovery(xml))
  }

  @Test
  fun detectsShiftedSubmitButtonAfterKeyboardOpens() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,239][450,365]" />
        <node package="com.pv.vivi" content-desc="Ievadi kontroles kodu" focusable="true" bounds="[330,760][750,820]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="13468" clickable="true" editable="true" focusable="true" focused="true" hint="kontroles kods" bounds="[350,850][730,940]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="Atcelt" clickable="true" bounds="[474,980][642,1070]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" bounds="[715,980][879,1070]" />
        <node package="com.android.inputmethod.latin" text="1" clickable="true" bounds="[260,1660][410,1810]" />
      </hierarchy>
    """.trimIndent()

    val submit = TicketViviPageEnforcer.controlCodeSubmitActionLooseForHierarchy(xml)

    assertEquals("submit_control_code_popup", submit?.reason)
    assertEquals(797, submit?.x)
    assertEquals(1025, submit?.y)
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_POPUP, TicketViviPageEnforcer.classifyForRecovery(xml))
  }

  @Test
  fun detectsGeneratedControlCodeGraphicWithoutVisibleDigits() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" text="Kontroles kods" bounds="[124,548][700,604]" />
        <node package="com.pv.vivi" class="android.widget.ImageView" content-desc="Aztec kontroles kods" bounds="[250,780][830,1360]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[868,1040][980,1152]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertEquals(924, close?.x)
    assertEquals(1096, close?.y)
    assertNull(TicketViviPageEnforcer.controlCodeResultValueForHierarchy(xml))
  }

  @Test
  fun generatedGraphicCloseFallbackIgnoresTopRightTicketExit() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" text="Kontroles kods" bounds="[124,548][700,604]" />
        <node package="com.pv.vivi" class="android.widget.ImageView" content-desc="Aztec kontroles kods" bounds="[200,800][880,1480]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[936,420][1032,516]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertTrue("generated-code close must be aligned with the Aztec, not the ticket exit", close?.y ?: 0 > 900)
  }

  @Test
  fun detectsGeneratedCodeResultFromSpacedNumericRowWithoutCloseNode() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" text="2 5 6 9 8 4 1 5" bounds="[354,1340][760,1418]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertTrue("synthetic close should be to the right of the generated code", close?.x ?: 0 > 760)
    assertEquals("25698415", TicketViviPageEnforcer.controlCodeResultValueForHierarchy(xml))
  }

  @Test
  fun detectsShortLiveGeneratedCodeResult() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,239][450,365]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,409][190,461]" />
        <node package="com.pv.vivi" content-desc="255" bounds="[485,1325][595,1414]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[823,1317][949,1422]" />
        <node package="com.pv.vivi" content-desc="30 dienu biļete" bounds="[396,1593][684,1653]" />
        <node package="com.pv.vivi" content-desc="23.04.2026 - 22.05.2026" bounds="[66,1871][592,1939]" />
        <node package="com.pv.vivi" content-desc="46.00€" bounds="[865,1871][1014,1939]" />
        <node package="com.pv.vivi" content-desc="AS “Pasažieru Vilciens” PVN Reģ. Nr. LV40003567907" bounds="[180,2005][900,2047]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertEquals(886, close?.x)
    assertEquals(1369, close?.y)
  }

  @Test
  fun detectsEmbeddedGeneratedControlCodeResultOverTicketDetail() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="23.04.2026 - 22.05.2026" bounds="[120,1580][700,1650]" />
        <node package="com.pv.vivi" text="46,00€" bounds="[730,1580][960,1650]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[936,420][1032,516]" />
        <node package="com.pv.vivi" text="25698415" bounds="[354,1340][760,1418]" />
        <node package="com.pv.vivi" text="x" clickable="true" bounds="[894,1324][982,1432]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertEquals(938, close?.x)
    assertEquals(1378, close?.y)
    assertEquals("[894,1324][982,1432]", close?.bounds)
  }

  @Test
  fun detectsNineDigitGeneratedCodeResultOverTicketDetail() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="30 dienu biļete" bounds="[396,1593][684,1653]" />
        <node package="com.pv.vivi" text="23.04.2026 - 22.05.2026" bounds="[120,1840][700,1910]" />
        <node package="com.pv.vivi" text="561649898" bounds="[176,1178][900,1280]" />
        <node package="com.pv.vivi" text="×" clickable="true" bounds="[824,1176][916,1282]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertFalse(TicketViviPageEnforcer.isTicketDetail(xml))
    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("561649898", TicketViviPageEnforcer.controlCodeResultValueForHierarchy(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertEquals(870, close?.x)
    assertEquals(1229, close?.y)
  }

  @Test
  fun fallsBackToGeneratedCodeRowGeometryWhenInlineCloseIsMissing() {
    val xml = """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="KONTROLES KODS" clickable="true" bounds="[53,264][450,390]" />
        <node package="com.pv.vivi" content-desc="ZONAS" bounds="[68,434][190,486]" />
        <node package="com.pv.vivi" content-desc="PV-ELB-20260423-0RJB2M" bounds="[119,1329][961,1423]" />
        <node package="com.pv.vivi" text="23.04.2026 - 22.05.2026" bounds="[120,1580][700,1650]" />
        <node package="com.pv.vivi" text="46,00€" bounds="[730,1580][960,1650]" />
        <node package="com.pv.vivi" content-desc="Aizvērt" clickable="true" bounds="[936,420][1032,516]" />
        <node package="com.pv.vivi" text="25698415" bounds="[354,1340][760,1418]" />
      </hierarchy>
    """.trimIndent()

    val close = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(xml)

    assertEquals(TicketViviRecoveryState.CONTROL_CODE_RESULT, TicketViviPageEnforcer.classifyForRecovery(xml))
    assertEquals("close_control_code_result", close?.reason)
    assertEquals(908, close?.x)
    assertEquals(1379, close?.y)
    assertEquals("[869,1340][947,1418]", close?.bounds)
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

    val currentRange = currentTicketDateRange()
    val listAction = TicketViviPageEnforcer.resetActionForHierarchy(
      """
        <hierarchy>
          <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
          <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$currentRange&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
          <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
        </hierarchy>
      """.trimIndent()
    )
    assertEquals("open_fresh_time_ticket_card", listAction?.reason)

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
    assertEquals("open_fresh_time_ticket_card", TicketViviPageEnforcer.recoveryActionForHierarchy(listXml)?.reason)
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
    assertEquals(994, action?.x)
    assertEquals(225, action?.y)
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
    val settingsAction = TicketViviPageEnforcer.recoveryActionForHierarchy(settingsXml)

    assertEquals(TicketViviRecoveryState.SETTINGS_OR_PROFILE, TicketViviPageEnforcer.classifyForRecovery(settingsXml))
    assertEquals("open_tickets_tab", settingsAction?.reason)
    assertEquals(405, settingsAction?.x)
    assertEquals(2285, settingsAction?.y)
  }

  @Test
  fun allowsKontrolesKodsButtonOnTicketDetail() {
    val xml = ticketDetailXml()

    assertTrue(TicketViviPageEnforcer.isTicketDetail(xml))
    assertTrue(TicketViviPageEnforcer.isControlCodeButtonTap(xml, 55, 265))
    assertTrue(TicketViviPageEnforcer.isControlCodeButtonTap(xml, 250, 327))
    assertTrue(TicketViviPageEnforcer.isControlCodeButtonTap(xml, 448, 388))
    assertFalse(TicketViviPageEnforcer.isForbiddenViviTap(xml, 250, 327))
    val action = TicketViviPageEnforcer.controlCodeButtonActionForHierarchy(xml)
    assertEquals("control_code_button_snap_detected", action?.reason)
    assertEquals(251, action?.x)
    assertEquals(327, action?.y)
    assertEquals("[53,264][450,390]", action?.bounds)
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
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="OK" clickable="true" bounds="[720,890][940,1016]" />
      </hierarchy>
    """.trimIndent()
  }

  private fun ticketsListXml(): String {
    val currentRange = currentTicketDateRange()
    return """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$currentRange&#10;B&#10;A" clickable="true" bounds="[0,536][1080,1011]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()
  }

  private fun loginScreenXml(): String {
    return """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="IEIET PROFILĀ" bounds="[318,471][762,554]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="user@example.test" clickable="true" editable="true" focusable="true" focused="false" bounds="[223,877][1001,956]" />
        <node package="com.pv.vivi" class="android.widget.EditText" text="" hint="Parole" clickable="true" editable="true" password="true" focusable="true" focused="false" bounds="[223,1069][873,1148]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="IEIET" clickable="false" enabled="false" bounds="[79,1203][1001,1418]" />
        <node package="com.pv.vivi" content-desc="Reģistrēties" clickable="true" bounds="[79,1479][1001,1610]" />
        <node package="com.pv.vivi" class="android.widget.Button" content-desc="TURPINĀT BEZ AUTORIZĀCIJAS" clickable="true" enabled="true" bounds="[79,2078][1001,2205]" />
      </hierarchy>
    """.trimIndent()
  }

  private fun ticketListWithExpiredAndCurrentCardsXml(today: LocalDate = LocalDate.now()): String {
    val firstExpiredRange = ticketDateRange(today.minusDays(65), today.minusDays(36))
    val secondExpiredRange = ticketDateRange(today.minusDays(40), today.minusDays(11))
    val currentRange = ticketDateRange(today.minusDays(3), today.plusDays(26))
    return """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$firstExpiredRange&#10;B&#10;A" clickable="true" bounds="[0,536][1080,900]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$secondExpiredRange&#10;B&#10;A" clickable="true" bounds="[0,910][1080,1274]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$currentRange&#10;B&#10;A" clickable="true" bounds="[0,1284][1080,1648]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()
  }

  private fun ticketListWithOnlyFutureCardsXml(today: LocalDate = LocalDate.now()): String {
    val expiredRange = ticketDateRange(today.minusDays(65), today.minusDays(36))
    val firstFutureRange = ticketDateRange(today.plusDays(1), today.plusDays(30))
    val secondFutureRange = ticketDateRange(today.plusDays(8), today.plusDays(37))
    return """
      <hierarchy>
        <node package="com.pv.vivi" content-desc="Manas biļetes" bounds="[288,158][792,270]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$expiredRange&#10;B&#10;A" clickable="true" bounds="[0,536][1080,900]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$firstFutureRange&#10;B&#10;A" clickable="true" bounds="[0,910][1080,1274]" />
        <node package="com.pv.vivi" content-desc="Olaine - Rīga&#10;30 dienu biļete&#10;DERĪGA POSMĀ&#10;Cena - Rīga&#10;DERĪGA&#10;$secondFutureRange&#10;B&#10;A" clickable="true" bounds="[0,1284][1080,1648]" />
        <node package="com.pv.vivi" content-desc="Tickets&#10;2. cilne no 4" clickable="true" selected="true" bounds="[270,2209][540,2361]" />
      </hierarchy>
    """.trimIndent()
  }

  private fun currentTicketDateRange(today: LocalDate = LocalDate.now()): String {
    return ticketDateRange(today.minusDays(3), today.plusDays(26))
  }

  private fun ticketDateRange(start: LocalDate, end: LocalDate): String {
    return "${start.format(TICKET_DATE_FORMAT)} - ${end.format(TICKET_DATE_FORMAT)}"
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

  private companion object {
    val TICKET_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
  }
}
