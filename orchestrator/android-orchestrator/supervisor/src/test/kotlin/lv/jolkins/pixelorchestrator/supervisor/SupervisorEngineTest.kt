package lv.jolkins.pixelorchestrator.supervisor

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.coreconfig.ServiceRuntimeState
import lv.jolkins.pixelorchestrator.coreconfig.ServiceStatus
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStateV1
import lv.jolkins.pixelorchestrator.coreconfig.StackStore
import lv.jolkins.pixelorchestrator.health.CommandResult
import lv.jolkins.pixelorchestrator.health.CommandRunner
import lv.jolkins.pixelorchestrator.health.RuntimeHealthChecker
import org.junit.Test

class SupervisorEngineTest {

  @Test
  fun skipsDisabledAppModulesDuringStartAllAndLoopRecovery() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val satiksmeBot = CountingController("satiksme_bot")
    val siteNotifier = CountingController("site_notifier")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      vpn = StackConfigV1().vpn.copy(enabled = true),
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1),
      modules = mapOf(
        "train_bot" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false),
        "satiksme_bot" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false),
        "site_notifier" to lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig(enabled = false)
      )
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "satiksme_bot" to satiksmeBot,
        "site_notifier" to siteNotifier
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_500) { store.saveStateCalls >= 2 }, "loop cycle did not complete")
      assertEquals(1, dns.startCalls)
      assertEquals(1, ssh.startCalls)
      assertEquals(1, vpn.startCalls)
      assertEquals(0, trainBot.startCalls)
      assertEquals(0, satiksmeBot.startCalls)
      assertEquals(0, siteNotifier.startCalls)
      assertEquals(ServiceStatus.STOPPED, store.state.services["train_bot"]?.status)
      assertEquals(ServiceStatus.STOPPED, store.state.services["satiksme_bot"]?.status)
      assertEquals(ServiceStatus.STOPPED, store.state.services["site_notifier"]?.status)
      assertTrue(store.state.operationLog.any { it.component == "train_bot" && it.action == "start_skipped" })
      assertTrue(store.state.operationLog.any { it.component == "satiksme_bot" && it.action == "start_skipped" })
      assertTrue(store.state.operationLog.any { it.component == "site_notifier" && it.action == "start_skipped" })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun savesStateOncePerLoopCycleAndIgnoresInconclusiveRemotePublicProbeFailures() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, unhealthyFails = 3),
      remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false, watchdogEscalateRuntimeRestart = true)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
            remotePublicRootCode = "000",
            remotePublicProbeAvailable = "1",
            remotePublicDohBareCode = "000"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertEquals(1, store.saveStateCalls, "startAll should persist final state once")

      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "loop cycle did not complete")
      assertEquals(2, store.saveStateCalls, "single loop cycle should save once")
      assertEquals(1, remote.startCalls, "remote should only be started by startAll")
      assertEquals(1, dns.startCalls, "dns should not restart for inconclusive public probe failures")
      assertEquals(ServiceStatus.DEGRADED, store.state.services["remote"]?.status)
      assertEquals(ServiceStatus.RUNNING, store.state.services["dns"]?.status)
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun restartsDnsWhenRemotePublicFailuresReachThresholdAndResetsAfterRecovery() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    var currentProbe = probeOutput(
      idU = "0",
      listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
      ddnsEpoch = nowEpoch.toString(),
      trainBotPid = "1234",
      trainBotHeartbeat = nowEpoch.toString(),
      siteNotifierPid = "2234",
      siteNotifierHeartbeat = nowEpoch.toString(),
      vpnHealth = "1",
      remotePublicRootCode = "500",
      remotePublicProbeAvailable = "1",
      remotePublicDohBareCode = "404"
    )
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, unhealthyFails = 2),
      remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false, watchdogEscalateRuntimeRestart = true)
    )
    val checker = RuntimeHealthChecker(CommandRunner { CommandResult(ok = true, stdout = currentProbe, stderr = "") })

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "first loop cycle did not complete")
      assertEquals(1, dns.startCalls, "dns should not restart on first remote public failure")

      assertTrue(waitFor(timeoutMs = 12_000) { dns.startCalls >= 2 }, "dns auto-restart did not trigger after threshold")

      currentProbe = probeOutput(
        idU = "0",
        listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
        ddnsEpoch = nowEpoch.toString(),
        trainBotPid = "1234",
        trainBotHeartbeat = nowEpoch.toString(),
        siteNotifierPid = "2234",
        siteNotifierHeartbeat = nowEpoch.toString(),
        vpnHealth = "1",
        remotePublicRootCode = "200",
        remotePublicProbeAvailable = "1",
        remotePublicDohBareCode = "200"
      )
      val recovered = engine.runHealthCheck(lv.jolkins.pixelorchestrator.health.HealthScope.FULL)
      assertTrue(recovered.remoteHealthy)
      assertEquals("true", recovered.evidence["remote_public_root_healthy"])
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun doesNotRestartDnsForRemoteFailureWhenEscalationIsDisabled() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10),
      remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false, watchdogEscalateRuntimeRestart = false)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "loop cycle did not complete")
      assertEquals(1, dns.startCalls, "dns should not be restarted when remote escalation is disabled")
      assertEquals(1, remote.startCalls, "remote should only be started by startAll")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun doesNotRestartDnsForRemoteFailureWhenRemoteNotRequired() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10),
      vpn = StackConfigV1().vpn.copy(enabled = true),
      remote = StackConfigV1().remote.copy(dohEnabled = false, dotEnabled = false)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "loop cycle did not complete")
      assertEquals(1, dns.startCalls, "dns should not be restarted when remote is optional")
      assertEquals(0, remote.startCalls, "remote should stay stopped when the module is disabled")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun autoSyncsDdnsWhenHeartbeatIsStale() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10),
      ddns = StackConfigV1().ddns.copy(enabled = true, intervalSeconds = 120)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
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
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "loop cycle did not complete")
      assertEquals(2, ddns.startCalls, "ddns should be auto-synced when heartbeat is stale")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun autoRestartStopsUnhealthyComponentBeforeStartingAgain() = runBlocking {
    val store = InMemoryStackStore()
    val siteNotifier = CountingController("site_notifier")
    val nowEpoch = System.currentTimeMillis() / 1000
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:2222\n",
            ddnsEpoch = nowEpoch.toString(),
            trainBotPid = "",
            trainBotHeartbeat = "",
            siteNotifierPid = "",
            siteNotifierHeartbeat = "",
            siteNotifierHelperHealthy = "0",
            siteNotifierHelperReason = "daemon_not_initialized",
            supervisorLoopHeartbeat = nowEpoch.toString(),
            vpnHealth = "0"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { StackConfigV1(supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1)) },
      stateStore = store,
      healthChecker = checker,
      components = mapOf("site_notifier" to siteNotifier)
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 2_500) { siteNotifier.startCalls >= 2 }, "site_notifier should auto-restart")
      assertTrue(siteNotifier.stopCalls >= 1, "auto-restart should stop before starting again")
      assertTrue(
        store.state.operationLog.any { it.component == "site_notifier" && it.action == "auto_restart" && it.details.contains("stop=ok") }
      )
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun skipsAutoRestartWhenComponentHealthHasAlreadyRecovered() = runBlocking {
    val store = InMemoryStackStore()
    val siteNotifier = CountingController("site_notifier").apply {
      healthOk = true
    }
    val nowEpoch = System.currentTimeMillis() / 1000
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:2222\n",
            ddnsEpoch = nowEpoch.toString(),
            trainBotPid = "",
            trainBotHeartbeat = "",
            siteNotifierPid = "",
            siteNotifierHeartbeat = "",
            siteNotifierHelperHealthy = "0",
            siteNotifierHelperReason = "daemon_not_initialized",
            supervisorLoopHeartbeat = nowEpoch.toString(),
            vpnHealth = "0"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { StackConfigV1(supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1)) },
      stateStore = store,
      healthChecker = checker,
      components = mapOf("site_notifier" to siteNotifier)
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_500) { store.saveStateCalls >= 2 }, "loop cycle did not complete")
      assertEquals(1, siteNotifier.startCalls, "recovered component should not restart")
      assertEquals(0, siteNotifier.stopCalls, "recovered component should not be stopped")
      assertTrue(siteNotifier.healthCalls >= 1, "health should be rechecked before restarting")
      assertTrue(store.state.operationLog.any { it.component == "site_notifier" && it.action == "auto_restart_skipped" })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun entersConvergenceModeAndRestartsVpnImmediatelyWhenTailnetDisappears() = runBlocking {
    val store = InMemoryStackStore()
    store.state = StackStateV1(
      lastNetworkFingerprint = "transport=wifi;wifi_enabled=1;wifi_connected=1;wifi_ipv4=192.168.1.44;mobile_iface=none;mobile_ipv4=none;adbd_ports=43463;public_ipv4=62.205.193.194",
      lastObservedPublicIpv4 = "62.205.193.194"
    )
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(
        healthPollSeconds = 10,
        networkConvergenceWindowSeconds = 30,
        networkConvergencePollSeconds = 1,
        managementUnhealthyFails = 5
      ),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
            ddnsEpoch = nowEpoch.toString(),
            ddnsLastIpv4 = "10.0.0.5",
            trainBotPid = "1234",
            trainBotHeartbeat = nowEpoch.toString(),
            siteNotifierPid = "2234",
            siteNotifierHeartbeat = nowEpoch.toString(),
            vpnHealth = "1",
            vpnTailnetIpv4 = "",
            managementHealthy = "0",
            managementReason = "tailnet_ip_missing",
            managementWifiConnected = "0",
            managementWifiIpv4 = "",
            managementMobileIface = "rmnet_data0",
            managementMobileIpv4 = "10.10.0.2",
            managementActiveTransport = "cellular",
            managementPublicIpv4Candidate = "10.0.0.5",
            managementNetworkFingerprint = "transport=cellular;wifi_enabled=1;wifi_connected=0;wifi_ipv4=none;mobile_iface=rmnet_data0;mobile_ipv4=10.10.0.2;adbd_ports=43463;public_ipv4=10.0.0.5"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 2_000) { vpn.startCalls >= 2 }, "vpn should restart immediately during convergence")
      assertEquals(1, ssh.startCalls, "ssh should wait while vpn-first recovery is happening")
      assertTrue(store.state.networkConvergenceUntilEpochSeconds > nowEpoch, "convergence window should be active")
      assertTrue(store.state.lastNetworkFingerprint.contains("transport=cellular"))
      assertTrue(store.state.operationLog.any { it.component == "supervisor" && it.action == "network_change" })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun autoSyncsDdnsImmediatelyWhenObservedPublicIpChanges() = runBlocking {
    val store = InMemoryStackStore()
    store.state = StackStateV1(
      lastNetworkFingerprint = "transport=wifi;wifi_enabled=1;wifi_connected=1;wifi_ipv4=192.168.1.44;mobile_iface=none;mobile_ipv4=none;adbd_ports=43463;public_ipv4=62.205.193.194",
      lastObservedPublicIpv4 = "62.205.193.194"
    )
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, networkConvergenceWindowSeconds = 30, networkConvergencePollSeconds = 1),
      vpn = StackConfigV1().vpn.copy(enabled = true),
      remote = StackConfigV1().remote.copy(dohEnabled = true, dotEnabled = false),
      ddns = StackConfigV1().ddns.copy(enabled = true, intervalSeconds = 120, syncOnNetworkChange = true, movePolicy = "stick_new_ip")
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
            managementWifiConnected = "0",
            managementWifiIpv4 = "",
            managementMobileIface = "rmnet_data0",
            managementMobileIpv4 = "10.10.0.2",
            managementActiveTransport = "cellular",
            managementPublicIpv4Candidate = "10.0.0.5",
            managementNetworkFingerprint = "transport=cellular;wifi_enabled=1;wifi_connected=0;wifi_ipv4=none;mobile_iface=rmnet_data0;mobile_ipv4=10.10.0.2;adbd_ports=43463;public_ipv4=10.0.0.5"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 2_000) { ddns.startCalls >= 2 }, "ddns should sync immediately when the observed public IP changes")
      assertEquals("10.0.0.5", store.state.lastObservedPublicIpv4)
      assertTrue(store.state.operationLog.any { it.component == "ddns" && it.action == "auto_sync" && it.details.contains("reason=public_ip_changed") })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun keepsRetryingDdnsDuringConvergenceWhileDirectPublicPathIsTransitioning() = runBlocking {
    val store = InMemoryStackStore()
    store.state = StackStateV1(
      lastNetworkFingerprint = "transport=wifi;wifi_enabled=1;wifi_connected=1;wifi_ipv4=192.168.1.44;mobile_iface=none;mobile_ipv4=none;adbd_ports=43463;public_ipv4=62.205.193.194",
      lastObservedPublicIpv4 = "62.205.193.194"
    )
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, networkConvergenceWindowSeconds = 30, networkConvergencePollSeconds = 1),
      ddns = StackConfigV1().ddns.copy(enabled = true, intervalSeconds = 120, syncOnNetworkChange = true, movePolicy = "stick_new_ip")
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
            managementWifiConnected = "0",
            managementWifiIpv4 = "",
            managementMobileIface = "rmnet_data0",
            managementMobileIpv4 = "10.10.0.2",
            managementActiveTransport = "cellular",
            managementPublicIpv4Candidate = "10.0.0.5",
            managementNetworkFingerprint = "transport=cellular;wifi_enabled=1;wifi_connected=0;wifi_ipv4=none;mobile_iface=rmnet_data0;mobile_ipv4=10.10.0.2;adbd_ports=43463;public_ipv4=10.0.0.5"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 3_500) { ddns.startCalls >= 3 }, "ddns should keep retrying while the direct public path is still transitioning during convergence")
      assertEquals(ServiceStatus.DEGRADED, store.state.services["remote"]?.status)
      assertEquals(0L, store.state.lastDirectPublicFailureEpochSeconds)
      assertEquals("10.0.0.5", store.state.lastObservedPublicIpv4)
      assertTrue(store.state.operationLog.any { it.component == "remote" && it.action == "direct_public_transition" })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun keepsSteadyStateBehaviorWhenNetworkFingerprintDoesNotChange() = runBlocking {
    val store = InMemoryStackStore()
    store.state = StackStateV1(
      lastNetworkFingerprint = "transport=wifi;wifi_enabled=1;wifi_connected=1;wifi_ipv4=192.168.1.44;mobile_iface=none;mobile_ipv4=none;adbd_ports=43463;public_ipv4=62.205.193.194",
      lastObservedPublicIpv4 = "62.205.193.194"
    )
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, networkConvergenceWindowSeconds = 30, networkConvergencePollSeconds = 1),
      ddns = StackConfigV1().ddns.copy(enabled = true, intervalSeconds = 120, syncOnNetworkChange = true, movePolicy = "stick_new_ip")
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
            managementPublicIpv4Candidate = "62.205.193.194",
            managementNetworkFingerprint = "transport=wifi;wifi_enabled=1;wifi_connected=1;wifi_ipv4=192.168.1.44;mobile_iface=none;mobile_ipv4=none;adbd_ports=43463;public_ipv4=62.205.193.194"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      delay(1_500)
      assertEquals(1, ddns.startCalls, "steady-state health should not force extra ddns syncs")
      assertEquals(0L, store.state.networkConvergenceUntilEpochSeconds)
      assertEquals("62.205.193.194", store.state.lastObservedPublicIpv4)
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun repairsManagementAuthDriftWithSingleSshRestart() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, managementUnhealthyFails = 1),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
            managementHealthy = "1",
            managementReason = "ok",
            managementAuthConsistent = "0",
            managementAuthWarningReason = "password_auth_runtime_mismatch"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      delay(1_500)
      assertEquals(1, vpn.startCalls, "vpn should not restart for auth drift alone")
      assertEquals(2, ssh.startCalls, "ssh should restart once to repair auth drift")
      assertEquals(ServiceStatus.RUNNING, store.state.services["management"]?.status)
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun resumeSupervisionStartsLoopWithoutReplayingComponentStarts() = runBlocking {
    val store = InMemoryStackStore()
    store.state = StackStateV1(
      services = mapOf("supervisor" to ServiceRuntimeState(status = ServiceStatus.DEGRADED))
    )
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10),
      ddns = StackConfigV1().ddns.copy(enabled = true, intervalSeconds = 120)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.resumeSupervision()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "resume supervision loop did not complete")
      assertEquals(1, ddns.startCalls, "ddns should auto-sync when supervision resumes")
      assertEquals(0, dns.startCalls, "resume should not replay component starts")
      assertEquals(0, ssh.startCalls, "resume should not replay component starts")
      assertEquals(0, trainBot.startCalls, "resume should not replay component starts")
      assertEquals(0, siteNotifier.startCalls, "resume should not replay component starts")
      assertEquals(0, remote.startCalls, "resume should not replay component starts")
      assertTrue(store.state.supervisorLoopHeartbeatEpochSeconds > 0L, "resume should refresh the supervisor loop heartbeat")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun resumeSupervisionDoesNothingWhenSupervisorWasStopped() = runBlocking {
    val store = InMemoryStackStore()
    store.state = StackStateV1(
      services = mapOf("supervisor" to ServiceRuntimeState(status = ServiceStatus.STOPPED))
    )
    val ddns = CountingController("ddns")
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
            ddnsEpoch = "0",
            trainBotPid = "1234",
            trainBotHeartbeat = (System.currentTimeMillis() / 1000).toString(),
            siteNotifierPid = "2234",
            siteNotifierHeartbeat = (System.currentTimeMillis() / 1000).toString(),
            vpnHealth = "1"
          ),
          stderr = ""
        )
      }
    )
    val engine = SupervisorEngine(
      configProvider = {
        StackConfigV1(
          supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1),
          ddns = StackConfigV1().ddns.copy(enabled = true, intervalSeconds = 120)
        )
      },
      stateStore = store,
      healthChecker = checker,
      components = mapOf("ddns" to ddns)
    )

    engine.resumeSupervision()
    delay(250)

    assertEquals(0, store.saveStateCalls, "stopped supervision should not auto-resume")
    assertEquals(0, ddns.startCalls, "stopped supervision should not auto-sync ddns")
  }

  @Test
  fun restartsVpnWhenEnabledAndUnhealthy() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\n",
            ddnsEpoch = nowEpoch.toString(),
            trainBotPid = "1234",
            trainBotHeartbeat = nowEpoch.toString(),
            siteNotifierPid = "2234",
            siteNotifierHeartbeat = nowEpoch.toString(),
            vpnHealth = "0"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "loop cycle did not complete")
      assertEquals(2, vpn.startCalls, "vpn should be auto-restarted when enabled and unhealthy")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun restartsVpnFirstWhenManagementFailsBecauseVpnEvidenceIsBad() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
            vpnHealth = "0",
            managementHealthy = "0",
            managementReason = "vpn_unhealthy"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 2_000) { vpn.startCalls >= 2 }, "vpn management recovery did not trigger")
      assertEquals(1, ssh.startCalls, "ssh should not restart for vpn-owned management failures")
      assertTrue(store.state.operationLog.any { it.component == "management" && it.action == "auto_recovery" && it.details.contains("target=vpn") })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun restartsSshWhenManagementFailsWithHealthyVpn() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:53\n",
            ddnsEpoch = nowEpoch.toString(),
            trainBotPid = "1234",
            trainBotHeartbeat = nowEpoch.toString(),
            siteNotifierPid = "2234",
            siteNotifierHeartbeat = nowEpoch.toString(),
            vpnHealth = "1",
            managementHealthy = "0",
            managementReason = "ssh_listener_missing",
            managementSshListener = "0"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 2_000) { ssh.startCalls >= 2 }, "ssh management recovery did not trigger")
      assertEquals(1, vpn.startCalls, "vpn should not restart for ssh-only management failures")
      assertTrue(store.state.operationLog.any { it.component == "management" && it.action == "auto_recovery" && it.details.contains("target=ssh") })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun skipsManagementRestartWhenTargetHasAlreadyRecovered() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh").apply {
      healthOk = true
    }
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:53\n",
            ddnsEpoch = nowEpoch.toString(),
            trainBotPid = "1234",
            trainBotHeartbeat = nowEpoch.toString(),
            siteNotifierPid = "2234",
            siteNotifierHeartbeat = nowEpoch.toString(),
            vpnHealth = "1",
            managementHealthy = "0",
            managementReason = "ssh_listener_missing",
            managementSshListener = "0"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_500) { store.saveStateCalls >= 2 }, "management loop cycle did not complete")
      assertEquals(1, ssh.startCalls, "recovered ssh should not be restarted by management recovery")
      assertEquals(0, ssh.stopCalls, "recovered ssh should not be stopped by management recovery")
      assertTrue(ssh.healthCalls >= 1, "management recovery should recheck target health")
      assertTrue(store.state.operationLog.any { it.component == "management" && it.action == "auto_recovery_skipped" && it.details.contains("target=ssh") })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun coordinatesVpnThenSshRecoveryAfterRepeatedSyntheticManagementFailures() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    var currentProbe = probeOutput(
      idU = "0",
      listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
      ddnsEpoch = nowEpoch.toString(),
      trainBotPid = "1234",
      trainBotHeartbeat = nowEpoch.toString(),
      siteNotifierPid = "2234",
      siteNotifierHeartbeat = nowEpoch.toString(),
      vpnHealth = "1",
      managementHealthy = "0",
      managementReason = "password_auth_not_ready",
      managementSshAuthMode = "password_only",
      managementSshPasswordAuthRequested = "1",
      managementSshPasswordAuthReady = "0",
      managementSshKeyAuthRequested = "0",
      managementSshKeyAuthReady = "0"
    )
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1, managementUnhealthyFails = 2, managementRecoveryCooldownSeconds = 30),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(CommandRunner { CommandResult(ok = true, stdout = currentProbe, stderr = "") })

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_500) { store.saveStateCalls >= 2 }, "first management loop cycle did not complete")
      assertEquals(1, vpn.startCalls, "vpn should not restart before management threshold is reached")
      assertEquals(1, ssh.startCalls, "ssh should not restart before coordinated recovery begins")

      assertTrue(waitFor(timeoutMs = 2_500) { vpn.startCalls >= 2 }, "coordinated vpn recovery did not trigger")
      assertEquals(1, ssh.startCalls, "ssh restart should wait for the post-vpn recovery step")

      currentProbe = probeOutput(
        idU = "0",
        listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
        ddnsEpoch = nowEpoch.toString(),
        trainBotPid = "1234",
        trainBotHeartbeat = nowEpoch.toString(),
        siteNotifierPid = "2234",
        siteNotifierHeartbeat = nowEpoch.toString(),
        vpnHealth = "1",
        managementHealthy = "0",
        managementReason = "password_auth_not_ready",
        managementSshAuthMode = "password_only",
        managementSshPasswordAuthRequested = "1",
        managementSshPasswordAuthReady = "0",
        managementSshKeyAuthRequested = "0",
        managementSshKeyAuthReady = "0"
      )
      assertTrue(waitFor(timeoutMs = 2_500) { ssh.startCalls >= 2 }, "coordinated ssh recovery did not trigger")
      assertTrue(store.state.operationLog.any { it.component == "management" && it.action == "auto_recovery" && it.details.contains("target=vpn") })
      assertTrue(store.state.operationLog.any { it.component == "management" && it.action == "auto_recovery" && it.details.contains("target=ssh") })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun honorsManagementRecoveryCooldownToAvoidFlapping() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val vpn = CountingController("vpn")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val currentProbe = probeOutput(
      idU = "0",
      listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
      ddnsEpoch = nowEpoch.toString(),
      trainBotPid = "1234",
      trainBotHeartbeat = nowEpoch.toString(),
      siteNotifierPid = "2234",
      siteNotifierHeartbeat = nowEpoch.toString(),
      vpnHealth = "1",
      managementHealthy = "0",
      managementReason = "ssh_auth_not_ready",
      managementSshAuthMode = "key_password",
      managementSshPasswordAuthRequested = "1",
      managementSshPasswordAuthReady = "0",
      managementSshKeyAuthRequested = "1",
      managementSshKeyAuthReady = "0"
    )
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 1, managementUnhealthyFails = 1, managementRecoveryCooldownSeconds = 30),
      vpn = StackConfigV1().vpn.copy(enabled = true)
    )
    val checker = RuntimeHealthChecker(CommandRunner { CommandResult(ok = true, stdout = currentProbe, stderr = "") })

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "vpn" to vpn,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 2_500) { vpn.startCalls >= 2 }, "management cooldown test did not trigger vpn recovery")
      assertTrue(waitFor(timeoutMs = 2_500) { ssh.startCalls >= 2 }, "management cooldown test did not trigger ssh recovery")
      val vpnStartsAfterRecovery = vpn.startCalls
      val sshStartsAfterRecovery = ssh.startCalls
      delay(1_500)
      assertEquals(vpnStartsAfterRecovery, vpn.startCalls, "vpn should not flap during management cooldown")
      assertEquals(sshStartsAfterRecovery, ssh.startCalls, "ssh should not flap during management cooldown")
      assertTrue(store.state.operationLog.any { it.component == "management" && it.action == "health_unhealthy" && it.details.contains("cooldown_remaining=") })
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun debouncesTrainBotRestartUntilTunnelFailuresReachThreshold() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, unhealthyFails = 3)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(
          ok = true,
          stdout = probeOutput(
            idU = "0",
            listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
            ddnsEpoch = nowEpoch.toString(),
            trainBotPid = "1234",
            trainBotTunnelEnabled = "1",
            trainBotTunnelSupervisorPid = "4444",
            trainBotTunnelPid = "5555",
            trainBotTunnelPublicBaseUrl = "https://train-bot.jolkins.id.lv",
            trainBotPublicRootCode = "530",
            trainBotPublicAppCode = "530",
            trainBotTunnelProbeAvailable = "1",
            trainBotHeartbeat = nowEpoch.toString(),
            siteNotifierPid = "2234",
            siteNotifierHeartbeat = nowEpoch.toString(),
            vpnHealth = "1"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "first loop cycle did not complete")
      assertEquals(1, trainBot.startCalls, "train bot should not restart on first tunnel failure")
      assertEquals("1", store.state.lastHealthSnapshot.evidence["train_bot_tunnel_failure_count"])
      assertEquals(ServiceStatus.DEGRADED, store.state.services["train_bot"]?.status)

      engine.runHealthCheck(lv.jolkins.pixelorchestrator.health.HealthScope.FULL)
      assertEquals(1, trainBot.startCalls, "manual health check must not trigger restart")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun restartsTrainBotWhenTunnelFailuresReachThresholdAndResetsAfterRecovery() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val trainBot = CountingController("train_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    var currentProbe = probeOutput(
      idU = "0",
      listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
      ddnsEpoch = nowEpoch.toString(),
      trainBotPid = "1234",
      trainBotTunnelEnabled = "1",
      trainBotTunnelSupervisorPid = "4444",
      trainBotTunnelPid = "5555",
      trainBotTunnelPublicBaseUrl = "https://train-bot.jolkins.id.lv",
      trainBotPublicRootCode = "530",
      trainBotPublicAppCode = "530",
      trainBotTunnelProbeAvailable = "1",
      trainBotHeartbeat = nowEpoch.toString(),
      siteNotifierPid = "2234",
      siteNotifierHeartbeat = nowEpoch.toString(),
      vpnHealth = "1"
    )
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, unhealthyFails = 1),
      trainBot = StackConfigV1().trainBot.copy(maxRapidRestarts = 2, rapidWindowSeconds = 300, backoffInitialSeconds = 1, backoffMaxSeconds = 2)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
        CommandResult(ok = true, stdout = currentProbe, stderr = "")
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "train_bot" to trainBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { trainBot.startCalls >= 2 }, "train bot auto-restart did not trigger")
      assertEquals("1", store.state.lastHealthSnapshot.evidence["train_bot_tunnel_failure_count"])

      currentProbe = probeOutput(
        idU = "0",
        listeners = "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\n",
        ddnsEpoch = nowEpoch.toString(),
        trainBotPid = "1234",
        trainBotTunnelEnabled = "1",
        trainBotTunnelSupervisorPid = "4444",
        trainBotTunnelPid = "5555",
        trainBotTunnelPublicBaseUrl = "https://train-bot.jolkins.id.lv",
        trainBotPublicRootCode = "200",
        trainBotPublicAppCode = "200",
        trainBotTunnelProbeAvailable = "1",
        trainBotHeartbeat = nowEpoch.toString(),
        siteNotifierPid = "2234",
        siteNotifierHeartbeat = nowEpoch.toString(),
        vpnHealth = "1"
      )
      val recovered = engine.runHealthCheck(lv.jolkins.pixelorchestrator.health.HealthScope.FULL)
      assertTrue(recovered.trainBotHealthy)
      assertEquals("0", recovered.evidence["train_bot_tunnel_failure_count"])
      assertFalse(recovered.evidence["train_bot_tunnel_healthy"] == "false")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun debouncesSatiksmeBotRestartUntilTunnelFailuresReachThreshold() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val satiksmeBot = CountingController("satiksme_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, unhealthyFails = 3)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
            satiksmeBotTunnelSupervisorPid = "4444",
            satiksmeBotTunnelPid = "5555",
            satiksmeBotTunnelPublicBaseUrl = "https://satiksme-bot.jolkins.id.lv",
            satiksmeBotPublicRootCode = "530",
            satiksmeBotPublicAppCode = "530",
            satiksmeBotTunnelProbeAvailable = "1",
            satiksmeBotHeartbeat = nowEpoch.toString(),
            siteNotifierPid = "2234",
            siteNotifierHeartbeat = nowEpoch.toString(),
            vpnHealth = "1"
          ),
          stderr = ""
        )
      }
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "satiksme_bot" to satiksmeBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 1_000) { store.saveStateCalls >= 2 }, "first loop cycle did not complete")
      assertEquals(1, satiksmeBot.startCalls, "satiksme bot should not restart on first tunnel/public failure")
      assertEquals("1", store.state.lastHealthSnapshot.evidence["satiksme_bot_tunnel_failure_count"])
      assertEquals("public_root_failed", store.state.lastHealthSnapshot.evidence["satiksme_bot_failure_reason"])
      assertEquals(ServiceStatus.DEGRADED, store.state.services["satiksme_bot"]?.status)

      engine.runHealthCheck(lv.jolkins.pixelorchestrator.health.HealthScope.FULL)
      assertEquals(1, satiksmeBot.startCalls, "manual health check must not trigger satiksme restart")
    } finally {
      engine.stopAll()
    }
  }

  @Test
  fun restartsSatiksmeBotImmediatelyWhenHeartbeatIsStale() = runBlocking {
    val store = InMemoryStackStore()
    val dns = CountingController("dns")
    val ssh = CountingController("ssh")
    val satiksmeBot = CountingController("satiksme_bot")
    val siteNotifier = CountingController("site_notifier")
    val ddns = CountingController("ddns")
    val remote = CountingController("remote")

    val nowEpoch = System.currentTimeMillis() / 1000
    val config = StackConfigV1(
      supervision = StackConfigV1().supervision.copy(healthPollSeconds = 10, unhealthyFails = 3),
      satiksmeBot = StackConfigV1().satiksmeBot.copy(backoffInitialSeconds = 1, backoffMaxSeconds = 1)
    )
    val checker = RuntimeHealthChecker(
      CommandRunner {
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
    )

    val engine = SupervisorEngine(
      configProvider = { config },
      stateStore = store,
      healthChecker = checker,
      components = mapOf(
        "dns" to dns,
        "ssh" to ssh,
        "satiksme_bot" to satiksmeBot,
        "site_notifier" to siteNotifier,
        "ddns" to ddns,
        "remote" to remote
      )
    )

    try {
      engine.startAll()
      assertTrue(waitFor(timeoutMs = 2_000) { satiksmeBot.startCalls >= 2 }, "satiksme bot should restart immediately for stale heartbeat")
      assertEquals("heartbeat_stale", store.state.lastHealthSnapshot.evidence["satiksme_bot_failure_reason"])
      assertEquals("0", store.state.lastHealthSnapshot.evidence["satiksme_bot_tunnel_failure_count"])
    } finally {
      engine.stopAll()
    }
  }

  private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val started = System.currentTimeMillis()
    while (System.currentTimeMillis() - started < timeoutMs) {
      if (condition()) {
        return true
      }
      delay(25)
    }
    return condition()
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

  private class CountingController(
    override val name: String
  ) : ComponentController {
    var startCalls: Int = 0
    var stopCalls: Int = 0
    var healthCalls: Int = 0
    var healthOk: Boolean = false

    override suspend fun start(): Boolean {
      startCalls += 1
      return true
    }

    override suspend fun stop(): Boolean {
      stopCalls += 1
      return true
    }

    override suspend fun health(): Boolean {
      healthCalls += 1
      return healthOk
    }
  }

  private class InMemoryStackStore : StackStore(
    configPath = Path.of("/tmp/in-memory-config.json"),
    statePath = Path.of("/tmp/in-memory-state.json")
  ) {
    var state: StackStateV1 = StackStateV1()
    var saveStateCalls: Int = 0

    override fun loadStateOrDefault(): StackStateV1 {
      return state
    }

    override fun saveState(state: StackStateV1) {
      saveStateCalls += 1
      this.state = state
    }
  }
}
