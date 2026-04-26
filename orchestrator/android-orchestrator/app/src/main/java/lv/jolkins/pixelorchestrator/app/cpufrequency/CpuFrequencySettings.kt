package lv.jolkins.pixelorchestrator.app.cpufrequency

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class CpuFrequencyCluster(
  val policyId: String,
  val displayName: String
) {
  LITTLE("policy0", "Little"),
  MID("policy4", "Mid"),
  BIG("policy7", "Big");

  companion object {
    fun fromPolicyId(value: String): CpuFrequencyCluster? = entries.firstOrNull { it.policyId == value.trim().lowercase() }
  }
}

@Serializable
enum class CpuFrequencyRuntimeState(
  val wireName: String,
  val defaultDetail: String
) {
  DISABLED("disabled", "Feature is off"),
  STARTING("starting", "Starting CPU and GPU cap control"),
  ENFORCING("enforcing", "Applying CPU and GPU caps"),
  SYSTEM_LIMITED("system_limited", "System thermal limit is below the requested cap"),
  STOPPED("stopped", "Stopped"),
  RESTORING_STOCK("restoring_stock", "Restoring stock CPU and GPU limits"),
  ERROR("error", "CPU or GPU cap control error");

  companion object {
    fun fromWireName(value: String?): CpuFrequencyRuntimeState {
      return entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: DISABLED
    }
  }
}

@Serializable
data class CpuFrequencyPolicySnapshot(
  val policyId: String,
  val label: String,
  val affectedCpus: String = "",
  val currentFreqKHz: Long = 0L,
  val appliedMaxFreqKHz: Long = 0L,
  val stockMaxFreqKHz: Long = 0L,
  val minFreqKHz: Long = 0L,
  val availableFreqsKHz: List<Long> = emptyList()
)

@Serializable
data class GpuFrequencyLiveSnapshot(
  val sysfsPath: String = "",
  val currentFreqKHz: Long = 0L,
  val appliedMaxFreqKHz: Long = 0L,
  val stockMaxFreqKHz: Long = 0L,
  val stockMinFreqKHz: Long = 0L,
  val availableFreqsKHz: List<Long> = emptyList(),
  val governor: String = "",
  val utilizationPercent: Int = -1
) {
  val available: Boolean
    get() = sysfsPath.isNotBlank() && stockMaxFreqKHz > 0L

  fun governorLabel(): String = governor.ifBlank { "Unknown" }

  fun utilizationLabel(): String {
    return if (utilizationPercent < 0) {
      "Unknown"
    } else {
      "$utilizationPercent%"
    }
  }
}

@Serializable
data class CpuFrequencyLiveSnapshot(
  val generatedAtMillis: Long = 0L,
  val policies: List<CpuFrequencyPolicySnapshot> = emptyList(),
  val gpu: GpuFrequencyLiveSnapshot = GpuFrequencyLiveSnapshot(),
  val thermalStatus: Int = -1,
  val charging: Boolean = false,
  val batteryTempTenthsC: Int = 0,
  val rootAvailable: Boolean = false
) {
  fun policy(cluster: CpuFrequencyCluster): CpuFrequencyPolicySnapshot {
    return policies.firstOrNull { it.policyId == cluster.policyId } ?: CpuFrequencyPolicySnapshot(
      policyId = cluster.policyId,
      label = cluster.displayName
    )
  }

  fun thermalStatusLabel(): String {
    return when (thermalStatus) {
      0 -> "None"
      1 -> "Light"
      2 -> "Moderate"
      3 -> "Severe"
      4 -> "Critical"
      5 -> "Emergency"
      6 -> "Shutdown"
      else -> "Unknown"
    }
  }

  fun batteryTempCelsius(): String {
    if (batteryTempTenthsC <= 0) {
      return "Unknown"
    }
    val value = batteryTempTenthsC / 10.0
    return String.format("%.1f C", value)
  }
}

@Serializable
data class CpuFrequencySettingsSnapshot(
  val enabled: Boolean = false,
  val suspended: Boolean = false,
  val desiredPolicyMaxKHz: Map<String, Long> = emptyMap(),
  val desiredGpuMaxKHz: Long? = null,
  val runtimeState: CpuFrequencyRuntimeState = CpuFrequencyRuntimeState.DISABLED,
  val runtimeDetail: String = CpuFrequencyRuntimeState.DISABLED.defaultDetail,
  val lastProbedAtMillis: Long = 0L,
  val lastAppliedAtMillis: Long = 0L,
  val lastFailureReason: String = "",
  val liveSnapshot: CpuFrequencyLiveSnapshot = CpuFrequencyLiveSnapshot(),
  val updatedAtMillis: Long = 0L
) {
  fun desiredCap(cluster: CpuFrequencyCluster): Long? = desiredPolicyMaxKHz[cluster.policyId]
  fun desiredGpuCap(): Long? = desiredGpuMaxKHz
  fun hasDesiredCpuCaps(): Boolean = desiredPolicyMaxKHz.values.any { it > 0L }
  fun hasDesiredGpuCap(): Boolean = (desiredGpuMaxKHz ?: 0L) > 0L
  fun hasAnyDesiredCaps(): Boolean = hasDesiredCpuCaps() || hasDesiredGpuCap()

  fun notificationSummary(): String {
    return when {
      !enabled -> "off"
      suspended -> "paused"
      !hasAnyDesiredCaps() -> runtimeSummary()
      else -> "${profileSummary()}: ${runtimeSummary()}"
    }
  }

  fun runtimeSummary(): String = runtimeDetail.ifBlank { runtimeState.defaultDetail }

  fun profileSummary(): String {
    val cpuSummary = CpuFrequencyCluster.entries.map { cluster ->
      val desired = desiredCap(cluster)
      "${cluster.displayName.lowercase()} ${formatKHz(desired)}"
    }
    val parts = cpuSummary.toMutableList()
    if (liveSnapshot.gpu.available || desiredGpuCap() != null) {
      parts += "gpu ${formatKHz(desiredGpuCap())}"
    }
    return parts.joinToString(" / ")
  }

  companion object {
    fun formatKHz(value: Long?): String {
      if (value == null || value <= 0L) {
        return "stock"
      }
      return if (value >= 1_000_000L) {
        String.format("%.2f GHz", value / 1_000_000.0)
      } else {
        "${value / 1000} MHz"
      }
    }
  }
}

interface CpuFrequencySettingsStore {
  fun load(): CpuFrequencySettingsSnapshot
  fun setEnabled(enabled: Boolean): CpuFrequencySettingsSnapshot
  fun setSuspended(suspended: Boolean): CpuFrequencySettingsSnapshot
  fun setDesiredCap(cluster: CpuFrequencyCluster, maxKHz: Long): CpuFrequencySettingsSnapshot
  fun setDesiredGpuCap(maxKHz: Long): CpuFrequencySettingsSnapshot
  fun updateRuntime(
    state: CpuFrequencyRuntimeState,
    detail: String = state.defaultDetail,
    lastFailureReason: String = load().lastFailureReason,
    lastAppliedAtMillis: Long = load().lastAppliedAtMillis,
    liveSnapshot: CpuFrequencyLiveSnapshot = load().liveSnapshot
  ): CpuFrequencySettingsSnapshot
  fun updateLiveSnapshot(liveSnapshot: CpuFrequencyLiveSnapshot): CpuFrequencySettingsSnapshot
}

class CpuFrequencyPreferencesStore internal constructor(
  private val sharedPreferences: SharedPreferences,
  private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
) : CpuFrequencySettingsStore {

  constructor(context: Context) : this(
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  )

  override fun load(): CpuFrequencySettingsSnapshot {
    val raw = sharedPreferences.getString(KEY_SNAPSHOT_JSON, "").orEmpty()
    if (raw.isBlank()) {
      return CpuFrequencySettingsSnapshot()
    }
    return runCatching { json.decodeFromString<CpuFrequencySettingsSnapshot>(raw) }
      .getOrElse { CpuFrequencySettingsSnapshot() }
  }

  override fun setEnabled(enabled: Boolean): CpuFrequencySettingsSnapshot {
    val current = load()
    val next = current.copy(
      enabled = enabled,
      suspended = false,
      runtimeState = if (enabled) CpuFrequencyRuntimeState.STARTING else CpuFrequencyRuntimeState.DISABLED,
      runtimeDetail = if (enabled) {
        "Waiting for the supervision service"
      } else {
        CpuFrequencyRuntimeState.DISABLED.defaultDetail
      },
      lastFailureReason = "",
      updatedAtMillis = System.currentTimeMillis()
    )
    return save(next)
  }

  override fun setSuspended(suspended: Boolean): CpuFrequencySettingsSnapshot {
    val current = load()
    val next = current.copy(
      suspended = suspended,
      runtimeState = when {
        !current.enabled -> CpuFrequencyRuntimeState.DISABLED
        suspended -> CpuFrequencyRuntimeState.STOPPED
        else -> CpuFrequencyRuntimeState.STARTING
      },
      runtimeDetail = when {
        !current.enabled -> CpuFrequencyRuntimeState.DISABLED.defaultDetail
        suspended -> CpuFrequencyRuntimeState.STOPPED.defaultDetail
        else -> "Waiting for the supervision service"
      },
      lastFailureReason = "",
      updatedAtMillis = System.currentTimeMillis()
    )
    return save(next)
  }

  override fun setDesiredCap(cluster: CpuFrequencyCluster, maxKHz: Long): CpuFrequencySettingsSnapshot {
    val current = load()
    val nextCaps = current.desiredPolicyMaxKHz.toMutableMap()
    nextCaps[cluster.policyId] = maxKHz
    val next = current.copy(
      desiredPolicyMaxKHz = nextCaps,
      runtimeState = when {
        !current.enabled -> CpuFrequencyRuntimeState.DISABLED
        current.suspended -> CpuFrequencyRuntimeState.STOPPED
        else -> CpuFrequencyRuntimeState.STARTING
      },
      runtimeDetail = when {
        !current.enabled -> CpuFrequencyRuntimeState.DISABLED.defaultDetail
        current.suspended -> CpuFrequencyRuntimeState.STOPPED.defaultDetail
        else -> "Applying updated CPU and GPU cap profile"
      },
      lastFailureReason = "",
      updatedAtMillis = System.currentTimeMillis()
    )
    return save(next)
  }

  override fun setDesiredGpuCap(maxKHz: Long): CpuFrequencySettingsSnapshot {
    val current = load()
    val next = current.copy(
      desiredGpuMaxKHz = maxKHz,
      runtimeState = when {
        !current.enabled -> CpuFrequencyRuntimeState.DISABLED
        current.suspended -> CpuFrequencyRuntimeState.STOPPED
        else -> CpuFrequencyRuntimeState.STARTING
      },
      runtimeDetail = when {
        !current.enabled -> CpuFrequencyRuntimeState.DISABLED.defaultDetail
        current.suspended -> CpuFrequencyRuntimeState.STOPPED.defaultDetail
        else -> "Applying updated CPU and GPU cap profile"
      },
      lastFailureReason = "",
      updatedAtMillis = System.currentTimeMillis()
    )
    return save(next)
  }

  override fun updateRuntime(
    state: CpuFrequencyRuntimeState,
    detail: String,
    lastFailureReason: String,
    lastAppliedAtMillis: Long,
    liveSnapshot: CpuFrequencyLiveSnapshot
  ): CpuFrequencySettingsSnapshot {
    val current = load()
    val next = current.copy(
      runtimeState = state,
      runtimeDetail = detail,
      lastFailureReason = lastFailureReason,
      lastAppliedAtMillis = lastAppliedAtMillis,
      lastProbedAtMillis = liveSnapshot.generatedAtMillis,
      liveSnapshot = liveSnapshot,
      updatedAtMillis = System.currentTimeMillis()
    )
    return save(next)
  }

  override fun updateLiveSnapshot(liveSnapshot: CpuFrequencyLiveSnapshot): CpuFrequencySettingsSnapshot {
    val current = load()
    val next = current.copy(
      lastProbedAtMillis = liveSnapshot.generatedAtMillis,
      liveSnapshot = liveSnapshot,
      updatedAtMillis = System.currentTimeMillis()
    )
    return save(next)
  }

  private fun save(snapshot: CpuFrequencySettingsSnapshot): CpuFrequencySettingsSnapshot {
    sharedPreferences.edit(commit = true) {
      putString(KEY_SNAPSHOT_JSON, json.encodeToString(snapshot))
    }
    return snapshot
  }

  companion object {
    private const val PREFS_NAME = "cpu_frequency_controls"
    private const val KEY_SNAPSHOT_JSON = "cpu_frequency_snapshot_v1"
  }
}
