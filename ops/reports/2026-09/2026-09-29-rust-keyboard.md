# Rust keyboard acceptance, 29 September 2026

The bounded native keyboard helper is deployed from clean source `2dc9085`
(implementation `f90b9d7`) in release `rust-keyboard-20260929`. The normal
`ticket_screen` deployment completed successfully and verified all five runtime
assets. The installed helper is root-owned, mode 0755, and has SHA-256
`e889e48ed8a79ce8ee2c867392dbf35e328d81be8311546fee1cb7f6fb2dc11e`.
The tested APK SHA-256 is
`e37a74e9bba29ab77135a9ee4938b29589e4135006b0b6a5228072c22ac9c837`.
The previous installed APK and this exact APK are retained in the ignored
`output/rust-migration` directory for rollback and comparison.

## Supporting checks

- 36 old C/new Rust process scenarios and parent/child cancellation passed.
- 13 fresh runtime-installer tests, the standard APK build/test run, the test
  APK build, packaging, formatting and deployment-wrapper checks passed.
- On the actual Pixel, the packaged Rust helper replaced two synthetic values
  in an androidTest-only EditText. Both actual resulting texts matched. The
  field suppressed the software keyboard, the root script verified foreground
  ownership, and the helper process stopped. The complete test took 3.723 s.
- The test helper file and test APK were removed. ViVi returned to the front.
  Android's test runner stopped the target process; the existing component
  restart entrypoint restored the supervisor and verified fresh assets.

## Real signed-in browser journeys

The existing Brave session reached the live ticket after deployment without
signing in again. Two separate code-generation requests used synthetic inspector
numbers. Each produced the requester-only result, then its visible close control
returned to the live stream. No result pixels or generated code values were
retained. Phone and durable state independently confirmed both results:

| Request | Phone duration | Browser receipt | Durable cleanup |
| --- | ---: | --- | --- |
| `control_code_a535b7c6-4f04-46e6-b7df-2e8730853f23` | 17,153 ms | `exact_phone_result_picture_presented` | succeeded, capture acknowledged, cleanup not pending |
| `control_code_11f88c64-3c39-4c5f-bde0-0c2330b7d1c1` | 16,638 ms | `exact_phone_result_picture_presented` | succeeded, capture acknowledged, cleanup not pending |

The durable final reason was `phone_visual_cleanup_complete` for each request.
There were zero duplicate results and the panel protection lease was inactive.
Reloading the same signed-in page returned to a live, usable ticket.

The user then explicitly requested real registration. One request was submitted
for the currently displayed unregistered ticket. Durable action
`ticket_65140a5a-0b0f-4ba5-b61b-56c26cb1d15a` completed from 09:17:52.337179 to
09:17:56.707652 UTC: `register_current`, `succeeded`, `activation_proven`,
`activated_current`, `ticket_action_registered`. After reload, the browser
reported the opened ticket registered and visually confirmed, with registration
disabled and the live picture restored. No second registration was attempted.

## Cleanup and limits

All task-owned Ticket tabs were closed around 09:21 UTC. No keyboard helper or
test file remained, ViVi was foreground, and the panel lease was inactive. The
existing bounded page-warmth owner initially kept one encoder alive. A root
process check at 09:47 UTC, after the subsequent health-test/service lifecycle,
found neither `TicketH264CaptureMain` nor `ticket-root-keyboard`. This proves the
observed final quiet state, not natural expiry of the 30-minute warmth timer.

The helper grew from 11,712 to 308,272 bytes because of the Rust standard library.
No size improvement is claimed. The two live durations establish successful
round trips, not a statistically meaningful tail-latency comparison. Brightness,
iPhone presentation and the unchanged capture/gesture implementations were not
migrated in this slice. There was no phone reboot test.
