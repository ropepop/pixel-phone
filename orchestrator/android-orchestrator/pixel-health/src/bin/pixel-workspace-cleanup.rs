//! Existing local generated-output cleanup; no live device/state authority.
use std::{
    collections::{BTreeMap, BTreeSet},
    env, fs,
    io::{self, Write},
    path::{Component, Path, PathBuf},
    process::{self, Command},
    time::{SystemTime, UNIX_EPOCH},
};

fn stamp() -> String {
    Command::new("date")
        .arg("+%Y-%m-%dT%H:%M:%S%z")
        .output()
        .ok()
        .filter(|r| r.status.success())
        .map(|r| String::from_utf8_lossy(&r.stdout).trim().to_owned())
        .unwrap_or_default()
}
fn log(message: &str) {
    println!("[{}] cleanup_workspace: {message}", stamp());
}
fn log_path(prefix: &str, path: &Path) {
    let mut out = io::stdout().lock();
    write!(out, "[{}] cleanup_workspace: {prefix}", stamp()).expect("cleanup report write failed");
    out.write_all(path.as_os_str().as_encoded_bytes())
        .expect("cleanup report write failed");
    writeln!(out).expect("cleanup report write failed");
}
fn warn(message: &str) {
    eprintln!("[{}] artifact retention: {message}", stamp());
}
fn seconds(time: SystemTime) -> f64 {
    match time.duration_since(UNIX_EPOCH) {
        Ok(v) => v.as_secs_f64(),
        Err(e) => -e.duration().as_secs_f64(),
    }
}
fn window(value: &str) -> Result<f64, &'static str> {
    // Nonfinite windows can turn a preview into deletion of every generated file.
    let value = value.trim();
    let bytes = value.as_bytes();
    if bytes.iter().enumerate().any(|(i, b)| {
        *b == b'_'
            && (i == 0
                || i + 1 == bytes.len()
                || !bytes[i - 1].is_ascii_digit()
                || !bytes[i + 1].is_ascii_digit())
    }) {
        return Err("invalid retention window");
    }
    value
        .replace('_', "")
        .trim()
        .parse::<f64>()
        .ok()
        .filter(|v| v.is_finite())
        .ok_or("invalid retention window")
}
fn absolute(path: &Path) -> io::Result<PathBuf> {
    let mut result = PathBuf::new();
    let path = if path.is_absolute() {
        path.to_owned()
    } else {
        env::current_dir()?.join(path)
    };
    for part in path.components() {
        match part {
            Component::CurDir => {}
            Component::ParentDir => {
                result.pop();
            }
            other => result.push(other.as_os_str()),
        }
    }
    Ok(result)
}
fn old(meta: &fs::Metadata, cutoff: f64) -> bool {
    meta.modified().is_ok_and(|v| seconds(v) < cutoff)
}
fn under(path: &Path, roots: &[PathBuf]) -> bool {
    roots.iter().any(|root| path.starts_with(root))
}
// Preserve the previous bottom-up traversal and never follow child symlinks.
fn walk(root: &Path, output: &mut Vec<(PathBuf, Vec<PathBuf>, Vec<PathBuf>)>, noisy: bool) {
    let entries = match fs::read_dir(root) {
        Ok(v) => v,
        Err(e) => {
            if noisy {
                warn(&format!("unable to inspect {}: {e}", root.display()))
            }
            return;
        }
    };
    let mut dirs = Vec::new();
    let mut files = Vec::new();
    for entry in entries {
        match entry {
            Ok(entry) => {
                let path = entry.path();
                if path.is_dir() {
                    dirs.push(path.clone());
                    if !path.is_symlink() {
                        walk(&path, output, noisy)
                    }
                } else {
                    files.push(path)
                }
            }
            Err(e) => {
                if noisy {
                    warn(&format!("unable to inspect {}: {e}", root.display()))
                }
            }
        }
    }
    output.push((root.to_owned(), dirs, files));
}
fn remove_file(path: &Path) {
    if let Err(e) = fs::remove_file(path)
        && e.kind() != io::ErrorKind::NotFound {
            warn(&format!(
                "failed to remove stale path {}: {e}",
                path.display()
            ))
        }
}
fn retention(roots: &[PathBuf], cutoff: f64) {
    for root in roots {
        if root.is_symlink() || fs::symlink_metadata(root).is_err() {
            continue;
        }
        let mut first = Vec::new();
        walk(root, &mut first, true);
        let stale_dirs = first
            .iter()
            .filter_map(|(p, _, _)| {
                fs::symlink_metadata(p)
                    .ok()
                    .filter(|m| p != root && m.is_dir() && old(m, cutoff))
                    .map(|_| p.clone())
            })
            .collect::<Vec<_>>();
        let mut second = Vec::new();
        walk(root, &mut second, true);
        for (_, dirs, files) in second {
            for path in files.into_iter().chain(dirs) {
                match fs::symlink_metadata(&path) {
                    Ok(m) if !m.is_dir() && old(&m, cutoff) => remove_file(&path),
                    Err(e) if e.kind() != io::ErrorKind::NotFound => {
                        warn(&format!("unable to inspect {}: {e}", path.display()))
                    }
                    _ => {}
                }
            }
        }
        for path in stale_dirs {
            if let Err(e) = fs::remove_dir(&path)
                && !matches!(
                    e.kind(),
                    io::ErrorKind::NotFound | io::ErrorKind::DirectoryNotEmpty
                ) {
                    warn(&format!(
                        "failed to remove stale path {}: {e}",
                        path.display()
                    ))
                }
        }
    }
}
#[derive(Default)]
struct Scan {
    tracked: BTreeSet<PathBuf>,
    files: BTreeMap<PathBuf, u64>,
    dirs: BTreeSet<PathBuf>,
}
impl Scan {
    fn count(&self) -> usize {
        self.files.len() + self.dirs.len()
    }
    fn bytes(&self) -> u64 {
        self.files.values().copied().sum()
    }
}
fn scan(repo: &Path, roots: &[PathBuf], protected: &[PathBuf], cutoff: f64, tracked: bool) -> Scan {
    let mut result = Scan::default();
    for root in roots {
        if tracked
            && let Ok(output) = Command::new("git")
                .arg("-C")
                .arg(repo)
                .args(["ls-files", "--"])
                .arg(root.strip_prefix(repo).unwrap_or(root))
                .output()
                && output.status.success() {
                    for line in String::from_utf8_lossy(&output.stdout)
                        .lines()
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                    {
                        let path = repo.join(line);
                        if !under(&path, protected) {
                            result.tracked.insert(path);
                        }
                    }
                }
        if root.is_symlink() || !root.is_dir() {
            continue;
        }
        let mut walked = Vec::new();
        walk(root, &mut walked, false);
        for (current, dirs, files) in walked {
            if under(&current, protected) {
                continue;
            }
            for path in files
                .into_iter()
                .chain(dirs.into_iter().filter(|p| p.is_symlink()))
            {
                if under(&path, protected) {
                    continue;
                }
                if let Ok(m) = fs::symlink_metadata(&path)
                    && old(&m, cutoff) {
                        result.files.insert(path, m.len());
                    }
            }
            if current == *root || current.is_symlink() {
                continue;
            }
            if !fs::symlink_metadata(&current).is_ok_and(|m| old(&m, cutoff)) {
                continue;
            }
            let Ok(entries) = fs::read_dir(&current) else {
                continue;
            };
            let removable = entries.into_iter().all(|entry| {
                entry.is_ok_and(|entry| {
                    let path = entry.path();
                    !under(&path, protected)
                        && if entry.file_type().is_ok_and(|m| m.is_dir()) {
                            result.dirs.contains(&path)
                        } else {
                            result.files.contains_key(&path)
                        }
                })
            });
            if removable {
                result.dirs.insert(current);
            }
        }
    }
    result
}
fn usage(stderr: bool) {
    let message = "Usage: cleanup_workspace.sh [--dry-run | --check]\n\nConservatively manages generated workspace garbage under approved scratch,\noutput, and artifact roots.\n\nOptions:\n  --dry-run   Report stale candidates and tracked-path violations without deleting\n  --check     Fail if stale garbage exists or tracked files appear in managed roots\n  -h, --help  Show help";
    if stderr {
        eprintln!("{message}")
    } else {
        println!("{message}")
    }
}
fn workspace(args: &[String]) -> Result<i32, String> {
    let repo = absolute(Path::new(args.first().ok_or("repository is required")?))
        .map_err(|e| e.to_string())?;
    let mut mode = "prune";
    for arg in &args[1..] {
        match arg.as_str() {
            "--dry-run" => mode = "dry-run",
            "--check" => mode = "check",
            "-h" | "--help" => {
                usage(false);
                return Ok(0);
            }
            _ => {
                eprintln!("Unsupported argument: {arg}");
                usage(true);
                return Ok(2);
            }
        }
    }
    let inside = Command::new("git")
        .arg("-C")
        .arg(&repo)
        .args(["rev-parse", "--is-inside-work-tree"])
        .output()
        .map_err(|_| "cleanup_workspace.sh requires git")?;
    if !inside.status.success() {
        log("git metadata unavailable; skipping generated-output cleanup");
        return Ok(0);
    }
    let hours = window(&env::var("PIXEL_ARTIFACT_RETENTION_HOURS").unwrap_or_else(|_| "72".into()))
        .map_err(str::to_owned)?;
    let cutoff = seconds(SystemTime::now()) - hours * 3600.;
    let mut roots = [
        ".codex-tmp",
        "output",
        ".artifacts",
        "orchestrator/.artifacts",
    ]
    .map(|p| repo.join(p))
    .to_vec();
    if let Ok(workloads) = fs::read_dir(repo.join("workloads")) {
        let mut workloads = workloads
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.is_dir() && !p.is_symlink())
            .collect::<Vec<_>>();
        workloads.sort();
        for workload in workloads {
            roots.extend([workload.join(".artifacts"), workload.join("output")]);
        }
    }
    let protected = [repo.join("ops/evidence"), repo.join("state/browser-use")];
    let before = scan(&repo, &roots, &protected, cutoff, true);
    let mut tracked = before.tracked.iter().collect::<Vec<_>>();
    tracked.sort_by(|a, b| {
        a.as_os_str()
            .as_encoded_bytes()
            .cmp(b.as_os_str().as_encoded_bytes())
    });
    for path in tracked {
        log_path("tracked_violation path=", path);
    }
    let mut files = before.files.iter().collect::<Vec<_>>();
    files.sort_by(|(a, _), (b, _)| {
        a.as_os_str()
            .as_encoded_bytes()
            .cmp(b.as_os_str().as_encoded_bytes())
    });
    for (path, size) in files {
        log_path(
            &format!("stale_candidate kind=file bytes={size} path="),
            path,
        );
    }
    let mut dirs = before.dirs.iter().collect::<Vec<_>>();
    dirs.sort_by(|a, b| {
        a.as_os_str()
            .as_encoded_bytes()
            .cmp(b.as_os_str().as_encoded_bytes())
    });
    for path in dirs {
        log_path("stale_candidate kind=dir bytes=0 path=", path);
    }
    let summary = format!(
        "mode={mode} tracked={} stale={} stale_bytes={}",
        before.tracked.len(),
        before.count(),
        before.bytes()
    );
    if mode != "prune" {
        log(&summary);
        return Ok(i32::from(
            mode == "check" && (!before.tracked.is_empty() || before.count() > 0),
        ));
    }
    if !before.tracked.is_empty() {
        log(&summary);
        log("refusing to prune managed roots while tracked-path violations exist");
        return Ok(1);
    }
    if before.count() == 0 {
        log(&summary);
        log("no stale generated output found");
        return Ok(0);
    }
    retention(&roots, seconds(SystemTime::now()) - hours * 3600.);
    let after = scan(
        &repo,
        &roots,
        &protected,
        seconds(SystemTime::now()) - hours * 3600.,
        false,
    );
    log(&format!(
        "mode={mode} tracked={} stale_before={} stale_after={} reclaimed_bytes={}",
        before.tracked.len(),
        before.count(),
        after.count(),
        i128::from(before.bytes()) - i128::from(after.bytes())
    ));
    if after.count() > 0 {
        log("stale generated output remains after pruning");
        return Ok(1);
    }
    Ok(0)
}
fn main() {
    let args = env::args().skip(1).collect::<Vec<_>>();
    let result = match args.first().map(String::as_str) {
        Some("workspace") => workspace(&args[1..]),
        Some("retention") => {
            if args.len() < 3 {
                Ok(0)
            } else {
                match window(&args[1]) {
                    Ok(hours) => {
                        let roots = args[2..]
                            .iter()
                            .filter(|s| !s.is_empty())
                            .map(PathBuf::from)
                            .collect::<Vec<_>>();
                        retention(&roots, seconds(SystemTime::now()) - hours * 3600.);
                        Ok(0)
                    }
                    Err(_) => {
                        warn("invalid retention window; skipping stale artifact cleanup");
                        Ok(0)
                    }
                }
            }
        }
        _ => Err("expected workspace or retention action".into()),
    };
    match result {
        Ok(code) => process::exit(code),
        Err(message) => {
            eprintln!("{message}");
            process::exit(2)
        }
    }
}
