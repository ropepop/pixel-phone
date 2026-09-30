package lv.jolkins.pixelorchestrator.app.ticket

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Installed Android JNI contracts, with synthetic state and no socket, capture or phone input. */
@RunWith(AndroidJUnit4::class)
class TicketNativePolicyInstrumentedTest {
  @Test fun monitoringAndCommandAuthorityRemainFencedAcrossNativeTransitions() {
    val schedule = TicketMonitoringSchedule()
    assertTrue(schedule.configure(TicketMonitoringConfig(true,"synthetic"),1_000L))
    val problem = ticketMonitoringObservation(null,true,1_000L)
    assertTrue(schedule.shouldReport(problem,1_000L)); schedule.reported(problem,1_000L)
    assertFalse(schedule.checkDue(15_999L)); assertTrue(schedule.checkDue(16_000L))
    schedule.checked(16_000L)
    val stale = ticketMonitoringObservation(TicketVisualPhoneState.UNACTIVATED_DETAIL,false,15_999L)
    assertFalse(schedule.shouldReport(stale,16_000L))
    val ready = ticketMonitoringObservation(TicketVisualPhoneState.UNACTIVATED_DETAIL,false,16_001L)
    assertTrue(schedule.shouldReport(ready,16_001L)); schedule.reported(ready,16_001L)
    assertFalse(schedule.checkDue(31_000L)); assertTrue(schedule.checkDue(316_001L))
    val command = TicketSpacetimeCommand("synthetic","ticket","pixel","ticket_action_v3","pending","rev","reason","{}","date","date","2099-01-01T00:00:00Z")
    val inbox = TicketCommandInbox()
    assertNull(inbox.snapshot())
    inbox.apply(TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.APPLIED,listOf(command)))
    assertTrue(inbox.contains(command)); assertFalse(inbox.contains(command.copy(revision="changed")))
    assertEquals(listOf(command),inbox.snapshot()); inbox.disconnected(); assertNull(inbox.snapshot())
    assertTrue(ticketSpacetimeCommandExpired("2026-01-01T00:00:00Z",Instant.parse("2026-01-01T00:00:00Z")))
    assertNull(ticketCommandAcknowledgement("ticket_action_v3",TicketSpacetimeCommandResult(true,"done","streaming",ticketActionV3=TicketVisualActionSnapshot())))
    assertEquals(TicketCommandAcknowledgement("failed","pixel_direct_failed"),ticketCommandAcknowledgement("start",TicketSpacetimeCommandResult(false,"","idle")))
  }
  @Test fun captureDemandAndFreshControlEvidenceRemainNativeAndFenced() {
    val cadence = TicketCaptureCadenceScheduler(1000L)
    cadence.parkOrdinaryCapture()
    assertEquals(-1L, cadence.waitMillis(1000L,false))
    assertTrue(cadence.enableDemandGateAndLatchOrdinaryCapture(3500L,1000L))
    cadence.beginCapture(1000L,false)
    assertEquals(-1L,cadence.waitMillis(1001L,false))
    assertEquals(999L,cadence.waitMillis(1001L,true))
    val session = TicketCaptureDemandSession()
    val request = TicketCaptureDemandRequest(7L,1L,2500L)
    var delivered = 0
    assertEquals(TicketCaptureDemandAdmissionResult.ACCEPTED,session.admit(request,7L,1000L,1000L){assertEquals(3500L,it);delivered++;true})
    assertEquals(TicketCaptureDemandAdmissionResult.NON_MONOTONIC_GENERATION,session.admit(request,7L,1000L,1000L){delivered++;true})
    assertEquals(1,delivered)
    assertEquals(TicketProofStreamCleanupDecision.DROP,ticketProofStreamCleanupDecision(1L,2L,true,0,false,false,false,false))
    val control = TicketPhoneControlState("pc-synthetic")
    val fence = TicketRegistrationEvidenceFence(1L,7L,3L,17,0L,0L)
    for (capture in listOf(1000L,2000L)) {
      control.observe(TicketVisualActionObservation(0,TicketVisualPhoneState.UNACTIVATED_DETAIL,"synthetic-private",
        TicketVisualProbeBounds(20,168,180,196),atMillis=capture+20,captureStartUs=capture*1000,captureGeneration=7),false,fence)
    }
    val revision = control.updates.value.contextRevision
    assertNotNull(control.registrationEvidence(revision,fence,2000L))
    assertNull(control.registrationEvidence(revision,fence,4000L))
    control.invalidate("physical_touch",true)
    assertNull(control.exactContext(revision,2001L))
  }

  @Test fun nativeVisualActionAdmissionAndRetainedOutcomeNeverGrantAnotherAttempt() {
    val request = TicketVisualActionRequest("synthetic",TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,"test","","","","","","")
    val journal = TicketVisualActionJournalState(actionId="synthetic",target="open_latest_unactivated",phase="terminal",
      terminalStatus="succeeded",terminalView="latest_unactivated",terminalReason="done",terminalOk=true,semanticProof=true)
    val retained = retainedTicketVisualTerminalSnapshot(journal,request,0L,0L)!!
    assertTrue(retained.terminal);assertTrue(retained.ok)
    assertEquals("complete",retained.phase)
    assertFalse(retainedTicketVisualTerminalSnapshot(journal.copy(terminalView="unknown"),request,1L,1L)!!.ok)
    val observation = TicketVisualActionObservation(1L,TicketVisualPhoneState.UNACTIVATED_DETAIL,"synthetic",atMillis=1000L,captureStartUs=1000000L)
    val consensus = TicketVisualObservationConsensus()
    assertNull(consensus.offer(observation))
    assertNull(consensus.offer(observation))
    assertEquals(observation.copy(probeId=2L),consensus.offer(observation.copy(probeId=2L)))
    assertFalse(ticketVisualObservationIsFreshForDispatch(observation,4001L,3000L))
  }

}
