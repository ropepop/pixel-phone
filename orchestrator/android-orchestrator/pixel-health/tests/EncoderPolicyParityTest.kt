package lv.jolkins.pixelorchestrator.app.ticket
import org.junit.Assert.*
import org.junit.Test
import java.util.Random
class EncoderPolicyParityTest {
 @Test fun primingClockKeyframeSuppressionAndBoundaryDrainMatchFormerOwner() {
  val times=listOf(Long.MIN_VALUE,-1L,0L,1L,99L,100L,1000L,Long.MAX_VALUE);var comparisons=0
  for(requested in listOf(Int.MIN_VALUE,-1,0,1,2,3,4,Int.MAX_VALUE)) {
   val old=LegacyTicketEncoderStartupPrimer(requested);val next=TicketEncoderStartupPrimer(requested);val random=Random(42)
   val frame=TicketH264FrameRecord(true,1L,1L,1L,1L,1L,1L,1L,byteArrayOf(0,0,1,0x65))
   fun compare(a:()->Any?,b:()->Any?) {val expected=runCatching(a);val actual=runCatching(b)
    assertEquals(expected.exceptionOrNull()?.javaClass,actual.exceptionOrNull()?.javaClass)
    assertEquals(expected.exceptionOrNull()?.message,actual.exceptionOrNull()?.message)
    assertEquals(expected.getOrNull()?.toString(),actual.getOrNull()?.toString());comparisons++}
   repeat(500) {
    val now=times[random.nextInt(times.size)];val vcl=random.nextBoolean();val key=random.nextBoolean()
    when(random.nextInt(10)) {
     0->compare({old.noteInputPosted(now)},{next.noteInputPosted(now)})
     1,2->compare({old.classifyCompleteAccessUnit(vcl,key,now)},{next.classifyCompleteAccessUnit(vcl,key,now)})
     3->compare({old.beginBoundaryDrain()},{next.beginBoundaryDrain()})
     4->compare({old.bufferBoundaryAccessUnit(if(vcl) frame else null)},{next.bufferBoundaryAccessUnit(if(vcl) frame else null)})
     5->compare({old.completeBoundaryDrain()},{next.completeBoundaryDrain()})
     6->compare({old.noteBoundaryAccessUnitForwarded()},{next.noteBoundaryAccessUnitForwarded()})
     7->compare({old.beginFallbackWait()},{next.beginFallbackWait()})
     8->compare({old.finish()},{next.finish()})
     9->{val f=if(vcl) frame else null;compare({old.classifyCompleteAccessUnit(f,vcl,key,now)},{next.classifyCompleteAccessUnit(f,vcl,key,now)})}
    }
    assertEquals(old.canPostInput(now),next.canPostInput(now));assertEquals(old.canPostAnotherInput(),next.canPostAnotherInput());assertEquals(old.millisUntilNextInput(now),next.millisUntilNextInput(now))
    assertEquals(old.firstKeyFrameForwarded(),next.firstKeyFrameForwarded());assertEquals(old.finished(),next.finished());assertEquals(old.fallbackWaitingForFirstKeyFrame(),next.fallbackWaitingForFirstKeyFrame())
    assertEquals(old.boundaryDrainActive(),next.boundaryDrainActive());assertEquals(old.boundaryAccessUnitForwarded(),next.boundaryAccessUnitForwarded())
    assertEquals(old.inputLimit(),next.inputLimit());assertEquals(old.inputPosts(),next.inputPosts());assertEquals(old.mediaOutputs(),next.mediaOutputs());assertEquals(old.lastInputAtMillis(),next.lastInputAtMillis());comparisons+=12
   }
  }
  println("ENCODER_STARTUP_PARITY_OK comparisons=$comparisons")
 }
 @Test fun codecInputValidationCapacityAndGenerationWrapMatchFormerOwner() {
  val old=LegacyTicketCodecInputLedger();val next=TicketCodecInputLedger();var comparisons=0
  for(id in listOf(-1L,0L,1L,Long.MAX_VALUE)) for(generation in listOf(-1L,0L,1L,Long.MAX_VALUE))
   for(start in listOf(-1L,0L,1L,Long.MAX_VALUE)) for(complete in listOf(0L,1L,2L,Long.MAX_VALUE)) for(input in listOf(0L,1L,2L,Long.MAX_VALUE)) {
    old.clear();next.clear()
    repeat(10) {index->
     val a=LegacyTicketCodecInputLedger.InputStage(id,generation,start,complete,input-index)
     val b=TicketCodecInputLedger.InputStage(id,generation,start,complete,input-index)
     val previous=runCatching {old.add(a)};val actual=runCatching {next.add(b)}
     assertEquals(previous.exceptionOrNull()?.javaClass,actual.exceptionOrNull()?.javaClass);assertEquals(previous.exceptionOrNull()?.message,actual.exceptionOrNull()?.message)
     assertEquals(old.contains(a),next.contains(b));assertEquals(old.size(),next.size());comparisons++
    }
   }
  for(current in listOf(Long.MIN_VALUE,-1L,0L,Long.MAX_VALUE)) for(now in listOf(Long.MIN_VALUE,-1L,0L,Long.MAX_VALUE)) {
   assertEquals(maxOf(current+1,now),NativeTicketMedia.nextEncoderGeneration(current,now));comparisons++
  }
  println("CODEC_INPUT_PARITY_OK comparisons=$comparisons")
 }
}
