package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal const val TICKET_MONITOR_INTERVAL_MILLIS = 5 * 60_000L
internal const val TICKET_MONITOR_FRESH_MILLIS = 30_000L

@Serializable
internal data class TicketMonitoringConfig(val enabled: Boolean, val epoch: String)
@Serializable
internal data class TicketMonitoringObservation(
  val status: String,
  val reason: String,
  val capturedAtMillis: Long
) {
  fun fresh(now: Long) = NativeTicketMonitoring.call("fresh", now, observation = this)["answer"]!!.jsonPrimitive.boolean
}

internal fun ticketMonitoringObservation(
  state: TicketVisualPhoneState?, busy: Boolean, capturedAtMillis: Long
): TicketMonitoringObservation = NativeTicketMonitoring.json.decodeFromJsonElement(
  NativeTicketMonitoring.call("observe", capturedAtMillis, state = state?.name, busy = busy)
)

/** A dispatched asynchronous probe is pending, not evidence that capture failed. */
internal fun ticketMonitoringProbeDispatch(probeId: Long?, now: Long): TicketMonitoringObservation? =
  if (probeId == null) ticketMonitoringObservation(null, false, now) else null

/** Health cadence only; this state cannot authorize an input or renew a control observation. */
internal class TicketMonitoringSchedule {
  private var schedule = JsonObject(emptyMap())
  val acceptCapturedAfter get() = schedule["acceptCapturedAfter"]?.jsonPrimitive?.long ?: 0L

  private fun call(operation: String, now: Long, config: TicketMonitoringConfig? = null,
    observation: TicketMonitoringObservation? = null): JsonElement {
    val result = NativeTicketMonitoring.call(operation, now, schedule, config, observation)
    schedule = result["schedule"]!!.jsonObject
    return result["answer"]!!
  }
  fun configure(next: TicketMonitoringConfig?, now: Long) = call("configure", now, next).jsonPrimitive.boolean
  fun checkDue(now: Long, observation: TicketMonitoringObservation? = null) =
    call("check_due", now, observation = observation).jsonPrimitive.boolean
  fun checked(now: Long) { call("checked", now) }
  fun shouldReport(observation: TicketMonitoringObservation, now: Long) =
    call("should_report", now, observation = observation).jsonPrimitive.boolean
  fun reported(observation: TicketMonitoringObservation, now: Long) { call("reported", now, observation = observation) }
}

/** One pure native owner; Android retains capture, clocks, scheduling and delivery. */
internal object NativeTicketMonitoring {
  val json = Json { encodeDefaults = true }
  init { System.loadLibrary("pixel_health") }
  @JvmStatic private external fun decide(operation: String, payload: String): String
  fun call(operation: String, now: Long, schedule: JsonObject = JsonObject(emptyMap()),
    config: TicketMonitoringConfig? = null, observation: TicketMonitoringObservation? = null,
    state: String? = null, busy: Boolean = false): JsonObject = json.parseToJsonElement(decide(operation, buildJsonObject {
      // An empty initial schedule uses the native defaults.
      if (schedule.isNotEmpty()) put("schedule", schedule)
      put("now", now)
      put("config", json.encodeToJsonElement(config))
      put("observation", json.encodeToJsonElement(observation))
      put("state", state?.let(::JsonPrimitive) ?: JsonNull)
      put("busy", busy)
    }.toString())).jsonObject
}
