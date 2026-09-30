package lv.jolkins.pixelorchestrator.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1

internal object NativeRedeployPolicy {
  // Explicit defaults preserve the existing typed configuration and health values at JNI ingress.
  private val json = Json { encodeDefaults = true }
  init { System.loadLibrary("pixel_health") }
  private external fun decide(operation: String, payload: String): String
  private inline fun <reified T> call(operation: String, args: JsonObject): T =
    json.decodeFromJsonElement(json.parseToJsonElement(decide(operation, args.toString())))

  @Serializable data class Policy(val healthWaitMillis: Long, val healthRetryMillis: Long, val neighborGraceMillis: Long)
  @Serializable data class State(val deadline: Long, val watchedNeighbors: List<String>, val targetHealthySince: Long? = null, val regressedNeighborSince: Map<String, Long> = emptyMap())
  @Serializable data class Step(val state: State, val terminal: Boolean, val success: Boolean, val gateHealthy: Boolean, val regressedNeighbors: List<String>, val stabilityWindowSatisfied: Boolean, val remainingSeconds: Long)
  @Serializable enum class RollbackStrategy { NONE, PREVIOUS_CURRENT_RELEASE }
  @Serializable data class Spec(
    val requestedComponent: String,
    val runtimeConfigComponent: String,
    val runtimeAssetComponent: String,
    val releaseManifestComponent: String?,
    val releaseInstallComponent: String?,
    val runtimeAction: String,
    val runtimeActionComponent: String,
    val stopComponent: String,
    val requiresQuiescentInstall: Boolean,
    val quiescenceProbeAdapter: String,
    val quiescenceProbeScript: String,
    val staleCleanupCommand: String,
    val rollbackStrategy: RollbackStrategy,
    val rollbackComponent: String,
    val retryBudget: Int,
    val healthGateComponents: List<String>,
    val targetComponents: Set<String>,
    val requiresReleaseManifest: Boolean
  )

  fun spec(component: String): Spec = call("spec", buildJsonObject { put("component", component) })
  fun rollbackCode(gateHealthy: Boolean, regressed: List<String>, stable: Boolean): String = call("rollback_code", buildJsonObject {
    put("gateHealthy", gateHealthy); put("regressed", json.encodeToJsonElement(regressed)); put("stable", stable)
  })
  fun issues(gateHealthy: Boolean, regressed: List<String>, stable: Boolean): String = call("issues", buildJsonObject {
    put("gateHealthy", gateHealthy); put("regressed", json.encodeToJsonElement(regressed)); put("stable", stable)
  })
  fun message(spec: Spec, gateHealthy: Boolean, regressed: List<String>): String = call("message", buildJsonObject {
    put("component", spec.requestedComponent); put("gates", json.encodeToJsonElement(spec.healthGateComponents)); put("gateHealthy", gateHealthy); put("regressed", json.encodeToJsonElement(regressed))
  })

  fun enabled(config: StackConfigV1, component: String): Boolean = call("enabled", buildJsonObject {
    put("config", json.encodeToJsonElement(config)); put("component", component)
  })
  fun healthy(snapshot: HealthSnapshot, component: String): Boolean = call("healthy", buildJsonObject {
    put("snapshot", json.encodeToJsonElement(snapshot)); put("component", component)
  })
  fun ready(snapshot: HealthSnapshot, component: String, disabled: Set<String>): Boolean = call("ready", buildJsonObject {
    put("snapshot", json.encodeToJsonElement(snapshot)); put("component", component); put("disabled", json.encodeToJsonElement(disabled))
  })
  fun policy(config: StackConfigV1): Policy = call("policy", buildJsonObject { put("config", json.encodeToJsonElement(config)) })
  fun neighbors(preMutation: HealthSnapshot, targets: Set<String>, supported: Set<String>, postMutation: HealthSnapshot? = null): List<String> =
    call(if (postMutation == null) "neighbors" else "regressions", buildJsonObject {
      put("preMutation", json.encodeToJsonElement(preMutation)); put("targets", json.encodeToJsonElement(targets)); put("supported", json.encodeToJsonElement(supported))
      put("postMutation", json.encodeToJsonElement(postMutation))
    })
  fun begin(now: Long, policy: Policy, preMutation: HealthSnapshot, targets: Set<String>, supported: Set<String>): State = call("begin", buildJsonObject {
    put("now", now); put("policy", json.encodeToJsonElement(policy)); put("preMutation", json.encodeToJsonElement(preMutation)); put("targets", json.encodeToJsonElement(targets)); put("supported", json.encodeToJsonElement(supported))
  })
  fun step(now: Long, policy: Policy, state: State, snapshot: HealthSnapshot, gates: List<String>, disabled: Set<String>): Step = call("step", buildJsonObject {
    put("now", now); put("policy", json.encodeToJsonElement(policy)); put("state", json.encodeToJsonElement(state)); put("snapshot", json.encodeToJsonElement(snapshot)); put("gates", json.encodeToJsonElement(gates)); put("disabled", json.encodeToJsonElement(disabled))
  })
}
