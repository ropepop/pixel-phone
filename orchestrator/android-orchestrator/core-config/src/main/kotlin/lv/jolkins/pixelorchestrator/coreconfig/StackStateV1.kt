package lv.jolkins.pixelorchestrator.coreconfig

import kotlinx.serialization.Serializable

@Serializable
data class StackStateV1(
  val schema: Int = 1,
  val bootPath: BootPath = BootPath.UNKNOWN,
  val lastSuccessfulBootEpochSeconds: Long = 0,
  val supervisorLoopHeartbeatEpochSeconds: Long = 0,
  val lastNetworkFingerprint: String = "",
  val lastObservedPublicIpv4: String = "",
  val networkConvergenceUntilEpochSeconds: Long = 0,
  val lastDirectPublicFailureEpochSeconds: Long = 0,
  val services: Map<String, ServiceRuntimeState> = defaultServices(),
  val moduleState: Map<String, ModuleRuntimeState> = defaultModuleState(),
  val lastHealthSnapshot: HealthSnapshot = HealthSnapshot(),
  val operationLog: List<OperationEvent> = emptyList()
) {
  companion object {
    fun defaultServices(): Map<String, ServiceRuntimeState> = mapOf(
      "dns" to ServiceRuntimeState(),
      "ssh" to ServiceRuntimeState(),
      "vpn" to ServiceRuntimeState(),
      "cpu_frequency" to ServiceRuntimeState(),
      "train_bot" to ServiceRuntimeState(),
      "satiksme_bot" to ServiceRuntimeState(),
      "site_notifier" to ServiceRuntimeState(),
      "subscription_bot" to ServiceRuntimeState(),
      "ddns" to ServiceRuntimeState(),
      "remote" to ServiceRuntimeState(),
      "management" to ServiceRuntimeState(),
      "runtime_cleanup" to ServiceRuntimeState(),
      "supervisor" to ServiceRuntimeState(status = ServiceStatus.STARTING)
    )

    fun defaultModuleState(): Map<String, ModuleRuntimeState> = mapOf(
      "dns" to ModuleRuntimeState(),
      "ssh" to ModuleRuntimeState(),
      "vpn" to ModuleRuntimeState(),
      "cpu_frequency" to ModuleRuntimeState(),
      "train_bot" to ModuleRuntimeState(),
      "satiksme_bot" to ModuleRuntimeState(),
      "site_notifier" to ModuleRuntimeState(),
      "subscription_bot" to ModuleRuntimeState(),
      "ddns" to ModuleRuntimeState(),
      "remote" to ModuleRuntimeState(),
      "management" to ModuleRuntimeState(),
      "runtime_cleanup" to ModuleRuntimeState(),
      "supervisor" to ModuleRuntimeState()
    )
  }
}

@Serializable
enum class ServiceStatus {
  STOPPED,
  STARTING,
  RUNNING,
  DEGRADED,
  CRASH_LOOP
}

@Serializable
enum class BootPath {
  UNKNOWN,
  ANDROID_BOOT,
  PROVIDER_HOOK
}

@Serializable
data class ServiceRuntimeState(
  val status: ServiceStatus = ServiceStatus.STOPPED,
  val restartCount: Int = 0,
  val lastFailureReason: String = "",
  val lastStartedEpochSeconds: Long = 0,
  val lastHealthyEpochSeconds: Long = 0
)

@Serializable
data class HealthSnapshot(
  val generatedEpochSeconds: Long = 0,
  val rootGranted: Boolean = false,
  val dnsHealthy: Boolean = false,
  val remoteHealthy: Boolean = false,
  val managementHealthy: Boolean = false,
  val sshHealthy: Boolean = false,
  val vpnHealthy: Boolean = false,
  val trainBotHealthy: Boolean = false,
  val satiksmeBotHealthy: Boolean = false,
  val siteNotifierHealthy: Boolean = false,
  val subscriptionBotHealthy: Boolean = false,
  val ddnsHealthy: Boolean = false,
  val supervisorLoopHealthy: Boolean = false,
  val managementAuthHealthy: Boolean = false,
  val deployHealthy: Boolean = false,
  val supervisorHealthy: Boolean = false,
  val moduleHealth: Map<String, ModuleHealthState> = emptyMap(),
  val evidence: Map<String, String> = emptyMap()
)

@Serializable
data class ModuleRuntimeState(
  val status: String = "unknown",
  val healthy: Boolean = false,
  val lastUpdatedEpochSeconds: Long = 0,
  val details: Map<String, String> = emptyMap()
)

@Serializable
data class ModuleHealthState(
  val healthy: Boolean = false,
  val status: String = "unknown",
  val details: Map<String, String> = emptyMap()
)

@Serializable
data class OperationEvent(
  val epochSeconds: Long,
  val component: String,
  val action: String,
  val success: Boolean,
  val details: String
)
