# Native restart and weekly cleanup decisions

Source candidate based on Pixel checkpoint `9d1aa21`; not yet installed.

Rust owns rapid-restart window/count/backoff/reset and cleanup day admission,
instant comparison and exact-alarm permission decision. Existing clocks,
SupervisorEngine effects/sleep, Java device calendar and AlarmManager calls are
the same adapters and owners. No cleanup execution or phone action is added.

The runnable `pixel-health/tests/run-maintenance-parity.sh` loads the exact
previous owners from Git and uses existing Gradle/JNI test tasks. Its21,600
backoff cases cover reset, strict window equality, backward clocks and Int/Long
overflow;13,460 calendar/alarm cases cover Riga/UTC/New York/Lord Howe/Apia,
gaps/overlaps/skipped dates, nanoseconds, Java's full year range and SDK/permission
boundaries. Root's final targeted Gradle run exited0 with BUILD SUCCESSFUL in6s;
durable comparison output is collected again in the combined candidate checks.

Installed acceptance must run the existing disposable SupervisorEngine fixture,
prove the exact persisted delay/count and unchanged live configuration, execute
the bounded backoff/calendar JNI fixture, and observe the actual scheduled
Monday03:00 alarm after normal app resume. The changed combined package still
requires browser→durable→phone→visible result and natural final-quiet acceptance.
No installed or physical-device pass is implied by the source checks.
