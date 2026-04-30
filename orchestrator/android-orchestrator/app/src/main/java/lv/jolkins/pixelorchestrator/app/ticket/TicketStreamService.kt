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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class TicketStreamService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val rootExecutor = SuRootExecutor()
  private val serverMutex = Mutex()
  private val clients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val encoderLock = Any()
  private val running = AtomicBoolean(false)

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
  @Volatile private var startupDisconnectGraceUntilMillis: Long = 0L
  @Volatile private var pendingStartAfterProjection: Boolean = false
  @Volatile private var streamActive: Boolean = false
  @Volatile private var lastViewerInputAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var lastMessage: String = "Ticket server is starting"

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
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
    stopEncoder()
    stopProjection()
    stopLocalServer()
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
      if (headers["upgrade"]?.equals("websocket", ignoreCase = true) == true && path == "/api/v1/session") {
        acceptWebSocket(socket, input, output, headers)
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
    headers: Map<String, String>
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
    Log.i(TAG, "ticket_websocket_open clients=${clients.size + 1}")

    lateinit var client: TicketWebSocket
    client = TicketWebSocket(
      socket = socket,
      input = input,
      output = output,
      onText = { message -> handleClientCommand(client, message) },
      onClose = {
        clients.remove(client)
        Log.i(TAG, "ticket_websocket_close clients=${clients.size}")
        if (clients.isEmpty()) {
          scheduleClientDisconnectStop()
        }
      }
    )
    clients.add(client)
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    if (ticketSessionOpen()) {
      sendInactivityStatus(client)
    }
    streamSize?.let { size ->
      client.sendText(configMessage(size))
      requestKeyFrame()
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
      "long_press", "longpress", "hold" -> {
        blockLongPress()
      }
      "swipe" -> {
        blockSwipe()
      }
      "keepalive" -> if (element["active"]?.jsonPrimitive?.booleanOrNull == true) sendStatus(client)
    }
  }

  private suspend fun startTicketSession(): TicketSessionResponse {
    if (!TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)) {
      return TicketSessionResponse(
        ok = false,
        state = "vivi_missing",
        message = "ViVi is not installed from a local Pixel app store yet"
      )
    }
    if (!TicketAv1Support.isHardwareEncoderAvailable()) {
      return TicketSessionResponse(
        ok = false,
        state = "av1_unsupported",
        message = "Hardware AV1 encoding is unavailable on this Pixel"
      )
    }
    rememberTicketBrightnessState()
    suppressBlackoutOverlayForRemote()
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
        return TicketSessionResponse(
          ok = false,
          state = "capture_permission_blocked",
          message = "Screen capture permission could not be opened on the Pixel"
        )
      }
      return TicketSessionResponse(
        ok = false,
        state = "capture_permission_required",
        message = "Screen capture permission is waiting on the Pixel"
      )
    }
    streamActive = true
    markViewerInput("session_start")
    lastMessage = "Ticket session is active"
    launchVivi()
    startForegroundGuard()
    ensureEncoderIfPossible()
    broadcastStatus()
    return TicketSessionResponse(ok = true, state = "active", message = lastMessage)
  }

  private suspend fun stopTicketSession(reason: String): TicketSessionResponse {
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    startupDisconnectGraceUntilMillis = 0L
    pendingStartAfterProjection = false
    streamActive = false
    brightnessGuardJob?.cancel()
    brightnessGuardJob = null
    cancelInactivityTimer()
    cancelForegroundGuard()
    stopEncoder()
    stopProjection()
    disableSecureWindowCaptureBypass()
    lastMessage = "Ticket session stopped: $reason"
    returnToOrchestrator()
    restoreTicketBrightness("session_stopped")
    releaseBlackoutOverlaySuppression()
    hideBlackoutOverlay()
    ticketBrightnessState = null
    PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(null)
    broadcastStatus()
    broadcastInactivityStatus()
    return TicketSessionResponse(ok = true, state = "stopped", message = lastMessage)
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
      if (clients.isEmpty()) {
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
        delay(VIVI_FOREGROUND_CHECK_MILLIS)
      }
      foregroundGuardJob = null
    }
  }

  private fun cancelForegroundGuard() {
    foregroundGuardJob?.cancel()
    foregroundGuardJob = null
    viviForegroundGraceUntilMillis = 0L
  }

  private suspend fun foregroundViolationReason(): String? {
    val output = focusedWindowSnapshot() ?: return "foreground_check_failed"
    systemEscapeReason(output)?.let { return it }
    if (SystemClock.elapsedRealtime() < viviForegroundGraceUntilMillis) {
      return null
    }
    return if (output.contains(TicketScreenConfig.VIVI_PACKAGE)) null else "left_vivi_app"
  }

  private fun systemEscapeReason(focusedWindow: String): String? {
    val normalized = focusedWindow.lowercase()
    return when {
      FOCUSED_POWER_TOKENS.any { token -> normalized.contains(token) } -> "remote_power_controls_blocked"
      FOCUSED_NETWORK_TOKENS.any { token -> normalized.contains(token) } -> "remote_network_controls_blocked"
      FOCUSED_SYSTEM_UI_TOKENS.any { token -> normalized.contains(token) } -> "remote_system_ui_blocked"
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
    clientSnapshot().forEach { client -> client.sendText(message) }
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
      extendStartupDisconnectGrace()
      markViewerInput("projection_granted")
      lastMessage = "Ticket session is active"
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
    val result = rootExecutor.runScript(
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
    )
    if (!result.ok) {
      Log.w(TAG, "secure_window_capture_bypass_enable_failed stderr=${result.stderr}")
    }
  }

  private suspend fun disableSecureWindowCaptureBypass() {
    val result = rootExecutor.runScript(
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
    )
    if (!result.ok) {
      Log.w(TAG, "secure_window_capture_bypass_disable_failed stderr=${result.stderr}")
    }
  }

  private fun ensureEncoderIfPossible() {
    if (!streamActive || clients.isEmpty() || mediaProjection == null) {
      return
    }
    synchronized(encoderLock) {
      if (encoderJob?.isActive == true) {
        return
      }
      startEncoderLocked()
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
    return """
      {"type":"config","codec":"${TicketScreenConfig.AV1_CODEC_STRING}","width":${size.width},"height":${size.height},"fps":${TicketScreenConfig.MAX_FPS}}
    """.trimIndent()
  }

  private fun broadcastConfig(size: TicketStreamSize) {
    val message = configMessage(size)
    clientSnapshot().forEach { client -> client.sendText(message) }
  }

  private fun broadcastFrame(keyFrame: Boolean, timestampUs: Long, payload: ByteArray) {
    clearStartupDisconnectGrace()
    val buffer = ByteBuffer.allocate(9 + payload.size)
    buffer.put(if (keyFrame) 1 else 0)
    buffer.putLong(timestampUs)
    buffer.put(payload)
    val frame = buffer.array()
    clientSnapshot().forEach { client -> client.sendBinary(frame) }
  }

  private fun requestKeyFrame() {
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
    clientSnapshot().forEach(::sendStatus)
  }

  private fun clientSnapshot(): List<TicketWebSocket> {
    return synchronized(clients) {
      clients.toList()
    }
  }

  private fun health(): TicketStreamHealth {
    val installedStores = TicketPackageSupport.installedLocalStores(this)
    val av1 = TicketAv1Support.isHardwareEncoderAvailable()
    val vivi = TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)
    val ok = running.get() && av1 && vivi
    return TicketStreamHealth(
      ok = ok,
      serverRunning = running.get(),
      av1HardwareEncoderAvailable = av1,
      projectionReady = mediaProjection != null,
      viviInstalled = vivi,
      accrescentInstalled = TicketScreenConfig.ACCRESCENT_PACKAGE in installedStores,
      installedStorePackages = installedStores,
      streamActive = streamActive,
      clients = clients.size,
      inactivityActive = ticketSessionOpen(),
      inactivityTimeoutMillis = TicketInactivityPolicy.TIMEOUT_MILLIS,
      inactivityRemainingMillis = inactivityRemainingMillis(),
      autoStartAllowed = true,
      autoStartBlockedReason = null,
      message = when {
        !vivi -> "ViVi is not installed from a local Pixel app store yet"
        !av1 -> "Hardware AV1 encoding is unavailable on this Pixel"
        mediaProjection == null -> "Screen capture permission is not ready"
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
    val violation = foregroundViolationReason()
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
        append("Cache-Control: no-store\r\n")
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
    private const val MAX_CLIENT_LOG_BYTES = 512
    private const val DEFAULT_BITRATE = 1_500_000
    private const val ENCODER_TIMEOUT_US = 50_000L
    private const val PRE_CAPTURE_APP_SETTLE_MILLIS = 800L
    private const val STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS = 5_000L
    private const val CLIENT_DISCONNECT_IDLE_GRACE_MILLIS = 10_000L
    private const val VIVI_FOREGROUND_INITIAL_DELAY_MILLIS = 1_500L
    private const val VIVI_FOREGROUND_CHECK_MILLIS = 750L
    private const val VIVI_FOREGROUND_GRACE_MILLIS = 3_000L
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

private fun browserPage(): String {
  return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Ticket</title>
  <style>
    :root { color-scheme: dark; font-family: system-ui, sans-serif; background: #05070a; color: #f3f7fb; }
    * { box-sizing: border-box; }
    body { margin: 0; width: 100vw; height: 100vh; min-height: 100vh; overflow: hidden; background: #05070a; }
    header { position: fixed; inset: 0; z-index: 5; display: grid; place-items: center; background: #05070a; }
    body.streaming header { display: none; }
    .start-panel { display: grid; justify-items: center; gap: 12px; }
    button { border: 1px solid #37445a; background: #1d2634; color: #f3f7fb; min-height: 36px; padding: 0 12px; border-radius: 6px; }
    button.primary { min-width: 112px; min-height: 44px; padding: 0 20px; background: #1f6feb; border-color: #4387ff; font-size: 16px; font-weight: 650; }
    button:disabled { opacity: .5; }
    main { position: relative; width: 100vw; height: 100vh; min-width: 0; min-height: 0; display: flex; align-items: center; justify-content: center; padding: 0; overflow: hidden; }
    canvas { display: none; width: var(--stream-css-width, 240px); height: var(--stream-css-height, 540px); max-width: 100%; max-height: 100%; background: #000; touch-action: none; }
    body.streaming canvas { display: block; }
    #idleTimer { position: absolute; top: 12px; right: 12px; z-index: 4; min-width: 58px; padding: 4px 8px; border-radius: 999px; background: rgba(10, 13, 18, .72); border: 1px solid rgba(185, 194, 207, .26); color: #edf3fb; font-size: 12px; line-height: 1.2; text-align: center; pointer-events: none; backdrop-filter: blur(6px); }
    #idleTimer[hidden] { display: none; }
    #idleTimer.urgent { color: #ffd6d6; border-color: rgba(255, 115, 115, .45); background: rgba(70, 18, 18, .74); }
    #status { max-width: min(420px, calc(100vw - 32px)); color: #b9c2cf; font-size: 13px; line-height: 1.35; text-align: center; pointer-events: none; }
    #status:empty { display: none; }
  </style>
</head>
<body>
  <header>
    <div class="start-panel">
      <button id="start" data-testid="start" type="button" class="primary">Start</button>
      <span id="status"></span>
    </div>
  </header>
  <main>
    <div id="idleTimer" data-testid="idle-timer" hidden>10:00</div>
    <canvas id="screen" width="540" height="1080"></canvas>
  </main>
  <script>
    const statusEl = document.getElementById('status');
    const idleTimerEl = document.getElementById('idleTimer');
    const canvas = document.getElementById('screen');
    const startButton = document.getElementById('start');
    const ctx = canvas.getContext('2d');
    let ws;
    let decoder;
    let configured = false;
    let needsKeyFrame = true;
    let pointerStart = null;
    let desiredActive = false;
    let startCommandSent = false;
    let connecting = null;
    let keepaliveTimer = null;
    let reconnectTimer = null;
    let selfHealTimer = null;
    let selfHealInFlight = false;
    let startupReconnectAttempts = 0;
    let lastActivitySentAt = 0;
    let streamDimensions = {width: 540, height: 1080};
    const maxTapDurationMs = 450;
    const maxTapTravelPx = 12;

    function setStatus(text = '') { statusEl.textContent = text || ''; }
    function showStart(message = '') {
      document.body.classList.remove('streaming');
      startButton.disabled = false;
      startButton.textContent = 'Start';
      setStatus(message);
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
    function resizeCanvasBox() {
      const main = document.querySelector('main');
      const style = getComputedStyle(main);
      const maxWidth = Math.max(1, main.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight));
      const maxHeight = Math.max(1, main.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom));
      const scale = Math.min(maxWidth / streamDimensions.width, maxHeight / streamDimensions.height);
      const cssWidth = Math.max(1, Math.floor(streamDimensions.width * scale));
      const cssHeight = Math.max(1, Math.floor(streamDimensions.height * scale));
      canvas.style.setProperty('--stream-css-width', `${'$'}{cssWidth}px`);
      canvas.style.setProperty('--stream-css-height', `${'$'}{cssHeight}px`);
    }
    function clientLog(event, details = {}) {
      try {
        fetch('/api/v1/client-log', {
          method: 'POST',
          cache: 'no-store',
          keepalive: true,
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({event, details})
        }).catch(() => {});
      } catch (_) {}
    }
    function socketUrl() {
      return (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/api/v1/session';
    }
    function closeDecoder() {
      configured = false;
      needsKeyFrame = true;
      document.body.classList.remove('streaming');
      updateIdleTimer(null);
      if (decoder) {
        try { decoder.close(); } catch (_) {}
        decoder = null;
      }
      ctx.clearRect(0, 0, canvas.width, canvas.height);
    }
    function stopKeepalive() {
      if (keepaliveTimer) clearInterval(keepaliveTimer);
      keepaliveTimer = null;
    }
    function clearReconnectTimer() {
      if (reconnectTimer) clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    function pageIsVisible() {
      return document.visibilityState !== 'hidden';
    }
    function scheduleStartupReconnect() {
      if (!desiredActive || configured) return false;
      startupReconnectAttempts += 1;
      const delayMs = Math.min(5000, 250 * startupReconnectAttempts);
      setStatus('Connecting');
      clearReconnectTimer();
      reconnectTimer = setTimeout(async () => {
        reconnectTimer = null;
        if (!desiredActive || configured) return;
        try {
          await connect(true);
          startCommandSent = true;
          send({type: 'start'});
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
      closeDecoder();
      showStart(pageLeaving ? '' : message);
    }
    async function connect(force = false) {
      if (ws && ws.readyState === WebSocket.OPEN) return;
      if (ws && ws.readyState === WebSocket.CONNECTING && connecting) return connecting;
      if (force && ws) {
        try { ws.close(); } catch (_) {}
      }
      setStatus('Connecting');
      connecting = new Promise((resolve, reject) => {
        const socket = new WebSocket(socketUrl());
        let settled = false;
        ws = socket;
        socket.binaryType = 'arraybuffer';
        const timeout = setTimeout(() => {
          if (settled) return;
          settled = true;
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
          clientLog('websocket_error');
          setStatus('Connection failed');
        };
        socket.onmessage = handleSocketMessage;
        socket.onclose = (event) => {
          clientLog('websocket_close', {code: event.code, reason: event.reason, clean: event.wasClean, desiredActive});
          clearTimeout(timeout);
          if (!settled) {
            settled = true;
            reject(new Error('websocket closed'));
          }
          if (ws === socket) ws = null;
          stopKeepalive();
          closeDecoder();
          if (desiredActive) {
            if (!configured && scheduleStartupReconnect()) return;
            endSession('connection_closed', false, 'Connection failed');
          } else {
            showStart();
          }
        };
      }).finally(() => {
        connecting = null;
      });
      return connecting;
    }
    async function handleSocketMessage(event) {
      if (typeof event.data === 'string') {
        const msg = JSON.parse(event.data);
        if (msg.type === 'config') await configureDecoder(msg);
        if (msg.type === 'health') {
          if (msg.data) updateIdleTimer(msg.data);
          if (
            desiredActive &&
            startCommandSent &&
            configured &&
            msg.data &&
            !msg.data.streamActive &&
            !msg.data.inactivityActive
          ) {
            startCommandSent = false;
            closeDecoder();
            scheduleStartupReconnect();
            return;
          }
          if (!configured && desiredActive) setStatus(msg.message || '');
        }
        if (msg.type === 'idle') updateIdleTimer(msg);
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
      decoder.decode(new EncodedVideoChunk({type: kind, timestamp, data: data.slice(9)}));
    }
    async function configureDecoder(config) {
      if (!('VideoDecoder' in window)) {
        clientLog('webcodecs_missing');
        await endSession('webcodecs_missing', false, 'This browser does not support WebCodecs');
        return;
      }
      const decoderConfig = {codec: config.codec, codedWidth: config.width, codedHeight: config.height};
      const supported = await VideoDecoder.isConfigSupported(decoderConfig);
      if (!supported.supported) {
        clientLog('av1_decode_unsupported', {config: decoderConfig});
        await endSession('av1_decode_unsupported', false, 'This browser cannot decode AV1 here');
        return;
      }
      canvas.width = config.width;
      canvas.height = config.height;
      streamDimensions = {width: config.width, height: config.height};
      canvas.style.setProperty('--stream-aspect', `${'$'}{config.width} / ${'$'}{config.height}`);
      canvas.dataset.streamWidth = String(config.width);
      canvas.dataset.streamHeight = String(config.height);
      resizeCanvasBox();
      closeDecoder();
      decoder = new VideoDecoder({
        output(frame) {
          ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
          frame.close();
        },
        error(error) {
          clientLog('decoder_error', {message: error.message});
          setStatus(`Decoder error: ${'$'}{error.message}`);
        }
      });
      decoder.configure(decoderConfig);
      configured = true;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      needsKeyFrame = true;
      document.body.classList.add('streaming');
      startButton.disabled = false;
      startButton.textContent = 'Start';
      setStatus('');
      requestAnimationFrame(resizeCanvasBox);
    }
    window.addEventListener('error', (event) => clientLog('window_error', {message: event.message}));
    window.addEventListener('unhandledrejection', (event) => clientLog('unhandled_rejection', {message: String(event.reason && event.reason.message || event.reason)}));
    window.addEventListener('resize', resizeCanvasBox);
    async function ensureStreaming(reason = 'self_heal') {
      if (!pageIsVisible() || selfHealInFlight) return;
      if (desiredActive && configured) return;
      selfHealInFlight = true;
      try {
        const health = await refreshHealth();
        if (!health.viviInstalled || !health.av1HardwareEncoderAvailable) return;
        if (!desiredActive) {
          clientLog('self_heal_start', {reason});
          await start();
        } else if (!configured && !reconnectTimer) {
          scheduleStartupReconnect();
        }
      } catch (_) {
        if (!desiredActive) {
          clientLog('self_heal_start_without_health', {reason});
          await start();
        } else if (!configured && !reconnectTimer) {
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
    async function refreshHealth() {
      const response = await fetch('/api/v1/health', {cache: 'no-store'});
      const health = await response.json();
      updateIdleTimer(health);
      if (!health.viviInstalled || !health.av1HardwareEncoderAvailable) {
        setStatus(health.message || 'Unavailable');
      } else if (!desiredActive) {
        setStatus('');
      }
      return health;
    }
    async function start() {
      if (desiredActive) return;
      desiredActive = true;
      startCommandSent = false;
      startupReconnectAttempts = 0;
      clearReconnectTimer();
      closeDecoder();
      startButton.disabled = true;
      startButton.textContent = 'Starting';
      setStatus('');
      try {
        await connect(true);
        startCommandSent = true;
        send({type: 'start'});
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
      noteActivity(true);
      pointerStart = {...point(event), at: performance.now()};
      canvas.setPointerCapture(event.pointerId);
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
      } else if (distance < maxTapTravelPx) {
        send({type: 'long_press'});
      } else {
        send({type: 'swipe'});
      }
      pointerStart = null;
    });
    window.addEventListener('keydown', () => noteActivity(true), {capture: true});
    window.addEventListener('wheel', () => noteActivity(), {capture: true, passive: true});
    window.addEventListener('pointermove', () => noteActivity(), {capture: true, passive: true});
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden' && desiredActive && configured) {
        endSession('page_hidden', true);
      } else if (document.visibilityState === 'visible') {
        ensureStreaming('visibility_visible');
      }
    });
    window.addEventListener('pagehide', () => { if (desiredActive) endSession('page_hidden', true); });
    window.addEventListener('pageshow', () => ensureStreaming('pageshow'));
    window.addEventListener('focus', () => ensureStreaming('focus'));
    window.addEventListener('beforeunload', () => { if (desiredActive) endSession('page_unload', true); });
    document.getElementById('start').addEventListener('click', () => start());
    resizeCanvasBox();
    startSelfHealTimer();
    ensureStreaming('initial_load').catch(() => setStatus('Unavailable'));
  </script>
</body>
</html>
  """.trimIndent()
}
