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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
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
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationPreferencesStore
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationServiceBridge
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationTicketInputFence
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviAuthSurface
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviLoginField
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviLogoutClickTarget
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationViviLogoutSurface
import lv.jolkins.pixelorchestrator.app.phoneautomation.TicketSliderGestureDispatchResult
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

private data class TicketCachedKeyFrame(
  val epoch: Long,
  val sequence: Long,
  val envelope: ByteArray,
  val cachedAtMillis: Long,
  val captureStartUs: Long
)

private data class TicketSessionStartSafetyPreflight(
  val portrait: PhonePortraitLock.EnsureResult,
  val secureCapture: TicketSecureWindowCaptureBypassEnsureResult,
  val armHandle: TicketHardwareH264ArmHandle? = null
)

private data class TicketVideoConfigSnapshot(
  val epoch: Long,
  val width: Int,
  val height: Int,
  val message: String
)

class TicketStreamService : Service() {
  private data class ControlCodeBrowserCaptureAck(
    val requestId: String,
    val ok: Boolean,
    val reason: String,
    val frameEpoch: Long,
    val frameSequence: Long,
    val receivedAtMillis: Long
  )

  private class ControlCodeCaptureWait(val requestId: String, var marker: Pair<Long, Long>) {
    val offeredFrames = mutableSetOf(marker)
    val acknowledgement = CompletableDeferred<ControlCodeBrowserCaptureAck>()
  }

  private data class FastControlCodePopupTransaction(
    val input: TicketViviPageAction,
    val submit: TicketViviPageAction
  )

  private data class FastControlCodeDelivery(
    val ok: Boolean,
    val reason: String,
    val streamEpoch: Long = 0L,
    val minFrameSequence: Long = 0L,
    val resultProof: String = "",
    val resultProofAtMillis: Long = 0L
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
        onEvent(
          "keyboard_clamp_restore_failed",
          "request=$requestId reason=$reason authority=accessibility_soft_keyboard " +
            "error=${lastError.ifBlank { "unavailable" }}"
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
        true
      }
      if (restored) {
        suppressionMayBeOwned = false
        acquired = false
        released = true
      }
      restored
    }
  }

  private data class TicketActivationDispatchPreparation(
    val observation: TicketVisualActionObservation,
    val gestureBounds: TicketViviGraphicBounds,
    val inputFence: PhoneAutomationTicketInputFence,
    val captureStreamEpoch: Long,
    val captureRestartCount: Long,
    val actionMutationGeneration: Long
  )

  private data class TicketActivationPreparationResult(
    val prepared: TicketActivationDispatchPreparation? = null,
    val reason: String = "ticket_action_visual_unproved"
  )

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
  private val secureCaptureStartupReconciled = CompletableDeferred<Boolean>()
  @Volatile private var secureCaptureStartupReady: Boolean = false
  @Volatile private var serviceLifecycleStopping: Boolean = false
  private val streamStartAdmission = TicketStreamStartAdmission()
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val rootExecutor = TicketRootCommandWorker()
  private val secureCaptureRootExecutor = TicketRootCommandWorker()
  private val secureWindowCaptureBypassOwner = TicketSecureWindowCaptureBypassOwner(
    // Keep capture-settings work independent of encoder process verification.
    // Reuse the existing second worker instead of adding another root transport.
    primaryRootExecutor = secureCaptureRootExecutor,
    fallbackRootExecutor = rootExecutor,
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
  internal val ticketSpacetimeResultNotifications = Channel<Unit>(Channel.CONFLATED)
  private val ticketSpacetimePhoneOutbox = TicketSpacetimePhoneOutbox(
    criticalTtlMillis = TICKET_SPACETIME_CRITICAL_MESSAGE_TTL_MILLIS,
    nowMillis = SystemClock::elapsedRealtime
  )
  private val ticketActivationCheckpointStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    TicketActivationCheckpointStore(applicationContext)
  }
  private val videoConnectionOwner = TicketVideoConnectionOwner()
  private val serverMutex = Mutex()
  private val videoClients = Collections.synchronizedSet(mutableSetOf<TicketWebSocket>())
  private val captureDemandSessions = Collections.synchronizedMap(
    mutableMapOf<TicketWebSocket, TicketCaptureDemandSession>()
  )
  private val videoSendStates = Collections.synchronizedMap(mutableMapOf<TicketWebSocket, TicketVideoDeliveryWriter>())
  private val encoderLock = Any()
  private val sessionMutex = Mutex()

  private val controlCodePhoneMutationLane = ControlCodePhoneMutationLane()
  @Volatile private var ticketVisualActionJobOwnershipActive: Boolean = false
  @Volatile private var ticketVisualActionCaptureLeaseActive: Boolean = false
  @Volatile private var viviReauthCaptureLeaseActive: Boolean = false
  @Volatile private var ticketColdRestartBlocked: Boolean = false
  @Volatile private var ticketActionPanelDarkLeaseSnapshot = TicketActionPanelDarkLeaseSnapshot()
  private val ticketActionPanelDarkLeaseFailures = AtomicLong(0L)
  @Volatile private var activeTicketActionPanelDarkLease: TicketActionPanelDarkLease? = null
  @Volatile private var viviReauthSnapshot = TicketViviReauthSnapshot()
  @Volatile private var viviReauthCompletedAtMillis: Long = 0L
  private val controlCodeBrowserCaptureLock = Object()
  private val running = AtomicBoolean(false)
  internal val phoneControlState = TicketPhoneControlState()
  private val pendingRootHardwareH264KeyFrame = TicketPendingKeyFrameRequest()
  private val rootHardwareH264CaptureEngine = TicketRootHardwareH264CaptureEngine(
    scope = serviceScope,
    rootExecutor = rootExecutor,
    onFrame = ::handleRootHardwareH264CaptureFrame,
    onCaptureUnavailable = {
      phoneControlState.invalidate("capture_unavailable", capturedThroughUs = SystemClock.elapsedRealtime() * 1_000L)
    },
    onObservation = { observation ->
      val touch = PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
      phoneControlState.observe(observation,
        !secureCaptureStartupReady || serviceLifecycleStopping ||
          ticketVisualActionJobOwnershipActive || viviReauthCaptureLeaseActive ||
          ticketSpacetimeControlCodeRequestActive() || controlCodeSignatureCleanupRequired ||
          activeControlCodeKeyboardClamp != null || activeTicketActionPanelDarkLease != null ||
          touch.active || !touch.available)
    },
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
  @Volatile private var activeControlCodeKeyboardClamp: RequestScopedKeyboardClampLease? = null
  @Volatile private var activeControlCodeRawDetailAnchor: String = ""
  @Volatile private var activeControlCodeRawDetailState: TicketVisualPhoneState = TicketVisualPhoneState.UNKNOWN
  @Volatile private var activeControlCodeVisualResultMode: TicketControlCodeVisualResultMode =
    TicketControlCodeVisualResultMode.NONE
  @Volatile private var activeControlCodeVisualSignatureExpiresAtMillis: Long = 0L
  @Volatile private var controlCodeSignatureCleanupRequired: Boolean = false
  private var rootH264BlankProbeJob: Job? = null
  private var ticketScreenWakeLock: PowerManager.WakeLock? = null
  private var ticketScreenWakeLockUsesTouchBrightnessOwner: Boolean? = null
  private val viviStateMemory = TicketViviStateMemory()
  private var streamWatchdogJob: Job? = null
  @Volatile private var viviForegroundGraceUntilMillis: Long = 0L
  @Volatile private var cachedForegroundViolationReason: String? = null
  @Volatile private var cachedForegroundCheckedAtMillis: Long = 0L
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
  @Volatile private var lastRootH264BlankProbeAtMillis: Long = 0L
  @Volatile private var lastRootH264VisibleProbePassedAtMillis: Long = 0L
  @Volatile private var lastRootH264BlankProbeResult: String = "not_run"
  @Volatile private var streamWatchdogStage: String = "idle"
  @Volatile private var controlCodeResultEncoderRefreshActive: Boolean = false
  @Volatile private var lastStreamWatchdogAction: String = "none"
  @Volatile private var lastStreamWatchdogReason: String? = null
  @Volatile private var lastStreamRecoveryResult: String = "none"
  @Volatile private var lastStreamRecoveryFailureReason: String? = null
  @Volatile private var lastStreamRecoveryAtMillis: Long = 0L
  @Volatile private var inputGateReason: String = "no_active_control"
  @Volatile private var controlCodeModeActive: Boolean = false
  @Volatile private var controlCodeTransitionGraceUntilMillis: Long = 0L
  private val inactivityStateLock = Any()
  @Volatile private var viewerInputGeneration: Long = 0L
  @Volatile private var lastViewerInputAtMillis: Long = SystemClock.elapsedRealtime()
  @Volatile private var lastSessionStopReason: String? = null
  @Volatile private var lastTicketScreenWakeAtMillis: Long = 0L
  @Volatile private var lastMessage: String = "Ticket server is starting"
  @Volatile private var lastEncoderStartAtMillis: Long = 0L
  @Volatile private var lastConfigSentAtMillis: Long = 0L
  @Volatile private var lastFrameSentAtMillis: Long = 0L
  @Volatile private var encodedFrames: Long = 0L
  @Volatile private var sentFrames: Long = 0L
  @Volatile private var droppedVideoFrames: Long = 0L
  private val clientGenerationCounter = AtomicLong(0L)
  @Volatile private var streamEpoch: Long = 0L
  @Volatile private var frameSequence: Long = 0L
  @Volatile private var latestKeyFrame: TicketCachedKeyFrame? = null
  @Volatile private var lastControlCodeRequestId: String? = null
  @Volatile private var lastControlCodeRequestStatus: String = "idle"
  @Volatile private var lastControlCodeRequestReason: String? = null
  @Volatile private var lastControlCodeRequestDurationMillis: Long? = null
  @Volatile private var lastControlCodeRequestCompletedAtMillis: Long = 0L
  private val ticketActionV3Lock = Any()
  @Volatile private var ticketActionV3Snapshot = TicketVisualActionSnapshot()
  @Volatile private var ticketActionV3Generation: Long = 0L
  /** Advances before every mutating V3 action so read-only visual proofs cannot cross a mutation. */
  @Volatile private var ticketActionV3MutationGeneration: Long = 0L
  private var ticketActionTiming: TicketActionTiming? = null
  @Volatile private var ticketVisualSwitchAnchors = TicketVisualSwitchAnchors()
  @Volatile private var ticketVisualSwitchAnchorsLoaded = false
  @Volatile private var controlCodeCaptureWait: ControlCodeCaptureWait? = null
  @Volatile private var lastControlCodeBrowserCaptureReason: String? = null
  @Volatile private var lastControlCodeBrowserCaptureCompletedAtMillis: Long = 0L
  @Volatile private var duplicateControlCodeResultCount: Long = 0L
  @Volatile private var lastDuplicateControlCodeRequestId: String? = null
  @Volatile private var lastDuplicateControlCodeResultAtMillis: Long = 0L

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
        rootHardwareH264CaptureEngine.cleanupStaleProcesses()
        rootHardwareH264CaptureEngine.probe()
      }
    }
  }

  private suspend fun awaitRootHardwareH264StartupReadiness(): TicketHardwareH264Health {
    val existingJob = synchronized(rootHardwareH264StartupReadinessLock) {
      rootHardwareH264StartupReadinessJob
    }
    if (existingJob == null) {
      startRootHardwareH264StartupReadiness()
    }
    val waitJob = synchronized(rootHardwareH264StartupReadinessLock) {
      rootHardwareH264StartupReadinessJob
    }
    waitJob?.join()
    var health = rootHardwareH264CaptureEngine.snapshot()
    if (!health.available) {
      // A failed service-start probe is not a readiness result. Retry through the
      // same serialized job so a viewer arriving during the first probe cannot
      // launch a competing cleanup/probe pair, while a later start can recover
      // from a transient root/APK failure.
      startRootHardwareH264StartupReadiness()
      val retryJob = synchronized(rootHardwareH264StartupReadinessLock) {
        rootHardwareH264StartupReadinessJob
      }
      if (retryJob !== waitJob) {
        retryJob?.join()
      }
      health = rootHardwareH264CaptureEngine.snapshot()
    }
    return health
  }

  override fun onCreate() {
    super.onCreate()
    serviceScope.launch {
      var touchBeginCount = -1L
      var available = false
      PhoneAutomationServiceBridge.rootPhysicalTouchStates.collect { touch ->
        if (touch.touchBeginCount != touchBeginCount || (available && !touch.available)) {
          phoneControlState.invalidate("physical_touch_state_changed", busy = touch.active || !touch.available,
            capturedThroughUs = SystemClock.elapsedRealtime() * 1_000L)
        }
        touchBeginCount = touch.touchBeginCount
        available = touch.available
      }
    }
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
      if (!PhonePortraitLock.ensureVerified(inputRootExecutor)) {
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
    releaseTicketScreenAwake()
    stopLocalServer()
    inputRootExecutor.close()
    ticketActionPanelDarkRootExecutor.close()
    ticketActionPanelDarkVerifyRootExecutor.close()
    wakeRootExecutor.close()
    foregroundRootExecutor.close()
    secureCaptureRootExecutor.close()
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
        path == "/api/v1/stream"
      ) {
        acceptWebSocket(socket, input, output, headers)
        return@runCatching
      }
      when {
        method == "GET" && (path == "/" || path == "/api/v1/bootstrap" || path == "/api/v1/cache-cleanup") ->
          sendText(output, 410, "Pixel-local viewer retired; use the public Ticket service")
        method == "GET" && path == "/api/v1/health" -> sendJson(output, health())
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
    lateinit var client: TicketWebSocket
    val clientLifecycleLock = Any()
    val videoClientRegistered = AtomicBoolean(false)
    val generation = nextClientGeneration()
    client = TicketWebSocket(
      socket = socket,
      input = input,
      output = output,
      onText = { message ->
        handleVideoClientCommand(client, message, mediaCommandsAllowed = videoClientRegistered.get())
      },
      onClose = {
        synchronized(clientLifecycleLock) {
            if (videoClientRegistered.getAndSet(false)) {
              videoClients.remove(client)
              captureDemandSessions.remove(client)
              videoConnectionOwner.release(generation)
            }
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
      binaryFramesInitiallyAllowed = false
    )
    // Start transport-control handling immediately after the upgrade, including while another
    // socket still owns serialized phone preflight. This lets every replacement answer Ping and
    // clock probes without starting media work or weakening the relay's short dead-path timeout.
    val videoReadStarted = CompletableDeferred<Unit>()
    val videoReadJob = serviceScope.async {
      videoReadStarted.complete(Unit)
      client.readLoop()
    }
    videoReadStarted.await()
    extendStartupDisconnectGrace()
    var acceptedClientGeneration = true
    sessionMutex.withLock {
      synchronized(clientLifecycleLock) {
          if (!client.isOpen()) {
            acceptedClientGeneration = false
          } else {
            acceptedClientGeneration = videoConnectionOwner.bind(generation)
            if (acceptedClientGeneration) {
              closeDuplicateViewerClients()
              clientDisconnectStopJob?.cancel()
              clientDisconnectStopJob = null
              videoSendStates[client] = newVideoDeliveryWriter(client)
              captureDemandSessions[client] = TicketCaptureDemandSession()
              videoClients.add(client)
              videoClientRegistered.set(true)
              markViewerInput("client_connected")
            }
          }
        }
    }
    if (!acceptedClientGeneration) {
      client.close()
      videoReadJob.await()
      return
    }
    if (!startTicketSessionForVideoClientOpen(generation)) {
      client.close()
      videoReadJob.await()
      return
    }
    val currentAfterStart = sessionMutex.withLock {
      synchronized(clientLifecycleLock) {
        if (
          !videoClientRegistered.get() ||
          !client.isOpen() ||
          !videoClients.contains(client) ||
          !videoConnectionOwner.isCurrent(generation)
        ) {
          false
        } else {
          if (ticketSessionOpen()) {
            updateTicketSessionState(TICKET_SESSION_LIVE, "client_connected")
          }
          streamSize?.let { size -> sendConfigAndWarmStart(client, size) }
          ensureEncoderIfPossible()
          scheduleStreamWatchdog()
          true
        }
      }
    }
    if (!currentAfterStart) {
      client.close()
    }
    videoReadJob.await()
  }

  private suspend fun startTicketSessionForVideoClientOpen(generation: Long): Boolean {
    var superseded = false
    startTicketSession(lockedStartDecision = {
      superseded = !videoConnectionOwner.isCurrent(generation)
      when {
        superseded -> TicketSessionResponse(
          ok = true, state = ticketSessionState, message = "Video socket was superseded before startup"
        )
        streamActive -> TicketSessionResponse(ok = true, state = ticketSessionState, message = lastMessage)
        controlCodeRequestActive() -> TicketSessionResponse(
          ok = false, state = ticketSessionState, message = "Control-code work owns the phone"
        )
        else -> null
      }
    })
    return !superseded
  }

  private fun handleVideoClientCommand(
    client: TicketWebSocket,
    message: String,
    mediaCommandsAllowed: Boolean = true
  ) {
    val phoneReceiveUptimeMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
    val receivedAtUptimeMillis = phoneReceiveUptimeMicros / 1_000L
    val element = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return
    when (element["type"]?.jsonPrimitive?.contentOrNull) {
      "keyframe" -> if (mediaCommandsAllowed) {
        sendCachedKeyFrameOrRequest(client, element["reason"]?.jsonPrimitive?.contentOrNull ?: "video_client_request")
      }
      "capture_demand" -> {
        if (!mediaCommandsAllowed) return
        val request = TicketCaptureDemandProtocol.parseRequest(element) ?: return
        val session = captureDemandSessions[client] ?: return
        val currentEpoch = synchronized(encoderLock) {
          streamEpoch.takeIf {
            streamActive &&
              activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
              videoClients.contains(client)
          } ?: 0L
        }
        session.admit(
          request = request,
          currentStreamEpoch = currentEpoch,
          receivedAtUptimeMillis = receivedAtUptimeMillis,
          nowUptimeMillis = SystemClock.elapsedRealtime()
        ) { validUntilUptimeMillis ->
          synchronized(encoderLock) {
            if (
              !streamActive ||
              activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 ||
              streamEpoch != request.streamEpoch ||
              !videoClients.contains(client) ||
              SystemClock.elapsedRealtime() > validUntilUptimeMillis
            ) {
              false
            } else {
              rootHardwareH264CaptureEngine.requestOrdinaryCaptureDemand(validUntilUptimeMillis)
            }
          }
        }
      }
      "clock_probe" -> {
        val request = TicketClockProbeProtocol.parseRequest(element) ?: return
        client.sendTextAtWrite {
          val phoneSendUptimeMicros = SystemClock.elapsedRealtimeNanos() / 1_000L
          TicketClockProbeProtocol.encodeResult(
            request,
            phoneReceiveUptimeMicros,
            phoneSendUptimeMicros
          )
        }
      }
    }
  }

  internal suspend fun handleTicketSpacetimeCommand(command: TicketSpacetimeCommand): TicketSpacetimeCommandResult {
    val payload = runCatching { json.parseToJsonElement(command.payloadJson).jsonObject }.getOrNull()
    val reason = payload?.stringValue("reason")
      ?: payload?.stringValue("acceptedReason")
      ?: command.reason.ifBlank { command.commandType }
    return try {
      when (command.commandType) {
        "cold_stop" -> sessionMutex.withLock {
          ticketColdRestartBlocked = true
          if (controlCodeRequestActive() || ticketVisualActionCaptureLeaseActive ||
            viviReauthCaptureLeaseActive || ticketVisualActionJobOwnershipActive) {
            TicketSpacetimeCommandResult(false, "phone_work_in_progress", ticketSpacetimeStreamState())
          } else {
            val stopped = stopTicketSessionLocked("owner_cold_restart")
            val health = rootHardwareH264CaptureEngine.snapshot()
            val proved = stopped.ok && !streamActive && !health.active &&
              health.encoderProcessCount == 0 && health.staleCaptureProcessCount == 0 &&
              !secureWindowCaptureBypassOwner.snapshot().active &&
              secureWindowCaptureBypassOwner.currentLease() == null
            TicketSpacetimeCommandResult(proved,
              if (proved) "cold_capture_released" else "cold_shutdown_unproved", ticketSpacetimeStreamState())
          }
        }
        "start" -> {
          if (controlCodeRequestActive()) {
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
              requestId = payload?.stringValue("requestId").orEmpty(),
              digits = payload?.stringValue("digits").orEmpty(),
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
        else -> {
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
      requestTicketSpacetimeResultPublication()
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
    val panelLease = newTicketPanelDarkLease("vivi_reauth:${request.requestId}")
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
        PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction()
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
    val login = awaitStableTicketVisualActionObservation(
      reason = "vivi_reauth_login",
      timeoutMillis = VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS,
      // v3 alone admits two agreeing UNKNOWN primary states so its independent, exact four-tab
      // side channel can prove an otherwise unrecognized Tickets body. The route policy below
      // still rejects UNKNOWN without a tab and every explicit login or blocker state.
      allowUnknown = request.logoutInApp
    )
    if (!PhoneAutomationServiceBridge.awaitAccessibilityConnection(
        VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS
      )
    ) {
      return reauthSnapshot(request, "needs_attention", "complete", "login_fields_not_detected")
    }
    val loginSurface = PhoneAutomationServiceBridge.classifyViviAuthSurface(
      TicketScreenConfig.VIVI_PACKAGE
    )
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
    val observation = terminalObservation ?: awaitStableTicketVisualActionObservation(
      "vivi_reauth_signed_in_proof", TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS, currentOnly = true
    )
    if (observation == null || observation.state in setOf(
        TicketVisualPhoneState.UNKNOWN, TicketVisualPhoneState.LOGIN_REQUIRED, TicketVisualPhoneState.BLOCKED
      ) || !ticketVisualObservationIsFreshForDispatch(
        observation, SystemClock.elapsedRealtime(), TICKET_CONTROL_OBSERVATION_TTL_MILLIS
      )) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    return reauthSnapshot(
      request,
      "succeeded",
      "complete",
      if (request.logoutInApp) "saved_credentials_sign_in_proven" else "signed_in_proven",
      proofSource = "phone_visual",
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
          ticketViviNavigationTarget(TicketViviNavigationMode.EXACT, requiredReturnTarget, signedInObservation) != null
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

  private enum class TicketViviNavigationDisposition {
    RESTORED, REDETECTED, NO_TICKET_PROVEN, FALLBACK_SAFE, UNCERTAIN
  }

  private data class TicketViviNavigationResult(
    val disposition: TicketViviNavigationDisposition,
    val observation: TicketVisualActionObservation
  )

  private suspend fun restoreOrRedetectViviTicketAfterReauth(
    request: TicketViviReauthRequest,
    originalTarget: TicketViviReauthReturnTarget?,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): TicketViviReauthSnapshot {
    updateViviReauthPhase(request, "running", "redetecting_latest_ticket", "running")
    val original = originalTarget?.let {
      navigateViviAfterReauth(request, TicketViviNavigationMode.ORIGINAL, it, initialObservation, panelLease)
    }
    val result = if (original == null || original.disposition == TicketViviNavigationDisposition.FALLBACK_SAFE) {
      navigateViviAfterReauth(request, TicketViviNavigationMode.LATEST, null,
        original?.observation ?: initialObservation, panelLease)
    } else original
    return when (result.disposition) {
      TicketViviNavigationDisposition.RESTORED -> provenViviReauthV4TicketSnapshot(
        request, "saved_credentials_original_ticket_restored", result.observation,
        originalTarget!!.detailAnchor, originalTarget.state
      )
      TicketViviNavigationDisposition.REDETECTED -> provenViviReauthV4TicketSnapshot(
        request, "saved_credentials_latest_ticket_redetected", result.observation,
        result.observation.currentAnchor, TicketVisualPhoneState.UNACTIVATED_DETAIL
      )
      TicketViviNavigationDisposition.NO_TICKET_PROVEN -> provenViviReauthV4NoTicketSnapshot(
        request, TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY, result.observation
      )
      else -> reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    }
  }

  private suspend fun navigateViviAfterReauth(
    request: TicketViviReauthRequest,
    mode: TicketViviNavigationMode,
    original: TicketViviReauthReturnTarget?,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): TicketViviNavigationResult {
    var observation = initialObservation
    fun result(disposition: TicketViviNavigationDisposition) = TicketViviNavigationResult(disposition, observation)
    val dispatchedKinds = mutableSetOf<TicketViviNavigationKind>()
    val mutationLimit = if (mode == TicketViviNavigationMode.LATEST) {
      VIVI_REAUTH_TICKET_REDETECT_MAX_MUTATIONS
    } else VIVI_REAUTH_TICKET_RESTORE_MAX_MUTATIONS
    repeat(mutationLimit) { mutationIndex ->
      if (original != null && ticketViviReauthRestoredTarget(original, observation)) {
        return result(TicketViviNavigationDisposition.RESTORED)
      }
      if (mode == TicketViviNavigationMode.ORIGINAL && observation.state in setOf(
          TicketVisualPhoneState.ACTIVATED_DETAIL, TicketVisualPhoneState.UNACTIVATED_DETAIL
        )) return result(TicketViviNavigationDisposition.FALLBACK_SAFE)
      val target = ticketViviNavigationTarget(mode, original, observation)
        ?: return result(if (mode == TicketViviNavigationMode.ORIGINAL && original != null &&
            ticketViviReauthOriginalTargetVisuallyAbsent(original, observation)) {
          TicketViviNavigationDisposition.FALLBACK_SAFE
        } else TicketViviNavigationDisposition.UNCERTAIN)
      val phase = if (mode == TicketViviNavigationMode.LATEST) target.kind.latestPhase
        else target.kind.restorePhase
      if (!dispatchedKinds.add(target.kind) || !persistViviReauthPhase(request, phase) ||
        !panelLease.beforeMutationAllowed()
      ) return result(TicketViviNavigationDisposition.UNCERTAIN)
      val before = observation
      panelLease.markMutationMayHaveDispatched()
      // The shell acknowledgement cannot prove non-dispatch. Only observe this
      // target's successor, and never send the same target kind twice in a route.
      tapTicketVisualProbeBounds(target.bounds, "vivi_reauth_${mode.name.lowercase()}_${target.kind.name.lowercase()}")
      observation = awaitViviReauthTransition("vivi_reauth_navigation_${mutationIndex + 1}") {
        ticketViviNavigationTransition(mode, original, target, before, it)
      } ?: return result(TicketViviNavigationDisposition.UNCERTAIN)
      if (mode == TicketViviNavigationMode.LATEST && target.kind == TicketViviNavigationKind.UNUSED_DETAIL) {
        observation = ticketVisualObservationAfterCardSelection(observation, target.selectedAnchor)
        return result(TicketViviNavigationDisposition.REDETECTED)
      }
      if (mode != TicketViviNavigationMode.EXACT && target.kind == TicketViviNavigationKind.TIME_TAB &&
        before.state == TicketVisualPhoneState.TICKETS_SINGLE_USE_EMPTY &&
        observation.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY
      ) return result(TicketViviNavigationDisposition.NO_TICKET_PROVEN)
    }
    return result(if (original != null && ticketViviReauthRestoredTarget(original, observation)) {
      TicketViviNavigationDisposition.RESTORED
    } else TicketViviNavigationDisposition.UNCERTAIN)
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
    if (!exactTarget || !ticketVisualObservationIsFreshForDispatch(
        observation, SystemClock.elapsedRealtime(), TICKET_CONTROL_OBSERVATION_TTL_MILLIS
      )) {
      return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    }
    return reauthSnapshot(
      request,
      "succeeded",
      "complete",
      reason,
      proofSource = "phone_visual",
      ok = true
    )
  }

  private suspend fun provenViviReauthV4NoTicketSnapshot(
    request: TicketViviReauthRequest,
    navigationFromState: TicketVisualPhoneState,
    observation: TicketVisualActionObservation
  ): TicketViviReauthSnapshot {
    if (!ticketVisualRedetectLatestNotDetectedObservation(
        TicketVisualActionTarget.REDETECT_LATEST,
        navigationFromState,
        observation
      ) || !ticketVisualObservationIsFreshForDispatch(
        observation, SystemClock.elapsedRealtime(), TICKET_CONTROL_OBSERVATION_TTL_MILLIS
      )
    ) return reauthSnapshot(request, "needs_attention", "complete", "visual_proof_failed")
    return reauthSnapshot(
      request,
      "succeeded",
      "complete",
      "saved_credentials_no_ticket_proven",
      proofSource = "phone_visual",
      ok = true
    )
  }

  private suspend fun restoreViviTicketAfterReauth(
    request: TicketViviReauthRequest,
    returnTarget: TicketViviReauthReturnTarget,
    initialObservation: TicketVisualActionObservation,
    panelLease: TicketActionPanelDarkLease
  ): TicketVisualActionObservation? =
    navigateViviAfterReauth(request, TicketViviNavigationMode.EXACT, returnTarget, initialObservation, panelLease)
      .takeIf { it.disposition == TicketViviNavigationDisposition.RESTORED }?.observation

  private suspend fun awaitViviReauthTransition(
    reason: String,
    classify: (TicketVisualActionObservation?) -> TicketViviReauthRestoreTransitionStatus
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
      when (classify(observed)) {
        TicketViviReauthRestoreTransitionStatus.PROVED -> return observed
        TicketViviReauthRestoreTransitionStatus.REJECTED -> return null
        TicketViviReauthRestoreTransitionStatus.WAITING -> delay(VIVI_REAUTH_VERIFY_POLL_MILLIS)
      }
    }
    return null
  }

  private suspend fun reauthAttentionOrFailure(
    request: TicketViviReauthRequest,
    fallbackReason: String
  ): TicketViviReauthSnapshot = reauthAuthSurfaceResult(
    request,
    PhoneAutomationServiceBridge.classifyViviAuthSurface(TicketScreenConfig.VIVI_PACKAGE),
    fallbackReason
  )

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
    requestTicketSpacetimeResultPublication()
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
    requestTicketSpacetimeResultPublication()
    viviReauthCompletedAtMillis = SystemClock.elapsedRealtime()
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
  private fun TicketVisualActionSnapshot.commandResult() = TicketSpacetimeCommandResult(
    ok = ok, reason = reason, streamState = ticketSpacetimeStreamState(),
    terminal = terminal, ticketActionV3 = this
  )

  private suspend fun handleTicketVisualActionV3(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest
  ): TicketSpacetimeCommandResult {
    val timing = TicketActionTiming(SystemClock::elapsedRealtime)
    var receivedRegistrationIdentity: TicketRegistrationProof? = null
    val generation = sessionMutex.withLock {
      synchronized(ticketActionV3Lock) {
        val retained = loadTicketVisualActionJournal()
        if (retained.hasRetainedTerminal &&
          (retained.actionId != request.actionId || retained.commandId != command.id)) {
          return TicketVisualActionSnapshot(
            actionId = request.actionId, target = request.target.wireName,
            status = "pending", phase = "waiting_for_terminal_finalization",
            reason = "ticket_action_v3_terminal_finalization_pending"
          ).commandResult()
        }
        if (serviceLifecycleStopping || !secureCaptureStartupReady) {
          return recordTicketVisualActionOutcome(request, request.terminal(
            if (serviceLifecycleStopping) "ticket_action_v3_service_stopping"
            else "ticket_action_v3_startup_reconcile_unproved",
            status = if (request.target.activatesTicket) "needs_attention" else "failed",
            terminalPhase = if (request.target.activatesTicket) "not_dispatched" else "failed"
          )).commandResult()
        }
        if (ticketActionV3Snapshot.actionId == request.actionId) {
          return ticketActionV3Snapshot.commandResult()
        }
        // The subscribed worker is the only executor. Retain the original control
        // identity before publishing busy state; fresh input proof is acquired later.
        receivedRegistrationIdentity = if (request.expectedInteractionRevision.startsWith("pc-")) {
          ticketPhoneControlRegistrationIdentity(request.expectedInteractionRevision,
            phoneControlState.exactContext(request.expectedInteractionRevision, SystemClock.elapsedRealtime()))
        } else null
        ticketActionV3Generation += 1L
        ticketActionV3MutationGeneration += 1L
        ticketActionV3Snapshot = TicketVisualActionSnapshot(
          actionId = request.actionId, target = request.target.wireName,
          status = "running", phase = "awaiting_visual_proof", reason = "ticket_action_v3_admitted"
        )
        ticketVisualActionJobOwnershipActive = true
        ticketActionV3Generation
      }
    }
    requestTicketSpacetimeResultPublication()
    timing.mark(TicketActionTiming.Phase.ADMITTED)
    ticketActionTiming = timing
    try {
      val terminal = try {
        controlCodePhoneMutationLane.withOwnership {
          runTicketVisualActionV3(command, request, generation, receivedRegistrationIdentity)
        }
      } catch (error: Throwable) {
        val revision = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
          request.expectedInteractionRevision
        } else command.revision
        val checkpoint = if (request.target.activatesTicket) {
          ticketActivationCheckpoint(command.id, revision, request.attemptId)
        } else null
        recordTicketVisualActionOutcome(request, request.terminal(
          "ticket_action_v3_internal_failure",
          status = if (request.target.activatesTicket) "needs_attention" else "failed",
          terminalPhase = ticketActivationFailureTerminalPhase(checkpoint)
            .takeIf { request.target.activatesTicket }
        ))
      }
      synchronized(ticketActionV3Lock) { ticketActionV3Snapshot = terminal }
      return terminal.commandResult()
    } finally {
      timing.mark(TicketActionTiming.Phase.TERMINAL)
      enqueueTicketSpacetimeTraceEvent(
        "ticket_action_timing", TicketTracePrivacy.allowlistedFields(timing.detail()), command.id
      )
      if (ticketActionTiming === timing) ticketActionTiming = null
      withContext(NonCancellable) {
        sessionMutex.withLock { ticketVisualActionJobOwnershipActive = false }
      }
    }
  }

  private suspend fun runTicketVisualActionV3(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    generation: Long,
    receivedRegistrationIdentity: TicketRegistrationProof?
  ): TicketVisualActionSnapshot {
    val recoveringControlCodeCheckpointAtStart = controlCodeSignatureCleanupRequired
    sessionMutex.withLock {
      ticketVisualActionCaptureLeaseActive = true
    }
    val panelLease = newTicketPanelDarkLease(request.actionId)
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
      coroutineScope {
        val preparationEpoch = streamEpoch
        val preparationRestart = rootHardwareH264CaptureEngine.snapshot().restartCount
        val preparationMutation = ticketActionV3MutationGeneration
        val preparationTouch = ticketActivationPhysicalTouchFence(
          PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
        )
        // Read-only observation can overlap display protection on an already-running stream.
        // The protected lane remains exclusive; no launch or input happens before acquisition.
        val warmObservation = if (!request.target.activatesTicket &&
          !recoveringControlCodeCheckpointAtStart && preparationTouch != null &&
          streamActive && preparationEpoch > 0L &&
          rootHardwareH264CaptureEngine.snapshot().active
        ) async {
          awaitStableTicketVisualActionObservation(
            "ticket_action_v3_initial", TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS
          )
        } else null
        try {
          leaseAcquired = panelLease.acquire()
          provisional = if (!leaseAcquired) {
            request.terminal(
              // The detailed clamp failure remains available in privacy-safe Pixel health. Publish the
              // existing allowlisted pre-dispatch reason so Spacetime never collapses this terminal.
              "ticket_action_failed"
            )
          } else {
            val result = runTicketVisualActionV3WithCaptureLease(
              command,
              request,
              generation,
              panelLease,
              receivedRegistrationIdentity,
              initialObservation = { mayUse ->
                if (!mayUse) {
                  warmObservation?.cancelAndJoin()
                  null
                } else warmObservation?.await()?.takeIf {
                  streamEpoch == preparationEpoch &&
                    rootHardwareH264CaptureEngine.snapshot().restartCount == preparationRestart &&
                    ticketActionV3Generation == generation &&
                    ticketActionV3MutationGeneration == preparationMutation &&
                    preparationTouch != null && ticketActivationPhysicalTouchFenceIsCurrent(
                      preparationTouch, PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
                    ) && ticketVisualObservationIsFreshForDispatch(
                      it, SystemClock.elapsedRealtime(), ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
                    )
                }
              },
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
                  request.terminal(
                    "ticket_action_physical_touch_preempted_after_dispatch",
                    status = "needs_attention",
                    terminalPhase = "outcome_unknown".takeIf { request.target.activatesTicket }
                  )
                }
              leaseState.physicalTouchPreempted ->
                request.terminal("ticket_action_physical_touch_preempted_before_dispatch")
              leaseState.failure.isNotBlank() ->
                request.terminal(
                  leaseState.failure,
                  status = if (leaseState.mutationMayHaveDispatched) "needs_attention" else "failed"
                )
              else -> result
            }
          }
        } finally {
          warmObservation?.cancel()
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
        PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction()
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
    // Completion is a durable fact about this action. Encoder progress or restart
    // during display cleanup cannot turn a proved physical outcome into uncertainty.
    val successfulProofCurrent = !provisionalHasBoundVisualProof ||
      (provisional.semanticProof && provisional.actionId == request.actionId &&
        generation == ticketActionV3Generation)
    val recoveredControlCodeSurface = recoveringControlCodeCheckpointAtStart && provisional.ok &&
      request.target == TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED &&
      provisional.currentView == TicketVisualActionView.LATEST_UNACTIVATED
    val recoveredControlCodeCleanupCommitted = if (!recoveredControlCodeSurface) {
      true
    } else if (successfulProofCurrent &&
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
          streamEpoch = currentWatermark.first,
          frameSequence = currentWatermark.second,
          reason = if (provisionalExpectedNegativeProof) {
            "ticket_action_frame_watermark_unproved"
          } else {
            "ticket_action_visual_unproved"
          },
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
          streamEpoch = currentWatermark.first,
          frameSequence = currentWatermark.second,
          reason = "ticket_action_visual_unproved",
          completedAt = Instant.now().toString(),
          terminal = true,
          ok = false
        )
      }
      else -> provisional
    }
    val terminal = if (request.target.activatesTicket && !rawTerminal.ok) {
      val checkpointRevision = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
        request.expectedInteractionRevision
      } else {
        command.revision
      }
      val stablePhase = ticketActivationFailureTerminalPhase(
        ticketActivationCheckpoint(command.id, checkpointRevision, request.attemptId),
        provisional.phase
      )
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
    val terminalExpectedNegativeProof =
      ticketVisualLatestNotDetectedTerminalHasBoundProof(terminal)
    val terminalObservation = terminal.proofObservation.takeIf {
      (terminal.ok || terminalExpectedNegativeProof) && successfulProofCurrent
    }
    val recorded = recordTicketVisualActionOutcome(request, terminal.copy(proofObservation = terminalObservation))
    if (recorded.ok && recoveredControlCodeSurface && recoveredControlCodeCleanupCommitted) {
      publishControlCodeReadyAfterPanelFinalization(lastControlCodeRequestId.orEmpty())
    }
    return recorded
  }

  private fun ticketActionPanelDarkFinalizationFailureReason(
    request: TicketVisualActionRequest,
    snapshot: TicketActionPanelDarkLeaseSnapshot
  ): String = when {
    !snapshot.mutationMayHaveDispatched -> "ticket_action_failed"
    request.target.activatesTicket -> "ticket_action_activation_dispatch_uncertain"
    else -> "ticket_action_navigation_dispatch_uncertain"
  }

  private suspend fun runTicketVisualActionV3WithCaptureLease(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    generation: Long,
    panelLease: TicketActionPanelDarkLease,
    receivedRegistrationIdentity: TicketRegistrationProof?,
    initialObservation: suspend (Boolean) -> TicketVisualActionObservation? = { null },
    onNewSessionAdmitted: (Long) -> Unit
  ): TicketVisualActionSnapshot {
    loadTicketVisualSwitchAnchorsIfNeeded()
    val recoveringControlCodeCheckpoint = controlCodeSignatureCleanupRequired
    if (recoveringControlCodeCheckpoint &&
      request.target != TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED
    ) {
      return request.terminal("control_code_cleanup_pending_requires_visual_reopen", status = "needs_attention")
    }
    val retainedJournal = loadTicketVisualActionJournal()
    retainedTicketVisualTerminalSnapshot(
      retainedJournal,
      request,
      streamEpoch,
      frameSequence
    )?.let { return it }
    if (ticketActionV3Generation != generation) {
      return request.terminal("ticket_action_v3_superseded")
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
        return request.terminal(
          "ticket_action_activation_checkpoint_unproved",
          status = "needs_attention",
          terminalPhase = "not_dispatched"
        )
      }
    }
    updateTicketVisualActionPhase(request, "starting_visual_stream")
    if (!panelLease.beforeMutationAllowed()) {
      return request.terminal("ticket_action_failed")
    }
    if (!streamActive) {
      val startResponse = startTicketSession(
        onNewSessionAdmitted = onNewSessionAdmitted
      )
      if (!startResponse.ok || !streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
        return request.terminal("ticket_action_visual_stream_unavailable")
      }
    }
    val viviAlreadyFocused = viviFocusedForFastPublicOpen("ticket_action_v3_prelaunch")
    val mayUseWarmObservation = viviAlreadyFocused &&
      !request.target.activatesTicket && !retainedJournal.navigationDispatchUncertain
    if (!mayUseWarmObservation) initialObservation(false)
    if (request.target.activatesTicket || !viviAlreadyFocused) {
      // The focus lookup can suspend while the physical-touch monitor remains live. Re-check at
      // the exact resume boundary so a touch that arrived during it can never be followed by input.
      if (!panelLease.beforeMutationAllowed()) {
        return request.terminal("ticket_action_failed")
      }
      // This is a no-touch Android activity resume. It never creates physical replay authority;
      // the durable dispatch checkpoint is still written only after the exact input fence passes.
      launchViviForWake("ticket_action_v3:${request.target.wireName}")
    }
    ensureRootHardwareH264CaptureIfPossible()
    rootHardwareH264CaptureEngine.requestImmediateRefresh("ticket_action_v3_capture_lease")
    if (request.target.activatesTicket) {
      updateTicketVisualActionPhase(request, "preparing_registration_input")
      if (!PhoneAutomationServiceBridge.awaitAccessibilityConnection(
          TICKET_SLIDER_ACCESSIBILITY_RECONNECT_TIMEOUT_MILLIS
        )
      ) {
        return request.terminal("ticket_action_accessibility_unavailable")
      }
    }
    val captureRecoveryBudget = TicketVisualCaptureRecoveryBudget()
    // A normal register-current command already names the private ticket identity. Prepare its
    // exact input fence and two new visual samples once, then use that same preparation for the
    // initial reconciliation and first stroke. Recovery still observes the retained journal
    // before considering any new preparation; it never acquires fresh replay authority.
    val initialPreparation = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT &&
      !retainedJournal.navigationDispatchUncertain &&
      ticketActivationCheckpoint(command.id, request.expectedInteractionRevision, request.attemptId) == null
    ) {
      val identity = receivedRegistrationIdentity?.takeIf {
        ticketRegistrationProofRevisionForRegisterCurrent(
          it.interactionRevision, request.expectedInteractionRevision
        ) != null && it.detailAnchor.isNotBlank()
      } ?: return request.terminal(
        "ticket_action_interaction_revision_unproved",
        status = "needs_attention",
        terminalPhase = "not_dispatched"
      )
      val preparation = prepareExactTicketActivationDispatch(
        request, identity.detailAnchor, generation, panelLease, resumeVivi = false
      )
      preparation.prepared ?: return request.terminal(preparation.reason, status = "needs_attention", terminalPhase = "not_dispatched")
    } else null
    val warmObservation = if (mayUseWarmObservation) initialObservation(true) else null
    var observation = initialPreparation?.observation ?: warmObservation ?: awaitStableTicketVisualActionObservation(
      "ticket_action_v3_initial",
      TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS,
      captureRecoveryBudget = captureRecoveryBudget.takeIf {
        retainedJournal.navigationDispatchUncertain &&
          retainedJournal.actionId == request.actionId &&
          retainedJournal.target == request.target.wireName
      }
    ) ?: return request.terminal("ticket_action_visual_unproved")
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
          return request.terminal(
            ticketActivationNoTransitionTerminalReason(checkpoint),
            observation,
            status = "needs_attention",
            terminalPhase = ticketActivationNoTransitionTerminalPhase(checkpoint)
          )
        TicketActivationCheckpointStage.ACTIVATION_DISPATCHING,
        TicketActivationCheckpointStage.NEEDS_ATTENTION -> {
          checkpoint.let(ticketActivationCheckpointStore::recordNeedsAttention)
          return request.terminal(
            "ticket_action_activation_dispatch_uncertain",
            observation,
            status = "needs_attention"
          )
        }
        TicketActivationCheckpointStage.FRESH_TICKET_PROVEN ->
          return request.terminal(
            "ticket_action_activation_checkpoint_unproved",
            observation,
            status = "needs_attention",
            terminalPhase = "not_dispatched"
          )
        null -> Unit
      }
    }

    var mutations = 0
    var controlCodeRecoveryListProved = observation.state == TicketVisualPhoneState.TICKET_LIST
    var selectedDetailAnchor = ""
    var selectedAnchor = retainedJournal.intendedAnchor.takeIf {
      retainedJournal.actionId == request.actionId && retainedJournal.target == request.target.wireName
    }.orEmpty()
    var pendingNavigation = retainedJournal.takeIf { it.navigationDispatchUncertain }
    val desiredState = if (request.target == TicketVisualActionTarget.SHOW_RECENT_ACTIVATED) {
      TicketVisualPhoneState.ACTIVATED_DETAIL
    } else {
      TicketVisualPhoneState.UNACTIVATED_DETAIL
    }
    fun requiredAnchor(): String = when (request.target) {
      TicketVisualActionTarget.SHOW_RECENT_ACTIVATED -> ticketVisualSwitchAnchors.recentActivatedAnchor
      TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED -> ticketVisualSwitchAnchors.latestUnactivatedAnchor
      TicketVisualActionTarget.REGISTER_CURRENT -> ""
      else -> selectedAnchor
    }
    while (true) {
      // A newly dispatched tap and a retained uncertain tap share one reconciliation path.
      // Neither is followed by another mutation until its typed successor and journal agree.
      pendingNavigation?.let { journal ->
        if (!ticketVisualJournalReconciled(journal, request, observation)) {
          return request.terminal(
            "ticket_action_navigation_dispatch_uncertain",
            observation,
            status = "needs_attention"
          )
        }
        if (observation.state == TicketVisualPhoneState.TICKET_LIST) controlCodeRecoveryListProved = true
        if (observation.state in setOf(
            TicketVisualPhoneState.UNACTIVATED_DETAIL, TicketVisualPhoneState.ACTIVATED_DETAIL
          ) && journal.navigationAnchor.isNotBlank()
        ) {
          selectedDetailAnchor = observation.currentAnchor
          observation = if (request.target == TicketVisualActionTarget.SHOW_RECENT_ACTIVATED) {
            ticketVisualObservationAfterRecentActivatedSelection(
              observation, journal.navigationAnchor, ticketVisualSwitchAnchors.recentActivatedAnchor
            )
          } else ticketVisualObservationAfterCardSelection(observation, journal.navigationAnchor)
        }
        if (!persistTicketVisualActionJournal(journal.copy(phase = "navigation_reconciled"))) {
          return request.terminal(
            if (mutations == 0) "ticket_action_navigation_journal_unproved"
            else "ticket_action_navigation_journal_unproved_after_dispatch",
            observation,
            status = "needs_attention"
          )
        }
        ticketVisualActionLatestNotDetectedAfterTimeTab(
          request, TicketVisualPhoneState.fromWireName(journal.navigationFromState), observation
        )?.let { return it }
        pendingNavigation = null
      }
      if (mutations >= TICKET_ACTION_V3_MAX_NAVIGATION_MUTATIONS) break
      if (ticketActionV3Generation != generation) {
        return request.terminal("ticket_action_v3_superseded")
      }
      val requiredAnchor = requiredAnchor()
      val targetProved = observation.state == desiredState &&
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
        return request.terminal("ticket_action_register_current_requires_unactivated_detail")
      }
      if (observation.state == TicketVisualPhoneState.LOGIN_REQUIRED ||
        observation.state == TicketVisualPhoneState.BLOCKED ||
        observation.state == TicketVisualPhoneState.UNKNOWN
      ) {
        return request.terminal("ticket_action_visual_state_${observation.state.wireName}")
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
            TicketVisualActionTarget.REGISTER_CURRENT -> null
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
      } ?: return request.terminal("ticket_action_visual_target_ambiguous")
      updateTicketVisualActionPhase(request, "applying_visual_navigation")
      val navigationJournal = TicketVisualActionJournalState(
        actionId = request.actionId,
        target = request.target.wireName,
        phase = "navigation_dispatched",
        intendedAnchor = selectedAnchor.ifBlank { requiredAnchor },
        navigationFromState = observation.state.wireName,
        navigationToState = if (observation.state == TicketVisualPhoneState.TICKET_LIST) {
          desiredState.wireName
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
        return request.terminal(
          "ticket_action_panel_dark_preempted",
          observation,
          status = if (panelLease.snapshot().mutationMayHaveDispatched) "needs_attention" else "failed"
        )
      }
      if (!persistTicketVisualActionJournal(navigationJournal)) {
        return request.terminal("ticket_action_navigation_journal_unproved", observation)
      }
      // A failed root result can arrive after Android accepted the tap. In either case this is
      // now one dispatched physical attempt: observe its typed result and never resend it.
      panelLease.markMutationMayHaveDispatched()
      ticketActionTiming?.mark(TicketActionTiming.Phase.INPUT_REQUESTED)
      tapTicketVisualProbeBounds(tapBounds, "ticket_action_v3_${request.target.wireName}")
      ticketActionTiming?.mark(TicketActionTiming.Phase.INPUT_RETURNED)
      mutations += 1
      observation = awaitStableTicketVisualActionObservation(
        "ticket_action_v3_after_navigation_$mutations",
        TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS,
        convergenceExtensionMillis = TICKET_ACTION_V3_FINAL_CONVERGENCE_MILLIS,
        captureRecoveryBudget = captureRecoveryBudget
      ) ?: return request.terminal("ticket_action_visual_transition_unproved", status = "needs_attention")
      pendingNavigation = navigationJournal
    }

    if (observation.state != desiredState) {
      return request.terminal("ticket_action_target_not_reached", observation)
    }
    val exactRegistrationProof = if (request.target == TicketVisualActionTarget.REGISTER_CURRENT) {
      val registrationProofGate = ticketRegistrationProofForCurrentVisualAction(
        proof = receivedRegistrationIdentity,
        request = request,
        observation = observation
      )
      registrationProofGate.proof ?: return request.terminal(
        registrationProofGate.failureReason ?: "ticket_action_interaction_revision_unproved",
        observation
      )
    } else {
      null
    }
    val expectedAnchor = exactRegistrationProof?.ticketAnchor ?: requiredAnchor()
    if (expectedAnchor.isBlank()) {
      return request.terminal("ticket_action_selected_anchor_missing", observation)
    }
    if (observation.currentAnchor.isBlank() && exactRegistrationProof == null) {
      return request.terminal("ticket_action_transition_anchor_missing", observation)
    }
    if (exactRegistrationProof == null && observation.currentAnchor != expectedAnchor) {
      return request.terminal("ticket_action_selected_anchor_conflict", observation)
    }
    val provenTicketAnchor = ticketVisualProvenTicketAnchor(observation, exactRegistrationProof)
    if (observation.state == TicketVisualPhoneState.UNACTIVATED_DETAIL && provenTicketAnchor.isNotBlank()) {
      updateTicketVisualSwitchAnchors(ticketVisualSwitchAnchors.copy(
        latestUnactivatedAnchor = provenTicketAnchor
      ))
    }
    if (request.target == TicketVisualActionTarget.OPEN_LATEST_UNACTIVATED ||
      request.target == TicketVisualActionTarget.OPEN_LATEST_AND_REGISTER ||
      request.target == TicketVisualActionTarget.RETURN_TO_LATEST_UNACTIVATED ||
      request.target == TicketVisualActionTarget.REDETECT_LATEST
    ) {
      if (selectedDetailAnchor.isBlank()) {
        return request.terminal("ticket_action_detail_identity_unproved", observation)
      }
      val slider = observation.sliderBounds
        ?: return request.terminal("ticket_action_slider_unproved", observation)
      val deviceBounds = ticketVisualProbeBoundsToDevice(slider)
      if (command.revision.isBlank() || deviceBounds.width < 220 || deviceBounds.height < 20) {
        return request.terminal("ticket_action_interaction_proof_invalid", observation)
      }
    }
    if (!request.target.activatesTicket) {
      if (recoveringControlCodeCheckpoint) {
        val recovered = controlCodeCleanupHasPanelOwner()
        if (!recovered) {
          return request.terminal("ticket_action_visual_unproved", observation, status = "needs_attention")
        }
      }
      return ticketVisualActionSuccess(
        request,
        if (request.target == TicketVisualActionTarget.REDETECT_LATEST) {
          "ticket_action_latest_redetected"
        } else {
          "ticket_action_target_visible"
        },
        observation
      )
    }
    return activateTicketFromVisualAction(
      command,
      request,
      observation,
      exactRegistrationProof,
      selectedDetailAnchor,
      panelLease,
      generation,
      initialPreparation
    )
  }

  private suspend fun activateTicketFromVisualAction(
    command: TicketSpacetimeCommand,
    request: TicketVisualActionRequest,
    observation: TicketVisualActionObservation,
    exactRegistrationProof: TicketRegistrationProof?,
    selectedDetailAnchor: String,
    panelLease: TicketActionPanelDarkLease,
    generation: Long,
    initialPreparation: TicketActivationDispatchPreparation? = null
  ): TicketVisualActionSnapshot {
    // The caller has already bound the request and observation. Every stroke
    // still obtains its fresh exact-ticket, input-window and touch proof below.
    val provenObservation = exactRegistrationProof?.let { proof ->
      observation.copy(currentAnchor = proof.ticketAnchor)
    } ?: observation
    val provenDetailAnchor = exactRegistrationProof?.detailAnchor.orEmpty()
      .ifBlank { selectedDetailAnchor }
      .ifBlank { observation.currentAnchor }
    val revision = exactRegistrationProof?.interactionRevision ?: command.revision
    if (provenDetailAnchor.isBlank()) {
      return request.terminal(
        "ticket_action_detail_identity_unproved",
        observation,
        status = "needs_attention",
        terminalPhase = "not_dispatched"
      )
    }
    // Retained stages are reconciled once before navigation. Creating the first
    // fresh checkpoint below still refuses any occupied slot immediately before input.
    var checkpoint: TicketActivationCheckpoint? = null
    for (ordinal in 1..2) {
      updateTicketVisualActionPhase(
        request,
        if (ordinal == 1) "preparing_registration_input" else "preparing_registration_retry"
      )
      val preparation = if (ordinal == 1 && initialPreparation != null) {
        TicketActivationPreparationResult(prepared = initialPreparation, reason = "")
      } else prepareExactTicketActivationDispatch(
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
        return request.terminal(
          if (ordinal == 1) preparation.reason else "ticket_action_retry_not_dispatched",
          observation,
          status = "needs_attention",
          terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
        )
      }
      if (checkpoint == null) {
        checkpoint = recordTicketActivationFreshProof(command.id, revision, request.attemptId)
          ?: return request.terminal(
            "ticket_action_activation_checkpoint_unproved",
            prepared.observation,
            status = "needs_attention",
            terminalPhase = "not_dispatched"
          )
      }
      if (!ticketActivationPreparationStillCurrent(prepared, generation, panelLease)) {
        // The durable stage still proves that no stroke for this ordinal was admitted.
        return request.terminal(
          "ticket_action_exact_input_fence_changed",
          prepared.observation,
          status = "needs_attention",
          terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
        )
      }
      val physicalTouchFence = ticketActivationPhysicalTouchFence(
        PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
      ) ?: return request.terminal(
        "ticket_action_panel_dark_preempted",
        prepared.observation,
        status = "needs_attention",
        terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
      )
      updateTicketVisualActionPhase(
        request,
        if (ordinal == 1) "dispatching_registration_gesture" else "dispatching_registration_retry"
      )
      val dispatching = recordTicketActivationDispatching(requireNotNull(checkpoint), ordinal)
        ?: return request.terminal(
          "ticket_action_activation_dispatch_checkpoint_unproved",
          prepared.observation,
          status = "needs_attention",
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
        return request.terminal(
          "ticket_action_exact_input_fence_changed",
          prepared.observation,
          status = "needs_attention",
          terminalPhase = if (ordinal == 1) "not_dispatched" else "retry_not_dispatched"
        )
      }
      val contract = ticketSliderGestureContract(prepared.gestureBounds)
      val y = (prepared.gestureBounds.top + prepared.gestureBounds.bottom) / 2
      panelLease.markMutationMayHaveDispatched()
      ticketActionTiming?.mark(TicketActionTiming.Phase.INPUT_REQUESTED)
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
      ticketActionTiming?.mark(TicketActionTiming.Phase.INPUT_RETURNED)
      if (gestureResult != TicketSliderGestureDispatchResult.COMPLETED) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return request.terminal(
          if (gestureResult == TicketSliderGestureDispatchResult.REJECTED) {
            "ticket_action_gesture_rejected"
          } else {
            "ticket_action_gesture_completion_uncertain"
          },
          prepared.observation,
          status = "needs_attention",
          terminalPhase = "outcome_unknown"
        )
      }
      val postGestureLeaseReady = panelLease.beforeMutationAllowed()
      val postGestureLease = panelLease.snapshot()
      if (!postGestureLeaseReady || postGestureLease.physicalTouchPreempted) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return request.terminal(
          "ticket_action_activation_outcome_unknown",
          prepared.observation,
          status = "needs_attention",
          terminalPhase = "outcome_unknown"
        )
      }
      val postLiftFence = ticketActivationPhysicalTouchFence(
        PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
      )
      val postGestureObservation = if (postLiftFence != null) {
        awaitStableTicketVisualActionObservation(
          "ticket_action_v3_activation_result_$ordinal",
          TICKET_ACTION_V3_ACTIVATION_PROOF_TIMEOUT_MILLIS,
          currentOnly = true
        )
      } else null
      val postProofLeaseReady = panelLease.beforeMutationAllowed()
      val postProofPhysicalTouchCurrent = postLiftFence != null &&
        ticketActivationPhysicalTouchFenceIsCurrent(
          postLiftFence, PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
        )
      if (!postProofLeaseReady || !postProofPhysicalTouchCurrent) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return request.terminal(
          "ticket_action_activation_outcome_unknown",
          postGestureObservation,
          status = "needs_attention",
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
          return request.terminal(
            "ticket_action_activation_proven_checkpoint_unproved",
            activated,
            status = "needs_attention",
            terminalPhase = "outcome_unknown"
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
      // A tap may allow fresh proof of success, but cannot authorize another physical stroke.
      val exactNoTransition = resultGenerationCurrent &&
        ticketActivationPhysicalTouchFenceIsCurrent(
          physicalTouchFence, PhoneAutomationServiceBridge.currentRootPhysicalTouchState()
        ) &&
        postGestureObservation?.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
        postGestureObservation.currentAnchor == provenDetailAnchor
      if (!exactNoTransition) {
        ticketActivationCheckpointStore.recordNeedsAttention(dispatching)
        return request.terminal(
          "ticket_action_post_gesture_visual_unproved",
          postGestureObservation,
          status = "needs_attention",
          terminalPhase = "outcome_unknown"
        )
      }
      val noTransition = ticketActivationCheckpointStore.recordNoTransitionProven(dispatching)
        ?: return request.terminal(
          "ticket_action_no_transition_checkpoint_unproved",
          postGestureObservation,
          status = "needs_attention",
          terminalPhase = "outcome_unknown"
        )
      checkpoint = noTransition
      if (ordinal == 2) {
        return request.terminal(
          "ticket_action_gesture_completed_no_transition",
          postGestureObservation,
          status = "needs_attention",
          terminalPhase = "no_transition"
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
    val captureStreamEpoch = streamEpoch
    if (captureStreamEpoch <= 0L) {
      return TicketActivationPreparationResult(reason = "ticket_action_visual_unproved")
    }
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
    val gestureBounds = ticketVisualSliderGestureBounds(
      visualBounds = visualSlider,
      displayWidth = resources.displayMetrics.widthPixels,
      displayHeight = resources.displayMetrics.heightPixels
    ) ?: return TicketActivationPreparationResult(reason = "ticket_action_slider_geometry_invalid")
    if (!ticketVisualObservationIsFreshForDispatch(
        freshObservation,
        SystemClock.elapsedRealtime(),
        ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
      )
    ) {
      return TicketActivationPreparationResult(reason = "ticket_action_visual_unproved")
    }
    val prepared = TicketActivationDispatchPreparation(
      observation = freshObservation,
      gestureBounds = gestureBounds,
      inputFence = inputFence,
      captureStreamEpoch = captureStreamEpoch,
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
    if (!ticketVisualObservationIsFreshForDispatch(
        prepared.observation,
        SystemClock.elapsedRealtime(),
        ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
      )
    ) return false
    if (!ticketActivationInputFenceStillCurrent(
        generation = generation,
        actionMutationGeneration = prepared.actionMutationGeneration,
        captureStreamEpoch = prepared.captureStreamEpoch,
        captureRestartCount = prepared.captureRestartCount,
        inputFence = prepared.inputFence,
        panelLease = panelLease
      )
    ) return false
    return ticketActionV3Generation == generation &&
      ticketActionV3MutationGeneration == prepared.actionMutationGeneration &&
      streamEpoch == prepared.captureStreamEpoch &&
      rootHardwareH264CaptureEngine.snapshot().restartCount == prepared.captureRestartCount &&
      ticketVisualObservationIsFreshForDispatch(
        prepared.observation,
        SystemClock.elapsedRealtime(),
        ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
      )
  }

  private suspend fun ticketActivationInputFenceStillCurrent(
    generation: Long,
    actionMutationGeneration: Long,
    captureStreamEpoch: Long,
    captureRestartCount: Long,
    inputFence: PhoneAutomationTicketInputFence,
    panelLease: TicketActionPanelDarkLease
  ): Boolean {
    fun cheapFenceCurrent(): Boolean = ticketActionV3Generation == generation &&
      ticketActionV3MutationGeneration == actionMutationGeneration &&
      captureStreamEpoch > 0L && streamEpoch == captureStreamEpoch &&
      rootHardwareH264CaptureEngine.snapshot().restartCount == captureRestartCount
    if (!cheapFenceCurrent()) return false
    if (!panelLease.beforeMutationAllowed() ||
      !PhoneAutomationServiceBridge.ticketInputFenceIsCurrent(inputFence)
    ) return false
    return cheapFenceCurrent()
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
    val snapshot = request.terminal(
      "ticket_action_registered",
      publishedObservation,
      status = "succeeded",
      ok = true,
      terminalPhase = "outcome_unknown".takeIf { !checkpointVisualMatches }
    ).let { value ->
      value.copy(
        interactionRevision = interactionRevision,
        activationRevision = activationRevision.takeIf { value.ok }.orEmpty(),
        activationAttemptId = request.attemptId,
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
      if (probe != null) {
        completedProbeSeen = true
      }
      // The exact probe id guarantees that this capture starts after the request. Use that request
      // boundary to reject an older capture; actual picture age uses the helper's capture start.
      val current = probe?.ticketActionObservation?.copy(atMillis = started)
      if (current != null && current.probeId == probeId) {
        consensus.offer(current, allowUnknown)?.let { return it }
      }
      delay(TICKET_ACTION_V3_PROBE_GAP_MILLIS)
    }
  }


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
        requestTicketSpacetimeResultPublication()
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
    return request.terminal("ticket_action_latest_not_detected", observation, expectedNegativeProof = true)
  }

  private fun ticketVisualActionSuccess(
    request: TicketVisualActionRequest,
    reason: String,
    observation: TicketVisualActionObservation
  ): TicketVisualActionSnapshot {
    // Slider eligibility is published only by the current phone observation owner.
    return request.terminal(reason, observation, status = "succeeded", ok = true)
  }

  private fun TicketVisualActionRequest.terminal(
    reason: String,
    observation: TicketVisualActionObservation? = null,
    status: String = "failed",
    ok: Boolean = false,
    expectedNegativeProof: Boolean = false,
    terminalPhase: String? = null
  ): TicketVisualActionSnapshot {
    val request = this
    val observationProved = observation != null && ticketVisualObservationIsFreshForDispatch(
      observation, SystemClock.elapsedRealtime(), TICKET_CONTROL_OBSERVATION_TTL_MILLIS
    )
    val resultView = observation?.let {
      ticketVisualResultView(request.target, it.state)
    } ?: TicketVisualActionView.UNKNOWN
    val successfulViewCurrent = !ok || ticketVisualTerminalViewCompatible(request.target, resultView)
    val terminalOk = ok && observationProved && successfulViewCurrent
    val expectedNegativeProofCurrent = expectedNegativeProof && !ok &&
      request.target == TicketVisualActionTarget.REDETECT_LATEST &&
      status == "failed" && reason == "ticket_action_latest_not_detected" &&
      observation?.state == TicketVisualPhoneState.TICKETS_TIME_EMPTY && observationProved
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
        "ticket_action_visual_unproved"
      ok && !observationProved -> "ticket_action_visual_unproved"
      ok && !successfulViewCurrent -> "ticket_action_terminal_view_unproved"
      else -> reason
    }
    return TicketVisualActionSnapshot(
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
      reason = terminalReason,
      completedAt = Instant.now().toString(),
      activationAttemptId = request.attemptId.takeIf {
        request.target.activatesTicket
      }.orEmpty(),
      semanticProof = observationProved,
      terminal = true,
      ok = terminalOk,
      proofObservation = observation
    )
  }

  private fun recordTicketVisualActionOutcome(
    request: TicketVisualActionRequest,
    snapshot: TicketVisualActionSnapshot
  ): TicketVisualActionSnapshot {
    val publicSnapshot = snapshot.copy(proofObservation = null)
    if (persistTicketVisualTerminalSnapshot(request, publicSnapshot, snapshot.proofObservation)) {
      return publicSnapshot
    }
    recordTicketEvent("ticket_action_terminal_journal_unproved", "action=${request.actionId.takeLast(24)}")
    return publicSnapshot.copy(
      status = "needs_attention", phase = "needs_attention",
      reason = "ticket_action_terminal_journal_unproved",
      ok = false
    )
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
    val terminalJournal = TicketVisualActionJournalState(
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
      semanticProof = snapshot.semanticProof
    )
    if (prior.hasRetainedTerminal) return prior == terminalJournal
    return persistTicketVisualActionJournal(terminalJournal)
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
      semanticProof = preferences.getBoolean("semantic_proof", false),
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
    val persisted = ticketVisualActionJournalWriteProved(
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
          .putBoolean("semantic_proof", value.semanticProof)
          .putInt("slider_left_basis_points", value.sliderLeftBasisPoints)
          .putInt("slider_top_basis_points", value.sliderTopBasisPoints)
          .putInt("slider_right_basis_points", value.sliderRightBasisPoints)
          .putInt("slider_bottom_basis_points", value.sliderBottomBasisPoints)
          .commit()
      },
      readBack = ::loadTicketVisualActionJournal
    )
    if (persisted && value.hasRetainedTerminal) requestTicketSpacetimeResultPublication()
    return persisted
  }


  internal fun pendingTicketVisualActionFinalization(): TicketActionFinalizationEnvelope? =
    ticketActionFinalizationEnvelope(loadTicketVisualActionJournal())

  internal fun completeTicketVisualActionFinalization(
    envelope: TicketActionFinalizationEnvelope
  ): Boolean {
    val journal = loadTicketVisualActionJournal()
    if (!journal.hasRetainedTerminal) return journal.actionId.isBlank()
    if (ticketActionFinalizationEnvelope(journal) != envelope) return false
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
    return startTicketSession(lockedStartDecision = {
      if (TicketSessionStopPolicy.browserAutoStartAllowedAfterStop(lastSessionStopReason)) null
      else TicketSessionResponse(false, "reconnect_required", "Reconnect to start Ticket again")
    }).toTicketSpacetimeCommandResult(cleanReason)
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
      put("lastStreamRecoveryResult", lastStreamRecoveryResult)
      put("streamWatchdogStage", streamWatchdogStage)
      put("lastStreamWatchdogAction", lastStreamWatchdogAction)
      put("controlCodeStatus", lastControlCodeRequestStatus)
    }.toString()
  }

  internal fun peekTicketCodePublication(): TicketCodePublication? = ticketSpacetimePhoneOutbox.peek()

  internal fun acknowledgeTicketCodePublication(publication: TicketCodePublication) {
    ticketSpacetimePhoneOutbox.acknowledge(publication)
  }

  private fun enqueueTicketCodePublication(publication: TicketCodePublication) {
    ticketSpacetimePhoneOutbox.enqueue(publication)
    requestTicketSpacetimeResultPublication()
  }

  internal fun ticketSpacetimeViviReauthState(): TicketViviReauthSnapshot? =
    viviReauthSnapshot.takeIf { it.requestId.isNotBlank() && it.status != "idle" }

  internal fun ticketSpacetimeActionProgress(): TicketVisualActionSnapshot? =
    ticketActionV3Snapshot.takeIf { it.actionId.isNotBlank() && !it.terminal && it.status == "running" }

  internal fun requestTicketSpacetimeResultPublication() {
    ticketSpacetimeResultNotifications.trySend(Unit)
  }

  private fun TicketSessionResponse.toTicketSpacetimeCommandResult(reason: String): TicketSpacetimeCommandResult {
    return TicketSpacetimeCommandResult(
      ok = ok,
      reason = if (ok) state else reason.ifBlank { state }.ifBlank { message },
      streamState = ticketSpacetimeStreamState()
    )
  }

  private fun JsonObject.stringValue(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

  private fun JsonObject.booleanValue(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

  private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

  internal fun setTicketColdRestartBlocked(blocked: Boolean) {
    ticketColdRestartBlocked = blocked
  }

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
        if (ticketColdRestartBlocked) {
          return@withLock TicketSessionResponse(false, "cold_restart", "Ticket is entering cold mode")
        }
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
      lastSessionStopReason = null
      markViewerInput("session_start_already_preparing")
      lastMessage = "Preparing ViVi for secure H.264 capture"
      scheduleTicketBrightnessGuard("session_start_already_preparing")
      startForegroundGuard()
      return TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
    }
    if (!TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)) {
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
      }
      recordTicketEvent("phone_portrait_lock_unverified", "session_start")
      return TicketSessionResponse(
        ok = false,
        state = "portrait_lock_failed",
        message = "Phone portrait lock could not be verified"
      )
    }
    if (safetyPreflight.secureCapture.outcome == "ownership_unproved") {
      cancelPreflightArm("secure_capture_ownership_unproved")
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
      if (!controlOwnershipActive) {
        return TicketSessionResponse(
          ok = false,
          state = "secure_capture_ownership_busy",
          message = "Secure capture ownership changed; retry the stream start"
        )
      }
      lastMessage = "Another Ticket operation is preparing secure capture"
      markViewerInput("session_start_secure_capture_ownership_busy")
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
      if (canReuseActiveHardwareStreamWithoutRootRevalidation()) {
        return@session reuseActiveHardwareStream("session_start_already_active")
      }
      if (!validateActiveTicketSessionBeforeReuse("session_start_already_active")) {
        lastMessage = "The current ViVi view could not be proved; no navigation was attempted"
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "active_stream_visual_unproved")
        return@session TicketSessionResponse(
          ok = false,
          state = "visual_unproved",
          message = lastMessage
        )
      }
      return@session reuseActiveHardwareStream("session_start_already_active")
    }
    val sourceSize = currentDisplaySize()
    var hardwareCapture = rootHardwareH264CaptureEngine.snapshot()
    if (!hardwareCapture.available) {
      // The service-start probe is the one owner of helper/classpath
      // readiness. Every cold viewer, not only control-code work, must join
      // that job before deciding that capture is unavailable; otherwise a
      // normal viewer can launch a competing probe while startup is still
      // cleaning up and leave an active-but-empty stream behind.
      hardwareCapture = awaitRootHardwareH264StartupReadiness()
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
    lastSessionStopReason = null
    if (effectiveHardwareUnavailableReason != null) {
      cancelPreflightArm("hardware_unavailable")
      fallbackReason = effectiveHardwareUnavailableReason
      cancelInactivityTimer()

      scheduleTicketBrightnessGuard("capture_unavailable")
      releaseBlackoutOverlaySuppression()
      lastMessage = "Hardware H.264 ticket stream is unavailable; stream was not started"
      activeCaptureMode = CAPTURE_MODE_IDLE
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      updateTicketSessionState(TICKET_SESSION_UNAVAILABLE, "hardware_h264_unavailable")
      recordTicketEvent("session_unavailable", fallbackReason.orEmpty())
      return@session TicketSessionResponse(
        ok = false,
        state = "hardware_h264_unavailable",
        message = lastMessage
      )
    }
    val admitted = streamStartAdmission.admit(
      controlCodeOwnsStart = controlCodeOwnsStart,
      additionalControlOwnershipActive = ::ticketSpacetimeControlCodeRequestActive,
      blocked = { false },
      admitted = {
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
          val activated = rootHardwareH264CaptureEngine.activateArmed(
            handle,
            source.first,
            source.second,
            size.width,
            size.height,
            TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
          )
          if (activated) preflightArm = null
        }
        val modeLabel = "root_hardware_h264"
        updateTicketSessionState(TICKET_SESSION_STARTING, "session_start_${modeLabel}_prepare")
        markViewerInput("session_start_${modeLabel}_prepare")
        lastMessage = "Preparing ViVi for secure H.264 capture"
        recordTicketEvent("session_started", "mode=$activeCaptureMode")
        startForegroundGuard()
        true
      }
    )
    // Admission, unused-arm cleanup, reliability, and capture scheduling are one ordered path.
    cancelPreflightArm("session_start_unused_arm")
    if (!admitted) {
      return@session TicketSessionResponse(
        ok = true, state = ticketSessionState, message = "Control-code work owns the phone"
      )
    }
    if (reliabilityProbeRequired) {
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
        return@session TicketSessionResponse(
          ok = false,
          state = "hardware_h264_unavailable",
          message = lastMessage
        )
      }
    }
    if (scheduleCaptureStart) {
      scheduleRootHardwareH264CaptureStart("session_start_root_hardware_h264_capture", suppressBlackout = false)
    }
    return@session TicketSessionResponse(ok = true, state = "starting", message = lastMessage)
    }
  }

  private suspend fun runSessionStartSafetyPreflight(): TicketSessionStartSafetyPreflight {
    val acquiredLease = AtomicReference<TicketSecureWindowCaptureBypassLease?>(null)
    val armedHandle = AtomicReference<TicketHardwareH264ArmHandle?>(null)
    val results = runTicketSessionStartPreflight(
      portrait = { PhonePortraitLock.ensureVerifiedResult(inputRootExecutor) },
      secureCapture = {
        ensureSecureWindowCaptureBypassResultForSessionStart("session_start").also {
          if (it.acquiredLease) acquiredLease.set(it.lease)
        }
      },
      onPortraitComplete = { result ->
        if (result.getOrNull()?.verified == true && armedHandle.get() == null) {
          val source = currentDisplaySize()
          val size = TicketStreamSizing.rootHardwareH264(source.first, source.second)
          val health = rootHardwareH264CaptureEngine.snapshot()
          if (health.available && !hardwareMarkedUnreliable()) {
            armedHandle.compareAndSet(
              null,
              rootHardwareH264CaptureEngine.arm(
                source.first, source.second, size.width, size.height,
                TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE
              )
            )
          }
        }
      },
      onCancelled = {
        armedHandle.getAndSet(null)?.let {
          rootHardwareH264CaptureEngine.cancelArmedAndJoin(it, "session_start_preflight_cancelled")
        }
        acquiredLease.getAndSet(null)?.let {
          secureWindowCaptureBypassOwner.releaseAcquiredLease(it, "session_start_preflight_cancelled")
        }
      }
    )
    return TicketSessionStartSafetyPreflight(
      portrait = results.portrait.getOrElse { PhonePortraitLock.EnsureResult(false, "exception", 0L) },
      secureCapture = results.secureCapture.getOrElse {
        TicketSecureWindowCaptureBypassEnsureResult(outcome = "exception")
      },
      armHandle = armedHandle.get()
    )
  }

  private fun tryReuseActiveHardwareStreamBeforePreflight(): TicketSessionResponse? {
    if (!canReuseActiveHardwareStreamWithoutRootRevalidation()) {
      return null
    }
    return reuseActiveHardwareStream("session_start_active_preflight")
  }

  private fun reuseActiveHardwareStream(reason: String): TicketSessionResponse {
    updateTicketSessionState(TICKET_SESSION_LIVE, reason)
    lastSessionStopReason = null
    markViewerInput(reason)
    lastMessage = activeCaptureModeMessage()
    scheduleTicketBrightnessGuard(reason)
    startForegroundGuard()
    ensureEncoderIfPossible()
    return TicketSessionResponse(ok = true, state = "active", message = lastMessage)
  }

  private fun canReuseActiveHardwareStreamWithoutRootRevalidation(): Boolean {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264 || !hardwareCaptureVerified) return false
    if (ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION || lastRootH264BlankProbeResult == "secure_capture_blocked") return false
    if (ticketVisualActionCaptureLeaseActive || viviReauthCaptureLeaseActive || ticketVisualActionJobOwnershipActive) return false
    if (!secureWindowCaptureBypassOwner.snapshot().active || secureWindowCaptureBypassOwner.currentLease() == null) return false
    val now = SystemClock.elapsedRealtime()
    val health = rootHardwareH264CaptureEngine.snapshot(now)
    // A parked, admitted encoder is reusable even after its last picture expires.
    // Presentation still waits for a newly demanded, current picture.
    if (health.active && health.ordinaryCaptureDemandGated &&
      (!health.captureFrameExpected || health.captureFrameExpectedAgoMillis?.let {
        it < STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS
      } == true)) return true
    val frameAge = ageMillis(lastFrameSentAtMillis, now) ?: return false
    viviStateMemory.recentTicketDetailWithin(ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS)
      ?: return false
    return frameAge <= LIVE_FRAME_MAX_AGE_MILLIS
  }

  private suspend fun validateActiveTicketSessionBeforeReuse(reason: String): Boolean =
    observeViviState("active_session:$reason") != TicketViviRecoveryState.UNKNOWN_VIVI

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

  private fun activeHardwareStreamStartingForRecovery(nowMillis: Long): Boolean {
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      return false
    }
    if (ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION || lastRootH264BlankProbeResult == "secure_capture_blocked") {
      return false
    }
    val health = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    return TicketStreamStartupRecoveryPolicy.canContinueCurrentEncoder(
      encoderActive = health.active,
      encoderState = health.state,
      ordinaryCaptureDemandGated = health.ordinaryCaptureDemandGated,
      captureFrameExpected = health.captureFrameExpected,
      captureFrameExpectedAgoMillis = health.captureFrameExpectedAgoMillis,
      firstUsefulFramePending = hardwareStartupFirstUsefulFramePending(health),
      frameAgeMillis = ageMillis(lastFrameSentAtMillis, nowMillis),
      encoderStartAgeMillis = ageMillis(lastEncoderStartAtMillis, nowMillis),
      liveFrameMaxAgeMillis = LIVE_FRAME_MAX_AGE_MILLIS,
      startupWaitMillis = STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS
    )
  }

  private suspend fun noteClientDetachedLocked(reason: String): TicketSessionResponse {
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

      scheduleTicketBrightnessGuard("client_detached:$reason")
      releaseTicketScreenAwake()
      releaseBlackoutOverlaySuppression()
      hideBlackoutOverlay()
      if (!bypassRelease.ok) {
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "secure_capture_release_unproved")
        lastMessage = "Browser disconnected, but secure capture cleanup needs attention"
        recordTicketEvent("client_detached_cleanup_unproved", bypassRelease.detail)
      }
      return TicketSessionResponse(
        ok = bypassRelease.ok,
        state = if (bypassRelease.ok) "client_disconnected" else "needs_attention",
        message = lastMessage
      )
    }
    return TicketSessionResponse(ok = true, state = "inactive", message = lastMessage)
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
    response
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
    if (reason == "owner_cold_restart") cancelAndJoinRootHardwareH264CapturePreparation(reason)
    rootH264BlankProbeJob?.cancel()
    rootH264BlankProbeJob = null
    streamWatchdogJob?.cancel()
    streamWatchdogJob = null
    streamWatchdogStage = "idle"
    updateTicketSessionState(TICKET_SESSION_STOPPED, "session_stop_$reason")
    resetFrameEpoch("session_stop_$reason", active = false)
    cancelInactivityTimer()
    cancelForegroundGuard()
    val stopCapture: suspend () -> Unit = {
      rootHardwareH264CaptureEngine.stopAndJoin(reason)
    }
    val restoreSettings: suspend () -> TicketSecureWindowCaptureBypassReleaseResult = {
      disableSecureWindowCaptureBypass("session_stop:$reason")
    }
    val bypassRelease = if (reason == "owner_cold_restart" && ticketColdRestartBlocked) {
      completeTicketColdTeardown(stopCapture, restoreSettings)
    } else {
      stopCapture()
      restoreSettings()
    }

    controlCodeModeActive = false
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
    videoConnectionOwner.clear()
    scheduleTicketBrightnessGuard("session_stopped:$reason")
    releaseBlackoutOverlaySuppression()
    hideBlackoutOverlay()
    releaseTicketScreenAwake()
    closeAllClients("session_stop_$reason")
    return TicketSessionResponse(
      ok = bypassRelease.ok,
      state = if (bypassRelease.ok) "stopped" else "needs_attention",
      message = lastMessage
    )
  }

  private fun ticketSessionOpen(): Boolean {
    return streamActive
  }

  private fun updateTicketSessionState(next: String, reason: String) {
    val now = SystemClock.elapsedRealtime()
    val previous = ticketSessionState
    if (previous != next) {
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
              ticketVisualActionCaptureLeaseActive,
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
        controlCodePhoneMutationLane.withOwnership {
          val violation = foregroundViolationReason()
          cacheForegroundViolation(violation)
          if (!controlSensitiveWindowActive() && !isBudgetedTicketState(ticketSessionState) && violation != null) {
            updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, violation)
          }
        }
        delay(VIVI_STABLE_FOREGROUND_CHECK_MILLIS)
      }
      foregroundGuardJob = null
    }
  }

  private fun cancelForegroundGuard() {
    foregroundGuardJob?.cancel()
    foregroundGuardJob = null
    viviForegroundGraceUntilMillis = 0L

    cachedForegroundViolationReason = null
    cachedForegroundCheckedAtMillis = 0L
    controlCodeTransitionGraceUntilMillis = 0L
    controlCodeModeActive = false
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

  private suspend fun wakeTicketScreenForSessionStart(reason: String): Boolean {
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
    }
    val interactive = waitForTicketScreenInteractiveForWake()
    if (interactive) {
      PhoneAutomationServiceBridge.markNonTouchInput("ticket:wake_interactive:$reason")
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
      }
    }
  }

  private fun cacheForegroundViolation(reason: String?) {
    cachedForegroundViolationReason = reason
    cachedForegroundCheckedAtMillis = SystemClock.elapsedRealtime()
  }

  private suspend fun cachedForegroundViolation(): String? {
    val checkedAt = cachedForegroundCheckedAtMillis
    if (checkedAt <= 0L ||
      SystemClock.elapsedRealtime() - checkedAt > CACHED_FOREGROUND_MAX_AGE_MILLIS
    ) {
      cacheForegroundViolation(foregroundViolationReason(allowStartupSystemUi = false))
    }
    return cachedForegroundViolationReason
  }

  private suspend fun foregroundViolationReason(allowStartupSystemUi: Boolean = true): String? {
    if (!ticketScreenInteractive()) {
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

  private suspend fun observeViviState(reason: String): TicketViviRecoveryState {
    val visual = awaitStableTicketVisualActionObservation(
      reason = reason, timeoutMillis = TICKET_CURRENT_VIEW_TIMEOUT_MILLIS
    )
    val state = visual?.toRecoveryState() ?: TicketViviRecoveryState.UNKNOWN_VIVI
    viviStateMemory.record(
      state = state, ticketId = null,
      source = if (visual == null) "root_visual_empty" else "root_visual", reason = reason
    )
    return state
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
                return@launch
              }
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

  private fun ensureEncoderIfPossible() {
    if (ticketColdRestartBlocked) return
    if (!streamActive || (videoClients.isEmpty() && !streamCaptureNeededForControlCodeRequest())) {
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 && hardwareCaptureVerified) {
      ensureRootHardwareH264CaptureIfPossible()
    }
  }

  private fun prewarmRootHardwareH264CaptureIfPossible(reason: String) {
    if (ticketColdRestartBlocked) return
    if (!streamActive || (videoClients.isEmpty() && !streamCaptureNeededForControlCodeRequest())) {
      return
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
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
      scheduleStreamWatchdog()
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


  private fun handleRootHardwareH264CaptureStateChanged(health: TicketHardwareH264Health) {
    if (health.restartCount > lastObservedHardwareRestartCount) {
      lastObservedHardwareRestartCount = health.restartCount
      if (unexpectedHardwareEncoderRestart(health)) {
        noteHardwareReliabilityFailure("hardware_encoder_restart:${health.lastExitReason.orEmpty()}")
      }
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

  private fun restartActiveStreamEngine(reason: String) {
    TicketStreamStartupRecoveryPolicy.restartIfNeeded(
      lock = encoderLock,
      shouldRestart = {
        // A watchdog and a durable recovery may both observe the old stalled encoder.
        // Recheck after joining its owner: teardown can block before the new start is recorded.
        streamActive && !activeHardwareStreamStartingForRecovery(SystemClock.elapsedRealtime())
      },
      restart = {
        val verifiedBeforeRestart = hardwareCaptureVerified
        lastStreamWatchdogAction = "restart_capture_engine"
        lastStreamWatchdogReason = reason
        lastStreamRecoveryResult = "started"
        lastStreamRecoveryFailureReason = null
        lastStreamRecoveryAtMillis = SystemClock.elapsedRealtime()
        resetFrameEpoch("active_stream_engine_restart_$reason", active = true)
        streamSize?.let(::broadcastConfig)
        when (activeCaptureMode) {
          CAPTURE_MODE_ROOT_HARDWARE_H264 -> {
            rootHardwareH264CaptureEngine.restart(reason)
            hardwareCaptureVerified = verifiedBeforeRestart
            ensureRootHardwareH264CaptureIfPossible()
          }
        }
        scheduleStreamWatchdog()
      }
    )
  }

  private fun scheduleStreamWatchdog() {
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
        evaluateStreamWatchdog()
      }
      streamWatchdogStage = "idle"
      streamWatchdogJob = null
    }
  }

  private fun streamWatchdogShouldRun(): Boolean {
    return !ticketColdRestartBlocked && streamActive &&
      activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264 &&
      (videoClients.isNotEmpty() || streamCaptureNeededForControlCodeRequest()) &&
      ticketSessionState != TICKET_SESSION_NEEDS_ATTENTION &&
      lastRootH264BlankProbeResult != "secure_capture_blocked"
  }

  private fun evaluateStreamWatchdog() {
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
      }
      return
    }
    val encoderStartAgeMillis = ageMillis(lastEncoderStartAtMillis, nowMillis)
    if (encoderStartAgeMillis == null || encoderStartAgeMillis < STREAM_WATCHDOG_NO_ENCODER_RESTART_MILLIS) {
      streamWatchdogStage = "waiting_startup"
      return
    }
    val health = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    if (health.active && health.ordinaryCaptureDemandGated && !health.captureFrameExpected) {
      streamWatchdogStage = "demand_idle"
      return
    }
    if (health.active && health.ordinaryCaptureDemandGated &&
      health.captureFrameExpectedAgoMillis?.let { it < STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS } == true) {
      streamWatchdogStage = "waiting_demanded_frame"
      return
    }
    if (hardwareStartupFirstUsefulFramePending(health)) {
      streamWatchdogStage = "waiting_startup_first_useful_frame"
      return
    }
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
      {"type":"config","serverVersion":"$SERVER_VERSION","codec":"$codec","transport":"$transport","captureMode":"$activeCaptureMode","captureSource":${json.encodeToString(captureSource)},"captureMethod":${json.encodeToString(captureMethod)},"rootCapture":true,"frameEnvelope":"$FRAME_ENVELOPE_VERSION","frameDependencyMode":"$frameDependencyMode","streamEpoch":$configuredEpoch,"phoneUptimeMillis":$phoneUptimeMillis,"captureDemandVersion":${TicketCaptureDemandProtocol.VERSION},"captureDemandTtlMillis":${TicketCaptureDemandProtocol.TTL_MILLIS},"qualityProfile":"$qualityProfile","colorCorrection":${json.encodeToString(colorCorrection)},"colorStandard":${json.encodeToString(colorStandard)},"width":${size.width},"height":${size.height},"sourceWidth":${size.sourceWidth},"sourceHeight":${size.sourceHeight},"sourceLeftCrop":${size.sourceLeftCrop},"sourceTopCrop":${size.sourceTopCrop},"sourceRightCrop":${size.sourceRightCrop},"sourceBottomCrop":${size.sourceBottomCrop},"sourceVisibleWidth":${size.sourceVisibleWidth},"sourceVisibleHeight":${size.sourceVisibleHeight},"bitrate":$bitrate,"fps":$fps,"sourceFps":$sourceFps,"keyframeIntervalFrames":$keyframeIntervalFrames,"feedbackVersion":$feedbackVersion,"keyFrameIntervalMillis":$keyFrameInterval}
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
    videoClientSnapshot().forEach { sendConfigAndWarmStart(it, size) }
  }

  private fun sendConfigAndWarmStart(client: TicketWebSocket, size: TicketStreamSize) {
    synchronized(encoderLock) {
      val config = videoConfigSnapshot(size) ?: return
      videoSendStates[client]?.configure(config.epoch, config.message)
    }
  }

  private fun newVideoDeliveryWriter(client: TicketWebSocket) = TicketVideoDeliveryWriter(
    scope = serviceScope,
    maxFrameBytes = VIDEO_CLIENT_MAX_FRAME_BYTES,
    timeoutMillis = VIDEO_CLIENT_SLOW_CLOSE_MILLIS,
    nowMillis = SystemClock::elapsedRealtime,
    sendConfig = { message, current -> client.sendConfigAndAllowBinaryIf(message, current) },
    sendFrame = { bytes, current -> client.sendBinaryIf(bytes, current) },
    onConfigured = {
      lastConfigSentAtMillis = SystemClock.elapsedRealtime()
      sendCachedKeyFrameOrRequest(client, "video_client_config_ready")
    },
    onFailure = { reason ->
      client.close()
      recordTicketEvent("video_client_delivery_failed", "reason=$reason")
    },
    requestRefresh = { rootHardwareH264CaptureEngine.requestImmediateRefresh("video_client_invalid_frame") }
  )

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
    sourceFrame: TicketRootCaptureFrame,
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
      val frame = TicketTsf3FrameEnvelope.encode(
        sourceFrame.keyFrame,
        epoch,
        sequence,
        sourceFrame.captureAttemptId,
        sourceFrame.codecGeneration,
        sourceFrame.captureStartUs,
        sourceFrame.captureCompleteUs,
        sourceFrame.codecInputUs,
        sourceFrame.codecOutputUs,
        sourceFrame.recordEmissionUs,
        0L,
        0L,
        sourceFrame.payload
      )
      clearStartupDisconnectGrace()
      lastFrameSentAtMillis = sentAtMillis
      if (sourceFrame.keyFrame) {
        latestKeyFrame = TicketCachedKeyFrame(
          epoch = epoch,
          sequence = sequence,
          envelope = frame,
          cachedAtMillis = sentAtMillis,
          captureStartUs = sourceFrame.captureStartUs
        )
      }
      TicketVideoDeliveryFrame(
        bytes = frame,
        keyFrame = sourceFrame.keyFrame,
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
    videoSendStates[client]?.offer(TicketVideoDeliveryFrame(frame, keyFrame, epoch, sequence))
  }

  private fun handleRootHardwareH264CaptureFrame(frame: TicketRootCaptureFrame) {
    val acceptedGeneration = synchronized(encoderLock) {
      // The engine's earlier read-side check can precede a wait behind recovery teardown.
      // Revalidate the same source fence before this callback can take the replacement epoch.
      if (!rootHardwareH264CaptureEngine.isCurrentCaptureGeneration(frame)) {
        return@synchronized null
      }
      if (!frame.keyFrame) {
        droppedVideoFrames += 1L
        rootHardwareH264CaptureEngine.requestImmediateRefresh("service_rejected_unexpected_delta")
        return@synchronized null
      }
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
    if (!hardwareFrameBroadcastAllowed) {
      return
    }
    val firstVisibleFrame = sentFrames == 0L
    broadcastFrame(
      sourceFrame = frame,
      acceptedGeneration = acceptedGeneration
    ) ?: return
    if (hardwareCaptureVerified || firstVisibleFrame) {
      if (lastStreamRecoveryResult == "started") {
        lastStreamRecoveryResult = "succeeded"
        lastStreamRecoveryFailureReason = null
        lastStreamRecoveryAtMillis = SystemClock.elapsedRealtime()
        streamWatchdogStage = "healthy"
      }
    }
    if ((firstVisibleFrame || ticketSessionState == TICKET_SESSION_STARTING) && hardwareCaptureVerified) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "root_hardware_h264_first_visible_frame")
      lastMessage = "Ticket session is active through hardware H.264 capture"
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
        return@launch
      }
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
    if (!streamActive || activeCaptureMode == CAPTURE_MODE_IDLE) {
      pendingRootHardwareH264KeyFrame.offer(reason)
      return
    }
    if (reason == "viewport_changed" && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      ensureRootHardwareH264CaptureIfPossible()
    }
    if (activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      rootHardwareH264CaptureEngine.requestKeyFrame(reason)
    }
  }

  private fun hardwareStartupFirstUsefulFramePending(health: TicketHardwareH264Health): Boolean {
    return TicketStreamStartupRecoveryPolicy.waitingForFirstUsefulFrame(
      encoderActive = health.active,
      encoderState = health.state,
      encoderStartAgeMillis = health.lastStartAgoMillis,
      lastFrameAgeMillis = health.lastFrameAgoMillis,
      lastFrameSourceToServiceMillis = health.lastFrameSourceToServiceMillis,
      graceMillis = STREAM_WATCHDOG_STARTUP_FIRST_USEFUL_FRAME_GRACE_MILLIS,
      sourceUsefulnessMillis = ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
    )
  }

  private fun nextClientGeneration(): Long {
    return clientGenerationCounter.incrementAndGet()
  }

  private fun closeDuplicateViewerClients() {
    videoClientSnapshot().forEach(TicketWebSocket::close)
  }

  private fun videoClientSnapshot(): List<TicketWebSocket> {
    return synchronized(videoClients) {
      videoClients.toList()
    }
  }

  private fun closeAllClients(reason: String) {
    val clients = videoClientSnapshot()
    if (clients.isEmpty()) {
      return
    }
    clients.forEach { it.close() }
  }

  private fun cleanupInactiveClientsIfNeeded(reason: String) {
    val startupClientGraceActive = startupDisconnectGraceUntilMillis > SystemClock.elapsedRealtime()
    val protectedControlActive = controlCodeRequestActive()
    if (streamActive ||
      ticketSessionState == TICKET_SESSION_STARTING ||
      totalClientCount() == 0 ||
      startupClientGraceActive ||
      protectedControlActive
    ) {
      return
    }
    closeAllClients("inactive_stream_$reason")
  }

  private fun totalClientCount(): Int = videoClients.size

  private fun recordTicketEvent(event: String, detail: String = "") {
    enqueueTicketSpacetimeTraceEvent(event.take(96), TicketTracePrivacy.allowlistedFields(detail), "")
  }

  private fun enqueueTicketSpacetimeTraceEvent(
    event: String,
    detailFields: Map<String, String>,
    traceId: String
  ) {
    ticketSpacetimeWorker?.recordDiagnostic(event, detailFields, traceId)
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
      invalidateVideoDeliveryStatesForEpoch(streamEpoch)
    }
  }

  private fun invalidateVideoDeliveryStatesForEpoch(expectedEpoch: Long) {
    synchronized(videoSendStates) {
      videoSendStates.values.forEach { it.configure(expectedEpoch) }
    }
  }

  private fun ensureFrameEpoch(reason: String): Long {
    if (streamEpoch == 0L) {
      resetFrameEpoch(reason, active = true)
    }
    return streamEpoch
  }


  private fun streamPipelineSnapshot(nowMillis: Long): TicketStreamPipeline {
    val protection = secureWindowCaptureBypassOwner.snapshot()
    return TicketStreamPipeline(
      videoClients = videoClients.size,
      captureMode = activeCaptureMode,
      streamEpoch = streamEpoch,
      frameSequence = frameSequence,
      lastKeyFrameSequence = latestKeyFrame?.sequence ?: 0L,
      streamConfigured = streamSize != null,
      sentFrames = sentFrames,
      droppedVideoFrames = droppedVideoFrames,
      lastFrameSentAgoMillis = ageMillis(lastFrameSentAtMillis, nowMillis),
      secureWindowCaptureBypassActive = protection.active,
      secureWindowCaptureBypassMessage = protection.message,
      rootH264BlankProbeResult = lastRootH264BlankProbeResult
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

  private fun health(): TicketStreamHealth {
    cleanupInactiveClientsIfNeeded("health")
    val nowMillis = SystemClock.elapsedRealtime()
    val hardwareCapture = rootHardwareH264CaptureEngine.snapshot(nowMillis)
    val vivi = TicketPackageSupport.isInstalled(this, TicketScreenConfig.VIVI_PACKAGE)
    val ok = running.get() && vivi && hardwareCapture.available
    val recoverySnapshot = TicketRecoveryHealth(
      streamStage = streamWatchdogStage,
      lastWatchdogAction = lastStreamWatchdogAction,
      lastStreamRecoveryResult = lastStreamRecoveryResult,
      lastStreamRecoveryReason = lastStreamWatchdogReason,
      lastStreamRecoveryAgoMillis = ageMillis(lastStreamRecoveryAtMillis, nowMillis),
      lastStreamRecoveryFailureReason = lastStreamRecoveryFailureReason,
    )
    val viviHealth = viviStateMemory.health(nowMillis)
    return TicketStreamHealth(
      ok = ok,
      serverVersion = SERVER_VERSION,
      phoneUptimeMillis = nowMillis,
      sessionState = ticketSessionState,
      serverRunning = running.get(),
      viviInstalled = vivi,
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
        totalDurationMillis = lastControlCodeRequestDurationMillis,
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
          protectionMode = lease.protectionMode,
          physicalVisibleWindowRemainingMillis = (
            PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().deadlineUptimeMillis - SystemClock.uptimeMillis()
          ).coerceAtLeast(0L),
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
          lastVerifierDurationMillis = lease.lastVerifierDurationMillis
        )
      },
      hardwareH264 = hardwareCapture,
      recovery = recoverySnapshot,
      ticketState = TicketControlStateHealth(
        state = ticketSessionState,
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

  private suspend fun launchViviForWake(reason: String) {
    runPanelDarkCommand("vivi_launch:$reason") {
      launchVivi()
      RootResult(exitCode = 0, stdout = "", stderr = "", command = "vivi_launch", durationMs = 0L)
    }
  }

  private suspend fun viviFocusedForFastPublicOpen(reason: String): Boolean {
    if (!ticketScreenInteractive()) {
      return false
    }
    val focused = focusedWindowSnapshot()
    val focusedVivi = focused?.contains(TicketScreenConfig.VIVI_PACKAGE) == true
    return focusedVivi
  }

  private suspend fun prepareViviForRootHardwareH264FastOpen(
    reason: String,
    wakeStartedAtMillis: Long
  ): Boolean {
    val deadline = wakeStartedAtMillis + TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS
    var focused = viviFocusedForFastPublicOpen(reason)
    if (!focused && SystemClock.elapsedRealtime() < deadline) {
      launchViviForWake(reason)
      focused = viviFocusedForFastPublicOpen("post_launch:$reason")
    }
    // Foreground proof permits provisional pictures only. Two fresh typed frames own readiness;
    // later mutations still require their separate exact ticket and physical-input fences.
    if (focused && streamActive && activeCaptureMode == CAPTURE_MODE_ROOT_HARDWARE_H264) {
      hardwareFrameBroadcastAllowed = true
    }
    val observation = if (focused) {
      awaitStableTicketVisualActionObservation(
        reason = "fast_open_current_visual:$reason",
        timeoutMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
        allowUnknown = false, currentOnly = true
      )
    } else null
    val state = if (!focused) TicketViviRecoveryState.OUTSIDE_VIVI
      else observation?.toRecoveryState() ?: TicketViviRecoveryState.UNKNOWN_VIVI
    val ready = observation != null && observation.state != TicketVisualPhoneState.UNKNOWN
    viviStateMemory.record(state, null, "ticket_action_v3_current_visual", "fast_public_open:$reason")
    updateTicketSessionState(
      if (ready) TICKET_SESSION_LIVE else TICKET_SESSION_NEEDS_ATTENTION,
      if (ready) "root_hardware_h264_vivi_ready_fast_$reason"
      else "root_hardware_h264_fast_open_${state.name.lowercase()}"
    )
    return ready
  }

  private fun scheduleRootHardwareH264CaptureStart(reason: String, suppressBlackout: Boolean) {
    // Starting the root encoder does not mutate ViVi. Begin it before the
    // serialized phone-mutation lane starts its current visual
    // proof so fresh frames can be ready as soon as foreground is confirmed.
    val job = synchronized(rootHardwareH264CapturePreparationLock) {
      val existing = rootHardwareH264CapturePreparationJob
      if (existing != null && !existing.isCompleted) {
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
    val wakeStartedAtMillis = SystemClock.elapsedRealtime()
    prewarmRootHardwareH264CaptureIfPossible("session_start_prewarm:$reason")
    wakeTicketScreenForSessionStart(reason)
    val prepareResult = prepareViviForRootHardwareH264FastOpen(reason, wakeStartedAtMillis)
    if (!streamActive || activeCaptureMode != CAPTURE_MODE_ROOT_HARDWARE_H264) {
      recordTicketEvent("${modeName}_prepare_ignored", "session_inactive:$reason")
      return@prepare false
    }
    if (!prepareResult) {
      rootHardwareH264CaptureEngine.stopAndJoin("phone_not_ready:$reason")
      streamActive = false
      hardwareCaptureVerified = false
      hardwareFrameBroadcastAllowed = false
      activeCaptureMode = CAPTURE_MODE_IDLE
      resetFrameEpoch("phone_not_ready:$reason", active = false)
      lastMessage = "Phone not ready: root could not confirm the ViVi ticket screen"
      return@prepare false
    }
    hardwareCaptureVerified = true
    hardwareFrameBroadcastAllowed = true
    ensureEncoderIfPossible()
    requestKeyFrame("vivi_ready_encoder_start:$reason")
    updateTicketSessionState(TICKET_SESSION_STARTING, "${modeName}_waiting_first_visible_frame")
    lastMessage = "Waiting for the first visible hardware H.264 frame"
    true
    }
  }

  private suspend fun verifyRootHardwareSecureCapturePixelsVisible(reason: String): Boolean {
    val snapshot = rootHardwareH264CaptureEngine.snapshot()
    val recentFrame = snapshot.lastFrameAgoMillis?.let { it <= SECURE_CAPTURE_PROBE_TIMEOUT_MILLIS } == true
    val visible = snapshot.lastVisibilityCheckResult == "visible" ||
      (snapshot.active && recentFrame && snapshot.blankFrameFailures == 0L)
    return visible
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
    } else {
      recordTicketEvent("ticket_brightness_restore_failed", "reason=$reason ${safeRootFailure(result)}")
    }
  }

  private suspend fun canForwardRemoteInput(): Boolean {
    if (!streamActive) {
      inputGateReason = "no_active_control"
      return false
    }
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT) {
      inputGateReason = "remote_input_canceled_after_control_exit"
      recordTicketEvent("remote_input_canceled_after_control_exit", inputGateReason)
      return false
    }
    val violation = cachedForegroundViolation() ?: return true
    inputGateReason = violation
    return false
  }

  private fun markControlCodeTransition(reason: String) {
    pauseForegroundGuardForControlCode(reason)
    controlCodeTransitionGraceUntilMillis = SystemClock.elapsedRealtime() + CONTROL_CODE_TRANSITION_GRACE_MILLIS
    updateTicketSessionState(TICKET_SESSION_CONTROL_TRANSITION, reason)
  }

  private fun pauseForegroundGuardForControlCode(reason: String) {
    val job = foregroundGuardJob
    if (job?.isActive != true) return
    job.cancel()
    foregroundGuardJob = null
  }

  private fun controlSensitiveWindowActive(): Boolean {
    val now = SystemClock.elapsedRealtime()
    return controlCodeModeActive ||
      controlCodeRequestActive() ||
      ticketVisualActionJobOwnershipActive ||
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
      pendingBrowserCapture = controlCodeCaptureWait != null,
      controlModeActive = controlCodeModeActive,
      sessionState = ticketSessionState
    )
  }

  private fun markControlCodeModeEntered(reason: String) {
    if (controlCodeModeActive) {
      return
    }
    if (ticketSessionState == TICKET_SESSION_CONTROL_EXIT || ticketSessionState == TICKET_SESSION_NEEDS_ATTENTION) {
      return
    }
    controlCodeModeActive = true
    updateTicketSessionState(TICKET_SESSION_CONTROL_ACTIVE, reason)
  }

  private fun controlCodeCleanupHasPanelOwner(): Boolean {
    // Surface proof does not clear the durable fence; the lease's finalization owns that commit.
    val owned = activeTicketActionPanelDarkLease != null
    if (!owned) {
      preserveControlCodeCleanupCheckpoint("cleanup_without_panel_finalization")
      updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "control_code_cleanup_panel_finalization_required")
    }
    return owned
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
    inputGateReason = "control_exit_popup_closed"
    return true
  }

  private fun publishControlCodeReadyAfterPanelFinalization(requestId: String) {
    if (streamActive) {
      updateTicketSessionState(TICKET_SESSION_LIVE, "control_exit_popup_closed")
      startForegroundGuard()
    }
    enqueueTicketCodePublication(TicketCodePublication(
      requestId, TicketCodePublicationKind.READY,
      cleanupRevision = phoneControlState.updates.value.contextRevision
    ))
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
        }
      }
    }
  }

  private fun beginControlCodeBrowserCaptureWait(requestId: String, epoch: Long, sequence: Long) {
    controlCodeResultEncoderRefreshActive = true
    synchronized(controlCodeBrowserCaptureLock) {
      check(controlCodeCaptureWait == null) { "control_code_capture_wait_already_owned" }
      controlCodeCaptureWait = ControlCodeCaptureWait(requestId, epoch to sequence)
    }
    lastControlCodeBrowserCaptureReason = "waiting"
    lastControlCodeBrowserCaptureCompletedAtMillis = 0L
  }

  private fun clearControlCodeBrowserCaptureWait(requestId: String) {
    synchronized(controlCodeBrowserCaptureLock) {
      controlCodeCaptureWait?.takeIf { it.requestId == requestId }?.let {
        controlCodeCaptureWait = null
        it.acknowledgement.cancel()
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
    if (cleanRequestId.isBlank()) return
    val ack = ControlCodeBrowserCaptureAck(
      requestId = cleanRequestId,
      ok = ok,
      reason = reason.trim().ifBlank { if (ok) "browser_capture_confirmed" else "browser_capture_failed" },
      frameEpoch = frameEpoch,
      frameSequence = frameSequence,
      receivedAtMillis = SystemClock.elapsedRealtime()
    )
    val accepted = synchronized(controlCodeBrowserCaptureLock) {
      val pending = controlCodeCaptureWait
      // The database can accept an earlier offered picture before a replacement
      // is published. That exact acknowledgement remains valid when it arrives.
      pending?.requestId == cleanRequestId &&
        (!ok || (frameEpoch to frameSequence) in pending.offeredFrames) &&
        pending.acknowledgement.complete(ack)
    }
    if (accepted) {
      lastControlCodeBrowserCaptureReason = ack.reason
      lastControlCodeBrowserCaptureCompletedAtMillis = ack.receivedAtMillis
    }
    recordTicketEvent(
      if (accepted) "control_code_browser_capture_received" else "control_code_browser_capture_ignored",
      "request=$cleanRequestId ok=$ok reason=${ack.reason} epoch=$frameEpoch sequence=$frameSequence"
    )
  }

  private suspend fun waitForControlCodeBrowserCapture(
    requestId: String,
    delivery: FastControlCodeDelivery
  ): ControlCodeBrowserCaptureAck = coroutineScope {
    val pending = synchronized(controlCodeBrowserCaptureLock) {
      requireNotNull(controlCodeCaptureWait?.takeIf { it.requestId == requestId })
    }
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadlineMillis = startedAtMillis + CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS

    // The acknowledgement wakes the request immediately, including while an
    // optional replacement picture is being observed. This child never inputs.
    val refresh = launch {
      while (true) {
        delay(3_000L)
        if (SystemClock.elapsedRealtime() + 3_000L >= deadlineMillis) break
        val marker = markerFirstControlCodeFrameWatermarkForBrowser(
          "result_delivery_refresh",
          TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE
        ) ?: continue
        val replace = synchronized(controlCodeBrowserCaptureLock) {
          if (controlCodeCaptureWait === pending && !pending.acknowledgement.isCompleted &&
            marker != pending.marker) {
            pending.marker = marker
            pending.offeredFrames.add(marker)
            true
          } else false
        }
        if (replace) {
          publishGeneratedControlCode(requestId, marker.first, marker.second,
            delivery.resultProof, System.currentTimeMillis())
        }
      }
    }
    try {
      val acknowledged = withTimeoutOrNull(CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS) {
        pending.acknowledgement.await()
      }
      val nowMillis = SystemClock.elapsedRealtime()
      acknowledged ?: ControlCodeBrowserCaptureAck(
        requestId, false, "control_code_browser_capture_ack_timeout", 0L, 0L, nowMillis
      ).also {
        lastControlCodeBrowserCaptureReason = it.reason
        lastControlCodeBrowserCaptureCompletedAtMillis = nowMillis
        recordTicketEvent(it.reason, "request=$requestId")
      }
    } finally {
      refresh.cancel()
      withContext(NonCancellable) { refresh.join() }
      controlCodeResultEncoderRefreshActive = false
    }
  }

  private suspend fun handleGenerateControlCode(
    requestId: String,
    digits: String,
    fastRevision: String = ""
  ) {
    val cleanRequestId = requestId.trim()
    val cleanDigits = digits.trim()
    val cleanFastRevision = fastRevision.trim()
    // The scoped Ticket subscription and generate_control_code command identify this operation.
    // Exact phone context, ViVi foreground and protected input are still proved at execution.
    val rejection = when {
      cleanRequestId.isBlank() -> "missing_request_id"
      !CONTROL_CODE_REQUEST_DIGITS_REGEX.matches(cleanDigits) -> "invalid_code"
      else -> null
    }
    if (rejection != null) {
      sendControlCodeFailure(cleanRequestId, rejection, 0L, cleanupPending = false)
      return
    }
    if (controlCodeRequestDuplicateActiveOrCompleted(cleanRequestId)) return
    val expectedPhoneContext = if (cleanFastRevision.startsWith("pc-")) {
      phoneControlState.exactContext(cleanFastRevision, SystemClock.elapsedRealtime())
    } else null
    if (expectedPhoneContext == null) {
      sendControlCodeFailure(cleanRequestId, "phone_control_context_changed", 0L, cleanupPending = false)
      return
    }
    val pendingCleanup = when {
      controlCodeSignatureCleanupRequired -> "control_code_cleanup_pending"
      activeControlCodeKeyboardClamp != null -> "control_code_keyboard_cleanup_pending"
      else -> null
    }
    if (pendingCleanup != null) {
      sendControlCodeFailure(cleanRequestId, pendingCleanup, 0L, cleanupPending = true)
      return
    }

    // Publish ownership before any session-start command can race this request.
    // The Spacetime desired-start lane will then coalesce onto the same phone
    // preparation instead of starting a second probe or session.
    lastControlCodeRequestId = cleanRequestId
    lastControlCodeRequestStatus = "queued"
    lastControlCodeRequestReason = null
    activeControlCodeRawDetailAnchor = ""
    activeControlCodeRawDetailState = TicketVisualPhoneState.UNKNOWN
    activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
    activeControlCodeVisualSignatureExpiresAtMillis = 0L
    persistControlCodeSignatureCleanupRequired(false)
    var refreshRequested = false
    var keyboardClampLease: RequestScopedKeyboardClampLease? = null
    var panelDarkLease: TicketActionPanelDarkLease? = null
    var controlClaimOwned = false
    var startedAtMillis = 0L
    try {
      sessionMutex.withLock {
        streamStartAdmission.claim()
        controlClaimOwned = true
        clientDisconnectStopJob?.cancel()
        clientDisconnectStopJob = null
      }
      cancelForegroundGuard()
      cancelAndJoinRootHardwareH264CapturePreparation("control_code_request_admitted")
      startedAtMillis = SystemClock.elapsedRealtime()
      // One independent frame may be requested while this request waits for the phone lane.
      // Display and input mutations remain behind the confirmed raw-panel lease.
      refreshRequested = rootHardwareH264CaptureEngine.requestImmediateRefresh("control_code_browser_dispatch")
      controlCodePhoneMutationLane.withOwnership {
        if (controlCodeRequestDuplicateActiveOrCompleted(cleanRequestId)) return@withOwnership

        var panelDarkLeaseAcquired = false
        var resultSent = false
        var generatedResultDelivered = false
        var deferredCleanupOk: Boolean? = null
        var deferredPhoneSurfaceCleaned = false
        var deferredCleanupReason = ""
        var cleanupAttemptReason: String? = null
        var browserCaptureFailure: String? = null
        var reason = "control_code_request_session_unavailable"
        try {

        val panelOwnerId = "control_code:${cleanRequestId.takeLast(24)}"
        panelDarkLease = newTicketPanelDarkLease(panelOwnerId)
          .also { lease -> activeTicketActionPanelDarkLease = lease }
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
          sendControlCodeFailure(
            cleanRequestId, failureReason, startedAtMillis,
            cleanupPending = false
          )
          return@withOwnership
        }

        keyboardClampLease = RequestScopedKeyboardClampLease(
          requestId = cleanRequestId,
          expectedPackageName = TicketScreenConfig.VIVI_PACKAGE,
          commandTimeoutMillis = CONTROL_CODE_KEYBOARD_CLAMP_COMMAND_TIMEOUT_MILLIS,
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
        lastControlCodeRequestCompletedAtMillis = 0L
        sendControlCodeProgress(cleanRequestId, "running", "phone_request_started")
        phoneControlState.invalidate("control_code_request", busy = true)
        try {
          if (!keyboardClampAcquired) {
            reason = "control_code_keyboard_clamp_unavailable"
          } else {
          if (canForwardRemoteInput()) {

            if (!refreshRequested) {
              refreshRequested = rootHardwareH264CaptureEngine.requestImmediateRefresh("control_code_gate_ready")
            }
            val delivery = runFastControlCodeDeliveryForRequest(
              cleanDigits,
              expectedPhoneContext,
              onFirstInput = {
                recordTicketEvent("control_code_first_input",
                  "phone_to_first_input_ms=${SystemClock.elapsedRealtime() - startedAtMillis}")
              }
            )
            reason = delivery.reason
            cleanupAttemptReason = "control_code_request_failed_return_raw"
            if (delivery.ok) {
              lastControlCodeRequestStatus = "running"
              lastControlCodeRequestReason = "generated_cleanup_pending"
              beginControlCodeBrowserCaptureWait(cleanRequestId, delivery.streamEpoch, delivery.minFrameSequence)

              publishGeneratedControlCode(cleanRequestId, delivery.streamEpoch,
                delivery.minFrameSequence, delivery.resultProof, delivery.resultProofAtMillis)
              resultSent = true
              generatedResultDelivered = true
              val capture = waitForControlCodeBrowserCapture(cleanRequestId, delivery)
              cleanupAttemptReason = if (capture.ok) "browser_capture_confirmed" else capture.reason
              browserCaptureFailure = if (capture.ok) null else "browser_capture_failed_${capture.reason}"
            } else {
              sendControlCodeFailure(
                cleanRequestId, reason, startedAtMillis, cleanupPending = true
              )
              resultSent = true
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
            sendControlCodeFailure(
              cleanRequestId, reason, startedAtMillis, cleanupPending = true
            )
            resultSent = true
          }
          cleanupAttemptReason = "control_code_request_exception_return_raw"
          if (generatedResultDelivered) browserCaptureFailure = reason
        } finally {
          clearControlCodeBrowserCaptureWait(cleanRequestId)
        }
        // Cleanup is outside the delivery catch: an uncertain close never starts another close.
        cleanupAttemptReason?.let { cleanupReason ->
          val cleaned = try {
            returnControlCodeSurfaceToRawTicket(
              cleanupReason, generatedResultObserved = generatedResultDelivered
            )
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Throwable) {
            recordTicketEvent("control_code_cleanup_failed", safeErrorDetail(error))
            false
          }

          deferredPhoneSurfaceCleaned = cleaned
          deferredCleanupOk = cleaned && browserCaptureFailure == null
          deferredCleanupReason = browserCaptureFailure ?: when {
            !cleaned -> "control_code_cleanup_attention_needed"
            generatedResultDelivered -> "return_to_raw_complete"
            else -> "ticket_detail"
          }
        }
        lastControlCodeRequestDurationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
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
            PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction()
            completed
          }
          if (panelDarkLeaseAcquired) {
            val leaseState = panelFinalization?.snapshot ?: panelDarkLease?.snapshot()
              ?: TicketActionPanelDarkLeaseSnapshot()
            val safe = panelFinalization?.safe == true && keyboardClampReleased
            val committed = safe &&
              ticketControlCodeCleanupMayCommitAfterPanelFinalization(
                deferredPhoneSurfaceCleaned, panelFinalization
              ) && commitControlCodeCleanStateAfterPanelFinalization(
                "control_code_request:$deferredCleanupReason"
              )
            val afterDispatch = leaseState.mutationMayHaveDispatched
            val cleanupPending = (afterDispatch && !committed) || !keyboardClampReleased
            if (cleanupPending || (!safe &&
                (deferredCleanupOk != null || controlCodeSignatureCleanupRequired))) {
              preserveControlCodeCleanupCheckpoint(when {
                !keyboardClampReleased -> "control_code_keyboard_restore_unproved"
                !safe -> "control_code_request_unsafe_finalization"
                else -> "control_code_request_checkpoint_clear_unproved:$deferredCleanupReason"
              })
            }
            val failure = when {
              !keyboardClampReleased -> "control_code_keyboard_restore_failed"
              !safe && afterDispatch -> "control_code_cleanup_attention_needed"
              !safe -> "control_code_panel_dark_unavailable"
              deferredCleanupOk == null -> null
              deferredPhoneSurfaceCleaned && !committed -> "control_code_cleanup_checkpoint_clear_unproved"
              deferredCleanupOk != true || !committed -> deferredCleanupReason
              else -> null
            }
            if (!resultSent) {
              sendControlCodeFailure(
                cleanRequestId, if (safe) reason else failure!!, startedAtMillis, cleanupPending = cleanupPending
              )
            }
            val publishCleanup = deferredCleanupOk != null ||
              (!safe && (!keyboardClampReleased || afterDispatch))
            if (publishCleanup) {
              if (failure == null) publishControlCodeReadyAfterPanelFinalization(cleanRequestId)
              sendControlCodeCleanup(
                cleanRequestId, failure == null, failure ?: deferredCleanupReason, startedAtMillis
              )
            }
            if (failure != null || (publishCleanup && generatedResultDelivered)) {
              lastControlCodeRequestStatus = if (failure == null) "succeeded" else "failed"
              lastControlCodeRequestReason = failure ?: "generated"
              lastControlCodeRequestCompletedAtMillis = SystemClock.elapsedRealtime()
            }
            if (failure != null) phoneControlState.invalidate(failure, busy = true)
          }
        }
      }
    } catch (error: Throwable) {
      // The inner owner has already restored keyboard/panel protection. Cancellation
      // before that owner starts must also retire the queued request and its claim.
      if (lastControlCodeRequestId == cleanRequestId &&
        lastControlCodeRequestStatus in setOf("queued", "running")
      ) {
        val cleanupPending = controlCodeSignatureCleanupRequired ||
          activeControlCodeKeyboardClamp != null ||
          panelDarkLease?.snapshot()?.mutationMayHaveDispatched == true
        if (cleanupPending) preserveControlCodeCleanupCheckpoint("control_code_request_interrupted")
        sendControlCodeFailure(
          cleanRequestId, "control_code_request_failed", startedAtMillis,
          cleanupPending = cleanupPending
        )
      }
      throw error
    } finally {
      rootHardwareH264CaptureEngine.clearTransientControlCodeVisualProbes()
      scheduleControlCodeVisualSignatureExpiry()
      if (controlClaimOwned) releaseControlCodeAutomationForRequest()
    }
  }

  private suspend fun runFastControlCodeDeliveryForRequest(
    cleanDigits: String,
    expectedPhoneContext: TicketVisualActionObservation,
    onFirstInput: () -> Unit
  ): FastControlCodeDelivery {
    val transaction = openControlCodePopupFastForRequest(expectedPhoneContext, onFirstInput)
      ?: return FastControlCodeDelivery(false, inputGateReason.ifBlank { "control_code_popup_timeout" })
    if (!enterAndSubmitControlCodeDigitsFastForRequest(cleanDigits, transaction)) {
      return FastControlCodeDelivery(false, inputGateReason.ifBlank { "control_code_input_submit_failed" })
    }
    return waitForGeneratedControlCodeResultAfterSubmit().also {
      if (it.ok) {

        markViewerInput("control_code_request_digits")
      }
    }
  }

  private suspend fun openControlCodePopupFastForRequest(
    expectedPhoneContext: TicketVisualActionObservation,
    onFirstInput: () -> Unit
  ): FastControlCodePopupTransaction? {
    val initialVisualDetail = awaitStableTicketVisualActionObservation(
      "control_code_before_button",
      TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS
    )
    if (initialVisualDetail?.state !in setOf(
        TicketVisualPhoneState.ACTIVATED_DETAIL,
        TicketVisualPhoneState.UNACTIVATED_DETAIL
      )) {
      inputGateReason = "control_code_button_visual_unproved"
      recordTicketEvent("control_code_request_fast_fail", "reason=button_visual_unproved")
      return null
    }
    val visualDetail = initialVisualDetail ?: return null
    if (visualDetail.state != expectedPhoneContext.state ||
      visualDetail.currentAnchor != expectedPhoneContext.currentAnchor
    ) {
      inputGateReason = "phone_control_context_changed"
      return null
    }

    if (!ticketControlCodeDetailAnchorIsValid(visualDetail.currentAnchor)) {
      inputGateReason = "control_code_detail_identity_unproved"
      recordTicketEvent("control_code_request_fast_fail", "reason=detail_identity_unproved")
      return null
    }
    val detectedControlBounds = visualDetail?.controlCodeBounds ?: run {
      inputGateReason = "control_code_button_bounds_unproved"
      return null
    }
    activeControlCodeRawDetailAnchor = visualDetail.currentAnchor
    activeControlCodeRawDetailState = visualDetail.state
    activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
    activeControlCodeVisualSignatureExpiresAtMillis = SystemClock.elapsedRealtime() +
      CONTROL_CODE_VISUAL_SIGNATURE_TTL_MILLIS
    if (!persistControlCodeSignatureCleanupRequired(true)) {
      activeControlCodeRawDetailAnchor = ""
      activeControlCodeRawDetailState = TicketVisualPhoneState.UNKNOWN
      activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE
      activeControlCodeVisualSignatureExpiresAtMillis = 0L
      inputGateReason = "control_code_cleanup_checkpoint_failed"
      return null
    }
    val mappedControlBounds = ticketVisualProbeBoundsToDevice(detectedControlBounds)
    val action = TicketViviPageAction(
      x = (mappedControlBounds.left + mappedControlBounds.right) / 2,
      y = (mappedControlBounds.top + mappedControlBounds.bottom) / 2,
      reason = "control_code_visual_anchor",
      bounds = "${mappedControlBounds.left},${mappedControlBounds.top},${mappedControlBounds.right},${mappedControlBounds.bottom}"
    )
    inputGateReason = action.reason
    markControlCodeTransition("control_code_request_open_popup_detected")
    onFirstInput()
    val tap = runFastNonTouchInput(
      "input tap ${action.x} ${action.y}",
      "control_code_request_open_popup_detected"
    )
    if (!tap.ok) {
      inputGateReason = "control_code_button_tap_failed"
      return null
    }

    val layoutProbe = awaitStableControlCodeSubmitLayout()
    val layout = layoutProbe?.result
    if (layout == TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY ||
      layout == TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY
    ) {
      val transaction = visualControlCodePopupTransaction(layoutProbe) ?: run {
        inputGateReason = "control_code_popup_geometry_unproved"
        return null
      }
      markOpenedControlCodePopupTransactionReady(
        source = "rooted_visual_probe",
      )
      return transaction
    }
    inputGateReason = "control_code_popup_bounds_unavailable"
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
    requireGeometry: Boolean = true,
    afterTyping: Boolean = false
  ): TicketControlCodeVisualProbe? {
    val proof = TicketControlCodeSubmitVisualProof()
    val samples = if (afterTyping) CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES
      else CONTROL_CODE_FAST_INTERACTION_RETRY_COUNT + 2
    repeat(samples) { attempt ->
      val started = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestControlCodeSubmitVisualProbe(
        "control_code_popup_visual_${attempt + 1}"
      )
      val current = probeId?.let {
        waitForFreshControlCodeVisualProbe(started, it, CONTROL_CODE_SUBMIT_VISUAL_PROBE_WAIT_MILLIS)
      }
      if (afterTyping && (probeId == null ||
          current?.result == TicketControlCodeVisualClassifier.CONTROL_POPUP_KEYBOARD_READY)) return null
      // One pair proves the state and exact targets for either the bounded retype
      // or submission; neither uses geometry retained from before typing.
      if (proof.observe(current, requireGeometry)) return current
      if (attempt + 1 < samples) delay(if (afterTyping) CONTROL_CODE_SUBMIT_VISUAL_SAMPLE_GAP_MILLIS
        else CONTROL_CODE_FAST_POLL_MILLIS)
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
      submit = action(submitBounds, "control_code_visual_submit")
    )
  }

  private fun markOpenedControlCodePopupTransactionReady(
    source: String
  ) {
    inputGateReason = "control_code_popup_transaction_ready:$source"

    markControlCodeModeEntered("control_code_request_popup_transaction_$source")
  }

  private suspend fun enterAndSubmitControlCodeDigitsFastForRequest(
    digits: String,
    transaction: FastControlCodePopupTransaction,
  ): Boolean {
    var typeResult = executeRootControlCodeType(digits, transaction, "control_code_root_input_manager_type")
    if (!typeResult.ok) {
      inputGateReason = "control_code_root_input_manager_type_failed"
      recordTicketEvent(
        "control_code_root_input_manager_type_failed",
        "exit_code=${typeResult.exitCode} timeout=${typeResult.exitCode == 124} duration_ms=${typeResult.durationMs}"
      )
      return false
    }

    var valueProof = awaitStableControlCodeSubmitLayout(afterTyping = true)
    if (valueProof?.result == TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY) {
      delay(CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS)

      valueProof = awaitStableControlCodeSubmitLayout(afterTyping = true)
    }
    if (valueProof?.result == TicketControlCodeVisualClassifier.CONTROL_POPUP_STATIC_READY) {
      val retypeTransaction = visualControlCodePopupTransaction(valueProof) ?: return false
      typeResult = executeRootControlCodeType(
        digits,
        retypeTransaction,
        "control_code_root_input_manager_retype"
      )
      valueProof = if (typeResult.ok) {
        awaitStableControlCodeSubmitLayout(afterTyping = true)
      } else {
        recordTicketEvent(
          "control_code_root_input_manager_retype_failed",
          "exit_code=${typeResult.exitCode} timeout=${typeResult.exitCode == 124} duration_ms=${typeResult.durationMs}"
        )
        null
      }
    }
    val submitTransaction = valueProof?.takeIf {
      it.result == TicketControlCodeVisualClassifier.CONTROL_POPUP_VALUE_READY
    }?.let(::visualControlCodePopupTransaction)
    if (submitTransaction == null) {
      inputGateReason = "control_code_submit_target_unproved"
      recordTicketEvent("control_code_submit_target_unproved", "fresh_value_and_ok_geometry_required")
      return false
    }

    val submitted = tapControlCodePointWithoutKeyboard(
      submitTransaction.submit.x,
      submitTransaction.submit.y,
      "control_code_root_submit_after_value_proof"
    )
    if (!submitted) {
      recordTicketEvent(
        "control_code_root_submit_dispatch_uncertain",
        "proved_value_submit_tap_unacknowledged; reconciling_visual_state"
      )
    }
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
    return runFastNonTouchInput(
      "input tap $x $y", reason, CONTROL_CODE_ROOT_SUBMIT_TIMEOUT_MILLIS.milliseconds
    ).ok
  }

  private suspend fun waitForGeneratedControlCodeResultAfterSubmit(): FastControlCodeDelivery {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val deadline = startedAtMillis + CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS
    val proof = TicketGeneratedWithCloseProof()
    var lastState = "unknown"
    while (SystemClock.elapsedRealtime() < deadline) {
      val probeStarted = SystemClock.elapsedRealtime()
      val probeId = rootHardwareH264CaptureEngine.requestControlCodeCleanupVisualProbe("control_code_after_submit")
        ?: break
      val probe = waitForFreshControlCodeVisualProbe(probeStarted, probeId,
        minOf(CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS, (deadline - probeStarted).coerceAtLeast(1L)))
      lastState = probe?.result ?: "unknown"
      if (probe != null && proof.observe(probe)) {
        activeControlCodeVisualResultMode = TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE

        markControlCodeModeEntered("control_code_request_generated_with_close_after_submit")
        sendControlCodeProgress(lastControlCodeRequestId.orEmpty(), "generated", "generated_waiting_for_picture")
        val marker = markerFirstControlCodeFrameWatermarkForBrowser(
          "control_code_result_after_phone_visual_proof",
          TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE, probe
        ) ?: return FastControlCodeDelivery(false, "control_code_generated_frame_watermark_unavailable")
        return FastControlCodeDelivery(
          ok = true, reason = "generated", streamEpoch = marker.first, minFrameSequence = marker.second,
          resultProof = "phone_visual_generated_with_close", resultProofAtMillis = System.currentTimeMillis()
        )
      }
      // Observe the single submit attempt to its deadline. Seeing the old popup
      // cannot prove that a delayed submit did not execute, so it never authorizes another tap.
      delay(CONTROL_CODE_VISUAL_STATE_RETRY_MILLIS)
    }
    val reason = when (lastState) {
      TicketControlCodeVisualClassifier.CONTROL_POPUP -> "control_code_submit_still_open"
      TicketControlCodeVisualClassifier.RAW_TICKET -> "control_code_not_generated"
      else -> "control_code_generated_state_timeout"
    }
    recordTicketEvent("control_code_generated_state_timeout", "last_state=$lastState")
    return FastControlCodeDelivery(false, reason)
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

  private suspend fun markerFirstControlCodeFrameWatermarkForBrowser(
    reason: String,
    expectedResultMode: TicketControlCodeVisualResultMode = TicketControlCodeVisualResultMode.NONE,
    verifiedProbe: TicketControlCodeVisualProbe? = null
  ): Pair<Long, Long>? {
    if (expectedResultMode != TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE) return null
    val epoch = streamEpoch
    val probe = verifiedProbe ?: awaitStableGeneratedControlCodeCloseProbe() ?: return null
    if (probe.captureStartUs <= 0L) return null
    val deadline = SystemClock.elapsedRealtime() + CONTROL_CODE_BROWSER_MARKER_PROBE_WAIT_MILLIS
    while (streamEpoch == epoch && SystemClock.elapsedRealtime() < deadline) {
      val encoded = latestKeyFrame
      if (encoded != null && encoded.epoch == epoch) {
        // Recognition and encoding retain the same captured picture. Never bind
        // recognition to a later frame: that was the source of browser re-recognition.
        if (encoded.captureStartUs == probe.captureStartUs) {

          return encoded.epoch to encoded.sequence
        }
        if (encoded.captureStartUs > probe.captureStartUs) break
      }
      delay(CONTROL_CODE_VISUAL_STATE_POLL_MILLIS)
    }
    recordTicketEvent("control_code_result_frame_unavailable", "reason=$reason")
    return null
  }

  private suspend fun returnControlCodeSurfaceToRawTicket(
    reason: String,
    generatedResultObserved: Boolean = false
  ): Boolean {
    if (!generatedResultObserved) {
      tryDismissOpenControlCodePopupAfterInputFailure(reason)
        ?.let { return it }
      val state = waitForCleanTicketSurfaceFast(reason,
        CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS, returnOnFreshGeneratedResult = true)
      if (state == TicketViviRecoveryState.TICKET_DETAIL) {
        return controlCodeCleanupHasPanelOwner()
      }
      if (state != TicketViviRecoveryState.CONTROL_CODE_RESULT) return false
    }
    return closeGeneratedControlCodeResult(reason)
  }

  private suspend fun tryDismissOpenControlCodePopupAfterInputFailure(
    reason: String
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

    recordTicketEvent(
      "control_code_failed_entry_popup_proved",
      "reason=$reason result=$visualResult"
    )
    val dismissed = runFastOneShotControlSurfaceCloseInput(
      command = "input keyevent KEYCODE_BACK",
      reason = "control_code_failed_entry_popup_back"
    ).ok
    if (!dismissed) {
      recordTicketEvent("control_code_failed_entry_popup_back_failed", reason)
      return false
    }
    requestKeyFrame("control_code_failed_entry_popup_back")
    val cleanState = waitForCleanTicketSurfaceFast(
      reason = reason,
      timeoutMillis = CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS
    )
    if (cleanState != TicketViviRecoveryState.TICKET_DETAIL) {
      recordTicketEvent(
        "control_code_failed_entry_popup_back_unproved",
        "state=${cleanState?.name ?: "unavailable"}"
      )
      return false
    }

    return controlCodeCleanupHasPanelOwner()
  }

  private suspend fun closeGeneratedControlCodeResult(
    reason: String,
  ): Boolean {

    updateTicketSessionState(TICKET_SESSION_CONTROL_EXIT, reason)

    if (activeControlCodeVisualResultMode !=
      TicketControlCodeVisualResultMode.GENERATED_WITH_CLOSE
    ) {
      recordTicketEvent(
        "control_code_fast_cleanup_close_blocked_unproved",
        "fresh_state=result_mode_unproved source=rooted_visual_probe"
      )
      return false
    }

    val closeProbe = awaitStableGeneratedControlCodeCloseProbe()
    val closeBounds = closeProbe?.closeBounds
    if (closeProbe?.result != TicketControlCodeVisualClassifier.GENERATED || closeBounds == null) {
      recordTicketEvent(
        "control_code_fast_cleanup_close_blocked_unproved",
        "fresh_state=${closeProbe?.result ?: "unknown"} source=rooted_visual_probe"
      )
      return false
    }
    val mapped = controlCodeVisualBoundsToDevice(closeBounds)
    val action = TicketViviPageAction(
      x = (mapped.left + mapped.right) / 2,
      y = (mapped.top + mapped.bottom) / 2,
      reason = "control_code_generated_close_visual",
      bounds = "${mapped.left},${mapped.top},${mapped.right},${mapped.bottom}"
    )
    // Even a failed acknowledgement can follow a dispatched tap. Reconcile
    // its result with fresh pixels; never repeat the close at retained coordinates.
    sendFastGeneratedResultCloseTap(action, "control_code_fast_cleanup_close")
    requestKeyFrame("control_code_fast_cleanup_close")

    val cleanState = waitForCleanTicketSurfaceFast(reason,
      CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS)
    if (cleanState != TicketViviRecoveryState.TICKET_DETAIL) {
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

    return controlCodeCleanupHasPanelOwner()
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

  private suspend fun sendFastGeneratedResultCloseTap(
    action: TicketViviPageAction,
    commandReason: String
  ): Boolean {
    val closeCommand = "input tap ${action.x} ${action.y}"
    val tap = runFastOneShotControlSurfaceCloseInput(closeCommand, commandReason)
    if (!tap.ok) {
      recordTicketEvent("control_code_fast_cleanup_close_failed", "reason=$commandReason duration_ms=${tap.durationMs}")
      return false
    }

    return true
  }

  private suspend fun waitForCleanTicketSurfaceFast(
    reason: String,
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
      if (rawTicketConfirmed ||
        (returnOnFreshGeneratedResult && state == TicketViviRecoveryState.CONTROL_CODE_RESULT)
      ) return state
      val sampleGapRemainingMillis = CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS -
        (SystemClock.elapsedRealtime() - visualProbeStartedAtMillis)
      if (sampleGapRemainingMillis > 0L && SystemClock.elapsedRealtime() < deadlineMillis) {
        delay(minOf(sampleGapRemainingMillis, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)))
      }
    }
    val durationMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    recordTicketEvent(
      "control_code_fast_cleanup_visual_inconclusive",
      "reason=$reason state=${lastState?.name ?: "unavailable"} duration_ms=$durationMillis"
    )
    return if (lastState == TicketViviRecoveryState.TICKET_DETAIL) null else lastState
  }

  private fun publishGeneratedControlCode(
    requestId: String, epoch: Long, sequence: Long, proof: String, proofAtMillis: Long
  ) {
    enqueueTicketCodePublication(TicketCodePublication(
      requestId, TicketCodePublicationKind.GENERATED, status = "succeeded", reason = "generated",
      cleanupPending = true, epoch = epoch, sequence = sequence, proof = proof,
      proofAt = if (proofAtMillis > 0) Instant.ofEpochMilli(proofAtMillis).toString() else ""
    ))
  }

  private fun sendControlCodeFailure(
    requestId: String, reason: String, startedAtMillis: Long, cleanupPending: Boolean
  ) {
    val nowMillis = SystemClock.elapsedRealtime()
    val duration = if (startedAtMillis > 0) (nowMillis - startedAtMillis).coerceAtLeast(0) else 0
    lastControlCodeRequestId = requestId.takeIf { it.isNotBlank() }
    lastControlCodeRequestStatus = "failed"
    lastControlCodeRequestReason = reason
    lastControlCodeRequestDurationMillis = duration
    lastControlCodeRequestCompletedAtMillis = nowMillis
    enqueueTicketCodePublication(TicketCodePublication(
      requestId, TicketCodePublicationKind.FAILURE, status = "failed", reason = reason,
      cleanupPending = cleanupPending
    ))
    recordTicketEvent("control_code_final_state",
      "request=$requestId status=failed reason=$reason duration_ms=$duration cleanup_pending=$cleanupPending")
    if (!cleanupPending && streamActive) startForegroundGuard()
  }

  private fun sendControlCodeProgress(requestId: String, status: String, reason: String) {
    enqueueTicketCodePublication(TicketCodePublication(
      requestId.trim(), TicketCodePublicationKind.PROGRESS, status = status, reason = reason,
      cleanupPending = status == "generated"
    ))
  }

  private fun sendControlCodeCleanup(requestId: String, ok: Boolean, reason: String, startedAtMillis: Long) {
    if (!ok) lastControlCodeRequestReason = reason
    enqueueTicketCodePublication(TicketCodePublication(
      requestId, TicketCodePublicationKind.CLEANUP, status = if (ok) "" else "failed",
      reason = reason, cleanupPending = !ok
    ))
    val duration = if (startedAtMillis > 0) {
      (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0)
    } else 0
    recordTicketEvent("control_code_cleanup_final_state",
      "request=$requestId status=${if (ok) "succeeded" else "failed"} reason=$reason duration_ms=$duration")
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
    return true
  }

  private fun newTicketPanelDarkLease(actionId: String): TicketActionPanelDarkLease {
    val visibilityOwner = java.util.UUID.randomUUID().toString()
    return TicketActionPanelDarkLease(
    actionId = actionId,
    scope = serviceScope,
    clampRootExecutor = ticketActionPanelDarkRootExecutor,
    verifyRootExecutor = ticketActionPanelDarkVerifyRootExecutor,
    physicalTouchState = PhoneAutomationServiceBridge::currentRootPhysicalTouchState,
    onDarkWriterStarting = {
      PhoneAutomationServiceBridge.registerPhysicalVisibilityBlocker(visibilityOwner)
    },
    onDarkWriterStopped = {
      PhoneAutomationServiceBridge.clearPhysicalVisibilityBlocker(visibilityOwner)
    },
    physicalVisibleWindow = {
      PhoneAutomationServiceBridge.currentPhysicalVisibleWindow().let {
        TicketPhysicalVisibleWindow(it.deadlineUptimeMillis, it.generation)
      }
    },
    ownerProcessId = android.os.Process.myPid(),
    onSnapshotChanged = { snapshot ->
      val prior = ticketActionPanelDarkLeaseSnapshot
      if (snapshot.failure.isNotBlank() &&
        (prior.ownerActionId != snapshot.ownerActionId || prior.failure.isBlank())
      ) ticketActionPanelDarkLeaseFailures.incrementAndGet()
      ticketActionPanelDarkLeaseSnapshot = snapshot
    }
    )
  }

  private val panelDarkCommandRunner by lazy {
    TicketPanelDarkCommandRunner(
      currentLease = { activeTicketActionPanelDarkLease },
      createLease = { newTicketPanelDarkLease("phone_command:${SystemClock.elapsedRealtime()}") },
      onOwnedLeaseChanged = { activeTicketActionPanelDarkLease = it }
    )
  }

  /**
   * Callers already own controlCodePhoneMutationLane. An admitted action retains its lease;
   * a standalone command owns the same helper through convergence and exact shutdown.
   */
  private suspend fun runPanelDarkCommand(
    reason: String,
    command: suspend () -> RootResult
  ): RootResult {
    val standalone = activeTicketActionPanelDarkLease == null
    // Explicitly disabled touch brightness retains the ordinary Ticket brightness policy.
    // An unavailable snapshot or lost monitor while enabled must still fail closed.
    if (standalone && touchBrightnessSnapshot()?.touchBrightnessEnabled == false) return command()
    if (standalone) {
      PhoneAutomationServiceBridge.markNonTouchInput(
        "ticket:$reason", TICKET_ACTION_V3_PANEL_DARK_LEASE_MILLIS
      )
    }
    return try {
      val result = panelDarkCommandRunner.run(reason, command)
      if (standalone && result.exitCode == 46) {
        // Several wake/recovery callers ignore a failed root result or try another path.
        // Stop the whole coroutine after an unsafe standalone attempt, never reacquire and replay.
        updateTicketSessionState(TICKET_SESSION_NEEDS_ATTENTION, "panel_dark_operation_stopped")
        throw CancellationException("phone operation stopped by panel-dark authority")
      }
      result
    } finally {
      if (standalone) {
        PhoneAutomationServiceBridge.clearNonTouchInputTailForBrowserCriticalAction()
      }
    }
  }

  private suspend fun runFastNonTouchInput(
    command: String,
    reason: String,
    timeout: Duration = NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS.milliseconds
  ): RootResult = runPanelDarkCommand(reason) {
    inputRootExecutor.runScript(boundedNonTouchCommand(command, timeout), timeout)
  }

  private suspend fun runSensitiveFastNonTouchScript(
    command: String,
    reason: String,
    timeout: Duration
  ): RootResult = runPanelDarkCommand(reason) {
    inputRootExecutor.runScript(boundedNonTouchCommand(command, timeout), timeout)
  }.copy(command = "[REDACTED]", stdout = "", stderr = "")

  private suspend fun runFastOneShotControlSurfaceCloseInput(
    command: String,
    reason: String
  ): RootResult = runPanelDarkCommand(reason) {
    controlSurfaceCloseRootExecutor.run(
      command, CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS.milliseconds
    )
  }

  private suspend fun runFastRecoveryInput(
    command: String,
    reason: String,
    timeout: Duration = TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS.milliseconds
  ): RootResult = runPanelDarkCommand(reason) {
    recoveryInputRootExecutor.run(command, timeout)
  }

  private suspend fun runFastNonTouchWakeScript(
    command: String, reason: String, timeout: Duration
  ): RootResult = runPanelDarkCommand(reason) {
    wakeRootExecutor.runScript(boundedNonTouchCommand(command, timeout), timeout)
  }

  private suspend fun runFastNonTouchScript(
    command: String, reason: String, timeout: Duration
  ): RootResult = runPanelDarkCommand(reason) {
    inputRootExecutor.runScript(boundedNonTouchCommand(command, timeout), timeout)
  }

  // Keep the descendant deadline independently of the root transport deadline.
  private fun boundedNonTouchCommand(command: String, timeout: Duration): String {
    val millis = (timeout.inWholeMilliseconds - NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS)
      .coerceAtLeast(250L)
    val seconds = "${millis / 1_000}.${(millis % 1_000).toString().padStart(3, '0')}s"
    return "timeout -k 0.250s $seconds sh -c ${shellQuote(command)}"
  }

  private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
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

  companion object {
    private const val SERVER_BACKLOG = 4
    private const val SOCKET_TIMEOUT_MILLIS = 30_000
    private const val MAX_HEADER_LINE_BYTES = 131_072
    private const val TICKET_SPACETIME_CRITICAL_MESSAGE_TTL_MILLIS = 5 * 60_000L
    private const val MAX_TICKET_EVENT_DETAIL_BYTES = 256
    private const val SESSION_START_TIMEOUT_MILLIS = 70_000L
    private const val SERVICE_DESTROY_JOIN_TIMEOUT_MILLIS = 12_000L
    const val SERVER_VERSION = "ticket-stream-2026-09-06-parallel-picture-readers-v372"
    private const val FRAME_ENVELOPE_VERSION = "tsf3"
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
    private const val CAPTURE_MODE_IDLE = "idle"
    private const val CAPTURE_MODE_ROOT_HARDWARE_H264 = TicketScreenConfig.ROOT_HARDWARE_H264_CAPTURE_MODE
    private const val ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS = 3_000L
    private const val LIVE_FRAME_MAX_AGE_MILLIS = 2_000L
    private const val ACTIVE_STREAM_REUSE_TICKET_DETAIL_MAX_AGE_MILLIS = 5 * 60_000L
    private const val TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS = 6_000L
    private const val STREAM_WATCHDOG_POLL_MILLIS = 500L
    private const val STREAM_WATCHDOG_NO_ENCODER_RESTART_MILLIS = 3_000L
    private const val STREAM_WATCHDOG_NO_FRAME_RESTART_MILLIS = 3_000L
    private const val STREAM_WATCHDOG_STALE_FRAME_RESTART_MILLIS = 3_000L
    private const val STREAM_WATCHDOG_STARTUP_FIRST_USEFUL_FRAME_GRACE_MILLIS =
      TICKET_FAST_PUBLIC_OPEN_ROOT_PROOF_TIMEOUT_MILLIS
    private const val STREAM_WATCHDOG_RECOVERY_COOLDOWN_MILLIS = 1_000L
    private const val HARDWARE_RELIABILITY_FAILURE_THRESHOLD = 3
    private const val SECURE_CAPTURE_PROBE_START_FRAME_COUNT = 3L
    private const val SECURE_CAPTURE_PROBE_DELAY_MILLIS = 700L
    private const val SECURE_CAPTURE_PROBE_MIN_INTERVAL_MILLIS = 8_000L
    private const val SECURE_CAPTURE_VISIBLE_PROBE_REUSE_MILLIS = 20_000L
    private const val SECURE_CAPTURE_PROBE_TIMEOUT_MILLIS = 1_500L
    private const val VIDEO_CLIENT_MAX_FRAME_BYTES =
      TicketTsf3FrameEnvelope.HEADER_BYTES + TicketH264FrameRecord.MAX_PAYLOAD_BYTES
    private const val VIDEO_CLIENT_SLOW_CLOSE_MILLIS = ROOT_KEYFRAME_CACHE_MAX_AGE_MILLIS
    private const val TICKET_FAST_PUBLIC_OPEN_BUDGET_MILLIS = 5_000L
    private const val TICKET_DETAIL_VISUAL_PROOF_TIMEOUT_MILLIS = 3_000L
    // Production rooted UiAutomator reads settle in roughly 2.5-3s after ViVi's Flutter
    // activation transition. Keep slider-geometry probes fast, but give activated semantics a
    // dedicated bounded read that can actually return the nonblank hierarchy.
    private const val TICKET_SLIDER_ACCESSIBILITY_RECONNECT_TIMEOUT_MILLIS = 5_000L
    private const val TICKET_SLIDER_GESTURE_TIMEOUT_MILLIS = 1_500L
    private const val TICKET_ACTION_V3_SAMPLE_WIDTH = 192
    private const val TICKET_ACTION_V3_SAMPLE_HEIGHT = 288
    private const val TICKET_ACTION_V3_VISUAL_TIMEOUT_MILLIS = 5_000L
    private const val TICKET_ACTION_V3_FINAL_CONVERGENCE_MILLIS = 8_000L
    private const val TICKET_ACTION_V3_CAPTURE_RECOVERY_TIMEOUT_MILLIS = 15_000L
    private const val TICKET_ACTION_V3_ACTIVATION_PROOF_TIMEOUT_MILLIS = 16_000L
    private const val TICKET_ACTION_V3_PROBE_WAIT_MILLIS = 2_500L
    private const val TICKET_ACTION_V3_PROBE_GAP_MILLIS = 90L
    private const val TICKET_ACTION_V3_PANEL_DARK_LEASE_MILLIS = 90_000L
    private const val TICKET_ACTION_V3_MAX_NAVIGATION_MUTATIONS = 4
    private const val TICKET_ACTION_V3_SWITCH_PREFERENCES = "ticket_action_v3_switch"
    private const val TICKET_ACTION_V3_JOURNAL_PREFERENCES = "ticket_action_v3_journal"
    // ViVi commits the registration before its Flutter accessibility tree finishes replacing
    // the slider with the activated status/countdown. A rooted hierarchy read on the production
    // Pixel can itself take about three seconds after that transition. Keep this bounded, but
    // leave enough time for two complete fresh observations plus visual/fallback proof. The
    // caller still requires both explicit activated-detail semantics and fresh rooted H.264.
    private const val TICKET_WAKE_RECOVERY_INPUT_TIMEOUT_MILLIS = 2_000L
    private const val TICKET_WAKE_COMMAND_TIMEOUT_MILLIS = 3_000L
    private const val TICKET_WAKE_INTERACTIVE_TIMEOUT_MILLIS = 900L
    private const val TICKET_WAKE_FAST_POLL_MILLIS = 100L
    private const val NON_TOUCH_ROOT_COMMAND_TIMEOUT_MILLIS = 120_000L
    private const val NON_TOUCH_COMMAND_SELF_TIMEOUT_CUSHION_MILLIS = 250L
    private const val CONTROL_CODE_FAST_CLOSE_COMMAND_TIMEOUT_MILLIS = 2_000L
    private const val CONTROL_CODE_KEYBOARD_CLAMP_COMMAND_TIMEOUT_MILLIS = 2_500L
    private const val STARTUP_CLIENT_DISCONNECT_GRACE_MILLIS = 5_000L
    private const val CLIENT_DISCONNECT_IDLE_GRACE_MILLIS = 90_000L
    private const val VIVI_FOREGROUND_INITIAL_DELAY_MILLIS = 1_500L
    private const val VIVI_STABLE_FOREGROUND_CHECK_MILLIS = 5_000L
    private const val VIVI_FOREGROUND_GRACE_MILLIS = 8_000L
    private const val TICKET_SCREEN_WAKE_HOLD_MILLIS = 30_000L
    private const val TICKET_SCREEN_WAKE_REQUEST_COOLDOWN_MILLIS = 2_000L
    private const val VIVI_REAUTH_JOURNAL_PREFERENCES = "ticket_vivi_reauth_journal_v1"
    private const val VIVI_REAUTH_CLEAR_TIMEOUT_MILLIS = 5_000L
    private const val VIVI_REAUTH_LOGIN_PROOF_TIMEOUT_MILLIS = 12_000L
    private const val VIVI_REAUTH_ACCESSIBILITY_TIMEOUT_MILLIS = 3_000L
    private const val VIVI_REAUTH_FIELD_SETTLE_MILLIS = 150L
    private const val VIVI_REAUTH_VERIFY_TIMEOUT_MILLIS = 15_000L
    private const val VIVI_REAUTH_VERIFY_SLICE_MILLIS = 2_500L
    private const val VIVI_REAUTH_VERIFY_POLL_MILLIS = 200L
    private const val VIVI_REAUTH_LOGOUT_POLL_MILLIS = 80L
    private const val VIVI_REAUTH_TICKET_RESTORE_MAX_MUTATIONS = 3
    private const val VIVI_REAUTH_TICKET_REDETECT_MAX_MUTATIONS = 5
    private const val CONTROL_CODE_FAST_INTERACTION_RETRY_COUNT = 4
    private const val CONTROL_CODE_FAST_RESULT_TIMEOUT_MILLIS = 18_000L
    private const val CONTROL_CODE_VISUAL_SIGNATURE_TTL_MILLIS = 60_000L
    private const val CONTROL_CODE_VISUAL_CHECKPOINT_PREFERENCES =
      "ticket_control_code_visual_checkpoint_v1"
    private const val CONTROL_CODE_VISUAL_CHECKPOINT_PENDING_KEY = "cleanup_pending"
    // Updated ViVi builds can publish the generated-result row and finish the
    // browser paint handshake after the phone marker, while still staying on
    // the normal user-facing path. Keep the acknowledgement bounded, but give
    // that public handoff enough time to arrive before using fallback cleanup.
    private const val CONTROL_CODE_BROWSER_CAPTURE_ACK_TIMEOUT_MILLIS = 20_000L
    private const val CONTROL_CODE_VISUAL_STATE_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_VISUAL_STATE_POLL_MILLIS = 40L
    private const val CONTROL_CODE_VISUAL_STATE_RETRY_MILLIS = 50L
    // The rooted helper can spend more than 250 ms capturing and classifying a frame while the
    // stream remains healthy. Do not overwrite that exact probe id before its reply has had a
    // bounded multi-frame opportunity to arrive. The two-frame generated signature proof still
    // remains mandatory; this only enlarges the transport wait inside one final proof window.
    private const val CONTROL_CODE_GENERATED_CLOSE_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_GENERATED_CLOSE_PROOF_TIMEOUT_MILLIS = 3_200L
    private const val CONTROL_CODE_BROWSER_MARKER_PROBE_WAIT_MILLIS = 1_800L
    // Focus/caret animation can outlive four rooted probe frames even though the field settles
    // moments later. Extra samples are observation-only: value still needs two agreeing frames,
    // and retyping remains gated by two freshly proved static-blank frames.
    private const val CONTROL_CODE_SUBMIT_VISUAL_MAX_SAMPLES = 8
    private const val CONTROL_CODE_SUBMIT_VISUAL_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_SUBMIT_VISUAL_SAMPLE_GAP_MILLIS = 250L
    private const val CONTROL_CODE_VALUE_RENDER_RECHECK_SETTLE_MILLIS = 350L
    private const val CONTROL_CODE_ROOT_TRANSACTION_TIMEOUT_MILLIS = 4_000L
    private const val CONTROL_CODE_ROOT_SUBMIT_TIMEOUT_MILLIS = 2_500L
    private const val CONTROL_CODE_FAST_POLL_MILLIS = 90L
    // A rooted hierarchy read can take about three seconds after an idle
    // period. Keep the healthy path unchanged, but leave enough budget for
    // one repeat of that same proof when the first read is inconclusive.
    private const val CONTROL_CODE_RESULT_CACHE_TTL_MILLIS = 90_000L
    private val CONTROL_CODE_REQUEST_DIGITS_REGEX = Regex("""^[0-9]{2,8}$""")
    // Each exact cleanup probe gets one full 1 FPS frame opportunity. The surrounding proof keeps
    // at least three seconds for two distinct consecutive RAW_TICKET samples.
    private const val CONTROL_CODE_CLEAN_SURFACE_PROBE_WAIT_MILLIS = 1_250L
    private const val CONTROL_CODE_FAST_CLEANUP_VERIFY_TIMEOUT_MILLIS = 3_200L
  private const val CONTROL_CODE_FAST_CLEANUP_POLL_MILLIS = 75L
  private const val CONTROL_CODE_FAST_CLEANUP_RAW_VISUAL_PROOF_COUNT = 2
    private const val CONTROL_CODE_FAST_CLEANUP_VISUAL_SAMPLE_GAP_MILLIS = 200L
    private const val TICKET_CURRENT_VIEW_TIMEOUT_MILLIS = 3_000L
    private const val CONTROL_CODE_TRANSITION_GRACE_MILLIS = 3_000L
    private const val CACHED_FOREGROUND_MAX_AGE_MILLIS = 2_000L
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
