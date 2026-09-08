package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketVideoDeliveryWriterTest {
  private fun frame(sequence: Long, epoch: Long = 1L) = TicketVideoDeliveryFrame(
    byteArrayOf(sequence.toByte()), true, epoch, sequence
  )

  @Test
  fun configurationPrecedesPicturesAndOnlyNewestWaitsBehindBlockedWrite() = runTest {
    val unblock = CompletableDeferred<Unit>()
    val sent = mutableListOf<String>()
    val writer = TicketVideoDeliveryWriter(
      backgroundScope, 100, 1_000L, { testScheduler.currentTime },
      sendConfig = { value, current -> current().also { if (it) sent += value } },
      sendFrame = { bytes, current ->
        if (bytes[0].toInt() == 1) unblock.await()
        current().also { if (it) sent += bytes[0].toString() }
      },
      onConfigured = {}, onFailure = { error(it) }, requestRefresh = { error("invalid frame") }
    )
    writer.configure(1L, "config")
    writer.offer(frame(1))
    runCurrent()
    assertEquals(listOf("config"), sent)
    writer.offer(frame(2))
    writer.offer(frame(3))
    writer.offer(frame(2))
    unblock.complete(Unit)
    runCurrent()
    assertEquals(listOf("config", "1", "3"), sent)
    writer.close()
  }

  @Test
  fun supersededConfigurationCannotAuthorizeOldPictures() = runTest {
    val unblock = CompletableDeferred<Unit>()
    val sent = mutableListOf<String>()
    var configured = 0
    val writer = TicketVideoDeliveryWriter(
      backgroundScope, 100, 1_000L, { testScheduler.currentTime },
      sendConfig = { value, current ->
        if (value == "old") unblock.await()
        current().also { if (it) sent += value }
      },
      sendFrame = { bytes, current -> current().also { if (it) sent += bytes[0].toString() } },
      onConfigured = { configured++ }, onFailure = { error(it) }, requestRefresh = { error("invalid frame") }
    )
    writer.configure(1L, "old")
    runCurrent()
    writer.offer(frame(1))
    writer.configure(2L)
    writer.configure(1L, "obsolete")
    writer.offer(frame(2, 1L))
    writer.configure(2L, "new")
    writer.offer(frame(3, 2L))
    unblock.complete(Unit)
    runCurrent()
    assertEquals(listOf("new", "3"), sent)
    assertEquals(1, configured)
    writer.close()
  }

  @Test
  fun deadlineClosesStalledOldWriteEvenAfterConfigurationChanges() = runTest {
    val unblock = CompletableDeferred<Unit>()
    val failures = mutableListOf<String>()
    var configured = 0
    var attempts = 0
    val writer = TicketVideoDeliveryWriter(
      backgroundScope, 100, 1_000L, { testScheduler.currentTime },
      sendConfig = { _, _ ->
        attempts++
        withContext(NonCancellable) { unblock.await() }
        true
      },
      sendFrame = { _, _ -> error("no picture may be sent") },
      onConfigured = { configured++ }, onFailure = { failures += it }, requestRefresh = {}
    )
    writer.configure(1L, "old")
    runCurrent()
    writer.configure(2L, "new")
    advanceTimeBy(1_000L)
    runCurrent()
    assertEquals(listOf("write_timeout"), failures)
    unblock.complete(Unit)
    runCurrent()
    writer.offer(frame(1, 2L))
    runCurrent()
    assertEquals(0, configured)
    assertEquals(1, attempts)
  }

  @Test
  fun invalidPicturesRequestRefreshAndCurrentWriteFailureIsTerminal() = runTest {
    var refreshes = 0
    val failures = mutableListOf<String>()
    val writer = TicketVideoDeliveryWriter(
      backgroundScope, 1, 1_000L, { testScheduler.currentTime },
      sendConfig = { _, current -> current() }, sendFrame = { _, _ -> false },
      onConfigured = {}, onFailure = { failures += it }, requestRefresh = { refreshes++ }
    )
    writer.configure(1L, "config")
    writer.offer(frame(1).copy(keyFrame = false))
    writer.offer(frame(1).copy(bytes = byteArrayOf(1, 2)))
    writer.offer(frame(1).copy(bytes = byteArrayOf()))
    writer.offer(frame(1, 2L))
    writer.offer(frame(1))
    runCurrent()
    assertEquals(3, refreshes)
    assertEquals(listOf("write_failed"), failures)
    writer.offer(frame(2))
    runCurrent()
    assertEquals(1, failures.size)
  }
}
