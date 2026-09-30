package lv.jolkins.pixelorchestrator.app.health

import android.os.Bundle
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.DayOfWeek
import java.time.ZonedDateTime
import lv.jolkins.pixelorchestrator.app.WeeklyCleanupSchedulePolicy
import lv.jolkins.pixelorchestrator.app.NativeRedeployPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import lv.jolkins.pixelorchestrator.coreconfig.*
import lv.jolkins.pixelorchestrator.health.*
import lv.jolkins.pixelorchestrator.supervisor.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Installed JNI and persistence; all service effects target an isolated fixture. */
@RunWith(AndroidJUnit4::class)
class SupervisorStateInstrumentedTest {
  @Test fun nativeRedeployPolicyPreservesInstalledStabilityAndRegressionFences() {
    val config = StackConfigV1(redeploy = RedeployConfig(20, 1, 1))
    val policy = NativeRedeployPolicy.policy(config)
    assertEquals(20_000L, policy.healthWaitMillis)
    val pre = HealthSnapshot(sshHealthy = true, moduleHealth = mapOf("ticket_screen" to ModuleHealthState(true)))
    val targets = setOf("ticket_screen")
    val supported = linkedSetOf("ssh", "ticket_screen")
    val gates = listOf("ticket_screen")
    var state = NativeRedeployPolicy.begin(0, policy, pre, targets, supported)
    var decision = NativeRedeployPolicy.step(0, policy, state, pre, gates, emptySet())
    assertFalse(decision.terminal)
    state = decision.state
    val regression = pre.copy(sshHealthy = false)
    decision = NativeRedeployPolicy.step(500, policy, state, regression, gates, emptySet())
    assertFalse(decision.terminal)
    decision = NativeRedeployPolicy.step(1_500, policy, decision.state, regression, gates, emptySet())
    assertTrue(decision.terminal)
    assertFalse(decision.success)
    assertEquals(listOf("ssh"), decision.regressedNeighbors)
    assertTrue(decision.stabilityWindowSatisfied)
    decision = NativeRedeployPolicy.step(1_000, policy, state, pre, gates, emptySet())
    assertTrue(decision.success)
    val disabled = pre.copy(moduleHealth = mapOf("ticket_screen" to ModuleHealthState(false, "disabled")))
    assertTrue(NativeRedeployPolicy.ready(disabled, "ticket_screen", targets))
    assertFalse(NativeRedeployPolicy.ready(disabled, "ticket_screen", emptySet()))
    assertEquals("neighbor_regression_rolled_back", NativeRedeployPolicy.rollbackCode(true, listOf("ssh"), true))
    assertEquals("ticket_screen", NativeRedeployPolicy.spec("ticket_screen").runtimeActionComponent)
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
      putString("redeploy_native_fixture", "PASS installed_JNI=true transient_regression=true exact_grace=true disabled_gate=true rollback_code=true no_live_mutation=true")
    })
  }

  @Test fun nativeBackoffAndCalendarUseInstalledLibraryWithoutChangingLiveServices() {
    var now = 1_000L
    val policy = BackoffPolicy(5, 20, 60, 3) { now }
    assertEquals(BackoffDecision(false, 5, 1), policy.recordRestart())
    assertEquals(BackoffDecision(false, 10, 2), policy.recordRestart())
    assertEquals(BackoffDecision(false, 20, 3), policy.recordRestart())
    assertEquals(BackoffDecision(true, 120, 4), policy.recordRestart())
    now = 1_060L // Equality retains the original window.
    assertEquals(BackoffDecision(true, 120, 5), policy.recordRestart())
    now++
    assertEquals(BackoffDecision(false, 5, 1), policy.recordRestart())
    policy.reset()
    assertEquals(BackoffDecision(false, 5, 1), policy.recordRestart())
    val current = ZonedDateTime.now()
    var expected = current.toLocalDate()
      .with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
      .atTime(3, 0).atZone(current.zone)
    if (!expected.isAfter(current)) expected = expected.toLocalDate().plusWeeks(1).atTime(3, 0).atZone(current.zone)
    assertEquals(expected, WeeklyCleanupSchedulePolicy.nextRunAfter(current))
    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
      putString("maintenance_fixture_result", "MAINTENANCE_NATIVE_OK backoff=7 calendar=1 no_live_mutation=true")
      putString("maintenance_next_run", expected.toInstant().toString())
    })
  }

  @Test fun nativeSupervisorPersistsActiveControllerRecoveryWithoutReplayingStarts() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val liveConfig = File(context.filesDir, "stack-store/orchestrator-config-v1.json")
    val liveBefore = liveConfig.readBytes()
    val dir = File(context.filesDir, "native-supervisor-acceptance")
    assertFalse("Fixture must not already exist", dir.exists())
    val config = StackConfigV1().let { it.copy(
      ddns = it.ddns.copy(enabled=false),
      supervision = it.supervision.copy(healthPollSeconds=60),
      modules = it.modules.mapValues { (name, module) -> module.copy(enabled=name=="ticket_screen") }
    ) }
    val store = StackStore(File(dir,"config.json").toPath(),File(dir,"state.json").toPath())
    var starts = 0
    var stops = 0
    var healthy = false
    val controller = object : ComponentController, AutoStartAwareComponentController, ModuleHealthAwareComponentController {
      override val name = "ticket_screen"
      override suspend fun start(): Boolean { starts++; healthy=starts>1; return healthy }
      override suspend fun stop(): Boolean { stops++; healthy=false; return true }
      override suspend fun health(): Boolean = healthy
      override suspend fun shouldAutoStart(): Boolean = true
      override suspend fun moduleHealthState(): ModuleHealthState = ModuleHealthState(healthy,if(healthy)"running" else "degraded")
    }
    // The first test-controller start fails; the existing generic recovery path
    // rechecks it, stops it and starts successfully. No RootExecutor, live
    // Ticket service, phone action or network is used.
    val checker = RuntimeHealthChecker(CommandRunner { CommandResult(false,"","") })
    val engine = SupervisorEngine({ config },store,checker,mapOf("ticket_screen" to controller))
    try {
      store.saveConfig(config)
      store.saveState(StackStateV1())
      engine.startAll()
      withTimeout(10_000) {
        while (store.loadStateOrDefault().operationLog.none { it.component=="ticket_screen" && it.action=="auto_restart" }) delay(20)
      }
      val fresh = StackStore(File(dir,"config.json").toPath(),File(dir,"state.json").toPath())
      val saved = fresh.loadStateOrDefault()
      assertEquals(2, starts)
      assertEquals(1, stops)
      assertTrue(saved.supervisorLoopHeartbeatEpochSeconds > 0)
      assertEquals(ServiceStatus.RUNNING,saved.services["supervisor"]?.status)
      assertEquals(ServiceStatus.RUNNING,saved.services["ticket_screen"]?.status)
      assertEquals(1,saved.operationLog.count { it.component=="ticket_screen" && it.action=="auto_restart" && it.success && it.details=="delay=5s rapid=1 stop=ok" })
      assertFalse(saved.lastHealthSnapshot.rootGranted)
      assertFalse(saved.moduleState.getValue("ticket_screen").healthy)
      assertEquals(0x180,Os.stat(File(dir,"state.json").path).st_mode and 0x1ff)
      engine.resumeSupervision()
      assertEquals("Active resume must not replay starts",2,starts)
      engine.runHealthCheck(HealthScope.FULL)
      assertEquals("Manual health must not replay starts",2,starts)
      assertTrue(fresh.loadStateOrDefault().operationLog.last().let { it.component=="health" && it.action=="check" })
      assertTrue(fresh.loadStateOrDefault().moduleState.getValue("ticket_screen").healthy)
      engine.stopAll()
      assertEquals(2,stops)
      assertEquals(ServiceStatus.STOPPED,fresh.loadStateOrDefault().services["supervisor"]?.status)
      engine.resumeSupervision()
      assertEquals("Stopped supervision must remain stopped",2,starts)
      instrumentation.sendStatus(0,Bundle().apply {
        putString("supervisor_fixture_result","SUPERVISOR_STATE_OK starts=2 stops=2 auto_restart=1")
        putString("supervisor_fixture_pid",android.os.Process.myPid().toString())
      })
    } finally {
      if(stops<2) engine.stopAll()
      assertArrayEquals("Live config must remain unchanged",liveBefore,liveConfig.readBytes())
      assertTrue(!dir.exists() || dir.deleteRecursively())
    }
  }
}
