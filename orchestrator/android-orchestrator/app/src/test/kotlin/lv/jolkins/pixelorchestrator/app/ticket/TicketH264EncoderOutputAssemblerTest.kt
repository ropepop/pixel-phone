package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketH264EncoderOutputAssemblerTest {
  @Test
  fun convertsLengthPrefixedAccessUnitOnceAcrossEverySplitBoundary() {
    val sps = nalPayload(0x67, 0x11, 0x22)
    val pps = nalPayload(0x68, 0x33)
    val idr = nalPayload(0x65, 0x80, 0x55, 0x66)
    val lengthPrefixed = lengthPrefixed(sps, pps, idr)
    val expected = annexB(sps, pps, idr) + aud()

    for (split in 1 until lengthPrefixed.size) {
      val assembler = TicketH264EncoderOutputAssembler()
      assertNull(
        assembler.accept(
          lengthPrefixed.copyOfRange(0, split),
          true,
          true,
          true
        )
      )
      val emitted = assembler.accept(
        lengthPrefixed.copyOfRange(split, lengthPrefixed.size),
        false,
        false,
        false
      )

      assertTrue("missing emission at split $split", emitted != null)
      assertArrayEquals(expected, emitted!!.payload)
      assertTrue(emitted.keyFrame)
      assertFalse(assembler.hasPending())
      assertEquals(1, countOccurrences(emitted.payload, aud()))
    }
  }

  @Test
  fun convertsLengthPrefixedAccessUnitsWhoseLengthsLookLikeStartCodes() {
    val sizes = buildList {
      add(1)
      addAll(0x100..0x1ff)
    }

    sizes.forEach { size ->
      val nal = ByteArray(size) { index ->
        if (index == 0) 0x61.toByte() else (index and 0xff).toByte()
      }
      val assembler = TicketH264EncoderOutputAssembler()
      latchLengthPrefixed(assembler)
      val emitted = assembler.accept(lengthPrefixed(nal), false, false, false)

      assertTrue("missing emission for NAL size $size", emitted != null)
      assertArrayEquals("bad conversion for NAL size $size", annexB(nal) + aud(), emitted!!.payload)
      assertEquals(1, countOccurrences(emitted.payload, aud()))
    }
  }

  @Test
  fun preservesThreeAndFourByteAnnexBStartCodes() {
    val nal = nalPayload(0x61, 0x80, 0x11, 0x22)
    val variants = listOf(
      byteArrayOf(0, 0, 1) to (byteArrayOf(0, 0, 1) + nal),
      byteArrayOf(0, 0, 0, 1) to (byteArrayOf(0, 0, 0, 1) + nal)
    )

    variants.forEach { (startCode, annexB) ->
      val assembler = TicketH264EncoderOutputAssembler()
      latchAnnexB(assembler, startCode)
      val emitted = assembler.accept(annexB, false, false, false)

      assertTrue(emitted != null)
      assertArrayEquals(annexB + aud(), emitted!!.payload)
    }
  }

  @Test
  fun preservesAnnexBFramesThatAlsoFormCompleteLengthPrefixedData() {
    val threeByteStartCode = byteArrayOf(0, 0, 1)
    val fourByteStartCode = byteArrayOf(0, 0, 0, 1)
    val collidingNal = byteArrayOf(0x61) + ByteArray(0x161) { 0x55 }
    val threeByteCollision = threeByteStartCode + collidingNal
    val fourByteCollision =
      fourByteStartCode + byteArrayOf(0x0a) + threeByteStartCode + collidingNal

    listOf(
      threeByteStartCode to threeByteCollision,
      fourByteStartCode to fourByteCollision
    ).forEach { (configStartCode, annexB) ->
      val assembler = TicketH264EncoderOutputAssembler()
      latchAnnexB(assembler, configStartCode)
      val emitted = assembler.accept(annexB, false, false, false)

      assertTrue(emitted != null)
      assertArrayEquals(annexB + aud(), emitted!!.payload)
    }
  }

  @Test
  fun failsClosedUntilCodecConfigurationSelectsFramingMode() {
    val assembler = TicketH264EncoderOutputAssembler()
    val media = lengthPrefixed(nalPayload(0x65, 0x80, 0x55))
    val ambiguousConfig = lengthPrefixed(byteArrayOf(0x67))

    assertNull(assembler.accept(media, false, false, true))
    assertNull(assembler.accept(ambiguousConfig, false, true, false))
    assertNull(assembler.accept(media, false, false, true))

    latchLengthPrefixed(assembler)
    assertTrue(assembler.accept(media, false, false, true) != null)
  }

  @Test
  fun emitsOneAnnexBAccessUnitForSeveralPartialBuffersAndMergesFlags() {
    val idr = nalPayload(0x65, 0x80, 0x01, 0x02, 0x03)
    val bytes = lengthPrefixed(idr)
    val first = bytes.copyOfRange(0, 2)
    val middle = bytes.copyOfRange(2, bytes.size - 1)
    val last = bytes.copyOfRange(bytes.size - 1, bytes.size)
    val assembler = TicketH264EncoderOutputAssembler()
    latchLengthPrefixed(assembler)

    val firstEmitted = assembler.accept(first, true, true, true)
    val middleEmitted = assembler.accept(middle, true, false, false)
    assertNull(firstEmitted)
    assertNull(middleEmitted)
    val emitted = assembler.accept(last, false, false, false)

    assertTrue(emitted != null)
    assertArrayEquals(annexB(idr) + aud(), emitted!!.payload)
    assertTrue(emitted.codecConfig)
    assertTrue(emitted.keyFrame)
    val drainProgress = TicketEncoderDrainProgress
      .fromDequeuedOutput(first.size, 0, false, false)
      .plus(TicketEncoderDrainProgress.fromDequeuedOutput(middle.size, 0, false, false))
      .plus(
        TicketEncoderDrainProgress.fromDequeuedOutput(
          last.size,
          emitted.payload.size,
          emitted.codecConfig,
          emitted.keyFrame
        )
      )
    assertTrue(drainProgress.madeCodecProgress)
    assertEquals(1, drainProgress.encodedFrameOutputs)
    assertEquals(1, countOccurrences(emitted.payload, aud()))
  }

  @Test
  fun flushDropsPartialAccessUnitAndDoesNotLeakItIntoNextFrame() {
    val first = lengthPrefixed(nalPayload(0x65, 0x80, 0x10, 0x11))
    val second = lengthPrefixed(nalPayload(0x41, 0x80, 0x20, 0x21))
    val assembler = TicketH264EncoderOutputAssembler()
    latchLengthPrefixed(assembler)

    assertNull(assembler.accept(first.copyOfRange(0, first.size - 1), true, false, true))
    assertTrue(assembler.hasPending())
    assembler.flush()
    assertFalse(assembler.hasPending())
    assertNull(assembler.accept(second, false, false, false))
    latchLengthPrefixed(assembler)

    val emitted = assembler.accept(second, false, false, false)
    assertTrue(emitted != null)
    assertArrayEquals(annexB(nalPayload(0x41, 0x80, 0x20, 0x21)) + aud(), emitted!!.payload)
    assertFalse(emitted.keyFrame)
  }

  @Test
  fun zeroByteFinalOrEosMarkerDoesNotFinalizePartialAccessUnit() {
    val frame = lengthPrefixed(nalPayload(0x65, 0x80, 0x30, 0x31))
    val assembler = TicketH264EncoderOutputAssembler()
    latchLengthPrefixed(assembler)

    assertNull(assembler.accept(frame.copyOfRange(0, frame.size - 1), true, false, true))
    assertNull(assembler.accept(byteArrayOf(), false, false, false))
    assertTrue(assembler.hasPending())

    assembler.flush()
    assertFalse(assembler.hasPending())
  }

  @Test
  fun dropsOverflowAndSkipsTheRemainderOfThatPartialAccessUnit() {
    val assembler = TicketH264EncoderOutputAssembler()
    latchLengthPrefixed(assembler)
    val oversized = ByteArray(TicketH264EncoderOutputAssembler.MAX_ASSEMBLY_BYTES + 1)

    assertNull(assembler.accept(oversized, true, false, true))
    assertTrue(assembler.hasPending())
    assertNull(assembler.accept(byteArrayOf(0x65), false, false, true))
    assertFalse(assembler.hasPending())

    val next = assembler.accept(lengthPrefixed(nalPayload(0x41, 0x80, 0x44)), false, false, false)
    assertTrue(next != null)
  }

  private fun latchLengthPrefixed(assembler: TicketH264EncoderOutputAssembler) {
    val config = lengthPrefixed(
      nalPayload(0x67, 0x11, 0x22),
      nalPayload(0x68, 0x33)
    )
    assertTrue(assembler.accept(config, false, true, false) != null)
  }

  private fun latchAnnexB(assembler: TicketH264EncoderOutputAssembler, startCode: ByteArray) {
    val config =
      startCode + nalPayload(0x67, 0x11, 0x22) +
        startCode + nalPayload(0x68, 0x33)
    assertTrue(assembler.accept(config, false, true, false) != null)
  }

  private fun nalPayload(header: Int, vararg payload: Int): ByteArray {
    return byteArrayOf(header.toByte()) + payload.map { it.toByte() }.toByteArray()
  }

  private fun lengthPrefixed(vararg nals: ByteArray): ByteArray {
    val output = mutableListOf<Byte>()
    nals.forEach { nal ->
      val size = nal.size
      output += (size ushr 24).toByte()
      output += (size ushr 16).toByte()
      output += (size ushr 8).toByte()
      output += size.toByte()
      output += nal.toList()
    }
    return output.toByteArray()
  }

  private fun annexB(vararg nals: ByteArray): ByteArray {
    return nals.fold(byteArrayOf()) { result, nal ->
      result + byteArrayOf(0, 0, 0, 1) + nal
    }
  }

  private fun aud(): ByteArray = byteArrayOf(0, 0, 0, 1, 9, 16)

  private fun countOccurrences(bytes: ByteArray, needle: ByteArray): Int {
    var count = 0
    for (index in 0..(bytes.size - needle.size)) {
      if (bytes.copyOfRange(index, index + needle.size).contentEquals(needle)) {
        count += 1
      }
    }
    return count
  }
}
