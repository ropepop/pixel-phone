package lv.jolkins.pixelorchestrator.app.ticket

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketWebSocketTest {
  @Test
  fun videoBinaryFramesRemainBlockedUntilConfigIsFlushed() {
    val written = ByteArrayOutputStream()
    val client = TicketWebSocket(
      socket = Socket(),
      input = BufferedInputStream(ByteArrayInputStream(byteArrayOf())),
      output = BufferedOutputStream(written),
      onText = {},
      onClose = {},
      binaryFramesInitiallyAllowed = false
    )

    assertFalse(client.binaryFramesAllowed())
    assertFalse(client.sendBinary(byteArrayOf(0x11)))
    assertArrayEquals(byteArrayOf(), written.toByteArray())

    client.sendText("status")
    assertFalse(client.binaryFramesAllowed())
    assertFalse(client.sendBinary(byteArrayOf(0x22)))

    assertTrue(client.sendConfigAndAllowBinary("config"))
    assertTrue(client.binaryFramesAllowed())
    assertTrue(client.sendBinary(byteArrayOf(0x33)))

    assertArrayEquals(
      serverFrame(0x1, "status".toByteArray()) +
        serverFrame(0x1, "config".toByteArray()) +
        serverFrame(0x2, byteArrayOf(0x33)),
      written.toByteArray()
    )
  }

  @Test
  fun concurrentBinaryWriteWaitsBehindTheConfigFlush() {
    val configWriteStarted = CountDownLatch(1)
    val releaseConfigWrite = CountDownLatch(1)
    val binaryCallStarted = CountDownLatch(1)
    val written = BlockingOutputStream(configWriteStarted, releaseConfigWrite)
    val client = TicketWebSocket(
      socket = Socket(),
      input = BufferedInputStream(ByteArrayInputStream(byteArrayOf())),
      output = BufferedOutputStream(written),
      onText = {},
      onClose = {},
      binaryFramesInitiallyAllowed = false
    )
    val executor = Executors.newFixedThreadPool(2)
    try {
      val config = executor.submit<Boolean> {
        client.sendConfigAndAllowBinary("config")
      }
      assertTrue(configWriteStarted.await(2, TimeUnit.SECONDS))
      val binary = executor.submit<Boolean> {
        binaryCallStarted.countDown()
        client.sendBinary(byteArrayOf(0x44))
      }
      assertTrue(binaryCallStarted.await(2, TimeUnit.SECONDS))

      releaseConfigWrite.countDown()

      assertTrue(config.get(2, TimeUnit.SECONDS))
      assertTrue(binary.get(2, TimeUnit.SECONDS))
      assertArrayEquals(
        serverFrame(0x1, "config".toByteArray()) + serverFrame(0x2, byteArrayOf(0x44)),
        written.toByteArray()
      )
    } finally {
      releaseConfigWrite.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun staleBinaryIsRejectedAfterWaitingBehindAConfigurationWrite() {
    val configWriteStarted = CountDownLatch(1)
    val releaseConfigWrite = CountDownLatch(1)
    val binaryCallStarted = CountDownLatch(1)
    val currentGeneration = AtomicBoolean(true)
    val written = BlockingOutputStream(configWriteStarted, releaseConfigWrite)
    val client = TicketWebSocket(
      socket = Socket(),
      input = BufferedInputStream(ByteArrayInputStream(byteArrayOf())),
      output = BufferedOutputStream(written),
      onText = {},
      onClose = {},
      binaryFramesInitiallyAllowed = false
    )
    val executor = Executors.newFixedThreadPool(2)
    try {
      val config = executor.submit<Boolean> {
        client.sendConfigAndAllowBinary("config")
      }
      assertTrue(configWriteStarted.await(2, TimeUnit.SECONDS))
      val binary = executor.submit<Boolean> {
        binaryCallStarted.countDown()
        client.sendBinaryIf(byteArrayOf(0x55)) { currentGeneration.get() }
      }
      assertTrue(binaryCallStarted.await(2, TimeUnit.SECONDS))
      currentGeneration.set(false)
      releaseConfigWrite.countDown()

      assertTrue(config.get(2, TimeUnit.SECONDS))
      assertFalse(binary.get(2, TimeUnit.SECONDS))
      assertArrayEquals(serverFrame(0x1, "config".toByteArray()), written.toByteArray())
    } finally {
      releaseConfigWrite.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun staleConfigurationCannotFlushAfterANewerGenerationReplacesIt() {
    val blockingWriteStarted = CountDownLatch(1)
    val releaseBlockingWrite = CountDownLatch(1)
    val oldConfigCallStarted = CountDownLatch(1)
    val newConfigCallStarted = CountDownLatch(1)
    val oldGenerationCurrent = AtomicBoolean(true)
    val written = BlockingOutputStream(blockingWriteStarted, releaseBlockingWrite)
    val client = TicketWebSocket(
      socket = Socket(),
      input = BufferedInputStream(ByteArrayInputStream(byteArrayOf())),
      output = BufferedOutputStream(written),
      onText = {},
      onClose = {},
      binaryFramesInitiallyAllowed = false
    )
    val executor = Executors.newFixedThreadPool(3)
    try {
      val blocker = executor.submit<Boolean> {
        client.sendText("blocking")
        true
      }
      assertTrue(blockingWriteStarted.await(2, TimeUnit.SECONDS))
      val oldConfig = executor.submit<Boolean> {
        oldConfigCallStarted.countDown()
        client.sendConfigAndAllowBinaryIf("old-config") { oldGenerationCurrent.get() }
      }
      assertTrue(oldConfigCallStarted.await(2, TimeUnit.SECONDS))
      val newConfig = executor.submit<Boolean> {
        newConfigCallStarted.countDown()
        client.sendConfigAndAllowBinaryIf("new-config") { true }
      }
      assertTrue(newConfigCallStarted.await(2, TimeUnit.SECONDS))

      oldGenerationCurrent.set(false)
      releaseBlockingWrite.countDown()

      assertTrue(blocker.get(2, TimeUnit.SECONDS))
      assertFalse(oldConfig.get(2, TimeUnit.SECONDS))
      assertTrue(newConfig.get(2, TimeUnit.SECONDS))
      assertArrayEquals(
        serverFrame(0x1, "blocking".toByteArray()) +
          serverFrame(0x1, "new-config".toByteArray()),
        written.toByteArray()
      )
    } finally {
      releaseBlockingWrite.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun guardedWriterReportsFailureBeforeItsDeliveryOwnerClosesTheSocket() {
    var closeCalls = 0
    val client = TicketWebSocket(
      socket = Socket(),
      input = BufferedInputStream(ByteArrayInputStream(byteArrayOf())),
      output = BufferedOutputStream(FailingOutputStream()),
      onText = {},
      onClose = { closeCalls += 1 },
      binaryFramesInitiallyAllowed = true
    )

    assertFalse(client.sendBinaryIf(byteArrayOf(0x66)) { true })
    assertEquals(0, closeCalls)

    client.close()
    assertEquals(1, closeCalls)
  }

  private fun serverFrame(opcode: Int, payload: ByteArray): ByteArray {
    require(payload.size < 126)
    return byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte()) + payload
  }

  private class BlockingOutputStream(
    private val writeStarted: CountDownLatch,
    private val releaseWrite: CountDownLatch
  ) : ByteArrayOutputStream() {
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      writeStarted.countDown()
      check(releaseWrite.await(2, TimeUnit.SECONDS))
      super.write(buffer, offset, length)
    }
  }

  private class FailingOutputStream : ByteArrayOutputStream() {
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
      error("write failed")
    }
  }
}
