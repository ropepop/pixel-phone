package lv.jolkins.pixelorchestrator.coreconfig

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

class StackConfigV1SerializationTest {

  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  @Test
  fun roundTripsVpnAndSshAuthMode() {
    val input = StackConfigV1(
      ssh = SshConfig(authMode = "password_only"),
      vpn = VpnConfig(
        enabled = true,
        runtimeRoot = "/data/local/pixel-stack/vpn",
        authKeyFile = "/data/local/pixel-stack/conf/vpn/tailscale-authkey",
        interfaceName = "tailscale0",
        hostname = "pixel-node",
        advertiseTags = "tag:pixel",
        acceptRoutes = false,
        acceptDns = false,
        nativeWirelessDebugEnabled = true
      ),
      ddns = DdnsConfig(
        enabled = true,
        syncOnNetworkChange = true,
        movePolicy = "stick_new_ip"
      ),
      supervision = SupervisionConfig(
        healthPollSeconds = 15,
        networkConvergenceWindowSeconds = 180,
        networkConvergencePollSeconds = 5,
        managementRequireWirelessDebug = false
      ),
      redeploy = RedeployConfig(
        healthWaitSeconds = 240,
        healthRetrySeconds = 5,
        neighborGraceSeconds = 20
      )
    )

    val encoded = json.encodeToString(StackConfigV1.serializer(), input)
    val decoded = json.decodeFromString(StackConfigV1.serializer(), encoded)

    assertEquals("password_only", decoded.ssh.authMode)
    assertTrue(decoded.vpn.enabled)
    assertEquals("tailscale0", decoded.vpn.interfaceName)
    assertEquals("tag:pixel", decoded.vpn.advertiseTags)
    assertTrue(decoded.vpn.nativeWirelessDebugEnabled)
    assertTrue(decoded.ddns.syncOnNetworkChange)
    assertEquals("stick_new_ip", decoded.ddns.movePolicy)
    assertEquals(180, decoded.supervision.networkConvergenceWindowSeconds)
    assertEquals(5, decoded.supervision.networkConvergencePollSeconds)
    assertEquals(false, decoded.supervision.managementRequireWirelessDebug)
    assertEquals(240, decoded.redeploy.healthWaitSeconds)
    assertEquals(5, decoded.redeploy.healthRetrySeconds)
    assertEquals(20, decoded.redeploy.neighborGraceSeconds)
  }

  @Test
  fun defaultsIncludeRedeployPolicy() {
    val encoded = json.encodeToString(StackConfigV1.serializer(), StackConfigV1())
    val decoded = json.decodeFromString(StackConfigV1.serializer(), encoded)

    assertEquals(180, decoded.redeploy.healthWaitSeconds)
    assertEquals(3, decoded.redeploy.healthRetrySeconds)
    assertEquals(12, decoded.redeploy.neighborGraceSeconds)
    assertEquals("/data/local/pixel-stack/apps/subscription-bot", decoded.subscriptionBot.runtimeRoot)
    assertEquals("/data/local/pixel-stack/conf/apps/subscription-bot.env", decoded.subscriptionBot.envFile)
    assertEquals("/data/local/pixel-stack/apps/subscription-bot/bin/subscription-bot.current", decoded.subscriptionBot.binaryPath)
    assertEquals("https://farel-subscription-bot.jolkins.id.lv/pixel-stack/subscription", decoded.subscriptionBot.publicBaseUrl)
    assertEquals("cloudflare_tunnel", decoded.subscriptionBot.ingressMode)
    assertEquals("subscription-bot", decoded.subscriptionBot.tunnelName)
    assertEquals("/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json", decoded.subscriptionBot.tunnelCredentialsFile)
    assertEquals(true, decoded.subscriptionBot.webShellEnabled)
    assertEquals(false, decoded.subscriptionBot.e2eMode)
    assertEquals(false, decoded.vpn.nativeWirelessDebugEnabled)
    assertEquals(true, decoded.ddns.syncOnNetworkChange)
    assertEquals("stick_new_ip", decoded.ddns.movePolicy)
    assertEquals(180, decoded.supervision.networkConvergenceWindowSeconds)
    assertEquals(5, decoded.supervision.networkConvergencePollSeconds)
    assertEquals(false, decoded.supervision.managementRequireWirelessDebug)
  }
}
