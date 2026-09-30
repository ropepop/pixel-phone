# Ticket root keyboard

The Android ARM64 Rust executable replaces the native C helper at the same
`ticket-root-keyboard` APK asset and `/data/local/pixel-stack/bin/pixel-ticket-root-keyboard`
runtime path. No Rust dependencies, extra daemon, JNI bridge or new phone protocol.

It accepts the existing `--input-x`, `--input-y` and optional paired
`--open-x`, `--open-y` arguments, reads 2–8 decimal digits from stdin, and runs
Android InputManager's existing key-event batch. Exit codes, keyboard refusal,
settling intervals, the 2.7-second deadline and parent-death termination are
preserved. It cannot submit the form or grant phone-action authority; the
existing Kotlin action lane, keyboard lease, fresh visual proof, physical-touch
preemption and panel protection still own that decision.

## Build

Use installed Rust with `rustup target add aarch64-linux-android` and the existing
Android NDK. `../../scripts/android/build_ticket_root_keyboard.sh` builds the
locked, offline package using the NDK's Android API 29 linker. Gradle's existing
`buildTicketRootKeyboard` task packages it and tracks the Rust source/manifest.
The ordinary APK build and `ticket_screen` deployment stay authoritative.

## Process contract check

The test invokes the real executable with harmless Android command stand-ins.
Run only in a disposable Linux Docker container; it refuses an existing
`/system` tree and does not run on Android. From this directory:

```sh
docker run --rm --init --network none --cpus=1 --memory=1g --memory-swap=1g \
  -v "$PWD:/input:ro" -w /work rust:1.94-bookworm \
  bash -c 'cp -R /input/. /work/ && cargo build --locked --offline --release && python3 tests/process_contract.py target/release/ticket-root-keyboard'
```

It checks the exact command order and arguments, digit/coordinate refusals,
split pipe output, visible-keyboard refusal, subprocess failures and timeouts,
child reaping, settling intervals, and owner-death cancellation. An optional
second executable runs the identical independently specified contract against
the previous C implementation. `--init` is required: the helper intentionally
rejects a caller whose process ID is 1.

These are process-boundary checks. Live acceptance separately requires a signed-in
browser control-code journey, fresh Pixel visual proof, returned result, keyboard
restoration, exact command cleanup, and unchanged physical-touch protection.

The Android test APK additionally contains `TicketRootKeyboardInstrumentedTest`.
It opens only its own numeric field with software-keyboard display disabled,
loads the packaged asset into an app-private temporary file, proves the fixture
owns the focused window, and checks actual root InputManager delivery of synthetic
digits and helper termination. Build it with `./gradlew :app:assembleDebugAndroidTest`
from the Android project. Run only during an owned phone-test session, then restore
the prior foreground and validate Ticket; the fixture is absent from the product APK.
