package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class TicketMediaLifetimeRegressionTest {
  private class Picture : AutoCloseable {
    var releases = 0
    override fun close() { releases++ }
  }

  @Test fun replacingPendingAndStoppingCannotReleaseTheConsumerPicture() {
    val handoff = TicketNewestFrameHandoff<Picture>()
    val active = Picture()
    val replaced = Picture()
    val newest = Picture()
    handoff.offer(active)
    assertSame(active, handoff.take())
    handoff.offer(replaced)
    handoff.offer(newest)
    assertEquals(1, replaced.releases)
    handoff.close()
    assertEquals(1, newest.releases)
    assertEquals(0, active.releases)
    active.close()
    assertEquals(1, active.releases)
    assertNull(handoff.take())
  }

  @Test fun recognitionAndEncodingKeepIndependentPictureReferences() {
    var releases = 0
    val lifetime = TicketSharedFrameLifetime { releases++ }
    lifetime.retain()
    lifetime.close()
    assertEquals(0, releases)
    lifetime.close()
    assertEquals(1, releases)
    assertThrows(IllegalStateException::class.java) { lifetime.retain() }
  }

  @Test fun latePrimerSiblingsCannotReplaceTheNewestBoundaryPicture() {
    val primer = TicketEncoderStartupPrimer(0)
    primer.noteInputPosted(0)
    primer.noteInputPosted(100)
    primer.noteInputPosted(200)
    assertEquals(TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 210))
    assertEquals(TicketEncoderStartupPrimer.OutputDisposition.SUPPRESS,
      primer.classifyCompleteAccessUnit(true, true, 220))
    primer.beginBoundaryDrain()
    val older = record(1)
    val current = record(2)
    primer.classifyCompleteAccessUnit(older, true, true, 1000)
    primer.classifyCompleteAccessUnit(current, true, true, 1001)
    assertSame(current, primer.completeBoundaryDrain())
    primer.noteBoundaryAccessUnitForwarded()
    primer.finish()
    assertEquals(TicketEncoderStartupPrimer.OutputDisposition.FORWARD,
      primer.classifyCompleteAccessUnit(true, true, 2000))
  }

  @Test fun partialAccessUnitSurvivesImmediateCodecBufferReuse() {
    val assembler = TicketH264EncoderOutputAssembler()
    val config = byteArrayOf(0,0,0,1,0x67,0x42,0x11,0,0,0,1,0x68,0x33)
    assembler.accept(config, false, true, false)
    var releases = 0
    fun owned(bytes: ByteArray): ByteArray {
      val buffer = ByteBuffer.wrap(bytes)
      return TicketCodecOutputCopy.copyAndRelease({ buffer }, 0, bytes.size) {
        bytes.fill(0)
        releases++
      }
    }
    assertNull(assembler.accept(owned(byteArrayOf(0,0,0,1,0x65,0x55)), true, false, true))
    val frame = assembler.accept(owned(byteArrayOf(0x66)), false, false, false)!!
    assertTrue(frame.idrKeyFrame)
    assertTrue(frame.payload.toList().windowed(3).contains(listOf(0x65.toByte(),0x55.toByte(),0x66.toByte())))
    assertEquals(2, releases)
  }

  private fun record(attempt: Long) = TicketH264FrameRecord(
    true, attempt, 1, 1, 2, 3, 4, 5, byteArrayOf(1)
  )
}
