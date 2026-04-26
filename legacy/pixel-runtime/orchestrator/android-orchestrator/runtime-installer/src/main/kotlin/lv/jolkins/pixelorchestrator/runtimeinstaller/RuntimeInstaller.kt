package lv.jolkins.pixelorchestrator.runtimeinstaller

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackPaths
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.rootexec.ShellEscaper

class RuntimeInstaller(
  private val rootExecutor: RootExecutor,
  private val artifactSyncer: ArtifactSyncer
) : RuntimeInstallerControl {

  override suspend fun bootstrap(
    config: StackConfigV1,
    assets: AssetProvider,
    manifest: ArtifactManifest,
    rootfsArtifactId: String
  ): BootstrapResult {
    trace("bootstrap:start")
    val preflight = preflight()
    trace("bootstrap:preflight rootGranted=${preflight.rootGranted}")
    if (!preflight.rootGranted) {
      return BootstrapResult(
        success = false,
        rootGranted = false,
        installedAtEpochSeconds = Instant.now().epochSecond,
        message = "Root access not available",
        installedArtifacts = emptyList()
      )
    }

    trace("bootstrap:syncBundledRuntimeAssets:start")
    val syncResult = syncBundledRuntimeAssets(assets)
    trace("bootstrap:syncBundledRuntimeAssets:done success=${syncResult.success}")
    if (!syncResult.success) {
      return BootstrapResult(
        success = false,
        rootGranted = true,
        installedAtEpochSeconds = Instant.now().epochSecond,
        message = "Bootstrap failed: ${syncResult.message}",
        installedArtifacts = emptyList()
      )
    }
    trace("bootstrap:ensureRequiredArtifactsPresent")
    ensureRequiredArtifactsPresent(manifest, rootfsArtifactId)

    val installed = mutableListOf<String>()
    manifest.artifacts.forEach { entry ->
      try {
        trace("bootstrap:artifact sync:start id=${entry.id}")
        val file = artifactSyncer.sync(entry)
        trace("bootstrap:artifact sync:done id=${entry.id} path=${file.fileName}")
        installed += entry.id
        installArtifactEntry(
          entry = entry,
          file = file,
          config = config,
          releaseId = manifest.manifestVersion,
          rootfsArtifactId = rootfsArtifactId
        )
      } catch (error: Exception) {
        trace("bootstrap:artifact failure id=${entry.id} error=${error::class.java.name}:${error.message ?: "(no message)"}")
        if (entry.id == rootfsArtifactId) {
          trace("bootstrap:artifact rootfs fallback: attempting legacy seed from $LEGACY_PIHOLE_ROOTFS")
          if (seedRootfsFromLegacyIfAvailable(config.runtime.rootfsPath)) {
            trace("bootstrap:artifact rootfs fallback: legacy seed successful")
            installed += entry.id
            return@forEach
          }
          trace("bootstrap:artifact rootfs fallback: legacy seed unavailable")
        }
        if (entry.required) {
          throw error
        }
      }
    }

    trace("bootstrap:installSshCredentialSources:start")
    installSshCredentialSources(config)
    trace("bootstrap:installSshCredentialSources:done")
    trace("bootstrap:installAppEnvSources:start")
    installAppEnvSources(config)
    trace("bootstrap:installAppEnvSources:done")
    trace("bootstrap:ensureRootfsUsable:start")
    ensureRootfsUsable(config.runtime.rootfsPath)
    trace("bootstrap:ensureRootfsUsable:done")

    return BootstrapResult(
      success = true,
      rootGranted = true,
      installedAtEpochSeconds = Instant.now().epochSecond,
      message = "Bootstrap complete",
      installedArtifacts = installed
    )
  }

  override suspend fun installComponentRelease(
    config: StackConfigV1,
    component: String,
    manifest: ComponentReleaseManifest
  ): SyncResult {
    return runCatching {
      ensureComponentReleasePresent(component, manifest)
      var rollbackMetadata: ReleaseRollbackMetadata? = null
      orderedComponentReleaseArtifacts(component, manifest.artifacts).forEach { entry ->
        trace("component-release:artifact sync:start component=$component id=${entry.id}")
        val file = artifactSyncer.sync(entry)
        trace("component-release:artifact sync:done component=$component id=${entry.id} path=${file.fileName}")
        val artifactMetadata =
          installComponentReleaseArtifact(
          component = component,
          entry = entry,
          file = file,
          config = config,
          releaseId = manifest.releaseId
        )
        if (artifactMetadata != null) {
          rollbackMetadata = artifactMetadata
        }
      }
      SyncResult(
        success = true,
        message = "Component release installed for $component",
        rollbackMetadata = rollbackMetadata
      )
    }.getOrElse { error ->
      trace("component-release:failed component=$component error=${error::class.java.name}:${error.message ?: "(no message)"}")
      SyncResult(
        success = false,
        message = "Component release install failed for $component (${error::class.java.name}): ${error.message ?: "(no message)"}"
      )
    }
  }

  override suspend fun rollbackComponentRelease(
    config: StackConfigV1,
    component: String,
    rollbackMetadata: ReleaseRollbackMetadata
  ): SyncResult {
    return runCatching {
      when (component) {
        "train_bot" -> rollbackTrainBotRelease(config, rollbackMetadata)
        "satiksme_bot" -> rollbackSatiksmeBotRelease(config, rollbackMetadata)
        "site_notifier" -> rollbackSiteNotifierRelease(config, rollbackMetadata)
        "subscription_bot" -> rollbackSubscriptionBotRelease(config, rollbackMetadata)
        else -> error("Rollback is unsupported for component: $component")
      }
      SyncResult(
        success = true,
        message = "Component rollback complete for $component",
        rollbackMetadata = rollbackMetadata
      )
    }.getOrElse { error ->
      SyncResult(
        success = false,
        message = "Component rollback failed for $component (${error::class.java.name}): ${error.message ?: "(no message)"}",
        rollbackMetadata = rollbackMetadata
      )
    }
  }

  override suspend fun pruneComponentReleases(
    config: StackConfigV1,
    component: String,
    keepReleases: Int
  ): SyncResult {
    return runCatching {
      val runtimeRoot = when (component) {
        "train_bot" -> config.trainBot.runtimeRoot
        "satiksme_bot" -> config.satiksmeBot.runtimeRoot
        "site_notifier" -> config.siteNotifier.runtimeRoot
        "subscription_bot" -> config.subscriptionBot.runtimeRoot
        else -> return@runCatching SyncResult(
          success = true,
          message = "Release pruning skipped for $component"
        )
      }
      pruneComponentReleaseDirs(runtimeRoot, keepReleases)
      SyncResult(
        success = true,
        message = "Release pruning complete for $component"
      )
    }.getOrElse { error ->
      SyncResult(
        success = false,
        message = "Release pruning failed for $component (${error::class.java.name}): ${error.message ?: "(no message)"}"
      )
    }
  }

  override suspend fun syncBundledRuntimeAssets(assets: AssetProvider, component: String?): SyncResult {
    return runCatching {
      trace("syncBundledRuntimeAssets:ensureLayout")
      ensureLayout()
      trace("syncBundledRuntimeAssets:installBundledScripts component=${component ?: "all"}")
      installBundledScripts(assets, component)
      SyncResult(
        success = true,
        message = "Bundled runtime assets synced"
      )
    }.getOrElse { error ->
      trace("syncBundledRuntimeAssets:failed error=${error::class.java.name}:${error.message ?: "(no message)"}")
      SyncResult(
        success = false,
        message = "Bundled runtime asset sync failed (${error::class.java.name}): ${error.message ?: "(no message)"}"
      )
    }
  }

  private suspend fun installArtifactEntry(
    entry: ArtifactEntry,
    file: Path,
    config: StackConfigV1,
    releaseId: String,
    rootfsArtifactId: String
  ) {
    when (entry.id) {
      rootfsArtifactId -> {
        trace("bootstrap:artifact install:rootfs:start")
        installRootfsFromTarball(file, config.runtime.rootfsPath)
        trace("bootstrap:artifact install:rootfs:done")
      }
      DNS_RUNTIME_ASSET_ID -> {
        trace("bootstrap:artifact install:dns-runtime-assets:start")
        installDnsRuntimeAssetsBundle(file)
        trace("bootstrap:artifact install:dns-runtime-assets:done")
      }
      DROPBEAR_ARTIFACT_ID -> {
        trace("bootstrap:artifact install:dropbear:start")
        installDropbearBundle(file)
        trace("bootstrap:artifact install:dropbear:done")
      }
      TAILSCALE_ARTIFACT_ID -> {
        trace("bootstrap:artifact install:tailscale:start")
        installTailscaleBundle(file, config)
        trace("bootstrap:artifact install:tailscale:done")
      }
      TRAIN_BOT_ARTIFACT_ID -> {
        trace("bootstrap:artifact install:train:start")
        installTrainBotBundle(file, config, releaseId)
        trace("bootstrap:artifact install:train:done")
      }
      SATIKSME_BOT_ARTIFACT_ID -> {
        trace("bootstrap:artifact install:satiksme:start")
        installSatiksmeBotBundle(file, config, releaseId)
        trace("bootstrap:artifact install:satiksme:done")
      }
      SITE_NOTIFIER_ARTIFACT_ID -> {
        trace("bootstrap:artifact install:notifier:start")
        installSiteNotifierBundle(file, config, releaseId)
        trace("bootstrap:artifact install:notifier:done")
      }
      SUBSCRIPTION_BOT_ARTIFACT_ID -> {
        trace("bootstrap:artifact install:subscription:start")
        installSubscriptionBotBundle(file, config, releaseId)
        trace("bootstrap:artifact install:subscription:done")
      }
    }
  }

  private suspend fun installComponentReleaseArtifact(
    component: String,
    entry: ArtifactEntry,
    file: Path,
    config: StackConfigV1,
    releaseId: String
  ): ReleaseRollbackMetadata? {
    when (component) {
      "dns" -> {
        require(entry.id == ROOTFS_ARTIFACT_ID || entry.id == DNS_RUNTIME_ASSET_ID) {
          "Unsupported artifact '${entry.id}' for dns release; expected $ROOTFS_ARTIFACT_ID or $DNS_RUNTIME_ASSET_ID"
        }
        if (entry.id == ROOTFS_ARTIFACT_ID) {
          installRootfsFromTarball(file, config.runtime.rootfsPath)
        } else {
          installDnsRuntimeAssetsBundle(file)
        }
        return null
      }
      "ssh" -> {
        require(entry.id == DROPBEAR_ARTIFACT_ID) {
          "Unsupported artifact '${entry.id}' for ssh release; expected $DROPBEAR_ARTIFACT_ID"
        }
        installDropbearBundle(file)
        return null
      }
      "vpn" -> {
        require(entry.id == TAILSCALE_ARTIFACT_ID) {
          "Unsupported artifact '${entry.id}' for vpn release; expected $TAILSCALE_ARTIFACT_ID"
        }
        installTailscaleBundle(file, config)
        return null
      }
      "train_bot" -> {
        require(entry.id == TRAIN_BOT_ARTIFACT_ID) {
          "Unsupported artifact '${entry.id}' for train_bot release; expected $TRAIN_BOT_ARTIFACT_ID"
        }
        return installTrainBotBundle(file, config, releaseId)
      }
      "satiksme_bot" -> {
        require(entry.id == SATIKSME_BOT_ARTIFACT_ID) {
          "Unsupported artifact '${entry.id}' for satiksme_bot release; expected $SATIKSME_BOT_ARTIFACT_ID"
        }
        return installSatiksmeBotBundle(file, config, releaseId)
      }
      "site_notifier" -> {
        require(entry.id == SITE_NOTIFIER_ARTIFACT_ID) {
          "Unsupported artifact '${entry.id}' for site_notifier release; expected $SITE_NOTIFIER_ARTIFACT_ID"
        }
        return installSiteNotifierBundle(file, config, releaseId)
      }
      "subscription_bot" -> {
        require(entry.id == SUBSCRIPTION_BOT_ARTIFACT_ID) {
          "Unsupported artifact '${entry.id}' for subscription_bot release; expected $SUBSCRIPTION_BOT_ARTIFACT_ID"
        }
        return installSubscriptionBotBundle(file, config, releaseId)
      }
      else -> error("Component $component does not support artifact release installs")
    }
  }

  private fun trace(message: String) {
    println("RuntimeInstaller: $message")
  }

  suspend fun preflight(): PreflightResult {
    val rootGranted = rootExecutor.isRootAvailable()
    if (!rootGranted) {
      return PreflightResult(
        rootGranted = false,
        selinuxMode = "unknown",
        writablePaths = emptyMap(),
        details = "su check failed"
      )
    }

    val selinux = rootExecutor.run("getenforce 2>/dev/null || echo unknown")
    val probe = listOf(
      StackPaths.BASE,
      StackPaths.CHROOT_ADGUARDHOME,
      StackPaths.CONF,
      StackPaths.RUN,
      StackPaths.LOG,
      StackPaths.SSH,
      StackPaths.VPN,
      StackPaths.APPS,
      StackPaths.TRAIN_BOT,
      StackPaths.SATIKSME_BOT,
      StackPaths.SITE_NOTIFIER,
      StackPaths.SUBSCRIPTION_BOT,
      StackPaths.BACKUPS
    )

    val writable = mutableMapOf<String, Boolean>()
    for (path in probe) {
      val cmd = "mkdir -p ${ShellEscaper.singleQuote(path)} && test -w ${ShellEscaper.singleQuote(path)}"
      writable[path] = rootExecutor.run(cmd).ok
    }

    return PreflightResult(
      rootGranted = true,
      selinuxMode = selinux.stdout.trim().ifBlank { "unknown" },
      writablePaths = writable,
      details = "ok"
    )
  }

  suspend fun ensureLayout() {
    val dirs = listOf(
      StackPaths.BASE,
      StackPaths.CHROOT_ADGUARDHOME,
      StackPaths.BIN,
      StackPaths.CONF,
      StackPaths.RUN,
      StackPaths.LOG,
      StackPaths.SSH,
      StackPaths.VPN,
      StackPaths.APPS,
      StackPaths.TRAIN_BOT,
      StackPaths.SATIKSME_BOT,
      StackPaths.SITE_NOTIFIER,
      StackPaths.SUBSCRIPTION_BOT,
      StackPaths.BACKUPS
    )
    val mkdir = dirs.joinToString(" ") { ShellEscaper.singleQuote(it) }
    rootExecutor.run("mkdir -p $mkdir")
  }

  private suspend fun installBundledScripts(assets: AssetProvider, component: String? = null) {
    when (component) {
      null -> {
        installTemplateGroups(
          assets,
          listOf("rooted", "ssh", "vpn", "train", "satiksme", "notifier", "subscription")
        )
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints())
      }
      "dns", "remote" -> {
        installTemplateGroups(assets, listOf("rooted"))
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-dns-start.sh", "pixel-dns-stop.sh"))
      }
      "ssh" -> {
        installTemplateGroups(assets, listOf("ssh"))
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-ssh-start.sh", "pixel-ssh-stop.sh", "pixel-management-health.sh"))
      }
      "vpn" -> {
        installTemplateGroups(assets, listOf("vpn"))
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-vpn-start.sh", "pixel-vpn-stop.sh", "pixel-vpn-health.sh", "pixel-management-health.sh"))
      }
      "ddns" -> {
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-ddns-sync.sh"))
      }
      "management" -> {
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-runtime-cleanup.sh"))
      }
      "train_bot" -> {
        installTemplateGroups(assets, listOf("train"))
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-train-start.sh", "pixel-train-stop.sh"))
      }
      "satiksme_bot" -> {
        installTemplateGroups(assets, listOf("satiksme"))
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-satiksme-start.sh", "pixel-satiksme-stop.sh", "pixel-satiksme-health.sh"))
      }
      "site_notifier" -> {
        installTemplateGroups(assets, listOf("notifier"))
        installOrchestratorEntrypoints(assets, orchestratorEntrypoints("pixel-notifier-start.sh", "pixel-notifier-stop.sh"))
      }
      "subscription_bot" -> {
        installTemplateGroups(assets, listOf("subscription"))
        installOrchestratorEntrypoints(
          assets,
          orchestratorEntrypoints("pixel-subscription-start.sh", "pixel-subscription-stop.sh", "pixel-subscription-health.sh")
        )
      }
      else -> error("Unsupported component runtime asset sync target: $component")
    }
  }

  private suspend fun installTemplateGroups(assets: AssetProvider, groups: List<String>) {
    val roots = mapOf(
      "rooted" to "${StackPaths.BASE}/templates/rooted",
      "ssh" to "${StackPaths.BASE}/templates/ssh",
      "vpn" to "${StackPaths.BASE}/templates/vpn",
      "train" to "${StackPaths.BASE}/templates/train",
      "satiksme" to "${StackPaths.BASE}/templates/satiksme",
      "notifier" to "${StackPaths.BASE}/templates/notifier",
      "subscription" to "${StackPaths.BASE}/templates/subscription"
    )

    for (group in groups) {
      val targetRoot = roots[group] ?: error("Unsupported template group: $group")
      installAssetTree(
        assets = assets,
        sourceRoot = "runtime/templates/$group",
        targetRoot = targetRoot,
        mode = "0755"
      )
    }
  }

  private fun orchestratorEntrypoints(vararg names: String): Map<String, String> {
    val scripts = mapOf(
      "runtime/entrypoints/pixel-dns-start.sh" to "${StackPaths.BIN}/pixel-dns-start.sh",
      "runtime/entrypoints/pixel-dns-stop.sh" to "${StackPaths.BIN}/pixel-dns-stop.sh",
      "runtime/entrypoints/pixel-ssh-start.sh" to "${StackPaths.BIN}/pixel-ssh-start.sh",
      "runtime/entrypoints/pixel-ssh-stop.sh" to "${StackPaths.BIN}/pixel-ssh-stop.sh",
      "runtime/entrypoints/pixel-vpn-start.sh" to "${StackPaths.BIN}/pixel-vpn-start.sh",
      "runtime/entrypoints/pixel-vpn-stop.sh" to "${StackPaths.BIN}/pixel-vpn-stop.sh",
      "runtime/entrypoints/pixel-vpn-health.sh" to "${StackPaths.BIN}/pixel-vpn-health.sh",
      "runtime/entrypoints/pixel-management-health.sh" to "${StackPaths.BIN}/pixel-management-health.sh",
      "runtime/entrypoints/pixel-ddns-sync.sh" to "${StackPaths.BIN}/pixel-ddns-sync.sh",
      "runtime/entrypoints/pixel-runtime-cleanup.sh" to "${StackPaths.BIN}/pixel-runtime-cleanup.sh",
      "runtime/entrypoints/pixel-train-start.sh" to "${StackPaths.BIN}/pixel-train-start.sh",
      "runtime/entrypoints/pixel-train-stop.sh" to "${StackPaths.BIN}/pixel-train-stop.sh",
      "runtime/entrypoints/pixel-satiksme-start.sh" to "${StackPaths.BIN}/pixel-satiksme-start.sh",
      "runtime/entrypoints/pixel-satiksme-stop.sh" to "${StackPaths.BIN}/pixel-satiksme-stop.sh",
      "runtime/entrypoints/pixel-satiksme-health.sh" to "${StackPaths.BIN}/pixel-satiksme-health.sh",
      "runtime/entrypoints/pixel-notifier-start.sh" to "${StackPaths.BIN}/pixel-notifier-start.sh",
      "runtime/entrypoints/pixel-notifier-stop.sh" to "${StackPaths.BIN}/pixel-notifier-stop.sh",
      "runtime/entrypoints/pixel-subscription-start.sh" to "${StackPaths.BIN}/pixel-subscription-start.sh",
      "runtime/entrypoints/pixel-subscription-stop.sh" to "${StackPaths.BIN}/pixel-subscription-stop.sh",
      "runtime/entrypoints/pixel-subscription-health.sh" to "${StackPaths.BIN}/pixel-subscription-health.sh"
    )
    if (names.isEmpty()) {
      return scripts
    }
    return names.associate { name ->
      val source = "runtime/entrypoints/$name"
      source to (scripts[source] ?: error("Unsupported orchestrator entrypoint: $name"))
    }
  }

  private suspend fun installOrchestratorEntrypoints(
    assets: AssetProvider,
    scripts: Map<String, String>
  ) {

    for ((source, target) in scripts) {
      installSingleAsset(assets, source, target, mode = "0755")
    }
  }

  private suspend fun installAssetTree(
    assets: AssetProvider,
    sourceRoot: String,
    targetRoot: String,
    mode: String
  ) {
    val entries = assets.list(sourceRoot)
    for (entry in entries) {
      val source = "$sourceRoot/$entry"
      val target = "$targetRoot/$entry"
      installSingleAsset(assets, source, target, mode)
    }
  }

  private suspend fun installSingleAsset(
    assets: AssetProvider,
    sourceAssetPath: String,
    targetPath: String,
    mode: String
  ) {
    val tempFile = withContext(Dispatchers.IO) {
      val file = Files.createTempFile("asset-stage-", "-${Paths.get(targetPath).fileName}")
      assets.open(sourceAssetPath).use { input ->
        Files.newOutputStream(file).use { output ->
          input.copyTo(output)
        }
      }
      file
    }

    val quotedTemp = ShellEscaper.singleQuote(tempFile.toAbsolutePath().toString())
    val quotedTarget = ShellEscaper.singleQuote(targetPath)
    val quotedParent = ShellEscaper.singleQuote(Paths.get(targetPath).parent.toString())
    val command = "mkdir -p $quotedParent && cp $quotedTemp $quotedTarget && chmod $mode $quotedTarget"
    val result = rootExecutor.run(command)
    if (!result.ok) {
      error("Failed to install asset $sourceAssetPath -> $targetPath: ${result.stderr}")
    }

    withContext(Dispatchers.IO) {
      Files.deleteIfExists(tempFile)
    }
  }

  private suspend fun installDnsRuntimeAssetsBundle(bundleTar: Path) {
    val quotedTar = ShellEscaper.singleQuote(bundleTar.toAbsolutePath().toString())
    val rootedTemplatesRoot = ShellEscaper.singleQuote("${StackPaths.BASE}/templates/rooted")
    val binRoot = ShellEscaper.singleQuote(StackPaths.BIN)
    val command = """
      set -eu
      stage_root="$(mktemp -d /data/local/tmp/pixel-dns-assets.XXXXXX)"
      cleanup() {
        rm -rf "${'$'}stage_root"
      }
      trap cleanup EXIT

      mkdir -p "${'$'}stage_root"
      tar -xf $quotedTar -C "${'$'}stage_root"

      [ -d "${'$'}stage_root/templates/rooted" ] || {
        echo "missing templates/rooted in dns runtime asset bundle" >&2
        exit 41
      }
      [ -f "${'$'}stage_root/bin/pixel-dns-start.sh" ] || {
        echo "missing bin/pixel-dns-start.sh in dns runtime asset bundle" >&2
        exit 42
      }
      [ -f "${'$'}stage_root/bin/pixel-dns-stop.sh" ] || {
        echo "missing bin/pixel-dns-stop.sh in dns runtime asset bundle" >&2
        exit 43
      }

      mkdir -p $rootedTemplatesRoot $binRoot
      cp -a "${'$'}stage_root/templates/rooted/." $rootedTemplatesRoot/
      cp "${'$'}stage_root/bin/pixel-dns-start.sh" $binRoot/pixel-dns-start.sh
      cp "${'$'}stage_root/bin/pixel-dns-stop.sh" $binRoot/pixel-dns-stop.sh
      find $rootedTemplatesRoot -type f -exec chmod 0755 '{}' '+'
      chmod 0755 $binRoot/pixel-dns-start.sh $binRoot/pixel-dns-stop.sh
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("DNS runtime asset bundle install failed: ${result.stderr}")
    }
  }

  private suspend fun installRootfsFromTarball(rootfsTar: Path, rootfsPath: String) {
    val quotedTar = ShellEscaper.singleQuote(rootfsTar.toAbsolutePath().toString())
    val quotedRootfs = ShellEscaper.singleQuote(rootfsPath)

    val command = """
      set -eu
      rootfs=$quotedRootfs

      unmount_if_mounted() {
        target="${'$'}1"
        if grep -F " ${'$'}{target} " /proc/mounts >/dev/null 2>&1; then
          umount "${'$'}target" >/dev/null 2>&1 || umount -l "${'$'}target" >/dev/null 2>&1 || true
        fi
      }

      mkdir -p "${'$'}rootfs"

      need_extract=0
      if [ ! -x "${'$'}rootfs/bin/sh" ] || [ ! -x "${'$'}rootfs/usr/bin/env" ] || [ ! -x "${'$'}rootfs/usr/bin/bash" ]; then
        need_extract=1
      fi

      if [ "${'$'}need_extract" -eq 1 ]; then
        unmount_if_mounted "${'$'}rootfs/opt/adguardhome/conf"
        unmount_if_mounted "${'$'}rootfs/opt/adguardhome/work"
        unmount_if_mounted "${'$'}rootfs/dev/pts"
        unmount_if_mounted "${'$'}rootfs/dev"
        find "${'$'}rootfs" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
        tar -xf $quotedTar -C "${'$'}rootfs"
      fi

      # Repair common merged-/usr symlinks missing from malformed rootfs artifacts.
      [ -e "${'$'}rootfs/bin" ] || { [ -d "${'$'}rootfs/usr/bin" ] && ln -s usr/bin "${'$'}rootfs/bin" || true; }
      [ -e "${'$'}rootfs/sbin" ] || { [ -d "${'$'}rootfs/usr/sbin" ] && ln -s usr/sbin "${'$'}rootfs/sbin" || true; }
      [ -e "${'$'}rootfs/lib" ] || { [ -d "${'$'}rootfs/usr/lib" ] && ln -s usr/lib "${'$'}rootfs/lib" || true; }

      # Ensure baseline dirs used by rooted runtime exist.
      mkdir -p \
        "${'$'}rootfs/etc" \
        "${'$'}rootfs/etc/apt" \
        "${'$'}rootfs/etc/apt/sources.list.d" \
        "${'$'}rootfs/etc/cron.d" \
        "${'$'}rootfs/var/log/adguardhome"

      # Validate chroot can actually execute bash with a sane PATH.
      chroot "${'$'}rootfs" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        /usr/bin/bash -c 'command -v mkdir >/dev/null'
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Rootfs extraction failed: ${result.stderr}")
    }
  }

  private suspend fun seedRootfsFromLegacyIfAvailable(rootfsPath: String): Boolean {
    if (rootfsPath == LEGACY_PIHOLE_ROOTFS) {
      return false
    }

    val quotedLegacyRootfs = ShellEscaper.singleQuote(LEGACY_PIHOLE_ROOTFS)
    val quotedTargetRootfs = ShellEscaper.singleQuote(rootfsPath)
    val command = """
      set -eu
      legacy_rootfs=$quotedLegacyRootfs
      target_rootfs=$quotedTargetRootfs

      unmount_if_mounted() {
        target="${'$'}1"
        if grep -F " ${'$'}{target} " /proc/mounts >/dev/null 2>&1; then
          umount "${'$'}target" >/dev/null 2>&1 || umount -l "${'$'}target" >/dev/null 2>&1 || true
        fi
      }

      [ -d "${'$'}legacy_rootfs" ] || exit 41
      [ -x "${'$'}legacy_rootfs/usr/bin/env" ] || exit 42
      [ -x "${'$'}legacy_rootfs/usr/bin/bash" ] || exit 43
      [ -x "${'$'}legacy_rootfs/bin/sh" ] || exit 44

      mkdir -p "${'$'}target_rootfs"
      if [ ! -x "${'$'}target_rootfs/usr/bin/env" ] || [ ! -x "${'$'}target_rootfs/usr/bin/bash" ] || [ ! -x "${'$'}target_rootfs/bin/sh" ]; then
        unmount_if_mounted "${'$'}target_rootfs/opt/adguardhome/conf"
        unmount_if_mounted "${'$'}target_rootfs/opt/adguardhome/work"
        unmount_if_mounted "${'$'}target_rootfs/dev/pts"
        unmount_if_mounted "${'$'}target_rootfs/dev"
        find "${'$'}target_rootfs" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
        for rel in usr bin sbin lib lib64 etc opt root home; do
          if [ -e "${'$'}legacy_rootfs/${'$'}rel" ]; then
            cp -a "${'$'}legacy_rootfs/${'$'}rel" "${'$'}target_rootfs/"
          fi
        done
      fi

      [ -e "${'$'}target_rootfs/bin" ] || { [ -d "${'$'}target_rootfs/usr/bin" ] && ln -s usr/bin "${'$'}target_rootfs/bin" || true; }
      [ -e "${'$'}target_rootfs/sbin" ] || { [ -d "${'$'}target_rootfs/usr/sbin" ] && ln -s usr/sbin "${'$'}target_rootfs/sbin" || true; }
      [ -e "${'$'}target_rootfs/lib" ] || { [ -d "${'$'}target_rootfs/usr/lib" ] && ln -s usr/lib "${'$'}target_rootfs/lib" || true; }

      mkdir -p \
        "${'$'}target_rootfs/etc" \
        "${'$'}target_rootfs/etc/apt" \
        "${'$'}target_rootfs/etc/apt/sources.list.d" \
        "${'$'}target_rootfs/etc/cron.d" \
        "${'$'}target_rootfs/var/log/adguardhome"

      chroot "${'$'}target_rootfs" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        /usr/bin/bash -c 'command -v mkdir >/dev/null'
    """.trimIndent()

    val result = rootExecutor.runScript(command, timeout = Duration.parse("1200s"))
    if (!result.ok) {
      trace(
        "bootstrap:artifact rootfs fallback failed exit=${result.exitCode} stderr=${
          result.stderr.lineSequence().firstOrNull().orEmpty()
        }"
      )
    }
    return result.ok
  }

  private suspend fun ensureRootfsUsable(rootfsPath: String) {
    val quotedRootfs = ShellEscaper.singleQuote(rootfsPath)
    val command = """
      set -eu
      [ -d $quotedRootfs ] || exit 1
      [ -e $quotedRootfs/bin ] || { [ -d $quotedRootfs/usr/bin ] && ln -s usr/bin $quotedRootfs/bin || true; }
      [ -e $quotedRootfs/sbin ] || { [ -d $quotedRootfs/usr/sbin ] && ln -s usr/sbin $quotedRootfs/sbin || true; }
      [ -e $quotedRootfs/lib ] || { [ -d $quotedRootfs/usr/lib ] && ln -s usr/lib $quotedRootfs/lib || true; }
      [ -x $quotedRootfs/usr/bin/env ]
      [ -x $quotedRootfs/usr/bin/bash ]
      [ -x $quotedRootfs/bin/sh ]
      chroot $quotedRootfs /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        /usr/bin/bash -c 'command -v mkdir >/dev/null'
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Rootfs validation failed: ${result.stderr}")
    }
  }

  private suspend fun installDropbearBundle(bundleTar: Path) {
    val quotedTar = ShellEscaper.singleQuote(bundleTar.toAbsolutePath().toString())
    val command = """
      set -eu
      mkdir -p ${ShellEscaper.singleQuote(StackPaths.SSH)}
      tar -xf $quotedTar -C ${ShellEscaper.singleQuote(StackPaths.SSH)}
      mkdir -p \
        ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin")} \
        ${ShellEscaper.singleQuote("${StackPaths.SSH}/conf")} \
        ${ShellEscaper.singleQuote("${StackPaths.SSH}/etc/dropbear")} \
        ${ShellEscaper.singleQuote("${StackPaths.SSH}/home/root/.ssh")} \
        ${ShellEscaper.singleQuote("${StackPaths.SSH}/logs")} \
        ${ShellEscaper.singleQuote("${StackPaths.SSH}/run")}
      if [ -x ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dropbearmulti")} ]; then
        ln -sf dropbearmulti ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dropbear")}
        ln -sf dropbearmulti ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dropbearkey")}
        ln -sf dropbearmulti ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dbclient")}
      fi
      chmod 0755 ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dropbear")} 2>/dev/null || true
      chmod 0755 ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dropbearkey")} 2>/dev/null || true
      chmod 0755 ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dbclient")} 2>/dev/null || true
      chmod 0755 ${ShellEscaper.singleQuote("${StackPaths.SSH}/bin/dropbearmulti")} 2>/dev/null || true
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Dropbear bundle install failed: ${result.stderr}")
    }
  }

  private suspend fun installTailscaleBundle(bundleTar: Path, config: StackConfigV1) {
    val runtimeRoot = config.vpn.runtimeRoot
    val quotedTar = ShellEscaper.singleQuote(bundleTar.toAbsolutePath().toString())
    val command = """
      set -eu
      mkdir -p ${ShellEscaper.singleQuote(runtimeRoot)}
      tar -xf $quotedTar -C ${ShellEscaper.singleQuote(runtimeRoot)}
      mkdir -p \
        ${ShellEscaper.singleQuote("$runtimeRoot/bin")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/conf")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/logs")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/run")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/state")}
      chmod 0755 ${ShellEscaper.singleQuote("$runtimeRoot/bin/tailscaled")} 2>/dev/null || true
      chmod 0755 ${ShellEscaper.singleQuote("$runtimeRoot/bin/tailscale")} 2>/dev/null || true
      if [ ! -x ${ShellEscaper.singleQuote("$runtimeRoot/bin/tailscaled")} ]; then
        echo "missing tailscaled binary after bundle install: $runtimeRoot/bin/tailscaled" >&2
        exit 32
      fi
      if [ ! -x ${ShellEscaper.singleQuote("$runtimeRoot/bin/tailscale")} ]; then
        echo "missing tailscale CLI after bundle install: $runtimeRoot/bin/tailscale" >&2
        exit 33
      fi
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Tailscale bundle install failed: ${result.stderr}")
    }
  }

  private suspend fun installTrainBotBundle(
    bundleTar: Path,
    config: StackConfigV1,
    releaseId: String
  ): ReleaseRollbackMetadata {
    val runtimeRoot = config.trainBot.runtimeRoot
    val quotedTar = ShellEscaper.singleQuote(bundleTar.toAbsolutePath().toString())
    val releaseRoot = "$runtimeRoot/releases/$releaseId"
    val currentLink = "$runtimeRoot/current"
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      release_root=${ShellEscaper.singleQuote(releaseRoot)}
      stage_root=${ShellEscaper.singleQuote("$releaseRoot.install.$$")}
      current_link=${ShellEscaper.singleQuote(currentLink)}
      previous_target="$(readlink -f "${'$'}current_link" 2>/dev/null || true)"

      mkdir -p "${'$'}runtime_root" "${'$'}runtime_root/releases"
      rm -rf "${'$'}stage_root" "${'$'}release_root"
      mkdir -p "${'$'}stage_root"
      tar -xf $quotedTar -C "${'$'}stage_root"
      mkdir -p \
        ${ShellEscaper.singleQuote("$runtimeRoot/bin")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/env")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/data/schedules")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/logs")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/run")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/state")}
      if [ -d "${'$'}stage_root/data/schedules" ]; then
        cp -a "${'$'}stage_root/data/schedules/." ${ShellEscaper.singleQuote("$runtimeRoot/data/schedules/")} 2>/dev/null || true
      fi
      mv "${'$'}stage_root" "${'$'}release_root"
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/train-bot")} ${ShellEscaper.singleQuote(config.trainBot.binaryPath)}
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/train-bot")} ${ShellEscaper.singleQuote("$runtimeRoot/bin/train-bot")}
      chmod 0755 "${'$'}release_root/bin/train-bot" 2>/dev/null || true
      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}release_root" "${'$'}current_link"
      echo "__PIXEL_RELEASE_META__ component=train_bot release_id=${releaseId} current_path=${currentLink}"
      echo "__PIXEL_RELEASE_META__ previous_target=${'$'}previous_target"
      echo "__PIXEL_RELEASE_META__ installed_target=${'$'}release_root"
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Train bot bundle install failed: ${result.stderr}")
    }
    return parseReleaseRollbackMetadata(
      stdout = result.stdout,
      component = "train_bot",
      releaseId = releaseId
    )
  }

  private suspend fun installSatiksmeBotBundle(
    bundleTar: Path,
    config: StackConfigV1,
    releaseId: String
  ): ReleaseRollbackMetadata {
    val runtimeRoot = config.satiksmeBot.runtimeRoot
    val quotedTar = ShellEscaper.singleQuote(bundleTar.toAbsolutePath().toString())
    val releaseRoot = "$runtimeRoot/releases/$releaseId"
    val currentLink = "$runtimeRoot/current"
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      release_root=${ShellEscaper.singleQuote(releaseRoot)}
      stage_root=${ShellEscaper.singleQuote("$releaseRoot.install.$$")}
      current_link=${ShellEscaper.singleQuote(currentLink)}
      previous_target="$(readlink -f "${'$'}current_link" 2>/dev/null || true)"

      mkdir -p "${'$'}runtime_root" "${'$'}runtime_root/releases"
      rm -rf "${'$'}stage_root" "${'$'}release_root"
      mkdir -p "${'$'}stage_root"
      tar -xf $quotedTar -C "${'$'}stage_root"
      mkdir -p \
        ${ShellEscaper.singleQuote("$runtimeRoot/bin")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/env")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/data/catalog")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/logs")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/run")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/state")}
      if [ -d "${'$'}stage_root/data/catalog" ]; then
        cp -a "${'$'}stage_root/data/catalog/." ${ShellEscaper.singleQuote("$runtimeRoot/data/catalog/")} 2>/dev/null || true
      fi
      mv "${'$'}stage_root" "${'$'}release_root"
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/satiksme-bot")} ${ShellEscaper.singleQuote(config.satiksmeBot.binaryPath)}
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/satiksme-bot")} ${ShellEscaper.singleQuote("$runtimeRoot/bin/satiksme-bot")}
      chmod 0755 "${'$'}release_root/bin/satiksme-bot" 2>/dev/null || true
      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}release_root" "${'$'}current_link"
      echo "__PIXEL_RELEASE_META__ component=satiksme_bot release_id=${releaseId} current_path=${currentLink}"
      echo "__PIXEL_RELEASE_META__ previous_target=${'$'}previous_target"
      echo "__PIXEL_RELEASE_META__ installed_target=${'$'}release_root"
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Satiksme bot bundle install failed: ${result.stderr}")
    }
    return parseReleaseRollbackMetadata(
      stdout = result.stdout,
      component = "satiksme_bot",
      releaseId = releaseId
    )
  }

  private suspend fun installSiteNotifierBundle(
    bundleTar: Path,
    config: StackConfigV1,
    releaseId: String
  ): ReleaseRollbackMetadata {
    val runtimeRoot = config.siteNotifier.runtimeRoot
    val quotedTar = ShellEscaper.singleQuote(bundleTar.toAbsolutePath().toString())
    val releaseRoot = "$runtimeRoot/releases/$releaseId"
    val currentLink = "$runtimeRoot/current"
    val bundledPythonWrapper = "$runtimeRoot/bin/site-notifier-python.current"
    val bundledPythonBinary = "$runtimeRoot/bin/site-notifier-python3.current"
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      release_root=${ShellEscaper.singleQuote(releaseRoot)}
      stage_root=${ShellEscaper.singleQuote("$releaseRoot.install.$$")}
      current_link=${ShellEscaper.singleQuote(currentLink)}
      bundled_python_wrapper=${ShellEscaper.singleQuote(bundledPythonWrapper)}
      bundled_python_binary=${ShellEscaper.singleQuote(bundledPythonBinary)}
      bundled_python_wrapper_tmp="${'$'}bundled_python_wrapper.tmp.$$"
      bundled_python_binary_tmp="${'$'}bundled_python_binary.tmp.$$"
      previous_target="$(readlink -f "${'$'}current_link" 2>/dev/null || true)"

      mkdir -p "${'$'}runtime_root" "${'$'}runtime_root/releases"
      rm -rf "${'$'}stage_root" "${'$'}release_root"
      mkdir -p "${'$'}stage_root"
      tar -xf $quotedTar -C "${'$'}stage_root"
      mv "${'$'}stage_root" "${'$'}release_root"
      mkdir -p \
        ${ShellEscaper.singleQuote("$runtimeRoot/bin")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/env")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/logs")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/run")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/state")}
      mkdir -p "${'$'}release_root/.venv/bin"
      cp "${'$'}release_root/.runtime/usr/bin/python3" "${'$'}bundled_python_binary_tmp"
      chmod 0755 "${'$'}bundled_python_binary_tmp"
      mv -f "${'$'}bundled_python_binary_tmp" "${'$'}bundled_python_binary"
      cat > "${'$'}bundled_python_wrapper_tmp" <<'EOF_NOTIFIER_PYTHON'
#!/system/bin/sh
set -eu
APP_ROOT=${runtimeRoot}/current
PYTHON_HOME="${'$'}APP_ROOT/.runtime/usr"
PYTHON_BIN=${bundledPythonBinary}
export PYTHONHOME="${'$'}PYTHON_HOME"
export LD_LIBRARY_PATH="${'$'}PYTHON_HOME/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
unset PYTHONPATH
exec "${'$'}PYTHON_BIN" "${'$'}@"
EOF_NOTIFIER_PYTHON
      chmod 0755 "${'$'}bundled_python_wrapper_tmp"
      mv -f "${'$'}bundled_python_wrapper_tmp" "${'$'}bundled_python_wrapper"
      ln -sfn "${'$'}bundled_python_wrapper" "${'$'}release_root/.venv/bin/python"
      ln -sfn python "${'$'}release_root/.venv/bin/python3"
      ln -sfn python "${'$'}release_root/.venv/bin/python3.12"
      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}release_root" "${'$'}current_link"
      if [ ! -f "${'$'}release_root/app.py" ]; then
        echo "missing notifier entry script after bundle install: ${config.siteNotifier.entryScript}" >&2
        exit 22
      fi
      if [ ! -x ${ShellEscaper.singleQuote(config.siteNotifier.pythonPath)} ]; then
        echo "missing notifier python after bundle install: ${config.siteNotifier.pythonPath}" >&2
        exit 23
      fi
      chmod 0755 ${ShellEscaper.singleQuote(config.siteNotifier.pythonPath)} 2>/dev/null || true
      echo "__PIXEL_RELEASE_META__ component=site_notifier release_id=${releaseId} current_path=${currentLink}"
      echo "__PIXEL_RELEASE_META__ previous_target=${'$'}previous_target"
      echo "__PIXEL_RELEASE_META__ installed_target=${'$'}release_root"
    """.trimIndent()
    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Site notifier bundle install failed: ${result.stderr}")
    }
    return parseReleaseRollbackMetadata(
      stdout = result.stdout,
      component = "site_notifier",
      releaseId = releaseId
    )
  }

  private suspend fun installSubscriptionBotBundle(
    bundleTar: Path,
    config: StackConfigV1,
    releaseId: String
  ): ReleaseRollbackMetadata {
    val runtimeRoot = config.subscriptionBot.runtimeRoot
    val quotedTar = ShellEscaper.singleQuote(bundleTar.toAbsolutePath().toString())
    val releaseRoot = "$runtimeRoot/releases/$releaseId"
    val currentLink = "$runtimeRoot/current"
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      release_root=${ShellEscaper.singleQuote(releaseRoot)}
      stage_root=${ShellEscaper.singleQuote("$releaseRoot.install.$$")}
      current_link=${ShellEscaper.singleQuote(currentLink)}
      previous_target="$(readlink -f "${'$'}current_link" 2>/dev/null || true)"

      mkdir -p "${'$'}runtime_root" "${'$'}runtime_root/releases"
      rm -rf "${'$'}stage_root" "${'$'}release_root"
      mkdir -p "${'$'}stage_root"
      tar -xf $quotedTar -C "${'$'}stage_root"
      mkdir -p \
        ${ShellEscaper.singleQuote("$runtimeRoot/bin")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/env")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/logs")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/run")} \
        ${ShellEscaper.singleQuote("$runtimeRoot/state")}
      mv "${'$'}stage_root" "${'$'}release_root"
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/subscription-bot")} ${ShellEscaper.singleQuote(config.subscriptionBot.binaryPath)}
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/subscription-bot")} ${ShellEscaper.singleQuote("$runtimeRoot/bin/subscription-bot")}
      chmod 0755 "${'$'}release_root/bin/subscription-bot" 2>/dev/null || true
      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}release_root" "${'$'}current_link"
      echo "__PIXEL_RELEASE_META__ component=subscription_bot release_id=${releaseId} current_path=${currentLink}"
      echo "__PIXEL_RELEASE_META__ previous_target=${'$'}previous_target"
      echo "__PIXEL_RELEASE_META__ installed_target=${'$'}release_root"
    """.trimIndent()

    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Subscription bot bundle install failed: ${result.stderr}")
    }
    return parseReleaseRollbackMetadata(
      stdout = result.stdout,
      component = "subscription_bot",
      releaseId = releaseId
    )
  }

  private suspend fun rollbackTrainBotRelease(
    config: StackConfigV1,
    rollbackMetadata: ReleaseRollbackMetadata
  ) {
    val runtimeRoot = config.trainBot.runtimeRoot
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      current_link=${ShellEscaper.singleQuote(rollbackMetadata.currentSymlinkPath)}
      rollback_target=${ShellEscaper.singleQuote(rollbackMetadata.previousTargetPath)}

      if [ -z "${'$'}rollback_target" ] || [ ! -d "${'$'}rollback_target" ]; then
        echo "missing rollback target for train bot: ${'$'}rollback_target" >&2
        exit 41
      fi

      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}rollback_target" "${'$'}current_link"
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/train-bot")} ${ShellEscaper.singleQuote(config.trainBot.binaryPath)}
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/train-bot")} ${ShellEscaper.singleQuote("$runtimeRoot/bin/train-bot")}
      chmod 0755 ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/train-bot")} 2>/dev/null || true
    """.trimIndent()
    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Train bot rollback failed: ${result.stderr}")
    }
  }

  private suspend fun rollbackSatiksmeBotRelease(
    config: StackConfigV1,
    rollbackMetadata: ReleaseRollbackMetadata
  ) {
    val runtimeRoot = config.satiksmeBot.runtimeRoot
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      current_link=${ShellEscaper.singleQuote(rollbackMetadata.currentSymlinkPath)}
      rollback_target=${ShellEscaper.singleQuote(rollbackMetadata.previousTargetPath)}

      if [ -z "${'$'}rollback_target" ] || [ ! -d "${'$'}rollback_target" ]; then
        echo "missing rollback target for satiksme bot: ${'$'}rollback_target" >&2
        exit 45
      fi

      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}rollback_target" "${'$'}current_link"
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/satiksme-bot")} ${ShellEscaper.singleQuote(config.satiksmeBot.binaryPath)}
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/satiksme-bot")} ${ShellEscaper.singleQuote("$runtimeRoot/bin/satiksme-bot")}
      chmod 0755 ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/satiksme-bot")} 2>/dev/null || true
    """.trimIndent()
    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Satiksme bot rollback failed: ${result.stderr}")
    }
  }

  private suspend fun rollbackSiteNotifierRelease(
    config: StackConfigV1,
    rollbackMetadata: ReleaseRollbackMetadata
  ) {
    val runtimeRoot = config.siteNotifier.runtimeRoot
    val bundledPythonWrapper = "$runtimeRoot/bin/site-notifier-python.current"
    val bundledPythonBinary = "$runtimeRoot/bin/site-notifier-python3.current"
    val command = """
      set -eu
      current_link=${ShellEscaper.singleQuote(rollbackMetadata.currentSymlinkPath)}
      rollback_target=${ShellEscaper.singleQuote(rollbackMetadata.previousTargetPath)}
      bundled_python_wrapper=${ShellEscaper.singleQuote(bundledPythonWrapper)}
      bundled_python_binary=${ShellEscaper.singleQuote(bundledPythonBinary)}
      bundled_python_wrapper_tmp="${'$'}bundled_python_wrapper.tmp.$$"
      bundled_python_binary_tmp="${'$'}bundled_python_binary.tmp.$$"

      if [ -z "${'$'}rollback_target" ] || [ ! -d "${'$'}rollback_target" ]; then
        echo "missing rollback target for site notifier: ${'$'}rollback_target" >&2
        exit 42
      fi

      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}rollback_target" "${'$'}current_link"
      mkdir -p ${ShellEscaper.singleQuote("$runtimeRoot/current/.venv/bin")}
      cp ${ShellEscaper.singleQuote("$runtimeRoot/current/.runtime/usr/bin/python3")} "${'$'}bundled_python_binary_tmp"
      chmod 0755 "${'$'}bundled_python_binary_tmp"
      mv -f "${'$'}bundled_python_binary_tmp" "${'$'}bundled_python_binary"
      cat > "${'$'}bundled_python_wrapper_tmp" <<'EOF_NOTIFIER_PYTHON'
#!/system/bin/sh
set -eu
APP_ROOT=${runtimeRoot}/current
PYTHON_HOME="${'$'}APP_ROOT/.runtime/usr"
PYTHON_BIN=${bundledPythonBinary}
export PYTHONHOME="${'$'}PYTHON_HOME"
export LD_LIBRARY_PATH="${'$'}PYTHON_HOME/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
unset PYTHONPATH
exec "${'$'}PYTHON_BIN" "${'$'}@"
EOF_NOTIFIER_PYTHON
      chmod 0755 "${'$'}bundled_python_wrapper_tmp"
      mv -f "${'$'}bundled_python_wrapper_tmp" "${'$'}bundled_python_wrapper"
      ln -sfn "${'$'}bundled_python_wrapper" ${ShellEscaper.singleQuote("$runtimeRoot/current/.venv/bin/python")}
      ln -sfn python ${ShellEscaper.singleQuote("$runtimeRoot/current/.venv/bin/python3")}
      ln -sfn python ${ShellEscaper.singleQuote("$runtimeRoot/current/.venv/bin/python3.12")}
      if [ ! -f ${ShellEscaper.singleQuote(config.siteNotifier.entryScript)} ]; then
        echo "missing notifier entry script after rollback: ${config.siteNotifier.entryScript}" >&2
        exit 43
      fi
      if [ ! -x ${ShellEscaper.singleQuote(config.siteNotifier.pythonPath)} ]; then
        echo "missing notifier python after rollback: ${config.siteNotifier.pythonPath}" >&2
        exit 44
      fi
      chmod 0755 ${ShellEscaper.singleQuote(config.siteNotifier.pythonPath)} 2>/dev/null || true
    """.trimIndent()
    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Site notifier rollback failed: ${result.stderr}")
    }
  }

  private suspend fun rollbackSubscriptionBotRelease(
    config: StackConfigV1,
    rollbackMetadata: ReleaseRollbackMetadata
  ) {
    val runtimeRoot = config.subscriptionBot.runtimeRoot
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      current_link=${ShellEscaper.singleQuote(rollbackMetadata.currentSymlinkPath)}
      rollback_target=${ShellEscaper.singleQuote(rollbackMetadata.previousTargetPath)}

      if [ -z "${'$'}rollback_target" ] || [ ! -d "${'$'}rollback_target" ]; then
        echo "missing rollback target for subscription bot: ${'$'}rollback_target" >&2
        exit 41
      fi

      rm -rf "${'$'}current_link"
      ln -sfn "${'$'}rollback_target" "${'$'}current_link"
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/subscription-bot")} ${ShellEscaper.singleQuote(config.subscriptionBot.binaryPath)}
      ln -sfn ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/subscription-bot")} ${ShellEscaper.singleQuote("$runtimeRoot/bin/subscription-bot")}
      chmod 0755 ${ShellEscaper.singleQuote("$runtimeRoot/current/bin/subscription-bot")} 2>/dev/null || true
    """.trimIndent()
    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Subscription bot rollback failed: ${result.stderr}")
    }
  }

  private suspend fun pruneComponentReleaseDirs(runtimeRoot: String, keepReleases: Int) {
    val command = """
      set -eu
      runtime_root=${ShellEscaper.singleQuote(runtimeRoot)}
      current_target="$(readlink -f "${'$'}runtime_root/current" 2>/dev/null || true)"
      count=0
      if [ -n "${'$'}current_target" ]; then
        count=1
      fi
      for dir in $(ls -1dt "${'$'}runtime_root"/releases/* 2>/dev/null || true); do
        if [ -n "${'$'}current_target" ] && [ "${'$'}dir" = "${'$'}current_target" ]; then
          continue
        fi
        count=$((count + 1))
        if [ "${'$'}count" -le ${keepReleases} ]; then
          continue
        fi
        rm -rf "${'$'}dir" || true
      done
    """.trimIndent()
    val result = rootExecutor.runScript(command)
    if (!result.ok) {
      error("Release pruning failed for $runtimeRoot: ${result.stderr}")
    }
  }

  private fun parseReleaseRollbackMetadata(
    stdout: String,
    component: String,
    releaseId: String
  ): ReleaseRollbackMetadata {
    val meta =
      stdout
        .lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("__PIXEL_RELEASE_META__ ") }
        .flatMap { line ->
          line.removePrefix("__PIXEL_RELEASE_META__ ")
            .split(" ")
            .asSequence()
            .filter { it.contains("=") }
        }
        .associate { token ->
          val index = token.indexOf('=')
          token.substring(0, index) to token.substring(index + 1)
        }

    val currentPath = meta["current_path"].orEmpty()
    val installedTarget = meta["installed_target"].orEmpty()
    require(currentPath.isNotBlank()) { "Missing current_path release metadata for $component" }
    require(installedTarget.isNotBlank()) { "Missing installed_target release metadata for $component" }
    return ReleaseRollbackMetadata(
      component = component,
      releaseId = meta["release_id"].orEmpty().ifBlank { releaseId },
      currentSymlinkPath = currentPath,
      previousTargetPath = meta["previous_target"].orEmpty(),
      installedTargetPath = installedTarget
    )
  }

  private suspend fun installSshCredentialSources(config: StackConfigV1) {
    val sourceAuthorizedKeys = ShellEscaper.singleQuote(config.ssh.authorizedKeysSourceFile)
    val sourcePasswordHash = ShellEscaper.singleQuote(config.ssh.passwordHashSourceFile)
    val targetAuthorizedKeys = ShellEscaper.singleQuote("${StackPaths.SSH}/home/root/.ssh/authorized_keys")
    val targetPasswd = ShellEscaper.singleQuote("${StackPaths.SSH}/etc/passwd")
    val legacyTargetAuthorizedKeys = ShellEscaper.singleQuote("/data/adb/pixel-stack/ssh/home/root/.ssh/authorized_keys")
    val legacyTargetPasswd = ShellEscaper.singleQuote("/data/adb/pixel-stack/ssh/etc/passwd")
    val sshAuthMode = when (config.ssh.authMode.trim().lowercase()) {
      "key_only", "password_only", "key_password" -> config.ssh.authMode.trim().lowercase()
      else -> "key_password"
    }
    val keyRequired = sshAuthMode != "password_only"

    val script = """
      set -eu
      src_auth=${sourceAuthorizedKeys}
      src_hash=${sourcePasswordHash}
      dst_auth=${targetAuthorizedKeys}
      dst_passwd=${targetPasswd}
      legacy_dst_auth=${legacyTargetAuthorizedKeys}
      legacy_dst_passwd=${legacyTargetPasswd}
      ssh_auth_mode=${ShellEscaper.singleQuote(sshAuthMode)}
      ssh_key_required=${if (keyRequired) "1" else "0"}

      if [ "${'$'}ssh_key_required" = "1" ] && [ ! -f "${'$'}src_auth" ]; then
        echo "missing SSH authorized_keys source: ${'$'}src_auth" >&2
        exit 13
      fi
      if [ ! -f "${'$'}src_hash" ]; then
        echo "missing SSH password hash source: ${'$'}src_hash" >&2
        exit 14
      fi

      mkdir -p ${ShellEscaper.singleQuote("${StackPaths.SSH}/home/root/.ssh")} ${ShellEscaper.singleQuote("${StackPaths.SSH}/etc")} '/data/adb/pixel-stack/ssh/home/root/.ssh' '/data/adb/pixel-stack/ssh/etc'
      if [ "${'$'}ssh_key_required" = "1" ]; then
        cp "${'$'}src_auth" "${'$'}dst_auth"
        cp "${'$'}src_auth" "${'$'}legacy_dst_auth"
        chmod 0600 "${'$'}dst_auth"
        chmod 0600 "${'$'}legacy_dst_auth"
      else
        : > "${'$'}dst_auth"
        : > "${'$'}legacy_dst_auth"
        chmod 0600 "${'$'}dst_auth"
        chmod 0600 "${'$'}legacy_dst_auth"
      fi

      hash_line="$(sed -n '/[^[:space:]]/ { s/^[[:space:]]*//; s/[[:space:]]*${'$'}//; p; q; }' "${'$'}src_hash")"
      if [ -z "${'$'}hash_line" ]; then
        echo "empty SSH password hash source: ${'$'}src_hash" >&2
        exit 15
      fi

      if printf '%s' "${'$'}hash_line" | grep -Eq '^root:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*:[^:]*${'$'}'; then
        passwd_line="${'$'}hash_line"
      elif printf '%s' "${'$'}hash_line" | grep -Eq '^\${'$'}6\${'$'}'; then
        passwd_line="root:${'$'}hash_line:0:0:root:${StackPaths.SSH}/home/root:/system/bin/sh"
      else
        echo 'invalid SSH password hash format (expected $6$ hash or full passwd line)' >&2
        exit 16
      fi

      printf '%s\n' "${'$'}passwd_line" > "${'$'}dst_passwd"
      printf '%s\n' "${'$'}passwd_line" > "${'$'}legacy_dst_passwd"
      chmod 0600 "${'$'}dst_passwd"
      chmod 0600 "${'$'}legacy_dst_passwd"
      if [ -f "${'$'}dst_passwd" ]; then
        expected_hash="$(printf '%s\n' "${'$'}passwd_line" | awk -F: '$1=="root"{print $2; exit}' 2>/dev/null || true)"
        umount /system/etc/passwd >/dev/null 2>&1 || true
        mount -o bind "${'$'}dst_passwd" /system/etc/passwd >/dev/null 2>&1 || true
        system_hash="$(awk -F: '$1=="root"{print $2; exit}' /system/etc/passwd 2>/dev/null || true)"
        if [ "${'$'}system_hash" != "${'$'}expected_hash" ]; then
          cat "${'$'}dst_passwd" > /system/etc/passwd
          chmod 0644 /system/etc/passwd >/dev/null 2>&1 || true
        fi
      fi
    """.trimIndent()

    val result = rootExecutor.runScript(script)
    if (!result.ok) {
      error("SSH credential source installation failed: ${result.stderr}")
    }
  }

  private suspend fun installAppEnvSources(config: StackConfigV1) {
    val trainEnvSource = ShellEscaper.singleQuote(config.trainBot.envFile)
    val satiksmeEnvSource = ShellEscaper.singleQuote(config.satiksmeBot.envFile)
    val notifierEnvSource = ShellEscaper.singleQuote(config.siteNotifier.envFile)
    val subscriptionEnvSource = ShellEscaper.singleQuote(config.subscriptionBot.envFile)
    val trainEnvTarget = ShellEscaper.singleQuote("${config.trainBot.runtimeRoot}/env/train-bot.env")
    val satiksmeEnvTarget = ShellEscaper.singleQuote("${config.satiksmeBot.runtimeRoot}/env/satiksme-bot.env")
    val notifierEnvTarget = ShellEscaper.singleQuote("${config.siteNotifier.runtimeRoot}/env/site-notifications.env")
    val subscriptionEnvTarget = ShellEscaper.singleQuote("${config.subscriptionBot.runtimeRoot}/env/subscription-bot.env")
    val script = """
      set -eu
      train_src=$trainEnvSource
      satiksme_src=$satiksmeEnvSource
      notifier_src=$notifierEnvSource
      subscription_src=$subscriptionEnvSource
      train_dst=$trainEnvTarget
      satiksme_dst=$satiksmeEnvTarget
      notifier_dst=$notifierEnvTarget
      subscription_dst=$subscriptionEnvTarget

      if [ ! -f "${'$'}train_src" ]; then
        echo "missing train bot env source: ${'$'}train_src" >&2
        exit 30
      fi
      if [ ! -f "${'$'}satiksme_src" ]; then
        echo "missing satiksme bot env source: ${'$'}satiksme_src" >&2
        exit 31
      fi
      if [ ! -f "${'$'}notifier_src" ]; then
        echo "missing site notifier env source: ${'$'}notifier_src" >&2
        exit 32
      fi
      if [ ! -f "${'$'}subscription_src" ]; then
        echo "missing subscription bot env source: ${'$'}subscription_src" >&2
        exit 33
      fi

      mkdir -p ${ShellEscaper.singleQuote("${config.trainBot.runtimeRoot}/env")} ${ShellEscaper.singleQuote("${config.satiksmeBot.runtimeRoot}/env")} ${ShellEscaper.singleQuote("${config.siteNotifier.runtimeRoot}/env")} ${ShellEscaper.singleQuote("${config.subscriptionBot.runtimeRoot}/env")}
      cp "${'$'}train_src" "${'$'}train_dst"
      cp "${'$'}satiksme_src" "${'$'}satiksme_dst"
      cp "${'$'}notifier_src" "${'$'}notifier_dst"
      cp "${'$'}subscription_src" "${'$'}subscription_dst"
      chmod 0600 "${'$'}train_dst" "${'$'}satiksme_dst" "${'$'}notifier_dst" "${'$'}subscription_dst"
    """.trimIndent()
    val result = rootExecutor.runScript(script)
    if (!result.ok) {
      error("App env source installation failed: ${result.stderr}")
    }
  }

  private fun ensureRequiredArtifactsPresent(manifest: ArtifactManifest, rootfsArtifactId: String) {
    val requiredIds = listOf(rootfsArtifactId, DNS_RUNTIME_ASSET_ID, DROPBEAR_ARTIFACT_ID, TAILSCALE_ARTIFACT_ID)
    requiredIds.forEach { requiredId ->
      val entry = manifest.artifacts.firstOrNull { it.id == requiredId }
        ?: error("Missing required artifact in manifest: $requiredId")
      if (!entry.required) {
        error("Required artifact must set required=true: $requiredId")
      }
    }
    val optionalIds = listOf(TRAIN_BOT_ARTIFACT_ID, SATIKSME_BOT_ARTIFACT_ID, SITE_NOTIFIER_ARTIFACT_ID, SUBSCRIPTION_BOT_ARTIFACT_ID)
    optionalIds.forEach { optionalId ->
      val entry = manifest.artifacts.firstOrNull { it.id == optionalId } ?: return@forEach
      if (!entry.required) {
        error("Bootstrap artifact must set required=true when present: $optionalId")
      }
    }
  }

  private fun ensureComponentReleasePresent(component: String, manifest: ComponentReleaseManifest) {
    require(manifest.schema == 1) { "Unsupported component release schema: ${manifest.schema}" }
    require(manifest.componentId == component) {
      "Component release manifest targets ${manifest.componentId}, expected $component"
    }
    require(manifest.signatureSchema.lowercase() == "none") {
      "Unsupported component release signature schema: ${manifest.signatureSchema}"
    }
    require(manifest.releaseId.isNotBlank()) { "Component release id is required for $component" }
    require(manifest.artifacts.isNotEmpty()) { "Component release artifacts are required for $component" }
    if (component == "dns") {
      val actualIds = manifest.artifacts.map { it.id }
      val expectedIds = listOf(ROOTFS_ARTIFACT_ID, DNS_RUNTIME_ASSET_ID)
      require(actualIds.size == expectedIds.size && actualIds.toSet() == expectedIds.toSet()) {
        "DNS component release must contain exactly ${expectedIds.joinToString(", ")}"
      }
    }
  }

  private fun orderedComponentReleaseArtifacts(component: String, artifacts: List<ArtifactEntry>): List<ArtifactEntry> {
    if (component != "dns") {
      return artifacts
    }
    val byId = artifacts.associateBy { it.id }
    return listOf(ROOTFS_ARTIFACT_ID, DNS_RUNTIME_ASSET_ID).map { artifactId ->
      byId[artifactId] ?: error("Missing required dns release artifact: $artifactId")
    }
  }

  private companion object {
    const val LEGACY_PIHOLE_ROOTFS = "${StackPaths.BASE}/chroots/pihole"
    const val ROOTFS_ARTIFACT_ID = "adguardhome-rootfs"
    const val DNS_RUNTIME_ASSET_ID = "dns-runtime-assets"
    const val DROPBEAR_ARTIFACT_ID = "dropbear-bundle"
    const val TAILSCALE_ARTIFACT_ID = "tailscale-bundle"
    const val TRAIN_BOT_ARTIFACT_ID = "train-bot-bundle"
    const val SATIKSME_BOT_ARTIFACT_ID = "satiksme-bot-bundle"
    const val SITE_NOTIFIER_ARTIFACT_ID = "site-notifier-bundle"
    const val SUBSCRIPTION_BOT_ARTIFACT_ID = "subscription-bot-bundle"
  }
}


data class BootstrapResult(
  val success: Boolean,
  val rootGranted: Boolean,
  val installedAtEpochSeconds: Long,
  val message: String,
  val installedArtifacts: List<String>
)


data class SyncResult(
  val success: Boolean,
  val message: String,
  val rollbackMetadata: ReleaseRollbackMetadata? = null
)


data class ReleaseRollbackMetadata(
  val component: String,
  val releaseId: String,
  val currentSymlinkPath: String,
  val previousTargetPath: String,
  val installedTargetPath: String
)


data class PreflightResult(
  val rootGranted: Boolean,
  val selinuxMode: String,
  val writablePaths: Map<String, Boolean>,
  val details: String
)
