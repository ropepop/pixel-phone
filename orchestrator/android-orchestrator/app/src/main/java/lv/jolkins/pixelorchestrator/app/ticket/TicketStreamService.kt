package lv.jolkins.pixelorchestrator.app.ticket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lv.jolkins.pixelorchestrator.app.MainActivity
import lv.jolkins.pixelorchestrator.app.SupervisorService
import lv.jolkins.pixelorchestrator.app.phoneautomation.AndroidPhoneAutomationAccessibilityRecoveryEnvironment
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationAccessibilityRecovery
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
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
import kotlin.math.roundToInt
import kotlin.time.Duration
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
    val input: TicketViviPageAction,
    val preKeyboardSubmit: TicketViviPageAction,
    val keyboardOpenSubmit: TicketViviPageAction,
    val inputSource: String,
    val preKeyboardSubmitSource: String,
    val keyboardOpenSubmitSource: String
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
    val failureReason: String = "control_code_result_timeout"
  )

  private data class RootViviObservation(
    val state: TicketViviRecoveryState,
    val hierarchy: String?,
    val durationMillis: Long,
    val error: String = ""
  )

  private data class ViviLoginCredentials(
    val email: String,
    val secret: String
  )

  private enum class ControlCodeSubmitTransition {
    GENERATED_RESULT,
    RETURNED_NO_RESULT,
    STILL_ON_POPUP,
    TIMEOUT
  }

  private class TicketVideoSendState {
    var inFlight: Boolean = false
    var inFlightSinceMillis: Long = 0L
    var pendingFrame: ByteArray? = null
    var pendingKeyFrame: Boolean = false
    var pendingQueuedAtMillis: Long = 0L
    var waitingForKeyFrame: Boolean = false
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val rootExecutor = TicketRootCommandWorker()
  private val inputRootExecutor = TicketRootCommandWorker()
  private val wakeRootExecutor = TicketRootCommandWorker()
  private val foregroundRootExecutor = TicketRootCommandWorker()
  private val runtimeStateStore = TicketRuntimeStateStore(rootExecutor, json)
  private val serverMutex = Mutex()
  private val controlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val protectedControlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val canceledRigasSatiksmeBatchIds = Collections.synchronizedSet(mutableSetOf<String>())
  private val videoClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val clientInfo = mutableMapOf<TicketWebSocket, TicketClientInfo>()
  private val videoSendStates = mutableMapOf<TicketWebSocket, TicketVideoSendState>()
  private val recentClientTelemetry = ArrayDeque<Pair<Long, String>>()
  private val recentTicketEvents = ArrayDeque<Triple<Long, String, String>>()
  private val encoderLock = Any()
  private val sessionMutex = Mutex()
  private val controlCodeRequestMutex = Mutex()
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
  private var controlCodeSoftCheckJob: Job? = null
  private var controlExitCleanupJob: Job? = null
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
  private val viviStateMemory = TicketViviStateMemory(::persistViviStateMemory)
  private lateinit var ticketRecoveryCoordinator: TicketRecoveryCoordinator
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
  @Volatile private var lastForegroundGuardRecentTicketDetailSkipAtMillis: Long = 0L
  @Volatile private var lastForegroundGuardInconclusiveAtMillis: Long = 0L
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
  @Volatile private var lastSessionReuseReason: String? = null
  @Volatile private var lastSessionReuseDurationMillis: Long? = null
  @Volatile private var lastSessionReuseCompletedAtMillis: Long = 0L
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
  @Volatile private var lastControlCodeRequestPhases: Map<String, Long> = emptyMap()
  @Volatile private var lastControlCodeRequestCompletedAtMillis: Long = 0L
  @Volatile private var lastControlCodeCommandOwner: String? = null
  @Volatile private var lastControlCodeCommandApp: String? = null
  @Volatile private var lastControlCodeCommandFlow: String? = null
  @Volatile private var lastRejectedControlCodeCommandOwner: String? = null
  @Volatile private var lastRejectedControlCodeCommandApp: String? = null
  @Volatile private var lastRejectedControlCodeCommandFlow: String? = null
  @Volatile private var lastRejectedControlCodeCommandReason: String? = null
  @Volatile private var lastRejectedControlCodeCommandAtMillis: Long = 0L
  @Volatile private var pendingControlCodeBrowserCaptureRequestId: String? = null
  @Volatile private var pendingControlCodeBrowserCaptureAck: ControlCodeBrowserCaptureAck? = null
  @Volatile private var lastControlCodeBrowserCaptureReason: String? = null
  @Volatile private var lastControlCodeBrowserCaptureCompletedAtMillis: Long = 0L
  @Volatile private var lastPixelTicketEventSeq: Long = 0L
  @Volatile private var lastPixelTicketState: String = ""
  @Volatile private var lastPixelTicketEventReason: String = ""
  @Volatile private var lastPixelTicketEventRequestId: String = ""
  @Volatile private var lastPixelTicketEventStreamEpoch: Long = 0L
  @Volatile private var lastPixelTicketEventFrameSequence: Long = 0L
  @Volatile private var lastPixelTicketEventMinFrameSequence: Long = 0L
  @Volatile private var lastPixelTicketEventSentAtMillis: Long = 0L
  @Volatile private var duplicateControlCodeResultCount: Long = 0L
  @Volatile private var lastDuplicateControlCodeRequestId: String? = null
  @Volatile private var lastDuplicateControlCodeResultAtMillis: Long = 0L
  private val recentControlCodeResultMessages = mutableMapOf<String, Pair<Long, String>>()
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
  @Volatile private var lastControlExitCleanupFreshFrameVerified: Boolean = false
  @Volatile private var lastPostCleanupFreshFrameVerifiedAtMillis: Long = 0L
  @Volatile private var lastPostCleanupFreshFrameVerificationReason: String? = null
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
  @Volatile private var lastWakeReason: String? = null
  @Volatile private var lastWakeStartedAtMillis: Long = 0L
  @Volatile private var lastWakeCommandMillis: Long? = null
  @Volatile private var lastWakeInteractiveMillis: Long? = null
  @Volatile private var lastWakeViviForegroundMillis: Long? = null
  @Volatile private var lastWakeTicketReadyMillis: Long? = null
  @Volatile private var lastWakeTotalMillis: Long? = null
  @Volatile private var lastWakeSucceeded: Boolean? = null
  @Volatile private var lastWakeSlowestPhase: String? = null
  @Volatile private var lastWakeSlowestPhaseDurationMillis: Long? = null
  @Volatile private var lastWakeFailureReason: String? = null
  @Volatile private var lastWakePreviousPhaseElapsedMillis: Long = 0L
  @Volatile private var lastRootReadinessResult: String = "not_run"
  @Volatile private var lastRootReadinessDurationMillis: Long? = null
  @Volatile private var lastRootReadinessAtMillis: Long = 0L
  @Volatile private var lastViviLoginCredentialsConfigured: Boolean = false
  @Volatile private var lastViviLoginStatus: String = "idle"
  @Volatile private var lastViviLoginReason: String = ""
  @Volatile private var lastViviLoginCompletedAtMillis: Long = 0L
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
      rootExecutor = wakeRootExecutor,
      runInput = ::runNonTouchInput,
      collapseSystemUi = { reason -> collapseNotificationShade(reason) },
      scheduleBrightnessGuard = ::scheduleTicketBrightnessGuard,
      stateMemory = viviStateMemory,
      returnToOrchestrator = ::returnToOrchestrator,
      loginIfNeeded = ::loginViviIfNeeded,
      onHardReset = ::recordViviHardReset,
      onRecoveryResult = ::onTicketRecoveryResult
    )
    restoreViviStateMemory()
    ensureNotificationChannel()
    serviceScope.launch(Dispatchers.IO) {
      rootHardwareH264CaptureEngine.cleanupStaleProcesses()
      rootHardwareH264CaptureEngine.probe()
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
    pendingRigasSatiksmeReturnCleanupJob?.cancel()
    pendingRigasSatiksmeReturnCleanupJob = null
    pendingRigasSatiksmeReturnCleanupStarted = false
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
          protectedControlClients.remove(client)
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
    extendStartupDisconnectGrace()
    closeDuplicateViewerClients(info)
    if (video) {
      videoClients.add(client)
      lastVideoClientConnectedAtMillis = SystemClock.elapsedRealtime()
      if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
        rootHardwareH264CaptureEngine.requestBurst("video_client_connected")
      }
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
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (reason == "relay_websocket_start") {
          serviceScope.launch {
            delay(2000)
            if (controlCodeRequestActive()) {
              recordTicketEvent("session_start_deferred_for_control_code", reason)
              return@launch
            }
            startTicketSession()
          }
        } else {
          startTicketSession()
        }
        sendStatus(client)
        sendInactivityStatus(client)
      }
      "stop" -> {
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val explicit = element["explicit"]?.jsonPrimitive?.booleanOrNull == true ||
          reason == TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP
        if (explicit) {
          if (staleExplicitStopSupersededByNewerClient(client, reason)) {
            recordTicketEvent("session_stop_ignored_newer_client", reason.ifBlank { TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP })
          } else {
            stopTicketSession(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP)
          }
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
      "generate_control_code" -> {
        val requestId = element["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val digits = element["digits"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val owner = element["owner"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val app = element["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val flow = element["flow"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val resultImage = element["resultImage"]?.jsonPrimitive?.booleanOrNull == true
        val rsQueueHint = element["rsQueueHint"]?.jsonObject?.let { hint ->
          RigasSatiksmeQueueHint(
            pendingAfterThis = hint["pendingAfterThis"]?.jsonPrimitive?.intOrNull ?: 0,
            ticketPriorityActive = hint["ticketPriorityActive"]?.jsonPrimitive?.booleanOrNull == true
          )
        } ?: RigasSatiksmeQueueHint()
        serviceScope.launch {
          handleGenerateControlCode(client, requestId, digits, owner, app, flow, resultImage, rsQueueHint)
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
      "prepare_control_code" -> {
        val owner = element["owner"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val app = element["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val flow = element["flow"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!controlCodeCommandEnvelopeMatches(owner, app, flow, TicketScreenConfig.TICKET_QR_OWNER_TICKET, TicketScreenConfig.TICKET_QR_APP_VIVI, TicketScreenConfig.TICKET_QR_FLOW_CONTROL_CODE)) {
          recordRejectedControlCodeCommand("", owner, app, flow, "wrong_command_owner")
          return
        }
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull ?: "dialog_open"
        handlePrepareControlCode(reason)
      }
      "control_code_browser_capture" -> {
        val requestId = element["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val owner = element["owner"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val app = element["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val flow = element["flow"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!controlCodeCommandEnvelopeMatches(owner, app, flow, TicketScreenConfig.TICKET_QR_OWNER_TICKET, TicketScreenConfig.TICKET_QR_APP_VIVI, TicketScreenConfig.TICKET_QR_FLOW_CONTROL_CODE)) {
          recordRejectedControlCodeCommand(requestId, owner, app, flow, "wrong_command_owner")
          return
        }
        val ok = element["ok"]?.jsonPrimitive?.booleanOrNull == true ||
          element["accepted"]?.jsonPrimitive?.booleanOrNull == true
        val reason = element["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val frameEpoch = element["candidateFrameEpoch"]?.jsonPrimitive?.longOrNull ?: 0L
        val frameSequence = element["candidateFrameSequence"]?.jsonPrimitive?.longOrNull ?: 0L
        handleControlCodeBrowserCapture(requestId, ok, reason, frameEpoch, frameSequence)
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
    return withTimeoutOrNull(SESSION_START_TIMEOUT_MILLIS) {
      sessionMutex.withLock {
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
        persistRuntimeState("session_start_already_preparing")
        return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
      }
      if (canReuseActiveHardwareStreamWithoutRootRevalidation("session_start_already_active")) {
        updateTicketSessionState(TICKET_SESSION_LIVE, "session_start_already_active")
        lastSessionStopReason = null
        markViewerInput("session_start_already_active")
        lastMessage = activeCaptureModeMessage()
        scheduleTicketBrightnessGuard("session_start_already_active")
        startForegroundGuard()
        ensureEncoderIfPossible()
        broadcastStatus()
        persistRuntimeState("session_start_already_active")
        return TicketSessionResponse(ok = true, state = "active", message = lastMessage)
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
        persistRuntimeState("session_start_already_active_revalidate")
        return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
      }
      updateTicketSessionState(TICKET_SESSION_LIVE, "session_start_already_active")
      lastSessionStopReason = null
      markViewerInput("session_start_already_active")
      lastMessage = activeCaptureModeMessage()
      scheduleTicketBrightnessGuard("session_start_already_active")
      startForegroundGuard()
      ensureEncoderIfPossible()
      broadcastStatus()
      persistRuntimeState("session_start_already_active")
      return TicketSessionResponse(ok = true, state = "active", message = lastMessage)
    }
    val sourceSize = currentDisplaySize()
    var hardwareCapture = rootHardwareH264CaptureEngine.snapshot()
    if (!hardwareCapture.available) {
      rootHardwareH264CaptureEngine.probe()
      hardwareCapture = rootHardwareH264CaptureEngine.snapshot()
    }
    val hardwareUnavailableReason = when {
      !hardwareCapture.available -> "hardware_h264_unavailable:${hardwareCapture.state}"
      hardwareMarkedUnreliable() -> hardwareUnreliableReason ?: "hardware_h264_unreliable"
      else -> null
    }
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_requested")
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
      persistRuntimeState("hardware_h264_unavailable")
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
    recordTicketEvent("session_started", "mode=$activeCaptureMode")
    startForegroundGuard()
    scheduleRootHardwareH264CaptureStart("session_start_root_hardware_h264_capture", suppressBlackout = false)
    scheduleDeferredSessionStartMaintenance("session_start")
    broadcastStatus()
    persistRuntimeState("session_start_${modeLabel}_prepare")
    return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
  }

  private fun canReuseActiveHardwareStreamWithoutRootRevalidation(reason: String): Boolean {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 || !hardwareCaptureVerified) {
      return false
    }
    val nowMillis = SystemClock.elapsedRealtime()
    val frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis) ?: return false
    if (frameAgeMillis > LIVE_FRAME_MAX_AGE_MILLIS) {
      return false
    }
    val current = viviStateMemory.current()
    val currentAgeMillis = ageMillis(current.observedAtMillis, nowMillis)
    if (
      current.state == TicketViviRecoveryState.TICKET_DETAIL &&
      currentAgeMillis != null &&
      currentAgeMillis in 0..ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS
    ) {
      recordTicketEvent(
        "session_start_active_reuse_fast",
        "reason=$reason proof=current_ticket_detail frame_age_ms=$frameAgeMillis proof_age_ms=$currentAgeMillis"
      )
      recordActiveSessionReuseTiming(reason, nowMillis)
      return true
    }
    val recent = viviStateMemory.recentTicketDetailWithin(ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS)
    if (
      recent != null &&
      current.state in setOf(TicketViviRecoveryState.UNKNOWN_VIVI, TicketViviRecoveryState.BLANK) &&
      current.source in setOf("fast_empty", "root_empty")
    ) {
      val proofAgeMillis = ageMillis(recent.observedAtMillis, nowMillis) ?: 0L
      recordTicketEvent(
        "session_start_active_reuse_fast",
        "reason=$reason proof=recent_ticket_detail_after_inconclusive frame_age_ms=$frameAgeMillis proof_age_ms=$proofAgeMillis current=${current.state.name}:${current.source}"
      )
      recordActiveSessionReuseTiming(reason, nowMillis)
      return true
    }
    return false
  }

  private fun recordActiveSessionReuseTiming(reason: String, startedAtMillis: Long) {
    val completedAtMillis = SystemClock.elapsedRealtime()
    lastSessionReuseReason = reason
    lastSessionReuseDurationMillis = (completedAtMillis - startedAtMillis).coerceAtLeast(0L)
    lastSessionReuseCompletedAtMillis = completedAtMillis
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
      val activeClients = totalClientCount()
      if (activeClients > 0) {
        recordTicketEvent("session_stop_ignored_active_clients", "http_browser_stop clients=$activeClients")
        broadcastStatus()
        TicketSessionResponse(ok = true, state = ticketSessionState, message = lastMessage)
      } else {
        stopTicketSession(TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP)
      }
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
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      rootH264BlankProbeJob?.cancel()
      rootH264BlankProbeJob = null
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
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      rootH264BlankProbeJob?.cancel()
      rootH264BlankProbeJob = null
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
      persistRuntimeState("session_stop_$reason")
      TicketSessionResponse(ok = true, state = "stopped", message = lastMessage)
    }
    closeAllClients("session_stop_$reason")
    if (ticketServiceEnabled()) recordTicketEvent("root_hardware_h264_ready_waiting", "session_stop_$reason")
    if (TicketSessionStopPolicy.shouldResetViviToTicket(reason)) {
      val stoppedState = observeRootViviState("session_stop_$reason")
      if (stoppedState.state == TicketViviRecoveryState.TICKET_DETAIL) {
        Log.i(TAG, "ticket_recovery_skipped_ticket_detail reason=$reason")
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
    lastForegroundGuardInconclusiveAtMillis = 0L
    resetControlCodeMode("foreground_guard_cancelled", broadcast = false)
    postRemoteTapForegroundCheckJob?.cancel()
    postRemoteTapForegroundCheckJob = null
    controlCodeSoftCheckJob?.cancel()
    controlCodeSoftCheckJob = null
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
        }.onFailure { error -> Log.w(TAG, "ticket_screen_wake_recreate_release_failed", error) }
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
      .onFailure { error -> Log.w(TAG, "ticket_screen_wake_hold_failed reason=$reason", error) }
  }

  private fun releaseTicketScreenAwake() {
    val lock = ticketScreenWakeLock ?: return
    ticketScreenWakeLock = null
    ticketScreenWakeLockUsesTouchBrightnessOwner = null
    runCatching {
      if (lock.isHeld) {
        lock.release()
      }
    }.onFailure { error -> Log.w(TAG, "ticket_screen_wake_release_failed", error) }
  }

  private fun ticketScreenInteractive(): Boolean {
    return getSystemService(PowerManager::class.java)?.isInteractive == true
  }

  private fun beginTicketWake(reason: String): Long {
    val nowMillis = SystemClock.elapsedRealtime()
    lastWakeReason = reason
    lastWakeStartedAtMillis = nowMillis
    lastWakeCommandMillis = null
    lastWakeInteractiveMillis = null
    lastWakeViviForegroundMillis = null
    lastWakeTicketReadyMillis = null
    lastWakeTotalMillis = null
    lastWakeSucceeded = null
    lastWakeSlowestPhase = null
    lastWakeSlowestPhaseDurationMillis = null
    lastWakeFailureReason = null
    lastWakePreviousPhaseElapsedMillis = 0L
    recordTicketEvent("wake_started", reason)
    return nowMillis
  }

  private fun recordTicketWakePhase(phase: String, startedAtMillis: Long, nowMillis: Long = SystemClock.elapsedRealtime()) {
    if (startedAtMillis <= 0L || startedAtMillis != lastWakeStartedAtMillis) {
      return
    }
    if (phase == "vivi_foreground" && lastWakeViviForegroundMillis != null) {
      return
    }
    if (phase == "ticket_ready" && lastWakeTicketReadyMillis != null) {
      return
    }
    val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
    val phaseDurationMillis = (elapsedMillis - lastWakePreviousPhaseElapsedMillis).coerceAtLeast(0L)
    val previousSlowest = lastWakeSlowestPhaseDurationMillis ?: -1L
    if (phaseDurationMillis > previousSlowest) {
      lastWakeSlowestPhase = phase
      lastWakeSlowestPhaseDurationMillis = phaseDurationMillis
    }
    lastWakePreviousPhaseElapsedMillis = elapsedMillis
    when (phase) {
      "wake_command" -> lastWakeCommandMillis = elapsedMillis
      "screen_interactive" -> lastWakeInteractiveMillis = elapsedMillis
      "vivi_foreground" -> if (lastWakeViviForegroundMillis == null) lastWakeViviForegroundMillis = elapsedMillis
      "ticket_ready" -> lastWakeTicketReadyMillis = elapsedMillis
    }
    recordTicketEvent("wake_phase", "phase=$phase elapsed_ms=$elapsedMillis duration_ms=$phaseDurationMillis")
  }

  private fun finishTicketWake(startedAtMillis: Long, succeeded: Boolean, reason: String) {
    if (startedAtMillis <= 0L || startedAtMillis != lastWakeStartedAtMillis) {
      return
    }
    val nowMillis = SystemClock.elapsedRealtime()
    val totalMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
    lastWakeTotalMillis = totalMillis
    lastWakeSucceeded = succeeded
    lastWakeFailureReason = reason.takeUnless { succeeded }
    if (lastWakeSlowestPhase == null) {
      lastWakeSlowestPhase = "total"
      lastWakeSlowestPhaseDurationMillis = totalMillis
    }
    recordTicketEvent(
      "wake_finished",
      "success=$succeeded reason=$reason total_ms=$totalMillis slowest=${lastWakeSlowestPhase.orEmpty()}:${lastWakeSlowestPhaseDurationMillis ?: 0L}"
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
    if (ticketWakeInProgress(now)) {
      recordTicketEvent("active_guard_deferred", "$reason wake_in_progress=true")
      return
    }
    if (now - lastViviPageEnforceAtMillis < VIVI_PAGE_ENFORCE_INTERVAL_MILLIS) {
      return
    }
    lastViviPageEnforceAtMillis = now
    var result = observeFastViviState("active_guard:$reason")
    if (result?.state == TicketViviRecoveryState.TICKET_DETAIL && recentForegroundGuardTicketDetailStillFresh(now)) {
      resetForegroundViolationConfirmation()
      if (shouldLogForegroundGuardRecentTicketDetailSkip(now)) {
        recordTicketEvent("active_guard_recent_ticket_detail", "reason=$reason")
      }
      return
    }
    if (result == null && foregroundGuardInconclusiveBackoffActive(now)) {
      recordTicketEvent("active_guard_inconclusive_backoff", "reason=$reason")
      return
    }
    if (result == null) {
      result = observeRootViviState(
        reason = "active_guard:$reason",
        timeoutMillis = TICKET_ROOT_HIERARCHY_DUMP_TIMEOUT_MILLIS
      )
    }
    if (controlSensitiveWindowActive()) {
      recordTicketEvent("active_guard_deferred_after_observe", "$reason state=${result.state.name} control_sensitive=true")
      return
    }
    if (result.state == TicketViviRecoveryState.TICKET_DETAIL) {
      lastForegroundGuardInconclusiveAtMillis = 0L
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
        lastForegroundGuardInconclusiveAtMillis = 0L
        resetForegroundViolationConfirmation()
        return
      }
      recordTicketEvent("active_guard_return_raw_failed", result.state.name)
    }
    if (attemptActiveGuardRecoveryAction(result.state, result.hierarchy, reason)) {
      lastForegroundGuardInconclusiveAtMillis = 0L
      resetForegroundViolationConfirmation()
      return
    }
    Log.w(TAG, "ticket_root_active_check_failed reason=$reason state=${result.state} error=${result.error}")
    recordTicketEvent("active_guard_failed", "root:${result.state}")
    if (
      result.state == TicketViviRecoveryState.UNKNOWN_VIVI ||
      result.state == TicketViviRecoveryState.BLANK
    ) {
      lastForegroundGuardInconclusiveAtMillis = now
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
    val action = TicketViviPageEnforcer.recoveryActionForHierarchy(hierarchy) ?: return false
    val input = if (action.x >= 0 && action.y >= 0) {
      runFastNonTouchInput("input tap ${action.x} ${action.y}", "active_guard_recovery_action:${action.reason}")
    } else {
      runFastNonTouchInput("input keyevent KEYCODE_BACK", "active_guard_recovery_action:${action.reason}")
    }
    recordTicketEvent(
      "active_guard_recovery_action",
      "state=${state.name} action=${action.reason} ok=${input.ok} duration_ms=${input.durationMs} reason=$reason"
    )
    return input.ok
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
      lastViviLoginCredentialsConfigured = false
      recordViviLoginStatus("credentials_missing", reason, completed = true)
      recordTicketEvent("vivi_login_credentials_missing", "reason=$reason")
      return null
    }
    val credentials = parseViviLoginCredentials(result.stdout)
    lastViviLoginCredentialsConfigured = credentials != null
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
    lastViviLoginStatus = status
    lastViviLoginReason = sanitizeViviLoginReason(reason)
    if (completed) {
      lastViviLoginCompletedAtMillis = SystemClock.elapsedRealtime()
    }
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

  private fun foregroundGuardInconclusiveBackoffActive(nowMillis: Long): Boolean {
    if (!streamActive || !hardwareCaptureVerified || ticketSessionState != TICKET_SESSION_LIVE) {
      return false
    }
    if (lastForegroundGuardInconclusiveAtMillis <= 0L) {
      return false
    }
    val current = viviStateMemory.current()
    if (
      current.observedAtMillis > 0L &&
      current.state != TicketViviRecoveryState.TICKET_DETAIL &&
      current.state != TicketViviRecoveryState.UNKNOWN_VIVI &&
      current.state != TicketViviRecoveryState.BLANK
    ) {
      return false
    }
    val ageMillis = nowMillis - lastForegroundGuardInconclusiveAtMillis
    return ageMillis in 0..FOREGROUND_GUARD_INCONCLUSIVE_BACKOFF_MILLIS
  }

  private fun shouldLogForegroundGuardRecentTicketDetailSkip(nowMillis: Long): Boolean {
    if (nowMillis - lastForegroundGuardRecentTicketDetailSkipAtMillis < FOREGROUND_GUARD_RECENT_TICKET_LOG_INTERVAL_MILLIS) {
      return false
    }
    lastForegroundGuardRecentTicketDetailSkipAtMillis = nowMillis
    return true
  }

  private fun foregroundGuardCanTrustLiveTicketDetailWithoutRoot(nowMillis: Long): Boolean {
    if (!streamActive || !hardwareCaptureVerified || ticketSessionState != TICKET_SESSION_LIVE) {
      return false
    }
    if (
      lastWakeSucceeded == true &&
      lastWakeTicketReadyMillis != null &&
      lastWakeStartedAtMillis > 0L &&
      nowMillis - lastWakeStartedAtMillis in 0..FOREGROUND_GUARD_RECENT_WAKE_READY_ROOTLESS_MILLIS
    ) {
      return true
    }
    val current = viviStateMemory.current()
    if (current.state != TicketViviRecoveryState.TICKET_DETAIL || current.observedAtMillis <= 0L) {
      return false
    }
    val ageMillis = nowMillis - current.observedAtMillis
    return ageMillis in 0..FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS
  }

  private suspend fun dumpViviHierarchy(
    fresh: Boolean = false,
    timeoutMillis: Long? = null
  ): RootResult {
    return TicketScreenObserver.dumpViviHierarchy(
      rootExecutor = wakeRootExecutor,
      reason = "service",
      forceFresh = fresh,
      timeoutMillis = timeoutMillis ?: TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS
    )
  }

  private fun recordRootReadiness(result: String, durationMillis: Long?) {
    lastRootReadinessResult = result
    lastRootReadinessDurationMillis = durationMillis
    lastRootReadinessAtMillis = SystemClock.elapsedRealtime()
  }

  private suspend fun observeFastViviState(reason: String): RootViviObservation? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val hierarchy = fastVisibleHierarchy(TicketScreenConfig.VIVI_PACKAGE, reason)
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    if (hierarchy.isBlank()) {
      viviStateMemory.record(
        state = TicketViviRecoveryState.UNKNOWN_VIVI,
        ticketId = null,
        source = "fast_empty",
        reason = reason
      )
      recordRootReadiness("$reason:fast_empty:UNKNOWN_VIVI", durationMillis)
      return null
    }
    val state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    viviStateMemory.record(
      state = state,
      ticketId = TicketViviPageEnforcer.ticketIdForHierarchy(hierarchy),
      source = "fast",
      reason = reason
    )
    recordRootReadiness("$reason:fast:${state.name}", durationMillis)
    return RootViviObservation(state, hierarchy, durationMillis)
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

  private suspend fun controlCodeRequestHierarchy(): String? {
    return observeRootViviState(
      reason = "control_code_request_hierarchy",
      timeoutMillis = CONTROL_CODE_REQUEST_HIERARCHY_TIMEOUT_MILLIS
    ).hierarchy
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
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && hardwareCaptureVerified) {
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
        targetFps = TicketScreenConfig.ROOT_HARDWARE_H264_FPS
      )
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      Log.i(TAG, "ticket_root_hardware_h264_capture_requested width=${size.width} height=${size.height} bitrate=${TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE} video_clients=${videoClients.size}")
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
      broadcastStatus()
      persistRuntimeState("root_hardware_h264_capture_state")
    }
  }

  private fun unexpectedHardwareEncoderRestart(health: TicketHardwareH264Health): Boolean {
    val reason = health.lastExitReason.orEmpty()
    if (reason.startsWith("requested_restart:")) {
      return false
    }
    when (reason) {
      "hardware_encoder_exit_0",
      "hardware_encoder_exit_143",
      "capture_config_changed" -> return false
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

  private fun restartActiveStream(reason: String) {
    if (!streamActive) {
      return
    }
    recordTicketEvent("stream_restart_requested", reason)
    resetFrameEpoch("root_hardware_h264_fresh_request_$reason", active = true)
    streamSize?.let(::broadcastConfig)
    rootHardwareH264CaptureEngine.requestKeyFrame("restart_stream:$reason")
    ensureEncoderIfPossible()
    broadcastStatus()
  }

  private fun restartActiveStreamEngine(reason: String) {
    if (!streamActive) {
      return
    }
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
    broadcastStatus()
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
    Log.i(TAG, "ticket_stream_config_sent width=${size.width} height=${size.height} video_clients=${videoClients.size}")
    videoClientSnapshot().forEach { client ->
      client.sendText(message)
    }
  }

  private fun sendConfigAndWarmStart(client: TicketWebSocket, size: TicketStreamSize) {
    client.sendText(configMessage(size))
    lastConfigSentAtMillis = SystemClock.elapsedRealtime()
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      rootHardwareH264CaptureEngine.requestBurst("video_client_warm_start")
    }
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
    if ((firstVisibleFrame || ticketSessionState == TICKET_SESSION_STARTING) && hardwareCaptureVerified) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_hardware_h264_first_visible_frame")
      lastMessage = "Ticket session is active through hardware H.264 capture"
      broadcastStatus()
      persistRuntimeState("root_hardware_h264_first_visible_frame")
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
      persistRuntimeState("secure_capture_blocked")
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
    keyFrameRequests += 1
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
    if (!streamActive || activeCaptureMode == CAPTURE_MODE_IDLE || videoClients.isEmpty()) {
      return false
    }
    if (hardwareStartupStillPreparing()) {
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

  private fun hardwareStartupStillPreparing(): Boolean {
    return activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
      (ticketSessionState == TICKET_SESSION_STARTING || !hardwareCaptureVerified || encodedFrames == 0L)
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

  private fun staleExplicitStopSupersededByNewerClient(client: TicketWebSocket, reason: String): Boolean {
    val normalizedReason = reason.trim().ifBlank { TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP }
    if (normalizedReason == "relay_no_viewers") {
      return false
    }
    if (normalizedReason != "relay_no_viewers" && normalizedReason != TicketSessionStopPolicy.BROWSER_EXPLICIT_STOP) {
      return false
    }
    if (totalClientCount() == 0) {
      return false
    }
    return synchronized(clientInfo) {
      val currentGeneration = clientInfo[client]?.generation ?: return@synchronized false
      clientInfo.values.any { info -> info.generation > currentGeneration }
    }
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
    val hardware = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    val activeRootCapture = TicketRootCaptureHealth(
      supported = hardware.available,
      active = hardware.active,
      state = hardware.state,
      message = hardware.message,
      encoderName = hardware.encoderName,
      width = hardware.width,
      height = hardware.height,
      bitrate = hardware.bitrate,
      frames = hardware.frames,
      keyFrames = hardware.keyFrames,
      lastFrameAgoMillis = hardware.lastFrameAgoMillis,
      lastKeyFrameAgoMillis = hardware.lastFrameAgoMillis,
      lastStartAgoMillis = hardware.lastStartAgoMillis,
      restarts = hardware.restartCount,
      lastRestartReason = hardware.lastExitReason,
      lastRestartAgoMillis = hardware.lastExitAgoMillis
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

  private fun persistViviStateMemory(snapshot: TicketViviStateMemorySnapshot) {
    val editor = getSharedPreferences(TICKET_VIVI_MEMORY_PREFS, Context.MODE_PRIVATE).edit()
      .putString(KEY_VIVI_MEMORY_CURRENT_STATE, snapshot.state.name)
      .putString(KEY_VIVI_MEMORY_CURRENT_TICKET_ID, snapshot.ticketId.orEmpty())
      .putString(KEY_VIVI_MEMORY_CURRENT_SOURCE, snapshot.source)
      .putString(KEY_VIVI_MEMORY_CURRENT_REASON, snapshot.reason)
      .putLong(KEY_VIVI_MEMORY_CURRENT_WALL_MILLIS, System.currentTimeMillis())
    if (snapshot.state == TicketViviRecoveryState.TICKET_DETAIL) {
      editor
        .putString(KEY_VIVI_MEMORY_TICKET_ID, snapshot.ticketId.orEmpty())
        .putString(KEY_VIVI_MEMORY_TICKET_SOURCE, snapshot.source)
        .putString(KEY_VIVI_MEMORY_TICKET_REASON, snapshot.reason)
        .putLong(KEY_VIVI_MEMORY_TICKET_WALL_MILLIS, System.currentTimeMillis())
    }
    editor.commit()
  }

  private fun restoreViviStateMemory() {
    val prefs = getSharedPreferences(TICKET_VIVI_MEMORY_PREFS, Context.MODE_PRIVATE)
    val nowWallMillis = System.currentTimeMillis()
    val ticketWallMillis = prefs.getLong(KEY_VIVI_MEMORY_TICKET_WALL_MILLIS, 0L)
    if (ticketWallMillis > 0L) {
      val ticketAgeMillis = (nowWallMillis - ticketWallMillis).coerceAtLeast(0L)
      if (ticketAgeMillis <= TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS) {
        val restored = viviStateMemory.seedTicketDetail(
          ticketId = prefs.getString(KEY_VIVI_MEMORY_TICKET_ID, "").orEmpty().ifBlank { null },
          observedAgeMillis = ticketAgeMillis,
          source = "persisted:${prefs.getString(KEY_VIVI_MEMORY_TICKET_SOURCE, "unknown").orEmpty()}",
          reason = "service_restore:${prefs.getString(KEY_VIVI_MEMORY_TICKET_REASON, "unknown").orEmpty()}"
        )
        recordTicketEvent(
          "vivi_ticket_detail_memory_restored",
          "age_ms=$ticketAgeMillis source=${restored.source}"
        )
      }
    }
    val currentWallMillis = prefs.getLong(KEY_VIVI_MEMORY_CURRENT_WALL_MILLIS, 0L)
    val currentStateName = prefs.getString(KEY_VIVI_MEMORY_CURRENT_STATE, "").orEmpty()
    val currentState = runCatching { TicketViviRecoveryState.valueOf(currentStateName) }.getOrNull()
    if (currentWallMillis <= 0L || currentState == null) {
      return
    }
    val currentAgeMillis = (nowWallMillis - currentWallMillis).coerceAtLeast(0L)
    if (currentAgeMillis > TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS) {
      return
    }
    if (ticketWallMillis > 0L && currentWallMillis < ticketWallMillis) {
      return
    }
    val restored = viviStateMemory.seed(
      state = currentState,
      ticketId = prefs.getString(KEY_VIVI_MEMORY_CURRENT_TICKET_ID, "").orEmpty().ifBlank { null },
      observedAgeMillis = currentAgeMillis,
      source = "persisted:${prefs.getString(KEY_VIVI_MEMORY_CURRENT_SOURCE, "unknown").orEmpty()}",
      reason = "service_restore:${prefs.getString(KEY_VIVI_MEMORY_CURRENT_REASON, "unknown").orEmpty()}"
    )
    recordTicketEvent(
      "vivi_current_memory_restored",
      "state=${restored.state.name} age_ms=$currentAgeMillis source=${restored.source}"
    )
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
    val recoverySnapshot = ticketRecoveryCoordinator.snapshot(nowMillis)
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
      lastSessionReuseReason = lastSessionReuseReason,
      lastSessionReuseDurationMillis = lastSessionReuseDurationMillis,
      lastSessionReuseAgoMillis = ageMillis(lastSessionReuseCompletedAtMillis, nowMillis),
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
        value = null,
        commandOwner = lastControlCodeCommandOwner,
        commandApp = lastControlCodeCommandApp,
        commandFlow = lastControlCodeCommandFlow,
        lastRejectedOwner = lastRejectedControlCodeCommandOwner,
        lastRejectedApp = lastRejectedControlCodeCommandApp,
        lastRejectedFlow = lastRejectedControlCodeCommandFlow,
        lastRejectedReason = lastRejectedControlCodeCommandReason,
        lastRejectedAgoMillis = ageMillis(lastRejectedControlCodeCommandAtMillis, nowMillis),
        totalDurationMillis = lastControlCodeRequestDurationMillis,
        phases = lastControlCodeRequestPhases,
        rootObservationMillis = controlCodeRootObservationMillis(lastControlCodeRequestPhases),
        visualMarkerMillis = controlCodeVisualMarkerMillis(lastControlCodeRequestPhases),
        resultProofMillis = controlCodeResultProofMillis(lastControlCodeRequestPhases),
        browserCaptureAckMillis = lastControlCodeRequestPhases["browser_capture_ack_wait"],
        browserCaptureReason = lastControlCodeBrowserCaptureReason,
        browserCaptureAgoMillis = ageMillis(lastControlCodeBrowserCaptureCompletedAtMillis, nowMillis),
        resultDeliveryMillis = controlCodeResultDeliveryMillis(lastControlCodeRequestPhases),
        completedAgoMillis = ageMillis(lastControlCodeRequestCompletedAtMillis, nowMillis),
        duplicateResults = duplicateControlCodeResultCount,
        lastDuplicateRequestId = lastDuplicateControlCodeRequestId,
        lastDuplicateAgoMillis = ageMillis(lastDuplicateControlCodeResultAtMillis, nowMillis)
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
        lastFreshFrameRequested = lastControlExitCleanupFreshFrameRequested,
        lastFreshFrameVerified = lastControlExitCleanupFreshFrameVerified,
        lastFreshFrameVerifiedAgoMillis = ageMillis(lastPostCleanupFreshFrameVerifiedAtMillis, nowMillis)
      ),
      loading = TicketLoadingHealth(
        lastPhase = lastLoadingPhase,
        lastDurationMillis = lastLoadingDurationMillis,
        lastCompletedAgoMillis = ageMillis(lastLoadingCompletedAtMillis, nowMillis),
        lastOverBudgetPhase = lastLoadingOverBudgetPhase,
        lastOverBudgetDurationMillis = lastLoadingOverBudgetDurationMillis,
        lastOverBudgetAgoMillis = ageMillis(lastLoadingOverBudgetAtMillis, nowMillis)
      ),
      wake = TicketWakeHealth(
        budgetMillis = TICKET_WAKE_BUDGET_MILLIS,
        lastReason = lastWakeReason,
        lastStartedAgoMillis = ageMillis(lastWakeStartedAtMillis, nowMillis),
        lastWakeCommandMillis = lastWakeCommandMillis,
        lastInteractiveMillis = lastWakeInteractiveMillis,
        lastViviForegroundMillis = lastWakeViviForegroundMillis,
        lastTicketReadyMillis = lastWakeTicketReadyMillis,
        lastTotalMillis = lastWakeTotalMillis,
        lastSucceeded = lastWakeSucceeded,
        lastSlowestPhase = lastWakeSlowestPhase,
        lastSlowestPhaseDurationMillis = lastWakeSlowestPhaseDurationMillis,
        lastFailureReason = lastWakeFailureReason
      ),
      automation = TicketAutomationHealth(
        ticketAutomationMode = "root_only",
        fallbackPolicy = "fail_fast",
        nonRootAccessibilityAllowed = false,
        lastRootReadinessResult = lastRootReadinessResult,
        lastRootReadinessDurationMillis = lastRootReadinessDurationMillis,
        lastRootReadinessAgoMillis = ageMillis(lastRootReadinessAtMillis, nowMillis),
        viviLoginCredentialsConfigured = lastViviLoginCredentialsConfigured,
        viviLoginLastStatus = lastViviLoginStatus,
        viviLoginLastReason = lastViviLoginReason,
        viviLoginLastCompletedAgoMillis = ageMillis(lastViviLoginCompletedAtMillis, nowMillis)
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
      ffmpeg = TicketFfmpegHealth(),
      hardwareH264 = hardwareCapture,
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
      pixelTicketStateEvent = TicketPixelStateEventHealth(
        eventSeq = lastPixelTicketEventSeq,
        ticketState = lastPixelTicketState,
        reason = lastPixelTicketEventReason,
        requestId = lastPixelTicketEventRequestId,
        streamEpoch = lastPixelTicketEventStreamEpoch,
        frameSequence = lastPixelTicketEventFrameSequence,
        minFrameSequence = lastPixelTicketEventMinFrameSequence,
        lastSentAgoMillis = ageMillis(lastPixelTicketEventSentAtMillis, nowMillis)
      ),
      recentClientTelemetry = recentClientTelemetrySnapshot(nowMillis),
      recentEvents = recentTicketEventsSnapshot(nowMillis),
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

  private fun controlCodeRootObservationMillis(phases: Map<String, Long>): Long? {
    val total = phases
      .filterKeys { key -> key.contains("root", ignoreCase = true) || key.contains("hierarchy", ignoreCase = true) }
      .values
      .sum()
    return total.takeIf { it > 0L }
  }

  private fun controlCodeVisualMarkerMillis(phases: Map<String, Long>): Long? {
    return phases["wait_result_visual_fast_path"]
      ?: phases["wait_result_visual_after_root"]
      ?: phases["wait_result_root_proof"]
      ?: phases["result_first_visible"]
  }

  private fun controlCodeResultProofMillis(phases: Map<String, Long>): Long? {
    return phases["wait_result_root_proof"]
      ?: phases["result_first_visible"]
  }

  private fun controlCodeResultDeliveryMillis(phases: Map<String, Long>): Long? {
    return phases["result_marker_requested"]
      ?: phases["result_marker_frame_ready"]
      ?: phases["result_first_visible"]
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

  private suspend fun launchViviUnlessFastTicketDetail(reason: String) {
    val rootState = observeRootViviState("launch_check:$reason", timeoutMillis = TICKET_WAKE_FAST_ROOT_DUMP_TIMEOUT_MILLIS)
    if (rootState.state == TicketViviRecoveryState.TICKET_DETAIL) {
      Log.i(TAG, "ticket_launch_skipped_root_ticket_detail reason=$reason")
      scheduleTicketBrightnessGuard("vivi_fast_ticket_detail")
      return
    }
    launchVivi()
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
    val recent = viviStateMemory.recentTicketDetailWithin(TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS) ?: return null
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
    if (ageMillis !in 0..TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS) {
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
    recordTicketEvent("wake_recent_ticket_detail_fast_ready", "reason=$reason age_ms=$ageMillis")
    return TicketAutopilotResult(true, TicketViviRecoveryState.TICKET_DETAIL, "wake_recent_ticket_detail_fast_ready")
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
    if (result.success && result.state == TicketViviRecoveryState.TICKET_DETAIL && lastWakeTicketReadyMillis == null) {
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
      val step = if (observation.hierarchy.isNullOrBlank()) {
        "fast_open_root_unavailable"
      } else {
        "fast_open_root_${state.name.lowercase()}"
      }
      recordTicketEvent("fast_public_open_root_proof_failed", "reason=$reason state=${state.name} step=$step")
      TicketAutopilotResult(false, state, step)
    }
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
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_hardware_h264_vivi_ready_fast_$reason")
    } else {
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "root_hardware_h264_fast_open_${prepareResult.state.name.lowercase()}")
    }
    finishTicketWake(
      wakeStartedAtMillis,
      succeeded = prepareResult.success,
      reason = if (prepareResult.success) "ticket_ready" else prepareResult.step
    )
    return prepareResult
  }

  private suspend fun prepareViviForRootHardwareH264Capture(
    reason: String,
    wakeStartedAtMillis: Long,
    budgetMillis: Long = TICKET_WAKE_BUDGET_MILLIS
  ): TicketAutopilotResult {
    val result = observeTicketDetailForWakeWithRoot(
      reason = reason,
      wakeStartedAtMillis = wakeStartedAtMillis,
      budgetMillis = budgetMillis
    )
    markWakeReadyIfNeeded(wakeStartedAtMillis, result)
    recordTicketEvent("root_hardware_h264_vivi_prepare", "${result.state}:${result.step}:success=${result.success}")
    if (result.success) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_hardware_h264_vivi_ready_$reason")
    } else {
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "root_hardware_h264_vivi_prepare_${result.state.name.lowercase()}")
    }
    finishTicketWake(wakeStartedAtMillis, succeeded = result.success, reason = if (result.success) "ticket_ready" else result.step)
    return result
  }

  private fun scheduleRootHardwareH264CaptureStart(reason: String, suppressBlackout: Boolean) {
    serviceScope.launch {
      if (suppressBlackout) {
        suppressBlackoutOverlayForRemote()
      }
      val modeName = "root_hardware_h264"
      updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_prepare_$reason")
      val wakeStartedAtMillis = beginTicketWake(reason)
      wakeTicketScreenForSessionStart(reason, wakeStartedAtMillis)
      val prepareResult = prepareViviForRootHardwareH264FastOpen(reason, wakeStartedAtMillis)
      if (!streamActive || activeCaptureMode == CAPTURE_MODE_IDLE) {
        recordTicketEvent("${modeName}_prepare_ignored", "session_inactive:$reason")
        broadcastStatus()
        return@launch
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
        broadcastStatus()
        persistRuntimeState("phone_not_ready")
        return@launch
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
    }
  }

  private suspend fun verifyRootHardwareSecureCaptureVisible(reason: String): Boolean {
    if (!verifyViviTicketDetailHierarchy(reason)) {
      return false
    }
    val visible = verifyRootHardwareSecureCapturePixelsVisible(reason)
    if (visible) {
      lastRootH264BlankProbeResult = "visible"
      lastRootH264VisibleProbePassedAtMillis = SystemClock.elapsedRealtime()
    }
    return visible
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

  private suspend fun verifyViviTicketDetailHierarchy(reason: String): Boolean {
    if (recentTicketDetailForSecureCapture(reason)) {
      return true
    }
    val observation = observeRootViviState("secure_capture_verify:$reason")
    val xml = observation.hierarchy
    if (xml.isNullOrBlank()) {
      recordTicketEvent("secure_capture_verify_hierarchy_unavailable", observation.error.takeLast(160))
      return false
    }
    val state = observation.state
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

  private suspend fun alignControlExitTicketDetailWithSurface(
    state: TicketViviRecoveryState,
    reason: String,
    detectorSource: String
  ): TicketViviRecoveryState {
    lastControlExitCleanupDetectorSource = detectorSource
    lastControlExitCleanupSurfaceProbeResult = "removed_raw_probe:${state.name}:$reason"
    if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
    }
    return state
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
    val result = runFastNonTouchScript(
      "am start -n $component",
      "return_orchestrator",
      NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds
    )
    if (result.ok) {
      return
    }
    Log.w(TAG, "ticket_return_orchestrator_root_launch_failed stderr=${result.stderr}")
    val intent = Intent(this, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    markTicketNonTouchAction("return_orchestrator:fallback")
    try {
      startActivity(intent)
    } finally {
      markTicketNonTouchAction("return_orchestrator:fallback_complete")
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
    val sourceX = size.sourceX(encodedX)
    val sourceY = size.sourceY(encodedY)
    return sourceX in (size.sourceWidth * VIVI_CONTROL_CODE_MIN_X_FRACTION).roundToInt()..
      (size.sourceWidth * VIVI_CONTROL_CODE_MAX_X_FRACTION).roundToInt() &&
      sourceY in (size.sourceHeight * VIVI_CONTROL_CODE_MIN_Y_FRACTION).roundToInt()..
      (size.sourceHeight * VIVI_CONTROL_CODE_MAX_Y_FRACTION).roundToInt()
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
    return TicketTapTarget(
      x = (size.sourceWidth * ((VIVI_CONTROL_CODE_MIN_X_FRACTION + VIVI_CONTROL_CODE_MAX_X_FRACTION) / 2f)).roundToInt(),
      y = (size.sourceHeight * ((VIVI_CONTROL_CODE_MIN_Y_FRACTION + VIVI_CONTROL_CODE_MAX_Y_FRACTION) / 2f)).roundToInt(),
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
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
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
    return status == "running"
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
      val hierarchy = controlExitHierarchy()
      val observedState = if (hierarchy.isNullOrBlank()) {
        TicketViviRecoveryState.UNKNOWN_VIVI
      } else {
        TicketViviPageEnforcer.classifyForRecovery(hierarchy)
      }
      when (observedState) {
        TicketViviRecoveryState.CONTROL_CODE_POPUP,
        TicketViviRecoveryState.CONTROL_CODE_RESULT -> {
          rememberControlCodeSurface(observedState)
          if (isSoftControlExitReason(reason)) {
            completeOrRecoverControlExitSoftCheck(reason, observedState.name)
          } else if (observedState == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
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
              observedState,
              reason,
              "root_soft_check"
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
            recordTicketEvent("control_code_soft_check_ok", observedState.name)
          }
        }
        else -> {
          if (isSoftControlExitReason(reason)) {
            completeOrRecoverControlExitSoftCheck(reason, observedState.name)
          } else if (recentSuccessfulControlExitCleanup()) {
            updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_soft_check_recent_cleanup_ok")
            recordTicketEvent("control_code_soft_check_ok", "recent_cleanup")
            broadcastStatus()
          } else {
            recordTicketEvent("control_code_soft_check_needs_attention", observedState.name)
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
    val hierarchy = controlExitHierarchy()
    val state = hierarchy?.let {
      alignControlExitTicketDetailWithSurface(
        TicketViviPageEnforcer.classifyForRecovery(it),
        reason,
        "root_after_close"
      )
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
    return foregroundViolationReason(allowStartupSystemUi = false)
  }

  private suspend fun softCleanupControlExitSurface(
    reason: String,
    detectedState: String,
    startedAtMillis: Long
  ): Boolean? {
    val hierarchy = controlExitHierarchy() ?: return null
    val state = alignControlExitTicketDetailWithSurface(
      TicketViviPageEnforcer.classifyForRecovery(hierarchy),
      reason,
      "root_cleanup"
    )
    if (state == TicketViviRecoveryState.TICKET_DETAIL) {
      val reportedState = controlExitCleanupReportedState(detectedState, null)
      val reportedAction = controlExitCleanupReportedAction(reportedState)
      return completeVerifiedTicketDetailControlExitCleanup(
        reason = reason,
        detectedState = reportedState,
        closeAction = reportedAction,
        startedAtMillis = startedAtMillis,
        firstVerificationResult = state.name
      )
    }
    if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      return tryControlExitGeneratedResultGeometryFallback(
        reason = reason,
        startedAtMillis = startedAtMillis,
        detectedState = state.name,
        verificationFailure = "root_cleanup_dirty"
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
    viviStateMemory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = null,
      source = "root",
      reason = "control_exit_cleanup:$reason"
    )
    recordInputGateDecision(allowed = true, reason = "control_exit_popup_closed")
    recordTicketEvent("control_exit_popup_closed", reason)
    updateTicketSessionState(TICKET_SESSION_LIVE, "control_exit_popup_closed")
    if (streamActive) {
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
    lastControlExitCleanupFreshFrameVerified = freshFrameVerified
    recordTicketEvent(
      "control_exit_cleanup",
      "reason=$reason state=$detectedState action=$closeAction verify=$verificationResult success=$succeeded fresh_frame=$freshFrameRequested fresh_frame_verified=$freshFrameVerified duration_ms=$durationMillis"
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

  private suspend fun handlePrepareControlCode(reason: String) {
    val cleanReason = reason.trim().ifBlank { "dialog_open" }
    if (recentControlCodePrepareTicketReady(cleanReason)) {
      recordTicketEvent("control_code_prepare", "TICKET_DETAIL:recent_ticket_detail:success=true")
      broadcastStatus()
      return
    }
    if (ticketWakeInProgress()) {
      recordTicketEvent("control_code_prepare_deferred_active_wake", cleanReason)
      recordTicketEvent("control_code_prepare", "TICKET_DETAIL:active_wake:success=true")
      broadcastStatus()
      return
    }
    val wakeStartedAtMillis = beginTicketWake("control_code_prepare:$cleanReason")
    wakeTicketScreenForSessionStart("control_code_prepare:$cleanReason", wakeStartedAtMillis)
    launchViviForWake("control_code_prepare:$cleanReason")
    var observation = observeRootViviStateForWake(
      reason = "control_code_prepare:$cleanReason",
      timeoutMillis = CONTROL_CODE_PREPARE_ROOT_DUMP_TIMEOUT_MILLIS
    )
    var success = observation.state == TicketViviRecoveryState.TICKET_DETAIL ||
      observation.state == TicketViviRecoveryState.CONTROL_CODE_POPUP
    var step = if (success) "prepare_fast_root_ready" else "prepare_fast_root_${observation.state.name.lowercase()}"
    if (!success && observation.state == TicketViviRecoveryState.CONTROL_CODE_RESULT && !observation.hierarchy.isNullOrBlank()) {
      val phases = linkedMapOf<String, Long>()
      val healed = healGeneratedControlCodeResultForRequest(
        generatedHierarchy = observation.hierarchy.orEmpty(),
        reason = "control_code_prepare:$cleanReason",
        phases = phases,
        requestStartedAtMillis = wakeStartedAtMillis
      )
      if (healed) {
        observation = observeRootViviStateForWake(
          reason = "control_code_prepare:$cleanReason:after_generated_heal",
          timeoutMillis = CONTROL_CODE_PREPARE_ROOT_DUMP_TIMEOUT_MILLIS
        )
        success = observation.state == TicketViviRecoveryState.TICKET_DETAIL ||
          observation.state == TicketViviRecoveryState.CONTROL_CODE_POPUP
        step = if (success) "prepare_fast_root_healed_generated_code" else "phone_stuck_on_generated_code"
      } else {
        step = "phone_stuck_on_generated_code"
      }
    }
    val result = TicketAutopilotResult(
      success = success,
      state = observation.state,
      step = step
    )
    markWakeReadyIfNeeded(wakeStartedAtMillis, result)
    if (success) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_prepare_ready")
    }
    finishTicketWake(wakeStartedAtMillis, succeeded = success, reason = if (success) "ticket_ready" else result.step)
    recordTicketEvent("control_code_prepare", "${result.state}:${result.step}:success=${result.success}")
    broadcastStatus()
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
    lastRejectedControlCodeCommandOwner = owner.trim().takeIf { it.isNotBlank() }
    lastRejectedControlCodeCommandApp = app.trim().takeIf { it.isNotBlank() }
    lastRejectedControlCodeCommandFlow = flow.trim().takeIf { it.isNotBlank() }
    lastRejectedControlCodeCommandReason = cleanReason
    lastRejectedControlCodeCommandAtMillis = nowMillis
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

  private fun recentControlCodePrepareTicketReady(reason: String): Boolean {
    if (!streamActive || !hardwareCaptureVerified || ticketSessionState != TICKET_SESSION_LIVE) {
      return false
    }
    val current = viviStateMemory.current()
    if (current.state != TicketViviRecoveryState.TICKET_DETAIL || current.observedAtMillis <= 0L) {
      return false
    }
    val previousObservedAtMillis = current.observedAtMillis
    val recent = viviStateMemory.record(
      state = TicketViviRecoveryState.TICKET_DETAIL,
      ticketId = current.ticketId,
      source = current.source,
      reason = "control_code_prepare_current_ticket_detail"
    )
    recordTicketEvent(
      "control_code_prepare_recent_ticket_detail",
      "source=${recent.source} reason=$reason age_ms=${SystemClock.elapsedRealtime() - previousObservedAtMillis}"
    )
    return true
  }

  private suspend fun ensureTicketSessionForControlCodeRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    if (!ticketSessionOpen()) {
      val response = startTicketSession()
      markControlCodeRequestPhase(phases, "request_session_started", requestStartedAtMillis)
      if (!response.ok || !ticketSessionOpen()) {
        val reason = "control_code_request_session_unavailable:${response.state}"
        recordInputGateDecision(allowed = false, reason = reason)
        recordTicketEvent("control_code_request_session_unavailable", "state=${response.state} message=${response.message.take(80)}")
        return false
      }
      recordTicketEvent("control_code_request_session_ready", response.state)
    }

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

  private suspend fun handleGenerateControlCode(
    replyClient: TicketWebSocket,
    requestId: String,
    digits: String,
    owner: String,
    app: String,
    flow: String,
    resultImage: Boolean,
    queueHint: RigasSatiksmeQueueHint
  ) {
    val cleanRequestId = requestId.trim()
    val cleanDigits = digits.trim()
    val requestedOwner = owner.trim()
    val requestedApp = app.trim()
    val requestedFlow = flow.trim()
    recordControlCodeCommandEnvelope(requestedOwner, requestedApp, requestedFlow)
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
      sendControlCodeResult(cleanRequestId, false, "unsupported_result_image_source", "", 0L, emptyMap(), cleanupPending = false)
      return
    }
    if (sendCachedControlCodeResult(cleanRequestId)) {
      return
    }
    protectedControlClients.add(replyClient)
    try {
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
        lastControlCodeRequestPhases = emptyMap()
        lastControlCodeRequestCompletedAtMillis = 0L
        broadcastStatus()

        var ok = false
        var reason = "failed"
        var value = ""
        var resultSent = false

        try {
          phases["phone_command_received"] = 0L
          if (!ensureTicketSessionForControlCodeRequest(phases, startedAtMillis)) {
            reason = "control_code_request_session_unavailable"
          } else if (!measureInputPhase(phases, "gate") { canForwardRemoteInput() }) {
            reason = inputGateReason
          } else {
            markControlCodeRequestPhase(phases, "request_gate_passed", startedAtMillis)
            val delivery = runFastControlCodeDeliveryForRequest(cleanDigits, phases, startedAtMillis)
            ok = delivery.ok
            reason = delivery.reason
            value = delivery.value
            if (ok) {
              lastControlCodeRequestStatus = "succeeded"
              lastControlCodeRequestReason = "generated"
              lastControlCodeRequestValue = value.ifBlank { null }
              lastControlCodeRequestDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
              lastControlCodeRequestPhases = phases.toMap()
              lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
              val totalDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
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
                totalDurationMillis = totalDurationMillis,
                phases = phases
              )
              resultSent = true
              recordTicketEvent("control_code_marker_delivered", "request=$cleanRequestId epoch=${delivery.streamEpoch} min_sequence=${delivery.minFrameSequence}")
              val browserCapture = waitForControlCodeBrowserCapture(
                requestId = cleanRequestId,
                phases = phases,
                requestStartedAtMillis = startedAtMillis
              )
              lastControlCodeRequestDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
              lastControlCodeRequestPhases = phases.toMap()
              clearControlCodeBrowserCaptureWait(cleanRequestId)
              val cleanupReason = if (browserCapture.ok) "browser_capture_confirmed" else browserCapture.reason
              val cleanupSucceeded = if (delivery.resultProof == "phone_visual_raw_ticket_after_submit") {
                completeControlCodeRawTicketAfterSubmitCleanup(
                  reason = cleanupReason,
                  phases = phases,
                  requestStartedAtMillis = startedAtMillis
                )
              } else {
                returnControlCodeSurfaceToRawTicket(
                  generatedHierarchy = delivery.generatedHierarchy,
                  reason = cleanupReason,
                  phases = phases,
                  requestStartedAtMillis = startedAtMillis
                )
              }
              sendControlCodeCleanup(
                requestId = cleanRequestId,
                ok = cleanupSucceeded,
                reason = if (cleanupSucceeded) "ticket_detail" else "control_code_cleanup_attention_needed",
                startedAtMillis = startedAtMillis
              )
            } else if (delivery.cleanupRequired) {
              val cleanupSucceeded = returnControlCodeSurfaceToRawTicket(
                generatedHierarchy = delivery.generatedHierarchy,
                reason = "control_code_request_failed_return_raw",
                phases = phases,
                requestStartedAtMillis = startedAtMillis
              )
              sendControlCodeResult(
                requestId = cleanRequestId,
                ok = false,
                reason = reason,
                value = value,
                startedAtMillis = startedAtMillis,
                phases = phases,
                cleanupPending = !cleanupSucceeded
              )
              resultSent = true
              sendControlCodeCleanup(
                requestId = cleanRequestId,
                ok = cleanupSucceeded,
                reason = if (cleanupSucceeded) "ticket_detail" else "control_code_cleanup_attention_needed",
                startedAtMillis = startedAtMillis
              )
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          reason = "control_code_request_failed"
          Log.w(TAG, "ticket_control_code_request_failed request=$cleanRequestId", error)
          val cleanupSucceeded = returnControlCodeSurfaceToRawTicket(
            generatedHierarchy = "",
            reason = "control_code_request_exception_return_raw",
            phases = phases,
            requestStartedAtMillis = startedAtMillis
          )
          sendControlCodeResult(
            requestId = cleanRequestId,
            ok = false,
            reason = reason,
            value = value,
            startedAtMillis = startedAtMillis,
            phases = phases,
            cleanupPending = !cleanupSucceeded
          )
          resultSent = true
          sendControlCodeCleanup(
            requestId = cleanRequestId,
            ok = cleanupSucceeded,
            reason = if (cleanupSucceeded) "ticket_detail" else "control_code_cleanup_attention_needed",
            startedAtMillis = startedAtMillis
          )
        }

        if (!resultSent) {
          sendControlCodeResult(
            requestId = cleanRequestId,
            ok = ok,
            reason = reason,
            value = value,
            startedAtMillis = startedAtMillis,
            phases = phases,
            cleanupPending = false
          )
        }
      }
    } finally {
      protectedControlClients.remove(replyClient)
    }
  }


  private suspend fun handleGenerateRigasSatiksmeMonthlyTicketQr(
    replyClient: TicketWebSocket,
    cleanRequestId: String,
    cleanDigits: String,
    queueHint: RigasSatiksmeQueueHint
  ) {
    if (sendCachedControlCodeResult(cleanRequestId)) {
      return
    }
    val reusePreviousRigasSatiksmeQr = cancelPendingRigasSatiksmeReturnCleanup("new_rs_monthly_ticket_request")
    protectedControlClients.add(replyClient)
    var pendingImmediateCleanup: PendingRigasSatiksmeReturnCleanup? = null
    try {
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
          return@withLock
        }

        val imageBytes = outcome.imageBytes ?: ByteArray(0)
        val watermark = requestFreshTicketStateFrameWatermark("rs_monthly_ticket_control_screen")
        lastControlCodeRequestStatus = "succeeded"
        lastControlCodeRequestReason = outcome.reason
        lastControlCodeRequestValue = null
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
      }
    } finally {
      protectedControlClients.remove(replyClient)
    }
    pendingImmediateCleanup?.let { cleanup ->
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
    val job = pendingRigasSatiksmeReturnCleanupJob ?: run {
      pendingRigasSatiksmeReturnCleanupStarted = false
      return false
    }
    val canceledActiveCleanup = job.isActive
    val cleanupStarted = pendingRigasSatiksmeReturnCleanupStarted
    val reusePreviousQr = canceledActiveCleanup && !cleanupStarted
    if (canceledActiveCleanup) {
      recordTicketEvent(
        "rs_monthly_ticket_idle_cleanup_canceled",
        "reason=$reason started=$cleanupStarted reuse_previous_qr=$reusePreviousQr"
      )
      job.cancel(CancellationException(reason))
    }
    pendingRigasSatiksmeReturnCleanupJob = null
    pendingRigasSatiksmeReturnCleanupStarted = false
    return reusePreviousQr
  }

  private fun scheduleRigasSatiksmeReturnCleanupAfterIdle(
    requestId: String,
    startedAtMillis: Long,
    reason: String
  ) {
    cancelPendingRigasSatiksmeReturnCleanup("reschedule_rs_monthly_ticket_idle_cleanup")
    pendingRigasSatiksmeReturnCleanupStarted = false
    pendingRigasSatiksmeReturnCleanupJob = serviceScope.launch(Dispatchers.IO) {
      try {
        delay(TICKET_RS_MONTHLY_IDLE_CLEANUP_DELAY_MILLIS)
        controlCodeRequestMutex.withLock {
          pendingRigasSatiksmeReturnCleanupStarted = true
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
        pendingRigasSatiksmeReturnCleanupJob = null
        pendingRigasSatiksmeReturnCleanupStarted = false
      }
    }
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
        override suspend fun launchApp(): Boolean = launchRigasSatiksmeAppForVisualAutomation()

        override suspend fun waitForForeground(): Boolean = waitForRigasSatiksmeVisualForeground()

        override suspend fun snapshot(reason: String): List<PhoneAutomationVisibleNode> {
          return snapshotRigasSatiksmeUiAutomatorNodes(reason)
        }

        override suspend fun resetApp(reason: String): Boolean {
          return resetRigasSatiksmeAppForVisualAutomation(reason)
        }

        override suspend fun tapNodeCenter(node: PhoneAutomationVisibleNode, reason: String): Boolean {
          return tapRigasSatiksmeVisibleNodeCenter(node, reason)
        }

        override suspend fun tapRatio(x: Double, y: Double, reason: String): Boolean {
          val (sourceWidth, sourceHeight) = currentDisplaySize()
          return tapRigasSatiksmeVisualTarget(
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
          return pressBackForRigasSatiksmeVisualDriver(reason)
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

  private suspend fun launchRigasSatiksmeAppForVisualAutomation(): Boolean {
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

  private suspend fun resetRigasSatiksmeAppForVisualAutomation(reason: String): Boolean {
    val startedAt = SystemClock.elapsedRealtime()
    recordTicketEvent("rs_monthly_ticket_app_reset_started", "reason=$reason package=${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}")
    val forceStop = runRigasSatiksmeVisualInput(
      "am force-stop ${TicketScreenConfig.RIGAS_SATIKSME_PACKAGE}",
      "${reason}_force_stop"
    )
    delay(180L)
    val launched = launchRigasSatiksmeAppForVisualAutomation()
    val foreground = if (launched) waitForRigasSatiksmeVisualForeground() else false
    val ok = forceStop.ok && launched && foreground
    recordTicketEvent(
      "rs_monthly_ticket_app_reset_finished",
      "reason=$reason ok=$ok force_stop_ok=${forceStop.ok} launched=$launched foreground=$foreground duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
    )
    return ok
  }

  private suspend fun waitForRigasSatiksmeVisualForeground(): Boolean {
    delay(700L)
    recordTicketEvent("rs_monthly_ticket_visual_foreground_ready", "source=visual_frame_gate")
    return true
  }

  private suspend fun captureRigasSatiksmeVisualFrame(reason: String): RigasSatiksmeVisualFrame? {
    val (sourceWidth, sourceHeight) = currentDisplaySize()
    val targetWidth = RIGAS_SATIKSME_VISUAL_CAPTURE_WIDTH.takeIf { sourceWidth > it } ?: sourceWidth
    val targetHeight = if (targetWidth == sourceWidth) {
      sourceHeight
    } else {
      ((sourceHeight.toLong() * targetWidth.toLong()) / sourceWidth.toLong()).coerceAtLeast(1L).toInt()
    }
    val result = rootHardwareH264CaptureEngine.captureSecurePngBase64(
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight,
      targetWidth = targetWidth,
      targetHeight = targetHeight,
      timeout = RIGAS_SATIKSME_VISUAL_CAPTURE_TIMEOUT_MILLIS.milliseconds
    )
    if (!result.ok) {
      recordTicketEvent(
        "rs_monthly_ticket_visual_capture_failed",
        "reason=$reason duration_ms=${result.durationMs} stderr_tail=${result.stderr.takeLast(140).replace('\n', ' ').replace('\r', ' ')}"
      )
      return null
    }
    val bytes = decodeBase64Png(result.stdout)
    if (bytes == null) {
      recordTicketEvent("rs_monthly_ticket_visual_capture_failed", "reason=$reason decode_png_failed")
      return null
    }
    val decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    if (decodedBitmap == null || decodedBitmap.width <= 0 || decodedBitmap.height <= 0) {
      recordTicketEvent("rs_monthly_ticket_visual_capture_failed", "reason=$reason bitmap_decode_failed")
      return null
    }
    return try {
      val pixels = IntArray(decodedBitmap.width * decodedBitmap.height)
      decodedBitmap.getPixels(pixels, 0, decodedBitmap.width, 0, 0, decodedBitmap.width, decodedBitmap.height)
      val frame = RigasSatiksmeVisualFrame(
        width = decodedBitmap.width,
        height = decodedBitmap.height,
        pixels = pixels,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight
      )
      recordTicketEvent(
        "rs_monthly_ticket_visual_capture_ready",
        "reason=$reason source=secure_display_capture width=${decodedBitmap.width} height=${decodedBitmap.height} source_width=$sourceWidth source_height=$sourceHeight bytes=${bytes.size} duration_ms=${result.durationMs}"
      )
      frame
    } finally {
      decodedBitmap.recycle()
    }
  }

  private suspend fun tapRigasSatiksmeVisualTarget(x: Int, y: Int, reason: String): Boolean {
    recordTicketEvent("rs_monthly_ticket_visual_tap", "reason=$reason x=$x y=$y")
    val result = runRigasSatiksmeVisualInput("input tap $x $y", reason)
    return result.ok
  }

  private suspend fun tapRigasSatiksmeVisibleNodeCenter(
    node: PhoneAutomationVisibleNode,
    reason: String
  ): Boolean {
    val center = parseUiAutomatorNodeCenter(node.bounds) ?: return false
    val (sourceWidth, sourceHeight) = currentDisplaySize()
    return tapRigasSatiksmeVisualTarget(
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

  private suspend fun pressBackForRigasSatiksmeVisualDriver(reason: String): Boolean {
    val result = runRigasSatiksmeVisualInput("input keyevent KEYCODE_BACK", reason)
    return result.ok
  }

  private suspend fun enterRigasSatiksmeManualCode(
    cleanDigits: String,
    fieldX: Int,
    fieldY: Int
  ): Boolean {
    if (!cleanDigits.all { it.isDigit() }) return false
    val result = runRigasSatiksmeVisualInput(
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

  private suspend fun runRigasSatiksmeVisualInput(command: String, reason: String): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      inputRootExecutor.runScript(command, RIGAS_SATIKSME_VISUAL_INPUT_TIMEOUT_MILLIS.milliseconds)
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
    val nodes = runCatching { RigasSatiksmeShellSemanticGateway.parseUiAutomatorNodes(result.stdout) }
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
      reason = waitOutcome.failureReason
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
    val action = streamSize?.let { size ->
      controlCodeGeometryTarget(size, "generated_request_immediate_macro").copy(reason = decision.reason)
    } ?: fallbackControlCodeButtonTarget().copy(reason = "${decision.reason}:display_geometry")
    recordInputGateDecision(allowed = true, reason = action.reason)
    markControlCodeTransition("control_code_request_open_type_submit_fast")
    val transaction = openedControlCodePopupTransactionTargets(
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      source = "immediate_open_type_submit_macro",
      eventReason = "control_code_popup_transaction_ready_macro"
    )
    val submit = transaction.keyboardOpenSubmit
    phases["ok_tap_target_source_keyboard_geometry"] = 1L
    phases["ok_tap_target_source_popup_transaction"] = 1L
    recordTicketEvent(
      "control_code_submit_target_source",
      "immediate_open_type_submit_macro input=${transaction.inputSource} pre=${transaction.preKeyboardSubmitSource} keyboard=${transaction.keyboardOpenSubmitSource}"
    )
    recordTicketEvent("control_code_submit_current_popup_target", submit.reason)
    recordTicketEvent("control_code_submit_target", submit.reason)
    val command = """
      log -p i -t TicketStreamService 'ticket_event event=control_code_macro_phase detail=popup_tap'
      input tap ${action.x} ${action.y}
      sleep 0.12
      log -p i -t TicketStreamService 'ticket_event event=control_code_macro_phase detail=popup_ready_after_short_settle'
      input tap ${transaction.input.x} ${transaction.input.y}
      sleep 0.035
      log -p i -t TicketStreamService 'ticket_event event=control_code_macro_phase detail=first_digit_start'
      input text $digits
      log -p i -t TicketStreamService 'ticket_event event=control_code_macro_phase detail=digits_typed'
      sleep 0.14
      input tap ${submit.x} ${submit.y}
      log -p i -t TicketStreamService 'ticket_event event=control_code_macro_phase detail=ok_tap_sent'
    """.trimIndent()
    val submitted = measureInputPhase(phases, "open_type_submit_fast") {
      runFastNonTouchInput(command, "control_code_request_open_type_submit_fast")
    }
    recordControlCodeSnapAttempt(
      rawX = action.x,
      rawY = action.y,
      candidateZone = action.candidateZone,
      snapTarget = SNAP_TARGET_CONTROL_CODE_BUTTON,
      accepted = submitted.ok,
      reason = if (submitted.ok) action.reason else "root_command_failed",
      finalX = action.x,
      finalY = action.y,
      detectedButtonBounds = action.detectedButtonBounds
    )
    if (!submitted.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_open_type_submit_failed")
      return false
    }
    markControlCodeRequestPhase(phases, "first_phone_tap", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "first_digit_entry", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "digits_typed", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "ok_tapped", requestStartedAtMillis)
    phases["control_code_submit_attempted"] = 1L
    recordTicketEvent("control_code_input_typed_submit_now", "open_type_submit_macro")
    recordTicketEvent("control_code_submit_attempted", "open_type_submit_macro digits=${digits.length}")
    return true
  }

  private fun requestFreshControlCodeFrameWatermark(reason: String): Pair<Long, Long> {
    return requestFreshTicketStateFrameWatermark(reason)
  }

  private fun requestFreshTicketStateFrameWatermark(reason: String): Pair<Long, Long> {
    requestKeyFrame(reason)
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      rootHardwareH264CaptureEngine.requestBurst(reason)
    }
    val eventStreamEpoch = streamEpoch
    val eventFrameSequence = (frameSequence + 1L).coerceAtLeast(1L)
    return eventStreamEpoch to eventFrameSequence
  }

  private suspend fun prepareViviForControlCodeRequest(phases: MutableMap<String, Long>): Boolean {
    return measureInputPhase(phases, "prepare_ticket_detail_fast") {
      val hierarchy = controlCodeRequestRootHierarchy(phases, "control_code_request_prepare_root")
      val state = hierarchy
        ?.let { TicketViviPageEnforcer.classifyForRecovery(it) }
        ?: TicketViviRecoveryState.UNKNOWN_VIVI
      val prepared = state == TicketViviRecoveryState.TICKET_DETAIL ||
        state == TicketViviRecoveryState.CONTROL_CODE_POPUP
      if (prepared) {
        recordTicketEvent("control_code_request_prepare_ready", state.name)
        return@measureInputPhase true
      }
      val reason = "control_code_request_prepare_${state.name.lowercase()}"
      recordInputGateDecision(allowed = false, reason = reason)
      recordTicketEvent("control_code_request_prepare_failed", "root:$state")
      false
    }
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
    delay(CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS)
    return openedControlCodePopupTransactionTargets(
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      source = "immediate_after_tap_settled_geometry",
      eventReason = "control_code_popup_transaction_ready"
    )
  }

  private suspend fun openControlCodePopupFromVerifiedStateFastForRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodePopupTransaction? {
    var hierarchy = resolveControlCodeHierarchyForFastRequest(phases, "control_code_root_retry")
    var recoveredTicketDetailForRequest = false
    if (hierarchy.isNullOrBlank()) {
      cachedControlCodePopupTargetsForRequest(phases, requestStartedAtMillis)?.let { return it }
      val recovered = recoverTicketDetailForControlCodeRequest(
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        reason = "control_code_request_recover_root_unavailable",
        launchVivi = true
      )
      if (recovered) {
        recoveredTicketDetailForRequest = true
        hierarchy = controlCodeRequestRootHierarchy(
          phases = phases,
          phase = "fast_find_button_after_root_recovery",
          timeoutMillis = CONTROL_CODE_GENERATED_RESULT_HARD_ROOT_DUMP_TIMEOUT_MILLIS
        )
      }
      if (hierarchy.isNullOrBlank() && !recoveredTicketDetailForRequest) {
        recordInputGateDecision(allowed = false, reason = "control_code_root_retry_unavailable")
        return null
      }
    }
    var state = if (hierarchy.isNullOrBlank() && recoveredTicketDetailForRequest) {
      TicketViviRecoveryState.TICKET_DETAIL
    } else {
      TicketViviPageEnforcer.classifyForRecovery(hierarchy.orEmpty())
    }
    if (state == TicketViviRecoveryState.CONTROL_CODE_POPUP) {
      controlCodeFastTargetsForHierarchy(hierarchy.orEmpty())?.let { transaction ->
        controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
        markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
        markControlCodeModeEntered("control_code_request_popup_already_open")
        sendTicketStateEvent(
          ticketState = TICKET_PIXEL_STATE_CONTROL_POPUP,
          reason = "control_code_popup_already_open",
          requestId = lastControlCodeRequestId.orEmpty()
        )
        return transaction
      }
    }
    if (state == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      rememberControlCodeSurface(state)
      val healed = healGeneratedControlCodeResultForRequest(
        generatedHierarchy = hierarchy.orEmpty(),
        reason = "control_code_request_previous_result_visible",
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
      )
      if (!healed) {
        recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
        return null
      }
      hierarchy = controlCodeRequestRootHierarchy(phases, "fast_find_button_after_generated_heal")
      if (hierarchy.isNullOrBlank()) {
        recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
        return null
      }
      state = TicketViviPageEnforcer.classifyForRecovery(hierarchy)
    }
    if (state != TicketViviRecoveryState.TICKET_DETAIL) {
      val recoveryReason = "control_code_request_recover_${state.name.lowercase()}"
      val recovered = recoverTicketDetailForControlCodeRequest(
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        reason = recoveryReason,
        launchVivi = state == TicketViviRecoveryState.OUTSIDE_VIVI || state == TicketViviRecoveryState.UNKNOWN_VIVI
      )
      if (recovered) {
        recoveredTicketDetailForRequest = true
        val recoveredHierarchy = controlCodeRequestRootHierarchy(
          phases = phases,
          phase = "fast_find_button_after_request_recovery",
          timeoutMillis = CONTROL_CODE_GENERATED_RESULT_HARD_ROOT_DUMP_TIMEOUT_MILLIS
        )
        if (!recoveredHierarchy.isNullOrBlank()) {
          hierarchy = recoveredHierarchy
          state = TicketViviPageEnforcer.classifyForRecovery(recoveredHierarchy)
        } else {
          state = TicketViviRecoveryState.TICKET_DETAIL
        }
      }
    }
    val ticketDetailHierarchy = hierarchy.orEmpty()
    if (state != TicketViviRecoveryState.TICKET_DETAIL) {
      recordInputGateDecision(allowed = false, reason = "control_code_request_unsafe_state:${state.name}")
      return null
    }
    val detectedAction = if (ticketDetailHierarchy.isNotBlank()) {
      TicketViviPageEnforcer.controlCodeButtonActionForHierarchy(ticketDetailHierarchy)
    } else {
      null
    }
    val action = detectedAction?.let { detected ->
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
    delay(CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS)
    return openedControlCodePopupTransactionTargets(
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      source = "verified_after_tap_settled_geometry",
      eventReason = "control_code_popup_transaction_ready_verified"
    )
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
    eventReason: String
  ): FastControlCodePopupTransaction {
    val (width, height) = currentDisplaySize()
    val preKeyboardSubmit = TicketViviPageAction(
      x = (width * CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_X_FRACTION).roundToInt(),
      y = (height * CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_Y_FRACTION).roundToInt(),
      reason = "submit_control_code_popup_pre_keyboard_geometry"
    )
    val keyboardOpenSubmit = fallbackControlCodeKeyboardOpenSubmitAction()
    controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
    recordInputGateDecision(allowed = true, reason = "control_code_popup_transaction_ready:$source")
    markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
    markControlCodeModeEntered("control_code_request_popup_transaction_$source")
    sendTicketStateEvent(
      ticketState = TICKET_PIXEL_STATE_CONTROL_POPUP,
      reason = eventReason,
      requestId = lastControlCodeRequestId.orEmpty()
    )
    return FastControlCodePopupTransaction(
      input = TicketViviPageAction(
        x = (width * CONTROL_CODE_FAST_POPUP_INPUT_X_FRACTION).roundToInt(),
        y = (height * CONTROL_CODE_FAST_POPUP_INPUT_Y_FRACTION).roundToInt(),
        reason = "focus_control_code_input_popup_transaction"
      ),
      preKeyboardSubmit = preKeyboardSubmit,
      keyboardOpenSubmit = keyboardOpenSubmit,
      inputSource = "deterministic_geometry:$source",
      preKeyboardSubmitSource = "deterministic_geometry:$source",
      keyboardOpenSubmitSource = "deterministic_keyboard_geometry:$source"
    )
  }

  private fun fallbackControlCodePopupTargetsAfterImmediateOpen(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodePopupTransaction {
    val (width, height) = currentDisplaySize()
    val preKeyboardSubmit = TicketViviPageAction(
      x = (width * CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_X_FRACTION).roundToInt(),
      y = (height * CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_Y_FRACTION).roundToInt(),
      reason = "submit_control_code_popup_pre_keyboard_geometry"
    )
    controlCodePopupReadyUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_POPUP_READY_CACHE_MILLIS
    recordInputGateDecision(allowed = true, reason = "control_code_popup_geometry_after_immediate_tap")
    markControlCodeRequestPhase(phases, "popup_ready", requestStartedAtMillis)
    markControlCodeModeEntered("control_code_request_popup_ready_immediate_geometry")
    sendTicketStateEvent(
      ticketState = TICKET_PIXEL_STATE_CONTROL_POPUP,
      reason = "control_code_popup_ready_geometry",
      requestId = lastControlCodeRequestId.orEmpty()
    )
    return FastControlCodePopupTransaction(
      input = TicketViviPageAction(
        x = (width * CONTROL_CODE_FAST_POPUP_INPUT_X_FRACTION).roundToInt(),
        y = (height * CONTROL_CODE_FAST_POPUP_INPUT_Y_FRACTION).roundToInt(),
        reason = "focus_control_code_input_popup_geometry"
      ),
      preKeyboardSubmit = preKeyboardSubmit,
      keyboardOpenSubmit = fallbackControlCodeKeyboardOpenSubmitAction(),
      inputSource = "deterministic_geometry:immediate_fallback",
      preKeyboardSubmitSource = "deterministic_geometry:immediate_fallback",
      keyboardOpenSubmitSource = "deterministic_keyboard_geometry:immediate_fallback"
    )
  }

  private suspend fun waitForControlCodePopupVisualReadyFast(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    source: String,
    eventReason: String
  ): FastControlCodePopupTransaction? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    rootHardwareH264CaptureEngine.requestBurst("control_code_popup_visual_ready")
    rootHardwareH264CaptureEngine.requestControlCodeVisualProbe("control_code_popup_visual_ready")
    val visualProbe = waitForFreshControlCodeVisualProbe(
      startedAtMillis,
      CONTROL_CODE_FAST_POPUP_VISUAL_WAIT_MILLIS
    )
    phases["popup_visual_probe"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    if (visualProbe != null && visualProbe.result == "control_popup") {
      phases["popup_visual_ready"] = (visualProbe.atMillis - startedAtMillis).coerceAtLeast(0L)
      recordTicketEvent(
        "control_code_popup_visual_ready",
        "source=$source reason=${visualProbe.reason} duration_ms=${phases["popup_visual_ready"]}"
      )
      return openedControlCodePopupTransactionTargets(
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        source = "visual_popup:$source",
        eventReason = eventReason
      )
    }
    if (visualProbe != null) {
      recordTicketEvent(
        "control_code_popup_visual_not_ready",
        "source=$source result=${visualProbe.result} reason=${visualProbe.reason}"
      )
    }
    return null
  }

  private suspend fun waitForControlCodePopupTargetsFast(
    phases: MutableMap<String, Long>,
    phase: String
  ): FastControlCodePopupTransaction? {
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

  private suspend fun enterAndSubmitControlCodeDigitsFastForRequest(
    digits: String,
    transaction: FastControlCodePopupTransaction,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    val typeCommand = """
      input tap ${transaction.input.x} ${transaction.input.y}
      sleep 0.08
      input keyevent KEYCODE_MOVE_END
      for i in 1 2 3 4 5 6; do input keyevent KEYCODE_DEL; done
      input text $digits
      sleep 0.04
    """.trimIndent()
    val typed = measureInputPhase(phases, "type_digits_fast") {
      runFastNonTouchInput(typeCommand, "control_code_request_type_digits_fast")
    }
    if (!typed.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_set_failed")
      return false
    }
    markControlCodeRequestPhase(phases, "digits_typed", requestStartedAtMillis)
    val submit = resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)
    phases["ok_tap_target_source_popup_transaction"] = 1L
    recordTicketEvent("control_code_submit_current_popup_target", submit.reason)
    recordTicketEvent("control_code_submit_target", submit.reason)
    val submitCommand = """
      input tap ${submit.x} ${submit.y}
      sleep 0.12
      input tap ${submit.x} ${submit.y}
    """.trimIndent()
    val submitted = measureInputPhase(phases, "submit_digits_fast") {
      runFastNonTouchInput(submitCommand, "control_code_request_submit_button_fast")
    }
    if (!submitted.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_submit_missing")
      return false
    }
    markControlCodeRequestPhase(phases, "ok_tapped", requestStartedAtMillis)
    phases["control_code_submit_attempted"] = 1L
    recordTicketEvent("control_code_input_typed_submit_now", "split_shell_text_ok")
    recordTicketEvent("control_code_submit_attempted", "coordinate_ok_after_digits digits=${digits.length}")
    return true
  }

  private suspend fun enterControlCodeDigitsFastForRequest(
    digits: String,
    transaction: FastControlCodePopupTransaction,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    val focus = measureInputPhase(phases, "focus_input_fast") {
      runFastNonTouchInput("input tap ${transaction.input.x} ${transaction.input.y}", "control_code_input_focus_fast")
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
        for i in 1 2 3 4 5 6; do input keyevent KEYCODE_DEL; done
        input text $digits
        """.trimIndent(),
        "control_code_request_type_digits_fast"
      )
    }
    if (!typed.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_set_failed")
      return false
    }
    delay(CONTROL_CODE_DIGITS_TYPED_SUBMIT_SETTLE_MILLIS)
    markControlCodeRequestPhase(phases, "digits_typed", requestStartedAtMillis)
    recordTicketEvent("control_code_input_typed_submit_now", "shell_text_ok")
    return true
  }

  private suspend fun tapControlCodeSubmitFastForRequest(
    transaction: FastControlCodePopupTransaction,
    digits: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    var submitAttempted = false
    val submit = resolveControlCodeSubmitAfterDigitsFastForRequest(transaction, phases)
    phases["ok_tap_target_source_popup_transaction"] = 1L
    recordTicketEvent("control_code_submit_current_popup_target", submit.reason)
    recordTicketEvent("control_code_submit_target", submit.reason)
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
      recordInputGateDecision(allowed = false, reason = "control_code_submit_missing")
    }
    return submitAttempted
  }

  private suspend fun resolveControlCodeSubmitAfterDigitsFastForRequest(
    transaction: FastControlCodePopupTransaction,
    phases: MutableMap<String, Long>
  ): TicketViviPageAction {
    val current = controlCodeRequestRootHierarchy(phases, "submit_after_digits_root")
      ?.let { hierarchy -> controlCodeFastTargetsForHierarchy(hierarchy) }
    if (current != null) {
      phases["ok_tap_target_source_current_popup_geometry"] = 1L
      recordTicketEvent(
        "control_code_submit_target_source",
        "current_popup_after_digits input=${current.inputSource} pre=${current.preKeyboardSubmitSource} keyboard=${current.keyboardOpenSubmitSource}"
      )
      return current.preKeyboardSubmit
    }
    phases["ok_tap_target_source_keyboard_geometry"] = 1L
    recordTicketEvent(
      "control_code_submit_target_source",
      "keyboard_geometry_after_digits_root_unavailable input=${transaction.inputSource} pre=${transaction.preKeyboardSubmitSource} keyboard=${transaction.keyboardOpenSubmitSource}"
    )
    return transaction.keyboardOpenSubmit
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
    rootHardwareH264CaptureEngine.requestBurst("control_code_after_ok")
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
    while (SystemClock.elapsedRealtime() < deadlineAtMillis) {
      val visualProbeStartedAtMillis = SystemClock.elapsedRealtime()
      rootHardwareH264CaptureEngine.requestControlCodeVisualProbe("control_code_after_ok_visual_state")
      val visualProbe = waitForFreshControlCodeVisualProbe(visualProbeStartedAtMillis, CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS)
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
                timeoutMillis = minOf(rootDumpTimeoutMillis, CONTROL_CODE_RAW_TICKET_ROOT_CONFIRM_TIMEOUT_MILLIS)
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
            if (rawTicketVisualCount >= CONTROL_CODE_RAW_TICKET_VISUAL_CONFIRM_COUNT) {
              val visualMarker = confirmGeneratedControlCodeResultForBrowser(
                value = "",
                hierarchy = CONTROL_CODE_MARKER_RESULT_HIERARCHY,
                phases = phases,
                requestStartedAtMillis = requestStartedAtMillis,
                waitStartedAtMillis = startedAtMillis,
                phase = "wait_result_phone_visual_generated_state",
                modeReason = "control_code_request_phone_visual_raw_ticket_after_submit",
                eventValue = "phone_visual_raw_ticket_after_submit",
                resultProof = "phone_visual_raw_ticket_after_submit"
              )
              phases["control_code_visual_popup_reject_count"] = popupRejectCount
              return ControlCodeResultWaitOutcome(generated = visualMarker, failureReason = "")
            }
          }
          visualProbe.result == "control_popup" -> {
            popupRejectCount += 1L
            phases["control_code_visual_popup_still_open"] = popupRejectCount
            if (popupRejectCount <= 3L || popupRejectCount % 10L == 0L) {
              recordTicketEvent(
                "control_code_visual_popup_still_open",
                "count=$popupRejectCount reason=${visualProbe.reason}"
              )
            }
          }
          else -> {
            phases["control_code_visual_non_generated_state"] = SystemClock.elapsedRealtime() - startedAtMillis
          }
        }
      }
      rootHardwareH264CaptureEngine.requestBurst("control_code_wait_generated_visual")
      delay(CONTROL_CODE_VISUAL_STATE_RETRY_MILLIS)
    }
    phases["wait_result_phone_visual_generated_state"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    phases["control_code_visual_popup_reject_count"] = popupRejectCount
    recordTicketEvent(
      "control_code_generated_state_timeout",
      "last_state=$lastObservedState popup_rejects=$popupRejectCount digits=${submittedDigits.length} root_dump_timeout_ms=$rootDumpTimeoutMillis"
    )
    return ControlCodeResultWaitOutcome(failureReason = "control_code_generated_state_timeout")
  }

  private suspend fun waitForFreshControlCodeVisualProbe(
    visualProbeStartedAtMillis: Long,
    timeoutMillis: Long
  ): TicketControlCodeVisualProbe? {
    val deadlineAtMillis = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadlineAtMillis) {
      rootHardwareH264CaptureEngine.recentControlCodeVisualProbeAfter(visualProbeStartedAtMillis)?.let { return it }
      delay(CONTROL_CODE_VISUAL_STATE_POLL_MILLIS)
    }
    return rootHardwareH264CaptureEngine.recentControlCodeVisualProbeAfter(visualProbeStartedAtMillis)
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
    val submit = TicketViviPageEnforcer.controlCodeSubmitActionLooseForHierarchy(hierarchy)
      ?: cachedControlCodePopupSurface()?.submit
      ?: fallbackControlCodeShiftedSubmitAction()
    return FastControlCodePopupTransaction(
      input = input,
      preKeyboardSubmit = submit,
      keyboardOpenSubmit = fallbackControlCodeKeyboardOpenSubmitAction(),
      inputSource = "root_hierarchy_loose",
      preKeyboardSubmitSource = "root_hierarchy_loose",
      keyboardOpenSubmitSource = "deterministic_keyboard_geometry:loose_root"
    )
  }

  private fun controlCodePopupTransactionForSurface(
    surface: TicketViviControlCodePopupSurface,
    source: String
  ): FastControlCodePopupTransaction {
    return FastControlCodePopupTransaction(
      input = surface.input,
      preKeyboardSubmit = surface.submit,
      keyboardOpenSubmit = fallbackControlCodeKeyboardOpenSubmitAction(),
      inputSource = "root_hierarchy:$source",
      preKeyboardSubmitSource = "root_hierarchy:$source",
      keyboardOpenSubmitSource = "deterministic_keyboard_geometry:$source"
    )
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

  private fun fallbackControlCodeShiftedSubmitAction(): TicketViviPageAction {
    return fallbackControlCodeKeyboardOpenSubmitAction().copy(reason = "submit_control_code_popup_shifted_geometry")
  }

  private fun fallbackControlCodeKeyboardOpenSubmitAction(): TicketViviPageAction {
    val (width, height) = currentDisplaySize()
    return TicketViviPageAction(
      x = (width * CONTROL_CODE_FAST_SHIFTED_OK_X_FRACTION).roundToInt(),
      y = (height * CONTROL_CODE_FAST_SHIFTED_OK_Y_FRACTION).roundToInt(),
      reason = "submit_control_code_popup_keyboard_geometry"
    )
  }

  private suspend fun openControlCodePopupForRequest(phases: MutableMap<String, Long>): Boolean {
    val hierarchy = measureInputPhase(phases, "find_button") {
      controlCodeRequestHierarchy()
    }
    if (hierarchy.isNullOrBlank()) {
      recordInputGateDecision(allowed = false, reason = "control_code_phone_state_normalizing")
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

    val keyboardBack = measureInputPhase(phases, "control_code_request_hide_keyboard") {
      runFastNonTouchInput("input keyevent KEYCODE_BACK", "control_code_request_hide_keyboard")
    }
    if (!keyboardBack.ok) {
      recordTicketEvent("control_code_keyboard_hide_failed", "duration_ms=${keyboardBack.durationMs}")
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
        recordInputGateDecision(allowed = false, reason = "control_code_submit_failed")
        return false
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
    val refreshedInputAction = measureInputPhase(phases, "find_input_after_root") {
      controlCodeRequestHierarchy()
        ?.let { hierarchy ->
          TicketViviPageEnforcer.controlCodeInputActionLooseForHierarchy(hierarchy)
            ?: TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy)?.input
        }
    } ?: inputAction
    val tapped = measureInputPhase(phases, "focus_input") {
      runFastNonTouchInput("input tap ${refreshedInputAction.x} ${refreshedInputAction.y}", "control_code_input_focus")
    }.ok
    if (!tapped) {
      recordInputGateDecision(allowed = false, reason = "control_code_input_focus_failed")
      return false
    }
    delay(CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS)
    val popupStillPresent = measureInputPhase(phases, "verify_input_open") {
      controlCodeRequestHierarchy()
        ?.let { hierarchy ->
          TicketViviPageEnforcer.controlCodePopupSurfaceForHierarchy(hierarchy) != null ||
            TicketViviPageEnforcer.hasControlCodeInputForHierarchy(hierarchy)
        } == true
    }
    if (!popupStillPresent) {
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

  private suspend fun completeControlCodeRawTicketAfterSubmitCleanup(
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    markControlCodeRequestPhase(phases, "cleanup_started", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "phone_raw_recovered", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_fast_cleanup_phase",
      "phone_visual_raw_ticket_after_submit"
    )
    updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)
    return completeControlExitCleanup(
      reason = reason,
      detectedState = TicketViviRecoveryState.CONTROL_CODE_RESULT.name,
      closeAction = "already_raw_after_submit",
      startedAtMillis = startedAtMillis,
      verificationResult = "phone_visual_raw_ticket_after_submit",
      freshFrameRequested = true
    )
  }

  private suspend fun returnControlCodeSurfaceToRawTicket(
    generatedHierarchy: String,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    if (generatedHierarchy == CONTROL_CODE_MARKER_RESULT_HIERARCHY) {
      rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
      val cleanupStart = beginGeneratedControlCodeResultFastClose(
        generatedHierarchy = generatedHierarchy,
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
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
          requestStartedAtMillis = requestStartedAtMillis
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
    sendTicketStateEvent(
      ticketState = TICKET_PIXEL_STATE_RETURNING_RAW,
      reason = reason,
      requestId = lastControlCodeRequestId.orEmpty()
    )

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
    return false
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
    while (SystemClock.elapsedRealtime() <= deadlineMillis) {
      val state = measureInputPhase(phases, "cleanup_root_verify") {
        val hierarchy = controlExitHierarchy()
        if (hierarchy.isNullOrBlank()) {
          TicketViviRecoveryState.UNKNOWN_VIVI
        } else {
          TicketViviPageEnforcer.classifyForRecovery(hierarchy)
        }
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

  private fun recentSuccessfulControlExitCleanup(): Boolean {
    val completedAge = ageMillis(lastControlExitCleanupCompletedAtMillis, SystemClock.elapsedRealtime())
    return lastControlExitCleanupSucceeded == true &&
      completedAge != null &&
      completedAge <= CONTROL_CODE_POST_CLEANUP_SOFT_CHECK_GRACE_MILLIS
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
    lastPixelTicketEventReason = reason
    lastPixelTicketEventRequestId = requestId
    lastPixelTicketEventStreamEpoch = eventStreamEpoch
    lastPixelTicketEventFrameSequence = eventFrameSequence
    lastPixelTicketEventMinFrameSequence = minFrameSequence
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
    try {
      controlCodeRequestMutex.withLock {
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
        broadcastStatus()
      }
    } finally {
      protectedControlClients.remove(replyClient)
      canceledRigasSatiksmeBatchIds.remove(cleanBatchId)
    }
    pendingImmediateCleanup?.let { cleanup ->
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
    lastControlCodeRequestValue = value.ifBlank { null }
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
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "control_code_result",
      "$requestId ok=$ok reason=$reason value=${value.ifBlank { "empty" }} duration_ms=$totalDurationMillis"
    )
    if (!ok && !cleanupPending && streamActive) {
      recordTicketEvent("foreground_guard_resumed_after_control_code_failure", reason)
      startForegroundGuard()
    }
    broadcastStatus()
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
    controlClientSnapshot().forEach { client -> client.sendText(message) }
    recordTicketEvent(
      "control_code_cleanup_complete",
      "$requestId ok=$ok reason=$reason duration_ms=$totalDurationMillis"
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
    val result = rootExecutor.runScript(
      wrapNonTouchPanelSleepClamp(command, commandTimeout = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds)
    ).also { recordInputCommandResult(reason, it) }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    return result
  }

  private suspend fun runFastNonTouchInput(command: String, reason: String): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    val result = inputRootExecutor.runScript(
      wrapNonTouchPanelSleepClamp(command, commandTimeout = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds)
    ).also { recordInputCommandResult(reason, it) }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    return result
  }

  private suspend fun runFastNonTouchWakeInput(command: String, reason: String): RootResult {
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      wakeRootExecutor.runScript(
        wrapNonTouchPanelSleepClamp(
          command,
          postMillis = TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS,
          commandTimeout = TICKET_WAKE_COMMAND_TIMEOUT_MILLIS.milliseconds
        ),
        TICKET_WAKE_COMMAND_TIMEOUT_MILLIS.milliseconds
      ).also { recordInputCommandResult(reason, it) }
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
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
    val commandTimeoutMillis = commandTimeout?.inWholeMilliseconds
      ?.minus(NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS)
      ?.coerceAtLeast(250L)
    val commandTimeoutLiteral = commandTimeoutMillis?.let {
      "${it / 1_000}.${(it % 1_000).toString().padStart(3, '0')}s"
    }
    val quotedCommand = shellQuote(command)
    val commandTimeoutScript = if (commandTimeoutLiteral == null) {
      """
        sh "${'$'}ticket_command_file"
        ticket_command_rc=${'$'}?
      """.trimIndent()
    } else {
      """
        if command -v timeout >/dev/null 2>&1; then
          timeout -k 0.250s $commandTimeoutLiteral sh "${'$'}ticket_command_file"
          ticket_command_rc=${'$'}?
          if [ "${'$'}ticket_command_rc" -eq 124 ] || [ "${'$'}ticket_command_rc" -eq 137 ]; then
            ticket_command_timed_out=1
          fi
        else
          sh "${'$'}ticket_command_file"
          ticket_command_rc=${'$'}?
        fi
      """.trimIndent()
    }
    return """
      ticket_clamp_stop="/data/local/tmp/pixel-ticket-panel-clamp-${'$'}${'$'}-$(date +%s%N 2>/dev/null || date +%s)"
      ticket_command_file="/data/local/tmp/pixel-ticket-nontouch-command-${'$'}${'$'}-$(date +%s%N 2>/dev/null || date +%s).sh"
      ticket_sysfs_clamp_pid=""
      ticket_display_clamp_pid=""
      ticket_command_rc=0
      ticket_command_timed_out=0
      rm -f "${'$'}ticket_clamp_stop" "${'$'}ticket_command_file" >/dev/null 2>&1 || true
      ticket_panel_dir=""
      for ticket_candidate in /sys/class/backlight/panel0-backlight /sys/class/backlight/*; do
        if [ -f "${'$'}ticket_candidate/brightness" ]; then
          ticket_panel_dir="${'$'}ticket_candidate"
          break
        fi
      done
      if [ -n "${'$'}ticket_panel_dir" ]; then
        echo 0 > "${'$'}ticket_panel_dir/brightness" 2>/dev/null || true
      fi
      settings put system screen_brightness_mode 0 >/dev/null 2>&1 || true
      if ! cmd display set-brightness 0 --unit percentage >/dev/null 2>&1; then
        settings put system screen_brightness 0 >/dev/null 2>&1 || true
      fi
      settings put system screen_brightness 0 >/dev/null 2>&1 || true
      ticket_panel_clamp_sleep() {
        sleep 0.005 2>/dev/null || usleep $intervalMicros 2>/dev/null || sleep 1
      }
      ticket_panel_display_sleep() {
        sleep 0.100 2>/dev/null || usleep 100000 2>/dev/null || sleep 1
      }
      ticket_panel_sysfs_clamp() {
        while [ ! -f "${'$'}ticket_clamp_stop" ]; do
          if [ -n "${'$'}ticket_panel_dir" ]; then
            echo 0 > "${'$'}ticket_panel_dir/brightness" 2>/dev/null || true
          fi
          ticket_panel_clamp_sleep
        done
      }
      ticket_panel_post_clamp() {
        ticket_panel_post_write_index=0
        while [ "${'$'}ticket_panel_post_write_index" -lt "$postWrites" ]; do
          if [ -n "${'$'}ticket_panel_dir" ]; then
            echo 0 > "${'$'}ticket_panel_dir/brightness" 2>/dev/null || true
          fi
          settings put system screen_brightness_mode 0 >/dev/null 2>&1 || true
          if ! cmd display set-brightness 0 --unit percentage >/dev/null 2>&1; then
            settings put system screen_brightness 0 >/dev/null 2>&1 || true
          fi
          settings put system screen_brightness 0 >/dev/null 2>&1 || true
          ticket_panel_post_write_index=${'$'}((ticket_panel_post_write_index + 1))
          if [ "${'$'}ticket_panel_post_write_index" -lt "$postWrites" ]; then
            ticket_panel_clamp_sleep
          fi
        done
      }
      ticket_panel_display_clamp() {
        while [ ! -f "${'$'}ticket_clamp_stop" ]; do
          settings put system screen_brightness_mode 0 >/dev/null 2>&1 || true
          if ! cmd display set-brightness 0 --unit percentage >/dev/null 2>&1; then
            settings put system screen_brightness 0 >/dev/null 2>&1 || true
          fi
          settings put system screen_brightness 0 >/dev/null 2>&1 || true
          ticket_panel_display_sleep
        done
        settings put system screen_brightness_mode 0 >/dev/null 2>&1 || true
        if ! cmd display set-brightness 0 --unit percentage >/dev/null 2>&1; then
          settings put system screen_brightness 0 >/dev/null 2>&1 || true
        fi
        settings put system screen_brightness 0 >/dev/null 2>&1 || true
      }
      ticket_panel_stop_clamps() {
        touch "${'$'}ticket_clamp_stop" >/dev/null 2>&1 || true
        if [ -n "${'$'}ticket_sysfs_clamp_pid" ]; then
          wait "${'$'}ticket_sysfs_clamp_pid" >/dev/null 2>&1 || true
        fi
        if [ -n "${'$'}ticket_display_clamp_pid" ]; then
          wait "${'$'}ticket_display_clamp_pid" >/dev/null 2>&1 || true
        fi
      }
      ticket_panel_trap_cleanup() {
        ticket_trap_rc="${'$'}1"
        ticket_panel_stop_clamps
        rm -f "${'$'}ticket_clamp_stop" "${'$'}ticket_command_file" >/dev/null 2>&1 || true
        exit "${'$'}ticket_trap_rc"
      }
      trap 'ticket_panel_trap_cleanup 143' HUP INT TERM
      ticket_panel_sysfs_clamp >/dev/null 2>&1 &
      ticket_sysfs_clamp_pid=${'$'}!
      ticket_panel_display_clamp >/dev/null 2>&1 &
      ticket_display_clamp_pid=${'$'}!
      ticket_panel_clamp_sleep
      printf '%s\n' $quotedCommand > "${'$'}ticket_command_file"
      chmod 0700 "${'$'}ticket_command_file" >/dev/null 2>&1 || true
      $commandTimeoutScript
      rm -f "${'$'}ticket_command_file" >/dev/null 2>&1 || true
      ticket_panel_stop_clamps
      if [ "${'$'}ticket_command_timed_out" -eq 0 ]; then
        ticket_panel_post_clamp >/dev/null 2>&1 &
      elif [ -n "${'$'}ticket_panel_dir" ]; then
        echo 0 > "${'$'}ticket_panel_dir/brightness" 2>/dev/null || true
      fi
      rm -f "${'$'}ticket_clamp_stop" >/dev/null 2>&1 || true
      trap - HUP INT TERM
      exit "${'$'}ticket_command_rc"
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

  // ===========================================================================
  // Rīgas Satiksme re-login channel
  // ----------------------------------------------------------------------------
  // Pixel-side consumer for the admin-driven re-login channel
  // (broker `POST /api/v1/rs/login/start` → WebSocket `rigassatiksme_login_start`).
  // Pixel runs a bounded, state-gated login flow and broadcasts the result
  // back to the broker as `rigassatiksme_login_result { state, failureReason? }`.
  //
  // Safety contract (mirrors the broker side):
  //   * The SMS code is never logged, never written to disk, never echoed in
  //     any `recordTicketEvent` or `Log.*` call.
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
    val result = runRigasSatiksmeVisualInput(
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
    val result = runRigasSatiksmeVisualInput(
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
    val tapResult = runRigasSatiksmeVisualInput(
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
    val tapResult = runRigasSatiksmeVisualInput(
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
    const val SERVER_VERSION = "ticket-stream-2026-05-23-priority-rs-vivi-v235"
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
    private const val TICKET_PIXEL_STATE_ATTENTION_NEEDED = "attention_needed"
    private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 750L
    private const val LIVE_FRAME_MAX_AGE_MILLIS = 2_000L
    private const val ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS = 30_000L
    private const val STREAM_STALE_ENGINE_RESTART_MILLIS = 4_000L
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
    private const val SECURE_CAPTURE_PROBE_EXIT_TIMEOUT_MILLIS = 250L
    private const val SECURE_CAPTURE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = 10_000L
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
    private const val TICKET_WAKE_RECOVERY_BUDGET_MILLIS = 60_000L
    private const val TICKET_WAKE_RECOVERY_MAX_ACTIONS = 4
    private const val TICKET_RS_MONTHLY_RETURN_BUDGET_MILLIS = 45_000L
    private const val TICKET_RS_MONTHLY_RETURN_MAX_RECOVERY_ACTIONS = 6
    private const val TICKET_RS_MONTHLY_IDLE_CLEANUP_DELAY_MILLIS = 2_500L
    private const val TICKET_RS_MONTHLY_FAST_RETURN_TIMEOUT_MILLIS = 8_000L
    private const val RIGAS_SATIKSME_RESULT_CAPTURE_MAX_WIDTH = 720
    private const val RIGAS_SATIKSME_VISUAL_CAPTURE_WIDTH = 360
    private const val RIGAS_SATIKSME_VISUAL_CAPTURE_TIMEOUT_MILLIS = 2_500L
    private const val RIGAS_SATIKSME_VISUAL_INPUT_TIMEOUT_MILLIS = 5_000L
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
    private const val TICKET_WAKE_LAUNCH_TIMEOUT_MILLIS = 1_500L
    private const val TICKET_WAKE_FAST_INITIAL_TIMEOUT_MILLIS = 0L
    private const val TICKET_WAKE_FAST_POST_LAUNCH_TIMEOUT_MILLIS = 600L
    private const val TICKET_WAKE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS = 450L
    private const val TICKET_WAKE_POST_LAUNCH_FAST_READY_TIMEOUT_MILLIS = 1_400L
    private const val TICKET_WAKE_POST_LAUNCH_FAST_READY_POLL_MILLIS = 120L
    private const val TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS = 10 * 60_000L
    private const val TICKET_WAKE_GUARD_GRACE_MILLIS = 1_000L
    private const val TICKET_WAKE_FAST_POLL_MILLIS = 100L
    private const val NON_TOUCH_SCRIPT_REASSERT_INTERVAL_MILLIS = 250L
    private const val NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS = 120_000L
    private const val NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS = 250L
    private const val NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS = 5L
    private const val TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS = 250L
    private const val NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS = 500L
    private const val STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS = 5_000L
    private const val CLIENT_DISCONNECT_IDLE_GRACE_MILLIS = 30_000L
    private const val VIVI_FOREGROUND_INITIAL_DELAY_MILLIS = 1_500L
    private const val VIVI_FOREGROUND_CHECK_MILLIS = 750L
    private const val VIVI_FOREGROUND_GRACE_MILLIS = 8_000L
    private const val VIVI_PAGE_ENFORCE_INTERVAL_MILLIS = 2_000L
    private const val FOREGROUND_GUARD_RECENT_TICKET_LOG_INTERVAL_MILLIS = 30_000L
    private const val FOREGROUND_GUARD_INCONCLUSIVE_BACKOFF_MILLIS = 30_000L
    private const val FOREGROUND_GUARD_RECENT_WAKE_READY_ROOTLESS_MILLIS = 30_000L
    private const val FOREGROUND_GUARD_RECENT_TICKET_DETAIL_SKIP_MAX_AGE_MILLIS = 3_000L
    private const val TICKET_WAKE_RECENT_DETAIL_FAST_READY_MAX_AGE_MILLIS = TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS
    private const val TICKET_SCREEN_WAKE_HOLD_MILLIS = 30_000L
    private const val TICKET_SCREEN_WAKE_REQUEST_COOLDOWN_MILLIS = 2_000L
    private const val ACTIVE_AUTOPILOT_MAX_STEPS = 2
    private const val ACTIVE_AUTOPILOT_TIMEOUT_MILLIS = 1_000L
    private const val VIVI_LOGIN_SECRET_FILE = "/data/local/pixel-stack/conf/apps/ticket-screen-vivi-login.env"
    private const val VIVI_LOGIN_EMAIL_ENV = "VIVI_LOGIN_EMAIL"
    private const val VIVI_LOGIN_SECRET_ENV = "VIVI_LOGIN_PASSWORD"
    private const val VIVI_LOGIN_SECRET_READ_TIMEOUT_MILLIS = 1_500L
    private const val VIVI_LOGIN_FIELD_SETTLE_MILLIS = 150L
    private const val VIVI_LOGIN_POST_SUBMIT_TIMEOUT_MILLIS = 12_000L
    private const val VIVI_LOGIN_POST_SUBMIT_POLL_MILLIS = 400L
    private const val VIVI_LOGIN_ROOT_DUMP_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_CODE_INPUT_FOCUS_SETTLE_MILLIS = 80L
    private const val CONTROL_CODE_POPUP_READY_CACHE_MILLIS = 2_000L
    private const val CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 600L
    private const val CONTROL_CODE_FAST_RESULT_ROOT_DUMP_TIMEOUT_MILLIS = 2_500L
    private const val CONTROL_CODE_RAW_TICKET_ROOT_CONFIRM_TIMEOUT_MILLIS = 700L
    private const val CONTROL_CODE_RAW_TICKET_VISUAL_CONFIRM_COUNT = 2L
    private const val CONTROL_CODE_FAST_RESULT_FINAL_ROOT_DUMP_TIMEOUT_MILLIS = 1_500L
    private const val CONTROL_CODE_GENERATED_RESULT_HARD_ROOT_DUMP_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_CODE_FAST_ROOT_RETRY_COUNT = 1
    private const val CONTROL_CODE_PREPARE_ROOT_DUMP_TIMEOUT_MILLIS = 700L
    private const val CONTROL_CODE_FAST_POPUP_TIMEOUT_MILLIS = 2_400L
    private const val CONTROL_CODE_FAST_POPUP_VISUAL_WAIT_MILLIS = 650L
    private const val CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS = 10_000L
    private const val CONTROL_CODE_BROWSER_CAPTURE_ACK_POLL_MILLIS = 40L
    private const val CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS = 10_000L
    private const val CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS = 0L
    private const val CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS = 250L
    private const val CONTROL_CODE_VISUAL_STATE_POLL_MILLIS = 40L
    private const val CONTROL_CODE_VISUAL_STATE_RETRY_MILLIS = 50L
    private const val CONTROL_CODE_DIGITS_TYPED_SUBMIT_SETTLE_MILLIS = 40L
    private const val CONTROL_CODE_FAST_POLL_MILLIS = 90L
    private const val CONTROL_CODE_FAST_BUTTON_X_FRACTION = 0.23f
    private const val CONTROL_CODE_FAST_BUTTON_Y_FRACTION = 0.136f
    private const val CONTROL_CODE_FAST_POPUP_INPUT_X_FRACTION = 0.50f
    private const val CONTROL_CODE_FAST_POPUP_INPUT_Y_FRACTION = 0.511f
    private const val CONTROL_CODE_FAST_SHIFTED_OK_X_FRACTION = 0.738f
    private const val CONTROL_CODE_FAST_SHIFTED_OK_Y_FRACTION = 0.422f
    private const val CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_X_FRACTION = 0.738f
    private const val CONTROL_CODE_POPUP_TRANSACTION_SUBMIT_Y_FRACTION = 0.573f
    private const val CONTROL_CODE_FAST_POPUP_GEOMETRY_SETTLE_MILLIS = 60L
    private const val CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = 15_000L
    private const val CONTROL_CODE_STALE_PREPARE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS
    private const val CONTROL_CODE_REQUEST_PREPARE_MAX_STEPS = 5
    private const val CONTROL_CODE_REQUEST_PREPARE_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_REQUEST_HIERARCHY_TIMEOUT_MILLIS = 6_000L
    private const val CONTROL_CODE_REQUEST_POPUP_TIMEOUT_MILLIS = 4_000L
    private const val CONTROL_CODE_REQUEST_SUBMIT_TRANSITION_TIMEOUT_MILLIS = 3_500L
    private const val CONTROL_CODE_SUBMIT_FIND_TIMEOUT_MILLIS = 1_200L
    private const val CONTROL_CODE_SUBMIT_FIND_POLL_MILLIS = 120L
    private const val CONTROL_CODE_REQUEST_RESULT_TIMEOUT_MILLIS = 12_000L
    private const val CONTROL_CODE_REQUEST_POLL_MILLIS = 180L
    private const val CONTROL_CODE_REQUEST_NO_RESULT_GRACE_MILLIS = 900L
    private const val CONTROL_CODE_REQUEST_NO_RESULT_TICKET_DETAIL_COUNT = 2
    private const val CONTROL_CODE_SNAP_MEMORY_MAX_AGE_MILLIS = 5_000L
    private const val CONTROL_CODE_SNAP_UNSAFE_STATE_MEMORY_MAX_AGE_MILLIS = 10_000L
    private const val RECENT_INPUT_RESULT_CACHE_SIZE = 80
    private const val RECENT_CONTROL_CODE_RESULT_CACHE_SIZE = 6
    private const val CONTROL_CODE_RESULT_CACHE_TTL_MILLIS = 90_000L
    private const val CONTROL_CODE_RESULT_IMAGE_CROP_PADDING = 32
    private const val CONTROL_CODE_RESULT_IMAGE_MIN_CROP_SIZE = 160
    private const val PNG_BASE64_BEGIN = "PNG_BASE64_BEGIN"
    private const val PNG_BASE64_END = "PNG_BASE64_END"
    private const val SNAP_TARGET_CONTROL_CODE_BUTTON = "control_code_button"
    private val CONTROL_CODE_REQUEST_DIGITS_REGEX = Regex("""^[0-9]{2,8}$""")
    private const val CONTROL_CODE_SOFT_CHECK_DELAY_MILLIS = 200L
    private const val CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS = 10_000L
    private const val CONTROL_CODE_SOFT_CHECK_MAX_STEPS = 2
    private const val CONTROL_CODE_POST_CLEANUP_SOFT_CHECK_GRACE_MILLIS = 8_000L
    private const val CONTROL_CODE_EXIT_VERIFY_SETTLE_MILLIS = 220L
    private const val CONTROL_CODE_EXIT_VERIFY_TIMEOUT_MILLIS = 1_200L
    private const val CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 900L
    private const val CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS = 75L
    private const val CONTROL_CODE_FAST_CLEANUP_ROOT_DUMP_TIMEOUT_MILLIS = 700L
    private const val CONTROL_CODE_GENERATED_HEAL_MAX_CLOSE_ATTEMPTS = 2
    private const val CONTROL_EXIT_SECOND_VERIFY_DELAY_MILLIS = 260L
    private const val CONTROL_EXIT_RECENT_DIRECT_CLOSE_MILLIS = 1_500L
    private const val TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS = 4_500L
    private const val CONTROL_EXIT_SURFACE_OBSERVE_TIMEOUT_MILLIS = 1_500L
    private const val CONTROL_EXIT_SURFACE_OBSERVE_RETRY_MILLIS = 120L
    private const val CONTROL_EXIT_FINAL_CONFIRM_DELAY_MILLIS = 350L
    private const val CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS = 12_000L
    private const val CONTROL_EXIT_RESULT_CLOSE_X_FRACTION = 0.82f
    private const val CONTROL_EXIT_RESULT_CLOSE_Y_FRACTION = 0.568f
    private const val CONTROL_EXIT_DUPLICATE_SUCCESS_KEEP_MILLIS = 15_000L
    private const val CONTROL_EXIT_CLEANUP_MAX_STEPS = 2
    private const val CONTROL_EXIT_CLEANUP_TIMEOUT_MILLIS = 1_800L
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
    const STALE_FRAME_SOFT_MS = 2000;
    const FRAME_DECODE_MAX_AGE_MS = 1500;
    const FRAME_RENDER_MAX_AGE_MS = 2000;
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
    let lastDrawnFrameEpoch = 0;
    let lastDrawnFrameSequence = 0;
    let pendingDecodedFrameMetadata = [];
    let configuredFrameEnvelope = 'legacy';
    let lastFrameDropLogAt = 0;
    let lastStaleFrameKeyframeAt = 0;
    let currentCodeRequestId = '';
    let codeResultTimer = null;
    let pendingGeneratedCodeResultEvent = null;
    let generatedCodeResultFrozen = false;
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
    const keyFrameOnlyLatestVideo = true;
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
    function clearGeneratedCodeResultFreeze(reason = 'code_result_reset', requestRefresh = true) {
      if (!generatedCodeResultFrozen) return;
      generatedCodeResultFrozen = false;
      delete canvas.dataset.controlCodeFrozen;
      clientLog('control_code_result_frame_unfrozen', {
        reason,
        drawnFrameEpoch: lastDrawnFrameEpoch,
        drawnFrameSequence: lastDrawnFrameSequence
      });
      if (requestRefresh) {
        sendVideo({type: 'keyframe', reason: 'control_code_result_unfreeze'}) || send({type: 'keyframe', reason: 'control_code_result_unfreeze'});
      }
    }
    function freezeGeneratedCodeResultFrame(msg) {
      const minFrameSequence = Number(msg && msg.minFrameSequence || 0);
      if (minFrameSequence && lastDrawnFrameSequence < minFrameSequence) return false;
      if (generatedCodeResultFrozen) return true;
      generatedCodeResultFrozen = true;
      canvas.dataset.controlCodeFrozen = 'true';
      clientLog('control_code_result_frame_frozen', {
        requestId: msg && msg.requestId || '',
        streamEpoch: Number(msg && msg.streamEpoch || 0),
        minFrameSequence,
        drawnFrameEpoch: lastDrawnFrameEpoch,
        drawnFrameSequence: lastDrawnFrameSequence
      });
      return true;
    }
    function hideCodeResult() {
      clearCodeResultTimer();
      pendingGeneratedCodeResultEvent = null;
      clearGeneratedCodeResultFreeze('code_result_dismissed');
      codeResult.hidden = true;
      codeResultValue.hidden = true;
      codeResultValue.textContent = '';
      codeResultStatus.textContent = '';
      currentCodeRequestId = '';
    }
    function showCodeResult(message, value = '') {
      clearCodeResultTimer();
      codeResult.hidden = false;
      codeResultStatus.textContent = message;
      codeResultValue.textContent = value || '';
      codeResultValue.hidden = !value;
    }
    function generatedResultRequestMatches(msg) {
      const messageRequestId = String(msg && msg.requestId || '');
      return !messageRequestId || !currentCodeRequestId || messageRequestId === currentCodeRequestId;
    }
    function generatedResultFrameReady(msg) {
      const minFrameSequence = Number(msg && msg.minFrameSequence || 0);
      if (!minFrameSequence) return true;
      const eventStreamEpoch = Number(msg && msg.streamEpoch || 0);
      if (eventStreamEpoch && currentStreamEpoch && eventStreamEpoch !== currentStreamEpoch) return false;
      if (eventStreamEpoch && lastDrawnFrameEpoch && lastDrawnFrameEpoch !== eventStreamEpoch) return false;
      return lastDrawnFrameSequence >= minFrameSequence;
    }
    function flushPendingGeneratedCodeResult() {
      const pending = pendingGeneratedCodeResultEvent;
      if (!pending) return false;
      if (!generatedResultRequestMatches(pending)) {
        pendingGeneratedCodeResultEvent = null;
        return false;
      }
      if (!generatedResultFrameReady(pending)) return false;
      pendingGeneratedCodeResultEvent = null;
      freezeGeneratedCodeResultFrame(pending);
      showCodeResult('Code ready', pending.value || '');
      clientLog('control_code_result_frame_ready', {
        requestId: pending.requestId || '',
        streamEpoch: Number(pending.streamEpoch || 0),
        minFrameSequence: Number(pending.minFrameSequence || 0),
        drawnFrameEpoch: lastDrawnFrameEpoch,
        drawnFrameSequence: lastDrawnFrameSequence
      });
      return true;
    }
    function handleGeneratedCodeResultEvent(msg) {
      if (!generatedResultRequestMatches(msg)) return;
      if (generatedResultFrameReady(msg)) {
        pendingGeneratedCodeResultEvent = null;
        freezeGeneratedCodeResultFrame(msg);
        showCodeResult('Code ready', msg.value || '');
        return;
      }
      pendingGeneratedCodeResultEvent = msg;
      showCodeResult('Waiting for Aztec frame');
      clientLog('control_code_result_waiting_for_frame', {
        requestId: msg.requestId || '',
        streamEpoch: Number(msg.streamEpoch || 0),
        minFrameSequence: Number(msg.minFrameSequence || 0),
        drawnFrameEpoch: lastDrawnFrameEpoch,
        drawnFrameSequence: lastDrawnFrameSequence
      });
      sendVideo({type: 'keyframe', reason: 'control_code_result_waiting_for_frame'}) || send({type: 'keyframe', reason: 'control_code_result_waiting_for_frame'});
    }
    function handleControlCodeResult(msg) {
      const messageRequestId = String(msg.requestId || '');
      if (!messageRequestId || messageRequestId !== currentCodeRequestId) return;
      codeSubmit.disabled = false;
      if (msg.ok === true || msg.accepted === true) {
        handleGeneratedCodeResultEvent(msg);
      } else {
        pendingGeneratedCodeResultEvent = null;
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
	      if (digits.length < 2 || digits.length > 8) {
	        showCodeResult('Enter 2-8 digits');
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
      send({type: 'generate_control_code', requestId: currentCodeRequestId, digits, owner: 'ticket', app: 'vivi', flow: 'control_code'});
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
    function handleViewportChanged() {
      resizeCanvasBox();
      if (configured && desiredActive && !generatedCodeResultFrozen) {
        sendVideo({type: 'keyframe', reason: 'viewport_changed'}) || send({type: 'keyframe', reason: 'viewport_changed'});
      }
    }
    window.addEventListener('orientationchange', handleViewportChanged);
    if (screen.orientation && screen.orientation.addEventListener) {
      screen.orientation.addEventListener('change', handleViewportChanged);
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
      if (!generatedCodeResultFrozen) {
        setStreamReady(false);
      }
      setStreamState('connecting', message);
      updateIdleTimer(null);
      if (decoder) {
        try { decoder.close(); } catch (_) {}
        decoder = null;
      }
      if (!generatedCodeResultFrozen) {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        keepFirstScreenPinned();
      }
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
        if (
          msg.type === 'ticket_state_event' &&
          msg.ticketState === 'generated_result' &&
          (!msg.requestId || String(msg.requestId) === currentCodeRequestId)
        ) {
          handleGeneratedCodeResultEvent(msg);
        }
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
          const videoSocketIsCurrent = videoWs === socket;
          clientLog('video_websocket_close', {code: event.code, reason: event.reason, clean: event.wasClean, desiredActive, current: videoSocketIsCurrent});
          clearTimeout(timeout);
          if (!settled) {
            settled = true;
            reject(new Error('video websocket closed'));
          }
          if (videoSocketIsCurrent) videoWs = null;
          if (desiredActive && !autoStartSuspended && videoSocketIsCurrent) {
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
      const receivedAt = performance.now();
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
          receivedAt,
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
        receivedAt,
        data: data.slice(9)
      };
    }
    function frameAgeMs(frame, now = performance.now()) {
      return now - Number(frame && frame.receivedAt || now);
    }
    function dropStaleFrameBeforeDecode(frame) {
      const ageMs = frameAgeMs(frame);
      if (ageMs <= FRAME_DECODE_MAX_AGE_MS) return false;
      needsKeyFrame = true;
      logFrameDrop('stale_before_decode', {ageMs: Math.round(ageMs), sequence: frame && frame.sequence || 0});
      sendVideo({type: 'keyframe', reason: 'stale_before_decode'}) || send({type: 'keyframe', reason: 'stale_before_decode'});
      return true;
    }
    function rememberPendingDecodedFrame(frame) {
      const metadata = {
        epoch: Number(frame.epoch || 0),
        timestamp: Number(frame.timestamp || 0),
        sequence: Number(frame.sequence || 0),
        receivedAt: Number(frame.receivedAt || performance.now())
      };
      pendingDecodedFrameMetadata.push(metadata);
      while (pendingDecodedFrameMetadata.length > 30) pendingDecodedFrameMetadata.shift();
      return metadata;
    }
    function takePendingDecodedFrameMetadata(videoFrame) {
      if (!pendingDecodedFrameMetadata.length) return null;
      const timestamp = Number(videoFrame && videoFrame.timestamp || 0);
      if (timestamp) {
        const index = pendingDecodedFrameMetadata.findIndex((metadata) => metadata.timestamp === timestamp);
        if (index >= 0) {
          const match = pendingDecodedFrameMetadata.splice(index, 1)[0];
          return match;
        }
      }
      return pendingDecodedFrameMetadata.shift();
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
      if (keyFrameOnlyLatestVideo && frame.kind !== 'key') {
        needsKeyFrame = true;
        sendVideo({type: 'keyframe', reason: 'non_key_frame_ignored'}) || send({type: 'keyframe', reason: 'non_key_frame_ignored'});
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
      if (dropStaleFrameBeforeDecode(frame)) return;
      if (!decoder) return;
      rememberPendingDecodedFrame(frame);
      try {
        decoder.decode(new EncodedVideoChunk({type: frame.kind, timestamp: frame.timestamp, data: frame.data}));
      } catch (error) {
        clientLog('decoder_decode_failed', {message: String(error && error.message || error)});
        recoverVideoPipeline('decoder_decode_failed', 'Reconnecting stream...');
      }
    }
    function normalizedStreamDimension(value, fallback) {
      const numeric = Math.round(Number(value || 0));
      if (Number.isFinite(numeric) && numeric >= 2) return numeric;
      return Math.max(2, Math.round(Number(fallback || 2)));
    }
    function applyStreamConfigDimensions(config) {
      const width = normalizedStreamDimension(config.width, streamDimensions.width || canvas.width);
      const height = normalizedStreamDimension(config.height, streamDimensions.height || canvas.height);
      canvas.width = width;
      canvas.height = height;
      ctx.imageSmoothingEnabled = false;
      streamDimensions = {width, height};
      canvas.style.setProperty('--stream-aspect', `${'$'}{width} / ${'$'}{height}`);
      canvas.dataset.streamWidth = String(width);
      canvas.dataset.streamHeight = String(height);
      resizeCanvasBox();
      return {width, height};
    }
    async function configureDecoder(config) {
      if (!checkServerVersion(config)) return;
      if (!('VideoDecoder' in window)) {
        clientLog('webcodecs_missing');
        await endSession('webcodecs_missing', false, 'This browser does not support WebCodecs');
        return;
      }
      const h264 = String(config.codec || '').startsWith('avc1') || config.transport === '${TicketScreenConfig.ROOT_HARDWARE_H264_TRANSPORT}';
      const configWidth = normalizedStreamDimension(config.width, streamDimensions.width || canvas.width);
      const configHeight = normalizedStreamDimension(config.height, streamDimensions.height || canvas.height);
      const decoderConfig = {codec: config.codec, codedWidth: configWidth, codedHeight: configHeight};
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
          width: configWidth,
          height: configHeight
        });
      }
      const appliedDimensions = applyStreamConfigDimensions(config);
      clientLog('stream_dimensions_applied', {
        width: appliedDimensions.width,
        height: appliedDimensions.height,
        sourceWidth: Number(config.sourceWidth || 0),
        sourceHeight: Number(config.sourceHeight || 0),
        sourceTopCrop: Number(config.sourceTopCrop || 0),
        streamEpoch: Number(config.streamEpoch || 0)
      });
      configuredFrameEnvelope = config.frameEnvelope || 'legacy';
      currentStreamEpoch = Number(config.streamEpoch || 0);
      lastAcceptedFrameSequence = 0;
      lastDrawnFrameEpoch = 0;
      lastDrawnFrameSequence = 0;
      pendingDecodedFrameMetadata = [];
      lastConfigAt = performance.now();
      lastFrameAt = 0;
      setStreamState('waiting');
      decoder = new VideoDecoder({
        output(frame) {
          const now = performance.now();
          const metadata = takePendingDecodedFrameMetadata(frame);
          const frameAgeMs = metadata ? now - Number(metadata.receivedAt || now) : 0;
          if (frameAgeMs > FRAME_RENDER_MAX_AGE_MS) {
            frame.close();
            needsKeyFrame = true;
            sendVideo({type: 'keyframe', reason: 'stale_at_render'}) || send({type: 'keyframe', reason: 'stale_at_render'});
            return;
          }
          if (generatedCodeResultFrozen) {
            lastFrameAt = performance.now();
            frame.close();
            return;
          }
          ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
          if (metadata) {
            lastDrawnFrameEpoch = Number(metadata.epoch || 0);
            lastDrawnFrameSequence = Number(metadata.sequence || 0);
            flushPendingGeneratedCodeResult();
          }
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
    window.addEventListener('resize', handleViewportChanged);
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', handleViewportChanged);
      window.visualViewport.addEventListener('scroll', handleViewportChanged);
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
        const hardwareSupported = Boolean(health.hardwareH264 && health.hardwareH264.available);
        if (!health.viviInstalled || !hardwareSupported) return;
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
      } else if (!health.viviInstalled || !(health.hardwareH264 && health.hardwareH264.available)) {
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
