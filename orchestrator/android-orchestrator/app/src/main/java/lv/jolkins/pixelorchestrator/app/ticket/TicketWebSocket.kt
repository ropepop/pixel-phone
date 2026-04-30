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
  private val onClose: () -> Unit
) {
  private val open = AtomicBoolean(true)
  private val writeLock = Any()

  fun sendText(value: String) {
    sendFrame(opcode = OPCODE_TEXT, payload = value.toByteArray(Charsets.UTF_8))
  }

  fun sendBinary(payload: ByteArray) {
    sendFrame(opcode = OPCODE_BINARY, payload = payload)
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

  fun close() {
    if (!open.getAndSet(false)) {
      return
    }
    runCatching { socket.close() }
    onClose()
  }

  private fun sendFrame(opcode: Int, payload: ByteArray) {
    if (!open.get()) {
      return
    }
    synchronized(writeLock) {
      runCatching {
        output.write(0x80 or opcode)
        when {
          payload.size < 126 -> output.write(payload.size)
          payload.size <= 65535 -> {
            output.write(126)
            output.write((payload.size shr 8) and 0xFF)
            output.write(payload.size and 0xFF)
          }
          else -> {
            output.write(127)
            val length = payload.size.toLong()
            for (shift in 56 downTo 0 step 8) {
              output.write(((length shr shift) and 0xFF).toInt())
            }
          }
        }
        output.write(payload)
        output.flush()
      }.onFailure {
        close()
      }
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
