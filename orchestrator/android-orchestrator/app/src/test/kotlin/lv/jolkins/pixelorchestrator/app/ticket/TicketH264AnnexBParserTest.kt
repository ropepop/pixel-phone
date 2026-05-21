package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketH264AnnexBParserTest {
  @Test
  fun emitsKeyFrameWithParameterSetsBeforeIdr() {
    val frames = mutableListOf<Pair<Boolean, ByteArray>>()
    val parser = TicketH264AnnexBParser { payload, keyFrame ->
      frames += keyFrame to payload
    }

    val sps = nal(0x67, 0x11, 0x22)
    val pps = nal(0x68, 0x33)
    val idr = nal(0x65, 0x80, 0x55)
    val delta = nal(0x41, 0x80, 0x66)
    val stream = sps + pps + idr + delta

    parser.push(stream.copyOfRange(0, 9))
    parser.push(stream.copyOfRange(9, stream.size))
    parser.finish()

    assertEquals(2, frames.size)
    assertTrue(frames[0].first)
    assertArrayEquals(sps + pps + idr, frames[0].second)
    assertFalse(frames[1].first)
    assertArrayEquals(delta, frames[1].second)
  }

  @Test
  fun groupsMultipleSlicesForOnePictureIntoOneAccessUnit() {
    val frames = mutableListOf<Pair<Boolean, ByteArray>>()
    val parser = TicketH264AnnexBParser { payload, keyFrame ->
      frames += keyFrame to payload
    }

    val sps = nal(0x67, 0x11)
    val pps = nal(0x68, 0x22)
    val idrFirstSlice = nal(0x65, 0x80, 0x33)
    val idrSecondSlice = nal(0x65, 0x40, 0x44)
    val deltaFirstSlice = nal(0x41, 0x80, 0x55)
    val deltaSecondSlice = nal(0x41, 0x40, 0x66)

    parser.push(sps + pps + idrFirstSlice + idrSecondSlice + deltaFirstSlice + deltaSecondSlice)
    parser.finish()

    assertEquals(2, frames.size)
    assertTrue(frames[0].first)
    assertArrayEquals(sps + pps + idrFirstSlice + idrSecondSlice, frames[0].second)
    assertFalse(frames[1].first)
    assertArrayEquals(deltaFirstSlice + deltaSecondSlice, frames[1].second)
  }

  @Test
  fun accessUnitDelimiterFlushesCurrentPictureWithoutForwardingAud() {
    val frames = mutableListOf<Pair<Boolean, ByteArray>>()
    val parser = TicketH264AnnexBParser { payload, keyFrame ->
      frames += keyFrame to payload
    }

    val sps = nal(0x67, 0x11, 0x22)
    val pps = nal(0x68, 0x33)
    val idr = nal(0x65, 0x80, 0x55)
    val delta = nal(0x41, 0x80, 0x66)

    parser.push(sps + pps + idr + aud())
    assertEquals(1, frames.size)
    assertTrue(frames[0].first)
    assertArrayEquals(sps + pps + idr, frames[0].second)

    parser.push(delta + aud())
    assertEquals(2, frames.size)
    assertFalse(frames[1].first)
    assertArrayEquals(delta, frames[1].second)
  }

  private fun nal(header: Int, vararg payload: Int): ByteArray {
    return byteArrayOf(0, 0, 0, 1, header.toByte()) + payload.map { it.toByte() }.toByteArray()
  }

  private fun aud(): ByteArray = nal(0x09, 0x10)
}
