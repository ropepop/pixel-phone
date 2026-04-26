package lv.jolkins.pixelorchestrator.app

internal fun buildTrainBotWebEnvUpsertScript(
  envFile: String,
  trainWebPublicBaseUrl: String,
  ingressMode: String,
  tunnelCredentialsFile: String,
  singleInstanceLockPath: String
): String {
  val quotedEnvFile = shellSingleQuote(envFile)
  val normalizedIngressMode = when (ingressMode.trim().lowercase()) {
    "cloudflare_tunnel" -> "cloudflare_tunnel"
    else -> "direct"
  }
  val directProxyEnabled = if (normalizedIngressMode == "direct") "true" else "false"
  val tunnelEnabled = if (normalizedIngressMode == "cloudflare_tunnel") "true" else "false"
  return buildString {
    appendLine("tmp_train_env=\$(mktemp)")
    appendLine("grep -Ev '^(TRAIN_WEB_ENABLED|TRAIN_WEB_BIND_ADDR|TRAIN_WEB_PORT|TRAIN_WEB_PUBLIC_BASE_URL|TRAIN_WEB_DIRECT_PROXY_ENABLED|TRAIN_WEB_TUNNEL_ENABLED|TRAIN_WEB_TUNNEL_CREDENTIALS_FILE|TRAIN_WEB_SESSION_SECRET_FILE|TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC|SINGLE_INSTANCE_LOCK_PATH)=' ${quotedEnvFile} > \"\$tmp_train_env\" 2>/dev/null || true")
    appendLine("cat >> \"\$tmp_train_env\" <<'EOF_TRAIN_WEB'")
    appendLine("TRAIN_WEB_ENABLED=true")
    appendLine("TRAIN_WEB_BIND_ADDR=127.0.0.1")
    appendLine("TRAIN_WEB_PORT=9317")
    appendLine("TRAIN_WEB_PUBLIC_BASE_URL=${trainWebPublicBaseUrl}")
    appendLine("TRAIN_WEB_DIRECT_PROXY_ENABLED=${directProxyEnabled}")
    appendLine("TRAIN_WEB_TUNNEL_ENABLED=${tunnelEnabled}")
    appendLine("TRAIN_WEB_TUNNEL_CREDENTIALS_FILE=${tunnelCredentialsFile}")
    appendLine("TRAIN_WEB_SESSION_SECRET_FILE=/data/local/pixel-stack/conf/apps/train-bot-web-session-secret")
    appendLine("TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC=300")
    appendLine("SINGLE_INSTANCE_LOCK_PATH=${singleInstanceLockPath}")
    appendLine("EOF_TRAIN_WEB")
    appendLine("mv \"\$tmp_train_env\" ${quotedEnvFile}")
    appendLine("chmod 600 ${quotedEnvFile}")
  }
}

internal fun buildTrainBotRuntimeEnvUpsertScript(
  envFile: String,
  runtimeRoot: String,
  scheduleDir: String,
  trainWebPublicBaseUrl: String,
  ingressMode: String,
  tunnelCredentialsFile: String,
  singleInstanceLockPath: String,
  maxRapidRestarts: Int,
  rapidWindowSeconds: Int,
  backoffInitialSeconds: Int,
  backoffMaxSeconds: Int
): String {
  val quotedEnvFile = shellSingleQuote(envFile)
  return buildString {
    appendLine("tmp_train_env=\$(mktemp)")
    appendLine("grep -Ev '^(DB_PATH|TZ|SCHEDULE_DIR|LONG_POLL_TIMEOUT|HTTP_TIMEOUT_SEC|DATA_RETENTION_HOURS|REPORT_COOLDOWN_MINUTES|REPORT_DEDUPE_SECONDS|TRAIN_WEB_ENABLED|TRAIN_WEB_BIND_ADDR|TRAIN_WEB_PORT|TRAIN_WEB_PUBLIC_BASE_URL|TRAIN_WEB_DIRECT_PROXY_ENABLED|TRAIN_WEB_TUNNEL_ENABLED|TRAIN_WEB_TUNNEL_CREDENTIALS_FILE|TRAIN_WEB_SESSION_SECRET_FILE|TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC|SINGLE_INSTANCE_LOCK_PATH|SERVICE_MAX_RAPID_RESTARTS|SERVICE_RAPID_WINDOW_SEC|SERVICE_BACKOFF_INITIAL_SEC|SERVICE_BACKOFF_MAX_SEC)=' ${quotedEnvFile} > \"\$tmp_train_env\" 2>/dev/null || true")
    appendLine("cat >> \"\$tmp_train_env\" <<'EOF_TRAIN_RUNTIME'")
    appendLine("DB_PATH=${runtimeRoot}/train_bot.db")
    appendLine("TZ=Europe/Riga")
    appendLine("SCHEDULE_DIR=${scheduleDir}")
    appendLine("LONG_POLL_TIMEOUT=30")
    appendLine("HTTP_TIMEOUT_SEC=45")
    appendLine("DATA_RETENTION_HOURS=24")
    appendLine("REPORT_COOLDOWN_MINUTES=3")
    appendLine("REPORT_DEDUPE_SECONDS=90")
    appendLine("SERVICE_MAX_RAPID_RESTARTS=${maxRapidRestarts}")
    appendLine("SERVICE_RAPID_WINDOW_SEC=${rapidWindowSeconds}")
    appendLine("SERVICE_BACKOFF_INITIAL_SEC=${backoffInitialSeconds}")
    appendLine("SERVICE_BACKOFF_MAX_SEC=${backoffMaxSeconds}")
    appendLine("EOF_TRAIN_RUNTIME")
    append(
      buildTrainBotWebEnvUpsertScriptToTempFile(
        tmpFileVar = "tmp_train_env",
        trainWebPublicBaseUrl = trainWebPublicBaseUrl,
        ingressMode = ingressMode,
        tunnelCredentialsFile = tunnelCredentialsFile,
        singleInstanceLockPath = singleInstanceLockPath
      )
    )
    appendLine("mv \"\$tmp_train_env\" ${quotedEnvFile}")
    appendLine("chmod 600 ${quotedEnvFile}")
  }
}

private fun buildTrainBotWebEnvUpsertScriptToTempFile(
  tmpFileVar: String,
  trainWebPublicBaseUrl: String,
  ingressMode: String,
  tunnelCredentialsFile: String,
  singleInstanceLockPath: String
): String {
  val normalizedIngressMode = when (ingressMode.trim().lowercase()) {
    "cloudflare_tunnel" -> "cloudflare_tunnel"
    else -> "direct"
  }
  val directProxyEnabled = if (normalizedIngressMode == "direct") "true" else "false"
  val tunnelEnabled = if (normalizedIngressMode == "cloudflare_tunnel") "true" else "false"
  return buildString {
    appendLine("cat >> \"\$${tmpFileVar}\" <<'EOF_TRAIN_WEB'")
    appendLine("TRAIN_WEB_ENABLED=true")
    appendLine("TRAIN_WEB_BIND_ADDR=127.0.0.1")
    appendLine("TRAIN_WEB_PORT=9317")
    appendLine("TRAIN_WEB_PUBLIC_BASE_URL=${trainWebPublicBaseUrl}")
    appendLine("TRAIN_WEB_DIRECT_PROXY_ENABLED=${directProxyEnabled}")
    appendLine("TRAIN_WEB_TUNNEL_ENABLED=${tunnelEnabled}")
    appendLine("TRAIN_WEB_TUNNEL_CREDENTIALS_FILE=${tunnelCredentialsFile}")
    appendLine("TRAIN_WEB_SESSION_SECRET_FILE=/data/local/pixel-stack/conf/apps/train-bot-web-session-secret")
    appendLine("TRAIN_WEB_TELEGRAM_AUTH_MAX_AGE_SEC=300")
    appendLine("SINGLE_INSTANCE_LOCK_PATH=${singleInstanceLockPath}")
    appendLine("EOF_TRAIN_WEB")
  }
}

internal fun shellSingleQuote(value: String): String {
  return "'" + value.replace("'", "'\"'\"'") + "'"
}
