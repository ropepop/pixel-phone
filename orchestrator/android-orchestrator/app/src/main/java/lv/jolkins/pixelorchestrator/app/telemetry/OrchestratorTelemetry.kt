package lv.jolkins.pixelorchestrator.app.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import java.util.concurrent.CancellationException

internal enum class OrchestratorTelemetryEventType(val wireValue: String) {
  APP_SESSION("app_session"),
  MANUAL_ACTION("manual_action"),
  COMPONENT_TRANSITION("component_transition"),
  HEALTH_CHANGE("health_change"),
  SETTING_CHANGE("setting_change"),
  CLEANUP_RESULT("cleanup_result"),
  SCHEDULING_FAILURE("scheduling_failure"),
  PERMISSION_CHANGE("permission_change"),
  DROPPED_EVENT_SUMMARY("dropped_event_summary")
}

internal enum class OrchestratorTelemetryComponent(val wireValue: String) {
  ORCHESTRATOR("orchestrator"),
  STACK("stack"),
  AUTOMATION("automation"),
  SPEEDTEST("speedtest"),
  CELLMAPPER("cellmapper"),
  TICKET_READINESS("ticket_readiness"),
  TOUCH_BRIGHTNESS("touch_brightness"),
  CPU("cpu"),
  GPU("gpu"),
  THERMAL("thermal"),
  PERMISSIONS("permissions"),
  CLEANUP("cleanup"),
  SCHEDULER("scheduler"),
  DIAGNOSTICS("diagnostics"),
  SUPERVISOR("supervisor"),
  MANAGEMENT("management"),
  SSH("ssh"),
  VPN("vpn"),
  TELEMETRY("telemetry")
}

internal enum class OrchestratorTelemetryCleanupCategory(val wireValue: String) {
  NONE("none"),
  TICKET_HIERARCHY_XML("ticket_hierarchy_xml"),
  DEPLOYMENT_ACTION_RESULTS("deployment_action_results"),
  SUPPORT_BUNDLES("support_bundles"),
  ROOT_COMMAND_HISTORY("root_command_history"),
  STACK_LOGS("stack_logs"),
  DNS_HISTORY("dns_history"),
  RETIRED_ARTIFACTS("retired_artifacts"),
  DEPLOYMENT_ARCHIVES("deployment_archives"),
  APP_CACHE("app_cache")
}

internal enum class OrchestratorTelemetryStatus(val wireValue: String) {
  UNKNOWN("unknown"),
  HEALTHY("healthy"),
  DEGRADED("degraded"),
  FAILED("failed"),
  STALE("stale"),
  ENABLED("enabled"),
  DISABLED("disabled"),
  RUNNING("running"),
  COMPLETED("completed"),
  SKIPPED("skipped"),
  UNAVAILABLE("unavailable")
}

internal enum class OrchestratorTelemetryResult(val wireValue: String) {
  NONE("none"),
  OK("ok"),
  FAILED("failed"),
  CANCELLED("cancelled"),
  DROPPED("dropped"),
  REJECTED("rejected"),
  RETRYING("retrying")
}

internal enum class OrchestratorTelemetryPriority(
  val wireValue: String,
  internal val rank: Int
) {
  LOW("low", 0),
  NORMAL("normal", 1),
  HIGH("high", 2),
  CRITICAL("critical", 3)
}

internal data class OrchestratorTelemetryDraft(
  val eventType: OrchestratorTelemetryEventType,
  val component: OrchestratorTelemetryComponent,
  val cleanupCategory: OrchestratorTelemetryCleanupCategory =
    OrchestratorTelemetryCleanupCategory.NONE,
  val status: OrchestratorTelemetryStatus,
  val result: OrchestratorTelemetryResult = OrchestratorTelemetryResult.NONE,
  val priority: OrchestratorTelemetryPriority = OrchestratorTelemetryPriority.NORMAL,
  val durationMillis: Long = 0,
  val count: Long = 0,
  val byteCount: Long = 0
) {
  init {
    require(
      if (eventType == OrchestratorTelemetryEventType.CLEANUP_RESULT) {
        cleanupCategory != OrchestratorTelemetryCleanupCategory.NONE
      } else {
        cleanupCategory == OrchestratorTelemetryCleanupCategory.NONE
      }
    ) {
      "cleanupCategory is required only for cleanup result events"
    }
    require(durationMillis in 0..MAX_DURATION_MILLIS) {
      "durationMillis must be between zero and seven days"
    }
    require(count in 0..MAX_COUNT) { "count exceeds the safe limit" }
    require(byteCount in 0..MAX_BYTE_COUNT) { "byteCount exceeds one tebibyte" }
  }

  private companion object {
    const val MAX_DURATION_MILLIS = 7L * 24 * 60 * 60 * 1_000
    const val MAX_COUNT = 1_000_000_000L
    const val MAX_BYTE_COUNT = 1024L * 1024 * 1024 * 1024
  }
}

internal class OrchestratorTelemetryPayload private constructor(
  val correlationId: String,
  val eventType: OrchestratorTelemetryEventType,
  val component: OrchestratorTelemetryComponent,
  val cleanupCategory: OrchestratorTelemetryCleanupCategory,
  val status: OrchestratorTelemetryStatus,
  val result: OrchestratorTelemetryResult,
  val priority: OrchestratorTelemetryPriority,
  val buildId: String,
  val durationMillis: Long,
  val count: Long,
  val byteCount: Long,
  val createdAtEpochMillis: Long
) {
  fun reducerRequestBody(): String {
    return JsonArray(
      listOf(
        JsonPrimitive(correlationId),
        JsonPrimitive(eventType.wireValue),
        JsonPrimitive(component.wireValue),
        JsonPrimitive(cleanupCategory.wireValue),
        JsonPrimitive(status.wireValue),
        JsonPrimitive(result.wireValue),
        JsonPrimitive(priority.wireValue),
        JsonPrimitive(buildId),
        JsonPrimitive(durationMillis),
        JsonPrimitive(count),
        JsonPrimitive(byteCount)
      )
    ).toString()
  }

  fun reducerRequestBytes(): ByteArray = reducerRequestBody().toByteArray(Charsets.UTF_8)

  companion object {
    private const val CORRELATION_ID_LENGTH = 24
    private const val MAX_BUILD_ID_LENGTH = 96
    private val correlationPattern = Regex("^[0-9a-f]{$CORRELATION_ID_LENGTH}$")
    private val buildIdPattern = Regex("^[A-Za-z0-9._-]{1,$MAX_BUILD_ID_LENGTH}$")

    fun create(
      draft: OrchestratorTelemetryDraft,
      correlationId: String,
      buildId: String,
      createdAtEpochMillis: Long
    ): OrchestratorTelemetryPayload {
      require(correlationPattern.matches(correlationId)) {
        "correlationId must be 24 lowercase hexadecimal characters"
      }
      require(buildIdPattern.matches(buildId) && !looksLikeIpv4(buildId)) {
        "buildId must be a bounded release token and not an IP address"
      }
      return OrchestratorTelemetryPayload(
        correlationId = correlationId,
        eventType = draft.eventType,
        component = draft.component,
        cleanupCategory = draft.cleanupCategory,
        status = draft.status,
        result = draft.result,
        priority = draft.priority,
        buildId = buildId,
        durationMillis = draft.durationMillis,
        count = draft.count,
        byteCount = draft.byteCount,
        createdAtEpochMillis = createdAtEpochMillis
      )
    }

    fun validateBuildId(buildId: String) {
      require(buildIdPattern.matches(buildId) && !looksLikeIpv4(buildId)) {
        "buildId must be a bounded release token and not an IP address"
      }
    }

    private fun looksLikeIpv4(value: String): Boolean {
      val parts = value.split('.')
      return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) &&
          part.toIntOrNull()?.let { it in 0..255 } == true
      }
    }
  }
}

internal fun interface OrchestratorTelemetryCorrelationIdFactory {
  fun nextId(): String
}

internal object SecureOrchestratorTelemetryCorrelationIds : OrchestratorTelemetryCorrelationIdFactory {
  private val random = SecureRandom()
  private val alphabet = "0123456789abcdef".toCharArray()

  override fun nextId(): String {
    val bytes = ByteArray(12)
    random.nextBytes(bytes)
    return buildString(24) {
      bytes.forEach { byte ->
        val value = byte.toInt() and 0xff
        append(alphabet[value ushr 4])
        append(alphabet[value and 0x0f])
      }
    }
  }
}

internal enum class OrchestratorTelemetryDeliveryState {
  PENDING,
  RETRYING,
  SENT,
  REJECTED,
  EXPIRED,
  EVICTED,
  DROPPED
}

internal data class OrchestratorTelemetryRecentEvent(
  val correlationId: String,
  val eventType: OrchestratorTelemetryEventType,
  val component: OrchestratorTelemetryComponent,
  val cleanupCategory: OrchestratorTelemetryCleanupCategory,
  val status: OrchestratorTelemetryStatus,
  val result: OrchestratorTelemetryResult,
  val priority: OrchestratorTelemetryPriority,
  val durationMillis: Long,
  val count: Long,
  val byteCount: Long,
  val createdAtEpochMillis: Long,
  val deliveryState: OrchestratorTelemetryDeliveryState
)

internal enum class OrchestratorTelemetryDropReason {
  EVENT_TOO_LARGE,
  HIGHER_PRIORITY_BACKLOG,
  DUPLICATE_CORRELATION_ID
}

internal sealed interface OrchestratorTelemetryEnqueueResult {
  data class Accepted(
    val correlationId: String,
    val evictedEventCount: Int,
    val queuedSerializedBytes: Int
  ) : OrchestratorTelemetryEnqueueResult

  data class Dropped(val reason: OrchestratorTelemetryDropReason) : OrchestratorTelemetryEnqueueResult
}

internal sealed interface OrchestratorTelemetrySendResult {
  data object Success : OrchestratorTelemetrySendResult
  data class Retryable(val statusCode: Int? = null) : OrchestratorTelemetrySendResult
  data class Rejected(val statusCode: Int) : OrchestratorTelemetrySendResult
}

internal fun interface OrchestratorTelemetryTransport {
  suspend fun send(payload: OrchestratorTelemetryPayload): OrchestratorTelemetrySendResult
}

internal data class OrchestratorTelemetryDrainResult(
  val sent: Int,
  val retryScheduled: Int,
  val rejected: Int,
  val remaining: Int
)

internal data class OrchestratorTelemetryBackoffPolicy(
  val baseDelayMillis: Long = 1_000,
  val maxDelayMillis: Long = 5 * 60 * 1_000L
) {
  init {
    require(baseDelayMillis > 0) { "baseDelayMillis must be positive" }
    require(maxDelayMillis >= baseDelayMillis) {
      "maxDelayMillis must not be shorter than baseDelayMillis"
    }
  }

  fun delayMillis(failureCount: Int): Long {
    require(failureCount > 0) { "failureCount must be positive" }
    var delay = baseDelayMillis
    repeat((failureCount - 1).coerceAtMost(62)) {
      if (delay >= maxDelayMillis) return maxDelayMillis
      delay = (delay * 2).coerceAtMost(maxDelayMillis)
    }
    return delay
  }
}

/**
 * A process-memory-only sender. This type has no Context, preferences, file,
 * database, or cache dependency, so process death and reboot intentionally
 * discard the queue and its recent-event view.
 */
internal class OrchestratorTelemetryClient(
  private val buildId: String,
  private val transport: OrchestratorTelemetryTransport,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val correlationIds: OrchestratorTelemetryCorrelationIdFactory =
    SecureOrchestratorTelemetryCorrelationIds,
  private val backoffPolicy: OrchestratorTelemetryBackoffPolicy =
    OrchestratorTelemetryBackoffPolicy(),
  private val maxQueueBytes: Int = MAX_QUEUE_BYTES,
  private val maxEventAgeMillis: Long = MAX_EVENT_AGE_MILLIS,
  private val recentEventLimit: Int = MAX_RECENT_EVENTS
) {
  private data class QueuedEvent(
    val payload: OrchestratorTelemetryPayload,
    val serializedBytes: Int,
    var failureCount: Int = 0,
    var nextAttemptAtMillis: Long = 0,
    var inFlight: Boolean = false
  )

  private data class RecentRecord(
    val payload: OrchestratorTelemetryPayload,
    var state: OrchestratorTelemetryDeliveryState
  )

  private val lock = Any()
  private val queue = mutableListOf<QueuedEvent>()
  private val recent = mutableListOf<RecentRecord>()
  private var queuedBytes = 0
  private var cumulativeDroppedCount = 0L
  private var unreportedDroppedCount = 0L

  init {
    OrchestratorTelemetryPayload.validateBuildId(buildId)
    require(maxQueueBytes in 1..MAX_QUEUE_BYTES) { "queue must be at most 4 MiB" }
    require(maxEventAgeMillis in 1..MAX_EVENT_AGE_MILLIS) {
      "event age must be at most 24 hours"
    }
    require(recentEventLimit in 1..MAX_RECENT_EVENTS) {
      "recent event view must contain at most 20 events"
    }
  }

  fun enqueue(draft: OrchestratorTelemetryDraft): OrchestratorTelemetryEnqueueResult {
    synchronized(lock) {
      val now = nowMillis()
      pruneExpiredLocked(now)
      val correlationId = uniqueCorrelationIdLocked()
        ?: return OrchestratorTelemetryEnqueueResult.Dropped(
          OrchestratorTelemetryDropReason.DUPLICATE_CORRELATION_ID
        ).also { recordDropLocked() }
      val payload = OrchestratorTelemetryPayload.create(draft, correlationId, buildId, now)
      return enqueuePayloadLocked(payload, trackIncomingDrop = true)
    }
  }

  suspend fun drainDue(maxEvents: Int = 32): OrchestratorTelemetryDrainResult {
    require(maxEvents > 0) { "maxEvents must be positive" }
    synchronized(lock) {
      val now = nowMillis()
      pruneExpiredLocked(now)
      enqueueDroppedSummaryLocked(now)
    }

    var sent = 0
    var retryScheduled = 0
    var rejected = 0
    repeat(maxEvents) {
      val pending = synchronized(lock) {
        val now = nowMillis()
        pruneExpiredLocked(now)
        nextReadyEventLocked(now)?.also { it.inFlight = true }
      } ?: return OrchestratorTelemetryDrainResult(
        sent = sent,
        retryScheduled = retryScheduled,
        rejected = rejected,
        remaining = queuedEventCount()
      )

      val sendResult = try {
        transport.send(pending.payload)
      } catch (cancelled: CancellationException) {
        synchronized(lock) { pending.inFlight = false }
        throw cancelled
      } catch (_: Throwable) {
        OrchestratorTelemetrySendResult.Retryable()
      }

      synchronized(lock) {
        val current = queue.firstOrNull {
          it.payload.correlationId == pending.payload.correlationId
        }
        if (current == null) {
          return@synchronized
        }
        current.inFlight = false
        when (sendResult) {
          OrchestratorTelemetrySendResult.Success -> {
            removeQueuedLocked(current)
            updateRecentStateLocked(
              current.payload.correlationId,
              OrchestratorTelemetryDeliveryState.SENT
            )
            sent += 1
          }
          is OrchestratorTelemetrySendResult.Retryable -> {
            current.failureCount += 1
            current.nextAttemptAtMillis = saturatingAdd(
              nowMillis(),
              backoffPolicy.delayMillis(current.failureCount)
            )
            updateRecentStateLocked(
              current.payload.correlationId,
              OrchestratorTelemetryDeliveryState.RETRYING
            )
            retryScheduled += 1
          }
          is OrchestratorTelemetrySendResult.Rejected -> {
            removeQueuedLocked(current)
            updateRecentStateLocked(
              current.payload.correlationId,
              OrchestratorTelemetryDeliveryState.REJECTED
            )
            recordDropLocked()
            rejected += 1
          }
        }
      }
    }
    return OrchestratorTelemetryDrainResult(
      sent = sent,
      retryScheduled = retryScheduled,
      rejected = rejected,
      remaining = queuedEventCount()
    )
  }

  fun recentEvents(): List<OrchestratorTelemetryRecentEvent> {
    synchronized(lock) {
      pruneExpiredLocked(nowMillis())
      return recent.asReversed().map { record ->
        val payload = record.payload
        OrchestratorTelemetryRecentEvent(
          correlationId = payload.correlationId,
          eventType = payload.eventType,
          component = payload.component,
          cleanupCategory = payload.cleanupCategory,
          status = payload.status,
          result = payload.result,
          priority = payload.priority,
          durationMillis = payload.durationMillis,
          count = payload.count,
          byteCount = payload.byteCount,
          createdAtEpochMillis = payload.createdAtEpochMillis,
          deliveryState = record.state
        )
      }
    }
  }

  fun queuedEventCount(): Int = synchronized(lock) {
    pruneExpiredLocked(nowMillis())
    queue.size
  }

  fun queuedSerializedBytes(): Int = synchronized(lock) {
    pruneExpiredLocked(nowMillis())
    queuedBytes
  }

  fun droppedEventCount(): Long = synchronized(lock) { cumulativeDroppedCount }

  private fun enqueueDroppedSummaryLocked(now: Long) {
    if (unreportedDroppedCount <= 0) return
    val correlationId = uniqueCorrelationIdLocked() ?: return
    val countToReport = unreportedDroppedCount.coerceAtMost(1_000_000_000L)
    val payload = OrchestratorTelemetryPayload.create(
      draft = OrchestratorTelemetryDraft(
        eventType = OrchestratorTelemetryEventType.DROPPED_EVENT_SUMMARY,
        component = OrchestratorTelemetryComponent.TELEMETRY,
        status = OrchestratorTelemetryStatus.DEGRADED,
        result = OrchestratorTelemetryResult.DROPPED,
        priority = OrchestratorTelemetryPriority.HIGH,
        count = countToReport
      ),
      correlationId = correlationId,
      buildId = buildId,
      createdAtEpochMillis = now
    )
    val result = enqueuePayloadLocked(payload, trackIncomingDrop = false)
    if (result is OrchestratorTelemetryEnqueueResult.Accepted) {
      unreportedDroppedCount = (unreportedDroppedCount - countToReport).coerceAtLeast(0)
    }
  }

  private fun enqueuePayloadLocked(
    payload: OrchestratorTelemetryPayload,
    trackIncomingDrop: Boolean
  ): OrchestratorTelemetryEnqueueResult {
    val serializedBytes = payload.reducerRequestBytes().size
    if (serializedBytes > maxQueueBytes) {
      addRecentLocked(payload, OrchestratorTelemetryDeliveryState.DROPPED)
      if (trackIncomingDrop) recordDropLocked()
      return OrchestratorTelemetryEnqueueResult.Dropped(
        OrchestratorTelemetryDropReason.EVENT_TOO_LARGE
      )
    }

    val bytesToFree = (queuedBytes.toLong() + serializedBytes - maxQueueBytes)
      .coerceAtLeast(0)
    val evictionCandidates = queue.withIndex()
      .filter { (_, queued) ->
        !queued.inFlight && queued.payload.priority.rank <= payload.priority.rank
      }
      .sortedWith(
        compareBy<IndexedValue<QueuedEvent>> { it.value.payload.priority.rank }
          .thenBy { it.value.payload.createdAtEpochMillis }
          .thenBy { it.index }
      )
    val selected = mutableListOf<IndexedValue<QueuedEvent>>()
    var selectedBytes = 0L
    for (candidate in evictionCandidates) {
      if (selectedBytes >= bytesToFree) break
      selected += candidate
      selectedBytes += candidate.value.serializedBytes
    }
    if (selectedBytes < bytesToFree) {
      addRecentLocked(payload, OrchestratorTelemetryDeliveryState.DROPPED)
      if (trackIncomingDrop) recordDropLocked()
      return OrchestratorTelemetryEnqueueResult.Dropped(
        OrchestratorTelemetryDropReason.HIGHER_PRIORITY_BACKLOG
      )
    }

    selected.sortedByDescending { it.index }.forEach { candidate ->
      val removed = queue.removeAt(candidate.index)
      queuedBytes -= removed.serializedBytes
      updateRecentStateLocked(
        removed.payload.correlationId,
        OrchestratorTelemetryDeliveryState.EVICTED
      )
      recordDropLocked()
    }
    queue += QueuedEvent(payload = payload, serializedBytes = serializedBytes)
    queuedBytes += serializedBytes
    addRecentLocked(payload, OrchestratorTelemetryDeliveryState.PENDING)
    return OrchestratorTelemetryEnqueueResult.Accepted(
      correlationId = payload.correlationId,
      evictedEventCount = selected.size,
      queuedSerializedBytes = queuedBytes
    )
  }

  private fun uniqueCorrelationIdLocked(): String? {
    repeat(8) {
      val candidate = correlationIds.nextId()
      val valid = runCatching {
        OrchestratorTelemetryPayload.create(
          draft = OrchestratorTelemetryDraft(
            eventType = OrchestratorTelemetryEventType.APP_SESSION,
            component = OrchestratorTelemetryComponent.ORCHESTRATOR,
            status = OrchestratorTelemetryStatus.UNKNOWN
          ),
          correlationId = candidate,
          buildId = buildId,
          createdAtEpochMillis = 0
        )
      }.isSuccess
      if (valid && queue.none { it.payload.correlationId == candidate }
        && recent.none { it.payload.correlationId == candidate }
      ) {
        return candidate
      }
    }
    return null
  }

  private fun nextReadyEventLocked(now: Long): QueuedEvent? {
    return queue.withIndex()
      .filter { (_, queued) -> !queued.inFlight && queued.nextAttemptAtMillis <= now }
      .minWithOrNull(
        compareByDescending<IndexedValue<QueuedEvent>> { it.value.payload.priority.rank }
          .thenBy { it.value.payload.createdAtEpochMillis }
          .thenBy { it.index }
      )
      ?.value
  }

  private fun pruneExpiredLocked(now: Long) {
    val expired = queue.filter { queued ->
      !queued.inFlight && elapsedAtLeast(now, queued.payload.createdAtEpochMillis, maxEventAgeMillis)
    }
    expired.forEach { queued ->
      removeQueuedLocked(queued)
      updateRecentStateLocked(
        queued.payload.correlationId,
        OrchestratorTelemetryDeliveryState.EXPIRED
      )
      recordDropLocked()
    }
  }

  private fun removeQueuedLocked(queued: QueuedEvent) {
    if (queue.remove(queued)) {
      queuedBytes -= queued.serializedBytes
    }
  }

  private fun addRecentLocked(
    payload: OrchestratorTelemetryPayload,
    state: OrchestratorTelemetryDeliveryState
  ) {
    recent += RecentRecord(payload, state)
    while (recent.size > recentEventLimit) {
      recent.removeAt(0)
    }
  }

  private fun updateRecentStateLocked(
    correlationId: String,
    state: OrchestratorTelemetryDeliveryState
  ) {
    recent.lastOrNull { it.payload.correlationId == correlationId }?.state = state
  }

  private fun recordDropLocked() {
    cumulativeDroppedCount = saturatingAdd(cumulativeDroppedCount, 1)
    unreportedDroppedCount = saturatingAdd(unreportedDroppedCount, 1)
  }

  private fun elapsedAtLeast(now: Long, then: Long, duration: Long): Boolean {
    return now >= then && now - then >= duration
  }

  private fun saturatingAdd(left: Long, right: Long): Long {
    if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
    return left + right
  }

  companion object {
    const val MAX_QUEUE_BYTES: Int = 4 * 1024 * 1024
    const val MAX_EVENT_AGE_MILLIS: Long = 24L * 60 * 60 * 1_000
    const val MAX_RECENT_EVENTS: Int = 20
  }
}
