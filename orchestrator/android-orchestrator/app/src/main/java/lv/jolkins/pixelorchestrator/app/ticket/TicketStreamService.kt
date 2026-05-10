package lv.jolkins.pixelorchestrator.app.ticket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
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
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

class TicketStreamService : Service() {
  private data class TicketClientInfo(
    val video: Boolean,
    val viewerId: String?,
    val pageId: String?,
    val pageVersion: String?,
    val generation: Long
  )

  private data class TicketTapTarget(
    val x: Int,
    val y: Int,
    val reason: String,
    val candidateZone: String? = null,
    val detectedButtonBounds: String? = null,
    val noOp: Boolean = false
  )

  private data class ControlCodeSnapFallbackDecision(
    val accepted: Boolean,
    val reason: String
  )

  private data class ControlCodeImmediateStartDecision(
    val accepted: Boolean,
    val reason: String
  )

  private data class GeneratedControlCodeResult(
    val value: String,
    val hierarchy: String
  )

  private data class FastControlCodeTargets(
    val input: TicketViviPageAction,
    val submit: TicketViviPageAction
  )

  private data class FastControlCodeDelivery(
    val ok: Boolean,
    val reason: String,
    val value: String = "",
    val imageBytes: ByteArray? = null,
    val cleanupStart: FastControlCodeCleanupStart? = null,
    val generatedHierarchy: String = "",
    val cleanupRequired: Boolean = true
  )

  private data class FastControlCodeCleanupStart(
    val startedAtMillis: Long,
    val closeAction: String,
    val action: TicketViviPageAction?,
    val closeSucceeded: Boolean,
    val fallbackState: TicketViviRecoveryState? = null
  )

  private data class ControlCodePngCaptureResult(
    val bytes: ByteArray? = null,
    val error: String = ""
  )

  private data class ControlCodePngCaptureSession(
    val process: Process,
    val input: BufferedInputStream
  )

  private data class ControlCodeImageCaptureOutcome(
    val bytes: ByteArray? = null,
    val cleanupStart: FastControlCodeCleanupStart? = null,
    val error: String = ""
  )

  private data class ControlCodeResultWaitOutcome(
    val generated: GeneratedControlCodeResult? = null,
    val failureReason: String = "control_code_result_timeout"
  )

  private enum class ControlCodeSubmitTransition {
    GENERATED_RESULT,
    RETURNED_NO_RESULT,
    STILL_ON_POPUP,
    TIMEOUT
  }

  private data class RawFrameRegionStats(
    val sampled: Int,
    val meanLuminance: Double,
    val darkRatio: Double
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
  private val controlCodeRequestMutex = Mutex()
  private val running = AtomicBoolean(false)
  private val rootFfmpegH264CaptureEngine = TicketRootFfmpegH264CaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootFfmpegH264CaptureFrame,
    onStateChanged = { health ->
      handleRootFfmpegH264CaptureStateChanged(health)
    }
  )

  private var serverJob: Job? = null
  private var serverSocket: ServerSocket? = null
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
  private var controlExitCleanupJob: Job? = null
  private var rootH264BlankProbeJob: Job? = null
  private var ticketScreenWakeLock: PowerManager.WakeLock? = null
  private val viviStateMemory = TicketViviStateMemory()
  private lateinit var ticketRecoveryCoordinator: TicketRecoveryCoordinator
  private lateinit var ticketAutopilot: TicketAutopilot
  @Volatile private var viviForegroundGraceUntilMillis: Long = 0L
  @Volatile private var lastViviPageEnforceAtMillis: Long = 0L
  @Volatile private var cachedForegroundViolationReason: String? = null
  @Volatile private var cachedForegroundCheckedAtMillis: Long = 0L
  @Volatile private var controlCodePopupReadyUntilMillis: Long = 0L
  @Volatile private var controlCodePopupSurfaceCache: TicketViviControlCodePopupSurface? = null
  @Volatile private var controlCodePopupSurfaceCachedAtMillis: Long = 0L
  @Volatile private var startupDisconnectGraceUntilMillis: Long = 0L
  @Volatile private var ticketSessionState: String = TICKET_SESSION_IDLE
  @Volatile private var ticketSessionStateChangedAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var ticketSessionStateReason: String = "init"
  @Volatile private var lastOverBudgetTicketState: String? = null
  @Volatile private var lastOverBudgetTicketStateDurationMillis: Long? = null
  @Volatile private var lastOverBudgetTicketStateReason: String? = null
  @Volatile private var streamActive: Boolean = false
  @Volatile private var ffmpegCaptureVerified: Boolean = false
  @Volatile private var activeCaptureMode: String = CAPTURE_MODE_IDLE
  @Volatile private var fallbackReason: String? = null
  @Volatile private var ffmpegCaptureSnapshot: TicketFfmpegHealth = TicketFfmpegHealth()
  @Volatile private var lastRootH264BlankProbeAtMillis: Long = 0L
  @Volatile private var lastRootH264BlankProbeResult: String = "not_run"
  @Volatile private var rootH264BlankProbeRecoveries: Long = 0L
  @Volatile private var rootH264BlankProbeFailures: Long = 0L
  @Volatile private var inputGateAllowed: Boolean = false
  @Volatile private var inputGateReason: String = "no_active_control"
  @Volatile private var inputGateChangedAtMillis: Long = 0L
  @Volatile private var lastControlCodeSnapRawX: Int? = null
  @Volatile private var lastControlCodeSnapRawY: Int? = null
  @Volatile private var lastControlCodeSnapCandidateZone: String? = null
  @Volatile private var lastControlCodeSnapDetectedButtonBounds: String? = null
  @Volatile private var lastControlCodeSnapTarget: String? = null
  @Volatile private var lastControlCodeSnapAccepted: Boolean? = null
  @Volatile private var lastControlCodeSnapReason: String? = null
  @Volatile private var lastControlCodeSnapFinalX: Int? = null
  @Volatile private var lastControlCodeSnapFinalY: Int? = null
  @Volatile private var lastControlCodeSnapCompletedAtMillis: Long = 0L
  @Volatile private var controlCodeModeActive: Boolean = false
  @Volatile private var controlCodeModeEntryId: Long = 0L
  @Volatile private var controlCodeModeEnteredAtMillis: Long = 0L
  @Volatile private var controlCodeTransitionGraceUntilMillis: Long = 0L
  @Volatile private var controlExitPopupLikelyUntilMillis: Long = 0L
  @Volatile private var lastControlCodeSurfaceState: String? = null
  @Volatile private var lastControlCodeSurfaceSeenAtMillis: Long = 0L
  @Volatile private var lastControlExitDirtySurfaceState: String? = null
  @Volatile private var lastControlExitDirectCloseAtMillis: Long = 0L
  @Volatile private var lastControlExitGeometryCloseAtMillis: Long = 0L
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
  @Volatile private var lastControlCodeRequestId: String? = null
  @Volatile private var lastControlCodeRequestStatus: String = "idle"
  @Volatile private var lastControlCodeRequestReason: String? = null
  @Volatile private var lastControlCodeRequestValue: String? = null
  @Volatile private var lastControlCodeRequestDurationMillis: Long? = null
  @Volatile private var lastControlCodeRequestCompletedAtMillis: Long = 0L
  @Volatile private var duplicateControlCodeResultCount: Long = 0L
  @Volatile private var lastDuplicateControlCodeRequestId: String? = null
  @Volatile private var lastDuplicateControlCodeResultAtMillis: Long = 0L
  private val recentControlCodeResultMessages = mutableMapOf<String, String>()
  private val recentControlCodeResultOrder = ArrayDeque<String>()
  @Volatile private var lastControlExitCleanupReason: String? = null
  @Volatile private var lastControlExitCleanupDetectedState: String? = null
  @Volatile private var lastControlExitCleanupCloseAction: String? = null
  @Volatile private var lastControlExitCleanupDetectorSource: String? = null
  @Volatile private var lastControlExitCleanupSurfaceProbeResult: String? = null
  @Volatile private var lastControlExitCleanupDurationMillis: Long? = null
  @Volatile private var lastControlExitCleanupCompletedAtMillis: Long = 0L
  @Volatile private var lastControlExitCleanupVerificationResult: String? = null
  @Volatile private var lastControlExitCleanupSucceeded: Boolean? = null
  @Volatile private var lastControlExitCleanupFreshFrameRequested: Boolean = false
  @Volatile private var lastControlExitSurfaceProbeAtMillis: Long = 0L
  @Volatile private var lastControlExitSurfaceProbeState: TicketViviRecoveryState? = null
  @Volatile private var lastControlExitSurfaceProbeReason: String? = null
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
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      TicketScreenConfig.ACTION_STOP_SERVER -> {
        stopLocalServer()
        stopSelf()
        return START_NOT_STICKY
      }
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
    rootH264BlankProbeJob?.cancel()
    rootH264BlankProbeJob = null
    cancelInactivityTimer()
    cancelForegroundGuard()
    postRemoteTapForegroundCheckJob?.cancel()
    postRemoteTapForegroundCheckJob = null
    controlExitCleanupJob?.cancel()
    controlExitCleanupJob = null
    if (::ticketRecoveryCoordinator.isInitialized) {
      ticketRecoveryCoordinator.cancel()
    }
    if (serviceEnabled && !touchBrightnessOwnsTicketBrightness()) {
      runCatching { runBlocking { enforceTicketSafeBrightness("service_destroyed_service_enabled") } }
    } else if (serviceEnabled) {
      ticketBrightnessGuardLastMessage = "Ticket brightness guard parked because touch brightness owns panel brightness"
    } else {
      runCatching { runBlocking { restoreTicketBrightness("service_destroyed_service_off") } }
      PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(null)
    }
    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(false)
    rootFfmpegH264CaptureEngine.stop("service_destroyed")
    runCatching { runBlocking { rootFfmpegH264CaptureEngine.cleanupStaleProcesses() } }
    closeAllClients("service_destroyed")
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
        val snapTarget = element["snapTarget"]?.jsonPrimitive?.contentOrNull
        tap(inputId, x, y, snapTarget)
      }
      "key" -> {
        val key = element["key"]?.jsonPrimitive?.contentOrNull ?: return
        val inputId = element["inputId"]?.jsonPrimitive?.contentOrNull
        handleRemoteKey(inputId, key)
      }
      "generate_control_code" -> {
        val requestId = element["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val digits = element["digits"]?.jsonPrimitive?.contentOrNull.orEmpty()
        handleGenerateControlCode(requestId, digits)
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

  private suspend fun startTicketSession(): TicketSessionResponse {
    cleanupInactiveClientsIfNeeded("session_start_preflight")
    return withTimeoutOrNull(SESSION_START_TIMEOUT_MILLIS) {
      sessionMutex.withLock {
        cleanupInactiveClientsIfNeeded("session_start_locked")
        startTicketSessionLocked()
      }
    } ?: run {
      cleanupInactiveClientsIfNeeded("session_start_timeout")
      recordTicketEvent("session_start_timeout", "timeout_ms=$SESSION_START_TIMEOUT_MILLIS state=$ticketSessionState clients=${totalClientCount()}")
      TicketSessionResponse(
        ok = false,
        state = "start_timeout",
        message = "Ticket session start timed out; stale clients were cleared and the next request can retry"
      )
    }
  }

  private suspend fun startTicketSessionLocked(): TicketSessionResponse {
    if (!TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)) {
      return TicketSessionResponse(
        ok = false,
        state = "vivi_missing",
        message = "ViVi is not installed from a local Pixel app store yet"
      )
    }
    if (streamActive && activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
      val previousMode = activeCaptureMode
      val reason = "capture_mode_cutover_$previousMode"
      recordTicketEvent("session_capture_mode_cutover", "from=$previousMode to=$CAPTURE_MODE_ROOT_FFMPEG_H264")
      streamActive = false
      ffmpegCaptureVerified = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      resetFrameEpoch(reason, active = false)
      rootFfmpegH264CaptureEngine.stop(reason)
      rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
    }
    if (streamActive) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "session_start_already_active")
      lastSessionStopReason = null
      markViewerInput("session_start_already_active")
      lastMessage = "Ticket session is active through FFmpeg H.264 capture"
      scheduleTicketBrightnessGuard("session_start_already_active")
      startForegroundGuard()
      ensureEncoderIfPossible()
      broadcastStatus()
      persistRuntimeState("session_start_already_active")
      return TicketSessionResponse(ok = true, state = "active", message = lastMessage)
    }
    val sourceSize = currentDisplaySize()
    var ffmpegCapture = rootFfmpegH264CaptureEngine.snapshot()
    if (!ffmpegCapture.available) {
      rootFfmpegH264CaptureEngine.probe(sourceSize.first, sourceSize.second)
      ffmpegCapture = rootFfmpegH264CaptureEngine.snapshot()
    }
    val rootFfmpegCaptureAvailable = ffmpegCapture.available
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_requested")
    recordTicketEvent(
      "session_start_requested",
      "root_ffmpeg_h264_available=${ffmpegCapture.available} root_ffmpeg_h264_state=${ffmpegCapture.state} fast_path=root_ffmpeg_h264"
    )
    lastSessionStopReason = null
    if (!rootFfmpegCaptureAvailable) {
      fallbackReason = "Root FFmpeg H.264 capture is unavailable"
      cancelInactivityTimer()
      disableSecureWindowCaptureBypass()
      disableNotificationLockdown("capture_unavailable")
      scheduleTicketBrightnessGuard("capture_unavailable")
      releaseBlackoutOverlaySuppression()
      lastMessage = "Root FFmpeg H.264 capture is unavailable; stream was not started"
      activeCaptureMode = CAPTURE_MODE_IDLE
      ffmpegCaptureVerified = false
      updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, "capture_unavailable_root_ffmpeg_h264_required")
      recordTicketEvent("session_unavailable", fallbackReason.orEmpty())
      persistRuntimeState("capture_unavailable_root_ffmpeg_h264_required")
      return TicketSessionResponse(
        ok = false,
        state = "capture_unavailable",
        message = lastMessage
      )
    }
    fallbackReason = null
    streamActive = true
    ffmpegCaptureVerified = false
    activeCaptureMode = CAPTURE_MODE_ROOT_FFMPEG_H264
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_root_ffmpeg_h264_prepare")
    markViewerInput("session_start_root_ffmpeg_h264_prepare")
    lastMessage = "Preparing ViVi for secure H.264 capture"
    recordTicketEvent("session_started", "mode=$activeCaptureMode")
    startForegroundGuard()
    requestTicketScreenWake("session_start")
    enableNotificationLockdown("session_start")
    enableSecureWindowCaptureBypass()
    scheduleTicketBrightnessGuard("session_start")
    scheduleRootFfmpegH264CaptureStart("session_start_root_ffmpeg_h264_capture", suppressBlackout = false)
    serviceScope.launch {
      rememberTicketBrightnessState()
      suppressBlackoutOverlayForRemote()
      broadcastStatus()
    }
    broadcastStatus()
    persistRuntimeState("session_start_root_ffmpeg_h264_prepare")
    return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
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
    if (ticketSessionOpen() || ticketSessionState in setOf(TICKET_SESSION_STARTING, TICKET_SESSION_LIVE, TICKET_SESSION_NEEDS_ATTENTION)) {
      updateTicketSessionState(TICKET_SESSION_CLIENT_DISCONNECTED, reason)
      lastMessage = "Browser disconnected; ticket session is waiting to reconnect"
      streamActive = false
      ffmpegCaptureVerified = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      rootH264BlankProbeJob?.cancel()
      rootH264BlankProbeJob = null
      resetFrameEpoch("client_detached_$reason", active = false)
      cancelInactivityTimer()
      cancelForegroundGuard()
      rootFfmpegH264CaptureEngine.stop(reason)
      rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
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
    val response = sessionMutex.withLock {
      clientDisconnectStopJob?.cancel()
      clientDisconnectStopJob = null
      startupDisconnectGraceUntilMillis = 0L
      streamActive = false
      ffmpegCaptureVerified = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      rootH264BlankProbeJob?.cancel()
      rootH264BlankProbeJob = null
      updateTicketSessionState(TICKET_SESSION_STOPPED, "session_stop_$reason")
      resetFrameEpoch("session_stop_$reason", active = false)
      cancelInactivityTimer()
      cancelForegroundGuard()
      rootFfmpegH264CaptureEngine.stop(reason)
      rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
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
    if (ticketServiceEnabled()) recordTicketEvent("root_ffmpeg_h264_ready_waiting", "session_stop_$reason")
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
    return streamActive
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
    clearControlCodePopupSurfaceCache()
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
        normalized.contains("systemui")
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
        lastMessage = "Ticket session is active through FFmpeg H.264 capture"
        broadcastStatus()
      }
      return
    }
    if (!result.success) {
      Log.w(TAG, "ticket_autopilot_active_failed reason=$reason state=${result.state} step=${result.step}")
      recordTicketEvent("active_guard_failed", "${result.state}:${result.step}")
      if (controlSensitiveWindowActive()) {
        recordTicketEvent("active_guard_recovery_deferred", "state=$ticketSessionState")
        return
      }
      scheduleTicketRecovery("active_guard_${result.state.name.lowercase()}", TicketRecoveryMode.ACTIVE_SOFT)
    }
  }

  private suspend fun dumpViviHierarchy(
    fresh: Boolean = false,
    timeoutMillis: Long? = null
  ): RootResult {
    return TicketScreenObserver.dumpViviHierarchy(
      rootExecutor = rootExecutor,
      reason = "service",
      forceFresh = fresh,
      timeoutMillis = timeoutMillis ?: TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS
    )
  }

  private suspend fun controlCodeRequestHierarchy(): String? {
    val accessibilityXml = TicketViviPageEnforcer.hierarchyForVisibleNodes(
      PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)
    )
    if (accessibilityXml.isNotBlank()) {
      return accessibilityXml
    }
    val dump = dumpViviHierarchy(fresh = true, timeoutMillis = CONTROL_CODE_REQUEST_HIERARCHY_TIMEOUT_MILLIS)
    return dump.stdout.takeIf { dump.ok && it.isNotBlank() }
  }

  private fun rememberControlCodePopupSurface(surface: TicketViviControlCodePopupSurface) {
    controlCodePopupSurfaceCache = surface
    controlCodePopupSurfaceCachedAtMillis = SystemClock.elapsedRealtime()
  }

  private fun cachedControlCodePopupSurface(): TicketViviControlCodePopupSurface? {
    val surface = controlCodePopupSurfaceCache ?: return null
    val ageMillis = SystemClock.elapsedRealtime() - controlCodePopupSurfaceCachedAtMillis
    return if (ageMillis in 0..CONTROL_CODE_POPUP_READY_CACHE_MILLIS) {
      surface
    } else {
      clearControlCodePopupSurfaceCache()
      null
    }
  }

  private fun clearControlCodePopupSurfaceCache() {
    controlCodePopupSurfaceCache = null
    controlCodePopupSurfaceCachedAtMillis = 0L
  }

  private suspend fun controlExitHierarchy(): String? {
    val accessibilityXml = TicketViviPageEnforcer.hierarchyForVisibleNodes(
      PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)
    )
    val accessibilityState = TicketViviPageEnforcer.classifyForRecovery(accessibilityXml)
    if (accessibilityState.controlExitHierarchyIsAuthoritative()) {
      return accessibilityXml
    }
    val dump = dumpViviHierarchy(fresh = true, timeoutMillis = CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS)
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
    if (activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264 || !ffmpegCaptureVerified) {
      return
    }
    ensureRootFfmpegH264CaptureIfPossible()
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

  private fun handleRootFfmpegH264CaptureStateChanged(health: TicketFfmpegHealth) {
    ffmpegCaptureSnapshot = health
    if (
      streamActive &&
      activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264 &&
      health.frames == 0L &&
      health.suppressedRawFrames >= ROOT_FFMPEG_RAW_FRAME_SUPPRESSION_FAILURE_THRESHOLD
    ) {
      serviceScope.launch {
        failRootFfmpegH264Capture("secure_capture_blocked_raw_frames")
      }
    }
    if (streamActive) {
      broadcastStatus()
      persistRuntimeState("root_ffmpeg_h264_capture_state")
    }
  }

  private suspend fun failRootFfmpegH264Capture(reason: String) = sessionMutex.withLock {
    val health = ffmpegCaptureSnapshot
    if (
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264 ||
      health.frames > 0L ||
      health.suppressedRawFrames < ROOT_FFMPEG_RAW_FRAME_SUPPRESSION_FAILURE_THRESHOLD
    ) {
      return@withLock
    }
    rootFfmpegH264CaptureEngine.stop(reason)
    rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
    streamActive = false
    ffmpegCaptureVerified = false
    activeCaptureMode = CAPTURE_MODE_IDLE
    resetFrameEpoch(reason, active = false)
    updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_blocked")
    lastMessage = "ViVi is protected from capture; stream was not started"
    lastRootH264BlankProbeResult = "secure_capture_blocked"
    recordTicketEvent("secure_capture_blocked", reason)
    broadcastStatus()
    persistRuntimeState(reason)
  }

  private fun restartActiveStream(reason: String) {
    if (!streamActive) {
      return
    }
    recordTicketEvent("stream_restart_requested", reason)
    resetFrameEpoch("root_ffmpeg_h264_fresh_request_$reason", active = true)
    streamSize?.let(::broadcastConfig)
    rootFfmpegH264CaptureEngine.requestKeyFrame("restart_stream:$reason")
    ensureEncoderIfPossible()
    broadcastStatus()
  }

  private fun configMessage(size: TicketStreamSize): String {
    val codec = TicketScreenConfig.ROOT_FFMPEG_H264_CODEC_STRING
    val transport = TicketScreenConfig.ROOT_FFMPEG_H264_TRANSPORT
    val qualityProfile = TicketScreenConfig.ROOT_FFMPEG_H264_QUALITY_PROFILE
    return """
      {"type":"config","serverVersion":"$SERVER_VERSION","codec":"$codec","transport":"$transport","captureMode":"$CAPTURE_MODE_ROOT_FFMPEG_H264","captureSource":${json.encodeToString(ffmpegCaptureSnapshot.captureSource)},"captureMethod":${json.encodeToString(ffmpegCaptureSnapshot.captureMethod)},"rootCapture":true,"frameEnvelope":"$FRAME_ENVELOPE_VERSION","streamEpoch":$streamEpoch,"qualityProfile":"$qualityProfile","width":${size.width},"height":${size.height},"bitrate":${TicketScreenConfig.ROOT_FFMPEG_H264_BITRATE},"fps":${TicketScreenConfig.ROOT_FFMPEG_H264_FPS},"keyFrameIntervalMillis":${TicketScreenConfig.ROOT_FFMPEG_H264_KEYFRAME_INTERVAL_MILLIS},"ffmpegVersion":${json.encodeToString(ffmpegCaptureSnapshot.version ?: "")}}
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
    sendCachedKeyFrameOrRequest(client, "video_client_warm_start")
  }

  private fun sendCachedKeyFrameOrRequest(client: TicketWebSocket, reason: String = "video_client_request"): Boolean {
    if (activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
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

  private fun handleRootFfmpegH264CaptureFrame(frame: TicketRootCaptureFrame) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
      return
    }
    val firstEncodedFrame = encodedFrames == 0L
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
    if (firstEncodedFrame || ticketSessionState == TICKET_SESSION_STARTING) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_ffmpeg_h264_first_visible_frame")
      lastMessage = "Ticket session is active through FFmpeg H.264 capture"
      broadcastStatus()
      persistRuntimeState("root_ffmpeg_h264_first_visible_frame")
    }
    if (frame.keyFrame || encodedFrames <= SECURE_CAPTURE_PROBE_START_FRAME_COUNT) {
      scheduleRootFfmpegSecureCaptureProbe("root_ffmpeg_h264_frame")
    }
  }

  private fun scheduleRootFfmpegSecureCaptureProbe(reason: String) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
      return
    }
    val nowMillis = SystemClock.elapsedRealtime()
    if (rootH264BlankProbeJob?.isActive == true) {
      return
    }
    val lastProbeAge = ageMillis(lastRootH264BlankProbeAtMillis, nowMillis)
    if (lastProbeAge != null && lastProbeAge < SECURE_CAPTURE_PROBE_MIN_INTERVAL_MILLIS) {
      return
    }
    rootH264BlankProbeJob = serviceScope.launch {
      delay(SECURE_CAPTURE_PROBE_DELAY_MILLIS)
      if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
        rootH264BlankProbeJob = null
        return@launch
      }
      lastRootH264BlankProbeAtMillis = SystemClock.elapsedRealtime()
      val visible = verifyRootFfmpegSecureCapturePixelsVisible(reason)
      if (visible) {
        lastRootH264BlankProbeResult = "visible"
        rootH264BlankProbeJob = null
        broadcastStatus()
        return@launch
      }
      rootH264BlankProbeFailures += 1L
      lastRootH264BlankProbeResult = "secure_capture_blocked"
      rootFfmpegH264CaptureEngine.stop("secure_capture_blocked:$reason")
      rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
      streamActive = false
      ffmpegCaptureVerified = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      resetFrameEpoch("secure_capture_blocked:$reason", active = false)
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_blocked")
      lastMessage = "ViVi is protected from capture; stream was stopped"
      recordTicketEvent("secure_capture_blocked", reason)
      rootH264BlankProbeJob = null
      broadcastStatus()
      persistRuntimeState("secure_capture_blocked")
    }
  }

  private fun requestKeyFrame(reason: String = "browser_request") {
    val nowMillis = SystemClock.elapsedRealtime()
    lastKeyFrameRequestedAtMillis = nowMillis
    keyFrameRequests += 1
    if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) {
      rootFfmpegH264CaptureEngine.requestKeyFrame(reason)
    }
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

  private fun cleanupInactiveClientsIfNeeded(reason: String) {
    if (streamActive || ticketSessionState == TICKET_SESSION_STARTING || totalClientCount() == 0) {
      return
    }
    closeAllClients("inactive_stream_$reason")
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
    val ffmpeg = rootFfmpegH264CaptureEngine.snapshot(nowMillis)
    val activeRootCapture = TicketRootCaptureHealth(
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
      lastFrameSentAtMillis = 0L
      lastKeyFrameEncodedAtMillis = 0L
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
    val ffmpegCapture = rootFfmpegH264CaptureEngine.snapshot(nowMillis)
    return TicketStreamPipeline(
      controlClients = controlClients.size,
      videoClients = videoClients.size,
      captureMode = activeCaptureMode,
      codec = if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) TicketScreenConfig.ROOT_FFMPEG_H264_CODEC_STRING else "",
      transport = if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) TicketScreenConfig.ROOT_FFMPEG_H264_TRANSPORT else "",
      frameEnvelope = FRAME_ENVELOPE_VERSION,
      streamEpoch = streamEpoch,
      frameSequence = frameSequence,
      lastKeyFrameSequence = latestKeyFrameSequence,
      qualityProfile = if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) {
        TicketScreenConfig.ROOT_FFMPEG_H264_QUALITY_PROFILE
      } else {
        "idle"
      },
      configuredWidth = streamSize?.width,
      configuredHeight = streamSize?.height,
      configuredBitrate = if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) TicketScreenConfig.ROOT_FFMPEG_H264_BITRATE else null,
      lastFrameBytes = lastFrameBytes,
      lastKeyFrameBytes = lastKeyFrameBytes,
      estimatedSendBitrate = estimatedSendBitrate,
      freshKeyFrameCacheMaxAgeMillis = ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS,
      encoderRunning = ffmpegCapture.active,
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
      secureWindowCaptureBypassMessage = secureWindowCaptureBypassMessage,
      rootH264BlankProbeResult = lastRootH264BlankProbeResult,
      rootH264BlankProbeRecoveries = rootH264BlankProbeRecoveries,
      rootH264BlankProbeFailures = rootH264BlankProbeFailures,
      lastRootH264BlankProbeAgoMillis = ageMillis(lastRootH264BlankProbeAtMillis, nowMillis)
    )
  }

  private fun streamVerdict(ffmpegCapture: TicketFfmpegHealth, nowMillis: Long): String {
    if (!streamActive) {
      return when (ticketSessionState) {
        TICKET_SESSION_NEEDS_ATTENTION -> "needs_attention"
        TICKET_SESSION_UNAVAILABLE -> "capture_blocked"
        else -> "idle"
      }
    }
    if (ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION || lastRootH264BlankProbeResult == "secure_capture_blocked") {
      return "capture_blocked"
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264 && ffmpegCapture.frames == 0L && ffmpegCapture.suppressedRawFrames > 0L) {
      return "capture_blocked"
    }
    if (lastFrameSentAtMillis > 0L && ageMillis(lastFrameSentAtMillis, nowMillis)?.let { it <= 2_500L } == true) {
      return "live"
    }
    return when (ticketSessionState) {
      TICKET_SESSION_STARTING -> "preparing_phone"
      TICKET_SESSION_SOFT_RECOVERY -> "stale_recovering"
      else -> "waiting_keyframe"
    }
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
    cleanupInactiveClientsIfNeeded("health")
    val nowMillis = SystemClock.elapsedRealtime()
    val installedStores = TicketPackageSupport.installedLocalStores(this)
    val ffmpegCapture = rootFfmpegH264CaptureEngine.snapshot(nowMillis)
    val vivi = TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)
    val ok = running.get() && vivi && ffmpegCapture.available
    val visibleFrameCodec = if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) {
      TicketScreenConfig.ROOT_FFMPEG_H264_CODEC_STRING
    } else {
      ""
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
      viviInstalled = vivi,
      accrescentInstalled = TicketScreenConfig.ACCRESCENT_PACKAGE in installedStores,
      installedStorePackages = installedStores,
      streamActive = streamActive,
      streamVerdict = streamVerdict(ffmpegCapture, nowMillis),
      clients = totalClientCount(),
      inactivityActive = ticketSessionOpen(),
      inactivityTimeoutMillis = TicketInactivityPolicy.TIMEOUT_MILLIS,
      inactivityRemainingMillis = inactivityRemainingMillis(nowMillis),
      autoStartAllowed = TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(lastSessionStopReason),
      autoStartBlockedReason = lastSessionStopReason?.takeUnless {
        TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(it)
      },
      serviceReadiness = serviceReadinessSnapshot(),
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
      controlCodeSnap = TicketControlCodeSnapHealth(
        lastRawX = lastControlCodeSnapRawX,
        lastRawY = lastControlCodeSnapRawY,
        lastCandidateZone = lastControlCodeSnapCandidateZone,
        lastDetectedButtonBounds = lastControlCodeSnapDetectedButtonBounds,
        lastSnapTarget = lastControlCodeSnapTarget,
        lastAccepted = lastControlCodeSnapAccepted,
        lastReason = lastControlCodeSnapReason,
        lastFinalX = lastControlCodeSnapFinalX,
        lastFinalY = lastControlCodeSnapFinalY,
        lastCompletedAgoMillis = ageMillis(lastControlCodeSnapCompletedAtMillis, nowMillis)
      ),
      controlCodeMode = TicketControlCodeModeHealth(
        active = controlCodeModeActive && streamActive,
        entryId = controlCodeModeEntryId,
        enteredAgoMillis = ageMillis(controlCodeModeEnteredAtMillis, nowMillis),
        transitionGraceActive = nowMillis < controlCodeTransitionGraceUntilMillis,
        transitionGraceRemainingMillis = (controlCodeTransitionGraceUntilMillis - nowMillis).coerceAtLeast(0L)
      ),
      controlCodeRequest = TicketControlCodeRequestHealth(
        requestId = lastControlCodeRequestId,
        status = lastControlCodeRequestStatus,
        reason = lastControlCodeRequestReason,
        value = lastControlCodeRequestValue,
        totalDurationMillis = lastControlCodeRequestDurationMillis,
        completedAgoMillis = ageMillis(lastControlCodeRequestCompletedAtMillis, nowMillis),
        duplicateResults = duplicateControlCodeResultCount,
        lastDuplicateRequestId = lastDuplicateControlCodeRequestId,
        lastDuplicateAgoMillis = ageMillis(lastDuplicateControlCodeResultAtMillis, nowMillis)
      ),
      controlExitCleanup = TicketControlExitCleanupHealth(
        lastReason = lastControlExitCleanupReason,
        lastDetectedState = lastControlExitCleanupDetectedState,
        lastCloseAction = lastControlExitCleanupCloseAction,
        lastDetectorSource = lastControlExitCleanupDetectorSource,
        lastSurfaceProbeResult = lastControlExitCleanupSurfaceProbeResult,
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
        activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264 && !ffmpegCapture.available -> ffmpegCapture.message
        ffmpegCapture.available -> lastMessage
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

  private suspend fun prepareViviForRootFfmpegH264Capture(reason: String): TicketAutopilotResult {
    val result = ticketAutopilot.driveToTicketDetail(
      reason = reason,
      forceFreshLaunch = false,
      allowControlCodePopup = true,
      restartPolicy = TicketAutopilotRestartPolicy.SOFT_RECOVERY,
      maxSteps = ROOT_FFMPEG_H264_START_AUTOPILOT_MAX_STEPS,
      timeoutMillis = ROOT_FFMPEG_H264_START_AUTOPILOT_TIMEOUT_MILLIS
    )
    recordTicketEvent("root_ffmpeg_h264_vivi_prepare", "${result.state}:${result.step}:success=${result.success}")
    if (result.success) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_ffmpeg_h264_vivi_ready_$reason")
    } else {
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "root_ffmpeg_h264_vivi_prepare_${result.state.name.lowercase()}")
    }
    return result
  }

  private fun scheduleRootFfmpegH264CaptureStart(reason: String, suppressBlackout: Boolean) {
    serviceScope.launch {
      if (suppressBlackout) {
        suppressBlackoutOverlayForRemote()
      }
      val modeName = "root_ffmpeg_h264"
      updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_prepare_$reason")
      launchViviUnlessFastTicketDetail(reason)
      delay(PRE_CAPTURE_APP_SETTLE_MILLIS)
      val prepareResult = prepareViviForRootFfmpegH264Capture(reason)
      if (!streamActive || activeCaptureMode == CAPTURE_MODE_IDLE) {
        recordTicketEvent("${modeName}_prepare_ignored", "session_inactive:$reason")
        broadcastStatus()
        return@launch
      }
      if (activeCaptureMode == CAPTURE_MODE_ROOT_FFMPEG_H264) {
        val verified = verifyRootFfmpegSecureCaptureVisible(reason)
        if (!verified) {
          rootFfmpegH264CaptureEngine.stop("secure_capture_blocked:$reason")
          rootFfmpegH264CaptureEngine.cleanupStaleProcesses()
          streamActive = false
          ffmpegCaptureVerified = false
          activeCaptureMode = CAPTURE_MODE_IDLE
          resetFrameEpoch("secure_capture_blocked:$reason", active = false)
          updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_blocked")
          lastMessage = "ViVi is protected from capture; stream was not started"
          recordTicketEvent("secure_capture_blocked", reason)
          broadcastStatus()
          persistRuntimeState("secure_capture_blocked")
          return@launch
        }
      }
      if (prepareResult.success) {
        ffmpegCaptureVerified = true
        ensureEncoderIfPossible()
        requestKeyFrame("vivi_ready:$reason")
        updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_waiting_first_visible_frame")
        lastMessage = "Waiting for the first visible FFmpeg H.264 frame"
      } else {
        lastMessage = "Ticket stream needs attention before capture can start"
      }
      broadcastStatus()
    }
  }

  private suspend fun verifyRootFfmpegSecureCaptureVisible(reason: String): Boolean {
    if (!verifyViviTicketDetailHierarchy(reason)) {
      return false
    }
    return verifyRootFfmpegSecureCapturePixelsVisible(reason)
  }

  private suspend fun verifyRootFfmpegSecureCapturePixelsVisible(reason: String): Boolean {
    val sourceSize = currentDisplaySize()
    val probeWidth = 180
    val probeHeight = ((sourceSize.second.toDouble() / sourceSize.first.toDouble()) * probeWidth)
      .roundToInt()
      .coerceAtLeast(2)
      .let { if (it % 2 == 0) it else it + 1 }
    val frame = captureRootSurfaceProbeRaw(probeWidth, probeHeight)
    if (frame == null) {
      recordTicketEvent("secure_capture_probe_failed", reason)
      return false
    }
    val visible = rootSurfaceRawFrameLooksVisible(frame)
    recordTicketEvent("secure_capture_probe", "visible=$visible bytes=${frame.size} width=$probeWidth height=$probeHeight")
    return visible
  }

  private suspend fun verifyViviTicketDetailHierarchy(reason: String): Boolean {
    val accessibilityNodes = PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)
    if (accessibilityNodes.isNotEmpty()) {
      val accessibilityXml = TicketViviPageEnforcer.hierarchyForVisibleNodes(accessibilityNodes)
      val state = TicketViviPageEnforcer.classifyForRecovery(accessibilityXml)
      if (state == TicketViviRecoveryState.TICKET_DETAIL) {
        viviStateMemory.record(
          state = state,
          ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(accessibilityXml),
          source = "accessibility",
          reason = "secure_capture_verify:$reason"
        )
        return true
      }
      recordTicketEvent("secure_capture_verify_not_ticket_detail", "accessibility:${state.name}")
    }
    if (recentTicketDetailForSecureCapture(reason)) {
      return true
    }
    val dump = dumpViviHierarchy(fresh = true)
    val xml = dump.stdout.takeIf { dump.ok && it.isNotBlank() }
    if (xml.isNullOrBlank()) {
      recordTicketEvent("secure_capture_verify_hierarchy_unavailable", dump.stderr.takeLast(160))
      return false
    }
    val state = TicketViviPageEnforcer.classifyForRecovery(xml)
    viviStateMemory.record(
      state = state,
      ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(xml),
      source = "root",
      reason = "secure_capture_verify:$reason"
    )
    if (state != TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent("secure_capture_verify_not_ticket_detail", "root:${state.name}")
      return false
    }
    return true
  }

  private fun recentTicketDetailForSecureCapture(reason: String): Boolean {
    val current = viviStateMemory.current()
    val nowMillis = SystemClock.elapsedRealtime()
    val currentAge = ageMillis(current.observedAtMillis, nowMillis)
    if (
      currentAge != null &&
      currentAge <= SECURE_CAPTURE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS &&
      current.state != TicketViviRecoveryState.TICKET_DETAIL &&
      current.state != TicketViviRecoveryState.UNKNOWN_VIVI
    ) {
      recordTicketEvent("secure_capture_verify_not_ticket_detail", "memory:${current.state.name}")
      return false
    }
    val recent = viviStateMemory.recentTicketDetailWithin(SECURE_CAPTURE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS)
      ?: return false
    recordTicketEvent(
      "secure_capture_verify_recent_ticket_detail",
      "source=${recent.source} reason=$reason age_ms=${SystemClock.elapsedRealtime() - recent.observedAtMillis}"
    )
    return true
  }

  private suspend fun captureRootSurfaceProbeRaw(width: Int, height: Int): ByteArray? = withContext(Dispatchers.IO) {
    val command = """
      APK=${'$'}(pm path lv.jolkins.pixelorchestrator 2>/dev/null | sed -n 's/^package://p' | head -n 1)
      test -n "${'$'}APK" &&
      CLASSPATH="${'$'}APK" app_process /system/bin lv.jolkins.pixelorchestrator.app.ticket.TicketRootSurfaceCaptureMain --width $width --height $height --fps 1 --frames 1
    """.trimIndent()
    val process = ProcessBuilder("su", "-c", command)
      .redirectErrorStream(false)
      .start()
    val payload = withTimeoutOrNull(3_000L) {
      BufferedInputStream(process.inputStream).use { it.readBytes() }
    }
    val exitCode = withTimeoutOrNull(SECURE_CAPTURE_PROBE_EXIT_TIMEOUT_MILLIS) {
      process.waitFor()
    }
    if (payload == null || exitCode == null) {
      runCatching { process.destroyForcibly() }
      return@withContext null
    }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
    if (exitCode != 0 || payload.size < 12 + width * height * 4) {
      if (stderr.isNotBlank()) {
        Log.w(TAG, "ticket_root_surface_probe_stderr $stderr")
      }
      return@withContext null
    }
    val header = ByteBuffer.wrap(payload, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
    val actualWidth = header.int
    val actualHeight = header.int
    val format = header.int
    if (actualWidth != width || actualHeight != height || format != 1) {
      Log.w(TAG, "ticket_root_surface_probe_bad_header width=$actualWidth height=$actualHeight format=$format")
      return@withContext null
    }
    payload
  }

  private suspend fun captureRootScreencapProbeRaw(): ByteArray? = withContext(Dispatchers.IO) {
    val process = ProcessBuilder("su", "-c", "screencap")
      .redirectErrorStream(false)
      .start()
    val payload = withTimeoutOrNull(CONTROL_EXIT_FAST_SCREENCAP_TIMEOUT_MILLIS) {
      BufferedInputStream(process.inputStream).use { it.readBytes() }
    }
    val exitCode = withTimeoutOrNull(SECURE_CAPTURE_PROBE_EXIT_TIMEOUT_MILLIS) {
      process.waitFor()
    }
    if (payload == null || exitCode == null) {
      runCatching { process.destroyForcibly() }
      return@withContext null
    }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
    if (exitCode != 0 || payload.size < 12) {
      if (stderr.isNotBlank()) {
        Log.w(TAG, "ticket_root_screencap_probe_stderr $stderr")
      }
      return@withContext null
    }
    val header = ByteBuffer.wrap(payload, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
    val actualWidth = header.int
    val actualHeight = header.int
    val format = header.int
    if (actualWidth <= 0 || actualHeight <= 0 || payload.size < 12 + actualWidth * actualHeight * 4) {
      Log.w(TAG, "ticket_root_screencap_probe_bad_frame width=$actualWidth height=$actualHeight format=$format bytes=${payload.size}")
      return@withContext null
    }
    payload
  }

  private fun rootSurfaceRawFrameLooksVisible(frame: ByteArray): Boolean {
    if (frame.size <= 12) {
      return false
    }
    val header = ByteBuffer.wrap(frame, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
    val width = header.int.coerceAtLeast(1)
    val height = header.int.coerceAtLeast(1)
    val pixelOffset = 12
    val stride = (width * height / 2400).coerceAtLeast(1)
    var sampled = 0
    var bright = 0
    var luminanceSum = 0L
    var pixel = 0
    while (pixel < width * height) {
      val offset = pixelOffset + pixel * 4
      if (offset + 2 >= frame.size) break
      val red = frame[offset].toInt() and 0xff
      val green = frame[offset + 1].toInt() and 0xff
      val blue = frame[offset + 2].toInt() and 0xff
      val luminance = (red * 299 + green * 587 + blue * 114) / 1000
      luminanceSum += luminance.toLong()
      if (luminance > 40) {
        bright += 1
      }
      sampled += 1
      pixel += stride
    }
    if (sampled == 0) {
      return false
    }
    val mean = luminanceSum.toDouble() / sampled.toDouble()
    val brightRatio = bright.toDouble() / sampled.toDouble()
    return brightRatio >= 0.08 || mean >= 35.0
  }

  private suspend fun alignControlExitTicketDetailWithSurface(
    state: TicketViviRecoveryState,
    reason: String,
    detectorSource: String
  ): TicketViviRecoveryState {
    if (
      state == TicketViviRecoveryState.CONTROL_CODE_POPUP ||
      state == TicketViviRecoveryState.CONTROL_CODE_RESULT
    ) {
      val directCloseAge = ageMillis(lastControlExitDirectCloseAtMillis, SystemClock.elapsedRealtime())
      if (directCloseAge != null && directCloseAge <= CONTROL_EXIT_RECENT_DIRECT_CLOSE_MILLIS) {
        val probed = controlExitGeneratedResultSurfaceProbe(reason, "${detectorSource}_post_direct_close")
        if (probed == TicketViviRecoveryState.TICKET_DETAIL) {
          recordTicketEvent("control_exit_surface_clean_after_direct_close", "${detectorSource}:${state.name}")
          return TicketViviRecoveryState.TICKET_DETAIL
        }
        if (probed == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
          rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
          recordTicketEvent("control_exit_surface_dirty", "$detectorSource generated_result_visible_after_direct_close")
          return TicketViviRecoveryState.CONTROL_CODE_RESULT
        }
      }
    }
    if (state != TicketViviRecoveryState.TICKET_DETAIL) {
      lastControlExitCleanupDetectorSource = detectorSource
      lastControlExitCleanupSurfaceProbeResult = "not_required:${state.name}"
      return state
    }
    if (!controlExitCleanNeedsSurfaceProof()) {
      lastControlExitCleanupDetectorSource = detectorSource
      lastControlExitCleanupSurfaceProbeResult = "not_required"
      return state
    }
    val probed = controlExitGeneratedResultSurfaceProbe(reason, detectorSource)
    if (probed == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
      recordTicketEvent("control_exit_surface_dirty", "$detectorSource generated_result_visible")
      return TicketViviRecoveryState.CONTROL_CODE_RESULT
    }
    if (probed == null && controlCodeSurfaceMemoryState() == TicketViviRecoveryState.CONTROL_CODE_RESULT.name) {
      recordTicketEvent("control_exit_surface_dirty", "$detectorSource probe_unavailable_recent_result")
      return TicketViviRecoveryState.CONTROL_CODE_RESULT
    }
    return state
  }

  private fun controlExitCleanNeedsSurfaceProof(): Boolean {
    return controlCodeSurfaceMemoryState() != null ||
      ticketSessionState == TICKET_SESSION_CONTROL_EXIT ||
      SystemClock.elapsedRealtime() < controlExitPopupLikelyUntilMillis
  }

  private suspend fun controlExitGeneratedResultSurfaceProbe(
    reason: String,
    detectorSource: String,
    allowCached: Boolean = true
  ): TicketViviRecoveryState? {
    val now = SystemClock.elapsedRealtime()
    val cachedAge = ageMillis(lastControlExitSurfaceProbeAtMillis, now)
    if (
      allowCached &&
      lastControlExitSurfaceProbeReason == reason &&
      cachedAge != null &&
      cachedAge <= CONTROL_EXIT_SURFACE_PROBE_CACHE_MILLIS
    ) {
      val cachedState = lastControlExitSurfaceProbeState
      lastControlExitCleanupDetectorSource = detectorSource
      lastControlExitCleanupSurfaceProbeResult = cachedState?.name ?: "unavailable_cached"
      return cachedState
    }
    val sourceSize = currentDisplaySize()
    val probeWidth = CONTROL_EXIT_SURFACE_PROBE_WIDTH
    val probeHeight = ((sourceSize.second.toDouble() / sourceSize.first.toDouble()) * probeWidth)
      .roundToInt()
      .coerceAtLeast(2)
      .let { if (it % 2 == 0) it else it + 1 }
    val frame = captureRootSurfaceProbeRaw(probeWidth, probeHeight)
    val state = when {
      frame == null -> null
      rootSurfaceRawFrameLooksLikeGeneratedCodeResult(frame) -> TicketViviRecoveryState.CONTROL_CODE_RESULT
      else -> TicketViviRecoveryState.TICKET_DETAIL
    }
    lastControlExitSurfaceProbeAtMillis = SystemClock.elapsedRealtime()
    lastControlExitSurfaceProbeReason = reason
    lastControlExitSurfaceProbeState = state
    lastControlExitCleanupDetectorSource = detectorSource
    lastControlExitCleanupSurfaceProbeResult = state?.name ?: "unavailable"
    recordTicketEvent(
      "control_exit_surface_probe",
      "source=$detectorSource result=${state?.name ?: "unavailable"} reason=$reason"
    )
    return state
  }

  private suspend fun controlExitGeneratedResultFastScreencapProbe(
    reason: String,
    detectorSource: String
  ): TicketViviRecoveryState? {
    val frame = captureRootScreencapProbeRaw()
    val state = when {
      frame == null -> null
      rootSurfaceRawFrameLooksLikeGeneratedCodeResult(frame) -> TicketViviRecoveryState.CONTROL_CODE_RESULT
      else -> TicketViviRecoveryState.TICKET_DETAIL
    }
    lastControlExitSurfaceProbeAtMillis = SystemClock.elapsedRealtime()
    lastControlExitSurfaceProbeReason = reason
    lastControlExitSurfaceProbeState = state
    lastControlExitCleanupDetectorSource = detectorSource
    lastControlExitCleanupSurfaceProbeResult = state?.name ?: "unavailable"
    recordTicketEvent(
      "control_exit_surface_probe",
      "source=$detectorSource result=${state?.name ?: "unavailable"} reason=$reason"
    )
    return state
  }

  private fun rootSurfaceRawFrameLooksLikeGeneratedCodeResult(frame: ByteArray): Boolean {
    if (frame.size <= 12) {
      return false
    }
    val header = ByteBuffer.wrap(frame, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
    val width = header.int.coerceAtLeast(1)
    val height = header.int.coerceAtLeast(1)
    val top = (height * CONTROL_EXIT_RESULT_BAR_TOP_FRACTION).roundToInt().coerceIn(0, height - 1)
    val bottom = (height * CONTROL_EXIT_RESULT_BAR_BOTTOM_FRACTION).roundToInt().coerceIn(top + 1, height)
    val whole = rawFrameRegionStats(
      frame,
      width,
      height,
      (width * 0.12f).roundToInt(),
      top,
      (width * 0.92f).roundToInt(),
      bottom
    ) ?: return false
    val bandRanges = listOf(
      0.16f to 0.34f,
      0.40f to 0.62f,
      0.72f to 0.90f
    )
    val darkBands = bandRanges.count { (leftFraction, rightFraction) ->
      val band = rawFrameRegionStats(
        frame,
        width,
        height,
        (width * leftFraction).roundToInt(),
        top,
        (width * rightFraction).roundToInt(),
        bottom
      ) ?: return@count false
      band.darkRatio >= CONTROL_EXIT_RESULT_BAR_BAND_DARK_RATIO &&
        band.meanLuminance <= CONTROL_EXIT_RESULT_BAR_BAND_MAX_MEAN
    }
    return whole.darkRatio >= CONTROL_EXIT_RESULT_BAR_DARK_RATIO &&
      whole.meanLuminance <= CONTROL_EXIT_RESULT_BAR_MAX_MEAN &&
      darkBands >= 2
  }

  private fun rawFrameRegionStats(
    frame: ByteArray,
    width: Int,
    height: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int
  ): RawFrameRegionStats? {
    val safeLeft = left.coerceIn(0, width - 1)
    val safeTop = top.coerceIn(0, height - 1)
    val safeRight = right.coerceIn(safeLeft + 1, width)
    val safeBottom = bottom.coerceIn(safeTop + 1, height)
    val pixelOffset = 12
    val xStride = ((safeRight - safeLeft) / 32).coerceAtLeast(1)
    val yStride = ((safeBottom - safeTop) / 16).coerceAtLeast(1)
    var sampled = 0
    var dark = 0
    var luminanceSum = 0L
    var y = safeTop
    while (y < safeBottom) {
      var x = safeLeft
      while (x < safeRight) {
        val offset = pixelOffset + (y * width + x) * 4
        if (offset + 2 < frame.size) {
          val red = frame[offset].toInt() and 0xff
          val green = frame[offset + 1].toInt() and 0xff
          val blue = frame[offset + 2].toInt() and 0xff
          val luminance = (red * 299 + green * 587 + blue * 114) / 1000
          luminanceSum += luminance.toLong()
          if (luminance <= CONTROL_EXIT_RESULT_BAR_DARK_LUMINANCE) {
            dark += 1
          }
          sampled += 1
        }
        x += xStride
      }
      y += yStride
    }
    if (sampled == 0) {
      return null
    }
    return RawFrameRegionStats(
      sampled = sampled,
      meanLuminance = luminanceSum.toDouble() / sampled.toDouble(),
      darkRatio = dark.toDouble() / sampled.toDouble()
    )
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
    if (touchBrightnessOwnsTicketBrightness()) {
      brightnessGuardJob = null
      ticketBrightnessGuardActive = false
      ticketBrightnessGuardLastReason = reason
      ticketBrightnessGuardLastMessage = "Ticket brightness guard parked because touch brightness owns panel brightness"
      releaseTicketScreenAwake()
      serviceScope.launch {
        hideBlackoutOverlay()
      }
      return
    }
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
        val activeSession = streamActive
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
        val activeSession = streamActive
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
    if (touchBrightnessOwnsTicketBrightness()) {
      return false
    }
    if (streamActive) {
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

  private fun touchBrightnessOwnsTicketBrightness(): Boolean {
    return touchBrightnessSnapshot()?.touchBrightnessEnabled == true
  }

  private suspend fun enforceTicketSafeBrightness(reason: String) {
    if (touchBrightnessOwnsTicketBrightness()) {
      ticketBrightnessGuardActive = false
      ticketBrightnessGuardLastReason = reason
      ticketBrightnessGuardLastMessage = "Ticket brightness guard parked because touch brightness owns panel brightness"
      releaseTicketScreenAwake()
      hideBlackoutOverlay()
      return
    }
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
    if (touchBrightnessOwnsTicketBrightness()) {
      ticketBrightnessGuardActive = false
      ticketBrightnessGuardLastReason = reason
      ticketBrightnessGuardLastMessage = "Ticket brightness guard restore skipped because touch brightness owns panel brightness"
      releaseTicketScreenAwake()
      hideBlackoutOverlay()
      return
    }
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
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT) {
      recordInputGateDecision(allowed = false, reason = "remote_input_canceled_after_control_exit")
      recordTicketEvent("remote_input_canceled_after_control_exit", inputGateReason)
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

  private fun isTopLeftQuickClaimCoordinate(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    return encodedX in 0..(size.width * REMOTE_QUICK_CLAIM_MAX_X_FRACTION).roundToInt() &&
      encodedY in 0..(size.height * REMOTE_QUICK_CLAIM_MAX_Y_FRACTION).roundToInt()
  }

  private fun controlCodeSnapCandidateZone(size: TicketStreamSize, encodedX: Int, encodedY: Int): String? {
    return when {
      isTopLeftQuickClaimCoordinate(size, encodedX, encodedY) -> "top_left_quarter"
      isLikelyControlCodeCoordinateTap(size, encodedX, encodedY) -> "control_code_button_geometry"
      else -> null
    }
  }

  private fun controlCodeGeometryTarget(size: TicketStreamSize, candidateZone: String): TicketTapTarget {
    val encodedX = (size.width * ((VIVI_CONTROL_CODE_MIN_X_FRACTION + VIVI_CONTROL_CODE_MAX_X_FRACTION) / 2f)).roundToInt()
    val encodedY = (size.height * ((VIVI_CONTROL_CODE_MIN_Y_FRACTION + VIVI_CONTROL_CODE_MAX_Y_FRACTION) / 2f)).roundToInt()
    return TicketTapTarget(
      x = size.sourceX(encodedX),
      y = size.sourceY(encodedY),
      reason = "control_code_button_snap_geometry",
      candidateZone = candidateZone
    )
  }

  private suspend fun controlCodeButtonSnapTarget(size: TicketStreamSize, encodedX: Int, encodedY: Int): TicketTapTarget? {
    val candidateZone = controlCodeSnapCandidateZone(size, encodedX, encodedY)
    if (candidateZone == null) {
      recordControlCodeSnapAttempt(
        rawX = size.sourceX(encodedX),
        rawY = size.sourceY(encodedY),
        candidateZone = "outside_allowed_zones",
        snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
        accepted = false,
        reason = "control_code_snap_outside_allowed_zones"
      )
      recordInputGateDecision(allowed = false, reason = "control_code_snap_outside_allowed_zones")
      ignoreProtectedRemoteInput("control_code_snap_outside_allowed_zones")
      return null
    }
    val dump = dumpViviHierarchy(fresh = true)
    if (!dump.ok || dump.stdout.isBlank()) {
      val fallback = controlCodeSnapFallbackDecision()
      if (fallback.accepted) {
        return controlCodeGeometryTarget(size, candidateZone).copy(reason = fallback.reason)
      }
      recordControlCodeSnapAttempt(
        rawX = size.sourceX(encodedX),
        rawY = size.sourceY(encodedY),
        candidateZone = candidateZone,
        snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
        accepted = false,
        reason = fallback.reason
      )
      recordInputGateDecision(allowed = false, reason = fallback.reason)
      Log.w(TAG, "ticket_control_code_snap_failed ${fallback.reason} stderr=${dump.stderr}")
      scheduleTicketRecovery(fallback.reason, TicketRecoveryMode.ACTIVE_SOFT)
      return null
    }
    TicketViviPageEnforcer.controlCodeButtonActionForHierarchy(dump.stdout)?.let { action ->
      return TicketTapTarget(
        x = action.x,
        y = action.y,
        reason = action.reason,
        candidateZone = candidateZone,
        detectedButtonBounds = action.bounds
      )
    }
    val state = TicketViviPageEnforcer.classifyForRecovery(dump.stdout)
    viviStateMemory.record(
      state = state,
      ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(dump.stdout),
      source = "root",
      reason = "control_code_snap"
    )
    if (state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
      rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_POPUP)
      markControlCodeModeEntered("control_code_popup_already_open")
      return TicketTapTarget(
        x = 0,
        y = 0,
        reason = "control_code_popup_already_open",
        candidateZone = candidateZone,
        noOp = true
      )
    }
    if (state == TicketViviRecoveryState.TICKET_DETAIL) {
      return controlCodeGeometryTarget(size, candidateZone).copy(reason = "control_code_button_snap_ticket_detail_geometry")
    }
    recordControlCodeSnapAttempt(
      rawX = size.sourceX(encodedX),
      rawY = size.sourceY(encodedY),
      candidateZone = candidateZone,
      snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
      accepted = false,
      reason = "control_code_snap_not_ticket_detail:${state.name}"
    )
    recordInputGateDecision(allowed = false, reason = "control_code_snap_not_ticket_detail")
    Log.i(TAG, "ticket_control_code_snap_failed state=$state")
    scheduleTicketRecovery("control_code_snap_${state.name.lowercase()}", TicketRecoveryMode.ACTIVE_SOFT)
    return null
  }

  private fun controlCodeSnapFallbackDecision(): ControlCodeSnapFallbackDecision {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
      return ControlCodeSnapFallbackDecision(false, "control_code_snap_stream_inactive")
    }
    if (ticketSessionState != TICKET_SESSION_LIVE) {
      return ControlCodeSnapFallbackDecision(false, "control_code_snap_ticket_state_stale:$ticketSessionState")
    }
    if (lastRootH264BlankProbeResult != "visible") {
      return ControlCodeSnapFallbackDecision(false, "control_code_snap_secure_probe_not_visible")
    }
    val current = viviStateMemory.current()
    val nowMillis = SystemClock.elapsedRealtime()
    val currentAge = ageMillis(current.observedAtMillis, nowMillis)
    if (
      currentAge != null &&
      currentAge <= CONTROL_CODE_SNAP_UNSAFE_STATE_MEMORY_MAX_AGE_MILLIS &&
      current.state != TicketViviRecoveryState.TICKET_DETAIL
    ) {
      return ControlCodeSnapFallbackDecision(false, "control_code_snap_recent_state_${current.state.name.lowercase()}")
    }
    if (current.state == TicketViviRecoveryState.TICKET_DETAIL && currentAge != null && currentAge <= CONTROL_CODE_SNAP_MEMORY_MAX_AGE_MILLIS) {
      return ControlCodeSnapFallbackDecision(true, "control_code_button_snap_recent_ticket_detail")
    }
    if (viviStateMemory.recentTicketDetailWithin(CONTROL_CODE_SNAP_MEMORY_MAX_AGE_MILLIS) != null) {
      return ControlCodeSnapFallbackDecision(true, "control_code_button_snap_recent_ticket_detail")
    }
    return ControlCodeSnapFallbackDecision(true, "control_code_button_snap_live_ticket_stream_geometry")
  }

  private fun recordControlCodeSnapAttempt(
    rawX: Int,
    rawY: Int,
    candidateZone: String?,
    snapTarget: String,
    accepted: Boolean,
    reason: String,
    finalX: Int? = null,
    finalY: Int? = null,
    detectedButtonBounds: String? = null
  ) {
    lastControlCodeSnapRawX = rawX
    lastControlCodeSnapRawY = rawY
    lastControlCodeSnapCandidateZone = candidateZone
    lastControlCodeSnapDetectedButtonBounds = detectedButtonBounds
    lastControlCodeSnapTarget = snapTarget
    lastControlCodeSnapAccepted = accepted
    lastControlCodeSnapReason = reason
    lastControlCodeSnapFinalX = finalX
    lastControlCodeSnapFinalY = finalY
    lastControlCodeSnapCompletedAtMillis = SystemClock.elapsedRealtime()
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
    val surfaceState = if (reason.contains("result", ignoreCase = true)) {
      TicketViviRecoveryState.CONTROL_CODE_RESULT
    } else {
      TicketViviRecoveryState.CONTROL_CODE_POPUP
    }
    rememberControlCodeSurface(surfaceState)
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

  private fun resetControlCodeMode(
    reason: String,
    broadcast: Boolean = true,
    scheduleSoftCheck: Boolean = true
  ) {
    if (!controlCodeModeActive && controlCodeModeEnteredAtMillis == 0L) {
      controlCodePopupReadyUntilMillis = 0L
      clearControlCodePopupSurfaceCache()
      return
    }
    controlCodeModeActive = false
    controlCodeModeEnteredAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    clearControlCodePopupSurfaceCache()
    if (streamActive && !reason.startsWith("session_stop_") && reason != "foreground_guard_cancelled") {
      updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)
    }
    Log.i(TAG, "ticket_control_code_mode_reset reason=$reason entry=$controlCodeModeEntryId")
    recordTicketEvent("control_code_reset", reason)
    if (broadcast) {
      broadcastStatus()
    }
    if (scheduleSoftCheck) {
      scheduleControlCodeSoftCheck(reason)
    }
  }

  private fun rememberControlCodeSurface(state: TicketViviRecoveryState) {
    if (state != TicketViviRecoveryState.CONTROL_CODE_POPUP && state != TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      return
    }
    lastControlCodeSurfaceState = state.name
    lastControlCodeSurfaceSeenAtMillis = SystemClock.elapsedRealtime()
    lastControlExitDirtySurfaceState = state.name
  }

  private fun recentControlCodeSurfaceState(): String? {
    val state = lastControlCodeSurfaceState ?: return null
    val age = ageMillis(lastControlCodeSurfaceSeenAtMillis, SystemClock.elapsedRealtime()) ?: return null
    return state.takeIf { age <= CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS }
  }

  private fun controlCodeSurfaceMemoryState(): String? {
    val state = lastControlCodeSurfaceState ?: return null
    val age = ageMillis(lastControlCodeSurfaceSeenAtMillis, SystemClock.elapsedRealtime()) ?: return null
    return when {
      controlCodeModeActive -> state
      ticketSessionState == TICKET_SESSION_CONTROL_EXIT -> state
      ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION -> state
      age <= CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS -> state
      else -> null
    }
  }

  private fun isSoftControlExitReason(reason: String): Boolean {
    return reason == "control_released" ||
      reason == "control_expired" ||
      reason == "control_session_ended" ||
      reason == "user_released" ||
      reason == "admin_revoked" ||
      reason == "phone_backend_switched" ||
      reason == "phone_left_ticket" ||
      reason.startsWith("control_code_closed_") ||
      reason.startsWith("control_exit")
  }

  private fun scheduleControlExitSoftSettle(reason: String) {
    if (!streamActive) {
      return
    }
    val transitionInFlight = SystemClock.elapsedRealtime() < controlCodeTransitionGraceUntilMillis
    cancelForegroundGuard()
    postRemoteTapForegroundCheckJob?.cancel()
    if (::ticketRecoveryCoordinator.isInitialized) {
      ticketRecoveryCoordinator.cancel()
    }
    updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)
    recordTicketEvent("control_exit_soft_settle", reason)
    controlExitPopupLikelyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
    if (!transitionInFlight) {
      controlCodeTransitionGraceUntilMillis = 0L
    }
    resetControlCodeMode(reason, broadcast = false, scheduleSoftCheck = false)
    scheduleControlExitCleanup(reason)
    broadcastStatus()
  }

  private fun scheduleControlExitCleanup(reason: String) {
    controlExitCleanupJob?.cancel()
    lastControlExitSurfaceProbeAtMillis = 0L
    lastControlExitSurfaceProbeState = null
    lastControlExitSurfaceProbeReason = null
    controlExitCleanupJob = serviceScope.launch {
      runControlExitCleanup(reason)
    }
  }

  private fun scheduleControlCodeSoftCheck(reason: String) {
    if (!streamActive || reason.startsWith("session_stop_") || reason == "foreground_guard_cancelled") {
      return
    }
    controlCodeSoftCheckJob?.cancel()
    controlCodeSoftCheckJob = serviceScope.launch {
      val now = SystemClock.elapsedRealtime()
      val transitionDelayMillis = (controlCodeTransitionGraceUntilMillis - now).coerceAtLeast(0L)
      val softControlExit = isSoftControlExitReason(reason)
      val baseDelayMillis = if (softControlExit) 0L else CONTROL_CODE_SOFT_CHECK_DELAY_MILLIS
      delay(baseDelayMillis + transitionDelayMillis)
      if (softControlExit) {
        runControlExitCleanup(reason)
        return@launch
      }
      val result = ticketAutopilot.observeFastState("control_code_soft_check:$reason")
      when (result?.state) {
        TicketViviRecoveryState.CONTROL_CODE_POPUP,
        TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
          rememberControlCodeSurface(result.state)
          if (isSoftControlExitReason(reason)) {
            completeOrRecoverControlExitSoftCheck(reason, result.state.name)
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
            val controlExitState = alignControlExitTicketDetailWithSurface(
              result.state,
              reason,
              "accessibility_soft_check"
            )
            if (controlExitState != TicketViviRecoveryState.TICKET_DETAIL) {
              completeOrRecoverControlExitSoftCheck(reason, controlExitState.name)
              return@launch
            }
            completeVerifiedTicketDetailControlExitCleanup(
              reason = reason,
              detectedState = controlExitState.name,
              closeAction = "none",
              startedAtMillis = SystemClock.elapsedRealtime(),
              firstVerificationResult = controlExitState.name
            )
          } else {
            updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_soft_check_ok")
            recordTicketEvent("control_code_soft_check_ok", result.state.name)
          }
        }
        else -> {
          if (isSoftControlExitReason(reason)) {
            completeOrRecoverControlExitSoftCheck(reason, result?.state?.name ?: "UNKNOWN")
          } else {
            recordTicketEvent("control_code_soft_check_needs_attention", result?.state?.name ?: "UNKNOWN")
            updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_code_soft_check_$reason")
            broadcastStatus()
          }
        }
      }
    }
  }

  private suspend fun runControlExitCleanup(reason: String): Boolean {
    val closed = withTimeoutOrNull(CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS) {
      trySoftCloseControlCodeSurface(reason)
    } == true
    if (closed) {
      return true
    }
    val result = ticketAutopilot.observeFastState("control_code_soft_check:$reason")
    val state = result?.state?.let { observed ->
      alignControlExitTicketDetailWithSurface(observed, reason, "accessibility_after_close")
    }
    return when (state) {
      TicketViviRecoveryState.CONTROL_CODE_POPUP,
      TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
        rememberControlCodeSurface(state)
        completeOrRecoverControlExitSoftCheck(reason, state.name)
      }
      TicketViviRecoveryState.TICKET_DETAIL -> completeVerifiedTicketDetailControlExitCleanup(
        reason = reason,
        detectedState = state.name,
        closeAction = "none",
        startedAtMillis = SystemClock.elapsedRealtime(),
        firstVerificationResult = state.name
      )
      else -> completeOrRecoverControlExitSoftCheck(reason, state?.name ?: "UNKNOWN")
    }
  }

  private suspend fun completeOrRecoverControlExitSoftCheck(reason: String, detectedState: String): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    delay(CONTROL_EXIT_FINAL_CONFIRM_DELAY_MILLIS)
    softCleanupControlExitSurface(reason, detectedState, startedAtMillis)?.let { return it }
    val hierarchy = controlExitHierarchy()
    val finalState = if (hierarchy.isNullOrBlank()) {
      TicketViviRecoveryState.BLANK
    } else {
      alignControlExitTicketDetailWithSurface(
        TicketViviPageEnforcer.classifyForRecovery(hierarchy),
        reason,
        "hierarchy_final_confirm"
      )
    }
    if (finalState == TicketViviRecoveryState.TICKET_DETAIL) {
      return completeVerifiedTicketDetailControlExitCleanup(
        reason = reason,
        detectedState = detectedState,
        closeAction = "final_confirm",
        startedAtMillis = startedAtMillis,
        firstVerificationResult = finalState.name
      )
    }
    recordTicketEvent("control_code_soft_check_needs_attention", finalState.name)
    updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_code_soft_check_$reason")
    broadcastStatus()
    return false
  }

  private suspend fun trySoftCloseControlCodeSurface(reason: String): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val violation = controlExitForegroundViolationReason()
    cacheForegroundViolation(violation)
    if (violation != null) {
      val hierarchy = controlExitHierarchy()
      val hierarchyState = if (hierarchy.isNullOrBlank()) {
        null
      } else {
        TicketViviPageEnforcer.classifyForRecovery(hierarchy)
      }
      if (hierarchyState?.controlExitHierarchyIsAuthoritative() == true) {
        recordTicketEvent("control_exit_foreground_check_bypassed", "$violation state=${hierarchyState.name}")
      } else {
        return tryControlExitGeneratedResultGeometryFallback(
          reason = reason,
          startedAtMillis = startedAtMillis,
          detectedState = "foreground_violation",
          verificationFailure = violation
        )
      }
    }
    if (controlExitCleanNeedsSurfaceProof()) {
      val surfaceState = controlExitGeneratedResultSurfaceProbe(reason, "control_exit_initial_surface")
      if (surfaceState == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
        return tryControlExitGeneratedResultGeometryFallback(
          reason = reason,
          startedAtMillis = startedAtMillis,
          detectedState = surfaceState.name,
          verificationFailure = "initial_surface_probe_dirty"
        )
      }
    }
    tryControlExitDirectCloseFromHierarchy(reason, startedAtMillis, "hierarchy_direct_initial")?.let { return it }
    if (SystemClock.elapsedRealtime() < controlExitPopupLikelyUntilMillis) {
      controlExitHierarchy()?.let { hierarchy ->
        val hierarchyState = alignControlExitTicketDetailWithSurface(
          TicketViviPageEnforcer.classifyForRecovery(hierarchy),
          reason,
          "hierarchy_initial"
        )
        if (hierarchyState == TicketViviRecoveryState.TICKET_DETAIL) {
          return completeVerifiedTicketDetailControlExitCleanup(
            reason = reason,
            detectedState = hierarchyState.name,
            closeAction = "none",
            startedAtMillis = startedAtMillis,
            firstVerificationResult = hierarchyState.name
          )
        }
        if (
          hierarchyState == TicketViviRecoveryState.CONTROL_CODE_POPUP ||
          hierarchyState == TicketViviRecoveryState.CONTROL_CODE_RESULT
        ) {
          rememberControlCodeSurface(hierarchyState)
          if (hideControlCodeKeyboardForControlExit(hierarchy, reason)) {
            delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
          }
          val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
          if (action != null) {
            val tap = runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_exit_popup_close")
            if (tap.ok) {
              return verifyAndCompleteControlExitCleanup(reason, hierarchyState.name, action.reason, startedAtMillis)
            }
            recordTicketEvent("control_exit_popup_close_failed", "duration_ms=${tap.durationMs}")
          }
        }
      }
    }
    val fastState = ticketAutopilot.observeFastState("control_exit_fast:$reason")
    val fastControlExitState = fastState?.state?.let { state ->
      alignControlExitTicketDetailWithSurface(state, reason, "accessibility_fast")
    }
    if (fastControlExitState == TicketViviRecoveryState.TICKET_DETAIL) {
      return completeVerifiedTicketDetailControlExitCleanup(
        reason = reason,
        detectedState = fastControlExitState.name,
        closeAction = "none",
        startedAtMillis = startedAtMillis,
        firstVerificationResult = fastControlExitState.name
      )
    }
    if (
      fastControlExitState == TicketViviRecoveryState.CONTROL_CODE_POPUP ||
      fastControlExitState == TicketViviRecoveryState.CONTROL_CODE_RESULT ||
      SystemClock.elapsedRealtime() < controlExitPopupLikelyUntilMillis
    ) {
      if (fastControlExitState == TicketViviRecoveryState.CONTROL_CODE_POPUP || fastControlExitState == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        rememberControlCodeSurface(fastControlExitState)
      }
      controlExitHierarchy()?.let { hierarchy ->
        if (hideControlCodeKeyboardForControlExit(hierarchy, reason)) {
          delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
        }
      }
      val accessibilityClosed = PhoneAutomationServiceBridge.clickSelectors(
        TicketScreenConfig.VIVI_PACKAGE,
        CONTROL_EXIT_CLOSE_SELECTORS,
        CONTROL_EXIT_ACCESSIBILITY_CLOSE_TIMEOUT_MILLIS
      )
      if (accessibilityClosed) {
          return verifyAndCompleteControlExitCleanup(
            reason = reason,
            detectedState = fastControlExitState?.name ?: "CONTROL_SURFACE_LIKELY",
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
            detectedState = fastControlExitState?.name ?: "CONTROL_SURFACE_LIKELY",
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
        detectedState = fastControlExitState?.name ?: "CONTROL_SURFACE_LIKELY",
        startedAtMillis = startedAtMillis
      )?.let { return it }
    }
    var lastDetectedState = "UNKNOWN"
    var lastVerificationResult = "popup_not_visible"
    val deadlineMillis = startedAtMillis + CONTROL_EXIT_SURFACE_OBSERVE_TIMEOUT_MILLIS
    while (true) {
      val hierarchy = controlExitHierarchy()
      if (!hierarchy.isNullOrBlank()) {
        val detectedState = alignControlExitTicketDetailWithSurface(
          TicketViviPageEnforcer.classifyForRecovery(hierarchy),
          reason,
          "hierarchy_observe"
        )
        lastDetectedState = detectedState.name
        rememberControlCodeSurface(detectedState)
        if (detectedState == TicketViviRecoveryState.TICKET_DETAIL) {
          return completeVerifiedTicketDetailControlExitCleanup(
            reason = reason,
            detectedState = detectedState.name,
            closeAction = "none",
            startedAtMillis = startedAtMillis,
            firstVerificationResult = detectedState.name
          )
        }
        if (hideControlCodeKeyboardForControlExit(hierarchy, reason)) {
          lastVerificationResult = "keyboard_hidden"
          delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
          continue
        }
        val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
        if (action != null) {
          val tap = runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_exit_popup_close")
          if (!tap.ok) {
            recordTicketEvent("control_exit_popup_close_failed", "duration_ms=${tap.durationMs}")
            softCleanupControlExitSurface(reason, detectedState.name, startedAtMillis)?.let { return it }
            recordControlExitCleanup(reason, detectedState.name, action.reason, startedAtMillis, "tap_failed", false, false)
            return false
          }
          return verifyAndCompleteControlExitCleanup(reason, detectedState.name, action.reason, startedAtMillis)
        }
        lastVerificationResult = "close_action_missing"
        if (!detectedState.controlExitMaySettle()) {
          softCleanupControlExitSurface(reason, detectedState.name, startedAtMillis)?.let { return it }
          recordTicketEvent("control_exit_popup_close_blocked", "close_action_missing")
          recordControlExitCleanup(reason, detectedState.name, "none", startedAtMillis, "close_action_missing", false, false)
          return false
        }
      } else {
        lastDetectedState = "UNKNOWN"
        lastVerificationResult = "hierarchy_unavailable"
      }
      if (SystemClock.elapsedRealtime() >= deadlineMillis) {
        if (lastDetectedState == "UNKNOWN" || lastDetectedState == TicketViviRecoveryState.BLANK.name) {
          return tryControlExitGeneratedResultGeometryFallback(
            reason = reason,
            startedAtMillis = startedAtMillis,
            detectedState = lastDetectedState,
            verificationFailure = lastVerificationResult
          )
        }
        softCleanupControlExitSurface(reason, lastDetectedState, startedAtMillis)?.let { return it }
        recordTicketEvent("control_exit_popup_close_blocked", lastVerificationResult)
        recordControlExitCleanup(reason, lastDetectedState, "none", startedAtMillis, lastVerificationResult, false, false)
        return false
      }
      delay(CONTROL_EXIT_SURFACE_OBSERVE_RETRY_MILLIS)
    }
  }

  private suspend fun tryControlExitDirectCloseFromHierarchy(
    reason: String,
    startedAtMillis: Long,
    detectorSource: String
  ): Boolean? {
    val hierarchy = controlExitHierarchy()
    if (hierarchy.isNullOrBlank()) {
      return null
    }
    val detectedState = alignControlExitTicketDetailWithSurface(
      TicketViviPageEnforcer.classifyForRecovery(hierarchy),
      reason,
      detectorSource
    )
    if (
      detectedState != TicketViviRecoveryState.CONTROL_CODE_POPUP &&
      detectedState != TicketViviRecoveryState.CONTROL_CODE_RESULT
    ) {
      return null
    }
    rememberControlCodeSurface(detectedState)
    if (hideControlCodeKeyboardForControlExit(hierarchy, reason)) {
      delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
    }
    val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
    if (action == null) {
      recordTicketEvent("control_exit_direct_close_missing", detectedState.name)
      return null
    }
    val tap = runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_exit_direct_close")
    if (!tap.ok) {
      recordTicketEvent("control_exit_direct_close_failed", "state=${detectedState.name} duration_ms=${tap.durationMs}")
      return null
    }
    lastControlExitDirectCloseAtMillis = SystemClock.elapsedRealtime()
    return verifyAndCompleteControlExitCleanup(
      reason = reason,
      detectedState = detectedState.name,
      closeAction = action.reason,
      startedAtMillis = startedAtMillis
    )
  }

  private suspend fun tryControlExitGeneratedResultGeometryFallback(
    reason: String,
    startedAtMillis: Long,
    detectedState: String,
    verificationFailure: String
  ): Boolean {
    val (width, height) = currentDisplaySize()
    val x = (width * CONTROL_EXIT_RESULT_CLOSE_X_FRACTION).roundToInt()
    val y = (height * CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION).roundToInt()
    rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
    lastControlExitGeometryCloseAtMillis = SystemClock.elapsedRealtime()
    recordTicketEvent("control_exit_result_geometry_fallback", "$detectedState after=$verificationFailure x=$x y=$y")
    val tap = runFastNonTouchInput("input tap $x $y", "control_exit_result_geometry_close")
    if (!tap.ok) {
      recordTicketEvent("control_exit_popup_close_failed", "geometry_result duration_ms=${tap.durationMs}")
      recordControlExitCleanup(reason, detectedState, "geometry_close_control_code_result", startedAtMillis, "tap_failed", false, false)
      return false
    }
    return verifyAndCompleteControlExitCleanup(
      reason = reason,
      detectedState = TicketViviRecoveryState.CONTROL_CODE_RESULT.name,
      closeAction = "geometry_close_control_code_result",
      startedAtMillis = startedAtMillis
    )
  }

  private suspend fun hideControlCodeKeyboardForControlExit(hierarchy: String, reason: String): Boolean {
    if (
      TicketViviPageEnforcer.classifyForRecovery(hierarchy) != TicketViviRecoveryState.CONTROL_CODE_POPUP ||
      !TicketViviPageEnforcer.isControlCodeInputFocused(hierarchy)
    ) {
      return false
    }
    val back = runFastNonTouchInput("input keyevent KEYCODE_BACK", "control_exit_keyboard_back")
    if (!back.ok) {
      recordTicketEvent("control_exit_keyboard_back_failed", "duration_ms=${back.durationMs}")
      return false
    }
    recordTicketEvent("control_exit_keyboard_hidden", reason)
    return true
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
      val alignedState = alignControlExitTicketDetailWithSurface(
        recovered.state,
        reason,
        "autopilot_recovered"
      )
      if (alignedState == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        return tryControlExitGeneratedResultGeometryFallback(
          reason = reason,
          startedAtMillis = startedAtMillis,
          detectedState = alignedState.name,
          verificationFailure = "surface_probe_dirty_after_recovery"
        )
      }
      val reportedState = controlExitCleanupReportedState(detectedState, recovered.firstActionState)
      val reportedAction = controlExitCleanupReportedAction(reportedState)
      return completeVerifiedTicketDetailControlExitCleanup(
        reason = reason,
        detectedState = reportedState,
        closeAction = reportedAction,
        startedAtMillis = startedAtMillis,
        firstVerificationResult = recovered.state.name
      )
    }
    return null
  }

  private fun controlExitCleanupReportedState(
    detectedState: String,
    firstActionState: TicketViviRecoveryState?
  ): String {
    if (detectedState != "UNKNOWN" && detectedState != "CONTROL_SURFACE_LIKELY") {
      return detectedState
    }
    controlCodeSurfaceMemoryState()?.let { return it }
    lastControlExitDirtySurfaceState?.let { return it }
    return when (firstActionState) {
      TicketViviRecoveryState.CONTROL_CODE_RESULT,
      TicketViviRecoveryState.CONTROL_CODE_POPUP -> firstActionState.name
      else -> detectedState
    }
  }

  private fun controlExitCleanupReportedAction(reportedState: String): String {
    return when (reportedState) {
      TicketViviRecoveryState.CONTROL_CODE_RESULT.name -> {
        val geometryAge = ageMillis(lastControlExitGeometryCloseAtMillis, SystemClock.elapsedRealtime())
        if (geometryAge != null && geometryAge <= CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS) {
          "geometry_close_control_code_result"
        } else {
          "soft_cleanup_close_control_code_result"
        }
      }
      TicketViviRecoveryState.CONTROL_CODE_POPUP.name -> "soft_cleanup_close_control_code_popup"
      else -> "soft_cleanup"
    }
  }

  private fun TicketViviRecoveryState.controlExitMaySettle(): Boolean {
    return this == TicketViviRecoveryState.BLANK ||
      this == TicketViviRecoveryState.UNKNOWN_VIVI ||
      this == TicketViviRecoveryState.OUTSIDE_VIVI
  }

  private fun TicketViviRecoveryState.controlExitHierarchyIsAuthoritative(): Boolean {
    return this == TicketViviRecoveryState.TICKET_DETAIL ||
      this == TicketViviRecoveryState.CONTROL_CODE_POPUP ||
      this == TicketViviRecoveryState.CONTROL_CODE_RESULT ||
      this == TicketViviRecoveryState.DISMISSIBLE_BLOCKER ||
      this == TicketViviRecoveryState.CART_OR_CHECKOUT
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
        return completeVerifiedTicketDetailControlExitCleanup(
          reason = reason,
          detectedState = detectedState,
          closeAction = closeAction,
          startedAtMillis = startedAtMillis,
          firstVerificationResult = verificationResult
        )
      }
      if (SystemClock.elapsedRealtime() >= deadlineMillis) {
        softCleanupControlExitSurface(reason, detectedState, startedAtMillis)?.let { return it }
        recordTicketEvent("control_exit_cleanup_verify_failed", "$detectedState->$verificationResult action=$closeAction")
        recordInputGateDecision(allowed = false, reason = "control_exit_cleanup_verify_failed")
        recordControlExitCleanup(reason, detectedState, closeAction, startedAtMillis, verificationResult, false, false)
        return false
      }
      delay(CONTROL_EXIT_SURFACE_OBSERVE_RETRY_MILLIS)
    }
  }

  private suspend fun observeControlExitVerificationState(reason: String): TicketViviRecoveryState? {
    val hierarchy = controlExitHierarchy()
    if (!hierarchy.isNullOrBlank()) {
      return alignControlExitTicketDetailWithSurface(
        TicketViviPageEnforcer.classifyForRecovery(hierarchy),
        reason,
        "hierarchy_verify"
      )
    }
    ticketAutopilot.observeFastState("control_exit_verify:$reason")?.let { verified ->
      if (verified.state != TicketViviRecoveryState.UNKNOWN_VIVI) {
        return alignControlExitTicketDetailWithSurface(verified.state, reason, "accessibility_verify")
      }
    }
    return null
  }

  private suspend fun completeVerifiedTicketDetailControlExitCleanup(
    reason: String,
    detectedState: String,
    closeAction: String,
    startedAtMillis: Long,
    firstVerificationResult: String
  ): Boolean {
    delay(CONTROL_EXIT_SECOND_VERIFY_DELAY_MILLIS)
    val secondVerification = observeControlExitVerificationState("second_confirm:$reason")
    if (secondVerification == TicketViviRecoveryState.TICKET_DETAIL) {
      val reportedState = controlExitCleanupReportedStateAfterVerification(detectedState, closeAction)
      val reportedAction = controlExitCleanupReportedActionAfterVerification(reportedState, closeAction)
      return completeControlExitCleanup(
        reason = reason,
        detectedState = reportedState,
        closeAction = reportedAction,
        startedAtMillis = startedAtMillis,
        verificationResult = "${firstVerificationResult}->${secondVerification.name}",
        freshFrameRequested = true
      )
    }
    if (!closeAction.startsWith("soft_cleanup")) {
      softCleanupControlExitSurface(reason, detectedState, startedAtMillis)?.let { return it }
    }
    val verificationResult = secondVerification?.name ?: "UNKNOWN"
    recordTicketEvent(
      "control_exit_cleanup_verify_failed",
      "$detectedState->$firstVerificationResult->$verificationResult action=$closeAction"
    )
    recordInputGateDecision(allowed = false, reason = "control_exit_cleanup_verify_failed")
    recordControlExitCleanup(reason, detectedState, closeAction, startedAtMillis, verificationResult, false, false)
    return false
  }

  private fun controlExitCleanupReportedStateAfterVerification(detectedState: String, closeAction: String): String {
    if (detectedState != TicketViviRecoveryState.TICKET_DETAIL.name || closeAction != "none") {
      return detectedState
    }
    return controlCodeSurfaceMemoryState() ?: lastControlExitDirtySurfaceState ?: detectedState
  }

  private fun controlExitCleanupReportedActionAfterVerification(reportedState: String, closeAction: String): String {
    if (closeAction != "none") {
      return closeAction
    }
    return when (reportedState) {
      TicketViviRecoveryState.CONTROL_CODE_RESULT.name -> "already_closed_after_control_code_result"
      TicketViviRecoveryState.CONTROL_CODE_POPUP.name -> "already_closed_after_control_code_popup"
      else -> closeAction
    }
  }

  private fun completeControlExitCleanup(
    reason: String,
    detectedState: String,
    closeAction: String,
    startedAtMillis: Long,
    verificationResult: String,
    freshFrameRequested: Boolean
  ): Boolean {
    val finalDetectedState = if (detectedState == "UNKNOWN" || detectedState == "CONTROL_SURFACE_LIKELY") {
      controlCodeSurfaceMemoryState() ?: lastControlExitDirtySurfaceState ?: detectedState
    } else {
      detectedState
    }
    val finalCloseAction = if (closeAction == "soft_cleanup" || closeAction == "none") {
      controlExitCleanupReportedAction(finalDetectedState).takeIf { it != "soft_cleanup" } ?: closeAction
    } else {
      closeAction
    }
    controlCodeModeActive = false
    controlCodeModeEnteredAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    clearControlCodePopupSurfaceCache()
    controlExitPopupLikelyUntilMillis = 0L
    lastControlCodeSurfaceState = null
    lastControlCodeSurfaceSeenAtMillis = 0L
    lastControlExitDirtySurfaceState = null
    lastControlExitDirectCloseAtMillis = 0L
    lastControlExitGeometryCloseAtMillis = 0L
    recordInputGateDecision(allowed = true, reason = "control_exit_popup_closed")
    recordTicketEvent("control_exit_popup_closed", reason)
    updateTicketSessionState(TICKET_SESSION_LIVE, "control_exit_popup_closed")
    if (streamActive) {
      startForegroundGuard()
    }
    recordTicketEvent("control_code_soft_check_ok", verificationResult.lowercase())
    if (freshFrameRequested) {
      requestKeyFrame("control_exit_cleanup")
    }
    recordControlExitCleanup(reason, finalDetectedState, finalCloseAction, startedAtMillis, verificationResult, true, freshFrameRequested)
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
    val completedAtMillis = SystemClock.elapsedRealtime()
    val previousAction = lastControlExitCleanupCloseAction
    val previousAge = ageMillis(lastControlExitCleanupCompletedAtMillis, completedAtMillis)
    if (
      succeeded &&
      lastControlExitCleanupSucceeded == true &&
      lastControlExitCleanupReason == reason &&
      closeAction == "soft_cleanup" &&
      previousAction != null &&
      previousAction != "soft_cleanup" &&
      previousAge != null &&
      previousAge <= CONTROL_EXIT_DUPLICATE_SUCCESS_KEEP_MILLIS
    ) {
      recordTicketEvent(
        "control_exit_cleanup_duplicate_ignored",
        "reason=$reason kept_action=$previousAction ignored_action=$closeAction"
      )
      return
    }
    lastControlExitCleanupReason = reason
    lastControlExitCleanupDetectedState = detectedState
    lastControlExitCleanupCloseAction = closeAction
    lastControlExitCleanupDurationMillis = durationMillis
    lastControlExitCleanupCompletedAtMillis = completedAtMillis
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

  private suspend fun handleGenerateControlCode(requestId: String, digits: String) {
    val cleanRequestId = requestId.trim()
    val cleanDigits = digits.trim()
    if (cleanRequestId.isBlank()) {
      sendControlCodeResult("", false, "missing_request_id", "", null, null, 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (!CONTROL_CODE_REQUEST_DIGITS_REGEX.matches(cleanDigits)) {
      sendControlCodeResult(cleanRequestId, false, "invalid_code", "", null, null, 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (sendCachedControlCodeResult(cleanRequestId)) {
      return
    }
    controlCodeRequestMutex.withLock {
      if (sendCachedControlCodeResult(cleanRequestId)) {
        return
      }
      val startedAtMillis = SystemClock.elapsedRealtime()
      val phases = linkedMapOf<String, Long>()
      lastControlCodeRequestId = cleanRequestId
      lastControlCodeRequestStatus = "running"
      lastControlCodeRequestReason = null
      lastControlCodeRequestValue = null
      lastControlCodeRequestDurationMillis = null
      lastControlCodeRequestCompletedAtMillis = 0L
      broadcastStatus()

      var ok = false
      var reason = "failed"
      var value = ""
      var imageMime: String? = null
      var imageBase64: String? = null
      var imageBytes: ByteArray? = null
      var resultSent = false

      try {
        phases["phone_command_received"] = 0L
        if (!measureInputPhase(phases, "gate") { canForwardRemoteInput() }) {
          reason = inputGateReason
        } else {
          markControlCodeRequestPhase(phases, "request_gate_passed", startedAtMillis)
	          val delivery = runFastControlCodeDeliveryForRequest(cleanDigits, phases, startedAtMillis)
	          ok = delivery.ok
	          reason = delivery.reason
	          value = delivery.value
	          imageBytes = delivery.imageBytes
	          imageMime = if (imageBytes == null) null else "image/png"
	          if (ok) {
            val cleanupStart = delivery.cleanupStart ?: beginGeneratedControlCodeResultFastClose(
              generatedHierarchy = delivery.generatedHierarchy,
              reason = "control_code_request_complete",
              phases = phases,
              requestStartedAtMillis = startedAtMillis
            )
            imageBase64 = encodeControlCodeImageBase64(imageBytes)
	            markControlCodeRequestPhase(phases, "image_delivered", startedAtMillis)
	            sendControlCodeResult(
	              requestId = cleanRequestId,
              ok = ok,
              reason = reason,
              value = value,
              imageMime = imageMime,
              imageBase64 = imageBase64,
              startedAtMillis = startedAtMillis,
              phases = phases,
              cleanupPending = true
            )
            resultSent = true
            recordTicketEvent("control_code_fast_cleanup_phase", "result_delivered")
            val cleanupSucceeded = finishGeneratedControlCodeResultFastCleanup(
              cleanupStart = cleanupStart,
              reason = "control_code_request_complete",
              phases = phases,
              requestStartedAtMillis = startedAtMillis
            )
            sendControlCodeCleanup(
              requestId = cleanRequestId,
              ok = cleanupSucceeded,
              reason = if (cleanupSucceeded) "ticket_detail" else "control_code_cleanup_attention_needed",
	              startedAtMillis = startedAtMillis
	            )
	            if (!cleanupSucceeded) {
	              scheduleControlExitSoftSettle("control_code_request_cleanup_failed")
	            }
	          } else if (reason == "control_code_image_capture_failed" && delivery.generatedHierarchy.isNotBlank()) {
	            val cleanupStart = delivery.cleanupStart ?: beginGeneratedControlCodeResultFastClose(
	              generatedHierarchy = delivery.generatedHierarchy,
	              reason = "control_code_request_capture_failed",
	              phases = phases,
	              requestStartedAtMillis = startedAtMillis
	            )
	            val cleanupSucceeded = finishGeneratedControlCodeResultFastCleanup(
	              cleanupStart = cleanupStart,
	              reason = "control_code_request_capture_failed",
	              phases = phases,
	              requestStartedAtMillis = startedAtMillis
	            )
	            sendControlCodeResult(
	              requestId = cleanRequestId,
	              ok = false,
	              reason = reason,
	              value = value,
	              imageMime = imageMime,
	              imageBase64 = imageBase64,
	              startedAtMillis = startedAtMillis,
	              phases = phases,
	              cleanupPending = true
	            )
	            resultSent = true
	            sendControlCodeCleanup(
	              requestId = cleanRequestId,
	              ok = cleanupSucceeded,
	              reason = if (cleanupSucceeded) "ticket_detail" else "control_code_cleanup_attention_needed",
	              startedAtMillis = startedAtMillis
	            )
	            if (!cleanupSucceeded) {
	              scheduleControlExitSoftSettle("control_code_request_capture_failed_cleanup_unverified")
	            }
	          } else if (delivery.cleanupRequired) {
	            sendControlCodeResult(
	              requestId = cleanRequestId,
	              ok = false,
	              reason = reason,
	              value = value,
	              imageMime = imageMime,
	              imageBase64 = imageBase64,
	              startedAtMillis = startedAtMillis,
	              phases = phases,
	              cleanupPending = true
	            )
	            resultSent = true
	            val cleanupSucceeded = recoverControlCodeRequestSurface("control_code_request_failed_cleanup")
	            sendControlCodeCleanup(
	              requestId = cleanRequestId,
	              ok = cleanupSucceeded,
	              reason = if (cleanupSucceeded) "ticket_detail" else "control_code_cleanup_attention_needed",
	              startedAtMillis = startedAtMillis
	            )
	            if (!cleanupSucceeded) {
	              scheduleControlExitSoftSettle("control_code_request_failed_cleanup_unverified")
	            }
	          }
	        }
	      } catch (cancelled: CancellationException) {
	        throw cancelled
	      } catch (error: Throwable) {
	        reason = "control_code_request_failed"
	        Log.w(TAG, "ticket_control_code_request_failed request=$cleanRequestId", error)
	        val cleanupSucceeded = runCatching {
	          recoverControlCodeRequestSurface("control_code_request_error_cleanup")
	        }.getOrDefault(false)
	        sendControlCodeResult(
	          requestId = cleanRequestId,
	          ok = false,
	          reason = reason,
	          value = value,
	          imageMime = imageMime,
	          imageBase64 = imageBase64,
	          startedAtMillis = startedAtMillis,
	          phases = phases,
	          cleanupPending = true
	        )
	        resultSent = true
	        sendControlCodeCleanup(
	          requestId = cleanRequestId,
	          ok = cleanupSucceeded,
	          reason = if (cleanupSucceeded) "ticket_detail" else "control_code_cleanup_attention_needed",
	          startedAtMillis = startedAtMillis
	        )
	        if (!cleanupSucceeded) {
	          scheduleControlExitSoftSettle("control_code_request_error_cleanup_unverified")
	        }
	      }

      if (!resultSent) {
        sendControlCodeResult(
          requestId = cleanRequestId,
          ok = ok,
          reason = reason,
          value = value,
          imageMime = imageMime,
          imageBase64 = imageBase64,
          startedAtMillis = startedAtMillis,
          phases = phases,
          cleanupPending = false
        )
      }
    }
  }

  private suspend fun runFastControlCodeDeliveryForRequest(
    cleanDigits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodeDelivery {
    val targets = openControlCodePopupFastForRequest(phases, requestStartedAtMillis) ?: return FastControlCodeDelivery(
      ok = false,
      reason = inputGateReason.ifBlank { "control_code_popup_timeout" }
    )
    if (!enterControlCodeDigitsFastForRequest(cleanDigits, targets, phases, requestStartedAtMillis)) {
      return FastControlCodeDelivery(
        ok = false,
        reason = inputGateReason.ifBlank { "control_code_input_set_failed" }
      )
    }
    val submitAttempted = tapControlCodeSubmitFastForRequest(targets, cleanDigits, phases, requestStartedAtMillis)
    if (!submitAttempted) {
      return FastControlCodeDelivery(
        ok = false,
        reason = inputGateReason.ifBlank { "control_code_submit_missing" }
      )
    }
    val waitOutcome = waitForGeneratedControlCodeResultAfterSubmit(phases, requestStartedAtMillis)
    val generated = waitOutcome.generated ?: return FastControlCodeDelivery(
      ok = false,
      reason = waitOutcome.failureReason
    )
    val capture = captureGeneratedControlCodeImageBytes(
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      generatedHierarchy = generated.hierarchy
    )
    if (capture.bytes == null) {
      return FastControlCodeDelivery(
        ok = false,
        reason = "control_code_image_capture_failed",
        value = generated.value,
        cleanupStart = capture.cleanupStart,
        generatedHierarchy = generated.hierarchy
      )
    }
    markViewerInput("control_code_request_digits")
    return FastControlCodeDelivery(
      ok = true,
      reason = "generated",
      value = generated.value,
      imageBytes = capture.bytes,
      cleanupStart = capture.cleanupStart,
      generatedHierarchy = generated.hierarchy
    )
  }

  private suspend fun prepareViviForControlCodeRequest(phases: MutableMap<String, Long>): Boolean {
    return measureInputPhase(phases, "prepare_ticket_detail_fast") {
      val fastState = ticketAutopilot.observeFastState("control_code_request_prepare_fast")
      if (fastState?.state == TicketViviRecoveryState.TICKET_DETAIL || fastState?.state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
        return@measureInputPhase true
      }
      val result = ticketAutopilot.driveToTicketDetail(
        reason = "control_code_request_prepare",
        forceFreshLaunch = false,
        allowControlCodePopup = true,
        restartPolicy = TicketAutopilotRestartPolicy.SOFT_RECOVERY,
        maxSteps = CONTROL_CODE_REQUEST_PREPARE_MAX_STEPS,
        timeoutMillis = CONTROL_CODE_REQUEST_PREPARE_TIMEOUT_MILLIS
      )
      val prepared = result.success &&
        (result.state == TicketViviRecoveryState.TICKET_DETAIL || result.state == TicketViviRecoveryState.CONTROL_CODE_POPUP)
      if (prepared) {
        recordTicketEvent("control_code_request_prepare_ready", result.state.name)
        return@measureInputPhase true
      }
      val reason = "control_code_request_prepare_${result.state.name.lowercase()}"
      recordInputGateDecision(allowed = false, reason = reason)
      recordTicketEvent("control_code_request_prepare_failed", "${result.state}:${result.step}")
      false
    }
  }

  private suspend fun openControlCodePopupFastForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodeTargets? {
    openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)?.let { return it }
    if (controlCodeRequestNeedsSurfaceRecovery()) {
      val recovered = measureInputPhase(phases, "pre_request_surface_cleanup") {
        runControlExitCleanup("control_code_request_preflight_cleanup")
      }
      if (!recovered) {
        recordInputGateDecision(allowed = false, reason = "control_code_request_preflight_cleanup_failed")
        return null
      }
      openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)?.let { return it }
    }
    if (!prepareViviForControlCodeRequest(phases)) {
      return null
    }
    return openControlCodePopupFromVerifiedStateFastForRequest(phases, requestStartedAtMillis)
  }

  private fun controlCodeRequestNeedsSurfaceRecovery(): Boolean {
    val current = viviStateMemory.current()
    val currentAge = ageMillis(current.observedAtMillis, SystemClock.elapsedRealtime())
    val recentControlSurface = currentAge != null &&
      currentAge <= CONTROL_CODE_SNAP_UNSAFE_STATE_MEMORY_MAX_AGE_MILLIS &&
      (
        current.state == TicketViviRecoveryState.CONTROL_CODE_POPUP ||
          current.state == TicketViviRecoveryState.CONTROL_CODE_RESULT
        )
    return recentControlSurface ||
      controlCodeModeActive ||
      ticketSessionState == TICKET_SESSION_CONTROL_EXIT ||
      (ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION && controlCodeSurfaceMemoryState() != null)
  }

  private suspend fun openControlCodePopupImmediateForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodeTargets? {
    val decision = controlCodeImmediateStartDecision()
    if (!decision.accepted) {
      recordTicketEvent("control_code_immediate_start_skipped", decision.reason)
      return null
    }
    val action = streamSize?.let { size ->
      controlCodeGeometryTarget(size, "generated_request_immediate").copy(reason = decision.reason)
    } ?: fallbackControlCodeButtonTarget().copy(reason = "${decision.reason}:display_geometry")
    recordInputGateDecision(allowed = true, reason = action.reason)
    markControlCodeTransition("control_code_request_open_popup_immediate")
    val tap = measureInputPhase(phases, "first_tap_fast") {
      runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_code_request_open_popup_immediate")
    }
    recordControlCodeSnapAttempt(
      rawX = action.x,
      rawY = action.y,
      candidateZone = action.candidateZone,
      snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
      accepted = tap.ok,
      reason = if (tap.ok) action.reason else "root_command_failed",
      finalX = action.x,
      finalY = action.y,
      detectedButtonBounds = action.detectedButtonBounds
    )
    if (!tap.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_immediate_tap_failed")
      return null
    }
    markControlCodeRequestPhase(phases, "first_phone_tap", requestStartedAtMillis)
    return waitForControlCodePopupTargetsFast(phases, "fast_popup_root_immediate")?.also {
      markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
      markControlCodeModeEntered("control_code_request_popup_ready_immediate")
    }
  }

  private suspend fun openControlCodePopupFromVerifiedStateFastForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
	  ): FastControlCodeTargets? {
	    val hierarchy = controlCodeRequestRootHierarchy(phases, "fast_find_button")
	    if (hierarchy.isNullOrBlank()) {
	      val recovered = measureInputPhase(phases, "recover_hierarchy_unavailable") {
	        runControlExitCleanup("control_code_request_hierarchy_unavailable")
	      }
	      if (recovered) {
	        return openControlCodePopupFromRecoveredStateFastForRequest(phases, requestStartedAtMillis)
	      }
	      recordInputGateDecision(allowed = false, reason = "control_code_request_hierarchy_unavailable")
	      return null
	    }
	    val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    if (state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
      controlCodeFastTargetsForHierarchy(hierarchy)?.let { targets ->
        controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
        markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
        markControlCodeModeEntered("control_code_request_popup_already_open")
        return targets
	      }
	    }
	    if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
	      rememberControlCodeSurface(state)
	      val recovered = measureInputPhase(phases, "recover_previous_result") {
	        runControlExitCleanup("control_code_request_previous_result")
	      }
	      if (recovered) {
	        return openControlCodePopupFromRecoveredStateFastForRequest(phases, requestStartedAtMillis)
	      }
	      recordInputGateDecision(allowed = false, reason = "control_code_request_previous_result_cleanup_failed")
	      return null
	    }
	    if (state != TicketViviRecoveryState.TICKET_DETAIL) {
	      recordInputGateDecision(allowed = false, reason = "control_code_request_unsafe_state:${state.name}")
	      scheduleTicketRecovery("control_code_request_unsafe_${state.name.lowercase()}", TicketRecoveryMode.ACTIVE_SOFT)
      return null
    }
    val action = TicketViviPageEnforcer.controlCodeButtonActionForHierarchy(hierarchy)?.let { detected ->
      TicketTapTarget(
        x = detected.x,
        y = detected.y,
        reason = detected.reason,
        candidateZone = "generated_request_fast",
        detectedButtonBounds = detected.bounds
      )
    } ?: streamSize?.let { size ->
      controlCodeGeometryTarget(size, "generated_request_fast").copy(reason = "control_code_button_request_geometry_fast")
    } ?: fallbackControlCodeButtonTarget()
    recordInputGateDecision(allowed = true, reason = action.reason)
    markControlCodeTransition("control_code_request_open_popup_fast")
    val tap = measureInputPhase(phases, "open_popup_fast") {
      runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_code_request_open_popup_fast")
    }
    recordControlCodeSnapAttempt(
      rawX = action.x,
      rawY = action.y,
      candidateZone = action.candidateZone,
      snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
      accepted = tap.ok,
      reason = if (tap.ok) action.reason else "root_command_failed",
      finalX = action.x,
      finalY = action.y,
      detectedButtonBounds = action.detectedButtonBounds
    )
    if (!tap.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_popup_open_failed")
      return null
    }
    markControlCodeRequestPhase(phases, "first_phone_tap", requestStartedAtMillis)
    val targets = waitForControlCodePopupTargetsFast(phases, "fast_popup_root")
    if (targets != null) {
      markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
      markControlCodeModeEntered("control_code_request_popup_ready_fast")
      return targets
    }
	    recordInputGateDecision(allowed = false, reason = "control_code_popup_timeout")
	    return null
	  }

	  private suspend fun openControlCodePopupFromRecoveredStateFastForRequest(
	    phases: MutableMap<String, Long>,
	    requestStartedAtMillis: Long
	  ): FastControlCodeTargets? {
	    val action = streamSize?.let { size ->
	      controlCodeGeometryTarget(size, "generated_request_recovered").copy(reason = "control_code_button_request_recovered_geometry")
	    } ?: fallbackControlCodeButtonTarget().copy(reason = "control_code_button_request_recovered_display_geometry")
	    recordInputGateDecision(allowed = true, reason = action.reason)
	    markControlCodeTransition("control_code_request_open_popup_recovered")
	    val tap = measureInputPhase(phases, "open_popup_recovered") {
	      runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_code_request_open_popup_recovered")
	    }
	    recordControlCodeSnapAttempt(
	      rawX = action.x,
	      rawY = action.y,
	      candidateZone = action.candidateZone,
	      snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
	      accepted = tap.ok,
	      reason = if (tap.ok) action.reason else "root_command_failed",
	      finalX = action.x,
	      finalY = action.y,
	      detectedButtonBounds = action.detectedButtonBounds
	    )
	    if (!tap.ok) {
	      recordInputGateDecision(allowed = false, reason = "control_code_popup_open_failed")
	      return null
	    }
	    markControlCodeRequestPhase(phases, "first_phone_tap", requestStartedAtMillis)
	    val targets = waitForControlCodePopupTargetsFast(phases, "fast_popup_root_recovered")
	    if (targets != null) {
	      markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
	      markControlCodeModeEntered("control_code_request_popup_ready_recovered")
	      return targets
	    }
	    recordInputGateDecision(allowed = false, reason = "control_code_popup_timeout")
	    return null
	  }

  private suspend fun waitForControlCodePopupTargetsFast(
    phases: MutableMap<String, Long>,
    phase: String
  ): FastControlCodeTargets? {
    val deadline = SystemClock.elapsedRealtime() + CONTROL_CODE_FAST_POPUP_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      delay(CONTROL_CODE_FAST_POLL_MILLIS)
      val currentHierarchy = controlCodeRequestRootHierarchy(phases, phase)
      val targets = currentHierarchy?.let { controlCodeFastTargetsForHierarchy(it) }
      if (targets != null) {
        controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
        return targets
      }
    }
    return null
  }

  private suspend fun enterControlCodeDigitsFastForRequest(
    digits: String,
    targets: FastControlCodeTargets,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    val focus = measureInputPhase(phases, "focus_input_fast") {
      runFastNonTouchInput("input tap ${targets.input.x} ${targets.input.y}", "control_code_input_focus_fast")
    }
    if (!focus.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_focus_failed")
      return false
    }
    delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
    val typed = measureInputPhase(phases, "type_digits_fast") {
      runFastNonTouchInput(
        """
        input keyevent KEYCODE_MOVE_END
        for i in 1 2 3 4 5 6 7 8 9; do input keyevent KEYCODE_DEL; done
        input text $digits
        """.trimIndent(),
        "control_code_request_type_digits_fast"
      )
    }
    if (!typed.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_set_failed")
      return false
    }
    delay(CONTROL_CODE_FAST_POLL_MILLIS)
    markControlCodeRequestPhase(phases, "digits_typed", requestStartedAtMillis)
    recordTicketEvent("control_code_input_typed_submit_now", "shell_text_ok")
    return true
  }

  private suspend fun tapControlCodeSubmitFastForRequest(
    targets: FastControlCodeTargets,
    digits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    var submitAttempted = false
    val submit = fallbackControlCodeShiftedSubmitAction() ?: targets.submit
    val tap = measureInputPhase(phases, "submit_digits_fast") {
      runFastNonTouchInput("input tap ${submit.x} ${submit.y}", "control_code_request_submit_button_fast")
    }
    if (tap.ok) {
      submitAttempted = true
      phases["control_code_submit_attempted"] = 1L
      markControlCodeRequestPhase(phases, "ok_tapped", requestStartedAtMillis)
      recordTicketEvent("control_code_submit_attempted", "coordinate_ok digits=${digits.length}")
    }
    if (!submitAttempted) {
      val accessibilitySubmitted = measureInputPhase(phases, "submit_accessibility_fallback") {
        PhoneAutomationServiceBridge.clickSelectors(
          expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
          selectors = controlCodeSubmitSelectors(),
          timeoutMillis = CONTROL_CODE_ACCESSIBILITY_SUBMIT_TIMEOUT_MILLIS
        )
      }
      submitAttempted = accessibilitySubmitted
      if (submitAttempted) {
        phases["control_code_submit_attempted"] = 1L
        markControlCodeRequestPhase(phases, "ok_tapped", requestStartedAtMillis)
        recordTicketEvent("control_code_submit_attempted", "accessibility_fallback digits=${digits.length}")
      }
    }
    if (!submitAttempted) {
      recordInputGateDecision(allowed = false, reason = "control_code_submit_missing")
    }
    return submitAttempted
  }

  private suspend fun waitForGeneratedControlCodeResultAfterSubmit(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): ControlCodeResultWaitOutcome {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadline = startedAtMillis + CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS
    var consecutiveTicketDetail = 0
    var sawPopup = false
    while (SystemClock.elapsedRealtime() < deadline) {
      val fastState = ticketAutopilot.observeFastState("control_code_result_fast_wait")?.state
      when (fastState) {
        TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
          val hierarchy = TicketViviPageEnforcer.hierarchyForVisibleNodes(
            PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)
          )
          if (hierarchy.isNotBlank() && !TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy)) {
            val value = TicketViviPageEnforcer.strictControlCodeResultValueForHierarchy(hierarchy).orEmpty()
            phases["wait_result_fast"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            markControlCodeRequestPhase(phases, "result_first_visible", requestStartedAtMillis)
            markControlCodeModeEntered("control_code_request_result_detected_fast")
            recordTicketEvent("control_code_request_result_detected_fast", value.ifBlank { "image_only" })
            return ControlCodeResultWaitOutcome(
              generated = GeneratedControlCodeResult(value = value, hierarchy = hierarchy),
              failureReason = ""
            )
          }
        }

        TicketViviRecoveryState.CONTROL_CODE_POPUP -> {
          sawPopup = true
          consecutiveTicketDetail = 0
          delay(CONTROL_CODE_FAST_POLL_MILLIS)
          continue
        }

        TicketViviRecoveryState.TICKET_DETAIL -> {
          consecutiveTicketDetail += 1
          if (
            consecutiveTicketDetail >= CONTROL_CODE_REQUEST_NO_RESULT_TICKET_DETAIL_COUNT &&
            SystemClock.elapsedRealtime() - startedAtMillis >= CONTROL_CODE_REQUEST_NO_RESULT_GRACE_MILLIS
          ) {
            phases["wait_result_fast"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            recordTicketEvent("control_code_request_not_generated", "ticket_detail_after_submit")
            return ControlCodeResultWaitOutcome(failureReason = "control_code_submit_returned_no_result")
          }
          delay(CONTROL_CODE_FAST_POLL_MILLIS)
          continue
        }

        else -> Unit
      }
      val hierarchy = controlCodeRequestRootHierarchy(phases, "fast_wait_result_root")
      if (!hierarchy.isNullOrBlank()) {
        val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
        viviStateMemory.record(
          state = state,
          ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(hierarchy),
          source = "control_code_fast",
          reason = "control_code_request_wait_after_submit"
        )
        if (TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy)) {
          sawPopup = true
        }
        val value = TicketViviPageEnforcer.strictControlCodeResultValueForHierarchy(hierarchy).orEmpty()
        if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT || value.isNotBlank()) {
          phases["wait_result_fast"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
          markControlCodeRequestPhase(phases, "result_first_visible", requestStartedAtMillis)
          markControlCodeModeEntered("control_code_request_result_detected_fast")
          recordTicketEvent("control_code_request_result_detected_fast", value.ifBlank { "image_only" })
          return ControlCodeResultWaitOutcome(
            generated = GeneratedControlCodeResult(value = value, hierarchy = hierarchy),
            failureReason = ""
          )
        }
        if (state == TicketViviRecoveryState.TICKET_DETAIL) {
          consecutiveTicketDetail += 1
          if (
            consecutiveTicketDetail >= CONTROL_CODE_REQUEST_NO_RESULT_TICKET_DETAIL_COUNT &&
            SystemClock.elapsedRealtime() - startedAtMillis >= CONTROL_CODE_REQUEST_NO_RESULT_GRACE_MILLIS
          ) {
            phases["wait_result_fast"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            recordTicketEvent("control_code_request_not_generated", "ticket_detail_after_submit")
            return ControlCodeResultWaitOutcome(failureReason = "control_code_submit_returned_no_result")
          }
        } else {
          consecutiveTicketDetail = 0
          if (state != TicketViviRecoveryState.CONTROL_CODE_POPUP && !TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy)) {
            phases["wait_result_fast"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            markControlCodeModeEntered("control_code_request_result_assumed_fast")
            recordTicketEvent("control_code_request_result_assumed_fast", state.name)
            return ControlCodeResultWaitOutcome(
              generated = GeneratedControlCodeResult(value = value, hierarchy = hierarchy),
              failureReason = ""
            )
          }
        }
      }
      delay(CONTROL_CODE_FAST_POLL_MILLIS)
    }
    phases["wait_result_fast"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    val popupStillPresent = controlCodePopupStillPresentAfterSubmit(phases)
    return if (sawPopup || popupStillPresent) {
      ControlCodeResultWaitOutcome(failureReason = "control_code_submit_timeout")
    } else {
      ControlCodeResultWaitOutcome(failureReason = "control_code_result_timeout")
    }
  }

  private suspend fun controlCodeRequestRootHierarchy(
    phases: MutableMap<String, Long>,
    phase: String
  ): String? {
    return measureInputPhase(phases, phase) {
      val accessibilityXml = TicketViviPageEnforcer.hierarchyForVisibleNodes(
        PhoneAutomationServiceBridge.snapshotVisibleNodes(TicketScreenConfig.VIVI_PACKAGE)
      )
      val accessibilityState = TicketViviPageEnforcer.classifyForRecovery(accessibilityXml)
      if (
        accessibilityXml.isNotBlank() &&
        accessibilityState != TicketViviRecoveryState.BLANK &&
        accessibilityState != TicketViviRecoveryState.OUTSIDE_VIVI
      ) {
        return@measureInputPhase accessibilityXml
      }
      val dump = dumpViviHierarchy(fresh = true, timeoutMillis = CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS)
      dump.stdout.takeIf { dump.ok && it.isNotBlank() }
    }
  }

  private fun controlCodeFastTargetsForHierarchy(hierarchy: String): FastControlCodeTargets? {
    val surface = TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)
    if (surface != null) {
      rememberControlCodePopupSurface(surface)
      return FastControlCodeTargets(input = surface.input, submit = surface.submit)
    }
    val input = TicketViviPageEnforcer.controlCodeInputActionLooseForHierarchy(hierarchy) ?: return null
    val submit = TicketViviPageEnforcer.controlCodeSubmitActionLooseForHierarchy(hierarchy)
      ?: cachedControlCodePopupSurface()?.submit
      ?: fallbackControlCodeShiftedSubmitAction()
    return FastControlCodeTargets(input = input, submit = submit)
  }

  private suspend fun findControlCodeSubmitActionFastForRequest(phases: MutableMap<String, Long>): TicketViviPageAction? {
    return controlCodeRequestRootHierarchy(phases, "find_submit_fast")
      ?.let { hierarchy ->
        TicketViviPageEnforcer.controlCodeSubmitActionLooseForHierarchy(hierarchy)
          ?: TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)?.also { surface ->
            rememberControlCodePopupSurface(surface)
          }?.submit
      }
  }

  private suspend fun verifyControlCodeDigitsEnteredFast(
    digits: String,
    phases: MutableMap<String, Long>
  ): Boolean {
    return controlCodeRequestRootHierarchy(phases, "verify_digits_fast")
      ?.let { hierarchy ->
        val inputValue = TicketViviPageEnforcer.controlCodeInputValueForHierarchy(hierarchy)
          ?: TicketViviPageEnforcer.controlCodeInputValueLooseForHierarchy(hierarchy)
        inputValue != null && (inputValue == digits || inputValue.filter { char -> char.isDigit() } == digits)
      } == true
  }

  private suspend fun controlCodeInputStillAvailableFastForRequest(
    phases: MutableMap<String, Long>,
    phase: String
  ): Boolean {
    return controlCodeRequestRootHierarchy(phases, phase)
      ?.let { hierarchy ->
        TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy) ||
          TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy) != null
      } == true
  }

  private suspend fun controlCodePopupStillPresentAfterSubmit(phases: MutableMap<String, Long>): Boolean {
    return controlCodeRequestRootHierarchy(phases, "verify_popup_after_submit")
      ?.let { hierarchy ->
        TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy) ||
          TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy) != null
      } == true
  }

  private fun fallbackControlCodeButtonTarget(): TicketTapTarget {
    val (width, height) = currentDisplaySize()
    return TicketTapTarget(
      x = (width * CONTROL_CODE_FAST_BUTTON_X_FRACTION).roundToInt(),
      y = (height * CONTROL_CODE_FAST_BUTTON_Y_FRACTION).roundToInt(),
      reason = "control_code_button_request_display_geometry_fast",
      candidateZone = "generated_request_fast"
    )
  }

  private fun controlCodeImmediateStartDecision(): ControlCodeImmediateStartDecision {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_FFMPEG_H264) {
      return ControlCodeImmediateStartDecision(false, "control_code_immediate_stream_inactive")
    }
    if (ticketSessionState != TICKET_SESSION_LIVE) {
      return ControlCodeImmediateStartDecision(false, "control_code_immediate_ticket_state_stale:$ticketSessionState")
    }
    if (lastRootH264BlankProbeResult != "visible") {
      return ControlCodeImmediateStartDecision(false, "control_code_immediate_secure_probe_not_visible")
    }
    val nowMillis = SystemClock.elapsedRealtime()
    val current = viviStateMemory.current()
    val currentAge = ageMillis(current.observedAtMillis, nowMillis)
    if (
      currentAge != null &&
      currentAge <= CONTROL_CODE_SNAP_UNSAFE_STATE_MEMORY_MAX_AGE_MILLIS &&
      current.state != TicketViviRecoveryState.TICKET_DETAIL
    ) {
      return ControlCodeImmediateStartDecision(false, "control_code_immediate_recent_state_${current.state.name.lowercase()}")
    }
    if (
      current.state == TicketViviRecoveryState.TICKET_DETAIL &&
      currentAge != null &&
      currentAge <= CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
    ) {
      return ControlCodeImmediateStartDecision(true, "control_code_button_immediate_recent_ticket_detail")
    }
    if (viviStateMemory.recentTicketDetailWithin(CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS) != null) {
      return ControlCodeImmediateStartDecision(true, "control_code_button_immediate_recent_ticket_detail")
    }
    return ControlCodeImmediateStartDecision(false, "control_code_immediate_no_recent_ticket_detail")
  }

  private fun fallbackControlCodeShiftedSubmitAction(): TicketViviPageAction {
    val (width, height) = currentDisplaySize()
    return TicketViviPageAction(
      x = (width * CONTROL_CODE_FAST_SHIFTED_OK_X_FRACTION).roundToInt(),
      y = (height * CONTROL_CODE_FAST_SHIFTED_OK_Y_FRACTION).roundToInt(),
      reason = "submit_control_code_popup_shifted_geometry"
    )
  }

  private suspend fun openControlCodePopupForRequest(phases: MutableMap<String, Long>): Boolean {
    val hierarchy = measureInputPhase(phases, "find_button") {
      controlCodeRequestHierarchy()
    }
    if (hierarchy.isNullOrBlank()) {
      recordInputGateDecision(allowed = false, reason = "control_code_request_hierarchy_unavailable")
      return false
    }
    val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    if (state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
      TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)?.let { surface ->
        rememberControlCodePopupSurface(surface)
      }
      controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
      markControlCodeModeEntered("control_code_request_popup_already_open")
      return true
    }
    if (state != TicketViviRecoveryState.TICKET_DETAIL) {
      recordInputGateDecision(allowed = false, reason = "control_code_request_unsafe_state:${state.name}")
      scheduleTicketRecovery("control_code_request_unsafe_${state.name.lowercase()}", TicketRecoveryMode.ACTIVE_SOFT)
      return false
    }
    val action = TicketViviPageEnforcer.controlCodeButtonActionForHierarchy(hierarchy)?.let { detected ->
      TicketTapTarget(
        x = detected.x,
        y = detected.y,
        reason = detected.reason,
        candidateZone = "generated_request",
        detectedButtonBounds = detected.bounds
      )
    } ?: streamSize?.let { size ->
      controlCodeGeometryTarget(size, "generated_request").copy(reason = "control_code_button_request_geometry")
    }
    if (action == null) {
      recordInputGateDecision(allowed = false, reason = "control_code_button_missing")
      return false
    }
    recordInputGateDecision(allowed = true, reason = action.reason)
    markControlCodeTransition("control_code_request_open_popup")
    val tap = measureInputPhase(phases, "open_popup") {
      runFastNonTouchInput("input tap ${action.x} ${action.y}", "control_code_request_open_popup")
    }
    recordControlCodeSnapAttempt(
      rawX = action.x,
      rawY = action.y,
      candidateZone = action.candidateZone,
      snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
      accepted = tap.ok,
      reason = if (tap.ok) action.reason else "root_command_failed",
      finalX = action.x,
      finalY = action.y,
      detectedButtonBounds = action.detectedButtonBounds
    )
    if (!tap.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_popup_open_failed")
      return false
    }
    val deadline = SystemClock.elapsedRealtime() + CONTROL_CODE_REQUEST_POPUP_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      delay(CONTROL_CODE_REQUEST_POLL_MILLIS)
      val currentHierarchy = controlCodeRequestHierarchy()
      val surface = currentHierarchy
        ?.takeIf { it.isNotBlank() }
        ?.let { TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(it) }
      if (surface != null) {
        rememberControlCodePopupSurface(surface)
        controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
        markControlCodeModeEntered("control_code_request_popup_ready")
        return true
      }
    }
    recordInputGateDecision(allowed = false, reason = "control_code_popup_timeout")
    return false
  }

  private suspend fun enterControlCodeDigitsForRequest(digits: String, phases: MutableMap<String, Long>): Boolean {
    val initialSubmitAction = cachedControlCodePopupSurface()?.submit
      ?: findControlCodeSubmitActionForRequest(phases, "find_submit_before_input")
    if (initialSubmitAction == null) {
      recordInputGateDecision(allowed = false, reason = "control_code_submit_missing")
      return false
    }
    if (!setControlCodeDigitsForRequest(digits, phases)) {
      return false
    }
    return submitControlCodeDigitsForRequest(initialSubmitAction, digits, phases)
  }

  private suspend fun tapControlCodeSubmitActionForRequest(
    action: TicketViviPageAction,
    phases: MutableMap<String, Long>,
    phase: String
  ): Boolean {
    val submitted = measureInputPhase(phases, phase) {
      runFastNonTouchInput(
        "input tap ${action.x} ${action.y}",
        "control_code_request_submit_button"
      )
    }
    return submitted.ok
  }

  private suspend fun submitControlCodeDigitsForRequest(
    initialSubmitAction: TicketViviPageAction,
    digits: String,
    phases: MutableMap<String, Long>
  ): Boolean {
    var submitAttempted = false
    val visibleSubmitAction = findControlCodeSubmitActionForRequest(phases, "find_submit_visible")
      ?: initialSubmitAction
    if (controlCodePopupStillPresentForCachedSubmit(phases, null)) {
      if (tapControlCodeSubmitActionForRequest(visibleSubmitAction, phases, "submit_digits_visible")) {
        submitAttempted = true
        when (waitForControlCodeSubmitTransition(phases)) {
          ControlCodeSubmitTransition.GENERATED_RESULT -> {
            markViewerInput("control_code_request_digits")
            return true
          }

          ControlCodeSubmitTransition.RETURNED_NO_RESULT -> {
            recordInputGateDecision(allowed = false, reason = "control_code_submit_returned_no_result")
            return false
          }

          ControlCodeSubmitTransition.STILL_ON_POPUP,
          ControlCodeSubmitTransition.TIMEOUT -> {
            if (!controlCodePopupStillPresentForCachedSubmit(phases, null)) {
              recordInputGateDecision(allowed = false, reason = "control_code_submit_timeout")
              return false
            }
          }
        }
      }
    }

    val accessibilityBack = measureInputPhase(phases, "control_code_request_hide_keyboard") {
      PhoneAutomationServiceBridge.performBack()
    }
    if (!accessibilityBack) {
      measureInputPhase(phases, "dismiss_keyboard_for_submit") {
        runFastNonTouchInput("input keyevent KEYCODE_BACK", "control_code_request_hide_keyboard")
      }
    }
    delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)

    val submitAction = findControlCodeSubmitActionForRequest(phases, "find_submit_after_keyboard")
      ?: if (controlCodePopupStillPresentForCachedSubmit(phases, null)) initialSubmitAction else null
    if (submitAction == null) {
      recordInputGateDecision(
        allowed = false,
        reason = if (submitAttempted) "control_code_submit_timeout" else "control_code_submit_missing"
      )
      return false
    } else {
      val submitted = tapControlCodeSubmitActionForRequest(submitAction, phases, "submit_digits")
      if (!submitted) {
        val accessibilitySubmitted = measureInputPhase(phases, "submit_accessibility") {
          PhoneAutomationServiceBridge.clickSelectors(
            expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
            selectors = controlCodeSubmitSelectors(),
            timeoutMillis = CONTROL_CODE_ACCESSIBILITY_SUBMIT_TIMEOUT_MILLIS
          )
        }
        submitAttempted = accessibilitySubmitted
        if (!submitAttempted) {
          recordInputGateDecision(allowed = false, reason = "control_code_submit_failed")
          return false
        }
      } else {
        submitAttempted = true
      }
    }
    if (!submitAttempted) {
      recordInputGateDecision(allowed = false, reason = "control_code_submit_missing")
      return false
    }
    return when (waitForControlCodeSubmitTransition(phases)) {
      ControlCodeSubmitTransition.GENERATED_RESULT -> {
        markViewerInput("control_code_request_digits")
        true
      }

      ControlCodeSubmitTransition.RETURNED_NO_RESULT -> {
        recordInputGateDecision(allowed = false, reason = "control_code_submit_returned_no_result")
        false
      }

      ControlCodeSubmitTransition.STILL_ON_POPUP,
      ControlCodeSubmitTransition.TIMEOUT -> {
        recordInputGateDecision(allowed = false, reason = "control_code_submit_timeout")
        false
      }
    }
  }

  private suspend fun findControlCodeSubmitActionForRequest(
    phases: MutableMap<String, Long>,
    phase: String
  ): TicketViviPageAction? {
    return measureInputPhase(phases, phase) {
      val deadline = SystemClock.elapsedRealtime() + CONTROL_CODE_SUBMIT_FIND_TIMEOUT_MILLIS
      var action: TicketViviPageAction? = null
      while (SystemClock.elapsedRealtime() < deadline && action == null) {
        action = controlCodeRequestHierarchy()
          ?.let { hierarchy ->
            TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)?.also { surface ->
              rememberControlCodePopupSurface(surface)
            }?.submit
          }
        if (action == null) {
          delay(CONTROL_CODE_SUBMIT_FIND_POLL_MILLIS)
        }
      }
      action
    }
  }

  private suspend fun controlCodePopupStillPresentForCachedSubmit(
    phases: MutableMap<String, Long>,
    expectedDigits: String?
  ): Boolean {
    return measureInputPhase(phases, "verify_cached_submit_popup") {
      controlCodeRequestHierarchy()
        ?.let { hierarchy ->
          TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy) != null ||
            (
              TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy) &&
                (
                  expectedDigits == null ||
                    TicketViviPageEnforcer.controlCodeInputValueForHierarchy(hierarchy)
                      ?.filter { char -> char.isDigit() } == expectedDigits ||
                    TicketViviPageEnforcer.controlCodeInputValueLooseForHierarchy(hierarchy)
                      ?.filter { char -> char.isDigit() } == expectedDigits
                )
              )
        } == true
    }
  }

  private suspend fun waitForControlCodeSubmitTransition(phases: MutableMap<String, Long>): ControlCodeSubmitTransition {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadline = startedAtMillis + CONTROL_CODE_REQUEST_SUBMIT_TRANSITION_TIMEOUT_MILLIS
    var consecutiveTicketDetail = 0
    var sawPopup = false
    while (SystemClock.elapsedRealtime() < deadline) {
      val hierarchy = controlCodeRequestHierarchy()
      if (!hierarchy.isNullOrBlank()) {
        val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
        when (state) {
          TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
            phases["submit_transition"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            markControlCodeModeEntered("control_code_request_result_detected")
            return ControlCodeSubmitTransition.GENERATED_RESULT
          }

          TicketViviRecoveryState.TICKET_DETAIL -> {
            consecutiveTicketDetail += 1
            if (
              consecutiveTicketDetail >= CONTROL_CODE_REQUEST_NO_RESULT_TICKET_DETAIL_COUNT &&
              SystemClock.elapsedRealtime() - startedAtMillis >= CONTROL_CODE_REQUEST_NO_RESULT_GRACE_MILLIS
            ) {
              phases["submit_transition"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
              recordTicketEvent("control_code_request_not_generated", "ticket_detail_after_submit")
              return ControlCodeSubmitTransition.RETURNED_NO_RESULT
            }
          }

          TicketViviRecoveryState.CONTROL_CODE_POPUP -> {
            consecutiveTicketDetail = 0
            sawPopup = true
          }

          else -> {
            consecutiveTicketDetail = 0
          }
        }
        if (TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy)) {
          sawPopup = true
        }
        val value = TicketViviPageEnforcer.strictControlCodeResultValueForHierarchy(hierarchy)
        if (!value.isNullOrBlank()) {
          phases["submit_transition"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
          markControlCodeModeEntered("control_code_request_result_detected")
          recordTicketEvent("control_code_request_result_detected", value)
          return ControlCodeSubmitTransition.GENERATED_RESULT
        }
      }
      delay(CONTROL_CODE_REQUEST_POLL_MILLIS)
    }
    phases["submit_transition"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    return if (sawPopup) ControlCodeSubmitTransition.STILL_ON_POPUP else ControlCodeSubmitTransition.TIMEOUT
  }

  private suspend fun setControlCodeDigitsForRequest(digits: String, phases: MutableMap<String, Long>): Boolean {
    if (!openControlCodeInputFieldForRequest(phases)) {
      return false
    }

    var verifyFailed = false

    val focusedAccessibilitySet = measureInputPhase(phases, "set_digits_focused_accessibility") {
      PhoneAutomationServiceBridge.setTextInFocusedInput(
        expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
        text = digits,
        timeoutMillis = CONTROL_CODE_ACCESSIBILITY_SET_TEXT_TIMEOUT_MILLIS
      )
    }
    if (focusedAccessibilitySet) {
      delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
      if (verifyControlCodeDigitsEntered(digits, phases)) {
        return true
      }
      verifyFailed = true
    }

    val firstEditableSet = measureInputPhase(phases, "set_digits_first_editable_accessibility") {
      PhoneAutomationServiceBridge.setTextInFirstEditableInput(
        expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
        text = digits,
        timeoutMillis = CONTROL_CODE_ACCESSIBILITY_SET_TEXT_TIMEOUT_MILLIS
      )
    }
    if (firstEditableSet) {
      delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
      if (verifyControlCodeDigitsEntered(digits, phases)) {
        return true
      }
      verifyFailed = true
    }

    val paste = measureInputPhase(phases, "paste_digits") {
      runFastNonTouchInput(
        """
        input keyevent KEYCODE_MOVE_END
        for i in 1 2 3 4 5 6 7 8 9; do input keyevent KEYCODE_DEL; done
        cmd clipboard set text ${shellQuote(digits)} >/dev/null 2>&1 && input keyevent KEYCODE_PASTE
        """.trimIndent(),
        "control_code_request_paste_digits"
      )
    }
    if (paste.ok) {
      delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
      if (verifyControlCodeDigitsEntered(digits, phases)) {
        return true
      }
      verifyFailed = true
    }

    val clear = measureInputPhase(phases, "clear_input") {
      runFastNonTouchInput(
        "input keyevent KEYCODE_MOVE_END; for i in 1 2 3 4 5 6 7 8 9; do input keyevent KEYCODE_DEL; done",
        "control_code_request_clear_input"
      )
    }
    if (!clear.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_clear_failed")
      return false
    }
    val typed = measureInputPhase(phases, "type_digits") {
      runFastNonTouchInput("input text $digits", "control_code_request_type_digits")
    }
    if (typed.ok) {
      delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
      if (verifyControlCodeDigitsEntered(digits, phases)) {
        return true
      }
      if (controlCodeInputStillAvailableForRequest(phases, "verify_type_input_still_open")) {
        return true
      }
      verifyFailed = true
    }
    recordInputGateDecision(
      allowed = false,
      reason = if (verifyFailed) "control_code_input_verify_failed" else "control_code_input_set_failed"
    )
    return false
  }

  private suspend fun controlCodeInputStillAvailableForRequest(
    phases: MutableMap<String, Long>,
    phase: String
  ): Boolean {
    return measureInputPhase(phases, phase) {
      controlCodeRequestHierarchy()
        ?.let { hierarchy ->
          TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy) ||
            TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy) != null
        } == true
    }
  }

  private suspend fun openControlCodeInputFieldForRequest(phases: MutableMap<String, Long>): Boolean {
    val inputAction = cachedControlCodePopupSurface()?.input ?: measureInputPhase(phases, "find_input") {
      controlCodeRequestHierarchy()
        ?.let { hierarchy ->
          TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)?.also { surface ->
            rememberControlCodePopupSurface(surface)
          }?.input
        }
    }
    if (inputAction == null) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_missing")
      return false
    }
    val accessibilityOpened = measureInputPhase(phases, "open_input_accessibility") {
      PhoneAutomationServiceBridge.openFirstEditableInput(
        expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
        timeoutMillis = CONTROL_CODE_ACCESSIBILITY_OPEN_INPUT_TIMEOUT_MILLIS
      )
    }
    delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
    val focusedAfterAccessibility = if (accessibilityOpened) {
      measureInputPhase(phases, "verify_input_focused") {
        controlCodeRequestHierarchy()
          ?.let { hierarchy -> TicketViviPageEnforcer.isControlCodeInputFocused(hierarchy) } == true
      }
    } else {
      false
    }
    var tapped = false
    if (!accessibilityOpened || !focusedAfterAccessibility) {
      val refreshedInputAction = measureInputPhase(phases, "find_input_after_accessibility") {
        controlCodeRequestHierarchy()
          ?.let { hierarchy ->
            TicketViviPageEnforcer.controlCodeInputActionLooseForHierarchy(hierarchy)
              ?: TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)?.input
          }
      } ?: inputAction
      tapped = measureInputPhase(phases, "focus_input") {
        runFastNonTouchInput("input tap ${refreshedInputAction.x} ${refreshedInputAction.y}", "control_code_input_focus")
      }.ok
    }
    if (!accessibilityOpened && !tapped) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_focus_failed")
      return false
    }
    if (tapped) {
      delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
    }
    val popupStillPresent = measureInputPhase(phases, "verify_input_open") {
      controlCodeRequestHierarchy()
        ?.let { hierarchy ->
          TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy) != null ||
            TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy)
        } == true
    }
    if (!popupStillPresent && !accessibilityOpened && !tapped) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_focus_failed")
      return false
    }
    return true
  }

  private suspend fun verifyControlCodeDigitsEntered(
    digits: String,
    phases: MutableMap<String, Long>,
    allowHiddenValue: Boolean = false
  ): Boolean {
    val verified = measureInputPhase(phases, "verify_digits") {
      val hierarchy = controlCodeRequestHierarchy()
      if (hierarchy.isNullOrBlank()) {
        false
      } else {
        val inputValue = TicketViviPageEnforcer.controlCodeInputValueForHierarchy(hierarchy)
          ?: TicketViviPageEnforcer.controlCodeInputValueLooseForHierarchy(hierarchy)
        inputValue != null &&
          (inputValue == digits || inputValue.filter { char -> char.isDigit() } == digits || (allowHiddenValue && inputValue.isBlank()))
      }
    }
    if (!verified) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_verify_failed")
    }
    return verified
  }

  private suspend fun strictControlCodeResultVisibleForRequest(phases: MutableMap<String, Long>): Boolean {
    return measureInputPhase(phases, "check_result_after_input") {
      val hierarchy = controlCodeRequestHierarchy()
      !hierarchy.isNullOrBlank() &&
        TicketViviPageEnforcer.classifyForRecovery(hierarchy) == TicketViviRecoveryState.CONTROL_CODE_RESULT &&
        !TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy)
    }
  }

  private suspend fun waitForGeneratedControlCodeResult(phases: MutableMap<String, Long>): ControlCodeResultWaitOutcome {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadline = startedAtMillis + CONTROL_CODE_REQUEST_RESULT_TIMEOUT_MILLIS
    var consecutiveTicketDetail = 0
    while (SystemClock.elapsedRealtime() < deadline) {
      val hierarchy = controlCodeRequestHierarchy()
      if (!hierarchy.isNullOrBlank()) {
        val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
        viviStateMemory.record(
          state = state,
          ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(hierarchy),
          source = "control_code_request",
          reason = "control_code_request_wait"
        )
        when (state) {
          TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
            val value = TicketViviPageEnforcer.controlCodeResultValueForHierarchy(hierarchy).orEmpty()
            phases["wait_result"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            markControlCodeModeEntered("control_code_request_result_detected")
            recordTicketEvent("control_code_request_result_detected", value.ifBlank { "image_only" })
            return ControlCodeResultWaitOutcome(
              generated = GeneratedControlCodeResult(value = value, hierarchy = hierarchy),
              failureReason = ""
            )
          }

          TicketViviRecoveryState.TICKET_DETAIL -> {
            consecutiveTicketDetail += 1
            if (
              consecutiveTicketDetail >= CONTROL_CODE_REQUEST_NO_RESULT_TICKET_DETAIL_COUNT &&
              SystemClock.elapsedRealtime() - startedAtMillis >= CONTROL_CODE_REQUEST_NO_RESULT_GRACE_MILLIS
            ) {
              phases["wait_result"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
              recordTicketEvent("control_code_request_not_generated", "ticket_detail_after_submit")
              return ControlCodeResultWaitOutcome(failureReason = "control_code_not_generated")
            }
          }

          else -> {
            consecutiveTicketDetail = 0
          }
        }
        val value = TicketViviPageEnforcer.strictControlCodeResultValueForHierarchy(hierarchy)
        if (!value.isNullOrBlank()) {
          phases["wait_result"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
          markControlCodeModeEntered("control_code_request_result_detected")
          recordTicketEvent("control_code_request_result_detected", value)
          return ControlCodeResultWaitOutcome(
            generated = GeneratedControlCodeResult(value = value, hierarchy = hierarchy),
            failureReason = ""
          )
        }
      }
      delay(CONTROL_CODE_REQUEST_POLL_MILLIS)
    }
    phases["wait_result"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    return ControlCodeResultWaitOutcome(failureReason = "control_code_result_timeout")
  }

  private suspend fun captureGeneratedControlCodeImageBytes(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    generatedHierarchy: String
  ): ControlCodeImageCaptureOutcome {
    markControlCodeRequestPhase(phases, "capture_started", requestStartedAtMillis)
    val captureStartedAtMillis = SystemClock.elapsedRealtime()
    var session: ControlCodePngCaptureSession? = null
    var cleanupStart: FastControlCodeCleanupStart? = null
    try {
      session = startControlCodePngCaptureDirect()
      if (session == null) {
        cleanupStart = beginControlCodeImageCaptureFailureCleanup(generatedHierarchy, phases, requestStartedAtMillis)
        recordTicketEvent("control_code_image_capture_failed", "start_failed")
        return ControlCodeImageCaptureOutcome(cleanupStart = cleanupStart, error = "start_failed")
      }

      val signature = readControlCodePngSignature(session)
      if (signature == null || !looksLikePng(signature)) {
        cleanupStart = beginGeneratedControlCodeResultFastClose(
          generatedHierarchy = generatedHierarchy,
          reason = "control_code_request_capture_failed",
          phases = phases,
          requestStartedAtMillis = requestStartedAtMillis
        )
        recordTicketEvent("control_code_image_capture_failed", "invalid_png_header")
        return ControlCodeImageCaptureOutcome(cleanupStart = cleanupStart, error = "invalid_png_header")
      }

      cleanupStart = beginGeneratedControlCodeResultFastClose(
        generatedHierarchy = generatedHierarchy,
        reason = "control_code_request_complete",
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
      )

      val result = readRemainingControlCodePngBytes(session, signature)
      val bytes = result.bytes
      if (bytes == null || !looksLikePng(bytes)) {
        recordTicketEvent("control_code_image_capture_failed", result.error.ifBlank { "invalid_png" })
        return ControlCodeImageCaptureOutcome(cleanupStart = cleanupStart, error = result.error.ifBlank { "invalid_png" })
      }
      markControlCodeRequestPhase(phases, "capture_bytes_ready", requestStartedAtMillis)
      recordTicketEvent("control_code_image_capture_ready", "bytes=${bytes.size}")
      return ControlCodeImageCaptureOutcome(bytes = bytes, cleanupStart = cleanupStart)
    } catch (cancelled: CancellationException) {
      if (cleanupStart == null) {
        cleanupStart = beginControlCodeImageCaptureFailureCleanup(generatedHierarchy, phases, requestStartedAtMillis)
      }
      throw cancelled
    } catch (error: Throwable) {
      if (cleanupStart == null) {
        cleanupStart = beginControlCodeImageCaptureFailureCleanup(generatedHierarchy, phases, requestStartedAtMillis)
      }
      val message = error.message?.take(120).orEmpty().ifBlank { error::class.java.simpleName }
      recordTicketEvent("control_code_image_capture_failed", message)
      return ControlCodeImageCaptureOutcome(cleanupStart = cleanupStart, error = message)
    } finally {
      phases["capture_image"] = (SystemClock.elapsedRealtime() - captureStartedAtMillis).coerceAtLeast(0L)
      session?.let { closeControlCodePngCaptureSession(it) }
    }
  }

  private suspend fun startControlCodePngCaptureDirect(): ControlCodePngCaptureSession? = withContext(Dispatchers.IO) {
    runCatching {
      val process = ProcessBuilder("su", "-c", "screencap -p")
        .redirectErrorStream(false)
        .start()
      ControlCodePngCaptureSession(
        process = process,
        input = BufferedInputStream(process.inputStream)
      )
    }.getOrElse { error ->
      Log.w(TAG, "ticket_control_code_screencap_start_failed", error)
      null
    }
  }

  private suspend fun readControlCodePngSignature(session: ControlCodePngCaptureSession): ByteArray? = withContext(Dispatchers.IO) {
    withTimeoutOrNull(CONTROL_CODE_REQUEST_CAPTURE_HEADER_TIMEOUT_MILLIS) {
      readExactControlCodePngBytes(session.input, PNG_SIGNATURE.size)
    }
  }

  private suspend fun readRemainingControlCodePngBytes(
    session: ControlCodePngCaptureSession,
    signature: ByteArray
  ): ControlCodePngCaptureResult = withContext(Dispatchers.IO) {
    val remaining = withTimeoutOrNull(CONTROL_CODE_REQUEST_CAPTURE_TIMEOUT_MILLIS) {
      session.input.readBytes()
    }
    val exitCode = withTimeoutOrNull(SECURE_CAPTURE_PROBE_EXIT_TIMEOUT_MILLIS) {
      session.process.waitFor()
    }
    if (remaining == null || exitCode == null) {
      runCatching { session.process.destroyForcibly() }
      return@withContext ControlCodePngCaptureResult(error = "timeout")
    }
    val stderr = runCatching {
      session.process.errorStream.bufferedReader().use { it.readText() }.trim()
    }.getOrDefault("")
    if (exitCode != 0) {
      return@withContext ControlCodePngCaptureResult(error = stderr.ifBlank { "exit=$exitCode" })
    }
    ControlCodePngCaptureResult(bytes = signature + remaining)
  }

  private fun readExactControlCodePngBytes(input: BufferedInputStream, size: Int): ByteArray? {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
      val read = input.read(buffer, offset, size - offset)
      if (read < 0) {
        return null
      }
      offset += read
    }
    return buffer
  }

  private fun closeControlCodePngCaptureSession(session: ControlCodePngCaptureSession) {
    runCatching { session.input.close() }
    if (session.process.isAlive) {
      runCatching { session.process.destroyForcibly() }
    }
  }

  private suspend fun encodeControlCodeImageBase64(imageBytes: ByteArray?): String? {
    return withContext(Dispatchers.Default) {
      imageBytes
        ?.takeIf { it.size > 32 && looksLikePng(it) }
        ?.let { Base64.getEncoder().encodeToString(it) }
    }
  }

  private fun looksLikePng(bytes: ByteArray): Boolean {
    return bytes.size >= PNG_SIGNATURE.size &&
      PNG_SIGNATURE.indices.all { index -> bytes[index] == PNG_SIGNATURE[index] }
  }

  private suspend fun beginControlCodeImageCaptureFailureCleanup(
    generatedHierarchy: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodeCleanupStart {
    return beginGeneratedControlCodeResultFastClose(
      generatedHierarchy = generatedHierarchy,
      reason = "control_code_request_capture_failed",
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis
    )
  }

  private suspend fun requireControlCodePopupForRemoteKey(): Boolean {
    if (SystemClock.elapsedRealtime() < controlCodePopupReadyUntilMillis) {
      return true
    }
    val hierarchy = controlCodeRequestHierarchy()
    if (hierarchy.isNullOrBlank()) {
      recordInputGateDecision(allowed = false, reason = "control_code_popup_check_failed")
      Log.w(TAG, "ticket_remote_key_blocked reason=control_code_popup_check_failed")
      return false
    }
    if (!TicketViviPageEnforcer.isControlCodePopup(hierarchy)) {
      val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
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
    if (!TicketViviPageEnforcer.isControlCodeInputFocused(hierarchy)) {
      val action = TicketViviPageEnforcer.controlCodeInputActionForHierarchy(hierarchy)
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

  private suspend fun beginGeneratedControlCodeResultFastClose(
    generatedHierarchy: String,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodeCleanupStart {
    val startedAtMillis = SystemClock.elapsedRealtime()
    markControlCodeRequestPhase(phases, "cleanup_started", requestStartedAtMillis)
    recordTicketEvent("control_code_fast_cleanup_phase", "result_ready_for_delivery")
    updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)

    val detectedState = if (generatedHierarchy.isBlank()) {
      TicketViviRecoveryState.UNKNOWN_VIVI
    } else {
      TicketViviRecoveryState.CONTROL_CODE_RESULT
    }
    if (detectedState != TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      recordTicketEvent("control_code_fast_cleanup_fallback", "state=${detectedState.name}")
      return FastControlCodeCleanupStart(
        startedAtMillis = startedAtMillis,
        closeAction = "none",
        action = null,
        closeSucceeded = false,
        fallbackState = detectedState
      )
    }
    rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)

    val action = controlCodeResultGeometryCloseAction()
    val closeSucceeded = sendFastGeneratedResultCloseTap(action, phases, requestStartedAtMillis, "control_code_fast_cleanup_close")
    if (closeSucceeded) {
      requestKeyFrame("control_code_fast_cleanup_close")
      markControlCodeRequestPhase(phases, "cleanup_keyframe_requested", requestStartedAtMillis)
      recordTicketEvent("control_code_fast_cleanup_phase", "keyframe_requested")
    }
    return FastControlCodeCleanupStart(
      startedAtMillis = startedAtMillis,
      closeAction = action.reason,
      action = action,
      closeSucceeded = closeSucceeded,
      fallbackState = if (closeSucceeded) null else TicketViviRecoveryState.CONTROL_CODE_RESULT
    )
  }

  private suspend fun finishGeneratedControlCodeResultFastCleanup(
    cleanupStart: FastControlCodeCleanupStart,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    if (!cleanupStart.closeSucceeded) {
      recordTicketEvent("control_code_fast_cleanup_fallback", "surface=${cleanupStart.fallbackState?.name ?: "close_failed"}")
      return runControlExitCleanup(reason)
    }

    val firstState = waitForCleanTicketSurfaceFast(
      reason = reason,
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS / 2
    )
    if (firstState == TicketViviRecoveryState.TICKET_DETAIL) {
      markControlCodeRequestPhase(phases, "phone_raw_recovered", requestStartedAtMillis)
      return completeFastVerifiedTicketDetailControlExitCleanup(reason, cleanupStart.closeAction, cleanupStart.startedAtMillis, "surface_clean")
    }

    if (firstState == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      recordTicketEvent("control_code_fast_cleanup_phase", "retry_close_after_dirty_surface")
      val retrySucceeded = sendFastGeneratedResultCloseTap(
        action = cleanupStart.action ?: controlCodeResultGeometryCloseAction(),
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        commandReason = "control_code_fast_cleanup_close_retry"
      )
      if (retrySucceeded) {
        requestKeyFrame("control_code_fast_cleanup_close")
        val retryState = waitForCleanTicketSurfaceFast(
          reason = reason,
          phases = phases,
          requestStartedAtMillis = requestStartedAtMillis,
          timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS / 2
        )
        if (retryState == TicketViviRecoveryState.TICKET_DETAIL) {
          markControlCodeRequestPhase(phases, "phone_raw_recovered", requestStartedAtMillis)
          return completeFastVerifiedTicketDetailControlExitCleanup(reason, cleanupStart.closeAction, cleanupStart.startedAtMillis, "surface_clean_after_retry")
        }
      }
    }

    recordTicketEvent("control_code_fast_cleanup_fallback", "surface=${firstState?.name ?: "unavailable"}")
    return runControlExitCleanup(reason)
  }

  private suspend fun sendFastGeneratedResultCloseTap(
    action: TicketViviPageAction,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    commandReason: String
  ): Boolean {
    val tap = measureInputPhase(phases, commandReason) {
      runFastNonTouchInput("input tap ${action.x} ${action.y}", commandReason)
    }
    if (!tap.ok) {
      recordTicketEvent("control_code_fast_cleanup_close_failed", "reason=$commandReason duration_ms=${tap.durationMs}")
      return false
    }
    lastControlExitDirectCloseAtMillis = SystemClock.elapsedRealtime()
    markControlCodeRequestPhase(phases, "cleanup_close_tap_sent", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "close_tap_sent", requestStartedAtMillis)
    recordTicketEvent("control_code_fast_cleanup_phase", "close_tap_sent action=${action.reason}")
    return true
  }

  private fun controlCodeResultGeometryCloseAction(): TicketViviPageAction {
    val (width, height) = currentDisplaySize()
    val x = (width * CONTROL_EXIT_RESULT_CLOSE_X_FRACTION).roundToInt()
    val y = (height * CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION).roundToInt()
    return TicketViviPageAction(
      x = x,
      y = y,
      reason = "geometry_close_control_code_result"
    )
  }

  private suspend fun waitForCleanTicketSurfaceFast(
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    timeoutMillis: Long
  ): TicketViviRecoveryState? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadlineMillis = startedAtMillis + timeoutMillis.coerceAtLeast(CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS)
    var lastState: TicketViviRecoveryState? = null
    var fastScreencapAttempted = false
    while (SystemClock.elapsedRealtime() <= deadlineMillis) {
      val accessibilityState = ticketAutopilot.observeFastState("control_code_fast_cleanup:$reason")?.state
      if (accessibilityState == TicketViviRecoveryState.TICKET_DETAIL) {
        lastControlExitCleanupDetectorSource = "accessibility_fast_cleanup"
        lastControlExitCleanupSurfaceProbeResult = "accessibility_clean"
        markControlCodeRequestPhase(phases, "cleanup_clean_surface", requestStartedAtMillis)
        markControlCodeRequestPhase(phases, "raw_ticket_fast_proof", requestStartedAtMillis)
        recordTicketEvent(
          "control_code_fast_cleanup_phase",
          "accessibility_clean duration_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)}"
        )
        return TicketViviRecoveryState.TICKET_DETAIL
      }
      if (accessibilityState == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
        lastState = TicketViviRecoveryState.CONTROL_CODE_RESULT
      }
      val state = if (!fastScreencapAttempted) {
        fastScreencapAttempted = true
        controlExitGeneratedResultFastScreencapProbe(
          reason = reason,
          detectorSource = "fast_cleanup_screencap"
        )
      } else {
        null
      }
      lastState = state
      if (state == TicketViviRecoveryState.TICKET_DETAIL) {
        markControlCodeRequestPhase(phases, "cleanup_clean_surface", requestStartedAtMillis)
        markControlCodeRequestPhase(phases, "raw_ticket_fast_proof", requestStartedAtMillis)
        recordTicketEvent(
          "control_code_fast_cleanup_phase",
          "clean_surface duration_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)}"
        )
        return state
      }
      if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
      }
      delay(CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS)
    }
    phases["cleanup_fast_verify"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    return lastState
  }

  private fun completeFastVerifiedTicketDetailControlExitCleanup(
    reason: String,
    closeAction: String,
    startedAtMillis: Long,
    firstVerificationResult: String
  ): Boolean {
    recordTicketEvent("control_code_fast_cleanup_phase", "cleanup_complete")
    return completeControlExitCleanup(
      reason = reason,
      detectedState = TicketViviRecoveryState.CONTROL_CODE_RESULT.name,
      closeAction = closeAction,
      startedAtMillis = startedAtMillis,
      verificationResult = firstVerificationResult,
      freshFrameRequested = true
    )
  }

  private suspend fun closeControlCodePopupFromRemote(reason: String): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val hierarchy = controlExitHierarchy()
    if (hierarchy.isNullOrBlank()) {
      recordInputGateDecision(allowed = false, reason = "control_code_popup_check_failed")
      Log.w(TAG, "ticket_control_code_close_failed reason=$reason hierarchy_unavailable")
      return runControlExitCleanup(reason)
    }
    val detectedState = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    if (detectedState == TicketViviRecoveryState.TICKET_DETAIL) {
      return completeVerifiedTicketDetailControlExitCleanup(
        reason = reason,
        detectedState = detectedState.name,
        closeAction = "none",
        startedAtMillis = startedAtMillis,
        firstVerificationResult = detectedState.name
      )
    }
    val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
    if (action == null) {
      recordInputGateDecision(allowed = false, reason = "control_code_close_missing")
      Log.i(TAG, "ticket_control_code_close_blocked reason=control_code_close_missing")
      return runControlExitCleanup(reason)
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

  private suspend fun recoverControlCodeRequestSurface(reason: String): Boolean {
    return closeControlCodePopupFromRemote(reason)
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

  private suspend fun tap(inputId: String?, encodedX: Int, encodedY: Int, snapTarget: String? = null) {
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
    if (!snapTarget.isNullOrBlank()) {
      if (snapTarget != SNAP_TARGET_CONTROL_CODE_BUTTON) {
        recordInputGateDecision(allowed = false, reason = "remote_snap_target_unsupported")
        ignoreProtectedRemoteInput("remote_snap_target_unsupported")
        sendInputResult(inputId, "tap", false, "remote_snap_target_unsupported", startedAtMillis, phases)
        return
      }
      val target = measureInputPhase(phases, "snap_target") {
        controlCodeButtonSnapTarget(size, encodedX, encodedY)
      } ?: run {
        sendInputResult(inputId, "tap", false, inputGateReason, startedAtMillis, phases)
        return
      }
      if (target.noOp) {
        recordInputGateDecision(allowed = true, reason = target.reason)
        recordControlCodeSnapAttempt(
          rawX = x,
          rawY = y,
          candidateZone = target.candidateZone,
          snapTarget = snapTarget,
          accepted = true,
          reason = target.reason,
          detectedButtonBounds = target.detectedButtonBounds
        )
        sendInputResult(inputId, "tap", true, target.reason, startedAtMillis, phases)
        return
      }
      recordInputGateDecision(allowed = true, reason = target.reason)
      markControlCodeTransition(target.reason)
      markViewerInput("control_code_snap_tap")
      val tap = measureInputPhase(phases, "command") {
        runFastNonTouchInput("input tap ${target.x} ${target.y}", "remote_control_code_snap_tap")
      }
      recordControlCodeSnapAttempt(
        rawX = x,
        rawY = y,
        candidateZone = target.candidateZone,
        snapTarget = snapTarget,
        accepted = tap.ok,
        reason = if (tap.ok) target.reason else "root_command_failed",
        finalX = target.x,
        finalY = target.y,
        detectedButtonBounds = target.detectedButtonBounds
      )
      if (tap.ok) {
        schedulePostRemoteTapForegroundCheck()
      }
      sendInputResult(
        inputId,
        "tap",
        tap.ok,
        if (tap.ok) target.reason else "root_command_failed",
        startedAtMillis,
        phases
      )
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

  private fun markControlCodeRequestPhase(
    phases: MutableMap<String, Long>,
    phase: String,
    requestStartedAtMillis: Long
  ) {
    phases[phase] = if (requestStartedAtMillis > 0L) {
      (SystemClock.elapsedRealtime() - requestStartedAtMillis).coerceAtLeast(0L)
    } else {
      0L
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

  private fun sendControlCodeResult(
    requestId: String,
    ok: Boolean,
    reason: String,
    value: String,
    imageMime: String?,
    imageBase64: String?,
    startedAtMillis: Long,
    phases: Map<String, Long>,
    cleanupPending: Boolean
  ) {
    val nowMillis = SystemClock.elapsedRealtime()
    val totalDurationMillis = if (startedAtMillis > 0L) {
      (nowMillis - startedAtMillis).coerceAtLeast(0L)
    } else {
      0L
    }
    lastControlCodeRequestId = requestId.takeIf { it.isNotBlank() }
    lastControlCodeRequestStatus = if (ok) "succeeded" else "failed"
    lastControlCodeRequestReason = reason
    lastControlCodeRequestValue = value.ifBlank { null }
    lastControlCodeRequestDurationMillis = totalDurationMillis
    lastControlCodeRequestCompletedAtMillis = nowMillis
    val phaseJson = buildJsonObject {
      phases.forEach { (name, duration) -> put(name, duration) }
    }
    val message = buildJsonObject {
      put("type", "control_code_result")
      put("requestId", requestId)
      put("ok", ok)
      put("accepted", ok)
      put("reason", reason)
      put("value", value)
      put("imageMime", imageMime.orEmpty())
      put("imageBase64", imageBase64.orEmpty())
      put("totalDurationMillis", totalDurationMillis)
      put("cleanupPending", cleanupPending)
      put("phases", phaseJson)
    }.toString()
    rememberControlCodeResult(requestId, message)
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "control_code_result",
      "$requestId ok=$ok reason=$reason value=${value.ifBlank { "empty" }} duration_ms=$totalDurationMillis"
    )
    broadcastStatus()
  }

  private fun sendControlCodeCleanup(
    requestId: String,
    ok: Boolean,
    reason: String,
    startedAtMillis: Long
  ) {
    val nowMillis = SystemClock.elapsedRealtime()
    val totalDurationMillis = if (startedAtMillis > 0L) {
      (nowMillis - startedAtMillis).coerceAtLeast(0L)
    } else {
      0L
    }
    if (!ok) {
      lastControlCodeRequestReason = reason
    }
    val message = buildJsonObject {
      put("type", "control_code_cleanup_complete")
      put("requestId", requestId)
      put("ok", ok)
      put("accepted", ok)
      put("reason", reason)
      put("totalDurationMillis", totalDurationMillis)
    }.toString()
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "control_code_cleanup_complete",
      "$requestId ok=$ok reason=$reason duration_ms=$totalDurationMillis"
    )
    broadcastStatus()
  }

  private fun sendCachedControlCodeResult(requestId: String): Boolean {
    val id = requestId.takeIf { it.isNotBlank() } ?: return false
    val message = synchronized(recentControlCodeResultMessages) {
      recentControlCodeResultMessages[id]
    } ?: return false
    duplicateControlCodeResultCount += 1
    lastDuplicateControlCodeRequestId = id
    lastDuplicateControlCodeResultAtMillis = SystemClock.elapsedRealtime()
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent("control_code_result_duplicate", id)
    broadcastStatus()
    return true
  }

  private fun rememberControlCodeResult(requestId: String, message: String) {
    val id = requestId.takeIf { it.isNotBlank() } ?: return
    synchronized(recentControlCodeResultMessages) {
      if (!recentControlCodeResultMessages.containsKey(id)) {
        recentControlCodeResultOrder.addLast(id)
      }
      recentControlCodeResultMessages[id] = message
      while (recentControlCodeResultOrder.size > RECENT_CONTROL_CODE_RESULT_CACHE_SIZE) {
        val removed = recentControlCodeResultOrder.removeFirst()
        recentControlCodeResultMessages.remove(removed)
      }
    }
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

  private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
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

  private fun promoteToForeground() {
    startForeground(NOTIFICATION_ID, foregroundNotification())
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
    private const val SESSION_START_TIMEOUT_MILLIS = 12_000L
    const val SERVER_VERSION = "ticket-stream-2026-05-10-control-code-fast-delivery-v118"
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
    private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 1_500L
    private const val SECURE_CAPTURE_PROBE_START_FRAME_COUNT = 3L
    private const val SECURE_CAPTURE_PROBE_DELAY_MILLIS = 700L
    private const val SECURE_CAPTURE_PROBE_MIN_INTERVAL_MILLIS = 8_000L
    private const val SECURE_CAPTURE_PROBE_TIMEOUT_MILLIS = 1_500L
    private const val SECURE_CAPTURE_PROBE_EXIT_TIMEOUT_MILLIS = 250L
    private const val SECURE_CAPTURE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = 10_000L
    private const val SEND_BITRATE_WINDOW_MILLIS = 1_000L
    private const val VIDEO_CLIENT_SLOW_WRITE_MILLIS = 250L
    private const val VIDEO_CLIENT_SLOW_CLOSE_MILLIS = 3_000L
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
    private const val ROOT_FFMPEG_H264_START_AUTOPILOT_MAX_STEPS = 8
    private const val ROOT_FFMPEG_H264_START_AUTOPILOT_TIMEOUT_MILLIS = 18_000L
    private const val ROOT_FFMPEG_RAW_FRAME_SUPPRESSION_FAILURE_THRESHOLD = 30L
    private const val CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS = 120L
    private const val CONTROL_CODE_POPUP_READY_CACHE_MILLIS = 2_000L
    private const val CONTROL_CODE_ACCESSIBILITY_OPEN_INPUT_TIMEOUT_MILLIS = 350L
    private const val CONTROL_CODE_ACCESSIBILITY_SET_TEXT_TIMEOUT_MILLIS = 450L
    private const val CONTROL_CODE_ACCESSIBILITY_SUBMIT_TIMEOUT_MILLIS = 450L
    private const val CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 1_100L
    private const val CONTROL_CODE_FAST_POPUP_TIMEOUT_MILLIS = 2_400L
    private const val CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS = 4_000L
    private const val CONTROL_CODE_FAST_RESULT_SETTLE_MILLIS = 650L
    private const val CONTROL_CODE_FAST_POLL_MILLIS = 120L
    private const val CONTROL_CODE_FAST_BUTTON_X_FRACTION = 0.23f
    private const val CONTROL_CODE_FAST_BUTTON_Y_FRACTION = 0.136f
    private const val CONTROL_CODE_FAST_SHIFTED_OK_X_FRACTION = 0.738f
    private const val CONTROL_CODE_FAST_SHIFTED_OK_Y_FRACTION = 0.422f
    private const val CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = 15_000L
    private const val CONTROL_CODE_REQUEST_PREPARE_MAX_STEPS = 5
    private const val CONTROL_CODE_REQUEST_PREPARE_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_REQUEST_HIERARCHY_TIMEOUT_MILLIS = 6_000L
    private const val CONTROL_CODE_REQUEST_POPUP_TIMEOUT_MILLIS = 4_000L
    private const val CONTROL_CODE_REQUEST_SUBMIT_TRANSITION_TIMEOUT_MILLIS = 3_500L
    private const val CONTROL_CODE_SUBMIT_FIND_TIMEOUT_MILLIS = 1_200L
    private const val CONTROL_CODE_SUBMIT_FIND_POLL_MILLIS = 120L
    private const val CONTROL_CODE_REQUEST_RESULT_TIMEOUT_MILLIS = 12_000L
    private const val CONTROL_CODE_REQUEST_POLL_MILLIS = 180L
    private const val CONTROL_CODE_REQUEST_CAPTURE_HEADER_TIMEOUT_MILLIS = 1_500L
    private const val CONTROL_CODE_REQUEST_CAPTURE_TIMEOUT_MILLIS = 6_000L
    private const val CONTROL_CODE_REQUEST_NO_RESULT_GRACE_MILLIS = 900L
    private const val CONTROL_CODE_REQUEST_NO_RESULT_TICKET_DETAIL_COUNT = 2
    private const val CONTROL_CODE_SNAP_MEMORY_MAX_AGE_MILLIS = 5_000L
    private const val CONTROL_CODE_SNAP_UNSAFE_STATE_MEMORY_MAX_AGE_MILLIS = 10_000L
    private const val RECENT_INPUT_RESULT_CACHE_SIZE = 80
    private const val RECENT_CONTROL_CODE_RESULT_CACHE_SIZE = 80
    private const val SNAP_TARGET_CONTROL_CODE_BUTTON = "control_code_button"
    private val CONTROL_CODE_REQUEST_DIGITS_REGEX = Regex("""^[0-9]{2,9}$""")
    private const val CONTROL_CODE_SOFT_CHECK_DELAY_MILLIS = 200L
    private const val CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS = 10_000L
    private const val CONTROL_CODE_SOFT_CHECK_MAX_STEPS = 2
    private const val CONTROL_CODE_EXIT_VERIFY_SETTLE_MILLIS = 220L
    private const val CONTROL_CODE_EXIT_VERIFY_TIMEOUT_MILLIS = 1_200L
    private const val CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 900L
    private const val CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS = 75L
    private const val CONTROL_EXIT_FAST_SCREENCAP_TIMEOUT_MILLIS = 900L
    private const val CONTROL_EXIT_SECOND_VERIFY_DELAY_MILLIS = 260L
    private const val CONTROL_EXIT_RECENT_DIRECT_CLOSE_MILLIS = 1_500L
    private const val TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS = 1_100L
    private const val CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS = 4_500L
    private const val CONTROL_EXIT_ACCESSIBILITY_CLOSE_TIMEOUT_MILLIS = 450L
    private const val CONTROL_EXIT_SURFACE_OBSERVE_TIMEOUT_MILLIS = 1_500L
    private const val CONTROL_EXIT_SURFACE_OBSERVE_RETRY_MILLIS = 120L
    private const val CONTROL_EXIT_FINAL_CONFIRM_DELAY_MILLIS = 350L
    private const val CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS = 12_000L
    private const val CONTROL_EXIT_RESULT_CLOSE_X_FRACTION = 0.82f
    private const val CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION = 0.592f
    private const val CONTROL_EXIT_DUPLICATE_SUCCESS_KEEP_MILLIS = 15_000L
    private const val CONTROL_EXIT_CLEANUP_MAX_STEPS = 2
    private const val CONTROL_EXIT_CLEANUP_TIMEOUT_MILLIS = 1_800L
    private const val CONTROL_EXIT_SURFACE_PROBE_WIDTH = 180
    private const val CONTROL_EXIT_SURFACE_PROBE_CACHE_MILLIS = 150L
    private const val CONTROL_EXIT_RESULT_BAR_TOP_FRACTION = 0.535f
    private const val CONTROL_EXIT_RESULT_BAR_BOTTOM_FRACTION = 0.595f
    private const val CONTROL_EXIT_RESULT_BAR_DARK_LUMINANCE = 80
    private const val CONTROL_EXIT_RESULT_BAR_DARK_RATIO = 0.35
    private const val CONTROL_EXIT_RESULT_BAR_MAX_MEAN = 135.0
    private const val CONTROL_EXIT_RESULT_BAR_BAND_DARK_RATIO = 0.45
    private const val CONTROL_EXIT_RESULT_BAR_BAND_MAX_MEAN = 115.0
    private val PNG_SIGNATURE = byteArrayOf(
      0x89.toByte(),
      0x50,
      0x4e,
      0x47,
      0x0d,
      0x0a,
      0x1a,
      0x0a
    )
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
    private val CONTROL_CODE_SUBMIT_SELECTORS = listOf(
      PhoneAutomationSelector(contentDescription = "OK"),
      PhoneAutomationSelector(text = "OK"),
      PhoneAutomationSelector(contentDescription = "Ok"),
      PhoneAutomationSelector(text = "Ok"),
      PhoneAutomationSelector(contentDescription = "Labi"),
      PhoneAutomationSelector(text = "Labi"),
      PhoneAutomationSelector(contentDescription = "Apstiprināt"),
      PhoneAutomationSelector(text = "Apstiprināt"),
      PhoneAutomationSelector(contentDescription = "Apstiprinat"),
      PhoneAutomationSelector(text = "Apstiprinat")
    )
    private fun controlCodeSubmitSelectors(): List<PhoneAutomationSelector> = CONTROL_CODE_SUBMIT_SELECTORS
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
    private const val REMOTE_QUICK_CLAIM_MAX_X_FRACTION = 0.25f
    private const val REMOTE_QUICK_CLAIM_MAX_Y_FRACTION = 0.25f
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
  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover">
  <meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate, max-age=0">
  <meta http-equiv="Pragma" content="no-cache">
  <meta http-equiv="Expires" content="0">
  <title>Ticket</title>
  <link rel="icon" href="data:,">
  <style>
    :root { color-scheme: dark; font-family: system-ui, sans-serif; background: #05070a; color: #f3f7fb; --ticket-stage-height: 100vh; --ticket-controls-offset: 16px; }
    * { box-sizing: border-box; }
    html { min-height: 100%; background: #05070a; touch-action: manipulation; }
    body { margin: 0; width: 100%; min-height: 100%; overflow-x: hidden; overflow-y: auto; background: #05070a; overscroll-behavior-y: contain; scroll-snap-type: y proximity; -webkit-overflow-scrolling: touch; touch-action: manipulation; }
    main { width: 100%; min-height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset) + var(--ticket-stage-height)); background: #05070a; }
    .stream-page { position: relative; width: 100vw; height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset)); min-height: calc(var(--ticket-stage-height) + var(--ticket-controls-offset)); background: #05070a; scroll-snap-align: start; }
    .stream-stage { position: fixed; inset: 0; z-index: 2; width: 100vw; height: var(--ticket-stage-height); min-height: var(--ticket-stage-height); overflow: hidden; background: #05070a; isolation: isolate; contain: layout paint; overscroll-behavior: none; touch-action: pan-y; opacity: 1; visibility: visible; transition: opacity 140ms ease, visibility 140ms ease; }
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
    canvas { position: absolute; left: 50%; top: 50%; display: block; width: var(--stream-css-width, 100vw); height: var(--stream-css-height, var(--ticket-stage-height)); max-width: none; max-height: none; background: #000; opacity: 0; transform: translate3d(-50%, -50%, 0); transition: opacity 160ms ease; image-rendering: crisp-edges; image-rendering: pixelated; touch-action: pan-y; user-select: none; -webkit-user-select: none; -webkit-touch-callout: none; -webkit-tap-highlight-color: transparent; }
    body[data-stream-ready="true"] canvas { opacity: 1; }
    #idleTimer { min-width: 58px; padding: 4px 8px; border-radius: 999px; background: rgba(10, 13, 18, .72); border: 1px solid rgba(185, 194, 207, .26); color: #edf3fb; font-size: 12px; line-height: 1.2; text-align: center; pointer-events: none; backdrop-filter: blur(6px); }
    #idleTimer[hidden] { display: none; }
    #idleTimer.urgent { color: #ffd6d6; border-color: rgba(255, 115, 115, .45); background: rgba(70, 18, 18, .74); }
    #status { max-width: min(420px, calc(100vw - 32px)); color: #b9c2cf; font-size: 13px; line-height: 1.35; text-align: center; pointer-events: none; }
    #status:empty { display: none; }
    .code-panel { width: min(420px, calc(100vw - 40px)); display: grid; gap: 10px; border: 1px solid #1d2a3c; border-radius: 8px; padding: 14px; background: #0d1724; }
    .code-panel label { display: grid; gap: 7px; color: #cdd7e6; font-size: 13px; font-weight: 700; }
    .code-panel input { min-height: 46px; border: 1px solid #37445a; border-radius: 6px; padding: 0 12px; background: #111b2a; color: #f3f7fb; font: inherit; font-size: 22px; text-align: center; font-variant-numeric: tabular-nums; }
    .code-actions { display: flex; gap: 8px; align-items: center; }
    .code-actions .primary { flex: 1 1 auto; }
    .code-result { position: relative; min-height: 90px; display: grid; justify-items: center; gap: 8px; border: 1px solid #29405b; border-radius: 8px; padding: 12px 40px 12px 12px; background: #09111d; text-align: center; }
    .code-result[hidden] { display: none; }
    .code-result img { width: min(100%, 320px); max-height: 360px; object-fit: contain; border-radius: 8px; background: #fff; }
    .code-result-value { overflow-wrap: anywhere; font-size: 26px; font-weight: 760; font-variant-numeric: tabular-nums; }
    .code-close { position: absolute; top: 8px; right: 8px; width: 28px; min-width: 28px; height: 28px; min-height: 28px; display: grid; place-items: center; border-radius: 999px; padding: 0; }
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
      <form id="codeForm" class="code-panel">
        <label>
          <span>Control code digits</span>
          <input id="codeDigits" type="text" inputmode="numeric" pattern="[0-9]*" minlength="2" maxlength="9" autocomplete="one-time-code">
        </label>
        <div class="code-actions">
          <button id="codeSubmit" type="submit" class="primary">Request code</button>
        </div>
        <div id="codeResult" class="code-result" hidden>
          <button id="codeClose" class="code-close" type="button" aria-label="Close generated code">×</button>
          <span id="codeResultStatus"></span>
          <img id="codeResultImage" alt="Generated control code" hidden>
          <span id="codeResultValue" class="code-result-value" hidden></span>
        </div>
      </form>
    </section>
  </main>
  <script>
    const PAGE_VERSION = "${TicketStreamService.SERVER_VERSION}";
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
    const streamPage = document.getElementById('streamPage');
    const streamStage = document.getElementById('streamStage');
    const controlsPanel = document.getElementById('controlsPanel');
    const streamPlaceholder = document.getElementById('streamPlaceholder');
    const canvas = document.getElementById('screen');
    const startButton = document.getElementById('start');
    const codeForm = document.getElementById('codeForm');
    const codeDigits = document.getElementById('codeDigits');
    const codeSubmit = document.getElementById('codeSubmit');
    const codeResult = document.getElementById('codeResult');
    const codeClose = document.getElementById('codeClose');
    const codeResultStatus = document.getElementById('codeResultStatus');
    const codeResultImage = document.getElementById('codeResultImage');
    const codeResultValue = document.getElementById('codeResultValue');
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
    let currentCodeRequestId = '';
    let codeResultTimer = null;
    const codeRequestTimes = [];
    let loadingStartedAt = 0;
    let loadingBlockingPhase = '';
    let loadingOverBudgetLogged = false;
    let loadingBudgetTimer = null;
    let loadingEscalationCount = 0;
    let healthReadyLogged = false;
    let sessionActiveLogged = false;
    let streamConfigLogged = false;
    let firstLiveFrameLogged = false;
    let lastTouchEndAt = 0;
    let lastTouchEndX = 0;
    let lastTouchEndY = 0;
    let streamDimensions = {width: 720, height: 1616};
    const maxTapDurationMs = 450;
    const maxTapTravelPx = 12;
    const streamVerticalPanThresholdPx = 18;
    const streamVerticalPanDominance = 1.25;
    const doubleTapSuppressMs = 420;
    const doubleTapSuppressPx = 28;
    const streamMessages = {
      booting: 'Connecting',
      connecting: 'Connecting',
      permission: 'Starting secure H.264 stream',
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
      if (desiredActive && !autoStartSuspended && viewerIsForeground()) {
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
    function cleanDigits(value) {
      return String(value || '').replace(/\D/g, '').slice(0, 9);
    }
    function noteCodeRequestWindow() {
      const now = Date.now();
      while (codeRequestTimes.length && now - codeRequestTimes[0] >= 60_000) {
        codeRequestTimes.shift();
      }
      if (codeRequestTimes.length >= 2) return false;
      codeRequestTimes.push(now);
      return true;
    }
    function clearCodeResultTimer() {
      if (codeResultTimer) clearTimeout(codeResultTimer);
      codeResultTimer = null;
    }
    function hideCodeResult() {
      clearCodeResultTimer();
      codeResult.hidden = true;
      codeResultImage.hidden = true;
      codeResultImage.removeAttribute('src');
      codeResultValue.hidden = true;
      codeResultValue.textContent = '';
      codeResultStatus.textContent = '';
      currentCodeRequestId = '';
    }
    function showCodeResult(message, value = '', imageMime = '', imageBase64 = '') {
      clearCodeResultTimer();
      codeResult.hidden = false;
      codeResultStatus.textContent = message;
      if (imageBase64) {
        codeResultImage.src = 'data:' + (imageMime || 'image/png') + ';base64,' + imageBase64;
        codeResultImage.hidden = false;
      } else {
        codeResultImage.hidden = true;
        codeResultImage.removeAttribute('src');
      }
      codeResultValue.textContent = value || '';
      codeResultValue.hidden = !value;
    }
    function handleControlCodeResult(msg) {
      if (msg.requestId && currentCodeRequestId && msg.requestId !== currentCodeRequestId) return;
      codeSubmit.disabled = false;
      if (msg.ok === true || msg.accepted === true) {
        showCodeResult('Code ready', msg.value || '', msg.imageMime || '', msg.imageBase64 || '');
      } else {
        showCodeResult(msg.reason || 'Code request failed');
      }
    }
    codeDigits.addEventListener('input', () => {
      const cleaned = cleanDigits(codeDigits.value);
      if (codeDigits.value !== cleaned) codeDigits.value = cleaned;
    });
    codeForm.addEventListener('submit', (event) => {
      event.preventDefault();
      const digits = cleanDigits(codeDigits.value);
      codeDigits.value = digits;
      if (digits.length < 2 || digits.length > 9) {
        showCodeResult('Enter 2-9 digits');
        return;
      }
      if (!noteCodeRequestWindow()) {
        showCodeResult('Two requests per minute');
        return;
      }
      hideCodeResult();
      currentCodeRequestId = randomId();
      codeSubmit.disabled = true;
      showCodeResult('Request queued');
      send({type: 'generate_control_code', requestId: currentCodeRequestId, digits});
    });
    codeClose.addEventListener('click', hideCodeResult);
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
    function viewerIsForeground() {
      return document.visibilityState === 'visible' && (typeof document.hasFocus !== 'function' || document.hasFocus());
    }
    function healthAutoStartBlocked(health) {
      if (!health || health.autoStartAllowed !== false) return false;
      if (health.autoStartBlockedReason === 'viewer_inactivity_timeout' && viewerIsForeground()) return false;
      return true;
    }
    function autoStartBlockedMessage(health, fallback = '') {
      if (health && health.autoStartBlockedReason === 'viewer_inactivity_timeout') return 'Session ended';
      return fallback || '';
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
        if (result && result.message && !configured) {
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
          if (msg.data && msg.data.autoStartAllowed === false && !desiredActive && healthAutoStartBlocked(msg.data)) {
            suspendAutoStart(autoStartBlockedMessage(msg.data, msg.message || ''));
            return;
          }
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
        if (msg.type === 'control_code_result') handleControlCodeResult(msg);
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
        await endSession('video_decode_unsupported', false, 'This browser cannot decode H.264 here');
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
      if (!viewerIsForeground() || selfHealInFlight) return;
      if (desiredActive && configured && firstFrameReceived) {
        if (!ws || ws.readyState === WebSocket.CLOSED) connect(true).catch(() => {});
        if (!videoWs || videoWs.readyState === WebSocket.CLOSED) connectVideo(true).catch(() => scheduleStartupReconnect());
        return;
      }
      selfHealInFlight = true;
      try {
        const health = await refreshHealth();
        if (health.autoStartAllowed === false && !desiredActive && healthAutoStartBlocked(health)) {
          suspendAutoStart(autoStartBlockedMessage(health, health.message || ''));
          return;
        }
        const ffmpegSupported = Boolean(health.ffmpeg && health.ffmpeg.available);
        if (!health.viviInstalled || !ffmpegSupported) return;
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
        if (!desiredActive || autoStartSuspended || !viewerIsForeground()) return;
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
      if (health.autoStartAllowed === false && !desiredActive && healthAutoStartBlocked(health)) {
        const message = autoStartBlockedMessage(health, health.message || '');
        setStreamState('ended', message);
        setStatus(message);
      } else if (!health.viviInstalled || !(health.ffmpeg && health.ffmpeg.available)) {
        setStreamState('unavailable', health.message || 'Unavailable');
        setStatus(health.message || 'Unavailable');
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
    canvas.addEventListener('pointerdown', (event) => {
      if (!desiredActive || !configured) return;
      if (event.button != null && event.button !== 0) return;
      noteActivity(true);
      pointerStart = {
        ...point(event),
        clientX: event.clientX,
        clientY: event.clientY,
        pointerId: event.pointerId,
        pointerType: event.pointerType || 'mouse',
        at: performance.now()
      };
      if (event.pointerType === 'mouse') {
        try { canvas.setPointerCapture(event.pointerId); } catch (_) {}
      }
    });
    canvas.addEventListener('pointermove', (event) => {
      if (!pointerStart || pointerStart.pointerId !== event.pointerId) return;
      if (pointerStart.pointerType === 'mouse') return;
      const dx = event.clientX - pointerStart.clientX;
      const dy = event.clientY - pointerStart.clientY;
      if (Math.abs(dy) >= streamVerticalPanThresholdPx && Math.abs(dy) > Math.abs(dx) * streamVerticalPanDominance) {
        pointerStart = null;
        clientLog('stream_vertical_scroll', {allowed: true});
      }
    });
    canvas.addEventListener('pointerup', (event) => {
      if (!desiredActive || !configured) return;
      if (!pointerStart) return;
      if (pointerStart.pointerId !== event.pointerId) return;
      const end = point(event);
      const dx = end.x - pointerStart.x;
      const dy = end.y - pointerStart.y;
      const distance = Math.hypot(dx, dy);
      const heldMs = performance.now() - pointerStart.at;
      if (distance < maxTapTravelPx && heldMs <= maxTapDurationMs) {
        setStatus('Use the request form below the ticket.');
      }
      pointerStart = null;
    });
    canvas.addEventListener('pointercancel', () => { pointerStart = null; });
    canvas.addEventListener('dblclick', (event) => event.preventDefault());
    function blockStreamGesture(event) {
      if (event.cancelable) event.preventDefault();
    }
    function blockDoubleTapZoom(event) {
      if (event.changedTouches && event.changedTouches.length > 0) {
        const touch = event.changedTouches[0];
        const now = performance.now();
        const nearLastTouch = now - lastTouchEndAt < doubleTapSuppressMs &&
          Math.hypot(touch.clientX - lastTouchEndX, touch.clientY - lastTouchEndY) < doubleTapSuppressPx;
        if (nearLastTouch && event.cancelable) event.preventDefault();
        lastTouchEndAt = now;
        lastTouchEndX = touch.clientX;
        lastTouchEndY = touch.clientY;
      }
    }
    canvas.addEventListener('touchend', blockDoubleTapZoom, {passive: false});
    for (const eventName of ['gesturestart', 'gesturechange', 'gestureend']) {
      canvas.addEventListener(eventName, blockStreamGesture, {passive: false});
      document.addEventListener(eventName, blockStreamGesture, {passive: false});
    }
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
