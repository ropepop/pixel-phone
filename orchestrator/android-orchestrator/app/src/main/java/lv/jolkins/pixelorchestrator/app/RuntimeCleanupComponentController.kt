package lv.jolkins.pixelorchestrator.app

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
    if (!result.ok) {
      return degraded("report_probe_failed", mapOf("detail" to result.stderr.trim().ifBlank { "unknown" }))
    }
    if (result.stdout.isBlank()) {
      return degraded("no_report")
    }

    val path = result.stdout.lineSequence()
      .firstOrNull { it.startsWith(REPORT_PATH_PREFIX) }
      ?.removePrefix(REPORT_PATH_PREFIX)
      ?.trim()
      .orEmpty()
    val body = result.stdout.lineSequence()
      .dropWhile { !it.startsWith(REPORT_BODY_MARKER) }
      .drop(1)
      .joinToString("\n")
      .trim()
    if (body.isBlank()) {
      return degraded("report_body_missing", mapOf("report_path" to path.ifBlank { "unknown" }))
    }

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
    val failed = report.status == CleanupReportStatus.FAILED.wireValue()
    val dryRunOnly = report.dryRun
    val stale = ageSeconds < 0 || ageSeconds > HEALTH_FRESH_SECONDS
    val healthy = !failed && !dryRunOnly && !stale
    val reason = when {
      failed -> "latest_cleanup_failed"
      dryRunOnly -> "latest_cleanup_was_dry_run"
      stale -> "latest_cleanup_stale"
      else -> "ok"
    }
    return ModuleHealthState(
      healthy = healthy,
      status = if (healthy) "running" else "degraded",
      details = mapOf(
        "failure_reason" to reason,
        "report_path" to path.ifBlank { "unknown" },
        "report_status" to report.status,
        "report_age_sec" to ageSeconds.toString(),
        "deleted_bytes" to report.summary.deletedBytes.toString(),
        "deleted_count" to report.summary.deletedCount.toString(),
        "dry_run" to report.dryRun.toString()
      )
    )
  }

  private fun degraded(reason: String, details: Map<String, String> = emptyMap()): ModuleHealthState {
    return ModuleHealthState(
      healthy = false,
      status = "degraded",
      details = mapOf("failure_reason" to reason) + details
    )
  }

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
