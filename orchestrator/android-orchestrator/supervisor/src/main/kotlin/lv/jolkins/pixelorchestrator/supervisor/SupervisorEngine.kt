package lv.jolkins.pixelorchestrator.supervisor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.ModuleRuntimeState
import lv.jolkins.pixelorchestrator.coreconfig.OperationEvent
import lv.jolkins.pixelorchestrator.coreconfig.ServiceRuntimeState
import lv.jolkins.pixelorchestrator.coreconfig.ServiceStatus
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStore
import lv.jolkins.pixelorchestrator.health.HealthScope
import lv.jolkins.pixelorchestrator.health.RuntimeHealthChecker

class SupervisorEngine(
  private val configProvider: () -> StackConfigV1,
  private val stateStore: StackStore,
  private val healthChecker: RuntimeHealthChecker,
  private val components: Map<String, ComponentController>
) : SupervisorControl {
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var loopJob: Job? = null
  private val backoffs = mutableMapOf<String, BackoffPolicy>()
  private val unhealthyCounts = mutableMapOf<String, Int>()
  private var managementPendingSshRestart = false
  private var managementRecoveryCooldownUntilEpochSeconds = 0L
  private var managementAuthRepairCooldownUntilEpochSeconds = 0L

  override suspend fun startAll() {
    val config = configProvider()
    var state = stateStore.loadStateOrDefault().withSupervisorStatus(ServiceStatus.STARTING)

    components.values.forEach { controller ->
      if (!isComponentEnabled(controller.name, config)) {
        state = state
          .appendEvent(controller.name, "start_skipped", true, "disabled")
          .markComponent(controller.name, ServiceStatus.STOPPED, "disabled", countAsRestart = false)
        return@forEach
      }
      if (controller is AutoStartAwareComponentController && !controller.shouldAutoStart()) {
        state = state
          .appendEvent(controller.name, "start_skipped", true, "disabled")
          .markComponent(controller.name, ServiceStatus.STOPPED, "disabled", countAsRestart = false)
        return@forEach
      }

      val ok = controller.start()
      state = state
        .appendEvent(controller.name, "start", ok, "startAll")
        .markComponent(controller.name, if (ok) ServiceStatus.RUNNING else ServiceStatus.DEGRADED, if (ok) "" else "start failed")

      ensureBackoffPolicy(controller.name, config)
    }

    stateStore.saveState(state)

    launchLoop(forceRestart = true)
  }

  override suspend fun resumeSupervision() {
    val config = configProvider()
    val state = stateStore.loadStateOrDefault()
    val supervisorStatus = state.services["supervisor"]?.status
    if (supervisorStatus == null || supervisorStatus == ServiceStatus.STOPPED) {
      return
    }

    components.keys.forEach { component ->
      val controller = components[component]
      val shouldAutoStart = when (controller) {
        is AutoStartAwareComponentController -> controller.shouldAutoStart()
        else -> true
      }
      if (controller != null && isComponentEnabled(component, config) && shouldAutoStart) {
        ensureBackoffPolicy(component, config)
      }
    }

    if (loopJob?.isActive == true) {
      return
    }

    stateStore.saveState(
      state
        .withSupervisorStatus(ServiceStatus.STARTING)
        .appendEvent("supervisor", "resume", true, "resume loop")
    )
    launchLoop(forceRestart = false)
  }

  override suspend fun stopAll() {
    loopJob?.cancel()
    loopJob = null
    managementPendingSshRestart = false
    managementRecoveryCooldownUntilEpochSeconds = 0L
    managementAuthRepairCooldownUntilEpochSeconds = 0L
    var state = stateStore.loadStateOrDefault()
    components.values.forEach { controller ->
      val ok = controller.stop()
      state = state
        .appendEvent(controller.name, "stop", ok, "stopAll")
        .markComponent(controller.name, ServiceStatus.STOPPED, if (ok) "" else "stop failed", countAsRestart = false)
    }

    stateStore.saveState(state.withSupervisorStatus(ServiceStatus.STOPPED))
  }

  override suspend fun startComponent(component: String) {
    val controller = components[component] ?: return
    ensureBackoffPolicy(component)
    val ok = controller.start()
    val state = stateStore.loadStateOrDefault()
      .appendEvent(component, "start", ok, "manual start component")
      .markComponent(component, if (ok) ServiceStatus.RUNNING else ServiceStatus.DEGRADED, if (ok) "" else "start failed")
    stateStore.saveState(state)
  }

  override suspend fun stopComponent(component: String) {
    val controller = components[component] ?: return
    val ok = controller.stop()
    val state = stateStore.loadStateOrDefault()
      .appendEvent(component, "stop", ok, "manual stop component")
      .markComponent(component, ServiceStatus.STOPPED, if (ok) "" else "stop failed", countAsRestart = false)
    stateStore.saveState(state)
  }

  override suspend fun restart(component: String) {
    val controller = components[component] ?: return
    ensureBackoffPolicy(component)
    controller.stop()
    val ok = controller.start()

    val state = stateStore.loadStateOrDefault()
      .appendEvent(component, "restart", ok, "manual restart")
      .markComponent(component, if (ok) ServiceStatus.RUNNING else ServiceStatus.DEGRADED, if (ok) "" else "restart failed")

    stateStore.saveState(state)
  }

  override suspend fun runHealthCheck(scope: HealthScope): HealthSnapshot {
    val config = configProvider()
    var snapshot = augmentComponentHealth(healthChecker.check(config), config)
    val trainBotTunnelFailureCount =
      nextTrainBotTunnelFailureCount(
        trainBotHealthy = snapshot.trainBotHealthy,
        tunnelFailure = snapshot.trainBotTunnelFailure()
      )
    val satiksmeBotTunnelFailureCount =
      nextSatiksmeBotTunnelFailureCount(
        satiksmeBotHealthy = snapshot.satiksmeBotHealthy,
        tunnelFailure = snapshot.satiksmeBotTunnelFailure()
      )
    snapshot = snapshot
      .withTrainBotTunnelFailureCount(trainBotTunnelFailureCount)
      .withSatiksmeBotTunnelFailureCount(satiksmeBotTunnelFailureCount)
    val state = stateStore.loadStateOrDefault()
      .withModuleHealth(snapshot)
      .copy(lastHealthSnapshot = snapshot)
      .appendEvent("health", "check", true, "scope=$scope")

    stateStore.saveState(state)
    return snapshot
  }

  override suspend fun syncDdnsNow() {
    val controller = components["ddns"] ?: return
    val ok = controller.start()
    val state = stateStore.loadStateOrDefault()
      .appendEvent("ddns", "sync_now", ok, "manual")
      .markComponent("ddns", if (ok) ServiceStatus.RUNNING else ServiceStatus.DEGRADED, if (ok) "" else "sync failed")

    stateStore.saveState(state)
  }

  private suspend fun runLoop() {
    while (scope.isActive) {
      val config = configProvider()
      var snapshot = augmentComponentHealth(healthChecker.check(config), config)
      val remoteEnabled = config.remote.dohEnabled || config.remote.dotEnabled
      val remoteEscalationEnabled =
        remoteEnabled && config.supervision.enforceRemoteListeners && config.remote.watchdogEscalateRuntimeRestart
      val dnsRestartHealthy = snapshot.dnsHealthy && (!remoteEscalationEnabled || snapshot.remoteHealthy)
      val trainBotTunnelFailureCount =
        nextTrainBotTunnelFailureCount(
          trainBotHealthy = snapshot.trainBotHealthy,
          tunnelFailure = snapshot.trainBotTunnelFailure()
        )
      val satiksmeBotTunnelFailureCount =
        nextSatiksmeBotTunnelFailureCount(
          satiksmeBotHealthy = snapshot.satiksmeBotHealthy,
          tunnelFailure = snapshot.satiksmeBotTunnelFailure()
        )
      snapshot = snapshot
        .withTrainBotTunnelFailureCount(trainBotTunnelFailureCount)
        .withSatiksmeBotTunnelFailureCount(satiksmeBotTunnelFailureCount)
      val previousState = stateStore.loadStateOrDefault()
      val initialState = previousState
        .withModuleHealth(snapshot)
        .copy(lastHealthSnapshot = snapshot)
      val networkObservation = observeNetworkState(previousState, initialState, snapshot, config)
      var state = networkObservation.state
        .withSupervisorStatus(ServiceStatus.RUNNING)

      val dnsOutcome =
        if (isComponentEnabled("dns", config)) restartDnsIfUnhealthy(snapshot, state, config, remoteEscalationEnabled)
        else RestartOutcome(state)
      state = dnsOutcome.state

      val vpnRequired = config.vpn.enabled || (config.modules["vpn"]?.enabled ?: false)
      val managementOutcome = recoverManagementPath(snapshot, state, config, vpnRequired, networkObservation.convergenceActive)
      state = managementOutcome.state

      val managementAuthOutcome = recoverManagementAuth(snapshot, state, config)
      state = managementAuthOutcome.state

      val sshOutcome =
        if (!isComponentEnabled("ssh", config) || managementEnabled(snapshot)) RestartOutcome(state)
        else restartIfUnhealthy("ssh", snapshot.sshHealthy, state)
      state = sshOutcome.state

      val vpnOutcome =
        if (!isComponentEnabled("vpn", config) || managementEnabled(snapshot)) RestartOutcome(state)
        else restartIfUnhealthy("vpn", if (vpnRequired) snapshot.vpnHealthy else true, state)
      state = vpnOutcome.state

      state = observeAppManagedComponentHealth(
        state = state,
        snapshot = snapshot,
        config = config,
        name = "cpu_frequency"
      )

      val trainOutcome = restartTrainBotIfUnhealthy(snapshot, state, config)
      state = trainOutcome.state

      val satiksmeOutcome = restartSatiksmeBotIfUnhealthy(snapshot, state, config)
      state = satiksmeOutcome.state

      val notifierOutcome =
        if (isComponentEnabled("site_notifier", config)) restartIfUnhealthy("site_notifier", snapshot.siteNotifierHealthy, state)
        else RestartOutcome(state)
      state = notifierOutcome.state

      val subscriptionEnabled = isComponentEnabled("subscription_bot", config)
      val subscriptionOutcome =
        restartIfUnhealthy("subscription_bot", if (subscriptionEnabled) snapshot.subscriptionBotHealthy else true, state)
      state = subscriptionOutcome.state

      val genericOutcome = restartGenericScriptComponents(state, config)
      state = genericOutcome.state

      state = syncDdnsIfDue(state, config, snapshot, networkObservation)
      state = observeRemoteHealth(state, snapshot)
      state = observeManagementHealth(state, snapshot)
      stateStore.saveState(state.withSupervisorLoopHeartbeat())

      val restartDelayMillis = listOf(
        dnsOutcome.delayMillis,
        managementOutcome.delayMillis,
        managementAuthOutcome.delayMillis,
        sshOutcome.delayMillis,
        vpnOutcome.delayMillis,
        trainOutcome.delayMillis,
        satiksmeOutcome.delayMillis,
        notifierOutcome.delayMillis,
        subscriptionOutcome.delayMillis,
        genericOutcome.delayMillis
      ).maxOrNull() ?: 0L
      if (restartDelayMillis > 0L) {
        delay(restartDelayMillis)
      }
      val pollSeconds = if ((System.currentTimeMillis() / 1000) < state.networkConvergenceUntilEpochSeconds) {
        config.supervision.networkConvergencePollSeconds.coerceAtLeast(1)
      } else {
        config.supervision.healthPollSeconds.coerceAtLeast(1)
      }
      delay(pollSeconds * 1000L)
    }
  }

  private suspend fun restartTrainBotIfUnhealthy(
    snapshot: HealthSnapshot,
    state: StackStateV1,
    config: StackConfigV1
  ): RestartOutcome {
    if (!isComponentEnabled("train_bot", config)) {
      unhealthyCounts["train_bot"] = 0
      backoffs["train_bot"]?.reset()
      return RestartOutcome(state)
    }
    if (snapshot.trainBotHealthy) {
      unhealthyCounts["train_bot"] = 0
      backoffs["train_bot"]?.reset()
      return RestartOutcome(state)
    }

    val failureCount = unhealthyCounts["train_bot"] ?: 0
    if (snapshot.trainBotTunnelFailure()) {
      val threshold = config.supervision.unhealthyFails.coerceAtLeast(1)
      if (failureCount < threshold) {
        return RestartOutcome(
          state = state
            .appendEvent("train_bot", "health_unhealthy", false, "tunnel/public probe failed count=$failureCount threshold=$threshold")
            .markComponent(
              "train_bot",
              ServiceStatus.DEGRADED,
              "tunnel/public probe failed ($failureCount/$threshold)",
              countAsRestart = false
            )
        )
      }
    }

    return restartIfUnhealthy("train_bot", false, state)
  }

  private suspend fun restartSatiksmeBotIfUnhealthy(
    snapshot: HealthSnapshot,
    state: StackStateV1,
    config: StackConfigV1
  ): RestartOutcome {
    if (!isComponentEnabled("satiksme_bot", config)) {
      unhealthyCounts["satiksme_bot"] = 0
      backoffs["satiksme_bot"]?.reset()
      return RestartOutcome(state)
    }
    if (snapshot.satiksmeBotHealthy) {
      unhealthyCounts["satiksme_bot"] = 0
      backoffs["satiksme_bot"]?.reset()
      return RestartOutcome(state)
    }

    val failureCount = unhealthyCounts["satiksme_bot"] ?: 0
    if (snapshot.satiksmeBotTunnelFailure()) {
      val threshold = config.supervision.unhealthyFails.coerceAtLeast(1)
      if (failureCount < threshold) {
        return RestartOutcome(
          state = state
            .appendEvent("satiksme_bot", "health_unhealthy", false, "tunnel/public probe failed count=$failureCount threshold=$threshold")
            .markComponent(
              "satiksme_bot",
              ServiceStatus.DEGRADED,
              "tunnel/public probe failed ($failureCount/$threshold)",
              countAsRestart = false
            )
        )
      }
    }

    return restartIfUnhealthy("satiksme_bot", false, state)
  }

  private suspend fun restartGenericScriptComponents(
    initialState: StackStateV1,
    config: StackConfigV1
  ): RestartOutcome {
    var state = initialState
    var maxDelayMillis = 0L
    components.keys
      .filter { name -> name !in EXPLICITLY_MANAGED_COMPONENT_NAMES }
      .filter { name -> isComponentEnabled(name, config) }
      .forEach { name ->
        val controller = components[name] ?: return@forEach
        if (controller is AutoStartAwareComponentController && !controller.shouldAutoStart()) {
          backoffs[name]?.reset()
          unhealthyCounts[name] = 0
          state = state.markComponent(name, ServiceStatus.STOPPED, "auto-start disabled", countAsRestart = false)
          return@forEach
        }
        val healthy = controller.health()
        val outcome = restartIfUnhealthy(name, healthy, state)
        state = outcome.state
        maxDelayMillis = maxOf(maxDelayMillis, outcome.delayMillis)
      }
    return RestartOutcome(state = state, delayMillis = maxDelayMillis)
  }

  private suspend fun recoverManagementPath(
    snapshot: HealthSnapshot,
    state: StackStateV1,
    config: StackConfigV1,
    vpnRequired: Boolean,
    convergenceActive: Boolean
  ): RestartOutcome {
    if (!managementEnabled(snapshot)) {
      unhealthyCounts["management"] = 0
      managementPendingSshRestart = false
      managementRecoveryCooldownUntilEpochSeconds = 0L
      managementAuthRepairCooldownUntilEpochSeconds = 0L
      return RestartOutcome(state)
    }

    val reason = managementReason(snapshot)
    val nowEpoch = System.currentTimeMillis() / 1000

    if (managementPendingSshRestart && snapshot.vpnHealthy) {
      managementPendingSshRestart = false
      unhealthyCounts["management"] = 0
      managementRecoveryCooldownUntilEpochSeconds = nowEpoch + config.supervision.managementRecoveryCooldownSeconds.coerceAtLeast(0)
      return restartComponentForManagement(
        target = "ssh",
        state = state,
        reason = reason,
        detail = "coordinated recovery step=ssh"
      )
    }

    if (snapshot.managementHealthy) {
      unhealthyCounts["management"] = 0
      managementPendingSshRestart = false
      managementRecoveryCooldownUntilEpochSeconds = 0L
      return RestartOutcome(state)
    }

    val immediateVpnRecovery =
      !vpnRequired ||
        !snapshot.vpnHealthy ||
        (convergenceActive && reason in setOf("vpn_unhealthy", "tailnet_ip_missing"))

    if (immediateVpnRecovery) {
      managementPendingSshRestart = false
      unhealthyCounts["management"] = 0
      return restartComponentForManagement(
        target = "vpn",
        state = state,
        reason = reason,
        detail = "vpn-first recovery"
      )
    }

    if (!snapshot.sshHealthy) {
      managementPendingSshRestart = false
      unhealthyCounts["management"] = 0
      return restartComponentForManagement(
        target = "ssh",
        state = state,
        reason = reason,
        detail = "ssh recovery"
      )
    }

    if (nowEpoch < managementRecoveryCooldownUntilEpochSeconds) {
      return RestartOutcome(
        state = state.appendEvent(
          "management",
          "health_unhealthy",
          false,
          "reason=$reason cooldown_remaining=${managementRecoveryCooldownUntilEpochSeconds - nowEpoch}s"
        )
      )
    }

    val nextFailureCount = (unhealthyCounts["management"] ?: 0) + 1
    unhealthyCounts["management"] = nextFailureCount
    val threshold = config.supervision.managementUnhealthyFails.coerceAtLeast(1)
    if (nextFailureCount < threshold) {
      return RestartOutcome(
        state = state
          .appendEvent("management", "health_unhealthy", false, "reason=$reason count=$nextFailureCount threshold=$threshold")
          .markComponent(
            "management",
            ServiceStatus.DEGRADED,
            "$reason ($nextFailureCount/$threshold)",
            countAsRestart = false
          )
      )
    }

    unhealthyCounts["management"] = 0
    managementPendingSshRestart = true
    return restartComponentForManagement(
      target = "vpn",
      state = state,
      reason = reason,
      detail = "coordinated recovery step=vpn"
    )
  }

  private suspend fun recoverManagementAuth(
    snapshot: HealthSnapshot,
    state: StackStateV1,
    config: StackConfigV1
  ): RestartOutcome {
    if (!managementEnabled(snapshot)) {
      managementAuthRepairCooldownUntilEpochSeconds = 0L
      return RestartOutcome(state)
    }

    if (snapshot.managementAuthHealthy || !snapshot.managementHealthy) {
      managementAuthRepairCooldownUntilEpochSeconds = 0L
      return RestartOutcome(state)
    }

    if (!snapshot.sshHealthy) {
      return RestartOutcome(state)
    }

    val nowEpoch = System.currentTimeMillis() / 1000
    if (nowEpoch < managementAuthRepairCooldownUntilEpochSeconds) {
      return RestartOutcome(
        state = state.appendEvent(
          "management",
          "auth_repair_wait",
          false,
          "reason=${managementAuthReason(snapshot)} cooldown_remaining=${managementAuthRepairCooldownUntilEpochSeconds - nowEpoch}s"
        )
      )
    }

    managementAuthRepairCooldownUntilEpochSeconds =
      nowEpoch + config.supervision.managementRecoveryCooldownSeconds.coerceAtLeast(0)
    return restartComponentForManagement(
      target = "ssh",
      state = state,
      reason = managementAuthReason(snapshot),
      detail = "auth drift repair"
    )
  }

  private suspend fun restartDnsIfUnhealthy(
    snapshot: HealthSnapshot,
    state: StackStateV1,
    config: StackConfigV1,
    remoteEscalationEnabled: Boolean
  ): RestartOutcome {
    val remotePublicFailure = remoteEscalationEnabled && snapshot.dnsHealthy && remotePublicContractFailed(snapshot)
    if (!remotePublicFailure) {
      unhealthyCounts["dns_remote"] = 0
      if (snapshot.dnsHealthy) {
        backoffs["dns"]?.reset()
        return RestartOutcome(state)
      }
      return restartIfUnhealthy("dns", false, state)
    }

    val nextFailureCount = (unhealthyCounts["dns_remote"] ?: 0) + 1
    unhealthyCounts["dns_remote"] = nextFailureCount
    val threshold = config.supervision.unhealthyFails.coerceAtLeast(1)
    if (nextFailureCount < threshold) {
      return RestartOutcome(
        state = state
          .appendEvent("dns", "health_unhealthy", false, "remote/public probe failed count=$nextFailureCount threshold=$threshold")
          .markComponent(
            "dns",
            ServiceStatus.DEGRADED,
            "remote/public probe failed ($nextFailureCount/$threshold)",
            countAsRestart = false
          )
      )
    }

    unhealthyCounts["dns_remote"] = 0
    return restartIfUnhealthy("dns", false, state)
  }

  private fun remotePublicContractFailed(snapshot: HealthSnapshot): Boolean {
    val evidence = snapshot.evidence
    if (evidence["remote_public_probe_available"] != "true") return false

    val publicRootCode = evidence["remote_public_root_code"].orEmpty()
    val publicTokenizedCode = evidence["remote_public_doh_tokenized_code"].orEmpty()
    val publicBareCode = evidence["remote_public_doh_bare_code"].orEmpty()
    val publicIdentityCode = evidence["remote_public_identity_inject_code"].orEmpty()
    val dohEndpointMode = evidence["doh_endpoint_mode"].orEmpty()
    val rootHealthy = evidence["remote_public_root_healthy"] == "true"
    val dohContractHealthy = evidence["remote_public_doh_contract"] == "true"
    val identityRequired = evidence["identity_frontend_required"] == "true"
    val publicIdentityHealthy = evidence["remote_public_identity_frontend_healthy"] == "true"

    val rootInconclusive = publicProbeInconclusive(publicRootCode)
    val dohInconclusive = when (dohEndpointMode) {
      "tokenized", "dual" -> publicProbeInconclusive(publicTokenizedCode) || publicProbeInconclusive(publicBareCode)
      "native" -> publicProbeInconclusive(publicBareCode)
      else -> publicProbeInconclusive(publicTokenizedCode) && publicProbeInconclusive(publicBareCode)
    }
    val identityInconclusive = publicProbeInconclusive(publicIdentityCode)

    return (!rootInconclusive && !rootHealthy) ||
      (!dohInconclusive && !dohContractHealthy) ||
      (identityRequired && !identityInconclusive && !publicIdentityHealthy)
  }

  private fun publicProbeInconclusive(code: String): Boolean = code == "000"

  private fun observeNetworkState(
    previousState: StackStateV1,
    state: StackStateV1,
    snapshot: HealthSnapshot,
    config: StackConfigV1
  ): NetworkObservation {
    val nowEpoch = System.currentTimeMillis() / 1000
    val currentFingerprint = normalizedStateValue(snapshot.evidence["network_fingerprint"])
    val currentPublicIpv4 = normalizedStateValue(snapshot.evidence["network_public_ipv4_candidate"])
    val previousFingerprint = normalizedStateValue(state.lastNetworkFingerprint)
    val previousPublicIpv4 = normalizedStateValue(state.lastObservedPublicIpv4)
    val fingerprintChanged =
      currentFingerprint.isNotBlank() &&
        previousFingerprint.isNotBlank() &&
        currentFingerprint != previousFingerprint
    val publicIpv4Changed =
      currentPublicIpv4.isNotBlank() &&
        previousPublicIpv4.isNotBlank() &&
        currentPublicIpv4 != previousPublicIpv4
    val previousDirectPublicTransitioning = directPublicTransitioning(previousState.lastHealthSnapshot)

    var nextState = state
    if (fingerprintChanged) {
      val convergenceUntil =
        nowEpoch + config.supervision.networkConvergenceWindowSeconds.coerceAtLeast(0)
      nextState = nextState
        .copy(networkConvergenceUntilEpochSeconds = convergenceUntil)
        .appendEvent(
          "supervisor",
          "network_change",
          true,
          "transport=${snapshot.evidence["network_active_transport"].orEmpty().ifBlank { "unknown" }} public_ipv4=${if (currentPublicIpv4.isBlank()) "unknown" else currentPublicIpv4}"
        )
    }

    val directPublicFailed = directPublicPathFailed(snapshot)
    val directPublicInTransition = directPublicTransitioning(snapshot)
    nextState = when {
      directPublicFailed && nextState.lastDirectPublicFailureEpochSeconds == 0L ->
        nextState
          .copy(lastDirectPublicFailureEpochSeconds = nowEpoch)
          .appendEvent("remote", "direct_public_degraded", false, "published_ip=${snapshot.evidence["ddns_published_ipv4"].orEmpty()} current_ip=${snapshot.evidence["network_public_ipv4_candidate"].orEmpty()}")
      directPublicFailed ->
        nextState.copy(lastDirectPublicFailureEpochSeconds = nowEpoch)
      !directPublicFailed && nextState.lastDirectPublicFailureEpochSeconds != 0L ->
        nextState
          .copy(lastDirectPublicFailureEpochSeconds = 0L)
          .appendEvent("remote", "direct_public_recovered", true, "published_ip=${snapshot.evidence["ddns_published_ipv4"].orEmpty()} current_ip=${snapshot.evidence["network_public_ipv4_candidate"].orEmpty()}")
      else -> nextState
    }
    nextState = when {
      directPublicFailed -> nextState
      directPublicInTransition && !previousDirectPublicTransitioning ->
        nextState.appendEvent(
          "remote",
          "direct_public_transition",
          true,
          "reason=${snapshot.evidence["direct_public_transition_reason"].orEmpty()} published_ip=${snapshot.evidence["ddns_published_ipv4"].orEmpty()} current_ip=${snapshot.evidence["network_public_ipv4_candidate"].orEmpty()}"
        )
      !directPublicInTransition && previousDirectPublicTransitioning ->
        nextState.appendEvent(
          "remote",
          "direct_public_transition_recovered",
          true,
          "published_ip=${snapshot.evidence["ddns_published_ipv4"].orEmpty()} current_ip=${snapshot.evidence["network_public_ipv4_candidate"].orEmpty()}"
        )
      else -> nextState
    }

    nextState = nextState.copy(
      lastNetworkFingerprint = currentFingerprint.ifBlank { state.lastNetworkFingerprint },
      lastObservedPublicIpv4 = currentPublicIpv4.ifBlank { state.lastObservedPublicIpv4 }
    )

    return NetworkObservation(
      state = nextState,
      fingerprintChanged = fingerprintChanged,
      publicIpv4Changed = publicIpv4Changed,
      convergenceActive = nowEpoch < nextState.networkConvergenceUntilEpochSeconds
    )
  }

  private fun directPublicPathFailed(snapshot: HealthSnapshot): Boolean {
    return snapshot.evidence["direct_public_path_healthy"] == "false"
  }

  private fun directPublicTransitioning(snapshot: HealthSnapshot): Boolean {
    return snapshot.evidence["direct_public_transitioning"] == "true"
  }

  private fun normalizedStateValue(value: String?): String {
    return value.orEmpty().trim().takeUnless { it.equals("none", ignoreCase = true) } ?: ""
  }

  private fun nextTrainBotTunnelFailureCount(trainBotHealthy: Boolean, tunnelFailure: Boolean): Int {
    if (trainBotHealthy || !tunnelFailure) {
      unhealthyCounts["train_bot"] = 0
      return 0
    }
    val next = (unhealthyCounts["train_bot"] ?: 0) + 1
    unhealthyCounts["train_bot"] = next
    return next
  }

  private fun nextSatiksmeBotTunnelFailureCount(satiksmeBotHealthy: Boolean, tunnelFailure: Boolean): Int {
    if (satiksmeBotHealthy || !tunnelFailure) {
      unhealthyCounts["satiksme_bot"] = 0
      return 0
    }
    val next = (unhealthyCounts["satiksme_bot"] ?: 0) + 1
    unhealthyCounts["satiksme_bot"] = next
    return next
  }

  private suspend fun restartIfUnhealthy(name: String, healthy: Boolean, state: StackStateV1): RestartOutcome {
    val controller = components[name] ?: return RestartOutcome(state)
    if (healthy) {
      backoffs[name]?.reset()
      return RestartOutcome(state)
    }

    if (controller.health()) {
      backoffs[name]?.reset()
      return RestartOutcome(
        state = state
          .appendEvent(name, "auto_restart_skipped", true, "recovered_before_restart")
          .markComponent(name, ServiceStatus.RUNNING, "", countAsRestart = false)
      )
    }

    val policy = backoffs[name] ?: return RestartOutcome(state)
    val decision = policy.recordRestart()

    if (decision.crashLoop) {
      return RestartOutcome(
        state = state
          .markComponent(name, ServiceStatus.CRASH_LOOP, "too many rapid restarts", countAsRestart = false)
          .appendEvent(name, "crash_loop", false, "rapid=${decision.rapidCount}"),
        delayMillis = decision.sleepSeconds * 1000L
      )
    }

    val stopOk = controller.stop()
    val ok = controller.start()
    return RestartOutcome(
      state = state
        .appendEvent(name, "auto_restart", ok, "delay=${decision.sleepSeconds}s rapid=${decision.rapidCount} stop=${if (stopOk) "ok" else "failed"}")
        .markComponent(name, if (ok) ServiceStatus.RUNNING else ServiceStatus.DEGRADED, if (ok) "" else "restart failed"),
      delayMillis = decision.sleepSeconds * 1000L
    )
  }

  private suspend fun restartComponentForManagement(
    target: String,
    state: StackStateV1,
    reason: String,
    detail: String
  ): RestartOutcome {
    val controller = components[target] ?: return RestartOutcome(state)
    if (controller.health()) {
      return RestartOutcome(
        state = state
          .appendEvent("management", "auto_recovery_skipped", true, "target=$target reason=$reason detail=$detail recovered_before_restart")
          .markComponent(target, ServiceStatus.RUNNING, "", countAsRestart = false),
        delayMillis = 0L
      )
    }
    controller.stop()
    val ok = controller.start()
    return RestartOutcome(
      state = state
        .appendEvent("management", "auto_recovery", ok, "target=$target reason=$reason detail=$detail")
        .markComponent(target, if (ok) ServiceStatus.RUNNING else ServiceStatus.DEGRADED, if (ok) "" else "restart failed"),
      delayMillis = 0L
    )
  }

  private suspend fun syncDdnsIfDue(
    state: StackStateV1,
    config: StackConfigV1,
    snapshot: HealthSnapshot,
    networkObservation: NetworkObservation
  ): StackStateV1 {
    if (!config.ddns.enabled) return state
    val controller = components["ddns"] ?: return state

    val now = System.currentTimeMillis() / 1000
    val intervalSeconds = config.ddns.intervalSeconds.coerceAtLeast(1)
    val convergenceIntervalSeconds = config.supervision.networkConvergencePollSeconds.coerceAtLeast(1)
    val ddnsState = state.services["ddns"] ?: ServiceRuntimeState()
    val ageSeconds = if (ddnsState.lastStartedEpochSeconds <= 0L) Long.MAX_VALUE else now - ddnsState.lastStartedEpochSeconds
    val stickNewIp = config.ddns.movePolicy.trim().lowercase() == "stick_new_ip"
    val directPublicFailed = directPublicPathFailed(snapshot)
    val directPublicInTransition = directPublicTransitioning(snapshot)
    val directPublicNeedsSync = directPublicFailed || directPublicInTransition
    val immediateReason = when {
      !snapshot.ddnsHealthy -> "unhealthy"
      config.ddns.syncOnNetworkChange && stickNewIp && networkObservation.publicIpv4Changed -> "public_ip_changed"
      config.ddns.syncOnNetworkChange && stickNewIp && networkObservation.fingerprintChanged -> "network_change"
      else -> null
    }
    val retryDue = when {
      directPublicNeedsSync && config.ddns.syncOnNetworkChange && stickNewIp && networkObservation.convergenceActive ->
        ageSeconds >= convergenceIntervalSeconds
      !snapshot.ddnsHealthy -> true
      else -> ageSeconds >= intervalSeconds
    }
    val reason = immediateReason ?: when {
      ddnsState.lastStartedEpochSeconds <= 0L -> "first_run"
      directPublicFailed && config.ddns.syncOnNetworkChange && stickNewIp && retryDue -> "direct_public_failed"
      directPublicInTransition && config.ddns.syncOnNetworkChange && stickNewIp && retryDue -> "direct_public_transition"
      retryDue && ageSeconds >= intervalSeconds -> "interval_elapsed"
      else -> null
    }
    if (reason == null) return state

    val ok = controller.start()
    return state
      .appendEvent("ddns", "auto_sync", ok, "reason=${reason} age=${if (ageSeconds == Long.MAX_VALUE) "never" else ageSeconds}s")
      .markComponent("ddns", if (ok) ServiceStatus.RUNNING else ServiceStatus.DEGRADED, if (ok) "" else "sync failed")
  }

  private fun observeRemoteHealth(state: StackStateV1, snapshot: HealthSnapshot): StackStateV1 {
    val healthy = snapshot.remoteHealthy && !directPublicPathFailed(snapshot)
    val current = state.services["remote"] ?: ServiceRuntimeState()
    if (healthy) {
      backoffs["remote"]?.reset()
      return if (current.status == ServiceStatus.DEGRADED || current.status == ServiceStatus.CRASH_LOOP) {
        state
          .markComponent("remote", ServiceStatus.RUNNING, "", countAsRestart = false)
          .appendEvent("remote", "health_recovered", true, "watchdog owned")
      } else {
        state
      }
    }

    return if (current.status != ServiceStatus.DEGRADED) {
      val reason = if (directPublicPathFailed(snapshot)) "direct public path degraded" else "remote healthcheck failed"
      state
        .markComponent("remote", ServiceStatus.DEGRADED, reason, countAsRestart = false)
        .appendEvent("remote", "health_unhealthy", false, reason)
    } else {
      state
    }
  }

  private fun observeManagementHealth(state: StackStateV1, snapshot: HealthSnapshot): StackStateV1 {
    if (!managementEnabled(snapshot)) {
      return state
    }

    val healthy = snapshot.managementHealthy
    val reason = managementReason(snapshot)
    val current = state.services["management"] ?: ServiceRuntimeState()
    if (healthy) {
      return if (current.status != ServiceStatus.RUNNING) {
        state
          .markComponent("management", ServiceStatus.RUNNING, "", countAsRestart = false)
          .appendEvent("management", "health_recovered", true, "reason=$reason")
      } else {
        state
      }
    }

    return if (current.status != ServiceStatus.DEGRADED) {
      state
        .markComponent("management", ServiceStatus.DEGRADED, reason, countAsRestart = false)
        .appendEvent("management", "health_unhealthy", false, "reason=$reason")
    } else {
      state
    }
  }

  private fun managementEnabled(snapshot: HealthSnapshot): Boolean {
    return snapshot.evidence["management_enabled"] == "true"
  }

  private fun managementReason(snapshot: HealthSnapshot): String {
    return snapshot.evidence["management_reason"].orEmpty().ifBlank { "unknown" }
  }

  private fun managementAuthReason(snapshot: HealthSnapshot): String {
    return snapshot.evidence["management_auth_warning_reason"].orEmpty().ifBlank { "unknown" }
  }

  private fun isComponentEnabled(name: String, config: StackConfigV1): Boolean {
    val moduleEnabled = config.modules[name]?.enabled ?: true
    return when (name) {
      "vpn" -> moduleEnabled && config.vpn.enabled
      "ddns" -> moduleEnabled && config.ddns.enabled
      "remote" -> moduleEnabled && (config.remote.dohEnabled || config.remote.dotEnabled)
      else -> moduleEnabled
    }
  }

  private suspend fun augmentComponentHealth(snapshot: HealthSnapshot, config: StackConfigV1): HealthSnapshot {
    val overlay = mutableMapOf<String, lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState>()
    components.values.forEach { controller ->
      if (!isComponentEnabled(controller.name, config)) {
        return@forEach
      }
      if (controller is ModuleHealthAwareComponentController) {
        overlay[controller.name] = controller.moduleHealthState()
      }
    }
    return if (overlay.isEmpty()) {
      snapshot
    } else {
      snapshot.copy(moduleHealth = snapshot.moduleHealth + overlay)
    }
  }

  private suspend fun observeAppManagedComponentHealth(
    state: StackStateV1,
    snapshot: HealthSnapshot,
    config: StackConfigV1,
    name: String
  ): StackStateV1 {
    if (!isComponentEnabled(name, config)) {
      return state
    }
    val moduleHealth = snapshot.moduleHealth[name] ?: return state
    return when (moduleHealth.status) {
      "disabled", "stopped" -> {
        state.markComponent(name, ServiceStatus.STOPPED, moduleHealth.status, countAsRestart = false)
      }

      else -> {
        if (moduleHealth.healthy) {
          val current = state.services[name] ?: ServiceRuntimeState()
          if (current.status != ServiceStatus.RUNNING) {
            state
              .markComponent(name, ServiceStatus.RUNNING, "", countAsRestart = false)
              .appendEvent(name, "health_recovered", true, moduleHealth.status)
          } else {
            state
          }
        } else {
          restartIfUnhealthy(name, false, state).state
        }
      }
    }
  }

  private fun StackStateV1.markComponent(
    name: String,
    status: ServiceStatus,
    failure: String,
    countAsRestart: Boolean = true
  ): StackStateV1 {
    val now = System.currentTimeMillis() / 1000
    val current = services[name] ?: ServiceRuntimeState()
    val updated = current.copy(
      status = status,
      restartCount = if (countAsRestart && status == ServiceStatus.RUNNING && current.status != ServiceStatus.RUNNING) {
        current.restartCount + 1
      } else {
        current.restartCount
      },
      lastFailureReason = failure,
      lastStartedEpochSeconds = if (status == ServiceStatus.RUNNING) now else current.lastStartedEpochSeconds,
      lastHealthyEpochSeconds = if (status == ServiceStatus.RUNNING) now else current.lastHealthyEpochSeconds
    )

    return copy(services = services + (name to updated))
  }

  private fun StackStateV1.appendEvent(component: String, action: String, success: Boolean, details: String): StackStateV1 {
    val next = operationLog
      .plus(
        OperationEvent(
          epochSeconds = System.currentTimeMillis() / 1000,
          component = component,
          action = action,
          success = success,
          details = details
        )
      )
      .takeLast(100)

    return copy(operationLog = next)
  }

  private fun StackStateV1.withSupervisorStatus(status: ServiceStatus): StackStateV1 {
    val now = System.currentTimeMillis() / 1000
    val supervisor = services["supervisor"] ?: ServiceRuntimeState()
    return copy(
      services = services + ("supervisor" to supervisor.copy(
        status = status,
        lastStartedEpochSeconds = if (status == ServiceStatus.RUNNING) now else supervisor.lastStartedEpochSeconds,
        lastHealthyEpochSeconds = if (status == ServiceStatus.RUNNING) now else supervisor.lastHealthyEpochSeconds
      )),
      lastSuccessfulBootEpochSeconds = if (status == ServiceStatus.RUNNING) now else lastSuccessfulBootEpochSeconds
    )
  }

  private fun StackStateV1.withSupervisorLoopHeartbeat(): StackStateV1 {
    return copy(supervisorLoopHeartbeatEpochSeconds = System.currentTimeMillis() / 1000)
  }

  private fun StackStateV1.withModuleHealth(snapshot: HealthSnapshot): StackStateV1 {
    val now = System.currentTimeMillis() / 1000
    val merged = moduleState.toMutableMap()
    snapshot.moduleHealth.forEach { (moduleId, moduleHealth) ->
      merged[moduleId] = ModuleRuntimeState(
        status = moduleHealth.status,
        healthy = moduleHealth.healthy,
        lastUpdatedEpochSeconds = now,
        details = moduleHealth.details
      )
    }
    return copy(moduleState = merged)
  }

  private data class RestartOutcome(
    val state: StackStateV1,
    val delayMillis: Long = 0L
  )

  private data class NetworkObservation(
    val state: StackStateV1,
    val fingerprintChanged: Boolean,
    val publicIpv4Changed: Boolean,
    val convergenceActive: Boolean
  )

  private fun launchLoop(forceRestart: Boolean) {
    if (forceRestart) {
      loopJob?.cancel()
    } else if (loopJob?.isActive == true) {
      return
    }

    loopJob = scope.launch {
      runLoop()
    }
  }

  private fun ensureBackoffPolicy(name: String, config: StackConfigV1 = configProvider()) {
    if (!backoffs.containsKey(name)) {
      val (initialSeconds, maxSeconds, rapidWindowSeconds, maxRapidRestarts) = when (name) {
        "train_bot" -> listOf(
          config.trainBot.backoffInitialSeconds,
          config.trainBot.backoffMaxSeconds,
          config.trainBot.rapidWindowSeconds,
          config.trainBot.maxRapidRestarts
        )
        "satiksme_bot" -> listOf(
          config.satiksmeBot.backoffInitialSeconds,
          config.satiksmeBot.backoffMaxSeconds,
          config.satiksmeBot.rapidWindowSeconds,
          config.satiksmeBot.maxRapidRestarts
        )
        "ssh" -> listOf(
          config.ssh.backoffInitialSeconds,
          config.ssh.backoffMaxSeconds,
          config.ssh.rapidWindowSeconds,
          config.ssh.maxRapidRestarts
        )
        "vpn" -> listOf(
          config.vpn.backoffInitialSeconds,
          config.vpn.backoffMaxSeconds,
          config.vpn.rapidWindowSeconds,
          config.vpn.maxRapidRestarts
        )
        "site_notifier" -> listOf(
          config.siteNotifier.backoffInitialSeconds,
          config.siteNotifier.backoffMaxSeconds,
          config.siteNotifier.rapidWindowSeconds,
          config.siteNotifier.maxRapidRestarts
        )
        else -> listOf(
          config.supervision.backoffInitialSeconds,
          config.supervision.backoffMaxSeconds,
          config.supervision.rapidWindowSeconds,
          config.supervision.maxRapidRestarts
        )
      }
      backoffs[name] = BackoffPolicy(
        initialSeconds = initialSeconds,
        maxSeconds = maxSeconds,
        rapidWindowSeconds = rapidWindowSeconds,
        maxRapidRestarts = maxRapidRestarts
      )
    }
  }

  private fun HealthSnapshot.trainBotTunnelFailure(): Boolean {
    return evidence["train_bot_tunnel_enabled"] == "true" && evidence["train_bot_tunnel_healthy"] == "false"
  }

  private fun HealthSnapshot.satiksmeBotTunnelFailure(): Boolean {
    if (satiksmeBotHealthy) return false
    return when (evidence["satiksme_bot_failure_reason"].orEmpty()) {
      "tunnel_supervisor_missing",
      "tunnel_pid_missing",
      "tunnel_probe_unavailable",
      "public_root_failed",
      "public_app_failed" -> true
      else -> false
    }
  }

  private fun HealthSnapshot.withTrainBotTunnelFailureCount(count: Int): HealthSnapshot {
    val module = moduleHealth["train_bot"]
    val updatedModuleHealth =
      if (module == null) {
        moduleHealth
      } else {
        moduleHealth + ("train_bot" to module.copy(details = module.details + ("tunnel_failure_count" to count.toString())))
      }
    return copy(
      moduleHealth = updatedModuleHealth,
      evidence = evidence + ("train_bot_tunnel_failure_count" to count.toString())
    )
  }

  private fun HealthSnapshot.withSatiksmeBotTunnelFailureCount(count: Int): HealthSnapshot {
    val module = moduleHealth["satiksme_bot"]
    val updatedModuleHealth =
      if (module == null) {
        moduleHealth
      } else {
        moduleHealth + ("satiksme_bot" to module.copy(details = module.details + ("tunnel_failure_count" to count.toString())))
      }
    return copy(
      moduleHealth = updatedModuleHealth,
      evidence = evidence + ("satiksme_bot_tunnel_failure_count" to count.toString())
    )
  }

  companion object {
    private val EXPLICITLY_MANAGED_COMPONENT_NAMES = setOf(
      "dns",
      "ssh",
      "vpn",
      "ddns",
      "remote",
      "management",
      "cpu_frequency",
      "train_bot",
      "satiksme_bot",
      "site_notifier",
      "subscription_bot"
    )
  }
}
