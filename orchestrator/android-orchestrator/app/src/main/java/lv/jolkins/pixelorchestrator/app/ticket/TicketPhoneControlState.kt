package lv.jolkins.pixelorchestrator.app.ticket

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

internal const val TICKET_CONTROL_OBSERVATION_TTL_MILLIS = 3_000L
private const val CLOCK_ANCHOR_MAX_AGE_MILLIS = 30_000L

// The classifier downsamples its input probe before reporting slider geometry.
internal fun ticketPhoneControlBounds(bounds: TicketVisualProbeBounds?) = bounds?.let {
  TicketCaptureGeometry.normalizeProbeBounds(it,
    TicketVisualActionClassifier.SAMPLE_WIDTH, TicketVisualActionClassifier.SAMPLE_HEIGHT)
}

/** Private capture identity stays on the phone; only the opaque revision leaves it. */
internal data class TicketPhoneControlObservation(
  val contextRevision: String,
  val sequence: Long,
  val observation: TicketVisualActionObservation?,
  val busy: Boolean,
  val reason: String,
  val inputAvailable: Boolean = true
)

/** Captured state only: admission and protected input remain owned by the action executor. */
internal data class TicketRegistrationEvidenceFence(
  val streamEpoch: Long,
  val captureGeneration: Long,
  val inputGeneration: Long,
  val windowId: Int,
  val touchGeneration: Long,
  val validAfterMillis: Long
)

internal data class TicketRegistrationVisualEvidence(
  val contextRevision: String,
  val first: TicketVisualActionObservation,
  val second: TicketVisualActionObservation,
  val fence: TicketRegistrationEvidenceFence
) {
  fun isFresh(nowMillis: Long): Boolean =
    fence.streamEpoch > 0L && fence.captureGeneration > 0L && fence.inputGeneration > 0L &&
      fence.windowId >= 0 && first.captureStartUs < second.captureStartUs &&
      ticketRegistrationObservationIsFresh(first, fence, nowMillis) &&
      ticketRegistrationObservationIsFresh(second, fence, nowMillis) &&
      ticketVisualObservationsAgree(first, second)
}

private fun ticketRegistrationObservationIsFresh(
  observation: TicketVisualActionObservation,
  fence: TicketRegistrationEvidenceFence,
  nowMillis: Long
): Boolean {
  val capturedAtMillis = observation.captureStartUs / 1_000L
  return observation.captureStartUs > 0L && observation.captureGeneration == fence.captureGeneration &&
    capturedAtMillis >= fence.validAfterMillis && capturedAtMillis <= observation.atMillis &&
    nowMillis - capturedAtMillis in 0 until TICKET_CONTROL_OBSERVATION_TTL_MILLIS &&
    observation.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
    observation.currentAnchor.isNotBlank() && observation.sliderBounds != null
}

/** One lifetime per service, independent of capture/codec generations and connections. */
internal class TicketPhoneControlState(
  val sessionId: String = "pc-${UUID.randomUUID()}"
) {
  private var contextCounter = 0L
  private var sequence = 0L
  private var capturedThroughUs = 0L
  private var contextKey: List<Any?>? = null
  private var registrationFirst: TicketVisualActionObservation? = null
  private var registrationFence: TicketRegistrationEvidenceFence? = null
  val updates = MutableStateFlow(TicketPhoneControlObservation("$sessionId:0", 0, null, false, "phone_session_started"))

  @Synchronized
  fun observe(
    observation: TicketVisualActionObservation,
    busy: Boolean,
    evidenceFence: TicketRegistrationEvidenceFence? = null,
    inputAvailable: Boolean = true
  ) {
    // The stderr reader may repeat or deliver an old probe after a newer capture.
    if (observation.captureStartUs <= capturedThroughUs) return
    capturedThroughUs = observation.captureStartUs
    val nextKey = listOf(observation.state, observation.currentAnchor, observation.sliderBounds, inputAvailable)
    val prior = updates.value.observation
    registrationFirst = prior?.takeIf {
      nextKey == contextKey && evidenceFence != null && evidenceFence == registrationFence &&
        ticketVisualObservationsAgree(it, observation)
    }
    registrationFence = evidenceFence.takeIf {
      inputAvailable && observation.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
        observation.currentAnchor.isNotBlank() && observation.sliderBounds != null
    }
    if (registrationFence == null) registrationFirst = null
    if (nextKey != contextKey) {
      contextCounter++
      contextKey = nextKey
    }
    updates.value = TicketPhoneControlObservation(
      "$sessionId:$contextCounter", ++sequence, observation, busy,
      when {
        !inputAvailable -> "ticket_action_accessibility_unavailable"
        busy -> "phone_busy"
        observation.currentAnchor.isBlank() -> "ticket_not_identified"
        else -> ""
      }, inputAvailable
    )
  }

  @Synchronized
  fun invalidate(reason: String, busy: Boolean = false, capturedThroughUs: Long = 0L) {
    // Break the context even if the same ticket later reappears. A gesture spanning
    // interference, unavailable capture or a physical mutation must start again.
    contextCounter++
    this.capturedThroughUs = maxOf(this.capturedThroughUs, capturedThroughUs)
    contextKey = null
    clearRegistrationEvidence()
    updates.value = TicketPhoneControlObservation("$sessionId:$contextCounter", ++sequence, null, busy, reason)
  }

  @Synchronized
  fun exactContext(revision: String, nowMillis: Long): TicketVisualActionObservation? {
    val state = updates.value
    val observation = state.observation ?: return null
    val age = nowMillis - observation.captureStartUs / 1_000L
    return observation.takeIf {
      state.inputAvailable && !state.busy && state.contextRevision == revision &&
        age in 0 until TICKET_CONTROL_OBSERVATION_TTL_MILLIS && it.currentAnchor.isNotBlank() &&
        it.state in setOf(TicketVisualPhoneState.UNACTIVATED_DETAIL, TicketVisualPhoneState.ACTIVATED_DETAIL)
    }
  }

  @Synchronized
  fun clearRegistrationEvidence() {
    registrationFirst = null
    registrationFence = null
  }

  @Synchronized
  fun registrationCandidateIsCurrent(
    revision: String,
    fence: TicketRegistrationEvidenceFence?,
    nowMillis: Long
  ): Boolean = fence != null && fence == registrationFence && updates.value.contextRevision == revision &&
    updates.value.observation?.let { ticketRegistrationObservationIsFresh(it, fence, nowMillis) } == true

  /** Only the already-admitted action may read while busy; this never publishes readiness. */
  @Synchronized
  fun registrationEvidence(
    revision: String,
    fence: TicketRegistrationEvidenceFence?,
    nowMillis: Long
  ): TicketRegistrationVisualEvidence? {
    if (fence == null || fence != registrationFence || updates.value.contextRevision != revision) return null
    return TicketRegistrationVisualEvidence(
      revision, registrationFirst ?: return null, updates.value.observation ?: return null, fence
    ).takeIf { it.isFresh(nowMillis) }
  }

  @Synchronized
  fun registrationEvidenceIsCurrent(
    evidence: TicketRegistrationVisualEvidence,
    fence: TicketRegistrationEvidenceFence?,
    nowMillis: Long
  ): Boolean = evidence.contextRevision == updates.value.contextRevision &&
    evidence.fence == registrationFence && evidence.fence == fence && evidence.isFresh(nowMillis)
}

/** Immutable receipt identity. Fresh pre-input observations still own gesture geometry. */
internal fun ticketPhoneControlRegistrationIdentity(
  revision: String,
  observation: TicketVisualActionObservation?
): TicketRegistrationProof? = observation?.takeIf {
  revision.startsWith("pc-") && it.state == TicketVisualPhoneState.UNACTIVATED_DETAIL &&
    it.currentAnchor.isNotBlank() && it.sliderBounds != null
}?.let {
  TicketRegistrationProof(
    status = "unactivated_ready", reason = "phone_control_context",
    interactionRevision = revision, streamEpoch = 0, frameSequence = 0,
    phoneDisplayWidth = 0, phoneDisplayHeight = 0,
    provedAtUptimeMillis = it.captureStartUs / 1_000L,
    ticketAnchor = it.currentAnchor, detailAnchor = it.currentAnchor
  )
}

internal data class TicketPhoneControlClock(val serverMillis: Long, val receivedMonotonicMillis: Long) {
  fun observedAtMillis(captureStartUs: Long, nowMillis: Long): Long? {
    val anchorAge = nowMillis - receivedMonotonicMillis
    val captureMillis = captureStartUs / 1_000L
    if (anchorAge !in 0 until CLOCK_ANCHOR_MAX_AGE_MILLIS || captureMillis > nowMillis || captureStartUs <= 0) return null
    // Server time was sampled before the response arrived. Subtracting from the
    // response boundary deliberately includes network time in the observation age.
    // One extra millisecond covers conversion rounding; no wall clock is trusted.
    return serverMillis + captureMillis - receivedMonotonicMillis - 1
  }

  fun observedAt(captureStartUs: Long, nowMillis: Long): String? =
    observedAtMillis(captureStartUs, nowMillis)?.let { Instant.ofEpochMilli(it).toString() }
}

internal data class TicketPhoneControlSession(val sessionId: String, val clockAt: String)

internal interface TicketPhoneControlTransport {
  suspend fun readControlSession(): TicketPhoneControlSession?
  suspend fun beginControlSession(sessionId: String, expectedPrevious: String)
  suspend fun publishControlObservation(sessionId: String, state: TicketPhoneControlObservation, observedAt: String, ready: Boolean)
}

/** Single coalescing publication owner, with no media/dispatch/reporting locks. */
internal class TicketPhoneControlPublisher(
  private val state: TicketPhoneControlState,
  private val nowMillis: () -> Long,
  private val onFailure: () -> Unit = {}
) {
  private var expectedPrevious: String? = null
  private var established = false
  @Volatile private var fenced = false
  @Volatile private var running = false
  private val clock = AtomicReference<TicketPhoneControlClock?>(null)

  fun observedAtMillis(capturedAtMillis: Long): Long? =
    if (fenced || !running) null else clock.get()?.observedAtMillis(capturedAtMillis * 1_000L, nowMillis())

  suspend fun run(transport: TicketPhoneControlTransport): Unit = coroutineScope {
    clock.set(null)
    running = true
    // Clock renewal has its own lane: three network round trips must not stop
    // fresh observations from replacing the last published state.
    val renewal = launch(start = CoroutineStart.UNDISPATCHED) {
      while (!fenced) {
        try {
          val current = transport.readControlSession()
          if (established && current?.sessionId != state.sessionId) {
            fenced = true
            state.invalidate("phone_session_replaced")
            break
          }
          if (expectedPrevious == null) expectedPrevious = current?.sessionId.orEmpty()
          transport.beginControlSession(state.sessionId, expectedPrevious.orEmpty())
          val anchored = transport.readControlSession()
          val received = nowMillis()
          if (anchored?.sessionId != state.sessionId) {
            fenced = true
            state.invalidate("phone_session_replaced")
            break
          }
          established = true
          clock.set(TicketPhoneControlClock(Instant.parse(anchored.clockAt).toEpochMilli(), received))
          delay(CLOCK_ANCHOR_MAX_AGE_MILLIS / 2)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          onFailure()
          delay(500L)
        }
      }
    }
    try {
      state.updates.collect { pending ->
        var observation = pending
        while (!fenced) {
          try {
            val anchor = clock.get()
            if (anchor == null) {
              delay(50L)
              observation = state.updates.value
              continue
            }
            if (observation.sequence == 0L) return@collect
            val source = observation.observation
            val observedAt = source?.let { anchor.observedAt(it.captureStartUs, nowMillis()) }.orEmpty()
            val sourceAge = source?.let { nowMillis() - it.captureStartUs / 1_000L }
            val ready = observation.inputAvailable && !observation.busy && source != null && source.currentAnchor.isNotBlank() &&
              source.state in setOf(TicketVisualPhoneState.UNACTIVATED_DETAIL, TicketVisualPhoneState.ACTIVATED_DETAIL) &&
              sourceAge != null && sourceAge in 0 until TICKET_CONTROL_OBSERVATION_TTL_MILLIS && observedAt.isNotEmpty() &&
              (source.state != TicketVisualPhoneState.UNACTIVATED_DETAIL || ticketPhoneControlBounds(source.sliderBounds) != null)
            transport.publishControlObservation(state.sessionId, observation, observedAt, ready)
            return@collect
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            // A publication failure does not invalidate a still-current clock.
            // Retry only recorded state delivery, always selecting the newest input.
            onFailure()
            if (state.updates.value.sequence != observation.sequence) return@collect
            delay(500L)
          }
        }
      }
    } finally {
      running = false
      clock.set(null)
      renewal.cancel()
    }
  }
}
