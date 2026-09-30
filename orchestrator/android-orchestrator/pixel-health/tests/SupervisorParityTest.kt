package lv.jolkins.pixelorchestrator.supervisor

import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lv.jolkins.pixelorchestrator.coreconfig.*
import lv.jolkins.pixelorchestrator.health.*
import org.junit.Test

/** Independent pre-migration owner from Git; production has no test clock seam. */
object SupervisorParityClock { var now = 1_790_680_000L }

class SupervisorParityTest {
  private class Controller : ComponentController {
    override val name = "ticket_screen"
    var starts = 0
    var succeeds = true
    var healthy = false
    var stopSucceeds = true
    val calls = mutableListOf<String>()
    override suspend fun start(): Boolean { calls += "start"; starts++; return succeeds }
    override suspend fun stop(): Boolean { calls += "stop"; return stopSucceeds }
    override suspend fun health(): Boolean { calls += "health"; return healthy }
  }
  private val oldController = Controller()
  private val newController = Controller()
  private val checker = RuntimeHealthChecker(CommandRunner { error("pure transitions must not probe") })
  private val store = StackStore(Path.of("unused-config"), Path.of("unused-state"))
  private val old = LegacySupervisorEngine({ StackConfigV1() }, store, checker, mapOf("ticket_screen" to oldController))
  private val new = RustSupervisorEngine({ StackConfigV1() }, store, checker, mapOf("ticket_screen" to newController))
  private fun call(owner: Any, method: String, vararg args: Any): Any? {
    val method = owner.javaClass.declaredMethods.single { it.name == method && it.parameterCount == args.size }.apply { isAccessible = true }
    return try { method.invoke(owner, *args) } catch (e: InvocationTargetException) { throw e.targetException }
  }
  private fun compare(method: String, vararg args: Any) {
    val expected = call(old, method, *args)
    val actual = call(new, method, *args)
    assertEquals(expected, actual, "$method at ${SupervisorParityClock.now}")
    if (expected is StackStateV1 && actual is StackStateV1) {
      val json = Json { encodeDefaults = true }
      assertEquals(json.encodeToString(expected), json.encodeToString(actual), "$method serialized state")
    }
  }
  private val states = listOf(
    StackStateV1(),
    StackStateV1(services=emptyMap(), moduleState=mapOf("retained" to ModuleRuntimeState(details=mapOf("note" to "ā🔒\n")))),
    StackStateV1(schema=7, bootPath=BootPath.PROVIDER_HOOK, lastSuccessfulBootEpochSeconds=17,
      services=ServiceStatus.entries.associate { it.name to ServiceRuntimeState(it,Int.MAX_VALUE,"prior",19,20) },
      operationLog=(0..104).map { OperationEvent(it.toLong(),"old","event",true,"$it") })
  )

  @Test fun stateUpdatesMatchAllFieldsAcrossMissingServicesOverflowAndLogRetention() {
    for (now in listOf(0L,1L,1_790_680_000L,Long.MAX_VALUE,Long.MIN_VALUE)) {
      SupervisorParityClock.now=now
      for (state in states) {
        for (status in ServiceStatus.entries) for (count in listOf(false,true)) for (name in listOf("missing",status.name,"supervisor")) {
          compare("markComponent",state,name,status,"ā🔒\n",count)
        }
        for (status in ServiceStatus.entries) compare("withSupervisorStatus",state,status)
        compare("appendEvent",state,"new","result",false,"ā🔒\n")
        compare("withSupervisorLoopHeartbeat",state)
        compare("withModuleHealth",state,HealthSnapshot(moduleHealth=mapOf("extra" to ModuleHealthState(true,"ready",mapOf("x" to "ā🔒")))))
      }
    }
  }

  @Test fun managementObservationMatchesEveryPriorStatusAndEvidenceVariant() {
    SupervisorParityClock.now=1_790_680_000
    for (status in ServiceStatus.entries) for (healthy in listOf(false,true)) for (enabled in listOf("","true","false")) for (reason in listOf(""," \u00a0","normal","none","ā🔒")) {
      val state=StackStateV1(services=mapOf("management" to ServiceRuntimeState(status)))
      val snapshot=HealthSnapshot(managementHealthy=healthy,evidence=mapOf("management_enabled" to enabled,"management_reason" to reason))
      compare("observeManagementHealth",state,snapshot)
    }
  }

  private fun property(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(value)

  @Test fun networkTransitionsKeepPreviousSnapshotSemanticsAndConvergenceBoundaries() {
    val text=listOf(""," "," NoNe ","none","\u00a0x\u00a0","wifi","mobile")
    for (now in listOf(0L,1_790_680_000L,Long.MAX_VALUE)) for (prior in text) for (current in text) for (failed in listOf(false,true)) for (transition in listOf(false,true)) for (window in listOf(-1,0,30)) {
      SupervisorParityClock.now=now
      val previous=StackStateV1(lastNetworkFingerprint=prior,lastObservedPublicIpv4=prior,networkConvergenceUntilEpochSeconds=now,lastDirectPublicFailureEpochSeconds=if(failed)0 else 7,
        lastHealthSnapshot=HealthSnapshot(evidence=mapOf("direct_public_transitioning" to (!transition).toString())))
      val snapshot=HealthSnapshot(evidence=mapOf("network_fingerprint" to current,"network_public_ipv4_candidate" to current,"network_active_transport" to "\u00a0","ddns_published_ipv4" to "192.0.2.10","direct_public_path_healthy" to (!failed).toString(),"direct_public_transitioning" to transition.toString(),"direct_public_transition_reason" to "test"))
      val state=previous.copy(lastHealthSnapshot=snapshot)
      val config=StackConfigV1().let { it.copy(supervision=it.supervision.copy(networkConvergenceWindowSeconds=window)) }
      val a=call(old,"observeNetworkState",previous,state,snapshot,config)!!
      val b=call(new,"observeNetworkState",previous,state,snapshot,config)!!
      for(field in listOf("state","fingerprintChanged","publicIpv4Changed","convergenceActive")) assertEquals(property(a,field),property(b,field),field)
      assertEquals(Json.encodeToString(property(a,"state") as StackStateV1), Json.encodeToString(property(b,"state") as StackStateV1))
    }
  }

  private suspend fun suspendCall(owner: Any, name: String, vararg args: Any): Any? = suspendCoroutine { continuation ->
    val method=owner.javaClass.declaredMethods.single { it.name==name && it.parameterCount==args.size+1 }.apply { isAccessible=true }
    try {
      val result=method.invoke(owner,*args,continuation)
      if(result !== COROUTINE_SUSPENDED) continuation.resume(result)
    } catch(e: InvocationTargetException) { continuation.resumeWithException(e.targetException) }
  }

  @Test fun activeControllerRecheckStopStartAndRecordedOutcomesMatch() = runBlocking {
    SupervisorParityClock.now=1_790_680_000
    for (state in states) for (observed in listOf(false,true)) for (rechecked in listOf(false,true)) for (startOk in listOf(false,true)) for (stopOk in listOf(false,true)) {
      val a=Controller().apply { healthy=rechecked; succeeds=startOk; stopSucceeds=stopOk }
      val b=Controller().apply { healthy=rechecked; succeeds=startOk; stopSucceeds=stopOk }
      val old=LegacySupervisorEngine({ StackConfigV1() },store,checker,mapOf(a.name to a))
      val new=RustSupervisorEngine({ StackConfigV1() },store,checker,mapOf(b.name to b))
      call(old,"ensureBackoffPolicy",a.name,StackConfigV1())
      call(new,"ensureBackoffPolicy",b.name,StackConfigV1())
      val expected=suspendCall(old,"restartIfUnhealthy",a.name,observed,state)!!
      val actual=suspendCall(new,"restartIfUnhealthy",b.name,observed,state)!!
      assertEquals(property(expected,"state"),property(actual,"state"))
      assertEquals(Json.encodeToString(property(expected,"state") as StackStateV1),Json.encodeToString(property(actual,"state") as StackStateV1))
      assertEquals(property(expected,"delayMillis"),property(actual,"delayMillis"))
      assertEquals(a.calls,b.calls)
    }
  }

  /** Same persisted policy journey in each fresh/warm benchmark process. */
  fun measuredCycle(engine: String, state: StackStateV1): StackStateV1 = runBlocking {
    val owner=if(engine=="Legacy") old else new
    val config=StackConfigV1().let { it.copy(ddns=it.ddns.copy(enabled=false)) }
    val snapshot=HealthSnapshot(remoteHealthy=true,managementHealthy=true,ddnsHealthy=true,
      moduleHealth=mapOf("ticket_screen" to ModuleHealthState(true,"running")),
      evidence=mapOf("network_fingerprint" to "wifi","network_public_ipv4_candidate" to "192.0.2.10","management_enabled" to "true"))
    var next=(call(owner,"withModuleHealth",state,snapshot) as StackStateV1).copy(lastHealthSnapshot=snapshot)
    val network=call(owner,"observeNetworkState",state,next,snapshot,config)!!
    next=property(network,"state") as StackStateV1
    next=call(owner,"withSupervisorStatus",next,ServiceStatus.RUNNING) as StackStateV1
    next=property(suspendCall(owner,"restartIfUnhealthy","ticket_screen",true,next)!!,"state") as StackStateV1
    next=call(owner,"observeRemoteHealth",next,snapshot) as StackStateV1
    next=call(owner,"observeManagementHealth",next,snapshot) as StackStateV1
    call(owner,"withSupervisorLoopHeartbeat",next) as StackStateV1
  }
}

object SupervisorMeasurements {
  @JvmStatic fun main(args: Array<String>) {
    val fixture=SupervisorParityTest()
    val dir=java.nio.file.Files.createTempDirectory("supervisor-measure")
    val store=StackStore(dir.resolve("config"),dir.resolve("state"))
    val cpu=java.lang.management.ManagementFactory.getThreadMXBean()
    val original=StackStateV1(operationLog=(0..99).map { OperationEvent(it.toLong(),"service","check",true,"sanitized") })
    fun run(engine: String): Triple<StackStateV1,Double,Double> {
      val startCpu=cpu.currentThreadCpuTime; val start=System.nanoTime()
      val state=fixture.measuredCycle(engine,original)
      store.saveState(state)
      val reloaded=store.loadStateOrDefault()
      val wall=(System.nanoTime()-start)/1e6; val used=(cpu.currentThreadCpuTime-startCpu)/1e6
      check(state==reloaded)
      return Triple(state,wall,used)
    }
    try {
      if(args.single()!="Warm") {
        val (_,wall,used)=run(args.single())
        println("SUPERVISOR_COLD engine=${args.single()} wall_ms=$wall cpu_ms=$used")
      } else {
        val samples=mapOf("Legacy" to mutableListOf<Pair<Double,Double>>(),"Rust" to mutableListOf())
        repeat(30) { check(run("Legacy").first==run("Rust").first) }
        repeat(500) { index ->
          val order=if(index%2==0) listOf("Legacy","Rust") else listOf("Rust","Legacy")
          val first=run(order[0]);val second=run(order[1]);check(first.first==second.first)
          samples.getValue(order[0]).add(first.second to first.third)
          samples.getValue(order[1]).add(second.second to second.third)
        }
        for((engine,values) in samples) {
          val wall=values.map{it.first}.sorted();val used=values.map{it.second}.sorted()
          println("SUPERVISOR_WARM engine=$engine successes=${values.size} p50_ms=${wall[250]} p95_ms=${wall[474]} cpu_p50_ms=${used[250]}")
        }
      }
    } finally { dir.toFile().deleteRecursively() }
  }
}
