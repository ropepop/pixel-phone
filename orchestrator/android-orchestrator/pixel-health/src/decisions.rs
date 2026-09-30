#![allow(non_snake_case)]
use crate::{Config, parser, trim};
use serde_json::{Value, json};

fn blank<'a>(value: &'a str, fallback: &'a str) -> &'a str {
    if trim(value).is_empty() {
        fallback
    } else {
        value
    }
}
fn has_port(listeners: &str, port: i64) -> bool {
    let needle = format!(":{port}");
    listeners
        .lines()
        .any(|line| line.contains(&format!("{needle} ")) || line.ends_with(&needle))
}
fn age(epoch: &str, now: i64) -> Option<i64> {
    integer(trim(epoch)).map(|epoch| {
        if epoch <= now {
            now.wrapping_sub(epoch)
        } else {
            0
        }
    })
}
fn age_text(value: Option<i64>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "unknown".into())
}
fn reachable(code: &str) -> bool {
    let code = trim(code);
    code.chars().count() == 3
        && code.chars().all(|c| digit(c).is_some())
        && integer(code).is_some_and(|code| (100..=499).contains(&code) && code != 404)
}
// Kotlin's numeric conversion accepts BMP decimal digits through Character.digit.
// Supplementary code points are surrogate pairs in that API and are rejected.
fn digit(c: char) -> Option<i64> {
    const ZEROES: &[u32] = &[
        0x0030, 0x0660, 0x06f0, 0x07c0, 0x0966, 0x09e6, 0x0a66, 0x0ae6, 0x0b66, 0x0be6, 0x0c66,
        0x0ce6, 0x0d66, 0x0de6, 0x0e50, 0x0ed0, 0x0f20, 0x1040, 0x1090, 0x17e0, 0x1810, 0x1946,
        0x19d0, 0x1a80, 0x1a90, 0x1b50, 0x1bb0, 0x1c40, 0x1c50, 0xa620, 0xa8d0, 0xa900, 0xa9d0,
        0xa9f0, 0xaa50, 0xabf0, 0xff10,
    ];
    let code = c as u32;
    ZEROES
        .iter()
        .find_map(|zero| (code >= *zero && code < zero + 10).then(|| i64::from(code - zero)))
}
fn integer(value: &str) -> Option<i64> {
    let negative = value.starts_with('-');
    let value = value.strip_prefix(['-', '+']).unwrap_or(value);
    if value.is_empty() {
        return None;
    }
    let mut result = 0_i64;
    for c in value.chars() {
        result = result.checked_mul(10)?.checked_sub(digit(c)?)?;
    }
    if negative {
        Some(result)
    } else {
        result.checked_neg()
    }
}
fn doh_contract(
    enabled: bool,
    available: bool,
    mode: &str,
    token: &str,
    bare: &str,
    unavailable_ok: bool,
) -> bool {
    if !enabled {
        true
    } else if !available {
        unavailable_ok
    } else {
        match mode {
            "tokenized" => reachable(token) && bare == "404",
            "dual" => reachable(token) && reachable(bare),
            "native" => reachable(bare),
            _ => false,
        }
    }
}
fn module(enabled: bool, healthy: bool, details: Value) -> Value {
    json!({"healthy": healthy, "status": if !enabled { "disabled" } else if healthy { "running" } else { "degraded" }, "details": details})
}

pub fn interpret(config: &Config, ok: bool, stdout: &str, nowEpoch: i64) -> Result<Value, String> {
    let parsed = if ok {
        parser::parse(stdout, config)?
    } else {
        None
    };
    let listenersOk = parsed.is_some();
    let p = parsed.unwrap_or_default();
    let t = |key| trim(p.get(key));
    let rootValue = t("idU");
    let listenersOutput = p.get("listeners");
    let dnsEnabled = config.enabled("dns")?;
    let sshEnabled = config.enabled("ssh")?;
    let trainBotEnabled = config.enabled("train_bot")?;
    let satiksmeBotEnabled = config.enabled("satiksme_bot")?;
    let siteNotifierEnabled = config.enabled("site_notifier")?;
    let subscriptionBotEnabled = config.enabled("subscription_bot")?;
    let ticketScreenEnabled = config.enabled("ticket_screen")?;
    let trainBotPid = t("trainBotPid");
    let trainBotTunnelEnabled = t("trainBotTunnelEnabled") == "1";
    let trainBotTunnelSupervisorPid = t("trainBotTunnelSupervisorPid");
    let trainBotTunnelPid = t("trainBotTunnelPid");
    let trainBotTunnelPublicBaseUrl = t("trainBotTunnelPublicBaseUrl");
    let trainBotPublicRootCode = blank(t("trainBotPublicRootCode"), "000");
    let trainBotPublicAppCode = blank(t("trainBotPublicAppCode"), "000");
    let trainBotTunnelProbeAvailable = t("trainBotTunnelProbeAvailable") == "1";
    let satiksmeBotPid = t("satiksmeBotPid");
    let satiksmeBotTunnelEnabled = t("satiksmeBotTunnelEnabled") == "1";
    let satiksmeBotTunnelSupervisorPid = t("satiksmeBotTunnelSupervisorPid");
    let satiksmeBotTunnelPid = t("satiksmeBotTunnelPid");
    let satiksmeBotTunnelPublicBaseUrl = t("satiksmeBotTunnelPublicBaseUrl");
    let satiksmeBotPublicRootCode = blank(t("satiksmeBotPublicRootCode"), "000");
    let satiksmeBotPublicAppCode = blank(t("satiksmeBotPublicAppCode"), "000");
    let satiksmeBotTunnelProbeAvailable = t("satiksmeBotTunnelProbeAvailable") == "1";
    let siteNotifierPid = t("siteNotifierPid");
    let siteNotifierHelperHealthy = t("siteNotifierHelperHealthy") == "1";
    let subscriptionBotPid = t("subscriptionBotPid");
    let trainBotHeartbeat = p.get("trainBotHeartbeatEpoch");
    let satiksmeBotHeartbeat = p.get("satiksmeBotHeartbeatEpoch");
    let siteNotifierHeartbeat = p.get("siteNotifierHeartbeatEpoch");
    let subscriptionBotHeartbeat = p.get("subscriptionBotHeartbeatEpoch");
    let trainBotScheduleRequired = t("trainBotScheduleRequired") == "1";
    let trainBotScheduleFresh = t("trainBotScheduleFresh") == "1";
    let trainBotScheduleServiceDate = t("trainBotScheduleServiceDate");
    let trainBotScheduleRows = blank(t("trainBotScheduleRows"), "unknown");
    let vpnTailscaledLive = blank(t("vpnTailscaledLive"), "0");
    let vpnTailscaledSock = blank(t("vpnTailscaledSock"), "0");
    let vpnTailnetIpv4 = t("vpnTailnetIpv4");
    let vpnGuardChainIpv4 = blank(t("vpnGuardChainIpv4"), "0");
    let vpnGuardChainIpv6 = blank(t("vpnGuardChainIpv6"), "0");
    let managementEnabled = t("managementEnabled") == "1";
    let managementSshListenerRaw = t("managementSshListener");
    let managementSshAuthMode = blank(t("managementSshAuthMode"), "disabled");
    let managementSshPasswordAuthRequested = blank(t("managementSshPasswordAuthRequested"), "0");
    let managementSshPasswordAuthReady = blank(t("managementSshPasswordAuthReady"), "0");
    let managementSshKeyAuthRequested = blank(t("managementSshKeyAuthRequested"), "0");
    let managementSshKeyAuthReady = blank(t("managementSshKeyAuthReady"), "0");
    let managementPmPath = t("managementPmPath");
    let managementAmPath = t("managementAmPath");
    let managementLogcatPath = t("managementLogcatPath");
    let managementRequireWirelessDebug = config.b("supervision.managementRequireWirelessDebug")?;
    let managementRequireAuthConsistency =
        config.b("supervision.managementRequireAuthConsistency")?;
    let managementWirelessDebugEnabled = blank(t("managementWirelessDebugEnabled"), "0");
    let managementWirelessDebugTlsPort = t("managementWirelessDebugTlsPort");
    let managementWirelessDebugLive = blank(t("managementWirelessDebugLive"), "0");
    let managementWirelessDebugLivePorts = t("managementWirelessDebugLivePorts");
    let managementWirelessDebugHealthy = blank(t("managementWirelessDebugHealthy"), "0");
    let managementWirelessDebugReason = blank(t("managementWirelessDebugReason"), "unknown");
    let managementWifiEnabled = blank(t("managementWifiEnabled"), "0");
    let managementWifiConnected = blank(t("managementWifiConnected"), "0");
    let managementWifiIpv4 = t("managementWifiIpv4");
    let managementMobileIface = t("managementMobileIface");
    let managementMobileIpv4 = t("managementMobileIpv4");
    let managementActiveTransport = blank(t("managementActiveTransport"), "unknown");
    let managementPublicIpv4Candidate = t("managementPublicIpv4Candidate");
    let managementNetworkFingerprint = t("managementNetworkFingerprint");
    let ddnsPublishedIpv4 = t("ddnsLastIpv4");
    let supervisorLoopHeartbeat = p.get("supervisorLoopHeartbeatEpoch");
    let vpnEnabled = config.enabled("vpn")? && config.b("vpn.enabled")?;
    let ddnsRequired = config.enabled("ddns")? && config.b("ddns.enabled")?;
    let remoteEnabled = config.enabled("remote")?
        && (config.b("remote.dohEnabled")? || config.b("remote.dotEnabled")?);
    let siteNotifierFailureReason = blank(
        t("siteNotifierHelperReason"),
        if siteNotifierHelperHealthy {
            "ok"
        } else {
            "unknown"
        },
    );
    let trainBotScheduleRowsPresent =
        integer(trainBotScheduleRows).is_some_and(|rows| rows > 0 && rows <= i64::from(i32::MAX));
    let vpnHealthy = vpnEnabled && t("vpnHealth") == "1";
    let vpnEnabledEffective = blank(t("vpnEnabledEffective"), if vpnEnabled { "1" } else { "0" });
    let managementHealthy = !managementEnabled || t("managementHealthy") == "1";
    let managementReason = blank(
        t("managementReason"),
        if managementEnabled {
            "unknown"
        } else {
            "disabled"
        },
    );
    let authMismatch = managementReason == "password_auth_runtime_mismatch"
        || managementReason == "key_auth_runtime_mismatch";
    let managementAuthConsistent = !managementEnabled
        || match t("managementAuthConsistent") {
            "1" => true,
            "0" => false,
            _ => !authMismatch,
        };
    let managementAuthWarningReason = blank(
        t("managementAuthWarningReason"),
        if !managementEnabled {
            "disabled"
        } else if managementAuthConsistent {
            "ok"
        } else if authMismatch {
            managementReason
        } else {
            "unknown"
        },
    );
    let managementSshListener = blank(managementSshListenerRaw, "0");
    let managementPathHealthy = !managementEnabled || managementHealthy;
    let rootGranted = rootValue == "0";
    let dnsHealthy = dnsEnabled && has_port(listenersOutput, config.i("dns.dnsPort")?);
    let ticketScreenHealthy = has_port(listenersOutput, 9388);
    let sshHealthy = sshEnabled
        && if !managementSshListenerRaw.is_empty() {
            managementSshListener == "1"
        } else {
            has_port(listenersOutput, config.i("ssh.port")?)
        };
    let dohEndpointMode = config.mode()?;
    let remoteHealthEnforced = remoteEnabled
        && config.b("supervision.enforceRemoteListeners")?
        && config.b("remote.watchdogEscalateRuntimeRestart")?;
    let remoteHttps =
        !remoteHealthEnforced || has_port(listenersOutput, config.i("remote.httpsPort")?);
    let remoteDot =
        !config.b("remote.dotEnabled")? || has_port(listenersOutput, config.i("remote.dotPort")?);
    let tokenizedDohCode = t("remoteDohTokenizedCode");
    let bareDohCode = t("remoteDohBareCode");
    let identityInjectCode = t("remoteIdentityInjectCode");
    let remotePublicBaseUrl = t("remotePublicBaseUrl");
    let remotePublicRootCode = blank(t("remotePublicRootCode"), "000");
    let remotePublicTokenizedCode = blank(t("remotePublicDohTokenizedCode"), "000");
    let remotePublicBareCode = blank(t("remotePublicDohBareCode"), "000");
    let remotePublicIdentityInjectCode = blank(t("remotePublicIdentityInjectCode"), "000");
    let remotePublicProbeAvailable = t("remotePublicProbeAvailable") == "1";
    let identityFrontendRequired = matches!(dohEndpointMode.as_str(), "tokenized" | "dual")
        && (config.b("remote.dohEnabled")? || config.b("remote.dotEnabled")?);
    let identityProbeUnavailable = identityInjectCode == "000";
    let identityFrontendHealthy =
        !identityFrontendRequired || identityProbeUnavailable || identityInjectCode == "200";
    let dohProbeMode = "no_query_http_contract";
    let dohProbeUnavailable = tokenizedDohCode == "000" && bareDohCode == "000";
    let remoteDohContract = doh_contract(
        config.b("remote.dohEnabled")?,
        !dohProbeUnavailable,
        &dohEndpointMode,
        tokenizedDohCode,
        bareDohCode,
        true,
    );
    let remotePublicRootHealthy = matches!(
        trim(remotePublicRootCode),
        "200" | "301" | "302" | "303" | "307" | "308" | "401"
    );
    let remotePublicIdentityFrontendHealthy = !identityFrontendRequired
        || (remotePublicProbeAvailable && remotePublicIdentityInjectCode == "200");
    let remotePublicDohContract = doh_contract(
        config.b("remote.dohEnabled")?,
        remotePublicProbeAvailable,
        &dohEndpointMode,
        remotePublicTokenizedCode,
        remotePublicBareCode,
        false,
    );
    let remotePublicHealthy = !remoteEnabled
        || (remotePublicProbeAvailable
            && remotePublicRootHealthy
            && remotePublicDohContract
            && remotePublicIdentityFrontendHealthy);
    let directPublicDnsConsistent = managementPublicIpv4Candidate.is_empty()
        || ddnsPublishedIpv4.is_empty()
        || managementPublicIpv4Candidate == ddnsPublishedIpv4;
    let directPublicProbeHealthy =
        !remoteEnabled || !remotePublicProbeAvailable || remotePublicHealthy;
    let directPublicTransitioning =
        remotePublicProbeAvailable && directPublicProbeHealthy && !directPublicDnsConsistent;
    let directPublicTransitionReason = if directPublicTransitioning {
        "published_public_ipv4_lagging"
    } else {
        "ok"
    };
    let directPublicPathHealthy = directPublicProbeHealthy;
    let ddnsFreshnessSeconds = 600.max(config.i("ddns.intervalSeconds")?.max(1) * 2);
    let ddnsHealthy = ddnsRequired
        && integer(trim(p.get("ddnsEpoch")))
            .is_some_and(|epoch| nowEpoch.wrapping_sub(epoch) <= ddnsFreshnessSeconds);
    let supervisorLoopHeartbeatAge = age(supervisorLoopHeartbeat, nowEpoch);
    let mut maxBackoff = i64::MIN;
    for path in [
        "supervision.backoffMaxSeconds",
        "ssh.backoffMaxSeconds",
        "vpn.backoffMaxSeconds",
        "trainBot.backoffMaxSeconds",
        "satiksmeBot.backoffMaxSeconds",
        "siteNotifier.backoffMaxSeconds",
        "subscriptionBot.backoffMaxSeconds",
    ] {
        maxBackoff = maxBackoff.max(config.i(path)?);
    }
    let maxPoll = config.i("supervision.healthPollSeconds")?.max(1).max(
        config
            .i("supervision.networkConvergencePollSeconds")?
            .max(1),
    );
    let supervisorLoopFreshSeconds = 30.max(maxBackoff + maxPoll + 5);
    let supervisorLoopHealthy =
        supervisorLoopHeartbeatAge.is_some_and(|age| age <= supervisorLoopFreshSeconds);
    let trainBotHeartbeatAge = age(trainBotHeartbeat, nowEpoch);
    let satiksmeBotHeartbeatAge = age(satiksmeBotHeartbeat, nowEpoch);
    let siteNotifierHeartbeatAge = age(siteNotifierHeartbeat, nowEpoch);
    let subscriptionBotHeartbeatAge = age(subscriptionBotHeartbeat, nowEpoch);
    let trainBotScheduleProbeInconclusive = trainBotScheduleRows == "unknown";
    let trainBotScheduleHealthy = !trainBotScheduleRequired
        || trainBotScheduleFresh
        || trainBotScheduleRowsPresent
        || trainBotScheduleProbeInconclusive;
    let trainBotPublicRootHealthy = trainBotPublicRootCode == "200";
    let trainBotPublicAppHealthy = trainBotPublicAppCode == "200";
    let trainBotTunnelSupervisorHealthy =
        !trainBotTunnelEnabled || !trainBotTunnelSupervisorPid.is_empty();
    let trainBotTunnelHealthy = !trainBotTunnelEnabled
        || (trainBotTunnelSupervisorHealthy
            && !trainBotTunnelPid.is_empty()
            && trainBotTunnelProbeAvailable
            && trainBotPublicRootHealthy
            && trainBotPublicAppHealthy);
    let satiksmeBotPublicRootHealthy = satiksmeBotPublicRootCode == "200";
    let satiksmeBotPublicAppHealthy = satiksmeBotPublicAppCode == "200";
    let satiksmeBotTunnelSupervisorHealthy =
        !satiksmeBotTunnelEnabled || !satiksmeBotTunnelSupervisorPid.is_empty();
    let satiksmeBotTunnelHealthy = !satiksmeBotTunnelEnabled
        || (satiksmeBotTunnelSupervisorHealthy
            && !satiksmeBotTunnelPid.is_empty()
            && satiksmeBotTunnelProbeAvailable
            && satiksmeBotPublicRootHealthy
            && satiksmeBotPublicAppHealthy);
    let satiksmeBotFailureReason = if satiksmeBotPid.is_empty() {
        "pid_missing"
    } else if satiksmeBotHeartbeatAge.is_none() {
        "heartbeat_missing"
    } else if satiksmeBotHeartbeatAge.is_some_and(|age| age > 120) {
        "heartbeat_stale"
    } else if !satiksmeBotTunnelEnabled {
        "ok"
    } else if !satiksmeBotTunnelSupervisorHealthy {
        "tunnel_supervisor_missing"
    } else if satiksmeBotTunnelPid.is_empty() {
        "tunnel_pid_missing"
    } else if !satiksmeBotTunnelProbeAvailable {
        "tunnel_probe_unavailable"
    } else if !satiksmeBotPublicRootHealthy {
        "public_root_failed"
    } else if !satiksmeBotPublicAppHealthy {
        "public_app_failed"
    } else {
        "ok"
    };
    let trainBotHealthy = trainBotEnabled
        && !trainBotPid.is_empty()
        && trainBotHeartbeatAge.is_some_and(|age| age <= 120)
        && trainBotScheduleHealthy
        && trainBotTunnelHealthy;
    let satiksmeBotHealthy = satiksmeBotEnabled
        && !satiksmeBotPid.is_empty()
        && satiksmeBotHeartbeatAge.is_some_and(|age| age <= 120)
        && satiksmeBotTunnelHealthy;
    let siteNotifierHealthy = siteNotifierEnabled && siteNotifierHelperHealthy;
    let subscriptionBotHealthy = subscriptionBotEnabled
        && !subscriptionBotPid.is_empty()
        && subscriptionBotHeartbeatAge.is_some_and(|age| age <= 120);
    let trainBotFailureReason = if !trainBotEnabled {
        "disabled"
    } else if trainBotPid.is_empty() {
        "pid_missing"
    } else if trainBotHeartbeatAge.is_none() {
        "heartbeat_missing"
    } else if trainBotHeartbeatAge.is_some_and(|age| age > 120) {
        "heartbeat_stale"
    } else if !trainBotScheduleHealthy {
        "schedule_unhealthy"
    } else if !trainBotTunnelHealthy {
        "tunnel_unhealthy"
    } else {
        "ok"
    };
    let siteNotifierEffectiveFailureReason = if !siteNotifierEnabled {
        "disabled"
    } else {
        siteNotifierFailureReason
    };
    let subscriptionBotFailureReason = if !subscriptionBotEnabled {
        "disabled"
    } else if subscriptionBotPid.is_empty() {
        "pid_missing"
    } else if subscriptionBotHeartbeatAge.is_none() {
        "heartbeat_missing"
    } else if subscriptionBotHeartbeatAge.is_some_and(|age| age > 120) {
        "heartbeat_stale"
    } else {
        "ok"
    };
    let remoteHealthy =
        remoteEnabled && remoteHttps && remoteDot && remoteDohContract && identityFrontendHealthy;
    let remoteRequired = remoteHealthEnforced;
    let managementAuthHealthy = !managementEnabled || managementAuthConsistent;
    let managementAuthGateHealthy =
        !managementEnabled || !managementRequireAuthConsistency || managementAuthHealthy;
    let supervisorHealthy = rootGranted
        && (!dnsEnabled || dnsHealthy)
        && (!sshEnabled || sshHealthy)
        && (!vpnEnabled || vpnHealthy)
        && (!trainBotEnabled || trainBotHealthy)
        && (!satiksmeBotEnabled || satiksmeBotHealthy)
        && (!siteNotifierEnabled || siteNotifierHealthy)
        && (!subscriptionBotEnabled || subscriptionBotHealthy)
        && (!remoteRequired || remoteHealthy)
        && (!managementEnabled || managementHealthy);
    let deployHealthy = supervisorLoopHealthy
        && ticketScreenHealthy
        && supervisorHealthy
        && (!ddnsRequired || ddnsHealthy)
        && managementAuthGateHealthy;

    let moduleHealth = json!({
        "dns": module(dnsEnabled, dnsHealthy, json!({
        "dns_port": config.i("dns.dnsPort")?.to_string(),
    })),
        "ssh": module(sshEnabled, sshHealthy, json!({
        "ssh_port": config.i("ssh.port")?.to_string(),
        "ssh_enabled": sshEnabled.to_string(),
        "ssh_listener": managementSshListener,
    })),
        "vpn": module(vpnEnabled, vpnHealthy, json!({
        "vpn_required": vpnEnabled.to_string(),
        "vpn_enabled": vpnEnabled.to_string(),
        "interface_name": config.s("vpn.interfaceName")?,
        "vpn_enabled_effective": vpnEnabledEffective,
        "tailscaled_live": vpnTailscaledLive,
        "tailscaled_sock": vpnTailscaledSock,
        "tailnet_ipv4": blank(vpnTailnetIpv4, "none"),
        "guard_chain_ipv4": vpnGuardChainIpv4,
        "guard_chain_ipv6": vpnGuardChainIpv6,
    })),
        "management": module(managementEnabled, managementHealthy, json!({
        "management_enabled": managementEnabled.to_string(),
        "failure_reason": managementReason,
        "management_auth_healthy": managementAuthHealthy.to_string(),
        "management_auth_required": managementRequireAuthConsistency.to_string(),
        "management_auth_gate_healthy": managementAuthGateHealthy.to_string(),
        "management_auth_consistent": managementAuthConsistent.to_string(),
        "management_auth_warning_reason": managementAuthWarningReason,
        "ssh_listener": managementSshListener,
        "ssh_auth_mode": managementSshAuthMode,
        "ssh_password_auth_requested": managementSshPasswordAuthRequested,
        "ssh_password_auth_ready": managementSshPasswordAuthReady,
        "ssh_key_auth_requested": managementSshKeyAuthRequested,
        "ssh_key_auth_ready": managementSshKeyAuthReady,
        "pm_path": blank(managementPmPath, "none"),
        "am_path": blank(managementAmPath, "none"),
        "logcat_path": blank(managementLogcatPath, "none"),
        "management_path_healthy": managementPathHealthy.to_string(),
        "require_wireless_debug": managementRequireWirelessDebug.to_string(),
        "wireless_debug_enabled": managementWirelessDebugEnabled,
        "wireless_debug_tls_port": blank(managementWirelessDebugTlsPort, "none"),
        "wireless_debug_live": managementWirelessDebugLive,
        "wireless_debug_live_ports": blank(managementWirelessDebugLivePorts, "none"),
        "wireless_debug_healthy": managementWirelessDebugHealthy,
        "wireless_debug_reason": managementWirelessDebugReason,
        "wifi_enabled": managementWifiEnabled,
        "wifi_connected": managementWifiConnected,
        "wifi_ipv4": blank(managementWifiIpv4, "none"),
        "mobile_iface": blank(managementMobileIface, "none"),
        "mobile_ipv4": blank(managementMobileIpv4, "none"),
        "active_transport": managementActiveTransport,
        "public_ipv4_candidate": blank(managementPublicIpv4Candidate, "none"),
        "network_fingerprint": blank(managementNetworkFingerprint, "none"),
        "vpn_required": managementEnabled.to_string(),
        "vpn_healthy": vpnHealthy.to_string(),
        "tailnet_ipv4": blank(vpnTailnetIpv4, "none"),
    })),
        "train_bot": module(trainBotEnabled, trainBotHealthy, json!({
        "train_bot_enabled": trainBotEnabled.to_string(),
        "train_bot_pid": trainBotPid,
        "tunnel_enabled": trainBotTunnelEnabled.to_string(),
        "tunnel_supervisor_pid": blank(trainBotTunnelSupervisorPid, "none"),
        "tunnel_pid": blank(trainBotTunnelPid, "none"),
        "tunnel_public_base_url": blank(trainBotTunnelPublicBaseUrl, "none"),
        "public_root_code": trainBotPublicRootCode,
        "public_app_code": trainBotPublicAppCode,
        "tunnel_probe_available": trainBotTunnelProbeAvailable.to_string(),
        "tunnel_supervisor_healthy": trainBotTunnelSupervisorHealthy.to_string(),
        "public_root_healthy": trainBotPublicRootHealthy.to_string(),
        "public_app_healthy": trainBotPublicAppHealthy.to_string(),
        "tunnel_healthy": trainBotTunnelHealthy.to_string(),
        "heartbeat_age_sec": age_text(trainBotHeartbeatAge),
        "schedule_required": trainBotScheduleRequired.to_string(),
        "schedule_fresh": trainBotScheduleFresh.to_string(),
        "schedule_rows_present": trainBotScheduleRowsPresent.to_string(),
        "schedule_probe_inconclusive": trainBotScheduleProbeInconclusive.to_string(),
        "schedule_service_date": trainBotScheduleServiceDate,
        "schedule_rows": trainBotScheduleRows,
        "failure_reason": trainBotFailureReason,
    })),
        "satiksme_bot": module(satiksmeBotEnabled, satiksmeBotHealthy, json!({
        "satiksme_bot_enabled": satiksmeBotEnabled.to_string(),
        "satiksme_bot_pid": satiksmeBotPid,
        "tunnel_enabled": satiksmeBotTunnelEnabled.to_string(),
        "tunnel_supervisor_pid": blank(satiksmeBotTunnelSupervisorPid, "none"),
        "tunnel_pid": blank(satiksmeBotTunnelPid, "none"),
        "tunnel_public_base_url": blank(satiksmeBotTunnelPublicBaseUrl, "none"),
        "public_root_code": satiksmeBotPublicRootCode,
        "public_app_code": satiksmeBotPublicAppCode,
        "tunnel_probe_available": satiksmeBotTunnelProbeAvailable.to_string(),
        "tunnel_supervisor_healthy": satiksmeBotTunnelSupervisorHealthy.to_string(),
        "public_root_healthy": satiksmeBotPublicRootHealthy.to_string(),
        "public_app_healthy": satiksmeBotPublicAppHealthy.to_string(),
        "tunnel_healthy": satiksmeBotTunnelHealthy.to_string(),
        "heartbeat_age_sec": age_text(satiksmeBotHeartbeatAge),
        "failure_reason": if satiksmeBotEnabled { satiksmeBotFailureReason } else { "disabled" },
    })),
        "site_notifier": module(siteNotifierEnabled, siteNotifierHealthy, json!({
        "site_notifier_enabled": siteNotifierEnabled.to_string(),
        "site_notifier_pid": blank(siteNotifierPid, "none"),
        "heartbeat_age_sec": age_text(siteNotifierHeartbeatAge),
        "helper_healthy": siteNotifierHelperHealthy.to_string(),
        "failure_reason": siteNotifierEffectiveFailureReason,
    })),
        "subscription_bot": module(subscriptionBotEnabled, subscriptionBotHealthy, json!({
        "subscription_bot_enabled": subscriptionBotEnabled.to_string(),
        "subscription_bot_pid": blank(subscriptionBotPid, "none"),
        "heartbeat_age_sec": age_text(subscriptionBotHeartbeatAge),
        "failure_reason": subscriptionBotFailureReason,
    })),
        "ticket_screen": module(true, ticketScreenHealthy, json!({
        "ticket_screen_enabled": ticketScreenEnabled.to_string(),
        "deploy_required": "true",
        "ticket_screen_port": "9388",
        "listener": if ticketScreenHealthy { "1" } else { "0" },
    })),
        "ddns": module(ddnsRequired, ddnsHealthy, json!({
        "ddns_enabled": ddnsRequired.to_string(),
        "last_sync_fresh": ddnsHealthy.to_string(),
    })),
        "remote": module(remoteEnabled, remoteHealthy, json!({
        "public_base_url": blank(remotePublicBaseUrl, "none"),
        "https_port": config.i("remote.httpsPort")?.to_string(),
        "dot_port": config.i("remote.dotPort")?.to_string(),
        "doh_endpoint_mode": dohEndpointMode,
        "doh_tokenized_code": blank(tokenizedDohCode, "none"),
        "doh_bare_code": blank(bareDohCode, "none"),
        "public_root_code": remotePublicRootCode,
        "public_doh_tokenized_code": remotePublicTokenizedCode,
        "public_doh_bare_code": remotePublicBareCode,
        "public_identity_inject_code": remotePublicIdentityInjectCode,
        "doh_probe_mode": dohProbeMode,
        "health_enforced": remoteHealthEnforced.to_string(),
        "doh_probe_unavailable": dohProbeUnavailable.to_string(),
        "doh_contract": remoteDohContract.to_string(),
        "public_probe_available": remotePublicProbeAvailable.to_string(),
        "public_root_healthy": remotePublicRootHealthy.to_string(),
        "public_doh_contract": remotePublicDohContract.to_string(),
        "direct_public_probe_healthy": directPublicProbeHealthy.to_string(),
        "direct_public_dns_consistent": directPublicDnsConsistent.to_string(),
        "direct_public_transitioning": directPublicTransitioning.to_string(),
        "direct_public_transition_reason": directPublicTransitionReason,
        "direct_public_path_healthy": directPublicPathHealthy.to_string(),
        "ddns_published_ipv4": blank(ddnsPublishedIpv4, "none"),
        "current_public_ipv4_candidate": blank(managementPublicIpv4Candidate, "none"),
        "identity_frontend_required": identityFrontendRequired.to_string(),
        "identity_inject_code": blank(identityInjectCode, "none"),
        "identity_probe_unavailable": identityProbeUnavailable.to_string(),
        "identity_frontend_healthy": identityFrontendHealthy.to_string(),
        "public_identity_frontend_healthy": remotePublicIdentityFrontendHealthy.to_string(),
    })),
        "supervisor": module(true, supervisorLoopHealthy, json!({
        "root_granted": rootGranted.to_string(),
        "loop_heartbeat_age_sec": age_text(supervisorLoopHeartbeatAge),
        "loop_heartbeat_fresh_sec": supervisorLoopFreshSeconds.to_string(),
        "deploy_healthy": deployHealthy.to_string(),
    })),
    });
    let evidence = json!({
        "id_u": rootValue,
        "dns_port": config.i("dns.dnsPort")?.to_string(),
        "ssh_port": config.i("ssh.port")?.to_string(),
        "ssh_enabled": sshEnabled.to_string(),
        "vpn_enabled": vpnEnabled.to_string(),
        "vpn_interface_name": config.s("vpn.interfaceName")?,
        "vpn_healthy": vpnHealthy.to_string(),
        "vpn_enabled_effective": vpnEnabledEffective,
        "vpn_tailscaled_live": vpnTailscaledLive,
        "vpn_tailscaled_sock": vpnTailscaledSock,
        "vpn_tailnet_ipv4": blank(vpnTailnetIpv4, "none"),
        "vpn_guard_chain_ipv4": vpnGuardChainIpv4,
        "vpn_guard_chain_ipv6": vpnGuardChainIpv6,
        "management_enabled": managementEnabled.to_string(),
        "management_healthy": managementHealthy.to_string(),
        "management_path_healthy": managementPathHealthy.to_string(),
        "management_reason": managementReason,
        "management_auth_healthy": managementAuthHealthy.to_string(),
        "management_auth_required": managementRequireAuthConsistency.to_string(),
        "management_auth_gate_healthy": managementAuthGateHealthy.to_string(),
        "management_auth_consistent": managementAuthConsistent.to_string(),
        "management_auth_warning_reason": managementAuthWarningReason,
        "management_ssh_listener": managementSshListener,
        "management_ssh_auth_mode": managementSshAuthMode,
        "management_ssh_password_auth_requested": managementSshPasswordAuthRequested,
        "management_ssh_password_auth_ready": managementSshPasswordAuthReady,
        "management_ssh_key_auth_requested": managementSshKeyAuthRequested,
        "management_ssh_key_auth_ready": managementSshKeyAuthReady,
        "management_pm_path": blank(managementPmPath, "none"),
        "management_am_path": blank(managementAmPath, "none"),
        "management_logcat_path": blank(managementLogcatPath, "none"),
        "management_require_wireless_debug": managementRequireWirelessDebug.to_string(),
        "management_wireless_debug_enabled": managementWirelessDebugEnabled,
        "management_wireless_debug_tls_port": blank(managementWirelessDebugTlsPort, "none"),
        "wireless_debug_live": managementWirelessDebugLive,
        "wireless_debug_live_ports": blank(managementWirelessDebugLivePorts, "none"),
        "management_wireless_debug_healthy": managementWirelessDebugHealthy,
        "management_wireless_debug_reason": managementWirelessDebugReason,
        "network_wifi_enabled": managementWifiEnabled,
        "network_wifi_connected": managementWifiConnected,
        "network_wifi_ipv4": blank(managementWifiIpv4, "none"),
        "network_mobile_iface": blank(managementMobileIface, "none"),
        "network_mobile_ipv4": blank(managementMobileIpv4, "none"),
        "network_active_transport": managementActiveTransport,
        "network_public_ipv4_candidate": blank(managementPublicIpv4Candidate, "none"),
        "network_fingerprint": blank(managementNetworkFingerprint, "none"),
        "ddns_published_ipv4": blank(ddnsPublishedIpv4, "none"),
        "ddns_required": ddnsRequired.to_string(),
        "ddns_healthy": ddnsHealthy.to_string(),
        "supervisor_loop_heartbeat_age_sec": age_text(supervisorLoopHeartbeatAge),
        "supervisor_loop_heartbeat_fresh_sec": supervisorLoopFreshSeconds.to_string(),
        "supervisor_loop_healthy": supervisorLoopHealthy.to_string(),
        "ticket_screen_deploy_required": "true",
        "ticket_screen_healthy": ticketScreenHealthy.to_string(),
        "deploy_healthy": deployHealthy.to_string(),
        "https_port": config.i("remote.httpsPort")?.to_string(),
        "dot_port": config.i("remote.dotPort")?.to_string(),
        "remote_public_base_url": blank(remotePublicBaseUrl, "none"),
        "doh_endpoint_mode": dohEndpointMode,
        "doh_tokenized_code": blank(tokenizedDohCode, "none"),
        "doh_bare_code": blank(bareDohCode, "none"),
        "remote_public_root_code": remotePublicRootCode,
        "remote_public_doh_tokenized_code": remotePublicTokenizedCode,
        "remote_public_doh_bare_code": remotePublicBareCode,
        "remote_public_identity_inject_code": remotePublicIdentityInjectCode,
        "doh_probe_mode": dohProbeMode,
        "remote_health_enforced": remoteHealthEnforced.to_string(),
        "doh_probe_unavailable": dohProbeUnavailable.to_string(),
        "doh_contract": remoteDohContract.to_string(),
        "remote_public_probe_available": remotePublicProbeAvailable.to_string(),
        "remote_public_root_healthy": remotePublicRootHealthy.to_string(),
        "remote_public_doh_contract": remotePublicDohContract.to_string(),
        "direct_public_probe_healthy": directPublicProbeHealthy.to_string(),
        "direct_public_dns_consistent": directPublicDnsConsistent.to_string(),
        "direct_public_transitioning": directPublicTransitioning.to_string(),
        "direct_public_transition_reason": directPublicTransitionReason,
        "direct_public_path_healthy": directPublicPathHealthy.to_string(),
        "identity_frontend_required": identityFrontendRequired.to_string(),
        "identity_inject_code": blank(identityInjectCode, "none"),
        "identity_probe_unavailable": identityProbeUnavailable.to_string(),
        "identity_frontend_healthy": identityFrontendHealthy.to_string(),
        "remote_public_identity_frontend_healthy": remotePublicIdentityFrontendHealthy.to_string(),
        "listeners_ok": listenersOk.to_string(),
        "train_bot_pid": trainBotPid,
        "train_bot_enabled": trainBotEnabled.to_string(),
        "train_bot_tunnel_enabled": trainBotTunnelEnabled.to_string(),
        "train_bot_tunnel_supervisor_pid": blank(trainBotTunnelSupervisorPid, "none"),
        "train_bot_tunnel_pid": blank(trainBotTunnelPid, "none"),
        "train_bot_tunnel_public_base_url": blank(trainBotTunnelPublicBaseUrl, "none"),
        "train_bot_public_root_code": trainBotPublicRootCode,
        "train_bot_public_app_code": trainBotPublicAppCode,
        "train_bot_tunnel_probe_available": trainBotTunnelProbeAvailable.to_string(),
        "train_bot_tunnel_supervisor_healthy": trainBotTunnelSupervisorHealthy.to_string(),
        "train_bot_public_root_healthy": trainBotPublicRootHealthy.to_string(),
        "train_bot_public_app_healthy": trainBotPublicAppHealthy.to_string(),
        "train_bot_tunnel_healthy": trainBotTunnelHealthy.to_string(),
        "satiksme_bot_pid": satiksmeBotPid,
        "satiksme_bot_enabled": satiksmeBotEnabled.to_string(),
        "satiksme_bot_tunnel_enabled": satiksmeBotTunnelEnabled.to_string(),
        "satiksme_bot_tunnel_supervisor_pid": blank(satiksmeBotTunnelSupervisorPid, "none"),
        "satiksme_bot_tunnel_pid": blank(satiksmeBotTunnelPid, "none"),
        "satiksme_bot_tunnel_public_base_url": blank(satiksmeBotTunnelPublicBaseUrl, "none"),
        "satiksme_bot_public_root_code": satiksmeBotPublicRootCode,
        "satiksme_bot_public_app_code": satiksmeBotPublicAppCode,
        "satiksme_bot_tunnel_probe_available": satiksmeBotTunnelProbeAvailable.to_string(),
        "satiksme_bot_tunnel_supervisor_healthy": satiksmeBotTunnelSupervisorHealthy.to_string(),
        "satiksme_bot_public_root_healthy": satiksmeBotPublicRootHealthy.to_string(),
        "satiksme_bot_public_app_healthy": satiksmeBotPublicAppHealthy.to_string(),
        "satiksme_bot_tunnel_healthy": satiksmeBotTunnelHealthy.to_string(),
        "satiksme_bot_failure_reason": satiksmeBotFailureReason,
        "site_notifier_pid": blank(siteNotifierPid, "none"),
        "site_notifier_enabled": siteNotifierEnabled.to_string(),
        "site_notifier_helper_healthy": siteNotifierHelperHealthy.to_string(),
        "site_notifier_failure_reason": siteNotifierFailureReason,
        "subscription_bot_enabled": subscriptionBotEnabled.to_string(),
        "subscription_bot_pid": blank(subscriptionBotPid, "none"),
        "train_bot_heartbeat_age_sec": age_text(trainBotHeartbeatAge),
        "satiksme_bot_heartbeat_age_sec": age_text(satiksmeBotHeartbeatAge),
        "site_notifier_heartbeat_age_sec": age_text(siteNotifierHeartbeatAge),
        "subscription_bot_heartbeat_age_sec": age_text(subscriptionBotHeartbeatAge),
        "subscription_bot_failure_reason": subscriptionBotFailureReason,
        "train_bot_schedule_required": trainBotScheduleRequired.to_string(),
        "train_bot_schedule_fresh": trainBotScheduleFresh.to_string(),
        "train_bot_schedule_rows_present": trainBotScheduleRowsPresent.to_string(),
        "train_bot_schedule_probe_inconclusive": trainBotScheduleProbeInconclusive.to_string(),
        "train_bot_schedule_service_date": trainBotScheduleServiceDate,
        "train_bot_schedule_rows": trainBotScheduleRows,
    });
    Ok(json!({
        "generatedEpochSeconds": nowEpoch,
        "rootGranted": rootGranted,
        "dnsHealthy": dnsHealthy,
        "remoteHealthy": remoteHealthy,
        "managementHealthy": managementHealthy,
        "sshHealthy": sshHealthy,
        "vpnHealthy": vpnHealthy,
        "trainBotHealthy": trainBotHealthy,
        "satiksmeBotHealthy": satiksmeBotHealthy,
        "siteNotifierHealthy": siteNotifierHealthy,
        "subscriptionBotHealthy": subscriptionBotHealthy,
        "ddnsHealthy": ddnsHealthy,
        "supervisorLoopHealthy": supervisorLoopHealthy,
        "managementAuthHealthy": managementAuthHealthy,
        "deployHealthy": deployHealthy,
        "supervisorHealthy": supervisorHealthy,
        "moduleHealth": moduleHealth,
        "evidence": evidence,
    }))
}
