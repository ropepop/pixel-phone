package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Test
import kotlin.test.assertTrue

class RuntimeInstallerAssetInstallTest {

  @Test
  fun componentScopedTrainBotSyncInstallsOnlyTrainAssets() = runBlocking {
    val rootExecutor = RecordingRootExecutor()
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.syncBundledRuntimeAssets(FakeAssetProvider(), component = "train_bot")

    assertTrue(result.success, "Expected train_bot asset sync to succeed")
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/templates/train/train-web-tunnel-service-loop.sh") },
      "Expected train tunnel template to be installed under /data/local/pixel-stack/templates/train"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/bin/pixel-train-start.sh") },
      "Expected train entrypoint to be installed under /data/local/pixel-stack/bin"
    )
    assertTrue(
      rootExecutor.commands.none { it.contains("/data/local/pixel-stack/bin/pixel-dns-start.sh") },
      "Did not expect dns entrypoints during component-scoped train_bot sync"
    )
    assertTrue(
      rootExecutor.commands.none { it.contains("/data/local/pixel-stack/templates/rooted/adguardhome-start") },
      "Did not expect rooted dns templates during component-scoped train_bot sync"
    )
  }

  @Test
  fun componentScopedSatiksmeBotSyncInstallsOnlySatiksmeAssets() = runBlocking {
    val rootExecutor = RecordingRootExecutor()
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.syncBundledRuntimeAssets(FakeAssetProvider(), component = "satiksme_bot")

    assertTrue(result.success, "Expected satiksme_bot asset sync to succeed")
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/templates/satiksme/satiksme-web-tunnel-service-loop.sh") },
      "Expected satiksme tunnel template to be installed under /data/local/pixel-stack/templates/satiksme"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/bin/pixel-satiksme-start.sh") },
      "Expected satiksme entrypoint to be installed under /data/local/pixel-stack/bin"
    )
    assertTrue(
      rootExecutor.commands.none { it.contains("/data/local/pixel-stack/bin/pixel-dns-start.sh") },
      "Did not expect dns entrypoints during component-scoped satiksme_bot sync"
    )
  }

  @Test
  fun componentScopedSubscriptionBotSyncInstallsOnlySubscriptionAssets() = runBlocking {
    val rootExecutor = RecordingRootExecutor()
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.syncBundledRuntimeAssets(FakeAssetProvider(), component = "subscription_bot")

    assertTrue(result.success, "Expected subscription_bot asset sync to succeed")
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/templates/subscription/subscription-service-loop.sh") },
      "Expected subscription service-loop template to be installed under /data/local/pixel-stack/templates/subscription"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/bin/pixel-subscription-start.sh") },
      "Expected subscription entrypoint to be installed under /data/local/pixel-stack/bin"
    )
    assertTrue(
      rootExecutor.commands.none { it.contains("/data/local/pixel-stack/bin/pixel-dns-start.sh") },
      "Did not expect dns entrypoints during component-scoped subscription_bot sync"
    )
  }

  @Test
  fun managementScopedSyncInstallsCleanupEntrypoint() = runBlocking {
    val rootExecutor = RecordingRootExecutor()
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.syncBundledRuntimeAssets(FakeAssetProvider(), component = "management")

    assertTrue(result.success, "Expected management asset sync to succeed")
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/bin/pixel-runtime-cleanup.sh") },
      "Expected cleanup entrypoint to be installed under /data/local/pixel-stack/bin"
    )
    assertTrue(
      rootExecutor.commands.none { it.contains("/data/local/pixel-stack/templates/train/") },
      "Did not expect train templates during management-scoped cleanup sync"
    )
  }

  @Test
  fun bootstrapInstallsSshTemplateAssetsUnderDataLocal() = runBlocking {
    val rootfsBytes = "rootfs".toByteArray()
    val dnsRuntimeBytes = "dns-runtime-assets".toByteArray()
    val dropbearBytes = "dropbear".toByteArray()
    val tailscaleBytes = "tailscale".toByteArray()
    val trainBotBytes = "train-bot".toByteArray()
    val satiksmeBotBytes = "satiksme-bot".toByteArray()
    val siteNotifierBytes = "site-notifier".toByteArray()
    val artifactDir = Files.createTempDirectory("runtime-installer-artifacts")
    val rootfsFile = artifactDir.resolve("adguardhome-rootfs-arm64.tar")
    val dnsRuntimeFile = artifactDir.resolve("dns-runtime-assets.tar")
    val dropbearFile = artifactDir.resolve("dropbear-bundle.tar")
    val tailscaleFile = artifactDir.resolve("tailscale-bundle.tar")
    val trainBotFile = artifactDir.resolve("train-bot-bundle.tar")
    val satiksmeBotFile = artifactDir.resolve("satiksme-bot-bundle.tar")
    val siteNotifierFile = artifactDir.resolve("site-notifier-bundle.tar")
    Files.write(rootfsFile, rootfsBytes)
    Files.write(dnsRuntimeFile, dnsRuntimeBytes)
    Files.write(dropbearFile, dropbearBytes)
    Files.write(tailscaleFile, tailscaleBytes)
    Files.write(trainBotFile, trainBotBytes)
    Files.write(satiksmeBotFile, satiksmeBotBytes)
    Files.write(siteNotifierFile, siteNotifierBytes)

    val manifest = ArtifactManifest(
      schema = 1,
      manifestVersion = "test",
      signatureSchema = "none",
      artifacts = listOf(
        ArtifactEntry(
          id = "adguardhome-rootfs",
          url = rootfsFile.toString(),
          sha256 = sha256(rootfsBytes),
          fileName = "adguardhome-rootfs-arm64.tar",
          sizeBytes = rootfsBytes.size.toLong(),
          required = true
        ),
        ArtifactEntry(
          id = "dns-runtime-assets",
          url = dnsRuntimeFile.toString(),
          sha256 = sha256(dnsRuntimeBytes),
          fileName = "dns-runtime-assets.tar",
          sizeBytes = dnsRuntimeBytes.size.toLong(),
          required = true
        ),
        ArtifactEntry(
          id = "dropbear-bundle",
          url = dropbearFile.toString(),
          sha256 = sha256(dropbearBytes),
          fileName = "dropbear-bundle.tar",
          sizeBytes = dropbearBytes.size.toLong(),
          required = true
        ),
        ArtifactEntry(
          id = "tailscale-bundle",
          url = tailscaleFile.toString(),
          sha256 = sha256(tailscaleBytes),
          fileName = "tailscale-bundle.tar",
          sizeBytes = tailscaleBytes.size.toLong(),
          required = true
        ),
        ArtifactEntry(
          id = "train-bot-bundle",
          url = trainBotFile.toString(),
          sha256 = sha256(trainBotBytes),
          fileName = "train-bot-bundle.tar",
          sizeBytes = trainBotBytes.size.toLong(),
          required = true
        ),
        ArtifactEntry(
          id = "satiksme-bot-bundle",
          url = satiksmeBotFile.toString(),
          sha256 = sha256(satiksmeBotBytes),
          fileName = "satiksme-bot-bundle.tar",
          sizeBytes = satiksmeBotBytes.size.toLong(),
          required = true
        ),
        ArtifactEntry(
          id = "site-notifier-bundle",
          url = siteNotifierFile.toString(),
          sha256 = sha256(siteNotifierBytes),
          fileName = "site-notifier-bundle.tar",
          sizeBytes = siteNotifierBytes.size.toLong(),
          required = true
        )
      )
    )

    val rootExecutor = RecordingRootExecutor()
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    installer.bootstrap(
      config = StackConfigV1(),
      assets = FakeAssetProvider(),
      manifest = manifest,
      rootfsArtifactId = "adguardhome-rootfs"
    )

    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/templates/ssh/pixel-ssh-launch.sh") },
      "Expected ssh launch template to be installed under /data/local/pixel-stack/templates/ssh"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("missing templates/rooted in dns runtime asset bundle").not() && it.contains("pixel-dns-start.sh") },
      "Expected dns runtime asset bundle install to wire dns entrypoints during bootstrap"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/templates/ssh/pixel-ssh-service-loop.sh") },
      "Expected ssh service-loop template to be installed under /data/local/pixel-stack/templates/ssh"
    )
  }

  @Test
  fun dnsComponentReleaseRequiresRootfsAndDnsRuntimeAssets() = runBlocking {
    val rootfsBytes = "rootfs".toByteArray()
    val dnsRuntimeBytes = "dns-runtime-assets".toByteArray()
    val artifactDir = Files.createTempDirectory("runtime-installer-dns-release")
    val rootfsFile = artifactDir.resolve("adguardhome-rootfs-arm64.tar")
    val dnsRuntimeFile = artifactDir.resolve("dns-runtime-assets.tar")
    Files.write(rootfsFile, rootfsBytes)
    Files.write(dnsRuntimeFile, dnsRuntimeBytes)

    val manifest = ComponentReleaseManifest(
      schema = 1,
      componentId = "dns",
      releaseId = "dns-release-123",
      signatureSchema = "none",
      artifacts = listOf(
        ArtifactEntry(
          id = "dns-runtime-assets",
          url = dnsRuntimeFile.toString(),
          sha256 = sha256(dnsRuntimeBytes),
          fileName = "dns-runtime-assets.tar",
          sizeBytes = dnsRuntimeBytes.size.toLong(),
          required = true
        ),
        ArtifactEntry(
          id = "adguardhome-rootfs",
          url = rootfsFile.toString(),
          sha256 = sha256(rootfsBytes),
          fileName = "adguardhome-rootfs-arm64.tar",
          sizeBytes = rootfsBytes.size.toLong(),
          required = true
        )
      )
    )

    val rootExecutor = RecordingRootExecutor()
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.installComponentRelease(
      config = StackConfigV1(),
      component = "dns",
      manifest = manifest
    )

    assertTrue(result.success, "Expected dns component release install to succeed")
    val rootfsInstallIndex =
      rootExecutor.commands.indexOfFirst { command ->
        command.contains("need_extract=0") &&
          command.contains("tar -xf") &&
          command.contains("chroot \"${'$'}rootfs\" /usr/bin/env -i PATH=")
      }
    val dnsRuntimeAssetInstallIndex =
      rootExecutor.commands.indexOfLast { command ->
        command.contains("pixel-dns-start.sh") && command.contains("templates/rooted")
      }
    assertTrue(rootfsInstallIndex >= 0, "Expected dns release install to run the rootfs extraction script")
    assertTrue(dnsRuntimeAssetInstallIndex >= 0, "Expected dns release install to run the dns runtime asset install script")
    assertTrue(
      rootfsInstallIndex < dnsRuntimeAssetInstallIndex,
      "Expected dns release install to apply rootfs before dns runtime assets"
    )
  }

  @Test
  fun dnsComponentReleaseRejectsUnexpectedArtifactSet() = runBlocking {
    val manifest = ComponentReleaseManifest(
      schema = 1,
      componentId = "dns",
      releaseId = "dns-release-123",
      signatureSchema = "none",
      artifacts = listOf(
        ArtifactEntry(
          id = "adguardhome-rootfs",
          url = "/tmp/adguardhome-rootfs-arm64.tar",
          sha256 = "0",
          fileName = "adguardhome-rootfs-arm64.tar",
          sizeBytes = 1,
          required = true
        )
      )
    )

    val installer = RuntimeInstaller(
      rootExecutor = RecordingRootExecutor(),
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.installComponentRelease(
      config = StackConfigV1(),
      component = "dns",
      manifest = manifest
    )

    assertTrue(!result.success, "Expected dns component release install to fail when dns runtime assets are missing")
  }

  @Test
  fun componentReleaseInstallPublishesImmutableSiteNotifierReleaseAndUpdatesCurrent() = runBlocking {
    val bundleBytes = "site-notifier".toByteArray()
    val artifactDir = Files.createTempDirectory("runtime-installer-component-release")
    val bundleFile = artifactDir.resolve("site-notifier-bundle.tar")
    Files.write(bundleFile, bundleBytes)

    val manifest = ComponentReleaseManifest(
      schema = 1,
      componentId = "site_notifier",
      releaseId = "release-123",
      signatureSchema = "none",
      artifacts = listOf(
        ArtifactEntry(
          id = "site-notifier-bundle",
          url = bundleFile.toString(),
          sha256 = sha256(bundleBytes),
          fileName = "site-notifier-bundle.tar",
          sizeBytes = bundleBytes.size.toLong(),
          required = true
        )
      )
    )

    val rootExecutor = RecordingRootExecutor(
      scriptStdoutProvider = { script ->
        if (script.contains("__PIXEL_RELEASE_META__ component=site_notifier release_id=release-123")) {
          """
          __PIXEL_RELEASE_META__ component=site_notifier release_id=release-123 current_path=/data/local/pixel-stack/apps/site-notifications/current
          __PIXEL_RELEASE_META__ previous_target=/data/local/pixel-stack/apps/site-notifications/releases/release-122
          __PIXEL_RELEASE_META__ installed_target=/data/local/pixel-stack/apps/site-notifications/releases/release-123
          """.trimIndent()
        } else {
          ""
        }
      }
    )
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.installComponentRelease(
      config = StackConfigV1(),
      component = "site_notifier",
      manifest = manifest
    )

    assertTrue(result.success, "Expected component release install to succeed")
    assertTrue(result.rollbackMetadata != null, "Expected rollback metadata to be captured")
    assertTrue(
      result.rollbackMetadata?.previousTargetPath == "/data/local/pixel-stack/apps/site-notifications/releases/release-122",
      "Expected previous release target to be captured for rollback"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("/data/local/pixel-stack/apps/site-notifications/releases/release-123") },
      "Expected immutable notifier release directory under releases/release-123"
    )
    assertTrue(
      rootExecutor.commands.any {
        it.contains("ln -sfn \"${'$'}release_root\" \"${'$'}current_link\"") ||
          it.contains("/data/local/pixel-stack/apps/site-notifications/current")
      },
      "Expected current symlink to be updated to the new immutable release"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("bundled_python_binary_tmp") && it.contains("mv -f \"${'$'}bundled_python_binary_tmp\" \"${'$'}bundled_python_binary\"") },
      "Expected notifier python binary to be swapped via temp file + mv"
    )
  }

  @Test
  fun siteNotifierRollbackRepointsCurrentReleaseAndRebuildsBundledPythonWrapper() = runBlocking {
    val rootExecutor = RecordingRootExecutor()
    val installer = RuntimeInstaller(
      rootExecutor = rootExecutor,
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.rollbackComponentRelease(
      config = StackConfigV1(),
      component = "site_notifier",
      rollbackMetadata = ReleaseRollbackMetadata(
        component = "site_notifier",
        releaseId = "release-123",
        currentSymlinkPath = "/data/local/pixel-stack/apps/site-notifications/current",
        previousTargetPath = "/data/local/pixel-stack/apps/site-notifications/releases/release-122",
        installedTargetPath = "/data/local/pixel-stack/apps/site-notifications/releases/release-123"
      )
    )

    assertTrue(result.success, "Expected site notifier rollback to succeed")
    assertTrue(
      rootExecutor.commands.any { it.contains("rollback_target='/data/local/pixel-stack/apps/site-notifications/releases/release-122'") },
      "Expected rollback target to be wired into the rollback script"
    )
    assertTrue(
      rootExecutor.commands.any { it.contains("bundled_python_wrapper_tmp") && it.contains("mv -f \"${'$'}bundled_python_wrapper_tmp\" \"${'$'}bundled_python_wrapper\"") },
      "Expected rollback to rebuild the notifier wrapper via temp file + mv"
    )
  }

  private fun createTempDir() = java.nio.file.Files.createTempDirectory("runtime-installer-test")

  private fun sha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
  }

  private class FakeAssetProvider : AssetProvider {
    private val files = mapOf(
      "runtime/templates/rooted/adguardhome-start" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/ssh/pixel-ssh-launch.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/ssh/pixel-ssh-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/vpn/pixel-vpn-launch.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/vpn/pixel-vpn-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/train/train-launch.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/train/train-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/train/train-web-tunnel-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/satiksme/satiksme-launch.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/satiksme/satiksme-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/satiksme/satiksme-web-tunnel-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/notifier/notifier-launch.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/notifier/notifier-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/subscription/subscription-launch.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/templates/subscription/subscription-service-loop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-dns-start.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-dns-stop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-ssh-start.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-ssh-stop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-management-health.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-vpn-start.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-vpn-stop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-vpn-health.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-ddns-sync.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-runtime-cleanup.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-train-start.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-train-stop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-satiksme-start.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-satiksme-stop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-satiksme-health.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-notifier-start.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-notifier-stop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-subscription-start.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-subscription-stop.sh" to "#!/system/bin/sh\n".toByteArray(),
      "runtime/entrypoints/pixel-subscription-health.sh" to "#!/system/bin/sh\n".toByteArray()
    )

    override fun open(path: String): java.io.InputStream {
      val bytes = files[path] ?: error("Missing fake asset: $path")
      return ByteArrayInputStream(bytes)
    }

    override fun list(path: String): List<String> {
      return files.keys
        .filter { it.startsWith("$path/") }
        .map { it.removePrefix("$path/") }
        .filter { !it.contains("/") }
    }
  }

  private class RecordingRootExecutor(
    private val runStdoutProvider: (String) -> String = { command ->
      if (command.contains("getenforce")) "Permissive\n" else ""
    },
    private val scriptStdoutProvider: (String) -> String = { "" }
  ) : RootExecutor {
    val commands = mutableListOf<String>()

    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: kotlin.time.Duration): RootResult {
      commands += command
      return RootResult(
        exitCode = 0,
        stdout = runStdoutProvider(command),
        stderr = "",
        command = command,
        durationMs = 0
      )
    }

    override suspend fun runScript(script: String, timeout: kotlin.time.Duration): RootResult {
      commands += script
      val defaultStdout =
        when {
          script.contains("__PIXEL_RELEASE_META__ component=train_bot") ->
            """
            __PIXEL_RELEASE_META__ component=train_bot release_id=test current_path=/data/local/pixel-stack/apps/train-bot/current
            __PIXEL_RELEASE_META__ previous_target=/data/local/pixel-stack/apps/train-bot/releases/previous
            __PIXEL_RELEASE_META__ installed_target=/data/local/pixel-stack/apps/train-bot/releases/test
            """.trimIndent()
          script.contains("__PIXEL_RELEASE_META__ component=satiksme_bot") ->
            """
            __PIXEL_RELEASE_META__ component=satiksme_bot release_id=test current_path=/data/local/pixel-stack/apps/satiksme-bot/current
            __PIXEL_RELEASE_META__ previous_target=/data/local/pixel-stack/apps/satiksme-bot/releases/previous
            __PIXEL_RELEASE_META__ installed_target=/data/local/pixel-stack/apps/satiksme-bot/releases/test
            """.trimIndent()
          script.contains("__PIXEL_RELEASE_META__ component=site_notifier") ->
            """
            __PIXEL_RELEASE_META__ component=site_notifier release_id=test current_path=/data/local/pixel-stack/apps/site-notifications/current
            __PIXEL_RELEASE_META__ previous_target=/data/local/pixel-stack/apps/site-notifications/releases/previous
            __PIXEL_RELEASE_META__ installed_target=/data/local/pixel-stack/apps/site-notifications/releases/test
            """.trimIndent()
          script.contains("__PIXEL_RELEASE_META__ component=subscription_bot") ->
            """
            __PIXEL_RELEASE_META__ component=subscription_bot release_id=test current_path=/data/local/pixel-stack/apps/subscription-bot/current
            __PIXEL_RELEASE_META__ previous_target=/data/local/pixel-stack/apps/subscription-bot/releases/previous
            __PIXEL_RELEASE_META__ installed_target=/data/local/pixel-stack/apps/subscription-bot/releases/test
            """.trimIndent()
          else -> ""
        }
      val providedStdout = scriptStdoutProvider(script)
      return RootResult(
        exitCode = 0,
        stdout = if (providedStdout.isNotBlank()) providedStdout else defaultStdout,
        stderr = "",
        command = "script",
        durationMs = 0
      )
    }
  }
}
