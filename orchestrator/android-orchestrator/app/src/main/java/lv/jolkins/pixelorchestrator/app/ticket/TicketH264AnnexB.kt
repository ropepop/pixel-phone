package lv.jolkins.pixelorchestrator.app.ticket

import java.io.ByteArrayOutputStream

data class TicketRootCaptureFrame(
  val keyFrame: Boolean,
  val timestampUs: Long,
  val payload: ByteArray,
  val width: Int,
  val height: Int
)

class TicketH264AnnexBParser(
  private val onAccessUnit: (payload: ByteArray, keyFrame: Boolean) -> Unit
) {
  private var buffer = ByteArray(0)
  private var sps: ByteArray? = null
  private var pps: ByteArray? = null
  private val pendingAccessUnit = mutableListOf<ByteArray>()
  private var pendingHasVcl = false
  private var pendingKeyFrame = false

  fun push(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    buffer += bytes
    drain(keepTrailing = true)
  }

  fun finish() {
    drain(keepTrailing = false)
    flushAccessUnit()
    buffer = ByteArray(0)
  }

  private fun drain(keepTrailing: Boolean) {
    while (true) {
      val first = findStartCode(buffer, 0)
      if (first == null) {
        if (buffer.size > MAX_BUFFER_BYTES) {
          buffer = buffer.takeLast(TRAILING_SEARCH_BYTES).toByteArray()
        }
        return
      }
      if (first.index > 0) {
        buffer = buffer.copyOfRange(first.index, buffer.size)
      }
      val next = findStartCode(buffer, first.length)
      if (next == null) {
        if (keepTrailing && isAccessUnitDelimiter(buffer)) {
          processNal(buffer)
          buffer = ByteArray(0)
        } else if (!keepTrailing) {
          processNal(buffer)
        }
        return
      }
      val nal = buffer.copyOfRange(0, next.index)
      buffer = buffer.copyOfRange(next.index, buffer.size)
      processNal(nal)
    }
  }

  private fun processNal(nalWithStartCode: ByteArray) {
    val start = startCodeLengthAt(nalWithStartCode, 0) ?: return
    if (nalWithStartCode.size <= start) return
    val nal = nalWithStartCode.copyOf()
    when (val nalType = nal[start].toInt() and 0x1f) {
      NAL_SPS -> {
        if (pendingHasVcl) flushAccessUnit()
        sps = nal
        pendingAccessUnit += nal
      }

      NAL_PPS -> {
        if (pendingHasVcl) flushAccessUnit()
        pps = nal
        pendingAccessUnit += nal
      }

      NAL_AUD -> {
        flushAccessUnit()
      }

      in NAL_NON_IDR..NAL_IDR -> {
        val startsPicture = sliceStartsPicture(nal, start + 1)
        if (pendingHasVcl && startsPicture) flushAccessUnit()
        if (nalType == NAL_IDR) prependStoredParameterSetsIfMissing()
        pendingAccessUnit += nal
        pendingHasVcl = true
        pendingKeyFrame = pendingKeyFrame || nalType == NAL_IDR
      }

      else -> {
        if (pendingHasVcl) flushAccessUnit()
        pendingAccessUnit += nal
      }
    }
  }

  private fun flushAccessUnit() {
    if (!pendingHasVcl) {
      pendingAccessUnit.clear()
      pendingKeyFrame = false
      return
    }
    val output = ByteArrayOutputStream()
    pendingAccessUnit.forEach { output.write(it) }
    onAccessUnit(output.toByteArray(), pendingKeyFrame)
    pendingAccessUnit.clear()
    pendingHasVcl = false
    pendingKeyFrame = false
  }

  private fun prependStoredParameterSetsIfMissing() {
    if (!pendingAccessUnit.any { nalType(it) == NAL_SPS }) {
      sps?.let { pendingAccessUnit.add(0, it) }
    }
    if (!pendingAccessUnit.any { nalType(it) == NAL_PPS }) {
      val insertAt = pendingAccessUnit.indexOfLast { nalType(it) == NAL_SPS }.let { if (it >= 0) it + 1 else 0 }
      pps?.let { pendingAccessUnit.add(insertAt, it) }
    }
  }

  private fun nalType(nalWithStartCode: ByteArray): Int? {
    val start = startCodeLengthAt(nalWithStartCode, 0) ?: return null
    if (nalWithStartCode.size <= start) return null
    return nalWithStartCode[start].toInt() and 0x1f
  }

  private fun isAccessUnitDelimiter(nalWithStartCode: ByteArray): Boolean {
    return nalType(nalWithStartCode) == NAL_AUD
  }

  private fun sliceStartsPicture(nalWithStartCode: ByteArray, payloadOffset: Int): Boolean {
    val firstMbInSlice = readUnsignedExpGolomb(nalWithStartCode, payloadOffset) ?: return true
    return firstMbInSlice == 0
  }

  private fun readUnsignedExpGolomb(bytes: ByteArray, byteOffset: Int): Int? {
    if (byteOffset >= bytes.size) return null
    var bitOffset = byteOffset * 8
    val endBit = bytes.size * 8
    var leadingZeros = 0
    while (bitOffset < endBit && !readBit(bytes, bitOffset)) {
      leadingZeros += 1
      bitOffset += 1
      if (leadingZeros > 31) return null
    }
    if (bitOffset >= endBit) return null
    bitOffset += 1
    var suffix = 0
    repeat(leadingZeros) {
      if (bitOffset >= endBit) return null
      suffix = (suffix shl 1) or if (readBit(bytes, bitOffset)) 1 else 0
      bitOffset += 1
    }
    return (1 shl leadingZeros) - 1 + suffix
  }

  private fun readBit(bytes: ByteArray, bitOffset: Int): Boolean {
    val value = bytes[bitOffset / 8].toInt() and 0xff
    val shift = 7 - (bitOffset % 8)
    return ((value shr shift) and 1) == 1
  }

  private fun findStartCode(bytes: ByteArray, from: Int): StartCode? {
    var index = from.coerceAtLeast(0)
    while (index <= bytes.size - 3) {
      val length = startCodeLengthAt(bytes, index)
      if (length != null) return StartCode(index, length)
      index += 1
    }
    return null
  }

  private fun startCodeLengthAt(bytes: ByteArray, index: Int): Int? {
    if (index + 3 <= bytes.size &&
      bytes[index] == ZERO &&
      bytes[index + 1] == ZERO &&
      bytes[index + 2] == ONE
    ) {
      return 3
    }
    if (index + 4 <= bytes.size &&
      bytes[index] == ZERO &&
      bytes[index + 1] == ZERO &&
      bytes[index + 2] == ZERO &&
      bytes[index + 3] == ONE
    ) {
      return 4
    }
    return null
  }

  private data class StartCode(val index: Int, val length: Int)

  private companion object {
    private const val MAX_BUFFER_BYTES = 2 * 1024 * 1024
    private const val TRAILING_SEARCH_BYTES = 8
    private const val NAL_NON_IDR = 1
    private const val NAL_IDR = 5
    private const val NAL_AUD = 9
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private val ZERO = 0.toByte()
    private val ONE = 1.toByte()
  }
}
