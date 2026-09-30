use crate::{Config, trim, whitespace};
use std::collections::BTreeMap;

// Marker protocol and empty-section defaults are shared with the installed
// shell helpers. Omitted enabled sections invalidate the entire atomic sample.
const FIELDS: &[(&str, &str, Option<&str>)] = &[
    ("idU", "__PIXEL_HEALTH_ID_U__", None),
    ("listeners", "__PIXEL_HEALTH_LISTENERS__", None),
    ("ddnsEpoch", "__PIXEL_HEALTH_DDNS_EPOCH__", Some("")),
    ("ddnsLastIpv4", "__PIXEL_HEALTH_DDNS_LAST_IPV4__", Some("")),
    (
        "supervisorLoopHeartbeatEpoch",
        "__PIXEL_HEALTH_SUPERVISOR_LOOP_HEARTBEAT__",
        Some(""),
    ),
    ("trainBotPid", "__PIXEL_HEALTH_TRAIN_BOT_PID__", Some("")),
    (
        "trainBotTunnelEnabled",
        "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_ENABLED__",
        Some("0"),
    ),
    (
        "trainBotTunnelSupervisorPid",
        "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_SUPERVISOR_PID__",
        Some(""),
    ),
    (
        "trainBotTunnelPid",
        "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PID__",
        Some(""),
    ),
    (
        "trainBotTunnelPublicBaseUrl",
        "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PUBLIC_BASE_URL__",
        Some(""),
    ),
    (
        "trainBotPublicRootCode",
        "__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_ROOT_CODE__",
        Some("000"),
    ),
    (
        "trainBotPublicAppCode",
        "__PIXEL_HEALTH_TRAIN_BOT_PUBLIC_APP_CODE__",
        Some("000"),
    ),
    (
        "trainBotTunnelProbeAvailable",
        "__PIXEL_HEALTH_TRAIN_BOT_TUNNEL_PROBE_AVAILABLE__",
        Some("0"),
    ),
    (
        "trainBotHeartbeatEpoch",
        "__PIXEL_HEALTH_TRAIN_BOT_HEARTBEAT__",
        Some(""),
    ),
    (
        "trainBotScheduleRequired",
        "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_REQUIRED__",
        Some("0"),
    ),
    (
        "trainBotScheduleFresh",
        "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_FRESH__",
        Some("0"),
    ),
    (
        "trainBotScheduleServiceDate",
        "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_SERVICE_DATE__",
        Some(""),
    ),
    (
        "trainBotScheduleRows",
        "__PIXEL_HEALTH_TRAIN_BOT_SCHEDULE_ROWS__",
        Some("unknown"),
    ),
    (
        "satiksmeBotPid",
        "__PIXEL_HEALTH_SATIKSME_BOT_PID__",
        Some(""),
    ),
    (
        "satiksmeBotTunnelEnabled",
        "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_ENABLED__",
        Some("0"),
    ),
    (
        "satiksmeBotTunnelSupervisorPid",
        "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_SUPERVISOR_PID__",
        Some(""),
    ),
    (
        "satiksmeBotTunnelPid",
        "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PID__",
        Some(""),
    ),
    (
        "satiksmeBotTunnelPublicBaseUrl",
        "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PUBLIC_BASE_URL__",
        Some(""),
    ),
    (
        "satiksmeBotPublicRootCode",
        "__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_ROOT_CODE__",
        Some("000"),
    ),
    (
        "satiksmeBotPublicAppCode",
        "__PIXEL_HEALTH_SATIKSME_BOT_PUBLIC_APP_CODE__",
        Some("000"),
    ),
    (
        "satiksmeBotTunnelProbeAvailable",
        "__PIXEL_HEALTH_SATIKSME_BOT_TUNNEL_PROBE_AVAILABLE__",
        Some("0"),
    ),
    (
        "satiksmeBotHeartbeatEpoch",
        "__PIXEL_HEALTH_SATIKSME_BOT_HEARTBEAT__",
        Some(""),
    ),
    (
        "siteNotifierPid",
        "__PIXEL_HEALTH_SITE_NOTIFIER_PID__",
        Some(""),
    ),
    (
        "siteNotifierHeartbeatEpoch",
        "__PIXEL_HEALTH_SITE_NOTIFIER_HEARTBEAT__",
        Some(""),
    ),
    (
        "siteNotifierHelperHealthy",
        "__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_HEALTHY__",
        Some("0"),
    ),
    (
        "siteNotifierHelperReason",
        "__PIXEL_HEALTH_SITE_NOTIFIER_HELPER_REASON__",
        Some("unknown"),
    ),
    (
        "subscriptionBotPid",
        "__PIXEL_HEALTH_SUBSCRIPTION_BOT_PID__",
        Some(""),
    ),
    (
        "subscriptionBotHeartbeatEpoch",
        "__PIXEL_HEALTH_SUBSCRIPTION_BOT_HEARTBEAT__",
        Some(""),
    ),
    ("vpnHealth", "__PIXEL_HEALTH_VPN_HEALTH__", Some("0")),
    (
        "vpnEnabledEffective",
        "__PIXEL_HEALTH_VPN_ENABLED_EFFECTIVE__",
        Some("0"),
    ),
    (
        "vpnTailscaledLive",
        "__PIXEL_HEALTH_VPN_TAILSCALED_LIVE__",
        Some("0"),
    ),
    (
        "vpnTailscaledSock",
        "__PIXEL_HEALTH_VPN_TAILSCALED_SOCK__",
        Some("0"),
    ),
    (
        "vpnTailnetIpv4",
        "__PIXEL_HEALTH_VPN_TAILNET_IPV4__",
        Some(""),
    ),
    (
        "vpnGuardChainIpv4",
        "__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV4__",
        Some("0"),
    ),
    (
        "vpnGuardChainIpv6",
        "__PIXEL_HEALTH_VPN_GUARD_CHAIN_IPV6__",
        Some("0"),
    ),
    (
        "managementEnabled",
        "__PIXEL_HEALTH_MANAGEMENT_ENABLED__",
        Some("0"),
    ),
    (
        "managementHealthy",
        "__PIXEL_HEALTH_MANAGEMENT_HEALTHY__",
        Some("0"),
    ),
    (
        "managementReason",
        "__PIXEL_HEALTH_MANAGEMENT_REASON__",
        Some("unknown"),
    ),
    (
        "managementAuthConsistent",
        "__PIXEL_HEALTH_MANAGEMENT_AUTH_CONSISTENT__",
        Some("1"),
    ),
    (
        "managementAuthWarningReason",
        "__PIXEL_HEALTH_MANAGEMENT_AUTH_WARNING_REASON__",
        Some("ok"),
    ),
    (
        "managementSshListener",
        "__PIXEL_HEALTH_MANAGEMENT_SSH_LISTENER__",
        Some("0"),
    ),
    (
        "managementSshAuthMode",
        "__PIXEL_HEALTH_MANAGEMENT_SSH_AUTH_MODE__",
        Some("disabled"),
    ),
    (
        "managementSshPasswordAuthRequested",
        "__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_REQUESTED__",
        Some("0"),
    ),
    (
        "managementSshPasswordAuthReady",
        "__PIXEL_HEALTH_MANAGEMENT_SSH_PASSWORD_AUTH_READY__",
        Some("0"),
    ),
    (
        "managementSshKeyAuthRequested",
        "__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_REQUESTED__",
        Some("0"),
    ),
    (
        "managementSshKeyAuthReady",
        "__PIXEL_HEALTH_MANAGEMENT_SSH_KEY_AUTH_READY__",
        Some("0"),
    ),
    (
        "managementPmPath",
        "__PIXEL_HEALTH_MANAGEMENT_PM_PATH__",
        Some(""),
    ),
    (
        "managementAmPath",
        "__PIXEL_HEALTH_MANAGEMENT_AM_PATH__",
        Some(""),
    ),
    (
        "managementLogcatPath",
        "__PIXEL_HEALTH_MANAGEMENT_LOGCAT_PATH__",
        Some(""),
    ),
    (
        "managementWirelessDebugEnabled",
        "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_ENABLED__",
        Some("0"),
    ),
    (
        "managementWirelessDebugTlsPort",
        "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_TLS_PORT__",
        Some(""),
    ),
    (
        "managementWirelessDebugLive",
        "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE__",
        Some("0"),
    ),
    (
        "managementWirelessDebugLivePorts",
        "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_LIVE_PORTS__",
        Some(""),
    ),
    (
        "managementWirelessDebugHealthy",
        "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_HEALTHY__",
        Some("0"),
    ),
    (
        "managementWirelessDebugReason",
        "__PIXEL_HEALTH_MANAGEMENT_WIRELESS_DEBUG_REASON__",
        Some("unknown"),
    ),
    (
        "managementWifiEnabled",
        "__PIXEL_HEALTH_MANAGEMENT_WIFI_ENABLED__",
        Some("0"),
    ),
    (
        "managementWifiConnected",
        "__PIXEL_HEALTH_MANAGEMENT_WIFI_CONNECTED__",
        Some("0"),
    ),
    (
        "managementWifiIpv4",
        "__PIXEL_HEALTH_MANAGEMENT_WIFI_IPV4__",
        Some(""),
    ),
    (
        "managementMobileIface",
        "__PIXEL_HEALTH_MANAGEMENT_MOBILE_IFACE__",
        Some(""),
    ),
    (
        "managementMobileIpv4",
        "__PIXEL_HEALTH_MANAGEMENT_MOBILE_IPV4__",
        Some(""),
    ),
    (
        "managementActiveTransport",
        "__PIXEL_HEALTH_MANAGEMENT_ACTIVE_TRANSPORT__",
        Some("unknown"),
    ),
    (
        "managementPublicIpv4Candidate",
        "__PIXEL_HEALTH_MANAGEMENT_PUBLIC_IPV4_CANDIDATE__",
        Some(""),
    ),
    (
        "managementNetworkFingerprint",
        "__PIXEL_HEALTH_MANAGEMENT_NETWORK_FINGERPRINT__",
        Some(""),
    ),
    (
        "remoteDohTokenizedCode",
        "__PIXEL_HEALTH_REMOTE_DOH_TOKENIZED_CODE__",
        Some("000"),
    ),
    (
        "remoteDohBareCode",
        "__PIXEL_HEALTH_REMOTE_DOH_BARE_CODE__",
        Some("000"),
    ),
    (
        "remoteIdentityInjectCode",
        "__PIXEL_HEALTH_REMOTE_IDENTITY_INJECT_CODE__",
        Some("000"),
    ),
    (
        "remotePublicBaseUrl",
        "__PIXEL_HEALTH_REMOTE_PUBLIC_BASE_URL__",
        Some(""),
    ),
    (
        "remotePublicRootCode",
        "__PIXEL_HEALTH_REMOTE_PUBLIC_ROOT_CODE__",
        Some("000"),
    ),
    (
        "remotePublicProbeAvailable",
        "__PIXEL_HEALTH_REMOTE_PUBLIC_PROBE_AVAILABLE__",
        Some("0"),
    ),
    (
        "remotePublicDohTokenizedCode",
        "__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_TOKENIZED_CODE__",
        Some("000"),
    ),
    (
        "remotePublicDohBareCode",
        "__PIXEL_HEALTH_REMOTE_PUBLIC_DOH_BARE_CODE__",
        Some("000"),
    ),
    (
        "remotePublicIdentityInjectCode",
        "__PIXEL_HEALTH_REMOTE_PUBLIC_IDENTITY_INJECT_CODE__",
        Some("000"),
    ),
];

#[derive(Default)]
pub struct Parsed(BTreeMap<&'static str, String>);
impl Parsed {
    pub fn get(&self, key: &str) -> &str {
        self.0.get(key).map(String::as_str).unwrap_or("")
    }
}

pub fn parse(stdout: &str, config: &Config) -> Result<Option<Parsed>, String> {
    let mut sections: BTreeMap<&str, String> = BTreeMap::new();
    let mut section = None;
    let mut end = false;
    let normalized = stdout.replace("\r\n", "\n").replace('\r', "\n");
    for line in normalized.split('\n') {
        let marker = line.trim_end_matches(whitespace);
        if marker == "__PIXEL_HEALTH_DONE__" {
            end = true;
            section = None;
        } else if let Some((_, known, _)) = FIELDS.iter().find(|(_, known, _)| *known == marker) {
            sections.entry(*known).or_default();
            section = Some(*known);
        } else if let Some(key) = section {
            let text = sections.get_mut(key).expect("current section exists");
            text.push_str(line);
            text.push('\n');
        }
    }
    let ddns = config.enabled("ddns")? && config.b("ddns.enabled")?;
    let train = config.enabled("train_bot")?;
    let satiksme = config.enabled("satiksme_bot")?;
    let notifier = config.enabled("site_notifier")?;
    let subscription = config.enabled("subscription_bot")?;
    let remote = config.enabled("remote")?
        && (config.b("remote.dohEnabled")? || config.b("remote.dotEnabled")?);
    if !end
        || FIELDS.iter().any(|(_, marker, _)| {
            let required = if marker.starts_with("__PIXEL_HEALTH_DDNS_") {
                ddns
            } else if marker.starts_with("__PIXEL_HEALTH_TRAIN_BOT_") {
                train
            } else if marker.starts_with("__PIXEL_HEALTH_SATIKSME_BOT_") {
                satiksme
            } else if marker.starts_with("__PIXEL_HEALTH_SITE_NOTIFIER_") {
                notifier
            } else if marker.starts_with("__PIXEL_HEALTH_SUBSCRIPTION_BOT_") {
                subscription
            } else if marker.starts_with("__PIXEL_HEALTH_REMOTE_") {
                remote
            } else {
                true
            };
            required && !sections.contains_key(marker)
        })
    {
        return Ok(None);
    }
    let mut parsed = BTreeMap::new();
    for (field, marker, fallback) in FIELDS {
        let text = sections.get(marker).map(String::as_str).unwrap_or("");
        let value = match fallback {
            None => text,
            Some(default) => text
                .split('\n')
                .find(|line| !trim(line).is_empty())
                .unwrap_or(default),
        };
        parsed.insert(*field, value.to_owned());
    }
    Ok(Some(Parsed(parsed)))
}
