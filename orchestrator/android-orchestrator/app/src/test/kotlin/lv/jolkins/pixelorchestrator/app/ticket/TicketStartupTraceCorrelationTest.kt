package lv.jolkins.pixelorchestrator.app.ticket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketStartupTraceCorrelationTest {
  @Test
  fun blankOrDelayedOlderCommandCannotReplaceActiveSocketTrace() {
    val trace = TicketStartupTraceCorrelation()
    assertTrue(trace.bindVideoSocket("startup_bbbbbbbb", generation = 2L))

    assertFalse(trace.bindCommand(""))
    assertEquals("startup_bbbbbbbb", trace.current())
    assertFalse(trace.bindCommand("startup_aaaaaaaa"))
    assertEquals("startup_bbbbbbbb", trace.current())
    assertTrue(trace.bindCommand("startup_bbbbbbbb"))
    assertEquals("startup_bbbbbbbb", trace.current())
  }

  @Test
  fun newerSocketReplacesEarlierCommandAndOldSocketCloseCannotReleaseIt() {
    val trace = TicketStartupTraceCorrelation()
    assertTrue(trace.bindCommand("startup_aaaaaaaa"))
    assertTrue(trace.bindVideoSocket("startup_bbbbbbbb", generation = 2L))

    trace.releaseVideoSocket(generation = 1L)
    assertFalse(trace.bindCommand("startup_aaaaaaaa"))
    assertEquals("startup_bbbbbbbb", trace.current())

    trace.releaseVideoSocket(generation = 2L)
    assertTrue(trace.bindCommand("startup_cccccccc"))
    assertEquals("startup_cccccccc", trace.current())
  }

  @Test
  fun blankSocketHeaderDoesNotEraseCommandOwnedCorrelation() {
    val trace = TicketStartupTraceCorrelation()
    assertTrue(trace.bindCommand("startup_aaaaaaaa"))

    assertTrue(trace.bindVideoSocket("", generation = 3L))
    assertEquals("startup_aaaaaaaa", trace.current())
    assertTrue(trace.bindCommand("startup_bbbbbbbb"))
    assertEquals("startup_bbbbbbbb", trace.current())
  }

  @Test
  fun newerSocketGenerationCannotBeReleasedOrReplacedByLateOlderWork() {
    val trace = TicketStartupTraceCorrelation()
    assertTrue(trace.bindVideoSocket("startup_bbbbbbbb", generation = 2L))
    assertTrue(trace.bindVideoSocket("startup_cccccccc", generation = 3L))

    trace.releaseVideoSocket(generation = 2L)
    assertFalse(trace.bindCommand("startup_bbbbbbbb"))
    assertFalse(trace.bindVideoSocket("startup_bbbbbbbb", generation = 2L))
    assertEquals("startup_cccccccc", trace.current())
  }

  @Test
  fun lateSupersededSocketMustRevalidateGenerationAndCorrelationAfterSuspension() {
    val trace = TicketStartupTraceCorrelation()
    val socketB = "startup_bbbbbbbb"
    val socketC = "startup_cccccccc"

    assertTrue(trace.bindVideoSocket(socketB, generation = 2L))
    assertTrue(trace.isCurrentVideoSocket(socketB, generation = 2L))

    assertTrue(trace.bindVideoSocket(socketC, generation = 3L))
    assertTrue(trace.isCurrentVideoSocket(socketC, generation = 3L))
    assertFalse(trace.isCurrentVideoSocket(socketB, generation = 2L))
    assertFalse(trace.isCurrentVideoSocket(socketC, generation = 2L))

    trace.releaseVideoSocket(generation = 2L)
    assertTrue(trace.isCurrentVideoSocket(socketC, generation = 3L))
    assertEquals(socketC, trace.current())
  }

  @Test
  fun oldRegisterThenNewReplaceThenOldStartCannotMutateSessionEngineOrEpoch() {
    val trace = TicketStartupTraceCorrelation()
    val socketB = "startup_bbbbbbbb"
    val socketC = "startup_cccccccc"
    var sessionStarts = 0
    var engineStarts = 0
    var streamEpoch = 41L

    assertTrue(trace.bindVideoSocket(socketB, generation = 2L))
    assertTrue(trace.bindVideoSocket(socketC, generation = 3L))

    val result = trace.resolveVideoSocketStart(
      value = socketB,
      generation = 2L,
      superseded = { "superseded" },
      current = {
        sessionStarts += 1
        engineStarts += 1
        streamEpoch += 1L
        "started"
      }
    )

    assertEquals("superseded", result)
    assertEquals(0, sessionStarts)
    assertEquals(0, engineStarts)
    assertEquals(41L, streamEpoch)
    assertTrue(trace.isCurrentVideoSocket(socketC, generation = 3L))
  }
}
