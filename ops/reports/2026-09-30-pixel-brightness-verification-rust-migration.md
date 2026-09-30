# Active brightness verification policy migration

This extracts bounded pure decisions; raw-touch/window arbitration and actual
settings/sysfs/power mutations retain their existing owner. Actual emitted-light
and physical touch/power targets remain unverified in this remote session.

`NativeTouchBrightness` now delegates legacy Android system-value and panel-value
conversion, target/restore lenient verification, visible-panel eligibility,
remote panel-state fallback, fallback percentage, unchanged Android-state choice
and panel-sleep classification to `pixel-health::touch_brightness`. Null fields,
single-precision float comparisons, NaN/infinity raw bits and JVM wrapping
integer subtraction/multiplication/absolute-value behavior are preserved.
Android retains Float parsing/serialization compatibility, Settings/sysfs reads
and writes, hardware capability queries, existing settle/retry effects and the
coupled physical touch/visible-window state machine.

Frozen `9d1aa21` helper functions and ScreenBrightnessControl produce 240,192
complete parity comparisons, with results and exact exceptions checked across
field combinations and numeric extremes. Existing touch runtime/controller and
brightness tests pass. The final combined source check contains 645 tests in
96 suites, zero failures/errors, with exact counts preserved in
`output/rust-migration/pixel-native-policy-final-test-summary.txt`.

The installed Android fixture exercises synthetic verification and reads the
actual panel without changing brightness, power, touch or saved settings. It
explicitly marks physical light unverified. Its actual execution remains pending
the exact clean final APK; do not count source tests as device acceptance.
