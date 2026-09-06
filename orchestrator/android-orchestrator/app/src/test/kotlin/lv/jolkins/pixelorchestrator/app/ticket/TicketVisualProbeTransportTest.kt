package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration

class TicketVisualProbeTransportTest {
  @Test
  fun parsesDedicatedActivatedDetailGeometryWithoutReplacingTheGenericCardBounds() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=ticket_list reason=ticket_action probe_id=80 " +
        "method=ticket_action_visual_probe " +
        "cards=opaque-card@8,40,184,120@10,95,182,112@154,72,181,92@1",
      nowMillis = 4_100L
    )

    val card = engine.recentControlCodeVisualProbeAfter(80L, 4_000L)
      ?.ticketActionObservation?.cards?.single()
    assertEquals(TicketVisualProbeBounds(8, 40, 184, 120), card?.bounds)
    assertEquals(TicketVisualProbeBounds(10, 95, 182, 112), card?.registrationBounds)
    assertEquals(TicketVisualProbeBounds(154, 72, 181, 92), card?.activatedDetailBounds)
    assertEquals(
      card?.activatedDetailBounds,
      card?.navigationBoundsFor(TicketVisualActionTarget.SHOW_RECENT_ACTIVATED)
    )
  }

  @Test
  fun parsesTheExactUnactivatedDetailWireShapeUsedByTheHelper() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=unactivated_detail reason=ticket_action probe_id=81 " +
        "method=ticket_action_visual_probe capture_start_us=4100123 anchor=00112233445566778899aabb " +
        "slider=14,231,178,250 control=135,206,180,226 back=166,4,187,24",
      nowMillis = 4_200L
    )

    val probe = engine.recentControlCodeVisualProbeAfter(81L, 4_000L)
    val observation = probe?.ticketActionObservation
    assertEquals(TicketVisualPhoneState.UNACTIVATED_DETAIL, observation?.state)
    assertEquals(4_100_123L, observation?.captureStartUs)
    val requestedObservation = requireNotNull(observation).copy(atMillis = 4_000L)
    assertTrue(ticketVisualFrameMatchesRegistrationObservation(
      requestedObservation, 7L, 7L, 4_100_123L, 4_800L, 3_000L
    ))
    assertFalse(ticketVisualFrameMatchesRegistrationObservation(
      requestedObservation, 7L, 7L, 4_099_999L, 4_800L, 3_000L
    ))
    assertEquals("00112233445566778899aabb", observation?.currentAnchor)
    assertEquals(TicketVisualProbeBounds(14, 231, 178, 250), observation?.sliderBounds)
    assertEquals(TicketVisualProbeBounds(135, 206, 180, 226), observation?.controlCodeBounds)
    assertEquals(TicketVisualProbeBounds(166, 4, 187, 24), observation?.backBounds)
    assertFalse(engine.snapshot(nowMillis = 4_300L).stderrTail.contains("00112233445566778899aabb"))
  }

  @Test
  fun missingMalformedOrNonpositiveCaptureTimeCannotBindARegistrationWatermark() = withEngine { engine ->
    listOf("", " capture_start_us=invalid", " capture_start_us=0", " capture_start_us=-1").forEachIndexed { index, field ->
      val id = 200L + index
      engine.ingestStderrLine(
        "CONTROL_CODE_VISUAL result=unactivated_detail reason=ticket_action probe_id=$id " +
          "method=ticket_action_visual_probe$field slider=14,231,178,250",
        nowMillis = 4_200L
      )
      val observation = requireNotNull(engine.recentControlCodeVisualProbeAfter(id, 4_000L)?.ticketActionObservation)
        .copy(atMillis = 4_000L)
      assertEquals(0L, observation.captureStartUs)
      assertFalse(ticketVisualFrameMatchesRegistrationObservation(
        observation, 7L, 7L, 4_100_123L, 4_800L, 3_000L
      ))
    }
  }

  @Test
  fun parsesOnlyTheSanitizedTimeTabGeometryForTheEmptySingleUseShell() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=tickets_single_use_empty reason=ticket_action probe_id=85 " +
        "method=ticket_action_visual_probe time=114,22,164,33",
      nowMillis = 4_400L
    )

    val observation = engine.recentControlCodeVisualProbeAfter(85L, 4_300L)
      ?.ticketActionObservation
    assertEquals(TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY, observation?.state)
    assertEquals(TicketVisualProbeBounds(114, 22, 164, 33), observation?.timeTicketsTabBounds)
    assertNull(observation?.ticketsTabBounds)
    assertNull(observation?.sliderBounds)
    assertTrue(observation?.cards?.isEmpty() == true)
  }

  @Test
  fun parsesTheDistinctEmptyTimeTicketsStateWithOnlyTheSingleUseTarget() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=tickets_time_empty reason=ticket_action probe_id=86 " +
        "method=ticket_action_visual_probe tickets=24,22,84,33",
      nowMillis = 4_500L
    )

    val observation = engine.recentControlCodeVisualProbeAfter(86L, 4_400L)
      ?.ticketActionObservation
    assertEquals(TicketVisualPhoneState.TICKETS_TIME_EMPTY, observation?.state)
    assertNull(observation?.timeTicketsTabBounds)
    assertEquals(TicketVisualProbeBounds(24, 22, 84, 33), observation?.ticketsTabBounds)
    assertNull(observation?.sliderBounds)
    assertTrue(observation?.cards?.isEmpty() == true)
  }

  @Test
  fun rejectsEmptyTabStatesWithoutTheirExactOppositeTabTarget() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=tickets_time_empty reason=ticket_action probe_id=87 " +
        "method=ticket_action_visual_probe",
      nowMillis = 4_600L
    )
    assertNull(engine.recentControlCodeVisualProbeAfter(87L, 4_500L)?.ticketActionObservation)

    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=tickets_time_empty reason=ticket_action probe_id=88 " +
        "method=ticket_action_visual_probe tickets=24,22,84,33 time=114,22,164,33",
      nowMillis = 4_700L
    )
    assertNull(engine.recentControlCodeVisualProbeAfter(88L, 4_600L)?.ticketActionObservation)

    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=tickets_single_use_empty reason=ticket_action probe_id=89 " +
        "method=ticket_action_visual_probe tickets=24,22,84,33 time=114,22,164,33",
      nowMillis = 4_800L
    )
    assertNull(engine.recentControlCodeVisualProbeAfter(89L, 4_700L)?.ticketActionObservation)
  }

  @Test
  fun rejectsWrongProbeIdAndPreRequestObservation() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=unactivated_detail reason=ticket_action probe_id=82 " +
        "method=ticket_action_visual_probe slider=14,231,178,250 back=166,4,187,24",
      nowMillis = 5_000L
    )

    assertNull(engine.recentControlCodeVisualProbeAfter(83L, 4_900L))
    assertNull(engine.recentControlCodeVisualProbeAfter(82L, 5_001L))
    assertTrue(engine.recentControlCodeVisualProbeAfter(82L, 5_000L)?.ticketActionObservation != null)
  }

  @Test
  fun rejectsTheWholeObservationWhenAnyAdvertisedCardIsMalformed() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=ticket_list reason=ticket_action probe_id=84 " +
        "method=ticket_action_visual_probe " +
        "cards=00112233445566778899aabb@8,40,184,120@10,95,182,112@1;truncated",
      nowMillis = 5_200L
    )

    assertNull(engine.recentControlCodeVisualProbeAfter(84L, 5_000L)?.ticketActionObservation)
  }

  @Test
  fun lateOlderResultCannotReplaceAnAlreadyReceivedExpectedProbe() = withEngine { engine ->
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=unactivated_detail reason=ticket_action probe_id=92 " +
        "method=ticket_action_visual_probe slider=14,231,178,250 back=166,4,187,24",
      nowMillis = 6_200L
    )
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=unknown reason=ticket_action probe_id=91 " +
        "method=ticket_action_visual_probe",
      nowMillis = 6_250L
    )

    val expected = engine.recentControlCodeVisualProbeAfter(92L, 6_000L)
    assertEquals(TicketVisualPhoneState.UNACTIVATED_DETAIL, expected?.ticketActionObservation?.state)
    assertEquals(6_200L, expected?.atMillis)
  }

  @Test
  fun ignoresVisualResultsFromAnOldCaptureGeneration() = withEngine { engine ->
    val currentGeneration = engine.advanceCaptureGeneration()
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=unactivated_detail reason=ticket_action probe_id=101 " +
        "method=ticket_action_visual_probe slider=14,231,178,250 back=166,4,187,24",
      nowMillis = 7_200L,
      sourceGeneration = currentGeneration
    )
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=unknown reason=ticket_action probe_id=100 " +
        "method=ticket_action_visual_probe",
      nowMillis = 7_250L,
      sourceGeneration = currentGeneration - 1L
    )

    assertNull(engine.recentControlCodeVisualProbeAfter(100L, 7_000L))
    assertEquals(
      TicketVisualPhoneState.UNACTIVATED_DETAIL,
      engine.recentControlCodeVisualProbeAfter(101L, 7_000L)?.ticketActionObservation?.state
    )
  }

  @Test
  fun parsesOnlyExactOpaqueSignatureFieldsAndNeverRetainsThemInHealth() = withEngine { engine ->
    val signature = "0123456789abcdef01234567"
    val epoch = "abcdef012345"
    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=raw_ticket reason=signature probe_id=110 " +
        "method=h264_bitmap_probe visual_signature=$signature visual_signature_epoch=$epoch",
      nowMillis = 8_000L
    )
    val valid = engine.recentControlCodeVisualProbeAfter(110L, 7_900L)
    assertEquals(signature, valid?.visualSignature)
    assertEquals(epoch, valid?.visualSignatureEpoch)
    assertFalse(engine.snapshot(nowMillis = 8_100L).stderrTail.contains(signature))

    engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=raw_ticket reason=signature probe_id=111 " +
        "method=h264_bitmap_probe visual_signature=short visual_signature_epoch=$epoch",
      nowMillis = 8_200L
    )
    val malformed = engine.recentControlCodeVisualProbeAfter(111L, 8_100L)
    assertEquals("", malformed?.visualSignature)
    assertEquals("", malformed?.visualSignatureEpoch)
  }

  @Test
  fun transientSignatureProbesArePurgedByAgeGenerationAndExplicitClear() = withEngine { engine ->
    val signature = "0123456789abcdef01234567"
    val epoch = "abcdef012345"
    fun ingest(id: Long, at: Long) = engine.ingestStderrLine(
      "CONTROL_CODE_VISUAL result=raw_ticket reason=signature probe_id=$id " +
        "method=h264_bitmap_probe visual_signature=$signature visual_signature_epoch=$epoch",
      nowMillis = at
    )

    ingest(120L, 9_000L)
    ingest(121L, 20_001L)
    assertNull(engine.recentControlCodeVisualProbeAfter(120L, 0L))
    assertTrue(engine.recentControlCodeVisualProbeAfter(121L, 20_000L) != null)

    engine.advanceCaptureGeneration()
    assertNull(engine.recentControlCodeVisualProbeAfter(121L, 0L))
    ingest(122L, 21_000L)
    engine.clearTransientControlCodeVisualProbes()
    assertNull(engine.recentControlCodeVisualProbeAfter(122L, 0L))
  }

  private fun withEngine(block: (TicketRootHardwareH264CaptureEngine) -> Unit) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
      block(TicketRootHardwareH264CaptureEngine(
        scope = scope,
        rootExecutor = UnusedRootExecutor,
        onFrame = {},
        onStateChanged = {}
      ))
    } finally {
      scope.cancel()
    }
  }

  private object UnusedRootExecutor : RootExecutor {
    override suspend fun isRootAvailable(): Boolean = false

    override suspend fun run(command: String, timeout: Duration): RootResult = unused(command)

    override suspend fun runScript(script: String, timeout: Duration): RootResult = unused(script)

    private fun unused(command: String) = RootResult(
      exitCode = 1,
      stdout = "",
      stderr = "unused",
      command = command,
      durationMs = 0L
    )
  }
}
