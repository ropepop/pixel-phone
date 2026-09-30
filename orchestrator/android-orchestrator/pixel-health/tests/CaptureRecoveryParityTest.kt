package lv.jolkins.pixelorchestrator.app.ticket
import org.junit.Assert.*
import org.junit.Test
class CaptureRecoveryParityTest {
 @Test fun inactiveWarmDemandAndFirstUsefulPictureMatchFormerOwner() {
  val ages=listOf(null,Long.MIN_VALUE,-1L,0L,1L,2999L,3000L,3001L,Long.MAX_VALUE);var comparisons=0
  for(active in listOf(false,true)) for(state in listOf("idle","starting","restarting","live")) for(gated in listOf(false,true)) for(expected in listOf(false,true)) for(pending in listOf(false,true))
   for(frameAge in ages) for(startAge in ages) for(expectedAge in ages) {
    assertEquals(LegacyTicketStreamStartupRecoveryPolicy.canContinueCurrentEncoder(active,state,gated,expected,pending,frameAge,startAge,3000L,3000L,expectedAge),
      TicketStreamStartupRecoveryPolicy.canContinueCurrentEncoder(active,state,gated,expected,pending,frameAge,startAge,3000L,3000L,expectedAge));comparisons++
   }
  for(active in listOf(false,true)) for(state in listOf("idle","starting","restarting","live")) for(start in ages) for(frame in ages) for(source in ages) for(grace in listOf(-1L,0L,1L,3000L,Long.MAX_VALUE)) {
   assertEquals(LegacyTicketStreamStartupRecoveryPolicy.waitingForFirstUsefulFrame(active,state,start,frame,source,grace,1000L),TicketStreamStartupRecoveryPolicy.waitingForFirstUsefulFrame(active,state,start,frame,source,grace,1000L));comparisons++
  }
  println("CAPTURE_RECOVERY_PARITY_OK comparisons=$comparisons")
 }
 @Test fun sparseVisibilityUniformBlackAndLuminanceBoundsMatchFormerOwner() {
  var comparisons=0
  for(size in listOf(0,1,7,8,64,3456)) for(bright in listOf(0,1,7,8,17,32)) for(background in listOf(0,34,35,60,61,179,180,255)) {
   val pixels=IntArray(size) {if(it<bright) -1 else 0xff000000.toInt() or(background shl 16) or(background shl 8) or background}
   assertEquals(LegacyTicketCaptureVisibilityClassifier.looksVisible(pixels),TicketCaptureVisibilityClassifier.looksVisible(pixels));comparisons++
  }
  val random=java.util.Random(90)
  repeat(500) {val pixels=IntArray(3456) {random.nextInt()};assertEquals(LegacyTicketCaptureVisibilityClassifier.looksVisible(pixels),TicketCaptureVisibilityClassifier.looksVisible(pixels));comparisons++}
  assertEquals(LegacyTicketCaptureVisibilityClassifier.looksVisible(null),TicketCaptureVisibilityClassifier.looksVisible(null));comparisons++
  println("CAPTURE_VISIBILITY_PARITY_OK comparisons=$comparisons")
 }
}
