package lv.jolkins.pixelorchestrator.supervisor

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import lv.jolkins.pixelorchestrator.coreconfig.*

/** Typed DTO adapter. Rust owns transitions; the engine retains effect ordering. */
internal object SupervisorState {
  private val json = Json { encodeDefaults = true }

  fun mark(state: StackStateV1, name: String, status: ServiceStatus, failure: String, countAsRestart: Boolean, now: Long): StackStateV1 =
    transition(state, "component", now) { put("name", name); put("status", status.name); put("failure", failure); put("countAsRestart", countAsRestart) }

  fun event(state: StackStateV1, component: String, action: String, success: Boolean, details: String, now: Long): StackStateV1 =
    transition(state, "event", now) { put("component", component); put("action", action); put("success", success); put("details", details) }

  fun status(state: StackStateV1, status: ServiceStatus, now: Long): StackStateV1 =
    transition(state, "supervisor", now) { put("status", status.name) }

  fun heartbeat(state: StackStateV1, now: Long): StackStateV1 = transition(state, "heartbeat", now) {}

  fun observe(state: StackStateV1, kind: String, snapshot: HealthSnapshot, now: Long): StackStateV1 =
    transition(state, kind, now) { put("snapshot", json.encodeToJsonElement(snapshot)) }

  fun network(previous: StackStateV1, state: StackStateV1, snapshot: HealthSnapshot, config: StackConfigV1, now: Long): NetworkObservation =
    json.decodeFromString(NativeSupervisor.network(json.encodeToString(previous), json.encodeToString(state), json.encodeToString(snapshot), json.encodeToString(config), now))

  private fun transition(state: StackStateV1, kind: String, now: Long, fields: JsonObjectBuilder.() -> Unit): StackStateV1 =
    json.decodeFromString(NativeSupervisor.transition(json.encodeToString(state), buildJsonObject { put("kind", kind); fields() }.toString(), now))
}

@Serializable
internal data class NetworkObservation(val state: StackStateV1, val fingerprintChanged: Boolean, val publicIpv4Changed: Boolean, val convergenceActive: Boolean)

internal object NativeSupervisor {
  init { System.loadLibrary("pixel_health") }
  @JvmStatic external fun transition(state: String, change: String, now: Long): String
  @JvmStatic external fun network(previous: String, state: String, snapshot: String, config: String, now: Long): String
  @JvmStatic external fun backoff(state: String, restart: Boolean, now: Long, initial: Int, maximum: Int, window: Int, rapidMaximum: Int): String
}
