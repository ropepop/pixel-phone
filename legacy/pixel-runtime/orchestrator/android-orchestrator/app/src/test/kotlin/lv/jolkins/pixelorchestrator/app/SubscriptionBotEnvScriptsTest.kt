package lv.jolkins.pixelorchestrator.app

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionBotEnvScriptsTest {
  @Test
  fun runtimeUpsertScriptPreservesExistingEnvAndRewritesManagedKeys() {
    val envFile = Files.createTempFile("subscription-bot-env", ".env")
    Files.write(
      envFile,
      (
        """
        BOT_TOKEN=test-token
        CUSTOM_FLAG=keep-me
        SUBSCRIPTION_BOT_PAYMENT_PROVIDER=nowpayments
        SUBSCRIPTION_BOT_NOWPAYMENTS_API_KEY=keep-api-key
        SUBSCRIPTION_BOT_DB_PATH=./state/legacy.db
        SUBSCRIPTION_BOT_SESSION_SECRET_FILE=./state/legacy.secret
        SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH=./state/legacy.lock
        SUBSCRIPTION_BOT_E2E_MODE=true
        SUBSCRIPTION_BOT_WEB_ENABLED=false
        SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=true
        SUBSCRIPTION_BOT_DEFAULT_PAY_ASSET=BTC
        SUBSCRIPTION_BOT_PLATFORM_FEE_BPS=250
        """.trimIndent() + "\n"
      ).toByteArray(StandardCharsets.UTF_8)
    )

    val script = buildSubscriptionBotRuntimeEnvUpsertScript(
      envFile = envFile.toString(),
      config = lv.jolkins.pixelorchestrator.coreconfig.SubscriptionBotConfig(
        runtimeRoot = "/data/local/pixel-stack/apps/subscription-bot",
        ingressMode = "cloudflare_tunnel",
        tunnelCredentialsFile = "/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json",
        webShellEnabled = false,
        e2eMode = false,
        maxRapidRestarts = 5,
        rapidWindowSeconds = 300,
        backoffInitialSeconds = 5,
        backoffMaxSeconds = 60
      ),
      publicBaseUrl = "https://example.test/pixel-stack/subscription",
      singleInstanceLockPath = "/data/local/pixel-stack/apps/subscription-bot/run/subscription-bot.instance.lock"
    )

    val process = ProcessBuilder("sh", "-c", "set -eu\n$script")
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(output, 0, process.waitFor())

    val merged = String(Files.readAllBytes(envFile), StandardCharsets.UTF_8)
    assertTrue(merged.contains("BOT_TOKEN=test-token"))
    assertTrue(merged.contains("CUSTOM_FLAG=keep-me"))
    assertTrue(merged.contains("TZ=Europe/Riga"))
    assertTrue(merged.contains("HTTP_TIMEOUT_SEC=20"))
    assertTrue(merged.contains("LONG_POLL_TIMEOUT=30"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_E2E_MODE=false"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_ENABLED=true"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=false"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_PORT=9320"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL=https://example.test/pixel-stack/subscription"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED=true"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE=/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_DB_PATH=/data/local/pixel-stack/apps/subscription-bot/state/subscription_bot.db"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_SESSION_SECRET_FILE=/data/local/pixel-stack/apps/subscription-bot/state/subscription-bot.session.secret"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH=/data/local/pixel-stack/apps/subscription-bot/run/subscription-bot.instance.lock"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_PLATFORM_FEE_BPS=1000"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_GRACE_DAYS=3"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_RENEWAL_LEAD_DAYS=7"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_REMINDER_DAYS=7,3,1"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_PAYMENT_PROVIDER=nowpayments"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_NOWPAYMENTS_API_KEY=keep-api-key"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_DEFAULT_PAY_ASSET=USDC"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_DEFAULT_PAY_NETWORK=solana"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_ALLOWED_PAY_ASSETS=USDC,USDT,SOL,ETH,BTC"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_ALLOWED_PAY_NETWORKS=solana,tron,base,bitcoin"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_NOWPAYMENTS_API_BASE_URL=https://api.nowpayments.io"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_REQUIRED_CONFIRMATIONS=3"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_QUOTE_TTL_SEC=900"))
    assertTrue(merged.contains("SERVICE_MAX_RAPID_RESTARTS=5"))
    assertFalse(merged.contains("SUBSCRIPTION_BOT_E2E_MODE=true"))
    assertFalse(merged.contains("SUBSCRIPTION_BOT_DEFAULT_PAY_ASSET=BTC"))
    assertFalse(merged.contains("SUBSCRIPTION_BOT_PLATFORM_FEE_BPS=250"))
    assertFalse(merged.contains("SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=true"))
    assertFalse(merged.contains("SUBSCRIPTION_BOT_DB_PATH=./state/legacy.db"))
    assertFalse(merged.contains("SUBSCRIPTION_BOT_SESSION_SECRET_FILE=./state/legacy.secret"))
    assertFalse(merged.contains("SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH=./state/legacy.lock"))
    assertEquals(1, Regex("^SUBSCRIPTION_BOT_WEB_ENABLED=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SUBSCRIPTION_BOT_E2E_MODE=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL=", RegexOption.MULTILINE).findAll(merged).count())
    assertEquals(1, Regex("^SUBSCRIPTION_BOT_DEFAULT_PAY_ASSET=", RegexOption.MULTILINE).findAll(merged).count())
  }

  @Test
  fun runtimeUpsertScriptEnablesWebOnlyDuringE2E() {
    val envFile = Files.createTempFile("subscription-bot-e2e-env", ".env")
    Files.write(envFile, "BOT_TOKEN=test-token\n".toByteArray(StandardCharsets.UTF_8))

    val script = buildSubscriptionBotRuntimeEnvUpsertScript(
      envFile = envFile.toString(),
      config = lv.jolkins.pixelorchestrator.coreconfig.SubscriptionBotConfig(
        runtimeRoot = "/data/local/pixel-stack/apps/subscription-bot",
        ingressMode = "cloudflare_tunnel",
        tunnelCredentialsFile = "/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json",
        webShellEnabled = false,
        e2eMode = true,
        maxRapidRestarts = 5,
        rapidWindowSeconds = 300,
        backoffInitialSeconds = 5,
        backoffMaxSeconds = 60
      ),
      publicBaseUrl = "https://example.test/pixel-stack/subscription",
      singleInstanceLockPath = "/data/local/pixel-stack/apps/subscription-bot/run/subscription-bot.instance.lock"
    )

    val process = ProcessBuilder("sh", "-c", "set -eu\n$script")
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(output, 0, process.waitFor())

    val merged = String(Files.readAllBytes(envFile), StandardCharsets.UTF_8)
    assertTrue(merged.contains("SUBSCRIPTION_BOT_E2E_MODE=true"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_ENABLED=true"))
    assertTrue(merged.contains("SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=false"))
  }
}
