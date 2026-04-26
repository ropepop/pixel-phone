package lv.jolkins.pixelorchestrator.app

import lv.jolkins.pixelorchestrator.coreconfig.SiteNotifierConfig

internal fun buildSiteNotifierRuntimeEnvUpsertScript(
  envFile: String,
  config: SiteNotifierConfig
): String {
  val quotedEnvFile = shellSingleQuote(envFile)
  return buildString {
    appendLine("tmp_notifier_env=\$(mktemp)")
    appendLine("grep -Ev '^(GRIBU_BASE_URL|GRIBU_CHECK_URL|GRIBU_LOGIN_PATH|CHECK_INTERVAL_SEC|CHECK_INTERVAL_FAST_SEC|CHECK_INTERVAL_IDLE_SEC|CHECK_INTERVAL_ERROR_BACKOFF_MAX_SEC|HTTP_TIMEOUT_SEC|ERROR_ALERT_COOLDOWN_SEC|STATE_FILE|DAEMON_LOCK_FILE|WATCHDOG_CHECK_SEC|WATCHDOG_STALE_SEC|SUPERVISOR_RESTART_BASE_SEC|SUPERVISOR_RESTART_MAX_SEC|PARSE_LOW_CONFIDENCE_DELTA_LIMIT|ROUTE_DISCOVERY_TTL_SEC|PARSE_MIN_CONFIDENCE_BASELINE|PARSE_MIN_CONFIDENCE_UPDATE|PARSE_MIN_CONFIDENCE_ROUTE_SELECTION|RUNTIME_CONTEXT_POLICY|NOTIFIER_RUNTIME_MODE|NOTIFIER_RUN_UID|NOTIFIER_CHROOT_ROOT|NOTIFIER_CHROOT_PYTHON|NOTIFIER_PYTHON_PATH|NOTIFIER_ENTRY_SCRIPT|SERVICE_MAX_RAPID_RESTARTS|SERVICE_RAPID_WINDOW_SEC|SERVICE_BACKOFF_INITIAL_SEC|SERVICE_BACKOFF_MAX_SEC)=' ${quotedEnvFile} > \"\$tmp_notifier_env\" 2>/dev/null || true")
    appendLine("cat >> \"\$tmp_notifier_env\" <<'EOF_NOTIFIER_RUNTIME'")
    appendLine("GRIBU_BASE_URL=https://www.gribu.lv")
    appendLine("GRIBU_CHECK_URL=/lv/messages")
    appendLine("GRIBU_LOGIN_PATH=/pieslegties")
    appendLine("CHECK_INTERVAL_SEC=60")
    appendLine("CHECK_INTERVAL_FAST_SEC=20")
    appendLine("CHECK_INTERVAL_IDLE_SEC=60")
    appendLine("CHECK_INTERVAL_ERROR_BACKOFF_MAX_SEC=180")
    appendLine("HTTP_TIMEOUT_SEC=20")
    appendLine("ERROR_ALERT_COOLDOWN_SEC=1800")
    appendLine("STATE_FILE=${config.runtimeRoot}/state/state.json")
    appendLine("DAEMON_LOCK_FILE=${config.runtimeRoot}/state/daemon.lock")
    appendLine("WATCHDOG_CHECK_SEC=10")
    appendLine("WATCHDOG_STALE_SEC=120")
    appendLine("SUPERVISOR_RESTART_BASE_SEC=2")
    appendLine("SUPERVISOR_RESTART_MAX_SEC=30")
    appendLine("PARSE_LOW_CONFIDENCE_DELTA_LIMIT=20")
    appendLine("ROUTE_DISCOVERY_TTL_SEC=21600")
    appendLine("PARSE_MIN_CONFIDENCE_BASELINE=0.8")
    appendLine("PARSE_MIN_CONFIDENCE_UPDATE=0.7")
    appendLine("PARSE_MIN_CONFIDENCE_ROUTE_SELECTION=0.7")
    appendLine("RUNTIME_CONTEXT_POLICY=orchestrator_root")
    appendLine("NOTIFIER_PYTHON_PATH=${config.pythonPath}")
    appendLine("NOTIFIER_ENTRY_SCRIPT=${config.entryScript}")
    appendLine("SERVICE_MAX_RAPID_RESTARTS=${config.maxRapidRestarts}")
    appendLine("SERVICE_RAPID_WINDOW_SEC=${config.rapidWindowSeconds}")
    appendLine("SERVICE_BACKOFF_INITIAL_SEC=${config.backoffInitialSeconds}")
    appendLine("SERVICE_BACKOFF_MAX_SEC=${config.backoffMaxSeconds}")
    appendLine("EOF_NOTIFIER_RUNTIME")
    appendLine("mv \"\$tmp_notifier_env\" ${quotedEnvFile}")
    appendLine("chmod 600 ${quotedEnvFile}")
  }
}
