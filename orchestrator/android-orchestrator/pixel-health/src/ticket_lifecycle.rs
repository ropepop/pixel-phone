//! Root lifecycle decisions; Android/settings/network tools remain effect adapters.
use crate::runtime_cleanup::{command_code, install_signal_handlers, interrupted};
use std::{
    env,
    ffi::OsString,
    fs,
    os::unix::ffi::OsStringExt,
    path::{Path, PathBuf},
};

const APP: &str = "lv.jolkins.pixelorchestrator";
fn command(values: &[&str], error: bool) -> (i32, Vec<u8>) {
    command_code(
        &values.iter().map(OsString::from).collect::<Vec<_>>(),
        None,
        true,
        error,
    )
}
fn effect(values: &[&str]) -> bool {
    command(values, false).0 == 0
}
fn text(values: &[&str]) -> String {
    String::from_utf8_lossy(&command(values, false).1)
        .trim_end_matches('\n')
        .replace('\r', "")
}
fn pause(value: &str) {
    effect(&["sleep", value]);
}
fn variable(key: &str, default: &str) -> String {
    env::var(key)
        .ok()
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| default.into())
}
fn number(key: &str, default: u64) -> u64 {
    let raw = variable(key, &default.to_string());
    if raw.bytes().all(|b| b.is_ascii_digit()) {
        raw.parse().unwrap_or(default)
    } else {
        default
    }
}
fn path(value: &Path) -> &str {
    value.to_str().unwrap_or("")
}
fn fail(message: &str) -> i32 {
    eprintln!("{message}");
    1
}

struct Runtime {
    base: PathBuf,
    conf: PathBuf,
    runtime: PathBuf,
    health: PathBuf,
}
impl Runtime {
    fn load() -> Option<Self> {
        let stack = PathBuf::from(variable("PIXEL_STACK_ROOT", "/data/local/pixel-stack"));
        let base = stack.join("apps/ticket-screen");
        let this = Self {
            conf: stack.join("conf/apps/ticket-screen.env"),
            runtime: base.join("env/ticket-screen.env"),
            health: PathBuf::from(variable(
                "PIXEL_TICKET_HEALTH_BIN",
                path(&stack.join("bin/pixel-ticket-health.sh")),
            )),
            base,
        };
        // Existing configuration is POSIX shell syntax. Source it once in its original order;
        // only the private pipe carries the exported environment, never a diagnostic/event.
        let (code, bytes) = command(
            &[
                "sh",
                "-c",
                "set -eu; set -a; for file do if [ -r \"$file\" ]; then . \"$file\"; fi; done; env -0",
                "ticket-env",
                path(&this.conf),
                path(&this.runtime),
            ],
            true,
        );
        if code != 0 {
            return None;
        }
        for row in bytes.split(|b| *b == 0) {
            if let Some(index) = row.iter().position(|b| *b == b'=') {
                // This executable has no concurrent environment readers/threads at setup.
                unsafe {
                    env::set_var(
                        OsString::from_vec(row[..index].to_vec()),
                        OsString::from_vec(row[index + 1..].to_vec()),
                    );
                }
            }
        }
        Some(this)
    }
    fn ready(&self, deep: bool) -> bool {
        if fs::File::open(&self.health).is_err() {
            return false;
        }
        let mut argv = vec!["sh", path(&self.health)];
        if deep {
            argv.push("--deep");
        }
        effect(&argv)
    }
    fn inputs_current(&self) -> bool {
        !self.conf.is_file()
            || matches!((fs::read(&self.conf),fs::read(&self.runtime)),(Ok(a),Ok(b)) if a==b)
    }
    fn open_ui(&self) {
        if ["1", "true", "TRUE", "yes", "YES", "on", "ON"]
            .contains(&variable("TICKET_SCREEN_OPEN_ORCHESTRATOR_ON_START", "0").as_str())
        {
            effect(&["am", "start", "-n", &format!("{APP}/.app.MainActivity")]);
        }
    }
    fn wait_ready(&self, seconds: u64, deep: bool) -> bool {
        for _ in 0..seconds.saturating_mul(5) {
            if interrupted() != 0 {
                return false;
            }
            if self.ready(deep) {
                return true;
            }
            pause("0.2");
        }
        false
    }
    fn start(&self, force: bool, deep: bool) -> i32 {
        if !force && self.ready(deep) && self.inputs_current() {
            self.open_ui();
            return 0;
        }
        for directory in ["run", "logs", "state", "env"] {
            if fs::create_dir_all(self.base.join(directory)).is_err() {
                return 1;
            }
        }
        effect(&[
            "chcon",
            "u:object_r:shell_data_file:s0",
            path(&self.base),
            path(&self.base.join("run")),
            path(&self.base.join("logs")),
            path(&self.base.join("state")),
            path(&self.base.join("env")),
        ]);
        let Some(_lock) = Lock::acquire(
            &self.base,
            number("TICKET_SCREEN_START_LOCK_WAIT_SECONDS", 10),
        ) else {
            if !force && self.wait_ready(1, deep) {
                return 0;
            }
            return fail("ticket start/stop is active but Ticket did not become ready");
        };
        if !force && self.ready(deep) && self.inputs_current() {
            self.open_ui();
            return 0;
        }
        if !self.runtime.is_file() && self.conf.is_file() {
            if !effect(&["cp", path(&self.conf), path(&self.runtime)])
                || !effect(&["chmod", "600", path(&self.runtime)])
            {
                return 1;
            }
            effect(&[
                "chcon",
                "u:object_r:shell_data_file:s0",
                path(&self.runtime),
            ]);
        }
        let code = command(
            &[
                "am",
                "start-foreground-service",
                "-n",
                &format!("{APP}/.app.SupervisorService"),
                "-a",
                "lv.jolkins.pixelorchestrator.action.TICKET_START_SERVER",
                "--es",
                "orchestrator_action",
                "ticket_start_server",
            ],
            true,
        )
        .0;
        if code != 0 {
            return code;
        }
        self.open_ui();
        if self.wait_ready(number("TICKET_SCREEN_START_TIMEOUT_SECONDS", 15), deep) {
            0
        } else {
            fail("Ticket did not become ready")
        }
    }
    fn listening(&self) -> bool {
        let port = variable("TICKET_SCREEN_PORT", "9388");
        let bytes = command(&["ss", "-ltn"], false).1;
        String::from_utf8_lossy(&bytes).lines().any(|line| {
            line.split_whitespace().any(|word| {
                word.ends_with(&format!(":{port}")) || word.ends_with(&format!(".{port}"))
            })
        })
    }
    fn service_active(&self) -> bool {
        let (code, bytes) = command(
            &[
                "dumpsys",
                "activity",
                "services",
                &format!("{APP}/.app.ticket.TicketStreamService"),
            ],
            false,
        );
        code != 0
            || bytes
                .windows(b"TicketStreamService".len())
                .any(|s| s == b"TicketStreamService")
    }
    fn stop(&self, deep: bool) -> i32 {
        if fs::create_dir_all(self.base.join("run")).is_err() {
            return 1;
        }
        let Some(_lock) = Lock::acquire(
            &self.base,
            number("TICKET_SCREEN_STOP_LOCK_WAIT_SECONDS", 10),
        ) else {
            return fail("ticket start/stop lock remained active");
        };
        effect(&[
            "am",
            "start-foreground-service",
            "-n",
            &format!("{APP}/.app.SupervisorService"),
            "-a",
            "lv.jolkins.pixelorchestrator.action.TICKET_STOP_SERVER",
            "--es",
            "orchestrator_action",
            "ticket_stop_server",
        ]);
        for _ in 0..25 {
            if !self.listening() || interrupted() != 0 {
                break;
            }
            pause("0.2");
        }
        if self.listening() && deep {
            effect(&["am", "force-stop", APP]);
            pause("0.2");
        }
        if self.listening() {
            return fail("Ticket runtime did not stop cleanly");
        }
        for _ in 0..number("TICKET_SCREEN_STOP_SERVICE_WAIT_ATTEMPTS", 100) {
            if !self.service_active() || interrupted() != 0 {
                break;
            }
            pause("0.2");
        }
        if self.service_active() {
            return fail(
                "Ticket service did not quiesce; secure capture saved state was not touched",
            );
        }
        if self.restore() {
            0
        } else {
            fail(
                "Ticket secure capture settings were not exactly restored; saved state was retained",
            )
        }
    }
    fn restore(&self) -> bool {
        if interrupted() != 0 {
            return false;
        }
        let record = self.base.join("state/ro-debuggable-before-ticket");
        let debug = || text(&["getprop", "ro.debuggable"]);
        let secure = || text(&["settings", "get", "secure", "disable_secure_windows"]);
        let valid_debug = |s: &str| ["0", "1"].contains(&s);
        let valid_secure = |s: &str| ["0", "1", "null"].contains(&s);
        if !record.exists() {
            let (d, s) = (debug(), secure());
            if !valid_debug(&d) || !valid_secure(&s) {
                return false;
            }
            if s != "1" {
                return true;
            }
            effect(&["settings", "put", "secure", "disable_secure_windows", "0"]);
            effect(&["resetprop", "ro.debuggable", "0"]);
            let (current_debug, current_secure) = (debug(), secure());
            return current_debug == "0" && current_secure == "0";
        }
        let Ok(bytes) = fs::read(&record) else {
            return false;
        };
        let body = String::from_utf8_lossy(&bytes);
        let mut lines = body.split('\n');
        let saved_debug = lines.next().unwrap_or("").replace('\r', "");
        let mut saved_secure = lines.next().unwrap_or("").replace('\r', "");
        if !valid_debug(&saved_debug) {
            return false;
        }
        if saved_secure.is_empty() {
            saved_secure = "0".into();
            let temporary = PathBuf::from(format!("{}.tmp.{}", path(&record), std::process::id()));
            if fs::write(&temporary, format!("{saved_debug}\n{saved_secure}\n")).is_err() {
                return false;
            }
            effect(&["chmod", "600", path(&temporary)]);
            if !effect(&["mv", path(&temporary), path(&record)]) {
                effect(&["rm", "-f", path(&temporary)]);
                return false;
            }
        } else if !valid_secure(&saved_secure) {
            return false;
        }
        if saved_secure == "null" {
            effect(&["settings", "delete", "secure", "disable_secure_windows"]);
        } else {
            effect(&[
                "settings",
                "put",
                "secure",
                "disable_secure_windows",
                &saved_secure,
            ]);
        }
        effect(&["resetprop", "ro.debuggable", &saved_debug]);
        let (current_debug, current_secure) = (debug(), secure());
        current_debug == saved_debug
            && current_secure == saved_secure
            && effect(&["rm", "-f", path(&record)])
            && !record.exists()
    }
}

struct Lock {
    directory: PathBuf,
    owner: PathBuf,
    held: bool,
}
impl Lock {
    fn acquire(base: &Path, seconds: u64) -> Option<Self> {
        let mut this = Self {
            directory: base.join("run/ticket-screen-start-stop.lock"),
            owner: base.join("run/ticket-screen-start-stop.lock/owner.pid"),
            held: false,
        };
        for _ in 0..seconds.saturating_mul(5).max(1) {
            if interrupted() != 0 {
                return None;
            }
            if fs::create_dir(&this.directory).is_err() {
                pause("0.1");
                let before = this.read_owner();
                if this.active(&before)
                    || before != this.read_owner()
                    || !effect(&["rm", "-f", path(&this.owner)])
                    || fs::remove_dir(&this.directory).is_err()
                    || fs::create_dir(&this.directory).is_err()
                {
                    pause("0.2");
                    continue;
                }
            }
            if fs::write(&this.owner, format!("{}\n", std::process::id())).is_err() {
                let _ = fs::remove_dir(&this.directory);
                return None;
            }
            this.held = true;
            return Some(this);
        }
        None
    }
    fn read_owner(&self) -> String {
        fs::read_to_string(&self.owner)
            .unwrap_or_default()
            .split('\n')
            .next()
            .unwrap_or("")
            .into()
    }
    fn alive(pid: &str) -> bool {
        !pid.is_empty()
            && pid.bytes().all(|b| b.is_ascii_digit())
            && effect(&["sh", "-c", "kill -0 \"$1\"", "ticket-owner", pid])
    }
    fn active(&self, pid: &str) -> bool {
        if !Self::alive(pid) {
            return false;
        }
        let cmdline = PathBuf::from(variable("TICKET_LOCK_PROC_ROOT", "/proc"))
            .join(pid)
            .join("cmdline");
        if !effect(&["sh", "-c", "test -r \"$1\"", "ticket-owner", path(&cmdline)]) {
            return true;
        }
        let timeout = variable("TICKET_LOCK_TIMEOUT_BIN", "timeout");
        let (code, bytes) = command(
            &[
                &timeout,
                "1",
                "sh",
                "-c",
                "tr '\\000' ' ' < \"$1\"",
                "ticket-owner",
                path(&cmdline),
            ],
            false,
        );
        if code != 0 {
            return Self::alive(pid);
        }
        let command = String::from_utf8_lossy(&bytes);
        command.contains("pixel-ticket-start.sh") || command.contains("pixel-ticket-stop.sh")
    }
}
impl Drop for Lock {
    fn drop(&mut self) {
        if self.held && self.read_owner() == std::process::id().to_string() {
            // Release is required even after a signal; never recursively delete contested state.
            let argv = ["rm", "-f", path(&self.owner)]
                .iter()
                .map(OsString::from)
                .collect::<Vec<_>>();
            if command_code(&argv, None, false, false).0 == 0 {
                let _ = fs::remove_dir(&self.directory);
            }
        }
    }
}

fn available(tool: &str) -> bool {
    effect(&[
        "sh",
        "-c",
        "command -v \"$1\" >/dev/null",
        "ticket-tool",
        tool,
    ])
}
fn health(runtime: &Runtime) -> i32 {
    let raw = variable("TICKET_SCREEN_PORT", "9388");
    if raw.is_empty()
        || !raw.bytes().all(|b| b.is_ascii_digit())
        || !matches!(raw.parse::<u32>(), Ok(1..=65535))
    {
        return 1;
    }
    if available("curl") {
        return i32::from(
            text(&[
                "curl",
                "-sS",
                "-o",
                "/dev/null",
                "-w",
                "%{http_code}",
                "--connect-timeout",
                "1",
                "--max-time",
                "1",
                &format!("http://127.0.0.1:{raw}/api/v1/health"),
            ]) != "200",
        );
    }
    if available("nc") {
        // The shell only connects stdin to the upstream network adapter; this owner
        // chooses its fence and classifies the returned first response line.
        let script = if available("timeout") {
            "printf 'GET /api/v1/health HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' | timeout 2 nc -w 1 127.0.0.1 \"$1\""
        } else {
            "printf 'GET /api/v1/health HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' | nc -w 1 127.0.0.1 \"$1\""
        };
        let response = text(&["sh", "-c", script, "ticket-http", &raw]);
        let first = response.lines().next().unwrap_or("");
        return i32::from(!(first.starts_with("HTTP/") && first.contains(" 200 ")));
    }
    i32::from(!available("ss") || !runtime.listening())
}
pub fn main(mode: &str) -> i32 {
    install_signal_handlers();
    let mut force = false;
    let mut deep = false;
    let action = mode
        .strip_prefix("pixel-ticket-")
        .unwrap()
        .strip_suffix(".sh")
        .unwrap();
    for arg in env::args().skip(2) {
        match arg.as_str() {
            "--force" if action == "start" => force = true,
            "--deep" | "--full" => {
                deep = true;
                if action == "start" {
                    force = true;
                }
            }
            _ => {
                eprintln!("unsupported ticket {action} argument: {arg}");
                return 2;
            }
        }
    }
    let Some(runtime) = Runtime::load() else {
        return 1;
    };
    let code = match action {
        "start" => runtime.start(force, deep),
        "stop" => runtime.stop(deep),
        _ => health(&runtime),
    };
    if interrupted() != 0 { 143 } else { code }
}
