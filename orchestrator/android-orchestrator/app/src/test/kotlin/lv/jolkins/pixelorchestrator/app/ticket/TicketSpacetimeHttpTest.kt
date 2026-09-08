package lv.jolkins.pixelorchestrator.app.ticket

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class TicketSpacetimeHttpTest {
  @Test fun healthyRequestsReuseTheirConnectionWithoutReplayingAmbiguousWrites() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("one"))
      server.enqueue(MockResponse().setBody("two"))
      server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
      server.start()
      val client = TicketSpacetimeHttpClient(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/"), "test-token", 1_000)
      assertEquals("one", client.post("/call", "application/json", "[]"))
      assertEquals("two", client.post("/call", "application/json", "[]"))
      assertEquals(0, server.takeRequest().sequenceNumber)
      assertEquals(1, server.takeRequest().sequenceNumber)
      assertTrue(runCatching { client.post("/call", "application/json", "[]") }.isFailure)
      assertEquals(3, server.requestCount)
    }
  }

  @Test fun stalledResponseBodyIsCancelledAndTheNextRequestStillWorks() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("slow").setBodyDelay(2, TimeUnit.SECONDS))
      server.enqueue(MockResponse().setBody("healthy"))
      server.start()
      val client = TicketSpacetimeHttpClient(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/"), "test-token", 200)
      val started = System.nanoTime()
      assertTrue(runCatching { client.post("/call", "application/json", "[]") }.isFailure)
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
      assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
      assertEquals("healthy", client.post("/call", "application/json", "[]"))
      assertEquals(2, server.requestCount)
    }
  }

  private fun server(handler: (RecordedRequest) -> MockResponse, check: (String) -> Unit) {
    MockWebServer().use { server ->
      server.dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = handler(request)
      }
      server.start()
      check(server.url("/").newBuilder().host("127.0.0.1").build().toString().removeSuffix("/"))
    }
  }

  @Test
  fun commandAndLogRequestsKeepTheirOwnCredentialsAndExactBodies() {
    val requests = AtomicInteger()
    server({ exchange ->
      val log = exchange.path == "/log"
      assertEquals("POST", exchange.method)
      assertEquals("Bearer ${if (log) "log-token" else "command-token"}",
        exchange.getHeader("Authorization"))
      assertEquals(if (log) "application/json" else "text/plain; charset=utf-8",
        exchange.getHeader("Content-Type"))
      assertEquals(if (log) "{}" else "biļete", exchange.body.readUtf8())
      requests.incrementAndGet()
      MockResponse().setResponseCode(200).setBody("recorded")
    }) { host -> runBlocking {
      assertEquals("recorded", TicketSpacetimeHttpClient(host, "command-token", 1_000)
        .post("/sql", "text/plain; charset=utf-8", "biļete"))
      assertEquals("", TicketSpacetimeHttpClient(host, "log-token", 1_000)
        .post("/log", "application/json", "{}", readResponse = false))
      assertEquals(2, requests.get())
    } }
  }

  @Test
  fun redirectsAndErrorBodiesCannotChangeTheTargetOrExposeResponseText() {
    val redirected = AtomicInteger()
    server({ exchange ->
      when (exchange.path) {
        "/redirect" -> MockResponse().setResponseCode(307).addHeader("Location", "/unexpected")
        "/missing" -> MockResponse().setResponseCode(400).setBody("unknown reducer; private-response-value")
        else -> { redirected.incrementAndGet(); MockResponse().setBody("unexpected") }
      }
    }) { host -> runBlocking {
      val client = TicketSpacetimeHttpClient(host, "test-token", 1_000)
      for ((path, code, unavailable) in listOf(Triple("/redirect", 307, false), Triple("/missing", 400, true))) {
        val error = runCatching { client.post(path, "application/json", "[]") }.exceptionOrNull()
        assertTrue(error is TicketSpacetimeCallException)
        error as TicketSpacetimeCallException
        assertEquals(code, error.httpCode)
        assertEquals(unavailable, error.reducerUnavailable)
        assertFalse(error.toString().contains("private-response-value"))
      }
      assertEquals(0, redirected.get())
    } }
  }

  @Test
  fun stalledOptionalLogDoesNotHoldTheCommandLane() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    server({ exchange ->
      if (exchange.path == "/log") {
        entered.countDown()
        release.await(2, TimeUnit.SECONDS)
      }
      MockResponse().setBody("settled")
    }) { host -> runBlocking {
      val logClient = TicketSpacetimeHttpClient(host, "log-token", 100)
      val queue = TicketOperationalLogQueue(this, { logClient.post("/log", "application/json", "{}", false) })
      try {
        assertTrue(queue.enqueue(TicketOperationalLogEvent("id", "warn", "failed", "", "{}")))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals("settled", TicketSpacetimeHttpClient(host, "command-token", 500)
          .post("/command", "application/json", "[]"))
      } finally { release.countDown(); queue.stop() }
    } }
  }

  @Test
  fun conciseFailuresPassPrivacyChecksWithoutRuntimeText() {
    for (name in listOf("ticket_action_terminal_journal_unproved", "keyboard_clamp_restore_failed",
      "secure_window_capture_bypass_startup_reconcile_failed", "http_request_failed")) {
      assertEquals(name, TicketTracePrivacy.eventName(name))
    }
    assertNull(TicketTracePrivacy.eventName("ticket_action_private_success"))
    assertNull(TicketTracePrivacy.eventName("keyboard_clamp_password=private_failed"))
    assertEquals(mapOf("ok" to "false"), TicketTracePrivacy.allowlistedFields("ok=false password=private stdout=private"))
  }
}
