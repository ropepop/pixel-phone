//! Local, read-only Ticket monitor. Never linked into the Android/JNI library.
use chrono::{DateTime, SecondsFormat, Utc};
use serde::de::{self, Deserialize, Deserializer, MapAccess, SeqAccess, Visitor};
use serde_json::{Map, Value, json};
use std::{
    collections::{HashMap, HashSet},
    env, fmt, fs,
    io::{self, Read, Write},
    os::unix::{fs::PermissionsExt, process::CommandExt},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant, SystemTime},
};

const VERSION: &str = "6";
const CAPTURE: usize = 262_144;
const WARM_MAX: i64 = 1_800_000;
const TERMINAL: &str = "succeeded failed expired canceled cancelled completed";
const COMMAND_STATES: &str =
    "succeeded failed expired canceled cancelled completed pending running warming closed";
const PHONE_STATES: &str = "idle starting streaming live stopped control_active control_transition control_exit recovering unavailable capture_blocked waiting_keyframe stale_recovering preparing_phone browser_decode_recovering timing_uncertain unknown client_disconnected";
const RELAY_VERDICTS: &str = "idle live preparing_phone waiting_keyframe stale_recovering browser_decode_recovering timing_uncertain unavailable unknown";
const SESSION_STATES: &str = "idle starting live stopped control_active control_transition control_exit soft_recovery recovering needs_attention unavailable client_disconnected";
const STREAM_VERDICTS: &str = "idle live starting waiting_keyframe stale_recovering recovering capture_blocked unavailable unknown";
const VISIBILITY: &str =
    "visible idle unknown not_checked not_run unavailable blocked hidden black error";
const TICKET_STATES: &str = "idle starting live stopped control_active control_transition control_exit soft_recovery needs_attention unavailable client_disconnected";
const VIVI_STATES: &str = "TICKET_DETAIL TICKET_LIST GENERATED_CONTROL_CODE CONTROL_CODE_POPUP LOGIN NO_TICKETS ROUTE_HOME CART UNKNOWN_VIVI OTHER_VIVI";
const RECOVERY_STAGES: &str =
    "idle demand_idle running healthy recovering blocked failed stopped none";
const RECOVERY_RESULTS: &str = "none pending succeeded failed recovered skipped not_needed";
const RECOVERY_FAILURES: &str = "timeout phone_not_ready capture_unavailable capture_blocked stream_start_failed ticket_not_ready vivi_attention_required foreground_mismatch unknown";
const LIFECYCLE_COMMAND: &str = "ps -A -o PID,PPID,ELAPSED,STAT,NAME,ARGS | awk 'NR == 1 || ($5 == \"sh\" && $6 == \"sh\" && ($7 == \"/data/local/pixel-stack/bin/pixel-ticket-start.sh\" || $7 == \"/data/local/pixel-stack/bin/pixel-ticket-stop.sh\")) || ($6 == \"/data/local/pixel-stack/bin/pixel-runtime-cleanup\" && ($7 == \"pixel-ticket-start.sh\" || $7 == \"pixel-ticket-stop.sh\")) || ($5 == \"tr\" && $6 == \"tr\" && ($7 == \"\\\\000\" || $7 == \"\\\\0\" || $7 == \"\\\\x00\"))'";
static INTERRUPTED: AtomicBool = AtomicBool::new(false);
unsafe extern "C" {
    fn kill(pid: i32, signal: i32) -> i32;
    fn signal(sig: i32, handler: usize) -> usize;
}
extern "C" fn interrupt(_: i32) {
    INTERRUPTED.store(true, Ordering::Relaxed);
}

fn contains(words: &str, value: &str) -> bool {
    words.split_whitespace().any(|w| w == value)
}
fn s(v: &Value) -> &str {
    v.as_str().unwrap_or("")
}
fn truth(v: &Value) -> bool {
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64() != Some(0.0),
        Value::String(s) => !s.is_empty(),
        Value::Array(a) => !a.is_empty(),
        Value::Object(o) => !o.is_empty(),
    }
}
fn yes(v: &Value) -> bool {
    v.as_bool() == Some(true)
}
fn int(v: &Value, low: i64, high: i64) -> Option<i64> {
    v.as_i64().filter(|n| (low..=high).contains(n))
}
fn as_int(v: &Value, default: i64) -> i64 {
    v.as_i64()
        .or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))
        .unwrap_or(default)
}
fn enum_value(v: &Value, allowed: &str, fallback: &str, lower: bool) -> Value {
    if v.is_null() {
        return Value::Null;
    }
    let Some(text) = v.as_str() else {
        return json!(fallback);
    };
    let text = text.trim();
    if text.is_empty() {
        return Value::Null;
    }
    let text = if lower {
        text.to_lowercase()
    } else {
        text.to_string()
    };
    json!(if contains(allowed, &text) {
        &text
    } else {
        fallback
    })
}
fn raw_enum(v: &Value, allowed: &str, lower: bool) -> bool {
    v.as_str().is_some_and(|text| {
        contains(
            allowed,
            &if lower {
                text.trim().to_lowercase()
            } else {
                text.trim().into()
            },
        )
    })
}
fn now() -> DateTime<Utc> {
    SystemTime::now().into()
}
fn utc_now() -> String {
    now().to_rfc3339_opts(SecondsFormat::Secs, true)
}
fn observed_now() -> DateTime<Utc> {
    // Python's observations have microsecond precision; keep the same age rounding.
    let n = now();
    DateTime::from_timestamp_micros(n.timestamp_micros()).unwrap()
}

// Configuration is the trust boundary. serde_json's Value normally accepts
// duplicate object keys; this visitor rejects them at every nesting level.
struct Unique(Value);
impl<'de> Deserialize<'de> for Unique {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct JsonVisitor;
        impl<'de> Visitor<'de> for JsonVisitor {
            type Value = Unique;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("JSON")
            }
            fn visit_bool<E: de::Error>(self, v: bool) -> Result<Unique, E> {
                Ok(Unique(json!(v)))
            }
            fn visit_i64<E: de::Error>(self, v: i64) -> Result<Unique, E> {
                Ok(Unique(json!(v)))
            }
            fn visit_u64<E: de::Error>(self, v: u64) -> Result<Unique, E> {
                Ok(Unique(json!(v)))
            }
            fn visit_f64<E: de::Error>(self, v: f64) -> Result<Unique, E> {
                Ok(Unique(json!(v)))
            }
            fn visit_str<E: de::Error>(self, v: &str) -> Result<Unique, E> {
                Ok(Unique(json!(v)))
            }
            fn visit_string<E: de::Error>(self, v: String) -> Result<Unique, E> {
                Ok(Unique(json!(v)))
            }
            fn visit_unit<E: de::Error>(self) -> Result<Unique, E> {
                Ok(Unique(Value::Null))
            }
            fn visit_none<E: de::Error>(self) -> Result<Unique, E> {
                self.visit_unit()
            }
            fn visit_seq<A: SeqAccess<'de>>(self, mut a: A) -> Result<Unique, A::Error> {
                let mut values = Vec::new();
                while let Some(Unique(v)) = a.next_element()? {
                    values.push(v);
                }
                Ok(Unique(Value::Array(values)))
            }
            fn visit_map<A: MapAccess<'de>>(self, mut a: A) -> Result<Unique, A::Error> {
                let mut map = Map::new();
                while let Some((key, Unique(value))) = a.next_entry::<String, Unique>()? {
                    if map.insert(key, value).is_some() {
                        return Err(de::Error::custom("duplicate JSON key"));
                    }
                }
                Ok(Unique(Value::Object(map)))
            }
        }
        d.deserialize_any(JsonVisitor)
    }
}
fn load_json(path: &Path) -> Result<Value, String> {
    let text = fs::read_to_string(path).map_err(|e| e.to_string())?;
    let Unique(value) = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    if !value.is_object() {
        return Err("must contain a JSON object".into());
    }
    Ok(value)
}
fn exact<'a>(v: &'a Value, name: &str, keys: &str) -> Result<&'a Map<String, Value>, String> {
    let map = v
        .as_object()
        .ok_or_else(|| format!("{name} must be an object"))?;
    let required: HashSet<_> = keys.split_whitespace().collect();
    if map.len() != required.len() || map.keys().any(|k| !required.contains(k.as_str())) {
        return Err(format!("{name} has missing or extra fields"));
    }
    Ok(map)
}
fn text<'a>(
    v: &'a Value,
    name: &str,
    max: usize,
    empty: bool,
    extra: &str,
) -> Result<&'a str, String> {
    let t = v
        .as_str()
        .ok_or_else(|| format!("{name} must be a string"))?;
    if (!empty && t.is_empty()) || t.chars().count() > max || t.chars().any(|c| (c as u32) < 32) {
        return Err(format!("{name} is not a valid string"));
    }
    if !extra.is_empty()
        && !t
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || extra.contains(c))
    {
        return Err(format!("{name} contains unsupported characters"));
    }
    Ok(t)
}
fn number(v: &Value, name: &str, low: f64, high: f64) -> Result<f64, String> {
    v.as_f64()
        .filter(|n| n.is_finite() && (low..=high).contains(n))
        .ok_or_else(|| format!("{name} must be numeric in {low}..{high}"))
}
fn url(v: &Value, name: &str, https: bool) -> Result<(), String> {
    let raw = text(v, name, 2048, false, "")?;
    let no_fragment = match raw.split_once('#') {
        Some((before, "")) => before,
        Some(_) => return Err(format!("{name} is not an approved credential-free URL")),
        None => raw,
    };
    let no_query = match no_fragment.split_once('?') {
        Some((before, "")) => before,
        Some(_) => return Err(format!("{name} is not an approved credential-free URL")),
        None => no_fragment,
    };
    let scheme = if https { "https" } else { "http" };
    let tail = no_query
        .split_once("://")
        .filter(|(s, _)| s.eq_ignore_ascii_case(scheme))
        .map(|(_, tail)| tail)
        .ok_or_else(|| format!("{name} is not an approved URL"))?;
    let (authority, path) = tail
        .split_once('/')
        .map(|(a, p)| (a, format!("/{p}")))
        .unwrap_or((tail, String::new()));
    let (host, port) = authority
        .split_once(':')
        .map(|(h, p)| (h, Some(p)))
        .unwrap_or((authority, None));
    let port_ok = match port {
        Some("") => https,
        Some(p) => p.bytes().all(|b| b.is_ascii_digit()) && p.parse::<u16>().is_ok_and(|n| n > 0),
        None => https,
    };
    if host.is_empty()
        || !host
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
        || !port_ok
        || authority.contains('@')
        || (!https
            && (host != "127.0.0.1"
                || !path.starts_with('/')
                || !path
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || "_./-".contains(c))))
    {
        return Err(format!("{name} is not an approved credential-free URL"));
    }
    Ok(())
}
fn validate_config(c: &Value) -> Result<(), String> {
    exact(
        c,
        "config",
        "version enabled paused_reason repair_mode public ssh containers container_endpoints spacetime pixel thresholds reporting standby_devices",
    )?;
    if c["version"].as_i64() != Some(1) || !c["enabled"].is_boolean() {
        return Err("config version or enabled flag is invalid".into());
    }
    text(&c["paused_reason"], "paused_reason", 256, true, "")?;
    if c["repair_mode"] != "disabled" {
        return Err("repair_mode must remain disabled".into());
    }
    let p = &c["public"];
    exact(
        p,
        "public",
        "page_url livez_url protected_health_url timeout_seconds",
    )?;
    for field in ["page_url", "livez_url", "protected_health_url"] {
        url(&p[field], field, true)?;
    }
    number(&p["timeout_seconds"], "public.timeout_seconds", 0.1, 120.)?;
    let ssh = &c["ssh"];
    exact(
        ssh,
        "ssh",
        "binary host user connect_timeout_seconds command_timeout_seconds",
    )?;
    text(&ssh["binary"], "ssh.binary", 256, false, "_./+-")?;
    text(&ssh["host"], "ssh.host", 253, false, "_.-")?;
    text(&ssh["user"], "ssh.user", 64, false, "_.-")?;
    number(
        &ssh["connect_timeout_seconds"],
        "ssh.connect_timeout_seconds",
        1.,
        120.,
    )?;
    number(
        &ssh["command_timeout_seconds"],
        "ssh.command_timeout_seconds",
        1.,
        300.,
    )?;
    let containers = c["containers"]
        .as_array()
        .filter(|a| !a.is_empty() && a.len() <= 32)
        .ok_or("containers must be a non-empty bounded array")?;
    let mut names = HashSet::new();
    for row in containers {
        exact(row, "container", "name health_required")?;
        let name = text(&row["name"], "container.name", 128, false, "_.-")?;
        if !names.insert(name) || !row["health_required"].is_boolean() {
            return Err("duplicate container or invalid health_required".into());
        }
    }
    let endpoints = c["container_endpoints"]
        .as_array()
        .filter(|a| !a.is_empty() && a.len() <= 64)
        .ok_or("container_endpoints must be a non-empty bounded array")?;
    let mut endpoint_names = HashSet::new();
    for row in endpoints {
        exact(row, "endpoint", "name container url")?;
        let name = text(&row["name"], "endpoint.name", 128, false, "_.-")?;
        let container = text(&row["container"], "endpoint.container", 128, false, "_.-")?;
        if !endpoint_names.insert(name) || !names.contains(container) {
            return Err("duplicate endpoint or unconfigured container".into());
        }
        url(&row["url"], "endpoint.url", false)?;
    }
    let st = &c["spacetime"];
    exact(
        st,
        "spacetime",
        "binary server database ticket_id expected_operator_identity timeout_seconds",
    )?;
    text(&st["binary"], "spacetime.binary", 256, false, "_./+-")?;
    url(&st["server"], "spacetime.server", true)?;
    text(&st["database"], "spacetime.database", 128, false, "_.-")?;
    text(&st["ticket_id"], "spacetime.ticket_id", 128, false, "_.:-")?;
    let identity = text(
        &st["expected_operator_identity"],
        "spacetime.expected_operator_identity",
        64,
        false,
        "",
    )?;
    if identity.len() != 64
        || !identity
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err("invalid operator identity".into());
    }
    number(
        &st["timeout_seconds"],
        "spacetime.timeout_seconds",
        1.,
        120.,
    )?;
    let px = &c["pixel"];
    exact(
        px,
        "pixel",
        "adb_binary serial timeout_seconds curl_path health_url",
    )?;
    text(&px["adb_binary"], "pixel.adb_binary", 256, false, "_./+-")?;
    text(&px["serial"], "pixel.serial", 128, false, "_.:-")?;
    number(&px["timeout_seconds"], "pixel.timeout_seconds", 1., 120.)?;
    if !text(&px["curl_path"], "pixel.curl_path", 512, false, "_./+-")?.starts_with('/') {
        return Err("pixel.curl_path must be absolute".into());
    }
    url(&px["health_url"], "pixel.health_url", false)?;
    let t = &c["thresholds"];
    exact(
        t,
        "thresholds",
        "pixel_frame_age_millis relay_frame_age_millis resources",
    )?;
    for field in ["pixel_frame_age_millis", "relay_frame_age_millis"] {
        let pair = &t[field];
        exact(pair, field, "warning failure")?;
        let warning = number(&pair["warning"], field, 1., 3000.)?;
        if pair["failure"].as_i64() != Some(3000) || warning >= 3000. {
            return Err(format!(
                "{field} requires warning below the 3000 ms product boundary"
            ));
        }
    }
    exact(&t["resources"], "resources", "host pixel")?;
    for (section, fields) in [
        (
            "host",
            &[
                ("memory_used_percent", 100.),
                ("root_disk_used_percent", 100.),
                ("container_cpu_percent", 10000.),
                ("container_memory_percent", 100.),
            ][..],
        ),
        (
            "pixel",
            &[
                ("battery_temperature_c", 100.),
                ("thermal_status", 6.),
                ("memory_used_percent", 100.),
                ("data_disk_used_percent", 100.),
            ][..],
        ),
    ] {
        let values = &t["resources"][section];
        let mut keys = fields.iter().map(|(f, _)| *f).collect::<Vec<_>>();
        if section == "pixel" {
            keys.push("battery_level_percent");
        }
        exact(values, section, &keys.join(" "))?;
        for (field, max) in fields {
            exact(&values[*field], field, "warning failure")?;
            let w = number(&values[*field]["warning"], field, 0., *max)?;
            let f = number(&values[*field]["failure"], field, 0., *max)?;
            if w >= f {
                return Err(format!("{field}.warning must be lower than failure"));
            }
        }
    }
    let battery = &t["resources"]["pixel"]["battery_level_percent"];
    exact(
        battery,
        "battery_level_percent",
        "warning_below failure_below",
    )?;
    if number(&battery["failure_below"], "battery.failure_below", 0., 100.)?
        >= number(&battery["warning_below"], "battery.warning_below", 0., 100.)?
    {
        return Err("battery failure must be below warning".into());
    }
    exact(
        &c["reporting"],
        "reporting",
        "max_degraded_evidence_reports",
    )?;
    if int(&c["reporting"]["max_degraded_evidence_reports"], 1, 1000).is_none() {
        return Err("invalid evidence retention".into());
    }
    let standby = c["standby_devices"]
        .as_array()
        .filter(|a| a.len() <= 16)
        .ok_or("standby_devices must be a bounded array")?;
    let mut serials = HashSet::new();
    for serial in standby {
        if !serials.insert(text(serial, "standby_devices", 128, false, "_.:-")?) {
            return Err("duplicate standby device".into());
        }
    }
    Ok(())
}

struct CommandResult {
    code: i32,
    out: String,
}
fn decode(mut data: Vec<u8>) -> String {
    let mut out = String::from_utf8_lossy(&data).into_owned();
    if out.len() > CAPTURE {
        let mut end = CAPTURE;
        while !out.is_char_boundary(end) {
            end -= 1;
        }
        out.truncate(end);
    }
    data.clear();
    out
}
fn drain(mut input: impl Read, sender: mpsc::Sender<Vec<u8>>) {
    let mut kept = Vec::new();
    let mut buf = [0; 8192];
    while let Ok(n) = input.read(&mut buf) {
        if n == 0 {
            break;
        }
        kept.extend_from_slice(&buf[..n.min(CAPTURE - kept.len())]);
    }
    let _ = sender.send(kept);
}
fn run(args: &[String], timeout: f64) -> CommandResult {
    if INTERRUPTED.load(Ordering::Relaxed) {
        return CommandResult {
            code: 130,
            out: String::new(),
        };
    }
    let mut command = Command::new(&args[0]);
    command
        .args(&args[1..])
        .env("LC_ALL", "C")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .process_group(0);
    let Ok(mut child) = command.spawn() else {
        return CommandResult {
            code: 124,
            out: String::new(),
        };
    };
    struct Group(i32);
    impl Drop for Group {
        fn drop(&mut self) {
            unsafe {
                kill(-self.0, 9);
            }
        }
    }
    let group = Group(child.id() as i32);
    let (out_tx, out_rx) = mpsc::channel();
    let (err_tx, err_rx) = mpsc::channel();
    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    thread::spawn(move || drain(stdout, out_tx));
    thread::spawn(move || drain(stderr, err_tx));
    let started = Instant::now();
    let mut status = None;
    let mut out = None;
    let mut err = None;
    loop {
        if status.is_none() {
            status = child.try_wait().ok().flatten();
        }
        if out.is_none() {
            out = out_rx.try_recv().ok();
        }
        if err.is_none() {
            err = err_rx.try_recv().ok();
        }
        if status.is_some() && out.is_some() && err.is_some() {
            break;
        }
        if INTERRUPTED.load(Ordering::Relaxed) || started.elapsed().as_secs_f64() >= timeout {
            unsafe {
                kill(-group.0, 9);
            }
            let _ = child.wait();
            if out.is_none() {
                out = out_rx.recv_timeout(Duration::from_millis(100)).ok();
            }
            if err.is_none() {
                let _ = err_rx.recv_timeout(Duration::from_millis(100));
            }
            return CommandResult {
                code: if INTERRUPTED.load(Ordering::Relaxed) {
                    130
                } else {
                    124
                },
                out: decode(out.unwrap_or_default()),
            };
        }
        thread::sleep(Duration::from_millis(2));
    }
    CommandResult {
        code: status.and_then(|s| s.code()).unwrap_or(1),
        out: decode(out.unwrap_or_default()),
    }
}
fn args(values: &[&str]) -> Vec<String> {
    values.iter().map(|v| (*v).into()).collect()
}
fn quote(value: &str) -> String {
    if !value.is_empty()
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "_@%+=:,./-".contains(c))
    {
        value.into()
    } else {
        format!("'{}'", value.replace('\'', "'\"'\"'"))
    }
}
fn ssh(c: &Value, cmd: &str) -> CommandResult {
    run(
        &[
            s(&c["binary"]).into(),
            "-o".into(),
            "BatchMode=yes".into(),
            "-o".into(),
            format!(
                "ConnectTimeout={}",
                c["connect_timeout_seconds"].as_f64().unwrap() as u64
            ),
            "-o".into(),
            "ConnectionAttempts=1".into(),
            format!("{}@{}", s(&c["user"]), s(&c["host"])),
            cmd.into(),
        ],
        c["command_timeout_seconds"].as_f64().unwrap(),
    )
}
fn adb(c: &Value, tail: &[&str]) -> CommandResult {
    let mut command = args(&[s(&c["adb_binary"]), "-s", s(&c["serial"])]);
    command.extend(args(tail));
    run(&command, c["timeout_seconds"].as_f64().unwrap())
}
fn safe_json(text: &str) -> Option<Value> {
    serde_json::from_str::<Value>(text)
        .ok()
        .filter(Value::is_object)
}
fn http_probe(url: &str, expected: u16, timeout: f64) -> Value {
    // Use the canonical Mac's native TLS client. No redirect, credential, cookie,
    // retry or browser state is involved. Captures use the same bounded owner.
    let result = run(
        &args(&[
            "curl",
            "--disable",
            "--silent",
            "--show-error",
            "--proto",
            "=https",
            "--max-time",
            &timeout.to_string(),
            "--dump-header",
            "-",
            "--user-agent",
            &format!("pixel-ticket-health-monitor/{VERSION}"),
            url,
        ]),
        timeout,
    );
    if result.code != 0 {
        return json!({"ok":false,"status":null});
    }
    let mut response = result.out.as_str();
    let mut status = None;
    let mut location = "";
    // Proxy CONNECT and informational headers precede the final response.
    while response.starts_with("HTTP/") {
        let split = response
            .find("\r\n\r\n")
            .map(|n| (n, 4))
            .or_else(|| response.find("\n\n").map(|n| (n, 2)));
        let Some((end, sep)) = split else {
            return json!({"ok":false,"status":null});
        };
        let header = &response[..end];
        status = header
            .lines()
            .next()
            .and_then(|l| l.split_whitespace().nth(1))
            .and_then(|n| n.parse::<u16>().ok());
        location = "";
        for line in header.lines().skip(1) {
            if let Some((key, value)) = line.split_once(':')
                && key.eq_ignore_ascii_case("location")
            {
                location = value.trim();
            }
        }
        response = &response[end + sep..];
    }
    let body_bytes = response.as_bytes();
    let body = String::from_utf8_lossy(&body_bytes[..body_bytes.len().min(65536)]);
    let body = safe_json(&body).unwrap_or(json!({}));
    let mut report = json!({"ok":status == Some(expected) && (expected != 302 || location.starts_with("/api/v1/auth/start?")),"status":status});
    for key in ["ok", "status", "serverVersion", "assetVersion"] {
        if let Some(v) = body.get(key) {
            report[format!("body_{key}")] = v.clone();
        }
    }
    report
}
fn collect_public(c: &Value) -> Value {
    let timeout = c["timeout_seconds"].as_f64().unwrap();
    let root = http_probe(s(&c["page_url"]), 302, timeout);
    let livez = http_probe(s(&c["livez_url"]), 200, timeout);
    let protected = http_probe(s(&c["protected_health_url"]), 401, timeout);
    json!({"ok":yes(&root["ok"]) && yes(&livez["ok"]) && yes(&protected["ok"]),"root_http":root["status"],"livez_http":livez["status"],"unauthenticated_health_http":protected["status"],"server_version":livez["body_serverVersion"],"asset_version":livez["body_assetVersion"]})
}
fn rounded(n: f64, digits: i32) -> f64 {
    // Decimal formatting rounds the original binary float, as Python round
    // does. Scaling first would turn 2.675 into a false exact tie at 267.5.
    format!("{n:.precision$}", precision = digits as usize)
        .parse()
        .unwrap()
}
fn parse_meminfo(output: &str) -> Value {
    let mut total = 0_i64;
    let mut available = -1_i64;
    for line in output.lines().take(128) {
        let parts: Vec<_> = line.split_whitespace().collect();
        if parts.len() != 3 || parts[2] != "kB" || !parts[1].bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        if let Ok(n) = parts[1].parse() {
            match parts[0] {
                "MemTotal:" => total = n,
                "MemAvailable:" => available = n,
                _ => {}
            }
        }
    }
    if total <= 0 || available < 0 || available > total {
        Value::Null
    } else {
        json!({"total_mib":rounded(total as f64/1024.,1),"available_mib":rounded(available as f64/1024.,1),"used_percent":rounded((total-available) as f64*100./total as f64,1)})
    }
}
fn parse_disk(output: &str) -> Value {
    for line in output.lines().take(16).collect::<Vec<_>>().iter().rev() {
        let parts: Vec<_> = line.split_whitespace().collect();
        if parts.len() < 6 {
            continue;
        }
        let n = parts.len();
        let Some(percent) = parts[n - 2]
            .strip_suffix('%')
            .and_then(|n| n.parse::<i64>().ok())
        else {
            continue;
        };
        let numbers = parts[n - 5..n - 2]
            .iter()
            .map(|n| n.parse::<i64>())
            .collect::<Result<Vec<_>, _>>();
        if let Ok(v) = numbers
            && v[0] > 0
            && v[1] >= 0
            && v[2] >= 0
            && percent >= 0
        {
            return json!({"total_mib":rounded(v[0] as f64/1024.,1),"used_mib":rounded(v[1] as f64/1024.,1),"available_mib":rounded(v[2] as f64/1024.,1),"used_percent":percent});
        }
    }
    Value::Null
}
fn percent(v: &Value, maximum: f64) -> Value {
    let raw = v
        .as_str()
        .map(str::to_string)
        .unwrap_or_else(|| v.to_string());
    let raw = raw.trim();
    let Some(n) = raw.strip_suffix('%') else {
        return Value::Null;
    };
    let parts: Vec<_> = n.split('.').collect();
    if parts.len() > 2
        || parts
            .iter()
            .any(|part| part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit()))
    {
        return Value::Null;
    }
    match n.parse::<f64>() {
        Ok(n) if n <= maximum => json!(rounded(n, 2)),
        _ => Value::Null,
    }
}
fn docker_stats(output: &str, names: &[String]) -> Value {
    let mut rows = Map::new();
    for line in output.lines().take(32) {
        let Some(item) = safe_json(line.trim()) else {
            continue;
        };
        let name = s(&item["Name"]);
        if !names.iter().any(|n| n == name) || rows.contains_key(name) {
            continue;
        }
        let mut row = json!({"cpu_percent":percent(&item["CPUPerc"],100000.),"memory_percent":percent(&item["MemPerc"],100.),"pids":as_int(&item["PIDs"],-1)});
        let usage = s(&item["MemUsage"]).trim();
        if !usage.is_empty()
            && usage.len() <= 48
            && usage
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || ".%+ /-".contains(c))
        {
            row["memory_usage"] = json!(usage);
        }
        rows.insert(name.into(), row);
    }
    Value::Object(rows)
}
fn collect_host(c: &Value) -> Value {
    let ssh_c = &c["ssh"];
    let reachable = ssh(ssh_c, "true").code == 0;
    let mut result = json!({"ok":reachable,"ssh":reachable,"containers":{},"endpoints":{}});
    if !reachable {
        result["error"] = json!("ssh_unreachable");
        return result;
    }
    let containers = c["containers"].as_array().unwrap();
    let mut names = Vec::new();
    for item in containers {
        let name = s(&item["name"]);
        names.push(name.to_string());
        let response = ssh(
            ssh_c,
            &format!(
                "docker inspect --format '{{{{json .State}}}}' {}",
                quote(name)
            ),
        );
        let state = if response.code == 0 {
            safe_json(response.out.trim()).filter(|v| v.as_object().is_some_and(|m| !m.is_empty()))
        } else {
            None
        };
        let summary = if let Some(state) = state {
            let status = enum_value(
                &state["Status"],
                "created running paused restarting removing exited dead",
                "other",
                true,
            );
            let health = enum_value(
                &state["Health"]["Status"],
                "starting healthy unhealthy",
                "other",
                true,
            );
            json!({"ok":yes(&state["Running"]) && status=="running" && (!yes(&item["health_required"]) || health=="healthy"),"status":status,"health":health,"failing_streak":int(&state["Health"]["FailingStreak"],0,1_000_000)})
        } else {
            json!({"ok":false,"status":"missing","health":null,"failing_streak":null})
        };
        result["containers"][name] = summary;
    }
    for item in c["container_endpoints"].as_array().unwrap() {
        let inner = format!(
            "if command -v curl >/dev/null 2>&1; then curl -fsS --max-time 5 {}; elif command -v wget >/dev/null 2>&1; then wget -qO- -T 5 {}; else exit 127; fi",
            quote(s(&item["url"])),
            quote(s(&item["url"]))
        );
        let response = ssh(
            ssh_c,
            &format!(
                "docker exec {} sh -lc {}",
                quote(s(&item["container"])),
                quote(&inner)
            ),
        );
        let payload = if response.code == 0 {
            safe_json(response.out.trim())
        } else {
            None
        };
        let status = enum_value(
            &payload.as_ref().unwrap_or(&Value::Null)["status"],
            "ok healthy ready live starting degraded error unhealthy unavailable",
            "other",
            true,
        );
        let ok = response.code == 0
            && payload
                .as_ref()
                .is_some_and(|v| yes(&v["ok"]) || status == "ok" || status == "healthy");
        result["endpoints"][s(&item["name"])] = json!({"ok":ok,"status":status});
    }
    let mem = ssh(ssh_c, "cat /proc/meminfo");
    let disk = ssh(ssh_c, "df -Pk /");
    let uptime = ssh(ssh_c, "cat /proc/uptime");
    let stats = ssh(
        ssh_c,
        &format!(
            "docker stats --no-stream --format '{{{{json .}}}}' {}",
            names.iter().map(|n| quote(n)).collect::<Vec<_>>().join(" ")
        ),
    );
    let memory = if mem.code == 0 {
        parse_meminfo(&mem.out)
    } else {
        Value::Null
    };
    let root_disk = if disk.code == 0 {
        parse_disk(&disk.out)
    } else {
        Value::Null
    };
    let uptime = if uptime.code == 0 {
        uptime
            .out
            .split_whitespace()
            .next()
            .and_then(|n| n.parse::<f64>().ok())
            .filter(|n| n.is_finite() && *n >= 0. && *n <= i64::MAX as f64)
            .map(|n| n as i64)
    } else {
        None
    };
    let docker = if stats.code == 0 {
        docker_stats(&stats.out, &names)
    } else {
        json!({})
    };
    let resource_ok = !memory.is_null()
        && !root_disk.is_null()
        && uptime.is_some()
        && docker.as_object().unwrap().len() == names.len()
        && docker.as_object().unwrap().values().all(|v| {
            !v["cpu_percent"].is_null()
                && !v["memory_percent"].is_null()
                && as_int(&v["pids"], -1) >= 0
        });
    result["resource_summary"] = json!({"ok":resource_ok,"uptime_seconds":uptime,"memory":memory,"root_disk":root_disk,"docker":docker});
    result["ok"] = json!(
        resource_ok
            && result["containers"]
                .as_object()
                .unwrap()
                .values()
                .all(|v| yes(&v["ok"]))
            && result["endpoints"]
                .as_object()
                .unwrap()
                .values()
                .all(|v| yes(&v["ok"]))
    );
    result
}

fn sql_parts(output: &str) -> (Vec<String>, Vec<HashMap<String, String>>, bool) {
    let output = output.replace('│', "|");
    let mut cells = Vec::new();
    for raw in output.lines() {
        let line = raw.trim().trim_matches('|').trim();
        if line.is_empty() || line.chars().all(|c| "-+=: ─┼┬┴╭╮╰╯├┤┌┐└┘".contains(c))
        {
            continue;
        }
        let row = line
            .split('|')
            .map(|v| {
                let v = v.trim();
                if v.len() >= 2
                    && ((v.starts_with('"') && v.ends_with('"'))
                        || (v.starts_with('\'') && v.ends_with('\'')))
                {
                    v[1..v.len() - 1].into()
                } else {
                    v.into()
                }
            })
            .collect::<Vec<String>>();
        if row.iter().any(|v| !v.is_empty()) {
            cells.push(row);
        }
    }
    if cells.is_empty() {
        return (vec![], vec![], true);
    }
    let header = cells.remove(0);
    let mut malformed = header.iter().any(String::is_empty)
        || header.iter().collect::<HashSet<_>>().len() != header.len();
    let mut rows = Vec::new();
    for row in cells {
        if row.len() != header.len() {
            malformed = true;
        } else {
            rows.push(header.iter().cloned().zip(row).collect());
        }
    }
    (header, rows, malformed)
}
fn strict_bool(v: &str) -> Result<bool, &'static str> {
    match v.trim().to_lowercase().as_str() {
        "true" => Ok(true),
        "false" => Ok(false),
        _ => Err("spacetime_data_invalid"),
    }
}
fn strict_int(v: &str, low: i64, high: i64) -> Result<i64, &'static str> {
    let v = v.trim();
    let digits = v.strip_prefix('-').unwrap_or(v);
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return Err("spacetime_data_invalid");
    }
    v.parse::<i64>()
        .ok()
        .filter(|v| (low..=high).contains(v))
        .ok_or("spacetime_data_invalid")
}
fn strict_enum(v: &str, allowed: &str) -> Result<String, &'static str> {
    let v = v.trim().to_lowercase();
    if contains(allowed, &v) {
        Ok(v)
    } else {
        Err("spacetime_data_invalid")
    }
}
fn strict_status(v: &Value, fields: &[(&str, &str, &str)]) -> Result<Value, &'static str> {
    let mut result = json!({});
    for (name, key, allowed) in fields {
        let raw = &v[*key];
        let value = if raw.is_null() {
            Value::Null
        } else if allowed.is_empty() {
            raw.as_bool()
                .map(Value::Bool)
                .ok_or("spacetime_data_invalid")?
        } else {
            json!(strict_enum(
                raw.as_str().ok_or("spacetime_data_invalid")?,
                allowed
            )?)
        };
        result[*name] = value;
    }
    Ok(result)
}
fn timestamp(value: &str) -> Result<DateTime<Utc>, &'static str> {
    if value.len() > 80 {
        return Err("spacetime_timestamp_invalid");
    }
    let normalized = if value.len() > 10 && value.is_char_boundary(10) {
        let mut text = value.to_owned();
        if let Some(separator) = value[10..].chars().next() {
            text.replace_range(10..10 + separator.len_utf8(), "T");
        }
        text
    } else {
        value.to_owned()
    };
    DateTime::parse_from_rfc3339(&normalized)
        .or_else(|_| {
            [
                "%Y-%m-%dT%H:%M:%S%.f%#z",
                "%Y-%m-%dT%H:%M%#z",
                "%Y-%m-%dT%H%#z",
            ]
            .iter()
            .find_map(|format| DateTime::parse_from_str(&normalized, format).ok())
            .ok_or(())
        })
        .and_then(|t| {
            if t.timestamp_subsec_nanos() >= 1_000_000_000 || value.ends_with('z') {
                Err(())
            } else {
                Ok(t)
            }
        })
        .map(|t| DateTime::from_timestamp_micros(t.timestamp_micros()).unwrap())
        .map_err(|_| "spacetime_timestamp_invalid")
}
fn age_millis(value: &str, observed: DateTime<Utc>) -> Result<i64, &'static str> {
    let micros = observed
        .signed_duration_since(timestamp(value)?)
        .num_microseconds()
        .ok_or("spacetime_timestamp_invalid")?;
    let age = micros.div_euclid(1000) + i64::from(micros.rem_euclid(1000) != 0);
    if age < -250 {
        return Err("spacetime_clock_unbounded");
    }
    Ok(age.max(0))
}
fn optional_frame_age(value: &str, observed: DateTime<Utc>) -> Result<Value, &'static str> {
    if ["", "null", "NULL", "(none = ())"].contains(&value) {
        return Ok(Value::Null);
    }
    let decoded;
    let value = if let Some(inner) = value
        .strip_prefix("(some = ")
        .and_then(|v| v.strip_suffix(')'))
    {
        decoded =
            serde_json::from_str::<String>(inner).map_err(|_| "spacetime_timestamp_invalid")?;
        if decoded.is_empty() {
            return Ok(Value::Null);
        }
        &decoded
    } else {
        value
    };
    Ok(json!(age_millis(value, observed)?))
}
fn page_warm(
    status: &Value,
    report_at: &str,
    observed: DateTime<Utc>,
) -> Result<Value, &'static str> {
    let raw = &status["pageOpenWarm"];
    if raw.is_null() {
        return Ok(Value::Null);
    }
    exact(raw, "pageOpenWarm", "retainedSessions expiresAt")
        .map_err(|_| "spacetime_warm_state_invalid")?;
    let count =
        int(&raw["retainedSessions"], 0, 1_000_000).ok_or("spacetime_warm_state_invalid")?;
    if count == 0 {
        if raw["expiresAt"] != "" {
            return Err("spacetime_warm_state_invalid");
        }
        return Ok(json!({"retained_sessions":0,"remaining_millis":0}));
    }
    let deadline = timestamp(
        raw["expiresAt"]
            .as_str()
            .ok_or("spacetime_timestamp_invalid")?,
    )?;
    let span = deadline
        .signed_duration_since(timestamp(report_at)?)
        .num_microseconds()
        .ok_or("spacetime_warm_state_invalid")?;
    if span <= 0 || span > WARM_MAX * 1000 {
        return Err("spacetime_warm_state_invalid");
    }
    let remaining = deadline
        .signed_duration_since(observed)
        .num_microseconds()
        .ok_or("spacetime_warm_state_invalid")?
        .div_euclid(1000);
    Ok(json!({"retained_sessions":count,"remaining_millis":remaining}))
}
fn spacetime_sql(
    c: &Value,
    sql: &str,
    columns: &[&str],
) -> Result<Vec<HashMap<String, String>>, &'static str> {
    let result = run(
        &args(&[
            s(&c["binary"]),
            "sql",
            "--yes",
            "-s",
            s(&c["server"]),
            s(&c["database"]),
            sql,
        ]),
        c["timeout_seconds"].as_f64().unwrap(),
    );
    if result.code != 0 {
        return Err("spacetime_query_failed");
    }
    let (header, rows, malformed) = sql_parts(&result.out);
    if malformed || header.iter().map(String::as_str).collect::<Vec<_>>() != columns {
        return Err("spacetime_query_schema_invalid");
    }
    Ok(rows)
}
fn collect_spacetime(c: &Value) -> Value {
    let login = run(
        &args(&[s(&c["binary"]), "login", "show"]),
        c["timeout_seconds"].as_f64().unwrap(),
    );
    let identity = login.out.trim().strip_prefix("You are logged in as ");
    let Some(identity) = identity.filter(|v| {
        v.len() == 64
            && v.bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    }) else {
        return json!({"ok":false,"error":"spacetime_operator_identity_unavailable","operator_identity_verified":false});
    };
    if login.code != 0 {
        return json!({"ok":false,"error":"spacetime_operator_identity_unavailable","operator_identity_verified":false});
    }
    if identity != s(&c["expected_operator_identity"]) {
        return json!({"ok":false,"error":"spacetime_operator_identity_mismatch","operator_identity_verified":false});
    }
    let collect = || -> Result<Value, &'static str> {
        let queries = [
            (
                "ticketremote_stream_desired_state",
                "desiredActive, viewerCount, reason",
                vec!["desiredActive", "viewerCount", "reason"],
            ),
            (
                "ticketremote_phone_current_report",
                "streamState, desiredActive, statusJson, updatedAt",
                vec!["streamState", "desiredActive", "statusJson", "updatedAt"],
            ),
            (
                "ticketremote_relay_current_report",
                "videoClients, streamVerdict, lastFrameAt, statusJson, updatedAt",
                vec![
                    "videoClients",
                    "streamVerdict",
                    "lastFrameAt",
                    "statusJson",
                    "updatedAt",
                ],
            ),
            ("ticketremote_stream_command", "status", vec!["status"]),
        ];
        let mut rows = Vec::new();
        let mut observed = Vec::new();
        for (table, fields, columns) in queries {
            rows.push(spacetime_sql(
                c,
                &format!(
                    "SELECT {fields} FROM {table} WHERE ticketId = '{}';",
                    s(&c["ticket_id"]).replace('\'', "''")
                ),
                &columns,
            )?);
            observed.push(observed_now());
        }
        if rows[..3].iter().any(|r| r.len() != 1) {
            return Err("spacetime_current_state_missing");
        }
        let (desired, phone, relay) = (&rows[0][0], &rows[1][0], &rows[2][0]);
        let phone_json = safe_json(&phone["statusJson"]).ok_or("spacetime_data_invalid")?;
        let relay_json = safe_json(&relay["statusJson"]).ok_or("spacetime_data_invalid")?;
        let statuses = rows[3]
            .iter()
            .map(|r| strict_enum(&r["status"], COMMAND_STATES))
            .collect::<Result<Vec<_>, _>>()?;
        let mut result = json!({
            "ok":true,"operator_identity_verified":true,
            "desired_active":strict_bool(&desired["desiredActive"])?});
        result["viewer_count"] = json!(strict_int(&desired["viewerCount"], 0, 1_000_000)?);
        result["desired_reason"] = enum_value(
            &json!(desired["reason"]),
            "admin_force admin_stop no_viewers relay_viewer_added relay_viewer_removed relay_viewer_updated viewer_heartbeat viewer_timeout",
            "other",
            true,
        );
        result["phone_stream_state"] = json!(strict_enum(&phone["streamState"], PHONE_STATES)?);
        result["phone_desired_active"] = json!(strict_bool(&phone["desiredActive"])?);
        result["phone_status"] = strict_status(
            &phone_json,
            &[
                ("stream_active", "streamActive", ""),
                ("stream_verdict", "streamVerdict", STREAM_VERDICTS),
                ("session_state", "sessionState", SESSION_STATES),
            ],
        )?;
        result["phone_report_age_millis"] = json!(age_millis(&phone["updatedAt"], observed[1])?);
        result["phone_observed_at"] =
            json!(observed[1].to_rfc3339_opts(SecondsFormat::Micros, false));
        result["relay_video_clients"] = json!(strict_int(&relay["videoClients"], 0, 1_000_000)?);
        result["relay_stream_verdict"] =
            json!(strict_enum(&relay["streamVerdict"], RELAY_VERDICTS)?);
        result["relay_last_frame_ago_millis"] =
            optional_frame_age(&relay["lastFrameAt"], observed[2])?;
        result["relay_report_age_millis"] = json!(age_millis(&relay["updatedAt"], observed[2])?);
        result["relay_observed_at"] =
            json!(observed[2].to_rfc3339_opts(SecondsFormat::Micros, false));
        result["page_open_warm"] = page_warm(&relay_json, &relay["updatedAt"], observed[2])?;
        result["relay_status"] = strict_status(
            &relay_json,
            &[
                ("phone_connected", "phoneConnected", ""),
                ("phone_desired", "phoneDesired", ""),
                ("phone_stream_state", "phoneStreamState", PHONE_STATES),
                ("live", "live", ""),
            ],
        )?;
        result["pending_stream_commands"] =
            json!(statuses.iter().filter(|s| !contains(TERMINAL, s)).count());
        Ok(result)
    };
    match collect() {
        Ok(result) => result,
        Err(error) => json!({"ok":false,"error":error,"operator_identity_verified":true}),
    }
}

fn select_pixel_health(h: &Value) -> Value {
    let r = &h["recovery"];
    let failure = |field: &str| {
        let value = &r[field];
        if value.is_null() || value == "" {
            value.clone()
        } else {
            enum_value(value, RECOVERY_FAILURES, "reported", true)
        }
    };
    json!({
        "ok":h["ok"].as_bool(),"session_state":enum_value(&h["sessionState"],SESSION_STATES,"other",true),"stream_active":h["streamActive"].as_bool(),"stream_verdict":enum_value(&h["streamVerdict"],STREAM_VERDICTS,"other",true),
        "visible_frame_age_millis":int(&h["streamPipeline"]["lastFrameSentAgoMillis"],-1,1_000_000_000_000),
        "hardware_h264":{"active":h["hardwareH264"]["active"].as_bool(),"available":h["hardwareH264"]["available"].as_bool(),"visibility":enum_value(&h["hardwareH264"]["lastVisibilityCheckResult"],VISIBILITY,"other",true)},
        "stream_pipeline":{"video_clients":int(&h["streamPipeline"]["videoClients"],0,1_000_000_000_000),"encoder_running":h["hardwareH264"]["active"].as_bool(),"last_frame_sent_ago_millis":int(&h["streamPipeline"]["lastFrameSentAgoMillis"],-1,1_000_000_000_000)},
        "ticket_state":enum_value(&h["ticketState"]["state"],TICKET_STATES,"other",true),"vivi_state":enum_value(&h["viviState"]["state"],VIVI_STATES,"OTHER_VIVI",false),
        "recovery":{"desired_stage":enum_value(&r["desiredRecoveryStage"],RECOVERY_STAGES,"other",true),"desired_result":enum_value(&r["lastDesiredRecoveryResult"],RECOVERY_RESULTS,"other",true),"desired_failure":failure("lastDesiredRecoveryFailureReason"),"stream_stage":enum_value(&r["streamStage"],RECOVERY_STAGES,"other",true),"stream_result":enum_value(&r["lastStreamRecoveryResult"],RECOVERY_RESULTS,"other",true),"stream_failure":failure("lastStreamRecoveryFailureReason")}
    })
}
fn pixel_contract(h: &Value) -> bool {
    let hardware = &h["hardwareH264"];
    let recovery = &h["recovery"];
    if !h["ok"].is_boolean()
        || !h["streamActive"].is_boolean()
        || !raw_enum(&h["sessionState"], SESSION_STATES, true)
        || !raw_enum(&h["streamVerdict"], STREAM_VERDICTS, true)
        || !hardware.is_object()
        || !hardware["active"].is_boolean()
        || !hardware["available"].is_boolean()
        || !raw_enum(&hardware["lastVisibilityCheckResult"], VISIBILITY, true)
        || !recovery.is_object()
        || !raw_enum(&recovery["streamStage"], RECOVERY_STAGES, true)
        || !raw_enum(
            &recovery["lastStreamRecoveryResult"],
            RECOVERY_RESULTS,
            true,
        )
        || (!recovery["lastStreamRecoveryFailureReason"].is_null()
            && !recovery["lastStreamRecoveryFailureReason"].is_string())
    {
        return false;
    }
    // Retained while the deployed V1 reporter still needs these legacy fields.
    for (field, allowed) in [
        ("desiredRecoveryStage", RECOVERY_STAGES),
        ("lastDesiredRecoveryResult", RECOVERY_RESULTS),
    ] {
        if recovery.get(field).is_some() && !raw_enum(&recovery[field], allowed, true) {
            return false;
        }
    }
    if !recovery["lastDesiredRecoveryFailureReason"].is_null()
        && !recovery["lastDesiredRecoveryFailureReason"].is_string()
    {
        return false;
    }
    if !h["ticketState"].is_object()
        || !raw_enum(&h["ticketState"]["state"], TICKET_STATES, true)
        || !h["viviState"].is_object()
        || !raw_enum(&h["viviState"]["state"], VIVI_STATES, false)
    {
        return false;
    }
    !yes(&h["streamActive"])
        || (h["streamPipeline"].is_object()
            && int(&h["streamPipeline"]["videoClients"], 0, 1_000_000_000_000).is_some()
            && int(
                &h["streamPipeline"]["lastFrameSentAgoMillis"],
                0,
                1_000_000_000_000,
            )
            .is_some())
}
fn parse_battery(output: &str) -> Value {
    let mut fields = HashMap::new();
    for line in output.lines().take(128) {
        if let Some((field, value)) = line.trim().split_once(':')
            && ["level", "status", "temperature"].contains(&field)
        {
            let value = value.trim();
            let digits = value.strip_prefix('-').unwrap_or(value);
            if !digits.is_empty()
                && digits.bytes().all(|b| b.is_ascii_digit())
                && let Ok(n) = value.parse::<i64>()
            {
                fields.insert(field, n);
            }
        }
    }
    let (Some(level), Some(status), Some(temp)) = (
        fields.get("level"),
        fields.get("status"),
        fields.get("temperature"),
    ) else {
        return Value::Null;
    };
    if !(0..=100).contains(level) {
        return Value::Null;
    }
    json!({"level_percent":level,"temperature_c":rounded(*temp as f64/10.,1),"status_code":status})
}
fn elapsed_seconds(raw: &str) -> Option<i64> {
    let (days, clock) = raw
        .split_once('-')
        .map(|(d, c)| (d.parse::<i64>().ok(), c))
        .unwrap_or((Some(0), raw));
    let days = days?;
    if !raw.chars().all(|c| c.is_ascii_digit() || "-:".contains(c)) {
        return None;
    }
    let parts = clock
        .split(':')
        .map(str::parse::<i64>)
        .collect::<Result<Vec<_>, _>>()
        .ok()?;
    if days < 0 || parts.iter().any(|p| *p < 0) {
        return None;
    }
    match parts.as_slice() {
        [h, m, s] if *h < 24 && *m < 60 && *s < 60 => {
            days.checked_mul(86400)?.checked_add(h * 3600 + m * 60 + s)
        }
        [m, s] if days == 0 && !raw.contains('-') && *s < 60 => m.checked_mul(60)?.checked_add(*s),
        _ => None,
    }
}
fn lifecycle(output: &str) -> Value {
    let lines = output
        .lines()
        .take(128)
        .filter(|l| !l.trim().is_empty())
        .collect::<Vec<_>>();
    if lines.first().is_none_or(|l| {
        l.split_whitespace().take(6).collect::<Vec<_>>()
            != ["PID", "PPID", "ELAPSED", "STAT", "NAME", "ARGS"]
    }) {
        return json!({"ok":false,"stuck_helper_count":0,"stuck_start_stop_count":0,"oldest_age_seconds":null});
    }
    let mut parents = HashMap::new();
    let mut helpers = Vec::new();
    for line in lines.into_iter().skip(1) {
        let fields = line.split_whitespace().collect::<Vec<_>>();
        if fields.len() < 6 {
            continue;
        }
        let (Ok(pid), Ok(ppid), Some(age)) = (
            fields[0].parse::<i64>(),
            fields[1].parse::<i64>(),
            elapsed_seconds(fields[2]),
        ) else {
            continue;
        };
        let old_lifecycle = fields[4] == "sh"
            && fields.len() >= 7
            && fields[5] == "sh"
            && [
                "/data/local/pixel-stack/bin/pixel-ticket-start.sh",
                "/data/local/pixel-stack/bin/pixel-ticket-stop.sh",
            ]
            .contains(&fields[6]);
        let native_lifecycle = fields.len() >= 7
            && fields[5] == "/data/local/pixel-stack/bin/pixel-runtime-cleanup"
            && ["pixel-ticket-start.sh", "pixel-ticket-stop.sh"].contains(&fields[6]);
        if old_lifecycle || native_lifecycle {
            parents.insert(pid, age);
        } else if fields[4] == "tr"
            && fields.len() == 7
            && fields[5] == "tr"
            && ["\\000", "\\0", "\\x00"].contains(&fields[6])
        {
            helpers.push((ppid, age));
        }
    }
    let parents = parents
        .into_iter()
        .filter(|(_, age)| *age > 60)
        .collect::<HashMap<_, _>>();
    let helpers = helpers
        .into_iter()
        .filter(|(ppid, age)| *age > 60 && (*ppid == 1 || parents.contains_key(ppid)))
        .map(|(_, age)| age)
        .collect::<Vec<_>>();
    let oldest = parents.values().chain(helpers.iter()).max();
    json!({"ok":true,"stuck_helper_count":helpers.len(),"stuck_start_stop_count":parents.len(),"oldest_age_seconds":oldest})
}
fn pixel_resources(c: &Value) -> Value {
    let battery = adb(c, &["shell", "dumpsys", "battery"]);
    let thermal = adb(c, &["shell", "dumpsys", "thermalservice"]);
    let memory = adb(c, &["shell", "cat", "/proc/meminfo"]);
    let disk = adb(c, &["shell", "df", "-Pk", "/data"]);
    let battery = if battery.code == 0 {
        parse_battery(&battery.out)
    } else {
        Value::Null
    };
    let thermal = if thermal.code == 0 {
        thermal.out.lines().find_map(|l| {
            l.trim()
                .split_once(':')
                .filter(|(k, _)| k.eq_ignore_ascii_case("Thermal Status"))
                .and_then(|(_, v)| v.trim().parse::<i64>().ok())
                .filter(|n| *n >= 0)
        })
    } else {
        None
    };
    let memory = if memory.code == 0 {
        parse_meminfo(&memory.out)
    } else {
        Value::Null
    };
    let disk = if disk.code == 0 {
        parse_disk(&disk.out)
    } else {
        Value::Null
    };
    json!({"ok":!battery.is_null() && thermal.is_some() && !memory.is_null() && !disk.is_null(),"battery":battery,"thermal_status":thermal,"memory":memory,"data_disk":disk})
}
fn collect_pixel(c: &Value) -> Value {
    let state = adb(c, &["get-state"]);
    let adb_state = enum_value(
        &json!(state.out.trim()),
        "device offline unauthorized unknown",
        "other",
        true,
    );
    let mut result = json!({"ok":false,"adb_state":adb_state});
    if state.code != 0 || adb_state != "device" {
        result["error"] = json!("adb_unreachable");
        return result;
    }
    let command = format!(
        "{} -fsS --max-time 5 {}",
        quote(s(&c["curl_path"])),
        quote(s(&c["health_url"]))
    );
    let response = adb(c, &["shell", "su", "-c", &command]);
    let health = if response.code == 0 {
        safe_json(response.out.trim()).filter(|h| h.as_object().is_some_and(|h| !h.is_empty()))
    } else {
        None
    };
    let Some(health) = health else {
        result["error"] = json!("pixel_health_unavailable");
        return result;
    };
    result["observed_at"] = json!(observed_now().to_rfc3339_opts(SecondsFormat::Micros, false));
    let rotation = adb(
        c,
        &[
            "shell",
            "settings",
            "get",
            "system",
            "accelerometer_rotation",
        ],
    );
    let user_rotation = adb(c, &["shell", "settings", "get", "system", "user_rotation"]);
    let rotations = [
        rotation.out.trim().parse::<i64>().unwrap_or(-1),
        user_rotation.out.trim().parse::<i64>().unwrap_or(-1),
    ];
    let resources = pixel_resources(c);
    let lifecycle_result = adb(c, &["shell", "su", "-c", LIFECYCLE_COMMAND]);
    let lifecycle = if lifecycle_result.code == 0 {
        lifecycle(&lifecycle_result.out)
    } else {
        lifecycle("")
    };
    let contract = pixel_contract(&health);
    result["ok"] = json!(
        yes(&health["ok"])
            && contract
            && rotations == [0, 0]
            && yes(&resources["ok"])
            && yes(&lifecycle["ok"])
    );
    result["health_contract_ok"] = json!(contract);
    result["health"] = select_pixel_health(&health);
    result["portrait_lock"] = json!({"ok":rotations==[0,0],"accelerometer_rotation":rotations[0],"user_rotation":rotations[1]});
    result["resources"] = resources;
    result["ticket_lifecycle"] = lifecycle;
    result
}

fn push_if(values: &mut Vec<String>, condition: bool, name: &str) {
    if condition {
        values.push(name.into());
    }
}
fn metric(v: &Value, high: f64) -> Option<f64> {
    v.as_f64().filter(|n| *n >= 0. && *n <= high)
}
fn classify(
    n: f64,
    t: &Value,
    failure: &str,
    warning: &str,
    low: bool,
    f: &mut Vec<String>,
    w: &mut Vec<String>,
) {
    let failed = if low {
        n <= t["failure_below"].as_f64().unwrap()
    } else {
        n >= t["failure"].as_f64().unwrap()
    };
    let warned = if low {
        n <= t["warning_below"].as_f64().unwrap()
    } else {
        n >= t["warning"].as_f64().unwrap()
    };
    if failed {
        f.push(failure.into());
    } else if warned {
        w.push(warning.into());
    }
}
fn dedup(values: &mut Vec<String>) {
    let mut seen = HashSet::new();
    values.retain(|v| seen.insert(v.clone()));
}
fn evaluate_resources(
    host: &Value,
    pixel: &Value,
    thresholds: &Value,
) -> (Vec<String>, Vec<String>) {
    let mut failures = Vec::new();
    let mut warnings = Vec::new();
    let limits = &thresholds["resources"];
    if yes(&host["ok"]) {
        let summary = &host["resource_summary"];
        let mut values = vec![
            (
                metric(&summary["memory"]["used_percent"], 100.),
                "memory_used_percent",
                "host_memory_pressure",
                "Host memory usage is above its warning threshold.",
            ),
            (
                metric(&summary["root_disk"]["used_percent"], 100.),
                "root_disk_used_percent",
                "host_root_disk_pressure",
                "Host root disk usage is above its warning threshold.",
            ),
        ];
        let docker = summary["docker"].as_object();
        if let Some(docker) = docker {
            for row in docker.values() {
                values.extend([
                    (
                        metric(&row["cpu_percent"], 10000.),
                        "container_cpu_percent",
                        "host_container_cpu_pressure",
                        "A Ticket container is above its CPU warning threshold.",
                    ),
                    (
                        metric(&row["memory_percent"], 100.),
                        "container_memory_percent",
                        "host_container_memory_pressure",
                        "A Ticket container is above its memory warning threshold.",
                    ),
                ]);
            }
        }
        if docker.is_none_or(|d| d.is_empty()) || values.iter().any(|(v, _, _, _)| v.is_none()) {
            failures.push("host_resource_metrics_invalid".into());
        } else {
            for (n, field, f, w) in values {
                classify(
                    n.unwrap(),
                    &limits["host"][field],
                    f,
                    w,
                    false,
                    &mut failures,
                    &mut warnings,
                );
            }
        }
    }
    if yes(&pixel["ok"]) {
        let summary = &pixel["resources"];
        let values = [
            (
                metric(&summary["battery"]["level_percent"], 100.),
                "battery_level_percent",
                "pixel_battery_critical",
                "Pixel battery level is below its warning threshold.",
                true,
            ),
            (
                metric(&summary["battery"]["temperature_c"], 100.),
                "battery_temperature_c",
                "pixel_battery_temperature_critical",
                "Pixel battery temperature is above its warning threshold.",
                false,
            ),
            (
                metric(&summary["thermal_status"], 6.),
                "thermal_status",
                "pixel_thermal_critical",
                "Pixel thermal status is above its warning threshold.",
                false,
            ),
            (
                metric(&summary["memory"]["used_percent"], 100.),
                "memory_used_percent",
                "pixel_memory_pressure",
                "Pixel memory usage is above its warning threshold.",
                false,
            ),
            (
                metric(&summary["data_disk"]["used_percent"], 100.),
                "data_disk_used_percent",
                "pixel_data_disk_pressure",
                "Pixel data disk usage is above its warning threshold.",
                false,
            ),
        ];
        if values.iter().any(|(v, _, _, _, _)| v.is_none()) {
            failures.push("pixel_resource_metrics_invalid".into());
        } else {
            for (n, field, f, w, low) in values {
                classify(
                    n.unwrap(),
                    &limits["pixel"][field],
                    f,
                    w,
                    low,
                    &mut failures,
                    &mut warnings,
                );
            }
        }
    }
    dedup(&mut failures);
    dedup(&mut warnings);
    (failures, warnings)
}
fn recovery_failed(r: &Value) -> bool {
    r["desired_stage"] == "failed"
        || r["desired_result"] == "failed"
        || truth(&r["desired_failure"])
        || contains("blocked failed", s(&r["stream_stage"]))
        || r["stream_result"] == "failed"
        || truth(&r["stream_failure"])
}
fn lifecycle_valid(v: &Value) -> bool {
    if exact(
        v,
        "lifecycle",
        "ok stuck_helper_count stuck_start_stop_count oldest_age_seconds",
    )
    .is_err()
        || !yes(&v["ok"])
    {
        return false;
    }
    let (Some(helpers), Some(parents)) = (
        int(&v["stuck_helper_count"], 0, 64),
        int(&v["stuck_start_stop_count"], 0, 64),
    ) else {
        return false;
    };
    if helpers + parents == 0 {
        v["oldest_age_seconds"].is_null()
    } else {
        int(&v["oldest_age_seconds"], 61, 315_576_000).is_some()
    }
}
fn check_frame(v: &Value, limits: &Value, failure: &str, f: &mut Vec<String>, w: &mut Vec<String>) {
    let age = int(v, 0, 1_000_000_000_000);
    if age.is_none_or(|n| n as f64 > limits["failure"].as_f64().unwrap()) {
        f.push(failure.into());
    } else if age.unwrap() as f64 > limits["warning"].as_f64().unwrap() {
        w.push(format!(
            "{failure}: above the early-warning target but within the product freshness boundary."
        ));
    }
}
fn evaluate_snapshot(snapshot: &Value, t: &Value, standby: &Value) -> Value {
    let mut failures = Vec::new();
    let mut warnings = Vec::new();
    for name in ["public", "host", "spacetime", "pixel"] {
        push_if(
            &mut failures,
            !snapshot[name].is_object() || !yes(&snapshot[name]["ok"]),
            &format!("{name}_unhealthy"),
        );
    }
    if standby.as_array().is_none_or(|a| a.is_empty()) {
        warnings.push("Only one physical Pixel is configured; standby failover cannot be proven without a second device.".into());
    }
    let (rf, rw) = evaluate_resources(&snapshot["host"], &snapshot["pixel"], t);
    failures.extend(rf);
    warnings.extend(rw);
    let st = &snapshot["spacetime"];
    let pixel = &snapshot["pixel"];
    let health = &pixel["health"];
    let pixel_ok = yes(&pixel["ok"]);
    let mut active = None;
    let mut warm = false;
    let mut mode = "unknown";
    if pixel_ok {
        push_if(
            &mut failures,
            !truth(&pixel["portrait_lock"]["ok"]),
            "portrait_unlocked",
        );
        let lifecycle = &pixel["ticket_lifecycle"];
        if !lifecycle_valid(lifecycle) {
            failures.push("pixel_ticket_lifecycle_metrics_invalid".into());
        } else {
            push_if(
                &mut failures,
                as_int(&lifecycle["stuck_helper_count"], 0) > 0
                    || as_int(&lifecycle["stuck_start_stop_count"], 0) > 0,
                "pixel_ticket_lifecycle_stuck",
            );
        }
    }
    if yes(&st["ok"]) {
        let desired = truth(&st["desired_active"]);
        let viewers = as_int(&st["viewer_count"], 0);
        let is_active = desired || viewers > 0;
        active = Some(is_active);
        mode = if is_active {
            "active_viewer"
        } else {
            "no_active_viewer"
        };
        let relay = &st["relay_status"];
        let clients = as_int(&st["relay_video_clients"], 0);
        let warm_status = &st["page_open_warm"];
        warm = is_active
            && clients == 0
            && warm_status.is_object()
            && int(&warm_status["retained_sessions"], 1, 1_000_000).is_some()
            && int(&warm_status["remaining_millis"], 1, WARM_MAX).is_some();
        if warm {
            mode = "warm_no_viewer";
        }
        push_if(
            &mut failures,
            as_int(&st["pending_stream_commands"], 0) != 0,
            "pending_stream_commands",
        );
        if is_active {
            push_if(
                &mut failures,
                !desired || viewers < 1,
                "viewer_desired_state_mismatch",
            );
            push_if(
                &mut failures,
                int(&st["relay_report_age_millis"], 0, 1_000_000_000_000).is_none_or(|n| n > 5000),
                "relay_report_stale_or_unavailable",
            );
            push_if(
                &mut failures,
                st["phone_stream_state"] != "streaming" || !truth(&st["phone_desired_active"]),
                "phone_report_not_streaming",
            );
            push_if(
                &mut failures,
                !truth(&relay["phone_connected"]) || !truth(&relay["phone_desired"]),
                "relay_phone_state_mismatch",
            );
            push_if(
                &mut failures,
                s(&relay["phone_stream_state"]).to_lowercase() != "streaming",
                "relay_phone_not_streaming",
            );
            if warm {
                push_if(
                    &mut failures,
                    !contains(
                        "idle live waiting_keyframe stale_recovering",
                        s(&st["relay_stream_verdict"]),
                    ),
                    "warm_relay_unavailable",
                );
                warnings.push("Intentional page warmth has no browser video client; live picture freshness and browser/action proof were not assessed.".into());
            } else {
                push_if(
                    &mut failures,
                    st["relay_stream_verdict"] != "live" || clients < 1,
                    "relay_not_live",
                );
                push_if(
                    &mut failures,
                    !truth(&relay["live"]),
                    "relay_phone_state_mismatch",
                );
                if clients == 0 {
                    failures.push(
                        if warm_status.is_null() {
                            "warm_state_coverage_unavailable"
                        } else {
                            "warm_hold_missing_or_expired"
                        }
                        .into(),
                    );
                }
                check_frame(
                    &st["relay_last_frame_ago_millis"],
                    &t["relay_frame_age_millis"],
                    "relay_frame_stale",
                    &mut failures,
                    &mut warnings,
                );
            }
            if pixel_ok {
                let verdicts = if warm {
                    "live waiting_keyframe stale_recovering"
                } else {
                    "live"
                };
                push_if(
                    &mut failures,
                    health["session_state"] != "live"
                        || !truth(&health["stream_active"])
                        || !contains(verdicts, s(&health["stream_verdict"])),
                    "pixel_stream_not_live",
                );
                let hardware = &health["hardware_h264"];
                let pipeline = &health["stream_pipeline"];
                push_if(
                    &mut failures,
                    !truth(&hardware["active"])
                        || !truth(&hardware["available"])
                        || (!warm && hardware["visibility"] != "visible"),
                    "pixel_hardware_capture_not_visible",
                );
                push_if(
                    &mut failures,
                    as_int(&pipeline["video_clients"], 0) < 1
                        || !truth(&pipeline["encoder_running"]),
                    "pixel_pipeline_not_live",
                );
                if !warm {
                    check_frame(
                        &health["visible_frame_age_millis"],
                        &t["pixel_frame_age_millis"],
                        "pixel_frame_stale",
                        &mut failures,
                        &mut warnings,
                    );
                    check_frame(
                        &pipeline["last_frame_sent_ago_millis"],
                        &t["pixel_frame_age_millis"],
                        "pixel_pipeline_frame_stale",
                        &mut failures,
                        &mut warnings,
                    );
                }
                push_if(
                    &mut failures,
                    health["ticket_state"] != "live" || health["vivi_state"] != "TICKET_DETAIL",
                    "pixel_ticket_state_not_live",
                );
                push_if(
                    &mut failures,
                    health["recovery"].is_object() && recovery_failed(&health["recovery"]),
                    "pixel_recovery_failed",
                );
            }
            warnings.push("Authenticated browser proof was not collected by the standalone monitor; backend live proof remains authoritative for unattended checks.".into());
        } else {
            push_if(
                &mut failures,
                desired || viewers != 0,
                "idle_desired_state_mismatch",
            );
            if pixel_ok {
                push_if(
                    &mut failures,
                    truth(&health["stream_active"]),
                    "idle_pixel_stream_still_active",
                );
                push_if(
                    &mut failures,
                    health["stream_verdict"] != "idle",
                    "idle_pixel_stream_verdict",
                );
                let session = s(&health["session_state"]);
                let ticket = s(&health["ticket_state"]);
                push_if(
                    &mut failures,
                    !contains("idle stopped client_disconnected", session)
                        || !contains("idle stopped client_disconnected", ticket)
                        || ticket != session,
                    "idle_pixel_state_not_settled",
                );
                push_if(
                    &mut failures,
                    health["hardware_h264"]["active"].as_bool() != Some(false),
                    "idle_pixel_hardware_capture_active",
                );
                push_if(
                    &mut failures,
                    !yes(&health["hardware_h264"]["available"]),
                    "pixel_hardware_capture_unavailable",
                );
                push_if(
                    &mut failures,
                    !health["recovery"].is_object() || recovery_failed(&health["recovery"]),
                    "pixel_recovery_failed",
                );
            }
            push_if(
                &mut failures,
                (!st["relay_stream_verdict"].is_null()
                    && !["idle", ""].contains(&s(&st["relay_stream_verdict"])))
                    || as_int(&st["relay_video_clients"], 0) != 0,
                "idle_relay_still_active",
            );
        }
    }
    dedup(&mut failures);
    let stream = if warm {
        "warm"
    } else if active == Some(true) {
        "live"
    } else {
        "idle"
    };
    let degraded = !failures.is_empty();
    json!({"status":if degraded {"degraded".into()} else {format!("healthy_{stream}")},"viewer_mode":mode,"stream_verdict":if degraded {"degraded"} else {stream},"frame_age_millis":if active==Some(true) && !warm && pixel_ok {health["visible_frame_age_millis"].clone()} else {Value::Null},"failures":failures,"warnings":warnings})
}
fn atomic_write(path: &Path, value: &Value) -> io::Result<()> {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    fs::create_dir_all(parent)?;
    fs::set_permissions(parent, fs::Permissions::from_mode(0o755))?;
    let mut value = value.clone();
    value.sort_all_objects();
    let mut staging = tempfile::Builder::new()
        .prefix(&format!(
            ".{}.",
            path.file_name().unwrap_or_default().to_string_lossy()
        ))
        .suffix(".tmp")
        .tempfile_in(parent)?;
    serde_json::to_writer_pretty(&mut staging, &value)?;
    staging.write_all(b"\n")?;
    staging.flush()?;
    staging.as_file().sync_all()?;
    staging
        .as_file()
        .set_permissions(fs::Permissions::from_mode(0o644))?;
    staging.persist(path).map_err(|e| e.error)?;
    Ok(())
}
fn managed_name(name: &str) -> bool {
    let b = name.as_bytes();
    b.len() == 16
        && b[8] == b'T'
        && b[15] == b'Z'
        && b[..8].iter().chain(b[9..15].iter()).all(u8::is_ascii_digit)
}
fn prune_evidence(root: &Path, max: usize) -> io::Result<()> {
    if !root.exists() {
        return Ok(());
    }
    let mut directories = Vec::new();
    for entry in fs::read_dir(root)? {
        let path = entry?.path();
        if !path.is_dir() || !managed_name(&path.file_name().unwrap_or_default().to_string_lossy())
        {
            continue;
        }
        let entries = fs::read_dir(&path)?.collect::<Result<Vec<_>, _>>()?;
        if entries.len() == 1 && path.join("summary.json").is_file() {
            directories.push(path);
        }
    }
    directories.sort_by(|a, b| b.cmp(a));
    for path in directories.into_iter().skip(max) {
        fs::remove_file(path.join("summary.json"))?;
        fs::remove_dir(path)?;
    }
    Ok(())
}
fn write_report(report: &mut Value, output: &Path, evidence: &Path, max: usize) -> io::Result<()> {
    if contains("degraded blocked", s(&report["status"])) {
        let directory = evidence.join(s(&report["timestamp"]).replace(['-', ':'], ""));
        fs::create_dir_all(&directory)?;
        fs::set_permissions(evidence, fs::Permissions::from_mode(0o755))?;
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o755))?;
        report["evidence_directory"] = json!(directory.to_string_lossy());
        atomic_write(&directory.join("summary.json"), report)?;
    }
    prune_evidence(evidence, max)?;
    atomic_write(output, report)
}
fn main() {
    unsafe {
        signal(2, interrupt as *const () as usize);
        signal(15, interrupt as *const () as usize);
    }
    let mut config_path = None;
    let mut output = PathBuf::from("state/ticket-health-monitor/latest.json");
    let mut evidence = PathBuf::from("ops/evidence/ticket-health-monitor");
    let mut check = false;
    let mut snapshot = None;
    let mut argv = env::args()
        .skip(1)
        .flat_map(|arg| match arg.split_once('=') {
            Some((key, value)) if key.starts_with("--") => vec![key.to_owned(), value.to_owned()],
            _ => vec![arg],
        });
    while let Some(flag) = argv.next() {
        match flag.as_str() {
            "--check-config" => check = true,
            "--help" | "-h" => {
                println!(
                    "Read-only, privacy-bounded Ticket health collector and evaluator.\n--config PATH [--output PATH] [--evidence-root PATH] [--check-config] [--evaluate-snapshot PATH]"
                );
                return;
            }
            "--config" | "--output" | "--evidence-root" | "--evaluate-snapshot" => {
                let Some(path) = argv.next() else {
                    eprintln!("missing value for {flag}");
                    std::process::exit(2);
                };
                match flag.as_str() {
                    "--config" => config_path = Some(PathBuf::from(path)),
                    "--output" => output = path.into(),
                    "--evidence-root" => evidence = path.into(),
                    _ => snapshot = Some(PathBuf::from(path)),
                }
            }
            _ => {
                eprintln!("unknown argument: {flag}");
                std::process::exit(2);
            }
        }
    }
    let config = config_path
        .ok_or("--config is required".into())
        .and_then(|p| load_json(&p))
        .and_then(|c| {
            validate_config(&c)?;
            Ok(c)
        });
    let config = match config {
        Ok(c) => c,
        Err(e) => {
            eprintln!("ticket health monitor config error: {e}");
            std::process::exit(2);
        }
    };
    if check {
        println!(
            "{}",
            if yes(&config["enabled"]) {
                "Ticket health monitor configuration is valid and enabled."
            } else {
                "Ticket health monitor configuration is valid and remains paused."
            }
        );
        return;
    }
    if let Some(path) = snapshot {
        let value = load_json(&path).unwrap_or_else(|e| {
            eprintln!("ticket health monitor snapshot error: {e}");
            std::process::exit(1)
        });
        let mut verdict =
            evaluate_snapshot(&value, &config["thresholds"], &config["standby_devices"]);
        verdict.sort_all_objects();
        println!("{}", serde_json::to_string_pretty(&verdict).unwrap());
        std::process::exit(if s(&verdict["status"]).starts_with("healthy") {
            0
        } else {
            1
        });
    }
    if !yes(&config["enabled"]) {
        println!(
            "Ticket health monitor is paused in product configuration; no probes or repairs were run."
        );
        std::process::exit(3);
    }
    let started = Instant::now();
    let snapshot = json!({"public":collect_public(&config["public"]),"host":collect_host(&config),"spacetime":collect_spacetime(&config["spacetime"]),"pixel":collect_pixel(&config["pixel"])});
    if INTERRUPTED.load(Ordering::Relaxed) {
        std::process::exit(130);
    }
    let mut report =
        evaluate_snapshot(&snapshot, &config["thresholds"], &config["standby_devices"]);
    for (key, value) in [
        ("timestamp", json!(utc_now())),
        ("monitor_version", json!(VERSION)),
        ("actions", json!([])),
        ("checked_surfaces", snapshot),
        ("repair_attempted", json!(false)),
        ("repair_result", json!("disabled")),
        ("no_browser_profile_changes", json!(true)),
        (
            "duration_millis",
            json!(started.elapsed().as_millis() as u64),
        ),
    ] {
        report[key] = value;
    }
    if let Err(e) = write_report(
        &mut report,
        &output,
        &evidence,
        config["reporting"]["max_degraded_evidence_reports"]
            .as_u64()
            .unwrap() as usize,
    ) {
        eprintln!("ticket health monitor report error: {e}");
        std::process::exit(1);
    }
    println!(
        "Ticket health: {} ({})",
        s(&report["status"]),
        s(&report["viewer_mode"])
    );
    std::process::exit(if s(&report["status"]).starts_with("healthy") {
        0
    } else {
        1
    });
}
