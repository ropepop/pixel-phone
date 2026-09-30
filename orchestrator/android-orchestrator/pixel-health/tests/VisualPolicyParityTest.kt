package lv.jolkins.pixelorchestrator.app.ticket
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class VisualPolicyParityTest {
 private val json=Json {encodeDefaults=true}
 private inline fun <reified A,reified B> convert(a:A):B=json.decodeFromJsonElement(json.encodeToJsonElement(a))
 private inline fun <reified A,reified B> compare(a:A,b:B) {assertEquals(json.encodeToJsonElement(a),json.encodeToJsonElement(b))}
 private fun request(t:TicketVisualActionTarget)=TicketVisualActionRequest("action",t,"browser","reason","action","pc-context","","policy","2099-01-01T00:00:00Z")
 @Test fun admittedRequestsMatchPreviousParserIncludingMalformedFieldsAndBoundaries() {
  var comparisons=0
  val basic=json.parseToJsonElement("""{"version":3,"actionId":"action","target":"open_latest_unactivated","attemptId":"action","expectedInteractionRevision":"pc-context","policyRevision":"policy","switchExpiresAt":"2099-01-01T00:00:00Z"}""").jsonObject
  val literals=listOf("null","true","3","3e0","+3","3.0","\\\"3\\\"","{}","[]","\\\" \\\"","\\\"schedule:pc-context\\\"","\\\"open_latest_unactivated\\\"","\\\"REGISTER_CURRENT\\\"","\\\"idle_ticket_refresh\\\"","\\\"activation_expiry_reset\\\"","\\\"2026-09-29T24:00:00Z\\\"","\\\"invalid\\\"").map {it.replace("\\\"","\"")}
  for(key in listOf("version","actionId","target","source","reason","attemptId","expectedInteractionRevision","scheduleId","policyRevision","switchExpiresAt","flow","activationAttemptId","activationRevision")) {
   for(literal in literals + listOf(json.encodeToJsonElement("a".repeat(129)).toString(),json.encodeToJsonElement("\u2007"+"a".repeat(64)+"\u2007").toString(),json.encodeToJsonElement("x".repeat(63)+"😀").toString())) {
    val p=JsonObject(basic.toMutableMap().apply {put(key,json.parseToJsonElement(literal))})
    val old=runCatching {LegacyparseTicketVisualActionRequest(p)};val next=runCatching {parseTicketVisualActionRequest(p)}
    assertEquals("$key=$literal",old.exceptionOrNull()?.javaClass,next.exceptionOrNull()?.javaClass)
    compare(old.getOrNull(),next.getOrNull());comparisons++
   }
  }
  for(t in TicketVisualActionTarget.entries) {
   compare(LegacyTicketVisualActionTarget.fromWireName(" \u2007"+t.wireName.uppercase()+"\u2007 "),TicketVisualActionTarget.fromWireName(" \u2007"+t.wireName.uppercase()+"\u2007 "));comparisons++
   val p=JsonObject(basic+mapOf("target" to JsonPrimitive(t.wireName)))
   compare(LegacyparseTicketVisualActionRequest(p),parseTicketVisualActionRequest(p));comparisons++
  }
  println("VISUAL_ACTION_REQUEST_PARITY_OK comparisons=$comparisons")
 }
 @Test fun journalsReconciliationAndTerminalProjectionsMatchFormerOwner() {
  var comparisons=0
  for(t in TicketVisualActionTarget.entries) for(v in TicketVisualActionView.entries) for(ok in listOf(false,true))
   for(semantic in listOf(false,true)) for(epoch in listOf(0L,1L)) for(reason in listOf("done","ticket_action_latest_not_detected")) {
    val r=request(t);val lr:LegacyTicketVisualActionRequest=convert(r)
    val j=TicketVisualActionJournalState(commandId="command",commandRevision="revision",actionId="action",target=t.wireName,phase="terminal",
      streamEpoch=epoch,frameSequence=epoch,terminalStatus=if(ok) "succeeded" else "failed",terminalView=v.wireName,terminalReason=reason,terminalOk=ok,
      completedAt="date",semanticProof=semantic,activationRevision="active",sliderLeftBasisPoints=if(semantic) -1 else 10,sliderTopBasisPoints=if(semantic) -1 else 20,
      sliderRightBasisPoints=if(semantic) -1 else 9000,sliderBottomBasisPoints=if(semantic) -1 else 8000)
    val lj:LegacyTicketVisualActionJournalState=convert(j)
    assertEquals(lj.hasRetainedTerminal,j.hasRetainedTerminal);assertEquals(lj.navigationDispatchUncertain,j.navigationDispatchUncertain)
    compare(LegacyretainedTicketVisualTerminalSnapshot(lj,lr,9,8),retainedTicketVisualTerminalSnapshot(j,r,9,8))
    compare(LegacyticketActionFinalizationEnvelope(lj),ticketActionFinalizationEnvelope(j))
    assertEquals(LegacyticketVisualLatestNotDetectedJournalHasBoundProof(lj),ticketVisualLatestNotDetectedJournalHasBoundProof(j));comparisons+=5
   }
  for(t in TicketVisualActionTarget.entries) for(from in TicketVisualPhoneState.entries) for(to in TicketVisualPhoneState.entries.map {it.wireName}+listOf(TICKET_ACTION_LIST_TO_SINGLE_USE,TICKET_ACTION_LIST_TO_TIME,""))
   for(current in TicketVisualPhoneState.entries) for(bounds in listOf(false,true)) {
    val j=TicketVisualActionJournalState(actionId="action",target=t.wireName,phase="navigation_dispatched",intendedAnchor="a",navigationFromState=from.wireName,navigationToState=to,navigationAnchor="a")
    val o=TicketVisualActionObservation(1,current,"a",ticketsTabBounds=if(bounds) TicketVisualProbeBounds(1,2,3,4) else null,timeTicketsTabBounds=if(bounds) null else TicketVisualProbeBounds(2,3,4,5))
    assertEquals(LegacyticketVisualJournalReconciled(convert(j),convert(request(t)),convert(o)),ticketVisualJournalReconciled(j,request(t),o));comparisons++
   }
  println("VISUAL_ACTION_JOURNAL_PARITY_OK comparisons=$comparisons")
 }
 @Test fun observationAgreementConsensusSelectionAndProofFencesMatchFormerOwner() {
  var comparisons=0;val random=Random(32)
  val old=LegacyTicketVisualObservationConsensus();val next=TicketVisualObservationConsensus()
  val points=listOf(Int.MIN_VALUE,-1,0,1,3,4,99,Int.MAX_VALUE)
  var prior=TicketVisualActionObservation(1,TicketVisualPhoneState.UNACTIVATED_DETAIL,"a",TicketVisualProbeBounds(1,2,3,4),atMillis=1000,captureStartUs=1000000)
  repeat(1200) {index->
   val state=TicketVisualPhoneState.entries[random.nextInt(TicketVisualPhoneState.entries.size)]
   val p=points[random.nextInt(points.size)]
   val o=if(index%3==0) prior.copy(probeId=index.toLong()) else TicketVisualActionObservation(index.toLong(),state,listOf("a",""," ","d_b")[random.nextInt(4)],
    sliderBounds=if(random.nextBoolean()) TicketVisualProbeBounds(p,p+1,p+10,p+20) else null,
    ticketsTabBounds=if(random.nextBoolean()) TicketVisualProbeBounds(1,2,3,4) else null,timeTicketsTabBounds=if(random.nextBoolean()) TicketVisualProbeBounds(1,2,3,4) else null,
    cards=listOf(TicketVisualCardAnchor("card",TicketVisualProbeBounds(1,2,3,4),registrationBounds=if(random.nextBoolean()) TicketVisualProbeBounds(2,3,4,5) else null,activatedDetailBounds=if(random.nextBoolean()) TicketVisualProbeBounds(3,4,5,6) else null,latest=random.nextBoolean())),
    atMillis=1000,captureStartUs=1000000,detailCardAnchor="detail")
   val lo:LegacyTicketVisualActionObservation=convert(o);val lp:LegacyTicketVisualActionObservation=convert(prior)
   for(tolerance in listOf(Int.MIN_VALUE,-1,0,3,Int.MAX_VALUE)) {assertEquals(LegacyticketVisualObservationsAgree(lp,lo,tolerance),ticketVisualObservationsAgree(prior,o,tolerance));comparisons++}
   val allow=random.nextBoolean();compare(old.offer(lo,allow),next.offer(o,allow));comparisons++
   for(t in TicketVisualActionTarget.entries) {
    val lt:LegacyTicketVisualActionTarget=convert(t);val anchors=TicketVisualSwitchAnchors("card","card");val la:LegacyTicketVisualSwitchAnchors=convert(anchors)
    compare(lo.timeTicketsNavigationBoundsFor(lt),o.timeTicketsNavigationBoundsFor(t));compare(lo.singleUseTicketsNavigationBoundsFor(lt),o.singleUseTicketsNavigationBoundsFor(t));compare(lo.cardFor(lt,la),o.cardFor(t,anchors));
    compare(LegacyticketVisualResultView(lt,convert(state)),ticketVisualResultView(t,state));comparisons+=4
   }
   compare(lo.latestRegistrationCard(),o.latestRegistrationCard());compare(lo.uniqueActivatedDetailCard(),o.uniqueActivatedDetailCard());
   compare(lo.activatedCardForRecentDetail(LegacyTicketVisualSwitchAnchors("d_detail","")),o.activatedCardForRecentDetail(TicketVisualSwitchAnchors("d_detail","")))
   compare(LegacyticketVisualRedetectListTabTarget(lo,false)?.first,ticketVisualRedetectListTabTarget(o,false)?.first)
   assertEquals(LegacyticketVisualRedetectListTabTarget(lo,false)?.second,ticketVisualRedetectListTabTarget(o,false)?.second);comparisons+=5
   for(now in listOf(-1L,0L,999L,1000L,1001L,4000L,Long.MIN_VALUE,Long.MAX_VALUE)) {
    assertEquals(LegacyticketVisualObservationIsFreshForDispatch(lo,now,3000),ticketVisualObservationIsFreshForDispatch(o,now,3000));comparisons++
   }
   val r=request(TicketVisualActionTarget.REGISTER_CURRENT);val proof=TicketRegistrationProof("unactivated_ready","","pc-context",0,0,0,0,ticketAnchor="card",detailAnchor="a")
   compare(LegacyticketRegistrationProofForCurrentVisualAction(proof,convert(r),lo),ticketRegistrationProofForCurrentVisualAction(proof,r,o));comparisons++
   compare(LegacyticketVisualObservationAfterCardSelection(lo,"card"),ticketVisualObservationAfterCardSelection(o,"card"))
   compare(LegacyticketVisualActivationObservationAfterCompletedGesture(lo,"card"),ticketVisualActivationObservationAfterCompletedGesture(o,"card"))
   compare(LegacyticketVisualObservationAfterRecentActivatedSelection(lo,"card","d_a"),ticketVisualObservationAfterRecentActivatedSelection(o,"card","d_a"));comparisons+=3
   prior=o
  }
  println("VISUAL_ACTION_OBSERVATION_PARITY_OK comparisons=$comparisons")
 }
}
