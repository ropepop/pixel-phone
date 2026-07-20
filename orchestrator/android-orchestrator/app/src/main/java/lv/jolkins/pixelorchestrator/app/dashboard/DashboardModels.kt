package lv.jolkins.pixelorchestrator.app.dashboard

import android.net.Uri
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyCluster
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencyLiveSnapshot
import lv.jolkins.pixelorchestrator.app.cpufrequency.CpuFrequencySettingsSnapshot
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationBackgroundReliability
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationDispatchInterval
import lv.jolkins.pixelorchestrator.app.phoneautomation.PhoneAutomationSettingsSnapshot
import lv.jolkins.pixelorchestrator.app.phoneautomation.phoneAutomationUiPolicy
import lv.jolkins.pixelorchestrator.app.ticket.TicketServiceSettingsSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot

internal enum class DashboardHealthLevel {
  HEALTHY,
  DEGRADED,
  FAILED,
  UNKNOWN,
  STALE
}

internal enum class DashboardOperationStatus {
  IDLE,
  RUNNING,
  SUCCEEDED,
  FAILED
}

internal data class DashboardModuleStatus(
  val id: String,
  val label: String,
  val healthy: Boolean?,
  val status: String,
  val details: Map<String, String> = emptyMap()
)

internal data class DashboardOperationState(
  val status: DashboardOperationStatus = DashboardOperationStatus.IDLE,
  val action: String = "",
  val title: String = "",
  val message: String = "",
  val detail: String = ""
) {
  val inProgress: Boolean
    get() = status == DashboardOperationStatus.RUNNING
}

internal data class DashboardActivityItem(
  val recordedAtMillis: Long,
  val title: String,
  val detail: String,
  val successful: Boolean?
)

internal data class DashboardUiState(
  val buildIdentity: String,
  val phoneAutomation: PhoneAutomationSettingsSnapshot = PhoneAutomationSettingsSnapshot(),
  val ticketService: TicketServiceSettingsSnapshot = TicketServiceSettingsSnapshot(),
  val cpuFrequency: CpuFrequencySettingsSnapshot = CpuFrequencySettingsSnapshot(),
  val cpuLive: CpuFrequencyLiveSnapshot = CpuFrequencyLiveSnapshot(),
  val reliability: PhoneAutomationBackgroundReliability = PhoneAutomationBackgroundReliability(
    batteryUnrestricted = false,
    exactAlarmGranted = false
  ),
  val healthLevel: DashboardHealthLevel = DashboardHealthLevel.UNKNOWN,
  val healthGeneratedAtMillis: Long = 0L,
  val modules: List<DashboardModuleStatus> = defaultDashboardModules(),
  val operation: DashboardOperationState = DashboardOperationState(),
  val recentActivity: List<DashboardActivityItem> = emptyList(),
  val telemetryActivity: List<DashboardActivityItem> = emptyList()
) {
  val phoneUiPolicy
    get() = phoneAutomationUiPolicy(phoneAutomation)

  val visibleActivity: List<DashboardActivityItem>
    get() = (recentActivity + telemetryActivity)
      .sortedByDescending(DashboardActivityItem::recordedAtMillis)
      .distinctBy { "${it.recordedAtMillis}:${it.title}:${it.detail}" }
      .take(20)
}

internal sealed interface DashboardAction {
  data class SetPhoneAutomation(val enabled: Boolean) : DashboardAction
  data class SetMaintainCellMapper(val enabled: Boolean) : DashboardAction
  data class SetReturnToOrchestrator(val enabled: Boolean) : DashboardAction
  data class SetDispatchInterval(val interval: PhoneAutomationDispatchInterval) : DashboardAction
  data class SetTicketService(val enabled: Boolean) : DashboardAction
  data class SetTouchBrightness(val enabled: Boolean) : DashboardAction
  data class SetCpuFrequency(val enabled: Boolean) : DashboardAction
  data class SetCpuCap(val cluster: CpuFrequencyCluster, val frequencyKHz: Long) : DashboardAction
  data class SetGpuCap(val frequencyKHz: Long) : DashboardAction
  data object RestoreStockFrequencies : DashboardAction
  data object OpenAccessibilitySettings : DashboardAction
  data object OpenNotificationAccessSettings : DashboardAction
  data object OpenBatterySettings : DashboardAction
  data object OpenExactAlarmSettings : DashboardAction
  data object Bootstrap : DashboardAction
  data object StartAll : DashboardAction
  data object StopAll : DashboardAction
  data object RunHealthCheck : DashboardAction
  data object SyncDdns : DashboardAction
  data object RunCleanup : DashboardAction
  data object ExportSupportBundle : DashboardAction
}

internal sealed interface DashboardEffect {
  data object OpenAccessibilitySettings : DashboardEffect
  data object OpenNotificationAccessSettings : DashboardEffect
  data object OpenBatterySettings : DashboardEffect
  data object OpenExactAlarmSettings : DashboardEffect
  data class ShareSupportBundle(val uri: Uri) : DashboardEffect
}

internal fun defaultDashboardModules(): List<DashboardModuleStatus> = MODULE_DEFINITIONS.map { definition ->
  DashboardModuleStatus(
    id = definition.id,
    label = definition.label,
    healthy = null,
    status = "Unknown"
  )
}

internal fun dashboardModules(snapshot: HealthSnapshot?): List<DashboardModuleStatus> {
  if (snapshot == null) return defaultDashboardModules()
  return MODULE_DEFINITIONS.map { definition ->
    val explicit = snapshot.moduleHealth[definition.id]
    val fallback = definition.fallback(snapshot)
    val status = explicit?.status.orEmpty().ifBlank {
      when (fallback) {
        true -> "Healthy"
        false -> "Unavailable"
      }
    }
    DashboardModuleStatus(
      id = definition.id,
      label = definition.label,
      healthy = explicit?.healthy ?: fallback,
      status = status.replace('_', ' ').replaceFirstChar { it.uppercase() },
      details = explicit?.details.orEmpty()
    )
  }
}

internal fun dashboardHealthLevel(
  snapshot: HealthSnapshot?,
  nowMillis: Long = System.currentTimeMillis(),
  staleAfterMillis: Long = HEALTH_STALE_AFTER_MILLIS
): DashboardHealthLevel {
  if (snapshot == null || snapshot.generatedEpochSeconds <= 0L) return DashboardHealthLevel.UNKNOWN
  if (nowMillis - snapshot.generatedEpochSeconds * 1_000L > staleAfterMillis) return DashboardHealthLevel.STALE

  val modules = dashboardModules(snapshot).filterNot { it.status.equals("disabled", ignoreCase = true) }
  val failed = modules.any { module ->
    !module.healthy.orFalse() && FAILURE_STATUS_WORDS.any { word -> module.status.contains(word, ignoreCase = true) }
  }
  if (failed) return DashboardHealthLevel.FAILED
  if (modules.any { it.healthy == false }) return DashboardHealthLevel.DEGRADED
  return DashboardHealthLevel.HEALTHY
}

internal fun nearestFrequencyIndex(values: List<Long>, desired: Long): Int {
  val exact = values.indexOf(desired)
  if (exact >= 0) return exact
  val nearestLower = values.indexOfLast { it <= desired }
  return if (nearestLower >= 0) nearestLower else values.lastIndex.coerceAtLeast(0)
}

private fun Boolean?.orFalse(): Boolean = this == true

private data class DashboardModuleDefinition(
  val id: String,
  val label: String,
  val fallback: (HealthSnapshot) -> Boolean
)

private val MODULE_DEFINITIONS = listOf(
  DashboardModuleDefinition("root", "Root access") { it.rootGranted },
  DashboardModuleDefinition("dns", "DNS") { it.dnsHealthy },
  DashboardModuleDefinition("remote", "Public remote") { it.remoteHealthy },
  DashboardModuleDefinition("ssh", "SSH") { it.sshHealthy },
  DashboardModuleDefinition("vpn", "VPN") { it.vpnHealthy },
  DashboardModuleDefinition("management", "Management") { it.managementHealthy },
  DashboardModuleDefinition("management_auth", "Management sign-in") { it.managementAuthHealthy },
  DashboardModuleDefinition("train_bot", "Train bot") { it.trainBotHealthy },
  DashboardModuleDefinition("satiksme_bot", "Satiksme bot") { it.satiksmeBotHealthy },
  DashboardModuleDefinition("site_notifier", "Site notifier") { it.siteNotifierHealthy },
  DashboardModuleDefinition("subscription_bot", "Subscription bot") { it.subscriptionBotHealthy },
  DashboardModuleDefinition("cpu_frequency", "CPU and GPU limits") {
    it.moduleHealth["cpu_frequency"]?.healthy ?: false
  },
  DashboardModuleDefinition("ticket_screen", "Ticket service") {
    it.moduleHealth["ticket_screen"]?.healthy ?: false
  },
  DashboardModuleDefinition("ddns", "DDNS") { it.ddnsHealthy },
  DashboardModuleDefinition("runtime_cleanup", "Runtime cleanup") {
    it.moduleHealth["runtime_cleanup"]?.healthy ?: false
  },
  DashboardModuleDefinition("supervisor_loop", "Supervisor loop") { it.supervisorLoopHealthy },
  DashboardModuleDefinition("deploy", "Deployment") { it.deployHealthy },
  DashboardModuleDefinition("supervisor", "Supervisor") { it.supervisorHealthy }
)

private val FAILURE_STATUS_WORDS = listOf("failed", "error", "crash", "unhealthy")
private const val HEALTH_STALE_AFTER_MILLIS = 2 * 60 * 1_000L
