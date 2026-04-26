package lv.jolkins.pixelorchestrator.app.cpufrequency

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.app.SupervisorService
import lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import lv.jolkins.pixelorchestrator.supervisor.AutoStartAwareComponentController
import lv.jolkins.pixelorchestrator.supervisor.ComponentController
import lv.jolkins.pixelorchestrator.supervisor.ModuleHealthAwareComponentController

@Serializable
private data class CpuFrequencyProbePayload(
  val generatedAtMillis: Long = 0L,
  val thermalStatus: Int = -1,
  val charging: Boolean = false,
  val batteryTempTenthsC: Int = 0,
  val rootAvailable: Boolean = false,
  val policies: List<CpuFrequencyPolicySnapshot> = emptyList(),
  val gpu: GpuFrequencyLiveSnapshot? = null,
  val error: String = ""
)

internal object CpuFrequencySupport {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  suspend fun readLiveSnapshot(rootExecutor: RootExecutor): Result<CpuFrequencyLiveSnapshot> {
    return runCatching {
      val result = rootExecutor.runScript(buildProbeScript())
      if (!result.ok) {
        error(result.stderr.ifBlank { result.stdout.ifBlank { "Root probe failed" } })
      }
      val payload = json.decodeFromString<CpuFrequencyProbePayload>(result.stdout.trim())
      if (payload.error.isNotBlank()) {
        error(payload.error)
      }
      val policies = payload.policies.map { policy ->
        policy.copy(availableFreqsKHz = normalizeAvailableFrequencies(policy.availableFreqsKHz))
      }
      val gpu = payload.gpu?.copy(
        availableFreqsKHz = normalizeAvailableFrequencies(payload.gpu.availableFreqsKHz)
      ) ?: GpuFrequencyLiveSnapshot()
      CpuFrequencyLiveSnapshot(
        generatedAtMillis = payload.generatedAtMillis,
        policies = policies,
        gpu = gpu,
        thermalStatus = payload.thermalStatus,
        charging = payload.charging,
        batteryTempTenthsC = payload.batteryTempTenthsC,
        rootAvailable = payload.rootAvailable
      )
    }
  }

  suspend fun restoreStock(rootExecutor: RootExecutor, liveSnapshot: CpuFrequencyLiveSnapshot): Result<Unit> {
    return runCatching {
      liveSnapshot.policies.forEach { policy ->
        if (policy.policyId.isBlank() || policy.stockMaxFreqKHz <= 0L) {
          return@forEach
        }
        val command = "echo ${policy.stockMaxFreqKHz} > /sys/devices/system/cpu/cpufreq/${policy.policyId}/scaling_max_freq"
        val result = rootExecutor.run(command)
        if (!result.ok) {
          error(result.stderr.ifBlank { "Failed restoring stock max for ${policy.policyId}" })
        }
      }
      val gpu = liveSnapshot.gpu
      if (gpu.available && gpu.stockMaxFreqKHz > 0L) {
        val result = rootExecutor.run("echo ${gpu.stockMaxFreqKHz} > ${gpu.sysfsPath}/scaling_max_freq")
        if (!result.ok) {
          error(result.stderr.ifBlank { "Failed restoring stock GPU max" })
        }
      }
    }
  }

  suspend fun applySoftCaps(
    rootExecutor: RootExecutor,
    settings: CpuFrequencySettingsSnapshot,
    liveSnapshot: CpuFrequencyLiveSnapshot
  ): Result<CpuFrequencyApplyOutcome> {
    return runCatching {
      var wroteAny = false
      var systemLimited = false
      liveSnapshot.policies.forEach { policy ->
        val desired = settings.desiredPolicyMaxKHz[policy.policyId] ?: return@forEach
        if (desired <= 0L) {
          return@forEach
        }
        if (policy.appliedMaxFreqKHz > desired) {
          val result = rootExecutor.run(
            "echo $desired > /sys/devices/system/cpu/cpufreq/${policy.policyId}/scaling_max_freq"
          )
          if (!result.ok) {
            error(result.stderr.ifBlank { "Failed applying cap for ${policy.policyId}" })
          }
          wroteAny = true
        } else if (policy.appliedMaxFreqKHz in 1 until desired) {
          systemLimited = true
        }
      }
      val desiredGpu = settings.desiredGpuCap()
      if (desiredGpu != null && desiredGpu > 0L) {
        val gpu = liveSnapshot.gpu
        if (!gpu.available) {
          error("GPU control is unavailable on this device")
        }
        if (gpu.appliedMaxFreqKHz > desiredGpu) {
          val result = rootExecutor.run("echo $desiredGpu > ${gpu.sysfsPath}/scaling_max_freq")
          if (!result.ok) {
            error(result.stderr.ifBlank { "Failed applying GPU cap" })
          }
          wroteAny = true
        } else if (gpu.appliedMaxFreqKHz in 1 until desiredGpu) {
          systemLimited = true
        }
      }
      CpuFrequencyApplyOutcome(wroteAny = wroteAny, systemLimited = systemLimited)
    }
  }

  suspend fun moduleHealthState(
    rootExecutor: RootExecutor,
    settingsStore: CpuFrequencySettingsStore
  ): ModuleHealthState {
    val snapshot = settingsStore.load()
    if (!snapshot.enabled) {
      return ModuleHealthState(
        healthy = false,
        status = "disabled",
        details = mapOf("reason" to "disabled")
      )
    }
    if (snapshot.suspended) {
      return ModuleHealthState(
        healthy = false,
        status = "stopped",
        details = mapOf("reason" to "suspended")
      )
    }
    val liveResult = readLiveSnapshot(rootExecutor)
    val liveSnapshot = liveResult.getOrElse { error ->
      return ModuleHealthState(
        healthy = false,
        status = "error",
        details = mapOf("reason" to error.message.orEmpty().ifBlank { "probe_failed" })
      )
    }
    val details = mutableMapOf(
      "thermal_status" to liveSnapshot.thermalStatusLabel(),
      "charging" to liveSnapshot.charging.toString(),
      "battery_temp" to liveSnapshot.batteryTempCelsius()
    )
    var healthy = true
    var status = "enforcing"
    for (cluster in CpuFrequencyCluster.entries) {
      val desired = snapshot.desiredCap(cluster)
      val policy = liveSnapshot.policy(cluster)
      details["${cluster.policyId}_current"] = CpuFrequencySettingsSnapshot.formatKHz(policy.currentFreqKHz)
      details["${cluster.policyId}_max"] = CpuFrequencySettingsSnapshot.formatKHz(policy.appliedMaxFreqKHz)
      details["${cluster.policyId}_desired"] = CpuFrequencySettingsSnapshot.formatKHz(desired)
      if (desired != null && desired > 0L) {
        if (policy.appliedMaxFreqKHz > desired) {
          healthy = false
          status = "drifted"
        } else if (policy.appliedMaxFreqKHz < desired && status == "enforcing") {
          status = "system_limited"
        }
      }
    }
    val desiredGpu = snapshot.desiredGpuCap()
    val gpu = liveSnapshot.gpu
    details["gpu_available"] = gpu.available.toString()
    if (gpu.available) {
      details["gpu_current"] = CpuFrequencySettingsSnapshot.formatKHz(gpu.currentFreqKHz)
      details["gpu_max"] = CpuFrequencySettingsSnapshot.formatKHz(gpu.appliedMaxFreqKHz)
      details["gpu_desired"] = CpuFrequencySettingsSnapshot.formatKHz(desiredGpu)
      details["gpu_governor"] = gpu.governorLabel()
      details["gpu_utilization"] = gpu.utilizationLabel()
      if (desiredGpu != null && desiredGpu > 0L) {
        if (gpu.appliedMaxFreqKHz > desiredGpu) {
          healthy = false
          status = "drifted"
        } else if (gpu.appliedMaxFreqKHz < desiredGpu && status == "enforcing") {
          status = "system_limited"
        }
      }
    } else {
      details["gpu_current"] = "Unavailable"
      details["gpu_max"] = "Unavailable"
      details["gpu_desired"] = CpuFrequencySettingsSnapshot.formatKHz(desiredGpu)
    }
    return ModuleHealthState(
      healthy = healthy,
      status = status,
      details = details
    )
  }

  private fun buildProbeScript(): String {
    val d = '$'
    return """
      set -eu
      now_ms=${d}(date +%s)000
      thermal_status=${d}(dumpsys thermalservice 2>/dev/null | sed -n 's/^Thermal Status: //p' | head -n 1)
      [ -n "${d}thermal_status" ] || thermal_status=-1
      charging=false
      battery_temp=0
      battery_dump=${d}(dumpsys battery 2>/dev/null | head -n 40)
      case "${d}battery_dump" in
        *"AC powered: true"*) charging=true ;;
      esac
      battery_temp=${d}(printf '%s\n' "${d}battery_dump" | sed -n 's/.*temperature: //p' | head -n 1)
      [ -n "${d}battery_temp" ] || battery_temp=0
      json_policies=""
      sep=""
      for policy_path in /sys/devices/system/cpu/cpufreq/policy0 /sys/devices/system/cpu/cpufreq/policy4 /sys/devices/system/cpu/cpufreq/policy7; do
        if [ ! -d "${d}policy_path" ]; then
          continue
        fi
        policy_id=${d}(basename "${d}policy_path")
        affected_cpus=${d}(tr '\n' ' ' < "${d}policy_path/affected_cpus" | sed 's/[[:space:]]\+/ /g; s/^ //; s/ $//')
        current_freq=${d}(cat "${d}policy_path/scaling_cur_freq" 2>/dev/null || echo 0)
        applied_max=${d}(cat "${d}policy_path/scaling_max_freq" 2>/dev/null || echo 0)
        stock_max=${d}(cat "${d}policy_path/cpuinfo_max_freq" 2>/dev/null || echo 0)
        min_freq=${d}(cat "${d}policy_path/cpuinfo_min_freq" 2>/dev/null || echo 0)
        available=${d}(tr '\n' ' ' < "${d}policy_path/scaling_available_frequencies" 2>/dev/null | sed 's/[[:space:]]\+/ /g; s/^ //; s/ $//')
        label=${d}policy_id
        case "${d}policy_id" in
          policy0) label="Little" ;;
          policy4) label="Mid" ;;
          policy7) label="Big" ;;
        esac
        available_json=""
        available_sep=""
        for freq in ${d}available; do
          available_json="${d}available_json${d}available_sep${d}freq"
          available_sep=","
        done
        json_policies="${d}json_policies${d}sep{\"policyId\":\"${d}policy_id\",\"label\":\"${d}label\",\"affectedCpus\":\"${d}affected_cpus\",\"currentFreqKHz\":${d}current_freq,\"appliedMaxFreqKHz\":${d}applied_max,\"stockMaxFreqKHz\":${d}stock_max,\"minFreqKHz\":${d}min_freq,\"availableFreqsKHz\":[${d}available_json]}"
        sep=","
      done
      gpu_json="null"
      for gpu_path in /sys/devices/platform/1f000000.mali /sys/devices/platform/mali; do
        if [ ! -d "${d}gpu_path" ]; then
          continue
        fi
        if [ ! -f "${d}gpu_path/available_frequencies" ] || [ ! -f "${d}gpu_path/cur_freq" ]; then
          continue
        fi
        gpu_current=${d}(cat "${d}gpu_path/cur_freq" 2>/dev/null || echo 0)
        gpu_applied_max=${d}(cat "${d}gpu_path/scaling_max_freq" 2>/dev/null || echo 0)
        gpu_stock_max=${d}(cat "${d}gpu_path/max_freq" 2>/dev/null || echo 0)
        gpu_stock_min=${d}(cat "${d}gpu_path/min_freq" 2>/dev/null || echo 0)
        gpu_governor=${d}(cat "${d}gpu_path/governor" 2>/dev/null | tr -d '\n\r')
        gpu_utilization=${d}(cat "${d}gpu_path/utilization" 2>/dev/null | tr -d '\n\r')
        [ -n "${d}gpu_utilization" ] || gpu_utilization=-1
        gpu_available_freqs=${d}(tr '\n' ' ' < "${d}gpu_path/available_frequencies" 2>/dev/null | sed 's/[[:space:]]\+/ /g; s/^ //; s/ $//')
        gpu_available_json=""
        gpu_available_sep=""
        for freq in ${d}gpu_available_freqs; do
          gpu_available_json="${d}gpu_available_json${d}gpu_available_sep${d}freq"
          gpu_available_sep=","
        done
        gpu_json="{\"sysfsPath\":\"${d}gpu_path\",\"currentFreqKHz\":${d}gpu_current,\"appliedMaxFreqKHz\":${d}gpu_applied_max,\"stockMaxFreqKHz\":${d}gpu_stock_max,\"stockMinFreqKHz\":${d}gpu_stock_min,\"availableFreqsKHz\":[${d}gpu_available_json],\"governor\":\"${d}gpu_governor\",\"utilizationPercent\":${d}gpu_utilization}"
        break
      done
      printf '{"generatedAtMillis":%s,"thermalStatus":%s,"charging":%s,"batteryTempTenthsC":%s,"rootAvailable":true,"policies":[%s],"gpu":%s}\n' \
        "${d}now_ms" "${d}thermal_status" "${d}charging" "${d}battery_temp" "${d}json_policies" "${d}gpu_json"
    """.trimIndent()
  }

  private fun normalizeAvailableFrequencies(values: List<Long>): List<Long> {
    return values.filter { it > 0L }.distinct().sorted()
  }
}

internal data class CpuFrequencyApplyOutcome(
  val wroteAny: Boolean,
  val systemLimited: Boolean
)

class CpuFrequencyRuntime(
  private val settingsStore: CpuFrequencySettingsStore,
  private val rootExecutor: RootExecutor,
  private val scope: CoroutineScope,
  private val onChanged: (CpuFrequencySettingsSnapshot) -> Unit
) {
  private var job: Job? = null

  fun start() {
    if (job?.isActive == true) {
      return
    }
    job = scope.launch {
      runLoop()
    }
  }

  fun stop(
    reason: String,
    finalState: CpuFrequencyRuntimeState,
    finalDetail: String,
    restoreStock: Boolean
  ) {
    job?.cancel()
    job = null
    scope.launch {
      val liveSnapshot = CpuFrequencySupport.readLiveSnapshot(rootExecutor).getOrElse {
        settingsStore.load().liveSnapshot
      }
      if (restoreStock) {
        settingsStore.updateRuntime(
          state = CpuFrequencyRuntimeState.RESTORING_STOCK,
          detail = "Restoring stock CPU and GPU limits",
          liveSnapshot = liveSnapshot,
          lastFailureReason = ""
        ).also(onChanged)
        val restoreResult = CpuFrequencySupport.restoreStock(rootExecutor, liveSnapshot)
        if (restoreResult.isFailure) {
          val failure = settingsStore.updateRuntime(
            state = CpuFrequencyRuntimeState.ERROR,
            detail = restoreResult.exceptionOrNull()?.message.orEmpty().ifBlank { "Failed restoring stock CPU and GPU limits" },
            liveSnapshot = liveSnapshot,
            lastFailureReason = reason
          )
          onChanged(failure)
          return@launch
        }
      }
      val snapshot = settingsStore.updateRuntime(
        state = finalState,
        detail = finalDetail,
        liveSnapshot = liveSnapshot,
        lastFailureReason = ""
      )
      onChanged(snapshot)
    }
  }

  private suspend fun runLoop() {
    settingsStore.updateRuntime(
      state = CpuFrequencyRuntimeState.STARTING,
      detail = CpuFrequencyRuntimeState.STARTING.defaultDetail,
      liveSnapshot = settingsStore.load().liveSnapshot,
      lastFailureReason = ""
    ).also(onChanged)

    while (scope.isActive) {
      val current = settingsStore.load()
      if (!current.enabled || current.suspended) {
        return
      }
      val liveResult = CpuFrequencySupport.readLiveSnapshot(rootExecutor)
      if (liveResult.isFailure) {
        val failed = settingsStore.updateRuntime(
          state = CpuFrequencyRuntimeState.ERROR,
          detail = liveResult.exceptionOrNull()?.message.orEmpty().ifBlank { CpuFrequencyRuntimeState.ERROR.defaultDetail },
          liveSnapshot = current.liveSnapshot,
          lastFailureReason = liveResult.exceptionOrNull()?.message.orEmpty()
        )
        onChanged(failed)
        delay(POLL_INTERVAL_MILLIS)
        continue
      }
      var liveSnapshot = liveResult.getOrThrow()
      val applyResult = CpuFrequencySupport.applySoftCaps(rootExecutor, current, liveSnapshot)
      if (applyResult.isFailure) {
        val failed = settingsStore.updateRuntime(
          state = CpuFrequencyRuntimeState.ERROR,
          detail = applyResult.exceptionOrNull()?.message.orEmpty().ifBlank { CpuFrequencyRuntimeState.ERROR.defaultDetail },
          liveSnapshot = liveSnapshot,
          lastFailureReason = applyResult.exceptionOrNull()?.message.orEmpty()
        )
        onChanged(failed)
        delay(POLL_INTERVAL_MILLIS)
        continue
      }
      val outcome = applyResult.getOrThrow()
      if (outcome.wroteAny) {
        liveSnapshot = CpuFrequencySupport.readLiveSnapshot(rootExecutor).getOrDefault(liveSnapshot)
      }
      val nextState = if (outcome.systemLimited) {
        CpuFrequencyRuntimeState.SYSTEM_LIMITED
      } else {
        CpuFrequencyRuntimeState.ENFORCING
      }
      val detail = if (!current.hasAnyDesiredCaps()) {
        "Monitoring CPU and GPU frequencies"
      } else if (outcome.systemLimited) {
        CpuFrequencyRuntimeState.SYSTEM_LIMITED.defaultDetail
      } else {
        CpuFrequencyRuntimeState.ENFORCING.defaultDetail
      }
      val updated = settingsStore.updateRuntime(
        state = nextState,
        detail = detail,
        liveSnapshot = liveSnapshot,
        lastFailureReason = "",
        lastAppliedAtMillis = if (outcome.wroteAny) System.currentTimeMillis() else current.lastAppliedAtMillis
      )
      onChanged(updated)
      delay(POLL_INTERVAL_MILLIS)
    }
  }

  companion object {
    private const val POLL_INTERVAL_MILLIS = 5_000L
  }
}

class CpuFrequencyComponentController(
  private val context: Context,
  private val settingsStore: CpuFrequencySettingsStore,
  private val rootExecutor: RootExecutor
) : ComponentController, AutoStartAwareComponentController, ModuleHealthAwareComponentController {
  override val name: String = "cpu_frequency"

  fun setEnabled(enabled: Boolean): CpuFrequencySettingsSnapshot {
    return settingsStore.setEnabled(enabled)
  }

  override suspend fun start(): Boolean {
    settingsStore.setSuspended(false)
    SupervisorService.start(
      context = context,
      action = SupervisorService.ACTION_REFRESH_CPU_FREQUENCY
    )
    return true
  }

  override suspend fun stop(): Boolean {
    settingsStore.setSuspended(true)
    SupervisorService.start(
      context = context,
      action = SupervisorService.ACTION_REFRESH_CPU_FREQUENCY
    )
    return true
  }

  override suspend fun health(): Boolean {
    return moduleHealthState().healthy
  }

  override suspend fun shouldAutoStart(): Boolean {
    return settingsStore.load().enabled
  }

  override suspend fun moduleHealthState(): ModuleHealthState {
    return CpuFrequencySupport.moduleHealthState(rootExecutor, settingsStore)
  }

  fun notificationSummary(): String {
    return settingsStore.load().notificationSummary()
  }
}
