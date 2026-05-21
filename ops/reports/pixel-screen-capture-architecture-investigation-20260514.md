# Pixel Screen Capture Architecture Investigation - 2026-05-14

## Scope

This investigation traces the Pixel ticket screen path from capture on the phone through public browser rendering. It now also records the cleanup pass that followed: hardware-only capture, marker-only control-code completion, one-second GOP, and adaptive FPS.

Finishing criteria:

- Identify the active production capture path.
- Trace one frame from Pixel screen to browser canvas.
- Identify current timing gates and slow-path safeguards.
- Find unused or legacy capture paths that still add complexity.
- Verify current source contracts with focused tests where practical.

## Current Active Path

The production path is rooted hardware H.264:

1. `ticket_remote` sees a browser video viewer.
2. `ticket_remote` counts that viewer and starts the private relay to the phone.
3. The relay opens two private WebSockets to the Pixel service:
   - `/api/v1/session` for control and status
   - `/api/v1/stream` for video
4. The Pixel service starts `TicketRootHardwareH264CaptureEngine`.
5. The engine launches a rooted `app_process` helper: `TicketRootHardwareH264CaptureMain`.
6. The helper repeatedly captures the screen through hidden Android `ScreenCapture`, draws the captured bitmap into a `MediaCodec` H.264 input surface, drains H.264 Annex B output, and writes it to stdout.
7. Kotlin reads stdout, parses H.264 access units, wraps each access unit in the `tsf2` frame envelope, and sends it to video WebSocket clients.
8. `ticket_remote` receives those binary frames, rejects stale or wrong-epoch frames, rewrites the timestamp to public wall-clock time, and fans out only fresh frames to browser video sessions.
9. The browser decodes H.264 with WebCodecs, then draws decoded frames to a canvas.

## Capture Engine Details

Current phone profile:

- Capture mode: `root_hardware_h264`
- Transport: `hardware-h264-annexb`
- Codec: `avc1.42C028`
- Target width: `720`
- Encoder FPS: `8`
- Steady capture-loop FPS: `4`
- Burst capture-loop FPS: `8`
- Burst hold: `6 seconds`
- Bitrate: `1,200,000`
- Keyframe interval: `1000 ms`
- Source top crop: `200` phone pixels
- Frame envelope: `tsf2`
- Helper: rooted `app_process` running `TicketRootHardwareH264CaptureMain`

Important behavior:

- This is not a direct display-to-encoder stream. It is a polling loop.
- Every frame calls Android hidden `ScreenCapture`.
- The returned hardware buffer is wrapped as a bitmap.
- The bitmap is cropped/scaled and drawn to the encoder input surface.
- `MediaCodec` outputs H.264.
- The encoder is configured for the burst rate, but the helper loop chooses the current target cadence each frame. Startup, viewer joins, keyframe recovery, and control-code activity extend an 8 FPS burst window. Once the burst window expires, the helper slows to 4 FPS without restarting the encoder.
- The normal GOP is now one second. Explicit keyframe commands are still used for new viewers, decoder recovery, generated-result marker delivery, and cleanup verification.

## Delay Sources

The main per-frame phone-side cost is:

- Hidden screen capture wait, with a 350 ms hard timeout.
- Bitmap wrap and optional visibility sampling.
- GPU canvas draw into the encoder input surface.
- Encoder output drain.
- Kotlin read and H.264 access-unit parsing.
- `tsf2` envelope allocation and per-client WebSocket writes.

The intended phone cadence is about one frame every 125 ms during burst and about one frame every 250 ms during steady state. Any capture/draw/encode cycle over the current target budget reduces real FPS. Phone health reports `hardwareH264.fps` as this current adaptive target.

Relay/browser delay gates:

- `ticket_remote` drops frames whose estimated visual age is over 750 ms.
- Pixel cached keyframes are considered fresh for 750 ms.
- Phone-side slow video writes are logged after 100 ms.
- Phone-side pending per-client video frame is stale after 150 ms.
- Phone-side slow clients are closed after 250 ms blocked write time.
- Public relay also keeps only newest pending video for slow browser clients.
- Browser drops old epochs, old sequences, and stale decode/render frames, then asks for a fresh keyframe.

## Complexity Findings

### 1. The production capture path is lighter than before, but still screenshot-polling

The active helper uses hardware H.264 encoding, but each frame still starts as a full hidden screen capture. That means CPU/GPU/memory churn remains higher than a true display surface streamed directly into the encoder.

This is the biggest processing-cost source.

### 2. The stream no longer runs as all-keyframe by default

The previous 100 ms keyframe interval made the 8 FPS stream effectively all-keyframe. The cleanup pass changed the interval to 1000 ms and kept explicit keyframe requests for the moments that need recovery or a fresh visible marker.

### 3. Old capture paths were removed from production source

The cleanup pass removed the obsolete production paths that made the capture stack hard to reason about:

- Cropped PNG result helper
- Phone-side image/base64 result delivery
- FFmpeg/raw production capture engine and helper wiring
- Raw surface/screencap probes used by normal control-code cleanup

The remaining `ffmpeg` health surface, where present, is only a removed/unavailable compatibility stub.

### 4. Control-code completion is now single-marker

The phone sends one success signal for generated control codes:

- `ticket_state_event(ticketState="generated_result")` with request id, value, stream epoch, frame sequence, and minimum frame sequence

`ticket_remote` completes the private requester result from this marker and dedupes by request id. Legacy `control_code_frame_ready` parsing remains in the public relay only for backward compatibility with older phone builds; the phone no longer sends it.

### 5. There are two browser viewers to keep in sync

There is still a Pixel-local viewer embedded in `TicketStreamService.kt` and the public `ticket_remote` viewer in `workloads/ticket-remote/internal/web/static/app.js`. Both implement stream WebSocket handling, H.264 decode, frame freshness, keyframe requests, and recovery behavior.

This duplication makes stale behavior more likely.

### 6. The public relay is still useful

The relay adds a network hop, but it also keeps the phone private, centralizes auth, fans out one phone stream to multiple viewers, drops stale frames, and hides the Pixel from the public internet. Removing it would likely shift complexity into phone-side auth/tunnel/security instead of reducing total risk.

## Implemented Cleanup Pass

Completed in this pass:

- Removed dead phone PNG result capture and image/base64 result plumbing.
- Removed FFmpeg/raw production capture wiring from the ticket service.
- Made generated-code success marker-only on the phone.
- Kept public relay legacy parsing for old `control_code_frame_ready` messages.
- Made browser and phone-local result UI numeric-only.
- Changed hardware H.264 keyframe interval from 100 ms to 1000 ms.
- Added adaptive 8 FPS burst / 4 FPS steady capture with six-second burst hold.
- Added helper `burst` command and `fps_target` health parsing.

Still worth considering later:

- Unify local and public viewer logic.
   - Generate both from one stream client source or remove the local viewer if it is no longer needed.

- Consider a deeper root capture experiment only after cleanup.
   - The largest win would be avoiding per-frame bitmap capture.
   - A true display-to-encoder surface path may be possible only through more hidden/root Android APIs, and it is riskier than the current helper.

## Verification

Completed for the cleanup pass:

- Public relay tests passed: `go test ./internal/phone ./internal/web`
- Public browser asset syntax passed: `node --check internal/web/static/app.js`
- Focused phone tests passed: `TicketH264AnnexBParserTest`, `TicketStreamServiceSourceTest`, and `TicketStreamSizingTest`
- Full phone unit/build check was run after the focused pass.

Limitations:

- The shared `Your Chrome` profile reached the ticket login page, so authenticated browser proof was not collected for this investigation.
- Direct ADB health probing from this shell hung and was stopped. No live Pixel `/api/v1/health` sample was collected in this run.
