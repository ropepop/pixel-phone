package lv.jolkins.pixelorchestrator.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ComponentRegistryDocument(
  val schema: Int = 1,
  val components: List<ComponentRegistryEntry> = emptyList()
)

@Serializable
data class ComponentRegistryEntry(
  val id: String,
  val startCommand: String,
  val stopCommand: String,
  val healthCommand: String
)

object ComponentRegistry {
  private const val ASSET_PATH = "runtime/component-registry.json"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun load(context: Context): List<ComponentRegistryEntry> {
    return runCatching {
      context.assets.open(ASSET_PATH).use { input ->
        val raw = input.bufferedReader().readText()
        val parsed = json.decodeFromString<ComponentRegistryDocument>(raw)
        val deduped = linkedMapOf<String, ComponentRegistryEntry>()
        parsed.components
          .filter { it.id.isNotBlank() }
          .forEach { deduped[it.id] = it }
        if (deduped.isEmpty()) defaultEntries() else deduped.values.toList()
      }
    }.getOrElse { defaultEntries() }
  }

  private fun defaultEntries(): List<ComponentRegistryEntry> = listOf(
    ComponentRegistryEntry(
      id = "dns",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-dns-start.sh",
      stopCommand = "sh /data/local/pixel-stack/bin/pixel-dns-stop.sh",
      healthCommand = "ss -ltn 2>/dev/null | grep -E '[:.]53[[:space:]]' >/dev/null"
    ),
    ComponentRegistryEntry(
      id = "ssh",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-ssh-start.sh",
      stopCommand = "sh /data/local/pixel-stack/bin/pixel-ssh-stop.sh",
      healthCommand = "ss -ltn 2>/dev/null | grep -E '[:.]2222[[:space:]]' >/dev/null"
    ),
    ComponentRegistryEntry(
      id = "vpn",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-vpn-start.sh",
      stopCommand = "sh /data/local/pixel-stack/bin/pixel-vpn-stop.sh",
      healthCommand = "sh /data/local/pixel-stack/bin/pixel-vpn-health.sh"
    ),
    ComponentRegistryEntry(
      id = "train_bot",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-train-start.sh",
      stopCommand = "sh /data/local/pixel-stack/bin/pixel-train-stop.sh",
      healthCommand = "pid=${'$'}(cat /data/local/pixel-stack/apps/train-bot/run/train-bot.pid 2>/dev/null || true); [ -n \"${'$'}pid\" ] && kill -0 \"${'$'}pid\" >/dev/null 2>&1"
    ),
    ComponentRegistryEntry(
      id = "satiksme_bot",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-satiksme-start.sh",
      stopCommand = "sh /data/local/pixel-stack/bin/pixel-satiksme-stop.sh",
      healthCommand = "sh /data/local/pixel-stack/bin/pixel-satiksme-health.sh"
    ),
    ComponentRegistryEntry(
      id = "site_notifier",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-notifier-start.sh",
      stopCommand = "sh /data/local/pixel-stack/bin/pixel-notifier-stop.sh",
      healthCommand = "pid=${'$'}(cat /data/local/pixel-stack/apps/site-notifications/run/site-notifier.pid 2>/dev/null || true); [ -n \"${'$'}pid\" ] && kill -0 \"${'$'}pid\" >/dev/null 2>&1"
    ),
    ComponentRegistryEntry(
      id = "subscription_bot",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-subscription-start.sh",
      stopCommand = "sh /data/local/pixel-stack/bin/pixel-subscription-stop.sh",
      healthCommand = "sh /data/local/pixel-stack/bin/pixel-subscription-health.sh"
    ),
    ComponentRegistryEntry(
      id = "ddns",
      startCommand = "sh /data/local/pixel-stack/bin/pixel-ddns-sync.sh",
      stopCommand = "true",
      healthCommand = "test -f /data/local/pixel-stack/run/ddns-last-sync-epoch"
    ),
    ComponentRegistryEntry(
      id = "remote",
      startCommand = "true",
      stopCommand = "true",
      healthCommand = "ss -ltn 2>/dev/null | grep -E '[:.]2789[[:space:]]' >/dev/null || true"
    ),
    ComponentRegistryEntry(
      id = "management",
      startCommand = "true",
      stopCommand = "true",
      healthCommand = "sh /data/local/pixel-stack/bin/pixel-management-health.sh"
    )
  )
}
