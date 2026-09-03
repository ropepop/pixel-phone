package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketVisualActionTest {
  private val json = Json

  @Test
  fun parsesAllSevenV3TargetsWithExactActivationIdentity() {
    TicketVisualActionTarget.entries.forEach { target ->
      val actionId = "action-${target.wireName}"
      val expectedRevision = if (target == TicketVisualActionTarget.REGISTER_CURRENT) "revision-7" else ""
      val switchAuthority = if (target in setOf(
          TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
          TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED
        )) {
        ",\"policyRevision\":\"policy-7\",\"switchExpiresAt\":\"2026-08-25T02:00:00Z\""
      } else {
        ""
      }
      val payload = json.parseToJsonElement(
        """{"version":3,"actionId":"$actionId","target":"${target.wireName}","source":"button","reason":"test","attemptId":"${if (target.activatesTicket) actionId else ""}","expectedInteractionRevision":"$expectedRevision"$switchAuthority}"""
      ).jsonObject
      assertEquals(target, parseTicketVisualActionRequest(payload)?.target)
    }
  }

  @Test
  fun rejectsWrongVersionAttemptAndMissingExactRevision() {
    assertNull(parse("""{"version":2,"actionId":"a","target":"redetect_latest"}"""))
    assertNull(parse("""{"version":3,"actionId":"a","target":"open_latest_and_register","attemptId":"b"}"""))
    assertNull(parse("""{"version":3,"actionId":"a","target":"register_current","attemptId":"a"}"""))
  }

  @Test
  fun rejectsServerRetryChildrenAndKeepsRetryInsideTheSameAction() {
    val parent = "register-parent"
    val child = "$parent-retry-1"
    val childPayload = """{"version":3,"actionId":"$child","target":"register_current","attemptId":"$parent","expectedInteractionRevision":"revision-7","parentActionId":"$parent","retryOrdinal":1}"""
    assertNull(parse(childPayload))

    val sameAction = parse(
      """{"version":3,"actionId":"$parent","target":"register_current","attemptId":"$parent","expectedInteractionRevision":"revision-7"}"""
    )
    assertEquals(parent, sameAction?.actionId)
    assertEquals(parent, sameAction?.attemptId)
  }

  @Test
  fun switchRequiresSpacetimeAuthorityAndOnlyVisualReadinessOnThePhone() {
    val ready = TicketVisualSwitchAnchors("activated", "unused")
    assertTrue(ready.visuallyReady)
    assertFalse(ready.copy(recentActivatedAnchor = "").visuallyReady)
    assertFalse(ready.copy(latestUnactivatedAnchor = "").visuallyReady)
    assertNull(parse(
      """{"version":3,"actionId":"switch","target":"show_recent_activated","attemptId":""}"""
    ))
    val admitted = parse(
      """{"version":3,"actionId":"switch","target":"show_recent_activated","attemptId":"","policyRevision":"policy-1","switchExpiresAt":"2026-08-25T02:00:00Z"}"""
    )!!
    assertTrue(admitted.hasSpacetimeSwitchAuthority)
    assertEquals("policy-1", admitted.policyRevision)
  }

  @Test
  fun listNavigationUsesRegistrationControlOnlyForUnactivatedTargets() {
    val cardBody = TicketVisualProbeBounds(8, 40, 184, 120)
    val registrationControl = TicketVisualProbeBounds(12, 100, 180, 116)
    val activatedDetailControl = TicketVisualProbeBounds(154, 75, 180, 95)
    val card = TicketVisualCardAnchor(
      anchor = "monthly-pass",
      bounds = cardBody,
      registrationBounds = registrationControl,
      activatedDetailBounds = activatedDetailControl,
      latest = true
    )

    assertEquals(registrationControl, card.navigationBoundsFor(TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED))
    assertEquals(registrationControl, card.navigationBoundsFor(TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER))
    assertEquals(registrationControl, card.navigationBoundsFor(TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED))
    assertEquals(activatedDetailControl, card.navigationBoundsFor(TicketVisualActionTarget.SHOW_RECENT_ACTIVATED))
    assertNull(card.navigationBoundsFor(TicketVisualActionTarget.REGISTER_CURRENT))
    assertEquals(registrationControl, card.navigationBoundsFor(TicketVisualActionTarget.REDETECT_LATEST))
    assertNull(card.copy(registrationBounds = null).navigationBoundsFor(TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED))
    assertNull(card.copy(activatedDetailBounds = null).navigationBoundsFor(TicketVisualActionTarget.SHOW_RECENT_ACTIVATED))
  }

  @Test
  fun duplicateVisualAnchorsFailClosed() {
    val observation = TicketVisualActionObservation(
      probeId = 2,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(
        TicketVisualCardAnchor("same", TicketVisualProbeBounds(0, 0, 10, 10), latest = true),
        TicketVisualCardAnchor("same", TicketVisualProbeBounds(0, 20, 10, 30), latest = true)
      )
    )
    assertNull(observation.latestCard())
    assertNull(observation.cardFor(
      TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
      TicketVisualSwitchAnchors(recentActivatedAnchor = "same")
    ))
  }

  @Test
  fun proveCurrentIsNonActivatingAndNeverSelectsAListCard() {
    val request = parse(
      """{"version":3,"actionId":"proof-a","target":"prove_current","source":"browser_auto_proof","reason":"stream_epoch","attemptId":""}"""
    )!!
    val card = TicketVisualCardAnchor(
      anchor = "opaque-latest",
      bounds = TicketVisualProbeBounds(8, 40, 184, 120),
      registrationBounds = TicketVisualProbeBounds(12, 100, 180, 116),
      latest = true
    )
    val list = TicketVisualActionObservation(
      probeId = 3,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(card)
    )

    assertEquals(TicketVisualActionTarget.PROVE_CURRENT, request.target)
    assertFalse(request.target.activatesTicket)
    assertNull(list.cardFor(request.target, TicketVisualSwitchAnchors()))
    assertNull(card.navigationBoundsFor(request.target))
  }

  @Test
  fun activatedCardSelectionPreservesExactIdentityAcrossMultipleCards() {
    val activated = TicketVisualCardAnchor(
      "activated-exact",
      TicketVisualProbeBounds(0, 20, 10, 30),
      activatedDetailBounds = TicketVisualProbeBounds(7, 22, 10, 28)
    )
    val observation = TicketVisualActionObservation(
      probeId = 3,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(
        TicketVisualCardAnchor("older", TicketVisualProbeBounds(0, 0, 10, 10)),
        activated,
        TicketVisualCardAnchor(
          "new-unused",
          TicketVisualProbeBounds(0, 40, 10, 50),
          registrationBounds = TicketVisualProbeBounds(0, 48, 10, 55),
          latest = true
        )
      )
    )

    assertEquals(
      activated,
      observation.cardFor(
        TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
        TicketVisualSwitchAnchors(recentActivatedAnchor = "activated-exact")
      )
    )
  }

  @Test
  fun activatedDetailNavigationUsesExactCardOrOneUniqueStatusTarget() {
    val unactivated = TicketVisualCardAnchor(
      "latest-current",
      TicketVisualProbeBounds(0, 40, 10, 50),
      registrationBounds = TicketVisualProbeBounds(0, 48, 10, 55),
      activatedDetailBounds = TicketVisualProbeBounds(7, 41, 10, 47),
      latest = true
    )
    val activated = TicketVisualCardAnchor(
      "activated",
      TicketVisualProbeBounds(0, 0, 10, 10),
      activatedDetailBounds = TicketVisualProbeBounds(7, 1, 10, 7)
    )
    val list = TicketVisualActionObservation(
      probeId = 4,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(activated, unactivated)
    )

    assertNull(list.activatedCardForRecentDetail(TicketVisualSwitchAnchors()))
    assertEquals(
      activated,
      list.activatedCardForRecentDetail(
        TicketVisualSwitchAnchors(recentActivatedAnchor = "activated")
      )
    )
    assertNull(list.copy(cards = listOf(
      activated,
      activated.copy(anchor = "other"),
      unactivated
    )).activatedCardForRecentDetail(
      TicketVisualSwitchAnchors(recentActivatedAnchor = "missing")
    ))
    assertNull(list.copy(cards = listOf(
      activated,
      activated.copy(bounds = TicketVisualProbeBounds(20, 0, 30, 10)),
      unactivated
    )).activatedCardForRecentDetail(
      TicketVisualSwitchAnchors(recentActivatedAnchor = "activated")
    ))

    val detailOnlyAnchor = TicketVisualSwitchAnchors(
      recentActivatedAnchor = "d_detail_only_identity"
    )
    // A detail-only identity cannot distinguish two registered-status targets.
    assertNull(list.activatedCardForRecentDetail(detailOnlyAnchor))
    val uniqueStatusList = list.copy(cards = listOf(
      activated.copy(activatedDetailBounds = null),
      unactivated
    ))
    assertEquals(unactivated, uniqueStatusList.activatedCardForRecentDetail(detailOnlyAnchor))
    assertNull(list.activatedCardForRecentDetail(
      TicketVisualSwitchAnchors(
        recentActivatedAnchor = "missing-date-card-anchor"
      )
    ))
    assertNull(list.copy(cards = listOf(
      activated,
      unactivated,
      unactivated.copy(anchor = "another-latest")
    )).activatedCardForRecentDetail(detailOnlyAnchor))
  }

  @Test
  fun controlCodeWithoutRetainedAnchorAcceptsOneUniqueRegisteredStatusTarget() {
    val eligible = TicketVisualCardAnchor(
      anchor = "date-card",
      bounds = TicketVisualProbeBounds(0, 20, 100, 80),
      registrationBounds = TicketVisualProbeBounds(5, 60, 95, 78),
      activatedDetailBounds = TicketVisualProbeBounds(78, 38, 95, 58),
      latest = true
    )
    val list = TicketVisualActionObservation(
      probeId = 30,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(eligible)
    )
    val noRetainedAnchor = TicketVisualSwitchAnchors()

    assertEquals(eligible, list.activatedCardForControlCode(noRetainedAnchor))
    assertTrue(ticketVisualControlCodeActivatedDetailProved(
      TicketVisualActionObservation(
        probeId = 31,
        state = TicketVisualPhoneState.ACTIVATED_DETAIL,
        currentAnchor = "d_reopened_activated_ticket"
      ),
      noRetainedAnchor
    ))
  }

  @Test
  fun controlCodeWithoutRetainedAnchorRejectsZeroOrMultipleRegisteredStatusTargets() {
    val genericOnly = TicketVisualCardAnchor(
      anchor = "generic-card",
      bounds = TicketVisualProbeBounds(0, 20, 100, 80),
      registrationBounds = TicketVisualProbeBounds(5, 60, 95, 78),
      latest = true
    )
    val eligible = genericOnly.copy(
      anchor = "eligible-one",
      activatedDetailBounds = TicketVisualProbeBounds(78, 38, 95, 58)
    )
    val noRetainedAnchor = TicketVisualSwitchAnchors()

    assertNull(TicketVisualActionObservation(
      probeId = 32,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(genericOnly)
    ).activatedCardForControlCode(noRetainedAnchor))
    assertNull(TicketVisualActionObservation(
      probeId = 33,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(eligible, eligible.copy(anchor = "eligible-two"))
    ).activatedCardForControlCode(noRetainedAnchor))
  }

  @Test
  fun controlCodeRetainedAnchorMismatchAndWrongDetailFailClosed() {
    val eligible = TicketVisualCardAnchor(
      anchor = "actual-card",
      bounds = TicketVisualProbeBounds(0, 20, 100, 80),
      activatedDetailBounds = TicketVisualProbeBounds(78, 38, 95, 58),
      latest = true
    )
    val list = TicketVisualActionObservation(
      probeId = 34,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(eligible)
    )
    assertNull(list.activatedCardForControlCode(
      TicketVisualSwitchAnchors(recentActivatedAnchor = "different-card")
    ))

    val detailAnchor = TicketVisualSwitchAnchors(recentActivatedAnchor = "d_expected_ticket")
    assertEquals(eligible, list.activatedCardForControlCode(detailAnchor))
    assertFalse(ticketVisualControlCodeActivatedDetailProved(
      TicketVisualActionObservation(
        probeId = 35,
        state = TicketVisualPhoneState.ACTIVATED_DETAIL,
        currentAnchor = "d_wrong_ticket"
      ),
      detailAnchor
    ))
    assertTrue(ticketVisualControlCodeActivatedDetailProved(
      TicketVisualActionObservation(
        probeId = 36,
        state = TicketVisualPhoneState.ACTIVATED_DETAIL,
        currentAnchor = "d_expected_ticket"
      ),
      detailAnchor
    ))
  }

  @Test
  fun missingActivatedTicketCanRestoreOnlyTheExactStartingUnactivatedDetail() {
    val initial = TicketVisualActionObservation(
      probeId = 37,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "d_exact_unused_ticket"
    )

    assertTrue(ticketVisualControlCodeUnactivatedDetailRestored(
      initial,
      initial.copy(probeId = 38)
    ))
    assertFalse(ticketVisualControlCodeUnactivatedDetailRestored(
      initial,
      initial.copy(probeId = 39, currentAnchor = "d_other_unused_ticket")
    ))
    assertFalse(ticketVisualControlCodeUnactivatedDetailRestored(
      initial,
      initial.copy(probeId = 40, state = TicketVisualPhoneState.ACTIVATED_DETAIL)
    ))
  }

  @Test
  fun showRecentWorksForOpenAndRegisterCardIdentityAndRegisterCurrentDetailIdentity() {
    val reopenedActivatedDetail = TicketVisualActionObservation(
      probeId = 20,
      state = TicketVisualPhoneState.ACTIVATED_DETAIL,
      currentAnchor = "d_static_ticket_signature"
    )

    // open_latest_and_register starts from a resolved list card, so its activation anchor is the
    // cross-view card identity and the reopened detail is rebound to that identity.
    val openedAndRegistered = ticketVisualObservationAfterRecentActivatedSelection(
      observation = reopenedActivatedDetail,
      selectedCardAnchor = "card_date_identity",
      recentActivatedAnchor = "card_date_identity"
    )
    assertEquals("card_date_identity", openedAndRegistered.currentAnchor)

    // register_current can start from prove_current, so the only activation identity is the
    // detail signature. The unique status target authorizes the tap, but cannot replace it.
    val registeredCurrent = ticketVisualObservationAfterRecentActivatedSelection(
      observation = reopenedActivatedDetail,
      selectedCardAnchor = "card_date_identity",
      recentActivatedAnchor = "d_static_ticket_signature"
    )
    assertEquals("d_static_ticket_signature", registeredCurrent.currentAnchor)

    val wrongDetail = reopenedActivatedDetail.copy(currentAnchor = "d_other_ticket_signature")
    val preservedWrongDetail = ticketVisualObservationAfterRecentActivatedSelection(
      observation = wrongDetail,
      selectedCardAnchor = "card_date_identity",
      recentActivatedAnchor = "d_static_ticket_signature"
    )
    assertEquals("d_other_ticket_signature", preservedWrongDetail.currentAnchor)
    assertFalse(preservedWrongDetail.currentAnchor == "d_static_ticket_signature")
  }

  @Test
  fun freshDetailFramesAllowSmallGeometryDriftButRejectConflictingAnchors() {
    val first = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "opaque-a",
      sliderBounds = TicketVisualProbeBounds(10, 20, 180, 42),
      backBounds = TicketVisualProbeBounds(165, 2, 184, 20)
    )
    val smallRasterDrift = first.copy(
      probeId = 2,
      currentAnchor = "opaque-a",
      sliderBounds = TicketVisualProbeBounds(11, 19, 181, 43),
      backBounds = TicketVisualProbeBounds(164, 3, 185, 21)
    )

    assertTrue(ticketVisualObservationsAgree(first, smallRasterDrift))
    assertFalse(ticketVisualObservationsAgree(
      first,
      smallRasterDrift.copy(currentAnchor = "")
    ))
    assertFalse(ticketVisualObservationsAgree(
      first,
      smallRasterDrift.copy(currentAnchor = "opaque-b")
    ))
    assertFalse(ticketVisualObservationsAgree(
      first,
      smallRasterDrift.copy(sliderBounds = TicketVisualProbeBounds(20, 19, 181, 43))
    ))
  }

  @Test
  fun freshActivatedDetailAgreementDoesNotRequireBackGeometryForViewing() {
    val first = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.ACTIVATED_DETAIL,
      currentAnchor = "opaque-activated"
    )
    val second = first.copy(probeId = 2)

    assertTrue(ticketVisualObservationsAgree(first, second))
    assertFalse(ticketVisualObservationsAgree(
      first,
      second.copy(state = TicketVisualPhoneState.UNKNOWN)
    ))
  }

  @Test
  fun freshRouteAgreementCannotCombineDifferentSelectedBottomTabs() {
    val first = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.VIVI_OTHER_TAB,
      bottomTab = TicketViviBottomTab.TICKETS
    )
    val second = first.copy(probeId = 2)

    assertTrue(ticketVisualObservationsAgree(first, second))
    assertFalse(
      ticketVisualObservationsAgree(
        first,
        second.copy(bottomTab = TicketViviBottomTab.MENU)
      )
    )
    val detail = first.copy(
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "detail-a",
      sliderBounds = TicketVisualProbeBounds(10, 20, 180, 42)
    )
    assertTrue(
      ticketVisualObservationsAgree(
        detail,
        detail.copy(bottomTab = TicketViviBottomTab.MENU)
      )
    )
  }

  @Test
  fun viviHomeAgreementRequiresTheSameTicketsTabGeometry() {
    val first = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.VIVI_HOME,
      ticketsTabBounds = TicketVisualProbeBounds(46, 254, 82, 283)
    )
    val settled = first.copy(
      probeId = 2,
      ticketsTabBounds = TicketVisualProbeBounds(45, 255, 83, 282)
    )

    assertTrue(ticketVisualObservationsAgree(first, settled))
    assertFalse(ticketVisualObservationsAgree(
      first,
      settled.copy(ticketsTabBounds = TicketVisualProbeBounds(55, 255, 90, 282))
    ))
    assertFalse(ticketVisualObservationsAgree(first, settled.copy(ticketsTabBounds = null)))
  }

  @Test
  fun emptySingleUseTicketsAgreementRequiresTheSameTimeTabGeometry() {
    val bounds = TicketVisualProbeBounds(114, 22, 164, 33)
    val first = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      timeTicketsTabBounds = bounds
    )
    val settled = first.copy(
      probeId = 2,
      timeTicketsTabBounds = TicketVisualProbeBounds(113, 23, 165, 32)
    )

    assertTrue(ticketVisualObservationsAgree(first, settled))
    assertFalse(ticketVisualObservationsAgree(
      first,
      settled.copy(timeTicketsTabBounds = TicketVisualProbeBounds(102, 23, 153, 32))
    ))
    assertFalse(ticketVisualObservationsAgree(first, settled.copy(timeTicketsTabBounds = null)))
  }

  @Test
  fun emptySingleUseTicketsShellAllowsOnlyNavigationTargetsToUseTheTimeTab() {
    val bounds = TicketVisualProbeBounds(114, 22, 164, 33)
    val observation = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      timeTicketsTabBounds = bounds
    )
    val allowed = setOf(
      TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
      TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
      TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
      TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED,
      TicketVisualActionTarget.REDETECT_LATEST
    )

    TicketVisualActionTarget.entries.forEach { target ->
      assertEquals(
        if (target in allowed) bounds else null,
        observation.timeTicketsNavigationBoundsFor(target)
      )
    }
    assertNull(observation.copy(state = TicketVisualPhoneState.UNKNOWN)
      .timeTicketsNavigationBoundsFor(TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED))
  }

  @Test
  fun emptyTimeTicketsShellAllowsOnlyRedetectionToRecheckSingleUse() {
    val bounds = TicketVisualProbeBounds(24, 22, 84, 33)
    val observation = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      ticketsTabBounds = bounds
    )

    TicketVisualActionTarget.entries.forEach { target ->
      assertEquals(
        if (target == TicketVisualActionTarget.REDETECT_LATEST) bounds else null,
        observation.singleUseTicketsNavigationBoundsFor(target)
      )
    }
    assertNull(observation.copy(state = TicketVisualPhoneState.UNKNOWN)
      .singleUseTicketsNavigationBoundsFor(TicketVisualActionTarget.REDETECT_LATEST))
  }

  @Test
  fun emptyTimeTicketsAgreementRequiresTheSameSingleUseTabGeometry() {
    val bounds = TicketVisualProbeBounds(24, 22, 84, 33)
    val first = TicketVisualActionObservation(
      probeId = 1,
      state = TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      ticketsTabBounds = bounds
    )
    val settled = first.copy(
      probeId = 2,
      ticketsTabBounds = TicketVisualProbeBounds(23, 23, 85, 32)
    )

    assertTrue(ticketVisualObservationsAgree(first, settled))
    assertFalse(ticketVisualObservationsAgree(
      first,
      settled.copy(ticketsTabBounds = TicketVisualProbeBounds(40, 23, 100, 32))
    ))
    assertFalse(ticketVisualObservationsAgree(first, settled.copy(ticketsTabBounds = null)))
  }

  @Test
  fun ticketListAgreementRequiresTheSameAnchorsLatestChoiceAndGeometry() {
    val card = TicketVisualCardAnchor(
      anchor = "opaque-latest",
      bounds = TicketVisualProbeBounds(8, 40, 184, 120),
      registrationBounds = TicketVisualProbeBounds(12, 100, 180, 116),
      latest = true
    )
    val first = TicketVisualActionObservation(1, TicketVisualPhoneState.TICKET_LIST, cards = listOf(card))
    val second = TicketVisualActionObservation(
      2,
      TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(card.copy(
        bounds = TicketVisualProbeBounds(9, 39, 185, 121),
        registrationBounds = TicketVisualProbeBounds(11, 101, 181, 117)
      ))
    )

    assertTrue(ticketVisualObservationsAgree(first, second))
    assertFalse(ticketVisualObservationsAgree(first, second.copy(cards = emptyList())))
    assertFalse(ticketVisualObservationsAgree(
      first,
      second.copy(cards = listOf(second.cards.single().copy(anchor = "different")))
    ))
  }

  @Test
  fun observationConsensusRetainsOneValidListAcrossUnknownTransitionFrames() {
    val card = TicketVisualCardAnchor(
      anchor = "opaque-latest",
      bounds = TicketVisualProbeBounds(8, 40, 184, 120),
      registrationBounds = TicketVisualProbeBounds(12, 100, 180, 116),
      latest = true
    )
    val first = TicketVisualActionObservation(
      probeId = 10,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(card)
    )
    val consensus = TicketVisualObservationConsensus()

    assertNull(consensus.offer(TicketVisualActionObservation(
      probeId = 9,
      state = TicketVisualPhoneState.UNKNOWN
    )))
    assertNull(consensus.offer(first))
    assertNull(consensus.offer(TicketVisualActionObservation(
      probeId = 11,
      state = TicketVisualPhoneState.UNKNOWN
    )))
    assertEquals(
      12L,
      consensus.offer(first.copy(probeId = 12))?.probeId
    )
  }

  @Test
  fun observationConsensusIgnoresTransientUnknownBeforeLoginProof() {
    val consensus = TicketVisualObservationConsensus()

    assertNull(consensus.offer(
      TicketVisualActionObservation(1, TicketVisualPhoneState.UNKNOWN),
      allowUnknown = false
    ))
    assertNull(consensus.offer(
      TicketVisualActionObservation(2, TicketVisualPhoneState.UNKNOWN),
      allowUnknown = false
    ))
    assertNull(consensus.offer(
      TicketVisualActionObservation(3, TicketVisualPhoneState.LOGIN_REQUIRED),
      allowUnknown = false
    ))
    assertEquals(
      TicketVisualPhoneState.LOGIN_REQUIRED,
      consensus.offer(
        TicketVisualActionObservation(4, TicketVisualPhoneState.LOGIN_REQUIRED),
        allowUnknown = false
      )?.state
    )
  }

  @Test
  fun observationConsensusRejectsSingleDuplicateAndConflictingListProofs() {
    val card = TicketVisualCardAnchor(
      anchor = "opaque-latest",
      bounds = TicketVisualProbeBounds(8, 40, 184, 120),
      registrationBounds = TicketVisualProbeBounds(12, 100, 180, 116),
      latest = true
    )
    val first = TicketVisualActionObservation(
      probeId = 20,
      state = TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(card)
    )
    val consensus = TicketVisualObservationConsensus()

    assertNull(consensus.offer(first))
    assertNull(consensus.offer(first))
    assertNull(consensus.offer(first.copy(
      probeId = 21,
      cards = listOf(card.copy(anchor = "opaque-conflict"))
    )))
    assertNull(consensus.offer(first.copy(probeId = 22)))
  }

  @Test
  fun observationConsensusResetRequiresTwoNewProbesAfterCaptureReplacement() {
    val first = TicketVisualActionObservation(
      probeId = 30,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "opaque-detail",
      sliderBounds = TicketVisualProbeBounds(10, 200, 180, 230)
    )
    val consensus = TicketVisualObservationConsensus()

    assertNull(consensus.offer(first))
    consensus.reset()
    assertNull(consensus.offer(first.copy(probeId = 31)))
    assertEquals(32L, consensus.offer(first.copy(probeId = 32))?.probeId)
  }

  @Test
  fun redetectionRequiresTwoAgreeingGeometryFramesBeforeAuthorizingATap() {
    val latest = TicketVisualCardAnchor(
      anchor = "opaque-latest",
      bounds = TicketVisualProbeBounds(8, 40, 184, 120),
      registrationBounds = TicketVisualProbeBounds(12, 100, 180, 116),
      latest = true
    )
    val older = TicketVisualCardAnchor(
      anchor = "opaque-older",
      bounds = TicketVisualProbeBounds(8, 150, 184, 230),
      registrationBounds = null,
      latest = false
    )
    val first = TicketVisualActionObservation(
      1,
      TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(latest, older)
    )
    val settled = TicketVisualActionObservation(
      2,
      TicketVisualPhoneState.TICKET_LIST,
      cards = listOf(
        older.copy(bounds = TicketVisualProbeBounds(8, 143, 184, 223)),
        latest.copy(
          bounds = TicketVisualProbeBounds(8, 33, 184, 113),
          registrationBounds = TicketVisualProbeBounds(12, 93, 180, 109)
        )
      )
    )

    val consensus = TicketVisualObservationConsensus()

    assertNull(consensus.offer(first))
    assertNull(consensus.offer(settled))
    assertEquals(3L, consensus.offer(settled.copy(probeId = 3))?.probeId)
  }

  @Test
  fun registrationProofCannotAuthorizeAVisuallyDifferentTicket() {
    val proof = TicketRegistrationProof(
      status = "unactivated_ready",
      reason = "test",
      interactionRevision = "revision",
      streamEpoch = 1,
      frameSequence = 2,
      phoneDisplayWidth = 1080,
      phoneDisplayHeight = 2400,
      ticketAnchor = "card-a",
      detailAnchor = "detail-a"
    )
    assertTrue(ticketRegistrationProofMatchesVisualAnchor(proof, "card-a"))
    assertFalse(ticketRegistrationProofMatchesVisualAnchor(proof, "card-b"))
    assertTrue(ticketRegistrationProofMatchesVisualDetail(proof, "detail-a"))
    assertFalse(ticketRegistrationProofMatchesVisualDetail(proof, ""))
    assertFalse(ticketRegistrationProofMatchesVisualDetail(proof, "detail-b"))
    assertFalse(ticketRegistrationProofMatchesVisualDetail(proof.copy(ticketAnchor = ""), "detail-a"))
  }

  @Test
  fun delayedRegisterKeepsTheCardAnchorSeparateFromTheDetailSignature() {
    val detail = TicketVisualActionObservation(
      probeId = 2,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "d_detail_signature"
    )
    val proof = TicketRegistrationProof(
      status = "unactivated_ready",
      reason = "test",
      interactionRevision = "revision",
      streamEpoch = 7,
      frameSequence = 10,
      phoneDisplayWidth = 1080,
      phoneDisplayHeight = 2400,
      ticketAnchor = "card-a",
      detailAnchor = "d_detail_signature"
    )

    assertEquals("card-a", ticketVisualProvenTicketAnchor(detail, proof))
    assertEquals("d_detail_signature", ticketVisualProvenTicketAnchor(detail, null))
  }

  @Test
  fun registerCurrentRequiresTheSameFreshDetailIdentityAndUsesFreshGeometry() {
    val request = parse(
      """{"version":3,"actionId":"a","target":"register_current","source":"test","reason":"test","attemptId":"a","expectedInteractionRevision":"revision"}"""
    )!!
    val proof = TicketRegistrationProof(
      status = "unactivated_ready",
      reason = "test",
      interactionRevision = "revision",
      streamEpoch = 7,
      frameSequence = 10,
      phoneDisplayWidth = 1080,
      phoneDisplayHeight = 2400,
      provedAtUptimeMillis = 1_000,
      ticketAnchor = "card-a",
      detailAnchor = "detail-a"
      // Prior geometry is intentionally absent. The fresh observation owns gesture geometry.
    )
    val currentDetail = TicketVisualActionObservation(
      probeId = 11,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "detail-a",
      sliderBounds = TicketVisualProbeBounds(10, 20, 180, 42),
      bottomTab = TicketViviBottomTab.TICKETS
    )

    val admitted = ticketRegistrationProofForCurrentVisualAction(
      proof,
      request,
      currentDetail
    )
    assertEquals(proof, admitted.proof)
    assertNull(admitted.failureReason)

    val scheduledProof = proof.copy(interactionRevision = "schedule:revision")
    val admittedScheduledProof = ticketRegistrationProofForCurrentVisualAction(
      scheduledProof,
      request,
      currentDetail
    )
    assertEquals(proof, admittedScheduledProof.proof)
    assertNull(admittedScheduledProof.failureReason)
    assertNull(
      ticketRegistrationProofRevisionForRegisterCurrent(
        proofRevision = "schedule:schedule:revision",
        expectedRevision = "schedule:revision"
      )
    )

    listOf(
      "schedule:different",
      "legacy:revision",
      "schedule:schedule:revision",
      "schedule:revision ",
      "revision-suffix",
      ""
    ).forEach { rejectedRevision ->
      val rejected = ticketRegistrationProofForCurrentVisualAction(
        proof.copy(interactionRevision = rejectedRevision),
        request,
        currentDetail
      )
      assertNull(rejectedRevision, rejected.proof)
      assertEquals(
        rejectedRevision,
        "ticket_action_interaction_revision_unproved",
        rejected.failureReason
      )
    }

    val changedIdentity = ticketRegistrationProofForCurrentVisualAction(
      proof,
      request,
      currentDetail.copy(currentAnchor = "different-detail")
    )
    assertNull(changedIdentity.proof)
    assertEquals("ticket_action_detail_identity_conflict", changedIdentity.failureReason)

    val blankIdentity = ticketRegistrationProofForCurrentVisualAction(
      proof,
      request,
      currentDetail.copy(currentAnchor = "")
    )
    assertNull(blankIdentity.proof)
    assertEquals("ticket_action_detail_identity_conflict", blankIdentity.failureReason)
    // The old proof contributes only the exact durable revision and opaque ticket identity.
    // A fresh two-frame detail observation and a new encoded watermark provide action-time
    // freshness, so an otherwise idle open view must not make the Register button fail.
    val idleIdentityProof = proof.copy(provedAtUptimeMillis = 1)
    assertEquals(idleIdentityProof, ticketRegistrationProofForCurrentVisualAction(
      idleIdentityProof,
      request,
      currentDetail
    ).proof)
    // Ticket detail is a full-screen overlay with no bottom navigation. A guessed yellow region
    // must not decide whether the exact browser-authorized ticket can reach the preparation fence.
    assertEquals(proof, ticketRegistrationProofForCurrentVisualAction(
      proof,
      request,
      currentDetail.copy(bottomTab = TicketViviBottomTab.NONE)
    ).proof)
    assertEquals(proof, ticketRegistrationProofForCurrentVisualAction(
      proof,
      request,
      currentDetail.copy(bottomTab = TicketViviBottomTab.HOME)
    ).proof)
    val invalidWatermark = ticketRegistrationProofForCurrentVisualAction(
      proof.copy(streamEpoch = 0, frameSequence = 0),
      request,
      currentDetail
    )
    assertNull(invalidWatermark.proof)
    assertEquals("ticket_action_interaction_revision_unproved", invalidWatermark.failureReason)
  }

  @Test
  fun exactCardSelectionCarriesItsOpaqueIdentityIntoTheTypedDetailView() {
    val detail = TicketVisualActionObservation(
      probeId = 8,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "detail-date",
      sliderBounds = TicketVisualProbeBounds(10, 20, 180, 42)
    )
    val selected = ticketVisualObservationAfterCardSelection(detail, "card-date")
    assertEquals("card-date", selected.currentAnchor)
    assertEquals(detail.sliderBounds, selected.sliderBounds)
    assertEquals("detail-date", detail.currentAnchor)
    assertEquals(detail, ticketVisualObservationAfterCardSelection(detail, ""))
    assertEquals(
      "",
      ticketVisualObservationAfterCardSelection(
        TicketVisualActionObservation(9, TicketVisualPhoneState.TICKET_LIST),
        "card-date"
      ).currentAnchor
    )
  }

  @Test
  fun postGestureClassificationRequiresTheSameFreshUnactivatedIdentity() {
    val sameUnactivated = TicketVisualActionObservation(
      probeId = 20,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "detail-a"
    )
    val differentUnactivated = sameUnactivated.copy(
      probeId = 21,
      currentAnchor = "detail-b"
    )
    val blankUnactivated = sameUnactivated.copy(
      probeId = 22,
      currentAnchor = ""
    )
    val unknown = sameUnactivated.copy(
      probeId = 23,
      state = TicketVisualPhoneState.UNKNOWN,
      currentAnchor = "detail-a"
    )

    assertEquals(
      "ticket_action_gesture_completed_no_transition",
      ticketVisualPostGestureFailureReason(sameUnactivated, "detail-a")
    )
    listOf(differentUnactivated, blankUnactivated, unknown).forEach { observation ->
      assertEquals(
        "ticket_action_post_gesture_visual_unproved",
        ticketVisualPostGestureFailureReason(observation, "detail-a")
      )
    }
    assertEquals(
      "ticket_action_post_gesture_visual_unproved",
      ticketVisualPostGestureFailureReason(null, "detail-a")
    )

    assertEquals(
      "detail-b",
      ticketVisualActivationObservationAfterCompletedGesture(
        differentUnactivated,
        "detail-a"
      )?.currentAnchor
    )
    assertEquals(
      "",
      ticketVisualActivationObservationAfterCompletedGesture(
        blankUnactivated,
        "detail-a"
      )?.currentAnchor
    )
    assertEquals(
      "detail-a",
      ticketVisualActivationObservationAfterCompletedGesture(
        unknown,
        "detail-a"
      )?.currentAnchor
    )
    assertEquals(
      "detail-a",
      ticketVisualActivationObservationAfterCompletedGesture(
        sameUnactivated.copy(state = TicketVisualPhoneState.ACTIVATED_DETAIL, currentAnchor = ""),
        "detail-a"
      )?.currentAnchor
    )
  }

  @Test
  fun terminalFailureKeepsTheSelectedCardIdentityForReconciliation() {
    val prior = TicketVisualActionJournalState(
      actionId = "a",
      target = "open_latest_unactivated",
      phase = "navigation_reconciled",
      intendedAnchor = "selected-card"
    )
    val conflictingDetail = TicketVisualActionObservation(
      probeId = 10,
      state = TicketVisualPhoneState.UNACTIVATED_DETAIL,
      currentAnchor = "unrelated-detail-date"
    )
    assertEquals(
      "selected-card",
      ticketVisualTerminalIntendedAnchor(prior, conflictingDetail)
    )
    assertEquals(
      "unrelated-detail-date",
      ticketVisualTerminalIntendedAnchor(prior.copy(intendedAnchor = ""), conflictingDetail)
    )
  }

  @Test
  fun restartJournalOnlyAcceptsExactPostTapVisualReconciliation() {
    val request = parse(
      """{"version":3,"actionId":"a","target":"open_latest_unactivated","source":"test","reason":"test","attemptId":""}"""
    )!!
    val journal = TicketVisualActionJournalState(
      actionId = "a",
      target = "open_latest_unactivated",
      phase = "navigation_dispatched",
      intendedAnchor = "anchor-a",
      navigationFromState = "ticket_list",
      navigationToState = "unactivated_detail",
      navigationAnchor = "anchor-a"
    )
    val matching = TicketVisualActionObservation(2, TicketVisualPhoneState.UNACTIVATED_DETAIL, "detail-a")
    assertTrue(ticketVisualJournalReconciled(journal, request, matching))
    // The unique persisted list tap is the causal card identity. A separate non-empty salted
    // detail identity proves that a real detail was reached without being mistaken for the card
    // anchor itself; it is retained separately before the executor rebinds the card identity.
    assertFalse(ticketVisualJournalReconciled(journal, request, matching.copy(currentAnchor = "")))
    assertTrue(ticketVisualJournalReconciled(journal, request, matching.copy(currentAnchor = "detail-b")))
    assertFalse(ticketVisualJournalReconciled(journal.copy(actionId = "other"), request, matching))
  }

  @Test
  fun restartJournalReconcilesDetailToListWithoutInventingAnAnchor() {
    val request = parse(
      """{"version":3,"actionId":"a","target":"open_latest_unactivated","source":"test","reason":"test","attemptId":""}"""
    )!!
    val journal = TicketVisualActionJournalState(
      actionId = "a",
      target = "open_latest_unactivated",
      phase = "navigation_dispatched",
      navigationFromState = "activated_detail",
      navigationToState = "ticket_list",
      navigationAnchor = ""
    )
    assertTrue(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(3, TicketVisualPhoneState.TICKET_LIST)
    ))
    assertFalse(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(4, TicketVisualPhoneState.UNACTIVATED_DETAIL)
    ))
  }

  @Test
  fun restartJournalReconcilesTheOneHomeToTicketsTabTap() {
    val request = parse(
      """{"version":3,"actionId":"a","target":"open_latest_unactivated","source":"test","reason":"test","attemptId":""}"""
    )!!
    val journal = TicketVisualActionJournalState(
      actionId = "a",
      target = "open_latest_unactivated",
      phase = "navigation_dispatched",
      navigationFromState = "vivi_home",
      navigationToState = "ticket_list"
    )

    assertTrue(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(3, TicketVisualPhoneState.TICKET_LIST)
    ))
    assertTrue(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(4, TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY)
    ))
    assertFalse(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(5, TicketVisualPhoneState.VIVI_HOME)
    ))
    assertFalse(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(6, TicketVisualPhoneState.UNKNOWN)
    ))

    val redetect = parse(
      """{"version":3,"actionId":"a","target":"redetect_latest","source":"test","reason":"test","attemptId":""}"""
    )!!
    assertTrue(ticketVisualJournalReconciled(
      journal.copy(target = redetect.target.wireName),
      redetect,
      TicketVisualActionObservation(
        probeId = 7,
        state = TicketVisualPhoneState.TICKETS_TIME_EMPTY,
        ticketsTabBounds = TicketVisualProbeBounds(24, 22, 84, 33)
      )
    ))
    assertFalse(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(
        probeId = 8,
        state = TicketVisualPhoneState.TICKETS_TIME_EMPTY,
        ticketsTabBounds = TicketVisualProbeBounds(24, 22, 84, 33)
      )
    ))
  }

  @Test
  fun restartJournalAcceptsOnlyTheDistinctEmptyTimeTabForRedetection() {
    val request = parse(
      """{"version":3,"actionId":"a","target":"redetect_latest","source":"test","reason":"test","attemptId":""}"""
    )!!
    val journal = TicketVisualActionJournalState(
      actionId = "a",
      target = "redetect_latest",
      phase = "navigation_dispatched",
      navigationFromState = "tickets_single_use_empty",
      navigationToState = "ticket_list"
    )

    assertTrue(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(3, TicketVisualPhoneState.TICKET_LIST)
    ))
    assertFalse(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(4, TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY)
    ))
    assertTrue(ticketVisualJournalReconciled(
      journal,
      request,
      TicketVisualActionObservation(5, TicketVisualPhoneState.TICKETS_TIME_EMPTY)
    ))

    val openRequest = parse(
      """{"version":3,"actionId":"a","target":"open_latest_unactivated","source":"test","reason":"test","attemptId":""}"""
    )!!
    assertFalse(ticketVisualJournalReconciled(
      journal.copy(target = "open_latest_unactivated"),
      openRequest,
      TicketVisualActionObservation(6, TicketVisualPhoneState.TICKETS_TIME_EMPTY)
    ))
  }

  @Test
  fun repeatedRedetectionReprovesBothTabsAndCrashReconcilesEachTap() {
    val request = parse(
      """{"version":3,"actionId":"repeat","target":"redetect_latest","source":"test","reason":"test","attemptId":""}"""
    )!!
    val backToSingleUse = TicketVisualActionJournalState(
      actionId = "repeat",
      target = "redetect_latest",
      phase = "navigation_dispatched",
      navigationFromState = "tickets_time_empty",
      navigationToState = "ticket_list"
    )
    assertTrue(ticketVisualJournalReconciled(
      backToSingleUse,
      request,
      TicketVisualActionObservation(
        probeId = 3,
        state = TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
        timeTicketsTabBounds = TicketVisualProbeBounds(114, 22, 164, 33)
      )
    ))
    // If a Single-use ticket appeared since the last cycle, the same owned tap may instead prove
    // a non-empty list and normal latest-card discovery resumes.
    assertTrue(ticketVisualJournalReconciled(
      backToSingleUse,
      request,
      TicketVisualActionObservation(4, TicketVisualPhoneState.TICKET_LIST)
    ))
    assertFalse(ticketVisualJournalReconciled(
      backToSingleUse,
      request,
      TicketVisualActionObservation(
        probeId = 5,
        state = TicketVisualPhoneState.TICKETS_TIME_EMPTY,
        ticketsTabBounds = TicketVisualProbeBounds(24, 22, 84, 33)
      )
    ))

    val forwardToTime = backToSingleUse.copy(
      navigationFromState = "tickets_single_use_empty"
    )
    val emptyTime = TicketVisualActionObservation(
      probeId = 6,
      state = TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      ticketsTabBounds = TicketVisualProbeBounds(24, 22, 84, 33)
    )
    assertTrue(ticketVisualJournalReconciled(forwardToTime, request, emptyTime))
    assertTrue(ticketVisualRedetectLatestNotDetectedObservation(
      request.target,
      TicketVisualPhoneState.fromWireName(forwardToTime.navigationFromState),
      emptyTime
    ))

    val otherRequest = parse(
      """{"version":3,"actionId":"repeat","target":"open_latest_unactivated","source":"test","reason":"test","attemptId":""}"""
    )!!
    assertFalse(ticketVisualJournalReconciled(
      backToSingleUse.copy(target = otherRequest.target.wireName),
      otherRequest,
      TicketVisualActionObservation(
        probeId = 7,
        state = TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
        timeTicketsTabBounds = TicketVisualProbeBounds(114, 22, 164, 33)
      )
    ))
  }

  @Test
  fun latestNotDetectedProofRequiresOwnedTimeTabTransitionExactEmptyStateAndWatermark() {
    val emptyTime = TicketVisualActionObservation(
      probeId = 9,
      state = TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      ticketsTabBounds = TicketVisualProbeBounds(24, 22, 84, 33)
    )
    assertTrue(ticketVisualRedetectLatestNotDetectedObservation(
      TicketVisualActionTarget.REDETECT_LATEST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      emptyTime
    ))
    assertTrue(ticketVisualRedetectLatestNotDetectedProof(
      TicketVisualActionTarget.REDETECT_LATEST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      emptyTime,
      streamEpoch = 4,
      frameSequence = 12
    ))
    assertFalse(ticketVisualRedetectLatestNotDetectedProof(
      TicketVisualActionTarget.REDETECT_LATEST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      emptyTime,
      streamEpoch = 0,
      frameSequence = 12
    ))
    assertFalse(ticketVisualRedetectLatestNotDetectedObservation(
      TicketVisualActionTarget.REDETECT_LATEST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      emptyTime.copy(state = TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY)
    ))
    assertFalse(ticketVisualRedetectLatestNotDetectedObservation(
      TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      emptyTime
    ))
    assertFalse(ticketVisualRedetectLatestNotDetectedObservation(
      TicketVisualActionTarget.REDETECT_LATEST,
      TicketVisualPhoneState.VIVI_HOME,
      emptyTime
    ))
    assertFalse(ticketVisualRedetectLatestNotDetectedObservation(
      TicketVisualActionTarget.REDETECT_LATEST,
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      emptyTime.copy(cards = listOf(TicketVisualCardAnchor(
        anchor = "opaque",
        bounds = TicketVisualProbeBounds(8, 40, 184, 120),
        latest = true
      )))
    ))
    val conflictingBounds = TicketVisualProbeBounds(10, 10, 20, 20)
    listOf(
      emptyTime.copy(controlCodeBounds = conflictingBounds),
      emptyTime.copy(backBounds = conflictingBounds),
      emptyTime.copy(ticketsTabBounds = null),
      emptyTime.copy(timeTicketsTabBounds = conflictingBounds),
      emptyTime.copy(sliderBounds = conflictingBounds)
    ).forEach { conflict ->
      assertFalse(ticketVisualRedetectLatestNotDetectedObservation(
        TicketVisualActionTarget.REDETECT_LATEST,
        TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
        conflict
      ))
    }
  }

  @Test
  fun latestNotDetectedTerminalProofShapeRejectsEveryAdjacentTerminal() {
    val valid = TicketVisualActionSnapshot(
      actionId = "redetect-1",
      target = TicketVisualActionTarget.REDETECT_LATEST.wireName,
      status = "failed",
      phase = "failed",
      currentView = TicketVisualActionView.UNKNOWN,
      streamEpoch = 4,
      frameSequence = 12,
      reason = "ticket_action_latest_not_detected",
      terminal = true,
      ok = false
    )
    assertTrue(ticketVisualLatestNotDetectedTerminalHasBoundProof(valid))
    listOf(
      valid.copy(ok = true),
      valid.copy(terminal = false),
      valid.copy(status = "needs_attention"),
      valid.copy(phase = "needs_attention"),
      valid.copy(target = TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED.wireName),
      valid.copy(currentView = TicketVisualActionView.LATEST_UNACTIVATED),
      valid.copy(reason = "ticket_action_visual_unproved"),
      valid.copy(streamEpoch = 0),
      valid.copy(frameSequence = 0),
      valid.copy(sliderRegion = TicketSliderRegionV3(
        proofActionId = "redetect-1",
        streamEpoch = 4,
        frameSequence = 12,
        leftBasisPoints = 100,
        topBasisPoints = 100,
        rightBasisPoints = 200,
        bottomBasisPoints = 200
      ))
    ).forEach { adjacent ->
      assertFalse(adjacent.toString(), ticketVisualLatestNotDetectedTerminalHasBoundProof(adjacent))
    }
  }

  @Test
  fun retainedLatestNotDetectedNeverBackfillsOrReplaysMissingProof() {
    val request = parse(
      """{"version":3,"actionId":"redetect-1","target":"redetect_latest","source":"test","reason":"test","attemptId":""}"""
    )!!
    val valid = TicketVisualActionJournalState(
      actionId = "redetect-1",
      target = TicketVisualActionTarget.REDETECT_LATEST.wireName,
      phase = "terminal",
      streamEpoch = 4,
      frameSequence = 12,
      terminalStatus = "failed",
      terminalReason = "ticket_action_latest_not_detected",
      terminalView = TicketVisualActionView.UNKNOWN.wireName,
      completedAt = "2026-09-02T00:00:00Z",
      terminalOk = false
    )
    assertTrue(ticketVisualLatestNotDetectedJournalHasBoundProof(valid))
    val replay = retainedTicketVisualTerminalSnapshot(valid, request, 9, 99)!!
    assertFalse(replay.ok)
    assertEquals("failed", replay.status)
    assertEquals("failed", replay.phase)
    assertEquals("ticket_action_latest_not_detected", replay.reason)
    assertEquals(4L, replay.streamEpoch)
    assertEquals(12L, replay.frameSequence)
    assertFalse(replay.switchAvailable)

    val invalid = listOf(
      valid.copy(streamEpoch = 0),
      valid.copy(frameSequence = 0),
      valid.copy(terminalStatus = "needs_attention"),
      valid.copy(terminalView = TicketVisualActionView.LATEST_UNACTIVATED.wireName),
      valid.copy(terminalOk = true),
      valid.copy(sliderLeftBasisPoints = 100)
    )
    invalid.forEach { journal ->
      assertFalse(journal.toString(), ticketVisualLatestNotDetectedJournalHasBoundProof(journal))
      val rejected = retainedTicketVisualTerminalSnapshot(journal, request, 9, 99)!!
      assertFalse(rejected.ok)
      assertEquals("needs_attention", rejected.status)
      assertEquals("needs_attention", rejected.phase)
      assertEquals("ticket_action_frame_watermark_unproved", rejected.reason)
      assertEquals(0L, rejected.streamEpoch)
      assertEquals(0L, rejected.frameSequence)
      assertFalse(rejected.switchAvailable)
    }
  }

  @Test
  fun captureRecoveryBudgetIsOneShotAndRequiresStarvationOrGenerationChange() {
    val budget = TicketVisualCaptureRecoveryBudget()
    assertFalse(budget.consumeIfCaptureWasInterrupted(5, 5, 2, 2, completedProbeSeen = true))
    assertFalse(budget.consumed)
    assertTrue(budget.consumeIfCaptureWasInterrupted(5, 6, 2, 2, completedProbeSeen = true))
    assertTrue(budget.consumed)
    assertFalse(budget.consumeIfCaptureWasInterrupted(6, 6, 2, 3, completedProbeSeen = true))

    val starved = TicketVisualCaptureRecoveryBudget()
    assertTrue(starved.consumeIfCaptureWasInterrupted(5, 5, 2, 2, completedProbeSeen = false))
  }

  @Test
  fun terminalAckLossRestartReturnsRetainedOutcomeWithoutRedispatchState() {
    val request = parse(
      """{"version":3,"actionId":"a","target":"open_latest_and_register","source":"test","reason":"test","attemptId":"a","policyRevision":"policy-1","switchExpiresAt":"2026-08-25T02:00:00Z"}"""
    )!!
    val retained = TicketVisualActionJournalState(
      actionId = "a",
      target = "open_latest_and_register",
      phase = "terminal",
      intendedAnchor = "opaque-anchor",
      streamEpoch = 9,
      frameSequence = 12,
      terminalStatus = "succeeded",
      terminalReason = "ticket_action_registered",
      terminalView = "activated_current",
      interactionRevision = "revision",
      activationRevision = "activation",
      activationAttemptId = "a",
      completedAt = "2026-08-24T00:00:00Z",
      terminalOk = true
    )
    val replay = retainedTicketVisualTerminalSnapshot(retained, request, 9, 12)!!
    assertTrue(replay.terminal)
    assertTrue(replay.ok)
    assertEquals("activation", replay.activationRevision)
    assertTrue(replay.switchAvailable)
    assertEquals("2026-08-25T02:00:00Z", replay.switchExpiresAt)
    assertFalse(retained.navigationDispatchUncertain)
  }

  @Test
  fun terminalJournalRebuildsTheExactIdempotentFinalizerEnvelope() {
    val journal = TicketVisualActionJournalState(
      commandId = "ticket:pixel:action-a",
      commandRevision = "command-revision",
      actionId = "action-a",
      target = TicketVisualActionTarget.REGISTER_CURRENT.wireName,
      phase = "terminal",
      streamEpoch = 9,
      frameSequence = 12,
      terminalStatus = "needs_attention",
      terminalPhase = "outcome_unknown",
      terminalReason = "ticket_action_gesture_completion_uncertain",
      terminalView = TicketVisualActionView.UNKNOWN.wireName,
      activationRevision = "must-not-replay-on-failure",
      activationAttemptId = "action-a",
      completedAt = "2026-08-24T00:00:00Z",
      terminalOk = false
    )

    val envelope = requireNotNull(ticketActionFinalizationEnvelope(journal))
    assertEquals("ticket:pixel:action-a", envelope.commandId)
    assertEquals("command-revision", envelope.commandRevision)
    assertEquals("outcome_unknown", envelope.action.phase)
    assertEquals("action-a", envelope.action.activationAttemptId)
    assertEquals("", envelope.action.activationRevision)
    assertFalse(envelope.action.ok)
  }

  @Test
  fun terminalWithoutAStableCompletionTimeCannotBypassRestaging() {
    val journal = TicketVisualActionJournalState(
      commandId = "ticket:pixel:action-a",
      commandRevision = "command-revision",
      actionId = "action-a",
      target = TicketVisualActionTarget.REGISTER_CURRENT.wireName,
      phase = "terminal",
      terminalStatus = "needs_attention",
      terminalPhase = "not_dispatched",
      terminalReason = "ticket_action_visual_unproved",
      terminalView = TicketVisualActionView.UNKNOWN.wireName,
      activationAttemptId = "action-a",
      completedAt = ""
    )

    assertEquals(null, ticketActionFinalizationEnvelope(journal))
  }

  @Test
  fun activationExpiryRefreshCorrelationSurvivesTerminalNetworkLoss() {
    val journal = TicketVisualActionJournalState(
      commandId = "ticket:pixel:refresh-a",
      commandRevision = "schedule:refresh-a",
      flow = "activation_expiry_reset",
      refreshActivationAttemptId = "activation-a",
      refreshActivationRevision = "activation-revision-a",
      actionId = "refresh-a",
      target = TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED.wireName,
      phase = "terminal",
      streamEpoch = 9,
      frameSequence = 12,
      terminalStatus = "succeeded",
      terminalPhase = "complete",
      terminalReason = "ticket_action_target_visible",
      terminalView = TicketVisualActionView.LATEST_UNACTIVATED.wireName,
      completedAt = "2026-08-24T00:00:00Z",
      terminalOk = true
    )

    val envelope = requireNotNull(ticketActionFinalizationEnvelope(journal))
    assertEquals("activation_expiry_reset", envelope.flow)
    assertEquals("activation-a", envelope.refreshActivationAttemptId)
    assertEquals("activation-revision-a", envelope.refreshActivationRevision)
    assertEquals("schedule:refresh-a", envelope.commandRevision)
  }

  @Test
  fun retainedNavigationSuccessRequiresTheTargetSpecificDetailView() {
    val cases = listOf(
      Triple(
        TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
        TicketVisualActionView.LATEST_UNACTIVATED,
        TicketVisualActionView.RECENT_ACTIVATED
      ),
      Triple(
        TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
        TicketVisualActionView.ACTIVATED_CURRENT,
        TicketVisualActionView.LATEST_UNACTIVATED
      ),
      Triple(
        TicketVisualActionTarget.REGISTER_CURRENT,
        TicketVisualActionView.ACTIVATED_CURRENT,
        TicketVisualActionView.LATEST_UNACTIVATED
      ),
      Triple(
        TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
        TicketVisualActionView.RECENT_ACTIVATED,
        TicketVisualActionView.LATEST_UNACTIVATED
      ),
      Triple(
        TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED,
        TicketVisualActionView.LATEST_UNACTIVATED,
        TicketVisualActionView.RECENT_ACTIVATED
      ),
      Triple(
        TicketVisualActionTarget.REDETECT_LATEST,
        TicketVisualActionView.LATEST_UNACTIVATED,
        TicketVisualActionView.UNKNOWN
      )
    )

    cases.forEach { (target, validView, invalidView) ->
      val actionId = "retained-${target.wireName}"
      val switchAuthority = if (target in setOf(
          TicketVisualActionTarget.SHOW_RECENT_ACTIVATED,
          TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED
        )) {
        ",\"policyRevision\":\"policy-1\",\"switchExpiresAt\":\"2026-08-25T02:00:00Z\""
      } else {
        ""
      }
      val expectedRevision = if (target == TicketVisualActionTarget.REGISTER_CURRENT) {
        ",\"expectedInteractionRevision\":\"revision-1\""
      } else {
        ""
      }
      val attemptId = if (target.activatesTicket) actionId else ""
      val request = parse(
        """{"version":3,"actionId":"$actionId","target":"${target.wireName}","source":"test","reason":"test","attemptId":"$attemptId"$expectedRevision$switchAuthority}"""
      )!!
      val retained = TicketVisualActionJournalState(
        actionId = actionId,
        target = target.wireName,
        phase = "terminal",
        streamEpoch = 9,
        frameSequence = 12,
        terminalStatus = "succeeded",
        terminalReason = "retained_success",
        terminalView = validView.wireName,
        terminalOk = true,
        sliderLeftBasisPoints = 900,
        sliderTopBasisPoints = 5_000,
        sliderRightBasisPoints = 9_100,
        sliderBottomBasisPoints = 6_100
      )

      assertTrue(retainedTicketVisualTerminalSnapshot(retained, request, 9, 12)!!.ok)

      val incompatible = retainedTicketVisualTerminalSnapshot(
        retained.copy(terminalView = invalidView.wireName),
        request,
        9,
        12
      )!!
      assertTrue(incompatible.terminal)
      assertFalse(incompatible.ok)
      assertEquals("needs_attention", incompatible.status)
      assertEquals("needs_attention", incompatible.phase)
      assertEquals("ticket_action_terminal_view_unproved", incompatible.reason)
      assertFalse(incompatible.switchAvailable)
      assertEquals("", incompatible.switchExpiresAt)
      assertNull(incompatible.sliderRegion)
    }
  }

  @Test
  fun retainedFailureAndPassiveCurrentProofKeepTheirExistingViewSemantics() {
    val switchRequest = parse(
      """{"version":3,"actionId":"failed-switch","target":"show_recent_activated","source":"test","reason":"test","attemptId":"","policyRevision":"policy-1","switchExpiresAt":"2026-08-25T02:00:00Z"}"""
    )!!
    val retainedFailure = TicketVisualActionJournalState(
      actionId = "failed-switch",
      target = "show_recent_activated",
      phase = "terminal",
      terminalStatus = "failed",
      terminalReason = "original_failure",
      terminalView = "unknown",
      terminalOk = false
    )

    val failureReplay = retainedTicketVisualTerminalSnapshot(
      retainedFailure,
      switchRequest,
      9,
      12
    )!!
    assertTrue(failureReplay.terminal)
    assertFalse(failureReplay.ok)
    assertEquals("failed", failureReplay.status)
    assertEquals("failed", failureReplay.phase)
    assertEquals("original_failure", failureReplay.reason)

    val proofRequest = parse(
      """{"version":3,"actionId":"proof-list","target":"prove_current","source":"test","reason":"test","attemptId":""}"""
    )!!
    val passiveListProof = retainedFailure.copy(
      actionId = "proof-list",
      target = "prove_current",
      terminalStatus = "succeeded",
      terminalReason = "ticket_action_current_list",
      terminalOk = true,
      streamEpoch = 9,
      frameSequence = 12
    )

    val proofReplay = retainedTicketVisualTerminalSnapshot(
      passiveListProof,
      proofRequest,
      9,
      12
    )!!
    assertTrue(proofReplay.ok)
    assertEquals(TicketVisualActionView.UNKNOWN, proofReplay.currentView)
    assertEquals("ticket_action_current_list", proofReplay.reason)
  }

  @Test
  fun restartBetweenTerminalAndGeometryReducerRetainsSafeSliderRegionWithoutRedispatch() {
    val request = parse(
      """{"version":3,"actionId":"proof-a","target":"prove_current","source":"browser_auto_proof","reason":"stream_epoch","attemptId":""}"""
    )!!
    val retained = TicketVisualActionJournalState(
      actionId = "proof-a",
      target = "prove_current",
      phase = "terminal",
      streamEpoch = 9,
      frameSequence = 12,
      terminalStatus = "succeeded",
      terminalReason = "ticket_action_current_unactivated_proved",
      terminalView = "latest_unactivated",
      completedAt = "2026-08-24T00:00:00Z",
      terminalOk = true,
      sliderLeftBasisPoints = 900,
      sliderTopBasisPoints = 5_000,
      sliderRightBasisPoints = 9_100,
      sliderBottomBasisPoints = 6_100
    )

    val replay = retainedTicketVisualTerminalSnapshot(retained, request, 10, 20)!!

    assertTrue(replay.ok)
    assertEquals("proof-a", replay.sliderRegion?.proofActionId)
    assertEquals(9L, replay.sliderRegion?.streamEpoch)
    assertEquals(12L, replay.sliderRegion?.frameSequence)
    assertEquals(900, replay.sliderRegion?.leftBasisPoints)
    assertEquals(6_100, replay.sliderRegion?.bottomBasisPoints)
    assertFalse(retained.navigationDispatchUncertain)
  }

  @Test
  fun retainedSliderRegionFailsClosedForWrongViewTargetOrBounds() {
    val request = parse(
      """{"version":3,"actionId":"proof-a","target":"prove_current","source":"browser_auto_proof","reason":"stream_epoch","attemptId":""}"""
    )!!
    val valid = TicketVisualActionJournalState(
      actionId = "proof-a",
      target = "prove_current",
      phase = "terminal",
      streamEpoch = 9,
      frameSequence = 12,
      terminalStatus = "succeeded",
      terminalReason = "ticket_action_current_unactivated_proved",
      terminalView = "latest_unactivated",
      terminalOk = true,
      sliderLeftBasisPoints = 900,
      sliderTopBasisPoints = 5_000,
      sliderRightBasisPoints = 9_100,
      sliderBottomBasisPoints = 6_100
    )

    assertNull(retainedTicketVisualTerminalSnapshot(
      valid.copy(sliderRightBasisPoints = 900),
      request,
      9,
      12
    )?.sliderRegion)
    assertNull(retainedTicketVisualTerminalSnapshot(
      valid.copy(terminalView = "recent_activated"),
      request,
      9,
      12
    )?.sliderRegion)
  }

  @Test
  fun retainedSuccessWithoutAnEncodedFrameWatermarkFailsClosed() {
    val request = parse(
      """{"version":3,"actionId":"a","target":"open_latest_unactivated","source":"test","reason":"test","attemptId":"","policyRevision":"policy-1","switchExpiresAt":"2026-08-25T02:00:00Z"}"""
    )!!
    val retained = TicketVisualActionJournalState(
      actionId = "a",
      target = "open_latest_unactivated",
      phase = "terminal",
      terminalStatus = "succeeded",
      terminalReason = "ticket_action_target_visible",
      terminalOk = true
    )

    val replay = retainedTicketVisualTerminalSnapshot(retained, request, 14, 0)!!

    assertFalse(replay.ok)
    assertEquals("needs_attention", replay.status)
    assertEquals("ticket_action_frame_watermark_unproved", replay.reason)
    assertEquals(0, replay.frameSequence)
    assertFalse(replay.switchAvailable)
    assertEquals("", replay.switchExpiresAt)
  }

  @Test
  fun provenCheckpointDoesNotReplaceActivatedAnchorFromDifferentCurrentTicket() {
    val original = TicketVisualSwitchAnchors(
      recentActivatedAnchor = "activated-original"
    )
    val differentActivated = TicketVisualActionObservation(
      probeId = 4,
      state = TicketVisualPhoneState.ACTIVATED_DETAIL,
      currentAnchor = "activated-different"
    )
    assertFalse(ticketVisualCheckpointMatchesActivatedAnchor(differentActivated, original))
    assertEquals("activated-original", original.recentActivatedAnchor)
    assertTrue(ticketVisualCheckpointMatchesActivatedAnchor(
      differentActivated.copy(currentAnchor = "activated-original"),
      original
    ))
  }

  @Test
  fun navigationJournalCommitFailureCannotProveMutationAdmission() {
    val intended = TicketVisualActionJournalState(
      actionId = "action",
      target = "open_latest_unactivated",
      phase = "navigation_dispatched"
    )
    var readAttempted = false

    assertFalse(ticketVisualActionJournalWriteProved(
      value = intended,
      commit = { false },
      readBack = {
        readAttempted = true
        intended
      }
    ))
    assertFalse(readAttempted)
  }

  @Test
  fun navigationJournalReadbackMismatchFailsEvenAfterSuccessfulCommit() {
    val intended = TicketVisualActionJournalState(
      actionId = "action",
      target = "open_latest_unactivated",
      phase = "navigation_dispatched"
    )

    assertFalse(ticketVisualActionJournalWriteProved(
      value = intended,
      commit = { true },
      readBack = { intended.copy(phase = "") }
    ))
    assertTrue(ticketVisualActionJournalWriteProved(
      value = intended,
      commit = { true },
      readBack = { intended }
    ))
  }

  @Test
  fun exactRevisionGeometryRefreshPreservesPhoneLocalTicketIdentityOnlyForThatRevision() {
    val prior = TicketRegistrationProof(
      status = "unactivated_ready",
      reason = "visual",
      interactionRevision = "proof-7",
      streamEpoch = 7,
      frameSequence = 11,
      phoneDisplayWidth = 1080,
      phoneDisplayHeight = 2424,
      ticketAnchor = "card-anchor",
      detailAnchor = "d_detail-anchor"
    )
    val refreshed = prior.copy(
      reason = "geometry_refresh",
      streamEpoch = 8,
      frameSequence = 3,
      ticketAnchor = "",
      detailAnchor = ""
    )
    val retained = ticketRegistrationProofPreservingExactIdentity(prior, refreshed)
    assertEquals("card-anchor", retained.ticketAnchor)
    assertEquals("d_detail-anchor", retained.detailAnchor)
    assertEquals(8, retained.streamEpoch)

    val differentRevision = ticketRegistrationProofPreservingExactIdentity(
      prior,
      refreshed.copy(interactionRevision = "proof-8")
    )
    assertEquals("", differentRevision.ticketAnchor)
    assertEquals("", differentRevision.detailAnchor)
  }

  private fun parse(value: String) = parseTicketVisualActionRequest(json.parseToJsonElement(value).jsonObject)
}
