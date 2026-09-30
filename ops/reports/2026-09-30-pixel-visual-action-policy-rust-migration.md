# Pixel visual-action policy migrated

Rust now owns request admission, target/terminal-view compatibility, retained
terminal and finalization projection, exact journal reconciliation, one-use capture
recovery admission, card/tab selection, two-observation agreement and consensus,
dispatch freshness, negative redetection proof, and registration identity/context
binding. The existing Android executor alone owns captures, journal commits/read-
back, navigation, input readiness and physical actions. No new physical attempt,
retry or worker exists. A retained uncertain/terminal record cannot create a new
registration. All existing Java-facing methods and persisted DTO fields remain.

The actual dd41627 source is the test-only oracle, generated into ignored build
output by `run-visual-policy-parity.sh`. Direct old/new host JNI checks passed:
274 request/type/clock/string-boundary cases; 25,956 journal/transition/terminal
comparisons; 61,200 observation/consensus/selection/proof comparisons. Existing
app tests passed after the extraction. A UTF-16 split-surrogate comparison caught
an output formatting difference; four bounded Java string slices remain in the
serialization adapter to preserve the former wire representation exactly.

Active callers were traced: TicketVisualActionExecutor consumes admitted requests,
consensus, proof binding and journals; TicketStreamService consumes latest/recent
card and context decisions; TicketPhoneControlState and its publisher consume
agreement and readiness; worker result delivery consumes terminal/finalization
projections. Private observations pass only within the phone process. Native
errors never include the serialized private observation or journal.

Synthetic installed-JNI coverage was added without capture, navigation, network
or input effects. Exact final APK/source identity and real nonactivating browser,
command/phone/returned result/reconnect/final quiet remain required before this
candidate is accepted.
