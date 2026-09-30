package lv.jolkins.pixelorchestrator.app.ticket

import java.io.*
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Public stream/packet bytes and state transitions compared with the former owners. */
class MediaParityTest {
  private val stages = longArrayOf(1, 2, 3, 4, 5, 6, 7)
  private val payload = byteArrayOf(0, 0, 0, 1, 0x65)
  private fun record(values: LongArray = stages, bytes: ByteArray? = payload) =
    TicketH264FrameRecord(true, values[0], values[1], values[2], values[3], values[4], values[5], values[6], bytes)
  private fun oldRecord(values: LongArray = stages, bytes: ByteArray? = payload) =
    LegacyTicketH264FrameRecord(true, values[0], values[1], values[2], values[3], values[4], values[5], values[6], bytes)
  private fun bytes(old: Boolean, values: LongArray = stages, payload: ByteArray? = this.payload): Any {
    val output = ByteArrayOutputStream()
    return try {
      if (old) LegacyTicketH264FrameRecord.write(output, oldRecord(values, payload))
      else TicketH264FrameRecord.write(output, record(values, payload))
      output.toByteArray().toList()
    } catch (error: IOException) { listOf(error.javaClass.name, error.message, output.toByteArray().toList()) }
  }

  private class Fragmented(input: ByteArray, val chunk: Int) : ByteArrayInputStream(input) {
    var count = 0
    override fun read(output: ByteArray, offset: Int, length: Int): Int {
      if (count++ % 3 == 0) return 0
      return super.read(output, offset, minOf(length, chunk))
    }
  }
  private fun read(old: Boolean, data: ByteArray, chunk: Int = 7): Any? {
    val input = Fragmented(data, chunk)
    return try {
      val output = ByteArrayOutputStream()
      if (old) {
        val record = LegacyTicketH264FrameRecord.read(input) ?: return null
        LegacyTicketH264FrameRecord.write(output, record)
      } else {
        val record = TicketH264FrameRecord.read(input) ?: return null
        TicketH264FrameRecord.write(output, record)
      }
      listOf(output.toByteArray().toList(), input.available())
    } catch (error: IOException) { listOf(error.javaClass.name, error.message, input.available()) }
  }

  @Test fun thfWireBytesFailuresPartialReadsAndErrorPrecedenceMatch() {
    val output = ByteArrayOutputStream().also { TicketH264FrameRecord.write(it, record()) }.toByteArray()
    val golden = "5448463101" + "0000000000000001" + "0000000000000002" + "0000000000000003" +
      "0000000000000004" + "0000000000000005" + "0000000000000006" + "0000000000000007" + "00000005" + "0000000165"
    assertEquals(golden, output.joinToString("") { "%02x".format(it.toInt() and 255) })
    val delta = ByteArrayOutputStream().also {
      TicketH264FrameRecord.write(it, TicketH264FrameRecord(false, 1, 2, 3, 4, 5, 6, 7, payload))
    }.toByteArray()
    assertEquals(0, delta[4].toInt())
    assertEquals(read(true, delta), read(false, delta))
    assertEquals(bytes(true), bytes(false))
    for (index in stages.indices) for (value in listOf(-1L, 0L, 1L, 2L, 3L, 4L, 7L, 8L, Long.MAX_VALUE, Long.MIN_VALUE)) {
      val changed = stages.clone().also { it[index] = value }
      assertEquals(bytes(true, changed), bytes(false, changed))
    }
    for (body in listOf(null, byteArrayOf(), payload, ByteArray(2 * 1024 * 1024), ByteArray(2 * 1024 * 1024 + 1))) {
      assertEquals(bytes(true, payload = body), bytes(false, payload = body))
    }
    for (end in 0..output.size) for (chunk in listOf(1, 7, 100)) {
      val data = output.copyOf(end)
      assertEquals(read(true, data, chunk), read(false, data, chunk))
    }
    val mutations = mutableListOf<ByteArray>()
    for (offset in 0..64) for (bit in listOf(1, 16, 128)) {
      mutations += output.clone().also { it[offset] = (it[offset].toInt() xor bit).toByte() }
    }
    for (size in listOf(-1, 0, 1, 2 * 1024 * 1024, 2 * 1024 * 1024 + 1, Int.MAX_VALUE)) {
      mutations += output.clone().also { ByteBuffer.wrap(it).putInt(61, size) }
    }
    // An invalid timestamp does not hide a truncated payload.
    mutations += output.copyOf(output.size - 1).also { ByteBuffer.wrap(it).putLong(5, -1) }
    for (data in mutations) assertEquals(read(true, data), read(false, data))
    assertEquals(read(true, output + output), read(false, output + output))
  }

  private fun tsf(old: Boolean, values: LongArray, payload: ByteArray?): Any =
    try {
      val result = if (old) LegacyTicketTsf3FrameEnvelope.encode(true,
        values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10], payload)
      else TicketTsf3FrameEnvelope.encode(true,
        values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10], payload)
      result.toList()
    } catch (error: IllegalArgumentException) { listOf(error.javaClass.name, error.message) }

  @Test fun tsfBytesSafeIntegerLimitsAndValidationOrderMatch() {
    val values = LongArray(11) { it + 1L }
    val expected = "5453463301" + "0000000000000001" + "0000000000000002" + "0000000000000003" +
      "0000000000000004" + "0000000000000005" + "0000000000000006" + "0000000000000007" +
      "0000000000000008" + "0000000000000009" + "000000000000000a" + "000000000000000b" + "0000000165"
    val actual = TicketTsf3FrameEnvelope.encode(true, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, payload)
    assertEquals(expected, actual.joinToString("") { "%02x".format(it.toInt() and 255) })
    val delta = TicketTsf3FrameEnvelope.encode(false, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, payload)
    assertArrayEquals(actual.clone().also { it[4] = 0 }, delta)
    for (index in values.indices) for (value in listOf(-1L, 0L, 1L, 5L, 9L, 9_007_199_254_740_991L, 9_007_199_254_740_992L, Long.MAX_VALUE, Long.MIN_VALUE)) {
      val changed = values.clone().also { it[index] = value }
      for (body in listOf(null, byteArrayOf(), payload)) assertEquals(tsf(true, changed, body), tsf(false, changed, body))
    }
    for (body in listOf(null, byteArrayOf(), payload, ByteArray(2 * 1024 * 1024), ByteArray(2 * 1024 * 1024 + 1))) {
      assertEquals(tsf(true, values, body), tsf(false, values, body))
    }
  }

  private fun annex(nal: ByteArray) = byteArrayOf(0, 0, 0, 1) + nal
  private fun length(nal: ByteArray) = ByteBuffer.allocate(4).putInt(nal.size).array() + nal
  private val sps = byteArrayOf(0x67, 0x42, 0x11)
  private val pps = byteArrayOf(0x68, 0x33)
  private val idr = byteArrayOf(0x65, 0x55, 0x66)

  @Test fun assemblerFragmentedMalformedOverflowAndResetSequencesMatch() {
    val random = Random(381790)
    val corpus = listOf(null, byteArrayOf(), annex(sps), annex(pps), annex(idr),
      annex(sps) + annex(pps), length(sps) + length(pps), length(idr), annex(byteArrayOf(0x41)),
      byteArrayOf(0, 0, 0, 8, 0x67), length(ByteArray(0x167) { 0x67 }), annex(byteArrayOf(0xe5.toByte())),
      annex(byteArrayOf(0)), annex(idr) + byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 0, 1, 9, 16))
    repeat(100) {
      val old = LegacyTicketH264EncoderOutputAssembler()
      TicketH264EncoderOutputAssembler().use { new ->
        fun accept(data: ByteArray?, partial: Boolean, config: Boolean, key: Boolean) {
          val expected = old.accept(data, partial, config, key)
          val actual = new.accept(data, partial, config, key)
          if (expected == null) assertNull(actual) else {
            assertNotNull(actual)
            assertArrayEquals(expected.payload, actual!!.payload)
            assertEquals(expected.codecConfig, actual.codecConfig)
            assertEquals(expected.keyFrame, actual.keyFrame)
            assertEquals(expected.containsVcl, actual.containsVcl)
            assertEquals(expected.idrKeyFrame, actual.idrKeyFrame)
          }
          if (random.nextBoolean()) assertEquals(old.consumeOverflowed(), new.consumeOverflowed())
        }
        val configured = if (it % 2 == 0) annex(sps) + annex(pps) else length(sps) + length(pps)
        for (cut in 0..configured.size) {
          old.reset(); new.reset()
          accept(configured.copyOfRange(0, cut), true, true, false)
          accept(configured.copyOfRange(cut, configured.size), false, false, false)
          accept(if (it % 2 == 0) annex(idr) else length(idr), false, false, true)
        }
        repeat(100) {
          if (random.nextInt(12) == 0) { old.reset(); new.reset() }
          accept(corpus[random.nextInt(corpus.size)], random.nextBoolean(), random.nextBoolean(), random.nextBoolean())
        }
        if (it < 4) {
          accept(ByteArray(2 * 1024 * 1024), true, false, false)
          accept(byteArrayOf(1), true, false, false)
          accept(byteArrayOf(), false, false, false)
          accept(annex(idr), false, false, true)
          accept(ByteArray(2 * 1024 * 1024 + 1), false, false, false)
          old.reset(); new.reset()
          accept(annex(sps) + annex(pps), false, true, false)
          accept(annex(idr), false, false, true)
          // Cached SPS/PPS plus delimiter consume 19 bytes of the final bound.
          for (size in listOf(2 * 1024 * 1024 - 19, 2 * 1024 * 1024 - 18)) {
            val boundary = ByteArray(size) { 0x55 }
            byteArrayOf(0, 0, 0, 1, 0x65).copyInto(boundary)
            accept(boundary, false, false, true)
          }
        }
        assertEquals(old.consumeOverflowed(), new.consumeOverflowed())
      }
    }
  }

  @Test fun closedOwnersRejectEveryOperationBeforeReenteringNativeState() {
    repeat(100) {
      val owner = TicketH264EncoderOutputAssembler()
      owner.accept(ByteArray(512 * 1024), true, true, false)
      owner.close()
      owner.close()
      val calls = listOf<() -> Unit>(
        { owner.accept(payload, false, false, false) },
        { owner.reset() }, { owner.consumeOverflowed() }
      )
      calls.forEach { call ->
        try { call(); fail("closed owner must reject native access") }
        catch (expected: IllegalStateException) { assertEquals("assembler is closed", expected.message) }
      }
    }
  }
}

/** Optional timings cover assembler -> THF1 stream -> TSF3 packet, with equal bytes. */
object MediaMeasurements {
  private class Pipeline(old: Boolean) : AutoCloseable {
    private val legacy = if (old) LegacyTicketH264EncoderOutputAssembler() else null
    private val native = if (old) null else TicketH264EncoderOutputAssembler()
    private val input = byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(64 * 1024) { 0x55 }
    init {
      val config = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x11, 0, 0, 0, 1, 0x68, 0x33)
      if (legacy != null) legacy.accept(config, false, true, false)
      else native!!.accept(config, false, true, false)
    }
    fun frame(): ByteArray {
      val output = ByteArrayOutputStream()
      if (legacy != null) {
        val unit = legacy.accept(input, false, false, true)!!
        LegacyTicketH264FrameRecord.write(output, LegacyTicketH264FrameRecord(true, 1, 2, 3, 4, 5, 6, 7, unit.payload))
        val record = LegacyTicketH264FrameRecord.read(ByteArrayInputStream(output.toByteArray()))
        return LegacyTicketTsf3FrameEnvelope.encode(true, 1, 2, record.captureAttemptId, record.codecGeneration,
          record.captureStartUs, record.captureCompleteUs, record.codecInputUs, record.codecOutputUs,
          record.recordEmissionUs, 0, 0, record.payload)
      }
      val unit = native!!.accept(input, false, false, true)!!
      TicketH264FrameRecord.write(output, TicketH264FrameRecord(true, 1, 2, 3, 4, 5, 6, 7, unit.payload))
      val record = TicketH264FrameRecord.read(ByteArrayInputStream(output.toByteArray()))
      return TicketTsf3FrameEnvelope.encode(true, 1, 2, record.captureAttemptId, record.codecGeneration,
        record.captureStartUs, record.captureCompleteUs, record.codecInputUs, record.codecOutputUs,
        record.recordEmissionUs, 0, 0, record.payload)
    }
    override fun close() { native?.close() }
  }
  @JvmStatic fun main(args: Array<String>) {
    // This source compiles against Android's SDK but measurements run on the
    // host JVM, where java.management is available.
    val cpu = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    val cpuMethod = Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")
    fun cpuTime() = cpuMethod.invoke(cpu) as Long
    fun sample(operation: () -> ByteArray): Pair<Double, Double> {
      val core = cpuTime()
      val start = System.nanoTime()
      check(operation().size > 64 * 1024)
      return (System.nanoTime() - start) / 1e6 to (cpuTime() - core) / 1e6
    }
    if (args[0] != "Warm") {
      val measured = sample { Pipeline(args[0] == "Legacy").use { it.frame() } }
      println("${args[0]} first 64KiB media journey: wall_ms=${measured.first} cpu_ms=${measured.second}")
    } else Pipeline(true).use { old -> Pipeline(false).use { new ->
      repeat(30) { check(old.frame().contentEquals(new.frame())) }
      val oldTimes = mutableListOf<Pair<Double, Double>>()
      val newTimes = mutableListOf<Pair<Double, Double>>()
      repeat(500) { index ->
        if (index % 2 == 0) { oldTimes += sample(old::frame); newTimes += sample(new::frame) }
        else { newTimes += sample(new::frame); oldTimes += sample(old::frame) }
      }
      for ((engine, values) in listOf("Legacy" to oldTimes, "Rust" to newTimes)) {
        val wall = values.map { it.first }.sorted()
        val core = values.map { it.second }.sorted()
        println("$engine 500 64KiB media journeys: success=500 p50_ms=${wall[250]} p95_ms=${wall[475]} cpu_p50_ms=${core[250]}")
      }
    } }
  }
}
