package lv.jolkins.pixelorchestrator.app

import lv.jolkins.pixelorchestrator.coreconfig.SubscriptionBotConfig

internal fun buildSubscriptionBotRuntimeEnvUpsertScript(
  envFile: String,
  config: SubscriptionBotConfig,
  publicBaseUrl: String,
  singleInstanceLockPath: String
): String {
  val quotedEnvFile = shellSingleQuote(envFile)
  val normalizedIngressMode = when (config.ingressMode.trim().lowercase()) {
    "cloudflare_tunnel" -> "cloudflare_tunnel"
    else -> "direct"
  }
  val tunnelEnabled = if (normalizedIngressMode == "cloudflare_tunnel") "true" else "false"
  return buildString {
    appendLine("tmp_subscription_env=\$(mktemp)")
    appendLine(
      "grep -Ev '^(TZ|HTTP_TIMEOUT_SEC|LONG_POLL_TIMEOUT|SUBSCRIPTION_BOT_E2E_MODE|SUBSCRIPTION_BOT_WEB_ENABLED|SUBSCRIPTION_BOT_WEB_SHELL_ENABLED|SUBSCRIPTION_BOT_WEB_BIND_ADDR|SUBSCRIPTION_BOT_WEB_PORT|SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL|SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED|SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE|SUBSCRIPTION_BOT_DB_PATH|SUBSCRIPTION_BOT_SESSION_SECRET_FILE|SUBSCRIPTION_BOT_TELEGRAM_AUTH_MAX_AGE_SEC|SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH|SUBSCRIPTION_BOT_PLATFORM_FEE_BPS|SUBSCRIPTION_BOT_GRACE_DAYS|SUBSCRIPTION_BOT_RENEWAL_LEAD_DAYS|SUBSCRIPTION_BOT_REMINDER_DAYS|SUBSCRIPTION_BOT_DEFAULT_PAY_ASSET|SUBSCRIPTION_BOT_DEFAULT_PAY_NETWORK|SUBSCRIPTION_BOT_ALLOWED_PAY_ASSETS|SUBSCRIPTION_BOT_ALLOWED_PAY_NETWORKS|SUBSCRIPTION_BOT_NOWPAYMENTS_API_BASE_URL|SUBSCRIPTION_BOT_REQUIRED_CONFIRMATIONS|SUBSCRIPTION_BOT_QUOTE_TTL_SEC|SERVICE_MAX_RAPID_RESTARTS|SERVICE_RAPID_WINDOW_SEC|SERVICE_BACKOFF_INITIAL_SEC|SERVICE_BACKOFF_MAX_SEC)=' ${quotedEnvFile} > \"\$tmp_subscription_env\" 2>/dev/null || true"
    )
    appendLine("cat >> \"\$tmp_subscription_env\" <<'EOF_SUBSCRIPTION_RUNTIME'")
    appendLine("TZ=Europe/Riga")
    appendLine("HTTP_TIMEOUT_SEC=20")
    appendLine("LONG_POLL_TIMEOUT=30")
    appendLine("SUBSCRIPTION_BOT_E2E_MODE=${config.e2eMode}")
    appendLine("SUBSCRIPTION_BOT_WEB_ENABLED=true")
    appendLine("SUBSCRIPTION_BOT_WEB_SHELL_ENABLED=${config.webShellEnabled}")
    appendLine("SUBSCRIPTION_BOT_WEB_BIND_ADDR=127.0.0.1")
    appendLine("SUBSCRIPTION_BOT_WEB_PORT=9320")
    appendLine("SUBSCRIPTION_BOT_WEB_PUBLIC_BASE_URL=${publicBaseUrl}")
    appendLine("SUBSCRIPTION_BOT_WEB_TUNNEL_ENABLED=${tunnelEnabled}")
    appendLine("SUBSCRIPTION_BOT_WEB_TUNNEL_CREDENTIALS_FILE=${config.tunnelCredentialsFile}")
    appendLine("SUBSCRIPTION_BOT_DB_PATH=${config.runtimeRoot}/state/subscription_bot.db")
    appendLine("SUBSCRIPTION_BOT_SESSION_SECRET_FILE=${config.runtimeRoot}/state/subscription-bot.session.secret")
    appendLine("SUBSCRIPTION_BOT_TELEGRAM_AUTH_MAX_AGE_SEC=300")
    appendLine("SUBSCRIPTION_BOT_SINGLE_INSTANCE_LOCK_PATH=${singleInstanceLockPath}")
    appendLine("SUBSCRIPTION_BOT_PLATFORM_FEE_BPS=1000")
    appendLine("SUBSCRIPTION_BOT_GRACE_DAYS=3")
    appendLine("SUBSCRIPTION_BOT_RENEWAL_LEAD_DAYS=7")
    appendLine("SUBSCRIPTION_BOT_REMINDER_DAYS=7,3,1")
    appendLine("SUBSCRIPTION_BOT_DEFAULT_PAY_ASSET=USDC")
    appendLine("SUBSCRIPTION_BOT_DEFAULT_PAY_NETWORK=solana")
    appendLine("SUBSCRIPTION_BOT_ALLOWED_PAY_ASSETS=USDC,USDT,SOL,ETH,BTC")
    appendLine("SUBSCRIPTION_BOT_ALLOWED_PAY_NETWORKS=solana,tron,base,bitcoin")
    appendLine("SUBSCRIPTION_BOT_NOWPAYMENTS_API_BASE_URL=https://api.nowpayments.io")
    appendLine("SUBSCRIPTION_BOT_REQUIRED_CONFIRMATIONS=3")
    appendLine("SUBSCRIPTION_BOT_QUOTE_TTL_SEC=900")
    appendLine("SERVICE_MAX_RAPID_RESTARTS=${config.maxRapidRestarts}")
    appendLine("SERVICE_RAPID_WINDOW_SEC=${config.rapidWindowSeconds}")
    appendLine("SERVICE_BACKOFF_INITIAL_SEC=${config.backoffInitialSeconds}")
    appendLine("SERVICE_BACKOFF_MAX_SEC=${config.backoffMaxSeconds}")
    appendLine("EOF_SUBSCRIPTION_RUNTIME")
    appendLine("mv \"\$tmp_subscription_env\" ${quotedEnvFile}")
    appendLine("chmod 600 ${quotedEnvFile}")
  }
}
