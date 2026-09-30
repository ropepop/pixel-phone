package lv.jolkins.pixelorchestrator.health

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import java.io.File
import lv.jolkins.pixelorchestrator.coreconfig.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RustParityTest {
  private val json = Json { encodeDefaults = true }
  private val now = 1700000000L
  private val fields = linkedMapOf(
    "ID_U" to "0",
    "LISTENERS" to "LISTEN 0 128 0.0.0.0:53\nLISTEN 0 128 0.0.0.0:2222\nLISTEN 0 128 0.0.0.0:443\nLISTEN 0 128 0.0.0.0:853\nLISTEN 0 128 0.0.0.0:9388",
    "DDNS_EPOCH" to "1700000000",
    "DDNS_LAST_IPV4" to "192.0.2.1",
    "SUPERVISOR_LOOP_HEARTBEAT" to "1700000000",
    "TRAIN_BOT_PID" to "123",
    "TRAIN_BOT_TUNNEL_ENABLED" to "1",
    "TRAIN_BOT_TUNNEL_SUPERVISOR_PID" to "123",
    "TRAIN_BOT_TUNNEL_PID" to "123",
    "TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL" to "https://example.test",
    "TRAIN_BOT_PUBLIC_ROOT_CODE" to "200",
    "TRAIN_BOT_PUBLIC_APP_CODE" to "200",
    "TRAIN_BOT_TUNNEL_PROBE_AVAILABLE" to "1",
    "TRAIN_BOT_HEARTBEAT" to "1700000000",
    "TRAIN_BOT_SCHEDULE_REQUIRED" to "1",
    "TRAIN_BOT_SCHEDULE_FRESH" to "1",
    "TRAIN_BOT_SCHEDULE_SERVICE_DATE" to "1",
    "TRAIN_BOT_SCHEDULE_ROWS" to "100",
    "SATIKSME_BOT_PID" to "123",
    "SATIKSME_BOT_TUNNEL_ENABLED" to "1",
    "SATIKSME_BOT_TUNNEL_SUPERVISOR_PID" to "123",
    "SATIKSME_BOT_TUNNEL_PID" to "123",
    "SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL" to "https://example.test",
    "SATIKSME_BOT_PUBLIC_ROOT_CODE" to "200",
    "SATIKSME_BOT_PUBLIC_APP_CODE" to "200",
    "SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE" to "1",
    "SATIKSME_BOT_HEARTBEAT" to "1700000000",
    "SITE_NOTIFIER_PID" to "123",
    "SITE_NOTIFIER_HEARTBEAT" to "1700000000",
    "SITE_NOTIFIER_HELPER_HEALTHY" to "1",
    "SITE_NOTIFIER_HELPER_REASON" to "ok",
    "SUBSCRIPTION_BOT_PID" to "123",
    "SUBSCRIPTION_BOT_HEARTBEAT" to "1700000000",
    "VPN_HEALTH" to "1",
    "VPN_ENABLED_EFFECTIVE" to "1",
    "VPN_TAILSCALED_LIVE" to "1",
    "VPN_TAILSCALED_SOCK" to "1",
    "VPN_TAILNET_IPV4" to "192.0.2.1",
    "VPN_GUARD_CHAIN_IPV4" to "192.0.2.1",
    "VPN_GUARD_CHAIN_IPV6" to "1",
    "MANAGEMENT_ENABLED" to "1",
    "MANAGEMENT_HEALTHY" to "1",
    "MANAGEMENT_REASON" to "ok",
    "MANAGEMENT_AUTH_CONSISTENT" to "1",
    "MANAGEMENT_AUTH_WARNING_REASON" to "ok",
    "MANAGEMENT_SSH_LISTENER" to "1",
    "MANAGEMENT_SSH_AUTH_MODE" to "key_password",
    "MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED" to "1",
    "MANAGEMENT_SSH_PASSWORD_AUTH_READY" to "1",
    "MANAGEMENT_SSH_KEY_AUTH_REQUESTED" to "1",
    "MANAGEMENT_SSH_KEY_AUTH_READY" to "1",
    "MANAGEMENT_PM_PATH" to "1",
    "MANAGEMENT_AM_PATH" to "1",
    "MANAGEMENT_LOGCAT_PATH" to "1",
    "MANAGEMENT_WIRELESS_DEBUG_ENABLED" to "1",
    "MANAGEMENT_WIRELESS_DEBUG_TLS_PORT" to "1",
    "MANAGEMENT_WIRELESS_DEBUG_LIVE" to "1",
    "MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS" to "1",
    "MANAGEMENT_WIRELESS_DEBUG_HEALTHY" to "1",
    "MANAGEMENT_WIRELESS_DEBUG_REASON" to "ok",
    "MANAGEMENT_WIFI_ENABLED" to "1",
    "MANAGEMENT_WIFI_CONNECTED" to "1",
    "MANAGEMENT_WIFI_IPV4" to "192.0.2.1",
    "MANAGEMENT_MOBILE_IFACE" to "1",
    "MANAGEMENT_MOBILE_IPV4" to "192.0.2.1",
    "MANAGEMENT_ACTIVE_TRANSPORT" to "wifi",
    "MANAGEMENT_PUBLIC_IPV4_CANDIDATE" to "1",
    "MANAGEMENT_NETWORK_FINGERPRINT" to "1",
    "REMOTE_DOH_TOKENIZED_CODE" to "200",
    "REMOTE_DOH_BARE_CODE" to "200",
    "REMOTE_IDENTITY_INJECT_CODE" to "200",
    "REMOTE_PUBLIC_BASE_URL" to "https://example.test",
    "REMOTE_PUBLIC_ROOT_CODE" to "200",
    "REMOTE_PUBLIC_PROBE_AVAILABLE" to "1",
    "REMOTE_PUBLIC_DOH_TOKENIZED_CODE" to "200",
    "REMOTE_PUBLIC_DOH_BARE_CODE" to "200",
    "REMOTE_PUBLIC_IDENTITY_INJECT_CODE" to "200",
  )
  private val moduleIds = listOf("dns", "ssh", "vpn", "ddns", "train_bot", "satiksme_bot", "site_notifier", "subscription_bot", "remote", "ticket_screen")
  private val enabledConfig = StackConfigV1(
    vpn = VpnConfig(enabled = true),
    remote = RemoteConfig(dohEnabled = true, dotEnabled = true, dohEndpointMode = "tokenized", dohPathToken = "synthetic-secret", watchdogEscalateRuntimeRestart = true)
  )
  private fun report(values: Map<String, String> = fields, lineEnding: String = "\n"): String =
    (values.entries.flatMap { (key, value) -> listOf("__PIXEL_HEALTH_${key}__", value) } + "__PIXEL_HEALTH_DONE__").joinToString(lineEnding)
  private fun compare(output: String, config: StackConfigV1 = enabledConfig, ok: Boolean = true) {
    val legacy = runBlocking {
      LegacyRuntimeHealthChecker(CommandRunner { CommandResult(ok, output, "") }).check(config)
    }
    val expected = json.parseToJsonElement(json.encodeToString(legacy))
    val actual = json.parseToJsonElement(NativeHealth.interpretProbe(json.encodeToString(config), ok, output, now))
    assertEquals(expected, actual, "Full snapshot mismatch")
    assertFalse(actual.toString().contains("synthetic-secret"))
  }

  @Test fun commandAndAllFieldsPreserveConfiguredContracts() {
    val configs = listOf(
      StackConfigV1(), enabledConfig,
      enabledConfig.copy(modules = moduleIds.associateWith { ModuleConfig(enabled = false) }),
      enabledConfig.copy(remote = enabledConfig.remote.copy(dohEndpointMode = "DUAL", hostname = "", httpsPort = 8443)),
      enabledConfig.copy(remote = enabledConfig.remote.copy(dohEndpointMode = "  ", hostname = "example.test", httpsPort = 443)),
      enabledConfig.copy(runtime = RuntimeConfig(rootfsPath = "/tmp/a'b c"), trainBot = TrainBotConfig(runtimeRoot = "/tmp/a'b", envFile = "/tmp/a'b.env")),
      enabledConfig.copy(supervision = SupervisionConfig(managementRequireAuthConsistency = true, enforceRemoteListeners = false, backoffMaxSeconds = 180)),
      enabledConfig.copy(remote = enabledConfig.remote.copy(dohPathToken = "\u001ctoken\u001f", hostname = "\u0085")),
      enabledConfig.copy(runtime = RuntimeConfig(rootfsPath = "/tmp/a\r\n\u2000\u2000b"))
    )
    for (config in configs) {
      val legacy = LegacyRuntimeHealthChecker(CommandRunner { error("no root command should run") })
      val method = LegacyRuntimeHealthChecker::class.java.getDeclaredMethod("buildProbeCommand", StackConfigV1::class.java)
      method.isAccessible = true
      assertEquals(method.invoke(legacy, config), NativeHealth.buildProbe(json.encodeToString(config)), "Atomic probe command changed")
      compare(report(), config)
      compare("", config)
      compare(report(), config, ok = false)
    }
  }

  @Test fun scalarAndIncompleteReportsKeepFailureAndDefaultSemantics() {
    val values = listOf("", " ", "0", "1", "200", "404", "000", "bad", "-1", "1700000001", "1699999800", "2147483648", "9223372036854775808", "  1  ", "\n \n200\n404", "١", "１２３", "\u001c1\u001f", "\u00851\u0085")
    for (field in fields.keys) {
      for (value in values) compare(report(fields + (field to value)))
      compare(report(fields.filterKeys { it != field }))
    }
    compare(report().replace("__PIXEL_HEALTH_DONE__", ""))
    compare(report().replace("__PIXEL_HEALTH_DONE__", "__PIXEL_HEALTH_ID_U__\n0\n__PIXEL_HEALTH_DONE__"))
    compare(report().replace("__PIXEL_HEALTH_DONE__", "__UNKNOWN_MARKER__\nignored\n__PIXEL_HEALTH_DONE__"))
    for (ending in listOf("\n", "\r\n", "\r")) compare(report(lineEnding = ending))
    compare(report(fields.toList().reversed().toMap()))
    compare(report() + "\ntrailing noise")
  }

  @Test fun moduleAndRemoteModesRetainPublicVsLocalPolicy() {
    for (mode in listOf("native", "dual", "tokenized", "unknown")) {
      for (local in listOf("000", "200", "400", "404", "500")) {
        for (public in listOf("000", "200", "404", "500")) {
          val config = enabledConfig.copy(remote = enabledConfig.remote.copy(dohEndpointMode = mode))
          compare(report(fields + mapOf("REMOTE_DOH_TOKENIZED_CODE" to local, "REMOTE_DOH_BARE_CODE" to local, "REMOTE_PUBLIC_DOH_BARE_CODE" to public, "REMOTE_PUBLIC_DOH_TOKENIZED_CODE" to public)), config)
        }
      }
    }
    for (module in moduleIds) {
      val config = enabledConfig.copy(modules = mapOf(module to ModuleConfig(enabled = false)))
      compare(report(), config)
      compare(report(fields.filterKeys { !it.startsWith(module.uppercase() + "_") }), config)
    }
  }

  @Test fun invalidConfigurationCannotBecomeHealthy() {
    for (invalid in listOf("", "{", "null", "{}", """{"modules":{}}""")) {
      assertFailsWith<IllegalStateException> { NativeHealth.buildProbe(invalid) }
      assertFailsWith<IllegalStateException> { NativeHealth.interpretProbe(invalid, true, report(), now) }
    }
  }

  @Test fun capturedDeviceReportMatchesOriginalAndInstalledChecker() {
    val path = System.getProperty("pixel.health.parity.sample")
    assumeTrue("Actual-device parity needs a captured read-only root sample", path != null)
    val captured = json.parseToJsonElement(File(path!!).readText()).jsonObject
    val configText = captured.getValue("config").toString()
    val config = json.decodeFromString<StackConfigV1>(configText)
    val ok = captured.getValue("ok").jsonPrimitive.boolean
    val stdout = captured.getValue("stdout").jsonPrimitive.content
    val epoch = captured.getValue("nowEpoch").jsonPrimitive.long
    val expected = runBlocking {
      LegacyRuntimeHealthChecker(CommandRunner { CommandResult(ok, stdout, "") }, epoch).check(config)
    }
    val original = json.parseToJsonElement(json.encodeToString(expected))
    assertEquals(original, captured.getValue("snapshot"), "Installed checker changed current health semantics")
    assertEquals(original, json.parseToJsonElement(NativeHealth.interpretProbe(configText, ok, stdout, epoch)))
  }
}
