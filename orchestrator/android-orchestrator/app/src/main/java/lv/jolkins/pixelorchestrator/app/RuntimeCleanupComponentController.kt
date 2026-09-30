package lv.jolkins.pixelorchestrator.app

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.supervisor.AutoStartAwareComponentController
import lv.jolkins.pixelorchestrator.supervisor.ComponentController
import lv.jolkins.pixelorchestrator.supervisor.ModuleHealthAwareComponentController

class RuntimeCleanupComponentController(
  private val rootExecutor: RootExecutor,
  private val json: Json
) : ComponentController, AutoStartAwareComponentController, ModuleHealthAwareComponentController {
  override val name: String = COMPONENT_NAME

  override suspend fun start(): Boolean = true

  override suspend fun stop(): Boolean = true

  override suspend fun health(): Boolean = moduleHealthState().healthy

  override suspend fun shouldAutoStart(): Boolean = false

  override suspend fun moduleHealthState(): ModuleHealthState {
    val result = rootExecutor.run(latestReportCommand())
    val protocol=NativeCleanupPolicy.call("health_protocol",buildJsonObject{put("ok",result.ok);put("stdout",result.stdout);put("stderr",result.stderr)}).jsonObject
    val path=protocol.getValue("path").jsonPrimitive.content
    val body=protocol.getValue("body").jsonPrimitive.content
    val reason=protocol.getValue("reason").jsonPrimitive.content
    if(reason.isNotEmpty()) return degraded(reason,json.decodeFromJsonElement(protocol.getValue("details")))

    val report = runCatching { json.decodeFromString<CleanupReport>(body) }.getOrElse { error ->
      return degraded(
        "report_parse_failed",
        mapOf(
          "report_path" to path.ifBlank { "unknown" },
          "detail" to (error.message ?: error::class.java.simpleName)
        )
      )
    }
    val finishedAt = runCatching { Instant.parse(report.finishedAt.ifBlank { report.startedAt }) }.getOrNull()
      ?: return degraded(
        "report_time_invalid",
        mapOf("report_path" to path.ifBlank { "unknown" }, "status" to report.status)
      )
    val ageSeconds = Duration.between(finishedAt, Instant.now()).seconds
    return NativeCleanupPolicy.value("health_result",buildJsonObject{
      put("report",json.encodeToJsonElement(report));put("path",path);put("age",ageSeconds)
    })
  }

  private fun degraded(reason:String,details:Map<String,String> = emptyMap()):ModuleHealthState =
    NativeCleanupPolicy.value("degraded",buildJsonObject{put("reason",reason);put("details",json.encodeToJsonElement(details))})

  private fun latestReportCommand(): String {
    return """
      set +e
      report_path="$(ls -1t /data/local/pixel-stack/logs/events/cleanup-*.json 2>/dev/null | grep -v -- '-dry-run.json' | sed -n '1p')"
      if [ -z "${'$'}report_path" ]; then
        report_path="$(ls -1t /data/local/pixel-stack/logs/events/cleanup-*.json 2>/dev/null | sed -n '1p')"
      fi
      if [ -n "${'$'}report_path" ] && [ -r "${'$'}report_path" ]; then
        printf '${REPORT_PATH_PREFIX}%s\n' "${'$'}report_path"
        printf '${REPORT_BODY_MARKER}\n'
        cat "${'$'}report_path" 2>/dev/null || true
      fi
    """.trimIndent()
  }

  companion object {
    const val COMPONENT_NAME = "runtime_cleanup"
    private const val REPORT_PATH_PREFIX = "REPORT_PATH\t"
    private const val REPORT_BODY_MARKER = "REPORT_BODY"
    private const val HEALTH_FRESH_SECONDS = 8L * 24L * 60L * 60L
  }
}
