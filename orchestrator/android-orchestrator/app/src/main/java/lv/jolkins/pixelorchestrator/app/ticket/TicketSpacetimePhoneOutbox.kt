package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal object TicketSpacetimeCriticalMessagePolicy {
  private val requiredStartupTraceEvents = setOf(
    "stream_client_opened",
    "stream_client_immediate_start",
    "stream_client_immediate_start_result",
    "stream_client_immediate_start_coalesced",
    "startup_phase_pixel_start_command_received",
    "startup_phase_session_start_requested",
    "startup_phase_root_capture_start_requested",
    "startup_phase_capture_helper_active",
    "startup_phase_vivi_foreground_confirmed",
    "startup_phase_first_keyframe_encoded"
  )

  fun key(payload: JsonObject): String? {
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
      "ticket_trace_event" -> {
        val event = payload.stringValue("event")
        val traceId = boundedStartupTraceId(payload.stringValue("traceId"))
        when {
          event.startsWith("latest_ticket_reselect_final_") -> {
          val eventIdentity = payload.stringValue("eventAtPhoneUptimeMillis")
            .ifBlank { payload.stringValue("eventAtEpochMillis") }
            .ifBlank { event }
          "$type:latest_ticket_reselect_final:$eventIdentity"
          }
          event in requiredStartupTraceEvents && traceId.isNotBlank() ->
            "$type:required_startup:$traceId:$event"
          else -> null
        }
      }
      else -> null
    }
  }

  fun replacement(payload: JsonObject): TicketSpacetimeCriticalReplacement? {
    if (payload.stringValue("type") != "ticket_trace_event") return null
    val event = payload.stringValue("event")
    if (event !in requiredStartupTraceEvents) return null
    val traceId = boundedStartupTraceId(payload.stringValue("traceId"))
    if (traceId.isBlank()) return null
    val eventAtMillis = payload.stringValue("eventAtPhoneUptimeMillis").toLongOrNull()
      ?.takeIf { it > 0L }
      ?: return null
    return TicketSpacetimeCriticalReplacement(
      group = "required_startup",
      generation = traceId,
      eventAtMillis = eventAtMillis,
      socketGeneration = payload.stringValue("detail_generation").toLongOrNull()
        ?.takeIf { it > 0L }
    )
  }

  fun isRequiredStartupTraceEvent(event: String): Boolean = event in requiredStartupTraceEvents

  fun boundedStartupTraceId(value: String): String {
    val clean = value.trim()
    if (clean.length != 16 || !clean.startsWith("startup_")) return ""
    return clean.takeIf {
      clean.removePrefix("startup_").all { char -> char in '0'..'9' || char in 'a'..'f' }
    }.orEmpty()
  }

  private fun JsonObject.stringValue(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}

internal data class TicketSpacetimeCriticalReplacement(
  val group: String,
  val generation: String,
  val eventAtMillis: Long,
  val socketGeneration: Long? = null
) {
  fun isSupersededBy(current: TicketSpacetimeCriticalReplacement): Boolean {
    if (group != current.group) return false
    val incomingSocketGeneration = socketGeneration
    val currentSocketGeneration = current.socketGeneration
    if (incomingSocketGeneration != null) {
      if (currentSocketGeneration != null) {
        if (incomingSocketGeneration != currentSocketGeneration) {
          return incomingSocketGeneration < currentSocketGeneration
        }
        return generation != current.generation
      }
      return false
    }
    if (generation == current.generation) return false
    return eventAtMillis < current.eventAtMillis
  }

  fun mergedWith(current: TicketSpacetimeCriticalReplacement): TicketSpacetimeCriticalReplacement {
    if (group != current.group) return this
    return copy(
      eventAtMillis = if (generation == current.generation) {
        maxOf(eventAtMillis, current.eventAtMillis)
      } else {
        eventAtMillis
      },
      socketGeneration = listOfNotNull(socketGeneration, current.socketGeneration).maxOrNull()
    )
  }
}

internal class TicketSpacetimePhoneOutbox(
  private val maxLossyMessages: Int,
  private val maxCriticalMessages: Int = 80,
  private val criticalTtlMillis: Long,
  private val criticalKey: (String) -> String?,
  private val criticalReplacement: (String) -> TicketSpacetimeCriticalReplacement? = { null },
  private val nowMillis: () -> Long
) {
  private val lock = Any()
  private val critical = linkedMapOf<String, Pair<String, Long>>()
  private val criticalReplacementGroupByKey = mutableMapOf<String, String>()
  private val criticalReplacementState = mutableMapOf<String, TicketSpacetimeCriticalReplacement>()
  private val lossy = ArrayDeque<String>()

  fun enqueue(message: String) {
    if (message.isBlank()) return
    synchronized(lock) {
      val now = nowMillis()
      pruneExpiredLocked(now)
      val key = criticalKey(message)
      if (key != null) {
        val replacement = criticalReplacement(message)
        if (replacement != null) {
          val current = criticalReplacementState[replacement.group]
          if (current != null && replacement.isSupersededBy(current)) {
            return@synchronized
          }
          if (current == null || current.generation != replacement.generation) {
            val replacedKeys = criticalReplacementGroupByKey
              .filterValues { it == replacement.group }
              .keys
              .toList()
            replacedKeys.forEach { replacedKey ->
              critical.remove(replacedKey)
              criticalReplacementGroupByKey.remove(replacedKey)
            }
          }
          criticalReplacementState[replacement.group] = current
            ?.let(replacement::mergedWith)
            ?: replacement
        }
        if (replacement != null && critical.containsKey(key)) {
          return@synchronized
        }
        if (!critical.containsKey(key) && isStartupTraceKey(key)) {
          val startupTraceCapacity = maxCriticalMessages.coerceAtLeast(1)
          val startupTraceCount = critical.keys.count(::isStartupTraceKey)
          if (startupTraceCount >= startupTraceCapacity) {
            critical.keys.firstOrNull(::isStartupTraceKey)?.let(::removeCriticalLocked)
          }
        }
        critical[key] = message to now
        if (replacement != null) {
          criticalReplacementGroupByKey[key] = replacement.group
        }
      } else {
        lossy.addLast(message)
        while (lossy.size > maxLossyMessages) {
          lossy.removeFirst()
        }
      }
    }
  }

  fun peek(maxMessages: Int = Int.MAX_VALUE): List<String> {
    synchronized(lock) {
      pruneExpiredLocked(nowMillis())
      val count = maxMessages.coerceAtLeast(1).coerceAtMost(critical.size + lossy.size)
      if (count == 0) return emptyList()
      return buildList(count) {
        critical.entries.forEach { (key, pending) ->
          if (!isStartupTraceKey(key) && size < count) add(pending.first)
        }
        critical.entries.forEach { (key, pending) ->
          if (isStartupTraceKey(key) && size < count) add(pending.first)
        }
        lossy.forEach { message ->
          if (size < count) add(message)
        }
      }
    }
  }

  fun acknowledge(message: String) {
    synchronized(lock) {
      val key = criticalKey(message)
      if (key != null) {
        if (critical[key]?.first == message) {
          removeCriticalLocked(key)
        }
      } else {
        lossy.remove(message)
      }
    }
  }

  private fun pruneExpiredLocked(now: Long) {
    val expiredKeys = critical.entries
      .filter { (_, pending) -> now - pending.second > criticalTtlMillis }
      .map { it.key }
    expiredKeys.forEach { key ->
      removeCriticalLocked(key)
    }
  }

  private fun removeCriticalLocked(key: String) {
    critical.remove(key)
    criticalReplacementGroupByKey.remove(key)
  }

  private fun isStartupTraceKey(key: String): Boolean =
    key.startsWith("ticket_trace_event:required_startup:")
}
