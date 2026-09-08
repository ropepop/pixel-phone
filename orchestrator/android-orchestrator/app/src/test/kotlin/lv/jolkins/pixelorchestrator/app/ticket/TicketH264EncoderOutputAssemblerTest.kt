package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.*
import org.junit.Test

class TicketH264EncoderOutputAssemblerTest {
  private val delimiter = byteArrayOf(0, 0, 0, 1, 9, 16)
  private val sps = byteArrayOf(0x67, 0x42, 0x11)
  private val pps = byteArrayOf(0x68, 0x33)
  private val idr = byteArrayOf(0x65, 0x55, 0x66)
  private fun annex(nal: ByteArray) = byteArrayOf(0, 0, 0, 1) + nal
  private fun length(nal: ByteArray) = byteArrayOf(
    (nal.size ushr 24).toByte(), (nal.size ushr 16).toByte(),
    (nal.size ushr 8).toByte(), nal.size.toByte()
  ) + nal

  @Test fun partialLengthPrefixAndFlagsBelongToOneAccessUnit() {
    val assembler = TicketH264EncoderOutputAssembler()
    val configuration = length(sps) + length(pps)
    assertNull(assembler.accept(configuration.copyOfRange(0, 2), true, true, false))
    assertNull(assembler.accept(configuration.copyOfRange(2, 7), true, false, false))
    val config = assembler.accept(configuration.copyOfRange(7, configuration.size), false, false, false)!!
    assertTrue(config.codecConfig)
    assertFalse(config.containsVcl)
    assertArrayEquals(annex(sps) + annex(pps) + delimiter, config.payload)
    val frame = length(idr)
    assertNull(assembler.accept(frame.copyOfRange(0, 3), true, false, true))
    assertNull(assembler.accept(byteArrayOf(), false, false, false))
    val output = assembler.accept(frame.copyOfRange(3, frame.size), false, false, false)!!
    assertTrue(output.keyFrame)
    assertTrue(output.idrKeyFrame)
    assertTrue(output.containsVcl)
    assertArrayEquals(annex(sps) + annex(pps) + annex(idr) + delimiter, output.payload)
  }

  @Test fun idrRequiresBothParameterSetsAndResetDropsTheirAuthority() {
    val assembler = TicketH264EncoderOutputAssembler()
    assertNull(assembler.accept(annex(idr), false, false, true))
    assertNotNull(assembler.accept(annex(sps), false, true, false))
    assertNull(assembler.accept(annex(idr), false, false, true))
    assertNotNull(assembler.accept(annex(pps), false, true, false))
    // The encoder flag alone never establishes that a picture is an IDR.
    val delta = assembler.accept(annex(byteArrayOf(0x41, 0x33)), false, false, true)!!
    assertTrue(delta.keyFrame)
    assertFalse(delta.idrKeyFrame)
    val complete = annex(sps) + annex(pps) + annex(idr) + delimiter
    assertArrayEquals(complete, assembler.accept(complete, false, false, false)!!.payload)
    assembler.reset()
    assertNull(assembler.accept(annex(idr), false, false, true))
  }

  @Test fun ambiguousAndMalformedFramingCannotEstablishOrChangeConfiguration() {
    val assembler = TicketH264EncoderOutputAssembler()
    val ambiguous = length(ByteArray(0x167) { 0x67 })
    assertNull(assembler.accept(ambiguous, false, true, false))
    assertNull(assembler.accept(byteArrayOf(0, 0, 0, 8, 0x67), false, true, false))
    assertNotNull(assembler.accept(annex(sps) + annex(pps), false, true, false))
    assertNull(assembler.accept(annex(byteArrayOf(0xe5.toByte())), false, false, true))
    assertNull(assembler.accept(annex(idr) + byteArrayOf(0, 0, 1), false, false, true))
    assertNull(assembler.accept(length(idr), false, false, true))
    assertNotNull(assembler.accept(annex(idr), false, false, true))
  }

  @Test fun overflowDiscardsRemainingFragmentsAndRecoversAtNextWholeUnit() {
    val assembler = TicketH264EncoderOutputAssembler()
    assertNotNull(assembler.accept(annex(sps) + annex(pps), false, true, false))
    assertNull(assembler.accept(ByteArray(TicketH264EncoderOutputAssembler.MAX_ASSEMBLY_BYTES), true, false, false))
    assertNull(assembler.accept(byteArrayOf(1), true, false, false))
    assertTrue(assembler.consumeOverflowed())
    assertFalse(assembler.consumeOverflowed())
    assertNull(assembler.accept(annex(idr), false, false, true))
    assertNotNull(assembler.accept(annex(idr), false, false, true))
  }
}
