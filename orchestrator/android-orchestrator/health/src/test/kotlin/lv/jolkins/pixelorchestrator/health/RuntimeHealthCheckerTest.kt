package lv.jolkins.pixelorchestrator.health

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import org.junit.Test

class RuntimeHealthCheckerTest {

  @Test
  fun buildProbeCommandIsShellParsable() {
    val checker = RuntimeHealthChecker(CommandRunner { CommandResult(ok = true, stdout = "", stderr = "") })
    val method = RuntimeHealthChecker::class.java.getDeclaredMethod("buildProbeCommand", StackConfigV1::class.java)
    method.isAccessible = true

    val command = method.invoke(checker, StackConfigV1()) as String
    val process = ProcessBuilder("/bin/sh", "-n", "-c", command).start()
    val stderr = process.errorStream.bufferedReader().use { it.readText() }

    assertEquals(0, process.waitFor(), stderr.ifBlank { "shell parse failed" })
  }

  @Test
  fun usesSingleBatchedProbeAndSynthesizesHealthySnapshot() {
    var calls = 0
    val runner = CommandRunner { cmd ->
      calls += 1
      val trainPidFallbackSnippet =
        """ps -A 2>/dev/null | awk '((${'$'}NF=="train-bot") || index(${'$'}NF,"train-bot.")==1) {print ${'$'}2; exit}'"""
      val trainTunnelSupervisorFallbackSnippet =
        """scan_pid_by_target /data/local/pixel-stack/apps/train-bot/bin/train-web-tunnel-service-loop"""
      val trainTunnelPidFallbackSnippet =
        """scan_pid_by_target /data/local/pixel-stack/apps/train-bot/bin/cloudflared"""
      val satiksmeTunnelSupervisorFallbackSnippet =
        """scan_pid_by_target /data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-web-tunnel-service-loop"""
      val satiksmeTunnelPidFallbackSnippet =
        """scan_pid_by_target /data/local/pixel-stack/apps/satiksme-bot/bin/cloudflared"""
      assertTrue(cmd.contains("__PIXEL_HEALTH_ID_U__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_LISTENERS__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_DDNS_EPOCH__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_DDNS_LAST_IPV4__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SUPERVISOR_LOOP_HEARTBEAT__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_PID__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_ENABLED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PID__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_ROOT_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_APP_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_REQUIRED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_FRESH__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_SERVICE_DATE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_ROWS__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SATIKSME_BOT_PID__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_ENABLED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PID__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_ROOT_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_APP_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SATIKSME_BOT_HEARTBEAT__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SITE_NOTIFIER_PID__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_HEALTHY__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_REASON__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SUBSCRIPTION_BOT_PID__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_SUBSCRIPTION_BOT_HEARTBEAT__"))
      assertTrue(cmd.contains("train-bot.pid"))
      assertTrue(cmd.contains("satiksme-bot.pid"))
      assertTrue(cmd.contains("site-notifier.pid"))
      assertTrue(cmd.contains("subscription-bot.pid"))
      assertTrue(cmd.contains("pixel-notifier-health.sh"))
      assertTrue(cmd.contains(trainPidFallbackSnippet))
      assertTrue(cmd.contains(trainTunnelSupervisorFallbackSnippet))
      assertTrue(cmd.contains(trainTunnelPidFallbackSnippet))
      assertTrue(cmd.contains(satiksmeTunnelSupervisorFallbackSnippet))
      assertTrue(cmd.contains(satiksmeTunnelPidFallbackSnippet))
      assertFalse(cmd.contains("app.py daemon"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_VPN_HEALTH__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_VPN_ENABLED_EFFECTIVE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_VPN_TAILSCALED_LIVE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_VPN_TAILSCALED_SOCK__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_VPN_TAILNET_IPV4__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV4__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV6__"))
      assertTrue(cmd.contains("pixel-management-health.sh"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_ENABLED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_HEALTHY__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_REASON__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_AUTH_CONSISTENT__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_AUTH_WARNING_REASON__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_SSH_LISTENER__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_SSH_AUTH_MODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_READY__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_REQUESTED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_READY__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_PM_PATH__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_AM_PATH__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_LOGCAT_PATH__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_ENABLED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_HEALTHY__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_REASON__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIFI_ENABLED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIFI_CONNECTED__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_WIFI_IPV4__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_MOBILE_IFACE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_MOBILE_IPV4__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_ACTIVE_TRANSPORT__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_PUBLIC_IPV4_CANDIDATE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_MANAGEMENT_NETWORK_FINGERPRINT__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_DOH_TOKENIZED_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_DOH_BARE_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_IDENTITY_INJECT_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_PUBLIC_BASE_URL__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_PUBLIC_ROOT_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_PUBLIC_PROBE_AVAILABLE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_TOKENIZED_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_BARE_CODE__"))
      assertTrue(cmd.contains("__PIXEL_HEALTH_REMOTE_PUBLIC_IDENTITY_INJECT_CODE__"))

      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\nLISTEN 0 50 127.0.0.1:9388\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1(
      vpn = StackConfigV1().vpn.copy(enabled = true),
      remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = true),
      modules = mapOf("subscription_bot" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = true))
    )
    val snapshot = runBlocking { checker.check(config) }

    assertEquals(1, calls)
    assertTrue(snapshot.rootGranted)
    assertTrue(snapshot.dnsHealthy)
    assertTrue(snapshot.sshHealthy)
    assertTrue(snapshot.vpnHealthy)
    assertTrue(snapshot.managementHealthy)
    assertTrue(snapshot.trainBotHealthy)
    assertTrue(snapshot.satiksmeBotHealthy)
    assertTrue(snapshot.siteNotifierHealthy)
    assertTrue(snapshot.subscriptionBotHealthy)
    assertTrue(snapshot.remoteHealthy)
    assertTrue(snapshot.ddnsHealthy)
    assertTrue(snapshot.supervisorLoopHealthy)
    assertTrue(snapshot.managementAuthHealthy)
    assertTrue(snapshot.deployHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertEquals("true", snapshot.evidence["listeners_ok"])
    assertEquals("false", snapshot.evidence["train_bot_schedule_required"])
    assertEquals("true", snapshot.evidence["train_bot_schedule_fresh"])
    assertEquals("100.64.0.10", snapshot.evidence["vpn_tailnet_ipv4"])
    assertEquals("true", snapshot.evidence["management_enabled"])
    assertEquals("ok", snapshot.evidence["management_reason"])
    assertEquals("true", snapshot.evidence["management_auth_consistent"])
    assertEquals("ok", snapshot.evidence["management_auth_warning_reason"])
    assertEquals("false", snapshot.evidence["direct_public_transitioning"])
    assertEquals("true", snapshot.evidence["direct_public_path_healthy"])
    assertEquals("running", snapshot.moduleHealth["management"]?.status)
    assertEquals("running", snapshot.moduleHealth["subscription_bot"]?.status)
    assertEquals("running", snapshot.moduleHealth["ticket_screen"]?.status)
    assertEquals("1", snapshot.moduleHealth["ticket_screen"]?.details?.get("listener"))
  }

  @Test
  fun marksSubscriptionBotDisabledWhenModuleIsTurnedOff() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          subscriptionBotPid = "",
          subscriptionBotHeartbeat = "",
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1(modules = mapOf("subscription_bot" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false)))
    val snapshot = runBlocking { checker.check(config) }

    assertFalse(snapshot.subscriptionBotHealthy)
    assertEquals("disabled", snapshot.moduleHealth["subscription_bot"]?.status)
    assertEquals("disabled", snapshot.moduleHealth["subscription_bot"]?.details?.get("failure_reason"))
    assertTrue(snapshot.supervisorHealthy)
  }

  @Test
  fun marksTrainSatiksmeAndSiteNotifierDisabledWhenModulesAreTurnedOff() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "",
          trainBotHeartbeat = "",
          satiksmeBotPid = "",
          satiksmeBotHeartbeat = "",
          siteNotifierPid = "",
          siteNotifierHeartbeat = "",
          supervisorLoopHeartbeat = nowEpoch.toString(),
          siteNotifierHelperHealthy = "0",
          siteNotifierHelperReason = "daemon_not_initialized",
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1(
      vpn = StackConfigV1().vpn.copy(enabled = true),
      modules = mapOf(
        "train_bot" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false),
        "satiksme_bot" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false),
        "site_notifier" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false),
        "subscription_bot" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false)
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertFalse(snapshot.trainBotHealthy)
    assertFalse(snapshot.satiksmeBotHealthy)
    assertFalse(snapshot.siteNotifierHealthy)
    assertEquals("disabled", snapshot.moduleHealth["train_bot"]?.status)
    assertEquals("disabled", snapshot.moduleHealth["satiksme_bot"]?.status)
    assertEquals("disabled", snapshot.moduleHealth["site_notifier"]?.status)
    assertEquals("disabled", snapshot.moduleHealth["train_bot"]?.details?.get("failure_reason"))
    assertEquals("disabled", snapshot.moduleHealth["satiksme_bot"]?.details?.get("failure_reason"))
    assertEquals("disabled", snapshot.moduleHealth["site_notifier"]?.details?.get("failure_reason"))
    assertTrue(snapshot.deployHealthy)
    assertTrue(snapshot.supervisorHealthy)
  }

  @Test
  fun doesNotTreatHttpsListenerAsHealthySshWhenDropbearIsMissing() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          vpnHealth = "1",
          managementEnabled = "1",
          managementHealthy = "0",
          managementReason = "ssh_listener_missing",
          managementSshListener = "0"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1(
      ssh = StackConfigV1().ssh.copy(port = 443),
      vpn = StackConfigV1().vpn.copy(enabled = true),
      remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = true, httpsPort = 443, watchdogEscalateRuntimeRestart = true)
    )
    val snapshot = runBlocking { checker.check(config) }

    assertFalse(snapshot.sshHealthy)
    assertFalse(snapshot.managementHealthy)
    assertFalse(snapshot.deployHealthy)
    assertEquals("0", snapshot.evidence["management_ssh_listener"])
    assertEquals("degraded", snapshot.moduleHealth["ssh"]?.status)
  }

  @Test
  fun failsManagementWhenWirelessDebugIsRequiredButNotLive() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          vpnHealth = "1",
          managementEnabled = "1",
          managementHealthy = "0",
          managementReason = "wireless_debug_disabled",
          managementWirelessDebugEnabled = "0",
          managementWirelessDebugTlsPort = "",
          managementWirelessDebugLive = "0",
          managementWirelessDebugLivePorts = "",
          managementWirelessDebugHealthy = "0",
          managementWirelessDebugReason = "wireless_debug_disabled"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1(
      vpn = StackConfigV1().vpn.copy(enabled = true),
      supervision = StackConfigV1().supervision.copy(managementRequireWirelessDebug = true)
    )
    val snapshot = runBlocking { checker.check(config) }

    assertFalse(snapshot.managementHealthy)
    assertFalse(snapshot.deployHealthy)
    assertEquals("true", snapshot.evidence["management_require_wireless_debug"])
    assertEquals("wireless_debug_disabled", snapshot.evidence["management_reason"])
  }

  @Test
  fun reportsManagementFailureReasonAndModuleDetails() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          managementHealthy = "0",
          managementReason = "password_auth_not_ready",
          managementSshAuthMode = "password_only",
          managementSshPasswordAuthRequested = "1",
          managementSshPasswordAuthReady = "0",
          managementSshKeyAuthRequested = "0",
          managementSshKeyAuthReady = "0"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking {
      checker.check(
        StackConfigV1(
          vpn = StackConfigV1().vpn.copy(enabled = true),
          remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false)
        )
      )
    }

    assertFalse(snapshot.managementHealthy)
    assertFalse(snapshot.supervisorHealthy)
    assertEquals("password_auth_not_ready", snapshot.evidence["management_reason"])
    assertEquals("false", snapshot.evidence["management_healthy"])
    assertEquals("degraded", snapshot.moduleHealth["management"]?.status)
    assertEquals("password_auth_not_ready", snapshot.moduleHealth["management"]?.details?.get("failure_reason"))
    assertEquals("password_only", snapshot.moduleHealth["management"]?.details?.get("ssh_auth_mode"))
    assertEquals("0", snapshot.moduleHealth["management"]?.details?.get("ssh_password_auth_ready"))
  }

  @Test
  fun keepsManagementRunningWhenAuthStateHasDriftedButPathIsUsable() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          managementHealthy = "1",
          managementReason = "ok",
          managementAuthConsistent = "0",
          managementAuthWarningReason = "password_auth_runtime_mismatch"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot =
      runBlocking {
        checker.check(
          StackConfigV1(
            vpn = StackConfigV1().vpn.copy(enabled = true),
            remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false)
          )
        )
      }

    assertTrue(snapshot.managementHealthy)
    assertFalse(snapshot.managementAuthHealthy)
    assertFalse(snapshot.deployHealthy)
    assertEquals("true", snapshot.evidence["management_path_healthy"])
    assertEquals("false", snapshot.evidence["management_auth_healthy"])
    assertEquals("false", snapshot.evidence["management_auth_consistent"])
    assertEquals("password_auth_runtime_mismatch", snapshot.evidence["management_auth_warning_reason"])
    assertEquals("running", snapshot.moduleHealth["management"]?.status)
    assertEquals("false", snapshot.moduleHealth["management"]?.details?.get("management_auth_healthy"))
    assertEquals("password_auth_runtime_mismatch", snapshot.moduleHealth["management"]?.details?.get("management_auth_warning_reason"))
  }

  @Test
  fun marksDeployUnhealthyWhenSupervisorLoopHeartbeatIsStale() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          supervisorLoopHeartbeat = (nowEpoch - 600).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot =
      runBlocking {
        checker.check(
          StackConfigV1(
            vpn = StackConfigV1().vpn.copy(enabled = true),
            remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false)
          )
        )
      }

    assertTrue(snapshot.supervisorHealthy)
    assertFalse(snapshot.supervisorLoopHealthy)
    assertFalse(snapshot.deployHealthy)
    assertEquals("false", snapshot.evidence["supervisor_loop_healthy"])
  }

  @Test
  fun marksDeployUnhealthyWhenDdnsIsEnabledAndHeartbeatIsStale() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (nowEpoch - 10_000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot =
      runBlocking {
        checker.check(
          StackConfigV1(
            vpn = StackConfigV1().vpn.copy(enabled = true),
            remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false)
          )
        )
      }

    assertFalse(snapshot.ddnsHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertFalse(snapshot.deployHealthy)
    assertEquals("true", snapshot.evidence["ddns_required"])
  }

  @Test
  fun keepsManagementHealthyWhenWirelessDebugIsNotLiveButNotRequired() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          vpnHealth = "1",
          managementWirelessDebugEnabled = "0",
          managementWirelessDebugTlsPort = "",
          managementWirelessDebugLive = "0",
          managementWirelessDebugLivePorts = "",
          managementWirelessDebugHealthy = "0",
          managementWirelessDebugReason = "listener_missing"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.managementHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertEquals("0", snapshot.evidence["wireless_debug_live"])
    assertEquals("false", snapshot.evidence["management_require_wireless_debug"])
    assertEquals("listener_missing", snapshot.evidence["management_wireless_debug_reason"])
  }

  @Test
  fun keepsDirectPublicPathHealthyWhenPublishedIpLagsObservedNetwork() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = nowEpoch.toString(),
          ddnsLastIpv4 = "62.205.193.194",
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          vpnHealth = "1",
          managementPublicIpv4Candidate = "10.0.0.5",
          managementNetworkFingerprint = "transport=cellular;wifi_enabled=1;wifi_connected=0;wifi_ipv4=none;mobile_iface=rmnet_data0;mobile_ipv4=10.10.0.2;adbd_ports=5555;public_ipv4=10.0.0.5",
          managementWifiConnected = "0",
          managementWifiIpv4 = "",
          managementMobileIface = "rmnet_data0",
          managementMobileIpv4 = "10.10.0.2",
          managementActiveTransport = "cellular"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot =
      runBlocking {
        checker.check(
          StackConfigV1(
            vpn = StackConfigV1().vpn.copy(enabled = true),
            remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false)
          )
        )
      }

    assertEquals("62.205.193.194", snapshot.evidence["ddns_published_ipv4"])
    assertEquals("10.0.0.5", snapshot.evidence["network_public_ipv4_candidate"])
    assertEquals("false", snapshot.evidence["direct_public_dns_consistent"])
    assertEquals("true", snapshot.evidence["direct_public_transitioning"])
    assertEquals("published_public_ipv4_lagging", snapshot.evidence["direct_public_transition_reason"])
    assertEquals("true", snapshot.evidence["direct_public_path_healthy"])
    assertEquals("running", snapshot.moduleHealth["remote"]?.status)
    assertEquals("true", snapshot.moduleHealth["remote"]?.details?.get("direct_public_transitioning"))
    assertEquals("true", snapshot.moduleHealth["remote"]?.details?.get("direct_public_path_healthy"))
  }

  @Test
  fun siteNotifierHelperFailureOverridesFreshPidAndHeartbeat() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          siteNotifierHelperHealthy = "0",
          siteNotifierHelperReason = "daemon_not_initialized",
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertFalse(snapshot.siteNotifierHealthy)
    assertFalse(snapshot.supervisorHealthy)
    assertEquals("false", snapshot.evidence["site_notifier_helper_healthy"])
    assertEquals("daemon_not_initialized", snapshot.evidence["site_notifier_failure_reason"])
    assertEquals("daemon_not_initialized", snapshot.moduleHealth["site_notifier"]?.details?.get("failure_reason"))
  }

  @Test
  fun marksSnapshotDegradedWhenProbeOutputCannotBeParsed() {
    val runner = CommandRunner { _ ->
      CommandResult(ok = true, stdout = "unexpected output", stderr = "")
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = true))
    val snapshot = runBlocking { checker.check(config) }

    assertFalse(snapshot.rootGranted)
    assertFalse(snapshot.dnsHealthy)
    assertFalse(snapshot.sshHealthy)
    assertFalse(snapshot.trainBotHealthy)
    assertFalse(snapshot.siteNotifierHealthy)
    assertFalse(snapshot.remoteHealthy)
    assertFalse(snapshot.ddnsHealthy)
    assertFalse(snapshot.supervisorHealthy)
    assertEquals("false", snapshot.evidence["listeners_ok"])
  }

  @Test
  fun marksSupervisorUnhealthyWhenRemoteIsRequiredButRemotePortsMissing() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      vpn = StackConfigV1().vpn.copy(enabled = true),
      remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false, watchdogEscalateRuntimeRestart = true)
    )
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.dnsHealthy)
    assertTrue(snapshot.sshHealthy)
    assertTrue(snapshot.vpnHealthy)
    assertFalse(snapshot.remoteHealthy)
    assertFalse(snapshot.supervisorHealthy)
  }

  @Test
  fun keepsSupervisorHealthyWhenRemoteListenerEnforcementIsDisabled() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false))
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.supervisorHealthy)
    assertTrue(snapshot.remoteHealthy)
    assertEquals("false", snapshot.evidence["remote_health_enforced"])
  }

  @Test
  fun reportsSatiksmeFailureReasonForTunnelSupervisorFailures() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          satiksmeBotPid = "3234",
          satiksmeBotTunnelEnabled = "1",
          satiksmeBotTunnelSupervisorPid = "",
          satiksmeBotTunnelPid = "5555",
          satiksmeBotTunnelPublicBaseUrl = "https://satiksme-bot.jolkins.id.lv",
          satiksmeBotTunnelProbeAvailable = "1",
          satiksmeBotHeartbeat = nowEpoch.toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertFalse(snapshot.satiksmeBotHealthy)
    assertEquals("tunnel_supervisor_missing", snapshot.evidence["satiksme_bot_failure_reason"])
    assertEquals("tunnel_supervisor_missing", snapshot.moduleHealth["satiksme_bot"]?.details?.get("failure_reason"))
  }

  @Test
  fun reportsSatiksmeFailureReasonForStaleHeartbeat() {
    val nowEpoch = System.currentTimeMillis() / 1000
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = nowEpoch.toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = nowEpoch.toString(),
          satiksmeBotPid = "3234",
          satiksmeBotHeartbeat = (nowEpoch - 600).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = nowEpoch.toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertFalse(snapshot.satiksmeBotHealthy)
    assertEquals("heartbeat_stale", snapshot.evidence["satiksme_bot_failure_reason"])
  }

  @Test
  fun marksRemoteHealthyInTokenizedModeWhenTokenPathIs200AndBarePathIsNon200() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          remoteDohTokenizedCode = "200",
          remoteDohBareCode = "404",
          remoteIdentityInjectCode = "200",
          remotePublicRootCode = "200",
          remotePublicProbeAvailable = "1",
          remotePublicDohTokenizedCode = "200",
          remotePublicDohBareCode = "404",
          remotePublicIdentityInjectCode = "200"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      remote = StackConfigV1().remote.copy(
        dohEnabled = true,
        dohEndpointMode = "tokenized",
        dohPathToken = "0123456789abcdef0123456789abcdef",
        watchdogEscalateRuntimeRestart = true
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.remoteHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertEquals("tokenized", snapshot.evidence["doh_endpoint_mode"])
    assertEquals("no_query_http_contract", snapshot.evidence["doh_probe_mode"])
    assertEquals("true", snapshot.evidence["doh_contract"])
    assertEquals("true", snapshot.evidence["identity_frontend_required"])
    assertEquals("true", snapshot.evidence["identity_frontend_healthy"])
    assertEquals("true", snapshot.evidence["remote_public_doh_contract"])
    assertEquals("true", snapshot.evidence["remote_public_identity_frontend_healthy"])
  }

  @Test
  fun keepsRemoteHealthyWhenPublicRootRedirectsToLogin() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          remoteDohTokenizedCode = "400",
          remoteDohBareCode = "404",
          remoteIdentityInjectCode = "200",
          remotePublicRootCode = "302",
          remotePublicProbeAvailable = "1",
          remotePublicDohTokenizedCode = "400",
          remotePublicDohBareCode = "404",
          remotePublicIdentityInjectCode = "200"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      remote = StackConfigV1().remote.copy(
        dohEnabled = true,
        dohEndpointMode = "tokenized",
        dohPathToken = "0123456789abcdef0123456789abcdef",
        watchdogEscalateRuntimeRestart = true
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.remoteHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertEquals("302", snapshot.evidence["remote_public_root_code"])
    assertEquals("true", snapshot.evidence["remote_public_root_healthy"])
  }

  @Test
  fun keepsRemoteHealthyInTokenizedModeWhenPublicIdentityInjectEndpointIsNotHealthyButLocalIngressIsHealthy() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          remoteDohTokenizedCode = "200",
          remoteDohBareCode = "404",
          remoteIdentityInjectCode = "200",
          remotePublicRootCode = "200",
          remotePublicProbeAvailable = "1",
          remotePublicDohTokenizedCode = "200",
          remotePublicDohBareCode = "404",
          remotePublicIdentityInjectCode = "502"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      remote = StackConfigV1().remote.copy(
        dohEnabled = true,
        dohEndpointMode = "tokenized",
        dohPathToken = "0123456789abcdef0123456789abcdef",
        watchdogEscalateRuntimeRestart = true
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.remoteHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertEquals("true", snapshot.evidence["identity_frontend_required"])
    assertEquals("true", snapshot.evidence["identity_frontend_healthy"])
    assertEquals("false", snapshot.evidence["remote_public_identity_frontend_healthy"])
    assertEquals("502", snapshot.evidence["remote_public_identity_inject_code"])
  }

  @Test
  fun marksRemoteUnhealthyInTokenizedModeWhenBarePathReturns200() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          remoteDohTokenizedCode = "200",
          remoteDohBareCode = "200",
          remotePublicRootCode = "200",
          remotePublicProbeAvailable = "1",
          remotePublicDohTokenizedCode = "200",
          remotePublicDohBareCode = "200",
          remotePublicIdentityInjectCode = "200"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      remote = StackConfigV1().remote.copy(
        dohEnabled = true,
        dohEndpointMode = "tokenized",
        dohPathToken = "0123456789abcdef0123456789abcdef",
        watchdogEscalateRuntimeRestart = true
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertFalse(snapshot.remoteHealthy)
    assertFalse(snapshot.supervisorHealthy)
    assertEquals("false", snapshot.evidence["doh_contract"])
    assertEquals("false", snapshot.evidence["remote_public_doh_contract"])
  }

  @Test
  fun marksRemoteUnhealthyInTokenizedModeWhenTokenPathIsRouteMiss() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          remoteDohTokenizedCode = "404",
          remoteDohBareCode = "404"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      remote = StackConfigV1().remote.copy(
        dohEnabled = true,
        dohEndpointMode = "tokenized",
        dohPathToken = "0123456789abcdef0123456789abcdef",
        watchdogEscalateRuntimeRestart = true
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertFalse(snapshot.remoteHealthy)
    assertFalse(snapshot.supervisorHealthy)
    assertEquals("false", snapshot.evidence["doh_contract"])
  }

  @Test
  fun keepsSupervisorHealthyWhenRemoteIsNotRequiredAndRemotePortsMissing() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      vpn = StackConfigV1().vpn.copy(enabled = true),
      remote = StackConfigV1().remote.copy(dohEnabled = false, dotEnabled = false)
    )
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.dnsHealthy)
    assertTrue(snapshot.sshHealthy)
    assertTrue(snapshot.vpnHealthy)
    assertFalse(snapshot.remoteHealthy)
    assertTrue(snapshot.supervisorHealthy)
  }

  @Test
  fun keepsRemoteHealthyWhenPublicRootIsUnavailableButLocalIngressIsHealthy() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          remoteDohTokenizedCode = "200",
          remoteDohBareCode = "404",
          remoteIdentityInjectCode = "200",
          remotePublicRootCode = "000",
          remotePublicProbeAvailable = "1",
          remotePublicDohTokenizedCode = "200",
          remotePublicDohBareCode = "404",
          remotePublicIdentityInjectCode = "200"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      remote = StackConfigV1().remote.copy(
        dohEnabled = true,
        dohEndpointMode = "tokenized",
        dohPathToken = "0123456789abcdef0123456789abcdef",
        watchdogEscalateRuntimeRestart = true
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.remoteHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertEquals("000", snapshot.evidence["remote_public_root_code"])
    assertEquals("false", snapshot.evidence["remote_public_root_healthy"])
  }

  @Test
  fun keepsRemoteHealthyWhenPublicTokenizedDohReturnsGatewayErrorButLocalIngressIsHealthy() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1",
          remoteDohTokenizedCode = "200",
          remoteDohBareCode = "404",
          remoteIdentityInjectCode = "200",
          remotePublicRootCode = "200",
          remotePublicProbeAvailable = "1",
          remotePublicDohTokenizedCode = "502",
          remotePublicDohBareCode = "404",
          remotePublicIdentityInjectCode = "200"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val config = StackConfigV1().copy(
      remote = StackConfigV1().remote.copy(
        dohEnabled = true,
        dohEndpointMode = "tokenized",
        dohPathToken = "0123456789abcdef0123456789abcdef",
        watchdogEscalateRuntimeRestart = true
      )
    )
    val snapshot = runBlocking { checker.check(config) }

    assertTrue(snapshot.remoteHealthy)
    assertTrue(snapshot.supervisorHealthy)
    assertEquals("502", snapshot.evidence["remote_public_doh_tokenized_code"])
    assertEquals("false", snapshot.evidence["remote_public_doh_contract"])
  }

  @Test
  fun marksTrainBotUnhealthyWhenFreshScheduleIsRequiredButMissing() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          trainBotScheduleRequired = "1",
          trainBotScheduleFresh = "0",
          trainBotScheduleServiceDate = "2026-02-28",
          trainBotScheduleRows = "0",
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertFalse(snapshot.trainBotHealthy)
    assertEquals("true", snapshot.evidence["train_bot_schedule_required"])
    assertEquals("false", snapshot.evidence["train_bot_schedule_fresh"])
  }

  @Test
  fun keepsTrainBotHealthyWhenFreshScheduleIsNotRequiredYet() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          trainBotScheduleRequired = "0",
          trainBotScheduleFresh = "0",
          trainBotScheduleServiceDate = "2026-02-28",
          trainBotScheduleRows = "0",
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.trainBotHealthy)
  }

  @Test
  fun keepsTrainBotHealthyWhenFreshScheduleIsPresent() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          trainBotScheduleRequired = "1",
          trainBotScheduleFresh = "1",
          trainBotScheduleServiceDate = "2026-02-28",
          trainBotScheduleRows = "7",
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.trainBotHealthy)
    assertEquals("7", snapshot.evidence["train_bot_schedule_rows"])
  }

  @Test
  fun keepsTrainBotHealthyWhenScheduleRowsExistEvenIfFreshFileIsMissing() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          trainBotScheduleRequired = "1",
          trainBotScheduleFresh = "0",
          trainBotScheduleServiceDate = "2026-02-28",
          trainBotScheduleRows = "3",
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.trainBotHealthy)
    assertEquals("true", snapshot.evidence["train_bot_schedule_rows_present"])
  }

  @Test
  fun keepsTrainBotHealthyWhenScheduleRowsAreUnknownButFreshFileExists() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          trainBotScheduleRequired = "1",
          trainBotScheduleFresh = "1",
          trainBotScheduleServiceDate = "2026-02-28",
          trainBotScheduleRows = "unknown",
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.trainBotHealthy)
    assertEquals("unknown", snapshot.evidence["train_bot_schedule_rows"])
  }

  @Test
  fun keepsTrainBotHealthyWhenScheduleProbeIsInconclusive() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          trainBotScheduleRequired = "1",
          trainBotScheduleFresh = "0",
          trainBotScheduleServiceDate = "2026-02-28",
          trainBotScheduleRows = "unknown",
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.trainBotHealthy)
    assertEquals("true", snapshot.evidence["train_bot_schedule_probe_inconclusive"])
  }

  @Test
  fun keepsTrainBotHealthyWhenTunnelModeHasLivePidAndPublicPagesReturn200() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotTunnelEnabled = "1",
          trainBotTunnelSupervisorPid = "4444",
          trainBotTunnelPid = "5555",
          trainBotTunnelPublicBaseUrl = "https://train-bot.jolkins.id.lv",
          trainBotPublicRootCode = "200",
          trainBotPublicAppCode = "200",
          trainBotTunnelProbeAvailable = "1",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.trainBotHealthy)
    assertEquals("true", snapshot.evidence["train_bot_tunnel_healthy"])
    assertEquals("200", snapshot.evidence["train_bot_public_root_code"])
    assertEquals("200", snapshot.evidence["train_bot_public_app_code"])
  }

  @Test
  fun marksTrainBotUnhealthyWhenTunnelModeHasNoLiveTunnelPid() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotTunnelEnabled = "1",
          trainBotTunnelSupervisorPid = "4444",
          trainBotTunnelPid = "",
          trainBotTunnelPublicBaseUrl = "https://train-bot.jolkins.id.lv",
          trainBotPublicRootCode = "200",
          trainBotPublicAppCode = "200",
          trainBotTunnelProbeAvailable = "1",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertFalse(snapshot.trainBotHealthy)
    assertEquals("false", snapshot.evidence["train_bot_tunnel_healthy"])
  }

  @Test
  fun marksTrainBotUnhealthyWhenTunnelModePublicProbeFails() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotTunnelEnabled = "1",
          trainBotTunnelSupervisorPid = "4444",
          trainBotTunnelPid = "5555",
          trainBotTunnelPublicBaseUrl = "https://train-bot.jolkins.id.lv",
          trainBotPublicRootCode = "530",
          trainBotPublicAppCode = "530",
          trainBotTunnelProbeAvailable = "1",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertFalse(snapshot.trainBotHealthy)
    assertEquals("false", snapshot.evidence["train_bot_tunnel_healthy"])
    assertEquals("530", snapshot.evidence["train_bot_public_root_code"])
  }

  @Test
  fun marksTrainBotUnhealthyWhenTunnelSupervisorPidIsMissing() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotTunnelEnabled = "1",
          trainBotTunnelSupervisorPid = "",
          trainBotTunnelPid = "5555",
          trainBotTunnelPublicBaseUrl = "https://train-bot.jolkins.id.lv",
          trainBotPublicRootCode = "200",
          trainBotPublicAppCode = "200",
          trainBotTunnelProbeAvailable = "1",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertFalse(snapshot.trainBotHealthy)
    assertEquals("false", snapshot.evidence["train_bot_tunnel_supervisor_healthy"])
  }

  @Test
  fun doesNotRequireTunnelProbeWhenTunnelModeIsDisabled() {
    val runner = CommandRunner {
      CommandResult(
        ok = true,
        stdout = probeOutput(
          idU = "0",
          listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
          ddnsEpoch = (System.currentTimeMillis() / 1000).toString(),
          trainBotPid = "1234",
          trainBotTunnelEnabled = "0",
          trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          siteNotifierPid = "2234",
          siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
          vpnHealth = "1"
        ),
        stderr = ""
      )
    }

    val checker = RuntimeHealthChecker(runner)
    val snapshot = runBlocking { checker.check(StackConfigV1()) }

    assertTrue(snapshot.trainBotHealthy)
    assertEquals("true", snapshot.evidence["train_bot_tunnel_healthy"])
  }

  private fun probeOutput(
    idU: String,
    listeners: String,
    ddnsEpoch: String,
    ddnsLastIpv4: String = "100.64.0.10",
    trainBotPid: String,
    trainBotTunnelEnabled: String = "0",
    trainBotTunnelSupervisorPid: String = "",
    trainBotTunnelPid: String = "",
    trainBotTunnelPublicBaseUrl: String = "",
    trainBotPublicRootCode: String = "000",
    trainBotPublicAppCode: String = "000",
    trainBotTunnelProbeAvailable: String = "0",
    trainBotHeartbeat: String,
    trainBotScheduleRequired: String = "0",
    trainBotScheduleFresh: String = "1",
    trainBotScheduleServiceDate: String = "2026-02-28",
    trainBotScheduleRows: String = "1",
    satiksmeBotPid: String = "3234",
    satiksmeBotTunnelEnabled: String = "0",
    satiksmeBotTunnelSupervisorPid: String = "",
    satiksmeBotTunnelPid: String = "",
    satiksmeBotTunnelPublicBaseUrl: String = "",
    satiksmeBotPublicRootCode: String = "000",
    satiksmeBotPublicAppCode: String = "000",
    satiksmeBotTunnelProbeAvailable: String = "0",
    satiksmeBotHeartbeat: String = trainBotHeartbeat,
    siteNotifierPid: String,
    siteNotifierHeartbeat: String,
    supervisorLoopHeartbeat: String = siteNotifierHeartbeat,
    siteNotifierHelperHealthy: String = "1",
    siteNotifierHelperReason: String = "daemon heartbeat is fresh",
    subscriptionBotPid: String = "4234",
    subscriptionBotHeartbeat: String = siteNotifierHeartbeat,
    vpnHealth: String,
    vpnEnabledEffective: String = "1",
    vpnTailscaledLive: String = "1",
    vpnTailscaledSock: String = "1",
    vpnTailnetIpv4: String = "100.64.0.10",
    vpnGuardChainIpv4: String = "1",
    vpnGuardChainIpv6: String = "1",
    managementEnabled: String = vpnEnabledEffective,
    managementHealthy: String = if (vpnEnabledEffective == "1" && vpnHealth == "1") "1" else "0",
    managementReason: String = when {
      vpnEnabledEffective != "1" -> "disabled"
      vpnHealth != "1" -> "vpn_unhealthy"
      else -> "ok"
    },
    managementAuthConsistent: String = "1",
    managementAuthWarningReason: String = "ok",
    managementSshListener: String = "1",
    managementSshAuthMode: String = "key_password",
    managementSshPasswordAuthRequested: String = "1",
    managementSshPasswordAuthReady: String = "1",
    managementSshKeyAuthRequested: String = "1",
    managementSshKeyAuthReady: String = "1",
    managementPmPath: String = "/system/bin/pm",
    managementAmPath: String = "/system/bin/am",
    managementLogcatPath: String = "/system/bin/logcat",
    managementWirelessDebugEnabled: String = "1",
    managementWirelessDebugTlsPort: String = "43463",
    managementWirelessDebugLive: String = "1",
    managementWirelessDebugLivePorts: String = "43463",
    managementWirelessDebugHealthy: String = "1",
    managementWirelessDebugReason: String = "ok",
    managementWifiEnabled: String = "1",
    managementWifiConnected: String = "1",
    managementWifiIpv4: String = "192.168.1.44",
    managementMobileIface: String = "",
    managementMobileIpv4: String = "",
    managementActiveTransport: String = "wifi",
    managementPublicIpv4Candidate: String = ddnsLastIpv4,
    managementNetworkFingerprint: String = "transport=wifi;wifi_enabled=1;wifi_connected=1;wifi_ipv4=192.168.1.44;mobile_iface=none;mobile_ipv4=none;adbd_ports=43463;public_ipv4=${ddnsLastIpv4}",
    remoteDohTokenizedCode: String = "404",
    remoteDohBareCode: String = "200",
    remoteIdentityInjectCode: String = "000",
    remotePublicBaseUrl: String = "https://dns.jolkins.id.lv",
    remotePublicRootCode: String = "200",
    remotePublicProbeAvailable: String = "1",
    remotePublicDohTokenizedCode: String = "404",
    remotePublicDohBareCode: String = "200",
    remotePublicIdentityInjectCode: String = "000"
  ): String {
    return buildString {
      appendLine("__PIXEL_HEALTH_ID_U__")
      appendLine(idU)
      appendLine("__PIXEL_HEALTH_LISTENERS__")
      append(listeners)
      appendLine("__PIXEL_HEALTH_DDNS_EPOCH__")
      appendLine(ddnsEpoch)
      appendLine("__PIXEL_HEALTH_DDNS_LAST_IPV4__")
      appendLine(ddnsLastIpv4)
      appendLine("__PIXEL_HEALTH_SUPERVISOR_LOOP_HEARTBEAT__")
      appendLine(supervisorLoopHeartbeat)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_PID__")
      appendLine(trainBotPid)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_ENABLED__")
      appendLine(trainBotTunnelEnabled)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_SUPERVISOR_PID__")
      appendLine(trainBotTunnelSupervisorPid)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PID__")
      appendLine(trainBotTunnelPid)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL__")
      appendLine(trainBotTunnelPublicBaseUrl)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_ROOT_CODE__")
      appendLine(trainBotPublicRootCode)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_APP_CODE__")
      appendLine(trainBotPublicAppCode)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE__")
      appendLine(trainBotTunnelProbeAvailable)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_HEARTBEAT__")
      appendLine(trainBotHeartbeat)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_REQUIRED__")
      appendLine(trainBotScheduleRequired)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_FRESH__")
      appendLine(trainBotScheduleFresh)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_SERVICE_DATE__")
      appendLine(trainBotScheduleServiceDate)
      appendLine("__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_ROWS__")
      appendLine(trainBotScheduleRows)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_PID__")
      appendLine(satiksmeBotPid)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_ENABLED__")
      appendLine(satiksmeBotTunnelEnabled)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_SUPERVISOR_PID__")
      appendLine(satiksmeBotTunnelSupervisorPid)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PID__")
      appendLine(satiksmeBotTunnelPid)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL__")
      appendLine(satiksmeBotTunnelPublicBaseUrl)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_ROOT_CODE__")
      appendLine(satiksmeBotPublicRootCode)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_APP_CODE__")
      appendLine(satiksmeBotPublicAppCode)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE__")
      appendLine(satiksmeBotTunnelProbeAvailable)
      appendLine("__PIXEL_HEALTH_SATIKSME_BOT_HEARTBEAT__")
      appendLine(satiksmeBotHeartbeat)
      appendLine("__PIXEL_HEALTH_SITE_NOTIFIER_PID__")
      appendLine(siteNotifierPid)
      appendLine("__PIXEL_HEALTH_SITE_NOTIFIER_HEARTBEAT__")
      appendLine(siteNotifierHeartbeat)
      appendLine("__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_HEALTHY__")
      appendLine(siteNotifierHelperHealthy)
      appendLine("__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_REASON__")
      appendLine(siteNotifierHelperReason)
      appendLine("__PIXEL_HEALTH_SUBSCRIPTION_BOT_PID__")
      appendLine(subscriptionBotPid)
      appendLine("__PIXEL_HEALTH_SUBSCRIPTION_BOT_HEARTBEAT__")
      appendLine(subscriptionBotHeartbeat)
      appendLine("__PIXEL_HEALTH_VPN_HEALTH__")
      appendLine(vpnHealth)
      appendLine("__PIXEL_HEALTH_VPN_ENABLED_EFFECTIVE__")
      appendLine(vpnEnabledEffective)
      appendLine("__PIXEL_HEALTH_VPN_TAILSCALED_LIVE__")
      appendLine(vpnTailscaledLive)
      appendLine("__PIXEL_HEALTH_VPN_TAILSCALED_SOCK__")
      appendLine(vpnTailscaledSock)
      appendLine("__PIXEL_HEALTH_VPN_TAILNET_IPV4__")
      appendLine(vpnTailnetIpv4)
      appendLine("__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV4__")
      appendLine(vpnGuardChainIpv4)
      appendLine("__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV6__")
      appendLine(vpnGuardChainIpv6)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_ENABLED__")
      appendLine(managementEnabled)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_HEALTHY__")
      appendLine(managementHealthy)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_REASON__")
      appendLine(managementReason)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_AUTH_CONSISTENT__")
      appendLine(managementAuthConsistent)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_AUTH_WARNING_REASON__")
      appendLine(managementAuthWarningReason)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_SSH_LISTENER__")
      appendLine(managementSshListener)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_SSH_AUTH_MODE__")
      appendLine(managementSshAuthMode)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED__")
      appendLine(managementSshPasswordAuthRequested)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_READY__")
      appendLine(managementSshPasswordAuthReady)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_REQUESTED__")
      appendLine(managementSshKeyAuthRequested)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_READY__")
      appendLine(managementSshKeyAuthReady)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_PM_PATH__")
      appendLine(managementPmPath)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_AM_PATH__")
      appendLine(managementAmPath)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_LOGCAT_PATH__")
      appendLine(managementLogcatPath)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_ENABLED__")
      appendLine(managementWirelessDebugEnabled)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT__")
      appendLine(managementWirelessDebugTlsPort)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE__")
      appendLine(managementWirelessDebugLive)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS__")
      appendLine(managementWirelessDebugLivePorts)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_HEALTHY__")
      appendLine(managementWirelessDebugHealthy)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_REASON__")
      appendLine(managementWirelessDebugReason)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIFI_ENABLED__")
      appendLine(managementWifiEnabled)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIFI_CONNECTED__")
      appendLine(managementWifiConnected)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_WIFI_IPV4__")
      appendLine(managementWifiIpv4)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_MOBILE_IFACE__")
      appendLine(managementMobileIface)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_MOBILE_IPV4__")
      appendLine(managementMobileIpv4)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_ACTIVE_TRANSPORT__")
      appendLine(managementActiveTransport)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_PUBLIC_IPV4_CANDIDATE__")
      appendLine(managementPublicIpv4Candidate)
      appendLine("__PIXEL_HEALTH_MANAGEMENT_NETWORK_FINGERPRINT__")
      appendLine(managementNetworkFingerprint)
      appendLine("__PIXEL_HEALTH_REMOTE_DOH_TOKENIZED_CODE__")
      appendLine(remoteDohTokenizedCode)
      appendLine("__PIXEL_HEALTH_REMOTE_DOH_BARE_CODE__")
      appendLine(remoteDohBareCode)
      appendLine("__PIXEL_HEALTH_REMOTE_IDENTITY_INJECT_CODE__")
      appendLine(remoteIdentityInjectCode)
      appendLine("__PIXEL_HEALTH_REMOTE_PUBLIC_BASE_URL__")
      appendLine(remotePublicBaseUrl)
      appendLine("__PIXEL_HEALTH_REMOTE_PUBLIC_ROOT_CODE__")
      appendLine(remotePublicRootCode)
      appendLine("__PIXEL_HEALTH_REMOTE_PUBLIC_PROBE_AVAILABLE__")
      appendLine(remotePublicProbeAvailable)
      appendLine("__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_TOKENIZED_CODE__")
      appendLine(remotePublicDohTokenizedCode)
      appendLine("__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_BARE_CODE__")
      appendLine(remotePublicDohBareCode)
      appendLine("__PIXEL_HEALTH_REMOTE_PUBLIC_IDENTITY_INJECT_CODE__")
      appendLine(remotePublicIdentityInjectCode)
      appendLine("__PIXEL_HEALTH_DONE__")
    }
  }
}
