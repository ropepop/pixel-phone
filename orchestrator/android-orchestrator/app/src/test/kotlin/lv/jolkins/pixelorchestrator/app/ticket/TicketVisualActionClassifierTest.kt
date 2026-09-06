package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class TicketVisualActionClassifierTest {
  @Test
  fun unknownAndOrientationDriftFailClosed() {
    val neutral = IntArray(TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT) {
      0xff777777.toInt()
    }
    assertEquals("unknown", TicketVisualActionClassifier.classify(neutral).state)
    assertEquals("unknown", TicketVisualActionClassifier.classify(IntArray(100)).state)
  }

  @Test
  fun selectedHomeBottomNavigationProvesOnlyTheTicketsTabInBothProbeSizes() {
    listOf(DARK to LIGHT, LIGHT to DARK).forEach { (background, neutral) ->
      val frame = viviHomeFrame(background, neutral)
      val sample = TicketVisualActionClassifier.classify(frame)
      val current = TicketVisualActionClassifier.classifyCurrent(frame)
      val highResolution = TicketVisualActionClassifier.classify(upscaleToProbe(frame))
      assertEquals("home", TicketVisualActionClassifier.selectedBottomNavigationTab(frame))
      assertEquals("home", TicketVisualActionClassifier.selectedBottomNavigationTab(upscaleToProbe(frame)))

      for (result in listOf(sample, current, highResolution)) {
        assertEquals("vivi_home", result.state)
        assertTrue(result.ticketsTabBounds != null)
        assertBoundsInSampleSpace(result.ticketsTabBounds!!)
        assertTrue(result.sliderBounds == null)
        assertTrue(result.backBounds == null)
        assertTrue(result.cards.isEmpty())
      }
      assertBoundsNear(sample.ticketsTabBounds!!, highResolution.ticketsTabBounds!!)
      assertTrue(sample.ticketsTabBounds!!.left >= 60)
      assertTrue(sample.ticketsTabBounds!!.right <= 84)
    }
  }

  @Test
  fun selectedProfileBottomNavigationIgnoresBodyAndRejectsASelectedPeer() {
    listOf(DARK to LIGHT, LIGHT to DARK).forEach { (background, neutral) ->
      val frame = viviProfileFrame(background, neutral).also { pixels ->
        // Dynamic account body colors are irrelevant to the lower-tab authority.
        fill(pixels, 12, 40, 180, 220, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
      }
      assertEquals("profile", TicketVisualActionClassifier.selectedBottomNavigationTab(frame))
      for (result in listOf(
        TicketVisualActionClassifier.classify(frame),
        TicketVisualActionClassifier.classifyCurrent(frame),
        TicketVisualActionClassifier.classify(upscaleToProbe(frame))
      )) {
        assertEquals("vivi_profile", result.state)
        assertTrue(result.ticketsTabBounds == null)
        assertTrue(result.sliderBounds == null)
        assertTrue(result.cards.isEmpty())
      }

      val peerSelected = frame.copyOf().also(::drawCurrentSelectedHome)
      assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(peerSelected).state)
      assertEquals("", TicketVisualActionClassifier.selectedBottomNavigationTab(peerSelected))
    }
  }

  @Test
  fun selectedTicketsAndMenuBottomNavigationProveOnlyAProfileRouteAuthority() {
    listOf(DARK to LIGHT, LIGHT to DARK).forEach { (background, neutral) ->
      listOf(true, false).forEach { ticketsSelected ->
        val frame = viviOtherTabFrame(background, neutral, ticketsSelected).also { pixels ->
          // Page content is intentionally arbitrary: only the four lower glyphs are authority.
          fill(pixels, 12, 40, 180, 220, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
        }
        assertEquals(
          if (ticketsSelected) "tickets" else "menu",
          TicketVisualActionClassifier.selectedBottomNavigationTab(frame)
        )
        for (result in listOf(
          TicketVisualActionClassifier.classify(frame),
          TicketVisualActionClassifier.classifyCurrent(frame),
          TicketVisualActionClassifier.classify(upscaleToProbe(frame))
        )) {
          assertEquals(
            "ticketsSelected=$ticketsSelected",
            if (ticketsSelected) "unknown" else "vivi_other_tab",
            result.state
          )
          assertTrue(result.ticketsTabBounds == null)
          assertTrue(result.sliderBounds == null)
          assertTrue(result.cards.isEmpty())
        }

        val peerSelected = frame.copyOf().also { pixels ->
          fill(pixels, 115, 264, 127, 278, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
          fill(pixels, 117, 266, 125, 276, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
        }
        assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(peerSelected).state)
        assertEquals("", TicketVisualActionClassifier.selectedBottomNavigationTab(peerSelected))
      }
    }
  }

  @Test
  fun homeProofDoesNotRequireAnyPageBodyActionInBothThemes() {
    listOf(DARK to LIGHT, LIGHT to DARK).forEach { (background, neutral) ->
      val frame = viviRootHomeFrame(background, neutral)
      val sample = TicketVisualActionClassifier.classify(frame)
      val current = TicketVisualActionClassifier.classifyCurrent(frame)
      val highResolution = TicketVisualActionClassifier.classify(upscaleToProbe(frame))

      for (result in listOf(sample, current, highResolution)) {
        assertEquals("background=$background", "vivi_home", result.state)
        assertTrue(result.ticketsTabBounds != null)
        assertBoundsInSampleSpace(result.ticketsTabBounds!!)
        assertTrue(result.sliderBounds == null)
        assertTrue(result.cards.isEmpty())
      }
      assertBoundsNear(sample.ticketsTabBounds!!, highResolution.ticketsTabBounds!!)

      val target = sample.ticketsTabBounds!!
      val mapped = TicketCaptureGeometry.mapProbeBoundsToDevice(
        TicketVisualProbeBounds(target.left, target.top, target.right, target.bottom),
        TicketVisualActionClassifier.SAMPLE_WIDTH,
        TicketVisualActionClassifier.SAMPLE_HEIGHT,
        1080,
        2424
      )
      val centerX = (mapped.left + mapped.right) / 2
      val centerY = (mapped.top + mapped.bottom) / 2
      assertTrue("mapped ticket x=$centerX", centerX in 350..470)
      assertTrue("mapped ticket y=$centerY", centerY in 2200..2370)
    }
  }

  @Test
  fun currentPixelBottomNavigationKeepsTheThinSelectedHomeProofAfterProbeReduction() {
    val frame = livePixelBottomNavigationHomeFrame()
    val sample = TicketVisualActionClassifier.classifyCurrent(frame)
    val highResolution = TicketVisualActionClassifier.classifyCurrent(upscaleToProbe(frame))

    for (result in listOf(sample, highResolution)) {
      assertEquals("vivi_home", result.state)
      assertTrue(result.ticketsTabBounds != null)
      assertTrue(result.sliderBounds == null)
      assertTrue(result.cards.isEmpty())
    }
    assertBoundsNear(sample.ticketsTabBounds!!, highResolution.ticketsTabBounds!!)

    val mapped = TicketCaptureGeometry.mapProbeBoundsToDevice(
      TicketVisualProbeBounds(
        sample.ticketsTabBounds!!.left,
        sample.ticketsTabBounds!!.top,
        sample.ticketsTabBounds!!.right,
        sample.ticketsTabBounds!!.bottom
      ),
      TicketVisualActionClassifier.SAMPLE_WIDTH,
      TicketVisualActionClassifier.SAMPLE_HEIGHT,
      1080,
      2424
    )
    val centerX = (mapped.left + mapped.right) / 2
    val centerY = (mapped.top + mapped.bottom) / 2
    assertTrue("mapped ticket x=$centerX", centerX in 390..425)
    assertTrue("mapped ticket y=$centerY", centerY in 2260..2310)
  }

  @Test
  fun dynamicPageBodyAndSeparatorDoNotChangeBottomNavigationAuthority() {
    val bodyCleared = livePixelBottomNavigationHomeFrame().also {
      fill(it, 0, 0, 192, 258, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
    }
    val bannerAndRouteBody = livePixelBottomNavigationHomeFrame().also {
      fill(it, 0, 33, 144, 34, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(it, 40, 34, 73, 35, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(it, 50, 35, 74, 38, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(it, 10, 38, 112, 39, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(it, 0, 39, 144, 40, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(it, 0, 40, 127, 41, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(it, 14, 180, 178, 224, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(it, 0, 250, 192, 258, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    }

    listOf("cleared" to bodyCleared, "banner" to bannerAndRouteBody).forEach { (variant, frame) ->
      assertEquals(variant, "vivi_home", TicketVisualActionClassifier.classifyCurrent(frame).state)
      assertEquals(variant, "proved_bottom_navigation", homeDiagnostic(frame))
    }
  }

  @Test
  fun sparseOrSolidSelectedHomeCannotAuthorizeTheBottomNavigation() {
    val sparse = livePixelBottomNavigationHomeFrame()
    fill(sparse, 8, 258, 38, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    listOf(22 to 266, 25 to 266, 21 to 267, 27 to 267, 19 to 268).forEach { (x, y) ->
      sparse[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = YELLOW
    }
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(sparse).state)
    assertEquals("reject_selected_home_sparse", homeDiagnostic(sparse))

    val solid = livePixelBottomNavigationHomeFrame()
    fill(solid, 12, 262, 32, 280, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(solid).state)
    assertEquals("reject_selected_home_shape", homeDiagnostic(solid))
  }

  @Test
  fun incompleteAmbiguousOrSelectedTicketBottomNavigationCannotAuthorizeRootRecovery() {
    val missingProfile = viviRootHomeFrame(DARK, LIGHT)
    fill(missingProfile, 106, 258, 140, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingProfile).state)

    val missingMenu = viviRootHomeFrame(DARK, LIGHT)
    fill(missingMenu, 152, 258, 186, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingMenu).state)

    val ticketSelected = livePixelBottomNavigationHomeFrame()
    listOf(64 to 266, 65 to 266, 66 to 266, 67 to 266).forEach { (x, y) ->
      ticketSelected[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = YELLOW
    }
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(ticketSelected).state)

    val profileSelected = livePixelBottomNavigationHomeFrame()
    listOf(116 to 267, 117 to 267, 118 to 267, 119 to 267).forEach { (x, y) ->
      profileSelected[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = YELLOW
    }
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(profileSelected).state)

    val menuSelected = livePixelBottomNavigationHomeFrame()
    listOf(163 to 265, 164 to 265, 165 to 265, 166 to 265).forEach { (x, y) ->
      menuSelected[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = YELLOW
    }
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(menuSelected).state)

    val shiftedTicket = viviRootHomeFrame(DARK, LIGHT)
    fill(shiftedTicket, 46, 258, 84, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(shiftedTicket, 86, 266, 102, 274, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(shiftedTicket).state)

    val overlay = viviRootHomeFrame(DARK, LIGHT)
    fill(overlay, 0, 250, 192, 288, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(overlay).state)
  }

  @Test
  fun ticketSelectedOrIncompleteBottomNavigationCannotMasqueradeAsHome() {
    val ticketSelected = viviHomeFrame(DARK, LIGHT)
    fill(ticketSelected, 8, 258, 38, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(ticketSelected, 50, 260, 74, 280, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(ticketSelected).state)

    val missingTicketGlyph = viviHomeFrame(DARK, LIGHT)
    fill(missingTicketGlyph, 46, 258, 82, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingTicketGlyph).state)
  }

  @Test
  fun currentDiagnosticNamesTheExactHomeProofGateWithoutContentOrCoordinates() {
    val proved = livePixelBottomNavigationHomeFrame()
    assertEquals("proved_bottom_navigation", homeDiagnostic(proved))

    val sparseSelectedHome = livePixelBottomNavigationHomeFrame()
    fill(sparseSelectedHome, 8, 258, 38, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("reject_selected_home_sparse", homeDiagnostic(sparseSelectedHome))

    val selectedTicket = livePixelBottomNavigationHomeFrame()
    fill(selectedTicket, 60, 263, 82, 278, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("reject_selected_ticket_conflict", homeDiagnostic(selectedTicket))

    val missingTicketGlyph = livePixelBottomNavigationHomeFrame()
    fill(missingTicketGlyph, 46, 258, 82, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("reject_ticket_components_missing", homeDiagnostic(missingTicketGlyph))

    listOf(
      proved,
      sparseSelectedHome,
      selectedTicket,
      missingTicketGlyph
    ).forEach { frame ->
      val code = homeDiagnostic(frame)
      assertTrue("unsafe diagnostic=$code", code.matches(Regex("[a-z_]{1,48}")))
    }
  }

  @Test
  fun homeDiagnosticIgnoresPageBodyAndSeparatesPeerShellRejections() {
    val dynamicBody = viviRootHomeFrame(DARK, LIGHT)
    fill(dynamicBody, 12, 35, 42, 37, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(dynamicBody, 8, 45, 184, 60, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("proved_bottom_navigation", homeDiagnostic(dynamicBody))

    val missingProfile = viviRootHomeFrame(DARK, LIGHT)
    fill(missingProfile, 106, 258, 140, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("reject_peer_profile_missing", homeDiagnostic(missingProfile))

    val missingMenu = viviRootHomeFrame(DARK, LIGHT)
    fill(missingMenu, 152, 258, 186, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("reject_peer_menu_rows", homeDiagnostic(missingMenu))
  }

  @Test
  fun bottomNavigationProofSurvivesAnOddPhaseLostSeparatorInBothThemes() {
    listOf(DARK to LIGHT, LIGHT to DARK).forEach { (background, neutral) ->
      val probe = lostSeparatorBottomNavigationProbeFrame(background, neutral)
      val result = TicketVisualActionClassifier.classifyCurrent(probe)

      assertEquals("background=$background", "vivi_home", result.state)
      assertTrue(result.ticketsTabBounds != null)
      assertBoundsInSampleSpace(result.ticketsTabBounds!!)
      assertTrue(result.sliderBounds == null)
      assertTrue(result.cards.isEmpty())
      assertEquals("proved_bottom_navigation", homeDiagnostic(probe))
    }
  }

  @Test
  fun bottomNavigationProofIgnoresTheTallFragmentedNewsBanner() {
    listOf(DARK to LIGHT, LIGHT to DARK).forEach { (background, neutral) ->
      val probe = lostSeparatorBottomNavigationProbeFrame(background, neutral)
      // Sanitized aggregate geometry from the live Home banner. It crosses the bounded tab rows,
      // but it is tall and fragmented: five short qualifying rows sit between wider orange rows.
      // No text or live pixels are retained in this fixture.
      fill(probe, 0, 66, 288, 68, YELLOW, TicketVisualActionClassifier.PROBE_WIDTH)
      fill(probe, 80, 68, 146, 70, YELLOW, TicketVisualActionClassifier.PROBE_WIDTH)
      fill(probe, 100, 70, 148, 76, YELLOW, TicketVisualActionClassifier.PROBE_WIDTH)
      fill(probe, 20, 76, 224, 78, YELLOW, TicketVisualActionClassifier.PROBE_WIDTH)
      fill(probe, 0, 78, 288, 80, YELLOW, TicketVisualActionClassifier.PROBE_WIDTH)
      fill(probe, 0, 80, 254, 82, YELLOW, TicketVisualActionClassifier.PROBE_WIDTH)

      val result = TicketVisualActionClassifier.classifyCurrent(probe)
      assertEquals("background=$background", "vivi_home", result.state)
      assertTrue(result.ticketsTabBounds != null)
      assertEquals("proved_bottom_navigation", homeDiagnostic(probe))
    }
  }

  @Test
  fun bottomNavigationProofRejectsFiveHomeSamplesAndEveryMissingOrSelectedPeer() {
    val background = DARK
    val neutral = LIGHT
    val fiveHomeSamples = lostSeparatorBottomNavigationProbeFrame(
      background,
      neutral,
      selectedHomeSamples = 5
    )
    val missingTicket = lostSeparatorBottomNavigationProbeFrame(background, neutral).also {
      fill(it, 92, 516, 164, 566, background, TicketVisualActionClassifier.PROBE_WIDTH)
    }
    val missingProfile = lostSeparatorBottomNavigationProbeFrame(background, neutral).also {
      fill(it, 212, 516, 280, 566, background, TicketVisualActionClassifier.PROBE_WIDTH)
    }
    val missingMenu = lostSeparatorBottomNavigationProbeFrame(background, neutral).also {
      fill(it, 304, 516, 372, 566, background, TicketVisualActionClassifier.PROBE_WIDTH)
    }
    val competingSelectedIcon = lostSeparatorBottomNavigationProbeFrame(background, neutral).also {
      listOf(232 to 534, 234 to 534, 236 to 534, 238 to 534).forEach { (x, y) ->
        fill(it, x, y, x + 1, y + 1, YELLOW, TicketVisualActionClassifier.PROBE_WIDTH)
      }
    }
    val navigationOverlay = lostSeparatorBottomNavigationProbeFrame(background, neutral).also {
      fill(it, 0, 500, 384, 576, MID, TicketVisualActionClassifier.PROBE_WIDTH)
    }

    listOf(
      "home" to fiveHomeSamples,
      "ticket" to missingTicket,
      "profile" to missingProfile,
      "menu" to missingMenu,
      "selected_peer" to competingSelectedIcon,
      "overlay" to navigationOverlay
    ).forEach { (cue, probe) ->
      assertEquals("cue=$cue diagnostic=${homeDiagnostic(probe)}", "unknown",
        TicketVisualActionClassifier.classifyCurrent(probe).state)
    }
    assertEquals("reject_selected_home_sparse", homeDiagnostic(fiveHomeSamples))
    assertEquals("reject_peer_selected_conflict", homeDiagnostic(competingSelectedIcon))
  }

  @Test
  fun bottomNavigationSideChannelNeverOverridesARecognizedBlockerOrExistingView() {
    val blockedLow = rawTicketFrame()
    fill(blockedLow, 8, 30, 40, 45, LIGHT)
    fill(blockedLow, 13, 39, 36, 40, DARK)
    fill(blockedLow, 31, 39, 42, 44, ORANGE)
    val blockedWithShell = lostSeparatorBottomNavigationProbeFrame(
      DARK,
      LIGHT,
      base = scale(blockedLow)
    )
    assertEquals("blocked", TicketVisualActionClassifier.classifyCurrent(blockedWithShell).state)
    assertEquals("reject_blocked_surface", homeDiagnostic(blockedWithShell))
    assertEquals("home", TicketVisualActionClassifier.selectedBottomNavigationTab(blockedWithShell))

    listOf(
      "profile" to viviProfileFrame(DARK, LIGHT),
      "tickets" to viviOtherTabFrame(DARK, LIGHT, ticketsSelected = true),
      "menu" to viviOtherTabFrame(DARK, LIGHT, ticketsSelected = false)
    ).forEach { (expectedTab, shell) ->
      val blockerBody = scale(blockedLow)
      for (y in 0 until 255) {
        for (x in 0 until TicketVisualActionClassifier.SAMPLE_WIDTH) {
          shell[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] =
            blockerBody[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x]
        }
      }
      assertEquals("tab=$expectedTab", "blocked", TicketVisualActionClassifier.classifyCurrent(shell).state)
      assertEquals(expectedTab, TicketVisualActionClassifier.selectedBottomNavigationTab(shell))
    }

    val detailWithNavigation = lostSeparatorBottomNavigationProbeFrame(
      DARK,
      LIGHT,
      base = unactivatedDetailFrame()
    )
    assertEquals(
      "unactivated_detail",
      TicketVisualActionClassifier.classifyCurrent(detailWithNavigation).state
    )
    assertEquals("reject_blocked_surface", homeDiagnostic(detailWithNavigation))
    assertEquals("home", TicketVisualActionClassifier.selectedBottomNavigationTab(detailWithNavigation))

    val login = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { DARK }
    fill(login, 30, 105, 162, 170, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(login, 35, 185, 157, 205, BLUE, TicketVisualActionClassifier.SAMPLE_WIDTH)
    val loginWithNavigation = lostSeparatorBottomNavigationProbeFrame(
      DARK,
      LIGHT,
      base = login
    )
    assertEquals("login_required", TicketVisualActionClassifier.classifyCurrent(loginWithNavigation).state)
    assertEquals("reject_blocked_surface", homeDiagnostic(loginWithNavigation))
    assertEquals("home", TicketVisualActionClassifier.selectedBottomNavigationTab(loginWithNavigation))

    val today = LocalDate.now()
    val existingViews = listOf(
      IntArray(TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT) { MID },
      listFrame(listOf(Triple(today, today.plusDays(1), 100)), setOf(100)),
      unactivatedDetailFrame()
    )
    existingViews.forEach { frame ->
      assertFalse(TicketVisualActionClassifier.classifyCurrent(frame).state == "vivi_home")
    }
  }

  @Test
  fun emptySingleUseTicketsShellProvesOnlyTheTimeTabInBothThemesAndProbeSizes() {
    listOf(
      Triple(DARK, LIGHT, MID),
      Triple(LIGHT, DARK, MID)
    ).forEach { (background, foreground, muted) ->
      val frame = emptySingleUseTicketsFrame(background, foreground, muted)
      val sample = TicketVisualActionClassifier.classify(frame)
      val current = TicketVisualActionClassifier.classifyCurrent(frame)
      val highResolution = TicketVisualActionClassifier.classify(upscaleToProbe(frame))

      for ((source, result) in listOf(
        "sample" to sample,
        "current" to current,
        "probe" to highResolution
      )) {
        assertEquals("background=$background source=$source", "tickets_single_use_empty", result.state)
        assertTrue(result.timeTicketsTabBounds != null)
        assertBoundsInSampleSpace(result.timeTicketsTabBounds!!)
        assertTrue(result.ticketsTabBounds == null)
        assertTrue(result.sliderBounds == null)
        assertTrue(result.backBounds == null)
        assertTrue(result.cards.isEmpty())
      }
      assertBoundsNear(sample.timeTicketsTabBounds!!, highResolution.timeTicketsTabBounds!!)

      val target = sample.timeTicketsTabBounds!!
      val mapped = TicketCaptureGeometry.mapProbeBoundsToDevice(
        TicketVisualProbeBounds(target.left, target.top, target.right, target.bottom),
        TicketVisualActionClassifier.SAMPLE_WIDTH,
        TicketVisualActionClassifier.SAMPLE_HEIGHT,
        1080,
        2424
      )
      val centerX = (mapped.left + mapped.right) / 2
      val centerY = (mapped.top + mapped.bottom) / 2
      assertTrue(centerX in 540..1017)
      assertTrue(centerY in 349..475)
    }
  }

  @Test
  fun selectedTimeTabEmptyShellHasItsOwnExactTypedStateAndSingleUseTarget() {
    listOf(
      Triple(DARK, LIGHT, MID),
      Triple(LIGHT, DARK, MID)
    ).forEach { (background, foreground, muted) ->
      val timeSelected = emptySingleUseTicketsFrame(background, foreground, muted)
      fill(timeSelected, 5, 33, 187, 41, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(timeSelected, 96, 36, 181, 38, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      listOf(
        "sample" to TicketVisualActionClassifier.classify(timeSelected),
        "current" to TicketVisualActionClassifier.classifyCurrent(timeSelected),
        "probe" to TicketVisualActionClassifier.classify(upscaleToProbe(timeSelected))
      ).forEach { (source, result) ->
        assertEquals("background=$background source=$source", "tickets_time_empty", result.state)
        assertNull(result.timeTicketsTabBounds)
        assertTrue(result.ticketsTabBounds != null)
        assertBoundsInSampleSpace(result.ticketsTabBounds!!)
        assertNull(result.sliderBounds)
        assertTrue(result.cards.isEmpty())
      }
      assertBoundsNear(
        TicketVisualActionClassifier.classify(timeSelected).ticketsTabBounds!!,
        TicketVisualActionClassifier.classify(upscaleToProbe(timeSelected)).ticketsTabBounds!!
      )

      val target = TicketVisualActionClassifier.classify(timeSelected).ticketsTabBounds!!
      val mapped = TicketCaptureGeometry.mapProbeBoundsToDevice(
        TicketVisualProbeBounds(target.left, target.top, target.right, target.bottom),
        TicketVisualActionClassifier.SAMPLE_WIDTH,
        TicketVisualActionClassifier.SAMPLE_HEIGHT,
        1080,
        2424
      )
      val centerX = (mapped.left + mapped.right) / 2
      val centerY = (mapped.top + mapped.bottom) / 2
      assertTrue(centerX in 56..562)
      assertTrue(centerY in 349..475)
    }
  }

  @Test
  fun wrongTabSelectionAndNonemptyOrRedesignedEmptyShellFailClosed() {
    val bothTabsSelected = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(bothTabsSelected, 96, 36, 181, 38, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(bothTabsSelected).state)

    val neitherTabSelected = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(neitherTabSelected, 5, 33, 187, 41, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(neitherTabSelected).state)

    val shiftedUnderline = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(shiftedUnderline, 5, 33, 100, 41, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(shiftedUnderline, 25, 36, 90, 38, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(shiftedUnderline).state)

    val ticketsUnselected = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(ticketsUnselected, 8, 262, 84, 280, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(ticketsUnselected, 18, 265, 31, 274, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(ticketsUnselected, 64, 266, 80, 274, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(ticketsUnselected, 65, 267, 79, 273, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(ticketsUnselected).state)

    val oversizedTicketSelection = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(oversizedTicketSelection, 46, 262, 84, 280, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(oversizedTicketSelection).state)

    val noLongerEmpty = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(noLongerEmpty, 20, 90, 172, 104, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(noLongerEmpty).state)

    val missingEmptySilhouette = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(missingEmptySilhouette, 24, 125, 170, 170, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingEmptySilhouette).state)

    val missingTimeLabel = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(missingTimeLabel, 100, 18, 184, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingTimeLabel).state)

    val missingNavigationSeparator = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(missingNavigationSeparator, 0, 255, 192, 263, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals(
      "tickets_single_use_empty",
      TicketVisualActionClassifier.classifyCurrent(missingNavigationSeparator).state
    )

    val missingSeparatorAndProfile = missingNavigationSeparator.clone()
    fill(missingSeparatorAndProfile, 106, 262, 140, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingSeparatorAndProfile).state)

    val missingSeparatorAndMenu = missingNavigationSeparator.clone()
    fill(missingSeparatorAndMenu, 152, 262, 186, 283, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingSeparatorAndMenu).state)

    val missingSeparatorWithPeerSelection = missingNavigationSeparator.clone()
    fill(missingSeparatorWithPeerSelection, 108, 266, 139, 274, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(missingSeparatorWithPeerSelection).state)

    val liveShapedTimeLabelSample = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(
      liveShapedTimeLabelSample,
      150,
      26,
      160,
      28,
      MID,
      TicketVisualActionClassifier.SAMPLE_WIDTH
    )
    fill(
      liveShapedTimeLabelSample,
      154,
      28,
      160,
      29,
      MID,
      TicketVisualActionClassifier.SAMPLE_WIDTH
    )
    val timeLabelResemblesDetailClose = upscaleToProbe(liveShapedTimeLabelSample)
    drawProbeTimeLabelAliasStrokes(timeLabelResemblesDetailClose, MID)
    assertTrue(preservedHeaderHasDetailClose(timeLabelResemblesDetailClose))
    assertFalse(nativeProbeHasDetailClose(timeLabelResemblesDetailClose))
    assertEquals(
      "tickets_single_use_empty",
      TicketVisualActionClassifier.classifyCurrent(timeLabelResemblesDetailClose).state
    )

    val genuineCloseOverTimeLabel = timeLabelResemblesDetailClose.clone()
    drawProbeHeaderCloseOnOddSamples(genuineCloseOverTimeLabel, LIGHT, 152, 27)
    assertTrue(nativeProbeHasDetailClose(genuineCloseOverTimeLabel))
    assertEquals(
      "unknown",
      TicketVisualActionClassifier.classifyCurrent(genuineCloseOverTimeLabel).state
    )

    val genuineCloseOverlay = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    drawDetailClose(genuineCloseOverlay)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(genuineCloseOverlay).state)

    val aliasedCloseWithPartialShellSample = liveShapedTimeLabelSample.clone()
    fill(
      aliasedCloseWithPartialShellSample,
      24,
      125,
      170,
      170,
      DARK,
      TicketVisualActionClassifier.SAMPLE_WIDTH
    )
    val aliasedCloseWithPartialShell = upscaleToProbe(aliasedCloseWithPartialShellSample)
    drawProbeTimeLabelAliasStrokes(aliasedCloseWithPartialShell, MID)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(aliasedCloseWithPartialShell).state)

    val redesignedOverlay = emptySingleUseTicketsFrame(DARK, LIGHT, MID)
    fill(redesignedOverlay, 24, 125, 170, 170, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classifyCurrent(redesignedOverlay).state)
  }

  @Test
  fun unreadableYellowCardNeverBecomesAnonymousLatestTicket() {
    val pixels = IntArray(TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT) {
      0xfff5f5f5.toInt()
    }
    for (y in 145 until 158) {
      for (x in 12 until TicketVisualActionClassifier.SAMPLE_WIDTH - 12) {
        pixels[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = 0xffe4a21c.toInt()
      }
    }
    assertTrue(TicketVisualActionClassifier.classify(pixels).cards.isEmpty())
  }

  @Test
  fun classifierAndGlyphLoopStayInsideProbeBudget() {
    val today = LocalDate.now()
    val highResolution = upscaleToProbe(
      listFrame(
        listOf(
          Triple(today.minusDays(2), today.minusDays(1), 60),
          Triple(today, today.plusDays(1), 135),
          Triple(today.plusDays(1), today.plusDays(2), 215)
        ),
        registrationCenters = setOf(60, 135, 215)
      )
    )
    repeat(3) { TicketVisualActionClassifier.classify(highResolution) }
    val highResolutionSamples = LongArray(20) {
      val started = System.nanoTime()
      TicketVisualActionClassifier.classify(highResolution)
      (System.nanoTime() - started) / 1_000_000L
    }
    assertTrue(
      "high-resolution visual probe p95 was ${highResolutionSamples.sorted()[18]}ms",
      highResolutionSamples.sorted()[18] < 250L
    )
  }

  @Test
  fun highResolutionListPreservesSelectionAndSampleSpaceGeometry() {
    val today = LocalDate.now()
    val sampleFrame = listFrame(
      listOf(
        Triple(today, today.plusDays(1), 100),
        Triple(today.plusDays(1), today.plusDays(2), 205)
      ),
      registrationCenters = setOf(100, 205)
    )

    val sample = TicketVisualActionClassifier.classify(sampleFrame)
    val highResolution = TicketVisualActionClassifier.classify(upscaleToProbe(sampleFrame))

    assertEquals("ticket_list", highResolution.state)
    assertEquals(sample.cards.size, highResolution.cards.size)
    assertEquals(1, highResolution.cards.count { it.latest })
    assertEquals(sample.cards.single { it.latest }.anchor, highResolution.cards.single { it.latest }.anchor)
    sample.cards.zip(highResolution.cards).forEach { (expected, actual) ->
      assertEquals(expected.anchor, actual.anchor)
      assertEquals(expected.latest, actual.latest)
      assertBoundsNear(expected.bounds, actual.bounds)
      assertBoundsInSampleSpace(actual.bounds)
      assertBoundsNear(expected.registrationBounds!!, actual.registrationBounds!!)
      assertBoundsInSampleSpace(actual.registrationBounds)
    }
  }

  @Test
  fun sliderEdgesExcludeCompactPaddingAndDarkPageBorders() {
    val pixels = unactivatedDetailFrame()
    fill(pixels, 0, 164, 192, 204, LIGHT, 192)
    fill(pixels, 0, 164, 6, 204, DARK, 192)
    fill(pixels, 186, 164, 192, 204, DARK, 192)
    fill(pixels, 14, 171, 178, 193, YELLOW, 192)
    fill(pixels, 15, 172, 38, 192, DARK, 192)
    fill(pixels, 24, 180, 31, 183, LIGHT, 192)
    for (input in listOf(pixels, upscaleToProbe(pixels))) {
      val result = TicketVisualActionClassifier.classify(input)
      assertEquals("unactivated_detail", result.state)
      val bounds = result.sliderBounds!!
      assertEquals(14, bounds.left)
      assertEquals(171, bounds.top)
      assertEquals(178, bounds.right)
      assertEquals(193, bounds.bottom)
    }
  }

  @Test
  fun highResolutionUnactivatedDetailKeepsDatesOutOfIdentityAndPreservesGeometry() {
    val low = rawTicketFrame()
    fill(low, 4, 43, 44, 47, YELLOW)
    fill(low, 8, 43, 12, 47, DARK)
    val sampleFrame = scale(low)
    fill(sampleFrame, 0, 196, 192, 288, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    renderDateRange(sampleFrame, LocalDate.now(), LocalDate.now().plusDays(1), 220)
    drawDetailClose(sampleFrame)

    val sample = TicketVisualActionClassifier.classify(sampleFrame)
    val highResolution = TicketVisualActionClassifier.classify(upscaleToProbe(sampleFrame))

    assertEquals("unactivated_detail", highResolution.state)
    assertTrue(highResolution.currentAnchor.matches(Regex("d_[0-9a-f]{28}")))
    assertEquals(sample.currentAnchor, highResolution.currentAnchor)
    assertBoundsNear(sample.sliderBounds!!, highResolution.sliderBounds!!)
    assertBoundsInSampleSpace(highResolution.sliderBounds)
    assertBoundsNear(sample.backBounds!!, highResolution.backBounds!!)
    assertBoundsInSampleSpace(highResolution.backBounds)
    sample.controlCodeBounds?.let { expected ->
      assertBoundsNear(expected, highResolution.controlCodeBounds!!)
      assertBoundsInSampleSpace(highResolution.controlCodeBounds)
    }
  }

  @Test
  fun rotatingTicketCodeDoesNotReplaceTheStaticDetailIdentity() {
    val original = unactivatedDetailFrame()
    drawDetailClose(original)
    val originalSignature = TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(
      original,
      TicketVisualActionClassifier.SAMPLE_WIDTH,
      TicketVisualActionClassifier.SAMPLE_HEIGHT
    )
    val rotatedCode = original.copyOf()
    fill(
      rotatedCode,
      32,
      20,
      160,
      136,
      RED,
      TicketVisualActionClassifier.SAMPLE_WIDTH
    )
    val changedMetadata = original.copyOf()
    fill(
      changedMetadata,
      40,
      144,
      72,
      160,
      DARK,
      TicketVisualActionClassifier.SAMPLE_WIDTH
    )
    val rotatedSignature = TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(
      rotatedCode,
      TicketVisualActionClassifier.SAMPLE_WIDTH,
      TicketVisualActionClassifier.SAMPLE_HEIGHT
    )
    val changedMetadataSignature = TicketControlCodeVisualClassifier.ticketDetailStaticVisualSignature(
      changedMetadata,
      TicketVisualActionClassifier.SAMPLE_WIDTH,
      TicketVisualActionClassifier.SAMPLE_HEIGHT
    )

    assertTrue(originalSignature.matches(Regex("[0-9a-f]{24}")))
    assertEquals(originalSignature, rotatedSignature)
    assertFalse(originalSignature == changedMetadataSignature)
  }

  @Test
  fun currentViviWordmarkProvidesControlCodeGeometryInBothHeaderThemes() {
    listOf(
      DARK to LIGHT,
      LIGHT to DARK
    ).forEach { (header, logo) ->
      val frame = unactivatedDetailFrame()
      fill(frame, 4, 2, 96, 34, header, TicketVisualActionClassifier.SAMPLE_WIDTH)
      drawViviWordmark(frame, logo)
      // The current route strip can leave a neutral two-pixel separator at the bottom of the
      // bounded header probe. It must not stretch or suppress the wordmark geometry.
      fill(frame, 16, 20, 96, 22, logo, TicketVisualActionClassifier.SAMPLE_WIDTH)
      drawDetailClose(frame, if (header == DARK) LIGHT else DARK)

      val sample = TicketVisualActionClassifier.classify(frame)
      val highResolution = TicketVisualActionClassifier.classify(upscaleToProbe(frame))

      assertEquals("unactivated_detail", sample.state)
      assertTrue(sample.controlCodeBounds != null)
      assertBoundsInSampleSpace(sample.controlCodeBounds!!)
      assertBoundsNear(sample.controlCodeBounds!!, highResolution.controlCodeBounds!!)
      assertTrue(sample.controlCodeBounds!!.right <= TicketVisualActionClassifier.SAMPLE_WIDTH / 2)
      assertTrue(sample.controlCodeBounds!!.bottom <= 34)
    }
  }

  @Test
  fun arbitraryTopLeftHeaderInkCannotAuthorizeControlCodeTap() {
    val solid = unactivatedDetailFrame()
    fill(solid, 4, 2, 96, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(solid, 12, 7, 58, 20, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertTrue(TicketVisualActionClassifier.classify(solid).controlCodeBounds == null)

    val singleIcon = unactivatedDetailFrame()
    fill(singleIcon, 4, 2, 96, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(singleIcon, 14, 8, 25, 19, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(singleIcon, 16, 20, 96, 22, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertTrue(TicketVisualActionClassifier.classify(singleIcon).controlCodeBounds == null)
  }

  @Test
  fun detailFixturesSeparateActivatedUnactivatedAndBlockedStates() {
    val activated = scale(rawTicketFrame())
    fill(activated, 0, 164, 192, 288, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    renderDateRange(activated, LocalDate.now(), LocalDate.now().plusDays(1), 205)
    assertEquals(1, TicketVisualDateGlyphRecognizer.recognize(activated, 192, 288).size)
    val activatedResult = TicketVisualActionClassifier.classify(activated)
    assertEquals("activated_detail", activatedResult.state)
    assertTrue(activatedResult.currentAnchor.matches(Regex("d_[0-9a-f]{28}")))

    val unactivatedLow = rawTicketFrame()
    fill(unactivatedLow, 4, 43, 44, 47, YELLOW)
    fill(unactivatedLow, 8, 43, 12, 47, DARK)
    val unactivatedPixels = scale(unactivatedLow)
    fill(unactivatedPixels, 0, 196, 192, 288, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    renderDateRange(unactivatedPixels, LocalDate.now(), LocalDate.now().plusDays(1), 220)
    val unactivated = TicketVisualActionClassifier.classify(unactivatedPixels)
    assertEquals("unactivated_detail", unactivated.state)
    assertTrue(unactivated.sliderBounds != null)
    assertTrue(unactivated.currentAnchor.matches(Regex("d_[0-9a-f]{28}")))

    val redesignedLow = rawTicketFrame()
    fill(redesignedLow, 7, 36, 41, 40, MID)
    fill(redesignedLow, 4, 43, 44, 47, YELLOW)
    fill(redesignedLow, 8, 43, 12, 47, DARK)
    val redesigned = scale(redesignedLow)
    drawDetailClose(redesigned)
    assertFalse(
      TicketControlCodeVisualClassifier.classify(redesignedLow) ==
        TicketControlCodeVisualClassifier.RAW_TICKET
    )
    val redesignedResult = TicketVisualActionClassifier.classify(redesigned)
    assertEquals("unactivated_detail", redesignedResult.state)
    assertTrue(redesignedResult.sliderBounds != null)
    assertTrue(redesignedResult.backBounds != null)
    assertEquals(160, redesignedResult.backBounds!!.left)
    assertEquals(0, redesignedResult.backBounds!!.top)
    assertEquals(188, redesignedResult.backBounds!!.right)
    assertEquals(24, redesignedResult.backBounds!!.bottom)

    val currentThumbBeforeTrackLow = rawTicketFrame()
    fill(currentThumbBeforeTrackLow, 11, 46, 44, 52, YELLOW)
    fill(currentThumbBeforeTrackLow, 4, 46, 11, 52, DARK)
    val currentThumbBeforeTrack = TicketVisualActionClassifier.classify(scale(currentThumbBeforeTrackLow))
    assertEquals("unactivated_detail", currentThumbBeforeTrack.state)
    assertTrue(currentThumbBeforeTrack.sliderBounds != null)
    assertTrue(currentThumbBeforeTrack.sliderBounds!!.left <= 16)

    val refreshingDetailLow = IntArray(48 * 72) { MID }
    fill(refreshingDetailLow, 0, 0, 48, 6, DARK)
    fill(refreshingDetailLow, 1, 6, 47, 13, RED)
    fill(refreshingDetailLow, 11, 46, 44, 52, YELLOW)
    fill(refreshingDetailLow, 4, 46, 11, 52, DARK)
    val refreshingDetail = TicketVisualActionClassifier.classify(scale(refreshingDetailLow))
    assertEquals("unknown", refreshingDetail.state)
    assertTrue(refreshingDetail.sliderBounds == null)

    val blockedLow = rawTicketFrame()
    fill(blockedLow, 8, 30, 40, 45, LIGHT)
    fill(blockedLow, 13, 39, 36, 40, DARK)
    fill(blockedLow, 31, 39, 42, 44, ORANGE)
    assertEquals("blocked", TicketVisualActionClassifier.classify(scale(blockedLow)).state)

    val oldGeneratedResult = rawTicketFrame()
    fill(oldGeneratedResult, 7, 36, 41, 40, DARK)
    fill(oldGeneratedResult, 36, 37, 40, 39, LIGHT)
    assertTrue(TicketControlCodeVisualClassifier.generatedResultCloseBounds(oldGeneratedResult).isNotBlank())
    assertEquals("blocked", TicketVisualActionClassifier.classify(scale(oldGeneratedResult)).state)
  }

  @Test
  fun detailCloseDetectorSupportsLightAndDarkHeadersAndCentersTheSafeTap() {
    val darkHeader = unactivatedDetailFrame()
    fill(darkHeader, 144, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawDetailClose(darkHeader, LIGHT)
    val darkHeaderResult = TicketVisualActionClassifier.classify(darkHeader)

    assertEquals("unactivated_detail", darkHeaderResult.state)
    assertEquals(174, (darkHeaderResult.backBounds!!.left + darkHeaderResult.backBounds!!.right) / 2)
    assertEquals(12, (darkHeaderResult.backBounds!!.top + darkHeaderResult.backBounds!!.bottom) / 2)

    val lightHeader = unactivatedDetailFrame()
    fill(lightHeader, 144, 2, 190, 34, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawDetailClose(lightHeader, DARK)
    val lightHeaderResult = TicketVisualActionClassifier.classify(lightHeader)

    assertEquals("unactivated_detail", lightHeaderResult.state)
    assertEquals(174, (lightHeaderResult.backBounds!!.left + lightHeaderResult.backBounds!!.right) / 2)
    assertEquals(12, (lightHeaderResult.backBounds!!.top + lightHeaderResult.backBounds!!.bottom) / 2)
    assertBoundsNear(
      lightHeaderResult.backBounds!!,
      TicketVisualActionClassifier.classify(upscaleToProbe(lightHeader)).backBounds!!
    )

    val shifted = unactivatedDetailFrame()
    fill(shifted, 144, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawDetailClose(shifted, LIGHT, 166, 16)
    val shiftedBounds = TicketVisualActionClassifier.classify(shifted).backBounds!!
    assertEquals(166, (shiftedBounds.left + shiftedBounds.right) / 2)
    assertEquals(16, (shiftedBounds.top + shiftedBounds.bottom) / 2)
  }

  @Test
  fun detailCloseDetectorFailsClosedForNonXAndAmbiguousHeaderControls() {
    val solidControl = unactivatedDetailFrame()
    fill(solidControl, 144, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(solidControl, 169, 7, 180, 18, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertTrue(TicketVisualActionClassifier.classify(solidControl).backBounds == null)

    val singleSlash = unactivatedDetailFrame()
    fill(singleSlash, 144, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    for (offset in -5..5) {
      singleSlash[(12 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + 174 + offset] = LIGHT
    }
    assertTrue(TicketVisualActionClassifier.classify(singleSlash).backBounds == null)

    val ambiguous = unactivatedDetailFrame()
    fill(ambiguous, 144, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawDetailClose(ambiguous, LIGHT, 160, 12)
    drawDetailClose(ambiguous, LIGHT, 178, 12)
    assertTrue(TicketVisualActionClassifier.classify(ambiguous).backBounds == null)
  }

  @Test
  fun detailCloseDetectorAcceptsCurrentLowContrastAntialiasing() {
    val pixels = unactivatedDetailFrame()
    val header = rgb(54, 55, 56)
    val close = rgb(86, 87, 88)
    fill(pixels, 144, 2, 190, 34, header, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawDetailClose(pixels, close, 174, 12)

    val result = TicketVisualActionClassifier.classify(pixels)

    assertEquals("unactivated_detail", result.state)
    assertEquals(174, (result.backBounds!!.left + result.backBounds!!.right) / 2)
    assertEquals(12, (result.backBounds!!.top + result.backBounds!!.bottom) / 2)
  }

  @Test
  fun detailCloseTemplateAcceptsFragmentedAntialiasingButKeepsExactCentre() {
    val pixels = unactivatedDetailFrame()
    fill(pixels, 144, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    for (offset in -6..6 step 2) {
      pixels[(12 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + 174 + offset] = LIGHT
      pixels[(12 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + 174 - offset] = LIGHT
    }

    val bounds = TicketVisualActionClassifier.classify(pixels).backBounds!!

    assertEquals(174, (bounds.left + bounds.right) / 2)
    assertEquals(12, (bounds.top + bounds.bottom) / 2)
  }

  @Test
  fun detailCloseTemplateRejectsNegativeAndBottomEdgeSamplesBeforeIndexing() {
    listOf(
      100 to 5,
      100 to (TicketVisualActionClassifier.SAMPLE_HEIGHT - 6),
      5 to 100,
      (TicketVisualActionClassifier.SAMPLE_WIDTH - 6) to 100
    ).forEach { (centerX, centerY) ->
      val pixels = IntArray(
        TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
      ) { DARK }
      val radius = 9
      pixels[centerY * TicketVisualActionClassifier.SAMPLE_WIDTH + centerX] = LIGHT
      for (step in 1..radius) {
        listOf(-step to -step, step to -step, -step to step, step to step).forEach { (dx, dy) ->
          val x = (centerX + dx).coerceIn(0, TicketVisualActionClassifier.SAMPLE_WIDTH - 1)
          val y = (centerY + dy).coerceIn(0, TicketVisualActionClassifier.SAMPLE_HEIGHT - 1)
          pixels[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = LIGHT
        }
      }

      assertEquals(0, invokeDetailCloseTemplateScore(pixels, centerX, centerY, radius))
    }
  }

  @Test
  fun currentFarRightCompactCloseIsProvedAfterActionProbeScaling() {
    val pixels = unactivatedDetailFrame()
    fill(pixels, 144, 2, 190, 34, rgb(54, 55, 56), TicketVisualActionClassifier.SAMPLE_WIDTH)
    for (offset in -2..2) {
      pixels[(9 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + 166 + offset] = LIGHT
      pixels[(9 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + 166 - offset] = LIGHT
    }

    val bounds = TicketVisualActionClassifier.classify(pixels).backBounds!!

    assertEquals(166, (bounds.left + bounds.right) / 2)
    assertEquals(9, (bounds.top + bounds.bottom) / 2)
  }

  @Test
  fun twoCompactFarRightClosesRemainAmbiguous() {
    val pixels = unactivatedDetailFrame()
    fill(pixels, 144, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    for (centerX in listOf(160, 176)) {
      for (offset in -2..2) {
        pixels[(9 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + centerX + offset] = LIGHT
        pixels[(9 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + centerX - offset] = LIGHT
      }
    }

    assertTrue(TicketVisualActionClassifier.classify(pixels).backBounds == null)
  }

  @Test
  fun unrelatedLeftHeaderInkIsNeverAcceptedAsTheCloseControl() {
    val pixels = unactivatedDetailFrame()
    fill(pixels, 2, 2, 190, 34, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    for (offset in -5..5) {
      pixels[(14 + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + 18 + kotlin.math.abs(offset)] = LIGHT
    }
    for (x in 18..29) pixels[14 * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = LIGHT

    assertTrue(TicketVisualActionClassifier.classify(pixels).backBounds == null)

    drawDetailClose(pixels, LIGHT, 174, 12)
    val exactClose = TicketVisualActionClassifier.classify(pixels).backBounds!!
    assertEquals(174, (exactClose.left + exactClose.right) / 2)
  }

  @Test
  fun nativeProbePreservesTheThinCurrentCloseThatNearestSamplingMisses() {
    val probe = upscaleToProbe(unactivatedDetailFrame())
    fill(probe, 288, 4, 380, 68, rgb(54, 55, 56), TicketVisualActionClassifier.PROBE_WIDTH)
    for (offset in -6..6) {
      probe[(18 + offset) * TicketVisualActionClassifier.PROBE_WIDTH + 333 + offset] = LIGHT
      probe[(18 + offset) * TicketVisualActionClassifier.PROBE_WIDTH + 333 - offset] = LIGHT
    }

    val bounds = TicketVisualActionClassifier.classify(probe).backBounds!!

    assertEquals(166, (bounds.left + bounds.right) / 2)
    assertEquals(9, (bounds.top + bounds.bottom) / 2)
  }

  @Test
  fun loginFixtureIsRecognizedButDesignDriftStillFailsClosed() {
    val login = IntArray(TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT) { DARK }
    fill(login, 30, 105, 162, 170, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(login, 35, 185, 157, 205, BLUE, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("login_required", TicketVisualActionClassifier.classify(login).state)

    val drift = login.copyOf()
    fill(drift, 35, 185, 157, 205, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classify(drift).state)
  }

  @Test
  fun currentDarkLoginRequiresLogoNeutralFormAndFullWidthGuestBar() {
    val background = rgb(47, 54, 57)
    val form = rgb(33, 40, 43)
    val login = IntArray(TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT) { background }
    // Production renders the logo as a thin outlined mark rather than a solid rectangle.
    fill(login, 56, 55, 133, 57, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(login, 56, 68, 133, 70, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(login, 56, 57, 61, 68, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(login, 128, 57, 133, 68, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(login, 10, 90, 182, 175, form, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(login, 0, 257, 192, 281, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)

    assertEquals("login_required", TicketVisualActionClassifier.classify(login).state)
    assertEquals("login_required", TicketVisualActionClassifier.classify(upscaleToProbe(login)).state)

    val missingLogo = login.copyOf()
    fill(missingLogo, 38, 55, 158, 102, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classify(missingLogo).state)

    val tooSparseLogo = login.copyOf()
    fill(tooSparseLogo, 38, 55, 158, 102, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(tooSparseLogo, 56, 55, 61, 70, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(tooSparseLogo, 128, 55, 133, 70, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classify(tooSparseLogo).state)

    val missingNeutralForm = login.copyOf()
    fill(missingNeutralForm, 10, 90, 182, 175, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertTrue(TicketVisualActionClassifier.classify(missingNeutralForm).state != "login_required")

    val insetGuestAction = login.copyOf()
    fill(insetGuestAction, 0, 240, 192, 283, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(insetGuestAction, 20, 257, 172, 281, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertEquals("unknown", TicketVisualActionClassifier.classify(insetGuestAction).state)
  }

  @Test
  fun latestSelectionIgnoresExpiredAndNonRegistrationCardsAcrossMidnight() {
    val today = LocalDate.now()
    val expired = today.minusDays(2) to today.minusDays(1)
    val current = today to today.plusDays(1)
    val upcoming = today.plusDays(1) to today.plusDays(2)
    val frame = listFrame(
      listOf(
        Triple(expired.first, expired.second, 60),
        Triple(current.first, current.second, 135),
        Triple(upcoming.first, upcoming.second, 215)
      ),
      registrationCenters = setOf(60, 135, 215)
    )
    val result = TicketVisualActionClassifier.classify(frame)
    assertEquals("ticket_list", result.state)
    assertEquals(3, result.cards.size)
    assertEquals(1, result.cards.count { it.latest })
    assertTrue(result.cards.single { it.latest }.bounds.top > 150)

    val newerWithoutRegistration = listFrame(
      listOf(
        Triple(current.first, current.second, 100),
        Triple(upcoming.first, upcoming.second, 205)
      ),
      registrationCenters = setOf(100)
    )
    val eligible = TicketVisualActionClassifier.classify(newerWithoutRegistration)
    assertEquals(1, eligible.cards.count { it.latest })
    assertTrue(eligible.cards.single { it.latest }.registrationBounds != null)
    assertTrue(eligible.cards.single { it.latest }.bounds.top < 100)
  }

  @Test
  fun currentOnlyProofRecognizesListLayoutWithoutResolvingDatesOrCards() {
    val today = LocalDate.now()
    val frame = listFrame(
      listOf(
        Triple(today, today.plusDays(1), 100),
        Triple(today.plusDays(1), today.plusDays(2), 205)
      ),
      registrationCenters = setOf(100, 205)
    )

    val result = TicketVisualActionClassifier.classifyCurrent(frame)

    assertEquals("ticket_list", result.state)
    assertTrue(result.cards.isEmpty())
    assertTrue(TicketVisualActionClassifier.currentVisualDiagnostic(frame).contains("date_probe_disabled"))
  }

  @Test
  fun currentListChromeWinsBeforeSliderLikeCardGeometry() {
    val frame = unactivatedDetailFrame()
    drawListChrome(frame)
    // Production-shaped list action geometry: a wide orange button with a separate dark marker.
    // It deliberately overlaps the detail slider scan window and retains an Aztec-like card body.
    fill(frame, 12, 148, 180, 168, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(frame, 16, 151, 34, 165, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)

    val current = TicketVisualActionClassifier.classifyCurrent(frame)

    assertEquals("ticket_list", current.state)
    assertTrue(current.sliderBounds == null)
    assertTrue(current.cards.isEmpty())
  }

  @Test
  fun currentProductionRedListHeaderWinsBeforeSliderLikeCardGeometry() {
    val frame = unactivatedDetailFrame()
    drawListChrome(frame, RED)
    fill(frame, 12, 148, 180, 168, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(frame, 16, 151, 34, 165, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)

    val current = TicketVisualActionClassifier.classifyCurrent(frame)

    assertEquals("ticket_list", current.state)
    assertTrue(current.sliderBounds == null)
  }

  @Test
  fun sliderLikeBandWithoutListChromeOrTicketDetailBaseFailsClosed() {
    val frame = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { LIGHT }
    // Preserve only the broad current colored header plus the misleading list action geometry.
    // With no ticket-code graphic this is not sufficient proof of an unactivated detail.
    fill(frame, 4, 0, 188, 40, RED, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(frame, 12, 148, 180, 168, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(frame, 16, 151, 34, 165, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)

    val current = TicketVisualActionClassifier.classifyCurrent(frame)

    assertEquals("unknown", current.state)
    assertTrue(current.sliderBounds == null)
  }

  @Test
  fun activatedJourneyStatusTargetIsSeparateFromTheCardAndRegistrationControlInBothThemes() {
    val today = LocalDate.now()
    val center = 120
    listOf(
      Triple(LIGHT, DARK, rgb(230, 245, 245)),
      Triple(DARK, LIGHT, rgb(36, 55, 55))
    ).forEach { (background, glyph, statusBackground) ->
      val frame = listFrame(
        listOf(Triple(today, today.plusDays(30), center)),
        registrationCenters = setOf(center),
        background = background,
        glyph = glyph
      )
      // Current ViVi paints a registered-status row with a compact orange check marker above
      // the full-width orange control for preparing the next journey on the same monthly card.
      fill(frame, 10, center + 5, 182, center + 17, statusBackground, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(frame, 160, center + 7, 174, center + 16, ORANGE, TicketVisualActionClassifier.SAMPLE_WIDTH)
      fill(frame, 12, center + 18, 180, center + 25, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)

      val result = TicketVisualActionClassifier.classify(frame)

      assertEquals("ticket_list", result.state)
      assertEquals(1, result.cards.size)
      val card = result.cards.single()
      assertTrue(card.latest)
      assertTrue(card.registrationBounds != null)
      assertTrue(card.activatedDetailBounds != null)
      assertFalse(card.activatedDetailBounds === card.bounds)
      assertTrue(card.activatedDetailBounds!!.left >= 150)
      assertTrue(card.activatedDetailBounds!!.bottom <= card.registrationBounds!!.top)
    }
  }

  @Test
  fun duplicateActivatedStatusMarkersFailClosedWithoutFallingBackToTheCardBody() {
    val today = LocalDate.now()
    val center = 120
    val frame = listFrame(
      listOf(Triple(today, today.plusDays(30), center)),
      registrationCenters = setOf(center)
    )
    fill(frame, 145, center + 7, 153, center + 15, ORANGE, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(frame, 166, center + 7, 174, center + 15, ORANGE, TicketVisualActionClassifier.SAMPLE_WIDTH)

    val card = TicketVisualActionClassifier.classify(frame).cards.single()

    assertTrue(card.registrationBounds != null)
    assertTrue(card.activatedDetailBounds == null)
  }

  @Test
  fun identicalLatestDatesStayAmbiguous() {
    val tomorrow = LocalDate.now().plusDays(1)
    val frame = listFrame(
      listOf(
        Triple(tomorrow, tomorrow.plusDays(1), 85),
        Triple(tomorrow, tomorrow.plusDays(1), 205)
      ),
      registrationCenters = setOf(85, 205)
    )
    val result = TicketVisualActionClassifier.classify(frame)
    assertEquals("ticket_list", result.state)
    assertEquals(2, result.cards.size)
    assertFalse(result.cards.any { it.latest })
  }

  private fun listFrame(
    ranges: List<Triple<LocalDate, LocalDate, Int>>,
    registrationCenters: Set<Int>,
    background: Int = LIGHT,
    glyph: Int = DARK
  ): IntArray {
    val pixels = IntArray(TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT) { background }
    drawListChrome(pixels)
    ranges.forEach { (from, until, center) ->
      renderDateRange(pixels, from, until, center - 3, glyph)
      if (center in registrationCenters) {
        fill(pixels, 12, center + 18, 180, center + 25, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
      }
    }
    return pixels
  }

  private fun viviHomeFrame(background: Int, neutral: Int): IntArray {
    val pixels = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { background }
    // Sanitized route-home chrome only: dynamic Search action plus the complete bottom navigation.
    // It contains no app text, journey, or ticket data.
    fill(pixels, 14, 180, 178, 204, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 0, 255, 192, 257, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawCurrentSelectedHome(pixels)
    fill(pixels, 64, 266, 80, 274, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 65, 267, 79, 273, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 115, 264, 127, 278, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 117, 266, 125, 276, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 264, 179, 266, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 269, 179, 271, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 274, 179, 276, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    return pixels
  }

  private fun viviProfileFrame(background: Int, neutral: Int): IntArray {
    val pixels = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { background }
    fill(pixels, 0, 255, 192, 257, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawCurrentHome(pixels, neutral)
    fill(pixels, 64, 265, 81, 274, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 65, 266, 80, 273, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 115, 264, 127, 278, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 117, 266, 125, 276, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 264, 179, 266, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 269, 179, 271, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 274, 179, 276, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    return pixels
  }

  private fun viviOtherTabFrame(
    background: Int,
    neutral: Int,
    ticketsSelected: Boolean
  ): IntArray {
    val pixels = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { background }
    fill(pixels, 0, 255, 192, 257, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawCurrentHome(pixels, neutral)
    fill(
      pixels,
      64,
      265,
      81,
      274,
      if (ticketsSelected) YELLOW else neutral,
      TicketVisualActionClassifier.SAMPLE_WIDTH
    )
    fill(pixels, 65, 266, 80, 273, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 115, 264, 127, 278, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 117, 266, 125, 276, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    val menuColor = if (ticketsSelected) neutral else YELLOW
    fill(pixels, 163, 264, 179, 266, menuColor, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 269, 179, 271, menuColor, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 274, 179, 276, menuColor, TicketVisualActionClassifier.SAMPLE_WIDTH)
    return pixels
  }

  private fun homeDiagnostic(pixels: IntArray): String =
    TicketVisualActionClassifier.currentVisualDiagnostic(pixels).substringAfter("_vivi_home_gate_")

  private fun viviRootHomeFrame(background: Int, neutral: Int): IntArray {
    val pixels = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { background }
    // Sanitized current route-root chrome. There is deliberately no route text or wide action:
    // authority comes from the full separator and four stable bottom-navigation silhouettes.
    fill(pixels, 0, 255, 192, 257, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawCurrentSelectedHome(pixels)
    fill(pixels, 64, 266, 80, 274, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 65, 267, 79, 273, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 115, 264, 127, 278, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 117, 266, 125, 276, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 264, 179, 266, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 269, 179, 271, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 274, 179, 276, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    return pixels
  }

  private fun livePixelBottomNavigationHomeFrame(): IntArray {
    val pixels = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { DARK }
    // Sanitized from the current rooted route-planning frame after the production 4/200/3/3 crop
    // and bounded probe reduction. Content and text are omitted; only the four bottom-navigation
    // silhouettes needed for semantic proof remain.
    fill(pixels, 0, 260, 192, 261, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)

    drawCurrentSelectedHome(pixels)

    fill(pixels, 64, 265, 81, 274, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 65, 266, 80, 273, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 115, 265, 127, 278, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 117, 267, 125, 276, DARK, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 265, 179, 267, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 270, 179, 272, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 275, 179, 277, LIGHT, TicketVisualActionClassifier.SAMPLE_WIDTH)
    return pixels
  }

  private fun lostSeparatorBottomNavigationProbeFrame(
    background: Int,
    neutral: Int,
    selectedHomeSamples: Int = 6,
    base: IntArray? = null
  ): IntArray {
    val sample = base?.copyOf() ?: IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { background }
    require(sample.size == TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT)

    // Sanitized bottom-navigation-only fixture. The divider exists only on an odd native-probe
    // row, so the production 384-to-192 point reduction intentionally loses it. No live pixels are
    // retained.
    fill(sample, 0, 250, 192, 288, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    val homeOutline = listOf(
      22 to 266,
      25 to 266,
      21 to 267,
      27 to 267,
      28 to 267,
      19 to 268,
      28 to 268,
      28 to 269,
      23 to 270,
      24 to 270,
      28 to 270,
      28 to 271,
      25 to 272,
      28 to 272
    )
    homeOutline.take(selectedHomeSamples.coerceIn(0, homeOutline.size)).forEach { (x, y) ->
      sample[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = YELLOW
    }
    fill(sample, 64, 265, 81, 274, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(sample, 65, 266, 80, 273, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(sample, 115, 265, 127, 278, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(sample, 117, 267, 125, 276, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(sample, 163, 265, 179, 267, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(sample, 163, 270, 179, 272, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(sample, 163, 275, 179, 277, neutral, TicketVisualActionClassifier.SAMPLE_WIDTH)

    return upscaleToProbe(sample).also { probe ->
      fill(
        probe,
        0,
        521,
        TicketVisualActionClassifier.PROBE_WIDTH,
        522,
        MID,
        TicketVisualActionClassifier.PROBE_WIDTH
      )
    }
  }

  private fun drawCurrentSelectedHome(pixels: IntArray) = drawCurrentHome(pixels, YELLOW)

  private fun drawCurrentHome(pixels: IntArray, color: Int) {
    // The current cropped/reduced frame retains fourteen orange samples spanning the Home outline.
    // Only these content-free coordinates are retained; no live page pixels or text are fixtures.
    listOf(
      22 to 266,
      25 to 266,
      21 to 267,
      27 to 267,
      28 to 267,
      19 to 268,
      28 to 268,
      28 to 269,
      23 to 270,
      24 to 270,
      28 to 270,
      28 to 271,
      25 to 272,
      28 to 272
    ).forEach { (x, y) ->
      pixels[y * TicketVisualActionClassifier.SAMPLE_WIDTH + x] = color
    }
  }

  private fun emptySingleUseTicketsFrame(
    background: Int,
    foreground: Int,
    muted: Int
  ): IntArray {
    val pixels = IntArray(
      TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) { background }
    // Sanitized Tickets-shell chrome only. These strokes preserve the current selected-tab,
    // empty-state, and bottom-navigation silhouettes without retaining any app text or ticket data.
    fill(pixels, 20, 25, 87, 27, foreground, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 20, 27, 26, 30, foreground, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 80, 27, 87, 30, foreground, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 118, 26, 150, 28, muted, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 118, 28, 124, 29, muted, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 144, 28, 150, 29, muted, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 11, 36, 96, 38, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)

    fill(pixels, 34, 147, 159, 150, muted, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 34, 144, 40, 155, muted, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 153, 144, 159, 155, muted, TicketVisualActionClassifier.SAMPLE_WIDTH)

    fill(pixels, 0, 260, 192, 262, muted, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 19, 265, 31, 274, foreground, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 21, 267, 29, 274, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 64, 266, 80, 274, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 65, 267, 79, 273, background, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 115, 265, 126, 274, foreground, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 266, 177, 268, foreground, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 163, 271, 177, 273, foreground, TicketVisualActionClassifier.SAMPLE_WIDTH)
    return pixels
  }

  private fun drawListChrome(pixels: IntArray, cardHeader: Int = YELLOW) {
    // Sanitized current-layout chrome only: selected-tab underline, separated top card header,
    // and the fixed lower navigation divider. No ticket text, dates, pixels, or identifiers.
    fill(pixels, 62, 36, 126, 38, YELLOW, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 8, 45, 184, 60, cardHeader, TicketVisualActionClassifier.SAMPLE_WIDTH)
    fill(pixels, 0, 260, 192, 262, MID, TicketVisualActionClassifier.SAMPLE_WIDTH)
  }

  private fun renderDateRange(
    pixels: IntArray,
    from: LocalDate,
    until: LocalDate,
    top: Int,
    color: Int = DARK
  ) {
    val format = DateTimeFormatter.ofPattern("ddMMyyyy")
    val value = from.format(format) + until.format(format)
    value.forEachIndexed { index, character ->
      templates.getValue(character).forEachIndexed { y, row ->
        row.forEachIndexed { x, bit ->
          if (bit == '#') pixels[(top + y) * TicketVisualActionClassifier.SAMPLE_WIDTH + 30 + index * 6 + x] = color
        }
      }
    }
  }

  private fun rawTicketFrame(): IntArray {
    val pixels = IntArray(48 * 72) { MID }
    fill(pixels, 0, 0, 48, 10, DARK, 48)
    fill(pixels, 2, 2, 19, 7, LIGHT, 48)
    fill(pixels, 1, 8, 47, 15, RED, 48)
    for (y in 14 until 34) for (x in 8 until 40) pixels[y * 48 + x] = if ((x + y) % 2 == 0) DARK else LIGHT
    for (y in 36 until 40) for (x in 7 until 41) pixels[y * 48 + x] = if (x % 5 == 0) DARK else LIGHT
    return pixels
  }

  private fun scale(source: IntArray): IntArray = IntArray(
    TicketVisualActionClassifier.SAMPLE_WIDTH * TicketVisualActionClassifier.SAMPLE_HEIGHT
  ) { index ->
    val x = index % TicketVisualActionClassifier.SAMPLE_WIDTH
    val y = index / TicketVisualActionClassifier.SAMPLE_WIDTH
    source[(y / 4) * 48 + x / 4]
  }

  private fun upscaleToProbe(source: IntArray): IntArray = IntArray(
    TicketVisualActionClassifier.PROBE_WIDTH * TicketVisualActionClassifier.PROBE_HEIGHT
  ) { index ->
    val x = index % TicketVisualActionClassifier.PROBE_WIDTH
    val y = index / TicketVisualActionClassifier.PROBE_WIDTH
    source[(y / 2) * TicketVisualActionClassifier.SAMPLE_WIDTH + x / 2]
  }

  private fun assertBoundsNear(
    expected: TicketVisualActionClassifier.Bounds,
    actual: TicketVisualActionClassifier.Bounds,
    tolerance: Int = 1
  ) {
    assertTrue(kotlin.math.abs(expected.left - actual.left) <= tolerance)
    assertTrue(kotlin.math.abs(expected.top - actual.top) <= tolerance)
    assertTrue(kotlin.math.abs(expected.right - actual.right) <= tolerance)
    assertTrue(kotlin.math.abs(expected.bottom - actual.bottom) <= tolerance)
  }

  private fun assertBoundsInSampleSpace(bounds: TicketVisualActionClassifier.Bounds) {
    assertTrue(bounds.left >= 0)
    assertTrue(bounds.top >= 0)
    assertTrue(bounds.right <= TicketVisualActionClassifier.SAMPLE_WIDTH)
    assertTrue(bounds.bottom <= TicketVisualActionClassifier.SAMPLE_HEIGHT)
    assertTrue(bounds.right > bounds.left)
    assertTrue(bounds.bottom > bounds.top)
  }

  private fun unactivatedDetailFrame(): IntArray {
    val low = rawTicketFrame()
    fill(low, 4, 43, 44, 47, YELLOW)
    fill(low, 8, 43, 12, 47, DARK)
    return scale(low)
  }

  private fun drawDetailClose(
    pixels: IntArray,
    color: Int = LIGHT,
    centerX: Int = 174,
    centerY: Int = 12
  ) {
    for (offset in -5..5) {
      pixels[(centerY + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + centerX + offset] = color
      pixels[(centerY + offset) * TicketVisualActionClassifier.SAMPLE_WIDTH + centerX - offset] = color
    }
  }

  private fun drawProbeHeaderCloseOnOddSamples(
    pixels: IntArray,
    color: Int,
    centerX: Int,
    centerY: Int
  ) {
    for (offset in -5..5) {
      val y = (centerY + offset) * 2 + 1
      val firstX = (centerX + offset) * 2 + 1
      val secondX = (centerX - offset) * 2 + 1
      pixels[y * TicketVisualActionClassifier.PROBE_WIDTH + firstX] = color
      pixels[y * TicketVisualActionClassifier.PROBE_WIDTH + secondX] = color
    }
  }

  private fun drawProbeTimeLabelAliasStrokes(pixels: IntArray, color: Int) {
    // Sanitized anonymous label strokes shaped like the current high-resolution header band.
    // No phase contains an X, but selecting the strongest pixel from each native 2x2 cell forms
    // the same compact false X that the live Time-tickets label produced after header preservation.
    fill(
      pixels,
      142 * 2,
      20 * 2,
      170 * 2,
      34 * 2,
      DARK,
      TicketVisualActionClassifier.PROBE_WIDTH
    )
    fun drawPhase(offsetX: Int, offsetY: Int, rows: List<String>) {
      rows.forEachIndexed { row, pattern ->
        pattern.forEachIndexed { column, value ->
          if (value != '#') return@forEachIndexed
          val x = (144 + column) * 2 + offsetX
          val y = (25 + row) * 2 + offsetY
          pixels[y * TicketVisualActionClassifier.PROBE_WIDTH + x] = color
        }
      }
    }
    drawPhase(
      0,
      0,
      listOf(
        "..........................",
        "..##########.###...........",
        "..###.##.###.##............",
        "..#...##.#...#.#...........",
        "#........................."
      )
    )
    drawPhase(
      1,
      0,
      listOf(
        "..........................",
        ".#######.######............",
        ".###..#..######............",
        ".##...#..#..#.##...........",
        ".........................."
      )
    )
    drawPhase(
      0,
      1,
      listOf(
        "..###.###.##...............",
        "..#...##.#...#.#...........",
        "..###.##.###..##...........",
        "#####.##.###.###...........",
        ".........................."
      )
    )
    drawPhase(
      1,
      1,
      listOf(
        "..##.###.##................",
        ".##...#..#..####...........",
        ".###..#..##..##............",
        "#####.#..######............",
        ".........................."
      )
    )
    val faintAntialias = rgb(18, 18, 18)
    pixels[(29 * 2) * TicketVisualActionClassifier.PROBE_WIDTH + 149 * 2] = faintAntialias
    pixels[(29 * 2 + 1) * TicketVisualActionClassifier.PROBE_WIDTH + 155 * 2 + 1] = faintAntialias
  }

  private fun preservedHeaderHasDetailClose(probe: IntArray): Boolean {
    val downsample = TicketVisualActionClassifier::class.java.getDeclaredMethod(
      "downsample",
      IntArray::class.java,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType
    ).also { it.isAccessible = true }
    val sample = downsample.invoke(
      null,
      probe,
      TicketVisualActionClassifier.PROBE_WIDTH,
      TicketVisualActionClassifier.PROBE_HEIGHT,
      TicketVisualActionClassifier.SAMPLE_WIDTH,
      TicketVisualActionClassifier.SAMPLE_HEIGHT
    ) as IntArray
    val preserve = TicketVisualActionClassifier::class.java.getDeclaredMethod(
      "preserveProbeHeaderContrast",
      IntArray::class.java,
      IntArray::class.java
    ).also { it.isAccessible = true }
    val preserved = preserve.invoke(null, probe, sample) as IntArray
    val detect = TicketVisualActionClassifier::class.java.getDeclaredMethod(
      "detectDetailCloseBounds",
      IntArray::class.java
    ).also { it.isAccessible = true }
    return detect.invoke(null, preserved) != null
  }

  private fun nativeProbeHasDetailClose(probe: IntArray): Boolean {
    val method = TicketVisualActionClassifier::class.java.getDeclaredMethod(
      "hasNativeProbeDetailClose",
      IntArray::class.java
    ).also { it.isAccessible = true }
    return method.invoke(null, probe) as Boolean
  }

  private fun drawViviWordmark(pixels: IntArray, color: Int) {
    val top = 7
    val height = 11
    fun drawV(left: Int) {
      for (row in 0 until height) {
        val inset = row / 3
        for (stroke in 0..1) {
          pixels[(top + row) * TicketVisualActionClassifier.SAMPLE_WIDTH + left + inset + stroke] = color
          pixels[(top + row) * TicketVisualActionClassifier.SAMPLE_WIDTH + left + 9 - inset - stroke] = color
        }
      }
    }
    drawV(12)
    fill(pixels, 25, top, 28, top + height, color, TicketVisualActionClassifier.SAMPLE_WIDTH)
    drawV(32)
    fill(pixels, 45, top, 48, top + height, color, TicketVisualActionClassifier.SAMPLE_WIDTH)
  }

  private fun invokeDetailCloseTemplateScore(
    pixels: IntArray,
    centerX: Int,
    centerY: Int,
    radius: Int
  ): Int {
    val method = TicketVisualActionClassifier::class.java.getDeclaredMethod(
      "detailCloseTemplateScore",
      IntArray::class.java,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType,
      Int::class.javaPrimitiveType
    )
    method.isAccessible = true
    return method.invoke(null, pixels, 0, centerX, centerY, radius, 1) as Int
  }

  private fun fill(pixels: IntArray, left: Int, top: Int, right: Int, bottom: Int, color: Int, width: Int = 48) {
    for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
  }

  private val templates = mapOf(
    '0' to listOf(".###.", "##.##", "##.##", "##.##", "##.##", "##.##", ".###."),
    '1' to listOf("..##.", ".###.", "..##.", "..##.", "..##.", "..##.", ".####"),
    '2' to listOf(".###.", "##.##", "...##", "..##.", ".##..", "##...", "#####"),
    '3' to listOf("####.", "...##", "...##", ".###.", "...##", "...##", "####."),
    '4' to listOf("...##", "..###", ".#.##", "##.##", "#####", "...##", "...##"),
    '5' to listOf("#####", "##...", "##...", "####.", "...##", "...##", "####."),
    '6' to listOf(".###.", "##...", "##...", "####.", "##.##", "##.##", ".###."),
    '7' to listOf("#####", "...##", "..##.", "..##.", ".##..", ".##..", ".##.."),
    '8' to listOf(".###.", "##.##", "##.##", ".###.", "##.##", "##.##", ".###."),
    '9' to listOf(".###.", "##.##", "##.##", ".####", "...##", "...##", ".###.")
  )

  private companion object {
    fun rgb(red: Int, green: Int, blue: Int): Int =
      (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    val DARK = rgb(0, 0, 0)
    val LIGHT = rgb(240, 240, 240)
    val MID = rgb(112, 112, 112)
    val RED = rgb(190, 45, 35)
    val ORANGE = rgb(230, 130, 30)
    val YELLOW = rgb(255, 190, 0)
    val BLUE = rgb(30, 60, 180)
  }
}
