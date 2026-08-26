package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class TicketSpacetimeKeyframeSubscriptionTest {
  @Test
  fun sharedLedgerRetainsEveryTerminalResultThroughDurableAck() {
    val ledger = TicketSpacetimeSubscribedCommandLedger()
    repeat(200) { index ->
      ledger.record(
        "command-$index",
        TicketSpacetimeCommandResult(true, "done", "streaming", terminal = true)
      )
    }
    assertEquals(200, ledger.size())
    assertTrue(ledger.takeForPoll("command-0")?.terminal == true)
    assertTrue(ledger.takeForPoll("command-0")?.terminal == true)
    assertEquals(200, ledger.size())
    ledger.acknowledge("command-0")
    assertEquals(200, ledger.size())
    assertEquals(1, ledger.acknowledgedSize())
    assertTrue(ledger.owns("command-0"))
  }

  @Test
  fun sharedLedgerConsumesRunningResultOnceSoPollCanAdvanceStateMachine() {
    val ledger = TicketSpacetimeSubscribedCommandLedger()
    ledger.record(
      "running",
      TicketSpacetimeCommandResult(false, "running", "starting", terminal = false)
    )
    assertFalse(ledger.takeForPoll("running")?.terminal ?: true)
    assertEquals(null, ledger.takeForPoll("running"))
  }

  @Test
  fun subscriptionAndPollCannotBothApplyTheSameTerminalPhoneAction() = runBlocking {
    val ledger = TicketSpacetimeSubscribedCommandLedger()
    val handoff = TicketSpacetimeSubscribedCommandHandoff(ledger, kotlinx.coroutines.sync.Mutex())
    val phoneCalls = AtomicInteger(0)
    val subscriptionEntered = CompletableDeferred<Unit>()
    val releaseSubscription = CompletableDeferred<Unit>()
    val terminal = TicketSpacetimeCommandResult(
      ok = true,
      reason = "terminal",
      streamState = "streaming",
      terminal = true
    )

    coroutineScope {
      val subscription = async {
        handoff.fromSubscription("action") {
          phoneCalls.incrementAndGet()
          subscriptionEntered.complete(Unit)
          releaseSubscription.await()
          terminal
        }
      }
      subscriptionEntered.await()
      val poll = async {
        handoff.fromPoll("action") {
          phoneCalls.incrementAndGet()
          terminal
        }
      }
      delay(20)
      releaseSubscription.complete(Unit)
      assertEquals(listOf(terminal, terminal), awaitAll(subscription, poll))
    }

    assertEquals(1, phoneCalls.get())
    assertTrue(ledger.hasResult("action"))
  }

  @Test
  fun consumedRunningHandoffStillSuppressesDuplicateSubscriptionDelivery() = runBlocking {
    val ledger = TicketSpacetimeSubscribedCommandLedger()
    val handoff = TicketSpacetimeSubscribedCommandHandoff(ledger, kotlinx.coroutines.sync.Mutex())
    val serviceCalls = AtomicInteger(0)
    val running = TicketSpacetimeCommandResult(
      ok = false,
      reason = "running",
      streamState = "starting",
      terminal = false
    )

    assertEquals(running, handoff.fromSubscription("action") {
      serviceCalls.incrementAndGet()
      running
    })
    assertEquals(running, handoff.fromPoll("action") {
      serviceCalls.incrementAndGet()
      error("poll must consume the subscribed running result first")
    })
    val duplicate = handoff.fromSubscription("action") {
      serviceCalls.incrementAndGet()
      error("duplicate subscription must remain suppressed after poll handoff")
    }

    assertEquals(1, serviceCalls.get())
    assertFalse(duplicate.terminal)
    assertEquals("subscription_command_owned_by_poller", duplicate.reason)
    assertTrue(ledger.owns("action"))
    ledger.acknowledge("action")
    assertFalse(ledger.owns("action"))
  }

  @Test
  fun acknowledgedTerminalTombstoneReacksStalePollWithoutPhoneHandlerReentry() = runBlocking {
    var now = 1_000L
    val ledger = TicketSpacetimeSubscribedCommandLedger(
      acknowledgedCapacity = 4,
      acknowledgedTtlMillis = 1_000L,
      nowMillis = { now }
    )
    val handoff = TicketSpacetimeSubscribedCommandHandoff(ledger, kotlinx.coroutines.sync.Mutex())
    val phoneCalls = AtomicInteger(0)
    var ackCalls = 0
    val terminal = TicketSpacetimeCommandResult(
      ok = false,
      reason = "selected_anchor_missing",
      streamState = "streaming",
      terminal = true,
      ticketActionV3 = TicketVisualActionSnapshot(
        actionId = "action",
        target = "open_latest_unactivated",
        status = "failed",
        phase = "failed",
        reason = "selected_anchor_missing",
        terminal = true,
        ok = false
      )
    )

    assertEquals(terminal, handoff.fromPoll("command") {
      phoneCalls.incrementAndGet()
      terminal
    })
    ackCalls += 1
    ledger.acknowledge("command")

    now += 20L
    assertEquals(terminal, handoff.fromPoll("command") {
      phoneCalls.incrementAndGet()
      error("stale pending poll must reuse the terminal tombstone")
    })
    ackCalls += 1
    ledger.acknowledge("command")

    assertEquals(1, phoneCalls.get())
    assertEquals(2, ackCalls)
    assertEquals(1, ledger.acknowledgedSize())
    assertTrue(ledger.owns("command"))
  }

  @Test
  fun terminalAckLossAndSubscriptionReplayNeverReenterPhoneOrPublishTerminal() = runBlocking {
    val ledger = TicketSpacetimeSubscribedCommandLedger()
    val handoff = TicketSpacetimeSubscribedCommandHandoff(ledger, kotlinx.coroutines.sync.Mutex())
    val phoneCalls = AtomicInteger(0)
    val terminal = TicketSpacetimeCommandResult(
      ok = true,
      reason = "complete",
      streamState = "streaming",
      terminal = true,
      ticketActionV3 = TicketVisualActionSnapshot(
        actionId = "action",
        target = "open_latest_unactivated",
        status = "succeeded",
        phase = "complete",
        reason = "complete",
        terminal = true,
        ok = true
      )
    )

    assertEquals(terminal, handoff.fromSubscription("command") {
      phoneCalls.incrementAndGet()
      terminal
    })
    // The first durable acknowledgement response is lost, so no acknowledge() call is made.
    assertEquals(terminal, handoff.fromPoll("command") {
      phoneCalls.incrementAndGet()
      error("ack loss must replay the retained terminal result")
    })
    ledger.acknowledge("command")

    val staleSubscription = handoff.fromSubscription("command") {
      phoneCalls.incrementAndGet()
      error("terminal tombstone must suppress stale subscription delivery")
    }
    var subscriptionTerminalPublications = 0
    staleSubscription.ticketActionV3?.takeIf { !staleSubscription.terminal }?.let {
      subscriptionTerminalPublications += 1
    }

    assertTrue(staleSubscription.terminal)
    assertEquals(1, phoneCalls.get())
    assertEquals(0, subscriptionTerminalPublications)
    assertEquals(1, ledger.acknowledgedSize())
  }

  @Test
  fun acknowledgedTombstonesAreCapacityAndTtlBoundedWithoutEvictingActiveOwnership() {
    var now = 1_000L
    val ledger = TicketSpacetimeSubscribedCommandLedger(
      acknowledgedCapacity = 2,
      acknowledgedTtlMillis = 100L,
      nowMillis = { now }
    )
    val terminal = TicketSpacetimeCommandResult(true, "done", "streaming", terminal = true)
    ledger.record("active", terminal)

    fun acknowledgeAt(id: String, atMillis: Long) {
      now = atMillis
      ledger.record(id, terminal)
      ledger.acknowledge(id)
    }

    acknowledgeAt("acked-1", 1_001L)
    acknowledgeAt("acked-2", 1_002L)
    acknowledgeAt("acked-3", 1_003L)

    assertEquals(2, ledger.acknowledgedSize())
    assertFalse(ledger.owns("acked-1"))
    assertTrue(ledger.owns("acked-2"))
    assertTrue(ledger.owns("acked-3"))
    assertTrue(ledger.owns("active"))
    assertEquals(3, ledger.size())

    now = 1_103L
    assertEquals(0, ledger.acknowledgedSize())
    assertFalse(ledger.owns("acked-2"))
    assertFalse(ledger.owns("acked-3"))
    assertTrue(ledger.owns("active"))
    assertEquals(1, ledger.size())
  }

  @Test
  fun parserAcceptsOnlyCurrentPendingTicketAutomationRows() {
    val message = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = transactionUpdate(
        commandRow(
          ticketId = "vivi-default",
          backendId = "pixel",
          commandType = "keyframe",
          status = "pending",
          expiresAt = "2099-01-01T00:00:00Z"
        ),
        commandRow(
          ticketId = "other-ticket",
          backendId = "pixel",
          commandType = "keyframe",
          status = "pending",
          expiresAt = "2099-01-01T00:00:00Z"
        ),
        commandRow(
          ticketId = "vivi-default",
          backendId = "pixel",
          commandType = "start",
          status = "pending",
          expiresAt = "2099-01-01T00:00:00Z"
        ),
        commandRow(
          ticketId = "vivi-default",
          backendId = "pixel",
          commandType = "keyframe",
          status = "pending",
          expiresAt = "2020-01-01T00:00:00Z"
        )
      ),
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel",
      now = Instant.parse("2026-08-23T00:00:00Z")
    )

    assertEquals(TicketSpacetimeKeyframeSubscriptionMessageKind.UPDATE, message.kind)
    assertEquals(listOf("keyframe", "start"), message.commands.map { it.commandType })
    assertTrue(message.commands.all { it.id == "trace_fixture" })
  }

  @Test
  fun liveSubscriptionAcceptsV3AndQueryIncludesBothFastCommands() {
    val message = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = transactionUpdate(commandRow(
        ticketId = "vivi-default",
        backendId = "pixel",
        commandType = "ticket_action_v3",
        status = "pending",
        expiresAt = "2099-01-01T00:00:00Z"
      )),
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel",
      now = Instant.parse("2026-08-23T00:00:00Z")
    )
    assertEquals("ticket_action_v3", message.commands.single().commandType)
    val query = ticketSpacetimeKeyframeSubscriptionQuery("vivi-default", "pixel")
    assertTrue(query.contains("status = 'pending'"))
    assertFalse(query.contains("commandType ="))
  }

  @Test
  fun parserAcceptsProductionArrayRowsInFullAndLightUpdates() {
    val row = commandArrayRow(
      ticketId = "vivi-default",
      backendId = "pixel",
      commandType = "keyframe",
      status = "pending",
      expiresAt = "2099-01-01T00:00:00Z"
    )
    val full = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = transactionUpdate(row),
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel",
      now = Instant.parse("2026-08-23T00:00:00Z")
    )
    val light = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = transactionUpdateLight(row),
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel",
      now = Instant.parse("2026-08-23T00:00:00Z")
    )

    val command = full.commands.single()
    assertEquals("trace_fixture", command.id)
    assertEquals("vivi-default", command.ticketId)
    assertEquals("pixel", command.backendId)
    assertEquals("keyframe", command.commandType)
    assertEquals("pending", command.status)
    assertEquals("revision-fixture", command.revision)
    assertEquals("viewer_join", command.reason)
    assertEquals("{}", command.payloadJson)
    assertEquals("2026-08-23T00:00:00Z", command.createdAt)
    assertEquals("2026-08-23T00:00:00Z", command.updatedAt)
    assertEquals("2099-01-01T00:00:00Z", command.expiresAt)
    assertEquals(listOf("trace_fixture"), light.commands.map { it.id })
  }

  @Test
  fun parserFiltersMismatchedAndExpiredProductionArrayRows() {
    val message = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = transactionUpdateLight(
        commandArrayRow("other-ticket", "pixel", "keyframe", "pending", "2099-01-01T00:00:00Z"),
        commandArrayRow("vivi-default", "other-backend", "keyframe", "pending", "2099-01-01T00:00:00Z"),
        commandArrayRow("vivi-default", "pixel", "start", "pending", "2099-01-01T00:00:00Z"),
        commandArrayRow("vivi-default", "pixel", "keyframe", "acknowledged", "2099-01-01T00:00:00Z"),
        commandArrayRow("vivi-default", "pixel", "keyframe", "pending", "2020-01-01T00:00:00Z"),
        commandArrayRow("vivi-default", "pixel", "keyframe", "pending", "not-a-timestamp"),
        commandArrayRow(
          "vivi-default",
          "pixel",
          "keyframe",
          "pending",
          "2099-01-01T00:00:00Z",
          payloadJson = "x".repeat(8 * 1024 + 1)
        )
      ),
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel",
      now = Instant.parse("2026-08-23T00:00:00Z")
    )

    assertEquals(listOf("start"), message.commands.map { it.commandType })
  }

  @Test
  fun parserRejectsMalformedProductionArrayRows() {
    val message = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = transactionUpdate(
        """["too","short"]""",
        """[123,"vivi-default","pixel","keyframe","pending","revision-fixture","viewer_join","{}","2026-08-23T00:00:00Z","2026-08-23T00:00:00Z","2099-01-01T00:00:00Z"]""",
        commandArrayRow(
          "vivi-default",
          "pixel",
          "keyframe",
          "pending",
          "2099-01-01T00:00:00Z"
        ).dropLast(1) + ",\"unexpected\"]"
      ),
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel"
    )

    assertEquals(TicketSpacetimeKeyframeSubscriptionMessageKind.UPDATE, message.kind)
    assertTrue(message.commands.isEmpty())
  }

  @Test
  fun identityTokenBodyIsIgnoredAndNeverBecomesACommand() {
    val message = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = """{"IdentityToken":{"identity":"identity-fixture","token":"must-not-surface","connection_id":"connection-fixture"}}""",
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel"
    )

    assertEquals(TicketSpacetimeKeyframeSubscriptionMessageKind.IDENTITY, message.kind)
    assertTrue(message.commands.isEmpty())
    assertFalse(message.toString().contains("must-not-surface"))
  }

  @Test
  fun malformedShapesAndWrongSnapshotRequestIdFailClosed() {
    val malformed = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = """{"InitialSubscription":[]}""",
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel"
    )
    val wrongRequest = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = """{"InitialSubscription":{"database_update":{"tables":[]},"request_id":9}}""",
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel"
    )

    assertEquals(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR, malformed.kind)
    assertEquals(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR, wrongRequest.kind)
  }

  @Test
  fun oversizedMessageFailsBeforeNestedParsing() {
    val message = parseTicketSpacetimeKeyframeSubscriptionMessage(
      rawMessage = "x".repeat(800 * 1024),
      expectedTicketId = "vivi-default",
      expectedBackendId = "pixel"
    )

    assertEquals(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR, message.kind)
  }

  @Test
  fun coordinatorRunsConcurrentPhoneAndAcknowledgementExactlyOnce() = runBlocking {
    val phoneCalls = AtomicInteger(0)
    val acknowledgementCalls = AtomicInteger(0)
    val coordinator = TicketSpacetimeKeyframeDispatchCoordinator(nowMillis = { 1_000L })

    val results = coroutineScope {
      (1..8).map {
        async {
          coordinator.process(
            commandId = "same-command",
            lane = "test",
            applyToPhone = {
              phoneCalls.incrementAndGet()
              TicketSpacetimeCommandResult(ok = true, reason = "keyframe_requested", streamState = "streaming")
            },
            acknowledge = { acknowledgementCalls.incrementAndGet() }
          )
        }
      }.awaitAll()
    }

    assertEquals(1, phoneCalls.get())
    assertEquals(1, acknowledgementCalls.get())
    assertEquals(1, results.count { it.phoneAppliedNow })
    assertEquals(1, results.count { it.acknowledgedNow })
    assertEquals(TicketSpacetimeKeyframeDispatchPhase.ACKED, coordinator.phase("same-command"))
  }

  @Test
  fun coordinatorRetriesFailedAcknowledgementWithoutRepeatingPhoneRequest() = runBlocking {
    val phoneCalls = AtomicInteger(0)
    val acknowledgementCalls = AtomicInteger(0)
    val coordinator = TicketSpacetimeKeyframeDispatchCoordinator(nowMillis = { 1_000L })
    val apply = suspend {
      phoneCalls.incrementAndGet()
      TicketSpacetimeCommandResult(ok = true, reason = "keyframe_requested", streamState = "streaming")
    }

    val first = runCatching {
      coordinator.process(
        commandId = "retry-command",
        lane = "live_subscription",
        applyToPhone = apply,
        acknowledge = {
          acknowledgementCalls.incrementAndGet()
          throw IOException("fixture acknowledgement failure")
        }
      )
    }
    assertTrue(first.isFailure)
    assertEquals(TicketSpacetimeKeyframeDispatchPhase.ACK_PENDING, coordinator.phase("retry-command"))

    val retried = coordinator.process(
      commandId = "retry-command",
      lane = "durable_poll",
      applyToPhone = apply,
      acknowledge = { acknowledgementCalls.incrementAndGet() }
    )

    assertEquals(1, phoneCalls.get())
    assertEquals(2, acknowledgementCalls.get())
    assertFalse(retried.phoneAppliedNow)
    assertTrue(retried.acknowledgedNow)
    assertEquals("live_subscription", retried.firstLane)
    assertEquals(TicketSpacetimeKeyframeDispatchPhase.ACKED, coordinator.phase("retry-command"))
  }

  @Test
  fun coordinatorIsBoundedAndOnlyCompletedCommandsExpire() = runBlocking {
    var now = 1_000L
    val phoneCalls = AtomicInteger(0)
    val coordinator = TicketSpacetimeKeyframeDispatchCoordinator(
      capacity = 2,
      ackedTtlMillis = 100L,
      nowMillis = { now }
    )
    suspend fun dispatch(id: String) = coordinator.process(
      commandId = id,
      lane = "test",
      applyToPhone = {
        phoneCalls.incrementAndGet()
        TicketSpacetimeCommandResult(ok = true, reason = id, streamState = "streaming")
      },
      acknowledge = {}
    )

    dispatch("one")
    dispatch("two")
    dispatch("three")
    dispatch("one")
    now += 101L
    dispatch("three")

    assertEquals(5, phoneCalls.get())
  }

  @Test
  fun pendingAcknowledgementCannotBeEvictedByCapacityPressure() = runBlocking {
    val coordinator = TicketSpacetimeKeyframeDispatchCoordinator(capacity = 1, nowMillis = { 1_000L })
    runCatching {
      coordinator.process(
        commandId = "pending",
        lane = "test",
        applyToPhone = {
          TicketSpacetimeCommandResult(ok = true, reason = "pending", streamState = "streaming")
        },
        acknowledge = { throw IOException("fixture acknowledgement failure") }
      )
    }

    val second = runCatching {
      coordinator.process(
        commandId = "other",
        lane = "test",
        applyToPhone = {
          TicketSpacetimeCommandResult(ok = true, reason = "other", streamState = "streaming")
        },
        acknowledge = {}
      )
    }

    assertTrue(second.isFailure)
    assertEquals(TicketSpacetimeKeyframeDispatchPhase.ACK_PENDING, coordinator.phase("pending"))
  }

  @Test
  fun reconnectBackoffIsCappedJitteredAndLongForProtocolFailures() {
    assertEquals(
      500L,
      ticketSpacetimeKeyframeReconnectDelayMillis("socket_closed", 1, 500L, 30_000L, 1.0)
    )
    assertEquals(
      2_000L,
      ticketSpacetimeKeyframeReconnectDelayMillis("socket_closed", 3, 500L, 30_000L, 1.0)
    )
    assertEquals(
      24_000L,
      ticketSpacetimeKeyframeReconnectDelayMillis("authorization_failed", 1, 500L, 30_000L, 0.0)
    )
  }

  @Test
  fun unexpectedBinaryFrameLeavesReadyStateAndStopPreventsReconnect() = runBlocking {
    val server = MockWebServer()
    val ready = CompletableDeferred<Unit>()
    val fallback = CompletableDeferred<String>()
    val serverListener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(
          """{"IdentityToken":{"identity":"identity-fixture","token":"server-token-fixture","connection_id":"connection-fixture"}}"""
        )
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        webSocket.send(initialSubscription())
        webSocket.send("unexpected-binary".encodeUtf8())
      }
    }
    server.enqueue(
      MockResponse()
        .setHeader("Sec-WebSocket-Protocol", "v1.json.spacetimedb")
        .withWebSocketUpgrade(serverListener)
    )
    server.start()
    val subscription = TicketSpacetimeKeyframeSubscription(
      scope = this,
      config = testConfig(server),
      json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
      onKeyframeCommand = {},
      onState = { state, category ->
        if (state == TicketSpacetimeKeyframeSubscriptionState.READY) ready.complete(Unit)
        if (state == TicketSpacetimeKeyframeSubscriptionState.FALLBACK) fallback.complete(category)
      },
      reconnectDelayMillis = 5_000L
    )

    try {
      subscription.start()
      withTimeout(5_000L) { ready.await() }
      assertEquals("unexpected_binary_message", withTimeout(5_000L) { fallback.await() })
      subscription.stop()
      kotlinx.coroutines.delay(50L)
      assertEquals(1, server.requestCount)
      assertFalse(subscription.isReady())
    } finally {
      subscription.stop()
      server.shutdown()
    }
  }

  @Test
  fun v3PriorityConsumerIsNotBlockedByAWaitingKeyframeCommand() = runBlocking {
    val server = MockWebServer()
    val socket = CompletableDeferred<WebSocket>()
    val keyframeStarted = CompletableDeferred<Unit>()
    val releaseKeyframe = CompletableDeferred<Unit>()
    val v3Delivered = CompletableDeferred<Unit>()
    val serverListener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(
          """{"IdentityToken":{"identity":"identity-fixture","token":"server-token-fixture","connection_id":"connection-fixture"}}"""
        )
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        if (!socket.isCompleted) socket.complete(webSocket)
        webSocket.send(initialSubscription(commandRow(
          ticketId = "vivi-default",
          backendId = "pixel",
          commandType = "keyframe",
          status = "pending",
          expiresAt = "2099-01-01T00:00:00Z",
          id = "keyframe-fixture"
        )))
      }
    }
    server.enqueue(
      MockResponse()
        .setHeader("Sec-WebSocket-Protocol", "v1.json.spacetimedb")
        .withWebSocketUpgrade(serverListener)
    )
    server.start()
    val subscription = TicketSpacetimeKeyframeSubscription(
      scope = this,
      config = testConfig(server),
      json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
      onKeyframeCommand = { command ->
        if (command.commandType == "keyframe") {
          keyframeStarted.complete(Unit)
          releaseKeyframe.await()
        } else if (command.commandType == "ticket_action_v3") {
          v3Delivered.complete(Unit)
        }
      },
      reconnectDelayMillis = 5_000L
    )

    try {
      subscription.start()
      withTimeout(5_000L) { keyframeStarted.await() }
      withTimeout(5_000L) { socket.await() }.send(transactionUpdate(commandRow(
        ticketId = "vivi-default",
        backendId = "pixel",
        commandType = "ticket_action_v3",
        status = "pending",
        expiresAt = "2099-01-01T00:00:00Z",
        id = "v3-fixture"
      )))
      withTimeout(1_000L) { v3Delivered.await() }
      assertFalse(releaseKeyframe.isCompleted)
    } finally {
      releaseKeyframe.complete(Unit)
      subscription.stop()
      server.shutdown()
    }
  }

  @Test
  fun websocketNegotiatesAuthenticatedUncompressedSubscriptionAndPushesProductionArrayKeyframe() = runBlocking {
    val server = MockWebServer()
    val subscribeMessage = CompletableDeferred<String>()
    val deliveredCommand = CompletableDeferred<TicketSpacetimeCommand>()
    val ready = CompletableDeferred<Unit>()
    val serverListener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(
          """{"IdentityToken":{"identity":"identity-fixture","token":"server-token-fixture","connection_id":"connection-fixture"}}"""
        )
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        subscribeMessage.complete(text)
        webSocket.send(
          initialSubscription(
            commandArrayRow(
              ticketId = "vivi-default",
              backendId = "pixel",
              commandType = "keyframe",
              status = "pending",
              expiresAt = "2099-01-01T00:00:00Z"
            )
          )
        )
      }
    }
    server.enqueue(
      MockResponse()
        .setHeader("Sec-WebSocket-Protocol", "v1.json.spacetimedb")
        .withWebSocketUpgrade(serverListener)
    )
    server.start()
    val subscription = TicketSpacetimeKeyframeSubscription(
      scope = this,
      config = testConfig(server),
      json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
      onKeyframeCommand = { command -> deliveredCommand.complete(command) },
      onState = { state, _ ->
        if (state == TicketSpacetimeKeyframeSubscriptionState.READY) ready.complete(Unit)
      },
      reconnectDelayMillis = 5_000L
    )

    try {
      subscription.start()
      val command = withTimeout(5_000L) { deliveredCommand.await() }
      withTimeout(5_000L) { ready.await() }
      val sent = withTimeout(5_000L) { subscribeMessage.await() }
      val request = server.takeRequest()

      assertEquals("keyframe", command.commandType)
      assertEquals("Bearer client-token-fixture", request.getHeader("Authorization"))
      assertEquals("v1.json.spacetimedb", request.getHeader("Sec-WebSocket-Protocol"))
      assertEquals("None", request.requestUrl?.queryParameter("compression"))
      assertEquals("false", request.requestUrl?.queryParameter("confirmed"))
      assertTrue(sent.contains("ticketremote_service_stream_command"))
      assertTrue(sent.contains("status = 'pending'"))
      assertFalse(sent.contains("commandType ="))
      assertFalse(sent.contains("client-token-fixture"))
      assertTrue(subscription.isReady())
    } finally {
      subscription.stop()
      server.shutdown()
    }
  }

  private fun initialSubscription(vararg rows: String): String {
    val tables = if (rows.isEmpty()) {
      "[]"
    } else {
      val inserts = rows.joinToString(",") { row -> kotlinx.serialization.json.JsonPrimitive(row).toString() }
      """[{"table_id":1,"table_name":"ticketremote_service_stream_command","num_rows":${rows.size},"updates":[{"deletes":[],"inserts":[$inserts]}]}]"""
    }
    return """{"InitialSubscription":{"database_update":{"tables":$tables},"request_id":0,"total_host_execution_duration":{"__time_duration_micros__":0}}}"""
  }

  private fun testConfig(server: MockWebServer): TicketSpacetimeKeyframeSubscriptionConfig {
    return TicketSpacetimeKeyframeSubscriptionConfig(
      host = server.url("/").toString().trimEnd('/'),
      database = "ticket-test",
      bearerToken = "client-token-fixture",
      ticketId = "vivi-default",
      backendId = "pixel"
    )
  }

  private fun transactionUpdate(vararg rows: String): String {
    val inserts = rows.joinToString(",") { row -> kotlinx.serialization.json.JsonPrimitive(row).toString() }
    return """{"TransactionUpdate":{"status":{"Committed":{"tables":[{"table_id":1,"table_name":"ticketremote_service_stream_command","num_rows":${rows.size},"updates":[{"deletes":[],"inserts":[$inserts]}]}]}},"timestamp":{"__timestamp_micros_since_unix_epoch__":0},"caller_identity":{"__identity__":"0"},"caller_connection_id":{"__connection_id__":"0"},"reducer_call":{"reducer_name":"fixture","reducer_id":0,"args":"{}","request_id":0},"energy_quanta_used":{"quanta":0},"total_host_execution_duration":{"__time_duration_micros__":0}}}"""
  }

  private fun transactionUpdateLight(vararg rows: String): String {
    val inserts = rows.joinToString(",") { row -> kotlinx.serialization.json.JsonPrimitive(row).toString() }
    return """{"TransactionUpdateLight":{"request_id":0,"update":{"tables":[{"table_id":1,"table_name":"ticketremote_service_stream_command","num_rows":${rows.size},"updates":[{"deletes":[],"inserts":[$inserts]}]}]}}}"""
  }

  private fun commandRow(
    ticketId: String,
    backendId: String,
    commandType: String,
    status: String,
    expiresAt: String,
    id: String = "trace_fixture"
  ): String {
    return """{"id":"$id","ticketId":"$ticketId","backendId":"$backendId","commandType":"$commandType","status":"$status","revision":"revision-fixture","reason":"viewer_join","payloadJson":"{}","createdAt":"2026-08-23T00:00:00Z","updatedAt":"2026-08-23T00:00:00Z","expiresAt":"$expiresAt"}"""
  }

  private fun commandArrayRow(
    ticketId: String,
    backendId: String,
    commandType: String,
    status: String,
    expiresAt: String,
    payloadJson: String = "{}"
  ): String {
    return kotlinx.serialization.json.JsonArray(
      listOf(
        "trace_fixture",
        ticketId,
        backendId,
        commandType,
        status,
        "revision-fixture",
        "viewer_join",
        payloadJson,
        "2026-08-23T00:00:00Z",
        "2026-08-23T00:00:00Z",
        expiresAt
      ).map { value -> kotlinx.serialization.json.JsonPrimitive(value) }
    ).toString()
  }
}
