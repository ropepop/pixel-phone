package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import org.junit.Test

class RuntimeInstallerBootstrapTest {

  @Test
  fun bootstrapAllowsPlatformOnlyManifest() = runBlocking {
    val rootfsBytes = "rootfs".toByteArray()
    val dnsRuntimeBytes = "dns-runtime-assets".toByteArray()
    val dropbearBytes = "dropbear".toByteArray()
    val tailscaleBytes = "tailscale".toByteArray()
    val artifactDir = Files.createTempDirectory("runtime-installer-platform-only")
    val rootfsFile = artifactDir.resolve("adguardhome-rootfs-arm64.tar")
    val dnsRuntimeFile = artifactDir.resolve("dns-runtime-assets.tar")
    val dropbearFile = artifactDir.resolve("dropbear-bundle.tar")
    val tailscaleFile = artifactDir.resolve("tailscale-bundle.tar")
    Files.write(rootfsFile, rootfsBytes)
    Files.write(dnsRuntimeFile, dnsRuntimeBytes)
    Files.write(dropbearFile, dropbearBytes)
    Files.write(tailscaleFile, tailscaleBytes)

    val manifest = ArtifactManifest(
      schema = 1,
      manifestVersion = "platform-only",
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
        )
      )
    )

    val installer = RuntimeInstaller(
      rootExecutor = FakeRootExecutor(),
      artifactSyncer = ArtifactSyncer(createTempDir())
    )

    val result = installer.bootstrap(
      config = StackConfigV1(),
      assets = FakeAssetProvider(),
      manifest = manifest,
      rootfsArtifactId = "adguardhome-rootfs"
    )

    assertTrue(result.success)
    assertEquals(
      listOf("adguardhome-rootfs", "dns-runtime-assets", "dropbear-bundle", "tailscale-bundle"),
      result.installedArtifacts
    )
  }

  @Test
  fun bootstrapFailsWhenRequiredArtifactIsMissing() = runBlocking {
    val installer = RuntimeInstaller(
      rootExecutor = FakeRootExecutor(),
      artifactSyncer = ArtifactSyncer(createTempDir())
    )
    val manifest = ArtifactManifest(
      schema = 1,
      manifestVersion = "test",
      signatureSchema = "ecdsa-sha256",
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

    val error = assertFailsWith<IllegalStateException> {
      installer.bootstrap(
        config = StackConfigV1(),
        assets = FakeAssetProvider(),
        manifest = manifest,
        rootfsArtifactId = "adguardhome-rootfs"
      )
    }

    assertTrue(error.message.orEmpty().contains("Missing required artifact in manifest: dns-runtime-assets"))
  }

  @Test
  fun bootstrapFailsWhenTailscaleArtifactIsMissing() = runBlocking {
    val installer = RuntimeInstaller(
      rootExecutor = FakeRootExecutor(),
      artifactSyncer = ArtifactSyncer(createTempDir())
    )
    val manifest = ArtifactManifest(
      schema = 1,
      manifestVersion = "test",
      signatureSchema = "ecdsa-sha256",
      artifacts = listOf(
        ArtifactEntry(
          id = "adguardhome-rootfs",
          url = "/tmp/adguardhome-rootfs-arm64.tar",
          sha256 = "0",
          fileName = "adguardhome-rootfs-arm64.tar",
          sizeBytes = 1,
          required = true
        ),
        ArtifactEntry(
          id = "dns-runtime-assets",
          url = "/tmp/dns-runtime-assets.tar",
          sha256 = "0",
          fileName = "dns-runtime-assets.tar",
          sizeBytes = 1,
          required = true
        ),
        ArtifactEntry(
          id = "dropbear-bundle",
          url = "/tmp/dropbear-bundle.tar",
          sha256 = "0",
          fileName = "dropbear-bundle.tar",
          sizeBytes = 1,
          required = true
        ),
        ArtifactEntry(
          id = "train-bot-bundle",
          url = "/tmp/train-bot-bundle.tar",
          sha256 = "0",
          fileName = "train-bot-bundle.tar",
          sizeBytes = 1,
          required = true
        ),
        ArtifactEntry(
          id = "satiksme-bot-bundle",
          url = "/tmp/satiksme-bot-bundle.tar",
          sha256 = "0",
          fileName = "satiksme-bot-bundle.tar",
          sizeBytes = 1,
          required = true
        ),
        ArtifactEntry(
          id = "site-notifier-bundle",
          url = "/tmp/site-notifier-bundle.tar",
          sha256 = "0",
          fileName = "site-notifier-bundle.tar",
          sizeBytes = 1,
          required = true
        )
      )
    )

    val error = assertFailsWith<IllegalStateException> {
      installer.bootstrap(
        config = StackConfigV1(),
        assets = FakeAssetProvider(),
        manifest = manifest,
        rootfsArtifactId = "adguardhome-rootfs"
      )
    }

    assertTrue(error.message.orEmpty().contains("Missing required artifact in manifest: tailscale-bundle"))
  }

  @Test
  fun bootstrapFailsWithExplicitErrorWhenSshSourceFilesAreMissing() = runBlocking {
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
          id = "tailscale-bundle",
          url = tailscaleFile.toString(),
          sha256 = sha256(tailscaleBytes),
          fileName = "tailscale-bundle.tar",
          sizeBytes = tailscaleBytes.size.toLong(),
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

    val installer = RuntimeInstaller(
      rootExecutor = FakeRootExecutor(failSshCredentialInstall = true),
      artifactSyncer = ArtifactSyncer(createTempDir())
    )
    val config = StackConfigV1(
      ssh = StackConfigV1().ssh.copy(
        authorizedKeysSourceFile = "/tmp/missing-authorized-keys",
        passwordHashSourceFile = "/tmp/missing-password-hash"
      )
    )

    val error = assertFailsWith<IllegalStateException> {
      installer.bootstrap(
        config = config,
        assets = FakeAssetProvider(),
        manifest = manifest,
        rootfsArtifactId = "adguardhome-rootfs"
      )
    }

    assertTrue(error.message.orEmpty().contains("SSH credential source installation failed: missing SSH authorized_keys source"))
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

  private class FakeRootExecutor(
    private val failSshCredentialInstall: Boolean = false
  ) : RootExecutor {
    override suspend fun isRootAvailable(): Boolean = true

    override suspend fun run(command: String, timeout: kotlin.time.Duration): RootResult {
      return RootResult(
        exitCode = 0,
        stdout = if (command.contains("getenforce")) "Permissive\n" else "",
        stderr = "",
        command = command,
        durationMs = 0
      )
    }

    override suspend fun runScript(script: String, timeout: kotlin.time.Duration): RootResult {
      if (failSshCredentialInstall && script.contains("src_auth=") && script.contains("dst_passwd=")) {
        return RootResult(
          exitCode = 13,
          stdout = "",
          stderr = "missing SSH authorized_keys source: /tmp/missing-authorized-keys",
          command = "installSshCredentialSources",
          durationMs = 0
        )
      }
      val stdout =
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
      return RootResult(
        exitCode = 0,
        stdout = stdout,
        stderr = "",
        command = "script",
        durationMs = 0
      )
    }
  }
}
