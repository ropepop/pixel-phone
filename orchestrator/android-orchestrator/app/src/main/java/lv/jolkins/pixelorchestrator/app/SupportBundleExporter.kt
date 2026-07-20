package lv.jolkins.pixelorchestrator.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyPreferencesStore
import lv.jolkins.pixelorchestrator.coreconfig.SecretRedactor
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

class SupportBundleExporter(
  private val context: Context,
  @Suppress("UNUSED_PARAMETER") rootExecutor: RootExecutor,
  private val json: Json = Json { prettyPrint = true; encodeDefaults = true }
) : SupportBundleExporting {

  init {
    removeExistingBundles()
  }

  override suspend fun export(
    config: StackConfigV1,
    state: StackStateV1,
    includeSecrets: Boolean
  ): File {
    val bundleDir = File(context.cacheDir, SUPPORT_CACHE_DIR)
    bundleDir.mkdirs()
    removeExistingBundles()

    val timestamp = System.currentTimeMillis() / 1_000
    val target = File(bundleDir, "pixel-stack-support-$timestamp.zip")
    val temporary = File(bundleDir, "${target.name}.tmp")
    val redactedConfig = SecretRedactor.redact(config, includeSecrets = false)
    val redactedState = redactState(state)

    try {
      ZipOutputStream(FileOutputStream(temporary)).use { zip ->
        zip.putJson("config.json", json.encodeToString(redactedConfig))
        zip.putJson("state.json", json.encodeToString(redactedState))
        zip.putJson(
          "cpu-frequency-state.json",
          json.encodeToString(CpuFrequencyPreferencesStore(context).load())
        )
      }
      check(temporary.renameTo(target)) { "Failed to finalize support archive" }
      Handler(Looper.getMainLooper()).postDelayed({ target.delete() }, SUPPORT_ARCHIVE_TTL_MILLIS)
      return target
    } finally {
      temporary.delete()
    }
  }

  private fun ZipOutputStream.putJson(name: String, value: String) {
    putNextEntry(ZipEntry(name))
    write(value.toByteArray(Charsets.UTF_8))
    closeEntry()
  }

  private fun removeExistingBundles() {
    val bundleDir = File(context.cacheDir, SUPPORT_CACHE_DIR)
    bundleDir.listFiles()?.forEach { candidate ->
      if (candidate.isFile &&
        (candidate.name.matches(SUPPORT_ARCHIVE_PATTERN) || candidate.name.endsWith(".zip.tmp"))
      ) {
        candidate.delete()
      }
    }
  }

  companion object {
    const val FILE_PROVIDER_AUTHORITY = "lv.jolkins.pixelorchestrator.support"
    private const val SUPPORT_CACHE_DIR = "support-bundles"
    private const val SUPPORT_ARCHIVE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    private val SUPPORT_ARCHIVE_PATTERN = Regex("pixel-stack-support-[0-9]+\\.zip")

    fun contentUri(context: Context, archive: File): Uri {
      require(archive.parentFile?.canonicalFile == File(context.cacheDir, SUPPORT_CACHE_DIR).canonicalFile) {
        "Support archive must be inside the private support cache"
      }
      return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, archive)
    }

    fun deleteArchive(context: Context, uri: Uri): Boolean {
      if (uri.authority != FILE_PROVIDER_AUTHORITY) return false
      val archiveName = uri.lastPathSegment.orEmpty()
      if (!archiveName.matches(SUPPORT_ARCHIVE_PATTERN)) return false
      val supportDir = File(context.cacheDir, SUPPORT_CACHE_DIR).canonicalFile
      val archive = File(supportDir, archiveName).canonicalFile
      if (archive.parentFile != supportDir) return false
      return !archive.exists() || archive.delete()
    }

    internal fun redactState(state: StackStateV1): StackStateV1 {
      return state.copy(
        lastNetworkFingerprint = "",
        lastObservedPublicIpv4 = "",
        services = state.services.mapValues { (_, service) -> service.copy(lastFailureReason = "") },
        moduleState = state.moduleState.mapValues { (_, module) -> module.copy(details = emptyMap()) },
        lastHealthSnapshot = state.lastHealthSnapshot.copy(
          moduleHealth = state.lastHealthSnapshot.moduleHealth.mapValues { (_, module) ->
            module.copy(details = emptyMap())
          },
          evidence = emptyMap()
        ),
        operationLog = state.operationLog.map { event -> event.copy(details = "") }
      )
    }
  }
}
