package lv.jolkins.pixelorchestrator.health

import java.lang.management.ManagementFactory
import kotlinx.coroutines.runBlocking
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1

// Optional local measurement, not a test or a phone probe.
object HealthParityMeasurements {
  @JvmStatic fun main(args: Array<String>) {
    val fixture = RustParityTest()
    val type = RustParityTest::class.java
    val config = type.getDeclaredField("enabledConfig").apply { isAccessible = true }.get(fixture) as StackConfigV1
    val fields = type.getDeclaredField("fields").apply { isAccessible = true }.get(fixture)
    val epoch = System.currentTimeMillis() / 1000
    val stdout = (type.getDeclaredMethod("report", Map::class.java, String::class.java).apply { isAccessible = true }.invoke(fixture, fields, "\n") as String).replace("1700000000", epoch.toString())
    val runner = CommandRunner { CommandResult(true, stdout, "") }
    val legacy = LegacyRuntimeHealthChecker(runner, epoch)
    val native = RuntimeHealthChecker(runner)
    val bean = ManagementFactory.getThreadMXBean()
    if (bean.isCurrentThreadCpuTimeSupported && !bean.isThreadCpuTimeEnabled) bean.isThreadCpuTimeEnabled = true
    fun sample(action: suspend () -> Unit): Pair<Long, Long> {
      val cpu = bean.currentThreadCpuTime
      val start = System.nanoTime()
      runBlocking { action() }
      return (System.nanoTime() - start) to (bean.currentThreadCpuTime - cpu)
    }
    if (args.isNotEmpty()) {
      val cold = sample { if (args[0] == "Kotlin") legacy.check(config) else native.check(config) }
      println("${args[0]} fresh_process_first_check_ms=${cold.first / 1e6} cpu_ms=${cold.second / 1e6}")
      return
    }
    val oldCold = sample { legacy.check(config) }
    val newCold = sample { native.check(config) }
    repeat(50) { legacyRun(legacy, native, config) }
    val oldSamples = mutableListOf<Pair<Long, Long>>()
    val newSamples = mutableListOf<Pair<Long, Long>>()
    repeat(500) {
      oldSamples += sample { legacy.check(config) }
      newSamples += sample { native.check(config) }
    }
    fun summary(name: String, cold: Pair<Long, Long>, values: List<Pair<Long, Long>>) {
      val walls = values.map { it.first }.sorted()
      val cpu = values.map { it.second }.sorted()
      println("$name cold_ms=${cold.first / 1e6} warm_p50_ms=${walls[250] / 1e6} warm_p95_ms=${walls[475] / 1e6} warm_cpu_p50_ms=${cpu[250] / 1e6} samples=500")
    }
    println("Local complete checker comparison: synthetic root response, actual root-command cost excluded.")
    summary("Kotlin baseline", oldCold, oldSamples)
    summary("Rust JNI", newCold, newSamples)
  }

  private fun legacyRun(old: LegacyRuntimeHealthChecker, current: RuntimeHealthChecker, config: StackConfigV1) = runBlocking {
    old.check(config)
    current.check(config)
  }
}
