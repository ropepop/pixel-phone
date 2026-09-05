package lv.jolkins.pixelorchestrator.app.ticket

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class TicketWebSocket(
  private val socket: Socket,
  private val input: BufferedInputStream,
  private val output: BufferedOutputStream,
  private val onText: suspend (String) -> Unit,
  private val onClose: () -> Unit,
  binaryFramesInitiallyAllowed: Boolean = true
) {
  private val open = AtomicBoolean(true)
  private val writeLock = Any()
  private var binaryFramesAllowed = binaryFramesInitiallyAllowed

  fun sendText(value: String) {
    sendFrame(opcode = OPCODE_TEXT, payload = value.toByteArray(Charsets.UTF_8))
  }

  fun sendTextAtWrite(buildValue: () -> String): Boolean {
    return sendFrame(
      opcode = OPCODE_TEXT,
      payload = byteArrayOf(),
      payloadAtWrite = { buildValue().toByteArray(Charsets.UTF_8) }
    )
  }

  fun sendConfigAndAllowBinary(value: String): Boolean {
    return sendFrame(
      opcode = OPCODE_TEXT,
      payload = value.toByteArray(Charsets.UTF_8),
      allowBinaryAfterSend = true
    )
  }

  fun sendConfigAndAllowBinaryIf(value: String, canSend: () -> Boolean): Boolean {
    return sendFrame(
      opcode = OPCODE_TEXT,
      payload = value.toByteArray(Charsets.UTF_8),
      allowBinaryAfterSend = true,
      canSend = canSend,
      closeOnFailure = false
    )
  }

  fun binaryFramesAllowed(): Boolean = synchronized(writeLock) {
    open.get() && binaryFramesAllowed
  }

  fun isOpen(): Boolean = open.get()

  fun sendBinary(payload: ByteArray): Boolean {
    return sendFrame(opcode = OPCODE_BINARY, payload = payload, requireBinaryAllowed = true)
  }

  fun sendBinaryIf(payload: ByteArray, canSend: () -> Boolean): Boolean {
    return sendFrame(
      opcode = OPCODE_BINARY,
      payload = payload,
      requireBinaryAllowed = true,
      canSend = canSend,
      closeOnFailure = false
    )
  }

  suspend fun readLoop() {
    try {
      while (open.get()) {
        val first = input.read()
        if (first < 0) break
        val second = input.read()
        if (second < 0) break
        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var length = (second and 0x7F).toLong()
        if (length == 126L) {
          length = ((input.readRequired() shl 8) or input.readRequired()).toLong()
        } else if (length == 127L) {
          length = 0L
          repeat(8) {
            length = (length shl 8) or input.readRequired().toLong()
          }
        }
        val mask = if (masked) ByteArray(4) { input.readRequired().toByte() } else null
        if (length > MAX_INBOUND_FRAME_BYTES) {
          close()
          break
        }
        val payload = ByteArray(length.toInt())
        var offset = 0
        while (offset < payload.size) {
          val read = input.read(payload, offset, payload.size - offset)
          if (read < 0) {
            close()
            return
          }
          offset += read
        }
        if (mask != null) {
          for (index in payload.indices) {
            payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
          }
        }
        when (opcode) {
          OPCODE_TEXT -> onText(String(payload, Charsets.UTF_8))
          OPCODE_CLOSE -> {
            close()
            break
          }
          OPCODE_PING -> sendFrame(opcode = OPCODE_PONG, payload = payload)
        }
      }
    } finally {
      close()
    }
  }

  fun close(): Boolean {
    if (!open.getAndSet(false)) {
      return false
    }
    runCatching { socket.close() }
    onClose()
    return true
  }

  private fun sendFrame(
    opcode: Int,
    payload: ByteArray,
    requireBinaryAllowed: Boolean = false,
    allowBinaryAfterSend: Boolean = false,
    canSend: () -> Boolean = { true },
    closeOnFailure: Boolean = true,
    payloadAtWrite: (() -> ByteArray)? = null
  ): Boolean {
    if (!open.get()) {
      return false
    }
    return synchronized(writeLock) {
      if (!open.get() || (requireBinaryAllowed && !binaryFramesAllowed) || !canSend()) {
        return@synchronized false
      }
      runCatching {
        val currentPayload = payloadAtWrite?.invoke() ?: payload
        output.write(0x80 or opcode)
        when {
          currentPayload.size < 126 -> output.write(currentPayload.size)
          currentPayload.size <= 65535 -> {
            output.write(126)
            output.write((currentPayload.size shr 8) and 0xFF)
            output.write(currentPayload.size and 0xFF)
          }
          else -> {
            output.write(127)
            val length = currentPayload.size.toLong()
            for (shift in 56 downTo 0 step 8) {
              output.write(((length shr shift) and 0xFF).toInt())
            }
          }
        }
        output.write(currentPayload)
        output.flush()
      }.fold(
        onSuccess = {
          if (allowBinaryAfterSend) {
            binaryFramesAllowed = true
          }
          true
        },
        onFailure = {
          if (closeOnFailure) close()
          false
        }
      )
    }
  }

  private fun BufferedInputStream.readRequired(): Int {
    val value = read()
    if (value < 0) {
      error("websocket stream closed")
    }
    return value
  }

  companion object {
    private const val OPCODE_TEXT = 0x1
    private const val OPCODE_BINARY = 0x2
    private const val OPCODE_CLOSE = 0x8
    private const val OPCODE_PING = 0x9
    private const val OPCODE_PONG = 0xA
    private const val MAX_INBOUND_FRAME_BYTES = 4096L

    fun acceptKey(clientKey: String): String {
      val digest = MessageDigest.getInstance("SHA-1").digest(
        (clientKey.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)
      )
      return Base64.encodeToString(digest, Base64.NO_WRAP)
    }
  }
}
