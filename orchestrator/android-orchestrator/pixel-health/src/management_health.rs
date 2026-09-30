//! Read-only management observations and readiness policy; platform tools supply facts.
use crate::runtime_cleanup::{command_code, install_signal_handlers, interrupted};
use std::{
    collections::HashSet,
    env,
    ffi::OsString,
    fs,
    io::{self, Write},
    os::unix::{ffi::OsStringExt, fs::PermissionsExt},
    path::{Path, PathBuf},
};

fn command(values: &[&str], error: bool) -> (i32, Vec<u8>) {
    command_code(
        &values.iter().map(OsString::from).collect::<Vec<_>>(),
        None,
        true,
        error,
    )
}
fn text(values: &[&str]) -> String {
    String::from_utf8_lossy(&command(values, false).1)
        .trim_end_matches('\n')
        .into()
}
fn variable(key: &str, default: &str) -> String {
    env::var(key)
        .ok()
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| default.into())
}
fn set(key: &str, value: &str) {
    // This standalone executable has no concurrent environment readers at setup.
    unsafe { env::set_var(key, value) };
}
fn executable(path: &Path) -> bool {
    fs::metadata(path).is_ok_and(|meta| meta.is_file() && meta.permissions().mode() & 0o111 != 0)
}
fn command_path(name: &str) -> String {
    text(&[
        "sh",
        "-c",
        "command -v \"$1\" 2>/dev/null || true",
        "management-command",
        name,
    ])
}
fn readable(path: &str) -> bool {
    fs::File::open(path).is_ok()
}
fn first_line(value: &str) -> String {
    value.replace('\r', "").lines().next().unwrap_or("").into()
}
fn first_nonempty(path: &str) -> String {
    fs::read_to_string(path)
        .unwrap_or_default()
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .unwrap_or("")
        .into()
}
fn yes(value: bool) -> &'static str {
    if value { "1" } else { "0" }
}
fn none(value: &str) -> &str {
    if value.is_empty() { "none" } else { value }
}
fn shell_bytes(bytes: &[u8]) -> &[u8] {
    let end = bytes
        .iter()
        .rposition(|byte| *byte != b'\n')
        .map_or(0, |index| index + 1);
    &bytes[..end]
}

fn configure(report: bool, deep: bool) -> Result<(), i32> {
    for (key, default) in [
        (
            "STACK_BIN_DIR",
            variable("PIXEL_STACK_BIN_DIR", "/data/local/pixel-stack/bin"),
        ),
        (
            "SSH_ROOT",
            variable("PIXEL_SSH_ROOT", "/data/local/pixel-stack/ssh"),
        ),
        (
            "SSH_LEGACY_ROOT",
            variable("PIXEL_SSH_LEGACY_ROOT", "/data/adb/pixel-stack/ssh"),
        ),
        (
            "VPN_ROOT",
            variable("PIXEL_VPN_ROOT", "/data/local/pixel-stack/vpn"),
        ),
        (
            "DDNS_CONF_FILE",
            variable(
                "PIXEL_DDNS_CONF_FILE",
                "/data/local/pixel-stack/conf/ddns.env",
            ),
        ),
        (
            "SYSTEM_PASSWD_FILE",
            variable("PIXEL_SSH_SYSTEM_PASSWD_FILE", "/system/etc/passwd"),
        ),
        (
            "PASSWORD_HASH_SOURCE_FILE",
            variable(
                "PIXEL_SSH_PASSWORD_HASH_SOURCE_FILE",
                "/data/local/pixel-stack/conf/ssh/root_password.hash",
            ),
        ),
        (
            "RUNTIME_AUTHORIZED_KEYS_FILE",
            variable(
                "PIXEL_SSH_RUNTIME_AUTHORIZED_KEYS_FILE",
                "/debug_ramdisk/pixel-ssh-auth/authorized_keys",
            ),
        ),
        (
            "DDNS_LAST_IPV4_FILE",
            variable(
                "PIXEL_DDNS_LAST_IPV4_FILE",
                "/data/local/pixel-stack/run/ddns-last-ipv4",
            ),
        ),
        (
            "PIXEL_STACK_CURL_ROOTFS",
            variable(
                "PIXEL_STACK_CURL_ROOTFS",
                "/data/local/pixel-stack/chroots/adguardhome",
            ),
        ),
        ("REPORT_MODE", yes(report).into()),
        ("DEEP_MODE", yes(deep).into()),
    ] {
        set(key, &default);
    }
    for (key, base, suffix) in [
        ("VPN_HEALTH_BIN", "STACK_BIN_DIR", "pixel-vpn-health.sh"),
        ("VPN_CONF_FILE", "VPN_ROOT", "conf/tailscale.env"),
        ("SSH_CONF_FILE", "SSH_ROOT", "conf/dropbear.env"),
        ("PASSWD_FILE", "SSH_ROOT", "etc/passwd"),
        ("LEGACY_PASSWD_FILE", "SSH_LEGACY_ROOT", "etc/passwd"),
        (
            "AUTHORIZED_KEYS_FILE",
            "SSH_ROOT",
            "home/root/.ssh/authorized_keys",
        ),
        (
            "WIRELESS_DEBUG_TLS_PORT_FILE",
            "VPN_ROOT",
            "run/wireless-debug-tls-port",
        ),
    ] {
        set(key, &format!("{}/{suffix}", variable(base, "")));
    }
    if !readable(&variable("VPN_CONF_FILE", "")) {
        set(
            "VPN_CONF_FILE",
            "/data/local/pixel-stack/conf/vpn/tailscale.env",
        );
    }
    // Configuration already uses POSIX syntax. Keep that one platform adapter;
    // its exported values stay in this private pipe, never a report or temp file.
    // The delimiter also preserves any configuration stdout before the environment.
    const DELIMITER: &[u8] = b"\0pixel-management-env\0";
    let (code, bytes) = command(
        &[
            "sh",
            "-c",
            "set -eu; set -a; if [ -r \"$SSH_CONF_FILE\" ]; then . \"$SSH_CONF_FILE\"; fi; if [ -r \"$VPN_CONF_FILE\" ]; then . \"$VPN_CONF_FILE\"; fi; if [ -r \"$DDNS_CONF_FILE\" ]; then . \"$DDNS_CONF_FILE\"; fi; printf '\\000pixel-management-env\\000'; env -0",
        ],
        true,
    );
    let split = bytes
        .windows(DELIMITER.len())
        .rposition(|part| part == DELIMITER);
    let prefix = split.unwrap_or(bytes.len());
    let _ = io::stdout().write_all(&bytes[..prefix]);
    if code != 0 {
        return Err(code);
    }
    let Some(split) = split else {
        return Err(1);
    };
    for row in bytes[split + DELIMITER.len()..].split(|byte| *byte == 0) {
        if let Some(index) = row.iter().position(|byte| *byte == b'=') {
            unsafe {
                env::set_var(
                    OsString::from_vec(row[..index].to_vec()),
                    OsString::from_vec(row[index + 1..].to_vec()),
                );
            }
        }
    }
    Ok(())
}

fn ipv4_for_interface(iface: &str) -> String {
    if iface.is_empty() {
        return String::new();
    }
    for line in text(&["ip", "-4", "addr", "show", "dev", iface])
        .replace('\r', "")
        .lines()
    {
        let fields = line.split_whitespace().collect::<Vec<_>>();
        if line.contains("inet ") && fields.len() > 1 {
            return fields[1].split('/').next().unwrap_or("").into();
        }
    }
    String::new()
}
fn first_interface(prefixes: &[&str]) -> String {
    for line in text(&["ip", "-o", "-4", "addr", "show"])
        .replace('\r', "")
        .lines()
    {
        if let Some(iface) = line.split_whitespace().nth(1)
            && prefixes.iter().any(|prefix| iface.starts_with(prefix))
        {
            return iface.into();
        }
    }
    String::new()
}
fn transport(iface: &str) -> &'static str {
    if ["wlan", "wifi"].iter().any(|p| iface.starts_with(p)) {
        "wifi"
    } else if ["rmnet", "ccmni", "pdp", "wwan"]
        .iter()
        .any(|p| iface.starts_with(p))
    {
        "cellular"
    } else if ["tailscale", "tun"].iter().any(|p| iface.starts_with(p)) {
        "vpn"
    } else if iface.is_empty() {
        "unknown"
    } else {
        "other"
    }
}
fn adbd_ports() -> Vec<String> {
    let mut ports = Vec::new();
    let mut seen = HashSet::new();
    for line in text(&["ss", "-ltnp"])
        .lines()
        .filter(|line| line.contains("adbd"))
    {
        let addr = line
            .split_whitespace()
            .nth(3)
            .unwrap_or("")
            .replace(['[', ']'], "");
        let numeric = |port: &&str| !port.is_empty() && port.bytes().all(|b| b.is_ascii_digit());
        let port = addr
            .rsplit_once(':')
            .map(|(_, p)| p)
            .filter(numeric)
            .or_else(|| addr.rsplit_once('.').map(|(_, p)| p).filter(numeric))
            .unwrap_or("");
        if !port.is_empty() && seen.insert(port.to_owned()) {
            ports.push(port.into());
        }
    }
    ports
}
fn ssh_present(port: &str) -> bool {
    text(&["ss", "-ltnp"]).lines().any(|line| {
        let fields = line.split_whitespace().collect::<Vec<_>>();
        if fields.first() != Some(&"LISTEN") || !line.contains("dropbear") || fields.len() < 4 {
            return false;
        }
        let addr = fields[3].replace(['[', ']'], "");
        (addr.ends_with(&format!(":{port}")) || addr.ends_with(&format!(".{port}")))
            && !addr.starts_with("127.0.0.1:")
            && !addr.starts_with("::1:")
    })
}
fn ssh_banner(port: &str, hosts: &[&str]) -> bool {
    if command_path("nc").is_empty() {
        return true;
    }
    let timeout = !command_path("timeout").is_empty();
    for host in hosts.iter().filter(|host| !host.is_empty()) {
        let mut values = Vec::new();
        if timeout {
            values.extend(["timeout", "3"]);
        }
        values.extend([
            "sh",
            "-c",
            "nc -w 1 -W 1 \"$1\" \"$2\" </dev/null 2>/dev/null",
            "management-banner",
            host,
            port,
        ]);
        if first_line(&text(&values)).starts_with("SSH-2.0-") {
            return true;
        }
    }
    false
}
fn public_ipv4(cached: &str) -> String {
    let curl = command_path("curl");
    let root = variable("PIXEL_STACK_CURL_ROOTFS", "");
    let chroot = curl.is_empty()
        && executable(&PathBuf::from(&root).join("usr/bin/curl"))
        && executable(&PathBuf::from(&root).join("usr/bin/env"))
        && command(
            &[
                "chroot",
                &root,
                "/usr/bin/env",
                "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/usr/bin/curl",
                "-V",
            ],
            false,
        )
        .0 == 0;
    if curl.is_empty() && !chroot {
        return cached.into();
    }
    let urls = variable(
        "PUBLIC_IP_DISCOVERY_V4_URLS",
        "https://api.ipify.org?format=json,https://checkip.amazonaws.com,https://ipv4.icanhazip.com",
    );
    for url in urls.split(',').map(str::trim).filter(|url| !url.is_empty()) {
        let mut values = if chroot {
            vec![
                "chroot",
                &root,
                "/usr/bin/env",
                "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/usr/bin/curl",
            ]
        } else {
            vec![curl.as_str()]
        };
        values.extend(["-fsS", "--connect-timeout", "2", "--max-time", "4", url]);
        let body = text(&values);
        if body.is_empty() {
            continue;
        }
        let compact = body.replace(['\r', '\n'], "");
        let json_ip = compact
            .match_indices("\"ip\"")
            .filter_map(|(index, key)| {
                let value = compact[index + key.len()..]
                    .trim_start()
                    .strip_prefix(':')?
                    .trim_start()
                    .strip_prefix('"')?;
                Some(value.split_once('"')?.0)
            })
            .last();
        let ip = json_ip
            .filter(|ip| !ip.is_empty())
            .map(str::to_owned)
            .unwrap_or_else(|| first_line(&body).trim().into());
        let parts = ip.split('.').collect::<Vec<_>>();
        // Preserve the existing syntax-only candidate check, including cached fallback.
        if parts.len() == 4
            && parts.iter().all(|part| {
                (1..=3).contains(&part.len()) && part.bytes().all(|byte| byte.is_ascii_digit())
            })
        {
            return ip;
        }
    }
    cached.into()
}
fn root_hash(path: &str) -> String {
    fs::read_to_string(path)
        .unwrap_or_default()
        .split('\n')
        .find_map(|line| {
            let mut fields = line.split(':');
            (fields.next() == Some("root")).then(|| fields.next().unwrap_or("").to_owned())
        })
        .unwrap_or_default()
}
fn source_hash(path: &str) -> String {
    let line = first_nonempty(path);
    if line.starts_with("root:") {
        line.split(':').nth(1).unwrap_or("").into()
    } else if line.starts_with("$6$") {
        line
    } else {
        String::new()
    }
}

pub(super) fn main() -> i32 {
    let mut report = variable("PIXEL_MANAGEMENT_HEALTH_REPORT", "0") == "1";
    let mut deep = variable("PIXEL_MANAGEMENT_HEALTH_DEEP", "0") == "1";
    for arg in env::args().skip(2) {
        match arg.as_str() {
            "--report" => report = true,
            "--deep" | "--full" => deep = true,
            _ => {
                eprintln!("unsupported management health argument: {arg}");
                return 2;
            }
        }
    }
    install_signal_handlers();
    if let Err(code) = configure(report, deep) {
        return code;
    }
    report = variable("REPORT_MODE", "0") == "1";
    deep = variable("DEEP_MODE", "0") == "1";
    let port = variable("SSH_PORT", "2222");
    let password_requested = variable("SSH_PASSWORD_AUTH", "1") == "1";
    let key_requested = variable("SSH_ALLOW_KEY_AUTH", "1") == "1";
    let require_wireless = variable("MANAGEMENT_REQUIRE_WIRELESS_DEBUG", "0");
    let uid = text(&["id", "-u"]);
    let pm = command_path("pm");
    let am = command_path("am");
    let logcat = command_path("logcat");
    let adb_enabled = first_line(&text(&["settings", "get", "global", "adb_enabled"]));
    let adb_wifi_enabled = first_line(&text(&["settings", "get", "global", "adb_wifi_enabled"]));
    let wifi_setting = first_line(&text(&["settings", "get", "global", "wifi_on"]));
    let tls_enabled = first_line(&text(&["getprop", "persist.adb.tls_server.enable"]));
    let mut tls_port_prop = first_line(&text(&["getprop", "service.adb.tls.port"]));
    if tls_port_prop == "0" {
        tls_port_prop.clear();
    }
    let mut vpn_enabled = "0".to_owned();
    let mut tailscaled_live = "0".to_owned();
    let mut tailscaled_sock = "0".to_owned();
    let mut tailnet = String::new();
    let mut guard_v4 = "0".to_owned();
    let mut guard_v6 = "0".to_owned();
    let mut vpn_health = false;
    let vpn_bin = variable("VPN_HEALTH_BIN", "");
    if executable(Path::new(&vpn_bin)) {
        set("PIXEL_VPN_HEALTH_REPORT", "1");
        let (code, bytes) = command(&[&vpn_bin, "--report"], false);
        vpn_health = code == 0;
        // POSIX read ignores an unterminated last row and retains carriage returns.
        for line in String::from_utf8_lossy(&bytes)
            .split_inclusive('\n')
            .filter_map(|line| line.strip_suffix('\n'))
        {
            if let Some((key, value)) = line.split_once('=') {
                match key {
                    "vpn_enabled" => vpn_enabled = value.into(),
                    "tailscaled_live" => tailscaled_live = value.into(),
                    "tailscaled_sock" => tailscaled_sock = value.into(),
                    "tailnet_ipv4" => tailnet = value.into(),
                    "guard_chain_ipv4" => guard_v4 = value.into(),
                    "guard_chain_ipv6" => guard_v6 = value.into(),
                    _ => {}
                }
            }
        }
    }
    let route = text(&["ip", "route", "get", "1.1.1.1"]).replace('\r', "");
    let fields = route.split_whitespace().collect::<Vec<_>>();
    let route_iface = fields
        .windows(2)
        .find_map(|pair| (pair[0] == "dev").then_some(pair[1]))
        .unwrap_or("");
    let active_transport = transport(route_iface);
    let wifi_iface = if transport(route_iface) == "wifi" {
        route_iface.into()
    } else {
        first_interface(&["wlan", "wifi"])
    };
    let mobile_iface = if transport(route_iface) == "cellular" {
        route_iface.into()
    } else {
        first_interface(&["rmnet", "ccmni", "pdp", "wwan"])
    };
    let wifi_ip = ipv4_for_interface(&wifi_iface);
    let mobile_ip = ipv4_for_interface(&mobile_iface);
    let wifi_enabled = wifi_setting == "1" || !wifi_iface.is_empty();
    let wifi_connected = !wifi_ip.is_empty() || active_transport == "wifi";
    let ports = adbd_ports();
    let live_ports = ports.join(",");
    let wireless_live = !live_ports.is_empty();
    let mut tls_port = tls_port_prop.clone();
    if tls_port.is_empty() {
        tls_port = adbd_ports()
            .into_iter()
            .find(|port| port != "5555")
            .unwrap_or_default();
    }
    if tls_port.is_empty() {
        tls_port = ports.first().cloned().unwrap_or_default();
    }
    let published_ip = first_nonempty(&variable("DDNS_LAST_IPV4_FILE", ""));
    let public_ip = if deep {
        public_ipv4(&published_ip)
    } else {
        published_ip.clone()
    };
    let fingerprint = format!(
        "transport={active_transport};wifi_enabled={};wifi_connected={};wifi_ipv4={};mobile_iface={};mobile_ipv4={};adbd_ports={};public_ipv4={}",
        yes(wifi_enabled),
        yes(wifi_connected),
        none(&wifi_ip),
        none(&mobile_iface),
        none(&mobile_ip),
        none(&live_ports),
        none(&public_ip)
    );
    let wireless_enabled =
        adb_enabled == "1" && (adb_wifi_enabled == "1" || tls_enabled == "1" || wireless_live);
    let wireless_reason = if wireless_live {
        "ok"
    } else if !wireless_enabled {
        if adb_enabled != "1" {
            "adb_disabled"
        } else {
            "wireless_debug_disabled"
        }
    } else {
        "listener_missing"
    };
    let ssh_listener = ssh_present(&port)
        && (!deep || ssh_banner(&port, &[&tailnet, &wifi_ip, &mobile_ip, "127.0.0.1"]));
    let auth_mode = match (password_requested, key_requested) {
        (true, true) => "key_password",
        (true, false) => "password_only",
        (false, true) => "key_only",
        (false, false) => "disabled",
    };
    let source_hash = source_hash(&variable("PASSWORD_HASH_SOURCE_FILE", ""));
    let source_ready = source_hash.starts_with("$6$");
    let local_ready = source_ready && root_hash(&variable("PASSWD_FILE", "")) == source_hash;
    let legacy_path = variable("LEGACY_PASSWD_FILE", "");
    let legacy_present = Path::new(&legacy_path).exists();
    let legacy_ready = !legacy_present || (source_ready && root_hash(&legacy_path) == source_hash);
    let system_ready = !ssh_listener
        || (source_ready && root_hash(&variable("SYSTEM_PASSWD_FILE", "")) == source_hash);
    let password_mismatch = source_ready && (!local_ready || !legacy_ready || !system_ready);
    let password_ready = source_ready && local_ready && legacy_ready && system_ready;
    let key_path = variable("AUTHORIZED_KEYS_FILE", "");
    let key_source = fs::metadata(&key_path).is_ok_and(|meta| meta.len() > 0);
    let key_runtime = !ssh_listener
        || (key_source
            && match (
                fs::read(&key_path),
                fs::read(variable("RUNTIME_AUTHORIZED_KEYS_FILE", "")),
            ) {
                (Ok(a), Ok(b)) => {
                    if command_path("cmp").is_empty() {
                        shell_bytes(&a) == shell_bytes(&b)
                    } else {
                        a == b
                    }
                }
                _ => false,
            });
    let key_mismatch = key_source && !key_runtime;
    let key_ready = key_source && key_runtime;
    let mut warnings = Vec::new();
    if password_requested && password_mismatch {
        warnings.push("password_auth_runtime_mismatch");
    }
    if key_requested && key_mismatch {
        warnings.push("key_auth_runtime_mismatch");
    }
    let enabled = vpn_enabled == "1";
    let reason = if !enabled {
        "disabled"
    } else if uid != "0" {
        "root_unavailable"
    } else if pm.is_empty() || am.is_empty() || logcat.is_empty() {
        "android_command_missing"
    } else if !vpn_health {
        "vpn_unhealthy"
    } else if tailnet.is_empty() {
        "tailnet_ip_missing"
    } else if require_wireless == "1" && !wireless_live {
        wireless_reason
    } else if !ssh_listener {
        "ssh_listener_missing"
    } else if auth_mode == "disabled" {
        "ssh_auth_unconfigured"
    } else if password_requested && !key_requested && !password_ready && !password_mismatch {
        "password_auth_not_ready"
    } else if !password_requested && key_requested && !key_ready && !key_mismatch {
        "key_auth_not_ready"
    } else if !password_ready && !key_ready && !password_mismatch && !key_mismatch {
        "ssh_auth_not_ready"
    } else {
        "ok"
    };
    let healthy = !enabled || reason == "ok";
    let warning = if !enabled {
        "disabled".into()
    } else if warnings.is_empty() {
        "ok".into()
    } else {
        warnings.join(",")
    };
    if interrupted() != 0 {
        return 128 + interrupted();
    }
    if report {
        for (key, value) in [
            ("remote_uid", uid.as_str()),
            ("pm_path", &pm),
            ("am_path", &am),
            ("logcat_path", &logcat),
            ("vpn_enabled", &vpn_enabled),
            ("vpn_health", yes(vpn_health)),
            ("tailscaled_live", &tailscaled_live),
            ("tailscaled_sock", &tailscaled_sock),
            ("tailnet_ipv4", &tailnet),
            ("guard_chain_ipv4", &guard_v4),
            ("guard_chain_ipv6", &guard_v6),
            ("wireless_debug_enabled", yes(wireless_enabled)),
            ("wireless_debug_tls_enabled_prop", &tls_enabled),
            ("wireless_debug_tls_port_prop", &tls_port_prop),
            ("wireless_debug_tls_port", &tls_port),
            ("wireless_debug_live", yes(wireless_live)),
            ("wireless_debug_live_ports", &live_ports),
            ("wireless_debug_healthy", yes(wireless_live)),
            ("wireless_debug_reason", wireless_reason),
            ("wifi_enabled", yes(wifi_enabled)),
            ("wifi_connected", yes(wifi_connected)),
            ("wifi_ipv4", &wifi_ip),
            ("mobile_iface", &mobile_iface),
            ("mobile_ipv4", &mobile_ip),
            ("active_transport", active_transport),
            ("public_ipv4_candidate", &public_ip),
            ("ddns_published_ipv4", &published_ip),
            ("network_fingerprint", &fingerprint),
            ("ssh_port", &port),
            ("ssh_listener", yes(ssh_listener)),
            ("ssh_auth_mode", auth_mode),
            ("ssh_password_auth_requested", yes(password_requested)),
            ("ssh_password_auth_ready", yes(password_ready)),
            ("ssh_password_hash_source_ready", yes(source_ready)),
            ("ssh_password_runtime_local_ready", yes(local_ready)),
            ("ssh_password_runtime_legacy_present", yes(legacy_present)),
            ("ssh_password_runtime_legacy_ready", yes(legacy_ready)),
            ("ssh_password_runtime_system_ready", yes(system_ready)),
            ("ssh_password_runtime_mismatch", yes(password_mismatch)),
            ("ssh_key_auth_requested", yes(key_requested)),
            ("ssh_key_auth_ready", yes(key_ready)),
            ("ssh_key_source_ready", yes(key_source)),
            ("ssh_key_runtime_ready", yes(key_runtime)),
            ("ssh_key_runtime_mismatch", yes(key_mismatch)),
            (
                "management_auth_consistent",
                yes(!enabled || warnings.is_empty()),
            ),
            ("management_auth_warning_reason", &warning),
            ("management_require_wireless_debug", &require_wireless),
            ("management_enabled", yes(enabled)),
            ("management_healthy", yes(healthy)),
            ("management_reason", reason),
            (
                "management_health_mode",
                if deep { "deep" } else { "local" },
            ),
        ] {
            println!("{key}={value}");
        }
    }
    i32::from(!healthy)
}
