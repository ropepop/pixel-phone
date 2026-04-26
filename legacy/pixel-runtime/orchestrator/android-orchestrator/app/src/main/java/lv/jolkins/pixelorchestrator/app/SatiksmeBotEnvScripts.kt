package lv.jolkins.pixelorchestrator.app

internal fun buildSatiksmeBotWebEnvUpsertScript(
  envFile: String,
  publicBaseUrl: String,
  ingressMode: String,
  tunnelCredentialsFile: String,
  singleInstanceLockPath: String
): String {
  val quotedEnvFile = shellSingleQuote(envFile)
  val normalizedIngressMode = when (ingressMode.trim().lowercase()) {
    "cloudflare_tunnel" -> "cloudflare_tunnel"
    else -> "direct"
  }
  val tunnelEnabled = if (normalizedIngressMode == "cloudflare_tunnel") "true" else "false"
  return buildString {
    appendLine("tmp_satiksme_env=\$(mktemp)")
    appendLine("grep -Ev '^(SATIKSME_WEB_ENABLED|SATIKSME_WEB_BIND_ADDR|SATIKSME_WEB_PORT|SATIKSME_WEB_PUBLIC_BASE_URL|SATIKSME_WEB_TUNNEL_ENABLED|SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE|SATIKSME_WEB_SESSION_SECRET_FILE|SATIKSME_WEB_TELEGRAM_AUTH_MAX_AGE_SEC|SINGLE_INSTANCE_LOCK_PATH)=' ${quotedEnvFile} > \"\$tmp_satiksme_env\" 2>/dev/null || true")
    appendLine("cat >> \"\$tmp_satiksme_env\" <<'EOF_SATIKSME_WEB'")
    appendLine("SATIKSME_WEB_ENABLED=true")
    appendLine("SATIKSME_WEB_BIND_ADDR=127.0.0.1")
    appendLine("SATIKSME_WEB_PORT=9327")
    appendLine("SATIKSME_WEB_PUBLIC_BASE_URL=${publicBaseUrl}")
    appendLine("SATIKSME_WEB_TUNNEL_ENABLED=${tunnelEnabled}")
    appendLine("SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE=${tunnelCredentialsFile}")
    appendLine("SATIKSME_WEB_SESSION_SECRET_FILE=/data/local/pixel-stack/conf/apps/satiksme-bot-web-session-secret")
    appendLine("SATIKSME_WEB_TELEGRAM_AUTH_MAX_AGE_SEC=300")
    appendLine("SINGLE_INSTANCE_LOCK_PATH=${singleInstanceLockPath}")
    appendLine("EOF_SATIKSME_WEB")
    appendLine("mv \"\$tmp_satiksme_env\" ${quotedEnvFile}")
    appendLine("chmod 600 ${quotedEnvFile}")
  }
}

internal fun buildSatiksmeBotRuntimeEnvUpsertScript(
  envFile: String,
  runtimeRoot: String,
  publicBaseUrl: String,
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
    appendLine("tmp_satiksme_env=\$(mktemp)")
    appendLine("grep -Ev '^(DB_PATH|TZ|LONG_POLL_TIMEOUT|HTTP_TIMEOUT_SEC|DATA_RETENTION_HOURS|REPORT_VISIBILITY_MINUTES|REPORT_PUBLIC_TTL_MINUTES|REPORT_COOLDOWN_MINUTES|REPORT_DEDUPE_SECONDS|REPORT_DUMP_CHAT|REPORTS_CHANNEL_URL|SATIKSME_SOURCE_STOPS_URL|SATIKSME_SOURCE_ROUTES_URL|SATIKSME_SOURCE_GTFS_URL|OFFICIAL_STOPS_URL|OFFICIAL_ROUTES_URL|OFFICIAL_GTFS_URL|OFFICIAL_LIVE_BASE_URL|SATIKSME_CATALOG_MIRROR_DIR|SATIKSME_CATALOG_OUTPUT_PATH|CATALOG_DIR|SATIKSME_CATALOG_REFRESH_HOURS|SATIKSME_CLEANUP_INTERVAL_MINUTES|SATIKSME_WEB_ENABLED|SATIKSME_WEB_BIND_ADDR|SATIKSME_WEB_PORT|SATIKSME_WEB_PUBLIC_BASE_URL|SATIKSME_WEB_TUNNEL_ENABLED|SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE|SATIKSME_WEB_SESSION_SECRET_FILE|SATIKSME_WEB_TELEGRAM_AUTH_MAX_AGE_SEC|SINGLE_INSTANCE_LOCK_PATH|SERVICE_MAX_RAPID_RESTARTS|SERVICE_RAPID_WINDOW_SEC|SERVICE_BACKOFF_INITIAL_SEC|SERVICE_BACKOFF_MAX_SEC)=' ${quotedEnvFile} > \"\$tmp_satiksme_env\" 2>/dev/null || true")
    appendLine("cat >> \"\$tmp_satiksme_env\" <<'EOF_SATIKSME_RUNTIME'")
    appendLine("DB_PATH=${runtimeRoot}/satiksme_bot.db")
    appendLine("TZ=Europe/Riga")
    appendLine("LONG_POLL_TIMEOUT=30")
    appendLine("HTTP_TIMEOUT_SEC=40")
    appendLine("DATA_RETENTION_HOURS=24")
    appendLine("REPORT_VISIBILITY_MINUTES=30")
    appendLine("REPORT_COOLDOWN_MINUTES=3")
    appendLine("REPORT_DEDUPE_SECONDS=90")
    appendLine("REPORT_DUMP_CHAT=@satiksme_bot_reports")
    appendLine("REPORTS_CHANNEL_URL=https://t.me/satiksme_bot_reports")
    appendLine("SATIKSME_SOURCE_STOPS_URL=https://saraksti.rigassatiksme.lv/riga/stops.txt")
    appendLine("SATIKSME_SOURCE_ROUTES_URL=https://saraksti.rigassatiksme.lv/riga/routes.txt")
    appendLine("SATIKSME_SOURCE_GTFS_URL=https://data.gov.lv/dati/dataset/6d78358a-0095-4ce3-b119-6cde5d0ac54f/resource/c576c770-a01b-49b0-bdc4-0005a1ec5838/download/marsrutusaraksti02_2026.zip")
    appendLine("SATIKSME_CATALOG_MIRROR_DIR=${runtimeRoot}/data/catalog/source")
    appendLine("SATIKSME_CATALOG_OUTPUT_PATH=${runtimeRoot}/data/catalog/generated/catalog.json")
    appendLine("SATIKSME_CATALOG_REFRESH_HOURS=24")
    appendLine("SATIKSME_CLEANUP_INTERVAL_MINUTES=10")
    appendLine("SERVICE_MAX_RAPID_RESTARTS=${maxRapidRestarts}")
    appendLine("SERVICE_RAPID_WINDOW_SEC=${rapidWindowSeconds}")
    appendLine("SERVICE_BACKOFF_INITIAL_SEC=${backoffInitialSeconds}")
    appendLine("SERVICE_BACKOFF_MAX_SEC=${backoffMaxSeconds}")
    appendLine("EOF_SATIKSME_RUNTIME")
    append(
      buildSatiksmeBotWebEnvUpsertScriptToTempFile(
        tmpFileVar = "tmp_satiksme_env",
        publicBaseUrl = publicBaseUrl,
        ingressMode = ingressMode,
        tunnelCredentialsFile = tunnelCredentialsFile,
        singleInstanceLockPath = singleInstanceLockPath
      )
    )
    appendLine("mv \"\$tmp_satiksme_env\" ${quotedEnvFile}")
    appendLine("chmod 600 ${quotedEnvFile}")
  }
}

private fun buildSatiksmeBotWebEnvUpsertScriptToTempFile(
  tmpFileVar: String,
  publicBaseUrl: String,
  ingressMode: String,
  tunnelCredentialsFile: String,
  singleInstanceLockPath: String
): String {
  val normalizedIngressMode = when (ingressMode.trim().lowercase()) {
    "cloudflare_tunnel" -> "cloudflare_tunnel"
    else -> "direct"
  }
  val tunnelEnabled = if (normalizedIngressMode == "cloudflare_tunnel") "true" else "false"
  return buildString {
    appendLine("cat >> \"\$${tmpFileVar}\" <<'EOF_SATIKSME_WEB'")
    appendLine("SATIKSME_WEB_ENABLED=true")
    appendLine("SATIKSME_WEB_BIND_ADDR=127.0.0.1")
    appendLine("SATIKSME_WEB_PORT=9327")
    appendLine("SATIKSME_WEB_PUBLIC_BASE_URL=${publicBaseUrl}")
    appendLine("SATIKSME_WEB_TUNNEL_ENABLED=${tunnelEnabled}")
    appendLine("SATIKSME_WEB_TUNNEL_CREDENTIALS_FILE=${tunnelCredentialsFile}")
    appendLine("SATIKSME_WEB_SESSION_SECRET_FILE=/data/local/pixel-stack/conf/apps/satiksme-bot-web-session-secret")
    appendLine("SATIKSME_WEB_TELEGRAM_AUTH_MAX_AGE_SEC=300")
    appendLine("SINGLE_INSTANCE_LOCK_PATH=${singleInstanceLockPath}")
    appendLine("EOF_SATIKSME_WEB")
  }
}
