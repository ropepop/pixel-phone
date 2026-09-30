package lv.jolkins.pixelorchestrator.app.ticket
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
class PhoneControlParityTest {
 @Test fun contextEvidenceAndFreshnessMatchFormerOwnerAcrossInterferenceAndClockLimits() {
  val old=LegacyTicketPhoneControlState("pc-parity");val next=TicketPhoneControlState("pc-parity")
  val random=Random(42);var comparisons=0
  val times=listOf(Long.MIN_VALUE,-1L,0L,1L,500L,2999L,3000L,3001L,10000L,Long.MAX_VALUE)
  val fences=listOf(null,TicketRegistrationEvidenceFence(1,7,3,17,0,0),TicketRegistrationEvidenceFence(2,8,3,17,1,500),TicketRegistrationEvidenceFence(0,7,3,17,0,0))
  repeat(1000) {
   val now=times[random.nextInt(times.size)];val fence=fences[random.nextInt(fences.size)]
   when(random.nextInt(4)) {
    0,1->{val v=TicketVisualActionObservation(it.toLong(),TicketVisualPhoneState.entries[random.nextInt(TicketVisualPhoneState.entries.size)],
      currentAnchor=listOf("private-a","private-b",""," ","\u2007")[random.nextInt(5)],
      sliderBounds=if(random.nextBoolean()) TicketVisualProbeBounds(20,168,180,196) else null,
      captureStartUs=now*1000L,atMillis=now+20,captureGeneration=listOf(0L,7L,8L)[random.nextInt(3)])
      val busy=random.nextBoolean();val available=random.nextBoolean();old.observe(v,busy,fence,available);next.observe(v,busy,fence,available)}
    2->{val busy=random.nextBoolean();old.invalidate("physical_touch",busy,now);next.invalidate("physical_touch",busy,now)}
    3->{old.clearRegistrationEvidence();next.clearRegistrationEvidence()}
   }
   assertEquals(old.updates.value,next.updates.value);comparisons++
   for(revision in listOf(old.updates.value.contextRevision,"pc-parity:0","wrong")) for(time in listOf(now,now+2999L,now+3000L)) {
    assertEquals(old.exactContext(revision,time),next.exactContext(revision,time))
    assertEquals(old.registrationCandidateIsCurrent(revision,fence,time),next.registrationCandidateIsCurrent(revision,fence,time))
    val a=old.registrationEvidence(revision,fence,time);val b=next.registrationEvidence(revision,fence,time)
    assertEquals(a?.contextRevision,b?.contextRevision);assertEquals(a?.first,b?.first);assertEquals(a?.second,b?.second);assertEquals(a?.fence,b?.fence)
    assertEquals(LegacyticketPhoneControlRegistrationIdentity(revision,old.exactContext(revision,time)),ticketPhoneControlRegistrationIdentity(revision,next.exactContext(revision,time)))
    comparisons+=4
   }
  }
  for(server in times) for(received in times) for(captured in times) for(now in times) {
   assertEquals(LegacyTicketPhoneControlClock(server,received).observedAtMillis(captured,now),TicketPhoneControlClock(server,received).observedAtMillis(captured,now));comparisons++
  }
  // A pair of agreeing synthetic detail observations must grant and then expire evidence.
  val f=fences[1]!!
  val pairOld=LegacyTicketPhoneControlState("pc-pair"); val pairNext=TicketPhoneControlState("pc-pair")
  for(capture in listOf(10001L,11001L)) {
   val v=TicketVisualActionObservation(0,TicketVisualPhoneState.UNACTIVATED_DETAIL,"private-live",TicketVisualProbeBounds(20,168,180,196),atMillis=capture+20,captureStartUs=capture*1000,captureGeneration=7)
   pairOld.observe(v,false,f);pairNext.observe(v,false,f)
  }
  val revision=pairOld.updates.value.contextRevision
  assertNotNull(pairOld.registrationEvidence(revision,f,11001L)); assertNotNull(pairNext.registrationEvidence(revision,f,11001L))
  for(now in listOf(11001L,13000L,13001L,14000L,14001L)) {
   val a=pairOld.registrationEvidence(revision,f,now);val b=pairNext.registrationEvidence(revision,f,now)
   assertEquals(a?.isFresh(now),b?.isFresh(now));assertEquals(a?.first,b?.first);assertEquals(a?.second,b?.second);comparisons++
  }
  println("PHONE_CONTROL_PARITY_OK comparisons=$comparisons")
 }
}
