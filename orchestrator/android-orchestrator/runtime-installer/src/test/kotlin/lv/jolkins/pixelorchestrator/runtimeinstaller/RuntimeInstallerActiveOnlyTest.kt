package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeInstallerActiveOnlyTest {
  @Test
  fun allAssetSyncInstallsOnlyActiveRuntimeFiles() = runBlocking {
    val root = RecordingRootExecutor()
    val installer = RuntimeInstaller(root, ArtifactSyncer(Files.createTempDirectory("active-assets")))

    val result = installer.syncBundledRuntimeAssets(ActiveAssetProvider())
    val commands = root.commands.joinToString("\n")

    assertTrue(result.success)
    listOf(
      "/templates/ssh/pixel-ssh-launch.sh",
      "/templates/vpn/pixel-vpn-launch.sh",
      "/bin/pixel-management-health.sh",
      "/bin/pixel-runtime-cleanup.sh",
      "/bin/pixel-ticket-health.sh",
      "/bin/pixel-ticket-lifecycle-lock.sh",
      "/bin/pixel-ticket-root-keyboard"
    ).forEach { expected -> assertTrue(commands.contains(expected), "missing active asset $expected") }
    listOf("adguardhome", "pixel-dns", "train-bot", "satiksme", "notifier", "subscription").forEach { retired ->
      assertFalse(commands.contains(retired), "retired asset unexpectedly installed: $retired")
    }
  }

  @Test
  fun ticketOnlySyncInstallsNativeRootKeyboard() = runBlocking {
    val root = RecordingRootExecutor()
    val installer = RuntimeInstaller(root, ArtifactSyncer(Files.createTempDirectory("ticket-assets")))

    val result = installer.syncBundledRuntimeAssets(ActiveAssetProvider(), component = "ticket_screen")
    val commands = root.commands.joinToString("\n")

    assertTrue(result.success)
    assertTrue(commands.contains("/bin/pixel-ticket-lifecycle-lock.sh"))
    assertTrue(commands.contains("/bin/pixel-ticket-root-keyboard"))
    assertTrue(commands.contains("chmod 0755"))
    assertFalse(commands.contains("/templates/ssh/"))
  }

  @Test
  fun retiredComponentSyncIsRejected() = runBlocking {
    val installer = RuntimeInstaller(
      RecordingRootExecutor(),
      ArtifactSyncer(Files.createTempDirectory("retired-assets"))
    )

    val result = installer.syncBundledRuntimeAssets(ActiveAssetProvider(), component = "train_bot")

    assertFalse(result.success)
    assertTrue(result.message.contains("Unsupported component runtime asset sync target"))
  }

  @Test
  fun bootstrapRequiresOnlySshAndVpnBundles() = runBlocking {
    val dropbear = "dropbear".toByteArray()
    val artifactDir = Files.createTempDirectory("active-bootstrap")
    val dropbearPath = artifactDir.resolve("dropbear.tar")
    Files.write(dropbearPath, dropbear)
    val manifest = ArtifactManifest(
      schema = 1,
      manifestVersion = "active-only",
      signatureSchema = "none",
      artifacts = listOf(
        ArtifactEntry(
          id = "dropbear-bundle",
          url = dropbearPath.toString(),
          sha256 = sha256(dropbear),
          fileName = "dropbear.tar",
          sizeBytes = dropbear.size.toLong(),
          required = true
        )
      )
    )
    val installer = RuntimeInstaller(
      RecordingRootExecutor(),
      ArtifactSyncer(Files.createTempDirectory("active-bootstrap-sync"))
    )

    val error = assertFailsWith<IllegalStateException> {
      installer.bootstrap(
        config = StackConfigV1(),
        assets = ActiveAssetProvider(),
        manifest = manifest
      )
    }

    assertTrue(error.message.orEmpty().contains("Missing required artifact in manifest: tailscale-bundle"))
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

  private class ActiveAssetProvider : AssetProvider {
    private val files = buildMap {
      put("ticket-root-keyboard", byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
      listOf(
        "runtime/templates/ssh/pixel-ssh-launch.sh",
        "runtime/templates/ssh/pixel-ssh-service-loop.sh",
        "runtime/templates/vpn/pixel-vpn-launch.sh",
        "runtime/templates/vpn/pixel-vpn-service-loop.sh",
        "runtime/entrypoints/pixel-ssh-start.sh",
        "runtime/entrypoints/pixel-ssh-stop.sh",
        "runtime/entrypoints/pixel-vpn-start.sh",
        "runtime/entrypoints/pixel-vpn-stop.sh",
        "runtime/entrypoints/pixel-vpn-health.sh",
        "runtime/entrypoints/pixel-management-health.sh",
        "runtime/entrypoints/pixel-runtime-cleanup.sh",
        "runtime/entrypoints/pixel-ticket-start.sh",
        "runtime/entrypoints/pixel-ticket-stop.sh",
        "runtime/entrypoints/pixel-ticket-health.sh",
        "runtime/entrypoints/pixel-ticket-lifecycle-lock.sh"
      ).forEach { put(it, "#!/system/bin/sh\n".toByteArray()) }
    }

    override fun open(path: String) = ByteArrayInputStream(files[path] ?: error("Missing fake asset: $path"))

    override fun list(path: String): List<String> = files.keys
      .filter { it.startsWith("$path/") }
      .map { it.removePrefix("$path/") }
      .filter { !it.contains('/') }
  }

  private class RecordingRootExecutor : RootExecutor {
    val commands = mutableListOf<String>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: kotlin.time.Duration): RootResult {
      commands += command
      return RootResult(
        exitCode = 0,
        stdout = if (command.contains("getenforce")) "Permissive\n" else "",
        stderr = "",
        command = command,
        durationMs = 0
      )
    }

    override suspend fun runScript(script: String, timeout: kotlin.time.Duration): RootResult {
      commands += script
      return RootResult(0, "", "", "script", 0)
    }
  }
}
