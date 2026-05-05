package lv.jolkins.pixelorchestrator.app.ticket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lv.jolkins.pixelorchestrator.app.MainActivity
import lv.jolkins.pixelorchestrator.app.SupervisorService
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSelector
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessControl
import lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessState
import lv.jolkins.pixelorchestrator.app.phoneautomation.TouchBrightnessRuntimeState
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class TicketStreamService : Service() {
  private data class TicketClientInfo(
    val video: Boolean,
    val viewerId: String?,
    val pageId: String?,
    val pageVersion: String?,
    val generation: Long
  )

  private class TicketVideoSendState {
    var inFlight: Boolean = false
    var inFlightSinceMillis: Long = 0L
    var pendingFrame: ByteArray? = null
    var pendingKeyFrame: Boolean = false
    var waitingForKeyFrame: Boolean = false
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val rootExecutor = TicketRootCommandWorker()
  private val inputRootExecutor = TicketRootCommandWorker()
  private val foregroundRootExecutor = TicketRootCommandWorker()
  private val runtimeStateStore = TicketRuntimeStateStore(rootExecutor, json)
  private val serverMutex = Mutex()
  private val controlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val videoClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val clientInfo = mutableMapOf<TicketWebSocket, TicketClientInfo>()
  private val videoSendStates = mutableMapOf<TicketWebSocket, TicketVideoSendState>()
  private val recentClientTelemetry = ArrayDeque<Pair<Long, String>>()
  private val recentTicketEvents = ArrayDeque<Triple<Long, String, String>>()
  private val encoderLock = Any()
  private val sessionMutex = Mutex()
  private val running = AtomicBoolean(false)
  private val rootCaptureEngine = TicketRootCaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootCaptureFrame,
    onStateChanged = { health ->
      rootCaptureSnapshot = health
      if (streamActive) {
        broadcastStatus()
        persistRuntimeState("root_capture_state")
      }
    }
  )
  private val rootPngCaptureEngine = TicketRootPngCaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootPngCaptureFrame,
    onStateChanged = { health ->
      rootCaptureSnapshot = health
      if (streamActive) {
        broadcastStatus()
        persistRuntimeState("root_png_capture_state")
      }
    }
  )
  private val rootAv1CaptureEngine = TicketRootAv1CaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootAv1CaptureFrame,
    onStateChanged = { health ->
      rootCaptureSnapshot = health
      if (streamActive) {
        broadcastStatus()
        persistRuntimeState("root_av1_capture_state")
      }
    }
  )
  private val rootFfmpegH264CaptureEngine = TicketRootFfmpegH264CaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootFfmpegH264CaptureFrame,
    onStateChanged = { health ->
      ffmpegCaptureSnapshot = health
      if (streamActive) {
        broadcastStatus()
        persistRuntimeState("root_ffmpeg_h264_capture_state")
      }
    }
  )

  private var serverJob: Job? = null
  private var serverSocket: ServerSocket? = null
  private var mediaProjection: MediaProjection? = null
  private var mediaProjectionCallback: MediaProjection.Callback? = null
  private var encoder: MediaCodec? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var encoderJob: Job? = null
  private var streamSize: TicketStreamSize? = null
  private var ticketBrightnessState: ScreenBrightnessState? = null
  private var brightnessGuardJob: Job? = null
  @Volatile private var ticketBrightnessGuardActive: Boolean = false
  @Volatile private var ticketBrightnessGuardLastReason: String? = null
  @Volatile private var ticketBrightnessGuardLastMessage: String = "Ticket brightness guard is inactive"
  @Volatile private var ticketBrightnessGuardLastEnforcedAtMillis: Long = 0L
  @Volatile private var ticketBrightnessGuardFailures: Long = 0L
  @Volatile private var ticketBrightnessGuardCurrentDisplayPercent: Float? = null
  @Volatile private var ticketBrightnessGuardCurrentPanelBrightness: Int? = null
  @Volatile private var ticketBrightnessGuardCurrentPanelMaxBrightness: Int? = null
  private var inactivityJob: Job? = null
  private var foregroundGuardJob: Job? = null
  private var clientDisconnectStopJob: Job? = null
  private var postRemoteTapForegroundCheckJob: Job? = null
  private var controlCodeSoftCheckJob: Job? = null
  private var ticketScreenWakeLock: PowerManager.WakeLock? = null
  private val viviStateMemory = TicketViviStateMemory()
  private lateinit var ticketRecoveryCoordinator: TicketRecoveryCoordinator
  private lateinit var ticketAutopilot: TicketAutopilot
  @Volatile private var viviForegroundGraceUntilMillis: Long = 0L
  @Volatile private var lastViviPageEnforceAtMillis: Long = 0L
  @Volatile private var cachedForegroundViolationReason: String? = null
  @Volatile private var cachedForegroundCheckedAtMillis: Long = 0L
  @Volatile private var controlCodePopupReadyUntilMillis: Long = 0L
  @Volatile private var startupDisconnectGraceUntilMillis: Long = 0L
  @Volatile private var ticketSessionState: String = TICKET_SESSION_IDLE
  @Volatile private var ticketSessionStateChangedAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var ticketSessionStateReason: String = "init"
  @Volatile private var lastOverBudgetTicketState: String? = null
  @Volatile private var lastOverBudgetTicketStateDurationMillis: Long? = null
  @Volatile private var lastOverBudgetTicketStateReason: String? = null
  @Volatile private var pendingStartAfterProjection: Boolean = false
  @Volatile private var projectionPrewarmPending: Boolean = false
  @Volatile private var mediaProjectionConsumed: Boolean = false
  @Volatile private var streamActive: Boolean = false
  @Volatile private var activeCaptureMode: String = CAPTURE_MODE_IDLE
  @Volatile private var fallbackReason: String? = null
  @Volatile private var rootCaptureSnapshot: TicketRootCaptureHealth = TicketRootCaptureHealth()
  @Volatile private var ffmpegCaptureSnapshot: TicketFfmpegHealth = TicketFfmpegHealth()
  @Volatile private var inputGateAllowed: Boolean = false
  @Volatile private var inputGateReason: String = "no_active_control"
  @Volatile private var inputGateChangedAtMillis: Long = 0L
  @Volatile private var controlCodeModeActive: Boolean = false
  @Volatile private var controlCodeModeEntryId: Long = 0L
  @Volatile private var controlCodeModeEnteredAtMillis: Long = 0L
  @Volatile private var controlCodeTransitionGraceUntilMillis: Long = 0L
  @Volatile private var controlExitPopupLikelyUntilMillis: Long = 0L
  @Volatile private var notificationLockdownActive: Boolean = false
  @Volatile private var notificationLockdownReason: String = "inactive"
  @Volatile private var notificationLockdownChangedAtMillis: Long = 0L
  @Volatile private var lastViewerInputAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var lastSessionStopReason: String? = null
  @Volatile private var lastForegroundViolationReason: String? = null
  @Volatile private var foregroundViolationCount: Int = 0
  @Volatile private var lastForegroundRecoveryAtMillis: Long = 0L
  @Volatile private var lastTicketScreenWakeAtMillis: Long = 0L
  @Volatile private var lastMessage: String = "Ticket server is starting"
  @Volatile private var capturePermissionLaunchedAtMillis: Long = 0L
  @Volatile private var lastEncoderStartAtMillis: Long = 0L
  @Volatile private var lastConfigSentAtMillis: Long = 0L
  @Volatile private var lastFrameEncodedAtMillis: Long = 0L
  @Volatile private var lastKeyFrameEncodedAtMillis: Long = 0L
  @Volatile private var lastFrameSentAtMillis: Long = 0L
  @Volatile private var lastKeyFrameRequestedAtMillis: Long = 0L
  @Volatile private var lastVideoClientConnectedAtMillis: Long = 0L
  @Volatile private var secureWindowCaptureBypassActive: Boolean = false
  @Volatile private var secureWindowCaptureBypassMessage: String = "Secure-window capture bypass is inactive"
  @Volatile private var encodedFrames: Long = 0L
  @Volatile private var sentFrames: Long = 0L
  @Volatile private var keyFrames: Long = 0L
  @Volatile private var keyFrameRequests: Long = 0L
  @Volatile private var droppedVideoFrames: Long = 0L
  @Volatile private var slowVideoWrites: Long = 0L
  @Volatile private var closedSlowVideoClients: Long = 0L
  @Volatile private var replacedClientSockets: Long = 0L
  @Volatile private var lastClientGeneration: Long = 0L
  @Volatile private var streamEpoch: Long = 0L
  @Volatile private var frameSequence: Long = 0L
  @Volatile private var latestKeyFrameStreamEpoch: Long = 0L
  @Volatile private var latestKeyFrameSequence: Long = 0L
  @Volatile private var latestKeyFrameEnvelope: ByteArray? = null
  @Volatile private var latestKeyFrameAtMillis: Long = 0L
  @Volatile private var latestKeyFrameTimestampUs: Long = 0L
  @Volatile private var lastFrameBytes: Int = 0
  @Volatile private var lastKeyFrameBytes: Int = 0
  @Volatile private var estimatedSendBitrate: Long = 0L
  @Volatile private var sendBitrateWindowStartedAtMillis: Long = 0L
  @Volatile private var sendBitrateWindowBytes: Long = 0L
  @Volatile private var lastInputCommandReason: String? = null
  @Volatile private var lastInputCommandDurationMillis: Long? = null
  @Volatile private var lastInputCommandCompletedAtMillis: Long = 0L
  @Volatile private var lastInputResultId: String? = null
  @Volatile private var lastInputResultKind: String? = null
  @Volatile private var lastInputResultAccepted: Boolean? = null
  @Volatile private var lastInputResultReason: String? = null
  @Volatile private var lastInputResultTotalDurationMillis: Long? = null
  @Volatile private var lastInputResultCompletedAtMillis: Long = 0L
  @Volatile private var duplicateInputResultCount: Long = 0L
  @Volatile private var lastDuplicateInputId: String? = null
  @Volatile private var lastDuplicateInputResultAtMillis: Long = 0L
  private val recentInputResultMessages = mutableMapOf<String, String>()
  private val recentInputResultOrder = ArrayDeque<String>()
  @Volatile private var lastControlExitCleanupReason: String? = null
  @Volatile private var lastControlExitCleanupDetectedState: String? = null
  @Volatile private var lastControlExitCleanupCloseAction: String? = null
  @Volatile private var lastControlExitCleanupDurationMillis: Long? = null
  @Volatile private var lastControlExitCleanupCompletedAtMillis: Long = 0L
  @Volatile private var lastControlExitCleanupVerificationResult: String? = null
  @Volatile private var lastControlExitCleanupSucceeded: Boolean? = null
  @Volatile private var lastControlExitCleanupFreshFrameRequested: Boolean = false
  @Volatile private var viviHardResetCount: Long = 0L
  @Volatile private var lastViviHardResetReason: String? = null
  @Volatile private var lastViviHardResetAtMillis: Long = 0L
  @Volatile private var lastLoadingPhase: String? = null
  @Volatile private var lastLoadingDurationMillis: Long? = null
  @Volatile private var lastLoadingCompletedAtMillis: Long = 0L
  @Volatile private var lastLoadingOverBudgetPhase: String? = null
  @Volatile private var lastLoadingOverBudgetDurationMillis: Long? = null
  @Volatile private var lastLoadingOverBudgetAtMillis: Long = 0L
  @Volatile private var lastRootHtmlRequestAtMillis: Long = 0L
  @Volatile private var lastBootstrapRequestAtMillis: Long = 0L
  @Volatile private var lastCacheCleanupRequestAtMillis: Long = 0L
  @Volatile private var lastClientPageVersion: String? = null
  @Volatile private var lastClientPageVersionAtMillis: Long = 0L

  override fun onCreate() {
    super.onCreate()
    ticketRecoveryCoordinator = TicketRecoveryCoordinator(
      context = this,
      scope = serviceScope,
      rootExecutor = rootExecutor,
      runInput = ::runNonTouchInput,
      collapseSystemUi = { reason -> collapseNotificationShade(reason) },
      scheduleBrightnessGuard = ::scheduleTicketBrightnessGuard,
      stateMemory = viviStateMemory,
      returnToOrchestrator = ::returnToOrchestrator,
      onHardReset = ::recordViviHardReset,
      onRecoveryResult = ::onTicketRecoveryResult
    )
    ticketAutopilot = TicketAutopilot(
      context = this,
      rootExecutor = rootExecutor,
      runInput = ::runNonTouchInput,
      collapseSystemUi = { reason -> collapseNotificationShade(reason) },
      scheduleBrightnessGuard = ::scheduleTicketBrightnessGuard,
      stateMemory = viviStateMemory,
      onHardReset = ::recordViviHardReset
    )
    ensureNotificationChannel()
    serviceScope.launch {
      rootFfmpegH264CaptureEngine.probe()
      rootAv1CaptureEngine.probe()
      rootPngCaptureEngine.probe()
      rootCaptureEngine.probe()
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      TicketScreenConfig.ACTION_STOP_SERVER -> {
        stopLocalServer()
        stopSelf()
        return START_NOT_STICKY
      }
      TicketScreenConfig.ACTION_MEDIA_PROJECTION_RESULT -> handleProjectionResult(intent)
      else -> startServer()
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    val serviceEnabled = runCatching { TicketServicePreferencesStore(this).load().enabled }.getOrDefault(false)
    brightnessGuardJob?.cancel()
    brightnessGuardJob = null
    ticketBrightnessGuardActive = false
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    cancelInactivityTimer()
    cancelForegroundGuard()
    postRemoteTapForegroundCheckJob?.cancel()
    postRemoteTapForegroundCheckJob = null
    if (::ticketRecoveryCoordinator.isInitialized) {
      ticketRecoveryCoordinator.cancel()
    }
    if (serviceEnabled) {
      runCatching { runBlocking { enforceTicketSafeBrightness("service_destroyed_service_enabled") } }
    } else {
      runCatching { runBlocking { restoreTicketBrightness("service_destroyed_service_off") } }
      PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(null)
    }
    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(false)
    rootFfmpegH264CaptureEngine.stop("service_destroyed")
    runCatching { runBlocking { rootFfmpegH264CaptureEngine.cleanupStaleProcesses() } }
    rootAv1CaptureEngine.stop("service_destroyed")
    runCatching { runBlocking { rootAv1CaptureEngine.cleanupStaleProcesses() } }
    rootPngCaptureEngine.stop("service_destroyed")
    runCatching { runBlocking { rootPngCaptureEngine.cleanupStaleProcesses() } }
    rootCaptureEngine.stop("service_destroyed")
    runCatching { runBlocking { rootCaptureEngine.cleanupStaleProcesses() } }
    closeAllClients("service_destroyed")
    stopEncoder()
    stopProjection()
    runCatching { runBlocking { disableSecureWindowCaptureBypass() } }
    runCatching { runBlocking { disableNotificationLockdown("service_destroyed") } }
    releaseTicketScreenAwake()
    stopLocalServer()
    inputRootExecutor.close()
    foregroundRootExecutor.close()
    rootExecutor.close()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startServer() {
    if (!running.compareAndSet(false, true)) {
      return
    }
    serverJob = serviceScope.launch {
      serverMutex.withLock {
        try {
          val socket = ServerSocket().apply {
            reuseAddress = true
            bind(
              InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                TicketScreenConfig.SERVICE_PORT
              ),
              SERVER_BACKLOG
            )
          }
          serverSocket = socket
          lastMessage = "Ticket server is listening on 127.0.0.1:${TicketScreenConfig.SERVICE_PORT}"
          while (running.get()) {
            val client = try {
              socket.accept()
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Throwable) {
              break
            }
            serviceScope.launch {
              handleHttpClient(client)
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          running.set(false)
          lastMessage = "Ticket server failed to start: ${error.message ?: error::class.java.simpleName}"
          Log.e(TAG, "ticket_server_start_failed", error)
        } finally {
          runCatching { serverSocket?.close() }
          serverSocket = null
        }
      }
    }
  }

  private fun stopLocalServer() {
    running.set(false)
    serverJob?.cancel()
    serverJob = null
    runCatching { serverSocket?.close() }
    serverSocket = null
    lastMessage = "Ticket server is stopped"
  }

  private suspend fun handleHttpClient(socket: Socket) {
    socket.soTimeout = SOCKET_TIMEOUT_MILLIS
    val input = BufferedInputStream(socket.getInputStream())
    val output = BufferedOutputStream(socket.getOutputStream())
    runCatching {
      val requestLine = input.readAsciiLine()
      if (requestLine.isBlank()) {
        return@runCatching
      }
      val parts = requestLine.split(" ")
      val method = parts.getOrNull(0).orEmpty()
      val target = parts.getOrNull(1).orEmpty()
      val path = target.substringBefore("?")
      val query = target.substringAfter("?", missingDelimiterValue = "")
      val headers = mutableMapOf<String, String>()
      while (true) {
        val line = input.readAsciiLine()
        if (line.isBlank()) break
        val separator = line.indexOf(':')
        if (separator > 0) {
          headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
      }
      Log.i(TAG, "ticket_http_request method=$method path=$path upgrade=${headers["upgrade"].orEmpty()}")
      if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true &&
        (path == "/api/v1/session" || path == "/api/v1/stream")
      ) {
        acceptWebSocket(socket, input, output, headers, query = query, video = path == "/api/v1/stream")
        return@runCatching
      }
      val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
      val body = if (bodyLength > 0) input.readFullyBytes(bodyLength) else ByteArray(0)
      val bodyText = body.toString(Charsets.UTF_8)
      when {
        method == "GET" && path == "/" -> {
          lastRootHtmlRequestAtMillis = SystemClock.elapsedRealtime()
          sendHtml(output, browserPage())
        }
        method == "GET" && path == "/api/v1/bootstrap" -> {
          lastBootstrapRequestAtMillis = SystemClock.elapsedRealtime()
          sendJsonPayload(output, bootstrapPayload(), clearSiteCache = false)
        }
        method == "GET" && path == "/api/v1/cache-cleanup" -> {
          lastCacheCleanupRequestAtMillis = SystemClock.elapsedRealtime()
          sendJsonPayload(output, bootstrapPayload(), clearSiteCache = true)
        }
        method == "GET" && path == "/api/v1/health" -> sendJson(output, health())
        method == "POST" && path == "/api/v1/session/start" -> sendJson(output, startTicketSession())
        method == "POST" && path == "/api/v1/session/stop" -> sendJson(output, handleBrowserStopRequest(bodyText))
        method == "POST" && path == "/api/v1/client-log" -> {
          val message = bodyText.take(MAX_CLIENT_LOG_BYTES)
          recordClientTelemetry(message)
          Log.i(TAG, "ticket_client_log $message")
          sendJsonPayload(output, """{"ok":true}""")
        }
        else -> sendText(output, 404, "not found")
      }
    }.onFailure { error ->
      Log.w(TAG, "http_request_failed", error)
    }
    if (!socket.isClosed) {
      runCatching { socket.close() }
    }
  }

  private suspend fun acceptWebSocket(
    socket: Socket,
    input: BufferedInputStream,
    output: BufferedOutputStream,
    headers: Map<String, String>,
    query: String,
    video: Boolean
  ) {
    val key = headers["sec-websocket-key"].orEmpty()
    if (key.isBlank()) {
      sendText(output, 400, "missing websocket key")
      return
    }
    output.write(
      buildString {
        append("HTTP/1.1 101 Switching Protocols\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Accept: ${TicketWebSocket.acceptKey(key)}\r\n")
        append("\r\n")
      }.toByteArray(Charsets.ISO_8859_1)
    )
    output.flush()
    socket.soTimeout = 0
    Log.i(TAG, "ticket_websocket_open kind=${if (video) "video" else "control"} clients=${totalClientCount() + 1}")

    lateinit var client: TicketWebSocket
    val info = TicketClientInfo(
      video = video,
      viewerId = queryParam(query, "viewer"),
      pageId = queryParam(query, "page"),
      pageVersion = queryParam(query, "pageVersion"),
      generation = nextClientGeneration()
    )
    client = TicketWebSocket(
      socket = socket,
      input = input,
      output = output,
      onText = { message ->
        if (video) {
          handleVideoClientCommand(client, message)
        } else {
          handleClientCommand(client, message)
        }
      },
      onClose = {
        if (video) {
          videoClients.remove(client)
        } else {
          controlClients.remove(client)
        }
        synchronized(clientInfo) {
          clientInfo.remove(client)
        }
        synchronized(videoSendStates) {
          videoSendStates.remove(client)
        }
        Log.i(TAG, "ticket_websocket_close kind=${if (video) "video" else "control"} clients=${totalClientCount()}")
        if (totalClientCount() == 0) {
          scheduleClientDisconnectGrace()
        }
      }
    )
    closeDuplicateViewerClients(info)
    if (video) {
      videoClients.add(client)
      lastVideoClientConnectedAtMillis = SystemClock.elapsedRealtime()
    } else {
      controlClients.add(client)
    }
    synchronized(clientInfo) {
      clientInfo[client] = info
    }
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    if (ticketSessionOpen()) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "client_connected")
      recordTicketEvent(
        "client_connected",
        "${if (video) "video" else "control"} generation=${info.generation} viewer=${info.viewerId.orEmpty()} page_version=${info.pageVersion.orEmpty()}"
      )
    }
    if (!video && ticketSessionOpen()) {
      sendStatus(client)
      sendInactivityStatus(client)
    }
    if (video) {
      streamSize?.let { size ->
        sendConfigAndWarmStart(client, size)
      }
    }
    ensureEncoderIfPossible()
    client.readLoop()
  }

  private suspend fun handleClientCommand(client: TicketWebSocket, message: String) {
    val element = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "start" -> {
        startTicketSession()
        sendStatus(client)
        sendInactivityStatus(client)
      }
      "stop" -> {
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val explicit = element["explicit"]?.jsonPrimitive?.booleanOrNull == true ||
          reason == TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP
        if (explicit) {
          stopTicketSession(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP)
        } else {
          noteClientDetached("browser_ws_stop_without_explicit")
        }
        sendStatus(client)
        sendInactivityStatus(client)
      }
      "activity" -> {
        markViewerInput("browser_activity")
        sendInactivityStatus(client)
      }
      "tap" -> {
        val x = element["x"]?.jsonPrimitive?.intOrNull ?: return
        val y = element["y"]?.jsonPrimitive?.intOrNull ?: return
        val inputId = element["inputId"]?.jsonPrimitive?.contentOrNull
        tap(inputId, x, y)
      }
      "key" -> {
        val key = element["key"]?.jsonPrimitive?.contentOrNull ?: return
        val inputId = element["inputId"]?.jsonPrimitive?.contentOrNull
        handleRemoteKey(inputId, key)
      }
      "keyframe" -> requestKeyFrame(element["reason"]?.jsonPrimitive?.contentOrNull ?: "browser_request")
      "restart_stream" -> {
        restartActiveStream("browser_restart_stream")
        sendStatus(client)
      }
      "reset_ticket" -> {
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull ?: "remote_reset_ticket"
        if (isSoftControlExitReason(reason)) {
          scheduleControlExitSoftSettle(reason)
        } else {
          scheduleTicketRecovery(reason, TicketRecoveryMode.FRESH_RESET)
        }
        sendStatus(client)
      }
      "control_exit" -> {
        scheduleControlExitSoftSettle(element["reason"]?.jsonPrimitive?.contentOrNull ?: "control_exit")
        sendStatus(client)
      }
      "long_press", "longpress", "hold" -> {
        blockLongPress()
      }
      "swipe" -> {
        blockSwipe()
      }
      "keepalive" -> if (element["active"]?.jsonPrimitive?.booleanOrNull == true) sendStatus(client)
    }
  }

  private fun handleVideoClientCommand(client: TicketWebSocket, message: String) {
    val element = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "keyframe" -> sendCachedKeyFrameOrRequest(client, element["reason"]?.jsonPrimitive?.contentOrNull ?: "video_client_request")
    }
  }

  private suspend fun startTicketSession(): TicketSessionResponse = sessionMutex.withLock {
    if (!TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)) {
      return@withLock TicketSessionResponse(
        ok = false,
        state = "vivi_missing",
        message = "ViVi is not installed from a local Pixel app store yet"
      )
    }
    if (streamActive) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "session_start_already_active")
      lastSessionStopReason = null
      markViewerInput("session_start_already_active")
      lastMessage = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_PNG -> "Ticket session is active through root lossless capture"
        CAPTURE_MODE_ROOT_FFMPEG_H264 -> "Ticket session is active through FFmpeg H.264 capture"
        else -> "Ticket session is active"
      }
      scheduleTicketBrightnessGuard("session_start_already_active")
      startForegroundGuard()
      ensureEncoderIfPossible()
      broadcastStatus()
      persistRuntimeState("session_start_already_active")
      return@withLock TicketSessionResponse(ok = true, state = "active", message = lastMessage)
    }
    val ffmpegCaptureAvailable = rootFfmpegH264CaptureEngine.probe()
    val rootAv1CaptureAvailable = rootAv1CaptureEngine.probe()
    val rootPngCaptureAvailable = rootPngCaptureEngine.probe()
    val rootCaptureAvailable = rootCaptureEngine.probe()
    val av1HardwareAvailable = TicketAv1Support.isHardwareEncoderAvailable()
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_requested")
    recordTicketEvent(
      "session_start_requested",
      "ffmpeg_h264_supported=$ffmpegCaptureAvailable root_av1_supported=$rootAv1CaptureAvailable root_png_supported=$rootPngCaptureAvailable root_h264_supported=$rootCaptureAvailable av1_supported=$av1HardwareAvailable"
    )
    lastSessionStopReason = null
    rememberTicketBrightnessState()
    suppressBlackoutOverlayForRemote()
    requestTicketScreenWake("session_start")
    enableNotificationLockdown("session_start")
    enableSecureWindowCaptureBypass()
    scheduleTicketBrightnessGuard("session_start")
    if (!rootPngCaptureAvailable) {
      fallbackReason = "Root lossless capture is unavailable"
      pendingStartAfterProjection = false
      stopProjection()
      cancelInactivityTimer()
      disableSecureWindowCaptureBypass()
      disableNotificationLockdown("capture_unavailable")
      scheduleTicketBrightnessGuard("capture_unavailable")
      releaseBlackoutOverlaySuppression()
      lastMessage = "Root lossless capture is unavailable; stream was not started"
      activeCaptureMode = CAPTURE_MODE_IDLE
      updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, "capture_unavailable_root_png_required")
      recordTicketEvent("session_unavailable", fallbackReason.orEmpty())
      persistRuntimeState("capture_unavailable_root_png_required")
      return@withLock TicketSessionResponse(
        ok = false,
        state = "capture_unavailable",
        message = lastMessage
      )
    }
    pendingStartAfterProjection = false
    projectionPrewarmPending = false
    fallbackReason = null
    stopProjection()
    streamActive = true
    activeCaptureMode = CAPTURE_MODE_ROOT_PNG
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_root_png_prepare")
    markViewerInput("session_start_root_png_prepare")
    lastMessage = "Preparing ViVi for root lossless capture"
    recordTicketEvent("session_started", "mode=$activeCaptureMode")
    startForegroundGuard()
    scheduleRootPngCaptureStart("session_start_root_png_capture", suppressBlackout = false)
    broadcastStatus()
    persistRuntimeState("session_start_root_png_prepare")
    return@withLock TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
  }

  private suspend fun handleBrowserStopRequest(body: String): TicketSessionResponse {
    val explicit = body.contains(""""explicit":true""") ||
      body.contains("explicit=true") ||
      body.contains(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP)
    return if (explicit) {
      stopTicketSession(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP)
    } else {
      noteClientDetached("http_stop_without_explicit")
    }
  }

  private suspend fun noteClientDetached(reason: String): TicketSessionResponse {
    recordTicketEvent("client_detached", reason)
    if (ticketSessionOpen()) {
      updateTicketSessionState(TICKET_SESSION_CLIENT_DISCONNECTED, reason)
      lastMessage = "Browser disconnected; ticket session is waiting to reconnect"
      streamActive = false
      pendingStartAfterProjection = false
      projectionPrewarmPending = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      resetFrameEpoch("client_detached_$reason", active = false)
      cancelInactivityTimer()
      cancelForegroundGuard()
      rootFfmpegH264CaptureEngine.stop(reason)
      rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
      rootAv1CaptureEngine.stop(reason)
      rootAv1CaptureEngine.cleanupStaleProcesses()
      rootPngCaptureEngine.stop(reason)
      rootPngCaptureEngine.cleanupStaleProcesses()
      rootCaptureEngine.stop(reason)
      stopEncoder()
      stopProjection()
      disableSecureWindowCaptureBypass()
      disableNotificationLockdown(reason)
      scheduleTicketBrightnessGuard("client_detached:$reason")
      releaseTicketScreenAwake()
      releaseBlackoutOverlaySuppression()
      hideBlackoutOverlay()
      broadcastStatus()
      persistRuntimeState(reason)
      if (ticketServiceEnabled()) {
        recordTicketEvent("root_capture_ready_waiting", "client_detached_$reason")
      }
      return TicketSessionResponse(ok = true, state = "client_disconnected", message = lastMessage)
    }
    return TicketSessionResponse(ok = true, state = "inactive", message = lastMessage)
  }

  private suspend fun stopTicketSession(reason: String): TicketSessionResponse {
    var shouldPrewarmProjection = false
    val response = sessionMutex.withLock {
      clientDisconnectStopJob?.cancel()
      clientDisconnectStopJob = null
      shouldPrewarmProjection = ticketServiceEnabled()
      startupDisconnectGraceUntilMillis = 0L
      pendingStartAfterProjection = false
      projectionPrewarmPending = false
      streamActive = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      updateTicketSessionState(TICKET_SESSION_STOPPED, "session_stop_$reason")
      resetFrameEpoch("session_stop_$reason", active = false)
      cancelInactivityTimer()
      cancelForegroundGuard()
      rootFfmpegH264CaptureEngine.stop(reason)
      rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
      rootAv1CaptureEngine.stop(reason)
      rootAv1CaptureEngine.cleanupStaleProcesses()
      rootPngCaptureEngine.stop(reason)
      rootPngCaptureEngine.cleanupStaleProcesses()
      rootCaptureEngine.stop(reason)
      rootCaptureEngine.cleanupStaleProcesses()
      stopEncoder()
      stopProjection()
      disableSecureWindowCaptureBypass()
      disableNotificationLockdown(reason)
      resetControlCodeMode("session_stop_$reason", broadcast = false)
      lastMessage = "Ticket session stopped: $reason"
      lastSessionStopReason = reason
      recordTicketEvent("session_stopped", reason)
      scheduleTicketBrightnessGuard("session_stopped:$reason")
      releaseBlackoutOverlaySuppression()
      hideBlackoutOverlay()
      releaseTicketScreenAwake()
      broadcastStatus()
      broadcastInactivityStatus()
      persistRuntimeState("session_stop_$reason")
      TicketSessionResponse(ok = true, state = "stopped", message = lastMessage)
    }
    closeAllClients("session_stop_$reason")
    if (shouldPrewarmProjection) {
      recordTicketEvent("root_capture_ready_waiting", "session_stop_$reason")
    }
    if (TicketSessionStopPolicy.shouldResetViviToTicket(reason)) {
      val stoppedState = ticketAutopilot.observeFastState("session_stop_$reason")
      if (stoppedState?.state == TicketViviRecoveryState.TICKET_DETAIL) {
        Log.i(TAG, "ticket_recovery_skipped_ticket_detail reason=$reason")
        broadcastStatus()
      } else {
        scheduleTicketRecovery(reason, TicketRecoveryMode.FRESH_RESET)
      }
    }
    return response
  }

  private fun ticketSessionOpen(): Boolean {
    return streamActive || pendingStartAfterProjection
  }

  private fun av1EncoderStale(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_MEDIAPROJECTION_AV1) {
      return false
    }
    if (encoderJob?.isActive != true) {
      return true
    }
    val lastFrameAgo = ageMillis(lastFrameEncodedAtMillis, nowMillis)
    return lastFrameAgo != null && lastFrameAgo > AV1_STALE_ENCODER_RECOVERY_MILLIS
  }

  private fun recoverStaleAv1Encoder(reason: String) {
    recordTicketEvent("av1_encoder_stale_recovery", reason)
    stopEncoder()
    stopProjection()
    pendingStartAfterProjection = true
    projectionPrewarmPending = false
    activeCaptureMode = CAPTURE_MODE_MEDIAPROJECTION_AV1
    serviceScope.launch {
      requestCapturePermission(force = true)
    }
  }

  private fun updateTicketSessionState(next: String, reason: String) {
    val now = SystemClock.elapsedRealtime()
    val previous = ticketSessionState
    if (previous != next) {
      val previousDuration = (now - ticketSessionStateChangedAtMillis).coerceAtLeast(0L)
      if (isBudgetedTicketState(previous) && previousDuration > TICKET_STATE_BUDGET_MILLIS) {
        lastOverBudgetTicketState = previous
        lastOverBudgetTicketStateDurationMillis = previousDuration
        lastOverBudgetTicketStateReason = ticketSessionStateReason
        recordTicketEvent("ticket_state_over_1s", "$previous duration_ms=$previousDuration reason=$ticketSessionStateReason")
      }
      ticketSessionState = next
      ticketSessionStateChangedAtMillis = now
    }
    ticketSessionStateReason = reason
  }

  private fun isBudgetedTicketState(state: String): Boolean {
    return state == TICKET_SESSION_STARTING ||
      state == TICKET_SESSION_CONTROL_TRANSITION ||
      state == TICKET_SESSION_CONTROL_EXIT ||
      state == TICKET_SESSION_SOFT_RECOVERY
  }

  private fun recordViviHardReset(reason: String) {
    viviHardResetCount += 1L
    lastViviHardResetReason = reason
    lastViviHardResetAtMillis = SystemClock.elapsedRealtime()
    recordTicketEvent("vivi_hard_reset", reason)
  }

  private fun extendStartupDisconnectGrace() {
    val untilMillis = SystemClock.elapsedRealtime() + STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS
    if (untilMillis > startupDisconnectGraceUntilMillis) {
      startupDisconnectGraceUntilMillis = untilMillis
    }
  }

  private fun clearStartupDisconnectGrace() {
    startupDisconnectGraceUntilMillis = 0L
  }

  private fun scheduleClientDisconnectGrace() {
    val startupGraceMillis = (startupDisconnectGraceUntilMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    val delayMillis = startupGraceMillis.coerceAtLeast(CLIENT_DISCONNECT_IDLE_GRACE_MILLIS)
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = serviceScope.launch {
      if (delayMillis > 0L) {
        delay(delayMillis)
      }
      if (totalClientCount() == 0) {
        clientDisconnectStopJob = null
        if (ticketSessionOpen()) {
          noteClientDetached("browser_left_ticket_screen")
        }
      } else {
        clientDisconnectStopJob = null
      }
    }
  }

  private fun markViewerInput(reason: String) {
    lastViewerInputAtMillis = SystemClock.elapsedRealtime()
    if (ticketSessionOpen()) {
      holdTicketScreenAwake("viewer_input_$reason")
      ensureInactivityTimer()
      broadcastInactivityStatus()
      Log.d(TAG, "ticket_viewer_input reason=$reason")
    }
  }

  private fun cancelInactivityTimer() {
    inactivityJob?.cancel()
    inactivityJob = null
  }

  private fun startForegroundGuard() {
    viviForegroundGraceUntilMillis = SystemClock.elapsedRealtime() + VIVI_FOREGROUND_GRACE_MILLIS
    cachedForegroundViolationReason = null
    cachedForegroundCheckedAtMillis = 0L
    if (foregroundGuardJob?.isActive == true) {
      return
    }
    foregroundGuardJob = serviceScope.launch {
      delay(VIVI_FOREGROUND_INITIAL_DELAY_MILLIS)
      while (streamActive) {
        val violation = foregroundViolationReason()
        cacheForegroundViolation(violation)
        if (violation != null) {
          handleForegroundViolation(violation)
        } else if (controlSensitiveWindowActive()) {
          resetForegroundViolationConfirmation()
          refreshControlCodeModeAfterRemoteTap()
        } else if (isBudgetedTicketState(ticketSessionState)) {
          resetForegroundViolationConfirmation()
        } else {
          resetForegroundViolationConfirmation()
          enforceViviTicketPageIfNeeded("foreground_guard")
        }
        delay(VIVI_FOREGROUND_CHECK_MILLIS)
      }
      foregroundGuardJob = null
    }
  }

  private fun cancelForegroundGuard() {
    foregroundGuardJob?.cancel()
    foregroundGuardJob = null
    viviForegroundGraceUntilMillis = 0L
    lastViviPageEnforceAtMillis = 0L
    cachedForegroundViolationReason = null
    cachedForegroundCheckedAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    controlCodeTransitionGraceUntilMillis = 0L
    resetForegroundViolationConfirmation()
    lastForegroundRecoveryAtMillis = 0L
    resetControlCodeMode("foreground_guard_cancelled", broadcast = false)
    postRemoteTapForegroundCheckJob?.cancel()
    postRemoteTapForegroundCheckJob = null
    controlCodeSoftCheckJob?.cancel()
    controlCodeSoftCheckJob = null
  }

  private fun holdTicketScreenAwake(reason: String) {
    val manager = getSystemService(PowerManager::class.java) ?: return
    val lock = ticketScreenWakeLock ?: manager.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "$packageName:TicketStream"
    ).apply {
      setReferenceCounted(false)
      ticketScreenWakeLock = this
    }
    runCatching { lock.acquire(TICKET_SCREEN_WAKE_HOLD_MILLIS) }
      .onFailure { error -> Log.w(TAG, "ticket_screen_wake_hold_failed reason=$reason", error) }
  }

  private fun releaseTicketScreenAwake() {
    val lock = ticketScreenWakeLock ?: return
    ticketScreenWakeLock = null
    runCatching {
      if (lock.isHeld) {
        lock.release()
      }
    }.onFailure { error -> Log.w(TAG, "ticket_screen_wake_release_failed", error) }
  }

  private fun ticketScreenInteractive(): Boolean {
    return getSystemService(PowerManager::class.java)?.isInteractive == true
  }

  private fun requestTicketScreenWake(reason: String) {
    holdTicketScreenAwake(reason)
    val now = SystemClock.elapsedRealtime()
    if (now - lastTicketScreenWakeAtMillis < TICKET_SCREEN_WAKE_REQUEST_COOLDOWN_MILLIS) {
      return
    }
    lastTicketScreenWakeAtMillis = now
    serviceScope.launch {
      val result = rootExecutor.runScript(
        """
        input keyevent KEYCODE_WAKEUP
        wm dismiss-keyguard >/dev/null 2>&1 || true
        cmd statusbar collapse >/dev/null 2>&1 || true
        """.trimIndent()
      )
      recordTicketEvent("screen_wake", "$reason ok=${result.ok} duration_ms=${result.durationMs}")
    }
  }

  private fun cacheForegroundViolation(reason: String?) {
    cachedForegroundViolationReason = reason
    cachedForegroundCheckedAtMillis = SystemClock.elapsedRealtime()
  }

  private fun cachedForegroundViolation(): String? {
    val checkedAt = cachedForegroundCheckedAtMillis
    if (checkedAt <= 0L ||
      SystemClock.elapsedRealtime() - checkedAt > CACHED_FOREGROUND_MAX_AGE_MILLIS
    ) {
      schedulePostRemoteTapForegroundCheck()
      return null
    }
    return cachedForegroundViolationReason
  }

  private suspend fun foregroundViolationReason(allowStartupSystemUi: Boolean = true): String? {
    if (!ticketScreenInteractive()) {
      requestTicketScreenWake("foreground_check")
      return "screen_not_interactive"
    }
    val output = focusedWindowSnapshot() ?: return "foreground_check_failed"
    val normalized = output.lowercase()
    val now = SystemClock.elapsedRealtime()
    systemEscapeReason(normalized)?.let { reason ->
      if (
        reason == "remote_system_ui_blocked" &&
        allowStartupSystemUi &&
        now < viviForegroundGraceUntilMillis &&
        (FOCUSED_CAPTURE_PERMISSION_TOKENS.any { token -> normalized.contains(token) } || normalized.contains("systemui"))
      ) {
        return null
      }
      if (reason == "remote_system_ui_blocked") {
        collapseNotificationShade("foreground_violation")
      }
      return reason
    }
    if (now < viviForegroundGraceUntilMillis) {
      return null
    }
    return if (output.contains(TicketScreenConfig.VIVI_PACKAGE)) null else "left_vivi_app"
  }

  private fun handleForegroundViolation(violation: String) {
    if (violation == "screen_not_interactive") {
      requestTicketScreenWake("foreground_violation")
      recordTicketEvent("foreground_screen_wake", violation)
      resetForegroundViolationConfirmation()
      return
    }
    if (lastForegroundViolationReason == violation) {
      foregroundViolationCount += 1
    } else {
      lastForegroundViolationReason = violation
      foregroundViolationCount = 1
    }
    Log.i(TAG, "ticket_foreground_guard_violation reason=$violation count=$foregroundViolationCount")
    recordTicketEvent("foreground_violation", "$violation count=$foregroundViolationCount")
    if (controlSensitiveWindowActive()) {
      recordTicketEvent("foreground_recovery_deferred", "$violation control_sensitive=true")
      return
    }
    if (isBudgetedTicketState(ticketSessionState)) {
      recordTicketEvent("foreground_recovery_deferred", "$violation state=$ticketSessionState")
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (
      streamActive &&
      foregroundViolationCount >= FOREGROUND_RECOVERY_CONFIRMATION_COUNT &&
      now - lastForegroundRecoveryAtMillis >= FOREGROUND_RECOVERY_COOLDOWN_MILLIS
    ) {
      lastForegroundRecoveryAtMillis = now
      scheduleTicketRecovery("foreground_$violation", TicketRecoveryMode.ACTIVE_SOFT)
    }
  }

  private fun resetForegroundViolationConfirmation() {
    lastForegroundViolationReason = null
    foregroundViolationCount = 0
  }

  private fun systemEscapeReason(normalizedFocusedWindow: String): String? {
    return when {
      FOCUSED_POWER_TOKENS.any { token -> normalizedFocusedWindow.contains(token) } -> "remote_power_controls_blocked"
      FOCUSED_NETWORK_TOKENS.any { token -> normalizedFocusedWindow.contains(token) } -> "remote_network_controls_blocked"
      FOCUSED_SYSTEM_UI_TOKENS.any { token -> normalizedFocusedWindow.contains(token) } -> "remote_system_ui_blocked"
      else -> null
    }
  }

  private suspend fun focusedWindowSnapshot(): String? {
    val result = foregroundRootExecutor.runScript(
      """
      dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp|topResumedActivity' | head -n 5
      """.trimIndent()
    )
    if (result.ok) {
      return result.stdout
    }
    Log.w(TAG, "ticket_foreground_check_failed stderr=${result.stderr}")
    return null
  }

  private suspend fun enforceViviTicketPageIfNeeded(reason: String) {
    if (controlSensitiveWindowActive()) {
      recordTicketEvent("active_guard_deferred", "$reason control_sensitive=true")
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (now - lastViviPageEnforceAtMillis < VIVI_PAGE_ENFORCE_INTERVAL_MILLIS) {
      return
    }
    lastViviPageEnforceAtMillis = now
    val result = ticketAutopilot.driveToTicketDetail(
      reason = reason,
      forceFreshLaunch = false,
      allowControlCodePopup = true,
      restartPolicy = TicketAutopilotRestartPolicy.NEVER,
      maxSteps = ACTIVE_AUTOPILOT_MAX_STEPS,
      timeoutMillis = ACTIVE_AUTOPILOT_TIMEOUT_MILLIS
    )
    if (result.success && result.state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
      controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
      markControlCodeModeEntered("foreground_guard_control_code_popup")
    }
    if (result.success && result.state == TicketViviRecoveryState.TICKET_DETAIL) {
      resetForegroundViolationConfirmation()
      if (streamActive && ticketSessionState in setOf(TICKET_SESSION_SOFT_RECOVERY, TICKET_SESSION_NEEDS_ATTENTION)) {
        recordTicketEvent("active_guard_live", reason)
        updateTicketSessionState(TICKET_SESSION_LIVE, "active_guard_ticket_detail_$reason")
        lastMessage = when (activeCaptureMode) {
          CAPTURE_MODE_ROOT_FFMPEG_H264 -> "Ticket session is active through FFmpeg H.264 capture"
          CAPTURE_MODE_ROOT_AV1 -> "Ticket session is active through root-fed AV1 capture"
          CAPTURE_MODE_ROOT_PNG -> "Ticket session is active through root lossless capture"
          else -> lastMessage
        }
        broadcastStatus()
      }
      return
    }
    if (!result.success) {
      Log.w(TAG, "ticket_autopilot_active_failed reason=$reason state=${result.state} step=${result.step}")
      recordTicketEvent("active_guard_failed", "${result.state}:${result.step}")
      scheduleTicketRecovery("active_guard_${result.state.name.lowercase()}", TicketRecoveryMode.ACTIVE_SOFT)
    }
  }

  private suspend fun dumpViviHierarchy(fresh: Boolean = false): RootResult {
    return TicketScreenObserver.dumpViviHierarchy(
      rootExecutor = rootExecutor,
      reason = "service",
      forceFresh = fresh
    )
  }

  private suspend fun controlExitHierarchy(): String? {
    val accessibilityXml = TicketViviPageEnforcer.hierarchyForVisibleNodes(
      PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)
    )
    if (accessibilityXml.isNotBlank()) {
      return accessibilityXml
    }
    val dump = dumpViviHierarchy(fresh = true)
    return dump.stdout.takeIf { dump.ok && it.isNotBlank() }
  }

  private fun inactivityRemainingMillis(nowMillis: Long = SystemClock.elapsedRealtime()): Long {
    return if (ticketSessionOpen()) {
      TicketInactivityPolicy.remainingMillis(
        lastInputAtMillis = lastViewerInputAtMillis,
        nowMillis = nowMillis
      )
    } else {
      0L
    }
  }

  private fun inactivityStatus(nowMillis: Long = SystemClock.elapsedRealtime()): TicketInactivityStatus {
    val active = ticketSessionOpen()
    return TicketInactivityStatus(
      active = active,
      timeoutMillis = TicketInactivityPolicy.TIMEOUT_MILLIS,
      remainingMillis = if (active) {
        inactivityRemainingMillis(nowMillis)
      } else {
        0L
      }
    )
  }

  private fun sendInactivityStatus(client: TicketWebSocket) {
    client.sendText(json.encodeToString(inactivityStatus()))
  }

  private fun broadcastInactivityStatus() {
    val message = json.encodeToString(inactivityStatus())
    controlClientSnapshot().forEach { client -> client.sendText(message) }
  }

  private fun ensureInactivityTimer() {
    if (inactivityJob?.isActive == true) {
      return
    }
    inactivityJob = serviceScope.launch {
      while (true) {
        delay(TicketInactivityPolicy.TICK_MILLIS)
        if (!ticketSessionOpen()) {
          inactivityJob = null
          broadcastInactivityStatus()
          return@launch
        }
        broadcastInactivityStatus()
        if (
          TicketInactivityPolicy.timedOut(
            lastInputAtMillis = lastViewerInputAtMillis,
            nowMillis = SystemClock.elapsedRealtime()
          )
        ) {
          Log.i(TAG, "ticket_inactivity_timeout")
          inactivityJob = null
          serviceScope.launch {
            stopTicketSession("viewer_inactivity_timeout")
          }
          return@launch
        }
      }
    }
  }

  private suspend fun suppressBlackoutOverlayForRemote() {
    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(true)
    hideBlackoutOverlay()
    refreshPhoneAutomation()
  }

  private fun releaseBlackoutOverlaySuppression() {
    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(false)
    refreshPhoneAutomation()
  }

  private suspend fun hideBlackoutOverlay() {
    runCatching {
      PhoneAutomationServiceBridge.setBlackoutOverlayVisible(false)
    }.onFailure { error ->
      Log.w(TAG, "blackout_overlay_hide_failed", error)
    }
  }

  private fun ticketServiceEnabled(): Boolean {
    return runCatching {
      TicketServicePreferencesStore(this).load().enabled
    }.getOrDefault(false)
  }

  private fun scheduleProjectionPrewarm(reason: String) {
    if (!ticketServiceEnabled() || streamActive || pendingStartAfterProjection || projectionPrewarmPending) {
      return
    }
    if (mediaProjection != null && !mediaProjectionConsumed) {
      return
    }
    projectionPrewarmPending = true
    recordTicketEvent("capture_permission_prewarm_scheduled", reason)
    serviceScope.launch {
      if (mediaProjectionConsumed) {
        stopProjection()
      }
      val launched = requestCapturePermission(force = true)
      if (launched) {
        lastMessage = "AV1 screen capture permission is being prepared for the next viewer"
        recordTicketEvent("capture_permission_prewarm_started", reason)
      } else {
        projectionPrewarmPending = false
        lastMessage = "AV1 screen capture permission prewarm failed"
        disableSecureWindowCaptureBypass()
        recordTicketEvent("capture_permission_prewarm_failed", reason)
      }
      broadcastStatus()
    }
  }

  private suspend fun requestCapturePermission(force: Boolean = false): Boolean {
    if (!AUTO_MEDIA_PROJECTION_PERMISSION_LAUNCH_ENABLED) {
      lastMessage = "Screen-sharing permission auto-launch is disabled for ticket sessions"
      Log.w(TAG, "ticket_capture_permission_auto_launch_blocked")
      return false
    }
    val now = SystemClock.elapsedRealtime()
    if (!force && now - capturePermissionLaunchedAtMillis < CAPTURE_PERMISSION_LAUNCH_COOLDOWN_MILLIS) {
      lastMessage = "Screen capture permission is already pending on the Pixel"
      return true
    }
    capturePermissionLaunchedAtMillis = now
    enableSecureWindowCaptureBypass()
    val component = "$packageName/.app.ticket.TicketCapturePermissionActivity"
    val result = rootExecutor.run("am start -n $component")
    if (result.ok) {
      scheduleCapturePermissionRootAssist("root_launch")
      return true
    }
    Log.w(TAG, "ticket_capture_permission_root_launch_failed stderr=${result.stderr}")
    return runCatching {
      val intent = Intent(this, TicketCapturePermissionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(intent)
      scheduleCapturePermissionRootAssist("activity_launch")
    }.onFailure { error ->
      Log.w(TAG, "ticket_capture_permission_launch_failed", error)
    }.isSuccess
  }

  private fun scheduleCapturePermissionRootAssist(reason: String) {
    recordTicketEvent("capture_permission_assist_scheduled", reason)
    serviceScope.launch {
      delay(CAPTURE_PERMISSION_ASSIST_DELAY_MILLIS)
      val result = rootExecutor.runScript(capturePermissionAssistScript())
      if (result.ok) {
        recordTicketEvent("capture_permission_assist", "ok:$reason ${result.stdout.trim().take(MAX_TICKET_EVENT_DETAIL_BYTES)}")
        Log.i(TAG, "ticket_capture_permission_assist_ok reason=$reason stdout=${result.stdout.trim()}")
      } else {
        recordTicketEvent("capture_permission_assist", "failed:$reason ${result.stderr.trim().take(MAX_TICKET_EVENT_DETAIL_BYTES)}")
        Log.w(TAG, "ticket_capture_permission_assist_failed reason=$reason stderr=${result.stderr}")
      }
      broadcastStatus()
    }
  }

  private fun capturePermissionAssistScript(): String {
    return """
      dump="/data/local/tmp/ticket-capture-permission.xml"
      i=0
      while [ "${'$'}i" -lt 20 ]; do
        rm -f "${'$'}dump" >/dev/null 2>&1 || true
        uiautomator dump "${'$'}dump" >/dev/null 2>&1 || true
        if [ -s "${'$'}dump" ]; then
          if grep -Eiq 'MediaProjection|media projection|screen|record|capture|cast|ekr.n|ierakst|tver' "${'$'}dump"; then
            line="$(grep -Ei 'resource-id="android:id/button1"|text="(Start now|Start|Allow|OK|Atļaut|Sākt|Turpināt)"' "${'$'}dump" | tail -n 1)"
            bounds="$(printf '%s\n' "${'$'}line" | sed -n 's/.*bounds="\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]\[\([0-9][0-9]*\),\([0-9][0-9]*\)\]".*/\1 \2 \3 \4/p' | tail -n 1)"
            if [ -n "${'$'}bounds" ]; then
              set -- ${'$'}bounds
              x="${'$'}(( ( ${'$'}1 + ${'$'}3 ) / 2 ))"
              y="${'$'}(( ( ${'$'}2 + ${'$'}4 ) / 2 ))"
              input tap "${'$'}x" "${'$'}y"
              echo "tapped_button ${'$'}x ${'$'}y"
              exit 0
            fi
            size="$(wm size 2>/dev/null | sed -n 's/.* \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1 \2/p' | tail -n 1)"
            if [ -n "${'$'}size" ]; then
              set -- ${'$'}size
              x="${'$'}(( ${'$'}1 * 3 / 4 ))"
              y="${'$'}(( ${'$'}2 * 4 / 5 ))"
              input tap "${'$'}x" "${'$'}y"
              echo "tapped_fallback ${'$'}x ${'$'}y"
              exit 0
            fi
          fi
        fi
        i="${'$'}((i + 1))"
        sleep 0.25
      done
      echo "permission_prompt_not_found"
      exit 1
    """.trimIndent()
  }

  private fun handleProjectionResult(intent: Intent) {
    val resultCode = intent.getIntExtra(TicketScreenConfig.EXTRA_RESULT_CODE, 0)
    @Suppress("DEPRECATION")
    val resultData = intent.getParcelableExtra<Intent>(TicketScreenConfig.EXTRA_RESULT_DATA)
    if (resultCode == 0 || resultData == null) {
      pendingStartAfterProjection = false
      projectionPrewarmPending = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      lastMessage = "Screen capture permission was not granted"
      cancelInactivityTimer()
      serviceScope.launch {
        disableSecureWindowCaptureBypass()
        disableNotificationLockdown("projection_not_granted")
        scheduleTicketBrightnessGuard("projection_not_granted")
        releaseBlackoutOverlaySuppression()
      }
      broadcastStatus()
      return
    }
    promoteToForeground(mediaProjectionActive = true)
    val manager = getSystemService(MediaProjectionManager::class.java)
    mediaProjection = try {
      manager.getMediaProjection(resultCode, resultData)
    } catch (error: SecurityException) {
      pendingStartAfterProjection = false
      projectionPrewarmPending = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      lastMessage = "Screen capture permission was rejected by Android"
      cancelInactivityTimer()
      Log.e(TAG, "media_projection_permission_rejected", error)
      serviceScope.launch {
        disableSecureWindowCaptureBypass()
        disableNotificationLockdown("projection_rejected")
        scheduleTicketBrightnessGuard("projection_rejected")
        releaseBlackoutOverlaySuppression()
      }
      broadcastStatus()
      return
    }
    mediaProjectionConsumed = false
    val callback = object : MediaProjection.Callback() {
      override fun onStop() {
        lastMessage = "Screen capture stopped by Android"
        streamActive = false
        activeCaptureMode = CAPTURE_MODE_IDLE
        cancelInactivityTimer()
        cancelForegroundGuard()
        stopEncoder()
        serviceScope.launch {
          disableSecureWindowCaptureBypass()
          disableNotificationLockdown("projection_stopped")
          scheduleTicketBrightnessGuard("projection_stopped")
          releaseBlackoutOverlaySuppression()
          hideBlackoutOverlay()
        }
        broadcastStatus()
      }
    }
    mediaProjectionCallback = callback
    mediaProjection?.registerCallback(callback, Handler(Looper.getMainLooper()))
    if (pendingStartAfterProjection) {
      pendingStartAfterProjection = false
      projectionPrewarmPending = false
      streamActive = true
      activeCaptureMode = CAPTURE_MODE_MEDIAPROJECTION_AV1
      extendStartupDisconnectGrace()
      markViewerInput("projection_granted")
      lastMessage = "Ticket session is active through AV1 capture"
      scheduleTicketBrightnessGuard("projection_granted")
      startForegroundGuard()
      scheduleAv1CaptureStart("projection_granted", suppressBlackout = true)
    } else {
      projectionPrewarmPending = false
      lastMessage = "Screen capture permission is ready"
      serviceScope.launch {
        disableSecureWindowCaptureBypass()
        scheduleTicketBrightnessGuard("projection_ready")
      }
    }
    broadcastStatus()
  }

  private fun stopProjection() {
    val projection = mediaProjection ?: return
    mediaProjectionCallback?.let { callback ->
      runCatching { projection.unregisterCallback(callback) }
    }
    runCatching { projection.stop() }
    mediaProjectionCallback = null
    mediaProjection = null
    mediaProjectionConsumed = false
  }

  private suspend fun enableSecureWindowCaptureBypass() {
    val script =
      """
      state_dir="/data/local/pixel-stack/apps/ticket-screen/state"
      state_file="${'$'}state_dir/ro-debuggable-before-ticket"
      reset_debuggable() {
        value="${'$'}1"
        if [ -x /debug_ramdisk/magisk ]; then
          su -M -c "/debug_ramdisk/magisk resetprop ro.debuggable ${'$'}value"
          return "${'$'}?"
        fi
        if command -v resetprop >/dev/null 2>&1; then
          su -M -c "resetprop ro.debuggable ${'$'}value"
          return "${'$'}?"
        fi
        su -M -c "/system_ext/bin/magisk resetprop ro.debuggable ${'$'}value"
      }
      mkdir -p "${'$'}state_dir"
      if [ ! -f "${'$'}state_file" ]; then
        getprop ro.debuggable > "${'$'}state_file"
        chmod 600 "${'$'}state_file" >/dev/null 2>&1 || true
      fi
      reset_debuggable 1
      settings put secure disable_secure_windows 1
      """.trimIndent()
    val result = runSecureWindowCaptureBypassScript(script)
    if (!result.ok) {
      secureWindowCaptureBypassActive = false
      secureWindowCaptureBypassMessage = "Secure-window capture bypass enable failed"
      Log.w(TAG, "secure_window_capture_bypass_enable_failed stderr=${result.stderr}")
    } else {
      secureWindowCaptureBypassActive = true
      secureWindowCaptureBypassMessage = "Secure-window capture bypass is active"
      Log.i(TAG, "secure_window_capture_bypass_enabled")
    }
  }

  private suspend fun disableSecureWindowCaptureBypass() {
    val script =
      """
      state_file="/data/local/pixel-stack/apps/ticket-screen/state/ro-debuggable-before-ticket"
      reset_debuggable() {
        value="${'$'}1"
        if [ -x /debug_ramdisk/magisk ]; then
          su -M -c "/debug_ramdisk/magisk resetprop ro.debuggable ${'$'}value"
          return "${'$'}?"
        fi
        if command -v resetprop >/dev/null 2>&1; then
          su -M -c "resetprop ro.debuggable ${'$'}value"
          return "${'$'}?"
        fi
        su -M -c "/system_ext/bin/magisk resetprop ro.debuggable ${'$'}value"
      }
      settings put secure disable_secure_windows 0
      original="0"
      if [ -r "${'$'}state_file" ]; then
        original="$(sed -n '1p' "${'$'}state_file" 2>/dev/null | tr -d '\r')"
      fi
      case "${'$'}original" in
        1) reset_debuggable 1 ;;
        *) reset_debuggable 0 ;;
      esac
      rm -f "${'$'}state_file" >/dev/null 2>&1 || true
      """.trimIndent()
    val result = runSecureWindowCaptureBypassScript(script)
    if (!result.ok) {
      secureWindowCaptureBypassMessage = "Secure-window capture bypass disable failed"
      Log.w(TAG, "secure_window_capture_bypass_disable_failed stderr=${result.stderr}")
    } else {
      secureWindowCaptureBypassActive = false
      secureWindowCaptureBypassMessage = "Secure-window capture bypass is inactive"
      Log.i(TAG, "secure_window_capture_bypass_disabled")
    }
  }

  private suspend fun runSecureWindowCaptureBypassScript(script: String): RootResult {
    val primary = rootExecutor.runScript(script)
    if (primary.ok) {
      return primary
    }
    val start = SystemClock.elapsedRealtime()
    return withContext(Dispatchers.IO) {
      try {
        val process = ProcessBuilder("su", "-c", script)
          .redirectErrorStream(true)
          .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        val fallback = RootResult(
          exitCode = exitCode,
          stdout = output,
          stderr = if (exitCode == 0) "" else output,
          command = script,
          durationMs = SystemClock.elapsedRealtime() - start
        )
        if (!fallback.ok) {
          Log.w(
            TAG,
            "secure_window_capture_bypass_direct_root_failed primary=${primary.stderr} fallback=${fallback.stderr}"
          )
        }
        fallback
      } catch (error: Throwable) {
        RootResult(
          exitCode = 1,
          stdout = "",
          stderr = "primary=${primary.stderr}; fallback=${error.message ?: error::class.java.simpleName}",
          command = script,
          durationMs = SystemClock.elapsedRealtime() - start
        )
      }
    }
  }

  private suspend fun enableNotificationLockdown(reason: String) {
    val result = rootExecutor.runScript(TicketNotificationLockdown.enableScript())
    notificationLockdownChangedAtMillis = SystemClock.elapsedRealtime()
    if (result.ok) {
      notificationLockdownActive = true
      notificationLockdownReason = "active:$reason"
      Log.i(TAG, "ticket_notification_lockdown_enabled reason=$reason")
    } else {
      notificationLockdownActive = false
      notificationLockdownReason = "enable_failed:$reason"
      Log.w(TAG, "ticket_notification_lockdown_enable_failed reason=$reason stderr=${result.stderr}")
    }
  }

  private suspend fun disableNotificationLockdown(reason: String) {
    val result = rootExecutor.runScript(TicketNotificationLockdown.disableScript())
    notificationLockdownChangedAtMillis = SystemClock.elapsedRealtime()
    if (result.ok) {
      notificationLockdownActive = false
      notificationLockdownReason = "inactive:$reason"
      Log.i(TAG, "ticket_notification_lockdown_disabled reason=$reason")
    } else {
      notificationLockdownReason = "disable_failed:$reason"
      Log.w(TAG, "ticket_notification_lockdown_disable_failed reason=$reason stderr=${result.stderr}")
    }
  }

  private suspend fun collapseNotificationShade(reason: String) {
    val result = rootExecutor.runScript(TicketNotificationLockdown.collapseScript())
    if (!result.ok) {
      Log.w(TAG, "ticket_notification_shade_collapse_failed reason=$reason stderr=${result.stderr}")
    }
  }

  private fun ensureEncoderIfPossible() {
    if (!streamActive || videoClients.isEmpty()) {
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) {
      ensureRootFfmpegH264CaptureIfPossible()
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_PNG) {
      ensureRootPngCaptureIfPossible()
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_AV1) {
      ensureRootAv1CaptureIfPossible()
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_H264) {
      ensureRootCaptureIfPossible()
      return
    }
    if (mediaProjection == null) {
      return
    }
    synchronized(encoderLock) {
      if (encoderJob?.isActive == true) {
        return
      }
      runCatching {
        startEncoderLocked()
      }.onFailure { error ->
        Log.w(TAG, "ticket_av1_encoder_start_failed", error)
        recordTicketEvent("encoder_start_failed", error.message ?: error::class.java.simpleName)
        lastMessage = "AV1 stream could not start: ${error.message ?: error::class.java.simpleName}"
        stopEncoder()
        stopProjection()
        if (streamActive) {
          pendingStartAfterProjection = true
          projectionPrewarmPending = false
          activeCaptureMode = CAPTURE_MODE_MEDIAPROJECTION_AV1
          serviceScope.launch {
            requestCapturePermission(force = true)
          }
        }
        broadcastStatus()
      }
    }
  }

  private fun ensureRootCaptureIfPossible() {
    synchronized(encoderLock) {
      val sourceSize = currentDisplaySize()
      val size = TicketStreamSizing.rootH264(sourceSize.first, sourceSize.second)
      val previousSize = streamSize
      if (streamEpoch == 0L || previousSize == null || previousSize.width != size.width || previousSize.height != size.height) {
        resetFrameEpoch("root_capture_config", active = true)
      }
      streamSize = size
      activeCaptureMode = CAPTURE_MODE_ROOT_H264
      lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
      broadcastConfig(size)
      rootCaptureEngine.start(sourceSize.first, sourceSize.second)
      rootCaptureSnapshot = rootCaptureEngine.snapshot()
      Log.i(TAG, "ticket_root_capture_requested width=${size.width} height=${size.height} video_clients=${videoClients.size}")
    }
  }

  private fun ensureRootFfmpegH264CaptureIfPossible() {
    synchronized(encoderLock) {
      val sourceSize = currentDisplaySize()
      val size = TicketStreamSizing.rootFfmpegH264(sourceSize.first, sourceSize.second)
      val previousSize = streamSize
      if (streamEpoch == 0L || previousSize == null || previousSize.width != size.width || previousSize.height != size.height) {
        resetFrameEpoch("root_ffmpeg_h264_capture_config", active = true)
      }
      streamSize = size
      activeCaptureMode = CAPTURE_MODE_ROOT_FFMPEG_H264
      lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
      broadcastConfig(size)
      rootFfmpegH264CaptureEngine.start(
        sourceWidth = sourceSize.first,
        sourceHeight = sourceSize.second,
        targetWidth = size.width,
        targetHeight = size.height,
        targetBitrate = TicketScreenConfig.ROOT_FFMPEG_H264_BITRATE,
        targetFps = TicketScreenConfig.ROOT_FFMPEG_H264_FPS
      )
      ffmpegCaptureSnapshot = rootFfmpegH264CaptureEngine.snapshot()
      Log.i(TAG, "ticket_root_ffmpeg_h264_capture_requested width=${size.width} height=${size.height} bitrate=${TicketScreenConfig.ROOT_FFMPEG_H264_BITRATE} video_clients=${videoClients.size}")
    }
  }

  private fun ensureRootPngCaptureIfPossible() {
    synchronized(encoderLock) {
      val sourceSize = currentDisplaySize()
      val size = TicketStreamSizing.rootPng(sourceSize.first, sourceSize.second)
      val previousSize = streamSize
      if (streamEpoch == 0L || previousSize == null || previousSize.width != size.width || previousSize.height != size.height) {
        resetFrameEpoch("root_png_capture_config", active = true)
      }
      streamSize = size
      activeCaptureMode = CAPTURE_MODE_ROOT_PNG
      lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
      broadcastConfig(size)
      rootPngCaptureEngine.start(sourceSize.first, sourceSize.second)
      rootCaptureSnapshot = rootPngCaptureEngine.snapshot()
      Log.i(TAG, "ticket_root_png_capture_requested width=${size.width} height=${size.height} video_clients=${videoClients.size}")
    }
  }

  private fun ensureRootAv1CaptureIfPossible() {
    synchronized(encoderLock) {
      val sourceSize = currentDisplaySize()
      val size = TicketStreamSizing.rootAv1Balanced(sourceSize.first, sourceSize.second)
      val previousSize = streamSize
      if (streamEpoch == 0L || previousSize == null || previousSize.width != size.width || previousSize.height != size.height) {
        resetFrameEpoch("root_av1_capture_config", active = true)
      }
      streamSize = size
      activeCaptureMode = CAPTURE_MODE_ROOT_AV1
      lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
      broadcastConfig(size)
      rootAv1CaptureEngine.start(
        sourceWidth = sourceSize.first,
        sourceHeight = sourceSize.second,
        targetWidth = size.width,
        targetHeight = size.height,
        targetBitrate = TicketScreenConfig.AV1_CAPTURE_BITRATE,
        fps = TicketScreenConfig.MAX_FPS
      )
      rootCaptureSnapshot = rootAv1CaptureEngine.snapshot()
      Log.i(TAG, "ticket_root_av1_capture_requested width=${size.width} height=${size.height} bitrate=${TicketScreenConfig.AV1_CAPTURE_BITRATE} video_clients=${videoClients.size}")
    }
  }

  private fun startEncoderLocked() {
    val projection = mediaProjection ?: return
    val codecName = TicketAv1Support.hardwareEncoderName() ?: run {
      lastMessage = "Hardware AV1 encoder is unavailable"
      broadcastStatus()
      return
    }
    val sourceSize = currentDisplaySize()
    val size = TicketStreamSizing.av1Clarity(sourceSize.first, sourceSize.second)
    streamSize = size
    val mediaFormat = MediaFormat.createVideoFormat(
      TicketScreenConfig.AV1_MIME_TYPE,
      size.width,
      size.height
    ).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, TicketScreenConfig.AV1_CAPTURE_BITRATE)
      setInteger(MediaFormat.KEY_FRAME_RATE, TicketScreenConfig.MAX_FPS)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, TicketScreenConfig.AV1_KEYFRAME_INTERVAL_SECONDS)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        setInteger(
          MediaFormat.KEY_BITRATE_MODE,
          MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
      }
      setLong(
        MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
        TicketScreenConfig.AV1_REPEAT_PREVIOUS_FRAME_AFTER_US
      )
    }
    val localEncoder = MediaCodec.createByCodecName(codecName)
    localEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val inputSurface = localEncoder.createInputSurface()
    val display = projection.createVirtualDisplay(
      "ticket-screen-av1",
      size.width,
      size.height,
      resources.displayMetrics.densityDpi,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      inputSurface,
      null,
      null
    )
    mediaProjectionConsumed = true
    encoder = localEncoder
    virtualDisplay = display
    localEncoder.start()
    lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
    Log.i(TAG, "ticket_av1_encoder_started width=${size.width} height=${size.height} bitrate=${TicketScreenConfig.AV1_CAPTURE_BITRATE} video_clients=${videoClients.size}")
    broadcastConfig(size)
    encoderJob = serviceScope.launch {
      drainEncoder(localEncoder)
    }
  }

  private suspend fun drainEncoder(localEncoder: MediaCodec) {
    val info = MediaCodec.BufferInfo()
    try {
      while (streamActive) {
        when (val index = localEncoder.dequeueOutputBuffer(info, ENCODER_TIMEOUT_US)) {
          MediaCodec.INFO_TRY_AGAIN_LATER -> delay(5)
          MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> streamSize?.let(::broadcastConfig)
          else -> if (index >= 0) {
            val output = localEncoder.getOutputBuffer(index)
            if (output != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
              output.position(info.offset)
              output.limit(info.offset + info.size)
              val payload = ByteArray(info.size)
              output.get(payload)
              val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
              encodedFrames += 1
              lastFrameEncodedAtMillis = SystemClock.elapsedRealtime()
              if (keyFrame) {
                keyFrames += 1
                lastKeyFrameEncodedAtMillis = lastFrameEncodedAtMillis
              }
              broadcastFrame(keyFrame = keyFrame, timestampUs = info.presentationTimeUs, payload = payload)
            }
            localEncoder.releaseOutputBuffer(index, false)
          }
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      Log.w(TAG, "encoder_failed", error)
      recordTicketEvent("encoder_failed", error.message ?: error::class.java.simpleName)
      lastMessage = "AV1 stream stopped: ${error.message ?: error::class.java.simpleName}"
      broadcastStatus()
      serviceScope.launch {
        delay(ENCODER_FAILURE_RECOVERY_DELAY_MILLIS)
        restartActiveStream("encoder_failed")
      }
    } finally {
      stopEncoder()
    }
  }

  private fun restartActiveStream(reason: String) {
    if (!streamActive) {
      return
    }
    recordTicketEvent("stream_restart_requested", reason)
    if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) {
      resetFrameEpoch("root_ffmpeg_h264_fresh_request_$reason", active = true)
      streamSize?.let(::broadcastConfig)
      rootFfmpegH264CaptureEngine.requestKeyFrame("restart_stream:$reason")
      ensureEncoderIfPossible()
    } else if (activeCaptureMode == CAPTURE_MODE_ROOT_PNG) {
      resetFrameEpoch("root_png_fresh_request_$reason", active = true)
      streamSize?.let(::broadcastConfig)
      rootPngCaptureEngine.requestFreshFrame("restart_stream:$reason")
      ensureEncoderIfPossible()
    } else if (activeCaptureMode == CAPTURE_MODE_ROOT_AV1) {
      resetFrameEpoch("root_av1_fresh_request_$reason", active = true)
      streamSize?.let(::broadcastConfig)
      rootAv1CaptureEngine.requestKeyFrame("restart_stream:$reason")
      ensureEncoderIfPossible()
    } else if (activeCaptureMode == CAPTURE_MODE_ROOT_H264) {
      restartRootCaptureForNewEpoch(reason)
      ensureEncoderIfPossible()
    } else if (mediaProjection != null) {
      resetFrameEpoch("mediaprojection_restart_$reason", active = true)
      stopEncoder()
      ensureEncoderIfPossible()
      requestKeyFrame()
    }
    broadcastStatus()
  }

  private fun stopEncoder() {
    synchronized(encoderLock) {
      encoderJob?.cancel()
      encoderJob = null
      runCatching { virtualDisplay?.release() }
      virtualDisplay = null
      runCatching { encoder?.stop() }
      runCatching { encoder?.release() }
      encoder = null
      if (activeCaptureMode != CAPTURE_MODE_ROOT_PNG && activeCaptureMode != CAPTURE_MODE_ROOT_AV1) {
        streamSize = null
      }
    }
  }

  private fun configMessage(size: TicketStreamSize): String {
    val codec = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_CODEC_STRING
      CAPTURE_MODE_ROOT_PNG -> TicketScreenConfig.ROOT_PNG_CODEC_STRING
      CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.H264_CODEC_STRING
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_CODEC_STRING
      else -> TicketScreenConfig.AV1_CODEC_STRING
    }
    val transport = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_TRANSPORT
      CAPTURE_MODE_ROOT_PNG -> TicketScreenConfig.ROOT_PNG_TRANSPORT
      CAPTURE_MODE_ROOT_H264 -> "h264-annexb"
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.ROOT_AV1_TRANSPORT
      else -> "av1-webcodecs"
    }
    val rootCapture = activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264 ||
      activeCaptureMode == CAPTURE_MODE_ROOT_PNG ||
      activeCaptureMode == CAPTURE_MODE_ROOT_H264 ||
      activeCaptureMode == CAPTURE_MODE_ROOT_AV1
    val bitrate = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_BITRATE
      CAPTURE_MODE_ROOT_PNG -> 0
      CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.ROOT_CAPTURE_BITRATE
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_CAPTURE_BITRATE
      else -> TicketScreenConfig.AV1_CAPTURE_BITRATE
    }
    val qualityProfile = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_QUALITY_PROFILE
      CAPTURE_MODE_ROOT_PNG -> TicketScreenConfig.ROOT_PNG_QUALITY_PROFILE
      CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.ROOT_CAPTURE_QUALITY_PROFILE
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_CAPTURE_QUALITY_PROFILE
      else -> TicketScreenConfig.AV1_CAPTURE_QUALITY_PROFILE
    }
    val keyFrameIntervalMillis = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_KEYFRAME_INTERVAL_MILLIS
      CAPTURE_MODE_ROOT_PNG -> 500
      CAPTURE_MODE_ROOT_H264 -> ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_KEYFRAME_INTERVAL_MILLIS
      else -> TicketScreenConfig.AV1_KEYFRAME_INTERVAL_MILLIS
    }
    return """
      {"type":"config","serverVersion":"$SERVER_VERSION","codec":"$codec","transport":"$transport","captureMode":"$activeCaptureMode","captureSource":${json.encodeToString(if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) ffmpegCaptureSnapshot.captureSource else activeCaptureMode)},"captureMethod":${json.encodeToString(if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) ffmpegCaptureSnapshot.captureMethod else activeCaptureMode)},"rootCapture":$rootCapture,"frameEnvelope":"$FRAME_ENVELOPE_VERSION","streamEpoch":$streamEpoch,"qualityProfile":"$qualityProfile","width":${size.width},"height":${size.height},"bitrate":$bitrate,"fps":${if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) TicketScreenConfig.ROOT_FFMPEG_H264_FPS else TicketScreenConfig.MAX_FPS},"keyFrameIntervalMillis":$keyFrameIntervalMillis,"repeatPreviousFrameAfterUs":${TicketScreenConfig.AV1_REPEAT_PREVIOUS_FRAME_AFTER_US},"ffmpegVersion":${json.encodeToString(ffmpegCaptureSnapshot.version ?: "")}}
    """.trimIndent()
  }

  private fun broadcastConfig(size: TicketStreamSize) {
    val message = configMessage(size)
    lastConfigSentAtMillis = SystemClock.elapsedRealtime()
    Log.i(TAG, "ticket_stream_config_sent width=${size.width} height=${size.height} video_clients=${videoClients.size}")
    videoClientSnapshot().forEach { client ->
      client.sendText(message)
    }
  }

  private fun sendConfigAndWarmStart(client: TicketWebSocket, size: TicketStreamSize) {
    client.sendText(configMessage(size))
    lastConfigSentAtMillis = SystemClock.elapsedRealtime()
  }

  private fun sendCachedKeyFrameOrRequest(client: TicketWebSocket, reason: String = "video_client_request"): Boolean {
    if (
      activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264 &&
      activeCaptureMode != CAPTURE_MODE_ROOT_H264 &&
      activeCaptureMode != CAPTURE_MODE_ROOT_PNG &&
      activeCaptureMode != CAPTURE_MODE_ROOT_AV1
    ) {
      requestKeyFrame(reason)
      return false
    }
    val frame = latestKeyFrameEnvelope
    val ageMillis = ageMillis(latestKeyFrameAtMillis, SystemClock.elapsedRealtime())
    if (
      frame != null &&
      latestKeyFrameStreamEpoch == streamEpoch &&
      ageMillis != null &&
      ageMillis <= ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
    ) {
      Log.i(TAG, "ticket_cached_keyframe_sent age_ms=$ageMillis epoch=$latestKeyFrameStreamEpoch sequence=$latestKeyFrameSequence timestamp_us=$latestKeyFrameTimestampUs")
      sentFrames += 1
      lastFrameSentAtMillis = SystemClock.elapsedRealtime()
      sendVideoFrame(client, frame, keyFrame = true)
      return true
    }
    requestKeyFrame(reason)
    return false
  }

  private fun broadcastFrame(keyFrame: Boolean, timestampUs: Long, payload: ByteArray) {
    clearStartupDisconnectGrace()
    val epoch = ensureFrameEpoch("frame")
    val sequence = synchronized(encoderLock) {
      frameSequence += 1
      frameSequence
    }
    val buffer = ByteBuffer.allocate(FRAME_ENVELOPE_HEADER_BYTES + payload.size)
    buffer.putInt(FRAME_ENVELOPE_MAGIC)
    buffer.put(if (keyFrame) FRAME_FLAG_KEYFRAME else 0.toByte())
    buffer.putLong(epoch)
    buffer.putLong(sequence)
    buffer.putLong(timestampUs)
    buffer.put(payload)
    val frame = buffer.array()
    sentFrames += 1
    lastFrameBytes = frame.size
    lastFrameSentAtMillis = SystemClock.elapsedRealtime()
    noteFrameBytes(frame.size, lastFrameSentAtMillis)
    if (keyFrame) {
      latestKeyFrameStreamEpoch = epoch
      latestKeyFrameSequence = sequence
      latestKeyFrameEnvelope = frame
      latestKeyFrameAtMillis = lastFrameSentAtMillis
      latestKeyFrameTimestampUs = timestampUs
      lastKeyFrameBytes = frame.size
    }
    videoClientSnapshot().forEach { client -> sendVideoFrame(client, frame, keyFrame) }
  }

  private fun sendVideoFrame(client: TicketWebSocket, frame: ByteArray, keyFrame: Boolean) {
    val sendState = synchronized(videoSendStates) {
      videoSendStates.getOrPut(client) { TicketVideoSendState() }
    }
    val nowMillis = SystemClock.elapsedRealtime()
    var requestFreshKeyFrame = false
    var shouldSend = true
    synchronized(sendState) {
      if (sendState.waitingForKeyFrame && !keyFrame) {
        droppedVideoFrames += 1
        requestFreshKeyFrame = true
        shouldSend = false
        return@synchronized
      }
      if (keyFrame) {
        sendState.waitingForKeyFrame = false
      }
      if (sendState.inFlight) {
        val inFlightFor = nowMillis - sendState.inFlightSinceMillis
        if (inFlightFor > VIDEO_CLIENT_SLOW_CLOSE_MILLIS) {
          closedSlowVideoClients += 1
          recordTicketEvent("video_client_closed_slow", "blocked_ms=$inFlightFor key=$keyFrame")
          client.close()
          sendState.inFlight = false
          sendState.inFlightSinceMillis = 0L
        } else {
          if (sendState.pendingFrame != null) {
            droppedVideoFrames += 1
          }
          sendState.pendingFrame = frame
          sendState.pendingKeyFrame = keyFrame
          if (!keyFrame && activeCaptureMode == CAPTURE_MODE_ROOT_AV1) {
            sendState.waitingForKeyFrame = true
            requestFreshKeyFrame = true
          }
        }
        shouldSend = false
        return@synchronized
      }
      sendState.inFlight = true
      sendState.inFlightSinceMillis = nowMillis
    }
    if (requestFreshKeyFrame) {
      requestKeyFrame("slow_video_client_latest_frame_drop")
    }
    if (!shouldSend) {
      return
    }
    serviceScope.launch(Dispatchers.IO) {
      val startMillis = SystemClock.elapsedRealtime()
      try {
        client.sendBinary(frame)
        val durationMillis = SystemClock.elapsedRealtime() - startMillis
        if (durationMillis > VIDEO_CLIENT_SLOW_WRITE_MILLIS) {
          slowVideoWrites += 1
          recordTicketEvent("video_write_slow", "duration_ms=$durationMillis key=$keyFrame")
        }
      } finally {
        var pendingFrame: ByteArray? = null
        var pendingKeyFrame = false
        synchronized(sendState) {
          sendState.inFlight = false
          sendState.inFlightSinceMillis = 0L
          pendingFrame = sendState.pendingFrame
          pendingKeyFrame = sendState.pendingKeyFrame
          sendState.pendingFrame = null
          sendState.pendingKeyFrame = false
        }
        if (pendingFrame != null) {
          sendVideoFrame(client, pendingFrame!!, pendingKeyFrame)
        }
      }
    }
  }

  private fun handleRootCaptureFrame(frame: TicketRootCaptureFrame) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_H264) {
      return
    }
    encodedFrames += 1
    lastFrameEncodedAtMillis = SystemClock.elapsedRealtime()
    if (frame.keyFrame) {
      keyFrames += 1
      lastKeyFrameEncodedAtMillis = lastFrameEncodedAtMillis
    }
    broadcastFrame(
      keyFrame = frame.keyFrame,
      timestampUs = frame.timestampUs,
      payload = frame.payload
    )
    rootCaptureSnapshot = rootCaptureEngine.snapshot()
  }

  private fun handleRootPngCaptureFrame(frame: TicketRootCaptureFrame) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_PNG) {
      return
    }
    encodedFrames += 1
    keyFrames += 1
    lastFrameEncodedAtMillis = SystemClock.elapsedRealtime()
    lastKeyFrameEncodedAtMillis = lastFrameEncodedAtMillis
    broadcastFrame(
      keyFrame = true,
      timestampUs = frame.timestampUs,
      payload = frame.payload
    )
    rootCaptureSnapshot = rootPngCaptureEngine.snapshot()
  }

  private fun handleRootAv1CaptureFrame(frame: TicketRootCaptureFrame) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_AV1) {
      return
    }
    encodedFrames += 1
    lastFrameEncodedAtMillis = SystemClock.elapsedRealtime()
    if (frame.keyFrame) {
      keyFrames += 1
      lastKeyFrameEncodedAtMillis = lastFrameEncodedAtMillis
    }
    broadcastFrame(
      keyFrame = frame.keyFrame,
      timestampUs = frame.timestampUs,
      payload = frame.payload
    )
    rootCaptureSnapshot = rootAv1CaptureEngine.snapshot()
  }

  private fun handleRootFfmpegH264CaptureFrame(frame: TicketRootCaptureFrame) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
      return
    }
    encodedFrames += 1
    lastFrameEncodedAtMillis = SystemClock.elapsedRealtime()
    if (frame.keyFrame) {
      keyFrames += 1
      lastKeyFrameEncodedAtMillis = lastFrameEncodedAtMillis
    }
    broadcastFrame(
      keyFrame = frame.keyFrame,
      timestampUs = frame.timestampUs,
      payload = frame.payload
    )
    ffmpegCaptureSnapshot = rootFfmpegH264CaptureEngine.snapshot()
  }

  private fun requestKeyFrame(reason: String = "browser_request") {
    val nowMillis = SystemClock.elapsedRealtime()
    lastKeyFrameRequestedAtMillis = nowMillis
    keyFrameRequests += 1
    if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) {
      rootFfmpegH264CaptureEngine.requestKeyFrame(reason)
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_PNG) {
      rootPngCaptureEngine.requestFreshFrame(reason)
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_AV1) {
      rootAv1CaptureEngine.requestKeyFrame(reason)
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_H264) {
      val cachedKeyFrameAge = ageMillis(latestKeyFrameAtMillis, nowMillis)
      if (
        latestKeyFrameEnvelope != null &&
        latestKeyFrameStreamEpoch == streamEpoch &&
        cachedKeyFrameAge != null &&
        cachedKeyFrameAge <= ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
      ) {
        rootCaptureEngine.noteSuppressedRestartRequest("cached_keyframe_available")
        return
      }
      val snapshot = rootCaptureEngine.snapshot(nowMillis)
      val lastKeyFrameAgo = snapshot.lastKeyFrameAgoMillis
      val lastStartAgo = snapshot.lastStartAgoMillis
      if (lastKeyFrameAgo == null && (lastStartAgo == null || lastStartAgo < ROOT_KEYFRAME_INITIAL_WAIT_MILLIS)) {
        rootCaptureEngine.noteSuppressedRestartRequest("waiting_for_initial_keyframe")
        return
      }
      val urgentFreshKeyFrame = isUrgentFreshKeyFrameReason(reason) &&
        lastKeyFrameAgo != null &&
        lastKeyFrameAgo > ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
      if (urgentFreshKeyFrame || lastKeyFrameAgo == null || lastKeyFrameAgo > ROOT_KEYFRAME_RESTART_AFTER_MILLIS) {
        val lastRestartAgo = snapshot.lastRestartAgoMillis
        val restartCooldownMillis = if (urgentFreshKeyFrame) {
          ROOT_URGENT_KEYFRAME_RESTART_COOLDOWN_MILLIS
        } else {
          ROOT_KEYFRAME_RESTART_COOLDOWN_MILLIS
        }
        if (lastRestartAgo != null && lastRestartAgo < restartCooldownMillis) {
          rootCaptureEngine.noteSuppressedRestartRequest("restart_cooldown")
          return
        }
        restartRootCaptureForNewEpoch("keyframe_requested:$reason")
      }
      return
    }
    runCatching {
      encoder?.setParameters(
        Bundle().apply {
          putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
      )
    }.onFailure { error ->
      Log.w(TAG, "key_frame_request_failed", error)
    }
  }

  private fun isUrgentFreshKeyFrameReason(reason: String): Boolean {
    return reason == "viewer_join" ||
      reason == "control_join" ||
      reason == "config_received" ||
      reason == "first_frame_retry" ||
      reason == "first_frame_timeout" ||
      reason == "video_socket_open"
  }

  private fun sendStatus(client: TicketWebSocket) {
    val health = health()
    val payload = json.encodeToString(health)
    val message = json.encodeToString(health.message)
    client.sendText("""{"type":"health","message":$message,"data":$payload}""")
  }

  private fun broadcastStatus() {
    controlClientSnapshot().forEach(::sendStatus)
  }

  private fun nextClientGeneration(): Long {
    lastClientGeneration += 1L
    return lastClientGeneration
  }

  private fun queryParam(query: String, name: String): String? {
    if (query.isBlank()) {
      return null
    }
    return query.split('&')
      .asSequence()
      .mapNotNull { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return@mapNotNull null
        val key = pair.substring(0, separator)
        if (key != name) return@mapNotNull null
        runCatching {
          URLDecoder.decode(pair.substring(separator + 1), Charsets.UTF_8.name())
        }.getOrNull()
      }
      .firstOrNull()
      ?.takeIf { it.isNotBlank() }
  }

  private fun closeDuplicateViewerClients(info: TicketClientInfo) {
    val duplicates = synchronized(clientInfo) {
      clientInfo.filter { (_, existing) ->
        existing.video == info.video
      }.keys.toList()
    }
    duplicates.forEach { client ->
      replacedClientSockets += 1
      recordTicketEvent(
        "client_replaced",
        "${if (info.video) "video" else "control"} viewer=${info.viewerId.orEmpty()} generation=${info.generation}"
      )
      client.close()
    }
  }

  private fun controlClientSnapshot(): List<TicketWebSocket> {
    return synchronized(controlClients) {
      controlClients.toList()
    }
  }

  private fun videoClientSnapshot(): List<TicketWebSocket> {
    return synchronized(videoClients) {
      videoClients.toList()
    }
  }

  private fun closeAllClients(reason: String) {
    val clients = controlClientSnapshot() + videoClientSnapshot()
    if (clients.isEmpty()) {
      return
    }
    recordTicketEvent("clients_closed", "$reason count=${clients.size}")
    clients.forEach { it.close() }
  }

  private fun totalClientCount(): Int = controlClients.size + videoClients.size

  private fun recordClientTelemetry(message: String) {
    synchronized(recentClientTelemetry) {
      recentClientTelemetry.addLast(SystemClock.elapsedRealtime() to message)
      while (recentClientTelemetry.size > RECENT_CLIENT_TELEMETRY_LIMIT) {
        recentClientTelemetry.removeFirst()
      }
    }
    updateLoadingTelemetryFromClientLog(message)
  }

  private fun updateLoadingTelemetryFromClientLog(message: String) {
    val element = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
    val pageVersion = element["pageVersion"]?.jsonPrimitive?.contentOrNull
    if (!pageVersion.isNullOrBlank()) {
      lastClientPageVersion = pageVersion
      lastClientPageVersionAtMillis = SystemClock.elapsedRealtime()
    }
    val event = element["event"]?.jsonPrimitive?.contentOrNull ?: return
    if (event != "loading_finished" && event != "loading_over_2s") {
      return
    }
    val details = element["details"]?.let { runCatching { it.jsonObject }.getOrNull() }
    val phase = details?.get("phase")?.jsonPrimitive?.contentOrNull
    val durationMillis = details?.get("durationMs")?.jsonPrimitive?.longOrNull
    val nowMillis = SystemClock.elapsedRealtime()
    if (event == "loading_finished") {
      lastLoadingPhase = phase
      lastLoadingDurationMillis = durationMillis
      lastLoadingCompletedAtMillis = nowMillis
    } else {
      lastLoadingOverBudgetPhase = phase
      lastLoadingOverBudgetDurationMillis = durationMillis
      lastLoadingOverBudgetAtMillis = nowMillis
      recordTicketEvent("loading_over_2s", "${phase.orEmpty()} duration_ms=${durationMillis ?: -1L}")
    }
  }

  private fun recordTicketEvent(event: String, detail: String = "") {
    synchronized(recentTicketEvents) {
      recentTicketEvents.addLast(Triple(SystemClock.elapsedRealtime(), event, detail.take(MAX_TICKET_EVENT_DETAIL_BYTES)))
      while (recentTicketEvents.size > RECENT_TICKET_EVENT_LIMIT) {
        recentTicketEvents.removeFirst()
      }
    }
    Log.i(TAG, "ticket_event event=$event detail=${detail.take(MAX_TICKET_EVENT_DETAIL_BYTES)}")
  }

  private fun recentClientTelemetrySnapshot(nowMillis: Long): List<TicketClientTelemetry> {
    return synchronized(recentClientTelemetry) {
      recentClientTelemetry.map { (atMillis, message) ->
        TicketClientTelemetry(
          atAgoMillis = (nowMillis - atMillis).coerceAtLeast(0L),
          message = message
        )
      }
    }
  }

  private fun recentTicketEventsSnapshot(nowMillis: Long): List<TicketSessionEvent> {
    return synchronized(recentTicketEvents) {
      recentTicketEvents.map { (atMillis, event, detail) ->
        TicketSessionEvent(
          atAgoMillis = (nowMillis - atMillis).coerceAtLeast(0L),
          event = event,
          detail = detail
        )
      }
    }
  }

  private fun persistRuntimeState(sessionState: String) {
    val nowMillis = SystemClock.elapsedRealtime()
    val activeRootCapture = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> rootFfmpegH264CaptureEngine.snapshot(nowMillis).let { ffmpeg ->
        TicketRootCaptureHealth(
          supported = ffmpeg.available,
          active = ffmpeg.active,
          state = ffmpeg.state,
          message = ffmpeg.message,
          encoderName = ffmpeg.encoderName,
          width = ffmpeg.width,
          height = ffmpeg.height,
          bitrate = ffmpeg.bitrate,
          frames = ffmpeg.frames,
          keyFrames = ffmpeg.keyFrames,
          lastFrameAgoMillis = ffmpeg.lastFrameAgoMillis,
          lastKeyFrameAgoMillis = ffmpeg.lastFrameAgoMillis,
          lastStartAgoMillis = ffmpeg.lastStartAgoMillis,
          restarts = ffmpeg.restartCount,
          lastRestartReason = ffmpeg.lastExitReason,
          lastRestartAgoMillis = ffmpeg.lastExitAgoMillis
        )
      }
      CAPTURE_MODE_ROOT_AV1 -> rootAv1CaptureEngine.snapshot(nowMillis)
      CAPTURE_MODE_ROOT_H264 -> rootCaptureEngine.snapshot(nowMillis)
      else -> rootPngCaptureEngine.snapshot(nowMillis)
    }
    val snapshot = TicketRuntimeSnapshot(
      savedAtElapsedMillis = nowMillis,
      sessionState = sessionState,
      streamActive = streamActive,
      captureMode = activeCaptureMode,
      rootCapture = activeRootCapture,
      lastGoodStreamAgoMillis = ageMillis(lastFrameSentAtMillis, nowMillis),
      fallbackReason = fallbackReason,
      recoveryCounters = TicketRecoveryCounters(
        rootCaptureRestarts = activeRootCapture.restarts,
        keyFrameRequests = keyFrameRequests,
        encodedFrames = encodedFrames,
        sentFrames = sentFrames
      )
    )
    serviceScope.launch {
      runCatching { runtimeStateStore.save(snapshot) }
        .onFailure { error -> Log.w(TAG, "ticket_runtime_state_persist_failed", error) }
    }
  }

  private fun ageMillis(timestampMillis: Long, nowMillis: Long): Long? {
    return timestampMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) }
  }

  private fun resetFrameEpoch(reason: String, active: Boolean) {
    synchronized(encoderLock) {
      streamEpoch = if (active) {
        SystemClock.elapsedRealtime().coerceAtLeast(streamEpoch + 1)
      } else {
        0L
      }
      frameSequence = 0L
      latestKeyFrameStreamEpoch = 0L
      latestKeyFrameSequence = 0L
      latestKeyFrameEnvelope = null
      latestKeyFrameAtMillis = 0L
      latestKeyFrameTimestampUs = 0L
      lastFrameBytes = 0
      lastKeyFrameBytes = 0
      estimatedSendBitrate = 0L
      sendBitrateWindowStartedAtMillis = 0L
      sendBitrateWindowBytes = 0L
    }
    recordTicketEvent("stream_epoch_reset", reason)
  }

  private fun ensureFrameEpoch(reason: String): Long {
    if (streamEpoch == 0L) {
      resetFrameEpoch(reason, active = true)
    }
    return streamEpoch
  }

  private fun restartRootCaptureForNewEpoch(reason: String) {
    resetFrameEpoch("root_capture_restart_$reason", active = true)
    streamSize?.let(::broadcastConfig)
    rootCaptureEngine.restart(reason)
  }

  private fun noteFrameBytes(frameBytes: Int, nowMillis: Long) {
    if (sendBitrateWindowStartedAtMillis == 0L) {
      sendBitrateWindowStartedAtMillis = nowMillis
      sendBitrateWindowBytes = frameBytes.toLong()
      return
    }
    sendBitrateWindowBytes += frameBytes.toLong()
    val elapsedMillis = nowMillis - sendBitrateWindowStartedAtMillis
    if (elapsedMillis >= SEND_BITRATE_WINDOW_MILLIS) {
      estimatedSendBitrate = (sendBitrateWindowBytes * 8_000L) / elapsedMillis.coerceAtLeast(1L)
      sendBitrateWindowStartedAtMillis = nowMillis
      sendBitrateWindowBytes = 0L
    }
  }

  private fun streamPipelineSnapshot(nowMillis: Long): TicketStreamPipeline {
    val rootCapture = activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264 ||
      activeCaptureMode == CAPTURE_MODE_ROOT_PNG ||
      activeCaptureMode == CAPTURE_MODE_ROOT_H264 ||
      activeCaptureMode == CAPTURE_MODE_ROOT_AV1
    val codec = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_CODEC_STRING
      CAPTURE_MODE_ROOT_PNG -> TicketScreenConfig.ROOT_PNG_CODEC_STRING
      CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.H264_CODEC_STRING
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_CODEC_STRING
      CAPTURE_MODE_MEDIAPROJECTION_AV1 -> TicketScreenConfig.AV1_CODEC_STRING
      else -> ""
    }
    val transport = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_TRANSPORT
      CAPTURE_MODE_ROOT_PNG -> TicketScreenConfig.ROOT_PNG_TRANSPORT
      CAPTURE_MODE_ROOT_H264 -> "h264-annexb"
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.ROOT_AV1_TRANSPORT
      CAPTURE_MODE_MEDIAPROJECTION_AV1 -> "av1-webcodecs"
      else -> ""
    }
    val qualityProfile = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_QUALITY_PROFILE
      CAPTURE_MODE_ROOT_PNG -> TicketScreenConfig.ROOT_PNG_QUALITY_PROFILE
      CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.ROOT_CAPTURE_QUALITY_PROFILE
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_CAPTURE_QUALITY_PROFILE
      CAPTURE_MODE_MEDIAPROJECTION_AV1 -> TicketScreenConfig.AV1_CAPTURE_QUALITY_PROFILE
      else -> "idle"
    }
    val rootPngCapture = rootPngCaptureEngine.snapshot(nowMillis)
    val rootH264Capture = rootCaptureEngine.snapshot(nowMillis)
    val rootAv1Capture = rootAv1CaptureEngine.snapshot(nowMillis)
    val ffmpegCapture = rootFfmpegH264CaptureEngine.snapshot(nowMillis)
    return TicketStreamPipeline(
      controlClients = controlClients.size,
      videoClients = videoClients.size,
      captureMode = activeCaptureMode,
      codec = codec,
      transport = transport,
      frameEnvelope = FRAME_ENVELOPE_VERSION,
      streamEpoch = streamEpoch,
      frameSequence = frameSequence,
      lastKeyFrameSequence = latestKeyFrameSequence,
      qualityProfile = qualityProfile,
      configuredWidth = streamSize?.width,
      configuredHeight = streamSize?.height,
      configuredBitrate = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_BITRATE
        CAPTURE_MODE_ROOT_PNG -> null
        CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.ROOT_CAPTURE_BITRATE
        CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_CAPTURE_BITRATE
        CAPTURE_MODE_MEDIAPROJECTION_AV1 -> TicketScreenConfig.AV1_CAPTURE_BITRATE
        else -> null
      },
      lastFrameBytes = lastFrameBytes,
      lastKeyFrameBytes = lastKeyFrameBytes,
      estimatedSendBitrate = estimatedSendBitrate,
      freshKeyFrameCacheMaxAgeMillis = ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS,
      encoderRunning = encoderJob?.isActive == true || ffmpegCapture.active || rootPngCapture.active || rootH264Capture.active || rootAv1Capture.active,
      streamConfigured = streamSize != null,
      encodedFrames = encodedFrames,
      sentFrames = sentFrames,
      keyFrames = keyFrames,
      droppedVideoFrames = droppedVideoFrames,
      slowVideoWrites = slowVideoWrites,
      closedSlowVideoClients = closedSlowVideoClients,
      replacedClientSockets = replacedClientSockets,
      lastClientGeneration = lastClientGeneration,
      lastEncoderStartAgoMillis = ageMillis(lastEncoderStartAtMillis, nowMillis),
      lastConfigSentAgoMillis = ageMillis(lastConfigSentAtMillis, nowMillis),
      lastFrameEncodedAgoMillis = ageMillis(lastFrameEncodedAtMillis, nowMillis),
      lastKeyFrameEncodedAgoMillis = ageMillis(lastKeyFrameEncodedAtMillis, nowMillis),
      lastFrameSentAgoMillis = ageMillis(lastFrameSentAtMillis, nowMillis),
      lastKeyFrameRequestedAgoMillis = ageMillis(lastKeyFrameRequestedAtMillis, nowMillis),
      lastVideoClientConnectedAgoMillis = ageMillis(lastVideoClientConnectedAtMillis, nowMillis),
      clients = clientConnectionSnapshot(),
      secureWindowCaptureBypassActive = secureWindowCaptureBypassActive,
      secureWindowCaptureBypassMessage = secureWindowCaptureBypassMessage
    )
  }

  private fun clientConnectionSnapshot(): List<TicketClientConnectionHealth> {
    return synchronized(clientInfo) {
      clientInfo.values
        .sortedWith(compareBy<TicketClientInfo> { it.video }.thenBy { it.generation })
        .map { info ->
          TicketClientConnectionHealth(
            kind = if (info.video) "video" else "control",
            viewerId = info.viewerId,
            pageId = info.pageId,
            pageVersion = info.pageVersion,
            generation = info.generation
          )
        }
    }
  }

  private fun health(): TicketStreamHealth {
    val nowMillis = SystemClock.elapsedRealtime()
    val installedStores = TicketPackageSupport.installedLocalStores(this)
    val av1 = TicketAv1Support.isHardwareEncoderAvailable()
    val h264 = TicketH264Support.isHardwareEncoderAvailable()
    val rootPngCapture = rootPngCaptureEngine.snapshot(nowMillis)
    val rootH264Capture = rootCaptureEngine.snapshot(nowMillis)
    val rootAv1Capture = rootAv1CaptureEngine.snapshot(nowMillis)
    val ffmpegCapture = rootFfmpegH264CaptureEngine.snapshot(nowMillis)
    val rootCapture = when {
      activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketRootCaptureHealth(
        supported = ffmpegCapture.available,
        active = ffmpegCapture.active,
        state = ffmpegCapture.state,
        message = ffmpegCapture.message,
        encoderName = ffmpegCapture.encoderName,
        width = ffmpegCapture.width,
        height = ffmpegCapture.height,
        bitrate = ffmpegCapture.bitrate,
        frames = ffmpegCapture.frames,
        keyFrames = ffmpegCapture.keyFrames,
        lastFrameAgoMillis = ffmpegCapture.lastFrameAgoMillis,
        lastKeyFrameAgoMillis = ffmpegCapture.lastFrameAgoMillis,
        lastStartAgoMillis = ffmpegCapture.lastStartAgoMillis,
        restarts = ffmpegCapture.restartCount,
        lastRestartReason = ffmpegCapture.lastExitReason,
        lastRestartAgoMillis = ffmpegCapture.lastExitAgoMillis
      )
      activeCaptureMode == CAPTURE_MODE_ROOT_AV1 -> rootAv1Capture
      activeCaptureMode == CAPTURE_MODE_ROOT_H264 && rootH264Capture.supported -> rootH264Capture
      activeCaptureMode == CAPTURE_MODE_ROOT_PNG -> rootPngCapture
      ffmpegCapture.available -> TicketRootCaptureHealth(
        supported = true,
        active = ffmpegCapture.active,
        state = ffmpegCapture.state,
        message = ffmpegCapture.message,
        encoderName = ffmpegCapture.encoderName
      )
      rootPngCapture.supported -> rootPngCapture
      rootAv1Capture.supported -> rootAv1Capture
      else -> rootPngCapture
    }
    val vivi = TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)
    val ok = running.get() && vivi && ffmpegCapture.available
    val visibleFrameCodec = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> TicketScreenConfig.ROOT_FFMPEG_H264_CODEC_STRING
      CAPTURE_MODE_ROOT_PNG -> TicketScreenConfig.ROOT_PNG_CODEC_STRING
      CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.H264_CODEC_STRING
      CAPTURE_MODE_ROOT_AV1 -> TicketScreenConfig.AV1_CODEC_STRING
      CAPTURE_MODE_MEDIAPROJECTION_AV1 -> TicketScreenConfig.AV1_CODEC_STRING
      else -> ""
    }
    val recoverySnapshot = ticketRecoveryCoordinator.snapshot(nowMillis)
    val viviHealth = viviStateMemory.health(nowMillis)
    val effectiveSessionState = if (
      streamActive &&
      ticketSessionState in setOf(TICKET_SESSION_SOFT_RECOVERY, TICKET_SESSION_NEEDS_ATTENTION) &&
      recoverySnapshot.state != "running" &&
      viviHealth.state == TicketViviRecoveryState.TICKET_DETAIL.name
    ) {
      TICKET_SESSION_LIVE
    } else {
      ticketSessionState
    }
    return TicketStreamHealth(
      ok = ok,
      serverVersion = SERVER_VERSION,
      sessionState = effectiveSessionState,
      serverRunning = running.get(),
      av1HardwareEncoderAvailable = av1,
      h264HardwareEncoderAvailable = h264,
      projectionReady = mediaProjection != null && !mediaProjectionConsumed,
      capturePermissionPending = pendingStartAfterProjection || projectionPrewarmPending,
      viviInstalled = vivi,
      accrescentInstalled = TicketScreenConfig.ACCRESCENT_PACKAGE in installedStores,
      installedStorePackages = installedStores,
      streamActive = streamActive,
      clients = totalClientCount(),
      inactivityActive = ticketSessionOpen(),
      inactivityTimeoutMillis = TicketInactivityPolicy.TIMEOUT_MILLIS,
      inactivityRemainingMillis = inactivityRemainingMillis(nowMillis),
      autoStartAllowed = TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(lastSessionStopReason),
      autoStartBlockedReason = lastSessionStopReason?.takeUnless {
        TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(it)
      },
      serviceReadiness = serviceReadinessSnapshot(),
      rootCapture = rootCapture,
      webrtc = TicketWebRtcHealth(
        enabled = false,
        message = "WebRTC is served by ticket_remote; Pixel emits FFmpeg H.264 frames for public ticket streaming"
      ),
      inputGate = TicketInputGateHealth(
        tapOnly = true,
        active = streamActive,
        allowed = inputGateAllowed && streamActive && activeCaptureMode != CAPTURE_MODE_IDLE,
        reason = if (streamActive) inputGateReason else "no_active_control",
        lastDecisionAgoMillis = ageMillis(inputGateChangedAtMillis, nowMillis),
        lastCommandReason = lastInputCommandReason,
        lastCommandDurationMillis = lastInputCommandDurationMillis,
        lastCommandCompletedAgoMillis = ageMillis(lastInputCommandCompletedAtMillis, nowMillis),
        lastInputId = lastInputResultId,
        lastInputKind = lastInputResultKind,
        lastInputAccepted = lastInputResultAccepted,
        lastInputReason = lastInputResultReason,
        lastInputTotalDurationMillis = lastInputResultTotalDurationMillis,
        lastInputCompletedAgoMillis = ageMillis(lastInputResultCompletedAtMillis, nowMillis),
        duplicateInputResults = duplicateInputResultCount,
        lastDuplicateInputId = lastDuplicateInputId,
        lastDuplicateInputAgoMillis = ageMillis(lastDuplicateInputResultAtMillis, nowMillis)
      ),
      controlCodeMode = TicketControlCodeModeHealth(
        active = controlCodeModeActive && streamActive,
        entryId = controlCodeModeEntryId,
        enteredAgoMillis = ageMillis(controlCodeModeEnteredAtMillis, nowMillis),
        transitionGraceActive = nowMillis < controlCodeTransitionGraceUntilMillis,
        transitionGraceRemainingMillis = (controlCodeTransitionGraceUntilMillis - nowMillis).coerceAtLeast(0L)
      ),
      controlExitCleanup = TicketControlExitCleanupHealth(
        lastReason = lastControlExitCleanupReason,
        lastDetectedState = lastControlExitCleanupDetectedState,
        lastCloseAction = lastControlExitCleanupCloseAction,
        lastDurationMillis = lastControlExitCleanupDurationMillis,
        lastCompletedAgoMillis = ageMillis(lastControlExitCleanupCompletedAtMillis, nowMillis),
        lastVerificationResult = lastControlExitCleanupVerificationResult,
        lastSucceeded = lastControlExitCleanupSucceeded,
        lastFreshFrameRequested = lastControlExitCleanupFreshFrameRequested
      ),
      loading = TicketLoadingHealth(
        lastPhase = lastLoadingPhase,
        lastDurationMillis = lastLoadingDurationMillis,
        lastCompletedAgoMillis = ageMillis(lastLoadingCompletedAtMillis, nowMillis),
        lastOverBudgetPhase = lastLoadingOverBudgetPhase,
        lastOverBudgetDurationMillis = lastLoadingOverBudgetDurationMillis,
        lastOverBudgetAgoMillis = ageMillis(lastLoadingOverBudgetAtMillis, nowMillis)
      ),
      page = TicketPageHealth(
        htmlVersion = SERVER_VERSION,
        cachePolicy = "no-store_html_clear_cache_only",
        lastRootHtmlRequestAgoMillis = ageMillis(lastRootHtmlRequestAtMillis, nowMillis),
        lastBootstrapRequestAgoMillis = ageMillis(lastBootstrapRequestAtMillis, nowMillis),
        lastCacheCleanupRequestAgoMillis = ageMillis(lastCacheCleanupRequestAtMillis, nowMillis),
        lastClientPageVersion = lastClientPageVersion,
        lastClientPageVersionAgoMillis = ageMillis(lastClientPageVersionAtMillis, nowMillis)
      ),
      notificationLockdown = TicketNotificationLockdownHealth(
        active = notificationLockdownActive,
        reason = notificationLockdownReason,
        lastChangedAgoMillis = ageMillis(notificationLockdownChangedAtMillis, nowMillis)
      ),
      brightnessGuard = TicketBrightnessGuardHealth(
        active = ticketBrightnessGuardActive,
        targetPercent = TICKET_SAFE_DIM_PERCENT,
        currentDisplayPercent = ticketBrightnessGuardCurrentDisplayPercent,
        currentPanelBrightness = ticketBrightnessGuardCurrentPanelBrightness,
        currentPanelMaxBrightness = ticketBrightnessGuardCurrentPanelMaxBrightness,
        lastEnforcedAgoMillis = ageMillis(ticketBrightnessGuardLastEnforcedAtMillis, nowMillis),
        failures = ticketBrightnessGuardFailures,
        lastReason = ticketBrightnessGuardLastReason,
        message = ticketBrightnessGuardLastMessage
      ),
      visibleFrame = TicketVisibleFrameHealth(
        codec = visibleFrameCodec,
        lastFrameAgoMillis = ageMillis(lastFrameSentAtMillis, nowMillis),
        lastKeyFrameAgoMillis = ageMillis(lastKeyFrameEncodedAtMillis, nowMillis),
        message = when {
          lastFrameSentAtMillis > 0L -> "Frames are being sent to connected viewers"
          streamActive -> "Waiting to send the first visible frame"
          else -> "No visible frame has been sent yet"
        }
      ),
      ffmpeg = ffmpegCapture,
      recovery = recoverySnapshot,
      ticketState = TicketControlStateHealth(
        state = effectiveSessionState,
        stateAgeMillis = ageMillis(ticketSessionStateChangedAtMillis, nowMillis),
        lastReason = ticketSessionStateReason,
        lastOverBudgetState = lastOverBudgetTicketState,
        lastOverBudgetDurationMillis = lastOverBudgetTicketStateDurationMillis,
        lastOverBudgetReason = lastOverBudgetTicketStateReason,
        hardResetCount = viviHardResetCount,
        lastHardResetReason = lastViviHardResetReason,
        lastHardResetAgoMillis = ageMillis(lastViviHardResetAtMillis, nowMillis)
      ),
      viviState = viviHealth,
      streamPipeline = streamPipelineSnapshot(nowMillis),
      recentClientTelemetry = recentClientTelemetrySnapshot(nowMillis),
      recentEvents = recentTicketEventsSnapshot(nowMillis),
      message = when {
        !vivi -> "ViVi is not installed from a local Pixel app store yet"
        streamActive -> lastMessage
        pendingStartAfterProjection -> "Legacy screen-capture permission path is waiting on the Pixel"
        activeCaptureMode == CAPTURE_MODE_MEDIAPROJECTION_AV1 && mediaProjection == null -> "Screen capture permission is not ready"
        activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264 && !ffmpegCapture.available -> ffmpegCapture.message
        activeCaptureMode == CAPTURE_MODE_ROOT_AV1 && !rootAv1Capture.supported -> rootAv1Capture.message
        rootCapture.supported -> lastMessage
        else -> lastMessage
      }
    )
  }

  private fun launchVivi() {
    val launchIntent = packageManager.getLaunchIntentForPackage(TicketScreenConfig.VIVI_PACKAGE)
    if (launchIntent == null) {
      lastMessage = "ViVi launch intent is unavailable"
      return
    }
    startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    scheduleTicketBrightnessGuard("vivi_launch")
  }

  private suspend fun launchViviUnlessFastTicketDetail(reason: String) {
    val fastState = ticketAutopilot.observeFastState(reason)
    if (fastState?.state == TicketViviRecoveryState.TICKET_DETAIL) {
      Log.i(TAG, "ticket_launch_skipped_fast_ticket_detail reason=$reason")
      scheduleTicketBrightnessGuard("vivi_fast_ticket_detail")
      return
    }
    launchVivi()
  }

  private suspend fun prepareViviForRootPngCapture(reason: String): TicketAutopilotResult {
    val result = ticketAutopilot.driveToTicketDetail(
      reason = reason,
      forceFreshLaunch = false,
      allowControlCodePopup = true,
      restartPolicy = TicketAutopilotRestartPolicy.SOFT_RECOVERY,
      maxSteps = ROOT_PNG_START_AUTOPILOT_MAX_STEPS,
      timeoutMillis = ROOT_PNG_START_AUTOPILOT_TIMEOUT_MILLIS
    )
    val modeName = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_FFMPEG_H264 -> "root_ffmpeg_h264"
      CAPTURE_MODE_ROOT_AV1 -> "root_av1"
      else -> "root_png"
    }
    recordTicketEvent("${modeName}_vivi_prepare", "${result.state}:${result.step}:success=${result.success}")
    if (result.success) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "${modeName}_vivi_ready_$reason")
    } else {
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "${modeName}_vivi_prepare_${result.state.name.lowercase()}")
    }
    return result
  }

  private fun scheduleAv1CaptureStart(reason: String, suppressBlackout: Boolean) {
    serviceScope.launch {
      if (suppressBlackout) {
        suppressBlackoutOverlayForRemote()
      }
      launchViviUnlessFastTicketDetail(reason)
      delay(AV1_ENCODER_START_SETTLE_MILLIS)
      ensureEncoderIfPossible()
      requestKeyFrame("vivi_ready:$reason")
      broadcastStatus()
    }
  }

  private fun scheduleRootPngCaptureStart(reason: String, suppressBlackout: Boolean) {
    serviceScope.launch {
      if (suppressBlackout) {
        suppressBlackoutOverlayForRemote()
      }
      val modeName = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_FFMPEG_H264 -> "root_ffmpeg_h264"
        CAPTURE_MODE_ROOT_AV1 -> "root_av1"
        else -> "root_png"
      }
      updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_prepare_$reason")
      launchViviUnlessFastTicketDetail(reason)
      delay(PRE_CAPTURE_APP_SETTLE_MILLIS)
      ensureEncoderIfPossible()
      requestKeyFrame("vivi_ready:$reason")
      broadcastStatus()
      val prepareResult = prepareViviForRootPngCapture(reason)
      if (prepareResult.success) {
        updateTicketSessionState(TICKET_SESSION_LIVE, "${modeName}_capture_ready")
        lastMessage = when (activeCaptureMode) {
          CAPTURE_MODE_ROOT_FFMPEG_H264 -> "Ticket session is active through FFmpeg H.264 capture"
          CAPTURE_MODE_ROOT_AV1 -> "Ticket session is active through root-fed AV1 capture"
          else -> "Ticket session is active through root lossless capture"
        }
      } else {
        lastMessage = "Ticket stream is live, but ViVi needs attention"
      }
      broadcastStatus()
    }
  }

  private fun scheduleTicketRecovery(
    reason: String,
    mode: TicketRecoveryMode = TicketRecoveryMode.ACTIVE_SOFT
  ) {
    if (streamActive) {
      updateTicketSessionState(
        if (mode == TicketRecoveryMode.ACTIVE_SOFT) TICKET_SESSION_SOFT_RECOVERY else TICKET_SESSION_STARTING,
        "recovery_scheduled_$reason"
      )
    }
    recordTicketEvent("recovery_scheduled", "$reason mode=$mode")
    ticketRecoveryCoordinator.schedule(reason, mode)
    broadcastStatus()
  }

  private fun onTicketRecoveryResult(reason: String, mode: TicketRecoveryMode, success: Boolean) {
    recordTicketEvent(
      if (success) "recovery_succeeded" else "recovery_failed",
      "$reason mode=$mode"
    )
    if (streamActive) {
      val currentViviState = viviStateMemory.current().state
      updateTicketSessionState(
        when {
          success -> TICKET_SESSION_LIVE
          currentViviState == TicketViviRecoveryState.TICKET_DETAIL -> TICKET_SESSION_LIVE
          else -> TICKET_SESSION_NEEDS_ATTENTION
        },
        "recovery_result_$reason"
      )
      broadcastStatus()
    }
  }

  private suspend fun returnToOrchestrator() {
    val component = "$packageName/.app.MainActivity"
    val result = rootExecutor.run("am start -n $component")
    if (result.ok) {
      return
    }
    Log.w(TAG, "ticket_return_orchestrator_root_launch_failed stderr=${result.stderr}")
    val intent = Intent(this, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    startActivity(intent)
  }

  private suspend fun rememberTicketBrightnessState() {
    val current = readBrightnessState()
    val touchRestore = readTouchBrightnessRestoreState()
    val preferred = when {
      current != null && !current.looksLikeTouchDim() && !current.looksLikeForcedMaximum() -> current
      touchRestore != null -> touchRestore
      current != null -> current
      else -> null
    }
    if (preferred != null) {
      ticketBrightnessState = preferred
      PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(preferred)
    } else {
      PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(null)
    }
  }

  private suspend fun readBrightnessState(): ScreenBrightnessState? {
    return runCatching {
      val result = rootExecutor.runScript(ScreenBrightnessControl.buildReadStateScript())
      if (result.ok) {
        ScreenBrightnessControl.parseState(result.stdout)
      } else {
        Log.w(TAG, "ticket_brightness_read_failed stderr=${result.stderr}")
        null
      }
    }.getOrElse { error ->
      Log.w(TAG, "ticket_brightness_read_failed", error)
      null
    }
  }

  private fun readTouchBrightnessRestoreState(): ScreenBrightnessState? {
    return runCatching {
      val snapshot = PhoneAutomationPreferencesStore(this).load()
      if (snapshot.touchBrightnessRestoreMode == null && snapshot.touchBrightnessRestoreValue == null) {
        null
      } else {
        ScreenBrightnessState(
          mode = snapshot.touchBrightnessRestoreMode,
          value = snapshot.touchBrightnessRestoreValue
        )
      }
    }.getOrNull()
  }

  private fun ScreenBrightnessState.looksLikeTouchDim(): Boolean {
    val display = displayPercentage
    if (display != null) {
      return display <= DIM_DISPLAY_PERCENT
    }
    return value != null && value <= DIM_LEGACY_BRIGHTNESS_VALUE
  }

  private fun ScreenBrightnessState.looksLikeForcedMaximum(): Boolean {
    val display = displayPercentage
    if (display != null) {
      return display >= MAX_DISPLAY_PERCENT
    }
    return value != null && value >= MAX_LEGACY_BRIGHTNESS_VALUE
  }

  private fun scheduleTicketBrightnessGuard(reason: String) {
    brightnessGuardJob?.cancel()
    ticketBrightnessGuardActive = true
    ticketBrightnessGuardLastReason = reason
    ticketBrightnessGuardLastMessage = "Ticket brightness guard is enforcing safe dim brightness"
    brightnessGuardJob = serviceScope.launch {
      var previousDelay = 0L
      for (targetDelay in BRIGHTNESS_GUARD_DELAYS_MILLIS) {
        delay(targetDelay - previousDelay)
        previousDelay = targetDelay
        if (!ticketBrightnessGuardShouldContinue()) {
          ticketBrightnessGuardActive = false
          ticketBrightnessGuardLastMessage = "Ticket brightness guard stopped"
          return@launch
        }
        val activeSession = streamActive || pendingStartAfterProjection
        if (activeSession) {
          holdTicketScreenAwake("brightness_guard_$reason")
          if (!ticketScreenInteractive()) {
            requestTicketScreenWake("brightness_guard_$reason")
          }
          hideBlackoutOverlay()
        } else {
          releaseTicketScreenAwake()
          if (ticketBrightnessGuardPausedForPhysicalUse()) {
            ticketBrightnessGuardActive = false
            ticketBrightnessGuardLastMessage = "Ticket brightness guard paused for physical phone use"
            return@launch
          }
        }
        enforceTicketSafeBrightness(reason)
      }
      while (ticketBrightnessGuardShouldContinue()) {
        delay(BRIGHTNESS_GUARD_REPEAT_MILLIS)
        val activeSession = streamActive || pendingStartAfterProjection
        if (activeSession) {
          holdTicketScreenAwake("brightness_guard_$reason")
          if (!ticketScreenInteractive()) {
            requestTicketScreenWake("brightness_guard_$reason")
          }
          hideBlackoutOverlay()
        } else {
          releaseTicketScreenAwake()
          if (ticketBrightnessGuardPausedForPhysicalUse()) {
            ticketBrightnessGuardActive = false
            ticketBrightnessGuardLastMessage = "Ticket brightness guard paused for physical phone use"
            return@launch
          }
        }
        enforceTicketSafeBrightness(reason)
      }
      ticketBrightnessGuardActive = false
      ticketBrightnessGuardLastMessage = "Ticket brightness guard stopped"
    }
  }

  private fun ticketBrightnessGuardShouldContinue(): Boolean {
    if (streamActive || pendingStartAfterProjection) {
      return true
    }
    return runCatching { TicketServicePreferencesStore(this).load().enabled }.getOrDefault(false)
  }

  private fun ticketBrightnessGuardPausedForPhysicalUse(): Boolean {
    val snapshot = touchBrightnessSnapshot() ?: return false
    return snapshot.touchBrightnessEnabled && snapshot.touchBrightnessState == TouchBrightnessRuntimeState.BRIGHT
  }

  private fun touchBrightnessSnapshot() = runCatching {
    PhoneAutomationPreferencesStore(this).load()
  }.getOrNull()

  private suspend fun enforceTicketSafeBrightness(reason: String) {
    runCatching {
      val result = rootExecutor.runScript(ScreenBrightnessControl.buildSetPercentScript(TICKET_SAFE_DIM_PERCENT))
      if (result.ok) {
        val current = readBrightnessState()
        ticketBrightnessGuardCurrentDisplayPercent = current?.displayPercentage
        ticketBrightnessGuardCurrentPanelBrightness = current?.panelActualBrightness ?: current?.panelBrightness
        ticketBrightnessGuardCurrentPanelMaxBrightness = current?.panelMaxBrightness
        ticketBrightnessGuardLastEnforcedAtMillis = SystemClock.elapsedRealtime()
        ticketBrightnessGuardLastReason = reason
        ticketBrightnessGuardLastMessage = "Ticket brightness guard enforced safe dim brightness"
        Log.i(TAG, "ticket_brightness_safe_dim_set reason=$reason percent=$TICKET_SAFE_DIM_PERCENT")
      } else {
        ticketBrightnessGuardFailures += 1
        ticketBrightnessGuardLastReason = reason
        ticketBrightnessGuardLastMessage = "Ticket brightness guard failed: ${result.stderr.ifBlank { result.stdout }.take(96)}"
        Log.w(TAG, "ticket_brightness_safe_dim_failed reason=$reason stderr=${result.stderr}")
      }
    }.onFailure { error ->
      ticketBrightnessGuardFailures += 1
      ticketBrightnessGuardLastReason = reason
      ticketBrightnessGuardLastMessage = "Ticket brightness guard failed: ${error.message ?: error::class.java.simpleName}"
      Log.w(TAG, "ticket_brightness_safe_dim_failed reason=$reason", error)
    }
  }

  private fun refreshPhoneAutomation() {
    runCatching {
      startService(
        Intent(this, SupervisorService::class.java)
          .setAction(SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION)
      )
    }.onFailure { error ->
      Log.w(TAG, "ticket_phone_automation_refresh_failed", error)
    }
  }

  private suspend fun restoreTicketBrightness(reason: String) {
    val state = ticketBrightnessState ?: readTouchBrightnessRestoreState() ?: return
    val result = rootExecutor.runScript(ScreenBrightnessControl.buildRestoreScript(state))
    if (result.ok) {
      val current = readBrightnessState()
      ticketBrightnessGuardCurrentDisplayPercent = current?.displayPercentage
      ticketBrightnessGuardCurrentPanelBrightness = current?.panelActualBrightness ?: current?.panelBrightness
      ticketBrightnessGuardCurrentPanelMaxBrightness = current?.panelMaxBrightness
      ticketBrightnessGuardLastEnforcedAtMillis = SystemClock.elapsedRealtime()
      ticketBrightnessGuardLastReason = reason
      ticketBrightnessGuardLastMessage = "Ticket brightness guard restored saved brightness"
      Log.i(
        TAG,
        "ticket_brightness_restored reason=$reason mode=${state.mode} value=${state.value} display=${state.displayPercentage} panel=${state.panelActualBrightness ?: state.panelBrightness}/${state.panelMaxBrightness}"
      )
    } else {
      Log.w(TAG, "ticket_brightness_restore_failed reason=$reason stderr=${result.stderr}")
    }
  }

  private suspend fun canForwardRemoteInput(): Boolean {
    if (!streamActive) {
      recordInputGateDecision(allowed = false, reason = "no_active_control")
      return false
    }
    val violation = cachedForegroundViolation() ?: return true
    recordInputGateDecision(allowed = false, reason = violation)
    Log.i(TAG, "ticket_remote_input_blocked reason=$violation")
    return false
  }

  private suspend fun requireViviForegroundForRemoteTap(): Boolean {
    val violation = foregroundViolationReason(allowStartupSystemUi = false)
    cacheForegroundViolation(violation)
    if (violation == null) {
      return true
    }
    val reason = "vivi_not_foreground_for_tap:$violation"
    recordInputGateDecision(allowed = false, reason = reason)
    recordTicketEvent("remote_tap_blocked", reason)
    Log.i(TAG, "ticket_remote_tap_blocked reason=$reason")
    return false
  }

  private suspend fun requireFastViviSandboxForRemoteTap(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    val suspiciousCoordinate = isSettingsCoordinateTap(size, encodedX, encodedY) ||
      isProfileCoordinateTap(size, encodedX, encodedY)
    if (suspiciousCoordinate) {
      val x = size.sourceX(encodedX)
      val y = size.sourceY(encodedY)
      val dump = dumpViviHierarchy()
      if (dump.ok && dump.stdout.isNotBlank()) {
        if (TicketViviPageEnforcer.isControlCodeButtonTap(dump.stdout, x, y)) {
          recordInputGateDecision(allowed = true, reason = "control_code_button_tap")
          markControlCodeTransition("control_code_button_tap")
          return true
        }
        if (TicketViviPageEnforcer.isForbiddenViviTap(dump.stdout, x, y)) {
          recordInputGateDecision(allowed = false, reason = "vivi_forbidden_node_tap_blocked")
          ignoreProtectedRemoteInput("vivi_forbidden_node_tap_blocked")
          return false
        }
        recordInputGateDecision(allowed = true, reason = "vivi_tap_allowed_by_hierarchy")
        return true
      }
      val fallbackReason = if (isProfileCoordinateTap(size, encodedX, encodedY)) {
        "vivi_profile_tap_blocked"
      } else {
        "vivi_settings_tap_blocked"
      }
      recordInputGateDecision(allowed = false, reason = fallbackReason)
      ignoreProtectedRemoteInput(fallbackReason)
      return false
    }
    recordInputGateDecision(allowed = true, reason = "vivi_tap_allowed")
    return true
  }

  private fun isSettingsCoordinateTap(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    return encodedX <= (size.width * VIVI_SETTINGS_MAX_X_FRACTION).roundToInt() &&
      encodedY <= (size.height * VIVI_SETTINGS_MAX_Y_FRACTION).roundToInt()
  }

  private fun isProfileCoordinateTap(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    return encodedX in (size.width * VIVI_PROFILE_MIN_X_FRACTION).roundToInt()..
      (size.width * VIVI_PROFILE_MAX_X_FRACTION).roundToInt() &&
      encodedY >= (size.height * VIVI_BOTTOM_NAV_MIN_Y_FRACTION).roundToInt()
  }

  private fun isLikelyControlCodeCoordinateTap(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    return encodedX in (size.width * VIVI_CONTROL_CODE_MIN_X_FRACTION).roundToInt()..
      (size.width * VIVI_CONTROL_CODE_MAX_X_FRACTION).roundToInt() &&
      encodedY in (size.height * VIVI_CONTROL_CODE_MIN_Y_FRACTION).roundToInt()..
      (size.height * VIVI_CONTROL_CODE_MAX_Y_FRACTION).roundToInt()
  }

  private fun recordInputGateDecision(allowed: Boolean, reason: String) {
    inputGateAllowed = allowed
    inputGateReason = reason
    inputGateChangedAtMillis = SystemClock.elapsedRealtime()
  }

  private fun markControlCodeTransition(reason: String) {
    controlCodeTransitionGraceUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_TRANSITION_GRACE_MILLIS
    updateTicketSessionState(TICKET_SESSION_CONTROL_TRANSITION, reason)
    recordTicketEvent("control_code_transition", reason)
    broadcastStatus()
  }

  private fun controlSensitiveWindowActive(): Boolean {
    val now = SystemClock.elapsedRealtime()
    return controlCodeModeActive ||
      now < controlCodeTransitionGraceUntilMillis ||
      ticketSessionState == TICKET_SESSION_CONTROL_EXIT ||
      ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION
  }

  private fun markControlCodeModeEntered(reason: String) {
    if (controlCodeModeActive) {
      return
    }
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT || ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION) {
      recordTicketEvent("control_code_enter_ignored", "$reason state=$ticketSessionState")
      return
    }
    controlCodeModeActive = true
    controlCodeModeEntryId += 1
    controlCodeModeEnteredAtMillis = SystemClock.elapsedRealtime()
    updateTicketSessionState(TICKET_SESSION_CONTROL_ACTIVE, reason)
    Log.i(TAG, "ticket_control_code_mode_entered reason=$reason entry=$controlCodeModeEntryId")
    recordTicketEvent("control_code_entered", reason)
    broadcastStatus()
  }

  private fun resetControlCodeMode(reason: String, broadcast: Boolean = true) {
    if (!controlCodeModeActive && controlCodeModeEnteredAtMillis == 0L) {
      controlCodePopupReadyUntilMillis = 0L
      return
    }
    controlCodeModeActive = false
    controlCodeModeEnteredAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    if (streamActive && !reason.startsWith("session_stop_") && reason != "foreground_guard_cancelled") {
      updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)
    }
    Log.i(TAG, "ticket_control_code_mode_reset reason=$reason entry=$controlCodeModeEntryId")
    recordTicketEvent("control_code_reset", reason)
    if (broadcast) {
      broadcastStatus()
    }
    scheduleControlCodeSoftCheck(reason)
  }

  private fun isSoftControlExitReason(reason: String): Boolean {
    return reason == "control_released" ||
      reason == "control_expired" ||
      reason.startsWith("control_code_closed_") ||
      reason.startsWith("control_exit")
  }

  private fun scheduleControlExitSoftSettle(reason: String) {
    if (!streamActive) {
      return
    }
    val transitionInFlight = SystemClock.elapsedRealtime() < controlCodeTransitionGraceUntilMillis
    postRemoteTapForegroundCheckJob?.cancel()
    updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)
    recordTicketEvent("control_exit_soft_settle", reason)
    val hadControlMode = controlCodeModeActive || controlCodeModeEnteredAtMillis > 0L
    if (hadControlMode) {
      controlExitPopupLikelyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
    }
    if (!transitionInFlight) {
      controlCodeTransitionGraceUntilMillis = 0L
    }
    resetControlCodeMode(reason, broadcast = false)
    if (!hadControlMode) {
      scheduleControlCodeSoftCheck(reason)
    }
    broadcastStatus()
  }

  private fun scheduleControlCodeSoftCheck(reason: String) {
    if (!streamActive || reason.startsWith("session_stop_") || reason == "foreground_guard_cancelled") {
      return
    }
    controlCodeSoftCheckJob?.cancel()
    controlCodeSoftCheckJob = serviceScope.launch {
      val now = SystemClock.elapsedRealtime()
      val transitionDelayMillis = (controlCodeTransitionGraceUntilMillis - now).coerceAtLeast(0L)
      delay(CONTROL_CODE_SOFT_CHECK_DELAY_MILLIS + transitionDelayMillis)
      val result = ticketAutopilot.observeFastState("control_code_soft_check:$reason")
      when (result?.state) {
        TicketViviRecoveryState.CONTROL_CODE_POPUP,
        TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
          if (isSoftControlExitReason(reason)) {
            val closed = withTimeoutOrNull(CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS) {
              trySoftCloseControlCodeSurface(reason)
            } == true
            if (!closed) {
              recordTicketEvent("control_code_soft_check_needs_attention", result.state.name)
              updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_code_soft_check_$reason")
              broadcastStatus()
            }
          } else if (result.state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
            controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
            markControlCodeModeEntered("control_code_soft_check_popup")
          } else {
            markControlCodeModeEntered("control_code_soft_check_result")
            recordTicketEvent("control_code_result_visible", reason)
          }
        }
        TicketViviRecoveryState.TICKET_DETAIL -> {
          if (isSoftControlExitReason(reason)) {
            completeControlExitCleanup(
              reason = reason,
              detectedState = result.state.name,
              closeAction = "none",
              startedAtMillis = SystemClock.elapsedRealtime(),
              verificationResult = result.state.name,
              freshFrameRequested = true
            )
          } else {
            updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_soft_check_ok")
            recordTicketEvent("control_code_soft_check_ok", result.state.name)
          }
        }
        else -> {
          val closed = if (isSoftControlExitReason(reason)) {
            withTimeoutOrNull(CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS) {
              trySoftCloseControlCodeSurface(reason)
            } == true
          } else {
            false
          }
          if (!closed) {
            recordTicketEvent("control_code_soft_check_needs_attention", result?.state?.name ?: "UNKNOWN")
            updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_code_soft_check_$reason")
            broadcastStatus()
          }
        }
      }
    }
  }

  private suspend fun trySoftCloseControlCodeSurface(reason: String): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val violation = controlExitForegroundViolationReason()
    cacheForegroundViolation(violation)
    if (violation != null) {
      recordTicketEvent("control_exit_popup_close_blocked", violation)
      recordControlExitCleanup(reason, "foreground_violation", "none", startedAtMillis, violation, false, false)
      return false
    }
    val fastState = ticketAutopilot.observeFastState("control_exit_fast:$reason")
    if (fastState?.state == TicketViviRecoveryState.TICKET_DETAIL) {
      return completeControlExitCleanup(
        reason = reason,
        detectedState = fastState.state.name,
        closeAction = "none",
        startedAtMillis = startedAtMillis,
        verificationResult = fastState.state.name,
        freshFrameRequested = true
      )
    }
    if (
      fastState?.state == TicketViviRecoveryState.CONTROL_CODE_POPUP ||
      fastState?.state == TicketViviRecoveryState.CONTROL_CODE_RESULT ||
      SystemClock.elapsedRealtime() < controlExitPopupLikelyUntilMillis
    ) {
      val accessibilityClosed = PhoneAutomationServiceBridge.clickSelectors(
        TicketScreenConfig.VIVI_PACKAGE,
        CONTROL_EXIT_CLOSE_SELECTORS,
        CONTROL_EXIT_ACCESSIBILITY_CLOSE_TIMEOUT_MILLIS
      )
      if (accessibilityClosed) {
        return verifyAndCompleteControlExitCleanup(
          reason = reason,
          detectedState = fastState?.state?.name ?: "CONTROL_SURFACE_LIKELY",
          closeAction = "accessibility_close",
          startedAtMillis = startedAtMillis
        )
      }
      val backClose = runFastNonTouchInput(
        "input keyevent KEYCODE_BACK; sleep 0.12; input keyevent KEYCODE_BACK",
        "control_exit_back_close"
      )
      if (backClose.ok) {
        val backVerified = verifyAndCompleteControlExitCleanup(
          reason = reason,
          detectedState = fastState?.state?.name ?: "CONTROL_SURFACE_LIKELY",
          closeAction = "back_close",
          startedAtMillis = startedAtMillis
        )
        if (backVerified) {
          return true
        }
      } else {
        recordTicketEvent("control_exit_back_close_failed", "duration_ms=${backClose.durationMs}")
      }
      softCleanupControlExitSurface(
        reason = reason,
        detectedState = fastState?.state?.name ?: "CONTROL_SURFACE_LIKELY",
        startedAtMillis = startedAtMillis
      )?.let { return it }
    }
    var lastDetectedState = "UNKNOWN"
    var lastVerificationResult = "popup_not_visible"
    val deadlineMillis = startedAtMillis + CONTROL_EXIT_SURFACE_OBSERVE_TIMEOUT_MILLIS
    while (true) {
      val hierarchy = controlExitHierarchy()
      if (!hierarchy.isNullOrBlank()) {
        val detectedState = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
        lastDetectedState = detectedState.name
        if (detectedState == TicketViviRecoveryState.TICKET_DETAIL) {
          return completeControlExitCleanup(
            reason = reason,
            detectedState = detectedState.name,
            closeAction = "none",
            startedAtMillis = startedAtMillis,
            verificationResult = detectedState.name,
            freshFrameRequested = true
          )
        }
        val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
        if (action != null) {
          val tap = runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_exit_popup_close")
          if (!tap.ok) {
            recordTicketEvent("control_exit_popup_close_failed", "duration_ms=${tap.durationMs}")
            recordControlExitCleanup(reason, detectedState.name, action.reason, startedAtMillis, "tap_failed", false, false)
            return false
          }
          return verifyAndCompleteControlExitCleanup(reason, detectedState.name, action.reason, startedAtMillis)
        }
        lastVerificationResult = "close_action_missing"
        if (!detectedState.controlExitMaySettle()) {
          recordTicketEvent("control_exit_popup_close_blocked", "close_action_missing")
          recordControlExitCleanup(reason, detectedState.name, "none", startedAtMillis, "close_action_missing", false, false)
          return false
        }
      } else {
        lastDetectedState = "UNKNOWN"
        lastVerificationResult = "hierarchy_unavailable"
      }
      if (SystemClock.elapsedRealtime() >= deadlineMillis) {
        softCleanupControlExitSurface(reason, lastDetectedState, startedAtMillis)?.let { return it }
        recordTicketEvent("control_exit_popup_close_blocked", lastVerificationResult)
        recordControlExitCleanup(reason, lastDetectedState, "none", startedAtMillis, lastVerificationResult, false, false)
        return false
      }
      delay(CONTROL_EXIT_SURFACE_OBSERVE_RETRY_MILLIS)
    }
  }

  private suspend fun controlExitForegroundViolationReason(): String? {
    val foregroundPackage = PhoneAutomationServiceBridge.currentForegroundPackage()
    if (foregroundPackage == TicketScreenConfig.VIVI_PACKAGE) {
      return null
    }
    if (!foregroundPackage.isNullOrBlank()) {
      return "left_vivi_app"
    }
    return foregroundViolationReason(allowStartupSystemUi = false)
  }

  private suspend fun softCleanupControlExitSurface(
    reason: String,
    detectedState: String,
    startedAtMillis: Long
  ): Boolean? {
    val recovered = ticketAutopilot.driveToTicketDetail(
      reason = "control_exit_cleanup_$reason",
      forceFreshLaunch = false,
      allowControlCodePopup = false,
      restartPolicy = TicketAutopilotRestartPolicy.NEVER,
      maxSteps = CONTROL_EXIT_CLEANUP_MAX_STEPS,
      timeoutMillis = CONTROL_EXIT_CLEANUP_TIMEOUT_MILLIS
    )
    if (recovered.success && recovered.state == TicketViviRecoveryState.TICKET_DETAIL) {
      return completeControlExitCleanup(
        reason = reason,
        detectedState = detectedState,
        closeAction = "soft_cleanup",
        startedAtMillis = startedAtMillis,
        verificationResult = recovered.state.name,
        freshFrameRequested = true
      )
    }
    return null
  }

  private fun TicketViviRecoveryState.controlExitMaySettle(): Boolean {
    return this == TicketViviRecoveryState.BLANK ||
      this == TicketViviRecoveryState.UNKNOWN_VIVI ||
      this == TicketViviRecoveryState.OUTSIDE_VIVI
  }

  private suspend fun verifyAndCompleteControlExitCleanup(
    reason: String,
    detectedState: String,
    closeAction: String,
    startedAtMillis: Long
  ): Boolean {
    delay(CONTROL_CODE_EXIT_VERIFY_SETTLE_MILLIS)
    var verificationResult = "UNKNOWN"
    val deadlineMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_EXIT_VERIFY_TIMEOUT_MILLIS
    while (true) {
      val verified = observeControlExitVerificationState(reason)
      verificationResult = verified?.name ?: "UNKNOWN"
      if (verified == TicketViviRecoveryState.TICKET_DETAIL) {
        return completeControlExitCleanup(
          reason = reason,
          detectedState = detectedState,
          closeAction = closeAction,
          startedAtMillis = startedAtMillis,
          verificationResult = verificationResult,
          freshFrameRequested = true
        )
      }
      if (SystemClock.elapsedRealtime() >= deadlineMillis) {
        recordTicketEvent("control_exit_cleanup_verify_failed", "$detectedState->$verificationResult action=$closeAction")
        recordInputGateDecision(allowed = false, reason = "control_exit_cleanup_verify_failed")
        recordControlExitCleanup(reason, detectedState, closeAction, startedAtMillis, verificationResult, false, false)
        return false
      }
      delay(CONTROL_EXIT_SURFACE_OBSERVE_RETRY_MILLIS)
    }
  }

  private suspend fun observeControlExitVerificationState(reason: String): TicketViviRecoveryState? {
    ticketAutopilot.observeFastState("control_exit_verify:$reason")?.let { verified ->
      if (verified.state != TicketViviRecoveryState.UNKNOWN_VIVI) {
        return verified.state
      }
    }
    val hierarchy = controlExitHierarchy()
    if (hierarchy.isNullOrBlank()) {
      return null
    }
    return TicketViviPageEnforcer.classifyForRecovery(hierarchy)
  }

  private fun completeControlExitCleanup(
    reason: String,
    detectedState: String,
    closeAction: String,
    startedAtMillis: Long,
    verificationResult: String,
    freshFrameRequested: Boolean
  ): Boolean {
    controlCodeModeActive = false
    controlCodeModeEnteredAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    controlExitPopupLikelyUntilMillis = 0L
    recordInputGateDecision(allowed = true, reason = "control_exit_popup_closed")
    recordTicketEvent("control_exit_popup_closed", reason)
    updateTicketSessionState(TICKET_SESSION_LIVE, "control_exit_popup_closed")
    recordTicketEvent("control_code_soft_check_ok", verificationResult.lowercase())
    if (freshFrameRequested) {
      requestKeyFrame("control_exit_cleanup")
    }
    recordControlExitCleanup(reason, detectedState, closeAction, startedAtMillis, verificationResult, true, freshFrameRequested)
    broadcastStatus()
    return true
  }

  private fun recordControlExitCleanup(
    reason: String,
    detectedState: String,
    closeAction: String,
    startedAtMillis: Long,
    verificationResult: String,
    succeeded: Boolean,
    freshFrameRequested: Boolean
  ) {
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    lastControlExitCleanupReason = reason
    lastControlExitCleanupDetectedState = detectedState
    lastControlExitCleanupCloseAction = closeAction
    lastControlExitCleanupDurationMillis = durationMillis
    lastControlExitCleanupCompletedAtMillis = SystemClock.elapsedRealtime()
    lastControlExitCleanupVerificationResult = verificationResult
    lastControlExitCleanupSucceeded = succeeded
    lastControlExitCleanupFreshFrameRequested = freshFrameRequested
    recordTicketEvent(
      "control_exit_cleanup",
      "reason=$reason state=$detectedState action=$closeAction verify=$verificationResult success=$succeeded fresh_frame=$freshFrameRequested duration_ms=$durationMillis"
    )
  }

  private fun ignoreProtectedRemoteInput(reason: String) {
    Log.i(TAG, "ticket_remote_input_ignored reason=$reason")
    markViewerInput(reason)
  }

  private fun isProtectedRemoteTap(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    return inRemoteSystemEdge(size, encodedX, encodedY)
  }

  private fun inRemoteSystemEdge(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    val topEdge = (size.height * REMOTE_TOP_SYSTEM_EDGE_FRACTION).roundToInt()
      .coerceAtLeast(REMOTE_MIN_SYSTEM_EDGE_PIXELS)
    val bottomEdge = (size.height * REMOTE_BOTTOM_SYSTEM_EDGE_FRACTION).roundToInt()
      .coerceAtLeast(REMOTE_MIN_SYSTEM_EDGE_PIXELS)
    val sideEdge = (size.width * REMOTE_SIDE_SYSTEM_EDGE_FRACTION).roundToInt()
      .coerceAtLeast(REMOTE_MIN_SYSTEM_EDGE_PIXELS)
    return encodedY <= topEdge || encodedY >= size.height - bottomEdge ||
      encodedX <= sideEdge || encodedX >= size.width - sideEdge
  }

  private suspend fun handleRemoteKey(inputId: String?, key: String) {
    if (sendCachedInputResult(inputId, "key")) {
      return
    }
    val startedAtMillis = SystemClock.elapsedRealtime()
    val phases = linkedMapOf<String, Long>()
    if (!measureInputPhase(phases, "gate") { canForwardRemoteInput() }) {
      sendInputResult(inputId, "key", false, inputGateReason, startedAtMillis, phases)
      return
    }
    if (TicketRemoteKeyPolicy.isCloseRequest(key)) {
      val closed = measureInputPhase(phases, "close_popup") {
        closeControlCodePopupFromRemote("remote_control_code_escape")
      }
      sendInputResult(
        inputId,
        "key",
        closed,
        if (closed) "control_code_popup_closed" else inputGateReason,
        startedAtMillis,
        phases
      )
      return
    }
    val command = TicketRemoteKeyPolicy.commandFor(key) ?: run {
      recordInputGateDecision(allowed = false, reason = "remote_key_unsupported")
      ignoreProtectedRemoteInput("remote_key_unsupported")
      sendInputResult(inputId, "key", false, "remote_key_unsupported", startedAtMillis, phases)
      return
    }
    if (!measureInputPhase(phases, "control_code_popup_check") { requireControlCodePopupForRemoteKey() }) {
      sendInputResult(inputId, "key", false, inputGateReason, startedAtMillis, phases)
      return
    }
    recordInputGateDecision(allowed = true, reason = "control_code_popup_key")
    markViewerInput("control_code_key")
    val result = measureInputPhase(phases, "command") {
      runFastNonTouchInput(command, "remote_control_code_key")
    }
    sendInputResult(
      inputId,
      "key",
      result.ok,
      if (result.ok) "control_code_key_sent" else "root_command_failed",
      startedAtMillis,
      phases
    )
  }

  private suspend fun requireControlCodePopupForRemoteKey(): Boolean {
    if (SystemClock.elapsedRealtime() < controlCodePopupReadyUntilMillis) {
      return true
    }
    val dump = dumpViviHierarchy(fresh = true)
    if (!dump.ok || dump.stdout.isBlank()) {
      recordInputGateDecision(allowed = false, reason = "control_code_popup_check_failed")
      Log.w(TAG, "ticket_remote_key_blocked reason=control_code_popup_check_failed stderr=${dump.stderr}")
      return false
    }
    if (!TicketViviPageEnforcer.isControlCodePopup(dump.stdout)) {
      val state = TicketViviPageEnforcer.classifyForRecovery(dump.stdout)
      val reason = if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        "control_code_result_visible"
      } else {
        "control_code_popup_not_visible"
      }
      recordInputGateDecision(allowed = false, reason = reason)
      if (state != TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        resetControlCodeMode("control_code_popup_not_visible")
      }
      Log.i(TAG, "ticket_remote_key_blocked reason=$reason")
      return false
    }
    if (!TicketViviPageEnforcer.isControlCodeInputFocused(dump.stdout)) {
      val action = TicketViviPageEnforcer.controlCodeInputActionForHierarchy(dump.stdout)
      if (action == null) {
        recordInputGateDecision(allowed = false, reason = "control_code_input_missing")
        Log.i(TAG, "ticket_remote_key_blocked reason=control_code_input_missing")
        return false
      }
      val tap = runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_code_input_focus")
      if (!tap.ok) {
        recordInputGateDecision(allowed = false, reason = "control_code_input_focus_failed")
        Log.w(TAG, "ticket_remote_key_blocked reason=control_code_input_focus_failed stderr=${tap.stderr}")
        return false
      }
      delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
    }
    controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
    markControlCodeModeEntered("remote_key_popup_check")
    return true
  }

  private suspend fun closeControlCodePopupFromRemote(reason: String): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val hierarchy = controlExitHierarchy()
    if (hierarchy.isNullOrBlank()) {
      recordInputGateDecision(allowed = false, reason = "control_code_popup_check_failed")
      Log.w(TAG, "ticket_control_code_close_failed reason=$reason hierarchy_unavailable")
      return false
    }
    val detectedState = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
    if (action == null) {
      recordInputGateDecision(allowed = false, reason = "control_code_close_missing")
      Log.i(TAG, "ticket_control_code_close_blocked reason=control_code_close_missing")
      return false
    }
    val tap = runFastNonTouchInput("input tap ${action.x} ${action.y}", reason)
    return if (tap.ok) {
      verifyAndCompleteControlExitCleanup(reason, detectedState.name, action.reason, startedAtMillis).also { closed ->
        if (closed) {
          markViewerInput(reason)
        }
      }
    } else {
      recordInputGateDecision(allowed = false, reason = "control_code_close_failed")
      Log.w(TAG, "ticket_control_code_close_failed reason=$reason stderr=${tap.stderr}")
      false
    }
  }

  private suspend fun closeControlCodeSurfaceTapFromRemote(x: Int, y: Int, reason: String): Boolean? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val hierarchy = controlExitHierarchy()
    if (hierarchy.isNullOrBlank()) {
      return null
    }
    if (!TicketViviPageEnforcer.isControlCodeExitCloseTap(hierarchy, x, y)) {
      return null
    }
    val detectedState = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy) ?: run {
      recordInputGateDecision(allowed = false, reason = "control_code_close_missing")
      return false
    }
    val tap = runFastNonTouchInput("input tap ${action.x} ${action.y}", reason)
    if (!tap.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_popup_close_tap_failed")
      Log.w(TAG, "ticket_control_code_close_tap_failed stderr=${tap.stderr}")
      return false
    }
    return verifyAndCompleteControlExitCleanup(reason, detectedState.name, action.reason, startedAtMillis).also { closed ->
      if (closed) {
        markViewerInput(reason)
      }
    }
  }

  private suspend fun tap(inputId: String?, encodedX: Int, encodedY: Int) {
    if (sendCachedInputResult(inputId, "tap")) {
      return
    }
    val startedAtMillis = SystemClock.elapsedRealtime()
    val phases = linkedMapOf<String, Long>()
    if (!measureInputPhase(phases, "gate") { canForwardRemoteInput() }) {
      sendInputResult(inputId, "tap", false, inputGateReason, startedAtMillis, phases)
      return
    }
    val size = streamSize ?: run {
      recordInputGateDecision(allowed = false, reason = "stream_size_unavailable")
      sendInputResult(inputId, "tap", false, "stream_size_unavailable", startedAtMillis, phases)
      return
    }
    val x = size.sourceX(encodedX)
    val y = size.sourceY(encodedY)
    if (!measureInputPhase(phases, "foreground_check") { requireViviForegroundForRemoteTap() }) {
      sendInputResult(inputId, "tap", false, inputGateReason, startedAtMillis, phases)
      return
    }
    if (isProtectedRemoteTap(size, encodedX, encodedY)) {
      recordInputGateDecision(allowed = false, reason = "remote_protected_tap_blocked")
      ignoreProtectedRemoteInput("remote_protected_tap_blocked")
      sendInputResult(inputId, "tap", false, "remote_protected_tap_blocked", startedAtMillis, phases)
      return
    }
    if (controlCodeModeActive && isLikelyControlCodeExitCloseCoordinate(size, encodedX, encodedY)) {
      val closeTap = measureInputPhase(phases, "control_exit_close_tap_check") {
        closeControlCodeSurfaceTapFromRemote(x, y, "remote_control_code_close_tap")
      }
      if (closeTap != null) {
        sendInputResult(
          inputId,
          "tap",
          closeTap,
          if (closeTap) "control_code_popup_closed" else inputGateReason,
          startedAtMillis,
          phases
        )
        return
      }
    }
    if (isLikelyControlCodeCoordinateTap(size, encodedX, encodedY)) {
      markControlCodeTransition("control_code_coordinate_tap")
    }
    if (!measureInputPhase(phases, "vivi_sandbox") { requireFastViviSandboxForRemoteTap(size, encodedX, encodedY) }) {
      sendInputResult(inputId, "tap", false, inputGateReason, startedAtMillis, phases)
      return
    }
    markViewerInput("tap")
    val tap = measureInputPhase(phases, "command") {
      runFastNonTouchInput("input tap $x $y", "remote_ticket_tap")
    }
    if (tap.ok) {
      schedulePostRemoteTapForegroundCheck()
    }
    sendInputResult(
      inputId,
      "tap",
      tap.ok,
      if (tap.ok) "remote_ticket_tap" else "root_command_failed",
      startedAtMillis,
      phases
    )
  }

  private fun schedulePostRemoteTapForegroundCheck() {
    postRemoteTapForegroundCheckJob?.cancel()
    serviceScope.launch {
      delay(REMOTE_TAP_FOREGROUND_SETTLE_MILLIS)
      val violation = foregroundViolationReason(allowStartupSystemUi = false)
      cacheForegroundViolation(violation)
      if (violation == null) {
        refreshControlCodeModeAfterRemoteTap()
        return@launch
      }
      Log.i(TAG, "ticket_post_tap_foreground_violation reason=$violation")
    }.also { postRemoteTapForegroundCheckJob = it }
  }

  private suspend fun refreshControlCodeModeAfterRemoteTap() {
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT || ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION) {
      return
    }
    val dump = dumpViviHierarchy(fresh = true)
    if (!dump.ok || dump.stdout.isBlank()) {
      return
    }
    when (TicketViviPageEnforcer.classifyForRecovery(dump.stdout)) {
      TicketViviRecoveryState.CONTROL_CODE_POPUP -> {
        controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
        markControlCodeModeEntered("remote_tap_popup_detected")
      }
      TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
        controlCodePopupReadyUntilMillis = 0L
        markControlCodeModeEntered("remote_tap_result_detected")
        recordTicketEvent("control_code_result_visible", "remote_tap_result_detected")
      }
      else -> if (controlCodeModeActive) {
        resetControlCodeMode("remote_tap_popup_gone")
      }
    }
  }

  private fun isLikelyControlCodeExitCloseCoordinate(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    return encodedX >= (size.width * VIVI_CONTROL_CODE_CLOSE_MIN_X_FRACTION).roundToInt() &&
      encodedY in (size.height * VIVI_CONTROL_CODE_CLOSE_MIN_Y_FRACTION).roundToInt()..
      (size.height * VIVI_CONTROL_CODE_CLOSE_MAX_Y_FRACTION).roundToInt()
  }

  private suspend fun <T> measureInputPhase(
    phases: MutableMap<String, Long>,
    phase: String,
    block: suspend () -> T
  ): T {
    val startedAtMillis = SystemClock.elapsedRealtime()
    return block().also {
      phases[phase] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    }
  }

  private fun sendInputResult(
    inputId: String?,
    kind: String,
    accepted: Boolean,
    reason: String,
    startedAtMillis: Long,
    phases: Map<String, Long>
  ) {
    val nowMillis = SystemClock.elapsedRealtime()
    val totalDurationMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
    lastInputResultId = inputId
    lastInputResultKind = kind
    lastInputResultAccepted = accepted
    lastInputResultReason = reason
    lastInputResultTotalDurationMillis = totalDurationMillis
    lastInputResultCompletedAtMillis = nowMillis
    val phaseJson = buildJsonObject {
      phases.forEach { (name, duration) -> put(name, duration) }
    }
    val message = buildJsonObject {
      put("type", "input_result")
      put("inputId", inputId.orEmpty())
      put("kind", kind)
      put("accepted", accepted)
      put("reason", reason)
      put("totalDurationMillis", totalDurationMillis)
      put("phases", phaseJson)
    }.toString()
    rememberInputResult(inputId, message)
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "input_result",
      "${inputId.orEmpty()} $kind accepted=$accepted reason=$reason duration_ms=$totalDurationMillis"
    )
  }

  private fun sendCachedInputResult(inputId: String?, kind: String): Boolean {
    val id = inputId?.takeIf { it.isNotBlank() } ?: return false
    val message = synchronized(recentInputResultMessages) {
      recentInputResultMessages[id]
    } ?: return false
    duplicateInputResultCount += 1
    lastDuplicateInputId = id
    lastDuplicateInputResultAtMillis = SystemClock.elapsedRealtime()
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent("input_result_duplicate", "$id $kind")
    return true
  }

  private fun rememberInputResult(inputId: String?, message: String) {
    val id = inputId?.takeIf { it.isNotBlank() } ?: return
    synchronized(recentInputResultMessages) {
      if (!recentInputResultMessages.containsKey(id)) {
        recentInputResultOrder.addLast(id)
      }
      recentInputResultMessages[id] = message
      while (recentInputResultOrder.size > RECENT_INPUT_RESULT_CACHE_SIZE) {
        val removed = recentInputResultOrder.removeFirst()
        recentInputResultMessages.remove(removed)
      }
    }
  }

  private suspend fun runNonTouchInput(command: String, reason: String): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return rootExecutor.run(command).also { recordInputCommandResult(reason, it) }
  }

  private suspend fun runFastNonTouchInput(command: String, reason: String): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return inputRootExecutor.run(command).also { recordInputCommandResult(reason, it) }
  }

  private fun recordInputCommandResult(reason: String, result: RootResult) {
    lastInputCommandReason = reason
    lastInputCommandDurationMillis = result.durationMs
    lastInputCommandCompletedAtMillis = SystemClock.elapsedRealtime()
    recordTicketEvent("input_command", "$reason duration_ms=${result.durationMs} ok=${result.ok}")
  }

  private suspend fun blockSwipe() {
    if (streamActive) {
      ignoreProtectedRemoteInput("remote_swipe_blocked")
    }
  }

  private suspend fun blockLongPress() {
    if (streamActive) {
      ignoreProtectedRemoteInput("remote_long_press_blocked")
    }
  }

  private fun currentDisplaySize(): Pair<Int, Int> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
      bounds.width() to bounds.height()
    } else {
      @Suppress("DEPRECATION")
      resources.displayMetrics.run { widthPixels to heightPixels }
    }
  }

  private fun promoteToForeground(mediaProjectionActive: Boolean) {
    val notification = foregroundNotification()
    if (mediaProjectionActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) {
      return
    }
    manager.createNotificationChannel(
      NotificationChannel(
        CHANNEL_ID,
        "Ticket screen guard",
        NotificationManager.IMPORTANCE_MIN
      ).apply {
        description = "Keeps ticket.jolkins.id.lv available quietly when enabled"
        setShowBadge(false)
      }
    )
  }

  private fun foregroundNotification(): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("Ticket remote access")
      .setContentText("Serving ticket.jolkins.id.lv on localhost")
      .setOngoing(true)
      .setLocalOnly(true)
      .setSilent(true)
      .setPriority(NotificationCompat.PRIORITY_MIN)
      .setVisibility(NotificationCompat.VISIBILITY_SECRET)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .build()
  }

  private fun serviceReadinessSnapshot(): TicketServiceReadinessHealth {
    val snapshot = TicketServicePreferencesStore(this).load()
    val nowWallMillis = System.currentTimeMillis()
    return TicketServiceReadinessHealth(
      enabled = snapshot.enabled,
      state = snapshot.runtimeState.wireName,
      detail = snapshot.runtimeSummary(),
      lastEnsureReason = snapshot.lastEnsureReason,
      lastEnsureAgeMillis = snapshot.lastEnsureAtMillis.takeIf { it > 0L }
        ?.let { (nowWallMillis - it).coerceAtLeast(0L) },
      lastEnsureSucceeded = snapshot.lastEnsureSucceeded,
      lastEnsureResult = snapshot.lastEnsureResult,
      localServerReachable = running.get() || snapshot.localServerReachable,
      tunnelReady = snapshot.tunnelReady,
      componentStatus = snapshot.componentStatus
    )
  }

  private fun sendJson(output: BufferedOutputStream, value: TicketStreamHealth) {
    sendJsonPayload(output, json.encodeToString(value))
  }

  private fun sendJson(output: BufferedOutputStream, value: TicketSessionResponse) {
    sendJsonPayload(output, json.encodeToString(value))
  }

  private fun sendJsonPayload(
    output: BufferedOutputStream,
    payload: String,
    clearSiteCache: Boolean = false
  ) {
    sendHttp(
      output = output,
      status = 200,
      contentType = "application/json; charset=utf-8",
      body = payload.toByteArray(Charsets.UTF_8),
      clearSiteCache = clearSiteCache
    )
  }

  private fun bootstrapPayload(): String {
    return """
      {
        "ok": true,
        "serverVersion": "$SERVER_VERSION",
        "cachePolicy": "no-store_html_clear_cache_only",
        "latestUrl": "/?v=$SERVER_VERSION"
      }
    """.trimIndent()
  }

  private fun sendHtml(output: BufferedOutputStream, html: String) {
    sendHttp(
      output = output,
      status = 200,
      contentType = "text/html; charset=utf-8",
      body = html.toByteArray(Charsets.UTF_8),
      clearSiteCache = true
    )
  }

  private fun sendText(output: BufferedOutputStream, status: Int, text: String) {
    sendHttp(
      output = output,
      status = status,
      contentType = "text/plain; charset=utf-8",
      body = text.toByteArray(Charsets.UTF_8),
      clearSiteCache = false
    )
  }

  private fun sendHttp(
    output: BufferedOutputStream,
    status: Int,
    contentType: String,
    body: ByteArray,
    clearSiteCache: Boolean
  ) {
    val statusText = if (status == 200) "OK" else "Error"
    output.write(
      buildString {
        append("HTTP/1.1 $status $statusText\r\n")
        append("Content-Type: $contentType\r\n")
        append("Content-Length: ${body.size}\r\n")
        append("X-Ticket-Server-Version: $SERVER_VERSION\r\n")
        append("X-Ticket-Cache-Policy: no-store\r\n")
        append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n")
        append("Pragma: no-cache\r\n")
        append("Expires: 0\r\n")
        append("Surrogate-Control: no-store\r\n")
        append("CDN-Cache-Control: no-store\r\n")
        append("Cloudflare-CDN-Cache-Control: no-store\r\n")
        if (clearSiteCache) append("Clear-Site-Data: \"cache\"\r\n")
        append("Connection: close\r\n")
        append("\r\n")
      }.toByteArray(Charsets.ISO_8859_1)
    )
    output.write(body)
    output.flush()
  }

  private fun BufferedInputStream.readAsciiLine(): String {
    val bytes = mutableListOf<Byte>()
    while (true) {
      val value = read()
      if (value < 0) break
      if (value == '\n'.code) break
      if (value != '\r'.code) {
        bytes += value.toByte()
      }
      if (bytes.size > MAX_HEADER_LINE_BYTES) {
        break
      }
    }
    return bytes.toByteArray().toString(Charsets.ISO_8859_1)
  }

  private fun BufferedInputStream.readFullyBytes(bytesToRead: Int): ByteArray {
    val body = ByteArray(bytesToRead)
    var offset = 0
    while (offset < body.size) {
      val read = read(body, offset, body.size - offset)
      if (read <= 0) break
      offset += read
    }
    return if (offset == body.size) {
      body
    } else {
      body.copyOf(offset)
    }
  }

  companion object {
    private const val TAG = "TicketStreamService"
    private const val CHANNEL_ID = "ticket_screen_guard"
    private const val NOTIFICATION_ID = 9388
    private const val SERVER_BACKLOG = 4
    private const val SOCKET_TIMEOUT_MILLIS = 30_000
    private const val MAX_HEADER_LINE_BYTES = 131_072
    private const val MAX_CLIENT_LOG_BYTES = 2_048
    private const val RECENT_CLIENT_TELEMETRY_LIMIT = 80
    private const val RECENT_TICKET_EVENT_LIMIT = 80
    private const val MAX_TICKET_EVENT_DETAIL_BYTES = 256
    const val SERVER_VERSION = "ticket-stream-2026-05-04-root-png-recovery-v70"
    private const val FRAME_ENVELOPE_VERSION = "tsf2"
    private const val FRAME_ENVELOPE_MAGIC = 0x54534632
    private const val FRAME_ENVELOPE_HEADER_BYTES = 29
    private const val FRAME_FLAG_KEYFRAME: Byte = 1
    private const val TICKET_SESSION_IDLE = "idle"
    private const val TICKET_SESSION_STARTING = "starting"
    private const val TICKET_SESSION_LIVE = "live"
    private const val TICKET_SESSION_CONTROL_TRANSITION = "control_transition"
    private const val TICKET_SESSION_CONTROL_ACTIVE = "control_active"
    private const val TICKET_SESSION_CONTROL_EXIT = "control_exit"
    private const val TICKET_SESSION_SOFT_RECOVERY = "soft_recovery"
    private const val TICKET_SESSION_NEEDS_ATTENTION = "needs_attention"
    private const val TICKET_SESSION_CLIENT_DISCONNECTED = "client_disconnected"
    private const val TICKET_SESSION_UNAVAILABLE = "unavailable"
    private const val TICKET_SESSION_STOPPED = "stopped"
    private const val TICKET_STATE_BUDGET_MILLIS = 1_000L
    private const val CAPTURE_MODE_IDLE = "idle"
    private const val CAPTURE_MODE_ROOT_FFMPEG_H264 = TicketScreenConfig.ROOT_FFMPEG_H264_CAPTURE_MODE
    private const val CAPTURE_MODE_ROOT_PNG = "root_screencap_png"
    private const val CAPTURE_MODE_ROOT_AV1 = TicketScreenConfig.ROOT_AV1_CAPTURE_MODE
    private const val CAPTURE_MODE_ROOT_H264 = "root_h264"
    private const val CAPTURE_MODE_MEDIAPROJECTION_AV1 = "mediaprojection_av1"
    private const val ENCODER_TIMEOUT_US = 50_000L
    private const val ENCODER_FAILURE_RECOVERY_DELAY_MILLIS = 150L
    private const val ROOT_KEYFRAME_RESTART_AFTER_MILLIS = 170_000L
    private const val ROOT_KEYFRAME_INITIAL_WAIT_MILLIS = 5_000L
    private const val ROOT_KEYFRAME_RESTART_COOLDOWN_MILLIS = 120_000L
    private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 1_500L
    private const val ROOT_URGENT_KEYFRAME_RESTART_COOLDOWN_MILLIS = 3_000L
    private const val SEND_BITRATE_WINDOW_MILLIS = 1_000L
    private const val VIDEO_CLIENT_SLOW_WRITE_MILLIS = 250L
    private const val VIDEO_CLIENT_SLOW_CLOSE_MILLIS = 3_000L
    private const val AUTO_MEDIA_PROJECTION_PERMISSION_LAUNCH_ENABLED = true
    private const val CAPTURE_PERMISSION_ASSIST_DELAY_MILLIS = 350L
    private const val CAPTURE_PERMISSION_LAUNCH_COOLDOWN_MILLIS = 15_000L
    private const val AV1_ENCODER_START_SETTLE_MILLIS = 750L
    private const val AV1_STALE_ENCODER_RECOVERY_MILLIS = 2_000L
    private const val PRE_CAPTURE_APP_SETTLE_MILLIS = 800L
    private const val STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS = 5_000L
    private const val CLIENT_DISCONNECT_IDLE_GRACE_MILLIS = 30_000L
    private const val VIVI_FOREGROUND_INITIAL_DELAY_MILLIS = 1_500L
    private const val VIVI_FOREGROUND_CHECK_MILLIS = 750L
    private const val VIVI_FOREGROUND_GRACE_MILLIS = 8_000L
    private const val VIVI_PAGE_ENFORCE_INTERVAL_MILLIS = 2_000L
    private const val TICKET_SCREEN_WAKE_HOLD_MILLIS = 30_000L
    private const val TICKET_SCREEN_WAKE_REQUEST_COOLDOWN_MILLIS = 2_000L
    private const val ACTIVE_AUTOPILOT_MAX_STEPS = 2
    private const val ACTIVE_AUTOPILOT_TIMEOUT_MILLIS = 1_000L
    private const val ROOT_PNG_START_AUTOPILOT_MAX_STEPS = 8
    private const val ROOT_PNG_START_AUTOPILOT_TIMEOUT_MILLIS = 18_000L
    private const val CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS = 120L
    private const val CONTROL_CODE_POPUP_READY_CACHE_MILLIS = 2_000L
    private const val RECENT_INPUT_RESULT_CACHE_SIZE = 80
    private const val CONTROL_CODE_SOFT_CHECK_DELAY_MILLIS = 200L
    private const val CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS = 10_000L
    private const val CONTROL_CODE_SOFT_CHECK_MAX_STEPS = 2
    private const val CONTROL_CODE_EXIT_VERIFY_SETTLE_MILLIS = 220L
    private const val CONTROL_CODE_EXIT_VERIFY_TIMEOUT_MILLIS = 1_200L
    private const val CONTROL_EXIT_ACCESSIBILITY_CLOSE_TIMEOUT_MILLIS = 450L
    private const val CONTROL_EXIT_SURFACE_OBSERVE_TIMEOUT_MILLIS = 1_500L
    private const val CONTROL_EXIT_SURFACE_OBSERVE_RETRY_MILLIS = 120L
    private const val CONTROL_EXIT_CLEANUP_MAX_STEPS = 2
    private const val CONTROL_EXIT_CLEANUP_TIMEOUT_MILLIS = 1_800L
    private val CONTROL_EXIT_CLOSE_SELECTORS = listOf(
      PhoneAutomationSelector(contentDescription = "Aizvērt"),
      PhoneAutomationSelector(text = "Aizvērt"),
      PhoneAutomationSelector(contentDescription = "Atcelt"),
      PhoneAutomationSelector(text = "Atcelt"),
      PhoneAutomationSelector(contentDescription = "Close"),
      PhoneAutomationSelector(text = "Close"),
      PhoneAutomationSelector(contentDescription = "Cancel"),
      PhoneAutomationSelector(text = "Cancel"),
      PhoneAutomationSelector(text = "x"),
      PhoneAutomationSelector(text = "×")
    )
    private const val CONTROL_CODE_TRANSITION_GRACE_MILLIS = 3_000L
    private const val REMOTE_TAP_FOREGROUND_SETTLE_MILLIS = 350L
    private const val CACHED_FOREGROUND_MAX_AGE_MILLIS = 2_000L
    private const val FOREGROUND_RECOVERY_CONFIRMATION_COUNT = 2
    private const val FOREGROUND_RECOVERY_COOLDOWN_MILLIS = 6_000L
    private const val VIVI_SETTINGS_MAX_X_FRACTION = 0.22f
    private const val VIVI_SETTINGS_MAX_Y_FRACTION = 0.24f
    private const val VIVI_PROFILE_MIN_X_FRACTION = 0.48f
    private const val VIVI_PROFILE_MAX_X_FRACTION = 0.75f
    private const val VIVI_BOTTOM_NAV_MIN_Y_FRACTION = 0.88f
    private const val VIVI_CONTROL_CODE_MIN_X_FRACTION = 0.04f
    private const val VIVI_CONTROL_CODE_MAX_X_FRACTION = 0.45f
    private const val VIVI_CONTROL_CODE_MIN_Y_FRACTION = 0.10f
    private const val VIVI_CONTROL_CODE_MAX_Y_FRACTION = 0.18f
    private const val VIVI_CONTROL_CODE_CLOSE_MIN_X_FRACTION = 0.78f
    private const val VIVI_CONTROL_CODE_CLOSE_MIN_Y_FRACTION = 0.12f
    private const val VIVI_CONTROL_CODE_CLOSE_MAX_Y_FRACTION = 0.75f
    private const val CONTROL_CODE_POPUP_CANCEL_X_FRACTION = 0.53f
    private const val CONTROL_CODE_POPUP_CANCEL_Y_FRACTION = 0.575f
    private const val REMOTE_TOP_SYSTEM_EDGE_FRACTION = 0.055f
    private const val REMOTE_BOTTOM_SYSTEM_EDGE_FRACTION = 0.03f
    private const val REMOTE_SIDE_SYSTEM_EDGE_FRACTION = 0.025f
    private const val REMOTE_MIN_SYSTEM_EDGE_PIXELS = 18
    private const val DIM_DISPLAY_PERCENT = 1.0f
    private const val MAX_DISPLAY_PERCENT = 99.0f
    private const val DIM_LEGACY_BRIGHTNESS_VALUE = 1
    private const val MAX_LEGACY_BRIGHTNESS_VALUE = 252
    private const val TICKET_SAFE_DIM_PERCENT = 1
    private const val BRIGHTNESS_GUARD_REPEAT_MILLIS = 2_500L
    private val BRIGHTNESS_GUARD_DELAYS_MILLIS = longArrayOf(0L, 1_000L, 2_500L, 5_000L)
    private val FOCUSED_POWER_TOKENS = listOf(
      "globalactions",
      "global_actions",
      "powermenu",
      "power menu",
      "shutdown",
      "restart"
    )
    private val FOCUSED_NETWORK_TOKENS = listOf(
      "internetdialog",
      "internet dialog",
      "network",
      "wifi",
      "wi-fi",
      "mobiledata",
      "mobile data",
      "airplane",
      "bluetooth"
    )
    private val FOCUSED_SYSTEM_UI_TOKENS = listOf(
      "com.android.systemui",
      "notificationshade",
      "quicksettings",
      "quick settings",
      "statusbar",
      "control center",
      "qscontainer"
    )
    private val FOCUSED_CAPTURE_PERMISSION_TOKENS = listOf(
      "mediaprojectionpermissionactivity",
      "media projection permission",
      "com.android.systemui/.mediaprojection.permission"
    )

    fun start(context: Context) {
      val intent = Intent(context, TicketStreamService::class.java)
        .setAction(TicketScreenConfig.ACTION_START_SERVER)
      context.startService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, TicketStreamService::class.java)
      context.stopService(intent)
    }
  }
}

internal fun browserPage(): String {
  return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate, max-age=0">
  <meta http-equiv="Pragma" content="no-cache">
  <meta http-equiv="Expires" content="0">
  <title>Ticket</title>
  <link rel="icon" href="data:,">
  <style>
    :root { color-scheme: dark; font-family: system-ui, sans-serif; background: #05070a; color: #f3f7fb; --ticket-stage-height: 100vh; --ticket-controls-offset: 16px; }
    * { box-sizing: border-box; }
    html { min-height: 100%; background: #05070a; }
    body { margin: 0; width: 100%; min-height: 100%; overflow-x: hidden; overflow-y: auto; background: #05070a; overscroll-behavior-y: contain; scroll-snap-type: y mandatory; -webkit-overflow-scrolling: touch; }
    main { width: 100%; min-height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset) + var(--ticket-stage-height)); background: #05070a; }
    .stream-page { position: relative; width: 100vw; height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset)); min-height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset)); background: #05070a; scroll-snap-align: start; }
    .stream-stage { position: fixed; inset: 0; z-index: 2; width: 100vw; height: var(--ticket-stage-height); min-height: var(--ticket-stage-height); overflow: hidden; background: #05070a; isolation: isolate; contain: layout paint; opacity: 1; visibility: visible; transition: opacity 140ms ease, visibility 140ms ease; }
    body.details-visible .stream-stage { opacity: 0; visibility: hidden; pointer-events: none; }
    .stream-frame { position: absolute; inset: 0; overflow: hidden; background: #05070a; }
    .stream-placeholder { position: absolute; inset: 0; z-index: 1; display: grid; place-items: center; padding: 24px; color: #d7deeb; font-size: 14px; line-height: 1.35; text-align: center; background: #05070a; transition: opacity 160ms ease; }
    body[data-stream-ready="true"] .stream-placeholder { opacity: 0; pointer-events: none; }
    .controls-panel { position: relative; z-index: 1; width: 100vw; min-height: var(--ticket-stage-height); display: grid; align-content: center; justify-items: center; gap: 16px; padding: 32px max(20px, env(safe-area-inset-left)) calc(32px + env(safe-area-inset-bottom)) max(20px, env(safe-area-inset-right)); background: #07101b; scroll-snap-align: start; transition: background 160ms ease; }
    body:not(.details-visible) .controls-panel { background: #05070a; }
    .controls-panel > * { transition: opacity 160ms ease; }
    body:not(.details-visible) .controls-panel > * { visibility: hidden; opacity: 0; pointer-events: none; }
    .start-panel { display: grid; justify-items: center; gap: 12px; }
    button { border: 1px solid #37445a; background: #1d2634; color: #f3f7fb; min-height: 36px; padding: 0 12px; border-radius: 6px; }
    button.primary { min-width: 112px; min-height: 44px; padding: 0 20px; background: #1f6feb; border-color: #4387ff; font-size: 16px; font-weight: 650; }
    button:disabled { opacity: .5; }
    canvas { position: absolute; left: 50%; top: 50%; display: block; width: var(--stream-css-width, 100vw); height: var(--stream-css-height, var(--ticket-stage-height)); max-width: none; max-height: none; background: #000; opacity: 0; transform: translate3d(-50%, -50%, 0); transition: opacity 160ms ease; image-rendering: crisp-edges; image-rendering: pixelated; touch-action: pan-y; user-select: none; -webkit-user-select: none; }
    body[data-stream-ready="true"] canvas { opacity: 1; }
    #idleTimer { min-width: 58px; padding: 4px 8px; border-radius: 999px; background: rgba(10, 13, 18, .72); border: 1px solid rgba(185, 194, 207, .26); color: #edf3fb; font-size: 12px; line-height: 1.2; text-align: center; pointer-events: none; backdrop-filter: blur(6px); }
    #idleTimer[hidden] { display: none; }
    #idleTimer.urgent { color: #ffd6d6; border-color: rgba(255, 115, 115, .45); background: rgba(70, 18, 18, .74); }
    #status { max-width: min(420px, calc(100vw - 32px)); color: #b9c2cf; font-size: 13px; line-height: 1.35; text-align: center; pointer-events: none; }
    #status:empty { display: none; }
    #modeNotice { position: fixed; left: 50%; top: max(18px, env(safe-area-inset-top)); z-index: 6; max-width: min(360px, calc(100vw - 32px)); pointer-events: none; opacity: 0; transform: translate3d(-50%, -8px, 0); transition: opacity 150ms ease, transform 150ms ease; }
    #modeNotice.visible { opacity: 1; transform: translate3d(-50%, 0, 0); }
    .mode-notice { border: 1px solid rgba(196, 212, 232, .28); border-radius: 8px; background: rgba(9, 14, 22, .88); color: #f3f7fb; box-shadow: 0 12px 32px rgba(0, 0, 0, .34); backdrop-filter: blur(10px); text-align: center; }
    .mode-notice.small { padding: 7px 12px; font-size: 12px; line-height: 1.2; }
    .mode-notice.large { padding: 14px 16px; font-size: 14px; line-height: 1.35; }
    .mode-notice-title { display: block; font-weight: 700; }
    .mode-notice-body { display: block; margin-top: 4px; color: #c9d4e2; font-size: 12px; }
  </style>
</head>
<body>
  <main id="ticketRoot">
    <section class="stream-page" id="streamPage" aria-label="Pixel stream">
      <div class="stream-stage" id="streamStage">
        <div class="stream-frame">
          <canvas id="screen" width="720" height="1616"></canvas>
          <div id="streamPlaceholder" class="stream-placeholder">Connecting</div>
        </div>
      </div>
    </section>
    <section class="controls-panel" id="controlsPanel" aria-label="Stream controls" aria-hidden="true">
      <div id="idleTimer" data-testid="idle-timer" hidden>10:00</div>
      <div class="start-panel">
        <button id="start" data-testid="start" type="button" class="primary">Start</button>
        <span id="status"></span>
      </div>
    </section>
  </main>
  <div id="modeNotice" aria-live="polite" aria-atomic="true"></div>
  <script>
    const PAGE_VERSION = "${TicketStreamService.SERVER_VERSION}";
    const CONTROL_CODE_NOTICE_COOKIE = 'ticket_control_code_notice_seen';
    const CONTROL_CODE_NOTICE_TEXT = 'Kontroles koda režīms';
    const VIEWER_ID_STORAGE_KEY = 'ticket_stream_viewer_id';
    const CONTROL_SOCKET_TIMEOUT_MS = 2500;
    const VIDEO_SOCKET_TIMEOUT_MS = 2000;
    const START_CONFIRM_DELAY_MS = 600;
    const STARTUP_RECONNECT_MAX_DELAY_MS = 1200;
    const LOADING_BUDGET_MS = 2000;
    const SELF_HEAL_INTERVAL_MS = 1000;
    const STREAM_WATCHDOG_INTERVAL_MS = 500;
    const NO_FIRST_FRAME_MS = 2000;
    const STALE_FRAME_SOFT_MS = 2500;
    const STALE_FRAME_RECONNECT_MS = 12000;
    const STALE_KEYFRAME_REQUEST_INTERVAL_MS = 2000;
    const viewerId = getOrCreateViewerId();
    const pageId = `${'$'}{Date.now().toString(36)}-${'$'}{Math.random().toString(36).slice(2)}`;
    const statusEl = document.getElementById('status');
    const idleTimerEl = document.getElementById('idleTimer');
    const modeNoticeEl = document.getElementById('modeNotice');
    const streamPage = document.getElementById('streamPage');
    const streamStage = document.getElementById('streamStage');
    const controlsPanel = document.getElementById('controlsPanel');
    const streamPlaceholder = document.getElementById('streamPlaceholder');
    const canvas = document.getElementById('screen');
    const startButton = document.getElementById('start');
    const ctx = canvas.getContext('2d');
    ctx.imageSmoothingEnabled = false;
    document.body.dataset.streamReady = 'false';
    document.body.dataset.streamState = 'booting';
    if ('scrollRestoration' in history) {
      history.scrollRestoration = 'manual';
    }
    window.scrollTo(0, 0);
    let ws;
    let videoWs;
    let decoder;
    let imageTransport = false;
    let imageDrawSerial = 0;
    let configured = false;
    let firstFrameReceived = false;
    let needsKeyFrame = true;
    let pointerStart = null;
    let desiredActive = false;
    let startCommandSent = false;
    let connecting = null;
    let videoConnecting = null;
    let keepaliveTimer = null;
    let reconnectTimer = null;
    let selfHealTimer = null;
    let streamWatchdogTimer = null;
    let selfHealInFlight = false;
    let videoRecoveryInFlight = false;
    let startupReconnectAttempts = 0;
    let autoStartSuspended = false;
    let capturePermissionPending = false;
    let versionReloading = false;
    let visibleSampleLogged = false;
    let lastActivitySentAt = 0;
    let lastConfigAt = 0;
    let lastFrameAt = 0;
    let currentStreamEpoch = 0;
    let lastAcceptedFrameSequence = 0;
    let configuredFrameEnvelope = 'legacy';
    let lastFrameDropLogAt = 0;
    let lastStaleFrameKeyframeAt = 0;
    let lastControlCodeEntryId = 0;
    let modeNoticeTimer = null;
    let loadingStartedAt = 0;
    let loadingBlockingPhase = '';
    let loadingOverBudgetLogged = false;
    let loadingBudgetTimer = null;
    let loadingEscalationCount = 0;
    let healthReadyLogged = false;
    let sessionActiveLogged = false;
    let streamConfigLogged = false;
    let firstLiveFrameLogged = false;
    let streamDimensions = {width: 720, height: 1616};
    const maxTapDurationMs = 450;
    const maxTapTravelPx = 12;
    const streamMessages = {
      booting: 'Connecting',
      connecting: 'Connecting',
      permission: 'Confirm screen capture on the Pixel',
      waiting: 'Waiting for ticket stream...',
      reconnecting: 'Reconnecting stream...',
      streaming: '',
      unavailable: 'Unavailable',
      ended: 'Session ended'
    };
    const loadingStates = new Set(['booting', 'connecting', 'permission', 'waiting', 'reconnecting']);
    const FRAME_ENVELOPE_MAGIC = 0x54534632;
    const FRAME_ENVELOPE_HEADER_BYTES = 29;

    function randomId() {
      if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID();
      return `${'$'}{Date.now().toString(36)}-${'$'}{Math.random().toString(36).slice(2)}`;
    }
    function getOrCreateViewerId() {
      try {
        const existing = localStorage.getItem(VIEWER_ID_STORAGE_KEY);
        if (existing) return existing;
        const fresh = randomId();
        localStorage.setItem(VIEWER_ID_STORAGE_KEY, fresh);
        return fresh;
      } catch (_) {
        return randomId();
      }
    }
    function withClientIdentity(url) {
      const separator = url.includes('?') ? '&' : '?';
      return url + separator +
        'viewer=' + encodeURIComponent(viewerId) +
        '&page=' + encodeURIComponent(pageId) +
        '&pageVersion=' + encodeURIComponent(PAGE_VERSION);
    }

    function currentStageHeight() {
      const value = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--ticket-stage-height'));
      return Number.isFinite(value) && value > 0 ? value : Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1);
    }
    function currentLoadingPhase(fallback = 'loading') {
      if (!ws || ws.readyState !== WebSocket.OPEN) return 'control_socket_open';
      if (!startCommandSent) return 'session_start';
      if (!videoWs || videoWs.readyState !== WebSocket.OPEN) return 'video_socket_open';
      if (!configured) return 'stream_config_received';
      if (!firstFrameReceived) return 'first_live_frame_drawn';
      return fallback;
    }
    function clearLoadingBudgetTimer() {
      if (loadingBudgetTimer) clearTimeout(loadingBudgetTimer);
      loadingBudgetTimer = null;
    }
    function armLoadingBudgetTimer() {
      clearLoadingBudgetTimer();
      loadingBudgetTimer = setTimeout(() => handleLoadingBudgetExceeded(), LOADING_BUDGET_MS);
    }
    function startLoading(phase, message = '') {
      if (document.body.dataset.streamReady === 'true') return;
      const blockingPhase = currentLoadingPhase(phase);
      const now = performance.now();
      if (!loadingStartedAt) {
        loadingStartedAt = now;
        loadingOverBudgetLogged = false;
        loadingEscalationCount = 0;
        clientLog('loading_started', {phase: blockingPhase, state: phase, message});
      } else if (loadingBlockingPhase && loadingBlockingPhase !== blockingPhase) {
        clientLog('loading_phase', {
          phase: blockingPhase,
          previousPhase: loadingBlockingPhase,
          durationMs: Math.round(now - loadingStartedAt)
        });
      }
      loadingBlockingPhase = blockingPhase;
      armLoadingBudgetTimer();
    }
    function finishLoading(phase = 'first_live_frame_drawn') {
      if (!loadingStartedAt) return;
      const durationMs = Math.round(performance.now() - loadingStartedAt);
      clientLog('loading_finished', {phase, previousPhase: loadingBlockingPhase, durationMs});
      loadingStartedAt = 0;
      loadingBlockingPhase = '';
      loadingOverBudgetLogged = false;
      loadingEscalationCount = 0;
      clearLoadingBudgetTimer();
    }
    function finishNonLiveLoading(phase) {
      if (!loadingStartedAt) return;
      const durationMs = Math.round(performance.now() - loadingStartedAt);
      clientLog('loading_finished', {phase, previousPhase: loadingBlockingPhase, durationMs, live: false});
      loadingStartedAt = 0;
      loadingBlockingPhase = '';
      loadingOverBudgetLogged = false;
      loadingEscalationCount = 0;
      clearLoadingBudgetTimer();
    }
    function handleLoadingBudgetExceeded() {
      if (!loadingStartedAt || document.body.dataset.streamReady === 'true') return;
      const durationMs = Math.round(performance.now() - loadingStartedAt);
      const phase = currentLoadingPhase(loadingBlockingPhase || 'loading');
      loadingBlockingPhase = phase;
      clientLog('loading_over_2s', {phase, durationMs, escalation: loadingEscalationCount});
      loadingOverBudgetLogged = true;
      loadingEscalationCount += 1;
      if (desiredActive && !autoStartSuspended && pageIsVisible()) {
        if (configured || videoWs) {
          recoverVideoPipeline('loading_over_budget', 'Reconnecting stream...', loadingEscalationCount >= 2);
        } else {
          connect(true).catch(() => {});
          connectVideo(true).catch(() => {});
          scheduleStartupReconnect();
        }
      }
      armLoadingBudgetTimer();
    }
    function setStreamState(state, message = '') {
      document.body.dataset.streamState = state;
      const text = message || streamMessages[state] || 'Connecting';
      if (document.body.dataset.streamReady === 'true') {
        if (state === 'streaming') streamPlaceholder.textContent = '';
      } else {
        streamPlaceholder.textContent = text;
      }
      if (state === 'streaming') {
        finishLoading('streaming');
      } else if (loadingStates.has(state)) {
        startLoading(state, text);
      } else if (state === 'unavailable' || state === 'ended') {
        finishNonLiveLoading(state);
      }
    }
    function collapseDetailsToStream() {
      document.body.classList.remove('details-visible');
      controlsPanel.setAttribute('aria-hidden', 'true');
      window.scrollTo(0, 0);
    }
    function setStreamReady(ready) {
      document.body.dataset.streamReady = ready ? 'true' : 'false';
      if (ready) {
        streamPlaceholder.textContent = '';
        finishLoading('first_live_frame_drawn');
      }
      if (!ready) {
        firstFrameReceived = false;
        visibleSampleLogged = false;
        streamConfigLogged = false;
        firstLiveFrameLogged = false;
        collapseDetailsToStream();
      }
    }
    function keepFirstScreenPinned(force = false) {
      if (force) {
        collapseDetailsToStream();
        return;
      }
      if (document.body.classList.contains('details-visible')) return;
      if (window.scrollY <= Math.max(80, currentStageHeight() * 0.2)) {
        window.scrollTo(0, 0);
      }
    }
    function setStatus(text = '') {
      const value = text || '';
      statusEl.textContent = value;
      if (document.body.dataset.streamReady !== 'true') {
        streamPlaceholder.textContent = value || 'Connecting';
      }
    }
    function showStart(message = '') {
      setStreamReady(false);
      setStreamState(message ? 'ended' : 'connecting', message);
      startButton.disabled = false;
      startButton.textContent = 'Start';
      setStatus(message);
      keepFirstScreenPinned(true);
    }
    function formatRemaining(ms) {
      const totalSeconds = Math.max(0, Math.ceil((ms || 0) / 1000));
      const minutes = Math.floor(totalSeconds / 60);
      const seconds = totalSeconds % 60;
      return `${'$'}{minutes}:${'$'}{String(seconds).padStart(2, '0')}`;
    }
    function updateIdleTimer(status) {
      if (!status || !status.active || !desiredActive) {
        idleTimerEl.hidden = true;
        idleTimerEl.classList.remove('urgent');
        return;
      }
      const remaining = Number(status.remainingMillis || 0);
      idleTimerEl.textContent = formatRemaining(remaining);
      idleTimerEl.classList.toggle('urgent', remaining <= 60_000);
      idleTimerEl.hidden = false;
    }
    function cookieValue(name) {
      const prefix = name + '=';
      return document.cookie.split(';').map((item) => item.trim()).find((item) => item.startsWith(prefix))?.slice(prefix.length) || '';
    }
    function rememberControlCodeNotice() {
      document.cookie = CONTROL_CODE_NOTICE_COOKIE + '=1; Max-Age=31536000; Path=/; SameSite=Lax';
    }
    function showModeNotice(kind) {
      if (modeNoticeTimer) clearTimeout(modeNoticeTimer);
      const large = kind === 'large';
      modeNoticeEl.className = 'visible';
      modeNoticeEl.innerHTML = large
        ? '<div class="mode-notice large"><span class="mode-notice-title">' + CONTROL_CODE_NOTICE_TEXT + '</span><span class="mode-notice-body">Var ievadīt kodu no šīs lapas.</span></div>'
        : '<div class="mode-notice small">' + CONTROL_CODE_NOTICE_TEXT + '</div>';
      modeNoticeTimer = setTimeout(() => {
        modeNoticeEl.classList.remove('visible');
      }, large ? 4200 : 1500);
    }
    function handleControlCodeMode(health) {
      const mode = health && health.controlCodeMode;
      if (!mode || !mode.active || !mode.entryId || mode.entryId === lastControlCodeEntryId) return;
      lastControlCodeEntryId = mode.entryId;
      if (cookieValue(CONTROL_CODE_NOTICE_COOKIE) === '1') {
        showModeNotice('small');
        return;
      }
      rememberControlCodeNotice();
      showModeNotice('large');
    }
    function noteActivity(force = false) {
      if (!desiredActive) return;
      const now = Date.now();
      if (!force && now - lastActivitySentAt < 15_000) return;
      lastActivitySentAt = now;
      send({type: 'activity'});
    }
    function updateLayoutMetrics() {
      const viewportWidth = Math.max(
        1,
        Math.round(window.innerWidth || 0),
        Math.round(document.documentElement.clientWidth || 0),
        Math.round(window.visualViewport ? window.visualViewport.width : 0)
      );
      const viewportHeightCandidates = [
        window.innerHeight || 0,
        document.documentElement.clientHeight || 0,
        window.visualViewport ? window.visualViewport.height : 0
      ];
      if (viewportWidth <= 820 && window.screen) {
        viewportHeightCandidates.push(window.screen.height || 0, window.screen.availHeight || 0);
      }
      const stageHeight = Math.max(1, Math.ceil(Math.max(...viewportHeightCandidates)));
      document.documentElement.style.setProperty('--ticket-stage-height', `${'$'}{stageHeight}px`);
      return {stageHeight, viewportWidth};
    }
    function updateDetailsReveal() {
      const threshold = currentStageHeight();
      const visible = window.scrollY >= threshold;
      document.body.classList.toggle('details-visible', visible);
      controlsPanel.setAttribute('aria-hidden', visible ? 'false' : 'true');
    }
    function resizeCanvasBox() {
      updateLayoutMetrics();
      const maxWidth = Math.max(1, streamStage.clientWidth || window.innerWidth || 1);
      const maxHeight = Math.max(1, streamStage.clientHeight || currentStageHeight());
      const scale = Math.max(maxWidth / streamDimensions.width, maxHeight / streamDimensions.height);
      const cssWidth = Math.max(maxWidth, Math.ceil(streamDimensions.width * scale));
      const cssHeight = Math.max(maxHeight, Math.ceil(streamDimensions.height * scale));
      canvas.style.setProperty('--stream-css-width', `${'$'}{cssWidth}px`);
      canvas.style.setProperty('--stream-css-height', `${'$'}{cssHeight}px`);
      updateDetailsReveal();
    }
    function clientLog(event, details = {}) {
      try {
        fetch('/api/v1/client-log', {
          method: 'POST',
          cache: 'no-store',
          keepalive: true,
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({event, pageVersion: PAGE_VERSION, details})
        }).catch(() => {});
      } catch (_) {}
    }
    function versionedRootUrl(serverVersion) {
      return location.origin + '/' + '?v=' + encodeURIComponent(serverVersion || PAGE_VERSION) + '&t=' + Date.now();
    }
    function forceFreshPageReload(serverVersion, reason) {
      if (versionReloading) return;
      versionReloading = true;
      clientLog('page_force_fresh_reload', {serverVersion, pageVersion: PAGE_VERSION, reason});
      location.replace(versionedRootUrl(serverVersion));
    }
    function bootstrapUrl(path) {
      return path + '?pageVersion=' + encodeURIComponent(PAGE_VERSION) + '&t=' + Date.now();
    }
    function fetchNoStoreJson(path) {
      return fetch(bootstrapUrl(path), {
        cache: 'no-store',
        credentials: 'same-origin',
        headers: {
          'Cache-Control': 'no-cache',
          'Pragma': 'no-cache'
        }
      }).then((response) => response.json());
    }
    async function verifyFreshBootstrap(reason = 'boot') {
      try {
        const bootstrap = await fetchNoStoreJson('/api/v1/bootstrap');
        clientLog('bootstrap_ready', {
          reason,
          serverVersion: bootstrap.serverVersion,
          pageVersion: PAGE_VERSION,
          cachePolicy: bootstrap.cachePolicy
        });
        clientLog('loading_phase', {phase: 'bootstrap_ready', sinceNavigationMs: Math.round(performance.now())});
        if (!checkServerVersion(bootstrap)) return false;
        return true;
      } catch (error) {
        clientLog('bootstrap_failed', {reason, message: String(error && error.message || error)});
        return true;
      }
    }
    function purgeLegacyBrowserCaches() {
      try {
        if ('serviceWorker' in navigator) {
          navigator.serviceWorker.getRegistrations()
            .then((registrations) => Promise.all(registrations.map((registration) => registration.unregister())).then(() => registrations.length))
            .then((count) => { if (count > 0) clientLog('legacy_service_workers_unregistered', {count}); })
            .catch(() => {});
        }
        if ('caches' in window) {
          caches.keys()
            .then((keys) => Promise.all(keys.map((key) => caches.delete(key))).then(() => keys.length))
            .then((count) => { if (count > 0) clientLog('legacy_browser_caches_deleted', {count}); })
            .catch(() => {});
        }
        fetchNoStoreJson('/api/v1/cache-cleanup')
          .then((cleanup) => clientLog('legacy_cache_cleanup_route_ready', {
            serverVersion: cleanup.serverVersion,
            pageVersion: PAGE_VERSION,
            cachePolicy: cleanup.cachePolicy
          }))
          .catch((error) => clientLog('legacy_cache_cleanup_route_failed', {message: String(error && error.message || error)}));
      } catch (_) {}
    }
    function socketUrl() {
      return withClientIdentity((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/v1/session');
    }
    function streamSocketUrl() {
      return withClientIdentity((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/v1/stream');
    }
    function serverVersionFrom(value) {
      if (!value || typeof value !== 'object') return '';
      return value.serverVersion || (value.data && value.data.serverVersion) || '';
    }
    function checkServerVersion(value) {
      const serverVersion = serverVersionFrom(value);
      if (!serverVersion || serverVersion === PAGE_VERSION) return true;
      clientLog('page_version_mismatch', {serverVersion, pageVersion: PAGE_VERSION});
      forceFreshPageReload(serverVersion, 'version_mismatch');
      return false;
    }
    function sendVideo(value) {
      if (videoWs && videoWs.readyState === WebSocket.OPEN) {
        videoWs.send(JSON.stringify(value));
        return true;
      }
      return false;
    }
    function closeDecoder(message = 'Connecting') {
      configured = false;
      firstFrameReceived = false;
      needsKeyFrame = true;
      imageTransport = false;
      imageDrawSerial += 1;
      setStreamReady(false);
      setStreamState('connecting', message);
      updateIdleTimer(null);
      if (decoder) {
        try { decoder.close(); } catch (_) {}
        decoder = null;
      }
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      keepFirstScreenPinned();
    }
    function stopKeepalive() {
      if (keepaliveTimer) clearInterval(keepaliveTimer);
      keepaliveTimer = null;
    }
    function clearReconnectTimer() {
      if (reconnectTimer) clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    function closeVideoSocket() {
      const socket = videoWs;
      videoWs = null;
      videoConnecting = null;
      if (socket) {
        try { socket.close(); } catch (_) {}
      }
    }
    async function recoverVideoPipeline(reason, message = 'Reconnecting stream...', restartStream = false) {
      if (videoRecoveryInFlight) return;
      videoRecoveryInFlight = true;
      clientLog('video_pipeline_recovery', {reason, restartStream});
      if (restartStream) send({type: 'restart_stream'});
      closeVideoSocket();
      closeDecoder(message);
      setStreamState('reconnecting', message);
      setStatus(message);
      try {
        await connect(true).catch(() => {});
        if (restartStream) send({type: 'restart_stream'});
        send({type: 'keyframe', reason: 'video_pipeline_recovery'});
        await connectVideo(true);
        sendVideo({type: 'keyframe', reason: 'video_pipeline_recovery'}) || send({type: 'keyframe', reason: 'video_pipeline_recovery'});
      } catch (_) {
        scheduleStartupReconnect();
      } finally {
        videoRecoveryInFlight = false;
      }
    }
    function pageIsVisible() {
      return document.visibilityState !== 'hidden';
    }
    function autoStartBlockedMessage(health, fallback = '') {
      if (health && health.autoStartBlockedReason === 'viewer_inactivity_timeout') return 'Session ended';
      return fallback || '';
    }
    function healthWaitingForCapture(health) {
      return Boolean(health && health.capturePermissionPending && !health.projectionReady);
    }
    function enterCapturePending(health, message) {
      capturePermissionPending = true;
      startCommandSent = true;
      setStreamState('permission', message || (health && health.message) || streamMessages.permission);
      setStatus(message || (health && health.message) || streamMessages.permission);
    }
    function suspendAutoStart(message = '') {
      autoStartSuspended = true;
      desiredActive = false;
      startCommandSent = false;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      stopKeepalive();
      if (ws) {
        try { ws.close(); } catch (_) {}
        ws = null;
      }
      closeVideoSocket();
      closeDecoder(message || 'Session ended');
      showStart(message);
    }
    async function postStartFallback(reason) {
      try {
        const response = await fetch('/api/v1/session/start', {method: 'POST', cache: 'no-store'});
        const result = await response.json();
        if (result && result.state === 'capture_permission_required') {
          capturePermissionPending = true;
          setStreamState('permission', result.message || streamMessages.permission);
          setStatus(result.message || streamMessages.permission);
        } else if (result && result.message && !configured) {
          setStreamState(result.ok ? 'waiting' : 'unavailable', result.message);
          setStatus(result.message);
        }
        clientLog('http_start_fallback', {reason, state: result && result.state, ok: result && result.ok});
        return result;
      } catch (error) {
        clientLog('http_start_fallback_failed', {reason, message: String(error && error.message || error)});
        return null;
      }
    }
    async function sendStartCommand(reason = 'start') {
      startCommandSent = true;
      if (send({type: 'start'})) {
        connectVideo(false).catch(() => scheduleStartupReconnect());
        setTimeout(async () => {
          if (!desiredActive || configured || autoStartSuspended) return;
          const health = await refreshHealth().catch(() => null);
          if (health && !health.streamActive && !health.inactivityActive) {
            await postStartFallback(`${'$'}{reason}_confirm`);
          }
        }, START_CONFIRM_DELAY_MS);
        return;
      }
      await postStartFallback(reason);
      connectVideo(false).catch(() => scheduleStartupReconnect());
    }
    function scheduleStartupReconnect() {
      if (autoStartSuspended || !desiredActive || configured) return false;
      if (capturePermissionPending) {
        setStreamState('permission', streamMessages.permission);
        setStatus(streamMessages.permission);
        return false;
      }
      startupReconnectAttempts += 1;
      const delayMs = Math.min(STARTUP_RECONNECT_MAX_DELAY_MS, 150 * startupReconnectAttempts);
      setStreamState(startCommandSent ? 'reconnecting' : 'connecting');
      setStatus(startCommandSent ? 'Reconnecting stream...' : 'Connecting');
      clearReconnectTimer();
      reconnectTimer = setTimeout(async () => {
        reconnectTimer = null;
        if (!desiredActive || configured) return;
        try {
          const health = await refreshHealth().catch(() => null);
          if (healthWaitingForCapture(health)) {
            enterCapturePending(health);
            return;
          }
          await connect(true);
          await sendStartCommand('startup_reconnect');
          await connectVideo(true);
        } catch (_) {
          scheduleStartupReconnect();
        }
      }, delayMs);
      return true;
    }
    function startKeepalive() {
      stopKeepalive();
      keepaliveTimer = setInterval(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          send({type: 'keepalive', active: true});
        }
      }, 15000);
    }
    function send(value) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(value));
        return true;
      }
      return false;
    }
    function sendStopBeacon() {
      try {
        if (navigator.sendBeacon) {
          navigator.sendBeacon('/api/v1/client-log', new Blob([JSON.stringify({event: 'client_detached', pageVersion: PAGE_VERSION})], {type: 'application/json'}));
          return;
        }
      } catch (_) {}
      clientLog('client_detached');
    }
    async function endSession(reason, pageLeaving = false, message = '') {
      const wasActive = desiredActive || startCommandSent || configured;
      const explicitStop = reason === 'browser_stop';
      desiredActive = false;
      startCommandSent = false;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      stopKeepalive();
      if (wasActive && !pageLeaving && explicitStop) {
        send({type: 'stop', reason, explicit: true});
        try {
          await fetch('/api/v1/session/stop', {
            method: 'POST',
            cache: 'no-store',
            keepalive: true,
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({explicit: true, reason})
          });
        } catch (_) {}
      } else if (wasActive) {
        clientLog('session_detached_without_stop', {reason, pageLeaving});
      }
      if (ws) {
        try { ws.close(); } catch (_) {}
        ws = null;
      }
      closeVideoSocket();
      closeDecoder();
      showStart(pageLeaving ? '' : message);
    }
    async function connect(force = false) {
      if (ws && ws.readyState === WebSocket.OPEN) return;
      if (ws && ws.readyState === WebSocket.CONNECTING && connecting) return connecting;
      if (force && ws) {
        try { ws.close(); } catch (_) {}
      }
      setStreamState('connecting');
      setStatus('Connecting');
      connecting = new Promise((resolve, reject) => {
        const socket = new WebSocket(socketUrl());
        let settled = false;
        ws = socket;
        const timeout = setTimeout(() => {
          if (settled) return;
          settled = true;
          setStreamState('reconnecting', 'Connection timed out');
          setStatus('Connection timed out');
          try { socket.close(); } catch (_) {}
          reject(new Error('websocket timeout'));
        }, CONTROL_SOCKET_TIMEOUT_MS);
        socket.onopen = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timeout);
          clientLog('loading_phase', {phase: 'control_socket_open', sinceNavigationMs: Math.round(performance.now())});
          startKeepalive();
          send({type: 'keepalive', active: true});
          resolve();
        };
        socket.onerror = () => {
          clientLog('control_websocket_error');
          setStreamState('reconnecting', 'Connection failed');
          setStatus('Connection failed');
        };
        socket.onmessage = handleControlSocketMessage;
        socket.onclose = (event) => {
          clientLog('control_websocket_close', {code: event.code, reason: event.reason, clean: event.wasClean, desiredActive});
          clearTimeout(timeout);
          if (!settled) {
            settled = true;
            reject(new Error('websocket closed'));
          }
          if (ws === socket) ws = null;
          stopKeepalive();
          if (desiredActive && !autoStartSuspended) {
            setTimeout(() => connect(true).catch(() => {}), 500);
          } else if (!autoStartSuspended) {
            showStart();
          }
        };
      }).finally(() => {
        connecting = null;
      });
      return connecting;
    }
    async function handleControlSocketMessage(event) {
      if (typeof event.data === 'string') {
        const msg = JSON.parse(event.data);
        if (!checkServerVersion(msg)) return;
        if (msg.type === 'health') {
          if (msg.data && msg.data.streamActive && !sessionActiveLogged) {
            sessionActiveLogged = true;
            clientLog('loading_phase', {phase: 'session_active', sinceNavigationMs: Math.round(performance.now())});
          }
          if (msg.data) updateIdleTimer(msg.data);
          if (msg.data) handleControlCodeMode(msg.data);
          if (msg.data && msg.data.autoStartAllowed === false && !desiredActive) {
            suspendAutoStart(autoStartBlockedMessage(msg.data, msg.message || ''));
            return;
          }
          if (msg.data && desiredActive && !configured && healthWaitingForCapture(msg.data)) {
            enterCapturePending(msg.data, msg.message);
            return;
          }
          if (msg.data && msg.data.projectionReady) capturePermissionPending = false;
          if (
            desiredActive &&
            startCommandSent &&
            configured &&
            msg.data &&
            !msg.data.streamActive &&
            !msg.data.inactivityActive
          ) {
            startCommandSent = false;
            closeDecoder('Reconnecting stream...');
            scheduleStartupReconnect();
            return;
          }
          if (!configured && desiredActive) {
            setStreamState('waiting', msg.message || streamMessages.waiting);
            setStatus(msg.message || '');
          }
        }
        if (msg.type === 'idle') updateIdleTimer(msg);
      }
    }
    async function connectVideo(force = false) {
      if (videoWs && videoWs.readyState === WebSocket.OPEN) return;
      if (videoWs && videoWs.readyState === WebSocket.CONNECTING && videoConnecting) return videoConnecting;
      if (force) {
        closeVideoSocket();
      }
      if (!desiredActive || autoStartSuspended) return;
      setStreamState(firstFrameReceived ? 'streaming' : 'waiting');
      videoConnecting = new Promise((resolve, reject) => {
        const socket = new WebSocket(streamSocketUrl());
        let settled = false;
        videoWs = socket;
        socket.binaryType = 'arraybuffer';
        const timeout = setTimeout(() => {
          if (settled) return;
          settled = true;
          clientLog('video_websocket_timeout');
          try { socket.close(); } catch (_) {}
          reject(new Error('video websocket timeout'));
        }, VIDEO_SOCKET_TIMEOUT_MS);
        socket.onopen = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timeout);
          clientLog('loading_phase', {phase: 'video_socket_open', sinceNavigationMs: Math.round(performance.now())});
          sendVideo({type: 'keyframe', reason: 'video_socket_open'});
          resolve();
        };
        socket.onerror = () => {
          clientLog('video_websocket_error');
          setStreamState('reconnecting', 'Video connection failed');
        };
        socket.onmessage = handleVideoSocketMessage;
        socket.onclose = (event) => {
          clientLog('video_websocket_close', {code: event.code, reason: event.reason, clean: event.wasClean, desiredActive});
          clearTimeout(timeout);
          if (!settled) {
            settled = true;
            reject(new Error('video websocket closed'));
          }
          if (videoWs === socket) videoWs = null;
          if (desiredActive && !autoStartSuspended) {
            closeDecoder('Reconnecting stream...');
            scheduleStartupReconnect();
          }
        };
      }).finally(() => {
        videoConnecting = null;
      });
      return videoConnecting;
    }
    function readUint64(view, offset) {
      return view.getUint32(offset) * 4294967296 + view.getUint32(offset + 4);
    }
    function logFrameDrop(reason, detail) {
      const now = performance.now();
      if (now - lastFrameDropLogAt < 1000) return;
      lastFrameDropLogAt = now;
      clientLog('frame_dropped_freshness', Object.assign({reason}, detail || {}));
    }
    function parseFrameEnvelope(raw) {
      const data = new Uint8Array(raw);
      const view = new DataView(raw);
      if (data.byteLength >= FRAME_ENVELOPE_HEADER_BYTES && view.getUint32(0) === FRAME_ENVELOPE_MAGIC) {
        const flags = view.getUint8(4);
        return {
          version: 'tsf2',
          kind: (flags & 1) === 1 ? 'key' : 'delta',
          epoch: readUint64(view, 5),
          sequence: readUint64(view, 13),
          timestamp: readUint64(view, 21),
          data: data.slice(FRAME_ENVELOPE_HEADER_BYTES)
        };
      }
      if (configuredFrameEnvelope === 'tsf2') {
        logFrameDrop('legacy_frame_in_tsf2_stream', {bytes: data.byteLength});
        sendVideo({type: 'keyframe', reason: 'legacy_frame_in_tsf2_stream'}) || send({type: 'keyframe', reason: 'legacy_frame_in_tsf2_stream'});
        return null;
      }
      if (data.byteLength < 9) return null;
      return {
        version: 'legacy',
        kind: data[0] === 1 ? 'key' : 'delta',
        epoch: currentStreamEpoch,
        sequence: 0,
        timestamp: readUint64(view, 1),
        data: data.slice(9)
      };
    }
    function acceptFreshFrame(frame) {
      if (!frame) return false;
      if (currentStreamEpoch && frame.epoch && frame.epoch !== currentStreamEpoch) {
        logFrameDrop('old_epoch', {currentEpoch: currentStreamEpoch, frameEpoch: frame.epoch, sequence: frame.sequence});
        return false;
      }
      if (frame.sequence && frame.sequence <= lastAcceptedFrameSequence) {
        logFrameDrop('old_sequence', {lastSequence: lastAcceptedFrameSequence, frameSequence: frame.sequence});
        return false;
      }
      if (needsKeyFrame && frame.kind !== 'key') return false;
      if (frame.kind === 'key') needsKeyFrame = false;
      if (frame.sequence) lastAcceptedFrameSequence = frame.sequence;
      return true;
    }
    async function handleVideoSocketMessage(event) {
      if (typeof event.data === 'string') {
        const msg = JSON.parse(event.data);
        if (!checkServerVersion(msg)) return;
        if (msg.type === 'config') {
          await configureDecoder(msg);
        }
        return;
      }
      if (!configured) return;
      const frame = parseFrameEnvelope(event.data);
      if (!acceptFreshFrame(frame)) return;
      if (imageTransport) {
        drawPngFrame(frame).catch((error) => {
          clientLog('png_draw_failed', {message: String(error && error.message || error)});
          recoverVideoPipeline('png_draw_failed', 'Reconnecting stream...');
        });
        return;
      }
      if (!decoder) return;
      try {
        decoder.decode(new EncodedVideoChunk({type: frame.kind, timestamp: frame.timestamp, data: frame.data}));
      } catch (error) {
        clientLog('decoder_decode_failed', {message: String(error && error.message || error)});
        recoverVideoPipeline('decoder_decode_failed', 'Reconnecting stream...');
      }
    }
    async function configureDecoder(config) {
      if (!checkServerVersion(config)) return;
      const png = config.transport === '${TicketScreenConfig.ROOT_PNG_TRANSPORT}' || String(config.codec || '').toLowerCase() === '${TicketScreenConfig.ROOT_PNG_CODEC_STRING}';
      if (png) {
        closeDecoder(streamMessages.waiting);
        imageTransport = true;
        if (!streamConfigLogged) {
          streamConfigLogged = true;
          clientLog('loading_phase', {
            phase: 'stream_config_received',
            sinceNavigationMs: Math.round(performance.now()),
            codec: config.codec,
            width: config.width,
            height: config.height
          });
        }
        canvas.width = config.width;
        canvas.height = config.height;
        ctx.imageSmoothingEnabled = false;
        streamDimensions = {width: config.width, height: config.height};
        configuredFrameEnvelope = config.frameEnvelope || 'legacy';
        currentStreamEpoch = Number(config.streamEpoch || 0);
        lastAcceptedFrameSequence = 0;
        canvas.style.setProperty('--stream-aspect', `${'$'}{config.width} / ${'$'}{config.height}`);
        canvas.dataset.streamWidth = String(config.width);
        canvas.dataset.streamHeight = String(config.height);
        resizeCanvasBox();
        lastConfigAt = performance.now();
        lastFrameAt = 0;
        setStreamState('waiting');
        configured = true;
        startupReconnectAttempts = 0;
        clearReconnectTimer();
        needsKeyFrame = true;
        sendVideo({type: 'keyframe', reason: 'config_received'}) || send({type: 'keyframe', reason: 'config_received'});
        return;
      }
      imageTransport = false;
      if (!('VideoDecoder' in window)) {
        clientLog('webcodecs_missing');
        await endSession('webcodecs_missing', false, 'This browser does not support WebCodecs');
        return;
      }
      const h264 = String(config.codec || '').startsWith('avc1') || config.transport === 'h264-annexb' || config.transport === '${TicketScreenConfig.ROOT_FFMPEG_H264_TRANSPORT}';
      const decoderConfig = {codec: config.codec, codedWidth: config.width, codedHeight: config.height};
      if (h264) decoderConfig.avc = {format: 'annexb'};
      const supported = await VideoDecoder.isConfigSupported(decoderConfig);
      if (!supported.supported) {
        clientLog('video_decode_unsupported', {config: decoderConfig});
        await endSession('video_decode_unsupported', false, h264 ? 'This browser cannot decode H.264 here' : 'This browser cannot decode AV1 here');
        return;
      }
      closeDecoder(streamMessages.waiting);
      if (!streamConfigLogged) {
        streamConfigLogged = true;
        clientLog('loading_phase', {
          phase: 'stream_config_received',
          sinceNavigationMs: Math.round(performance.now()),
          codec: config.codec,
          width: config.width,
          height: config.height
        });
      }
      canvas.width = config.width;
      canvas.height = config.height;
      ctx.imageSmoothingEnabled = false;
      streamDimensions = {width: config.width, height: config.height};
      configuredFrameEnvelope = config.frameEnvelope || 'legacy';
      currentStreamEpoch = Number(config.streamEpoch || 0);
      lastAcceptedFrameSequence = 0;
      canvas.style.setProperty('--stream-aspect', `${'$'}{config.width} / ${'$'}{config.height}`);
      canvas.dataset.streamWidth = String(config.width);
      canvas.dataset.streamHeight = String(config.height);
      resizeCanvasBox();
      lastConfigAt = performance.now();
      lastFrameAt = 0;
      setStreamState('waiting');
      decoder = new VideoDecoder({
        output(frame) {
          ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
          lastFrameAt = performance.now();
          if (!firstFrameReceived) {
            firstFrameReceived = true;
            if (!firstLiveFrameLogged) {
              firstLiveFrameLogged = true;
              clientLog('loading_phase', {phase: 'first_live_frame_drawn', sinceNavigationMs: Math.round(performance.now())});
            }
            setStreamReady(true);
            setStreamState('streaming');
            setStatus('');
            keepFirstScreenPinned(true);
            sampleVisibleFrame();
          }
          frame.close();
        },
        error(error) {
          clientLog('decoder_error', {message: error.message});
          recoverVideoPipeline('decoder_error', 'Reconnecting stream...');
        }
      });
      decoder.configure(decoderConfig);
      configured = true;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      needsKeyFrame = true;
      startButton.disabled = false;
      startButton.textContent = 'Start';
      sendVideo({type: 'keyframe', reason: 'config_received'}) || send({type: 'keyframe', reason: 'config_received'});
      requestAnimationFrame(resizeCanvasBox);
    }
    async function drawPngFrame(frame) {
      const drawId = ++imageDrawSerial;
      const blob = new Blob([frame.data], {type: 'image/png'});
      const bitmap = await createImageBitmap(blob);
      try {
        if (!configured || !imageTransport || drawId !== imageDrawSerial) return;
        ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
        lastFrameAt = performance.now();
        if (!firstFrameReceived) {
          firstFrameReceived = true;
          if (!firstLiveFrameLogged) {
            firstLiveFrameLogged = true;
            clientLog('loading_phase', {phase: 'first_live_frame_drawn', sinceNavigationMs: Math.round(performance.now())});
          }
          setStreamReady(true);
          setStreamState('streaming');
          setStatus('');
          keepFirstScreenPinned(true);
          sampleVisibleFrame();
        }
      } finally {
        if (bitmap && bitmap.close) bitmap.close();
      }
    }
    function sampleVisibleFrame() {
      if (visibleSampleLogged) return;
      visibleSampleLogged = true;
      setTimeout(() => {
        try {
          const sampleWidth = Math.min(48, canvas.width);
          const sampleHeight = Math.min(48, canvas.height);
          const x = Math.max(0, Math.floor((canvas.width - sampleWidth) / 2));
          const y = Math.max(0, Math.floor((canvas.height - sampleHeight) / 2));
          const pixels = ctx.getImageData(x, y, sampleWidth, sampleHeight).data;
          let nonBlackPixels = 0;
          for (let i = 0; i < pixels.length; i += 4) {
            if (pixels[i] > 8 || pixels[i + 1] > 8 || pixels[i + 2] > 8) nonBlackPixels += 1;
          }
          clientLog(nonBlackPixels > 0 ? 'first_frame_visible_sample' : 'first_frame_black_sample', {
            nonBlackPixels,
            samplePixels: sampleWidth * sampleHeight,
            width: canvas.width,
            height: canvas.height
          });
        } catch (error) {
          clientLog('first_frame_sample_failed', {message: String(error && error.message || error)});
        }
      }, 250);
    }
    window.addEventListener('error', (event) => clientLog('window_error', {message: event.message}));
    window.addEventListener('unhandledrejection', (event) => clientLog('unhandled_rejection', {message: String(event.reason && event.reason.message || event.reason)}));
    window.addEventListener('resize', resizeCanvasBox);
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', resizeCanvasBox);
      window.visualViewport.addEventListener('scroll', resizeCanvasBox);
    }
    window.addEventListener('scroll', updateDetailsReveal, {passive: true});
    async function ensureStreaming(reason = 'self_heal') {
      if (autoStartSuspended) return;
      if (!pageIsVisible() || selfHealInFlight) return;
      if (desiredActive && configured && firstFrameReceived) {
        if (!ws || ws.readyState === WebSocket.CLOSED) connect(true).catch(() => {});
        if (!videoWs || videoWs.readyState === WebSocket.CLOSED) connectVideo(true).catch(() => scheduleStartupReconnect());
        return;
      }
      selfHealInFlight = true;
      try {
        const health = await refreshHealth();
        if (health.autoStartAllowed === false && !desiredActive) {
          suspendAutoStart(autoStartBlockedMessage(health, health.message || ''));
          return;
        }
        const ffmpegSupported = Boolean(health.ffmpeg && health.ffmpeg.available);
        if (!health.viviInstalled || !ffmpegSupported) return;
        if (healthWaitingForCapture(health)) {
          desiredActive = true;
          enterCapturePending(health);
          await connect(true).catch(() => {});
          await connectVideo(false).catch(() => {});
          return;
        }
        if (!desiredActive) {
          clientLog('self_heal_start', {reason});
          await start();
        } else if (!configured && !reconnectTimer) {
          await connect(true).catch(() => {});
          await connectVideo(false).catch(() => {});
          scheduleStartupReconnect();
        } else if (configured && !firstFrameReceived) {
          sendVideo({type: 'keyframe', reason: 'first_frame_retry'}) || send({type: 'keyframe', reason: 'first_frame_retry'});
        }
      } catch (_) {
        if (!desiredActive) {
          clientLog('self_heal_start_without_health', {reason});
          await start();
        } else if (!configured && !reconnectTimer) {
          await connect(true).catch(() => {});
          await connectVideo(false).catch(() => {});
          scheduleStartupReconnect();
        }
      } finally {
        selfHealInFlight = false;
      }
    }
    function startSelfHealTimer() {
      if (selfHealTimer) clearInterval(selfHealTimer);
      selfHealTimer = setInterval(() => ensureStreaming('watchdog'), SELF_HEAL_INTERVAL_MS);
    }
    function startStreamWatchdog() {
      if (streamWatchdogTimer) clearInterval(streamWatchdogTimer);
      streamWatchdogTimer = setInterval(() => {
        if (!desiredActive || autoStartSuspended || !pageIsVisible()) return;
        const now = performance.now();
        if (!configured) {
          if (!reconnectTimer) scheduleStartupReconnect();
          return;
        }
        if (!firstFrameReceived && now - lastConfigAt > NO_FIRST_FRAME_MS) {
          clientLog('stream_watchdog_no_first_frame', {sinceConfigMs: Math.round(now - lastConfigAt)});
          recoverVideoPipeline('stream_watchdog_no_first_frame');
          return;
        }
        const staleForMs = now - lastFrameAt;
        if (firstFrameReceived && staleForMs > STALE_FRAME_SOFT_MS) {
          if (now - lastStaleFrameKeyframeAt > STALE_KEYFRAME_REQUEST_INTERVAL_MS) {
            lastStaleFrameKeyframeAt = now;
            clientLog('stream_watchdog_stale_frame', {sinceFrameMs: Math.round(staleForMs), action: 'keyframe'});
            sendVideo({type: 'keyframe', reason: 'stale_frame_retry'}) || send({type: 'keyframe', reason: 'stale_frame_retry'});
          }
          if (staleForMs > STALE_FRAME_RECONNECT_MS) {
            clientLog('stream_watchdog_stale_frame_reconnect', {sinceFrameMs: Math.round(staleForMs)});
            recoverVideoPipeline('stream_watchdog_stale_frame_reconnect');
          }
        }
      }, STREAM_WATCHDOG_INTERVAL_MS);
    }
    async function refreshHealth() {
      const response = await fetch('/api/v1/health', {cache: 'no-store'});
      const health = await response.json();
      if (!checkServerVersion(health)) return health;
      if (!healthReadyLogged) {
        healthReadyLogged = true;
        clientLog('loading_phase', {phase: 'health_ready', sinceNavigationMs: Math.round(performance.now()), ok: Boolean(health.ok)});
      }
      if (health.streamActive && !sessionActiveLogged) {
        sessionActiveLogged = true;
        clientLog('loading_phase', {phase: 'session_active', sinceNavigationMs: Math.round(performance.now())});
      }
      updateIdleTimer(health);
      handleControlCodeMode(health);
      if (health.autoStartAllowed === false && !desiredActive) {
        const message = autoStartBlockedMessage(health, health.message || '');
        setStreamState('ended', message);
        setStatus(message);
      } else if (!health.viviInstalled || !(health.ffmpeg && health.ffmpeg.available)) {
        setStreamState('unavailable', health.message || 'Unavailable');
        setStatus(health.message || 'Unavailable');
      } else if (desiredActive && healthWaitingForCapture(health)) {
        enterCapturePending(health);
      } else if (health.projectionReady) {
        capturePermissionPending = false;
      } else if (desiredActive && health.streamActive && !firstFrameReceived) {
        setStreamState('waiting');
      } else if (!desiredActive) {
        setStatus('');
      }
      return health;
    }
    async function start() {
      if (desiredActive) {
        await sendStartCommand('manual_retry');
        return;
      }
      autoStartSuspended = false;
      desiredActive = true;
      startCommandSent = false;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      closeDecoder('Connecting');
      startButton.disabled = true;
      startButton.textContent = 'Starting';
      setStatus('');
      try {
        await connect(true);
        await sendStartCommand('start');
        await connectVideo(false);
      } catch (_) {
        scheduleStartupReconnect();
      }
    }
    async function stop() {
      await endSession('browser_stop', false, '');
    }
    function point(event) {
      const rect = canvas.getBoundingClientRect();
      return {
        x: Math.round(((event.clientX - rect.left) / rect.width) * canvas.width),
        y: Math.round(((event.clientY - rect.top) / rect.height) * canvas.height)
      };
    }
    function remoteKeyPayload(event) {
      if (!desiredActive || !configured) return null;
      if (event.ctrlKey || event.metaKey || event.altKey) return null;
      const key = event.key === 'Esc' ? 'Escape' : event.key;
      if (key.length === 1 && /^[A-Za-z0-9]${'$'}/.test(key)) return {key};
      if (['Backspace', 'Delete', 'Enter', 'Escape'].includes(key)) return {key};
      return null;
    }
    function forwardRemoteKey(event) {
      noteActivity(true);
      const payload = remoteKeyPayload(event);
      if (!payload) return;
      event.preventDefault();
      send({type: 'key', key: payload.key});
    }
    canvas.addEventListener('pointerdown', (event) => {
      if (!desiredActive || !configured) return;
      if (event.button != null && event.button !== 0) return;
      noteActivity(true);
      pointerStart = {...point(event), at: performance.now()};
      if (event.pointerType === 'mouse') {
        try { canvas.setPointerCapture(event.pointerId); } catch (_) {}
      }
    });
    canvas.addEventListener('pointerup', (event) => {
      if (!desiredActive || !configured) return;
      if (!pointerStart) return;
      const end = point(event);
      const dx = end.x - pointerStart.x;
      const dy = end.y - pointerStart.y;
      const distance = Math.hypot(dx, dy);
      const heldMs = performance.now() - pointerStart.at;
      if (distance < maxTapTravelPx && heldMs <= maxTapDurationMs) {
        send({type: 'tap', x: end.x, y: end.y});
      }
      pointerStart = null;
    });
    canvas.addEventListener('pointercancel', () => { pointerStart = null; });
    window.addEventListener('keydown', forwardRemoteKey, {capture: true});
    window.addEventListener('wheel', () => noteActivity(), {capture: true, passive: true});
    window.addEventListener('pointermove', () => noteActivity(), {capture: true, passive: true});
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden' && desiredActive && (configured || startCommandSent)) {
        endSession('page_hidden', true);
      } else if (document.visibilityState === 'visible') {
        ensureStreaming('visibility_visible');
      }
    });
    window.addEventListener('pagehide', () => { if (desiredActive) endSession('page_hidden', true); });
    window.addEventListener('pageshow', () => {
      keepFirstScreenPinned(true);
      ensureStreaming('pageshow');
    });
    window.addEventListener('focus', () => ensureStreaming('focus'));
    window.addEventListener('beforeunload', () => { if (desiredActive) endSession('page_unload', true); });
    document.getElementById('start').addEventListener('click', () => start());
    resizeCanvasBox();
    keepFirstScreenPinned(true);
    clientLog('page_boot', {pageVersion: PAGE_VERSION, href: location.href});
    purgeLegacyBrowserCaches();
    verifyFreshBootstrap('initial_load');
    clientLog('loading_phase', {phase: 'html_ready', sinceNavigationMs: Math.round(performance.now())});
    startLoading('initial_connection');
    startSelfHealTimer();
    startStreamWatchdog();
    ensureStreaming('initial_load').catch(() => setStatus('Unavailable'));
  </script>
</body>
</html>
  """.trimIndent()
}
