package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TicketIdleRefreshTest {
  private val close = TicketVisualProbeBounds(170, 4, 187, 20)
  private val unused = TicketVisualProbeBounds(10, 100, 180, 130)
  private val activated = TicketVisualProbeBounds(145, 75, 185, 95)
  private val anchor = "d_" + "a".repeat(28)
  private fun detail(active: Boolean = false, id: Long = 1) = TicketVisualActionObservation(
    id, if (active) TicketVisualPhoneState.ACTIVATED_DETAIL else TicketVisualPhoneState.UNACTIVATED_DETAIL,
    currentAnchor = anchor, sliderBounds = unused.takeUnless { active }, backBounds = close,
    captureGeneration = 2, detailCardAnchor = "original"
  )
  private fun card(id: String = "original", latest: Boolean = false) = TicketVisualCardAnchor(
    id, TicketVisualProbeBounds(8, 50, 185, 140), unused, activated, latest
  )
  private fun list(vararg cards: TicketVisualCardAnchor) = TicketVisualActionObservation(
    2, TicketVisualPhoneState.TICKET_LIST, cards = cards.toList(), captureGeneration = 2
  )

  @Test fun sameUnusedAndActivatedTicketEachRestartOnceAndReopenExactCard() = runBlocking {
    for (active in listOf(false, true)) {
      val before = detail(active)
      val observations = ArrayDeque(listOf(list(card()), detail(active, 3)))
      val taps = mutableListOf<TicketVisualProbeBounds>()
      var claims = 0
      var restarts = 0
      val result = refreshCurrentTicket(before, false,
        begin = { claims++; true },
        restart = { restarts++; true },
        dispatch = { _, bounds, _ -> taps.add(bounds); true },
        observe = { observations.removeFirstOrNull() })
      assertTrue(result.ok)
      assertEquals(1, claims)
      assertEquals(1, restarts)
      assertEquals(listOf(if (active) activated else unused), taps)
      assertEquals(before.currentAnchor, result.observation?.currentAnchor)
      assertEquals(before.state, result.observation?.state)
    }
  }

  @Test fun returningViewerBeforeClaimOrDeniedClaimNeverTaps() = runBlocking {
    var taps = 0
    val result = refreshCurrentTicket(detail(), false, { false }, { error("restart after denied admission") },
      { _, _, _ -> taps++; true }, { error("observation after rejected admission") })
    assertFalse(result.ok)
    assertEquals(0, taps)
    assertEquals("ticket_action_idle_refresh_cancelled", result.reason)
  }

  @Test fun viewerReturnAfterRestartStillFinishesButTouchProtectionCanStopReopen() = runBlocking {
    for (physicalTouch in listOf(false, true)) {
      var viewerPresent = false
      var claims = 0
      var taps = 0
      val observations = ArrayDeque(listOf(list(card()), detail(id = 3)))
      val result = refreshCurrentTicket(detail(), false,
        begin = { claims++; !viewerPresent },
        restart = { viewerPresent = true; true },
        dispatch = { _, _, _ ->
          if (physicalTouch) false else { taps++; true }
        }, observe = { observations.removeFirstOrNull() })
      assertEquals(!physicalTouch, result.ok)
      assertEquals(1, claims)
      assertEquals(if (physicalTouch) 0 else 1, taps)
    }
  }

  @Test fun ambiguousOrMissingMatchingCardStopsAfterRestartWithoutNewestFallback() = runBlocking {
    for (cards in listOf(emptyList(), listOf(card("newest", latest = true)), listOf(card(), card()))) {
      var taps = 0
      val result = refreshCurrentTicket(detail(), false, { true }, { true },
        { _, _, _ -> taps++; true }, { list(*cards.toTypedArray()) })
      assertFalse(result.ok)
      assertEquals(0, taps)
      assertEquals("ticket_action_visual_target_ambiguous", result.reason)
    }
  }

  @Test fun exactValidityPairSelectsOriginalAmongSeveralAndNeverSubstitutesWhenMissing() {
    val original = detail()
    assertEquals("original", ticketIdleRefreshCard(original, list(card(), card("newest", true)))?.anchor)
    assertNull(ticketIdleRefreshCard(original, list(card("newest", true))))
    assertNull(ticketIdleRefreshCard(original.copy(detailCardAnchor = ""), list(card())))
  }

  @Test fun wrongTicketOrChangedActivationStateCannotBecomeSuccess() = runBlocking {
    for (wrong in listOf(detail(id = 3).copy(currentAnchor = "d_" + "b".repeat(28)), detail(true, 3))) {
      var taps = 0
      val observations = ArrayDeque(listOf(list(card()), wrong))
      val result = refreshCurrentTicket(detail(), false, { true }, { true },
        { _, _, _ -> taps++; true }, { observations.removeFirstOrNull() })
      assertFalse(result.ok)
      assertEquals(1, taps)
      assertEquals("ticket_action_detail_identity_conflict", result.reason)
    }
  }

  @Test fun uncertainTapOrRestartJournalNeverReplays() = runBlocking {
    for (retained in listOf(false, true)) {
      var claims = 0
      var restarts = 0
      val result = refreshCurrentTicket(detail(), retained, { claims++; true },
        { restarts++; false }, { _, _, _ -> error("tap after uncertain restart") },
        { error("observation after uncertain restart") })
      assertFalse(result.ok)
      assertEquals(if (retained) 0 else 1, claims)
      assertEquals(if (retained) 0 else 1, restarts)
    }
  }

  @Test fun nonDetailMissingIdentityAndChangedCaptureCannotAuthorizeExtraNavigation() = runBlocking {
    for (before in listOf(list(card()), detail().copy(currentAnchor = ""), detail().copy(backBounds = null))) {
      assertFalse(refreshCurrentTicket(before, false,
        { error("claim on unknown detail") }, { error("restart on unknown detail") },
        { _, _, _ -> error("tap on unknown detail") }, { null }).ok)
    }
    var taps = 0
    val changedCapture = refreshCurrentTicket(detail(), false, { true }, { true },
      { _, _, _ -> taps++; true }, { list(card()).copy(captureGeneration = 3) })
    assertFalse(changedCapture.ok)
    assertEquals(0, taps)
  }

  @Test fun refreshPayloadIsInternalAndRetainedSuccessAcceptsBothTicketStates() {
    val payload = """{"version":3,"actionId":"idle-action","target":"refresh_current_ticket","source":"ticket_remote_idle_refresh","flow":"idle_ticket_refresh"}"""
    assertNotNull(parseTicketVisualActionRequest(Json.parseToJsonElement(payload).jsonObject))
    assertNull(parseTicketVisualActionRequest(Json.parseToJsonElement(payload.replace("ticket_remote_idle_refresh", "browser")).jsonObject))
    assertNull(parseTicketVisualActionRequest(Json.parseToJsonElement(payload.replace("idle_ticket_refresh", "")).jsonObject))
    for (view in listOf(TicketVisualActionView.LATEST_UNACTIVATED, TicketVisualActionView.ACTIVATED_CURRENT)) {
      assertTrue(ticketVisualTerminalViewCompatible(TicketVisualActionTarget.REFRESH_CURRENT_TICKET, view))
    }
    assertFalse(ticketVisualTerminalViewCompatible(TicketVisualActionTarget.REFRESH_CURRENT_TICKET, TicketVisualActionView.UNKNOWN))
  }

  @Test fun missingValidityIdentityNeverRestartsEvenWhenOnlyOneTicketMightRemain() = runBlocking {
    assertFalse(refreshCurrentTicket(detail().copy(detailCardAnchor = ""), false,
      { error("claim before identity") }, { error("restart before identity") },
      { _, _, _ -> error("tap before identity") }, { list(card()) }).ok)
  }

  @Test fun journalThenClaimThenRestartAndNeverClaimAgainForRestoration() = runBlocking {
    val order = mutableListOf<String>()
    val observations = ArrayDeque(listOf(list(card()), detail(id = 3)))
    val result = refreshCurrentTicket(detail(), false,
      begin = { order.add("claim"); true },
      restart = { order.add("restart"); true },
      dispatch = { _, _, _ -> order.add("tap"); true },
      observe = { observations.removeFirstOrNull() },
      prepare = { _, _, _ -> order.add("prepare_and_journal"); true })
    assertTrue(result.ok)
    assertEquals(listOf("prepare_and_journal", "claim", "restart", "prepare_and_journal", "tap"), order)
    assertFalse(refreshCurrentTicket(detail(), false,
      { error("claim after failed preparation") }, { error("restart") }, { _, _, _ -> error("tap") }, { null },
      prepare = { _, _, _ -> false }).ok)
  }

  @Test fun freshDateIdentityWorksAfterColdStartButExactFinalDetailProofRemainsRequired() = runBlocking {
    val newHelper = detail().copy(currentAnchor = "d_" + "b".repeat(28), captureGeneration = 9)
    val observations = ArrayDeque(listOf(list(card()).copy(captureGeneration = 9), newHelper.copy(probeId = 3)))
    assertTrue(refreshCurrentTicket(newHelper, false, { true }, { true },
      { _, _, _ -> true }, { observations.removeFirstOrNull() }).ok)
    assertFalse(ticketVisualObservationsAgree(newHelper, newHelper.copy(detailCardAnchor = "other")))
  }

  @Test fun appRestoresExactDetailDirectlyWithoutAnyNavigation() = runBlocking {
    for (active in listOf(false, true)) {
      var restarts = 0
      val result = refreshCurrentTicket(detail(active), false, { true }, { restarts++; true },
        { _, _, _ -> error("already restored") }, { detail(active, 2) })
      assertTrue(result.ok)
      assertEquals(1, restarts)
    }
  }

  @Test fun homeRouteReusesNavigationButSelectsOriginalEvenWhenAnotherCardIsLatest() = runBlocking {
    val tickets = TicketVisualProbeBounds(20, 180, 40, 195)
    val time = TicketVisualProbeBounds(100, 25, 180, 40)
    val observations = ArrayDeque(listOf(
      TicketVisualActionObservation(2, TicketVisualPhoneState.VIVI_HOME,
        ticketsTabBounds = tickets, captureGeneration = 2),
      TicketVisualActionObservation(3, TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
        timeTicketsTabBounds = time, captureGeneration = 2),
      list(card(), card("newest", true)).copy(probeId = 4), detail(id = 5)))
    val taps = mutableListOf<TicketVisualProbeBounds>()
    val result = refreshCurrentTicket(detail(), false, { true }, { true },
      { _, bounds, _ -> taps.add(bounds); true }, { observations.removeFirstOrNull() })
    assertTrue(result.ok)
    assertEquals(listOf(tickets, time, unused), taps)
  }

  @Test fun failedTapUnchangedRouteLoginAndStalePicturesNeverRepeatInput() = runBlocking {
    var attempts = 0
    assertFalse(refreshCurrentTicket(detail(), false, { true }, { true },
      { _, _, _ -> attempts++; false }, { list(card()) }).ok)
    assertEquals(1, attempts)
    for (state in listOf(TicketVisualPhoneState.LOGIN_REQUIRED, TicketVisualPhoneState.UNKNOWN,
        TicketVisualPhoneState.TICKETS_TIME_EMPTY)) {
      assertFalse(refreshCurrentTicket(detail(), false, { true }, { true },
        { _, _, _ -> error("unexpected surface must not receive input") },
        { TicketVisualActionObservation(2, state, captureGeneration = 2) }).ok)
    }
    var probe = 1L
    var taps = 0
    assertFalse(refreshCurrentTicket(detail(), false, { true }, { true },
      { _, _, _ -> taps++; true }, { TicketVisualActionObservation(++probe,
        TicketVisualPhoneState.VIVI_HOME, ticketsTabBounds = unused, captureGeneration = 2) }).ok)
    assertEquals(1, taps)
    assertFalse(refreshCurrentTicket(detail(), false, { true }, { true },
      { _, _, _ -> error("stale image must not receive input") }, { detail() }).ok)
  }

  @Test fun detailValidityBandUsesExactlyTheExistingListCardAnchor() {
    val pixels = dateFixture()
    val recognized = TicketVisualDateGlyphRecognizer.recognize(pixels, 384, 576)
    assertEquals(1, recognized.size)
    assertEquals("2031-04-01", recognized.single().from.toString())
    assertEquals("2031-04-30", recognized.single().until.toString())
    assertEquals(recognized.single().anchor, detailCardAnchor(pixels))
  }

  @Test fun homeNoticeMayResembleTicketGraphicsButRequiresAllFourNavigationGlyphs() {
    val pixels = homePixels()
    paint(pixels, 4, 32, 188, 56, 0xffbf4020.toInt())
    for (y in 56 until 136) for (x in 32 until 160) {
      pixels[y * 192 + x] = if ((x / 4 + y / 4) % 2 == 0) -1 else 0xff202020.toInt()
    }
    val compact = IntArray(48 * 72) { pixels[(it / 48 * 4 + 2) * 192 + it % 48 * 4 + 2] }
    assertEquals(TicketControlCodeVisualClassifier.RAW_TICKET,
      TicketControlCodeVisualClassifier.classifyForActivatedTicket(compact))
    assertEquals("home", TicketVisualActionClassifier.selectedBottomNavigationTab(pixels))
    assertEquals("vivi_home", TicketVisualActionClassifier.classify(pixels).state)
    // A missing peer, competing selected tab, or covered navigation cannot inherit Home authority.
    for ((left, right) in listOf(8 to 38, 46 to 82, 106 to 140, 152 to 186)) {
      val missing = pixels.copyOf()
      paint(missing, left, 258, right, 284, 0xff30353a.toInt())
      assertNotEquals("vivi_home", TicketVisualActionClassifier.classify(missing).state)
    }
    val selectedPeer = pixels.copyOf()
    paint(selectedPeer, 62, 265, 78, 272, 0xffffa000.toInt())
    assertNotEquals("vivi_home", TicketVisualActionClassifier.classify(selectedPeer).state)
  }

  @Test fun realPopupAndLoginStillBlockHomeNavigation() {
    val popup = homePixels()
    paint(popup, 32, 120, 160, 180, -1)
    paint(popup, 124, 156, 168, 176, 0xffffa000.toInt())
    assertEquals("blocked", TicketVisualActionClassifier.classify(popup).state)
    val login = homePixels()
    paint(login, 32, 100, 160, 200, -1)
    paint(login, 32, 204, 160, 224, 0xff0055ff.toInt())
    assertEquals("login_required", TicketVisualActionClassifier.classify(login).state)
  }

  @Test fun homeNoticeAndSearchButtonMustNotBecomeATicketSlider() {
    val pixels = homePixels()
    // Public notice and Search button geometry from the failed September 14 refresh.
    // No notice text, screenshot, ticket content or identity is retained.
    paint(pixels, 48, 13, 192, 46, 0xffffbb00.toInt())
    paint(pixels, 14, 211, 178, 237, 0xffffbb00.toInt())
    val compact = IntArray(48 * 72) { pixels[(it / 48 * 4 + 2) * 192 + it % 48 * 4 + 2] }
    assertTrue(TicketControlCodeVisualClassifier.registrationSliderBounds(compact).isNotEmpty())
    assertEquals("home", TicketVisualActionClassifier.selectedBottomNavigationTab(pixels))
    assertEquals("vivi_home", TicketVisualActionClassifier.classify(pixels).state)
    for ((left, right) in listOf(8 to 38, 46 to 82, 106 to 140, 152 to 186)) {
      val missing = pixels.copyOf()
      paint(missing, left, 258, right, 284, 0xff30353a.toInt())
      assertNotEquals("vivi_home", TicketVisualActionClassifier.classify(missing).state)
    }
  }

  @Test fun oldTimeTicketListGrantsOnlyTheOppositeTabTarget() {
    val pixels = oldTimeListPixels()
    val oldList = TicketVisualActionClassifier.classify(pixels)
    assertEquals("ticket_list", oldList.state)
    assertNotNull(oldList.ticketsTabBounds)
    assertNull(oldList.timeTicketsTabBounds)
    assertTrue(oldList.cards.isEmpty())
    val selectedHome = pixels.copyOf().also { paintProbe(it, 20, 265, 25, 271, 0xffffa000.toInt()) }
    assertNull(TicketVisualActionClassifier.classify(selectedHome).ticketsTabBounds)
    val ambiguousTabs = pixels.copyOf().also { paintProbe(it, 10, 36, 98, 38, 0xffffa000.toInt()) }
    assertNull(TicketVisualActionClassifier.classify(ambiguousTabs).ticketsTabBounds)
    val coveredTickets = pixels.copyOf().also { paintProbe(it, 46, 262, 84, 284, 0xff30353a.toInt()) }
    assertNull(TicketVisualActionClassifier.classify(coveredTickets).ticketsTabBounds)
  }

  @Test fun mutedThinThreeStillIdentifiesTheOnlyNewRegistrationCard() {
    val pixels = oldTimeListPixels()
    paintProbe(pixels, 8, 54, 184, 155, -1)
    paintProbe(pixels, 12, 125, 180, 144, 0xffffa000.toInt())
    val muted = 0xffc0c0c0.toInt()
    val newDate = "23.09.2099-22.10.2099"
    drawDate(pixels, newDate, top = 205, left = 24, color = muted)
    for (y in 205 until 214) for (x in 36 until 44) pixels[y * 384 + x] = -1
    val thinThree = listOf(
      "...###..", ".##..###", "##....##", "......##", "...###..",
      "......##", "......##", "##....##", ".######."
    )
    thinThree.forEachIndexed { y, row -> row.forEachIndexed { x, ink ->
      if (ink == '#') pixels[(205 + y) * 384 + 36 + x] = muted
    } }
    val result = TicketVisualActionClassifier.classify(pixels)
    assertEquals("ticket_list", result.state)
    assertEquals(1, result.cards.size)
    assertEquals(1, result.cards.count { it.latest && it.registrationBounds != null })
    assertEquals("2099-09-23", TicketVisualDateGlyphRecognizer.recognize(pixels, 384, 576)
      .first { it.centerY < 300 }.from.toString())

    val missingDate = pixels.copyOf()
    for (y in 205 until 214) for (x in 24 until 300) missingDate[y * 384 + x] = -1
    assertTrue(TicketVisualActionClassifier.classify(missingDate).cards.none { it.latest })
  }

  private fun oldTimeListPixels(): IntArray {
    val pixels = homePixels()
    for (y in 262 until 284) for (x in 8 until 84) {
      val index = y * 192 + x
      if (x < 40 && pixels[index] == 0xffffa000.toInt()) pixels[index] = -1
      if (x >= 46 && pixels[index] == -1) pixels[index] = 0xffffa000.toInt()
    }
    paint(pixels, 20, 22, 80, 26, -1)
    for (x in 116 until 166 step 3) paint(pixels, x, 22, x + 1, 26, -1)
    paint(pixels, 101, 36, 181, 38, 0xffffa000.toInt())
    paint(pixels, 8, 46, 184, 54, 0xffbf4020.toInt())
    paint(pixels, 154, 88, 164, 93, 0xffffa000.toInt())
    paint(pixels, 2, 258, 190, 259, -1)
    return IntArray(384 * 576) { pixels[(it / 384 / 2) * 192 + it % 384 / 2] }
  }

  private fun paintProbe(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
    for (y in top * 2 until bottom * 2) for (x in left * 2 until right * 2) {
      pixels[y * 384 + x] = color
    }
  }

  private fun paint(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
    for (y in top until bottom) for (x in left until right) pixels[y * 192 + x] = color
  }

  private fun homePixels(): IntArray {
    val pixels = IntArray(192 * 288) { 0xff30353a.toInt() }
    fun glyph(left: Int, top: Int, color: Int, rows: String) {
      rows.split('/').forEachIndexed { y, row -> row.forEachIndexed { x, value ->
        if (value == '#') pixels[(top + y) * 192 + left + x] = color
      } }
    }
    // Synthetic public navigation icons only; no captured page or ticket content.
    glyph(20, 265, 0xffffa000.toInt(), "..#../.#.#./#...#/#.#.#/#.#.#/##.##")
    glyph(62, 265, -1, "##################/#................#/#................#/##..............##/.#..............#./##..............##/#................#/##################")
    glyph(118, 265, -1, "..####../.#....#./.#....#./..####../......../.#....#./#......#/#......#/########")
    glyph(162, 265, -1, "############/............/............/############/............/............/############")
    return pixels
  }

  @Test fun detailValidityRejectsMissingPartialAmbiguousAndOutOfBandPairs() {
    assertEquals("", detailCardAnchor(dateFixture(text = "01.04.2031")))
    assertEquals("", detailCardAnchor(dateFixture(text = "31.04.2031-30.04.2031")))
    assertEquals("", detailCardAnchor(dateFixture(top = 240)))
    assertEquals("", detailCardAnchor(dateFixture(top = 360)))
    val ambiguous = dateFixture(top = 285)
    drawDate(ambiguous, "01.04.2031-30.04.2031", top = 310)
    assertEquals("", detailCardAnchor(ambiguous))
  }

  @Test fun otherTicketNumbersOutsideValidityBandCannotChangeCardIdentity() {
    val pixels = dateFixture()
    val expected = detailCardAnchor(pixels)
    assertTrue(expected.isNotBlank())
    drawDate(pixels, "22.05.2031-30.05.2031", top = 150)
    drawDate(pixels, "22.05.2031-30.05.2031", top = 370)
    drawDate(pixels, "4600", top = 312, left = 304)
    assertEquals(expected, detailCardAnchor(pixels))
  }

  private fun detailCardAnchor(pixels: IntArray): String {
    val bounds = TicketVisualActionClassifier.detailValidityBounds(384, 576)
    val width = TicketVisualActionClassifier.DETAIL_VALIDITY_WIDTH
    val height = TicketVisualActionClassifier.DETAIL_VALIDITY_HEIGHT
    val band = IntArray(width * height) { index ->
      val x = bounds.left + index % width * (bounds.right - bounds.left) / width
      val y = bounds.top + index / width * (bounds.bottom - bounds.top) / height
      pixels[y * 384 + x]
    }
    return TicketVisualActionClassifier.detailCardAnchor(band)
  }

  private fun dateFixture(text: String = "01.04.2031-30.04.2031", top: Int = 308): IntArray =
    IntArray(384 * 576) { 0xffffffff.toInt() }.also { drawDate(it, text, top) }

  private fun drawDate(pixels: IntArray, text: String, top: Int, left: Int = 12,
    color: Int = 0xff000000.toInt()) {
    // Seven-pixel source digits match the ordinary probe's date scale; the bounded native strip
    // preserves fourteen-pixel digits. Keep the fixture at that scale rather than enlarging it twice.
    val digits = listOf(
      ".###./##.##/##.##/##.##/##.##/##.##/.###.",
      "..##./.###./..##./..##./..##./..##./.####",
      ".###./##.##/...##/..##./.##../##.../#####",
      "####./...##/...##/.###./...##/...##/####.",
      "...##/..###/.#.##/##.##/#####/...##/...##",
      "#####/##.../##.../####./...##/...##/####.",
      ".###./##.../##.../####./##.##/##.##/.###.",
      "#####/...##/...##/..##./..##./.##../.##..",
      ".###./##.##/##.##/.###./##.##/##.##/.###.",
      ".###./##.##/##.##/.####/...##/...##/.###."
    )
    text.forEachIndexed { index, character ->
      val rows = if (character.isDigit()) digits[character.digitToInt()].split('/') else emptyList()
      rows.forEachIndexed { y, row -> row.forEachIndexed { x, pixel ->
        if (pixel == '#') pixels[(top + y) * 384 + left + index * 12 + x] = color
      } }
    }
  }

}
