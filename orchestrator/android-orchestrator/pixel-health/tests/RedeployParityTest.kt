package lv.jolkins.pixelorchestrator.app

import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.coreconfig.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RedeployParityTest {
  private val supported = linkedSetOf("ssh","vpn","cpu_frequency","management","ticket_screen","runtime_cleanup")
  private val old = LegacyRedeployPolicy()
  private var comparisons = 0
  private fun equal(a: Any?, b: Any?) { assertEquals(a,b); comparisons++ }

  @Test fun precedenceDisabledGatesOrderingAndConfigurationBoundsMatchFrozenOwner() {
    for (mask in 0..7) for (component in supported + "unknown") for (override in listOf(null, ModuleHealthState(false),ModuleHealthState(true),ModuleHealthState(false,"disabled"),ModuleHealthState(true,"disabled"))) {
      val snapshot = HealthSnapshot(sshHealthy=mask and 1 != 0,vpnHealthy=mask and 2 != 0,managementHealthy=mask and 4 != 0,
        moduleHealth=if (override == null) emptyMap() else mapOf(component to override))
      equal(old.componentHealthy(snapshot,component), NativeRedeployPolicy.healthy(snapshot,component))
      for (disabled in listOf(emptySet(),setOf(component),setOf("unknown"))) {
        equal(old.componentReadyForRedeploy(snapshot,component,disabled), NativeRedeployPolicy.ready(snapshot,component,disabled))
      }
      for (targets in listOf(emptySet(),setOf(component),supported)) {
        equal(old.healthyNeighborsOutsideTarget(snapshot,targets),NativeRedeployPolicy.neighbors(snapshot,targets,supported))
        equal(old.detectNeighborRegressions(snapshot,HealthSnapshot(),targets),NativeRedeployPolicy.neighbors(snapshot,targets,supported,HealthSnapshot()))
      }
    }
    for (wait in listOf(Int.MIN_VALUE,-1,0,1,180,Int.MAX_VALUE)) for (retry in listOf(-1,0,1,Int.MAX_VALUE)) for (grace in listOf(Int.MIN_VALUE,-1,0,1,Int.MAX_VALUE)) {
      val config = StackConfigV1(redeploy=RedeployConfig(wait,retry,grace))
      val expected = old.resolveRedeployPolicy(config); val actual = NativeRedeployPolicy.policy(config)
      equal(listOf(expected.healthWaitMillis,expected.healthRetryMillis,expected.neighborGraceMillis),listOf(actual.healthWaitMillis,actual.healthRetryMillis,actual.neighborGraceMillis))
    }
    for (component in supported + "unknown") for (vpn in listOf(false,true)) for (enabled in listOf(null,false,true)) {
      val config = StackConfigV1(vpn=StackConfigV1().vpn.copy(enabled=vpn), modules=if (enabled==null) emptyMap() else mapOf(component to ModuleConfig(enabled=enabled)))
      equal(old.componentEnabledInConfig(config,component),NativeRedeployPolicy.enabled(config,component))
    }
    println("REDEPLOY_ADMISSION_PARITY_OK comparisons=$comparisons")
  }

  @Test fun actualFrozenLoopMatchesNativeStabilityTimeoutRecoveryAndClockTransitions() = runBlocking {
    val random = Random(55123)
    val pre = HealthSnapshot(sshHealthy=true,vpnHealthy=true,managementHealthy=true,
      moduleHealth=mapOf("ticket_screen" to ModuleHealthState(true),"cpu_frequency" to ModuleHealthState(true)))
    val starts = listOf(0L,-1L,Long.MIN_VALUE,Long.MAX_VALUE-500L)
    var journeys = 0
    for (start in starts) for (grace in listOf(0,1,5)) for (trial in 0 until 80) {
      val config = StackConfigV1(redeploy=RedeployConfig(20,1,grace))
      val policy = NativeRedeployPolicy.policy(config)
      val gates = if (trial % 11 == 0) emptyList() else listOf("ticket_screen")
      val targets = setOf("ticket_screen")
      val disabled = if (trial % 7 == 0) targets else emptySet()
      val snapshots = (0..30).map { index ->
        HealthSnapshot(sshHealthy=index > 20 || random.nextInt(5)!=0,vpnHealthy=index > 20 || random.nextInt(7)!=0,managementHealthy=true,
          moduleHealth=mapOf("cpu_frequency" to ModuleHealthState(true),"ticket_screen" to ModuleHealthState(index>20 || random.nextBoolean(),if (trial%7==0) "disabled" else "running")))
      }
      // Include backward clock motion and exact deadline/grace edges; overflow follows Kotlin Long.
      val times = (0..30).map { index -> start + index*1000L - if (trial%13==0 && index in 3..4) 3000L else 0L }
      var probeIndex=0; var clockIndex=0
      old.waits=0
      old.clock={ if (clockIndex++ == 0) start else times[(clockIndex-2).coerceAtMost(times.lastIndex)] }
      old.probe={ snapshots[(probeIndex++).coerceAtMost(snapshots.lastIndex)] }
      val expected = old.waitForRedeployHealth(old.resolveRedeploySpec("ticket_screen").copy(healthGateComponents=gates,targetComponents=targets),pre,old.resolveRedeployPolicy(config),disabled)
      var state = NativeRedeployPolicy.begin(start,policy,pre,targets,supported)
      var index=0
      while (true) {
        val actual=NativeRedeployPolicy.step(times[index],policy,state,snapshots[index],gates,disabled)
        state=actual.state
        if (actual.terminal) {
          equal(expected.success,actual.success);equal(expected.gateHealthy,actual.gateHealthy)
          equal(expected.regressedNeighbors,actual.regressedNeighbors);equal(expected.stabilityWindowSatisfied,actual.stabilityWindowSatisfied)
          equal(expected.healthSnapshot,snapshots[index]);equal(old.waits,index);equal(probeIndex,index+1)
          break
        }
        index++;assertTrue("native loop failed to terminate",index<times.size)
      }
      journeys++
    }
    println("REDEPLOY_STATE_PARITY_OK journeys=$journeys comparisons=$comparisons")
  }

  @Test fun completeRoutesRollbackCodesAndIssueOrderMatchFrozenOwner() {
    for (component in listOf("dns","ssh","vpn","ddns","train_bot","satiksme_bot","site_notifier","subscription_bot","ticket_screen","remote","management","cpu_frequency","runtime_cleanup","unknown","","😀")) {
      val a=runCatching {old.resolveRedeploySpec(component)};val b=runCatching {NativeRedeployPolicy.spec(component)}
      equal(a.isSuccess,b.isSuccess);equal(a.exceptionOrNull()?.javaClass,b.exceptionOrNull()?.javaClass);equal(a.exceptionOrNull()?.message,b.exceptionOrNull()?.message)
      if (a.isSuccess) {
        val x=a.getOrThrow();val y=b.getOrThrow()
        equal(listOf(x.requestedComponent,x.runtimeConfigComponent,x.runtimeAssetComponent,x.releaseManifestComponent,x.releaseInstallComponent,x.runtimeAction,x.runtimeActionComponent,x.stopComponent,x.requiresQuiescentInstall,x.quiescenceProbeScript,x.staleCleanupCommand,x.rollbackStrategy.name,x.rollbackComponent,x.retryBudget,x.healthGateComponents,x.targetComponents,x.requiresReleaseManifest),
          listOf(y.requestedComponent,y.runtimeConfigComponent,y.runtimeAssetComponent,y.releaseManifestComponent,y.releaseInstallComponent,y.runtimeAction,y.runtimeActionComponent,y.stopComponent,y.requiresQuiescentInstall,if(y.quiescenceProbeAdapter.isEmpty()) "" else "probe:${y.quiescenceProbeAdapter}",y.staleCleanupCommand,y.rollbackStrategy.name,y.rollbackComponent,y.retryBudget,y.healthGateComponents,y.targetComponents,y.requiresReleaseManifest))
        for (gate in listOf(false,true)) for (neighbors in listOf(emptyList(),listOf("ssh"),listOf("vpn","ssh"),listOf("","é","😀"))) {
          equal(old.buildRedeployMessage(x,gate,neighbors),NativeRedeployPolicy.message(y,gate,neighbors))
        }
      }
    }
    for (gate in listOf(false,true)) for (stable in listOf(false,true)) for (neighbors in listOf(emptyList(),listOf("ssh"),listOf("vpn","ssh"),listOf("","é","😀"))) {
      equal(old.rollbackFailureCode(gate,neighbors,stable),NativeRedeployPolicy.rollbackCode(gate,neighbors,stable))
      equal(old.buildPostDeployIssues(gate,neighbors,stable),NativeRedeployPolicy.issues(gate,neighbors,stable))
    }
    println("REDEPLOY_ROUTE_OUTCOME_PARITY_OK comparisons=$comparisons")
  }
}
