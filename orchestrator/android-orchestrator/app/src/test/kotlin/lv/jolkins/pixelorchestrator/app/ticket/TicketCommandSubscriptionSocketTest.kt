package lv.jolkins.pixelorchestrator.app.ticket

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

class TicketCommandSubscriptionSocketTest {
  private val protocol = "v1.json.spacetimedb"
  private fun row(id: String, ticket: String = "ticket") = JsonArray(listOf(id, ticket, "pixel", "start", "pending", "rev", "owner", "{}", "date", "date", "2099-01-01T00:00:00Z").map(::JsonPrimitive)).toString()
  private fun update(id: String?, initial: Boolean = false, request: Int = 0, delete: String? = null, ticket: String = "ticket"): String {
    val table = buildJsonObject { put("tables", buildJsonArray { add(buildJsonObject {
      put("table_name", "ticketremote_service_stream_command"); put("updates", buildJsonArray { add(buildJsonObject {
        put("inserts", JsonArray(listOfNotNull(id?.let { JsonPrimitive(row(it, ticket)) })))
        put("deletes", JsonArray(listOfNotNull(delete?.let { JsonPrimitive(row(it)) })))
      }) })
    }) }) }
    return buildJsonObject { if (initial) put("InitialSubscription", buildJsonObject { put("request_id", request); put("database_update", table) })
      else put("TransactionUpdateLight", buildJsonObject { put("update", table) }) }.toString()
  }
  private suspend fun awaitState(check: () -> Boolean) { withTimeout(4000) { while (!check()) delay(5) } }
  @Test fun realSocketReplacesAuthorityAndRetainsRecordedOutcomeUntilDeletion() = runBlocking {
    MockWebServer().use { server ->
      val sockets = LinkedBlockingQueue<WebSocket>()
      val subscriptionRequests = LinkedBlockingQueue<String>()
      repeat(2) { server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", protocol).withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) { subscriptionRequests.add(text) }
      })) }
      server.start()
      val inbox = TicketCommandInbox()
      val results = TicketCommandResults()
      val client = OkHttpClient()
      val subscription = TicketSpacetimeCommandSubscription(this, TicketSpacetimeCommandSubscriptionConfig(server.url("/").toString(), "synthetic-db", "synthetic-token", "ticket", "pixel"), Json, inbox, httpClient = client, reconnectDelayMillis = 10, maxReconnectDelayMillis = 10, reconnectJitter = { 1.0 })
      try {
        subscription.start()
        val first = sockets.poll(4, TimeUnit.SECONDS)!!
        assertTrue(subscriptionRequests.poll(4, TimeUnit.SECONDS)!!.contains("\"request_id\":0"))
        assertNull(inbox.snapshot())
        first.send("{\"IdentityToken\":\"synthetic-secret\"}")
        first.send(update("a", initial = true))
        awaitState { inbox.snapshot()?.map { it.id } == listOf("a") }
        val outcome = TicketSpacetimeCommandResult(true, "applied", "streaming")
        results.settle("a", outcome) // Worker has already recorded its outcome; acknowledgement was lost.
        first.send(update("b", delete = "a"))
        awaitState { inbox.snapshot()?.map { it.id } == listOf("b") }
        results.retain(inbox.snapshot()!!.map { it.id }.toSet())
        assertNull(results.peek("a"))
        results.settle("b", outcome)
        first.close(1000, null)
        val second = sockets.poll(4, TimeUnit.SECONDS)!!
        assertNotNull(subscriptionRequests.poll(4, TimeUnit.SECONDS))
        assertNull(inbox.snapshot())
        assertEquals(outcome, results.peek("b"))
        second.send("{\"IdentityToken\":\"synthetic-secret\"}")
        second.send(update("b", initial = true))
        awaitState { inbox.snapshot()?.singleOrNull()?.id == "b" }
        assertEquals(outcome, results.peek(inbox.snapshot()!!.single().id))
        second.send(update(null, delete = "b"))
        awaitState { inbox.snapshot()?.isEmpty() == true }
        results.retain(emptySet())
        assertNull(results.peek("b"))
      } finally { subscription.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
  }
  @Test fun foreignRowsAndProtocolFailuresNeverGrantCommandAuthority() = runBlocking {
    for (scenario in listOf("foreign", "wrong-request", "missing-identity", "early-update", "binary", "wrong-protocol")) {
      MockWebServer().use { server ->
        val states = LinkedBlockingQueue<Pair<TicketSpacetimeCommandSubscriptionState, String>>()
        server.enqueue(MockResponse().setHeader("Sec-WebSocket-Protocol", if (scenario == "wrong-protocol") "wrong" else protocol).withWebSocketUpgrade(object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            if (scenario != "missing-identity") webSocket.send("{\"IdentityToken\":\"synthetic-secret\"}")
            when (scenario) {
              "foreign" -> webSocket.send(update("foreign-command", initial = true, ticket = "foreign"))
              "wrong-request" -> webSocket.send(update("a", initial = true, request = 7))
              "early-update" -> webSocket.send(update("a"))
              "binary" -> webSocket.send("synthetic-secret".encodeUtf8())
              else -> webSocket.send(update("a", initial = true))
            }
          }
        }))
        server.start()
        val inbox = TicketCommandInbox()
        val client = OkHttpClient()
        val subscription = TicketSpacetimeCommandSubscription(this, TicketSpacetimeCommandSubscriptionConfig(server.url("/").toString(), "synthetic-db", "synthetic-token", "ticket", "pixel"), Json, inbox, onState = { state, category -> states.add(state to category) }, httpClient = client, reconnectDelayMillis = 5000, maxReconnectDelayMillis = 5000)
        try {
          subscription.start()
          val state = states.poll(4, TimeUnit.SECONDS)!!
          if (scenario == "foreign") {
            assertEquals(TicketSpacetimeCommandSubscriptionState.READY, state.first)
            awaitState { inbox.snapshot() != null }
            assertTrue(inbox.snapshot()!!.isEmpty())
          } else {
            assertEquals(scenario, TicketSpacetimeCommandSubscriptionState.DISCONNECTED, state.first)
            assertNull(inbox.snapshot())
            assertFalse(state.second.contains("synthetic-secret"))
          }
        } finally { subscription.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
      }
    }
  }
}
