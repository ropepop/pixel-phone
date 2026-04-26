package lv.jolkins.pixelorchestrator.app

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainBotEnvScriptsTest {
  @Test
  fun upsertScriptPreservesExistingEnvAndOverridesTrainWebKeys() {
    val envFile = Files.createTempFile("train-bot-env", ".env")
    Files.write(
      envFile,
      (
        """
        BOT_TOKEN=test-token
        CUSTOM_FLAG=keep-me
        TRAIN_WEB_ENABLED=false
        TRAIN_WEB_PUBLIC_BASE_URL=https://old.example/pixel-stack/train
        """.trimIndent() + "\n"
      ).toByteArray(StandardCharsets.UTF_8)
    )
    val script = buildTrainBotWebEnvUpsertScript(
      envFile = envFile.toString(),
      trainWebPublicBaseUrl = "https://example.test/pixel-stack/train",
      ingressMode = "cloudflare_tunnel",
      tunnelCredentialsFile = "/data/local/pixel-stack/conf/apps/train-bot-cloudflared.json",
      singleInstanceLockPath = "/data/local/pixel-stack/apps/train-bot/run/train-bot.instance.lock"
    )

    val process = ProcessBuilder("sh", "-c", "set -eu\n$script")
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(output, 0, process.waitFor())

    val merged = String(Files.readAllBytes(envFile), StandardCharsets.UTF_8)
    assertTrue(merged.contains("BOT_TOKEN=test-token"))
    assertTrue(merged.contains("CUSTOM_FLAG=keep-me"))
    assertTrue(merged.contains("TRAIN_WEB_ENABLED=true"))
    assertTrue(merged.contains("TRAIN_WEB_BIND_ADDR=127.0.0.1"))
    assertTrue(merged.contains("TRAIN_WEB_PORT=9317"))
    assertTrue(merged.contains("TRAIN_WEB_PUBLIC_BASE_URL=https://example.test/pixel-stack/train"))
    assertTrue(merged.contains("TRAIN_WEB_DIRECT_PROXY_ENABLED=false"))
    assertTrue(merged.contains("TRAIN_WEB_TUNNEL_ENABLED=true"))
    assertTrue(merged.contains("TRAIN_WEB_TUNNEL_CREDENTIALS_FILE=/data/local/pixel-stack/conf/apps/train-bot-cloudflared.json"))
    assertTrue(merged.contains("TRAIN_WEB_SESSION_SECRET_FILE=/data/local/pixel-stack/conf/apps/train-bot-web-session-secret"))
    assertTrue(merged.contains("TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC=300"))
    assertTrue(merged.contains("SINGLE_INSTANCE_LOCK_PATH=/data/local/pixel-stack/apps/train-bot/run/train-bot.instance.lock"))
    assertFalse(merged.contains("TRAIN_WEB_ENABLED=false"))
    assertFalse(merged.contains("https://old.example/pixel-stack/train"))
    assertEquals(1, Regex("^TRAIN_WEB_ENABLED=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^TRAIN_WEB_PUBLIC_BASE_URL=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^TRAIN_WEB_DIRECT_PROXY_ENABLED=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^TRAIN_WEB_TUNNEL_ENABLED=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^TRAIN_WEB_TUNNEL_CREDENTIALS_FILE=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SINGLE_INSTANCE_LOCK_PATH=", RegexOption.MULTILINE).findAll(merged).count())
  }
}
