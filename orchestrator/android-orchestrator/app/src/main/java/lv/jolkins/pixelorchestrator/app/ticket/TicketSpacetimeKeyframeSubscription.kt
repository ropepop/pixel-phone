package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class TicketSpacetimeKeyframeSubscriptionState {
  READY,
  FALLBACK,
  COMMAND_FAILED
}

internal enum class TicketSpacetimeKeyframeSubscriptionMessageKind {
  IDENTITY,
  IGNORED,
  APPLIED,
  UPDATE,
  ERROR
}

internal data class TicketSpacetimeKeyframeSubscriptionMessage(
  val kind: TicketSpacetimeKeyframeSubscriptionMessageKind,
  val commands: List<TicketSpacetimeCommand> = emptyList(),
  val requestId: Int? = null
)

internal data class TicketSpacetimeKeyframeSubscriptionConfig(
  val host: String,
  val database: String,
  val bearerToken: String,
  val ticketId: String,
  val backendId: String
)

internal enum class TicketSpacetimeKeyframeDispatchPhase {
  PROCESSING,
  ACK_PENDING,
  ACKED
}

internal data class TicketSpacetimeKeyframeDispatchOutcome(
  val result: TicketSpacetimeCommandResult,
  val phoneAppliedNow: Boolean,
  val acknowledgedNow: Boolean,
  val firstDispatchAtMillis: Long,
  val firstLane: String
)

/**
 * Shared warm-subscription/poll handoff. Terminal results survive acknowledgement as bounded
 * tombstones, so an already-fetched pending row can be acknowledged again without re-entering the
 * phone handler. Running results are consumed once so polling can advance the same state machine.
 * Active entries are never evicted; only acknowledged tombstones are TTL/capacity pruned.
 */
internal class TicketSpacetimeSubscribedCommandLedger(
  private val acknowledgedCapacity: Int = SUBSCRIBED_COMMAND_ACK_TOMBSTONE_CAPACITY,
  private val acknowledgedTtlMillis: Long = SUBSCRIBED_COMMAND_ACK_TOMBSTONE_TTL_MILLIS,
  private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
  private val results = linkedMapOf<String, TicketSpacetimeCommandResult>()
  private val owned = linkedSetOf<String>()
  private val acknowledgedAtMillis = linkedMapOf<String, Long>()

  init {
    require(acknowledgedCapacity > 0)
    require(acknowledgedTtlMillis > 0L)
  }

  @Synchronized fun record(commandId: String, result: TicketSpacetimeCommandResult) {
    require(commandId.isNotBlank())
    pruneAcknowledged(nowMillis())
    if (acknowledgedAtMillis.containsKey(commandId)) return
    owned.add(commandId)
    results[commandId] = result
    acknowledgedAtMillis.remove(commandId)
  }

  @Synchronized fun hasResult(commandId: String): Boolean {
    pruneAcknowledged(nowMillis())
    return results.containsKey(commandId)
  }

  @Synchronized fun owns(commandId: String): Boolean {
    pruneAcknowledged(nowMillis())
    return owned.contains(commandId)
  }

  @Synchronized fun claim(commandId: String): Boolean {
    require(commandId.isNotBlank())
    pruneAcknowledged(nowMillis())
    return owned.add(commandId)
  }

  @Synchronized fun release(commandId: String) {
    if (acknowledgedAtMillis.containsKey(commandId)) return
    results.remove(commandId)
    owned.remove(commandId)
    acknowledgedAtMillis.remove(commandId)
  }

  @Synchronized fun peek(commandId: String): TicketSpacetimeCommandResult? {
    pruneAcknowledged(nowMillis())
    return results[commandId]
  }

  @Synchronized fun takeForPoll(commandId: String): TicketSpacetimeCommandResult? {
    pruneAcknowledged(nowMillis())
    val result = results[commandId] ?: return null
    if (!result.terminal) results.remove(commandId)
    return result
  }

  @Synchronized fun settle(commandId: String, terminal: TicketSpacetimeCommandResult) {
    require(terminal.terminal)
    val now = nowMillis()
    pruneAcknowledged(now)
    owned.add(commandId)
    results[commandId] = terminal
    acknowledgedAtMillis.remove(commandId)
    acknowledgedAtMillis[commandId] = now
    pruneAcknowledged(now)
  }

  @Synchronized fun acknowledge(commandId: String) {
    val now = nowMillis()
    pruneAcknowledged(now)
    val result = results[commandId]
    if (result?.terminal == true) {
      owned.add(commandId)
      acknowledgedAtMillis.remove(commandId)
      acknowledgedAtMillis[commandId] = now
      pruneAcknowledged(now)
    } else {
      release(commandId)
    }
  }

  @Synchronized internal fun size(): Int {
    pruneAcknowledged(nowMillis())
    return results.size
  }

  @Synchronized internal fun acknowledgedSize(): Int {
    pruneAcknowledged(nowMillis())
    return acknowledgedAtMillis.size
  }

  private fun pruneAcknowledged(now: Long) {
    val expired = acknowledgedAtMillis.entries
      .takeWhile { now - it.value >= acknowledgedTtlMillis }
      .map { it.key }
    expired.forEach(::removeAcknowledged)
    while (acknowledgedAtMillis.size > acknowledgedCapacity) {
      removeAcknowledged(acknowledgedAtMillis.keys.first())
    }
  }

  private fun removeAcknowledged(commandId: String) {
    acknowledgedAtMillis.remove(commandId)
    results.remove(commandId)
    owned.remove(commandId)
  }
}

/**
 * Atomically hands a pending command between the live subscription and reconnect poller.
 * The result is recorded before the phone-dispatch mutex is released, closing the small race in
 * which the other lane could acquire the mutex after the phone mutation but before ledger record.
 */
internal class TicketSpacetimeSubscribedCommandHandoff(
  private val ledger: TicketSpacetimeSubscribedCommandLedger,
  private val dispatchMutex: Mutex
) {
  suspend fun fromSubscription(
    commandId: String,
    applyToPhone: suspend () -> TicketSpacetimeCommandResult
  ): TicketSpacetimeCommandResult = dispatchMutex.withLock {
    ledger.peek(commandId) ?: if (!ledger.claim(commandId)) {
      TicketSpacetimeCommandResult(
        ok = false,
        reason = "subscription_command_owned_by_poller",
        streamState = "",
        terminal = false
      )
    } else {
      try {
        applyToPhone().also { ledger.record(commandId, it) }
      } catch (error: Throwable) {
        ledger.release(commandId)
        throw error
      }
    }
  }

  suspend fun fromPoll(
    commandId: String,
    applyToPhone: suspend () -> TicketSpacetimeCommandResult
  ): TicketSpacetimeCommandResult {
    ledger.takeForPoll(commandId)?.let { return it }
    return dispatchMutex.withLock {
      ledger.takeForPoll(commandId) ?: run {
        ledger.claim(commandId)
        try {
          applyToPhone().also { result ->
            ledger.record(commandId, result)
            if (!result.terminal) ledger.takeForPoll(commandId)
          }
        } catch (error: Throwable) {
          ledger.release(commandId)
          throw error
        }
      }
    }
  }
}

/**
 * Gives the live-subscription and durable-polling paths one owner for the entire keyframe
 * transaction. A failed acknowledgement remains ACK_PENDING, so the durable poller can retry it
 * without applying a second phone keyframe request. Only completed ACKED entries expire.
 */
internal class TicketSpacetimeKeyframeDispatchCoordinator(
  private val capacity: Int = 64,
  private val ackedTtlMillis: Long = 5 * 60_000L,
  private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
  private data class Entry(
    var phase: TicketSpacetimeKeyframeDispatchPhase,
    val firstDispatchAtMillis: Long,
    val firstLane: String,
    var result: TicketSpacetimeCommandResult? = null,
    var acknowledgedAtMillis: Long = 0L
  )

  private val mutex = Mutex()
  private val entries = linkedMapOf<String, Entry>()

  init {
    require(capacity > 0)
    require(ackedTtlMillis > 0L)
  }

  suspend fun process(
    commandId: String,
    lane: String,
    applyToPhone: suspend () -> TicketSpacetimeCommandResult,
    acknowledge: suspend (TicketSpacetimeCommandResult) -> Unit
  ): TicketSpacetimeKeyframeDispatchOutcome {
    require(commandId.isNotBlank())
    require(lane.isNotBlank())
    return mutex.withLock {
      pruneAcked(nowMillis())
      var phoneAppliedNow = false
      var entry = entries[commandId]
      if (entry == null) {
        makeRoomForNewEntry()
        entry = Entry(
          phase = TicketSpacetimeKeyframeDispatchPhase.PROCESSING,
          firstDispatchAtMillis = nowMillis(),
          firstLane = lane
        )
        entries[commandId] = entry
        try {
          entry.result = applyToPhone()
          entry.phase = TicketSpacetimeKeyframeDispatchPhase.ACK_PENDING
          phoneAppliedNow = true
        } catch (cancelled: CancellationException) {
          entries.remove(commandId)
          throw cancelled
        } catch (error: Throwable) {
          entries.remove(commandId)
          throw error
        }
      }

      val result = checkNotNull(entry.result) { "keyframe_dispatch_result_missing" }
      if (entry.phase == TicketSpacetimeKeyframeDispatchPhase.ACKED) {
        return@withLock TicketSpacetimeKeyframeDispatchOutcome(
          result = result,
          phoneAppliedNow = false,
          acknowledgedNow = false,
          firstDispatchAtMillis = entry.firstDispatchAtMillis,
          firstLane = entry.firstLane
        )
      }
      check(entry.phase == TicketSpacetimeKeyframeDispatchPhase.ACK_PENDING) {
        "keyframe_dispatch_state_invalid"
      }
      acknowledge(result)
      entry.phase = TicketSpacetimeKeyframeDispatchPhase.ACKED
      entry.acknowledgedAtMillis = nowMillis()
      TicketSpacetimeKeyframeDispatchOutcome(
        result = result,
        phoneAppliedNow = phoneAppliedNow,
        acknowledgedNow = true,
        firstDispatchAtMillis = entry.firstDispatchAtMillis,
        firstLane = entry.firstLane
      )
    }
  }

  suspend fun hasHandledOnPhone(commandId: String): Boolean {
    return mutex.withLock {
      pruneAcked(nowMillis())
      entries[commandId]?.phase in setOf(
        TicketSpacetimeKeyframeDispatchPhase.ACK_PENDING,
        TicketSpacetimeKeyframeDispatchPhase.ACKED
      )
    }
  }

  internal suspend fun phase(commandId: String): TicketSpacetimeKeyframeDispatchPhase? {
    return mutex.withLock { entries[commandId]?.phase }
  }

  private fun pruneAcked(now: Long) {
    val iterator = entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next().value
      if (
        entry.phase == TicketSpacetimeKeyframeDispatchPhase.ACKED &&
        now - entry.acknowledgedAtMillis > ackedTtlMillis
      ) {
        iterator.remove()
      }
    }
  }

  private fun makeRoomForNewEntry() {
    while (entries.size >= capacity) {
      val removableId = entries.entries.firstOrNull {
        it.value.phase == TicketSpacetimeKeyframeDispatchPhase.ACKED
      }?.key ?: error("keyframe_dispatch_capacity_exhausted")
      entries.remove(removableId)
    }
  }
}

internal fun ticketSpacetimeKeyframeSubscriptionQuery(ticketId: String, backendId: String): String {
  return "SELECT * FROM ticketremote_service_stream_command " +
    "WHERE ticketId = ${ticketSpacetimeSubscriptionSqlLiteral(ticketId)} " +
    "AND backendId = ${ticketSpacetimeSubscriptionSqlLiteral(backendId)} " +
    "AND status = 'pending'"
}

internal fun ticketSpacetimeKeyframeSubscribeMessage(query: String): String {
  return buildJsonObject {
    put("Subscribe", buildJsonObject {
      put("query_strings", buildJsonArray { add(JsonPrimitive(query)) })
      put("request_id", KEYFRAME_SUBSCRIPTION_REQUEST_ID)
    })
  }.toString()
}

internal fun parseTicketSpacetimeKeyframeSubscriptionMessage(
  rawMessage: String,
  expectedTicketId: String,
  expectedBackendId: String,
  json: Json = Json { ignoreUnknownKeys = true },
  now: Instant = Instant.now()
): TicketSpacetimeKeyframeSubscriptionMessage {
  if (
    rawMessage.length > MAX_KEYFRAME_MESSAGE_CHARS ||
    rawMessage.toByteArray(Charsets.UTF_8).size > MAX_KEYFRAME_MESSAGE_BYTES
  ) {
    return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
  }
  val root = runCatching { json.parseToJsonElement(rawMessage) as? JsonObject }.getOrNull()
    ?: return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
  if (root.containsKey("IdentityToken")) {
    // The first server message contains the caller's token. Its body is intentionally never read,
    // copied into another object, or surfaced to diagnostics.
    return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.IDENTITY)
  }
  if (root.containsKey("SubscriptionError")) {
    return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
  }
  val kind: TicketSpacetimeKeyframeSubscriptionMessageKind
  val databaseUpdate: JsonObject
  when {
    root.containsKey("InitialSubscription") -> {
      kind = TicketSpacetimeKeyframeSubscriptionMessageKind.APPLIED
      val initial = root["InitialSubscription"] as? JsonObject
        ?: return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
      val requestId = (initial["request_id"] as? JsonPrimitive)?.intOrNull
      if (requestId != KEYFRAME_SUBSCRIPTION_REQUEST_ID) {
        return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
      }
      databaseUpdate = initial["database_update"] as? JsonObject
        ?: return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
    }
    root.containsKey("TransactionUpdate") -> {
      kind = TicketSpacetimeKeyframeSubscriptionMessageKind.UPDATE
      val status = (root["TransactionUpdate"] as? JsonObject)?.get("status") as? JsonObject
      databaseUpdate = status?.get("Committed") as? JsonObject
        ?: return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.IGNORED)
    }
    root.containsKey("TransactionUpdateLight") -> {
      kind = TicketSpacetimeKeyframeSubscriptionMessageKind.UPDATE
      databaseUpdate = (root["TransactionUpdateLight"] as? JsonObject)?.get("update") as? JsonObject
        ?: return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
    }
    else -> return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.IGNORED)
  }

  val commands = mutableListOf<TicketSpacetimeCommand>()
  val tables = databaseUpdate["tables"] as? JsonArray
    ?: return TicketSpacetimeKeyframeSubscriptionMessage(TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR)
  for (tableElement in tables) {
    val table = tableElement as? JsonObject ?: continue
    if (table.string("table_name") != KEYFRAME_SUBSCRIPTION_TABLE) continue
    val updates = table["updates"] as? JsonArray ?: continue
    for (updateElement in updates) {
      val rawUpdate = updateElement as? JsonObject ?: continue
      val update = (rawUpdate["Uncompressed"] as? JsonObject) ?: rawUpdate
      val inserts = update["inserts"] as? JsonArray ?: continue
      for (rowElement in inserts) {
        if (commands.size >= MAX_KEYFRAME_COMMANDS_PER_MESSAGE) break
        val rowText = (rowElement as? JsonPrimitive)?.contentOrNull ?: continue
        if (rowText.toByteArray(Charsets.UTF_8).size > MAX_KEYFRAME_ROW_BYTES) continue
        val row = runCatching { json.parseToJsonElement(rowText) }.getOrNull() ?: continue
        val command = row.toKeyframeCommandOrNull(expectedTicketId, expectedBackendId, now) ?: continue
        commands += command
      }
    }
  }
  return TicketSpacetimeKeyframeSubscriptionMessage(
    kind = kind,
    commands = commands,
    requestId = if (kind == TicketSpacetimeKeyframeSubscriptionMessageKind.APPLIED) {
      KEYFRAME_SUBSCRIPTION_REQUEST_ID
    } else {
      null
    }
  )
}

/**
 * Adds a low-latency view of the existing durable Ticket command rows. The serialized HTTP worker
 * stays active as the recovery path. V3 actions use a small priority consumer so a keyframe that
 * is waiting for stream startup cannot delay command observation; both sources still meet in the
 * shared dispatch ledger before any phone work.
 */
internal class TicketSpacetimeKeyframeSubscription(
  private val scope: CoroutineScope,
  private val config: TicketSpacetimeKeyframeSubscriptionConfig,
  private val json: Json,
  private val onKeyframeCommand: suspend (TicketSpacetimeCommand) -> Unit,
  private val onState: (TicketSpacetimeKeyframeSubscriptionState, String) -> Unit = { _, _ -> },
  httpClient: OkHttpClient? = null,
  private val reconnectDelayMillis: Long = 500L,
  private val maxReconnectDelayMillis: Long = 30_000L,
  private val reconnectJitter: () -> Double = { ThreadLocalRandom.current().nextDouble() }
) {
  private sealed interface SocketEvent {
    data class Parsed(val message: TicketSpacetimeKeyframeSubscriptionMessage) : SocketEvent
    data class Failed(val category: String) : SocketEvent
    data object Closed : SocketEvent
  }

  private val ownsHttpClient = httpClient == null
  private val httpClient = httpClient ?: OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(5, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .pingInterval(15, TimeUnit.SECONDS)
    .build()
  private val ready = AtomicBoolean(false)
  private val stopped = AtomicBoolean(false)
  private val priorityCommands = Channel<TicketSpacetimeCommand>(capacity = 32)
  private val commands = Channel<TicketSpacetimeCommand>(capacity = 64)
  private var job: Job? = null

  init {
    require(reconnectDelayMillis > 0L)
    require(maxReconnectDelayMillis >= reconnectDelayMillis)
    require(config.bearerToken.isNotBlank())
    val baseUrl = config.host.toHttpUrl()
    require(baseUrl.isHttps || baseUrl.host in LOCAL_SUBSCRIPTION_HOSTS) {
      "Ticket Spacetime subscription requires an encrypted non-local host"
    }
  }

  fun start() {
    if (stopped.get()) return
    if (job?.isActive == true) return
    job = scope.launch(Dispatchers.IO) {
      coroutineScope {
        launch { consumePriorityCommands() }
        launch { consumeCommands() }
        connectionLoop()
      }
    }
  }

  suspend fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    val activeJob = job
    job = null
    activeJob?.cancelAndJoin()
    ready.set(false)
    priorityCommands.close()
    commands.close()
    if (ownsHttpClient) {
      httpClient.connectionPool.evictAll()
      httpClient.dispatcher.executorService.shutdown()
    }
  }

  fun isReady(): Boolean = ready.get()

  private suspend fun consumePriorityCommands() {
    for (command in priorityCommands) {
      dispatchSubscribedCommand(command)
    }
  }

  private suspend fun consumeCommands() {
    for (command in commands) {
      dispatchSubscribedCommand(command)
    }
  }

  private suspend fun dispatchSubscribedCommand(command: TicketSpacetimeCommand) {
    try {
      onKeyframeCommand(command)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      onState(TicketSpacetimeKeyframeSubscriptionState.COMMAND_FAILED, ticketOperationalErrorCategory(error))
    }
  }

  private fun enqueueSubscribedCommand(command: TicketSpacetimeCommand): Boolean {
    return if (command.commandType in setOf("ticket_action_v3", "generate_control_code", "control_code_browser_capture", "vivi_reauth")) {
      priorityCommands.trySend(command).isSuccess
    } else {
      commands.trySend(command).isSuccess
    }
  }

  private suspend fun connectionLoop() {
    var consecutiveFailures = 0
    var initialFallbackReported = false
    while (currentCoroutineContext().isActive) {
      val category = try {
        runConnection()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        ticketOperationalErrorCategory(error)
      }
      val reachedReady = ready.getAndSet(false)
      if (reachedReady || !initialFallbackReported) {
        onState(TicketSpacetimeKeyframeSubscriptionState.FALLBACK, category)
        initialFallbackReported = true
      }
      consecutiveFailures = if (reachedReady) 1 else (consecutiveFailures + 1).coerceAtMost(31)
      delay(
        ticketSpacetimeKeyframeReconnectDelayMillis(
          category = category,
          consecutiveFailures = consecutiveFailures,
          baseDelayMillis = reconnectDelayMillis,
          maxDelayMillis = maxReconnectDelayMillis,
          randomUnit = reconnectJitter()
        )
      )
    }
  }

  private suspend fun runConnection(): String {
    val socketEvents = Channel<SocketEvent>(capacity = 16)
    val request = Request.Builder()
      .url(
        config.host.toHttpUrl().newBuilder()
          .addPathSegment("v1")
          .addPathSegment("database")
          .addPathSegment(config.database)
          .addPathSegment("subscribe")
          // The listener intentionally accepts only uncompressed v1 JSON text frames. The row is
          // already durable before it enters this advisory fast path, while the existing poller
          // remains the authoritative recovery and acknowledgement path.
          .addQueryParameter("compression", "None")
          .addQueryParameter("confirmed", "false")
          .build()
      )
      .header("Authorization", "Bearer ${config.bearerToken}")
      .header("Sec-WebSocket-Protocol", KEYFRAME_SUBSCRIPTION_PROTOCOL)
      .build()
    val subscribeMessage = ticketSpacetimeKeyframeSubscribeMessage(
      ticketSpacetimeKeyframeSubscriptionQuery(config.ticketId, config.backendId)
    )
    val listener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        if (response.header("Sec-WebSocket-Protocol") != KEYFRAME_SUBSCRIPTION_PROTOCOL) {
          socketEvents.trySend(SocketEvent.Failed("protocol_mismatch"))
          webSocket.cancel()
          return
        }
        if (!webSocket.send(subscribeMessage)) {
          socketEvents.trySend(SocketEvent.Failed("subscribe_send_failed"))
          webSocket.cancel()
        }
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        val parsed = parseTicketSpacetimeKeyframeSubscriptionMessage(
          rawMessage = text,
          expectedTicketId = config.ticketId,
          expectedBackendId = config.backendId,
          json = json
        )
        if (!socketEvents.trySend(SocketEvent.Parsed(parsed)).isSuccess) {
          webSocket.cancel()
        }
      }

      override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        socketEvents.trySend(SocketEvent.Failed("unexpected_binary_message"))
        webSocket.cancel()
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        socketEvents.trySend(SocketEvent.Closed)
      }

      override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
        val category = if (response?.code in setOf(401, 403)) {
          "authorization_failed"
        } else {
          ticketOperationalErrorCategory(error)
        }
        socketEvents.trySend(SocketEvent.Failed(category))
      }
    }
    val webSocket = httpClient.newWebSocket(request, listener)
    var identityReceived = false
    var matchingSnapshotApplied = false
    try {
      for (event in socketEvents) {
        when (event) {
          is SocketEvent.Parsed -> when (event.message.kind) {
            TicketSpacetimeKeyframeSubscriptionMessageKind.IDENTITY -> {
              if (identityReceived || matchingSnapshotApplied) return "subscription_protocol_error"
              identityReceived = true
            }
            TicketSpacetimeKeyframeSubscriptionMessageKind.IGNORED -> Unit
            TicketSpacetimeKeyframeSubscriptionMessageKind.ERROR -> return "subscription_protocol_error"
            TicketSpacetimeKeyframeSubscriptionMessageKind.APPLIED -> {
              if (
                !identityReceived ||
                matchingSnapshotApplied ||
                event.message.requestId != KEYFRAME_SUBSCRIPTION_REQUEST_ID
              ) {
                return "subscription_protocol_error"
              }
              matchingSnapshotApplied = true
              if (ready.compareAndSet(false, true)) {
                onState(TicketSpacetimeKeyframeSubscriptionState.READY, "live_subscription")
              }
              for (command in event.message.commands) {
                if (!enqueueSubscribedCommand(command)) {
                  return "command_buffer_full"
                }
              }
            }
            TicketSpacetimeKeyframeSubscriptionMessageKind.UPDATE -> {
              if (!matchingSnapshotApplied) return "subscription_protocol_error"
              for (command in event.message.commands) {
                if (!enqueueSubscribedCommand(command)) {
                  return "command_buffer_full"
                }
              }
            }
          }
          is SocketEvent.Failed -> return event.category
          SocketEvent.Closed -> return "socket_closed"
        }
      }
      return "socket_closed"
    } finally {
      socketEvents.close()
      webSocket.cancel()
    }
  }
}

private fun JsonElement.toKeyframeCommandOrNull(
  expectedTicketId: String,
  expectedBackendId: String,
  now: Instant
): TicketSpacetimeCommand? {
  val fields = when (this) {
    is JsonObject -> KEYFRAME_COMMAND_FIELD_NAMES.associateWith { field ->
      val value = this[field] as? JsonPrimitive ?: return null
      if (!value.isString) return null
      value.content
    }
    is JsonArray -> {
      if (size != KEYFRAME_COMMAND_FIELD_NAMES.size) return null
      val values = map {
        val value = it as? JsonPrimitive ?: return null
        if (!value.isString) return null
        value.content
      }
      KEYFRAME_COMMAND_FIELD_NAMES.zip(values).toMap()
    }
    else -> return null
  }
  val id = fields.getValue("id")
  val ticketId = fields.getValue("ticketId")
  val backendId = fields.getValue("backendId")
  val commandType = fields.getValue("commandType")
  val status = fields.getValue("status")
  val payloadJson = fields.getValue("payloadJson")
  val expiresAt = fields.getValue("expiresAt")
  if (
    id.isBlank() || id.length > 512 ||
    ticketId != expectedTicketId || backendId != expectedBackendId ||
    commandType !in LIVE_TICKET_COMMAND_TYPES || status != "pending" ||
    payloadJson.toByteArray(Charsets.UTF_8).size > MAX_KEYFRAME_PAYLOAD_BYTES ||
    ticketSpacetimeCommandExpired(expiresAt, now)
  ) {
    return null
  }
  return TicketSpacetimeCommand(
    id = id,
    ticketId = ticketId,
    backendId = backendId,
    commandType = commandType,
    status = status,
    revision = fields.getValue("revision"),
    reason = fields.getValue("reason"),
    payloadJson = payloadJson.ifBlank { "{}" },
    createdAt = fields.getValue("createdAt"),
    updatedAt = fields.getValue("updatedAt"),
    expiresAt = expiresAt
  )
}

private fun JsonObject.string(key: String): String {
  return (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

private fun ticketSpacetimeSubscriptionSqlLiteral(value: String): String {
  return "'" + value.replace("'", "''") + "'"
}

internal fun ticketSpacetimeKeyframeReconnectDelayMillis(
  category: String,
  consecutiveFailures: Int,
  baseDelayMillis: Long,
  maxDelayMillis: Long,
  randomUnit: Double
): Long {
  require(consecutiveFailures > 0)
  require(baseDelayMillis > 0L)
  require(maxDelayMillis >= baseDelayMillis)
  val longBackoff = category in LONG_SUBSCRIPTION_BACKOFF_CATEGORIES
  var nominalDelay = if (longBackoff) maxDelayMillis else baseDelayMillis
  if (!longBackoff) {
    repeat((consecutiveFailures - 1).coerceAtMost(30)) {
      nominalDelay = if (nominalDelay >= maxDelayMillis / 2L) {
        maxDelayMillis
      } else {
        nominalDelay * 2L
      }
    }
  }
  val boundedRandom = randomUnit.coerceIn(0.0, 1.0)
  val jitterMultiplier = 0.8 + (0.2 * boundedRandom)
  return (nominalDelay * jitterMultiplier).toLong().coerceIn(1L, maxDelayMillis)
}

private val LONG_SUBSCRIPTION_BACKOFF_CATEGORIES = setOf(
  "authorization_failed",
  "protocol_mismatch",
  "subscription_protocol_error"
)
private val LOCAL_SUBSCRIPTION_HOSTS = setOf("localhost", "127.0.0.1", "::1")
private const val KEYFRAME_SUBSCRIPTION_PROTOCOL = "v1.json.spacetimedb"
private const val SUBSCRIBED_COMMAND_ACK_TOMBSTONE_CAPACITY = 1_024
// Ticket action commands live for at most ten minutes. Keep their acknowledged ownership longer
// than that so neither an already-fetched poll row nor a delayed subscription frame can reapply it.
private const val SUBSCRIBED_COMMAND_ACK_TOMBSTONE_TTL_MILLIS = 15 * 60_000L
private val LIVE_TICKET_COMMAND_TYPES = setOf(
  "start", "activity", "keyframe", "recover_stream", "ticket_action_v3",
  "generate_control_code", "control_code_browser_capture", "control_code_result_ack", "vivi_reauth",
  "control_exit", "generate_rigassatiksme_qr_batch", "rigassatiksme_login_start",
  "rigassatiksme_login_sms", "cancel_rigassatiksme_login"
)
private val KEYFRAME_COMMAND_FIELD_NAMES = listOf(
  "id",
  "ticketId",
  "backendId",
  "commandType",
  "status",
  "revision",
  "reason",
  "payloadJson",
  "createdAt",
  "updatedAt",
  "expiresAt"
)
private const val KEYFRAME_SUBSCRIPTION_TABLE = "ticketremote_service_stream_command"
private const val KEYFRAME_SUBSCRIPTION_REQUEST_ID = 0
private const val MAX_KEYFRAME_COMMANDS_PER_MESSAGE = 32
private const val MAX_KEYFRAME_MESSAGE_CHARS = 768 * 1024
private const val MAX_KEYFRAME_MESSAGE_BYTES = 1024 * 1024
private const val MAX_KEYFRAME_ROW_BYTES = 16 * 1024
private const val MAX_KEYFRAME_PAYLOAD_BYTES = 8 * 1024
