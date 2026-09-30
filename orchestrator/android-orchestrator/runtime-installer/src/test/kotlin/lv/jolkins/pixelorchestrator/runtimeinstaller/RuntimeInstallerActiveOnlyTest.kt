package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class RuntimeInstallerActiveOnlyTest {
  @Test
  fun cancellationDuringRootCopyDeletesTheExtractedLocalAsset() = runBlocking {
    val extracted = CompletableDeferred<Path>()
    val root = object : RootExecutor {
      override suspend fun isRootAvailable() = true
      override suspend fun run(command: String, timeout: kotlin.time.Duration): RootResult {
        Regex("(?m)^cp '([^']+)' ").find(command)?.let { match ->
          extracted.complete(Path.of(match.groupValues[1]))
          awaitCancellation()
        }
        return RootResult(0, "", "", command, 0)
      }
      override suspend fun runScript(script: String, timeout: kotlin.time.Duration) = run(script, timeout)
    }
    val installer = RuntimeInstaller(root, ArtifactSyncer(Files.createTempDirectory("cancelled-asset")))
    val operation = async { installer.syncBundledRuntimeAssets(ActiveAssetProvider(), component = "ticket_screen") }
    val temporary = extracted.await()
    assertTrue(Files.exists(temporary))
    operation.cancelAndJoin()
    assertFalse(Files.exists(temporary), "cancelled root effect must not strand extracted asset")
  }

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
      "/bin/pixel-runtime-cleanup'",
      "/bin/pixel-runtime-cleanup.sh",
      "/bin/pixel-ticket-health.sh",
      "/bin/pixel-ticket-lifecycle-lock.sh",
      "/bin/pixel-ticket-root-keyboard"
    ).forEach { expected -> assertTrue(commands.contains(expected), "missing active asset $expected") }
    assertTrue(commands.indexOf("/bin/pixel-runtime-cleanup'") < commands.indexOf("/bin/pixel-runtime-cleanup.sh'"),"native sibling must precede cleanup launcher")
    listOf("adguardhome", "pixel-dns", "train-bot", "satiksme", "notifier", "subscription").forEach { retired ->
      assertFalse(commands.contains(retired), "retired asset unexpectedly installed: $retired")
    }
  }

  @Test
  fun cleanupOnlySyncInstallsNativeSiblingBeforeLauncherWithoutOtherRuntimeChanges() = runBlocking {
    val root=RecordingRootExecutor()
    val installer=RuntimeInstaller(root,ArtifactSyncer(Files.createTempDirectory("cleanup-assets")))
    val result=installer.syncBundledRuntimeAssets(ActiveAssetProvider(),component="runtime_cleanup")
    val commands=root.commands.joinToString("\n")
    assertTrue(result.success)
    assertTrue(commands.indexOf("/bin/pixel-runtime-cleanup'")>=0)
    assertTrue(commands.indexOf("/bin/pixel-runtime-cleanup'")<commands.indexOf("/bin/pixel-runtime-cleanup.sh'"))
    assertFalse(commands.contains("pixel-ssh"));assertFalse(commands.contains("pixel-ticket"))
  }

  @Test
  fun ticketOnlySyncInstallsNativeRootKeyboard() = runBlocking {
    val root = RecordingRootExecutor()
    val installer = RuntimeInstaller(root, ArtifactSyncer(Files.createTempDirectory("ticket-assets")))

    val result = installer.syncBundledRuntimeAssets(ActiveAssetProvider(), component = "ticket_screen")
    val commands = root.commands.joinToString("\n")

    assertTrue(result.success)
    assertTrue(commands.contains("/bin/pixel-ticket-lifecycle-lock.sh"))
    assertTrue(commands.indexOf("/bin/pixel-runtime-cleanup'") >= 0)
    assertTrue(commands.indexOf("/bin/pixel-runtime-cleanup'") < commands.indexOf("/bin/pixel-ticket-start.sh'"))
    assertTrue(commands.contains("/bin/pixel-ticket-root-keyboard"))
    assertTrue(commands.contains("chmod 0755"))
    assertFalse(commands.contains("/templates/ssh/"))
    System.getenv("PIXEL_LIFECYCLE_INSTALLER_COMMAND_RECEIPT")?.let { receipt ->
      val command = root.commands.single { it.contains("mv -f") && it.contains("/bin/pixel-runtime-cleanup'") }
      Files.writeString(Path.of(receipt), command)
    }
    Unit
  }

  @Test
  fun everyManagementLauncherSyncPublishesNativeOwnerBeforeTheLauncher() = runBlocking {
    val cache = Files.createTempDirectory("management-native-assets")
    try {
      for (component in listOf("ssh", "vpn", "management")) {
        val root = RecordingRootExecutor()
        val installer = RuntimeInstaller(root, ArtifactSyncer(cache.resolve(component)))
        val result = installer.syncBundledRuntimeAssets(ActiveAssetProvider(), component = component)
        assertTrue(result.success, result.message)
        val commands = root.commands.joinToString("\n")
        val native = commands.indexOf("/bin/pixel-runtime-cleanup'")
        val launcher = commands.indexOf("/bin/pixel-management-health.sh'")
        assertTrue(native >= 0, "missing native owner for $component")
        assertTrue(launcher > native, "launcher published before its owner for $component")
        assertFalse(commands.contains("/bin/pixel-ticket-start.sh"))
      }
    } finally {
      Files.walk(cache).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
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
    val root = RecordingRootExecutor()
    val cache = Files.createTempDirectory("active-bootstrap-sync")
    val installer = RuntimeInstaller(root, ArtifactSyncer(cache))

    val error = assertFailsWith<IllegalStateException> {
      installer.bootstrap(
        config = StackConfigV1(),
        assets = ActiveAssetProvider(),
        manifest = manifest
      )
    }

    assertTrue(error.message.orEmpty().contains("Missing required artifact in manifest: tailscale-bundle"))
    assertEquals(0, root.rootChecks)
    assertTrue(root.commands.isEmpty())
    Files.list(cache).use { assertEquals(0L, it.count()) }
  }

  @Test
  fun bootstrapRejectsRetiredArtifactsAndRootfsSelectorsBeforeEffects() = runBlocking {
    val owned = Files.createTempDirectory("phone-bootstrap-admission")
    try {
      val active = accessArtifacts(owned)
      val retired = listOf("adguardhome-rootfs", "dns-runtime-assets", "train-bot-bundle",
        "satiksme-bot-bundle", "site-notifier-bundle", "subscription-bot-bundle", "unknown-artifact")
      for ((index, id) in retired.withIndex()) {
        val root = RecordingRootExecutor()
        val cache = owned.resolve("retired-$index")
        val assets = ActiveAssetProvider()
        val installer = RuntimeInstaller(root, ArtifactSyncer(cache))
        val error = assertFailsWith<IllegalStateException> {
          installer.bootstrap(StackConfigV1(), assets,
            ArtifactManifest(manifestVersion = "owned", artifacts = active + active[0].copy(id = id)))
        }
        assertTrue(error.message.orEmpty().contains("only Dropbear/Tailscale"), error.message)
        assertEquals(0, root.rootChecks, id)
        assertTrue(root.commands.isEmpty(), id)
        assertEquals(0, assets.opened, id)
        Files.list(cache).use { assertEquals(0L, it.count(), id) }
      }
      for (selector in listOf("adguardhome-rootfs", "dropbear-bundle", "tailscale-bundle")) {
        val root = RecordingRootExecutor()
        val cache = owned.resolve("selector-$selector")
        val assets = ActiveAssetProvider()
        val installer = RuntimeInstaller(root, ArtifactSyncer(cache))
        val error = assertFailsWith<IllegalStateException> {
          installer.bootstrap(StackConfigV1(), assets,
            ArtifactManifest(manifestVersion = "owned", artifacts = active), selector)
        }
        assertTrue(error.message.orEmpty().contains("rootfs installation is retired"), error.message)
        assertEquals(0, root.rootChecks, selector)
        assertTrue(root.commands.isEmpty(), selector)
        assertEquals(0, assets.opened, selector)
        Files.list(cache).use { assertEquals(0L, it.count(), selector) }
      }
    } finally {
      Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
  }

  @Test
  fun componentAdmissionRejectsRetiredAndMismatchedOwnersBeforeEffects() = runBlocking {
    val owned = Files.createTempDirectory("phone-component-admission")
    try {
      val active = accessArtifacts(owned)
      val cases = listOf(
        Triple("dns", "dns", "adguardhome-rootfs"),
        Triple("ddns", "ddns", "dropbear-bundle"),
        Triple("remote", "remote", "dns-runtime-assets"),
        Triple("train_bot", "train_bot", "train-bot-bundle"),
        Triple("satiksme_bot", "satiksme_bot", "satiksme-bot-bundle"),
        Triple("site_notifier", "site_notifier", "site-notifier-bundle"),
        Triple("subscription_bot", "subscription_bot", "subscription-bot-bundle"),
        Triple("ssh", "vpn", "dropbear-bundle"),
        Triple("ssh", "ssh", "tailscale-bundle"),
        Triple("vpn", "vpn", "dropbear-bundle"),
        Triple("ssh", "ssh", "train-bot-bundle"),
        Triple("vpn", "vpn", "adguardhome-rootfs")
      )
      for ((index, case) in cases.withIndex()) {
        val root = RecordingRootExecutor()
        val cache = owned.resolve("deny-$index")
        val result = RuntimeInstaller(root, ArtifactSyncer(cache)).installComponentRelease(
          StackConfigV1(), case.first,
          ComponentReleaseManifest(componentId = case.second, releaseId = "owned", signatureSchema = "none",
            artifacts = listOf(active[0].copy(id = case.third)))
        )
        assertFalse(result.success, case.toString())
        assertTrue(result.message.contains("Phone component") || result.message.contains("Component release manifest targets"), result.message)
        assertEquals(0, root.rootChecks)
        assertTrue(root.commands.isEmpty(), case.toString())
        Files.list(cache).use { assertEquals(0L, it.count(), case.toString()) }
      }
    } finally {
      Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
  }

  @Test
  fun bootstrapAndComponentAdmissionRetainAccessInstallation() = runBlocking {
    val owned = Files.createTempDirectory("phone-access-installation")
    try {
      val active = accessArtifacts(owned)
      val root = RecordingRootExecutor()
      val result = RuntimeInstaller(root, ArtifactSyncer(owned.resolve("bootstrap"))).bootstrap(
        StackConfigV1(), ActiveAssetProvider(), ArtifactManifest(manifestVersion = "owned", artifacts = active)
      )
      assertTrue(result.success, result.message)
      assertEquals(listOf("dropbear-bundle", "tailscale-bundle"), result.installedArtifacts)
      assertTrue(root.rootChecks > 0)
      assertTrue(root.commands.any { it.contains("tar -xf") && it.contains("/pixel-stack/ssh") })
      assertTrue(root.commands.any { it.contains("tar -xf") && it.contains("/pixel-stack/vpn") })
      for ((component, entry) in listOf("ssh" to active[0], "vpn" to active[1])) {
        val componentRoot = RecordingRootExecutor()
        val componentResult = RuntimeInstaller(componentRoot, ArtifactSyncer(owned.resolve(component))).installComponentRelease(
          StackConfigV1(), component,
          ComponentReleaseManifest(componentId = component, releaseId = "owned", signatureSchema = "none", artifacts = listOf(entry))
        )
        assertTrue(componentResult.success, componentResult.message)
        assertTrue(componentRoot.commands.any { it.contains("tar -xf") })
      }
    } finally {
      Files.walk(owned).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
  }

  private fun accessArtifacts(directory: Path): List<ArtifactEntry> = listOf("dropbear-bundle", "tailscale-bundle").map { id ->
    val bytes = "owned $id".toByteArray()
    val source = directory.resolve("$id.tar")
    Files.write(source, bytes)
    ArtifactEntry(id, source.toString(), sha256(bytes), source.fileName.toString(), bytes.size.toLong())
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

  private class ActiveAssetProvider : AssetProvider {
    var opened = 0
    private val files = buildMap {
      put("ticket-root-keyboard", byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
      put("pixel-runtime-cleanup",byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
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

    override fun open(path: String): ByteArrayInputStream {
      opened++
      return ByteArrayInputStream(files[path] ?: error("Missing fake asset: $path"))
    }

    override fun list(path: String): List<String> = files.keys
      .filter { it.startsWith("$path/") }
      .map { it.removePrefix("$path/") }
      .filter { !it.contains('/') }
  }

  private class RecordingRootExecutor : RootExecutor {
    val commands = mutableListOf<String>()
    var rootChecks = 0

    override suspend fun isRootAvailable(): Boolean {
      rootChecks++
      return true
    }

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
