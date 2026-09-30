package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
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
@Serializable
internal data class TicketPhoneControlObservation(
  val contextRevision: String,
  val sequence: Long,
  val observation: TicketVisualActionObservation?,
  val busy: Boolean,
  val reason: String,
  val inputAvailable: Boolean = true
)

/** Captured state only: admission and protected input remain owned by the action executor. */
@Serializable
internal data class TicketRegistrationEvidenceFence(
  val streamEpoch: Long,
  val captureGeneration: Long,
  val inputGeneration: Long,
  val windowId: Int,
  val touchGeneration: Long,
  val validAfterMillis: Long
)

@Serializable
internal data class TicketRegistrationVisualEvidence(
  val contextRevision: String,
  val first: TicketVisualActionObservation,
  val second: TicketVisualActionObservation,
  val fence: TicketRegistrationEvidenceFence
) {
  fun isFresh(nowMillis: Long): Boolean = NativeTicketControl.call("evidence_fresh", buildJsonObject {
    put("evidence", NativeTicketControl.json.encodeToJsonElement(this@TicketRegistrationVisualEvidence))
    put("now", nowMillis)
  }).jsonPrimitive.boolean
}

internal object NativeTicketControl {
  val json = Json { encodeDefaults = true }
  init { System.loadLibrary("pixel_health") }
  private external fun decide(operation: String, payload: String): String
  fun call(operation: String, args: JsonObject, state: JsonElement = JsonNull, session: String = ""): JsonElement =
    json.parseToJsonElement(decide(operation, buildJsonObject {
      put("args", args); put("state", state); put("session", session)
    }.toString()))
}

/** One lifetime per service, independent of capture/codec generations and connections. */
internal class TicketPhoneControlState(
  val sessionId: String = "pc-${UUID.randomUUID()}"
) {
  private var nativeState: JsonElement = JsonNull
  val updates = MutableStateFlow(TicketPhoneControlObservation("$sessionId:0", 0, null, false, "phone_session_started"))

  private fun call(operation: String, args: JsonObject): JsonElement {
    val result = NativeTicketControl.call(operation, args, nativeState, sessionId).jsonObject
    nativeState = result.getValue("state")
    updates.value = NativeTicketControl.json.decodeFromJsonElement(nativeState.jsonObject.getValue("update"))
    return result.getValue("answer")
  }

  @Synchronized
  fun observe(observation: TicketVisualActionObservation, busy: Boolean,
    evidenceFence: TicketRegistrationEvidenceFence? = null, inputAvailable: Boolean = true) {
    call("observe", buildJsonObject {
      put("observation", NativeTicketControl.json.encodeToJsonElement(observation))
      put("busy", busy); put("inputAvailable", inputAvailable)
      put("fence", NativeTicketControl.json.encodeToJsonElement(evidenceFence))
    })
  }

  @Synchronized
  fun invalidate(reason: String, busy: Boolean = false, capturedThroughUs: Long = 0L) {
    call("invalidate", buildJsonObject {put("reason", reason); put("busy", busy); put("captured", capturedThroughUs)})
  }

  @Synchronized
  fun exactContext(revision: String, nowMillis: Long): TicketVisualActionObservation? =
    NativeTicketControl.json.decodeFromJsonElement(call("exact", buildJsonObject {put("revision", revision); put("now", nowMillis)}))

  @Synchronized
  fun clearRegistrationEvidence() {call("clear", buildJsonObject {})}

  @Synchronized
  fun registrationCandidateIsCurrent(revision: String, fence: TicketRegistrationEvidenceFence?, nowMillis: Long): Boolean =
    call("candidate", buildJsonObject {
      put("revision", revision); put("now", nowMillis); put("fence", NativeTicketControl.json.encodeToJsonElement(fence))
    }).jsonPrimitive.boolean

  @Synchronized
  fun registrationEvidence(revision: String, fence: TicketRegistrationEvidenceFence?, nowMillis: Long): TicketRegistrationVisualEvidence? =
    NativeTicketControl.json.decodeFromJsonElement(call("evidence", buildJsonObject {
      put("revision", revision); put("now", nowMillis); put("fence", NativeTicketControl.json.encodeToJsonElement(fence))
    }))

  @Synchronized
  fun registrationEvidenceIsCurrent(evidence: TicketRegistrationVisualEvidence, fence: TicketRegistrationEvidenceFence?, nowMillis: Long): Boolean =
    call("evidence_current", buildJsonObject {
      put("evidence", NativeTicketControl.json.encodeToJsonElement(evidence)); put("now", nowMillis)
      put("fence", NativeTicketControl.json.encodeToJsonElement(fence))
    }).jsonPrimitive.boolean
}

/** Immutable receipt identity. Fresh pre-input observations still own gesture geometry. */
internal fun ticketPhoneControlRegistrationIdentity(
  revision: String,
  observation: TicketVisualActionObservation?
): TicketRegistrationProof? = observation?.takeIf {
  NativeTicketControl.call("identity", buildJsonObject {
    put("revision", revision); put("observation", NativeTicketControl.json.encodeToJsonElement(it))
  }).jsonPrimitive.boolean
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
    return NativeTicketControl.call("clock", buildJsonObject {
      put("server", serverMillis); put("received", receivedMonotonicMillis)
      put("captureStartUs", captureStartUs); put("now", nowMillis)
    }).jsonPrimitive.longOrNull
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
            val ready = NativeTicketControl.call("publish_ready", buildJsonObject {
              put("update", NativeTicketControl.json.encodeToJsonElement(observation))
              put("now", nowMillis()); put("observedAt", observedAt)
              put("boundsAvailable", ticketPhoneControlBounds(source?.sliderBounds) != null)
            }).jsonPrimitive.boolean
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
