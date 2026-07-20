package lv.jolkins.pixelorchestrator.app.ticket

/** Keeps durable Ticket traces useful without copying client identity or arbitrary runtime text. */
internal object TicketTracePrivacy {
  private val numericFields = setOf(
    "blocked_ms",
    "bytes",
    "clients",
    "count",
    "duration_ms",
    "elapsed_ms",
    "fps",
    "frame_age_ms",
    "frame_sequence",
    "frames",
    "generation",
    "height",
    "last_fresh_frame_age_ms",
    "nodes",
    "startup_fps",
    "startup_frames",
    "stdout_len",
    "timeout_ms",
    "total_ms",
    "video_clients",
    "width"
  )
  private val booleanFields = setOf(
    "active",
    "available",
    "cleaned",
    "complete",
    "control_sensitive",
    "fresh_frame_ready",
    "focused",
    "h264_active",
    "interactive",
    "ok",
    "recovered",
    "stream_active",
    "success",
    "video",
    "wake_in_progress"
  )
  private val token = Regex("(?:^|\\s)([a-z][a-z0-9_]*)=([^\\s]+)")
  private val eventToken = Regex("^[a-z][a-z0-9_]{0,95}$")
  private val eventPrefixes = setOf(
    "session_", "spacetime_", "startup_phase_", "hardware_", "stream_", "recovery_",
    "root_hardware", "root_capture", "loading_", "client_", "keyframe", "ticket_brightness_",
    "latest_ticket_reselect_", "control_code_", "ticket_control_code_", "ticket_card_"
  )
  private val exactEvents = setOf(
    "ticket_state_event", "vivi_hard_reset", "secure_capture_blocked"
  )
  private val fixedValues = mapOf(
    "level" to setOf("info", "warn", "error"),
    "streamState" to setOf(
      "idle", "starting", "streaming", "live", "control_transition", "control_active",
      "control_exit", "soft_recovery", "needs_attention", "client_disconnected", "unavailable",
      "stopped"
    ),
    "sessionState" to setOf(
      "idle", "starting", "live", "control_transition", "control_active", "control_exit",
      "soft_recovery", "needs_attention", "client_disconnected", "unavailable", "stopped"
    ),
    "captureMode" to setOf("idle", "root_hardware_h264"),
    "hardwareH264State" to setOf("idle", "starting", "active", "restarting", "unavailable"),
    "hardwareH264HelperState" to setOf("unavailable", "installed", "ready", "capture_blocked"),
    "hardwareH264Visibility" to setOf("not_run", "visible", "blocked", "unknown"),
    "streamWatchdogStage" to setOf(
      "idle", "watching", "waiting_phone_ready", "healthy", "waiting_startup", "waiting_frame",
      "waiting_encoder", "cooldown", "recovering", "blocked"
    ),
    "lastStreamWatchdogAction" to setOf(
      "none", "keep_active", "wait_first_frame", "restart_capture_engine", "secure_capture_blocked"
    )
  )

  val allowedFieldNames: Set<String> = numericFields + booleanFields

  fun allowlistedFields(detail: String): Map<String, String> {
    if (detail.isBlank()) return emptyMap()
    return buildMap {
      token.findAll(detail).forEach { match ->
        val key = match.groupValues[1]
        val value = match.groupValues[2]
        when {
          key in numericFields && value.matches(Regex("-?[0-9]{1,18}")) -> put(key, value)
          key in booleanFields && value in setOf("true", "false") -> put(key, value)
        }
      }
    }
  }

  fun eventName(value: String): String? {
    if (!eventToken.matches(value)) return null
    return value.takeIf { it in exactEvents || eventPrefixes.any(it::startsWith) }
  }

  fun fixedValue(field: String, value: String): String {
    return value.takeIf { it in fixedValues[field].orEmpty() } ?: "unknown"
  }

  fun numericValue(value: String, allowNegative: Boolean = false): String {
    val pattern = if (allowNegative) Regex("-?[0-9]{1,18}") else Regex("[0-9]{1,18}")
    return value.takeIf(pattern::matches).orEmpty()
  }

  fun booleanValue(value: String): String = value.takeIf { it == "true" || it == "false" }.orEmpty()
}
