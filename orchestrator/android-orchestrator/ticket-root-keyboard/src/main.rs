use std::io::{self, Read};
use std::os::fd::AsRawFd;
use std::os::unix::process::CommandExt;
use std::process::{Child, Command, ExitCode, Stdio};
use std::time::{Duration, Instant};

const DEADLINE: Duration = Duration::from_millis(2700);
const POLL: Duration = Duration::from_millis(10);
const KEY_NAMES: [&str; 10] = [
    "KEYCODE_0",
    "KEYCODE_1",
    "KEYCODE_2",
    "KEYCODE_3",
    "KEYCODE_4",
    "KEYCODE_5",
    "KEYCODE_6",
    "KEYCODE_7",
    "KEYCODE_8",
    "KEYCODE_9",
];

#[cfg(not(any(target_os = "linux", target_os = "android")))]
compile_error!("ticket-root-keyboard requires Linux or Android");

// Linux/Android ABI calls missing from std. The helper is single-threaded; its
// pre-exec hook performs only these async-signal-safe calls, never allocation.
unsafe extern "C" {
    fn prctl(option: i32, ...) -> i32;
    fn getppid() -> i32;
    fn fcntl(fd: i32, command: i32, ...) -> i32;
}

fn bind_parent(expected: i32) -> io::Result<()> {
    // PR_SET_PDEATHSIG = 1; SIGKILL = 9. Check after installing the signal to
    // close the race where the owning root command dies during process launch.
    if unsafe { prctl(1, 9usize, 0usize, 0usize, 0usize) } != 0 {
        return Err(io::Error::last_os_error());
    }
    if unsafe { getppid() } != expected {
        return Err(io::Error::from_raw_os_error(10)); // ECHILD; no allocation after fork.
    }
    Ok(())
}

struct Secret<const N: usize>([u8; N]);

impl<const N: usize> Drop for Secret<N> {
    fn drop(&mut self) {
        for byte in &mut self.0 {
            unsafe { std::ptr::write_volatile(byte, 0) };
        }
    }
}

fn coordinate(value: &str) -> Option<i32> {
    // Match strtol's decimal whitespace/sign handling, including -0.
    let value = value.trim_start_matches([' ', '\t', '\n', '\r', '\x0b', '\x0c']);
    let number = value.parse::<i64>().ok()?;
    (0..=10000).contains(&number).then_some(number as i32)
}

fn coordinates() -> Result<(Option<(i32, i32)>, (i32, i32)), u8> {
    let mut points = [None; 4];
    let mut args = std::env::args_os().skip(1);
    while let Some(name) = args.next() {
        let index = match name.to_str() {
            Some("--open-x") => 0,
            Some("--open-y") => 1,
            Some("--input-x") => 2,
            Some("--input-y") => 3,
            _ => return Err(40),
        };
        points[index] = Some(
            args.next()
                .and_then(|arg| arg.to_str().and_then(coordinate))
                .ok_or(40)?,
        );
    }
    let open = match (points[0], points[1]) {
        (Some(x), Some(y)) => Some((x, y)),
        (None, None) => None,
        _ => return Err(40),
    };
    Ok((open, (points[2].ok_or(40)?, points[3].ok_or(40)?)))
}

fn settle(duration: Duration, deadline: Instant) -> Result<(), u8> {
    let remaining = deadline.saturating_duration_since(Instant::now());
    std::thread::sleep(duration.min(remaining));
    if remaining < duration {
        Err(54)
    } else {
        Ok(())
    }
}

// Every error, read failure and deadline must kill AND reap the owned command.
// Child alone does not kill on Drop.
struct OwnedChild(Child);

impl Drop for OwnedChild {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn spawn(command: &mut Command) -> io::Result<OwnedChild> {
    let parent = std::process::id() as i32;
    unsafe {
        command.pre_exec(move || bind_parent(parent));
    }
    command.spawn().map(OwnedChild)
}

fn run_input(command: &mut Command, deadline: Instant, failure: u8) -> Result<(), u8> {
    let mut child = spawn(command).map_err(|_| failure)?;
    loop {
        match child.0.try_wait() {
            Ok(Some(status)) => {
                return if status.success() {
                    Ok(())
                } else {
                    Err(failure)
                };
            }
            Ok(None) => {}
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => return Err(failure),
        }
        settle(POLL, deadline)?;
    }
}

fn tap(point: (i32, i32), deadline: Instant, failure: u8) -> Result<(), u8> {
    run_input(
        Command::new("/system/bin/input").arg0("input").args([
            "tap",
            &point.0.to_string(),
            &point.1.to_string(),
        ]),
        deadline,
        failure,
    )
}

fn keyboard_hidden(deadline: Instant) -> Result<(), u8> {
    const SHOWN: &[u8] = b"mInputShown=true";
    const HIDDEN: &[u8] = b"mImeWindowVis=0";
    let mut child = spawn(
        Command::new("/system/bin/dumpsys")
            .arg0("dumpsys")
            .arg("input_method")
            .stdout(Stdio::piped()),
    )
    .map_err(|_| 46)?;
    let mut output = child.0.stdout.take().ok_or(46)?;
    let fd = output.as_raw_fd();
    // F_GETFL = 3, F_SETFL = 4, O_NONBLOCK = 2048 on Linux/Android.
    let flags = unsafe { fcntl(fd, 3) };
    if flags < 0 || unsafe { fcntl(fd, 4, flags | 2048) } < 0 {
        return Err(46);
    }
    let mut buffer = Secret([0u8; 4096 + SHOWN.len() + 1]);
    let mut carry = 0;
    let mut shown = false;
    let mut hidden = false;
    let mut exited: Option<bool> = None;
    loop {
        loop {
            if Instant::now() >= deadline {
                return Err(54);
            }
            match output.read(&mut buffer.0[carry..4096 + SHOWN.len()]) {
                Ok(0) => break,
                Ok(count) => {
                    let total = carry + count;
                    // dumpsys is text. Match the old bounded C string scans.
                    let text_end = buffer.0[..total]
                        .iter()
                        .position(|&byte| byte == 0)
                        .unwrap_or(total);
                    shown |= buffer.0[..text_end]
                        .windows(SHOWN.len())
                        .any(|part| part == SHOWN);
                    hidden |= buffer.0[..text_end]
                        .windows(HIDDEN.len())
                        .any(|part| part == HIDDEN);
                    carry = total.min(SHOWN.len());
                    buffer.0.copy_within(total - carry..total, 0);
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) if error.kind() == io::ErrorKind::WouldBlock => break,
                Err(_) => return Err(46),
            }
        }
        if let Some(success) = exited {
            return if !success {
                Err(46)
            } else if shown && !hidden {
                Err(47)
            } else {
                Ok(())
            };
        }
        match child.0.try_wait() {
            Ok(Some(status)) => exited = Some(status.success()),
            Ok(None) => settle(POLL, deadline)?,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => return Err(46),
        }
    }
}

fn run() -> Result<(), u8> {
    let parent = unsafe { getppid() };
    if parent <= 1 || bind_parent(parent).is_err() {
        return Err(55);
    }
    let deadline = Instant::now() + DEADLINE;
    let (open, input) = coordinates()?;
    let mut digits = Secret([0; 32]);
    let count = io::stdin().read(&mut digits.0[..31]).map_err(|_| 41)?;
    let length = digits.0[..count]
        .iter()
        .position(|byte| matches!(byte, 0 | b'\r' | b'\n'))
        .unwrap_or(count);
    if !(2..=8).contains(&length) || !digits.0[..length].iter().all(u8::is_ascii_digit) {
        return Err(41);
    }
    if let Some(point) = open {
        tap(point, deadline, 45)?;
        settle(Duration::from_millis(250), deadline)?;
    }
    tap(input, deadline, 44)?;
    settle(Duration::from_millis(300), deadline)?;
    keyboard_hidden(deadline)?;

    // InputManager's existing virtual keyboard does not create an InputDevice or
    // trigger a display/brightness handoff. Never send a form-activation key.
    let mut command = Command::new("/system/bin/input");
    command.arg0("input");
    command.args(["keyevent", "--delay", "20", "KEYCODE_MOVE_END"]);
    command.args(["KEYCODE_DEL"; 8]);
    command.args(
        digits.0[..length]
            .iter()
            .map(|byte| KEY_NAMES[(byte - b'0') as usize]),
    );
    drop(digits);
    run_input(&mut command, deadline, 51)?;
    settle(Duration::from_millis(100), deadline)
}

fn main() -> ExitCode {
    ExitCode::from(run().err().unwrap_or(0))
}
