# Pixel encoder, startup recovery and visibility policy migration

Source acceptance pending the coherent maintenance/artifact/encoder checkpoint;
no phone changes occurred while extracting this owner.

The rooted hardware helper's startup primer now keeps a value-state array in
Java and executes its priming spacing/budget, first-key forwarding, sibling
suppression, boundary-drain/fallback and terminal decisions in Rust. Java keeps
one boundary frame reference for the real platform output pipe. Codec input
timestamp/order/capacity validation and generation rollover are native; the
bounded queue retains actual InputStage references and identity semantics.
Startup recovery grants (including nullable/negative ages, parked warm grant,
first useful frame grace) and visible-versus-dark pixel classification are native.
Restart callback, surface/codec and clock effects stay at existing sites.

The fixture freezes the actual `dd41627` Java/Kotlin owners. Complete comparison
results: startup 52,000; codec-input and generation 10,256; recovery 75,816;
visibility 789. State, frame-reference selection and exception type/message are
compared across operations, clock extremes/overflow, invalid stages and inputs,
capacity, full recovery grant combinations and representative pixel distributions.
Existing startup recovery and teardown tests also pass. Exact counts are saved
in `output/rust-migration/pixel-artifact-encoder-parity-first.txt`.

The installed-APK root `app_process` fixture now executes priming cadence,
first key forwarding, boundary frame identity, codec input admission, generation
rollover and dark/visible classification using that APK's library. It loads both
test and installed product classes, verifies the exact packaged library SHA-256,
and performs no screen capture, touch or release mutation. This and actual browser
stream/helper journeys are still required for final device acceptance.

NDK 27.1 supplies MediaCodec input/output APIs; that is not a missing-API excuse.
The necessary compatibility binding remains Android's private ScreenCapture
reflection/result, HardwareBuffer/Bitmap/ColorSpace and Canvas-to-Surface path.
The active owner inventory distinguishes that platform effect from remaining
pure business decisions. Final physical emitted-light/touch proof is separate.
