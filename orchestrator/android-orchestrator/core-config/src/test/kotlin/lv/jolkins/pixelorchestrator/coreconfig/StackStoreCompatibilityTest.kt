package lv.jolkins.pixelorchestrator.coreconfig

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StackStoreCompatibilityTest {

  @Test
  fun loadConfigOrDefaultMapsLegacyRemoteKeys() {
    val tempDir = Files.createTempDirectory("stack-store-compat-load")
    val configPath = tempDir.resolve("orchestrator-config-v1.json")
    val statePath = tempDir.resolve("orchestrator-state-v1.json")

    val legacyJson = """
      {
        "schema": 1,
        "remote": {
          "dohSecretToken": "legacy-token-123456",
          "adminBasicAuthUser": "legacy-admin",
          "adminBasicAuthPasswordFile": "/tmp/legacy-admin-password"
        }
      }
    """.trimIndent()
    Files.writeString(configPath, legacyJson, StandardCharsets.UTF_8)

    val store = StackStore(configPath = configPath, statePath = statePath)
    val loaded = store.loadConfigOrDefault()

    assertEquals("legacy-token-123456", loaded.remote.dohPathToken)
    assertEquals("native", loaded.remote.dohEndpointMode)
    assertEquals(false, loaded.remote.routerPublicIpAttributionEnabled)
    assertEquals("", loaded.remote.routerLanIp)
    assertEquals("legacy-admin", loaded.remote.adminUsername)
    assertEquals("/tmp/legacy-admin-password", loaded.remote.adminPasswordFile)
    assertEquals("/data/local/pixel-stack/conf/adguardhome/ipinfo-lite-token", loaded.remote.ipinfoLiteTokenFile)
  }

  @Test
  fun saveConfigWritesOnlyNewRemoteKeys() {
    val tempDir = Files.createTempDirectory("stack-store-compat-save")
    val configPath = tempDir.resolve("orchestrator-config-v1.json")
    val statePath = tempDir.resolve("orchestrator-state-v1.json")
    val store = StackStore(configPath = configPath, statePath = statePath)

    val config = StackConfigV1(
      remote = StackConfigV1().remote.copy(
        dohEndpointMode = "tokenized",
        dohPathToken = "new-token-123456",
        routerPublicIpAttributionEnabled = true,
        routerLanIp = "192.168.31.1",
        adminUsername = "new-admin",
        adminPasswordFile = "/tmp/new-admin-password",
        ipinfoLiteTokenFile = "/tmp/ipinfo-lite-token"
      )
    )
    store.saveConfig(config)

    val saved = Files.readString(configPath, StandardCharsets.UTF_8)
    assertTrue(saved.contains("\"dohEndpointMode\""))
    assertTrue(saved.contains("\"dohPathToken\""))
    assertTrue(saved.contains("\"routerPublicIpAttributionEnabled\""))
    assertTrue(saved.contains("\"routerLanIp\""))
    assertTrue(saved.contains("\"adminUsername\""))
    assertTrue(saved.contains("\"adminPasswordFile\""))
    assertTrue(saved.contains("\"ipinfoLiteTokenFile\""))
    assertFalse(saved.contains("\"dohSecretToken\""))
    assertFalse(saved.contains("\"adminBasicAuthUser\""))
    assertFalse(saved.contains("\"adminBasicAuthPasswordFile\""))
    assertFalse(saved.contains("\"adminBasicAuthHash\""))
    assertFalse(saved.contains("\"adminBasicAuthEnabled\""))
  }
}
