package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Test

class TicketCaptureProcessCountsTest {
  @Test fun shutdownDoesNotWaitForAFrameFromAParkedEncoder() {
    val writerStopped = java.util.concurrent.CountDownLatch(1)
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    var killed = false
    var inputClosed = false
    val process = object : Process() {
      override fun getOutputStream() = java.io.ByteArrayOutputStream()
      override fun getInputStream() = object : java.io.ByteArrayInputStream(byteArrayOf()) {
        override fun close() {
          // Model the pipe reader holding its lock until the child writer exits.
          writerStopped.await(5, java.util.concurrent.TimeUnit.SECONDS)
          inputClosed = true
        }
      }
      override fun getErrorStream() = java.io.ByteArrayInputStream(byteArrayOf())
      override fun waitFor() = 0
      override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
      override fun exitValue() = 0
      override fun destroy() { killed = true }
      override fun destroyForcibly(): Process { killed = true; return this }
    }
    try {
      executor.submit { destroyTicketCaptureProcessAndWait(process) {
        org.junit.Assert.assertTrue(killed)
        writerStopped.countDown()
      } }.get(1, java.util.concurrent.TimeUnit.SECONDS)
      org.junit.Assert.assertTrue(inputClosed)
    } finally {
      writerStopped.countDown()
      executor.shutdownNow()
    }
  }

  @Test fun missingOrFailedProcessReadbackIsNeverZeroProof() {
    assertEquals(HardwareProcessCounts(-1, -1), parseTicketHardwareProcessCounts(""))
    assertEquals(HardwareProcessCounts(-1, -1), parseTicketHardwareProcessCounts("permission denied"))
    assertEquals(HardwareProcessCounts(0, -1), parseTicketHardwareProcessCounts("encoder=0"))
    assertEquals(HardwareProcessCounts(0, 0), parseTicketHardwareProcessCounts("encoder=0 wrappers=0\n"))
    assertEquals(HardwareProcessCounts(1, 2), parseTicketHardwareProcessCounts("encoder=1 wrappers=2\n"))
  }
}
