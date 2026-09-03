package lv.jolkins.pixelorchestrator.app.ticket

import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviAuthSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketViviReauthTest {
  @Test
  fun payloadRequiresExactLegacyFullResetOrInAppLogoutShape() {
    val safe = ticketViviReauthRequest(command("""{"version":1,"requestId":"vivi-reauth-request-1","credentialRevision":"rev-2"}"""))
    assertEquals("vivi-reauth-request-1", safe?.requestId)
    assertEquals("rev-2", safe?.credentialRevision)
    assertFalse(safe?.resetAppData ?: true)
    assertFalse(safe?.logoutInApp ?: true)
    val fullReset = ticketViviReauthRequest(
      command("""{"version":2,"requestId":"vivi-full-reset-request-1","credentialRevision":"rev-2","resetAppData":true}""")
    )
    assertEquals("vivi-full-reset-request-1", fullReset?.requestId)
    assertEquals("rev-2", fullReset?.credentialRevision)
    assertTrue(fullReset?.resetAppData == true)
    assertFalse(fullReset?.logoutInApp ?: true)
    val logoutLogin = ticketViviReauthRequest(
      command("""{"version":3,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2","logoutInApp":true}""")
    )
    assertEquals("vivi-logout-login-request-1", logoutLogin?.requestId)
    assertEquals("rev-2", logoutLogin?.credentialRevision)
    assertFalse(logoutLogin?.resetAppData ?: true)
    assertTrue(logoutLogin?.logoutInApp == true)
    assertNull(ticketViviReauthRequest(command("""{"version":2,"requestId":"request-1","credentialRevision":"rev-2"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":2,"requestId":"request-1","credentialRevision":"rev-2","resetAppData":false}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":2,"requestId":"request-1","credentialRevision":"rev-2","resetAppData":"true"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":2,"requestId":"request-1","credentialRevision":"rev-2","resetAppData":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":1,"requestId":"vivi-full-reset-request-1","credentialRevision":"rev-2"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":1,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":1,"requestId":"vivi-reauth-request-1","credentialRevision":"rev-2","resetAppData":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"request-1","credentialRevision":"rev-2","resetAppData":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"request-1","credentialRevision":"rev-2","logoutInApp":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2","logoutInApp":false}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2","logoutInApp":"true"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"vivi-full-reset-request-1","credentialRevision":"rev-2","logoutInApp":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":2,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2","resetAppData":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":"1","requestId":"vivi-reauth-request-1","credentialRevision":"rev-2"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":1,"requestId":1,"credentialRevision":"rev-2"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":1,"requestId":"vivi-reauth-request-1","credentialRevision":2}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":true,"requestId":"vivi-reauth-request-1","credentialRevision":"rev-2"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":1,"requestId":"vivi-reauth-request-1","credentialRevision":"rev-2","password":"secret"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":2,"requestId":"vivi-full-reset-request-1","credentialRevision":"rev-2","resetAppData":true,"extra":1}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2","logoutInApp":true,"extra":1}""")))
    listOf(
      """{"version":1,"requestId":"","credentialRevision":"rev-2"}""",
      """{"version":1,"requestId":"request-1","credentialRevision":"rev-2"}""",
      """{"version":1,"requestId":"vivi-reauth-","credentialRevision":"rev-2"}""",
      """{"version":2,"requestId":"vivi-full-reset-","credentialRevision":"rev-2","resetAppData":true}""",
      """{"version":3,"requestId":"vivi-logout-login-","credentialRevision":"rev-2","logoutInApp":true}"""
    ).forEach { payload -> assertNull(ticketViviReauthRequest(command(payload))) }
  }

  @Test
  fun terminalReplayRequiresSameRequestAndExactCredentialRevision() {
    val journal = TicketViviReauthJournal(
      requestId = "request-1",
      credentialRevision = "rev-2",
      phase = "verifying_signed_in",
      terminalStatus = "succeeded",
      terminalReason = "signed_in_proven",
      proofSource = "phone_visual",
      streamEpoch = 7,
      frameSequence = 11,
      terminalOk = true
    )
    val retained = retainedTicketViviReauthSnapshot(journal, TicketViviReauthRequest("request-1", "rev-2"))
    assertEquals("succeeded", retained?.status)
    assertEquals("phone_visual", retained?.proofSource)
    assertNull(retainedTicketViviReauthSnapshot(journal, TicketViviReauthRequest("request-2", "rev-2")))
    assertNull(retainedTicketViviReauthSnapshot(journal, TicketViviReauthRequest("request-1", "rev-3")))
    assertNull(
      retainedTicketViviReauthSnapshot(
        journal,
        TicketViviReauthRequest("request-1", "rev-2", resetAppData = true)
      )
    )
    val fullResetJournal = journal.copy(resetAppData = true)
    assertEquals(
      true,
      retainedTicketViviReauthSnapshot(
        fullResetJournal,
        TicketViviReauthRequest("request-1", "rev-2", resetAppData = true)
      )?.resetAppData
    )
    assertNull(
      retainedTicketViviReauthSnapshot(
        fullResetJournal,
        TicketViviReauthRequest("request-1", "rev-2")
      )
    )
    val logoutJournal = journal.copy(
      requestId = "vivi-logout-login-request-1",
      logoutInApp = true,
      terminalReason = "saved_credentials_sign_in_proven"
    )
    assertEquals(
      true,
      retainedTicketViviReauthSnapshot(
        logoutJournal,
        TicketViviReauthRequest(
          "vivi-logout-login-request-1",
          "rev-2",
          logoutInApp = true
        )
      )?.logoutInApp
    )
    assertNull(
      retainedTicketViviReauthSnapshot(
        logoutJournal,
        TicketViviReauthRequest("vivi-logout-login-request-1", "rev-2")
      )
    )
  }

  @Test
  fun dispatchCheckpointsAreTreatedAsPossiblyMutated() {
    assertFalse(TicketViviReauthJournal().resetAppData)
    assertFalse(TicketViviReauthJournal().logoutInApp)
    assertFalse(TicketViviReauthJournal(phase = "").mutationMayHaveDispatched)
    listOf(
      "clear_dispatching",
      "opening_vivi",
      "device_link_force_stop_dispatching",
      "device_link_relaunch_dispatching",
      "detail_close_dispatching",
      "profile_tab_dispatching",
      "account_controls_dispatching",
      "account_scroll_dispatching",
      "logout_dispatching",
      "verifying_signed_out",
      "email_dispatching",
      "password_dispatching",
      "submit_dispatching",
      "verifying_signed_in"
    ).forEach { phase -> assertTrue(phase, TicketViviReauthJournal(phase = phase).mutationMayHaveDispatched) }
  }

  @Test
  fun onlySafeInitialDeviceLinkCanRequestTheOneRefresh() {
    PhoneAutomationViviAuthSurface.entries.forEach { surface ->
      assertEquals(
        surface == PhoneAutomationViviAuthSurface.DEVICE_LINK,
        ticketViviSafeReauthShouldRefreshInitialDeviceLink(false, surface)
      )
      assertFalse(ticketViviSafeReauthShouldRefreshInitialDeviceLink(true, surface))
    }
  }

  @Test
  fun preflightSuccessAcceptsTheExactBoundedSignedInSurfaceSet() {
    listOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      TicketVisualPhoneState.TICKET_LIST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      TicketVisualPhoneState.VIVI_HOME,
      TicketVisualPhoneState.VIVI_PROFILE,
      TicketVisualPhoneState.VIVI_OTHER_TAB
    ).forEach { state -> assertTrue(state.wireName, ticketViviReauthPreflightAuthenticatedState(state)) }
    assertFalse(ticketViviReauthPreflightAuthenticatedState(TicketVisualPhoneState.LOGIN_REQUIRED))
    assertFalse(ticketViviReauthPreflightAuthenticatedState(TicketVisualPhoneState.BLOCKED))
    assertFalse(ticketViviReauthPreflightAuthenticatedState(TicketVisualPhoneState.UNKNOWN))
    assertFalse(ticketViviReauthPreflightAuthenticatedState(null))
  }

  @Test
  fun inAppLogoutNeedsEitherOneProvedLowerTabOrAnExactDetailClose() {
    TicketViviBottomTab.entries.filterNot { it == TicketViviBottomTab.NONE }.forEach { tab ->
      assertTrue(
        tab.wireName,
        ticketViviLogoutLoginStartObservation(
          TicketVisualActionObservation(
            probeId = 1,
            state = TicketVisualPhoneState.UNKNOWN,
            bottomTab = tab
          )
        )
      )
    }
    listOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL
    ).forEach { state ->
      assertTrue(
        state.wireName,
        ticketViviLogoutLoginStartObservation(
          TicketVisualActionObservation(
            probeId = 1,
            state = state,
            backBounds = TicketVisualProbeBounds(170, 8, 187, 26)
          )
        )
      )
      assertFalse(
        ticketViviLogoutLoginStartObservation(
          TicketVisualActionObservation(
            probeId = 1,
            state = state,
            bottomTab = TicketViviBottomTab.PROFILE
          )
        )
      )
    }
    listOf(
      TicketVisualPhoneState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED
    ).forEach { state ->
      assertFalse(
        state.wireName,
        ticketViviLogoutLoginStartObservation(
          TicketVisualActionObservation(
            probeId = 1,
            state = state,
            bottomTab = TicketViviBottomTab.PROFILE
          )
        )
      )
    }
    assertFalse(ticketViviLogoutLoginStartObservation(null))
  }

  @Test
  fun profileRouteSideChannelAllowsUnknownBodyButNeverAnExplicitBlocker() {
    assertTrue(
      ticketViviLogoutBottomRouteObservation(
        TicketVisualActionObservation(
          probeId = 1,
          state = TicketVisualPhoneState.UNKNOWN,
          bottomTab = TicketViviBottomTab.PROFILE
        ),
        TicketViviBottomTab.PROFILE
      )
    )
    assertFalse(
      ticketViviLogoutBottomRouteObservation(
        TicketVisualActionObservation(
          probeId = 1,
          state = TicketVisualPhoneState.UNKNOWN,
          bottomTab = TicketViviBottomTab.HOME
        ),
        TicketViviBottomTab.PROFILE
      )
    )
    listOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      TicketVisualPhoneState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED
    ).forEach { state ->
      assertFalse(
        state.wireName,
        ticketViviLogoutBottomRouteObservation(
          TicketVisualActionObservation(
            probeId = 1,
            state = state,
            bottomTab = TicketViviBottomTab.PROFILE
          ),
          TicketViviBottomTab.PROFILE
        )
      )
    }
  }

  @Test
  fun postSubmitSuccessRetainsTheBoundedSignedInSurfaceSet() {
    listOf(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      TicketVisualPhoneState.TICKET_LIST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      TicketVisualPhoneState.VIVI_HOME,
      TicketVisualPhoneState.VIVI_PROFILE,
      TicketVisualPhoneState.VIVI_OTHER_TAB
    ).forEach { state -> assertTrue(state.wireName, ticketViviReauthPostSubmitSignedInState(state)) }
    assertFalse(ticketViviReauthPostSubmitSignedInState(TicketVisualPhoneState.LOGIN_REQUIRED))
    assertFalse(ticketViviReauthPostSubmitSignedInState(TicketVisualPhoneState.BLOCKED))
    assertFalse(ticketViviReauthPostSubmitSignedInState(TicketVisualPhoneState.UNKNOWN))
    assertFalse(ticketViviReauthPostSubmitSignedInState(null))
  }

  @Test
  fun legacyAndFullResetPreflightAcceptActualClassifierProofForEveryLowerNavRoute() {
    val legacyModes = listOf(
      TicketViviReauthRequest("vivi-reauth-1", "rev"),
      TicketViviReauthRequest("vivi-full-reset-1", "rev", resetAppData = true)
    )
    val exactLowerNavObservations = TicketViviBottomTab.entries
      .filterNot { it == TicketViviBottomTab.NONE }
      .associateWith(::classifiedLowerNavObservation)
    assertEquals(TicketVisualPhoneState.UNKNOWN, exactLowerNavObservations.getValue(
      TicketViviBottomTab.TICKETS
    ).state)

    legacyModes.forEach { request ->
      assertFalse(request.logoutInApp)
      exactLowerNavObservations.forEach { (tab, observation) ->
        assertTrue(
          "${request.requestId}:${tab.wireName}:${observation.state.wireName}",
          ticketViviReauthPreflightAuthenticatedObservation(observation)
        )
      }
    }
  }

  @Test
  fun v3PostSubmitAcceptsActualHomeProfileTicketsAndMenuProofButNotBlockedRoutes() {
    TicketViviBottomTab.entries.filterNot { it == TicketViviBottomTab.NONE }.forEach { tab ->
      val observation = classifiedLowerNavObservation(tab)
      assertTrue(
        "${tab.wireName}:${observation.state.wireName}",
        ticketViviReauthPostSubmitSignedInObservation(observation)
      )
    }
    listOf(
      TicketVisualPhoneState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED
    ).forEach { state ->
      TicketViviBottomTab.entries.filterNot { it == TicketViviBottomTab.NONE }.forEach { tab ->
        assertFalse(
          "${state.wireName}:${tab.wireName}",
          ticketViviReauthPostSubmitSignedInObservation(
            TicketVisualActionObservation(1, state, bottomTab = tab)
          )
        )
      }
    }
    assertFalse(
      ticketViviReauthPostSubmitSignedInObservation(
        TicketVisualActionObservation(1, TicketVisualPhoneState.UNKNOWN)
      )
    )
  }

  @Test
  fun v3InitialConsensusCarriesActualUnknownTicketsRouteWithoutWideningLegacyOrBlockers() {
    val unknownTickets = classifiedLowerNavObservation(TicketViviBottomTab.TICKETS)
    assertEquals(TicketVisualPhoneState.UNKNOWN, unknownTickets.state)
    val v3 = TicketViviReauthRequest(
      "vivi-logout-login-1",
      "rev",
      logoutInApp = true
    )
    val v3Consensus = TicketVisualObservationConsensus()
    assertNull(v3Consensus.offer(unknownTickets, allowUnknown = v3.logoutInApp))
    val stableTickets = v3Consensus.offer(
      unknownTickets.copy(probeId = unknownTickets.probeId + 1),
      allowUnknown = v3.logoutInApp
    )
    assertEquals(TicketViviBottomTab.TICKETS, stableTickets?.bottomTab)
    assertTrue(ticketViviLogoutLoginStartObservation(stableTickets))

    listOf(
      TicketViviReauthRequest("vivi-reauth-1", "rev"),
      TicketViviReauthRequest("vivi-full-reset-1", "rev", resetAppData = true)
    ).forEach { request ->
      val consensus = TicketVisualObservationConsensus()
      assertNull(consensus.offer(unknownTickets, allowUnknown = request.logoutInApp))
      assertNull(
        consensus.offer(
          unknownTickets.copy(probeId = unknownTickets.probeId + 1),
          allowUnknown = request.logoutInApp
        )
      )
    }

    val unknownWithoutTab = TicketVisualActionObservation(10, TicketVisualPhoneState.UNKNOWN)
    val unknownConsensus = TicketVisualObservationConsensus()
    assertNull(unknownConsensus.offer(unknownWithoutTab, allowUnknown = v3.logoutInApp))
    val stableUnknown = unknownConsensus.offer(
      unknownWithoutTab.copy(probeId = 11),
      allowUnknown = v3.logoutInApp
    )
    assertFalse(ticketViviLogoutLoginStartObservation(stableUnknown))

    listOf(
      TicketVisualPhoneState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED
    ).forEachIndexed { index, state ->
      val consensus = TicketVisualObservationConsensus()
      val first = TicketVisualActionObservation(
        probeId = 20L + index * 2,
        state = state,
        bottomTab = TicketViviBottomTab.TICKETS
      )
      assertNull(consensus.offer(first, allowUnknown = v3.logoutInApp))
      val stable = consensus.offer(first.copy(probeId = first.probeId + 1), allowUnknown = true)
      assertFalse(state.wireName, ticketViviLogoutLoginStartObservation(stable))
    }
  }

  @Test
  fun journalWriteRequiresCommitAndExactReadBack() {
    val expected = TicketViviReauthJournal(
      requestId = "request-1",
      credentialRevision = "rev-2",
      resetAppData = true,
      logoutInApp = false
    )
    assertTrue(ticketViviReauthJournalWriteProved(expected, { true }, { expected }))
    assertFalse(ticketViviReauthJournalWriteProved(expected, { false }, { expected }))
    assertFalse(ticketViviReauthJournalWriteProved(expected, { true }, { expected.copy(phase = "changed") }))
    assertFalse(
      ticketViviReauthJournalWriteProved(
        expected,
        { true },
        { expected.copy(resetAppData = false) }
      )
    )
    assertFalse(
      ticketViviReauthJournalWriteProved(
        expected,
        { true },
        { expected.copy(logoutInApp = true) }
      )
    )
  }

  private fun command(payload: String) = TicketSpacetimeCommand(
    id = "command-1",
    ticketId = "ticket-1",
    backendId = "backend-1",
    commandType = "vivi_reauth",
    status = "pending",
    revision = "command-revision",
    reason = "requested",
    payloadJson = payload,
    createdAt = "",
    updatedAt = "",
    expiresAt = ""
  )

  private fun classifiedLowerNavObservation(
    expectedTab: TicketViviBottomTab
  ): TicketVisualActionObservation {
    val frame = lowerNavFrame(expectedTab)
    val classified = TicketVisualActionClassifier.classifyCurrent(frame)
    val selectedTab = TicketViviBottomTab.fromWireName(
      TicketVisualActionClassifier.selectedBottomNavigationTab(frame)
    )
    assertEquals(expectedTab, selectedTab)
    return TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.fromWireName(classified.state),
      bottomTab = selectedTab
    )
  }

  private fun lowerNavFrame(selectedTab: TicketViviBottomTab): IntArray {
    val width = TicketVisualActionClassifier.SAMPLE_WIDTH
    val height = TicketVisualActionClassifier.SAMPLE_HEIGHT
    val dark = rgb(0, 0, 0)
    val neutral = rgb(240, 240, 240)
    val muted = rgb(112, 112, 112)
    val selected = rgb(255, 190, 0)
    val pixels = IntArray(width * height) { dark }

    fun fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
      for (y in top until bottom) for (x in left until right) pixels[y * width + x] = color
    }
    fill(12, 40, 180, 220, muted)
    fill(0, 255, 192, 257, muted)
    val homeColor = if (selectedTab == TicketViviBottomTab.HOME) selected else neutral
    listOf(
      17 to 270, 18 to 267, 19 to 265, 20 to 264, 21 to 265, 22 to 267, 23 to 269,
      24 to 270, 25 to 271, 26 to 270, 27 to 269, 28 to 267, 25 to 272, 28 to 272
    ).forEach { (x, y) -> pixels[y * width + x] = homeColor }
    fill(64, 265, 81, 274, if (selectedTab == TicketViviBottomTab.TICKETS) selected else neutral)
    fill(65, 266, 80, 273, dark)
    fill(115, 264, 127, 278, if (selectedTab == TicketViviBottomTab.PROFILE) selected else neutral)
    fill(117, 266, 125, 276, dark)
    val menuColor = if (selectedTab == TicketViviBottomTab.MENU) selected else neutral
    fill(163, 264, 179, 266, menuColor)
    fill(163, 269, 179, 271, menuColor)
    fill(163, 274, 179, 276, menuColor)
    return pixels
  }

  private fun rgb(red: Int, green: Int, blue: Int): Int =
    (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
