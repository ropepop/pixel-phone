package lv.jolkins.pixelorchestrator.coreconfig

import kotlinx.serialization.Serializable

@Serializable
data class StackConfigV1(
  val schema: Int = 1,
  val runtime: RuntimeConfig = RuntimeConfig(),
  val dns: DnsConfig = DnsConfig(),
  val remote: RemoteConfig = RemoteConfig(),
  val ssh: SshConfig = SshConfig(),
  val vpn: VpnConfig = VpnConfig(),
  val trainBot: TrainBotConfig = TrainBotConfig(),
  val satiksmeBot: SatiksmeBotConfig = SatiksmeBotConfig(),
  val siteNotifier: SiteNotifierConfig = SiteNotifierConfig(),
  val subscriptionBot: SubscriptionBotConfig = SubscriptionBotConfig(),
  val ddns: DdnsConfig = DdnsConfig(),
  val redeploy: RedeployConfig = RedeployConfig(),
  val supervision: SupervisionConfig = SupervisionConfig(),
  val features: FeatureFlags = FeatureFlags(),
  val modules: Map<String, ModuleConfig> = emptyMap(),
  val observability: ObservabilityConfig = ObservabilityConfig()
)

@Serializable
data class RuntimeConfig(
  val rootfsPath: String = "/data/local/pixel-stack/chroots/adguardhome",
  val baseDir: String = "/data/local/pixel-stack",
  val controlMode: String = "local-su",
  val bootMode: String = "android-dual-path"
)

@Serializable
data class DnsConfig(
  val dnsPort: Int = 53,
  val webPort: Int = 8080,
  val precutoverPort: Int = 5353,
  val dohBackend: String = "dnscrypt-proxy",
  val dohPort: Int = 5053,
  val dohUpstream1: String = "https://1.1.1.1/dns-query",
  val dohUpstream2: String = "https://1.0.0.1/dns-query",
  val testDomain: String = "example.com"
)

@Serializable
data class RemoteConfig(
  val dohEnabled: Boolean = false,
  val dotEnabled: Boolean = false,
  // When enabled, managed identities get a random DoT hostname under *.dotHostname.
  val dotIdentityEnabled: Boolean = false,
  // The random per-identity DoT hostname label length. Effective max is 63.
  val dotIdentityLabelLength: Int = 20,
  // Values: native | tokenized | dual
  val dohEndpointMode: String = "native",
  val hostname: String = "dns.example.com",
  val dotHostname: String = "dns.example.com",
  val httpsPort: Int = 2789,
  val dotPort: Int = 2790,
  val dotMaxConnPerIp: Int = 20,
  val dotProxyTimeoutSeconds: Int = 15,
  // Deprecated no-op in native direct AdGuard mode; retained for compatibility.
  val dohInternalPort: Int = 8053,
  // Used when dohEndpointMode is tokenized/dual; maps legacy dohSecretToken.
  val dohPathToken: String = "",
  // When enabled, tokenized DoH requests from routerLanIp are attributed to ddns-last-ipv4.
  val routerPublicIpAttributionEnabled: Boolean = false,
  val routerLanIp: String = "",
  val adminEnabled: Boolean = true,
  val adminUsername: String = "pihole",
  val adminPasswordFile: String = "/data/local/pixel-stack/conf/adguardhome/remote-admin-password",
  val ipinfoLiteTokenFile: String = "/data/local/pixel-stack/conf/adguardhome/ipinfo-lite-token",
  val adminAllowCidrs: String = "0.0.0.0/0",
  val acmeEnabled: Boolean = true,
  val acmeEmail: String = "",
  val acmeCfTokenFile: String = "/data/local/pixel-stack/conf/ddns/cloudflare-token",
  val acmeRenewMinDays: Int = 30,
  // Deprecated no-op in native direct AdGuard mode; retained for compatibility.
  val dohRateLimitRps: Int = 20,
  val watchdogEnabled: Boolean = true,
  val watchdogEscalateRuntimeRestart: Boolean = false,
  val watchdogIntervalSeconds: Int = 30,
  val watchdogFails: Int = 2,
  val watchdogCooldownSeconds: Int = 120
)

@Serializable
data class SshConfig(
  val bindAddress: String = "0.0.0.0",
  val port: Int = 2222,
  val authMode: String = "key_password",
  val passwordAuthEnabled: Boolean = true,
  val keepAliveSeconds: Int = 30,
  val idleTimeoutSeconds: Int = 0,
  val receiveWindowBytes: Int = 262144,
  val wifiForceLowLatencyMode: Boolean = true,
  val wifiForceHiPerfMode: Boolean = false,
  val maxRapidRestarts: Int = 5,
  val rapidWindowSeconds: Int = 300,
  val backoffInitialSeconds: Int = 5,
  val backoffMaxSeconds: Int = 60,
  val dropbearRootDir: String = "/data/local/pixel-stack/ssh",
  val authorizedKeysSourceFile: String = "/data/local/pixel-stack/conf/ssh/authorized_keys",
  val passwordHashSourceFile: String = "/data/local/pixel-stack/conf/ssh/root_password.hash"
)

@Serializable
data class VpnConfig(
  val enabled: Boolean = false,
  val runtimeRoot: String = "/data/local/pixel-stack/vpn",
  val authKeyFile: String = "/data/local/pixel-stack/conf/vpn/tailscale-authkey",
  val interfaceName: String = "tailscale0",
  val hostname: String = "",
  val advertiseTags: String = "",
  val acceptRoutes: Boolean = false,
  val acceptDns: Boolean = false,
  val nativeWirelessDebugEnabled: Boolean = false,
  val maxRapidRestarts: Int = 5,
  val rapidWindowSeconds: Int = 300,
  val backoffInitialSeconds: Int = 5,
  val backoffMaxSeconds: Int = 60
)

@Serializable
data class TrainBotConfig(
  val runtimeRoot: String = "/data/local/pixel-stack/apps/train-bot",
  val envFile: String = "/data/local/pixel-stack/conf/apps/train-bot.env",
  val binaryPath: String = "/data/local/pixel-stack/apps/train-bot/bin/train-bot.current",
  val scheduleDir: String = "/data/local/pixel-stack/apps/train-bot/data/schedules",
  val publicBaseUrl: String = "https://train-bot.jolkins.id.lv",
  val ingressMode: String = "cloudflare_tunnel",
  val tunnelName: String = "train-bot",
  val tunnelCredentialsFile: String = "/data/local/pixel-stack/conf/apps/train-bot-cloudflared.json",
  val maxRapidRestarts: Int = 5,
  val rapidWindowSeconds: Int = 300,
  val backoffInitialSeconds: Int = 5,
  val backoffMaxSeconds: Int = 60
)

@Serializable
data class SatiksmeBotConfig(
  val runtimeRoot: String = "/data/local/pixel-stack/apps/satiksme-bot",
  val envFile: String = "/data/local/pixel-stack/conf/apps/satiksme-bot.env",
  val binaryPath: String = "/data/local/pixel-stack/apps/satiksme-bot/bin/satiksme-bot.current",
  val publicBaseUrl: String = "https://satiksme-bot.jolkins.id.lv",
  val ingressMode: String = "cloudflare_tunnel",
  val tunnelName: String = "satiksme-bot",
  val tunnelCredentialsFile: String = "/data/local/pixel-stack/conf/apps/satiksme-bot-cloudflared.json",
  val maxRapidRestarts: Int = 5,
  val rapidWindowSeconds: Int = 300,
  val backoffInitialSeconds: Int = 5,
  val backoffMaxSeconds: Int = 60
)

@Serializable
data class SiteNotifierConfig(
  val runtimeRoot: String = "/data/local/pixel-stack/apps/site-notifications",
  val envFile: String = "/data/local/pixel-stack/conf/apps/site-notifications.env",
  val pythonPath: String = "/data/local/pixel-stack/apps/site-notifications/bin/site-notifier-python.current",
  val entryScript: String = "/data/local/pixel-stack/apps/site-notifications/current/app.py",
  val maxRapidRestarts: Int = 5,
  val rapidWindowSeconds: Int = 300,
  val backoffInitialSeconds: Int = 5,
  val backoffMaxSeconds: Int = 60
)

@Serializable
data class SubscriptionBotConfig(
  val runtimeRoot: String = "/data/local/pixel-stack/apps/subscription-bot",
  val envFile: String = "/data/local/pixel-stack/conf/apps/subscription-bot.env",
  val binaryPath: String = "/data/local/pixel-stack/apps/subscription-bot/bin/subscription-bot.current",
  val publicBaseUrl: String = "https://farel-subscription-bot.jolkins.id.lv/pixel-stack/subscription",
  val ingressMode: String = "cloudflare_tunnel",
  val tunnelName: String = "subscription-bot",
  val tunnelCredentialsFile: String = "/data/local/pixel-stack/conf/apps/subscription-bot-cloudflared.json",
  val webShellEnabled: Boolean = true,
  val e2eMode: Boolean = false,
  val maxRapidRestarts: Int = 5,
  val rapidWindowSeconds: Int = 300,
  val backoffInitialSeconds: Int = 5,
  val backoffMaxSeconds: Int = 60
)

@Serializable
data class DdnsConfig(
  val enabled: Boolean = true,
  val provider: String = "cloudflare",
  val intervalSeconds: Int = 120,
  val requireStableReads: Int = 2,
  val syncOnNetworkChange: Boolean = true,
  val movePolicy: String = "stick_new_ip",
  val ttl: Int = 120,
  val zoneName: String = "example.com",
  val recordName: String = "dns.example.com",
  val updateIpv4: Boolean = true,
  val updateIpv6: Boolean = false,
  val tokenFile: String = "/data/local/pixel-stack/conf/ddns/cloudflare-token",
  val ipv4DiscoveryUrls: String = "https://api.ipify.org?format=json,https://checkip.amazonaws.com,https://ipv4.icanhazip.com",
  val ipv6DiscoveryUrls: String = "https://api64.ipify.org?format=json,https://ipv6.icanhazip.com"
)

@Serializable
data class RedeployConfig(
  val healthWaitSeconds: Int = 180,
  val healthRetrySeconds: Int = 3,
  val neighborGraceSeconds: Int = 12
)

@Serializable
data class SupervisionConfig(
  val healthPollSeconds: Int = 15,
  val networkConvergenceWindowSeconds: Int = 180,
  val networkConvergencePollSeconds: Int = 5,
  val enforceRemoteListeners: Boolean = true,
  val unhealthyFails: Int = 3,
  val managementUnhealthyFails: Int = 2,
  val managementRequireWirelessDebug: Boolean = false,
  val managementRecoveryCooldownSeconds: Int = 120,
  val maxRapidRestarts: Int = 5,
  val rapidWindowSeconds: Int = 300,
  val backoffInitialSeconds: Int = 5,
  val backoffMaxSeconds: Int = 60
)

@Serializable
data class FeatureFlags(
  val remoteClientPackEnabled: Boolean = true,
  val windowsDohRefreshEnabled: Boolean = true
)

@Serializable
data class ModuleConfig(
  val enabled: Boolean = true,
  val startCommandOverride: String = "",
  val stopCommandOverride: String = "",
  val healthCommandOverride: String = ""
)

@Serializable
data class ObservabilityConfig(
  val enabled: Boolean = true,
  val runIdEnvVar: String = "PIXEL_RUN_ID",
  val eventOutputDir: String = "/data/local/pixel-stack/logs/events",
  val evidenceRoot: String = "/data/local/pixel-stack/logs/evidence"
)
