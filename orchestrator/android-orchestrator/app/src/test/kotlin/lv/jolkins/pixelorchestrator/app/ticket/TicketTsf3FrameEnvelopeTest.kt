package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketTsf3FrameEnvelopeTest {
  @Test
  fun emitsTheExactNinetyThreeByteBigEndianContract() {
    val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0, 0, 0, 1, 9, 16)
    val encoded = TicketTsf3FrameEnvelope.encode(
      true,
      10L,
      20L,
      30L,
      40L,
      50L,
      60L,
      70L,
      80L,
      90L,
      0L,
      0L,
      payload
    )
    val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)

    assertEquals(TicketTsf3FrameEnvelope.HEADER_BYTES + payload.size, encoded.size)
    assertEquals(TicketTsf3FrameEnvelope.MAGIC, input.int)
    assertEquals(TicketTsf3FrameEnvelope.FLAG_KEY_FRAME, input.get())
    listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 0L, 0L).forEach {
      assertEquals(it, input.long)
    }
    val actualPayload = ByteArray(input.remaining())
    input.get(actualPayload)
    assertArrayEquals(payload, actualPayload)
    assertEquals(93, TicketTsf3FrameEnvelope.HEADER_BYTES)
  }

  @Test
  fun rejectsIdentityFieldsOutsideTheBrowserExactIntegerRange() {
    repeat(4) { unsafeIndex ->
      val values = longArrayOf(10L, 20L, 30L, 40L)
      values[unsafeIndex] = TicketTsf3FrameEnvelope.MAX_SAFE_INTEGER + 1L
      assertTrue(runCatching {
        TicketTsf3FrameEnvelope.encode(
          true,
          values[0],
          values[1],
          values[2],
          values[3],
          50L,
          60L,
          70L,
          80L,
          90L,
          0L,
          0L,
          byteArrayOf(0, 0, 0, 1, 0x65)
        )
      }.exceptionOrNull() is IllegalArgumentException)
    }
  }
}
