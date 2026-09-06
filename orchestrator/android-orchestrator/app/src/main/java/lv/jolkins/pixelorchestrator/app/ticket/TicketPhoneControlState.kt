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
  val reason: String
)

/** One lifetime per service, independent of capture/codec generations and connections. */
internal class TicketPhoneControlState(
  val sessionId: String = "pc-${UUID.randomUUID()}"
) {
  private var contextCounter = 0L
  private var sequence = 0L
  private var capturedThroughUs = 0L
  private var contextKey: List<Any?>? = null
  val updates = MutableStateFlow(TicketPhoneControlObservation("$sessionId:0", 0, null, false, "phone_session_started"))

  @Synchronized
  fun observe(observation: TicketVisualActionObservation, busy: Boolean) {
    // The stderr reader may repeat or deliver an old probe after a newer capture.
    if (observation.captureStartUs <= capturedThroughUs) return
    capturedThroughUs = observation.captureStartUs
    val nextKey = listOf(observation.state, observation.currentAnchor, observation.sliderBounds)
    if (nextKey != contextKey) {
      contextCounter++
      contextKey = nextKey
    }
    updates.value = TicketPhoneControlObservation(
      "$sessionId:$contextCounter", ++sequence, observation, busy,
      if (busy) "phone_busy" else if (observation.currentAnchor.isBlank()) "ticket_not_identified" else ""
    )
  }

  @Synchronized
  fun invalidate(reason: String, busy: Boolean = false, capturedThroughUs: Long = 0L) {
    // Break the context even if the same ticket later reappears. A gesture spanning
    // interference, unavailable capture or a physical mutation must start again.
    contextCounter++
    this.capturedThroughUs = maxOf(this.capturedThroughUs, capturedThroughUs)
    contextKey = null
    updates.value = TicketPhoneControlObservation("$sessionId:$contextCounter", ++sequence, null, busy, reason)
  }

  @Synchronized
  fun exactContext(revision: String, nowMillis: Long): TicketVisualActionObservation? {
    val state = updates.value
    val observation = state.observation ?: return null
    val age = nowMillis - observation.captureStartUs / 1_000L
    return observation.takeIf {
      !state.busy && state.contextRevision == revision &&
        age in 0 until TICKET_CONTROL_OBSERVATION_TTL_MILLIS && it.currentAnchor.isNotBlank() &&
        it.state in setOf(TicketVisualPhoneState.UNACTIVATED_DETAIL, TicketVisualPhoneState.ACTIVATED_DETAIL)
    }
  }
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
  fun observedAt(captureStartUs: Long, nowMillis: Long): String? {
    val anchorAge = nowMillis - receivedMonotonicMillis
    val captureMillis = captureStartUs / 1_000L
    if (anchorAge !in 0 until CLOCK_ANCHOR_MAX_AGE_MILLIS || captureMillis > nowMillis || captureStartUs <= 0) return null
    // Server time was sampled before the response arrived. Subtracting from the
    // response boundary deliberately includes network time in the observation age.
    // One extra millisecond covers conversion rounding; no wall clock is trusted.
    return Instant.ofEpochMilli(serverMillis + captureMillis - receivedMonotonicMillis - 1).toString()
  }
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

  suspend fun run(transport: TicketPhoneControlTransport): Unit = coroutineScope {
    val clock = AtomicReference<TicketPhoneControlClock?>(null)
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
            val ready = !observation.busy && source != null && source.currentAnchor.isNotBlank() &&
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
      renewal.cancel()
    }
  }
}
