package lv.jolkins.pixelorchestrator.app.ticket

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
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
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
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationTicketInputFence
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviAuthSurface
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviLoginField
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviLogoutClickTarget
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviLogoutSurface
import lv.jolkins.pixelorchestrator.app.phoneautomation.TicketSliderGestureDispatchResult
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationVisibleNode
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhonePortraitLock
import lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessControl
import lv.jolkins.pixelorchestrator.app.phoneautomation.ScreenBrightnessState
import lv.jolkins.pixelorchestrator.app.phoneautomation.TouchBrightnessRuntimeState
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import lv.jolkins.pixelorchestrator.rootexec.SuRootExecutor
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal data class TicketLatestTicketReselectAbsentState(
  val interactionRevision: String,
  val requireUnactivatedRegistration: Boolean
)

private data class TicketCachedKeyFrame(
  val epoch: Long,
  val sequence: Long,
  val envelope: ByteArray,
  val cachedAtMillis: Long,
  val timestampUs: Long
)

private data class TicketSessionStartSafetyPreflight(
  val portrait: PhonePortraitLock.EnsureResult,
  val portraitDurationMillis: Long,
  val secureCapture: TicketSecureWindowCaptureBypassEnsureResult,
  val secureCaptureDurationMillis: Long,
  val totalDurationMillis: Long,
  val armHandle: TicketHardwareH264ArmHandle? = null
)

private data class TicketSessionStartPreflightHealth(
  val outcome: String = "not_run",
  val totalMillis: Long? = null,
  val portraitMillis: Long? = null,
  val secureCaptureMillis: Long? = null,
  val completedAtMillis: Long = 0L
)

private data class TicketVideoConfigSnapshot(
  val epoch: Long,
  val width: Int,
  val height: Int,
  val message: String
)

class TicketStreamService : Service() {
  private data class TicketClientInfo(
    val video: Boolean,
    val viewerId: String?,
    val pageId: String?,
    val pageVersion: String?,
    val startupTraceCorrelationId: String,
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

  private data class ControlCodeFastPreflight(
    val ready: Boolean,
    val ticketDetailHierarchy: String? = null
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

  /**
   * Owns request-lifetime soft-keyboard suppression without disabling an IME or registering a
   * hardware keyboard. The critical field tap is gated on the Accessibility controller's verified
   * hidden mode, so control-code entry cannot trigger an Android display-configuration change.
   */
  private class RequestScopedKeyboardClampLease(
    private val requestId: String,
    private val expectedPackageName: String,
    private val commandTimeoutMillis: Long,
    private val onPhase: (String) -> Unit,
    private val onEvent: (String, String) -> Unit
  ) {
    private val releaseMutex = Mutex()
    @Volatile private var suppressionMayBeOwned: Boolean = false
    @Volatile private var acquired: Boolean = false
    @Volatile private var released: Boolean = false

    suspend fun acquire(): Boolean = releaseMutex.withLock {
      if (released) {
        return false
      }
      if (acquired) {
        return PhoneAutomationServiceBridge.isViviControlCodeKeyboardModeSuppressed(
          expectedPackageName
        )
      }
      val startedAtMillis = SystemClock.elapsedRealtime()
      onPhase("keyboard_clamp_requested")
      val applied = runCatching {
        val connected = PhoneAutomationServiceBridge.awaitAccessibilityConnection(
          commandTimeoutMillis
        )
        if (!connected) {
          false
        } else {
          // From this call boundary onward, a failed Boolean may still mean HIDDEN was applied but
          // its inline rollback could not be proved. Finalization must therefore verify restore.
          suppressionMayBeOwned = true
          PhoneAutomationServiceBridge.suppressViviControlCodeKeyboardMode(expectedPackageName)
        }
      }.getOrElse { error ->
        onEvent(
          "keyboard_clamp_apply_failed",
          "request=$requestId error=${error::class.java.simpleName}"
        )
        false
      }
      if (!applied) {
        onEvent(
          "keyboard_clamp_apply_failed",
          "request=$requestId authority=accessibility_soft_keyboard unavailable=true"
        )
        return false
      }
      acquired = true
      onPhase("keyboard_clamp_applied")
      onEvent(
        "keyboard_clamp_applied",
        "request=$requestId authority=accessibility_soft_keyboard " +
          "duration_ms=${SystemClock.elapsedRealtime() - startedAtMillis}"
      )
      true
    }

    /** Revalidates the exact owned mode at the last safe point before the field can be touched. */
    suspend fun awaitApplied(): Boolean {
      val ready = !released && acquired && runCatching {
        PhoneAutomationServiceBridge.isViviControlCodeKeyboardModeSuppressed(expectedPackageName)
      }.getOrDefault(false)
      if (!ready) {
        onEvent(
          "keyboard_clamp_gate_failed",
          "request=$requestId owned_hidden_mode_not_confirmed"
        )
      }
      return ready
    }

    private suspend fun restoreMode(reason: String): Boolean {
      var restored = false
      var lastError = ""
      for (attempt in 0 until 2) {
        restored = runCatching {
          PhoneAutomationServiceBridge.restoreViviControlCodeKeyboardMode(expectedPackageName)
        }.getOrElse { error ->
          lastError = error::class.java.simpleName
          false
        }
        if (restored) {
          break
        }
        if (attempt == 0) {
          delay(40L)
        }
      }
      if (!restored) {
        onPhase("keyboard_clamp_restore_failed")
        onEvent(
          "keyboard_clamp_restore_failed",
          "request=$requestId reason=$reason authority=accessibility_soft_keyboard " +
            "error=${lastError.ifBlank { "unavailable" }}"
        )
      } else {
        onEvent(
          "keyboard_clamp_restored",
          "request=$requestId reason=$reason authority=accessibility_soft_keyboard"
        )
      }
      return restored
    }

    suspend fun release(reason: String): Boolean = releaseMutex.withLock {
      if (released) {
        return true
      }
      val restored = if (acquired || suppressionMayBeOwned) {
        restoreMode(reason)
      } else {
        onEvent("keyboard_clamp_release_noop", "request=$requestId reason=$reason")
        true
      }
      if (restored) {
        suppressionMayBeOwned = false
        acquired = false
        released = true
        onPhase("keyboard_clamp_released")
      }
      restored
    }
  }

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
    val error: String = "",
    val visual: TicketVisualActionObservation? = null
  )

  private data class TicketAutopilotResult(
    val success: Boolean,
    val state: TicketViviRecoveryState,
    val step: String,
    val finalActionCategory: String = "none",
    val finalActionOutcome: String = "not_attempted",
    val finalHierarchy: String = "",
    val finalSliderBounds: TicketViviGraphicBounds? = null
  )

  private data class TicketActivationDispatchPreparation(
    val observation: TicketVisualActionObservation,
    val gestureBounds: TicketViviGraphicBounds,
    val watermark: Pair<Long, Long>,
    val inputFence: PhoneAutomationTicketInputFence,
    val captureRestartCount: Long,
    val actionMutationGeneration: Long
  )

  private data class TicketActivationPreparationResult(
    val prepared: TicketActivationDispatchPreparation? = null,
    val reason: String = "ticket_action_visual_unproved"
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

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
  private val secureCaptureStartupReconciled = CompletableDeferred<Boolean>()
  @Volatile private var secureCaptureStartupReady: Boolean = false
  @Volatile private var serviceLifecycleStopping: Boolean = false
  private val streamStartAdmission = TicketStreamStartAdmission()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val rootExecutor = TicketRootCommandWorker()
  private val secureWindowCaptureBypassFallbackRootExecutor = TicketRootCommandWorker()
  private val secureWindowCaptureBypassOwner = TicketSecureWindowCaptureBypassOwner(
    primaryRootExecutor = rootExecutor,
    fallbackRootExecutor = secureWindowCaptureBypassFallbackRootExecutor,
    onEvent = { event, detail -> recordTicketEvent(event, detail) }
  )
  private val inputRootExecutor = TicketRootCommandWorker()
  private val controlSurfaceCloseRootExecutor = SuRootExecutor()
  private val recoveryInputRootExecutor = SuRootExecutor()
  // Keep the dedicated root transport alive for the full service lifetime. Magisk tears down
  // descendants of a short-lived app-owned `su -c` session even after nohup/setsid, which can
  // remove the exact panel-dark helper between launch acknowledgement and readiness proof.
  private val ticketActionPanelDarkRootExecutor = TicketRootCommandWorker()
  private val ticketActionPanelDarkVerifyRootExecutor = TicketRootCommandWorker()
  private val wakeRootExecutor = TicketRootCommandWorker()
  private val foregroundRootExecutor = TicketRootCommandWorker()
  private var ticketSpacetimeWorker: TicketSpacetimeWorker? = null
  private val ticketSpacetimePhoneOutbox = TicketSpacetimePhoneOutbox(
    maxLossyMessages = TICKET_SPACETIME_PHONE_MESSAGE_LIMIT,
    maxCriticalMessages = TICKET_SPACETIME_PHONE_MESSAGE_LIMIT,
    criticalTtlMillis = TICKET_SPACETIME_CRITICAL_MESSAGE_TTL_MILLIS,
    criticalKey = ::ticketSpacetimeCriticalMessageKey,
    criticalReplacement = ::ticketSpacetimeCriticalMessageReplacement,
    nowMillis = SystemClock::elapsedRealtime
  )
  private val ticketActivationCheckpointStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    TicketActivationCheckpointStore(applicationContext)
  }
  private val startupTracePhaseLock = Any()
  private val startupTraceOncePhases = mutableSetOf<String>()
  private val startupTraceCorrelation = TicketStartupTraceCorrelation()
  @Volatile private var lastStartupPreflight = TicketSessionStartPreflightHealth()
  private val serverMutex = Mutex()
  private val controlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val protectedControlClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val canceledRigasSatiksmeBatchIds = Collections.synchronizedSet(mutableSetOf<String>())
  private val videoClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val clientInfo = mutableMapOf<TicketWebSocket, TicketClientInfo>()
  private val videoSendStates = TicketVideoClientDeliveryRegistry<TicketWebSocket>()
  private val encoderLock = Any()
  private val sessionMutex = Mutex()

  private val controlCodePhoneMutationLane = ControlCodePhoneMutationLane()
  @Volatile private var lastTicketRegistrationProof: TicketRegistrationProof? = null
  @Volatile private var currentTicketRegistrationProof: TicketRegistrationProof? = null
  @Volatile private var ticketVisualActionJobOwnershipActive: Boolean = false
  @Volatile private var ticketVisualActionCaptureLeaseActive: Boolean = false
  @Volatile private var viviReauthCaptureLeaseActive: Boolean = false
  @Volatile private var ticketActionV3CleanupCheckpointRecoveryActive: Boolean = false
  @Volatile private var ticketActionPanelDarkLeaseSnapshot = TicketActionPanelDarkLeaseSnapshot()
  private val ticketActionPanelDarkLeaseFailures = AtomicLong(0L)
  @Volatile private var activeTicketActionPanelDarkLease: TicketActionPanelDarkLease? = null
  @Volatile private var viviReauthSnapshot = TicketViviReauthSnapshot()
  @Volatile private var viviReauthCompletedAtMillis: Long = 0L
  private val controlCodeBrowserCaptureLock = Object()
  private val running = AtomicBoolean(false)
  private val pendingRootHardwareH264KeyFrame = TicketPendingKeyFrameRequest()
  private val rootHardwareH264CaptureEngine = TicketRootHardwareH264CaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootHardwareH264CaptureFrame,
    onStateChanged = { health ->
      handleRootHardwareH264CaptureStateChanged(health)
    },
    pendingKeyFrameRequest = pendingRootHardwareH264KeyFrame
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
  private val rootHardwareH264CapturePreparationLock = Any()
  private var rootHardwareH264CapturePreparationJob: Job? = null
  private val rootHardwareH264StartupReadinessLock = Any()
  private var rootHardwareH264StartupReadinessJob: Job? = null
  private var postRemoteTapForegroundCheckJob: Job? = null
  private var controlExitCleanupJob: Job? = null
  @Volatile private var activeControlCodeKeyboardClamp: RequestScopedKeyboardClampLease? = null
  @Volatile private var activeControlCodeGeneratedVisualSignature: String = ""
  @Volatile private var activeControlCodeVisualSignatureEpoch: String = ""
  @Volatile private var activeControlCodeRawDetailAnchor: String = ""
  @Volatile private var activeControlCodeRawDetailState: TicketVisualPhoneState = TicketVisualPhoneState.UNKNOWN
  @Volatile private var activeControlCodeVisualResultMode: TicketControlCodeVisualResultMode =
    TicketControlCodeVisualResultMode.NONE
  @Volatile private var activeControlCodeVisualSignatureExpiresAtMillis: Long = 0L
  @Volatile private var controlCodeSignatureCleanupRequired: Boolean = false
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
  @Volatile private var startupDisconnectGraceUntilMillis: Long = 0L
  @Volatile private var ticketSessionState: String = TICKET_SESSION_IDLE
  @Volatile private var ticketSessionStateChangedAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var ticketSessionStateReason: String = "init"
  @Volatile private var streamActive: Boolean = false
  @Volatile private var ticketSessionGeneration: Long = 0L
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
  @Volatile private var controlCodeResultEncoderRefreshActive: Boolean = false
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
  @Volatile private var lastVideoClientConnectedAtMillis: Long = 0L
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
  @Volatile private var latestKeyFrame: TicketCachedKeyFrame? = null
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
  @Volatile private var lastControlCodeFastReadyStreamEpoch: Long = 0L
  @Volatile private var lastControlCodeCommandOwner: String? = null
  @Volatile private var lastControlCodeCommandApp: String? = null
  @Volatile private var lastControlCodeCommandFlow: String? = null
  private val ticketActionV3Lock = Any()
  @Volatile private var ticketActionV3Snapshot = TicketVisualActionSnapshot()
  @Volatile private var ticketActionV3Generation: Long = 0L
  /** Advances before every mutating V3 action so read-only visual proofs cannot cross a mutation. */
  @Volatile private var ticketActionV3MutationGeneration: Long = 0L
  private var ticketActionV3Job: Job? = null
  @Volatile private var deferredTicketVisualTerminalActionId: String = ""
  @Volatile private var deferredTicketVisualTerminalObservation: TicketVisualActionObservation? = null
  @Volatile private var ticketVisualSwitchAnchors = TicketVisualSwitchAnchors()
  @Volatile private var ticketVisualSwitchAnchorsLoaded = false
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

  /**
   * Resolve the rooted H.264 helper once as the service comes up. A cold
   * control-code request can then wait for this same job instead of racing the
   * helper install/classpath probe or starting an encoder that cannot produce
   * frames yet.
   */
  private fun startRootHardwareH264StartupReadiness() {
    synchronized(rootHardwareH264StartupReadinessLock) {
      val existing = rootHardwareH264StartupReadinessJob
      if (existing != null && !existing.isCompleted) {
        return
      }
      rootHardwareH264StartupReadinessJob = serviceScope.launch(Dispatchers.IO) {
        val startedAtMillis = SystemClock.elapsedRealtime()
        recordTicketEvent("root_hardware_h264_startup_readiness_started", "source=service_create")
        val cleanupStartedAtMillis = SystemClock.elapsedRealtime()
        rootHardwareH264CaptureEngine.cleanupStaleProcesses()
        val cleanupMillis = (SystemClock.elapsedRealtime() - cleanupStartedAtMillis).coerceAtLeast(0L)
        val probeStartedAtMillis = SystemClock.elapsedRealtime()
        val probeOk = rootHardwareH264CaptureEngine.probe()
        val probeMillis = (SystemClock.elapsedRealtime() - probeStartedAtMillis).coerceAtLeast(0L)
        val health = rootHardwareH264CaptureEngine.snapshot()
        recordTicketEvent(
          "root_hardware_h264_startup_readiness_completed",
          "ok=$probeOk available=${health.available} helper=${health.captureHelperState} cleanup_ms=$cleanupMillis probe_ms=$probeMillis total_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)}"
        )
      }
    }
  }

  private suspend fun awaitRootHardwareH264StartupReadiness(reason: String): TicketHardwareH264Health {
    val existingJob = synchronized(rootHardwareH264StartupReadinessLock) {
      rootHardwareH264StartupReadinessJob
    }
    if (existingJob == null) {
      startRootHardwareH264StartupReadiness()
    }
    val waitJob = synchronized(rootHardwareH264StartupReadinessLock) {
      rootHardwareH264StartupReadinessJob
    }
    val startedAtMillis = SystemClock.elapsedRealtime()
    waitJob?.join()
    var health = rootHardwareH264CaptureEngine.snapshot()
    var retried = false
    if (!health.available) {
      // A failed service-start probe is not a readiness result. Retry through the
      // same serialized job so a viewer arriving during the first probe cannot
      // launch a competing cleanup/probe pair, while a later start can recover
      // from a transient root/APK failure.
      retried = true
      startRootHardwareH264StartupReadiness()
      val retryJob = synchronized(rootHardwareH264StartupReadinessLock) {
        rootHardwareH264StartupReadinessJob
      }
      if (retryJob !== waitJob) {
        retryJob?.join()
      }
      health = rootHardwareH264CaptureEngine.snapshot()
    }
    recordTicketEvent(
      if (reason == "control_code_request") {
        "control_code_root_capture_readiness_wait"
      } else {
        "root_hardware_h264_startup_readiness_wait"
      },
      "reason=$reason wait_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)} retry=$retried available=${health.available} state=${health.state} helper=${health.captureHelperState}"
    )
    return health
  }

  private suspend fun awaitRootHardwareH264StartupReadinessForControlCodeRequest(): TicketHardwareH264Health {
    return awaitRootHardwareH264StartupReadiness("control_code_request")
  }

  override fun onCreate() {
    super.onCreate()
    controlCodeSignatureCleanupRequired = applicationContext.getSharedPreferences(
      CONTROL_CODE_VISUAL_CHECKPOINT_PREFERENCES,
      Context.MODE_PRIVATE
    ).getBoolean(CONTROL_CODE_VISUAL_CHECKPOINT_PENDING_KEY, false)
    serviceScope.launch(Dispatchers.IO) {
      val startupRelease = disableSecureWindowCaptureBypass("service_startup_reconcile")
      secureCaptureStartupReady = startupRelease.ok
      secureCaptureStartupReconciled.complete(startupRelease.ok)
      if (!startupRelease.ok) {
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_startup_reconcile_unproved")
        lastMessage = "Secure ViVi capture cleanup could not be proved during startup"
        recordTicketEvent(
          "secure_window_capture_bypass_startup_reconcile_failed",
          startupRelease.detail
        )
        return@launch
      }
      currentCoroutineContext().ensureActive()
      if (serviceLifecycleStopping) return@launch
      // Protected-pixel helper readiness and durable command subscriptions may begin only after
      // any ownership left by a prior process has been exactly restored and cleared.
      startRootHardwareH264StartupReadiness()
      val staleClampCleanup = ticketActionPanelDarkVerifyRootExecutor.runScript(
        TicketActionPanelDarkLease.CLEANUP_STALE_HELPERS_SCRIPT,
        2.seconds
      )
      if (!staleClampCleanup.ok) {
        ticketActionPanelDarkLeaseFailures.incrementAndGet()
        recordTicketEvent("ticket_action_panel_dark_stale_cleanup_failed", "startup")
        return@launch
      } else if (staleClampCleanup.stdout.lineSequence().none { it.trim() == "cleaned=0" }) {
        recordTicketEvent("ticket_action_panel_dark_stale_helper_cleaned", "startup")
      }
      currentCoroutineContext().ensureActive()
      if (serviceLifecycleStopping) return@launch
      // Do not subscribe to durable actions until the prior-process helper sweep is complete.
      // Otherwise a freshly admitted action could create a current helper while the sweep is
      // still scanning and have that helper mistaken for an orphan.
      ticketSpacetimeWorker = TicketSpacetimeWorker(
        scope = serviceScope,
        rootExecutor = inputRootExecutor,
        service = this@TicketStreamService
      ).also { worker ->
        worker.start()
      }
      inputRootExecutor.runScript(TicketUiautomatorDump.startupSweepCommand())
      PhonePortraitLock.force(inputRootExecutor)
      if (!PhonePortraitLock.verify(inputRootExecutor)) {
        recordTicketEvent("phone_portrait_lock_unverified", "startup")
      }
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      TicketScreenConfig.ACTION_STOP_SERVER -> {
        serviceLifecycleStopping = true
        secureCaptureStartupReady = false
        secureCaptureStartupReconciled.complete(false)
        secureWindowCaptureBypassOwner.stopAcceptingNewOwnership()
        streamActive = false
        hardwareCaptureVerified = false
        hardwareFrameBroadcastAllowed = false
        activeCaptureMode = CAPTURE_MODE_IDLE
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
    serviceLifecycleStopping = true
    secureCaptureStartupReady = false
    secureCaptureStartupReconciled.complete(false)
    secureWindowCaptureBypassOwner.stopAcceptingNewOwnership()
    // Fence all stream/session admission before cancelling children. The final owner release
    // below therefore runs only after the last already-admitted settings mutation has quiesced.
    streamActive = false
    hardwareCaptureVerified = false
    hardwareFrameBroadcastAllowed = false
    activeCaptureMode = CAPTURE_MODE_IDLE
    val serviceEnabled = runCatching { TicketServicePreferencesStore(this).load().enabled }.getOrDefault(false)
    brightnessGuardJob?.cancel()
    brightnessGuardJob = null
    ticketBrightnessGuardActive = false
    clientDisconnectStopJob?.cancel()
    clientDisconnectStopJob = null
    ticketSpacetimeWorker?.stop()
    ticketSpacetimeWorker = null
    rootH264BlankProbeJob?.cancel()
    rootH264BlankProbeJob = null
    synchronized(rootHardwareH264StartupReadinessLock) {
      rootHardwareH264StartupReadinessJob?.cancel()
      rootHardwareH264StartupReadinessJob = null
    }
    cancelRootHardwareH264CapturePreparation("service_destroyed")
    cancelInactivityTimer()
    cancelForegroundGuard()
    postRemoteTapForegroundCheckJob?.cancel()
    postRemoteTapForegroundCheckJob = null
    controlExitCleanupJob?.cancel()
    controlExitCleanupJob = null
    ticketActionV3Job?.cancel()
    cancelPendingRigasSatiksmeReturnCleanup("service_destroyed")
    cancelTicketRecovery("service_destroyed")
    serviceJob.cancel()
    val serviceChildrenQuiesced = runCatching {
      runBlocking {
        withContext(NonCancellable) {
          withTimeoutOrNull(SERVICE_DESTROY_JOIN_TIMEOUT_MILLIS) {
            serviceJob.join()
            true
          } ?: false
        }
      }
    }.getOrDefault(false)
    ticketActionV3Job = null
    if (!serviceChildrenQuiesced) {
      recordTicketEvent("ticket_service_destroy_join_timeout", "secure_capture_admission_fenced=true")
    }
    activeControlCodeKeyboardClamp?.let { clamp ->
      val keyboardClampReleased = runCatching {
        runBlocking {
          withContext(NonCancellable) {
            clamp.release("service_destroyed")
          }
        }
      }.getOrDefault(false)
      if (keyboardClampReleased) {
        activeControlCodeKeyboardClamp = null
      } else {
        preserveControlCodeCleanupCheckpoint("service_destroy_keyboard_restore_unproved")
        recordTicketEvent(
          "keyboard_clamp_restore_failed",
          "reason=service_destroyed durable_cleanup_checkpoint_retained=true"
        )
      }
    }
    activeTicketActionPanelDarkLease?.let { lease ->
      runCatching {
        runBlocking {
          withContext(NonCancellable) {
            lease.release("service_destroyed")
          }
        }
      }
      activeTicketActionPanelDarkLease = null
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
    runCatching { runBlocking { rootHardwareH264CaptureEngine.stopAndJoin("service_destroyed") } }
    closeAllClients("service_destroyed")
    val finalBypassRelease = runCatching {
      runBlocking {
        withContext(NonCancellable) {
          disableSecureWindowCaptureBypass("service_destroyed_final")
        }
      }
    }.getOrNull()
    if (finalBypassRelease?.ok != true) {
      recordTicketEvent(
        "service_destroy_secure_capture_cleanup_unproved",
        finalBypassRelease?.detail ?: "release_exception"
      )
    }
    runCatching { runBlocking { disableNotificationLockdown("service_destroyed") } }
    releaseTicketScreenAwake()
    stopLocalServer()
    inputRootExecutor.close()
    ticketActionPanelDarkRootExecutor.close()
    ticketActionPanelDarkVerifyRootExecutor.close()
    wakeRootExecutor.close()
    foregroundRootExecutor.close()
    secureWindowCaptureBypassFallbackRootExecutor.close()
    rootExecutor.close()
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
      startupTraceCorrelationId = boundedStartupTraceCorrelationId(headers["x-ticket-startup-trace"].orEmpty()),
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
          recordTicketEventForTrace(
            "stream_client_closed",
            streamClientTraceDetail(info, "closed"),
            info.startupTraceCorrelationId
          )
          startupTraceCorrelation.releaseVideoSocket(info.generation)
        } else {
          controlClients.remove(client)
          protectedControlClients.remove(client)
        }
        synchronized(clientInfo) {
          clientInfo.remove(client)
        }
        videoSendStates.remove(client)?.close()
        serviceScope.launch {
          sessionMutex.withLock {
            if (totalClientCount() == 0) {
              scheduleClientDisconnectGraceLocked()
            }
          }
        }
      },
      binaryFramesInitiallyAllowed = !video
    )
    extendStartupDisconnectGrace()
    var acceptedClientGeneration = true
    sessionMutex.withLock {
      if (video) {
        acceptedClientGeneration = bindStartupTraceCorrelationIdFromVideoSocket(
          info.startupTraceCorrelationId,
          info.generation
        )
        if (!acceptedClientGeneration) return@withLock
      }
      closeDuplicateViewerClients(info)
      clientDisconnectStopJob?.cancel()
      clientDisconnectStopJob = null
      if (video) {
        videoSendStates.add(client, newVideoClientDeliveryState())
        videoClients.add(client)
        recordTicketEventForCurrentVideoSocket(
          "stream_client_opened",
          streamClientTraceDetail(info, "opened"),
          info
        )
        lastVideoClientConnectedAtMillis = SystemClock.elapsedRealtime()
      } else {
        controlClients.add(client)
      }
      synchronized(clientInfo) {
        clientInfo[client] = info
      }
      markViewerInput("client_connected")
    }
    if (!acceptedClientGeneration) {
      client.close()
      return
    }
    if (video && !startTicketSessionForVideoClientOpen(info)) {
      return
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

  private suspend fun startTicketSessionForVideoClientOpen(info: TicketClientInfo): Boolean {
    val traceId = boundedStartupTraceCorrelationId(info.startupTraceCorrelationId)
    var decision = "pending"
    val response = startTicketSession(
      lockedStartDecision = {
        startupTraceCorrelation.resolveVideoSocketStart(
          value = traceId,
          generation = info.generation,
          superseded = {
            decision = "superseded"
            TicketSessionResponse(
              ok = true,
              state = ticketSessionState,
              message = "Video socket was superseded before startup"
            )
          },
          current = {
            when {
              streamActive -> {
                decision = "coalesced"
                TicketSessionResponse(ok = true, state = ticketSessionState, message = lastMessage)
              }
              controlCodeRequestActive() -> {
                decision = "control_code_deferred"
                recordTicketEventForTrace(
                  "stream_client_start_deferred_for_control_code",
                  streamClientTraceDetail(info, "control_code_active"),
                  info.startupTraceCorrelationId
                )
                TicketSessionResponse(ok = false, state = ticketSessionState, message = "Control-code work owns the phone")
              }
              else -> {
                decision = "started"
                recordTicketEventForCurrentVideoSocket(
                  "stream_client_immediate_start",
                  streamClientTraceDetail(info, "video_socket_open"),
                  info
                )
                null
              }
            }
          }
        )
      }
    )
    when (decision) {
      "started" -> recordTicketEventForCurrentVideoSocket(
        "stream_client_immediate_start_result",
        "ok=${response.ok} generation=${info.generation}",
        info
      )
      "coalesced" -> recordTicketEventForCurrentVideoSocket(
        "stream_client_immediate_start_coalesced",
        "generation=${info.generation} state=${response.state}",
        info
      )
    }
    return decision != "superseded"
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
          if (controlCodeRequestActive()) {
            recordTicketEvent("spacetime_start_coalesced_control_code", reason)
            TicketSpacetimeCommandResult(
              ok = true,
              reason = "control_code_request_owns_start",
              streamState = ticketSpacetimeStreamState()
            )
          } else if (ticketSpacetimeBackgroundStreamAlreadyHealthy()) {
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
          requestKeyFrame(reason.ifBlank { "spacetime_keyframe" })
          TicketSpacetimeCommandResult(ok = true, reason = "keyframe_requested", streamState = ticketSpacetimeStreamState())
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
        "ticket_action_v3" -> {
          val request = payload?.let(::parseTicketVisualActionRequest)?.copy(
            commandId = command.id,
            commandRevision = command.revision
          )
            ?: return TicketSpacetimeCommandResult(
              ok = false,
              reason = "ticket_action_v3_payload_invalid",
              streamState = ticketSpacetimeStreamState()
            )
          handleTicketVisualActionV3(command, request)
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
              queueHint = payload?.jsonObjectValue("rsQueueHint")?.let { hint ->
                RigasSatiksmeQueueHint(
                  pendingAfterThis = hint["pendingAfterThis"]?.jsonPrimitive?.intOrNull ?: 0,
                  ticketPriorityActive = hint["ticketPriorityActive"]?.jsonPrimitive?.booleanOrNull == true
                )
              } ?: RigasSatiksmeQueueHint(),
              fastRevision = payload?.stringValue("fastRevision").orEmpty(),
              serverSentAt = payload?.stringValue("serverSentAt").orEmpty().ifBlank { command.createdAt }
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
      val publicReason = if (command.commandType == "ticket_action_v3") {
        "ticket_action_v3_internal_failure"
      } else {
        safeErrorDetail(error).ifBlank { "command_failed" }
      }
      TicketSpacetimeCommandResult(ok = false, reason = publicReason, streamState = ticketSpacetimeStreamState())
    }
  }

  internal suspend fun handleTicketSpacetimeViviReauthCommand(
    command: TicketSpacetimeCommand,
    credentials: TicketSpacetimeViviCredentials?
  ): TicketSpacetimeCommandResult {
    val request = ticketViviReauthRequest(command)
      ?: return viviReauthCommandResult(
        TicketViviReauthSnapshot(
          status = "failed",
          phase = "complete",
          reason = "internal_failure",
          terminal = true
        )
      )
    reconcileTicketSpacetimeViviReauthCommand(command)?.let { return it }
    if (credentials == null || !credentials.valid) {
      return terminalViviReauth(request, "failed", "credential_missing")
    }
    if (credentials.revision != request.credentialRevision) {
      return terminalViviReauth(request, "failed", "credential_revision_stale")
    }
    val prior = loadViviReauthJournal()
    if (prior.requestId.isNotBlank() && !prior.terminal) {
      val status = if (prior.mutationMayHaveDispatched) "needs_attention" else "failed"
      return terminalViviReauth(request, status, "visual_proof_failed")
    }
    return controlCodePhoneMutationLane.withOwnership {
      runViviReauth(request, credentials)
    }.let(::viviReauthCommandResult)
  }

  /**
   * Reconciles only durable phone-side evidence for this exact request. The
   * worker calls this before the database claim so a restart can publish a
   * retained terminal result or deterministic uncertainty without fetching a
   * credential or repeating any physical work.
   */
  internal fun reconcileTicketSpacetimeViviReauthCommand(
    command: TicketSpacetimeCommand
  ): TicketSpacetimeCommandResult? {
    val request = ticketViviReauthRequest(command) ?: return null
    val journal = loadViviReauthJournal()
    if (ticketViviReauthRequestIdHasSemanticMismatch(journal, request)) {
      return viviReauthCommandResult(
        reauthSnapshot(request, "failed", "complete", "internal_failure")
      )
    }
    retainedTicketViviReauthSnapshot(journal, request)?.let { retained ->
      viviReauthSnapshot = retained
      return viviReauthCommandResult(retained)
    }
    if (ticketViviReauthJournalMatchesRequest(journal, request) &&
      !journal.terminal && journal.requestId.isNotBlank()
    ) {
      val status = if (journal.mutationMayHaveDispatched) "needs_attention" else "failed"
      return terminalViviReauth(request, status, "visual_proof_failed")
    }
    return null
  }

  private suspend fun runViviReauth(
    request: TicketViviReauthRequest,
    credentials: TicketSpacetimeViviCredentials
  ): TicketViviReauthSnapshot {
    val panelLease = TicketActionPanelDarkLease(
      actionId = "vivi_reauth:${request.requestId}",
      scope = serviceScope,
      clampRootExecutor = ticketActionPanelDarkRootExecutor,
      verifyRootExecutor = ticketActionPanelDarkVerifyRootExecutor,
      physicalTouchState = PhoneAutomationServiceBridge::currentRootPhysicalTouchState,
      ownerProcessId = android.os.Process.myPid(),
      onSnapshotChanged = { snapshot ->
        val prior = ticketActionPanelDarkLeaseSnapshot
        if (snapshot.failure.isNotBlank() &&
          (prior.ownerActionId != snapshot.ownerActionId || prior.failure.isBlank())
        ) ticketActionPanelDarkLeaseFailures.incrementAndGet()
        ticketActionPanelDarkLeaseSnapshot = snapshot
        broadcastStatus()
      }
    )
    activeTicketActionPanelDarkLease = panelLease
    var acquired = false
    var commandStartedProofSessionGeneration: Long? = null
    var provisional = reauthSnapshot(request, "failed", "complete", "internal_failure")
    var finalization: TicketActionPanelDarkLeaseFinalization? = null
    sessionMutex.withLock {
      viviReauthCaptureLeaseActive = true
    }
    try {
      acquired = panelLease.acquire()
      provisional = if (!acquired) {
        reauthSnapshot(request, "failed", "complete", "visual_proof_failed")
      } else {
        executeViviReauthAttempt(
          request,
          credentials,
          panelLease,
          onNewSessionAdmitted = { generation ->
            commandStartedProofSessionGeneration = generation
          }
        )
      }
    } finally {
      withContext(NonCancellable) {
        finalization = if (acquired) {
          panelLease.releaseAfterFinalConvergence("vivi_reauth_terminal")
        } else {
          panelLease.release("vivi_reauth_acquire_failed")
        }
        if (activeTicketActionPanelDarkLease === panelLease) activeTicketActionPanelDarkLease = null
        PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(
          "ticket:vivi_reauth:${request.requestId.takeLast(24)}:released"
        )
        sessionMutex.withLock {
          viviReauthCaptureLeaseActive = false
          commandStartedProofSessionGeneration?.let { generation ->
            scheduleCommandStartedProofStreamCleanupLocked(
              expectedSessionGeneration = generation,
              reason = "vivi_reauth_proof_stream_idle"
            )
          }
        }
      }
    }
    val safe = finalization?.safe == true
    val terminal = if (provisional.ok && !safe) {
      reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    } else {
      provisional
    }
    return persistViviReauthTerminal(terminal)
  }

  private suspend fun executeViviReauthAttempt(
    request: TicketViviReauthRequest,
    credentials: TicketSpacetimeViviCredentials,
    panelLease: TicketActionPanelDarkLease,
    onNewSessionAdmitted: (Long) -> Unit
  ): TicketViviReauthSnapshot {
    updateViviReauthPhase(request, "running", "opening_vivi", "running")
    if (!persistViviReauthPhase(request, "opening_vivi") || !panelLease.beforeMutationAllowed()) {
      return reauthSnapshot(request, "failed", "complete", "visual_proof_failed")
    }
    panelLease.markMutationMayHaveDispatched()
    val preparedViviDuringSessionStart = !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      !hardwareCaptureVerified ||
      !hardwareFrameBroadcastAllowed
    if (preparedViviDuringSessionStart) {
      val started = startTicketSession(
        prepareCaptureWithCurrentPhoneMutationOwnership = true,
        onNewSessionAdmitted = onNewSessionAdmitted
      )
      if (!started.ok || !streamActive ||
        activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
        !hardwareCaptureVerified ||
        !hardwareFrameBroadcastAllowed
      ) {
        return reauthSnapshot(request, "failed", "complete", "visual_proof_failed")
      }
    }
    if (request.resetAppData) {
      updateViviReauthPhase(request, "running", "resetting_vivi", "running")
      if (!persistViviReauthPhase(request, "clear_dispatching") ||
        !panelLease.beforeMutationAllowed()
      ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      panelLease.markMutationMayHaveDispatched()
      val cleared = inputRootExecutor.runScript(
        "pm clear --user 0 ${TicketScreenConfig.VIVI_PACKAGE}",
        VIVI_REAUTH_CLEAR_TIMEOUT_MILLIS.milliseconds
      )
      recordTicketEvent(
        "vivi_reauth_package_clear",
        "ok=${cleared.ok} duration_ms=${cleared.durationMs} exit_code=${cleared.exitCode}"
      )
      if (!cleared.ok || !cleared.stdout.lineSequence().any { it.trim() == "Success" }) {
        return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      }
      updateViviReauthPhase(request, "running", "opening_vivi", "running")
      if (!persistViviReauthPhase(request, "opening_vivi") ||
        !panelLease.beforeMutationAllowed()
      ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      panelLease.markMutationMayHaveDispatched()
      launchVivi()
    } else if (!preparedViviDuringSessionStart) {
      launchVivi()
    }
    ensureRootHardwareH264CaptureIfPossible()
    rootHardwareH264CaptureEngine.requestImmediateRefresh("vivi_reauth_open")

    updateViviReauthPhase(request, "running", "detecting_login", "running")
    var login = awaitStableTicketVisualActionObservation(
      reason = "vivi_reauth_login",
      timeoutMillis = VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS,
      // v3 alone admits two agreeing UNKNOWN primary states so its independent, exact four-tab
      // side channel can prove an otherwise unrecognized Tickets body. The route policy below
      // still rejects UNKNOWN without a tab and every explicit login or blocker state.
      allowUnknown = request.logoutInApp
    )
    // v1/v2 retain their legacy prove-or-login behavior. v3 is intentionally different: a
    // currently signed-in surface must first traverse the in-app logout route so the configured
    // credentials, rather than an existing ViVi session, are the cause of terminal success.
    if (!request.logoutInApp && ticketViviReauthPreflightAuthenticatedObservation(login)) {
      return provenSignedInViviReauthSnapshot(request)
    }
    if (!PhoneAutomationServiceBridge.awaitAccessibilityConnection(
        VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
      )
    ) {
      return reauthSnapshot(request, "needs_attention", "complete", "login_fields_not_detected")
    }
    var loginSurface = PhoneAutomationServiceBridge.classifyViviAuthSurface(
      TicketScreenConfig.VIVI_PACKAGE
    )
    if (!request.logoutInApp && ticketViviSafeReauthShouldRefreshInitialDeviceLink(
        request.resetAppData,
        loginSurface
      )
    ) {
      updateViviReauthPhase(request, "running", "refreshing_device_link", "running")
      if (!persistViviReauthPhase(request, "device_link_force_stop_dispatching") ||
        !panelLease.beforeMutationAllowed()
      ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      panelLease.markMutationMayHaveDispatched()
      val stopped = inputRootExecutor.runScript(
        "am force-stop --user 0 ${TicketScreenConfig.VIVI_PACKAGE}",
        VIVI_REAUTH_FORCE_STOP_TIMEOUT_MILLIS.milliseconds
      )
      if (!stopped.ok) {
        return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      }
      if (!persistViviReauthPhase(request, "device_link_relaunch_dispatching") ||
        !panelLease.beforeMutationAllowed()
      ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      panelLease.markMutationMayHaveDispatched()
      launchVivi()
      rootHardwareH264CaptureEngine.requestImmediateRefresh("vivi_reauth_device_link_refresh")
      updateViviReauthPhase(request, "running", "detecting_login", "running")
      login = awaitStableTicketVisualActionObservation(
        reason = "vivi_reauth_device_link_refresh",
        timeoutMillis = VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS,
        allowUnknown = false
      )
      if (!request.logoutInApp && ticketViviReauthPreflightAuthenticatedObservation(login)) {
        return provenSignedInViviReauthSnapshot(request)
      }
      loginSurface = PhoneAutomationServiceBridge.classifyViviAuthSurface(
        TicketScreenConfig.VIVI_PACKAGE
      )
    }
    var logoutReturnTarget: TicketViviReauthReturnTarget? = null
    val signedOutByV3Logout = if (request.logoutInApp &&
      ticketViviLogoutLoginStartObservation(login)
    ) {
      logoutReturnTarget = ticketViviReauthReturnTarget(login)
        ?: return reauthSnapshot(request, "failed", "complete", "visual_proof_failed")
      val logoutFailure = performViviInAppLogout(request, login!!, panelLease)
      if (logoutFailure != null) {
        return reauthSnapshot(request, "needs_attention", "complete", logoutFailure)
      }
      true
    } else {
      false
    }
    if (!signedOutByV3Logout) {
      if (login?.state != TicketVisualPhoneState.LOGIN_REQUIRED) {
        return reauthAuthSurfaceResult(request, loginSurface, "login_screen_not_detected")
      }
      if (loginSurface != PhoneAutomationViviAuthSurface.LOGIN) {
        return reauthAuthSurfaceResult(request, loginSurface, "login_fields_not_detected")
      }
    }

    updateViviReauthPhase(request, "running", "entering_credentials", "running")
    if (!persistViviReauthPhase(request, "email_dispatching") ||
      !panelLease.beforeMutationAllowed()
    ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    panelLease.markMutationMayHaveDispatched()
    val emailSet = PhoneAutomationServiceBridge.setViviLoginFieldText(
      TicketScreenConfig.VIVI_PACKAGE,
      PhoneAutomationViviLoginField.EMAIL,
      credentials.email,
      VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
    )
    if (!emailSet) return reauthSnapshot(request, "failed", "complete", "login_fields_not_detected")
    delay(VIVI_REAUTH_FIELD_SETTLE_MILLIS)

    if (!persistViviReauthPhase(request, "password_dispatching") ||
      !panelLease.beforeMutationAllowed()
    ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    panelLease.markMutationMayHaveDispatched()
    val passwordSet = PhoneAutomationServiceBridge.setViviLoginFieldText(
      TicketScreenConfig.VIVI_PACKAGE,
      PhoneAutomationViviLoginField.PASSWORD,
      credentials.password,
      VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
    )
    if (!passwordSet) return reauthSnapshot(request, "failed", "complete", "login_fields_not_detected")
    delay(VIVI_REAUTH_FIELD_SETTLE_MILLIS)

    updateViviReauthPhase(request, "running", "submitting", "running")
    if (!persistViviReauthPhase(request, "submit_dispatching") ||
      !panelLease.beforeMutationAllowed()
    ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    panelLease.markMutationMayHaveDispatched()
    val submitted = PhoneAutomationServiceBridge.submitViviLogin(
      TicketScreenConfig.VIVI_PACKAGE,
      VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
    )
    if (!submitted) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    if (!persistViviReauthPhase(request, "verifying_signed_in")) {
      return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    }
    updateViviReauthPhase(request, "running", "verifying_signed_in", "running")
    return verifyViviReauthResult(request, panelLease, logoutReturnTarget)
  }

  /**
   * Performs the v3 non-destructive route exactly once per mutation boundary:
   * proved bottom tab -> exact Profile tab -> exact account surface -> one scroll -> exact Iziet.
   * The destructive account-delete control is part of the Accessibility rejection contract and
   * can never be returned as a click target.
   */
  private suspend fun performViviInAppLogout(
    request: TicketViviReauthRequest,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): String? {
    updateViviReauthPhase(request, "running", "opening_account_controls", "running")
    var routeObservation = initialObservation
    if (routeObservation.state in setOf(
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        TicketVisualPhoneState.UNACTIVATED_DETAIL
      )
    ) {
      val detailClose = routeObservation.backBounds ?: return "logout_control_not_detected"
      if (!persistViviReauthPhase(request, "detail_close_dispatching") ||
        !panelLease.beforeMutationAllowed()
      ) return "logout_action_uncertain"
      panelLease.markMutationMayHaveDispatched()
      routeObservation = ticketViviReauthDispatchOnceThenProve(
        dispatch = {
          tapTicketVisualProbeBounds(detailClose, "vivi_reauth_close_ticket_detail")
        },
        observeSuccessor = {
          rootHardwareH264CaptureEngine.requestImmediateRefresh("vivi_reauth_detail_closed")
          awaitStableTicketVisualActionObservation(
            reason = "vivi_reauth_detail_closed",
            timeoutMillis = VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS,
            allowUnknown = true
          )
        },
        successorProven = { ticketViviLogoutBottomRouteObservation(it) }
      ) ?: return "logout_action_uncertain"
    }

    val profileSurfaces = setOf(
      PhoneAutomationViviLogoutSurface.PROFILE_LANDING,
      PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_BEFORE_SCROLL,
      PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_AFTER_SCROLL
    )
    var accountSurface: PhoneAutomationViviLogoutSurface
    if (routeObservation.bottomTab != TicketViviBottomTab.PROFILE) {
      if (!persistViviReauthPhase(request, "profile_tab_dispatching") ||
        !panelLease.beforeMutationAllowed()
      ) return "logout_action_uncertain"
      panelLease.markMutationMayHaveDispatched()
      accountSurface = ticketViviReauthDispatchOnceThenProve(
        dispatch = {
          PhoneAutomationServiceBridge.clickViviLogoutRouteTarget(
            TicketScreenConfig.VIVI_PACKAGE,
            PhoneAutomationViviLogoutClickTarget.PROFILE_TAB,
            VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
          )
        },
        observeSuccessor = {
          rootHardwareH264CaptureEngine.requestImmediateRefresh("vivi_reauth_profile_open")
          awaitViviLogoutSurface(profileSurfaces)
        },
        successorProven = profileSurfaces::contains
      ) ?: return "logout_action_uncertain"
    } else {
      accountSurface = awaitViviLogoutSurface(profileSurfaces)
    }

    if (accountSurface == PhoneAutomationViviLogoutSurface.PROFILE_LANDING) {
      if (!awaitViviProfileRouteProof("vivi_reauth_profile_landing") ||
        !persistViviReauthPhase(request, "account_controls_dispatching") ||
        !panelLease.beforeMutationAllowed()
      ) return "logout_action_uncertain"
      panelLease.markMutationMayHaveDispatched()
      val accountDetailSurfaces = setOf(
        PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_BEFORE_SCROLL,
        PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_AFTER_SCROLL
      )
      accountSurface = ticketViviReauthDispatchOnceThenProve(
        dispatch = {
          PhoneAutomationServiceBridge.clickViviLogoutRouteTarget(
            TicketScreenConfig.VIVI_PACKAGE,
            PhoneAutomationViviLogoutClickTarget.ACCOUNT_CONTROLS,
            VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
          )
        },
        observeSuccessor = { awaitViviLogoutSurface(accountDetailSurfaces) },
        successorProven = accountDetailSurfaces::contains
      ) ?: return "logout_action_uncertain"
    }
    if (accountSurface == PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_BEFORE_SCROLL) {
      if (!awaitViviProfileRouteProof("vivi_reauth_account_before_scroll") ||
        !persistViviReauthPhase(request, "account_scroll_dispatching") ||
        !panelLease.beforeMutationAllowed()
      ) return "logout_action_uncertain"
      panelLease.markMutationMayHaveDispatched()
      accountSurface = ticketViviReauthDispatchOnceThenProve(
        dispatch = {
          PhoneAutomationServiceBridge.scrollViviAccountDetailsOnce(
            TicketScreenConfig.VIVI_PACKAGE
          )
        },
        observeSuccessor = {
          awaitViviLogoutSurface(
            setOf(PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_AFTER_SCROLL)
          )
        },
        successorProven = {
          it == PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_AFTER_SCROLL
        }
      ) ?: return "logout_action_uncertain"
    }
    if (accountSurface != PhoneAutomationViviLogoutSurface.ACCOUNT_DETAILS_AFTER_SCROLL) {
      return "logout_control_not_detected"
    }
    if (!awaitViviProfileRouteProof("vivi_reauth_account_after_scroll")) {
      return "logout_control_not_detected"
    }

    updateViviReauthPhase(request, "running", "requesting_logout", "running")
    if (!persistViviReauthPhase(request, "logout_dispatching") ||
      !panelLease.beforeMutationAllowed()
    ) return "logout_action_uncertain"
    panelLease.markMutationMayHaveDispatched()
    PhoneAutomationServiceBridge.clickViviLogoutRouteTarget(
      TicketScreenConfig.VIVI_PACKAGE,
      PhoneAutomationViviLogoutClickTarget.LOGOUT,
      VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
    )
    if (!persistViviReauthPhase(request, "verifying_signed_out")) {
      return "logout_action_uncertain"
    }
    updateViviReauthPhase(request, "running", "verifying_signed_out", "running")
    return if (awaitViviSignedOutProof()) null else "logout_transition_not_proven"
  }

  private suspend fun awaitViviProfileRouteProof(reason: String): Boolean {
    val deadline = SystemClock.elapsedRealtime() + VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      rootHardwareH264CaptureEngine.requestImmediateRefresh(reason)
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val observation = awaitStableTicketVisualActionObservation(
        reason = reason,
        timeoutMillis = minOf(VIVI_REAUTH_VERIFY_SLICE_MILLIS, remaining),
        allowUnknown = true
      )
      if (ticketViviLogoutBottomRouteObservation(observation, TicketViviBottomTab.PROFILE)) {
        return true
      }
      delay(VIVI_REAUTH_LOGOUT_POLL_MILLIS)
    }
    return false
  }

  private suspend fun awaitViviLogoutSurface(
    accepted: Set<PhoneAutomationViviLogoutSurface>
  ): PhoneAutomationViviLogoutSurface {
    val deadline = SystemClock.elapsedRealtime() + VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      val current = PhoneAutomationServiceBridge.classifyViviLogoutSurface(
        TicketScreenConfig.VIVI_PACKAGE
      )
      if (current in accepted) return current
      delay(VIVI_REAUTH_LOGOUT_POLL_MILLIS)
    }
    return PhoneAutomationViviLogoutSurface.UNKNOWN
  }

  private suspend fun awaitViviSignedOutProof(): Boolean {
    val deadline = SystemClock.elapsedRealtime() + VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      rootHardwareH264CaptureEngine.requestImmediateRefresh("vivi_reauth_signed_out_verify")
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val visual = awaitStableTicketVisualActionObservation(
        reason = "vivi_reauth_signed_out_verify",
        timeoutMillis = minOf(VIVI_REAUTH_VERIFY_SLICE_MILLIS, remaining),
        allowUnknown = true
      )
      if (visual?.state == TicketVisualPhoneState.LOGIN_REQUIRED &&
        PhoneAutomationServiceBridge.classifyViviAuthSurface(
          TicketScreenConfig.VIVI_PACKAGE
        ) == PhoneAutomationViviAuthSurface.LOGIN
      ) return true
      delay(VIVI_REAUTH_LOGOUT_POLL_MILLIS)
    }
    return false
  }

  private suspend fun provenSignedInViviReauthSnapshot(
    request: TicketViviReauthRequest,
    requiredReturnTarget: TicketViviReauthReturnTarget? = null,
    terminalObservation: TicketVisualActionObservation? = null
  ): TicketViviReauthSnapshot {
    if (requiredReturnTarget != null &&
      !ticketViviReauthRestoredTarget(requiredReturnTarget, terminalObservation)
    ) {
      return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    }
    val watermark = awaitTicketVisualActionFrameWatermark("vivi_reauth_signed_in_proof")
      ?: return reauthSnapshot(
        request,
        "needs_attention",
        "complete",
        "visual_proof_failed"
      )
    return reauthSnapshot(
      request,
      "succeeded",
      "complete",
      if (request.logoutInApp) "saved_credentials_sign_in_proven" else "signed_in_proven",
      proofSource = "phone_visual",
      streamEpoch = watermark.first,
      frameSequence = watermark.second,
      ok = true
    )
  }

  private suspend fun verifyViviReauthResult(
    request: TicketViviReauthRequest,
    panelLease: TicketActionPanelDarkLease,
    requiredReturnTarget: TicketViviReauthReturnTarget?
  ): TicketViviReauthSnapshot {
    val deadline = SystemClock.elapsedRealtime() + VIVI_REAUTH_VERIFY_TIMEOUT_MILLIS
    var lastState = TicketVisualPhoneState.UNKNOWN
    while (SystemClock.elapsedRealtime() < deadline) {
      rootHardwareH264CaptureEngine.requestImmediateRefresh("vivi_reauth_verify")
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val observed = awaitStableTicketVisualActionObservation(
        reason = "vivi_reauth_verify",
        timeoutMillis = minOf(VIVI_REAUTH_VERIFY_SLICE_MILLIS, remaining),
        allowUnknown = true
      )
      if (observed != null) lastState = observed.state
      if (ticketViviReauthPostSubmitSignedInObservation(observed)) {
        if (request.redetectAfterLogin) {
          return restoreOrRedetectViviTicketAfterReauth(
            request,
            requiredReturnTarget,
            observed ?: continue,
            panelLease
          )
        }
        if (requiredReturnTarget == null) return provenSignedInViviReauthSnapshot(request)
        val signedInObservation = observed ?: continue
        if (ticketViviReauthRestoredTarget(requiredReturnTarget, signedInObservation) ||
          ticketViviReauthRestoreTarget(requiredReturnTarget, signedInObservation) != null
        ) {
          val terminalObservation = restoreViviTicketAfterReauth(
            request,
            requiredReturnTarget,
            signedInObservation,
            panelLease
          )
            ?: return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
          return provenSignedInViviReauthSnapshot(
            request,
            requiredReturnTarget,
            terminalObservation
          )
        }
      }
      when (PhoneAutomationServiceBridge.classifyViviAuthSurface(TicketScreenConfig.VIVI_PACKAGE)) {
        PhoneAutomationViviAuthSurface.ADDITIONAL_VERIFICATION ->
          return reauthSnapshot(request, "needs_attention", "complete", "additional_verification_required")
        PhoneAutomationViviAuthSurface.DEVICE_LINK ->
          return reauthSnapshot(request, "needs_attention", "complete", "device_link_required")
        PhoneAutomationViviAuthSurface.PROFILE_SELECTION ->
          return reauthSnapshot(request, "needs_attention", "complete", "profile_selection_required")
        PhoneAutomationViviAuthSurface.CAPTCHA ->
          return reauthSnapshot(request, "needs_attention", "complete", "captcha_required")
        PhoneAutomationViviAuthSurface.ONBOARDING ->
          return reauthSnapshot(request, "needs_attention", "complete", "onboarding_required")
        else -> Unit
      }
      delay(VIVI_REAUTH_VERIFY_POLL_MILLIS)
    }
    return if (lastState == TicketVisualPhoneState.LOGIN_REQUIRED) {
      reauthSnapshot(request, "failed", "complete", "credentials_rejected")
    } else {
      reauthAttentionOrFailure(request, "visual_proof_failed")
    }
  }

  private enum class TicketViviReauthOriginalRestoreDisposition {
    RESTORED,
    NO_TICKET_PROVEN,
    FALLBACK_SAFE,
    UNCERTAIN
  }

  private data class TicketViviReauthOriginalRestoreResult(
    val disposition: TicketViviReauthOriginalRestoreDisposition,
    val observation: TicketVisualActionObservation
  )

  /**
   * Version 4 remains inside the re-authentication lease. It first gives the retained private
   * detail identity one bounded restoration attempt, then uses the existing latest-ticket visual
   * route only after that identity is absent or unavailable. No child Ticket action is enqueued.
   */
  private suspend fun restoreOrRedetectViviTicketAfterReauth(
    request: TicketViviReauthRequest,
    originalTarget: TicketViviReauthReturnTarget?,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): TicketViviReauthSnapshot {
    updateViviReauthPhase(request, "running", "redetecting_latest_ticket", "running")
    var observation = initialObservation
    if (originalTarget != null) {
      val original = attemptViviOriginalTicketRestoreBeforeRedetect(
        request,
        originalTarget,
        observation,
        panelLease
      )
      observation = original.observation
      when (original.disposition) {
        TicketViviReauthOriginalRestoreDisposition.RESTORED ->
          return provenViviReauthV4TicketSnapshot(
            request,
            "saved_credentials_original_ticket_restored",
            observation,
            expectedAnchor = originalTarget.detailAnchor,
            expectedState = originalTarget.state
          )
        TicketViviReauthOriginalRestoreDisposition.NO_TICKET_PROVEN ->
          return provenViviReauthV4NoTicketSnapshot(
            request,
            TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
            observation
          )
        TicketViviReauthOriginalRestoreDisposition.UNCERTAIN ->
          return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
        TicketViviReauthOriginalRestoreDisposition.FALLBACK_SAFE -> Unit
      }
    }
    return redetectLatestViviTicketAfterReauth(request, observation, panelLease)
  }

  private suspend fun attemptViviOriginalTicketRestoreBeforeRedetect(
    request: TicketViviReauthRequest,
    originalTarget: TicketViviReauthReturnTarget,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): TicketViviReauthOriginalRestoreResult {
    var observation = initialObservation
    val dispatchedKinds = mutableSetOf<TicketViviReauthRestoreTargetKind>()
    repeat(VIVI_REAUTH_TICKET_RESTORE_MAX_MUTATIONS) { mutationIndex ->
      if (ticketViviReauthRestoredTarget(originalTarget, observation)) {
        return TicketViviReauthOriginalRestoreResult(
          TicketViviReauthOriginalRestoreDisposition.RESTORED,
          observation
        )
      }
      if (observation.state in setOf(
          TicketVisualPhoneState.ACTIVATED_DETAIL,
          TicketVisualPhoneState.UNACTIVATED_DETAIL
        )
      ) {
        return TicketViviReauthOriginalRestoreResult(
          TicketViviReauthOriginalRestoreDisposition.FALLBACK_SAFE,
          observation
        )
      }
      val target = ticketViviReauthV4OriginalRestoreTarget(originalTarget, observation)
      if (target == null) {
        val safeFallback = ticketViviReauthOriginalTargetVisuallyAbsent(
          originalTarget,
          observation
        )
        return TicketViviReauthOriginalRestoreResult(
          if (safeFallback) TicketViviReauthOriginalRestoreDisposition.FALLBACK_SAFE
          else TicketViviReauthOriginalRestoreDisposition.UNCERTAIN,
          observation
        )
      }
      if (!dispatchedKinds.add(target.kind) ||
        !persistViviReauthPhase(request, target.kind.journalPhase) ||
        !panelLease.beforeMutationAllowed()
      ) {
        return TicketViviReauthOriginalRestoreResult(
          TicketViviReauthOriginalRestoreDisposition.UNCERTAIN,
          observation
        )
      }
      panelLease.markMutationMayHaveDispatched()
      val before = observation
      tapTicketVisualProbeBounds(
        target.bounds,
        "vivi_reauth_v4_original_${target.kind.name.lowercase()}"
      )
      val after = awaitViviReauthV4OriginalTransition(
        originalTarget,
        target.kind,
        before,
        "vivi_reauth_v4_original_${mutationIndex + 1}"
      ) ?: return TicketViviReauthOriginalRestoreResult(
        TicketViviReauthOriginalRestoreDisposition.UNCERTAIN,
        observation
      )
      observation = after
      if (target.kind == TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB &&
        before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY &&
        observation.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY
      ) {
        return TicketViviReauthOriginalRestoreResult(
          TicketViviReauthOriginalRestoreDisposition.NO_TICKET_PROVEN,
          observation
        )
      }
    }
    return TicketViviReauthOriginalRestoreResult(
      if (ticketViviReauthRestoredTarget(originalTarget, observation)) {
        TicketViviReauthOriginalRestoreDisposition.RESTORED
      } else {
        TicketViviReauthOriginalRestoreDisposition.UNCERTAIN
      },
      observation
    )
  }

  private suspend fun awaitViviReauthV4OriginalTransition(
    originalTarget: TicketViviReauthReturnTarget,
    dispatchedKind: TicketViviReauthRestoreTargetKind,
    before: TicketVisualActionObservation,
    reason: String
  ): TicketVisualActionObservation? {
    val deadline = SystemClock.elapsedRealtime() + VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      rootHardwareH264CaptureEngine.requestImmediateRefresh(reason)
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val observed = awaitStableTicketVisualActionObservation(
        reason = reason,
        timeoutMillis = minOf(VIVI_REAUTH_VERIFY_SLICE_MILLIS, remaining),
        allowUnknown = true
      )
      if (observed == null || observed.probeId <= before.probeId ||
        observed.state == TicketVisualPhoneState.UNKNOWN
      ) {
        delay(VIVI_REAUTH_VERIFY_POLL_MILLIS)
        continue
      }
      if (observed.state == before.state && observed.currentAnchor == before.currentAnchor) {
        delay(VIVI_REAUTH_VERIFY_POLL_MILLIS)
        continue
      }
      if (ticketViviReauthRestoredTarget(originalTarget, observed)) return observed
      val accepted = when (dispatchedKind) {
        TicketViviReauthRestoreTargetKind.TICKETS_TAB ->
          ticketViviLogoutBottomRouteObservation(before) && observed.state in setOf(
            TicketVisualPhoneState.TICKET_LIST,
            TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
            TicketVisualPhoneState.TICKETS_TIME_EMPTY
          )
        TicketViviReauthRestoreTargetKind.SINGLE_USE_TICKETS_TAB ->
          before.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY && observed.state in setOf(
            TicketVisualPhoneState.TICKET_LIST,
            TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY
          )
        TicketViviReauthRestoreTargetKind.TIME_TICKETS_TAB ->
          before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY && observed.state in setOf(
            TicketVisualPhoneState.TICKET_LIST,
            TicketVisualPhoneState.TICKETS_TIME_EMPTY
          )
        TicketViviReauthRestoreTargetKind.LATEST_UNACTIVATED_DETAIL,
        TicketViviReauthRestoreTargetKind.UNIQUE_ACTIVATED_DETAIL ->
          before.state == TicketVisualPhoneState.TICKET_LIST && observed.state in setOf(
            TicketVisualPhoneState.ACTIVATED_DETAIL,
            TicketVisualPhoneState.UNACTIVATED_DETAIL
          ) && ticketViviReauthTerminalReadyObservation(observed)
      }
      return observed.takeIf { accepted }
    }
    return null
  }

  private suspend fun redetectLatestViviTicketAfterReauth(
    request: TicketViviReauthRequest,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): TicketViviReauthSnapshot {
    var observation = initialObservation
    val dispatchedKinds = mutableSetOf<TicketViviReauthRedetectTargetKind>()
    repeat(VIVI_REAUTH_TICKET_REDETECT_MAX_MUTATIONS) { mutationIndex ->
      if (observation.state == TicketVisualPhoneState.LOGIN_REQUIRED ||
        observation.state == TicketVisualPhoneState.BLOCKED ||
        observation.state == TicketVisualPhoneState.UNKNOWN &&
          !ticketViviLogoutBottomRouteObservation(observation)
      ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      val target = ticketViviReauthRedetectTarget(observation)
        ?: return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      if (!dispatchedKinds.add(target.kind) ||
        !persistViviReauthPhase(request, target.kind.journalPhase) ||
        !panelLease.beforeMutationAllowed()
      ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
      val before = observation
      panelLease.markMutationMayHaveDispatched()
      tapTicketVisualProbeBounds(
        target.bounds,
        "vivi_reauth_v4_redetect_${target.kind.name.lowercase()}"
      )
      observation = awaitViviReauthV4RedetectTransition(
        target,
        before,
        "vivi_reauth_v4_redetect_${mutationIndex + 1}"
      ) ?: return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")

      if (target.kind == TicketViviReauthRedetectTargetKind.LATEST_UNACTIVATED_DETAIL) {
        if (!ticketViviReauthRedetectedLatest(before, target.selectedAnchor, observation)) {
          return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
        }
        val selectedObservation = ticketVisualObservationAfterCardSelection(
          observation,
          target.selectedAnchor
        )
        return provenViviReauthV4TicketSnapshot(
          request,
          "saved_credentials_latest_ticket_redetected",
          selectedObservation,
          expectedAnchor = target.selectedAnchor,
          expectedState = TicketVisualPhoneState.UNACTIVATED_DETAIL
        )
      }
      if (target.kind == TicketViviReauthRedetectTargetKind.TIME_TICKETS_TAB &&
        before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY &&
        observation.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY
      ) {
        return provenViviReauthV4NoTicketSnapshot(request, before.state, observation)
      }
    }
    return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
  }

  private suspend fun awaitViviReauthV4RedetectTransition(
    target: TicketViviReauthRedetectTarget,
    before: TicketVisualActionObservation,
    reason: String
  ): TicketVisualActionObservation? {
    val deadline = SystemClock.elapsedRealtime() + VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      rootHardwareH264CaptureEngine.requestImmediateRefresh(reason)
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val observed = awaitStableTicketVisualActionObservation(
        reason = reason,
        timeoutMillis = minOf(VIVI_REAUTH_VERIFY_SLICE_MILLIS, remaining),
        allowUnknown = true
      )
      if (observed == null || observed.probeId <= before.probeId ||
        observed.state == TicketVisualPhoneState.UNKNOWN
      ) {
        delay(VIVI_REAUTH_VERIFY_POLL_MILLIS)
        continue
      }
      if (observed.state == before.state && observed.currentAnchor == before.currentAnchor) {
        delay(VIVI_REAUTH_VERIFY_POLL_MILLIS)
        continue
      }
      val accepted = when (target.kind) {
        TicketViviReauthRedetectTargetKind.CLOSE_WRONG_DETAIL ->
          before.state in setOf(
            TicketVisualPhoneState.ACTIVATED_DETAIL,
            TicketVisualPhoneState.UNACTIVATED_DETAIL
          ) && ticketViviLogoutBottomRouteObservation(observed)
        TicketViviReauthRedetectTargetKind.TICKETS_TAB ->
          ticketViviLogoutBottomRouteObservation(before) && observed.state in setOf(
            TicketVisualPhoneState.TICKET_LIST,
            TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
            TicketVisualPhoneState.TICKETS_TIME_EMPTY
          )
        TicketViviReauthRedetectTargetKind.SINGLE_USE_TICKETS_TAB ->
          before.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY && observed.state in setOf(
            TicketVisualPhoneState.TICKET_LIST,
            TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY
          )
        TicketViviReauthRedetectTargetKind.TIME_TICKETS_TAB ->
          before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY && observed.state in setOf(
            TicketVisualPhoneState.TICKET_LIST,
            TicketVisualPhoneState.TICKETS_TIME_EMPTY
          )
        TicketViviReauthRedetectTargetKind.LATEST_UNACTIVATED_DETAIL ->
          ticketViviReauthRedetectedLatest(before, target.selectedAnchor, observed)
      }
      return observed.takeIf { accepted }
    }
    return null
  }

  private suspend fun provenViviReauthV4TicketSnapshot(
    request: TicketViviReauthRequest,
    reason: String,
    observation: TicketVisualActionObservation,
    expectedAnchor: String,
    expectedState: TicketVisualPhoneState
  ): TicketViviReauthSnapshot {
    val exactTarget = expectedAnchor.isNotBlank() && observation.state == expectedState &&
      observation.currentAnchor == expectedAnchor &&
      if (expectedState == TicketVisualPhoneState.UNACTIVATED_DETAIL) {
        observation.sliderBounds != null && observation.backBounds != null
      } else {
        expectedState == TicketVisualPhoneState.ACTIVATED_DETAIL &&
          observation.sliderBounds == null && observation.backBounds != null
      }
    if (!exactTarget) {
      return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    }
    val watermark = awaitTicketVisualActionFrameWatermark("vivi_reauth_v4_ticket_proof")
      ?: return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    return reauthSnapshot(
      request,
      "succeeded",
      "complete",
      reason,
      proofSource = "phone_visual",
      streamEpoch = watermark.first,
      frameSequence = watermark.second,
      ok = true
    )
  }

  private suspend fun provenViviReauthV4NoTicketSnapshot(
    request: TicketViviReauthRequest,
    navigationFromState: TicketVisualPhoneState,
    observation: TicketVisualActionObservation
  ): TicketViviReauthSnapshot {
    val watermark = awaitTicketVisualActionFrameWatermark("vivi_reauth_v4_no_ticket_proof")
      ?: return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    if (!ticketViviReauthNoTicketProven(
        navigationFromState,
        observation,
        watermark.first,
        watermark.second
      )
    ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    return reauthSnapshot(
      request,
      "succeeded",
      "complete",
      "saved_credentials_no_ticket_proven",
      proofSource = "phone_visual",
      streamEpoch = watermark.first,
      frameSequence = watermark.second,
      ok = true
    )
  }

  /**
   * A normal account switch must return the shared viewer to a real ticket before succeeding.
   * Every transition is visually typed, journaled, and fenced by the existing re-auth lease. A
   * dispatched transition is observed once and is never replayed after an ambiguous result.
   */
  private suspend fun restoreViviTicketAfterReauth(
    request: TicketViviReauthRequest,
    returnTarget: TicketViviReauthReturnTarget,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): TicketVisualActionObservation? {
    var observation = initialObservation
    val dispatchedKinds = mutableSetOf<TicketViviReauthRestoreTargetKind>()
    repeat(VIVI_REAUTH_TICKET_RESTORE_MAX_MUTATIONS) { mutationIndex ->
      if (ticketViviReauthRestoredTarget(returnTarget, observation)) return observation
      val target = ticketViviReauthRestoreTarget(returnTarget, observation) ?: return null
      if (!dispatchedKinds.add(target.kind) ||
        !persistViviReauthPhase(request, target.kind.journalPhase) ||
        !panelLease.beforeMutationAllowed()
      ) return null
      panelLease.markMutationMayHaveDispatched()
      // A failed shell result can still follow a dispatched tap. Observe and reconcile the exact
      // successor either way; never repeat this target kind.
      tapTicketVisualProbeBounds(
        target.bounds,
        "vivi_reauth_ticket_restore_${target.kind.name.lowercase()}"
      )
      observation = awaitViviReauthRestoreTransition(
        returnTarget = returnTarget,
        dispatchedKind = target.kind,
        before = observation,
        reason = "vivi_reauth_ticket_restore_${mutationIndex + 1}"
      ) ?: return null
    }
    return observation.takeIf { ticketViviReauthRestoredTarget(returnTarget, it) }
  }

  private suspend fun awaitViviReauthRestoreTransition(
    returnTarget: TicketViviReauthReturnTarget,
    dispatchedKind: TicketViviReauthRestoreTargetKind,
    before: TicketVisualActionObservation,
    reason: String
  ): TicketVisualActionObservation? {
    val deadline = SystemClock.elapsedRealtime() + VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadline) {
      rootHardwareH264CaptureEngine.requestImmediateRefresh(reason)
      val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val observed = awaitStableTicketVisualActionObservation(
        reason = reason,
        timeoutMillis = minOf(VIVI_REAUTH_VERIFY_SLICE_MILLIS, remaining),
        allowUnknown = true
      )
      when (ticketViviReauthRestoreTransitionStatus(
        returnTarget,
        dispatchedKind,
        before,
        observed
      )) {
        TicketViviReauthRestoreTransitionStatus.PROVED -> return observed
        TicketViviReauthRestoreTransitionStatus.REJECTED -> return null
        TicketViviReauthRestoreTransitionStatus.WAITING ->
          delay(VIVI_REAUTH_VERIFY_POLL_MILLIS)
      }
    }
    return null
  }

  private suspend fun reauthAttentionOrFailure(
    request: TicketViviReauthRequest,
    fallbackReason: String
  ): TicketViviReauthSnapshot = when (
    PhoneAutomationServiceBridge.classifyViviAuthSurface(TicketScreenConfig.VIVI_PACKAGE)
  ) {
    PhoneAutomationViviAuthSurface.ADDITIONAL_VERIFICATION ->
      reauthSnapshot(request, "needs_attention", "complete", "additional_verification_required")
    PhoneAutomationViviAuthSurface.DEVICE_LINK ->
      reauthSnapshot(request, "needs_attention", "complete", "device_link_required")
    PhoneAutomationViviAuthSurface.PROFILE_SELECTION ->
      reauthSnapshot(request, "needs_attention", "complete", "profile_selection_required")
    PhoneAutomationViviAuthSurface.CAPTCHA ->
      reauthSnapshot(request, "needs_attention", "complete", "captcha_required")
    PhoneAutomationViviAuthSurface.ONBOARDING ->
      reauthSnapshot(request, "needs_attention", "complete", "onboarding_required")
    else -> reauthSnapshot(request, "needs_attention", "complete", fallbackReason)
  }

  private fun reauthAuthSurfaceResult(
    request: TicketViviReauthRequest,
    surface: PhoneAutomationViviAuthSurface,
    fallbackReason: String
  ): TicketViviReauthSnapshot = when (surface) {
    PhoneAutomationViviAuthSurface.ADDITIONAL_VERIFICATION ->
      reauthSnapshot(request, "needs_attention", "complete", "additional_verification_required")
    PhoneAutomationViviAuthSurface.DEVICE_LINK ->
      reauthSnapshot(request, "needs_attention", "complete", "device_link_required")
    PhoneAutomationViviAuthSurface.PROFILE_SELECTION ->
      reauthSnapshot(request, "needs_attention", "complete", "profile_selection_required")
    PhoneAutomationViviAuthSurface.CAPTCHA ->
      reauthSnapshot(request, "needs_attention", "complete", "captcha_required")
    PhoneAutomationViviAuthSurface.ONBOARDING ->
      reauthSnapshot(request, "needs_attention", "complete", "onboarding_required")
    else -> reauthSnapshot(request, "needs_attention", "complete", fallbackReason)
  }

  private fun reauthSnapshot(
    request: TicketViviReauthRequest,
    status: String,
    phase: String,
    reason: String,
    proofSource: String = "",
    streamEpoch: Long = 0L,
    frameSequence: Long = 0L,
    ok: Boolean = false
  ) = TicketViviReauthSnapshot(
    requestId = request.requestId,
    credentialRevision = request.credentialRevision,
    resetAppData = request.resetAppData,
    logoutInApp = request.logoutInApp,
    redetectAfterLogin = request.redetectAfterLogin,
    status = status,
    phase = phase,
    reason = reason,
    proofSource = proofSource,
    streamEpoch = streamEpoch,
    frameSequence = frameSequence,
    terminal = phase == "complete",
    ok = ok
  )

  private fun updateViviReauthPhase(
    request: TicketViviReauthRequest,
    status: String,
    phase: String,
    reason: String
  ) {
    viviReauthSnapshot = reauthSnapshot(request, status, phase, reason)
    broadcastStatus()
  }

  private fun terminalViviReauth(
    request: TicketViviReauthRequest,
    status: String,
    reason: String
  ): TicketSpacetimeCommandResult {
    return viviReauthCommandResult(
      persistViviReauthTerminal(reauthSnapshot(request, status, "complete", reason))
    )
  }

  private fun persistViviReauthTerminal(snapshot: TicketViviReauthSnapshot): TicketViviReauthSnapshot {
    val value = TicketViviReauthJournal(
      requestId = snapshot.requestId,
      credentialRevision = snapshot.credentialRevision,
      resetAppData = snapshot.resetAppData,
      logoutInApp = snapshot.logoutInApp,
      redetectAfterLogin = snapshot.redetectAfterLogin,
      phase = "terminal",
      terminalStatus = snapshot.status,
      terminalReason = snapshot.reason,
      proofSource = snapshot.proofSource,
      streamEpoch = snapshot.streamEpoch,
      frameSequence = snapshot.frameSequence,
      terminalOk = snapshot.ok
    )
    val terminal = if (persistViviReauthJournal(value)) snapshot else snapshot.copy(
      status = "needs_attention",
      reason = "internal_failure",
      ok = false
    )
    viviReauthSnapshot = terminal
    viviReauthCompletedAtMillis = SystemClock.elapsedRealtime()
    broadcastStatus()
    return terminal
  }

  private fun viviReauthCommandResult(snapshot: TicketViviReauthSnapshot) =
    TicketSpacetimeCommandResult(
      ok = snapshot.ok,
      reason = snapshot.reason.ifBlank { "internal_failure" },
      streamState = ticketSpacetimeStreamState(),
      terminal = snapshot.terminal,
      viviReauth = snapshot
    )

  private fun loadViviReauthJournal(): TicketViviReauthJournal {
    val preferences = applicationContext.getSharedPreferences(VIVI_REAUTH_JOURNAL_PREFERENCES, Context.MODE_PRIVATE)
    return TicketViviReauthJournal(
      requestId = preferences.getString("request_id", "").orEmpty(),
      credentialRevision = preferences.getString("credential_revision", "").orEmpty(),
      resetAppData = preferences.getBoolean("reset_app_data", false),
      logoutInApp = preferences.getBoolean("logout_in_app", false),
      redetectAfterLogin = preferences.getBoolean("redetect_after_login", false),
      phase = preferences.getString("phase", "").orEmpty(),
      terminalStatus = preferences.getString("terminal_status", "").orEmpty(),
      terminalReason = preferences.getString("terminal_reason", "").orEmpty(),
      proofSource = preferences.getString("proof_source", "").orEmpty(),
      streamEpoch = preferences.getLong("stream_epoch", 0L),
      frameSequence = preferences.getLong("frame_sequence", 0L),
      terminalOk = preferences.getBoolean("terminal_ok", false)
    )
  }

  private fun persistViviReauthPhase(request: TicketViviReauthRequest, phase: String): Boolean =
    persistViviReauthJournal(
      TicketViviReauthJournal(
        requestId = request.requestId,
        credentialRevision = request.credentialRevision,
        resetAppData = request.resetAppData,
        logoutInApp = request.logoutInApp,
        redetectAfterLogin = request.redetectAfterLogin,
        phase = phase
      )
    )

  private fun persistViviReauthJournal(value: TicketViviReauthJournal): Boolean {
    val preferences = applicationContext.getSharedPreferences(VIVI_REAUTH_JOURNAL_PREFERENCES, Context.MODE_PRIVATE)
    return ticketViviReauthJournalWriteProved(
      value,
      commit = {
        preferences.edit()
          .putString("request_id", value.requestId)
          .putString("credential_revision", value.credentialRevision)
          .putBoolean("reset_app_data", value.resetAppData)
          .putBoolean("logout_in_app", value.logoutInApp)
          .putBoolean("redetect_after_login", value.redetectAfterLogin)
          .putString("phase", value.phase)
          .putString("terminal_status", value.terminalStatus)
          .putString("terminal_reason", value.terminalReason)
          .putString("proof_source", value.proofSource)
          .putLong("stream_epoch", value.streamEpoch)
          .putLong("frame_sequence", value.frameSequence)
          .putBoolean("terminal_ok", value.terminalOk)
          .commit()
      },
      readBack = ::loadViviReauthJournal
    )
  }

  /**
   * Executes every v3 Ticket action through one visual state machine.  This path deliberately
   * does not read the accessibility hierarchy: the rooted capture helper returns only opaque
   * card anchors and bounded controls, and every mutation requires two distinct agreeing probes.
   */
  private suspend fun handleTicketVisualActionV3(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest
  ): TicketSpacetimeCommandResult {
    return sessionMutex.withLock {
      synchronized(ticketActionV3Lock) {
      val retainedTerminal = loadTicketVisualActionJournal()
      if (retainedTerminal.hasRetainedTerminal &&
        (retainedTerminal.actionId != request.actionId ||
          retainedTerminal.commandId != command.id)
      ) {
        val pending = TicketVisualActionSnapshot(
          actionId = request.actionId,
          target = request.target.wireName,
          status = "pending",
          phase = "waiting_for_terminal_finalization",
          reason = "ticket_action_v3_terminal_finalization_pending"
        )
        return TicketSpacetimeCommandResult(
          ok = false,
          reason = pending.reason,
          streamState = ticketSpacetimeStreamState(),
          terminal = false,
          ticketActionV3 = pending
        )
      }
      if (serviceLifecycleStopping || !secureCaptureStartupReady) {
        val blocked = ticketVisualActionTerminal(
          request = request,
          ok = false,
          status = if (request.target.activatesTicket) "needs_attention" else "failed",
          reason = if (serviceLifecycleStopping) {
            "ticket_action_v3_service_stopping"
          } else {
            "ticket_action_v3_startup_reconcile_unproved"
          },
          terminalPhase = if (request.target.activatesTicket) "not_dispatched" else "failed"
        )
        return TicketSpacetimeCommandResult(
          ok = false,
          reason = blocked.reason,
          streamState = ticketSpacetimeStreamState(),
          terminal = true,
          ticketActionV3 = blocked
        )
      }
      if (ticketActionV3Snapshot.actionId == request.actionId) {
        return TicketSpacetimeCommandResult(
          ok = ticketActionV3Snapshot.ok,
          reason = ticketActionV3Snapshot.reason.ifBlank { "ticket_action_v3_running" },
          streamState = ticketSpacetimeStreamState(),
          terminal = ticketActionV3Snapshot.terminal,
          ticketActionV3 = ticketActionV3Snapshot
        )
      }
      val cleanupCheckpointRecoveryProof =
        request.target == TicketVisualActionTarget.PROVE_CURRENT &&
          controlCodeSignatureCleanupRequired
      if (ticketActionV3Job?.isActive == true) {
        val busy = TicketVisualActionSnapshot(
          actionId = request.actionId,
          target = request.target.wireName,
          status = "pending",
          phase = "waiting_for_phone_lane",
          reason = "ticket_action_v3_phone_lane_busy"
        )
        return TicketSpacetimeCommandResult(
          ok = false,
          reason = busy.reason,
          streamState = ticketSpacetimeStreamState(),
          terminal = false,
          ticketActionV3 = busy
        )
      }
      ticketActionV3Generation += 1L
      val generation = ticketActionV3Generation
      if (request.target != TicketVisualActionTarget.PROVE_CURRENT ||
        cleanupCheckpointRecoveryProof
      ) {
        ticketActionV3MutationGeneration += 1L
      }
      ticketActionV3CleanupCheckpointRecoveryActive = cleanupCheckpointRecoveryProof
      ticketActionV3Snapshot = TicketVisualActionSnapshot(
        actionId = request.actionId,
        target = request.target.wireName,
        status = "running",
        phase = "awaiting_visual_proof",
        reason = "ticket_action_v3_admitted"
      )
      ticketVisualActionJobOwnershipActive = true
      ticketActionV3Job = serviceScope.launch {
        try {
          val terminal = runCatching {
            if (request.target == TicketVisualActionTarget.PROVE_CURRENT &&
              !cleanupCheckpointRecoveryProof
            ) {
              runReadOnlyTicketVisualProofV3(command, request, generation)
            } else {
              controlCodePhoneMutationLane.withOwnership {
                runTicketVisualActionV3(
                  command,
                  request,
                  generation,
                  readOnlyCleanupCheckpointRecovery = cleanupCheckpointRecoveryProof
                )
              }
            }
          }.getOrElse {
            val checkpoint = if (request.target.activatesTicket) {
              val revision = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
                request.expectedInteractionRevision
              } else {
                command.revision
              }
              ticketActivationCheckpoint(command.id, revision, request.attemptId)
            } else {
              null
            }
            val failurePhase = when {
              checkpoint?.stage == TicketActivationCheckpointStage.NO_TRANSITION_PROVEN &&
                checkpoint.dispatchOrdinal >= 2 -> "no_transition"
              checkpoint?.stage == TicketActivationCheckpointStage.NO_TRANSITION_PROVEN ->
                "retry_not_dispatched"
              checkpoint?.dispatchOrdinal?.let { ordinal -> ordinal > 0 } == true ->
                "outcome_unknown"
              else -> "not_dispatched"
            }
            ticketVisualActionTerminal(
              request,
              ok = false,
              status = if (request.target.activatesTicket) "needs_attention" else "failed",
              reason = "ticket_action_v3_internal_failure",
              terminalPhase = failurePhase.takeIf { request.target.activatesTicket }
            )
          }
          synchronized(ticketActionV3Lock) {
            if (ticketActionV3Generation == generation &&
              ticketActionV3Snapshot.actionId == request.actionId
            ) {
              ticketActionV3Snapshot = terminal
              ticketActionV3CleanupCheckpointRecoveryActive = false
            }
          }
          broadcastStatus()
        } finally {
          withContext(NonCancellable) {
            sessionMutex.withLock {
              ticketVisualActionJobOwnershipActive = false
            }
          }
        }
      }
      return TicketSpacetimeCommandResult(
        ok = false,
        reason = "ticket_action_v3_running",
        streamState = ticketSpacetimeStreamState(),
        terminal = false,
        ticketActionV3 = ticketActionV3Snapshot
      )
      }
    }
  }

  /**
   * `prove_current` observes the rooted capture only. Its ordinary caller deliberately does not
   * acquire the phone mutation lane, launch ViVi, write brightness, or take the panel-dark lease.
   * A retained cleanup checkpoint may wrap this same no-input proof in the lane and panel lease so
   * the durable fence can be cleared only after a safe final tail. The action and physical-touch
   * generations fence both fresh observations and the final encoded watermark.
   */
  private suspend fun runReadOnlyTicketVisualProofV3(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    generation: Long,
    requireFreshRawDetail: Boolean = false
  ): TicketVisualActionSnapshot {
    val mutationGeneration = ticketActionV3MutationGeneration
    val initialTouch = PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
    if (initialTouch.active) {
      return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        "ticket_action_current_physical_touch_active"
      )
    }
    if (!streamActive || !rootHardwareH264CaptureEngine.snapshot().active) {
      return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        "ticket_action_current_stream_unavailable"
      )
    }
    updateTicketVisualActionPhase(request, "read_only_capture")
    rootHardwareH264CaptureEngine.requestImmediateRefresh("ticket_action_v3_read_only_proof")
    return try {
      val rawDetailProofEpoch = streamEpoch
      val rawDetailProofRestartCount = rootHardwareH264CaptureEngine.snapshot().restartCount
      if (requireFreshRawDetail && !verifyFreshTicketDetailVisualProof(
          reason = "control_code_cleanup_checkpoint_recovery",
          probeWaitMillis = CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS
        )
      ) {
        recordTicketEvent(
          "control_code_cleanup_checkpoint_visual_recovery_rejected",
          "dimension=raw_ticket_unproved"
        )
        return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          "control_code_cleanup_pending_requires_visual_reopen"
        )
      }
      val current = awaitStableTicketVisualActionObservation(
        reason = "ticket_action_v3_prove_current",
        timeoutMillis = TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS,
        allowUnknown = !requireFreshRawDetail,
        currentOnly = true
      ) ?: return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        "ticket_action_visual_unproved"
      )
      if (requireFreshRawDetail) {
        val captureGenerationChanged = streamEpoch != rawDetailProofEpoch ||
          rootHardwareH264CaptureEngine.snapshot().restartCount != rawDetailProofRestartCount
        val mismatch = if (captureGenerationChanged) {
          "capture_generation_changed"
        } else {
          ticketControlCodeFreshRawDetailMismatch(current)
        }
        if (mismatch != null) {
          recordTicketEvent(
            "control_code_cleanup_checkpoint_visual_recovery_rejected",
            "dimension=$mismatch view=${current.state.wireName}"
          )
          return ticketVisualActionTerminal(
            request,
            false,
            "needs_attention",
            "control_code_cleanup_pending_requires_visual_reopen",
            current
          )
        }
      }
      val finalTouch = PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
      if (ticketActionV3Generation != generation ||
        ticketActionV3MutationGeneration != mutationGeneration ||
        finalTouch.active ||
        (finalTouch.available && initialTouch.available &&
          finalTouch.observedAtUptimeMillis != initialTouch.observedAtUptimeMillis)
      ) {
        return ticketVisualActionTerminal(
          request,
          false,
          "failed",
          "ticket_action_current_proof_fence_changed",
          current
        )
      }
      updateTicketVisualActionPhase(request, "binding_read_only_watermark")
      proveCurrentTicketVisualAction(command, request, current).let { result ->
        val watermarkTouch = PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
        val touchFenceCurrent = !watermarkTouch.active &&
          (!watermarkTouch.available || !initialTouch.available ||
            watermarkTouch.observedAtUptimeMillis == initialTouch.observedAtUptimeMillis)
        if (ticketActionV3MutationGeneration == mutationGeneration && touchFenceCurrent) {
          result
        } else {
          ticketVisualActionTerminal(
            request,
            false,
            "failed",
            "ticket_action_current_proof_fence_changed",
            current
          )
        }
      }
    } finally {
      // The one-shot all-intra refresh needs no release operation.
    }
  }

  private suspend fun runTicketVisualActionV3(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    generation: Long,
    readOnlyCleanupCheckpointRecovery: Boolean = false
  ): TicketVisualActionSnapshot {
    val recoveringControlCodeCheckpointAtStart = controlCodeSignatureCleanupRequired
    val readOnlyCleanupCheckpointPendingInsideLane =
      readOnlyCleanupCheckpointRecovery &&
        request.target == TicketVisualActionTarget.PROVE_CURRENT &&
        recoveringControlCodeCheckpointAtStart
    sessionMutex.withLock {
      ticketVisualActionCaptureLeaseActive = true
    }
    synchronized(ticketActionV3Lock) {
      deferredTicketVisualTerminalActionId = ""
      deferredTicketVisualTerminalObservation = null
    }
    val panelLease = TicketActionPanelDarkLease(
      actionId = request.actionId,
      scope = serviceScope,
      clampRootExecutor = ticketActionPanelDarkRootExecutor,
      verifyRootExecutor = ticketActionPanelDarkVerifyRootExecutor,
      physicalTouchState = PhoneAutomationServiceBridge::currentRootPhysicalTouchState,
      ownerProcessId = android.os.Process.myPid(),
      onSnapshotChanged = { snapshot ->
        val prior = ticketActionPanelDarkLeaseSnapshot
        if (snapshot.failure.isNotBlank() &&
          (prior.ownerActionId != snapshot.ownerActionId || prior.failure.isBlank())
        ) {
          ticketActionPanelDarkLeaseFailures.incrementAndGet()
        }
        ticketActionPanelDarkLeaseSnapshot = snapshot
        broadcastStatus()
      }
    )
    activeTicketActionPanelDarkLease = panelLease
    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "ticket:ticket_action_panel_dark:${request.actionId.takeLast(24)}",
      durationMillis = TICKET_ACTION_V3_PANEL_DARK_LEASE_MILLIS
    )
    var leaseAcquired = false
    var commandStartedProofSessionGeneration: Long? = null
    var provisional = TicketVisualActionSnapshot(
      actionId = request.actionId,
      target = request.target.wireName,
      status = "failed",
      phase = "failed",
      reason = "ticket_action_failed",
      terminal = true,
      ok = false
    )
    var finalization: TicketActionPanelDarkLeaseFinalization? = null
    try {
      leaseAcquired = panelLease.acquire()
      provisional = if (!leaseAcquired) {
        ticketVisualActionTerminal(
          request,
          ok = false,
          status = "failed",
          // The detailed clamp failure remains available in privacy-safe Pixel health. Publish the
          // existing allowlisted pre-dispatch reason so Spacetime never collapses this terminal.
          reason = "ticket_action_failed"
        )
      } else if (readOnlyCleanupCheckpointRecovery &&
        request.target == TicketVisualActionTarget.PROVE_CURRENT
      ) {
        runReadOnlyTicketVisualProofV3(
          command,
          request,
          generation,
          requireFreshRawDetail = readOnlyCleanupCheckpointPendingInsideLane
        )
      } else {
        val result = runTicketVisualActionV3WithCaptureLease(
          command,
          request,
          generation,
          panelLease,
          onNewSessionAdmitted = { sessionGeneration ->
            commandStartedProofSessionGeneration = sessionGeneration
          }
        )
        val leaseState = panelLease.snapshot()
        when {
          leaseState.physicalTouchPreempted && leaseState.mutationMayHaveDispatched ->
            if (result.reason.startsWith("ticket_action_physical_touch_preempted_after_dispatch_")) {
              result
            } else {
              ticketVisualActionTerminal(
                request,
                ok = false,
                status = "needs_attention",
                reason = "ticket_action_physical_touch_preempted_after_dispatch",
                terminalPhase = "outcome_unknown".takeIf { request.target.activatesTicket }
              )
            }
          leaseState.physicalTouchPreempted ->
            ticketVisualActionTerminal(
              request,
              ok = false,
              status = "failed",
              reason = "ticket_action_physical_touch_preempted_before_dispatch"
            )
          leaseState.failure.isNotBlank() ->
            ticketVisualActionTerminal(
              request,
              ok = false,
              status = if (leaseState.mutationMayHaveDispatched) "needs_attention" else "failed",
              reason = leaseState.failure
            )
          else -> result
        }
      }
    } finally {
      withContext(NonCancellable) {
        finalization = if (leaseAcquired) {
          panelLease.releaseAfterFinalConvergence("ticket_action_terminal")
        } else {
          panelLease.release("ticket_action_acquire_failed")
        }
        if (activeTicketActionPanelDarkLease === panelLease) {
          activeTicketActionPanelDarkLease = null
        }
        PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(
          "ticket:ticket_action_panel_dark:${request.actionId.takeLast(24)}:released"
        )
        sessionMutex.withLock {
          ticketVisualActionCaptureLeaseActive = false
          commandStartedProofSessionGeneration?.let { sessionGeneration ->
            scheduleCommandStartedProofStreamCleanupLocked(
              expectedSessionGeneration = sessionGeneration,
              reason = "ticket_action_v3_proof_stream_idle"
            )
          }
        }
      }
    }
    val completedLease = finalization
    val provisionalExpectedNegativeProof =
      ticketVisualLatestNotDetectedTerminalHasBoundProof(provisional)
    val provisionalHasBoundVisualProof = provisional.ok || provisionalExpectedNegativeProof
    val successfulProofCurrent = if (!provisionalHasBoundVisualProof) {
      true
    } else {
      synchronized(encoderLock) {
        val keyFrame = latestKeyFrame
        ticketVisualSuccessProofCurrentAfterPanelFinalization(
          proofActionId = provisional.actionId,
          expectedActionId = request.actionId,
          proofGeneration = generation,
          currentGeneration = ticketActionV3Generation,
          proofStreamEpoch = provisional.streamEpoch,
          proofFrameSequence = provisional.frameSequence,
          currentStreamEpoch = streamEpoch,
          currentFrameSequence = frameSequence,
          latestKeyFrameEpoch = keyFrame?.epoch ?: 0L,
          latestKeyFrameSequence = keyFrame?.sequence ?: 0L
        )
      }
    }
    val cleanupRecoveryTailCurrent = !readOnlyCleanupCheckpointPendingInsideLane ||
      (!controlCodeRequestActive() &&
        pendingControlCodeBrowserCaptureRequestId == null &&
        activeControlCodeKeyboardClamp == null &&
        activeTicketActionPanelDarkLease == null)
    val recoveredControlCodeSurface = recoveringControlCodeCheckpointAtStart && provisional.ok &&
      when (request.target) {
        TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED ->
          provisional.currentView == TicketVisualActionView.LATEST_UNACTIVATED
        TicketVisualActionTarget.PROVE_CURRENT -> readOnlyCleanupCheckpointRecovery &&
          provisional.currentView in setOf(
            TicketVisualActionView.ACTIVATED_CURRENT,
            TicketVisualActionView.LATEST_UNACTIVATED
          )
        else -> false
      }
    val recoveredControlCodeCleanupCommitted = if (!recoveredControlCodeSurface) {
      true
    } else if (successfulProofCurrent && cleanupRecoveryTailCurrent &&
      ticketControlCodeCleanupMayCommitAfterPanelFinalization(
        phoneSurfaceCleaned = true,
        finalization = completedLease
      )
    ) {
      commitControlCodeCleanStateAfterPanelFinalization("ticket_action_visual_reopen")
    } else {
      false
    }
    if (recoveredControlCodeSurface && !recoveredControlCodeCleanupCommitted) {
      preserveControlCodeCleanupCheckpoint("ticket_action_visual_reopen_finalization_unproved")
    }
    val rawTerminal = when {
      leaseAcquired && completedLease?.safe != true -> {
        val leaseState = completedLease?.snapshot ?: panelLease.snapshot()
        provisional.copy(
          status = if (leaseState.mutationMayHaveDispatched) "needs_attention" else "failed",
          phase = if (leaseState.mutationMayHaveDispatched) "needs_attention" else "failed",
          reason = ticketActionPanelDarkFinalizationFailureReason(request, leaseState),
          switchAvailable = false,
          switchExpiresAt = "",
          sliderRegion = null,
          completedAt = Instant.now().toString(),
          terminal = true,
          ok = false
        )
      }
      provisionalHasBoundVisualProof && !successfulProofCurrent -> {
        val currentWatermark = synchronized(encoderLock) { streamEpoch to frameSequence }
        provisional.copy(
          status = "needs_attention",
          phase = "needs_attention",
          currentView = TicketVisualActionView.UNKNOWN,
          switchAvailable = false,
          switchExpiresAt = "",
          streamEpoch = currentWatermark.first,
          frameSequence = currentWatermark.second,
          reason = if (provisionalExpectedNegativeProof) {
            "ticket_action_frame_watermark_unproved"
          } else {
            "ticket_action_visual_unproved"
          },
          sliderRegion = null,
          completedAt = Instant.now().toString(),
          terminal = true,
          ok = false
        )
      }
      provisional.ok && !recoveredControlCodeCleanupCommitted -> {
        val currentWatermark = synchronized(encoderLock) { streamEpoch to frameSequence }
        provisional.copy(
          status = "needs_attention",
          phase = "needs_attention",
          currentView = TicketVisualActionView.UNKNOWN,
          switchAvailable = false,
          switchExpiresAt = "",
          streamEpoch = currentWatermark.first,
          frameSequence = currentWatermark.second,
          reason = "ticket_action_visual_unproved",
          sliderRegion = null,
          completedAt = Instant.now().toString(),
          terminal = true,
          ok = false
        )
      }
      else -> provisional
    }
    val terminal = if (request.target.activatesTicket && !rawTerminal.ok) {
      val stablePhase = provisional.phase.takeIf {
        it in setOf("retry_not_dispatched", "no_transition", "outcome_unknown")
      } ?: if (completedLease?.snapshot?.mutationMayHaveDispatched == true) {
        "outcome_unknown"
      } else {
        "not_dispatched"
      }
      rawTerminal.copy(
        status = "needs_attention",
        phase = stablePhase,
        currentView = if (stablePhase == "outcome_unknown") {
          TicketVisualActionView.UNKNOWN
        } else {
          rawTerminal.currentView
        },
        activationRevision = "",
        activationAttemptId = request.attemptId,
        ok = false
      )
    } else {
      rawTerminal
    }
    val deferredObservation = synchronized(ticketActionV3Lock) {
      deferredTicketVisualTerminalObservation.takeIf {
        deferredTicketVisualTerminalActionId == request.actionId
      }.also {
        deferredTicketVisualTerminalActionId = ""
        deferredTicketVisualTerminalObservation = null
      }
    }
    val terminalExpectedNegativeProof =
      ticketVisualLatestNotDetectedTerminalHasBoundProof(terminal)
    if (terminal.ok || terminalExpectedNegativeProof || terminal != provisional) {
      val terminalObservation = deferredObservation.takeIf {
        (terminal.ok || terminalExpectedNegativeProof) && successfulProofCurrent
      }
      if (!persistTicketVisualTerminalSnapshot(request, terminal, terminalObservation)) {
        recordTicketEvent(
          "ticket_action_terminal_journal_unproved",
          "action=${request.actionId.takeLast(24)} status=${terminal.status}"
        )
        return terminal.copy(
          status = "needs_attention",
          phase = "needs_attention",
          reason = "ticket_action_terminal_journal_unproved",
          switchAvailable = false,
          switchExpiresAt = "",
          sliderRegion = null,
          ok = false
        )
      }
    }
    if (terminal.ok && recoveredControlCodeSurface && recoveredControlCodeCleanupCommitted) {
      publishControlCodeReadyAfterPanelFinalization(lastControlCodeRequestId.orEmpty())
    }
    return terminal
  }

  private fun ticketActionPanelDarkFinalizationFailureReason(
    request: TicketVisualActionRequest,
    snapshot: TicketActionPanelDarkLeaseSnapshot
  ): String = when {
    !snapshot.mutationMayHaveDispatched -> "ticket_action_failed"
    request.target.activatesTicket -> "ticket_action_activation_dispatch_uncertain"
    request.target != TicketVisualActionTarget.PROVE_CURRENT ->
      "ticket_action_navigation_dispatch_uncertain"
    else -> "ticket_action_v3_failed"
  }

  private suspend fun runTicketVisualActionV3WithCaptureLease(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    generation: Long,
    panelLease: TicketActionPanelDarkLease,
    onNewSessionAdmitted: (Long) -> Unit
  ): TicketVisualActionSnapshot {
    loadTicketVisualSwitchAnchorsIfNeeded()
    val recoveringControlCodeCheckpoint = controlCodeSignatureCleanupRequired
    if (recoveringControlCodeCheckpoint &&
      request.target != TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED
    ) {
      return ticketVisualActionTerminal(
        request,
        false,
        "needs_attention",
        "control_code_cleanup_pending_requires_visual_reopen"
      )
    }
    val retainedJournal = loadTicketVisualActionJournal()
    retainedTicketVisualTerminalSnapshot(
      retainedJournal,
      request,
      streamEpoch,
      frameSequence
    )?.let { return it }
    if (ticketActionV3Generation != generation) {
      return ticketVisualActionTerminal(request, false, "failed", "ticket_action_v3_superseded")
    }
    if (request.target.activatesTicket) {
      val checkpointRevision = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
        request.expectedInteractionRevision
      } else {
        command.revision
      }
      val unresolvedCheckpoint = ticketActivationCheckpointStore.load()
      if (unresolvedCheckpoint != null &&
        (unresolvedCheckpoint.commandId != command.id ||
          unresolvedCheckpoint.interactionRevision != checkpointRevision ||
          unresolvedCheckpoint.activationAttemptId != request.attemptId)
      ) {
        return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          "ticket_action_activation_checkpoint_unproved",
          terminalPhase = "not_dispatched"
        )
      }
    }
    updateTicketVisualActionPhase(request, "starting_visual_stream")
    if (!panelLease.beforeMutationAllowed()) {
      return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        "ticket_action_failed"
      )
    }
    if (!streamActive) {
      val startResponse = startTicketSession(
        onNewSessionAdmitted = onNewSessionAdmitted
      )
      if (!startResponse.ok || !streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
        return ticketVisualActionTerminal(
          request,
          false,
          "failed",
          "ticket_action_visual_stream_unavailable"
        )
      }
    }
    val viviAlreadyFocused = viviFocusedForFastPublicOpen("ticket_action_v3_prelaunch")
    if (request.target.activatesTicket || !viviAlreadyFocused) {
      // The focus lookup can suspend while the physical-touch monitor remains live. Re-check at
      // the exact resume boundary so a touch that arrived during it can never be followed by input.
      if (!panelLease.beforeMutationAllowed()) {
        return ticketVisualActionTerminal(
          request,
          false,
          "failed",
          "ticket_action_failed"
        )
      }
      // This is a no-touch Android activity resume. It never creates physical replay authority;
      // the durable dispatch checkpoint is still written only after the exact input fence passes.
      launchViviForWake("ticket_action_v3:${request.target.wireName}")
    } else {
      recordTicketEvent(
        "ticket_action_v3_vivi_launch_skipped",
        "target=${request.target.wireName} reason=already_focused"
      )
    }
    ensureRootHardwareH264CaptureIfPossible()
    rootHardwareH264CaptureEngine.requestImmediateRefresh("ticket_action_v3_capture_lease")
    if (request.target.activatesTicket) {
      updateTicketVisualActionPhase(request, "preparing_registration_input")
      if (!PhoneAutomationServiceBridge.awaitAccessibilityConnection(
          TICKET_SLIDER_ACCESSIBILITY_RECONNECT_TIMEOUT_MILLIS
        )
      ) {
        return ticketVisualActionTerminal(
          request,
          false,
          "failed",
          "ticket_action_accessibility_unavailable"
        )
      }
    }
    val captureRecoveryBudget = TicketVisualCaptureRecoveryBudget()
    var observation = awaitStableTicketVisualActionObservation(
      "ticket_action_v3_initial",
      TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS,
      captureRecoveryBudget = captureRecoveryBudget.takeIf {
        retainedJournal.navigationDispatchUncertain &&
          retainedJournal.actionId == request.actionId &&
          retainedJournal.target == request.target.wireName
      }
    ) ?: return ticketVisualActionTerminal(request, false, "failed", "ticket_action_visual_unproved")
    if (request.target.activatesTicket) {
      val checkpointRevision = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
        request.expectedInteractionRevision
      } else {
        command.revision
      }
      val checkpoint = ticketActivationCheckpoint(command.id, checkpointRevision, request.attemptId)
      when (checkpoint?.stage) {
        TicketActivationCheckpointStage.ACTIVATION_PROVEN ->
          return ticketVisualActivationSuccess(
            request,
            observation,
            checkpointRevision,
            checkpoint.activationRevision,
            checkpointRecovery = true
          )
        TicketActivationCheckpointStage.NO_TRANSITION_PROVEN ->
          return ticketVisualActionTerminal(
            request,
            false,
            "needs_attention",
            ticketActivationNoTransitionTerminalReason(checkpoint),
            observation,
            terminalPhase = ticketActivationNoTransitionTerminalPhase(checkpoint)
          )
        TicketActivationCheckpointStage.ACTIVATION_DISPATCHING,
        TicketActivationCheckpointStage.NEEDS_ATTENTION -> {
          checkpoint.let(ticketActivationCheckpointStore::recordNeedsAttention)
          return ticketVisualActionTerminal(
            request,
            false,
            "needs_attention",
            "ticket_action_activation_dispatch_uncertain",
            observation
          )
        }
        TicketActivationCheckpointStage.FRESH_TICKET_PROVEN,
        null -> Unit
      }
    }

    var mutations = 0
    var controlCodeRecoveryListProved = observation.state == TicketVisualPhoneState.TICKET_LIST
    var selectedDetailAnchor = ""
    val journal = retainedJournal
    var selectedAnchor = journal.intendedAnchor.takeIf {
      journal.actionId == request.actionId && journal.target == request.target.wireName
    }.orEmpty()
    if (journal.navigationDispatchUncertain) {
      if (journal.actionId != request.actionId || !ticketVisualJournalReconciled(journal, request, observation)) {
        return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_navigation_dispatch_uncertain", observation
        )
      }
      if (journal.navigationToState in setOf(
          TicketVisualPhoneState.UNACTIVATED_DETAIL.wireName,
          TicketVisualPhoneState.ACTIVATED_DETAIL.wireName
        ) && journal.navigationAnchor.isNotBlank()
      ) {
        selectedDetailAnchor = observation.currentAnchor
        observation = if (request.target == TicketVisualActionTarget.SHOW_RECENT_ACTIVATED) {
          ticketVisualObservationAfterRecentActivatedSelection(
            observation,
            journal.navigationAnchor,
            ticketVisualSwitchAnchors.recentActivatedAnchor
          )
        } else {
          ticketVisualObservationAfterCardSelection(observation, journal.navigationAnchor)
        }
      }
      if (!persistTicketVisualActionJournal(journal.copy(phase = "navigation_reconciled"))) {
        return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          "ticket_action_navigation_journal_unproved",
          observation
        )
      }
      ticketVisualActionLatestNotDetectedAfterTimeTab(
        request,
        TicketVisualPhoneState.fromWireName(journal.navigationFromState),
        observation
      )?.let { return it }
    }
    while (mutations < TICKET_ACTION_V3_MAX_NAVIGATION_MUTATIONS) {
      if (ticketActionV3Generation != generation) {
        return ticketVisualActionTerminal(request, false, "failed", "ticket_action_v3_superseded")
      }
      val desiredState = when (request.target) {
        TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
        TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
        TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED,
        TicketVisualActionTarget.REDETECT_LATEST -> TicketVisualPhoneState.UNACTIVATED_DETAIL
        TicketVisualActionTarget.REGISTER_CURRENT -> TicketVisualPhoneState.UNACTIVATED_DETAIL
        TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> TicketVisualPhoneState.ACTIVATED_DETAIL
        TicketVisualActionTarget.PROVE_CURRENT -> null
      }
      val requiredAnchor = when (request.target) {
        TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> ticketVisualSwitchAnchors.recentActivatedAnchor
        TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED -> ticketVisualSwitchAnchors.latestUnactivatedAnchor
        TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
        TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
        TicketVisualActionTarget.REDETECT_LATEST -> selectedAnchor
        else -> ""
      }
      val targetProved = desiredState != null && observation.state == desiredState &&
        (!recoveringControlCodeCheckpoint || mutations > 0 && controlCodeRecoveryListProved) &&
        when (request.target) {
        TicketVisualActionTarget.REGISTER_CURRENT -> true
        else -> requiredAnchor.isNotBlank() && observation.currentAnchor == requiredAnchor
      }
      if (targetProved) break
      if (request.target == TicketVisualActionTarget.REGISTER_CURRENT &&
        observation.state in setOf(
          TicketVisualPhoneState.VIVI_HOME,
          TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
          TicketVisualPhoneState.TICKETS_TIME_EMPTY
        )
      ) {
        return ticketVisualActionTerminal(
          request,
          false,
          "failed",
          "ticket_action_register_current_requires_unactivated_detail"
        )
      }
      if (observation.state == TicketVisualPhoneState.LOGIN_REQUIRED ||
        observation.state == TicketVisualPhoneState.BLOCKED ||
        observation.state == TicketVisualPhoneState.UNKNOWN
      ) {
        return ticketVisualActionTerminal(request, false, "failed", "ticket_action_visual_state_${observation.state.wireName}")
      }
      var selectedAnchorForTransition = ""
      val tapBounds = when (observation.state) {
        TicketVisualPhoneState.TICKET_LIST -> {
          val selectedCard = when (request.target) {
            TicketVisualActionTarget.SHOW_RECENT_ACTIVATED ->
              observation.activatedCardForRecentDetail(ticketVisualSwitchAnchors)
            TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED ->
              observation.cardFor(request.target, ticketVisualSwitchAnchors)
            TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
            TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER ->
              observation.latestRegistrationCard()
            TicketVisualActionTarget.REDETECT_LATEST -> observation.latestRegistrationCard()
            TicketVisualActionTarget.REGISTER_CURRENT,
            TicketVisualActionTarget.PROVE_CURRENT -> null
          }
          selectedAnchor = selectedCard?.anchor.orEmpty()
          selectedAnchorForTransition = selectedAnchor
          // Current ViVi cards keep the most recently activated journey behind a compact proved
          // registered-status target and expose a separate registration control for the next
          // journey. No action taps the generic card body, and redetection also converges on the
          // unactivated Aztec/slider detail instead of publishing the list as a terminal state.
          selectedCard?.navigationBoundsFor(request.target)
        }
        TicketVisualPhoneState.VIVI_HOME -> observation.ticketsTabBounds
        TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY ->
          observation.timeTicketsNavigationBoundsFor(request.target)
        TicketVisualPhoneState.TICKETS_TIME_EMPTY ->
          observation.singleUseTicketsNavigationBoundsFor(request.target)
        else -> observation.backBounds
      } ?: return ticketVisualActionTerminal(request, false, "failed", "ticket_action_visual_target_ambiguous")
      updateTicketVisualActionPhase(request, "applying_visual_navigation")
      val navigationJournal = TicketVisualActionJournalState(
        actionId = request.actionId,
        target = request.target.wireName,
        phase = "navigation_dispatched",
        intendedAnchor = selectedAnchor.ifBlank { requiredAnchor },
        navigationFromState = observation.state.wireName,
        navigationToState = if (observation.state == TicketVisualPhoneState.TICKET_LIST) {
          desiredState?.wireName.orEmpty()
        } else {
          TicketVisualPhoneState.TICKET_LIST.wireName
        },
        navigationAnchor = if (observation.state == TicketVisualPhoneState.TICKET_LIST) {
          selectedAnchorForTransition
        } else {
          ""
        }
      )
      if (!panelLease.beforeMutationAllowed()) {
        return ticketVisualActionTerminal(
          request,
          false,
          if (panelLease.snapshot().mutationMayHaveDispatched) "needs_attention" else "failed",
          "ticket_action_panel_dark_preempted",
          observation
        )
      }
      if (!persistTicketVisualActionJournal(navigationJournal)) {
        return ticketVisualActionTerminal(
          request,
          false,
          "failed",
          "ticket_action_navigation_journal_unproved",
          observation
        )
      }
      // A failed root result can arrive after Android accepted the tap. In either case this is
      // now one dispatched physical attempt: observe its typed result and never resend it.
      panelLease.markMutationMayHaveDispatched()
      tapTicketVisualProbeBounds(tapBounds, "ticket_action_v3_${request.target.wireName}")
      mutations += 1
      val postNavigationObservation = awaitStableTicketVisualActionObservation(
        "ticket_action_v3_after_navigation_$mutations",
        TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS,
        convergenceExtensionMillis = TICKET_ACTION_V3_FINAL_CONVERGENCE_MILLIS,
        captureRecoveryBudget = captureRecoveryBudget
      )
      observation = postNavigationObservation
        ?: return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          "ticket_action_visual_transition_unproved"
        )
      if (!ticketVisualJournalReconciled(navigationJournal, request, observation)) {
        return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          "ticket_action_navigation_dispatch_uncertain",
          observation
        )
      }
      if (observation.state == TicketVisualPhoneState.TICKET_LIST) {
        controlCodeRecoveryListProved = true
      }
      if (observation.state in setOf(
          TicketVisualPhoneState.UNACTIVATED_DETAIL,
          TicketVisualPhoneState.ACTIVATED_DETAIL
      ) && navigationJournal.navigationAnchor.isNotBlank()
      ) {
        // The card and geometry agreed in two fresh list frames, and this is the typed detail
        // reached by that one tap. Preserve both identities: the card anchor is used across views,
        // while the detail anchor still guards a later register_current action.
        selectedDetailAnchor = observation.currentAnchor
        observation = if (request.target == TicketVisualActionTarget.SHOW_RECENT_ACTIVATED) {
          ticketVisualObservationAfterRecentActivatedSelection(
            observation,
            navigationJournal.navigationAnchor,
            ticketVisualSwitchAnchors.recentActivatedAnchor
          )
        } else {
          ticketVisualObservationAfterCardSelection(observation, navigationJournal.navigationAnchor)
        }
      }
      if (!persistTicketVisualActionJournal(
          navigationJournal.copy(
            phase = "navigation_reconciled",
            intendedAnchor = selectedAnchor
          )
        )
      ) {
        return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          "ticket_action_navigation_journal_unproved_after_dispatch",
          observation
        )
      }
      ticketVisualActionLatestNotDetectedAfterTimeTab(
        request,
        TicketVisualPhoneState.fromWireName(navigationJournal.navigationFromState),
        observation
      )?.let { return it }
    }

    val expectedState = if (request.target == TicketVisualActionTarget.SHOW_RECENT_ACTIVATED) {
      TicketVisualPhoneState.ACTIVATED_DETAIL
    } else {
      TicketVisualPhoneState.UNACTIVATED_DETAIL
    }
    if (observation.state != expectedState) {
      return ticketVisualActionTerminal(request, false, "failed", "ticket_action_target_not_reached", observation)
    }
    val exactRegistrationProof = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
      val registrationProofGate = ticketRegistrationProofForCurrentVisualAction(
        proof = currentTicketRegistrationProof,
        request = request,
        observation = observation
      )
      registrationProofGate.proof ?: return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        registrationProofGate.failureReason ?: "ticket_action_interaction_revision_unproved",
        observation
      )
    } else {
      null
    }
    val expectedAnchor = when (request.target) {
      TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> ticketVisualSwitchAnchors.recentActivatedAnchor
      TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED -> ticketVisualSwitchAnchors.latestUnactivatedAnchor
      TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
      TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER,
      TicketVisualActionTarget.REDETECT_LATEST -> selectedAnchor
      TicketVisualActionTarget.REGISTER_CURRENT -> exactRegistrationProof?.ticketAnchor.orEmpty()
      TicketVisualActionTarget.PROVE_CURRENT -> ""
    }
    if (expectedAnchor.isBlank()) {
      return ticketVisualActionTerminal(request, false, "failed", "ticket_action_selected_anchor_missing", observation)
    }
    if (observation.currentAnchor.isBlank() && exactRegistrationProof == null) {
      return ticketVisualActionTerminal(request, false, "failed", "ticket_action_transition_anchor_missing", observation)
    }
    if (exactRegistrationProof == null && observation.currentAnchor != expectedAnchor) {
      return ticketVisualActionTerminal(request, false, "failed", "ticket_action_selected_anchor_conflict", observation)
    }
    val provenTicketAnchor = ticketVisualProvenTicketAnchor(observation, exactRegistrationProof)
    if (observation.state == TicketVisualPhoneState.UNACTIVATED_DETAIL && provenTicketAnchor.isNotBlank()) {
      updateTicketVisualSwitchAnchors(ticketVisualSwitchAnchors.copy(
        latestUnactivatedAnchor = provenTicketAnchor
      ))
    }
    var actionRegistrationProof: TicketRegistrationProof? = null
    if (request.target == TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED ||
      request.target == TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER ||
      request.target == TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED ||
      request.target == TicketVisualActionTarget.REDETECT_LATEST
    ) {
      if (selectedDetailAnchor.isBlank()) {
        return ticketVisualActionTerminal(
          request,
          false,
          "failed",
          "ticket_action_detail_identity_unproved",
          observation
        )
      }
      val slider = observation.sliderBounds
        ?: return ticketVisualActionTerminal(request, false, "failed", "ticket_action_slider_unproved", observation)
      val deviceBounds = ticketVisualProbeBoundsToDevice(slider)
      if (command.revision.isBlank() || deviceBounds.width < 220 || deviceBounds.height < 20) {
        return ticketVisualActionTerminal(request, false, "failed", "ticket_action_interaction_proof_invalid", observation)
      }
      val registrationWatermark = awaitTicketVisualActionFrameWatermark(
        "ticket_action_v3_unactivated_registration_proof"
      ) ?: return ticketVisualActionTerminal(
        request,
        false,
        "needs_attention",
        "ticket_action_frame_watermark_unproved",
        observation
      )
      val unboundRegistrationProof = TicketRegistrationProof(
          status = "unactivated_ready",
          reason = "ticket_action_visual_proof",
          interactionRevision = command.revision,
          streamEpoch = registrationWatermark.first,
          frameSequence = registrationWatermark.second,
          phoneDisplayWidth = resources.displayMetrics.widthPixels,
          phoneDisplayHeight = resources.displayMetrics.heightPixels,
          provedAtUptimeMillis = SystemClock.elapsedRealtime(),
          ticketAnchor = observation.currentAnchor,
          detailAnchor = selectedDetailAnchor,
          sliderLeft = deviceBounds.left,
          sliderTop = deviceBounds.top,
          sliderRight = deviceBounds.right,
          sliderBottom = deviceBounds.bottom
      )
      actionRegistrationProof = bindTicketRegistrationProofToCurrentWatermark(
        unboundRegistrationProof,
        registrationWatermark
      ) ?: return ticketVisualActionTerminal(
        request,
        false,
        "needs_attention",
        "ticket_action_frame_watermark_unproved",
        observation
      )
    }
    if (!request.target.activatesTicket) {
      if (recoveringControlCodeCheckpoint) {
        val recovered = completeControlExitCleanup(
          reason = "ticket_action_visual_reopen",
          detectedState = TicketViviRecoveryState.CONTROL_CODE_RESULT.name,
          closeAction = "visual_list_reopen",
          startedAtMillis = SystemClock.elapsedRealtime(),
          verificationResult = "ticket_action_visual_reopen",
          freshFrameRequested = false
        )
        if (!recovered) {
          return ticketVisualActionTerminal(
            request,
            false,
            "needs_attention",
            "ticket_action_visual_unproved",
            observation
          )
        }
      }
      return ticketVisualActionSuccess(
        request,
        if (request.target == TicketVisualActionTarget.REDETECT_LATEST) {
          "ticket_action_latest_redetected"
        } else {
          "ticket_action_target_visible"
        },
        observation,
        actionRegistrationProof
      )
    }
    return activateTicketFromVisualAction(
      command,
      request,
      observation,
      exactRegistrationProof,
      actionRegistrationProof,
      panelLease,
      generation
    )
  }

  private suspend fun proveCurrentTicketVisualAction(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    observation: TicketVisualActionObservation
  ): TicketVisualActionSnapshot {
    if (observation.state != TicketVisualPhoneState.UNACTIVATED_DETAIL) {
      val reason = when (observation.state) {
        TicketVisualPhoneState.ACTIVATED_DETAIL -> "ticket_action_current_activated"
        TicketVisualPhoneState.TICKET_LIST -> "ticket_action_current_list"
        TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY ->
          "ticket_action_current_tickets_single_use_empty"
        TicketVisualPhoneState.TICKETS_TIME_EMPTY ->
          "ticket_action_current_tickets_time_empty"
        TicketVisualPhoneState.VIVI_HOME -> "ticket_action_current_vivi_home"
        TicketVisualPhoneState.VIVI_PROFILE -> "ticket_action_current_vivi_profile"
        TicketVisualPhoneState.VIVI_OTHER_TAB -> "ticket_action_current_vivi_other_tab"
        TicketVisualPhoneState.LOGIN_REQUIRED -> "ticket_action_current_login_required"
        TicketVisualPhoneState.BLOCKED -> "ticket_action_current_blocked"
        TicketVisualPhoneState.UNKNOWN -> "ticket_action_current_unknown"
        TicketVisualPhoneState.UNACTIVATED_DETAIL -> error("handled above")
      }
      return ticketVisualActionSuccess(request, reason, observation)
    }
    val slider = observation.sliderBounds
      ?: return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        "ticket_action_slider_unproved",
        observation
      )
    if (observation.currentAnchor.isBlank() || command.revision.isBlank()) {
      return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        "ticket_action_current_detail_identity_unproved",
        observation
      )
    }
    val deviceBounds = ticketVisualProbeBoundsToDevice(slider)
    if (deviceBounds.width < 220 || deviceBounds.height < 20) {
      return ticketVisualActionTerminal(
        request,
        false,
        "failed",
        "ticket_action_slider_geometry_invalid",
        observation
      )
    }
    val proof = TicketRegistrationProof(
      status = "unactivated_ready",
      reason = "ticket_action_visual_current_proof",
      interactionRevision = command.revision,
      streamEpoch = 0L,
      frameSequence = 0L,
      phoneDisplayWidth = resources.displayMetrics.widthPixels,
      phoneDisplayHeight = resources.displayMetrics.heightPixels,
      provedAtUptimeMillis = SystemClock.elapsedRealtime(),
      ticketAnchor = observation.currentAnchor,
      detailAnchor = observation.currentAnchor,
      sliderLeft = deviceBounds.left,
      sliderTop = deviceBounds.top,
      sliderRight = deviceBounds.right,
      sliderBottom = deviceBounds.bottom
    )
    return ticketVisualActionSuccess(
      request,
      "ticket_action_current_unactivated_proved",
      observation,
      proof
    )
  }

  private suspend fun activateTicketFromVisualAction(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    observation: TicketVisualActionObservation,
    boundRegistrationProof: TicketRegistrationProof?,
    preparedRegistrationProof: TicketRegistrationProof?,
    panelLease: TicketActionPanelDarkLease,
    generation: Long
  ): TicketVisualActionSnapshot {
    val exactRegistrationProof = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
      val registrationProofGate = ticketRegistrationProofForCurrentVisualAction(
        proof = currentTicketRegistrationProof,
        request = request,
        observation = observation
      )
      registrationProofGate.proof?.takeIf { it == boundRegistrationProof }
        ?: return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          registrationProofGate.failureReason ?: "ticket_action_interaction_revision_unproved",
          observation,
          terminalPhase = "not_dispatched"
        )
    } else {
      null
    }
    val provenObservation = exactRegistrationProof?.let { proof ->
      observation.copy(currentAnchor = proof.ticketAnchor)
    } ?: observation
    val provenDetailAnchor = exactRegistrationProof?.detailAnchor.orEmpty()
      .ifBlank { preparedRegistrationProof?.detailAnchor.orEmpty() }
      .ifBlank { observation.currentAnchor }
    val revision = exactRegistrationProof?.interactionRevision ?: command.revision
    if (provenDetailAnchor.isBlank()) {
      return ticketVisualActionTerminal(
        request,
        false,
        "needs_attention",
        "ticket_action_detail_identity_unproved",
        observation,
        terminalPhase = "not_dispatched"
      )
    }
    val prior = ticketActivationCheckpoint(command.id, revision, request.attemptId)
    when (prior?.stage) {
      TicketActivationCheckpointStage.ACTIVATION_PROVEN -> return ticketVisualActivationSuccess(
        request,
        observation,
        revision,
        prior.activationRevision,
        checkpointRecovery = true
      )
      TicketActivationCheckpointStage.ACTIVATION_DISPATCHING,
      TicketActivationCheckpointStage.NEEDS_ATTENTION -> {
        prior.let(ticketActivationCheckpointStore::recordNeedsAttention)
        return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_activation_dispatch_uncertain",
          observation, terminalPhase = "outcome_unknown"
        )
      }
      TicketActivationCheckpointStage.NO_TRANSITION_PROVEN -> {
        return ticketVisualActionTerminal(
          request,
          false,
          "needs_attention",
          ticketActivationNoTransitionTerminalReason(prior),
          observation,
          terminalPhase = ticketActivationNoTransitionTerminalPhase(prior)
        )
      }
      TicketActivationCheckpointStage.FRESH_TICKET_PROVEN -> {
        return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_activation_checkpoint_unproved",
          observation, terminalPhase = "not_dispatched"
        )
      }
      null -> Unit
    }

    var checkpoint: TicketActivationCheckpoint? = null
    for (ordinal in 1..2) {
      updateTicketVisualActionPhase(
        request,
        if (ordinal == 1) "preparing_registration_input" else "preparing_registration_retry"
      )
      val preparation = prepareExactTicketActivationDispatch(
        request = request,
        expectedDetailAnchor = provenDetailAnchor,
        generation = generation,
        panelLease = panelLease,
        resumeVivi = true
      )
      val prepared = preparation.prepared
      if (prepared == null) {
        // No new dispatch was admitted. Preserve FRESH_TICKET_PROVEN (first stroke) or
        // NO_TRANSITION_PROVEN (retry) so exact server settlement can retire that certainty.
        return ticketVisualActionTerminal(
          request = request,
          ok = false,
          status = "needs_attention",
          reason = if (ordinal == 1) preparation.reason else "ticket_action_retry_not_dispatched",
          observation = observation,
          terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
        )
      }
      if (checkpoint == null) {
        checkpoint = recordTicketActivationFreshProof(command.id, revision, request.attemptId)
          ?: return ticketVisualActionTerminal(
            request, false, "needs_attention", "ticket_action_activation_checkpoint_unproved",
            prepared.observation, terminalPhase = "not_dispatched"
          )
      }
      if (!ticketActivationPreparationStillCurrent(prepared, generation, panelLease)) {
        // The durable stage still proves that no stroke for this ordinal was admitted.
        return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_exact_input_fence_changed",
          prepared.observation,
          terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
        )
      }
      val physicalTouchFence = ticketActivationPhysicalTouchFence(
        PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
      ) ?: return ticketVisualActionTerminal(
        request, false, "needs_attention", "ticket_action_panel_dark_preempted",
        prepared.observation,
        terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
      )
      updateTicketVisualActionPhase(
        request,
        if (ordinal == 1) "dispatching_registration_gesture" else "dispatching_registration_retry"
      )
      val dispatching = recordTicketActivationDispatching(requireNotNull(checkpoint), ordinal)
        ?: return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_activation_dispatch_checkpoint_unproved",
          prepared.observation,
          terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
        )
      checkpoint = dispatching
      if (!ticketActivationPreparationStillCurrent(prepared, generation, panelLease) ||
        !ticketActivationPhysicalTouchFenceIsCurrent(
          physicalTouchFence,
          PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
        )
      ) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_exact_input_fence_changed",
          prepared.observation,
          terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
        )
      }
      val contract = ticketSliderGestureContract(prepared.gestureBounds)
      val y = (prepared.gestureBounds.top + prepared.gestureBounds.bottom) / 2
      panelLease.markMutationMayHaveDispatched()
      val gestureResult = PhoneAutomationServiceBridge.performTicketSliderFullStroke(
        expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
        startX = contract.startX,
        startY = y,
        endX = contract.endX,
        endY = y,
        durationMillis = contract.durationMillis,
        timeoutMillis = TICKET_SLIDER_GESTURE_TIMEOUT_MILLIS,
        expectedInputFence = prepared.inputFence
      )
      if (gestureResult != TicketSliderGestureDispatchResult.COMPLETED) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return ticketVisualActionTerminal(
          request = request,
          ok = false,
          status = "needs_attention",
          reason = if (gestureResult == TicketSliderGestureDispatchResult.REJECTED) {
            "ticket_action_gesture_rejected"
          } else {
            "ticket_action_gesture_completion_uncertain"
          },
          observation = prepared.observation,
          terminalPhase = "outcome_unknown"
        )
      }
      val postGestureLeaseReady = panelLease.beforeMutationAllowed()
      val postGestureLease = panelLease.snapshot()
      if (!postGestureLeaseReady || postGestureLease.physicalTouchPreempted) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return ticketVisualActionTerminal(
          request = request,
          ok = false,
          status = "needs_attention",
          reason = "ticket_action_activation_outcome_unknown",
          observation = prepared.observation,
          terminalPhase = "outcome_unknown"
        )
      }
      val postGestureObservation = awaitStableTicketVisualActionObservation(
        "ticket_action_v3_activation_result_$ordinal",
        TICKET_ACTION_V3_ACTIVATION_PROOF_TIMEOUT_MILLIS,
        currentOnly = true
      )
      val postProofLeaseReady = panelLease.beforeMutationAllowed()
      val postProofPhysicalTouchCurrent = ticketActivationPhysicalTouchFenceIsCurrent(
        physicalTouchFence,
        PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
      )
      if (!postProofLeaseReady || !postProofPhysicalTouchCurrent) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return ticketVisualActionTerminal(
          request = request,
          ok = false,
          status = "needs_attention",
          reason = "ticket_action_activation_outcome_unknown",
          observation = postGestureObservation,
          terminalPhase = "outcome_unknown"
        )
      }
      val resultGenerationCurrent = ticketActionV3Generation == generation &&
        rootHardwareH264CaptureEngine.snapshot().restartCount == prepared.captureRestartCount
      val activationObservation = ticketVisualActivationObservationAfterCompletedGesture(
        postGestureObservation,
        provenObservation.currentAnchor
      )
      val activated = activationObservation?.takeIf {
        resultGenerationCurrent && it.state == TicketVisualPhoneState.ACTIVATED_DETAIL
      }
      if (activated != null) {
        val activationRevision = ticketActivationRevision(command.id, revision, request.attemptId)
        rememberTicketVisualActivatedAnchor(activated)
        if (recordTicketActivationProven(dispatching, activationRevision) == null) {
          return ticketVisualActionTerminal(
            request, false, "needs_attention", "ticket_action_activation_proven_checkpoint_unproved",
            activated, terminalPhase = "outcome_unknown"
          )
        }
        return ticketVisualActivationSuccess(
          request,
          activated,
          revision,
          activationRevision,
          updateActivatedAnchor = false
        )
      }
      val exactNoTransition = resultGenerationCurrent &&
        postGestureObservation?.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
        postGestureObservation.currentAnchor == provenDetailAnchor
      if (!exactNoTransition) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_post_gesture_visual_unproved",
          postGestureObservation, terminalPhase = "outcome_unknown"
        )
      }
      val noTransition = ticketActivationCheckpointStore.recordNoTransitionProven(dispatching)
        ?: return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_no_transition_checkpoint_unproved",
          postGestureObservation, terminalPhase = "outcome_unknown"
        )
      checkpoint = noTransition
      if (ordinal == 2) {
        return ticketVisualActionTerminal(
          request, false, "needs_attention", "ticket_action_gesture_completed_no_transition",
          postGestureObservation, terminalPhase = "no_transition"
        )
      }
    }
    error("ticket activation attempt loop exhausted")
  }

  private suspend fun prepareExactTicketActivationDispatch(
    request: TicketVisualActionRequest,
    expectedDetailAnchor: String,
    generation: Long,
    panelLease: TicketActionPanelDarkLease,
    resumeVivi: Boolean
  ): TicketActivationPreparationResult {
    if (resumeVivi) {
      if (!panelLease.beforeMutationAllowed()) {
        return TicketActivationPreparationResult(reason = "ticket_action_panel_dark_preempted")
      }
      launchViviForWake("ticket_action_v3_retry:${request.target.wireName}")
      ensureRootHardwareH264CaptureIfPossible()
      rootHardwareH264CaptureEngine.requestImmediateRefresh("ticket_action_v3_retry_resume")
    }
    if (!PhoneAutomationServiceBridge.awaitAccessibilityConnection(
        TICKET_SLIDER_ACCESSIBILITY_RECONNECT_TIMEOUT_MILLIS
      )
    ) {
      return TicketActivationPreparationResult(reason = "ticket_action_accessibility_unavailable")
    }
    val inputFence = PhoneAutomationServiceBridge.awaitStableTicketInputFence(
      expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
      timeoutMillis = TICKET_SLIDER_ACCESSIBILITY_RECONNECT_TIMEOUT_MILLIS
    ) ?: return TicketActivationPreparationResult(reason = "ticket_action_input_window_unproved")
    val actionMutationGeneration = ticketActionV3MutationGeneration
    val captureRestartCount = rootHardwareH264CaptureEngine.snapshot().restartCount
    rootHardwareH264CaptureEngine.requestImmediateRefresh("ticket_action_v3_exact_input_proof")
    val freshObservation = awaitStableTicketVisualActionObservation(
      reason = "ticket_action_v3_exact_input_proof",
      timeoutMillis = TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS,
      currentOnly = true
    ) ?: return TicketActivationPreparationResult(reason = "ticket_action_visual_unproved")
    if (freshObservation.state != TicketVisualPhoneState.UNACTIVATED_DETAIL ||
      freshObservation.currentAnchor != expectedDetailAnchor
    ) {
      return TicketActivationPreparationResult(reason = "ticket_action_detail_identity_conflict")
    }
    val visualSlider = freshObservation.sliderBounds?.let(::ticketVisualProbeBoundsToDevice)
      ?: return TicketActivationPreparationResult(reason = "ticket_action_slider_unproved")
    val firstHierarchy = fastTicketRegistrationHierarchy("ticket_action_v3_semantic_slider_1")
    val firstSemantic = TicketViviPageEnforcer.ticketRegistrationSliderBoundsForHierarchy(firstHierarchy)
      ?: return TicketActivationPreparationResult(reason = "ticket_action_slider_unproved")
    delay(TICKET_ACTION_V3_PROBE_GAP_MILLIS)
    val secondHierarchy = fastTicketRegistrationHierarchy("ticket_action_v3_semantic_slider_2")
    val secondSemantic = TicketViviPageEnforcer.ticketRegistrationSliderBoundsForHierarchy(secondHierarchy)
      ?.takeIf { it == firstSemantic }
      ?: return TicketActivationPreparationResult(reason = "ticket_action_slider_unproved")
    val gestureBounds = ticketSliderGestureBoundsAfterVisualProof(
      hierarchyBounds = secondSemantic,
      visualBounds = visualSlider,
      displayWidth = resources.displayMetrics.widthPixels,
      displayHeight = resources.displayMetrics.heightPixels
    ) ?: return TicketActivationPreparationResult(reason = "ticket_action_slider_geometry_invalid")
    val watermark = awaitTicketVisualActionFrameWatermark("ticket_action_v3_exact_input_watermark")
      ?: return TicketActivationPreparationResult(reason = "ticket_action_frame_watermark_unproved")
    val prepared = TicketActivationDispatchPreparation(
      observation = freshObservation,
      gestureBounds = gestureBounds,
      watermark = watermark,
      inputFence = inputFence,
      captureRestartCount = captureRestartCount,
      actionMutationGeneration = actionMutationGeneration
    )
    if (ticketActionV3MutationGeneration != actionMutationGeneration ||
      !ticketActivationPreparationStillCurrent(prepared, generation, panelLease)
    ) {
      return TicketActivationPreparationResult(reason = "ticket_action_exact_input_fence_changed")
    }
    return TicketActivationPreparationResult(prepared = prepared, reason = "")
  }

  private suspend fun ticketActivationPreparationStillCurrent(
    prepared: TicketActivationDispatchPreparation,
    generation: Long,
    panelLease: TicketActionPanelDarkLease
  ): Boolean {
    return ticketActionV3Generation == generation &&
      ticketActionV3MutationGeneration == prepared.actionMutationGeneration &&
      rootHardwareH264CaptureEngine.snapshot().restartCount == prepared.captureRestartCount &&
      synchronized(encoderLock) { ticketVisualActionWatermarkCurrentLocked(prepared.watermark) } &&
      PhoneAutomationServiceBridge.ticketInputFenceIsCurrent(prepared.inputFence) &&
      panelLease.beforeMutationAllowed()
  }

  private suspend fun ticketVisualActivationSuccess(
    request: TicketVisualActionRequest,
    observation: TicketVisualActionObservation,
    interactionRevision: String,
    activationRevision: String,
    updateActivatedAnchor: Boolean = true,
    checkpointRecovery: Boolean = false
  ): TicketVisualActionSnapshot {
    val checkpointVisualMatches = !checkpointRecovery ||
      ticketVisualCheckpointMatchesActivatedAnchor(observation, ticketVisualSwitchAnchors)
    if (updateActivatedAnchor && !checkpointRecovery && checkpointVisualMatches) {
      rememberTicketVisualActivatedAnchor(observation)
    }
    val publishedObservation = if (checkpointVisualMatches) observation else observation.copy(
      state = TicketVisualPhoneState.UNKNOWN,
      currentAnchor = ""
    )
    val watermark = awaitTicketVisualActionFrameWatermark(
      "ticket_action_v3_activation_terminal"
    ) ?: return ticketVisualActionTerminal(
      request = request,
      ok = false,
      status = "needs_attention",
      reason = "ticket_action_frame_watermark_unproved",
      observation = publishedObservation,
      terminalPhase = "outcome_unknown"
    )
    val snapshot = ticketVisualActionTerminal(
      request = request,
      ok = true,
      status = "succeeded",
      reason = "ticket_action_registered",
      observation = publishedObservation,
      proofWatermark = watermark,
      terminalPhase = "outcome_unknown".takeIf { !checkpointVisualMatches }
    ).let { value ->
      value.copy(
        interactionRevision = interactionRevision,
        activationRevision = activationRevision.takeIf { value.ok }.orEmpty(),
        activationAttemptId = request.attemptId,
        switchAvailable = value.switchAvailable && checkpointVisualMatches,
        switchExpiresAt = value.switchExpiresAt.takeIf { checkpointVisualMatches }.orEmpty()
      )
    }
    return snapshot
  }

  private fun rememberTicketVisualActivatedAnchor(observation: TicketVisualActionObservation) {
    if (observation.state != TicketVisualPhoneState.ACTIVATED_DETAIL || observation.currentAnchor.isBlank()) return
    updateTicketVisualSwitchAnchors(ticketVisualSwitchAnchors.copy(
      recentActivatedAnchor = observation.currentAnchor
    ))
  }

  private suspend fun awaitStableTicketVisualActionObservation(
    reason: String,
    timeoutMillis: Long,
    convergenceExtensionMillis: Long = 0L,
    allowUnknown: Boolean = false,
    currentOnly: Boolean = false,
    captureRecoveryBudget: TicketVisualCaptureRecoveryBudget? = null
  ): TicketVisualActionObservation? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val baselineStreamEpoch = streamEpoch
    val baselineRestartCount = rootHardwareH264CaptureEngine.snapshot().restartCount
    val boundedConvergenceExtensionMillis = convergenceExtensionMillis.coerceAtLeast(0L)
    var deadline = startedAtMillis + timeoutMillis
    var convergenceExtended = boundedConvergenceExtensionMillis == 0L
    var completedProbeSeen = false
    val consensus = TicketVisualObservationConsensus()
    var consensusStreamEpoch = baselineStreamEpoch
    var consensusRestartCount = baselineRestartCount
    while (true) {
      val nowMillis = SystemClock.elapsedRealtime()
      if (nowMillis >= deadline) {
        val health = rootHardwareH264CaptureEngine.snapshot()
        if (!convergenceExtended) {
          convergenceExtended = true
          deadline += boundedConvergenceExtensionMillis
          if (streamEpoch != consensusStreamEpoch || health.restartCount != consensusRestartCount) {
            consensus.reset()
            consensusStreamEpoch = streamEpoch
            consensusRestartCount = health.restartCount
          }
          val recoverAtBoundary = captureRecoveryBudget?.consumeIfCaptureWasInterrupted(
            baselineStreamEpoch = baselineStreamEpoch,
            currentStreamEpoch = streamEpoch,
            baselineRestartCount = baselineRestartCount,
            currentRestartCount = health.restartCount,
            completedProbeSeen = completedProbeSeen
          ) == true
          if (recoverAtBoundary) {
            // Recovery only restores the proof source. The already-journaled physical mutation is
            // never repeated, and a same-generation candidate remains available for convergence.
            ensureRootHardwareH264CaptureIfPossible()
            rootHardwareH264CaptureEngine.requestImmediateRefresh(
              "ticket_action_v3_capture_recovery"
            )
          }
          continue
        }
        val extend = captureRecoveryBudget?.consumeIfCaptureWasInterrupted(
          baselineStreamEpoch = baselineStreamEpoch,
          currentStreamEpoch = streamEpoch,
          baselineRestartCount = baselineRestartCount,
          currentRestartCount = health.restartCount,
          completedProbeSeen = completedProbeSeen
        ) == true
        if (!extend) return null
        deadline = startedAtMillis + maxOf(
          timeoutMillis + boundedConvergenceExtensionMillis,
          TICKET_ACTION_V3_CAPTURE_RECOVERY_TIMEOUT_MILLIS
        )
        if (deadline <= nowMillis) return null
        // This only restores the proof source. It never repeats the tap or registration drag.
        consensus.reset()
        ensureRootHardwareH264CaptureIfPossible()
        rootHardwareH264CaptureEngine.requestImmediateRefresh(
          "ticket_action_v3_capture_recovery"
        )
        continue
      }
      val beforeProbeStreamEpoch = streamEpoch
      val beforeProbeRestartCount = rootHardwareH264CaptureEngine.snapshot().restartCount
      if (beforeProbeStreamEpoch != consensusStreamEpoch ||
        beforeProbeRestartCount != consensusRestartCount
      ) {
        consensus.reset()
        consensusStreamEpoch = beforeProbeStreamEpoch
        consensusRestartCount = beforeProbeRestartCount
      }
      val started = SystemClock.elapsedRealtime()
      val probeId = if (currentOnly) {
        rootHardwareH264CaptureEngine.requestTicketCurrentVisualProbe(reason)
      } else {
        rootHardwareH264CaptureEngine.requestTicketActionVisualProbe(reason)
      }
      if (probeId == null) {
        delay(TICKET_ACTION_V3_PROBE_GAP_MILLIS)
        continue
      }
      val remainingMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val probe = waitForFreshControlCodeVisualProbe(
        started,
        probeId,
        minOf(TICKET_ACTION_V3_PROBE_WAIT_MILLIS, remainingMillis)
      )
      val afterProbeStreamEpoch = streamEpoch
      val afterProbeRestartCount = rootHardwareH264CaptureEngine.snapshot().restartCount
      if (afterProbeStreamEpoch != beforeProbeStreamEpoch ||
        afterProbeRestartCount != beforeProbeRestartCount
      ) {
        // Never combine evidence across a restarted capture source, even when both pictures look
        // alike. The next two probes must agree inside the replacement source generation.
        consensus.reset()
        consensusStreamEpoch = afterProbeStreamEpoch
        consensusRestartCount = afterProbeRestartCount
        delay(TICKET_ACTION_V3_PROBE_GAP_MILLIS)
        continue
      }
      if (probe != null) completedProbeSeen = true
      val current = probe?.ticketActionObservation
      if (current != null && current.probeId == probeId) {
        consensus.offer(current, allowUnknown)?.let { return it }
      }
      delay(TICKET_ACTION_V3_PROBE_GAP_MILLIS)
    }
  }

  /**
   * Binds a terminal visual proof to a real encoded frame after that proof. A classifier result
   * can arrive before MediaCodec emits the first frame of a restarted epoch, so publishing the
   * service counters directly can otherwise expose sequence zero to the browser.
   */
  private suspend fun awaitTicketVisualActionFrameWatermark(reason: String): Pair<Long, Long>? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val starting = synchronized(encoderLock) { streamEpoch to frameSequence }
    requestKeyFrame(reason)
    val deadlineMillis = startedAtMillis + TICKET_ACTION_V3_FRAME_WATERMARK_TIMEOUT_MILLIS
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      val watermark = synchronized(encoderLock) {
        val currentEpoch = streamEpoch
        val currentSequence = frameSequence
        latestKeyFrame?.takeIf { keyFrame ->
          currentEpoch > 0L && currentSequence > 0L &&
            keyFrame.epoch == currentEpoch && keyFrame.sequence > 0L &&
            (currentEpoch != starting.first || keyFrame.sequence > starting.second)
        }?.let { keyFrame -> keyFrame.epoch to keyFrame.sequence }
      }
      if (watermark != null) return watermark
      delay(TICKET_ACTION_V3_PROBE_GAP_MILLIS)
    }
    recordTicketEvent(
      "ticket_action_frame_watermark_unproved",
      "reason=$reason epoch=$streamEpoch sequence=$frameSequence"
    )
    return null
  }

  private fun ticketVisualActionWatermarkCurrentLocked(
    watermark: Pair<Long, Long>
  ): Boolean = watermark.first > 0L && watermark.second > 0L &&
    streamEpoch == watermark.first && frameSequence >= watermark.second &&
    latestKeyFrame?.let { keyFrame ->
      keyFrame.epoch == watermark.first && keyFrame.sequence >= watermark.second
    } == true

  private fun TicketVisualActionObservation.toRecoveryState(): TicketViviRecoveryState = when (state) {
    TicketVisualPhoneState.ACTIVATED_DETAIL,
    TicketVisualPhoneState.UNACTIVATED_DETAIL -> TicketViviRecoveryState.TICKET_DETAIL
    TicketVisualPhoneState.TICKET_LIST -> TicketViviRecoveryState.TICKET_LIST_WITH_CARD
    TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
    TicketVisualPhoneState.TICKETS_TIME_EMPTY -> TicketViviRecoveryState.TICKET_LIST_EMPTY
    TicketVisualPhoneState.VIVI_HOME,
    TicketVisualPhoneState.VIVI_PROFILE,
    TicketVisualPhoneState.VIVI_OTHER_TAB -> TicketViviRecoveryState.OTHER_VIVI_TAB
    TicketVisualPhoneState.LOGIN_REQUIRED -> TicketViviRecoveryState.LOGIN_REQUIRED
    TicketVisualPhoneState.BLOCKED -> TicketViviRecoveryState.DISMISSIBLE_BLOCKER
    TicketVisualPhoneState.UNKNOWN -> TicketViviRecoveryState.UNKNOWN_VIVI
  }

  private suspend fun tapTicketVisualProbeBounds(bounds: TicketVisualProbeBounds, reason: String): Boolean {
    val mapped = ticketVisualProbeBoundsToDevice(bounds)
    if (mapped.width <= 0 || mapped.height <= 0) return false
    return runFastRecoveryInput(
      "input tap ${(mapped.left + mapped.right) / 2} ${(mapped.top + mapped.bottom) / 2}",
      reason
    ).ok
  }

  private fun ticketVisualProbeBoundsToDevice(bounds: TicketVisualProbeBounds): TicketViviGraphicBounds {
    val displayWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val displayHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
    return TicketCaptureGeometry.mapProbeBoundsToDevice(
      bounds = bounds,
      probeWidth = TICKET_ACTION_V3_SAMPLE_WIDTH,
      probeHeight = TICKET_ACTION_V3_SAMPLE_HEIGHT,
      sourceWidth = displayWidth,
      sourceHeight = displayHeight
    )
  }

  private fun updateTicketVisualActionPhase(request: TicketVisualActionRequest, phase: String) {
    synchronized(ticketActionV3Lock) {
      if (ticketActionV3Snapshot.actionId == request.actionId) {
        ticketActionV3Snapshot = ticketActionV3Snapshot.copy(phase = phase, reason = phase)
      }
    }
  }

  private suspend fun ticketVisualActionLatestNotDetectedAfterTimeTab(
    request: TicketVisualActionRequest,
    navigationFromState: TicketVisualPhoneState,
    observation: TicketVisualActionObservation
  ): TicketVisualActionSnapshot? {
    if (!ticketVisualRedetectLatestNotDetectedObservation(
        request.target,
        navigationFromState,
        observation
      )
    ) return null
    val watermark = awaitTicketVisualActionFrameWatermark(
      "ticket_action_v3_redetect_latest_not_detected"
    ) ?: return ticketVisualActionTerminal(
      request,
      false,
      "needs_attention",
      "ticket_action_frame_watermark_unproved",
      observation
    )
    if (!ticketVisualRedetectLatestNotDetectedProof(
        request.target,
        navigationFromState,
        observation,
        watermark.first,
        watermark.second
      )
    ) {
      return ticketVisualActionTerminal(
        request,
        false,
        "needs_attention",
        "ticket_action_visual_unproved",
        observation
      )
    }
    return ticketVisualActionTerminal(
      request = request,
      ok = false,
      status = "failed",
      reason = "ticket_action_latest_not_detected",
      observation = observation,
      proofWatermark = watermark,
      expectedNegativeProof = true
    )
  }

  private suspend fun ticketVisualActionSuccess(
    request: TicketVisualActionRequest,
    reason: String,
    observation: TicketVisualActionObservation,
    registrationProof: TicketRegistrationProof? = null
  ): TicketVisualActionSnapshot {
    val watermark = awaitTicketVisualActionFrameWatermark(
      "ticket_action_v3_${request.target.wireName}_terminal"
    ) ?: return ticketVisualActionTerminal(
      request,
      false,
      "needs_attention",
      "ticket_action_frame_watermark_unproved",
      observation
    )
    if (registrationProof != null &&
      bindTicketRegistrationProofToCurrentWatermark(registrationProof, watermark) == null
    ) {
      return ticketVisualActionTerminal(
        request,
        false,
        "needs_attention",
        "ticket_action_frame_watermark_unproved",
        observation
      )
    }
    val publishesSliderRegion = registrationProof != null &&
      request.target in setOf(
        TicketVisualActionTarget.PROVE_CURRENT,
        TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED,
        TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED,
        TicketVisualActionTarget.REDETECT_LATEST
      )
    val sliderRegion = if (publishesSliderRegion) {
      val normalized = observation.sliderBounds?.let {
        TicketCaptureGeometry.normalizeProbeBounds(
          bounds = it,
          probeWidth = TICKET_ACTION_V3_SAMPLE_WIDTH,
          probeHeight = TICKET_ACTION_V3_SAMPLE_HEIGHT
        )
      } ?: return ticketVisualActionTerminal(
        request,
        false,
        "needs_attention",
        "ticket_action_slider_geometry_invalid",
        observation
      )
      TicketSliderRegionV3(
        proofActionId = request.actionId,
        streamEpoch = watermark.first,
        frameSequence = watermark.second,
        leftBasisPoints = normalized.leftBasisPoints,
        topBasisPoints = normalized.topBasisPoints,
        rightBasisPoints = normalized.rightBasisPoints,
        bottomBasisPoints = normalized.bottomBasisPoints
      )
    } else {
      null
    }
    // Terminal proof and normalized geometry enter one local envelope. The worker settles that
    // envelope in one server transaction; a restart safely repeats the same terminal facts.
    return ticketVisualActionTerminal(
      request = request,
      ok = true,
      status = "succeeded",
      reason = reason,
      observation = observation,
      proofWatermark = watermark,
      sliderRegion = sliderRegion
    )
  }

  private fun ticketVisualActionTerminal(
    request: TicketVisualActionRequest,
    ok: Boolean,
    status: String,
    reason: String,
    observation: TicketVisualActionObservation? = null,
    proofWatermark: Pair<Long, Long>? = null,
    sliderRegion: TicketSliderRegionV3? = null,
    expectedNegativeProof: Boolean = false,
    terminalPhase: String? = null
  ): TicketVisualActionSnapshot {
    // Spacetime owns switch admission and expiry. Pixel only confirms that both private visual
    // anchors needed to execute an admitted command are locally present and echoes the supplied
    // authoritative deadline; it never derives a business window from its own clock.
    val switchAvailable = request.hasSpacetimeSwitchAuthority && ticketVisualSwitchAnchors.visuallyReady
    val currentWatermark = synchronized(encoderLock) { streamEpoch to frameSequence }
    val successfulWatermarkCurrent = !ok || proofWatermark?.let { watermark ->
      synchronized(encoderLock) {
        ticketVisualActionWatermarkCurrentLocked(watermark)
      }
    } == true
    val resultView = observation?.let {
      ticketVisualResultView(request.target, it.state)
    } ?: TicketVisualActionView.UNKNOWN
    val successfulViewCurrent = !ok || ticketVisualTerminalViewCompatible(request.target, resultView)
    val terminalOk = ok && successfulWatermarkCurrent && successfulViewCurrent
    val expectedNegativeProofCurrent = expectedNegativeProof && !ok &&
      request.target == TicketVisualActionTarget.REDETECT_LATEST &&
      status == "failed" && reason == "ticket_action_latest_not_detected" &&
      observation?.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY &&
      proofWatermark?.let { watermark ->
        synchronized(encoderLock) {
          ticketVisualActionWatermarkCurrentLocked(watermark)
        }
      } == true
    val candidateStatus = when {
      expectedNegativeProof && !expectedNegativeProofCurrent -> "needs_attention"
      ok && !terminalOk -> "needs_attention"
      else -> status
    }
    val terminalStatus = if (request.target.activatesTicket && !terminalOk) {
      "needs_attention"
    } else {
      candidateStatus
    }
    val terminalReason = when {
      expectedNegativeProof && !expectedNegativeProofCurrent ->
        "ticket_action_frame_watermark_unproved"
      ok && !successfulWatermarkCurrent -> "ticket_action_frame_watermark_unproved"
      ok && !successfulViewCurrent -> "ticket_action_terminal_view_unproved"
      else -> reason
    }
    val terminalSwitchAvailable = terminalOk && switchAvailable
    val terminalWatermark = proofWatermark.takeIf {
      terminalOk || expectedNegativeProofCurrent
    } ?: currentWatermark
    val snapshot = TicketVisualActionSnapshot(
      actionId = request.actionId,
      target = request.target.wireName,
      status = terminalStatus,
      phase = terminalPhase ?: when {
        terminalOk && request.target.activatesTicket -> "activation_proven"
        terminalOk -> "complete"
        request.target.activatesTicket -> "not_dispatched"
        else -> terminalStatus
      },
      currentView = resultView,
      switchAvailable = terminalSwitchAvailable,
      switchExpiresAt = request.switchExpiresAt.takeIf { terminalSwitchAvailable }.orEmpty(),
      streamEpoch = terminalWatermark.first,
      frameSequence = terminalWatermark.second,
      reason = terminalReason,
      completedAt = Instant.now().toString(),
      activationAttemptId = request.attemptId.takeIf {
        request.target.activatesTicket
      }.orEmpty(),
      sliderRegion = sliderRegion?.takeIf {
        terminalOk && it.proofActionId == request.actionId &&
          it.streamEpoch == terminalWatermark.first &&
          it.frameSequence == terminalWatermark.second
      },
      terminal = true,
      ok = terminalOk
    )
    val deferProvedMutationTerminal =
      (snapshot.ok || ticketVisualLatestNotDetectedTerminalHasBoundProof(snapshot)) &&
      (request.target != TicketVisualActionTarget.PROVE_CURRENT ||
        ticketVisualActionCaptureLeaseActive)
    if (deferProvedMutationTerminal) {
      synchronized(ticketActionV3Lock) {
        deferredTicketVisualTerminalActionId = request.actionId
        deferredTicketVisualTerminalObservation = observation
      }
      return snapshot
    }
    if (persistTicketVisualTerminalSnapshot(request, snapshot, observation)) return snapshot
    recordTicketEvent(
      "ticket_action_terminal_journal_unproved",
      "action=${request.actionId.takeLast(24)} status=${snapshot.status}"
    )
    return if (snapshot.ok) {
      snapshot.copy(
        status = "needs_attention",
        phase = "needs_attention",
        reason = "ticket_action_terminal_journal_unproved",
        switchAvailable = false,
        switchExpiresAt = "",
        sliderRegion = null,
        ok = false
      )
    } else {
      snapshot
    }
  }

  private fun persistTicketVisualTerminalSnapshot(
    request: TicketVisualActionRequest,
    snapshot: TicketVisualActionSnapshot,
    observation: TicketVisualActionObservation?
  ): Boolean {
    val prior = loadTicketVisualActionJournal()
    val sameActionPrior = prior.takeIf {
      it.actionId == request.actionId && it.target == request.target.wireName
    } ?: TicketVisualActionJournalState()
    return persistTicketVisualActionJournal(
      TicketVisualActionJournalState(
        commandId = request.commandId,
        commandRevision = request.commandRevision,
        flow = request.flow,
        refreshActivationAttemptId = request.refreshActivationAttemptId,
        refreshActivationRevision = request.refreshActivationRevision,
        actionId = request.actionId,
        target = request.target.wireName,
        phase = "terminal",
        intendedAnchor = ticketVisualTerminalIntendedAnchor(sameActionPrior, observation),
        navigationFromState = sameActionPrior.navigationFromState,
        navigationToState = sameActionPrior.navigationToState,
        navigationAnchor = sameActionPrior.navigationAnchor,
        streamEpoch = snapshot.streamEpoch,
        frameSequence = snapshot.frameSequence,
        terminalStatus = snapshot.status,
        terminalPhase = snapshot.phase,
        terminalReason = snapshot.reason,
        terminalView = snapshot.currentView.wireName,
        interactionRevision = snapshot.interactionRevision,
        activationRevision = snapshot.activationRevision,
        activationAttemptId = snapshot.activationAttemptId,
        completedAt = snapshot.completedAt,
        terminalOk = snapshot.ok,
        sliderLeftBasisPoints = snapshot.sliderRegion?.leftBasisPoints ?: -1,
        sliderTopBasisPoints = snapshot.sliderRegion?.topBasisPoints ?: -1,
        sliderRightBasisPoints = snapshot.sliderRegion?.rightBasisPoints ?: -1,
        sliderBottomBasisPoints = snapshot.sliderRegion?.bottomBasisPoints ?: -1
      )
    )
  }

  private fun loadTicketVisualActionJournal(): TicketVisualActionJournalState {
    val preferences = applicationContext.getSharedPreferences(
      TICKET_ACTION_V3_JOURNAL_PREFERENCES,
      Context.MODE_PRIVATE
    )
    return TicketVisualActionJournalState(
      commandId = preferences.getString("command_id", "").orEmpty(),
      commandRevision = preferences.getString("command_revision", "").orEmpty(),
      flow = preferences.getString("flow", "").orEmpty(),
      refreshActivationAttemptId = preferences.getString("refresh_activation_attempt_id", "").orEmpty(),
      refreshActivationRevision = preferences.getString("refresh_activation_revision", "").orEmpty(),
      actionId = preferences.getString("action_id", "").orEmpty(),
      target = preferences.getString("target", "").orEmpty(),
      phase = preferences.getString("phase", "").orEmpty(),
      intendedAnchor = preferences.getString("intended_anchor", "").orEmpty(),
      navigationFromState = preferences.getString("navigation_from_state", "").orEmpty(),
      navigationToState = preferences.getString("navigation_to_state", "").orEmpty(),
      navigationAnchor = preferences.getString("navigation_anchor", "").orEmpty(),
      streamEpoch = preferences.getLong("stream_epoch", 0L),
      frameSequence = preferences.getLong("frame_sequence", 0L),
      terminalStatus = preferences.getString("terminal_status", "").orEmpty(),
      terminalPhase = preferences.getString("terminal_phase", "").orEmpty(),
      terminalReason = preferences.getString("terminal_reason", "").orEmpty(),
      terminalView = preferences.getString("terminal_view", "").orEmpty(),
      interactionRevision = preferences.getString("interaction_revision", "").orEmpty(),
      activationRevision = preferences.getString("activation_revision", "").orEmpty(),
      activationAttemptId = preferences.getString("activation_attempt_id", "").orEmpty(),
      completedAt = preferences.getString("completed_at", "").orEmpty(),
      terminalOk = preferences.getBoolean("terminal_ok", false),
      sliderLeftBasisPoints = preferences.getInt("slider_left_basis_points", -1),
      sliderTopBasisPoints = preferences.getInt("slider_top_basis_points", -1),
      sliderRightBasisPoints = preferences.getInt("slider_right_basis_points", -1),
      sliderBottomBasisPoints = preferences.getInt("slider_bottom_basis_points", -1)
    )
  }

  private fun persistTicketVisualActionJournal(value: TicketVisualActionJournalState): Boolean {
    val preferences = applicationContext.getSharedPreferences(
      TICKET_ACTION_V3_JOURNAL_PREFERENCES,
      Context.MODE_PRIVATE
    )
    return ticketVisualActionJournalWriteProved(
      value = value,
      commit = {
        preferences.edit()
          .putString("command_id", value.commandId)
          .putString("command_revision", value.commandRevision)
          .putString("flow", value.flow)
          .putString("refresh_activation_attempt_id", value.refreshActivationAttemptId)
          .putString("refresh_activation_revision", value.refreshActivationRevision)
          .putString("action_id", value.actionId)
          .putString("target", value.target)
          .putString("phase", value.phase)
          .putString("intended_anchor", value.intendedAnchor)
          .putString("navigation_from_state", value.navigationFromState)
          .putString("navigation_to_state", value.navigationToState)
          .putString("navigation_anchor", value.navigationAnchor)
          .putLong("stream_epoch", value.streamEpoch)
          .putLong("frame_sequence", value.frameSequence)
          .putString("terminal_status", value.terminalStatus)
          .putString("terminal_phase", value.terminalPhase)
          .putString("terminal_reason", value.terminalReason)
          .putString("terminal_view", value.terminalView)
          .putString("interaction_revision", value.interactionRevision)
          .putString("activation_revision", value.activationRevision)
          .putString("activation_attempt_id", value.activationAttemptId)
          .putString("completed_at", value.completedAt)
          .putBoolean("terminal_ok", value.terminalOk)
          .putInt("slider_left_basis_points", value.sliderLeftBasisPoints)
          .putInt("slider_top_basis_points", value.sliderTopBasisPoints)
          .putInt("slider_right_basis_points", value.sliderRightBasisPoints)
          .putInt("slider_bottom_basis_points", value.sliderBottomBasisPoints)
          .commit()
      },
      readBack = ::loadTicketVisualActionJournal
    )
  }

  internal fun stageTicketVisualActionFinalization(
    commandId: String,
    commandRevision: String,
    action: TicketVisualActionSnapshot
  ): TicketActionFinalizationEnvelope? {
    if (!action.terminal || commandId.isBlank() || commandRevision.isBlank()) return null
    val journal = loadTicketVisualActionJournal()
    if (!journal.hasRetainedTerminal || journal.actionId != action.actionId ||
      journal.target != action.target
    ) return null
    val staged = journal.copy(
      commandId = commandId,
      commandRevision = commandRevision,
      terminalStatus = action.status,
      terminalPhase = action.phase,
      terminalReason = action.reason,
      terminalView = action.currentView.wireName,
      streamEpoch = action.streamEpoch,
      frameSequence = action.frameSequence,
      interactionRevision = action.interactionRevision,
      activationRevision = action.activationRevision.takeIf { action.ok }.orEmpty(),
      activationAttemptId = action.activationAttemptId,
      completedAt = action.completedAt.ifBlank { journal.completedAt }.ifBlank {
        Instant.now().toString()
      },
      terminalOk = action.ok,
      sliderLeftBasisPoints = action.sliderRegion?.leftBasisPoints ?: -1,
      sliderTopBasisPoints = action.sliderRegion?.topBasisPoints ?: -1,
      sliderRightBasisPoints = action.sliderRegion?.rightBasisPoints ?: -1,
      sliderBottomBasisPoints = action.sliderRegion?.bottomBasisPoints ?: -1
    )
    if (!persistTicketVisualActionJournal(staged)) return null
    return ticketActionFinalizationEnvelope(staged)
  }

  internal fun pendingTicketVisualActionFinalization(): TicketActionFinalizationEnvelope? =
    ticketActionFinalizationEnvelope(loadTicketVisualActionJournal())

  internal fun completeTicketVisualActionFinalization(
    envelope: TicketActionFinalizationEnvelope
  ): Boolean {
    val journal = loadTicketVisualActionJournal()
    if (!journal.hasRetainedTerminal) return journal.actionId.isBlank()
    if (journal.commandId != envelope.commandId ||
      journal.commandRevision != envelope.commandRevision ||
      journal.actionId != envelope.action.actionId
    ) return false
    val activationCheckpointReady = if (envelope.action.activationAttemptId.isBlank()) {
      true
    } else {
      val checkpoint = ticketActivationCheckpointStore.load()
      checkpoint == null || if (
        checkpoint.commandId == envelope.commandId &&
        checkpoint.activationAttemptId == envelope.action.activationAttemptId
      ) {
        if (ticketActivationCheckpointSafeToClearAfterTerminalFinalization(
            checkpoint,
            envelope.commandId,
            envelope.action
          )
        ) {
          ticketActivationCheckpointStore.clearIfMatches(
            envelope.commandId,
            envelope.action.activationAttemptId
          )
        } else {
          // The server owns the terminal action now, but this local stage still records a
          // possibly dispatched or otherwise uncertain physical outcome. Preserve that fence.
          true
        }
      } else {
        // This terminal action never owned the older checkpoint. Preserve that safety fence while
        // allowing this already-finalized zero-effect envelope to leave the worker queue.
        true
      }
    }
    if (!activationCheckpointReady) return false
    return clearTicketVisualActionJournal(envelope.action.actionId)
  }

  private fun clearTicketVisualActionJournal(actionId: String): Boolean {
    val preferences = applicationContext.getSharedPreferences(
      TICKET_ACTION_V3_JOURNAL_PREFERENCES,
      Context.MODE_PRIVATE
    )
    if (preferences.getString("action_id", "") != actionId) return true
    return preferences.edit().clear().commit() &&
      preferences.getString("action_id", "").isNullOrBlank()
  }

  private fun loadTicketVisualSwitchAnchorsIfNeeded() {
    if (ticketVisualSwitchAnchorsLoaded) return
    synchronized(ticketActionV3Lock) {
      if (ticketVisualSwitchAnchorsLoaded) return
      val preferences = applicationContext.getSharedPreferences(
        TICKET_ACTION_V3_SWITCH_PREFERENCES,
        Context.MODE_PRIVATE
      )
      ticketVisualSwitchAnchors = TicketVisualSwitchAnchors(
        recentActivatedAnchor = preferences.getString("recent_activated_anchor", "").orEmpty(),
        latestUnactivatedAnchor = preferences.getString("latest_unactivated_anchor", "").orEmpty()
      )
      ticketVisualSwitchAnchorsLoaded = true
    }
  }

  private fun updateTicketVisualSwitchAnchors(value: TicketVisualSwitchAnchors) {
    ticketVisualSwitchAnchors = value
    applicationContext.getSharedPreferences(TICKET_ACTION_V3_SWITCH_PREFERENCES, Context.MODE_PRIVATE)
      .edit()
      .putString("recent_activated_anchor", value.recentActivatedAnchor)
      .putString("latest_unactivated_anchor", value.latestUnactivatedAnchor)
      .remove("recent_activated_at")
      .remove("latest_unactivated_at")
      .commit()
  }

  private fun rememberTicketRegistrationProof(proof: TicketRegistrationProof) {
    val retained = ticketRegistrationProofPreservingExactIdentity(
      currentTicketRegistrationProof,
      proof
    )
    currentTicketRegistrationProof = retained
    lastTicketRegistrationProof = retained
  }

  private fun bindTicketRegistrationProofToCurrentWatermark(
    proof: TicketRegistrationProof,
    watermark: Pair<Long, Long>
  ): TicketRegistrationProof? = synchronized(encoderLock) {
    if (!ticketVisualActionWatermarkCurrentLocked(watermark)) return@synchronized null
    proof.copy(
      streamEpoch = watermark.first,
      frameSequence = watermark.second,
      provedAtUptimeMillis = SystemClock.elapsedRealtime()
    ).also(::rememberTicketRegistrationProof)
  }

  internal fun ticketActivationCheckpoint(
    commandId: String,
    interactionRevision: String,
    activationAttemptId: String
  ): TicketActivationCheckpoint? {
    return ticketActivationCheckpointStore.loadFor(commandId, interactionRevision, activationAttemptId)
  }

  internal fun recordTicketActivationFreshProof(
    commandId: String,
    interactionRevision: String,
    activationAttemptId: String
  ): TicketActivationCheckpoint? {
    return ticketActivationCheckpointStore.recordFreshTicketProven(
      commandId = commandId,
      interactionRevision = interactionRevision,
      activationAttemptId = activationAttemptId
    )
  }

  internal fun recordTicketActivationDispatching(
    checkpoint: TicketActivationCheckpoint,
    ordinal: Int
  ): TicketActivationCheckpoint? {
    return ticketActivationCheckpointStore.recordActivationDispatching(checkpoint, ordinal)
  }

  internal fun recordTicketActivationProven(
    checkpoint: TicketActivationCheckpoint,
    activationRevision: String
  ): TicketActivationCheckpoint? {
    return ticketActivationCheckpointStore.recordActivationProven(checkpoint, activationRevision)
  }

  internal suspend fun handleTicketSpacetimeDesiredActive(reason: String): TicketSpacetimeCommandResult {
    val cleanReason = reason.ifBlank { "spacetime_desired_active" }
    if (controlCodeRequestActive()) {
      recordTicketEvent("spacetime_desired_start_coalesced_control_code", cleanReason)
      return TicketSpacetimeCommandResult(
        ok = true,
        reason = "control_code_request_owns_start",
        streamState = ticketSpacetimeStreamState()
      )
    }
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
      put("hardwareH264FrameDependencyMode", h264.frameDependencyMode)
      put("hardwareH264UnexpectedDeltaFrames", h264.unexpectedDeltaFrames)
      put("hardwareH264HelperState", h264.captureHelperState)
      put("hardwareH264Visibility", h264.lastVisibilityCheckResult)
      put("hardwareH264Fps", h264.fps ?: -1)
      put("hardwareH264FrameIntervalMillis", h264.currentIntervalMillis ?: -1L)
      put("hardwareH264CadenceDeadlineMisses", h264.cadenceDeadlineMisses)
      put("hardwareH264CadenceSkippedTicks", h264.cadenceSkippedTicks)
      put("lastStreamRecoveryResult", lastStreamRecoveryResult)
      put("streamWatchdogStage", streamWatchdogStage)
      put("lastStreamWatchdogAction", lastStreamWatchdogAction)
      put("desiredRecoveryStage", spacetimeDesiredRecoveryStage)
      put("lastDesiredRecoveryResult", lastSpacetimeDesiredRecoveryResult)
      put("controlCodeStatus", lastControlCodeRequestStatus)
      put("latestTicketReselectStatus", "retired")
      put("latestTicketReselectPhase", "ticket_action_v3_only")
      put("latestTicketReselectProofSource", "visual_action")
    }.toString()
  }

  internal fun peekTicketSpacetimePhoneMessages(maxMessages: Int = Int.MAX_VALUE): List<String> {
    return ticketSpacetimePhoneOutbox.peek(maxMessages)
  }

  internal fun acknowledgeTicketSpacetimePhoneMessage(message: String) {
    ticketSpacetimePhoneOutbox.acknowledge(message)
  }

  internal fun enqueueTicketSpacetimePhoneMessage(message: String) {
    ticketSpacetimePhoneOutbox.enqueue(message)
  }

  private fun ticketSpacetimeCriticalMessageKey(message: String): String? {
    val payload = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
    return TicketSpacetimeCriticalMessagePolicy.key(payload)
  }

  private fun ticketSpacetimeCriticalMessageReplacement(message: String): TicketSpacetimeCriticalReplacement? {
    val payload = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
    return TicketSpacetimeCriticalMessagePolicy.replacement(payload)
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
    rootHardwareH264CaptureEngine.stopAndJoin("spacetime_desired_recovery:$reason")

    val captureLease = ensureSecureWindowCaptureBypassForProtectedPixels("spacetime_desired_recovery")
    if (captureLease == null) {
      val failureReason = "secure_capture_bypass_unavailable"
      spacetimeDesiredRecoveryStage = "failed"
      lastSpacetimeDesiredRecoveryAction = "enable_secure_capture_bypass"
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
      updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, failureReason)
      lastMessage = "Secure ViVi capture could not be enabled; stream was not restarted"
      recordTicketEvent(
        "spacetime_desired_recovery_failed",
        "reason=$reason failure=$failureReason"
      )
      broadcastStatus()
      return TicketSpacetimeCommandResult(
        ok = false,
        reason = failureReason,
        streamState = ticketSpacetimeStreamState()
      )
    }
    return secureWindowCaptureBypassOwner.runRetainingOnSuccess(
      lease = captureLease,
      reason = "spacetime_desired_recovery:$reason",
      shouldRetain = {
        it.ok && streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264
      },
      retainIfCurrent = {
        streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264
      }
    ) capture@{
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
      return@capture TicketSpacetimeCommandResult(
        ok = false,
        reason = failureReason,
        streamState = ticketSpacetimeStreamState()
      )
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
    result
    }
  }

  private fun boundedStartupTraceCorrelationId(value: String): String {
    val clean = value.trim()
    if (clean.length != 16 || !clean.startsWith("startup_")) return ""
    if (!clean.removePrefix("startup_").all { it in '0'..'9' || it in 'a'..'f' }) return ""
    return clean
  }

  private fun JsonObject.stringValue(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

  private fun JsonObject.booleanValue(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

  private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

  private fun JsonObject.jsonObjectValue(key: String): JsonObject? = runCatching { this[key]?.jsonObject }.getOrNull()

  private suspend fun startTicketSession(
    prepareCaptureWithCurrentPhoneMutationOwnership: Boolean = false,
    timeoutMillis: Long = SESSION_START_TIMEOUT_MILLIS,
    lockedStartDecision: (() -> TicketSessionResponse?)? = null,
    onNewSessionAdmitted: ((Long) -> Unit)? = null
  ): TicketSessionResponse {
    val response = withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
      if (!secureCaptureStartupReconciled.await()) {
        return@withTimeoutOrNull TicketSessionResponse(
          ok = false,
          state = "secure_capture_startup_reconcile_unproved",
          message = "Secure ViVi capture cleanup was not proved during startup"
        )
      }
      if (serviceLifecycleStopping) {
        return@withTimeoutOrNull TicketSessionResponse(
          ok = false,
          state = "service_stopping",
          message = "Ticket service is stopping"
        )
      }
      sessionMutex.withLock {
        lockedStartDecision?.invoke() ?: startTicketSessionLocked(
          scheduleCaptureStart = !prepareCaptureWithCurrentPhoneMutationOwnership,
          controlCodeOwnsStart = prepareCaptureWithCurrentPhoneMutationOwnership,
          onNewSessionAdmitted = onNewSessionAdmitted
        )
      }
    } ?: return run {
      cleanupInactiveClientsIfNeeded("session_start_timeout")
      recordTicketEvent("session_start_timeout", "timeout_ms=$timeoutMillis state=$ticketSessionState clients=${totalClientCount()}")
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

  private suspend fun startTicketSessionLocked(
    scheduleCaptureStart: Boolean,
    controlCodeOwnsStart: Boolean,
    onNewSessionAdmitted: ((Long) -> Unit)?
  ): TicketSessionResponse {
    tryReuseActiveHardwareStreamBeforePreflight()?.let { return it }
    if (
      shouldCoalescePreparingTicketStreamBeforePreflight(
        streamActive = streamActive,
        rootHardwareCaptureMode = activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264,
        hardwareCaptureVerified = hardwareCaptureVerified
      )
    ) {
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
    beginStartupTrace("session_start")
    if (!TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)) {
      recordStartupTracePhase("vivi_missing", "package=${TicketScreenConfig.VIVI_PACKAGE}", complete = true)
      return TicketSessionResponse(
        ok = false,
        state = "vivi_missing",
        message = "ViVi is not installed from a local Pixel app store yet"
      )
    }
    val safetyPreflight = runSessionStartSafetyPreflight()
    var preflightArm = safetyPreflight.armHandle
    suspend fun cancelPreflightArm(reason: String) {
      preflightArm?.let { rootHardwareH264CaptureEngine.cancelArmedAndJoin(it, reason) }
      preflightArm = null
    }
    val captureLease = safetyPreflight.secureCapture.lease
    if (!safetyPreflight.portrait.verified) {
      cancelPreflightArm("portrait_unverified")
      if (shouldReleaseSessionStartSecureLease(false, safetyPreflight.secureCapture)) {
        val released = secureWindowCaptureBypassOwner.releaseAcquiredLease(
          requireNotNull(captureLease),
          "portrait_lock_failed:session_start"
        )
        if (released != null && !released.ok) {
          recordStartupTracePhase(
            "secure_capture_cleanup_failed",
            "duration_ms=${safetyPreflight.totalDurationMillis}",
            once = true
          )
        }
      }
      recordTicketEvent("phone_portrait_lock_unverified", "session_start")
      recordStartupTracePhase("portrait_lock_failed", "session_start", complete = true)
      return TicketSessionResponse(
        ok = false,
        state = "portrait_lock_failed",
        message = "Phone portrait lock could not be verified"
      )
    }
    if (safetyPreflight.secureCapture.outcome == "ownership_unproved") {
      cancelPreflightArm("secure_capture_ownership_unproved")
      recordStartupTracePhase(
        "secure_capture_ownership_unproved",
        "duration_ms=${safetyPreflight.secureCaptureDurationMillis}",
        once = true,
        complete = true
      )
      return TicketSessionResponse(
        ok = false,
        state = "secure_capture_ownership_unproved",
        message = "Secure capture could not be freshly verified; the existing owner was preserved"
      )
    }
    if (safetyPreflight.secureCapture.outcome == "ownership_busy") {
      cancelPreflightArm("secure_capture_ownership_busy")
      tryReuseActiveHardwareStreamBeforePreflight()?.let { return it }
      val controlOwnershipActive = controlCodeOwnsStart ||
        streamStartAdmission.claimCount() > 0L ||
        ticketSpacetimeControlCodeRequestActive()
      recordStartupTracePhase(
        "secure_capture_ownership_busy",
        "duration_ms=${safetyPreflight.secureCaptureDurationMillis} active=$controlOwnershipActive",
        once = true
      )
      if (!controlOwnershipActive) {
        recordStartupTracePhase(
          "secure_capture_ownership_retry",
          "duration_ms=${safetyPreflight.secureCaptureDurationMillis}",
          once = true,
          complete = true
        )
        return TicketSessionResponse(
          ok = false,
          state = "secure_capture_ownership_busy",
          message = "Secure capture ownership changed; retry the stream start"
        )
      }
      lastMessage = "Another Ticket operation is preparing secure capture"
      markViewerInput("session_start_secure_capture_ownership_busy")
      broadcastStatus()
      return TicketSessionResponse(
        ok = true,
        state = "starting",
        message = lastMessage
      )
    }
    if (captureLease == null) {
      cancelPreflightArm("secure_capture_unavailable")
      fallbackReason = "secure_capture_bypass_unavailable"
      cancelInactivityTimer()
      updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, "secure_capture_bypass_unavailable")
      lastMessage = "Secure ViVi capture could not be enabled; stream was not started"
      recordTicketEvent("session_unavailable", "secure_capture_bypass_unavailable")
      recordStartupTracePhase(
        "secure_capture_bypass_unavailable",
        "session_start",
        complete = true
      )
      return TicketSessionResponse(
        ok = false,
        state = "secure_capture_bypass_unavailable",
        message = lastMessage
      )
    }
    return secureWindowCaptureBypassOwner.runRetainingOnSuccess(
      lease = captureLease,
      reason = "session_start",
      shouldRetain = {
        it.ok && streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264
      },
      retainIfCurrent = {
        streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264
      },
      beforeConditionalRelease = {
        cancelPreflightArm("session_start_not_retained")
      }
    ) session@{
    if (streamActive) {
      cancelPreflightArm("stream_became_active")
      if (canReuseActiveHardwareStreamWithoutRootRevalidation("session_start_already_active")) {
        return@session reuseActiveHardwareStream(
          reason = "session_start_already_active",
          traceDetail = "fast=true frame_sequence=$frameSequence"
        )
      }
      if (!validateActiveTicketSessionBeforeReuse("session_start_already_active")) {
        recordTicketEvent(
          "session_start_already_active_revalidate",
          "state=$ticketSessionState frame_sequence=$frameSequence reason=$ticketSessionStateReason"
        )
        lastMessage = "The current ViVi view could not be proved; no navigation was attempted"
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "active_stream_visual_unproved")
        recordStartupTracePhase(
          "active_stream_revalidation_unproved",
          "state=$ticketSessionState frame_sequence=$frameSequence",
          once = true,
          complete = true
        )
        broadcastStatus()
        return@session TicketSessionResponse(
          ok = false,
          state = "visual_unproved",
          message = lastMessage
        )
      }
      return@session reuseActiveHardwareStream(
        reason = "session_start_already_active",
        traceDetail = "validated=true frame_sequence=$frameSequence"
      )
    }
    val sourceSize = currentDisplaySize()
    var hardwareCapture = rootHardwareH264CaptureEngine.snapshot()
    if (!hardwareCapture.available) {
      // The service-start probe is the one owner of helper/classpath
      // readiness. Every cold viewer, not only control-code work, must join
      // that job before deciding that capture is unavailable; otherwise a
      // normal viewer can launch a competing probe while startup is still
      // cleaning up and leave an active-but-empty stream behind.
      hardwareCapture = if (streamCaptureNeededForControlCodeRequest()) {
        awaitRootHardwareH264StartupReadinessForControlCodeRequest()
      } else {
        awaitRootHardwareH264StartupReadiness("session_start")
      }
    }
    // A dimensions-aware reliability probe captures one real frame. Keep it on the admitted side
    // of streamStartAdmission so a blocked background start cannot acquire protected pixels.
    val reliabilityProbeRequired = hardwareMarkedUnreliable()
    val hardwareUnavailableReason = when {
      !hardwareCapture.available -> "hardware_h264_unavailable:${hardwareCapture.state}"
      else -> null
    }
    val effectiveHardwareUnavailableReason = hardwareUnavailableReason
    updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_requested")
    recordStartupTracePhase("session_start_requested", "hardware_state=${hardwareCapture.state}", once = true)
    recordTicketEvent(
      "session_start_requested",
      "root_hardware_h264_available=${hardwareCapture.available} root_hardware_h264_state=${hardwareCapture.state} selected_mode=${if (effectiveHardwareUnavailableReason == null) CAPTURE_MODE_ROOT_HARDWARE_H264 else CAPTURE_MODE_IDLE}"
    )
    lastSessionStopReason = null
    if (effectiveHardwareUnavailableReason != null) {
      cancelPreflightArm("hardware_unavailable")
      fallbackReason = effectiveHardwareUnavailableReason
      cancelInactivityTimer()
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
      return@session TicketSessionResponse(
        ok = false,
        state = "hardware_h264_unavailable",
        message = lastMessage
      )
    }
    var armConsumedByAdmission = false
    var scheduleAfterArmCleanup = false
    var admissionGranted = false
    var scheduleAfterReliabilityProbe = false
    val admissionResponse = streamStartAdmission.admit(
      controlCodeOwnsStart = controlCodeOwnsStart,
      additionalControlOwnershipActive = ::ticketSpacetimeControlCodeRequestActive,
      blocked = {
        recordTicketEvent("session_start_coalesced_control_code", "state=$ticketSessionState")
        TicketSessionResponse(
          ok = true,
          state = ticketSessionState,
          message = "Control-code work owns the phone"
        )
      },
      admitted = {
        admissionGranted = true
        fallbackReason = null
        ticketSessionGeneration += 1L
        streamActive = true
        onNewSessionAdmitted?.invoke(ticketSessionGeneration)
        hardwareCaptureVerified = false
        hardwareFrameBroadcastAllowed = false
        activeCaptureMode = CAPTURE_MODE_ROOT_HARDWARE_H264
        preflightArm?.let { handle ->
          val source = currentDisplaySize()
          val size = TicketStreamSizing.rootHardwareH264(source.first, source.second)
          armConsumedByAdmission = rootHardwareH264CaptureEngine.activateArmed(
            handle,
            source.first,
            source.second,
            size.width,
            size.height,
            TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
          )
          if (armConsumedByAdmission) preflightArm = null
        }
        val modeLabel = "root_hardware_h264"
        updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_${modeLabel}_prepare")
        markViewerInput("session_start_${modeLabel}_prepare")
        lastMessage = "Preparing ViVi for secure H.264 capture"
        recordTicketEvent("session_capture_mode_selected", "mode=$activeCaptureMode fallback=${fallbackReason.orEmpty()}")
        recordStartupTracePhase("capture_mode_selected", "mode=$activeCaptureMode", once = true)
        recordTicketEvent("session_started", "mode=$activeCaptureMode")
        startForegroundGuard()
        if (scheduleCaptureStart && reliabilityProbeRequired) {
          scheduleAfterReliabilityProbe = true
        } else if (scheduleCaptureStart && (preflightArm == null || armConsumedByAdmission)) {
          scheduleRootHardwareH264CaptureStart("session_start_root_hardware_h264_capture", suppressBlackout = false)
        } else if (scheduleCaptureStart) {
          scheduleAfterArmCleanup = true
        } else {
          recordTicketEvent("root_hardware_h264_prepare_owned", "session_start_root_hardware_h264_capture")
        }
        broadcastStatus()
        TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
      }
    )
    if (!armConsumedByAdmission) cancelPreflightArm("session_start_not_admitted")
    if (admissionGranted && reliabilityProbeRequired) {
      hardwareCapture = refreshHardwareReliabilityIfProbePasses(
        sourceSize.first,
        sourceSize.second,
        hardwareCapture
      )
      if (!hardwareCapture.available || hardwareMarkedUnreliable()) {
        val reliabilityFailure = hardwareUnreliableReason ?: "hardware_h264_unreliable"
        fallbackReason = reliabilityFailure
        streamActive = false
        hardwareCaptureVerified = false
        hardwareFrameBroadcastAllowed = false
        activeCaptureMode = CAPTURE_MODE_IDLE
        cancelForegroundGuard()
        releaseBlackoutOverlaySuppression()
        lastMessage = "Hardware H.264 ticket stream is unreliable; stream was not started"
        updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, "hardware_h264_unreliable")
        recordTicketEvent("session_unavailable", reliabilityFailure)
        recordStartupTracePhase("hardware_h264_unreliable", reliabilityFailure, complete = true)
        return@session TicketSessionResponse(
          ok = false,
          state = "hardware_h264_unavailable",
          message = lastMessage
        )
      }
      if (scheduleAfterReliabilityProbe) {
        scheduleRootHardwareH264CaptureStart(
          "session_start_root_hardware_h264_capture_reliability_recovered",
          suppressBlackout = false
        )
      }
    }
    if (scheduleAfterArmCleanup) {
      scheduleRootHardwareH264CaptureStart("session_start_root_hardware_h264_capture_fallback", suppressBlackout = false)
    }
    return@session admissionResponse
    }
  }

  private suspend fun runSessionStartSafetyPreflight(): TicketSessionStartSafetyPreflight {
    val totalStartedAtMillis = SystemClock.elapsedRealtime()
    val acquiredLease = AtomicReference<TicketSecureWindowCaptureBypassLease?>(null)
    val armedHandle = AtomicReference<TicketHardwareH264ArmHandle?>(null)
    val results = runTicketSessionStartPreflight(
      portrait = {
        val startedAtMillis = SystemClock.elapsedRealtime()
        val result = try {
          PhonePortraitLock.ensureVerifiedResult(inputRootExecutor)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          PhonePortraitLock.EnsureResult(
            verified = false,
            outcome = "exception",
            durationMillis = 0L
          )
        }
        result to (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
      },
      secureCapture = {
        val startedAtMillis = SystemClock.elapsedRealtime()
        val result = ensureSecureWindowCaptureBypassResultForSessionStart("session_start")
        if (result.acquiredLease) acquiredLease.set(result.lease)
        result to (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
      },
      onPortraitComplete = { result ->
        val portrait = result.getOrNull()?.first
        if (portrait?.verified == true && armedHandle.get() == null) {
          val source = currentDisplaySize()
          val size = TicketStreamSizing.rootHardwareH264(source.first, source.second)
          val health = rootHardwareH264CaptureEngine.snapshot()
          if (health.available && !hardwareMarkedUnreliable()) {
            armedHandle.compareAndSet(
              null,
              rootHardwareH264CaptureEngine.arm(
                source.first,
                source.second,
                size.width,
                size.height,
                TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
              )
            )
          }
        }
      },
      onCancelled = {
        armedHandle.getAndSet(null)?.let { handle ->
          rootHardwareH264CaptureEngine.cancelArmedAndJoin(handle, "session_start_preflight_cancelled")
        }
        acquiredLease.getAndSet(null)?.let { lease ->
          secureWindowCaptureBypassOwner.releaseAcquiredLease(
            lease,
            "session_start_preflight_cancelled"
          )
        }
      }
    )
    val portrait = results.portrait.getOrElse {
      PhonePortraitLock.EnsureResult(false, "exception", 0L) to 0L
    }
    val secureCapture = results.secureCapture.getOrElse {
      TicketSecureWindowCaptureBypassEnsureResult(outcome = "exception") to 0L
    }
    val totalDurationMillis = (SystemClock.elapsedRealtime() - totalStartedAtMillis).coerceAtLeast(0L)
    val healthOutcome = when {
      !portrait.first.verified -> "portrait_${startupPreflightOutcome(portrait.first.outcome)}"
      secureCapture.first.lease != null -> "ready"
      else -> startupPreflightOutcome(secureCapture.first.outcome)
    }
    lastStartupPreflight = TicketSessionStartPreflightHealth(
      outcome = healthOutcome,
      totalMillis = totalDurationMillis,
      portraitMillis = portrait.second,
      secureCaptureMillis = secureCapture.second,
      completedAtMillis = SystemClock.elapsedRealtime()
    )
    recordStartupTracePhase(
      "portrait_lock_${startupPreflightOutcome(portrait.first.outcome)}",
      "duration_ms=${portrait.second}",
      once = true
    )
    recordStartupTracePhase(
      "secure_capture_${startupPreflightOutcome(secureCapture.first.outcome)}",
      "duration_ms=${secureCapture.second}",
      once = true
    )
    recordStartupTracePhase(
      "session_preflight_complete",
      "preflight_ms=$totalDurationMillis portrait_ms=${portrait.second} secure_capture_ms=${secureCapture.second}",
      once = true
    )
    return TicketSessionStartSafetyPreflight(
      portrait = portrait.first,
      portraitDurationMillis = portrait.second,
      secureCapture = secureCapture.first,
      secureCaptureDurationMillis = secureCapture.second,
      totalDurationMillis = totalDurationMillis,
      armHandle = armedHandle.get()
    )
  }

  private fun startupPreflightOutcome(value: String): String = when (value) {
    "already_verified", "repaired", "failed", "exception", "verified", "enabled",
    "ownership_established", "ownership_busy", "ownership_unproved", "admission_closed",
    "admission_blocked" -> value
    else -> "failed"
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
    val result = observeFastViviState("active_session:$reason")
    if (TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(result.state)) {
      recordTicketEvent("session_start_active_revalidated", "visual:${result.state.name} reason=$reason")
      return true
    }
    recordTicketEvent(
      "session_start_already_active_revalidate",
      "reason=$reason state=${result.state.name} error=${result.error.takeLast(160)}"
    )
    return false
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
      rootHardwareH264CaptureEngine.stopAndJoin(reason)
      val bypassRelease = disableSecureWindowCaptureBypass("client_detached:$reason")
      disableNotificationLockdown(reason)
      scheduleTicketBrightnessGuard("client_detached:$reason")
      releaseTicketScreenAwake()
      releaseBlackoutOverlaySuppression()
      hideBlackoutOverlay()
      if (!bypassRelease.ok) {
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_release_unproved")
        lastMessage = "Browser disconnected, but secure capture cleanup needs attention"
        recordTicketEvent("client_detached_cleanup_unproved", bypassRelease.detail)
      }
      broadcastStatus()
      if (ticketServiceEnabled()) {
        recordTicketEvent("root_capture_ready_waiting", "client_detached_$reason")
      }
      return TicketSessionResponse(
        ok = bypassRelease.ok,
        state = if (bypassRelease.ok) "client_disconnected" else "needs_attention",
        message = lastMessage
      )
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
          !ticketProofStreamAutomationOwnershipActive() &&
          TicketInactivityPolicy.shouldStop(
            lastInputAtMillis = lastViewerInputAtMillis,
            nowMillis = nowMillis,
            activeViewerDemand = videoClients.isNotEmpty()
          )
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
    rootHardwareH264CaptureEngine.stopAndJoin(reason)
    val bypassRelease = disableSecureWindowCaptureBypass("session_stop:$reason")
    disableNotificationLockdown(reason)
    resetControlCodeMode("session_stop_$reason", broadcast = false)
    lastMessage = if (bypassRelease.ok) {
      "Ticket session stopped: $reason"
    } else {
      "Ticket stream stopped, but secure capture cleanup needs attention"
    }
    lastSessionStopReason = reason
    if (bypassRelease.ok) {
      recordTicketEvent("session_stopped", reason)
    } else {
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_release_unproved")
      recordTicketEvent("session_stop_cleanup_unproved", "reason=$reason ${bypassRelease.detail}")
    }
    startupTraceCorrelation.clear()
    scheduleTicketBrightnessGuard("session_stopped:$reason")
    releaseBlackoutOverlaySuppression()
    hideBlackoutOverlay()
    releaseTicketScreenAwake()
    broadcastStatus()
    broadcastInactivityStatus()
    closeAllClients("session_stop_$reason")
    return TicketSessionResponse(
      ok = bypassRelease.ok,
      state = if (bypassRelease.ok) "stopped" else "needs_attention",
      message = lastMessage
    )
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

  private fun scheduleCommandStartedProofStreamCleanupLocked(
    expectedSessionGeneration: Long,
    reason: String
  ) {
    if (!streamActive ||
      ticketSessionGeneration != expectedSessionGeneration ||
      totalClientCount() != 0
    ) {
      return
    }
    // Invalidate an inactivity timeout token captured before the command's final convergence.
    // The stricter generation-fenced cleanup below still parks this stream after 90 seconds.
    markViewerInput("command_proof_terminal_cleanup")
    scheduleClientDisconnectGraceLocked(
      expectedSessionGeneration = expectedSessionGeneration,
      stopReason = reason
    )
  }

  private fun ticketProofStreamAutomationOwnershipActive(): Boolean {
    return streamStartAdmission.claimCount() > 0L ||
      ticketSpacetimeControlCodeRequestActive() ||
      ticketVisualActionJobOwnershipActive ||
      ticketVisualActionCaptureLeaseActive ||
      ticketActionV3Job?.isActive == true ||
      viviReauthCaptureLeaseActive
  }

  private fun scheduleClientDisconnectGraceLocked(
    expectedSessionGeneration: Long? = null,
    stopReason: String = "browser_left_ticket_screen"
  ) {
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
          val decision = ticketProofStreamCleanupDecision(
            expectedSessionGeneration = expectedSessionGeneration,
            currentSessionGeneration = ticketSessionGeneration,
            streamActive = ticketSessionOpen(),
            clientCount = totalClientCount(),
            startOwnershipActive = streamStartAdmission.claimCount() > 0L,
            controlOwnershipActive = ticketSpacetimeControlCodeRequestActive(),
            actionOwnershipActive = ticketVisualActionJobOwnershipActive ||
              ticketVisualActionCaptureLeaseActive ||
              ticketActionV3Job?.isActive == true,
            reauthOwnershipActive = viviReauthCaptureLeaseActive
          )
          when (decision) {
            TicketProofStreamCleanupDecision.STOP -> noteClientDetachedLocked(stopReason)
            TicketProofStreamCleanupDecision.RETRY -> scheduleClientDisconnectGraceLocked(
              expectedSessionGeneration = expectedSessionGeneration,
              stopReason = stopReason
            )
            TicketProofStreamCleanupDecision.DROP -> Unit
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

  internal fun bindStartupTraceCorrelationIdFromVideoSocket(value: String, generation: Long): Boolean {
    val clean = boundedStartupTraceCorrelationId(value)
    return startupTraceCorrelation.bindVideoSocket(clean, generation)
  }

  internal fun bindStartupTraceCorrelationIdFromCommand(value: String): Boolean {
    val clean = boundedStartupTraceCorrelationId(value)
    return startupTraceCorrelation.bindCommand(clean)
  }

  internal fun noteStartupStartCommandReceived(
    commandId: String,
    traceId: String,
    databaseToPhoneMillis: Long
  ) {
    val cleanTraceId = boundedStartupTraceCorrelationId(traceId)
    val adopted = bindStartupTraceCorrelationIdFromCommand(cleanTraceId)
    if (cleanTraceId.isNotBlank() && !adopted) {
      recordTicketEventForTrace(
        "startup_start_command_trace_ignored",
        "command_hash=${hashedOperationalIdentifier(commandId)} database_to_phone_ms=${databaseToPhoneMillis.coerceAtLeast(-1L)}",
        ""
      )
      return
    }
    recordTicketEventForTrace(
      "startup_phase_pixel_start_command_received",
      "command_hash=${hashedOperationalIdentifier(commandId)} database_to_phone_ms=${databaseToPhoneMillis.coerceAtLeast(-1L)}",
      cleanTraceId
    )
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
    if (result.state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD) {
      resetForegroundViolationConfirmation()
      recordTicketEvent("active_guard_ticket_list_ready", reason)
      if (streamActive && ticketSessionState != TICKET_SESSION_LIVE) {
        updateTicketSessionState(TICKET_SESSION_LIVE, "active_guard_ticket_list_$reason")
        lastMessage = "Ticket session is active through hardware H.264 capture"
        broadcastStatus()
      }
      return
    }
    if (controlSensitiveWindowActive()) {
      recordTicketEvent("active_guard_deferred_after_observe", "$reason state=${result.state.name} control_sensitive=true")
      return
    }
    if (result.state == TicketViviRecoveryState.TICKET_DETAIL) {
      if (
        streamActive &&
        activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
        !verifyFreshTicketDetailVisualProof("active_guard:$reason")
      ) {
        recordTicketEvent("active_guard_ticket_detail_visual_unproved", "reason=$reason")
        if (streamActive) {
          updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "active_guard_ticket_detail_visual_unproved")
          broadcastStatus()
        }
        return
      }
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
      recordTicketEvent("vivi_login_owner_action_required", "source=active_guard")
      return false
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
      runFastRecoveryInput("input tap ${action.x} ${action.y}", "active_guard_recovery_action:${action.reason}")
    } else {
      runFastRecoveryInput("input keyevent KEYCODE_BACK", "active_guard_recovery_action:${action.reason}")
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
    return RootResult(
      exitCode = 126,
      stdout = "",
      stderr = "ticket_hierarchy_detection_retired",
      command = "visual_ticket_detection_only",
      durationMs = 0L
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
    val visual = awaitStableTicketVisualActionObservation(
      reason = "legacy_visual_observation:$reason",
      timeoutMillis = timeoutMillis ?: TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS
    )
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    if (visual == null) {
      viviStateMemory.record(
        state = TicketViviRecoveryState.UNKNOWN_VIVI,
        ticketId = null,
        source = "root_visual_empty",
        reason = reason
      )
      recordRootReadiness("$reason:UNKNOWN_VIVI", durationMillis)
      return RootViviObservation(
        TicketViviRecoveryState.UNKNOWN_VIVI,
        null,
        durationMillis,
        "visual_detection_unavailable"
      )
    }
    val state = visual.toRecoveryState()
    viviStateMemory.record(
      state = state,
      ticketId = null,
      source = "root_visual",
      reason = reason
    )
    recordRootReadiness("$reason:${state.name}", durationMillis)
    return RootViviObservation(state, null, durationMillis, visual = visual)
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
        reason = "control_exit_hierarchy",
        hierarchy = hierarchy
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
                val nowMillis = SystemClock.elapsedRealtime()
                val activeViewerDemand = videoClients.isNotEmpty()
                if (
                  inactivityJob === runningJob &&
                  TicketInactivityPolicy.shouldRetain(
                    lastInputAtMillis = lastViewerInputAtMillis,
                    nowMillis = nowMillis,
                    activeViewerDemand = activeViewerDemand
                  )
                ) {
                  lastViewerInputAtMillis = nowMillis
                  viewerInputGeneration += 1L
                  null
                } else if (
                  inactivityJob === runningJob &&
                  TicketInactivityPolicy.shouldStop(
                    lastInputAtMillis = lastViewerInputAtMillis,
                    nowMillis = nowMillis,
                    activeViewerDemand = activeViewerDemand
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

  private suspend fun ensureSecureWindowCaptureBypassForProtectedPixels(
    reason: String
  ): TicketSecureWindowCaptureBypassLease? {
    if (serviceLifecycleStopping || !secureCaptureStartupReady) {
      recordTicketEvent(
        "secure_window_capture_bypass_admission_blocked",
        "reason=$reason stopping=$serviceLifecycleStopping startup_ready=$secureCaptureStartupReady"
      )
      return null
    }
    return secureWindowCaptureBypassOwner.ensure(reason)
  }

  private suspend fun ensureSecureWindowCaptureBypassResultForSessionStart(
    reason: String
  ): TicketSecureWindowCaptureBypassEnsureResult {
    if (serviceLifecycleStopping || !secureCaptureStartupReady) {
      recordTicketEvent(
        "secure_window_capture_bypass_admission_blocked",
        "reason=$reason stopping=$serviceLifecycleStopping startup_ready=$secureCaptureStartupReady"
      )
      return TicketSecureWindowCaptureBypassEnsureResult(outcome = "admission_blocked")
    }
    return secureWindowCaptureBypassOwner.ensureWithResult(
      reason = reason,
      preserveExistingLease = true
    )
  }

  private suspend fun disableSecureWindowCaptureBypass(
    reason: String = "ticket_session_release"
  ): TicketSecureWindowCaptureBypassReleaseResult {
    val first = secureWindowCaptureBypassOwner.release(reason)
    val result = if (!first.ok && first.cleanupRequired) {
      recordTicketEvent("secure_window_capture_bypass_release_retry", "reason=$reason ${first.detail}")
      secureWindowCaptureBypassOwner.release("retry:$reason")
    } else {
      first
    }
    if (!result.ok) {
      recordTicketEvent(
        "secure_window_capture_bypass_release_unproved",
        "reason=$reason cleanup_required=${result.cleanupRequired} ${result.detail}"
      )
    }
    return result
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
    if (!streamActive || (videoClients.isEmpty() && !streamCaptureNeededForControlCodeRequest())) {
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && hardwareCaptureVerified) {
      ensureRootHardwareH264CaptureIfPossible()
    }
  }

  private fun prewarmRootHardwareH264CaptureIfPossible(reason: String) {
    if (!streamActive || (videoClients.isEmpty() && !streamCaptureNeededForControlCodeRequest())) {
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      recordStartupTracePhase("root_capture_prewarm_started", reason, once = true)
      ensureRootHardwareH264CaptureIfPossible()
    }
  }

  /**
   * A browser request is itself a capture consumer, even before the H.264
   * websocket has attached. Without this demand, a true cold request can
   * start the phone session while the encoder remains idle and then spend the
   * whole preparation budget waiting for visual proof that can never arrive.
   */
  private fun streamCaptureNeededForControlCodeRequest(): Boolean {
    return streamStartAdmission.claimCount() > 0L ||
      ticketVisualActionJobOwnershipActive ||
      ticketVisualActionCaptureLeaseActive ||
      viviReauthCaptureLeaseActive ||
      controlCodeRequestActive()
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
        targetBitrate = TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
      )
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      recordStartupTracePhase("root_capture_start_requested", "width=${size.width} height=${size.height} fps=${TicketScreenConfig.ROOT_HARDWARE_H264_FPS} frame_dependency_mode=${TicketScreenConfig.ROOT_HARDWARE_H264_FRAME_DEPENDENCY_MODE}", once = true)
      scheduleStreamWatchdog("root_capture_start_requested")
    }
  }

  private fun streamSizeChanged(previous: TicketStreamSize?, next: TicketStreamSize): Boolean {
    return previous == null ||
      previous.width != next.width ||
      previous.height != next.height ||
      previous.sourceWidth != next.sourceWidth ||
      previous.sourceHeight != next.sourceHeight ||
      previous.sourceLeftCrop != next.sourceLeftCrop ||
      previous.sourceTopCrop != next.sourceTopCrop ||
      previous.sourceRightCrop != next.sourceRightCrop ||
      previous.sourceBottomCrop != next.sourceBottomCrop ||
      previous.sourceVisibleWidth != next.sourceVisibleWidth ||
      previous.sourceVisibleHeight != next.sourceVisibleHeight
  }

  private fun streamSizeSummary(size: TicketStreamSize): String {
    return "${size.width}x${size.height}/source=${size.sourceWidth}x${size.sourceHeight}/" +
      "crop=${size.sourceLeftCrop},${size.sourceTopCrop},${size.sourceRightCrop},${size.sourceBottomCrop}/" +
      "visible=${size.sourceVisibleWidth}x${size.sourceVisibleHeight}"
  }

  private fun handleRootHardwareH264CaptureStateChanged(health: TicketHardwareH264Health) {
    hardwareCaptureSnapshot = health
    if (
      streamActive &&
      activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
      health.active &&
      health.state == "active"
    ) {
      recordStartupTracePhase(
        "capture_helper_active",
        "state=${health.state} helper=${health.captureHelperState}",
        once = true
      )
    }
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
        "state=${health.state} active=${health.active} available=${health.available} frames=${health.frames} keyframes=${health.keyFrames} unexpected_deltas=${health.unexpectedDeltaFrames} restarts=${health.restartCount} last_exit=${health.lastExitReason.orEmpty()} frame_age_ms=${ageMillis(lastFrameSentAtMillis, SystemClock.elapsedRealtime()) ?: -1L} clients=${videoClients.size}"
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
      health.frameDependencyMode,
      health.frames == 0L,
      health.frames == 1L,
      health.keyFrames == 0L,
      health.encoderProcessCount,
      health.staleCaptureProcessCount,
      health.lastCaptureCleanupResult,
      health.blankFrameFailures,
      health.unexpectedDeltaFrames,
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
    val verifiedBeforeRestart = hardwareCaptureVerified
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
        hardwareCaptureVerified = verifiedBeforeRestart
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
      (videoClients.isEmpty() && !streamCaptureNeededForControlCodeRequest())
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
      (videoClients.isNotEmpty() || streamCaptureNeededForControlCodeRequest()) &&
      ticketSessionState != TICKET_SESSION_NEEDS_ATTENTION &&
      lastRootH264BlankProbeResult != "secure_capture_blocked"
  }

  private fun evaluateStreamWatchdog(trigger: String) {
    if (!streamWatchdogShouldRun()) {
      return
    }
    if (controlCodeResultEncoderRefreshActive) {
      streamWatchdogStage = "control_code_result_encoder_refresh"
      return
    }
    if (!hardwareFrameBroadcastAllowed || !hardwareCaptureVerified) {
      streamWatchdogStage = if (hardwareFrameBroadcastAllowed) {
        "waiting_ticket_ready"
      } else {
        "waiting_phone_ready"
      }
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

  private fun configMessage(size: TicketStreamSize, configuredEpoch: Long): String {
    val hardware = rootHardwareH264CaptureEngine.snapshot()
    val codec = TicketScreenConfig.ROOT_HARDWARE_H264_CODEC_STRING
    val transport = TicketScreenConfig.ROOT_HARDWARE_H264_TRANSPORT
    val qualityProfile = TicketScreenConfig.ROOT_HARDWARE_H264_QUALITY_PROFILE
    val captureSource = hardware.captureSource
    val captureMethod = hardware.captureMethod
    val bitrate = TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
    val fps = TicketScreenConfig.ROOT_HARDWARE_H264_FPS
    val feedbackVersion = 1
    val sourceFps = TicketScreenConfig.ROOT_HARDWARE_H264_FPS
    val keyframeIntervalFrames = 1
    val frameDependencyMode = TicketScreenConfig.ROOT_HARDWARE_H264_FRAME_DEPENDENCY_MODE
    val keyFrameInterval = TicketScreenConfig.ROOT_HARDWARE_H264_KEYFRAME_INTERVAL_MILLIS
    val colorCorrection = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_CORRECTION
    val colorStandard = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_STANDARD
    val phoneUptimeMillis = SystemClock.elapsedRealtime()
    return """
      {"type":"config","serverVersion":"$SERVER_VERSION","codec":"$codec","transport":"$transport","captureMode":"$activeCaptureMode","captureSource":${json.encodeToString(captureSource)},"captureMethod":${json.encodeToString(captureMethod)},"rootCapture":true,"frameEnvelope":"$FRAME_ENVELOPE_VERSION","frameDependencyMode":"$frameDependencyMode","streamEpoch":$configuredEpoch,"phoneUptimeMillis":$phoneUptimeMillis,"qualityProfile":"$qualityProfile","colorCorrection":${json.encodeToString(colorCorrection)},"colorStandard":${json.encodeToString(colorStandard)},"width":${size.width},"height":${size.height},"sourceWidth":${size.sourceWidth},"sourceHeight":${size.sourceHeight},"sourceLeftCrop":${size.sourceLeftCrop},"sourceTopCrop":${size.sourceTopCrop},"sourceRightCrop":${size.sourceRightCrop},"sourceBottomCrop":${size.sourceBottomCrop},"sourceVisibleWidth":${size.sourceVisibleWidth},"sourceVisibleHeight":${size.sourceVisibleHeight},"bitrate":$bitrate,"fps":$fps,"sourceFps":$sourceFps,"keyframeIntervalFrames":$keyframeIntervalFrames,"feedbackVersion":$feedbackVersion,"keyFrameIntervalMillis":$keyFrameInterval}
    """.trimIndent()
  }

  private fun videoConfigSnapshot(size: TicketStreamSize): TicketVideoConfigSnapshot? {
    return synchronized(encoderLock) {
      val epoch = streamEpoch
      if (epoch <= 0L) return@synchronized null
      TicketVideoConfigSnapshot(
        epoch = epoch,
        width = size.width,
        height = size.height,
        message = configMessage(size, epoch)
      )
    }
  }

  private fun broadcastConfig(size: TicketStreamSize) {
    val config = videoConfigSnapshot(size) ?: return
    videoClientSnapshot().forEach { client ->
      val sendState = replaceVideoDeliveryStateForConfig(client, config.epoch) ?: return@forEach
      launchVideoClientConfigWriter(client, sendState, config, warmStart = false)
    }
  }

  private fun sendConfigAndWarmStart(client: TicketWebSocket, size: TicketStreamSize) {
    val config = videoConfigSnapshot(size) ?: return
    val sendState = replaceVideoDeliveryStateForConfig(client, config.epoch) ?: return
    launchVideoClientConfigWriter(client, sendState, config, warmStart = true)
  }

  private fun newVideoClientDeliveryState(expectedEpoch: Long = 0L): TicketVideoClientDeliveryState {
    return TicketVideoClientDeliveryState(
      expectedEpoch = expectedEpoch,
      maxFrameBytes = VIDEO_CLIENT_MAX_FRAME_BYTES,
      slowCloseMillis = VIDEO_CLIENT_SLOW_CLOSE_MILLIS
    )
  }

  private fun replaceVideoDeliveryStateForConfig(
    client: TicketWebSocket,
    expectedEpoch: Long
  ): TicketVideoClientDeliveryState? {
    return synchronized(encoderLock) {
      if (expectedEpoch <= 0L || streamEpoch != expectedEpoch) {
        return@synchronized null
      }
      val replacement = newVideoClientDeliveryState(expectedEpoch)
      if (videoSendStates.replace(client, replacement) != null) replacement else null
    }
  }

  private fun markVideoClientConfigReady(
    client: TicketWebSocket,
    expectedState: TicketVideoClientDeliveryState
  ): Boolean {
    var ready = false
    videoSendStates.ifCurrent(client, expectedState) {
      ready = expectedState.markConfigReady()
    }
    return ready
  }

  private fun launchVideoClientConfigWriter(
    client: TicketWebSocket,
    sendState: TicketVideoClientDeliveryState,
    config: TicketVideoConfigSnapshot,
    warmStart: Boolean
  ) {
    TicketVideoClientConfigWriterPump(
      scope = serviceScope,
      slowCloseMillis = VIDEO_CLIENT_SLOW_CLOSE_MILLIS,
      nowMillis = SystemClock::elapsedRealtime,
      isCurrent = { videoDeliveryStateIsCurrent(client, sendState) },
      sendConfig = { isCurrent ->
        client.sendConfigAndAllowBinaryIf(config.message) {
          isCurrent() && sendState.canAcceptConfig()
        }
      },
      markReady = { markVideoClientConfigReady(client, sendState) },
      onConfigured = {
        lastConfigSentAtMillis = SystemClock.elapsedRealtime()
        recordStartupTracePhase(
          "stream_config_sent",
          "width=${config.width} height=${config.height} clients=${videoClients.size}",
          once = true
        )
        if (videoDeliveryStateIsCurrent(client, sendState)) {
          sendCachedKeyFrameOrRequest(
            client,
            if (warmStart) "video_client_warm_start" else "video_client_config_ready"
          )
        }
      },
      onExpired = { blockedMillis ->
        closeVideoClientAfterExpiredWrite(
          client = client,
          expectedState = sendState,
          event = "video_client_config_timeout",
          detail = "blocked_ms=$blockedMillis epoch=${config.epoch}"
        )
      },
      onRejectedCurrent = {
        closeVideoClientIfCurrent(
          client = client,
          expectedState = sendState,
          slow = false,
          event = "video_client_config_failed",
          detail = "epoch=${config.epoch}"
        )
      }
    ).start()
  }

  private fun videoDeliveryStateIsCurrent(
    client: TicketWebSocket,
    expectedState: TicketVideoClientDeliveryState
  ): Boolean {
    return videoSendStates.isCurrent(client, expectedState)
  }

  private fun closeVideoClientIfCurrent(
    client: TicketWebSocket,
    expectedState: TicketVideoClientDeliveryState,
    slow: Boolean,
    event: String,
    detail: String
  ): Boolean {
    val closed = videoSendStates.ifCurrent(client, expectedState) {
        videoSendStates.remove(client)
        expectedState.close()
        if (slow) closedSlowVideoClients += 1L
        client.close()
    }
    if (closed) recordTicketEvent(event, detail)
    return closed
  }

  private fun closeVideoClientAfterExpiredWrite(
    client: TicketWebSocket,
    expectedState: TicketVideoClientDeliveryState,
    event: String,
    detail: String
  ) {
    if (
      closeVideoClientIfCurrent(
        client = client,
        expectedState = expectedState,
        slow = true,
        event = event,
        detail = detail
      )
    ) {
      return
    }
    if (client.close()) {
      closedSlowVideoClients += 1L
      recordTicketEvent(event, detail)
    }
  }

  private fun sendCachedKeyFrameOrRequest(client: TicketWebSocket, reason: String = "video_client_request"): Boolean {
    if (activeCaptureMode == CAPTURE_MODE_IDLE) {
      requestKeyFrame(reason)
      return false
    }
    val cached = latestKeyFrame
    val sourceTail = synchronized(encoderLock) {
      streamEpoch to frameSequence
    }
    val ageMillis = cached?.let { ageMillis(it.cachedAtMillis, SystemClock.elapsedRealtime()) }
    if (
      cached != null &&
      cached.epoch == sourceTail.first &&
      cached.sequence == sourceTail.second &&
      ageMillis != null &&
      ageMillis <= ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
    ) {
      sentFrames += 1
      lastFrameSentAtMillis = SystemClock.elapsedRealtime()
      sendVideoFrame(
        client = client,
        frame = cached.envelope,
        keyFrame = true,
        epoch = cached.epoch,
        sequence = cached.sequence
      )
      return true
    }
    requestKeyFrame(reason)
    return false
  }

  private fun broadcastFrame(
    keyFrame: Boolean,
    timestampUs: Long,
    payload: ByteArray,
    acceptedGeneration: TicketVideoFrameGeneration
  ): TicketVideoDeliveryFrame? {
    val sentAtMillis = SystemClock.elapsedRealtime()
    val deliveryFrame = synchronized(encoderLock) {
      val currentSize = streamSize
      if (
        !streamActive ||
        activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
        !acceptedGeneration.matches(
          currentEpoch = streamEpoch,
          currentWidth = currentSize?.width,
          currentHeight = currentSize?.height
        )
      ) {
        return@synchronized null
      }
      val epoch = streamEpoch
      if (epoch <= 0L) return@synchronized null
      frameSequence += 1
      val sequence = frameSequence
      val buffer = ByteBuffer.allocate(FRAME_ENVELOPE_HEADER_BYTES + payload.size)
      buffer.putInt(FRAME_ENVELOPE_MAGIC)
      buffer.put(if (keyFrame) FRAME_FLAG_KEYFRAME else 0.toByte())
      buffer.putLong(epoch)
      buffer.putLong(sequence)
      buffer.putLong(timestampUs)
      buffer.put(payload)
      val frame = buffer.array()
      clearStartupDisconnectGrace()
      lastFrameBytes = frame.size
      lastFrameSentAtMillis = sentAtMillis
      noteFrameBytes(frame.size, sentAtMillis)
      if (keyFrame) {
        lastKeyFrameBytes = frame.size
        latestKeyFrame = TicketCachedKeyFrame(
          epoch = epoch,
          sequence = sequence,
          envelope = frame,
          cachedAtMillis = sentAtMillis,
          timestampUs = timestampUs
        )
      }
      TicketVideoDeliveryFrame(
        bytes = frame,
        keyFrame = keyFrame,
        epoch = epoch,
        sequence = sequence
      )
    } ?: return null
    sentFrames += 1
    videoClientSnapshot().forEach { client ->
      sendVideoFrame(
        client = client,
        frame = deliveryFrame.bytes,
        keyFrame = deliveryFrame.keyFrame,
        epoch = deliveryFrame.epoch,
        sequence = deliveryFrame.sequence
      )
    }
    return deliveryFrame
  }

  private fun sendVideoFrame(
    client: TicketWebSocket,
    frame: ByteArray,
    keyFrame: Boolean,
    epoch: Long,
    sequence: Long
  ) {
    val sendState = videoSendStates.current(client) ?: return
    val decision = sendState.offer(
      frame = TicketVideoDeliveryFrame(
        bytes = frame,
        keyFrame = keyFrame,
        epoch = epoch,
        sequence = sequence
      ),
      nowMillis = SystemClock.elapsedRealtime()
    )
    applyVideoDeliveryEffects(client, sendState, decision)
    if (decision.closeSlowClient) return
    decision.frameToWrite?.let { firstFrame ->
      launchVideoFrameWriter(client, sendState, firstFrame, decision.writeToken)
    }
  }

  private fun launchVideoFrameWriter(
    client: TicketWebSocket,
    sendState: TicketVideoClientDeliveryState,
    firstFrame: TicketVideoDeliveryFrame,
    firstWriteToken: Long
  ) {
    TicketVideoClientWriterPump(
      scope = serviceScope,
      state = sendState,
      slowWriteMillis = VIDEO_CLIENT_SLOW_WRITE_MILLIS,
      slowCloseMillis = VIDEO_CLIENT_SLOW_CLOSE_MILLIS,
      nowMillis = SystemClock::elapsedRealtime,
      isCurrent = { videoDeliveryStateIsCurrent(client, sendState) },
      sendBinary = { frame, canSend ->
        client.sendBinaryIf(frame.bytes, canSend)
      },
      onDecision = { decision ->
        applyVideoDeliveryEffects(client, sendState, decision)
      },
      onSlowWrite = { frame, durationMillis ->
        val current = videoSendStates.ifCurrent(client, sendState) {
          slowVideoWrites += 1L
        }
        if (current) {
          recordTicketEvent(
            "video_write_slow",
            "duration_ms=$durationMillis key=${frame.keyFrame} sequence=${frame.sequence}"
          )
        }
      },
      onWriteExpired = { frame, blockedMillis ->
        closeVideoClientAfterExpiredWrite(
          client = client,
          expectedState = sendState,
          event = "video_client_write_timeout",
          detail = "blocked_ms=$blockedMillis key=${frame.keyFrame} sequence=${frame.sequence}"
        )
      },
      onRejectedCurrent = {
        closeVideoClientIfCurrent(
          client = client,
          expectedState = sendState,
          slow = false,
          event = "video_client_write_failed",
          detail = "sequence=${firstFrame.sequence}"
        )
      }
    ).start(
      firstFrame = firstFrame,
      firstWriteToken = firstWriteToken
    )
  }

  private fun applyVideoDeliveryEffects(
    client: TicketWebSocket,
    expectedState: TicketVideoClientDeliveryState,
    decision: TicketVideoDeliveryDecision
  ) {
    val accepted = videoSendStates.ifCurrent(client, expectedState) {
        if (decision.droppedFrames > 0) {
          droppedVideoFrames += decision.droppedFrames.toLong()
        }
        if (decision.closeSlowClient) {
          videoSendStates.remove(client)
          expectedState.close()
          closedSlowVideoClients += 1L
          client.close()
        }
    }
    if (!accepted) return
    if (
      decision.dropReason == TicketVideoClientDeliveryState.DROP_PENDING_REPLACED ||
      decision.dropReason == TicketVideoClientDeliveryState.DROP_EPOCH_MISMATCH ||
      decision.dropReason == TicketVideoClientDeliveryState.DROP_STALE_FRAME ||
      decision.dropReason == TicketVideoClientDeliveryState.DROP_UNEXPECTED_DELTA ||
      decision.dropReason == TicketVideoClientDeliveryState.DROP_FRAME_TOO_LARGE
    ) {
      recordTicketEvent(
        "video_client_frame_drop",
        "reason=${decision.dropReason} dropped=${decision.droppedFrames} request_refresh=${decision.requestImmediateRefresh}"
      )
    }
    if (decision.requestImmediateRefresh) {
      val reason = when (decision.dropReason) {
        TicketVideoClientDeliveryState.DROP_UNEXPECTED_DELTA -> "video_client_unexpected_delta"
        TicketVideoClientDeliveryState.DROP_FRAME_TOO_LARGE -> "video_client_frame_too_large"
        else -> "video_client_refresh"
      }
      rootHardwareH264CaptureEngine.requestImmediateRefresh(reason)
    }
    if (decision.closeSlowClient) {
      recordTicketEvent(
        "video_client_closed_slow",
        "blocked_ms=${decision.blockedMillis} dropped=${decision.droppedFrames}"
      )
    }
  }

  private fun handleRootHardwareH264CaptureFrame(frame: TicketRootCaptureFrame) {
    val encodedAtMillis = SystemClock.elapsedRealtime()
    if (!frame.keyFrame) {
      droppedVideoFrames += 1L
      rootHardwareH264CaptureEngine.requestImmediateRefresh("service_rejected_unexpected_delta")
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      return
    }
    val acceptedGeneration = synchronized(encoderLock) {
      if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
        null
      } else {
        ensureFrameEpoch("frame")
        val currentSize = streamSize
        if (
          currentSize == null ||
          currentSize.width != frame.width ||
          currentSize.height != frame.height
        ) {
          return@synchronized null
        }
        encodedFrames += 1
        lastFrameEncodedAtMillis = encodedAtMillis
        keyFrames += 1
        lastKeyFrameEncodedAtMillis = encodedAtMillis
        TicketVideoFrameGeneration(
          epoch = streamEpoch,
          width = frame.width,
          height = frame.height
        )
      }
    }
    if (acceptedGeneration == null) {
      return
    }
    recordStartupTracePhase("first_keyframe_encoded", "encoded_frames=$encodedFrames", once = true)
    if (!hardwareFrameBroadcastAllowed) {
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      return
    }
    val firstVisibleFrame = sentFrames == 0L
    val deliveredFrame = broadcastFrame(
      keyFrame = true,
      timestampUs = frame.timestampUs,
      payload = frame.payload,
      acceptedGeneration = acceptedGeneration
    ) ?: run {
      hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
      return
    }
    hardwareCaptureSnapshot = rootHardwareH264CaptureEngine.snapshot()
    if (hardwareCaptureVerified || firstVisibleFrame) {
      recordStartupTracePhase("first_visible_frame_sent", "sequence=${deliveredFrame.sequence} keyframe=true", once = true, complete = hardwareCaptureVerified)
      if (lastStreamRecoveryResult == "started") {
        lastStreamRecoveryResult = "succeeded"
        lastStreamRecoveryFailureReason = null
        lastStreamRecoveryAtMillis = SystemClock.elapsedRealtime()
        streamWatchdogStage = "healthy"
        recordTicketEvent("stream_watchdog_recovery_succeeded", lastStreamWatchdogReason.orEmpty())
        recordTicketEvent(
          "stream_recovery_completed",
          "reason=${lastStreamWatchdogReason.orEmpty()} frame_sequence=${deliveredFrame.sequence} keyframe=true clients=${videoClients.size}"
        )
      }
    }
    if ((firstVisibleFrame || ticketSessionState == TICKET_SESSION_STARTING) && hardwareCaptureVerified) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_hardware_h264_first_visible_frame")
      publishControlCodeFastReadyAfterSessionProof()
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
      rootHardwareH264CaptureEngine.stopAndJoin("secure_capture_blocked:$reason")
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
      pendingRootHardwareH264KeyFrame.offer(reason)
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
      (videoClients.isEmpty() && !streamCaptureNeededForControlCodeRequest())
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
    recordTicketEventForTrace(event, detail, startupTraceCorrelation.current())
  }

  private fun recordTicketEventForCurrentVideoSocket(
    event: String,
    detail: String,
    info: TicketClientInfo
  ): Boolean {
    val traceId = boundedStartupTraceCorrelationId(info.startupTraceCorrelationId)
    if (!startupTraceCorrelation.isCurrentVideoSocket(traceId, info.generation)) {
      return false
    }
    recordTicketEventForTrace(event, detail, traceId)
    return true
  }

  private fun recordTicketEventForTrace(event: String, detail: String = "", traceId: String) {
    val cleanEvent = event.take(96)
    val safeFields = TicketTracePrivacy.allowlistedFields(detail)
    enqueueTicketSpacetimeTraceEvent(cleanEvent, safeFields, boundedStartupTraceCorrelationId(traceId))
  }

  private fun enqueueTicketSpacetimeTraceEvent(
    event: String,
    detailFields: Map<String, String>,
    traceId: String
  ) {
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
      if (traceId.isNotBlank()) put("traceId", traceId)
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
      event.startsWith("wake_") ||
      event.startsWith("fast_public_open_") ||
      event.startsWith("root_hardware") ||
      event.startsWith("root_capture") ||
      event == "root_readiness" ||
      event.startsWith("loading_") ||
      event.startsWith("client_") ||
      event.startsWith("keyframe") ||
      event.startsWith("ticket_brightness_") ||
      event.startsWith("latest_ticket_reselect_") ||
      event.startsWith("control_code_") ||
      event.startsWith("ticket_control_code_") ||
      event.startsWith("ticket_card_") ||
      event.startsWith("ticket_detail_") ||
      event.startsWith("ticket_registration_") ||
      event.startsWith("ticket_slider_") ||
      event == "ticket_state_event" ||
      event == "vivi_hard_reset" ||
      event == "vivi_reauth_package_clear" ||
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
      latestKeyFrame = null
      lastFrameSentAtMillis = 0L
      lastKeyFrameEncodedAtMillis = 0L
      lastFrameBytes = 0
      lastKeyFrameBytes = 0
      estimatedSendBitrate = 0L
      sendBitrateWindowStartedAtMillis = 0L
      sendBitrateWindowBytes = 0L
      invalidateVideoDeliveryStatesForEpoch(streamEpoch)
    }
    recordTicketEvent("stream_epoch_reset", reason)
  }

  private fun invalidateVideoDeliveryStatesForEpoch(expectedEpoch: Long) {
    videoSendStates.replaceAll { newVideoClientDeliveryState(expectedEpoch) }
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
    val secureCaptureBypass = secureWindowCaptureBypassOwner.snapshot()
    val startupPreflight = lastStartupPreflight
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
      frameDependencyMode = if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
        TicketScreenConfig.ROOT_HARDWARE_H264_FRAME_DEPENDENCY_MODE
      } else {
        ""
      },
      streamEpoch = streamEpoch,
      frameSequence = frameSequence,
      lastKeyFrameSequence = latestKeyFrame?.sequence ?: 0L,
      qualityProfile = when (activeCaptureMode) {
        CAPTURE_MODE_ROOT_HARDWARE_H264 -> TicketScreenConfig.ROOT_HARDWARE_H264_QUALITY_PROFILE
        else -> "idle"
      },
      configuredWidth = streamSize?.width,
      configuredHeight = streamSize?.height,
      configuredSourceWidth = streamSize?.sourceWidth,
      configuredSourceHeight = streamSize?.sourceHeight,
      sourceLeftCrop = streamSize?.sourceLeftCrop ?: TicketScreenConfig.TICKET_MEDIA_LEFT_CROP_SOURCE_PIXELS,
      sourceTopCrop = streamSize?.sourceTopCrop ?: TicketScreenConfig.TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS,
      sourceRightCrop = streamSize?.sourceRightCrop ?: TicketScreenConfig.TICKET_MEDIA_RIGHT_CROP_SOURCE_PIXELS,
      sourceBottomCrop = streamSize?.sourceBottomCrop ?: TicketScreenConfig.TICKET_MEDIA_BOTTOM_CROP_SOURCE_PIXELS,
      sourceVisibleWidth = streamSize?.sourceVisibleWidth,
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
      secureWindowCaptureBypassActive = secureCaptureBypass.active,
      secureWindowCaptureBypassMessage = secureCaptureBypass.message,
      startupPreflightOutcome = startupPreflight.outcome,
      startupPreflightTotalMillis = startupPreflight.totalMillis,
      startupPreflightPortraitMillis = startupPreflight.portraitMillis,
      startupPreflightSecureCaptureMillis = startupPreflight.secureCaptureMillis,
      lastStartupPreflightAgoMillis = ageMillis(startupPreflight.completedAtMillis, nowMillis),
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
      viviReauth = viviReauthSnapshot.let { snapshot ->
        TicketViviReauthHealth(
          status = snapshot.status,
          phase = snapshot.phase,
          reason = snapshot.reason,
          credentialRevisionObserved = snapshot.credentialRevision.isNotBlank(),
          completedAgoMillis = ageMillis(viviReauthCompletedAtMillis, nowMillis)
        )
      },
      latestTicketReselect = TicketLatestTicketReselectHealth(
        status = "retired",
        active = false,
        reason = "ticket_action_v3_only"
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
      actionPanelDarkLease = ticketActionPanelDarkLeaseSnapshot.let { lease ->
        TicketActionPanelDarkLeaseHealth(
          active = lease.active,
          ownerActionId = lease.ownerActionId,
          ageMillis = ageMillis(lease.acquiredAtUptimeMillis, nowMillis),
          lastZeroConfirmationAgoMillis = ageMillis(
            lease.lastZeroConfirmedAtUptimeMillis,
            nowMillis
          ),
          failure = lease.failure,
          failures = ticketActionPanelDarkLeaseFailures.get(),
          physicalTouchPreempted = lease.physicalTouchPreempted,
          releaseReason = lease.releaseReason,
          launchExitCode = lease.launchExitCode,
          launchDurationMillis = lease.launchDurationMillis,
          lastVerifierClassification = lease.lastVerifierClassification,
          lastVerifierExitCode = lease.lastVerifierExitCode,
          lastVerifierDurationMillis = lease.lastVerifierDurationMillis,
          helperStage = lease.helperStage,
          helperExitCode = lease.helperExitCode
        )
      },
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

  private fun recentLiveRawTicketProofForControlCode(
    nowMillis: Long,
    maxAgeMillis: Long
  ): TicketViviStateMemorySnapshot? {
    if (
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      !hardwareCaptureVerified ||
      (ticketSessionState != TICKET_SESSION_LIVE &&
        ticketSessionState != TICKET_SESSION_STARTING &&
        ticketSessionState != TICKET_SESSION_CONTROL_EXIT) ||
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

  /**
   * The browser's fast-state revision is the handoff hint. The current stream, raw-ticket marker,
   * and rooted hierarchy retained when the last fast-ready state was published are the local
   * proof for the immediate geometry path. No new hierarchy read belongs on the healthy path.
   */
  private fun controlCodeFastVisualMarkerFresh(
    nowMillis: Long = SystemClock.elapsedRealtime()
  ): Boolean {
    return streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
      hardwareCaptureVerified && lastPixelTicketState == TICKET_PIXEL_STATE_RAW_TICKET &&
      (ageMillis(lastFrameSentAtMillis, nowMillis) ?: Long.MAX_VALUE) <= LIVE_FRAME_MAX_AGE_MILLIS
  }

  private fun controlCodeFastStateRevisionAccepted(
    revision: String,
    nowMillis: Long = SystemClock.elapsedRealtime()
  ): Boolean {
    val cleanRevision = revision.trim()
    if (cleanRevision.isBlank() || !controlCodeFastVisualMarkerFresh(nowMillis)) {
      return false
    }
    val parts = cleanRevision.split(':')
    if (parts.size != 4 || parts[0] != "phone") {
      return false
    }
    val issuedAtMillis = parts[1].toLongOrNull() ?: return false
    val revisionEpoch = parts[2].toLongOrNull() ?: return false
    val revisionSequence = parts[3].toLongOrNull() ?: return false
    if (
      issuedAtMillis <= 0L ||
      revisionEpoch <= 0L ||
      revisionSequence <= 0L ||
      issuedAtMillis > nowMillis ||
      nowMillis - issuedAtMillis > CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
    ) {
      return false
    }
    // The epoch is the coordinator key. A stream restart changes it and forces the single inline
    // preparation path; an unchanged stream may safely reuse a revision once its frame sequence
    // is already at or beyond the revision watermark.
    return streamEpoch == revisionEpoch && frameSequence >= revisionSequence
  }

  private suspend fun ensureControlCodeRequestPreflight(
    requestedFastRevision: String,
    fastStateAcceptedAtAdmission: Boolean,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): ControlCodeFastPreflight {
    val nowMillis = SystemClock.elapsedRealtime()
    val revisionAccepted = fastStateAcceptedAtAdmission ||
      controlCodeFastStateRevisionAccepted(requestedFastRevision, nowMillis)
    if (revisionAccepted) {
      val visualDetail = awaitStableTicketVisualActionObservation(
        "control_code_fast_state_revision",
        CONTROL_CODE_RECENT_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS
      )
      if (visualDetail?.state !in setOf(
          TicketVisualPhoneState.ACTIVATED_DETAIL,
          TicketVisualPhoneState.UNACTIVATED_DETAIL
        )) {
        recordTicketEvent("control_code_fast_state_revision_rejected", "reason=ticket_detail_visual_unproved")
        return ControlCodeFastPreflight(ready = false)
      }
      markControlCodeRequestPhase(phases, "fast_state_revision_accepted", requestStartedAtMillis)
      recordTicketEvent(
        "control_code_fast_state_revision_accepted",
        "revision_present=${requestedFastRevision.isNotBlank()} epoch=$streamEpoch sequence=$frameSequence"
      )
      return ControlCodeFastPreflight(ready = true, ticketDetailHierarchy = "visual_ticket_detail")
    }

    markControlCodeRequestPhase(phases, "fast_state_revision_missed", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_fast_state_revision_missed",
      "revision_present=${requestedFastRevision.isNotBlank()}"
    )
    reuseRecentTicketDetailForControlCode(phases, requestStartedAtMillis)?.let { visualMarker ->
      markControlCodeRequestPhase(phases, "fast_state_inline_preparation_accepted", requestStartedAtMillis)
      recordTicketEvent(
        "control_code_fast_state_inline_preparation_accepted",
        "source=root_h264_recent_detail_visual_proof epoch=$streamEpoch sequence=$frameSequence"
      )
      return ControlCodeFastPreflight(ready = true, ticketDetailHierarchy = visualMarker)
    }
    val ticketDetailHierarchy = ensureTicketSessionForControlCodeRequest(phases, requestStartedAtMillis)
    if (ticketDetailHierarchy.isNullOrBlank()) {
      return ControlCodeFastPreflight(ready = false)
    }
    // Inline preparation is the cold/stale handoff. It already proved the current
    // hierarchy and rooted Aztec frame, so requiring a second, independently
    // published visual marker here would reject a valid cold request before the
    // popup can be opened.
    markControlCodeRequestPhase(phases, "fast_state_inline_preparation_accepted", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_fast_state_inline_preparation_accepted",
      "epoch=$streamEpoch sequence=$frameSequence"
    )
    return ControlCodeFastPreflight(ready = true, ticketDetailHierarchy = ticketDetailHierarchy)
  }

  /**
   * A stale browser revision does not make a recent rooted ticket proof stale.
   * Reuse the retained hierarchy only after fresh rooted H.264 Aztec samples
   * agree with it; this removes the duplicate three-second hierarchy dump while
   * keeping the same detail-state and visual-state proof boundary.
   */
  private suspend fun reuseRecentTicketDetailForControlCode(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): String? {
    if (
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      !hardwareCaptureVerified ||
      (ticketSessionState != TICKET_SESSION_LIVE &&
        ticketSessionState != TICKET_SESSION_CONTROL_EXIT)
    ) {
      return null
    }
    val visualProofed = awaitStableTicketVisualActionObservation(
      reason = "control_code_stale_revision_recent_detail",
      timeoutMillis = CONTROL_CODE_RECENT_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS
    )
    if (visualProofed?.state !in setOf(
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        TicketVisualPhoneState.UNACTIVATED_DETAIL
      )) {
      recordTicketEvent(
        "control_code_stale_revision_recent_detail_rejected",
        "reason=aztec_visual_not_proven"
      )
      return null
    }
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_recent_detail_reused")
    }
    markControlCodeRequestPhase(phases, "request_ticket_detail_ready", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_request_ticket_detail_reused",
      "source=root_h264_recent_detail_visual_proof fast_revision_missed=true epoch=$streamEpoch sequence=$frameSequence"
    )
    return "visual_ticket_detail"
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
    if (!beginControlCodePanelDarkMutation("vivi_launch:$reason")) return
    recordTicketEvent("wake_launch_vivi_root", reason)
    val boundedTimeoutMillis = timeoutMillis.coerceAtLeast(1L)
    val startedAtMillis = SystemClock.elapsedRealtime()
    launchVivi()
    recordTicketEvent(
      "wake_launch_vivi_root",
      "ok=true duration_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)} " +
        "timeout_ms=$boundedTimeoutMillis mode=activity_intent"
    )
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

  private suspend fun viviFocusedForFastPublicOpen(reason: String): Boolean {
    if (!ticketScreenInteractive()) {
      recordTicketEvent("fast_public_open_vivi_focus_missed", "reason=$reason interactive=false")
      return false
    }
    val focused = focusedWindowSnapshot()
    val focusedVivi = focused?.contains(TicketScreenConfig.VIVI_PACKAGE) == true
    if (!focusedVivi) {
      recordTicketEvent("fast_public_open_vivi_focus_missed", "reason=$reason focused=${focused.orEmpty().take(120)}")
    } else {
      recordStartupTracePhase(
        "vivi_foreground_confirmed",
        "interactive=true reason=$reason",
        once = true
      )
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
    return RootResult(
      exitCode = 126,
      stdout = "",
      stderr = "ticket_hierarchy_detection_retired",
      command = "visual_ticket_detection_only",
      durationMs = 0L
    )
  }

  private suspend fun observeRootViviStateForWake(
    reason: String,
    timeoutMillis: Long
  ): RootViviObservation {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val visual = awaitStableTicketVisualActionObservation(
      reason = "wake_visual_observation:$reason",
      timeoutMillis = timeoutMillis
    )
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    if (visual == null) {
      viviStateMemory.record(
        state = TicketViviRecoveryState.UNKNOWN_VIVI,
        ticketId = null,
        source = "root_visual_empty",
        reason = reason
      )
      recordRootReadiness("$reason:UNKNOWN_VIVI", durationMillis)
      return RootViviObservation(
        TicketViviRecoveryState.UNKNOWN_VIVI,
        null,
        durationMillis,
        "visual_detection_unavailable"
      )
    }
    val state = visual.toRecoveryState()
    viviStateMemory.record(
      state = state,
      ticketId = null,
      source = "root_visual",
      reason = reason
    )
    recordRootReadiness("$reason:${state.name}", durationMillis)
    return RootViviObservation(state, null, durationMillis, visual = visual)
  }

  private suspend fun observeTicketDetailForWakeWithRoot(
    reason: String,
    wakeStartedAtMillis: Long,
    budgetMillis: Long = TICKET_WAKE_BUDGET_MILLIS,
    maxRecoveryActions: Int = TICKET_WAKE_RECOVERY_MAX_ACTIONS,
    recoveryActionRepeatCooldownMillis: Long = 0L,
    ticketCardSelectionGraceMillis: Long = 0L,
    extendBudgetAfterRecoveryAction: Boolean = true,
    parallelTicketDetailProofAfterTicketCardSelection: Boolean = false,
    requireNewTicketRegistration: Boolean = false,
    requireTicketListWithRegistrationButton: Boolean = false,
    requireFreshAztecVisualProof: Boolean = false,
    requireUnactivatedRegistration: Boolean = false,
    requireLatestTicketSelection: Boolean = false
  ): TicketAutopilotResult {
    var lastState = TicketViviRecoveryState.UNKNOWN_VIVI
    var lastStep = "wake_root_unavailable"
    var finalActionCategory = "none"
    var finalActionOutcome = "not_attempted"
    var attemptedGeneratedWakeHeal = false
    var attemptedPopupWakeReturn = false
    var attemptedWakeRelaunch = false
    var wakeRecoveryActions = 0
    var rootUnavailableAttempts = 0
    var lastRecoveryActionKey = ""
    var lastRecoveryActionAtMillis = 0L
    var ticketCardSelectionGraceDeadlineMillis = 0L
    var newTicketRegistrationAttempted = false
    var latestTicketSelectionAction = ""
    val canAcceptHealedTicketDetail =
      TicketLatestTicketReselectRecoveryPolicy.canAcceptHealedTicketDetail(
        requireNewTicketRegistration = requireNewTicketRegistration,
        requireTicketListWithRegistrationButton = requireTicketListWithRegistrationButton,
        requireFreshAztecVisualProof = requireFreshAztecVisualProof,
        requireUnactivatedRegistration = requireUnactivatedRegistration,
        requireLatestTicketSelection = requireLatestTicketSelection
      )
    fun remainingMillis(activeBudgetMillis: Long): Long {
      return TicketLatestTicketReselectRecoveryPolicy.remainingMillis(
        wakeStartedAtMillis = wakeStartedAtMillis,
        launchBudgetMillis = activeBudgetMillis,
        ticketCardSelectionGraceDeadlineMillis = ticketCardSelectionGraceDeadlineMillis,
        nowMillis = SystemClock.elapsedRealtime()
      )
    }
    fun result(
      success: Boolean,
      state: TicketViviRecoveryState,
      step: String,
      finalHierarchy: String = "",
      finalSliderBounds: TicketViviGraphicBounds? = null
    ): TicketAutopilotResult {
      return TicketAutopilotResult(
        success = success,
        state = state,
        step = step,
        finalActionCategory = finalActionCategory,
        finalActionOutcome = finalActionOutcome,
        finalHierarchy = finalHierarchy,
        finalSliderBounds = finalSliderBounds
      )
    }
    while (true) {
      val activeBudgetMillis = if (wakeRecoveryActions > 0 && extendBudgetAfterRecoveryAction) {
        maxOf(budgetMillis, TICKET_WAKE_RECOVERY_BUDGET_MILLIS)
      } else {
        budgetMillis
      }
      val currentRemainingMillis = remainingMillis(activeBudgetMillis)
      if (currentRemainingMillis <= 0L) {
        return result(false, lastState, "wake_budget_exhausted:$lastStep")
      }
      val timeoutMillis = wakeRootDumpTimeoutMillis(rootUnavailableAttempts, currentRemainingMillis)
      val observation = observeRootViviStateForWake("wake_root:$reason", timeoutMillis = timeoutMillis)
      val state = observation.state
      lastState = state
      val visual = observation.visual
      if (visual != null) {
        rootUnavailableAttempts = 0
        lastStep = "wake_visual_${visual.state.wireName}"
        if (visual.state != TicketVisualPhoneState.UNKNOWN) {
          recordTicketWakePhase("vivi_foreground", wakeStartedAtMillis)
        }
        if (requireTicketListWithRegistrationButton &&
          visual.state == TicketVisualPhoneState.TICKET_LIST &&
          visual.latestRegistrationCard() != null
        ) {
          recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
          return result(true, state, "wake_visual_ticket_list_with_registration_button")
        }
        if (visual.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
          !requireTicketListWithRegistrationButton &&
          (!requireLatestTicketSelection || latestTicketSelectionAction.isNotBlank()) &&
          (!requireNewTicketRegistration || newTicketRegistrationAttempted || visual.sliderBounds != null)
        ) {
          val sliderBounds = visual.sliderBounds?.let(::ticketVisualProbeBoundsToDevice)
          if (!requireUnactivatedRegistration || sliderBounds != null) {
            recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
            return result(
              true,
              TicketViviRecoveryState.TICKET_DETAIL,
              if (sliderBounds != null) "wake_visual_unactivated_registration_ready" else "wake_visual_ticket_detail_ready",
              finalSliderBounds = sliderBounds
            )
          }
        }
        if (visual.state == TicketVisualPhoneState.ACTIVATED_DETAIL &&
          !requireTicketListWithRegistrationButton &&
          !requireUnactivatedRegistration &&
          (!requireLatestTicketSelection || latestTicketSelectionAction.isNotBlank()) &&
          (!requireNewTicketRegistration || newTicketRegistrationAttempted)
        ) {
          recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
          return result(true, TicketViviRecoveryState.TICKET_DETAIL, "wake_visual_ticket_detail_ready")
        }
        if (wakeRecoveryActions < maxRecoveryActions) {
          val visualTarget = when (visual.state) {
            TicketVisualPhoneState.TICKET_LIST -> if (
              requireNewTicketRegistration || requireUnactivatedRegistration || requireLatestTicketSelection
            ) {
              visual.latestRegistrationCard()?.registrationBounds
            } else {
              visual.uniqueActivatedDetailCard()?.activatedDetailBounds
            }
            TicketVisualPhoneState.ACTIVATED_DETAIL,
            TicketVisualPhoneState.UNACTIVATED_DETAIL,
            TicketVisualPhoneState.BLOCKED -> visual.backBounds
            else -> null
          }
          if (visualTarget != null) {
            val actionKey = "${visual.state.wireName}:${visualTarget.left}:${visualTarget.top}:${visualTarget.right}:${visualTarget.bottom}"
            val nowMillis = SystemClock.elapsedRealtime()
            if (!recoveryActionCoolingDown(
                actionKey,
                lastRecoveryActionKey,
                nowMillis,
                lastRecoveryActionAtMillis,
                recoveryActionRepeatCooldownMillis
              )
            ) {
              val tapped = tapTicketVisualProbeBounds(visualTarget, "wake_visual_recovery:$reason")
              lastRecoveryActionKey = actionKey
              lastRecoveryActionAtMillis = nowMillis
              wakeRecoveryActions += 1
              finalActionCategory = if (visual.state == TicketVisualPhoneState.TICKET_LIST) {
                "ticket_card_selection"
              } else {
                "visual_navigation"
              }
              finalActionOutcome = if (tapped) "succeeded" else "failed"
              if (tapped && visual.state == TicketVisualPhoneState.TICKET_LIST) {
                newTicketRegistrationAttempted = requireNewTicketRegistration || requireUnactivatedRegistration
                latestTicketSelectionAction = "visual_latest_ticket_card"
              }
              lastStep = "wake_visual_action_${if (tapped) "sent" else "failed"}"
              if (tapped) {
                delay(TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS)
                continue
              }
            }
          }
        }
      }
      if (observation.hierarchy.isNullOrBlank()) {
        rootUnavailableAttempts += 1
        if (visual == null) lastStep = "wake_visual_unavailable"
      } else {
        rootUnavailableAttempts = 0
        lastStep = "wake_root_${state.name.lowercase()}"
        if (state != TicketViviRecoveryState.BLANK && state != TicketViviRecoveryState.OUTSIDE_VIVI) {
          recordTicketWakePhase("vivi_foreground", wakeStartedAtMillis)
        }
        if (
          requireTicketListWithRegistrationButton &&
          state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD &&
          TicketViviPageEnforcer.isTicketListWithCardAndRegistrationButton(observation.hierarchy.orEmpty())
        ) {
          recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
          return result(true, state, "wake_root_ticket_list_with_registration_button")
        }
        val unactivatedRegistrationDetail =
          TicketViviPageEnforcer.isUnactivatedRegistrationDetail(observation.hierarchy.orEmpty())
        val alreadyUnactivatedRegistration =
          requireUnactivatedRegistration && unactivatedRegistrationDetail
        if (state == TicketViviRecoveryState.TICKET_DETAIL &&
          !requireTicketListWithRegistrationButton &&
          (!requireLatestTicketSelection || latestTicketSelectionAction.isNotBlank()) &&
          ((!requireNewTicketRegistration || newTicketRegistrationAttempted) || alreadyUnactivatedRegistration)
        ) {
          if (requireUnactivatedRegistration &&
            TicketViviPageEnforcer.isUnactivatedRegistrationDetail(observation.hierarchy.orEmpty())) {
            val semanticBounds = TicketViviPageEnforcer.ticketRegistrationSliderBoundsForHierarchy(observation.hierarchy.orEmpty())
            val sliderBounds = semanticBounds?.let {
              detectFreshTicketRegistrationSliderBounds(
                hierarchyBounds = it,
                reason = "wake_root_unactivated:$reason"
              )
            }
            if (sliderBounds != null) {
              recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
              return result(
                true,
                state,
                "wake_root_unactivated_registration_ready",
                observation.hierarchy.orEmpty(),
                finalSliderBounds = sliderBounds
              )
            }
            lastStep = "wake_root_unactivated_visual_unproved"
          }
          if (!requireUnactivatedRegistration) {
            val upcomingPreValidityTicketDetail =
              TicketViviPageEnforcer.isUpcomingPreValidityTicketDetail(observation.hierarchy.orEmpty())
            val unactivatedDetailSelected =
              requireLatestTicketSelection &&
              TicketLatestTicketReselectRecoveryPolicy.isTicketDetailSelectionAction(
                latestTicketSelectionAction
              ) && unactivatedRegistrationDetail
            val upcomingPreValidityDetailSelected =
              requireLatestTicketSelection &&
                latestTicketSelectionAction == "open_upcoming_time_ticket_detail_card" &&
                upcomingPreValidityTicketDetail
            val freshAztecVisualProofed = if (
              requireFreshAztecVisualProof &&
              !unactivatedDetailSelected &&
              !upcomingPreValidityDetailSelected
            ) {
              verifyFreshTicketDetailVisualProof("wake_root:$reason")
            } else {
              false
            }
            val ticketDetailProofed = TicketLatestTicketReselectRecoveryPolicy.ticketDetailProofAccepted(
              requireFreshAztecVisualProof = requireFreshAztecVisualProof,
              requireLatestTicketSelection = requireLatestTicketSelection,
              latestTicketSelectionAction = latestTicketSelectionAction,
              unactivatedRegistrationDetail = unactivatedRegistrationDetail,
              upcomingPreValidityTicketDetail = upcomingPreValidityTicketDetail,
              freshAztecVisualProofed = freshAztecVisualProofed
            )
            if (ticketDetailProofed) {
              recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
              return result(
                true,
                state,
                if (
                  requireLatestTicketSelection &&
                  (unactivatedDetailSelected || upcomingPreValidityDetailSelected)
                ) {
                  "wake_root_latest_ticket_semantic_proved"
                } else if (requireNewTicketRegistration) {
                  "wake_root_ticket_detail_after_new_registration"
                } else {
                  "wake_root_ticket_detail_aztec_proved"
                },
                observation.hierarchy.orEmpty()
              )
            }
            lastStep = "wake_root_ticket_detail_aztec_visual_unproved"
          }
        }
        if (state == TicketViviRecoveryState.LOGIN_REQUIRED) {
          recordTicketEvent("vivi_login_owner_action_required", "source=wake_root")
          lastStep = "wake_root_login_owner_action_required"
          break
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
            if (canAcceptHealedTicketDetail) {
              recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
              return result(true, TicketViviRecoveryState.TICKET_DETAIL, lastStep)
            }
            continue
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
            if (canAcceptHealedTicketDetail) {
              recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
              return result(true, TicketViviRecoveryState.TICKET_DETAIL, lastStep)
            }
            continue
          }
        }
        val recoveryAction = if (
          requireTicketListWithRegistrationButton && state == TicketViviRecoveryState.TICKET_DETAIL
        ) {
          TicketViviPageEnforcer.ticketDetailReturnToListActionForHierarchy(observation.hierarchy)
        } else if (
          requireLatestTicketSelection &&
          latestTicketSelectionAction.isBlank() &&
          state == TicketViviRecoveryState.TICKET_DETAIL
        ) {
          TicketViviPageEnforcer.ticketDetailReturnToListActionForHierarchy(observation.hierarchy)
        } else if (
          requireLatestTicketSelection && state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD
        ) {
          TicketViviPageEnforcer.ticketCardDetailActionForHierarchy(observation.hierarchy)
        } else if (
          requireNewTicketRegistration && state == TicketViviRecoveryState.TICKET_DETAIL
        ) {
          TicketViviPageEnforcer.ticketDetailReturnToListActionForHierarchy(observation.hierarchy)
        } else if (
          requireTicketListWithRegistrationButton && state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD
        ) {
          null
        } else if (
          requireNewTicketRegistration && state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD
        ) {
          TicketViviPageEnforcer.bestTicketCardActionForHierarchy(observation.hierarchy)
        } else if (state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD) {
          TicketViviPageEnforcer.ticketCardDetailActionForHierarchy(observation.hierarchy)
        } else {
          TicketViviPageEnforcer.recoveryActionForHierarchy(observation.hierarchy)
        }
        val recoveryActionKey = recoveryAction?.let { "${state.name}:${it.reason}:${it.x}:${it.y}" }.orEmpty()
        val actionNowMillis = SystemClock.elapsedRealtime()
        val sameActionCoolingDown = recoveryActionCoolingDown(
          actionKey = recoveryActionKey,
          lastActionKey = lastRecoveryActionKey,
          nowMillis = actionNowMillis,
          lastActionAtMillis = lastRecoveryActionAtMillis,
          cooldownMillis = recoveryActionRepeatCooldownMillis
        )
        val actionRemainingMillis = remainingMillis(activeBudgetMillis)
        val actionEligible =
          recoveryAction != null &&
          !sameActionCoolingDown &&
          actionRemainingMillis >= TICKET_WAKE_RECOVERY_MIN_ACTION_TIMEOUT_MILLIS &&
          wakeRecoveryActions < maxRecoveryActions
        if (actionEligible) {
          val action = checkNotNull(recoveryAction)
          finalActionCategory = TicketLatestTicketReselectRecoveryPolicy.actionCategory(action.reason)
          val actionSucceeded = attemptWakeRecoveryActionForRootWake(
            state = state,
            hierarchy = observation.hierarchy,
            action = action,
            reason = reason,
            timeoutMillis = minOf(NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS, actionRemainingMillis)
          )
          finalActionOutcome = TicketLatestTicketReselectRecoveryPolicy.actionOutcome(
            attempted = true,
            succeeded = actionSucceeded
          )
          val actionCompletedAtMillis = SystemClock.elapsedRealtime()
          val updatedGraceDeadlineMillis =
            TicketLatestTicketReselectRecoveryPolicy.ticketCardSelectionGraceDeadlineMillis(
              currentDeadlineMillis = ticketCardSelectionGraceDeadlineMillis,
              actionReason = action.reason,
              actionSucceeded = actionSucceeded,
              actionCompletedAtMillis = actionCompletedAtMillis,
              graceMillis = ticketCardSelectionGraceMillis
            )
          if (updatedGraceDeadlineMillis > ticketCardSelectionGraceDeadlineMillis) {
            ticketCardSelectionGraceDeadlineMillis = updatedGraceDeadlineMillis
            recordTicketEvent(
              "latest_ticket_reselect_ticket_card_action_grace_started",
              "duration_ms=$ticketCardSelectionGraceMillis"
            )
          }
          if (actionSucceeded || recoveryActionRepeatCooldownMillis > 0L) {
            wakeRecoveryActions += 1
            lastRecoveryActionKey = recoveryActionKey
            lastRecoveryActionAtMillis = actionCompletedAtMillis
          }
          if (actionSucceeded) {
            if (TicketLatestTicketReselectRecoveryPolicy.actionCategory(action.reason) == "ticket_card_selection") {
              newTicketRegistrationAttempted = true
            }
            if (TicketLatestTicketReselectRecoveryPolicy.isTicketDetailSelectionAction(action.reason)) {
              latestTicketSelectionAction = action.reason
            }
            if (
              parallelTicketDetailProofAfterTicketCardSelection &&
              state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD
            ) {
              return proveTicketDetailAfterFastCardSelection(reason)
            }
            lastStep = "wake_root_recovery_action_${state.name.lowercase()}"
            val recoveryBudgetMillis = maxOf(budgetMillis, TICKET_WAKE_RECOVERY_BUDGET_MILLIS)
            delay(minOf(TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS, remainingMillis(recoveryBudgetMillis)).coerceAtLeast(0L))
            continue
          }
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
        delay(minOf(TICKET_WAKE_RECOVERY_ACTION_SETTLE_MILLIS, remainingMillis(recoveryBudgetMillis)).coerceAtLeast(0L))
        continue
      }
      delay(minOf(TICKET_WAKE_FAST_POLL_MILLIS, remainingMillis(activeBudgetMillis)).coerceAtLeast(0L))
    }
    return result(false, lastState, lastStep)
  }

  private suspend fun proveTicketDetailAfterFastCardSelection(
    reason: String
  ): TicketAutopilotResult = coroutineScope {
    val hierarchyDeferred = async {
      observeRootViviStateForWake(
        reason = "fast_open_after_card:$reason",
        timeoutMillis = TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS
      )
    }
    val visualProofDeferred = async {
      verifyFreshTicketDetailVisualProof(
        reason = "fast_open_after_card:$reason",
        timeoutMillis = TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS
      )
    }
    val observation = hierarchyDeferred.await()
    val visualProofed = visualProofDeferred.await()
    val hierarchy = observation.hierarchy.orEmpty()
    if (
      observation.state == TicketViviRecoveryState.TICKET_DETAIL &&
      hierarchy.isNotBlank() &&
      TicketViviPageEnforcer.isTicketDetail(hierarchy) &&
      visualProofed
    ) {
      recordTicketWakePhase("ticket_ready", lastWakeStartedAtMillis)
      return@coroutineScope TicketAutopilotResult(
        success = true,
        state = TicketViviRecoveryState.TICKET_DETAIL,
        step = "fast_open_ticket_card_detail_parallel_proof"
      )
    }
    TicketAutopilotResult(
      success = false,
      state = observation.state,
      step = if (visualProofed) {
        "fast_open_ticket_card_detail_hierarchy_unproved"
      } else {
        "fast_open_ticket_card_detail_aztec_unproved"
      }
    )
  }

  private suspend fun attemptWakeRecoveryActionForRootWake(
    state: TicketViviRecoveryState,
    hierarchy: String,
    action: TicketViviPageAction,
    reason: String,
    timeoutMillis: Long,
    zeroTailPanelClamp: Boolean = false
  ): Boolean {
    if (state == TicketViviRecoveryState.TICKET_LIST_WITH_CARD || action.reason.contains("ticket_card")) {
      recordTicketEvent(
        "ticket_card_selection_decision",
        "${TicketViviPageEnforcer.ticketCardSelectionSummaryForHierarchy(hierarchy)} action=${action.reason}"
      )
    }
    val input = if (action.x >= 0 && action.y >= 0) {
      runFastRecoveryInput(
        "input tap ${action.x} ${action.y}",
        "wake_recovery_action:${action.reason}",
        timeout = minOf(timeoutMillis, TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS).milliseconds,
        zeroTailPanelClamp = zeroTailPanelClamp
      )
    } else {
      runFastRecoveryInput(
        "input keyevent KEYCODE_BACK",
        "wake_recovery_action:${action.reason}",
        timeout = minOf(timeoutMillis, TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS).milliseconds,
        zeroTailPanelClamp = zeroTailPanelClamp
      )
    }
    recordTicketEvent(
      "wake_recovery_action",
      "state=${state.name} action=${action.reason} ok=${input.ok} duration_ms=${input.durationMs} reason=$reason"
    )
    return input.ok
  }

  private fun recoveryActionCoolingDown(
    actionKey: String,
    lastActionKey: String,
    nowMillis: Long,
    lastActionAtMillis: Long,
    cooldownMillis: Long
  ): Boolean {
    return actionKey.isNotBlank() &&
      actionKey == lastActionKey &&
      cooldownMillis > 0L &&
      nowMillis - lastActionAtMillis in 0 until cooldownMillis
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

  private fun markWakeReadyIfNeeded(
    wakeStartedAtMillis: Long,
    result: TicketAutopilotResult,
    allowKnownViviState: Boolean = false
  ) {
    if (
      result.state != TicketViviRecoveryState.BLANK &&
      result.state != TicketViviRecoveryState.OUTSIDE_VIVI &&
      result.state != TicketViviRecoveryState.UNKNOWN_VIVI
    ) {
      recordTicketWakePhase("vivi_foreground", wakeStartedAtMillis)
    }
    if (
      result.success &&
      (
        result.state == TicketViviRecoveryState.TICKET_DETAIL ||
          (allowKnownViviState && TicketFastOpenVisualReadinessPolicy.isKnownRecoveryState(result.state))
        )
    ) {
      recordTicketWakePhase("ticket_ready", wakeStartedAtMillis)
    }
  }

  /**
   * Uses the same current-view visual classifier as Ticket action v3 as the complete public-open
   * state authority. Any two distinct fresh frames agreeing on a typed ViVi view are safe to
   * stream without requiring Back/control geometry, because viewing performs no phone mutation.
   * Unknown transition frames are ignored until the proof deadline and never authorize startup.
   */
  private suspend fun observeTicketDetailForFastPublicOpenCurrentVisualProof(
    reason: String,
    wakeStartedAtMillis: Long
  ): TicketAutopilotResult {
    if (!viviFocusedForFastPublicOpen("current_visual:$reason")) {
      return TicketAutopilotResult(
        false,
        TicketViviRecoveryState.OUTSIDE_VIVI,
        "fast_open_current_visual_outside_vivi"
      )
    }
    val visualProofBudgetMillis = remainingFastPublicOpenBudgetMillis(wakeStartedAtMillis)
    val observation = awaitStableTicketVisualActionObservation(
      reason = "fast_open_current_visual:$reason",
      timeoutMillis = visualProofBudgetMillis,
      allowUnknown = false,
      currentOnly = true
    ) ?: return TicketAutopilotResult(
      false,
      TicketViviRecoveryState.UNKNOWN_VIVI,
      "fast_open_current_visual_unproved"
    )
    val readiness = TicketFastOpenVisualReadinessPolicy.decide(observation.state)
    when (observation.state) {
      TicketVisualPhoneState.ACTIVATED_DETAIL,
      TicketVisualPhoneState.UNACTIVATED_DETAIL -> {
        viviStateMemory.record(
          state = TicketViviRecoveryState.TICKET_DETAIL,
          ticketId = null,
          source = "ticket_action_v3_current_visual",
          reason = "fast_public_open:$reason"
        )
      }
      TicketVisualPhoneState.TICKET_LIST -> {
        viviStateMemory.record(
          state = TicketViviRecoveryState.TICKET_LIST_WITH_CARD,
          ticketId = null,
          source = "ticket_action_v3_current_visual",
          reason = "fast_public_open:$reason"
        )
      }
      TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY,
      TicketVisualPhoneState.TICKETS_TIME_EMPTY,
      TicketVisualPhoneState.VIVI_HOME,
      TicketVisualPhoneState.VIVI_PROFILE,
      TicketVisualPhoneState.VIVI_OTHER_TAB,
      TicketVisualPhoneState.LOGIN_REQUIRED,
      TicketVisualPhoneState.BLOCKED,
      TicketVisualPhoneState.UNKNOWN -> Unit
    }
    recordTicketEvent(
      if (readiness.success) {
        "fast_public_open_current_visual_ready"
      } else {
        "fast_public_open_current_visual_failed"
      },
      "reason=$reason view=${observation.state.wireName}"
    )
    return TicketAutopilotResult(
      readiness.success,
      readiness.recoveryState,
      readiness.step
    )
  }

  /**
   * Hierarchy alone cannot prove that ViVi has finished replacing the list or
   * transition frame. Require fresh rooted visual samples containing the
   * Aztec/detail layout before reporting a normal ticket ready.
   */
  private suspend fun verifyFreshTicketDetailVisualProof(
    reason: String,
    timeoutMillis: Long = TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS,
    probeWaitMillis: Long = CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS
  ): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadlineMillis = startedAtMillis + timeoutMillis.coerceAtLeast(1L)
    val proof = TicketControlCodeCleanupVisualProof(TICKET_DETAIL_VISUAL_PROOF_SAMPLE_COUNT)
    var sampleIndex = 0
    var lastResult = TicketControlCodeVisualClassifier.UNKNOWN
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      if (!rootHardwareH264CaptureEngine.snapshot().active) {
        delay(TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS)
        continue
      }
      val probeStartedAtMillis = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestActivatedTicketVisualProbe(
        "${reason}_aztec_${sampleIndex + 1}"
      )
      if (probeId == null) {
        delay(TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS)
        continue
      }
      sampleIndex += 1
      val remainingMillis = (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val visualProbe = waitForFreshControlCodeVisualProbe(
        visualProbeStartedAtMillis = probeStartedAtMillis,
        expectedProbeId = probeId,
        timeoutMillis = minOf(probeWaitMillis.coerceAtLeast(1L), remainingMillis)
      )
      lastResult = visualProbe?.result ?: TicketControlCodeVisualClassifier.UNKNOWN
      if (proof.observe(lastResult)) {
        val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
        recordTicketEvent(
          "ticket_detail_aztec_visual_proofed",
          "reason=$reason samples=${proof.consecutiveRawTicketSamples} duration_ms=$durationMillis"
        )
        return true
      }
      if (lastResult == TicketControlCodeVisualClassifier.CONTROL_POPUP ||
        lastResult == TicketControlCodeVisualClassifier.GENERATED
      ) {
        recordTicketEvent(
          "ticket_detail_aztec_visual_proof_rejected",
          "reason=$reason state=$lastResult"
        )
        return false
      }
      delay(
        minOf(
          TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_SAMPLE_GAP_MILLIS,
          (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        )
      )
    }
    recordTicketEvent(
      "ticket_detail_aztec_visual_proof_inconclusive",
      "reason=$reason samples=$sampleIndex last=$lastResult duration_ms=${(SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)}"
    )
    return false
  }

  /**
   * Requires fresh rooted H.264 geometry to agree with the semantic registration anchors.
   * The visual probe confirms the live frame and full horizontal track, while the semantic
   * rectangle supplies the precise gesture row. No captured image leaves the phone.
   */
  private suspend fun detectFreshTicketRegistrationSliderBounds(
    hierarchyBounds: TicketViviGraphicBounds,
    reason: String,
    timeoutMillis: Long = TICKET_SLIDER_PROOF_TIMEOUT_MILLIS
  ): TicketViviGraphicBounds? {
    val displayWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val displayHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
    val deadlineMillis = SystemClock.elapsedRealtime() + timeoutMillis.coerceAtLeast(1L)
    var previous: TicketViviGraphicBounds? = null
    var stableSamples = 0
    var sampleCount = 0
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      if (!rootHardwareH264CaptureEngine.snapshot().active) {
        delay(TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS)
        continue
      }
      val probeStartedAtMillis = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestTicketDetailVisualProbe(
        "${reason}_slider_${sampleCount + 1}"
      )
      if (probeId == null) {
        delay(TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS)
        continue
      }
      sampleCount += 1
      val remainingMillis = (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
      val visualProbe = waitForFreshControlCodeVisualProbe(
        visualProbeStartedAtMillis = probeStartedAtMillis,
        expectedProbeId = probeId,
        timeoutMillis = minOf(CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS, remainingMillis)
      )
      val rawBounds = visualProbe?.sliderBounds
      val mapped = if (
        visualProbe?.result == TicketControlCodeVisualClassifier.RAW_TICKET && rawBounds != null
      ) {
        TicketCaptureGeometry.mapProbeBoundsToDevice(
          bounds = TicketVisualProbeBounds(
            rawBounds.left,
            rawBounds.top,
            rawBounds.right,
            rawBounds.bottom
          ),
          probeWidth = TicketControlCodeVisualClassifier.SAMPLE_WIDTH,
          probeHeight = TicketControlCodeVisualClassifier.SAMPLE_HEIGHT,
          sourceWidth = displayWidth,
          sourceHeight = displayHeight
        ).takeIf { it.width >= 220 && it.height >= 24 && it.width >= it.height * 3 }
      } else {
        null
      }
      val gestureBounds = mapped?.let {
        ticketSliderGestureBoundsAfterVisualProof(
          hierarchyBounds = hierarchyBounds,
          visualBounds = it,
          displayWidth = displayWidth,
          displayHeight = displayHeight
        )
      }
      if (mapped != null && gestureBounds != null) {
        stableSamples = if (previous != null && sliderGeometryClose(previous, mapped)) {
          stableSamples + 1
        } else {
          1
        }
        previous = mapped
        if (stableSamples >= TICKET_DETAIL_VISUAL_PROOF_SAMPLE_COUNT) {
          recordTicketEvent(
            "ticket_registration_slider_visual_proofed",
            "reason=$reason samples=$sampleCount visual_bounds=${mapped.left},${mapped.top},${mapped.right},${mapped.bottom} gesture_bounds=${gestureBounds.left},${gestureBounds.top},${gestureBounds.right},${gestureBounds.bottom}"
          )
          return gestureBounds
        }
      } else {
        stableSamples = 0
        previous = null
      }
      delay(TICKET_SLIDER_PROOF_POLL_MILLIS)
    }
    recordTicketEvent(
      "ticket_registration_slider_visual_proof_failed",
      "reason=$reason samples=$sampleCount"
    )
    return null
  }

  private fun sliderGeometryClose(
    previous: TicketViviGraphicBounds?,
    current: TicketViviGraphicBounds
  ): Boolean {
    if (previous == null) return false
    return kotlin.math.abs(previous.left - current.left) <= 48 &&
      kotlin.math.abs(previous.top - current.top) <= 48 &&
      kotlin.math.abs(previous.right - current.right) <= 48 &&
      kotlin.math.abs(previous.bottom - current.bottom) <= 48
  }

  private suspend fun prepareViviForRootHardwareH264FastOpen(
    reason: String,
    wakeStartedAtMillis: Long
  ): TicketAutopilotResult {
    val launchedViviForWake = !viviFocusedForFastPublicOpen(reason)
    if (!launchedViviForWake) {
      allowProvisionalHardwareH264FramesForFocusedVivi(
        reason = "preflight_focused:$reason",
        focusedVivi = true
      )
    }
    if (launchedViviForWake) {
      val launchBudgetMillis = remainingFastPublicOpenBudgetMillis(wakeStartedAtMillis)
      if (launchBudgetMillis > 0L) {
        val launchTimeoutMillis = minOf(TICKET_WAKE_LAUNCH_TIMEOUT_MILLIS, launchBudgetMillis)
        recordTicketEvent("fast_public_open_launch_once", "reason=$reason timeout_ms=$launchTimeoutMillis")
        launchViviForWake(reason, timeoutMillis = launchTimeoutMillis)
        if (viviFocusedForFastPublicOpen("post_launch_stream:$reason")) {
          allowProvisionalHardwareH264FramesForFocusedVivi(
            reason = "post_launch_focused:$reason",
            focusedVivi = true
          )
        }
        // A cold launch can restore ViVi to a different route, including the ticket list. The
        // fresh V3 current-view frames below are the authority; explicit V3 actions own any
        // later navigation.
        recordTicketEvent("fast_public_open_post_launch_memory_skipped", "reason=$reason cold_launch=true")
      } else {
        recordTicketEvent("fast_public_open_launch_skipped", "reason=$reason remaining_ms=$launchBudgetMillis")
      }
    }
    // The same two-frame current-view classifier as Ticket action v3 is the complete startup
    // authority. Details and lists remain viewable without hierarchy, legacy Aztec proof, Back
    // geometry, or startup navigation; explicit V3 actions own any later phone mutation.
    val prepareResult = observeTicketDetailForFastPublicOpenCurrentVisualProof(
      reason = reason,
      wakeStartedAtMillis = wakeStartedAtMillis
    )
    markWakeReadyIfNeeded(wakeStartedAtMillis, prepareResult, allowKnownViviState = true)
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

  /**
   * Forward only fresh root H.264 frames while the current ViVi foreground is
   * still being verified. This never marks the ticket ready and never unlocks
   * control-code input; the two-frame V3 current-view proof remains the authority.
   */
  private fun allowProvisionalHardwareH264FramesForFocusedVivi(
    reason: String,
    focusedVivi: Boolean
  ) {
    if (
      !focusedVivi ||
      !streamActive ||
      activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
      hardwareCaptureVerified ||
      hardwareFrameBroadcastAllowed
    ) {
      return
    }
    hardwareFrameBroadcastAllowed = true
    recordTicketEvent("hardware_h264_provisional_frames_allowed", reason)
    broadcastStatus()
  }

  private fun scheduleRootHardwareH264CaptureStart(reason: String, suppressBlackout: Boolean) {
    // Starting the root encoder does not mutate ViVi. Begin it before the
    // serialized phone-mutation lane starts its potentially slow hierarchy
    // proof so fresh frames can be ready as soon as foreground is confirmed.
    val job = synchronized(rootHardwareH264CapturePreparationLock) {
      val existing = rootHardwareH264CapturePreparationJob
      if (existing != null && !existing.isCompleted) {
        recordTicketEvent("root_hardware_h264_prepare_coalesced", "reason=$reason")
        return@synchronized null
      }
      prewarmRootHardwareH264CaptureIfPossible("scheduled:$reason")
      serviceScope.launch(start = CoroutineStart.LAZY) {
        try {
          controlCodePhoneMutationLane.withOwnership {
            prepareRootHardwareH264CaptureWithPhoneMutationOwnership(reason, suppressBlackout)
          }
        } finally {
          val completingJob = coroutineContext[Job]
          synchronized(rootHardwareH264CapturePreparationLock) {
            if (rootHardwareH264CapturePreparationJob === completingJob) {
              rootHardwareH264CapturePreparationJob = null
            }
          }
        }
      }.also { rootHardwareH264CapturePreparationJob = it }
    }
    job?.start()
  }

  private fun cancelRootHardwareH264CapturePreparation(reason: String) {
    val job = synchronized(rootHardwareH264CapturePreparationLock) {
      rootHardwareH264CapturePreparationJob.also { rootHardwareH264CapturePreparationJob = null }
    }
    if (job != null && !job.isCompleted) {
      job.cancel()
      recordTicketEvent("root_hardware_h264_prepare_cancelled", reason)
    }
  }

  private suspend fun cancelAndJoinRootHardwareH264CapturePreparation(reason: String) {
    val job = synchronized(rootHardwareH264CapturePreparationLock) {
      rootHardwareH264CapturePreparationJob.also { rootHardwareH264CapturePreparationJob = null }
    }
    if (job != null && !job.isCompleted) {
      job.cancel()
      recordTicketEvent("root_hardware_h264_prepare_cancelled", reason)
      job.join()
      recordTicketEvent("root_hardware_h264_prepare_cancelled_joined", reason)
    }
  }

  private suspend fun prepareRootHardwareH264CaptureWithPhoneMutationOwnership(
    reason: String,
    suppressBlackout: Boolean
  ): Boolean {
    val captureLease = secureWindowCaptureBypassOwner.currentLease() ?: run {
      recordTicketEvent("root_hardware_h264_prepare_ignored", "secure_capture_ownership_missing:$reason")
      return false
    }
    return secureWindowCaptureBypassOwner.runRetainingOnSuccess(
      lease = captureLease,
      reason = "root_hardware_h264_prepare:$reason",
      shouldRetain = {
        it && streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264
      },
      retainIfCurrent = {
        streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264
      }
    ) prepare@{
    val modeName = "root_hardware_h264"
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      recordTicketEvent("${modeName}_prepare_ignored", "session_inactive_before_prepare:$reason")
      return@prepare false
    }
    if (hardwareCaptureVerified && hardwareFrameBroadcastAllowed) {
      recordTicketEvent("${modeName}_prepare_reused", reason)
      return@prepare true
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
      return@prepare false
    }
    if (!prepareResult.success) {
      rootHardwareH264CaptureEngine.stopAndJoin("phone_not_ready:$reason")
      streamActive = false
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      resetFrameEpoch("phone_not_ready:$reason", active = false)
      lastMessage = "Phone not ready: root could not confirm the ViVi ticket screen"
      recordTicketEvent("phone_not_ready", prepareResult.step)
      recordStartupTracePhase("phone_not_ready", prepareResult.step, once = true, complete = true)
      broadcastStatus()
      return@prepare false
    }
    hardwareCaptureVerified = true
    hardwareFrameBroadcastAllowed = true
    recordTicketEvent("hardware_h264_wake_frames_allowed", reason)
    ensureEncoderIfPossible()
    requestKeyFrame("vivi_ready_encoder_start:$reason")
    val watermark = requestFreshTicketStateFrameWatermark("vivi_ready:$reason")
    val ticketPixelState = when (prepareResult.state) {
      TicketViviRecoveryState.TICKET_DETAIL -> TICKET_PIXEL_STATE_RAW_TICKET
      TicketViviRecoveryState.TICKET_LIST_WITH_CARD -> TICKET_PIXEL_STATE_TICKET_LIST
      else -> null
    }
    if (ticketPixelState != null) {
      sendTicketStateEvent(
        ticketState = ticketPixelState,
        reason = if (ticketPixelState == TICKET_PIXEL_STATE_RAW_TICKET) {
          "session_start_raw_ticket_ready"
        } else {
          "session_start_ticket_list_ready"
        },
        eventStreamEpoch = watermark.first,
        eventFrameSequence = watermark.second,
        minFrameSequence = watermark.second
      )
    } else {
      recordTicketEvent(
        "session_start_known_non_ticket_view_ready",
        "state=${prepareResult.state.name.lowercase()}"
      )
    }
    updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_waiting_first_visible_frame")
    lastMessage = "Waiting for the first visible hardware H.264 frame"
    broadcastStatus()
    true
    }
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
      ticketActionV3Job?.isActive == true ||
      viviReauthSnapshot.status == "running" ||
      now < controlCodeTransitionGraceUntilMillis ||
      ticketSessionState == TICKET_SESSION_CONTROL_EXIT
  }

  private fun controlCodeRequestActive(): Boolean {
    val status = lastControlCodeRequestStatus
    return status == "running" || status == "queued" || streamStartAdmission.claimCount() > 0L
  }

  internal fun ticketSpacetimeControlCodeRequestActive(): Boolean {
    return streamStartAdmission.claimCount() > 0L || ticketControlOwnershipActive(
      requestStatus = lastControlCodeRequestStatus,
      pendingBrowserCapture = pendingControlCodeBrowserCaptureRequestId != null,
      controlModeActive = controlCodeModeActive,
      sessionState = ticketSessionState
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
      return
    }
    controlCodeModeActive = false
    controlCodeModeEnteredAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    if (
      streamActive &&
      ticketSessionState != TICKET_SESSION_LIVE &&
      !reason.startsWith("session_stop_") &&
      reason != "foreground_guard_cancelled"
    ) {
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
    if (
      streamActive &&
      ticketSessionState == TICKET_SESSION_LIVE &&
      !controlCodeModeActive &&
      controlCodeModeEnteredAtMillis == 0L &&
      lastControlCodeSurfaceState == null &&
      lastControlExitDirtySurfaceState == null
    ) {
      // The phone may already have completed its automatic generated-result
      // cleanup before the browser's later close acknowledgement arrives.
      // Treat that acknowledgement as idempotent instead of re-entering the
      // control-exit state after the raw ticket is already restored.
      recordTicketEvent("control_exit_already_clean", reason)
      broadcastStatus()
      return
    }
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
        runScheduledControlExitCleanupWithPanelDarkLease(reason)
      }
    }
  }

  /**
   * A browser-close cleanup is still a phone action. It therefore owns the same panel-dark lease
   * and phone lane as an admitted request, and it does not clear the durable cleanup fence until
   * the cleaned surface, convergence tail, fresh zero, and exact helper shutdown are all proven.
   */
  private suspend fun runScheduledControlExitCleanupWithPanelDarkLease(reason: String): Boolean {
    val ownerSuffix = SystemClock.elapsedRealtime().toString()
    val panelLease = TicketActionPanelDarkLease(
      actionId = "control_code:cleanup:$ownerSuffix",
      scope = serviceScope,
      clampRootExecutor = ticketActionPanelDarkRootExecutor,
      verifyRootExecutor = ticketActionPanelDarkVerifyRootExecutor,
      physicalTouchState = PhoneAutomationServiceBridge::currentRootPhysicalTouchState,
      ownerProcessId = android.os.Process.myPid(),
      onSnapshotChanged = { snapshot ->
        val prior = ticketActionPanelDarkLeaseSnapshot
        if (snapshot.failure.isNotBlank() &&
          (prior.ownerActionId != snapshot.ownerActionId || prior.failure.isBlank())
        ) {
          ticketActionPanelDarkLeaseFailures.incrementAndGet()
        }
        ticketActionPanelDarkLeaseSnapshot = snapshot
        broadcastStatus()
      }
    )
    activeTicketActionPanelDarkLease = panelLease
    PhoneAutomationServiceBridge.markNonTouchInput(
      reason = "ticket:control_code_cleanup_panel_dark:$ownerSuffix",
      durationMillis = TICKET_ACTION_V3_PANEL_DARK_LEASE_MILLIS
    )
    var leaseAcquired = false
    var phoneSurfaceCleaned = false
    var cleanupFailure: Throwable? = null
    var finalization: TicketActionPanelDarkLeaseFinalization? = null
    try {
      leaseAcquired = panelLease.acquire()
      if (leaseAcquired) {
        phoneSurfaceCleaned = runControlExitCleanup(reason)
      } else {
        recordTicketEvent(
          "control_exit_cleanup_panel_dark_acquire_failed",
          "reason=$reason lease=${panelLease.snapshot().failure}"
        )
      }
    } catch (error: Throwable) {
      cleanupFailure = error
      recordTicketEvent(
        "control_exit_cleanup_panel_exception",
        "reason=$reason error=${safeErrorDetail(error)}"
      )
    } finally {
      withContext(NonCancellable) {
        finalization = if (leaseAcquired) {
          panelLease.releaseAfterFinalConvergence("control_exit_cleanup_terminal")
        } else {
          panelLease.release("control_exit_cleanup_acquire_failed")
        }
        if (activeTicketActionPanelDarkLease === panelLease) {
          activeTicketActionPanelDarkLease = null
        }
        PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(
          "ticket:control_code_cleanup_panel_dark:$ownerSuffix:released"
        )
      }
    }

    val cleanupMayCommit = ticketControlCodeCleanupMayCommitAfterPanelFinalization(
      phoneSurfaceCleaned,
      finalization
    )
    if (cleanupMayCommit &&
      commitControlCodeCleanStateAfterPanelFinalization("scheduled:$reason")
    ) {
      publishControlCodeReadyAfterPanelFinalization(lastControlCodeRequestId.orEmpty())
      recordTicketEvent("control_exit_cleanup_panel_finalized", "reason=$reason")
      broadcastStatus()
      return true
    }

    preserveControlCodeCleanupCheckpoint("scheduled:$reason")
    val leaseState = finalization?.snapshot ?: panelLease.snapshot()
    val failureReason = ticketScheduledControlCleanupFailureReason(
      leaseAcquired = leaseAcquired,
      finalizationSafe = finalization?.safe,
      mutationMayHaveDispatched = leaseState.mutationMayHaveDispatched
    )
    updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, failureReason)
    recordInputGateDecision(false, failureReason)
    markControlCodeFastNotReady("blocked", failureReason)
    if (leaseState.mutationMayHaveDispatched) {
      lastControlCodeRequestStatus = "failed"
      lastControlCodeRequestReason = "control_code_cleanup_attention_needed"
      lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
      lastControlCodeRequestId.orEmpty().takeIf(String::isNotBlank)?.let { requestId ->
        sendControlCodeCleanup(
          requestId,
          false,
          "control_code_cleanup_attention_needed",
          startedAtMillis = 0L
        )
      }
    }
    recordTicketEvent(
      "control_exit_cleanup_panel_finalization_failed",
      "reason=$reason failure=$failureReason cleaned=$phoneSurfaceCleaned exception=${cleanupFailure != null}"
    )
    broadcastStatus()
    cleanupFailure?.let { error ->
      if (error is CancellationException) throw error
    }
    return false
  }

  private suspend fun runControlExitCleanup(reason: String): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val phases = linkedMapOf<String, Long>()
    if (ticketControlCodeCleanupRequiresVisualReopen(
        controlCodeSignatureCleanupRequired,
        activeControlCodeVisualResultMode,
        activeControlCodeRawDetailAnchor
      )) {
      recordInputGateDecision(false, "control_exit_cleanup_requires_visual_reopen")
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_exit_cleanup_requires_visual_reopen")
      recordControlExitCleanup(
        reason, controlCodeSurfaceMemoryState() ?: "UNKNOWN", "none",
        startedAtMillis, "volatile_visual_identity_unavailable", false, false
      )
      broadcastStatus()
      return false
    }
    if (withTimeoutOrNull(CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS) {
        returnControlCodeSurfaceToRawTicket("", reason, phases, startedAtMillis)
      } == true
    ) return true
    if (controlCodeSignatureCleanupRequired) {
      recordInputGateDecision(false, "control_exit_strict_visual_cleanup_unproved")
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_exit_strict_visual_cleanup_unproved")
      recordControlExitCleanup(
        reason, controlCodeSurfaceMemoryState() ?: "UNKNOWN", "none",
        startedAtMillis, "strict_visual_identity_unproved", false, false
      )
      broadcastStatus()
      return false
    }
    val preparedHierarchy = withTimeoutOrNull(CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS) {
      prepareTicketDetailForControlCodeRequest(phases, startedAtMillis)
    }
    if (!preparedHierarchy.isNullOrBlank()) {
      return completeControlExitCleanup(
        reason, TicketViviRecoveryState.TICKET_DETAIL.name, "detected_detail_preparation",
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
    // Every production cleanup now owns an action panel-dark lease (the admitted request, the V3
    // visual-reopen action, or the scheduled cleanup wrapper). Keep the private identity/checkpoint
    // intact until that lease proves its final tail and exact shutdown.
    val deferReadyPublication = activeTicketActionPanelDarkLease != null
    val finalDetectedState = if (detectedState == "UNKNOWN" || detectedState == "CONTROL_SURFACE_LIKELY") {
      controlCodeSurfaceMemoryState() ?: lastControlExitDirtySurfaceState ?: detectedState
    } else {
      detectedState
    }
    val finalCloseAction = closeAction
    recordTicketEvent("control_code_soft_check_ok", verificationResult.lowercase())
    val cleanupStartedAtMillis = startedAtMillis
    val freshFrameVerified = if (freshFrameRequested) {
      waitForFreshStreamFrameAfterCleanup(reason, cleanupStartedAtMillis)
    } else {
      true
    }
    if (!deferReadyPublication) {
      // A legacy/background observer may discover that the phone already looks clean, but it has
      // no action-scoped final panel proof. Keep the durable fence closed and require the admitted
      // visual-reopen path instead of clearing private identity on that weaker observation.
      preserveControlCodeCleanupCheckpoint("cleanup_without_panel_finalization:$reason")
      updateTicketSessionState(
        TICKET_SESSION_NEEDS_ATTENTION,
        "control_code_cleanup_panel_finalization_required"
      )
      recordControlExitCleanup(
        reason,
        finalDetectedState,
        finalCloseAction,
        startedAtMillis,
        "${verificationResult}_panel_finalization_missing",
        false,
        freshFrameRequested,
        freshFrameVerified
      )
      broadcastStatus()
      return false
    }
    if (!freshFrameVerified) {
      recordTicketEvent("post_cleanup_stream_stale", reason)
      restartActiveStreamEngine("post_cleanup_stale_$reason")
    } else {
      recordTicketEvent(
        "control_code_ready_publication_deferred",
        "reason=$reason waiting_for_panel_dark_finalization"
      )
    }
    recordControlExitCleanup(
      reason,
      finalDetectedState,
      finalCloseAction,
      startedAtMillis,
      if (deferReadyPublication) "${verificationResult}_panel_finalization_pending" else verificationResult,
      freshFrameVerified && !deferReadyPublication,
      freshFrameRequested,
      freshFrameVerified
    )
    broadcastStatus()
    return freshFrameVerified
  }

  private fun commitControlCodeCleanStateAfterPanelFinalization(
    reason: String,
    finalViviState: TicketViviRecoveryState = TicketViviRecoveryState.TICKET_DETAIL
  ): Boolean {
    // Prove the durable fence clear before discarding the private in-memory identity. If this write
    // fails, the old pending checkpoint and signatures still fence the next control request.
    if (!persistControlCodeSignatureCleanupRequired(false)) {
      recordTicketEvent(
        "control_code_cleanup_checkpoint_clear_unproved",
        "reason=$reason"
      )
      return false
    }
    controlCodeModeActive = false
    controlCodeModeEnteredAtMillis = 0L
    controlCodePopupReadyUntilMillis = 0L
    lastControlCodeSurfaceState = null
    lastControlCodeSurfaceSeenAtMillis = 0L
    lastControlExitDirtySurfaceState = null
    activeControlCodeGeneratedVisualSignature = ""
    activeControlCodeVisualSignatureEpoch = ""
    activeControlCodeRawDetailAnchor = ""
    activeControlCodeRawDetailState = TicketVisualPhoneState.UNKNOWN
    activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
    activeControlCodeVisualSignatureExpiresAtMillis = 0L
    viviStateMemory.record(
      state = finalViviState,
      ticketId = null,
      source = "root",
      reason = "control_exit_cleanup:$reason"
    )
    recordInputGateDecision(allowed = true, reason = "control_exit_popup_closed")
    recordTicketEvent("control_exit_popup_closed", reason)
    return true
  }

  private fun publishControlCodeReadyAfterPanelFinalization(requestId: String) {
    if (streamActive) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "control_exit_popup_closed")
      startForegroundGuard()
    }
    sendTicketStateEvent(
      ticketState = TICKET_PIXEL_STATE_RAW_TICKET,
      reason = "return_to_raw_complete",
      requestId = requestId,
      eventStreamEpoch = streamEpoch,
      eventFrameSequence = frameSequence,
      minFrameSequence = frameSequence
    )
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
    sessionMutex.withLock {
      streamStartAdmission.claim()
      // Admission and disconnect cleanup share the session lock. A cleanup that
      // already won the lock finishes first; otherwise this claim prevents a
      // stop from committing while the request begins to reuse the stream.
      clientDisconnectStopJob?.cancel()
      clientDisconnectStopJob = null
    }
    // A request is the sole phone-control authority while it is admitted. Stop
    // obsolete foreground work before the request enters the serialized
    // mutation lane. A background root-capture preparation may be inside the
    // lane's long list-recovery proof; cancel and join it so the request does
    // not wait behind work that is no longer relevant to the user action.
    cancelForegroundGuard()
    cancelTicketRecovery("control_code_request_admitted")
    controlExitCleanupJob?.cancel()
    controlExitCleanupJob = null
    cancelAndJoinRootHardwareH264CapturePreparation("control_code_request_admitted")
    recordTicketEvent(
      "control_code_request_phone_lane_claimed",
      "background_phone_work_cancelled=true rooted_capture_preparation=cancelled_and_joined"
    )
  }

  private suspend fun releaseControlCodeAutomationForRequest() {
    withContext(NonCancellable) {
      sessionMutex.withLock {
        val remainingClaims = streamStartAdmission.release()
        if (remainingClaims != 0L) return@withLock
        if (
          shouldScheduleDisconnectAfterFinalControlClaim(
            remainingClaims = remainingClaims,
            clientCount = totalClientCount(),
            sessionOpen = ticketSessionOpen()
          )
        ) {
          markViewerInput("control_automation_released")
          scheduleClientDisconnectGraceLocked()
          recordTicketEvent("client_disconnect_cleanup_rescheduled", "reason=control_automation_released")
        }
      }
    }
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

  private fun recentPreparedControlCodeTicketDetailHierarchy(
    nowMillis: Long = SystemClock.elapsedRealtime()
  ): String? {
    if (!controlCodeFastVisualMarkerFresh(nowMillis)) {
      return null
    }
    val hierarchy = viviStateMemory.recentTicketDetailHierarchyWithin(
      CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
    )?.hierarchy ?: return null
    return hierarchy.takeIf {
      TicketViviPageEnforcer.classifyForRecovery(it) == TicketViviRecoveryState.TICKET_DETAIL &&
      TicketViviPageEnforcer.isTicketDetail(it)
    }
  }

  /**
   * Reuses the same recent rooted ticket-detail proof when the browser revision
   * expires during a cold session handoff. The proof still requires a live
   * rooted H.264 stream, a fresh raw-ticket event, and the retained rooted
   * detail hierarchy; it only avoids dumping that hierarchy a second time.
   */
  private fun recentRootedTicketDetailProofForControlCode(
    nowMillis: Long = SystemClock.elapsedRealtime()
  ): String? {
    val rawTicketProof = recentLiveRawTicketProofForControlCode(
      nowMillis,
      CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
    ) ?: return null
    if (rawTicketProof.state != TicketViviRecoveryState.TICKET_DETAIL) {
      return null
    }
    val hierarchy = viviStateMemory.recentTicketDetailHierarchyWithin(
      CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS
    )?.hierarchy ?: return null
    return hierarchy.takeIf {
      TicketViviPageEnforcer.classifyForRecovery(it) == TicketViviRecoveryState.TICKET_DETAIL &&
        TicketViviPageEnforcer.isTicketDetail(it)
    }
  }

  private fun publishControlCodeFastReadyAfterSessionProof() {
    if (
      streamEpoch <= 0L ||
      lastControlCodeFastReadyStreamEpoch == streamEpoch ||
      lastPixelTicketState != TICKET_PIXEL_STATE_RAW_TICKET
    ) {
      return
    }
    val nowMillis = SystemClock.elapsedRealtime()
    if (recentPreparedControlCodeTicketDetailHierarchy(nowMillis) == null) {
      return
    }
    lastControlCodeFastReadyStreamEpoch = streamEpoch
    markControlCodeFastReady("session_start_ticket_detail")
  }

  private fun markControlCodeFastReady(reason: String) {
    val cleanReason = reason.trim().ifBlank { "ticket_detail" }
    val watermark = requestFreshTicketStateFrameWatermark("control_code_fast_ready:$cleanReason")
    val nowMillis = SystemClock.elapsedRealtime()
    val revision = "phone:$nowMillis:${watermark.first}:${watermark.second}"
    lastControlCodeFastReadyRevision = revision
    lastControlCodeFastReadyStreamEpoch = watermark.first
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
    // The generated result asks for one independent frame. Normal 1 FPS capture resumes
    // one second later; repeated requests inside that period are coalesced.
    controlCodeResultEncoderRefreshActive = true
    val refreshRequested = rootHardwareH264CaptureEngine.requestImmediateRefresh(
      "control_code_browser_capture_wait"
    )
    synchronized(controlCodeBrowserCaptureLock) {
      pendingControlCodeBrowserCaptureRequestId = requestId.takeIf { it.isNotBlank() }
      pendingControlCodeBrowserCaptureAck = null
    }
    lastControlCodeBrowserCaptureReason = "waiting"
    lastControlCodeBrowserCaptureCompletedAtMillis = 0L
    recordTicketEvent(
      "control_code_browser_capture_wait_started",
      "request=$requestId refresh_requested=$refreshRequested"
    )
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
    try {
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
    } finally {
      controlCodeResultEncoderRefreshActive = false
    }
  }

  private suspend fun ensureTicketSessionForControlCodeRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): String? {
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT) {
      recordTicketEvent("control_code_request_control_exit_cleanup", ticketSessionState)
      markControlCodeRequestPhase(phases, "request_control_exit_cleanup_started", requestStartedAtMillis)
      val cleaned = runControlExitCleanup("control_code_request_preflight_control_exit")
      markControlCodeRequestPhase(phases, "request_control_exit_cleanup_finished", requestStartedAtMillis)
      if (!cleaned || ticketSessionState == TICKET_SESSION_CONTROL_EXIT || ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION) {
        val reason = "control_code_request_control_exit_unavailable"
        recordInputGateDecision(allowed = false, reason = reason)
        recordTicketEvent(reason, "cleaned=$cleaned state=$ticketSessionState")
        return null
      }
    }

    recentPreparedControlCodeTicketDetailHierarchy()?.let { hierarchy ->
      markControlCodeRequestPhase(phases, "request_ticket_detail_ready", requestStartedAtMillis)
      recordTicketEvent(
        "control_code_request_ticket_detail_reused",
        "source=root_h264_fast_state_proof epoch=$streamEpoch sequence=$frameSequence"
      )
      return hierarchy
    }

    recentRootedTicketDetailProofForControlCode()?.let { hierarchy ->
      markControlCodeRequestPhase(phases, "request_ticket_detail_ready", requestStartedAtMillis)
      recordTicketEvent(
        "control_code_request_ticket_detail_reused",
        "source=root_h264_recent_session_proof fast_revision_missed=true epoch=$streamEpoch sequence=$frameSequence"
      )
      return hierarchy
    }

    if (!ticketSessionOpen() || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      // A control-code request owns the cold-start preparation.  Do not queue
      // a background start and then make the request wait for an unrelated
      // job: the same serialized lane must finish root H.264 preparation
      // before the first ViVi mutation.
      val response = startTicketSession(
        prepareCaptureWithCurrentPhoneMutationOwnership = true,
        timeoutMillis = CONTROL_CODE_INLINE_PREPARATION_TIMEOUT_MILLIS
      )
      markControlCodeRequestPhase(phases, "request_session_started", requestStartedAtMillis)
      if (!response.ok || !ticketSessionOpen()) {
        val reason = "control_code_request_session_unavailable:${response.state}"
        recordInputGateDecision(allowed = false, reason = reason)
        recordTicketEvent("control_code_request_session_unavailable", "state=${response.state} message=${response.message.take(80)}")
        return null
      }
      recordTicketEvent("control_code_request_session_ready", response.state)
      recentPreparedControlCodeTicketDetailHierarchy()?.let { hierarchy ->
        markControlCodeRequestPhase(phases, "request_ticket_detail_ready", requestStartedAtMillis)
        recordTicketEvent(
          "control_code_request_ticket_detail_reused",
          "source=session_start_proof epoch=$streamEpoch sequence=$frameSequence"
        )
        return hierarchy
      }
    } else if (rootCaptureNeedsOwnedPreparation()) {
      // The stream/session coordinator may already have established the root
      // H.264 mode while its verification is still in progress. Do not enter
      // the session mutex again here: that mutex also protects disconnect
      // bookkeeping and can be held by a stale-client cleanup. The admitted
      // request already owns the phone mutation lane, so it completes the one
      // preparation operation directly on this path.
      recordTicketEvent(
        "control_code_request_capture_preparation_owned",
        "epoch=$streamEpoch state=$ticketSessionState"
      )
      val prepared = prepareRootHardwareH264CaptureWithPhoneMutationOwnership(
        reason = "control_code_request_existing_stream",
        suppressBlackout = false
      )
      markControlCodeRequestPhase(phases, "request_capture_prepared", requestStartedAtMillis)
      if (!prepared || !streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
        val reason = "control_code_request_capture_unavailable"
        recordInputGateDecision(allowed = false, reason = reason)
        recordTicketEvent("control_code_request_capture_unavailable", "prepared=$prepared state=$ticketSessionState")
        return null
      }
      recentPreparedControlCodeTicketDetailHierarchy()?.let { hierarchy ->
        markControlCodeRequestPhase(phases, "request_ticket_detail_ready", requestStartedAtMillis)
        recordTicketEvent(
          "control_code_request_ticket_detail_reused",
          "source=root_capture_preparation_proof epoch=$streamEpoch sequence=$frameSequence"
        )
        return hierarchy
      }
    }

    return prepareTicketDetailForControlCodeRequest(phases, requestStartedAtMillis)
  }

  /** Visual-only preparation for the Ticket control-code path. */
  private suspend fun prepareTicketDetailForControlCodeRequest(
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): String? {
    loadTicketVisualSwitchAnchorsIfNeeded()
    val deadlineMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_INLINE_PREPARATION_TIMEOUT_MILLIS
    var viviLaunchAttempted = false
    var staleSurfaceActionAttempted = false
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      val observation = awaitStableTicketVisualActionObservation(
        "control_code_inline_preparation",
        minOf(TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS,
          (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
      )
      when (observation?.state) {
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        TicketVisualPhoneState.UNACTIVATED_DETAIL -> {
          updateTicketSessionState(TICKET_SESSION_LIVE, "control_code_inline_ticket_detail_ready")
          cacheForegroundViolation(null)
          markControlCodeRequestPhase(phases, "request_ticket_detail_ready", requestStartedAtMillis)
          return "visual_ticket_detail"
        }
        TicketVisualPhoneState.UNKNOWN -> {
          if (viviLaunchAttempted) {
            recordTicketEvent("control_code_inline_preparation_failed", "reason=visual_state_unknown")
            return null
          }
          viviLaunchAttempted = true
          launchViviForWake("control_code_inline_preparation_launch")
          markControlCodeRequestPhase(phases, "request_vivi_launch", requestStartedAtMillis)
        }
        TicketVisualPhoneState.VIVI_HOME -> {
          // A control-code close can return ViVi to its route root. That is not ticket-detail
          // cleanup success and this request must not invent a second recovery mutation. A later
          // V3 action can use its separately journaled, visually proved Tickets-tab transition.
          recordTicketEvent(
            "control_code_inline_preparation_failed",
            "reason=vivi_home_requires_ticket_action"
          )
          return null
        }
        TicketVisualPhoneState.TICKET_LIST,
        TicketVisualPhoneState.BLOCKED -> {
          if (staleSurfaceActionAttempted) {
            recordTicketEvent("control_code_inline_preparation_failed", "reason=stale_visual_surface_persisted")
            return null
          }
          val bounds = if (observation.state == TicketVisualPhoneState.TICKET_LIST) {
            observation.activatedCardForRecentDetail(ticketVisualSwitchAnchors)
              ?.activatedDetailBounds
          } else {
            observation.backBounds
          } ?: run {
            recordTicketEvent("control_code_inline_preparation_failed", "reason=missing_visual_action")
            return null
          }
          staleSurfaceActionAttempted = true
          if (!tapTicketVisualProbeBounds(bounds, "control_code_inline_preparation_visual_action")) {
            recordTicketEvent("control_code_inline_preparation_failed", "reason=visual_action_failed")
            return null
          }
          markControlCodeRequestPhase(phases, "request_preparation_action_sent", requestStartedAtMillis)
        }
        else -> {
          recordTicketEvent("control_code_inline_preparation_failed", "reason=unrecognized_visual_state")
          return null
        }
      }
      delay(minOf(CONTROL_CODE_FAST_POLL_MILLIS, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
    }
    recordTicketEvent("control_code_inline_preparation_failed", "reason=deadline")
    return null
  }

  private fun rootCaptureNeedsOwnedPreparation(): Boolean {
    return streamActive &&
      activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
      (!hardwareCaptureVerified || !hardwareFrameBroadcastAllowed)
  }

  private suspend fun handleGenerateControlCode(
    replyClient: TicketWebSocket?,
    requestId: String,
    digits: String,
    owner: String,
    app: String,
    flow: String,
    queueHint: RigasSatiksmeQueueHint,
    fastRevision: String = "",
    serverSentAt: String = ""
  ) {
    val cleanRequestId = requestId.trim()
    val cleanDigits = digits.trim()
    val requestedOwner = owner.trim()
    val requestedApp = app.trim()
    val requestedFlow = flow.trim()
    val cleanFastRevision = fastRevision.trim()
    val databaseSubmissionEpochMillis = parseControlCodeServerSentAtMillis(serverSentAt)
    val databaseToPhoneMillis = databaseSubmissionEpochMillis?.let { submittedAtMillis ->
      (System.currentTimeMillis() - submittedAtMillis).coerceAtLeast(0L)
    }
    recordControlCodeCommandEnvelope(requestedOwner, requestedApp, requestedFlow)
    recordTicketEvent(
      "control_code_request_received",
      "request=$cleanRequestId owner=$requestedOwner app=$requestedApp flow=$requestedFlow digit_count=${cleanDigits.length}"
    )
    databaseToPhoneMillis?.let { durationMillis ->
      recordTicketEvent("control_code_database_to_phone", "duration_ms=$durationMillis")
    }
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
    if (sendCachedControlCodeResult(cleanRequestId) ||
      controlCodeRequestDuplicateActiveOrCompleted(cleanRequestId)
    ) return
    if (controlCodeSignatureCleanupRequired) {
      recordRejectedControlCodeCommand(
        cleanRequestId,
        requestedOwner,
        requestedApp,
        requestedFlow,
        "control_code_cleanup_pending"
      )
      sendControlCodeResult(
        cleanRequestId,
        false,
        "control_code_cleanup_pending",
        "",
        0L,
        emptyMap(),
        cleanupPending = true
      )
      return
    }
    if (activeControlCodeKeyboardClamp != null) {
      recordRejectedControlCodeCommand(
        cleanRequestId,
        requestedOwner,
        requestedApp,
        requestedFlow,
        "control_code_keyboard_cleanup_pending"
      )
      sendControlCodeResult(
        cleanRequestId,
        false,
        "control_code_keyboard_cleanup_pending",
        "",
        0L,
        emptyMap(),
        cleanupPending = true
      )
      return
    }

    // Publish ownership before any session-start command can race this request.
    // The Spacetime desired-start lane will then coalesce onto the same phone
    // preparation instead of starting a second probe or session.
    lastControlCodeRequestId = cleanRequestId
    lastControlCodeRequestStatus = "queued"
    lastControlCodeRequestReason = null
    activeControlCodeGeneratedVisualSignature = ""
    activeControlCodeVisualSignatureEpoch = ""
    activeControlCodeRawDetailAnchor = ""
    activeControlCodeRawDetailState = TicketVisualPhoneState.UNKNOWN
    activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
    activeControlCodeVisualSignatureExpiresAtMillis = 0L
    persistControlCodeSignatureCleanupRequired(false)
    if (replyClient != null) protectedControlClients.add(replyClient)
    var refreshRequested = false
    var requestPhases: MutableMap<String, Long>? = null
    var requestStartedAtMillis = 0L
    var keyboardClampLease: RequestScopedKeyboardClampLease? = null
    var panelDarkLease: TicketActionPanelDarkLease? = null
    claimControlCodeAutomationForRequest()
    try {
      val startedAtMillis = SystemClock.elapsedRealtime()
      requestStartedAtMillis = startedAtMillis
      val phases: MutableMap<String, Long> = Collections.synchronizedMap(
        linkedMapOf("phone_command_received" to 0L)
      )
      databaseToPhoneMillis?.let { phases["database_to_phone_receipt"] = it }
      requestPhases = phases
      // One independent frame may be requested while this request waits for the phone lane.
      // Display and input mutations remain behind the confirmed raw-panel lease.
      refreshRequested = rootHardwareH264CaptureEngine.requestImmediateRefresh("control_code_browser_dispatch")
      if (refreshRequested) phases["capture_refresh_requested"] = 0L
      controlCodePhoneMutationLane.withOwnership {
        if (sendCachedControlCodeResult(cleanRequestId) ||
          controlCodeRequestDuplicateActiveOrCompleted(cleanRequestId)
        ) return@withOwnership

        var panelDarkLeaseAcquired = false
        var resultSent = false
        var generatedResultDelivered = false
        var deferredCleanupOk: Boolean? = null
        var deferredPhoneSurfaceCleaned = false
        var deferredCleanupReason = ""
        var reason = "control_code_request_session_unavailable"
        var value = ""
        var dirtyHierarchy = ""
        try {

        val panelOwnerId = "control_code:${cleanRequestId.takeLast(24)}"
        panelDarkLease = TicketActionPanelDarkLease(
          actionId = panelOwnerId,
          scope = serviceScope,
          clampRootExecutor = ticketActionPanelDarkRootExecutor,
          verifyRootExecutor = ticketActionPanelDarkVerifyRootExecutor,
          physicalTouchState = PhoneAutomationServiceBridge::currentRootPhysicalTouchState,
          ownerProcessId = android.os.Process.myPid(),
          onSnapshotChanged = { snapshot ->
            val prior = ticketActionPanelDarkLeaseSnapshot
            if (snapshot.failure.isNotBlank() &&
              (prior.ownerActionId != snapshot.ownerActionId || prior.failure.isBlank())
            ) {
              ticketActionPanelDarkLeaseFailures.incrementAndGet()
            }
            ticketActionPanelDarkLeaseSnapshot = snapshot
            broadcastStatus()
          }
        ).also { lease -> activeTicketActionPanelDarkLease = lease }
        PhoneAutomationServiceBridge.markNonTouchInput(
          reason = "ticket:control_code_panel_dark:${cleanRequestId.takeLast(24)}",
          durationMillis = TICKET_ACTION_V3_PANEL_DARK_LEASE_MILLIS
        )
        panelDarkLeaseAcquired = panelDarkLease?.acquire() == true
        if (!panelDarkLeaseAcquired) {
          val failureReason = "control_code_panel_dark_unavailable"
          recordTicketEvent(
            "control_code_panel_dark_acquire_failed",
            "lease=${panelDarkLease?.snapshot()?.failure.orEmpty()}"
          )
          lastControlCodeRequestStatus = "failed"
          lastControlCodeRequestReason = failureReason
          lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
          sendControlCodeResult(
            cleanRequestId,
            false,
            failureReason,
            "",
            startedAtMillis,
            phases,
            cleanupPending = false
          )
          return@withOwnership
        }

        keyboardClampLease = RequestScopedKeyboardClampLease(
          requestId = cleanRequestId,
          expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
          commandTimeoutMillis = CONTROL_CODE_KEYBOARD_CLAMP_COMMAND_TIMEOUT_MILLIS,
          onPhase = { phase -> markControlCodeRequestPhase(phases, phase, startedAtMillis) },
          onEvent = ::recordTicketEvent
        ).also { lease ->
          // The action-wide raw-panel lease is already confirmed before soft-keyboard suppression.
          // The final field-tap gate validates the already-owned hidden mode immediately before entry.
          activeControlCodeKeyboardClamp = lease
        }
        val keyboardClampAcquired = keyboardClampLease?.acquire() == true
        lastControlCodeRequestId = cleanRequestId
        lastControlCodeRequestStatus = "running"
        lastControlCodeRequestReason = null
        lastControlCodeRequestDurationMillis = null
        lastControlCodeRequestPhases = emptyMap()
        lastControlCodeRequestCompletedAtMillis = 0L
        sendControlCodeProgress(cleanRequestId, "running", "phone_request_started")
        val fastStateAcceptedAtAdmission = controlCodeFastStateRevisionAccepted(cleanFastRevision)
        markControlCodeFastNotReady("cleanup", "control_code_request")
        recordTicketEvent("control_code_request_running", "request=$cleanRequestId")
        try {
          if (!keyboardClampAcquired) {
            reason = "control_code_keyboard_clamp_unavailable"
          } else {
          val preflight = ensureControlCodeRequestPreflight(
            requestedFastRevision = cleanFastRevision,
            fastStateAcceptedAtAdmission = fastStateAcceptedAtAdmission,
            phases = phases,
            requestStartedAtMillis = startedAtMillis
          )
          if (preflight.ready &&
            measureInputPhase(phases, "gate") { canForwardRemoteInput() }
          ) {
            markControlCodeRequestPhase(phases, "request_gate_passed", startedAtMillis)
            if (!refreshRequested) {
              refreshRequested = rootHardwareH264CaptureEngine.requestImmediateRefresh("control_code_gate_ready")
            }
            val delivery = runFastControlCodeDeliveryForRequest(
              cleanDigits,
              preflight.ticketDetailHierarchy,
              phases,
              startedAtMillis
            )
            reason = delivery.reason
            value = delivery.value
            dirtyHierarchy = delivery.generatedHierarchy
            if (delivery.ok) {
              val duration = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
              lastControlCodeRequestStatus = "running"
              lastControlCodeRequestReason = "generated_cleanup_pending"
              beginControlCodeBrowserCaptureWait(cleanRequestId)
              recordControlCodeTimingSummary(
                stage = "result_ready",
                phases = phases,
                databaseToPhoneMillis = databaseToPhoneMillis,
                databaseSubmissionEpochMillis = databaseSubmissionEpochMillis
              )
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
              generatedResultDelivered = true
              val capture = waitForControlCodeBrowserCapture(cleanRequestId, phases, startedAtMillis)
              clearControlCodeBrowserCaptureWait(cleanRequestId)
              val cleanupReason = if (capture.ok) "browser_capture_confirmed" else capture.reason
              val cleaned = returnControlCodeSurfaceToRawTicket(
                dirtyHierarchy,
                cleanupReason,
                phases,
                startedAtMillis
              )
              val requestSucceeded = capture.ok && cleaned
              markControlCodeRequestPhase(phases, "cleanup_finished", startedAtMillis)
              deferredPhoneSurfaceCleaned = cleaned
              deferredCleanupOk = requestSucceeded
              deferredCleanupReason = when {
                !capture.ok -> "browser_capture_failed_${capture.reason}"
                cleaned -> "return_to_raw_complete"
                else -> "control_code_cleanup_attention_needed"
              }
            } else if (delivery.cleanupRequired) {
              sendControlCodeResult(
                cleanRequestId, false, reason, value, startedAtMillis, phases, cleanupPending = true
              )
              resultSent = true
              val cleaned = returnControlCodeSurfaceToRawTicket(
                dirtyHierarchy, "control_code_request_failed_return_raw", phases, startedAtMillis
              )
              deferredPhoneSurfaceCleaned = cleaned
              deferredCleanupOk = cleaned
              deferredCleanupReason = if (cleaned) {
                "ticket_detail"
              } else {
                "control_code_cleanup_attention_needed"
              }
            }
          } else {
            reason = inputGateReason.ifBlank { reason }
          }
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
              dirtyHierarchy,
              "control_code_request_exception_return_raw",
              phases,
              startedAtMillis
            )
          }.getOrDefault(false)
          deferredPhoneSurfaceCleaned = cleaned
          deferredCleanupOk = cleaned
          deferredCleanupReason = if (cleaned) {
            "ticket_detail"
          } else {
            "control_code_cleanup_attention_needed"
          }
        }
        lastControlCodeRequestDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
        lastControlCodeRequestPhases = phases.toMap()
        recordControlCodeTimingSummary(
          stage = "finished",
          phases = phases,
          databaseToPhoneMillis = databaseToPhoneMillis,
          databaseSubmissionEpochMillis = databaseSubmissionEpochMillis,
          totalDurationMillis = lastControlCodeRequestDurationMillis
        )
        } finally {
          var keyboardClampReleased = true
          val panelFinalization = withContext(NonCancellable) {
            keyboardClampReleased = keyboardClampLease?.release(
              "control_code_request_finally"
            ) ?: true
            if (keyboardClampReleased && activeControlCodeKeyboardClamp === keyboardClampLease) {
              activeControlCodeKeyboardClamp = null
            }
            val completed = if (panelDarkLeaseAcquired) {
              panelDarkLease?.releaseAfterFinalConvergence("control_code_terminal")
            } else {
              panelDarkLease?.release("control_code_acquire_failed")
            }
            if (activeTicketActionPanelDarkLease === panelDarkLease) {
              activeTicketActionPanelDarkLease = null
            }
            PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction(
              "ticket:control_code_panel_dark:${cleanRequestId.takeLast(24)}:released"
            )
            completed
          }
          if (panelDarkLeaseAcquired) {
            val leaseState = panelFinalization?.snapshot ?: panelDarkLease?.snapshot()
              ?: TicketActionPanelDarkLeaseSnapshot()
            if (panelFinalization?.safe == true && keyboardClampReleased) {
              val localCleanupCommitted = if (
                ticketControlCodeCleanupMayCommitAfterPanelFinalization(
                  deferredPhoneSurfaceCleaned,
                  panelFinalization
                )
              ) {
                commitControlCodeCleanStateAfterPanelFinalization(
                  "control_code_request:$deferredCleanupReason"
                )
              } else {
                false
              }
              if (leaseState.mutationMayHaveDispatched && !localCleanupCommitted) {
                preserveControlCodeCleanupCheckpoint(
                  "control_code_request_checkpoint_clear_unproved:$deferredCleanupReason"
                )
              }
              if (!resultSent) {
                val cleanupPending = leaseState.mutationMayHaveDispatched && !localCleanupCommitted
                sendControlCodeResult(
                  cleanRequestId, false, reason, value, startedAtMillis, phases,
                  cleanupPending = cleanupPending
                )
                resultSent = true
              }
              deferredCleanupOk?.let { cleanupOk ->
                if (cleanupOk && localCleanupCommitted) {
                  publishControlCodeReadyAfterPanelFinalization(cleanRequestId)
                  sendControlCodeCleanup(
                    cleanRequestId, true, deferredCleanupReason, startedAtMillis
                  )
                  markControlCodeFastReady("cleanup:$deferredCleanupReason")
                  if (generatedResultDelivered) {
                    lastControlCodeRequestStatus = "succeeded"
                    lastControlCodeRequestReason = "generated"
                    lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
                  }
                } else {
                  val cleanupFailureReason = if (
                    deferredPhoneSurfaceCleaned && !localCleanupCommitted
                  ) {
                    "control_code_cleanup_checkpoint_clear_unproved"
                  } else {
                    deferredCleanupReason
                  }
                  lastControlCodeRequestStatus = "failed"
                  lastControlCodeRequestReason = cleanupFailureReason
                  lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
                  sendControlCodeCleanup(
                    cleanRequestId, false, cleanupFailureReason, startedAtMillis
                  )
                  markControlCodeFastNotReady("blocked", cleanupFailureReason)
                }
              }
            } else {
              val afterPossibleDispatch = leaseState.mutationMayHaveDispatched
              if (!keyboardClampReleased ||
                afterPossibleDispatch ||
                deferredCleanupOk != null ||
                controlCodeSignatureCleanupRequired
              ) {
                preserveControlCodeCleanupCheckpoint(
                  if (keyboardClampReleased) {
                    "control_code_request_unsafe_finalization"
                  } else {
                    "control_code_keyboard_restore_unproved"
                  }
                )
              }
              val failureReason = when {
                !keyboardClampReleased -> "control_code_keyboard_restore_failed"
                afterPossibleDispatch -> "control_code_cleanup_attention_needed"
                else -> "control_code_panel_dark_unavailable"
              }
              lastControlCodeRequestStatus = "failed"
              lastControlCodeRequestReason = failureReason
              lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
              if (!resultSent) {
                sendControlCodeResult(
                  cleanRequestId, false, failureReason, value, startedAtMillis, phases,
                  cleanupPending = afterPossibleDispatch || !keyboardClampReleased
                )
                resultSent = true
              }
              if (!keyboardClampReleased || afterPossibleDispatch || deferredCleanupOk != null) {
                sendControlCodeCleanup(
                  cleanRequestId, false, failureReason, startedAtMillis
                )
              }
              markControlCodeFastNotReady("blocked", failureReason)
            }
          }
        }
      }
    } finally {
      requestPhases?.let { phases ->
        lastControlCodeRequestPhases = synchronized(phases) { phases.toMap() }
      }
      rootHardwareH264CaptureEngine.clearTransientControlCodeVisualProbes()
      scheduleControlCodeVisualSignatureExpiry()
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
      TicketUiautomatorDump.commandForRigasSatiksme(
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
    if (expectedPackageName == TicketScreenConfig.VIVI_PACKAGE) {
      recordTicketEvent(
        "ticket_hierarchy_read_rejected",
        "reason=$reason source=accessibility_visual_only"
      )
      return ""
    }
    val startedAt = SystemClock.elapsedRealtime()
    val nodes = PhoneAutomationServiceBridge.snapshotVisibleNodes(expectedPackageName)
    val durationMs = SystemClock.elapsedRealtime() - startedAt
    if (nodes.isEmpty()) {
      recordTicketEvent("fast_visible_hierarchy_empty", "reason=$reason package=$expectedPackageName duration_ms=$durationMs")
      return ""
    }
    return buildFastVisibleHierarchy(nodes, expectedPackageName, reason, durationMs)
  }

  private suspend fun fastTicketRegistrationHierarchy(reason: String): String {
    val startedAt = SystemClock.elapsedRealtime()
    val nodes = PhoneAutomationServiceBridge.snapshotTicketRegistrationNodes(
      TicketScreenConfig.VIVI_PACKAGE
    )
    val durationMs = SystemClock.elapsedRealtime() - startedAt
    if (nodes.isEmpty()) {
      recordTicketEvent(
        "ticket_registration_hierarchy_empty",
        "reason=$reason duration_ms=$durationMs"
      )
      return ""
    }
    return buildFastVisibleHierarchy(
      nodes = nodes,
      expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
      reason = reason,
      durationMs = durationMs
    )
  }

  private fun buildFastVisibleHierarchy(
    nodes: List<PhoneAutomationVisibleNode>,
    expectedPackageName: String,
    reason: String,
    durationMs: Long
  ): String {
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

  private suspend fun observeAccessibilityViviState(reason: String): RootViviObservation {
    return observeRootViviState(reason = "accessibility_retired:$reason")
  }

  private suspend fun dumpVisibleHierarchyWithRoot(path: String, reason: String): String {
    recordTicketEvent(
      "ticket_hierarchy_read_rejected",
      "reason=$reason path=${path.substringAfterLast('/')} source=visual_only"
    )
    return ""
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
    ticketDetailHierarchy: String?,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodeDelivery {
    val transaction = openControlCodePopupFastForRequest(
      ticketDetailHierarchy,
      phases,
      requestStartedAtMillis
    ) ?: return FastControlCodeDelivery(
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
    val waitOutcome = waitForGeneratedControlCodeResultAfterSubmit(
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      submittedDigits = cleanDigits,
      timeoutMillis = CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS
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
      ) ?: return FastControlCodeDelivery(
        ok = false,
        reason = "control_code_generated_frame_watermark_unavailable",
        generatedHierarchy = generated.hierarchy,
        cleanupRequired = true
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

  private suspend fun requestFreshControlCodeFrameWatermark(reason: String): Pair<Long, Long>? {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val startingEpoch = streamEpoch
    val startingSequence = frameSequence
    requestKeyFrame(reason)
    val deadlineMillis = startedAtMillis + CONTROL_CODE_BROWSER_MARKER_PROBE_WAIT_MILLIS
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      val currentEpoch = streamEpoch
      val currentKeyFrameSequence = latestKeyFrame?.sequence ?: 0L
      if (
        currentEpoch > 0L &&
        currentKeyFrameSequence > 0L &&
        (currentEpoch != startingEpoch || currentKeyFrameSequence > startingSequence)
      ) {
        return currentEpoch to currentKeyFrameSequence
      }
      delay(CONTROL_CODE_VISUAL_STATE_POLL_MILLIS)
    }
    recordTicketEvent(
      "control_code_request_result_marker_frame_wait_timeout",
      "epoch=$streamEpoch sequence=$frameSequence start_epoch=$startingEpoch start_sequence=$startingSequence reason=$reason"
    )
    return null
  }

  private fun requestFreshTicketStateFrameWatermark(reason: String): Pair<Long, Long> {
    requestKeyFrame(reason)
    val eventStreamEpoch = streamEpoch
    val eventFrameSequence = (frameSequence + 1L).coerceAtLeast(1L)
    return eventStreamEpoch to eventFrameSequence
  }

  private suspend fun openControlCodePopupFastForRequest(
    ticketDetailHierarchy: String?,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): FastControlCodePopupTransaction? {
    val initialVisualDetail = awaitStableTicketVisualActionObservation(
      "control_code_before_button",
      TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS
    )
    if (initialVisualDetail?.state !in setOf(
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        TicketVisualPhoneState.UNACTIVATED_DETAIL
      )) {
      recordInputGateDecision(allowed = false, reason = "control_code_button_visual_unproved")
      recordTicketEvent("control_code_request_fast_fail", "reason=button_visual_unproved")
      return null
    }
    val visualDetail = initialVisualDetail ?: return null
    if (!ticketControlCodeDetailAnchorIsValid(visualDetail.currentAnchor)) {
      recordInputGateDecision(allowed = false, reason = "control_code_detail_identity_unproved")
      recordTicketEvent("control_code_request_fast_fail", "reason=detail_identity_unproved")
      return null
    }
    val detectedControlBounds = visualDetail?.controlCodeBounds ?: run {
      recordInputGateDecision(allowed = false, reason = "control_code_button_bounds_unproved")
      return null
    }
    activeControlCodeGeneratedVisualSignature = ""
    activeControlCodeVisualSignatureEpoch = ""
    activeControlCodeRawDetailAnchor = visualDetail.currentAnchor
    activeControlCodeRawDetailState = visualDetail.state
    activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
    activeControlCodeVisualSignatureExpiresAtMillis = SystemClock.elapsedRealtime() +
      CONTROL_CODE_VISUAL_SIGNATURE_TTL_MILLIS
    if (!persistControlCodeSignatureCleanupRequired(true)) {
      activeControlCodeGeneratedVisualSignature = ""
      activeControlCodeVisualSignatureEpoch = ""
      activeControlCodeRawDetailAnchor = ""
      activeControlCodeRawDetailState = TicketVisualPhoneState.UNKNOWN
      activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
      activeControlCodeVisualSignatureExpiresAtMillis = 0L
      recordInputGateDecision(allowed = false, reason = "control_code_cleanup_checkpoint_failed")
      return null
    }
    val mappedControlBounds = ticketVisualProbeBoundsToDevice(detectedControlBounds)
    val action = TicketViviPageAction(
      x = (mappedControlBounds.left + mappedControlBounds.right) / 2,
      y = (mappedControlBounds.top + mappedControlBounds.bottom) / 2,
      reason = "control_code_visual_anchor",
      bounds = "${mappedControlBounds.left},${mappedControlBounds.top},${mappedControlBounds.right},${mappedControlBounds.bottom}"
    )
    recordInputGateDecision(allowed = true, reason = action.reason)
    markControlCodeTransition("control_code_request_open_popup_detected")
    val tap = measureInputPhase(phases, "first_tap_fast") {
      runFastNonTouchInput(
        "input tap ${action.x} ${action.y}",
        "control_code_request_open_popup_detected",
        postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS
      )
    }
    if (!tap.ok) {
      recordInputGateDecision(allowed = false, reason = "control_code_button_tap_failed")
      return null
    }
    markControlCodeRequestPhase(phases, "first_phone_tap", requestStartedAtMillis)

    val layoutProbe = awaitStableControlCodeSubmitLayout()
    val layout = layoutProbe?.result
    if (layout == TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY ||
      layout == TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY
    ) {
      val transaction = visualControlCodePopupTransaction(layoutProbe) ?: run {
        recordInputGateDecision(allowed = false, reason = "control_code_popup_geometry_unproved")
        return null
      }
      markOpenedControlCodePopupTransactionReady(
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        source = "rooted_visual_probe",
        eventReason = "control_code_popup_transaction_ready"
      )
      return FastControlCodePopupTransaction(
        open = action,
        input = transaction.input,
        submit = transaction.submit,
        inputSource = "rooted_visual_probe",
        submitSource = "rooted_visual_probe"
      )
    }
    recordInputGateDecision(allowed = false, reason = "control_code_popup_bounds_unavailable")
    recordTicketEvent("control_code_request_fast_fail", "reason=popup_bounds_unavailable")
    return null
  }

  private fun scheduleControlCodeVisualSignatureExpiry() {
    val expectedExpiry = activeControlCodeVisualSignatureExpiresAtMillis
    if (!controlCodeSignatureCleanupRequired || expectedExpiry <= 0L) return
    serviceScope.launch {
      val remaining = expectedExpiry - SystemClock.elapsedRealtime()
      if (remaining > 0L) delay(remaining)
      if (controlCodeSignatureCleanupRequired &&
        activeControlCodeVisualSignatureExpiresAtMillis == expectedExpiry &&
        SystemClock.elapsedRealtime() >= expectedExpiry
      ) {
        activeControlCodeGeneratedVisualSignature = ""
        activeControlCodeVisualSignatureEpoch = ""
        activeControlCodeRawDetailAnchor = ""
        activeControlCodeRawDetailState = TicketVisualPhoneState.UNKNOWN
        activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
        activeControlCodeVisualSignatureExpiresAtMillis = 0L
      }
    }
  }

  private fun persistControlCodeSignatureCleanupRequired(required: Boolean): Boolean {
    val editor = applicationContext.getSharedPreferences(
      CONTROL_CODE_VISUAL_CHECKPOINT_PREFERENCES,
      Context.MODE_PRIVATE
    ).edit().putBoolean(CONTROL_CODE_VISUAL_CHECKPOINT_PENDING_KEY, required)
    // Both setting and clearing this gate are safety decisions. Public readiness may follow a
    // clear, so an asynchronous apply is not strong enough for the finalization boundary.
    if (!editor.commit()) return false
    controlCodeSignatureCleanupRequired = required
    return true
  }

  private fun preserveControlCodeCleanupCheckpoint(reason: String): Boolean {
    val persisted = persistControlCodeSignatureCleanupRequired(true)
    if (!persisted) {
      // Keep the live admission fence closed even if durable storage is temporarily unavailable.
      // The failed durable write is itself a needs-attention condition and must never reopen input.
      controlCodeSignatureCleanupRequired = true
      recordTicketEvent(
        "control_code_cleanup_checkpoint_set_unproved",
        "reason=$reason"
      )
    }
    return persisted
  }

  private suspend fun awaitStableControlCodeSubmitLayout(
    requireGeometry: Boolean = true
  ): TicketControlCodeVisualProbe? {
    var previous: TicketControlCodeVisualProbe? = null
    repeat(CONTROL_CODE_FAST_INTERACTION_RETRY_COUNT + 2) { attempt ->
      val started = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestControlCodeSubmitVisualProbe(
        "control_code_popup_visual_${attempt + 1}"
      ) ?: return@repeat
      val current = waitForFreshControlCodeVisualProbe(
        started,
        probeId,
        CONTROL_CODE_SUBMIT_VISUAL_PROBE_WAIT_MILLIS
      )
      val result = current?.result ?: TicketControlCodeVisualClassifier.UNKNOWN
      val supportedResult = result in setOf(
          TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
          TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
          TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY
        )
      val geometryAgrees = !requireGeometry || (
        current?.inputBounds != null && current.submitBounds != null &&
          previous?.inputBounds == current.inputBounds && previous?.submitBounds == current.submitBounds
        )
      if (previous != null && previous.probeId != probeId && result == previous.result &&
        supportedResult && geometryAgrees
      ) return current
      previous = current
      delay(CONTROL_CODE_FAST_POLL_MILLIS)
    }
    return null
  }

  private fun visualControlCodePopupTransaction(
    probe: TicketControlCodeVisualProbe
  ): FastControlCodePopupTransaction? {
    val inputBounds = probe.inputBounds ?: return null
    val submitBounds = probe.submitBounds ?: return null
    fun action(bounds: TicketControlCodeVisualBounds, reason: String): TicketViviPageAction {
      val mapped = controlCodeVisualBoundsToDevice(
        bounds = bounds,
        probeWidth = TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_WIDTH,
        probeHeight = TicketControlCodeVisualClassifier.SUBMIT_SAMPLE_HEIGHT
      )
      return TicketViviPageAction(
        x = (mapped.left + mapped.right) / 2,
        y = (mapped.top + mapped.bottom) / 2,
        reason = reason,
        bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
      )
    }
    return FastControlCodePopupTransaction(
      input = action(inputBounds, "control_code_visual_input"),
      submit = action(submitBounds, "control_code_visual_submit"),
      inputSource = "rooted_visual_probe",
      submitSource = "rooted_visual_probe"
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
    var typeResult = measureInputPhase(phases, "root_input_manager_type") {
      executeRootControlCodeType(digits, transaction, "control_code_root_input_manager_type")
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
      recordInputGateDecision(allowed = false, reason = "control_code_root_input_manager_type_failed")
      recordTicketEvent(
        "control_code_root_input_manager_type_failed",
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
        "input_manager_no_device_lifecycle"
      )
    }
    markControlCodeRequestPhase(phases, "first_digit_entry", requestStartedAtMillis)
    var valueProof = waitForEnteredControlCodeValueVisualProof(phases)
    if (valueProof == ControlCodeEnteredValueProof.STATIC_BLANK) {
      recordTicketEvent(
        "control_code_value_render_recheck_started",
        "settle_ms=$CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS"
      )
      delay(CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS)
      markControlCodeRequestPhase(phases, "value_render_recheck_started", requestStartedAtMillis)
      valueProof = waitForEnteredControlCodeValueVisualProof(phases)
      markControlCodeRequestPhase(phases, "value_render_recheck_finished", requestStartedAtMillis)
    }
    if (valueProof == ControlCodeEnteredValueProof.STATIC_BLANK) {
      phases["control_code_root_retype_attempted"] = 1L
      recordTicketEvent(
        "control_code_root_retype_attempted",
        "blank_unshifted_popup_reproved input_manager_retry=true"
      )
      typeResult = measureInputPhase(phases, "root_input_manager_retype") {
        executeRootControlCodeType(
          digits,
          transaction.copy(open = null),
          "control_code_root_input_manager_retype"
        )
      }
      valueProof = if (typeResult.ok) {
        waitForEnteredControlCodeValueVisualProof(phases)
      } else {
        recordTicketEvent(
          "control_code_root_input_manager_retype_failed",
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
    val submitProbe = awaitStableControlCodeSubmitLayout()
    val submitTransaction = submitProbe?.takeIf {
      it.result == TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY
    }?.let(::visualControlCodePopupTransaction)
    if (submitTransaction == null) {
      recordInputGateDecision(allowed = false, reason = "control_code_submit_target_unproved")
      recordTicketEvent(
        "control_code_submit_target_unproved",
        "fresh_value_and_ok_geometry_required"
      )
      return false
    }
    markControlCodeRequestPhase(phases, "digits_typed", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "field_focus_and_value_proved", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "ok_target_proved", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "ok_dispatch_started", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_root_value_proved_submit_dispatched",
      "input_manager_keyevents digits=${digits.length} input=${transaction.inputSource} submit=${submitTransaction.submitSource} fresh_target=true"
    )
    val submitted = measureInputPhase(phases, "root_submit_after_value_proof") {
      tapControlCodePointWithoutKeyboard(
        submitTransaction.submit.x,
        submitTransaction.submit.y,
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
      "acknowledged=$submitted digits=${digits.length} input=${transaction.inputSource} submit=${submitTransaction.submitSource} fresh_target=true"
    )
    return true
  }

  private suspend fun executeRootControlCodeType(
    digits: String,
    transaction: FastControlCodePopupTransaction,
    reason: String
  ): RootResult {
    if (!awaitControlCodeKeyboardClamp(reason)) {
      return RootResult(
        exitCode = 43,
        stdout = "",
        stderr = "request-scoped virtual keyboard suppression was not applied",
        command = "keyboard_clamp_gate:$reason",
        durationMs = 0L
      )
    }
    return runSensitiveFastNonTouchScript(
      // The popup and its input geometry were already proved on two fresh visual frames.
      // Re-tapping the header here can dismiss the current modal through its scrim, so the
      // one-shot InputManager helper must focus only the proved input before entering the value.
      command = TicketControlCodeRootInput.buildTypeScript(
        digits = digits,
        inputX = transaction.input.x,
        inputY = transaction.input.y
      ),
      reason = reason,
      timeout = CONTROL_CODE_ROOT_TRANSACTION_TIMEOUT_MILLIS.milliseconds
    )
  }

  private suspend fun awaitControlCodeKeyboardClamp(reason: String): Boolean {
    val lease = activeControlCodeKeyboardClamp
    if (lease == null) {
      recordTicketEvent(
        "keyboard_clamp_gate_failed",
        "reason=$reason request_scoped_lease_missing"
      )
      return false
    }
    val ready = lease.awaitApplied()
    if (!ready) {
      recordTicketEvent(
        "keyboard_clamp_gate_failed",
        "reason=$reason request_scoped_lease_not_ready"
      )
    }
    return ready
  }

  private suspend fun tapControlCodePointWithoutKeyboard(
    x: Int,
    y: Int,
    reason: String
  ): Boolean {
    if (!beginControlCodePanelDarkMutation(reason)) return false
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
    timeoutMillis: Long
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
    val generatedWithCloseProof = TicketGeneratedWithCloseProof()
    var submitRetryAttempted = false
    var popupRejectCountAfterRetry = 0L
    while (SystemClock.elapsedRealtime() < deadlineAtMillis) {
      val visualProbeStartedAtMillis = SystemClock.elapsedRealtime()
      val visualProbeId = rootHardwareH264CaptureEngine
        .requestControlCodeCleanupVisualProbe("control_code_after_ok_visual_state")
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
        if (generatedWithCloseProof.observe(visualProbe)) {
          activeControlCodeGeneratedVisualSignature = visualProbe.visualSignature
          activeControlCodeVisualSignatureEpoch = visualProbe.visualSignatureEpoch
          activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE
          val visualMarker = confirmGeneratedControlCodeResultForBrowser(
            value = "",
            hierarchy = CONTROL_CODE_MARKER_RESULT_HIERARCHY,
            phases = phases,
            requestStartedAtMillis = requestStartedAtMillis,
            waitStartedAtMillis = startedAtMillis,
            phase = "wait_result_phone_visual_generated_with_close",
            modeReason = "control_code_request_generated_with_close_after_submit",
            eventValue = "phone_visual_generated_with_close_after_submit",
            resultProof = "phone_visual_generated_with_close",
            expectedResultMode = activeControlCodeVisualResultMode
          ) ?: return ControlCodeResultWaitOutcome(
            failureReason = "control_code_generated_frame_watermark_unavailable"
          )
          phases["control_code_visual_popup_reject_count"] = popupRejectCount
          return ControlCodeResultWaitOutcome(generated = visualMarker, failureReason = "")
        }
        when {
          visualProbe.result in setOf("generated", "raw_ticket") -> {
            rawTicketVisualCount += 1L
            phases["control_code_visual_raw_ticket_state"] = SystemClock.elapsedRealtime() - startedAtMillis
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
              val retryProbe = awaitStableControlCodeSubmitLayout()
              val retryLayout = retryProbe?.result
              val retryTransaction = retryProbe?.let(::visualControlCodePopupTransaction)
              if (retryLayout != TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY ||
                retryTransaction == null
              ) {
                recordTicketEvent(
                  "control_code_submit_retry_not_attempted",
                  "fresh_popup_visual_bounds_unavailable"
                )
                return ControlCodeResultWaitOutcome(
                  failureReason = "control_code_submit_retry_target_unproved"
                )
              }
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
    phases["wait_result_phone_visual_generated_state"] = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    phases["control_code_visual_popup_reject_count"] = popupRejectCount
    val failureReason = when (lastObservedState) {
      TicketControlCodeVisualClassifier.CONTROL_POPUP -> "control_code_submit_still_open"
      TicketControlCodeVisualClassifier.RAW_TICKET -> "control_code_not_generated"
      else -> "control_code_generated_state_timeout"
    }
    recordTicketEvent(
      "control_code_generated_state_timeout",
      "last_state=$lastObservedState popup_rejects=$popupRejectCount digits=${submittedDigits.length} visual_only=true"
    )
    return ControlCodeResultWaitOutcome(
      failureReason = failureReason,
      failureHierarchy = ""
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
    resultProof: String = "phone_visual",
    expectedResultMode: TicketControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
  ): GeneratedControlCodeResult? {
    phases[phase] = (SystemClock.elapsedRealtime() - waitStartedAtMillis).coerceAtLeast(0L)
    markControlCodeRequestPhase(phases, "result_first_visible", requestStartedAtMillis)
    markControlCodeModeEntered(modeReason)
    rememberControlCodeSurface(TicketViviRecoveryState.CONTROL_CODE_RESULT)
    recordTicketEvent("control_code_request_result_detected", eventValue)
    val watermark = markerFirstControlCodeFrameWatermarkForBrowser(
      reason = "control_code_result_after_phone_visual_proof",
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      expectedResultMode = expectedResultMode
    ) ?: return null
    return GeneratedControlCodeResult(
      value = value,
      hierarchy = hierarchy,
      streamEpoch = watermark.first,
      minFrameSequence = watermark.second,
      resultProof = resultProof,
      resultProofAtMillis = System.currentTimeMillis()
    )
  }

  private suspend fun markerFirstControlCodeFrameWatermarkForBrowser(
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    expectedResultMode: TicketControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
  ): Pair<Long, Long>? {
    // The generated proof above is a rooted visual proof, but the old marker was published
    // against the current stream counter immediately. That let the browser receive a valid
    // marker before the next H.264 frame had actually carried the generated surface. Wait for a
    // fresh generated probe first, then watermark the following frame. This keeps the browser
    // capture proof visual and prevents the phone from cleaning the result before it can freeze.
    // Settle before the final probe so the exact proved surface is the one immediately bound to
    // the requested stream watermark.
    recordTicketEvent(
      "control_code_request_result_marker_delay",
      "delay_ms=$CONTROL_CODE_GENERATED_RESULT_MARKER_DELAY_MILLIS reason=$reason"
    )
    measureInputPhase(phases, "result_marker_delay") {
      delay(CONTROL_CODE_GENERATED_RESULT_MARKER_DELAY_MILLIS)
    }
    val markerWaitStartedAtMillis = SystemClock.elapsedRealtime()
    var markerProbeId = 0L
    val markerProbeState: String
    val markerGeneratedFrameConfirmed = when (expectedResultMode) {
      TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE -> {
        val generated = awaitStableGeneratedControlCodeCloseProbe()
        markerProbeId = generated?.probeId ?: 0L
        markerProbeState = generated?.result ?: "probe_unavailable"
        generated?.result == TicketControlCodeVisualClassifier.GENERATED && generated.closeBounds != null
      }
      TicketControlCodeVisualResultMode.NONE -> {
        markerProbeState = "result_mode_unproved"
        false
      }
    }
    phases["wait_result_browser_frame"] =
      (SystemClock.elapsedRealtime() - markerWaitStartedAtMillis).coerceAtLeast(0L)
    recordTicketEvent(
      if (markerGeneratedFrameConfirmed) {
        "control_code_request_result_marker_visual_ready"
      } else {
        "control_code_request_result_marker_visual_unconfirmed"
      },
      "state=$markerProbeState probe_id=$markerProbeId mode=${expectedResultMode.name.lowercase()}"
    )
    if (!markerGeneratedFrameConfirmed) {
      markControlCodeRequestPhase(phases, "result_marker_failed", requestStartedAtMillis)
      return null
    }
    val watermark = requestFreshControlCodeFrameWatermark(reason) ?: run {
      markControlCodeRequestPhase(phases, "result_marker_frame_failed", requestStartedAtMillis)
      return null
    }
    markControlCodeRequestPhase(phases, "result_marker_frame_requested", requestStartedAtMillis)
    markControlCodeRequestPhase(phases, "result_marker_frame_ready", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_request_result_marker_ready",
      "epoch=${watermark.first} min_sequence=${watermark.second} reason=$reason visual_confirmed=$markerGeneratedFrameConfirmed"
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

  private suspend fun healGeneratedControlCodeResultForRequest(
    generatedHierarchy: String,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    freshFrameRequired: Boolean = true
  ): Boolean {
    repeat(CONTROL_CODE_GENERATED_HEAL_MAX_CLOSE_ATTEMPTS) { attempt ->
      val cleanState = waitForCleanTicketSurfaceFast(
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS,
        returnOnFreshGeneratedResult = true
      )
      if (cleanState == TicketViviRecoveryState.TICKET_DETAIL) {
        recordTicketEvent("control_code_generated_heal_ready", "already_ticket_detail")
        return true
      }
      if (cleanState != TicketViviRecoveryState.CONTROL_CODE_RESULT) {
        recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
        recordTicketEvent("control_code_generated_heal_failed", "state=${cleanState?.name ?: "unknown"}")
        return false
      }
      val closeStartedAtMillis = SystemClock.elapsedRealtime()
      val cleanup = beginGeneratedControlCodeResultFastClose(
        generatedHierarchy = CONTROL_CODE_MARKER_RESULT_HIERARCHY,
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
      )
      if (!cleanup.closeSucceeded) {
        recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
        recordTicketEvent("control_code_generated_heal_failed", "close_failed_${attempt + 1}")
        return false
      }
      val afterClose = waitForCleanTicketSurfaceFast(
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS
      )
      if (afterClose == TicketViviRecoveryState.TICKET_DETAIL) {
        markControlCodeRequestPhase(phases, "phone_raw_recovered", requestStartedAtMillis)
        recordTicketEvent("control_code_generated_heal_ready", "closed_after_${attempt + 1}")
        return completeFastVerifiedTicketDetailControlExitCleanup(
          reason = reason,
          closeAction = cleanup.closeAction,
          startedAtMillis = closeStartedAtMillis,
          firstVerificationResult = "generated_heal_surface_clean",
          freshFrameRequested = freshFrameRequired
        )
      }
    }
    recordInputGateDecision(allowed = false, reason = "phone_stuck_on_generated_code")
    recordTicketEvent("control_code_generated_heal_failed", "phone_stuck_on_generated_code")
    return false
  }

  private suspend fun returnControlCodeSurfaceToRawTicket(
    generatedHierarchy: String,
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long
  ): Boolean {
    val startedAtMillis = SystemClock.elapsedRealtime()
    if (generatedHierarchy == CONTROL_CODE_MARKER_RESULT_HIERARCHY) {
      val cleanupStart = beginGeneratedControlCodeResultFastClose(
        generatedHierarchy = generatedHierarchy,
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
      )
      return finishGeneratedControlCodeResultFastCleanup(
        cleanupStart = cleanupStart,
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
      )
    }
    if (generatedHierarchy.isBlank()) {
      // A failed value proof leaves the input sheet open, but its hierarchy can be temporarily
      // unavailable while the root keyboard helper is settling. The submit-layout probe is
      // already the authoritative popup proof; dismiss that sheet with BACK before spending
      // time on broad foreground recovery. This never runs for a generated result, whose
      // cleanup remains the badge X path above.
      tryDismissOpenControlCodePopupAfterInputFailure(
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis,
        startedAtMillis = startedAtMillis
      )?.let { return it }
    }
    val cleanState = waitForCleanTicketSurfaceFast(
      reason = reason,
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS,
      returnOnFreshGeneratedResult = true
    )
    if (cleanState == TicketViviRecoveryState.TICKET_DETAIL) {
      return completeFastVerifiedTicketDetailControlExitCleanup(reason, "none", startedAtMillis, "surface_clean")
    }
    if (cleanState == TicketViviRecoveryState.CONTROL_CODE_RESULT) {
      val cleanupStart = beginGeneratedControlCodeResultFastClose(
        generatedHierarchy = CONTROL_CODE_MARKER_RESULT_HIERARCHY,
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
      )
      return finishGeneratedControlCodeResultFastCleanup(
        cleanupStart = cleanupStart,
        reason = reason,
        phases = phases,
        requestStartedAtMillis = requestStartedAtMillis
      )
    }
    return false
  }

  private suspend fun tryDismissOpenControlCodePopupAfterInputFailure(
    reason: String,
    phases: MutableMap<String, Long>,
    requestStartedAtMillis: Long,
    startedAtMillis: Long
  ): Boolean? {
    val visualResult = awaitStableControlCodeSubmitLayout(requireGeometry = false)?.result
    val popupProof = visualResult in setOf(
      TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY,
      TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY,
      TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY
    )
    if (!popupProof) {
      return null
    }
    markControlCodeRequestPhase(phases, "failed_entry_popup_proved", requestStartedAtMillis)
    recordTicketEvent(
      "control_code_failed_entry_popup_proved",
      "reason=$reason result=$visualResult"
    )
    val dismissed = measureInputPhase(phases, "failed_entry_popup_back") {
      runFastOneShotControlSurfaceCloseInput(
        command = "input keyevent KEYCODE_BACK",
        reason = "control_code_failed_entry_popup_back"
      ).ok
    }
    if (!dismissed) {
      recordTicketEvent("control_code_failed_entry_popup_back_failed", reason)
      return false
    }
    requestKeyFrame("control_code_failed_entry_popup_back")
    val cleanState = waitForCleanTicketSurfaceFast(
      reason = reason,
      phases = phases,
      requestStartedAtMillis = requestStartedAtMillis,
      timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS
    )
    if (cleanState != TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent(
        "control_code_failed_entry_popup_back_unproved",
        "state=${cleanState?.name ?: "unavailable"}"
      )
      return false
    }
    markControlCodeRequestPhase(phases, "phone_raw_recovered", requestStartedAtMillis)
    return completeFastVerifiedTicketDetailControlExitCleanup(
      reason = reason,
      closeAction = "back_control_code_popup",
      startedAtMillis = startedAtMillis,
      firstVerificationResult = "failed_entry_popup_dismissed"
    )
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

    if (activeControlCodeVisualResultMode !=
      TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE
    ) {
      recordTicketEvent(
        "control_code_fast_cleanup_close_blocked_unproved",
        "fresh_state=result_mode_unproved source=rooted_visual_probe"
      )
      return FastControlCodeCleanupStart(
        startedAtMillis = startedAtMillis,
        closeAction = "none",
        action = null,
        closeSucceeded = false,
        fallbackState = TicketViviRecoveryState.UNKNOWN_VIVI
      )
    }

    val closeProbe = awaitStableGeneratedControlCodeCloseProbe()
    val closeBounds = closeProbe?.closeBounds
    if (closeProbe?.result != TicketControlCodeVisualClassifier.GENERATED || closeBounds == null) {
      recordTicketEvent(
        "control_code_fast_cleanup_close_blocked_unproved",
        "fresh_state=${closeProbe?.result ?: "unknown"} source=rooted_visual_probe"
      )
      return FastControlCodeCleanupStart(
        startedAtMillis = startedAtMillis,
        closeAction = "none",
        action = null,
        closeSucceeded = false,
        fallbackState = TicketViviRecoveryState.UNKNOWN_VIVI
      )
    }
    val mapped = controlCodeVisualBoundsToDevice(closeBounds)
    val action = TicketViviPageAction(
      x = (mapped.left + mapped.right) / 2,
      y = (mapped.top + mapped.bottom) / 2,
      reason = "control_code_generated_close_visual",
      bounds = "${mapped.left},${mapped.top},${mapped.right},${mapped.bottom}"
    )
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

  private suspend fun awaitStableGeneratedControlCodeCloseProbe(): TicketControlCodeVisualProbe? {
    val proof = TicketGeneratedWithCloseProof()
    val deadlineMillis = SystemClock.elapsedRealtime() +
      CONTROL_CODE_GENERATED_CLOSE_PROOF_TIMEOUT_MILLIS
    repeat(CONTROL_CODE_FAST_INTERACTION_RETRY_COUNT + 2) { attempt ->
      val remainingMillis = deadlineMillis - SystemClock.elapsedRealtime()
      if (remainingMillis <= 0L) return null
      val started = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestControlCodeCleanupVisualProbe(
        "control_code_generated_close_${attempt + 1}"
      ) ?: return@repeat
      val current = waitForFreshControlCodeVisualProbe(
        started,
        probeId,
        minOf(CONTROL_CODE_GENERATED_CLOSE_PROBE_WAIT_MILLIS, remainingMillis)
      )
      if (proof.observe(current)) return current
      val remainingAfterProbeMillis = deadlineMillis - SystemClock.elapsedRealtime()
      if (remainingAfterProbeMillis > 0L) {
        delay(minOf(CONTROL_CODE_FAST_POLL_MILLIS, remainingAfterProbeMillis))
      }
    }
    return null
  }

  private fun controlCodeVisualBoundsToDevice(
    bounds: TicketControlCodeVisualBounds,
    probeWidth: Int = TicketControlCodeVisualClassifier.SAMPLE_WIDTH,
    probeHeight: Int = TicketControlCodeVisualClassifier.SAMPLE_HEIGHT
  ): TicketViviGraphicBounds {
    val displayWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
    val displayHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1)
    return TicketCaptureGeometry.mapProbeBoundsToDevice(
      bounds = TicketVisualProbeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
      probeWidth = probeWidth,
      probeHeight = probeHeight,
      sourceWidth = displayWidth,
      sourceHeight = displayHeight
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
    val restoredDetail = awaitStableTicketVisualActionObservation(
      "control_code_cleanup_original_detail",
      TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS
    )
    val restoredDetailMismatch = ticketControlCodeRestoredOriginalDetailMismatch(
      restoredDetail,
      activeControlCodeRawDetailAnchor,
      activeControlCodeRawDetailState
    )
    if (restoredDetailMismatch != null) {
      recordTicketEvent(
        "control_code_fast_cleanup_original_detail_unproved",
        "state=${restoredDetail?.state?.wireName ?: "unknown"} dimension=$restoredDetailMismatch"
      )
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
    recordTicketEvent(
      "control_code_fast_cleanup_close_dispatched",
      "action=${action.reason} x=${action.x} y=${action.y} bounds=${action.bounds ?: "geometry"}"
    )
    val tap = measureInputPhase(phases, commandReason) {
      runFastOneShotControlSurfaceCloseInput(
        closeCommand,
        commandReason
      )
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
        timeoutMillis = minOf(CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS, remainingMillis)
      )
      if (visualProbe == null) {
        recordTicketEvent(
          "control_code_fast_cleanup_visual_retry",
          "reason=$reason probe=$visualProbeId result=timeout"
        )
        continue
      }
      // This compact lane proves only that the result strip/popup is gone for two fresh frames.
      // Ordinary ViVi Aztec payloads rotate, so their full code signature must never gate return
      // to the ticket. Terminal cleanup authority follows below through two agreeing action probes
      // of the exact pre-submit state, static detail anchor, and ViVi control geometry.
      val proofResult = visualProbe.result
      val state = when (proofResult) {
        TicketControlCodeVisualClassifier.RAW_TICKET -> TicketViviRecoveryState.TICKET_DETAIL
        TicketControlCodeVisualClassifier.TICKET_LIST_WITH_REGISTRATION_BUTTON ->
          TicketViviRecoveryState.TICKET_LIST_WITH_CARD
        TicketControlCodeVisualClassifier.GENERATED -> TicketViviRecoveryState.CONTROL_CODE_RESULT
        TicketControlCodeVisualClassifier.CONTROL_POPUP -> TicketViviRecoveryState.CONTROL_CODE_POPUP
        else -> TicketViviRecoveryState.UNKNOWN_VIVI
      }
      val rawTicketConfirmed = visualProof.observe(proofResult)
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

  private fun parseControlCodeServerSentAtMillis(value: String): Long? {
    val cleanValue = value.trim()
    if (cleanValue.isBlank()) {
      return null
    }
    return runCatching { Instant.parse(cleanValue).toEpochMilli() }.getOrNull()
  }

  /**
   * Publishes the small set of request-relative timings that explain the user-visible path.
   * These are ordinary expiring Ticket trace events; the request table and public protocol stay
   * unchanged. A result-ready row is emitted before the browser acknowledgement, then a finished
   * row carries the acknowledgement and cleanup checkpoints.
   */
  private fun recordControlCodeTimingSummary(
    stage: String,
    phases: Map<String, Long>,
    databaseToPhoneMillis: Long?,
    databaseSubmissionEpochMillis: Long?,
    totalDurationMillis: Long? = null
  ) {
    val snapshot = synchronized(phases) { phases.toMap() }
    val fields = linkedMapOf<String, Long>()
    databaseToPhoneMillis?.let { fields["database_to_phone_ms"] = it.coerceAtLeast(0L) }

    fun phase(name: String): Long? = snapshot[name]?.coerceAtLeast(0L)
    fun add(name: String, value: Long?) {
      if (value != null) fields[name] = value.coerceAtLeast(0L)
    }

    add("phone_to_ticket_detail_ready_ms", phase("request_ticket_detail_ready"))
    add("phone_to_gate_ms", phase("request_gate_passed"))
    add("phone_to_ok_tap_ms", phase("ok_tapped"))
    add("phone_to_input_typed_ms", phase("digits_typed"))
    val popupReady = phase("popup_ready")
    val firstDigit = phase("first_digit_entry")
    add("phone_to_first_tap_ms", phase("first_phone_tap"))
    add(
      "popup_open_ms",
      phase("control_code_popup_after_button_1") ?: phase("control_code_popup_after_button_2")
    )
    add("phone_to_popup_ready_ms", popupReady)
    add("phone_to_first_input_ms", firstDigit)
    add("popup_to_first_input_ms", popupReady?.let { ready -> firstDigit?.minus(ready) })
    add("phone_to_result_ms", phase("result_first_visible"))
    add("phone_to_marker_ready_ms", phase("result_marker_frame_ready"))
    add("phone_to_browser_ack_ms", phase("browser_capture_ack_received"))
    add("phone_to_cleanup_ms", phase("cleanup_finished"))
    add("total_ms", totalDurationMillis ?: phase("result_marker_frame_ready"))

    fun addDatabaseRelative(name: String, phoneRelativeValue: Long?) {
      add(name, databaseToPhoneMillis?.let { receipt -> phoneRelativeValue?.plus(receipt) })
    }
    addDatabaseRelative("database_to_first_tap_ms", phase("first_phone_tap"))
    addDatabaseRelative("database_to_popup_ready_ms", popupReady)
    addDatabaseRelative("database_to_browser_ack_ms", phase("browser_capture_ack_received"))
    databaseSubmissionEpochMillis?.let { submittedAtMillis ->
      val now = System.currentTimeMillis()
      add("database_to_result_ms", (now - submittedAtMillis).coerceAtLeast(0L).takeIf { stage == "result_ready" })
      add("database_to_finished_ms", (now - submittedAtMillis).coerceAtLeast(0L).takeIf { stage == "finished" })
    }

    if (fields.isEmpty()) {
      return
    }
    recordTicketEvent(
      "control_code_timing_$stage",
      fields.entries.joinToString(" ") { (name, value) -> "$name=$value" }
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

  /**
   * Every ViVi input in an admitted control-code request crosses the same synchronous physical-
   * touch and exact-zero boundary. Once this returns false, the lease remains terminally inactive,
   * so retry/recovery branches can observe but cannot dispatch another phone input.
   */
  private suspend fun beginControlCodePanelDarkMutation(reason: String): Boolean {
    val lease = activeTicketActionPanelDarkLease ?: return true
    if (!lease.snapshot().ownerActionId.startsWith("control_code:")) return true
    if (!lease.beforeMutationAllowed()) {
      recordInputGateDecision(false, "control_code_panel_dark_preempted:$reason")
      recordTicketEvent(
        "control_code_panel_dark_mutation_blocked",
        "reason=$reason release=${lease.snapshot().releaseReason}"
      )
      return false
    }
    // The root call can be accepted even when its result is lost. Mark the attempt before crossing
    // that boundary so later touch/zero loss is always terminal and never eligible for replay.
    lease.markMutationMayHaveDispatched()
    return true
  }

  private fun controlCodePanelDarkMutationBlockedResult(reason: String): RootResult = RootResult(
    exitCode = 46,
    stdout = "",
    stderr = "control-code panel-dark mutation gate blocked",
    command = "panel_dark_gate:$reason",
    durationMs = 0L
  )

  private suspend fun runFastNonTouchInput(
    command: String,
    reason: String,
    postMillis: Long = NON_TOUCH_PANEL_SLEEP_CLAMP_POST_MILLIS,
    timeout: Duration = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds
  ): RootResult {
    if (!beginControlCodePanelDarkMutation(reason)) {
      return controlCodePanelDarkMutationBlockedResult(reason)
    }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    val commandTimeout = (timeout - postMillis.milliseconds).coerceAtLeast(250.milliseconds)
    val result = inputRootExecutor.runScript(
      wrapNonTouchPanelSleepClamp(
        command,
        postMillis = postMillis,
        commandTimeout = commandTimeout
      ),
      timeout
    ).also { recordInputCommandResult(reason, it) }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    return result
  }

  private suspend fun runSensitiveFastNonTouchScript(
    command: String,
    reason: String,
    timeout: Duration
  ): RootResult {
    if (!beginControlCodePanelDarkMutation(reason)) {
      return controlCodePanelDarkMutationBlockedResult(reason)
    }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      val rawResult = supervisorScope {
        val rootCommand = async {
          inputRootExecutor.runScript(
            wrapNonTouchPanelSleepClamp(
              command,
              postMillis = CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS,
              commandTimeout = timeout
            ),
            timeout
          )
        }
        var preempted = false
        while (!rootCommand.isCompleted) {
          val touch = PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
          val lease = activeTicketActionPanelDarkLease
          val leaseState = lease?.snapshot()
          if (!touch.available || touch.active ||
            leaseState?.physicalTouchPreempted == true ||
            !leaseState?.failure.isNullOrBlank()
          ) {
            lease?.beforeMutationAllowed()
            rootCommand.cancel(
              CancellationException("control-code input preempted by physical-touch authority")
            )
            runCatching { rootCommand.await() }
            preempted = true
            break
          }
          delay(CONTROL_CODE_SENSITIVE_INPUT_TOUCH_POLL_MILLIS)
        }
        if (preempted) {
          controlCodePanelDarkMutationBlockedResult("physical_touch_during:$reason")
        } else {
          rootCommand.await()
        }
      }
      rawResult.copy(command = "[REDACTED]", stdout = "", stderr = "")
        .also { recordInputCommandResult(reason, it) }
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  private suspend fun runFastOneShotControlSurfaceCloseInput(
    command: String,
    reason: String
  ): RootResult {
    if (!beginControlCodePanelDarkMutation(reason)) {
      return controlCodePanelDarkMutationBlockedResult(reason)
    }
    PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason")
    return try {
      val timeout = CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS.milliseconds
      controlSurfaceCloseRootExecutor.run(command, timeout)
        .also { recordInputCommandResult(reason, it) }
    } finally {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete")
    }
  }

  private suspend fun runFastRecoveryInput(
    command: String,
    reason: String,
    timeout: Duration = TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS.milliseconds,
    zeroTailPanelClamp: Boolean = false
  ): RootResult {
    if (!beginControlCodePanelDarkMutation(reason)) {
      return controlCodePanelDarkMutationBlockedResult(reason)
    }
    val suppressionTailMillis = if (zeroTailPanelClamp) 0L else NON_TOUCH_INPUT_SUPPRESSION_MILLIS
    if (zeroTailPanelClamp) {
      PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction("ticket:$reason")
    } else {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason", suppressionTailMillis)
    }
    return try {
      if (zeroTailPanelClamp) {
        recoveryInputRootExecutor.runScript(
          wrapNonTouchPanelSleepClamp(command, postMillis = 0L, commandTimeout = timeout),
          timeout
        )
      } else {
        recoveryInputRootExecutor.run(command, timeout)
      }
        .also { recordInputCommandResult(reason, it) }
    } finally {
      if (zeroTailPanelClamp) {
        PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction("ticket:$reason:complete")
      } else {
        PhoneAutomationServiceBridge.markNonTouchInput("ticket:$reason:complete", suppressionTailMillis)
      }
    }
  }

  private suspend fun runFastNonTouchWakeScript(command: String, reason: String, timeout: Duration): RootResult {
    if (!beginControlCodePanelDarkMutation(reason)) {
      return controlCodePanelDarkMutationBlockedResult(reason)
    }
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
    if (!beginControlCodePanelDarkMutation(reason)) {
      return controlCodePanelDarkMutationBlockedResult(reason)
    }
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
    private const val SERVICE_DESTROY_JOIN_TIMEOUT_MILLIS = 12_000L
    const val SERVER_VERSION = "ticket-stream-2026-09-04-proof-stream-idle-cleanup-v340"
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
    private const val TICKET_PIXEL_STATE_TICKET_LIST = "ticket_list"
    private const val TICKET_PIXEL_STATE_CONTROL_POPUP = "control_popup"
    private const val TICKET_PIXEL_STATE_GENERATED_RESULT = "generated_result"
    private const val TICKET_PIXEL_STATE_RETURNING_RAW = "returning_raw"
    private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 1_250L
    private const val LIVE_FRAME_MAX_AGE_MILLIS = 2_000L
    private const val ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS = 5 * 60_000L
    private const val STREAM_STALE_ENGINE_RESTART_MILLIS = 3_000L
    private const val STREAM_WATCHDOG_POLL_MILLIS = 500L
    private const val STREAM_WATCHDOG_NO_ENCODER_RESTART_MILLIS = 3_000L
    private const val STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS = 3_000L
    private const val STREAM_WATCHDOG_STALE_FRAME_RESTART_MILLIS = 3_000L
    private const val STREAM_WATCHDOG_RECOVERY_COOLDOWN_MILLIS = 1_000L
    private const val SPACETIME_DESIRED_RECOVERY_COOLDOWN_MILLIS = 20_000L
    private const val SPACETIME_DESIRED_RECOVERY_STALE_BLOCK_MILLIS = 15_000L
    private const val HARDWARE_RELIABILITY_FAILURE_THRESHOLD = 3
    private const val POST_CLEANUP_FRESH_FRAME_TIMEOUT_MILLIS = 3_000L
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
    private const val VIDEO_CLIENT_MAX_FRAME_BYTES = 5 * 1024 * 1024
    private const val VIDEO_CLIENT_SLOW_CLOSE_MILLIS = 250L
    private const val TICKET_WAKE_BUDGET_MILLIS = 3_000L
    private const val TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS = 5_000L
    private const val TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS = 6_000L
    private const val TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_POLL_MILLIS = 40L
    private const val TICKET_FAST_PUBLIC_OPEN_VISUAL_PROOF_SAMPLE_GAP_MILLIS = 80L
    private const val TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS = 3_000L
    private const val TICKET_DETAIL_VISUAL_PROOF_SAMPLE_COUNT = 2
    private const val TICKET_SLIDER_PROOF_TIMEOUT_MILLIS = 3_000L
    // Production rooted UiAutomator reads settle in roughly 2.5-3s after ViVi's Flutter
    // activation transition. Keep slider-geometry probes fast, but give activated semantics a
    // dedicated bounded read that can actually return the nonblank hierarchy.
    private const val TICKET_ACTIVATED_HIERARCHY_PROOF_TIMEOUT_MILLIS = 5_000L
    private const val TICKET_SLIDER_PROOF_POLL_MILLIS = 80L
    private const val TICKET_SLIDER_TRANSIENT_PROOF_MAX_AGE_MILLIS = 15_000L
    private const val MAX_INSTANT_SLIDER_RESULTS = 8
    private const val NON_TOUCH_INPUT_SUPPRESSION_MILLIS = 4_000L
    private const val TICKET_SLIDER_ACCESSIBILITY_RECONNECT_TIMEOUT_MILLIS = 5_000L
    private const val TICKET_SLIDER_GESTURE_TIMEOUT_MILLIS = 1_500L
    private const val TICKET_ACTION_V3_SAMPLE_WIDTH = 192
    private const val TICKET_ACTION_V3_SAMPLE_HEIGHT = 288
    private const val TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS = 5_000L
    private const val TICKET_ACTION_V3_FINAL_CONVERGENCE_MILLIS = 8_000L
    private const val TICKET_ACTION_V3_CAPTURE_RECOVERY_TIMEOUT_MILLIS = 15_000L
    private const val TICKET_ACTION_V3_FRAME_WATERMARK_TIMEOUT_MILLIS = 15_000L
    private const val TICKET_ACTION_V3_ACTIVATION_PROOF_TIMEOUT_MILLIS = 16_000L
    private const val TICKET_ACTION_V3_PROBE_WAIT_MILLIS = 2_500L
    private const val TICKET_ACTION_V3_PROBE_GAP_MILLIS = 90L
    private const val TICKET_ACTION_V3_PANEL_DARK_LEASE_MILLIS = 90_000L
    private const val TICKET_ACTION_V3_MAX_NAVIGATION_MUTATIONS = 4
    private const val TICKET_ACTION_V3_SWITCH_PREFERENCES = "ticket_action_v3_switch"
    private const val TICKET_ACTION_V3_JOURNAL_PREFERENCES = "ticket_action_v3_journal"
    private const val TICKET_SLIDER_COMPLETION_DURATION_MILLIS = 800L
    // ViVi commits the registration before its Flutter accessibility tree finishes replacing
    // the slider with the activated status/countdown. A rooted hierarchy read on the production
    // Pixel can itself take about three seconds after that transition. Keep this bounded, but
    // leave enough time for two complete fresh observations plus visual/fallback proof. The
    // caller still requires both explicit activated-detail semantics and fresh rooted H.264.
    private const val TICKET_SLIDER_ACTIVATED_PROOF_TIMEOUT_MILLIS = 16_000L
    private const val TICKET_ACTIVATED_ROOTED_FRAME_FALLBACK_TIMEOUT_MILLIS = 1_500L
    private const val TICKET_ACTIVATED_ROOTED_FRAME_MAX_AGE_MILLIS = 1_250L
    private const val TICKET_SLIDER_COMPLETION_PROGRESS = 5_000
    private const val TICKET_WAKE_RECOVERY_BUDGET_MILLIS = 60_000L
    private const val TICKET_WAKE_RECOVERY_MAX_ACTIONS = 4
    private const val TICKET_WAKE_RECOVERY_MIN_ACTION_TIMEOUT_MILLIS = 4_000L
    private const val TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS = 2_000L
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
    private const val TICKET_WAKE_MEMORY_TICKET_DETAIL_MAX_AGE_MILLIS = 10 * 60_000L
    private const val TICKET_WAKE_GUARD_GRACE_MILLIS = 1_000L
    private const val TICKET_WAKE_FAST_POLL_MILLIS = 100L
    private const val NON_TOUCH_SCRIPT_REASSERT_INTERVAL_MILLIS = 250L
    private const val NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS = 120_000L
  private const val NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS = 250L
  private const val NON_TOUCH_PANEL_SLEEP_CLAMP_INTERVAL_MILLIS = 5L
  private const val TICKET_WAKE_PANEL_SLEEP_CLAMP_POST_MILLIS = 250L
    // Keep the panel clamp active while each command runs, then perform one final
    // safety write. Repeating slow sysfs brightness writes after every fast tap/type
    // can turn this nominal post-delay into multiple seconds on the Pixel panel.
    private const val CONTROL_CODE_FAST_PANEL_SLEEP_CLAMP_POST_MILLIS = 0L
    private const val CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS = 2_000L
    private const val CONTROL_CODE_KEYBOARD_CLAMP_COMMAND_TIMEOUT_MILLIS = 2_500L
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
    private const val TICKET_SCREEN_WAKE_HOLD_MILLIS = 30_000L
    private const val TICKET_SCREEN_WAKE_REQUEST_COOLDOWN_MILLIS = 2_000L
    private const val VIVI_REAUTH_JOURNAL_PREFERENCES = "ticket_vivi_reauth_journal_v1"
    private const val VIVI_REAUTH_CLEAR_TIMEOUT_MILLIS = 5_000L
    private const val VIVI_REAUTH_FORCE_STOP_TIMEOUT_MILLIS = 5_000L
    private const val VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS = 12_000L
    private const val VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS = 3_000L
    private const val VIVI_REAUTH_FIELD_SETTLE_MILLIS = 150L
    private const val VIVI_REAUTH_VERIFY_TIMEOUT_MILLIS = 15_000L
    private const val VIVI_REAUTH_VERIFY_SLICE_MILLIS = 2_500L
    private const val VIVI_REAUTH_VERIFY_POLL_MILLIS = 200L
    private const val VIVI_REAUTH_LOGOUT_POLL_MILLIS = 80L
    private const val VIVI_REAUTH_TICKET_RESTORE_MAX_MUTATIONS = 3
    private const val VIVI_REAUTH_TICKET_REDETECT_MAX_MUTATIONS = 5
    private const val CONTROL_CODE_POPUP_READY_CACHE_MILLIS = 2_000L
    private const val CONTROL_CODE_FAST_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_RAW_TICKET_ROOT_CONFIRM_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_RECENT_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_CODE_RAW_TICKET_VISUAL_REJECT_LOG_COUNT = 2L
    private const val CONTROL_CODE_SUBMIT_RETRY_MIN_POPUP_SAMPLES = 2L
    private const val CONTROL_CODE_SUBMIT_RETRY_MIN_AGE_MILLIS = 300L
    private const val CONTROL_CODE_SUBMIT_RETRY_POST_POPUP_LIMIT = 2L
    private const val CONTROL_CODE_FAST_RESULT_FINAL_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_FAST_INTERACTION_RETRY_COUNT = 4
    private const val CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS = 18_000L
    private const val CONTROL_CODE_VISUAL_SIGNATURE_TTL_MILLIS = 60_000L
    private const val CONTROL_CODE_VISUAL_CHECKPOINT_PREFERENCES =
      "ticket_control_code_visual_checkpoint_v1"
    private const val CONTROL_CODE_VISUAL_CHECKPOINT_PENDING_KEY = "cleanup_pending"
    private const val CONTROL_CODE_BROWSER_CAPTURE_ACK_POLL_MILLIS = 40L
    // Updated ViVi builds can publish the generated-result row and finish the
    // browser paint handshake after the phone marker, while still staying on
    // the normal user-facing path. Keep the acknowledgement bounded, but give
    // that public handoff enough time to arrive before using fallback cleanup.
    private const val CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS = 20_000L
    private const val CONTROL_CODE_RECOVERY_QUEUE_POLL_MILLIS = 250L
    private const val CONTROL_CODE_RESELECT_FRESH_TICKET_MAX_AGE_MILLIS = 5_000L
    private const val LATEST_TICKET_RESELECT_RELAUNCH_DELAY_MILLIS = 300L
    private const val LATEST_TICKET_RESELECT_RECOVERY_BUDGET_MILLIS = 120_000L
    private const val LATEST_TICKET_RESELECT_MAX_RECOVERY_ACTIONS = 6
    private const val LATEST_TICKET_RESELECT_REPEAT_ACTION_COOLDOWN_MILLIS = 30_000L
    private const val LATEST_TICKET_RESELECT_TICKET_CARD_ACTION_GRACE_MILLIS = 60_000L
    private const val LATEST_TICKET_RESELECT_IN_APP_ACTION_GRACE_MILLIS = 2_500L
    private const val LATEST_TICKET_RESELECT_IN_APP_RESET_BUDGET_MILLIS = 8_000L
    private const val LATEST_TICKET_RESELECT_IN_APP_ACTION_SETTLE_MILLIS = 80L
    private const val LATEST_TICKET_RESELECT_IN_APP_ACTION_COOLDOWN_MILLIS = 280L
    private const val LATEST_TICKET_RESELECT_SETTLE_TIMEOUT_MILLIS = 4_000L
    private const val LATEST_TICKET_RESELECT_PROOF_HOLD_MILLIS =
      LATEST_TICKET_RESELECT_SETTLE_TIMEOUT_MILLIS + 1_500L
    private const val LATEST_TICKET_RESELECT_PROOF_NUDGE_MILLIS = 1_000L
    private const val LATEST_TICKET_RESELECT_PROOF_IDLE_STOP_GRACE_MILLIS = 2_000L
    private const val LATEST_TICKET_RESELECT_ACTIVE_WINDOW_MILLIS =
      LATEST_TICKET_RESELECT_RECOVERY_BUDGET_MILLIS +
        LATEST_TICKET_RESELECT_TICKET_CARD_ACTION_GRACE_MILLIS +
        LATEST_TICKET_RESELECT_SETTLE_TIMEOUT_MILLIS +
        30_000L
    private const val CONTROL_CODE_POST_SUBMIT_FRAME_SETTLE_MILLIS = 0L
    private const val CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_VISUAL_STATE_POLL_MILLIS = 40L
    private const val CONTROL_CODE_VISUAL_STATE_RETRY_MILLIS = 50L
    // The rooted helper can spend more than 250 ms capturing and classifying a frame while the
    // stream remains healthy. Do not overwrite that exact probe id before its reply has had a
    // bounded multi-frame opportunity to arrive. The two-frame generated signature proof still
    // remains mandatory; this only enlarges the transport wait inside one final proof window.
    private const val CONTROL_CODE_GENERATED_CLOSE_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_GENERATED_CLOSE_PROOF_TIMEOUT_MILLIS = 3_200L
    private const val CONTROL_CODE_BROWSER_MARKER_PROBE_ATTEMPTS = 2
    private const val CONTROL_CODE_BROWSER_MARKER_PROBE_WAIT_MILLIS = 1_800L
    private const val CONTROL_CODE_GENERATED_RESULT_MARKER_DELAY_MILLIS = 200L
    private const val CONTROL_CODE_SUBMIT_VISUAL_REQUIRED_SAMPLES = 2
    // Focus/caret animation can outlive four rooted probe frames even though the field settles
    // moments later. Extra samples are observation-only: value still needs two agreeing frames,
    // and retyping remains gated by two freshly proved static-blank frames.
    private const val CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES = 8
    private const val CONTROL_CODE_SUBMIT_VISUAL_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_SUBMIT_VISUAL_SAMPLE_GAP_MILLIS = 250L
    private const val CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS = 350L
    private const val CONTROL_CODE_ROOT_TRANSACTION_TIMEOUT_MILLIS = 4_000L
    private const val CONTROL_CODE_SENSITIVE_INPUT_TOUCH_POLL_MILLIS = 5L
    private const val CONTROL_CODE_ROOT_SUBMIT_TIMEOUT_MILLIS = 2_500L
    private const val CONTROL_CODE_FAST_POLL_MILLIS = 90L
    // A rooted hierarchy read can take about three seconds after an idle
    // period. Keep the healthy path unchanged, but leave enough budget for
    // one repeat of that same proof when the first read is inconclusive.
    private const val CONTROL_CODE_INLINE_PREPARATION_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_CODE_IMMEDIATE_TICKET_DETAIL_MEMORY_MAX_AGE_MILLIS = 15_000L
    private const val RECENT_CONTROL_CODE_RESULT_CACHE_SIZE = 6
    private const val CONTROL_CODE_RESULT_CACHE_TTL_MILLIS = 90_000L
    private const val CONTROL_CODE_RESULT_IMAGE_CROP_PADDING = 32
    private const val CONTROL_CODE_RESULT_IMAGE_MIN_CROP_SIZE = 160
    private const val PNG_BASE64_BEGIN = "PNG_BASE64_BEGIN"
    private const val PNG_BASE64_END = "PNG_BASE64_END"
    private const val SNAP_TARGET_CONTROL_CODE_BUTTON = "control_code_button"
    private val CONTROL_CODE_REQUEST_DIGITS_REGEX = Regex("""^[0-9]{2,8}$""")
    private const val CONTROL_CODE_SOFT_CHECK_TIMEOUT_MILLIS = 10_000L
    // Each exact cleanup probe gets one full 1 FPS frame opportunity. The surrounding proof keeps
    // at least three seconds for two distinct consecutive RAW_TICKET samples.
    private const val CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 3_200L
  private const val CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS = 75L
  private const val CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT = 2
    private const val CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS = 200L
    private const val CONTROL_CODE_GENERATED_HEAL_MAX_CLOSE_ATTEMPTS = 2
    private const val TICKET_HIERARCHY_DEFAULT_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_EXIT_ROOT_DUMP_TIMEOUT_MILLIS = 8_000L
    private const val CONTROL_EXIT_RECENT_SURFACE_MEMORY_MILLIS = 12_000L
    private const val CONTROL_CODE_TRANSITION_GRACE_MILLIS = 3_000L
    private const val REMOTE_TAP_FOREGROUND_SETTLE_MILLIS = 350L
    private const val CACHED_FOREGROUND_MAX_AGE_MILLIS = 2_000L
    private const val FOREGROUND_RECOVERY_CONFIRMATION_COUNT = 2
    private const val FOREGROUND_RECOVERY_COOLDOWN_MILLIS = 6_000L
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
