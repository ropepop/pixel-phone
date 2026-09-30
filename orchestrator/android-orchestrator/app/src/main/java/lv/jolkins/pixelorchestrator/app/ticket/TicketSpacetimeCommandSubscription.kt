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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

@Serializable
internal enum class TicketSpacetimeCommandSubscriptionMessageKind {
  IDENTITY,
  IGNORED,
  APPLIED,
  UPDATE,
  ERROR
}

@Serializable
internal data class TicketSpacetimeCommandSubscriptionMessage(
  val kind: TicketSpacetimeCommandSubscriptionMessageKind,
  val commands: List<TicketSpacetimeCommand> = emptyList(),
  val requestId: Int? = null,
  val deleted: List<String> = emptyList(),
  val desired: TicketSpacetimeDesiredState? = null,
  val desiredDeleted: Boolean = false,
  val monitoring: TicketMonitoringConfig? = null,
  val monitoringDeleted: Boolean = false
)

@Serializable
internal data class TicketCommandControlSnapshot(
  val commands: List<TicketSpacetimeCommand>,
  val desired: TicketSpacetimeDesiredState?,
  val monitoring: TicketMonitoringConfig? = null
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
  private var inbox: JsonObject? = null
  val changed = Channel<Unit>(Channel.CONFLATED)

  private fun call(operation: String, args: JsonObject = JsonObject(emptyMap())): JsonElement {
    val result = NativeTicketCommand.call(operation,args,inbox).jsonObject
    inbox = result.getValue("inbox").jsonObject
    check(result.getValue("error") == JsonNull) { result.getValue("error").jsonPrimitive.content }
    return result.getValue("answer")
  }

  @Synchronized fun disconnected() { call("disconnect"); changed.trySend(Unit) }

  @Synchronized fun apply(message: TicketSpacetimeCommandSubscriptionMessage) {
    call("apply",buildJsonObject { put("message",NativeTicketCommand.json.encodeToJsonElement(message)) })
    changed.trySend(Unit)
  }

  @Synchronized fun snapshot(): List<TicketSpacetimeCommand>? = controlSnapshot()?.commands

  @Synchronized fun controlSnapshot(): TicketCommandControlSnapshot? = call("snapshot").let {
    if (it == JsonNull) null else NativeTicketCommand.json.decodeFromJsonElement<TicketCommandControlSnapshot>(it)
  }

  @Synchronized fun contains(command: TicketSpacetimeCommand): Boolean =
    call("contains",buildJsonObject { put("command",NativeTicketCommand.json.encodeToJsonElement(command)) }).jsonPrimitive.boolean
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

internal fun ticketSpacetimeKeyframeSubscribeMessage(query: String, desiredQuery: String? = null, monitoringQuery: String? = null): String {
  return buildJsonObject {
    put("Subscribe", buildJsonObject {
      put("query_strings", buildJsonArray {
        add(JsonPrimitive(query))
        desiredQuery?.let { add(JsonPrimitive(it)) }
        monitoringQuery?.let { add(JsonPrimitive(it)) }
      })
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
): TicketSpacetimeCommandSubscriptionMessage =
  NativeTicketCommand.parse(rawMessage, expectedTicketId, expectedBackendId, json, now)

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
      "SELECT * FROM $DESIRED_SUBSCRIPTION_TABLE WHERE id = ${ticketSpacetimeSubscriptionSqlLiteral("${config.ticketId}:${config.backendId}")}",
      "SELECT * FROM $MONITORING_SUBSCRIPTION_TABLE WHERE id = ${ticketSpacetimeSubscriptionSqlLiteral("${config.ticketId}:${config.backendId}")}"
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
private const val DESIRED_SUBSCRIPTION_TABLE = "ticketremote_service_stream_desired_state"
private const val MONITORING_SUBSCRIPTION_TABLE = "ticketremote_monitoring_config"
private const val COMMAND_SUBSCRIPTION_REQUEST_ID = 0
