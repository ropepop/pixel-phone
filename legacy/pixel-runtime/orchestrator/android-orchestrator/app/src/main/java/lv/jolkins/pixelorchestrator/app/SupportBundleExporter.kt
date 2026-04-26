package lv.jolkins.pixelorchestrator.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.SecretRedactor
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor

class SupportBundleExporter(
  private val context: Context,
  private val rootExecutor: RootExecutor,
  private val json: Json = Json { prettyPrint = true; encodeDefaults = true }
) : SupportBundleExporting {

  override suspend fun export(
    config: StackConfigV1,
    state: StackStateV1,
    includeSecrets: Boolean
  ): File {
    val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
      ?: context.filesDir
    val bundleDir = File(downloadsDir, "pixel-stack-support")
    bundleDir.mkdirs()

    val timestamp = System.currentTimeMillis() / 1000
    val root = File(bundleDir, "bundle-$timestamp")
    root.mkdirs()

    val redacted = SecretRedactor.redact(config, includeSecrets)

    File(root, "health.json").writeText(
      json.encodeToString(state.lastHealthSnapshot),
      StandardCharsets.UTF_8
    )

    File(root, "config.json").writeText(
      json.encodeToString(redacted),
      StandardCharsets.UTF_8
    )

    File(root, "state.json").writeText(
      json.encodeToString(state),
      StandardCharsets.UTF_8
    )

    exportRootLogs(root)

    return root
  }

  private suspend fun exportRootLogs(root: File) {
    val listResult = rootExecutor.run("if [ -d /data/local/pixel-stack/logs ]; then ls -1 /data/local/pixel-stack/logs; fi")
    if (!listResult.ok) {
      return
    }

    val names = listResult.stdout
      .lineSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .toList()

    for (name in names) {
      val path = "/data/local/pixel-stack/logs/$name"
      val readResult = rootExecutor.run("cat '${path.replace("'", "'\"'\"'")}'")
      if (readResult.ok) {
        val target = File(root, "logs/$name")
        target.parentFile?.mkdirs()
        target.writeText(readResult.stdout, StandardCharsets.UTF_8)
      } else {
        val target = File(root, "logs/$name.error.txt")
        target.parentFile?.mkdirs()
        target.writeText(readResult.stderr, StandardCharsets.UTF_8)
      }
    }

    val runDirResult = rootExecutor.run("if [ -d /data/local/pixel-stack/run ]; then ls -1 /data/local/pixel-stack/run; fi")
    if (!runDirResult.ok) {
      return
    }

    for (name in runDirResult.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }) {
      if (name.endsWith(".pid") || name.startsWith("ddns-last-") || name.startsWith("pihole-") || name.startsWith("adguardhome-")) {
        val path = "/data/local/pixel-stack/run/$name"
        val readResult = rootExecutor.run("cat '${path.replace("'", "'\"'\"'")}' 2>/dev/null || true")
        if (readResult.ok) {
          val target = File(root, "run/$name")
          target.parentFile?.mkdirs()
          target.writeText(readResult.stdout, StandardCharsets.UTF_8)
        }
      }
    }
  }
}
