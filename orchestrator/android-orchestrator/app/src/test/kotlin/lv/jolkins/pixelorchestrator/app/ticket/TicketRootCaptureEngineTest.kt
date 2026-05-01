package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.io.IOException
import java.io.InputStream

class TicketRootCaptureEngineTest {
  @Test
  fun stderrReaderReturnsTrimmedText() {
    val text = readRootCaptureStderr("  screenrecord warning  \n".byteInputStream())

    assertEquals("screenrecord warning", text)
  }

  @Test
  fun stderrReaderIgnoresClosedStream() {
    val text = readRootCaptureStderr(
      object : InputStream() {
        override fun read(): Int {
          throw IOException("Stream closed")
        }
      }
    )

    assertNull(text)
  }

  @Test
  fun expectedCaptureCloseMatchesClosedPipeErrorsOnly() {
    assertTrue(isExpectedRootCaptureClose(IOException("Stream closed")))
    assertTrue(isExpectedRootCaptureClose(InterruptedIOException("read interrupted by close() on another thread")))
    assertFalse(isExpectedRootCaptureClose(IOException("screenrecord encoder failed")))
    assertFalse(isExpectedRootCaptureClose(IllegalStateException("Stream closed")))
  }
}
