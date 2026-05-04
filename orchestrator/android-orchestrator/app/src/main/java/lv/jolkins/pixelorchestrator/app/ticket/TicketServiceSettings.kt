package lv.jolkins.pixelorchestrator.app.ticket

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

enum class TicketServiceRuntimeState(
  val wireName: String,
  val defaultDetail: String
) {
  DISABLED("disabled", "Ticket service reliability is off"),
  STARTING("starting", "Starting ticket service readiness"),
  READY("ready", "Ticket service is ready"),
  DEGRADED("degraded", "Ticket service readiness is degraded"),
  STOPPING("stopping", "Stopping ticket service"),
  STOPPED("stopped", "Ticket service is stopped");

  companion object {
    fun fromWireName(value: String?): TicketServiceRuntimeState {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: DISABLED
    }
  }
}

data class TicketServiceSettingsSnapshot(
  val enabled: Boolean = false,
  val runtimeState: TicketServiceRuntimeState = TicketServiceRuntimeState.DISABLED,
  val runtimeDetail: String = TicketServiceRuntimeState.DISABLED.defaultDetail,
  val lastEnsureReason: String = "",
  val lastEnsureAtMillis: Long = 0L,
  val lastEnsureSucceeded: Boolean = false,
  val lastEnsureResult: String = "",
  val localServerReachable: Boolean = false,
  val tunnelReady: Boolean = false,
  val componentStatus: String = "",
  val updatedAtMillis: Long = 0L
) {
  fun runtimeSummary(): String = runtimeDetail.ifBlank { runtimeState.defaultDetail }

  fun notificationSummary(): String {
    return when {
      !enabled -> "off"
      else -> runtimeSummary()
    }
  }

  fun statusSummary(nowMillis: Long = System.currentTimeMillis()): String {
    val ensureAge = if (lastEnsureAtMillis > 0L) {
      "${((nowMillis - lastEnsureAtMillis) / 1000L).coerceAtLeast(0L)}s ago"
    } else {
      "never"
    }
    val local = if (localServerReachable) "ready" else "not ready"
    val tunnel = if (tunnelReady) "ready" else "not ready"
    return buildString {
      append(runtimeSummary())
      append(". Local server: ")
      append(local)
      append(". Tunnel: ")
      append(tunnel)
      append(". Last check: ")
      append(ensureAge)
      if (lastEnsureReason.isNotBlank()) {
        append(" (")
        append(lastEnsureReason)
        append(")")
      }
    }
  }
}

interface TicketServiceSettingsStore {
  fun load(): TicketServiceSettingsSnapshot
  fun setEnabled(enabled: Boolean): TicketServiceSettingsSnapshot
  fun recordEnsureStarted(reason: String): TicketServiceSettingsSnapshot
  fun recordEnsureResult(
    reason: String,
    success: Boolean,
    result: String,
    localServerReachable: Boolean,
    tunnelReady: Boolean,
    componentStatus: String
  ): TicketServiceSettingsSnapshot
  fun updateRuntimeState(
    state: TicketServiceRuntimeState,
    detail: String = state.defaultDetail
  ): TicketServiceSettingsSnapshot
}

class TicketServicePreferencesStore internal constructor(
  private val backend: TicketServicePreferencesBackend
) : TicketServiceSettingsStore {
  constructor(context: Context) : this(
    SharedPreferencesTicketServiceBackend(
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )
  )

  override fun load(): TicketServiceSettingsSnapshot {
    return TicketServiceSettingsSnapshot(
      enabled = backend.getBoolean(KEY_ENABLED, false),
      runtimeState = TicketServiceRuntimeState.fromWireName(
        backend.getString(KEY_RUNTIME_STATE, TicketServiceRuntimeState.DISABLED.wireName)
      ),
      runtimeDetail = backend.getString(KEY_RUNTIME_DETAIL, TicketServiceRuntimeState.DISABLED.defaultDetail),
      lastEnsureReason = backend.getString(KEY_LAST_ENSURE_REASON, ""),
      lastEnsureAtMillis = backend.getLong(KEY_LAST_ENSURE_AT_MILLIS, 0L),
      lastEnsureSucceeded = backend.getBoolean(KEY_LAST_ENSURE_SUCCEEDED, false),
      lastEnsureResult = backend.getString(KEY_LAST_ENSURE_RESULT, ""),
      localServerReachable = backend.getBoolean(KEY_LOCAL_SERVER_REACHABLE, false),
      tunnelReady = backend.getBoolean(KEY_TUNNEL_READY, false),
      componentStatus = backend.getString(KEY_COMPONENT_STATUS, ""),
      updatedAtMillis = backend.getLong(KEY_UPDATED_AT_MILLIS, 0L)
    )
  }

  override fun setEnabled(enabled: Boolean): TicketServiceSettingsSnapshot {
    val state = if (enabled) {
      TicketServiceRuntimeState.STARTING
    } else {
      TicketServiceRuntimeState.DISABLED
    }
    val detail = if (enabled) {
      "Starting local ticket server and tunnel readiness"
    } else {
      TicketServiceRuntimeState.DISABLED.defaultDetail
    }
    return mutate(
      KEY_ENABLED to enabled,
      KEY_RUNTIME_STATE to state.wireName,
      KEY_RUNTIME_DETAIL to detail,
      KEY_LAST_ENSURE_RESULT to "",
      KEY_LOCAL_SERVER_REACHABLE to false,
      KEY_TUNNEL_READY to false,
      KEY_COMPONENT_STATUS to ""
    )
  }

  override fun recordEnsureStarted(reason: String): TicketServiceSettingsSnapshot {
    val current = load()
    if (!current.enabled) {
      return updateRuntimeState(
        TicketServiceRuntimeState.DISABLED,
        TicketServiceRuntimeState.DISABLED.defaultDetail
      )
    }
    val keepReadyWhileRechecking = current.runtimeState == TicketServiceRuntimeState.READY &&
      current.localServerReachable &&
      current.tunnelReady
    val nextState = if (keepReadyWhileRechecking) {
      TicketServiceRuntimeState.READY
    } else {
      TicketServiceRuntimeState.STARTING
    }
    val nextDetail = if (keepReadyWhileRechecking) {
      current.runtimeSummary()
    } else {
      "Checking local ticket server and tunnel readiness"
    }
    return mutate(
      KEY_RUNTIME_STATE to nextState.wireName,
      KEY_RUNTIME_DETAIL to nextDetail,
      KEY_LAST_ENSURE_REASON to reason,
      KEY_LAST_ENSURE_AT_MILLIS to System.currentTimeMillis(),
      KEY_LAST_ENSURE_SUCCEEDED to keepReadyWhileRechecking
    )
  }

  override fun recordEnsureResult(
    reason: String,
    success: Boolean,
    result: String,
    localServerReachable: Boolean,
    tunnelReady: Boolean,
    componentStatus: String
  ): TicketServiceSettingsSnapshot {
    val state = when {
      !load().enabled -> TicketServiceRuntimeState.DISABLED
      success && localServerReachable && tunnelReady -> TicketServiceRuntimeState.READY
      else -> TicketServiceRuntimeState.DEGRADED
    }
    val detail = when (state) {
      TicketServiceRuntimeState.READY -> "Local ticket server and tunnel are ready"
      TicketServiceRuntimeState.DISABLED -> TicketServiceRuntimeState.DISABLED.defaultDetail
      else -> result.ifBlank { state.defaultDetail }
    }
    return mutate(
      KEY_RUNTIME_STATE to state.wireName,
      KEY_RUNTIME_DETAIL to detail,
      KEY_LAST_ENSURE_REASON to reason,
      KEY_LAST_ENSURE_AT_MILLIS to System.currentTimeMillis(),
      KEY_LAST_ENSURE_SUCCEEDED to (state == TicketServiceRuntimeState.READY),
      KEY_LAST_ENSURE_RESULT to result,
      KEY_LOCAL_SERVER_REACHABLE to localServerReachable,
      KEY_TUNNEL_READY to tunnelReady,
      KEY_COMPONENT_STATUS to componentStatus
    )
  }

  override fun updateRuntimeState(
    state: TicketServiceRuntimeState,
    detail: String
  ): TicketServiceSettingsSnapshot {
    return mutate(
      KEY_RUNTIME_STATE to state.wireName,
      KEY_RUNTIME_DETAIL to detail.ifBlank { state.defaultDetail }
    )
  }

  private fun mutate(vararg updates: Pair<String, Any>): TicketServiceSettingsSnapshot {
    val mutations = updates.toMap() + (KEY_UPDATED_AT_MILLIS to System.currentTimeMillis())
    backend.applyMutations(mutations)
    return load()
  }

  private companion object {
    private const val PREFS_NAME = "ticket_service_settings"
    private const val KEY_ENABLED = "ticket_service_enabled"
    private const val KEY_RUNTIME_STATE = "runtime_state"
    private const val KEY_RUNTIME_DETAIL = "runtime_detail"
    private const val KEY_LAST_ENSURE_REASON = "last_ensure_reason"
    private const val KEY_LAST_ENSURE_AT_MILLIS = "last_ensure_at_millis"
    private const val KEY_LAST_ENSURE_SUCCEEDED = "last_ensure_succeeded"
    private const val KEY_LAST_ENSURE_RESULT = "last_ensure_result"
    private const val KEY_LOCAL_SERVER_REACHABLE = "local_server_reachable"
    private const val KEY_TUNNEL_READY = "tunnel_ready"
    private const val KEY_COMPONENT_STATUS = "component_status"
    private const val KEY_UPDATED_AT_MILLIS = "updated_at_millis"
  }
}

internal interface TicketServicePreferencesBackend {
  fun getBoolean(key: String, defaultValue: Boolean): Boolean
  fun getString(key: String, defaultValue: String): String
  fun getLong(key: String, defaultValue: Long): Long
  fun applyMutations(mutations: Map<String, Any>)
}

private class SharedPreferencesTicketServiceBackend(
  private val sharedPreferences: SharedPreferences
) : TicketServicePreferencesBackend {
  override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
    return sharedPreferences.getBoolean(key, defaultValue)
  }

  override fun getString(key: String, defaultValue: String): String {
    return sharedPreferences.getString(key, defaultValue).orEmpty().ifBlank { defaultValue }
  }

  override fun getLong(key: String, defaultValue: Long): Long {
    return sharedPreferences.getLong(key, defaultValue)
  }

  override fun applyMutations(mutations: Map<String, Any>) {
    sharedPreferences.edit(commit = true) {
      mutations.forEach { (key, value) ->
        when (value) {
          is Boolean -> putBoolean(key, value)
          is String -> putString(key, value)
          is Long -> putLong(key, value)
          else -> error("Unsupported preferences value for $key: ${value::class.java.name}")
        }
      }
    }
  }
}
