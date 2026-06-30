package lv.jolkins.pixelorchestrator.app

import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackPaths
import lv.jolkins.pixelorchestrator.coreconfig.StackStore
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactEntry
import lv.jolkins.pixelorchestrator.runtimeinstaller.ArtifactManifest
import lv.jolkins.pixelorchestrator.runtimeinstaller.AssetProvider
import lv.jolkins.pixelorchestrator.runtimeinstaller.ComponentReleaseManifest
import lv.jolkins.pixelorchestrator.runtimeinstaller.RuntimeInstallerControl

internal class NightlyCleanupSupport(
  private val stackStore: StackStore,
  private val rootExecutor: RootExecutor,
  private val runtimeInstaller: RuntimeInstallerControl,
  private val assetProvider: AssetProvider,
  private val json: Json
) {
  suspend fun run(trigger: CleanupTrigger, dryRun: Boolean): FacadeOperationResult {
    val startedAt = Instant.now()
    val config = stackStore.loadConfigOrDefault()
    return runCatching {
      val syncResult = runtimeInstaller.syncBundledRuntimeAssets(assetProvider, component = MANAGEMENT_ASSET_SCOPE)
      if (!syncResult.success) {
        return@runCatching persistReport(
          config = config,
          report = buildReport(
            trigger = trigger,
            dryRun = dryRun,
            status = CleanupReportStatus.FAILED,
            startedAt = startedAt,
            protectedPaths = emptyList(),
            candidates = emptyList(),
            deletedPaths = emptyList(),
            skippedPaths = emptyList(),
            failurePaths = listOf(
              CleanupPathRecord(
                category = "runtime_asset_sync",
                path = CLEANUP_SCRIPT_PATH,
                detail = syncResult.message
              )
            )
          )
        )
      }

      val protectedPaths = buildProtectedPaths(config)
      val protectedListPath = "${StackPaths.RUN}/cleanup-protected-${REPORT_STAMP.format(startedAt)}.txt"
      writeRootFile(
        path = protectedListPath,
        body =
          protectedPaths
            .map { it.path }
            .joinToString(separator = "\n", postfix = "\n"),
        mode = "0600"
      )

      try {
        val cleanupResult = rootExecutor.runScript(buildCleanupCommand(protectedListPath, dryRun))
        val scriptOutput = parseScriptOutput(cleanupResult.stdout)
        val failures =
          if (cleanupResult.ok) {
            scriptOutput.failures
          } else {
            scriptOutput.failures + CleanupPathRecord(
              category = "cleanup_script",
              path = CLEANUP_SCRIPT_PATH,
              detail = abbreviate(cleanupResult.stderr.ifBlank { cleanupResult.stdout })
            )
          }
        val status =
          when {
            failures.isNotEmpty() -> CleanupReportStatus.FAILED
            dryRun -> CleanupReportStatus.DRY_RUN
            else -> CleanupReportStatus.COMPLETED
          }
        persistReport(
          config = config,
          report = buildReport(
            trigger = trigger,
            dryRun = dryRun,
            status = status,
            startedAt = startedAt,
            protectedPaths = protectedPaths,
            candidates = scriptOutput.candidates,
            deletedPaths = scriptOutput.deletedPaths,
            skippedPaths = scriptOutput.skippedPaths,
            failurePaths = failures
          )
        )
      } finally {
        deleteRootPath(protectedListPath)
      }
    }.getOrElse { error ->
      persistReport(
        config = config,
        report = buildReport(
          trigger = trigger,
          dryRun = dryRun,
          status = CleanupReportStatus.FAILED,
          startedAt = startedAt,
          protectedPaths = emptyList(),
          candidates = emptyList(),
          deletedPaths = emptyList(),
          skippedPaths = emptyList(),
          failurePaths = listOf(
            CleanupPathRecord(
              category = "cleanup_exception",
              path = CLEANUP_SCRIPT_PATH,
              detail = "${error::class.java.name}: ${error.message ?: "(no message)"}"
            )
          )
        )
      )
    }
  }

  suspend fun buildSkippedResult(
    trigger: CleanupTrigger,
    dryRun: Boolean,
    reason: String
  ): FacadeOperationResult {
    return persistReport(
      config = stackStore.loadConfigOrDefault(),
      report = buildReport(
        trigger = trigger,
        dryRun = dryRun,
        status = CleanupReportStatus.SKIPPED,
        startedAt = Instant.now(),
        protectedPaths = emptyList(),
        candidates = emptyList(),
        deletedPaths = emptyList(),
        skippedPaths = listOf(
          CleanupPathRecord(
            category = "mutation_lock",
            path = MUTATION_LOCK_PATH,
            detail = reason
          )
        ),
        failurePaths = emptyList()
      )
    )
  }

  private suspend fun persistReport(
    config: StackConfigV1,
    report: CleanupReport
  ): FacadeOperationResult {
    val outputPath = writeCleanupReport(config, report)
    val success = report.status != CleanupReportStatus.FAILED.wireValue()
    return FacadeOperationResult(
      success = success,
      message = buildMessage(report),
      outputPath = outputPath
    )
  }

  private fun buildReport(
    trigger: CleanupTrigger,
    dryRun: Boolean,
    status: CleanupReportStatus,
    startedAt: Instant,
    protectedPaths: List<CleanupPathRecord>,
    candidates: List<CleanupPathRecord>,
    deletedPaths: List<CleanupPathRecord>,
    skippedPaths: List<CleanupPathRecord>,
    failurePaths: List<CleanupPathRecord>
  ): CleanupReport {
    val summary = summarize(protectedPaths, candidates, deletedPaths, skippedPaths, failurePaths)
    return CleanupReport(
      trigger = trigger.wireValue(),
      dryRun = dryRun,
      status = status.wireValue(),
      startedAt = startedAt.toString(),
      finishedAt = Instant.now().toString(),
      summary = summary,
      protectedPaths = protectedPaths.sortedBy { it.path },
      candidates = candidates.sortedBy { it.path },
      deletedPaths = deletedPaths.sortedBy { it.path },
      skippedPaths = skippedPaths.sortedBy { it.path },
      failurePaths = failurePaths.sortedBy { it.path }
    )
  }

  private fun summarize(
    protectedPaths: List<CleanupPathRecord>,
    candidates: List<CleanupPathRecord>,
    deletedPaths: List<CleanupPathRecord>,
    skippedPaths: List<CleanupPathRecord>,
    failurePaths: List<CleanupPathRecord>
  ): CleanupSummary {
    val categories =
      buildSet {
        candidates.forEach { add(it.category) }
        deletedPaths.forEach { add(it.category) }
        skippedPaths.forEach { add(it.category) }
        failurePaths.forEach { add(it.category) }
      }
        .sorted()
        .map { category ->
          CleanupCategorySummary(
            category = category,
            candidates = candidates.count { it.category == category },
            candidateBytes = candidates.filter { it.category == category }.sumOf { it.bytes },
            deleted = deletedPaths.count { it.category == category },
            deletedBytes = deletedPaths.filter { it.category == category }.sumOf { it.bytes },
            skipped = skippedPaths.count { it.category == category },
            failures = failurePaths.count { it.category == category }
          )
        }
    return CleanupSummary(
      protectedCount = protectedPaths.size,
      candidateCount = candidates.size,
      candidateBytes = candidates.sumOf { it.bytes },
      deletedCount = deletedPaths.size,
      deletedBytes = deletedPaths.sumOf { it.bytes },
      skippedCount = skippedPaths.size,
      failureCount = failurePaths.size,
      categories = categories
    )
  }

  private fun buildMessage(report: CleanupReport): String {
    return when (report.status) {
      CleanupReportStatus.COMPLETED.wireValue() ->
        "Cleanup complete: removed ${report.summary.deletedCount} paths and reclaimed ${report.summary.deletedBytes} bytes."
      CleanupReportStatus.DRY_RUN.wireValue() ->
        "Cleanup dry-run complete: ${report.summary.candidateCount} paths are eligible, totaling ${report.summary.candidateBytes} bytes."
      CleanupReportStatus.SKIPPED.wireValue() ->
        "Cleanup skipped: ${report.skippedPaths.firstOrNull()?.detail ?: "unknown reason"}"
      else ->
        "Cleanup failed: ${report.failurePaths.firstOrNull()?.detail ?: "unknown failure"}"
    }
  }

  private suspend fun buildProtectedPaths(config: StackConfigV1): List<CleanupPathRecord> {
    val protected = linkedMapOf<String, CleanupPathRecord>()

    fun add(path: String, category: String, detail: String) {
      val normalized = path.trim()
      if (normalized.isBlank()) {
        return
      }
      protected.putIfAbsent(
        normalized,
        CleanupPathRecord(
          category = category,
          path = normalized,
          detail = detail
        )
      )
    }

    val runtimeManifest = loadRuntimeManifest()
    add(RUNTIME_MANIFEST_FILE, "runtime_manifest", "live runtime manifest")
    runtimeManifest.artifacts.forEach { entry ->
      localArtifactPath(entry.url)?.let { add(it, "runtime_manifest_artifact", "runtime manifest artifact:${entry.id}") }
    }

    for (component in COMPONENT_RELEASE_COMPONENTS) {
      val staged = loadComponentReleaseManifestIfPresent(component) ?: continue
      add(staged.first, "component_manifest", "staged component manifest:$component")
      staged.second.artifacts.forEach { entry ->
        localArtifactPath(entry.url)?.let { add(it, "component_manifest_artifact", "component manifest artifact:$component:${entry.id}") }
      }
    }

    protectedReleasePaths(config).forEach { add(it.path, it.category, it.detail) }
    protectedRecentTermuxArtifacts().forEach { add(it.path, it.category, it.detail) }

    return protected.values.toList()
  }

  private suspend fun protectedReleasePaths(config: StackConfigV1): List<CleanupPathRecord> {
    val runtimeRoots = listOf(
      "train_bot" to config.trainBot.runtimeRoot,
      "satiksme_bot" to config.satiksmeBot.runtimeRoot,
      "site_notifier" to config.siteNotifier.runtimeRoot,
      "subscription_bot" to config.subscriptionBot.runtimeRoot
    )
    return runtimeRoots.flatMap { (component, runtimeRoot) ->
      val paths = mutableListOf<CleanupPathRecord>()
      val releases = queryReleasePaths(runtimeRoot)
      val current = releases.currentPath
      val previous = releases.releasePaths.firstOrNull { it != current } ?: releases.releasePaths.firstOrNull()
      if (current.isNotBlank()) {
        paths += CleanupPathRecord("release_dir", current, detail = "current release:$component")
      }
      if (previous != null && previous != current) {
        paths += CleanupPathRecord("release_dir", previous, detail = "rollback release:$component")
      }
      if (current.isBlank() && previous != null) {
        paths += CleanupPathRecord("release_dir", previous, detail = "fallback protected release:$component")
      }
      paths
    }
  }

  private suspend fun protectedRecentTermuxArtifacts(): List<CleanupPathRecord> {
    val protected = mutableListOf<CleanupPathRecord>()

    val siteNotifierMirrorPaths =
      listPathsSortedByMtime("$TERMUX_HOME/telegram-train-app/workloads/site-notifications/.artifacts/site-notifier/*") +
        listPathsSortedByMtime("$TERMUX_HOME/telegram-train-app/workloads/site-notifications/.artifacts/component-releases/*")
    val retainedSiteNotifierGroups = retainNewestGroups(siteNotifierMirrorPaths, limit = 2) { path ->
      SITE_NOTIFIER_COHORT_REGEX.find(path)?.value?.replace('_', '-')
    }
    retainedSiteNotifierGroups.forEach { path ->
      protected += CleanupPathRecord("termux_artifact", path, detail = "recent Termux notifier cohort")
    }

    listPathsSortedByMtime("$TERMUX_HOME/telegram-train-app/orchestrator/.artifacts/runtime-local/*")
      .take(2)
      .forEach { path ->
        protected += CleanupPathRecord("termux_artifact", path, detail = "recent Termux orchestrator snapshot")
      }

    listPathsSortedByMtime("$TERMUX_HOME/site-notifications-build*")
      .take(2)
      .forEach { path ->
        protected += CleanupPathRecord("termux_artifact", path, detail = "recent Termux build dir")
      }

    return protected
  }

  private fun retainNewestGroups(
    paths: List<String>,
    limit: Int,
    keySelector: (String) -> String?
  ): List<String> {
    val selectedKeys = linkedSetOf<String>()
    val retained = mutableListOf<String>()
    for (path in paths) {
      val key = keySelector(path) ?: continue
      if (key in selectedKeys || selectedKeys.size < limit) {
        selectedKeys += key
        retained += path
      }
    }
    return retained
  }

  private suspend fun queryReleasePaths(runtimeRoot: String): ReleasePathQuery {
    val script = """
      set -eu
      runtime_root=${singleQuote(runtimeRoot)}
      current_target="$(readlink -f "${'$'}runtime_root/current" 2>/dev/null || true)"
      if [ -n "${'$'}current_target" ]; then
        printf 'CURRENT\t%s\n' "${'$'}current_target"
      fi
      for dir in $(ls -1dt "${'$'}runtime_root"/releases/* 2>/dev/null || true); do
        printf 'RELEASE\t%s\n' "${'$'}dir"
      done
    """.trimIndent()
    val result = rootExecutor.runScript(script)
    if (!result.ok) {
      error("Failed to discover protected release paths for $runtimeRoot: ${result.stderr}")
    }
    var currentPath = ""
    val releasePaths = mutableListOf<String>()
    result.stdout.lineSequence().forEach { line ->
      val parts = line.split('\t', limit = 2)
      if (parts.size != 2) {
        return@forEach
      }
      when (parts[0]) {
        "CURRENT" -> currentPath = parts[1]
        "RELEASE" -> releasePaths += parts[1]
      }
    }
    return ReleasePathQuery(currentPath = currentPath, releasePaths = releasePaths)
  }

  private suspend fun listPathsSortedByMtime(glob: String): List<String> {
    val script = "ls -1dt $glob 2>/dev/null || true"
    val result = rootExecutor.runScript(script)
    if (!result.ok) {
      error("Failed to list cleanup paths for $glob: ${result.stderr}")
    }
    return result.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
  }

  private suspend fun loadRuntimeManifest(): ArtifactManifest {
    val result = rootExecutor.run("if [ -f '${RUNTIME_MANIFEST_FILE}' ]; then cat '${RUNTIME_MANIFEST_FILE}'; fi")
    if (!result.ok) {
      error("Failed to read runtime manifest at $RUNTIME_MANIFEST_FILE: ${result.stderr}")
    }
    val raw = result.stdout.trim()
    if (raw.isBlank()) {
      error("Missing runtime manifest at $RUNTIME_MANIFEST_FILE")
    }
    val manifest = json.decodeFromString<ArtifactManifest>(raw)
    require(manifest.manifestVersion.isNotBlank()) { "Runtime manifest version is required" }
    require(manifest.artifacts.isNotEmpty()) { "Runtime manifest artifacts are required" }
    manifest.artifacts.forEach { entry ->
      require(isLocalArtifactUrl(entry.url)) { "Runtime artifact ${entry.id} must use a local path" }
    }
    return manifest
  }

  private suspend fun loadComponentReleaseManifestIfPresent(component: String): Pair<String, ComponentReleaseManifest>? {
    val manifestPath = componentReleaseManifestPath(component)
    val result = rootExecutor.run("if [ -f '${manifestPath}' ]; then cat '${manifestPath}'; fi")
    if (!result.ok) {
      error("Failed to read component release manifest at $manifestPath: ${result.stderr}")
    }
    val raw = result.stdout.trim()
    if (raw.isBlank()) {
      return null
    }
    val manifest = json.decodeFromString<ComponentReleaseManifest>(raw)
    require(manifest.componentId == component) { "Component manifest mismatch for $component" }
    require(manifest.releaseId.isNotBlank()) { "Component release id is required for $component" }
    require(manifest.artifacts.isNotEmpty()) { "Component release artifacts are required for $component" }
    manifest.artifacts.forEach { entry ->
      require(isLocalArtifactUrl(entry.url)) { "Component artifact ${entry.id} must use a local path" }
    }
    return manifestPath to manifest
  }

  private fun buildCleanupCommand(protectedListPath: String, dryRun: Boolean): String {
    val dryRunArg = if (dryRun) " --dry-run" else ""
    return buildString {
      append(singleQuote(CLEANUP_SCRIPT_PATH))
      append(" --protected-list ")
      append(singleQuote(protectedListPath))
      append(" --stack-base ")
      append(singleQuote(StackPaths.BASE))
      append(" --orchestrator-cache ")
      append(singleQuote(ORCHESTRATOR_CACHE_DIR))
      append(" --termux-home ")
      append(singleQuote(TERMUX_HOME))
      append(" --artifact-age-days ")
      append(CLEANUP_RETENTION_DAYS)
      append(" --log-age-days ")
      append(CLEANUP_RETENTION_DAYS)
      append(dryRunArg)
    }
  }

  private suspend fun writeCleanupReport(config: StackConfigV1, report: CleanupReport): String {
    val outputDir = config.observability.eventOutputDir.trim().ifBlank { DEFAULT_EVENT_OUTPUT_DIR }.removeSuffix("/")
    val fileName = buildString {
      append("cleanup-")
      append(REPORT_STAMP.format(Instant.parse(report.finishedAt)))
      if (report.dryRun) {
        append("-dry-run")
      }
      append(".json")
    }
    val outputPath = "$outputDir/$fileName"
    writeRootFile(outputPath, json.encodeToString(CleanupReport.serializer(), report), mode = "0600")
    return outputPath
  }

  private suspend fun writeRootFile(path: String, body: String, mode: String) {
    val tempFile = withContext(Dispatchers.IO) {
      File.createTempFile("cleanup-stage-", ".tmp").apply {
        writeText(body, Charsets.UTF_8)
      }
    }
    try {
      val script = buildString {
        appendLine("set -eu")
        appendLine("source_file=${singleQuote(tempFile.absolutePath)}")
        appendLine("target=${singleQuote(path)}")
        appendLine("parent=$(dirname \"${'$'}target\")")
        appendLine("tmp=\"${'$'}{target}.tmp\"")
        appendLine("mkdir -p \"${'$'}parent\"")
        appendLine("cp \"${'$'}source_file\" \"${'$'}tmp\"")
        appendLine("chmod $mode \"${'$'}tmp\"")
        appendLine("mv \"${'$'}tmp\" \"${'$'}target\"")
      }
      val result = rootExecutor.runScript(script)
      if (!result.ok) {
        error("Failed to write root file $path: ${result.stderr}")
      }
    } finally {
      withContext(Dispatchers.IO) {
        tempFile.delete()
      }
    }
  }

  private suspend fun deleteRootPath(path: String) {
    val result = rootExecutor.run("rm -rf ${singleQuote(path)}")
    if (!result.ok) {
      Log.w(TAG, "cleanup_temp_delete_failed path=$path detail=${abbreviate(result.stderr)}")
    }
  }

  private fun parseScriptOutput(stdout: String): CleanupScriptOutput {
    val candidates = mutableListOf<CleanupPathRecord>()
    val deleted = mutableListOf<CleanupPathRecord>()
    val skipped = mutableListOf<CleanupPathRecord>()
    val failures = mutableListOf<CleanupPathRecord>()

    stdout.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
      val parts = line.split('\t', limit = 5)
      if (parts.size < 4) {
        return@forEach
      }
      val type = parts[0]
      val category = parts[1]
      val bytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
      val path = parts.getOrNull(3).orEmpty()
      val detail = parts.getOrNull(4).orEmpty()
      val record = CleanupPathRecord(category = category, path = path, bytes = bytes, detail = detail)
      when (type) {
        "CANDIDATE" -> candidates += record
        "DELETE" -> deleted += record
        "SKIP" -> skipped += record
        "FAIL" -> failures += record
      }
    }

    return CleanupScriptOutput(
      candidates = candidates,
      deletedPaths = deleted,
      skippedPaths = skipped,
      failures = failures
    )
  }

  private fun componentReleaseManifestPath(component: String): String {
    return "/data/local/pixel-stack/conf/runtime/components/$component/release-manifest.json"
  }

  private fun localArtifactPath(rawUrl: String): String? {
    val url = rawUrl.trim()
    return when {
      url.startsWith("file://", ignoreCase = true) -> url.removePrefix("file://")
      url.startsWith("/") -> url
      else -> null
    }
  }

  private fun isLocalArtifactUrl(rawUrl: String): Boolean {
    return localArtifactPath(rawUrl)?.startsWith("/") == true
  }

  private fun singleQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
  }

  private fun abbreviate(value: String): String {
    val normalized = value.trim().replace("\n", "\\n")
    if (normalized.isEmpty()) {
      return "<empty>"
    }
    return normalized.take(MAX_LOG_FIELD_LENGTH)
  }

  private data class ReleasePathQuery(
    val currentPath: String,
    val releasePaths: List<String>
  )

  private data class CleanupScriptOutput(
    val candidates: List<CleanupPathRecord>,
    val deletedPaths: List<CleanupPathRecord>,
    val skippedPaths: List<CleanupPathRecord>,
    val failures: List<CleanupPathRecord>
  )

  private companion object {
    const val TAG = "NightlyCleanupSupport"
    const val MAX_LOG_FIELD_LENGTH = 600
    const val MANAGEMENT_ASSET_SCOPE = "management"
    const val DEFAULT_EVENT_OUTPUT_DIR = "${StackPaths.LOG}/events"
    const val CLEANUP_SCRIPT_PATH = "${StackPaths.BIN}/pixel-runtime-cleanup.sh"
    const val MUTATION_LOCK_PATH = "${StackPaths.RUN}/orchestrator-mutation.lock"
    const val ORCHESTRATOR_CACHE_DIR = "/data/user/0/lv.jolkins.pixelorchestrator/cache"
    const val TERMUX_HOME = "/data/user/0/com.termux/files/home"
    const val CLEANUP_RETENTION_DAYS = 30
    const val RUNTIME_MANIFEST_FILE = "/data/local/pixel-stack/conf/runtime/runtime-manifest.json"
    val REPORT_STAMP: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC)
    val SITE_NOTIFIER_COHORT_REGEX = Regex("""site[-_]notifier-\d{8}T\d{6}Z""")
    val COMPONENT_RELEASE_COMPONENTS = listOf("dns", "train_bot", "satiksme_bot", "site_notifier", "subscription_bot")
  }
}
