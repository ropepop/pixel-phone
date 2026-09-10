package lv.jolkins.pixelorchestrator.app.ticket

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class TicketCaptureResultTest {
  @Test fun successfulWrapTransfersOwnershipWithoutReleasingThePicture() {
    val releases = AtomicInteger()
    val owner = TicketCaptureResult<Any> { releases.incrementAndGet() }
    val raw = Any()
    owner.succeed(raw)
    assertSame(raw, owner.awaitAndWrap(0, TimeUnit.MILLISECONDS) { it })
    owner.close()
    assertEquals(0, releases.get())
  }

  @Test fun timeoutReleasesLateSuccessExactlyOnce() {
    val releases = AtomicInteger()
    val owner = TicketCaptureResult<Any> { releases.incrementAndGet() }
    assertThrows(TimeoutException::class.java) { owner.await(0, TimeUnit.MILLISECONDS) }
    owner.succeed(Any())
    owner.close()
    assertEquals(1, releases.get())
  }

  @Test fun callbackFailureWinsAndLaterSuccessCannotLeak() {
    val releases = AtomicInteger()
    val owner = TicketCaptureResult<Any> { releases.incrementAndGet() }
    val failure = IllegalStateException("test")
    owner.fail(failure)
    owner.succeed(Any())
    assertSame(failure, assertThrows(ExecutionException::class.java) {
      owner.await(0, TimeUnit.MILLISECONDS)
    }.cause)
    assertEquals(1, releases.get())
  }

  @Test fun throwingOrNullWrapperReleasesRawResult() {
    for (throws in listOf(false, true)) {
      val releases = AtomicInteger()
      val owner = TicketCaptureResult<Any> { releases.incrementAndGet() }
      owner.succeed(Any())
      if (throws) assertThrows(IllegalStateException::class.java) {
        owner.awaitAndWrap<Any>(0, TimeUnit.MILLISECONDS) { throw IllegalStateException("wrapping failed") }
      } else assertNull(owner.awaitAndWrap<Any>(0, TimeUnit.MILLISECONDS) { null })
      owner.close()
      assertEquals(1, releases.get())
    }
  }

  @Test fun interruptedWaitAbandonsTheCallback() {
    val releases = AtomicInteger()
    val owner = TicketCaptureResult<Any> { releases.incrementAndGet() }
    val started = CountDownLatch(1)
    val outcome = AtomicReference<Throwable>()
    val waiter = Thread {
      started.countDown()
      try { owner.await(10, TimeUnit.SECONDS) } catch (t: Throwable) { outcome.set(t) }
    }
    waiter.start()
    assertTrue(started.await(1, TimeUnit.SECONDS))
    waiter.interrupt()
    waiter.join(1000)
    assertFalse(waiter.isAlive)
    assertTrue(outcome.get() is InterruptedException)
    owner.succeed(Any())
    assertEquals(1, releases.get())
  }

  @Test fun simultaneousTimeoutAndSuccessEitherTransferOrReleaseButNeverBoth() {
    repeat(200) {
      val releases = AtomicInteger()
      val owner = TicketCaptureResult<Any> { releases.incrementAndGet() }
      val gate = CountDownLatch(1)
      val callback = Thread { gate.await(); owner.succeed(Any()) }
      callback.start()
      gate.countDown()
      var transferred = false
      try { transferred = owner.await(0, TimeUnit.MILLISECONDS) != null }
      catch (_: TimeoutException) { }
      callback.join(1000)
      assertFalse(callback.isAlive)
      owner.close()
      assertEquals(if (transferred) 0 else 1, releases.get())
    }
  }

  @Test fun abandonedBeforeWaitReleasesAlreadyDeliveredResult() {
    val releases = AtomicInteger()
    val owner = TicketCaptureResult<Any> { releases.incrementAndGet() }
    owner.succeed(Any())
    owner.close()
    owner.close()
    assertThrows(IllegalStateException::class.java) { owner.await(0, TimeUnit.MILLISECONDS) }
    assertEquals(1, releases.get())
  }
}
