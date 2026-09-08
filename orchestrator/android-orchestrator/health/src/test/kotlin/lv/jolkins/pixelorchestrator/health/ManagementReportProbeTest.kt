package lv.jolkins.pixelorchestrator.health

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lv.jolkins.pixelorchestrator.coreconfig.ModuleConfig
import lv.jolkins.pixelorchestrator.coreconfig.StackConfigV1
import org.junit.Test

class ManagementReportProbeTest {
  @Test
  fun parsesReportOncePreservingFirstValueAndLiteralContent() {
    val fields = filterReport(
      "vpn_health=1\nmanagement_enabled=1\nmanagement_healthy=1\n" +
        "management_reason=first\nmanagement_reason=second\n" +
        "network_fingerprint=wifi=example; literal ${'$'}HOME\nunknown=ignored\nmalformed\n",
      enabled = true,
      exitCode = 0
    )
    assertEquals("1", fields["VPN_HEALTH"])
    assertEquals("first", fields["MANAGEMENT_REASON"])
    assertEquals("wifi=example; literal ${'$'}HOME", fields["MANAGEMENT_NETWORK_FINGERPRINT"])
    assertEquals(35, fields.size)
  }

  @Test
  fun missingFailedReportPreservesFailClosedEnabledDefaults() {
    val fields = filterReport("", enabled = true, exitCode = 1)
    assertEquals("0", fields["VPN_HEALTH"])
    assertEquals("1", fields["MANAGEMENT_ENABLED"])
    assertEquals("0", fields["MANAGEMENT_HEALTHY"])
    assertEquals("unknown", fields["MANAGEMENT_REASON"])
  }

  @Test
  fun disabledAndExplicitStatusKeepTheirExistingMeaning() {
    val disabled = filterReport("", enabled = false, exitCode = 1)
    assertEquals("1", disabled["VPN_HEALTH"])
    assertEquals("0", disabled["MANAGEMENT_ENABLED"])
    assertEquals("1", disabled["MANAGEMENT_HEALTHY"])
    assertEquals("disabled", disabled["MANAGEMENT_REASON"])
    val explicit = filterReport("management_enabled=0\nmanagement_healthy=0\n", enabled = true, exitCode = 0)
    assertEquals("0", explicit["MANAGEMENT_HEALTHY"])
    assertEquals("disabled", explicit["MANAGEMENT_REASON"])
  }

  @Test
  fun disabledWorkloadsDoNotProduceProcessDatabaseOrRemoteProbes() {
    val config = StackConfigV1().let { defaults ->
      defaults.copy(modules = listOf("ddns", "train_bot", "satiksme_bot", "site_notifier", "subscription_bot", "remote")
        .associateWith { ModuleConfig(enabled = false) })
    }
    val script = buildProbe(config)
    for (unneeded in listOf("train_pid=", "satiksme_pid=", "notifier_pid=", "subscription_pid=", "train_schedule_rows=", "remote_doh_tokenized_code=", "ddns-last-sync-epoch")) {
      assertFalse(script.contains(unneeded), unneeded)
    }
    assertTrue(script.contains("pixel-management-health.sh --report"))
    assertTrue(script.contains("ss -ltn"))
    val process = ProcessBuilder("/bin/sh", "-n", "-c", script).start()
    assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
  }

  private fun filterReport(report: String, enabled: Boolean, exitCode: Int): Map<String, String> {
    val defaults = StackConfigV1()
    val script = buildProbe(defaults.copy(vpn = defaults.vpn.copy(enabled = enabled)))
    val start = script.indexOf("printf '%s\\n' \"${'$'}management_report\" | awk")
    assertTrue(start >= 0)
    val end = Regex("(?m)^\\s*'$").find(script, start)?.range?.last ?: error("missing awk end")
    val process = ProcessBuilder("/bin/sh", "-c", script.substring(start, end + 1)).apply {
      environment()["management_report"] = report
      environment()["management_health_rc"] = exitCode.toString()
    }.start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), process.errorStream.bufferedReader().readText())
    return output.lines().dropLastWhile { it.isEmpty() }.chunked(2).associate { pair ->
      pair[0].removePrefix("__PIXEL_HEALTH_").removeSuffix("__") to pair.getOrElse(1) { "" }
    }
  }

  private fun buildProbe(config: StackConfigV1): String {
    val checker = RuntimeHealthChecker(CommandRunner { CommandResult(ok = true, stdout = "", stderr = "") })
    return RuntimeHealthChecker::class.java.getDeclaredMethod("buildProbeCommand", StackConfigV1::class.java).apply {
      isAccessible = true
    }.invoke(checker, config) as String
  }
}
