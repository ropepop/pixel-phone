package lv.jolkins.pixelorchestrator.app.ticket

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketH264FrameRecordTest {
  @Test
  fun roundTripsEveryStageAndPreservesAnnexBWithAudByteForByte() {
    val payload = byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x65, 2, 0, 0, 0, 1, 9, 16)
    val expected = record(payload)
    val output = ByteArrayOutputStream()

    TicketH264FrameRecord.write(output, expected)
    val encoded = output.toByteArray()
    val actual = TicketH264FrameRecord.read(ByteArrayInputStream(encoded))!!

    assertEquals(TicketH264FrameRecord.HEADER_BYTES + payload.size, encoded.size)
    assertEquals(expected.keyFrame, actual.keyFrame)
    assertEquals(expected.captureAttemptId, actual.captureAttemptId)
    assertEquals(expected.codecGeneration, actual.codecGeneration)
    assertEquals(expected.captureStartUs, actual.captureStartUs)
    assertEquals(expected.captureCompleteUs, actual.captureCompleteUs)
    assertEquals(expected.codecInputUs, actual.codecInputUs)
    assertEquals(expected.codecOutputUs, actual.codecOutputUs)
    assertEquals(expected.recordEmissionUs, actual.recordEmissionUs)
    assertArrayEquals(payload, actual.payload)
    assertNull(TicketH264FrameRecord.read(ByteArrayInputStream(byteArrayOf())))
  }

  @Test
  fun readsAcrossOneByteChunksAndRejectsTruncation() {
    val output = ByteArrayOutputStream()
    TicketH264FrameRecord.write(output, record(byteArrayOf(0, 0, 0, 1, 0x65, 0x11)))
    val encoded = output.toByteArray()
    val oneByteInput = object : ByteArrayInputStream(encoded) {
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
        super.read(bytes, offset, length.coerceAtMost(1))
    }
    assertTrue(TicketH264FrameRecord.read(oneByteInput) != null)

    listOf(1, TicketH264FrameRecord.HEADER_BYTES - 1, encoded.size - 1).forEach { size ->
      assertTrue(runCatching {
        TicketH264FrameRecord.read(ByteArrayInputStream(encoded.copyOf(size)))
      }.exceptionOrNull() is IOException)
    }
  }

  @Test
  fun enforcesTwoMiBAtWriterAndReaderBeforePayloadAllocation() {
    val exact = ByteArray(TicketH264FrameRecord.MAX_PAYLOAD_BYTES) { 1 }
    val output = ByteArrayOutputStream()
    TicketH264FrameRecord.write(output, record(exact))
    assertEquals(TicketH264FrameRecord.HEADER_BYTES + exact.size, output.size())

    assertTrue(runCatching {
      TicketH264FrameRecord.write(
        ByteArrayOutputStream(),
        record(ByteArray(TicketH264FrameRecord.MAX_PAYLOAD_BYTES + 1))
      )
    }.exceptionOrNull() is IOException)

    val invalidHeader = output.toByteArray().copyOf(TicketH264FrameRecord.HEADER_BYTES)
    java.nio.ByteBuffer.wrap(invalidHeader)
      .order(java.nio.ByteOrder.BIG_ENDIAN)
      .putInt(TicketH264FrameRecord.HEADER_BYTES - 4, TicketH264FrameRecord.MAX_PAYLOAD_BYTES + 1)
    assertTrue(runCatching {
      TicketH264FrameRecord.read(ByteArrayInputStream(invalidHeader))
    }.exceptionOrNull() is IOException)
  }

  private fun record(payload: ByteArray) = TicketH264FrameRecord(
    true,
    7L,
    11L,
    1_000L,
    1_010L,
    1_020L,
    1_030L,
    1_040L,
    payload
  )
}
