package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
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

internal enum class TicketSpacetimeCommandSubscriptionState {
  READY,
  DISCONNECTED
}

internal enum class TicketSpacetimeCommandSubscriptionMessageKind {
  IDENTITY,
  IGNORED,
  APPLIED,
  UPDATE,
  ERROR
}

internal data class TicketSpacetimeCommandSubscriptionMessage(
  val kind: TicketSpacetimeCommandSubscriptionMessageKind,
  val commands: List<TicketSpacetimeCommand> = emptyList(),
  val requestId: Int? = null,
  val deleted: List<String> = emptyList(),
  val desired: TicketSpacetimeDesiredState? = null,
  val desiredDeleted: Boolean = false
)

internal data class TicketCommandControlSnapshot(
  val commands: List<TicketSpacetimeCommand>,
  val desired: TicketSpacetimeDesiredState?
)

internal data class TicketSpacetimeCommandSubscriptionConfig(
  val host: String,
  val database: String,
  val bearerToken: String,
  val ticketId: String,
  val backendId: String
)

/** The subscription owns current rows; only the worker executes them. */
internal class TicketCommandInbox {
  private val commands = linkedMapOf<String, TicketSpacetimeCommand>()
  private var ready = false
  private var desired: TicketSpacetimeDesiredState? = null
  val changed = Channel<Unit>(Channel.CONFLATED)

  @Synchronized fun disconnected() {
    ready = false
    commands.clear()
    desired = null
    changed.trySend(Unit)
  }

  @Synchronized fun apply(message: TicketSpacetimeCommandSubscriptionMessage) {
    if (message.kind == TicketSpacetimeCommandSubscriptionMessageKind.APPLIED) {
      commands.clear()
      desired = null
      ready = true
    }
    check(ready) { "command_snapshot_not_ready" }
    message.deleted.forEach(commands::remove)
    message.commands.forEach { commands[it.id] = it }
    if (message.desiredDeleted) desired = null
    message.desired?.let { desired = it }
    check(commands.size <= 128) { "command_snapshot_capacity" }
    changed.trySend(Unit)
  }

  @Synchronized fun snapshot(): List<TicketSpacetimeCommand>? {
    if (!ready) return null
    return commands.values.filterNot { ticketSpacetimeCommandExpired(it.expiresAt, Instant.now()) }
  }

  @Synchronized fun controlSnapshot(): TicketCommandControlSnapshot? = if (!ready) null else
    TicketCommandControlSnapshot(commands.values.filterNot { ticketSpacetimeCommandExpired(it.expiresAt, Instant.now()) }, desired)

  @Synchronized fun contains(command: TicketSpacetimeCommand): Boolean =
    ready && commands[command.id] == command && !ticketSpacetimeCommandExpired(command.expiresAt, Instant.now())
}

/** Terminal results remain until the authoritative snapshot removes the command.
 * A network failure may retry settlement, but must never repeat its phone effect.
 */
internal class TicketCommandResults {
  private val results = java.util.concurrent.ConcurrentHashMap<String, TicketSpacetimeCommandResult>()
  fun peek(id: String): TicketSpacetimeCommandResult? = results[id]
  fun settle(id: String, result: TicketSpacetimeCommandResult) {
    if (result.terminal) results[id] = result
  }
  fun retain(ids: Set<String>) { results.keys.retainAll(ids) }
}

internal fun ticketSpacetimeKeyframeSubscriptionQuery(ticketId: String, backendId: String): String {
  return "SELECT * FROM ticketremote_service_stream_command " +
    "WHERE ticketId = ${ticketSpacetimeSubscriptionSqlLiteral(ticketId)} " +
    "AND backendId = ${ticketSpacetimeSubscriptionSqlLiteral(backendId)} " +
    "AND status = 'pending'"
}

internal fun ticketSpacetimeKeyframeSubscribeMessage(query: String, desiredQuery: String? = null): String {
  return buildJsonObject {
    put("Subscribe", buildJsonObject {
      put("query_strings", buildJsonArray { add(JsonPrimitive(query)); desiredQuery?.let { add(JsonPrimitive(it)) } })
      put("request_id", COMMAND_SUBSCRIPTION_REQUEST_ID)
    })
  }.toString()
}

internal fun parseTicketSpacetimeCommandSubscriptionMessage(
  rawMessage: String,
  expectedTicketId: String,
  expectedBackendId: String,
  json: Json = Json { ignoreUnknownKeys = true },
  now: Instant = Instant.now()
): TicketSpacetimeCommandSubscriptionMessage {
  if (
    rawMessage.length > MAX_KEYFRAME_MESSAGE_CHARS ||
    rawMessage.toByteArray(Charsets.UTF_8).size > MAX_KEYFRAME_MESSAGE_BYTES
  ) {
    return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
  }
  val root = runCatching { json.parseToJsonElement(rawMessage) as? JsonObject }.getOrNull()
    ?: return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
  if (root.containsKey("IdentityToken")) {
    // The first server message contains the caller's token. Its body is intentionally never read,
    // copied into another object, or surfaced to diagnostics.
    return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.IDENTITY)
  }
  if (root.containsKey("SubscriptionError")) {
    return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
  }
  val kind: TicketSpacetimeCommandSubscriptionMessageKind
  val databaseUpdate: JsonObject
  when {
    root.containsKey("InitialSubscription") -> {
      kind = TicketSpacetimeCommandSubscriptionMessageKind.APPLIED
      val initial = root["InitialSubscription"] as? JsonObject
        ?: return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
      val requestId = (initial["request_id"] as? JsonPrimitive)?.intOrNull
      if (requestId != COMMAND_SUBSCRIPTION_REQUEST_ID) {
        return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
      }
      databaseUpdate = initial["database_update"] as? JsonObject
        ?: return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
    }
    root.containsKey("TransactionUpdate") -> {
      kind = TicketSpacetimeCommandSubscriptionMessageKind.UPDATE
      val status = (root["TransactionUpdate"] as? JsonObject)?.get("status") as? JsonObject
      databaseUpdate = status?.get("Committed") as? JsonObject
        ?: return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.IGNORED)
    }
    root.containsKey("TransactionUpdateLight") -> {
      kind = TicketSpacetimeCommandSubscriptionMessageKind.UPDATE
      databaseUpdate = (root["TransactionUpdateLight"] as? JsonObject)?.get("update") as? JsonObject
        ?: return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
    }
    else -> return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.IGNORED)
  }

  val commands = mutableListOf<TicketSpacetimeCommand>()
  val deleted = mutableListOf<String>()
  var desired: TicketSpacetimeDesiredState? = null
  var desiredDeleted = false
  val tables = databaseUpdate["tables"] as? JsonArray
    ?: return TicketSpacetimeCommandSubscriptionMessage(TicketSpacetimeCommandSubscriptionMessageKind.ERROR)
  for (tableElement in tables) {
    val table = tableElement as? JsonObject ?: continue
    val tableName = table.string("table_name")
    if (tableName != COMMAND_SUBSCRIPTION_TABLE && tableName != DESIRED_SUBSCRIPTION_TABLE) continue
    val updates = table["updates"] as? JsonArray ?: continue
    for (updateElement in updates) {
      val rawUpdate = updateElement as? JsonObject ?: continue
      val update = (rawUpdate["Uncompressed"] as? JsonObject) ?: rawUpdate
      for (rowElement in update["deletes"] as? JsonArray ?: JsonArray(emptyList())) {
        val rowText = (rowElement as? JsonPrimitive)?.contentOrNull ?: error("invalid_deleted_command")
        check(rowText.toByteArray(Charsets.UTF_8).size <= MAX_KEYFRAME_ROW_BYTES)
        val row = json.parseToJsonElement(rowText)
        val id = when (row) {
          is JsonObject -> row["id"]
          is JsonArray -> row.firstOrNull()
          else -> null
        } as? JsonPrimitive ?: error("invalid_deleted_command")
        if (tableName == DESIRED_SUBSCRIPTION_TABLE) {
          if (id.content == "$expectedTicketId:$expectedBackendId") desiredDeleted = true
        } else deleted += id.content
      }
      val inserts = update["inserts"] as? JsonArray ?: continue
      for (rowElement in inserts) {
        check(commands.size < MAX_KEYFRAME_COMMANDS_PER_MESSAGE) { "command_update_capacity" }
        val rowText = (rowElement as? JsonPrimitive)?.contentOrNull ?: continue
        if (rowText.toByteArray(Charsets.UTF_8).size > MAX_KEYFRAME_ROW_BYTES) continue
        val row = runCatching { json.parseToJsonElement(rowText) }.getOrNull() ?: continue
        if (tableName == DESIRED_SUBSCRIPTION_TABLE) {
          row.toStreamDesiredOrNull(expectedTicketId, expectedBackendId)?.let { desired = it }
          continue
        }
        val command = row.toKeyframeCommandOrNull(expectedTicketId, expectedBackendId, now) ?: continue
        commands += command
      }
    }
  }
  return TicketSpacetimeCommandSubscriptionMessage(
    kind = kind,
    commands = commands,
    deleted = deleted,
    desired = desired,
    desiredDeleted = desiredDeleted,
    requestId = if (kind == TicketSpacetimeCommandSubscriptionMessageKind.APPLIED) {
      COMMAND_SUBSCRIPTION_REQUEST_ID
    } else {
      null
    }
  )
}

/** Reconnecting transport for one authoritative current-command snapshot. */
internal class TicketSpacetimeCommandSubscription(
  private val scope: CoroutineScope,
  private val config: TicketSpacetimeCommandSubscriptionConfig,
  private val json: Json,
  private val inbox: TicketCommandInbox,
  private val onState: (TicketSpacetimeCommandSubscriptionState, String) -> Unit = { _, _ -> },
  httpClient: OkHttpClient? = null,
  private val reconnectDelayMillis: Long = 500L,
  private val maxReconnectDelayMillis: Long = 30_000L,
  private val reconnectJitter: () -> Double = { ThreadLocalRandom.current().nextDouble() }
) {
  private sealed interface SocketEvent {
    data class Parsed(val message: TicketSpacetimeCommandSubscriptionMessage) : SocketEvent
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
      connectionLoop()
    }
  }

  suspend fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    val activeJob = job
    job = null
    activeJob?.cancelAndJoin()
    ready.set(false)
    inbox.disconnected()
    if (ownsHttpClient) {
      httpClient.connectionPool.evictAll()
      httpClient.dispatcher.executorService.shutdown()
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
      inbox.disconnected()
      val reachedReady = ready.getAndSet(false)
      if (reachedReady || !initialFallbackReported) {
        onState(TicketSpacetimeCommandSubscriptionState.DISCONNECTED, category)
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
          .addQueryParameter("compression", "None")
          .addQueryParameter("confirmed", "false")
          .build()
      )
      .header("Authorization", "Bearer ${config.bearerToken}")
      .header("Sec-WebSocket-Protocol", COMMAND_SUBSCRIPTION_PROTOCOL)
      .build()
    val subscribeMessage = ticketSpacetimeKeyframeSubscribeMessage(
      ticketSpacetimeKeyframeSubscriptionQuery(config.ticketId, config.backendId),
      "SELECT * FROM $DESIRED_SUBSCRIPTION_TABLE WHERE id = ${ticketSpacetimeSubscriptionSqlLiteral("${config.ticketId}:${config.backendId}")}"
    )
    val listener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        if (response.header("Sec-WebSocket-Protocol") != COMMAND_SUBSCRIPTION_PROTOCOL) {
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
        val parsed = runCatching {
          parseTicketSpacetimeCommandSubscriptionMessage(text, config.ticketId, config.backendId, json)
        }.getOrElse {
          socketEvents.close(it)
          webSocket.cancel()
          return
        }
        if (!socketEvents.trySend(SocketEvent.Parsed(parsed)).isSuccess) {
          socketEvents.close(IllegalStateException("command_updates_overflow"))
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
    val snapshotDeadline = System.nanoTime() / 1_000_000L + 8_000L
    try {
      while (true) {
        val event = if (matchingSnapshotApplied) socketEvents.receive() else {
          val remaining = snapshotDeadline - System.nanoTime() / 1_000_000L
          if (remaining <= 0L) return "command_snapshot_timeout"
          withTimeoutOrNull(remaining) { socketEvents.receive() } ?: return "command_snapshot_timeout"
        }
        when (event) {
          is SocketEvent.Parsed -> when (event.message.kind) {
            TicketSpacetimeCommandSubscriptionMessageKind.IDENTITY -> {
              if (identityReceived || matchingSnapshotApplied) return "subscription_protocol_error"
              identityReceived = true
            }
            TicketSpacetimeCommandSubscriptionMessageKind.IGNORED -> Unit
            TicketSpacetimeCommandSubscriptionMessageKind.ERROR -> return "subscription_protocol_error"
            TicketSpacetimeCommandSubscriptionMessageKind.APPLIED -> {
              if (
                !identityReceived ||
                matchingSnapshotApplied ||
                event.message.requestId != COMMAND_SUBSCRIPTION_REQUEST_ID
              ) {
                return "subscription_protocol_error"
              }
              matchingSnapshotApplied = true
              if (ready.compareAndSet(false, true)) {
                onState(TicketSpacetimeCommandSubscriptionState.READY, "live_subscription")
              }
              inbox.apply(event.message)
            }
            TicketSpacetimeCommandSubscriptionMessageKind.UPDATE -> {
              if (!matchingSnapshotApplied) return "subscription_protocol_error"
              inbox.apply(event.message)
            }
          }
          is SocketEvent.Failed -> return event.category
          SocketEvent.Closed -> return "socket_closed"
        }
      }
    } finally {
      inbox.disconnected()
      socketEvents.close()
      webSocket.cancel()
    }
  }
}

private fun JsonElement.toStreamDesiredOrNull(ticketId: String, backendId: String): TicketSpacetimeDesiredState? {
  val fields = when (this) {
    is JsonObject -> this
    is JsonArray -> {
      check(size == STREAM_DESIRED_FIELDS.size) { "invalid_desired_state_shape" }
      STREAM_DESIRED_FIELDS.zip(this).toMap()
    }
    else -> error("invalid_desired_state_shape")
  }
  fun string(name: String) = (fields[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
  if (string("id") != "$ticketId:$backendId" || string("ticketId") != ticketId || string("backendId") != backendId) return null
  val phase = ticketSpacetimeOptionalString(fields["coldRestartPhase"])
  check(phase in setOf("", "quiescing", "stopping", "confirmed", "reloading", "asleep", "live", "failed")) { "invalid_cold_restart_phase" }
  return TicketSpacetimeDesiredState(
    desiredActive = (fields["desiredActive"] as? JsonPrimitive)?.booleanOrNull ?: error("invalid_desired_active"),
    viewerCount = (fields["viewerCount"] as? JsonPrimitive)?.intOrNull ?: error("invalid_desired_viewers"),
    reason = string("reason"), revision = string("revision"), updatedAt = string("updatedAt"),
    coldRestartId = ticketSpacetimeOptionalString(fields["coldRestartId"]), coldRestartPhase = phase
  )
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
private const val COMMAND_SUBSCRIPTION_PROTOCOL = "v1.json.spacetimedb"
private val LIVE_TICKET_COMMAND_TYPES = setOf(
  "start", "cold_stop", "ticket_action_v3",
  "generate_control_code", "control_code_browser_capture", "vivi_reauth",
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
private const val COMMAND_SUBSCRIPTION_TABLE = "ticketremote_service_stream_command"
private const val DESIRED_SUBSCRIPTION_TABLE = "ticketremote_stream_desired_state"
private val STREAM_DESIRED_FIELDS = listOf("id", "ticketId", "backendId", "desiredActive", "viewerCount", "reason", "revision", "updatedBy", "updatedAt", "coldRestartId", "coldRestartPhase", "coldRestartStartedAt", "coldRestartError")
private const val COMMAND_SUBSCRIPTION_REQUEST_ID = 0
private const val MAX_KEYFRAME_COMMANDS_PER_MESSAGE = 128
private const val MAX_KEYFRAME_MESSAGE_CHARS = 768 * 1024
private const val MAX_KEYFRAME_MESSAGE_BYTES = 1024 * 1024
private const val MAX_KEYFRAME_ROW_BYTES = 16 * 1024
private const val MAX_KEYFRAME_PAYLOAD_BYTES = 8 * 1024
