package lv.jolkins.pixelorchestrator.health

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1

class RuntimeHealthChecker(
  private val commandRunner: CommandRunner
) {
  private val json = Json { encodeDefaults = true }

  suspend fun check(config: StackConfigV1): HealthSnapshot {
    val probe = commandRunner.run(buildProbeCommand(config))
    return json.decodeFromString(
      NativeHealth.interpretProbe(
        json.encodeToString(config),
        probe.ok,
        probe.stdout,
        System.currentTimeMillis() / 1000
      )
    )
  }

  private fun buildProbeCommand(config: StackConfigV1): String =
    NativeHealth.buildProbe(json.encodeToString(config))
}

internal object NativeHealth {
  init { System.loadLibrary("pixel_health") }

  @JvmStatic external fun buildProbe(config: String): String
  @JvmStatic external fun interpretProbe(config: String, ok: Boolean, stdout: String, now: Long): String
}
