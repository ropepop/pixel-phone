package lv.jolkins.pixelorchestrator.app.ticket

import java.nio.ByteBuffer
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TicketCodecOutputCopyTest {
  @Test fun codecBufferIsReleasedBeforeAnyPacingOrFailedDelivery() {
    var released = false
    val buffer = ByteBuffer.wrap(byteArrayOf(9, 1, 2, 3, 8))
    val owned = TicketCodecOutputCopy.copyAndRelease({ buffer }, 1, 3) {
      released = true
      buffer.clear()
      while (buffer.hasRemaining()) buffer.put(0)
    }
    assertTrue(released)
    assertArrayEquals(byteArrayOf(1, 2, 3), owned)
    assertThrows(IOException::class.java) {
      check(released)
      throw IOException("pipe closed")
    }
  }

  @Test fun lookupAndCopyFailuresStillReturnTheCodecBuffer() {
    for (lookupFails in listOf(false, true)) {
      var releases = 0
      assertThrows(RuntimeException::class.java) {
        TicketCodecOutputCopy.copyAndRelease({
          if (lookupFails) throw IllegalStateException("unavailable")
          ByteBuffer.allocate(1)
        }, 0, 2) { releases++ }
      }
      assertEquals(1, releases)
    }
  }

  @Test fun emptyEndOfStreamStillReleasesItsBuffer() {
    var releases = 0
    assertEquals(0, TicketCodecOutputCopy.copyAndRelease({ null }, 0, 0) { releases++ }.size)
    assertEquals(1, releases)
  }
}
