package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviAuthSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketViviReauthTest {
  @Test
  fun ambiguousDispatchIsNeverReplayedAndOnlyTheExactSuccessorAdvances() = runBlocking {
    var acceptedDispatches = 0
    var acceptedObservations = 0
    val accepted = ticketViviReauthDispatchOnceThenProve(
      dispatch = {
        acceptedDispatches += 1
        false
      },
      observeSuccessor = {
        acceptedObservations += 1
        "account_details_before_scroll"
      },
      successorProven = { it == "account_details_before_scroll" }
    )
    assertEquals("account_details_before_scroll", accepted)
    assertEquals(1, acceptedDispatches)
    assertEquals(1, acceptedObservations)

    var rejectedDispatches = 0
    var rejectedObservations = 0
    val rejected = ticketViviReauthDispatchOnceThenProve(
      dispatch = {
        rejectedDispatches += 1
        false
      },
      observeSuccessor = {
        rejectedObservations += 1
        "unchanged"
      },
      successorProven = { it == "account_details_before_scroll" }
    )
    assertNull(rejected)
    assertEquals(1, rejectedDispatches)
    assertEquals(1, rejectedObservations)
  }

  @Test
  fun payloadRequiresExactVersionedShapeAndNativeTrueModeFlags() {
    val safe = ticketViviReauthRequest(command("""{"version":1,"requestId":"vivi-reauth-request-1","credentialRevision":"rev-2"}"""))
    assertEquals("vivi-reauth-request-1", safe?.requestId)
    assertEquals("rev-2", safe?.credentialRevision)
    assertFalse(safe?.resetAppData ?: true)
    assertFalse(safe?.logoutInApp ?: true)
    assertFalse(safe?.redetectAfterLogin ?: true)
    val fullReset = ticketViviReauthRequest(
      command("""{"version":2,"requestId":"vivi-full-reset-request-1","credentialRevision":"rev-2","resetAppData":true}""")
    )
    assertEquals("vivi-full-reset-request-1", fullReset?.requestId)
    assertEquals("rev-2", fullReset?.credentialRevision)
    assertTrue(fullReset?.resetAppData == true)
    assertFalse(fullReset?.logoutInApp ?: true)
    assertFalse(fullReset?.redetectAfterLogin ?: true)
    val logoutLogin = ticketViviReauthRequest(
      command("""{"version":3,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2","logoutInApp":true}""")
    )
    assertEquals("vivi-logout-login-request-1", logoutLogin?.requestId)
    assertEquals("rev-2", logoutLogin?.credentialRevision)
    assertFalse(logoutLogin?.resetAppData ?: true)
    assertTrue(logoutLogin?.logoutInApp == true)
    assertFalse(logoutLogin?.redetectAfterLogin ?: true)
    val logoutRedetect = ticketViviReauthRequest(
      command("""{"version":4,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":true,"redetectAfterLogin":true}""")
    )
    assertEquals("vivi-logout-redetect-login-request-1", logoutRedetect?.requestId)
    assertEquals("rev-2", logoutRedetect?.credentialRevision)
    assertFalse(logoutRedetect?.resetAppData ?: true)
    assertTrue(logoutRedetect?.logoutInApp == true)
    assertTrue(logoutRedetect?.redetectAfterLogin == true)
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
    assertNull(ticketViviReauthRequest(command("""{"version":4,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":4,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":false,"redetectAfterLogin":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":4,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":true,"redetectAfterLogin":false}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":4,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":"true","redetectAfterLogin":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":4,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":true,"redetectAfterLogin":"true"}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":4,"requestId":"vivi-logout-login-request-1","credentialRevision":"rev-2","logoutInApp":true,"redetectAfterLogin":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":3,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":true}""")))
    assertNull(ticketViviReauthRequest(command("""{"version":4,"requestId":"vivi-logout-redetect-login-request-1","credentialRevision":"rev-2","logoutInApp":true,"redetectAfterLogin":true,"extra":1}""")))
    listOf(
      """{"version":1,"requestId":"","credentialRevision":"rev-2"}""",
      """{"version":1,"requestId":"request-1","credentialRevision":"rev-2"}""",
      """{"version":1,"requestId":"vivi-reauth-","credentialRevision":"rev-2"}""",
      """{"version":2,"requestId":"vivi-full-reset-","credentialRevision":"rev-2","resetAppData":true}""",
      """{"version":3,"requestId":"vivi-logout-login-","credentialRevision":"rev-2","logoutInApp":true}""",
      """{"version":4,"requestId":"vivi-logout-redetect-login-","credentialRevision":"rev-2","logoutInApp":true,"redetectAfterLogin":true}"""
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
    val redetectJournal = logoutJournal.copy(
      requestId = "vivi-logout-redetect-login-request-1",
      redetectAfterLogin = true,
      terminalReason = "saved_credentials_latest_ticket_redetected"
    )
    val redetectRequest = TicketViviReauthRequest(
      "vivi-logout-redetect-login-request-1",
      "rev-2",
      logoutInApp = true,
      redetectAfterLogin = true
    )
    assertTrue(retainedTicketViviReauthSnapshot(redetectJournal, redetectRequest)?.redetectAfterLogin == true)
    assertNull(retainedTicketViviReauthSnapshot(redetectJournal, redetectRequest.copy(redetectAfterLogin = false)))
  }

  @Test
  fun sameRequestIdRequiresExactSemanticIdentityBeforeAnyReplay() {
    val request = TicketViviReauthRequest(
      "vivi-logout-redetect-login-request-1",
      "rev-2",
      logoutInApp = true,
      redetectAfterLogin = true
    )
    val matching = TicketViviReauthJournal(
      requestId = request.requestId,
      credentialRevision = request.credentialRevision,
      logoutInApp = true,
      redetectAfterLogin = true
    )
    assertTrue(ticketViviReauthJournalMatchesRequest(matching, request))
    assertFalse(ticketViviReauthRequestIdHasSemanticMismatch(matching, request))
    listOf(
      matching.copy(credentialRevision = "rev-3"),
      matching.copy(resetAppData = true),
      matching.copy(logoutInApp = false),
      matching.copy(redetectAfterLogin = false)
    ).forEach { mismatch ->
      assertFalse(ticketViviReauthJournalMatchesRequest(mismatch, request))
      assertTrue(ticketViviReauthRequestIdHasSemanticMismatch(mismatch, request))
    }
    assertFalse(
      ticketViviReauthRequestIdHasSemanticMismatch(
        matching.copy(requestId = "vivi-logout-redetect-login-other"),
        request
      )
    )
  }

  @Test
  fun dispatchCheckpointsAreTreatedAsPossiblyMutated() {
    assertFalse(TicketViviReauthJournal().resetAppData)
    assertFalse(TicketViviReauthJournal().logoutInApp)
    assertFalse(TicketViviReauthJournal().redetectAfterLogin)
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
      "verifying_signed_in",
      "ticket_restore_tickets_tab_dispatching",
      "ticket_restore_single_use_tab_dispatching",
      "ticket_restore_time_tab_dispatching",
      "ticket_restore_detail_dispatching",
      "ticket_redetect_detail_close_dispatching",
      "ticket_redetect_tickets_tab_dispatching",
      "ticket_redetect_single_use_tab_dispatching",
      "ticket_redetect_time_tab_dispatching",
      "ticket_redetect_latest_detail_dispatching"
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
  fun inAppLogoutStartsOnlyFromAnExactRestorableDetail() {
    TicketViviBottomTab.entries.filterNot { it == TicketViviBottomTab.NONE }.forEach { tab ->
      assertFalse(
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
    val back = TicketVisualProbeBounds(170, 8, 187, 26)
    val slider = TicketVisualProbeBounds(20, 220, 170, 238)
    assertTrue(ticketViviLogoutLoginStartObservation(TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.ACTIVATED_DETAIL,
      currentAnchor = "d_1111111111111111aaaaaaaaaaaa",
      backBounds = back
    )))
    assertTrue(ticketViviLogoutLoginStartObservation(TicketVisualActionObservation(
      probeId = 2,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "d_2222222222222222bbbbbbbbbbbb",
      sliderBounds = slider,
      backBounds = back
    )))
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
  fun normalLogoutTerminalReadinessRequiresAnExactTicketDetail() {
    val back = TicketVisualProbeBounds(170, 8, 187, 26)
    val slider = TicketVisualProbeBounds(20, 220, 170, 238)
    val activated = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.ACTIVATED_DETAIL,
      currentAnchor = "d_1111111111111111aaaaaaaaaaaa",
      backBounds = back
    )
    val unactivated = TicketVisualActionObservation(
      probeId = 2,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "d_2222222222222222bbbbbbbbbbbb",
      sliderBounds = slider,
      backBounds = back
    )
    assertTrue(ticketViviReauthTerminalReadyObservation(activated))
    assertTrue(ticketViviReauthTerminalReadyObservation(unactivated))
    assertFalse(ticketViviReauthTerminalReadyObservation(activated.copy(currentAnchor = "")))
    assertFalse(ticketViviReauthTerminalReadyObservation(activated.copy(currentAnchor = "card_anchor")))
    assertFalse(ticketViviReauthTerminalReadyObservation(activated.copy(currentAnchor = "d_zzzzzzzzzzzzzzzzzzzzzzzzzzzz")))
    assertFalse(ticketViviReauthTerminalReadyObservation(activated.copy(backBounds = null)))
    assertFalse(ticketViviReauthTerminalReadyObservation(activated.copy(sliderBounds = slider)))
    assertFalse(ticketViviReauthTerminalReadyObservation(unactivated.copy(sliderBounds = null)))
    listOf(
      TicketVisualPhoneState.TICKET_LIST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      TicketVisualPhoneState.VIVI_HOME,
      TicketVisualPhoneState.VIVI_PROFILE,
      TicketVisualPhoneState.VIVI_OTHER_TAB,
      TicketVisualPhoneState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED,
      TicketVisualPhoneState.UNKNOWN
    ).forEach { state ->
      assertFalse(
        state.wireName,
        ticketViviReauthTerminalReadyObservation(
          TicketVisualActionObservation(
            3,
            state,
            currentAnchor = "d_3333333333333333cccccccccccc",
            backBounds = back
          )
        )
      )
    }
    assertFalse(ticketViviReauthTerminalReadyObservation(null))
  }

  @Test
  fun normalLogoutReturnTargetRequiresAndRestoresTheExactOriginalDetail() {
    val initial = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "d_1111111111111111aaaaaaaaaaaa",
      sliderBounds = TicketVisualProbeBounds(20, 220, 170, 238),
      backBounds = TicketVisualProbeBounds(170, 8, 187, 26)
    )
    val target = ticketViviReauthReturnTarget(initial)
    assertEquals(TicketVisualPhoneState.UNACTIVATED_DETAIL, target?.state)
    assertEquals("d_1111111111111111aaaaaaaaaaaa", target?.detailAnchor)
    assertTrue(ticketViviReauthRestoredTarget(checkNotNull(target), initial.copy(probeId = 2)))
    assertFalse(
      ticketViviReauthRestoredTarget(
        target,
        initial.copy(probeId = 3, currentAnchor = "d_2222222222222222bbbbbbbbbbbb")
      )
    )
    assertFalse(
      ticketViviReauthRestoredTarget(
        target,
        initial.copy(probeId = 4, state = TicketVisualPhoneState.ACTIVATED_DETAIL, sliderBounds = null)
      )
    )
    assertNull(
      ticketViviReauthReturnTarget(
        TicketVisualActionObservation(5, TicketVisualPhoneState.VIVI_HOME)
      )
    )
  }

  @Test
  fun normalLogoutRestoreUsesOnlyTypedNonActivatingNavigationTargets() {
    val tickets = TicketVisualProbeBounds(48, 260, 96, 286)
    val timeTickets = TicketVisualProbeBounds(96, 40, 170, 66)
    val registration = TicketVisualProbeBounds(20, 190, 170, 216)
    val activatedDetail = TicketVisualProbeBounds(20, 90, 170, 116)
    val cardBounds = TicketVisualProbeBounds(10, 70, 182, 224)
    val unactivatedTarget = TicketViviReauthReturnTarget(
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      "d_1111111111111111aaaaaaaaaaaa"
    )
    val activatedTarget = TicketViviReauthReturnTarget(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      "d_2222222222222222bbbbbbbbbbbb"
    )

    assertEquals(
      TicketViviReauthRestoreTarget(
        TicketViviReauthRestoreTargetKind.TICKETS_TAB,
        tickets
      ),
      ticketViviReauthRestoreTarget(
        unactivatedTarget,
        TicketVisualActionObservation(
          1,
          TicketVisualPhoneState.VIVI_HOME,
          ticketsTabBounds = tickets,
          bottomTab = TicketViviBottomTab.HOME
        )
      )
    )
    assertEquals(
      TicketViviReauthRestoreTarget(
        TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB,
        timeTickets
      ),
      ticketViviReauthRestoreTarget(
        unactivatedTarget,
        TicketVisualActionObservation(
          2,
          TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
          timeTicketsTabBounds = timeTickets,
          bottomTab = TicketViviBottomTab.TICKETS
        )
      )
    )
    val list = TicketVisualActionObservation(
      probeId = 3,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(
        TicketVisualCardAnchor(
          anchor = "latest_unused",
          bounds = cardBounds,
          registrationBounds = registration,
          latest = true
        ),
        TicketVisualCardAnchor(
          anchor = "used",
          bounds = cardBounds,
          activatedDetailBounds = activatedDetail
        )
      )
    )
    assertEquals(
      TicketViviReauthRestoreTarget(
        TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL,
        registration
      ),
      ticketViviReauthRestoreTarget(unactivatedTarget, list)
    )
    assertEquals(
      TicketViviReauthRestoreTarget(
        TicketViviReauthRestoreTargetKind.UNIQUE_ACTIVATED_DETAIL,
        activatedDetail
      ),
      ticketViviReauthRestoreTarget(activatedTarget, list)
    )
    assertNull(
      ticketViviReauthRestoreTarget(
        unactivatedTarget,
        list.copy(cards = list.cards + list.cards.first().copy(anchor = "duplicate_latest"))
      )
    )
    assertNull(
      ticketViviReauthRestoreTarget(
        activatedTarget,
        list.copy(cards = list.cards + list.cards.last().copy(anchor = "duplicate_activated"))
      )
    )
    assertNull(
      ticketViviReauthRestoreTarget(
        unactivatedTarget,
        TicketVisualActionObservation(4, TicketVisualPhoneState.TICKETS_TIME_EMPTY)
      )
    )
    listOf(
      TicketVisualPhoneState.VIVI_PROFILE,
      TicketVisualPhoneState.VIVI_OTHER_TAB,
      TicketVisualPhoneState.UNKNOWN
    ).forEach { state ->
      assertNull(
        ticketViviReauthRestoreTarget(
          unactivatedTarget,
          TicketVisualActionObservation(5, state, bottomTab = TicketViviBottomTab.PROFILE)
        )
      )
    }
  }

  @Test
  fun restoreTransitionsNeverReplayAndAcceptOnlyTheExactSuccessor() {
    val tickets = TicketVisualProbeBounds(48, 260, 96, 286)
    val timeTickets = TicketVisualProbeBounds(96, 40, 170, 66)
    val registration = TicketVisualProbeBounds(20, 190, 170, 216)
    val cardBounds = TicketVisualProbeBounds(10, 70, 182, 224)
    val target = TicketViviReauthReturnTarget(
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      "d_1111111111111111aaaaaaaaaaaa"
    )
    val home = TicketVisualActionObservation(
      1,
      TicketVisualPhoneState.VIVI_HOME,
      ticketsTabBounds = tickets,
      bottomTab = TicketViviBottomTab.HOME
    )
    assertEquals(
      TicketViviReauthRestoreTransitionStatus.WAITING,
      ticketViviReauthRestoreTransitionStatus(
        target,
        TicketViviReauthRestoreTargetKind.TICKETS_TAB,
        home,
        home.copy(probeId = 2)
      )
    )
    assertEquals(
      TicketViviReauthRestoreTransitionStatus.REJECTED,
      ticketViviReauthRestoreTransitionStatus(
        target,
        TicketViviReauthRestoreTargetKind.TICKETS_TAB,
        home,
        TicketVisualActionObservation(3, TicketVisualPhoneState.VIVI_PROFILE)
      )
    )
    val singleUse = TicketVisualActionObservation(
      4,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      timeTicketsTabBounds = timeTickets,
      bottomTab = TicketViviBottomTab.TICKETS
    )
    assertEquals(
      TicketViviReauthRestoreTransitionStatus.PROVED,
      ticketViviReauthRestoreTransitionStatus(
        target,
        TicketViviReauthRestoreTargetKind.TICKETS_TAB,
        home,
        singleUse
      )
    )
    val list = TicketVisualActionObservation(
      5,
      TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(TicketVisualCardAnchor(
        anchor = "latest_unused",
        bounds = cardBounds,
        registrationBounds = registration,
        latest = true
      ))
    )
    assertEquals(
      TicketViviReauthRestoreTransitionStatus.PROVED,
      ticketViviReauthRestoreTransitionStatus(
        target,
        TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB,
        singleUse,
        list
      )
    )
    assertEquals(
      TicketViviReauthRestoreTransitionStatus.WAITING,
      ticketViviReauthRestoreTransitionStatus(
        target,
        TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL,
        list,
        list.copy(probeId = 6)
      )
    )
    val exactDetail = TicketVisualActionObservation(
      7,
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = target.detailAnchor,
      sliderBounds = TicketVisualProbeBounds(20, 220, 170, 238),
      backBounds = TicketVisualProbeBounds(170, 8, 187, 26)
    )
    assertEquals(
      TicketViviReauthRestoreTransitionStatus.PROVED,
      ticketViviReauthRestoreTransitionStatus(
        target,
        TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL,
        list,
        exactDetail
      )
    )
    assertEquals(
      TicketViviReauthRestoreTransitionStatus.REJECTED,
      ticketViviReauthRestoreTransitionStatus(
        target,
        TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL,
        list,
        exactDetail.copy(
          probeId = 8,
          currentAnchor = "d_2222222222222222bbbbbbbbbbbb"
        )
      )
    )
  }

  @Test
  fun v4RedetectUsesOnlyFreshUniqueNonActivatingTargets() {
    val back = TicketVisualProbeBounds(170, 8, 187, 26)
    val tickets = TicketVisualProbeBounds(48, 260, 96, 286)
    val timeTickets = TicketVisualProbeBounds(96, 40, 170, 66)
    val registration = TicketVisualProbeBounds(20, 190, 170, 216)
    val cardBounds = TicketVisualProbeBounds(10, 70, 182, 224)
    val card = TicketVisualCardAnchor(
      anchor = "latest_unused",
      bounds = cardBounds,
      registrationBounds = registration,
      latest = true
    )
    val list = TicketVisualActionObservation(
      10,
      TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(card)
    )
    assertEquals(
      TicketViviReauthRedetectTarget(
        TicketViviReauthRedetectTargetKind.LATEST_UNACTIVATED_DETAIL,
        registration,
        card.anchor
      ),
      ticketViviReauthRedetectTarget(list)
    )
    assertNull(ticketViviReauthRedetectTarget(list.copy(cards = listOf(card, card.copy()))))
    assertEquals(
      TicketViviReauthRedetectTargetKind.CLOSE_WRONG_DETAIL,
      ticketViviReauthRedetectTarget(
        TicketVisualActionObservation(
          11,
          TicketVisualPhoneState.ACTIVATED_DETAIL,
          currentAnchor = "d_1111111111111111aaaaaaaaaaaa",
          backBounds = back
        )
      )?.kind
    )
    TicketViviBottomTab.entries.filterNot { it == TicketViviBottomTab.NONE }.forEach { tab ->
      assertEquals(
        tab.wireName,
        TicketViviReauthRedetectTargetKind.TICKETS_TAB,
        ticketViviReauthRedetectTarget(
          TicketVisualActionObservation(
            12,
            when (tab) {
              TicketViviBottomTab.HOME -> TicketVisualPhoneState.VIVI_HOME
              TicketViviBottomTab.PROFILE -> TicketVisualPhoneState.VIVI_PROFILE
              TicketViviBottomTab.MENU -> TicketVisualPhoneState.VIVI_OTHER_TAB
              TicketViviBottomTab.TICKETS -> TicketVisualPhoneState.UNKNOWN
              TicketViviBottomTab.NONE -> error("filtered")
            },
            ticketsTabBounds = tickets,
            bottomTab = tab
          )
        )?.kind
      )
    }
    assertEquals(
      TicketViviReauthRedetectTargetKind.SINGLE_USE_TICKETS_TAB,
      ticketViviReauthRedetectTarget(
        TicketVisualActionObservation(
          13,
          TicketVisualPhoneState.TICKETS_TIME_EMPTY,
          ticketsTabBounds = tickets,
          bottomTab = TicketViviBottomTab.TICKETS
        )
      )?.kind
    )
    assertEquals(
      TicketViviReauthRedetectTargetKind.TIME_TICKETS_TAB,
      ticketViviReauthRedetectTarget(
        TicketVisualActionObservation(
          14,
          TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
          timeTicketsTabBounds = timeTickets,
          bottomTab = TicketViviBottomTab.TICKETS
        )
      )?.kind
    )

    val rawDetail = TicketVisualActionObservation(
      15,
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "d_2222222222222222bbbbbbbbbbbb",
      sliderBounds = TicketVisualProbeBounds(20, 220, 170, 238),
      backBounds = back
    )
    assertTrue(ticketViviReauthRedetectedLatest(list, card.anchor, rawDetail))
    assertFalse(ticketViviReauthRedetectedLatest(list, "other", rawDetail))
    assertFalse(ticketViviReauthRedetectedLatest(list, card.anchor, rawDetail.copy(probeId = 10)))
    assertFalse(
      ticketViviReauthRedetectedLatest(
        list.copy(cards = listOf(card, card.copy())),
        card.anchor,
        rawDetail
      )
    )
  }

  @Test
  fun v4OriginalAbsenceAndTwoTabNoTicketProofFailClosed() {
    val target = TicketViviReauthReturnTarget(
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      "d_1111111111111111aaaaaaaaaaaa"
    )
    val tickets = TicketVisualProbeBounds(48, 260, 96, 286)
    val timeTickets = TicketVisualProbeBounds(96, 40, 170, 66)
    val emptyList = TicketVisualActionObservation(20, TicketVisualPhoneState.TICKET_LIST)
    assertTrue(ticketViviReauthOriginalTargetVisuallyAbsent(target, emptyList))
    val ambiguous = emptyList.copy(cards = listOf(
      TicketVisualCardAnchor(
        "latest_unused",
        TicketVisualProbeBounds(10, 70, 182, 224),
        registrationBounds = TicketVisualProbeBounds(20, 190, 170, 216),
        latest = true
      )
    ))
    assertFalse(ticketViviReauthOriginalTargetVisuallyAbsent(target, ambiguous))

    val timeEmpty = TicketVisualActionObservation(
      22,
      TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      ticketsTabBounds = tickets,
      bottomTab = TicketViviBottomTab.TICKETS
    )
    assertTrue(
      ticketViviReauthNoTicketProven(
        TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
        timeEmpty,
        7,
        11
      )
    )
    assertFalse(
      ticketViviReauthNoTicketProven(
        TicketVisualPhoneState.TICKETS_TIME_EMPTY,
        timeEmpty,
        7,
        11
      )
    )
    assertFalse(
      ticketViviReauthNoTicketProven(
        TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
        timeEmpty.copy(timeTicketsTabBounds = timeTickets),
        7,
        11
      )
    )
    assertFalse(
      ticketViviReauthNoTicketProven(
        TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
        timeEmpty,
        0,
        11
      )
    )
  }

  @Test
  fun v4OriginalRestoreCanReachTicketsFromEveryProvedBottomRoute() {
    val target = TicketViviReauthReturnTarget(
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      "d_1111111111111111aaaaaaaaaaaa"
    )
    val tickets = TicketVisualProbeBounds(48, 260, 96, 286)
    listOf(
      TicketVisualPhoneState.VIVI_HOME to TicketViviBottomTab.HOME,
      TicketVisualPhoneState.VIVI_PROFILE to TicketViviBottomTab.PROFILE,
      TicketVisualPhoneState.VIVI_OTHER_TAB to TicketViviBottomTab.MENU,
      TicketVisualPhoneState.UNKNOWN to TicketViviBottomTab.TICKETS
    ).forEachIndexed { index, (state, tab) ->
      assertEquals(
        TicketViviReauthRestoreTargetKind.TICKETS_TAB,
        ticketViviReauthV4OriginalRestoreTarget(
          target,
          TicketVisualActionObservation(
            30L + index,
            state,
            ticketsTabBounds = tickets,
            bottomTab = tab
          )
        )?.kind
      )
    }
    assertNull(
      ticketViviReauthV4OriginalRestoreTarget(
        target,
        TicketVisualActionObservation(
          40,
          TicketVisualPhoneState.UNKNOWN,
          ticketsTabBounds = tickets,
          bottomTab = TicketViviBottomTab.NONE
        )
      )
    )
    assertEquals(
      TicketViviReauthRestoreTargetKind.SINGLE_USE_TICKETS_TAB,
      ticketViviReauthV4OriginalRestoreTarget(
        target,
        TicketVisualActionObservation(
          41,
          TicketVisualPhoneState.TICKETS_TIME_EMPTY,
          ticketsTabBounds = tickets,
          bottomTab = TicketViviBottomTab.TICKETS
        )
      )?.kind
    )
  }

  @Test
  fun v4OriginalSearchTraversesTimeToSingleUseAndBackBeforeNoTicketProof() {
    val target = TicketViviReauthReturnTarget(
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      "d_1111111111111111aaaaaaaaaaaa"
    )
    val tickets = TicketVisualProbeBounds(48, 260, 96, 286)
    val timeTickets = TicketVisualProbeBounds(96, 40, 170, 66)
    val timeEmpty = TicketVisualActionObservation(
      50,
      TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      ticketsTabBounds = tickets,
      bottomTab = TicketViviBottomTab.TICKETS
    )
    assertEquals(
      TicketViviReauthRestoreTargetKind.SINGLE_USE_TICKETS_TAB,
      ticketViviReauthV4OriginalRestoreTarget(target, timeEmpty)?.kind
    )
    val singleUseEmpty = TicketVisualActionObservation(
      51,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      timeTicketsTabBounds = timeTickets,
      bottomTab = TicketViviBottomTab.TICKETS
    )
    assertEquals(
      TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB,
      ticketViviReauthV4OriginalRestoreTarget(target, singleUseEmpty)?.kind
    )
    assertTrue(
      ticketViviReauthNoTicketProven(
        singleUseEmpty.state,
        timeEmpty.copy(probeId = 52),
        9,
        13
      )
    )
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
    val returnTarget = TicketViviReauthReturnTarget(
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      "d_1111111111111111aaaaaaaaaaaa"
    )
    TicketViviBottomTab.entries.filterNot { it == TicketViviBottomTab.NONE }.forEach { tab ->
      val observation = classifiedLowerNavObservation(tab)
      assertTrue(
        "${tab.wireName}:${observation.state.wireName}",
        ticketViviReauthPostSubmitSignedInObservation(observation)
      )
      val restoreTarget = ticketViviReauthRestoreTarget(returnTarget, observation)
      if (tab == TicketViviBottomTab.HOME) {
        assertEquals(TicketViviReauthRestoreTargetKind.TICKETS_TAB, restoreTarget?.kind)
      } else {
        assertNull("${tab.wireName}:${observation.state.wireName}", restoreTarget)
      }
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
  fun v3InitialConsensusCarriesButCannotStartFromAnUnknownTicketsRoute() {
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
    assertFalse(ticketViviLogoutLoginStartObservation(stableTickets))

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
    assertFalse(
      ticketViviReauthJournalWriteProved(
        expected,
        { true },
        { expected.copy(redetectAfterLogin = true) }
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
      sliderBounds = classified.sliderBounds?.let {
        TicketVisualProbeBounds(it.left, it.top, it.right, it.bottom)
      },
      backBounds = classified.backBounds?.let {
        TicketVisualProbeBounds(it.left, it.top, it.right, it.bottom)
      },
      ticketsTabBounds = classified.ticketsTabBounds?.let {
        TicketVisualProbeBounds(it.left, it.top, it.right, it.bottom)
      },
      timeTicketsTabBounds = classified.timeTicketsTabBounds?.let {
        TicketVisualProbeBounds(it.left, it.top, it.right, it.bottom)
      },
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
