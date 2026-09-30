//! Protected, allowlisted runtime cleanup. Android owns scheduling and its mutation lock.
use std::{
    collections::HashSet,
    env,
    ffi::OsString,
    fs,
    io::{Read, Write},
    os::unix::{
        ffi::{OsStrExt, OsStringExt},
        process::CommandExt,
    },
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::atomic::{AtomicI32, Ordering},
    thread,
    time::Duration,
};

static INTERRUPTED: AtomicI32 = AtomicI32::new(0);
unsafe extern "C" {
    fn signal(sig: i32, handler: usize) -> usize;
    fn kill(pid: i32, sig: i32) -> i32;
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn prctl(option: i32, ...) -> i32;
    #[cfg(any(target_os = "linux", target_os = "android"))]
    fn getppid() -> i32;
}
extern "C" fn interrupt(sig: i32) {
    INTERRUPTED.store(sig, Ordering::Relaxed);
}

// Platform tools perform effects only; this owner decides every target and transition.
pub(super) fn command_code(
    args: &[OsString],
    output: Option<fs::File>,
    cancellable: bool,
    inherit_error: bool,
) -> (i32, Vec<u8>) {
    if cancellable && INTERRUPTED.load(Ordering::Relaxed) != 0 {
        return (1, Vec::new());
    }
    let mut cmd = Command::new(&args[0]);
    cmd.args(&args[1..]).stderr(if inherit_error {
        Stdio::inherit()
    } else {
        Stdio::null()
    });
    let capture = output.is_none();
    cmd.stdout(output.map_or_else(Stdio::piped, Stdio::from));
    #[cfg(any(target_os = "linux", target_os = "android"))]
    let parent = std::process::id() as i32;
    unsafe {
        cmd.pre_exec(move || {
            // Keep the root executor's existing process group: its complete
            // cancellation must include nested sh/root-recheck descendants.
            #[cfg(any(target_os = "linux", target_os = "android"))]
            {
                if prctl(1, 15) != 0 || getppid() != parent {
                    return Err(std::io::Error::last_os_error());
                }
            }
            Ok(())
        });
    }
    let Ok(mut child) = cmd.spawn() else {
        return (127, Vec::new());
    };
    let reader = if capture {
        let mut stdout = child.stdout.take().unwrap();
        Some(thread::spawn(move || {
            let mut bytes = Vec::new();
            let _ = stdout.read_to_end(&mut bytes);
            bytes
        }))
    } else {
        None
    };
    let success = loop {
        if cancellable && INTERRUPTED.load(Ordering::Relaxed) != 0 {
            unsafe { kill(child.id() as i32, 15) };
            for _ in 0..50 {
                if !matches!(child.try_wait(), Ok(None)) {
                    break;
                }
                thread::sleep(Duration::from_millis(10));
            }
            unsafe { kill(child.id() as i32, 9) };
            let _ = child.wait();
            break 1;
        }
        match child.try_wait() {
            Ok(Some(status)) => break status.code().unwrap_or(1),
            Ok(None) => thread::sleep(Duration::from_millis(10)),
            Err(_) => break 1,
        }
    };
    let bytes = reader.and_then(|r| r.join().ok()).unwrap_or_default();
    (success, bytes)
}
fn command(args: &[OsString], output: Option<fs::File>, cancellable: bool) -> (bool, Vec<u8>) {
    let (code, bytes) = command_code(args, output, cancellable, false);
    (code == 0, bytes)
}
pub(super) fn interrupted() -> i32 {
    INTERRUPTED.load(Ordering::Relaxed)
}
pub(super) fn install_signal_handlers() {
    for sig in [1, 2, 15] {
        unsafe {
            signal(sig, interrupt as *const () as usize);
        }
    }
}
fn args(values: &[&str]) -> Vec<OsString> {
    values.iter().map(|s| (*s).into()).collect()
}
fn effect(values: &[&str], cancellable: bool) -> bool {
    command(&args(values), None, cancellable).0
}
fn path_args(values: &[&str], paths: &[&Path]) -> Vec<OsString> {
    let mut argv = args(values);
    argv.extend(paths.iter().map(|p| p.as_os_str().to_owned()));
    argv
}
fn output_paths(output: &[u8]) -> impl Iterator<Item = PathBuf> + '_ {
    output
        .split(|c| *c == b'\n')
        .filter(|p| !p.is_empty())
        .map(|p| PathBuf::from(OsString::from_vec(p.to_vec())))
}
fn suffixed(path: &Path, suffix: &str) -> PathBuf {
    let mut path = path.as_os_str().to_owned();
    path.push(suffix);
    path.into()
}
fn bytes(path: &Path) -> u64 {
    let Ok(meta) = fs::metadata(path) else {
        return 0;
    };
    if meta.is_dir() {
        String::from_utf8_lossy(&command(&path_args(&["du", "-sk"], &[path]), None, true).1)
            .split_whitespace()
            .next()
            .and_then(|s| s.parse::<u64>().ok())
            .unwrap_or(0)
            .saturating_mul(1024)
    } else {
        meta.len()
    }
}
fn record(kind: &str, category: &str, size: u64, path: &Path, detail: &str) {
    if INTERRUPTED.load(Ordering::Relaxed) != 0 {
        return;
    }
    let mut stdout = std::io::stdout().lock();
    let _ = stdout.write_all(format!("{kind}\t{category}\t{size}\t").as_bytes());
    let _ = stdout.write_all(path.as_os_str().as_bytes());
    let _ = stdout.write_all(format!("\t{detail}\n").as_bytes());
    let _ = stdout.flush();
}

struct Cleanup {
    stack: PathBuf,
    cache: PathBuf,
    termux: PathBuf,
    tmp: PathBuf,
    db: PathBuf,
    package: String,
    recheck: String,
    protected: HashSet<OsString>,
    retained: HashSet<PathBuf>,
    artifact_age: String,
    log_age: String,
    db_limit: u64,
    log_limit: u64,
    total_limit: u64,
    retired_dns: bool,
    frequent: bool,
    dry_run: bool,
    rotation_active: bool,
}
impl Cleanup {
    fn parse() -> Result<Self, String> {
        let mut this = Self {
            stack: "/data/local/pixel-stack".into(),
            cache: "/data/user/0/lv.jolkins.pixelorchestrator/cache".into(),
            termux: "/data/user/0/com.termux/files/home".into(),
            tmp: "/data/local/tmp".into(),
            db: "/data/user_de/0/giilmkonhuutj.ih.wb/databases/sulogs.db".into(),
            package: "giilmkonhuutj.ih.wb".into(),
            recheck: "su -c id -u".into(),
            protected: HashSet::new(),
            retained: HashSet::new(),
            artifact_age: "30".into(),
            log_age: "30".into(),
            db_limit: 33_554_432,
            log_limit: 1_048_576,
            total_limit: 33_554_432,
            retired_dns: false,
            frequent: false,
            dry_run: false,
            rotation_active: false,
        };
        let mut protected = None;
        let mut argv = env::args().skip(1);
        while let Some(arg) = argv.next() {
            match arg.as_str() {
                "--dry-run" => this.dry_run = true,
                "--frequent" => this.frequent = true,
                "--retired-dns" => this.retired_dns = true,
                "--protected-list" => protected = argv.next().map(PathBuf::from),
                "--stack-base" => this.stack = argv.next().unwrap_or_default().into(),
                "--orchestrator-cache" => this.cache = argv.next().unwrap_or_default().into(),
                "--termux-home" => this.termux = argv.next().unwrap_or_default().into(),
                "--local-tmp" => this.tmp = argv.next().unwrap_or_default().into(),
                "--superuser-log-db" => this.db = argv.next().unwrap_or_default().into(),
                "--superuser-package" => this.package = argv.next().unwrap_or_default(),
                "--root-recheck-command" => this.recheck = argv.next().unwrap_or_default(),
                "--artifact-age-days" => this.artifact_age = argv.next().unwrap_or_default(),
                "--log-age-days" => this.log_age = argv.next().unwrap_or_default(),
                "--superuser-log-max-bytes" | "--known-log-max-bytes" | "--stack-log-max-bytes" => {
                    let value = argv.next().unwrap_or_default();
                    let error = match arg.as_str() {
                        "--superuser-log-max-bytes" => {
                            "superuser log max bytes must be a whole number"
                        }
                        "--known-log-max-bytes" => "known log max bytes must be a whole number",
                        _ => "stack log max bytes must be a whole number",
                    };
                    if value.is_empty() || !value.bytes().all(|c| c.is_ascii_digit()) {
                        return Err(error.into());
                    }
                    let value = value.parse().map_err(|_| error.to_string())?;
                    match arg.as_str() {
                        "--superuser-log-max-bytes" => this.db_limit = value,
                        "--known-log-max-bytes" => this.log_limit = value,
                        _ => this.total_limit = value,
                    }
                }
                _ => return Err(format!("Unsupported argument: {arg}")),
            }
        }
        let path = protected
            .filter(|p| p.is_file())
            .ok_or("Protected path list is required")?;
        // grep -Fqx treats a trailing newline as a separator, not part of the path.
        this.protected = fs::read(path)
            .map_err(|_| "Protected path list is required")?
            .split(|c| *c == b'\n')
            .map(|p| OsString::from_vec(p.to_vec()))
            .collect();
        for (value, error) in [
            (
                &this.artifact_age,
                "artifact age must be a whole number of days",
            ),
            (&this.log_age, "log age must be a whole number of days"),
        ] {
            if value.is_empty() || !value.bytes().all(|c| c.is_ascii_digit()) {
                return Err(error.into());
            }
        }
        Ok(this)
    }
    fn delete(&self, category: &str, path: &Path, detail: &str) {
        if !path.exists() {
            return;
        }
        let size = bytes(path);
        let (kind, detail) = if self.protected.contains(path.as_os_str()) {
            ("SKIP", "protected".into())
        } else if self.retained.contains(path) {
            ("SKIP", "retained_generation".into())
        } else if self.dry_run {
            ("CANDIDATE", detail.into())
        } else if command(&path_args(&["rm", "-rf"], &[path]), None, true).0 {
            ("DELETE", detail.into())
        } else {
            ("FAIL", format!("delete_failed:{detail}"))
        };
        record(kind, category, size, path, &detail);
    }
    fn truncate(&self, category: &str, path: &Path, detail: &str) {
        if INTERRUPTED.load(Ordering::Relaxed) != 0 {
            return;
        }
        if !path.is_file() {
            return;
        }
        let size = bytes(path);
        let (kind, detail) = if self.protected.contains(path.as_os_str()) {
            ("SKIP", "protected".into())
        } else if self.dry_run {
            ("CANDIDATE", detail.into())
        } else if fs::File::create(path).is_ok() {
            ("DELETE", format!("truncated:{detail}"))
        } else {
            ("FAIL", format!("truncate_failed:{detail}"))
        };
        record(kind, category, size, path, &detail);
    }
    fn scan(&self, category: &str, detail: &str, root: &Path, tail: &[&str]) {
        let mut argv = path_args(&["find"], &[root]);
        argv.extend(args(tail));
        for path in output_paths(&command(&argv, None, true).1) {
            if INTERRUPTED.load(Ordering::Relaxed) != 0 {
                break;
            }
            self.delete(category, &path, detail);
        }
    }
    fn old_scan(&self, category: &str, detail: &str, root: &Path, tail: &[&str], days: &str) {
        let age = format!("+{days}");
        let mut argv = tail.to_vec();
        argv.extend(["-mtime", &age]);
        self.scan(category, detail, root, &argv);
    }
    fn logs(&self) -> Vec<PathBuf> {
        [
            "logs/adguardhome-service-loop.log",
            "vpn/logs/pixel-vpn-service-loop.log",
            "vpn/logs/tailscaled.log",
            "ssh/logs/pixel-ssh-service-loop.log",
            "ssh/logs/dropbear.log",
            "logs/ddns-runner.log",
            "apps/site-notifications/logs/daemon.log",
            "apps/site-notifications/logs/service-loop.log",
            "apps/subscription-bot/logs/subscription-bot.log",
            "apps/subscription-bot/logs/subscription-bot-cloudflared.log",
            "apps/subscription-bot/logs/subscription-web-tunnel-service-loop.log",
            "apps/subscription-bot/logs/service-loop.log",
            "apps/train-bot/logs/train-bot.log",
            "apps/train-bot/logs/train-bot-cloudflared.log",
            "apps/train-bot/logs/train-web-tunnel-service-loop.log",
            "apps/train-bot/logs/service-loop.log",
            "apps/satiksme-bot/logs/satiksme-bot.log",
            "apps/satiksme-bot/logs/satiksme-bot-cloudflared.log",
            "apps/satiksme-bot/logs/satiksme-web-tunnel-service-loop.log",
            "apps/satiksme-bot/logs/service-loop.log",
            "apps/ticket-screen/logs/ticket-screen-cloudflared.log",
            "apps/ticket-screen/logs/ticket-web-tunnel-service-loop.log",
        ]
        .iter()
        .map(|p| self.stack.join(p))
        .collect()
    }
    fn tail_to(&self, path: &Path, destination: &Path) -> bool {
        if INTERRUPTED.load(Ordering::Relaxed) != 0 {
            return false;
        }
        fs::File::create(destination).is_ok_and(|out| {
            command(
                &args(&[
                    "tail",
                    "-c",
                    &self.log_limit.to_string(),
                    &path.to_string_lossy(),
                ]),
                Some(out),
                true,
            )
            .0
        })
    }
    fn rotate_log(&self, path: &Path, rotation: bool) {
        if !path.is_file() {
            return;
        }
        let size = bytes(path);
        if size <= self.log_limit {
            return;
        }
        let category = if rotation {
            "runtime_log_rotation"
        } else {
            "runtime_log"
        };
        if self.protected.contains(path.as_os_str()) {
            record("SKIP", category, size, path, "protected");
            return;
        }
        if self.dry_run {
            record(
                "CANDIDATE",
                category,
                size,
                path,
                if rotation {
                    "oversize_rotation"
                } else {
                    "oversize:known_runtime_log"
                },
            );
            return;
        }
        let tmp = suffixed(path, ".pixel-cleanup-tmp");
        let target = if rotation {
            path.to_path_buf()
        } else {
            suffixed(path, ".1")
        };
        let ok = self.tail_to(path, &tmp)
            && effect(
                &[
                    "mv",
                    "-f",
                    &tmp.to_string_lossy(),
                    &target.to_string_lossy(),
                ],
                true,
            )
            && (rotation
                || (INTERRUPTED.load(Ordering::Relaxed) == 0 && fs::File::create(path).is_ok()));
        if ok {
            record(
                "DELETE",
                category,
                size - self.log_limit,
                path,
                if rotation {
                    "bounded_rotation"
                } else {
                    "rotated:known_runtime_log"
                },
            );
        } else {
            effect(&["rm", "-f", &tmp.to_string_lossy()], false);
            record(
                "FAIL",
                category,
                size,
                path,
                if rotation {
                    "rotation_bound_failed"
                } else {
                    "rotate_failed:known_runtime_log"
                },
            );
        }
    }
    fn log_total(&self, extras: bool) -> u64 {
        let mut total = 0u64;
        for path in self.logs() {
            for suffix in if extras {
                &["", ".1", ".2", ".3", ".old"][..]
            } else {
                &["", ".1"][..]
            } {
                total = total.saturating_add(bytes(&suffixed(&path, suffix)));
            }
            if extras {
                let pattern = format!("{}.bak*", path.file_name().unwrap().to_string_lossy());
                let listing = command(
                    &args(&[
                        "find",
                        &path.parent().unwrap().to_string_lossy(),
                        "-mindepth",
                        "1",
                        "-maxdepth",
                        "1",
                        "-name",
                        &pattern,
                    ]),
                    None,
                    true,
                )
                .1;
                for p in output_paths(&listing) {
                    total = total.saturating_add(bytes(&p))
                }
            }
        }
        total
    }
    fn maintain_logs(&self) {
        for path in self.logs() {
            self.rotate_log(&path, false);
            self.rotate_log(&suffixed(&path, ".1"), true);
            for suffix in [".2", ".3", ".old"] {
                self.delete(
                    "runtime_log_rotation",
                    &suffixed(&path, suffix),
                    "extra_rotation",
                )
            }
            let pattern = format!("{}.bak*", path.file_name().unwrap().to_string_lossy());
            self.scan(
                "runtime_log_rotation",
                "extra_backup_rotation",
                path.parent().unwrap(),
                &[
                    "-mindepth",
                    "1",
                    "-maxdepth",
                    "1",
                    "-type",
                    "f",
                    "-name",
                    &pattern,
                ],
            );
        }
        let mut remaining = self.log_total(false);
        for rotation in [true, false] {
            for path in self.logs() {
                if remaining <= self.total_limit {
                    break;
                }
                let path = if rotation {
                    suffixed(&path, ".1")
                } else {
                    path
                };
                let size = bytes(&path);
                if size == 0 {
                    continue;
                }
                if rotation {
                    self.delete("runtime_log_total", &path, "over_total_limit_rotation")
                } else {
                    self.truncate("runtime_log_total", &path, "over_total_limit_active")
                }
                if !self.protected.contains(path.as_os_str())
                    && (self.dry_run
                        || if rotation {
                            !path.exists()
                        } else {
                            bytes(&path) == 0
                        })
                {
                    remaining = remaining.saturating_sub(size);
                }
            }
        }
    }
    fn recheck(&self) -> bool {
        effect(&["sh", "-c", &self.recheck], true)
    }
    fn db_bytes(&self) -> u64 {
        ["", "-wal", "-shm"]
            .iter()
            .map(|s| bytes(&suffixed(&self.db, s)))
            .sum()
    }
    fn backups_exist(&self) -> bool {
        ["", "-wal", "-shm"]
            .iter()
            .any(|s| suffixed(&self.db, &format!(".pixel-cleanup-backup{s}")).exists())
    }
    fn delete_backups(&self) -> bool {
        let mut argv = args(&["rm", "-f"]);
        argv.extend(
            ["", "-wal", "-shm"]
                .map(|s| suffixed(&self.db, &format!(".pixel-cleanup-backup{s}")).into_os_string()),
        );
        command(&argv, None, false).0
    }
    fn rollback(&mut self) -> bool {
        if !self.rotation_active {
            return true;
        }
        let mut ok = true;
        for suffix in ["", "-wal", "-shm"] {
            let backup = suffixed(&self.db, &format!(".pixel-cleanup-backup{suffix}"));
            let target = suffixed(&self.db, suffix);
            if backup.exists() {
                ok &= effect(&["rm", "-f", &target.to_string_lossy()], false);
                ok &= effect(
                    &["mv", &backup.to_string_lossy(), &target.to_string_lossy()],
                    false,
                );
            }
        }
        self.rotation_active = false;
        ok
    }
    fn recover(&mut self) -> bool {
        if !self.backups_exist() {
            return true;
        }
        self.rotation_active = true;
        let (kind, detail, ok) = if self.db.exists() && self.recheck() {
            self.rotation_active = false;
            if self.delete_backups() {
                ("DELETE", "reconciled_interrupted_success", true)
            } else {
                ("FAIL", "interrupted_backup_delete_failed", false)
            }
        } else if self.rollback() {
            ("SKIP", "recovered_interrupted_rotation", true)
        } else {
            ("FAIL", "interrupted_rotation_recovery_failed", false)
        };
        record(kind, "superuser_log_db", 0, &self.db, detail);
        ok
    }
    fn maintain_db(&mut self) {
        if !self.recover() || INTERRUPTED.load(Ordering::Relaxed) != 0 {
            return;
        }
        let size = self.db_bytes();
        let missing = ["", "-wal", "-shm"]
            .iter()
            .all(|s| !suffixed(&self.db, s).exists());
        let early = if missing {
            Some(("SKIP", "missing"))
        } else if self.protected.contains(self.db.as_os_str()) {
            Some(("SKIP", "protected"))
        } else if size <= self.db_limit {
            Some(("SKIP", "within_size_limit"))
        } else if self.dry_run {
            Some(("CANDIDATE", "over_size_limit"))
        } else if !self.recheck() {
            Some(("FAIL", "root_recheck_failed_before_rotation"))
        } else {
            None
        };
        if let Some((kind, detail)) = early {
            record(kind, "superuser_log_db", size, &self.db, detail);
            return;
        }
        effect(&["am", "force-stop", &self.package], true);
        self.rotation_active = true;
        let mut prepared = true;
        for suffix in ["", "-wal", "-shm"] {
            let source = suffixed(&self.db, suffix);
            let backup = suffixed(&self.db, &format!(".pixel-cleanup-backup{suffix}"));
            if source.exists()
                && !effect(
                    &["mv", &source.to_string_lossy(), &backup.to_string_lossy()],
                    true,
                )
            {
                prepared = false;
                break;
            }
        }
        if INTERRUPTED.load(Ordering::Relaxed) != 0 {
            return;
        }
        let detail = if !prepared {
            if self.rollback() {
                "rotation_prepare_failed_rolled_back"
            } else {
                "rotation_prepare_and_rollback_failed"
            }
        } else if !self.recheck() {
            if self.rollback() {
                "root_recheck_failed_rolled_back"
            } else {
                "root_recheck_and_rollback_failed"
            }
        } else {
            self.rotation_active = false;
            self.delete_backups();
            if self.backups_exist() {
                "backup_delete_failed"
            } else {
                "rotated_over_size_limit"
            }
        };
        record(
            if detail == "rotated_over_size_limit" {
                "DELETE"
            } else {
                "FAIL"
            },
            "superuser_log_db",
            size,
            &self.db,
            detail,
        );
    }
    fn retain_newest(&mut self, root: &Path, prefix: &str, notifier: bool) {
        let Ok(entries) = fs::read_dir(root) else {
            return;
        };
        let mut paths: Vec<_> = entries
            .filter_map(Result::ok)
            .map(|e| e.path())
            .filter(|p| {
                p.file_name()
                    .is_some_and(|s| s.to_string_lossy().starts_with(prefix))
            })
            .collect();
        paths.sort_by(|a, b| {
            let mtime = |p: &Path| fs::symlink_metadata(p).ok().and_then(|m| m.modified().ok());
            mtime(b).cmp(&mtime(a)).then_with(|| a.cmp(b))
        });
        let mut count = 0;
        for path in paths {
            let name = path.file_name().unwrap().to_string_lossy();
            if notifier {
                let Some(stamp) = name
                    .strip_prefix("site-notifier-bundle-")
                    .and_then(|s| s.strip_suffix(".tar"))
                else {
                    continue;
                };
                let Some(date) = stamp.strip_prefix("site-notifier-") else {
                    continue;
                };
                if !date.is_ascii()
                    || date.len() != 16
                    || date.as_bytes()[8] != b'T'
                    || !date.ends_with('Z')
                    || !date[..8]
                        .bytes()
                        .chain(date[9..15].bytes())
                        .all(|c| c.is_ascii_digit())
                {
                    continue;
                }
                for peer in [
                    root.join(format!("source-{stamp}.tar")),
                    root.parent()
                        .unwrap()
                        .join("component-releases")
                        .join(format!("site_notifier-{stamp}")),
                ] {
                    if peer.exists() {
                        self.retained.insert(peer);
                    }
                }
            }
            self.retained.insert(path);
            count += 1;
            if count == 2 {
                break;
            }
        }
    }
    fn artifacts(&mut self) {
        let notifier = self
            .termux
            .join("telegram-train-app/workloads/site-notifications/.artifacts/site-notifier");
        self.retain_newest(&notifier, "site-notifier-bundle-site-notifier-", true);
        let local = self
            .termux
            .join("telegram-train-app/orchestrator/.artifacts/runtime-local");
        self.retain_newest(&local, "", false);
        self.retain_newest(&self.termux.clone(), "site-notifications-build", false);
        let first = ["-mindepth", "1", "-maxdepth", "1"];
        for (detail, tail) in [
            (
                "pixel_orchestrator_runtime_dir",
                vec!["-type", "d", "-name", "pixel-orchestrator-runtime-*"],
            ),
            (
                "orchestrator_runtime_dir",
                vec!["-type", "d", "-name", "orchestrator-runtime-*"],
            ),
            (
                "site_notifier_build_dir",
                vec!["-type", "d", "-name", "site-notifications-build*"],
            ),
            (
                "ticket_capture_dir",
                vec![
                    "-type",
                    "d",
                    "(",
                    "-name",
                    "ticket-poll-*",
                    "-o",
                    "-name",
                    "ticket-capture-*",
                    ")",
                ],
            ),
            (
                "runtime_bundle",
                vec![
                    "-type",
                    "f",
                    "(",
                    "-name",
                    "adguardhome-rootfs*.tar",
                    "-o",
                    "-name",
                    "*-rootfs-*.tar",
                    "-o",
                    "-name",
                    "dropbear-bundle*.tar",
                    "-o",
                    "-name",
                    "tailscale-bundle*.tar",
                    "-o",
                    "-name",
                    "site-notifier-bundle*.tar",
                    "-o",
                    "-name",
                    "source-site-notifier-*.tar",
                    "-o",
                    "-name",
                    "train-bot-bundle*.tar",
                    "-o",
                    "-name",
                    "satiksme-bot-bundle*.tar",
                    "-o",
                    "-name",
                    "subscription-bot-bundle*.tar",
                    "-o",
                    "-name",
                    "pixel-orchestrator-runtime-*.tar",
                    ")",
                ],
            ),
            (
                "debug_apk",
                vec![
                    "-type",
                    "f",
                    "(",
                    "-name",
                    "*-debug.apk",
                    "-o",
                    "-name",
                    "app-debug.apk",
                    "-o",
                    "-name",
                    "pixel-orchestrator*.apk",
                    ")",
                ],
            ),
            (
                "ticket_capture_file",
                vec![
                    "-type",
                    "f",
                    "(",
                    "-name",
                    "ticket-poll-*",
                    "-o",
                    "-name",
                    "ticket-capture-*",
                    "-o",
                    "-name",
                    "pixel-ticket-*capture*",
                    ")",
                ],
            ),
        ] {
            let mut argv = first.to_vec();
            argv.extend(tail);
            self.old_scan("tmp_artifact", detail, &self.tmp, &argv, &self.artifact_age);
        }
        for (category, detail, root, tail) in [
            (
                "app_cache",
                "runtime_artifact_cache",
                self.cache.join("runtime-artifacts"),
                vec!["(", "-type", "f", "-o", "-type", "d", ")"],
            ),
            (
                "app_cache",
                "staged_asset_temp",
                self.cache.clone(),
                vec!["-type", "f", "-name", "asset-stage-*"],
            ),
            (
                "app_cache",
                "cache_temp",
                self.cache.clone(),
                vec!["-type", "f", "-name", "*.tmp"],
            ),
            (
                "runtime_artifact",
                "runtime_manifest_artifacts",
                self.stack.join("conf/runtime/artifacts"),
                vec!["-type", "f"],
            ),
            (
                "runtime_artifact",
                "unreferenced_canonical_artifact",
                self.stack.join("conf/runtime/artifacts/sha256"),
                vec!["-type", "f"],
            ),
            (
                "termux_artifact",
                "site_notifier_artifact",
                notifier,
                vec!["-type", "f"],
            ),
            (
                "termux_artifact",
                "site_notifier_component_release",
                self.termux.join(
                    "telegram-train-app/workloads/site-notifications/.artifacts/component-releases",
                ),
                vec!["-type", "d"],
            ),
            (
                "termux_artifact",
                "orchestrator_runtime_local",
                local,
                vec!["-type", "d"],
            ),
            (
                "termux_artifact",
                "site_notifier_build_dir",
                self.termux.clone(),
                vec!["-type", "d", "-name", "site-notifications-build*"],
            ),
        ] {
            let mut argv = first.to_vec();
            argv.extend(tail);
            self.old_scan(category, detail, &root, &argv, &self.artifact_age);
        }
        self.scan(
            "support_bundle",
            "stale_private_support_archive",
            &self.cache.join("support-bundles"),
            &[
                "-mindepth",
                "1",
                "-maxdepth",
                "1",
                "-type",
                "f",
                "(",
                "-name",
                "pixel-stack-support-*.zip",
                "-o",
                "-name",
                "*.zip.tmp",
                ")",
                "-mmin",
                "+1440",
            ],
        );
        self.old_scan(
            "component_artifact",
            "component_manifest_artifacts",
            &self.stack.join("conf/runtime/components"),
            &[
                "-mindepth",
                "3",
                "-maxdepth",
                "3",
                "-type",
                "f",
                "-path",
                "*/artifacts/*",
            ],
            &self.artifact_age,
        );
        for name in [
            "train-bot",
            "satiksme-bot",
            "site-notifications",
            "subscription-bot",
        ] {
            self.old_scan(
                "release_dir",
                "non_current_release",
                &self.stack.join(format!("apps/{name}/releases")),
                &["-mindepth", "1", "-maxdepth", "1", "-type", "d"],
                &self.artifact_age,
            );
        }
        self.old_scan(
            "cleanup_report",
            "old_cleanup_report",
            &self.stack.join("logs/events"),
            &[
                "-mindepth",
                "1",
                "-maxdepth",
                "1",
                "-type",
                "f",
                "-name",
                "cleanup-*.json",
            ],
            &self.log_age,
        );
        for name in [
            "dnscrypt-static-test.log",
            "manual-dns-start.log",
            "manual-dns-stop.log",
            "pihole-rooted-boot.log",
            "pihole-rooted-runtime.log",
            "pihole-rooted-service-loop.log",
            "pixel-dns-start-manual.log",
            "vpn-break-glass.log",
        ] {
            self.old_scan(
                "legacy_log",
                "legacy_debug_log",
                &self.stack.join("logs"),
                &[
                    "-mindepth",
                    "1",
                    "-maxdepth",
                    "1",
                    "-type",
                    "f",
                    "-name",
                    name,
                ],
                &self.log_age,
            );
        }
    }
    fn retired_dns(&self) {
        if !self.retired_dns {
            return;
        }
        let root = self.stack.join("chroots/adguardhome");
        let logs = root.join("var/log/adguardhome");
        if effect(&["pidof", "AdGuardHome", "pihole-FTL", "dnsmasq"], true)
            || effect(
                &["pgrep", "-f", "[A]dGuardHome|[p]ihole-FTL|[p]ihole-rooted"],
                true,
            )
        {
            record(
                "SKIP",
                "retired_dns_log",
                bytes(&logs),
                &logs,
                "runtime_still_active",
            );
            return;
        }
        for path in [
            root.join("opt/adguardhome/work/data/querylog.json"),
            self.stack.join("state/adguardhome/work/data/querylog.json"),
        ] {
            self.delete("retired_dns_log", &path, "retired_query_history");
            self.delete(
                "retired_dns_log",
                &suffixed(&path, ".1"),
                "retired_query_history_rotation",
            );
        }
        for name in ["adguardhome-runtime.log", "adguardhome-service-loop.log"] {
            let path = self.stack.join("logs").join(name);
            self.delete(
                "retired_dns_log",
                &path,
                if name.contains("service-loop") {
                    "retired_service_loop_log"
                } else {
                    "retired_service_runtime_log"
                },
            );
            self.delete(
                "retired_dns_log",
                &suffixed(&path, ".1"),
                if name.contains("service-loop") {
                    "retired_service_loop_log_rotation"
                } else {
                    "retired_service_runtime_log_rotation"
                },
            );
        }
        for name in [
            "adguardhome-runtime.log",
            "adguardhome.log",
            "doh-server.log",
            "nginx-access.log",
            "nginx-error.log",
            "nginx-doh-access.log",
            "remote-watchdog.log",
            "remote-nginx-access.log",
            "remote-nginx-error.log",
            "remote-nginx-doh-access.log",
            "remote-nginx-dot-access.log",
            "doh-identity-web.log",
            "train-bot-cloudflared.log",
        ] {
            self.delete(
                "retired_dns_log",
                &logs.join(name),
                "retired_disabled_service_log",
            );
            self.delete(
                "retired_dns_log",
                &logs.join(format!("{name}.1")),
                "retired_disabled_service_log_rotation",
            );
        }
        self.scan(
            "retired_dns_log",
            "retired_start_attempt_log",
            &logs,
            &[
                "-mindepth",
                "1",
                "-maxdepth",
                "1",
                "-type",
                "f",
                "-name",
                "adguardhome-start-attempt-*.log",
            ],
        );
        for (dir, names) in [
            ("pihole", &["pihole.log", "FTL.log"][..]),
            (
                "pihole-rooted",
                &[
                    "doh-server.log",
                    "nginx-access.log",
                    "nginx-doh-access.log",
                    "nginx-error.log",
                ][..],
            ),
        ] {
            for name in names {
                for suffix in ["", ".1"] {
                    self.delete(
                        "retired_dns_log",
                        &self
                            .stack
                            .join(format!("chroots/pihole/var/log/{dir}/{name}{suffix}")),
                        "retired_disabled_service_log",
                    );
                }
            }
        }
    }
    fn run(&mut self) {
        if !self.frequent {
            self.artifacts()
        }
        self.scan(
            "action_result",
            "unconsumed_action_result",
            &self.stack.join("run/orchestrator-action-results"),
            &[
                "-mindepth",
                "1",
                "-maxdepth",
                "1",
                "-type",
                "f",
                "-name",
                "*.json",
                "-mmin",
                "+1440",
            ],
        );
        self.maintain_logs();
        self.maintain_db();
        if self.frequent {
            record(
                "OBSERVE",
                "superuser_log_db",
                self.db_bytes(),
                &self.db,
                "after_maintenance",
            );
            record(
                "OBSERVE",
                "runtime_log_total",
                self.log_total(true),
                &self.stack.join("logs"),
                "after_maintenance",
            );
        } else {
            self.retired_dns()
        }
    }
}
impl Drop for Cleanup {
    fn drop(&mut self) {
        self.rollback();
    }
}
pub fn main() -> i32 {
    install_signal_handlers();
    let mut cleanup = match Cleanup::parse() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("{e}");
            return 2;
        }
    };
    cleanup.run();
    let sig = INTERRUPTED.load(Ordering::Relaxed);
    if sig == 0 { 0 } else { 128 + sig }
}
