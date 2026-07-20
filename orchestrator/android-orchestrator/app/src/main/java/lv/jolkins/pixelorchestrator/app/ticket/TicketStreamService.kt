package lv.jolkins.pixelorchestrator.app.ticket

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lv.jolkins.pixelorchestrator.app.MainActivity
import lv.jolkins.pixelorchestrator.app.SupervisorService
import lv.jolkins.pixelorchestrator.app.phoneautomation.ChatGPTRootShellInput
import lv.jolkins.pixelorchestrator.app.phoneautomation.ChatGPTPhoneRunner
import lv.jolkins.pixelorchestrator.app.phoneautomation.ChatGPTSpacetimeWorker
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhonePortraitLock
import lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessControl
import lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessState
import lv.jolkins.pixelorchestrator.app.phoneautomation.TouchBrightnessRuntimeState
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.time.Instant
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    val detectedButtonBounds: String? = null
  )

  private data class RigasSatiksmeQueueHint(
    val pendingAfterThis: Int = 0,
    val ticketPriorityActive: Boolean = false
  )

  private data class RigasSatiksmeBatchJob(
    val requestId: String,
    val digits: String,
    val createdAt: String = ""
  )

  private class RigassatiksmeLoginCodeHolder {
    @Volatile private var value: String? = null

    fun put(code: String) {
      value = code
    }

    fun consume(): String? {
      val current = value
      value = null
      return current
    }

    fun peek(): String? = value

    fun clear() {
      value = null
    }
  }

  private data class PendingRigasSatiksmeReturnCleanup(
    val requestId: String,
    val phases: MutableMap<String, Long>,
    val requestStartedAtMillis: Long,
    val reason: String
  )

  private data class ControlCodeImmediateStartDecision(
    val accepted: Boolean,
    val reason: String
  )

  private data class GeneratedControlCodeResult(
    val value: String,
    val hierarchy: String,
    val streamEpoch: Long = 0L,
    val minFrameSequence: Long = 0L,
    val resultProof: String = "",
    val resultProofAtMillis: Long = 0L,
    val imageBytes: ByteArray = ByteArray(0)
  )

  private data class ControlCodeBrowserCaptureAck(
    val requestId: String,
    val ok: Boolean,
    val reason: String,
    val frameEpoch: Long,
    val frameSequence: Long,
    val receivedAtMillis: Long
  )

  private data class FastControlCodePopupTransaction(
    val open: TicketViviPageAction? = null,
    val openCandidateZone: String? = null,
    val openDetectedButtonBounds: String? = null,
    val input: TicketViviPageAction,
    val submit: TicketViviPageAction,
    val inputSource: String,
    val submitSource: String
  )

  private data class FastControlCodeDelivery(
    val ok: Boolean,
    val reason: String,
    val value: String = "",
    val cleanupStart: FastControlCodeCleanupStart? = null,
    val generatedHierarchy: String = "",
    val streamEpoch: Long = 0L,
    val minFrameSequence: Long = 0L,
    val resultProof: String = "",
    val resultProofAtMillis: Long = 0L,
    val cleanupRequired: Boolean = true
  )

  private data class FastControlCodeCleanupStart(
    val startedAtMillis: Long,
    val closeAction: String,
    val action: TicketViviPageAction?,
    val closeSucceeded: Boolean,
    val fallbackState: TicketViviRecoveryState? = null
  )

  private data class ControlCodeResultWaitOutcome(
    val generated: GeneratedControlCodeResult? = null,
    val failureReason: String = "control_code_result_timeout",
    val failureHierarchy: String = ""
  )

  private enum class ControlCodeEnteredValueProof {
    VALUE_READY,
    STATIC_BLANK,
    UNSAFE
  }

  private data class RootViviObservation(
    val state: TicketViviRecoveryState,
    val hierarchy: String?,
    val durationMillis: Long,
    val error: String = ""
  )

  private data class TicketAutopilotResult(
    val success: Boolean,
    val state: TicketViviRecoveryState,
    val step: String
  )

  private enum class TicketRecoveryMode { ACTIVE_SOFT, FRESH_RESET }

  private data class TicketRecoveryRuntime(
    val generation: Long = 0L,
    val state: String = "idle",
    val reason: String? = null,
    val mode: TicketRecoveryMode? = null,
    val result: String = "none",
    val step: String = "idle",
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L
  )

  private data class ViviLoginCredentials(
    val email: String,
    val secret: String
  )

  private class TicketVideoSendState {
    var inFlight: Boolean = false
    var inFlightSinceMillis: Long = 0L
    var pendingFrame: ByteArray? = null
    var pendingKeyFrame: Boolean = false
    var pendingQueuedAtMillis: Long = 0L
    var waitingForKeyFrame: Boolean = false
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val controlCodeAutomationClaims = AtomicLong(0L)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val rootExecutor = TicketRootCommandWorker()
  private val inputRootExecutor = TicketRootCommandWorker()
  private val wakeRootExecutor = TicketRootCommandWorker()
  private val foregroundRootExecutor = TicketRootCommandWorker()
  private val chatgptRunner by lazy {
    ChatGPTPhoneRunner(
      this,
      shellInput = ChatGPTRootShellInput(
        rootExecutor = inputRootExecutor,
        clipboardTextSetter = ::setChatGptClipboardText
      )
    )
  }
  private var chatgptSpacetimeWorker: ChatGPTSpacetimeWorker? = null
  private var ticketSpacetimeWorker: TicketSpacetimeWorker? = null
  private val ticketSpacetimePhoneOutbox = TicketSpacetimePhoneOutbox(
    maxLossyMessages = TICKET_SPACETIME_PHONE_MESSAGE_LIMIT,
    criticalTtlMillis = TICKET_SPACETIME_CRITICAL_MESSAGE_TTL_MILLIS,
    criticalKey = ::ticketSpacetimeCriticalMessageKey,
    nowMillis = SystemClock::elapsedRealtime
  )
  private val startupTracePhaseLock = Any()
  private val startupTraceOncePhases = mutableSetOf<String>()
  private val serverMutex = Mutex()
  private val controlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val protectedControlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val canceledRigasSatiksmeBatchIds = Collections.synchronizedSet(mutableSetOf<String>())
  private val videoClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val clientInfo = mutableMapOf<TicketWebSocket, TicketClientInfo>()
  private val videoSendStates = mutableMapOf<TicketWebSocket, TicketVideoSendState>()
  private val encoderLock = Any()
  private val sessionMutex = Mutex()

  private fun setChatGptClipboardText(text: String): Boolean {
    return runCatching {
      val clipboard = getSystemService(ClipboardManager::class.java)
      clipboard.setPrimaryClip(ClipData.newPlainText("chatgpt-broker", text))
      true
    }.getOrDefault(false)
  }
  private val controlCodePhoneMutationLane = ControlCodePhoneMutationLane()
  private val controlCodeBrowserCaptureLock = Object()
  private val running = AtomicBoolean(false)
  private val rootHardwareH264CaptureEngine = TicketRootHardwareH264CaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootHardwareH264CaptureFrame,
    onStateChanged = { health ->
      handleRootHardwareH264CaptureStateChanged(health)
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
  private var controlExitCleanupJob: Job? = null
  private val pendingRigasSatiksmeReturnCleanupLock = Any()
  private var pendingRigasSatiksmeReturnCleanupJob: Job? = null
  private var pendingRigasSatiksmeReturnCleanupStarted: Boolean = false
  @Volatile private var lastRigasSatiksmeBatchId: String? = null
  @Volatile private var lastRigasSatiksmeBatchStatus: String = "idle"
  @Volatile private var lastRigasSatiksmeBatchActiveRequestId: String? = null
  @Volatile private var lastRigasSatiksmeBatchJobCount: Int = 0
  @Volatile private var lastRigasSatiksmeBatchCompletedCount: Int = 0
  @Volatile private var lastRigasSatiksmeBatchResultRequestId: String? = null
  @Volatile private var lastRigasSatiksmeBatchResultStatus: String? = null
  @Volatile private var lastRigasSatiksmeBatchResultReason: String? = null
  @Volatile private var lastRigasSatiksmeBatchCancelReason: String? = null
  @Volatile private var lastRigasSatiksmeBatchPhases: Map<String, Long> = emptyMap()
  @Volatile private var lastRigasSatiksmeBatchCompletedAtMillis: Long = 0L
  @Volatile private var rigassatiksmeLoginRequestId: String? = null
  @Volatile private var rigassatiksmeLoginPhoneLast4: String? = null
  @Volatile private var rigassatiksmeLoginState: String = "idle"
  @Volatile private var rigassatiksmeLoginLastState: String = "idle"
  @Volatile private var rigassatiksmeLoginLastFailureReason: String? = null
  @Volatile private var rigassatiksmeLoginStartedAtMillis: Long = 0L
  @Volatile private var rigassatiksmeLoginCompletedAtMillis: Long = 0L
  @Volatile private var rigassatiksmeLoginAttempts: Long = 0L
  @Volatile private var rigassatiksmeLoginSuccesses: Long = 0L
  @Volatile private var rigassatiksmeLoginFailures: Long = 0L
  @Volatile private var rigassatiksmeLoginFailureByReason: Map<String, Long> = emptyMap()
  @Volatile private var rigassatiksmeLoginAwaitingSms: Boolean = false
  @Volatile private var rigassatiksmeLoginLastResultJson: String? = null
  @Volatile private var rigassatiksmeLoginLastResultAtMillis: Long = 0L
  private val rigassatiksmeLoginFailureByReasonLock = Any()
  private val rigassatiksmeLoginCodeHolder = RigassatiksmeLoginCodeHolder()
  private var rigassatiksmeLoginJob: Job? = null
  private var rootH264BlankProbeJob: Job? = null
  private var ticketScreenWakeLock: PowerManager.WakeLock? = null
  private var ticketScreenWakeLockUsesTouchBrightnessOwner: Boolean? = null
  private val viviStateMemory = TicketViviStateMemory()
  private val ticketRecoveryLock = Any()
  private var ticketRecoveryJob: Job? = null
  @Volatile private var ticketRecovery = TicketRecoveryRuntime()
  private var streamWatchdogJob: Job? = null
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
  @Volatile private var streamActive: Boolean = false
  @Volatile private var hardwareCaptureVerified: Boolean = false
  @Volatile private var hardwareFrameBroadcastAllowed: Boolean = false
  @Volatile private var activeCaptureMode: String = CAPTURE_MODE_IDLE
  @Volatile private var fallbackReason: String? = null
  @Volatile private var hardwareReliabilityFailures: Int = 0
  @Volatile private var hardwareMarkedUnreliableAtMillis: Long = 0L
  @Volatile private var hardwareUnreliableReason: String? = null
  @Volatile private var lastObservedHardwareRestartCount: Long = 0L
  @Volatile private var hardwareCaptureSnapshot: TicketHardwareH264Health = TicketHardwareH264Health()
  @Volatile private var lastRootH264BlankProbeAtMillis: Long = 0L
  @Volatile private var lastRootH264VisibleProbePassedAtMillis: Long = 0L
  @Volatile private var lastRootH264BlankProbeResult: String = "not_run"
  @Volatile private var lastPublishedRootHardwareH264HealthSignature: String = ""
  @Volatile private var rootH264BlankProbeRecoveries: Long = 0L
  @Volatile private var rootH264BlankProbeFailures: Long = 0L
  @Volatile private var streamWatchdogStage: String = "idle"
  @Volatile private var lastStreamWatchdogAction: String = "none"
  @Volatile private var lastStreamWatchdogReason: String? = null
  @Volatile private var lastStreamRecoveryResult: String = "none"
  @Volatile private var lastStreamRecoveryFailureReason: String? = null
  @Volatile private var lastStreamRecoveryAtMillis: Long = 0L
  @Volatile private var spacetimeDesiredRecoveryStage: String = "idle"
  @Volatile private var lastSpacetimeDesiredRecoveryAction: String = "none"
  @Volatile private var lastSpacetimeDesiredRecoveryResult: String = "none"
  @Volatile private var lastSpacetimeDesiredRecoveryFailureReason: String? = null
  @Volatile private var lastSpacetimeDesiredRecoveryProbeResult: String? = null
  @Volatile private var lastSpacetimeDesiredRecoveryAtMillis: Long = 0L
  @Volatile private var inputGateReason: String = "no_active_control"
  @Volatile private var controlCodeModeActive: Boolean = false
  @Volatile private var controlCodeModeEnteredAtMillis: Long = 0L
  @Volatile private var controlCodeTransitionGraceUntilMillis: Long = 0L
  @Volatile private var lastControlCodeSurfaceState: String? = null
  @Volatile private var lastControlCodeSurfaceSeenAtMillis: Long = 0L
  @Volatile private var lastControlExitDirtySurfaceState: String? = null
  private val inactivityStateLock = Any()
  @Volatile private var viewerInputGeneration: Long = 0L
  @Volatile private var lastViewerInputAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var lastSessionStopReason: String? = null
  @Volatile private var lastForegroundViolationReason: String? = null
  @Volatile private var foregroundViolationCount: Int = 0
  @Volatile private var lastForegroundRecoveryAtMillis: Long = 0L
  @Volatile private var lastForegroundGuardRecentTicketDetailSkipAtMillis: Long = 0L
  @Volatile private var lastActiveGuardRecoverySessionRetryAtMillis: Long = 0L
  @Volatile private var lastTicketScreenWakeAtMillis: Long = 0L
  @Volatile private var lastMessage: String = "Ticket server is starting"
  @Volatile private var lastEncoderStartAtMillis: Long = 0L
  @Volatile private var lastConfigSentAtMillis: Long = 0L
  @Volatile private var lastFrameEncodedAtMillis: Long = 0L
  @Volatile private var lastKeyFrameEncodedAtMillis: Long = 0L
  @Volatile private var lastFrameSentAtMillis: Long = 0L
  @Volatile private var lastKeyFrameRequestedAtMillis: Long = 0L
  @Volatile private var pendingStartupKeyFrameReason: String? = null
  @Volatile private var lastVideoClientConnectedAtMillis: Long = 0L
  @Volatile private var secureWindowCaptureBypassActive: Boolean = false
  @Volatile private var secureWindowCaptureBypassMessage: String = "Secure-window capture bypass is inactive"
  @Volatile private var encodedFrames: Long = 0L
  @Volatile private var sentFrames: Long = 0L
  @Volatile private var keyFrames: Long = 0L
  @Volatile private var droppedVideoFrames: Long = 0L
  @Volatile private var slowVideoWrites: Long = 0L
  @Volatile private var closedSlowVideoClients: Long = 0L
  @Volatile private var replacedClientSockets: Long = 0L
  private val clientGenerationCounter = AtomicLong(0L)
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
  @Volatile private var lastControlCodeRequestId: String? = null
  @Volatile private var lastControlCodeRequestStatus: String = "idle"
  @Volatile private var lastControlCodeRequestReason: String? = null
  @Volatile private var lastControlCodeRequestDurationMillis: Long? = null
  @Volatile private var lastControlCodeRequestPhases: Map<String, Long> = emptyMap()
  @Volatile private var lastControlCodeRequestCompletedAtMillis: Long = 0L
  @Volatile private var lastControlCodeFastReadyRevision: String = ""
  @Volatile private var lastControlCodeCommandOwner: String? = null
  @Volatile private var lastControlCodeCommandApp: String? = null
  @Volatile private var lastControlCodeCommandFlow: String? = null
  @Volatile private var latestTicketReselectStatus: String = "idle"
  @Volatile private var latestTicketReselectReason: String = ""
  @Volatile private var latestTicketReselectCommandId: String = ""
  @Volatile private var latestTicketReselectPhase: String = "idle"
  @Volatile private var latestTicketReselectStartedAtMillis: Long = 0L
  @Volatile private var latestTicketReselectTicketDetailAtMillis: Long = 0L
  @Volatile private var latestTicketReselectCompletedAtMillis: Long = 0L
  @Volatile private var latestTicketReselectFreshFrameAtMillis: Long = 0L
  @Volatile private var latestTicketReselectProofSource: String = ""
  @Volatile private var latestTicketReselectProofHoldUntilMillis: Long = 0L
  @Volatile private var latestTicketReselectLastProofNudgeAtMillis: Long = 0L
  private val latestTicketReselectStateLock = Any()
  @Volatile private var latestTicketReselectGeneration: Long = 0L
  private var latestTicketReselectRecoveryJob: Job? = null
  private var latestTicketReselectSettleJob: Job? = null
  private var latestTicketReselectProofIdleStopJob: Job? = null
  @Volatile private var pendingControlCodeBrowserCaptureRequestId: String? = null
  @Volatile private var pendingControlCodeBrowserCaptureAck: ControlCodeBrowserCaptureAck? = null
  @Volatile private var lastControlCodeBrowserCaptureReason: String? = null
  @Volatile private var lastControlCodeBrowserCaptureCompletedAtMillis: Long = 0L
  @Volatile private var lastPixelTicketEventSeq: Long = 0L
  @Volatile private var lastPixelTicketState: String = ""
  @Volatile private var lastPixelTicketEventSentAtMillis: Long = 0L
  @Volatile private var duplicateControlCodeResultCount: Long = 0L
  @Volatile private var lastDuplicateControlCodeRequestId: String? = null
  @Volatile private var lastDuplicateControlCodeResultAtMillis: Long = 0L
  private val recentControlCodeResultMessages = mutableMapOf<String, Pair<Long, String>>()
  private val recentControlCodeResultOrder = ArrayDeque<String>()
  @Volatile private var lastPostCleanupFreshFrameVerifiedAtMillis: Long = 0L
  @Volatile private var lastPostCleanupFreshFrameVerificationReason: String? = null
  @Volatile private var lastWakeStartedAtMillis: Long = 0L
  @Volatile private var lastWakeSucceeded: Boolean? = null

  override fun onCreate() {
    super.onCreate()
    serviceScope.launch(Dispatchers.IO) {
      inputRootExecutor.runScript(TicketUiautomatorDump.startupSweepCommand())
      PhonePortraitLock.force(inputRootExecutor)
      if (!PhonePortraitLock.verify(inputRootExecutor)) {
        recordTicketEvent("phone_portrait_lock_unverified", "startup")
      }
      rootHardwareH264CaptureEngine.cleanupStaleProcesses()
      rootHardwareH264CaptureEngine.probe()
    }
    chatgptSpacetimeWorker = ChatGPTSpacetimeWorker(
      scope = serviceScope,
      rootExecutor = inputRootExecutor,
      runner = chatgptRunner,
      ticketPriorityActive = ::chatgptTicketPriorityActive
    ).also { worker ->
      worker.start()
    }
    ticketSpacetimeWorker = TicketSpacetimeWorker(
      scope = serviceScope,
      rootExecutor = inputRootExecutor,
      service = this
    ).also { worker ->
      worker.start()
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
    latestTicketReselectRecoveryJob?.cancel()
    latestTicketReselectRecoveryJob = null
    latestTicketReselectSettleJob?.cancel()
    latestTicketReselectSettleJob = null
    latestTicketReselectProofIdleStopJob?.cancel()
    latestTicketReselectProofIdleStopJob = null
    chatgptSpacetimeWorker?.stop()
    chatgptSpacetimeWorker = null
    ticketSpacetimeWorker?.stop()
    ticketSpacetimeWorker = null
    rootH264BlankProbeJob?.cancel()
    rootH264BlankProbeJob = null
    cancelInactivityTimer()
    cancelForegroundGuard()
    postRemoteTapForegroundCheckJob?.cancel()
    postRemoteTapForegroundCheckJob = null
    controlExitCleanupJob?.cancel()
    controlExitCleanupJob = null
    cancelPendingRigasSatiksmeReturnCleanup("service_destroyed")
    cancelTicketRecovery("service_destroyed")
    if (serviceEnabled && !touchBrightnessOwnsTicketBrightness()) {
      runCatching { runBlocking { enforceTicketSafeBrightness("service_destroyed_service_enabled") } }
    } else if (serviceEnabled) {
      ticketBrightnessGuardLastMessage = "Ticket brightness guard parked because touch brightness owns panel brightness"
    } else {
      runCatching { runBlocking { restoreTicketBrightness("service_destroyed_service_off") } }
      PhoneAutomationServiceBridge.setRemoteScreenBrightnessState(null)
    }
    PhoneAutomationServiceBridge.setBlackoutOverlaySuppressed(false)
    rootHardwareH264CaptureEngine.stop("service_destroyed")
    runCatching { runBlocking { rootHardwareH264CaptureEngine.cleanupStaleProcesses() } }
    closeAllClients("service_destroyed")
    runCatching { runBlocking { disableSecureWindowCaptureBypass() } }
    runCatching { runBlocking { disableNotificationLockdown("service_destroyed") } }
    releaseTicketScreenAwake()
    stopLocalServer()
    inputRootExecutor.close()
    wakeRootExecutor.close()
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
          recordTicketEvent("ticket_server_start_failed", safeErrorDetail(error))
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
        method == "GET" && (path == "/" || path == "/api/v1/bootstrap" || path == "/api/v1/cache-cleanup") ->
          sendText(output, 410, "Pixel-local viewer retired; use the public Ticket service")
        method == "GET" && path == "/api/v1/health" -> sendJson(output, health())
        (method == "GET" || method == "POST") && path.startsWith("/api/v1/chatgpt/") -> {
          sendText(output, 410, "chatgpt phone http control disabled; use spacetime queue")
        }
        method == "POST" && path == "/api/v1/session/start" -> sendJson(output, startTicketSession())
        method == "POST" && path == "/api/v1/session/recover" -> sendJson(output, recoverTicketSession(bodyText))
        method == "POST" && path == "/api/v1/session/stop" -> sendJson(output, handleBrowserStopRequest(bodyText))
        method == "POST" && path == "/api/v1/rs/login/start" -> {
          sendJsonPayload(output, handleRigassatiksmeLoginStartHttp(bodyText))
        }
        method == "POST" && path == "/api/v1/rs/login/sms" -> {
          sendJsonPayload(output, handleRigassatiksmeLoginSmsHttp(bodyText))
        }
        method == "GET" && path == "/api/v1/rs/login/status" -> {
          sendJsonPayload(output, rigassatiksmeLoginStatusPayload())
        }
        method == "POST" && path == "/api/v1/rs/login/cancel" -> {
          sendJsonPayload(output, handleRigassatiksmeLoginCancelHttp(bodyText))
        }
        else -> sendText(output, 404, "not found")
      }
    }.onFailure { error ->
      recordTicketEvent("http_request_failed", safeErrorDetail(error))
      runCatching {
        if (!socket.isClosed) {
          sendText(output, 500, "internal error: ${safeErrorDetail(error)}")
        }
      }
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
    lateinit var client: TicketWebSocket
    val info = TicketClientInfo(
      video = video,
      viewerId = queryParam(query, "viewer"),
      pageId = queryParam(query, "page"),
      pageVersion = queryParam(query, "pageVersion") ?: queryParam(query, "page_version"),
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
          recordTicketEvent("stream_client_closed", streamClientTraceDetail(info, "closed"))
        } else {
          controlClients.remove(client)
          protectedControlClients.remove(client)
        }
        synchronized(clientInfo) {
          clientInfo.remove(client)
        }
        synchronized(videoSendStates) {
          videoSendStates.remove(client)
        }
        serviceScope.launch {
          sessionMutex.withLock {
            if (totalClientCount() == 0) {
              scheduleClientDisconnectGraceLocked()
            }
          }
        }
      }
    )
    extendStartupDisconnectGrace()
    sessionMutex.withLock {
      closeDuplicateViewerClients(info)
      clientDisconnectStopJob?.cancel()
      clientDisconnectStopJob = null
      if (video) {
        videoClients.add(client)
        recordTicketEvent("stream_client_opened", streamClientTraceDetail(info, "opened"))
        lastVideoClientConnectedAtMillis = SystemClock.elapsedRealtime()
      } else {
        controlClients.add(client)
      }
      synchronized(clientInfo) {
        clientInfo[client] = info
      }
      markViewerInput("client_connected")
    }
    if (video) {
      startTicketSessionForVideoClientOpen(info)
    }
    if (ticketSessionOpen()) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "client_connected")
      recordTicketEvent(
        "client_connected",
        "generation=${info.generation} video=$video"
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
    scheduleStreamWatchdog("client_connected")
    client.readLoop()
  }

  private suspend fun startTicketSessionForVideoClientOpen(info: TicketClientInfo) {
    if (streamActive) {
      return
    }
    if (controlCodeRequestActive()) {
      recordTicketEvent("stream_client_start_deferred_for_control_code", streamClientTraceDetail(info, "control_code_active"))
      return
    }
    recordTicketEvent("stream_client_immediate_start", streamClientTraceDetail(info, "video_socket_open"))
    val response = startTicketSession()
    recordTicketEvent(
      "stream_client_immediate_start_result",
      "ok=${response.ok} generation=${info.generation}"
    )
  }

  private suspend fun handleClientCommand(client: TicketWebSocket, message: String) {
    val element = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "generate_rigassatiksme_qr_batch" -> {
        val batchId = element["batchId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val owner = element["owner"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val app = element["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val flow = element["flow"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ticketPriorityActive = element["ticketPriorityActive"]?.jsonPrimitive?.booleanOrNull == true
        if (!controlCodeCommandEnvelopeMatches(
            owner,
            app,
            flow,
            TicketScreenConfig.TICKET_QR_OWNER_RIGAS_SATIKSME,
            TicketScreenConfig.TICKET_QR_APP_RIGAS_SATIKSME,
            TicketScreenConfig.TICKET_QR_FLOW_MONTHLY_TICKET
          )
        ) {
          recordRejectedControlCodeCommand("", owner, app, flow, "wrong_command_owner")
          return
        }
        val jobs = element["jobs"]?.jsonArray?.mapNotNull { item ->
          val itemObject = item.jsonObject
          val requestId = itemObject["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
          val digits = itemObject["digits"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
          val createdAt = itemObject["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
          if (requestId.isBlank()) null else RigasSatiksmeBatchJob(requestId, digits, createdAt)
        }.orEmpty()
        serviceScope.launch {
          handleGenerateRigasSatiksmeQrBatch(client, batchId, jobs, ticketPriorityActive)
        }
      }
      "cancel_rigassatiksme_qr_batch" -> {
        val batchId = element["batchId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull ?: "batch_canceled"
        if (batchId.isNotBlank()) {
          canceledRigasSatiksmeBatchIds.add(batchId)
          lastRigasSatiksmeBatchStatus = "canceling"
          lastRigasSatiksmeBatchCancelReason = reason
          recordTicketEvent("rs_monthly_ticket_batch_cancel_requested", "batch=$batchId reason=$reason")
          broadcastStatus()
        }
      }
      "rigassatiksme_login_start" -> {
        val requestId = element["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val phone = element["phone"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val locale = element["locale"]?.jsonPrimitive?.contentOrNull.orEmpty()
        handleRigassatiksmeLoginStart(requestId, phone, locale, client)
      }
      "rigassatiksme_login_sms" -> {
        val requestId = element["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val code = element["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        handleRigassatiksmeLoginSms(requestId, code, client)
      }
      "cancel_rigassatiksme_login" -> {
        val requestId = element["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "broker_cancel" }
        handleRigassatiksmeLoginCancel(requestId, reason)
      }
    }
  }

  private fun handleVideoClientCommand(client: TicketWebSocket, message: String) {
    val element = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "keyframe" -> sendCachedKeyFrameOrRequest(client, element["reason"]?.jsonPrimitive?.contentOrNull ?: "video_client_request")
    }
  }

  internal suspend fun handleTicketSpacetimeCommand(command: TicketSpacetimeCommand): TicketSpacetimeCommandResult {
    val payload = runCatching { json.parseToJsonElement(command.payloadJson).jsonObject }.getOrNull()
    val reason = payload?.stringValue("reason")
      ?: payload?.stringValue("acceptedReason")
      ?: command.reason.ifBlank { command.commandType }
    return try {
      when (command.commandType) {
        "start" -> {
          if (ticketSpacetimeBackgroundStreamAlreadyHealthy()) {
            TicketSpacetimeCommandResult(ok = true, reason = "stream_already_healthy", streamState = ticketSpacetimeStreamState())
          } else {
            startTicketSession().toTicketSpacetimeCommandResult(reason)
          }
        }
        "activity" -> {
          markViewerInput(reason.ifBlank { "spacetime_activity" })
          TicketSpacetimeCommandResult(ok = true, reason = "activity_recorded", streamState = ticketSpacetimeStreamState())
        }
        "keyframe" -> {
          if (ticketSpacetimeBackgroundStreamAlreadyHealthy()) {
            TicketSpacetimeCommandResult(ok = true, reason = "stream_already_healthy", streamState = ticketSpacetimeStreamState())
          } else {
            requestKeyFrame(reason.ifBlank { "spacetime_keyframe" })
            TicketSpacetimeCommandResult(ok = true, reason = "keyframe_requested", streamState = ticketSpacetimeStreamState())
          }
        }
        "recover_stream" -> {
          if (ticketSpacetimeBackgroundStreamAlreadyHealthy()) {
            TicketSpacetimeCommandResult(ok = true, reason = "stream_already_healthy", streamState = ticketSpacetimeStreamState())
          } else {
            recoverTicketSession(
              """{"reason":${json.encodeToString(reason.ifBlank { "spacetime_recover_stream" })}}"""
            ).toTicketSpacetimeCommandResult(reason)
          }
        }
        "force_ticket_reselect" -> controlCodePhoneMutationLane.withOwnership {
          forceLatestTicketReselect(
            reason = reason.ifBlank { "admin_force_latest_ticket_reselect" },
            commandId = command.id
          )
        }
        "prepare_control_code" -> {
          val owner = payload?.stringValue("owner").orEmpty()
          val app = payload?.stringValue("app").orEmpty()
          val flow = payload?.stringValue("flow").orEmpty()
          if (!controlCodeCommandEnvelopeMatches(owner, app, flow, TicketScreenConfig.TICKET_QR_OWNER_TICKET, TicketScreenConfig.TICKET_QR_APP_VIVI, TicketScreenConfig.TICKET_QR_FLOW_CONTROL_CODE)) {
            recordRejectedControlCodeCommand("", owner, app, flow, "wrong_command_owner")
            TicketSpacetimeCommandResult(ok = false, reason = "wrong_command_owner", streamState = ticketSpacetimeStreamState())
          } else {
            recordTicketEvent("control_code_prepare_not_required", "immediate_submission")
            TicketSpacetimeCommandResult(ok = true, reason = "prepare_control_code_done", streamState = ticketSpacetimeStreamState())
          }
        }
        "generate_control_code" -> {
          serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            handleGenerateControlCode(
              replyClient = null,
              requestId = payload?.stringValue("requestId").orEmpty(),
              digits = payload?.stringValue("digits").orEmpty(),
              owner = payload?.stringValue("owner").orEmpty(),
              app = payload?.stringValue("app").orEmpty(),
              flow = payload?.stringValue("flow").orEmpty(),
              resultImage = payload?.booleanValue("resultImage") == true,
              queueHint = payload?.jsonObjectValue("rsQueueHint")?.let { hint ->
                RigasSatiksmeQueueHint(
                  pendingAfterThis = hint["pendingAfterThis"]?.jsonPrimitive?.intOrNull ?: 0,
                  ticketPriorityActive = hint["ticketPriorityActive"]?.jsonPrimitive?.booleanOrNull == true
                )
              } ?: RigasSatiksmeQueueHint(),
              fastRevision = payload?.stringValue("fastRevision").orEmpty()
            )
          }
          TicketSpacetimeCommandResult(ok = true, reason = "generate_control_code_started", streamState = ticketSpacetimeStreamState())
        }
        "control_code_browser_capture" -> {
          handleControlCodeBrowserCapture(
            requestId = payload?.stringValue("requestId").orEmpty(),
            ok = payload?.booleanValue("ok") == true || payload?.booleanValue("accepted") == true,
            reason = reason.ifBlank { "browser_capture_confirmed" },
            frameEpoch = payload?.longValue("candidateFrameEpoch") ?: 0L,
            frameSequence = payload?.longValue("candidateFrameSequence") ?: 0L
          )
          TicketSpacetimeCommandResult(ok = true, reason = "browser_capture_recorded", streamState = ticketSpacetimeStreamState())
        }
        "control_code_result_ack" -> {
          recordTicketEvent("control_code_result_ack_spacetime", reason.ifBlank { command.id })
          TicketSpacetimeCommandResult(ok = true, reason = "control_code_result_ack_ignored_direct", streamState = ticketSpacetimeStreamState())
        }
        "control_exit" -> {
          scheduleControlExitSoftSettle(reason.ifBlank { "spacetime_control_exit" })
          TicketSpacetimeCommandResult(ok = true, reason = "control_exit_scheduled", streamState = ticketSpacetimeStreamState())
        }
        else -> {
          recordTicketEvent("spacetime_command_unsupported", "${command.commandType}:${command.id}")
          TicketSpacetimeCommandResult(ok = false, reason = "unsupported_command", streamState = ticketSpacetimeStreamState())
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      recordTicketEvent("spacetime_command_failed", "${command.commandType}:${safeErrorDetail(error)}")
      TicketSpacetimeCommandResult(ok = false, reason = safeErrorDetail(error).ifBlank { "command_failed" }, streamState = ticketSpacetimeStreamState())
    }
  }

  internal suspend fun handleTicketSpacetimeDesiredActive(reason: String): TicketSpacetimeCommandResult {
    val cleanReason = reason.ifBlank { "spacetime_desired_active" }
    if (streamActive && ticketSessionState in setOf(TICKET_SESSION_STARTING, TICKET_SESSION_LIVE, TICKET_SESSION_CONTROL_ACTIVE, TICKET_SESSION_CONTROL_TRANSITION)) {
      ensureEncoderIfPossible()
      return TicketSpacetimeCommandResult(ok = true, reason = "already_active", streamState = ticketSpacetimeStreamState())
    }
    if (shouldRunTicketSpacetimeDesiredRecovery(SystemClock.elapsedRealtime())) {
      return recoverTicketSpacetimeDesiredStream(cleanReason)
    }
    return startTicketSession().toTicketSpacetimeCommandResult(cleanReason)
  }

  internal fun ticketSpacetimeStreamActive(): Boolean = streamActive

  private fun ticketSpacetimeBackgroundStreamAlreadyHealthy(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    if (
      !streamActive ||
      ticketSessionState != TICKET_SESSION_LIVE ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      !hardwareCaptureVerified ||
      lastRootH264BlankProbeResult == "secure_capture_blocked"
    ) {
      return false
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: return false
    return frameAgeMillis <= LIVE_FRAME_MAX_AGE_MILLIS
  }

  internal fun ticketSpacetimeDirectRecoveryPending(): Boolean =
    shouldRunTicketSpacetimeDesiredRecovery(SystemClock.elapsedRealtime())

  internal fun ticketSpacetimeStreamState(): String = when {
    streamActive && ticketSessionState == TICKET_SESSION_LIVE -> "streaming"
    streamActive && ticketSessionState == TICKET_SESSION_STARTING -> "starting"
    streamActive -> ticketSessionState
    else -> ticketSessionState
  }

  internal fun ticketSpacetimeCompactHealthJson(): String {
    val nowMillis = SystemClock.elapsedRealtime()
    val h264 = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    return buildJsonObject {
      put("streamActive", streamActive)
      put("sessionState", ticketSessionState)
      put("streamState", ticketSpacetimeStreamState())
      put("captureMode", activeCaptureMode)
      put("videoClients", videoClients.size)
      put("hardwareH264State", h264.state)
      put("hardwareH264Active", h264.active)
      put("hardwareH264Available", h264.available)
      put("hardwareH264HelperState", h264.captureHelperState)
      put("hardwareH264Visibility", h264.lastVisibilityCheckResult)
      put("lastStreamRecoveryResult", lastStreamRecoveryResult)
      put("streamWatchdogStage", streamWatchdogStage)
      put("lastStreamWatchdogAction", lastStreamWatchdogAction)
      put("desiredRecoveryStage", spacetimeDesiredRecoveryStage)
      put("lastDesiredRecoveryResult", lastSpacetimeDesiredRecoveryResult)
      put("controlCodeStatus", lastControlCodeRequestStatus)
    }.toString()
  }

  internal fun peekTicketSpacetimePhoneMessages(maxMessages: Int = Int.MAX_VALUE): List<String> {
    return ticketSpacetimePhoneOutbox.peek(maxMessages)
  }

  internal fun acknowledgeTicketSpacetimePhoneMessage(message: String) {
    ticketSpacetimePhoneOutbox.acknowledge(message)
  }

  private fun enqueueTicketSpacetimePhoneMessage(message: String) {
    ticketSpacetimePhoneOutbox.enqueue(message)
  }

  private fun ticketSpacetimeCriticalMessageKey(message: String): String? {
    val payload = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
    val type = payload.stringValue("type")
    val requestId = payload.stringValue("requestId").trim()
    return when (type) {
      "ticket_state_event" -> requestId.takeIf { it.isNotBlank() }
        ?.let { "$type:$it:${payload.stringValue("ticketState")}" }
      "control_code_progress",
      "control_code_result",
      "control_code_cleanup_complete",
      "rigassatiksme_qr_result" -> requestId.takeIf { it.isNotBlank() }?.let { "$type:$it" }
      "control_code_fast_state" -> type
      else -> null
    }
  }

  private fun TicketSessionResponse.toTicketSpacetimeCommandResult(reason: String): TicketSpacetimeCommandResult {
    return TicketSpacetimeCommandResult(
      ok = ok,
      reason = if (ok) state else reason.ifBlank { state }.ifBlank { message },
      streamState = ticketSpacetimeStreamState()
    )
  }

  private fun shouldRunTicketSpacetimeDesiredRecovery(nowMillis: Long): Boolean {
    if (streamActive) {
      return false
    }
    if (
      lastSpacetimeDesiredRecoveryAtMillis > 0L &&
      nowMillis - lastSpacetimeDesiredRecoveryAtMillis < SPACETIME_DESIRED_RECOVERY_COOLDOWN_MILLIS
    ) {
      return false
    }
    if (ticketSessionState == TICKET_SESSION_UNAVAILABLE || hardwareMarkedUnreliable()) {
      return true
    }
    if (ticketSessionState != TICKET_SESSION_NEEDS_ATTENTION) {
      return false
    }
    if (lastRootH264BlankProbeResult != "secure_capture_blocked") {
      return false
    }
    val blockedAgeMillis = ageMillis(lastRootH264BlankProbeAtMillis, nowMillis) ?: return true
    return blockedAgeMillis >= SPACETIME_DESIRED_RECOVERY_STALE_BLOCK_MILLIS
  }

  private suspend fun recoverTicketSpacetimeDesiredStream(reason: String): TicketSpacetimeCommandResult {
    val startedAtMillis = SystemClock.elapsedRealtime()
    lastSpacetimeDesiredRecoveryAtMillis = startedAtMillis
    spacetimeDesiredRecoveryStage = "cleanup"
    lastSpacetimeDesiredRecoveryAction = "cleanup_stale_hardware_capture"
    lastSpacetimeDesiredRecoveryResult = "started"
    lastSpacetimeDesiredRecoveryFailureReason = null
    lastSpacetimeDesiredRecoveryProbeResult = null
    recordTicketEvent(
      "spacetime_desired_recovery_started",
      "reason=$reason state=$ticketSessionState probe=$lastRootH264BlankProbeResult"
    )
    rootH264BlankProbeJob?.cancel()
    rootH264BlankProbeJob = null
    streamWatchdogJob?.cancel()
    streamWatchdogJob = null
    streamWatchdogStage = "idle"
    rootHardwareH264CaptureEngine.stop("spacetime_desired_recovery:$reason")
    rootHardwareH264CaptureEngine.cleanupStaleProcesses()

    val sourceSize = currentDisplaySize()
    spacetimeDesiredRecoveryStage = "probe"
    lastSpacetimeDesiredRecoveryAction = "root_h264_helper_probe"
    val probeOk = rootHardwareH264CaptureEngine.probe(sourceSize.first, sourceSize.second)
    val probeSnapshot = rootHardwareH264CaptureEngine.snapshot()
    val probeResult = probeSnapshot.lastVisibilityCheckResult
    lastSpacetimeDesiredRecoveryProbeResult = probeResult.ifBlank { probeSnapshot.captureHelperState }
    if (!probeOk || probeResult != "visible") {
      val failureReason = when {
        probeResult == "blocked" || probeSnapshot.captureHelperState == "capture_blocked" -> "secure_capture_blocked"
        !probeSnapshot.available -> "hardware_h264_unavailable:${probeSnapshot.captureHelperState}"
        else -> "hardware_h264_probe_unverified:${probeResult.ifBlank { probeSnapshot.captureHelperState }}"
      }
      spacetimeDesiredRecoveryStage = "failed"
      lastSpacetimeDesiredRecoveryAction = "root_h264_helper_probe"
      lastSpacetimeDesiredRecoveryResult = "failed"
      lastSpacetimeDesiredRecoveryFailureReason = failureReason
      lastStreamRecoveryResult = "failed"
      lastStreamRecoveryFailureReason = failureReason
      lastStreamRecoveryAtMillis = SystemClock.elapsedRealtime()
      streamActive = false
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      fallbackReason = failureReason
      if (failureReason == "secure_capture_blocked") {
        lastRootH264BlankProbeAtMillis = SystemClock.elapsedRealtime()
        lastRootH264BlankProbeResult = "secure_capture_blocked"
        rootH264BlankProbeFailures += 1L
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_blocked")
        lastMessage = "ViVi is protected from capture; stream was not restarted"
      } else {
        updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, "spacetime_desired_recovery_probe_failed")
        lastMessage = "Hardware H.264 ticket stream is unavailable; stream was not restarted"
      }
      recordTicketEvent(
        "spacetime_desired_recovery_failed",
        "reason=$reason failure=$failureReason probe_ok=$probeOk probe=$probeResult helper=${probeSnapshot.captureHelperState}"
      )
      broadcastStatus()
      return TicketSpacetimeCommandResult(ok = false, reason = failureReason, streamState = ticketSpacetimeStreamState())
    }

    hardwareReliabilityFailures = 0
    hardwareMarkedUnreliableAtMillis = 0L
    hardwareUnreliableReason = null
    fallbackReason = null
    lastRootH264BlankProbeAtMillis = SystemClock.elapsedRealtime()
    lastRootH264BlankProbeResult = "visible"
    lastRootH264VisibleProbePassedAtMillis = lastRootH264BlankProbeAtMillis
    rootH264BlankProbeRecoveries += 1L
    lastStreamRecoveryResult = "started"
    lastStreamRecoveryFailureReason = null
    lastStreamRecoveryAtMillis = lastRootH264BlankProbeAtMillis
    updateTicketSessionState(TICKET_SESSION_IDLE, "spacetime_desired_recovery_probe_visible")
    recordTicketEvent(
      "spacetime_desired_recovery_probe_visible",
      "reason=$reason helper=${probeSnapshot.captureHelperState} probe=$probeResult"
    )

    spacetimeDesiredRecoveryStage = "restart"
    lastSpacetimeDesiredRecoveryAction = "start_stream_after_probe"
    val response = startTicketSession()
    val result = response.toTicketSpacetimeCommandResult(
      if (response.ok) "spacetime_desired_recovery_succeeded" else response.state.ifBlank { reason }
    )
    if (result.ok) {
      spacetimeDesiredRecoveryStage = "started"
      lastSpacetimeDesiredRecoveryResult = "succeeded"
      lastSpacetimeDesiredRecoveryFailureReason = null
      recordTicketEvent("spacetime_desired_recovery_succeeded", "reason=$reason stream_state=${result.streamState}")
    } else {
      spacetimeDesiredRecoveryStage = "failed"
      lastSpacetimeDesiredRecoveryResult = "failed"
      lastSpacetimeDesiredRecoveryFailureReason = result.reason
      recordTicketEvent("spacetime_desired_recovery_failed", "reason=$reason failure=${result.reason}")
    }
    broadcastStatus()
    return result
  }

  private fun forceLatestTicketReselect(reason: String, commandId: String): TicketSpacetimeCommandResult {
    val cleanReason = reason.ifBlank { "admin_force_latest_ticket_reselect" }
    val cleanCommandId = commandId.ifBlank { "unknown" }
    if (controlSensitiveWindowActive()) {
      recordTicketEvent(
        "latest_ticket_reselect_blocked",
        "reason=control_code_active command=$cleanCommandId state=$ticketSessionState"
      )
      return TicketSpacetimeCommandResult(
        ok = false,
        reason = "control_code_active",
        streamState = ticketSpacetimeStreamState()
      )
    }
    markLatestTicketReselectStarted(cleanReason, cleanCommandId)
    recordTicketEvent("latest_ticket_reselect_command_received", "reason=$cleanReason command=$cleanCommandId")
    viviStateMemory.clear("root", "force_ticket_reselect:$cleanCommandId")
    recordTicketEvent(
      "latest_ticket_reselect_memory_cleared",
      "command=$cleanCommandId stale_memory_bypass=true"
    )
    recordTicketEvent("latest_ticket_reselect_requested", "reason=$cleanReason command=$cleanCommandId")
    scheduleLatestTicketReselectRecovery(cleanReason, cleanCommandId)
    if (streamActive) {
      requestKeyFrame("latest_ticket_reselect_requested")
    }
    return TicketSpacetimeCommandResult(
      ok = true,
      reason = "latest_ticket_reselect_scheduled",
      streamState = ticketSpacetimeStreamState()
    )
  }

  private fun markLatestTicketReselectStarted(reason: String, commandId: String) {
    synchronized(latestTicketReselectStateLock) {
      latestTicketReselectRecoveryJob?.cancel()
      latestTicketReselectRecoveryJob = null
      latestTicketReselectSettleJob?.cancel()
      latestTicketReselectSettleJob = null
      latestTicketReselectProofIdleStopJob?.cancel()
      latestTicketReselectProofIdleStopJob = null
      val nowMillis = SystemClock.elapsedRealtime()
      latestTicketReselectStatus = "pending"
      latestTicketReselectReason = reason
      latestTicketReselectCommandId = commandId
      latestTicketReselectPhase = "ticket_reselect_requested"
      latestTicketReselectStartedAtMillis = nowMillis
      latestTicketReselectTicketDetailAtMillis = 0L
      latestTicketReselectCompletedAtMillis = 0L
      latestTicketReselectFreshFrameAtMillis = 0L
      latestTicketReselectProofSource = ""
      latestTicketReselectProofHoldUntilMillis = 0L
      latestTicketReselectLastProofNudgeAtMillis = 0L
      latestTicketReselectGeneration += 1L
    }
    broadcastStatus()
  }

  private fun scheduleLatestTicketReselectRecovery(reason: String, commandId: String) {
    val recoveryJob = synchronized(latestTicketReselectStateLock) {
      latestTicketReselectRecoveryJob?.cancel()
      val generation = latestTicketReselectGeneration
      val job = serviceScope.launch(start = CoroutineStart.LAZY) {
        try {
          controlCodePhoneMutationLane.withOwnership {
            if (latestTicketReselectGenerationIsCurrent(generation, commandId)) {
              runLatestTicketReselectRecovery(reason, commandId, generation)
            }
          }
        } finally {
          val completingJob = coroutineContext[Job]
          synchronized(latestTicketReselectStateLock) {
            if (latestTicketReselectRecoveryJob === completingJob) {
              latestTicketReselectRecoveryJob = null
            }
          }
        }
      }
      latestTicketReselectRecoveryJob = job
      job
    }
    recoveryJob.start()
  }

  private suspend fun runLatestTicketReselectRecovery(reason: String, commandId: String, generation: Long) {
    val recoveryStartedAtMillis = SystemClock.elapsedRealtime()
    val recoveryReason = "latest_ticket_reselect:${commandId.takeLast(12)}"
    if (streamActive) {
      updateTicketSessionState(TICKET_SESSION_STARTING, "latest_ticket_reselect_recovery_started")
    }
    recordTicketEvent("latest_ticket_reselect_vivi_opening", "reason=$reason command=$commandId")
    recordViviHardReset("recovery:$reason")
    wakeRootExecutor.run("am force-stop ${TicketScreenConfig.VIVI_PACKAGE}", 1_500.milliseconds)
    delay(LATEST_TICKET_RESELECT_RELAUNCH_DELAY_MILLIS)
    launchViviForWake(recoveryReason)
    recordTicketEvent("latest_ticket_reselect_vivi_opened", "reason=$reason command=$commandId")
    val result = observeTicketDetailForWakeWithRoot(
      reason = recoveryReason,
      wakeStartedAtMillis = recoveryStartedAtMillis,
      budgetMillis = LATEST_TICKET_RESELECT_RECOVERY_BUDGET_MILLIS,
      maxRecoveryActions = LATEST_TICKET_RESELECT_MAX_RECOVERY_ACTIONS
    )
    markWakeReadyIfNeeded(recoveryStartedAtMillis, result)
    recordTicketEvent(
      "latest_ticket_reselect_recovery_result",
      "${result.state}:${result.step}:success=${result.success} reason=$reason"
    )
    val acceptedResult = synchronized(latestTicketReselectStateLock) {
      if (latestTicketReselectGeneration != generation || latestTicketReselectCommandId != commandId) {
        false
      } else {
        if (result.success) {
          recordTicketEvent(
            "latest_ticket_reselect_ticket_detail_observed",
            "state=${result.state.name} step=${result.step} command=$commandId"
          )
          markLatestTicketReselectTicketDetailObserved(result)
          beginLatestTicketReselectStreamProof(reason, commandId)
        } else {
          recordTicketEvent(
            "latest_ticket_reselect_failed",
            "state=${result.state.name} step=${result.step} command=$commandId"
          )
          latestTicketReselectPhase = "ticket_detail_failed"
        }
        true
      }
    }
    if (!acceptedResult) {
      return
    }
    if (result.success) {
      if (streamActive) {
        updateTicketSessionState(TICKET_SESSION_LIVE, "latest_ticket_reselect_ticket_detail_ready")
        cacheForegroundViolation(null)
        startForegroundGuard()
        restartActiveStreamEngine("latest_ticket_reselect")
        requestKeyFrame("latest_ticket_reselect_ticket_detail_ready")
      } else {
        startTicketSession()
      }
      scheduleLatestTicketReselectSettle(
        successReason = "latest_ticket_reselect_succeeded",
        failureReason = "latest_ticket_reselect_stream_unstable",
        generation = generation,
        commandId = commandId
      )
    } else {
      if (streamActive) {
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "latest_ticket_reselect_${result.state.name.lowercase()}")
      }
      scheduleLatestTicketReselectSettle(
        successReason = "latest_ticket_reselect_succeeded_after_recovery",
        failureReason = "latest_ticket_reselect_failed",
        generation = generation,
        commandId = commandId
      )
    }
    broadcastStatus()
  }

  private fun latestTicketReselectGenerationIsCurrent(generation: Long, commandId: String): Boolean {
    return synchronized(latestTicketReselectStateLock) {
      latestTicketReselectGeneration == generation && latestTicketReselectCommandId == commandId
    }
  }

  private fun markLatestTicketReselectFinished(
    reason: String,
    success: Boolean,
    generation: Long,
    commandId: String
  ) {
    if (!latestTicketReselectGenerationIsCurrent(generation, commandId)) {
      return
    }
    val nowMillis = SystemClock.elapsedRealtime()
    latestTicketReselectStatus = if (success) "succeeded" else "failed"
    latestTicketReselectReason = reason
    latestTicketReselectCompletedAtMillis = nowMillis
    if (!success) {
      latestTicketReselectPhase = if (latestTicketReselectTicketDetailAtMillis > 0L) "stream_proof_failed" else "failed"
      latestTicketReselectFreshFrameAtMillis = 0L
      latestTicketReselectProofHoldUntilMillis = 0L
    }
    markLatestTicketReselectFreshIfReady(nowMillis)
    recordTicketEvent(
      "latest_ticket_reselect_finished",
      "status=${latestTicketReselectStatus} reason=$reason command=$latestTicketReselectCommandId"
    )
    broadcastStatus()
    scheduleLatestTicketReselectProofIdleStop(
      reason = "latest_ticket_reselect_finished:$reason",
      generation = generation,
      commandId = commandId
    )
  }

  private fun scheduleLatestTicketReselectSettle(
    successReason: String,
    failureReason: String,
    generation: Long,
    commandId: String
  ) {
    val settleJob = synchronized(latestTicketReselectStateLock) {
      if (latestTicketReselectGeneration != generation || latestTicketReselectCommandId != commandId) {
        null
      } else {
        latestTicketReselectSettleJob?.cancel()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
          try {
            val deadlineMillis = SystemClock.elapsedRealtime() + LATEST_TICKET_RESELECT_SETTLE_TIMEOUT_MILLIS
            while (
              latestTicketReselectGenerationIsCurrent(generation, commandId) &&
              SystemClock.elapsedRealtime() < deadlineMillis
            ) {
              val completed = synchronized(latestTicketReselectStateLock) {
                if (latestTicketReselectGeneration == generation && latestTicketReselectCommandId == commandId) {
                  completeLatestTicketReselectIfFresh(successReason, generation, commandId)
                } else {
                  false
                }
              }
              if (completed) {
                return@launch
              }
              delay(CONTROL_CODE_RECOVERY_QUEUE_POLL_MILLIS)
            }
            synchronized(latestTicketReselectStateLock) {
              if (latestTicketReselectGeneration != generation || latestTicketReselectCommandId != commandId) {
                return@synchronized
              }
              if (completeLatestTicketReselectIfFresh(successReason, generation, commandId)) {
                return@synchronized
              }
              markLatestTicketReselectFinished(
                reason = failureReason,
                success = false,
                generation = generation,
                commandId = commandId
              )
            }
          } finally {
            val completingJob = coroutineContext[Job]
            synchronized(latestTicketReselectStateLock) {
              if (latestTicketReselectSettleJob === completingJob) {
                latestTicketReselectSettleJob = null
              }
            }
          }
        }
        latestTicketReselectSettleJob = job
        job
      }
    }
    settleJob?.start()
  }

  private fun completeLatestTicketReselectIfFresh(reason: String, generation: Long, commandId: String): Boolean {
    if (!latestTicketReselectGenerationIsCurrent(generation, commandId)) {
      return false
    }
    val nowMillis = SystemClock.elapsedRealtime()
    if (!latestTicketReselectRecent(nowMillis)) {
      return false
    }
    if (!noteLatestTicketReselectFreshStreamReady(nowMillis)) {
      nudgeLatestTicketReselectStreamProof(nowMillis)
      return false
    }
    latestTicketReselectStatus = "succeeded"
    latestTicketReselectReason = reason
    latestTicketReselectPhase = "ready"
    latestTicketReselectCompletedAtMillis = nowMillis
    latestTicketReselectProofHoldUntilMillis = 0L
    recordTicketEvent(reason, "fresh_frame_ready=true")
    broadcastStatus()
    scheduleLatestTicketReselectProofIdleStop(
      reason = "latest_ticket_reselect_succeeded",
      generation = generation,
      commandId = commandId
    )
    return true
  }

  private fun latestTicketReselectActive(nowMillis: Long): Boolean {
    val startedAtMillis = latestTicketReselectStartedAtMillis
    if (startedAtMillis <= 0L) {
      return false
    }
    val ageMillis = nowMillis - startedAtMillis
    return latestTicketReselectStatus == "pending" &&
      ageMillis in 0..LATEST_TICKET_RESELECT_ACTIVE_WINDOW_MILLIS
  }

  private fun latestTicketReselectRecent(nowMillis: Long): Boolean {
    val startedAtMillis = latestTicketReselectStartedAtMillis
    return startedAtMillis > 0L &&
      nowMillis - startedAtMillis in 0..LATEST_TICKET_RESELECT_ACTIVE_WINDOW_MILLIS
  }

  private fun markLatestTicketReselectFreshIfReady(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    if (latestTicketReselectFreshFrameAtMillis > 0L) {
      return true
    }
    if (!latestTicketReselectRecent(nowMillis) || latestTicketReselectStatus != "succeeded") {
      return false
    }
    return noteLatestTicketReselectFreshStreamReady(nowMillis)
  }

  private fun noteLatestTicketReselectFreshStreamReady(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    if (
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      !hardwareCaptureVerified ||
      ticketSessionState != TICKET_SESSION_LIVE
    ) {
      return false
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: return false
    if (frameAgeMillis > LIVE_FRAME_MAX_AGE_MILLIS) {
      return false
    }
    val current = viviStateMemory.current()
    val currentAgeMillis = ageMillis(current.observedAtMillis, nowMillis) ?: return false
    if (
      current.state != TicketViviRecoveryState.TICKET_DETAIL ||
      currentAgeMillis > CONTROL_CODE_RESELECT_FRESH_TICKET_MAX_AGE_MILLIS
    ) {
      return false
    }
    if (latestTicketReselectFreshFrameAtMillis == 0L) {
      latestTicketReselectFreshFrameAtMillis = nowMillis
      latestTicketReselectProofSource = if (videoClients.isEmpty()) "self_proof_root_hardware_h264" else "viewer_root_hardware_h264"
      latestTicketReselectPhase = "stream_proof_ready"
      recordTicketEvent(
        "latest_ticket_reselect_fresh_stream_ready",
        "frame_age_ms=$frameAgeMillis ticket_age_ms=$currentAgeMillis proof=${latestTicketReselectProofSource}"
      )
    }
    return true
  }

  private fun markLatestTicketReselectTicketDetailObserved(result: TicketAutopilotResult) {
    latestTicketReselectPhase = "ticket_detail_observed"
    latestTicketReselectTicketDetailAtMillis = SystemClock.elapsedRealtime()
    recordTicketEvent(
      "latest_ticket_reselect_ticket_detail_ready",
      "state=${result.state.name} step=${result.step} command=$latestTicketReselectCommandId"
    )
  }

  private fun beginLatestTicketReselectStreamProof(reason: String, commandId: String) {
    val nowMillis = SystemClock.elapsedRealtime()
    latestTicketReselectPhase = "stream_proof_pending"
    latestTicketReselectProofSource = ""
    latestTicketReselectProofHoldUntilMillis = nowMillis + LATEST_TICKET_RESELECT_PROOF_HOLD_MILLIS
    latestTicketReselectLastProofNudgeAtMillis = 0L
    recordTicketEvent(
      "latest_ticket_reselect_stream_proof_started",
      "reason=$reason command=$commandId clients=${videoClients.size} hold_ms=$LATEST_TICKET_RESELECT_PROOF_HOLD_MILLIS"
    )
  }

  private fun latestTicketReselectProofHoldActive(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    return latestTicketReselectStatus == "pending" &&
      latestTicketReselectProofHoldUntilMillis > nowMillis &&
      latestTicketReselectTicketDetailAtMillis > 0L
  }

  private fun streamCaptureNeededForLatestTicketReselectProof(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    return videoClients.isEmpty() && latestTicketReselectProofHoldActive(nowMillis)
  }

  private fun nudgeLatestTicketReselectStreamProof(nowMillis: Long) {
    if (!latestTicketReselectProofHoldActive(nowMillis)) {
      return
    }
    if (nowMillis - latestTicketReselectLastProofNudgeAtMillis < LATEST_TICKET_RESELECT_PROOF_NUDGE_MILLIS) {
      return
    }
    latestTicketReselectLastProofNudgeAtMillis = nowMillis
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      recordTicketEvent("latest_ticket_reselect_stream_proof_waiting", "stream_inactive")
      return
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis)
    if (
      hardwareCaptureVerified &&
      frameAgeMillis != null &&
      frameAgeMillis > STREAM_STALE_ENGINE_RESTART_MILLIS
    ) {
      restartActiveStreamEngine("latest_ticket_reselect_self_proof_stale")
      return
    }
    if (hardwareCaptureVerified) {
      ensureRootHardwareH264CaptureIfPossible()
      requestKeyFrame("latest_ticket_reselect_self_proof")
    }
  }

  private fun scheduleLatestTicketReselectProofIdleStop(
    reason: String,
    generation: Long,
    commandId: String
  ) {
    if (totalClientCount() > 0 || !streamActive) {
      return
    }
    val idleStopJob = synchronized(latestTicketReselectStateLock) {
      if (latestTicketReselectGeneration != generation || latestTicketReselectCommandId != commandId) {
        null
      } else {
        latestTicketReselectProofIdleStopJob?.cancel()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
          try {
            delay(LATEST_TICKET_RESELECT_PROOF_IDLE_STOP_GRACE_MILLIS)
            val runningJob = coroutineContext[Job]
            sessionMutex.withLock {
              val currentProofJob = synchronized(latestTicketReselectStateLock) {
                latestTicketReselectProofIdleStopJob === runningJob &&
                  latestTicketReselectGeneration == generation &&
                  latestTicketReselectCommandId == commandId
              }
              if (currentProofJob && totalClientCount() == 0 && streamActive) {
                noteClientDetachedLocked(reason)
              }
            }
          } finally {
            val completingJob = coroutineContext[Job]
            synchronized(latestTicketReselectStateLock) {
              if (latestTicketReselectProofIdleStopJob === completingJob) {
                latestTicketReselectProofIdleStopJob = null
              }
            }
          }
        }
        latestTicketReselectProofIdleStopJob = job
        job
      }
    }
    idleStopJob?.start()
  }

  private fun JsonObject.stringValue(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

  private fun JsonObject.booleanValue(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

  private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

  private fun JsonObject.jsonObjectValue(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()

  private suspend fun startTicketSession(
    prepareCaptureWithCurrentPhoneMutationOwnership: Boolean = false
  ): TicketSessionResponse {
    val response = withTimeoutOrNull(SESSION_START_TIMEOUT_MILLIS) {
      sessionMutex.withLock {
        startTicketSessionLocked(
          scheduleCaptureStart = !prepareCaptureWithCurrentPhoneMutationOwnership
        )
      }
    } ?: return run {
      cleanupInactiveClientsIfNeeded("session_start_timeout")
      recordTicketEvent("session_start_timeout", "timeout_ms=$SESSION_START_TIMEOUT_MILLIS state=$ticketSessionState clients=${totalClientCount()}")
      TicketSessionResponse(
        ok = false,
        state = "start_timeout",
        message = "Ticket session start timed out; stale clients were cleared and the next request can retry"
      )
    }

    if (
      !prepareCaptureWithCurrentPhoneMutationOwnership ||
      !response.ok ||
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      (hardwareCaptureVerified && hardwareFrameBroadcastAllowed)
    ) {
      return response
    }

    val prepared = prepareRootHardwareH264CaptureWithPhoneMutationOwnership(
      reason = "control_code_request_owned_session_start",
      suppressBlackout = false
    )
    return TicketSessionResponse(
      ok = prepared,
      state = ticketSessionState,
      message = lastMessage
    )
  }

  private suspend fun startTicketSessionLocked(scheduleCaptureStart: Boolean): TicketSessionResponse {
    tryReuseActiveHardwareStreamBeforePreflight()?.let { return it }
    beginStartupTrace("session_start")
    PhonePortraitLock.force(inputRootExecutor)
    if (!PhonePortraitLock.verify(inputRootExecutor)) {
      recordTicketEvent("phone_portrait_lock_unverified", "session_start")
      recordStartupTracePhase("portrait_lock_failed", "session_start", complete = true)
      return TicketSessionResponse(
        ok = false,
        state = "portrait_lock_failed",
        message = "Phone portrait lock could not be verified"
      )
    }
    if (!TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)) {
      recordStartupTracePhase("vivi_missing", "package=${TicketScreenConfig.VIVI_PACKAGE}", complete = true)
      return TicketSessionResponse(
        ok = false,
        state = "vivi_missing",
        message = "ViVi is not installed from a local Pixel app store yet"
      )
    }
    if (streamActive) {
      if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && !hardwareCaptureVerified) {
        recordTicketEvent(
          "session_start_already_preparing",
          "state=$ticketSessionState frame_sequence=$frameSequence reason=$ticketSessionStateReason"
        )
        lastSessionStopReason = null
        markViewerInput("session_start_already_preparing")
        lastMessage = "Preparing ViVi for secure H.264 capture"
        scheduleTicketBrightnessGuard("session_start_already_preparing")
        startForegroundGuard()
        broadcastStatus()
        recordStartupTracePhase("session_already_preparing", "state=$ticketSessionState frame_sequence=$frameSequence", once = true)
        return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
      }
      if (canReuseActiveHardwareStreamWithoutRootRevalidation("session_start_already_active")) {
        return reuseActiveHardwareStream(
          reason = "session_start_already_active",
          traceDetail = "fast=true frame_sequence=$frameSequence"
        )
      }
      if (!validateActiveTicketSessionBeforeReuse("session_start_already_active")) {
        recordTicketEvent(
          "session_start_already_active_revalidate",
          "state=$ticketSessionState frame_sequence=$frameSequence reason=$ticketSessionStateReason"
        )
        lastSessionStopReason = null
        markViewerInput("session_start_already_active_revalidate")
        lastMessage = "Verifying ViVi ticket page before reusing the active H.264 stream"
        scheduleTicketBrightnessGuard("session_start_already_active_revalidate")
        startForegroundGuard()
        ensureEncoderIfPossible()
        scheduleTicketRecovery("session_start_already_active_revalidate", TicketRecoveryMode.ACTIVE_SOFT)
        recordStartupTracePhase("active_stream_revalidation_started", "state=$ticketSessionState frame_sequence=$frameSequence", once = true)
        return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
      }
      return reuseActiveHardwareStream(
        reason = "session_start_already_active",
        traceDetail = "validated=true frame_sequence=$frameSequence"
      )
    }
    val sourceSize = currentDisplaySize()
    var hardwareCapture = rootHardwareH264CaptureEngine.snapshot()
    if (!hardwareCapture.available) {
      rootHardwareH264CaptureEngine.probe()
      hardwareCapture = rootHardwareH264CaptureEngine.snapshot()
    }
    hardwareCapture = refreshHardwareReliabilityIfProbePasses(sourceSize.first, sourceSize.second, hardwareCapture)
    val hardwareUnavailableReason = when {
      !hardwareCapture.available -> "hardware_h264_unavailable:${hardwareCapture.state}"
      hardwareMarkedUnreliable() -> hardwareUnreliableReason ?: "hardware_h264_unreliable"
      else -> null
    }
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_requested")
    recordStartupTracePhase("session_start_requested", "hardware_state=${hardwareCapture.state}", once = true)
    recordTicketEvent(
      "session_start_requested",
      "root_hardware_h264_available=${hardwareCapture.available} root_hardware_h264_state=${hardwareCapture.state} selected_mode=${if (hardwareUnavailableReason == null) CAPTURE_MODE_ROOT_HARDWARE_H264 else CAPTURE_MODE_IDLE}"
    )
    lastSessionStopReason = null
    if (hardwareUnavailableReason != null) {
      fallbackReason = hardwareUnavailableReason
      cancelInactivityTimer()
      disableSecureWindowCaptureBypass()
      disableNotificationLockdown("capture_unavailable")
      scheduleTicketBrightnessGuard("capture_unavailable")
      releaseBlackoutOverlaySuppression()
      lastMessage = "Hardware H.264 ticket stream is unavailable; stream was not started"
      activeCaptureMode = CAPTURE_MODE_IDLE
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, "hardware_h264_unavailable")
      recordTicketEvent("session_unavailable", fallbackReason.orEmpty())
      recordStartupTracePhase("hardware_h264_unavailable", fallbackReason.orEmpty(), complete = true)
      return TicketSessionResponse(
        ok = false,
        state = "hardware_h264_unavailable",
        message = lastMessage
      )
    }
    fallbackReason = null
    streamActive = true
    hardwareCaptureVerified = false
    hardwareFrameBroadcastAllowed = false
    activeCaptureMode = CAPTURE_MODE_ROOT_HARDWARE_H264
    val modeLabel = "root_hardware_h264"
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_${modeLabel}_prepare")
    markViewerInput("session_start_${modeLabel}_prepare")
    lastMessage = "Preparing ViVi for secure H.264 capture"
    recordTicketEvent("session_capture_mode_selected", "mode=$activeCaptureMode fallback=${fallbackReason.orEmpty()}")
    recordStartupTracePhase("capture_mode_selected", "mode=$activeCaptureMode", once = true)
    recordTicketEvent("session_started", "mode=$activeCaptureMode")
    startForegroundGuard()
    if (scheduleCaptureStart) {
      scheduleRootHardwareH264CaptureStart("session_start_root_hardware_h264_capture", suppressBlackout = false)
    } else {
      recordTicketEvent("root_hardware_h264_prepare_owned", "session_start_root_hardware_h264_capture")
    }
    scheduleDeferredSessionStartMaintenance("session_start")
    broadcastStatus()
    return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
  }

  private fun tryReuseActiveHardwareStreamBeforePreflight(): TicketSessionResponse? {
    if (!canReuseActiveHardwareStreamWithoutRootRevalidation("session_start_active_preflight")) {
      return null
    }
    return reuseActiveHardwareStream(
      reason = "session_start_active_preflight",
      traceDetail = "preflight_fast=true frame_sequence=$frameSequence"
    )
  }

  private fun reuseActiveHardwareStream(reason: String, traceDetail: String): TicketSessionResponse {
    updateTicketSessionState(TICKET_SESSION_LIVE, reason)
    lastSessionStopReason = null
    markViewerInput(reason)
    lastMessage = activeCaptureModeMessage()
    scheduleTicketBrightnessGuard(reason)
    startForegroundGuard()
    ensureEncoderIfPossible()
    broadcastStatus()
    recordStartupTracePhase("active_stream_reused", traceDetail, once = true, complete = true)
    return TicketSessionResponse(ok = true, state = "active", message = lastMessage)
  }

  private fun canReuseActiveHardwareStreamWithoutRootRevalidation(reason: String): Boolean {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 || !hardwareCaptureVerified) return false
    val now = SystemClock.elapsedRealtime()
    val frameAge = ageMillis(lastFrameSentAtMillis, now) ?: return false
    val proof = viviStateMemory.recentTicketDetailWithin(ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS)
      ?: return false
    val ready = frameAge <= LIVE_FRAME_MAX_AGE_MILLIS
    if (ready) recordTicketEvent(
      "session_start_active_reuse_fast",
      "reason=$reason frame_age_ms=$frameAge proof_age_ms=${ageMillis(proof.observedAtMillis, now) ?: 0L}"
    )
    return ready
  }

  private suspend fun validateActiveTicketSessionBeforeReuse(reason: String): Boolean {
    val fast = observeFastViviState("active_session:$reason")
    if (fast?.state == TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent("session_start_active_revalidated", "fast:${fast.state.name} reason=$reason")
      return true
    }
    val result = fast ?: observeRootViviState("active_session:$reason")
    if (result.state == TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent("session_start_active_revalidated", "root:${result.state.name} reason=$reason")
      return true
    }
    recordTicketEvent(
      "session_start_already_active_revalidate",
      "reason=$reason state=${result.state.name} error=${result.error.takeLast(160)}"
    )
    return false
  }

  private fun scheduleDeferredSessionStartMaintenance(reason: String) {
    serviceScope.launch {
      val startedAtMillis = SystemClock.elapsedRealtime()
      while (
        streamActive &&
        activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
        frameSequence == 0L &&
        SystemClock.elapsedRealtime() - startedAtMillis < STARTUP_MAINTENANCE_DEFER_MILLIS
      ) {
        delay(STARTUP_MAINTENANCE_POLL_MILLIS)
      }
      if (!streamActive) {
        return@launch
      }
      val waitedMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
      recordTicketEvent("session_start_maintenance", "reason=$reason waited_ms=$waitedMillis frame_sequence=$frameSequence")
      enableNotificationLockdown(reason)
      enableSecureWindowCaptureBypass()
      scheduleTicketBrightnessGuard(reason)
      rememberTicketBrightnessState()
      suppressBlackoutOverlayForRemote()
      broadcastStatus()
    }
  }

  private suspend fun refreshHardwareReliabilityIfProbePasses(
    sourceWidth: Int,
    sourceHeight: Int,
    current: TicketHardwareH264Health
  ): TicketHardwareH264Health {
    if (!hardwareMarkedUnreliable()) {
      return current
    }
    val previousReason = hardwareUnreliableReason ?: "hardware_h264_unreliable"
    val probeOk = rootHardwareH264CaptureEngine.probe(sourceWidth, sourceHeight)
    val refreshed = rootHardwareH264CaptureEngine.snapshot()
    if (!probeOk || !refreshed.available || !refreshed.captureHelperAvailable || refreshed.captureHelperState != "ready") {
      recordTicketEvent(
        "hardware_reliability_probe_still_blocked",
        "previous_reason=$previousReason probe_ok=$probeOk state=${refreshed.state} helper=${refreshed.captureHelperState}"
      )
      return refreshed
    }
    hardwareReliabilityFailures = 0
    hardwareMarkedUnreliableAtMillis = 0L
    hardwareUnreliableReason = null
    recordTicketEvent(
      "hardware_reliability_probe_recovered",
      "previous_reason=$previousReason state=${refreshed.state} helper=${refreshed.captureHelperState}"
    )
    return refreshed
  }

  private fun hardwareMarkedUnreliable(): Boolean {
    return hardwareMarkedUnreliableAtMillis > 0L ||
      hardwareReliabilityFailures >= HARDWARE_RELIABILITY_FAILURE_THRESHOLD
  }

  private fun activeCaptureModeMessage(): String {
    return when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_HARDWARE_H264 -> "Ticket session is active through hardware H.264 capture"
      else -> lastMessage
    }
  }

  private suspend fun handleBrowserStopRequest(body: String): TicketSessionResponse {
    val explicit = body.contains(""""explicit":true""") ||
      body.contains("explicit=true") ||
      body.contains(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP)
    return if (explicit) {
      var activeClients = 0
      val stopped = stopTicketSessionIfAllowed(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP) {
        activeClients = totalClientCount()
        activeClients == 0
      }
      if (stopped == null) {
        recordTicketEvent("session_stop_ignored_active_clients", "http_browser_stop clients=$activeClients")
        broadcastStatus()
        TicketSessionResponse(ok = true, state = ticketSessionState, message = lastMessage)
      } else {
        stopped
      }
    } else {
      noteClientDetached("http_stop_without_explicit")
    }
  }

  private suspend fun recoverTicketSession(body: String): TicketSessionResponse {
    val reason = recoverTicketSessionReason(body)
    recordTicketEvent("session_recover_requested", reason)
    if (streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      val nowMillis = SystemClock.elapsedRealtime()
      lastSessionStopReason = null
      markViewerInput("session_recover_$reason")
      startForegroundGuard()
      scheduleTicketBrightnessGuard("session_recover:$reason")
      if (activeHardwareStreamFreshForRecovery(nowMillis)) {
        lastMessage = "Ticket stream is already live"
        updateTicketSessionState(TICKET_SESSION_LIVE, "session_recover_kept_active_$reason")
        lastStreamWatchdogAction = "keep_active"
        lastStreamWatchdogReason = "remote_$reason"
        lastStreamRecoveryResult = "succeeded"
        lastStreamRecoveryFailureReason = null
        lastStreamRecoveryAtMillis = nowMillis
        recordTicketEvent(
          "stream_recovery_kept_active",
          "reason=remote_$reason frame_age_ms=${ageMillis(lastFrameSentAtMillis, nowMillis) ?: -1L} clients=${videoClients.size}"
        )
        broadcastStatus()
        return TicketSessionResponse(ok = true, state = TICKET_SESSION_LIVE, message = lastMessage)
      }
      if (activeHardwareStreamStartingForRecovery(nowMillis)) {
        lastMessage = "Ticket stream is already starting"
        updateTicketSessionState(TICKET_SESSION_STARTING, "session_recover_waiting_first_frame_$reason")
        lastStreamWatchdogAction = "wait_first_frame"
        lastStreamWatchdogReason = "remote_$reason"
        lastStreamRecoveryResult = "started"
        lastStreamRecoveryFailureReason = null
        lastStreamRecoveryAtMillis = nowMillis
        recordTicketEvent(
          "stream_recovery_waiting_active",
          "reason=remote_$reason encoder_start_age_ms=${ageMillis(lastEncoderStartAtMillis, nowMillis) ?: -1L} clients=${videoClients.size}"
        )
        rootHardwareH264CaptureEngine.requestKeyFrame("session_recover_waiting_first_frame:$reason")
        ensureRootHardwareH264CaptureIfPossible()
        broadcastStatus()
        return TicketSessionResponse(ok = true, state = TICKET_SESSION_STARTING, message = lastMessage)
      }
      lastMessage = "Recovering the active H.264 ticket stream"
      updateTicketSessionState(TICKET_SESSION_STARTING, "session_recover_$reason")
      restartActiveStreamEngine("remote_$reason")
      broadcastStatus()
      return TicketSessionResponse(ok = true, state = "recovering", message = lastMessage)
    }
    return startTicketSession()
  }

  private fun activeHardwareStreamFreshForRecovery(nowMillis: Long): Boolean {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 || !hardwareCaptureVerified) {
      return false
    }
    if (ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION || lastRootH264BlankProbeResult == "secure_capture_blocked") {
      return false
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: return false
    return frameAgeMillis <= LIVE_FRAME_MAX_AGE_MILLIS
  }

  private fun activeHardwareStreamStartingForRecovery(nowMillis: Long): Boolean {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      return false
    }
    if (ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION || lastRootH264BlankProbeResult == "secure_capture_blocked") {
      return false
    }
    val health = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    if (!health.active && health.state != "starting" && health.state != "restarting") {
      return false
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis)
    if (frameAgeMillis != null && frameAgeMillis <= LIVE_FRAME_MAX_AGE_MILLIS) {
      return true
    }
    val encoderStartAgeMillis = ageMillis(lastEncoderStartAtMillis, nowMillis)
    return encoderStartAgeMillis == null || encoderStartAgeMillis < STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS
  }

  private fun recoverTicketSessionReason(body: String): String {
    val parsed = runCatching {
      json.parseToJsonElement(body).jsonObject["reason"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    val reason = (parsed ?: body).trim()
      .replace(Regex("[^A-Za-z0-9_.:-]+"), "_")
      .trim('_')
      .take(96)
    return reason.ifBlank { "remote_recover_stream" }
  }

  private suspend fun noteClientDetached(reason: String): TicketSessionResponse {
    return sessionMutex.withLock {
      noteClientDetachedLocked(reason)
    }
  }

  private suspend fun noteClientDetachedLocked(reason: String): TicketSessionResponse {
    recordTicketEvent("client_detached", reason)
    if (ticketSessionOpen() || ticketSessionState in setOf(TICKET_SESSION_STARTING, TICKET_SESSION_LIVE, TICKET_SESSION_NEEDS_ATTENTION)) {
      updateTicketSessionState(TICKET_SESSION_CLIENT_DISCONNECTED, reason)
      lastMessage = "Browser disconnected; ticket session is waiting to reconnect"
      streamActive = false
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      rootH264BlankProbeJob?.cancel()
      rootH264BlankProbeJob = null
      streamWatchdogJob?.cancel()
      streamWatchdogJob = null
      streamWatchdogStage = "idle"
      resetFrameEpoch("client_detached_$reason", active = false)
      cancelInactivityTimer()
      cancelForegroundGuard()
      rootHardwareH264CaptureEngine.stop(reason)
      rootHardwareH264CaptureEngine.cleanupStaleProcesses()
      disableSecureWindowCaptureBypass()
      disableNotificationLockdown(reason)
      scheduleTicketBrightnessGuard("client_detached:$reason")
      releaseTicketScreenAwake()
      releaseBlackoutOverlaySuppression()
      hideBlackoutOverlay()
      broadcastStatus()
      if (ticketServiceEnabled()) {
        recordTicketEvent("root_capture_ready_waiting", "client_detached_$reason")
      }
      return TicketSessionResponse(ok = true, state = "client_disconnected", message = lastMessage)
    }
    return TicketSessionResponse(ok = true, state = "inactive", message = lastMessage)
  }

  private suspend fun stopTicketSessionIfAllowed(
    reason: String,
    shouldStopLocked: () -> Boolean
  ): TicketSessionResponse? {
    val response = sessionMutex.withLock {
      if (shouldStopLocked()) stopTicketSessionLocked(reason) else null
    } ?: return null
    return completeTicketSessionStop(reason, response)
  }

  private suspend fun stopTicketSessionIfStillInactive(
    expectedViewerInputGeneration: Long,
    expectedLastInputAtMillis: Long
  ): Boolean {
    val reason = "viewer_inactivity_timeout"
    val response = sessionMutex.withLock {
      val authorized = synchronized(inactivityStateLock) {
        val nowMillis = SystemClock.elapsedRealtime()
        val stillInactive = viewerInputGeneration == expectedViewerInputGeneration &&
          lastViewerInputAtMillis == expectedLastInputAtMillis &&
          ticketSessionOpen() &&
          TicketInactivityPolicy.timedOut(lastViewerInputAtMillis, nowMillis)
        if (stillInactive) {
          streamActive = false
        }
        stillInactive
      }
      if (!authorized) {
        null
      } else {
        stopTicketSessionLocked(reason)
      }
    } ?: return false
    completeTicketSessionStop(reason, response)
    return true
  }

  private suspend fun stopTicketSessionLocked(reason: String): TicketSessionResponse {
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    startupDisconnectGraceUntilMillis = 0L
    streamActive = false
    hardwareCaptureVerified = false
    hardwareFrameBroadcastAllowed = false
    activeCaptureMode = CAPTURE_MODE_IDLE
    rootH264BlankProbeJob?.cancel()
    rootH264BlankProbeJob = null
    streamWatchdogJob?.cancel()
    streamWatchdogJob = null
    streamWatchdogStage = "idle"
    updateTicketSessionState(TICKET_SESSION_STOPPED, "session_stop_$reason")
    resetFrameEpoch("session_stop_$reason", active = false)
    cancelInactivityTimer()
    cancelForegroundGuard()
    rootHardwareH264CaptureEngine.stop(reason)
    rootHardwareH264CaptureEngine.cleanupStaleProcesses()
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
    closeAllClients("session_stop_$reason")
    return TicketSessionResponse(ok = true, state = "stopped", message = lastMessage)
  }

  private suspend fun completeTicketSessionStop(
    reason: String,
    response: TicketSessionResponse
  ): TicketSessionResponse {
    if (ticketServiceEnabled()) recordTicketEvent("root_hardware_h264_ready_waiting", "session_stop_$reason")
    if (TicketSessionStopPolicy.shouldResetViviToTicket(reason)) {
      val stoppedState = observeRootViviState("session_stop_$reason")
      if (stoppedState.state == TicketViviRecoveryState.TICKET_DETAIL) {
        recordTicketEvent("ticket_recovery_skipped_ticket_detail", reason)
        broadcastStatus()
      } else {
        recordTicketEvent("session_stop_root_not_ticket_detail", stoppedState.state.name)
      }
    }
    return response
  }

  private fun ticketSessionOpen(): Boolean {
    return streamActive
  }

  private fun chatgptTicketPriorityActive(): Boolean {
    return streamActive ||
      totalClientCount() > 0 ||
      protectedControlClients.isNotEmpty() ||
      controlCodeRequestActive() ||
      ticketSessionState in setOf(
        TICKET_SESSION_STARTING,
        TICKET_SESSION_CONTROL_TRANSITION,
        TICKET_SESSION_CONTROL_ACTIVE,
        TICKET_SESSION_CONTROL_EXIT,
        TICKET_SESSION_SOFT_RECOVERY,
        TICKET_SESSION_NEEDS_ATTENTION
      )
  }

  private fun updateTicketSessionState(next: String, reason: String) {
    val now = SystemClock.elapsedRealtime()
    val previous = ticketSessionState
    if (previous != next) {
      val previousDuration = (now - ticketSessionStateChangedAtMillis).coerceAtLeast(0L)
      if (isBudgetedTicketState(previous) && previousDuration > TICKET_STATE_BUDGET_MILLIS) {
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

  private fun recordViviHardReset(reason: String) = recordTicketEvent("vivi_hard_reset", reason)

  private fun extendStartupDisconnectGrace() {
    val untilMillis = SystemClock.elapsedRealtime() + STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS
    if (untilMillis > startupDisconnectGraceUntilMillis) {
      startupDisconnectGraceUntilMillis = untilMillis
    }
  }

  private fun clearStartupDisconnectGrace() {
    startupDisconnectGraceUntilMillis = 0L
  }

  private fun scheduleClientDisconnectGraceLocked() {
    val startupGraceMillis = (startupDisconnectGraceUntilMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    val delayMillis = startupGraceMillis.coerceAtLeast(CLIENT_DISCONNECT_IDLE_GRACE_MILLIS)
    clientDisconnectStopJob?.cancel()
    val disconnectJob = serviceScope.launch(start = CoroutineStart.LAZY) {
      if (delayMillis > 0L) {
        delay(delayMillis)
      }
      val runningJob = coroutineContext[Job]
      sessionMutex.withLock {
        if (clientDisconnectStopJob !== runningJob) {
          return@withLock
        }
        try {
          if (totalClientCount() == 0 && ticketSessionOpen()) {
            noteClientDetachedLocked("browser_left_ticket_screen")
          }
        } finally {
          if (clientDisconnectStopJob === runningJob) {
            clientDisconnectStopJob = null
          }
        }
      }
    }
    clientDisconnectStopJob = disconnectJob
    disconnectJob.start()
  }

  private fun markViewerInput(reason: String) {
    val sessionWasOpen = synchronized(inactivityStateLock) {
      lastViewerInputAtMillis = SystemClock.elapsedRealtime()
      viewerInputGeneration += 1L
      ticketSessionOpen()
    }
    if (sessionWasOpen) {
      holdTicketScreenAwake("viewer_input_$reason")
      ensureInactivityTimer()
      broadcastInactivityStatus()
    }
  }

  private fun cancelInactivityTimer() {
    synchronized(inactivityStateLock) {
      inactivityJob?.cancel()
      inactivityJob = null
    }
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
        val violation = controlCodePhoneMutationLane.withOwnership {
          val currentViolation = foregroundViolationReason()
          cacheForegroundViolation(currentViolation)
          if (currentViolation != null) {
            handleForegroundViolation(currentViolation)
          } else if (controlSensitiveWindowActive()) {
            resetForegroundViolationConfirmation()
            refreshControlCodeModeAfterRemoteTap()
          } else if (isBudgetedTicketState(ticketSessionState)) {
            resetForegroundViolationConfirmation()
          } else {
            resetForegroundViolationConfirmation()
            enforceViviTicketPageIfNeeded("foreground_guard")
          }
          currentViolation
        }
        delay(foregroundGuardDelayMillis(violation))
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
  }

  private fun holdTicketScreenAwake(reason: String) {
    val manager = getSystemService(PowerManager::class.java) ?: return
    val touchBrightnessOwner = touchBrightnessOwnsTicketBrightness()
    val existingLock = ticketScreenWakeLock
    val lock = if (existingLock != null && ticketScreenWakeLockUsesTouchBrightnessOwner == touchBrightnessOwner) {
      existingLock
    } else {
      existingLock?.let { oldLock ->
        runCatching {
          if (oldLock.isHeld) {
            oldLock.release()
          }
        }.onFailure { error -> recordTicketEvent("ticket_screen_wake_recreate_release_failed", safeErrorDetail(error)) }
      }
      val flags = if (touchBrightnessOwner) {
        PowerManager.PARTIAL_WAKE_LOCK
      } else {
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
      }
      manager.newWakeLock(flags, "$packageName:TicketStream").apply {
        setReferenceCounted(false)
        ticketScreenWakeLock = this
        ticketScreenWakeLockUsesTouchBrightnessOwner = touchBrightnessOwner
      }
    }
    runCatching { lock.acquire(TICKET_SCREEN_WAKE_HOLD_MILLIS) }
      .onFailure { error -> recordTicketEvent("ticket_screen_wake_hold_failed", "reason=$reason error=${safeErrorDetail(error)}") }
  }

  private fun releaseTicketScreenAwake() {
    val lock = ticketScreenWakeLock ?: return
    ticketScreenWakeLock = null
    ticketScreenWakeLockUsesTouchBrightnessOwner = null
    runCatching {
      if (lock.isHeld) {
        lock.release()
      }
    }.onFailure { error -> recordTicketEvent("ticket_screen_wake_release_failed", safeErrorDetail(error)) }
  }

  private fun ticketScreenInteractive(): Boolean {
    return getSystemService(PowerManager::class.java)?.isInteractive == true
  }

  private fun beginStartupTrace(reason: String) {
    synchronized(startupTracePhaseLock) {
      startupTraceOncePhases.clear()
    }
    recordTicketEvent("startup_phase_phone_session_start_received", reason)
  }

  private fun recordStartupTracePhase(
    name: String,
    detail: String = "",
    once: Boolean = false,
    complete: Boolean = false
  ) {
    val cleanName = name.take(80)
    if (once) {
      val first = synchronized(startupTracePhaseLock) {
        startupTraceOncePhases.add(cleanName)
      }
      if (!first) return
    }
    recordTicketEvent("startup_phase_$cleanName", if (complete) "$detail complete=true" else detail)
  }

  private fun beginTicketWake(reason: String): Long {
    return SystemClock.elapsedRealtime().also {
      lastWakeStartedAtMillis = it
      lastWakeSucceeded = null
      recordTicketEvent("wake_started", reason)
    }
  }

  private fun recordTicketWakePhase(
    phase: String,
    startedAtMillis: Long,
    nowMillis: Long = SystemClock.elapsedRealtime()
  ) {
    if (startedAtMillis == lastWakeStartedAtMillis) {
      recordTicketEvent("wake_phase", "phase=$phase elapsed_ms=${(nowMillis - startedAtMillis).coerceAtLeast(0L)}")
    }
  }

  private fun finishTicketWake(startedAtMillis: Long, succeeded: Boolean, reason: String) {
    if (startedAtMillis != lastWakeStartedAtMillis) return
    lastWakeSucceeded = succeeded
    recordTicketEvent(
      "wake_finished",
      "success=$succeeded reason=$reason total_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)}"
    )
  }

  private suspend fun wakeTicketScreenForSessionStart(reason: String, startedAtMillis: Long): Boolean {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:wake_start:$reason")
    holdTicketScreenAwake(reason)
    val nowMillis = SystemClock.elapsedRealtime()
    val screenAlreadyInteractive = ticketScreenInteractive()
    val shouldSendWakeCommand = !screenAlreadyInteractive
    if (shouldSendWakeCommand) {
      lastTicketScreenWakeAtMillis = nowMillis
      val result = runFastNonTouchWakeScript(
        """
        input keyevent KEYCODE_WAKEUP
        """.trimIndent(),
        "wake_session_start",
        TICKET_WAKE_COMMAND_TIMEOUT_MILLIS.milliseconds
      )
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:wake_command_complete:$reason")
      recordTicketEvent("screen_wake", "$reason ok=${result.ok} duration_ms=${result.durationMs}")
    } else {
      recordTicketEvent("screen_wake_skipped_interactive", reason)
    }
    recordTicketWakePhase("wake_command", startedAtMillis)
    val interactive = waitForTicketScreenInteractiveForWake()
    if (interactive) {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:wake_interactive:$reason")
      recordTicketWakePhase("screen_interactive", startedAtMillis)
    } else {
      recordTicketEvent("wake_screen_interactive_timeout", "reason=$reason timeout_ms=$TICKET_WAKE_INTERACTIVE_TIMEOUT_MILLIS")
    }
    return interactive
  }

  private suspend fun waitForTicketScreenInteractiveForWake(): Boolean {
    val deadlineMillis = SystemClock.elapsedRealtime() + TICKET_WAKE_INTERACTIVE_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() <= deadlineMillis) {
      if (ticketScreenInteractive()) {
        return true
      }
      delay(TICKET_WAKE_FAST_POLL_MILLIS)
    }
    return ticketScreenInteractive()
  }

  private fun requestTicketScreenWake(reason: String) {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:wake_request:$reason")
    holdTicketScreenAwake(reason)
    val now = SystemClock.elapsedRealtime()
    if (now - lastTicketScreenWakeAtMillis < TICKET_SCREEN_WAKE_REQUEST_COOLDOWN_MILLIS) {
      return
    }
    lastTicketScreenWakeAtMillis = now
    serviceScope.launch {
      controlCodePhoneMutationLane.withOwnership {
        val result = runFastNonTouchScript(
          """
          input keyevent KEYCODE_WAKEUP
          wm dismiss-keyguard >/dev/null 2>&1 || true
          cmd statusbar collapse >/dev/null 2>&1 || true
          """.trimIndent(),
          "wake_request",
          TICKET_WAKE_COMMAND_TIMEOUT_MILLIS.milliseconds
        )
        PhoneAutomationServiceBridge.markNonTouchInput("ticket:wake_request_complete:$reason")
        recordTicketEvent("screen_wake", "$reason ok=${result.ok} duration_ms=${result.durationMs}")
      }
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
    recordTicketEvent("ticket_foreground_check_failed", safeRootFailure(result))
    return null
  }

  private suspend fun enforceViviTicketPageIfNeeded(reason: String) {
    if (controlSensitiveWindowActive()) {
      recordTicketEvent("active_guard_deferred", "$reason control_sensitive=true")
      return
    }
    val now = SystemClock.elapsedRealtime()
    if (ticketWakeInProgress(now)) {
      recordTicketEvent("active_guard_deferred", "$reason wake_in_progress=true")
      return
    }
    if (now - lastViviPageEnforceAtMillis < viviPageEnforceIntervalMillis(now)) {
      return
    }
    lastViviPageEnforceAtMillis = now
    val result = observeFastViviState("active_guard:$reason")
    if (result.state == TicketViviRecoveryState.TICKET_DETAIL && recentForegroundGuardTicketDetailStillFresh(now)) {
      resetForegroundViolationConfirmation()
      if (shouldLogForegroundGuardRecentTicketDetailSkip(now)) {
        recordTicketEvent("active_guard_recent_ticket_detail", "reason=$reason")
      }
      return
    }
    if (controlSensitiveWindowActive()) {
      recordTicketEvent("active_guard_deferred_after_observe", "$reason state=${result.state.name} control_sensitive=true")
      return
    }
    if (result.state == TicketViviRecoveryState.TICKET_DETAIL) {
      resetForegroundViolationConfirmation()
      if (streamActive && ticketSessionState in setOf(TICKET_SESSION_SOFT_RECOVERY, TICKET_SESSION_NEEDS_ATTENTION)) {
        recordTicketEvent("active_guard_live", reason)
        updateTicketSessionState(TICKET_SESSION_LIVE, "active_guard_ticket_detail_$reason")
        lastMessage = "Ticket session is active through hardware H.264 capture"
        broadcastStatus()
      }
      return
    }
    if (
      result.state == TicketViviRecoveryState.CONTROL_CODE_RESULT ||
      result.state == TicketViviRecoveryState.CONTROL_CODE_POPUP
    ) {
      rememberControlCodeSurface(result.state)
      recordTicketEvent("active_guard_return_raw", "state=${result.state.name} reason=$reason")
      val phases = mutableMapOf<String, Long>()
      val returned = returnControlCodeSurfaceToRawTicket(
        generatedHierarchy = result.hierarchy.orEmpty(),
        reason = "active_guard_return_raw:$reason",
        phases = phases,
        requestStartedAtMillis = now
      )
      if (returned) {
        resetForegroundViolationConfirmation()
        return
      }
      recordTicketEvent("active_guard_return_raw_failed", result.state.name)
    }
    if (attemptActiveGuardRecoveryAction(result.state, result.hierarchy, reason)) {
      resetForegroundViolationConfirmation()
      return
    }
    recordTicketEvent("active_guard_failed", "root:${result.state}")
    if (
      result.state == TicketViviRecoveryState.UNKNOWN_VIVI ||
      result.state == TicketViviRecoveryState.BLANK
    ) {
      recordTicketEvent("active_guard_inconclusive", result.error.ifBlank { result.state.name })
      return
    }
    if (!controlSensitiveWindowActive() && streamActive) {
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "active_guard_${result.state.name.lowercase()}")
      broadcastStatus()
    }
  }

  private suspend fun attemptActiveGuardRecoveryAction(
    state: TicketViviRecoveryState,
    hierarchy: String?,
    reason: String
  ): Boolean {
    if (hierarchy.isNullOrBlank()) {
      return false
    }
    if (state == TicketViviRecoveryState.LOGIN_REQUIRED) {
      return loginViviIfNeeded(hierarchy, "active_guard:$reason")
    }
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(hierarchy)
    if (action == null) {
      if (state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD) {
        recordTicketEvent(
          "ticket_card_selection_failed",
          TicketViviPageEnforcer.ticketCardSelectionSummaryForHierarchy(hierarchy)
        )
      }
      return false
    }
    val input = if (action.x >= 0 && action.y >= 0) {
      runFastNonTouchInput("input tap ${action.x} ${action.y}", "active_guard_recovery_action:${action.reason}")
    } else {
      runFastNonTouchInput("input keyevent KEYCODE_BACK", "active_guard_recovery_action:${action.reason}")
    }
    recordTicketEvent(
      "active_guard_recovery_action",
      "state=${state.name} action=${action.reason} ok=${input.ok} duration_ms=${input.durationMs} reason=$reason"
    )
    if (input.ok) {
      scheduleSessionRetryAfterActiveGuardRecovery(action.reason, reason)
    }
    return input.ok
  }

  private fun scheduleSessionRetryAfterActiveGuardRecovery(actionReason: String, guardReason: String) {
    val now = SystemClock.elapsedRealtime()
    if (streamActive || totalClientCount() == 0) {
      return
    }
    if (ticketSessionState != TICKET_SESSION_NEEDS_ATTENTION) {
      return
    }
    if (now - lastActiveGuardRecoverySessionRetryAtMillis < ACTIVE_GUARD_RECOVERY_SESSION_RETRY_COOLDOWN_MILLIS) {
      return
    }
    lastActiveGuardRecoverySessionRetryAtMillis = now
    recordTicketEvent(
      "active_guard_recovery_session_retry_scheduled",
      "action=$actionReason reason=$guardReason clients=${totalClientCount()}"
    )
    serviceScope.launch {
      delay(ACTIVE_GUARD_RECOVERY_SESSION_RETRY_DELAY_MILLIS)
      retrySessionAfterActiveGuardRecovery(actionReason, guardReason)
    }
  }

  private suspend fun retrySessionAfterActiveGuardRecovery(actionReason: String, guardReason: String) {
    if (streamActive || totalClientCount() == 0 || controlSensitiveWindowActive()) {
      return
    }
    if (ticketSessionState != TICKET_SESSION_NEEDS_ATTENTION) {
      return
    }
    val fast = observeFastViviState("active_guard_recovery_retry:$guardReason")
    val observed = fast ?: observeRootViviState(
      reason = "active_guard_recovery_retry:$guardReason",
      timeoutMillis = TICKET_ROOT_HIERARCHY_DUMP_TIMEOUT_MILLIS
    )
    if (observed.state != TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent(
        "active_guard_recovery_session_retry_skipped",
        "action=$actionReason reason=$guardReason state=${observed.state.name}"
      )
      return
    }
    recordTicketEvent(
      "active_guard_recovery_session_retry",
      "action=$actionReason reason=$guardReason clients=${totalClientCount()}"
    )
    startTicketSession()
  }

  private suspend fun loginViviIfNeeded(hierarchy: String, reason: String): Boolean {
    val cleanReason = sanitizeViviLoginReason(reason)
    val surface = TicketViviPageEnforcer.loginSurfaceForHierarchy(hierarchy)
    if (surface == null) {
      recordViviLoginStatus("screen_not_detected", cleanReason, completed = true)
      recordTicketEvent("vivi_login_skipped", "screen_not_detected reason=$cleanReason")
      return false
    }
    val credentials = loadViviLoginCredentials(cleanReason) ?: return false
    recordViviLoginStatus("started", cleanReason)
    recordTicketEvent("vivi_login_started", "reason=$cleanReason credentials_configured=true")
    val visibleEmail = surface.visibleEmail.trim()
    if (visibleEmail != credentials.email) {
      if (!enterViviLoginText(surface.email, credentials.email, "vivi_login_email")) {
        recordViviLoginStatus("email_entry_failed", cleanReason, completed = true)
        recordTicketEvent("vivi_login_failed", "stage=email_entry reason=$cleanReason")
        return false
      }
      delay(VIVI_LOGIN_FIELD_SETTLE_MILLIS)
    }
    if (!enterViviLoginText(surface.password, credentials.secret, "vivi_login_secret")) {
      recordViviLoginStatus("secret_entry_failed", cleanReason, completed = true)
      recordTicketEvent("vivi_login_failed", "stage=secret_entry reason=$cleanReason")
      return false
    }
    delay(VIVI_LOGIN_FIELD_SETTLE_MILLIS)
    val submitAction = resolveViviLoginSubmitAfterSecret(surface.submit, cleanReason)
    val submit = runFastNonTouchInput(
      "input tap ${submitAction.x} ${submitAction.y}",
      "vivi_login_submit"
    )
    if (!submit.ok) {
      recordViviLoginStatus("submit_failed", cleanReason, completed = true)
      recordTicketEvent("vivi_login_failed", "stage=submit reason=$cleanReason duration_ms=${submit.durationMs}")
      return false
    }
    recordTicketEvent("vivi_login_submitted", "reason=$cleanReason")
    return waitForViviLoginScreenToDisappear(cleanReason)
  }

  private suspend fun resolveViviLoginSubmitAfterSecret(
    fallback: TicketViviPageAction,
    reason: String
  ): TicketViviPageAction {
    val refreshed = observeRootViviState(
      reason = "vivi_login_submit_target:$reason",
      timeoutMillis = VIVI_LOGIN_ROOT_DUMP_TIMEOUT_MILLIS
    )
    TicketViviPageEnforcer.loginSurfaceForHierarchy(refreshed.hierarchy.orEmpty())?.submit?.let { action ->
      return action
    }
    if (refreshed.state == TicketViviRecoveryState.LOGIN_REQUIRED) {
      runFastNonTouchInput("input keyevent KEYCODE_BACK", "vivi_login_hide_keyboard")
      delay(VIVI_LOGIN_FIELD_SETTLE_MILLIS)
      val keyboardHidden = observeRootViviState(
        reason = "vivi_login_submit_target_keyboard_hidden:$reason",
        timeoutMillis = VIVI_LOGIN_ROOT_DUMP_TIMEOUT_MILLIS
      )
      TicketViviPageEnforcer.loginSurfaceForHierarchy(keyboardHidden.hierarchy.orEmpty())?.submit?.let { action ->
        return action
      }
    }
    recordTicketEvent("vivi_login_submit_target_fallback", "state=${refreshed.state.name} reason=$reason")
    return fallback
  }

  private suspend fun enterViviLoginText(
    action: TicketViviPageAction,
    value: String,
    reason: String
  ): Boolean {
    val textEntryCommand =
      if (reason == "vivi_login_secret") {
        "input text ${shellQuote(androidInputTextLiteral(value))}"
      } else {
        """
          cmd clipboard set text ${shellQuote(value)} >/dev/null 2>&1
          input keyevent KEYCODE_PASTE
        """.trimIndent()
      }
    val command = """
      input tap ${action.x} ${action.y}
      sleep 0.100
      input keyevent KEYCODE_MOVE_END
      for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50 51 52 53 54 55 56 57 58 59 60 61 62 63 64 65 66 67 68 69 70 71 72 73 74 75 76 77 78 79 80; do input keyevent KEYCODE_DEL; done
      $textEntryCommand
    """.trimIndent()
    return runFastNonTouchInput(command, reason).ok
  }

  private suspend fun waitForViviLoginScreenToDisappear(reason: String): Boolean {
    val deadlineMillis = SystemClock.elapsedRealtime() + VIVI_LOGIN_POST_SUBMIT_TIMEOUT_MILLIS
    var lastState = TicketViviRecoveryState.LOGIN_REQUIRED
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      delay(VIVI_LOGIN_POST_SUBMIT_POLL_MILLIS)
      val observation = observeRootViviState(
        reason = "vivi_login_wait:$reason",
        timeoutMillis = VIVI_LOGIN_ROOT_DUMP_TIMEOUT_MILLIS
      )
      lastState = observation.state
      if (observation.state == TicketViviRecoveryState.AUTH_ATTENTION_REQUIRED) {
        recordViviLoginStatus("attention_required", reason, completed = true)
        recordTicketEvent("vivi_login_attention_required", "state=${observation.state.name} reason=$reason")
        return false
      }
      if (
        observation.state != TicketViviRecoveryState.LOGIN_REQUIRED &&
        observation.state != TicketViviRecoveryState.UNKNOWN_VIVI &&
        observation.state != TicketViviRecoveryState.BLANK
      ) {
        recordViviLoginStatus("succeeded", reason, completed = true)
        recordTicketEvent("vivi_login_succeeded", "state=${observation.state.name} reason=$reason")
        return true
      }
    }
    recordViviLoginStatus("timeout", reason, completed = true)
    recordTicketEvent("vivi_login_failed", "stage=wait state=${lastState.name} reason=$reason")
    return false
  }

  private suspend fun loadViviLoginCredentials(reason: String): ViviLoginCredentials? {
    val path = shellQuote(VIVI_LOGIN_SECRET_FILE)
    val result = wakeRootExecutor.runScript(
      """
        if [ ! -r $path ]; then
          exit 44
        fi
        cat $path
      """.trimIndent(),
      VIVI_LOGIN_SECRET_READ_TIMEOUT_MILLIS.milliseconds
    )
    if (!result.ok || result.stdout.isBlank()) {
      recordViviLoginStatus("credentials_missing", reason, completed = true)
      recordTicketEvent("vivi_login_credentials_missing", "reason=$reason")
      return null
    }
    val credentials = parseViviLoginCredentials(result.stdout)
    if (credentials == null) {
      recordViviLoginStatus("credentials_invalid", reason, completed = true)
      recordTicketEvent("vivi_login_credentials_invalid", "reason=$reason")
    }
    return credentials
  }

  private fun parseViviLoginCredentials(raw: String): ViviLoginCredentials? {
    var email = ""
    var secret = ""
    raw.lineSequence().forEach { line ->
      val trimmed = line.trim()
      if (trimmed.isBlank() || trimmed.startsWith("#")) {
        return@forEach
      }
      val separator = trimmed.indexOf('=')
      if (separator <= 0) {
        return@forEach
      }
      val key = trimmed.substring(0, separator).trim()
      val value = decodeViviLoginEnvValue(trimmed.substring(separator + 1))
      when (key) {
        VIVI_LOGIN_EMAIL_ENV -> email = value
        VIVI_LOGIN_SECRET_ENV -> secret = value
      }
    }
    return if (email.isNotBlank() && secret.isNotBlank()) {
      ViviLoginCredentials(email = email, secret = secret)
    } else {
      null
    }
  }

  private fun decodeViviLoginEnvValue(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length >= 2) {
      val first = trimmed.first()
      val last = trimmed.last()
      if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
        return trimmed.substring(1, trimmed.length - 1)
      }
    }
    return trimmed
  }

  private fun recordViviLoginStatus(status: String, reason: String, completed: Boolean = false) {
    recordTicketEvent("vivi_login_$status", "reason=${sanitizeViviLoginReason(reason)} completed=$completed")
  }

  private fun sanitizeViviLoginReason(reason: String): String {
    return reason
      .take(96)
      .replace(Regex("""[^A-Za-z0-9_:.=-]"""), "_")
  }

  private fun recentForegroundGuardTicketDetailStillFresh(nowMillis: Long): Boolean {
    if (!streamActive || !hardwareCaptureVerified || ticketSessionState != TICKET_SESSION_LIVE) {
      return false
    }
    val current = viviStateMemory.current()
    if (current.state != TicketViviRecoveryState.TICKET_DETAIL || current.observedAtMillis <= 0L) {
      return false
    }
    val ageMillis = nowMillis - current.observedAtMillis
    return ageMillis in 0..FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS
  }

  private fun shouldLogForegroundGuardRecentTicketDetailSkip(nowMillis: Long): Boolean {
    if (nowMillis - lastForegroundGuardRecentTicketDetailSkipAtMillis < FOREGROUND_GUARD_RECENT_TICKET_LOG_INTERVAL_MILLIS) {
      return false
    }
    lastForegroundGuardRecentTicketDetailSkipAtMillis = nowMillis
    return true
  }

  private fun foregroundGuardDelayMillis(violation: String?): Long {
    if (violation != null || controlSensitiveWindowActive() || isBudgetedTicketState(ticketSessionState)) {
      return VIVI_FOREGROUND_CHECK_MILLIS
    }
    if (!streamActive || !hardwareCaptureVerified || ticketSessionState != TICKET_SESSION_LIVE) {
      return VIVI_FOREGROUND_CHECK_MILLIS
    }
    val current = viviStateMemory.current()
    return if (current.state == TicketViviRecoveryState.TICKET_DETAIL) {
      VIVI_STABLE_FOREGROUND_CHECK_MILLIS
    } else {
      VIVI_FOREGROUND_CHECK_MILLIS
    }
  }

  private fun viviPageEnforceIntervalMillis(nowMillis: Long): Long {
    return if (stableLiveTicketDetailForSlowActiveGuard(nowMillis)) {
      VIVI_STABLE_PAGE_ENFORCE_INTERVAL_MILLIS
    } else {
      VIVI_PAGE_ENFORCE_INTERVAL_MILLIS
    }
  }

  private fun stableLiveTicketDetailForSlowActiveGuard(nowMillis: Long): Boolean {
    if (
      !streamActive ||
      !hardwareCaptureVerified ||
      ticketSessionState != TICKET_SESSION_LIVE ||
      controlSensitiveWindowActive()
    ) {
      return false
    }
    val current = viviStateMemory.current()
    if (current.state != TicketViviRecoveryState.TICKET_DETAIL || current.observedAtMillis <= 0L) {
      return false
    }
    val ageMillis = nowMillis - current.observedAtMillis
    return ageMillis in 0..VIVI_STABLE_PAGE_ENFORCE_MEMORY_MAX_AGE_MILLIS
  }

  private suspend fun dumpViviHierarchy(
    @Suppress("UNUSED_PARAMETER") fresh: Boolean = false,
    timeoutMillis: Long? = null
  ): RootResult {
    val boundedTimeout = timeoutMillis ?: TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS
    return wakeRootExecutor.runScript(
      TicketUiautomatorDump.command("/sdcard/pixel-ticket-window.xml", boundedTimeout),
      boundedTimeout.milliseconds
    )
  }

  private fun recordRootReadiness(result: String, durationMillis: Long?) {
    recordTicketEvent("root_readiness", "result=${result.take(96)} duration_ms=${durationMillis ?: -1L}")
  }

  private suspend fun observeFastViviState(reason: String): RootViviObservation {
    return observeRootViviState(reason, TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS)
  }

  private suspend fun observeRootViviState(
    reason: String,
    timeoutMillis: Long? = null
  ): RootViviObservation {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val dump = dumpViviHierarchy(fresh = true, timeoutMillis = timeoutMillis)
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    val hierarchy = dump.stdout.takeIf { dump.ok && it.isNotBlank() }
    if (hierarchy.isNullOrBlank()) {
      val error = dump.stderr.takeLast(160).ifBlank { "hierarchy_unavailable" }
      viviStateMemory.record(
        state = TicketViviRecoveryState.UNKNOWN_VIVI,
        ticketId = null,
        source = "root_empty",
        reason = reason
      )
      recordRootReadiness("$reason:UNKNOWN_VIVI", durationMillis)
      return RootViviObservation(TicketViviRecoveryState.UNKNOWN_VIVI, null, durationMillis, error)
    }
    val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    viviStateMemory.record(
      state = state,
      ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(hierarchy),
      source = "root",
      reason = reason
    )
    recordRootReadiness("$reason:${state.name}", durationMillis)
    return RootViviObservation(state, hierarchy, durationMillis)
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
    val dump = dumpViviHierarchy(fresh = true, timeoutMillis = CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS)
    val hierarchy = dump.stdout.takeIf { dump.ok && it.isNotBlank() }
    val state = hierarchy
      ?.let { TicketViviPageEnforcer.classifyForRecovery(it) }
      ?: TicketViviRecoveryState.UNKNOWN_VIVI
    if (!hierarchy.isNullOrBlank()) {
      viviStateMemory.record(
        state = state,
        ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(hierarchy),
        source = "root",
        reason = "control_exit_hierarchy"
      )
    }
    recordRootReadiness("control_exit_hierarchy:${state.name}", dump.durationMs)
    return hierarchy
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
    val timerJob = synchronized(inactivityStateLock) {
      if (inactivityJob?.isCompleted == false) {
        null
      } else {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
          val runningJob = coroutineContext[Job]
          var capturedTimeoutToken: Pair<Long, Long>? = null
          try {
            while (true) {
              delay(TicketInactivityPolicy.nextTickMillis(inactivityRemainingMillis()))
              if (!ticketSessionOpen()) {
                broadcastInactivityStatus()
                return@launch
              }
              broadcastInactivityStatus()
              val timeoutToken = synchronized(inactivityStateLock) {
                if (
                  inactivityJob === runningJob &&
                  TicketInactivityPolicy.timedOut(
                    lastInputAtMillis = lastViewerInputAtMillis,
                    nowMillis = SystemClock.elapsedRealtime()
                  )
                ) {
                  viewerInputGeneration to lastViewerInputAtMillis
                } else {
                  null
                }
              }
              if (timeoutToken != null) {
                recordTicketEvent("ticket_inactivity_timeout")
                capturedTimeoutToken = timeoutToken
                break
              }
            }
          } finally {
            synchronized(inactivityStateLock) {
              if (inactivityJob === runningJob) {
                inactivityJob = null
              }
            }
          }
          capturedTimeoutToken?.let { token ->
            serviceScope.launch {
              val stopped = stopTicketSessionIfStillInactive(
                expectedViewerInputGeneration = token.first,
                expectedLastInputAtMillis = token.second
              )
              if (!stopped && ticketSessionOpen()) {
                ensureInactivityTimer()
              }
            }
          }
        }
        inactivityJob = job
        job
      }
    }
    timerJob?.start()
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
    }.onFailure { error -> recordTicketEvent("blackout_overlay_hide_failed", safeErrorDetail(error)) }
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
      recordTicketEvent("secure_window_capture_bypass_enable_failed", safeRootFailure(result))
    } else {
      secureWindowCaptureBypassActive = true
      secureWindowCaptureBypassMessage = "Secure-window capture bypass is active"
      recordTicketEvent("secure_window_capture_bypass_enabled")
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
      recordTicketEvent("secure_window_capture_bypass_disable_failed", safeRootFailure(result))
    } else {
      secureWindowCaptureBypassActive = false
      secureWindowCaptureBypassMessage = "Secure-window capture bypass is inactive"
      recordTicketEvent("secure_window_capture_bypass_disabled")
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
          recordTicketEvent(
            "secure_window_capture_bypass_direct_root_failed",
            "primary=${safeRootFailure(primary)} fallback=${safeRootFailure(fallback)}"
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
    if (result.ok) {
      recordTicketEvent("ticket_notification_lockdown_enabled", reason)
    } else {
      recordTicketEvent("ticket_notification_lockdown_enable_failed", "reason=$reason ${safeRootFailure(result)}")
    }
  }

  private suspend fun disableNotificationLockdown(reason: String) {
    val result = rootExecutor.runScript(TicketNotificationLockdown.disableScript())
    if (result.ok) {
      recordTicketEvent("ticket_notification_lockdown_disabled", reason)
    } else {
      recordTicketEvent("ticket_notification_lockdown_disable_failed", "reason=$reason ${safeRootFailure(result)}")
    }
  }

  private suspend fun collapseNotificationShade(reason: String) {
    val result = rootExecutor.runScript(TicketNotificationLockdown.collapseScript())
    if (!result.ok) {
      recordTicketEvent("ticket_notification_shade_collapse_failed", "reason=$reason ${safeRootFailure(result)}")
    }
  }

  private fun ensureEncoderIfPossible() {
    if (!streamActive || (videoClients.isEmpty() && !streamCaptureNeededForLatestTicketReselectProof())) {
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && hardwareCaptureVerified) {
      ensureRootHardwareH264CaptureIfPossible()
    }
  }

  private fun prewarmRootHardwareH264CaptureIfPossible(reason: String) {
    if (!streamActive || (videoClients.isEmpty() && !streamCaptureNeededForLatestTicketReselectProof())) {
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      recordStartupTracePhase("root_capture_prewarm_started", reason, once = true)
      ensureRootHardwareH264CaptureIfPossible()
    }
  }

  private fun ensureRootHardwareH264CaptureIfPossible() {
    synchronized(encoderLock) {
      val sourceSize = currentDisplaySize()
      val size = TicketStreamSizing.rootHardwareH264(sourceSize.first, sourceSize.second)
      val previousSize = streamSize
      val previousHealth = rootHardwareH264CaptureEngine.snapshot()
      val streamConfigChanged = streamSizeChanged(previousSize, size)
      val needsNewEpoch = streamEpoch == 0L || streamConfigChanged
      if (needsNewEpoch) {
        resetFrameEpoch("root_hardware_h264_capture_config", active = true)
      }
      if (streamConfigChanged && previousSize != null) {
        recordTicketEvent(
          "root_hardware_h264_capture_config_changed",
          "previous=${streamSizeSummary(previousSize)} next=${streamSizeSummary(size)}"
        )
      }
      streamSize = size
      activeCaptureMode = CAPTURE_MODE_ROOT_HARDWARE_H264
      lastEncoderStartAtMillis = SystemClock.elapsedRealtime()
      val justSentSameConfig = !needsNewEpoch &&
        lastConfigSentAtMillis > 0L &&
        SystemClock.elapsedRealtime() - lastConfigSentAtMillis <= 1_000L
      if (needsNewEpoch || (!justSentSameConfig && (!previousHealth.active || lastConfigSentAtMillis == 0L))) {
        broadcastConfig(size)
      }
      rootHardwareH264CaptureEngine.start(
        sourceWidth = sourceSize.first,
        sourceHeight = sourceSize.second,
        targetWidth = size.width,
        targetHeight = size.height,
        targetBitrate = TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE,
        targetFps = TicketScreenConfig.ROOT_HARDWARE_H264_STEADY_FPS,
        startupFps = TicketScreenConfig.ROOT_HARDWARE_H264_STARTUP_FPS,
        startupFrameCount = TicketScreenConfig.ROOT_HARDWARE_H264_STARTUP_FRAMES
      )
      val pendingStartupKeyFrame = pendingStartupKeyFrameReason
      pendingStartupKeyFrameReason = null
      if (!pendingStartupKeyFrame.isNullOrBlank()) {
        rootHardwareH264CaptureEngine.requestKeyFrame("startup_pending:$pendingStartupKeyFrame")
      }
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      recordStartupTracePhase("root_capture_start_requested", "width=${size.width} height=${size.height} fps=${TicketScreenConfig.ROOT_HARDWARE_H264_STEADY_FPS} startup_fps=${TicketScreenConfig.ROOT_HARDWARE_H264_STARTUP_FPS} startup_frames=${TicketScreenConfig.ROOT_HARDWARE_H264_STARTUP_FRAMES}", once = true)
      scheduleStreamWatchdog("root_capture_start_requested")
    }
  }

  private fun streamSizeChanged(previous: TicketStreamSize?, next: TicketStreamSize): Boolean {
    return previous == null ||
      previous.width != next.width ||
      previous.height != next.height ||
      previous.sourceWidth != next.sourceWidth ||
      previous.sourceHeight != next.sourceHeight ||
      previous.sourceTopCrop != next.sourceTopCrop ||
      previous.sourceVisibleHeight != next.sourceVisibleHeight
  }

  private fun streamSizeSummary(size: TicketStreamSize): String {
    return "${size.width}x${size.height}/source=${size.sourceWidth}x${size.sourceHeight}/crop=${size.sourceTopCrop}/visible=${size.sourceVisibleHeight}"
  }

  private fun handleRootHardwareH264CaptureStateChanged(health: TicketHardwareH264Health) {
    hardwareCaptureSnapshot = health
    if (health.restartCount > lastObservedHardwareRestartCount) {
      lastObservedHardwareRestartCount = health.restartCount
      if (unexpectedHardwareEncoderRestart(health)) {
        noteHardwareReliabilityFailure("hardware_encoder_restart:${health.lastExitReason.orEmpty()}")
      } else {
        recordTicketEvent("hardware_encoder_restart_ignored", health.lastExitReason.orEmpty())
      }
    }
    if (streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && shouldPublishRootHardwareH264Health(health)) {
      recordTicketEvent(
        "hardware_h264_health_changed",
        "state=${health.state} active=${health.active} available=${health.available} frames=${health.frames} keyframes=${health.keyFrames} restarts=${health.restartCount} last_exit=${health.lastExitReason.orEmpty()} frame_age_ms=${ageMillis(lastFrameSentAtMillis, SystemClock.elapsedRealtime()) ?: -1L} clients=${videoClients.size}"
      )
      broadcastStatus()
    }
  }

  private fun unexpectedHardwareEncoderRestart(health: TicketHardwareH264Health): Boolean {
    val reason = health.lastExitReason.orEmpty()
    if (reason.startsWith("requested_restart:")) {
      return false
    }
    when (reason) {
      "hardware_encoder_exit_0",
      "capture_config_changed" -> return false
    }
    if (reason == "hardware_encoder_exit_143") {
      return streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && videoClients.isNotEmpty()
    }
    return true
  }

  private fun noteHardwareReliabilityFailure(reason: String) {
    hardwareReliabilityFailures += 1
    recordTicketEvent("hardware_reliability_failure", "count=$hardwareReliabilityFailures reason=$reason")
    if (hardwareReliabilityFailures >= HARDWARE_RELIABILITY_FAILURE_THRESHOLD && hardwareMarkedUnreliableAtMillis == 0L) {
      hardwareMarkedUnreliableAtMillis = SystemClock.elapsedRealtime()
      hardwareUnreliableReason = "hardware_h264_marked_unreliable:$reason"
      recordTicketEvent("hardware_reliability_marked_unreliable", hardwareUnreliableReason.orEmpty())
    }
  }

  private fun shouldPublishRootHardwareH264Health(health: TicketHardwareH264Health): Boolean {
    val signature = listOf(
      health.available,
      health.active,
      health.state,
      health.message,
      health.width,
      health.height,
      health.bitrate,
      health.fps,
      health.frames == 0L,
      health.frames == 1L,
      health.keyFrames == 0L,
      health.encoderProcessCount,
      health.staleCaptureProcessCount,
      health.lastCaptureCleanupResult,
      health.blankFrameFailures,
      health.lastVisibilityCheckResult,
      health.restartCount,
      health.lastExitReason
    ).joinToString("|")
    if (signature == lastPublishedRootHardwareH264HealthSignature) {
      return false
    }
    lastPublishedRootHardwareH264HealthSignature = signature
    return true
  }

  private fun restartActiveStreamEngine(reason: String) {
    if (!streamActive) {
      return
    }
    lastStreamWatchdogAction = "restart_capture_engine"
    lastStreamWatchdogReason = reason
    lastStreamRecoveryResult = "started"
    lastStreamRecoveryFailureReason = null
    lastStreamRecoveryAtMillis = SystemClock.elapsedRealtime()
    recordTicketEvent(
      "stream_recovery_started",
      "reason=$reason mode=$activeCaptureMode clients=${videoClients.size} frame_age_ms=${ageMillis(lastFrameSentAtMillis, lastStreamRecoveryAtMillis) ?: -1L} watchdog=$streamWatchdogStage"
    )
    recordTicketEvent("active_stream_engine_restart", "mode=$activeCaptureMode reason=$reason")
    resetFrameEpoch("active_stream_engine_restart_$reason", active = true)
    streamSize?.let(::broadcastConfig)
    when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_HARDWARE_H264 -> {
        rootHardwareH264CaptureEngine.restart(reason)
        hardwareCaptureVerified = true
        ensureRootHardwareH264CaptureIfPossible()
      }
    }
    scheduleStreamWatchdog("engine_restart:$reason")
    broadcastStatus()
  }

  private fun scheduleStreamWatchdog(reason: String) {
    if (
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      (videoClients.isEmpty() && !streamCaptureNeededForLatestTicketReselectProof())
    ) {
      return
    }
    if (streamWatchdogJob?.isActive == true) {
      return
    }
    streamWatchdogStage = "watching"
    streamWatchdogJob = serviceScope.launch {
      while (streamWatchdogShouldRun()) {
        delay(STREAM_WATCHDOG_POLL_MILLIS)
        evaluateStreamWatchdog(reason)
      }
      streamWatchdogStage = "idle"
      streamWatchdogJob = null
    }
  }

  private fun streamWatchdogShouldRun(): Boolean {
    return streamActive &&
      activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
      (videoClients.isNotEmpty() || streamCaptureNeededForLatestTicketReselectProof()) &&
      ticketSessionState != TICKET_SESSION_NEEDS_ATTENTION &&
      lastRootH264BlankProbeResult != "secure_capture_blocked"
  }

  private fun evaluateStreamWatchdog(trigger: String) {
    if (!streamWatchdogShouldRun()) {
      return
    }
    if (!hardwareFrameBroadcastAllowed) {
      streamWatchdogStage = "waiting_phone_ready"
      return
    }
    val nowMillis = SystemClock.elapsedRealtime()
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis)
    if (frameAgeMillis != null && frameAgeMillis <= LIVE_FRAME_MAX_AGE_MILLIS) {
      streamWatchdogStage = "healthy"
      if (lastStreamRecoveryResult == "started") {
        lastStreamRecoveryResult = "succeeded"
        lastStreamRecoveryFailureReason = null
        recordTicketEvent(
          "stream_recovery_completed",
          "reason=${lastStreamWatchdogReason.orEmpty()} frame_age_ms=${frameAgeMillis ?: -1L} clients=${videoClients.size}"
        )
      }
      return
    }
    val encoderStartAgeMillis = ageMillis(lastEncoderStartAtMillis, nowMillis)
    if (encoderStartAgeMillis == null || encoderStartAgeMillis < STREAM_WATCHDOG_NO_ENCODER_RESTART_MILLIS) {
      streamWatchdogStage = "waiting_startup"
      return
    }
    val health = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    val recoveryReason = when {
      !health.active -> "watchdog_no_encoder"
      lastFrameSentAtMillis == 0L && encoderStartAgeMillis >= STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS -> "watchdog_no_first_frame"
      frameAgeMillis != null && frameAgeMillis >= STREAM_WATCHDOG_STALE_FRAME_RESTART_MILLIS -> "watchdog_stale_visible_frame"
      else -> null
    }
    if (recoveryReason == null) {
      streamWatchdogStage = if (health.active) "waiting_frame" else "waiting_encoder"
      return
    }
    if (lastStreamRecoveryAtMillis > 0L && nowMillis - lastStreamRecoveryAtMillis < STREAM_WATCHDOG_RECOVERY_COOLDOWN_MILLIS) {
      streamWatchdogStage = "cooldown"
      return
    }
    streamWatchdogStage = "recovering"
    recordTicketEvent(
      "stream_watchdog_recovery_started",
      "reason=$recoveryReason trigger=$trigger encoder_active=${health.active} frame_age_ms=${frameAgeMillis ?: -1} encoder_start_age_ms=$encoderStartAgeMillis"
    )
    restartActiveStreamEngine(recoveryReason)
  }

  private fun configMessage(size: TicketStreamSize): String {
    val hardware = rootHardwareH264CaptureEngine.snapshot()
    val codec = TicketScreenConfig.ROOT_HARDWARE_H264_CODEC_STRING
    val transport = TicketScreenConfig.ROOT_HARDWARE_H264_TRANSPORT
    val qualityProfile = TicketScreenConfig.ROOT_HARDWARE_H264_QUALITY_PROFILE
    val captureSource = hardware.captureSource
    val captureMethod = hardware.captureMethod
    val bitrate = TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
    val fps = TicketScreenConfig.ROOT_HARDWARE_H264_FPS
    val keyFrameInterval = TicketScreenConfig.ROOT_HARDWARE_H264_KEYFRAME_INTERVAL_MILLIS
    val colorCorrection = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_CORRECTION
    val colorStandard = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_STANDARD
    val phoneUptimeMillis = SystemClock.elapsedRealtime()
    return """
      {"type":"config","serverVersion":"$SERVER_VERSION","codec":"$codec","transport":"$transport","captureMode":"$activeCaptureMode","captureSource":${json.encodeToString(captureSource)},"captureMethod":${json.encodeToString(captureMethod)},"rootCapture":true,"frameEnvelope":"$FRAME_ENVELOPE_VERSION","streamEpoch":$streamEpoch,"phoneUptimeMillis":$phoneUptimeMillis,"qualityProfile":"$qualityProfile","colorCorrection":${json.encodeToString(colorCorrection)},"colorStandard":${json.encodeToString(colorStandard)},"width":${size.width},"height":${size.height},"sourceWidth":${size.sourceWidth},"sourceHeight":${size.sourceHeight},"sourceTopCrop":${size.sourceTopCrop},"sourceVisibleHeight":${size.sourceVisibleHeight},"bitrate":$bitrate,"fps":$fps,"keyFrameIntervalMillis":$keyFrameInterval}
    """.trimIndent()
  }

  private fun broadcastConfig(size: TicketStreamSize) {
    val message = configMessage(size)
    lastConfigSentAtMillis = SystemClock.elapsedRealtime()
    recordStartupTracePhase("stream_config_sent", "width=${size.width} height=${size.height} clients=${videoClients.size}", once = true)
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
    if (activeCaptureMode == CAPTURE_MODE_IDLE) {
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
          if (keyFrame) {
            if (sendState.pendingFrame != null) {
              droppedVideoFrames += 1
            }
            sendState.pendingFrame = frame
            sendState.pendingKeyFrame = true
            sendState.pendingQueuedAtMillis = nowMillis
          } else if (sendState.pendingFrame == null) {
            sendState.pendingFrame = frame
            sendState.pendingKeyFrame = false
            sendState.pendingQueuedAtMillis = nowMillis
          } else {
            droppedVideoFrames += 1
            sendState.pendingFrame = null
            sendState.pendingKeyFrame = false
            sendState.pendingQueuedAtMillis = 0L
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
          if (pendingFrame != null && sendState.pendingQueuedAtMillis > 0L &&
            SystemClock.elapsedRealtime() - sendState.pendingQueuedAtMillis > VIDEO_CLIENT_PENDING_MAX_AGE_MILLIS
          ) {
            droppedVideoFrames += 1
            pendingFrame = null
            pendingKeyFrame = false
            sendState.waitingForKeyFrame = true
            requestFreshKeyFrame = true
          }
          sendState.pendingFrame = null
          sendState.pendingKeyFrame = false
          sendState.pendingQueuedAtMillis = 0L
        }
        if (requestFreshKeyFrame) {
          requestKeyFrame("video_client_pending_frame_stale")
        }
        if (pendingFrame != null) {
          sendVideoFrame(client, pendingFrame!!, pendingKeyFrame)
        }
      }
    }
  }

  private fun handleRootHardwareH264CaptureFrame(frame: TicketRootCaptureFrame) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      return
    }
    encodedFrames += 1
    lastFrameEncodedAtMillis = SystemClock.elapsedRealtime()
    if (!frame.keyFrame && latestKeyFrameEnvelope == null) {
      droppedVideoFrames += 1
      requestKeyFrame("hardware_h264_waiting_initial_key_frame")
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      return
    }
    if (frame.keyFrame) {
      keyFrames += 1
      lastKeyFrameEncodedAtMillis = lastFrameEncodedAtMillis
      recordStartupTracePhase("first_keyframe_encoded", "encoded_frames=$encodedFrames", once = true)
    }
    if (!hardwareFrameBroadcastAllowed) {
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      return
    }
    val firstVisibleFrame = sentFrames == 0L
    broadcastFrame(
      keyFrame = frame.keyFrame,
      timestampUs = frame.timestampUs,
      payload = frame.payload
    )
    hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
    if (hardwareCaptureVerified) {
      recordStartupTracePhase("first_visible_frame_sent", "sequence=$frameSequence keyframe=${frame.keyFrame}", once = true, complete = true)
      if (lastStreamRecoveryResult == "started") {
        lastStreamRecoveryResult = "succeeded"
        lastStreamRecoveryFailureReason = null
        lastStreamRecoveryAtMillis = SystemClock.elapsedRealtime()
        streamWatchdogStage = "healthy"
        recordTicketEvent("stream_watchdog_recovery_succeeded", lastStreamWatchdogReason.orEmpty())
        recordTicketEvent(
          "stream_recovery_completed",
          "reason=${lastStreamWatchdogReason.orEmpty()} frame_sequence=$frameSequence keyframe=${frame.keyFrame} clients=${videoClients.size}"
        )
      }
    }
    if ((firstVisibleFrame || ticketSessionState == TICKET_SESSION_STARTING) && hardwareCaptureVerified) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_hardware_h264_first_visible_frame")
      lastMessage = "Ticket session is active through hardware H.264 capture"
      broadcastStatus()
    } else if (firstVisibleFrame) {
      recordTicketEvent("root_hardware_h264_startup_frame", "awaiting_ticket_ready")
      broadcastStatus()
    }
    if (encodedFrames <= SECURE_CAPTURE_PROBE_START_FRAME_COUNT) {
      scheduleRootHardwareSecureCaptureProbe("root_hardware_h264_frame")
    }
  }

  private fun scheduleRootHardwareSecureCaptureProbe(reason: String) {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
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
    if (recentVisibleSecureCaptureProbeStillFresh(nowMillis)) {
      recordTicketEvent("secure_capture_probe_recent_visible_reused", reason)
      return
    }
    rootH264BlankProbeJob = serviceScope.launch {
      delay(SECURE_CAPTURE_PROBE_DELAY_MILLIS)
      if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
        rootH264BlankProbeJob = null
        return@launch
      }
      lastRootH264BlankProbeAtMillis = SystemClock.elapsedRealtime()
      val visible = verifyRootHardwareSecureCapturePixelsVisible(reason)
      if (visible) {
        lastRootH264BlankProbeResult = "visible"
        lastRootH264VisibleProbePassedAtMillis = SystemClock.elapsedRealtime()
        rootH264BlankProbeJob = null
        broadcastStatus()
        return@launch
      }
      rootH264BlankProbeFailures += 1L
      lastRootH264BlankProbeResult = "secure_capture_blocked"
      streamWatchdogStage = "blocked"
      lastStreamWatchdogAction = "secure_capture_blocked"
      lastStreamWatchdogReason = reason
      lastStreamRecoveryResult = "failed"
      lastStreamRecoveryFailureReason = "secure_capture_blocked"
      lastStreamRecoveryAtMillis = SystemClock.elapsedRealtime()
      recordTicketEvent("stream_recovery_failed", "reason=$reason failure=secure_capture_blocked clients=${videoClients.size}")
      rootHardwareH264CaptureEngine.stop("secure_capture_blocked:$reason")
      rootHardwareH264CaptureEngine.cleanupStaleProcesses()
      streamActive = false
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      resetFrameEpoch("secure_capture_blocked:$reason", active = false)
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_blocked")
      lastMessage = "ViVi is protected from capture; stream was stopped"
      recordTicketEvent("secure_capture_blocked", reason)
      rootH264BlankProbeJob = null
      broadcastStatus()
    }
  }

  private fun recentVisibleSecureCaptureProbeStillFresh(nowMillis: Long): Boolean {
    if (lastRootH264BlankProbeResult != "visible") {
      return false
    }
    val visibleAge = ageMillis(lastRootH264VisibleProbePassedAtMillis, nowMillis) ?: return false
    return visibleAge <= SECURE_CAPTURE_VISIBLE_PROBE_REUSE_MILLIS
  }

  private fun requestKeyFrame(reason: String = "browser_request") {
    val nowMillis = SystemClock.elapsedRealtime()
    lastKeyFrameRequestedAtMillis = nowMillis
    recordStartupTracePhase("keyframe_requested", reason, once = true)
    if (!streamActive || activeCaptureMode == CAPTURE_MODE_IDLE) {
      pendingStartupKeyFrameReason = reason
      recordTicketEvent("keyframe_held_for_startup", reason)
      return
    }
    if (activeStreamStaleForRecovery(nowMillis)) {
      restartActiveStreamEngine("stale_keyframe_request_$reason")
    } else if (reason == "viewport_changed" && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      ensureRootHardwareH264CaptureIfPossible()
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      rootHardwareH264CaptureEngine.requestKeyFrame(reason)
    }
  }

  private fun activeStreamStaleForRecovery(nowMillis: Long): Boolean {
    if (
      !streamActive ||
      activeCaptureMode == CAPTURE_MODE_IDLE ||
      (videoClients.isEmpty() && !streamCaptureNeededForLatestTicketReselectProof(nowMillis))
    ) {
      return false
    }
    if (hardwareStartupStillPreparing(nowMillis)) {
      return false
    }
    val lastFrameAge = ageMillis(lastFrameSentAtMillis, nowMillis)
    if (lastFrameAge != null) {
      return lastFrameAge > STREAM_STALE_ENGINE_RESTART_MILLIS
    }
    val configAge = ageMillis(lastConfigSentAtMillis, nowMillis)
    return ticketSessionState != TICKET_SESSION_STARTING &&
      configAge != null &&
      configAge > STREAM_STALE_ENGINE_RESTART_MILLIS
  }

  private fun hardwareStartupStillPreparing(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    if (activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      return false
    }
    if (ticketSessionState == TICKET_SESSION_STARTING || !hardwareCaptureVerified) {
      return true
    }
    if (lastFrameSentAtMillis == 0L) {
      val encoderStartAgeMillis = ageMillis(lastEncoderStartAtMillis, nowMillis)
      return encoderStartAgeMillis == null || encoderStartAgeMillis < STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS
    }
    return encodedFrames == 0L
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
    return clientGenerationCounter.incrementAndGet()
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
      clientInfo.filter { (client, existing) ->
        existing.video == info.video && (info.video || !protectedControlClients.contains(client))
      }.keys.toList()
    }
    duplicates.forEach { client ->
      replacedClientSockets += 1
      recordTicketEvent(
        "client_replaced",
        "generation=${info.generation} video=${info.video}"
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
    val startupClientGraceActive = startupDisconnectGraceUntilMillis > SystemClock.elapsedRealtime()
    val protectedControlActive = protectedControlClients.isNotEmpty() || controlCodeRequestActive()
    if (streamActive ||
      ticketSessionState == TICKET_SESSION_STARTING ||
      totalClientCount() == 0 ||
      startupClientGraceActive ||
      protectedControlActive
    ) {
      if ((startupClientGraceActive || protectedControlActive) && totalClientCount() > 0 && !streamActive) {
        recordTicketEvent("inactive_stream_cleanup_deferred", "$reason clients=${totalClientCount()}")
      }
      return
    }
    closeAllClients("inactive_stream_$reason")
  }

  private fun totalClientCount(): Int = controlClients.size + videoClients.size

  private fun streamClientTraceDetail(info: TicketClientInfo, @Suppress("UNUSED_PARAMETER") status: String): String {
    val nowMillis = SystemClock.elapsedRealtime()
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: -1L
    val h264 = hardwareCaptureSnapshot
    return "generation=${info.generation} video=${info.video} video_clients=${videoClients.size} stream_active=$streamActive frame_age_ms=$frameAgeMillis h264_active=${h264.active}"
  }

  private fun recordTicketEvent(event: String, detail: String = "") {
    val cleanEvent = event.take(96)
    val safeFields = TicketTracePrivacy.allowlistedFields(detail)
    enqueueTicketSpacetimeTraceEvent(cleanEvent, safeFields)
  }

  private fun enqueueTicketSpacetimeTraceEvent(event: String, detailFields: Map<String, String>) {
    if (!shouldPublishTicketTraceEvent(event)) {
      return
    }
	    val nowMillis = SystemClock.elapsedRealtime()
	    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: -1L
	    val h264 = hardwareCaptureSnapshot
	    val eventAtEpochMillis = System.currentTimeMillis()
	    val message = buildJsonObject {
	      put("type", "ticket_trace_event")
	      put("event", event)
      put("eventAtEpochMillis", eventAtEpochMillis.toString())
      put("eventAtPhoneUptimeMillis", nowMillis.toString())
      put(
        "level",
        if (
          event.contains("failed") ||
          event.contains("failure") ||
          event.contains("blocked") ||
          event.contains("unavailable") ||
          event.contains("exit") ||
          event.contains("restart")
        ) {
          "warn"
        } else {
          "info"
        },
      )
      detailFields.forEach { (key, value) -> put("detail_$key", value) }
      put("streamState", ticketSpacetimeStreamState())
      put("sessionState", ticketSessionState)
      put("streamActive", streamActive)
      put("captureMode", activeCaptureMode)
      put("videoClients", videoClients.size.toString())
	      put("frameSequence", frameSequence.toString())
	      put("sentFrames", sentFrames.toString())
	      put("lastFreshFrameAgeMillis", frameAgeMillis.toString())
	      put("phoneUptimeMillis", nowMillis.toString())
	      put("hardwareH264State", h264.state)
	      put("hardwareH264Active", h264.active.toString())
	      put("hardwareH264Available", h264.available.toString())
	      put("hardwareH264Frames", h264.frames.toString())
	      put("hardwareH264KeyFrames", h264.keyFrames.toString())
	      put("hardwareH264Restarts", h264.restartCount.toString())
	      put("hardwareH264LastFrameAgeMillis", h264.lastFrameAgoMillis?.toString().orEmpty())
	      put("hardwareH264LastStartAgeMillis", h264.lastStartAgoMillis?.toString().orEmpty())
	      put("hardwareH264HelperState", h264.captureHelperState)
	      put("hardwareH264Visibility", h264.lastVisibilityCheckResult)
	      put("lastStreamRecoveryAgeMillis", ageMillis(lastStreamRecoveryAtMillis, nowMillis)?.toString().orEmpty())
	      put("streamWatchdogStage", streamWatchdogStage)
	      put("lastStreamWatchdogAction", lastStreamWatchdogAction)
	      put("lastVideoClientAgeMillis", ageMillis(lastVideoClientConnectedAtMillis, nowMillis)?.toString().orEmpty())
	      put("timestampMillis", eventAtEpochMillis.toString())
	    }.toString()
    enqueueTicketSpacetimePhoneMessage(message)
  }

  private fun shouldPublishTicketTraceEvent(event: String): Boolean {
    return event.startsWith("session_") ||
      event.startsWith("spacetime_") ||
      event.startsWith("startup_phase_") ||
      event.startsWith("hardware_") ||
      event.startsWith("stream_") || event.startsWith("recovery_") ||
      event.startsWith("root_hardware") ||
      event.startsWith("root_capture") ||
      event.startsWith("loading_") ||
      event.startsWith("client_") ||
      event.startsWith("keyframe") ||
      event.startsWith("ticket_brightness_") ||
      event.startsWith("latest_ticket_reselect_") ||
      event.startsWith("control_code_") ||
      event.startsWith("ticket_control_code_") ||
      event.startsWith("ticket_card_") ||
      event == "ticket_state_event" ||
      event == "vivi_hard_reset" ||
      event == "secure_capture_blocked"
  }

  private fun safeErrorDetail(error: Throwable): String {
    return (error.message ?: error::class.java.simpleName)
      .split(Regex("\\s+"))
      .joinToString(" ")
      .take(MAX_TICKET_EVENT_DETAIL_BYTES)
  }

  private fun safeRootFailure(result: RootResult): String {
    return (result.stderr.ifBlank { result.stdout }.ifBlank { "exit_code=${result.exitCode}" })
      .split(Regex("\\s+"))
      .joinToString(" ")
      .take(MAX_TICKET_EVENT_DETAIL_BYTES)
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
    val hardwareCapture = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    return TicketStreamPipeline(
      controlClients = controlClients.size,
      videoClients = videoClients.size,
      captureMode = activeCaptureMode,
      codec = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_HARDWARE_H264 -> TicketScreenConfig.ROOT_HARDWARE_H264_CODEC_STRING
        else -> ""
      },
      transport = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_HARDWARE_H264 -> TicketScreenConfig.ROOT_HARDWARE_H264_TRANSPORT
        else -> ""
      },
      frameEnvelope = FRAME_ENVELOPE_VERSION,
      streamEpoch = streamEpoch,
      frameSequence = frameSequence,
      lastKeyFrameSequence = latestKeyFrameSequence,
      qualityProfile = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_HARDWARE_H264 -> TicketScreenConfig.ROOT_HARDWARE_H264_QUALITY_PROFILE
        else -> "idle"
      },
      configuredWidth = streamSize?.width,
      configuredHeight = streamSize?.height,
      configuredSourceWidth = streamSize?.sourceWidth,
      configuredSourceHeight = streamSize?.sourceHeight,
      sourceTopCrop = streamSize?.sourceTopCrop ?: TicketScreenConfig.TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS,
      sourceVisibleHeight = streamSize?.sourceVisibleHeight,
      configuredBitrate = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_HARDWARE_H264 -> TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
        else -> null
      },
      lastFrameBytes = lastFrameBytes,
      lastKeyFrameBytes = lastKeyFrameBytes,
      estimatedSendBitrate = estimatedSendBitrate,
      freshKeyFrameCacheMaxAgeMillis = ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS,
      colorCorrection = if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_CORRECTION else "none",
      colorStandard = if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_STANDARD else "",
      postCleanupFreshFrameVerifiedAgoMillis = ageMillis(lastPostCleanupFreshFrameVerifiedAtMillis, nowMillis),
      postCleanupFreshFrameVerificationReason = lastPostCleanupFreshFrameVerificationReason,
      encoderRunning = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_HARDWARE_H264 -> hardwareCapture.active
        else -> false
      },
      streamConfigured = streamSize != null,
      encodedFrames = encodedFrames,
      sentFrames = sentFrames,
      keyFrames = keyFrames,
      droppedVideoFrames = droppedVideoFrames,
      slowVideoWrites = slowVideoWrites,
      closedSlowVideoClients = closedSlowVideoClients,
      replacedClientSockets = replacedClientSockets,
      lastClientGeneration = clientGenerationCounter.get(),
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

  private fun streamVerdict(hardwareCapture: TicketHardwareH264Health, nowMillis: Long): String {
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
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && hardwareCapture.frames == 0L && hardwareCapture.restartCount > 0L) {
      return "capture_blocked"
    }
    if (lastFrameSentAtMillis > 0L && ageMillis(lastFrameSentAtMillis, nowMillis)?.let { it <= LIVE_FRAME_MAX_AGE_MILLIS } == true) {
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
    val hardwareCapture = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    val vivi = TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)
    val ok = running.get() && vivi && hardwareCapture.available
    val visibleFrameCodec = when (activeCaptureMode) {
      CAPTURE_MODE_ROOT_HARDWARE_H264 -> TicketScreenConfig.ROOT_HARDWARE_H264_CODEC_STRING
      else -> ""
    }
    val recoveryState = ticketRecovery
    val recoverySnapshot = TicketRecoveryHealth(
      state = recoveryState.state,
      currentReason = recoveryState.reason,
      currentMode = recoveryState.mode?.name?.lowercase(),
      lastResult = recoveryState.result,
      lastStep = recoveryState.step,
      startedAgoMillis = ageMillis(recoveryState.startedAtMillis, nowMillis),
      completedAgoMillis = ageMillis(recoveryState.completedAtMillis, nowMillis),
      streamStage = streamWatchdogStage,
      lastWatchdogAction = lastStreamWatchdogAction,
      lastStreamRecoveryResult = lastStreamRecoveryResult,
      lastStreamRecoveryReason = lastStreamWatchdogReason,
      lastStreamRecoveryAgoMillis = ageMillis(lastStreamRecoveryAtMillis, nowMillis),
      lastStreamRecoveryFailureReason = lastStreamRecoveryFailureReason,
      desiredRecoveryStage = spacetimeDesiredRecoveryStage,
      lastDesiredRecoveryAction = lastSpacetimeDesiredRecoveryAction,
      lastDesiredRecoveryResult = lastSpacetimeDesiredRecoveryResult,
      lastDesiredRecoveryAgoMillis = ageMillis(lastSpacetimeDesiredRecoveryAtMillis, nowMillis),
      lastDesiredRecoveryFailureReason = lastSpacetimeDesiredRecoveryFailureReason,
      lastDesiredRecoveryProbeResult = lastSpacetimeDesiredRecoveryProbeResult
    )
    val rawViviHealth = viviStateMemory.health(nowMillis)
    val viviHealth = effectiveViviHealthForPublicStream(rawViviHealth, nowMillis, hardwareCapture)
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
      phoneUptimeMillis = nowMillis,
      sessionState = effectiveSessionState,
      serverRunning = running.get(),
      viviInstalled = vivi,
      accrescentInstalled = TicketScreenConfig.ACCRESCENT_PACKAGE in installedStores,
      installedStorePackages = installedStores,
      streamActive = streamActive,
      streamVerdict = streamVerdict(hardwareCapture, nowMillis),
      clients = totalClientCount(),
      inactivityActive = ticketSessionOpen(),
      inactivityTimeoutMillis = TicketInactivityPolicy.TIMEOUT_MILLIS,
      inactivityRemainingMillis = inactivityRemainingMillis(nowMillis),
      autoStartAllowed = TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(lastSessionStopReason),
      autoStartBlockedReason = lastSessionStopReason?.takeUnless {
        TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(it)
      },
      controlCodeRequest = TicketControlCodeRequestHealth(
        requestId = lastControlCodeRequestId,
        status = lastControlCodeRequestStatus,
        reason = lastControlCodeRequestReason,
        value = null,
        commandOwner = lastControlCodeCommandOwner,
        commandApp = lastControlCodeCommandApp,
        commandFlow = lastControlCodeCommandFlow,
        totalDurationMillis = lastControlCodeRequestDurationMillis,
        phases = lastControlCodeRequestPhases,
        browserCaptureAckMillis = lastControlCodeRequestPhases["browser_capture_ack_wait"],
        browserCaptureReason = lastControlCodeBrowserCaptureReason,
        browserCaptureAgoMillis = ageMillis(lastControlCodeBrowserCaptureCompletedAtMillis, nowMillis),
        completedAgoMillis = ageMillis(lastControlCodeRequestCompletedAtMillis, nowMillis),
        duplicateResults = duplicateControlCodeResultCount,
        lastDuplicateRequestId = lastDuplicateControlCodeRequestId,
        lastDuplicateAgoMillis = ageMillis(lastDuplicateControlCodeResultAtMillis, nowMillis)
      ),
      latestTicketReselect = TicketLatestTicketReselectHealth(
        status = latestTicketReselectStatus,
        active = latestTicketReselectActive(nowMillis),
        reason = latestTicketReselectReason,
        commandId = latestTicketReselectCommandId.takeLast(24),
        phase = latestTicketReselectPhase,
        ticketDetailAgoMillis = ageMillis(latestTicketReselectTicketDetailAtMillis, nowMillis),
        proofSource = latestTicketReselectProofSource,
        proofHoldRemainingMillis = (latestTicketReselectProofHoldUntilMillis - nowMillis).coerceAtLeast(0L),
        startedAgoMillis = ageMillis(latestTicketReselectStartedAtMillis, nowMillis),
        completedAgoMillis = ageMillis(latestTicketReselectCompletedAtMillis, nowMillis),
        freshFrameAgoMillis = ageMillis(latestTicketReselectFreshFrameAtMillis, nowMillis)
      ),
      rigasSatiksmeBatch = TicketRigasSatiksmeBatchHealth(
        batchId = lastRigasSatiksmeBatchId,
        status = lastRigasSatiksmeBatchStatus,
        activeRequestId = lastRigasSatiksmeBatchActiveRequestId,
        jobCount = lastRigasSatiksmeBatchJobCount,
        completedCount = lastRigasSatiksmeBatchCompletedCount,
        lastResultRequestId = lastRigasSatiksmeBatchResultRequestId,
        lastResultStatus = lastRigasSatiksmeBatchResultStatus,
        lastResultReason = lastRigasSatiksmeBatchResultReason,
        lastCancelReason = lastRigasSatiksmeBatchCancelReason,
        phases = lastRigasSatiksmeBatchPhases,
        completedAgoMillis = ageMillis(lastRigasSatiksmeBatchCompletedAtMillis, nowMillis)
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
      hardwareH264 = hardwareCapture,
      recovery = recoverySnapshot,
      ticketState = TicketControlStateHealth(
        state = effectiveSessionState,
        stateAgeMillis = ageMillis(ticketSessionStateChangedAtMillis, nowMillis),
        lastReason = ticketSessionStateReason
      ),
      viviState = viviHealth,
      streamPipeline = streamPipelineSnapshot(nowMillis),
      message = when {
        !vivi -> "ViVi is not installed from a local Pixel app store yet"
        streamActive -> lastMessage
        activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && !hardwareCapture.available -> hardwareCapture.message
        hardwareCapture.available -> lastMessage
        else -> lastMessage
      }
    )
  }

  private fun effectiveViviHealthForPublicStream(
    rawViviHealth: TicketViviStateHealth,
    nowMillis: Long,
    hardwareCapture: TicketHardwareH264Health
  ): TicketViviStateHealth {
    if (
      rawViviHealth.state != TicketViviRecoveryState.UNKNOWN_VIVI.name &&
      rawViviHealth.state != TicketViviRecoveryState.BLANK.name
    ) {
      return rawViviHealth
    }
    if (
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      !hardwareCaptureVerified ||
      !hardwareCapture.active ||
      ticketSessionState != TICKET_SESSION_LIVE ||
      lastPixelTicketState != TICKET_PIXEL_STATE_RAW_TICKET
    ) {
      return rawViviHealth
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: return rawViviHealth
    if (frameAgeMillis > LIVE_FRAME_MAX_AGE_MILLIS) {
      return rawViviHealth
    }
    val ticketEventAgeMillis = ageMillis(lastPixelTicketEventSentAtMillis, nowMillis) ?: return rawViviHealth
    if (ticketEventAgeMillis > ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS) {
      return rawViviHealth
    }
    val recent = viviStateMemory.recentTicketDetailWithin(ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS)
      ?: return rawViviHealth
    return TicketViviStateHealth(
      state = TicketViviRecoveryState.TICKET_DETAIL.name,
      ticketId = recent.ticketId,
      observedAgoMillis = (nowMillis - recent.observedAtMillis).coerceAtLeast(0L),
      source = "effective_stream_recent_ticket_detail",
      reason = "raw_ticket_stream_live_after_${rawViviHealth.source}"
    )
  }

  private fun currentViviStateIsInconclusiveFastObservation(
    current: TicketViviStateMemorySnapshot
  ): Boolean {
    return current.state in setOf(TicketViviRecoveryState.UNKNOWN_VIVI, TicketViviRecoveryState.BLANK) &&
      current.source in setOf("fast_empty", "root_empty")
  }

  private fun recentLiveRawTicketProofForControlCode(
    nowMillis: Long,
    maxAgeMillis: Long
  ): TicketViviStateMemorySnapshot? {
    if (
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      !hardwareCaptureVerified ||
      ticketSessionState != TICKET_SESSION_LIVE ||
      lastPixelTicketState != TICKET_PIXEL_STATE_RAW_TICKET
    ) {
      return null
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: return null
    if (frameAgeMillis > LIVE_FRAME_MAX_AGE_MILLIS) {
      return null
    }
    val ticketEventAgeMillis = ageMillis(lastPixelTicketEventSentAtMillis, nowMillis) ?: return null
    if (ticketEventAgeMillis > maxAgeMillis) {
      return null
    }
    return viviStateMemory.recentTicketDetailWithin(maxAgeMillis)
  }

  private fun markTicketNonTouchAction(reason: String) {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
  }

  private fun launchVivi() {
    val launchIntent = packageManager.getLaunchIntentForPackage(TicketScreenConfig.VIVI_PACKAGE)
    if (launchIntent == null) {
      lastMessage = "ViVi launch intent is unavailable"
      return
    }
    markTicketNonTouchAction("vivi_launch")
    try {
      startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } finally {
      markTicketNonTouchAction("vivi_launch:complete")
    }
    scheduleTicketBrightnessGuard("vivi_launch")
  }

  private suspend fun launchViviForWake(
    reason: String,
    timeoutMillis: Long = TICKET_WAKE_LAUNCH_TIMEOUT_MILLIS
  ) {
    recordTicketEvent("wake_launch_vivi_root", reason)
    val boundedTimeoutMillis = timeoutMillis.coerceAtLeast(1L)
    val result = runFastNonTouchWakeScript(
      """
        am start -n ${TicketScreenConfig.VIVI_LAUNCH_ACTIVITY} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
      """.trimIndent(),
      "wake_launch_vivi:$reason",
      boundedTimeoutMillis.milliseconds
    )
    recordTicketEvent("wake_launch_vivi_root", "ok=${result.ok} duration_ms=${result.durationMs} timeout_ms=$boundedTimeoutMillis")
  }

  private suspend fun fastWakeReadyFromRecentTicketDetail(
    reason: String,
    wakeStartedAtMillis: Long
  ): TicketAutopilotResult? {
    val recent = viviStateMemory.recentTicketDetailWithin(TICKET_WAKE_FOCUSED_TICKET_DETAIL_FAST_READY_MAX_AGE_MILLIS) ?: return null
    val ageMillis = (SystemClock.elapsedRealtime() - recent.observedAtMillis).coerceAtLeast(0L)
    val current = viviStateMemory.current()
    val currentAgeMillis = if (current.observedAtMillis > 0L) {
      (SystemClock.elapsedRealtime() - current.observedAtMillis).coerceAtLeast(0L)
    } else {
      Long.MAX_VALUE
    }
    if (
      current.observedAtMillis > recent.observedAtMillis &&
      currentStateInvalidatesRecentTicketDetailFastWake(current.state)
    ) {
      recordTicketEvent(
        "wake_recent_ticket_detail_fast_ready_current_non_detail",
        "reason=$reason current=${current.state.name} age_ms=$currentAgeMillis"
      )
      return null
    }
    val freshTicketDetail = ageMillis in 0..TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS
    val focusedStaleTicketDetail = ageMillis in 0..TICKET_WAKE_FOCUSED_TICKET_DETAIL_FAST_READY_MAX_AGE_MILLIS
    if (!freshTicketDetail && !focusedStaleTicketDetail) {
      recordTicketEvent("wake_recent_ticket_detail_fast_ready_stale", "reason=$reason age_ms=$ageMillis")
      return null
    }
    if (!ticketScreenInteractive()) {
      return null
    }
    val focused = focusedWindowSnapshot() ?: return null
    if (!focused.contains(TicketScreenConfig.VIVI_PACKAGE)) {
      recordTicketEvent("wake_recent_ticket_detail_fast_ready_skipped", "reason=$reason focused=${focused.take(120)}")
      return null
    }
    recordTicketWakePhase("vivi_foreground", wakeStartedAtMillis)
    recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
    if (freshTicketDetail) {
      recordTicketEvent("wake_recent_ticket_detail_fast_ready", "reason=$reason age_ms=$ageMillis")
      return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, "wake_recent_ticket_detail_fast_ready")
    }
    recordTicketEvent(
      "wake_focused_stale_ticket_detail_fast_ready",
      "reason=$reason age_ms=$ageMillis current=${current.state.name}:${current.source}"
    )
    return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, "wake_focused_stale_ticket_detail_fast_ready")
  }

  private suspend fun fastWakeReadyFromRecentTicketDetailAfterLaunch(
    reason: String,
    wakeStartedAtMillis: Long,
    budgetMillis: Long = TICKET_WAKE_BUDGET_MILLIS
  ): TicketAutopilotResult? {
    val deadlineMillis = minOf(
      SystemClock.elapsedRealtime() + TICKET_WAKE_POST_LAUNCH_FAST_READY_TIMEOUT_MILLIS,
      wakeStartedAtMillis + budgetMillis
    )
    while (SystemClock.elapsedRealtime() <= deadlineMillis) {
      val result = fastWakeReadyFromRecentTicketDetail("$reason:post_launch", wakeStartedAtMillis)
      if (result != null) {
        recordTicketEvent("wake_recent_ticket_detail_fast_ready_after_launch", "reason=$reason")
        return result
      }
      delay(TICKET_WAKE_POST_LAUNCH_FAST_READY_POLL_MILLIS)
    }
    recordTicketEvent(
      "wake_recent_ticket_detail_fast_ready_after_launch_missed",
      "reason=$reason remaining_ms=${remainingWakeBudgetMillis(wakeStartedAtMillis, budgetMillis)}"
    )
    return null
  }

  private fun currentStateInvalidatesRecentTicketDetailFastWake(
    state: TicketViviRecoveryState
  ): Boolean {
    return state != TicketViviRecoveryState.TICKET_DETAIL &&
      state != TicketViviRecoveryState.UNKNOWN_VIVI &&
      state != TicketViviRecoveryState.BLANK
  }

  private fun remainingWakeBudgetMillis(
    wakeStartedAtMillis: Long,
    budgetMillis: Long = TICKET_WAKE_BUDGET_MILLIS
  ): Long {
    val elapsedMillis = SystemClock.elapsedRealtime() - wakeStartedAtMillis
    return (budgetMillis - elapsedMillis).coerceAtLeast(0L)
  }

  private fun remainingFastPublicOpenBudgetMillis(wakeStartedAtMillis: Long): Long {
    return remainingWakeBudgetMillis(wakeStartedAtMillis, TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS)
  }

  private fun recentTicketDetailMemoryAvailableForFastWake(): Boolean {
    return viviStateMemory.recentTicketDetailWithin(TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS) != null
  }

  private suspend fun viviFocusedForFastPublicOpen(reason: String): Boolean {
    if (!ticketScreenInteractive()) {
      recordTicketEvent("fast_public_open_vivi_focus_missed", "reason=$reason interactive=false")
      return false
    }
    val focused = focusedWindowSnapshot()
    val focusedVivi = focused?.contains(TicketScreenConfig.VIVI_PACKAGE) == true
    if (!focusedVivi) {
      recordTicketEvent("fast_public_open_vivi_focus_missed", "reason=$reason focused=${focused.orEmpty().take(120)}")
    }
    return focusedVivi
  }

  private fun wakeRootDumpTimeoutMillis(
    rootUnavailableAttempts: Int,
    remainingMillis: Long
  ): Long {
    val requestedMillis = if (rootUnavailableAttempts > 0) {
      TICKET_WAKE_FAST_ROOT_DUMP_TIMEOUT_MILLIS
    } else {
      TICKET_WAKE_FAST_POST_LAUNCH_TIMEOUT_MILLIS
    }
    return minOf(requestedMillis, remainingMillis)
  }

  private fun ticketWakeInProgress(nowMillis: Long = SystemClock.elapsedRealtime()): Boolean {
    val wakeStartedAt = lastWakeStartedAtMillis
    return wakeStartedAt > 0L &&
      lastWakeSucceeded == null &&
      nowMillis - wakeStartedAt in 0..(TICKET_WAKE_RECOVERY_BUDGET_MILLIS + TICKET_WAKE_GUARD_GRACE_MILLIS)
  }

  private suspend fun dumpViviHierarchyForWake(timeoutMillis: Long): RootResult {
    return wakeRootExecutor.runScript(
      TicketUiautomatorDump.command(
        path = "/sdcard/pixel-ticket-window.xml",
        timeoutMillis = timeoutMillis
      ),
      timeoutMillis.milliseconds
    )
  }

  private suspend fun observeRootViviStateForWake(
    reason: String,
    timeoutMillis: Long
  ): RootViviObservation {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val dump = dumpViviHierarchyForWake(timeoutMillis = timeoutMillis)
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    val hierarchy = dump.stdout.takeIf { dump.ok && it.isNotBlank() }
    if (hierarchy.isNullOrBlank()) {
      val error = dump.stderr.takeLast(160).ifBlank { "hierarchy_unavailable" }
      viviStateMemory.record(
        state = TicketViviRecoveryState.UNKNOWN_VIVI,
        ticketId = null,
        source = "root_empty",
        reason = reason
      )
      recordRootReadiness("$reason:UNKNOWN_VIVI", durationMillis)
      return RootViviObservation(TicketViviRecoveryState.UNKNOWN_VIVI, null, durationMillis, error)
    }
    val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    viviStateMemory.record(
      state = state,
      ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(hierarchy),
      source = "root",
      reason = reason
    )
    recordRootReadiness("$reason:${state.name}", durationMillis)
    return RootViviObservation(state, hierarchy, durationMillis)
  }

  private suspend fun observeTicketDetailForWakeWithRoot(
    reason: String,
    wakeStartedAtMillis: Long,
    budgetMillis: Long = TICKET_WAKE_BUDGET_MILLIS,
    maxRecoveryActions: Int = TICKET_WAKE_RECOVERY_MAX_ACTIONS
  ): TicketAutopilotResult {
    var lastState = TicketViviRecoveryState.UNKNOWN_VIVI
    var lastStep = "wake_root_unavailable"
    var attemptedGeneratedWakeHeal = false
    var attemptedPopupWakeReturn = false
    var attemptedWakeRelaunch = false
    var wakeRecoveryActions = 0
    var rootUnavailableAttempts = 0
    while (true) {
      val activeBudgetMillis = if (wakeRecoveryActions > 0) {
        maxOf(budgetMillis, TICKET_WAKE_RECOVERY_BUDGET_MILLIS)
      } else {
        budgetMillis
      }
      val remainingMillis = remainingWakeBudgetMillis(wakeStartedAtMillis, activeBudgetMillis)
      if (remainingMillis <= 0L) {
        return TicketAutopilotResult(false, lastState, "wake_budget_exhausted:$lastStep")
      }
      val timeoutMillis = wakeRootDumpTimeoutMillis(rootUnavailableAttempts, remainingMillis)
      val observation = observeRootViviStateForWake("wake_root:$reason", timeoutMillis = timeoutMillis)
      val state = observation.state
      lastState = state
      if (observation.hierarchy.isNullOrBlank()) {
        rootUnavailableAttempts += 1
        lastStep = "wake_root_unavailable"
      } else {
        rootUnavailableAttempts = 0
        lastStep = "wake_root_${state.name.lowercase()}"
        if (state != TicketViviRecoveryState.BLANK && state != TicketViviRecoveryState.OUTSIDE_VIVI) {
          recordTicketWakePhase("vivi_foreground", wakeStartedAtMillis)
        }
        if (state == TicketViviRecoveryState.TICKET_DETAIL) {
          recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
          return TicketAutopilotResult(true, state, "wake_root_ticket_detail")
        }
        if (
          state == TicketViviRecoveryState.LOGIN_REQUIRED &&
          wakeRecoveryActions < maxRecoveryActions &&
          loginViviIfNeeded(observation.hierarchy.orEmpty(), "wake_root:$reason")
        ) {
          wakeRecoveryActions += 1
          lastStep = "wake_root_login_submitted"
          val recoveryBudgetMillis = maxOf(budgetMillis, TICKET_WAKE_RECOVERY_BUDGET_MILLIS)
          delay(minOf(TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS, remainingWakeBudgetMillis(wakeStartedAtMillis, recoveryBudgetMillis)).coerceAtLeast(0L))
          continue
        }
        if (state == TicketViviRecoveryState.CONTROL_CODE_POPUP && !attemptedPopupWakeReturn) {
          attemptedPopupWakeReturn = true
          rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_POPUP)
          val returnedRaw = returnControlCodeSurfaceToRawTicket(
            generatedHierarchy = "",
            reason = "wake_stale_control_code_popup:$reason",
            phases = mutableMapOf(),
            requestStartedAtMillis = wakeStartedAtMillis
          )
          if (returnedRaw) {
            lastStep = "wake_root_popup_returned_raw"
            recordTicketEvent("wake_root_popup_returned_raw", reason)
            recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
            return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, lastStep)
          }
        }
        if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT && !attemptedGeneratedWakeHeal) {
          attemptedGeneratedWakeHeal = true
          rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
          val healed = healGeneratedControlCodeResultForRequest(
            generatedHierarchy = observation.hierarchy,
            reason = "wake_stale_generated_result:$reason",
            phases = mutableMapOf(),
            requestStartedAtMillis = wakeStartedAtMillis,
            freshFrameRequired = false
          )
          if (healed) {
            lastStep = "wake_root_generated_healed"
            recordTicketEvent("wake_root_generated_healed", reason)
            recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
            return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, lastStep)
          }
        }
        if (
          wakeRecoveryActions < maxRecoveryActions &&
          attemptWakeRecoveryActionForRootWake(state, observation.hierarchy, reason)
        ) {
          wakeRecoveryActions += 1
          lastStep = "wake_root_recovery_action_${state.name.lowercase()}"
          val recoveryBudgetMillis = maxOf(budgetMillis, TICKET_WAKE_RECOVERY_BUDGET_MILLIS)
          delay(minOf(TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS, remainingWakeBudgetMillis(wakeStartedAtMillis, recoveryBudgetMillis)).coerceAtLeast(0L))
          continue
        }
      }
      if (
        !attemptedWakeRelaunch &&
        wakeRecoveryActions < maxRecoveryActions &&
        attemptWakeRelaunchForRootWake(state, reason)
      ) {
        attemptedWakeRelaunch = true
        wakeRecoveryActions += 1
        lastStep = "wake_root_relaunch_${state.name.lowercase()}"
        val recoveryBudgetMillis = maxOf(budgetMillis, TICKET_WAKE_RECOVERY_BUDGET_MILLIS)
        delay(minOf(TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS, remainingWakeBudgetMillis(wakeStartedAtMillis, recoveryBudgetMillis)).coerceAtLeast(0L))
        continue
      }
      delay(minOf(TICKET_WAKE_FAST_POLL_MILLIS, remainingWakeBudgetMillis(wakeStartedAtMillis, activeBudgetMillis)).coerceAtLeast(0L))
    }
  }

  private suspend fun attemptWakeRecoveryActionForRootWake(
    state: TicketViviRecoveryState,
    hierarchy: String?,
    reason: String
  ): Boolean {
    if (hierarchy.isNullOrBlank()) {
      return false
    }
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(hierarchy) ?: return false
    if (state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD || action.reason.contains("ticket_card")) {
      recordTicketEvent(
        "ticket_card_selection_decision",
        "${TicketViviPageEnforcer.ticketCardSelectionSummaryForHierarchy(hierarchy)} action=${action.reason}"
      )
    }
    val input = if (action.x >= 0 && action.y >= 0) {
      runFastNonTouchInput("input tap ${action.x} ${action.y}", "wake_recovery_action:${action.reason}")
    } else {
      runFastNonTouchInput("input keyevent KEYCODE_BACK", "wake_recovery_action:${action.reason}")
    }
    recordTicketEvent(
      "wake_recovery_action",
      "state=${state.name} action=${action.reason} ok=${input.ok} duration_ms=${input.durationMs} reason=$reason"
    )
    return input.ok
  }

  private suspend fun attemptWakeRelaunchForRootWake(
    state: TicketViviRecoveryState,
    reason: String
  ): Boolean {
    if (
      state != TicketViviRecoveryState.BLANK &&
      state != TicketViviRecoveryState.OUTSIDE_VIVI &&
      state != TicketViviRecoveryState.UNKNOWN_VIVI
    ) {
      return false
    }
    recordTicketEvent("wake_recovery_relaunch", "state=${state.name} reason=$reason")
    launchViviForWake("wake_recovery_${state.name.lowercase()}:$reason")
    return true
  }

  private fun markWakeReadyIfNeeded(wakeStartedAtMillis: Long, result: TicketAutopilotResult) {
    if (
      result.state != TicketViviRecoveryState.BLANK &&
      result.state != TicketViviRecoveryState.OUTSIDE_VIVI &&
      result.state != TicketViviRecoveryState.UNKNOWN_VIVI
    ) {
      recordTicketWakePhase("vivi_foreground", wakeStartedAtMillis)
    }
    if (result.success && result.state == TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
    }
  }

  private suspend fun observeTicketDetailForFastPublicOpenVisibleProof(
    reason: String,
    wakeStartedAtMillis: Long
  ): TicketAutopilotResult? {
    val observation = observeFastViviState("fast_public_open:$reason") ?: return null
    val state = observation.state
    return when (state) {
      TicketViviRecoveryState.TICKET_DETAIL -> {
        recordTicketEvent(
          "fast_public_open_visible_proof",
          "reason=$reason state=${state.name} duration_ms=${observation.durationMillis}"
        )
        TicketAutopilotResult(true, state, "fast_open_visible_ticket_detail")
      }
      TicketViviRecoveryState.UNKNOWN_VIVI,
      TicketViviRecoveryState.BLANK,
      TicketViviRecoveryState.OUTSIDE_VIVI -> {
        recordTicketEvent(
          "fast_public_open_visible_proof_inconclusive",
          "reason=$reason state=${state.name} duration_ms=${observation.durationMillis}"
        )
        null
      }
      else -> {
        val step = "fast_open_visible_${state.name.lowercase()}"
        recordTicketEvent(
          "fast_public_open_visible_proof_failed",
          "reason=$reason state=${state.name} step=$step duration_ms=${observation.durationMillis}"
        )
        TicketAutopilotResult(false, state, step)
      }
    }
  }

  private suspend fun observeTicketDetailForFastPublicOpenRootProof(
    reason: String,
    wakeStartedAtMillis: Long
  ): TicketAutopilotResult {
    val remainingMillis = remainingFastPublicOpenBudgetMillis(wakeStartedAtMillis)
    if (remainingMillis < TICKET_FAST_PUBLIC_OPEN_MIN_ROOT_PROOF_TIMEOUT_MILLIS) {
      recordTicketEvent(
        "fast_public_open_root_proof_skipped",
        "reason=$reason remaining_ms=$remainingMillis"
      )
      return TicketAutopilotResult(false, TicketViviRecoveryState.UNKNOWN_VIVI, "fast_public_open_budget_exhausted")
    }
    val timeoutMillis = minOf(TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS, remainingMillis)
    recordTicketEvent("fast_public_open_root_proof", "reason=$reason timeout_ms=$timeoutMillis")
    val observation = observeRootViviStateForWake("fast_open_root:$reason", timeoutMillis = timeoutMillis)
    val state = observation.state
    return if (state == TicketViviRecoveryState.TICKET_DETAIL && !observation.hierarchy.isNullOrBlank()) {
      TicketAutopilotResult(true, state, "fast_open_root_ticket_detail")
    } else {
      val visualFallback = observeTicketDetailForFastPublicOpenRootH264VisualFallback(
        reason = reason,
        wakeStartedAtMillis = wakeStartedAtMillis,
        hierarchyObservation = observation
      )
      if (visualFallback != null) {
        return visualFallback
      }
      val step = if (observation.hierarchy.isNullOrBlank()) {
        "fast_open_root_unavailable"
      } else {
        "fast_open_root_${state.name.lowercase()}"
      }
      recordTicketEvent("fast_public_open_root_proof_failed", "reason=$reason state=${state.name} step=$step")
      TicketAutopilotResult(false, state, step)
    }
  }

  /**
   * A ViVi ticket can be visibly rendered in its SurfaceView while Android exposes no usable
   * UiAutomator tree. During the bounded public-open path only, use the already-prewarmed rooted
   * H.264 helper as a semantic fallback after that empty/unknown tree has been observed.
   *
   * This accepts two distinct fresh `raw_ticket` classifications and fails closed for a control
   * popup or generated result. It never taps, types, enables accessibility, or exports pixels.
   */
  private suspend fun observeTicketDetailForFastPublicOpenRootH264VisualFallback(
    reason: String,
    wakeStartedAtMillis: Long,
    hierarchyObservation: RootViviObservation
  ): TicketAutopilotResult? {
    if (
      hierarchyObservation.state != TicketViviRecoveryState.UNKNOWN_VIVI &&
      hierarchyObservation.state != TicketViviRecoveryState.BLANK
    ) {
      return null
    }
    if (!viviFocusedForFastPublicOpen("root_h264_visual:$reason")) {
      recordTicketEvent(
        "root_hardware_h264_visual_fallback_skipped",
        "reason=$reason focused_vivi=false state=${hierarchyObservation.state.name}"
      )
      return null
    }
    val deadlineMillis = wakeStartedAtMillis + TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS
    if (deadlineMillis - SystemClock.elapsedRealtime() < TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_MIN_REMAINING_MILLIS) {
      recordTicketEvent(
        "root_hardware_h264_visual_fallback_skipped",
        "reason=$reason remaining_ms=${(deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)}"
      )
      return null
    }
    val proof = TicketControlCodeCleanupVisualProof(TICKET_FAST_PUBLIC_OPEN_VISUAL_RAW_TICKET_PROOF_COUNT)
    var lastResult = TicketControlCodeVisualClassifier.UNKNOWN
    var sampleIndex = 0
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      val capture = rootHardwareH264CaptureEngine.snapshot()
      if (!capture.active) {
        delay(TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS)
        continue
      }
      val startedAtMillis = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestTicketDetailVisualProbe(
        "fast_public_open_${sampleIndex + 1}"
      )
      if (probeId == null) {
        delay(TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS)
        continue
      }
      sampleIndex += 1
      val remainingMillis = (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val visualProbe = waitForFreshControlCodeVisualProbe(
        visualProbeStartedAtMillis = startedAtMillis,
        expectedProbeId = probeId,
        timeoutMillis = minOf(CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS, remainingMillis)
      )
      val result = visualProbe?.result ?: TicketControlCodeVisualClassifier.UNKNOWN
      lastResult = result
      when (result) {
        TicketControlCodeVisualClassifier.RAW_TICKET -> {
          if (proof.observe(result)) {
            viviStateMemory.record(
              state = TicketViviRecoveryState.TICKET_DETAIL,
              ticketId = null,
              source = "root_h264_visual",
              reason = "fast_public_open:$reason"
            )
            recordTicketEvent(
              "root_hardware_h264_visual_fallback_ready",
              "reason=$reason samples=${proof.consecutiveRawTicketSamples}"
            )
            return TicketAutopilotResult(
              success = true,
              state = TicketViviRecoveryState.TICKET_DETAIL,
              step = "fast_open_root_h264_visual_ticket_detail"
            )
          }
        }
        TicketControlCodeVisualClassifier.CONTROL_POPUP -> {
          proof.observe(result)
          recordTicketEvent("root_hardware_h264_visual_fallback_rejected", "reason=$reason state=control_popup")
          return TicketAutopilotResult(false, TicketViviRecoveryState.CONTROL_CODE_POPUP, "fast_open_root_h264_visual_control_popup")
        }
        TicketControlCodeVisualClassifier.GENERATED -> {
          proof.observe(result)
          recordTicketEvent("root_hardware_h264_visual_fallback_rejected", "reason=$reason state=generated")
          return TicketAutopilotResult(false, TicketViviRecoveryState.CONTROL_CODE_RESULT, "fast_open_root_h264_visual_generated")
        }
        else -> proof.observe(result)
      }
      val sampleGapMillis = TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_SAMPLE_GAP_MILLIS -
        (SystemClock.elapsedRealtime() - startedAtMillis)
      if (sampleGapMillis > 0L && SystemClock.elapsedRealtime() < deadlineMillis) {
        delay(minOf(sampleGapMillis, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
      }
    }
    recordTicketEvent(
      "root_hardware_h264_visual_fallback_inconclusive",
      "reason=$reason samples=$sampleIndex last=$lastResult"
    )
    return null
  }

  private suspend fun prepareViviForRootHardwareH264FastOpen(
    reason: String,
    wakeStartedAtMillis: Long
  ): TicketAutopilotResult {
    var result = fastWakeReadyFromRecentTicketDetail(reason, wakeStartedAtMillis)
    val launchedViviForWake = result == null && !viviFocusedForFastPublicOpen(reason)
    if (launchedViviForWake) {
      val launchBudgetMillis = remainingFastPublicOpenBudgetMillis(wakeStartedAtMillis)
      if (launchBudgetMillis > 0L) {
        val launchTimeoutMillis = minOf(TICKET_WAKE_LAUNCH_TIMEOUT_MILLIS, launchBudgetMillis)
        recordTicketEvent("fast_public_open_launch_once", "reason=$reason timeout_ms=$launchTimeoutMillis")
        launchViviForWake(reason, timeoutMillis = launchTimeoutMillis)
        if (recentTicketDetailMemoryAvailableForFastWake()) {
          result = fastWakeReadyFromRecentTicketDetailAfterLaunch(
            reason,
            wakeStartedAtMillis,
            budgetMillis = TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS
          )
        } else {
          recordTicketEvent("fast_public_open_post_launch_memory_skipped", reason)
        }
      } else {
        recordTicketEvent("fast_public_open_launch_skipped", "reason=$reason remaining_ms=$launchBudgetMillis")
      }
    }
    result = result ?: observeTicketDetailForFastPublicOpenVisibleProof(reason, wakeStartedAtMillis)
    val prepareResult = result ?: observeTicketDetailForFastPublicOpenRootProof(reason, wakeStartedAtMillis)
    markWakeReadyIfNeeded(wakeStartedAtMillis, prepareResult)
    recordTicketEvent(
      "root_hardware_h264_fast_open_prepare",
      "${prepareResult.state}:${prepareResult.step}:success=${prepareResult.success}"
    )
    if (prepareResult.success) {
      recordStartupTracePhase("ticket_ready_proved", "state=${prepareResult.state} step=${prepareResult.step}", once = true)
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_hardware_h264_vivi_ready_fast_$reason")
    } else {
      recordStartupTracePhase("ticket_ready_failed", "state=${prepareResult.state} step=${prepareResult.step}", once = true)
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "root_hardware_h264_fast_open_${prepareResult.state.name.lowercase()}")
    }
    finishTicketWake(
      wakeStartedAtMillis,
      succeeded = prepareResult.success,
      reason = if (prepareResult.success) "ticket_ready" else prepareResult.step
    )
    return prepareResult
  }

  private fun scheduleRootHardwareH264CaptureStart(reason: String, suppressBlackout: Boolean) {
    serviceScope.launch {
      controlCodePhoneMutationLane.withOwnership {
        prepareRootHardwareH264CaptureWithPhoneMutationOwnership(reason, suppressBlackout)
      }
    }
  }

  private suspend fun prepareRootHardwareH264CaptureWithPhoneMutationOwnership(
    reason: String,
    suppressBlackout: Boolean
  ): Boolean {
    val modeName = "root_hardware_h264"
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      recordTicketEvent("${modeName}_prepare_ignored", "session_inactive_before_prepare:$reason")
      return false
    }
    if (hardwareCaptureVerified && hardwareFrameBroadcastAllowed) {
      recordTicketEvent("${modeName}_prepare_reused", reason)
      return true
    }
    if (suppressBlackout) {
      suppressBlackoutOverlayForRemote()
    }
    updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_prepare_$reason")
    val wakeStartedAtMillis = beginTicketWake(reason)
    prewarmRootHardwareH264CaptureIfPossible("session_start_prewarm:$reason")
    wakeTicketScreenForSessionStart(reason, wakeStartedAtMillis)
    val prepareResult = prepareViviForRootHardwareH264FastOpen(reason, wakeStartedAtMillis)
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      recordTicketEvent("${modeName}_prepare_ignored", "session_inactive:$reason")
      broadcastStatus()
      return false
    }
    if (!prepareResult.success) {
      rootHardwareH264CaptureEngine.stop("phone_not_ready:$reason")
      rootHardwareH264CaptureEngine.cleanupStaleProcesses()
      streamActive = false
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      resetFrameEpoch("phone_not_ready:$reason", active = false)
      lastMessage = "Phone not ready: root could not confirm the ViVi ticket screen"
      recordTicketEvent("phone_not_ready", prepareResult.step)
      recordStartupTracePhase("phone_not_ready", prepareResult.step, once = true, complete = true)
      broadcastStatus()
      return false
    }
    hardwareCaptureVerified = true
    hardwareFrameBroadcastAllowed = true
    recordTicketEvent("hardware_h264_wake_frames_allowed", reason)
    ensureEncoderIfPossible()
    requestKeyFrame("vivi_ready_encoder_start:$reason")
    val watermark = requestFreshTicketStateFrameWatermark("vivi_ready:$reason")
    sendTicketStateEvent(
      ticketState = TICKET_PIXEL_STATE_RAW_TICKET,
      reason = "session_start_raw_ticket_ready",
      eventStreamEpoch = watermark.first,
      eventFrameSequence = watermark.second,
      minFrameSequence = watermark.second
    )
    updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_waiting_first_visible_frame")
    lastMessage = "Waiting for the first visible hardware H.264 frame"
    broadcastStatus()
    return true
  }

  private suspend fun verifyRootHardwareSecureCapturePixelsVisible(reason: String): Boolean {
    val snapshot = rootHardwareH264CaptureEngine.snapshot()
    val recentFrame = snapshot.lastFrameAgoMillis?.let { it <= SECURE_CAPTURE_PROBE_TIMEOUT_MILLIS } == true
    val visible = snapshot.lastVisibilityCheckResult == "visible" ||
      (snapshot.active && recentFrame && snapshot.blankFrameFailures == 0L)
    recordTicketEvent(
      "secure_capture_probe",
      "visible=$visible source=hardware_h264_health reason=$reason health=${snapshot.lastVisibilityCheckResult}"
    )
    return visible
  }



  private fun scheduleTicketRecovery(
    reason: String,
    mode: TicketRecoveryMode = TicketRecoveryMode.ACTIVE_SOFT
  ) {
    if (streamActive) updateTicketSessionState(
      if (mode == TicketRecoveryMode.ACTIVE_SOFT) TICKET_SESSION_SOFT_RECOVERY else TICKET_SESSION_STARTING,
      "recovery_scheduled_$reason"
    )
    val recoveryJob = synchronized(ticketRecoveryLock) {
      ticketRecoveryJob?.cancel()
      val generation = ticketRecovery.generation + 1L
      ticketRecovery = TicketRecoveryRuntime(
        generation = generation, state = "running", reason = reason, mode = mode,
        result = "running", step = "scheduled", startedAtMillis = SystemClock.elapsedRealtime()
      )
      val job = serviceScope.launch(start = CoroutineStart.LAZY) {
        val runningJob = coroutineContext[Job]
        try {
          controlCodePhoneMutationLane.withOwnership {
            if (!ownsTicketRecovery(generation, runningJob)) return@withOwnership
            currentCoroutineContext().ensureActive()
            val startedAtMillis = SystemClock.elapsedRealtime()
            if (mode == TicketRecoveryMode.FRESH_RESET) {
              runFastNonTouchWakeScript(
                "am force-stop ${TicketScreenConfig.VIVI_PACKAGE}",
                "recovery_force_stop:$reason",
                NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds
              )
              if (!ownsTicketRecovery(generation, runningJob)) return@withOwnership
              launchViviForWake("recovery:$reason")
            }
            if (!ownsTicketRecovery(generation, runningJob)) return@withOwnership
            val result = observeTicketDetailForWakeWithRoot(
              reason = "recovery:$reason",
              wakeStartedAtMillis = startedAtMillis,
              budgetMillis = TICKET_WAKE_RECOVERY_BUDGET_MILLIS,
              maxRecoveryActions = TICKET_WAKE_RECOVERY_MAX_ACTIONS
            )
            synchronized(ticketRecoveryLock) {
              if (!ownsTicketRecovery(generation, runningJob)) return@withOwnership
              ticketRecovery = ticketRecovery.copy(
                state = if (result.success) "succeeded" else "failed",
                result = if (result.success) "succeeded" else "failed",
                step = result.step,
                completedAtMillis = SystemClock.elapsedRealtime()
              )
              onTicketRecoveryResult(reason, mode, result.success)
            }
          }
        } finally {
          synchronized(ticketRecoveryLock) {
            if (ticketRecoveryJob === runningJob) ticketRecoveryJob = null
          }
        }
      }
      ticketRecoveryJob = job
      recordTicketEvent("recovery_scheduled", "$reason mode=$mode generation=$generation")
      job
    }
    recoveryJob.start()
    broadcastStatus()
  }

  private fun ownsTicketRecovery(generation: Long, job: Job?): Boolean = synchronized(ticketRecoveryLock) {
    ticketRecovery.generation == generation && ticketRecoveryJob === job
  }

  private fun cancelTicketRecovery(step: String) = synchronized(ticketRecoveryLock) {
    ticketRecoveryJob?.cancel() ?: return@synchronized
    ticketRecoveryJob = null
    ticketRecovery = ticketRecovery.copy(
      generation = ticketRecovery.generation + 1L,
      state = "idle", result = "cancelled", step = step, completedAtMillis = SystemClock.elapsedRealtime()
    )
  }

  private fun onTicketRecoveryResult(reason: String, mode: TicketRecoveryMode, success: Boolean) {
    recordTicketEvent(
      if (success) "recovery_succeeded" else "recovery_failed",
      "$reason mode=$mode"
    )
    if (reason.startsWith("admin_force_latest_ticket_reselect")) {
      val reselectToken = synchronized(latestTicketReselectStateLock) {
        if (latestTicketReselectStatus == "pending" && latestTicketReselectReason == reason) {
          latestTicketReselectGeneration to latestTicketReselectCommandId
        } else {
          null
        }
      }
      if (reselectToken != null) {
        scheduleLatestTicketReselectSettle(
          successReason = if (success) {
            "latest_ticket_reselect_succeeded"
          } else {
            "latest_ticket_reselect_succeeded_after_recovery"
          },
          failureReason = if (success) {
            "latest_ticket_reselect_stream_unstable"
          } else {
            "latest_ticket_reselect_failed"
          },
          generation = reselectToken.first,
          commandId = reselectToken.second
        )
        recordTicketEvent(
          if (success) "latest_ticket_reselect_ticket_detail_ready" else "latest_ticket_reselect_waiting_for_recovery",
          "$reason mode=$mode"
        )
      }
    }
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
        recordTicketEvent("ticket_brightness_read_failed", safeRootFailure(result))
        null
      }
    }.getOrElse { error ->
      recordTicketEvent("ticket_brightness_read_failed", safeErrorDetail(error))
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
      } else {
        ticketBrightnessGuardFailures += 1
        ticketBrightnessGuardLastReason = reason
        ticketBrightnessGuardLastMessage = "Ticket brightness guard failed: ${result.stderr.ifBlank { result.stdout }.take(96)}"
        recordTicketEvent("ticket_brightness_safe_dim_failed", "reason=$reason ${safeRootFailure(result)}")
      }
    }.onFailure { error ->
      ticketBrightnessGuardFailures += 1
      ticketBrightnessGuardLastReason = reason
      ticketBrightnessGuardLastMessage = "Ticket brightness guard failed: ${error.message ?: error::class.java.simpleName}"
      recordTicketEvent("ticket_brightness_safe_dim_failed", "reason=$reason error=${safeErrorDetail(error)}")
    }
  }

  private fun refreshPhoneAutomation() {
    runCatching {
      startService(
        Intent(this, SupervisorService::class.java)
          .setAction(SupervisorService.ACTION_REFRESH_PHONE_AUTOMATION)
      )
    }.onFailure { error -> recordTicketEvent("ticket_phone_automation_refresh_failed", safeErrorDetail(error)) }
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
      recordTicketEvent("ticket_brightness_restored", "reason=$reason")
    } else {
      recordTicketEvent("ticket_brightness_restore_failed", "reason=$reason ${safeRootFailure(result)}")
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
    return false
  }

  private fun controlCodeGeometryTarget(size: TicketStreamSize, candidateZone: String): TicketTapTarget {
    return TicketTapTarget(
      x = (size.sourceWidth * ((VIVI_CONTROL_CODE_MIN_X_FRACTION + VIVI_CONTROL_CODE_MAX_X_FRACTION) / 2f)).roundToInt(),
      y = (size.sourceHeight * ((VIVI_CONTROL_CODE_MIN_Y_FRACTION + VIVI_CONTROL_CODE_MAX_Y_FRACTION) / 2f)).roundToInt(),
      reason = "control_code_button_snap_geometry",
      candidateZone = candidateZone
    )
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
    recordTicketEvent(
      "control_code_target",
      "accepted=$accepted reason=$reason raw=$rawX,$rawY final=${finalX ?: rawX},${finalY ?: rawY} zone=${candidateZone.orEmpty()} target=$snapTarget bounds=${detectedButtonBounds.orEmpty()}"
    )
  }

  private fun recordInputGateDecision(allowed: Boolean, reason: String) {
    inputGateReason = reason
  }

  private fun markControlCodeTransition(reason: String) {
    pauseForegroundGuardForControlCode(reason)
    controlCodeTransitionGraceUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_TRANSITION_GRACE_MILLIS
    updateTicketSessionState(TICKET_SESSION_CONTROL_TRANSITION, reason)
    recordTicketEvent("control_code_transition", reason)
    broadcastStatus()
  }

  private fun pauseForegroundGuardForControlCode(reason: String) {
    val job = foregroundGuardJob
    if (job?.isActive != true) {
      resetForegroundViolationConfirmation()
      return
    }
    job.cancel()
    foregroundGuardJob = null
    resetForegroundViolationConfirmation()
    recordTicketEvent("foreground_guard_paused_for_control_code", reason)
  }

  private fun controlSensitiveWindowActive(): Boolean {
    val now = SystemClock.elapsedRealtime()
    return controlCodeModeActive ||
      controlCodeRequestActive() ||
      now < controlCodeTransitionGraceUntilMillis ||
      ticketSessionState == TICKET_SESSION_CONTROL_EXIT
  }

  private fun controlCodeRequestActive(): Boolean {
    val status = lastControlCodeRequestStatus
    return status == "running" || status == "queued"
  }

  internal fun ticketSpacetimeControlCodeRequestActive(): Boolean {
    return controlCodeRequestActive() ||
      pendingControlCodeBrowserCaptureRequestId != null ||
      controlCodeModeActive ||
      ticketSessionState in setOf(
        TICKET_SESSION_CONTROL_ACTIVE,
        TICKET_SESSION_CONTROL_TRANSITION,
        TICKET_SESSION_CONTROL_EXIT
      )
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
    controlCodeModeEnteredAtMillis = SystemClock.elapsedRealtime()
    updateTicketSessionState(TICKET_SESSION_CONTROL_ACTIVE, reason)
    recordTicketEvent("control_code_entered", reason)
    broadcastStatus()
  }

  private fun resetControlCodeMode(
    reason: String,
    broadcast: Boolean = true
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
    recordTicketEvent("control_code_reset", reason)
    if (broadcast) {
      broadcastStatus()
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

  private fun scheduleControlExitSoftSettle(reason: String) {
    cancelForegroundGuard()
    postRemoteTapForegroundCheckJob?.cancel()
    cancelTicketRecovery("control_exit:$reason")
    if (streamActive) updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)
    resetControlCodeMode(reason, broadcast = false)
    scheduleControlExitCleanup(reason)
    recordTicketEvent("control_exit_cleanup_scheduled", reason)
    broadcastStatus()
  }

  private fun scheduleControlExitCleanup(reason: String) {
    controlExitCleanupJob?.cancel()
    controlExitCleanupJob = serviceScope.launch {
      controlCodePhoneMutationLane.withOwnership {
        runControlExitCleanup(reason)
      }
    }
  }

  private suspend fun runControlExitCleanup(reason: String): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val phases = linkedMapOf<String, Long>()
    if (withTimeoutOrNull(CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS) {
        returnControlCodeSurfaceToRawTicket("", reason, phases, startedAtMillis)
      } == true
    ) return true
    if (recoverTicketDetailForControlCodeRequest(phases, startedAtMillis, "control_exit:$reason")) {
      return completeControlExitCleanup(
        reason, controlCodeSurfaceMemoryState() ?: "UNKNOWN", "root_recovery",
        startedAtMillis, TicketViviRecoveryState.TICKET_DETAIL.name, true
      )
    }
    recordInputGateDecision(false, "control_exit_cleanup_failed")
    updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_exit_cleanup_failed")
    recordControlExitCleanup(
      reason, controlCodeSurfaceMemoryState() ?: "UNKNOWN", "none",
      startedAtMillis, "ticket_detail_unproved", false, false
    )
    broadcastStatus()
    return false
  }

  private suspend fun completeControlExitCleanup(
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
    val finalCloseAction = closeAction
    controlCodeModeActive = false
    controlCodeModeEnteredAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    clearControlCodePopupSurfaceCache()
    lastControlCodeSurfaceState = null
    lastControlCodeSurfaceSeenAtMillis = 0L
    lastControlExitDirtySurfaceState = null
    viviStateMemory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = null,
      source = "root",
      reason = "control_exit_cleanup:$reason"
    )
    recordInputGateDecision(allowed = true, reason = "control_exit_popup_closed")
    recordTicketEvent("control_exit_popup_closed", reason)
    if (streamActive) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "control_exit_popup_closed")
      startForegroundGuard()
    }
    recordTicketEvent("control_code_soft_check_ok", verificationResult.lowercase())
    val cleanupStartedAtMillis = startedAtMillis
    val freshFrameVerified = if (freshFrameRequested) {
      waitForFreshStreamFrameAfterCleanup(reason, cleanupStartedAtMillis)
    } else {
      true
    }
    if (!freshFrameVerified) {
      recordTicketEvent("post_cleanup_stream_stale", reason)
      restartActiveStreamEngine("post_cleanup_stale_$reason")
    } else {
      sendTicketStateEvent(
        ticketState = TICKET_PIXEL_STATE_RAW_TICKET,
        reason = "return_to_raw_complete",
        requestId = lastControlCodeRequestId.orEmpty(),
        eventStreamEpoch = streamEpoch,
        eventFrameSequence = frameSequence,
        minFrameSequence = frameSequence
      )
    }
    recordControlExitCleanup(
      reason,
      finalDetectedState,
      finalCloseAction,
      startedAtMillis,
      verificationResult,
      freshFrameVerified,
      freshFrameRequested,
      freshFrameVerified
    )
    broadcastStatus()
    return freshFrameVerified
  }

  private suspend fun waitForFreshStreamFrameAfterCleanup(reason: String, cleanupStartedAtMillis: Long): Boolean {
    if (!streamActive || videoClients.isEmpty()) {
      lastPostCleanupFreshFrameVerificationReason = "no_active_video_client:$reason"
      return true
    }
    val baselineFrameAtMillis = lastFrameSentAtMillis.coerceAtLeast(cleanupStartedAtMillis)
    requestKeyFrame("control_exit_cleanup")
    val deadlineMillis = SystemClock.elapsedRealtime() + POST_CLEANUP_FRESH_FRAME_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() <= deadlineMillis) {
      val frameAtMillis = lastFrameSentAtMillis
      if (frameAtMillis > baselineFrameAtMillis) {
        lastPostCleanupFreshFrameVerifiedAtMillis = frameAtMillis
        lastPostCleanupFreshFrameVerificationReason = reason
        recordTicketEvent("post_cleanup_fresh_frame_verified", "reason=$reason frame_age_ms=${ageMillis(frameAtMillis, SystemClock.elapsedRealtime()) ?: -1L}")
        return true
      }
      delay(POST_CLEANUP_FRESH_FRAME_POLL_MILLIS)
    }
    lastPostCleanupFreshFrameVerificationReason = "timeout:$reason"
    return false
  }

  private fun recordControlExitCleanup(
    reason: String,
    detectedState: String,
    closeAction: String,
    startedAtMillis: Long,
    verificationResult: String,
    succeeded: Boolean,
    freshFrameRequested: Boolean,
    freshFrameVerified: Boolean = false
  ) {
    recordTicketEvent(
      "control_exit_cleanup",
      "reason=$reason state=$detectedState action=$closeAction verify=$verificationResult success=$succeeded fresh_frame=$freshFrameRequested fresh_frame_verified=$freshFrameVerified duration_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)}"
    )
  }

  private suspend fun claimControlCodeAutomationForRequest() {
    controlCodeAutomationClaims.incrementAndGet()
  }

  private fun releaseControlCodeAutomationForRequest() {
    controlCodeAutomationClaims.updateAndGet { (it - 1L).coerceAtLeast(0L) }
  }

  private fun recordControlCodeCommandEnvelope(owner: String, app: String, flow: String) {
    lastControlCodeCommandOwner = owner.trim().takeIf { it.isNotBlank() }
    lastControlCodeCommandApp = app.trim().takeIf { it.isNotBlank() }
    lastControlCodeCommandFlow = flow.trim().takeIf { it.isNotBlank() }
  }

  private fun controlCodeCommandEnvelopeMatches(
    owner: String,
    app: String,
    flow: String,
    expectedOwner: String,
    expectedApp: String,
    expectedFlow: String
  ): Boolean {
    val cleanOwner = owner.trim()
    val cleanApp = app.trim()
    val cleanFlow = flow.trim()
    return cleanOwner == expectedOwner && cleanApp == expectedApp && cleanFlow == expectedFlow
  }

  private fun recordRejectedControlCodeCommand(
    requestId: String,
    owner: String,
    app: String,
    flow: String,
    reason: String
  ) {
    recordControlCodeCommandEnvelope(owner, app, flow)
    val cleanReason = reason.trim().ifBlank { "wrong_command_owner" }
    val nowMillis = SystemClock.elapsedRealtime()
    if (requestId.isNotBlank()) {
      lastControlCodeRequestId = requestId.trim()
      lastControlCodeRequestStatus = "failed"
      lastControlCodeRequestReason = cleanReason
      lastControlCodeRequestDurationMillis = 0L
      lastControlCodeRequestPhases = emptyMap()
      lastControlCodeRequestCompletedAtMillis = nowMillis
    }
    recordTicketEvent(
      "control_code_command_rejected",
      "request=${requestId.trim().ifBlank { "missing" }} owner=${owner.trim().ifBlank { "missing" }} app=${app.trim().ifBlank { "missing" }} flow=${flow.trim().ifBlank { "missing" }} reason=$cleanReason"
    )
    broadcastStatus()
  }

  private fun markControlCodeFastReady(reason: String) {
    val cleanReason = reason.trim().ifBlank { "ticket_detail" }
    val watermark = requestFreshTicketStateFrameWatermark("control_code_fast_ready:$cleanReason")
    val nowMillis = SystemClock.elapsedRealtime()
    val revision = "phone:$nowMillis:${watermark.first}:${watermark.second}"
    lastControlCodeFastReadyRevision = revision
    controlCodeTransitionGraceUntilMillis = 0L
    if (ticketSessionState != TICKET_SESSION_LIVE) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_fast_ready")
    }
    sendControlCodeFastState(
      status = "fast_ready",
      reason = cleanReason,
      revision = revision,
      streamEpochValue = watermark.first,
      frameSequenceValue = watermark.second,
      rawTicketConfirmed = true,
      cleanupClear = true,
      streamLive = streamActive && hardwareCaptureVerified
    )
  }

  private fun markControlCodeFastNotReady(status: String, reason: String) {
    val cleanStatus = when (status.trim()) {
      "cleanup" -> "cleanup"
      "blocked" -> "blocked"
      else -> "warming"
    }
    val nowMillis = SystemClock.elapsedRealtime()
    val previousRevision = lastControlCodeFastReadyRevision
    lastControlCodeFastReadyRevision = ""
    sendControlCodeFastState(
      status = cleanStatus,
      reason = reason.trim().ifBlank { cleanStatus },
      revision = previousRevision.ifBlank { "phone:$nowMillis:$cleanStatus" },
      streamEpochValue = streamEpoch,
      frameSequenceValue = frameSequence,
      rawTicketConfirmed = false,
      cleanupClear = false,
      streamLive = streamActive && hardwareCaptureVerified
    )
  }

  private fun sendControlCodeFastState(
    status: String,
    reason: String,
    revision: String,
    streamEpochValue: Long,
    frameSequenceValue: Long,
    rawTicketConfirmed: Boolean,
    cleanupClear: Boolean,
    streamLive: Boolean
  ) {
    val cleanStatus = when (status.trim()) {
      "fast_ready" -> "fast_ready"
      "cleanup" -> "cleanup"
      "blocked" -> "blocked"
      else -> "warming"
    }
    val message = buildJsonObject {
      put("type", "control_code_fast_state")
      put("status", cleanStatus)
      put("revision", revision.trim())
      put("reason", reason.trim().ifBlank { cleanStatus })
      put("streamEpoch", streamEpochValue)
      put("frameSequence", frameSequenceValue)
      put("rawTicketConfirmed", rawTicketConfirmed)
      put("cleanupClear", cleanupClear)
      put("streamLive", streamLive)
      put("phoneUptimeMillis", SystemClock.elapsedRealtime())
    }.toString()
    enqueueTicketSpacetimePhoneMessage(message)
    recordTicketEvent(
      "control_code_fast_state",
      "status=$cleanStatus reason=${reason.trim().ifBlank { cleanStatus }} revision=${revision.trim().ifBlank { "missing" }} stream_live=$streamLive"
    )
    broadcastStatus()
  }

  private fun beginControlCodeBrowserCaptureWait(requestId: String) {
    synchronized(controlCodeBrowserCaptureLock) {
      pendingControlCodeBrowserCaptureRequestId = requestId.takeIf { it.isNotBlank() }
      pendingControlCodeBrowserCaptureAck = null
    }
    lastControlCodeBrowserCaptureReason = "waiting"
    lastControlCodeBrowserCaptureCompletedAtMillis = 0L
    recordTicketEvent("control_code_browser_capture_wait_started", "request=$requestId")
    broadcastStatus()
  }

  private fun clearControlCodeBrowserCaptureWait(requestId: String) {
    synchronized(controlCodeBrowserCaptureLock) {
      if (pendingControlCodeBrowserCaptureRequestId == requestId) {
        pendingControlCodeBrowserCaptureRequestId = null
        pendingControlCodeBrowserCaptureAck = null
      }
    }
  }

  private fun handleControlCodeBrowserCapture(
    requestId: String,
    ok: Boolean,
    reason: String,
    frameEpoch: Long,
    frameSequence: Long
  ) {
    val cleanRequestId = requestId.trim()
    if (cleanRequestId.isBlank()) {
      return
    }
    val ack = ControlCodeBrowserCaptureAck(
      requestId = cleanRequestId,
      ok = ok,
      reason = reason.trim().ifBlank { if (ok) "browser_capture_confirmed" else "browser_capture_failed" },
      frameEpoch = frameEpoch,
      frameSequence = frameSequence,
      receivedAtMillis = SystemClock.elapsedRealtime()
    )
    var accepted = false
    synchronized(controlCodeBrowserCaptureLock) {
      if (pendingControlCodeBrowserCaptureRequestId == cleanRequestId) {
        pendingControlCodeBrowserCaptureAck = ack
        accepted = true
      }
    }
    lastControlCodeBrowserCaptureReason = ack.reason
    lastControlCodeBrowserCaptureCompletedAtMillis = ack.receivedAtMillis
    recordTicketEvent(
      if (accepted) "control_code_browser_capture_received" else "control_code_browser_capture_ignored",
      "request=$cleanRequestId ok=$ok reason=${ack.reason} epoch=$frameEpoch sequence=$frameSequence"
    )
    broadcastStatus()
  }

  private suspend fun waitForControlCodeBrowserCapture(
    requestId: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): ControlCodeBrowserCaptureAck {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadlineMillis = startedAtMillis + CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS
    markControlCodeRequestPhase(phases, "browser_capture_wait_started", requestStartedAtMillis)
    while (true) {
      val nowMillis = SystemClock.elapsedRealtime()
      val ack = synchronized(controlCodeBrowserCaptureLock) {
        pendingControlCodeBrowserCaptureAck?.takeIf { it.requestId == requestId }
      }
      if (ack != null) {
        rootHardwareH264CaptureEngine.stopControlCodeRequestBurst("browser_capture_acknowledged")
        markControlCodeRequestPhase(phases, "capture_burst_stopped", requestStartedAtMillis)
        phases["capture_burst_duration"] = (nowMillis - requestStartedAtMillis).coerceAtLeast(0L)
        phases["browser_capture_ack_wait"] = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        markControlCodeRequestPhase(phases, "browser_capture_ack_received", requestStartedAtMillis)
        recordTicketEvent(
          "control_code_browser_capture_accepted",
          "request=$requestId ok=${ack.ok} reason=${ack.reason} epoch=${ack.frameEpoch} sequence=${ack.frameSequence}"
        )
        return ack
      }
      if (nowMillis >= deadlineMillis) {
        val reason = "control_code_browser_capture_ack_timeout"
        rootHardwareH264CaptureEngine.stopControlCodeRequestBurst(reason)
        markControlCodeRequestPhase(phases, "capture_burst_stopped", requestStartedAtMillis)
        phases["capture_burst_duration"] = (nowMillis - requestStartedAtMillis).coerceAtLeast(0L)
        phases["browser_capture_ack_wait"] = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        markControlCodeRequestPhase(phases, "browser_capture_ack_timeout", requestStartedAtMillis)
        lastControlCodeBrowserCaptureReason = reason
        lastControlCodeBrowserCaptureCompletedAtMillis = nowMillis
        recordTicketEvent(reason, "request=$requestId")
        broadcastStatus()
        return ControlCodeBrowserCaptureAck(
          requestId = requestId,
          ok = false,
          reason = reason,
          frameEpoch = 0L,
          frameSequence = 0L,
          receivedAtMillis = nowMillis
        )
      }
      delay(minOf(CONTROL_CODE_BROWSER_CAPTURE_ACK_POLL_MILLIS, (deadlineMillis - nowMillis).coerceAtLeast(1L)))
    }
  }

  private suspend fun ensureTicketSessionForControlCodeRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT) {
      recordTicketEvent("control_code_request_control_exit_cleanup", ticketSessionState)
      markControlCodeRequestPhase(phases, "request_control_exit_cleanup_started", requestStartedAtMillis)
      val cleaned = runControlExitCleanup("control_code_request_preflight_control_exit")
      markControlCodeRequestPhase(phases, "request_control_exit_cleanup_finished", requestStartedAtMillis)
      if (!cleaned || ticketSessionState == TICKET_SESSION_CONTROL_EXIT || ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION) {
        val recovered = recoverTicketDetailForControlCodeRequest(
          phases = phases,
          requestStartedAtMillis = requestStartedAtMillis,
          reason = "control_code_request_recover_control_exit",
          launchVivi = true
        )
        if (!recovered || ticketSessionState == TICKET_SESSION_CONTROL_EXIT || ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION) {
          val reason = "control_code_request_control_exit_unavailable"
          recordInputGateDecision(allowed = false, reason = reason)
          recordTicketEvent(reason, "cleaned=$cleaned recovered=$recovered state=$ticketSessionState")
          return false
        }
      }
    }

    if (!ticketSessionOpen() || rootCaptureNeedsOwnedPreparation()) {
      val response = startTicketSession(
        prepareCaptureWithCurrentPhoneMutationOwnership = true
      )
      markControlCodeRequestPhase(phases, "request_session_started", requestStartedAtMillis)
      if (!response.ok || !ticketSessionOpen()) {
        val reason = "control_code_request_session_unavailable:${response.state}"
        recordInputGateDecision(allowed = false, reason = reason)
        recordTicketEvent("control_code_request_session_unavailable", "state=${response.state} message=${response.message.take(80)}")
        return false
      }
      recordTicketEvent("control_code_request_session_ready", response.state)
    }

    val foregroundViolation = foregroundViolationReason(allowStartupSystemUi = false)
    cacheForegroundViolation(foregroundViolation)
    if (foregroundViolation != null) {
      val recovered = recoverTicketDetailForControlCodeRequest(
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        reason = "control_code_request_foreground_$foregroundViolation",
        launchVivi = true
      )
      if (!recovered) {
        val reason = "control_code_request_foreground_unavailable:$foregroundViolation"
        recordInputGateDecision(allowed = false, reason = reason)
        recordTicketEvent("control_code_request_foreground_unavailable", foregroundViolation)
        return false
      }
    }

    return true
  }

  private fun rootCaptureNeedsOwnedPreparation(): Boolean {
    return streamActive &&
      activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
      (!hardwareCaptureVerified || !hardwareFrameBroadcastAllowed)
  }

  private suspend fun recoverTicketDetailForControlCodeRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    reason: String,
    launchVivi: Boolean = false
  ): Boolean {
    val recoveryStartedAtMillis = SystemClock.elapsedRealtime()
    if (launchVivi) {
      launchViviForWake(reason)
    }
    val result = observeTicketDetailForWakeWithRoot(
      reason = reason,
      wakeStartedAtMillis = recoveryStartedAtMillis,
      budgetMillis = TICKET_WAKE_RECOVERY_BUDGET_MILLIS,
      maxRecoveryActions = TICKET_WAKE_RECOVERY_MAX_ACTIONS
    )
    markWakeReadyIfNeeded(recoveryStartedAtMillis, result)
    markControlCodeRequestPhase(phases, "request_ticket_detail_recovery", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_request_ticket_detail_recovery",
      "${result.state}:${result.step}:success=${result.success} reason=$reason"
    )
    if (!result.success) {
      return false
    }
    updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_request_ticket_detail_ready")
    cacheForegroundViolation(null)
    if (streamActive) {
      startForegroundGuard()
    }
    return true
  }

  private fun controlCodeRecoveryBlockReason(nowMillis: Long = SystemClock.elapsedRealtime()): String? {
    if (latestTicketReselectRecent(nowMillis)) {
      when (latestTicketReselectStatus) {
        "pending" -> return "waiting_for_ticket_reselect"
        "failed" -> return "control_code_stream_unstable"
        "succeeded" -> {
          if (!markLatestTicketReselectFreshIfReady(nowMillis)) {
            return "waiting_for_stream_recovery"
          }
        }
      }
    }
    if (hardwareMarkedUnreliable()) {
      return "control_code_stream_unstable"
    }
    if (!streamActive) {
      return null
    }
    if (
      ticketSessionState in setOf(
        TICKET_SESSION_STARTING,
        TICKET_SESSION_SOFT_RECOVERY,
        TICKET_SESSION_NEEDS_ATTENTION,
        TICKET_SESSION_CONTROL_EXIT
      )
    ) {
      return "waiting_for_stream_recovery"
    }
    if (lastStreamRecoveryResult == "started" || streamWatchdogStage in setOf("recovering", "waiting_frame", "waiting_startup", "waiting_encoder", "cooldown")) {
      return "waiting_for_stream_recovery"
    }
    if (activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 || !hardwareCaptureVerified) {
      return "waiting_for_stream_recovery"
    }
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: return "waiting_for_stream_recovery"
    if (frameAgeMillis > LIVE_FRAME_MAX_AGE_MILLIS) {
      return "waiting_for_stream_recovery"
    }
    return null
  }

  private suspend fun handleGenerateControlCode(
    replyClient: TicketWebSocket?,
    requestId: String,
    digits: String,
    owner: String,
    app: String,
    flow: String,
    resultImage: Boolean,
    queueHint: RigasSatiksmeQueueHint,
    fastRevision: String = ""
  ) {
    val cleanRequestId = requestId.trim()
    val cleanDigits = digits.trim()
    val requestedOwner = owner.trim()
    val requestedApp = app.trim()
    val requestedFlow = flow.trim()
    val cleanFastRevision = fastRevision.trim()
    recordControlCodeCommandEnvelope(requestedOwner, requestedApp, requestedFlow)
    recordTicketEvent(
      "control_code_request_received",
      "request=$cleanRequestId owner=$requestedOwner app=$requestedApp flow=$requestedFlow digit_count=${cleanDigits.length}"
    )
    if (cleanRequestId.isBlank()) {
      recordRejectedControlCodeCommand(cleanRequestId, requestedOwner, requestedApp, requestedFlow, "missing_request_id")
      sendControlCodeResult("", false, "missing_request_id", "", 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (!CONTROL_CODE_REQUEST_DIGITS_REGEX.matches(cleanDigits)) {
      sendControlCodeResult(cleanRequestId, false, "invalid_code", "", 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (requestedOwner.isBlank() || requestedApp.isBlank() || requestedFlow.isBlank()) {
      recordRejectedControlCodeCommand(cleanRequestId, requestedOwner, requestedApp, requestedFlow, "command_owner_flow_required")
      sendControlCodeResult(cleanRequestId, false, "command_owner_flow_required", "", 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (requestedApp == TicketScreenConfig.TICKET_QR_APP_RIGAS_SATIKSME &&
      requestedFlow == TicketScreenConfig.TICKET_QR_FLOW_MONTHLY_TICKET
    ) {
      if (requestedOwner != TicketScreenConfig.TICKET_QR_OWNER_RIGAS_SATIKSME) {
        recordRejectedControlCodeCommand(cleanRequestId, requestedOwner, requestedApp, requestedFlow, "wrong_command_owner")
        sendControlCodeResult(cleanRequestId, false, "wrong_command_owner", "", 0L, emptyMap(), cleanupPending = false)
        return
      }
      handleGenerateRigasSatiksmeMonthlyTicketQr(replyClient, cleanRequestId, cleanDigits, queueHint)
      return
    }
    if (requestedApp != TicketScreenConfig.TICKET_QR_APP_VIVI) {
      recordRejectedControlCodeCommand(cleanRequestId, requestedOwner, requestedApp, requestedFlow, "unsupported_qr_source")
      sendControlCodeResult(cleanRequestId, false, "unsupported_qr_source", "", 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (requestedFlow != TicketScreenConfig.TICKET_QR_FLOW_CONTROL_CODE) {
      recordRejectedControlCodeCommand(cleanRequestId, requestedOwner, requestedApp, requestedFlow, "unsupported_qr_flow")
      sendControlCodeResult(cleanRequestId, false, "unsupported_qr_flow", "", 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (requestedOwner != TicketScreenConfig.TICKET_QR_OWNER_TICKET) {
      recordRejectedControlCodeCommand(cleanRequestId, requestedOwner, requestedApp, requestedFlow, "wrong_command_owner")
      sendControlCodeResult(cleanRequestId, false, "wrong_command_owner", "", 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (resultImage) {
      sendControlCodeResult(cleanRequestId, false, "unsupported_result_image_source", "", 0L, emptyMap(), false)
      return
    }
    if (sendCachedControlCodeResult(cleanRequestId) ||
      controlCodeRequestDuplicateActiveOrCompleted(cleanRequestId)
    ) return

    if (replyClient != null) protectedControlClients.add(replyClient)
    var burstStarted = false
    claimControlCodeAutomationForRequest()
    try {
      val startedAtMillis = SystemClock.elapsedRealtime()
      val phases = linkedMapOf<String, Long>("phone_command_received" to 0L)
      burstStarted = rootHardwareH264CaptureEngine.startControlCodeRequestBurst("control_code_browser_dispatch")
      if (burstStarted) phases["capture_burst_started"] = 0L
      controlCodePhoneMutationLane.withOwnership {
        if (sendCachedControlCodeResult(cleanRequestId) ||
          controlCodeRequestDuplicateActiveOrCompleted(cleanRequestId)
        ) return@withOwnership

        lastControlCodeRequestId = cleanRequestId
        lastControlCodeRequestStatus = "running"
        lastControlCodeRequestReason = null
        lastControlCodeRequestDurationMillis = null
        lastControlCodeRequestPhases = emptyMap()
        lastControlCodeRequestCompletedAtMillis = 0L
        sendControlCodeProgress(cleanRequestId, "running", "phone_request_started")
        markControlCodeFastNotReady("cleanup", "control_code_request")
        recordTicketEvent("control_code_request_running", "request=$cleanRequestId")

        var reason = "control_code_request_session_unavailable"
        var value = ""
        var resultSent = false
        var dirtyHierarchy = ""
        try {
          if (ensureTicketSessionForControlCodeRequest(phases, startedAtMillis) &&
            measureInputPhase(phases, "gate") { canForwardRemoteInput() }
          ) {
            markControlCodeRequestPhase(phases, "request_gate_passed", startedAtMillis)
            if (!burstStarted) {
              burstStarted = rootHardwareH264CaptureEngine.startControlCodeRequestBurst("control_code_gate_ready")
            }
            val delivery = runFastControlCodeDeliveryForRequest(cleanDigits, phases, startedAtMillis)
            reason = delivery.reason
            value = delivery.value
            dirtyHierarchy = delivery.generatedHierarchy
            if (delivery.ok) {
              val duration = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
              lastControlCodeRequestStatus = "succeeded"
              lastControlCodeRequestReason = "generated"
              lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
              beginControlCodeBrowserCaptureWait(cleanRequestId)
              sendTicketStateEvent(
                ticketState = TICKET_PIXEL_STATE_GENERATED_RESULT,
                reason = "generated",
                requestId = cleanRequestId,
                value = delivery.value,
                eventStreamEpoch = delivery.streamEpoch,
                eventFrameSequence = delivery.minFrameSequence,
                minFrameSequence = delivery.minFrameSequence,
                resultProof = delivery.resultProof,
                resultFrameEpoch = delivery.streamEpoch,
                resultMinFrameSequence = delivery.minFrameSequence,
                resultProofAtMillis = delivery.resultProofAtMillis,
                totalDurationMillis = duration,
                phases = phases
              )
              resultSent = true
              val capture = waitForControlCodeBrowserCapture(cleanRequestId, phases, startedAtMillis)
              clearControlCodeBrowserCaptureWait(cleanRequestId)
              val cleanupReason = if (capture.ok) "browser_capture_confirmed" else capture.reason
              val cleaned = returnControlCodeSurfaceToRawTicket(
                dirtyHierarchy,
                cleanupReason,
                phases,
                startedAtMillis,
                reuseGeneratedProof = capture.ok
              ) || recoverTicketDetailForControlCodeRequest(
                phases, startedAtMillis, "control_code_success_cleanup_recover"
              )
              markControlCodeRequestPhase(phases, "cleanup_finished", startedAtMillis)
              sendControlCodeCleanup(
                cleanRequestId, cleaned,
                if (cleaned) "ticket_detail" else "control_code_cleanup_attention_needed",
                startedAtMillis
              )
              if (cleaned) markControlCodeFastReady("cleanup:$cleanupReason")
              else markControlCodeFastNotReady("blocked", "control_code_cleanup_attention_needed")
            } else if (delivery.cleanupRequired) {
              sendControlCodeResult(
                cleanRequestId, false, reason, value, startedAtMillis, phases, cleanupPending = true
              )
              resultSent = true
              val cleaned = returnControlCodeSurfaceToRawTicket(
                dirtyHierarchy, "control_code_request_failed_return_raw", phases, startedAtMillis
              ) || recoverTicketDetailForControlCodeRequest(
                phases, startedAtMillis, "control_code_failed_cleanup_recover", launchVivi = true
              )
              sendControlCodeCleanup(
                cleanRequestId, cleaned,
                if (cleaned) "ticket_detail" else "control_code_cleanup_attention_needed",
                startedAtMillis
              )
              if (cleaned) markControlCodeFastReady("failed_delivery_cleanup")
              else markControlCodeFastNotReady("blocked", "control_code_cleanup_attention_needed")
            }
          } else {
            reason = inputGateReason.ifBlank { reason }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          reason = "control_code_request_failed"
          recordTicketEvent("ticket_control_code_request_failed", "request=$cleanRequestId error=${safeErrorDetail(error)}")
          if (!resultSent) {
            sendControlCodeResult(
              cleanRequestId, false, reason, value, startedAtMillis, phases, cleanupPending = true
            )
            resultSent = true
          }
          val cleaned = runCatching {
            returnControlCodeSurfaceToRawTicket(
              dirtyHierarchy, "control_code_request_exception_return_raw", phases, startedAtMillis
            ) || recoverTicketDetailForControlCodeRequest(
              phases, startedAtMillis, "control_code_exception_cleanup_recover", launchVivi = true
            )
          }.getOrDefault(false)
          sendControlCodeCleanup(
            cleanRequestId, cleaned,
            if (cleaned) "ticket_detail" else "control_code_cleanup_attention_needed",
            startedAtMillis
          )
          if (cleaned) markControlCodeFastReady("exception_cleanup")
          else markControlCodeFastNotReady("blocked", "control_code_cleanup_attention_needed")
        }
        if (!resultSent) {
          sendControlCodeResult(cleanRequestId, false, reason, value, startedAtMillis, phases, false)
        }
        lastControlCodeRequestDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
        lastControlCodeRequestPhases = phases.toMap()
      }
    } finally {
      if (burstStarted) rootHardwareH264CaptureEngine.stopControlCodeRequestBurst("control_code_request_finally")
      releaseControlCodeAutomationForRequest()
      if (replyClient != null) protectedControlClients.remove(replyClient)
    }
  }


  private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr(
    replyClient: TicketWebSocket?,
    cleanRequestId: String,
    cleanDigits: String,
    queueHint: RigasSatiksmeQueueHint
  ) {
    if (sendCachedControlCodeResult(cleanRequestId)) {
      return
    }
    val reusePreviousRigasSatiksmeQr = cancelPendingRigasSatiksmeReturnCleanup("new_rs_monthly_ticket_request")
    if (replyClient != null) {
      protectedControlClients.add(replyClient)
    }
    var pendingImmediateCleanup: PendingRigasSatiksmeReturnCleanup? = null
    var automationClaimed = false
    try {
      automationClaimed = true
      claimControlCodeAutomationForRequest()
      controlCodePhoneMutationLane.withOwnership {
        if (sendCachedControlCodeResult(cleanRequestId)) {
          return@withOwnership
        }
        val startedAtMillis = SystemClock.elapsedRealtime()
        val phases = linkedMapOf<String, Long>()
        lastControlCodeRequestId = cleanRequestId
        lastControlCodeRequestStatus = "running"
        lastControlCodeRequestReason = null
        lastControlCodeRequestDurationMillis = null
        lastControlCodeRequestPhases = emptyMap()
        lastControlCodeRequestCompletedAtMillis = 0L
        broadcastStatus()

        phases["phone_command_received"] = 0L
        recordTicketEvent("rs_monthly_ticket_request_started", "request=$cleanRequestId")
        markControlCodeTransition("rs_monthly_ticket_request")
        val outcome = RigasSatiksmeMonthlyTicketOperation(
          sourceApp = TicketScreenConfig.TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME,
          ticketFlow = TicketScreenConfig.TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY,
          runFlow = ::runRigasSatiksmeMonthlyTicketFlow,
          captureImage = ::captureRigasSatiksmeMonthlyTicketImageBytes,
          markPhase = ::markControlCodeRequestPhase
        ).run(
          cleanDigits = cleanDigits,
          phases = phases,
          requestStartedAtMillis = startedAtMillis,
          reusePreviousRigasSatiksmeQr = reusePreviousRigasSatiksmeQr
        )
        if (!outcome.ok) {
          val totalDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
          lastControlCodeRequestStatus = "failed"
          lastControlCodeRequestReason = outcome.reason
          lastControlCodeRequestDurationMillis = totalDurationMillis
          lastControlCodeRequestPhases = outcome.phases
          lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
          sendControlCodeResult(
            requestId = cleanRequestId,
            ok = false,
            reason = outcome.reason,
            value = "",
            startedAtMillis = startedAtMillis,
            phases = outcome.phases,
            cleanupPending = outcome.cleanupRequired
          )
          if (shouldDeferRigasSatiksmeReturnCleanup(queueHint, outcome.reason)) {
            scheduleRigasSatiksmeReturnCleanupAfterIdle(
              requestId = cleanRequestId,
              startedAtMillis = startedAtMillis,
              reason = outcome.reason
            )
          } else {
            pendingImmediateCleanup = PendingRigasSatiksmeReturnCleanup(
              requestId = cleanRequestId,
              phases = phases,
              requestStartedAtMillis = startedAtMillis,
              reason = outcome.reason
            )
          }
          pendingImmediateCleanup?.let { cleanup ->
            completeRigasSatiksmeImmediateCleanup(cleanup)
            pendingImmediateCleanup = null
          }
          return@withOwnership
        }

        val imageBytes = outcome.imageBytes ?: ByteArray(0)
        val watermark = requestFreshTicketStateFrameWatermark("rs_monthly_ticket_control_screen")
        lastControlCodeRequestStatus = "succeeded"
        lastControlCodeRequestReason = outcome.reason
        lastControlCodeRequestDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
        lastControlCodeRequestPhases = outcome.phases
        lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
        sendRigassatiksmeQrResult(
          requestId = cleanRequestId,
          ok = true,
          reason = outcome.reason,
          imageBytes = imageBytes,
          startedAtMillis = startedAtMillis,
          phases = outcome.phases,
          sourceApp = outcome.sourceApp,
          ticketFlow = outcome.ticketFlow
        )
        sendTicketStateEvent(
          ticketState = TICKET_PIXEL_STATE_GENERATED_RESULT,
          reason = "rs_monthly_ticket_control_screen",
          requestId = cleanRequestId,
          value = "",
          eventStreamEpoch = watermark.first,
          eventFrameSequence = watermark.second,
          minFrameSequence = watermark.second,
          totalDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L),
          phases = phases
        )
        recordTicketEvent(
          "rs_monthly_ticket_control_screen",
          "request=$cleanRequestId bytes=${imageBytes.size}"
        )
        if (queueHint.ticketPriorityActive) {
          pendingImmediateCleanup = PendingRigasSatiksmeReturnCleanup(
            requestId = cleanRequestId,
            phases = phases,
            requestStartedAtMillis = startedAtMillis,
            reason = outcome.reason
          )
        } else {
          scheduleRigasSatiksmeReturnCleanupAfterIdle(
            requestId = cleanRequestId,
            startedAtMillis = startedAtMillis,
            reason = outcome.reason
          )
        }
        pendingImmediateCleanup?.let { cleanup ->
          completeRigasSatiksmeImmediateCleanup(cleanup)
          pendingImmediateCleanup = null
        }
      }
    } finally {
      if (automationClaimed) {
        releaseControlCodeAutomationForRequest()
      }
      if (replyClient != null) {
        protectedControlClients.remove(replyClient)
      }
    }
  }

  private suspend fun completeRigasSatiksmeImmediateCleanup(
    cleanup: PendingRigasSatiksmeReturnCleanup
  ) {
    val cleanupSucceeded = returnRigasSatiksmeMonthlyTicketFlowToViviTicket(
      phases = cleanup.phases,
      requestStartedAtMillis = cleanup.requestStartedAtMillis,
      reason = cleanup.reason
    )
    sendControlCodeCleanup(
      requestId = cleanup.requestId,
      ok = cleanupSucceeded,
      reason = if (cleanupSucceeded) "ticket_detail" else "rs_monthly_ticket_cleanup_attention_needed",
      startedAtMillis = cleanup.requestStartedAtMillis
    )
  }

  private fun shouldDeferRigasSatiksmeReturnCleanup(
    queueHint: RigasSatiksmeQueueHint,
    reason: String
  ): Boolean {
    if (queueHint.ticketPriorityActive) return false
    return !rigasSatiksmeFailureRequiresImmediateCleanup(reason)
  }

  private fun rigasSatiksmeFailureRequiresImmediateCleanup(reason: String): Boolean {
    return when (reason) {
      "rs_phone_automation_unavailable",
      "rs_app_attention_required",
      "rs_auth_blocked" -> true
      else -> false
    }
  }

  private fun cancelPendingRigasSatiksmeReturnCleanup(reason: String): Boolean {
    val snapshot = synchronized(pendingRigasSatiksmeReturnCleanupLock) {
      val job = pendingRigasSatiksmeReturnCleanupJob
      if (job == null) {
        pendingRigasSatiksmeReturnCleanupStarted = false
        null
      } else {
        val pendingCleanup = !job.isCompleted
        val cleanupStarted = pendingRigasSatiksmeReturnCleanupStarted
        pendingRigasSatiksmeReturnCleanupJob = null
        pendingRigasSatiksmeReturnCleanupStarted = false
        Triple(job, pendingCleanup, cleanupStarted)
      }
    } ?: return false
    val (job, pendingCleanup, cleanupStarted) = snapshot
    val reusePreviousQr = pendingCleanup && !cleanupStarted
    if (pendingCleanup) {
      recordTicketEvent(
        "rs_monthly_ticket_idle_cleanup_canceled",
        "reason=$reason started=$cleanupStarted reuse_previous_qr=$reusePreviousQr"
      )
      job.cancel(CancellationException(reason))
    }
    return reusePreviousQr
  }

  private fun scheduleRigasSatiksmeReturnCleanupAfterIdle(
    requestId: String,
    startedAtMillis: Long,
    reason: String
  ) {
    cancelPendingRigasSatiksmeReturnCleanup("reschedule_rs_monthly_ticket_idle_cleanup")
    val cleanupJob = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
      try {
        delay(TICKET_RS_MONTHLY_IDLE_CLEANUP_DELAY_MILLIS)
        controlCodePhoneMutationLane.withOwnership {
          val runningJob = coroutineContext[Job]
          val ownsCleanup = synchronized(pendingRigasSatiksmeReturnCleanupLock) {
            if (pendingRigasSatiksmeReturnCleanupJob === runningJob) {
              pendingRigasSatiksmeReturnCleanupStarted = true
              true
            } else {
              false
            }
          }
          if (!ownsCleanup) {
            return@withOwnership
          }
          currentCoroutineContext().ensureActive()
          recordTicketEvent(
            "rs_monthly_ticket_idle_cleanup_started",
            "request=$requestId reason=$reason delay_ms=$TICKET_RS_MONTHLY_IDLE_CLEANUP_DELAY_MILLIS"
          )
          val cleanupPhases = linkedMapOf<String, Long>()
          val cleanupSucceeded = returnRigasSatiksmeMonthlyTicketFlowToViviTicket(
            phases = cleanupPhases,
            requestStartedAtMillis = startedAtMillis,
            reason = reason
          )
          sendControlCodeCleanup(
            requestId = requestId,
            ok = cleanupSucceeded,
            reason = if (cleanupSucceeded) "ticket_detail" else "rs_monthly_ticket_cleanup_attention_needed",
            startedAtMillis = startedAtMillis
          )
        }
      } finally {
        val completingJob = coroutineContext[Job]
        synchronized(pendingRigasSatiksmeReturnCleanupLock) {
          if (pendingRigasSatiksmeReturnCleanupJob === completingJob) {
            pendingRigasSatiksmeReturnCleanupJob = null
            pendingRigasSatiksmeReturnCleanupStarted = false
          }
        }
      }
    }
    synchronized(pendingRigasSatiksmeReturnCleanupLock) {
      pendingRigasSatiksmeReturnCleanupStarted = false
      pendingRigasSatiksmeReturnCleanupJob = cleanupJob
    }
    cleanupJob.start()
  }

  private suspend fun returnRigasSatiksmeMonthlyTicketFlowToViviTicket(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    reason: String
  ): Boolean {
    markControlCodeRequestPhase(phases, "rs_monthly_ticket_return_started", requestStartedAtMillis)
    if (runRigasSatiksmeMonthlyTicketFastReturnToViviTicket(reason)) {
      rememberRigasSatiksmeReturnedToViviTicketDetail(reason)
      markControlCodeRequestPhase(phases, "rs_monthly_ticket_return_finished", requestStartedAtMillis)
      updateTicketSessionState(TICKET_SESSION_LIVE, "rs_monthly_ticket_fast_return_ticket")
      val watermark = requestFreshTicketStateFrameWatermark("rs_monthly_ticket_fast_return_ticket")
      sendTicketStateEvent(
        ticketState = TICKET_PIXEL_STATE_RAW_TICKET,
        reason = "rs_monthly_ticket_fast_return_ticket",
        eventStreamEpoch = watermark.first,
        eventFrameSequence = watermark.second,
        minFrameSequence = watermark.second
      )
      broadcastStatus()
      return true
    }
    val wakeReason = "rs_monthly_ticket_return:$reason"
    val wakeStartedAtMillis = beginTicketWake(wakeReason)
    wakeTicketScreenForSessionStart(wakeReason, wakeStartedAtMillis)
    launchViviForWake(wakeReason)
    val result = observeTicketDetailForWakeWithRoot(
      wakeReason,
      wakeStartedAtMillis,
      budgetMillis = TICKET_RS_MONTHLY_RETURN_BUDGET_MILLIS,
      maxRecoveryActions = TICKET_RS_MONTHLY_RETURN_MAX_RECOVERY_ACTIONS
    )
    markWakeReadyIfNeeded(wakeStartedAtMillis, result)
    finishTicketWake(
      wakeStartedAtMillis,
      succeeded = result.success,
      reason = if (result.success) "ticket_ready" else result.step
    )
    recordTicketEvent(
      "rs_monthly_ticket_return_ticket",
      "reason=$reason state=${result.state} step=${result.step} success=${result.success}"
    )
    if (result.success) {
      rememberRigasSatiksmeReturnedToViviTicketDetail(reason)
      markControlCodeRequestPhase(phases, "rs_monthly_ticket_return_finished", requestStartedAtMillis)
      updateTicketSessionState(TICKET_SESSION_LIVE, "rs_monthly_ticket_return_ticket")
      val watermark = requestFreshTicketStateFrameWatermark("rs_monthly_ticket_return_ticket")
      sendTicketStateEvent(
        ticketState = TICKET_PIXEL_STATE_RAW_TICKET,
        reason = "rs_monthly_ticket_return_ticket",
        eventStreamEpoch = watermark.first,
        eventFrameSequence = watermark.second,
        minFrameSequence = watermark.second
      )
    } else {
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "rs_monthly_ticket_return_${result.state.name.lowercase()}")
    }
    broadcastStatus()
    return result.success
  }

  private fun rememberRigasSatiksmeReturnedToViviTicketDetail(reason: String) {
    val remembered = viviStateMemory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = null,
      source = "root",
      reason = "rs_monthly_ticket_return:$reason"
    )
    recordTicketEvent(
      "rs_monthly_ticket_return_ticket_detail_remembered",
      "reason=$reason source=${remembered.source}"
    )
  }

  private suspend fun runRigasSatiksmeMonthlyTicketFastReturnToViviTicket(reason: String): Boolean {
    recordTicketEvent("rs_monthly_ticket_fast_return_started", "reason=$reason")
    val result = runFastNonTouchScript(
      """
      set -u
      am start -n ${TicketScreenConfig.VIVI_LAUNCH_ACTIVITY} >/dev/null 2>&1 || {
        echo "RS_FAST_RETURN_STATUS launch_failed"
        exit 1
      }
      sleep 0.5
      input tap 405 2331 >/dev/null 2>&1 || true
      sleep 0.3
      input tap 785 412 >/dev/null 2>&1 || true
      sleep 0.35
      input tap 540 775 >/dev/null 2>&1 || true
      sleep 0.1
      echo "RS_FAST_RETURN_STATUS tapped"
      """.trimIndent(),
      "rs_monthly_ticket_fast_return",
      TICKET_RS_MONTHLY_FAST_RETURN_TIMEOUT_MILLIS.milliseconds
    )
    val hierarchyWaitStartedAt = SystemClock.elapsedRealtime()
    var hierarchy = ""
    var state = TicketViviRecoveryState.UNKNOWN_VIVI
    while (SystemClock.elapsedRealtime() - hierarchyWaitStartedAt <= 1_800L) {
      hierarchy = fastVisibleHierarchy(TicketScreenConfig.VIVI_PACKAGE, "rs_monthly_ticket_fast_return")
      state = if (hierarchy.isBlank()) {
        TicketViviRecoveryState.UNKNOWN_VIVI
      } else {
        TicketViviPageEnforcer.classifyForRecovery(hierarchy)
      }
      if (state == TicketViviRecoveryState.TICKET_DETAIL) {
        break
      }
      delay(120)
    }
    recordTicketEvent(
      "rs_monthly_ticket_fast_return_hierarchy_wait_finished",
      "state=$state duration_ms=${SystemClock.elapsedRealtime() - hierarchyWaitStartedAt} hierarchy_len=${hierarchy.length}"
    )
    if (hierarchy.isBlank()) {
      hierarchy = dumpVisibleHierarchyWithRoot(
        path = "/data/local/tmp/pixel-vivi-fast-return-window.xml",
        reason = "rs_monthly_ticket_fast_return_fallback"
      )
      state = if (hierarchy.isBlank()) {
        TicketViviRecoveryState.UNKNOWN_VIVI
      } else {
        TicketViviPageEnforcer.classifyForRecovery(hierarchy)
      }
    }
    val success = result.ok && state == TicketViviRecoveryState.TICKET_DETAIL
    recordTicketEvent(
      "rs_monthly_ticket_fast_return_finished",
      "reason=$reason success=$success state=$state duration_ms=${result.durationMs} hierarchy_len=${hierarchy.length} stderr_tail=${result.stderr.takeLast(120).replace('\n', ' ').replace('\r', ' ')}"
    )
    return success
  }

  private suspend fun runRigasSatiksmeMonthlyTicketFlow(
    cleanDigits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    reusePreviousRigasSatiksmeQr: Boolean
  ): RigasSatiksmeMonthlyTicketFlowResult {
    markControlCodeRequestPhase(phases, "rs_monthly_ticket_automation_preflight_started", requestStartedAtMillis)
    if (reusePreviousRigasSatiksmeQr) {
      recordTicketEvent("rs_monthly_ticket_warm_previous_qr_ignored", "shell_semantic_driver=true")
    }
    val driver = RigasSatiksmeDirectTapDriver(
      gateway = object : RigasSatiksmeDirectTapGateway {
        override suspend fun launchApp(): Boolean = launchRigasSatiksmeAppForDirectAutomation()

        override suspend fun waitForForeground(): Boolean = waitForRigasSatiksmeDirectForeground()

        override suspend fun snapshot(reason: String): List<PhoneAutomationVisibleNode> {
          return snapshotRigasSatiksmeUiAutomatorNodes(reason)
        }

        override suspend fun resetApp(reason: String): Boolean {
          return resetRigasSatiksmeAppForDirectAutomation(reason)
        }

        override suspend fun tapNodeCenter(node: PhoneAutomationVisibleNode, reason: String): Boolean {
          return tapRigasSatiksmeVisibleNodeCenter(node, reason)
        }

        override suspend fun tapRatio(x: Double, y: Double, reason: String): Boolean {
          val (sourceWidth, sourceHeight) = currentDisplaySize()
          return tapRigasSatiksmeDirectTarget(
            x = (sourceWidth * x).toInt().coerceIn(0, sourceWidth - 1),
            y = (sourceHeight * y).toInt().coerceIn(0, sourceHeight - 1),
            reason = reason
          )
        }

        override suspend fun enterManualCode(
          cleanDigits: String,
          fieldXRatio: Double,
          fieldYRatio: Double
        ): Boolean {
          val (sourceWidth, sourceHeight) = currentDisplaySize()
          return enterRigasSatiksmeManualCode(
            cleanDigits = cleanDigits,
            fieldX = (sourceWidth * fieldXRatio).toInt().coerceIn(0, sourceWidth - 1),
            fieldY = (sourceHeight * fieldYRatio).toInt().coerceIn(0, sourceHeight - 1)
          )
        }

        override suspend fun pressBack(reason: String): Boolean {
          return pressBackForRigasSatiksmeDirectDriver(reason)
        }

        override fun recordPhase(name: String, details: String) {
          recordTicketEvent(name, details)
        }
      }
    )
    markControlCodeRequestPhase(phases, "rs_monthly_ticket_drive_started", requestStartedAtMillis)
    val startedAt = SystemClock.elapsedRealtime()
    val result = driver.run(cleanDigits)
    markControlCodeRequestPhase(phases, "rs_monthly_ticket_flow_finished", requestStartedAtMillis)
    recordTicketEvent(
      "rs_monthly_ticket_shell_semantic_flow_finished",
      "ok=${result.ok} reason=${result.reason} duration_ms=${SystemClock.elapsedRealtime() - startedAt} hierarchy_len=${result.hierarchy.length} details=${result.details.takeLast(120).replace('\n', ' ').replace('\r', ' ')}"
    )
    return result
  }

  private suspend fun launchRigasSatiksmeAppForDirectAutomation(): Boolean {
    markControlCodeTransition("rs_monthly_ticket_visual_launch")
    val startedAt = SystemClock.elapsedRealtime()
    return try {
      withContext(Dispatchers.Main) {
        val launchIntent = Intent().setClassName(
          TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
          "${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}.MainActivity"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
      }
      recordTicketEvent(
        "rs_monthly_ticket_visual_launch_finished",
        "ok=true source=startActivity duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
      )
      true
    } catch (error: Throwable) {
      recordTicketEvent(
        "rs_monthly_ticket_visual_launch_failed",
        "source=startActivity duration_ms=${SystemClock.elapsedRealtime() - startedAt} error=${error.message?.take(120)?.replace('\n', ' ')?.replace('\r', ' ')}"
      )
      false
    }
  }

  private suspend fun resetRigasSatiksmeAppForDirectAutomation(reason: String): Boolean {
    val startedAt = SystemClock.elapsedRealtime()
    recordTicketEvent("rs_monthly_ticket_app_reset_started", "reason=$reason package=${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}")
    val forceStop = runRigasSatiksmeDirectInput(
      "am force-stop ${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}",
      "${reason}_force_stop"
    )
    delay(180L)
    val launched = launchRigasSatiksmeAppForDirectAutomation()
    val foreground = if (launched) waitForRigasSatiksmeDirectForeground() else false
    val ok = forceStop.ok && launched && foreground
    recordTicketEvent(
      "rs_monthly_ticket_app_reset_finished",
      "reason=$reason ok=$ok force_stop_ok=${forceStop.ok} launched=$launched foreground=$foreground duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
    )
    return ok
  }

  private suspend fun waitForRigasSatiksmeDirectForeground(): Boolean {
    delay(700L)
    recordTicketEvent("rs_monthly_ticket_visual_foreground_ready", "source=visual_frame_gate")
    return true
  }

  private suspend fun tapRigasSatiksmeDirectTarget(x: Int, y: Int, reason: String): Boolean {
    recordTicketEvent("rs_monthly_ticket_visual_tap", "reason=$reason x=$x y=$y")
    val result = runRigasSatiksmeDirectInput("input tap $x $y", reason)
    return result.ok
  }

  private suspend fun tapRigasSatiksmeVisibleNodeCenter(
    node: PhoneAutomationVisibleNode,
    reason: String
  ): Boolean {
    val center = parseUiAutomatorNodeCenter(node.bounds) ?: return false
    val (sourceWidth, sourceHeight) = currentDisplaySize()
    return tapRigasSatiksmeDirectTarget(
      x = center.first.coerceIn(0, sourceWidth - 1),
      y = center.second.coerceIn(0, sourceHeight - 1),
      reason = "$reason:node"
    )
  }

  private fun parseUiAutomatorNodeCenter(bounds: String): Pair<Int, Int>? {
    val match = Regex("""\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""")
      .find(bounds)
      ?: return null
    val left = match.groupValues[1].toIntOrNull() ?: return null
    val top = match.groupValues[2].toIntOrNull() ?: return null
    val right = match.groupValues[3].toIntOrNull() ?: return null
    val bottom = match.groupValues[4].toIntOrNull() ?: return null
    if (right <= left || bottom <= top) return null
    return Pair((left + right) / 2, (top + bottom) / 2)
  }

  private suspend fun pressBackForRigasSatiksmeDirectDriver(reason: String): Boolean {
    val result = runRigasSatiksmeDirectInput("input keyevent KEYCODE_BACK", reason)
    return result.ok
  }

  private suspend fun enterRigasSatiksmeManualCode(
    cleanDigits: String,
    fieldX: Int,
    fieldY: Int
  ): Boolean {
    if (!cleanDigits.all { it.isDigit() }) return false
    val result = runRigasSatiksmeDirectInput(
      """
      input tap $fieldX $fieldY
      sleep 0.06
      input keyevent KEYCODE_MOVE_END KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL KEYCODE_DEL
      input text $cleanDigits
      """.trimIndent(),
      "rs_monthly_ticket_enter_manual_code"
    )
    return result.ok
  }

  private suspend fun runRigasSatiksmeDirectInput(command: String, reason: String): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      inputRootExecutor.runScript(command, RIGAS_SATIKSME_DIRECT_INPUT_TIMEOUT_MILLIS.milliseconds)
        .also { recordInputCommandResult(reason, it) }
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  private suspend fun snapshotRigasSatiksmeUiAutomatorNodes(reason: String): List<PhoneAutomationVisibleNode> {
    val startedAt = SystemClock.elapsedRealtime()
    val result = inputRootExecutor.runScript(
      TicketUiautomatorDump.command(
        path = "/data/local/tmp/rs-direct-window.xml",
        timeoutMillis = RIGAS_SATIKSME_DIRECT_UI_DUMP_TIMEOUT_MILLIS
      ),
      RIGAS_SATIKSME_DIRECT_UI_DUMP_TIMEOUT_MILLIS.milliseconds
    )
    if (!result.ok) {
      recordTicketEvent(
        "rs_monthly_ticket_direct_snapshot_failed",
        "reason=$reason duration_ms=${result.durationMs} stdout_len=${result.stdout.length} output=${result.stdout.takeLast(140).replace('\n', ' ').replace('\r', ' ')}"
      )
      return emptyList()
    }
    val nodes = runCatching { RigasSatiksmeUiAutomatorParser.parse(result.stdout) }
      .getOrElse { error ->
        recordTicketEvent(
          "rs_monthly_ticket_direct_snapshot_failed",
          "reason=$reason parse_error=${error.message?.take(120)?.replace('\n', ' ')?.replace('\r', ' ')}"
        )
        emptyList()
      }
      .filter { it.className.isNotBlank() || it.text.isNotBlank() || it.contentDescription.isNotBlank() }
    recordTicketEvent(
      "rs_monthly_ticket_direct_snapshot_ready",
      "reason=$reason nodes=${nodes.size} duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
    )
    return nodes
  }

  private suspend fun captureRigasSatiksmeMonthlyTicketImageBytes(
    cleanDigits: String,
    hierarchy: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): ByteArray? {
    val captured = captureGeneratedControlCodeImageBytes(
      hierarchy = hierarchy,
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      cropToControlCodeGraphic = false,
      maxOutputWidth = RIGAS_SATIKSME_RESULT_CAPTURE_MAX_WIDTH
    )
    if (captured == null || captured.isEmpty()) {
      recordTicketEvent("rs_monthly_ticket_app_screenshot_missing", "code=[REDACTED]")
      return null
    }
    if (isMostlyBlankPng(captured)) {
      recordTicketEvent(
        "rs_monthly_ticket_secure_capture_blocked",
        "bytes=${captured.size} code=[REDACTED]"
      )
      return null
    }
    recordTicketEvent(
      "rs_monthly_ticket_app_screenshot_ready",
      "bytes=${captured.size} code=[REDACTED]"
    )
    return captured
  }

  private suspend fun fastVisibleHierarchy(expectedPackageName: String, reason: String): String {
    val startedAt = SystemClock.elapsedRealtime()
    val nodes = PhoneAutomationServiceBridge.snapshotVisibleNodes(expectedPackageName)
    val durationMs = SystemClock.elapsedRealtime() - startedAt
    if (nodes.isEmpty()) {
      recordTicketEvent("fast_visible_hierarchy_empty", "reason=$reason package=$expectedPackageName duration_ms=$durationMs")
      return ""
    }
    val hierarchy = buildString {
      append("<hierarchy>")
      nodes.forEachIndexed { index, node ->
        append("<node index=\"").append(index).append("\"")
        append(" text=\"").append(escapeHierarchyAttribute(node.text)).append("\"")
        append(" resource-id=\"").append(escapeHierarchyAttribute(node.resourceId)).append("\"")
        append(" class=\"").append(escapeHierarchyAttribute(node.className)).append("\"")
        append(" package=\"").append(escapeHierarchyAttribute(expectedPackageName)).append("\"")
        append(" content-desc=\"").append(escapeHierarchyAttribute(node.contentDescription)).append("\"")
        append(" clickable=\"").append(node.clickable).append("\"")
        append(" enabled=\"").append(node.enabled).append("\"")
        append(" focused=\"").append(node.focused).append("\"")
        append(" focusable=\"").append(node.focusable).append("\"")
        append(" bounds=\"").append(escapeHierarchyAttribute(node.bounds)).append("\"")
        append(" />")
      }
      append("</hierarchy>")
    }
    recordTicketEvent(
      "fast_visible_hierarchy_ready",
      "reason=$reason package=$expectedPackageName nodes=${nodes.size} duration_ms=$durationMs hierarchy_len=${hierarchy.length}"
    )
    return hierarchy
  }

  private suspend fun dumpVisibleHierarchyWithRoot(path: String, reason: String): String {
    val result = runFastNonTouchWakeScript(
      TicketUiautomatorDump.command(
        path = path,
        timeoutMillis = TICKET_ROOT_HIERARCHY_DUMP_TIMEOUT_MILLIS
      ),
      "root_visible_hierarchy_dump:$reason",
      TICKET_ROOT_HIERARCHY_DUMP_TIMEOUT_MILLIS.milliseconds
    )
    recordTicketEvent(
      "root_visible_hierarchy_dumped",
      "reason=$reason ok=${result.ok} duration_ms=${result.durationMs} stdout_len=${result.stdout.length} stderr_tail=${result.stderr.takeLast(120).replace('\n', ' ').replace('\r', ' ')}"
    )
    val hierarchy = if (result.stdout.isBlank()) {
      val directFileHierarchy = runCatching {
        java.io.File(path).takeIf { it.exists() && it.length() > 0L }?.readText().orEmpty()
      }.getOrDefault("")
      if (directFileHierarchy.isNotBlank()) {
        recordTicketEvent(
          "root_visible_hierarchy_file_read",
          "reason=$reason mode=direct stdout_len=${directFileHierarchy.length}"
        )
        directFileHierarchy
      } else {
        val fileResult = runFastNonTouchWakeScript(
          "/system/bin/cat $path 2>/dev/null || true",
          "root_visible_hierarchy_file_read:$reason",
          2_000.milliseconds
        )
        recordTicketEvent(
          "root_visible_hierarchy_file_read",
          "reason=$reason mode=root_cat ok=${fileResult.ok} duration_ms=${fileResult.durationMs} stdout_len=${fileResult.stdout.length}"
        )
        fileResult.stdout
      }
    } else {
      result.stdout
    }
    return hierarchy.trim()
  }

  private fun classifyRigasSatiksmeMonthlyTicketHierarchy(hierarchy: String, cleanDigits: String): String {
    if (hierarchy.isBlank()) {
      return ""
    }
    fun has(value: String) = hierarchy.contains(value, ignoreCase = true)
    if (has("Wrong code")) {
      return "wrong_code"
    }
    val hasViviMonthlyControlScreen =
      has("KONTROLES KODS") &&
        has("Aizvērt") &&
        hasRigasSatiksmeMonthlyTicketMarker(hierarchy)
    if (hasViviMonthlyControlScreen && has(cleanDigits)) {
      return "rs_monthly_ticket_control_screen"
    }
    if (hasViviMonthlyControlScreen) {
      return "stale_control_ticket"
    }
    if (
      has("Ievadi kontroles kodu") ||
        has("kontroles kods") && has("OK") && has("Atcelt")
    ) {
      return "wrong_code"
    }
    val hasControlScreen =
      !has("REGISTER A TRIP") &&
        has("TICKET FOR CONTROL") &&
        has("qr code") &&
        hasRigasSatiksmeMonthlyTicketMarker(hierarchy)
    if (hasControlScreen && has(cleanDigits)) {
      return "rs_monthly_ticket_control_screen"
    }
    if (hasControlScreen) {
      return "stale_control_ticket"
    }
    if (isRigasSatiksmeMonthlyTicketListMissing(hierarchy)) {
      return "missing_monthly_ticket"
    }
    return "missing_control_ticket"
  }

  private fun classifyRigasSatiksmeMonthlyTicketStartHierarchy(hierarchy: String, cleanDigits: String): String {
    if (hierarchy.isBlank()) {
      return ""
    }
    fun has(value: String) = hierarchy.contains(value, ignoreCase = true)
    val hasMonthlyControlScreen =
      has("KONTROLES KODS") && has("Aizvērt") && hasRigasSatiksmeMonthlyTicketMarker(hierarchy) ||
        (!has("REGISTER A TRIP") && has("TICKET FOR CONTROL") && has("qr code") && hasRigasSatiksmeMonthlyTicketMarker(hierarchy))
    if (hasMonthlyControlScreen && has(cleanDigits)) {
      return "matching_control_ticket"
    }
    if (hasMonthlyControlScreen) {
      return "previous_control_ticket"
    }
    if (
      has("Ievadi kontroles kodu") ||
        has("kontroles kods") && has("OK") && has("Atcelt") ||
        has("ENTER THE CODE MANUALLY")
    ) {
      return "manual_code_screen"
    }
    if (has("REGISTER A TRIP") || has("TICKET FOR CONTROL")) {
      return "home_screen"
    }
    return "unknown_start"
  }

  private fun hasRigasSatiksmeMonthlyTicketMarker(hierarchy: String): Boolean {
    return Regex("""1\s*month""", RegexOption.IGNORE_CASE).containsMatchIn(hierarchy) ||
      hierarchy.contains("30 dienu biļete", ignoreCase = true)
  }

  private fun isRigasSatiksmeMonthlyTicketListMissing(hierarchy: String): Boolean {
    val lower = hierarchy.lowercase()
    val onMonthlyTicketList = listOf(
      "tickets",
      "biļetes",
      "ticket list",
      "available tickets"
    ).any { lower.contains(it) }
    val explicitEmptyTicketList = listOf(
      "no tickets",
      "no active tickets",
      "nav biļešu",
      "biļešu nav"
    ).any { lower.contains(it) }
    return onMonthlyTicketList && explicitEmptyTicketList && !hasRigasSatiksmeMonthlyTicketMarker(hierarchy)
  }

  private fun escapeHierarchyAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

  private suspend fun runFastControlCodeDeliveryForRequest(
    cleanDigits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodeDelivery {
    val immediateSubmitted = runImmediateControlCodeOpenTypeSubmitForRequest(cleanDigits, phases, requestStartedAtMillis)
    if (immediateSubmitted == false) {
      return FastControlCodeDelivery(
        ok = false,
        reason = inputGateReason.ifBlank { "control_code_open_type_submit_failed" },
        cleanupRequired = true
      )
    }
    if (immediateSubmitted != true) {
      val transaction = openControlCodePopupFastForRequest(phases, requestStartedAtMillis) ?: return FastControlCodeDelivery(
        ok = false,
        reason = inputGateReason.ifBlank { "control_code_popup_timeout" },
        cleanupRequired = true
      )
      if (!enterAndSubmitControlCodeDigitsFastForRequest(cleanDigits, transaction, phases, requestStartedAtMillis)) {
        return FastControlCodeDelivery(
          ok = false,
          reason = inputGateReason.ifBlank { "control_code_input_submit_failed" }
        )
      }
    }
    val waitOutcome = waitForGeneratedControlCodeResultAfterSubmit(
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      submittedDigits = cleanDigits,
      timeoutMillis = CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS,
      rootDumpTimeoutMillis = CONTROL_CODE_FAST_RESULT_ROOT_DUMP_TIMEOUT_MILLIS
    )
    val generated = waitOutcome.generated ?: return FastControlCodeDelivery(
      ok = false,
      reason = waitOutcome.failureReason,
      generatedHierarchy = waitOutcome.failureHierarchy
    )
    val watermark = if (generated.streamEpoch > 0L && generated.minFrameSequence > 0L) {
      recordTicketEvent(
        "control_code_marker_watermark_reused",
        "epoch=${generated.streamEpoch} min_sequence=${generated.minFrameSequence}"
      )
      generated.streamEpoch to generated.minFrameSequence
    } else {
      markerFirstControlCodeFrameWatermarkForBrowser(
        reason = "control_code_marker_ready",
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
      )
    }
    markControlCodeRequestPhase(phases, "result_marker_requested", requestStartedAtMillis)
    markViewerInput("control_code_request_digits")
    return FastControlCodeDelivery(
      ok = true,
      reason = "generated",
      value = generated.value,
      generatedHierarchy = generated.hierarchy,
      streamEpoch = watermark.first,
      minFrameSequence = watermark.second,
      resultProof = generated.resultProof,
      resultProofAtMillis = generated.resultProofAtMillis
    )
  }

  private suspend fun runImmediateControlCodeOpenTypeSubmitForRequest(
    digits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean? {
    val decision = controlCodeImmediateStartDecision()
    if (!decision.accepted) {
      recordTicketEvent("control_code_immediate_open_type_submit_skipped", decision.reason)
      return null
    }
    // A real rooted tap is the authoritative open action.  The one-shot virtual keyboard is
    // intentionally created only after that tap: on current ViVi builds a newly registered
    // keyboard can consume the first code-button touch as focus without opening the popup.
    // Its subsequent input tap still happens after the popup has had the helper-registration
    // window to settle, and value proof remains mandatory before OK.
    val transaction = openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)
      ?: return false
    markControlCodeRequestPhase(phases, "native_open_type_dispatched", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_popup_open_then_type_dispatched",
      "root_open_before_registered_keyboard"
    )
    return enterAndSubmitControlCodeDigitsFastForRequest(
      digits = digits,
      transaction = transaction.copy(open = null),
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis
    )
  }

  private fun requestFreshControlCodeFrameWatermark(reason: String): Pair<Long, Long> {
    return requestFreshTicketStateFrameWatermark(reason)
  }

  private fun requestFreshTicketStateFrameWatermark(reason: String): Pair<Long, Long> {
    requestKeyFrame(reason)
    val eventStreamEpoch = streamEpoch
    val eventFrameSequence = (frameSequence + 1L).coerceAtLeast(1L)
    return eventStreamEpoch to eventFrameSequence
  }

  private suspend fun openControlCodePopupFastForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodePopupTransaction? {
    cachedControlCodePopupTargetsForRequest(phases, requestStartedAtMillis)?.let { transaction ->
      sendTicketStateEvent(
        ticketState = TICKET_PIXEL_STATE_CONTROL_POPUP,
        reason = "control_code_popup_cached_ready",
        requestId = lastControlCodeRequestId.orEmpty()
      )
      return transaction
    }
    openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)?.let { return it }
    openControlCodePopupFromVerifiedStateFastForRequest(phases, requestStartedAtMillis)?.let { return it }
    val failReason = inputGateReason.ifBlank { "control_code_phone_not_ready" }
    recordInputGateDecision(allowed = false, reason = failReason)
    recordTicketEvent("control_code_request_fast_fail", failReason)
    return null
  }

  private suspend fun openControlCodePopupImmediateForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodePopupTransaction? {
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
      runFastNonTouchInput(
        "input tap ${action.x} ${action.y}",
        "control_code_request_open_popup_immediate",
        postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS
      )
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
    delay(CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS)
    return openedControlCodePopupTransactionTargets(
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      source = "immediate_after_tap_settled_geometry",
      eventReason = "control_code_popup_transaction_ready",
      announceReady = false
    )
  }

  private suspend fun openControlCodePopupFromVerifiedStateFastForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodePopupTransaction? {
    val hierarchy = resolveControlCodeHierarchyForFastRequest(phases, "control_code_root_retry")
    val state = hierarchy?.let(TicketViviPageEnforcer::classifyForRecovery)
    if (state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
      controlCodeFastTargetsForHierarchy(hierarchy)?.let {
        markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
        markControlCodeModeEntered("control_code_request_popup_already_open")
        return it
      }
    }
    val ready = when (state) {
      TicketViviRecoveryState.TICKET_DETAIL -> true
      TicketViviRecoveryState.CONTROL_CODE_RESULT -> healGeneratedControlCodeResultForRequest(
        hierarchy, "control_code_request_previous_result_visible", phases, requestStartedAtMillis
      )
      else -> recoverTicketDetailForControlCodeRequest(
        phases, requestStartedAtMillis,
        "control_code_request_recover_${state?.name?.lowercase() ?: "root_unavailable"}",
        launchVivi = state == null || state == TicketViviRecoveryState.OUTSIDE_VIVI || state == TicketViviRecoveryState.UNKNOWN_VIVI
      )
    }
    if (!ready) {
      recordInputGateDecision(false, "control_code_phone_not_ready")
      return null
    }
    viviStateMemory.record(
      TicketViviRecoveryState.TICKET_DETAIL, null, "root", "control_code_request_verified"
    )
    return openControlCodePopupImmediateForRequest(phases, requestStartedAtMillis)
  }

  private suspend fun resolveControlCodeHierarchyForFastRequest(
    phases: MutableMap<String, Long>,
    retryPhasePrefix: String
  ): String? {
    var hierarchy = controlCodeRequestRootHierarchy(phases, "fast_find_button")
    if (!hierarchy.isNullOrBlank()) {
      return hierarchy
    }
    repeat(CONTROL_CODE_FAST_ROOT_RETRY_COUNT) { attempt ->
      delay((CONTROL_CODE_FAST_POLL_MILLIS / 2).coerceAtLeast(25L))
      hierarchy = controlCodeRequestRootHierarchy(phases, "${retryPhasePrefix}_${attempt + 1}")
      if (!hierarchy.isNullOrBlank()) {
        return hierarchy
      }
    }
    return null
  }

  private fun cachedControlCodePopupTargetsForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodePopupTransaction? {
    val surface = cachedControlCodePopupSurface() ?: return null
    controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
    recordInputGateDecision(allowed = true, reason = "control_code_request_popup_cached_after_root_miss")
    markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
    markControlCodeModeEntered("control_code_request_popup_cached_after_root_miss")
    return controlCodePopupTransactionForSurface(surface, "cached_root_surface")
  }

  private fun openedControlCodePopupTransactionTargets(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    source: String,
    eventReason: String,
    announceReady: Boolean = true
  ): FastControlCodePopupTransaction {
    val (width, height) = currentDisplaySize()
    val staticSubmit = TicketViviPageAction(
      x = (width * CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_X_FRACTION).roundToInt(),
      y = (height * CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_Y_FRACTION).roundToInt(),
      reason = "submit_control_code_popup_keyboard_free_geometry"
    )
    if (announceReady) {
      markOpenedControlCodePopupTransactionReady(phases, requestStartedAtMillis, source, eventReason)
    }
    return FastControlCodePopupTransaction(
      input = TicketViviPageAction(
        x = (width * CONTROL_CODE_FAST_POPUP_INPUT_X_FRACTION).roundToInt(),
        y = (height * CONTROL_CODE_FAST_POPUP_INPUT_Y_FRACTION).roundToInt(),
        reason = "focus_control_code_input_popup_transaction"
      ),
      submit = staticSubmit,
      inputSource = "deterministic_geometry:$source",
      submitSource = "deterministic_keyboard_free_geometry:$source"
    )
  }

  private fun markOpenedControlCodePopupTransactionReady(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    source: String,
    eventReason: String
  ) {
    controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
    recordInputGateDecision(allowed = true, reason = "control_code_popup_transaction_ready:$source")
    markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
    markControlCodeModeEntered("control_code_request_popup_transaction_$source")
    sendTicketStateEvent(
      ticketState = TICKET_PIXEL_STATE_CONTROL_POPUP,
      reason = eventReason,
      requestId = lastControlCodeRequestId.orEmpty()
    )
  }

  private suspend fun enterAndSubmitControlCodeDigitsFastForRequest(
    digits: String,
    transaction: FastControlCodePopupTransaction,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    var typeResult = measureInputPhase(phases, "root_virtual_keyboard_type") {
      executeRootControlCodeType(digits, transaction, "control_code_root_virtual_keyboard_type")
    }
    if (!typeResult.ok) {
      transaction.open?.let { open ->
        recordControlCodeSnapAttempt(
          rawX = open.x,
          rawY = open.y,
          candidateZone = transaction.openCandidateZone,
          snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
          accepted = false,
          reason = "native_open_type_failed:${typeResult.exitCode}",
          finalX = open.x,
          finalY = open.y,
          detectedButtonBounds = transaction.openDetectedButtonBounds
        )
      }
      recordInputGateDecision(allowed = false, reason = "control_code_root_virtual_keyboard_type_failed")
      recordTicketEvent(
        "control_code_root_virtual_keyboard_type_failed",
        "exit_code=${typeResult.exitCode} timeout=${typeResult.exitCode == 124} duration_ms=${typeResult.durationMs}"
      )
      return false
    }
    transaction.open?.let { open ->
      recordControlCodeSnapAttempt(
        rawX = open.x,
        rawY = open.y,
        candidateZone = transaction.openCandidateZone,
        snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
        accepted = true,
        reason = "native_open_type_completed",
        finalX = open.x,
        finalY = open.y,
        detectedButtonBounds = transaction.openDetectedButtonBounds
      )
      markControlCodeRequestPhase(phases, "native_open_type_completed", requestStartedAtMillis)
      recordTicketEvent(
        "control_code_popup_opened_keyboard_free",
        "native_root_keyboard_pre_registered"
      )
    }
    markControlCodeRequestPhase(phases, "first_digit_entry", requestStartedAtMillis)
    var valueProof = waitForEnteredControlCodeValueVisualProof(phases)
    if (valueProof == ControlCodeEnteredValueProof.STATIC_BLANK) {
      phases["control_code_root_retype_attempted"] = 1L
      recordTicketEvent("control_code_root_retype_attempted", "blank_unshifted_popup_proved")
      typeResult = measureInputPhase(phases, "root_virtual_keyboard_retype") {
        executeRootControlCodeType(
          digits,
          transaction.copy(open = null),
          "control_code_root_virtual_keyboard_retype"
        )
      }
      valueProof = if (typeResult.ok) {
        waitForEnteredControlCodeValueVisualProof(phases)
      } else {
        recordTicketEvent(
          "control_code_root_virtual_keyboard_retype_failed",
          "exit_code=${typeResult.exitCode} timeout=${typeResult.exitCode == 124} duration_ms=${typeResult.durationMs}"
        )
        ControlCodeEnteredValueProof.UNSAFE
      }
    }
    if (valueProof != ControlCodeEnteredValueProof.VALUE_READY) {
      recordInputGateDecision(allowed = false, reason = "control_code_entered_value_unproved")
      recordTicketEvent(
        "control_code_entered_value_unproved",
        "submit_blocked proof=${valueProof.name.lowercase()}"
      )
      return false
    }
    markControlCodeRequestPhase(phases, "digits_typed", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "ok_dispatch_started", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_root_value_proved_submit_dispatched",
      "virtual_hardware_keyboard digits=${digits.length} input=${transaction.inputSource} submit=${transaction.submitSource}"
    )
    val submitted = measureInputPhase(phases, "root_submit_after_value_proof") {
      tapControlCodePointWithoutKeyboard(
        transaction.submit.x,
        transaction.submit.y,
        "control_code_root_submit_after_value_proof"
      )
    }
    if (!submitted) {
      recordTicketEvent(
        "control_code_root_submit_dispatch_uncertain",
        "proved_value_submit_tap_unacknowledged; reconciling_visual_state"
      )
    }
    phases["control_code_submit_attempted"] = 1L
    if (submitted) {
      markControlCodeRequestPhase(phases, "ok_tapped", requestStartedAtMillis)
    } else {
      markControlCodeRequestPhase(phases, "ok_tap_unacknowledged", requestStartedAtMillis)
    }
    recordTicketEvent(
      "control_code_root_value_proved_submit_attempted",
      "acknowledged=$submitted digits=${digits.length} input=${transaction.inputSource} submit=${transaction.submitSource}"
    )
    return true
  }

  private suspend fun executeRootControlCodeType(
    digits: String,
    transaction: FastControlCodePopupTransaction,
    reason: String
  ): RootResult {
    return runSensitiveFastNonTouchScript(
      command = TicketControlCodeRootInput.buildTypeScript(
        digits = digits,
        inputX = transaction.input.x,
        inputY = transaction.input.y,
        openX = transaction.open?.x,
        openY = transaction.open?.y
      ),
      reason = reason,
      timeout = CONTROL_CODE_ROOT_TRANSACTION_TIMEOUT_MILLIS.milliseconds
    )
  }

  private suspend fun tapControlCodePointWithoutKeyboard(
    x: Int,
    y: Int,
    reason: String
  ): Boolean {
    recordTicketEvent("control_code_keyboard_free_tap", "reason=$reason source=root_panel_clamped")
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      val timeout = CONTROL_CODE_ROOT_SUBMIT_TIMEOUT_MILLIS.milliseconds
      inputRootExecutor.runScript(
        wrapNonTouchPanelSleepClamp(
          command = "input tap $x $y",
          postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS,
          commandTimeout = timeout
        ),
        timeout
      ).also { recordInputCommandResult(reason, it) }.ok
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  private suspend fun waitForEnteredControlCodeValueVisualProof(
    phases: MutableMap<String, Long>
  ): ControlCodeEnteredValueProof {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val proof = TicketControlCodeSubmitVisualProof()
    var consecutiveStaticBlankSamples = 0
    repeat(CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES) { sampleIndex ->
      val probeStartedAtMillis = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestControlCodeSubmitVisualProbe(
        "control_code_submit_layout_${sampleIndex + 1}"
      ) ?: run {
        phases["submit_after_digits_visual"] = SystemClock.elapsedRealtime() - startedAtMillis
        recordTicketEvent("control_code_submit_visual_fallback", "probe_unavailable")
        return ControlCodeEnteredValueProof.UNSAFE
      }
      val sample = waitForFreshControlCodeVisualProbe(
        probeStartedAtMillis,
        probeId,
        CONTROL_CODE_SUBMIT_VISUAL_PROBE_WAIT_MILLIS
      )
      val sampleResult = sample?.result ?: TicketControlCodeVisualClassifier.UNKNOWN
      val ready = proof.observe(probeId, sampleResult)
      if (ready) {
        phases["submit_after_digits_visual"] = SystemClock.elapsedRealtime() - startedAtMillis
        recordTicketEvent(
          "control_code_submit_visual_ready",
          "samples=$CONTROL_CODE_SUBMIT_VISUAL_REQUIRED_SAMPLES"
        )
        return ControlCodeEnteredValueProof.VALUE_READY
      }
      if (sampleResult == TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY) {
        phases["submit_after_digits_visual"] = SystemClock.elapsedRealtime() - startedAtMillis
        recordTicketEvent(
          "control_code_submit_visual_fallback",
          "sample=${sampleIndex + 1} result=keyboard_shifted"
        )
        return ControlCodeEnteredValueProof.UNSAFE
      }
      consecutiveStaticBlankSamples = if (
        sampleResult == TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY
      ) {
        consecutiveStaticBlankSamples + 1
      } else {
        0
      }
      if (consecutiveStaticBlankSamples >= CONTROL_CODE_SUBMIT_VISUAL_REQUIRED_SAMPLES) {
        recordTicketEvent(
          "control_code_submit_visual_blank",
          "samples=$consecutiveStaticBlankSamples unshifted_popup=true"
        )
        phases["submit_after_digits_visual"] = SystemClock.elapsedRealtime() - startedAtMillis
        return ControlCodeEnteredValueProof.STATIC_BLANK
      }
      if (sampleIndex + 1 < CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES) {
        recordTicketEvent(
          "control_code_submit_visual_transient",
          "sample=${sampleIndex + 1} result=$sampleResult"
        )
        delay(CONTROL_CODE_SUBMIT_VISUAL_SAMPLE_GAP_MILLIS)
      }
    }
    phases["submit_after_digits_visual"] = SystemClock.elapsedRealtime() - startedAtMillis
    recordTicketEvent(
      "control_code_submit_visual_fallback",
      "samples=$CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES result=unproved"
    )
    return ControlCodeEnteredValueProof.UNSAFE
  }

  private suspend fun waitForGeneratedControlCodeResultAfterSubmit(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    submittedDigits: String,
    timeoutMillis: Long,
    rootDumpTimeoutMillis: Long
  ): ControlCodeResultWaitOutcome {
    val startedAtMillis = SystemClock.elapsedRealtime()
    recordTicketEvent("control_code_waiting_result_marker", "await_phone_visual_generated_state_after_submit")
    recordTicketEvent(
      "control_code_after_ok_marker_settle",
      "settle_ms=$CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS"
    )
    if (CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS > 0L) {
      delay(CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS)
    }
    val deadlineAtMillis = startedAtMillis + timeoutMillis
    var popupRejectCount = 0L
    var lastObservedState = "not_run"
    var rawTicketVisualCount = 0L
    var rawTicketRootConfirmationAttempted = false
    var submitRetryAttempted = false
    var popupRejectCountAfterRetry = 0L
    while (SystemClock.elapsedRealtime() < deadlineAtMillis) {
      val visualProbeStartedAtMillis = SystemClock.elapsedRealtime()
      val visualProbeId = rootHardwareH264CaptureEngine
        .requestControlCodeRequestVisualProbe("control_code_after_ok_visual_state")
      if (visualProbeId == null) {
        lastObservedState = "probe_unavailable"
        phases["control_code_visual_probe_unavailable"] = SystemClock.elapsedRealtime() - startedAtMillis
        recordTicketEvent("control_code_visual_probe_unavailable", "fall_back_to_terminal_root")
        break
      }
      val visualProbe = waitForFreshControlCodeVisualProbe(
        visualProbeStartedAtMillis,
        visualProbeId,
        CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS
      )
      if (visualProbe != null) {
        lastObservedState = visualProbe.result
        when {
          visualProbe.result == "generated" -> {
            val visualMarker = confirmGeneratedControlCodeResultForBrowser(
              value = "",
              hierarchy = CONTROL_CODE_MARKER_RESULT_HIERARCHY,
              phases = phases,
              requestStartedAtMillis = requestStartedAtMillis,
              waitStartedAtMillis = startedAtMillis,
              phase = "wait_result_phone_visual_generated_state",
              modeReason = "control_code_request_phone_visual_generated_after_submit",
              eventValue = "phone_visual_generated_after_submit",
              resultProof = "phone_visual"
            )
            phases["control_code_visual_popup_reject_count"] = popupRejectCount
            return ControlCodeResultWaitOutcome(generated = visualMarker, failureReason = "")
          }
          visualProbe.result == "raw_ticket" -> {
            rawTicketVisualCount += 1L
            phases["control_code_visual_raw_ticket_state"] = SystemClock.elapsedRealtime() - startedAtMillis
            if (!rawTicketRootConfirmationAttempted) {
              rawTicketRootConfirmationAttempted = true
              val hierarchy = controlCodeRequestRootHierarchy(
                phases,
                "wait_result_raw_ticket_root",
                timeoutMillis = maxOf(rootDumpTimeoutMillis, CONTROL_CODE_RAW_TICKET_ROOT_CONFIRM_TIMEOUT_MILLIS)
              ).orEmpty()
              val state = if (hierarchy.isBlank()) {
                TicketViviRecoveryState.UNKNOWN_VIVI
              } else {
                TicketViviPageEnforcer.classifyForRecovery(hierarchy)
              }
              recordTicketEvent(
                "control_code_visual_raw_ticket_root_confirm",
                "state=${state.name} hierarchy_len=${hierarchy.length}"
              )
              if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
                val visualMarker = confirmGeneratedControlCodeResultForBrowser(
                  value = "",
                  hierarchy = hierarchy,
                  phases = phases,
                  requestStartedAtMillis = requestStartedAtMillis,
                  waitStartedAtMillis = startedAtMillis,
                  phase = "wait_result_phone_visual_generated_state",
                  modeReason = "control_code_request_phone_visual_root_confirmed_after_submit",
                  eventValue = "phone_visual_raw_ticket_root_confirmed_after_submit",
                  resultProof = "phone_visual_root_confirmed"
                )
                phases["control_code_visual_popup_reject_count"] = popupRejectCount
                return ControlCodeResultWaitOutcome(generated = visualMarker, failureReason = "")
              }
            }
            if (rawTicketVisualCount == CONTROL_CODE_RAW_TICKET_VISUAL_REJECT_LOG_COUNT) {
              phases["control_code_visual_raw_ticket_after_submit_rejected"] = SystemClock.elapsedRealtime() - startedAtMillis
              recordTicketEvent(
                "control_code_visual_raw_ticket_after_submit_wait",
                "count=$rawTicketVisualCount root_confirmed=false"
              )
            }
          }
          visualProbe.result == "control_popup" -> {
            popupRejectCount += 1L
            if (submitRetryAttempted) {
              popupRejectCountAfterRetry += 1L
            }
            phases["control_code_visual_popup_still_open"] = popupRejectCount
            if (popupRejectCount <= 3L || popupRejectCount % 10L == 0L) {
              recordTicketEvent(
                "control_code_visual_popup_still_open",
                "count=$popupRejectCount reason=${visualProbe.reason}"
              )
            }
            if (
              !submitRetryAttempted &&
              popupRejectCount >= CONTROL_CODE_SUBMIT_RETRY_MIN_POPUP_SAMPLES &&
              SystemClock.elapsedRealtime() - startedAtMillis >= CONTROL_CODE_SUBMIT_RETRY_MIN_AGE_MILLIS
            ) {
              val retryTargetProof = waitForEnteredControlCodeValueVisualProof(phases)
              if (retryTargetProof != ControlCodeEnteredValueProof.VALUE_READY) {
                recordTicketEvent(
                  "control_code_submit_retry_not_attempted",
                  "entered_value_submit_target_unproved proof=${retryTargetProof.name.lowercase()}"
                )
                return ControlCodeResultWaitOutcome(
                  failureReason = "control_code_submit_retry_target_unproved"
                )
              }
              val retryTransaction = openedControlCodePopupTransactionTargets(
                phases = phases,
                requestStartedAtMillis = requestStartedAtMillis,
                source = "root_submit_retry_value_visual",
                eventReason = "control_code_submit_retry_transaction_ready",
                announceReady = false
              )
              submitRetryAttempted = true
              phases["control_code_submit_retry_attempted"] = 1L
              recordTicketEvent(
                "control_code_submit_retry_dispatched",
                "value_samples=$CONTROL_CODE_SUBMIT_VISUAL_REQUIRED_SAMPLES digits=${submittedDigits.length}"
              )
              val retried = measureInputPhase(phases, "root_submit_retry_after_value_proof") {
                tapControlCodePointWithoutKeyboard(
                  retryTransaction.submit.x,
                  retryTransaction.submit.y,
                  "control_code_root_submit_retry_after_value_proof"
                )
              }
              recordTicketEvent(
                "control_code_submit_retry_attempted",
                "value_samples=$CONTROL_CODE_SUBMIT_VISUAL_REQUIRED_SAMPLES acknowledged=$retried digits=${submittedDigits.length}"
              )
              if (!retried) {
                recordTicketEvent(
                  "control_code_submit_retry_dispatch_uncertain",
                  "tap_unacknowledged; reconciling_visual_state"
                )
              }
              popupRejectCountAfterRetry = 0L
            } else if (
              submitRetryAttempted &&
              popupRejectCountAfterRetry >= CONTROL_CODE_SUBMIT_RETRY_POST_POPUP_LIMIT
            ) {
              recordTicketEvent(
                "control_code_submit_retry_exhausted",
                "popup_samples_after_retry=$popupRejectCountAfterRetry"
              )
              return ControlCodeResultWaitOutcome(
                failureReason = "control_code_submit_still_open"
              )
            }
          }
          else -> {
            phases["control_code_visual_non_generated_state"] = SystemClock.elapsedRealtime() - startedAtMillis
          }
        }
      }
      delay(CONTROL_CODE_VISUAL_STATE_RETRY_MILLIS)
    }
    val finalHierarchy = controlCodeRequestRootHierarchy(
      phases,
      "wait_generated_result_final_root",
      timeoutMillis = maxOf(rootDumpTimeoutMillis, CONTROL_CODE_FAST_RESULT_FINAL_ROOT_DUMP_TIMEOUT_MILLIS)
    ).orEmpty()
    val finalState = if (finalHierarchy.isBlank()) {
      TicketViviRecoveryState.UNKNOWN_VIVI
    } else {
      TicketViviPageEnforcer.classifyForRecovery(finalHierarchy)
    }
    recordTicketEvent(
      "control_code_generated_state_final_root",
      "state=${finalState.name} hierarchy_len=${finalHierarchy.length} last_visual=$lastObservedState"
    )
    if (finalState == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      val rootMarker = confirmGeneratedControlCodeResultForBrowser(
        value = "",
        hierarchy = finalHierarchy,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        waitStartedAtMillis = startedAtMillis,
        phase = "wait_result_phone_root_generated_state",
        modeReason = "control_code_request_phone_final_root_confirmed",
        eventValue = "phone_final_root_confirmed_generated_after_submit",
        resultProof = "phone_visual_root_confirmed"
      )
      phases["control_code_visual_popup_reject_count"] = popupRejectCount
      return ControlCodeResultWaitOutcome(generated = rootMarker, failureReason = "")
    }
    phases["wait_result_phone_visual_generated_state"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    phases["control_code_visual_popup_reject_count"] = popupRejectCount
    val failureReason = when (finalState) {
      TicketViviRecoveryState.CONTROL_CODE_POPUP -> "control_code_submit_still_open"
      TicketViviRecoveryState.TICKET_DETAIL -> "control_code_not_generated"
      else -> "control_code_generated_state_timeout"
    }
    recordTicketEvent(
      "control_code_generated_state_timeout",
      "last_state=$lastObservedState final_state=${finalState.name} popup_rejects=$popupRejectCount digits=${submittedDigits.length} root_dump_timeout_ms=$rootDumpTimeoutMillis"
    )
    return ControlCodeResultWaitOutcome(
      failureReason = failureReason,
      failureHierarchy = finalHierarchy
    )
  }

  private suspend fun waitForFreshControlCodeVisualProbe(
    visualProbeStartedAtMillis: Long,
    expectedProbeId: Long,
    timeoutMillis: Long
  ): TicketControlCodeVisualProbe? {
    val deadlineAtMillis = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadlineAtMillis) {
      rootHardwareH264CaptureEngine
        .recentControlCodeVisualProbeAfter(expectedProbeId, visualProbeStartedAtMillis)
        ?.let { return it }
      delay(CONTROL_CODE_VISUAL_STATE_POLL_MILLIS)
    }
    return rootHardwareH264CaptureEngine.recentControlCodeVisualProbeAfter(
      expectedProbeId,
      visualProbeStartedAtMillis
    )
  }

  private suspend fun confirmGeneratedControlCodeResultForBrowser(
    value: String,
    hierarchy: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    waitStartedAtMillis: Long,
    phase: String,
    modeReason: String,
    eventValue: String,
    resultProof: String = "phone_visual"
  ): GeneratedControlCodeResult {
    phases[phase] = (SystemClock.elapsedRealtime() - waitStartedAtMillis).coerceAtLeast(0L)
    markControlCodeRequestPhase(phases, "result_first_visible", requestStartedAtMillis)
    markControlCodeModeEntered(modeReason)
    rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
    recordTicketEvent("control_code_request_result_detected", eventValue)
    val watermark = markerFirstControlCodeFrameWatermarkForBrowser(
      reason = "control_code_result_after_phone_visual_proof",
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis
    )
    return GeneratedControlCodeResult(
      value = value,
      hierarchy = hierarchy,
      streamEpoch = watermark.first,
      minFrameSequence = watermark.second,
      resultProof = resultProof,
      resultProofAtMillis = System.currentTimeMillis()
    )
  }

  private fun markerFirstControlCodeFrameWatermarkForBrowser(
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Pair<Long, Long> {
    rootHardwareH264CaptureEngine.requestControlCodeRequestVisualProbe(
      "control_code_browser_marker"
    )
    val watermark = requestFreshControlCodeFrameWatermark(reason)
    phases["wait_result_browser_frame"] = 0L
    markControlCodeRequestPhase(phases, "result_marker_frame_requested", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "result_marker_frame_ready", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_request_result_marker_ready",
      "epoch=${watermark.first} min_sequence=${watermark.second} reason=$reason"
    )
    return watermark
  }

  private suspend fun captureGeneratedControlCodeImageBytes(
    hierarchy: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    cropToControlCodeGraphic: Boolean = true,
    maxOutputWidth: Int? = null
  ): ByteArray? {
    val result = measureInputPhase(phases, "capture_result_image_png") {
      val (sourceWidth, sourceHeight) = currentDisplaySize()
      val targetWidth = maxOutputWidth
        ?.takeIf { it > 0 && sourceWidth > it }
        ?: sourceWidth
      val targetHeight = if (targetWidth == sourceWidth) {
        sourceHeight
      } else {
        ((sourceHeight.toLong() * targetWidth.toLong()) / sourceWidth.toLong()).coerceAtLeast(1L).toInt()
      }
      rootHardwareH264CaptureEngine.captureSecurePngBase64(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        targetWidth = targetWidth,
        targetHeight = targetHeight
      )
    }
    if (!result.ok) {
      recordTicketEvent(
        "control_code_result_image_capture_failed",
        result.stderr.ifBlank { result.stdout }.takeLast(180)
      )
      return null
    }
    val fullImage = decodeBase64Png(result.stdout)
    if (fullImage == null) {
      recordTicketEvent("control_code_result_image_capture_failed", "decode_png_failed")
      return null
    }
    val bounds = if (cropToControlCodeGraphic) {
      TicketViviPageEnforcer.controlCodeResultGraphicBoundsForHierarchy(hierarchy)
    } else {
      null
    }
    val cropped = if (cropToControlCodeGraphic) {
      cropControlCodeImage(fullImage, bounds) ?: fullImage
    } else {
      fullImage
    }
    markControlCodeRequestPhase(phases, "result_image_png_ready", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_result_image_captured",
      "bytes=${cropped.size} cropped=${cropToControlCodeGraphic && bounds != null}"
    )
    return cropped
  }

  private fun decodeBase64Png(stdout: String): ByteArray? {
    val payload = extractMarkedPngBase64(stdout)
    val bytes = decodeBase64Bytes(payload) ?: return null
    return bytes.takeIf { looksLikePng(it) }
  }

  private fun extractMarkedPngBase64(stdout: String): String {
    val start = stdout.indexOf(PNG_BASE64_BEGIN)
    if (start < 0) {
      return stdout
    }
    val payloadStart = start + PNG_BASE64_BEGIN.length
    val end = stdout.indexOf(PNG_BASE64_END, payloadStart)
    return if (end >= 0) {
      stdout.substring(payloadStart, end)
    } else {
      stdout.substring(payloadStart)
    }
  }

  private fun decodeBase64Bytes(stdout: String): ByteArray? {
    val encoded = buildString(stdout.length) {
      stdout.forEach { char ->
        if (!char.isWhitespace()) {
          append(char)
        }
      }
    }
    if (encoded.isBlank()) {
      return null
    }
    return runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
  }

  private fun isMostlyBlankPng(image: ByteArray): Boolean {
    val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return true
    return try {
      val width = bitmap.width
      val height = bitmap.height
      if (width <= 0 || height <= 0) {
        return true
      }
      val stepX = (width / 96).coerceAtLeast(1)
      val stepY = (height / 160).coerceAtLeast(1)
      var sampled = 0
      var nonDark = 0
      var nonWhite = 0
      var y = 0
      while (y < height) {
        var x = 0
        while (x < width) {
          val pixel = bitmap.getPixel(x, y)
          val red = Color.red(pixel)
          val green = Color.green(pixel)
          val blue = Color.blue(pixel)
          if (red > 32 || green > 32 || blue > 32) {
            nonDark += 1
          }
          if (red < 245 || green < 245 || blue < 245) {
            nonWhite += 1
          }
          sampled += 1
          x += stepX
        }
        y += stepY
      }
      sampled == 0 || nonDark < sampled / 100 || nonWhite < sampled / 100
    } finally {
      bitmap.recycle()
    }
  }

  private fun cropControlCodeImage(image: ByteArray, bounds: TicketViviGraphicBounds?): ByteArray? {
    bounds ?: return image
    val source = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return image
    return try {
      val left = (bounds.left - CONTROL_CODE_RESULT_IMAGE_CROP_PADDING).coerceIn(0, (source.width - 1).coerceAtLeast(0))
      val top = (bounds.top - CONTROL_CODE_RESULT_IMAGE_CROP_PADDING).coerceIn(0, (source.height - 1).coerceAtLeast(0))
      val right = (bounds.right + CONTROL_CODE_RESULT_IMAGE_CROP_PADDING).coerceIn(left + 1, source.width)
      val bottom = (bounds.bottom + CONTROL_CODE_RESULT_IMAGE_CROP_PADDING).coerceIn(top + 1, source.height)
      if (right - left < CONTROL_CODE_RESULT_IMAGE_MIN_CROP_SIZE || bottom - top < CONTROL_CODE_RESULT_IMAGE_MIN_CROP_SIZE) {
        return image
      }
      val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
      try {
        val output = ByteArrayOutputStream()
        if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) {
          return image
        }
        output.toByteArray().takeIf { looksLikePng(it) && it.isNotEmpty() } ?: image
      } finally {
        if (cropped !== source) {
          cropped.recycle()
        }
      }
    } catch (error: Throwable) {
      recordTicketEvent("control_code_result_image_crop_failed", error.message ?: error::class.java.simpleName)
      image
    } finally {
      source.recycle()
    }
  }

  private fun looksLikePng(bytes: ByteArray): Boolean {
    return bytes.size >= 8 &&
      bytes[0] == 0x89.toByte() &&
      bytes[1] == 0x50.toByte() &&
      bytes[2] == 0x4E.toByte() &&
      bytes[3] == 0x47.toByte() &&
      bytes[4] == 0x0D.toByte() &&
      bytes[5] == 0x0A.toByte() &&
      bytes[6] == 0x1A.toByte() &&
      bytes[7] == 0x0A.toByte()
  }

  private suspend fun controlCodeRequestRootHierarchy(
    phases: MutableMap<String, Long>,
    phase: String,
    timeoutMillis: Long = CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS
  ): String? {
    return measureInputPhase(phases, phase) {
      observeRootViviState(
        reason = "control_code_request:$phase",
        timeoutMillis = timeoutMillis
      ).hierarchy
    }
  }

  private fun controlCodeFastTargetsForHierarchy(hierarchy: String): FastControlCodePopupTransaction? {
    val surface = TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)
    if (surface != null) {
      rememberControlCodePopupSurface(surface)
      return controlCodePopupTransactionForSurface(surface, "root_hierarchy_surface")
    }
    val input = TicketViviPageEnforcer.controlCodeInputActionLooseForHierarchy(hierarchy) ?: return null
    val submit = TicketViviPageEnforcer.controlCodeSubmitActionLooseForHierarchy(hierarchy) ?: return null
    return FastControlCodePopupTransaction(
      input = input,
      submit = submit,
      inputSource = "root_hierarchy_loose",
      submitSource = "root_hierarchy_loose"
    )
  }

  private fun controlCodePopupTransactionForSurface(
    surface: TicketViviControlCodePopupSurface,
    source: String
  ): FastControlCodePopupTransaction {
    return FastControlCodePopupTransaction(
      input = surface.input,
      submit = surface.submit,
      inputSource = "root_hierarchy:$source",
      submitSource = "root_hierarchy:$source"
    )
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
    controlCodeRecoveryBlockReason()?.let { reason ->
      return ControlCodeImmediateStartDecision(false, reason)
    }
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      return ControlCodeImmediateStartDecision(false, "control_code_immediate_stream_inactive")
    }
    if (ticketSessionState != TICKET_SESSION_LIVE) {
      return ControlCodeImmediateStartDecision(false, "control_code_immediate_ticket_state_stale:$ticketSessionState")
    }
    val nowMillis = SystemClock.elapsedRealtime()
    val current = viviStateMemory.current()
    val currentAge = ageMillis(current.observedAtMillis, nowMillis)
    if (
      currentViviStateIsInconclusiveFastObservation(current) &&
      recentLiveRawTicketProofForControlCode(
        nowMillis,
        CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
      ) != null
    ) {
      return ControlCodeImmediateStartDecision(true, "control_code_button_immediate_live_stream_recent_ticket_detail")
    }
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
    if (
      current.state == TicketViviRecoveryState.TICKET_DETAIL &&
      currentAge != null &&
      currentAge <= CONTROL_CODE_STALE_PREPARE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
    ) {
      return ControlCodeImmediateStartDecision(true, "control_code_button_immediate_stale_prepare_ticket_detail")
    }
    if (viviStateMemory.recentTicketDetailWithin(CONTROL_CODE_STALE_PREPARE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS) != null) {
      return ControlCodeImmediateStartDecision(true, "control_code_button_immediate_stale_prepare_ticket_detail")
    }
    return ControlCodeImmediateStartDecision(false, "control_code_immediate_no_recent_ticket_detail")
  }

  private suspend fun healGeneratedControlCodeResultForRequest(
    generatedHierarchy: String,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    freshFrameRequired: Boolean = true
  ): Boolean {
    var hierarchy = generatedHierarchy
    // CONTROL_CODE_GENERATED_HEAL_MAX_CLOSE_ATTEMPTS = 2 keeps request-time healing bounded.
    repeat(CONTROL_CODE_GENERATED_HEAL_MAX_CLOSE_ATTEMPTS) { attempt ->
      if (hierarchy.isBlank()) {
        recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
        recordTicketEvent("control_code_generated_heal_failed", "hierarchy_unavailable")
        return false
      }
      val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
      if (state == TicketViviRecoveryState.TICKET_DETAIL) {
        recordTicketEvent("control_code_generated_heal_ready", "already_ticket_detail")
        return true
      }
      if (state != TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
        recordTicketEvent("control_code_generated_heal_failed", "state=${state.name}")
        return false
      }
      rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
      val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
        ?: controlCodeResultGeometryCloseAction()
      val closeStartedAtMillis = SystemClock.elapsedRealtime()
      val closeSucceeded = sendFastGeneratedResultCloseTap(
        action = action,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        commandReason = "control_code_generated_heal_close_${attempt + 1}"
      )
      if (!closeSucceeded) {
        recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
        recordTicketEvent("control_code_generated_heal_failed", "close_failed_${attempt + 1}")
        return false
      }
      requestKeyFrame("control_code_generated_heal_close")
      val cleanState = waitForCleanTicketSurfaceFast(
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS
      )
      if (cleanState == TicketViviRecoveryState.TICKET_DETAIL) {
        markControlCodeRequestPhase(phases, "phone_raw_recovered", requestStartedAtMillis)
        recordTicketEvent("control_code_generated_heal_ready", "closed_after_${attempt + 1}")
        return completeFastVerifiedTicketDetailControlExitCleanup(
          reason = reason,
          closeAction = action.reason,
          startedAtMillis = closeStartedAtMillis,
          firstVerificationResult = "generated_heal_surface_clean",
          freshFrameRequested = freshFrameRequired
        )
      }
      hierarchy = controlCodeRequestRootHierarchy(phases, "generated_heal_verify_${attempt + 1}").orEmpty()
    }
    recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
    recordTicketEvent("control_code_generated_heal_failed", "phone_stuck_on_generated_code")
    return false
  }

  private suspend fun returnControlCodeSurfaceToRawTicket(
    generatedHierarchy: String,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    reuseGeneratedProof: Boolean = false
  ): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    if (generatedHierarchy == CONTROL_CODE_MARKER_RESULT_HIERARCHY) {
      rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
      val cleanupStart = beginGeneratedControlCodeResultFastClose(
        generatedHierarchy = generatedHierarchy,
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        reuseGeneratedProof = reuseGeneratedProof
      )
      return finishGeneratedControlCodeResultFastCleanup(cleanupStart, reason, phases, requestStartedAtMillis)
    }
    val hierarchy = if (generatedHierarchy.isNotBlank()) {
      generatedHierarchy
    } else {
      controlExitHierarchy().orEmpty()
    }
    if (hierarchy.isBlank()) {
      val cleanState = waitForCleanTicketSurfaceFast(
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS
      )
      return if (cleanState == TicketViviRecoveryState.TICKET_DETAIL) {
        completeFastVerifiedTicketDetailControlExitCleanup(reason, "none", startedAtMillis, "surface_clean")
      } else {
        false
      }
    }

    return when (val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)) {
      TicketViviRecoveryState.TICKET_DETAIL -> completeFastVerifiedTicketDetailControlExitCleanup(
        reason = reason,
        closeAction = "none",
        startedAtMillis = startedAtMillis,
        firstVerificationResult = state.name
      )
      TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
        rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
        val cleanupStart = beginGeneratedControlCodeResultFastClose(
          generatedHierarchy = hierarchy,
          reason = reason,
          phases = phases,
          requestStartedAtMillis = requestStartedAtMillis,
          reuseGeneratedProof = reuseGeneratedProof
        )
        finishGeneratedControlCodeResultFastCleanup(cleanupStart, reason, phases, requestStartedAtMillis)
      }
      TicketViviRecoveryState.CONTROL_CODE_POPUP -> {
        rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_POPUP)
        val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(hierarchy)
          ?: cachedControlCodePopupSurface()?.close
          ?: return false
        val closeSucceeded = sendFastGeneratedResultCloseTap(
          action = action,
          phases = phases,
          requestStartedAtMillis = requestStartedAtMillis,
          commandReason = "control_code_return_raw_popup_close"
        )
        if (!closeSucceeded) {
          return false
        }
        requestKeyFrame("control_code_return_raw_popup_close")
        val cleanState = waitForCleanTicketSurfaceFast(
          reason = reason,
          phases = phases,
          requestStartedAtMillis = requestStartedAtMillis,
          timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS
        )
        if (cleanState == TicketViviRecoveryState.TICKET_DETAIL) {
          completeFastVerifiedTicketDetailControlExitCleanup(
            reason = reason,
            closeAction = action.reason,
            startedAtMillis = startedAtMillis,
            firstVerificationResult = "surface_clean",
            detectedState = TicketViviRecoveryState.CONTROL_CODE_POPUP.name
          )
        } else {
          false
        }
      }
      else -> false
    }
  }

  private suspend fun beginGeneratedControlCodeResultFastClose(
    generatedHierarchy: String,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    reuseGeneratedProof: Boolean
  ): FastControlCodeCleanupStart {
    val startedAtMillis = SystemClock.elapsedRealtime()
    markControlCodeRequestPhase(phases, "cleanup_started", requestStartedAtMillis)
    recordTicketEvent("control_code_fast_cleanup_phase", "result_ready_for_delivery")
    updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)
    sendTicketStateEvent(
      ticketState = TICKET_PIXEL_STATE_RETURNING_RAW,
      reason = reason,
      requestId = lastControlCodeRequestId.orEmpty()
    )

    // This method is only entered after the same request has already produced either a
    // fresh generated-frame marker or a hierarchy classified as the generated result.
    // Re-probing before the close used to spend several hundred milliseconds rediscovering
    // that fact and delayed the inline X after the browser had safely frozen its copy.
    val detectedState = if (reuseGeneratedProof) {
      if (generatedHierarchy == CONTROL_CODE_MARKER_RESULT_HIERARCHY) {
        TicketViviRecoveryState.CONTROL_CODE_RESULT
      } else {
        TicketViviPageEnforcer.classifyForRecovery(generatedHierarchy)
      }
    } else {
      observeFreshControlCodeCleanupState("control_code_fast_cleanup_before_close")
        ?: TicketViviRecoveryState.UNKNOWN_VIVI
    }
    recordTicketEvent(
      if (reuseGeneratedProof) "control_code_fast_cleanup_proof_reused" else "control_code_fast_cleanup_proof_refreshed",
      "state=${detectedState.name} source=${if (!reuseGeneratedProof) "cleanup_visual" else if (generatedHierarchy == CONTROL_CODE_MARKER_RESULT_HIERARCHY) "phone_visual_marker" else "root_hierarchy"}"
    )
    if (detectedState == TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent("control_code_fast_cleanup_close_skipped", "fresh_state=ticket_detail")
      return FastControlCodeCleanupStart(
        startedAtMillis = startedAtMillis,
        closeAction = "none",
        action = null,
        closeSucceeded = true,
        fallbackState = null
      )
    }
    if (detectedState != TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      recordTicketEvent(
        "control_code_fast_cleanup_close_blocked_unproved",
        "fresh_state=${detectedState.name}"
      )
      return FastControlCodeCleanupStart(
        startedAtMillis = startedAtMillis,
        closeAction = "none",
        action = null,
        closeSucceeded = false,
        fallbackState = detectedState
      )
    }
    rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)

    val action = TicketViviPageEnforcer.controlCodeExitCloseActionForHierarchy(generatedHierarchy)
      ?: controlCodeResultGeometryCloseAction()
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
      return false
    }
    recordTicketEvent(
      "control_code_fast_cleanup_phase",
      if (cleanupStart.action == null) "inline_close_not_needed" else "inline_close_acknowledged"
    )
    var cleanState = waitForCleanTicketSurfaceFast(
      reason = reason,
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS,
      returnOnFreshGeneratedResult = true
    )
    if (cleanState == TicketViviRecoveryState.CONTROL_CODE_RESULT && cleanupStart.action != null) {
      recordTicketEvent(
        "control_code_fast_cleanup_close_retry",
        "fresh_state=control_code_result action=${cleanupStart.action.reason}"
      )
      val retrySucceeded = sendFastGeneratedResultCloseTap(
        action = cleanupStart.action,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        commandReason = "control_code_fast_cleanup_close_retry"
      )
      if (retrySucceeded) {
        requestKeyFrame("control_code_fast_cleanup_close_retry")
        cleanState = waitForCleanTicketSurfaceFast(
          reason = reason,
          phases = phases,
          requestStartedAtMillis = requestStartedAtMillis,
          timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS
        )
      }
    }
    if (cleanState != TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent("control_code_fast_cleanup_fallback", "surface=${cleanState?.name ?: "unavailable"}")
      return false
    }
    markControlCodeRequestPhase(phases, "phone_raw_recovered", requestStartedAtMillis)
    return completeFastVerifiedTicketDetailControlExitCleanup(
      reason = reason,
      closeAction = cleanupStart.closeAction,
      startedAtMillis = cleanupStart.startedAtMillis,
      firstVerificationResult = "inline_close_h264_verified",
      freshFrameRequested = false
    )
  }

  private suspend fun sendFastGeneratedResultCloseTap(
    action: TicketViviPageAction,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    commandReason: String
  ): Boolean {
    val closeCommand = "input tap ${action.x} ${action.y}"
    val inlineResultClose =
      action.reason == "close_control_code_result" ||
        action.reason == "geometry_close_control_code_result"
    recordTicketEvent(
      "control_code_fast_cleanup_close_dispatched",
      "action=${action.reason} x=${action.x} y=${action.y} bounds=${action.bounds ?: "geometry"}"
    )
    val tap = measureInputPhase(phases, commandReason) {
      if (inlineResultClose) {
        runFastInlineControlResultCloseInput(closeCommand, commandReason)
      } else {
        runFastNonTouchInput(
          closeCommand,
          commandReason,
          postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS
        )
      }
    }
    if (!tap.ok) {
      recordTicketEvent("control_code_fast_cleanup_close_failed", "reason=$commandReason duration_ms=${tap.durationMs}")
      return false
    }
    markControlCodeRequestPhase(phases, "cleanup_close_tap_sent", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "close_tap_sent", requestStartedAtMillis)
    recordTicketEvent("control_code_fast_cleanup_phase", "close_tap_sent action=${action.reason}")
    return true
  }

  private suspend fun observeFreshControlCodeCleanupState(reason: String): TicketViviRecoveryState? {
    var lastState: TicketViviRecoveryState? = null
    repeat(CONTROL_CODE_FAST_CLOSE_PROOF_MAX_SAMPLES) { sampleIndex ->
      val probeStartedAtMillis = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestControlCodeCleanupVisualProbe(
        "${reason}_${sampleIndex + 1}"
      ) ?: return lastState
      val visualProbe = waitForFreshControlCodeVisualProbe(
        visualProbeStartedAtMillis = probeStartedAtMillis,
        expectedProbeId = probeId,
        timeoutMillis = CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS
      )
      val state = when (visualProbe?.result) {
        TicketControlCodeVisualClassifier.RAW_TICKET -> TicketViviRecoveryState.TICKET_DETAIL
        TicketControlCodeVisualClassifier.GENERATED -> TicketViviRecoveryState.CONTROL_CODE_RESULT
        TicketControlCodeVisualClassifier.CONTROL_POPUP -> TicketViviRecoveryState.CONTROL_CODE_POPUP
        else -> TicketViviRecoveryState.UNKNOWN_VIVI
      }
      lastState = state
      recordTicketEvent(
        "control_code_cleanup_fresh_visual_state",
        "reason=$reason sample=${sampleIndex + 1} state=${state.name}"
      )
      if (state != TicketViviRecoveryState.UNKNOWN_VIVI) {
        return state
      }
      if (sampleIndex + 1 < CONTROL_CODE_FAST_CLOSE_PROOF_MAX_SAMPLES) {
        delay(CONTROL_CODE_VISUAL_STATE_POLL_MILLIS)
      }
    }
    return lastState
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
    timeoutMillis: Long,
    returnOnFreshGeneratedResult: Boolean = false
  ): TicketViviRecoveryState? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadlineMillis = startedAtMillis + timeoutMillis.coerceAtLeast(CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS)
    var lastState: TicketViviRecoveryState? = null
    val visualProof = TicketControlCodeCleanupVisualProof(CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT)
    while (SystemClock.elapsedRealtime() <= deadlineMillis) {
      val visualProbeStartedAtMillis = SystemClock.elapsedRealtime()
      val visualProbeId = rootHardwareH264CaptureEngine
        .requestControlCodeCleanupVisualProbe(
          "control_code_cleanup_visual_verify_${visualProof.consecutiveRawTicketSamples + 1}"
        )
      if (visualProbeId == null) {
        phases["cleanup_visual_verify"] = SystemClock.elapsedRealtime() - startedAtMillis
        recordTicketEvent("control_code_fast_cleanup_visual_unavailable", "reason=$reason")
        return null
      }
      val remainingMillis = (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val visualProbe = waitForFreshControlCodeVisualProbe(
        visualProbeStartedAtMillis = visualProbeStartedAtMillis,
        expectedProbeId = visualProbeId,
        timeoutMillis = minOf(CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS, remainingMillis)
      )
      if (visualProbe == null) {
        recordTicketEvent(
          "control_code_fast_cleanup_visual_retry",
          "reason=$reason probe=$visualProbeId result=timeout"
        )
        continue
      }
      val state = when (visualProbe.result) {
        TicketControlCodeVisualClassifier.RAW_TICKET -> TicketViviRecoveryState.TICKET_DETAIL
        TicketControlCodeVisualClassifier.GENERATED -> TicketViviRecoveryState.CONTROL_CODE_RESULT
        TicketControlCodeVisualClassifier.CONTROL_POPUP -> TicketViviRecoveryState.CONTROL_CODE_POPUP
        else -> TicketViviRecoveryState.UNKNOWN_VIVI
      }
      val rawTicketConfirmed = visualProof.observe(visualProbe.result)
      lastState = state
      if (state == TicketViviRecoveryState.TICKET_DETAIL) {
        if (rawTicketConfirmed) {
          val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
          phases["cleanup_visual_verify"] = durationMillis
          markControlCodeRequestPhase(phases, "cleanup_clean_surface", requestStartedAtMillis)
          markControlCodeRequestPhase(phases, "raw_ticket_fast_proof", requestStartedAtMillis)
          recordTicketEvent(
            "control_code_fast_cleanup_phase",
            "clean_surface duration_ms=$durationMillis source=root_h264_visual probes=${visualProof.consecutiveRawTicketSamples}"
          )
          return state
        }
        val sampleGapRemainingMillis = CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS -
          (SystemClock.elapsedRealtime() - visualProbeStartedAtMillis)
        if (sampleGapRemainingMillis > 0L && SystemClock.elapsedRealtime() < deadlineMillis) {
          delay(minOf(sampleGapRemainingMillis, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
        }
        continue
      }
      if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
        if (returnOnFreshGeneratedResult) {
          val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
          phases["cleanup_visual_verify"] = durationMillis
          recordTicketEvent(
            "control_code_fast_cleanup_result_still_visible",
            "reason=$reason duration_ms=$durationMillis"
          )
          return state
        }
      }
      val sampleGapRemainingMillis = CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS -
        (SystemClock.elapsedRealtime() - visualProbeStartedAtMillis)
      if (sampleGapRemainingMillis > 0L && SystemClock.elapsedRealtime() < deadlineMillis) {
        delay(minOf(sampleGapRemainingMillis, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
      }
    }
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    phases["cleanup_visual_verify"] = durationMillis
    phases["cleanup_fast_verify"] = durationMillis
    recordTicketEvent(
      "control_code_fast_cleanup_visual_inconclusive",
      "reason=$reason state=${lastState?.name ?: "unavailable"} duration_ms=$durationMillis"
    )
    return if (lastState == TicketViviRecoveryState.TICKET_DETAIL) null else lastState
  }

  private suspend fun completeFastVerifiedTicketDetailControlExitCleanup(
    reason: String,
    closeAction: String,
    startedAtMillis: Long,
    firstVerificationResult: String,
    freshFrameRequested: Boolean = true,
    detectedState: String = TicketViviRecoveryState.CONTROL_CODE_RESULT.name
  ): Boolean {
    recordTicketEvent("control_code_fast_cleanup_phase", "cleanup_complete")
    return completeControlExitCleanup(
      reason = reason,
      detectedState = detectedState,
      closeAction = closeAction,
      startedAtMillis = startedAtMillis,
      verificationResult = firstVerificationResult,
      freshFrameRequested = freshFrameRequested
    )
  }

  private fun schedulePostRemoteTapForegroundCheck() {
    postRemoteTapForegroundCheckJob?.cancel()
    serviceScope.launch {
      delay(REMOTE_TAP_FOREGROUND_SETTLE_MILLIS)
      controlCodePhoneMutationLane.withOwnership {
        val violation = foregroundViolationReason(allowStartupSystemUi = false)
        cacheForegroundViolation(violation)
        if (violation == null) {
          refreshControlCodeModeAfterRemoteTap()
          return@withOwnership
        }
        recordTicketEvent("ticket_post_tap_foreground_violation", violation)
      }
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

  private fun sendTicketStateEvent(
    ticketState: String,
    reason: String,
    requestId: String = "",
    value: String = "",
    eventStreamEpoch: Long = streamEpoch,
    eventFrameSequence: Long = frameSequence,
    minFrameSequence: Long = 0L,
    resultProof: String = "",
    resultFrameEpoch: Long = 0L,
    resultMinFrameSequence: Long = 0L,
    resultProofAtMillis: Long = 0L,
    totalDurationMillis: Long = 0L,
    phases: Map<String, Long> = emptyMap()
  ) {
    val eventSeq = lastPixelTicketEventSeq + 1L
    val nowMillis = SystemClock.elapsedRealtime()
    lastPixelTicketEventSeq = eventSeq
    lastPixelTicketState = ticketState
    lastPixelTicketEventSentAtMillis = nowMillis
    val phaseJson = buildJsonObject {
      phases.forEach { (name, duration) -> put(name, duration) }
    }
    val message = buildJsonObject {
      put("type", "ticket_state_event")
      put("eventSeq", eventSeq)
      put("ticketState", ticketState)
      put("reason", reason)
      put("requestId", requestId)
      put("value", value)
      put("streamEpoch", eventStreamEpoch)
      put("frameSequence", eventFrameSequence)
      put("minFrameSequence", minFrameSequence)
      if (resultProof.isNotBlank()) {
        put("resultProof", resultProof)
        put("resultFrameEpoch", resultFrameEpoch)
        put("resultMinFrameSequence", resultMinFrameSequence)
        if (resultProofAtMillis > 0L) {
          put("resultProofAt", Instant.ofEpochMilli(resultProofAtMillis).toString())
        }
      }
      put("phoneUptimeMillis", nowMillis)
      put("totalDurationMillis", totalDurationMillis)
      put("phases", phaseJson)
    }.toString()
    enqueueTicketSpacetimePhoneMessage(message)
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "ticket_state_event",
      "seq=$eventSeq state=$ticketState reason=$reason request=$requestId epoch=$eventStreamEpoch frame=$eventFrameSequence min=$minFrameSequence"
    )
  }

  private suspend fun handleGenerateRigasSatiksmeQrBatch(
    replyClient: TicketWebSocket,
    batchId: String,
    jobs: List<RigasSatiksmeBatchJob>,
    ticketPriorityActive: Boolean
  ) {
    val cleanBatchId = batchId.trim().ifBlank { "rsbatch-${SystemClock.elapsedRealtime()}" }
    val cleanJobs = jobs.map {
      it.copy(requestId = it.requestId.trim(), digits = it.digits.trim(), createdAt = it.createdAt.trim())
    }.filter { it.requestId.isNotBlank() }
    if (cleanJobs.isEmpty()) {
      recordTicketEvent("rs_monthly_ticket_batch_rejected", "batch=$cleanBatchId reason=empty_jobs")
      return
    }

    cancelPendingRigasSatiksmeReturnCleanup("new_rs_monthly_ticket_batch")
    canceledRigasSatiksmeBatchIds.remove(cleanBatchId)
    protectedControlClients.add(replyClient)
    var pendingImmediateCleanup: PendingRigasSatiksmeReturnCleanup? = null
    var lastBatchRequestId = cleanJobs.last().requestId
    var lastBatchStartedAtMillis = SystemClock.elapsedRealtime()
    var lastBatchReason = "generated"
    var automationClaimed = false
    try {
      automationClaimed = true
      claimControlCodeAutomationForRequest()
      controlCodePhoneMutationLane.withOwnership {
        val batchPhases = linkedMapOf<String, Long>()
        lastRigasSatiksmeBatchId = cleanBatchId
        lastRigasSatiksmeBatchStatus = "running"
        lastRigasSatiksmeBatchActiveRequestId = null
        lastRigasSatiksmeBatchJobCount = cleanJobs.size
        lastRigasSatiksmeBatchCompletedCount = 0
        lastRigasSatiksmeBatchResultRequestId = null
        lastRigasSatiksmeBatchResultStatus = null
        lastRigasSatiksmeBatchResultReason = null
        lastRigasSatiksmeBatchCancelReason = null
        lastRigasSatiksmeBatchPhases = emptyMap()
        lastRigasSatiksmeBatchCompletedAtMillis = 0L
        broadcastStatus()

        recordTicketEvent("rs_monthly_ticket_batch_started", "batch=$cleanBatchId jobs=${cleanJobs.size}")
        markControlCodeTransition("rs_monthly_ticket_batch")
        for (job in cleanJobs) {
          if (canceledRigasSatiksmeBatchIds.contains(cleanBatchId)) {
            lastRigasSatiksmeBatchStatus = "canceled"
            lastRigasSatiksmeBatchActiveRequestId = null
            lastRigasSatiksmeBatchCancelReason = "ticket_lease_active"
            break
          }
          val startedAtMillis = SystemClock.elapsedRealtime()
          lastBatchStartedAtMillis = startedAtMillis
          lastBatchRequestId = job.requestId
          val phases = linkedMapOf<String, Long>()
          phases["phone_command_received"] = 0L
          lastRigasSatiksmeBatchActiveRequestId = job.requestId
          lastRigasSatiksmeBatchStatus = "running"
          broadcastStatus()

          if (!CONTROL_CODE_REQUEST_DIGITS_REGEX.matches(job.digits)) {
            val reason = "invalid_code"
            lastBatchReason = reason
            sendRigassatiksmeQrResult(
              requestId = job.requestId,
              ok = false,
              reason = reason,
              imageBytes = ByteArray(0),
              startedAtMillis = startedAtMillis,
              phases = phases,
              sourceApp = TicketScreenConfig.TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME,
              ticketFlow = TicketScreenConfig.TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY
            )
            lastRigasSatiksmeBatchCompletedCount += 1
            lastRigasSatiksmeBatchResultRequestId = job.requestId
            lastRigasSatiksmeBatchResultStatus = "failed"
            lastRigasSatiksmeBatchResultReason = reason
            batchPhases["job_${lastRigasSatiksmeBatchCompletedCount}_total"] =
              (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
            lastRigasSatiksmeBatchPhases = batchPhases.toMap()
            continue
          }

          recordTicketEvent(
            "rs_monthly_ticket_batch_job_started",
            "batch=$cleanBatchId request=${job.requestId} created_at=${job.createdAt.ifBlank { "missing" }}"
          )
          val outcome = RigasSatiksmeMonthlyTicketOperation(
            sourceApp = TicketScreenConfig.TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME,
            ticketFlow = TicketScreenConfig.TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY,
            runFlow = ::runRigasSatiksmeMonthlyTicketFlow,
            captureImage = ::captureRigasSatiksmeMonthlyTicketImageBytes,
            markPhase = ::markControlCodeRequestPhase
          ).run(
            cleanDigits = job.digits,
            phases = phases,
            requestStartedAtMillis = startedAtMillis,
            reusePreviousRigasSatiksmeQr = false
          )
          val totalDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
          lastBatchReason = outcome.reason
          lastRigasSatiksmeBatchCompletedCount += 1
          lastRigasSatiksmeBatchResultRequestId = job.requestId
          lastRigasSatiksmeBatchResultStatus = if (outcome.ok) "succeeded" else "failed"
          lastRigasSatiksmeBatchResultReason = outcome.reason
          lastRigasSatiksmeBatchPhases = outcome.phases
          batchPhases["job_${lastRigasSatiksmeBatchCompletedCount}_total"] = totalDurationMillis
          sendRigassatiksmeQrResult(
            requestId = job.requestId,
            ok = outcome.ok,
            reason = outcome.reason,
            imageBytes = outcome.imageBytes ?: ByteArray(0),
            startedAtMillis = startedAtMillis,
            phases = outcome.phases,
            sourceApp = outcome.sourceApp.ifBlank { TicketScreenConfig.TICKET_QR_RESULT_SOURCE_APP_RIGAS_SATIKSME },
            ticketFlow = outcome.ticketFlow.ifBlank { TicketScreenConfig.TICKET_QR_RESULT_FLOW_RIGAS_SATIKSME_ANDROID_MONTHLY }
          )
          recordTicketEvent(
            "rs_monthly_ticket_batch_job_finished",
            "batch=$cleanBatchId request=${job.requestId} ok=${outcome.ok} reason=${outcome.reason} duration_ms=$totalDurationMillis"
          )
          if (rigasSatiksmeFailureRequiresImmediateCleanup(outcome.reason)) {
            pendingImmediateCleanup = PendingRigasSatiksmeReturnCleanup(
              requestId = job.requestId,
              phases = phases,
              requestStartedAtMillis = startedAtMillis,
              reason = outcome.reason
            )
            break
          }
        }

        if (lastRigasSatiksmeBatchStatus != "canceled") {
          lastRigasSatiksmeBatchStatus = "completed"
        }
        lastRigasSatiksmeBatchActiveRequestId = null
        lastRigasSatiksmeBatchPhases = batchPhases.toMap()
        lastRigasSatiksmeBatchCompletedAtMillis = SystemClock.elapsedRealtime()
        if (pendingImmediateCleanup == null && ticketPriorityActive) {
          pendingImmediateCleanup = PendingRigasSatiksmeReturnCleanup(
            requestId = lastBatchRequestId,
            phases = linkedMapOf(),
            requestStartedAtMillis = lastBatchStartedAtMillis,
            reason = lastBatchReason
          )
        } else if (pendingImmediateCleanup == null) {
          scheduleRigasSatiksmeReturnCleanupAfterIdle(
            requestId = lastBatchRequestId,
            startedAtMillis = lastBatchStartedAtMillis,
            reason = lastBatchReason
          )
        }
        pendingImmediateCleanup?.let { cleanup ->
          completeRigasSatiksmeImmediateCleanup(cleanup)
          pendingImmediateCleanup = null
        }
        broadcastStatus()
      }
    } finally {
      if (automationClaimed) {
        releaseControlCodeAutomationForRequest()
      }
      protectedControlClients.remove(replyClient)
      canceledRigasSatiksmeBatchIds.remove(cleanBatchId)
    }
  }

  private fun sendControlCodeResult(
    requestId: String,
    ok: Boolean,
    reason: String,
    value: String,
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
    lastControlCodeRequestDurationMillis = totalDurationMillis
    lastControlCodeRequestPhases = phases.toMap()
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
      put("totalDurationMillis", totalDurationMillis)
      put("cleanupPending", cleanupPending)
      put("phases", phaseJson)
    }.toString()
    if (ok) {
      rememberControlCodeResult(requestId, message)
    }
    enqueueTicketSpacetimePhoneMessage(message)
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "control_code_result",
      "$requestId ok=$ok reason=$reason value_present=${value.isNotBlank()} duration_ms=$totalDurationMillis"
    )
    recordTicketEvent(
      "control_code_final_state",
      "request=$requestId status=${if (ok) "succeeded" else "failed"} reason=$reason duration_ms=$totalDurationMillis value_present=${value.isNotBlank()} cleanup_pending=$cleanupPending"
    )
    if (!ok && !cleanupPending && streamActive) {
      recordTicketEvent("foreground_guard_resumed_after_control_code_failure", reason)
      startForegroundGuard()
    }
    broadcastStatus()
  }

  private fun sendControlCodeProgress(requestId: String, status: String, reason: String) {
    val cleanRequestId = requestId.trim()
    if (cleanRequestId.isBlank()) return
    val message = buildJsonObject {
      put("type", "control_code_progress")
      put("requestId", cleanRequestId)
      put("status", status)
      put("reason", reason)
    }.toString()
    enqueueTicketSpacetimePhoneMessage(message)
    controlClientSnapshot().forEach { client -> client.sendText(message) }
  }

  private fun sendRigassatiksmeQrResult(
    requestId: String,
    ok: Boolean,
    reason: String,
    imageBytes: ByteArray,
    startedAtMillis: Long,
    phases: Map<String, Long>,
    imageMime: String = "image/png",
    sourceApp: String = "",
    ticketFlow: String = ""
  ) {
    val nowMillis = SystemClock.elapsedRealtime()
    val totalDurationMillis = if (startedAtMillis > 0L) {
      (nowMillis - startedAtMillis).coerceAtLeast(0L)
    } else {
      0L
    }
    val phaseJson = buildJsonObject {
      phases.forEach { (name, duration) -> put(name, duration) }
    }
    val imageBase64 = if (ok && imageBytes.isNotEmpty()) {
      Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    } else {
      ""
    }
    val accepted = ok && imageBase64.isNotBlank()
    val normalizedReason = normalizeRigassatiksmeQrResultReason(accepted, reason)
    val message = buildJsonObject {
      put("type", "rigassatiksme_qr_result")
      put("requestId", requestId)
      put("ok", accepted)
      put("accepted", accepted)
      put("reason", normalizedReason)
      put("imageMime", imageMime)
      put("imageBase64", imageBase64)
      if (sourceApp.isNotBlank()) {
        put("sourceApp", sourceApp)
      }
      if (ticketFlow.isNotBlank()) {
        put("ticketFlow", ticketFlow)
      }
      put("totalDurationMillis", totalDurationMillis)
      put("phases", phaseJson)
    }.toString()
    if (accepted) {
      rememberControlCodeResult(requestId, message)
    }
    enqueueTicketSpacetimePhoneMessage(message)
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "rigassatiksme_qr_result",
      "$requestId ok=$accepted reason=$normalizedReason sourceApp=${sourceApp.ifBlank { "missing" }} ticketFlow=${ticketFlow.ifBlank { "missing" }} bytes=${imageBytes.size} duration_ms=$totalDurationMillis"
    )
    broadcastStatus()
  }

  private fun normalizeRigassatiksmeQrResultReason(accepted: Boolean, reason: String): String {
    val cleanReason = reason.trim()
    return when {
      accepted -> cleanReason.ifBlank { "generated" }
      cleanReason.isBlank() || cleanReason == "generated" -> "qr_image_missing"
      else -> cleanReason
    }
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
    enqueueTicketSpacetimePhoneMessage(message)
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "control_code_cleanup_complete",
      "$requestId ok=$ok reason=$reason duration_ms=$totalDurationMillis"
    )
    recordTicketEvent(
      "control_code_cleanup_final_state",
      "request=$requestId status=${if (ok) "succeeded" else "failed"} reason=$reason duration_ms=$totalDurationMillis"
    )
    broadcastStatus()
  }

  private fun sendCachedControlCodeResult(requestId: String): Boolean {
    val id = requestId.takeIf { it.isNotBlank() } ?: return false
    val nowMillis = SystemClock.elapsedRealtime()
    val message = synchronized(recentControlCodeResultMessages) {
      pruneRecentControlCodeResultsLocked(nowMillis)
      recentControlCodeResultMessages[id]?.second
    } ?: return false
    duplicateControlCodeResultCount += 1
    lastDuplicateControlCodeRequestId = id
    lastDuplicateControlCodeResultAtMillis = SystemClock.elapsedRealtime()
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent("control_code_result_duplicate", id)
    broadcastStatus()
    return true
  }

  private fun controlCodeRequestDuplicateActiveOrCompleted(requestId: String): Boolean {
    val id = requestId.takeIf { it.isNotBlank() } ?: return false
    if (lastControlCodeRequestId != id) {
      return false
    }
    val nowMillis = SystemClock.elapsedRealtime()
    val completedAgeMillis = if (lastControlCodeRequestCompletedAtMillis > 0L) {
      nowMillis - lastControlCodeRequestCompletedAtMillis
    } else {
      Long.MAX_VALUE
    }
    val duplicate = when (lastControlCodeRequestStatus) {
      "running" -> true
      "succeeded" -> completedAgeMillis in 0..CONTROL_CODE_RESULT_CACHE_TTL_MILLIS
      else -> false
    }
    if (!duplicate) {
      return false
    }
    duplicateControlCodeResultCount += 1
    lastDuplicateControlCodeRequestId = id
    lastDuplicateControlCodeResultAtMillis = nowMillis
    recordTicketEvent(
      "control_code_request_duplicate_ignored",
      "request=$id status=$lastControlCodeRequestStatus completed_age_ms=${if (completedAgeMillis == Long.MAX_VALUE) -1L else completedAgeMillis}"
    )
    broadcastStatus()
    return true
  }

  private fun rememberControlCodeResult(requestId: String, message: String) {
    val id = requestId.takeIf { it.isNotBlank() } ?: return
    val nowMillis = SystemClock.elapsedRealtime()
    synchronized(recentControlCodeResultMessages) {
      pruneRecentControlCodeResultsLocked(nowMillis)
      if (!recentControlCodeResultMessages.containsKey(id)) {
        recentControlCodeResultOrder.addLast(id)
      }
      recentControlCodeResultMessages[id] = nowMillis to message
      while (recentControlCodeResultOrder.size > RECENT_CONTROL_CODE_RESULT_CACHE_SIZE) {
        val removed = recentControlCodeResultOrder.removeFirst()
        recentControlCodeResultMessages.remove(removed)
      }
    }
  }

  private fun pruneRecentControlCodeResultsLocked(nowMillis: Long) {
    val kept = mutableListOf<String>()
    while (recentControlCodeResultOrder.isNotEmpty()) {
      val id = recentControlCodeResultOrder.removeFirst()
      val cached = recentControlCodeResultMessages[id]
      if (cached != null && nowMillis - cached.first <= CONTROL_CODE_RESULT_CACHE_TTL_MILLIS) {
        kept.add(id)
      } else {
        recentControlCodeResultMessages.remove(id)
      }
    }
    while (kept.size > RECENT_CONTROL_CODE_RESULT_CACHE_SIZE) {
      val removed = kept.removeAt(0)
      recentControlCodeResultMessages.remove(removed)
    }
    kept.forEach { recentControlCodeResultOrder.addLast(it) }
  }

  private suspend fun runFastNonTouchInput(
    command: String,
    reason: String,
    postMillis: Long = NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS
  ): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    val result = inputRootExecutor.runScript(
      wrapNonTouchPanelSleepClamp(
        command,
        postMillis = postMillis,
        commandTimeout = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds
      )
    ).also { recordInputCommandResult(reason, it) }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    return result
  }

  private suspend fun runSensitiveFastNonTouchScript(
    command: String,
    reason: String,
    timeout: Duration
  ): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      val rawResult = inputRootExecutor.runScript(
        wrapNonTouchPanelSleepClamp(
          command,
          postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS,
          commandTimeout = timeout
        ),
        timeout
      )
      rawResult.copy(command = "[REDACTED]", stdout = "", stderr = "")
        .also { recordInputCommandResult(reason, it) }
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  private suspend fun runFastInlineControlResultCloseInput(
    command: String,
    reason: String
  ): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    val script = """
      ticket_panel_dir=""
      for ticket_candidate in /sys/class/backlight/panel0-backlight /sys/class/backlight/*; do
        if [ -f "${'$'}ticket_candidate/brightness" ]; then
          ticket_panel_dir="${'$'}ticket_candidate"
          break
        fi
      done
      ticket_panel_dark() {
        if [ -n "${'$'}ticket_panel_dir" ]; then
          echo 0 > "${'$'}ticket_panel_dir/brightness" 2>/dev/null || true
        else
          settings put system screen_brightness_mode 0 >/dev/null 2>&1 || true
          settings put system screen_brightness 0 >/dev/null 2>&1 || true
        fi
      }
      ticket_panel_dark
      $command
      ticket_command_rc=${'$'}?
      ticket_panel_dark
      exit "${'$'}ticket_command_rc"
    """.trimIndent()
    val result = inputRootExecutor.runScript(
      script,
      TICKET_INLINE_CLOSE_COMMAND_TIMEOUT_MILLIS.milliseconds
    ).also { recordInputCommandResult(reason, it) }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    return result
  }

  private suspend fun runFastNonTouchWakeScript(command: String, reason: String, timeout: Duration): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    val activeReassertJob = serviceScope.launch {
      while (true) {
        PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:active")
        delay(NON_TOUCH_SCRIPT_REASSERT_INTERVAL_MILLIS)
      }
    }
    return try {
      wakeRootExecutor.runScript(
        wrapNonTouchPanelSleepClamp(command, postMillis = TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS, commandTimeout = timeout),
        timeout
      ).also { recordInputCommandResult(reason, it) }
    } finally {
      activeReassertJob.cancel()
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  private suspend fun runFastNonTouchScript(command: String, reason: String, timeout: Duration): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    val activeReassertJob = serviceScope.launch {
      while (true) {
        PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:active")
        delay(NON_TOUCH_SCRIPT_REASSERT_INTERVAL_MILLIS)
      }
    }
    return try {
      inputRootExecutor.runScript(wrapNonTouchPanelSleepClamp(command, commandTimeout = timeout), timeout).also { recordInputCommandResult(reason, it) }
    } finally {
      activeReassertJob.cancel()
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  private fun wrapNonTouchPanelSleepClamp(
    command: String,
    postMillis: Long = NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS,
    commandTimeout: Duration? = null
  ): String {
    val intervalMicros = NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS * 1_000L
    val postWrites = ((postMillis + NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS - 1) /
      NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS).coerceAtLeast(1L)
    val timeoutMillis = commandTimeout?.inWholeMilliseconds
      ?.minus(NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS)?.coerceAtLeast(250L)
    val runCommand = timeoutMillis?.let {
      val literal = "${it / 1_000}.${(it % 1_000).toString().padStart(3, '0')}s"
      "timeout -k 0.250s $literal sh -c ${shellQuote(command)}"
    } ?: "sh -c ${shellQuote(command)}"
    return """
      ticket_stop="/data/local/tmp/pixel-ticket-panel-clamp-${'$'}${'$'}"
      ticket_panel=""
      for candidate in /sys/class/backlight/panel0-backlight /sys/class/backlight/*; do
        if [ -f "${'$'}candidate/brightness" ]; then ticket_panel="${'$'}candidate"; break; fi
      done
      ticket_dark() {
        if [ -n "${'$'}ticket_panel" ]; then
          echo 0 > "${'$'}ticket_panel/brightness" 2>/dev/null || true
        else
          settings put system screen_brightness_mode 0 >/dev/null 2>&1 || true
          settings put system screen_brightness 0 >/dev/null 2>&1 || true
        fi
      }
      ticket_dark
      rm -f "${'$'}ticket_stop"
      (
        while [ ! -f "${'$'}ticket_stop" ]; do
          ticket_dark
          usleep $intervalMicros 2>/dev/null || sleep 0.005
        done
      ) &
      ticket_clamp_pid=${'$'}!
      trap 'touch "${'$'}ticket_stop"; wait "${'$'}ticket_clamp_pid" 2>/dev/null || true' HUP INT TERM EXIT
      $runCommand
      ticket_rc=${'$'}?
      touch "${'$'}ticket_stop"
      wait "${'$'}ticket_clamp_pid" 2>/dev/null || true
      ticket_post=0
      while [ "${'$'}ticket_post" -lt "$postWrites" ]; do
        ticket_dark
        ticket_post=${'$'}((ticket_post + 1))
        [ "${'$'}ticket_post" -ge "$postWrites" ] || usleep $intervalMicros 2>/dev/null || sleep 0.005
      done
      rm -f "${'$'}ticket_stop"
      trap - HUP INT TERM EXIT
      exit "${'$'}ticket_rc"
    """.trimIndent()
  }

  private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
  }

  private fun androidInputTextLiteral(value: String): String {
    return value
      .replace("%", "%25")
      .replace(" ", "%s")
  }

  private fun recordInputCommandResult(reason: String, result: RootResult) {
    recordTicketEvent(
      "input_command",
      "$reason duration_ms=${result.durationMs} ok=${result.ok} exit_code=${result.exitCode}"
    )
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

  private fun sendText(output: BufferedOutputStream, status: Int, text: String) {
    sendHttp(
      output = output,
      status = status,
      contentType = "text/plain; charset=utf-8",
      body = text.toByteArray(Charsets.UTF_8)
    )
  }

  private fun sendHttp(
    output: BufferedOutputStream,
    status: Int,
    contentType: String,
    body: ByteArray
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

  // ===========================================================================
  // Rīgas Satiksme re-login channel
  // ----------------------------------------------------------------------------
  // Pixel-side consumer for the admin-driven re-login channel
  // (broker `POST /api/v1/rs/login/start` → WebSocket `rigassatiksme_login_start`).
  // Pixel runs a bounded, state-gated login flow and broadcasts the result
  // back to the broker as `rigassatiksme_login_result { state, failureReason? }`.
  //
  // Safety contract (mirrors the broker side):
  //   * The SMS code is never logged, never written to disk, and never echoed
  //     in any runtime diagnostic event.
  //   * The phone is persisted only as `phoneLast4`.
  //   * Exactly one SMS attempt per `rigassatiksme_login_start`.
  //   * A running RS QR job is preempted by the broker; the Pixel does not
  //     race the broker here.
  // ===========================================================================

  private fun handleRigassatiksmeLoginStart(
    requestId: String,
    phone: String,
    locale: String,
    client: TicketWebSocket?,
  ) {
    if (requestId.isBlank()) {
      recordTicketEvent("rigassatiksme_login_invalid", "reason=missing_request_id")
      return
    }
    if (!RigasSatiksmeLoginOperation.isValidPhone(phone)) {
      recordTicketEvent(
        "rigassatiksme_login_invalid",
        "request_id=$requestId reason=phone_invalid"
      )
      sendRigassatiksmeLoginResult(
        requestId = requestId,
        state = "failed",
        failureReason = "phone_field_missing",
        phases = mapOf("login_invalid_at" to SystemClock.elapsedRealtime()),
      )
      return
    }
    val previousRequestId = rigassatiksmeLoginRequestId
    val previousState = rigassatiksmeLoginState
    if (rigassatiksmeLoginJob != null && rigassatiksmeLoginJob?.isActive == true &&
      (previousState == "waiting_for_sms" || previousState == "running" || previousState == "started")
    ) {
      recordTicketEvent(
        "rigassatiksme_login_duplicate_rejected",
        "request_id=$requestId previous_request_id=${previousRequestId.orEmpty()} previous_state=$previousState"
      )
      sendRigassatiksmeLoginResult(
        requestId = requestId,
        state = "failed",
        failureReason = "login_unreachable",
        phases = mapOf("login_duplicate_at" to SystemClock.elapsedRealtime()),
      )
      return
    }
    val phoneLast4 = RigasSatiksmeLoginOperation.phoneLast4(phone)
    rigassatiksmeLoginRequestId = requestId
    rigassatiksmeLoginPhoneLast4 = phoneLast4
    rigassatiksmeLoginState = "started"
    rigassatiksmeLoginLastState = "started"
    rigassatiksmeLoginLastFailureReason = null
    rigassatiksmeLoginStartedAtMillis = SystemClock.elapsedRealtime()
    rigassatiksmeLoginCompletedAtMillis = 0L
    rigassatiksmeLoginAwaitingSms = false
    synchronized(rigassatiksmeLoginFailureByReasonLock) {
      rigassatiksmeLoginFailureByReason = rigassatiksmeLoginFailureByReason
    }
    rigassatiksmeLoginAttempts += 1
    rigassatiksmeLoginCodeHolder.clear()
    val nowMillis = SystemClock.elapsedRealtime()
    recordTicketEvent(
      "rigassatiksme_login_started",
      "request_id=$requestId phone_last4=$phoneLast4 locale=${locale.take(20).ifBlank { "missing" }}"
    )
    sendRigassatiksmeLoginResult(
      requestId = requestId,
      state = "started",
      failureReason = null,
      phases = mapOf("login_started_at" to nowMillis),
    )
    val previousJob = rigassatiksmeLoginJob
    previousJob?.cancel()
    rigassatiksmeLoginJob = serviceScope.launch {
      try {
        runRigassatiksmeLoginFlow(requestId, phone, phoneLast4, locale)
      } catch (cancellation: CancellationException) {
        sendRigassatiksmeLoginResult(
          requestId = requestId,
          state = "failed",
          failureReason = "canceled",
          phases = mapOf("login_canceled_at" to SystemClock.elapsedRealtime()),
        )
        markRigassatiksmeLoginTerminal(requestId, "failed", "canceled", nowMillis)
        throw cancellation
      } catch (error: Throwable) {
        val reason = "login_unreachable"
        recordTicketEvent(
          "rigassatiksme_login_unhandled_error",
          "request_id=$requestId phone_last4=$phoneLast4 error=${error.message?.take(120)?.replace('\n', ' ')?.replace('\r', ' ')}"
        )
        sendRigassatiksmeLoginResult(
          requestId = requestId,
          state = "failed",
          failureReason = reason,
          phases = mapOf("login_error_at" to SystemClock.elapsedRealtime()),
        )
        markRigassatiksmeLoginTerminal(requestId, "failed", reason, SystemClock.elapsedRealtime())
      }
    }
  }

  private fun handleRigassatiksmeLoginSms(
    requestId: String,
    code: String,
    client: TicketWebSocket?,
  ) {
    if (requestId.isBlank()) {
      recordTicketEvent("rigassatiksme_login_sms_invalid", "reason=missing_request_id")
      return
    }
    if (requestId != rigassatiksmeLoginRequestId) {
      recordTicketEvent(
        "rigassatiksme_login_sms_invalid",
        "request_id=$requestId active_request_id=${rigassatiksmeLoginRequestId.orEmpty()} reason=request_id_mismatch"
      )
      sendRigassatiksmeLoginResult(
        requestId = requestId,
        state = "failed",
        failureReason = "sms_field_missing",
        phases = mapOf("login_sms_no_active_at" to SystemClock.elapsedRealtime()),
      )
      return
    }
    if (rigassatiksmeLoginState != "waiting_for_sms") {
      recordTicketEvent(
        "rigassatiksme_login_sms_invalid",
        "request_id=$requestId state=${rigassatiksmeLoginState} reason=state_mismatch"
      )
      val reason = if (rigassatiksmeLoginState == "succeeded" || rigassatiksmeLoginState == "failed") {
        "wrong_sms_code"
      } else {
        "sms_field_missing"
      }
      sendRigassatiksmeLoginResult(
        requestId = requestId,
        state = "failed",
        failureReason = reason,
        phases = mapOf("login_sms_state_at" to SystemClock.elapsedRealtime()),
      )
      markRigassatiksmeLoginTerminal(requestId, "failed", reason, SystemClock.elapsedRealtime())
      return
    }
    if (!RigasSatiksmeLoginOperation.isValidSmsCode(code)) {
      recordTicketEvent(
        "rigassatiksme_login_sms_invalid",
        "request_id=$requestId reason=code_invalid"
      )
      sendRigassatiksmeLoginResult(
        requestId = requestId,
        state = "failed",
        failureReason = "sms_field_missing",
        phases = mapOf("login_sms_code_invalid_at" to SystemClock.elapsedRealtime()),
      )
      markRigassatiksmeLoginTerminal(requestId, "failed", "sms_field_missing", SystemClock.elapsedRealtime())
      return
    }
    rigassatiksmeLoginCodeHolder.put(code)
    rigassatiksmeLoginState = "running"
    rigassatiksmeLoginAwaitingSms = false
    recordTicketEvent(
      "rigassatiksme_login_sms_accepted",
      "request_id=$requestId phone_last4=${rigassatiksmeLoginPhoneLast4.orEmpty()}"
    )
  }

  private fun handleRigassatiksmeLoginCancel(requestId: String, reason: String) {
    if (requestId.isBlank()) {
      recordTicketEvent("rigassatiksme_login_cancel_invalid", "reason=missing_request_id")
      return
    }
    if (requestId != rigassatiksmeLoginRequestId) {
      recordTicketEvent(
        "rigassatiksme_login_cancel_no_active",
        "request_id=$requestId active_request_id=${rigassatiksmeLoginRequestId.orEmpty()} reason=$reason"
      )
      return
    }
    rigassatiksmeLoginJob?.cancel()
    rigassatiksmeLoginJob = null
    rigassatiksmeLoginCodeHolder.clear()
    recordTicketEvent(
      "rigassatiksme_login_canceled",
      "request_id=$requestId phone_last4=${rigassatiksmeLoginPhoneLast4.orEmpty()} reason=$reason"
    )
    sendRigassatiksmeLoginResult(
      requestId = requestId,
      state = "failed",
      failureReason = if (reason.isBlank()) "canceled" else reason,
      phases = mapOf("login_canceled_at" to SystemClock.elapsedRealtime()),
    )
    markRigassatiksmeLoginTerminal(requestId, "failed", if (reason.isBlank()) "canceled" else reason, SystemClock.elapsedRealtime())
  }

  private fun handleRigassatiksmeLoginStartHttp(body: String): String {
    val parsed = try {
      org.json.JSONObject(body)
    } catch (error: Throwable) {
      return buildJsonObject {
        put("ok", false)
        put("error", "invalid_json")
      }.toString()
    }
    val requestId = parsed.optString("requestId", "").trim()
    val phone = parsed.optString("phone", "").trim()
    val locale = parsed.optString("locale", "").trim()
    if (requestId.isBlank()) {
      return buildJsonObject {
        put("ok", false)
        put("error", "missing_request_id")
      }.toString()
    }
    if (!RigasSatiksmeLoginOperation.isValidPhone(phone)) {
      return buildJsonObject {
        put("ok", false)
        put("error", "invalid_phone")
      }.toString()
    }
    handleRigassatiksmeLoginStart(
      requestId = requestId,
      phone = phone,
      locale = locale,
      client = null,
    )
    return buildJsonObject {
      put("ok", true)
      put("requestId", requestId)
      put("state", rigassatiksmeLoginState)
    }.toString()
  }

  private fun handleRigassatiksmeLoginSmsHttp(body: String): String {
    val parsed = try {
      org.json.JSONObject(body)
    } catch (error: Throwable) {
      return buildJsonObject {
        put("ok", false)
        put("error", "invalid_json")
      }.toString()
    }
    val requestId = parsed.optString("requestId", "").trim()
    val code = parsed.optString("code", "")
    if (requestId.isBlank()) {
      return buildJsonObject {
        put("ok", false)
        put("error", "missing_request_id")
      }.toString()
    }
    if (requestId != rigassatiksmeLoginRequestId) {
      return buildJsonObject {
        put("ok", false)
        put("error", "request_id_mismatch")
        put("activeRequestId", rigassatiksmeLoginRequestId.orEmpty())
      }.toString()
    }
    if (rigassatiksmeLoginState != "waiting_for_sms") {
      return buildJsonObject {
        put("ok", false)
        put("error", "not_waiting_for_sms")
        put("state", rigassatiksmeLoginState)
      }.toString()
    }
    if (!RigasSatiksmeLoginOperation.isValidSmsCode(code)) {
      return buildJsonObject {
        put("ok", false)
        put("error", "invalid_code")
      }.toString()
    }
    handleRigassatiksmeLoginSms(
      requestId = requestId,
      code = code,
      client = null,
    )
    return buildJsonObject {
      put("ok", true)
      put("requestId", requestId)
      put("state", rigassatiksmeLoginState)
    }.toString()
  }

  private fun handleRigassatiksmeLoginCancelHttp(body: String): String {
    val parsed = try {
      org.json.JSONObject(body)
    } catch (error: Throwable) {
      return buildJsonObject {
        put("ok", false)
        put("error", "invalid_json")
      }.toString()
    }
    val requestId = parsed.optString("requestId", "").trim()
    val reason = parsed.optString("reason", "canceled").trim()
    if (requestId.isBlank()) {
      return buildJsonObject {
        put("ok", false)
        put("error", "missing_request_id")
      }.toString()
    }
    handleRigassatiksmeLoginCancel(requestId, reason)
    return buildJsonObject {
      put("ok", true)
      put("requestId", requestId)
      put("state", rigassatiksmeLoginState)
    }.toString()
  }

  private fun rigassatiksmeLoginStatusPayload(): String {
    val nowMillis = SystemClock.elapsedRealtime()
    val durationMs = if (rigassatiksmeLoginStartedAtMillis > 0L) {
      (nowMillis - rigassatiksmeLoginStartedAtMillis).coerceAtLeast(0L)
    } else {
      0L
    }
    return buildJsonObject {
      put("state", rigassatiksmeLoginState)
      put("requestId", rigassatiksmeLoginRequestId.orEmpty())
      put("phoneLast4", rigassatiksmeLoginPhoneLast4.orEmpty())
      put("failureReason", rigassatiksmeLoginLastFailureReason.orEmpty())
      put("startedAtMillis", rigassatiksmeLoginStartedAtMillis)
      put("completedAtMillis", rigassatiksmeLoginCompletedAtMillis)
      put("durationMillis", durationMs)
      put("awaitingSms", rigassatiksmeLoginAwaitingSms)
      put("attempts", rigassatiksmeLoginAttempts)
      put("successes", rigassatiksmeLoginSuccesses)
      put("failures", rigassatiksmeLoginFailures)
      put("lastResult", rigassatiksmeLoginLastResultJson.orEmpty())
      put("lastResultAtMillis", rigassatiksmeLoginLastResultAtMillis)
    }.toString()
  }

  private suspend fun runRigassatiksmeLoginFlow(
    requestId: String,
    phone: String,
    phoneLast4: String,
    locale: String,
  ) {
    val startedAtMillis = SystemClock.elapsedRealtime()
    // Pause the foreground guard for the entire login flow so it doesn't
    // fight us by switching back to ViVi while we're driving the RS app.
    markControlCodeTransition("rs_login_request")
    controlCodeTransitionGraceUntilMillis = startedAtMillis + RS_LOGIN_TIMEOUT_MILLIS + 10_000L
    val operation = RigasSatiksmeLoginOperation()
    var step = RigasSatiksmeLoginOperation.LoginStep.IDLE
    var launched = false
    var phoneTyped = false
    var codeTyped = false
    var submitTapped = false
    val maxActions = RS_LOGIN_MAX_ACTIONS
    val deadlineMillis = startedAtMillis + RS_LOGIN_TIMEOUT_MILLIS
    var actionCount = 0
    var consecutiveUnknown = 0
    var lastFailureReason: String? = null
    while (actionCount < maxActions && SystemClock.elapsedRealtime() < deadlineMillis) {
      if (rigassatiksmeLoginRequestId != requestId) {
        recordTicketEvent(
          "rigassatiksme_login_preempted",
          "request_id=$requestId phone_last4=$phoneLast4"
        )
        return
      }
      if (!launched) {
        if (!ensureRigassatiksmeLoginForeground()) {
          markRigassatiksmeLoginFailure(requestId, "phone_unavailable", phoneLast4)
          return
        }
        launched = true
        delay(RS_LOGIN_AFTER_LAUNCH_SETTLE_MILLIS)
      }
      val snapshot = snapshotRigasSatiksmeUiAutomatorNodes("rs_login_step_$actionCount")
      val smsCodeAvailable = rigassatiksmeLoginCodeHolder.peek() != null
      val decision = operation.decide(step, snapshot, smsCodeAvailable)
      step = decision.nextState
      if (decision.done) {
        when (decision.resultState) {
          "succeeded" -> {
            sendRigassatiksmeLoginResult(
              requestId = requestId,
              state = "succeeded",
              failureReason = null,
              phases = buildRigassatiksmeLoginPhases(startedAtMillis, mapOf("login_done_observed" to true)),
            )
            markRigassatiksmeLoginTerminal(requestId, "succeeded", null, SystemClock.elapsedRealtime())
            return
          }
          "failed" -> {
            val reason = when (decision.action) {
              RigasSatiksmeLoginDriverAction.ReportAuthBlocked -> "rs_auth_blocked"
              RigasSatiksmeLoginDriverAction.ReportWrongCode -> "wrong_sms_code"
              else -> "login_unreachable"
            }
            markRigassatiksmeLoginFailure(requestId, reason, phoneLast4)
            return
          }
        }
      }
      when (decision.action) {
        RigasSatiksmeLoginDriverAction.Noop -> {
          consecutiveUnknown += 1
          delay(RS_LOGIN_STEP_SETTLE_MILLIS)
        }
        RigasSatiksmeLoginDriverAction.TypePhone -> {
          if (!phoneTyped) {
            if (!typeRigassatiksmeLoginPhone(phone, phoneLast4)) {
              markRigassatiksmeLoginFailure(requestId, "phone_field_missing", phoneLast4)
              return
            }
            phoneTyped = true
            rigassatiksmeLoginState = "waiting_for_sms"
            rigassatiksmeLoginAwaitingSms = true
            delay(RS_LOGIN_AFTER_INPUT_SETTLE_MILLIS)
          }
          consecutiveUnknown = 0
        }
        RigasSatiksmeLoginDriverAction.TypeCode -> {
          if (!codeTyped) {
            val code = rigassatiksmeLoginCodeHolder.consume()
            if (code.isNullOrBlank() || !RigasSatiksmeLoginOperation.isValidSmsCode(code)) {
              markRigassatiksmeLoginFailure(requestId, "sms_field_missing", phoneLast4)
              return
            }
            if (!typeRigassatiksmeLoginCode(code, phoneLast4)) {
              markRigassatiksmeLoginFailure(requestId, "sms_field_missing", phoneLast4)
              return
            }
            codeTyped = true
            rigassatiksmeLoginAwaitingSms = false
            delay(RS_LOGIN_AFTER_INPUT_SETTLE_MILLIS)
          }
          consecutiveUnknown = 0
        }
        RigasSatiksmeLoginDriverAction.TapSignIn -> {
          if (!submitTapped) {
            if (!tapRigassatiksmeLoginButton(phoneLast4)) {
              markRigassatiksmeLoginFailure(requestId, "submit_failed", phoneLast4)
              return
            }
            submitTapped = true
            rigassatiksmeLoginState = "running"
            delay(RS_LOGIN_AFTER_SUBMIT_SETTLE_MILLIS)
          }
          consecutiveUnknown = 0
        }
        RigasSatiksmeLoginDriverAction.TapSignInToShowForm -> {
          if (!tapRigassatiksmeLandingLoginButton(phoneLast4)) {
            markRigassatiksmeLoginFailure(requestId, "login_landing_tap_failed", phoneLast4)
            return
          }
          delay(RS_LOGIN_AFTER_LAUNCH_SETTLE_MILLIS)
          consecutiveUnknown = 0
        }
        RigasSatiksmeLoginDriverAction.ReportWrongCode -> {
          markRigassatiksmeLoginFailure(requestId, "wrong_sms_code", phoneLast4)
          return
        }
        RigasSatiksmeLoginDriverAction.ReportAuthBlocked -> {
          markRigassatiksmeLoginFailure(requestId, "rs_auth_blocked", phoneLast4)
          return
        }
      }
      actionCount += 1
    }
    if (SystemClock.elapsedRealtime() >= deadlineMillis) {
      lastFailureReason = if (phoneTyped && !codeTyped) "sms_timeout" else "phone_field_missing"
    } else if (actionCount >= maxActions) {
      lastFailureReason = if (submitTapped) "login_unreachable" else "phone_field_missing"
    }
    val reason = lastFailureReason ?: "phone_field_missing"
    markRigassatiksmeLoginFailure(requestId, reason, phoneLast4)
  }

  private suspend fun ensureRigassatiksmeLoginForeground(): Boolean {
    // If the RS app is already in the foreground, don't force-stop it.
    // The session might still be valid. Just take a snapshot and let the
    // classifier determine if we need to log in or if we're already done.
    if (PhoneAutomationServiceBridge.waitForForegroundPackage(
        TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
        timeoutMillis = 1_500L
      )
    ) {
      return true
    }
    // RS app is not in the foreground. Launch it without force-stopping.
    recordTicketEvent("rigassatiksme_login_launch", "package=${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}")
    return try {
      withContext(Dispatchers.Main) {
        val launchIntent = Intent().setClassName(
          TicketScreenConfig.RIGAS_SATIKSME_PACKAGE,
          "${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}.MainActivity"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
      }
      delay(700L)
      true
    } catch (error: Throwable) {
      recordTicketEvent(
        "rigassatiksme_login_launch_failed",
        "error=${error.message?.take(120)?.replace('\n', ' ')?.replace('\r', ' ')}"
      )
      false
    }
  }

  private suspend fun typeRigassatiksmeLoginPhone(phone: String, phoneLast4: String): Boolean {
    recordTicketEvent("rigassatiksme_login_phone_typing_started", "phone_last4=$phoneLast4")
    val cleanPhone = phone.filter { it.isDigit() }
    val result = runRigasSatiksmeDirectInput(
      buildString {
        append("input tap 650 894\n")
        append("sleep 0.15\n")
        append("input keyevent KEYCODE_MOVE_END\n")
        for (i in 0 until 20) {
          append("input keyevent KEYCODE_DEL\n")
        }
        append("input text ").append(cleanPhone)
      },
      "rs_login_type_phone"
    )
    if (!result.ok) {
      recordTicketEvent(
        "rigassatiksme_login_phone_typing_failed",
        "phone_last4=$phoneLast4"
      )
      return false
    }
    recordTicketEvent("rigassatiksme_login_phone_typed", "phone_last4=$phoneLast4")
    return true
  }

  private suspend fun typeRigassatiksmeLoginCode(code: String, phoneLast4: String): Boolean {
    recordTicketEvent("rigassatiksme_login_code_typing_started", "phone_last4=$phoneLast4")
    val cleanCode = code.trim()
    if (!RigasSatiksmeLoginOperation.isValidSmsCode(cleanCode)) {
      recordTicketEvent(
        "rigassatiksme_login_code_invalid",
        "phone_last4=$phoneLast4"
      )
      return false
    }
    // Escape spaces in the password for the shell; hyphens and alphanumerics are safe.
    val shellSafeCode = cleanCode.replace(" ", "\\ ")
    val result = runRigasSatiksmeDirectInput(
      buildString {
        append("input tap 540 1062\n")
        append("sleep 0.15\n")
        append("input keyevent KEYCODE_MOVE_END\n")
        for (i in 0 until 30) {
          append("input keyevent KEYCODE_DEL\n")
        }
        append("input text ").append(shellSafeCode)
      },
      "rs_login_type_code"
    )
    if (!result.ok) {
      recordTicketEvent(
        "rigassatiksme_login_code_typing_failed",
        "phone_last4=$phoneLast4"
      )
      return false
    }
    recordTicketEvent("rigassatiksme_login_code_typed", "phone_last4=$phoneLast4")
    return true
  }

  private suspend fun tapRigassatiksmeLoginButton(phoneLast4: String): Boolean {
    recordTicketEvent("rigassatiksme_login_submit_tap_started", "phone_last4=$phoneLast4")
    val tapResult = runRigasSatiksmeDirectInput(
      "input tap 540 1266",
      "rs_login_tap_log_in"
    )
    if (!tapResult.ok) {
      recordTicketEvent(
        "rigassatiksme_login_submit_tap_failed",
        "phone_last4=$phoneLast4"
      )
      return false
    }
    recordTicketEvent("rigassatiksme_login_submit_tapped", "phone_last4=$phoneLast4")
    return true
  }

  private suspend fun tapRigassatiksmeLandingLoginButton(phoneLast4: String): Boolean {
    recordTicketEvent("rigassatiksme_login_landing_tap_started", "phone_last4=$phoneLast4")
    val tapResult = runRigasSatiksmeDirectInput(
      "input tap 540 1124",
      "rs_login_tap_landing_log_in"
    )
    if (!tapResult.ok) {
      recordTicketEvent(
        "rigassatiksme_login_landing_tap_failed",
        "phone_last4=$phoneLast4"
      )
      return false
    }
    recordTicketEvent("rigassatiksme_login_landing_tapped", "phone_last4=$phoneLast4")
    return true
  }

  private fun markRigassatiksmeLoginFailure(
    requestId: String,
    reason: String,
    phoneLast4: String,
  ) {
    if (rigassatiksmeLoginRequestId != requestId) return
    val nowMillis = SystemClock.elapsedRealtime()
    sendRigassatiksmeLoginResult(
      requestId = requestId,
      state = "failed",
      failureReason = reason,
      phases = mapOf("login_failure_at" to nowMillis),
    )
    markRigassatiksmeLoginTerminal(requestId, "failed", reason, nowMillis)
    recordTicketEvent(
      "rigassatiksme_login_finished",
      "request_id=$requestId phone_last4=$phoneLast4 state=failed reason=$reason duration_ms=${nowMillis - rigassatiksmeLoginStartedAtMillis}"
    )
  }

  private fun markRigassatiksmeLoginTerminal(
    requestId: String,
    state: String,
    failureReason: String?,
    nowMillis: Long,
  ) {
    if (rigassatiksmeLoginRequestId != requestId) return
    rigassatiksmeLoginState = state
    rigassatiksmeLoginLastState = state
    rigassatiksmeLoginLastFailureReason = failureReason
    rigassatiksmeLoginCompletedAtMillis = nowMillis
    rigassatiksmeLoginAwaitingSms = false
    rigassatiksmeLoginCodeHolder.clear()
    // Release the foreground guard pause so the ticket stream can resume.
    controlCodeTransitionGraceUntilMillis = 0L
    if (state == "succeeded") {
      rigassatiksmeLoginSuccesses += 1
    } else if (state == "failed") {
      rigassatiksmeLoginFailures += 1
      val reason = failureReason?.takeIf { it.isNotBlank() } ?: "unknown"
      synchronized(rigassatiksmeLoginFailureByReasonLock) {
        val updated = rigassatiksmeLoginFailureByReason.toMutableMap()
        updated[reason] = (updated[reason] ?: 0L) + 1L
        rigassatiksmeLoginFailureByReason = updated
      }
    }
  }

  private fun buildRigassatiksmeLoginPhases(
    startedAtMillis: Long,
    extra: Map<String, Any>,
  ): Map<String, Long> {
    val nowMillis = SystemClock.elapsedRealtime()
    val total = (nowMillis - startedAtMillis).coerceAtLeast(0L)
    val phases = mutableMapOf<String, Long>(
      "rs_login_total" to total
    )
    extra.forEach { (key, value) ->
      when (value) {
        is Boolean -> if (value) phases[key] = nowMillis
        is Long -> phases[key] = value
        is Number -> phases[key] = value.toLong()
        else -> Unit
      }
    }
    return phases
  }

  private fun sendRigassatiksmeLoginResult(
    requestId: String,
    state: String,
    failureReason: String?,
    phases: Map<String, Long>,
  ) {
    val phoneLast4 = rigassatiksmeLoginPhoneLast4.orEmpty()
    val normalizedState = when (state) {
      "succeeded" -> "succeeded"
      "started" -> "started"
      "waiting_for_sms" -> "waiting_for_sms"
      "running" -> "running"
      "failed" -> "failed"
      "canceled" -> "failed"
      else -> "failed"
    }
    val normalizedReason = failureReason?.takeIf { it.isNotBlank() }
      ?: when (normalizedState) {
        "succeeded" -> "generated"
        "started" -> ""
        "waiting_for_sms" -> ""
        "running" -> ""
        else -> "login_unreachable"
      }
    val payload = buildJsonObject {
      put("type", "rigassatiksme_login_result")
      put("requestId", requestId)
      put("state", normalizedState)
      put("phoneLast4", phoneLast4)
      put("failureReason", normalizedReason)
      put("phases", buildJsonObject {
        phases.forEach { (key, value) -> put(key, value) }
      })
    }
    val message = payload.toString()
    rigassatiksmeLoginLastResultJson = message
    rigassatiksmeLoginLastResultAtMillis = SystemClock.elapsedRealtime()
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    val durationMs = (SystemClock.elapsedRealtime() - rigassatiksmeLoginStartedAtMillis).coerceAtLeast(0L)
    recordTicketEvent(
      "rigassatiksme_login_result",
      "request_id=$requestId state=$normalizedState phone_last4=$phoneLast4 reason=$normalizedReason duration_ms=$durationMs"
    )
    broadcastStatus()
  }

  companion object {
    private const val SERVER_BACKLOG = 4
    private const val SOCKET_TIMEOUT_MILLIS = 30_000
    private const val MAX_HEADER_LINE_BYTES = 131_072
    private const val TICKET_SPACETIME_PHONE_MESSAGE_LIMIT = 80
    private const val TICKET_SPACETIME_CRITICAL_MESSAGE_TTL_MILLIS = 5 * 60_000L
    private const val MAX_TICKET_EVENT_DETAIL_BYTES = 256
    private const val SESSION_START_TIMEOUT_MILLIS = 70_000L
    const val SERVER_VERSION = "ticket-stream-2026-07-11-panel-sleep-shield-handoff-v290"
    private const val CONTROL_CODE_MARKER_RESULT_HIERARCHY = "__marker_control_code_result__"
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
    private const val CAPTURE_MODE_ROOT_HARDWARE_H264 = TicketScreenConfig.ROOT_HARDWARE_H264_CAPTURE_MODE
    private const val TICKET_PIXEL_STATE_RAW_TICKET = "raw_ticket"
    private const val TICKET_PIXEL_STATE_CONTROL_POPUP = "control_popup"
    private const val TICKET_PIXEL_STATE_GENERATED_RESULT = "generated_result"
    private const val TICKET_PIXEL_STATE_RETURNING_RAW = "returning_raw"
    private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 750L
    private const val LIVE_FRAME_MAX_AGE_MILLIS = 2_000L
    private const val ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS = 5 * 60_000L
    private const val STREAM_STALE_ENGINE_RESTART_MILLIS = 4_000L
    private const val STREAM_WATCHDOG_POLL_MILLIS = 500L
    private const val STREAM_WATCHDOG_NO_ENCODER_RESTART_MILLIS = 1_200L
    private const val STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS = 2_500L
    private const val STREAM_WATCHDOG_STALE_FRAME_RESTART_MILLIS = 4_000L
    private const val STREAM_WATCHDOG_RECOVERY_COOLDOWN_MILLIS = 1_000L
    private const val SPACETIME_DESIRED_RECOVERY_COOLDOWN_MILLIS = 20_000L
    private const val SPACETIME_DESIRED_RECOVERY_STALE_BLOCK_MILLIS = 15_000L
    private const val HARDWARE_RELIABILITY_FAILURE_THRESHOLD = 3
    private const val STARTUP_MAINTENANCE_DEFER_MILLIS = 1_200L
    private const val STARTUP_MAINTENANCE_POLL_MILLIS = 40L
    private const val POST_CLEANUP_FRESH_FRAME_TIMEOUT_MILLIS = 2_500L
    private const val POST_CLEANUP_FRESH_FRAME_POLL_MILLIS = 100L
    private const val SECURE_CAPTURE_PROBE_START_FRAME_COUNT = 3L
    private const val SECURE_CAPTURE_PROBE_DELAY_MILLIS = 700L
    private const val SECURE_CAPTURE_PROBE_MIN_INTERVAL_MILLIS = 8_000L
    private const val SECURE_CAPTURE_VISIBLE_PROBE_REUSE_MILLIS = 20_000L
    private const val SECURE_CAPTURE_PROBE_TIMEOUT_MILLIS = 1_500L
    private const val TICKET_VIVI_MEMORY_PREFS = "ticket_vivi_state_memory"
    private const val KEY_VIVI_MEMORY_CURRENT_STATE = "current_state"
    private const val KEY_VIVI_MEMORY_CURRENT_TICKET_ID = "current_ticket_id"
    private const val KEY_VIVI_MEMORY_CURRENT_SOURCE = "current_source"
    private const val KEY_VIVI_MEMORY_CURRENT_REASON = "current_reason"
    private const val KEY_VIVI_MEMORY_CURRENT_WALL_MILLIS = "current_wall_millis"
    private const val KEY_VIVI_MEMORY_TICKET_ID = "ticket_detail_ticket_id"
    private const val KEY_VIVI_MEMORY_TICKET_SOURCE = "ticket_detail_source"
    private const val KEY_VIVI_MEMORY_TICKET_REASON = "ticket_detail_reason"
    private const val KEY_VIVI_MEMORY_TICKET_WALL_MILLIS = "ticket_detail_wall_millis"
    private const val SEND_BITRATE_WINDOW_MILLIS = 1_000L
    private const val VIDEO_CLIENT_SLOW_WRITE_MILLIS = 100L
    private const val VIDEO_CLIENT_PENDING_MAX_AGE_MILLIS = 150L
    private const val VIDEO_CLIENT_SLOW_CLOSE_MILLIS = 250L
    private const val TICKET_WAKE_BUDGET_MILLIS = 3_000L
    private const val TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS = 5_000L
    private const val TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS = 2_500L
    private const val TICKET_FAST_PUBLIC_OPEN_MIN_ROOT_PROOF_TIMEOUT_MILLIS = 1_000L
    private const val TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_MIN_REMAINING_MILLIS = 600L
    private const val TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS = 40L
    private const val TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_SAMPLE_GAP_MILLIS = 80L
    private const val TICKET_FAST_PUBLIC_OPEN_VISUAL_RAW_TICKET_PROOF_COUNT = 2
    private const val TICKET_WAKE_RECOVERY_BUDGET_MILLIS = 60_000L
    private const val TICKET_WAKE_RECOVERY_MAX_ACTIONS = 4
    private const val TICKET_RS_MONTHLY_RETURN_BUDGET_MILLIS = 45_000L
    private const val TICKET_RS_MONTHLY_RETURN_MAX_RECOVERY_ACTIONS = 6
    private const val TICKET_RS_MONTHLY_IDLE_CLEANUP_DELAY_MILLIS = 2_500L
    private const val TICKET_RS_MONTHLY_FAST_RETURN_TIMEOUT_MILLIS = 8_000L
    private const val RIGAS_SATIKSME_RESULT_CAPTURE_MAX_WIDTH = 720
    private const val RIGAS_SATIKSME_DIRECT_INPUT_TIMEOUT_MILLIS = 5_000L
    private const val RIGAS_SATIKSME_DIRECT_UI_DUMP_TIMEOUT_MILLIS = 3_800L
    private const val RS_LOGIN_TIMEOUT_MILLIS = 120_000L
    private const val RS_LOGIN_MAX_ACTIONS = 20
    private const val RS_LOGIN_STEP_SETTLE_MILLIS = 500L
    private const val RS_LOGIN_AFTER_INPUT_SETTLE_MILLIS = 400L
    private const val RS_LOGIN_AFTER_SUBMIT_SETTLE_MILLIS = 1500L
    private const val RS_LOGIN_AFTER_LAUNCH_SETTLE_MILLIS = 2000L
    private const val TICKET_ROOT_HIERARCHY_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val TICKET_WAKE_COMMAND_TIMEOUT_MILLIS = 3_000L
    private const val TICKET_WAKE_INTERACTIVE_TIMEOUT_MILLIS = 900L
    private const val TICKET_WAKE_LAUNCH_TIMEOUT_MILLIS = 3_000L
    private const val TICKET_WAKE_FAST_POST_LAUNCH_TIMEOUT_MILLIS = 8_000L
    private const val TICKET_WAKE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS = 450L
    private const val TICKET_WAKE_POST_LAUNCH_FAST_READY_TIMEOUT_MILLIS = 1_400L
    private const val TICKET_WAKE_POST_LAUNCH_FAST_READY_POLL_MILLIS = 120L
    private const val TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS = 10 * 60_000L
    private const val TICKET_WAKE_FOCUSED_TICKET_DETAIL_FAST_READY_MAX_AGE_MILLIS = 24 * 60 * 60_000L
    private const val TICKET_WAKE_GUARD_GRACE_MILLIS = 1_000L
    private const val TICKET_WAKE_FAST_POLL_MILLIS = 100L
    private const val NON_TOUCH_SCRIPT_REASSERT_INTERVAL_MILLIS = 250L
    private const val NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS = 120_000L
    private const val NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS = 250L
    private const val NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS = 5L
    private const val TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS = 250L
    private const val CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS = 5L
    private const val TICKET_INLINE_CLOSE_COMMAND_TIMEOUT_MILLIS = 2_000L
    private const val NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS = 2_500L
    private const val STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS = 5_000L
    private const val CLIENT_DISCONNECT_IDLE_GRACE_MILLIS = 90_000L
    private const val VIVI_FOREGROUND_INITIAL_DELAY_MILLIS = 1_500L
    private const val VIVI_FOREGROUND_CHECK_MILLIS = 1_500L
    private const val VIVI_STABLE_FOREGROUND_CHECK_MILLIS = 5_000L
    private const val VIVI_FOREGROUND_GRACE_MILLIS = 8_000L
    private const val VIVI_PAGE_ENFORCE_INTERVAL_MILLIS = 5_000L
    private const val VIVI_STABLE_PAGE_ENFORCE_INTERVAL_MILLIS = 30_000L
    private const val VIVI_STABLE_PAGE_ENFORCE_MEMORY_MAX_AGE_MILLIS = 60_000L
    private const val FOREGROUND_GUARD_RECENT_TICKET_LOG_INTERVAL_MILLIS = 30_000L
    private const val FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS = 3_000L
    private const val ACTIVE_GUARD_RECOVERY_SESSION_RETRY_DELAY_MILLIS = 650L
    private const val ACTIVE_GUARD_RECOVERY_SESSION_RETRY_COOLDOWN_MILLIS = 3_000L
    private const val TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS = TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS
    private const val TICKET_SCREEN_WAKE_HOLD_MILLIS = 30_000L
    private const val TICKET_SCREEN_WAKE_REQUEST_COOLDOWN_MILLIS = 2_000L
    private const val VIVI_LOGIN_SECRET_FILE = "/data/local/pixel-stack/conf/apps/ticket-screen-vivi-login.env"
    private const val VIVI_LOGIN_EMAIL_ENV = "VIVI_LOGIN_EMAIL"
    private const val VIVI_LOGIN_SECRET_ENV = "VIVI_LOGIN_PASSWORD"
    private const val VIVI_LOGIN_SECRET_READ_TIMEOUT_MILLIS = 1_500L
    private const val VIVI_LOGIN_FIELD_SETTLE_MILLIS = 150L
    private const val VIVI_LOGIN_POST_SUBMIT_TIMEOUT_MILLIS = 12_000L
    private const val VIVI_LOGIN_POST_SUBMIT_POLL_MILLIS = 400L
    private const val VIVI_LOGIN_ROOT_DUMP_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_CODE_POPUP_READY_CACHE_MILLIS = 2_000L
    private const val CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_FAST_RESULT_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_RAW_TICKET_ROOT_CONFIRM_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_RAW_TICKET_VISUAL_REJECT_LOG_COUNT = 2L
    private const val CONTROL_CODE_SUBMIT_RETRY_MIN_POPUP_SAMPLES = 2L
    private const val CONTROL_CODE_SUBMIT_RETRY_MIN_AGE_MILLIS = 300L
    private const val CONTROL_CODE_SUBMIT_RETRY_POST_POPUP_LIMIT = 2L
    private const val CONTROL_CODE_FAST_RESULT_FINAL_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_FAST_ROOT_RETRY_COUNT = 1
    private const val CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS = 18_000L
    private const val CONTROL_CODE_BROWSER_CAPTURE_ACK_POLL_MILLIS = 40L
    private const val CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS = 10_000L
    private const val CONTROL_CODE_RECOVERY_QUEUE_POLL_MILLIS = 250L
    private const val CONTROL_CODE_RESELECT_FRESH_TICKET_MAX_AGE_MILLIS = 5_000L
    private const val LATEST_TICKET_RESELECT_RELAUNCH_DELAY_MILLIS = 300L
    private const val LATEST_TICKET_RESELECT_RECOVERY_BUDGET_MILLIS = TICKET_WAKE_RECOVERY_BUDGET_MILLIS
    private const val LATEST_TICKET_RESELECT_MAX_RECOVERY_ACTIONS = TICKET_WAKE_RECOVERY_MAX_ACTIONS
    private const val LATEST_TICKET_RESELECT_SETTLE_TIMEOUT_MILLIS = 20_000L
    private const val LATEST_TICKET_RESELECT_PROOF_HOLD_MILLIS =
      LATEST_TICKET_RESELECT_SETTLE_TIMEOUT_MILLIS + 5_000L
    private const val LATEST_TICKET_RESELECT_PROOF_NUDGE_MILLIS = 1_000L
    private const val LATEST_TICKET_RESELECT_PROOF_IDLE_STOP_GRACE_MILLIS = 2_000L
    private const val LATEST_TICKET_RESELECT_ACTIVE_WINDOW_MILLIS =
      LATEST_TICKET_RESELECT_RECOVERY_BUDGET_MILLIS + LATEST_TICKET_RESELECT_SETTLE_TIMEOUT_MILLIS + 5_000L
    private const val CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS = 0L
    private const val CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS = 250L
    private const val CONTROL_CODE_VISUAL_STATE_POLL_MILLIS = 40L
    private const val CONTROL_CODE_VISUAL_STATE_RETRY_MILLIS = 50L
    private const val CONTROL_CODE_SUBMIT_VISUAL_REQUIRED_SAMPLES = 2
    private const val CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES = 4
    private const val CONTROL_CODE_SUBMIT_VISUAL_PROBE_WAIT_MILLIS = 350L
    private const val CONTROL_CODE_SUBMIT_VISUAL_SAMPLE_GAP_MILLIS = 250L
    private const val CONTROL_CODE_ROOT_TRANSACTION_TIMEOUT_MILLIS = 4_000L
    private const val CONTROL_CODE_ROOT_SUBMIT_TIMEOUT_MILLIS = 2_500L
    private const val CONTROL_CODE_FAST_POLL_MILLIS = 90L
    private const val CONTROL_CODE_FAST_BUTTON_X_FRACTION = 0.23f
    private const val CONTROL_CODE_FAST_BUTTON_Y_FRACTION = 0.136f
    private const val CONTROL_CODE_FAST_POPUP_INPUT_X_FRACTION = 0.50f
    private const val CONTROL_CODE_FAST_POPUP_INPUT_Y_FRACTION = 0.512f
    private const val CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_X_FRACTION = 0.738f
    private const val CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_Y_FRACTION = 0.573f
    private const val CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS = 60L
    private const val CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = 15_000L
    private const val CONTROL_CODE_STALE_PREPARE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS
    private const val CONTROL_CODE_SNAP_UNSAFE_STATE_MEMORY_MAX_AGE_MILLIS = 10_000L
    private const val RECENT_CONTROL_CODE_RESULT_CACHE_SIZE = 6
    private const val CONTROL_CODE_RESULT_CACHE_TTL_MILLIS = 90_000L
    private const val CONTROL_CODE_RESULT_IMAGE_CROP_PADDING = 32
    private const val CONTROL_CODE_RESULT_IMAGE_MIN_CROP_SIZE = 160
    private const val PNG_BASE64_BEGIN = "PNG_BASE64_BEGIN"
    private const val PNG_BASE64_END = "PNG_BASE64_END"
    private const val SNAP_TARGET_CONTROL_CODE_BUTTON = "control_code_button"
    private val CONTROL_CODE_REQUEST_DIGITS_REGEX = Regex("""^[0-9]{2,8}$""")
    private const val CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS = 10_000L
    private const val CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 1_400L
    private const val CONTROL_CODE_FAST_CLOSE_PROOF_MAX_SAMPLES = 2
    private const val CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS = 75L
    private const val CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT = 2
    private const val CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS = 200L
    private const val CONTROL_CODE_GENERATED_HEAL_MAX_CLOSE_ATTEMPTS = 2
    private const val TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS = 12_000L
    private const val CONTROL_EXIT_RESULT_CLOSE_X_FRACTION = 0.82f
    private const val CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION = 0.565f
    private const val CONTROL_CODE_TRANSITION_GRACE_MILLIS = 3_000L
    private const val REMOTE_TAP_FOREGROUND_SETTLE_MILLIS = 350L
    private const val CACHED_FOREGROUND_MAX_AGE_MILLIS = 2_000L
    private const val FOREGROUND_RECOVERY_CONFIRMATION_COUNT = 2
    private const val FOREGROUND_RECOVERY_COOLDOWN_MILLIS = 6_000L
    private const val VIVI_CONTROL_CODE_MIN_X_FRACTION = 0.04f
    private const val VIVI_CONTROL_CODE_MAX_X_FRACTION = 0.45f
    private const val VIVI_CONTROL_CODE_MIN_Y_FRACTION = 0.10f
    private const val VIVI_CONTROL_CODE_MAX_Y_FRACTION = 0.18f
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
