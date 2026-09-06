package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketSpacetimeResultPublisherTest {
  @Test fun notificationDeliversRetainedResultsWhileMediaWorkIsBlockedAndThenSleeps() = runTest {
    val mediaGate = CompletableDeferred<Unit>()
    val media = backgroundScope.launch { mediaGate.await() }
    val notifications = Channel<Unit>(Channel.CONFLATED)
    val retained = ArrayDeque(listOf("progress", "terminal"))
    val delivered = mutableListOf<String>()
    var calls = 0
    backgroundScope.launch {
      TicketSpacetimeResultPublisher(notifications, publishNext = {
        calls++
        retained.removeFirstOrNull()?.let { delivered.add(it); true } ?: false
      }).run()
    }
    runCurrent()
    assertEquals(listOf("progress", "terminal"), delivered)
    assertFalse(media.isCompleted)
    val idleCalls = calls
    advanceTimeBy(10_000)
    runCurrent()
    assertEquals(idleCalls, calls)
    retained.add("cleanup")
    notifications.trySend(Unit)
    runCurrent()
    assertEquals(listOf("progress", "terminal", "cleanup"), delivered)
  }

  @Test fun uncertainDeliveryRetriesRetainedOutcomeWithoutLosingAnArrival() = runTest {
    val notifications = Channel<Unit>(Channel.CONFLATED)
    val retained = ArrayDeque(listOf("terminal"))
    val attempts = mutableListOf<String>()
    var fail = true
    var failures = 0
    backgroundScope.launch {
      TicketSpacetimeResultPublisher(notifications, publishNext = publish@ {
        val next = retained.firstOrNull() ?: return@publish false
        attempts.add(next)
        if (fail) { fail = false; error("delivery_uncertain") }
        retained.removeFirst()
        true
      }, onFailure = { failures++ }).run()
    }
    runCurrent()
    assertEquals(listOf("terminal"), attempts)
    assertEquals(1, failures)
    retained.add("cleanup")
    repeat(10) { notifications.trySend(Unit) }
    advanceTimeBy(250)
    runCurrent()
    assertEquals(listOf("terminal", "terminal", "cleanup"), attempts)
    assertTrue(retained.isEmpty())
  }

  @Test fun replacingConnectionRestartsDeliveryFromRetainedOutcome() = runTest {
    val notifications = Channel<Unit>(Channel.CONFLATED)
    val blockedDelivery = CompletableDeferred<Unit>()
    var acknowledged = false
    var attempts = 0
    val first = backgroundScope.launch {
      TicketSpacetimeResultPublisher(notifications, publishNext = {
        attempts++
        blockedDelivery.await()
        acknowledged = true
        false
      }).run()
    }
    runCurrent()
    first.cancel()
    first.join()
    assertFalse(acknowledged)
    backgroundScope.launch {
      TicketSpacetimeResultPublisher(notifications, publishNext = {
        if (acknowledged) false else { attempts++; acknowledged = true; true }
      }).run()
    }
    runCurrent()
    assertEquals(2, attempts)
    assertTrue(acknowledged)
  }
}
