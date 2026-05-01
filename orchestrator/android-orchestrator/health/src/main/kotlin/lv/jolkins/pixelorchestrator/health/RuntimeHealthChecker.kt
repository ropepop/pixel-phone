package lv.jolkins.pixelorchestrator.health

import lv.jolkins.pixelorchestrator.coreconfig.HealthSnapshot
import lv.jolkins.pixelorchestrator.coreconfig.ModuleHealthState
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1

class RuntimeHealthChecker(
  private val commandRunner: CommandRunner
) {

  suspend fun check(config: StackConfigV1): HealthSnapshot {
    val probe = commandRunner.run(buildProbeCommand(config))
    val parsed = if (probe.ok) parseProbeOutput(probe.stdout) else null
    val nowEpoch = System.currentTimeMillis() / 1000
    val rootValue = parsed?.idU?.trim().orEmpty()
    val listenersOutput = parsed?.listeners.orEmpty()
    val dnsEnabled = isModuleEnabled(config, "dns")
    val sshEnabled = isModuleEnabled(config, "ssh")
    val vpnEnabled = isModuleEnabled(config, "vpn") && config.vpn.enabled
    val trainBotEnabled = isModuleEnabled(config, "train_bot")
    val satiksmeBotEnabled = isModuleEnabled(config, "satiksme_bot")
    val siteNotifierEnabled = isModuleEnabled(config, "site_notifier")
    val subscriptionBotEnabled = isModuleEnabled(config, "subscription_bot")
    val ticketScreenEnabled = isModuleEnabled(config, "ticket_screen")
    val ddnsRequired = isModuleEnabled(config, "ddns") && config.ddns.enabled
    val remoteEnabled = isModuleEnabled(config, "remote") && (config.remote.dohEnabled || config.remote.dotEnabled)
    val trainBotPid = parsed?.trainBotPid?.trim().orEmpty()
    val trainBotTunnelEnabled = parsed?.trainBotTunnelEnabled?.trim() == "1"
    val trainBotTunnelSupervisorPid = parsed?.trainBotTunnelSupervisorPid?.trim().orEmpty()
    val trainBotTunnelPid = parsed?.trainBotTunnelPid?.trim().orEmpty()
    val trainBotTunnelPublicBaseUrl = parsed?.trainBotTunnelPublicBaseUrl?.trim().orEmpty()
    val trainBotPublicRootCode = parsed?.trainBotPublicRootCode?.trim().orEmpty().ifBlank { "000" }
    val trainBotPublicAppCode = parsed?.trainBotPublicAppCode?.trim().orEmpty().ifBlank { "000" }
    val trainBotTunnelProbeAvailable = parsed?.trainBotTunnelProbeAvailable?.trim() == "1"
    val satiksmeBotPid = parsed?.satiksmeBotPid?.trim().orEmpty()
    val satiksmeBotTunnelEnabled = parsed?.satiksmeBotTunnelEnabled?.trim() == "1"
    val satiksmeBotTunnelSupervisorPid = parsed?.satiksmeBotTunnelSupervisorPid?.trim().orEmpty()
    val satiksmeBotTunnelPid = parsed?.satiksmeBotTunnelPid?.trim().orEmpty()
    val satiksmeBotTunnelPublicBaseUrl = parsed?.satiksmeBotTunnelPublicBaseUrl?.trim().orEmpty()
    val satiksmeBotPublicRootCode = parsed?.satiksmeBotPublicRootCode?.trim().orEmpty().ifBlank { "000" }
    val satiksmeBotPublicAppCode = parsed?.satiksmeBotPublicAppCode?.trim().orEmpty().ifBlank { "000" }
    val satiksmeBotTunnelProbeAvailable = parsed?.satiksmeBotTunnelProbeAvailable?.trim() == "1"
    val siteNotifierPid = parsed?.siteNotifierPid?.trim().orEmpty()
    val siteNotifierHelperHealthy = parsed?.siteNotifierHelperHealthy?.trim() == "1"
    val siteNotifierFailureReason = parsed?.siteNotifierHelperReason?.trim().orEmpty().ifBlank {
      if (siteNotifierHelperHealthy) "ok" else "unknown"
    }
    val subscriptionBotPid = parsed?.subscriptionBotPid?.trim().orEmpty()
    val trainBotHeartbeat = parsed?.trainBotHeartbeatEpoch.orEmpty()
    val satiksmeBotHeartbeat = parsed?.satiksmeBotHeartbeatEpoch.orEmpty()
    val siteNotifierHeartbeat = parsed?.siteNotifierHeartbeatEpoch.orEmpty()
    val subscriptionBotHeartbeat = parsed?.subscriptionBotHeartbeatEpoch.orEmpty()
    val trainBotScheduleRequired = parsed?.trainBotScheduleRequired?.trim() == "1"
    val trainBotScheduleFresh = parsed?.trainBotScheduleFresh?.trim() == "1"
    val trainBotScheduleServiceDate = parsed?.trainBotScheduleServiceDate?.trim().orEmpty()
    val trainBotScheduleRows = parsed?.trainBotScheduleRows?.trim().orEmpty().ifBlank { "unknown" }
    val trainBotScheduleRowsCount = trainBotScheduleRows.toIntOrNull()
    val trainBotScheduleRowsPresent = (trainBotScheduleRowsCount ?: 0) > 0
    val vpnHealthy = if (vpnEnabled) parsed?.vpnHealth?.trim() == "1" else false
    val vpnEnabledEffective = parsed?.vpnEnabledEffective?.trim().orEmpty().ifBlank { if (vpnEnabled) "1" else "0" }
    val vpnTailscaledLive = parsed?.vpnTailscaledLive?.trim().orEmpty().ifBlank { "0" }
    val vpnTailscaledSock = parsed?.vpnTailscaledSock?.trim().orEmpty().ifBlank { "0" }
    val vpnTailnetIpv4 = parsed?.vpnTailnetIpv4?.trim().orEmpty()
    val vpnGuardChainIpv4 = parsed?.vpnGuardChainIpv4?.trim().orEmpty().ifBlank { "0" }
    val vpnGuardChainIpv6 = parsed?.vpnGuardChainIpv6?.trim().orEmpty().ifBlank { "0" }
    val managementEnabled = parsed?.managementEnabled?.trim() == "1"
    val managementHealthy = if (managementEnabled) parsed?.managementHealthy?.trim() == "1" else true
    val managementReason = parsed?.managementReason?.trim().orEmpty().ifBlank { if (managementEnabled) "unknown" else "disabled" }
    val managementAuthConsistent = when {
      !managementEnabled -> true
      parsed?.managementAuthConsistent?.trim() == "1" -> true
      parsed?.managementAuthConsistent?.trim() == "0" -> false
      managementReason == "password_auth_runtime_mismatch" || managementReason == "key_auth_runtime_mismatch" -> false
      else -> true
    }
    val managementAuthWarningReason =
      parsed?.managementAuthWarningReason?.trim().orEmpty().ifBlank {
        when {
          !managementEnabled -> "disabled"
          managementAuthConsistent -> "ok"
          managementReason == "password_auth_runtime_mismatch" || managementReason == "key_auth_runtime_mismatch" -> managementReason
          else -> "unknown"
        }
      }
    val managementSshListenerRaw = parsed?.managementSshListener?.trim().orEmpty()
    val managementSshListener = managementSshListenerRaw.ifBlank { "0" }
    val managementSshAuthMode = parsed?.managementSshAuthMode?.trim().orEmpty().ifBlank { "disabled" }
    val managementSshPasswordAuthRequested = parsed?.managementSshPasswordAuthRequested?.trim().orEmpty().ifBlank { "0" }
    val managementSshPasswordAuthReady = parsed?.managementSshPasswordAuthReady?.trim().orEmpty().ifBlank { "0" }
    val managementSshKeyAuthRequested = parsed?.managementSshKeyAuthRequested?.trim().orEmpty().ifBlank { "0" }
    val managementSshKeyAuthReady = parsed?.managementSshKeyAuthReady?.trim().orEmpty().ifBlank { "0" }
    val managementPmPath = parsed?.managementPmPath?.trim().orEmpty()
    val managementAmPath = parsed?.managementAmPath?.trim().orEmpty()
    val managementLogcatPath = parsed?.managementLogcatPath?.trim().orEmpty()
    val managementRequireWirelessDebug = config.supervision.managementRequireWirelessDebug
    val managementWirelessDebugEnabled = parsed?.managementWirelessDebugEnabled?.trim().orEmpty().ifBlank { "0" }
    val managementWirelessDebugTlsPort = parsed?.managementWirelessDebugTlsPort?.trim().orEmpty()
    val managementWirelessDebugLive = parsed?.managementWirelessDebugLive?.trim().orEmpty().ifBlank { "0" }
    val managementWirelessDebugLivePorts = parsed?.managementWirelessDebugLivePorts?.trim().orEmpty()
    val managementWirelessDebugHealthy = parsed?.managementWirelessDebugHealthy?.trim().orEmpty().ifBlank { "0" }
    val managementWirelessDebugReason = parsed?.managementWirelessDebugReason?.trim().orEmpty()
      .ifBlank { "unknown" }
    val managementWifiEnabled = parsed?.managementWifiEnabled?.trim().orEmpty().ifBlank { "0" }
    val managementWifiConnected = parsed?.managementWifiConnected?.trim().orEmpty().ifBlank { "0" }
    val managementWifiIpv4 = parsed?.managementWifiIpv4?.trim().orEmpty()
    val managementMobileIface = parsed?.managementMobileIface?.trim().orEmpty()
    val managementMobileIpv4 = parsed?.managementMobileIpv4?.trim().orEmpty()
    val managementActiveTransport = parsed?.managementActiveTransport?.trim().orEmpty().ifBlank { "unknown" }
    val managementPublicIpv4Candidate = parsed?.managementPublicIpv4Candidate?.trim().orEmpty()
    val managementNetworkFingerprint = parsed?.managementNetworkFingerprint?.trim().orEmpty()
    val managementPathHealthy = if (managementEnabled) managementHealthy else true
    val ddnsPublishedIpv4 = parsed?.ddnsLastIpv4?.trim().orEmpty()
    val supervisorLoopHeartbeat = parsed?.supervisorLoopHeartbeatEpoch.orEmpty()

    val rootGranted = rootValue == "0"
    val dnsHealthy = dnsEnabled && listenersOutput.hasPort(config.dns.dnsPort)
    val ticketScreenHealthy = listenersOutput.hasPort(TICKET_SCREEN_PORT)
    val sshHealthy = when {
      !sshEnabled -> false
      managementSshListenerRaw.isNotBlank() -> managementSshListener == "1"
      else -> listenersOutput.hasPort(config.ssh.port)
    }
    val dohEndpointMode = normalizeDohEndpointMode(config.remote.dohEndpointMode)
    val remoteHealthEnforced = remoteEnabled && config.supervision.enforceRemoteListeners && config.remote.watchdogEscalateRuntimeRestart
    val remoteHttps = if (remoteHealthEnforced) {
      listenersOutput.hasPort(config.remote.httpsPort)
    } else {
      true
    }

    val remoteDot = if (config.remote.dotEnabled) {
      listenersOutput.hasPort(config.remote.dotPort)
    } else {
      true
    }
    val tokenizedDohCode = parsed?.remoteDohTokenizedCode?.trim().orEmpty()
    val bareDohCode = parsed?.remoteDohBareCode?.trim().orEmpty()
    val identityInjectCode = parsed?.remoteIdentityInjectCode?.trim().orEmpty()
    val remotePublicBaseUrl = parsed?.remotePublicBaseUrl?.trim().orEmpty()
    val remotePublicRootCode = parsed?.remotePublicRootCode?.trim().orEmpty().ifBlank { "000" }
    val remotePublicTokenizedCode = parsed?.remotePublicDohTokenizedCode?.trim().orEmpty().ifBlank { "000" }
    val remotePublicBareCode = parsed?.remotePublicDohBareCode?.trim().orEmpty().ifBlank { "000" }
    val remotePublicIdentityInjectCode = parsed?.remotePublicIdentityInjectCode?.trim().orEmpty().ifBlank { "000" }
    val remotePublicProbeAvailable = parsed?.remotePublicProbeAvailable?.trim() == "1"
    val identityFrontendRequired = remoteFrontendViaNginx(dohEndpointMode) && (config.remote.dohEnabled || config.remote.dotEnabled)
    val identityProbeUnavailable = identityInjectCode == "000"
    val identityFrontendHealthy = when {
      !identityFrontendRequired -> true
      identityProbeUnavailable -> true
      else -> identityInjectCode == "200"
    }
    val dohProbeMode = "no_query_http_contract"
    val dohProbeUnavailable = tokenizedDohCode == "000" && bareDohCode == "000"
    val tokenizedDohReachable = dohCodeReachableNonRouteMiss(tokenizedDohCode)
    val bareDohReachable = dohCodeReachableNonRouteMiss(bareDohCode)
    val remoteDohContract = when {
      !config.remote.dohEnabled -> true
      dohProbeUnavailable -> true
      dohEndpointMode == "tokenized" -> tokenizedDohReachable && bareDohCode == "404"
      dohEndpointMode == "dual" -> tokenizedDohReachable && bareDohReachable
      dohEndpointMode == "native" -> bareDohReachable
      else -> false
    }
    val remotePublicRootHealthy = httpCodeHealthyForRemoteRoot(remotePublicRootCode)
    val remotePublicTokenizedReachable = dohCodeReachableNonRouteMiss(remotePublicTokenizedCode)
    val remotePublicBareReachable = dohCodeReachableNonRouteMiss(remotePublicBareCode)
    val remotePublicIdentityFrontendHealthy = when {
      !identityFrontendRequired -> true
      !remotePublicProbeAvailable -> false
      else -> remotePublicIdentityInjectCode == "200"
    }
    val remotePublicDohContract = when {
      !config.remote.dohEnabled -> true
      !remotePublicProbeAvailable -> false
      dohEndpointMode == "tokenized" -> remotePublicTokenizedReachable && remotePublicBareCode == "404"
      dohEndpointMode == "dual" -> remotePublicTokenizedReachable && remotePublicBareReachable
      dohEndpointMode == "native" -> remotePublicBareReachable
      else -> false
    }
    val remotePublicHealthy = when {
      !remoteEnabled -> true
      !remotePublicProbeAvailable -> false
      else -> remotePublicRootHealthy && remotePublicDohContract && remotePublicIdentityFrontendHealthy
    }
    val directPublicDnsConsistent = when {
      managementPublicIpv4Candidate.isBlank() -> true
      ddnsPublishedIpv4.isBlank() -> true
      else -> managementPublicIpv4Candidate == ddnsPublishedIpv4
    }
    val directPublicProbeHealthy = when {
      !remoteEnabled -> true
      !remotePublicProbeAvailable -> true
      else -> remotePublicHealthy
    }
    val directPublicTransitioning = remotePublicProbeAvailable && directPublicProbeHealthy && !directPublicDnsConsistent
    val directPublicTransitionReason = if (directPublicTransitioning) "published_public_ipv4_lagging" else "ok"
    val directPublicPathHealthy = directPublicProbeHealthy

    val ddnsHealthy = if (ddnsRequired) {
      parsed?.ddnsEpoch?.let { isDdnsHeartbeatFresh(it, config) } ?: false
    } else {
      false
    }
    val supervisorLoopHeartbeatAge = heartbeatAgeSeconds(supervisorLoopHeartbeat, nowEpoch)
    val supervisorLoopFreshSeconds = supervisorLoopFreshSeconds(config)
    val supervisorLoopHealthy =
      supervisorLoopHeartbeatAge != null && supervisorLoopHeartbeatAge <= supervisorLoopFreshSeconds
    val trainBotHeartbeatAge = heartbeatAgeSeconds(trainBotHeartbeat, nowEpoch)
    val satiksmeBotHeartbeatAge = heartbeatAgeSeconds(satiksmeBotHeartbeat, nowEpoch)
    val siteNotifierHeartbeatAge = heartbeatAgeSeconds(siteNotifierHeartbeat, nowEpoch)
    val subscriptionBotHeartbeatAge = heartbeatAgeSeconds(subscriptionBotHeartbeat, nowEpoch)
    val trainBotScheduleProbeInconclusive = trainBotScheduleRows == "unknown"
    val trainBotScheduleHealthy =
      !trainBotScheduleRequired || trainBotScheduleFresh || trainBotScheduleRowsPresent || trainBotScheduleProbeInconclusive
    val trainBotPublicRootHealthy = trainBotPublicRootCode == "200"
    val trainBotPublicAppHealthy = trainBotPublicAppCode == "200"
    val trainBotTunnelSupervisorHealthy = when {
      !trainBotTunnelEnabled -> true
      else -> trainBotTunnelSupervisorPid.isNotBlank()
    }
    val trainBotTunnelHealthy = when {
      !trainBotTunnelEnabled -> true
      !trainBotTunnelSupervisorHealthy -> false
      trainBotTunnelPid.isBlank() -> false
      !trainBotTunnelProbeAvailable -> false
      else -> trainBotPublicRootHealthy && trainBotPublicAppHealthy
    }
    val satiksmeBotPublicRootHealthy = satiksmeBotPublicRootCode == "200"
    val satiksmeBotPublicAppHealthy = satiksmeBotPublicAppCode == "200"
    val satiksmeBotTunnelSupervisorHealthy = when {
      !satiksmeBotTunnelEnabled -> true
      else -> satiksmeBotTunnelSupervisorPid.isNotBlank()
    }
    val satiksmeBotTunnelHealthy = when {
      !satiksmeBotTunnelEnabled -> true
      !satiksmeBotTunnelSupervisorHealthy -> false
      satiksmeBotTunnelPid.isBlank() -> false
      !satiksmeBotTunnelProbeAvailable -> false
      else -> satiksmeBotPublicRootHealthy && satiksmeBotPublicAppHealthy
    }
    val satiksmeBotFailureReason =
      satiksmeBotFailureReason(
        satiksmeBotPid = satiksmeBotPid,
        satiksmeBotHeartbeatAge = satiksmeBotHeartbeatAge,
        satiksmeBotTunnelEnabled = satiksmeBotTunnelEnabled,
        satiksmeBotTunnelSupervisorHealthy = satiksmeBotTunnelSupervisorHealthy,
        satiksmeBotTunnelPid = satiksmeBotTunnelPid,
        satiksmeBotTunnelProbeAvailable = satiksmeBotTunnelProbeAvailable,
        satiksmeBotPublicRootHealthy = satiksmeBotPublicRootHealthy,
        satiksmeBotPublicAppHealthy = satiksmeBotPublicAppHealthy
      )
    val trainBotHealthy =
      trainBotEnabled &&
        trainBotPid.isNotBlank() &&
        trainBotHeartbeatAge != null &&
        trainBotHeartbeatAge <= APP_HEARTBEAT_FRESH_SEC &&
        trainBotScheduleHealthy &&
        trainBotTunnelHealthy
    val satiksmeBotHealthy =
      satiksmeBotEnabled &&
        satiksmeBotPid.isNotBlank() &&
        satiksmeBotHeartbeatAge != null &&
        satiksmeBotHeartbeatAge <= APP_HEARTBEAT_FRESH_SEC &&
        satiksmeBotTunnelHealthy
    val siteNotifierHealthy = siteNotifierEnabled && siteNotifierHelperHealthy
    val subscriptionBotHealthy =
      subscriptionBotEnabled &&
        subscriptionBotPid.isNotBlank() &&
        subscriptionBotHeartbeatAge != null &&
        subscriptionBotHeartbeatAge <= APP_HEARTBEAT_FRESH_SEC
    val trainBotFailureReason =
      when {
        !trainBotEnabled -> "disabled"
        trainBotPid.isBlank() -> "pid_missing"
        trainBotHeartbeatAge == null -> "heartbeat_missing"
        trainBotHeartbeatAge > APP_HEARTBEAT_FRESH_SEC -> "heartbeat_stale"
        !trainBotScheduleHealthy -> "schedule_unhealthy"
        !trainBotTunnelHealthy -> "tunnel_unhealthy"
        else -> "ok"
      }
    val siteNotifierEffectiveFailureReason =
      when {
        !siteNotifierEnabled -> "disabled"
        else -> siteNotifierFailureReason
      }
    val subscriptionBotFailureReason =
      when {
        !subscriptionBotEnabled -> "disabled"
        subscriptionBotPid.isBlank() -> "pid_missing"
        subscriptionBotHeartbeatAge == null -> "heartbeat_missing"
        subscriptionBotHeartbeatAge > APP_HEARTBEAT_FRESH_SEC -> "heartbeat_stale"
        else -> "ok"
      }

    val remoteHealthy = remoteEnabled && remoteHttps && remoteDot && remoteDohContract && identityFrontendHealthy
    val remoteRequired = remoteHealthEnforced
    val managementAuthHealthy = !managementEnabled || managementAuthConsistent
    val supervisorHealthy = rootGranted &&
      (!dnsEnabled || dnsHealthy) &&
      (!sshEnabled || sshHealthy) &&
      (!vpnEnabled || vpnHealthy) &&
      (!trainBotEnabled || trainBotHealthy) &&
      (!satiksmeBotEnabled || satiksmeBotHealthy) &&
      (!siteNotifierEnabled || siteNotifierHealthy) &&
      (!subscriptionBotEnabled || subscriptionBotHealthy) &&
      (!remoteRequired || remoteHealthy) &&
      (!managementEnabled || managementHealthy)
    val deployHealthy = supervisorLoopHealthy &&
      rootGranted &&
      (!dnsEnabled || dnsHealthy) &&
      (!sshEnabled || sshHealthy) &&
      (!vpnEnabled || vpnHealthy) &&
      (!trainBotEnabled || trainBotHealthy) &&
      (!satiksmeBotEnabled || satiksmeBotHealthy) &&
      (!siteNotifierEnabled || siteNotifierHealthy) &&
      (!subscriptionBotEnabled || subscriptionBotHealthy) &&
      (!ddnsRequired || ddnsHealthy) &&
      (!remoteRequired || remoteHealthy) &&
      (!managementEnabled || (managementHealthy && managementAuthHealthy))
    val moduleHealth = mapOf(
      "dns" to ModuleHealthState(
        healthy = dnsHealthy,
        status = when {
          !dnsEnabled -> "disabled"
          dnsHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf("dns_port" to config.dns.dnsPort.toString())
      ),
      "ssh" to ModuleHealthState(
        healthy = sshHealthy,
        status = when {
          !sshEnabled -> "disabled"
          sshHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "ssh_port" to config.ssh.port.toString(),
          "ssh_enabled" to sshEnabled.toString(),
          "ssh_listener" to managementSshListener
        )
      ),
      "vpn" to ModuleHealthState(
        healthy = vpnHealthy,
        status = when {
          !vpnEnabled -> "disabled"
          vpnHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "vpn_required" to vpnEnabled.toString(),
          "vpn_enabled" to vpnEnabled.toString(),
          "interface_name" to config.vpn.interfaceName,
          "vpn_enabled_effective" to vpnEnabledEffective,
          "tailscaled_live" to vpnTailscaledLive,
          "tailscaled_sock" to vpnTailscaledSock,
          "tailnet_ipv4" to vpnTailnetIpv4.ifBlank { "none" },
          "guard_chain_ipv4" to vpnGuardChainIpv4,
          "guard_chain_ipv6" to vpnGuardChainIpv6
        )
      ),
      "management" to ModuleHealthState(
        healthy = managementHealthy,
        status = when {
          !managementEnabled -> "disabled"
          managementHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "management_enabled" to managementEnabled.toString(),
          "failure_reason" to managementReason,
          "management_auth_healthy" to managementAuthHealthy.toString(),
          "management_auth_consistent" to managementAuthConsistent.toString(),
          "management_auth_warning_reason" to managementAuthWarningReason,
          "ssh_listener" to managementSshListener,
          "ssh_auth_mode" to managementSshAuthMode,
          "ssh_password_auth_requested" to managementSshPasswordAuthRequested,
          "ssh_password_auth_ready" to managementSshPasswordAuthReady,
          "ssh_key_auth_requested" to managementSshKeyAuthRequested,
          "ssh_key_auth_ready" to managementSshKeyAuthReady,
          "pm_path" to managementPmPath.ifBlank { "none" },
          "am_path" to managementAmPath.ifBlank { "none" },
          "logcat_path" to managementLogcatPath.ifBlank { "none" },
          "management_path_healthy" to managementPathHealthy.toString(),
          "require_wireless_debug" to managementRequireWirelessDebug.toString(),
          "wireless_debug_enabled" to managementWirelessDebugEnabled,
          "wireless_debug_tls_port" to managementWirelessDebugTlsPort.ifBlank { "none" },
          "wireless_debug_live" to managementWirelessDebugLive,
          "wireless_debug_live_ports" to managementWirelessDebugLivePorts.ifBlank { "none" },
          "wireless_debug_healthy" to managementWirelessDebugHealthy,
          "wireless_debug_reason" to managementWirelessDebugReason,
          "wifi_enabled" to managementWifiEnabled,
          "wifi_connected" to managementWifiConnected,
          "wifi_ipv4" to managementWifiIpv4.ifBlank { "none" },
          "mobile_iface" to managementMobileIface.ifBlank { "none" },
          "mobile_ipv4" to managementMobileIpv4.ifBlank { "none" },
          "active_transport" to managementActiveTransport,
          "public_ipv4_candidate" to managementPublicIpv4Candidate.ifBlank { "none" },
          "network_fingerprint" to managementNetworkFingerprint.ifBlank { "none" },
          "vpn_required" to managementEnabled.toString(),
          "vpn_healthy" to vpnHealthy.toString(),
          "tailnet_ipv4" to vpnTailnetIpv4.ifBlank { "none" }
        )
      ),
      "train_bot" to ModuleHealthState(
        healthy = trainBotHealthy,
        status = when {
          !trainBotEnabled -> "disabled"
          trainBotHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "train_bot_enabled" to trainBotEnabled.toString(),
          "train_bot_pid" to trainBotPid,
          "tunnel_enabled" to trainBotTunnelEnabled.toString(),
          "tunnel_supervisor_pid" to trainBotTunnelSupervisorPid.ifBlank { "none" },
          "tunnel_pid" to trainBotTunnelPid.ifBlank { "none" },
          "tunnel_public_base_url" to trainBotTunnelPublicBaseUrl.ifBlank { "none" },
          "public_root_code" to trainBotPublicRootCode,
          "public_app_code" to trainBotPublicAppCode,
          "tunnel_probe_available" to trainBotTunnelProbeAvailable.toString(),
          "tunnel_supervisor_healthy" to trainBotTunnelSupervisorHealthy.toString(),
          "public_root_healthy" to trainBotPublicRootHealthy.toString(),
          "public_app_healthy" to trainBotPublicAppHealthy.toString(),
          "tunnel_healthy" to trainBotTunnelHealthy.toString(),
          "heartbeat_age_sec" to (trainBotHeartbeatAge?.toString() ?: "unknown"),
          "schedule_required" to trainBotScheduleRequired.toString(),
          "schedule_fresh" to trainBotScheduleFresh.toString(),
          "schedule_rows_present" to trainBotScheduleRowsPresent.toString(),
          "schedule_probe_inconclusive" to trainBotScheduleProbeInconclusive.toString(),
          "schedule_service_date" to trainBotScheduleServiceDate,
          "schedule_rows" to trainBotScheduleRows,
          "failure_reason" to trainBotFailureReason
        )
      ),
      "satiksme_bot" to ModuleHealthState(
        healthy = satiksmeBotHealthy,
        status = when {
          !satiksmeBotEnabled -> "disabled"
          satiksmeBotHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "satiksme_bot_enabled" to satiksmeBotEnabled.toString(),
          "satiksme_bot_pid" to satiksmeBotPid,
          "tunnel_enabled" to satiksmeBotTunnelEnabled.toString(),
          "tunnel_supervisor_pid" to satiksmeBotTunnelSupervisorPid.ifBlank { "none" },
          "tunnel_pid" to satiksmeBotTunnelPid.ifBlank { "none" },
          "tunnel_public_base_url" to satiksmeBotTunnelPublicBaseUrl.ifBlank { "none" },
          "public_root_code" to satiksmeBotPublicRootCode,
          "public_app_code" to satiksmeBotPublicAppCode,
          "tunnel_probe_available" to satiksmeBotTunnelProbeAvailable.toString(),
          "tunnel_supervisor_healthy" to satiksmeBotTunnelSupervisorHealthy.toString(),
          "public_root_healthy" to satiksmeBotPublicRootHealthy.toString(),
          "public_app_healthy" to satiksmeBotPublicAppHealthy.toString(),
          "tunnel_healthy" to satiksmeBotTunnelHealthy.toString(),
          "heartbeat_age_sec" to (satiksmeBotHeartbeatAge?.toString() ?: "unknown"),
          "failure_reason" to if (satiksmeBotEnabled) satiksmeBotFailureReason else "disabled"
        )
      ),
      "site_notifier" to ModuleHealthState(
        healthy = siteNotifierHealthy,
        status = when {
          !siteNotifierEnabled -> "disabled"
          siteNotifierHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "site_notifier_enabled" to siteNotifierEnabled.toString(),
          "site_notifier_pid" to siteNotifierPid.ifBlank { "none" },
          "heartbeat_age_sec" to (siteNotifierHeartbeatAge?.toString() ?: "unknown"),
          "helper_healthy" to siteNotifierHelperHealthy.toString(),
          "failure_reason" to siteNotifierEffectiveFailureReason
        )
      ),
      "subscription_bot" to ModuleHealthState(
        healthy = subscriptionBotHealthy,
        status = when {
          !subscriptionBotEnabled -> "disabled"
          subscriptionBotHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "subscription_bot_enabled" to subscriptionBotEnabled.toString(),
          "subscription_bot_pid" to subscriptionBotPid.ifBlank { "none" },
          "heartbeat_age_sec" to (subscriptionBotHeartbeatAge?.toString() ?: "unknown"),
          "failure_reason" to subscriptionBotFailureReason
        )
      ),
      "ticket_screen" to ModuleHealthState(
        healthy = ticketScreenHealthy,
        status = when {
          ticketScreenHealthy -> "running"
          ticketScreenEnabled -> "degraded"
          else -> "disabled"
        },
        details = mapOf(
          "ticket_screen_enabled" to ticketScreenEnabled.toString(),
          "ticket_screen_port" to TICKET_SCREEN_PORT.toString(),
          "listener" to if (ticketScreenHealthy) "1" else "0"
        )
      ),
      "ddns" to ModuleHealthState(
        healthy = ddnsHealthy,
        status = when {
          !ddnsRequired -> "disabled"
          ddnsHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "ddns_enabled" to ddnsRequired.toString(),
          "last_sync_fresh" to ddnsHealthy.toString()
        )
      ),
      "remote" to ModuleHealthState(
        healthy = remoteHealthy,
        status = when {
          !remoteEnabled -> "disabled"
          remoteHealthy -> "running"
          else -> "degraded"
        },
        details = mapOf(
          "public_base_url" to remotePublicBaseUrl.ifBlank { "none" },
          "https_port" to config.remote.httpsPort.toString(),
          "dot_port" to config.remote.dotPort.toString(),
          "doh_endpoint_mode" to dohEndpointMode,
          "doh_tokenized_code" to tokenizedDohCode.ifBlank { "none" },
          "doh_bare_code" to bareDohCode.ifBlank { "none" },
          "public_root_code" to remotePublicRootCode,
          "public_doh_tokenized_code" to remotePublicTokenizedCode,
          "public_doh_bare_code" to remotePublicBareCode,
          "public_identity_inject_code" to remotePublicIdentityInjectCode,
          "doh_probe_mode" to dohProbeMode,
          "health_enforced" to remoteHealthEnforced.toString(),
          "doh_probe_unavailable" to dohProbeUnavailable.toString(),
          "doh_contract" to remoteDohContract.toString(),
          "public_probe_available" to remotePublicProbeAvailable.toString(),
          "public_root_healthy" to remotePublicRootHealthy.toString(),
          "public_doh_contract" to remotePublicDohContract.toString(),
          "direct_public_probe_healthy" to directPublicProbeHealthy.toString(),
          "direct_public_dns_consistent" to directPublicDnsConsistent.toString(),
          "direct_public_transitioning" to directPublicTransitioning.toString(),
          "direct_public_transition_reason" to directPublicTransitionReason,
          "direct_public_path_healthy" to directPublicPathHealthy.toString(),
          "ddns_published_ipv4" to ddnsPublishedIpv4.ifBlank { "none" },
          "current_public_ipv4_candidate" to managementPublicIpv4Candidate.ifBlank { "none" },
          "identity_frontend_required" to identityFrontendRequired.toString(),
          "identity_inject_code" to identityInjectCode.ifBlank { "none" },
          "identity_probe_unavailable" to identityProbeUnavailable.toString(),
          "identity_frontend_healthy" to identityFrontendHealthy.toString(),
          "public_identity_frontend_healthy" to remotePublicIdentityFrontendHealthy.toString()
        )
      ),
      "supervisor" to ModuleHealthState(
        healthy = supervisorLoopHealthy,
        status = if (supervisorLoopHealthy) "running" else "degraded",
        details = mapOf(
          "root_granted" to rootGranted.toString(),
          "loop_heartbeat_age_sec" to (supervisorLoopHeartbeatAge?.toString() ?: "unknown"),
          "loop_heartbeat_fresh_sec" to supervisorLoopFreshSeconds.toString(),
          "deploy_healthy" to deployHealthy.toString()
        )
      )
    )

    return HealthSnapshot(
      generatedEpochSeconds = nowEpoch,
      rootGranted = rootGranted,
      dnsHealthy = dnsHealthy,
      remoteHealthy = remoteHealthy,
      managementHealthy = managementHealthy,
      sshHealthy = sshHealthy,
      vpnHealthy = vpnHealthy,
      trainBotHealthy = trainBotHealthy,
      satiksmeBotHealthy = satiksmeBotHealthy,
      siteNotifierHealthy = siteNotifierHealthy,
      subscriptionBotHealthy = subscriptionBotHealthy,
      ddnsHealthy = ddnsHealthy,
      supervisorLoopHealthy = supervisorLoopHealthy,
      managementAuthHealthy = managementAuthHealthy,
      deployHealthy = deployHealthy,
      supervisorHealthy = supervisorHealthy,
      moduleHealth = moduleHealth,
      evidence = mapOf(
        "id_u" to rootValue,
        "dns_port" to config.dns.dnsPort.toString(),
        "ssh_port" to config.ssh.port.toString(),
        "ssh_enabled" to sshEnabled.toString(),
        "vpn_enabled" to vpnEnabled.toString(),
        "vpn_interface_name" to config.vpn.interfaceName,
        "vpn_healthy" to vpnHealthy.toString(),
        "vpn_enabled_effective" to vpnEnabledEffective,
        "vpn_tailscaled_live" to vpnTailscaledLive,
        "vpn_tailscaled_sock" to vpnTailscaledSock,
        "vpn_tailnet_ipv4" to vpnTailnetIpv4.ifBlank { "none" },
        "vpn_guard_chain_ipv4" to vpnGuardChainIpv4,
        "vpn_guard_chain_ipv6" to vpnGuardChainIpv6,
        "management_enabled" to managementEnabled.toString(),
        "management_healthy" to managementHealthy.toString(),
        "management_path_healthy" to managementPathHealthy.toString(),
        "management_reason" to managementReason,
        "management_auth_healthy" to managementAuthHealthy.toString(),
        "management_auth_consistent" to managementAuthConsistent.toString(),
        "management_auth_warning_reason" to managementAuthWarningReason,
        "management_ssh_listener" to managementSshListener,
        "management_ssh_auth_mode" to managementSshAuthMode,
        "management_ssh_password_auth_requested" to managementSshPasswordAuthRequested,
        "management_ssh_password_auth_ready" to managementSshPasswordAuthReady,
        "management_ssh_key_auth_requested" to managementSshKeyAuthRequested,
        "management_ssh_key_auth_ready" to managementSshKeyAuthReady,
        "management_pm_path" to managementPmPath.ifBlank { "none" },
        "management_am_path" to managementAmPath.ifBlank { "none" },
        "management_logcat_path" to managementLogcatPath.ifBlank { "none" },
        "management_require_wireless_debug" to managementRequireWirelessDebug.toString(),
        "management_wireless_debug_enabled" to managementWirelessDebugEnabled,
        "management_wireless_debug_tls_port" to managementWirelessDebugTlsPort.ifBlank { "none" },
        "wireless_debug_live" to managementWirelessDebugLive,
        "wireless_debug_live_ports" to managementWirelessDebugLivePorts.ifBlank { "none" },
        "management_wireless_debug_healthy" to managementWirelessDebugHealthy,
        "management_wireless_debug_reason" to managementWirelessDebugReason,
        "network_wifi_enabled" to managementWifiEnabled,
        "network_wifi_connected" to managementWifiConnected,
        "network_wifi_ipv4" to managementWifiIpv4.ifBlank { "none" },
        "network_mobile_iface" to managementMobileIface.ifBlank { "none" },
        "network_mobile_ipv4" to managementMobileIpv4.ifBlank { "none" },
        "network_active_transport" to managementActiveTransport,
        "network_public_ipv4_candidate" to managementPublicIpv4Candidate.ifBlank { "none" },
        "network_fingerprint" to managementNetworkFingerprint.ifBlank { "none" },
        "ddns_published_ipv4" to ddnsPublishedIpv4.ifBlank { "none" },
        "ddns_required" to ddnsRequired.toString(),
        "ddns_healthy" to ddnsHealthy.toString(),
        "supervisor_loop_heartbeat_age_sec" to (supervisorLoopHeartbeatAge?.toString() ?: "unknown"),
        "supervisor_loop_heartbeat_fresh_sec" to supervisorLoopFreshSeconds.toString(),
        "supervisor_loop_healthy" to supervisorLoopHealthy.toString(),
        "deploy_healthy" to deployHealthy.toString(),
        "https_port" to config.remote.httpsPort.toString(),
        "dot_port" to config.remote.dotPort.toString(),
        "remote_public_base_url" to remotePublicBaseUrl.ifBlank { "none" },
        "doh_endpoint_mode" to dohEndpointMode,
        "doh_tokenized_code" to tokenizedDohCode.ifBlank { "none" },
        "doh_bare_code" to bareDohCode.ifBlank { "none" },
        "remote_public_root_code" to remotePublicRootCode,
        "remote_public_doh_tokenized_code" to remotePublicTokenizedCode,
        "remote_public_doh_bare_code" to remotePublicBareCode,
        "remote_public_identity_inject_code" to remotePublicIdentityInjectCode,
        "doh_probe_mode" to dohProbeMode,
        "remote_health_enforced" to remoteHealthEnforced.toString(),
        "doh_probe_unavailable" to dohProbeUnavailable.toString(),
        "doh_contract" to remoteDohContract.toString(),
        "remote_public_probe_available" to remotePublicProbeAvailable.toString(),
        "remote_public_root_healthy" to remotePublicRootHealthy.toString(),
        "remote_public_doh_contract" to remotePublicDohContract.toString(),
        "direct_public_probe_healthy" to directPublicProbeHealthy.toString(),
        "direct_public_dns_consistent" to directPublicDnsConsistent.toString(),
        "direct_public_transitioning" to directPublicTransitioning.toString(),
        "direct_public_transition_reason" to directPublicTransitionReason,
        "direct_public_path_healthy" to directPublicPathHealthy.toString(),
        "identity_frontend_required" to identityFrontendRequired.toString(),
        "identity_inject_code" to identityInjectCode.ifBlank { "none" },
        "identity_probe_unavailable" to identityProbeUnavailable.toString(),
        "identity_frontend_healthy" to identityFrontendHealthy.toString(),
        "remote_public_identity_frontend_healthy" to remotePublicIdentityFrontendHealthy.toString(),
        "listeners_ok" to (probe.ok && parsed != null).toString(),
        "train_bot_pid" to trainBotPid,
        "train_bot_enabled" to trainBotEnabled.toString(),
        "train_bot_tunnel_enabled" to trainBotTunnelEnabled.toString(),
        "train_bot_tunnel_supervisor_pid" to trainBotTunnelSupervisorPid.ifBlank { "none" },
        "train_bot_tunnel_pid" to trainBotTunnelPid.ifBlank { "none" },
        "train_bot_tunnel_public_base_url" to trainBotTunnelPublicBaseUrl.ifBlank { "none" },
        "train_bot_public_root_code" to trainBotPublicRootCode,
        "train_bot_public_app_code" to trainBotPublicAppCode,
        "train_bot_tunnel_probe_available" to trainBotTunnelProbeAvailable.toString(),
        "train_bot_tunnel_supervisor_healthy" to trainBotTunnelSupervisorHealthy.toString(),
        "train_bot_public_root_healthy" to trainBotPublicRootHealthy.toString(),
        "train_bot_public_app_healthy" to trainBotPublicAppHealthy.toString(),
        "train_bot_tunnel_healthy" to trainBotTunnelHealthy.toString(),
        "satiksme_bot_pid" to satiksmeBotPid,
        "satiksme_bot_enabled" to satiksmeBotEnabled.toString(),
        "satiksme_bot_tunnel_enabled" to satiksmeBotTunnelEnabled.toString(),
        "satiksme_bot_tunnel_supervisor_pid" to satiksmeBotTunnelSupervisorPid.ifBlank { "none" },
        "satiksme_bot_tunnel_pid" to satiksmeBotTunnelPid.ifBlank { "none" },
        "satiksme_bot_tunnel_public_base_url" to satiksmeBotTunnelPublicBaseUrl.ifBlank { "none" },
        "satiksme_bot_public_root_code" to satiksmeBotPublicRootCode,
        "satiksme_bot_public_app_code" to satiksmeBotPublicAppCode,
        "satiksme_bot_tunnel_probe_available" to satiksmeBotTunnelProbeAvailable.toString(),
        "satiksme_bot_tunnel_supervisor_healthy" to satiksmeBotTunnelSupervisorHealthy.toString(),
        "satiksme_bot_public_root_healthy" to satiksmeBotPublicRootHealthy.toString(),
        "satiksme_bot_public_app_healthy" to satiksmeBotPublicAppHealthy.toString(),
        "satiksme_bot_tunnel_healthy" to satiksmeBotTunnelHealthy.toString(),
        "satiksme_bot_failure_reason" to satiksmeBotFailureReason,
        "site_notifier_pid" to siteNotifierPid.ifBlank { "none" },
        "site_notifier_enabled" to siteNotifierEnabled.toString(),
        "site_notifier_helper_healthy" to siteNotifierHelperHealthy.toString(),
        "site_notifier_failure_reason" to siteNotifierFailureReason,
        "subscription_bot_enabled" to subscriptionBotEnabled.toString(),
        "subscription_bot_pid" to subscriptionBotPid.ifBlank { "none" },
        "train_bot_heartbeat_age_sec" to (trainBotHeartbeatAge?.toString() ?: "unknown"),
        "satiksme_bot_heartbeat_age_sec" to (satiksmeBotHeartbeatAge?.toString() ?: "unknown"),
        "site_notifier_heartbeat_age_sec" to (siteNotifierHeartbeatAge?.toString() ?: "unknown"),
        "subscription_bot_heartbeat_age_sec" to (subscriptionBotHeartbeatAge?.toString() ?: "unknown"),
        "subscription_bot_failure_reason" to subscriptionBotFailureReason,
        "train_bot_schedule_required" to trainBotScheduleRequired.toString(),
        "train_bot_schedule_fresh" to trainBotScheduleFresh.toString(),
        "train_bot_schedule_rows_present" to trainBotScheduleRowsPresent.toString(),
        "train_bot_schedule_probe_inconclusive" to trainBotScheduleProbeInconclusive.toString(),
        "train_bot_schedule_service_date" to trainBotScheduleServiceDate,
        "train_bot_schedule_rows" to trainBotScheduleRows
      )
    )
  }

  private fun heartbeatAgeSeconds(epochText: String, nowEpoch: Long): Long? {
    val epoch = epochText.trim().toLongOrNull() ?: return null
    return if (epoch <= nowEpoch) nowEpoch - epoch else 0L
  }

  private fun isModuleEnabled(config: StackConfigV1, moduleId: String): Boolean {
    return config.modules[moduleId]?.enabled ?: true
  }

  private fun satiksmeBotFailureReason(
    satiksmeBotPid: String,
    satiksmeBotHeartbeatAge: Long?,
    satiksmeBotTunnelEnabled: Boolean,
    satiksmeBotTunnelSupervisorHealthy: Boolean,
    satiksmeBotTunnelPid: String,
    satiksmeBotTunnelProbeAvailable: Boolean,
    satiksmeBotPublicRootHealthy: Boolean,
    satiksmeBotPublicAppHealthy: Boolean
  ): String {
    return when {
      satiksmeBotPid.isBlank() -> "pid_missing"
      satiksmeBotHeartbeatAge == null -> "heartbeat_missing"
      satiksmeBotHeartbeatAge > APP_HEARTBEAT_FRESH_SEC -> "heartbeat_stale"
      !satiksmeBotTunnelEnabled -> "ok"
      !satiksmeBotTunnelSupervisorHealthy -> "tunnel_supervisor_missing"
      satiksmeBotTunnelPid.isBlank() -> "tunnel_pid_missing"
      !satiksmeBotTunnelProbeAvailable -> "tunnel_probe_unavailable"
      !satiksmeBotPublicRootHealthy -> "public_root_failed"
      !satiksmeBotPublicAppHealthy -> "public_app_failed"
      else -> "ok"
    }
  }

  private fun isDdnsHeartbeatFresh(epochText: String, config: StackConfigV1): Boolean {
    val epoch = epochText.trim().toLongOrNull() ?: return false
    val freshnessSeconds = maxOf(600L, config.ddns.intervalSeconds.toLong().coerceAtLeast(1L) * 2L)
    return (System.currentTimeMillis() / 1000) - epoch <= freshnessSeconds
  }

  private fun supervisorLoopFreshSeconds(config: StackConfigV1): Long {
    val maxBackoffSeconds = listOf(
      config.supervision.backoffMaxSeconds,
      config.ssh.backoffMaxSeconds,
      config.vpn.backoffMaxSeconds,
      config.trainBot.backoffMaxSeconds,
      config.satiksmeBot.backoffMaxSeconds,
      config.siteNotifier.backoffMaxSeconds,
      config.subscriptionBot.backoffMaxSeconds
    ).maxOrNull()?.toLong() ?: config.supervision.backoffMaxSeconds.toLong()
    val maxPollSeconds = maxOf(
      config.supervision.healthPollSeconds.toLong().coerceAtLeast(1L),
      config.supervision.networkConvergencePollSeconds.toLong().coerceAtLeast(1L)
    )
    return maxOf(30L, maxBackoffSeconds + maxPollSeconds + 5L)
  }

  private fun parseProbeOutput(stdout: String): ParsedProbe? {
    var section: ProbeSection = ProbeSection.NONE
    var seenId = false
    var seenListeners = false
    var seenDdns = false
    var seenDdnsLastIpv4 = false
    var seenSupervisorLoopHeartbeat = false
    var seenTrainPid = false
    var seenTrainTunnelEnabled = false
    var seenTrainTunnelSupervisorPid = false
    var seenTrainTunnelPid = false
    var seenTrainTunnelPublicBaseUrl = false
    var seenTrainBotPublicRootCode = false
    var seenTrainBotPublicAppCode = false
    var seenTrainBotTunnelProbeAvailable = false
    var seenTrainHeartbeat = false
    var seenTrainScheduleRequired = false
    var seenTrainScheduleFresh = false
    var seenTrainScheduleServiceDate = false
    var seenTrainScheduleRows = false
    var seenSatiksmePid = false
    var seenSatiksmeTunnelEnabled = false
    var seenSatiksmeTunnelSupervisorPid = false
    var seenSatiksmeTunnelPid = false
    var seenSatiksmeTunnelPublicBaseUrl = false
    var seenSatiksmePublicRootCode = false
    var seenSatiksmePublicAppCode = false
    var seenSatiksmeTunnelProbeAvailable = false
    var seenSatiksmeHeartbeat = false
    var seenNotifierPid = false
    var seenNotifierHeartbeat = false
    var seenNotifierHelperHealthy = false
    var seenNotifierHelperReason = false
    var seenSubscriptionPid = false
    var seenSubscriptionHeartbeat = false
    var seenVpnHealth = false
    var seenVpnEnabledEffective = false
    var seenVpnTailscaledLive = false
    var seenVpnTailscaledSock = false
    var seenVpnTailnetIpv4 = false
    var seenVpnGuardChainIpv4 = false
    var seenVpnGuardChainIpv6 = false
    var seenManagementEnabled = false
    var seenManagementHealthy = false
    var seenManagementReason = false
    var seenManagementAuthConsistent = false
    var seenManagementAuthWarningReason = false
    var seenManagementSshListener = false
    var seenManagementSshAuthMode = false
    var seenManagementSshPasswordAuthRequested = false
    var seenManagementSshPasswordAuthReady = false
    var seenManagementSshKeyAuthRequested = false
    var seenManagementSshKeyAuthReady = false
    var seenManagementPmPath = false
    var seenManagementAmPath = false
    var seenManagementLogcatPath = false
    var seenManagementWirelessDebugEnabled = false
    var seenManagementWirelessDebugTlsPort = false
    var seenManagementWirelessDebugLive = false
    var seenManagementWirelessDebugLivePorts = false
    var seenManagementWirelessDebugHealthy = false
    var seenManagementWirelessDebugReason = false
    var seenManagementWifiEnabled = false
    var seenManagementWifiConnected = false
    var seenManagementWifiIpv4 = false
    var seenManagementMobileIface = false
    var seenManagementMobileIpv4 = false
    var seenManagementActiveTransport = false
    var seenManagementPublicIpv4Candidate = false
    var seenManagementNetworkFingerprint = false
    var seenRemoteDohTokenizedCode = false
    var seenRemoteDohBareCode = false
    var seenRemoteIdentityInjectCode = false
    var seenRemotePublicBaseUrl = false
    var seenRemotePublicRootCode = false
    var seenRemotePublicProbeAvailable = false
    var seenRemotePublicDohTokenizedCode = false
    var seenRemotePublicDohBareCode = false
    var seenRemotePublicIdentityInjectCode = false
    var seenEnd = false
    val idBuilder = StringBuilder()
    val listenersBuilder = StringBuilder()
    val ddnsBuilder = StringBuilder()
    val ddnsLastIpv4Builder = StringBuilder()
    val supervisorLoopHeartbeatBuilder = StringBuilder()
    val trainPidBuilder = StringBuilder()
    val trainTunnelEnabledBuilder = StringBuilder()
    val trainTunnelSupervisorPidBuilder = StringBuilder()
    val trainTunnelPidBuilder = StringBuilder()
    val trainTunnelPublicBaseUrlBuilder = StringBuilder()
    val trainPublicRootCodeBuilder = StringBuilder()
    val trainPublicAppCodeBuilder = StringBuilder()
    val trainTunnelProbeAvailableBuilder = StringBuilder()
    val trainHeartbeatBuilder = StringBuilder()
    val trainScheduleRequiredBuilder = StringBuilder()
    val trainScheduleFreshBuilder = StringBuilder()
    val trainScheduleServiceDateBuilder = StringBuilder()
    val trainScheduleRowsBuilder = StringBuilder()
    val satiksmePidBuilder = StringBuilder()
    val satiksmeTunnelEnabledBuilder = StringBuilder()
    val satiksmeTunnelSupervisorPidBuilder = StringBuilder()
    val satiksmeTunnelPidBuilder = StringBuilder()
    val satiksmeTunnelPublicBaseUrlBuilder = StringBuilder()
    val satiksmePublicRootCodeBuilder = StringBuilder()
    val satiksmePublicAppCodeBuilder = StringBuilder()
    val satiksmeTunnelProbeAvailableBuilder = StringBuilder()
    val satiksmeHeartbeatBuilder = StringBuilder()
    val notifierPidBuilder = StringBuilder()
    val notifierHeartbeatBuilder = StringBuilder()
    val notifierHelperHealthyBuilder = StringBuilder()
    val notifierHelperReasonBuilder = StringBuilder()
    val subscriptionPidBuilder = StringBuilder()
    val subscriptionHeartbeatBuilder = StringBuilder()
    val vpnHealthBuilder = StringBuilder()
    val vpnEnabledEffectiveBuilder = StringBuilder()
    val vpnTailscaledLiveBuilder = StringBuilder()
    val vpnTailscaledSockBuilder = StringBuilder()
    val vpnTailnetIpv4Builder = StringBuilder()
    val vpnGuardChainIpv4Builder = StringBuilder()
    val vpnGuardChainIpv6Builder = StringBuilder()
    val managementEnabledBuilder = StringBuilder()
    val managementHealthyBuilder = StringBuilder()
    val managementReasonBuilder = StringBuilder()
    val managementAuthConsistentBuilder = StringBuilder()
    val managementAuthWarningReasonBuilder = StringBuilder()
    val managementSshListenerBuilder = StringBuilder()
    val managementSshAuthModeBuilder = StringBuilder()
    val managementSshPasswordAuthRequestedBuilder = StringBuilder()
    val managementSshPasswordAuthReadyBuilder = StringBuilder()
    val managementSshKeyAuthRequestedBuilder = StringBuilder()
    val managementSshKeyAuthReadyBuilder = StringBuilder()
    val managementPmPathBuilder = StringBuilder()
    val managementAmPathBuilder = StringBuilder()
    val managementLogcatPathBuilder = StringBuilder()
    val managementWirelessDebugEnabledBuilder = StringBuilder()
    val managementWirelessDebugTlsPortBuilder = StringBuilder()
    val managementWirelessDebugLiveBuilder = StringBuilder()
    val managementWirelessDebugLivePortsBuilder = StringBuilder()
    val managementWirelessDebugHealthyBuilder = StringBuilder()
    val managementWirelessDebugReasonBuilder = StringBuilder()
    val managementWifiEnabledBuilder = StringBuilder()
    val managementWifiConnectedBuilder = StringBuilder()
    val managementWifiIpv4Builder = StringBuilder()
    val managementMobileIfaceBuilder = StringBuilder()
    val managementMobileIpv4Builder = StringBuilder()
    val managementActiveTransportBuilder = StringBuilder()
    val managementPublicIpv4CandidateBuilder = StringBuilder()
    val managementNetworkFingerprintBuilder = StringBuilder()
    val remoteDohTokenizedCodeBuilder = StringBuilder()
    val remoteDohBareCodeBuilder = StringBuilder()
    val remoteIdentityInjectCodeBuilder = StringBuilder()
    val remotePublicBaseUrlBuilder = StringBuilder()
    val remotePublicRootCodeBuilder = StringBuilder()
    val remotePublicProbeAvailableBuilder = StringBuilder()
    val remotePublicDohTokenizedCodeBuilder = StringBuilder()
    val remotePublicDohBareCodeBuilder = StringBuilder()
    val remotePublicIdentityInjectCodeBuilder = StringBuilder()

    stdout.lineSequence().forEach { line ->
      when (line.trimEnd()) {
        MARKER_ID_U -> {
          section = ProbeSection.ID_U
          seenId = true
          return@forEach
        }
        MARKER_LISTENERS -> {
          section = ProbeSection.LISTENERS
          seenListeners = true
          return@forEach
        }
        MARKER_DDNS_EPOCH -> {
          section = ProbeSection.DDNS_EPOCH
          seenDdns = true
          return@forEach
        }
        MARKER_DDNS_LAST_IPV4 -> {
          section = ProbeSection.DDNS_LAST_IPV4
          seenDdnsLastIpv4 = true
          return@forEach
        }
        MARKER_SUPERVISOR_LOOP_HEARTBEAT -> {
          section = ProbeSection.SUPERVISOR_LOOP_HEARTBEAT
          seenSupervisorLoopHeartbeat = true
          return@forEach
        }
        MARKER_TRAIN_BOT_PID -> {
          section = ProbeSection.TRAIN_BOT_PID
          seenTrainPid = true
          return@forEach
        }
        MARKER_TRAIN_BOT_TUNNEL_ENABLED -> {
          section = ProbeSection.TRAIN_BOT_TUNNEL_ENABLED
          seenTrainTunnelEnabled = true
          return@forEach
        }
        MARKER_TRAIN_BOT_TUNNEL_SUPERVISOR_PID -> {
          section = ProbeSection.TRAIN_BOT_TUNNEL_SUPERVISOR_PID
          seenTrainTunnelSupervisorPid = true
          return@forEach
        }
        MARKER_TRAIN_BOT_TUNNEL_PID -> {
          section = ProbeSection.TRAIN_BOT_TUNNEL_PID
          seenTrainTunnelPid = true
          return@forEach
        }
        MARKER_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL -> {
          section = ProbeSection.TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL
          seenTrainTunnelPublicBaseUrl = true
          return@forEach
        }
        MARKER_TRAIN_BOT_PUBLIC_ROOT_CODE -> {
          section = ProbeSection.TRAIN_BOT_PUBLIC_ROOT_CODE
          seenTrainBotPublicRootCode = true
          return@forEach
        }
        MARKER_TRAIN_BOT_PUBLIC_APP_CODE -> {
          section = ProbeSection.TRAIN_BOT_PUBLIC_APP_CODE
          seenTrainBotPublicAppCode = true
          return@forEach
        }
        MARKER_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE -> {
          section = ProbeSection.TRAIN_BOT_TUNNEL_PROBE_AVAILABLE
          seenTrainBotTunnelProbeAvailable = true
          return@forEach
        }
        MARKER_TRAIN_BOT_HEARTBEAT -> {
          section = ProbeSection.TRAIN_BOT_HEARTBEAT
          seenTrainHeartbeat = true
          return@forEach
        }
        MARKER_TRAIN_BOT_SCHEDULE_REQUIRED -> {
          section = ProbeSection.TRAIN_BOT_SCHEDULE_REQUIRED
          seenTrainScheduleRequired = true
          return@forEach
        }
        MARKER_TRAIN_BOT_SCHEDULE_FRESH -> {
          section = ProbeSection.TRAIN_BOT_SCHEDULE_FRESH
          seenTrainScheduleFresh = true
          return@forEach
        }
        MARKER_TRAIN_BOT_SCHEDULE_SERVICE_DATE -> {
          section = ProbeSection.TRAIN_BOT_SCHEDULE_SERVICE_DATE
          seenTrainScheduleServiceDate = true
          return@forEach
        }
        MARKER_TRAIN_BOT_SCHEDULE_ROWS -> {
          section = ProbeSection.TRAIN_BOT_SCHEDULE_ROWS
          seenTrainScheduleRows = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_PID -> {
          section = ProbeSection.SATIKSME_BOT_PID
          seenSatiksmePid = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_TUNNEL_ENABLED -> {
          section = ProbeSection.SATIKSME_BOT_TUNNEL_ENABLED
          seenSatiksmeTunnelEnabled = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_TUNNEL_SUPERVISOR_PID -> {
          section = ProbeSection.SATIKSME_BOT_TUNNEL_SUPERVISOR_PID
          seenSatiksmeTunnelSupervisorPid = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_TUNNEL_PID -> {
          section = ProbeSection.SATIKSME_BOT_TUNNEL_PID
          seenSatiksmeTunnelPid = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL -> {
          section = ProbeSection.SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL
          seenSatiksmeTunnelPublicBaseUrl = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_PUBLIC_ROOT_CODE -> {
          section = ProbeSection.SATIKSME_BOT_PUBLIC_ROOT_CODE
          seenSatiksmePublicRootCode = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_PUBLIC_APP_CODE -> {
          section = ProbeSection.SATIKSME_BOT_PUBLIC_APP_CODE
          seenSatiksmePublicAppCode = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE -> {
          section = ProbeSection.SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE
          seenSatiksmeTunnelProbeAvailable = true
          return@forEach
        }
        MARKER_SATIKSME_BOT_HEARTBEAT -> {
          section = ProbeSection.SATIKSME_BOT_HEARTBEAT
          seenSatiksmeHeartbeat = true
          return@forEach
        }
        MARKER_SITE_NOTIFIER_PID -> {
          section = ProbeSection.SITE_NOTIFIER_PID
          seenNotifierPid = true
          return@forEach
        }
        MARKER_SITE_NOTIFIER_HEARTBEAT -> {
          section = ProbeSection.SITE_NOTIFIER_HEARTBEAT
          seenNotifierHeartbeat = true
          return@forEach
        }
        MARKER_SITE_NOTIFIER_HELPER_HEALTHY -> {
          section = ProbeSection.SITE_NOTIFIER_HELPER_HEALTHY
          seenNotifierHelperHealthy = true
          return@forEach
        }
        MARKER_SITE_NOTIFIER_HELPER_REASON -> {
          section = ProbeSection.SITE_NOTIFIER_HELPER_REASON
          seenNotifierHelperReason = true
          return@forEach
        }
        MARKER_SUBSCRIPTION_BOT_PID -> {
          section = ProbeSection.SUBSCRIPTION_BOT_PID
          seenSubscriptionPid = true
          return@forEach
        }
        MARKER_SUBSCRIPTION_BOT_HEARTBEAT -> {
          section = ProbeSection.SUBSCRIPTION_BOT_HEARTBEAT
          seenSubscriptionHeartbeat = true
          return@forEach
        }
        MARKER_VPN_HEALTH -> {
          section = ProbeSection.VPN_HEALTH
          seenVpnHealth = true
          return@forEach
        }
        MARKER_VPN_ENABLED_EFFECTIVE -> {
          section = ProbeSection.VPN_ENABLED_EFFECTIVE
          seenVpnEnabledEffective = true
          return@forEach
        }
        MARKER_VPN_TAILSCALED_LIVE -> {
          section = ProbeSection.VPN_TAILSCALED_LIVE
          seenVpnTailscaledLive = true
          return@forEach
        }
        MARKER_VPN_TAILSCALED_SOCK -> {
          section = ProbeSection.VPN_TAILSCALED_SOCK
          seenVpnTailscaledSock = true
          return@forEach
        }
        MARKER_VPN_TAILNET_IPV4 -> {
          section = ProbeSection.VPN_TAILNET_IPV4
          seenVpnTailnetIpv4 = true
          return@forEach
        }
        MARKER_VPN_GUARD_CHAIN_IPV4 -> {
          section = ProbeSection.VPN_GUARD_CHAIN_IPV4
          seenVpnGuardChainIpv4 = true
          return@forEach
        }
        MARKER_VPN_GUARD_CHAIN_IPV6 -> {
          section = ProbeSection.VPN_GUARD_CHAIN_IPV6
          seenVpnGuardChainIpv6 = true
          return@forEach
        }
        MARKER_MANAGEMENT_ENABLED -> {
          section = ProbeSection.MANAGEMENT_ENABLED
          seenManagementEnabled = true
          return@forEach
        }
        MARKER_MANAGEMENT_HEALTHY -> {
          section = ProbeSection.MANAGEMENT_HEALTHY
          seenManagementHealthy = true
          return@forEach
        }
        MARKER_MANAGEMENT_REASON -> {
          section = ProbeSection.MANAGEMENT_REASON
          seenManagementReason = true
          return@forEach
        }
        MARKER_MANAGEMENT_AUTH_CONSISTENT -> {
          section = ProbeSection.MANAGEMENT_AUTH_CONSISTENT
          seenManagementAuthConsistent = true
          return@forEach
        }
        MARKER_MANAGEMENT_AUTH_WARNING_REASON -> {
          section = ProbeSection.MANAGEMENT_AUTH_WARNING_REASON
          seenManagementAuthWarningReason = true
          return@forEach
        }
        MARKER_MANAGEMENT_SSH_LISTENER -> {
          section = ProbeSection.MANAGEMENT_SSH_LISTENER
          seenManagementSshListener = true
          return@forEach
        }
        MARKER_MANAGEMENT_SSH_AUTH_MODE -> {
          section = ProbeSection.MANAGEMENT_SSH_AUTH_MODE
          seenManagementSshAuthMode = true
          return@forEach
        }
        MARKER_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED -> {
          section = ProbeSection.MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED
          seenManagementSshPasswordAuthRequested = true
          return@forEach
        }
        MARKER_MANAGEMENT_SSH_PASSWORD_AUTH_READY -> {
          section = ProbeSection.MANAGEMENT_SSH_PASSWORD_AUTH_READY
          seenManagementSshPasswordAuthReady = true
          return@forEach
        }
        MARKER_MANAGEMENT_SSH_KEY_AUTH_REQUESTED -> {
          section = ProbeSection.MANAGEMENT_SSH_KEY_AUTH_REQUESTED
          seenManagementSshKeyAuthRequested = true
          return@forEach
        }
        MARKER_MANAGEMENT_SSH_KEY_AUTH_READY -> {
          section = ProbeSection.MANAGEMENT_SSH_KEY_AUTH_READY
          seenManagementSshKeyAuthReady = true
          return@forEach
        }
        MARKER_MANAGEMENT_PM_PATH -> {
          section = ProbeSection.MANAGEMENT_PM_PATH
          seenManagementPmPath = true
          return@forEach
        }
        MARKER_MANAGEMENT_AM_PATH -> {
          section = ProbeSection.MANAGEMENT_AM_PATH
          seenManagementAmPath = true
          return@forEach
        }
        MARKER_MANAGEMENT_LOGCAT_PATH -> {
          section = ProbeSection.MANAGEMENT_LOGCAT_PATH
          seenManagementLogcatPath = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIRELESS_DEBUG_ENABLED -> {
          section = ProbeSection.MANAGEMENT_WIRELESS_DEBUG_ENABLED
          seenManagementWirelessDebugEnabled = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT -> {
          section = ProbeSection.MANAGEMENT_WIRELESS_DEBUG_TLS_PORT
          seenManagementWirelessDebugTlsPort = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIRELESS_DEBUG_LIVE -> {
          section = ProbeSection.MANAGEMENT_WIRELESS_DEBUG_LIVE
          seenManagementWirelessDebugLive = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS -> {
          section = ProbeSection.MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS
          seenManagementWirelessDebugLivePorts = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIRELESS_DEBUG_HEALTHY -> {
          section = ProbeSection.MANAGEMENT_WIRELESS_DEBUG_HEALTHY
          seenManagementWirelessDebugHealthy = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIRELESS_DEBUG_REASON -> {
          section = ProbeSection.MANAGEMENT_WIRELESS_DEBUG_REASON
          seenManagementWirelessDebugReason = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIFI_ENABLED -> {
          section = ProbeSection.MANAGEMENT_WIFI_ENABLED
          seenManagementWifiEnabled = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIFI_CONNECTED -> {
          section = ProbeSection.MANAGEMENT_WIFI_CONNECTED
          seenManagementWifiConnected = true
          return@forEach
        }
        MARKER_MANAGEMENT_WIFI_IPV4 -> {
          section = ProbeSection.MANAGEMENT_WIFI_IPV4
          seenManagementWifiIpv4 = true
          return@forEach
        }
        MARKER_MANAGEMENT_MOBILE_IFACE -> {
          section = ProbeSection.MANAGEMENT_MOBILE_IFACE
          seenManagementMobileIface = true
          return@forEach
        }
        MARKER_MANAGEMENT_MOBILE_IPV4 -> {
          section = ProbeSection.MANAGEMENT_MOBILE_IPV4
          seenManagementMobileIpv4 = true
          return@forEach
        }
        MARKER_MANAGEMENT_ACTIVE_TRANSPORT -> {
          section = ProbeSection.MANAGEMENT_ACTIVE_TRANSPORT
          seenManagementActiveTransport = true
          return@forEach
        }
        MARKER_MANAGEMENT_PUBLIC_IPV4_CANDIDATE -> {
          section = ProbeSection.MANAGEMENT_PUBLIC_IPV4_CANDIDATE
          seenManagementPublicIpv4Candidate = true
          return@forEach
        }
        MARKER_MANAGEMENT_NETWORK_FINGERPRINT -> {
          section = ProbeSection.MANAGEMENT_NETWORK_FINGERPRINT
          seenManagementNetworkFingerprint = true
          return@forEach
        }
        MARKER_REMOTE_DOH_TOKENIZED_CODE -> {
          section = ProbeSection.REMOTE_DOH_TOKENIZED_CODE
          seenRemoteDohTokenizedCode = true
          return@forEach
        }
        MARKER_REMOTE_DOH_BARE_CODE -> {
          section = ProbeSection.REMOTE_DOH_BARE_CODE
          seenRemoteDohBareCode = true
          return@forEach
        }
        MARKER_REMOTE_IDENTITY_INJECT_CODE -> {
          section = ProbeSection.REMOTE_IDENTITY_INJECT_CODE
          seenRemoteIdentityInjectCode = true
          return@forEach
        }
        MARKER_REMOTE_PUBLIC_BASE_URL -> {
          section = ProbeSection.REMOTE_PUBLIC_BASE_URL
          seenRemotePublicBaseUrl = true
          return@forEach
        }
        MARKER_REMOTE_PUBLIC_ROOT_CODE -> {
          section = ProbeSection.REMOTE_PUBLIC_ROOT_CODE
          seenRemotePublicRootCode = true
          return@forEach
        }
        MARKER_REMOTE_PUBLIC_PROBE_AVAILABLE -> {
          section = ProbeSection.REMOTE_PUBLIC_PROBE_AVAILABLE
          seenRemotePublicProbeAvailable = true
          return@forEach
        }
        MARKER_REMOTE_PUBLIC_DOH_TOKENIZED_CODE -> {
          section = ProbeSection.REMOTE_PUBLIC_DOH_TOKENIZED_CODE
          seenRemotePublicDohTokenizedCode = true
          return@forEach
        }
        MARKER_REMOTE_PUBLIC_DOH_BARE_CODE -> {
          section = ProbeSection.REMOTE_PUBLIC_DOH_BARE_CODE
          seenRemotePublicDohBareCode = true
          return@forEach
        }
        MARKER_REMOTE_PUBLIC_IDENTITY_INJECT_CODE -> {
          section = ProbeSection.REMOTE_PUBLIC_IDENTITY_INJECT_CODE
          seenRemotePublicIdentityInjectCode = true
          return@forEach
        }
        MARKER_END -> {
          section = ProbeSection.NONE
          seenEnd = true
          return@forEach
        }
      }

      when (section) {
        ProbeSection.ID_U -> idBuilder.appendLine(line)
        ProbeSection.LISTENERS -> listenersBuilder.appendLine(line)
        ProbeSection.DDNS_EPOCH -> ddnsBuilder.appendLine(line)
        ProbeSection.DDNS_LAST_IPV4 -> ddnsLastIpv4Builder.appendLine(line)
        ProbeSection.SUPERVISOR_LOOP_HEARTBEAT -> supervisorLoopHeartbeatBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_PID -> trainPidBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_TUNNEL_ENABLED -> trainTunnelEnabledBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_TUNNEL_SUPERVISOR_PID -> trainTunnelSupervisorPidBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_TUNNEL_PID -> trainTunnelPidBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL -> trainTunnelPublicBaseUrlBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_PUBLIC_ROOT_CODE -> trainPublicRootCodeBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_PUBLIC_APP_CODE -> trainPublicAppCodeBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_TUNNEL_PROBE_AVAILABLE -> trainTunnelProbeAvailableBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_HEARTBEAT -> trainHeartbeatBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_SCHEDULE_REQUIRED -> trainScheduleRequiredBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_SCHEDULE_FRESH -> trainScheduleFreshBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_SCHEDULE_SERVICE_DATE -> trainScheduleServiceDateBuilder.appendLine(line)
        ProbeSection.TRAIN_BOT_SCHEDULE_ROWS -> trainScheduleRowsBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_PID -> satiksmePidBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_TUNNEL_ENABLED -> satiksmeTunnelEnabledBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_TUNNEL_SUPERVISOR_PID -> satiksmeTunnelSupervisorPidBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_TUNNEL_PID -> satiksmeTunnelPidBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL -> satiksmeTunnelPublicBaseUrlBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_PUBLIC_ROOT_CODE -> satiksmePublicRootCodeBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_PUBLIC_APP_CODE -> satiksmePublicAppCodeBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE -> satiksmeTunnelProbeAvailableBuilder.appendLine(line)
        ProbeSection.SATIKSME_BOT_HEARTBEAT -> satiksmeHeartbeatBuilder.appendLine(line)
        ProbeSection.SITE_NOTIFIER_PID -> notifierPidBuilder.appendLine(line)
        ProbeSection.SITE_NOTIFIER_HEARTBEAT -> notifierHeartbeatBuilder.appendLine(line)
        ProbeSection.SITE_NOTIFIER_HELPER_HEALTHY -> notifierHelperHealthyBuilder.appendLine(line)
        ProbeSection.SITE_NOTIFIER_HELPER_REASON -> notifierHelperReasonBuilder.appendLine(line)
        ProbeSection.SUBSCRIPTION_BOT_PID -> subscriptionPidBuilder.appendLine(line)
        ProbeSection.SUBSCRIPTION_BOT_HEARTBEAT -> subscriptionHeartbeatBuilder.appendLine(line)
        ProbeSection.VPN_HEALTH -> vpnHealthBuilder.appendLine(line)
        ProbeSection.VPN_ENABLED_EFFECTIVE -> vpnEnabledEffectiveBuilder.appendLine(line)
        ProbeSection.VPN_TAILSCALED_LIVE -> vpnTailscaledLiveBuilder.appendLine(line)
        ProbeSection.VPN_TAILSCALED_SOCK -> vpnTailscaledSockBuilder.appendLine(line)
        ProbeSection.VPN_TAILNET_IPV4 -> vpnTailnetIpv4Builder.appendLine(line)
        ProbeSection.VPN_GUARD_CHAIN_IPV4 -> vpnGuardChainIpv4Builder.appendLine(line)
        ProbeSection.VPN_GUARD_CHAIN_IPV6 -> vpnGuardChainIpv6Builder.appendLine(line)
        ProbeSection.MANAGEMENT_ENABLED -> managementEnabledBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_HEALTHY -> managementHealthyBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_REASON -> managementReasonBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_AUTH_CONSISTENT -> managementAuthConsistentBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_AUTH_WARNING_REASON -> managementAuthWarningReasonBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_SSH_LISTENER -> managementSshListenerBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_SSH_AUTH_MODE -> managementSshAuthModeBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED -> managementSshPasswordAuthRequestedBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_SSH_PASSWORD_AUTH_READY -> managementSshPasswordAuthReadyBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_SSH_KEY_AUTH_REQUESTED -> managementSshKeyAuthRequestedBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_SSH_KEY_AUTH_READY -> managementSshKeyAuthReadyBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_PM_PATH -> managementPmPathBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_AM_PATH -> managementAmPathBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_LOGCAT_PATH -> managementLogcatPathBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIRELESS_DEBUG_ENABLED -> managementWirelessDebugEnabledBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIRELESS_DEBUG_TLS_PORT -> managementWirelessDebugTlsPortBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIRELESS_DEBUG_LIVE -> managementWirelessDebugLiveBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS -> managementWirelessDebugLivePortsBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIRELESS_DEBUG_HEALTHY -> managementWirelessDebugHealthyBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIRELESS_DEBUG_REASON -> managementWirelessDebugReasonBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIFI_ENABLED -> managementWifiEnabledBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIFI_CONNECTED -> managementWifiConnectedBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_WIFI_IPV4 -> managementWifiIpv4Builder.appendLine(line)
        ProbeSection.MANAGEMENT_MOBILE_IFACE -> managementMobileIfaceBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_MOBILE_IPV4 -> managementMobileIpv4Builder.appendLine(line)
        ProbeSection.MANAGEMENT_ACTIVE_TRANSPORT -> managementActiveTransportBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_PUBLIC_IPV4_CANDIDATE -> managementPublicIpv4CandidateBuilder.appendLine(line)
        ProbeSection.MANAGEMENT_NETWORK_FINGERPRINT -> managementNetworkFingerprintBuilder.appendLine(line)
        ProbeSection.REMOTE_DOH_TOKENIZED_CODE -> remoteDohTokenizedCodeBuilder.appendLine(line)
        ProbeSection.REMOTE_DOH_BARE_CODE -> remoteDohBareCodeBuilder.appendLine(line)
        ProbeSection.REMOTE_IDENTITY_INJECT_CODE -> remoteIdentityInjectCodeBuilder.appendLine(line)
        ProbeSection.REMOTE_PUBLIC_BASE_URL -> remotePublicBaseUrlBuilder.appendLine(line)
        ProbeSection.REMOTE_PUBLIC_ROOT_CODE -> remotePublicRootCodeBuilder.appendLine(line)
        ProbeSection.REMOTE_PUBLIC_PROBE_AVAILABLE -> remotePublicProbeAvailableBuilder.appendLine(line)
        ProbeSection.REMOTE_PUBLIC_DOH_TOKENIZED_CODE -> remotePublicDohTokenizedCodeBuilder.appendLine(line)
        ProbeSection.REMOTE_PUBLIC_DOH_BARE_CODE -> remotePublicDohBareCodeBuilder.appendLine(line)
        ProbeSection.REMOTE_PUBLIC_IDENTITY_INJECT_CODE -> remotePublicIdentityInjectCodeBuilder.appendLine(line)
        ProbeSection.NONE -> Unit
      }
    }

    if (
      !seenId || !seenListeners || !seenDdns || !seenDdnsLastIpv4 || !seenSupervisorLoopHeartbeat || !seenTrainPid || !seenTrainTunnelEnabled || !seenTrainTunnelSupervisorPid ||
      !seenTrainTunnelPid || !seenTrainTunnelPublicBaseUrl || !seenTrainBotPublicRootCode ||
      !seenTrainBotPublicAppCode || !seenTrainBotTunnelProbeAvailable || !seenTrainHeartbeat ||
      !seenTrainScheduleRequired || !seenTrainScheduleFresh || !seenTrainScheduleServiceDate || !seenTrainScheduleRows ||
      !seenSatiksmePid || !seenSatiksmeTunnelEnabled || !seenSatiksmeTunnelSupervisorPid || !seenSatiksmeTunnelPid ||
      !seenSatiksmeTunnelPublicBaseUrl || !seenSatiksmePublicRootCode || !seenSatiksmePublicAppCode ||
      !seenSatiksmeTunnelProbeAvailable || !seenSatiksmeHeartbeat ||
      !seenNotifierPid || !seenNotifierHeartbeat || !seenNotifierHelperHealthy || !seenNotifierHelperReason ||
      !seenSubscriptionPid || !seenSubscriptionHeartbeat ||
      !seenVpnHealth || !seenVpnEnabledEffective ||
      !seenVpnTailscaledLive || !seenVpnTailscaledSock || !seenVpnTailnetIpv4 ||
      !seenVpnGuardChainIpv4 || !seenVpnGuardChainIpv6 ||
      !seenManagementEnabled || !seenManagementHealthy || !seenManagementReason ||
      !seenManagementAuthConsistent || !seenManagementAuthWarningReason || !seenManagementSshListener ||
      !seenManagementSshAuthMode || !seenManagementSshPasswordAuthRequested || !seenManagementSshPasswordAuthReady ||
      !seenManagementSshKeyAuthRequested || !seenManagementSshKeyAuthReady ||
      !seenManagementPmPath || !seenManagementAmPath || !seenManagementLogcatPath ||
      !seenManagementWirelessDebugEnabled || !seenManagementWirelessDebugTlsPort ||
      !seenManagementWirelessDebugLive || !seenManagementWirelessDebugLivePorts ||
      !seenManagementWirelessDebugHealthy || !seenManagementWirelessDebugReason ||
      !seenManagementWifiEnabled || !seenManagementWifiConnected || !seenManagementWifiIpv4 ||
      !seenManagementMobileIface || !seenManagementMobileIpv4 || !seenManagementActiveTransport ||
      !seenManagementPublicIpv4Candidate || !seenManagementNetworkFingerprint ||
      !seenRemoteDohTokenizedCode || !seenRemoteDohBareCode || !seenRemoteIdentityInjectCode ||
      !seenRemotePublicBaseUrl || !seenRemotePublicRootCode || !seenRemotePublicProbeAvailable ||
      !seenRemotePublicDohTokenizedCode || !seenRemotePublicDohBareCode || !seenRemotePublicIdentityInjectCode ||
      !seenEnd
    ) {
      return null
    }

    return ParsedProbe(
      idU = idBuilder.toString(),
      listeners = listenersBuilder.toString(),
      ddnsEpoch = ddnsBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      ddnsLastIpv4 = ddnsLastIpv4Builder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      supervisorLoopHeartbeatEpoch = supervisorLoopHeartbeatBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      trainBotPid = trainPidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      trainBotTunnelEnabled = trainTunnelEnabledBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      trainBotTunnelSupervisorPid = trainTunnelSupervisorPidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      trainBotTunnelPid = trainTunnelPidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      trainBotTunnelPublicBaseUrl = trainTunnelPublicBaseUrlBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      trainBotPublicRootCode = trainPublicRootCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      trainBotPublicAppCode = trainPublicAppCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      trainBotTunnelProbeAvailable = trainTunnelProbeAvailableBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      trainBotHeartbeatEpoch = trainHeartbeatBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      trainBotScheduleRequired = trainScheduleRequiredBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      trainBotScheduleFresh = trainScheduleFreshBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      trainBotScheduleServiceDate = trainScheduleServiceDateBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      trainBotScheduleRows = trainScheduleRowsBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "unknown",
      satiksmeBotPid = satiksmePidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      satiksmeBotTunnelEnabled = satiksmeTunnelEnabledBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      satiksmeBotTunnelSupervisorPid = satiksmeTunnelSupervisorPidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      satiksmeBotTunnelPid = satiksmeTunnelPidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      satiksmeBotTunnelPublicBaseUrl = satiksmeTunnelPublicBaseUrlBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      satiksmeBotPublicRootCode = satiksmePublicRootCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      satiksmeBotPublicAppCode = satiksmePublicAppCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      satiksmeBotTunnelProbeAvailable = satiksmeTunnelProbeAvailableBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      satiksmeBotHeartbeatEpoch = satiksmeHeartbeatBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      siteNotifierPid = notifierPidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      siteNotifierHeartbeatEpoch = notifierHeartbeatBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      siteNotifierHelperHealthy = notifierHelperHealthyBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      siteNotifierHelperReason = notifierHelperReasonBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "unknown",
      subscriptionBotPid = subscriptionPidBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      subscriptionBotHeartbeatEpoch = subscriptionHeartbeatBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      vpnHealth = vpnHealthBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      vpnEnabledEffective = vpnEnabledEffectiveBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      vpnTailscaledLive = vpnTailscaledLiveBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      vpnTailscaledSock = vpnTailscaledSockBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      vpnTailnetIpv4 = vpnTailnetIpv4Builder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      vpnGuardChainIpv4 = vpnGuardChainIpv4Builder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      vpnGuardChainIpv6 = vpnGuardChainIpv6Builder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementEnabled = managementEnabledBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementHealthy = managementHealthyBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementReason = managementReasonBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "unknown",
      managementAuthConsistent = managementAuthConsistentBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "1",
      managementAuthWarningReason = managementAuthWarningReasonBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "ok",
      managementSshListener = managementSshListenerBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementSshAuthMode = managementSshAuthModeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "disabled",
      managementSshPasswordAuthRequested = managementSshPasswordAuthRequestedBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementSshPasswordAuthReady = managementSshPasswordAuthReadyBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementSshKeyAuthRequested = managementSshKeyAuthRequestedBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementSshKeyAuthReady = managementSshKeyAuthReadyBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementPmPath = managementPmPathBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementAmPath = managementAmPathBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementLogcatPath = managementLogcatPathBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementWirelessDebugEnabled = managementWirelessDebugEnabledBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementWirelessDebugTlsPort = managementWirelessDebugTlsPortBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementWirelessDebugLive = managementWirelessDebugLiveBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementWirelessDebugLivePorts = managementWirelessDebugLivePortsBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementWirelessDebugHealthy = managementWirelessDebugHealthyBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementWirelessDebugReason = managementWirelessDebugReasonBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "unknown",
      managementWifiEnabled = managementWifiEnabledBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementWifiConnected = managementWifiConnectedBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      managementWifiIpv4 = managementWifiIpv4Builder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementMobileIface = managementMobileIfaceBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementMobileIpv4 = managementMobileIpv4Builder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementActiveTransport = managementActiveTransportBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "unknown",
      managementPublicIpv4Candidate = managementPublicIpv4CandidateBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      managementNetworkFingerprint = managementNetworkFingerprintBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      remoteDohTokenizedCode = remoteDohTokenizedCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      remoteDohBareCode = remoteDohBareCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      remoteIdentityInjectCode = remoteIdentityInjectCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      remotePublicBaseUrl = remotePublicBaseUrlBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
      remotePublicRootCode = remotePublicRootCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      remotePublicProbeAvailable = remotePublicProbeAvailableBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "0",
      remotePublicDohTokenizedCode = remotePublicDohTokenizedCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      remotePublicDohBareCode = remotePublicDohBareCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000",
      remotePublicIdentityInjectCode = remotePublicIdentityInjectCodeBuilder.lineSequence().firstOrNull { it.isNotBlank() } ?: "000"
    )
  }

  private fun String.hasPort(port: Int): Boolean {
    return lineSequence().any { line ->
      line.contains(":$port ") || line.endsWith(":$port")
    }
  }

  private data class ParsedProbe(
    val idU: String,
    val listeners: String,
    val ddnsEpoch: String,
    val ddnsLastIpv4: String,
    val supervisorLoopHeartbeatEpoch: String,
    val trainBotPid: String,
    val trainBotTunnelEnabled: String,
    val trainBotTunnelSupervisorPid: String,
    val trainBotTunnelPid: String,
    val trainBotTunnelPublicBaseUrl: String,
    val trainBotPublicRootCode: String,
    val trainBotPublicAppCode: String,
    val trainBotTunnelProbeAvailable: String,
    val trainBotHeartbeatEpoch: String,
    val trainBotScheduleRequired: String,
    val trainBotScheduleFresh: String,
    val trainBotScheduleServiceDate: String,
    val trainBotScheduleRows: String,
    val satiksmeBotPid: String,
    val satiksmeBotTunnelEnabled: String,
    val satiksmeBotTunnelSupervisorPid: String,
    val satiksmeBotTunnelPid: String,
    val satiksmeBotTunnelPublicBaseUrl: String,
    val satiksmeBotPublicRootCode: String,
    val satiksmeBotPublicAppCode: String,
    val satiksmeBotTunnelProbeAvailable: String,
    val satiksmeBotHeartbeatEpoch: String,
    val siteNotifierPid: String,
    val siteNotifierHeartbeatEpoch: String,
    val siteNotifierHelperHealthy: String,
    val siteNotifierHelperReason: String,
    val subscriptionBotPid: String,
    val subscriptionBotHeartbeatEpoch: String,
    val vpnHealth: String,
    val vpnEnabledEffective: String,
    val vpnTailscaledLive: String,
    val vpnTailscaledSock: String,
    val vpnTailnetIpv4: String,
    val vpnGuardChainIpv4: String,
    val vpnGuardChainIpv6: String,
    val managementEnabled: String,
    val managementHealthy: String,
    val managementReason: String,
    val managementAuthConsistent: String,
    val managementAuthWarningReason: String,
    val managementSshListener: String,
    val managementSshAuthMode: String,
    val managementSshPasswordAuthRequested: String,
    val managementSshPasswordAuthReady: String,
    val managementSshKeyAuthRequested: String,
    val managementSshKeyAuthReady: String,
    val managementPmPath: String,
    val managementAmPath: String,
    val managementLogcatPath: String,
    val managementWirelessDebugEnabled: String,
    val managementWirelessDebugTlsPort: String,
    val managementWirelessDebugLive: String,
    val managementWirelessDebugLivePorts: String,
    val managementWirelessDebugHealthy: String,
    val managementWirelessDebugReason: String,
    val managementWifiEnabled: String,
    val managementWifiConnected: String,
    val managementWifiIpv4: String,
    val managementMobileIface: String,
    val managementMobileIpv4: String,
    val managementActiveTransport: String,
    val managementPublicIpv4Candidate: String,
    val managementNetworkFingerprint: String,
    val remoteDohTokenizedCode: String,
    val remoteDohBareCode: String,
    val remoteIdentityInjectCode: String,
    val remotePublicBaseUrl: String,
    val remotePublicRootCode: String,
    val remotePublicProbeAvailable: String,
    val remotePublicDohTokenizedCode: String,
    val remotePublicDohBareCode: String,
    val remotePublicIdentityInjectCode: String
  )

  private enum class ProbeSection {
    NONE,
    ID_U,
    LISTENERS,
    DDNS_EPOCH,
    DDNS_LAST_IPV4,
    SUPERVISOR_LOOP_HEARTBEAT,
    TRAIN_BOT_PID,
    TRAIN_BOT_TUNNEL_ENABLED,
    TRAIN_BOT_TUNNEL_SUPERVISOR_PID,
    TRAIN_BOT_TUNNEL_PID,
    TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL,
    TRAIN_BOT_PUBLIC_ROOT_CODE,
    TRAIN_BOT_PUBLIC_APP_CODE,
    TRAIN_BOT_TUNNEL_PROBE_AVAILABLE,
    TRAIN_BOT_HEARTBEAT,
    TRAIN_BOT_SCHEDULE_REQUIRED,
    TRAIN_BOT_SCHEDULE_FRESH,
    TRAIN_BOT_SCHEDULE_SERVICE_DATE,
    TRAIN_BOT_SCHEDULE_ROWS,
    SATIKSME_BOT_PID,
    SATIKSME_BOT_TUNNEL_ENABLED,
    SATIKSME_BOT_TUNNEL_SUPERVISOR_PID,
    SATIKSME_BOT_TUNNEL_PID,
    SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL,
    SATIKSME_BOT_PUBLIC_ROOT_CODE,
    SATIKSME_BOT_PUBLIC_APP_CODE,
    SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE,
    SATIKSME_BOT_HEARTBEAT,
    SITE_NOTIFIER_PID,
    SITE_NOTIFIER_HEARTBEAT,
    SITE_NOTIFIER_HELPER_HEALTHY,
    SITE_NOTIFIER_HELPER_REASON,
    SUBSCRIPTION_BOT_PID,
    SUBSCRIPTION_BOT_HEARTBEAT,
    VPN_HEALTH,
    VPN_ENABLED_EFFECTIVE,
    VPN_TAILSCALED_LIVE,
    VPN_TAILSCALED_SOCK,
    VPN_TAILNET_IPV4,
    VPN_GUARD_CHAIN_IPV4,
    VPN_GUARD_CHAIN_IPV6,
    MANAGEMENT_ENABLED,
    MANAGEMENT_HEALTHY,
    MANAGEMENT_REASON,
    MANAGEMENT_AUTH_CONSISTENT,
    MANAGEMENT_AUTH_WARNING_REASON,
    MANAGEMENT_SSH_LISTENER,
    MANAGEMENT_SSH_AUTH_MODE,
    MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED,
    MANAGEMENT_SSH_PASSWORD_AUTH_READY,
    MANAGEMENT_SSH_KEY_AUTH_REQUESTED,
    MANAGEMENT_SSH_KEY_AUTH_READY,
    MANAGEMENT_PM_PATH,
    MANAGEMENT_AM_PATH,
    MANAGEMENT_LOGCAT_PATH,
    MANAGEMENT_WIRELESS_DEBUG_ENABLED,
    MANAGEMENT_WIRELESS_DEBUG_TLS_PORT,
    MANAGEMENT_WIRELESS_DEBUG_LIVE,
    MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS,
    MANAGEMENT_WIRELESS_DEBUG_HEALTHY,
    MANAGEMENT_WIRELESS_DEBUG_REASON,
    MANAGEMENT_WIFI_ENABLED,
    MANAGEMENT_WIFI_CONNECTED,
    MANAGEMENT_WIFI_IPV4,
    MANAGEMENT_MOBILE_IFACE,
    MANAGEMENT_MOBILE_IPV4,
    MANAGEMENT_ACTIVE_TRANSPORT,
    MANAGEMENT_PUBLIC_IPV4_CANDIDATE,
    MANAGEMENT_NETWORK_FINGERPRINT,
    REMOTE_DOH_TOKENIZED_CODE,
    REMOTE_DOH_BARE_CODE,
    REMOTE_IDENTITY_INJECT_CODE,
    REMOTE_PUBLIC_BASE_URL,
    REMOTE_PUBLIC_ROOT_CODE,
    REMOTE_PUBLIC_PROBE_AVAILABLE,
    REMOTE_PUBLIC_DOH_TOKENIZED_CODE,
    REMOTE_PUBLIC_DOH_BARE_CODE,
    REMOTE_PUBLIC_IDENTITY_INJECT_CODE
  }

  private fun singleQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
  }

  private fun normalizeDohEndpointMode(mode: String): String {
    return mode.trim().lowercase().ifBlank { "native" }
  }

  private fun remoteFrontendViaNginx(mode: String): Boolean {
    return mode == "tokenized" || mode == "dual"
  }

  private fun dohCodeReachableNonRouteMiss(code: String): Boolean {
    val normalized = code.trim()
    if (normalized.length != 3 || !normalized.all(Char::isDigit)) {
      return false
    }
    val numeric = normalized.toIntOrNull() ?: return false
    return numeric in 100..499 && numeric != 404
  }

  private fun httpCodeHealthyForRemoteRoot(code: String): Boolean {
    return when (code.trim()) {
      "200", "301", "302", "303", "307", "308", "401" -> true
      else -> false
    }
  }

  private fun buildProbeCommand(config: StackConfigV1): String {
    val rootfsPath = singleQuote(config.runtime.rootfsPath)
    val supervisorStateFile = singleQuote("/data/data/lv.jolkins.pixelorchestrator/files/stack-store/orchestrator-state-v1.json")
    val trainEnvFile = singleQuote(config.trainBot.envFile)
    val trainPidFile = singleQuote("${config.trainBot.runtimeRoot}/run/train-bot.pid")
    val trainTunnelSupervisorPidFile = singleQuote("${config.trainBot.runtimeRoot}/run/train-web-tunnel-service-loop.pid")
    val trainTunnelPidFile = singleQuote("${config.trainBot.runtimeRoot}/run/train-bot-cloudflared.pid")
    val trainHeartbeatFile = singleQuote("${config.trainBot.runtimeRoot}/run/heartbeat.epoch")
    val trainRuntimeRoot = singleQuote(config.trainBot.runtimeRoot)
    val trainScheduleDir = singleQuote(config.trainBot.scheduleDir)
    val satiksmeEnvFile = singleQuote(config.satiksmeBot.envFile)
    val satiksmePidFile = singleQuote("${config.satiksmeBot.runtimeRoot}/run/satiksme-bot.pid")
    val satiksmeTunnelSupervisorPidFile = singleQuote("${config.satiksmeBot.runtimeRoot}/run/satiksme-web-tunnel-service-loop.pid")
    val satiksmeTunnelPidFile = singleQuote("${config.satiksmeBot.runtimeRoot}/run/satiksme-bot-cloudflared.pid")
    val satiksmeHeartbeatFile = singleQuote("${config.satiksmeBot.runtimeRoot}/run/heartbeat.epoch")
    val notifierPidFile = singleQuote("${config.siteNotifier.runtimeRoot}/run/site-notifier.pid")
    val notifierHeartbeatFile = singleQuote("${config.siteNotifier.runtimeRoot}/run/heartbeat.epoch")
    val notifierHealthScript = singleQuote("/data/local/pixel-stack/bin/pixel-notifier-health.sh")
    val subscriptionPidFile = singleQuote("${config.subscriptionBot.runtimeRoot}/run/subscription-bot.pid")
    val subscriptionHeartbeatFile = singleQuote("${config.subscriptionBot.runtimeRoot}/run/heartbeat.epoch")
    val dohEndpointMode = singleQuote(normalizeDohEndpointMode(config.remote.dohEndpointMode))
    val dohPathToken = singleQuote(config.remote.dohPathToken.trim())
    val remotePublicBaseUrl = singleQuote(
      if (config.remote.hostname.isBlank()) {
        ""
      } else if (config.remote.httpsPort == 443) {
        "https://${config.remote.hostname}"
      } else {
        "https://${config.remote.hostname}:${config.remote.httpsPort}"
      }
    )
    return """
      set +e
      rootfs_path=$rootfsPath
      resolve_probe_curl() {
        if command -v curl >/dev/null 2>&1; then
          printf 'native:%s\n' "$(command -v curl 2>/dev/null || true)"
          return 0
        fi
        if [ -n "${'$'}rootfs_path" ] && [ -x "${'$'}rootfs_path/usr/bin/curl" ] && [ -x "${'$'}rootfs_path/usr/bin/env" ] && chroot "${'$'}rootfs_path" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -V >/dev/null 2>&1; then
          printf 'chroot:%s\n' "${'$'}rootfs_path"
          return 0
        fi
        return 1
      }
      probe_http_code() {
        probe_spec="${'$'}1"
        probe_url="${'$'}2"
        probe_timeout="${'$'}3"
        probe_resolve_host="${'$'}{4:-}"
        probe_resolve_port="${'$'}{5:-}"
        probe_resolve_ip="${'$'}{6:-}"
        probe_resolve_args=""
        if [ -n "${'$'}probe_resolve_host" ] && [ -n "${'$'}probe_resolve_port" ] && [ -n "${'$'}probe_resolve_ip" ]; then
          probe_resolve_args="--resolve ${'$'}probe_resolve_host:${'$'}probe_resolve_port:${'$'}probe_resolve_ip"
        fi
        case "${'$'}probe_spec" in
          native:*)
            probe_bin=${'$'}{probe_spec#native:}
            probe_code=${'$'}("${'$'}probe_bin" -ksS -o /dev/null -w '%{http_code}' --max-time "${'$'}probe_timeout" ${'$'}probe_resolve_args "${'$'}probe_url" 2>/dev/null || true)
            ;;
          chroot:*)
            probe_rootfs=${'$'}{probe_spec#chroot:}
            probe_code=${'$'}(chroot "${'$'}probe_rootfs" /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/curl -ksS -o /dev/null -w '%{http_code}' --max-time "${'$'}probe_timeout" ${'$'}probe_resolve_args "${'$'}probe_url" 2>/dev/null || true)
            ;;
          *)
            probe_code="000"
            ;;
        esac
        case "${'$'}probe_code" in
          ""|"000000") probe_code="000" ;;
        esac
        printf '%s' "${'$'}probe_code"
      }
      scan_pid_by_target() {
        scan_target="${'$'}1"
        scan_target_base=${'$'}(basename "${'$'}scan_target")
        ps -A -o PID=,NAME=,ARGS= 2>/dev/null | awk -v pat="${'$'}scan_target" -v target_base="${'$'}scan_target_base" '
          function starts_with(value, prefix) { return index(value, prefix) == 1 }
          function next_is_boundary(value, prefix_len) {
            c = substr(value, prefix_len + 1, 1)
            return c == "" || c == " "
          }
          {
            pid = $1
            name = $2
            args = ""
            if (NF >= 3) {
              args = substr($0, index($0, $3))
            }
            if (name == target_base ||
              args == pat ||
              (starts_with(args, pat) && next_is_boundary(args, length(pat))) ||
              (starts_with(args, "sh " pat) && next_is_boundary(args, length("sh " pat))) ||
              (starts_with(args, target_base) && next_is_boundary(args, length(target_base)))) {
              print pid
              exit
            }
          }
        ' | tr -d '\r'
      }
      printf '$MARKER_ID_U\n'
      id -u 2>/dev/null || true
      printf '$MARKER_LISTENERS\n'
      ss -ltn 2>/dev/null || true
      printf '$MARKER_DDNS_EPOCH\n'
      if [ -f /data/local/pixel-stack/run/ddns-last-sync-epoch ]; then
        cat /data/local/pixel-stack/run/ddns-last-sync-epoch 2>/dev/null || true
      fi
      printf '$MARKER_DDNS_LAST_IPV4\n'
      if [ -f /data/local/pixel-stack/run/ddns-last-ipv4 ]; then
        cat /data/local/pixel-stack/run/ddns-last-ipv4 2>/dev/null || true
      fi
      printf '$MARKER_SUPERVISOR_LOOP_HEARTBEAT\n'
      if [ -r $supervisorStateFile ]; then
        sed -n 's/.*"supervisorLoopHeartbeatEpochSeconds"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' $supervisorStateFile 2>/dev/null | sed -n '1p'
      fi
      printf '$MARKER_TRAIN_BOT_PID\n'
      train_pid=""
      if [ -r $trainPidFile ]; then
        train_pid=${'$'}(sed -n '1p' $trainPidFile 2>/dev/null | tr -d '\r')
      fi
      if [ -z "${'$'}train_pid" ] || ! kill -0 "${'$'}train_pid" >/dev/null 2>&1; then
        train_pid=${'$'}(
          ps -A 2>/dev/null | awk '((${'$'}NF=="train-bot") || index(${'$'}NF,"train-bot.")==1) {print ${'$'}2; exit}' | tr -d '\r'
        )
      fi
      if [ -n "${'$'}train_pid" ] && kill -0 "${'$'}train_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}train_pid"
      fi
      printf '$MARKER_TRAIN_BOT_TUNNEL_ENABLED\n'
      train_tunnel_enabled="0"
      train_tunnel_public_base_url=""
      if [ -r $trainEnvFile ]; then
        while IFS= read -r line || [ -n "${'$'}line" ]; do
          case "${'$'}line" in
            ''|'#'*) continue ;;
            *=*) ;;
            *) continue ;;
          esac
          key=${'$'}{line%%=*}
          value=${'$'}{line#*=}
          case "${'$'}value" in
            \"*\") value=${'$'}{value#\"}; value=${'$'}{value%\"} ;;
            \'*\') value=${'$'}{value#\'}; value=${'$'}{value%\'} ;;
          esac
          case "${'$'}key" in
            TRAIN_WEB_TUNNEL_ENABLED)
              case "${'$'}value" in
                1|true|TRUE|yes|YES|on|ON) train_tunnel_enabled="1" ;;
                *) train_tunnel_enabled="0" ;;
              esac
              ;;
            TRAIN_WEB_PUBLIC_BASE_URL)
              train_tunnel_public_base_url="${'$'}value"
              ;;
          esac
        done < $trainEnvFile
      fi
      printf '%s\n' "${'$'}train_tunnel_enabled"
      printf '$MARKER_TRAIN_BOT_TUNNEL_SUPERVISOR_PID\n'
      train_tunnel_supervisor_pid=""
      if [ "${'$'}train_tunnel_enabled" = "1" ] && [ -r $trainTunnelSupervisorPidFile ]; then
        train_tunnel_supervisor_pid=${'$'}(sed -n '1p' $trainTunnelSupervisorPidFile 2>/dev/null | tr -d '\r')
      fi
      if [ "${'$'}train_tunnel_enabled" = "1" ] && { [ -z "${'$'}train_tunnel_supervisor_pid" ] || ! kill -0 "${'$'}train_tunnel_supervisor_pid" >/dev/null 2>&1; }; then
        train_tunnel_supervisor_pid=${'$'}(scan_pid_by_target /data/local/pixel-stack/apps/train-bot/bin/train-web-tunnel-service-loop)
      fi
      if [ -n "${'$'}train_tunnel_supervisor_pid" ] && kill -0 "${'$'}train_tunnel_supervisor_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}train_tunnel_supervisor_pid"
      fi
      printf '$MARKER_TRAIN_BOT_TUNNEL_PID\n'
      train_tunnel_pid=""
      if [ "${'$'}train_tunnel_enabled" = "1" ] && [ -r $trainTunnelPidFile ]; then
        train_tunnel_pid=${'$'}(sed -n '1p' $trainTunnelPidFile 2>/dev/null | tr -d '\r')
      fi
      if [ "${'$'}train_tunnel_enabled" = "1" ] && { [ -z "${'$'}train_tunnel_pid" ] || ! kill -0 "${'$'}train_tunnel_pid" >/dev/null 2>&1; }; then
        train_tunnel_pid=${'$'}(scan_pid_by_target /data/local/pixel-stack/apps/train-bot/bin/cloudflared)
      fi
      if [ -n "${'$'}train_tunnel_pid" ] && kill -0 "${'$'}train_tunnel_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}train_tunnel_pid"
      fi
      printf '$MARKER_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL\n'
      printf '%s\n' "${'$'}train_tunnel_public_base_url"
      train_public_root_code="000"
      train_public_app_code="000"
      train_tunnel_probe_available="0"
      train_public_curl_spec="$(resolve_probe_curl 2>/dev/null || true)"
      if [ "${'$'}train_tunnel_enabled" = "1" ] && [ -n "${'$'}train_tunnel_public_base_url" ] && [ -n "${'$'}train_public_curl_spec" ]; then
        train_tunnel_probe_available="1"
        train_public_root_url=${'$'}(printf '%s' "${'$'}train_tunnel_public_base_url" | sed 's#/*$##')
        train_public_app_url="${'$'}train_public_root_url/app"
        train_public_root_code=${'$'}(probe_http_code "${'$'}train_public_curl_spec" "${'$'}train_public_root_url/" 8)
        train_public_app_code=${'$'}(probe_http_code "${'$'}train_public_curl_spec" "${'$'}train_public_app_url" 8)
      fi
      printf '$MARKER_TRAIN_BOT_PUBLIC_ROOT_CODE\n'
      printf '%s\n' "${'$'}train_public_root_code"
      printf '$MARKER_TRAIN_BOT_PUBLIC_APP_CODE\n'
      printf '%s\n' "${'$'}train_public_app_code"
      printf '$MARKER_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE\n'
      printf '%s\n' "${'$'}train_tunnel_probe_available"
      printf '$MARKER_TRAIN_BOT_HEARTBEAT\n'
      if [ -f $trainHeartbeatFile ]; then
        cat $trainHeartbeatFile 2>/dev/null || true
      fi
      train_tz="Europe/Riga"
      train_scraper_daily_hour="3"
      train_db_path=""
      if [ -r $trainEnvFile ]; then
        while IFS= read -r line || [ -n "${'$'}line" ]; do
          case "${'$'}line" in
            ''|'#'*) continue ;;
            *=*) ;;
            *) continue ;;
          esac
          key=${'$'}{line%%=*}
          value=${'$'}{line#*=}
          case "${'$'}value" in
            \"*\") value=${'$'}{value#\"}; value=${'$'}{value%\"} ;;
            \'*\') value=${'$'}{value#\'}; value=${'$'}{value%\'} ;;
          esac
          case "${'$'}key" in
            TZ) train_tz="${'$'}value" ;;
            SCRAPER_DAILY_HOUR) train_scraper_daily_hour="${'$'}value" ;;
            DB_PATH) train_db_path="${'$'}value" ;;
          esac
        done < $trainEnvFile
      fi
      case "${'$'}train_tz" in
        "") train_tz="Europe/Riga" ;;
      esac
      case "${'$'}train_scraper_daily_hour" in
        ''|*[!0-9]*) train_scraper_daily_hour=3 ;;
      esac
      if [ "${'$'}train_scraper_daily_hour" -lt 0 ] || [ "${'$'}train_scraper_daily_hour" -gt 23 ]; then
        train_scraper_daily_hour=3
      fi
      train_service_date=${'$'}(TZ="${'$'}train_tz" date +%F 2>/dev/null || TZ=Europe/Riga date +%F 2>/dev/null || date +%F)
      train_local_hour=${'$'}(TZ="${'$'}train_tz" date +%H 2>/dev/null || TZ=Europe/Riga date +%H 2>/dev/null || date +%H)
      case "${'$'}train_local_hour" in
        ''|*[!0-9]*) train_local_hour=0 ;;
      esac
      train_schedule_required=0
      if [ "${'$'}train_local_hour" -ge "${'$'}train_scraper_daily_hour" ]; then
        train_schedule_required=1
      fi
      train_schedule_path=$trainScheduleDir/"${'$'}{train_service_date}.json"
      train_schedule_fresh=0
      if [ -s "${'$'}train_schedule_path" ]; then
        train_schedule_fresh=1
      fi
      if [ -n "${'$'}train_db_path" ]; then
        case "${'$'}train_db_path" in
          /*) train_runtime_db_path="${'$'}train_db_path" ;;
          ./*) train_runtime_db_path=$trainRuntimeRoot/"${'$'}{train_db_path#./}" ;;
          *) train_runtime_db_path=$trainRuntimeRoot/"${'$'}train_db_path" ;;
        esac
      else
        train_runtime_db_path=$trainRuntimeRoot/"train_bot.db"
      fi
      train_sqlite3_bin="${'$'}(command -v sqlite3 2>/dev/null || true)"
      train_schedule_rows="unknown"
      if [ -n "${'$'}train_sqlite3_bin" ] && [ -f "${'$'}train_runtime_db_path" ]; then
        train_schedule_rows=${'$'}("${'$'}train_sqlite3_bin" "${'$'}train_runtime_db_path" "select count(*) from train_instances where service_date='${'$'}train_service_date';" 2>/dev/null || printf 'unknown\n')
        train_schedule_rows=${'$'}(printf '%s' "${'$'}train_schedule_rows" | tr -d '\r')
        case "${'$'}train_schedule_rows" in
          "") train_schedule_rows="unknown" ;;
        esac
      fi
      printf '$MARKER_TRAIN_BOT_SCHEDULE_REQUIRED\n'
      printf '%s\n' "${'$'}train_schedule_required"
      printf '$MARKER_TRAIN_BOT_SCHEDULE_FRESH\n'
      printf '%s\n' "${'$'}train_schedule_fresh"
      printf '$MARKER_TRAIN_BOT_SCHEDULE_SERVICE_DATE\n'
      printf '%s\n' "${'$'}train_service_date"
      printf '$MARKER_TRAIN_BOT_SCHEDULE_ROWS\n'
      printf '%s\n' "${'$'}train_schedule_rows"
      printf '$MARKER_SATIKSME_BOT_PID\n'
      satiksme_pid=""
      if [ -r $satiksmePidFile ]; then
        satiksme_pid=${'$'}(sed -n '1p' $satiksmePidFile 2>/dev/null | tr -d '\r')
      fi
      if [ -z "${'$'}satiksme_pid" ] || ! kill -0 "${'$'}satiksme_pid" >/dev/null 2>&1; then
        satiksme_pid=${'$'}(
          ps -A 2>/dev/null | awk '((${'$'}NF=="satiksme-bot") || index(${'$'}NF,"satiksme-bot.")==1) {print ${'$'}2; exit}' | tr -d '\r'
        )
      fi
      if [ -n "${'$'}satiksme_pid" ] && kill -0 "${'$'}satiksme_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}satiksme_pid"
      fi
      printf '$MARKER_SATIKSME_BOT_TUNNEL_ENABLED\n'
      satiksme_tunnel_enabled="0"
      satiksme_tunnel_public_base_url=""
      if [ -r $satiksmeEnvFile ]; then
        while IFS= read -r line || [ -n "${'$'}line" ]; do
          case "${'$'}line" in
            ''|'#'*) continue ;;
            *=*) ;;
            *) continue ;;
          esac
          key=${'$'}{line%%=*}
          value=${'$'}{line#*=}
          case "${'$'}value" in
            \"*\") value=${'$'}{value#\"}; value=${'$'}{value%\"} ;;
            \'*\') value=${'$'}{value#\'}; value=${'$'}{value%\'} ;;
          esac
          case "${'$'}key" in
            SATIKSME_WEB_TUNNEL_ENABLED)
              case "${'$'}value" in
                1|true|TRUE|yes|YES|on|ON) satiksme_tunnel_enabled="1" ;;
                *) satiksme_tunnel_enabled="0" ;;
              esac
              ;;
            SATIKSME_WEB_PUBLIC_BASE_URL)
              satiksme_tunnel_public_base_url="${'$'}value"
              ;;
          esac
        done < $satiksmeEnvFile
      fi
      printf '%s\n' "${'$'}satiksme_tunnel_enabled"
      printf '$MARKER_SATIKSME_BOT_TUNNEL_SUPERVISOR_PID\n'
      satiksme_tunnel_supervisor_pid=""
      if [ "${'$'}satiksme_tunnel_enabled" = "1" ] && [ -r $satiksmeTunnelSupervisorPidFile ]; then
        satiksme_tunnel_supervisor_pid=${'$'}(sed -n '1p' $satiksmeTunnelSupervisorPidFile 2>/dev/null | tr -d '\r')
      fi
      if [ "${'$'}satiksme_tunnel_enabled" = "1" ] && { [ -z "${'$'}satiksme_tunnel_supervisor_pid" ] || ! kill -0 "${'$'}satiksme_tunnel_supervisor_pid" >/dev/null 2>&1; }; then
        satiksme_tunnel_supervisor_pid=${'$'}(scan_pid_by_target /data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-web-tunnel-service-loop)
      fi
      if [ -n "${'$'}satiksme_tunnel_supervisor_pid" ] && kill -0 "${'$'}satiksme_tunnel_supervisor_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}satiksme_tunnel_supervisor_pid"
      fi
      printf '$MARKER_SATIKSME_BOT_TUNNEL_PID\n'
      satiksme_tunnel_pid=""
      if [ "${'$'}satiksme_tunnel_enabled" = "1" ] && [ -r $satiksmeTunnelPidFile ]; then
        satiksme_tunnel_pid=${'$'}(sed -n '1p' $satiksmeTunnelPidFile 2>/dev/null | tr -d '\r')
      fi
      if [ "${'$'}satiksme_tunnel_enabled" = "1" ] && { [ -z "${'$'}satiksme_tunnel_pid" ] || ! kill -0 "${'$'}satiksme_tunnel_pid" >/dev/null 2>&1; }; then
        satiksme_tunnel_pid=${'$'}(scan_pid_by_target /data/local/pixel-stack/apps/satiksme-bot/bin/cloudflared)
      fi
      if [ -n "${'$'}satiksme_tunnel_pid" ] && kill -0 "${'$'}satiksme_tunnel_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}satiksme_tunnel_pid"
      fi
      printf '$MARKER_SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL\n'
      printf '%s\n' "${'$'}satiksme_tunnel_public_base_url"
      satiksme_public_root_code="000"
      satiksme_public_app_code="000"
      satiksme_tunnel_probe_available="0"
      satiksme_public_curl_spec="$(resolve_probe_curl 2>/dev/null || true)"
      if [ "${'$'}satiksme_tunnel_enabled" = "1" ] && [ -n "${'$'}satiksme_tunnel_public_base_url" ] && [ -n "${'$'}satiksme_public_curl_spec" ]; then
        satiksme_tunnel_probe_available="1"
        satiksme_public_root_url=${'$'}(printf '%s' "${'$'}satiksme_tunnel_public_base_url" | sed 's#/*$##')
        satiksme_public_app_url="${'$'}satiksme_public_root_url/app"
        satiksme_public_root_code=${'$'}(probe_http_code "${'$'}satiksme_public_curl_spec" "${'$'}satiksme_public_root_url/" 8)
        satiksme_public_app_code=${'$'}(probe_http_code "${'$'}satiksme_public_curl_spec" "${'$'}satiksme_public_app_url" 8)
      fi
      printf '$MARKER_SATIKSME_BOT_PUBLIC_ROOT_CODE\n'
      printf '%s\n' "${'$'}satiksme_public_root_code"
      printf '$MARKER_SATIKSME_BOT_PUBLIC_APP_CODE\n'
      printf '%s\n' "${'$'}satiksme_public_app_code"
      printf '$MARKER_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE\n'
      printf '%s\n' "${'$'}satiksme_tunnel_probe_available"
      printf '$MARKER_SATIKSME_BOT_HEARTBEAT\n'
      if [ -f $satiksmeHeartbeatFile ]; then
        cat $satiksmeHeartbeatFile 2>/dev/null || true
      fi
      printf '$MARKER_SITE_NOTIFIER_PID\n'
      notifier_pid=""
      if [ -r $notifierPidFile ]; then
        notifier_pid=${'$'}(sed -n '1p' $notifierPidFile 2>/dev/null | tr -d '\r')
      fi
      if [ -n "${'$'}notifier_pid" ] && kill -0 "${'$'}notifier_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}notifier_pid"
      fi
      printf '$MARKER_SITE_NOTIFIER_HEARTBEAT\n'
      if [ -f $notifierHeartbeatFile ]; then
        cat $notifierHeartbeatFile 2>/dev/null || true
      fi
      notifier_health_output=""
      set +e
      notifier_health_output=${'$'}(sh $notifierHealthScript 2>&1)
      notifier_health_rc=${'$'}?
      set -e
      printf '$MARKER_SITE_NOTIFIER_HELPER_HEALTHY\n'
      if [ "${'$'}notifier_health_rc" -eq 0 ]; then
        printf '1\n'
      else
        printf '0\n'
      fi
      printf '$MARKER_SITE_NOTIFIER_HELPER_REASON\n'
      notifier_health_reason=${'$'}(printf '%s\n' "${'$'}notifier_health_output" | sed -n '1p' | tr -d '\r')
      case "${'$'}notifier_health_reason" in
        healthy:\ *) notifier_health_reason=${'$'}{notifier_health_reason#healthy: } ;;
        unhealthy:\ *) notifier_health_reason=${'$'}{notifier_health_reason#unhealthy: } ;;
        healthy:*) notifier_health_reason=${'$'}{notifier_health_reason#healthy:} ;;
        unhealthy:*) notifier_health_reason=${'$'}{notifier_health_reason#unhealthy:} ;;
      esac
      if [ -z "${'$'}notifier_health_reason" ]; then
        if [ "${'$'}notifier_health_rc" -eq 0 ]; then
          notifier_health_reason="ok"
        else
          notifier_health_reason="unknown"
        fi
      fi
      printf '%s\n' "${'$'}notifier_health_reason"
      printf '$MARKER_SUBSCRIPTION_BOT_PID\n'
      subscription_pid=""
      if [ -r $subscriptionPidFile ]; then
        subscription_pid=${'$'}(sed -n '1p' $subscriptionPidFile 2>/dev/null | tr -d '\r')
      fi
      if [ -z "${'$'}subscription_pid" ] || ! kill -0 "${'$'}subscription_pid" >/dev/null 2>&1; then
        subscription_pid=${'$'}(
          ps -A 2>/dev/null | awk '((${'$'}NF=="subscription-bot") || index(${'$'}NF,"subscription-bot.")==1) {print ${'$'}2; exit}' | tr -d '\r'
        )
      fi
      if [ -n "${'$'}subscription_pid" ] && kill -0 "${'$'}subscription_pid" >/dev/null 2>&1; then
        printf '%s\n' "${'$'}subscription_pid"
      fi
      printf '$MARKER_SUBSCRIPTION_BOT_HEARTBEAT\n'
      if [ -f $subscriptionHeartbeatFile ]; then
        cat $subscriptionHeartbeatFile 2>/dev/null || true
      fi
      management_report=""
      set +e
      management_report=${'$'}(PIXEL_MANAGEMENT_HEALTH_REPORT=1 sh /data/local/pixel-stack/bin/pixel-management-health.sh --report 2>/dev/null)
      management_health_rc=${'$'}?
      set -e
      management_extract() {
        key="${'$'}1"
        line=${'$'}(printf '%s\n' "${'$'}management_report" | grep -m 1 "^${'$'}key=" 2>/dev/null || true)
        case "${'$'}line" in
          "${'$'}key="*)
            printf '%s\n' "${'$'}{line#*=}"
            ;;
        esac
      }
      vpn_health_value=${'$'}(management_extract vpn_health)
      if [ -z "${'$'}vpn_health_value" ]; then
        if [ ${if (config.vpn.enabled || (config.modules["vpn"]?.enabled ?: false)) "1" else "0"} -eq 1 ]; then
          vpn_health_value="0"
        else
          vpn_health_value="1"
        fi
      fi
      management_enabled_value=${'$'}(management_extract management_enabled)
      if [ -z "${'$'}management_enabled_value" ]; then
        if [ ${if (config.vpn.enabled || (config.modules["vpn"]?.enabled ?: false)) "1" else "0"} -eq 1 ]; then
          management_enabled_value="1"
        else
          management_enabled_value="0"
        fi
      fi
      management_healthy_value=${'$'}(management_extract management_healthy)
      if [ -z "${'$'}management_healthy_value" ]; then
        if [ "${'$'}management_enabled_value" = "1" ] && [ "${'$'}management_health_rc" -ne 0 ]; then
          management_healthy_value="0"
        else
          management_healthy_value="1"
        fi
      fi
      management_reason_value=${'$'}(management_extract management_reason)
      if [ -z "${'$'}management_reason_value" ]; then
        if [ "${'$'}management_enabled_value" = "1" ]; then
          management_reason_value="unknown"
        else
          management_reason_value="disabled"
        fi
      fi
      printf '$MARKER_VPN_HEALTH\n'
      printf '%s\n' "${'$'}vpn_health_value"
      printf '$MARKER_VPN_ENABLED_EFFECTIVE\n'
      printf '%s\n' "${'$'}(management_extract vpn_enabled)"
      printf '$MARKER_VPN_TAILSCALED_LIVE\n'
      printf '%s\n' "${'$'}(management_extract tailscaled_live)"
      printf '$MARKER_VPN_TAILSCALED_SOCK\n'
      printf '%s\n' "${'$'}(management_extract tailscaled_sock)"
      printf '$MARKER_VPN_TAILNET_IPV4\n'
      printf '%s\n' "${'$'}(management_extract tailnet_ipv4)"
      printf '$MARKER_VPN_GUARD_CHAIN_IPV4\n'
      printf '%s\n' "${'$'}(management_extract guard_chain_ipv4)"
      printf '$MARKER_VPN_GUARD_CHAIN_IPV6\n'
      printf '%s\n' "${'$'}(management_extract guard_chain_ipv6)"
      printf '$MARKER_MANAGEMENT_ENABLED\n'
      printf '%s\n' "${'$'}management_enabled_value"
      printf '$MARKER_MANAGEMENT_HEALTHY\n'
      printf '%s\n' "${'$'}management_healthy_value"
      printf '$MARKER_MANAGEMENT_REASON\n'
      printf '%s\n' "${'$'}management_reason_value"
      printf '$MARKER_MANAGEMENT_AUTH_CONSISTENT\n'
      printf '%s\n' "${'$'}(management_extract management_auth_consistent)"
      printf '$MARKER_MANAGEMENT_AUTH_WARNING_REASON\n'
      printf '%s\n' "${'$'}(management_extract management_auth_warning_reason)"
      printf '$MARKER_MANAGEMENT_SSH_LISTENER\n'
      printf '%s\n' "${'$'}(management_extract ssh_listener)"
      printf '$MARKER_MANAGEMENT_SSH_AUTH_MODE\n'
      printf '%s\n' "${'$'}(management_extract ssh_auth_mode)"
      printf '$MARKER_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED\n'
      printf '%s\n' "${'$'}(management_extract ssh_password_auth_requested)"
      printf '$MARKER_MANAGEMENT_SSH_PASSWORD_AUTH_READY\n'
      printf '%s\n' "${'$'}(management_extract ssh_password_auth_ready)"
      printf '$MARKER_MANAGEMENT_SSH_KEY_AUTH_REQUESTED\n'
      printf '%s\n' "${'$'}(management_extract ssh_key_auth_requested)"
      printf '$MARKER_MANAGEMENT_SSH_KEY_AUTH_READY\n'
      printf '%s\n' "${'$'}(management_extract ssh_key_auth_ready)"
      printf '$MARKER_MANAGEMENT_PM_PATH\n'
      printf '%s\n' "${'$'}(management_extract pm_path)"
      printf '$MARKER_MANAGEMENT_AM_PATH\n'
      printf '%s\n' "${'$'}(management_extract am_path)"
      printf '$MARKER_MANAGEMENT_LOGCAT_PATH\n'
      printf '%s\n' "${'$'}(management_extract logcat_path)"
      printf '$MARKER_MANAGEMENT_WIRELESS_DEBUG_ENABLED\n'
      printf '%s\n' "${'$'}(management_extract wireless_debug_enabled)"
      printf '$MARKER_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT\n'
      printf '%s\n' "${'$'}(management_extract wireless_debug_tls_port)"
      printf '$MARKER_MANAGEMENT_WIRELESS_DEBUG_LIVE\n'
      printf '%s\n' "${'$'}(management_extract wireless_debug_live)"
      printf '$MARKER_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS\n'
      printf '%s\n' "${'$'}(management_extract wireless_debug_live_ports)"
      printf '$MARKER_MANAGEMENT_WIRELESS_DEBUG_HEALTHY\n'
      printf '%s\n' "${'$'}(management_extract wireless_debug_healthy)"
      printf '$MARKER_MANAGEMENT_WIRELESS_DEBUG_REASON\n'
      printf '%s\n' "${'$'}(management_extract wireless_debug_reason)"
      printf '$MARKER_MANAGEMENT_WIFI_ENABLED\n'
      printf '%s\n' "${'$'}(management_extract wifi_enabled)"
      printf '$MARKER_MANAGEMENT_WIFI_CONNECTED\n'
      printf '%s\n' "${'$'}(management_extract wifi_connected)"
      printf '$MARKER_MANAGEMENT_WIFI_IPV4\n'
      printf '%s\n' "${'$'}(management_extract wifi_ipv4)"
      printf '$MARKER_MANAGEMENT_MOBILE_IFACE\n'
      printf '%s\n' "${'$'}(management_extract mobile_iface)"
      printf '$MARKER_MANAGEMENT_MOBILE_IPV4\n'
      printf '%s\n' "${'$'}(management_extract mobile_ipv4)"
      printf '$MARKER_MANAGEMENT_ACTIVE_TRANSPORT\n'
      printf '%s\n' "${'$'}(management_extract active_transport)"
      printf '$MARKER_MANAGEMENT_PUBLIC_IPV4_CANDIDATE\n'
      printf '%s\n' "${'$'}(management_extract public_ipv4_candidate)"
      printf '$MARKER_MANAGEMENT_NETWORK_FINGERPRINT\n'
      printf '%s\n' "${'$'}(management_extract network_fingerprint)"
      remote_doh_tokenized_code="000"
      remote_doh_bare_code="000"
      remote_identity_inject_code="000"
      remote_public_base=$remotePublicBaseUrl
      remote_public_root_code="000"
      remote_public_probe_available="0"
      remote_public_doh_tokenized_code="000"
      remote_public_doh_bare_code="000"
      remote_public_identity_inject_code="000"
      remote_doh_curl_spec="$(resolve_probe_curl 2>/dev/null || true)"
      if [ ${if (config.remote.dohEnabled) "1" else "0"} -eq 1 ] && [ -n "${'$'}remote_doh_curl_spec" ]; then
        remote_doh_mode=$dohEndpointMode
        remote_doh_token=$dohPathToken
        remote_doh_base="https://127.0.0.1:${config.remote.httpsPort}"
        case "${'$'}remote_doh_mode" in
          tokenized|dual)
            if [ -n "${'$'}remote_doh_token" ]; then
              remote_doh_tokenized_code=${'$'}(probe_http_code "${'$'}remote_doh_curl_spec" "${'$'}remote_doh_base/${'$'}remote_doh_token/dns-query" 4)
            fi
            ;;
        esac
        remote_doh_bare_code=${'$'}(probe_http_code "${'$'}remote_doh_curl_spec" "${'$'}remote_doh_base/dns-query" 4)
      fi
      if [ -n "${'$'}remote_doh_curl_spec" ]; then
        remote_doh_mode=$dohEndpointMode
        remote_doh_base="https://127.0.0.1:${config.remote.httpsPort}"
        case "${'$'}remote_doh_mode" in
          tokenized|dual)
            remote_identity_inject_code=${'$'}(probe_http_code "${'$'}remote_doh_curl_spec" "${'$'}remote_doh_base/pixel-stack/identity/inject.js" 4)
            ;;
        esac
      fi
      if [ ${if (config.remote.dohEnabled || config.remote.dotEnabled) "1" else "0"} -eq 1 ] && [ -n "${'$'}remote_doh_curl_spec" ] && [ -n "${'$'}remote_public_base" ]; then
        remote_public_probe_available="1"
        remote_public_root_code=${'$'}(probe_http_code "${'$'}remote_doh_curl_spec" "${'$'}remote_public_base/" 8 "${config.remote.hostname}" "${config.remote.httpsPort}" "127.0.0.1")
        if [ ${if (config.remote.dohEnabled) "1" else "0"} -eq 1 ]; then
          remote_doh_mode=$dohEndpointMode
          remote_doh_token=$dohPathToken
          case "${'$'}remote_doh_mode" in
            tokenized|dual)
              if [ -n "${'$'}remote_doh_token" ]; then
                remote_public_doh_tokenized_code=${'$'}(probe_http_code "${'$'}remote_doh_curl_spec" "${'$'}remote_public_base/${'$'}remote_doh_token/dns-query" 8 "${config.remote.hostname}" "${config.remote.httpsPort}" "127.0.0.1")
                remote_public_identity_inject_code=${'$'}(probe_http_code "${'$'}remote_doh_curl_spec" "${'$'}remote_public_base/pixel-stack/identity/inject.js" 8 "${config.remote.hostname}" "${config.remote.httpsPort}" "127.0.0.1")
              fi
              ;;
          esac
          remote_public_doh_bare_code=${'$'}(probe_http_code "${'$'}remote_doh_curl_spec" "${'$'}remote_public_base/dns-query" 8 "${config.remote.hostname}" "${config.remote.httpsPort}" "127.0.0.1")
        fi
      fi
      printf '$MARKER_REMOTE_DOH_TOKENIZED_CODE\n'
      printf '%s\n' "${'$'}remote_doh_tokenized_code"
      printf '$MARKER_REMOTE_DOH_BARE_CODE\n'
      printf '%s\n' "${'$'}remote_doh_bare_code"
      printf '$MARKER_REMOTE_IDENTITY_INJECT_CODE\n'
      printf '%s\n' "${'$'}remote_identity_inject_code"
      printf '$MARKER_REMOTE_PUBLIC_BASE_URL\n'
      printf '%s\n' "${'$'}remote_public_base"
      printf '$MARKER_REMOTE_PUBLIC_ROOT_CODE\n'
      printf '%s\n' "${'$'}remote_public_root_code"
      printf '$MARKER_REMOTE_PUBLIC_PROBE_AVAILABLE\n'
      printf '%s\n' "${'$'}remote_public_probe_available"
      printf '$MARKER_REMOTE_PUBLIC_DOH_TOKENIZED_CODE\n'
      printf '%s\n' "${'$'}remote_public_doh_tokenized_code"
      printf '$MARKER_REMOTE_PUBLIC_DOH_BARE_CODE\n'
      printf '%s\n' "${'$'}remote_public_doh_bare_code"
      printf '$MARKER_REMOTE_PUBLIC_IDENTITY_INJECT_CODE\n'
      printf '%s\n' "${'$'}remote_public_identity_inject_code"
      printf '$MARKER_END\n'
    """.trimIndent()
  }

  companion object {
    private const val TICKET_SCREEN_PORT = 9388
    private const val APP_HEARTBEAT_FRESH_SEC = 120L
    private const val MARKER_ID_U = "__PIXEL_HEALTH_ID_U__"
    private const val MARKER_LISTENERS = "__PIXEL_HEALTH_LISTENERS__"
    private const val MARKER_DDNS_EPOCH = "__PIXEL_HEALTH_DDNS_EPOCH__"
    private const val MARKER_DDNS_LAST_IPV4 = "__PIXEL_HEALTH_DDNS_LAST_IPV4__"
    private const val MARKER_SUPERVISOR_LOOP_HEARTBEAT = "__PIXEL_HEALTH_SUPERVISOR_LOOP_HEARTBEAT__"
    private const val MARKER_TRAIN_BOT_PID = "__PIXEL_HEALTH_TRAIN_BOT_PID__"
    private const val MARKER_TRAIN_BOT_TUNNEL_ENABLED = "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_ENABLED__"
    private const val MARKER_TRAIN_BOT_TUNNEL_SUPERVISOR_PID = "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_SUPERVISOR_PID__"
    private const val MARKER_TRAIN_BOT_TUNNEL_PID = "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PID__"
    private const val MARKER_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL = "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL__"
    private const val MARKER_TRAIN_BOT_PUBLIC_ROOT_CODE = "__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_ROOT_CODE__"
    private const val MARKER_TRAIN_BOT_PUBLIC_APP_CODE = "__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_APP_CODE__"
    private const val MARKER_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE = "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE__"
    private const val MARKER_TRAIN_BOT_HEARTBEAT = "__PIXEL_HEALTH_TRAIN_BOT_HEARTBEAT__"
    private const val MARKER_TRAIN_BOT_SCHEDULE_REQUIRED = "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_REQUIRED__"
    private const val MARKER_TRAIN_BOT_SCHEDULE_FRESH = "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_FRESH__"
    private const val MARKER_TRAIN_BOT_SCHEDULE_SERVICE_DATE = "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_SERVICE_DATE__"
    private const val MARKER_TRAIN_BOT_SCHEDULE_ROWS = "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_ROWS__"
    private const val MARKER_SATIKSME_BOT_PID = "__PIXEL_HEALTH_SATIKSME_BOT_PID__"
    private const val MARKER_SATIKSME_BOT_TUNNEL_ENABLED = "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_ENABLED__"
    private const val MARKER_SATIKSME_BOT_TUNNEL_SUPERVISOR_PID = "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_SUPERVISOR_PID__"
    private const val MARKER_SATIKSME_BOT_TUNNEL_PID = "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PID__"
    private const val MARKER_SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL = "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL__"
    private const val MARKER_SATIKSME_BOT_PUBLIC_ROOT_CODE = "__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_ROOT_CODE__"
    private const val MARKER_SATIKSME_BOT_PUBLIC_APP_CODE = "__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_APP_CODE__"
    private const val MARKER_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE = "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE__"
    private const val MARKER_SATIKSME_BOT_HEARTBEAT = "__PIXEL_HEALTH_SATIKSME_BOT_HEARTBEAT__"
    private const val MARKER_SITE_NOTIFIER_PID = "__PIXEL_HEALTH_SITE_NOTIFIER_PID__"
    private const val MARKER_SITE_NOTIFIER_HEARTBEAT = "__PIXEL_HEALTH_SITE_NOTIFIER_HEARTBEAT__"
    private const val MARKER_SITE_NOTIFIER_HELPER_HEALTHY = "__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_HEALTHY__"
    private const val MARKER_SITE_NOTIFIER_HELPER_REASON = "__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_REASON__"
    private const val MARKER_SUBSCRIPTION_BOT_PID = "__PIXEL_HEALTH_SUBSCRIPTION_BOT_PID__"
    private const val MARKER_SUBSCRIPTION_BOT_HEARTBEAT = "__PIXEL_HEALTH_SUBSCRIPTION_BOT_HEARTBEAT__"
    private const val MARKER_VPN_HEALTH = "__PIXEL_HEALTH_VPN_HEALTH__"
    private const val MARKER_VPN_ENABLED_EFFECTIVE = "__PIXEL_HEALTH_VPN_ENABLED_EFFECTIVE__"
    private const val MARKER_VPN_TAILSCALED_LIVE = "__PIXEL_HEALTH_VPN_TAILSCALED_LIVE__"
    private const val MARKER_VPN_TAILSCALED_SOCK = "__PIXEL_HEALTH_VPN_TAILSCALED_SOCK__"
    private const val MARKER_VPN_TAILNET_IPV4 = "__PIXEL_HEALTH_VPN_TAILNET_IPV4__"
    private const val MARKER_VPN_GUARD_CHAIN_IPV4 = "__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV4__"
    private const val MARKER_VPN_GUARD_CHAIN_IPV6 = "__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV6__"
    private const val MARKER_MANAGEMENT_ENABLED = "__PIXEL_HEALTH_MANAGEMENT_ENABLED__"
    private const val MARKER_MANAGEMENT_HEALTHY = "__PIXEL_HEALTH_MANAGEMENT_HEALTHY__"
    private const val MARKER_MANAGEMENT_REASON = "__PIXEL_HEALTH_MANAGEMENT_REASON__"
    private const val MARKER_MANAGEMENT_AUTH_CONSISTENT = "__PIXEL_HEALTH_MANAGEMENT_AUTH_CONSISTENT__"
    private const val MARKER_MANAGEMENT_AUTH_WARNING_REASON = "__PIXEL_HEALTH_MANAGEMENT_AUTH_WARNING_REASON__"
    private const val MARKER_MANAGEMENT_SSH_LISTENER = "__PIXEL_HEALTH_MANAGEMENT_SSH_LISTENER__"
    private const val MARKER_MANAGEMENT_SSH_AUTH_MODE = "__PIXEL_HEALTH_MANAGEMENT_SSH_AUTH_MODE__"
    private const val MARKER_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED = "__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED__"
    private const val MARKER_MANAGEMENT_SSH_PASSWORD_AUTH_READY = "__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_READY__"
    private const val MARKER_MANAGEMENT_SSH_KEY_AUTH_REQUESTED = "__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_REQUESTED__"
    private const val MARKER_MANAGEMENT_SSH_KEY_AUTH_READY = "__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_READY__"
    private const val MARKER_MANAGEMENT_PM_PATH = "__PIXEL_HEALTH_MANAGEMENT_PM_PATH__"
    private const val MARKER_MANAGEMENT_AM_PATH = "__PIXEL_HEALTH_MANAGEMENT_AM_PATH__"
    private const val MARKER_MANAGEMENT_LOGCAT_PATH = "__PIXEL_HEALTH_MANAGEMENT_LOGCAT_PATH__"
    private const val MARKER_MANAGEMENT_WIRELESS_DEBUG_ENABLED = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_ENABLED__"
    private const val MARKER_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT__"
    private const val MARKER_MANAGEMENT_WIRELESS_DEBUG_LIVE = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE__"
    private const val MARKER_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS__"
    private const val MARKER_MANAGEMENT_WIRELESS_DEBUG_HEALTHY = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_HEALTHY__"
    private const val MARKER_MANAGEMENT_WIRELESS_DEBUG_REASON = "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_REASON__"
    private const val MARKER_MANAGEMENT_WIFI_ENABLED = "__PIXEL_HEALTH_MANAGEMENT_WIFI_ENABLED__"
    private const val MARKER_MANAGEMENT_WIFI_CONNECTED = "__PIXEL_HEALTH_MANAGEMENT_WIFI_CONNECTED__"
    private const val MARKER_MANAGEMENT_WIFI_IPV4 = "__PIXEL_HEALTH_MANAGEMENT_WIFI_IPV4__"
    private const val MARKER_MANAGEMENT_MOBILE_IFACE = "__PIXEL_HEALTH_MANAGEMENT_MOBILE_IFACE__"
    private const val MARKER_MANAGEMENT_MOBILE_IPV4 = "__PIXEL_HEALTH_MANAGEMENT_MOBILE_IPV4__"
    private const val MARKER_MANAGEMENT_ACTIVE_TRANSPORT = "__PIXEL_HEALTH_MANAGEMENT_ACTIVE_TRANSPORT__"
    private const val MARKER_MANAGEMENT_PUBLIC_IPV4_CANDIDATE = "__PIXEL_HEALTH_MANAGEMENT_PUBLIC_IPV4_CANDIDATE__"
    private const val MARKER_MANAGEMENT_NETWORK_FINGERPRINT = "__PIXEL_HEALTH_MANAGEMENT_NETWORK_FINGERPRINT__"
    private const val MARKER_REMOTE_DOH_TOKENIZED_CODE = "__PIXEL_HEALTH_REMOTE_DOH_TOKENIZED_CODE__"
    private const val MARKER_REMOTE_DOH_BARE_CODE = "__PIXEL_HEALTH_REMOTE_DOH_BARE_CODE__"
    private const val MARKER_REMOTE_IDENTITY_INJECT_CODE = "__PIXEL_HEALTH_REMOTE_IDENTITY_INJECT_CODE__"
    private const val MARKER_REMOTE_PUBLIC_BASE_URL = "__PIXEL_HEALTH_REMOTE_PUBLIC_BASE_URL__"
    private const val MARKER_REMOTE_PUBLIC_ROOT_CODE = "__PIXEL_HEALTH_REMOTE_PUBLIC_ROOT_CODE__"
    private const val MARKER_REMOTE_PUBLIC_PROBE_AVAILABLE = "__PIXEL_HEALTH_REMOTE_PUBLIC_PROBE_AVAILABLE__"
    private const val MARKER_REMOTE_PUBLIC_DOH_TOKENIZED_CODE = "__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_TOKENIZED_CODE__"
    private const val MARKER_REMOTE_PUBLIC_DOH_BARE_CODE = "__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_BARE_CODE__"
    private const val MARKER_REMOTE_PUBLIC_IDENTITY_INJECT_CODE = "__PIXEL_HEALTH_REMOTE_PUBLIC_IDENTITY_INJECT_CODE__"
    private const val MARKER_END = "__PIXEL_HEALTH_DONE__"
  }
}
