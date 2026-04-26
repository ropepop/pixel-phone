package lv.jolkins.pixelorchestrator.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatiksmeBotEnvScriptsTest {
  @Test
  fun runtimeUpsertScriptPreservesExistingEnvAndMigratesLegacyKeys() {
    val envFile = Files.createTempFile("satiksme-bot-env", ".env")
    Files.write(
      envFile,
      (
        """
        BOT_TOKEN=test-token
        CUSTOM_FLAG=keep-me
        REPORT_PUBLIC_TTL_MINUTES=10
        OFFICIAL_STOPS_URL=https://legacy.example/stops.txt
        SATIKSME_WEB_ENABLED=false
        """.trimIndent() + "\n"
      ).toByteArray(StandardCharsets.UTF_8)
    )

    val script = buildSatiksmeBotRuntimeEnvUpsertScript(
      envFile = envFile.toString(),
      runtimeRoot = "/data/local/pixel-stack/apps/satiksme-bot",
      publicBaseUrl = "https://example.test/pixel-stack/satiksme",
      ingressMode = "cloudflare_tunnel",
      tunnelCredentialsFile = "/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json",
      singleInstanceLockPath = "/data/local/pixel-stack/apps/satiksme-bot/run/satiksme-bot.instance.lock",
      maxRapidRestarts = 5,
      rapidWindowSeconds = 300,
      backoffInitialSeconds = 5,
      backoffMaxSeconds = 60
    )

    val process = ProcessBuilder("sh", "-c", "set -eu\n$script")
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(output, 0, process.waitFor())

    val merged = String(Files.readAllBytes(envFile), StandardCharsets.UTF_8)
    assertTrue(merged.contains("BOT_TOKEN=test-token"))
    assertTrue(merged.contains("CUSTOM_FLAG=keep-me"))
    assertTrue(merged.contains("LONG_POLL_TIMEOUT=30"))
    assertTrue(merged.contains("HTTP_TIMEOUT_SEC=40"))
    assertTrue(merged.contains("REPORT_VISIBILITY_MINUTES=30"))
    assertTrue(merged.contains("REPORT_DUMP_CHAT=@satiksme_bot_reports"))
    assertTrue(merged.contains("REPORTS_CHANNEL_URL=https://t.me/satiksme_bot_reports"))
    assertTrue(merged.contains("SATIKSME_SOURCE_STOPS_URL=https://saraksti.rigassatiksme.lv/riga/stops.txt"))
    assertTrue(merged.contains("SATIKSME_CATALOG_MIRROR_DIR=/data/local/pixel-stack/apps/satiksme-bot/data/catalog/source"))
    assertTrue(merged.contains("SATIKSME_CATALOG_OUTPUT_PATH=/data/local/pixel-stack/apps/satiksme-bot/data/catalog/generated/catalog.json"))
    assertTrue(merged.contains("SATIKSME_WEB_ENABLED=true"))
    assertTrue(merged.contains("SATIKSME_WEB_PORT=9327"))
    assertTrue(merged.contains("SATIKSME_WEB_PUBLIC_BASE_URL=https://example.test/pixel-stack/satiksme"))
    assertTrue(merged.contains("SATIKSME_WEB_TUNNEL_ENABLED=true"))
    assertTrue(merged.contains("SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE=/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json"))
    assertTrue(merged.contains("SINGLE_INSTANCE_LOCK_PATH=/data/local/pixel-stack/apps/satiksme-bot/run/satiksme-bot.instance.lock"))
    assertFalse(merged.contains("REPORT_PUBLIC_TTL_MINUTES="))
    assertFalse(merged.contains("OFFICIAL_STOPS_URL="))
    assertFalse(merged.contains("SATIKSME_LIVE_DEPARTURES_URL="))
    assertFalse(merged.contains("SATIKSME_WEB_DIRECT_PROXY_ENABLED="))
    assertEquals(1, Regex("^SATIKSME_WEB_ENABLED=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SATIKSME_WEB_PUBLIC_BASE_URL=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SATIKSME_SOURCE_STOPS_URL=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SATIKSME_CATALOG_OUTPUT_PATH=", RegexOption.MULTILINE).findAll(merged).count())
  }
}
