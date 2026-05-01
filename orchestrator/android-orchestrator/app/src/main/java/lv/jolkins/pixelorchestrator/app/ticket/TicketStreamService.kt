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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lv.jolkins.pixelorchestrator.app.MainActivity
import lv.jolkins.pixelorchestrator.app.SupervisorService
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
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
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class TicketStreamService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val rootExecutor = TicketRootCommandWorker()
  private val runtimeStateStore = TicketRuntimeStateStore(rootExecutor, json)
  private val serverMutex = Mutex()
  private val controlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val videoClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val recentClientTelemetry = ArrayDeque<Pair<Long, String>>()
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
  private var inactivityJob: Job? = null
  private var foregroundGuardJob: Job? = null
  private var clientDisconnectStopJob: Job? = null
  @Volatile private var viviForegroundGraceUntilMillis: Long = 0L
  @Volatile private var lastViviPageEnforceAtMillis: Long = 0L
  @Volatile private var startupDisconnectGraceUntilMillis: Long = 0L
  @Volatile private var pendingStartAfterProjection: Boolean = false
  @Volatile private var streamActive: Boolean = false
  @Volatile private var activeCaptureMode: String = CAPTURE_MODE_IDLE
  @Volatile private var fallbackReason: String? = null
  @Volatile private var rootCaptureSnapshot: TicketRootCaptureHealth = TicketRootCaptureHealth()
  @Volatile private var lastViewerInputAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var lastSessionStopReason: String? = null
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

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
    serviceScope.launch {
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
    brightnessGuardJob?.cancel()
    brightnessGuardJob = null
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    cancelInactivityTimer()
    cancelForegroundGuard()
    PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(null)
    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(false)
    rootCaptureEngine.stop("service_destroyed")
    stopEncoder()
    stopProjection()
    runCatching { runBlocking { disableSecureWindowCaptureBypass() } }
    stopLocalServer()
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
      val path = parts.getOrNull(1)?.substringBefore("?").orEmpty()
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
        acceptWebSocket(socket, input, output, headers, video = path == "/api/v1/stream")
        return@runCatching
      }
      val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
      val body = if (bodyLength > 0) input.readFullyBytes(bodyLength) else ByteArray(0)
      when {
        method == "GET" && path == "/" -> sendHtml(output, browserPage())
        method == "GET" && path == "/api/v1/health" -> sendJson(output, health())
        method == "POST" && path == "/api/v1/session/start" -> sendJson(output, startTicketSession())
        method == "POST" && path == "/api/v1/session/stop" -> sendJson(output, stopTicketSession("browser_requested"))
        method == "POST" && path == "/api/v1/client-log" -> {
          val message = body.toString(Charsets.UTF_8).take(MAX_CLIENT_LOG_BYTES)
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
    client = TicketWebSocket(
      socket = socket,
      input = input,
      output = output,
      onText = { message ->
        if (video) {
          handleVideoClientCommand(message)
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
        Log.i(TAG, "ticket_websocket_close kind=${if (video) "video" else "control"} clients=${totalClientCount()}")
        if (totalClientCount() == 0) {
          scheduleClientDisconnectStop()
        }
      }
    )
    if (video) {
      videoClients.add(client)
      lastVideoClientConnectedAtMillis = SystemClock.elapsedRealtime()
    } else {
      controlClients.add(client)
    }
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    if (!video && ticketSessionOpen()) {
      sendStatus(client)
      sendInactivityStatus(client)
    }
    if (video) {
      streamSize?.let { size ->
      client.sendText(configMessage(size))
        lastConfigSentAtMillis = SystemClock.elapsedRealtime()
      requestKeyFrame()
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
        stopTicketSession("browser_ws_requested")
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
        tap(x, y)
      }
      "keyframe" -> requestKeyFrame()
      "restart_stream" -> {
        if (streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_H264) {
          rootCaptureEngine.restart("browser_restart_stream")
          ensureEncoderIfPossible()
        } else if (streamActive && mediaProjection != null) {
          stopEncoder()
          ensureEncoderIfPossible()
          requestKeyFrame()
        }
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

  private fun handleVideoClientCommand(message: String) {
    val element = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "keyframe" -> requestKeyFrame()
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
    val rootCaptureAvailable = rootCaptureEngine.probe()
    val av1Available = TicketAv1Support.isHardwareEncoderAvailable()
    if (pendingStartAfterProjection && mediaProjection == null) {
      extendStartupDisconnectGrace()
      markViewerInput("capture_permission_already_pending")
      lastMessage = "Screen capture permission is waiting on the Pixel"
      broadcastStatus()
      return@withLock TicketSessionResponse(
        ok = false,
        state = "capture_permission_required",
        message = lastMessage
      )
    }
    lastSessionStopReason = null
    rememberTicketBrightnessState()
    suppressBlackoutOverlayForRemote()
    if (rootCaptureAvailable) {
      enableSecureWindowCaptureBypass()
      pendingStartAfterProjection = false
      fallbackReason = null
      activeCaptureMode = CAPTURE_MODE_ROOT_H264
      streamActive = true
      markViewerInput("session_start_root_capture")
      lastMessage = "Ticket session is active through root capture"
      launchVivi()
      startForegroundGuard()
      ensureEncoderIfPossible()
      broadcastStatus()
      persistRuntimeState("session_start_root_capture")
      return@withLock TicketSessionResponse(ok = true, state = "active", message = lastMessage)
    }
    fallbackReason = rootCaptureSnapshot.message.ifBlank { "Root capture is unavailable" }
    if (!av1Available) {
      cancelInactivityTimer()
      restoreTicketBrightness("capture_unavailable")
      releaseBlackoutOverlaySuppression()
      lastMessage = "Root capture is unavailable and MediaProjection AV1 fallback is unavailable"
      activeCaptureMode = CAPTURE_MODE_IDLE
      persistRuntimeState("capture_unavailable")
      return@withLock TicketSessionResponse(
        ok = false,
        state = "capture_unavailable",
        message = lastMessage
      )
    }
    activeCaptureMode = CAPTURE_MODE_MEDIAPROJECTION_AV1
    if (mediaProjection == null) {
      pendingStartAfterProjection = true
      extendStartupDisconnectGrace()
      markViewerInput("session_start_requested")
      launchVivi()
      delay(PRE_CAPTURE_APP_SETTLE_MILLIS)
      if (!requestCapturePermission()) {
        pendingStartAfterProjection = false
        cancelInactivityTimer()
        disableSecureWindowCaptureBypass()
        restoreTicketBrightness("capture_permission_blocked")
        releaseBlackoutOverlaySuppression()
        return@withLock TicketSessionResponse(
          ok = false,
          state = "capture_permission_blocked",
          message = "Screen capture permission could not be opened on the Pixel"
        )
      }
      return@withLock TicketSessionResponse(
        ok = false,
        state = "capture_permission_required",
        message = "Screen capture permission is waiting on the Pixel"
      )
    }
    streamActive = true
    activeCaptureMode = CAPTURE_MODE_MEDIAPROJECTION_AV1
    markViewerInput("session_start")
    lastMessage = "Ticket session is active through MediaProjection fallback"
    launchVivi()
    startForegroundGuard()
    ensureEncoderIfPossible()
    broadcastStatus()
    persistRuntimeState("session_start_mediaprojection_fallback")
    return@withLock TicketSessionResponse(ok = true, state = "active", message = lastMessage)
  }

  private suspend fun stopTicketSession(reason: String): TicketSessionResponse = sessionMutex.withLock {
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    startupDisconnectGraceUntilMillis = 0L
    pendingStartAfterProjection = false
    streamActive = false
    activeCaptureMode = CAPTURE_MODE_IDLE
    brightnessGuardJob?.cancel()
    brightnessGuardJob = null
    cancelInactivityTimer()
    cancelForegroundGuard()
    rootCaptureEngine.stop(reason)
    stopEncoder()
    stopProjection()
    disableSecureWindowCaptureBypass()
    lastMessage = "Ticket session stopped: $reason"
    lastSessionStopReason = reason
    if (TicketSessionStopPolicy.shouldResetViviToTicket(reason)) {
      resetViviToTicket(reason)
    } else {
      returnToOrchestrator()
    }
    restoreTicketBrightness("session_stopped")
    releaseBlackoutOverlaySuppression()
    hideBlackoutOverlay()
    ticketBrightnessState = null
    PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(null)
    broadcastStatus()
    broadcastInactivityStatus()
    persistRuntimeState("session_stop_$reason")
    return@withLock TicketSessionResponse(ok = true, state = "stopped", message = lastMessage)
  }

  private fun ticketSessionOpen(): Boolean {
    return streamActive || pendingStartAfterProjection
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

  private fun scheduleClientDisconnectStop() {
    val startupGraceMillis = (startupDisconnectGraceUntilMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    val delayMillis = startupGraceMillis.coerceAtLeast(CLIENT_DISCONNECT_IDLE_GRACE_MILLIS)
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = serviceScope.launch {
      if (delayMillis > 0L) {
        delay(delayMillis)
      }
      if (totalClientCount() == 0) {
        clientDisconnectStopJob = null
        stopTicketSession("browser_left_ticket_screen")
      } else {
        clientDisconnectStopJob = null
      }
    }
  }

  private fun markViewerInput(reason: String) {
    lastViewerInputAtMillis = SystemClock.elapsedRealtime()
    if (ticketSessionOpen()) {
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
    if (foregroundGuardJob?.isActive == true) {
      return
    }
    foregroundGuardJob = serviceScope.launch {
      delay(VIVI_FOREGROUND_INITIAL_DELAY_MILLIS)
      while (streamActive) {
        val violation = foregroundViolationReason()
        if (violation != null) {
          Log.i(TAG, "ticket_foreground_guard_stop reason=$violation")
          foregroundGuardJob = null
          stopTicketSession(violation)
          return@launch
        }
        enforceViviTicketPageIfNeeded("foreground_guard")
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
  }

  private suspend fun foregroundViolationReason(allowStartupSystemUi: Boolean = true): String? {
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
      return reason
    }
    if (now < viviForegroundGraceUntilMillis) {
      return null
    }
    return if (output.contains(TicketScreenConfig.VIVI_PACKAGE)) null else "left_vivi_app"
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
    val result = rootExecutor.runScript(
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
    val now = SystemClock.elapsedRealtime()
    if (now < viviForegroundGraceUntilMillis ||
      now - lastViviPageEnforceAtMillis < VIVI_PAGE_ENFORCE_INTERVAL_MILLIS
    ) {
      return
    }
    lastViviPageEnforceAtMillis = now
    val dump = rootExecutor.runScript(
      """
      uiautomator dump /sdcard/pixel-ticket-window.xml >/dev/null 2>&1
      cat /sdcard/pixel-ticket-window.xml 2>/dev/null || true
      """.trimIndent()
    )
    if (!dump.ok) {
      Log.w(TAG, "ticket_vivi_page_dump_failed reason=$reason stderr=${dump.stderr}")
      return
    }
    val action = TicketViviPageEnforcer.actionForHierarchy(dump.stdout) ?: return
    val tap = rootExecutor.run("input tap ${action.x} ${action.y}")
    if (tap.ok) {
      Log.i(TAG, "ticket_vivi_page_enforced reason=$reason action=${action.reason} x=${action.x} y=${action.y}")
    } else {
      Log.w(TAG, "ticket_vivi_page_enforce_failed reason=$reason action=${action.reason} stderr=${tap.stderr}")
    }
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

  private suspend fun requestCapturePermission(): Boolean {
    val now = SystemClock.elapsedRealtime()
    if (now - capturePermissionLaunchedAtMillis < CAPTURE_PERMISSION_LAUNCH_COOLDOWN_MILLIS) {
      lastMessage = "Screen capture permission is already pending on the Pixel"
      return true
    }
    capturePermissionLaunchedAtMillis = now
    enableSecureWindowCaptureBypass()
    val component = "$packageName/.app.ticket.TicketCapturePermissionActivity"
    val result = rootExecutor.run("am start -n $component")
    if (result.ok) {
      return true
    }
    Log.w(TAG, "ticket_capture_permission_root_launch_failed stderr=${result.stderr}")
    return runCatching {
      val intent = Intent(this, TicketCapturePermissionActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(intent)
    }.onFailure { error ->
      Log.w(TAG, "ticket_capture_permission_launch_failed", error)
    }.isSuccess
  }

  private fun handleProjectionResult(intent: Intent) {
    val resultCode = intent.getIntExtra(TicketScreenConfig.EXTRA_RESULT_CODE, 0)
    @Suppress("DEPRECATION")
    val resultData = intent.getParcelableExtra<Intent>(TicketScreenConfig.EXTRA_RESULT_DATA)
    if (resultCode == 0 || resultData == null) {
      pendingStartAfterProjection = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      lastMessage = "Screen capture permission was not granted"
      cancelInactivityTimer()
      serviceScope.launch {
        disableSecureWindowCaptureBypass()
        restoreTicketBrightness("projection_not_granted")
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
      activeCaptureMode = CAPTURE_MODE_IDLE
      lastMessage = "Screen capture permission was rejected by Android"
      cancelInactivityTimer()
      Log.e(TAG, "media_projection_permission_rejected", error)
      serviceScope.launch {
        disableSecureWindowCaptureBypass()
        restoreTicketBrightness("projection_rejected")
        releaseBlackoutOverlaySuppression()
      }
      broadcastStatus()
      return
    }
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
          returnToOrchestrator()
          restoreTicketBrightness("projection_stopped")
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
      streamActive = true
      activeCaptureMode = CAPTURE_MODE_MEDIAPROJECTION_AV1
      extendStartupDisconnectGrace()
      markViewerInput("projection_granted")
      lastMessage = "Ticket session is active through MediaProjection fallback"
      serviceScope.launch { suppressBlackoutOverlayForRemote() }
      launchVivi()
      startForegroundGuard()
      ensureEncoderIfPossible()
    } else {
      lastMessage = "Screen capture permission is ready"
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

  private fun ensureEncoderIfPossible() {
    if (!streamActive || videoClients.isEmpty()) {
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
      startEncoderLocked()
    }
  }

  private fun ensureRootCaptureIfPossible() {
    synchronized(encoderLock) {
      val sourceSize = currentDisplaySize()
      val size = TicketStreamSizing.rootH264(sourceSize.first, sourceSize.second)
      streamSize = size
      activeCaptureMode = CAPTURE_MODE_ROOT_H264
      lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
      broadcastConfig(size)
      rootCaptureEngine.start(sourceSize.first, sourceSize.second)
      rootCaptureSnapshot = rootCaptureEngine.snapshot()
      Log.i(TAG, "ticket_root_capture_requested width=${size.width} height=${size.height} video_clients=${videoClients.size}")
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
    val size = TicketStreamSizing.fitTo1080Equivalent(sourceSize.first, sourceSize.second)
    streamSize = size
    val mediaFormat = MediaFormat.createVideoFormat(
      TicketScreenConfig.AV1_MIME_TYPE,
      size.width,
      size.height
    ).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
      setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE)
      setInteger(MediaFormat.KEY_FRAME_RATE, TicketScreenConfig.MAX_FPS)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        setInteger(
          MediaFormat.KEY_BITRATE_MODE,
          MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        )
      }
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
    encoder = localEncoder
    virtualDisplay = display
    localEncoder.start()
    lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
    Log.i(TAG, "ticket_encoder_started width=${size.width} height=${size.height} video_clients=${videoClients.size}")
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
      lastMessage = "AV1 stream stopped: ${error.message ?: error::class.java.simpleName}"
      broadcastStatus()
    } finally {
      stopEncoder()
    }
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
      streamSize = null
    }
  }

  private fun configMessage(size: TicketStreamSize): String {
    val codec = if (activeCaptureMode == CAPTURE_MODE_ROOT_H264) {
      TicketScreenConfig.H264_CODEC_STRING
    } else {
      TicketScreenConfig.AV1_CODEC_STRING
    }
    val transport = if (activeCaptureMode == CAPTURE_MODE_ROOT_H264) "h264-annexb" else "av1-webcodecs"
    val rootCapture = activeCaptureMode == CAPTURE_MODE_ROOT_H264
    return """
      {"type":"config","serverVersion":"$SERVER_VERSION","codec":"$codec","transport":"$transport","rootCapture":$rootCapture,"width":${size.width},"height":${size.height},"fps":${TicketScreenConfig.MAX_FPS}}
    """.trimIndent()
  }

  private fun broadcastConfig(size: TicketStreamSize) {
    val message = configMessage(size)
    lastConfigSentAtMillis = SystemClock.elapsedRealtime()
    Log.i(TAG, "ticket_stream_config_sent width=${size.width} height=${size.height} video_clients=${videoClients.size}")
    videoClientSnapshot().forEach { client -> client.sendText(message) }
  }

  private fun broadcastFrame(keyFrame: Boolean, timestampUs: Long, payload: ByteArray) {
    clearStartupDisconnectGrace()
    val buffer = ByteBuffer.allocate(9 + payload.size)
    buffer.put(if (keyFrame) 1 else 0)
    buffer.putLong(timestampUs)
    buffer.put(payload)
    val frame = buffer.array()
    sentFrames += 1
    lastFrameSentAtMillis = SystemClock.elapsedRealtime()
    videoClientSnapshot().forEach { client -> client.sendBinary(frame) }
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

  private fun requestKeyFrame() {
    lastKeyFrameRequestedAtMillis = SystemClock.elapsedRealtime()
    keyFrameRequests += 1
    if (activeCaptureMode == CAPTURE_MODE_ROOT_H264) {
      val lastKeyFrameAgo = rootCaptureEngine.snapshot(lastKeyFrameRequestedAtMillis).lastKeyFrameAgoMillis
      if (lastKeyFrameAgo == null || lastKeyFrameAgo > ROOT_KEYFRAME_RESTART_AFTER_MILLIS) {
        rootCaptureEngine.restart("keyframe_requested")
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

  private fun sendStatus(client: TicketWebSocket) {
    val health = health()
    val payload = json.encodeToString(health)
    val message = json.encodeToString(health.message)
    client.sendText("""{"type":"health","message":$message,"data":$payload}""")
  }

  private fun broadcastStatus() {
    controlClientSnapshot().forEach(::sendStatus)
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

  private fun totalClientCount(): Int = controlClients.size + videoClients.size

  private fun recordClientTelemetry(message: String) {
    synchronized(recentClientTelemetry) {
      recentClientTelemetry.addLast(SystemClock.elapsedRealtime() to message)
      while (recentClientTelemetry.size > RECENT_CLIENT_TELEMETRY_LIMIT) {
        recentClientTelemetry.removeFirst()
      }
    }
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

  private fun persistRuntimeState(sessionState: String) {
    val nowMillis = SystemClock.elapsedRealtime()
    val snapshot = TicketRuntimeSnapshot(
      savedAtElapsedMillis = nowMillis,
      sessionState = sessionState,
      streamActive = streamActive,
      captureMode = activeCaptureMode,
      rootCapture = rootCaptureEngine.snapshot(nowMillis),
      lastGoodStreamAgoMillis = ageMillis(lastFrameSentAtMillis, nowMillis),
      fallbackReason = fallbackReason,
      recoveryCounters = TicketRecoveryCounters(
        rootCaptureRestarts = rootCaptureEngine.snapshot(nowMillis).restarts,
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

  private fun streamPipelineSnapshot(nowMillis: Long): TicketStreamPipeline {
    return TicketStreamPipeline(
      controlClients = controlClients.size,
      videoClients = videoClients.size,
      encoderRunning = encoderJob?.isActive == true || rootCaptureEngine.snapshot(nowMillis).active,
      streamConfigured = streamSize != null,
      encodedFrames = encodedFrames,
      sentFrames = sentFrames,
      keyFrames = keyFrames,
      lastEncoderStartAgoMillis = ageMillis(lastEncoderStartAtMillis, nowMillis),
      lastConfigSentAgoMillis = ageMillis(lastConfigSentAtMillis, nowMillis),
      lastFrameEncodedAgoMillis = ageMillis(lastFrameEncodedAtMillis, nowMillis),
      lastKeyFrameEncodedAgoMillis = ageMillis(lastKeyFrameEncodedAtMillis, nowMillis),
      lastFrameSentAgoMillis = ageMillis(lastFrameSentAtMillis, nowMillis),
      lastKeyFrameRequestedAgoMillis = ageMillis(lastKeyFrameRequestedAtMillis, nowMillis),
      lastVideoClientConnectedAgoMillis = ageMillis(lastVideoClientConnectedAtMillis, nowMillis),
      secureWindowCaptureBypassActive = secureWindowCaptureBypassActive,
      secureWindowCaptureBypassMessage = secureWindowCaptureBypassMessage
    )
  }

  private fun health(): TicketStreamHealth {
    val nowMillis = SystemClock.elapsedRealtime()
    val installedStores = TicketPackageSupport.installedLocalStores(this)
    val av1 = TicketAv1Support.isHardwareEncoderAvailable()
    val h264 = TicketH264Support.isHardwareEncoderAvailable()
    val rootCapture = rootCaptureEngine.snapshot(nowMillis)
    val vivi = TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)
    val ok = running.get() && vivi && (rootCapture.supported || av1)
    val visibleFrameCodec = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_H264 -> TicketScreenConfig.H264_CODEC_STRING
      CAPTURE_MODE_MEDIAPROJECTION_AV1 -> TicketScreenConfig.AV1_CODEC_STRING
      else -> ""
    }
    return TicketStreamHealth(
      ok = ok,
      serverVersion = SERVER_VERSION,
      serverRunning = running.get(),
      av1HardwareEncoderAvailable = av1,
      h264HardwareEncoderAvailable = h264,
      projectionReady = mediaProjection != null,
      capturePermissionPending = pendingStartAfterProjection,
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
      rootCapture = rootCapture,
      webrtc = TicketWebRtcHealth(
        enabled = false,
        message = "WebRTC is served by ticket_remote; Pixel emits H.264 root frames"
      ),
      inputGate = TicketInputGateHealth(
        tapOnly = true,
        active = streamActive,
        allowed = streamActive && activeCaptureMode != CAPTURE_MODE_IDLE,
        reason = if (streamActive) "tap_gate_active" else "no_active_control"
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
      streamPipeline = streamPipelineSnapshot(nowMillis),
      recentClientTelemetry = recentClientTelemetrySnapshot(nowMillis),
      message = when {
        !vivi -> "ViVi is not installed from a local Pixel app store yet"
        streamActive -> lastMessage
        rootCapture.supported -> lastMessage
        av1 && pendingStartAfterProjection -> "Screen capture permission is waiting on the Pixel"
        !rootCapture.supported && !av1 -> "Root capture is unavailable and MediaProjection AV1 fallback is unavailable"
        activeCaptureMode == CAPTURE_MODE_MEDIAPROJECTION_AV1 && mediaProjection == null -> "Screen capture permission is not ready"
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

  private suspend fun resetViviToTicket(reason: String) {
    val launchIntent = packageManager.getLaunchIntentForPackage(TicketScreenConfig.VIVI_PACKAGE)
    if (launchIntent == null) {
      Log.w(TAG, "ticket_vivi_reset_launch_intent_missing reason=$reason")
      returnToOrchestrator()
      return
    }
    try {
      startActivity(
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      )
    } catch (error: Throwable) {
      Log.w(TAG, "ticket_vivi_reset_initial_launch_failed reason=$reason", error)
      returnToOrchestrator()
      return
    }
    delay(VIVI_TICKET_RESET_SETTLE_MILLIS)
    val backResult = rootExecutor.run("input keyevent KEYCODE_BACK")
    if (!backResult.ok) {
      Log.w(TAG, "ticket_vivi_reset_back_failed reason=$reason stderr=${backResult.stderr}")
    }
    delay(VIVI_TICKET_RESET_SETTLE_MILLIS)
    try {
      val relaunchIntent = packageManager.getLaunchIntentForPackage(TicketScreenConfig.VIVI_PACKAGE)
        ?: launchIntent
      startActivity(
        relaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      )
      Log.i(TAG, "ticket_vivi_reset_to_ticket reason=$reason")
    } catch (error: Throwable) {
      Log.w(TAG, "ticket_vivi_reset_relaunch_failed reason=$reason", error)
      returnToOrchestrator()
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
    if (ticketBrightnessState == null) {
      return
    }
    brightnessGuardJob?.cancel()
    brightnessGuardJob = serviceScope.launch {
      var previousDelay = 0L
      for (targetDelay in BRIGHTNESS_GUARD_DELAYS_MILLIS) {
        delay(targetDelay - previousDelay)
        previousDelay = targetDelay
        if (!streamActive && !pendingStartAfterProjection) {
          return@launch
        }
        hideBlackoutOverlay()
        restoreTicketBrightnessForCurrentMode(reason)
      }
      while (streamActive || pendingStartAfterProjection) {
        delay(BRIGHTNESS_GUARD_REPEAT_MILLIS)
        hideBlackoutOverlay()
        restoreTicketBrightnessForCurrentMode(reason)
      }
    }
  }

  private suspend fun restoreTicketBrightnessForCurrentMode(reason: String) {
    val touchSnapshot = touchBrightnessSnapshot()
    if (touchSnapshot?.touchBrightnessEnabled == true &&
      touchSnapshot.touchBrightnessState == TouchBrightnessRuntimeState.BLACKOUT_IDLE
    ) {
      setTicketPanelBrightnessPercent(0, "$reason:touch_dim")
    } else if (touchSnapshot?.touchBrightnessEnabled == true) {
      restoreTicketBrightnessIfTooBright(reason)
    } else {
      restoreTicketBrightness(reason)
    }
  }

  private fun isTouchBrightnessEnabled(): Boolean {
    return touchBrightnessSnapshot()?.touchBrightnessEnabled ?: false
  }

  private fun touchBrightnessSnapshot() = runCatching {
    PhoneAutomationPreferencesStore(this).load()
  }.getOrNull()

  private suspend fun setTicketPanelBrightnessPercent(percent: Int, reason: String) {
    runCatching {
      val result = rootExecutor.runScript(ScreenBrightnessControl.buildSetPanelPercentScript(percent))
      if (result.ok) {
        Log.i(TAG, "ticket_panel_brightness_percent_set reason=$reason percent=$percent")
      } else {
        Log.w(TAG, "ticket_panel_brightness_percent_failed reason=$reason stderr=${result.stderr}")
      }
    }.onFailure { error ->
      Log.w(TAG, "ticket_panel_brightness_percent_failed reason=$reason", error)
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
      Log.i(
        TAG,
        "ticket_brightness_restored reason=$reason mode=${state.mode} value=${state.value} display=${state.displayPercentage} panel=${state.panelActualBrightness ?: state.panelBrightness}/${state.panelMaxBrightness}"
      )
    } else {
      Log.w(TAG, "ticket_brightness_restore_failed reason=$reason stderr=${result.stderr}")
    }
  }

  private suspend fun restoreTicketBrightnessIfTooBright(reason: String) {
    val target = ticketBrightnessState ?: readTouchBrightnessRestoreState() ?: return
    val targetPanel = target.panelBrightness ?: target.panelActualBrightness ?: return restoreTicketBrightness(reason)
    val current = readBrightnessState() ?: return restoreTicketBrightness(reason)
    val currentPanel = current.panelActualBrightness ?: current.panelBrightness ?: return restoreTicketBrightness(reason)
    if (currentPanel > targetPanel + PANEL_VALUE_TOLERANCE) {
      restoreTicketBrightness("$reason:panel_too_bright")
    }
  }

  private suspend fun canForwardRemoteInput(): Boolean {
    if (!streamActive) {
      return false
    }
    val violation = foregroundViolationReason(allowStartupSystemUi = false)
    if (violation == null) {
      return true
    }
    Log.i(TAG, "ticket_remote_input_blocked reason=$violation")
    stopTicketSession(violation)
    return false
  }

  private fun ignoreProtectedRemoteInput(reason: String) {
    Log.i(TAG, "ticket_remote_input_ignored reason=$reason")
    markViewerInput(reason)
  }

  private fun isProtectedRemoteTap(size: TicketStreamSize, encodedX: Int, encodedY: Int): Boolean {
    val x = encodedX / size.width.toFloat()
    val y = encodedY / size.height.toFloat()
    return inRemoteSystemEdge(size, encodedX, encodedY) || inTicketCloseZone(x, y)
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

  private fun inTicketCloseZone(x: Float, y: Float): Boolean {
    return x in TICKET_CLOSE_ZONE_MIN_X..TICKET_CLOSE_ZONE_MAX_X &&
      y in TICKET_CLOSE_ZONE_MIN_Y..TICKET_CLOSE_ZONE_MAX_Y
  }

  private suspend fun tap(encodedX: Int, encodedY: Int) {
    if (!canForwardRemoteInput()) {
      return
    }
    val size = streamSize ?: return
    if (isProtectedRemoteTap(size, encodedX, encodedY)) {
      ignoreProtectedRemoteInput("remote_protected_tap_blocked")
      return
    }
    val x = size.sourceX(encodedX)
    val y = size.sourceY(encodedY)
    markViewerInput("tap")
    rootExecutor.run("input tap $x $y")
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
        "Ticket screen",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Keeps ticket.jolkins.id.lv available when enabled"
      }
    )
  }

  private fun foregroundNotification(): Notification {
    val openIntent = Intent(this, MainActivity::class.java)
    val pendingIntent = android.app.PendingIntent.getActivity(
      this,
      9388,
      openIntent,
      android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle("Ticket remote access")
      .setContentText("Serving ticket.jolkins.id.lv on localhost")
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .build()
  }

  private fun sendJson(output: BufferedOutputStream, value: TicketStreamHealth) {
    sendJsonPayload(output, json.encodeToString(value))
  }

  private fun sendJson(output: BufferedOutputStream, value: TicketSessionResponse) {
    sendJsonPayload(output, json.encodeToString(value))
  }

  private fun sendJsonPayload(output: BufferedOutputStream, payload: String) {
    sendHttp(
      output = output,
      status = 200,
      contentType = "application/json; charset=utf-8",
      body = payload.toByteArray(Charsets.UTF_8)
    )
  }

  private fun sendHtml(output: BufferedOutputStream, html: String) {
    sendHttp(
      output = output,
      status = 200,
      contentType = "text/html; charset=utf-8",
      body = html.toByteArray(Charsets.UTF_8)
    )
  }

  private fun sendText(output: BufferedOutputStream, status: Int, text: String) {
    sendHttp(
      output = output,
      status = status,
      contentType = "text/plain; charset=utf-8",
      body = text.toByteArray(Charsets.UTF_8)
    )
  }

  private fun sendHttp(output: BufferedOutputStream, status: Int, contentType: String, body: ByteArray) {
    val statusText = if (status == 200) "OK" else "Error"
    output.write(
      buildString {
        append("HTTP/1.1 $status $statusText\r\n")
        append("Content-Type: $contentType\r\n")
        append("Content-Length: ${body.size}\r\n")
        append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n")
        append("Pragma: no-cache\r\n")
        append("Expires: 0\r\n")
        append("Surrogate-Control: no-store\r\n")
        append("CDN-Cache-Control: no-store\r\n")
        append("Cloudflare-CDN-Cache-Control: no-store\r\n")
        append("Clear-Site-Data: \"cache\"\r\n")
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
    private const val CHANNEL_ID = "ticket_screen"
    private const val NOTIFICATION_ID = 9388
    private const val SERVER_BACKLOG = 4
    private const val SOCKET_TIMEOUT_MILLIS = 30_000
    private const val MAX_HEADER_LINE_BYTES = 131_072
    private const val MAX_CLIENT_LOG_BYTES = 2_048
    private const val RECENT_CLIENT_TELEMETRY_LIMIT = 12
    const val SERVER_VERSION = "ticket-stream-2026-05-01-v2"
    private const val CAPTURE_MODE_IDLE = "idle"
    private const val CAPTURE_MODE_ROOT_H264 = "root_h264"
    private const val CAPTURE_MODE_MEDIAPROJECTION_AV1 = "mediaprojection_av1"
    private const val DEFAULT_BITRATE = 1_500_000
    private const val ENCODER_TIMEOUT_US = 50_000L
    private const val ROOT_KEYFRAME_RESTART_AFTER_MILLIS = 2_000L
    private const val CAPTURE_PERMISSION_LAUNCH_COOLDOWN_MILLIS = 15_000L
    private const val PRE_CAPTURE_APP_SETTLE_MILLIS = 800L
    private const val STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS = 5_000L
    private const val CLIENT_DISCONNECT_IDLE_GRACE_MILLIS = 10_000L
    private const val VIVI_FOREGROUND_INITIAL_DELAY_MILLIS = 1_500L
    private const val VIVI_FOREGROUND_CHECK_MILLIS = 750L
    private const val VIVI_FOREGROUND_GRACE_MILLIS = 8_000L
    private const val VIVI_PAGE_ENFORCE_INTERVAL_MILLIS = 2_000L
    private const val VIVI_TICKET_RESET_SETTLE_MILLIS = 350L
    private const val REMOTE_TOP_SYSTEM_EDGE_FRACTION = 0.055f
    private const val REMOTE_BOTTOM_SYSTEM_EDGE_FRACTION = 0.03f
    private const val REMOTE_SIDE_SYSTEM_EDGE_FRACTION = 0.025f
    private const val REMOTE_MIN_SYSTEM_EDGE_PIXELS = 18
    private const val TICKET_CLOSE_ZONE_MIN_X = 0.82f
    private const val TICKET_CLOSE_ZONE_MAX_X = 1.0f
    private const val TICKET_CLOSE_ZONE_MIN_Y = 0.02f
    private const val TICKET_CLOSE_ZONE_MAX_Y = 0.16f
    private const val DIM_DISPLAY_PERCENT = 1.0f
    private const val MAX_DISPLAY_PERCENT = 99.0f
    private const val DIM_LEGACY_BRIGHTNESS_VALUE = 1
    private const val MAX_LEGACY_BRIGHTNESS_VALUE = 252
    private const val PANEL_VALUE_TOLERANCE = 2
    private const val BRIGHTNESS_GUARD_REPEAT_MILLIS = 2_500L
    private val BRIGHTNESS_GUARD_DELAYS_MILLIS = longArrayOf(250L, 1_000L, 2_500L, 5_000L)
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
    canvas { position: absolute; left: 50%; top: 50%; display: block; width: var(--stream-css-width, 100vw); height: var(--stream-css-height, var(--ticket-stage-height)); max-width: none; max-height: none; background: #000; opacity: 0; transform: translate3d(-50%, -50%, 0); transition: opacity 160ms ease; touch-action: pan-y; user-select: none; -webkit-user-select: none; }
    body[data-stream-ready="true"] canvas { opacity: 1; }
    #idleTimer { min-width: 58px; padding: 4px 8px; border-radius: 999px; background: rgba(10, 13, 18, .72); border: 1px solid rgba(185, 194, 207, .26); color: #edf3fb; font-size: 12px; line-height: 1.2; text-align: center; pointer-events: none; backdrop-filter: blur(6px); }
    #idleTimer[hidden] { display: none; }
    #idleTimer.urgent { color: #ffd6d6; border-color: rgba(255, 115, 115, .45); background: rgba(70, 18, 18, .74); }
    #status { max-width: min(420px, calc(100vw - 32px)); color: #b9c2cf; font-size: 13px; line-height: 1.35; text-align: center; pointer-events: none; }
    #status:empty { display: none; }
  </style>
</head>
<body>
  <main id="ticketRoot">
    <section class="stream-page" id="streamPage" aria-label="Pixel stream">
      <div class="stream-stage" id="streamStage">
        <div class="stream-frame">
          <canvas id="screen" width="540" height="1080"></canvas>
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
  <script>
    const PAGE_VERSION = "${TicketStreamService.SERVER_VERSION}";
    const statusEl = document.getElementById('status');
    const idleTimerEl = document.getElementById('idleTimer');
    const streamPage = document.getElementById('streamPage');
    const streamStage = document.getElementById('streamStage');
    const controlsPanel = document.getElementById('controlsPanel');
    const streamPlaceholder = document.getElementById('streamPlaceholder');
    const canvas = document.getElementById('screen');
    const startButton = document.getElementById('start');
    const ctx = canvas.getContext('2d');
    document.body.dataset.streamReady = 'false';
    document.body.dataset.streamState = 'booting';
    if ('scrollRestoration' in history) {
      history.scrollRestoration = 'manual';
    }
    window.scrollTo(0, 0);
    let ws;
    let videoWs;
    let decoder;
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
    let startupReconnectAttempts = 0;
    let autoStartSuspended = false;
    let capturePermissionPending = false;
    let versionReloading = false;
    let visibleSampleLogged = false;
    let lastActivitySentAt = 0;
    let lastConfigAt = 0;
    let lastFrameAt = 0;
    let streamDimensions = {width: 540, height: 1080};
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

    function currentStageHeight() {
      const value = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--ticket-stage-height'));
      return Number.isFinite(value) && value > 0 ? value : Math.max(1, window.innerHeight || document.documentElement.clientHeight || 1);
    }
    function setStreamState(state, message = '') {
      document.body.dataset.streamState = state;
      const text = message || streamMessages[state] || 'Connecting';
      if (document.body.dataset.streamReady !== 'true') {
        streamPlaceholder.textContent = text;
      }
    }
    function collapseDetailsToStream() {
      document.body.classList.remove('details-visible');
      controlsPanel.setAttribute('aria-hidden', 'true');
      window.scrollTo(0, 0);
    }
    function setStreamReady(ready) {
      document.body.dataset.streamReady = ready ? 'true' : 'false';
      if (!ready) {
        firstFrameReceived = false;
        visibleSampleLogged = false;
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
    function socketUrl() {
      return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/v1/session';
    }
    function streamSocketUrl() {
      return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/v1/stream';
    }
    function serverVersionFrom(value) {
      if (!value || typeof value !== 'object') return '';
      return value.serverVersion || (value.data && value.data.serverVersion) || '';
    }
    function checkServerVersion(value) {
      const serverVersion = serverVersionFrom(value);
      if (!serverVersion || serverVersion === PAGE_VERSION) return true;
      if (versionReloading) return false;
      versionReloading = true;
      clientLog('page_version_mismatch', {serverVersion, pageVersion: PAGE_VERSION});
      const nextUrl = location.origin + location.pathname + '?v=' + encodeURIComponent(serverVersion) + '&t=' + Date.now();
      location.replace(nextUrl);
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
        }, 1200);
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
      const delayMs = Math.min(5000, 250 * startupReconnectAttempts);
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
          navigator.sendBeacon('/api/v1/session/stop', new Blob([], {type: 'text/plain'}));
          return;
        }
      } catch (_) {}
      try { fetch('/api/v1/session/stop', {method: 'POST', cache: 'no-store', keepalive: true}).catch(() => {}); } catch (_) {}
    }
    async function endSession(reason, pageLeaving = false, message = '') {
      const wasActive = desiredActive || startCommandSent || configured;
      desiredActive = false;
      startCommandSent = false;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      stopKeepalive();
      if (wasActive && !pageLeaving) {
        send({type: 'stop', reason});
        try { await fetch('/api/v1/session/stop', {method: 'POST', cache: 'no-store', keepalive: true}); } catch (_) {}
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
        }, 10000);
        socket.onopen = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timeout);
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
          if (msg.data) updateIdleTimer(msg.data);
          if (msg.data && msg.data.autoStartAllowed === false) {
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
        }, 10000);
        socket.onopen = () => {
          if (settled) return;
          settled = true;
          clearTimeout(timeout);
          sendVideo({type: 'keyframe'});
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
    async function handleVideoSocketMessage(event) {
      if (typeof event.data === 'string') {
        const msg = JSON.parse(event.data);
        if (!checkServerVersion(msg)) return;
        if (msg.type === 'config') {
          await configureDecoder(msg);
        }
        return;
      }
      if (!configured || !decoder) return;
      const data = new Uint8Array(event.data);
      const view = new DataView(event.data);
      const kind = data[0] === 1 ? 'key' : 'delta';
      if (needsKeyFrame && kind !== 'key') return;
      if (kind === 'key') needsKeyFrame = false;
      const high = view.getUint32(1);
      const low = view.getUint32(5);
      const timestamp = high * 4294967296 + low;
      lastFrameAt = performance.now();
      decoder.decode(new EncodedVideoChunk({type: kind, timestamp, data: data.slice(9)}));
    }
    async function configureDecoder(config) {
      if (!checkServerVersion(config)) return;
      if (!('VideoDecoder' in window)) {
        clientLog('webcodecs_missing');
        await endSession('webcodecs_missing', false, 'This browser does not support WebCodecs');
        return;
      }
      const h264 = String(config.codec || '').startsWith('avc1') || config.transport === 'h264-annexb';
      const decoderConfig = {codec: config.codec, codedWidth: config.width, codedHeight: config.height};
      if (h264) decoderConfig.avc = {format: 'annexb'};
      const supported = await VideoDecoder.isConfigSupported(decoderConfig);
      if (!supported.supported) {
        clientLog('video_decode_unsupported', {config: decoderConfig});
        await endSession('video_decode_unsupported', false, h264 ? 'This browser cannot decode H.264 here' : 'This browser cannot decode AV1 here');
        return;
      }
      closeDecoder(streamMessages.waiting);
      canvas.width = config.width;
      canvas.height = config.height;
      streamDimensions = {width: config.width, height: config.height};
      canvas.style.setProperty('--stream-aspect', `${'$'}{config.width} / ${'$'}{config.height}`);
      canvas.dataset.streamWidth = String(config.width);
      canvas.dataset.streamHeight = String(config.height);
      resizeCanvasBox();
      lastConfigAt = performance.now();
      lastFrameAt = lastConfigAt;
      setStreamState('waiting');
      decoder = new VideoDecoder({
        output(frame) {
          ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
          lastFrameAt = performance.now();
          if (!firstFrameReceived) {
            firstFrameReceived = true;
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
          setStreamState('reconnecting', `Decoder error: ${'$'}{error.message}`);
          setStatus(`Decoder error: ${'$'}{error.message}`);
        }
      });
      decoder.configure(decoderConfig);
      configured = true;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      needsKeyFrame = true;
      startButton.disabled = false;
      startButton.textContent = 'Start';
      sendVideo({type: 'keyframe'}) || send({type: 'keyframe'});
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
      if (!pageIsVisible() || selfHealInFlight) return;
      if (desiredActive && configured && firstFrameReceived) {
        if (!ws || ws.readyState === WebSocket.CLOSED) connect(true).catch(() => {});
        if (!videoWs || videoWs.readyState === WebSocket.CLOSED) connectVideo(true).catch(() => scheduleStartupReconnect());
        return;
      }
      selfHealInFlight = true;
      try {
        const health = await refreshHealth();
        if (health.autoStartAllowed === false) {
          suspendAutoStart(autoStartBlockedMessage(health, health.message || ''));
          return;
        }
        if (!health.viviInstalled || !health.av1HardwareEncoderAvailable) return;
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
          sendVideo({type: 'keyframe'}) || send({type: 'keyframe'});
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
      selfHealTimer = setInterval(() => ensureStreaming('watchdog'), 3000);
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
        if (!firstFrameReceived && now - lastConfigAt > 7000) {
          clientLog('stream_watchdog_no_first_frame', {sinceConfigMs: Math.round(now - lastConfigAt)});
          setStreamState('waiting');
          sendVideo({type: 'keyframe'}) || send({type: 'keyframe'});
          send({type: 'restart_stream'});
          closeVideoSocket();
          closeDecoder('Reconnecting stream...');
          connectVideo(true).catch(() => scheduleStartupReconnect());
          return;
        }
        if (firstFrameReceived && now - lastFrameAt > 8000) {
          clientLog('stream_watchdog_stale_frame', {sinceFrameMs: Math.round(now - lastFrameAt)});
          send({type: 'restart_stream'});
          closeDecoder('Reconnecting stream...');
          closeVideoSocket();
          connectVideo(true).catch(() => scheduleStartupReconnect());
        }
      }, 2000);
    }
    async function refreshHealth() {
      const response = await fetch('/api/v1/health', {cache: 'no-store'});
      const health = await response.json();
      if (!checkServerVersion(health)) return health;
      updateIdleTimer(health);
      if (health.autoStartAllowed === false) {
        const message = autoStartBlockedMessage(health, health.message || '');
        setStreamState('ended', message);
        setStatus(message);
      } else if (!health.viviInstalled || (!health.av1HardwareEncoderAvailable && !(health.rootCapture && health.rootCapture.supported))) {
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
    window.addEventListener('keydown', () => noteActivity(true), {capture: true});
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
    startSelfHealTimer();
    startStreamWatchdog();
    ensureStreaming('initial_load').catch(() => setStatus('Unavailable'));
  </script>
</body>
</html>
  """.trimIndent()
}
