# Pixel capture demand and phone-control decisions moved to Rust

The existing packaged native library owns fixed one-frame-per-second cadence,
expiry/coalescing of one demand opportunity, exact relay demand parsing and
admission, deferred proof-stream cleanup, phone-local context replacement,
registration evidence freshness and agreement, exact-context lookup, clock
projection and publication readiness. Java/Kotlin keep the existing synchronized
calls, socket/helper delivery, framework clocks, flow notifications and actual
input/capture/publication effects. A rejected or unavailable helper delivery
never advances the accepted generation. No timer, retry, worker or fallback was
added; no physical action was replayed.

`run-capture-parity.sh` freezes the actual dd41627 owners from Git, rather than
copying the new policy into an oracle. It passed 22,500 cadence transitions and
3,207 strict-wire/admission/cleanup comparisons through the actual host JNI.
Tests include clock overflow/backward movement, expired demand before a throwing
capture call, proof coalescence, helper failure, exact generation fences and
integer spelling. The wire comparison caught and fixed exponent/plus spelling
compatibility by reusing the existing native JSON integer lexer.

`run-phone-control-parity.sh` likewise freezes the previous owner. 47,005 context/evidence/clock comparisons passed. Existing
publisher coroutine tests remain active, including input-service disappearance,
network publication failure and coalescing. Synthetic Android JNI coverage was
added to the existing no-input fixture. Exact new APK installation and affected
real browser/device journeys remain required before acceptance.

Active callers: root H.264 helper owns cadence effects; TicketWebSocket's exact
session owns one demand admission state; TicketStreamService owns deferred cleanup
and phone-control state; the single control publisher owns transport and renewal.
Private anchors exist only in process memory. Native bridge errors do not include
serialized observations. Android capture, Accessibility, MediaCodec, root input
and service/coroutine lifecycle remain necessary platform adapters.
